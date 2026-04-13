package sh.haven.feature.sftp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket

/**
 * Coverage for the C3 security fix: the loopback HTTP bridge must
 * require a per-`start()` random token as the first URL path segment
 * and must NOT advertise CORS to other apps on the device.
 *
 * The tests speak raw HTTP/1.1 over a Socket so they don't depend on
 * any HTTP client library and exercise the actual byte-level request
 * parser inside SftpStreamServer.
 */
class SftpStreamServerTest {

    private lateinit var server: SftpStreamServer
    private val payload: ByteArray = "0123456789abcdef".toByteArray()

    @Before
    fun setUp() {
        server = SftpStreamServer()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun publishPayload(filename: String = "movie.mp4"): String {
        return server.publish(
            path = "/$filename",
            size = payload.size.toLong(),
            contentType = "video/mp4",
            opener = { offset ->
                ByteArrayInputStream(payload, offset.toInt(), payload.size - offset.toInt())
                    as InputStream
            },
        )
    }

    private data class HttpResponse(
        val statusLine: String,
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun rawGet(path: String, range: String? = null): HttpResponse {
        Socket("127.0.0.1", server.port).use { sock ->
            sock.soTimeout = 5_000
            val out = sock.getOutputStream()
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                if (range != null) append("Range: ").append(range).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val all = sock.getInputStream().readBytes()
            // Split headers / body on the first blank CRLF line.
            val sep = indexOf(all, "\r\n\r\n".toByteArray())
            require(sep >= 0) { "Malformed response: no header/body separator" }
            val headerText = String(all, 0, sep, Charsets.US_ASCII)
            val body = all.copyOfRange(sep + 4, all.size)

            val lines = headerText.split("\r\n")
            val statusLine = lines.first()
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            val headers = lines.drop(1)
                .mapNotNull {
                    val c = it.indexOf(':')
                    if (c <= 0) null else it.substring(0, c).trim().lowercase() to it.substring(c + 1).trim()
                }
                .toMap()
            return HttpResponse(statusLine, statusCode, headers, body)
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // ── token gating ─────────────────────────────────────────────────

    @Test
    fun `published path begins with leading slash followed by token segment`() {
        server.start()
        val urlPath = publishPayload()
        // /<token>/<key>
        assertTrue("expected leading slash, got '$urlPath'", urlPath.startsWith("/"))
        val parts = urlPath.removePrefix("/").split('/', limit = 2)
        assertEquals(2, parts.size)
        assertTrue("token must not be empty", parts[0].isNotEmpty())
        assertEquals("movie.mp4", parts[1])
    }

    @Test
    fun `request with correct token returns 200 and full payload`() {
        server.start()
        val urlPath = publishPayload()
        val resp = rawGet(urlPath)
        assertEquals(200, resp.statusCode)
        assertTrue(payload.contentEquals(resp.body))
    }

    @Test
    fun `request without any token segment returns 404`() {
        server.start()
        publishPayload()
        // /movie.mp4 — only a single segment, no token at all
        val resp = rawGet("/movie.mp4")
        assertEquals(404, resp.statusCode)
        assertEquals(0, resp.body.size)
    }

    @Test
    fun `request with wrong token returns 404 even when the key exists`() {
        server.start()
        val urlPath = publishPayload()
        val realKey = urlPath.substringAfterLast('/')
        val resp = rawGet("/this-is-not-the-real-token/$realKey")
        assertEquals(404, resp.statusCode)
    }

    @Test
    fun `request with correct token but unknown key returns 404`() {
        server.start()
        val urlPath = publishPayload()
        val token = urlPath.removePrefix("/").substringBefore('/')
        val resp = rawGet("/$token/missing-file.mp4")
        assertEquals(404, resp.statusCode)
    }

    @Test
    fun `token is regenerated on every restart`() {
        server.start()
        val firstUrl = publishPayload()
        val firstToken = firstUrl.removePrefix("/").substringBefore('/')
        server.stop()

        server.start()
        val secondUrl = publishPayload()
        val secondToken = secondUrl.removePrefix("/").substringBefore('/')

        assertNotEquals(
            "stopping and restarting must rotate the token so a stale URL " +
                "leaked from a previous session can't be replayed",
            firstToken,
            secondToken,
        )
    }

    @Test
    fun `replaying a token from a previous server lifecycle returns 404`() {
        server.start()
        val firstUrl = publishPayload()
        server.stop()
        server.start()
        // Re-publish so the entry exists under the NEW token; the stale
        // path uses the OLD token, which must no longer authenticate.
        publishPayload()
        val resp = rawGet(firstUrl)
        assertEquals(404, resp.statusCode)
    }

    // ── CORS ─────────────────────────────────────────────────────────

    @Test
    fun `successful response does NOT include Access-Control-Allow-Origin`() {
        server.start()
        val urlPath = publishPayload()
        val resp = rawGet(urlPath)
        assertEquals(200, resp.statusCode)
        assertFalse(
            "C3: SftpStreamServer must not advertise wildcard CORS — that lets " +
                "a WebView/browser tab on the device read SFTP bytes via fetch()",
            resp.headers.containsKey("access-control-allow-origin"),
        )
    }

    // ── Range support still works through the token gate ────────────

    @Test
    fun `Range request with valid token returns 206 Partial Content`() {
        server.start()
        val urlPath = publishPayload()
        val resp = rawGet(urlPath, range = "bytes=4-9")
        assertEquals(206, resp.statusCode)
        val expected = payload.copyOfRange(4, 10)
        assertTrue(expected.contentEquals(resp.body))
        // Content-Range header records the served slice.
        val cr = resp.headers["content-range"]
        assertNotNull(cr)
        assertEquals("bytes 4-9/${payload.size}", cr)
    }

    @Test
    fun `Range request without token returns 404 not 206`() {
        server.start()
        publishPayload()
        // Even with a valid Range header, a missing token must still 404.
        val resp = rawGet("/movie.mp4", range = "bytes=0-3")
        assertEquals(404, resp.statusCode)
    }

    // ── token has enough entropy to resist guessing ─────────────────

    @Test
    fun `token has at least 128 bits of entropy in printable form`() {
        server.start()
        val urlPath = publishPayload()
        val token = urlPath.removePrefix("/").substringBefore('/')
        // 32 random bytes encoded URL-safe base64 (no padding) is
        // ceil(32 * 4 / 3) = 43 characters. Anything materially shorter
        // means somebody dropped the SecureRandom width.
        assertTrue("token too short: '$token'", token.length >= 40)
    }
}
