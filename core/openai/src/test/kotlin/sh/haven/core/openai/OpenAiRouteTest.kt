package sh.haven.core.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.SocketFactory

/**
 * The pure route policy shared by all three OPENAI dial sites — the
 * fail-closed contract lives here so the connect path, the chat stream and
 * the agent tool cannot drift.
 */
class OpenAiRouteTest {

    private val factory = object : SocketFactory() {
        override fun createSocket() = throw java.net.SocketException("unused")
        override fun createSocket(host: String, port: Int) = throw java.net.SocketException("unused")
        override fun createSocket(
            host: String, port: Int, localHost: java.net.InetAddress, localPort: Int,
        ) = throw java.net.SocketException("unused")
        override fun createSocket(host: java.net.InetAddress, port: Int) = throw java.net.SocketException("unused")
        override fun createSocket(
            host: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int,
        ) = throw java.net.SocketException("unused")
    }

    // --- isRouted ---

    @Test fun routedRequiresCoherentTypeAndCarrier() {
        assertTrue(AiRoute.isRouted("SSH", "carrier"))
        assertTrue(AiRoute.isRouted("RETICULUM", "carrier"))
    }

    @Test fun routedFalseForStaleOrUnknownPairs() {
        assertFalse(AiRoute.isRouted("SSH", null))
        assertFalse(AiRoute.isRouted(null, "carrier"))
        assertFalse(AiRoute.isRouted("BOGUS", "carrier"))
        assertFalse(AiRoute.isRouted(null, null))
    }

    @Test fun knownRouteTypes() {
        assertTrue(AiRoute.isKnownRouteType("SSH"))
        assertTrue(AiRoute.isKnownRouteType("RETICULUM"))
        assertFalse(AiRoute.isKnownRouteType("BOGUS"))
        assertFalse(AiRoute.isKnownRouteType(null))
    }

    // --- dialFactory ---

    @Test fun routedUsesTheCarrierFactoryAndNeverConsultsTheTunnel() {
        // Double-hop guard: a routed dial ignores the tunnel entirely —
        // tunnelFactory null and tunnelConfigured true would be refused if
        // consulted.
        val dial = AiRoute.dialFactory(
            routed = true, routeFactory = factory,
            tunnelFactory = null, tunnelConfigured = true,
        )
        assertEquals(AiRoute.Dial.Via(factory), dial)
    }

    @Test fun routedWithoutAFactoryRefuses() {
        // The carrier's forward died between connect and dial: refuse, never
        // fall through to direct.
        val dial = AiRoute.dialFactory(
            routed = true, routeFactory = null,
            tunnelFactory = factory, tunnelConfigured = true,
        )
        assertTrue(dial is AiRoute.Dial.Refused)
        assertTrue((dial as AiRoute.Dial.Refused).reason.contains("carrier"))
    }

    @Test fun unroutedTunnelConfiguredUsesTheTunnelFactory() {
        assertEquals(
            AiRoute.Dial.Via(factory),
            AiRoute.dialFactory(false, null, factory, tunnelConfigured = true),
        )
    }

    @Test fun tunnelConfiguredWithoutAFactoryRefuses() {
        val dial = AiRoute.dialFactory(false, null, null, tunnelConfigured = true)
        assertTrue(dial is AiRoute.Dial.Refused)
        assertTrue((dial as AiRoute.Dial.Refused).reason.contains("Tunnel"))
    }

    @Test fun noRouteMeansPlainDirectDial() {
        assertEquals(
            AiRoute.Dial.Via(null),
            AiRoute.dialFactory(false, null, null, tunnelConfigured = false),
        )
    }

    // --- endpointHostPort ---

    @Test fun bareHostComposesWithTheProfilePort() {
        assertEquals("llm.lan" to 8317, AiRoute.endpointHostPort("llm.lan", 8317))
    }

    @Test fun bareHostWithoutAPortDefaultsTo80() {
        assertEquals("llm.lan" to 80, AiRoute.endpointHostPort("llm.lan", 0))
    }

    @Test fun urlHostOverridesTheProfilePort() {
        assertEquals(
            "api.example.com" to 8443,
            AiRoute.endpointHostPort("https://api.example.com:8443", 22),
        )
    }

    @Test fun urlWithoutAPortDefaultsByScheme() {
        assertEquals("api.example.com" to 80, AiRoute.endpointHostPort("http://api.example.com", 22))
        assertEquals("api.example.com" to 443, AiRoute.endpointHostPort("https://api.example.com", 22))
    }

    @Test fun urlWithoutAHostIsRejected() {
        var thrown = false
        try {
            AiRoute.endpointHostPort("https:///path", 22)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test fun dialViaWithNullFactoryMeansDirect() {
        val via = AiRoute.Dial.Via(null)
        assertNull((via as AiRoute.Dial.Via).factory)
    }
}