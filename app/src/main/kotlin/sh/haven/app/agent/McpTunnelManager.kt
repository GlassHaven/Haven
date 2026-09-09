package sh.haven.app.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.local.GuestServiceManager
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact

private const val TAG = "McpTunnelManager"

/**
 * Brings up a dedicated, headless SSH reverse tunnel to the designated
 * `mcpTunnelEndpointProfileId` endpoint host, carrying **adb-forwarding**
 * ([exposeAdbPort]) and **guest-MCP-service forwarding** ([refreshForwards])
 * — no longer Haven's own MCP endpoint itself, which now rides the user's
 * interactive session for the same profile instead (see [McpNearCarrier]).
 *
 * Why MCP moved off this class: a headless tunnel can only authenticate
 * non-interactively ([resolveHeadlessAuth] excludes FIDO2/encrypted keys,
 * since there's no UI to drive a touch prompt from a background service) and
 * otherwise falls back to trying every non-SK key in the whole keystore —
 * including keys never meant for that host. When none of those are actually
 * authorized, this class would retry a failing connection forever with no
 * way to recover short of a full app restart. [McpNearCarrier] sidesteps
 * this by reusing whatever auth the user's own interactive connection
 * already completed, instead of resolving its own.
 *
 * This class still owns its problem well: adb/guest-service forwarding
 * *does* want to work even when nothing is interactively connected, so it
 * keeps its own independent, always-reconnecting headless session — WG
 * remains MCP's own primary, always-on carrier regardless of any of this.
 * The tunnel:
 *  - rides its own [SshSessionManager] session marked `headless` (no
 *    shell channel ever opened — see [SshSessionManager.registerSession]),
 *  - uses an unlimited auto-reconnect policy that also fires on network
 *    change, so it self-heals after roams and restarts,
 *  - skips connecting entirely when there's nothing to carry (no exposed
 *    adb port, no running guest MCP service) — see the early-out in
 *    [establish] — so a not-yet-configured endpoint doesn't spin a doomed
 *    auth retry loop for nothing.
 *
 * Lifecycle is bound to the MCP endpoint toggle in `HavenApp`: [start]
 * on enable, [stop] on disable.
 */
