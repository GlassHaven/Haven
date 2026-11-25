package sh.haven.core.ssh

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.message.UserMessageBus
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.terminal.ScrollbackRing
import sh.haven.core.ssh.sftp.JschSftpSession
import sh.haven.core.ssh.sftp.SftpSession
import sh.haven.core.ssh.sshlib.SshlibConnection
import sh.haven.core.ssh.sshlib.SshlibSftpConnector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
 * Time to watch a freshly-opened shell before declaring it healthy. A real
 * interactive shell stays open well past this; a shell with no session manager
 * (or a broken exec) EOFs within ms, so the failure path returns fast and the
 * success path waits at most this long. (#215 follow-up)
 */
private const val SHELL_SETTLE_MS = 1500L

/** Definitive outcome of opening a shell for a freshly-connected SSH session. */
sealed interface ShellOutcome {
    val sessionId: String
    /** Live terminal: channel connected + reader running past the settle window. */
    data class Ready(override val sessionId: String) : ShellOutcome
    /** Remote shell exited immediately (clean exit) — e.g. no session manager installed. */
    data class ShellClosed(override val sessionId: String, val exitStatus: Int) : ShellOutcome
    /** Opening the shell threw, or the channel dropped during connect. */
    data class Failed(
        override val sessionId: String,
        val reason: String,
        /** True when the live server rejected opening another channel (e.g. MaxSessions full, #888). */
        val channelRejected: Boolean = false,
    ) : ShellOutcome
}

/**
 * Why a session-manager attach came up as a plain shell instead of the
 * named tmux/screen/etc. session. The shell itself is healthy ([ShellOutcome.Ready])
 * — the manager command we typed into it silently failed, so the user lands
 * in a bare prompt with no explanation. The post-attach probe (#731) names
 * the cause so the terminal can say so instead.
 */
sealed interface SessionAttachNotice {
    val sessionId: String
    /** The manager's list command exited non-zero — most often the binary
     *  isn't installed (or can't run under the host's default shell, e.g.
     *  tmux on a Windows OpenSSH server). */
    data class ManagerUnavailable(override val sessionId: String, val manager: SessionManager) : SessionAttachNotice
    /** The manager answered but the session we asked to attach/create is
     *  not in its list — the start command failed after login. */
    data class SessionMissing(override val sessionId: String, val manager: SessionManager, val sessionName: String) : SessionAttachNotice
}

/** Grace before the first post-attach probe. The manager command is typed
 *  into the PTY asynchronously, so the named session may not be listed yet
 *  even though the attach will succeed. */
private const val ATTACH_PROBE_GRACE_MS = 1200L

/**
 * A terminal emulator wired to a freshly-created [TerminalSession] at connect
 * time. [onData] becomes the session's data callback (so output is parsed — and
 * terminal capability probes answered — live from the first byte, before the
 * reader starts), and [onReady] is called right after the session is built so
 * the provider can point the emulator's input/resize back at the session.
 *
 * This is the seam that lets the emulator be created at connect (#290 issue #2)
 * without core/ssh depending on the feature/terminal module that owns it: the
 * owner registers a [TerminalAttachmentProvider]; [SshSessionManager] calls it
 * from [SshSessionManager.createTerminalSession]. When no provider is
 * registered the session falls back to the caller-supplied callback (the
 * pre-existing no-op-then-attach-later behaviour), so tests and any non-UI
 * caller keep working.
 */
interface TerminalAttachment {
    val onData: (ByteArray, Int, Int) -> Unit
    fun onReady(session: TerminalSession)
}

/** Supplies a [TerminalAttachment] for a session being created, or null to fall back. */
fun interface TerminalAttachmentProvider {
    fun attach(sessionId: String): TerminalAttachment?
}

/**
 * Manages active SSH sessions across the app.
 * Sessions are identified by a unique sessionId (UUID).
 * Multiple sessions may share the same profileId (multi-tab).
 */
@Singleton
class SshSessionManager @Inject constructor(
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
    private val userMessageBus: UserMessageBus,
) {
    constructor(
        hostKeyVerifier: HostKeyVerifier,
        connectionLogRepository: ConnectionLogRepository,
    ) : this(hostKeyVerifier, connectionLogRepository, UserMessageBus())

    // Off-thread sink for reconnect breadcrumbs — never blocks the reader /
    // ioExecutor; logEvent self-gates on the connection-logging preference.
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Registered by the feature-layer emulator owner (#290 issue #2) so the
     * terminal emulator is wired as the data callback at connect time. Null
     * until the owner's `start()` runs; [createTerminalSession] falls back to
     * the caller-supplied callback when null.
     */
    @Volatile
    var terminalAttachmentProvider: TerminalAttachmentProvider? = null

    /**
     * Record a reconnect-lifecycle breadcrumb in the connection log (visible in
     * Settings on release builds, where app logcat is stripped). Used to diagnose
     * reconnects that leave the terminal pane blank: the timeline of channel-swap
     * → reattach-sent → reader-ended shows whether the reattach fired and whether
     * the new shell produced any output. Best-effort; failures are swallowed.
     */
    private fun breadcrumb(profileId: String, detail: String) {
        logScope.launch {
            runCatching {
                connectionLogRepository.logEvent(
                    profileId, ConnectionLog.Status.DISCONNECTED, details = "reconnect-trace: $detail",
                )
            }
        }
    }

    data class PortForwardInfo(
        val ruleId: String,
        val type: PortForwardType,
        val bindAddress: String,
        val bindPort: Int,
        val targetHost: String,
        val targetPort: Int,
        val actualBoundPort: Int = bindPort,
        /**
         * When true, this forward is load-bearing for the session: if it
         * fails to (re-)bind, the session must NOT be considered healthy.
         * Used for the MCP reverse tunnel (`-R`), where a server-side
         * stale bind would otherwise leave a silently forward-less but
         * "CONNECTED" session. Mirrors OpenSSH `ExitOnForwardFailure`.
         */
        val critical: Boolean = false,
        /**
         * When true, a REMOTE (`-R`) bind failure triggers a one-shot
         * self-heal: over the (already-connected) SSH session, kill a stale
         * `sshd` process still holding this listen port on the endpoint host,
         * then retry the bind once. Used only for Haven's own dedicated
         * reverse-tunnel ports (the MCP `-R` + multiplexed guest MCP ports),
         * where the holder can only be a stale instance of our own forward —
         * automating the manual `kill` that previously un-wedged the tunnel
         * after an app restart / WiFi roam. Never set on arbitrary user
         * forwards.
         */
        val selfHealOnBindFailure: Boolean = false,
    )

    enum class PortForwardType { LOCAL, REMOTE, DYNAMIC }

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val client: SshConnection,
        val shellChannel: ShellChannel? = null,
        val terminalSession: TerminalSession? = null,
        val sftpSession: SftpSession? = null,
        val connectionConfig: ConnectionConfig? = null,
        val sessionManager: SessionManager = SessionManager.NONE,
        val sessionCommandOverride: String? = null,
        val chosenSessionName: String? = null,
        /**
         * Per-connection bypass of [sessionManager]. When true,
         * [buildSessionManagerCommand] returns null even when the
         * profile has tmux/zellij/screen configured, so the SSH shell
         * stays as the user's natural login shell. Set from the session
         * picker's "Plain shell" affordance for one-off bare-bash
         * sessions on a profile that's normally wrapped in a multiplexer.
         */
        val bypassSessionManager: Boolean = false,
        val postLoginCommand: String? = null,
        val postLoginBeforeSessionManager: Boolean = false,
        /** SSH exec request configured by the profile; null means shell. */
        val remoteCommand: String? = null,
        val requestPty: Boolean = true,
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
        /**
         * True for tunnel-only sessions that intentionally have no shell
         * (e.g. the dedicated MCP reverse tunnel). The reconnect path
         * skips opening a shell channel for these, so a network blip
         * re-establishes the port forwards without spawning an unwanted
         * (and possibly server-rejected) shell. (#150 tunnel-only.)
         */
        val headless: Boolean = false,
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

    /**
     * Post-attach session-manager verification notices (#731), keyed by
     * sessionId. Non-null means the shell came up but the session manager
     * didn't (not installed / start command failed) — the terminal shows a
     * banner naming the cause instead of dropping the user into a bare
     * prompt. Same separate-map rationale as [agentScrollback]: keeps
     * [SessionState]'s shape stable for unrelated consumers. Dropped in
     * [removeSession].
     */
    private val sessionNotices = ConcurrentHashMap<String, MutableStateFlow<SessionAttachNotice?>>()

    /** Flow of the attach notice for [sessionId] (null = attach looked
     *  healthy). Created on first access so tabs opened before the probe
     *  finished still observe the result. */
    fun attachNoticeFlow(sessionId: String): StateFlow<SessionAttachNotice?> =
        sessionNotices.getOrPut(sessionId) { MutableStateFlow(null) }

    private fun publishAttachNotice(sessionId: String, notice: SessionAttachNotice?) {
        sessionNotices.getOrPut(sessionId) { MutableStateFlow(null) }.value = notice
    }

    /** Clear the attach notice for [sessionId] — the banner's dismiss button
     *  (#731 follow-up). The banner has no auto-dismiss and the notice only
     *  cleared on re-attach/removal, so once raised it could not be gotten rid
     *  of. A dismiss while a probe is still in flight can be re-raised when
     *  that probe finishes; in practice the banner only appears after the
     *  probe's passes complete, so the tap lands on a settled notice. */
    fun dismissAttachNotice(sessionId: String) {
        publishAttachNotice(sessionId, null)
    }

    /**
     * Live tunnel leases, keyed by leaseId. A lease ties a tunnel-dependent
     * resource (a VNC/RDP Desktop tab and its protocol client) to the SSH
     * session that carries its forward, so teardown cascades both ways:
     * removing the SSH session (for any reason) fires each lease's
     * [LeaseRecord.onParentGone] so the dependent closes itself, and closing
     * a lease releases the dependent (and tears the SSH down if it was opened
     * solely for this tunnel). This replaces the loose profileId-string
     * tracking that left tabs orphaned over dead pipes (#121).
     */
    private val leases = ConcurrentHashMap<String, LeaseRecord>()

    /**
     * Per-session provider that re-resolves the routing proxy (per-profile
     * WireGuard / Tailscale tunnel, or legacy SOCKS/HTTP) on auto-reconnect.
     *
     * core:ssh cannot depend on core:tunnel (core:tunnel already depends on
     * core:ssh — a cycle), so [SshSessionManager] has no [TunnelResolver]. The
     * ViewModel that owns the resolver registers this callback after a
     * successful connect; [attemptReconnect] invokes it to rebuild the same
     * proxy the initial connect used. Without it, a tunnel-routed session whose
     * host is only reachable through the tunnel (device off-LAN) reconnects
     * directly and never recovers. Jump-host sessions don't use this — they go
     * through [createProxyJump] keyed on [SessionState.jumpSessionId].
     */
    private val reconnectProxyProviders = ConcurrentHashMap<String, suspend () -> HavenProxy?>()

    /**
     * Per-session hooks run once after a successful reconnect (status back to
     * CONNECTED, forwards re-applied). Used by the USB/IP forwarder to re-run the
     * host-side `usbip attach` — the device node on the host vanishes when the
     * tunnel drops, and re-binding the `-R` alone doesn't restore it.
     */
    private val reconnectHooks = ConcurrentHashMap<String, suspend () -> Unit>()

    private class LeaseRecord(
        val leaseId: String,
        val sessionId: String,
        val dependentProfileId: String,
        val localForwardPort: Int?,
        val onParentGone: () -> Unit,
        val closed: AtomicBoolean = AtomicBoolean(false),
    )

    /** Background executor for disconnect I/O so callers on main thread don't block. */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-session-io").apply { isDaemon = true }
    }

    /**
     * Separate executor for reconnect backoff + network dialing so a long
     * Thread.sleep or blocking connect in [attemptReconnect] never delays
     * [removeSession], [closeShellOnly], or [disconnectAll] teardowns queued
     * on [ioExecutor].
     */
    private val reconnectExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ssh-session-reconnect").apply { isDaemon = true }
    }

    /** Active reconnect threads by sessionId, interrupted immediately on [removeSession] / [disconnectAll]. */
    private val reconnectThreads = ConcurrentHashMap<String, Thread>()

    /** Sessions explicitly removed via [removeSession] / [disconnectAll] so an in-flight connect can detect cancellation. */
    private val removedSessionIds = ConcurrentHashMap.newKeySet<String>()

    /** Test seam for creating replacement clients during [attemptReconnect]. */
    internal var reconnectClientFactory: (ConnectionConfig) -> SshConnection = {
        SshConnectionFactory.create(it)
    }

    /** Test seam for initial reconnect delay in [attemptReconnect]. */
    internal var reconnectInitialDelayMs: Long = RECONNECT_INITIAL_DELAY_MS

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
    fun registerSession(
        profileId: String,
        label: String,
        client: SshConnection,
        headless: Boolean = false,
    ): String {
        // Reap dead sessions of the same profile AND kind so they don't pile up
        // across failed-reconnect / re-connect cycles (observed: a stale
        // DISCONNECTED 'near' lingering beside the live one with its dead
        // forwards). Same-kind only — a headless tunnel reconnecting must not
        // yank a dead interactive tab, nor vice versa; never a live or
        // mid-reconnect session.
        _sessions.value.values
            .filter {
                it.profileId == profileId &&
                    it.headless == headless &&
                    (it.status == SessionState.Status.DISCONNECTED || it.status == SessionState.Status.ERROR) &&
                    it.sessionId !in reconnectingInFlight
            }
            .map { it.sessionId }
            .forEach { removeSession(it) }

        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                client = client,
                headless = headless,
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
        remoteCommand: String? = config.remoteCommand,
        requestPty: Boolean = config.requestPty,
    ) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                connectionConfig = config,
                sessionManager = sessionMgr,
                sessionCommandOverride = sessionCommandOverride,
                postLoginCommand = postLoginCommand,
                postLoginBeforeSessionManager = postLoginBeforeSessionManager,
                remoteCommand = remoteCommand?.takeIf { it.isNotBlank() },
                requestPty = requestPty,
            ))
        }
        if (config.sshEngine == SshEngine.SSHLIB) {
            engineLog(
                _sessions.value[sessionId]?.profileId ?: return,
                "engine=sshlib (experimental): whole connection — terminal, exec, SFTP and " +
                    "tunnels over sshlib. Jump/proxy, FIDO2, OpenSSH certs and MFA chains are " +
                    "refused on this engine; switch the profile back to JSch for those. Known " +
                    "limitation: a second command channel on one connection can be refused and " +
                    "drop the connection — https://github.com/connectbot/cbssh/issues/238",
            )
        }
    }

    /**
     * Register (or clear, when [provider] is null) the routing-proxy provider
     * invoked on auto-reconnect for [sessionId]. Call after a successful connect
     * with the same resolution the initial connect used
     * (`tunnelResolver.havenProxy(profile)`). Cleared automatically in
     * [removeSession].
     */
    fun setReconnectProxyProvider(sessionId: String, provider: (suspend () -> HavenProxy?)?) {
        if (provider == null) reconnectProxyProviders.remove(sessionId)
        else reconnectProxyProviders[sessionId] = provider
    }

    /**
     * Register (or clear, with null) a hook run once after each successful
     * reconnect of [sessionId], after forwards are re-applied. Cleared
     * automatically in [removeSession]. See [reconnectHooks].
     */
    fun setOnReconnected(sessionId: String, hook: (suspend () -> Unit)?) {
        if (hook == null) reconnectHooks.remove(sessionId)
        else reconnectHooks[sessionId] = hook
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
        val channel = session.client.openTerminalChannel(session.remoteCommand, session.requestPty)
        attachShellChannel(sessionId, channel)
    }

    fun attachShellChannel(sessionId: String, channel: ShellChannel) {
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
        connectSignal: CompletableDeferred<ShellOutcome>? = null,
    ): TerminalSession? {
        val session = _sessions.value[sessionId] ?: return null
        val channel = session.shellChannel ?: return null
        val pendingCmds = if (session.remoteCommand == null) {
            buildPendingCommands(sessionId, session.sessionManager, session.postLoginCommand, session.postLoginBeforeSessionManager)
        } else {
            emptyList()
        }
        // Mirror raw SSH stdout into an app-scoped ring buffer so the
        // agent transport can read the same bytes the emulator sees,
        // without reaching into activity-scoped state.
        val ring = agentScrollback.computeIfAbsent(sessionId) {
            ScrollbackRing(AGENT_SCROLLBACK_CAPACITY_BYTES)
        }
        // Wire the emulator as the data callback at connect time so terminal
        // probes are parsed/answered live from the first byte (#290 issue #2).
        // Falls back to the caller-supplied callback when no owner is
        // registered (tests / non-UI callers).
        val attachment = terminalAttachmentProvider?.attach(sessionId)
        val termSession = TerminalSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            shell = channel,
            client = session.client,
            onDataReceived = attachment?.onData ?: onDataReceived,
            // Permanent ring mirror — NOT swapped by replaceDataCallback, so it
            // survives reattach now that the session is created at connect time
            // and the emulator attaches afterwards. (#215 follow-up)
            onMirror = { data, off, len -> ring.append(data, off, len) },
            onDisconnected = { cleanExit ->
                // Initial-connect window: report the outcome to the awaiting
                // connect path exactly once, instead of the normal
                // disconnect/reconnect handling. Once completed (here or
                // disarmed by openShellAndAwaitReady after the window),
                // established sessions take the reconnect-aware path below.
                if (connectSignal != null && !connectSignal.isCompleted) {
                    connectSignal.complete(
                        if (cleanExit) ShellOutcome.ShellClosed(sessionId, channel.exitStatus)
                        else ShellOutcome.Failed(sessionId, "shell closed during connect"),
                    )
                    updateStatus(sessionId, SessionState.Status.DISCONNECTED)
                } else if (cleanExit) {
                    if (isJumpOrTunnelCarrier(sessionId) && session.client.isConnected) {
                        Log.d(TAG, "Shell for $sessionId exited cleanly while carrying jump/tunnel dependents — keeping SSH transport connected")
                        closeShellOnly(sessionId)
                    } else {
                        Log.d(TAG, "Session $sessionId exited cleanly — tearing down session")
                        removeSession(sessionId)
                    }
                } else {
                    Log.d(TAG, "Session $sessionId disconnected unexpectedly")
                    val sess = _sessions.value[sessionId]
                    val cfg = sess?.connectionConfig
                    if (sess?.shellChannel?.isConnected == true) {
                        // #888: a reconnect already swapped this session onto a fresh
                        // live shell, so this EOF is the stale old reader finishing.
                        // Don't fire a second reconnect that would tear the new shell
                        // down (and, with a capped server, exhaust overflow dials).
                        Log.d(TAG, "Session $sessionId old reader ended but current shell is live — ignoring stale EOF")
                    } else if (cfg != null && cfg.reconnectPolicy.autoReconnect) {
                        reconnectExecutor.execute { attemptReconnect(sessionId) }
                    } else if (sess?.status == SessionState.Status.RECONNECTING) {
                        // #888: connection-reuse siblings carry autoReconnect=false
                        // because the primary session owns reconnecting the shared
                        // client. When previousClient.disconnect() closes the old
                        // reader, keep RECONNECTING so TerminalViewModel does not
                        // drop the sibling tab while the primary swaps channels.
                        Log.d(TAG, "Sibling session $sessionId old reader ended during primary reconnect — keeping RECONNECTING")
                    } else {
                        if (cfg != null) {
                            Log.d(TAG, "Session $sessionId auto-reconnect disabled by profile policy")
                        }
                        updateStatus(sessionId, SessionState.Status.DISCONNECTED)
                    }
                }
            },
            pendingCommands = pendingCmds,
            onBreadcrumb = { detail -> breadcrumb(session.profileId, detail) },
        )
        attachTerminalSession(sessionId, termSession)
        // Let the owner point the emulator's input/resize sinks back at this
        // session BEFORE the reader starts (term.start() runs after this
        // returns), so the inputSink is set before the first byte arrives.
        attachment?.onReady(termSession)
        return termSession
    }

    /**
     * Open a shell for a freshly-connected session, attach + start its terminal
     * session, and AWAIT a definitive outcome before returning — so callers
     * navigate to the terminal ONLY on a confirmed live shell. A shell that
     * closes immediately (no session manager on the host) returns
     * [ShellOutcome.ShellClosed] within ms; a healthy shell returns
     * [ShellOutcome.Ready] after at most [settleMs]. Replaces the old
     * open-shell-then-poll pattern. (#215 follow-up)
     *
     * NOTE: leaves status as-is on Ready (the caller flips it to CONNECTED on
     * success); sets ERROR on ShellClosed/Failed so a dead shell never reads
     * as connected.
     */
    suspend fun openShellAndAwaitReady(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
        settleMs: Long = SHELL_SETTLE_MS,
    ): ShellOutcome {
        val session = _sessions.value[sessionId]
            ?: return ShellOutcome.Failed(sessionId, "session not found")
        val channel = try {
            session.client.openTerminalChannel(session.remoteCommand, session.requestPty)
        } catch (e: Exception) {
            Log.w(TAG, "openShellChannel failed for $sessionId: ${e.message}")
            updateStatus(sessionId, SessionState.Status.ERROR)
            return ShellOutcome.Failed(
                sessionId = sessionId,
                reason = e.message ?: "failed to open shell",
                channelRejected = e is SshChannelOpenRejectedException,
            )
        }
        attachShellChannel(sessionId, channel)
        val connectSignal = CompletableDeferred<ShellOutcome>()
        val term = createTerminalSession(sessionId, onDataReceived, connectSignal)
            ?: run {
                updateStatus(sessionId, SessionState.Status.ERROR)
                return ShellOutcome.Failed(sessionId, "could not create terminal session")
            }
        term.start()
        val signalled = withTimeoutOrNull(settleMs) { connectSignal.await() }
        // Disarm: after the window, future disconnects must take the normal
        // (reconnect-aware) path, not the connect-outcome path.
        if (!connectSignal.isCompleted) connectSignal.complete(ShellOutcome.Ready(sessionId))
        return when {
            signalled is ShellOutcome.ShellClosed || signalled is ShellOutcome.Failed -> {
                updateStatus(sessionId, SessionState.Status.ERROR)
                signalled
            }
            channel.isConnected -> {
                verifySessionManagerAttached(sessionId)
                ShellOutcome.Ready(sessionId)
            }
            else -> {
                updateStatus(sessionId, SessionState.Status.ERROR)
                ShellOutcome.ShellClosed(sessionId, channel.exitStatus)
            }
        }
    }

    /**
     * After a Ready shell, check that the session manager actually came up.
     * The manager start command is typed into the PTY asynchronously, so a
     * silent failure (tmux not installed, POSIX one-liner through cmd.exe on
     * a Windows host) leaves the user in a plain login shell with no error
     * (#731). Exec the manager's list command over the same channel the
     * picker uses and publish a [SessionAttachNotice] when the named session
     * isn't there. Runs off the connect path (the banner appears a beat
     * later); retries once after a grace so a slow-but-working attach is
     * never reported as failed.
     */
    internal fun verifySessionManagerAttached(sessionId: String, graceMs: Long = ATTACH_PROBE_GRACE_MS) {
        val session = _sessions.value[sessionId] ?: return
        val manager = session.sessionManager
        if (manager == SessionManager.NONE || session.bypassSessionManager) return
        // A custom Session command replaces the built-in start template, so the
        // manager's built-in list command can't verify it: on a Windows OpenSSH
        // host the POSIX list probe (`sh -c 'tmux ls …'`) fails through
        // cmd/PowerShell even when the custom command attached tmux fine
        // (#731 follow-up — reporter's custom `tmux new-session -A -s {name}`
        // worked but the banner false-positived). The global preference is
        // folded into this field by the connect path before storeConnectionConfig,
        // so any non-blank value — per-profile or global — means "we can't
        // verify this attach".
        if (!session.sessionCommandOverride.isNullOrBlank()) return
        val listCommand = manager.listCommand ?: return
        val rawName = session.chosenSessionName ?: session.label ?: sessionId.take(8)
        val expected = SessionManager.sanitizeSessionName(rawName)
        // A fresh attach attempt clears any notice from a previous one (e.g.
        // a reconnect after the host got tmux installed) so a stale banner
        // can't outlive the attach it described.
        publishAttachNotice(sessionId, null)
        logScope.launch {
            delay(graceMs)
            var listed: List<String>?
            var unavailable = false
            try {
                val r = session.client.execCommand(listCommand)
                unavailable = r.exitStatus != 0
                listed = if (!unavailable) SessionManager.parseSessionList(manager, r.stdout) else null
            } catch (e: Exception) {
                Log.d(TAG, "attach probe failed for $sessionId: ${e.message}")
                return@launch
            }
            if (unavailable) {
                if (_sessions.value.containsKey(sessionId))
                    publishAttachNotice(sessionId, SessionAttachNotice.ManagerUnavailable(sessionId, manager))
                return@launch
            }
            if (listed?.contains(expected) == true) return@launch
            // Absent on the first pass — give the start command one more
            // grace window before declaring the attach failed.
            delay(graceMs)
            try {
                val r = session.client.execCommand(listCommand)
                if (r.exitStatus != 0) {
                    if (_sessions.value.containsKey(sessionId))
                        publishAttachNotice(sessionId, SessionAttachNotice.ManagerUnavailable(sessionId, manager))
                } else if (!SessionManager.parseSessionList(manager, r.stdout).contains(expected)) {
                    if (_sessions.value.containsKey(sessionId))
                        publishAttachNotice(sessionId, SessionAttachNotice.SessionMissing(sessionId, manager, expected))
                }
            } catch (e: Exception) {
                Log.d(TAG, "attach-probe retry failed for $sessionId: ${e.message}")
            }
        }
    }

    /**
     * True iff the session has a connected channel AND an attached terminal
     * session — i.e. a usable terminal tab can/does exist. (#215 follow-up)
     */
    fun isLiveTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.shellChannel?.isConnected == true &&
            session.terminalSession != null
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
     * Open (or reuse) an [SftpSession] for [profileId]. Finds any connected
     * session for that profile and opens (or reuses) an SFTP session on it.
     * Callers in feature- and app-modules use this so they never import
     * engine types (JSch's `ChannelSftp`) directly. Returns null if no
     * session for this profile is connected.
     *
     * Engine routing (#58): a profile toggled to [SshEngine.SSHLIB] gets a
     * sshlib-backed session over its own dedicated connection when the
     * config is within sshlib's phase-1 capabilities; otherwise it falls
     * back to JSch with a connection-log line naming why. A dial failure on
     * the sshlib path throws — never a silent fallback.
     */
    fun openSftpSession(profileId: String): SftpSession? {
        val session = _sessions.value.values
            .filter { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            .firstOrNull() ?: return null
        // Reuse the existing session if still connected
        session.sftpSession?.let { if (it.isConnected) return it }
        val sftp = dialSftp(session)
        _sessions.update { map ->
            val existing = map[session.sessionId] ?: return@update map
            map + (session.sessionId to existing.copy(sftpSession = sftp))
        }
        return sftp
    }

    private fun dialSftp(session: SessionState): SftpSession {
        val config = session.connectionConfig
        if (config?.sshEngine == SshEngine.SSHLIB) {
            val reason = SshlibSftpConnector.unsupportedReason(
                config,
                hasJump = session.jumpSessionId != null,
                hasProxy = session.client.connectedViaProxy,
            )
            if (reason == null) {
                // Blocking dial — callers already invoke openSftpSession off
                // the main thread (the JSch path opens a channel over the wire
                // too). Same precedent as the reconnect loop's connectBlocking.
                val sftp = runBlocking { SshlibSftpConnector.connect(config, hostKeyVerifier) }
                engineLog(session.profileId, "SFTP: sshlib engine (dedicated connection)")
                return sftp
            }
            // The dedicated dial is out, but the session's own connection may
            // already BE sshlib (a whole SshlibConnection, jump/proxy included),
            // in which case nothing falls back to JSch — say which it is rather
            // than claiming JSch every time.
            engineLog(
                session.profileId,
                if (session.client is SshClient) {
                    "SFTP: falling back to JSch — sshlib engine does not support $reason yet"
                } else {
                    "SFTP: on the session's sshlib connection — no dedicated dial ($reason)"
                },
            )
        }
        return session.client.openSftpSession()
    }

    /** Best-effort connection-log line for SSH-engine routing decisions (#58). */
    private fun engineLog(profileId: String, detail: String) {
        logScope.launch {
            runCatching {
                connectionLogRepository.logEvent(
                    profileId, ConnectionLog.Status.CONNECTED, details = detail,
                )
            }
        }
    }

    private fun activeShellCount(client: SshConnection): Int =
        _sessions.value.values.count {
            it.client === client &&
                it.shellChannel != null &&
                it.status != SessionState.Status.DISCONNECTED &&
                it.status != SessionState.Status.ERROR
        }

    /**
     * Swap [sessionId] onto [newClient] (used when a reused client hits the
     * server's `MaxSessions` cap and the caller dials an overflow client, #888).
     */
    fun replaceSessionClient(sessionId: String, newClient: SshConnection) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(client = newClient, status = SessionState.Status.CONNECTING))
        }
    }

    /**
     * Find a connected [SshClient] for this profile. Used by file-transfer
     * code paths that need shell exec (SCP, shell-ls browsing) — i.e. the
     * legacy transport for servers without an SFTP subsystem.
     *
     * Prefers the connected client with the fewest active shell channels so
     * exec/SFTP requests use a connection with free `MaxSessions` headroom (#888).
     */
    fun getSshClientForProfile(profileId: String): SshConnection? {
        val values = _sessions.value.values
        val interactive = values.filter {
            it.profileId == profileId && !it.headless && it.status == SessionState.Status.CONNECTED
        }
        if (interactive.isNotEmpty()) {
            return interactive.minByOrNull { activeShellCount(it.client) }?.client
        }
        return values
            .filter { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            .minByOrNull { activeShellCount(it.client) }
            ?.client
    }

    /**
     * True when another live session shares [sessionId]'s [SshClient] — i.e.
     * this session was opened via connection reuse (a second terminal on an
     * already-connected profile, sharing one SSH connection across several
     * shell channels / tmux sessions). Callers use this to avoid duplicating
     * connection-scoped setup (port forwards) and to keep the shared client
     * alive in [tearDown] until its last session goes away.
     */
    fun isClientShared(sessionId: String): Boolean {
        val target = _sessions.value[sessionId] ?: return false
        return _sessions.value.values.any { it.sessionId != sessionId && it.client === target.client }
    }

    /**
     * Resolve a live [SshClient] to reuse for [profileId], coordinating with
     * an in-flight connect. Returns a CONNECTED session's client immediately
     * if one exists; otherwise, if a session for the profile is currently
     * CONNECTING (e.g. the MCP carrier coming up on app launch), suspends
     * until that connect resolves and returns its client (or null if it
     * failed). Returns null when nothing is live and nothing is in flight.
     * Bounded by [timeoutMs] so a stuck connect can't hang the caller.
     *
     * This closes the app-launch race where a restored terminal tab and the
     * MCP carrier both target the same tunnel-routed profile: without the
     * wait, the tab's reuse check runs before the carrier reaches CONNECTED,
     * so it dials a second concurrent WireGuard flow the peer can't service
     * and times out. MUST be called BEFORE the caller registers its own
     * session, so it never waits on itself.
     */
    suspend fun awaitReusableClient(profileId: String, timeoutMs: Long = 35_000): SshConnection? {
        _sessions.value.values
            .filter {
                it.profileId == profileId && !it.headless && it.status == SessionState.Status.CONNECTED
            }
            .minByOrNull { activeShellCount(it.client) }
            ?.client
            ?.let { return it }
        val pending = _sessions.value.values.firstOrNull {
            it.profileId == profileId && !it.headless && it.status == SessionState.Status.CONNECTING
        } ?: return null
        Log.d(TAG, "awaitReusableClient($profileId): waiting on in-flight connect ${pending.sessionId}")
        return withTimeoutOrNull(timeoutMs) {
            _sessions.first { map ->
                val s = map[pending.sessionId]
                s == null || s.status != SessionState.Status.CONNECTING
            }
            _sessions.value.values
                .filter {
                    it.profileId == profileId && !it.headless && it.status == SessionState.Status.CONNECTED
                }
                .minByOrNull { activeShellCount(it.client) }
                ?.client
        }
    }

    /**
     * Suspend until a CONNECTED session exists for [profileId], when one is
     * currently (re)connecting. Returns true if connected (already, or within
     * [timeoutMs]); false immediately when nothing is live or in flight.
     *
     * Unlike [awaitReusableClient] this also waits through RECONNECTING and is
     * used by the file-backend resolver: right after the app restarts from a
     * self-install the restored SSH session is briefly RECONNECTING, so a
     * backend lookup would otherwise return null and read as "No connected
     * backend" even though the session is coming back. (#245 follow-up)
     */
    suspend fun awaitConnected(profileId: String, timeoutMs: Long = 12_000): Boolean {
        if (isProfileConnected(profileId)) return true
        val inFlight = _sessions.value.values.any {
            it.profileId == profileId &&
                (it.status == SessionState.Status.CONNECTING ||
                    it.status == SessionState.Status.RECONNECTING)
        }
        if (!inFlight) return false
        return withTimeoutOrNull(timeoutMs) {
            _sessions.first { map ->
                map.values.any {
                    it.profileId == profileId && it.status == SessionState.Status.CONNECTED
                }
            }
            true
        } ?: false
    }

    /**
     * Store a bookkeeping [ConnectionConfig] for a connection-reuse session
     * [sessionId] — one that was registered against another session's live
     * [SshClient] instead of dialing its own. The config carries reconnect
     * OFF (the primary session owns reconnect of the shared client) and a
     * fresh empty password, so [tearDown]'s auth-zeroing can never reach the
     * primary's live credential. Host/port/username are copied from the
     * profile's primary session purely for display/bookkeeping.
     */
    fun storeReuseConfig(sessionId: String, profileId: String, sessionMgr: SessionManager) {
        val ref = _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.sessionId != sessionId && it.connectionConfig != null }
            ?.connectionConfig
        val config = ConnectionConfig(
            host = ref?.host ?: "reused-connection",
            port = ref?.port ?: 22,
            username = ref?.username ?: "reused",
            authMethod = ConnectionConfig.AuthMethod.Password(""),
            sshOptions = ref?.sshOptions ?: emptyMap(),
            remoteCommand = ref?.remoteCommand,
            requestPty = ref?.requestPty ?: true,
            reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = false),
        )
        storeConnectionConfig(sessionId, config, sessionMgr)
    }

    /**
     * Create an [ScpClient] bound to the connected JSch session for
     * [profileId]. Each call produces a new façade — the session itself is
     * shared, but SCP opens a fresh exec channel per operation so there is
     * no caching to do here.
     */
    fun openScpForProfile(profileId: String): ScpClient? {
        val client = getSshClientForProfile(profileId) ?: return null
        val jschSession = (client as? SshClient)?.jschSession ?: return null
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
        reconnectExecutor.execute { attemptReconnect(sessionId) }
    }

    /**
     * Request reconnection of all sessions that are DISCONNECTED or ERROR.
     * Used by [SshConnectionService] when network becomes available.
     */
    fun requestReconnectAll() {
        _sessions.value.forEach { (id, session) ->
            val cfg = session.connectionConfig ?: return@forEach
            if (session.status !in listOf(SessionState.Status.DISCONNECTED, SessionState.Status.ERROR)) return@forEach
            // #150: honour per-profile policy. Either toggle alone is
            // enough to disable a network-change reconnect — both have
            // to be on for the NetworkMonitor → reconnect path.
            if (!cfg.reconnectPolicy.autoReconnect) return@forEach
            if (!cfg.reconnectPolicy.onNetworkChange) return@forEach
            requestReconnect(id)
        }
    }

    /**
     * Probe every live SSH session and reconnect any whose socket died
     * silently. Called on every return-to-foreground (see [SshConnectionService])
     * because a backgrounded socket can be dropped by NAT/Doze without a
     * default-network transport change — so neither the keepalive-timeout path
     * nor the [NetworkMonitor] path fires, and the session sits frozen while
     * still reporting CONNECTED.
     *
     * That CONNECTED status is exactly why we must probe first: [requestReconnect]
     * no-ops on a CONNECTED session, so a failed probe is flipped to DISCONNECTED
     * before being handed off. Probes run concurrently with a bounded per-probe
     * timeout so one dead session doesn't serialize behind another's wait.
     *
     * Skips:
     *  - non-CONNECTED sessions (CONNECTING/RECONNECTING are already in flight;
     *    DISCONNECTED/ERROR are covered by [requestReconnect]/[requestReconnectAll]),
     *  - headless tunnel sessions (e.g. the MCP reverse tunnel) — McpTunnelManager
     *    owns those via its own watchdog and probing here would race its reconnect,
     *  - sessions whose profile policy disables autoReconnect.
     */
    suspend fun probeAndReconnectStale(probeTimeoutMs: Long = 5_000L, profileId: String? = null) {
        val candidates = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED &&
                !it.headless &&
                it.connectionConfig?.reconnectPolicy?.autoReconnect == true &&
                (profileId == null || it.profileId == profileId)
        }
        if (candidates.isEmpty()) return
        Log.d(TAG, "probeAndReconnectStale: probing ${candidates.size} live session(s)" +
            (profileId?.let { " for profile $it" } ?: ""))
        coroutineScope {
            candidates.map { session ->
                async {
                    if (!session.client.isAlive(probeTimeoutMs)) {
                        Log.d(TAG, "probeAndReconnectStale: ${session.sessionId} stale — reconnecting")
                        updateStatus(session.sessionId, SessionState.Status.DISCONNECTED)
                        requestReconnect(session.sessionId)
                    }
                }
            }.awaitAll()
        }
    }

    /** Sessions with a reconnect loop currently running, so a second caller
     *  (e.g. a late onDisconnected racing a network-probe) can't start a
     *  duplicate loop that tears down a healthy session. (#208 finding 10) */
    private val reconnectingInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Attempt to reconnect a dropped session with exponential backoff.
     * Called on [reconnectExecutor].
     */
    private fun attemptReconnect(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        val config = session.connectionConfig ?: return
        val sessionMgr = session.sessionManager

        if (!reconnectingInFlight.add(sessionId)) {
            Log.d(TAG, "attemptReconnect: $sessionId already in flight — skipping duplicate")
            return
        }
        val currentThread = Thread.currentThread()
        reconnectThreads[sessionId] = currentThread
        try {
        updateStatus(sessionId, SessionState.Status.RECONNECTING)
        breadcrumb(session.profileId, "attempt started → ${config.host}:${config.port}")

        // #888: mark any connection-reuse siblings sharing session.client as
        // RECONNECTING up front, BEFORE previousClient.disconnect() closes their
        // old shell streams. Otherwise each sibling's old reader thread sees
        // autoReconnect=false and flips the sibling to DISCONNECTED, causing
        // TerminalViewModel.reconcileSessions() to drop its tab mid-reconnect.
        val initialSharedClient = session.client
        val initialSiblings = _sessions.value.values.filter {
            it.sessionId != sessionId &&
                it.client === initialSharedClient &&
                (it.status == SessionState.Status.CONNECTED || it.status == SessionState.Status.DISCONNECTED)
        }
        for (sib in initialSiblings) {
            updateStatus(sib.sessionId, SessionState.Status.RECONNECTING)
        }

        // Proactively mark dependent sessions (that use this as jump host) as RECONNECTING
        // so the UI shows the reconnecting indicator immediately instead of waiting for keepalive timeout
        val dependents = _sessions.value.values.filter { it.jumpSessionId == sessionId }
        for (dep in dependents) {
            if (dep.status == SessionState.Status.CONNECTED) {
                Log.d(TAG, "Marking dependent session ${dep.sessionId} as RECONNECTING (jump host $sessionId dropped)")
                updateStatus(dep.sessionId, SessionState.Status.RECONNECTING)
            }
        }

        // #150: per-profile cap. 0 means unlimited (useful for tunnel-only
        // profiles that hold port forwards alive). Negative values are
        // treated as the legacy default; the data model schema rejects
        // them but defensive code is cheap.
        val maxAttempts = config.reconnectPolicy.maxAttempts
        val unlimited = maxAttempts == 0
        val attemptCap = when {
            unlimited -> Int.MAX_VALUE
            maxAttempts > 0 -> maxAttempts
            else -> RECONNECT_MAX_ATTEMPTS
        }
        var delayMs = reconnectInitialDelayMs
        var attempt = 0
        while (attempt < attemptCap) {
            attempt++
            // Check if session was removed (user manually disconnected)
            if (_sessions.value[sessionId] == null || currentThread.isInterrupted) {
                Log.d(TAG, "Reconnect cancelled for $sessionId — session removed")
                return
            }

            val capLabel = if (unlimited) "∞" else "$attemptCap"
            Log.d(TAG, "Reconnect attempt $attempt/$capLabel for $sessionId (delay ${delayMs}ms)")
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return
            }

            // Check again after sleep
            if (_sessions.value[sessionId] == null || currentThread.isInterrupted) return

            var candidateClient: SshConnection? = null
            try {
                // Rebuild the routing proxy the same way the initial connect
                // did. Jump host > per-profile tunnel (WireGuard / Tailscale) /
                // SOCKS / HTTP — mirroring ConnectionsViewModel.connectSsh.
                // Without the tunnel branch a tunnel-routed profile reconnects
                // directly and never recovers when the host is only reachable
                // through the tunnel (device off-LAN).
                val jumpSid = _sessions.value[sessionId]?.jumpSessionId
                val proxy = if (jumpSid != null) {
                    val jumpSession = _sessions.value[jumpSid]
                    if (jumpSession?.status != SessionState.Status.CONNECTED) {
                        Log.w(TAG, "Jump host $jumpSid not connected — cannot reconnect $sessionId")
                        delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                        continue
                    }
                    // Fail CLOSED. A null here used to fall through to a
                    // proxy-less dial, which silently reconnects straight to the
                    // target instead of through the bastion — the opposite of
                    // what a jump-host profile asks for. Retry instead; the
                    // interactive path already throws for the same case.
                    val jumpProxy = createProxyJump(jumpSid)
                    if (jumpProxy == null) {
                        Log.w(TAG, "Jump host $jumpSid gave no usable proxy — not dialing $sessionId direct")
                        delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                        continue
                    }
                    jumpProxy
                } else {
                    reconnectProxyProviders[sessionId]?.let { provider ->
                        runBlocking { provider() }
                    }
                }

                val previousClient = _sessions.value[sessionId]?.client
                val previousShell = _sessions.value[sessionId]?.shellChannel
                val newClient = reconnectClientFactory(config).apply {
                    // Carry FIDO wiring from the client we're replacing so a
                    // security-key profile can re-authenticate on reconnect —
                    // otherwise addFidoIdentity NPEs on the null authenticator.
                    fidoAuthenticator = previousClient?.fidoAuthenticator
                    openKeychainClients = previousClient?.openKeychainClients
                    // Same for security-chip keys: KeystoreSshIdentity needs
                    // the signer, and a reconnect that drops it would fail
                    // with "no HardwareSshKeySigner configured".
                    hardwareKeySigner = previousClient?.hardwareKeySigner
                }
                candidateClient = newClient
                val hostKeyEntry = newClient.connectBlocking(
                    config,
                    proxy = proxy,
                    trustedHostCaKeys = runBlocking { hostKeyVerifier.trustedHostCaKeys() },
                )

                // Silent TOFU on reconnect: auto-accept new, abort on change.
                // A null entry means the host presented a certificate that
                // JSch verified against a trusted host CA (#133) — nothing
                // to TOFU.
                if (hostKeyEntry != null) {
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
                } else {
                    Log.i(TAG, "Reconnect $sessionId: host verified by trusted CA")
                }

                // If removeSession() raced while connectBlocking / TOFU was running,
                // tear down newClient immediately instead of leaking it on the server.
                if (_sessions.value[sessionId] == null || currentThread.isInterrupted) {
                    Log.d(TAG, "Session $sessionId removed during reconnect dial — disconnecting newClient")
                    runCatching { newClient.disconnect() }
                    return
                }

                // Capture any connection-reuse sibling sessions that shared previousClient
                // so they also move onto newClient and reattach their shells.
                val sharedSiblingIds = if (previousClient != null) {
                    _sessions.value.values
                        .filter { it.sessionId != sessionId && it.client === previousClient }
                        .map { it.sessionId }
                } else emptyList()

                // Update session state (and any shared siblings) with new client
                _sessions.update { map ->
                    val existing = map[sessionId] ?: return@update map
                    var updated = map + (sessionId to existing.copy(client = newClient))
                    for (sibId in sharedSiblingIds) {
                        val sib = updated[sibId] ?: continue
                        updated = updated + (sibId to sib.copy(client = newClient))
                    }
                    updated
                }

                if (_sessions.value[sessionId] == null) {
                    runCatching { newClient.disconnect() }
                    return
                }

                // Disconnect the replaced shell channel and old client so they don't leak.
                runCatching { if (previousShell?.isConnected == true) previousShell.disconnect() }
                if (previousClient != null && previousClient !== newClient) {
                    val stillUsed = _sessions.value.values.any { it.client === previousClient }
                    if (!stillUsed) {
                        runCatching { previousClient.disconnect() }
                    }
                }

                // Open shell and reconnect terminal — skipped for headless
                // tunnel-ONLY sessions (e.g. the MCP reverse tunnel), which
                // carry port forwards and never had a shell. But a headless
                // session that ALSO holds an interactive terminal (the "near"
                // profile used as BOTH the MCP reverse-tunnel endpoint and a
                // REPL tab — tunnel + REPL collapsed onto one session) must
                // still reattach its shell, or the tunnel recovers on
                // return-to-foreground (via McpTunnelManager.kickNow) while the
                // terminal stays frozen. A pure tunnel has terminalSession ==
                // null and still skips this.
                val reconnecting = _sessions.value[sessionId]
                if ((reconnecting?.terminalSession != null || reconnecting?.shellChannel != null) &&
                    (reconnecting.headless != true || reconnecting.terminalSession != null)
                ) {
                    val channel = newClient.openTerminalChannel(reconnecting.remoteCommand, reconnecting.requestPty)
                    if (_sessions.value[sessionId] == null || currentThread.isInterrupted) {
                        runCatching { channel.disconnect() }
                        runCatching { newClient.disconnect() }
                        return
                    }
                    attachShellChannel(sessionId, channel)

                    // Swap channel in the terminal session and restart reader
                    val termSession = _sessions.value[sessionId]?.terminalSession
                    val sess = _sessions.value[sessionId]
                    val pendingCmds = if (sess?.remoteCommand == null) {
                        buildPendingCommands(sessionId, sessionMgr, sess?.postLoginCommand, sess?.postLoginBeforeSessionManager ?: false)
                    } else {
                        emptyList()
                    }
                    if (pendingCmds.isNotEmpty()) {
                        termSession?.setPendingCommands(pendingCmds)
                    }
                    termSession?.reconnect(channel, newClient)
                }

                // Reattach shell channels for any connection-reuse sibling sessions
                // that were sharing previousClient.
                //
                // #888: keep both the primary session and all siblings in
                // RECONNECTING until EVERY sibling shell channel has opened.
                // Flipping a sibling to CONNECTED inside this loop wakes
                // McpNearCarrier mid-loop, whose recordTunnelSshPid opens an
                // `exec` channel on newClient and steals OpenSSH's 10th
                // MaxSessions slot from the 10th tab.
                val reattachedSiblingIds = mutableListOf<String>()
                val failedSiblingIds = mutableListOf<String>()
                val failedSiblingDetails = mutableListOf<Pair<String, String>>()
                var activeSiblingClient: SshConnection = newClient
                val overflowOwnersAssigned = HashSet<SshConnection>()
                for (sibId in sharedSiblingIds) {
                    val sib = _sessions.value[sibId] ?: continue
                    if (sib.terminalSession != null || sib.shellChannel != null) {
                        runCatching {
                            val oldSibShell = sib.shellChannel
                            var targetClient: SshConnection = activeSiblingClient
                            val sibChannel = try {
                                targetClient.openTerminalChannel(sib.remoteCommand, sib.requestPty)
                            } catch (firstErr: Exception) {
                                if (firstErr is SshChannelOpenRejectedException &&
                                    !config.authMethod.containsFidoKey()
                                ) {
                                    val overflow = dialReconnectOverflowClient(
                                        sessionId = sessionId,
                                        config = config,
                                        proxy = proxy,
                                        previousClient = previousClient,
                                        currentThread = currentThread,
                                    ) ?: throw firstErr
                                    activeSiblingClient = overflow
                                    targetClient = overflow
                                    overflow.openTerminalChannel(sib.remoteCommand, sib.requestPty)
                                } else {
                                    if (!targetClient.isConnected) throw firstErr
                                    // One brief retry in case a transient probe/exec channel
                                    // was releasing the server's last MaxSessions slot.
                                    Thread.sleep(150)
                                    targetClient.openTerminalChannel(sib.remoteCommand, sib.requestPty)
                                }
                            }
                            if (targetClient !== newClient) {
                                val becomeOwner = overflowOwnersAssigned.add(targetClient)
                                _sessions.update { map ->
                                    val cur = map[sibId] ?: return@update map
                                    val ownerCfg = if (becomeOwner) {
                                        config.copy(authMethod = config.authMethod.deepCopy())
                                    } else {
                                        cur.connectionConfig
                                    }
                                    map + (sibId to cur.copy(client = targetClient, connectionConfig = ownerCfg))
                                }
                            }
                            attachShellChannel(sibId, sibChannel)
                            runCatching { if (oldSibShell?.isConnected == true) oldSibShell.disconnect() }
                            val sibPending = if (sib.remoteCommand == null) {
                                buildPendingCommands(sibId, sib.sessionManager, sib.postLoginCommand, sib.postLoginBeforeSessionManager)
                            } else emptyList()
                            if (sibPending.isNotEmpty()) {
                                sib.terminalSession?.setPendingCommands(sibPending)
                            }
                            sib.terminalSession?.reconnect(sibChannel, targetClient)
                            reattachedSiblingIds.add(sibId)
                        }.onFailure { e ->
                            Log.w(TAG, "Failed to reattach reused sibling session $sibId: ${e.message}")
                            breadcrumb(session.profileId, "sibling reattach failed ($sibId): ${e.message}")
                            failedSiblingIds.add(sibId)
                            val displayName = sib.chosenSessionName?.takeIf { it.isNotBlank() }
                                ?: sib.label.ifBlank { sibId.take(8) }
                            val reason = e.message?.takeIf { it.isNotBlank() }
                                ?: "failed to reopen remote shell"
                            failedSiblingDetails.add(displayName to reason)
                        }
                    } else {
                        reattachedSiblingIds.add(sibId)
                    }
                }

                // Restore port forwards
                val forwards = _sessions.value[sessionId]?.activeForwards.orEmpty()
                if (forwards.isNotEmpty()) {
                    // Clear current list, re-apply will add them back
                    _sessions.update { map ->
                        val existing = map[sessionId] ?: return@update map
                        map + (sessionId to existing.copy(activeForwards = emptyList()))
                    }
                    // ExitOnForwardFailure: if a critical forward (e.g. the
                    // MCP -R tunnel) can't re-bind — typically because a
                    // stale server-side bind hasn't timed out yet — tear the
                    // transport down and let the backoff loop retry rather
                    // than declaring a healthy-but-forward-less session.
                    if (!applyPortForwards(sessionId, forwards)) {
                        Log.w(TAG, "Critical port forward failed to re-bind for $sessionId — retrying")
                        try { newClient.disconnect() } catch (_: Throwable) {}
                        overflowOwnersAssigned.forEach { c -> runCatching { c.disconnect() } }
                        delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                        continue
                    }
                }

                if (_sessions.value[sessionId] == null || currentThread.isInterrupted) {
                    runCatching { newClient.disconnect() }
                    overflowOwnersAssigned.forEach { c -> runCatching { c.disconnect() } }
                    return
                }

                // Emit any dropped-sibling notice BEFORE publishing CONNECTED on
                // _sessions so observers waking on the status transition already
                // see the buffered UserMessageBus event (avoids a scheduling race
                // between the reconnect thread and status-polling callers/tests).
                if (failedSiblingDetails.isNotEmpty()) {
                    val firstReason = failedSiblingDetails.first().second
                    val summary = if (failedSiblingDetails.size == 1) {
                        "Tab '${failedSiblingDetails.single().first}' closed during reconnect: $firstReason"
                    } else {
                        val names = failedSiblingDetails.joinToString(", ") { "'${it.first}'" }
                        "Closed ${failedSiblingDetails.size} tabs ($names) during reconnect: $firstReason"
                    }
                    userMessageBus.error(summary)
                }

                // Atomically publish CONNECTED for the primary session and all
                // reattached siblings now that every shell channel is open (#888).
                _sessions.update { map ->
                    var updated = map
                    updated[sessionId]?.let { cur ->
                        updated = updated + (sessionId to cur.copy(status = SessionState.Status.CONNECTED))
                    }
                    for (sibId in reattachedSiblingIds) {
                        updated[sibId]?.let { sib ->
                            updated = updated + (sibId to sib.copy(status = SessionState.Status.CONNECTED))
                        }
                    }
                    for (sibId in failedSiblingIds) {
                        updated[sibId]?.let { sib ->
                            updated = updated + (sibId to sib.copy(
                                status = SessionState.Status.DISCONNECTED,
                                shellChannel = null,
                            ))
                        }
                    }
                    updated
                }
                // Post-reconnect hooks (e.g. USB/IP host re-attach). Best-effort —
                // a failing hook must never abort a successful reconnect.
                reconnectHooks[sessionId]?.let { hook -> runCatching { runBlocking { hook() } } }
                Log.d(TAG, "Reconnected $sessionId on attempt $attempt")
                return
            } catch (e: Exception) {
                runCatching { candidateClient?.disconnect() }
                if (_sessions.value[sessionId] == null || currentThread.isInterrupted || e is InterruptedException) {
                    Log.d(TAG, "Reconnect aborted for $sessionId during dial")
                    return
                }
                Log.w(TAG, "Reconnect attempt $attempt failed for $sessionId: ${e.message}")
                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            }
        }

        Log.d(TAG, "Reconnect failed after $attempt attempts for $sessionId")
        val exhaustedClient = _sessions.value[sessionId]?.client
        _sessions.update { map ->
            var updated = map
            updated[sessionId]?.let { cur ->
                updated = updated + (sessionId to cur.copy(status = SessionState.Status.DISCONNECTED))
            }
            if (exhaustedClient != null) {
                for ((id, sib) in updated) {
                    if (id != sessionId && sib.client === exhaustedClient && sib.status == SessionState.Status.RECONNECTING) {
                        updated = updated + (id to sib.copy(status = SessionState.Status.DISCONNECTED))
                    }
                }
            }
            updated
        }
        } finally {
            reconnectThreads.remove(sessionId, currentThread)
            reconnectingInFlight.remove(sessionId)
        }
    }

    /**
     * Dial an overflow [SshConnection] during [attemptReconnect] when the
     * newly-reconnected primary client rejects a sibling shell open with
     * [SshChannelOpenRejectedException] (e.g. `MaxSessions` was lowered on the
     * server while sessions were already open, #888). Returns null on any
     * auth/network/host-key failure so the caller records and surfaces the
     * original rejection reason.
     */
    private fun dialReconnectOverflowClient(
        sessionId: String,
        config: ConnectionConfig,
        proxy: HavenProxy?,
        previousClient: SshConnection?,
        currentThread: Thread,
    ): SshConnection? {
        val overflow = reconnectClientFactory(config).apply {
            fidoAuthenticator = previousClient?.fidoAuthenticator
            openKeychainClients = previousClient?.openKeychainClients
            hardwareKeySigner = previousClient?.hardwareKeySigner
        }
        return try {
            val hostKeyEntry = overflow.connectBlocking(
                config,
                proxy = proxy,
                trustedHostCaKeys = runBlocking { hostKeyVerifier.trustedHostCaKeys() },
            )
            if (hostKeyEntry != null) {
                when (val hkResult = runBlocking { hostKeyVerifier.verify(hostKeyEntry) }) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> runBlocking { hostKeyVerifier.accept(hkResult.entry) }
                    is HostKeyResult.KeyChanged -> {
                        Log.w(TAG, "Host key changed on overflow reconnect dial for $sessionId — aborting overflow")
                        overflow.disconnect()
                        return null
                    }
                }
            }
            if (_sessions.value[sessionId] == null || currentThread.isInterrupted) {
                runCatching { overflow.disconnect() }
                return null
            }
            overflow
        } catch (e: Exception) {
            Log.w(TAG, "Overflow reconnect dial failed for $sessionId: ${e.message}")
            runCatching { overflow.disconnect() }
            null
        }
    }

    /**
     * True when another live session or tunnel resource rides [sessionId]
     * as its jump host or port-forward carrier:
     *  - a session whose [SessionState.jumpSessionId] is [sessionId], or
     *  - a VNC/RDP/SMB profile registered in [SessionState.tunnelDependents] or [leases].
     */
    private fun hasActiveJumpDependents(sessionId: String): Boolean =
        _sessions.value.values.any {
            it.jumpSessionId == sessionId &&
                it.status != SessionState.Status.DISCONNECTED &&
                it.status != SessionState.Status.ERROR
        }

    fun isJumpOrTunnelCarrier(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        if (session.tunnelDependents.isNotEmpty()) return true
        if (leases.values.any { it.sessionId == sessionId }) return true
        return hasActiveJumpDependents(sessionId)
    }

    /**
     * Close only the interactive shell channel and terminal attachment on
     * [sessionId], keeping the underlying SSH connection and [SessionState]
     * CONNECTED so jump-host and tunnel dependents riding [sessionId]
     * stay alive (#764). Marks [SessionState.tunnelOpened] true so that once
     * the last jump/tunnel dependent disconnects, [sessionId] is torn down
     * instead of lingering forever without a terminal tab.
     */
    fun closeShellOnly(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = if (existing.client.isConnected) SessionState.Status.CONNECTED else existing.status,
                shellChannel = null,
                terminalSession = null,
                tunnelOpened = true,
                chosenSessionName = null,
                bypassSessionManager = false,
            ))
        }
        agentScrollback.remove(sessionId)
        sessionNotices.remove(sessionId)
        ioExecutor.execute {
            try { session.terminalSession?.close() } catch (e: Exception) {
                Log.e(TAG, "closeShellOnly: terminalSession.close() failed", e)
            }
            try {
                if (session.shellChannel?.isConnected == true) {
                    session.shellChannel.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "closeShellOnly: shellChannel.disconnect() failed", e)
            }
        }
    }

    /**
     * Close the terminal tab for [sessionId]. When [sessionId] is carrying
     * active jump-host or tunnel dependents (#764), tear down only its shell
     * channel and leave the SSH transport connected; otherwise remove the
     * session completely.
     */
    fun closeShellOrRemoveSession(sessionId: String) {
        if (isJumpOrTunnelCarrier(sessionId)) {
            Log.d(TAG, "Closing shell on $sessionId while keeping SSH transport alive for jump/tunnel dependents")
            closeShellOnly(sessionId)
        } else {
            removeSession(sessionId)
        }
    }

    fun removeSession(sessionId: String) {
        removedSessionIds.add(sessionId)
        val session = _sessions.value[sessionId] ?: return
        val parentJumpId = session.jumpSessionId
        // Cascade: disconnect any sessions that use this one as a jump host
        val dependents = _sessions.value.values.filter { it.jumpSessionId == sessionId }
        _sessions.update { it - sessionId }
        reconnectThreads.remove(sessionId)?.interrupt()
        agentScrollback.remove(sessionId)
        reconnectProxyProviders.remove(sessionId)
        reconnectHooks.remove(sessionId)
        sessionNotices.remove(sessionId)
        // Fire any tunnel leases riding on this session so their dependents
        // (VNC/RDP tabs) tear themselves down. Single-shot per lease; runs on
        // the caller thread (the consumer re-dispatches to its own scope).
        // Only true removal fires this — reconnect copies the entry in place
        // and never calls removeSession, so a network blip won't close tabs.
        fireParentGone(sessionId)
        ioExecutor.execute { tearDown(session) }
        for (dep in dependents) {
            Log.d(TAG, "Cascading disconnect from jump host $sessionId to ${dep.sessionId}")
            removeSession(dep.sessionId)
        }
        // If this session rode an upstream jump host that was auto-opened (or
        // had its own terminal tab closed via closeShellOnly) and now carries
        // no remaining jump/tunnel dependents, tear the jump host down too.
        if (parentJumpId != null) {
            val parentJump = _sessions.value[parentJumpId]
            if (parentJump != null && parentJump.tunnelOpened && !isJumpOrTunnelCarrier(parentJumpId)) {
                Log.d(TAG, "Tearing down orphaned jump host $parentJumpId (last dependent $sessionId removed)")
                removeSession(parentJumpId)
            }
        }
    }

    private fun fireParentGone(sessionId: String) {
        val affected = leases.values.filter { it.sessionId == sessionId }
        for (rec in affected) {
            if (leases.remove(rec.leaseId) != null && rec.closed.compareAndSet(false, true)) {
                Log.d(TAG, "Tunnel parent $sessionId gone — notifying dependent ${rec.dependentProfileId}")
                runCatching { rec.onParentGone() }
                    .onFailure { Log.w(TAG, "onParentGone callback for ${rec.dependentProfileId} threw", it) }
            }
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
     * The full bytes buffered in [sessionId]'s agent scrollback ring (raw
     * SSH stdout, capped at the ring size), or null if empty — replayed
     * into a fresh emulator when a recreated ViewModel reattaches so the
     * prior screen is restored instead of coming back blank (#272-SSH).
     * Mirrors LocalSessionManager.snapshotScrollback.
     */
    fun snapshotScrollback(sessionId: String): ByteArray? =
        agentScrollback[sessionId]?.snapshot()?.takeIf { it.isNotEmpty() }

    /**
     * Monotonic count of bytes appended to a session's agent scrollback
     * ring over its lifetime. Unlike the ring's [snapshot] size (which
     * saturates at the ring capacity), this never saturates and never
     * resets — so it's the right value to capture as a "baseline" if
     * you want to detect "have new bytes arrived since this point?"
     * Returns null when the session has no ring (i.e. never had a
     * terminal channel attached). (#161)
     */
    fun agentScrollbackTotalBytes(sessionId: String): Long? =
        agentScrollback[sessionId]?.totalBytesAppended

    /**
     * Identify the SSH session, if any, carrying a remote port-forward
     * for [bindPort] on the loopback. By convention Haven uses 8730 for
     * the MCP reverse tunnel — i.e. the session running the Claude Code
     * REPL on the workstation. Used by the `queue_terminal_input` MCP
     * tool to auto-detect which session to watch + type into. Returns
     * the first match (there should be at most one in practice). (#161)
     */
    fun findRemoteForwardSession(bindPort: Int): String? =
        _sessions.value.values.firstOrNull { state ->
            // Skip the dedicated headless MCP tunnel — it carries the same
            // `-R` but has no terminal to type into. The auto-detect wants
            // the interactive session running the agent's REPL.
            !state.headless && state.activeForwards.any {
                it.type == PortForwardType.REMOTE && it.bindPort == bindPort
            }
        }?.sessionId

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

    fun wasSessionRemoved(sessionId: String): Boolean = sessionId in removedSessionIds

    // --- Profile-level helpers ---

    fun getSessionsForProfile(profileId: String, includeHeadless: Boolean = false): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId && (includeHeadless || !it.headless) }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && !it.headless && it.status == SessionState.Status.CONNECTED
        }

    fun getProfileStatus(profileId: String): SessionState.Status? {
        val statuses = _sessions.value.values
            .filter { it.profileId == profileId && !it.headless }
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
        val session = (_sessions.value.values
            .firstOrNull { it.profileId == profileId && !it.headless && it.connectionConfig != null }
            ?: _sessions.value.values
                .firstOrNull { it.profileId == profileId && it.connectionConfig != null })
            ?: return null
        // Deep-copy auth material so tearDown() zeroing one session's bytes
        // doesn't corrupt the config for other sessions sharing the same profile.
        val config = session.connectionConfig!!
        val safeCopy = config.copy(
            authMethod = config.authMethod.deepCopy(),
            agentIdentities = config.agentIdentities.map { it.deepCopy() },
        )
        return safeCopy to session.sessionManager
    }

    fun removeAllSessionsForProfile(profileId: String, includeHeadless: Boolean = false) {
        val toRemove = _sessions.value.values
            .filter { it.profileId == profileId && (includeHeadless || !it.headless) }
            .map { it.sessionId }
        toRemove.forEach { removeSession(it) }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        snapshot.forEach { removedSessionIds.add(it.sessionId) }
        _sessions.update { emptyMap() }
        reconnectThreads.values.forEach { it.interrupt() }
        reconnectThreads.clear()
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

    fun setBypassSessionManager(sessionId: String, bypass: Boolean) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(bypassSessionManager = bypass))
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
     * Mark [sessionId] as having been auto-opened solely as a jump host for a
     * downstream SSH session (which tracks its dependency via
     * [SessionState.jumpSessionId]). When the last jump/tunnel dependent
     * disconnects, [removeSession] tears [sessionId] down automatically.
     */
    fun markJumpOpened(sessionId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(tunnelOpened = true))
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
        val candidates = mutableListOf<String>()
        _sessions.update { map ->
            map.mapValues { (_, session) ->
                if (dependentProfileId !in session.tunnelDependents) return@mapValues session
                val newDeps = session.tunnelDependents - dependentProfileId
                if (newDeps.isEmpty() && session.tunnelOpened) {
                    candidates += session.sessionId
                }
                session.copy(tunnelDependents = newDeps)
            }
        }
        val toTearDown = candidates.filter { !hasActiveJumpDependents(it) }
        for (sid in toTearDown) {
            Log.d(TAG, "Tearing down orphaned tunnel session $sid (last dependent $dependentProfileId released)")
            removeSession(sid)
        }
        return toTearDown
    }

    /** A first-class handle for a tunnel-dependent resource. See [acquireTunnelLease]. */
    interface TunnelLease : AutoCloseable {
        val sessionId: String
        val dependentProfileId: String
        /** Release this dependent. Idempotent. */
        override fun close()
    }

    /**
     * Acquire a lease tying a tunnel-dependent resource (a VNC/RDP tab and
     * its client) to the SSH session [sessionId] that carries its forward.
     *
     * The dependent must already have been registered on the session via
     * [markTunnelOpened] / [attachTunnelDependent] (done by the connect
     * orchestrator, which knows whether the SSH was auto-opened or reused).
     * The lease owns the *local port forward*, the *teardown callback*, and
     * the *dependent release*:
     *  - [onParentGone] fires when the SSH session is removed for any reason
     *    (user disconnect, network-death teardown, jump-host cascade) so the
     *    dependent closes itself. It runs on the [removeSession] caller
     *    thread — the consumer must re-dispatch to its own scope.
     *  - [close] (consumer-initiated: tab closed / connect failed / swipe)
     *    removes the forward and releases the dependent, tearing the SSH down
     *    iff it was opened solely for this tunnel.
     *
     * Both directions are single-shot and collision-free, so a tab-close
     * racing an SSH teardown can't double-free (#121).
     */
    fun acquireTunnelLease(
        sessionId: String,
        dependentProfileId: String,
        localForwardPort: Int?,
        onParentGone: () -> Unit,
    ): TunnelLease {
        val rec = LeaseRecord(
            leaseId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            dependentProfileId = dependentProfileId,
            localForwardPort = localForwardPort,
            onParentGone = onParentGone,
        )
        leases[rec.leaseId] = rec
        return object : TunnelLease {
            override val sessionId = sessionId
            override val dependentProfileId = dependentProfileId
            override fun close() = closeLease(rec.leaseId)
        }
    }

    /**
     * Tear down the tunnel-dependent resources for [dependentProfileId] —
     * used when the user disconnects that VNC/RDP/SMB profile directly from
     * the connections list. Fires each matching lease's onParentGone (so the
     * Desktop tab closes even when the carrying SSH is a shared / user-opened
     * session that legitimately survives), then releases the SSH dependent
     * (tearing the SSH down iff it was opened solely for this tunnel). Returns
     * the SSH session ids actually torn down. (#121: the parent-gone cascade
     * alone can't close the tab when the SSH isn't removed.)
     */
    fun teardownTunnelDependent(dependentProfileId: String): List<String> {
        val matching = leases.values.filter { it.dependentProfileId == dependentProfileId }
        for (rec in matching) {
            if (leases.remove(rec.leaseId) != null && rec.closed.compareAndSet(false, true)) {
                runCatching { rec.onParentGone() }
                    .onFailure { Log.w(TAG, "onParentGone for $dependentProfileId threw", it) }
            }
        }
        return releaseTunnelDependent(dependentProfileId)
    }

    /** Consumer-initiated lease release. Idempotent; does not fire [LeaseRecord.onParentGone]. */
    private fun closeLease(leaseId: String) {
        val rec = leases.remove(leaseId) ?: return
        if (!rec.closed.compareAndSet(false, true)) return
        rec.localForwardPort?.let { port ->
            _sessions.value[rec.sessionId]?.client?.let { client ->
                runCatching { client.delPortForwardingL("127.0.0.1", port) }
                    .onFailure { Log.w(TAG, "Failed to remove tunnel forward $port on ${rec.sessionId}", it) }
            }
        }
        releaseTunnelDependent(rec.dependentProfileId)
    }

    /**
     * Create a [ProxyJump] from a connected jump host session.
     * Returns null if the session doesn't exist or isn't connected.
     */
    fun createProxyJump(jumpSessionId: String): HavenProxy? {
        val jumpSession = _sessions.value[jumpSessionId]
        if (jumpSession == null) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId not found in sessions map")
            return null
        }
        if (jumpSession.status != SessionState.Status.CONNECTED) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId status=${jumpSession.status}, expected CONNECTED")
            return null
        }
        // A sshlib jump session opens its own direct-tcpip transport to the
        // target instead of going through a JSch Proxy. Deferred to dial time
        // because only the dialer knows the target host/port.
        (jumpSession.client as? SshlibConnection)?.let { sshlibJump ->
            Log.d(TAG, "createProxyJump: sshlib jump session $jumpSessionId")
            return HavenProxy(
                jschProxy = null,
                sshlibJump = { host, port -> sshlibJump.openJumpTransport(host, port) },
            )
        }
        val jschSession = (jumpSession.client as? SshClient)?.jschSession
        if (jschSession == null) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId has no jschSession (client.isConnected=${jumpSession.client.isConnected})")
            return null
        }
        Log.d(TAG, "createProxyJump: success for $jumpSessionId (jsch connected=${jschSession.isConnected})")
        return HavenProxy(ProxyJump(jschSession))
    }

    /**
     * Activate port forwards on a connected session.
     * Each rule is applied independently; failures are logged but don't block others.
     */
    /**
     * Activate [rules] on [sessionId]. Returns true iff every rule the
     * caller treats as load-bearing came up.
     *
     * A rule fails the call when its [PortForwardInfo.critical] flag is
     * set and the bind throws — this is the `ExitOnForwardFailure`
     * semantics the MCP reverse tunnel relies on, so a stale server-side
     * `-R` bind surfaces as a failed (re)connect instead of a healthy
     * session with no working forward. Non-critical forwards keep the
     * previous best-effort behaviour (log and continue), so the return
     * value is true unless a *critical* forward failed.
     */
    fun applyPortForwards(sessionId: String, rules: List<PortForwardInfo>): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        val activated = mutableListOf<PortForwardInfo>()
        var criticalFailed = false

        for (rule in rules) {
            if (_sessions.value[sessionId] == null) {
                Log.d(TAG, "applyPortForwards aborted — session $sessionId removed")
                break
            }
            try {
                when (rule.type) {
                    PortForwardType.LOCAL -> {
                        if (rule.bindPort != 0 && (
                                activated.any { (it.type == PortForwardType.LOCAL || it.type == PortForwardType.DYNAMIC) && (it.bindPort == rule.bindPort || it.actualBoundPort == rule.bindPort) } ||
                                    isLocalPortHeldByOtherSession(sessionId, rule.bindPort)
                                )
                        ) {
                            Log.d(TAG, "Skipping L ${rule.bindAddress}:${rule.bindPort} on $sessionId — already bound")
                            continue
                        }
                        val actualPort = bindLocalWithRetry(session.client, rule)
                        val bound = rule.copy(actualBoundPort = actualPort)
                        activated.add(bound)
                        recordActiveForward(sessionId, bound)
                        Log.d(TAG, "Port forward activated: L ${rule.bindAddress}:$actualPort -> ${rule.targetHost}:${rule.targetPort}")
                    }
                    PortForwardType.REMOTE -> {
                        if (rule.selfHealOnBindFailure) {
                            stripConflictingRemoteForwards(sessionId, session.profileId, session.client, rule.bindPort)
                        } else if (rule.bindPort != 0 && (
                                activated.any { it.type == PortForwardType.REMOTE && it.bindPort == rule.bindPort } ||
                                    isRemotePortHeldByOtherSession(sessionId, session.profileId, rule.bindPort)
                                )
                        ) {
                            Log.d(TAG, "Skipping R ${rule.bindAddress}:${rule.bindPort} on $sessionId — already bound for ${session.profileId}")
                            continue
                        }
                        bindRemoteWithSelfHeal(session.client, rule)
                        activated.add(rule)
                        recordActiveForward(sessionId, rule)
                        Log.d(TAG, "Port forward activated: R ${rule.bindAddress}:${rule.bindPort} -> ${rule.targetHost}:${rule.targetPort}")
                    }
                    PortForwardType.DYNAMIC -> {
                        if (rule.bindPort != 0 && (
                                activated.any { (it.type == PortForwardType.LOCAL || it.type == PortForwardType.DYNAMIC) && (it.bindPort == rule.bindPort || it.actualBoundPort == rule.bindPort) } ||
                                    isLocalPortHeldByOtherSession(sessionId, rule.bindPort)
                                )
                        ) {
                            Log.d(TAG, "Skipping D ${rule.bindAddress}:${rule.bindPort} on $sessionId — already bound")
                            continue
                        }
                        val actualPort = session.client.setPortForwardingDynamic(
                            rule.bindAddress, rule.bindPort,
                        )
                        val bound = rule.copy(actualBoundPort = actualPort)
                        activated.add(bound)
                        recordActiveForward(sessionId, bound)
                        Log.d(TAG, "Port forward activated: D ${rule.bindAddress}:$actualPort (SOCKS5)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to activate port forward ${rule.ruleId}: ${e.message}")
                if (rule.critical) criticalFailed = true
            }
        }

        // After a clean (re)connect of a self-healing reverse tunnel, record
        // this connection's endpoint sshd PID per port so a future instance can
        // kill this session if it lingers stale and blocks the rebind, without
        // overwriting the PID of a sibling session forwarding a different port.
        if (!criticalFailed && _sessions.value[sessionId] != null) {
            activated
                .filter { it.selfHealOnBindFailure && it.type == PortForwardType.REMOTE }
                .forEach { recordTunnelSshPid(session.client, it.bindPort) }
        }
        return !criticalFailed
    }

    private fun recordActiveForward(sessionId: String, forward: PortForwardInfo) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            val deduped = existing.activeForwards.filterNot { prev ->
                prev.ruleId == forward.ruleId || (prev.type == forward.type && prev.bindPort == forward.bindPort && forward.bindPort != 0)
            }
            map + (sessionId to existing.copy(activeForwards = deduped + forward))
        }
    }

    private fun isLocalPortHeldByOtherSession(sessionId: String, port: Int): Boolean =
        _sessions.value.values.any { s ->
            s.sessionId != sessionId &&
                s.status != SessionState.Status.DISCONNECTED &&
                s.status != SessionState.Status.ERROR &&
                s.activeForwards.any {
                    (it.type == PortForwardType.LOCAL || it.type == PortForwardType.DYNAMIC) &&
                        (it.bindPort == port || it.actualBoundPort == port)
                }
        }

    private fun isRemotePortHeldByOtherSession(sessionId: String, profileId: String, port: Int): Boolean =
        _sessions.value.values.any { s ->
            s.sessionId != sessionId &&
                s.profileId == profileId &&
                s.status != SessionState.Status.DISCONNECTED &&
                s.status != SessionState.Status.ERROR &&
                s.activeForwards.any { it.type == PortForwardType.REMOTE && it.bindPort == port }
        }

    /**
     * Ensure at most one session per profile carries a self-healing remote
     * forward on [bindPort] in [SessionState.activeForwards]. Releases the
     * remote port on any sibling session's connected client and drops the rule
     * from its [SessionState.activeForwards] so a later reconnect of that
     * sibling cannot re-bind [bindPort] and kill [newOwnerSessionId]'s `sshd`.
     */
    private fun stripConflictingRemoteForwards(
        newOwnerSessionId: String,
        profileId: String,
        newOwnerClient: SshConnection,
        bindPort: Int,
    ) {
        val siblings = _sessions.value.values.filter { s ->
            s.sessionId != newOwnerSessionId &&
                s.profileId == profileId &&
                s.activeForwards.any { it.type == PortForwardType.REMOTE && it.bindPort == bindPort }
        }
        for (sib in siblings) {
            if (sib.client !== newOwnerClient && sib.client.isConnected) {
                runCatching { sib.client.delPortForwardingR(bindPort) }
            }
        }
        if (siblings.isNotEmpty()) {
            val siblingIds = siblings.map { it.sessionId }.toSet()
            _sessions.update { map ->
                var updated = map
                for (sibId in siblingIds) {
                    val existing = updated[sibId] ?: continue
                    updated = updated + (sibId to existing.copy(
                        activeForwards = existing.activeForwards.filterNot {
                            it.type == PortForwardType.REMOTE && it.bindPort == bindPort
                        },
                    ))
                }
                updated
            }
        }
    }

    /**
     * Bind a LOCAL (`-L`) forward, retrying "already in use" for a few
     * seconds. jsch binds without SO_REUSEADDR, so the port is unbindable
     * while accepted sockets from a just-closed transfer sit in TIME_WAIT,
     * or while a dying predecessor's PortWatcher is still shutting down —
     * both hit on reconnect and on remove→add of the same port (found
     * live: a 119MB APK download through the forward, then a rebind).
     */
    private fun bindLocalWithRetry(client: SshConnection, rule: PortForwardInfo): Int {
        var lastFailure: Exception? = null
        repeat(4) {
            try {
                return client.setPortForwardingL(
                    rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort,
                )
            } catch (e: Exception) {
                if (e.message?.contains("already", ignoreCase = true) != true) throw e
                lastFailure = e
                Log.w(TAG, "L ${rule.bindPort} bind busy (${e.message}) — retrying")
                try {
                    Thread.sleep(750)
                } catch (_: InterruptedException) {
                    throw e
                }
            }
        }
        throw lastFailure!!
    }

    /**
     * Bind a REMOTE (`-R`) forward, with one-shot self-heal for
     * [PortForwardInfo.selfHealOnBindFailure] forwards: if the bind throws
     * (typically "Address already in use" from a stale `sshd` holding the
     * port after an app restart / roam), free the stale holder over the
     * already-connected session and retry once. A second failure propagates
     * to the caller (which sets `criticalFailed` as before).
     */
    private fun bindRemoteWithSelfHeal(client: SshConnection, rule: PortForwardInfo) {
        try {
            client.setPortForwardingR(rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort)
        } catch (e: Exception) {
            if (!rule.selfHealOnBindFailure) throw e
            Log.w(TAG, "R ${rule.bindPort} bind failed (${e.message}) — self-healing stale forward on endpoint")
            if (!freeStaleReverseForward(client, rule.bindPort)) throw e
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {}
            client.setPortForwardingR(rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort)
            Log.i(TAG, "R ${rule.bindPort} self-heal succeeded — rebound after freeing stale holder")
        }
    }

    /**
     * Over the connected [client], kill the STALE prior tunnel's `sshd`
     * session that is still holding the reverse-forward port on the endpoint,
     * so a fresh `-R` bind can succeed. Uses the PID recorded by
     * [recordTunnelSshPid] on the previous successful connect — because
     * `ss -p` without root cannot attribute the listen socket to a PID (sshd
     * hardens its processes, `dumpable=0`, so `/proc/<pid>/fd` is root-only).
     * Surgical + root-free: kills only a live `sshd-session` PID (comm check
     * guards against PID reuse) that is NOT the current connection's own
     * session (`$PPID`). Returns true iff it killed the recorded holder.
     */
    private fun freeStaleReverseForward(client: SshConnection, port: Int): Boolean {
        val cmd =
            "F=\"\$HOME/.haven-mcp-tunnel-$port.pid\"; L=\"\$HOME/.haven-mcp-tunnel.pid\"; " +
                "old=\$(cat \"\$F\" 2>/dev/null); " +
                "if [ -z \"\$old\" ]; then old=\$(cat \"\$L\" 2>/dev/null); fi; " +
                "rm -f \"\$F\" \"\$L\" 2>/dev/null; " +
                "if [ -n \"\$old\" ] && [ \"\$old\" != \"\$PPID\" ] && grep -qa sshd \"/proc/\$old/comm\" 2>/dev/null; then " +
                "kill \"\$old\" 2>/dev/null && echo HEALED || echo KILLFAIL; " +
                "else echo NOHOLDER; fi"
        return try {
            val r = runBlocking { client.execCommand(cmd) }
            Log.i(TAG, "self-heal on :$port -> ${r.stdout.trim().ifEmpty { r.stderr.trim() }}")
            r.stdout.contains("HEALED")
        } catch (e: Exception) {
            Log.w(TAG, "self-heal exec on :$port failed: ${e.message}")
            false
        }
    }

    /**
     * Record THIS connection's endpoint-side `sshd-session` PID (`$PPID` of an
     * exec) to `~/.haven-mcp-tunnel-[port].pid` after a successful self-heal
     * forward bind, so a later instance whose bind is blocked by this (now
     * stale) session can find + kill it (see [freeStaleReverseForward]).
     * Best-effort.
     */
    private fun recordTunnelSshPid(client: SshConnection, port: Int) {
        try {
            runBlocking {
                client.execCommand(
                    "echo \"\$PPID\" > \"\$HOME/.haven-mcp-tunnel-$port.pid\" 2>/dev/null; " +
                        "rm -f \"\$HOME/.haven-mcp-tunnel.pid\" 2>/dev/null",
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Remove any port forward with [ruleId] from [sessionId] (releasing the
     * bind if connected and dropping it from [SessionState.activeForwards]).
     */
    fun forgetPortForward(sessionId: String, ruleId: String) {
        val session = _sessions.value[sessionId] ?: return
        val matching = session.activeForwards.filter { it.ruleId == ruleId }
        for (fwd in matching) {
            removePortForward(sessionId, fwd)
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
     *
     * When one-tap X11 or PulseAudio forwarding (#605) is enabled on the
     * session's [ConnectionConfig], the `export DISPLAY=...` / `export PULSE_SERVER=...`
     * snippet is queued in the raw shell and, if a session manager is used,
     * queued inside the attached session as well so re-attached tmux/zellij/screen
     * windows also pick up the forwarded endpoints.
     */
    internal fun buildPendingCommands(
        sessionId: String,
        manager: SessionManager,
        postLoginCommand: String?,
        beforeSessionManager: Boolean = false,
    ): List<String> = buildList {
        val cfg = _sessions.value[sessionId]?.connectionConfig
        val envCmd = buildForwardingEnvExportCommand(
            forwardX11 = cfg?.forwardX11 == true,
            forwardPulseAudio = cfg?.forwardPulseAudio == true,
        )
        val sessionCmd = buildSessionManagerCommand(sessionId, manager)
        val loginCmd = postLoginCommand?.takeIf { it.isNotBlank() }
        if (beforeSessionManager) {
            envCmd?.let { add(it) }
            loginCmd?.let { add(it) }
            sessionCmd?.let {
                add(it)
                envCmd?.let { env -> add(env) }
            }
        } else {
            envCmd?.let { add(it) }
            sessionCmd?.let {
                add(it)
                envCmd?.let { env -> add(env) }
            }
            loginCmd?.let { add(it) }
        }
    }

    /**
     * Build the session manager command string for a given session, or null if none.
     * Uses the user-chosen session name if set, otherwise a deterministic name.
     */
    private fun buildSessionManagerCommand(sessionId: String, manager: SessionManager): String? {
        val session = _sessions.value[sessionId]
        // Per-session bypass wins over both the manager template and any
        // sessionCommandOverride — this is the "Plain shell" picker path.
        if (session?.bypassSessionManager == true) return null
        val commandTemplate = manager.command ?: return null
        val rawName = session?.chosenSessionName
            ?: session?.label ?: sessionId.take(8)
        val sessionName = SessionManager.sanitizeSessionName(rawName)
        // User override replaces the built-in command template. A blank
        // override is treated as absent: the field is cleared from the profile
        // editor by emptying the text box, and a stray blank must not swallow
        // the manager command entirely.
        val override = session?.sessionCommandOverride?.takeIf { it.isNotBlank() }
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
            if (session.sftpSession?.isConnected == true) {
                session.sftpSession.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tearDown: sftpSession.close() failed", e)
        }
        try {
            if (session.shellChannel?.isConnected == true) {
                session.shellChannel.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tearDown: shellChannel.disconnect() failed", e)
        }
        // Only drop the underlying SSH connection when no other live session
        // still rides it. Connection reuse (a 2nd terminal on an already-
        // connected profile) shares one SshClient across several shell
        // channels; tearing the client down here would kill the sibling
        // session. removeSession() has already pulled this session from the
        // map, so this checks the *remaining* sessions.
        val clientStillInUse = _sessions.value.values.any {
            it.client === session.client &&
                it.status != SessionState.Status.DISCONNECTED &&
                it.status != SessionState.Status.ERROR
        }
        if (clientStillInUse) {
            Log.d(TAG, "tearDown: keeping shared SSH client alive for ${session.sessionId}'s sibling session(s)")
        } else {
            try { session.client.disconnect() } catch (e: Exception) {
                Log.e(TAG, "tearDown: client.disconnect() failed", e)
            }
        }
        // Zero out auth material so it doesn't linger in heap
        session.connectionConfig?.authMethod?.clearSensitiveMaterial()
        session.connectionConfig?.agentIdentities?.forEach { identity ->
            identity.keyBytes.fill(0)
            identity.passphrase?.fill(0)
        }
    }
}
