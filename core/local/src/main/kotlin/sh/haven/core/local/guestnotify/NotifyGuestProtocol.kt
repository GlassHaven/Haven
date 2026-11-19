package sh.haven.core.local.guestnotify

/**
 * Wire protocol for the guest → Android notification bridge (#851, command-first).
 *
 * The staged `haven-notify` helper writes a request to the abstract
 * `LocalSocket` hosted by [NotifyGuestServer]; the server posts an Android
 * notification and writes back a one-line JSON ack. Direction is the reverse
 * of the GPS bridge (guest writes, host reads).
 *
 * The request is deliberately line-based, not JSON, so a `/bin/sh` client can
 * produce it with `printf` and no JSON escaping:
 *  - line 1 (up to the first `\n`) = title (required, single line);
 *  - everything after = body (may be multi-line; trailing newlines trimmed).
 * The guest half-closes its write side after the request (socat does this on
 * stdin EOF), so the server reads the request bytes until EOF then parses.
 *
 * The ack IS JSON (host-generated, controlled strings): `{"ok":true,"id":N}`
 * or `{"ok":false,"error":"..."}`, newline-terminated. The helper prints it to
 * the terminal so a failed delivery is a visible diagnostic, not a silent drop.
 *
 * Pure object: no Android types, so JVM tests drive it directly (the
 * [sh.haven.core.usb.UsbProxyProtocol] idiom).
 */
object NotifyGuestProtocol {

    const val MAX_TITLE: Int = 256
    const val MAX_BODY: Int = 4096

    /** Hard cap on request bytes read off the socket (title + body + newlines). */
    const val MAX_REQUEST: Int = MAX_TITLE + MAX_BODY + 2

    data class NotifyRequest(val title: String, val body: String)

    class ProtocolException(message: String) : Exception(message)

    /**
     * Parse the full request bytes (read until peer half-close). Splits title
     * from body at the first newline; tolerates CRLF. Rejects a blank title or
     * an over-length title/body with [ProtocolException].
     */
    fun parseRequest(raw: ByteArray): NotifyRequest {
        val text = String(raw, Charsets.UTF_8)
        val nl = text.indexOf('\n')
        val titleRaw = if (nl >= 0) text.substring(0, nl) else text
        val bodyRaw = if (nl >= 0) text.substring(nl + 1) else ""
        val title = titleRaw.trimEnd('\r').trim()
        val body = bodyRaw.trimEnd('\n', '\r')
        if (title.isBlank()) throw ProtocolException("title is required")
        if (title.length > MAX_TITLE) throw ProtocolException("title too long (max $MAX_TITLE chars)")
        if (body.length > MAX_BODY) throw ProtocolException("body too long (max $MAX_BODY chars)")
        return NotifyRequest(title, body)
    }

    fun encodeAck(id: Int): String = "{\"ok\":true,\"id\":$id}\n"

    fun encodeError(error: String): String = "{\"ok\":false,\"error\":${jsonString(error)}}\n"

    /** Minimal JSON string literal escaping for host-controlled ack strings. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
