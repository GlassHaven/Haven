package sh.haven.app.agent

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.AgentAuditEvent
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val TAG = "McpServer"

/**
 * Minimal Model Context Protocol (MCP) server for Haven.
 *
 * Binds to **127.0.0.1 only** (loopback) so the server is reachable
 * from local processes (any MCP client running in PRoot, a script,
 * a curl test) but not from the LAN. This is the agent transport
 * that implements Haven's "shared viewport" principle:
 * every observable state and action a human has in the UI should be
 * reachable by an agent through the same underlying ViewModels and
 * repositories.
 *
 * ### Protocol
 *
 * Implements the 2025-06-18 **Streamable HTTP** transport in
 * stateless mode: a single `POST /mcp` endpoint that accepts one
 * JSON-RPC 2.0 message and responds with a single JSON body. No SSE,
 * no WebSocket, no session management. This is the smallest
 * implementation that satisfies the MCP spec for a tool-only server.
 *
 * Supported methods:
 * - `initialize` — protocol handshake, returns server info + tools capability
 * - `notifications/initialized` — acknowledged, no-op
 * - `tools/list` — returns the available tool definitions
 * - `tools/call` — dispatches to a named tool, passing arguments
 *
 * ### Security model (v1)
 *
 * Loopback-only binding is the entire security story in v1. Anyone
 * who can open a TCP socket to 127.0.0.1 on this device can call
 * every exposed tool. This is acceptable for a read-only v1 on
 * Android because all local processes on Android already have at
 * least as much access to this app's data as they do through normal
 * IPC. Write operations (upload, delete, disconnect, etc.) will
 * require per-action consent before they land in v2.
 */
