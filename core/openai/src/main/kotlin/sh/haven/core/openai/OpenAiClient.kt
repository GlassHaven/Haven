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
            .apply { factory?.let { socketFactory(it) } }
            .build()

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

    private fun baseUrl(base: String, prefix: String?): String {
        var b = base.trim().trimEnd('/')
        prefix?.takeIf { it.isNotBlank() }?.let { p ->
            b += if (p.startsWith("/")) p else "/$p"
        }
        return b
    }

    fun modelsUrl(base: String, prefix: String?, protocol: AiProtocol = AiProtocol.OPENAI): String =
        baseUrl(base, prefix) + when (protocol) {
            AiProtocol.OPENAI, AiProtocol.ANTHROPIC -> "/v1/models"
            AiProtocol.OLLAMA -> "/api/tags"
            AiProtocol.GEMINI -> "/v1beta/models"
        }

    fun chatUrl(base: String, prefix: String?, protocol: AiProtocol = AiProtocol.OPENAI): String =
        chatUrlFor(base, prefix, protocol, model = "", stream = false)

    private fun chatUrlFor(base: String, prefix: String?, protocol: AiProtocol, model: String, stream: Boolean): String =
        baseUrl(base, prefix) + when (protocol) {
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
    ): String {
        val url = chatUrlFor(base, prefix, protocol, model, stream = false)
        val (code, body) = execute(
            client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build(),
            url,
            apiKey,
            chatPayload(model, messages, maxTokens, temperature, protocol, stream = false),
            protocol = protocol,
        )
        if (code !in 200..299) throw mapHttpError(code, body)
        return parseNonStreaming(body, protocol)
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
    ): Flow<ChatChunk> = callbackFlow {
        val payload = chatPayload(model, messages, maxTokens, temperature, protocol, stream = true)
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
                            val chunk = ChatChunk(
                                delta = parseNonStreaming(source.readUtf8(), protocol),
                                finishReason = "stop",
                            )
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
    ): Pair<Int, String> = withContext(kotlinx.coroutines.Dispatchers.IO) {
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
     *
     * Images (vision) change the shape per protocol: OpenAI turns into
     * content-parts with data: URLs, Ollama keeps the string content and adds
     * a raw-base64 `images` array, Anthropic adds image content blocks, and
     * Gemini adds inlineData parts. Anthropic/Gemini hoist system text out of
     * the message list, so images on system messages are dropped.
     */
    private fun chatPayload(
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int?,
        temperature: Double?,
        protocol: AiProtocol,
        stream: Boolean,
    ): String = when (protocol) {
        AiProtocol.OPENAI -> JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                messages.forEach { put(openAiMessageJson(it)) }
            })
            put("stream", stream)
            maxTokens?.let { put("max_tokens", it) }
            temperature?.let { put("temperature", it) }
        }.toString()
        AiProtocol.OLLAMA -> JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    val obj = JSONObject().put("role", m.role).put("content", m.content)
                    if (m.images.isNotEmpty()) {
                        // Ollama's native shape: raw base64 strings, no data: prefix.
                        obj.put("images", JSONArray().apply { m.images.forEach { put(it.base64) } })
                    }
                    put(obj)
                }
            })
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
            put("messages", JSONArray().apply {
                messages.filter { it.role != "system" }.forEach {
                    put(JSONObject().put("role", it.role).put("content", anthropicContentJson(it)))
                }
            })
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
            put("contents", JSONArray().apply {
                messages.filter { it.role != "system" }.forEach {
                    // Gemini uses "model" where OpenAI uses "assistant".
                    put(
                        JSONObject()
                            .put("role", if (it.role == "assistant") "model" else it.role)
                            .put("parts", geminiPartsJson(it)),
                    )
                }
            })
            if (maxTokens != null || temperature != null) {
                put("generationConfig", JSONObject().apply {
                    maxTokens?.let { put("maxOutputTokens", it) }
                    temperature?.let { put("temperature", it) }
                })
            }
        }.toString()
    }

    /**
     * OpenAI message: `content` stays a plain string for text-only turns
     * (maximum server compatibility); with images it becomes a typed parts
     * array with data: URLs.
     */
    private fun openAiMessageJson(m: ChatMessage): JSONObject {
        val obj = JSONObject().put("role", m.role)
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

    /**
     * Anthropic content: plain string for text-only turns (identical to
     * today's shape), typed blocks when images are present.
     */
    private fun anthropicContentJson(m: ChatMessage): Any {
        if (m.images.isEmpty()) return m.content
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", m.content))
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
        }
    }

    /** Gemini parts: one text part plus an inlineData part per image. */
    private fun geminiPartsJson(m: ChatMessage): JSONArray = JSONArray().apply {
        put(JSONObject().put("text", m.content))
        m.images.forEach { img ->
            put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", img.mimeType).put("data", img.base64),
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

    private fun parseNonStreaming(body: String, protocol: AiProtocol = AiProtocol.OPENAI): String = try {
        when (protocol) {
            AiProtocol.OPENAI -> {
                val choices = JSONObject(body).getJSONArray("choices")
                optTextField(choices.getJSONObject(0).getJSONObject("message"), "content")
            }
            AiProtocol.OLLAMA ->
                optTextField(JSONObject(body).optJSONObject("message"), "content")
            AiProtocol.ANTHROPIC -> {
                val content = JSONObject(body).getJSONArray("content")
                (0 until content.length())
                    .map { content.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("") { optTextField(it, "text") }
            }
            AiProtocol.GEMINI -> {
                val parts = JSONObject(body).optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")
                parts?.let { p -> (0 until p.length()).joinToString("") { optTextField(p.getJSONObject(it), "text") } }
                    .orEmpty()
            }
        }
    } catch (e: Exception) {
        throw OpenAiException.BadResponse("Unparseable completion body: ${e.message}")
    }
}