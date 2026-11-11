package sh.haven.core.glkvm

import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit

/**
 * Exercises the request pipeline against a mock kvmd: auth headers, the GL
 * fork's 403-ForbiddenError relogin (vs a real 401 denial), HID query
 * shapes, wheel pacing, snapshot stale-token retry, login failure, and the
 * TOFU trust decision.
 */
class GlkvmClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GlkvmClient

    /** Recorded requests: path -> queries, in call order (HID + video API only). */
    private val reqs = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = kvmdDispatcher()
        server.start()
        client = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${server.port}"
            user = "admin"
            password = "secret"
        }.build()
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    private fun kvmdDispatcher(
        onSnapshot: (Dispatcher.(Int) -> MockResponse)? = null,
        loginFails: Boolean = false,
    ) = object : Dispatcher() {
        var loginCount = 0
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath ?: return MockResponse().setResponseCode(400)
            if (path == "/api/auth/login") {
                loginCount++
                if (loginFails) return MockResponse().setBody("""{"ok":false,"message":"bad creds"}""")
                return MockResponse().setBody("""{"ok":true,"result":{"token":"tok-t$loginCount"}}""")
            }
            val url = request.requestUrl!!
            val query = url.queryParameterNames.associateWith { url.queryParameter(it) ?: "" }
            synchronized(reqs) { reqs.add(path to query) }
            return when {
                path.startsWith("/api/hid/events/") -> MockResponse().setBody("""{"ok":true}""")
                path == "/streamer/snapshot" -> onSnapshot?.invoke(this, loginCount)
                    ?: MockResponse().setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42.toByte())))
                        .setHeader("Content-Type", "image/jpeg")
                path == "/api/streamer" -> MockResponse().setBody(
                    """{"ok":true,"result":{"streamer":{"hdmi":{"signal":true},
                       "source":{"real_resolution":"2560x1440@60","captured_fps":60.0}}}}"""
                )
                else -> MockResponse().setResponseCode(404).setBody("nope")
            }
        }
    }

    private fun paths() = synchronized(reqs) { reqs.map { it.first } }

    private fun clearRecordings() = synchronized(reqs) { reqs.clear() }

    // -- auth ----------------------------------------------------------------

    @Test
    fun `login posts form and caches the token`() {
        client.videoState()
        val login: RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no login request")
        assertEquals("/api/auth/login", login.path!!.substringBefore('?'))
        val body = login.body.readUtf8()
        assertTrue("body=$body", body.contains("user=admin") && body.contains("passwd=secret"))
        val api: RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no api request")
        assertTrue(api.path!!.contains("auth_token=tok-t1"))
        assertEquals("tok-t1", api.getHeader("token"))

        clearRecordings()
        client.sendKey("KeyA", true)
        assertEquals(listOf("/api/hid/events/send_key"), paths())
    }

    @Test
    fun `bad password surfaces a login failure`() {
        val s2 = MockWebServer()
        s2.dispatcher = kvmdDispatcher(loginFails = true)
        s2.start()
        val c2 = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${s2.port}"
            user = "admin"
            password = "wrong"
        }.build()
        try {
            c2.videoState()
            fail("expected GlkvmException")
        } catch (e: GlkvmException) {
            assertTrue(e.message!!.contains("login failed"))
        } finally {
            c2.close()
            s2.shutdown()
        }
    }

    // -- GL fork quirks ------------------------------------------------------

    @Test
    fun `403 ForbiddenError forces one relogin and retries`() {
        val s2 = MockWebServer()
        var loginCount = 0
        var served403 = false
        s2.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: return MockResponse().setResponseCode(400)
                if (path == "/api/auth/login") {
                    loginCount++
                    return MockResponse().setBody("""{"ok":true,"result":{"token":"tok-r$loginCount"}}""")
                }
                if (path == "/api/streamer") {
                    return if (loginCount >= 2) {
                        MockResponse().setBody(
                            """{"ok":true,"result":{"streamer":{"hdmi":{"signal":true},"source":{}}}}"""
                        )
                    } else {
                        served403 = true
                        MockResponse().setResponseCode(403).setBody("""{"ok":false,"message":"ForbiddenError"}""")
                    }
                }
                return MockResponse().setResponseCode(404)
            }
        }
        s2.start()
        val c2 = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${s2.port}"
            user = "u"
            password = "p"
        }.build()
        try {
            val state = c2.videoState()
            assertTrue(state.signal)
            assertEquals(2, loginCount) // one initial login + one forced relogin
            assertEquals(true, served403)
        } finally {
            c2.close()
            s2.shutdown()
        }
    }

    @Test
    fun `401 denial retries once then surfaces the error`() {
        val s3 = MockWebServer()
        s3.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/auth/login") {
                    MockResponse().setBody("""{"ok":true,"result":{"token":"t"}}""")
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"ok":false,"message":"denied"}""")
                }
        }
        s3.start()
        val c3 = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${s3.port}"
            user = "u"
            password = "p"
        }.build()
        try {
            c3.videoState()
            fail("expected GlkvmException")
        } catch (e: GlkvmException) {
            assertTrue(e.message!!.contains("401"))
        } finally {
            c3.close()
            s3.shutdown()
        }
    }

    // -- HID -----------------------------------------------------------------

    @Test
    fun `key and mouse calls carry the documented query shapes`() {
        client.sendKey("KeyA", true)
        client.sendKey("KeyA", false)
        client.sendMouseButton("left", true)
        client.sendMouseMove(300, 200)
        client.sendMouseClick(300, 400) // move + button = 2 calls

        val calls = synchronized(reqs) { reqs.toList() }
        assertEquals(6, calls.size)
        val (_, keyQ) = calls[0]
        assertEquals("KeyA", keyQ["key"])
        assertEquals("1", keyQ["state"])
        assertEquals("0", calls[1].second["state"])
        assertEquals("left", calls[2].second["button"])

        val (_, moveQ) = calls[3]
        val vx = moveQ["to_x"]!!.toInt()
        val vy = moveQ["to_y"]!!.toInt()
        val (bx, by) = GlkvmGeometry.pxFromV(vx, vy, 2560, 1440)
        assertTrue("x roundtrip $bx", Math.abs(bx - 300) <= 1.0)
        assertTrue("y roundtrip $by", Math.abs(by - 200) <= 0.6)

        assertEquals("/api/hid/events/send_mouse_move", calls[4].first)
        assertEquals("/api/hid/events/send_mouse_button", calls[5].first)
    }

    @Test
    fun `wheel sends one post per notch with device delta`() {
        client.sendWheel(2)   // down×2 → delta_y = -3 each
        client.sendWheel(-1)  // up×1   → delta_y = +3
        val wheel = synchronized(reqs) { reqs.filter { it.first == "/api/hid/events/send_mouse_wheel" } }
        assertEquals(3, wheel.size)
        assertEquals("-3", wheel[0].second["delta_y"])
        assertEquals("-3", wheel[1].second["delta_y"])
        assertEquals("3", wheel[2].second["delta_y"])
        assertEquals("0", wheel[0].second["delta_x"])
    }

    // -- snapshot ------------------------------------------------------------

    @Test
    fun `snapshot returns raw bytes`() {
        val bytes = client.snapshot()
        assertTrue(bytes.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42.toByte())))
    }

    @Test
    fun `snapshot survives a stale-token 403 via relogin`() {
        val s4 = MockWebServer()
        var loginCount = 0
        var snapshotHits = 0
        s4.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: return MockResponse().setResponseCode(400)
                if (path == "/api/auth/login") {
                    loginCount++
                    return MockResponse().setBody("""{"ok":true,"result":{"token":"tok-n$loginCount"}}""")
                }
                if (path == "/streamer/snapshot") {
                    snapshotHits++
                    // kvmd restarted between the login and use: reject every
                    // snapshot fetched with the OLD token (403 ForbiddenError,
                    // the GL quirk). After the forced relogin the same request
                    // starts returning bytes.
                    return if (loginCount < 2) {
                        MockResponse().setResponseCode(403).setBody("""{"ok":false,"message":"ForbiddenError"}""")
                    } else {
                        MockResponse().setBody(Buffer().write(byteArrayOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte())))
                            .setHeader("Content-Type", "image/jpeg")
                    }
                }
                return MockResponse().setResponseCode(404)
            }
        }
        s4.start()
        val c4 = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${s4.port}"
            user = "u"
            password = "p"
        }.build()
        try {
            val bytes = c4.snapshot()
            assertTrue(bytes.contentEquals(byteArrayOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte())))
            // Fetch pattern: initial 403, callText probe 403 (its own ForbiddenError
            // retry fires the forced relogin), callText retry probes again, and the
            // byte refetch succeeds — one forced relogin total.
            assertEquals(2, loginCount)
        } finally {
            c4.close()
            s4.shutdown()
        }
    }

    // -- TLS -----------------------------------------------------------------

    private fun fakeCert(vararg bytes: Byte) = mockk<java.security.cert.X509Certificate> {
        every { encoded } returns bytes
    }

    @Test
    fun `tofu pins on first sight and pins must keep matching`() {
        val seen = mutableListOf<String>()
        val (_, tm) = GlkvmTrust.tofu(null) { seen.add(it) }
        val certA = fakeCert(1, 2, 3)
        tm.checkServerTrusted(arrayOf(certA), "RSA")
        assertEquals(1, seen.size)

        val pinned = seen.first()
        val (_, tm2) = GlkvmTrust.tofu(pinned) { fail("no re-pin expected") }
        tm2.checkServerTrusted(arrayOf(certA), "RSA") // same cert passes silently

        val certB = fakeCert(9, 9, 9)
        val (_, tm3) = GlkvmTrust.tofu(pinned) { fail("no re-pin expected") }
        try {
            tm3.checkServerTrusted(arrayOf(certB), "RSA")
            fail("expected cert mismatch")
        } catch (e: GlkvmCertMismatch) {
            assertTrue(e.message!!.contains("certificate changed"))
        }

        // Two different certs hash differently (the pin is byte-content based).
        val (sf, tm4) = GlkvmTrust.tofu(null) { seen.add(it) }
        tm4.checkServerTrusted(arrayOf(fakeCert(7, 7)), "RSA")
        assertEquals(2, seen.size)
        assertTrue(seen[0] != seen[1])
        // The factory is wired for real TLS use; assert type only (no handshake here).
        assertTrue(sf is javax.net.ssl.SSLSocketFactory)
    }

    @Test
    fun `empty chain is rejected`() {
        val (_, tm) = GlkvmTrust.tofu(null) { fail() }
        try {
            tm.checkServerTrusted(emptyArray(), "RSA")
            fail("expected CertificateException")
        } catch (e: CertificateException) {
            assertTrue(e.message!!.contains("empty"))
        }
    }

    @Test(timeout = 5_000)
    fun `plainSocketFactory with no-op setSoTimeout and private hostname never pools idle sockets`() {
        val dialledHosts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tunnelLikeFactory = object : javax.net.SocketFactory() {
            override fun createSocket(): java.net.Socket = object : java.net.Socket() {
                private var delegate: java.net.Socket? = null
                override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                    val addr = endpoint as java.net.InetSocketAddress
                    dialledHosts.add(addr.hostString)
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress("127.0.0.1", server.port), timeout)
                    delegate = s
                }
                override fun getInputStream() = delegate!!.getInputStream()
                override fun getOutputStream() = delegate!!.getOutputStream()
                override fun isConnected() = delegate?.isConnected == true
                override fun isClosed() = delegate?.isClosed ?: false
                override fun close() { delegate?.close() }
                // Exact contract of TunneledSocket / DeferredTunneledSocket:
                // userspace tunnels ignore kernel SO_TIMEOUT, which causes
                // OkHttp's RealConnection.isHealthy 1 ms probe after >=10 s
                // idle to block forever if tunnel sockets are kept in ConnectionPool.
                override fun setSoTimeout(timeout: Int) { /* no-op */ }
                override fun getSoTimeout(): Int = 0
            }
            override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, lh: java.net.InetAddress?, lp: Int) =
                throw UnsupportedOperationException()
            override fun createSocket(host: java.net.InetAddress?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(a: java.net.InetAddress?, port: Int, la: java.net.InetAddress?, lp: Int) =
                throw UnsupportedOperationException()
        }

        val tunneledClient = GlkvmClient.Builder().apply {
            host = "glkvm-private.tunnel.invalid"
            port = 443
            baseUrlOverride = "http://glkvm-private.tunnel.invalid:443"
            user = "admin"
            password = "secret\n"
            plainSocketFactory = tunnelLikeFactory
        }.build()
        try {
            val state = tunneledClient.videoState()
            assertTrue(state.signal)
            val loginReq = server.takeRequest(2, TimeUnit.SECONDS)!!
            // Trailing newline from clipboard paste must be stripped before form-encoding
            // so kvmd's valid_passwd validator does not reject with HTTP 400 ValidatorError.
            assertFalse("passwd must strip trailing newline: ${loginReq.body.clone().readUtf8()}",
                loginReq.body.readUtf8().contains("%0A"))
            val snap = tunneledClient.snapshot()
            assertEquals(4, snap.size)
            assertTrue(
                "Tunnel factory must receive original private hostname",
                dialledHosts.all { it == "glkvm-private.tunnel.invalid" },
            )
            assertEquals(
                "No-op setSoTimeout tunnel sockets must not sit idle in OkHttp ConnectionPool (login + videoState + snapshot = 3 dials)",
                3,
                dialledHosts.size,
            )
        } finally {
            tunneledClient.close()
        }
    }

    @Test
    fun `edidProd reads current EDID and posts multipart edid until HDMI signal returns`() {
        val s2 = MockWebServer()
        var edidPosted = false
        var signalPollsAfterPost = 0
        s2.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: return MockResponse().setResponseCode(400)
                return when (path) {
                    "/api/auth/login" -> MockResponse().setBody("""{"ok":true,"result":{"token":"tok-edid"}}""")
                    "/api/upgrade/get_edid" -> MockResponse().setBody("""{"ok":true,"result":{"edid":"E1920x1080"}}""")
                    "/api/upgrade/edid" -> {
                        val body = request.body.readUtf8()
                        assertTrue("expected multipart edid field, got: $body", body.contains("E1920x1080"))
                        edidPosted = true
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    "/api/streamer" -> {
                        val sig = if (edidPosted) {
                            signalPollsAfterPost++
                            signalPollsAfterPost >= 2
                        } else {
                            false
                        }
                        MockResponse().setBody(
                            """{"ok":true,"result":{"streamer":{"hdmi":{"signal":$sig},"source":{"real_resolution":"1920x1080@60"}}}}"""
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s2.start()
        val c2 = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${s2.port}"
            user = "admin"
            password = "secret"
        }.build()
        try {
            assertTrue(c2.edidProd(timeoutMs = 2_000L, pollIntervalMs = 20L))
            assertTrue(edidPosted)
            assertEquals(2, signalPollsAfterPost)
        } finally {
            c2.close()
            s2.shutdown()
        }
    }
}