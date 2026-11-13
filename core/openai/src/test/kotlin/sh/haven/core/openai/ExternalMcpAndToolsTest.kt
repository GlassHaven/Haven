package sh.haven.core.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end protocol tests for #807:
 * 1. [ExternalMcpClient] streamable-HTTP MCP 2025-06-18 session handshake,
 *    Bearer auth, SSE progress-notification skipping, and HTTP 404 session
 *    re-initialization.
 * 2. Tool-definition serialization, streaming/non-streaming tool-call parsing,
 *    and multi-call tool-result history formatting across OpenAI, Ollama,
 *    Anthropic, and Gemini.
 */
class ExternalMcpAndToolsTest {

    private lateinit var server: MockWebServer
    private lateinit var openAiClient: OpenAiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        openAiClient = OpenAiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString()

    @Test
    fun `ExternalMcpClient initializes session, sends Bearer token, and lists tools`() = runBlocking {
        // 1. initialize -> returns Mcp-Session-Id
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "sess-123")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}"""),
        )
        // 2. notifications/initialized -> 202 Accepted
        server.enqueue(MockResponse().setResponseCode(202))
        // 3. tools/list -> returns two tools
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[""" +
                        """{"name":"get_time","description":"Read clock","inputSchema":{"type":"object","properties":{"tz":{"type":"string"}}}},""" +
                        """{"name":"ping"}""" +
                        """]}}""",
                ),
        )

        val mcp = ExternalMcpClient(
            httpClient = openAiClient.buildClient(null),
            endpointUrl = server.url("/mcp").toString(),
            bearerToken = "tok-xyz",
        )
        val tools = mcp.listTools()
        assertEquals(2, tools.size)
        assertEquals("get_time", tools[0].name)
        assertEquals("Read clock", tools[0].description)
        assertEquals("string", JSONObject(tools[0].inputSchemaJson).getJSONObject("properties").getJSONObject("tz").getString("type"))
        assertEquals("ping", tools[1].name)

        val reqInit = server.takeRequest()
        assertEquals("Bearer tok-xyz", reqInit.getHeader("Authorization"))
        assertEquals("initialize", JSONObject(reqInit.body.readUtf8()).getString("method"))

        val reqNotif = server.takeRequest()
        assertEquals("sess-123", reqNotif.getHeader("Mcp-Session-Id"))
        assertEquals("notifications/initialized", JSONObject(reqNotif.body.readUtf8()).getString("method"))

        val reqList = server.takeRequest()
        assertEquals("sess-123", reqList.getHeader("Mcp-Session-Id"))
        assertEquals("tools/list", JSONObject(reqList.body.readUtf8()).getString("method"))
    }

    @Test
    fun `ExternalMcpClient skips SSE notifications and re-initializes on HTTP 404`() = runBlocking {
        // Initial handshake
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "sess-old")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        // First tools/call hits 404 (expired session)
        server.enqueue(MockResponse().setResponseCode(404))
        // Re-initialize handshake
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "sess-new")
                .setBody("""{"jsonrpc":"2.0","id":3,"result":{}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        // Retried tools/call responds as SSE with a notification frame before the matching id frame
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":50}}\n\n" +
                        "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"2026-11-13T09:15:00Z\"}],\"isError\":false}}\n\n",
                ),
        )

        val mcp = ExternalMcpClient(
            httpClient = openAiClient.buildClient(null),
            endpointUrl = server.url("/mcp").toString(),
        )
        val callOut = mcp.callTool("get_time", JSONObject().put("tz", "UTC"))
        assertFalse(callOut.isError)
        assertEquals("2026-11-13T09:15:00Z", callOut.content)

        // Drain 5 prior requests and check 6th used sess-new
        repeat(5) { server.takeRequest() }
        val retried = server.takeRequest()
        assertEquals("sess-new", retried.getHeader("Mcp-Session-Id"))
        val params = JSONObject(retried.body.readUtf8()).getJSONObject("params")
        assertEquals("get_time", params.getString("name"))
        assertEquals("UTC", params.getJSONObject("arguments").getString("tz"))
    }

    @Test
    fun `OpenAI protocol streams split tool_calls and serializes tool results`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"ha\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ven\\\"}\"}},{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val tools = listOf(
            ChatToolSpec(
                name = "lookup",
                description = "Search docs",
                inputSchemaJson = """{"type":"object","properties":{"q":{"type":"string"}}}""",
            ),
        )
        val http = openAiClient.buildClient(null)
        val chunks = openAiClient.chatCompletionStream(
            client = http,
            base = baseUrl(),
            prefix = null,
            apiKey = "k",
            model = "gpt-4o",
            messages = listOf(ChatMessage("user", "find haven")),
            protocol = AiProtocol.OPENAI,
            tools = tools,
        ).toList()

        val toolCalls = chunks.flatMap { it.toolCalls }
        assertEquals(2, toolCalls.size)
        assertEquals(ChatToolCall("call_a", "lookup", """{"q":"haven"}"""), toolCalls[0])
        assertEquals(ChatToolCall("call_b", "ping", "{}"), toolCalls[1])

        val firstReq = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("lookup", firstReq.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))

        // Second turn with assistant tool_calls + tool result
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Found it.\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"),
        )
        val followUp = listOf(
            ChatMessage("user", "find haven"),
            ChatMessage("assistant", "", toolCalls = toolCalls),
            ChatMessage("tool", "doc-1", toolCallId = "call_a", toolName = "lookup"),
            ChatMessage("tool", "pong", toolCallId = "call_b", toolName = "ping"),
        )
        val replyChunks = openAiClient.chatCompletionStream(
            client = http,
            base = baseUrl(),
            prefix = null,
            apiKey = "k",
            model = "gpt-4o",
            messages = followUp,
            protocol = AiProtocol.OPENAI,
            tools = tools,
        ).toList()
        assertEquals("Found it.", replyChunks.joinToString("") { it.delta })

        val secondReq = JSONObject(server.takeRequest().body.readUtf8())
        val sentMessages = secondReq.getJSONArray("messages")
        assertEquals(4, sentMessages.length())
        val assistantSent = sentMessages.getJSONObject(1)
        assertTrue(assistantSent.isNull("content"))
        assertEquals(2, assistantSent.getJSONArray("tool_calls").length())
        val toolSent = sentMessages.getJSONObject(2)
        assertEquals("tool", toolSent.getString("role"))
        assertEquals("call_a", toolSent.getString("tool_call_id"))
        assertEquals("doc-1", toolSent.getString("content"))
    }

    @Test
    fun `Anthropic protocol parses streamed tool_use and coalesces consecutive tool_result blocks`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: content_block_start\n" +
                        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Checking...\"}}\n\n" +
                        "event: content_block_start\n" +
                        "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"stat_host\",\"input\":{}}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"host\\\":\"}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"nas\\\"}\"}}\n\n" +
                        "event: message_delta\n" +
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n" +
                        "event: message_stop\n" +
                        "data: {\"type\":\"message_stop\"}\n\n",
                ),
        )
        val tools = listOf(ChatToolSpec("stat_host", "Stat host", """{"type":"object","properties":{"host":{"type":"string"}}}"""))
        val http = openAiClient.buildClient(null)
        val chunks = openAiClient.chatCompletionStream(
            client = http,
            base = baseUrl(),
            prefix = null,
            apiKey = "sk-ant",
            model = "claude-sonnet-4-5",
            messages = listOf(ChatMessage("user", "check nas")),
            protocol = AiProtocol.ANTHROPIC,
            tools = tools,
        ).toList()

        assertEquals("Checking...", chunks.joinToString("") { it.delta })
        val calls = chunks.flatMap { it.toolCalls }
        assertEquals(listOf(ChatToolCall("toolu_1", "stat_host", """{"host":"nas"}""")), calls)
        server.takeRequest()

        // Verify two consecutive tool results coalesce into a single "user" turn with two tool_result blocks
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Up.\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"),
        )
        openAiClient.chatCompletionStream(
            client = http,
            base = baseUrl(),
            prefix = null,
            apiKey = "sk-ant",
            model = "claude-sonnet-4-5",
            messages = listOf(
                ChatMessage("user", "check nas"),
                ChatMessage("assistant", "Checking...", toolCalls = calls + ChatToolCall("toolu_2", "stat_host", """{"host":"db"}""")),
                ChatMessage("tool", "nas=up", toolCallId = "toolu_1", toolName = "stat_host"),
                ChatMessage("tool", "db=up", toolCallId = "toolu_2", toolName = "stat_host"),
            ),
            protocol = AiProtocol.ANTHROPIC,
            tools = tools,
        ).toList()

        val req2 = JSONObject(server.takeRequest().body.readUtf8())
        val msgs = req2.getJSONArray("messages")
        assertEquals(3, msgs.length())
        val coalescedUser = msgs.getJSONObject(2)
        assertEquals("user", coalescedUser.getString("role"))
        val blocks = coalescedUser.getJSONArray("content")
        assertEquals(2, blocks.length())
        assertEquals("tool_result", blocks.getJSONObject(0).getString("type"))
        assertEquals("toolu_1", blocks.getJSONObject(0).getString("tool_use_id"))
        assertEquals("toolu_2", blocks.getJSONObject(1).getString("tool_use_id"))
    }

    @Test
    fun `Ollama and Gemini protocols parse tool calls and strip unsupported Gemini schema keys`() = runBlocking {
        // 1. Ollama NDJSON tool call
        val ollamaParser = OllamaStreamParser()
        val ollamaChunks = ollamaParser.feed(
            """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"df_h","arguments":{"mount":"/"}}}]},"done":true}""" + "\n",
        )
        val ollamaCalls = ollamaChunks.flatMap { it.toolCalls }
        assertEquals(1, ollamaCalls.size)
        assertEquals("df_h", ollamaCalls[0].name)
        assertEquals("/", JSONObject(ollamaCalls[0].argumentsJson).getString("mount"))

        // 2. Gemini SSE functionCall + schema sanitization
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"df_h\",\"args\":{\"mount\":\"/data\"}}}]},\"finishReason\":\"STOP\"}]}\n\n",
                ),
        )
        val tools = listOf(
            ChatToolSpec(
                name = "df_h",
                description = "Disk free",
                inputSchemaJson = """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false,"properties":{"mount":{"type":"string","additionalProperties":false}}}""",
            ),
        )
        val http = openAiClient.buildClient(null)
        val gemChunks = openAiClient.chatCompletionStream(
            client = http,
            base = baseUrl(),
            prefix = null,
            apiKey = "gem-key",
            model = "models/gemini-2.5-flash",
            messages = listOf(ChatMessage("user", "disk?")),
            protocol = AiProtocol.GEMINI,
            tools = tools,
        ).toList()

        val gemCalls = gemChunks.flatMap { it.toolCalls }
        assertEquals(1, gemCalls.size)
        assertEquals("df_h", gemCalls[0].name)
        assertEquals("/data", JSONObject(gemCalls[0].argumentsJson).getString("mount"))

        val gemReq = JSONObject(server.takeRequest().body.readUtf8())
        val params = gemReq.getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")
            .getJSONObject(0)
            .getJSONObject("parameters")
        assertFalse(params.has("${'$'}schema"))
        assertFalse(params.has("additionalProperties"))
        assertFalse(params.getJSONObject("properties").getJSONObject("mount").has("additionalProperties"))
    }
}
