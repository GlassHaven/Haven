package sh.haven.core.local.guestnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure JVM round-trips for the guest notification wire protocol (#851), the
 * [sh.haven.core.usb.UsbProxyProtocolTest] idiom: drive the parser/encoder over
 * byte arrays, no Android types or sockets.
 */
class NotifyGuestProtocolTest {

    private fun parse(s: String) = NotifyGuestProtocol.parseRequest(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `title and body split at first newline`() {
        val req = parse("Task complete\nThe script finished\n")
        assertEquals("Task complete", req.title)
        assertEquals("The script finished", req.body)
    }

    @Test
    fun `multi-line body is preserved`() {
        val req = parse("Build\nline one\nline two\n")
        assertEquals("Build", req.title)
        assertEquals("line one\nline two", req.body)
    }

    @Test
    fun `title-only request has empty body`() {
        val req = parse("Just a title\n")
        assertEquals("Just a title", req.title)
        assertEquals("", req.body)
    }

    @Test
    fun `no trailing newline still parses`() {
        val req = parse("Title\nbody")
        assertEquals("Title", req.title)
        assertEquals("body", req.body)
    }

    @Test
    fun `CRLF line endings are tolerated`() {
        val req = parse("Title\r\nbody\r\n")
        assertEquals("Title", req.title)
        assertEquals("body", req.body)
    }

    @Test
    fun `utf8 title and body survive`() {
        val req = parse("Tâche terminée\nLe script a fini — été\n")
        assertEquals("Tâche terminée", req.title)
        assertEquals("Le script a fini — été", req.body)
    }

    @Test
    fun `empty title is rejected`() {
        assertRejected("\nbody\n")
        assertRejected("")
    }

    @Test
    fun `blank title is rejected`() {
        assertRejected("   \nbody\n")
    }

    @Test
    fun `over-length title is rejected`() {
        val longTitle = "x".repeat(NotifyGuestProtocol.MAX_TITLE + 1)
        assertRejected("$longTitle\nbody\n")
    }

    @Test
    fun `over-length body is rejected`() {
        val longBody = "y".repeat(NotifyGuestProtocol.MAX_BODY + 1)
        assertRejected("Title\n$longBody\n")
    }

    @Test
    fun `max-length title and body are accepted`() {
        val title = "x".repeat(NotifyGuestProtocol.MAX_TITLE)
        val body = "y".repeat(NotifyGuestProtocol.MAX_BODY)
        val req = parse("$title\n$body\n")
        assertEquals(title, req.title)
        assertEquals(body, req.body)
    }

    @Test
    fun `ack is a JSON line with id`() {
        assertEquals("{\"ok\":true,\"id\":42}\n", NotifyGuestProtocol.encodeAck(42))
    }

    @Test
    fun `error ack escapes quotes and newlines`() {
        val ack = NotifyGuestProtocol.encodeError("bad \"title\"\nnext")
        assertEquals("{\"ok\":false,\"error\":\"bad \\\"title\\\"\\nnext\"}\n", ack)
    }

    @Test
    fun `error ack is newline terminated`() {
        assertTrue(NotifyGuestProtocol.encodeError("rate limited").endsWith("\n"))
    }

    private fun assertRejected(s: String) {
        try {
            parse(s)
            fail("expected ProtocolException for: ${s.take(40)}")
        } catch (_: NotifyGuestProtocol.ProtocolException) {
            // expected
        }
    }
}
