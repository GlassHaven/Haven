package sh.haven.core.ssh

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class SshClientResolveHostTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `resolveHost returns IP literal as-is`() {
        assertEquals("192.168.1.1", SshClient.resolveHost("192.168.1.1"))
    }

    @Test
    fun `resolveHost returns onion address as-is`() {
        assertEquals(
            "abcdef1234567890.onion",
            SshClient.resolveHost("abcdef1234567890.onion"),
        )
    }

    @Test
    fun `resolveHost does not resolve onion subdomain`() {
        assertEquals(
            "subdomain.abcdef1234567890.onion",
            SshClient.resolveHost("subdomain.abcdef1234567890.onion"),
        )
    }

    @Test
    fun `resolveHost does not treat onion-like suffix as onion`() {
        // "notonion" should not match — only exact ".onion" suffix
        // DNS fails for this fake hostname, throwing UnknownHostException
        assertThrows(java.net.UnknownHostException::class.java) {
            SshClient.resolveHost("host.notonion")
        }
    }
}
