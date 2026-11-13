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

    // -- Stock upstream PiKVM (kvmd) compatibility --------------------------

    @Test
    fun `pikvm Set-Cookie auth, source online streamer shape, and api streamer snapshot fallback`() {
        val pikvm = MockWebServer()
        var loginCount = 0
        var legacySnapshotProbes = 0
        var apiSnapshotHits = 0
        val seenCookies = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val seenQueries = java.util.Collections.synchronizedList(mutableListOf<String?>())
        pikvm.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                val path = url.encodedPath
                if (path == "/api/auth/login") {
                    loginCount++
                    return MockResponse()
                        .setHeader("Set-Cookie", "auth_token=pikvm-cookie-$loginCount; Path=/; HttpOnly")
                        .setBody("""{"ok":true,"result":{}}""")
                }
                seenCookies.add(request.getHeader("Cookie"))
                seenQueries.add(url.encodedQuery)
                if (request.getHeader("Cookie") != "auth_token=pikvm-cookie-$loginCount") {
                    return MockResponse().setResponseCode(401).setBody("""{"ok":false,"result":{"error":"UnauthorizedError"}}""")
                }
                return when (path) {
                    "/api/streamer" -> MockResponse().setBody(
                        """{"ok":true,"result":{"source":{"online":true,"resolution":{"width":1920,"height":1080},"captured_fps":59.5},"stream":{"clients":1}}}""",
                    )
                    "/streamer/snapshot" -> {
                        legacySnapshotProbes++
                        MockResponse().setResponseCode(404).setBody("not found")
                    }
                    "/api/streamer/snapshot" -> {
                        apiSnapshotHits++
                        MockResponse()
                            .setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22)))
                            .setHeader("Content-Type", "image/jpeg")
                    }
                    "/api/hid/events/send_key" -> MockResponse().setBody("""{"ok":true,"result":{}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        pikvm.start()
        val pc = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${pikvm.port}"
            user = "admin"
            password = "secret"
        }.build()
        try {
            val state = pc.videoState()
            assertTrue(state.signal)
            assertEquals("1920x1080", state.resolution)
            assertEquals(59.5, state.fps!!, 0.01)

            val snap1 = pc.snapshot()
            assertTrue(snap1.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22)))
            val snap2 = pc.snapshot()
            assertTrue(snap2.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22)))
            // /streamer/snapshot 404s once, then client remembers /api/streamer/snapshot.
            assertEquals(1, legacySnapshotProbes)
            assertEquals(2, apiSnapshotHits)

            pc.sendKey("Enter", true)
            assertEquals(1, loginCount)
            assertTrue(seenCookies.all { it == "auth_token=pikvm-cookie-1" })
            // Cookie-authenticated PiKVM requests don't leak token in query string.
            assertTrue(seenQueries.none { it?.contains("auth_token=") == true })
        } finally {
            pc.close()
            pikvm.shutdown()
        }
    }

    @Test
    fun `pikvm expired cookie 401 on snapshot forces relogin and retries`() {
        val pikvm = MockWebServer()
        var loginCount = 0
        pikvm.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: return MockResponse().setResponseCode(400)
                if (path == "/api/auth/login") {
                    loginCount++
                    return MockResponse()
                        .setHeader("Set-Cookie", "auth_token=ck-$loginCount; Path=/")
                        .setBody("""{"ok":true,"result":{}}""")
                }
                if (path == "/streamer/snapshot") {
                    return if (loginCount < 2 || request.getHeader("Cookie") != "auth_token=ck-2") {
                        MockResponse().setResponseCode(401).setBody("""{"ok":false,"result":{"error":"UnauthorizedError"}}""")
                    } else {
                        MockResponse()
                            .setBody(Buffer().write(byteArrayOf(9, 8, 7, 6)))
                            .setHeader("Content-Type", "image/jpeg")
                    }
                }
                return MockResponse().setResponseCode(404)
            }
        }
        pikvm.start()
        val pc = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${pikvm.port}"
            user = "admin"
            password = "secret"
        }.build()
        try {
            val bytes = pc.snapshot()
            assertTrue(bytes.contentEquals(byteArrayOf(9, 8, 7, 6)))
            assertEquals(2, loginCount)
        } finally {
            pc.close()
            pikvm.shutdown()
        }
    }

    @Test
    fun `pikvm with auth disabled succeeds via auth check fallback`() {
        val pikvm = MockWebServer()
        pikvm.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl?.encodedPath) {
                    "/api/auth/login" -> MockResponse().setResponseCode(403)
                        .setBody("""{"ok":false,"result":{"error":"ForbiddenError","error_msg":"Auth is disabled"}}""")
                    "/api/auth/check" -> MockResponse().setBody("""{"ok":true,"result":{}}""")
                    "/api/streamer" -> MockResponse().setBody(
                        """{"ok":true,"result":{"streamer":{"source":{"online":false,"resolution":{"width":0,"height":0}}}}}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        pikvm.start()
        val pc = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${pikvm.port}"
            user = "admin"
            password = ""
        }.build()
        try {
            val state = pc.videoState()
            assertFalse(state.signal)
            assertEquals("", state.resolution)
        } finally {
            pc.close()
            pikvm.shutdown()
        }
    }

    // -- Issue #845: /api/ws?stream=1 stream wake & cold-start recovery ------

    @Test(timeout = 10_000)
    fun `ensureStreamSession opens authenticated ws stream=1, sends ping, retries cold 503, and closes cleanly`() {
        val rm1 = MockWebServer()
        val activeWs = java.util.concurrent.atomic.AtomicInteger(0)
        val coldSnapshotAttemptsAfterWs = java.util.concurrent.atomic.AtomicInteger(0)
        val wsMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val closeLatch = java.util.concurrent.CountDownLatch(1)
        val wsUpgradeQuery = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val wsUpgradeTokenHeader = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val wsCloseCode = java.util.concurrent.atomic.AtomicInteger(-1)

        rm1.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                return when (url.encodedPath) {
                    "/api/auth/login" -> MockResponse().setBody("""{"ok":true,"result":{"token":"tok-rm1"}}""")
                    "/api/ws" -> {
                        wsUpgradeQuery.set(url.encodedQuery)
                        wsUpgradeTokenHeader.set(request.getHeader("token"))
                        if (url.queryParameter("stream") != "1" || url.queryParameter("auth_token") != "tok-rm1") {
                            MockResponse().setResponseCode(403).setBody("""{"ok":false,"message":"ForbiddenError"}""")
                        } else {
                            MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                                    activeWs.incrementAndGet()
                                    webSocket.send("""{"event_type":"loop","event":{"version":{"major":4,"minor":82}}}""")
                                }

                                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                                    wsMessages.add(text)
                                    if (text.contains("\"ping\"")) {
                                        webSocket.send("""{"event_type":"pong","event":{}}""")
                                    }
                                }

                                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                                    wsCloseCode.set(code)
                                    activeWs.decrementAndGet()
                                    webSocket.close(code, reason)
                                    closeLatch.countDown()
                                }
                            })
                        }
                    }
                    "/streamer/snapshot" -> {
                        if (activeWs.get() <= 0) {
                            // Comet RM1 with streamer.forever=false: hangs/unavailable without /api/ws?stream=1.
                            MockResponse().setResponseCode(503).setBody("""{"ok":false,"result":{"error":"UnavailableError"}}""")
                        } else if (coldSnapshotAttemptsAfterWs.incrementAndGet() <= 2) {
                            // RV1126 ustreamer still initializing capture for first two polls after WS open.
                            MockResponse().setResponseCode(503).setBody("""{"ok":false,"result":{"error":"UnavailableError"}}""")
                        } else {
                            MockResponse()
                                .setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAA.toByte(), 0x55.toByte())))
                                .setHeader("Content-Type", "image/jpeg")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        rm1.start()
        val c = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${rm1.port}"
            user = "admin"
            password = "secret"
            streamWakeRetryDelayMs = 15L
        }.build()
        try {
            assertTrue("expected /api/ws?stream=1 to open", c.ensureStreamSession())
            assertEquals("tok-rm1", wsUpgradeTokenHeader.get())
            assertTrue("ws query=${wsUpgradeQuery.get()}", wsUpgradeQuery.get()!!.contains("stream=1"))
            assertTrue("ws query=${wsUpgradeQuery.get()}", wsUpgradeQuery.get()!!.contains("auth_token=tok-rm1"))

            val jpeg = c.snapshot()
            assertTrue(jpeg.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAA.toByte(), 0x55.toByte())))
            assertEquals(3, coldSnapshotAttemptsAfterWs.get())
            assertTrue(
                "expected kvmd sliding-auth ping message over ws, got $wsMessages",
                wsMessages.any { it.contains("\"event_type\":\"ping\"") },
            )

            c.closeStreamSession()
            assertTrue("expected clean WebSocket close handshake", closeLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1000, wsCloseCode.get())
            assertEquals(0, activeWs.get())
        } finally {
            c.close()
            rm1.shutdown()
        }
    }

    @Test(timeout = 10_000)
    fun `snapshot auto-opens stream ws and exits stuck gl_webrtc adaptive mode when snapshot stalls`() {
        val rm1 = MockWebServer()
        val wsOpen = java.util.concurrent.atomic.AtomicBoolean(false)
        val adaptiveMode = java.util.concurrent.atomic.AtomicBoolean(true)
        val setParamsQueries = java.util.Collections.synchronizedList(mutableListOf<String?>())

        rm1.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                return when (url.encodedPath) {
                    "/api/auth/login" -> MockResponse().setBody("""{"ok":true,"result":{"token":"tok-adapt"}}""")
                    "/api/ws" -> MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                            wsOpen.set(true)
                        }
                        override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                            wsOpen.set(false)
                            webSocket.close(code, reason)
                        }
                    })
                    "/api/streamer/set_params" -> {
                        setParamsQueries.add(url.encodedQuery)
                        if (url.queryParameter("gl_webrtc") == "0") {
                            adaptiveMode.set(false)
                        }
                        MockResponse().setBody("""{"ok":true,"result":{}}""")
                    }
                    "/streamer/snapshot" -> {
                        if (!wsOpen.get() || adaptiveMode.get()) {
                            // Simulate ustreamer stalled until /api/ws?stream=1 is open AND adaptive mode is exited.
                            MockResponse()
                                .setHeadersDelay(350, TimeUnit.MILLISECONDS)
                                .setResponseCode(504)
                        } else {
                            MockResponse()
                                .setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33, 0x44)))
                                .setHeader("Content-Type", "image/jpeg")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        rm1.start()
        val c = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${rm1.port}"
            user = "admin"
            password = "secret"
            snapshotReadTimeoutMs = 120L
            streamWakeRetryDelayMs = 15L
        }.build()
        try {
            // Even without an explicit ensureStreamSession() call, snapshot() self-wakes /api/ws?stream=1
            // and clears gl_webrtc adaptive mode on GLToken devices when /streamer/snapshot stalls.
            val jpeg = c.snapshot()
            assertTrue(jpeg.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33, 0x44)))
            assertTrue(wsOpen.get())
            assertFalse(adaptiveMode.get())
            assertTrue("setParams=$setParamsQueries", setParamsQueries.any { it?.contains("gl_webrtc=0") == true })
        } finally {
            c.close()
            rm1.shutdown()
        }
    }

    @Test(timeout = 10_000)
    fun `pikvm ensureStreamSession uses Cookie auth on ws without calling gl_webrtc`() {
        val pikvm = MockWebServer()
        val wsCookie = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val wsQuery = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val setParamsCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val snapCount = java.util.concurrent.atomic.AtomicInteger(0)

        pikvm.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                return when (url.encodedPath) {
                    "/api/auth/login" -> MockResponse()
                        .setHeader("Set-Cookie", "auth_token=pikvm-ws-cookie; Path=/; HttpOnly")
                        .setBody("""{"ok":true,"result":{}}""")
                    "/api/ws" -> {
                        wsCookie.set(request.getHeader("Cookie"))
                        wsQuery.set(url.encodedQuery)
                        MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                                webSocket.close(code, reason)
                            }
                        })
                    }
                    "/api/streamer/set_params" -> {
                        setParamsCalled.set(true)
                        MockResponse().setResponseCode(400)
                    }
                    "/streamer/snapshot" -> {
                        if (snapCount.incrementAndGet() == 1) {
                            MockResponse().setResponseCode(502).setBody("Bad Gateway")
                        } else {
                            MockResponse()
                                .setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x77, 0x11)))
                                .setHeader("Content-Type", "image/jpeg")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        pikvm.start()
        val pc = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${pikvm.port}"
            user = "admin"
            password = "secret"
            streamWakeRetryDelayMs = 15L
        }.build()
        try {
            assertTrue(pc.ensureStreamSession())
            assertEquals("auth_token=pikvm-ws-cookie", wsCookie.get())
            assertEquals("stream=1", wsQuery.get())
            val jpeg = pc.snapshot()
            assertTrue(jpeg.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x77, 0x11)))
            assertFalse("stock PiKVM must not receive GL-only gl_webrtc param", setParamsCalled.get())
        } finally {
            pc.close()
            pikvm.shutdown()
        }
    }
}