package sh.haven.core.openai

import org.json.JSONObject

/**
 * The contract every protocol's stream parser exposes to the HTTP read loop
 * (see [OpenAiClient.chatCompletionStream]).
 */
interface ChatStreamParser {
    val isDone: Boolean
    fun feed(text: String, finished: Boolean = false): List<ChatChunk>
}

/**
 * Read a JSON string field as text, treating an explicit JSON null as "".
 * Android's org.json renders JSONObject.NULL through optString() as the
 * literal "null" (the JVM implementation returns the fallback), so any
 * field a server may set to null — e.g. the role-announcement chunk
 * `{"delta":{"role":"assistant","content":null}}` — must go through this.
 */
internal fun optTextField(obj: JSONObject?, key: String): String {
    val v = obj?.opt(key) ?: return ""
    return if (v === JSONObject.NULL) "" else v.toString()
}

/**
 * Incremental parser for the `text/event-stream` body of a streaming
 * `POST /v1/chat/completions` (`stream: true`).
 *
 * Feed decoded text as it arrives — the parser keeps state across calls, so
 * lines split mid-JSON by TCP packet boundaries are fine. Emits chunks only
 * on complete `data:` lines; blank lines (SSE keep-alives / comment `:` lines)
 * are ignored.
 */
class OpenAiSseParser : ChatStreamParser {
    private val lineBuffer = StringBuilder()
    private var done = false

    override val isDone: Boolean get() = done

    /**
     * Feed newly arrived text; returns the chunks complete lines parse to.
     * [finished] marks EOF (end of body) so a trailing unterminated line is
     * still flushed.
     */
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
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return emptyList() // keep-alive / SSE comment
        if (!trimmed.startsWith("data:")) return emptyList() // non-data event fields we don't use
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            done = true
            return emptyList()
        }
        val chunk = parseChunkPayload(payload) ?: return emptyList()
        return listOf(chunk)
    }

    /**
     * Parse one `data:` payload into a [ChatChunk]. Returns null for shapes
     * that carry no text (role-only chunks) or that we can't parse — servers
     * that add extra fields keep working; malformed lines are skipped rather
     * than aborting the stream.
     */
    private fun parseChunkPayload(payload: String): ChatChunk? = try {
        val json = JSONObject(payload)
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.getJSONObject(0)
        val delta = choice.optJSONObject("delta")
        ChatChunk(
            delta = optTextField(delta, "content"),
            finishReason = optTextField(choice, "finish_reason").takeIf { it.isNotEmpty() && it != "null" },
        )
    } catch (_: Exception) {
        null
    }
}