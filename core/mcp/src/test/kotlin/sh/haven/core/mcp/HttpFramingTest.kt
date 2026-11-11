package sh.haven.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pins the byte-level HTTP framing (#mcp-backbone Stage 0). The parser is pure
 * over an InputStream, so these run without a socket or Robolectric. They guard
 * the two defects the rewrite fixed — unbounded Content-Length (OOM) and a
 * char-vs-byte body read that hung on multibyte UTF-8 — plus the header bound
 * and the Origin/CSRF helper.
 */
class HttpFramingTest {

    private fun parse(raw: String) =
        parseHttpRequest(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    @Test
    fun `parses a normal POST with a json body`() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val raw = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:8730\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
        val res = parse(raw)
        assertTrue(res is HttpParseResult.Ok)
        res as HttpParseResult.Ok
        assertEquals("POST", res.request.method)
        assertEquals("/mcp", res.request.path)
        assertEquals(body, res.request.body)
    }

    @Test
    fun `multibyte utf8 body is read by byte length, not char count`() {
        // '中文' is 3 bytes/char, '😀' is a 4-byte surrogate pair — so the byte
        // Content-Length exceeds the char count. The old char-counting loop
        // under-filled and stalled until the socket timeout; this must not.
        val body = """{"text":"中文😀"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val raw = "POST /mcp HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n"
            .toByteArray(Charsets.UTF_8) + bytes
        val res = parseHttpRequest(ByteArrayInputStream(raw))
        assertTrue(res is HttpParseResult.Ok)
        assertEquals(body, (res as HttpParseResult.Ok).request.body)
    }

    @Test
    fun `oversized content-length is refused before allocation`() {
        val res = parse("POST /mcp HTTP/1.1\r\nContent-Length: 2000000000\r\n\r\n")
        assertTrue(res is HttpParseResult.Fail)
        assertEquals(413, (res as HttpParseResult.Fail).status)
    }

    @Test
    fun `negative content-length is refused`() {
        val res = parse("POST /mcp HTTP/1.1\r\nContent-Length: -5\r\n\r\n")
        assertEquals(413, (res as HttpParseResult.Fail).status)
    }

    @Test
    fun `an unterminated header block is bounded, not read forever`() {
        // No terminating CRLFCRLF; a single header far past MAX_HEADER_BYTES.
        val res = parse("POST /mcp HTTP/1.1\r\nX-Pad: " + "a".repeat(70 * 1024))
        assertTrue(res is HttpParseResult.Fail)
        assertEquals(431, (res as HttpParseResult.Fail).status)
    }

    @Test
    fun `a body truncated by EOF fails rather than hanging`() {
        val res = parse("POST /mcp HTTP/1.1\r\nContent-Length: 100\r\n\r\nshort")
        assertTrue(res is HttpParseResult.Fail)
        assertEquals(400, (res as HttpParseResult.Fail).status)
    }

    @Test
    fun `a clean close before any bytes is Closed`() {
        assertTrue(parse("") is HttpParseResult.Closed)
    }

    @Test
    fun `header names are lowercased and parsed`() {
        val res = parse("GET /mcp HTTP/1.1\r\nMcp-Session-Id: abc\r\n\r\n") as HttpParseResult.Ok
        assertEquals("abc", res.request.headers["mcp-session-id"])
    }

    @Test
    fun `loopback origins are accepted and real hosts rejected`() {
        assertTrue(isLoopbackOrigin("http://127.0.0.1:8730"))
        assertTrue(isLoopbackOrigin("https://127.0.0.1:8730"))
        assertTrue(isLoopbackOrigin("http://localhost:8788"))
        assertTrue(isLoopbackOrigin("http://[::1]:8730"))
        assertTrue(isLoopbackOrigin("http://127.5.5.5"))
        assertFalse(isLoopbackOrigin("https://evil.example"))
        assertFalse(isLoopbackOrigin("https://127.attacker.tld"))
        assertFalse(isLoopbackOrigin("http://127.0.0.1.attacker.tld:8730"))
        assertFalse(isLoopbackOrigin("http://127.256.0.1:8730"))
        assertFalse(isLoopbackOrigin("file://127.0.0.1/etc/hosts"))
        assertFalse(isLoopbackOrigin("javascript://127.0.0.1/%0aalert(1)"))
        assertFalse(isLoopbackOrigin("http://127.0.0.1@evil.example"))
        assertFalse(isLoopbackOrigin("http://192.168.1.5:8730"))
        assertFalse(isLoopbackOrigin("null"))
    }
}
