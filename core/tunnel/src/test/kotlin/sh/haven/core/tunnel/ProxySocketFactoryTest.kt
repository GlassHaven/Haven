package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Unit tests verify the factory's *dispatch* shape — it picks the right
 * java.net.Proxy.Type and surfaces an IOException for unknown types.
 *
 * Actual SOCKS / HTTP handshake behaviour is JDK code we don't unit-test
 * here (would need a fake proxy server). Coverage at the
 * Socket(Proxy)-handshake layer is the JDK's responsibility.
 */
class ProxySocketFactoryTest {

    @Test
    fun unknownProxyTypeThrowsOnCreateSocket() {
        val factory = ProxySocketFactory("WAT", "127.0.0.1", 1080)
        try {
            factory.createSocket("example.com", 80)
            fail("expected IOException for unknown proxy type")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun socks5IsAcceptedAndReturnsSocketAttempt() {
        // Point at a port that won't accept — connect() will fail, but we
        // verify no IOException-from-dispatch and no NPE; the factory got
        // far enough to attempt the proxied connect.
        val factory = ProxySocketFactory("SOCKS5", "127.0.0.1", 1, connectTimeoutMs = 100)
        try {
            factory.createSocket("example.com", 80)
            // If it somehow returned a Socket, fine — we just verify it's
            // an actual Socket.
        } catch (e: IOException) {
            // Connect-refused / timeout is the expected outcome here.
            // We DO NOT want "Unsupported proxy type" — that'd indicate
            // dispatch broke.
            assertNotNull(e.message)
            org.junit.Assert.assertFalse(
                "must NOT report unsupported type for SOCKS5",
                e.message?.contains("Unsupported proxy type") == true,
            )
        }
    }

    @Test
    fun httpIsAcceptedAndReturnsSocketAttempt() {
        val factory = ProxySocketFactory("HTTP", "127.0.0.1", 1, connectTimeoutMs = 100)
        try {
            factory.createSocket("example.com", 80)
        } catch (e: IOException) {
            org.junit.Assert.assertFalse(
                "must NOT report unsupported type for HTTP",
                e.message?.contains("Unsupported proxy type") == true,
            )
        }
    }

    @Test
    fun socks4IsAcceptedSameAsSocks5() {
        val factory = ProxySocketFactory("SOCKS4", "127.0.0.1", 1, connectTimeoutMs = 100)
        try {
            factory.createSocket("example.com", 80)
        } catch (e: IOException) {
            org.junit.Assert.assertFalse(
                "must NOT report unsupported type for SOCKS4",
                e.message?.contains("Unsupported proxy type") == true,
            )
        }
    }

    // #227: authenticated handshakes, verified against a loopback stub proxy.

    @Test
    fun socks5SendsRfc1929CredentialsAndDomainConnect() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val sentUser = AtomicReference<String>()
        val sentPass = AtomicReference<String>()
        val sentHost = AtomicReference<String>()
        val sentPort = AtomicReference<Int>()
        val done = CountDownLatch(1)

        val srv = thread(isDaemon = true) {
            server.accept().use { s ->
                val ins = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                // Greeting
                assertEquals(0x05, ins.read())
                val n = ins.read()
                val methods = IntArray(n) { ins.read() }
                assertTrue("client must offer user/pass auth", methods.contains(0x02))
                out.write(byteArrayOf(0x05, 0x02)); out.flush() // select user/pass
                // Auth (RFC 1929)
                assertEquals(0x01, ins.read())
                val ulen = ins.read(); val u = ByteArray(ulen).also { ins.readFully(it) }
                val plen = ins.read(); val p = ByteArray(plen).also { ins.readFully(it) }
                sentUser.set(String(u, Charsets.ISO_8859_1))
                sentPass.set(String(p, Charsets.ISO_8859_1))
                out.write(byteArrayOf(0x01, 0x00)); out.flush() // auth ok
                // CONNECT
                assertEquals(0x05, ins.read())
                assertEquals(0x01, ins.read()) // CMD CONNECT
                ins.read()                     // RSV
                assertEquals(0x03, ins.read()) // ATYP domain (remote DNS)
                val dlen = ins.read(); val d = ByteArray(dlen).also { ins.readFully(it) }
                val port = (ins.read() shl 8) or ins.read()
                sentHost.set(String(d, Charsets.ISO_8859_1))
                sentPort.set(port)
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
                done.countDown()
            }
        }

        var client: Socket? = null
        try {
            val factory = ProxySocketFactory(
                proxyType = "SOCKS5",
                proxyHost = "127.0.0.1",
                proxyPort = server.localPort,
                connectTimeoutMs = 5_000,
                proxyUser = "alice",
                proxyPassword = "s3cr3t",
            )
            client = factory.createSocket("example.onion", 9050)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("alice", sentUser.get())
            assertEquals("s3cr3t", sentPass.get())
            assertEquals("example.onion", sentHost.get())
            assertEquals(9050, sentPort.get())
            assertTrue(client.isConnected)
        } finally {
            client?.close(); server.close(); srv.join(2_000)
        }
    }

    @Test
    fun socks5HandlesServerChoosingNoAuth() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val sentHost = AtomicReference<String>()
        val done = CountDownLatch(1)
        val srv = thread(isDaemon = true) {
            server.accept().use { s ->
                val ins = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                assertEquals(0x05, ins.read())
                val n = ins.read(); repeat(n) { ins.read() }
                out.write(byteArrayOf(0x05, 0x00)); out.flush() // pick no-auth
                assertEquals(0x05, ins.read()); ins.read(); ins.read()
                assertEquals(0x03, ins.read())
                val dlen = ins.read(); val d = ByteArray(dlen).also { ins.readFully(it) }
                ins.read(); ins.read() // port
                sentHost.set(String(d, Charsets.ISO_8859_1))
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
                done.countDown()
            }
        }
        var client: Socket? = null
        try {
            client = ProxySocketFactory("SOCKS5", "127.0.0.1", server.localPort, 5_000, "alice", "pw")
                .createSocket("host.example", 22)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("host.example", sentHost.get())
            assertTrue(client.isConnected)
        } finally {
            client?.close(); server.close(); srv.join(2_000)
        }
    }

