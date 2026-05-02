package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import sh.haven.core.data.terminal.ScrollbackRing
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Soft cap on per-session terminal scrollback exposed to the agent
 * transport. The agent's `read_terminal_scrollback` tool returns
 * (a slice of) this buffer; everything older than 256 KiB rolls off.
 * Compose's terminal still keeps its own visual scrollback — this is a
 * separate, app-scoped mirror so the agent can read what the user sees
 * without going through activity-scoped state.
 */
private const val AGENT_SCROLLBACK_CAPACITY_BYTES = 256 * 1024

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

    enum class PortForwardType { LOCAL, REMOTE, DYNAMIC }

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
        val sessionCommandOverride: String? = null,
        val chosenSessionName: String? = null,
        val postLoginCommand: String? = null,
        val postLoginBeforeSessionManager: Boolean = false,
        val activeForwards: List<PortForwardInfo> = emptyList(),
        /** Session ID of the jump host session, if this connection goes through one. */
        val jumpSessionId: String? = null,
        /**
         * True when this session was opened automatically as a tunnel for
         * another profile (VNC/RDP/SMB-over-SSH-forward), as opposed to
         * being opened directly by the user. Combined with an empty
         * [tunnelDependents], signals the session can be torn down when
         * its last consumer disconnects (#121, KoriKraut).
         */
        val tunnelOpened: Boolean = false,
        /**
         * Profile IDs of non-SSH connections (VNC, RDP, SMB) currently
         * using this SSH session as their tunnel. When this empties on a
         * [tunnelOpened] session, the session is torn down by
         * [releaseTunnelDependent].
         */
        val tunnelDependents: Set<String> = emptySet(),
    ) {
        enum class Status { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    /**
     * Per-session app-scoped mirror of recent SSH stdout bytes for the
     * agent transport's `read_terminal_scrollback` tool. Allocated lazily
     * when the first terminal session is created on a session, dropped
     * in [removeSession] so closed sessions don't leak. We keep this in
     * a separate map rather than [SessionState] so the data class shape
     * stays unchanged for unrelated consumers.
     */
    private val agentScrollback = ConcurrentHashMap<String, ScrollbackRing>()

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

    fun storeConnectionConfig(
        sessionId: String,
        config: ConnectionConfig,
        sessionMgr: SessionManager,
        sessionCommandOverride: String? = null,
        postLoginCommand: String? = null,
        postLoginBeforeSessionManager: Boolean = true,
    ) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                connectionConfig = config,
                sessionManager = sessionMgr,
                sessionCommandOverride = sessionCommandOverride,
                postLoginCommand = postLoginCommand,
                postLoginBeforeSessionManager = postLoginBeforeSessionManager,
            ))
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
        val pendingCmds = buildPendingCommands(sessionId, session.sessionManager, session.postLoginCommand, session.postLoginBeforeSessionManager)
        // Mirror raw SSH stdout into an app-scoped ring buffer so the
        // agent transport can read the same bytes the emulator sees,
        // without reaching into activity-scoped state.
        val ring = agentScrollback.computeIfAbsent(sessionId) {
            ScrollbackRing(AGENT_SCROLLBACK_CAPACITY_BYTES)
        }
        val mirroringCallback: (ByteArray, Int, Int) -> Unit = { data, off, len ->
            ring.append(data, off, len)
            onDataReceived(data, off, len)
        }
        val termSession = TerminalSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            channel = channel,
            client = session.client,
            onDataReceived = mirroringCallback,
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
            pendingCommands = pendingCmds,
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
     * Whether a session already has a terminal session attached (e.g. after Activity recreation).
     * The TerminalViewModel should reattach to it rather than creating a new one.
     */
    fun hasExistingTerminalSession(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.terminalSession != null
    }

    /** Get the existing terminal session for reattach after Activity recreation. */
    fun getExistingTerminalSession(sessionId: String): TerminalSession? {
        return _sessions.value[sessionId]?.terminalSession
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

    /**
     * Find a connected [SshClient] for this profile. Used by file-transfer
     * code paths that need shell exec (SCP, shell-ls browsing) — i.e. the
     * legacy transport for servers without an SFTP subsystem.
     */
    fun getSshClientForProfile(profileId: String): SshClient? {
        return _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.client
    }

    /**
     * Create an [ScpClient] bound to the connected JSch session for
     * [profileId]. Each call produces a new façade — the session itself is
     * shared, but SCP opens a fresh exec channel per operation so there is
     * no caching to do here.
     */
    fun openScpForProfile(profileId: String): ScpClient? {
        val client = getSshClientForProfile(profileId) ?: return null
        val jschSession = client.jschSession ?: return null
        return ScpClient(jschSession)
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
     * Request reconnection of a specific session.
     * No-op if session is already RECONNECTING or CONNECTED, or has no connection config.
     * Used by [NetworkMonitor] to trigger immediate reconnect on network change.
     */
    fun requestReconnect(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        if (session.status == SessionState.Status.RECONNECTING) return
        if (session.status == SessionState.Status.CONNECTED) return
        if (session.connectionConfig == null) return
        Log.d(TAG, "requestReconnect: $sessionId (status=${session.status})")
        ioExecutor.execute { attemptReconnect(sessionId) }
    }

    /**
     * Request reconnection of all sessions that are DISCONNECTED or ERROR.
     * Used by [SshConnectionService] when network becomes available.
     */
    fun requestReconnectAll() {
        _sessions.value.forEach { (id, session) ->
            if (session.status in listOf(SessionState.Status.DISCONNECTED, SessionState.Status.ERROR)
                && session.connectionConfig != null
            ) {
                requestReconnect(id)
            }
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

        // Proactively mark dependent sessions (that use this as jump host) as RECONNECTING
        // so the UI shows the reconnecting indicator immediately instead of waiting for keepalive timeout
        val dependents = _sessions.value.values.filter { it.jumpSessionId == sessionId }
        for (dep in dependents) {
            if (dep.status == SessionState.Status.CONNECTED) {
                Log.d(TAG, "Marking dependent session ${dep.sessionId} as RECONNECTING (jump host $sessionId dropped)")
                updateStatus(dep.sessionId, SessionState.Status.RECONNECTING)
            }
        }

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
                // If this session goes through a jump host, get its proxy
                val jumpSid = _sessions.value[sessionId]?.jumpSessionId
                val proxy = if (jumpSid != null) {
                    val jumpSession = _sessions.value[jumpSid]
                    if (jumpSession?.status != SessionState.Status.CONNECTED) {
                        Log.w(TAG, "Jump host $jumpSid not connected — cannot reconnect $sessionId")
                        delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                        continue
                    }
                    createProxyJump(jumpSid)
                } else null

                val newClient = SshClient()
                val hostKeyEntry = newClient.connectBlocking(config, proxy = proxy)

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
                val sess = _sessions.value[sessionId]
                val pendingCmds = buildPendingCommands(sessionId, sessionMgr, sess?.postLoginCommand, sess?.postLoginBeforeSessionManager ?: false)
                if (pendingCmds.isNotEmpty()) {
                    termSession?.setPendingCommands(pendingCmds)
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
        // Cascade: disconnect any sessions that use this one as a jump host
        val dependents = _sessions.value.values.filter { it.jumpSessionId == sessionId }
        _sessions.update { it - sessionId }
        agentScrollback.remove(sessionId)
        ioExecutor.execute { tearDown(session) }
        for (dep in dependents) {
            Log.d(TAG, "Cascading disconnect from jump host $sessionId to ${dep.sessionId}")
            removeSession(dep.sessionId)
        }
    }

    /**
     * Read the most recent [maxBytes] of raw SSH stdout for [sessionId]
     * from the agent-scope ring buffer, or null if no ring exists yet
     * (no terminal session has been created on this SSH session).
     *
     * Bytes are returned in chronological order. ANSI escape sequences,
     * OSC markers, and other control bytes are preserved — the agent is
     * expected to parse them or ask the user to strip them.
     */
    fun readAgentScrollback(sessionId: String, maxBytes: Int): ByteArray? {
        val ring = agentScrollback[sessionId] ?: return null
        val full = ring.snapshot()
        return if (full.size <= maxBytes) full
        else full.copyOfRange(full.size - maxBytes, full.size)
    }

    /**
     * Send [text] as UTF-8 bytes to the active terminal session for
     * [sessionId]. Throws [IllegalStateException] if there is no
     * connected session with an attached terminal session — the agent
     * transport surfaces that as a JSON-RPC error.
     *
     * Routed through [TerminalSession.sendToSsh] (rather than writing
     * the shell channel directly) so it shares the same back-pressure
     * and serialised-write executor that user keystrokes use; an agent
     * pasting a long block can't interleave bytes with a human typing.
     */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("No SSH session: $sessionId")
        val terminal = session.terminalSession
            ?: throw IllegalStateException(
                "Session $sessionId has no active terminal — open a terminal tab first")
        terminal.sendToSsh(text.toByteArray(Charsets.UTF_8))
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
        // Deep-copy auth material so tearDown() zeroing one session's bytes
        // doesn't corrupt the config for other sessions sharing the same profile.
        val config = session.connectionConfig!!
        val safeCopy = when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> config.copy(
                authMethod = ConnectionConfig.AuthMethod.Password(auth.password.copyOf())
            )
            is ConnectionConfig.AuthMethod.PrivateKey -> config.copy(
                authMethod = auth.copy(keyBytes = auth.keyBytes.copyOf())
            )
            else -> config
        }
        return safeCopy to session.sessionManager
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

    fun setJumpSessionId(sessionId: String, jumpSessionId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(jumpSessionId = jumpSessionId))
        }
    }

    /**
     * Mark [sessionId] as having been opened as a tunnel for
     * [dependentProfileId] (a VNC/RDP/SMB profile that needs this SSH
     * session for port forwarding). When the last dependent is released,
     * the session is torn down — see [releaseTunnelDependent].
     *
     * Call this only when the session is freshly opened by the auto-tunnel
     * path; for sessions that were already connected and just had a new
     * dependent attached, use [attachTunnelDependent] instead so the
     * tunnelOpened flag isn't (re-)set on a user-opened session.
     */
    fun markTunnelOpened(sessionId: String, dependentProfileId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                tunnelOpened = true,
                tunnelDependents = existing.tunnelDependents + dependentProfileId,
            ))
        }
    }

    /**
     * Add [dependentProfileId] to an existing session's dependent set
     * without changing its [SessionState.tunnelOpened] flag. Use when an
     * already-connected session is reused by a new VNC/RDP/SMB tunnel —
     * the session might have been opened directly by the user (terminal),
     * and we don't want to inherit a teardown policy that would close it
     * when the tunnel goes away.
     */
    fun attachTunnelDependent(sessionId: String, dependentProfileId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                tunnelDependents = existing.tunnelDependents + dependentProfileId,
            ))
        }
    }

    /**
     * Remove [dependentProfileId] from every session's tunnel-dependent
     * set. Tears down any session that (a) was opened solely as a tunnel
     * ([SessionState.tunnelOpened] = true) and (b) has no remaining
     * dependents after the removal. Returns the list of session IDs
     * actually torn down (for logging / audit purposes).
     *
     * Called by [ConnectionsViewModel.disconnect] when a VNC/RDP/SMB
     * profile is disconnected, so the SSH session that was opened only
     * to carry its forward stops idling on the user's connections list
     * (#121, KoriKraut).
     */
    fun releaseTunnelDependent(dependentProfileId: String): List<String> {
        val toTearDown = mutableListOf<String>()
        _sessions.update { map ->
            map.mapValues { (_, session) ->
                if (dependentProfileId !in session.tunnelDependents) return@mapValues session
                val newDeps = session.tunnelDependents - dependentProfileId
                if (newDeps.isEmpty() && session.tunnelOpened) {
                    toTearDown += session.sessionId
                }
                session.copy(tunnelDependents = newDeps)
            }
        }
        for (sid in toTearDown) {
            Log.d(TAG, "Tearing down orphaned tunnel session $sid (last dependent $dependentProfileId released)")
            removeSession(sid)
        }
        return toTearDown
    }

    /**
     * Create a [ProxyJump] from a connected jump host session.
     * Returns null if the session doesn't exist or isn't connected.
     */
    fun createProxyJump(jumpSessionId: String): ProxyJump? {
        val jumpSession = _sessions.value[jumpSessionId]
        if (jumpSession == null) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId not found in sessions map")
            return null
        }
        if (jumpSession.status != SessionState.Status.CONNECTED) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId status=${jumpSession.status}, expected CONNECTED")
            return null
        }
        val jschSession = jumpSession.client.jschSession
        if (jschSession == null) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId has no jschSession (client.isConnected=${jumpSession.client.isConnected})")
            return null
        }
        Log.d(TAG, "createProxyJump: success for $jumpSessionId (jsch connected=${jschSession.isConnected})")
        return ProxyJump(jschSession)
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
                    PortForwardType.DYNAMIC -> {
                        val actualPort = session.client.setPortForwardingDynamic(
                            rule.bindAddress, rule.bindPort,
                        )
                        activated.add(rule.copy(actualBoundPort = actualPort))
                        Log.d(TAG, "Port forward activated: D ${rule.bindAddress}:$actualPort (SOCKS5)")
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
                PortForwardType.DYNAMIC -> session.client.delPortForwardingDynamic(forward.bindAddress, forward.actualBoundPort)
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
     * Build the list of commands to send after login. Each fires on the next
     * detected shell prompt. By default: session manager first, then post-login
     * command (runs inside the session manager). When [beforeSessionManager] is
     * true, the post-login command runs first (in the raw SSH shell).
     */
    private fun buildPendingCommands(
        sessionId: String,
        manager: SessionManager,
        postLoginCommand: String?,
        beforeSessionManager: Boolean = false,
    ): List<String> = buildList {
        val sessionCmd = buildSessionManagerCommand(sessionId, manager)
        val loginCmd = postLoginCommand?.takeIf { it.isNotBlank() }
        if (beforeSessionManager) {
            loginCmd?.let { add(it) }
            sessionCmd?.let { add(it) }
        } else {
            sessionCmd?.let { add(it) }
            loginCmd?.let { add(it) }
        }
    }

    /**
     * Build the session manager command string for a given session, or null if none.
     * Uses the user-chosen session name if set, otherwise a deterministic name.
     */
    private fun buildSessionManagerCommand(sessionId: String, manager: SessionManager): String? {
        val commandTemplate = manager.command ?: return null
        val session = _sessions.value[sessionId]
        val rawName = session?.chosenSessionName
            ?: session?.label ?: sessionId.take(8)
        // Sanitize for use as tmux/screen/zellij session name (no spaces or shell metacharacters)
        val sessionName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "-")
        // User override replaces the built-in command template
        val override = session?.sessionCommandOverride
        if (!override.isNullOrBlank()) {
            return override.replace("{name}", sessionName)
        }
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
        // Zero out auth material so it doesn't linger in heap
        when (val auth = session.connectionConfig?.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> auth.clear()
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                auth.keyBytes.fill(0)
                auth.clear()
            }
            else -> {}
        }
    }
}
