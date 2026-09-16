package sh.haven.core.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the incremental SSE parser: line splitting at arbitrary
 * byte boundaries, `[DONE]`, keep-alive/comment lines, and the flush-at-EOF
 * contract [OpenAiClient.chatCompletionStream] relies on.
 */
class OpenAiSseParserTest {

    private fun chunkLine(delta: String, finish: String? = null): String {
        val obj = StringBuilder("""{"choices":[{"delta":{"content":"$delta"}""")
        if (finish != null) obj.append(""","finish_reason":"$finish"""")
        obj.append("}]}")
        return "data: $obj"
    }

    @Test
    fun `whole lines produce one chunk each`() {
        val parser = OpenAiSseParser()
        val out = parser.feed(chunkLine("Hel") + "\n" + chunkLine("lo") + "\n")
        assertEquals(listOf("Hel", "lo"), out.map { it.delta })
        assertFalse(parser.isDone)
    }

    @Test
    fun `split at newline boundary resumes without duplication`() {
        val parser = OpenAiSseParser()
        val first = parser.feed(chunkLine("Hel") + "\n")
        val second = parser.feed(chunkLine("lo") + "\n")
        assertEquals(listOf("Hel"), first.map { it.delta })
        assertEquals(listOf("lo"), second.map { it.delta })
    }

    @Test
    fun `split mid-line buffers until the newline arrives`() {
        val parser = OpenAiSseParser()
        val line = chunkLine("abc")
        val a = parser.feed(line.take(line.length / 2))
        val b = parser.feed(line.drop(line.length / 2) + "\n")
        assertEquals(0, a.size)
        assertEquals(listOf("abc"), b.map { it.delta })
    }

    @Test
    fun `crlf line endings are handled`() {
        val parser = OpenAiSseParser()
        val out = parser.feed(chunkLine("a") + "\r\n" + chunkLine("b") + "\r\n")
        assertEquals(listOf("a", "b"), out.map { it.delta })
    }

    @Test
    fun `DONE terminates and trailing lines are ignored`() {
        val parser = OpenAiSseParser()
        val out = parser.feed(chunkLine("x") + "\n" + "data: [DONE]\n" + chunkLine("y") + "\n")
        assertEquals(listOf("x"), out.map { it.delta })
        assertTrue(parser.isDone)
    }

    @Test
    fun `blank lines and comment keep-alives are skipped`() {
        val parser = OpenAiSseParser()
        val out = parser.feed("\n" + ": ping\n" + chunkLine("z") + "\n\n")
        assertEquals(listOf("z"), out.map { it.delta })
    }

    @Test
    fun `malformed json line is skipped not fatal`() {
        val parser = OpenAiSseParser()
        val out = parser.feed("data: {not json}\n" + chunkLine("ok") + "\n")
        assertEquals(listOf("ok"), out.map { it.delta })
    }

    @Test
    fun `eof flush emits nothing when the buffer is empty`() {
        val parser = OpenAiSseParser()
        parser.feed(chunkLine("x") + "\n")
        val flushed = parser.feed("", finished = true)
        assertEquals(0, flushed.size)
        // Only a `data: [DONE]` line sets isDone — EOF does not.
        assertFalse(parser.isDone)
    }

    @Test
    fun `eof flush of a trailing unterminated data line still parses it`() {
        val parser = OpenAiSseParser()
        val flushed = parser.feed(chunkLine("tail"), finished = true)
        assertEquals(listOf("tail"), flushed.map { it.delta })
    }

    @Test
    fun `finish_reason is carried on the chunk`() {
        val parser = OpenAiSseParser()
        val out = parser.feed(chunkLine("", finish = "stop") + "\n")
        assertEquals("stop", out.single().finishReason)
    }

    @Test
    fun `no content key parses as empty delta`() {
        val parser = OpenAiSseParser()
        val out = parser.feed("""data: {"choices":[{"delta":{},"finish_reason":null}]}""" + "\n")
        assertEquals(1, out.size)
        assertEquals("", out.single().delta)
        assertNull(out.single().finishReason)
    }

    @Test
    fun `explicit null content is empty not the string null`() {
        // llama-server forks announce the turn with {"delta":{"role":"assistant",
        // "content":null}}; org.json's optString renders JSONObject.NULL as the
        // literal "null", which used to stream into the bubble as text.
        val parser = OpenAiSseParser()
        val out = parser.feed(
            """data: {"choices":[{"finish_reason":null,"delta":{"role":"assistant","content":null}}]}""" + "\n" +
                """data: {"choices":[{"delta":{"content":"Hel"}}]}""" + "\n" +
                """data: {"choices":[{"finish_reason":"stop","delta":{"content":"lo"}}]}""" + "\n",
        )
        assertEquals(listOf("", "Hel", "lo"), out.map { it.delta })
        assertTrue("no literal \"null\" may appear in deltas", out.none { it.delta == "null" })
        assertEquals("stop", out.last().finishReason)
    }

    @Test
    fun `optTextField maps JSON null to empty on every org json build`() {
        // The JVM and Android org.json forks disagree here: Android's optString
        // renders JSONObject.NULL as the literal "null", the JVM one returns the
        // fallback. The helper must bypass optString entirely.
        val obj = JSONObject("""{"a":null,"b":"x"}""")
        assertEquals("", optTextField(obj, "a"))
        assertEquals("", optTextField(obj, "missing"))
        assertEquals("x", optTextField(obj, "b"))
        assertEquals("", optTextField(null, "b"))
    }
}