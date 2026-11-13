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
    private var callSeq = 0

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
            val msg = json.optJSONObject("message")
            val content = optTextField(msg, "content")
            val calls = parseOllamaToolCalls(msg) { "ollama_call_${++callSeq}" }
            val finish = if (json.optBoolean("done", false)) {
                done = true
                if (calls.isNotEmpty()) "tool_calls" else "stop"
            } else null
            ChatChunk(delta = content, finishReason = finish, toolCalls = calls)
        } catch (_: Exception) {
            return emptyList()
        }
        return if (chunk.delta.isEmpty() && chunk.finishReason == null && chunk.toolCalls.isEmpty()) {
            emptyList()
        } else {
            listOf(chunk)
        }
    }
}

internal fun parseOllamaToolCalls(msg: JSONObject?, nextId: () -> String): List<ChatToolCall> {
    val arr = msg?.optJSONArray("tool_calls") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: continue
            val name = optTextField(fn, "name")
            if (name.isEmpty()) continue
            val argsObj = fn.opt("arguments")
            val argsJson = when (argsObj) {
                is JSONObject -> argsObj.toString()
                is String -> argsObj.ifBlank { "{}" }
                else -> "{}"
            }
            val id = optTextField(tc, "id").ifEmpty { nextId() }
            add(ChatToolCall(id = id, name = name, argumentsJson = argsJson))
        }
    }
}

/**
 * Anthropic Messages SSE: `event:`/`data:` pairs; text arrives in
 * `content_block_delta` events, `tool_use` blocks stream via
 * `content_block_start` + `input_json_delta`, and the stream ends on `message_stop`.
 */
class AnthropicSseParser : ChatStreamParser {
    private data class PendingToolUse(
        val id: String,
        val name: String,
        val initialJson: String,
        val partialJson: StringBuilder = StringBuilder(),
    )

    private val lineBuffer = StringBuilder()
    private val pendingToolUses = LinkedHashMap<Int, PendingToolUse>()
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
                    val remaining = flushToolUses()
                    if (remaining.isNotEmpty()) {
                        chunks += ChatChunk(delta = "", finishReason = "tool_calls", toolCalls = remaining)
                    }
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
            val remaining = flushToolUses()
            return if (remaining.isNotEmpty()) {
                listOf(ChatChunk(delta = "", finishReason = "tool_calls", toolCalls = remaining))
            } else {
                emptyList()
            }
        }
        return try {
            val json = JSONObject(payload)
            when (json.optString("type")) {
                "content_block_start" -> {
                    val block = json.optJSONObject("content_block")
                    if (block != null && optTextField(block, "type") == "tool_use") {
                        val index = json.optInt("index", pendingToolUses.size)
                        val id = optTextField(block, "id").ifEmpty { "toolu_$index" }
                        val name = optTextField(block, "name")
                        val inputObj = block.optJSONObject("input")
                        val initJson = if (inputObj != null && inputObj.length() > 0) inputObj.toString() else ""
                        if (name.isNotEmpty()) {
                            pendingToolUses[index] = PendingToolUse(id = id, name = name, initialJson = initJson)
                        }
                    }
                    emptyList()
                }
                "content_block_delta" -> {
                    val delta = json.optJSONObject("delta")
                    when (optTextField(delta, "type")) {
                        "text_delta" -> {
                            val text = optTextField(delta, "text")
                            if (text.isNotEmpty()) listOf(ChatChunk(delta = text, finishReason = null)) else emptyList()
                        }
                        "input_json_delta" -> {
                            val index = json.optInt("index", -1)
                            val slot = pendingToolUses[index]
                            if (slot != null) {
                                slot.partialJson.append(optTextField(delta, "partial_json"))
                            }
                            emptyList()
                        }
                        else -> emptyList()
                    }
                }
                "message_stop" -> {
                    done = true
                    val calls = flushToolUses()
                    if (calls.isNotEmpty()) {
                        listOf(ChatChunk(delta = "", finishReason = "tool_calls", toolCalls = calls))
                    } else {
                        emptyList()
                    }
                }
                else -> emptyList() // message_start/message_delta/ping/…
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun flushToolUses(): List<ChatToolCall> {
        if (pendingToolUses.isEmpty()) return emptyList()
        val out = pendingToolUses.values.map { slot ->
            val args = slot.partialJson.toString().ifBlank { slot.initialJson.ifBlank { "{}" } }
            ChatToolCall(id = slot.id, name = slot.name, argumentsJson = args)
        }
        pendingToolUses.clear()
        return out
    }
}

/**
 * Gemini `:streamGenerateContent?alt=sse`: each `data:` record is a full
 * GenerateContentResponse; text is `candidates[0].content.parts[*].text`,
 * tool calls are `parts[*].functionCall`, and the last record carries `finishReason`.
 */
class GeminiSseParser : ChatStreamParser {
    private val lineBuffer = StringBuilder()
    private var done = false
    private var callSeq = 0

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
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            val text = parts
                ?.let { p -> (0 until p.length()).joinToString("") { optTextField(p.getJSONObject(it), "text") } }
                .orEmpty()
            val calls = parseGeminiFunctionCalls(parts) { "gemini_call_${++callSeq}" }
            val finish = optTextField(candidate, "finishReason")
            if (finish.isNotEmpty()) done = true
            if (text.isEmpty() && finish.isEmpty() && calls.isEmpty()) emptyList()
            else {
                val reason = when {
                    calls.isNotEmpty() && finish.isNotEmpty() -> "tool_calls"
                    finish.isNotEmpty() -> "stop"
                    else -> null
                }
                listOf(ChatChunk(delta = text, finishReason = reason, toolCalls = calls))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

internal fun parseGeminiFunctionCalls(parts: org.json.JSONArray?, nextId: () -> String): List<ChatToolCall> {
    parts ?: return emptyList()
    return buildList {
        for (i in 0 until parts.length()) {
            val fc = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
            val name = optTextField(fc, "name")
            if (name.isEmpty()) continue
            val args = fc.optJSONObject("args")?.toString() ?: "{}"
            add(ChatToolCall(id = nextId(), name = name, argumentsJson = args))
        }
    }
}
