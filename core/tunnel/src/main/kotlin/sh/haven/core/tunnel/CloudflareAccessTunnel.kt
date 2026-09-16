package sh.haven.core.tunnel

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [Tunnel] implementation that talks to a **Cloudflare Tunnel** published
 * hostname — the same wire protocol `cloudflared access ssh --hostname <h>`
 * uses on desktop, irrespective of whether the route has Cloudflare Access
 * sitting in front of it.
 *
 * Wire shape (verified against cloudflared's `carrier/websocket.go` and
 * `carrier/carrier.go` — Cloudflare don't publish a spec):
 *  - Dial `wss://<hostname>/` (root path, no subprotocol).
 *  - Plain binary frames ferry raw TCP bytes both directions; SSH then
 *    runs unmodified on top.
 *  - **Optional** `Cf-Access-Token: <jwt>` header carries an Access JWT
 *    when the route is Access-protected. Sent as an HTTP header — *not*
 *    a cookie, despite the browser-side flow setting `CF_Authorization`.
 *  - **Optional** `Cf-Access-Jump-Destination: <host>:<port>` header for
 *    bastion-mode multi-target tunnels (the server-side connector reads
 *    it to decide which SSH host to forward to).
 *
 * If the hostname is Access-protected and no JWT is supplied, Cloudflare's
 * edge responds with HTTP 302 to `/cdn-cgi/access/login/...`. We detect
 * that in [mapFailure] and surface a "sign in required" hint rather than
 * the bare "Expected HTTP 101".
 *
 * Per-hostname proxy: every [dial] must target the configured [hostname];
 * port is ignored because the server-side connector decides the upstream
 * SSH target (statically from the published route, or dynamically via
 * [jumpDestination] when set).
 *
 * Lifetime: the [OkHttpClient] is owned by the caller (Hilt singleton)
 * and not closed in [close] — only the per-dial WebSockets are torn
 * down. Since this backend has no L3 surface there's no [socksAddress]
 * implementation; rclone / IronRDP can't route through it.
 *
 * Thread-safety: [dial] may be called concurrently. Each dial owns its
 * own WebSocket; the parent tunnel only tracks them for [close] to
 * cancel any still-live streams.
 *
 * The class is still named `CloudflareAccessTunnel` for compatibility
 * with rc1–rc3 code paths; it now handles both unprotected Cloudflare
 * Tunnel routes and Access-protected ones uniformly. See GH #154.
 */
class CloudflareAccessTunnel internal constructor(
    private val hostname: String,
    /** Cloudflare Access JWT. Empty / blank for unprotected Tunnel routes. */
    private val jwt: String,
    /** Optional `Cf-Access-Jump-Destination` value, e.g. `internal-host:22`. Blank to omit. */
    private val jumpDestination: String,
    private val httpClient: OkHttpClient,
    /** Test seam. Production code passes null; tests point at MockWebServer. */
    private val gatewayUrlOverride: String? = null,
) : Tunnel {

    companion object {
        /**
         * Public factory for callers outside `:core:tunnel` (the
         * production path goes through [DefaultTunnelFactory] and never
         * needs this). Used by the Settings "Test connection" button to
         * build a transient tunnel without going through [TunnelManager]
         * — the test deliberately isn't ref-counted into any live
         * session.
         */
        fun forTest(
            hostname: String,
            jwt: String,
            httpClient: OkHttpClient,
            jumpDestination: String = "",
        ): CloudflareAccessTunnel = CloudflareAccessTunnel(
            hostname = hostname,
            jwt = jwt,
            jumpDestination = jumpDestination,
            httpClient = httpClient,
        )
    }

    private val live = CopyOnWriteArraySet<WebSocketTunneledConnection>()

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        require(host.equals(hostname, ignoreCase = true)) {
            "CloudflareAccessTunnel is bound to '$hostname'; cannot dial '$host'"
        }

        val url = gatewayUrlOverride ?: "https://$hostname/"
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Haven-CF-Tunnel")
        if (jwt.isNotBlank()) {
            builder.header("Cf-Access-Token", jwt)
        }
        if (jumpDestination.isNotBlank()) {
            builder.header("Cf-Access-Jump-Destination", jumpDestination)
        }
        val request = builder.build()

        // Per-dial client. Two adjustments vs the shared singleton:
        //  - Apply the requested call timeout (production paths typically
        //    pass timeoutMs > 0 so reads don't block forever).
        //  - Disable redirect-following. Cloudflare's signal that a route
        //    is Access-protected is a 302 to `/cdn-cgi/access/login/...`
        //    on the team domain; OkHttp's WS dialer would otherwise
        //    silently chase that redirect to a *different host* and
        //    return whatever 404 the team domain serves, hiding the
        //    diagnostic we actually want. cloudflared/gorilla never
        //    follows WS redirects; we mirror that here.
        val client = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .apply {
                if (timeoutMs > 0) {
                    callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                }
            }
            .build()

        val conn = WebSocketTunneledConnection(hostname) { live.remove(it) }
        live.add(conn)
        try {
            conn.start(client, request, timeoutMs)
        } catch (t: Throwable) {
            live.remove(conn)
            throw t
        }
        return conn
    }

    override fun socksAddress(): InetSocketAddress? = null

    override fun close() {
        // Snapshot to avoid CME if a dial finishes concurrently.
        live.toList().forEach { runCatching { it.close() } }
        live.clear()
    }
}

