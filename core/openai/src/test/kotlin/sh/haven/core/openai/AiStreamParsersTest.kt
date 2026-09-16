package sh.haven.core.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Incremental-parse contracts for the three non-OpenAI wire formats, mirroring
 * [OpenAiSseParserTest]: whole lines, splits mid-record, end markers, and
 * malformed records skipped rather than fatal.
 */
class AiStreamParsersTest {

    // ---- OllamaStreamParser ----

    @Test
    fun `ollama parser emits deltas and stops on done`() {
        val p = OllamaStreamParser()
        val chunks = p.feed(
            """{"message":{"role":"assistant","content":"Hel"},"done":false}
""" +
                """{"message":{"content":"lo"},"done":true}""" + "\n",
        )
        assertEquals("Hello", chunks.joinToString("") { it.delta })
        assertTrue(p.isDone)
        assertEquals("stop", chunks.last().finishReason)
    }

    @Test
    fun `ollama parser buffers records split across feeds`() {
        val p = OllamaStreamParser()
        val first = p.feed("""{"message":{"content":"ab""") // no newline yet
        assertTrue(first.isEmpty())
        val second = p.feed("""c"},"done":false}""" + "\n")
        assertEquals(listOf("abc"), second.map { it.delta })
        assertFalse(p.isDone)
    }

    @Test
    fun `ollama parser skips malformed and empty lines`() {
        val p = OllamaStreamParser()
        val chunks = p.feed("not json\n\n" + """{"message":{"content":"x"},"done":false}""" + "\n")
        assertEquals(listOf("x"), chunks.map { it.delta })
        assertFalse(p.isDone)
    }

    // ---- AnthropicSseParser ----

    @Test
    fun `anthropic parser emits text deltas and stops on message_stop`() {
        val p = AnthropicSseParser()
        val chunks = p.feed(
            """event: message_start
data: {"type":"message_start","message":{}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

event: message_stop
data: {"type":"message_stop"}
""",
        )
        assertEquals(listOf("Hi"), chunks.map { it.delta })
        assertTrue(p.isDone)
    }

    @Test
    fun `anthropic parser ignores non-text deltas and thinking blocks`() {
        val p = AnthropicSseParser()
        val chunks = p.feed(
            """data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hmm"}}

data: {"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{}"}}

data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}

""",
        )
        assertEquals(listOf("ok"), chunks.map { it.delta })
    }

    @Test
    fun `anthropic parser handles data split across feeds`() {
        val p = AnthropicSseParser()
        assertTrue(p.feed("""data: {"type":"content_block_del""").isEmpty())
        val chunks = p.feed("""ta","delta":{"type":"text_delta","text":"tail"}}

""" + "data: {\"type\":\"message_stop\"}\n")
        assertEquals(listOf("tail"), chunks.map { it.delta })
        assertTrue(p.isDone)
    }

    // ---- GeminiSseParser ----

    @Test
    fun `gemini parser emits parts text and stops on finishReason`() {
        val p = GeminiSseParser()
        val chunks = p.feed(
            """data: {"candidates":[{"content":{"parts":[{"text":"Ge"}]}}]}

data: {"candidates":[{"content":{"parts":[{"text":"t"},{"text":" it"}]},"finishReason":"STOP"}]}

""",
        )
        assertEquals("Get it", chunks.joinToString("") { it.delta })
        assertTrue(p.isDone)
        assertEquals("stop", chunks.last().finishReason)
    }

    @Test
    fun `gemini parser ignores non-data and malformed records`() {
        val p = GeminiSseParser()
        val chunks = p.feed(": keepalive\n\ndata: not json\n\n" +
            """data: {"candidates":[{"content":{"parts":[{"text":"x"}]}}]}""" + "\n")
        assertEquals(listOf("x"), chunks.map { it.delta })
        assertFalse(p.isDone)
    }
}