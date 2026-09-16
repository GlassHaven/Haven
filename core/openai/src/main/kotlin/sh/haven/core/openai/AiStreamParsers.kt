package sh.haven.core.openai

import org.json.JSONObject

/**
 * Incremental stream parsers for the non-OpenAI wire formats, each with the
 * same contract as [OpenAiSseParser]: feed text as it arrives (splits across
 * feed calls are fine), get [ChatChunk]s for complete records, [isDone] flips
 * on the protocol's end marker, and [feed]`("", finished = true)` flushes a
 * trailing unterminated record. Malformed records are skipped, never fatal.
 */

/** Ollama `/api/chat` NDJSON: one JSON object per line. */
class OllamaStreamParser : ChatStreamParser {
    private val lineBuffer = StringBuilder()
    private var done = false

    override val isDone: Boolean get() = done

    override fun feed(text: String, finished: Boolean): List<ChatChunk> {
        if (done) return emptyList()
        lineBuffer.append(text)
        val chunks = mutableListOf<ChatChunk>()
        while (true) {
            val nl = lineBuffer.indexOf('\n')
            if (nl < 0) {
                if (finished) {
                    chunks += parseLine(lineBuffer.toString())
                    lineBuffer.setLength(0)
                }
                return chunks
            }
            val line = lineBuffer.substring(0, nl).trimEnd('\r')
            lineBuffer.delete(0, nl + 1)
            chunks += parseLine(line)
            if (done) return chunks
        }
    }

    private fun parseLine(line: String): List<ChatChunk> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val chunk = try {
            val json = JSONObject(trimmed)
            val content = optTextField(json.optJSONObject("message"), "content")
            val finish = if (json.optBoolean("done", false)) {
                done = true
                "stop"
            } else null
            ChatChunk(delta = content, finishReason = finish)
        } catch (_: Exception) {
            return emptyList()
        }
        return if (chunk.delta.isEmpty() && chunk.finishReason == null) emptyList() else listOf(chunk)
    }
}

/**
 * Anthropic Messages SSE: `event:`/`data:` pairs; text arrives in
 * `content_block_delta` events, the stream ends on `message_stop`.
 */
class AnthropicSseParser : ChatStreamParser {
    private val lineBuffer = StringBuilder()
    private var done = false

    override val isDone: Boolean get() = done

    override fun feed(text: String, finished: Boolean): List<ChatChunk> {
        if (done) return emptyList()
        lineBuffer.append(text)
        val chunks = mutableListOf<ChatChunk>()
        while (true) {
            val nl = lineBuffer.indexOf('\n')
            if (nl < 0) {
                if (finished) {
                    chunks += parseLine(lineBuffer.toString())
                    lineBuffer.setLength(0)
                }
                return chunks
            }
            val line = lineBuffer.substring(0, nl).trimEnd('\r')
            lineBuffer.delete(0, nl + 1)
            chunks += parseLine(line)
            if (done) return chunks
        }
    }

    private fun parseLine(line: String): List<ChatChunk> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return emptyList()
        if (!trimmed.startsWith("data:")) return emptyList() // event:/ping lines we don't use
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            done = true
            return emptyList()
        }
        return try {
            val json = JSONObject(payload)
            when (json.optString("type")) {
                "content_block_delta" -> {
                    val delta = json.optJSONObject("delta")
                    val text = optTextField(delta, "text")
                    if (delta != null && optTextField(delta, "type") == "text_delta" && text.isNotEmpty()) {
                        listOf(ChatChunk(delta = text, finishReason = null))
                    } else {
                        emptyList()
                    }
                }
                "message_stop" -> {
                    done = true
                    emptyList()
                }
                else -> emptyList() // message_start/content_block_start/ping/… carry no text
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Gemini `:streamGenerateContent?alt=sse`: each `data:` record is a full
 * GenerateContentResponse; text is `candidates[0].content.parts[*].text` and
 * the last record carries `finishReason`.
 */
class GeminiSseParser : ChatStreamParser {
    private val lineBuffer = StringBuilder()
    private var done = false

    override val isDone: Boolean get() = done

    override fun feed(text: String, finished: Boolean): List<ChatChunk> {
        if (done) return emptyList()
        lineBuffer.append(text)
        val chunks = mutableListOf<ChatChunk>()
        while (true) {
            val nl = lineBuffer.indexOf('\n')
            if (nl < 0) {
                if (finished) {
                    chunks += parseLine(lineBuffer.toString())
                    lineBuffer.setLength(0)
                }
                return chunks
            }
            val line = lineBuffer.substring(0, nl).trimEnd('\r')
            lineBuffer.delete(0, nl + 1)
            chunks += parseLine(line)
            if (done) return chunks
        }
    }

    private fun parseLine(line: String): List<ChatChunk> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return emptyList()
        if (!trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            done = true
            return emptyList()
        }
        return try {
            val json = JSONObject(payload)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val text = candidate?.optJSONObject("content")?.optJSONArray("parts")
                ?.let { parts -> (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") } }
                .orEmpty()
            val finish = optTextField(candidate, "finishReason")
            if (finish.isNotEmpty()) done = true
            if (text.isEmpty() && finish.isEmpty()) emptyList()
            else listOf(ChatChunk(delta = text, finishReason = if (finish.isNotEmpty()) "stop" else null))
        } catch (_: Exception) {
            emptyList()
        }
    }
}