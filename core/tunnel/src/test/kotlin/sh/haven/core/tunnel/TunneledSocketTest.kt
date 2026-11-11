package sh.haven.core.tunnel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress

class TunneledSocketTest {

    @Test
    fun inputStreamReadsFromConnection() {
        val payload = "hello".toByteArray()
        val s = TunneledSocket(FakeConn(payload), "example.com", 443)
        val read = s.getInputStream().readBytes()
        assertArrayEquals(payload, read)
    }

    @Test
    fun outputStreamWritesToConnection() {
        val sink = ByteArrayOutputStream()
        val s = TunneledSocket(FakeConn(output = sink), "example.com", 443)
        s.getOutputStream().write("world".toByteArray())
        assertArrayEquals("world".toByteArray(), sink.toByteArray())
    }

    @Test
    fun portMatchesConstructorArg() {
        val s = TunneledSocket(FakeConn(), "example.com", 1234)
        assertEquals(1234, s.port)
    }

    @Test
    fun inetAddressResolvesHostname() {
        val s = TunneledSocket(FakeConn(), "127.0.0.1", 80)
        assertNotNull(s.inetAddress)
        assertEquals("127.0.0.1", s.inetAddress?.hostAddress)
    }

    @Test
    fun closeIsIdempotent() {
        val conn = CountingConn()
        val s = TunneledSocket(conn, "h", 80)
        s.close()
        s.close()
        s.close()
        assertEquals(1, conn.closeCount)
        assertTrue(s.isClosed)
        assertFalse(s.isConnected)
    }

    @Test
    fun getInputStreamAfterCloseThrows() {
        val s = TunneledSocket(FakeConn(), "h", 80)
        s.close()
        try {
            s.getInputStream()
            fail("expected IOException after close")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun getOutputStreamAfterCloseThrows() {
        val s = TunneledSocket(FakeConn(), "h", 80)
        s.close()
        try {
            s.getOutputStream()
            fail("expected IOException after close")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun connectThrowsUnsupported() {
        val s = TunneledSocket(FakeConn(), "h", 80)
        try {
            s.connect(InetSocketAddress("x", 1))
            fail("expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) { /* expected */ }
    }

    @Test
    fun bindThrowsUnsupported() {
        val s = TunneledSocket(FakeConn(), "h", 80)
        try {
            s.bind(InetSocketAddress("x", 1))
            fail("expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) { /* expected */ }
    }

    @Test
    fun deferredTunneledSocketUsesTunnelRemoteAddressWithoutSystemDns() {
        val conn = object : TunneledConnection {
            override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
            override val outputStream: OutputStream = ByteArrayOutputStream()
            override val remoteAddress: String = "10.55.0.1"
            override fun close() {}
        }
        val tunnel = object : Tunnel {
            override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
                assertEquals("glkvm.lan", host)
                assertEquals(443, port)
                return conn
            }
            override fun close() {}
        }
        val s = DeferredTunneledSocket(tunnel, dialTimeoutMs = 5_000)
        s.connect(InetSocketAddress.createUnresolved("glkvm.lan", 443))

        assertNotNull("DeferredTunneledSocket must expose tunnel-reported peer IP (#539)", s.inetAddress)
        assertEquals("10.55.0.1", s.inetAddress?.hostAddress)
        val remote = s.remoteSocketAddress as? InetSocketAddress
        assertNotNull(remote)
        assertFalse("remoteSocketAddress should be resolved when tunnel reports peer IP", remote!!.isUnresolved)
        assertEquals("10.55.0.1", remote.address.hostAddress)
        assertEquals(443, remote.port)
    }

    private class FakeConn(
        input: ByteArray = ByteArray(0),
        output: OutputStream = ByteArrayOutputStream(),
    ) : TunneledConnection {
        override val inputStream: InputStream = ByteArrayInputStream(input)
        override val outputStream: OutputStream = output
        override fun close() {}
    }

    private class CountingConn : TunneledConnection {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        var closeCount: Int = 0
            private set
        override fun close() { closeCount++ }
    }
}
