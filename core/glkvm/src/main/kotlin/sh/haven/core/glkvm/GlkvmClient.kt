package sh.haven.core.glkvm

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.sign

/** Thrown on API failures after token retry has been exhausted. */
open class GlkvmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Raised when the pinned TLS certificate does not match the server's. */
class GlkvmCertMismatch(message: String) : GlkvmException(message)

/**
 * Driver for GL.iNet GLKVM and upstream PiKVM (`kvmd`) web APIs over HTTPS.
 * Protocol verified against GLKVM kvmd 4.82 (RM10) and stock PiKVM `kvmd`:
 *
 *  - login is a form POST (`user` + `passwd`) to `/api/auth/login`:
 *      * GLKVM returns `{"ok":true,"result":{"token":"..."}}` and expects
 *        both `?auth_token=` and a `token` header on subsequent calls;
 *      * stock PiKVM returns `{"ok":true,"result":{}}` with
 *        `Set-Cookie: auth_token=...` and expects `Cookie: auth_token=...`;
 *      * PiKVM instances with `auth.enabled: false` verify via `/api/auth/check`
 *        and run without session headers;
 *  - a kvmd restart invalidates the cached session: stock PiKVM answers `401`,
 *    while GL firmware answers a format-valid unknown token with **403
 *    ForbiddenError**, so either triggers one forced relogin + retry;
 *  - TLS certs are self-signed on stock devices — callers inject the trust
 *    decision ([GlkvmTrust.tofu]); hostname verification is deliberately
 *    pin-only.
 *
 * All coordinates are TARGET-SCREEN pixels; the caller tracks the current
 * frame size (from decoded snapshots) and sets [frameWidth]/[frameHeight].
 * No image decoding lives here — `snapshot()` returns raw JPEG bytes.
 */
