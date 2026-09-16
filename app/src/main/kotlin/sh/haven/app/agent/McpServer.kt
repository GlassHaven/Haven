package sh.haven.app.agent

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.AgentAuditEvent
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mcp.HttpResponse
import sh.haven.core.mcp.McpError
import sh.haven.core.mcp.ParsedHttpRequest
import sh.haven.core.mcp.SessionExpiredError
import sh.haven.core.mcp.isLoopbackOrigin
import sh.haven.core.mcp.jsonRpcError
import sh.haven.core.mcp.jsonRpcResult
import sh.haven.core.mcp.serveHttpConnection
import sh.haven.core.mcp.textResponse
import sh.haven.core.mcp.writeHttpResponse
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val TAG = "McpServer"

/** Max time to wait for the user to answer a consent prompt before returning a
 *  retryable error. An interactive human needs time to notice the prompt,
 *  switch to Haven and tap — 8s was unusable in practice. Held below the
 *  socket read timeout (raised in lockstep below) so the connection isn't
 *  dropped mid-wait. If the MCP client gives up sooner, [AgentConsentManager]
 *  clears the orphaned prompt in a finally block so the sheet never sticks. */
private const val CONSENT_WAIT_MS: Long = 55_000L

/**
 * Maximum concurrently-open MCP connections across the kernel-socket binders
 * (#mcp-backbone Stage 4). Keep-alive means a connection can now hold its
 * worker thread between requests, so the old thread-per-connection-unbounded
 * shape needed a ceiling; connections beyond it get an immediate 503.
 */
private const val MAX_CONCURRENT_CONNECTIONS = 64

/**
 * The trust origin of an accepted MCP connection, tagged **at bind time** by
 * the listener that accepted it — never inferred from the peer address
 * (#mcp-backbone Stage 2). The old inference (`peer.isLoopbackAddress ⇒
 * device-trusted`) failed in two directions: a reverse-tunnel (`-R`) carrier
 * delivers *remote* traffic to the phone's 127.0.0.1, and any co-resident app
 * with INTERNET can dial another app's loopback listener. Each binder now
 * declares what its sockets actually are:
 *
 * - [DEVICE] — the plain loopback binder (8730–8739): an on-device process
 *   (in-proot agent, adb forward, curl). The only origin eligible for the
 *   `trustLoopbackMcpClients` auto-trust opt-in.
 * - [TUNNELED] — the dedicated loopback binder the SSH `-R` carriers target
 *   ([McpNearCarrier]): the far end is a remote host, so it is NEVER
 *   device-trusted and always runs the full pairing + consent gate.
 * - [LAN] / [WIREGUARD] — networked peers; always the full gate.
 */
internal enum class McpOrigin { DEVICE, TUNNELED, LAN, WIREGUARD }

/** The one MCP resources/read resource: a live snapshot of Haven's own rendered UI.
 *  The file-shaped sibling of the `capture_haven_ui` tool — an agent reads it to
 *  see the current screen without a tool call (VISION.md §1a). (Don't write the
 *  literal slash-star resources glob in a kdoc; it opens a nested block comment.) */
private const val UI_SCREEN_RESOURCE_URI = "ui://haven/screen"

/** Backoff between WireGuard (re)bind attempts when no tunnel is up yet
 *  or the listener dropped (roam re-handshake, tunnel released). */
private const val WG_RETRY_MS: Long = 5_000L

/**
 * Minimal Model Context Protocol (MCP) server for Haven.
 *
 * Binds to **127.0.0.1 only** (loopback) so the server is reachable
 * from local processes (any MCP client running in PRoot, a script,
 * a curl test) but not from the LAN. This is the agent transport
 * that implements Haven's "shared viewport" principle:
 * every observable state and action a human has in the UI should be
 * reachable by an agent through the same underlying ViewModels and
 * repositories.
 *
 * ### Protocol
 *
 * Implements the 2025-06-18 **Streamable HTTP** transport. A single
 * `POST /mcp` endpoint accepts one JSON-RPC 2.0 message and responds
 * with a single JSON body. No SSE, no WebSocket. Connections are
 * keep-alive (#mcp-backbone Stage 4, `core:mcp`), so a client holds one
 * warm connection instead of dialling per request.
 *
 * ### Sessions
 *
 * The server is process-stateful per the streamable-HTTP spec:
 * `initialize` mints a UUID, returned in the `Mcp-Session-Id` response
 * header. The client echoes it on subsequent requests so the server
 * can re-resolve the paired client identity without re-prompting.
 * When the server doesn't recognise a presented session id (because
 * the process restarted, lost its in-memory session map, etc.) it
 * responds with HTTP **404** instead of HTTP 200 + JSON-RPC error
 * — the spec-defined signal that tells the client to re-`initialize`
 * automatically. Without this, Claude Code's MCP transport sees a
 * generic JSON-RPC -32001 and gets wedged until the user tears down
 * its session entirely.
 *
 * Clients that don't send `Mcp-Session-Id` must present their pairing
 * token (`Authorization: Bearer …`) instead. The old fallback — trusting
 * the last-seen `clientInfo.name` from this process's most recent
 * `initialize` — authenticated a server-side sticky NAME that any peer on
 * the same carrier could free-ride, and is gone (#mcp-backbone Stage 3).
 *
 * Supported methods:
 * - `initialize` — protocol handshake, returns server info + tools capability
 * - `notifications/initialized` — acknowledged, no-op
 * - `tools/list` — returns the available tool definitions
 * - `tools/call` — dispatches to a named tool, passing arguments
 *
 * ### Security model
 *
 * Trust is decided by the connection's [McpOrigin] (tagged at bind time by
 * the accepting listener) plus pairing + per-action consent. Only
 * [McpOrigin.DEVICE] (the plain loopback binder) can skip the gate, and only
 * when the user has opted in via `trustLoopbackMcpClients` (default OFF —
 * on stock Android any co-resident app with INTERNET can reach another
 * app's loopback listener, so loopback reachability is NOT proof of being
 * this device's user). Reverse-tunneled (`-R`) traffic lands on a separate
 * [McpOrigin.TUNNELED] listener and always runs the full gate, as do LAN
 * and WireGuard peers. (#mcp-backbone Stage 2)
 */
