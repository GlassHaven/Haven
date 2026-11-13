package sh.haven.core.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * HTTP client for AI endpoints. [AiProtocol] selects the wire format: the
 * OpenAI-compatible default (`/v1/chat/completions` + SSE) plus Ollama's
 * native `/api`, Anthropic Messages and Gemini. All protocols reduce to the
 * same [ModelInfo]/[ChatChunk]/text surface so session tracking, the chat UI
 * and the MCP tools are protocol-agnostic.
 *
 * Tunnel routing: pass the [SocketFactory] from `TunnelResolver.socketFactory`
 * (null = direct). The base URL stays exactly as saved — the factory
 * intercepts socket creation, so `http://127.0.0.1:8317` over a tunnel dials
 * the tunnel's far end, and no URL rewriting is involved.
 *
 * OkHttp (not HttpURLConnection) because the JDK client cannot accept a
 * SocketFactory for plain `http:` — see the module comment in build.gradle.kts.
 */
@Singleton
class OpenAiClient @Inject constructor() {

    /**
     * One client per profile (tunnel factories differ per profile); built via
     * [buildClient] rather than injected so tests can supply their own.
     */
    fun buildClient(factory: SocketFactory?): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Streaming bodies are read for as long as the server sends; per
            // -call readTimeout() overrides below cover finite requests.
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (factory != null) {
                    socketFactory(factory)
                    // Userspace tunnel sockets ignore setSoTimeout, which makes
                    // OkHttp's 1 ms RealConnection.isHealthy probe block forever
                    // after >= 10 s idle between chat turns. Disable idle pooling
                    // and avoid leaking internal tunnel hostnames to system DNS.
                    connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                    dns { hostname -> placeholderOrLiteralAddresses(hostname) }
                }
            }
            .build()

    private fun placeholderOrLiteralAddresses(hostname: String): List<java.net.InetAddress> {
        val trimmed = hostname.trim().removeSurrounding("[", "]")
        if (trimmed.equals("localhost", ignoreCase = true)) {
            return listOf(
                java.net.InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)),
                java.net.InetAddress.getByAddress("localhost", ByteArray(16).apply { this[15] = 1 }),
            )
        }
        val ipv4Parts = trimmed.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } }) {
            val nums = ipv4Parts.map { it.toInt() }
            if (nums.all { it in 0..255 }) {
                return listOf(
                    java.net.InetAddress.getByAddress(
                        trimmed,
                        byteArrayOf(nums[0].toByte(), nums[1].toByte(), nums[2].toByte(), nums[3].toByte()),
                    ),
                )
            }
        }
        if (trimmed.contains(':')) {
            return try {
                listOf(java.net.InetAddress.getByName(trimmed))
            } catch (_: java.net.UnknownHostException) {
                listOf(java.net.InetAddress.getByAddress(hostname, ByteArray(4)))
            }
        }
        return listOf(java.net.InetAddress.getByAddress(hostname, ByteArray(4)))
    }

    private fun Request.Builder.auth(apiKey: String?, protocol: AiProtocol): Request.Builder {
        if (apiKey.isNullOrBlank() && protocol != AiProtocol.GEMINI) return this
        return when (protocol) {
            // Ollama is keyless by default; a Bearer header is harmless if set.
            AiProtocol.OPENAI, AiProtocol.OLLAMA ->
                if (apiKey.isNullOrBlank()) this else header("Authorization", "Bearer $apiKey")
            AiProtocol.ANTHROPIC -> header("x-api-key", apiKey.orEmpty())
                .header("anthropic-version", "2023-06-01")
            AiProtocol.GEMINI -> header("x-goog-api-key", apiKey.orEmpty())
        }
    }

    private fun baseUrl(base: String, prefix: String?, protocol: AiProtocol = AiProtocol.OPENAI): String {
        var b = base.trim().trimEnd('/')
        val normPrefix = prefix?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "/$it" }
        if (normPrefix != null && !b.endsWith(normPrefix) && !b.endsWith("$normPrefix/v1")) {
            b += normPrefix
        }
        val versionSegment = when (protocol) {
            AiProtocol.OPENAI, AiProtocol.ANTHROPIC -> "/v1"
            AiProtocol.OLLAMA -> "/api"
            AiProtocol.GEMINI -> "/v1beta"
        }
        if (b.endsWith(versionSegment)) {
            b = b.removeSuffix(versionSegment)
        }
        return b
    }

    fun modelsUrl(base: String, prefix: String?, protocol: AiProtocol = AiProtocol.OPENAI): String =
        baseUrl(base, prefix, protocol) + when (protocol) {
            AiProtocol.OPENAI, AiProtocol.ANTHROPIC -> "/v1/models"
            AiProtocol.OLLAMA -> "/api/tags"
            AiProtocol.GEMINI -> "/v1beta/models"
        }

    fun chatUrl(base: String, prefix: String?, protocol: AiProtocol = AiProtocol.OPENAI): String =
        chatUrlFor(base, prefix, protocol, model = "", stream = false)

    private fun chatUrlFor(base: String, prefix: String?, protocol: AiProtocol, model: String, stream: Boolean): String =
        baseUrl(base, prefix, protocol) + when (protocol) {
            AiProtocol.OPENAI -> "/v1/chat/completions"
            AiProtocol.OLLAMA -> "/api/chat"
            AiProtocol.ANTHROPIC -> "/v1/messages"
            // Gemini carries the model in the path; the stream uses the SSE alt.
            // /v1beta/models returns names like "models/gemini-2.5-flash" —
            // don't let the stored id double the segment.
            AiProtocol.GEMINI ->
                "/v1beta/models/${model.removePrefix("models/")}:" +
                    if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        }

    /**
     * Connect-time probe: `GET /v1/models`. Maps the failure modes the connect
     * flow needs to distinguish (bad key → Auth; wrong prefix → Http; tunnel
     * down → Network) into [OpenAiException] subtypes.
     */
    suspend fun verify(
        client: OkHttpClient,
        base: String,
        prefix: String?,
        apiKey: String?,
        protocol: AiProtocol = AiProtocol.OPENAI,
    ): VerifyResult =
        try {
            val (code, body) = execute(
                client.newBuilder().readTimeout(15, TimeUnit.SECONDS).build(),
                modelsUrl(base, prefix, protocol),
                apiKey,
                protocol = protocol,
            )
            when {
                code == 401 || code == 403 -> VerifyResult.Failure(
                    OpenAiException.Auth("Endpoint rejected the API key (HTTP $code)"),
                )
                code == 404 -> VerifyResult.Failure(
                    OpenAiException.Http(code, "Endpoint has no /v1/models (wrong path prefix?)"),
                )
                code in 400..499 -> VerifyResult.Failure(OpenAiException.Http(code, "HTTP $code from $base"))
                code !in 200..299 -> VerifyResult.Failure(OpenAiException.Http(code, "HTTP $code from $base"))
                else -> try {
                    val models = parseModels(body, protocol)
                    VerifyResult.Ok(models)
                } catch (e: Exception) {
                    VerifyResult.Failure(OpenAiException.BadResponse("Unparseable models body: ${e.message}"))
                }
            }
        } catch (e: IOException) {
            VerifyResult.Failure(OpenAiException.Network("Could not reach $base: ${e.message}", e))
        }

    suspend fun listModels(
        client: OkHttpClient,
        base: String,
        prefix: String?,
        apiKey: String?,
        protocol: AiProtocol = AiProtocol.OPENAI,
    ): List<ModelInfo> {
        val (code, body) = execute(
            client.newBuilder().readTimeout(15, TimeUnit.SECONDS).build(),
            modelsUrl(base, prefix, protocol),
            apiKey,
            protocol = protocol,
        )
        if (code !in 200..299) throw mapHttpError(code, body)
        return parseModels(body, protocol)
    }

    /** Non-streaming one-shot completion (the MCP `openai_chat` path). */
    suspend fun chatCompletion(
        client: OkHttpClient,
        base: String,
        prefix: String?,
        apiKey: String?,
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        protocol: AiProtocol = AiProtocol.OPENAI,
        tools: List<ChatToolSpec> = emptyList(),
    ): String {
        val url = chatUrlFor(base, prefix, protocol, model, stream = false)
        val (code, body) = execute(
            client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build(),
            url,
            apiKey,
            chatPayload(model, messages, maxTokens, temperature, protocol, stream = false, tools = tools),
            protocol = protocol,
        )
        if (code !in 200..299) throw mapHttpError(code, body)
        return parseNonStreamingChunk(body, protocol).delta
    }

    /**
     * Streaming completion as a cold [Flow]. Collect on any dispatcher (the
     * reads happen on OkHttp's thread); cancelling the collector cancels the
     * HTTP call via [awaitClose]. Falls back to a single chunk when the server
     * answers `stream: true` with a plain JSON body (some servers ignore it).
     */
    fun chatCompletionStream(
        client: OkHttpClient,
        base: String,
        prefix: String?,
        apiKey: String?,
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        protocol: AiProtocol = AiProtocol.OPENAI,
        tools: List<ChatToolSpec> = emptyList(),
    ): Flow<ChatChunk> = callbackFlow {
        val payload = chatPayload(model, messages, maxTokens, temperature, protocol, stream = true, tools = tools)
        val request = Request.Builder()
            .url(chatUrlFor(base, prefix, protocol, model, stream = true))
            .auth(apiKey, protocol)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) close(OpenAiException.Network("Request failed: ${e.message}", e))
                else close()
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                if (code !in 200..299) {
                    val errBody = response.body?.string().orEmpty()
                    response.close()
                    if (!call.isCanceled()) close(mapHttpError(code, errBody)) else close()
                    return
                }
                val contentType = response.header("Content-Type").orEmpty()
                try {
                    response.use { resp ->
                        val source = resp.body?.source() ?: run { close(); return }
                        if (contentType.contains("json") && !contentType.contains("event-stream") &&
                            !contentType.contains("ndjson") && protocol != AiProtocol.GEMINI
                        ) {
                            // Server ignored `stream: true` — one-shot JSON body.
                            val chunk = parseNonStreamingChunk(source.readUtf8(), protocol)
                            if (!call.isCanceled()) trySend(chunk)
                        } else {
                            val parser = newStreamParser(protocol)
                            while (!call.isCanceled() && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                parser.feed(line + "\n").forEach { trySend(it) }
                                if (parser.isDone) break
                            }
                            if (!call.isCanceled() && !parser.isDone) {
                                parser.feed("", finished = true).forEach { trySend(it) }
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (!call.isCanceled()) close(OpenAiException.Network("Stream read failed: ${e.message}", e))
                } finally {
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private suspend fun execute(
        client: OkHttpClient,
        url: String,
        apiKey: String?,
        postBody: String? = null,
        protocol: AiProtocol = AiProtocol.OPENAI,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        val request = if (postBody != null) {
            builder.auth(apiKey, protocol).post(postBody.toRequestBody("application/json".toMediaType())).build()
        } else {
            builder.auth(apiKey, protocol).get().build()
        }
        client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }

    /**
     * Build the request body for [protocol]. The OpenAI and Ollama shapes are
     * message-lists; Anthropic takes `system` as a top-level field and
     * requires `max_tokens`; Gemini nests turns as `contents`/`parts`.
     */
    private fun chatPayload(
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int?,
        temperature: Double?,
        protocol: AiProtocol,
        stream: Boolean,
        tools: List<ChatToolSpec> = emptyList(),
    ): String = when (protocol) {
        AiProtocol.OPENAI -> JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                messages.forEach { put(openAiMessageJson(it)) }
            })
            if (tools.isNotEmpty()) {
                put("tools", openAiToolsJson(tools))
            }
            put("stream", stream)
            maxTokens?.let { put("max_tokens", it) }
            temperature?.let { put("temperature", it) }
        }.toString()
        AiProtocol.OLLAMA -> JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                messages.forEach { m -> put(ollamaMessageJson(m)) }
            })
            if (tools.isNotEmpty()) {
                put("tools", openAiToolsJson(tools))
            }
            put("stream", stream)
            if (maxTokens != null || temperature != null) {
                put("options", JSONObject().apply {
                    maxTokens?.let { put("num_predict", it) }
                    temperature?.let { put("temperature", it) }
                })
            }
        }.toString()
        AiProtocol.ANTHROPIC -> JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens ?: 1024) // Anthropic requires the field
            val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
            if (system.isNotEmpty()) put("system", system)
            put("messages", anthropicMessagesJson(messages))
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { t ->
                        put(
                            JSONObject()
                                .put("name", t.name)
                                .put("description", t.description)
                                .put("input_schema", parseSchemaJson(t.inputSchemaJson)),
                        )
                    }
                })
            }
            put("stream", stream)
            temperature?.let { put("temperature", it) }
        }.toString()
        AiProtocol.GEMINI -> JSONObject().apply {
            val system = messages.filter { it.role == "system" }
            if (system.isNotEmpty()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().apply {
                    system.forEach { put(JSONObject().put("text", it.content)) }
                }))
            }
            put("contents", geminiContentsJson(messages))
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put(
                            "functionDeclarations",
                            JSONArray().apply {
                                tools.forEach { t ->
                                    put(
                                        JSONObject()
                                            .put("name", t.name)
                                            .put("description", t.description)
                                            .put("parameters", sanitizeGeminiSchema(parseSchemaJson(t.inputSchemaJson))),
                                    )
                                }
                            },
                        ),
                    ),
                )
            }
            if (maxTokens != null || temperature != null) {
                put("generationConfig", JSONObject().apply {
                    maxTokens?.let { put("maxOutputTokens", it) }
                    temperature?.let { put("temperature", it) }
                })
            }
        }.toString()
    }

    private fun openAiToolsJson(tools: List<ChatToolSpec>): JSONArray = JSONArray().apply {
        tools.forEach { t ->
            put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", parseSchemaJson(t.inputSchemaJson)),
                    ),
            )
        }
    }

    private fun parseSchemaJson(raw: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("type", "object") }

    private fun parseArgsObject(raw: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrElse { JSONObject() }

    /**
     * Gemini `/v1beta` rejects `$schema` and `additionalProperties` inside
     * `functionDeclarations.parameters`. Strip unsupported keys recursively.
     */
    private fun sanitizeGeminiSchema(node: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "\$schema" || key == "additionalProperties") continue
            when (val v = node.opt(key)) {
                is JSONObject -> out.put(key, sanitizeGeminiSchema(v))
                is JSONArray -> out.put(
                    key,
                    JSONArray().apply {
                        for (i in 0 until v.length()) {
                            val elem = v.opt(i)
                            put(if (elem is JSONObject) sanitizeGeminiSchema(elem) else elem)
                        }
                    },
                )
                else -> out.put(key, v)
            }
        }
        return out
    }

    /**
     * OpenAI message: `content` stays a plain string for text-only turns
     * (maximum server compatibility); with images it becomes a typed parts
     * array with data: URLs; assistant tool-call turns emit `tool_calls` and
     * tool results emit `tool_call_id` (#807).
     */
    private fun openAiMessageJson(m: ChatMessage): JSONObject {
        if (m.role == "tool") {
            return JSONObject()
                .put("role", "tool")
                .put("tool_call_id", m.toolCallId.orEmpty())
                .put("content", m.content)
        }
        val obj = JSONObject().put("role", m.role)
        if (m.toolCalls.isNotEmpty()) {
            obj.put("content", if (m.content.isEmpty()) JSONObject.NULL else m.content)
            obj.put(
                "tool_calls",
                JSONArray().apply {
                    m.toolCalls.forEach { tc ->
                        put(
                            JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.argumentsJson),
                                ),
                        )
                    }
                },
            )
            return obj
        }
        if (m.images.isEmpty()) {
            obj.put("content", m.content)
        } else {
            obj.put("content", JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", m.content))
                m.images.forEach { img ->
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:${img.mimeType};base64,${img.base64}")),
                    )
                }
            })
        }
        return obj
    }

    private fun ollamaMessageJson(m: ChatMessage): JSONObject {
        if (m.role == "tool") {
            return JSONObject()
                .put("role", "tool")
                .put("content", m.content)
                .apply {
                    if (!m.toolName.isNullOrBlank()) put("tool_name", m.toolName)
                }
        }
        val obj = JSONObject().put("role", m.role).put("content", m.content)
        if (m.images.isNotEmpty()) {
            // Ollama's native shape: raw base64 strings, no data: prefix.
            obj.put("images", JSONArray().apply { m.images.forEach { put(it.base64) } })
        }
        if (m.toolCalls.isNotEmpty()) {
            obj.put(
                "tool_calls",
                JSONArray().apply {
                    m.toolCalls.forEach { tc ->
                        put(
                            JSONObject().put(
                                "function",
                                JSONObject()
                                    .put("name", tc.name)
                                    .put("arguments", parseArgsObject(tc.argumentsJson)),
                            ),
                        )
                    }
                },
            )
        }
        return obj
    }

    /**
     * Anthropic message list: coalesces consecutive `role == "tool"` messages
     * into a single `"user"` message with multiple `tool_result` blocks so
     * multi-call turns keep alternating user/assistant roles (#807).
     */
    private fun anthropicMessagesJson(messages: List<ChatMessage>): JSONArray {
        val out = JSONArray()
        val nonSystem = messages.filter { it.role != "system" }
        var i = 0
        while (i < nonSystem.size) {
            val m = nonSystem[i]
            if (m.role == "tool") {
                val blocks = JSONArray()
                while (i < nonSystem.size && nonSystem[i].role == "tool") {
                    val tr = nonSystem[i]
                    blocks.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", tr.toolCallId.orEmpty())
                            .put("content", tr.content),
                    )
                    i++
                }
                out.put(JSONObject().put("role", "user").put("content", blocks))
            } else {
                out.put(JSONObject().put("role", m.role).put("content", anthropicContentJson(m)))
                i++
            }
        }
        return out
    }

    /**
     * Anthropic content: plain string for text-only turns (identical to
     * today's shape), typed blocks when images or `tool_use` calls are present.
     */
    private fun anthropicContentJson(m: ChatMessage): Any {
        if (m.images.isEmpty() && m.toolCalls.isEmpty()) return m.content
        return JSONArray().apply {
            if (m.content.isNotEmpty() || m.toolCalls.isEmpty()) {
                put(JSONObject().put("type", "text").put("text", m.content))
            }
            m.images.forEach { img ->
                put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", img.mimeType)
                                .put("data", img.base64),
                        ),
                )
            }
            m.toolCalls.forEach { tc ->
                put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", tc.id)
                        .put("name", tc.name)
                        .put("input", parseArgsObject(tc.argumentsJson)),
                )
            }
        }
    }

    /**
     * Gemini contents list: coalesces consecutive `role == "tool"` messages into
     * a single `"user"` turn carrying `functionResponse` parts (#807).
     */
    private fun geminiContentsJson(messages: List<ChatMessage>): JSONArray {
        val out = JSONArray()
        val nonSystem = messages.filter { it.role != "system" }
        var i = 0
        while (i < nonSystem.size) {
            val m = nonSystem[i]
            if (m.role == "tool") {
                val parts = JSONArray()
                while (i < nonSystem.size && nonSystem[i].role == "tool") {
                    val tr = nonSystem[i]
                    parts.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject()
                                .put("name", tr.toolName.orEmpty())
                                .put("response", JSONObject().put("result", tr.content)),
                        ),
                    )
                    i++
                }
                out.put(JSONObject().put("role", "user").put("parts", parts))
            } else {
                out.put(
                    JSONObject()
                        .put("role", if (m.role == "assistant") "model" else m.role)
                        .put("parts", geminiPartsJson(m)),
                )
                i++
            }
        }
        return out
    }

    /** Gemini parts: text, optional inlineData per image, and optional functionCall per tool call. */
    private fun geminiPartsJson(m: ChatMessage): JSONArray = JSONArray().apply {
        if (m.content.isNotEmpty() || m.toolCalls.isEmpty()) {
            put(JSONObject().put("text", m.content))
        }
        m.images.forEach { img ->
            put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", img.mimeType).put("data", img.base64),
                ),
            )
        }
        m.toolCalls.forEach { tc ->
            put(
                JSONObject().put(
                    "functionCall",
                    JSONObject()
                        .put("name", tc.name)
                        .put("args", parseArgsObject(tc.argumentsJson)),
                ),
            )
        }
    }

    private fun newStreamParser(protocol: AiProtocol): ChatStreamParser = when (protocol) {
        AiProtocol.OPENAI -> OpenAiSseParser()
        AiProtocol.OLLAMA -> OllamaStreamParser()
        AiProtocol.ANTHROPIC -> AnthropicSseParser()
        AiProtocol.GEMINI -> GeminiSseParser()
    }

    private fun mapHttpError(code: Int, body: String): OpenAiException =
        if (code == 401 || code == 403) {
            OpenAiException.Auth("Endpoint rejected the API key (HTTP $code)")
        } else {
            OpenAiException.Http(code, "HTTP $code: ${body.take(300)}")
        }

    private fun parseModels(body: String, protocol: AiProtocol = AiProtocol.OPENAI): List<ModelInfo> = try {
        when (protocol) {
            AiProtocol.OPENAI, AiProtocol.ANTHROPIC -> {
                val root = JSONObject(body)
                val data = root.optJSONArray("data")
                val models = root.optJSONArray("models")
                when {
                    // Standard OpenAI shape: {"data":[{"id":..,"owned_by":..}]}.
                    data != null -> (0 until data.length()).map { i ->
                        val m = data.getJSONObject(i)
                        ModelInfo(id = m.optString("id"), ownedBy = m.optString("owned_by").takeIf { it.isNotEmpty() })
                    }
                    // Ollama-style shape (llama-server forks, Ollama itself):
                    // {"models":[{"model":..,"name":..}]}.
                    models != null -> (0 until models.length()).map { i ->
                        val m = models.getJSONObject(i)
                        val id = m.optString("model").ifBlank { m.optString("name") }
                        ModelInfo(id = id, ownedBy = null)
                    }
                    else -> throw OpenAiException.BadResponse("Unparseable models body: no data/models array")
                }
            }
            // Ollama /api/tags: {"models":[{"name":"qwen3:8b",…}]}.
            AiProtocol.OLLAMA -> {
                val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
                (0 until models.length()).map { i ->
                    ModelInfo(id = models.getJSONObject(i).optString("name"))
                }
            }
            // Gemini /v1beta/models: {"models":[{"name":"models/gemini-…","displayName":…}]}.
            AiProtocol.GEMINI -> {
                val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
                (0 until models.length()).map { i ->
                    val m = models.getJSONObject(i)
                    ModelInfo(id = m.optString("name"), ownedBy = m.optString("displayName").takeIf { it.isNotEmpty() })
                }
            }
        }
    } catch (e: OpenAiException) {
        throw e
    } catch (e: Exception) {
        throw OpenAiException.BadResponse("Unparseable models body: ${e.message}")
    }

    private fun parseNonStreamingChunk(body: String, protocol: AiProtocol = AiProtocol.OPENAI): ChatChunk = try {
        when (protocol) {
            AiProtocol.OPENAI -> {
                val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                val message = choice.getJSONObject("message")
                val calls = buildList {
                    val arr = message.optJSONArray("tool_calls")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val tc = arr.optJSONObject(i) ?: continue
                            val fn = tc.optJSONObject("function") ?: continue
                            val name = optTextField(fn, "name")
                            if (name.isEmpty()) continue
                            add(
                                ChatToolCall(
                                    id = optTextField(tc, "id").ifEmpty { "call_$i" },
                                    name = name,
                                    argumentsJson = optTextField(fn, "arguments").ifBlank { "{}" },
                                ),
                            )
                        }
                    }
                }
                ChatChunk(
                    delta = optTextField(message, "content"),
                    finishReason = if (calls.isNotEmpty()) "tool_calls" else "stop",
                    toolCalls = calls,
                )
            }
            AiProtocol.OLLAMA -> {
                val msg = JSONObject(body).optJSONObject("message")
                var seq = 0
                val calls = parseOllamaToolCalls(msg) { "ollama_call_${++seq}" }
                ChatChunk(
                    delta = optTextField(msg, "content"),
                    finishReason = if (calls.isNotEmpty()) "tool_calls" else "stop",
                    toolCalls = calls,
                )
            }
            AiProtocol.ANTHROPIC -> {
                val content = JSONObject(body).getJSONArray("content")
                val blocks = (0 until content.length()).map { content.getJSONObject(it) }
                val text = blocks
                    .filter { optTextField(it, "type") == "text" }
                    .joinToString("") { optTextField(it, "text") }
                val calls = blocks
                    .filter { optTextField(it, "type") == "tool_use" }
                    .mapIndexed { idx, b ->
                        ChatToolCall(
                            id = optTextField(b, "id").ifEmpty { "toolu_$idx" },
                            name = optTextField(b, "name"),
                            argumentsJson = b.optJSONObject("input")?.toString() ?: "{}",
                        )
                    }
                    .filter { it.name.isNotEmpty() }
                ChatChunk(
                    delta = text,
                    finishReason = if (calls.isNotEmpty()) "tool_calls" else "stop",
                    toolCalls = calls,
                )
            }
            AiProtocol.GEMINI -> {
                val parts = JSONObject(body).optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")
                val text = parts?.let { p -> (0 until p.length()).joinToString("") { optTextField(p.getJSONObject(it), "text") } }
                    .orEmpty()
                var seq = 0
                val calls = parseGeminiFunctionCalls(parts) { "gemini_call_${++seq}" }
                ChatChunk(
                    delta = text,
                    finishReason = if (calls.isNotEmpty()) "tool_calls" else "stop",
                    toolCalls = calls,
                )
            }
        }
    } catch (e: Exception) {
        throw OpenAiException.BadResponse("Unparseable completion body: ${e.message}")
    }
}
