package sh.haven.feature.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class OscHandlerTest {

    private lateinit var handler: OscHandler
    private val clipboardResults = mutableListOf<String>()
    private val hyperlinkResults = mutableListOf<String?>()
    private val notificationResults = mutableListOf<Pair<String, String>>()
    private val cwdResults = mutableListOf<String>()
    private val colorQueryResults = mutableListOf<Pair<Int, String>>()

    @Before
    fun setUp() {
        clipboardResults.clear()
        hyperlinkResults.clear()
        notificationResults.clear()
        cwdResults.clear()
        colorQueryResults.clear()
        handler = OscHandler(
            onClipboardSet = { clipboardResults.add(it) },
            onHyperlink = { hyperlinkResults.add(it) },
            onNotification = { title, body -> notificationResults.add(title to body) },
            onCwdChanged = { cwdResults.add(it) },
            onColorQuery = { osc, terminator -> colorQueryResults.add(osc to terminator) },
        )
    }

    private fun process(input: ByteArray): ByteArray {
        val output = ByteArray(input.size + 1024)
        val len = handler.process(input, 0, input.size, output)
        return output.copyOfRange(0, len)
    }

    private fun process(input: String): ByteArray = process(input.toByteArray())

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    // ========================================================================
    // OSC 52 — clipboard
    // ========================================================================

    @Test
    fun `complete OSC 52 with BEL terminator sets clipboard`() {
        val b64 = encode("Hello from remote!")
        val input = "\u001b]52;c;$b64\u0007"
        val output = process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals("Hello from remote!", clipboardResults[0])
        assertEquals(0, output.size)
    }

    @Test
    fun `complete OSC 52 with ST terminator sets clipboard`() {
        val b64 = encode("ST terminated")
        val input = "\u001b]52;c;$b64\u001b\\"
        val output = process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals("ST terminated", clipboardResults[0])
        assertEquals(0, output.size)
    }

    @Test
    fun `non-OSC data passes through unchanged`() {
        val input = "Hello, World!\r\n"
        val output = process(input)
        assertEquals(input, String(output))
        assertEquals(0, clipboardResults.size)
    }

    @Test
    fun `regular escape sequences pass through`() {
        val input = "\u001b[1;1H"
        val output = process(input)
        assertEquals(input, String(output))
    }

    @Test
    fun `OSC 52 surrounded by normal data`() {
        val b64 = encode("clip")
        val input = "before\u001b]52;c;$b64\u0007after"
        val output = process(input)

        assertEquals("beforeafter", String(output))
        assertEquals(1, clipboardResults.size)
        assertEquals("clip", clipboardResults[0])
    }

    @Test
    fun `multiple OSC 52 sequences in one buffer`() {
        val b64a = encode("first")
        val b64b = encode("second")
        val input = "\u001b]52;c;$b64a\u0007\u001b]52;c;$b64b\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(2, clipboardResults.size)
        assertEquals("first", clipboardResults[0])
        assertEquals("second", clipboardResults[1])
    }

    @Test
    fun `OSC 52 split across two buffers`() {
        val b64 = encode("split test")
        val full = "\u001b]52;c;$b64\u0007"
        val mid = full.length / 2
        val part1 = full.substring(0, mid).toByteArray()
        val part2 = full.substring(mid).toByteArray()

        val out1 = process(part1)
        assertEquals(0, out1.size)
        assertEquals(0, clipboardResults.size)

        val out2 = process(part2)
        assertEquals(0, out2.size)
        assertEquals(1, clipboardResults.size)
        assertEquals("split test", clipboardResults[0])
    }

    @Test
    fun `ST terminator split across buffers - ESC in first, backslash in second`() {
        val b64 = encode("st split")
        val part1 = "\u001b]52;c;$b64\u001b".toByteArray()
        val part2 = "\\".toByteArray()

        val out1 = process(part1)
        assertEquals(0, out1.size)
        assertEquals(0, clipboardResults.size)

        val out2 = process(part2)
        assertEquals(0, out2.size)
        assertEquals(1, clipboardResults.size)
        assertEquals("st split", clipboardResults[0])
    }

    @Test
    fun `incomplete OSC prefix flushes to output`() {
        val input = "\u001b]5X rest"
        val output = process(input)
        assertEquals(input, String(output))
        assertEquals(0, clipboardResults.size)
    }

    @Test
    fun `ESC followed by non-bracket flushes`() {
        val input = "\u001bA normal text"
        val output = process(input)
        assertEquals(input, String(output))
    }

    @Test
    fun `empty data payload is ignored - query request`() {
        val input = "\u001b]52;c;\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(0, clipboardResults.size)
    }

    @Test
    fun `selection parameter p works`() {
        val b64 = encode("primary")
        val input = "\u001b]52;p;$b64\u0007"
        process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals("primary", clipboardResults[0])
    }

    @Test
    fun `empty selection parameter works`() {
        val b64 = encode("no sel")
        val input = "\u001b]52;;$b64\u0007"
        process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals("no sel", clipboardResults[0])
    }

    @Test
    fun `payload exceeding 1MB cap aborts and flushes`() {
        val bigData = ByteArray(OscHandler.MAX_PAYLOAD_BYTES + 100) { 'A'.code.toByte() }
        val prefix = "\u001b]52;c;".toByteArray()
        val suffix = byteArrayOf(0x07)

        val input = prefix + bigData + suffix
        val output = ByteArray(input.size + 1024)
        val outLen = handler.process(input, 0, input.size, output)

        assertEquals(0, clipboardResults.size)
        assertTrue("Expected flushed bytes in output", outLen > 0)
    }

    @Test
    fun `offset parameter is respected`() {
        val b64 = encode("offset test")
        val payload = "\u001b]52;c;$b64\u0007".toByteArray()
        val padded = ByteArray(10) { 0x41 } + payload
        val output = ByteArray(padded.size + 1024)

        val outLen = handler.process(padded, 10, payload.size, output)

        assertEquals(0, outLen)
        assertEquals(1, clipboardResults.size)
        assertEquals("offset test", clipboardResults[0])
    }

    @Test
    fun `UTF-8 encoded payload decodes correctly`() {
        val text = "Hello \u00e9\u00e0\u00fc \u2603 \u2764"
        val b64 = encode(text)
        val input = "\u001b]52;c;$b64\u0007"
        process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals(text, clipboardResults[0])
    }

    @Test
    fun `normal escape sequences before and after OSC 52`() {
        val b64 = encode("copy this")
        val input = "\u001b[2J\u001b]52;c;$b64\u0007\u001b[1;1H"
        val output = process(input)

        assertEquals("\u001b[2J\u001b[1;1H", String(output))
        assertEquals(1, clipboardResults.size)
        assertEquals("copy this", clipboardResults[0])
    }

    // ========================================================================
    // OSC 7 — current working directory
    // ========================================================================

    @Test
    fun `OSC 7 sets CWD with BEL terminator`() {
        val input = "\u001b]7;file:///home/user\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, cwdResults.size)
        assertEquals("file:///home/user", cwdResults[0])
    }

    @Test
    fun `OSC 7 sets CWD with ST terminator`() {
        val input = "\u001b]7;file:///tmp\u001b\\"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, cwdResults.size)
        assertEquals("file:///tmp", cwdResults[0])
    }

    @Test
    fun `OSC 7 split across buffers`() {
        val full = "\u001b]7;file:///home/user/project\u0007"
        val mid = full.length / 2
        process(full.substring(0, mid).toByteArray())
        process(full.substring(mid).toByteArray())

        assertEquals(1, cwdResults.size)
        assertEquals("file:///home/user/project", cwdResults[0])
    }

    @Test
    fun `OSC 7 empty payload is ignored`() {
        val input = "\u001b]7;\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(0, cwdResults.size)
    }

    // ========================================================================
    // OSC 8 — hyperlinks
    // ========================================================================

    @Test
    fun `OSC 8 opens hyperlink`() {
        val input = "\u001b]8;;https://example.com\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, hyperlinkResults.size)
        assertEquals("https://example.com", hyperlinkResults[0])
    }

    @Test
    fun `OSC 8 closes hyperlink with empty URI`() {
        val input = "\u001b]8;;\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, hyperlinkResults.size)
        assertNull(hyperlinkResults[0])
    }

    @Test
    fun `OSC 8 open and close hyperlink with text between`() {
        val input = "\u001b]8;;https://example.com\u0007Click here\u001b]8;;\u0007"
        val output = process(input)

        assertEquals("Click here", String(output))
        assertEquals(2, hyperlinkResults.size)
        assertEquals("https://example.com", hyperlinkResults[0])
        assertNull(hyperlinkResults[1])
    }

    @Test
    fun `OSC 8 with params field`() {
        val input = "\u001b]8;id=link1;https://example.com\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, hyperlinkResults.size)
        assertEquals("https://example.com", hyperlinkResults[0])
    }

    @Test
    fun `OSC 8 with ST terminator`() {
        val input = "\u001b]8;;https://example.com\u001b\\"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, hyperlinkResults.size)
        assertEquals("https://example.com", hyperlinkResults[0])
    }

    // ========================================================================
    // OSC 9 — notifications (iTerm2-style)
    // ========================================================================

    @Test
    fun `OSC 9 fires notification`() {
        val input = "\u001b]9;Build complete\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, notificationResults.size)
        assertEquals("" to "Build complete", notificationResults[0])
    }

    @Test
    fun `OSC 9 empty body is ignored`() {
        val input = "\u001b]9;\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(0, notificationResults.size)
    }

    @Test
    fun `OSC 9 with ST terminator`() {
        val input = "\u001b]9;Tests passed\u001b\\"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, notificationResults.size)
        assertEquals("" to "Tests passed", notificationResults[0])
    }

    // ========================================================================
    // OSC 777 — notifications (rxvt-unicode-style)
    // ========================================================================

    @Test
    fun `OSC 777 notify fires notification with title and body`() {
        val input = "\u001b]777;notify;Build;Done successfully\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, notificationResults.size)
        assertEquals("Build" to "Done successfully", notificationResults[0])
    }

    @Test
    fun `OSC 777 non-notify subcommand is ignored`() {
        val input = "\u001b]777;other;foo;bar\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(0, notificationResults.size)
    }

    @Test
    fun `OSC 777 with semicolons in body`() {
        val input = "\u001b]777;notify;Alert;step 1;step 2;done\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, notificationResults.size)
        assertEquals("Alert" to "step 1;step 2;done", notificationResults[0])
    }

    @Test
    fun `OSC 777 missing body is ignored`() {
        val input = "\u001b]777;notify;Title\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(0, notificationResults.size)
    }

    @Test
    fun `OSC 777 with ST terminator`() {
        val input = "\u001b]777;notify;CI;Pipeline green\u001b\\"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(1, notificationResults.size)
        assertEquals("CI" to "Pipeline green", notificationResults[0])
    }

    // ========================================================================
    // Passthrough — unhandled OSC numbers
    // ========================================================================

    @Test
    fun `OSC 0 set title passes through unchanged`() {
        val input = "\u001b]0;my title\u0007"
        val output = process(input)
        assertEquals(input, String(output))
    }

    @Test
    fun `OSC 4 passes through unchanged`() {
        val input = "\u001b]4;1;rgb:ff/00/00\u0007"
        val output = process(input)
        assertEquals(input, String(output))
    }

    @Test
    fun `OSC 10 color set is stripped without a query reply`() {
        // OSC 10 is now handled (#867): a non-"?" payload (a color set) is
        // consumed, not passed through, and triggers no query callback.
        val esc = 27.toChar()
        val bel = 7.toChar()
        val output = process("$esc]10;#ffffff$bel")
        assertEquals(0, output.size)
        assertTrue(colorQueryResults.isEmpty())
    }

    @Test
    fun `OSC 0 with ST terminator passes through`() {
        val input = "\u001b]0;title\u001b\\"
        val output = process(input)
        assertEquals(input, String(output))
    }

    // ========================================================================
    // Mixed sequences
    // ========================================================================

    @Test
    fun `mixed OSC types in one buffer`() {
        val b64 = encode("copied")
        val input = "\u001b]7;file:///home\u0007" +
            "text" +
            "\u001b]52;c;$b64\u0007" +
            "\u001b]0;title\u0007" +
            "\u001b]9;done\u0007"
        val output = process(input)

        assertEquals("text\u001b]0;title\u0007", String(output))
        assertEquals(1, cwdResults.size)
        assertEquals("file:///home", cwdResults[0])
        assertEquals(1, clipboardResults.size)
        assertEquals("copied", clipboardResults[0])
        assertEquals(1, notificationResults.size)
        assertEquals("" to "done", notificationResults[0])
    }

    @Test
    fun `handled and passthrough OSC interleaved`() {
        val input = "\u001b]0;title1\u0007\u001b]7;file:///tmp\u0007\u001b]0;title2\u0007"
        val output = process(input)

        assertEquals("\u001b]0;title1\u0007\u001b]0;title2\u0007", String(output))
        assertEquals(1, cwdResults.size)
        assertEquals("file:///tmp", cwdResults[0])
    }

    @Test
    fun `OSC 8 hyperlink with visible text and surrounding content`() {
        val input = "Visit \u001b]8;;https://example.com\u0007here\u001b]8;;\u0007 for info"
        val output = process(input)

        assertEquals("Visit here for info", String(output))
        assertEquals(2, hyperlinkResults.size)
        assertEquals("https://example.com", hyperlinkResults[0])
        assertNull(hyperlinkResults[1])
    }

    // ========================================================================
    // Buffer-overflow regressions (#208 findings 6 & 18)
    // A malicious server can stream a handled-OSC payload (or a long OSC
    // number) across many small read chunks so the accumulating buffer far
    // exceeds the per-chunk output buffer, then send a terminator that flushes
    // it. Pre-fix the unchecked arraycopy threw ArrayIndexOutOfBounds on the
    // reader thread, killing the session output pipeline.
    // ========================================================================

    @Test
    fun `large OSC 52 payload streamed in chunks then aborted does not overflow`() {
        val payloadSize = 100_000
        // ESC ] 5 2 ; — enter PAYLOAD state for handled OSC 52.
        handler.process(byteArrayOf(0x1B, ']'.code.toByte(), '5'.code.toByte(), '2'.code.toByte(), ';'.code.toByte()), 0, 5)
        val chunk = ByteArray(1024) { 'A'.code.toByte() }
        var streamed = 0
        while (streamed < payloadSize) {
            val n = minOf(chunk.size, payloadSize - streamed)
            handler.process(chunk, 0, n)
            streamed += n
        }
        // ESC followed by a non-backslash aborts the sequence → flushAll() copies
        // the ~100 KB payloadBuf into the small chunk-sized outputBuf.
        handler.process(byteArrayOf(0x1B, 'X'.code.toByte()), 0, 2)
        // No exception thrown == fixed; the aborted payload is flushed to output.
        assertTrue(handler.outputLen >= payloadSize)
    }

    @Test
    fun `long OSC number streamed in chunks then invalid char does not overflow`() {
        // ESC ] → OSC_BRACKET.
        handler.process(byteArrayOf(0x1B, ']'.code.toByte()), 0, 2)
        val total = 100_000
        val digits = ByteArray(1024) { '9'.code.toByte() }
        var streamed = 0
        while (streamed < total) {
            val n = minOf(digits.size, total - streamed)
            handler.process(digits, 0, n)
            streamed += n
        }
        // A non-digit, non-';' char flushes the giant seqBuf into outputBuf.
        handler.process(byteArrayOf('Z'.code.toByte()), 0, 1)
        assertTrue(handler.outputLen >= total)
    }

    // ========================================================================
    // OSC 10 / 11 — foreground/background color queries (#867)
    // ========================================================================

    @Test
    fun `OSC 11 background query with BEL dispatches query and is stripped`() {
        val esc = 27.toChar()
        val bel = 7.toChar()
        val output = process("$esc]11;?$bel")
        assertEquals(listOf(11 to bel.toString()), colorQueryResults)
        assertEquals(0, output.size)
    }

    @Test
    fun `OSC 10 foreground query with ST dispatches query with ST terminator`() {
        val esc = 27.toChar()
        val st = "$esc\\"
        val output = process("$esc]10;?$st")
        assertEquals(listOf(10 to st), colorQueryResults)
        assertEquals(0, output.size)
    }

    @Test
    fun `OSC 11 color set is not a query`() {
        val esc = 27.toChar()
        val bel = 7.toChar()
        process("$esc]11;rgb:ffff/ffff/ffff$bel")
        assertTrue(colorQueryResults.isEmpty())
    }

    @Test
    fun `OSC 11 query split across chunks still dispatches`() {
        val esc = 27.toChar()
        val bel = 7.toChar()
        handler.process("$esc]11;".toByteArray(), 0, 5)
        val out = ByteArray(64)
        val tail = "?$bel".toByteArray()
        val len = handler.process(tail, 0, tail.size, out)
        assertEquals(listOf(11 to bel.toString()), colorQueryResults)
        assertEquals(0, len)
    }

    @Test
    fun `formatColorReply expands 8-bit channels to 16-bit`() {
        // #eff1f5 → rgb:efef/f1f1/f5f5 (the reporter's example).
        val reply = OscHandler.formatColorReply(11, 0xFFEFF1F5L, 7.toChar().toString())
        val esc = 27.toChar()
        assertEquals("$esc]11;rgb:efef/f1f1/f5f5${7.toChar()}", reply)
    }

    @Test
    fun `formatColorReply echoes ST terminator and handles black`() {
        val esc = 27.toChar()
        val st = "$esc\\"
        val reply = OscHandler.formatColorReply(10, 0xFF000000L, st)
        assertEquals("$esc]10;rgb:0000/0000/0000$st", reply)
    }
}