@Singleton
class McpTunnelManager @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val sshIdentityRepository: sh.haven.core.data.repository.SshIdentityRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val sshSessionManager: SshSessionManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
    private val guestServiceManager: GuestServiceManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var job: Job? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var currentPort: Int = 0

    /**
     * Establish (or re-establish) the reverse tunnel forwarding
     * `127.0.0.1:[mcpPort]` on the laptop back to the device. Idempotent:
     * a no-op if already running for the same port; restarts if the port
     * changed (e.g. the MCP server fell back from 8730 to 8731).
     */
    fun start(mcpPort: Int) = synchronized(lock) {
        if (job?.isActive == true && currentPort == mcpPort) return
        stopLocked()
        currentPort = mcpPort
        launchTunnel(mcpPort)
    }

    private fun launchTunnel(mcpPort: Int) {
        job = scope.launch {
            // Supervisory loop. The SSH `-R` is the unconditional managed
            // transport for a configured endpoint — it runs independent of
            // WireGuard (WG serves the device's own WG address; it gives a
            // remote SSH endpoint host nothing). Rebind churn is owned by the
            // watchdog below, not by deferring to WG.
            while (isActive) {
                // Connect phase: retry with backoff until the tunnel + critical
                // forward are up, or the config is unusable (FATAL).
                var delayMs = INITIAL_RETRY_MS
                var established = false
                while (isActive && !established) {
                    when (establish(mcpPort)) {
                        Outcome.ESTABLISHED -> established = true
                        Outcome.FATAL -> return@launch // misconfig — retrying won't help
                        Outcome.RETRY -> {
                            delay(delayMs)
                            delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
                        }
                    }
                }
                if (!established) continue // cancelled

                // Watchdog phase: a headless session has no shell channel, so the
                // usual channel-exit reconnect trigger never fires for it. Network
                // roams are caught by NetworkMonitor, but a silent keepalive
                // timeout / server-side close would otherwise go unnoticed. Poll
                // the session and kick a reconnect when the transport has dropped.
                //
                // TCP-liveness (client.isConnected) only — this session no longer
                // carries a single canonical HTTP-probeable forward to end-to-end
                // check (it's Haven's own MCP endpoint that used to be that
                // anchor; adb is raw TCP, and guest-service ports vary), so a
                // wedged-but-TCP-alive reverse forward here isn't detected. Each
                // forward is still `selfHealOnBindFailure`, and a full reconnect
                // (triggered below on an actual transport drop) rebuilds them
                // all from scratch regardless.
                val sid = sessionId ?: continue
                var watching = true
                while (isActive && watching) {
                    delay(HEALTH_CHECK_MS)
                    val s = sshSessionManager.getSession(sid)
                    if (s == null) {
                        // Session evaporated — fall back to the outer loop to
                        // re-establish rather than giving up (the endpoint stays
                        // enabled, so the tunnel should self-heal).
                        watching = false
                        continue
                    }
                    when (s.status) {
                        SshSessionManager.SessionState.Status.CONNECTED -> {
                            if (!s.client.isConnected) {
                                Log.i(TAG, "MCP-tunnel headless transport dropped — requesting reconnect")
                                sshSessionManager.updateStatus(
                                    sid, SshSessionManager.SessionState.Status.DISCONNECTED,
                                )
                                sshSessionManager.requestReconnect(sid)
                            }
                        }
                        SshSessionManager.SessionState.Status.DISCONNECTED,
                        SshSessionManager.SessionState.Status.ERROR -> {
                            sshSessionManager.requestReconnect(sid)
                        }
                        else -> { /* CONNECTING / RECONNECTING — in progress */ }
                    }
                }
            }
        }
    }

    /**
     * Proactively revive the tunnel **now**, without waiting for the
     * [HEALTH_CHECK_MS] watchdog tick (a coroutine `delay` that Doze can defer
     * well past 15 s). Called by `SshConnectionService` on return-to-foreground
     * and on network-available — the two moments the standard SSH recovery paths
     * fire but which skip this headless session.
     *
     *  - tunnel not started / no endpoint configured → no-op.
     *  - CONNECTED with a live transport → nothing to do.
     *  - CONNECTED but the transport dropped, or any other state (CONNECTING /
     *    RECONNECTING / DISCONNECTED / ERROR) → restart the establish job so
     *    the connect-retry backoff resets to [INITIAL_RETRY_MS] instead of
     *    waiting out an in-flight 30 s [SshSessionManager] reconnect backoff
     *    while the user is right there.
     */
    fun kickNow() {
        synchronized(lock) {
            val sid = sessionId ?: return
            val port = currentPort
            if (port <= 0) return
            val session = sshSessionManager.getSession(sid) ?: return
            if (session.status == SshSessionManager.SessionState.Status.CONNECTED && session.client.isConnected) {
                return // healthy — nothing to do
            }
            Log.i(TAG, "kickNow: MCP-tunnel headless session ${session.status} — restarting with fresh backoff")
            stopLocked()
            currentPort = port
            launchTunnel(port)
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    /**
     * Multiplex any RUNNING guest-service ports that aren't already forwarded
     * onto the live tunnel session, without a reconnect. Called when a guest
     * service starts after the tunnel is already up. Additive only: a stopped
     * service's forward lingers (harmless — it just refuses connections) until
     * the next reconnect rebuilds the set from scratch in [establish].
     */
    fun refreshForwards() = synchronized(lock) {
        val sid = sessionId
        if (sid == null) {
            // No headless session yet — establish() may have found nothing
            // to carry at endpoint-enable time and skipped connecting
            // entirely (see its early-out). A guest service just started,
            // so there's something to carry now; (re)start picks it up.
            if (currentPort > 0) start(currentPort)
            return
        }
        val s = sshSessionManager.getSession(sid) ?: return
        if (s.status != SshSessionManager.SessionState.Status.CONNECTED) return
        val already = s.activeForwards
            .filter { it.type == SshSessionManager.PortForwardType.REMOTE }
            .map { it.bindPort }
            .toSet()
        val toAdd = guestServiceManager.runningPorts().distinct()
            .filter { it != currentPort && it !in already }
            .map { guestForwardInfo(it) }
        if (toAdd.isNotEmpty()) {
            sshSessionManager.applyPortForwards(sid, toAdd)
            Log.i(TAG, "Multiplexed guest ports onto MCP tunnel: ${toAdd.map { it.bindPort }}")
        }
    }

    /**
     * Expose the device's adbd (already listening on `127.0.0.1:[port]`) to the
     * MCP-tunnel endpoint as a **non-critical** REMOTE forward
     * (`127.0.0.1:[port]` on the endpoint ← phone `127.0.0.1:[port]`). The
     * phone-side hop is loopback, which the kernel never routes through a system
     * VpnService tun — so adb stays reachable even with a VPN active. Additive
     * and idempotent, mirroring [refreshForwards]; the caller persists the port
     * (UserPreferencesRepository.mcpAdbExposedPort) so [establish] re-arms it on
     * a full tunnel rebuild.
     *
     * Returns true if the forward is now bound on a live tunnel session; false
     * if the tunnel isn't currently up (the pref re-arm will bind it later) or
     * the bind failed.
     */
    fun exposeAdbPort(port: Int): Boolean = synchronized(lock) {
        val sid = sessionId
        if (sid == null) {
            // No headless session yet, same reasoning as refreshForwards():
            // the caller already persisted mcpAdbExposedPort before calling
            // this, so the fresh establish() this kicks off picks it up.
            if (currentPort > 0) start(currentPort)
            return false // not bound yet — the connect that just started will bind it
        }
        val s = sshSessionManager.getSession(sid) ?: return false
        if (s.status != SshSessionManager.SessionState.Status.CONNECTED) return false
        val alreadyBound = s.activeForwards.any {
            it.type == SshSessionManager.PortForwardType.REMOTE && it.bindPort == port
        }
        if (alreadyBound) return true
        val ok = sshSessionManager.applyPortForwards(sid, listOf(adbForwardInfo(port)))
        if (ok) {
            Log.i(TAG, "Exposed adb over MCP tunnel: -R $port")
        } else {
            Log.w(TAG, "adb -R $port failed to bind on MCP tunnel")
        }
        ok
    }

    /**
     * Tear down the adb reverse forward applied by [exposeAdbPort]: release the
     * endpoint bind and drop it from the session's activeForwards so a later
     * reconnect doesn't re-add it. No-op if the tunnel isn't up.
     */
    fun unexposeAdbPort(port: Int) {
        synchronized(lock) {
            val sid = sessionId ?: return
            sshSessionManager.getSession(sid) ?: return
            sshSessionManager.removePortForward(sid, adbForwardInfo(port))
            Log.i(TAG, "Removed adb forward from MCP tunnel: -R $port")
        }
    }

    private fun adbForwardInfo(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = ADB_FORWARD_RULE_ID,
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = "127.0.0.1",
        bindPort = port,
        targetHost = "127.0.0.1",
        targetPort = port,
        critical = false,
        selfHealOnBindFailure = true,
    )

    /**
     * A non-critical REMOTE forward for a guest MCP service port (e.g. a KiCad
     * MCP). Used both at [establish] time and by [refreshForwards] for a
     * live-add, so the two paths can't drift. selfHealOnBindFailure mirrors the
     * critical MCP forward and [adbForwardInfo]: a stale bind self-heals rather
     * than silently failing.
     */
    private fun guestForwardInfo(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = "guest-mcp-$port",
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = "127.0.0.1",
        bindPort = port,
        targetHost = "127.0.0.1",
        targetPort = port,
        critical = false,
        selfHealOnBindFailure = true,
    )

    private fun stopLocked() {
        job?.cancel()
        job = null
        teardownSession()
        currentPort = 0
    }

    /**
     * Tear down the headless tunnel session — release its server-side `-R`
     * binds and drop the [SshSessionManager] entry — WITHOUT cancelling the
     * supervising [job]. Lets the supervisory loop stand the `-R` down (e.g.
     * when WireGuard takes over the MCP transport) and re-establish later,
     * rather than ending the job. Reentrant under [lock] so [stopLocked]
     * reuses it. No-op if no session is up.
     */
    private fun teardownSession(reason: String? = null) = synchronized(lock) {
        val sid = sessionId ?: return@synchronized
        if (reason != null) Log.i(TAG, "Standing down MCP reverse tunnel: $reason")
        // Best-effort: release the server-side `-R` binds now, so a clean
        // re-enable / restart doesn't hit a stale bind. (A server only
        // reaps a dead client's forward on its own ClientAliveInterval, so
        // without this an immediate re-enable races the reaper. A hard
        // process kill skips this path and still relies on that reaper.)
        runCatching {
            val s = sshSessionManager.getSession(sid)
            val client = s?.client
            if (client != null && client.isConnected) {
                s.activeForwards
                    .filter { it.type == SshSessionManager.PortForwardType.REMOTE }
                    .forEach { fwd -> runCatching { client.delPortForwardingR(fwd.bindPort) } }
            }
        }
        sshSessionManager.removeSession(sid)
        sessionId = null
    }

    private enum class Outcome { ESTABLISHED, RETRY, FATAL }

    private suspend fun establish(mcpPort: Int): Outcome {
        val profileId = preferencesRepository.mcpTunnelEndpointProfileId.first()
        if (profileId.isNullOrBlank()) {
            Log.i(TAG, "No MCP tunnel endpoint configured — endpoint is on-device only")
            return Outcome.FATAL
        }
        // Haven's own MCP endpoint no longer rides this headless session —
        // see McpNearCarrier. Only adb-forwarding and guest-MCP-service
        // forwarding do. Skip connecting (and, more importantly, skip
        // authenticating) entirely when neither is in use, rather than
        // spinning a doomed auth retry loop for a session with nothing to
        // carry.
        val adbPort = preferencesRepository.mcpAdbExposedPort.first()
        val guestPortsAtStart = guestServiceManager.runningPorts().distinct()
        if (adbPort == null && guestPortsAtStart.isEmpty()) {
            Log.i(TAG, "Nothing to carry on the MCP-tunnel headless session (no exposed adb port, no running guest MCP service) — not connecting")
            return Outcome.FATAL
        }
        val storedProfile = connectionRepository.getById(profileId)
        if (storedProfile == null) {
            Log.w(TAG, "MCP tunnel endpoint profile $profileId not found")
            return Outcome.FATAL
        }
        if (!storedProfile.isSsh) {
            Log.w(TAG, "MCP tunnel endpoint profile ${LogRedact.of(storedProfile.label)} is not SSH — ignoring")
            return Outcome.FATAL
        }
        // Apply the effective SSH identity (#360) before resolving headless
        // auth, so an agent-initiated connect honours the same credential
        // bundle as an interactive one. No-op when no identity is assigned.
        val profile = sshIdentityRepository.applyTo(storedProfile)
        val auth = resolveHeadlessAuth(profile)
        if (auth == null) {
            // Expected, not a failure: a touch-required / encrypted-only
            // endpoint (e.g. a FIDO2 key) can't authenticate a *headless*
            // connection, so adb/guest-service forwarding just aren't
            // available over this tunnel for this endpoint. Don't write a
            // per-kick FAILED connection-log entry (it read as a failure
            // storm) — just note it in logcat and stop the headless attempt.
            Log.i(
                TAG,
                "MCP-tunnel headless session for ${profile.label} needs a non-interactive " +
                    "(unencrypted, non-FIDO2) key — adb/guest-service forwarding unavailable",
            )
            return Outcome.FATAL
        }

        val config = ConnectionConfig(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            authMethod = auth,
            sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
            addressFamily = ConnectionConfig.AddressFamily.valueOf(profile.addressFamilyEnum.name),
            bindAddress = profile.bindAddress,
            // Unlimited self-healing: a headless tunnel that holds the MCP
            // forward should never give up while the endpoint is enabled.
            reconnectPolicy = ConnectionConfig.ReconnectPolicy(
                autoReconnect = true,
                maxAttempts = 0,
                onNetworkChange = true,
            ),
        )

        val client = SshClient()
        val sid = sshSessionManager.registerSession(
            profileId = profile.id,
            label = "MCP tunnel: ${profile.label}",
            client = client,
            headless = true,
        )
        sessionId = sid
        return try {
            val hostKeyEntry = client.connect(config, trustedHostCaKeys = hostKeyVerifier.trustedHostCaKeys())
            // Fail-closed TOFU: this headless tunnel never silently trusts an
            // unknown or changed host key. The user establishes trust by
            // connecting the profile interactively first (host-key prompt). (#5)
            when (val r = if (hostKeyEntry == null) HostKeyResult.Trusted else hostKeyVerifier.verify(hostKeyEntry)) {
                is HostKeyResult.Trusted -> { /* ok */ }
                is HostKeyResult.NewHost -> {
                    Log.w(TAG, "Unknown host key for ${LogRedact.of(profile.label)} — refusing MCP tunnel (connect it interactively first)")
                    cleanup(sid)
                    return Outcome.FATAL
                }
                is HostKeyResult.KeyChanged -> {
                    Log.w(TAG, "Host key changed for ${LogRedact.of(profile.label)} — refusing MCP tunnel")
                    cleanup(sid)
                    return Outcome.FATAL
                }
            }
            sshSessionManager.storeConnectionConfig(sid, config, SessionManager.NONE)
            sshSessionManager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)

            // Everything this session carries is non-critical now (Haven's
            // own MCP endpoint moved to McpNearCarrier, riding the
            // interactive session instead) — a guest crash or stale guest
            // bind must never tear this session down, it just self-heals.
            val forwards = mutableListOf<SshSessionManager.PortForwardInfo>()
            guestServiceManager.runningPorts().distinct()
                .filter { it != mcpPort }
                .forEach { forwards.add(guestForwardInfo(it)) }
            preferencesRepository.mcpAdbExposedPort.first()?.let { adbPort ->
                if (adbPort != mcpPort && forwards.none { it.bindPort == adbPort }) {
                    forwards.add(adbForwardInfo(adbPort))
                }
            }
            if (forwards.isEmpty()) {
                // Raced: whatever we were carrying (checked at the top of
                // establish()) stopped between then and now. Nothing to
                // bind, but the session itself connected fine — keep it up
                // rather than tearing down a fresh connect for nothing;
                // refreshForwards()/exposeAdbPort() will use it once there's
                // something to carry again.
                Log.i(TAG, "Connected to ${LogRedact.of(profile.label)} with nothing (yet) to forward")
            } else if (!sshSessionManager.applyPortForwards(sid, forwards)) {
                Log.w(TAG, "Forward(s) failed to bind on ${LogRedact.of(profile.label)} (stale bind?) — retrying")
                cleanup(sid)
                return Outcome.RETRY
            }
            Log.i(
                TAG,
                "MCP-tunnel headless session up via ${profile.label}" +
                    (if (forwards.isNotEmpty()) " (${forwards.map { it.bindPort }.joinToString(",")})" else ""),
            )
            connectionLogRepository.logEvent(
                profile.id, ConnectionLog.Status.CONNECTED,
                details = "MCP-tunnel headless session (adb/guest-service forwarding)",
            )
            Outcome.ESTABLISHED
        } catch (e: Exception) {
            Log.w(TAG, "MCP tunnel connect to ${LogRedact.of(profile.label)} failed: ${e.message}")
            cleanup(sid)
            Outcome.RETRY
        }
    }

    private fun cleanup(sid: String) {
        sshSessionManager.removeSession(sid)
        if (sessionId == sid) sessionId = null
    }

    /**
     * Resolve a non-interactive [ConnectionConfig.AuthMethod] for the
     * endpoint, or null if the profile would require a UI prompt. The
     * actual rules live in [HeadlessSshAuth], shared with the run_command
     * executor ([HeadlessSshExec]) so the two headless connectors can't
     * drift.
     */
    private suspend fun resolveHeadlessAuth(profile: ConnectionProfile): ConnectionConfig.AuthMethod? =
        HeadlessSshAuth.resolve(profile, sshKeyRepository)

    private companion object {
        const val ADB_FORWARD_RULE_ID = "adb-reverse"
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 30_000L
        const val HEALTH_CHECK_MS = 15_000L
    }
}
