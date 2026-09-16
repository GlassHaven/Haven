package sh.haven.core.openai

import sh.haven.core.data.db.entities.ConnectionProfile
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