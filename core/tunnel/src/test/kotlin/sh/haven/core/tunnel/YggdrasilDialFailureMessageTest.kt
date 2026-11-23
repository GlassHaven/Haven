package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #876 — a mesh dial timeout with LAN-only peers is an unreachable
 * underlay (no working peer link from the remote network). The error
 * must say so instead of leaving the user with a bare deadline.
 */
class YggdrasilDialFailureMessageTest {

    @Test
    fun `non-deadline errors pass through unchanged`() {
        assertEquals(
            "Yggdrasil dial 200:db8::1:9999 failed: connection refused",
            yggdrasilDialFailureMessage("200:db8::1", 9999, "connection refused"),
        )
    }

    @Test
    fun `null message degrades to unknown error`() {
        assertEquals(
            "Yggdrasil dial 200:db8::1:9999 failed: unknown error",
            yggdrasilDialFailureMessage("200:db8::1", 9999, null),
        )
    }

    @Test
    fun `dial timeout names the peer-link problem`() {
        val msg = yggdrasilDialFailureMessage("200:dab9::7c91", 9999, "context deadline exceeded")
        assertTrue(msg.startsWith("Yggdrasil dial 200:dab9::7c91:9999 failed: context deadline exceeded"))
        assertTrue(msg.contains("peer link"))
        assertTrue(msg.contains("LAN-only"))
        assertTrue(msg.contains("public peer"))
    }

    @Test
    fun `deadline hint does not fire on unrelated errors`() {
        val msg = yggdrasilDialFailureMessage("200:db8::1", 9999, "no route to host")
        assertFalse(msg.contains("peer link"))
    }
}
