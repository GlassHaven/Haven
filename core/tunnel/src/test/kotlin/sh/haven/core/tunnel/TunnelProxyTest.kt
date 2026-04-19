package sh.haven.core.tunnel

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TunnelProxyTest {

    @Test
    fun connectDialsTunnelWithHostPortTimeout() {
        val tunnel = mockk<Tunnel>()
        val conn = fakeConn()
        every { tunnel.dial("example.com", 22, 10_000) } returns conn

        val proxy = TunnelProxy(tunnel)
        proxy.connect(null, "example.com", 22, 10_000)

        verify(exactly = 1) { tunnel.dial("example.com", 22, 10_000) }
    }

    @Test
    fun streamsComeFromDialedConnection() {
        val tunnel = mockk<Tunnel>()
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val output = ByteArrayOutputStream()
        val conn = object : TunneledConnection {
            override val inputStream = input
            override val outputStream = output
            override fun close() {}
        }
        every { tunnel.dial(any(), any(), any()) } returns conn

        val proxy = TunnelProxy(tunnel)
        proxy.connect(null, "h", 1, 0)

        assertSame(input, proxy.inputStream)
        assertSame(output, proxy.outputStream)
    }

    @Test
    fun getSocketIsNull() {
        // Our tunnels are userspace-netstack backed; there is no kernel
        // Socket. JSch's Proxy contract allows null here, and a non-null
        // return would imply direct BSD socket access we don't have.
        val tunnel = mockk<Tunnel>()
        every { tunnel.dial(any(), any(), any()) } returns fakeConn()

        val proxy = TunnelProxy(tunnel)
        proxy.connect(null, "h", 1, 0)

        assertNull(proxy.socket)
    }

    @Test
    fun closeClosesConnectionAndClearsState() {
        val tunnel = mockk<Tunnel>()
        val conn = mockk<TunneledConnection>(relaxed = true)
        every { tunnel.dial(any(), any(), any()) } returns conn

        val proxy = TunnelProxy(tunnel)
        proxy.connect(null, "h", 1, 0)
        proxy.close()

        verify(exactly = 1) { conn.close() }
        assertThrows(IllegalStateException::class.java) { proxy.inputStream }
    }

    @Test
    fun accessingStreamsBeforeConnectFails() {
        val tunnel = mockk<Tunnel>()
        val proxy = TunnelProxy(tunnel)
        assertThrows(IllegalStateException::class.java) { proxy.inputStream }
        assertThrows(IllegalStateException::class.java) { proxy.outputStream }
    }

    private fun fakeConn(): TunneledConnection = object : TunneledConnection {
        override val inputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}
