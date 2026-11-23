package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #876 — a management dial timeout on a LAN-only self-hosted server is an
 * unreachable underlay (the phone is remote; Haven's per-connection tunnels
 * do not carry the tunnel's own underlay sockets). The error must say so
 * instead of leaving the user with a bare "context deadline exceeded".
 */
class NetbirdStartFailureMessageTest {

    private val dialTimeout =
        "join netbird network: create auth client: create connection: dial context: context deadline exceeded"

    @Test
    fun `non-dial errors pass through unchanged`() {
        val raw = "invalid setup key"
        assertEquals(
            "Failed to start NetBird tunnel: invalid setup key",
            netbirdStartFailureMessage(raw, "https://netbird.example.com:443"),
        )
    }

    @Test
    fun `null message degrades to unknown error`() {
        assertEquals(
            "Failed to start NetBird tunnel: unknown error",
            netbirdStartFailureMessage(null, ""),
        )
    }

    @Test
    fun `dial timeout on a self-hosted server names the underlay problem`() {
        val msg = netbirdStartFailureMessage(dialTimeout, "https://192.168.0.180:443")
        assertTrue(msg.contains("https://192.168.0.180:443"))
        assertTrue(msg.contains("LAN-only"))
        assertTrue(msg.contains("underlay"))
        assertTrue(msg.startsWith("Failed to start NetBird tunnel: $dialTimeout"))
    }

    @Test
    fun `dial timeout on the hosted default points at connectivity, not LAN`() {
        val msg = netbirdStartFailureMessage(dialTimeout, "")
        assertTrue(msg.contains("hosted NetBird management server"))
        assertTrue(msg.contains("network connectivity"))
        assertFalse(msg.contains("LAN-only"))
    }
}
