package sh.haven.feature.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VncComposeBar's contract: what the user sees is exactly what is typed —
 * nothing added, nothing silently dropped.
 */
class VncComposeBarTest {

    @Test
    fun `printable ASCII, newline and tab are typeable`() {
        assertTrue(isVncTypeable("P@ssw0rd! ~`{}[]|\\:;\"'<>,.?/"))
        assertTrue(isVncTypeable("line one\nline two\tend"))
    }

    @Test
    fun `characters VncClient cannot type are not`() {
        val noBreakSpace = Char(0xA0)
        assertFalse(isVncTypeable("中文"))
        assertFalse(isVncTypeable("café"))
        assertFalse(isVncTypeable("emoji 🙂"))
        assertFalse("no-break space", isVncTypeable("a${noBreakSpace}b"))
    }

    @Test
    fun `payload is the text unchanged when Enter is off`() {
        assertEquals("  spaced  password ", vncComposePayload("  spaced  password ", pressEnter = false))
    }

    @Test
    fun `Enter appends exactly one Return`() {
        assertEquals("hunter2\n", vncComposePayload("hunter2", pressEnter = true))
    }

    @Test
    fun `CRLF and CR collapse to one Return each`() {
        assertEquals("a\nb\nc", vncComposePayload("a\r\nb\rc", pressEnter = false))
    }
}