    @Test
    fun socks5EncodesNonAsciiCredentialsByByteLength() {
        // #227 regression: the RFC 1929 length octets must equal the UTF-8 BYTE
        // length of the credentials, not the character count (JSch's ProxySOCKS5
        // used the char count, corrupting non-ASCII creds → "server status = 01").
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val user = "用户"        // 2 chars, 6 UTF-8 bytes
        val pass = "pä55wörd"    // 8 chars, 10 UTF-8 bytes (ä, ö are 2 bytes each)
        val gotUser = AtomicReference<String>()
        val gotPass = AtomicReference<String>()
        val gotUlen = AtomicReference<Int>()
        val gotPlen = AtomicReference<Int>()
        val done = CountDownLatch(1)
        val srv = thread(isDaemon = true) {
            server.accept().use { s ->
                val ins = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                assertEquals(0x05, ins.read()); val n = ins.read(); repeat(n) { ins.read() }
                out.write(byteArrayOf(0x05, 0x02)); out.flush()
                assertEquals(0x01, ins.read())
                val ulen = ins.read(); val u = ByteArray(ulen).also { ins.readFully(it) }
                val plen = ins.read(); val p = ByteArray(plen).also { ins.readFully(it) }
                gotUlen.set(ulen); gotPlen.set(plen)
                gotUser.set(String(u, Charsets.UTF_8)); gotPass.set(String(p, Charsets.UTF_8))
                out.write(byteArrayOf(0x01, 0x00)); out.flush()
                assertEquals(0x05, ins.read()); ins.read(); ins.read()
                assertEquals(0x03, ins.read())
                val dl = ins.read(); ByteArray(dl).also { ins.readFully(it) }; ins.read(); ins.read()
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
                done.countDown()
            }
        }
        var client: Socket? = null
        try {
            client = ProxySocketFactory("SOCKS5", "127.0.0.1", server.localPort, 5_000, user, pass)
                .createSocket("h", 22)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("ulen must be the UTF-8 byte length, not the char count", 6, gotUlen.get())
            assertEquals("plen must be the UTF-8 byte length, not the char count", 10, gotPlen.get())
            assertEquals(user, gotUser.get())
            assertEquals(pass, gotPass.get())
            assertTrue(client.isConnected)
        } finally {
            client?.close(); server.close(); srv.join(2_000)
        }
    }

    @Test
    fun httpConnectWithoutCredentialsUsesConnectHandshake() {
        // #777: java.net.Socket(Proxy(Proxy.Type.HTTP, ...)) throws
        // IllegalArgumentException("Invalid Proxy") on JVM/Android because raw
        // Socket only accepts SOCKS and DIRECT. Unauthenticated HTTP proxies
        // must still perform the HTTP CONNECT handshake via AuthenticatedProxy.
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val requestLines = AtomicReference<List<String>>()
        val done = CountDownLatch(1)
        val srv = thread(isDaemon = true) {
            server.accept().use { s ->
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val out = s.getOutputStream()
                val lines = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    lines.add(line)
                }
                requestLines.set(lines)
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                done.countDown()
            }
        }
        var client: Socket? = null
        try {
            client = ProxySocketFactory("HTTP", "127.0.0.1", server.localPort, 5_000)
                .createSocket("example.com", 443)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val lines = requestLines.get()
            assertTrue("CONNECT line present", lines.any { it == "CONNECT example.com:443 HTTP/1.1" })
            assertTrue(
                "Proxy-Connection header present (proves manual HTTP CONNECT path, not Android-broken Socket(Proxy.Type.HTTP))",
                lines.any { it.equals("Proxy-Connection: keep-alive", ignoreCase = true) },
            )
            org.junit.Assert.assertFalse(
                "Proxy-Authorization must be absent when no credentials set",
                lines.any { it.startsWith("Proxy-Authorization:", ignoreCase = true) },
            )
            assertTrue(client.isConnected)
        } finally {
            client?.close(); server.close(); srv.join(2_000)
        }
    }

    @Test
    fun httpConnectSendsBasicProxyAuthorization() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val requestLines = AtomicReference<List<String>>()
        val done = CountDownLatch(1)
        val srv = thread(isDaemon = true) {
            server.accept().use { s ->
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val out = s.getOutputStream()
                val lines = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    lines.add(line)
                }
                requestLines.set(lines)
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                done.countDown()
            }
        }
        var client: Socket? = null
        try {
            client = ProxySocketFactory("HTTP", "127.0.0.1", server.localPort, 5_000, "alice", "pw")
                .createSocket("example.com", 443)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val lines = requestLines.get()
            assertTrue("CONNECT line present", lines.any { it == "CONNECT example.com:443 HTTP/1.1" })
            val expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("alice:pw".toByteArray(Charsets.ISO_8859_1))
            assertTrue(
                "Proxy-Authorization header present and correct",
                lines.any { it == "Proxy-Authorization: $expected" },
            )
            assertTrue(client.isConnected)
        } finally {
            client?.close(); server.close(); srv.join(2_000)
        }
    }
}
