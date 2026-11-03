package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * The interesting half of these tests drives the *real* JSch against a real
 * socket, because the whole rewrite rests on a claim about which string JSch
 * emits for which failure. Asserting that against a hand-built exception would
 * only prove the rewrite matches my own assumption — which is exactly the
 * mistake #557 was diagnosed with twice.
 */
class SshConnectDiagnosisTest {

    /**
     * A listening socket that never accepts and never writes.
     *
     * The kernel completes the TCP handshake from the listen backlog on its
     * own, so the client sees an established connection and then silence —
     * which is the whole point, and needs no accept loop to arrange. An
     * earlier version ran one and held its lock across `accept()`, which
     * deadlocked teardown; the tests then hung until the timeout killed them.
     */
    private class SilentPeer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        override fun close() { runCatching { server.close() } }
    }

    private fun connectExpectingFailure(port: Int, timeoutMs: Int): JSchException {
        val session = JSch().getSession("u", LOOPBACK, port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setPassword("x")
        return try {
            session.connect(timeoutMs)
            throw AssertionError("expected the connect to fail")
        } catch (e: JSchException) {
            e
        } finally {
            runCatching { session.disconnect() }
        }
    }

    @Test(timeout = LIVE_TEST_TIMEOUT_MS)
    fun `a peer that accepts TCP then stays silent is reported as not-an-SSH-server`() {
        SilentPeer().use { peer ->
            val raw = connectExpectingFailure(peer.port, TIMEOUT_MS)

            // The premise, asserted rather than assumed: this is the exact
            // failure #557 reported.
            assertTrue(
                "expected a read timeout, got: ${raw.message}",
                raw.message.orEmpty().contains("Read timed out"),
            )

            val out = SshConnectDiagnosis.rewrite(
                raw,
                host = "ssh.example.com",
                address = LOOPBACK,
                port = peer.port,
                timeoutMs = TIMEOUT_MS,
                serverVersion = null,
            )
            val message = out.message.orEmpty()
            assertTrue(message, message.contains("accepted the TCP connection then sent nothing"))
            assertTrue(message, message.contains("it is not an SSH server"))
            // The address actually dialled is the fact the reporter never had.
            assertTrue(message, message.contains("ssh.example.com [$LOOPBACK]:${peer.port}"))
            assertTrue(message, message.contains("1.5s"))
        }
    }

    @Test(timeout = LIVE_TEST_TIMEOUT_MS)
    fun `rewriting keeps the cause chain that host rediscovery walks`() {
        SilentPeer().use { peer ->
            val raw = connectExpectingFailure(peer.port, TIMEOUT_MS)
            val out = SshConnectDiagnosis.rewrite(
                raw, "ssh.example.com", LOOPBACK, peer.port, TIMEOUT_MS, serverVersion = null,
            )
            assertSame(raw, out.cause)
            assertTrue(out.isSshNetworkError())
        }
    }

    @Test
    fun `a connect-phase timeout is reported as no TCP connection at all`() {
        // JSch's Util.createSocket throws away the SocketTimeoutException and
        // substitutes this fixed string; reproducing that shape is the only way
        // to exercise it without an unroutable address the CI sandbox lacks.
        val raw = JSchException(
            SshConnectDiagnosis.CONNECT_TIMEOUT_MARKER,
            SocketTimeoutException("Connect timed out"),
        )

        val message = SshConnectDiagnosis.rewrite(
            raw, "ssh.example.com", "203.0.113.5", 22, 10_000, serverVersion = null,
        ).message.orEmpty()

        assertTrue(message, message.startsWith("No TCP connection to ssh.example.com [203.0.113.5]:22"))
        assertTrue(message, message.contains("10s"))
    }

    @Test
    fun `a peer that sent its banner then stalled is reported against key exchange`() {
        val raw = JSchException("Session.connect: java.net.SocketTimeoutException: Read timed out",
            SocketTimeoutException("Read timed out"))

        val message = SshConnectDiagnosis.rewrite(
            raw, "ssh.example.com", "203.0.113.5", 22, 10_000,
            serverVersion = "SSH-2.0-OpenSSH_9.6",
        ).message.orEmpty()

        assertTrue(message, message.contains("SSH-2.0-OpenSSH_9.6"))
        assertTrue(message, message.contains("during key exchange"))
    }

    @Test(timeout = LIVE_TEST_TIMEOUT_MS)
    fun `connection refused is left alone — it already names the address and reason`() {
        // A port nothing is listening on.
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { it.close(); it.localPort }
        val raw = connectExpectingFailure(dead, TIMEOUT_MS)
        assertTrue(raw.message, raw.message.orEmpty().contains("Connection refused"))

        val out = SshConnectDiagnosis.rewrite(
            raw, "ssh.example.com", LOOPBACK, dead, TIMEOUT_MS, serverVersion = null,
        )
        assertSame(raw, out)
    }

    @Test
    fun `an auth failure is not a network diagnosis and is passed through`() {
        val raw = JSchException("Auth fail")
        assertSame(raw, SshConnectDiagnosis.rewrite(raw, "h", "1.2.3.4", 22, 10_000, null))
    }

    @Test
    fun `an unresolved name collapses the duplicate host and address`() {
        val raw = JSchException(
            SshConnectDiagnosis.CONNECT_TIMEOUT_MARKER,
            SocketTimeoutException("Connect timed out"),
        )
        // proxied connects hand the hostname through unresolved as the address
        val message = SshConnectDiagnosis.rewrite(
            raw, "ssh.example.com", "ssh.example.com", 22, 10_000, null,
        ).message.orEmpty()
        assertEquals(true, message.contains("ssh.example.com:22"))
        assertTrue(message, !message.contains("["))
    }

    @Test
    fun `a proxied connect closed before any SSH banner names the proxy EOF`() {
        val raw = JSchException(SshConnectDiagnosis.FOREIGN_HOST_CLOSED_MARKER)
        val rewritten = SshConnectDiagnosis.rewrite(
            raw,
            host = "192.168.1.10",
            address = "192.168.1.10",
            port = 22,
            timeoutMs = 10_000,
            serverVersion = null,
            viaProxy = true,
        )
        val message = rewritten.message.orEmpty()
        assertTrue(message, message.contains("Proxy closed the connection to 192.168.1.10:22"))
        assertTrue(message, message.contains(SshConnectDiagnosis.FOREIGN_HOST_CLOSED_MARKER))
        assertSame(raw, rewritten.cause)
        assertTrue(rewritten.isSshNetworkError())

        // Direct connections (viaProxy = false) keep the original JSch exception untouched.
        val direct = SshConnectDiagnosis.rewrite(
            raw,
            host = "192.168.1.10",
            address = "192.168.1.10",
            port = 22,
            timeoutMs = 10_000,
            serverVersion = null,
            viaProxy = false,
        )
        assertSame(raw, direct)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TIMEOUT_MS = 1_500

        /** A wedged socket fixture must fail the test, not hang the task. */
        const val LIVE_TEST_TIMEOUT_MS = 30_000L
    }
}
