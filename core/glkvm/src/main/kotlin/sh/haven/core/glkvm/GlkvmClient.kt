package sh.haven.core.glkvm

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
open class GlkvmException(message: String) : RuntimeException(message)

/** Raised when the pinned TLS certificate does not match the server's. */
class GlkvmCertMismatch(message: String) : GlkvmException(message)

/**
 * Driver for a GL.iNet GLKVM (PiKVM kvmd fork) web API over HTTPS.
 * Protocol empirically verified against kvmd 4.82 (RM10); ~/Code/glkvm-mcp
 * `kvmd.py` is the reference write-up — this port carries the GL fork's
 * quirks:
 *
 *  - login is a form POST returning a token; every API call then echoes it
 *    both as `?auth_token=` and as a `token` header;
 *  - a kvmd restart invalidates the cached token, but GL firmware answers a
 *    format-valid unknown token with **403 ForbiddenError** (real permission
 *    denials come back 401/400), so a 403 with that word triggers one forced
 *    relogin + retry;
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
    private val baseUrl: HttpUrl,
    private val user: String,
    private val password: String,
) : AutoCloseable {

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
    private var token: String? = null
    private val tokenLock = Any()

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

        fun build(): GlkvmClient {
            val host = host.trim()
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
                // SSH-forward path dials 127.0.0.1; pin-by-SHA256 is the trust
                // model (same policy as the known_tls_certs TOFU store).
                builder.hostnameVerifier { _, _ -> true }
            }
            if (plainSocketFactory != null) {
                builder.socketFactory(plainSocketFactory!!)
            }
            return GlkvmClient(builder.build(), base.toHttpUrl(), user, password)
        }
    }

    // -- auth ----------------------------------------------------------------

    private fun login(force: Boolean): String = synchronized(tokenLock) {
        val cached = token
        if (!force && cached != null) return cached
        val form = FormBody.Builder().add("user", user).add("passwd", password).build()
        val req = Request.Builder()
            .url(baseUrl.resolve("api/auth/login") ?: throw GlkvmException("bad base url"))
            .post(form).build()
        http.newCall(req).execute().use { resp ->
            val responseBody = resp.body
            val body: String = if (responseBody != null) responseBody.string() else ""
            if (!resp.isSuccessful) throw GlkvmException("auth/login -> HTTP ${resp.code}: ${body.take(200)}")
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw GlkvmException("auth/login: non-JSON response: ${body.take(120)}") }
            if (!json.optBoolean("ok")) {
                throw GlkvmException("login failed: ${json.optString("message").ifEmpty { body.take(120) }}")
            }
            val resultObj = json.optJSONObject("result")
            val t = resultObj?.optString("token") ?: ""
            if (t.isEmpty()) throw GlkvmException("login failed: no token in response")
            token = t
            return t
        }
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
        val tok = login(force = false)
        val url = (baseUrl.resolve(path) ?: throw GlkvmException("bad path: $path"))
            .newBuilder().apply {
                query.forEach { (k, v) -> addQueryParameter(k, v) }
                addQueryParameter("auth_token", tok)
            }.build()
        val rb = Request.Builder().url(url).header("token", tok)
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

    // -- video ---------------------------------------------------------------

    /**
     * Raw JPEG of the live frame (streamer snapshot). Binary-safe: goes over
     * the wire without the token-retry text decode. A stale-token snapshot
     * surfaces as GlkvmException — callers re-raise or refresh; the poller
     * treats one failure as a skipped tick.
     */
    fun snapshot(): ByteArray {
        fetchSnapshotBytes().use { first ->
            if (first.code == 401 || first.code == 403) {
                // Route the retry through the text pipeline, which owns the
                // GL quirks (403 ForbiddenError relogin; a real 401/403 denial
                // throws with the server's explanation) — then fetch again.
                callText("GET", "/streamer/snapshot")
                val tok2 = login(force = false)
                val url2 = (baseUrl.resolve("streamer/snapshot") ?: throw GlkvmException("bad base url"))
                    .newBuilder().addQueryParameter("auth_token", tok2).build()
                http.newCall(Request.Builder().url(url2).header("token", tok2).get().build())
                    .execute().use { second ->
                        if (!second.isSuccessful) throw GlkvmException("/streamer/snapshot -> HTTP ${second.code}")
                        return second.body?.bytes() ?: ByteArray(0)
                    }
            }
            if (first.code >= 400) throw GlkvmException("/streamer/snapshot -> HTTP ${first.code}")
            return first.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Raw snapshot GET without touching the token state; caller closes the response. */
    private fun fetchSnapshotBytes(): okhttp3.Response {
        val tok = login(force = false)
        val url = (baseUrl.resolve("streamer/snapshot") ?: throw GlkvmException("bad base url"))
            .newBuilder().addQueryParameter("auth_token", tok).build()
        val req = Request.Builder().url(url).header("token", tok).get().build()
        return http.newCall(req).execute()
    }

    /** HDMI/capture state: signal, real resolution string, captured fps. */
    fun videoState(): VideoState {
        val streamer = callJson("/api/streamer")
            .optJSONObject("result")?.optJSONObject("streamer")
        if (streamer == null) {
            throw GlkvmException("/api/streamer: unexpected shape")
        }
        val source = streamer.optJSONObject("source") ?: JSONObject()
        val hdmi = streamer.optJSONObject("hdmi") ?: JSONObject()
        return VideoState(
            signal = hdmi.optBoolean("signal", false),
            resolution = source.optString("real_resolution", ""),
            fps = if (source.has("captured_fps")) source.optDouble("captured_fps") else null,
        )
    }

    data class VideoState(val signal: Boolean, val resolution: String, val fps: Double? = null)

    override fun close() {
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
    }

    companion object {
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