/**
 * [TunneledConnection] backed by an OkHttp [WebSocket]. Inbound binary
 * frames are piped into [inputStream]; [outputStream] writes are sent as
 * single binary frames per call. SSH already produces suitably-sized
 * writes, so we don't bother batching.
 */
private class WebSocketTunneledConnection(
    private val hostname: String,
    private val onClose: (WebSocketTunneledConnection) -> Unit,
) : TunneledConnection {

    private val pipeBufferSize = 64 * 1024
    private val inboundSink = PipedOutputStream()
    private val inboundSource = PipedInputStream(inboundSink, pipeBufferSize)
    private val openedLatch = CountDownLatch(1)
    private val openError = AtomicReference<Throwable?>(null)
    private val webSocket = AtomicReference<WebSocket?>(null)
    @Volatile private var closed = false

    override val inputStream: InputStream = inboundSource

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xFF).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Cloudflare Tunnel closed")
            if (len == 0) return
            val ws = webSocket.get() ?: throw IOException("Cloudflare Tunnel WebSocket not yet open")
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            val ok = ws.send(ByteString.of(*slice))
            if (!ok) throw IOException("Cloudflare Tunnel WebSocket send queue closed")
        }
    }

    fun start(client: OkHttpClient, request: Request, timeoutMs: Int) {
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket.set(ws)
                openedLatch.countDown()
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (closed) return
                try {
                    bytes.write(inboundSink)
                    inboundSink.flush()
                } catch (t: Throwable) {
                    // Reader closed before WS finished — common on
                    // disconnect, not worth re-raising.
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // Cloudflare's gateway never sends text frames. If we
                // see one, treat it as a protocol error so callers
                // don't silently drop bytes.
                fail(ws, IOException("Cloudflare Tunnel sent unexpected text frame"))
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                closeQuietly()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                closeQuietly()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                fail(ws, mapFailure(t, response))
            }

            private fun fail(ws: WebSocket, error: Throwable) {
                if (openError.compareAndSet(null, error)) {
                    openedLatch.countDown()
                }
                closeQuietly()
            }
        }

        client.newWebSocket(request, listener)

        val awaitMs = if (timeoutMs > 0) timeoutMs.toLong() else 30_000L
        val opened = openedLatch.await(awaitMs, TimeUnit.MILLISECONDS)
        openError.get()?.let { throw it }
        if (!opened) {
            close()
            throw IOException("Cloudflare Tunnel WebSocket open timed out after ${awaitMs}ms")
        }
    }

    /**
     * Translate OkHttp's failure surface into a connection-log-friendly
     * exception.
     *
     * Detection ladder (most-specific first):
     *  - **302 to `/cdn-cgi/access/login`** → "this hostname is Access-protected,
     *    sign in to capture a JWT". Mirrors cloudflared's
     *    `IsAccessResponse` check.
     *  - **401/403** → "JWT rejected, re-authenticate". Only meaningful
     *    if a JWT was sent; otherwise the more useful signal is the
     *    302 above.
     *  - **5xx** → "Cloudflare gateway error".
     *
     * Every failure also carries the Cloudflare-specific debug headers
     * (`cf-ray` for support correlation, `cf-mitigated` for WAF blocks,
     * `Server`, `WWW-Authenticate`, `Location`) plus the first 256 chars
     * of any HTML body. JWT-shaped values are redacted before logging.
     */
    private fun mapFailure(t: Throwable, response: Response?): Throwable {
        val code = response?.code
        val location = response?.header("Location").orEmpty()
        val detail = buildDiagnostics(response)
        val base = when {
            code == 302 && location.contains(ACCESS_LOGIN_PATH) ->
                "Cloudflare Tunnel '$hostname' requires Access auth — sign in to capture a JWT"
            code == 401 || code == 403 ->
                "Cloudflare Tunnel '$hostname' rejected request (HTTP $code) — JWT may be invalid or expired"
            code != null && code in 500..599 ->
                "Cloudflare gateway error for '$hostname' (HTTP $code)"
            code == null ->
                "Cloudflare Tunnel WebSocket to '$hostname' failed: ${t.message}"
            else ->
                "Cloudflare Tunnel WebSocket to '$hostname' failed: HTTP $code ${t.message ?: ""}".trim()
        }
        return IOException(if (detail.isEmpty()) base else "$base — $detail", t)
    }

    /**
     * Build a `key=value; key=value` diagnostic string from the upgrade
     * response. Empty if [response] is null. Body excerpt is included
     * only if the content-type looks textual and the body is small.
     */
    private fun buildDiagnostics(response: Response?): String {
        if (response == null) return ""
        val parts = mutableListOf<String>()
        for (name in DIAG_HEADERS) {
            response.header(name)?.let { parts += "$name=${redact(it)}" }
        }
        // Body excerpt — guarded by content-type and length. OkHttp
        // peekBody buffers without consuming the upstream source, so
        // we don't break any downstream consumer of `response`.
        val contentType = response.header("Content-Type").orEmpty()
        val textual = contentType.startsWith("text/", ignoreCase = true) ||
            contentType.contains("html", ignoreCase = true) ||
            contentType.contains("json", ignoreCase = true) ||
            contentType.isEmpty()
        if (textual) {
            runCatching {
                val peeked = response.peekBody(BODY_PEEK_BYTES)
                val excerpt = peeked.string().take(BODY_EXCERPT_CHARS).replace(Regex("\\s+"), " ").trim()
                if (excerpt.isNotEmpty()) parts += "body=\"$excerpt\""
            }
        }
        return parts.joinToString("; ")
    }

    /** Redact JWT-shaped values so debug strings don't leak credentials. */
    private fun redact(value: String): String {
        if (value.length > 40 && value.contains('.')) return "<redacted ${value.length} chars>"
        return value
    }

    private companion object {
        /** Headers we want in the diagnostic line, in priority order. */
        val DIAG_HEADERS = listOf(
            "cf-ray",
            "cf-mitigated",
            "Server",
            "WWW-Authenticate",
            "Location",
        )
        const val BODY_PEEK_BYTES = 1024L
        const val BODY_EXCERPT_CHARS = 256
        /** cloudflared's signal that a route is Access-protected (carrier.go:120). */
        const val ACCESS_LOGIN_PATH = CloudflareAccessLogin.ACCESS_LOGIN_PATH
    }

    override fun close() {
        if (closed) return
        closed = true
        webSocket.getAndSet(null)?.let { runCatching { it.close(1000, null) } }
        runCatching { inboundSink.close() }
        runCatching { inboundSource.close() }
        onClose(this)
    }

    private fun closeQuietly() {
        runCatching { inboundSink.close() }
    }
}
