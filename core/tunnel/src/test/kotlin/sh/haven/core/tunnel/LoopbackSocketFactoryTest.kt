package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress

/**
 * The loopback factory dials its bind port whatever the caller asks for —
 * the contract the AI route carriers (SSH LOCAL forward, Reticulum bridge)
 * depend on. The real endpoint name must never be dialled.
 */
class LoopbackSocketFactoryTest {

    /** A socket pair served by a little in-process echo server. */
    private fun echoServer(): ServerSocket = ServerSocket(0).apply {
        Thread {
            try {
                val client = accept()
                // One read is enough — the tests write one small payload.
                // readBytes() would block until the client's EOF and hang.
                client.soTimeout = 5000
                val buf = ByteArray(64)
                val n = client.getInputStream().read(buf)
                client.getOutputStream().write(buf, 0, n)
                client.close()
            } catch (_: Exception) {
                // client-side failures surface in the test body
            }
        }.apply { isDaemon = true }.start()
    }

    @Test
    fun `createSocket host port dials the bind port not the named endpoint`() {
        val server = echoServer()
        val dialled = mutableListOf<Int>()
        val factory = LoopbackSocketFactory(
            port = server.localPort,
            dial = { p ->
                dialled += p
                Socket().apply { connect(InetSocketAddress("127.0.0.1", p)) }
            },
        )
        val socket = factory.createSocket("real-endpoint.example.com", 8443)
        socket.getOutputStream().write("ping".toByteArray())
        val buf = ByteArray(4)
        assertEquals(4, socket.getInputStream().read(buf))
        assertEquals("ping", String(buf))
        assertEquals(listOf(server.localPort), dialled)
        server.close()
    }

    @Test
    fun `createSocket then connect ignores the requested endpoint`() {
        // OkHttp's connect path: bare socket, then connect(real endpoint).
        // The endpoint address must be ignored — dialled port is the bind.
        val server = echoServer()
        val dialled = mutableListOf<Int>()
        val factory = LoopbackSocketFactory(
            port = server.localPort,
            dial = { p ->
                dialled += p
                Socket().apply { connect(InetSocketAddress("127.0.0.1", p)) }
            },
        )
        val socket = factory.createSocket()
        val endpoint: SocketAddress = InetSocketAddress("real-endpoint.example.com", 8443)
        socket.connect(endpoint, 5000)
        assertTrue(socket.isConnected)
        socket.getOutputStream().write("hello".toByteArray())
        val buf = ByteArray(5)
        assertEquals(5, socket.getInputStream().read(buf))
        assertEquals("hello", String(buf))
        assertEquals(listOf(server.localPort), dialled)
        socket.close()
        server.close()
    }

    @Test
    fun `echo round trip over the deferred socket carries bytes both ways`() {
        val server = echoServer()
        val factory = LoopbackSocketFactory(
            port = server.localPort,
            dial = { p -> Socket().apply { connect(InetSocketAddress("127.0.0.1", p)) } },
        )
        val socket = factory.createSocket()
        socket.connect(InetSocketAddress("10.255.255.1", 9999), 5000)
        socket.soTimeout = 2000
        assertEquals(2000, socket.soTimeout)
        socket.getOutputStream().write("roundtrip".toByteArray())
        val received = ByteArrayOutputStream()
        val buf = ByteArray(64)
        var n: Int
        while (received.size() < "roundtrip".length) {
            n = socket.getInputStream().read(buf)
            if (n < 0) break
            received.write(buf, 0, n)
        }
        assertEquals("roundtrip", received.toString("UTF-8"))
        socket.close()
        assertTrue(socket.isClosed)
        server.close()
    }

    @Test
    fun `socket options set before connect are accepted not fatal`() {
        // OkHttp 5 (ConnectPlan.connectSocket) sets soTimeout on the raw
        // socket BEFORE calling connect(); the option must not throw then.
        // It is dropped (no kernel socket yet) — the post-connect getter must
        // still round-trip, and the dial must still go to the bind port.
        val server = echoServer()
        val dialled = mutableListOf<Int>()
        val factory = LoopbackSocketFactory(
            port = server.localPort,
            dial = { p ->
                dialled += p
                Socket().apply { connect(InetSocketAddress("127.0.0.1", p)) }
            },
        )
        val socket = factory.createSocket()
        socket.soTimeout = 1234 // pre-connect: OkHttp 5's order, was IOException
        socket.tcpNoDelay = true
        assertEquals(0, socket.soTimeout) // dropped — nothing to apply it to
        socket.connect(InetSocketAddress("real-endpoint.example.com", 8443), 5000)
        socket.soTimeout = 4321 // post-connect: reaches the kernel socket
        assertEquals(4321, socket.soTimeout)
        socket.getOutputStream().write("ping".toByteArray())
        val buf = ByteArray(4)
        assertEquals(4, socket.getInputStream().read(buf))
        assertEquals("ping", String(buf))
        assertEquals(listOf(server.localPort), dialled)
        socket.close()
        server.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive bind port is rejected`() {
        LoopbackSocketFactory(port = 0)
    }
}