@Singleton
class McpServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val reticulumSessionManager: sh.haven.core.reticulum.ReticulumSessionManager,
    private val reticulumForwardServer: sh.haven.core.reticulum.ReticulumForwardServer,
    private val rcloneClient: RcloneClient,
    private val mailSessionManager: sh.haven.core.mail.MailSessionManager,
    private val sftpStreamServer: SftpStreamServer,
    private val hlsStreamServer: HlsStreamServer,
    private val ffmpegExecutor: FfmpegExecutor,
    private val preferencesRepository: UserPreferencesRepository,
    private val terminalFontInstaller: TerminalFontInstaller,
    private val localSessionManager: LocalSessionManager,
    private val auditRecorder: AgentAuditRecorder,
    private val consentManager: AgentConsentManager,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val workspaceRepository: sh.haven.core.data.repository.WorkspaceRepository,
    private val workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher,
    private val tunnelConfigRepository: sh.haven.core.data.repository.TunnelConfigRepository,
    private val tunnelManager: sh.haven.core.tunnel.TunnelManager,
    private val terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
    private val portKnocker: sh.haven.core.knock.PortKnocker,
    private val spaSender: sh.haven.core.spa.SpaSender,
    private val connectionLogRepository: sh.haven.core.data.repository.ConnectionLogRepository,
    private val servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
    private val syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository,
    private val mailRuleRepository: sh.haven.core.data.repository.MailRuleRepository,
    private val mailWatchManager: sh.haven.app.agent.mailrules.MailWatchManager,
    private val agentActivityHolder: sh.haven.core.data.agent.AgentActivityHolder,
    private val terminalInputQueue: TerminalInputQueue,
    private val prootInstallLogRepository: sh.haven.core.data.repository.ProotInstallLogRepository,
    private val sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    private val knownHostDao: sh.haven.core.data.db.KnownHostDao,
    private val stepCaConfigRepository: sh.haven.core.data.repository.StepCaConfigRepository,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val usbBroker: sh.haven.core.usb.UsbBroker,
    private val usbIpServer: sh.haven.core.usb.UsbIpServer,
    private val usbDriveVmManager: sh.haven.app.usb.UsbDriveVmManager,
    private val umlRecoveryManager: sh.haven.app.usb.UmlRecoveryManager,
    private val presentationManager: sh.haven.core.data.agent.AgentPresentationManager,
    private val mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder,
    private val mcpTunnelManager: McpTunnelManager,
    // Serial session managers for the serial↔TCP bridge (bridge_serial_to_tcp).
    private val btSerialSessionManager: sh.haven.core.btserial.BtSerialSessionManager,
    private val bleSerialSessionManager: sh.haven.core.bleserial.BleSerialSessionManager,
    private val usbSerialSessionManager: sh.haven.core.usbserial.UsbSerialSessionManager,
    // OpenAI endpoint (openai_* tools): the session manager holding connect-time
    // model caches, plus the tunnel resolver so an MCP chat on a tunnel-routed
    // profile dials through the tunnel (fail closed) like the connect path does.
    private val openAiSessionManager: sh.haven.core.openai.OpenAiSessionManager,
    private val tunnelResolver: sh.haven.core.tunnel.TunnelResolver,
    // Capture + drive Haven's OWN rendered UI (self-hosting loop, §1a).
    // Registered with the foreground activity by MainActivity.onResume.
    private val havenUiBridge: HavenUiBridge,
    // Tier-3 standing policies: scoped, rate-capped, expiring no-prompt
    // grants consulted at the consent gate (VISION.md "Consent tiers").
    private val standingPolicyEnforcer: StandingPolicyEnforcer,
    private val standingPolicyRepository: sh.haven.core.data.repository.StandingPolicyRepository,
    // One-shot exec on a saved SSH profile for run_command (#367).
    private val headlessSshExec: HeadlessSshExec,
    // Defaulted so the manual McpServer constructions in unit tests (which
    // don't exercise the auth-prompt verbs) compile without it; Hilt always
    // injects the real @Singleton in production.
    private val pendingAuthPromptHolder: sh.haven.core.data.agent.PendingAuthPromptHolder =
        sh.haven.core.data.agent.PendingAuthPromptHolder(),
    // Same as above: defaulted for the manual test constructions; Hilt injects
    // the real @Singleton so this mirrors the same instance ConnectionsViewModel
    // writes the session-manager picker state to.
    private val sessionSelectionHolder: sh.haven.core.data.agent.SessionSelectionHolder =
        sh.haven.core.data.agent.SessionSelectionHolder(),
) : Closeable {

    /**
     * Transport-level budget for a consent prompt. The manager's own
     * requestConsent timeout is derived from this (90%, so it always fires
     * first): since #337 mechanism 3 a backgrounded call HOLDS inside
     * requestConsent, and without the margin the outer wrapper would win
     * every unanswered wait and report -32012 "timed out" where the contract
     * is -32000 "denied" with a DENIED audit row. Internal var (not const) so
     * unit tests can shrink the real-time wait.
     */
    internal var consentWaitMs: Long = CONSENT_WAIT_MS

    /**
     * Last authenticated (or, on `initialize`, self-asserted) client name.
     * Used for AUDIT attribution and as the key for consent memos /
     * standing policies — never as authentication (#mcp-backbone Stage 3):
     * the dispatch gate accepts only a bearer pairing token or a session id
     * minted by an authenticated `initialize` in this process. Set from the
     * bearer/session resolution on every authenticated request, so grant
     * keys are backed by a real credential.
     */
    @Volatile
    private var lastClientHint: String? = null

    /**
     * In-memory mirror of [UserPreferencesRepository.mcpClientTokenHashes]
     * (clientName → SHA-256 hex of its pairing token). Seeded at [start],
     * lazily on first use for the socket-less test paths, and updated when a
     * pairing mints a token. Null = not yet loaded.
     */
    @Volatile
    private var clientTokenHashes: Map<String, String>? = null

    /**
     * Keeps [allowedClients] and [clientTokenHashes] in sync with DataStore
     * after [start]'s initial seed. Pairing mutates the caches synchronously
     * (so a just-minted token works on the very next request), but *removals*
     * — un-pairing from the MCP tool or Settings — only write DataStore, so
     * without this collector a revoked bearer token stayed valid in the cache
     * until an app restart (#mcp-backbone Stage 3). Cancelled in [stopLocked].
     */
    @Volatile
    private var trustSyncJob: Job? = null

    /**
     * Session map for the streamable-HTTP transport. `initialize`
     * mints a UUID and stores [Session] here; subsequent requests
     * that present the same id in `Mcp-Session-Id` resolve to the
     * paired clientName. Entries older than [SESSION_TTL_MS] are
     * purged at next initialize to keep the map bounded.
     *
     * Unbounded growth is unlikely in practice (one entry per
     * client-initialize across the lifetime of the foreground process)
     * but the TTL guards against pathological churn.
     */
    private data class Session(val clientName: String, val createdAt: Long)
    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * In-memory mirror of [UserPreferencesRepository.mcpAllowedClients]
     * read once at server start and updated when [handleInitialize]
     * persists a new pairing. Lets every JSON-RPC dispatch check the
     * allowlist without re-collecting the Flow on the hot path. Treat
     * as authoritative within the lifetime of the running server;
     * Settings changes that wipe the allowlist while the server is up
     * are picked up on the next server restart.
     */
    @Volatile
    private var allowedClients: Set<String> = emptySet()

    /**
     * When true (an explicit opt-in, default OFF), MCP clients arriving on
     * the plain **loopback** binder ([McpOrigin.DEVICE] — `adb forward`, an
     * on-device agent) skip both the pairing prompt and per-action consent.
     * Off by default because loopback reachability is not proof of being
     * this device's user: any co-resident app with INTERNET can dial
     * 127.0.0.1 (#mcp-backbone Stage 2). The TUNNELED / LAN / WireGuard
     * origins are genuinely remote and ALWAYS keep the full pairing +
     * consent gate regardless of this flag. Mirrors [UserPreferencesRepository
     * .trustLoopbackMcpClients]; seeded in [start] and updated at
     * runtime via [setTrustLoopbackEnabled]. (#214)
     */
    @Volatile
    private var trustLoopbackEnabled: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Open connections across the kernel-socket binders (keep-alive cap, Stage 4). */
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Serializes [start] and [stop] so preference-driven toggles that
     * land close together can never leave the server with a half-torn
     * listener or a zombie accept thread.
     */
    private val lifecycleLock = Any()

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    /**
     * Dedicated loopback listener for reverse-tunneled (`-R`) carriers
     * (#mcp-backbone Stage 2). [McpNearCarrier] points its forward's
     * targetPort here instead of at [port], so traffic arriving from the
     * remote end of an SSH tunnel is tagged [McpOrigin.TUNNELED] and can
     * never inherit the loopback auto-trust the plain binder's
     * [McpOrigin.DEVICE] sockets are eligible for. A co-resident app that
     * dials this port directly just gets the *stricter* tag — full gate.
     */
    private var tunneledServerSocket: ServerSocket? = null
    private var tunneledServerThread: Thread? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    /** Port of the tunneled-origin loopback listener; 0 when not running. */
    @Volatile
    var tunneledPort: Int = 0
        private set

    private val _endpointUrl = MutableStateFlow<String?>(null)
    val endpointUrl: StateFlow<String?> = _endpointUrl.asStateFlow()

    /**
     * URL the endpoint is reachable at over the active WireGuard tunnel
     * (`http://<wg-interface-ip>:<port>/mcp`), or null when not bound on a
     * WG tunnel. Stable across the device's WiFi/hotspot roams. (#176)
     */
    private val _wireguardEndpointUrl = MutableStateFlow<String?>(null)
    val wireguardEndpointUrl: StateFlow<String?> = _wireguardEndpointUrl.asStateFlow()

    // The WireGuard-shadowed-by-system-VPN warning is published to
    // [mcpStatusHolder] (core/data) so feature modules (Settings) can observe it
    // without depending on :app. See [detectVpnAddressCollision].

    /** The address the WG MCP listener is currently bound to, for collision re-checks. */
    @Volatile private var wireguardBoundAddress: String? = null

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    /** Watches VPN networks so the collision warning updates live as VPNs come/go. */
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** Synthetic profile id under which the MCP listener holds a WG tunnel. */
    private val wireguardProfileId = "mcp-wg-listener"
    /**
     * Synthetic profile id under which the MCP server actively keeps the
     * configured carrier WG tunnel UP (distinct from [wireguardProfileId],
     * which merely binds whatever WG tunnel is live). Holding this refcount
     * is what makes the WG-exposed endpoint survive app restart /
     * re-foreground instead of waiting for some other profile to dial it.
     */
    private val wireguardCarrierProfileId = "mcp-wg-carrier"
    private var wireguardJob: Job? = null
    @Volatile private var wireguardListener: sh.haven.core.tunnel.TunneledServerSocket? = null

    /**
     * URL the endpoint is reachable at on the device's Wi-Fi/LAN address
     * (`http://<lan-ip>:<port>/mcp`), or null when the LAN bind is off or no
     * suitable interface is up. A same-network client can point here
     * directly — no WireGuard, no reverse forward.
     */
    private val _lanEndpointUrl = MutableStateFlow<String?>(null)
    val lanEndpointUrl: StateFlow<String?> = _lanEndpointUrl.asStateFlow()

    private var lanServerSocket: ServerSocket? = null
    private var lanServerThread: Thread? = null

    /**
     * Standard MCP server registration JSON for the Haven endpoint.
     * Null when the server isn't running. Any MCP-aware client can
     * merge this into its own config; the shape is compatible with
     * the common `{ "mcpServers": { "name": { "type", "url" } } }`
     * format most clients accept.
     */
    val mcpServerConfigJson: String?
        get() {
            val url = _endpointUrl.value ?: return null
            return """
                {
                  "mcpServers": {
                    "haven": {
                      "type": "http",
                      "url": "$url"
                    }
                  }
                }
            """.trimIndent()
        }

    private val tools = McpTools(
        context = context,
        connectionRepository = connectionRepository,
        portForwardRepository = portForwardRepository,
        sshSessionManager = sshSessionManager,
        sessionManagerRegistry = sessionManagerRegistry,
        reticulumSessionManager = reticulumSessionManager,
        reticulumForwardServer = reticulumForwardServer,
        rcloneClient = rcloneClient,
        mailSessionManager = mailSessionManager,
        sftpStreamServer = sftpStreamServer,
        hlsStreamServer = hlsStreamServer,
        ffmpegExecutor = ffmpegExecutor,
        preferencesRepository = preferencesRepository,
        terminalFontInstaller = terminalFontInstaller,
        localSessionManager = localSessionManager,
        agentUiCommandBus = agentUiCommandBus,
        transportSelector = transportSelector,
        workspaceRepository = workspaceRepository,
        workspaceLauncher = workspaceLauncher,
        tunnelConfigRepository = tunnelConfigRepository,
        tunnelManager = tunnelManager,
        terminalSessionRegistry = terminalSessionRegistry,
        portKnocker = portKnocker,
        spaSender = spaSender,
        connectionLogRepository = connectionLogRepository,
        servedFileTracker = servedFileTracker,
        syncProfileRepository = syncProfileRepository,
        mailRuleRepository = mailRuleRepository,
        mailWatchManager = mailWatchManager,
        agentActivityHolder = agentActivityHolder,
        terminalInputQueue = terminalInputQueue,
        prootInstallLogRepository = prootInstallLogRepository,
        sshKeyRepository = sshKeyRepository,
        knownHostDao = knownHostDao,
        stepCaConfigRepository = stepCaConfigRepository,
        totpSecretRepository = totpSecretRepository,
        ageIdentityRepository = ageIdentityRepository,
        desktopSessionRegistry = desktopSessionRegistry,
        usbBroker = usbBroker,
        usbIpServer = usbIpServer,
        usbDriveVmManager = usbDriveVmManager,
        umlRecoveryManager = umlRecoveryManager,
        presentationManager = presentationManager,
        havenUiBridge = havenUiBridge,
        standingPolicyRepository = standingPolicyRepository,
        mcpTunnelManager = mcpTunnelManager,
        mcpStatusHolder = mcpStatusHolder,
        btSerialSessionManager = btSerialSessionManager,
        bleSerialSessionManager = bleSerialSessionManager,
        usbSerialSessionManager = usbSerialSessionManager,
        openAiSessionManager = openAiSessionManager,
        tunnelResolver = tunnelResolver,
        headlessSshExec = headlessSshExec,
        pendingAuthPromptHolder = pendingAuthPromptHolder,
        sessionSelectionHolder = sessionSelectionHolder,
        agentConsentManager = consentManager,
        mcpPortProvider = { port },
    )

    /**
     * Invoke an MCP tool by name, BYPASSING the interactive consent gate. For the
     * Mail-Rules engine only: an enabled rule is the user's standing pre-authorization,
     * and the engine fires while backgrounded (where [tools] consent would fail closed).
     * The tool's own audit still records the call. Throws [McpError] on bad input.
     */
    suspend fun callToolUnconsented(name: String, arguments: org.json.JSONObject): org.json.JSONObject =
        tools.call(name, arguments, clientHint = "mail-rule")

    /**
     * The [ConsentLevel] of tool [name] (null if unknown). The Mail-Rules executor derives
     * a rule action's background-safety posture from this: NEVER → runs in background;
     * non-NEVER → treated as destructive (notify-only / queued until foreground).
     */
    fun toolConsentLevel(name: String): ConsentLevel? = tools.consentFor(name)?.level

    /**
     * Start the server on the first free port in [8730..8739] (a small
     * deterministic range so a reconnecting MCP client can find us
     * again after an app restart). Falls back to an OS-assigned port
     * if all preferred ports are busy.
     *
     * Idempotent: if a healthy instance is already running this is a
     * no-op; if a stale instance is detected (flag set but listener
     * socket closed or accept thread dead) it's torn down and
     * re-created. This matters because the Android process can be
     * suspended for long periods and the server's threads may be
     * killed out from under it while [isRunning] still reads `true`.
     */
    fun start() = synchronized(lifecycleLock) {
        if (isHealthy()) return@synchronized
        if (isRunning) {
            Log.w(TAG, "start() found stale instance, tearing down before rebinding")
            stopLocked()
        }
        val ss = bindLoopback()
        serverSocket = ss
        port = ss.localPort
        isRunning = true
        mcpStatusHolder.setRunning(true)
        _endpointUrl.value = "http://127.0.0.1:$port/mcp"
        // Seed the in-memory allowlist mirror from DataStore. Subsequent
        // pairing approvals append to this on the dispatch thread.
        allowedClients = runBlocking { preferencesRepository.mcpAllowedClients.first() }
        clientTokenHashes = runBlocking { preferencesRepository.mcpClientTokenHashes.first() }
        trustLoopbackEnabled = runBlocking { preferencesRepository.trustLoopbackMcpClients.first() }
        Log.i(TAG, "MCP server listening on ${_endpointUrl.value} (paired clients: ${allowedClients.size}, trustLoopback=$trustLoopbackEnabled)")

        // Track DataStore so an un-pair (revoking allowlist + token) takes
        // effect immediately, not just after a restart (#mcp-backbone Stage 3).
        trustSyncJob?.cancel()
        trustSyncJob = scope.launch {
            launch { preferencesRepository.mcpAllowedClients.collect { allowedClients = it } }
            launch { preferencesRepository.mcpClientTokenHashes.collect { clientTokenHashes = it } }
        }

        serverThread = acceptThread(ss, "mcp-http", McpOrigin.DEVICE)

        // Dedicated tunneled-origin listener the `-R` carriers target
        // (#mcp-backbone Stage 2). Bound whenever the endpoint runs so the
        // near carrier always has a target.
        val tss = bindTunneled()
        tunneledServerSocket = tss
        tunneledPort = tss.localPort
        tunneledServerThread = acceptThread(tss, "mcp-http-tunneled", McpOrigin.TUNNELED)
        Log.i(TAG, "MCP tunneled-origin listener on 127.0.0.1:$tunneledPort")

        // Optionally also expose the endpoint on the active WireGuard tunnel.
        if (runBlocking { preferencesRepository.mcpWireguardEnabled.first() }) {
            startWireguardBinderLocked()
        }
        // Optionally also bind the device's Wi-Fi/LAN address for direct
        // same-network reach.
        if (runBlocking { preferencesRepository.mcpLanBindEnabled.first() }) {
            startLanBinderLocked()
        }
    }

    /**
     * Actively restart the accept loop when the endpoint is enabled but not
     * [isHealthy] — a process-suspend zombie (`isRunning` still true but the
     * socket/thread died, see [start]) or a loop killed when the OS trimmed the
     * process. Contributed to [sh.haven.core.ssh.ForegroundReviveHook] so the
     * FGS kicks it on return-to-foreground and network-available — the same
     * instant recovery the interactive SSH sessions already get — instead of the
     * endpoint staying dead behind a still-up carrier until the user toggles the
     * pref. (#mcp-backbone Stage 1)
     *
     * Non-blocking per the hook contract: the enabled check and any restart run
     * on [scope]. [start] is a no-op when already healthy, so a redundant kick on
     * a live server costs a single `isHealthy()` check. A deliberately-disabled
     * endpoint (`mcpAgentEndpointEnabled == false`) is never resurrected — stop()
     * clears the flag and this respects it.
     *
     * Scope note: this heals the loopback accept loop (the near-carrier path that
     * dies on process trim). The WG/LAN binders are re-established by [start] on
     * the same restart, but a WG binder that dies while the loopback loop stays
     * healthy is out of scope here — WG has its own accept-loop retry.
     */
    fun reviveNow() {
        scope.launch {
            val enabled = runCatching {
                preferencesRepository.mcpAgentEndpointEnabled.first()
            }.getOrDefault(false)
            if (!enabled) return@launch
            val healthyBefore = isHealthy()
            start()
            if (!healthyBefore) {
                Log.i(TAG, "reviveNow: endpoint enabled but unhealthy — accept loop restarted")
            }
        }
    }

    override fun close() = stop()

    /**
     * Enable/disable the LAN-exposed listener at runtime (driven by the
     * `mcpLanBindEnabled` preference). No-op when the server isn't running —
     * [start] picks up the current pref value itself.
     */
    fun setLanBindEnabled(enabled: Boolean) = synchronized(lifecycleLock) {
        if (!isRunning) return@synchronized
        if (enabled) startLanBinderLocked() else stopLanBinderLocked()
    }

    /**
     * Toggle loopback auto-trust at runtime (driven by the
     * `trustLoopbackMcpClients` preference). No lock needed — it's a
     * single volatile flag read on the dispatch path. (#214)
     */
    fun setTrustLoopbackEnabled(enabled: Boolean) {
        trustLoopbackEnabled = enabled
        Log.i(TAG, "MCP loopback auto-trust ${if (enabled) "enabled" else "disabled"}")
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun startLanBinderLocked() {
        if (lanServerThread?.isAlive == true) return
        val boundPort = port
        val lanSs = bindLan(boundPort)
        if (lanSs == null) {
            // No suitable interface up right now (e.g. mobile-only). The
            // HavenApp connectivity observer re-invokes this on the next
            // network change, so this is a soft skip, not an error.
            Log.i(TAG, "MCP LAN bind requested but no Wi-Fi/LAN address is up; will retry on network change")
            return
        }
        lanServerSocket = lanSs
        _lanEndpointUrl.value = "http://${lanSs.inetAddress.hostAddress}:$boundPort/mcp"
        Log.i(TAG, "MCP also listening on LAN ${_lanEndpointUrl.value}")
        lanServerThread = acceptThread(lanSs, "mcp-http-lan", McpOrigin.LAN)
    }

    /**
     * Spawn the accept loop for [ss], tagging every accepted connection with
     * [origin] — the bind-time trust tag (#mcp-backbone Stage 2). One shape
     * shared by the DEVICE, TUNNELED, and LAN binders so their handling can't
     * drift; the WireGuard netstack path has its own coroutine loop.
     *
     * Connections are keep-alive (Stage 4), so each holds its worker thread
     * while open; [activeConnections] caps the total and overflow gets an
     * immediate 503 rather than an unbounded thread pile.
     */
    private fun acceptThread(ss: ServerSocket, name: String, origin: McpOrigin): Thread =
        thread(name = name, isDaemon = true) {
            while (isRunning && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    thread(name = "$name-client", isDaemon = true) {
                        val overloaded = activeConnections.incrementAndGet() > MAX_CONCURRENT_CONNECTIONS
                        try {
                            if (overloaded) {
                                Log.w(TAG, "$name: connection cap ($MAX_CONCURRENT_CONNECTIONS) hit — 503")
                                writeHttpResponse(
                                    client.getOutputStream(),
                                    textResponse(503, "Service Unavailable"),
                                    keepAlive = false,
                                )
                            } else {
                                handleClient(client, origin)
                            }
                        } catch (e: Throwable) {
                            // Last-resort guard so one bad request can
                            // never kill a worker thread silently and
                            // leave the peer socket in CLOSE_WAIT.
                            Log.w(TAG, "$name worker crashed: ${e.message}")
                        } finally {
                            activeConnections.decrementAndGet()
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: IOException) {
                    // Socket closed by stop() — expected on shutdown
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "$name accept failed: ${e.message}")
                }
            }
            Log.i(TAG, "$name accept loop exited")
        }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun stopLanBinderLocked() {
        try { lanServerSocket?.close() } catch (_: Exception) {}
        lanServerSocket = null
        lanServerThread = null
        _lanEndpointUrl.value = null
    }

    /**
     * Enable/disable the WireGuard-exposed listener at runtime (driven by
     * the `mcpWireguardEnabled` preference). No-op when the server isn't
     * running — [start] picks up the current pref value itself.
     */
    fun setWireguardEnabled(enabled: Boolean) = synchronized(lifecycleLock) {
        if (!isRunning) return@synchronized
        if (enabled) startWireguardBinderLocked() else stopWireguardBinderLocked()
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun startWireguardBinderLocked() {
        if (wireguardJob?.isActive == true) return
        registerVpnWatcherLocked()
        val boundPort = port
        wireguardJob = scope.launch {
            while (isActive) {
                // If a carrier tunnel is configured, actively bring it UP
                // (acquire is get-or-create) so the WG-exposed endpoint comes
                // back on its own after an app restart / re-foreground rather
                // than waiting for some other profile to dial it. Idempotent;
                // re-run each iteration so a dropped carrier is re-established.
                // Zero-config fallback: when no carrier is explicitly set but
                // the user has exactly one WireGuard tunnel config, treat that
                // as the carrier. With several WG configs we stay attach-only
                // (no guessing) until the pref names one.
                val carrierId = preferencesRepository.mcpWireguardTunnelConfigId.first()
                    ?: runCatching {
                        tunnelConfigRepository.getAll()
                            .filter { it.type == "WIREGUARD" }
                            .singleOrNull()?.id
                    }.getOrNull()
                if (!carrierId.isNullOrBlank()) {
                    try {
                        tunnelManager.acquire(carrierId, wireguardCarrierProfileId)
                    } catch (e: Exception) {
                        Log.w(TAG, "MCP WG carrier acquire ($carrierId) failed: ${e.message}")
                    }
                }
                // Bind on whichever WireGuard tunnel is currently up; holding
                // it as a dependent keeps it alive while MCP is bound.
                val tunnel = tunnelManager.acquireFirstWireguard(wireguardProfileId)
                if (tunnel == null) {
                    delay(WG_RETRY_MS)
                    continue
                }
                val ln = try {
                    tunnel.listenTcp(boundPort)
                } catch (e: Exception) {
                    Log.w(TAG, "WG listen on $boundPort failed: ${e.message}")
                    tunnelManager.release(wireguardProfileId)
                    delay(WG_RETRY_MS)
                    continue
                }
                if (ln == null) {
                    tunnelManager.release(wireguardProfileId)
                    delay(WG_RETRY_MS)
                    continue
                }
                wireguardListener = ln
                val wgAddr = tunnel.localAddress()
                _wireguardEndpointUrl.value = wgAddr?.let { "http://$it:$boundPort/mcp" }
                wireguardBoundAddress = wgAddr
                Log.i(TAG, "MCP also listening on WireGuard ${_wireguardEndpointUrl.value ?: ":$boundPort"}")
                refreshWireguardCollision()
                try {
                    while (isActive) {
                        val conn = ln.accept()
                        scope.launch {
                            try {
                                // WireGuard peers are always remote (a WG
                                // tunnel IP) — never loopback-trusted (#214).
                                // One-shot (maxRequests=1), NOT keep-alive: the
                                // netstack connection has no read timeout, so
                                // an idle keep-alive peer would pin an IO
                                // thread indefinitely.
                                serveHttpConnection(conn.inputStream, conn.outputStream, maxRequests = 1) { req ->
                                    dispatchHttpRequest(req, McpOrigin.WIREGUARD)
                                }
                            } catch (e: Throwable) {
                                Log.w(TAG, "WG worker crashed: ${e.message}")
                            } finally {
                                try { conn.close() } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    // accept() throws when the listener or tunnel closes
                    // (roam re-handshake, profile released the tunnel, or our
                    // own stop closed it) — fall through to re-acquire.
                    Log.i(TAG, "WG accept loop ended: ${e.message}")
                } finally {
                    wireguardListener = null
                    _wireguardEndpointUrl.value = null
                    wireguardBoundAddress = null
                    mcpStatusHolder.setWireguardCollision(null)
                    tunnelManager.release(wireguardProfileId)
                }
                if (isActive) delay(WG_RETRY_MS)
            }
        }
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun stopWireguardBinderLocked() {
        wireguardJob?.cancel()
        wireguardJob = null
        unregisterVpnWatcherLocked()
        wireguardBoundAddress = null
        mcpStatusHolder.setWireguardCollision(null)
        // Close the listener to unblock a pending accept(), then drop our
        // hold on the tunnel.
        try { wireguardListener?.close() } catch (_: Exception) {}
        wireguardListener = null
        _wireguardEndpointUrl.value = null
        scope.launch {
            tunnelManager.release(wireguardProfileId)
            // Drop our active hold on the carrier tunnel too. If another
            // profile still depends on it, it stays up; otherwise it's torn
            // down (we were the only thing keeping it alive).
            tunnelManager.release(wireguardCarrierProfileId)
        }
    }

    /**
     * Best-effort: a warning when an active system VPN (another app's
     * [NetworkCapabilities.TRANSPORT_VPN] network) holds [address] — the same
     * address our userspace WireGuard netstack serves MCP on. The kernel VPN
     * then shadows our listener (peers reach the kernel tun, which RSTs our
     * port), so the WG endpoint is silently unreachable. Needs
     * `ACCESS_NETWORK_STATE` (held); returns null on any lookup failure.
     */
    private fun detectVpnAddressCollision(address: String?): sh.haven.core.data.agent.WgCollisionInfo? {
        if (address.isNullOrBlank()) return null
        val cm = connectivityManager ?: return null
        val target = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return null
        return try {
            val iface = cm.allNetworks.firstNotNullOfOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return@firstNotNullOfOrNull null
                val lp = cm.getLinkProperties(net) ?: return@firstNotNullOfOrNull null
                if (lp.linkAddresses.any { it.address == target }) lp.interfaceName ?: "vpn" else null
            } ?: return null
            sh.haven.core.data.agent.WgCollisionInfo(vpnInterface = iface, address = address)
        } catch (e: Exception) {
            Log.w(TAG, "VPN collision check failed: ${e.message}")
            null
        }
    }

    /** Re-evaluate the WG endpoint collision against the currently-bound address. */
    private fun refreshWireguardCollision() {
        val info = detectVpnAddressCollision(wireguardBoundAddress)
        if (info != mcpStatusHolder.wireguardCollision.value) {
            mcpStatusHolder.setWireguardCollision(info)
            if (info != null) {
                Log.w(TAG, "WG MCP carrier shadowed: system VPN (${info.vpnInterface}) holds ${info.address} — endpoint unreachable from peers")
            }
        }
    }

    /** Must hold [lifecycleLock]. Idempotent. Watches VPN networks for live collision updates. */
    private fun registerVpnWatcherLocked() {
        if (vpnNetworkCallback != null) return
        val cm = connectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshWireguardCollision()
            override fun onLost(network: Network) = refreshWireguardCollision()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                refreshWireguardCollision()
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(req, cb)
            vpnNetworkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "VPN watcher register failed: ${e.message}")
        }
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun unregisterVpnWatcherLocked() {
        val cb = vpnNetworkCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        vpnNetworkCallback = null
    }

    fun stop() = synchronized(lifecycleLock) {
        stopLocked()
    }

    /** Must be called while holding [lifecycleLock]. */
    private fun stopLocked() {
        isRunning = false
        mcpStatusHolder.setRunning(false)
        trustSyncJob?.cancel()
        trustSyncJob = null
        stopWireguardBinderLocked()
        stopLanBinderLocked()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        try { tunneledServerSocket?.close() } catch (_: Exception) {}
        tunneledServerSocket = null
        tunneledServerThread = null
        tunneledPort = 0
        port = 0
        _endpointUrl.value = null
    }

    /**
     * True iff the server is running AND both loopback listeners (device +
     * tunneled origins) are still bound with live accept threads. Used by
     * [start] to distinguish a healthy instance from a process-suspend
     * zombie; a dead tunneled listener alone counts as unhealthy so the
     * Stage-1 revive hook heals the near-carrier path too.
     */
    private fun isHealthy(): Boolean {
        if (!isRunning) return false
        if (serverSocket?.isClosed != false) return false
        if (serverThread?.isAlive != true) return false
        if (tunneledServerSocket?.isClosed != false) return false
        if (tunneledServerThread?.isAlive != true) return false
        return true
    }

    // --- Socket binding ---

    // Off-device reachability has three layers: McpTunnelManager's SSH
    // reverse tunnel (roaming fallback), the WireGuard-tunnel netstack bind
    // ([startWireguardBinderLocked], #176 — direct reach for a WG peer), and
    // the LAN bind below ([bindLan] — direct reach for a same-network
    // client). All share [dispatchHttpRequest]'s pairing/consent gate.
    private fun bindLoopback(): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        // Try preferred ports first so a client that cached an endpoint
        // across app restarts has a decent chance of finding us again.
        for (p in 8730..8739) {
            try {
                return ServerSocket(p, 10, loopback).apply { reuseAddress = true }
            } catch (_: IOException) {
                // busy, try next
            }
        }
        // All preferred ports busy — let the OS pick one
        return ServerSocket(0, 10, loopback).apply { reuseAddress = true }
    }

    /**
     * Bind the tunneled-origin loopback listener on the first free port in
     * [8740..8749] — deterministic, like [bindLoopback]'s 8730–8739, so the
     * near carrier's stored `-R` forward usually still points at the right
     * target after a server restart. OS-assigned fallback if all are busy.
     */
    private fun bindTunneled(): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (p in 8740..8749) {
            try {
                return ServerSocket(p, 10, loopback).apply { reuseAddress = true }
            } catch (_: IOException) {
                // busy, try next
            }
        }
        return ServerSocket(0, 10, loopback).apply { reuseAddress = true }
    }

    /**
     * Bind a real (kernel) [ServerSocket] on the device's Wi-Fi/LAN IPv4
     * address at [boundPort], so a same-network client reaches the endpoint
     * directly. Returns null when no suitable interface is up (e.g. mobile
     * data only) — the caller treats that as a soft skip and retries on the
     * next connectivity change.
     *
     * We pick a specific interface address rather than binding 0.0.0.0 so we
     * never expose the endpoint on the mobile (rmnet) interface — only on
     * Wi-Fi / Ethernet-style site-local networks the user is deliberately on.
     */
    private fun bindLan(boundPort: Int): ServerSocket? {
        val addr = pickLanAddress() ?: return null
        return try {
            ServerSocket(boundPort, 10, addr).apply { reuseAddress = true }
        } catch (e: IOException) {
            Log.w(TAG, "LAN bind on ${addr.hostAddress}:$boundPort failed: ${e.message}")
            null
        }
    }

    /**
     * The device's preferred Wi-Fi/LAN IPv4 address: an up, non-loopback,
     * site-local IPv4 on a wlan/eth-style interface. Excludes mobile (rmnet)
     * and tunnel (tun/wg/rmnet) interfaces so the LAN bind never lands on a
     * carrier-facing address. Null when none is available.
     */
    private fun pickLanAddress(): InetAddress? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .filterNot { ni ->
                    val n = ni.name.lowercase()
                    n.startsWith("rmnet") || n.startsWith("tun") || n.startsWith("wg") ||
                        n.startsWith("dummy") || n.startsWith("p2p")
                }
                .sortedBy { ni -> if (ni.name.lowercase().startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { a -> a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress }
        } catch (e: Exception) {
            Log.w(TAG, "pickLanAddress failed: ${e.message}")
            null
        }
    }

    // --- HTTP + JSON-RPC handling ---

    private fun handleClient(socket: Socket, origin: McpOrigin) {
        try {
            // Read timeout. Must exceed CONSENT_WAIT_MS so a request blocked on
            // an interactive consent prompt isn't dropped mid-wait; it also
            // bounds how long an idle keep-alive connection holds its worker.
            socket.soTimeout = 70_000
            // Keep-alive (#mcp-backbone Stage 4): serve requests off this
            // connection until the peer closes / asks to close / times out
            // idle. This is what lets an MCP client hold one warm connection
            // instead of dialling per request — the failover proxy's reason.
            socket.use { s ->
                serveHttpConnection(s.getInputStream(), s.getOutputStream()) { req ->
                    dispatchHttpRequest(req, origin)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "client handler error: ${e.message}")
        }
    }

    /**
     * Route one parsed request to the JSON-RPC layer / CORS / errors. Shared
     * by the kernel-[ServerSocket] paths ([handleClient]) and the WireGuard
     * netstack accept loop so all transports run identical request handling +
     * pairing checks (#176). [origin] is the accepting listener's bind-time
     * trust tag (#mcp-backbone Stage 2).
     */
    private fun dispatchHttpRequest(req: ParsedHttpRequest, origin: McpOrigin): HttpResponse {
        val method = req.method
        val path = req.path
        return when {
            method == "POST" && (path == "/mcp" || path == "/") -> {
                // DNS-rebinding / CSRF guard (#mcp-backbone Stage 0): a browser
                // page on the device can reach the loopback endpoint. Reject a
                // POST that carries a cross-origin `Origin`; non-browser MCP
                // clients send no Origin and pass through.
                val httpOrigin = req.headers["origin"]
                if (httpOrigin != null && !isLoopbackOrigin(httpOrigin)) {
                    return textResponse(403, "Forbidden")
                }
                val sessionId = req.headers["mcp-session-id"]?.takeIf { it.isNotEmpty() }
                // Pairing-token credential (#mcp-backbone Stage 3).
                val bearer = req.headers["authorization"]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring("Bearer ".length)?.trim()?.takeIf { it.isNotEmpty() }
                val outcome = handleJsonRpc(req.body, sessionId, origin, bearer)
                if (outcome.httpStatus == 404) {
                    // Streamable-HTTP signal: presented session id is unknown.
                    // Empty body — the client re-initialises.
                    jsonResponse(404, "", null)
                } else {
                    jsonResponse(outcome.httpStatus, outcome.body, outcome.responseSessionId)
                }
            }
            method == "GET" && (path == "/mcp" || path == "/") -> {
                // SSE channel for server-initiated messages — deferred until
                // something consumes it (listChanged, Stage 5). Spec allows 405.
                textResponse(405, "Method Not Allowed")
            }
            method == "OPTIONS" -> optionsResponse(req.headers["origin"])
            else -> textResponse(404, "Not Found")
        }
    }

    /**
     * CORS preflight. Reflects the allow-origin ONLY for a loopback [origin]
     * (never the old wildcard) so a cross-origin browser preflight fails and the
     * real request is never sent — the belt to the Origin-reject suspenders on
     * POST. A non-browser client never sends OPTIONS.
     */
    private fun optionsResponse(origin: String?): HttpResponse {
        val cors = if (origin != null && isLoopbackOrigin(origin)) listOf(
            "Access-Control-Allow-Origin" to origin,
            "Access-Control-Allow-Methods" to "POST, GET, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, Mcp-Session-Id, Mcp-Protocol-Version, Authorization",
        ) else emptyList()
        return HttpResponse(204, "No Content", ByteArray(0), headers = cors)
    }

    /** A JSON body response. No `Access-Control-Allow-Origin: *` (#mcp-backbone
     *  Stage 0): a wildcard let any web page read MCP responses off the
     *  loopback endpoint. */
    private fun jsonResponse(status: Int, body: String, sessionId: String?): HttpResponse {
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            404 -> "Not Found"
            else -> "OK"
        }
        val headers = buildList {
            add("Cache-Control" to "no-store")
            if (sessionId != null) add("Mcp-Session-Id" to sessionId)
        }
        return HttpResponse(
            status, statusText, body.toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8", headers,
        )
    }

    /**
     * Test-friendly overload — drives the JSON-RPC layer with no session
     * id and no bearer token, i.e. the fully unauthenticated path.
     * Production traffic goes through the full overload via [handleClient].
     */
    internal fun handleJsonRpc(body: String): String =
        handleJsonRpc(body, requestSessionId = null).body

    /**
     * Outcome of a JSON-RPC request as seen by the HTTP layer.
     *
     * - [httpStatus] is normally 200; becomes 404 when an unknown
     *   `Mcp-Session-Id` is presented, so the client per spec
     *   re-initialises automatically.
     * - [body] is the JSON-RPC reply body (may be empty for
     *   notifications or 404s).
     * - [responseSessionId] is set by a successful `initialize`; the
     *   HTTP layer echoes it back to the client in the
     *   `Mcp-Session-Id` response header.
     */
    internal data class JsonRpcOutcome(
        val httpStatus: Int,
        val body: String,
        val responseSessionId: String?,
    )

    /**
     * Parse a JSON-RPC 2.0 request and return the response. Visible
     * to tests in the same module so the consent gate, error mapping,
     * and audit-outcome plumbing can be exercised without sockets.
     *
     * [requestSessionId] is the value of the inbound `Mcp-Session-Id`
     * header, or null when the client doesn't send one (legacy path).
     */
    /** clientName → token-hash map, loading it from DataStore on first use. */
    private fun tokenHashes(): Map<String, String> =
        clientTokenHashes ?: runBlocking { preferencesRepository.mcpClientTokenHashes.first() }
            .also { clientTokenHashes = it }

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** A fresh 256-bit pairing token, minted on pairing approval. */
    private fun mintPairingToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * The client name [token] authenticates, or null. The credential — not
     * the self-asserted `clientInfo.name` — is the identity (#mcp-backbone
     * Stage 3). Hash-compare is constant-time so the lookup can't be used
     * as a timing oracle.
     */
    private fun resolveBearer(token: String): String? {
        val presented = sha256Hex(token).toByteArray(Charsets.US_ASCII)
        return tokenHashes().entries.firstOrNull { (_, hash) ->
            java.security.MessageDigest.isEqual(hash.toByteArray(Charsets.US_ASCII), presented)
        }?.key
    }

    internal fun handleJsonRpc(
        body: String,
        requestSessionId: String?,
        origin: McpOrigin = McpOrigin.LAN,
        bearerToken: String? = null,
    ): JsonRpcOutcome {
        if (body.isBlank()) {
            return JsonRpcOutcome(200, jsonRpcError(null, -32700, "Parse error: empty body"), null)
        }
        val req = try {
            JSONObject(body)
        } catch (e: Exception) {
            return JsonRpcOutcome(200, jsonRpcError(null, -32700, "Parse error: ${e.message}"), null)
        }
        val id = req.opt("id") // may be null for notifications
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()

        // Notifications (no id) get no response per JSON-RPC spec, but we still
        // need to return an empty 200 for the HTTP layer. Return "" for those.
        val isNotification = !req.has("id")

        // Resolve the effective clientName before dispatch, strongest
        // credential first: a bearer pairing token (survives app + phone
        // restarts), then the session id (minted by an authenticated
        // initialize in this process), then — for `initialize` only — the
        // self-asserted clientInfo.name, which is attribution for the
        // pairing prompt/audit, never authentication (#mcp-backbone Stage 3).
        val authClient: String? = bearerToken?.let { resolveBearer(it) }
        val sessionClient: String? = requestSessionId?.let { sessions[it]?.clientName }
        when {
            authClient != null -> lastClientHint = authClient
            sessionClient != null -> lastClientHint = sessionClient
            method == "initialize" ->
                params.optJSONObject("clientInfo")?.optString("name")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { lastClientHint = it }
        }

        val started = System.nanoTime()
        var outcome = AgentAuditEvent.Outcome.OK
        var errorMessage: String? = null
        var resultJson: JSONObject? = null
        var newSessionId: String? = null
        val response = try {
            val result = dispatch(method, params, requestSessionId, origin, authClient)
            if (result is JSONObject) resultJson = result
            // A successful `initialize` mints a new session id we'll
            // hand back to the client in the response header. The
            // client echoes it on subsequent requests and we resolve
            // it back to a clientName without re-prompting.
            if (method == "initialize" && result is JSONObject) {
                val clientName = lastClientHint
                if (!clientName.isNullOrBlank()) {
                    purgeExpiredSessions()
                    val sid = UUID.randomUUID().toString()
                    sessions[sid] = Session(clientName, System.currentTimeMillis())
                    newSessionId = sid
                }
            }
            if (isNotification) "" else jsonRpcResult(id, result)
        } catch (e: SessionExpiredError) {
            // Streamable-HTTP signal: the client presented a session
            // id we don't recognise (we restarted; map was cleared).
            // Return 404 with empty body — the spec-defined cue that
            // tells the client to re-`initialize` automatically
            // instead of propagating an opaque -32001 to the user.
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            return JsonRpcOutcome(404, "", null)
        } catch (e: ConsentDeniedError) {
            // User denied via the bottom-sheet prompt, or no foreground
            // activity was available to ask. Audit it as DENIED so the
            // user can tell intent-from-the-agent apart from runtime
            // failure later.
            outcome = AgentAuditEvent.Outcome.DENIED
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, e.code, e.message ?: "User denied")
        } catch (e: McpError) {
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, e.code, e.message ?: "Error")
        } catch (e: Throwable) {
            // Throwable, not Exception: a JNI-backed handler can throw an
            // Error (#469 — UnsatisfiedLinkError when a native lib failed
            // to load), and letting it unwind past here kills the client's
            // socket with no response instead of a JSON-RPC error.
            Log.e(TAG, "dispatch failed for method=$method", e)
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, -32603, "Internal error: ${e.message}")
        }

        // Record after responding so latency is unaffected. We skip
        // bookkeeping notifications (`notifications/initialized`) —
        // they carry no semantic action and would just clutter the
        // audit view.
        if (method != "notifications/initialized") {
            val toolName = if (method == "tools/call") {
                params.optString("name").takeIf { it.isNotEmpty() }
            } else null
            val rawArgs = if (method == "tools/call") {
                params.optJSONObject("arguments")
            } else if (params.length() > 0) params else null
            // For tools/call, the structured tool output lives under
            // `structuredContent`; the summariser wants that, not the
            // MCP wrapper that holds `content` + `structuredContent`.
            val resultForSummary = if (method == "tools/call") {
                resultJson?.optJSONObject("structuredContent")
            } else resultJson
            val durationMs = (System.nanoTime() - started) / 1_000_000
            auditRecorder.record(
                method = method,
                toolName = toolName,
                rawArgs = rawArgs,
                result = resultForSummary,
                durationMs = durationMs,
                outcome = outcome,
                errorMessage = errorMessage,
                clientHint = lastClientHint,
            )
        }

        return JsonRpcOutcome(200, response, newSessionId)
    }

    /**
     * Drop session entries older than [SESSION_TTL_MS]. Called from
     * the `initialize` success path so the work stays off the hot
     * dispatch path. The map is small in normal use; this is a
     * belt-and-braces guard against unbounded growth under churn.
     */
    private fun purgeExpiredSessions() {
        val cutoff = System.currentTimeMillis() - SESSION_TTL_MS
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.createdAt < cutoff) it.remove()
        }
    }

    /**
     * Dispatch an MCP method to its handler.
     *
     * [requestSessionId] is the inbound `Mcp-Session-Id` header. When
     * present but unrecognised we throw [SessionExpiredError] so the
     * HTTP layer returns 404 and the client per spec re-initialises.
     */
    private fun dispatch(
        method: String,
        params: JSONObject,
        requestSessionId: String?,
        origin: McpOrigin,
        authClient: String? = null,
    ): Any? {
        // Loopback auto-trust: only a DEVICE-origin client (the plain
        // loopback binder) can be treated as already-paired and
        // consent-bypassed, and only when the user opted in. TUNNELED
        // traffic also arrives on 127.0.0.1 but its far end is a remote
        // host — never trusted (#mcp-backbone Stage 2). LAN / WireGuard
        // always run the full gate. (#214)
        val trusted = origin == McpOrigin.DEVICE && trustLoopbackEnabled
        // Authentication gate (#mcp-backbone Stage 3): every method except
        // the pair-or-fail path itself requires a credential — the bearer
        // pairing token ([authClient], verified upstream) or a session id
        // minted by an authenticated `initialize` in this process. A bare
        // clientInfo.name is NOT a credential and never passes this gate:
        // that string is chosen by the client, so trusting it let any peer
        // inherit another client's pairing and grants by using its name.
        // `ping` is intentionally allowed unauthenticated so a client can
        // probe for the server's existence without identifying itself.
        // `notifications/initialized` is a spec-required ack sent right
        // after `initialize` and is exempt so a paired client that just
        // finished its handshake doesn't silently fail it.
        if (!trusted && method != "initialize" && method != "ping" && method != "notifications/initialized") {
            val sessionClient = requestSessionId?.let { sessions[it]?.clientName }
            if (authClient == null && requestSessionId != null && sessionClient == null) {
                // The client sent a session id we don't recognise.
                // Almost always means the server restarted while the
                // client cached its id. Returning -32001 here would
                // wedge the client; SessionExpiredError → HTTP 404
                // tells it to re-initialise.
                throw SessionExpiredError()
            }
            // A token-verified identity IS paired (the hash lookup is the
            // allowlist); a session-derived name additionally has to be in
            // the allowlist so a session minted for a loopback-trusted name
            // can't be replayed on a remote origin. (#214)
            val client = authClient ?: sessionClient?.takeIf { it in allowedClients }
            if (client.isNullOrBlank()) {
                throw McpError(
                    -32001,
                    "MCP client not paired with Haven. Send `initialize` first (with your " +
                        "`Authorization: Bearer` pairing token if you have one); " +
                        "if the prompt was denied, open Haven and approve a fresh attempt.",
                )
            }
        }
        return when (method) {
            "initialize" -> handleInitialize(params, trusted, authClient)
            "notifications/initialized" -> JSONObject() // ack
            "tools/list" -> handleToolsList()
            "tools/call" -> handleToolsCall(params, trusted, origin)
            "resources/list" -> handleResourcesList()
            "resources/read" -> handleResourcesRead(params, trusted)
            "ping" -> JSONObject()
            else -> throw McpError(-32601, "Method not found: $method")
        }
    }

    private fun handleInitialize(params: JSONObject, trusted: Boolean, authClient: String?): JSONObject {
        val clientProtoVersion = params.optString("protocolVersion", "2025-06-18")
        val clientInfo = params.optJSONObject("clientInfo")
        val clientName = clientInfo?.optString("name")?.takeIf { it.isNotBlank() }
        val clientVersion = clientInfo?.optString("version")?.takeIf { it.isNotBlank() }
        Log.i(TAG, "MCP initialize from name=${clientName ?: "<anonymous>"} v=${clientVersion ?: "?"} protocolVersion=$clientProtoVersion trusted=$trusted tokenAuth=${authClient != null}")

        // Pairing gate. An empty / unknown name has no way to pair (the
        // user would tap Allow on "MCP client '' wants to connect" with
        // no idea who's behind it), so refuse outright. We keep this even
        // for loopback-trusted clients: a name is cheap to require and
        // keeps the audit trail meaningful.
        if (clientName.isNullOrBlank()) {
            throw McpError(
                -32002,
                "MCP initialize must include clientInfo.name. Anonymous clients " +
                    "cannot be paired and are rejected.",
            )
        }
        // Loopback auto-trust: skip the pairing prompt entirely. We do NOT
        // persist the name to the allowlist — trust here is origin-scoped
        // (loopback binder), not name-scoped, so it must never leak to a
        // later LAN/WireGuard connection that happens to use the same
        // clientInfo.name. The dispatch gate is already skipped for these
        // requests (see [dispatch]). (#214)
        if (trusted) {
            Log.i(TAG, "MCP initialize: '$clientName' on loopback — auto-trusted, no pairing prompt")
            return initializeResult()
        }
        // Token-authenticated re-initialize (#mcp-backbone Stage 3): the
        // bearer credential IS the pairing — no prompt. The identity is the
        // token's owner, not the self-asserted name.
        if (authClient != null) {
            if (clientName != authClient) {
                Log.w(TAG, "MCP initialize: token for '$authClient' presented with clientInfo.name='$clientName' — proceeding as '$authClient'")
            }
            if (authClient !in allowedClients) allowedClients = allowedClients + authClient
            Log.i(TAG, "MCP initialize: '$authClient' authenticated by pairing token; no prompt")
            return initializeResult()
        }
        // No credential → the on-device pairing prompt, ALWAYS — including
        // for names in the legacy (pre-token) allowlist, which is the
        // one-time re-pair migration: name-only allowlist membership was
        // spoofable, so it no longer skips the prompt (#mcp-backbone
        // Stage 3). Approval mints the client's pairing token, returned
        // once in the initialize result.
        Log.i(TAG, "MCP initialize: '$clientName' has no pairing token — queueing pairing prompt")
        val decision = runBlocking {
            consentManager.requestClientPairing(clientName, clientVersion)
        }
        Log.i(TAG, "MCP initialize: pairing decision for '$clientName' = $decision")
        return when (decision) {
            ConsentDecision.ALLOW -> {
                val token = mintPairingToken()
                val hash = sha256Hex(token)
                runBlocking {
                    preferencesRepository.addMcpAllowedClient(clientName)
                    preferencesRepository.setMcpClientTokenHash(clientName, hash)
                }
                allowedClients = allowedClients + clientName
                clientTokenHashes = (clientTokenHashes ?: emptyMap()) + (clientName to hash)
                Log.i(TAG, "MCP client '$clientName' paired with Haven; pairing token minted (allowlist size=${allowedClients.size})")
                initializeResult(pairingToken = token)
            }
            ConsentDecision.DENY -> {
                throw McpError(
                    -32001,
                    "MCP client '$clientName' is not paired with Haven. " +
                        "The pairing prompt was denied or no Haven activity was visible. " +
                        "Open Haven and retry to surface a fresh pairing prompt.",
                )
            }
        }
    }

    /**
     * The `initialize` response body, shared by the token/prompt-paired
     * paths and the loopback auto-trust path. [pairingToken] is non-null
     * only on the response to an approved pairing: the token is shown ONCE
     * (only its hash is stored) in `_meta` and in the instructions text, so
     * both the client program and the agent reading the handshake can
     * capture it.
     */
    private fun initializeResult(pairingToken: String? = null): JSONObject = JSONObject().apply {
        put("protocolVersion", "2025-06-18")
        put("serverInfo", JSONObject().apply {
            put("name", "haven-agent")
            put("version", sh.haven.app.BuildConfig.VERSION_NAME)
        })
        put("capabilities", JSONObject().apply {
            put("tools", JSONObject().apply {
                put("listChanged", false)
            })
            // One resource: Haven's own rendered screen (ui://haven/screen).
            put("resources", JSONObject().apply {
                put("subscribe", false)
                put("listChanged", false)
            })
        })
        val instructions =
            "Haven is a mobile thin-client OS for distributed compute, storage, and presence. " +
                "Use these tools to inspect the user's saved connections, active sessions, and " +
                "cloud storage without disturbing the UI they're looking at."
        if (pairingToken != null) {
            put("_meta", JSONObject().put("sh.haven/pairingToken", pairingToken))
            put(
                "instructions",
                instructions + "\n\nPairing approved. Your pairing token (shown once, never " +
                    "again): $pairingToken\nSend it on every future request as an " +
                    "'Authorization: Bearer' header to stay paired across reconnects and Haven " +
                    "restarts; without it, reconnecting re-prompts the user on the device.",
            )
        } else {
            put("instructions", instructions)
        }
    }

    private fun handleToolsList(): JSONObject {
        return JSONObject().apply {
            put("tools", JSONArray().apply {
                tools.definitions().forEach { put(it) }
            })
        }
    }

    private fun handleResourcesList(): JSONObject = JSONObject().apply {
        put("resources", JSONArray().put(JSONObject().apply {
            put("uri", UI_SCREEN_RESOURCE_URI)
            put("name", "Haven UI screen")
            put(
                "description",
                "A live PNG screenshot of Haven's OWN rendered screen — the app UI the user is " +
                    "looking at right now. Read it to see the current Haven UI without a tool call; " +
                    "the file-shaped sibling of the capture_haven_ui tool. FLAG_SECURE (screen " +
                    "security on) and Haven-not-foreground are returned as read errors.",
            )
            put("mimeType", "image/png")
        }))
    }

    private fun handleResourcesRead(params: JSONObject, trusted: Boolean): JSONObject {
        val uri = params.optString("uri")
        if (uri != UI_SCREEN_RESOURCE_URI) {
            throw McpError(-32602, "Unknown resource uri: '$uri'. Call resources/list for what's available.")
        }
        // A screen read can expose credentials, so it carries the SAME gate as
        // the capture_haven_ui tool (ONCE_PER_SESSION) — and shares its memo key
        // (toolName) so a prior allow covers both and the resource can't be used
        // to bypass the tool's consent. Loopback stays auto-trusted.
        // A standing policy covering capture_haven_ui covers the resource
        // read too (same memo key, same gate, same audit).
        val policyAllowed = !trusted && runBlocking {
            standingPolicyEnforcer.permits(lastClientHint, "capture_haven_ui", JSONObject())
        }
        if (!trusted && !policyAllowed) {
            val decision = runBlocking {
                withTimeoutOrNull(consentWaitMs) {
                    consentManager.requestConsent(
                        toolName = "capture_haven_ui",
                        clientHint = lastClientHint,
                        summary = "Let the agent see Haven's own screen",
                        level = ConsentLevel.ONCE_PER_SESSION,
                        timeoutMs = consentWaitMs * 9 / 10,
                    )
                }
            }
            when (decision) {
                ConsentDecision.DENY -> throw ConsentDeniedError("capture_haven_ui")
                null -> throw McpError(
                    -32012,
                    "Consent prompt for the Haven UI screen wasn't answered in time — approve it " +
                        "on the device and retry.",
                )
                else -> { /* ALLOW — proceed */ }
            }
        }
        val (b64, mime) = runBlocking { tools.readUiScreenResource() }
        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("uri", uri)
                put("mimeType", mime)
                put("blob", b64)
            }))
        }
    }

    private fun handleToolsCall(
        params: JSONObject,
        trusted: Boolean,
        origin: McpOrigin = McpOrigin.LAN,
    ): JSONObject {
        val name = params.optString("name", "")
            .ifEmpty { throw McpError(-32602, "Missing tool name") }
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        // Pre-consent capability gate (#mcp-backbone Stage 5). Some tools
        // (serve_file, queue_terminal_input) live behind an extra Settings
        // toggle on top of the global agent endpoint. Failing here means the
        // user doesn't see a consent prompt for a tool that would then error
        // anyway, and the audit log records the denial cleanly. The gate is
        // now declared on the tool (ToolHandler.capability) — the transport
        // gates by that property, not by matching tool names, so a new gated
        // tool is a field, not another branch here. Capability gates are
        // feature on/off toggles, not consent, so they hold regardless of
        // loopback auto-trust (#214).
        runBlocking { tools.capabilityDenial(name) }?.let { throw McpError(-32011, it) }

        // Look up consent metadata before invoking the handler. Unknown
        // tools fall through to tools.call() which will throw the right
        // error; treating them as NEVER avoids prompting on a typo.
        //
        // Loopback auto-trust (#214) bypasses the per-action consent
        // prompt — but NOT the capability gate above, which is an explicit
        // feature on/off toggle, not consent, and stays regardless of origin.
        val consent = tools.consentFor(name)
        // Tier-3 standing policy: a user-installed, scoped, rate-capped,
        // expiring grant covers this exact (client, tool, args) — skip the
        // prompt below. Still audited like every call; any non-match (incl.
        // a rate ceiling hit) falls through to the normal prompt. Only
        // evaluated where a prompt would otherwise be needed.
        val policyAllowed = !trusted && consent != null && consent.level != ConsentLevel.NEVER &&
            runBlocking { standingPolicyEnforcer.permits(lastClientHint, name, arguments) }
        if (policyAllowed) {
            Log.i(TAG, "tools/call '$name' allowed by standing policy for '$lastClientHint'")
        }
        if (!trusted && !policyAllowed && consent != null && consent.level != ConsentLevel.NEVER) {
            val summary = try {
                consent.summary(arguments)
            } catch (e: Exception) {
                // A summary builder shouldn't crash the dispatcher; fall
                // back to the tool name so the prompt still renders.
                Log.w(TAG, "summary builder for '$name' threw: ${e.message}")
                name
            }
            // Bound the wait so a prompt the user hasn't answered doesn't block
            // the handler past the socket / client timeout (which surfaced as
            // "operation timed out" / "socket closed"). On timeout, return a
            // retryable error rather than hanging — and never auto-allow.
            // Stable key for this exact operation (sorted args) so an EVERY_CALL
            // approval that the client gave up on can be honoured on the retry of
            // the SAME call without re-prompting (#mcp-timeout).
            val operationKey = runCatching {
                arguments.keys().asSequence().sorted()
                    .joinToString("") { k -> "$k=${arguments.opt(k)}" }
            }.getOrDefault(arguments.toString())
            val decision = runBlocking {
                withTimeoutOrNull(consentWaitMs) {
                    consentManager.requestConsent(
                        toolName = name,
                        clientHint = lastClientHint,
                        summary = summary,
                        level = consent.level,
                        operationKey = operationKey,
                        timeoutMs = consentWaitMs * 9 / 10,
                    )
                }
            }
            when (decision) {
                ConsentDecision.DENY -> throw ConsentDeniedError(name)
                null -> throw McpError(
                    -32012,
                    "Consent prompt for '$name' wasn't answered in time — approve it on the device " +
                        "and retry. (Once approved for this session, further calls won't prompt.)",
                )
                else -> { /* ALLOW — proceed */ }
            }
        }
        mcpStatusHolder.callStarted(name)
        val result = try {
            runBlocking { tools.callTyped(name, arguments, lastClientHint, origin.name) }
        } catch (e: McpError) {
            mcpStatusHolder.callFinished(name, e.message)
            throw e
        } catch (e: Exception) {
            mcpStatusHolder.callFinished(name, e.message)
            Log.e(TAG, "tool '$name' threw", e)
            throw McpError(-32603, "Tool failed: ${e.message}")
        }
        mcpStatusHolder.callFinished(name)
        return renderToolResult(result)
    }

    /**
     * Render a typed [ToolResult] to the MCP `tools/call` response
     * (#mcp-backbone Stage 5). The transport now maps a type to content
     * blocks instead of sniffing reserved `__`-prefixed keys out of a
     * JSONObject:
     * - [ToolResult.Passthrough]: the guest server's own content array,
     *   forwarded verbatim so its text/image blocks reach the agent
     *   unchanged; structuredContent only when there's a structured echo.
     * - [ToolResult.Image]: an `image` content block (so the agent sees the
     *   picture directly — no second port, no download) plus the structured
     *   text echo, with the base64 kept out of that echo so it isn't
     *   duplicated.
     * - [ToolResult.Structured]: the ordinary structured-text case.
     */
    private fun renderToolResult(result: ToolResult): JSONObject = when (result) {
        is ToolResult.Passthrough -> JSONObject().apply {
            put("content", result.content)
            if (result.structured.length() > 0) put("structuredContent", result.structured)
        }
        is ToolResult.Image -> JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image")
                    put("data", result.base64)
                    put("mimeType", result.mimeType)
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", result.structured.toString(2))
                })
            })
            put("structuredContent", result.structured)
        }
        is ToolResult.Structured -> JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", result.structured.toString(2))
            }))
            put("structuredContent", result.structured)
        }
    }

}

/** Session entries older than this are evicted on the next initialize. */
private const val SESSION_TTL_MS: Long = 24L * 60L * 60L * 1000L

/**
 * Raised by the dispatcher when the user denies a consent prompt (or no
 * foreground activity is available to render one). Audit is recorded
 * with [AgentAuditEvent.Outcome.DENIED] so the dashboard can distinguish
 * "the agent tried and we said no" from "the agent tried and it broke."
 */
class ConsentDeniedError(toolName: String) :
    McpError(-32000, "User denied: $toolName")
