package sh.haven.core.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import sh.haven.core.mcp.jsonRpcRequest
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Result of invoking a single MCP tool via `tools/call` (#807).
 */
data class McpToolResult(
    val content: String,
    val isError: Boolean = false,
)

/**
 * Streamable-HTTP MCP 2025-06-18 client over [OkHttpClient] (#807).
 *
 * Used by AI chat completions to discover and invoke tools exposed by external
 * MCP servers (`http://` or `https://`, optional `Authorization: Bearer` token,
 * and optional tunnel [OkHttpClient]).
 *
 * Handles both `application/json` bodies and `text/event-stream` SSE responses,
 * skipping server-to-client notification frames (`notifications/...` with no
 * `id`) and closing the stream immediately after reading the matching response
 * frame so servers that keep SSE streams open (e.g. FastMCP) never hang.
 * Re-initializes automatically when an `Mcp-Session-Id` expires (HTTP 404).
 */
class ExternalMcpClient(
    httpClient: OkHttpClient,
    private val endpointUrl: String,
    private val bearerToken: String? = null,
    connectTimeoutSeconds: Long = 10,
    readTimeoutSeconds: Long = 30,
) {
    private class SessionExpired : IOException("MCP session expired (HTTP 404)")

    private val client: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    @Volatile private var initialized: Boolean = false
    @Volatile private var sessionId: String? = null
    private val nextId = AtomicInteger(1)

    /** Discover tools via `tools/list` and map them to [ChatToolSpec]. */
    suspend fun listTools(): List<ChatToolSpec> = withContext(Dispatchers.IO) {
        ensureSession()
        val result = rpcWithRetry("tools/list", JSONObject()) ?: return@withContext emptyList()
        val arr = result.optJSONArray("tools") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = optTextField(obj, "name").trim()
                if (name.isEmpty()) continue
                val desc = optTextField(obj, "description")
                val schema = obj.optJSONObject("inputSchema")?.toString()
                    ?: """{"type":"object","properties":{}}"""
                add(ChatToolSpec(name = name, description = desc, inputSchemaJson = schema))
            }
        }
    }

    /** Invoke [name] via `tools/call` and format its result blocks as text. */
    suspend fun callTool(name: String, arguments: JSONObject): McpToolResult = withContext(Dispatchers.IO) {
        ensureSession()
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val result = rpcWithRetry("tools/call", params) ?: return@withContext McpToolResult("")
        val isError = result.optBoolean("isError", false)
        val contentArr = result.optJSONArray("content")
        val rendered = when {
            contentArr != null && contentArr.length() > 0 -> {
                buildList {
                    for (i in 0 until contentArr.length()) {
                        val block = contentArr.optJSONObject(i) ?: continue
                        when (optTextField(block, "type")) {
                            "text" -> add(optTextField(block, "text"))
                            "resource" -> {
                                val res = block.optJSONObject("resource")
                                add(optTextField(res, "text").ifEmpty { block.toString() })
                            }
                            else -> add(block.toString())
                        }
                    }
                }.joinToString("\n")
            }
            result.has("structuredContent") -> result.opt("structuredContent")?.toString().orEmpty()
            else -> result.toString()
        }
        McpToolResult(content = rendered, isError = isError)
    }

    private fun ensureSession() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val params = JSONObject().apply {
                put("protocolVersion", "2025-06-18")
                put("capabilities", JSONObject())
                put(
                    "clientInfo",
                    JSONObject().apply {
                        put("name", "haven-chat")
                        put("version", "1.0")
                    },
                )
            }
            rpc("initialize", params)
            runCatching {
                post(jsonRpcRequest(id = null, method = "notifications/initialized"), expectResponse = false)
            }
            initialized = true
        }
    }

    private fun rpcWithRetry(method: String, params: JSONObject): JSONObject? =
        try {
            rpc(method, params)
        } catch (_: SessionExpired) {
            synchronized(this) {
                initialized = false
                sessionId = null
            }
            ensureSession()
            rpc(method, params)
        }

    private fun rpc(method: String, params: JSONObject): JSONObject? {
        val id = nextId.getAndIncrement()
        val json = post(jsonRpcRequest(id, method, params), expectResponse = true)
        json?.optJSONObject("error")?.let { err ->
            throw IOException("MCP error ${err.optInt("code")}: ${optTextField(err, "message")}")
        }
        return json?.optJSONObject("result")
    }

    private fun post(body: JSONObject, expectResponse: Boolean): JSONObject? {
        val builder = Request.Builder()
            .url(endpointUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply {
                if (!bearerToken.isNullOrBlank()) {
                    header("Authorization", "Bearer ${bearerToken.trim()}")
                }
                sessionId?.let { header("Mcp-Session-Id", it) }
            }
            .post(body.toString().toRequestBody("application/json".toMediaType()))

        client.newCall(builder.build()).execute().use { resp ->
            resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (resp.code == 404) throw SessionExpired()
            if (resp.code !in 200..299) {
                val errText = resp.body.string().take(240)
                throw IOException("MCP HTTP ${resp.code}: $errText")
            }
            if (!expectResponse || resp.code == 202 || resp.code == 204) return null
            val contentType = resp.header("Content-Type").orEmpty()
            val source = resp.body.source()
            return if (contentType.contains("event-stream", ignoreCase = true)) {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (!payload.startsWith("{")) continue
                    val frame = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    // Skip server notifications (`notifications/...` carry no `id`).
                    if (frame.has("id") && !frame.isNull("id")) {
                        return frame
                    }
                }
                null
            } else {
                val text = source.readUtf8().trim()
                if (text.startsWith("{")) runCatching { JSONObject(text) }.getOrNull() else null
            }
        }
    }
}
