package sh.haven.feature.vnc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.vnc.protocol.AuthenticationFailedException
import sh.haven.core.vnc.protocol.VncAuthMethod

/**
 * The auth-failure text must match the scheme that ran: a macOS account
 * login (type 30) has no 8-character limit, so telling the user to shorten
 * their password or run vncpasswd sends them the wrong way.
 */
class VncAuthErrorTest {

    private val macTypes = listOf(30, 33, 36, 2, 35)

    private fun describe(method: VncAuthMethod, offered: List<Int> = emptyList()) =
        describeVncError(
            AuthenticationFailedException("Authentication or authorization failure", method, offered),
            "127.0.0.1",
            40023,
        )

    @Test
    fun `macOS account failure points at the account, not the VNC password limit`() {
        val text = describe(VncAuthMethod.MACOS_ACCOUNT, macTypes)
        assertTrue(text.startsWith("Authentication failed: Authentication or authorization failure."))
        assertTrue(text.contains("short name"))
        assertTrue(text.contains("Allow access for"))
        assertFalse(text.contains("limited to 8 characters"))
        assertFalse(text.contains("vncpasswd"))
    }

    @Test
    fun `VncAuth failure against a Mac suggests the account login`() {
        val text = describe(VncAuthMethod.VNC_PASSWORD, macTypes)
        assertTrue(text.contains("limited to 8 characters"))
        assertTrue(text.contains("macOS Screen Sharing"))
        assertFalse(text.contains("vncpasswd"))
    }

    @Test
    fun `VncAuth failure elsewhere keeps the vncpasswd hint`() {
        val text = describe(VncAuthMethod.VNC_PASSWORD, listOf(2))
        assertTrue(text.contains("limited to 8 characters"))
        assertTrue(text.contains("vncpasswd"))
    }

    @Test
    fun `VeNCrypt Plain failure does not mention the 8-character limit`() {
        val text = describe(VncAuthMethod.USERNAME_PASSWORD, listOf(19))
        assertTrue(text.contains("username or password"))
        assertFalse(text.contains("limited to 8 characters"))
    }
}