class GlkvmClient private constructor(
    private val http: OkHttpClient,
    private val snapshotHttp: OkHttpClient,
    private val wsHttp: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val user: String,
    private val password: String,
    private val streamWsOpenTimeoutMs: Long,
    private val streamWakeMaxRetries: Int,
    private val streamWakeRetryDelayMs: Long,
) : AutoCloseable {

    private sealed interface AuthSession {
        data class GlToken(val token: String) : AuthSession
        data class CookieToken(val cookieValue: String) : AuthSession
        data object NoAuth : AuthSession
    }

    /** Current frame size in px — the mouse-verb calibration input. */
    @Volatile var frameWidth: Int = 2560
    @Volatile var frameHeight: Int = 1440

    /**
     * Active non-pillarboxed horizontal bounds `[activeLeftPx, activeLeftPx + activeWidthPx]`
     * within [frameWidth]. Defaults to full width (`0..frameWidth`); updated by
     * `GlkvmDesktopSession` when symmetric horizontal pillarbox bars (e.g. a 16:10
     * display mirrored inside a 16:9 HDMI signal) are detected on decoded frames.
     */
    @Volatile var activeLeftPx: Int = 0
    @Volatile var activeWidthPx: Int = 2560

    @Volatile
    private var authSession: AuthSession? = null
    private val tokenLock = Any()

    /** Snapshot endpoint relative to [baseUrl]; switches to `api/streamer/snapshot` if `streamer/snapshot` 404s. */
    @Volatile
    private var snapshotPath: String = "streamer/snapshot"

    private val wsLock = Any()

    @Volatile
    private var streamWs: WebSocket? = null

    @Volatile
    private var streamWsOpen: Boolean = false

    @Volatile
    private var streamWsUnsupported: Boolean = false

    @Volatile
    private var lastWsPingAtMs: Long = 0L

    @Volatile
    private var glAdaptiveCleared: Boolean = false

    @Volatile
    private var closedClient: Boolean = false

    class Builder {
        var host: String = ""
        var port: Int = 443
        var user: String = "admin"
        var password: String = ""

        /** Injected TLS trust; pair from [GlkvmTrust.tofu]. Null = system default. */
        var sslSocketFactory: SSLSocketFactory? = null
        var trustManager: X509TrustManager? = null

        /** Plain-TCP factory for tunnel route-through (WireGuard/Tailscale/SOCKS); null = direct. */
        var plainSocketFactory: javax.net.SocketFactory? = null

        /** Full base URL override, e.g. `http://127.0.0.1:2222` (SSH-forward dial). */
        var baseUrlOverride: String? = null

        /** Per-attempt read timeout on snapshot GETs so a sleeping/stalled streamer self-heals promptly. */
        var snapshotReadTimeoutMs: Long = 6_000L

        /** Max handshake wait for `GET /api/ws?stream=1` HTTP 101 upgrade. */
        var streamWsOpenTimeoutMs: Long = 2_000L

        /** Cold-start retry count & delay while `ustreamer` initializes capture after `/api/ws?stream=1` opens. */
        var streamWakeMaxRetries: Int = 8
        var streamWakeRetryDelayMs: Long = 300L

        fun build(): GlkvmClient {
            val host = host.trim()
            val cleanPassword = password.trim('\r', '\n')
            require(host.isNotEmpty() || baseUrlOverride != null) { "GLKVM host is required" }
            val base = baseUrlOverride ?: "https://$host:$port"
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
            val tm = trustManager
            check(!(tm != null) || sslSocketFactory != null) { "trustManager set without socket factory" }
            if (tm != null && sslSocketFactory != null) {
                builder.sslSocketFactory(sslSocketFactory!!, tm)
                // The device cert rarely carries a matching hostname, and the
                // SSH-forward / tunnel-relay path dials 127.0.0.1; pin-by-SHA256
                // is the trust model (same policy as the known_tls_certs TOFU store).
                builder.hostnameVerifier { _, _ -> true }
            }
            if (plainSocketFactory != null) {
                builder.socketFactory(plainSocketFactory!!)
                // Userspace tunnel sockets (TunneledSocket / DeferredTunneledSocket)
                // ignore setSoTimeout, so OkHttp's RealConnection.isHealthy 1 ms
                // SO_TIMEOUT probe on pooled connections idle >= 10 s would block
                // indefinitely. Do not keep idle sockets in OkHttp's pool when a
                // custom plainSocketFactory is injected directly.
                builder.connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                // Tunnel/proxy factories (WireGuard, Tailscale, SOCKS5) resolve
                // internal hostnames remotely via InetSocketAddress.hostString;
                // never consult system clearnet DNS (avoids leaking private .lan
                // names, mobile DNS timeouts, and split-horizon wrong IPs).
                builder.dns { hostname ->
                    placeholderOrLiteralAddresses(hostname)
                }
            }
            val coreHttp = builder.build()
            val snapHttp = coreHttp.newBuilder()
                .readTimeout(snapshotReadTimeoutMs.coerceAtLeast(50L), TimeUnit.MILLISECONDS)
                .build()
            val wsClient = coreHttp.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
            return GlkvmClient(
                http = coreHttp,
                snapshotHttp = snapHttp,
                wsHttp = wsClient,
                baseUrl = base.toHttpUrl(),
                user = user,
                password = cleanPassword,
                streamWsOpenTimeoutMs = streamWsOpenTimeoutMs.coerceAtLeast(50L),
                streamWakeMaxRetries = streamWakeMaxRetries.coerceAtLeast(0),
                streamWakeRetryDelayMs = streamWakeRetryDelayMs.coerceAtLeast(0L),
            )
        }

        private fun placeholderOrLiteralAddresses(hostname: String): List<java.net.InetAddress> {
            val trimmed = hostname.trim().removeSurrounding("[", "]")
            if (trimmed.equals("localhost", ignoreCase = true)) {
                return listOf(
                    java.net.InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)),
                    java.net.InetAddress.getByAddress("localhost", ByteArray(16).apply { this[15] = 1 }),
                )
            }
            val ipv4Parts = trimmed.split('.')
            if (ipv4Parts.size == 4 && ipv4Parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } }) {
                val nums = ipv4Parts.map { it.toInt() }
                if (nums.all { it in 0..255 }) {
                    return listOf(
                        java.net.InetAddress.getByAddress(
                            trimmed,
                            byteArrayOf(nums[0].toByte(), nums[1].toByte(), nums[2].toByte(), nums[3].toByte()),
                        ),
                    )
                }
            }
            if (trimmed.contains(':')) {
                return try {
                    listOf(java.net.InetAddress.getByName(trimmed))
                } catch (_: java.net.UnknownHostException) {
                    listOf(java.net.InetAddress.getByAddress(hostname, ByteArray(4)))
                }
            }
            return listOf(java.net.InetAddress.getByAddress(hostname, ByteArray(4)))
        }
    }

    // -- auth ----------------------------------------------------------------

    private fun login(force: Boolean): AuthSession = synchronized(tokenLock) {
        val cached = authSession
        if (!force && cached != null) return cached
        if (force) {
            closeStreamSession()
            glAdaptiveCleared = false
        }
        val form = FormBody.Builder().add("user", user).add("passwd", password).build()
        val req = Request.Builder()
            .url(baseUrl.resolve("api/auth/login") ?: throw GlkvmException("bad base url"))
            .post(form).build()
        http.newCall(req).execute().use { resp ->
            val responseBody = resp.body
            val body: String = if (responseBody != null) responseBody.string() else ""
            if (!resp.isSuccessful) {
                if ((resp.code == 400 || resp.code == 403 || resp.code == 404) && isServerUnauthenticated()) {
                    authSession = AuthSession.NoAuth
                    return AuthSession.NoAuth
                }
                throw GlkvmException("auth/login -> HTTP ${resp.code}: ${body.take(200)}")
            }
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw GlkvmException("auth/login: non-JSON response: ${body.take(120)}") }
            if (!json.optBoolean("ok")) {
                throw GlkvmException("login failed: ${json.optString("message").ifEmpty { body.take(120) }}")
            }
            val resultObj = json.optJSONObject("result")
            val glToken = resultObj?.optString("token").orEmpty()
            val resolved = when {
                glToken.isNotEmpty() -> AuthSession.GlToken(glToken)
                else -> {
                    val cookie = extractAuthTokenCookie(resp)
                    when {
                        !cookie.isNullOrEmpty() -> AuthSession.CookieToken(cookie)
                        isServerUnauthenticated() -> AuthSession.NoAuth
                        else -> throw GlkvmException("login failed: no token in response")
                    }
                }
            }
            authSession = resolved
            return resolved
        }
    }

    private fun extractAuthTokenCookie(resp: okhttp3.Response): String? {
        for (raw in resp.headers("Set-Cookie")) {
            val firstPair = raw.substringBefore(';').trim()
            if (firstPair.startsWith("auth_token=")) {
                val value = firstPair.removePrefix("auth_token=").trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun isServerUnauthenticated(): Boolean {
        val url = baseUrl.resolve("api/auth/check") ?: return false
        return runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun buildAuthedRequest(
        path: String,
        auth: AuthSession,
        query: Map<String, String> = emptyMap(),
    ): Pair<HttpUrl, Request.Builder> {
        val cleanPath = path.trimStart('/')
        val url = (baseUrl.resolve(cleanPath) ?: throw GlkvmException("bad path: $path"))
            .newBuilder().apply {
                query.forEach { (k, v) -> addQueryParameter(k, v) }
                if (auth is AuthSession.GlToken) {
                    addQueryParameter("auth_token", auth.token)
                }
            }.build()
        val rb = Request.Builder().url(url)
        when (auth) {
            is AuthSession.GlToken -> rb.header("token", auth.token)
            is AuthSession.CookieToken -> rb.header("Cookie", "auth_token=${auth.cookieValue}")
            AuthSession.NoAuth -> Unit
        }
        return url to rb
    }

    // -- request pipeline ----------------------------------------------------

    /** One HTTP round trip with token retry. Returns bytes for JSON bodies. */
    private fun callText(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        retry: Boolean = true,
    ): String {
        val auth = login(force = false)
        val (_, rb) = buildAuthedRequest(path, auth, query)
        if (body != null) rb.post(body.toRequestBody(null))
        http.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.code == 401 && retry -> {
                    login(force = true)
                    return callText(method, path, query, body, retry = false)
                }
                // GL fork: unknown-but-format-valid token -> 403 ForbiddenError.
                resp.code == 403 && text.contains("ForbiddenError") && retry -> {
                    login(force = true)
                    return callText(method, path, query, body, retry = false)
                }
                resp.code >= 400 -> throw GlkvmException("$method $path -> HTTP ${resp.code}: ${text.take(200)}")
            }
            return text
        }
    }

    private fun callJson(path: String, query: Map<String, String> = emptyMap(), body: String? = null): JSONObject {
        val text = callText(if (body != null) "POST" else "GET", path, query, body)
        return runCatching { JSONObject(text) }
            .getOrElse { throw GlkvmException("$path: non-JSON body: ${text.take(120)}") }
    }

    private fun postOk(path: String, query: Map<String, String> = emptyMap()) {
        val json = callJson(path, query, body = "")
        if (!json.optBoolean("ok", true)) {
            throw GlkvmException("$path: ${json.optString("message").ifEmpty { json.toString().take(120) }}")
        }
    }

    // -- HID -----------------------------------------------------------------

    /**
     * Press or release a key. [code] is a KeyboardEvent.code name
     * (KeyA, MetaLeft, IntlBackslash…) — a *position* code the target OS
     * maps through its own layout.
     */
    fun sendKey(code: String, pressed: Boolean) {
        postOk("/api/hid/events/send_key", mapOf("key" to code, "state" to if (pressed) "1" else "0"))
    }

    /** Press or release a button: "left", "middle", "right". */
    fun sendMouseButton(button: String, pressed: Boolean) {
        postOk(
            "/api/hid/events/send_mouse_button",
            mapOf("button" to button, "state" to if (pressed) "1" else "0"),
        )
    }

    /** Absolute mouse move in target-screen px, device int16s from [frameWidth]/[frameHeight]. */
    fun sendMouseMove(x: Int, y: Int) {
        val w = frameWidth
        val activeW = activeWidthPx.takeIf { it in 1..w } ?: w
        val activeL = activeLeftPx.coerceIn(0, (w - activeW).coerceAtLeast(0))
        val vx = GlkvmGeometry.vFromPxX(x.toDouble(), w, activeLeftPx = activeL, activeWidthPx = activeW)
        val vy = GlkvmGeometry.vFromPxY(y.toDouble(), frameHeight)
        postOk(
            "/api/hid/events/send_mouse_move",
            mapOf("to_x" to vx.toString(), "to_y" to vy.toString()),
        )
    }

    /** Move to (x, y) then full press+release (device default when state omitted). */
    fun sendMouseClick(x: Int, y: Int, button: String = "left") {
        sendMouseMove(x, y)
        Thread.sleep(35)
        postOk("/api/hid/events/send_mouse_button", mapOf("button" to button))
    }

    /**
     * Type [text] as real keystrokes. Mapped characters go through the
     * layout table ([GlkvmLayout.keySequenceFor]) as position codes + Shift
     * — the target OS then applies its own layout — paced at [delay] ms per
     * stroke. Characters outside the table fall back to kvmd's
     * `/api/hid/print` keysym printer, which bakes in a US layout and may
     * mangle those on other targets (accents land wrong; documented GL
     * behaviour).
     */
    fun typeText(
        text: String,
        targetLayout: String? = null,
        delay: Long = 4,
        isCancelled: () -> Boolean = { false },
    ) {
        if (text.isEmpty()) return
        val table = GlkvmLayout.tableFor(targetLayout)
        val unmappedRun = StringBuilder()
        var shiftHeld = false
        fun setShift(want: Boolean) {
            if (shiftHeld != want) {
                sendKey("ShiftLeft", want)
                shiftHeld = want
            }
        }
        fun flushUnmapped(): Boolean {
            if (unmappedRun.isEmpty()) return true
            if (isCancelled()) return false
            setShift(false)
            callText(
                "POST",
                "/api/hid/print",
                mapOf("delay" to (delay / 10).toString()),
                body = unmappedRun.toString(),
            )
            unmappedRun.setLength(0)
            return !isCancelled()
        }
        try {
            for (ch in text) {
                if (isCancelled()) return
                val key = table[ch]
                if (key != null) {
                    if (!flushUnmapped()) return
                    setShift(key.shift)
                    sendKey(key.code, true)
                    if (delay > 0) Thread.sleep(delay / 2)
                    sendKey(key.code, false)
                    if (delay > 0) Thread.sleep(delay)
                } else {
                    unmappedRun.append(ch)
                }
            }
            flushUnmapped()
        } finally {
            if (shiftHeld) {
                runCatching { sendKey("ShiftLeft", false) }
            }
        }
    }

    /**
     * Wheel notches on the device convention (measured one notch down ≈
     * delta_y = -3): positive [notches] scroll down, negative up. One POST
     * per notch with ~30 ms pacing. NOTE the sign discipline belongs in the
     * adapter — the RemoteDesktopSession abstraction uses +deltaY = up.
     */
    fun sendWheel(notches: Int) {
        val dir = sign(notches.toFloat()).toInt()
        for (i in 0 until kotlin.math.abs(notches)) {
            postOk(
                "/api/hid/events/send_mouse_wheel",
                mapOf("delta_x" to "0", "delta_y" to (-GlkvmGeometry.WHEEL_STEP * dir).toString()),
            )
            if (i < notches - 1) Thread.sleep(30)
        }
    }

    // -- video & stream wake -------------------------------------------------

    /**
     * Ensure an authenticated `GET /api/ws?stream=1` WebSocket session is open.
     *
     * On GL.iNet Comet (RM1 `1.10.1`+) and stock PiKVM configs where
     * `kvmd.streamer.forever` defaults to `false`, `kvmd`'s stream controller
     * stops `ustreamer` whenever zero `/api/ws` stream clients are connected,
     * causing `/streamer/snapshot` to return `502`/`503` or time out until a
     * browser session opens (#845). Holding `/api/ws?stream=1` open keeps
     * `ustreamer` awake for the lifetime of the Haven session, and periodic
     * JSON `ping` events refresh GLKVM's sliding `auth_token` expiry.
     */
    fun ensureStreamSession(): Boolean {
        if (closedClient) return false
        if (streamWsOpen && streamWs != null) {
            keepStreamAliveIfNeeded()
            return true
        }
        if (streamWsUnsupported) return false
        return openStreamSessionInternal(allowRelogin = true)
    }

    private fun openStreamSessionInternal(allowRelogin: Boolean): Boolean {
        val auth = login(force = false)
        val openLatch = java.util.concurrent.CountDownLatch(1)
        val authRejected = java.util.concurrent.atomic.AtomicBoolean(false)
        synchronized(wsLock) {
            if (closedClient || streamWsUnsupported) return false
            if (streamWsOpen && streamWs != null) return true
            streamWs?.let { stale -> runCatching { stale.cancel() } }
            streamWs = null
            streamWsOpen = false
            val (_, rb) = buildAuthedRequest("api/ws", auth, mapOf("stream" to "1"))
            streamWs = wsHttp.newWebSocket(
                rb.get().build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        synchronized(wsLock) {
                            if (streamWs === webSocket && !closedClient) {
                                streamWsOpen = true
                                lastWsPingAtMs = System.currentTimeMillis()
                            }
                        }
                        runCatching { webSocket.send(STREAM_WS_PING_EVENT) }
                        openLatch.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Drain kvmd state broadcasts so the socket receive window stays clear.
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // Drain binary pong/HID frames.
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        synchronized(wsLock) {
                            if (streamWs === webSocket) {
                                streamWsOpen = false
                            }
                        }
                        runCatching { webSocket.close(code, reason) }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        synchronized(wsLock) {
                            if (streamWs === webSocket) {
                                streamWs = null
                                streamWsOpen = false
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        val code = response?.code ?: -1
                        synchronized(wsLock) {
                            if (streamWs === webSocket) {
                                streamWs = null
                                streamWsOpen = false
                            }
                            if (code == 404) {
                                streamWsUnsupported = true
                            }
                        }
                        if (code == 401 || code == 403) {
                            authRejected.set(true)
                        }
                        openLatch.countDown()
                    }
                },
            )
        }
        openLatch.await(streamWsOpenTimeoutMs, TimeUnit.MILLISECONDS)
        if (authRejected.get() && allowRelogin && !closedClient) {
            login(force = true)
            return openStreamSessionInternal(allowRelogin = false)
        }
        return streamWsOpen
    }

    /** Send a kvmd JSON ping over `/api/ws?stream=1` periodically to refresh sliding token expiry. */
    fun keepStreamAliveIfNeeded(nowMs: Long = System.currentTimeMillis()) {
        val ws = streamWs ?: return
        if (!streamWsOpen) return
        if (nowMs - lastWsPingAtMs >= STREAM_WS_HEARTBEAT_MS) {
            lastWsPingAtMs = nowMs
            if (!ws.send(STREAM_WS_PING_EVENT)) {
                synchronized(wsLock) {
                    if (streamWs === ws) {
                        streamWs = null
                        streamWsOpen = false
                    }
                }
            }
        }
    }

    /** Close `/api/ws?stream=1` cleanly (`1000`) so kvmd clears HID state and idles `ustreamer`. */
    fun closeStreamSession() {
        val ws = synchronized(wsLock) {
            val cur = streamWs
            streamWs = null
            streamWsOpen = false
            cur
        } ?: return
        runCatching { ws.close(1000, "closed") }
    }

    /**
     * Exit GLKVM's `webrtc_client` adaptive mode (`gl_webrtc=0`) once per GL token
     * session if `/streamer/snapshot` stalls or stays unavailable even with
     * `/api/ws?stream=1` open (e.g. after the GL.iNet mobile/cloud client left
     * `webrtc_client` holding the capture device). Never sent to stock PiKVM.
     */
    private fun exitGlAdaptiveModeIfNeeded() {
        if (glAdaptiveCleared || authSession !is AuthSession.GlToken) return
        glAdaptiveCleared = true
        runCatching { postOk("/api/streamer/set_params", mapOf("gl_webrtc" to "0")) }
    }

    /**
     * Raw JPEG of the live frame (`streamer/snapshot` on GLKVM and nginx-fronted
     * PiKVM, falling back to `api/streamer/snapshot` on kvmd setups without the
     * direct ustreamer route). Binary-safe with one forced relogin retry on
     * stale session (`401` or GL's `403 ForbiddenError`), plus automatic
     * `/api/ws?stream=1` streamer wakeup and cold-start retries when `ustreamer`
     * is sleeping or initializing (#845).
     */
    fun snapshot(): ByteArray {
        keepStreamAliveIfNeeded()
        val currentPath = snapshotPath
        return snapshotAt(currentPath, allowFallbackPath = currentPath == "streamer/snapshot")
    }

    private fun snapshotAt(path: String, allowFallbackPath: Boolean): ByteArray {
        var reloginUsed = false
        var wakeAttempt = 0
        while (true) {
            val response = try {
                fetchSnapshotBytes(path)
            } catch (e: InterruptedIOException) {
                if (closedClient || Thread.currentThread().isInterrupted || e !is SocketTimeoutException) {
                    throw GlkvmException("/$path interrupted: ${e.message}", e)
                }
                if (wakeAttempt >= streamWakeMaxRetries) {
                    if (allowFallbackPath) {
                        val fallback = "api/streamer/snapshot"
                        val bytes = snapshotAt(fallback, allowFallbackPath = false)
                        snapshotPath = fallback
                        return bytes
                    }
                    throw GlkvmException("/$path timed out waiting for streamer", e)
                }
                wakeAttempt++
                ensureStreamSession()
                exitGlAdaptiveModeIfNeeded()
                if (streamWakeRetryDelayMs > 0) Thread.sleep(streamWakeRetryDelayMs)
                continue
            }

            response.use { resp ->
                if (resp.code == 404 && allowFallbackPath) {
                    val fallback = "api/streamer/snapshot"
                    val bytes = snapshotAt(fallback, allowFallbackPath = false)
                    snapshotPath = fallback
                    return bytes
                }
                if (resp.code == 401 || resp.code == 403) {
                    val errText = resp.body?.string().orEmpty()
                    if (!reloginUsed && (resp.code == 401 || errText.contains("ForbiddenError"))) {
                        reloginUsed = true
                        login(force = true)
                        return@use
                    }
                    throw GlkvmException("/$path -> HTTP ${resp.code}: ${errText.take(200)}")
                }
                if (resp.code == 502 || resp.code == 503 || resp.code == 504) {
                    if (!closedClient && wakeAttempt < streamWakeMaxRetries) {
                        wakeAttempt++
                        ensureStreamSession()
                        if (resp.code == 504 || wakeAttempt >= 2) {
                            exitGlAdaptiveModeIfNeeded()
                        }
                        if (streamWakeRetryDelayMs > 0) Thread.sleep(streamWakeRetryDelayMs)
                        return@use
                    }
                    if (allowFallbackPath) {
                        val fallback = "api/streamer/snapshot"
                        val bytes = snapshotAt(fallback, allowFallbackPath = false)
                        snapshotPath = fallback
                        return bytes
                    }
                    val errText = resp.body?.string().orEmpty()
                    throw GlkvmException("/$path -> HTTP ${resp.code}: ${errText.take(200)}")
                }
                if (resp.code >= 400) {
                    val errText = resp.body?.string().orEmpty()
                    throw GlkvmException("/$path -> HTTP ${resp.code}: ${errText.take(200)}")
                }
                return resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    /** Raw snapshot GET using cached auth; caller closes the response. */
    private fun fetchSnapshotBytes(path: String): okhttp3.Response {
        val auth = login(force = false)
        val (_, rb) = buildAuthedRequest(path, auth)
        return snapshotHttp.newCall(rb.get().build()).execute()
    }

    /** HDMI/capture state: signal, resolution string, captured fps. */
    fun videoState(): VideoState {
        val result = callJson("/api/streamer").optJSONObject("result")
        val streamer = result?.optJSONObject("streamer")
            ?: result?.takeIf { it.has("source") || it.has("hdmi") || it.has("stream") }
            ?: throw GlkvmException("/api/streamer: unexpected shape")
        val source = streamer.optJSONObject("source") ?: JSONObject()
        val hdmi = streamer.optJSONObject("hdmi")
        val signal = if (hdmi != null && hdmi.has("signal")) {
            hdmi.optBoolean("signal", false)
        } else {
            source.optBoolean("online", false)
        }
        val realRes = source.optString("real_resolution", "")
        val resolution = realRes.ifEmpty { formatStreamerResolution(source.opt("resolution")) }
        return VideoState(
            signal = signal,
            resolution = resolution,
            fps = if (source.has("captured_fps")) source.optDouble("captured_fps") else null,
        )
    }

    private fun formatStreamerResolution(raw: Any?): String = when (raw) {
        is JSONObject -> {
            val w = raw.optInt("width", 0)
            val h = raw.optInt("height", 0)
            if (w > 0 && h > 0) "${w}x$h" else ""
        }
        is String -> raw
        else -> ""
    }

    data class VideoState(val signal: Boolean, val resolution: String, val fps: Double? = null)

    /** Current configured EDID preset on the KVM (e.g. `"E2560x1440"`, or `""` for device default). */
    fun edidGet(): String {
        val json = callJson("/api/upgrade/get_edid")
        val r = json.optJSONObject("result") ?: json
        return r.optString("edid", "")
    }

    /** Re-apply an EDID preset (`POST /api/upgrade/edid` multipart field `"edid"`). */
    fun edidSet(value: String): JSONObject {
        val auth = login(force = false)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("edid", value)
            .build()
        val (_, rb) = buildAuthedRequest("api/upgrade/edid", auth)
        http.newCall(rb.post(multipart).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if ((resp.code == 401) || (resp.code == 403 && text.contains("ForbiddenError"))) {
                val auth2 = login(force = true)
                val (_, rb2) = buildAuthedRequest("api/upgrade/edid", auth2)
                http.newCall(rb2.post(multipart).build())
                    .execute().use { second ->
                        val t2 = second.body?.string().orEmpty()
                        if (!second.isSuccessful) throw GlkvmException("POST /api/upgrade/edid -> HTTP ${second.code}: ${t2.take(200)}")
                        return runCatching { JSONObject(t2) }.getOrElse { JSONObject().put("ok", true) }
                    }
            }
            if (!resp.isSuccessful) throw GlkvmException("POST /api/upgrade/edid -> HTTP ${resp.code}: ${text.take(200)}")
            return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", true) }
        }
    }

    /**
     * Re-emit the current EDID preset (HPD pulse) to wake a target whose GPU dropped
     * its HDMI output (typical after a KVM reboot while Windows still has Duplicate enabled).
     * Polls up to [timeoutMs] for `videoState().signal` to become true.
     */
    fun edidProd(timeoutMs: Long = 15_000L, pollIntervalMs: Long = 2_000L): Boolean {
        val cur = edidGet()
        val value = cur.ifEmpty { "E2560x1440" }
        edidSet(value)
        var after = videoState()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!after.signal && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollIntervalMs.coerceAtLeast(10L))
            after = videoState()
        }
        return after.signal
    }

    override fun close() {
        closedClient = true
        closeStreamSession()
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val STREAM_WS_HEARTBEAT_MS = 15_000L
        private const val STREAM_WS_PING_EVENT = """{"event_type":"ping","event":{}}"""

        /** SHA-256 of a cert chain's leaf, lowercase hex — the TOFU pin format. */
        fun leafSha256(cert: Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Trust decision for self-signed kvmd devices: first use pins the leaf
 * cert's SHA-256 (the `known_tls_certs` TOFU model), later uses must match
 * the pin. System CAs are NOT consulted as a fallback — a self-issued kvmd
 * cert must never gain credibility from a public CA, and the public-CA path
 * would invite LAN MITM trust.
 */
object GlkvmTrust {

    fun tofu(pinnedSha256: String?, onPin: (String) -> Unit): Pair<SSLSocketFactory, X509TrustManager> {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)

            private fun check(chain: Array<X509Certificate>) {
                if (chain.isEmpty()) throw CertificateException("empty TLS certificate chain")
                val sha = GlkvmClient.leafSha256(chain[0])
                if (pinnedSha256 != null) {
                    if (!pinnedSha256.equals(sha, ignoreCase = true)) {
                        throw GlkvmCertMismatch(
                            "GLKVM TLS certificate changed (pinned ${pinnedSha256.take(12)}, got ${sha.take(12)}). " +
                                "Verify the device or re-trust it in the connection editor."
                        )
                    }
                } else {
                    onPin(sha)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tm), SecureRandom())
        }
        return ctx.socketFactory to tm
    }
}