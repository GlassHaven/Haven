package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshSessionManager"

/**
 * Manages active SSH sessions across the app.
 * Sessions are identified by a unique sessionId (UUID).
 * Multiple sessions may share the same profileId (multi-tab).
 */
@Singleton
class SshSessionManager @Inject constructor(
    private val hostKeyVerifier: HostKeyVerifier,
) {

    data class PortForwardInfo(
        val ruleId: String,
        val type: PortForwardType,
        val bindAddress: String,
        val bindPort: Int,
        val targetHost: String,
        val targetPort: Int,
        val actualBoundPort: Int = bindPort,
    )

    enum class PortForwardType { LOCAL, REMOTE }

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val client: SshClient,
        val shellChannel: ChannelShell? = null,
        val terminalSession: TerminalSession? = null,
        val sftpChannel: ChannelSftp? = null,
        val connectionConfig: ConnectionConfig? = null,
        val sessionManager: SessionManager = SessionManager.NONE,
        val chosenSessionName: String? = null,
        val activeForwards: List<PortForwardInfo> = emptyList(),
    ) {
        enum class Status { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    /** Background executor for disconnect I/O so callers on main thread don't block. */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
            it.status == SessionState.Status.CONNECTING ||
            it.status == SessionState.Status.RECONNECTING
        }

    val hasActiveSessions: Boolean
        get() = activeSessions.isNotEmpty()

    /**
     * Register a new session. Returns the generated sessionId (UUID).
     */
    fun registerSession(profileId: String, label: String, client: SshClient): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                client = client,
            ))
        }
        return sessionId
    }

    fun storeConnectionConfig(sessionId: String, config: ConnectionConfig, sessionMgr: SessionManager) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(connectionConfig = config, sessionManager = sessionMgr))
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    /**
     * Open a shell channel on the SSH session and store it in the session state.
     * Must be called after the SSH session is connected.
     */
    fun openShellForSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        val channel = session.client.openShellChannel()
        attachShellChannel(sessionId, channel)
    }

    fun attachShellChannel(sessionId: String, channel: ChannelShell) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(shellChannel = channel))
        }
    }

    /**
     * Create a [TerminalSession] for a connected session that has a shell channel.
     * Returns the session, or null if the session/channel doesn't exist.
     * The [onDataReceived] callback delivers SSH output bytes.
     * Call [TerminalSession.start] after wiring up the emulator.
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): TerminalSession? {
        val session = _sessions.value[sessionId] ?: return null
        val channel = session.shellChannel ?: return null
        val pendingCmd = buildSessionManagerCommand(sessionId, session.sessionManager)
        val termSession = TerminalSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            channel = channel,
            client = session.client,
            onDataReceived = onDataReceived,
            onDisconnected = { cleanExit ->
                if (cleanExit) {
                    Log.d(TAG, "Session $sessionId exited cleanly — not reconnecting")
                    updateStatus(sessionId, SessionState.Status.DISCONNECTED)
                } else {
                    Log.d(TAG, "Session $sessionId disconnected unexpectedly")
                    val sess = _sessions.value[sessionId]
                    if (sess?.connectionConfig != null) {
                        ioExecutor.execute { attemptReconnect(sessionId) }
                    } else {
                        updateStatus(sessionId, SessionState.Status.DISCONNECTED)
                    }
                }
            },
            pendingCommand = pendingCmd,
        )
        attachTerminalSession(sessionId, termSession)
        return termSession
    }

    /**
     * Whether a session has a shell channel ready but no terminal session yet.
     */
    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.shellChannel != null &&
            session.terminalSession == null
    }

    /**
     * Open an SFTP channel for a profile. Finds any connected session for that profile
     * and opens (or reuses) an SFTP channel on it.
     * Returns the channel, or null if no session for this profile is connected.
     */
    fun openSftpForProfile(profileId: String): ChannelSftp? {
        val session = _sessions.value.values
            .filter { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            .firstOrNull() ?: return null
        // Reuse existing channel if still connected
        session.sftpChannel?.let { if (it.isConnected) return it }
        val channel = session.client.openSftpChannel()
        _sessions.update { map ->
            val existing = map[session.sessionId] ?: return@update map
            map + (session.sessionId to existing.copy(sftpChannel = channel))
        }
        return channel
    }

    fun attachTerminalSession(sessionId: String, terminalSession: TerminalSession) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(terminalSession = terminalSession))
        }
    }

    /**
     * Detach a terminal session without closing the shell channel.
     * Called when TerminalViewModel is cleared (Activity destroyed) but the
     * process stays alive via the foreground service. Allows a new
     * TerminalViewModel to re-create a TerminalSession on the same channel.
     */
    fun detachTerminalSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        session.terminalSession?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(terminalSession = null))
        }
    }

    /**
     * Attempt to reconnect a dropped session with exponential backoff.
     * Called on the ioExecutor thread.
     */
    private fun attemptReconnect(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        val config = session.connectionConfig ?: return
        val sessionMgr = session.sessionManager

        updateStatus(sessionId, SessionState.Status.RECONNECTING)

        var delayMs = RECONNECT_INITIAL_DELAY_MS
        for (attempt in 1..RECONNECT_MAX_ATTEMPTS) {
            // Check if session was removed (user manually disconnected)
            if (_sessions.value[sessionId] == null) {
                Log.d(TAG, "Reconnect cancelled for $sessionId — session removed")
                return
            }

            Log.d(TAG, "Reconnect attempt $attempt/$RECONNECT_MAX_ATTEMPTS for $sessionId (delay ${delayMs}ms)")
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return
            }

            // Check again after sleep
            if (_sessions.value[sessionId] == null) return

            try {
                val newClient = SshClient()
                val hostKeyEntry = newClient.connectBlocking(config)

                // Silent TOFU on reconnect: auto-accept new, abort on change
                val hkResult = runBlocking { hostKeyVerifier.verify(hostKeyEntry) }
                when (hkResult) {
                    is HostKeyResult.Trusted -> { /* matches — continue */ }
                    is HostKeyResult.NewHost -> {
                        runBlocking { hostKeyVerifier.accept(hkResult.entry) }
                    }
                    is HostKeyResult.KeyChanged -> {
                        Log.w(TAG, "Host key changed during reconnect for $sessionId — aborting")
                        newClient.disconnect()
                        updateStatus(sessionId, SessionState.Status.ERROR)
                        return
                    }
                }

                // Update session state with new client
                _sessions.update { map ->
                    val existing = map[sessionId] ?: return@update map
                    map + (sessionId to existing.copy(client = newClient))
                }

                // Open shell and reconnect terminal
                val channel = newClient.openShellChannel()
                attachShellChannel(sessionId, channel)

                // Swap channel in the terminal session and restart reader
                val termSession = _sessions.value[sessionId]?.terminalSession
                val pendingCmd = buildSessionManagerCommand(sessionId, sessionMgr)
                if (pendingCmd != null) {
                    termSession?.pendingCommand = pendingCmd
                }
                termSession?.reconnect(channel, newClient)

                // Restore port forwards
                val forwards = _sessions.value[sessionId]?.activeForwards.orEmpty()
                if (forwards.isNotEmpty()) {
                    // Clear current list, re-apply will add them back
                    _sessions.update { map ->
                        val existing = map[sessionId] ?: return@update map
                        map + (sessionId to existing.copy(activeForwards = emptyList()))
                    }
                    applyPortForwards(sessionId, forwards)
                }

                updateStatus(sessionId, SessionState.Status.CONNECTED)
                Log.d(TAG, "Reconnected $sessionId on attempt $attempt")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt failed for $sessionId: ${e.message}")
                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            }
        }

        Log.d(TAG, "Reconnect failed after $RECONNECT_MAX_ATTEMPTS attempts for $sessionId")
        updateStatus(sessionId, SessionState.Status.DISCONNECTED)
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute { tearDown(session) }
    }

    fun getSession(sessionId: String): SessionState? = _sessions.value[sessionId]

    // --- Profile-level helpers ---

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    fun getProfileStatus(profileId: String): SessionState.Status? {
        val statuses = _sessions.value.values
            .filter { it.profileId == profileId }
            .map { it.status }
        if (statuses.isEmpty()) return null
        // Priority: CONNECTED > RECONNECTING > CONNECTING > ERROR > DISCONNECTED
        return when {
            SessionState.Status.CONNECTED in statuses -> SessionState.Status.CONNECTED
            SessionState.Status.RECONNECTING in statuses -> SessionState.Status.RECONNECTING
            SessionState.Status.CONNECTING in statuses -> SessionState.Status.CONNECTING
            SessionState.Status.ERROR in statuses -> SessionState.Status.ERROR
            else -> SessionState.Status.DISCONNECTED
        }
    }

    fun getConnectionConfigForProfile(profileId: String): Pair<ConnectionConfig, SessionManager>? {
        val session = _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.connectionConfig != null }
            ?: return null
        return session.connectionConfig!! to session.sessionManager
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { tearDown(it) }
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            snapshot.forEach { tearDown(it) }
            SshClient.clearDnsCache()
        }
    }

    fun setChosenSessionName(sessionId: String, name: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(chosenSessionName = name))
        }
    }

    /**
     * Activate port forwards on a connected session.
     * Each rule is applied independently; failures are logged but don't block others.
     */
    fun applyPortForwards(sessionId: String, rules: List<PortForwardInfo>) {
        val session = _sessions.value[sessionId] ?: return
        val activated = mutableListOf<PortForwardInfo>()

        for (rule in rules) {
            try {
                when (rule.type) {
                    PortForwardType.LOCAL -> {
                        val actualPort = session.client.setPortForwardingL(
                            rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort,
                        )
                        activated.add(rule.copy(actualBoundPort = actualPort))
                        Log.d(TAG, "Port forward activated: L ${rule.bindAddress}:$actualPort -> ${rule.targetHost}:${rule.targetPort}")
                    }
                    PortForwardType.REMOTE -> {
                        session.client.setPortForwardingR(
                            rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort,
                        )
                        activated.add(rule)
                        Log.d(TAG, "Port forward activated: R ${rule.bindAddress}:${rule.bindPort} -> ${rule.targetHost}:${rule.targetPort}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to activate port forward ${rule.ruleId}: ${e.message}")
            }
        }

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(activeForwards = existing.activeForwards + activated))
        }
    }

    /**
     * Remove a single port forward from a connected session.
     */
    fun removePortForward(sessionId: String, forward: PortForwardInfo) {
        val session = _sessions.value[sessionId] ?: return
        try {
            when (forward.type) {
                PortForwardType.LOCAL -> session.client.delPortForwardingL(forward.bindAddress, forward.actualBoundPort)
                PortForwardType.REMOTE -> session.client.delPortForwardingR(forward.bindPort)
            }
            Log.d(TAG, "Port forward removed: ${forward.type} ${forward.bindAddress}:${forward.bindPort}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove port forward ${forward.ruleId}: ${e.message}")
        }
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                activeForwards = existing.activeForwards.filter { it.ruleId != forward.ruleId },
            ))
        }
    }

    /**
     * Build the session manager command string for a given session, or null if none.
     * Uses the user-chosen session name if set, otherwise a deterministic name.
     */
    private fun buildSessionManagerCommand(sessionId: String, manager: SessionManager): String? {
        val commandTemplate = manager.command ?: return null
        val session = _sessions.value[sessionId]
        val sessionName = session?.chosenSessionName
            ?: "haven-${session?.profileId?.take(8) ?: sessionId.take(8)}"
        return commandTemplate(sessionName)
    }

    companion object {
        private const val RECONNECT_MAX_ATTEMPTS = 5
        private const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
    }

    private fun tearDown(session: SessionState) {
        try { session.terminalSession?.close() } catch (e: Exception) {
            Log.e(TAG, "tearDown: terminalSession.close() failed", e)
        }
        try {
            if (session.sftpChannel?.isConnected == true) {
                session.sftpChannel.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tearDown: sftpChannel.disconnect() failed", e)
        }
        try {
            if (session.shellChannel?.isConnected == true) {
                session.shellChannel.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tearDown: shellChannel.disconnect() failed", e)
        }
        try { session.client.disconnect() } catch (e: Exception) {
            Log.e(TAG, "tearDown: client.disconnect() failed", e)
        }
        // Zero out key material so it doesn't linger in heap
        (session.connectionConfig?.authMethod as? ConnectionConfig.AuthMethod.PrivateKey)
            ?.keyBytes?.fill(0)
    }
}
