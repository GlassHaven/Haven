package sh.haven.core.ssh

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AUTO address-family fallback (#566): with several resolved addresses, the
 * first one that answers a TCP handshake is used instead of blindly taking
 * the resolver's first answer.
 */
class SshClientAutoAddressTest {

    private val a = InetAddress.getByName("192.0.2.1")
    private val b = InetAddress.getByName("192.0.2.2")
    private val c = InetAddress.getByName("192.0.2.3")

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(SshClient.selectAutoAddress(emptyList()) { error("must not probe") })
    }

    @Test
    fun `single address is returned without probing`() {
        val picked = SshClient.selectAutoAddress(listOf(a)) { error("must not probe") }
        assertEquals(a, picked)
    }

    @Test
    fun `first reachable address wins`() {
        val probed = mutableListOf<InetAddress>()
        val picked = SshClient.selectAutoAddress(listOf(a, b, c)) { addr ->
            probed += addr
            true
        }
        assertEquals(a, picked)
        assertEquals(listOf(a), probed)
    }

    @Test
    fun `dead first address falls through to the second`() {
        val picked = SshClient.selectAutoAddress(listOf(a, b, c)) { addr -> addr == b }
        assertEquals(b, picked)
    }

    @Test
    fun `no reachable address falls back to the first`() {
        val picked = SshClient.selectAutoAddress(listOf(a, b, c)) { false }
        assertEquals(a, picked)
    }

    @Test
    fun `probing stops after four candidates`() {
        val d = InetAddress.getByName("192.0.2.4")
        val e = InetAddress.getByName("192.0.2.5")
        var probes = 0
        SshClient.selectAutoAddress(listOf(a, b, c, d, e)) {
            probes++
            false
        }
        assertEquals(4, probes)
    }

    @Test
    fun `probeTcp completes a real handshake against a listening socket`() {
        ServerSocket(0).use { server ->
            val loopback = InetAddress.getByName("127.0.0.1")
            assertTrue(SshClient.probeTcp(loopback, server.localPort, timeoutMs = 2000))
        }
    }

    @Test
    fun `probeTcp fails against a closed port`() {
        // Bind then close to get a port that was just proven free.
        val port = ServerSocket(0).use { it.localPort }
        val loopback = InetAddress.getByName("127.0.0.1")
        assertFalse(SshClient.probeTcp(loopback, port, timeoutMs = 2000))
    }

    @Test
    fun `probeTcp accepts any address when no port is known`() {
        assertTrue(SshClient.probeTcp(a, 0, timeoutMs = 1))
    }
}
