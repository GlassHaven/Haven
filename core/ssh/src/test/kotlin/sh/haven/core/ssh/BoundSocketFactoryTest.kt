package sh.haven.core.ssh

import com.jcraft.jsch.JSchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The bind-side proof for ssh -b on a connection profile (#636).
 *
 * [BoundSocketFactoryTest.peerAddressIsTheBoundAddress] is the load-bearing
 * one: a factory-bound socket arriving on a server listening on a DIFFERENT
 * loopback address (127.0.0.2, routed by the same `lo` device on Linux) shows
 * the bound source, while an unbound (wildcard) socket would have shown the
 * destination address. So the observed peer address is direct evidence the
 * bind was applied, not an incidental property of loopback.
 */
class BoundSocketFactoryTest {

    private fun factory(bind: String, timeoutMs: Int = 5_000) =
        BoundSocketFactory(bind, timeoutMs)

    @Test
    fun `peer address is the bound address`() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.2"))
        val accepted = java.util.concurrent.CompletableFuture<String>()
        val acceptor = thread(isDaemon = true) {
            runCatching {
                server.accept().use { peer -> accepted.complete(peer.inetAddress.hostAddress) }
            }
        }
        try {
            // A factory-less socket connecting to 127.0.0.2 would source from
            // 127.0.0.2 (Linux picks the destination's own loopback address).
            val socket = factory("127.0.0.1").createSocket("127.0.0.2", server.localPort)
            assertEquals(
                "127.0.0.1",
                accepted.get(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertEquals("127.0.0.1", socket.localAddress.hostAddress)
        } finally {
            acceptor.interrupt()
            server.close()
        }
    }

    @Test
    fun `binding an address no interface holds is refused`() {
        // TEST-NET-3 (RFC 5737) — documentation range, never a local address
        // on a CI runner, so bind() fails with EADDRNOTAVAIL.
        try {
            factory("203.0.113.7").createSocket("127.0.0.1", 22)
            throw AssertionError("expected a bind failure")
        } catch (e: JSchException) {
            assertTrue(
                "expected a bind-failure message, got: ${e.message}",
                e.message?.contains("bind failed") == true,
            )
        }
    }

    @Test
    fun `dial failure surfaces as JSchException, not a hang`() {
        // TEST-NET-3 (RFC 5737) drops on a runner without a route. A failing
        // dial must end within the timeout's budget as a wrapped JSchException
        // — never hang. Which failure wins is host-dependent: a route that
        // silently drops gives the SocketTimeoutException path; a loopback
        // bound source aimed outside its scope is rejected outright (EINVAL)
        // before the timeout can matter. Both are prompt failures.
        val start = System.nanoTime()
        try {
            factory("127.0.0.1", timeoutMs = 500).createSocket("203.0.113.1", 9999)
            throw AssertionError("expected the dial to fail")
        } catch (e: JSchException) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(
                "dial took ${elapsedMs}ms — past the connect timeout budget",
                elapsedMs < 5_000,
            )
        }
    }
}