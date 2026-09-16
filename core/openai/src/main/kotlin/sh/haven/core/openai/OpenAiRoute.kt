package sh.haven.core.openai

import android.util.Log
import sh.haven.core.data.db.entities.ConnectionProfile
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Route policy for OPENAI endpoint dials — pure, shared by all three call
 * sites (the connect path in ConnectionsViewModel, the chat stream in
 * ChatViewModel, and the agent's `openai_chat` tool), so none of them can
 * drift on the fail-closed contract.
 *
 * Two routing layers, mutually exclusive by design:
 *
 *  - **AI route carrier** ([ConnectionProfile.aiRouteType]): the endpoint's
 *    HTTP rides a live carrier — an SSH session's LOCAL forward or a
 *    Reticulum mesh bridge — presented to the client as a loopback bind
 *    (`127.0.0.1:<boundPort>`). The factory is minted per connect and kept
 *    on the session state; a routed dial uses it and never consults the
 *    profile's own tunnel (the carrier itself is the transport — routing
 *    the endpoint through a tunnel AND through the carrier would be a
 *    double-hop).
 *  - **Route-through tunnel** ([ConnectionProfile.tunnelConfigId]): the
 *    per-profile WireGuard / Tailscale / SOCKS / HTTP routing every other
 *    transport uses.
 *
 * In both cases a configured-but-unresolvable route refuses the dial
 * rather than falling through to direct (R7): a misconfigured route must
 * fail loudly, never leak an unencrypted / unbypassed connection it was
 * explicitly set up to avoid.
 */
object AiRoute {

    /** True when the stored (type, carrier) pair is a coherent route. */
    fun isRouted(routeType: String?, carrierProfileId: String?): Boolean =
        (routeType == "SSH" || routeType == "RETICULUM") && carrierProfileId != null

    /** True when [routeType] is a carrier kind a dial can actually use. */
    fun isKnownRouteType(routeType: String?): Boolean =
        routeType == "SSH" || routeType == "RETICULUM"

    /**
     * Pick the [SocketFactory] for a dial. [routeFactory] is the carrier's
     * loopback factory (from session state); [tunnelFactory] and
     * [tunnelConfigured] are the Route-through resolution.
     */
    fun dialFactory(
        routed: Boolean,
        routeFactory: SocketFactory?,
        tunnelFactory: SocketFactory?,
        tunnelConfigured: Boolean,
    ): Dial = when {
        routed -> routeFactory
            ?.let { Dial.Via(it) }
            ?: Dial.Refused(
                "AI route carrier not established — refusing a direct dial to the endpoint.",
            )
        tunnelConfigured -> tunnelFactory
            ?.let { Dial.Via(it) }
            ?: Dial.Refused(
                "Tunnel configured but provides no socket factory — refusing to dial directly.",
            )
        else -> Dial.Via(null)
    }

    sealed interface Dial {
        /** [factory] null = plain direct dial (no route configured). */
        data class Via(val factory: SocketFactory?) : Dial
        data class Refused(val reason: String) : Dial
    }

    /**
     * The endpoint's `host:port` as the carrier dials it (the SSH forward's
     * remote target / the mesh bridge's `nc` target). [host] is either a bare
     * host (composed with [port]) or a full base URL with scheme, mirroring
     * [ConnectionProfile.openaiBaseUrl].
     */
    fun endpointHostPort(host: String, port: Int): Pair<String, Int> {
        val trimmed = host.trim()
        if (trimmed.contains("://")) {
            // java.net.URI over URL: URI's getPort returns -1 for an absent
            // port (URL.get_port throws for unparseable forms instead).
            val uri = java.net.URI(trimmed)
            val p = uri.port.takeIf { it != -1 } ?: if (uri.scheme == "https") 443 else 80
            val host = uri.host ?: throw IllegalArgumentException("URL has no host: $trimmed")
            return host to p
        }
        return trimmed to if (port > 0) port else 80
    }
}

/**
 * Live AI-route handles, keyed by the endpoint profile that owns each one.
 *
 * The connect path registers a handle when it establishes a carrier; every
 * disconnect path — ConnectionsViewModel's and the agent's
 * `disconnect_profile` alike — calls [teardownFor], so a route never
 * outlives its endpoint or its carrier whichever path the disconnect
 * arrives on. Without a shared registry the programmatic path had no way
 * to reach the UI-scoped handle store and the carrier's LOCAL forward
 * survived the disconnect (observed: `ss -tln` still LISTENing after
 * `disconnect_profile`).
 */
@Singleton
class AiRouteRegistry @Inject constructor() {

    /** One live route: who owns it, which carrier carries it, how to tear it down. */
    class Handle(
        val ownerProfileId: String,
        val carrierProfileId: String,
        /** Idempotent resource teardown: close the SSH lease / stop the mesh forward. */
        val release: () -> Unit,
        /** Fail the owner's sessions — the route vanished under their feet. */
        val onUnreachable: () -> Unit,
    )

    private val handles = java.util.concurrent.ConcurrentHashMap<String, Handle>()

    fun register(handle: Handle) {
        handles[handle.ownerProfileId] = handle
    }

    /**
     * Release the route [ownerProfileId] owns, if any. Idempotent; a failing
     * [Handle.release] is logged and swallowed so one bad resource can't
     * block the rest of the teardown.
     */
    fun release(ownerProfileId: String) {
        val handle = handles.remove(ownerProfileId) ?: return
        runCatching(handle.release).onFailure {
            Log.w(TAG, "AI route release failed for $ownerProfileId", it)
        }
    }

    /**
     * Full teardown for [profileId]: release the route it owns (it's the
     * endpoint) and cascade the routes it carries (it's the carrier — the
     * routed endpoints' sessions must fail closed rather than sit green
     * over a dead forward). Call BEFORE the profile's transports are
     * disconnected so the carrier's client is still alive to remove the
     * LOCAL forward from.
     */
    fun teardownFor(profileId: String) {
        release(profileId)
        cascadeCarried(profileId)
    }

    /**
     * The carrier session behind [carrierProfileId]'s routes died (network
     * death, cascade, removeSession). Release every route it carries — best
     * effort, a forward on a dead client releases as a no-op — and fail the
     * owners' sessions.
     */
    fun carrierGone(carrierProfileId: String) {
        cascadeCarried(carrierProfileId)
    }

    private fun cascadeCarried(carrierProfileId: String) {
        handles.entries
            .filter { it.value.carrierProfileId == carrierProfileId }
            .map { it.key }
            .forEach { owner ->
                val handle = handles.remove(owner) ?: return@forEach
                runCatching(handle.release).onFailure {
                    Log.w(TAG, "AI route release failed for $owner", it)
                }
                runCatching(handle.onUnreachable).onFailure {
                    Log.w(TAG, "AI route onUnreachable failed for $owner", it)
                }
            }
    }

    private companion object {
        const val TAG = "AiRouteRegistry"
    }
}