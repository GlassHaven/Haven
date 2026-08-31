package sh.haven.core.ssh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshSessionAttacher"

/**
 * Attaches one tmux/zellij/screen session — by name — as a terminal session
 * riding an already-live (or in-flight) SSH connection to a profile.
 *
 * This is the single implementation of the "another tab on the same host"
 * step that was previously duplicated with diverging behaviour:
 *  - `ConnectionsViewModel.restorePreviousSessions` dialed a whole new SSH
 *    connection per extra session (second auth round, second tunnel flow);
 *  - `TerminalViewModel.addSshTabForProfile` reused the live client (the
 *    canonical model — one SSH connection carrying several multiplexer
 *    sessions).
 * Both now call [ensureAttached]. The workspace launcher (Phase 2) calls it
 * directly per planned session name, without going through the UI command bus.
 *
 * Deliberately NOT here: dialing a connection from cold. Establishing the
 * first connection to a profile stays on the existing connect paths
 * (interactive `ConnectProfile` for prompts/FIDO/TOFU, `connectSshSilent`
 * for group launch) — [ensureAttached] returns [Result.NoLiveConnection]
 * and the caller picks a dial path. Duplicating the auth matrix headlessly
 * here is exactly the kind of copy this class exists to delete.
 *
 * Attached sessions get their terminal emulator automatically: the
 * app-scoped `SshTerminalEmulatorOwner` provides it via
 * [SshSessionManager.terminalAttachmentProvider] inside
 * [SshSessionManager.openShellAndAwaitReady], and `TerminalViewModel`
 * adopts it as a tab from the sessions flow — so callers need no UI plumbing.
 */
@Singleton
class SshSessionAttacher @Inject constructor(
    private val sessionManager: SshSessionManager,
    private val connectionRepository: ConnectionRepository,
    private val preferencesRepository: UserPreferencesRepository,
) {
    sealed interface Result {
        /**
         * A live terminal for this (profile, session name) already exists —
         * nothing created. Launching a workspace twice, or over tabs the user
         * already opened, reuses instead of duplicating.
         */
        data class AlreadyLive(val sessionId: String) : Result

        /** New terminal session attached and shell confirmed live. */
        data class Attached(val sessionId: String) : Result

        /**
         * No CONNECTED or in-flight connection to the profile — the caller
         * must dial first (interactively or silently), then retry.
         */
        data object NoLiveConnection : Result

        data class Failed(val message: String) : Result
    }

    /**
     * Ensure a live terminal session for [profileId] attached to the
     * multiplexer session [sessionName] (null = let the session-manager
     * command create a fresh one). Reuses the profile's live [SshClient];
     * waits out an in-flight connect via
     * [SshSessionManager.awaitReusableClient] so a restore never races a
     * dial it could ride instead.
     */
    suspend fun ensureAttached(profileId: String, sessionName: String?): Result {
        val profile = connectionRepository.getById(profileId)
            ?: return Result.Failed("profile not found")

        if (sessionName != null) {
            val wanted = SessionManager.sanitizeSessionName(sessionName)
            sessionManager.getSessionsForProfile(profileId)
                .firstOrNull { s ->
                    s.chosenSessionName?.let { SessionManager.sanitizeSessionName(it) } == wanted &&
                        sessionManager.isLiveTerminal(s.sessionId)
                }
                ?.let { return Result.AlreadyLive(it.sessionId) }
        }

        val client = sessionManager.awaitReusableClient(profileId)
            ?: return Result.NoLiveConnection

        val sessionId = sessionManager.registerSession(profileId, profile.label, client)
        try {
            sessionManager.storeReuseConfig(sessionId, profileId, resolveSessionManager(profile))
            if (sessionName != null) {
                sessionManager.setChosenSessionName(sessionId, sessionName)
            }
            val outcome = withContext(Dispatchers.IO) {
                sessionManager.openShellAndAwaitReady(sessionId)
            }
            return when (outcome) {
                is ShellOutcome.Ready -> {
                    sessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
                    persistOpenSessionNames(profileId)
                    Result.Attached(sessionId)
                }
                is ShellOutcome.ShellClosed -> {
                    sessionManager.removeSession(sessionId)
                    Result.Failed(
                        "Shell closed (exit ${outcome.exitStatus}) — is your session manager " +
                            "(tmux/zellij/screen) installed on this host?",
                    )
                }
                is ShellOutcome.Failed -> {
                    sessionManager.removeSession(sessionId)
                    Result.Failed(outcome.reason.ifBlank { "Failed to open the remote shell" })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureAttached($profileId, $sessionName) failed", e)
            sessionManager.removeSession(sessionId)
            return Result.Failed(e.message ?: "attach failed")
        }
    }

    /**
     * The multiplexer sessions currently live on the host for [profileId],
     * or null when unknowable (no live client, no session manager
     * configured, or the list command failed). One-shot ground truth for
     * the workspace launcher's reconciliation: a saved name missing from
     * this list will be *recreated* by the attach (`new-session -A`), and
     * the launcher reports that instead of silently handing back an empty
     * session.
     */
    suspend fun listRemoteSessions(profileId: String): List<String>? {
        val profile = connectionRepository.getById(profileId) ?: return null
        val manager = resolveSessionManager(profile)
        val listCmd = manager.listCommand ?: return null
        val client = sessionManager.getSshClientForProfile(profileId) ?: return null
        return try {
            val result = client.execCommand(listCmd)
            if (result.exitStatus == 0) {
                SessionManager.parseSessionList(manager, result.stdout)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "listRemoteSessions($profileId) failed: ${e.message}")
            null
        }
    }

    /**
     * Persist every open multiplexer session name for the profile
     * (pipe-delimited [ConnectionProfile.lastSessionName]) — same
     * bookkeeping `finishConnect` does on a primary dial, so "remembered
     * sessions" (reconnect + old-workspace fallback) stay current.
     */
    private suspend fun persistOpenSessionNames(profileId: String) {
        val names = sessionManager.getSessionsForProfile(profileId)
            .filter { it.sessionManager != SessionManager.NONE }
            .mapNotNull { it.chosenSessionName }
            .map { SessionManager.sanitizeSessionName(it) }
            .distinct()
        if (names.isEmpty()) return
        connectionRepository.getById(profileId)?.let {
            connectionRepository.save(it.copy(lastSessionName = names.joinToString("|")))
        }
    }

    /** Profile override when valid, else the global preference. */
    private suspend fun resolveSessionManager(profile: ConnectionProfile): SessionManager {
        val override = profile.sessionManager
        if (override != null) {
            try {
                return SessionManager.valueOf(override)
            } catch (_: IllegalArgumentException) {
                // Unknown value persisted by an older build — use the preference.
            }
        }
        return when (preferencesRepository.sessionManager.first()) {
            UserPreferencesRepository.SessionManager.NONE -> SessionManager.NONE
            UserPreferencesRepository.SessionManager.TMUX -> SessionManager.TMUX
            UserPreferencesRepository.SessionManager.ZELLIJ -> SessionManager.ZELLIJ
            UserPreferencesRepository.SessionManager.SCREEN -> SessionManager.SCREEN
            UserPreferencesRepository.SessionManager.BYOBU -> SessionManager.BYOBU
            UserPreferencesRepository.SessionManager.HERDR -> SessionManager.HERDR
        }
    }
}