@Singleton
class McpServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val rcloneClient: RcloneClient,
    private val sftpStreamServer: SftpStreamServer,
    private val hlsStreamServer: HlsStreamServer,
    private val ffmpegExecutor: FfmpegExecutor,
    private val preferencesRepository: UserPreferencesRepository,
    private val terminalFontInstaller: TerminalFontInstaller,
    private val localSessionManager: LocalSessionManager,
    private val auditRecorder: AgentAuditRecorder,
    private val consentManager: AgentConsentManager,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
) : Closeable {

    /**
     * Last `clientInfo.name` we saw on an `initialize` request. Best
     * effort: in the stateless transport we can't actually pin a name
     * to a specific later call, but in practice clients open one
     * connection and then make a small burst of requests, so the most
     * recent hint is usually the right one. Stored separately from any
     * per-request state so it survives across handler invocations.
     */
    @Volatile
    private var lastClientHint: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serializes [start] and [stop] so preference-driven toggles that
     * land close together can never leave the server with a half-torn
     * listener or a zombie accept thread.
     */
    private val lifecycleLock = Any()

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    private val _endpointUrl = MutableStateFlow<String?>(null)
    val endpointUrl: StateFlow<String?> = _endpointUrl.asStateFlow()

    /**
     * Standard MCP server registration JSON for the Haven endpoint.
     * Null when the server isn't running. Any MCP-aware client can
     * merge this into its own config; the shape is compatible with
     * the common `{ "mcpServers": { "name": { "type", "url" } } }`
     * format most clients accept.
     */
    val mcpServerConfigJson: String?
        get() {
            val url = _endpointUrl.value ?: return null
            return """
                {
                  "mcpServers": {
                    "haven": {
                      "type": "http",
                      "url": "$url"
                    }
                  }
                }
            """.trimIndent()
        }

    private val tools = McpTools(
        context = context,
        connectionRepository = connectionRepository,
        portForwardRepository = portForwardRepository,
        sshSessionManager = sshSessionManager,
        sessionManagerRegistry = sessionManagerRegistry,
        rcloneClient = rcloneClient,
        sftpStreamServer = sftpStreamServer,
        hlsStreamServer = hlsStreamServer,
        ffmpegExecutor = ffmpegExecutor,
        preferencesRepository = preferencesRepository,
        terminalFontInstaller = terminalFontInstaller,
        localSessionManager = localSessionManager,
        agentUiCommandBus = agentUiCommandBus,
        transportSelector = transportSelector,
    )

    /**
     * Start the server on the first free port in [8730..8739] (a small
     * deterministic range so a reconnecting MCP client can find us
     * again after an app restart). Falls back to an OS-assigned port
     * if all preferred ports are busy.
     *
     * Idempotent: if a healthy instance is already running this is a
     * no-op; if a stale instance is detected (flag set but listener
     * socket closed or accept thread dead) it's torn down and
     * re-created. This matters because the Android process can be
     * suspended for long periods and the server's threads may be
     * killed out from under it while [isRunning] still reads `true`.
     */
    fun start() = synchronized(lifecycleLock) {
        if (isHealthy()) return@synchronized
        if (isRunning) {
            Log.w(TAG, "start() found stale instance, tearing down before rebinding")
            stopLocked()
        }
        val ss = bindLoopback()
        serverSocket = ss
        port = ss.localPort
        isRunning = true
        _endpointUrl.value = "http://127.0.0.1:$port/mcp"
        Log.i(TAG, "MCP server listening on ${_endpointUrl.value}")

        serverThread = thread(name = "mcp-http", isDaemon = true) {
            while (isRunning && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    thread(name = "mcp-client", isDaemon = true) {
                        try {
                            handleClient(client)
                        } catch (e: Throwable) {
                            // Last-resort guard so one bad request can
                            // never kill a worker thread silently and
                            // leave the peer socket in CLOSE_WAIT.
                            Log.w(TAG, "worker crashed: ${e.message}")
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: IOException) {
                    // Socket closed by stop() — expected on shutdown
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed: ${e.message}")
                }
            }
            Log.i(TAG, "MCP accept loop exited")
        }
    }

    override fun close() = stop()

    fun stop() = synchronized(lifecycleLock) {
        stopLocked()
    }

    /** Must be called while holding [lifecycleLock]. */
    private fun stopLocked() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        port = 0
        _endpointUrl.value = null
    }

    /**
     * True iff the server is running AND the listener socket is still
     * bound AND the accept thread is still alive. Used by [start] to
     * distinguish a healthy instance from a process-suspend zombie.
     */
    private fun isHealthy(): Boolean {
        if (!isRunning) return false
        val ss = serverSocket ?: return false
        if (ss.isClosed) return false
        val t = serverThread ?: return false
        if (!t.isAlive) return false
        return true
    }

    // --- Socket binding ---

    private fun bindLoopback(): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        // Try preferred ports first so a client that cached an endpoint
        // across app restarts has a decent chance of finding us again.
        for (p in 8730..8739) {
            try {
                return ServerSocket(p, 10, loopback).apply { reuseAddress = true }
            } catch (_: IOException) {
                // busy, try next
            }
        }
        // All preferred ports busy — let the OS pick one
        return ServerSocket(0, 10, loopback).apply { reuseAddress = true }
    }

    // --- HTTP + JSON-RPC handling ---

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 3) {
                    writeError(s, 400, "Bad Request")
                    return
                }
                val method = parts[0]
                val path = parts[1]

                // Parse headers
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        if (name == "content-length") {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                }

                when {
                    method == "POST" && (path == "/mcp" || path == "/") -> {
                        val body = if (contentLength > 0) {
                            val buf = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val n = reader.read(buf, read, contentLength - read)
                                if (n < 0) break
                                read += n
                            }
                            String(buf, 0, read)
                        } else ""
                        val response = handleJsonRpc(body)
                        writeJson(s, 200, response)
                    }
                    method == "GET" && (path == "/mcp" || path == "/") -> {
                        // SSE channel for server-initiated messages — not
                        // supported in v1. Spec allows 405.
                        writeError(s, 405, "Method Not Allowed")
                    }
                    method == "OPTIONS" -> {
                        // CORS preflight — respond permissive for local use
                        val headers = buildString {
                            append("HTTP/1.1 204 No Content\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
                            append("Access-Control-Allow-Headers: Content-Type, Mcp-Session-Id, Mcp-Protocol-Version\r\n")
                            append("Content-Length: 0\r\n")
                            append("\r\n")
                        }
                        s.getOutputStream().write(headers.toByteArray(Charsets.UTF_8))
                    }
                    else -> writeError(s, 404, "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "client handler error: ${e.message}")
        }
    }

    /**
     * Parse a JSON-RPC 2.0 request and return the response body. Visible
     * to tests in the same module so the consent gate, error mapping, and
     * audit-outcome plumbing can be exercised without sockets.
     */
    internal fun handleJsonRpc(body: String): String {
        if (body.isBlank()) {
            return jsonRpcError(null, -32700, "Parse error: empty body")
        }
        val req = try {
            JSONObject(body)
        } catch (e: Exception) {
            return jsonRpcError(null, -32700, "Parse error: ${e.message}")
        }
        val id = req.opt("id") // may be null for notifications
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()

        // Notifications (no id) get no response per JSON-RPC spec, but we still
        // need to return an empty 200 for the HTTP layer. Return "" for those.
        val isNotification = !req.has("id")

        // Capture client name from `initialize` so subsequent audit
        // rows can attribute calls to the right MCP client. Best
        // effort: stateless transport, so this is just "the most
        // recent name we saw."
        if (method == "initialize") {
            params.optJSONObject("clientInfo")?.optString("name")
                ?.takeIf { it.isNotEmpty() }
                ?.let { lastClientHint = it }
        }

        val started = System.nanoTime()
        var outcome = AgentAuditEvent.Outcome.OK
        var errorMessage: String? = null
        var resultJson: JSONObject? = null
        val response = try {
            val result = dispatch(method, params)
            if (result is JSONObject) resultJson = result
            if (isNotification) "" else jsonRpcResult(id, result)
        } catch (e: ConsentDeniedError) {
            // User denied via the bottom-sheet prompt, or no foreground
            // activity was available to ask. Audit it as DENIED so the
            // user can tell intent-from-the-agent apart from runtime
            // failure later.
            outcome = AgentAuditEvent.Outcome.DENIED
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, e.code, e.message ?: "User denied")
        } catch (e: McpError) {
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, e.code, e.message ?: "Error")
        } catch (e: Exception) {
            Log.e(TAG, "dispatch failed for method=$method", e)
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, -32603, "Internal error: ${e.message}")
        }

        // Record after responding so latency is unaffected. We skip
        // bookkeeping notifications (`notifications/initialized`) —
        // they carry no semantic action and would just clutter the
        // audit view.
        if (method != "notifications/initialized") {
            val toolName = if (method == "tools/call") {
                params.optString("name").takeIf { it.isNotEmpty() }
            } else null
            val rawArgs = if (method == "tools/call") {
                params.optJSONObject("arguments")
            } else if (params.length() > 0) params else null
            // For tools/call, the structured tool output lives under
            // `structuredContent`; the summariser wants that, not the
            // MCP wrapper that holds `content` + `structuredContent`.
            val resultForSummary = if (method == "tools/call") {
                resultJson?.optJSONObject("structuredContent")
            } else resultJson
            val durationMs = (System.nanoTime() - started) / 1_000_000
            auditRecorder.record(
                method = method,
                toolName = toolName,
                rawArgs = rawArgs,
                result = resultForSummary,
                durationMs = durationMs,
                outcome = outcome,
                errorMessage = errorMessage,
                clientHint = lastClientHint,
            )
        }

        return response
    }

    /** Dispatch an MCP method to its handler. */
    private fun dispatch(method: String, params: JSONObject): Any? = when (method) {
        "initialize" -> handleInitialize(params)
        "notifications/initialized" -> JSONObject() // ack
        "tools/list" -> handleToolsList()
        "tools/call" -> handleToolsCall(params)
        "ping" -> JSONObject()
        else -> throw McpError(-32601, "Method not found: $method")
    }

    private fun handleInitialize(params: JSONObject): JSONObject {
        val clientProtoVersion = params.optString("protocolVersion", "2025-06-18")
        Log.i(TAG, "MCP client connected, protocolVersion=$clientProtoVersion")
        return JSONObject().apply {
            put("protocolVersion", "2025-06-18")
            put("serverInfo", JSONObject().apply {
                put("name", "haven-agent")
                put("version", sh.haven.app.BuildConfig.VERSION_NAME)
            })
            put("capabilities", JSONObject().apply {
                // Advertise tools capability only in v1
                put("tools", JSONObject().apply {
                    put("listChanged", false)
                })
            })
            put("instructions",
                "Haven is a mobile thin-client OS for distributed compute, storage, and presence. " +
                    "Use these tools to inspect the user's saved connections, active sessions, and " +
                    "cloud storage without disturbing the UI they're looking at.")
        }
    }

    private fun handleToolsList(): JSONObject {
        return JSONObject().apply {
            put("tools", JSONArray().apply {
                tools.definitions().forEach { put(it) }
            })
        }
    }

    private fun handleToolsCall(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
            .ifEmpty { throw McpError(-32602, "Missing tool name") }
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        // Look up consent metadata before invoking the handler. Unknown
        // tools fall through to tools.call() which will throw the right
        // error; treating them as NEVER avoids prompting on a typo.
        val consent = tools.consentFor(name)
        if (consent != null && consent.level != ConsentLevel.NEVER) {
            val summary = try {
                consent.summary(arguments)
            } catch (e: Exception) {
                // A summary builder shouldn't crash the dispatcher; fall
                // back to the tool name so the prompt still renders.
                Log.w(TAG, "summary builder for '$name' threw: ${e.message}")
                name
            }
            val decision = runBlocking {
                consentManager.requestConsent(
                    toolName = name,
                    clientHint = lastClientHint,
                    summary = summary,
                    level = consent.level,
                )
            }
            if (decision == ConsentDecision.DENY) {
                throw ConsentDeniedError(name)
            }
        }
        val content = try {
            runBlocking { tools.call(name, arguments) }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "tool '$name' threw", e)
            throw McpError(-32603, "Tool failed: ${e.message}")
        }
        return JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", content.toString(2))
                })
            })
            put("structuredContent", content)
        }
    }

    // --- JSON-RPC response builders ---

    private fun jsonRpcResult(id: Any?, result: Any?): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id)
        obj.put("result", result ?: JSONObject.NULL)
        return obj.toString()
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id) else obj.put("id", JSONObject.NULL)
        obj.put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
        return obj.toString()
    }

    // --- HTTP response helpers ---

    private fun writeJson(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusLine = when (status) {
            200 -> "200 OK"
            204 -> "204 No Content"
            else -> "$status OK"
        }
        val headers = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun writeError(socket: Socket, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val statusLine = "$status $text"
        val headers = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}

/** Lightweight error type carrying a JSON-RPC error code. */
open class McpError(val code: Int, message: String) : RuntimeException(message)

/**
 * Raised by the dispatcher when the user denies a consent prompt (or no
 * foreground activity is available to render one). Audit is recorded
 * with [AgentAuditEvent.Outcome.DENIED] so the dashboard can distinguish
 * "the agent tried and we said no" from "the agent tried and it broke."
 */
class ConsentDeniedError(toolName: String) :
    McpError(-32000, "User denied: $toolName")
