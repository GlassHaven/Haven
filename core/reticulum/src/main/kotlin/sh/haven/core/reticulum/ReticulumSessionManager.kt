package sh.haven.core.reticulum

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact

private const val TAG = "ReticulumSessionManager"

/**
 * Manages active Reticulum/rnsh sessions across the app.
 * Parallel to [sh.haven.core.ssh.SshSessionManager] but without
 * reconnect, SFTP, or session manager (tmux/zellij) logic.
 *
 * Uses the coroutines-first [ReticulumTransport] interface backed by
 * the native rnsh-kt bridge.
 */
@Singleton
class ReticulumSessionManager @Inject constructor(
    private val transport: ReticulumTransport,
    private val forwardServer: ReticulumForwardServer,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val destinationHash: String,
        val reticulumSession: ReticulumSession? = null,
        val sftpSession: sh.haven.core.reticulum.sftp.ReticulumSftpSession? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("rns-session-mgr")
    )

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    /**
     * Register a new session. Returns the generated sessionId.
     */
    fun registerSession(profileId: String, label: String, destinationHash: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                destinationHash = destinationHash,
            ))
        }
        return sessionId
    }

    /**
     * Connect a registered session. Suspend function — call from a
     * coroutine on Dispatchers.IO or similar.
     *
     * Initialises Reticulum if needed, resolves the destination, creates
     * a shell session. Throws on failure.
     */
    suspend fun connectSession(
        sessionId: String,
        configDir: String,
        host: String,
        port: Int,
        ifacNetname: String? = null,
        ifacNetkey: String? = null,
        socketDialer: ((String, Int, Int) -> java.net.Socket)? = null,
    ) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        // A failure here must be visible in the sessions StateFlow, not only
        // in the thrown exception. `registerSession` marks the entry CONNECTING
        // and `activeSessions` counts CONNECTING, so a session whose connect
        // throws without updating status stays a permanent ghost: it is
        // rendered as a live tab and keeps the stack-shutdown idle check from
        // firing (#601, where a failed duplicate-tab connect "is still counted
        // as active"). The catch rethrows so callers keep their current
        // handling; ConnectionsViewModel's removeSession-on-error then also
        // finds the session in ERROR rather than CONNECTING.
        val identityHash: String
        val shellSession: RnshShellSession
        try {
            Log.w(TAG, "initReticulum: host=${LogRedact.of(host)} port=$port ifac=${ifacNetname != null} tunnel=${socketDialer != null}")
            // Init Reticulum (idempotent — safe to call multiple times)
            identityHash = transport.init(configDir, host, port, ifacNetname, ifacNetkey, socketDialer)
            Log.w(TAG, "initReticulum OK, identity=$identityHash")

            // Open shell session (resolves destination + Link + handshake)
            Log.w(TAG, "Opening session to ${session.destinationHash}...")
            shellSession = transport.openSession(
                destinationHash = session.destinationHash,
            )
        } catch (e: Exception) {
            updateStatus(sessionId, SessionState.Status.ERROR)
            throw e
        }

        // Stash the shell session BEFORE publishing CONNECTED. The sessions
        // StateFlow drives TerminalViewModel.syncSessions, whose RNS branch
        // calls createTerminalSession exactly once per emission and `continue`s
        // on null — so an observer that sees CONNECTED must already be able to
        // find the shell, or the tab is never created and the session lingers
        // CONNECTED with no terminal (the #601 "no tab, but counted as active"
        // ghost). Updating after the stash makes "CONNECTED ⇒ shell available"
        // hold for every observer.
        shellSessions[sessionId] = shellSession
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
            ))
        }
        Log.w(TAG, "Session $sessionId connected")
    }

    // Temporary stash for shell sessions between connect and terminal creation
    private val shellSessions = mutableMapOf<String, RnshShellSession>()

    /**
     * Create a [ReticulumSession] for a connected session.
     * Returns the session, or null if not ready.
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): ReticulumSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) {
            Log.w(TAG, "createTerminalSession($sessionId): not attached, status=${session.status}")
            return null
        }
        if (session.reticulumSession != null) {
            Log.w(TAG, "createTerminalSession($sessionId): not attached, terminal already present")
            return null
        }

        val shellSession = shellSessions.remove(sessionId)
        if (shellSession == null) {
            // #601: the CONNECTED-with-no-tab ghost lands here. Log.w (not d)
            // so the reason survives the release build's log stripping — the
            // reporter's log showed a fully-connected duplicate and nothing
            // explaining the missing tab.
            Log.w(TAG, "createTerminalSession($sessionId): not attached, no stashed shell session")
            return null
        }

        val reticulumSession = ReticulumSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            shellSession = shellSession,
            onDataReceived = onDataReceived,
            onDisconnected = { _ ->
                Log.d(TAG, "Session $sessionId disconnected")
                updateStatus(sessionId, SessionState.Status.DISCONNECTED)
            },
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(reticulumSession = reticulumSession))
        }

        return reticulumSession
    }

    /**
     * Detach a terminal session without closing the underlying rnsh session.
     */
    fun detachTerminalSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        session.reticulumSession?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(reticulumSession = null))
        }
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.reticulumSession == null
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        shellSessions.remove(sessionId)
        // Tear down any port forwards if this was the profile's last session.
        if (_sessions.value.values.none { it.profileId == session.profileId }) {
            forwardServer.stopAllForProfile(session.profileId)
        }
        scope.launch {
            try {
                session.reticulumSession?.close()
                session.sftpSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
            shutdownStackIfIdle()
        }
    }

    /** Send [text] as UTF-8 to the rnsh session's input — sendInput contract, see #366. */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("No Reticulum session: $sessionId")
        val rns = session.reticulumSession
            ?: throw IllegalStateException("Reticulum session $sessionId has no live transport")
        rns.sendInput(text.toByteArray(Charsets.UTF_8))
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        forwardServer.stopAllForProfile(profileId)
        scope.launch {
            toRemove.forEach { session ->
                try {
                    session.reticulumSession?.close()
                    session.sftpSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
            shutdownStackIfIdle()
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        shellSessions.clear()
        forwardServer.stopAll()
        scope.launch {
            snapshot.forEach { session ->
                try {
                    session.reticulumSession?.close()
                    session.sftpSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
            shutdownStackIfIdle()
        }
    }

    /**
     * Drop the process-wide Reticulum stack once nothing is using it.
     *
     * The stack is a process singleton whose mode is fixed at start-up: a
     * shared instance owns the interfaces on behalf of every app using it, so
     * a gateway profile cannot add to one (#588). Nothing used to release it,
     * so that choice outlived the session that made it and the next connect
     * was refused until the app was force-stopped. Reported against v5.87.59:
     * "the banner keeps appearing even after disconnecting the session and
     * just closing Haven makes possible to choose again".
     *
     * Re-reading [_sessions] here rather than trusting the caller's snapshot
     * narrows, but does not close, the race with a connect that starts while
     * this is in flight; both run on [scope]'s IO dispatcher with no lock
     * between them.
     */
    private suspend fun shutdownStackIfIdle() {
        if (_sessions.value.isNotEmpty()) return
        try {
            transport.closeAll()
            Log.d(TAG, "last session gone: Reticulum stack shut down")
        } catch (e: Exception) {
            Log.e(TAG, "stack shutdown failed", e)
        }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    /**
     * Lazily open (or reuse) a persistent SFTP session for [profileId] over the
     * profile's connected Link, mirroring SSH's `openSftpSession`. The actual
     * `sftp-server` Link + handshake happen on first file op, not here. Returns
     * null if the profile has no CONNECTED session. Synchronized so concurrent
     * resolvers don't double-open.
     */
    @Synchronized
    fun openSftpSessionForProfile(
        profileId: String,
        sftpServerPath: String,
    ): sh.haven.core.reticulum.sftp.ReticulumSftpSession? {
        val state = _sessions.value.values.firstOrNull {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        } ?: return null
        state.sftpSession?.let { return it }
        val session = sh.haven.core.reticulum.sftp.ReticulumSftpSession(
            transport, state.destinationHash, sftpServerPath,
        )
        _sessions.update { map ->
            val cur = map[state.sessionId] ?: return@update map
            map + (state.sessionId to cur.copy(sftpSession = session))
        }
        return session
    }

    fun getProfileStatus(profileId: String): SessionState.Status? {
        val statuses = _sessions.value.values
            .filter { it.profileId == profileId }
            .map { it.status }
        if (statuses.isEmpty()) return null
        return when {
            SessionState.Status.CONNECTED in statuses -> SessionState.Status.CONNECTED
            SessionState.Status.CONNECTING in statuses -> SessionState.Status.CONNECTING
            SessionState.Status.ERROR in statuses -> SessionState.Status.ERROR
            else -> SessionState.Status.DISCONNECTED
        }
    }
}
