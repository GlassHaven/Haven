package sh.haven.core.mcp

import java.io.InputStream

/**
 * Byte-level HTTP/1.1 framing for the MCP backbone (#mcp-backbone Stage 4 —
 * Layer A). Extracted from the server so the parser, its DoS bounds, and the
 * keep-alive connection loop ([serveHttpConnection]) are one shared,
 * socket-free-testable core rather than transport-specific copies.
 */

/**
 * Hard cap on a request body (#mcp-backbone Stage 0). The body buffer is sized
 * from the client-supplied `Content-Length`; without a bound, a hostile
 * `Content-Length: 2000000000` forces a multi-GB allocation → OutOfMemoryError
 * before a byte of body arrives. MCP request bodies are small JSON (bulk bytes
 * go out-of-band via serve_file), so 8 MiB is generous headroom. A larger or
 * negative length is refused with 413 before any allocation.
 */
const val MAX_BODY_BYTES: Int = 8 * 1024 * 1024

/**
 * Hard cap on the HTTP header block (#mcp-backbone Stage 0). The header reader
 * accumulates bytes until the CRLFCRLF terminator; without a bound a peer that
 * never sends the blank line (Slowloris) grows the buffer until the socket
 * timeout. 64 KiB is far above any real MCP request's headers.
 */
const val MAX_HEADER_BYTES: Int = 64 * 1024

/** Outcome of [parseHttpRequest]. */
sealed interface HttpParseResult {
    data class Ok(val request: ParsedHttpRequest) : HttpParseResult
    /** Malformed or oversized — [status]/[reason] is the HTTP error to return. */
    data class Fail(val status: Int, val reason: String) : HttpParseResult
    /** Clean EOF before any bytes — the peer closed without sending a request. */
    object Closed : HttpParseResult
}

/**
 * One parsed HTTP request. [headers] keys are lowercased; [body] is UTF-8.
 * [httpVersion] is the request-line version token (`HTTP/1.1`); anything else
 * gets close-per-response semantics from [serveHttpConnection].
 */
data class ParsedHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    val httpVersion: String = "HTTP/1.1",
)

/**
 * Byte-accurate HTTP/1.1 request parser (#mcp-backbone Stage 0). Reads the
 * header block up to the CRLFCRLF terminator (bounded by [MAX_HEADER_BYTES]),
 * then reads exactly `Content-Length` BYTES (bounded by [MAX_BODY_BYTES]) and
 * decodes them as UTF-8.
 *
 * Replaces a char-based read that (a) sized the body buffer from an unbounded
 * `Content-Length` (a hostile length OOM'd the process) and (b) counted CHARS
 * against a BYTE length, so any multibyte-UTF-8 body under-filled the loop and
 * stalled the read until the 70 s socket timeout. Pure over an [InputStream] so
 * it is unit-testable without a socket.
 */
fun parseHttpRequest(input: InputStream): HttpParseResult {
    val head = java.io.ByteArrayOutputStream(512)
    val cr = '\r'.code
    val lf = '\n'.code
    var state = 0 // progress through the CR LF CR LF terminator
    while (true) {
        if (head.size() >= MAX_HEADER_BYTES) {
            return HttpParseResult.Fail(431, "Request Header Fields Too Large")
        }
        val b = input.read()
        if (b < 0) {
            return if (head.size() == 0) HttpParseResult.Closed
            else HttpParseResult.Fail(400, "Bad Request")
        }
        head.write(b)
        state = when {
            state == 0 && b == cr -> 1
            state == 1 && b == lf -> 2
            state == 2 && b == cr -> 3
            state == 3 && b == lf -> 4
            b == cr -> 1
            else -> 0
        }
        if (state == 4) break
    }
    // Headers are ASCII/latin1; decode the block that way for line splitting.
    val lines = head.toString("ISO-8859-1").split("\r\n")
    val parts = lines.firstOrNull().orEmpty().split(" ")
    if (parts.size < 3) return HttpParseResult.Fail(400, "Bad Request")
    val headers = HashMap<String, String>()
    for (i in 1 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon > 0) {
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }
    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    if (contentLength !in 0..MAX_BODY_BYTES) return HttpParseResult.Fail(413, "Payload Too Large")
    val body = if (contentLength > 0) {
        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(buf, read, contentLength - read)
            if (n < 0) return HttpParseResult.Fail(400, "Bad Request") // body truncated by EOF
            read += n
        }
        String(buf, Charsets.UTF_8)
    } else ""
    return HttpParseResult.Ok(ParsedHttpRequest(parts[0], parts[1], headers, body, parts[2]))
}

/**
 * True iff [origin] (an HTTP `Origin` header, `scheme://host[:port]`) names a
 * loopback host. The browser DNS-rebinding / CSRF guard rejects a POST carrying
 * any other Origin — a page served from a real host sends its domain as the
 * Origin host, never `127.0.0.1`. A `null`/opaque origin (sandboxed iframe,
 * `file://`) is treated as non-loopback and denied. Non-browser MCP clients send
 * no Origin at all and bypass the check entirely.
 */
fun isLoopbackOrigin(origin: String): Boolean {
    val schemeSep = origin.indexOf("://")
    if (schemeSep <= 0) return false
    val scheme = origin.substring(0, schemeSep).lowercase()
    if (scheme != "http" && scheme != "https") return false
    val authority = origin.substring(schemeSep + 3).substringBefore('/')
    return isLoopbackHttpHost(authority)
}

/**
 * True iff [hostHeader] (an HTTP `Host` header or `host[:port]` authority) names
 * a strict loopback host (`localhost`, `[::1]`, or a 4-octet `127.0.0.0/8` IPv4
 * literal). Rejects userinfo (`@`), whitespace, and domains that merely begin
 * with `"127."` such as `127.attacker.tld`.
 */
fun isLoopbackHttpHost(hostHeader: String): Boolean {
    val trimmed = hostHeader.trim()
    if (trimmed.isEmpty() || '@' in trimmed || '/' in trimmed || trimmed.any { it.isWhitespace() }) {
        return false
    }
    val host = if (trimmed.startsWith("[")) {
        val end = trimmed.indexOf(']')
        if (end <= 1) return false
        val rest = trimmed.substring(end + 1)
        if (rest.isNotEmpty() && (!rest.startsWith(":") || rest.substring(1).toIntOrNull() !in 1..65535)) {
            return false
        }
        trimmed.substring(1, end).lowercase()
    } else {
        val colon = trimmed.indexOf(':')
        if (colon >= 0) {
            if (trimmed.indexOf(':', colon + 1) >= 0) return false
            val port = trimmed.substring(colon + 1).toIntOrNull() ?: return false
            if (port !in 1..65535) return false
            trimmed.substring(0, colon).lowercase()
        } else {
            trimmed.lowercase()
        }
    }
    if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
    return isIpv4LoopbackLiteral(host)
}

private fun isIpv4LoopbackLiteral(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    for (i in 0..3) {
        val p = parts[i]
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return false
        if (p.length > 1 && p[0] == '0') return false
        val octet = p.toIntOrNull() ?: return false
        if (octet !in 0..255) return false
        if (i == 0 && octet != 127) return false
    }
    return true
}
