package sh.haven.core.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The protocol-variant HTTP surface: per [AiProtocol], the models path, auth
 * headers, request payload shape, non-streaming response parse, and the
 * streaming parse (NDJSON for Ollama, event-stream for Anthropic, SSE records
 * for Gemini). Live end-to-end coverage exists for OPENAI and ANTHROPIC
 * (CLIProxyAPI serves /v1/messages); OLLAMA and GEMINI are covered here at
 * the wire level — Gemini had no live key at test time.
 */
class AiProtocolClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString()

    // ---- Anthropic ----

    @Test
    fun `anthropic verify hits v1 models with x-api-key and api-version`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-4","display_name":"C4"}]}"""))
        val result = client.verify(client.buildClient(null), baseUrl(), null, "sk-x", AiProtocol.ANTHROPIC)
        assertTrue("expected Ok, got $result", result is VerifyResult.Ok)
        assertEquals(listOf("claude-4"), (result as VerifyResult.Ok).models.map { it.id })
        val req = server.takeRequest()
        assertEquals("sk-x", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertTrue(req.path!!.endsWith("/v1/models"))
    }

    @Test
    fun `anthropic chat posts messages with system pulled out and joins text blocks`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"thinking","thinking":"h"},{"type":"text","text":"He"},{"type":"text","text":"y"}]}""",
            ),
        )
        val reply = client.chatCompletion(
            client.buildClient(null), baseUrl(), null, "sk-x", "claude-4",
            listOf(ChatMessage("system", "be brief"), ChatMessage("user", "hi")),
            maxTokens = 77,
            protocol = AiProtocol.ANTHROPIC,
        )
        assertEquals("Hey", reply)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        val body = req.body.readUtf8()
        assertTrue("system must be a top-level field, saw: $body", body.contains("\"system\":\"be brief\""))
        assertTrue(body.contains("\"max_tokens\":77"))
        assertTrue("messages must exclude system, saw: $body", !body.contains("be brief\\\","))
    }

    @Test
    fun `anthropic stream parses content_block_delta until message_stop`() = runBlocking {
        val body = buildString {
            append("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{}}\n\n")
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n")
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n")
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body),
        )
        val chunks = client.chatCompletionStream(
            client.buildClient(null), baseUrl(), null, "sk-x", "claude-4",
            listOf(ChatMessage("user", "hi")), protocol = AiProtocol.ANTHROPIC,
        ).toList()
        assertEquals(listOf("Hel", "lo"), chunks.map { it.delta })
        assertEquals("/v1/messages", server.takeRequest().path)
    }

    // ---- Ollama ----

    @Test
    fun `ollama verify hits api tags and reads names`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"qwen3:8b"},{"name":"llama3:latest"}]}"""))
        val result = client.verify(client.buildClient(null), baseUrl(), null, null, AiProtocol.OLLAMA)
        assertTrue("expected Ok, got $result", result is VerifyResult.Ok)
        assertEquals(listOf("qwen3:8b", "llama3:latest"), (result as VerifyResult.Ok).models.map { it.id })
        assertEquals("/api/tags", server.takeRequest().path)
    }

    @Test
    fun `ollama chat posts native payload and reads message content`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"message":{"role":"assistant","content":"one shot"},"done":true}"""))
        val reply = client.chatCompletion(
            client.buildClient(null), baseUrl(), null, null, "qwen3:8b",
            listOf(ChatMessage("user", "hi")), maxTokens = 32,
            protocol = AiProtocol.OLLAMA,
        )
        assertEquals("one shot", reply)
        val req = server.takeRequest()
        assertEquals("/api/chat", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"num_predict\":32"))
        assertTrue(body.contains("\"stream\":false"))
    }

    @Test
    fun `ollama stream reads ndjson lines until done`() = runBlocking {
        val body = """{"message":{"content":"Hel"},"done":false}
""" + """{"message":{"content":"lo"},"done":true}
"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(body))
        val chunks = client.chatCompletionStream(
            client.buildClient(null), baseUrl(), null, null, "qwen3:8b",
            listOf(ChatMessage("user", "hi")), protocol = AiProtocol.OLLAMA,
        ).toList()
        assertEquals(listOf("Hel", "lo"), chunks.map { it.delta })
    }

    // ---- Gemini ----

    @Test
    fun `gemini verify hits v1beta models with x-goog-api-key`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"models":[{"name":"models/gemini-2.0-flash","displayName":"Gemini Flash"}]}""",
            ),
        )
        val result = client.verify(client.buildClient(null), baseUrl(), null, "g-key", AiProtocol.GEMINI)
        assertTrue("expected Ok, got $result", result is VerifyResult.Ok)
        val models = (result as VerifyResult.Ok).models
        assertEquals("models/gemini-2.0-flash", models[0].id)
        assertEquals("Gemini Flash", models[0].ownedBy)
        val req = server.takeRequest()
        assertEquals("g-key", req.headers["x-goog-api-key"])
        assertTrue(req.path!!.endsWith("/v1beta/models"))
    }

    @Test
    fun `gemini chat posts contents with model in the path`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"Ge"},{"text":"mini"}]}}]}""",
            ),
        )
        val reply = client.chatCompletion(
            client.buildClient(null), baseUrl(), null, "g-key", "models/gemini-2.0-flash",
            listOf(ChatMessage("system", "brief"), ChatMessage("assistant", "earlier"), ChatMessage("user", "hi")),
            protocol = AiProtocol.GEMINI,
        )
        assertEquals("Gemini", reply)
        val req = server.takeRequest()
        // The stored id carries the "models/" segment the list endpoint
        // returns; the URL must not double it.
        assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue("assistant turns map to role=model, saw: $body", body.contains("\"role\":\"model\""))
    }

    @Test
    fun `gemini stream reads sse records and stops on finishReason`() = runBlocking {
        val body = buildString {
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Ge\"}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"mini\"}]},\"finishReason\":\"STOP\"}]}\n\n")
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val chunks = client.chatCompletionStream(
            client.buildClient(null), baseUrl(), null, "g-key", "models/gemini-2.0-flash",
            listOf(ChatMessage("user", "hi")), protocol = AiProtocol.GEMINI,
        ).toList()
        assertEquals(listOf("Ge", "mini"), chunks.map { it.delta })
        val path = server.takeRequest().path!!
        assertTrue(path.contains(":streamGenerateContent"))
        assertEquals("/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse", path)
    }

    // ---- Vision (images on ChatMessage) ----

    private val visionMessage = ChatMessage(
        "user", "what is this?",
        listOf(ChatImage("image/jpeg", "AAAA")),
    )

    @Test
    fun `openai vision turns content into typed parts with data url`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"a cat"}}]}"""))
        client.chatCompletion(
            client.buildClient(null), baseUrl(), null, null, "gpt-x",
            listOf(visionMessage), protocol = AiProtocol.OPENAI,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"image_url\""))
        assertTrue(body.contains("\"url\":\"data:image/jpeg;base64,AAAA\""))
        assertTrue(body.contains("\"text\":\"what is this?\""))
    }

    @Test
    fun `openai text-only turn keeps plain string content`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        client.chatCompletion(
            client.buildClient(null), baseUrl(), null, null, "gpt-x",
            listOf(ChatMessage("user", "hi")), protocol = AiProtocol.OPENAI,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue("content must stay a string, saw: $body", body.contains("\"content\":\"hi\""))
        assertTrue("no image parts expected", !body.contains("image_url"))
    }

    @Test
    fun `ollama vision adds raw base64 images array`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"message":{"role":"assistant","content":"a cat"},"done":true}"""))
        client.chatCompletion(
            client.buildClient(null), baseUrl(), null, null, "llava",
            listOf(visionMessage), protocol = AiProtocol.OLLAMA,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue("content stays a string, saw: $body", body.contains("\"content\":\"what is this?\""))
        assertTrue(body.contains("\"images\":[\"AAAA\"]"))
        assertTrue("no data: prefix in ollama images", !body.contains("data:image"))
    }

    @Test
    fun `anthropic vision adds image content block with base64 source`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"a cat"}]}"""))
        client.chatCompletion(
            client.buildClient(null), baseUrl(), null, "sk-x", "claude-4",
            listOf(visionMessage), protocol = AiProtocol.ANTHROPIC,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"image\""))
        assertTrue(body.contains("\"media_type\":\"image/jpeg\""))
        assertTrue(body.contains("\"data\":\"AAAA\""))
        assertTrue(body.contains("\"type\":\"text\""))
    }

    @Test
    fun `gemini vision adds inlineData part`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"a cat"}]}}]}"""))
        client.chatCompletion(
            client.buildClient(null), baseUrl(), null, "g-key", "models/gemini-2.0-flash",
            listOf(visionMessage), protocol = AiProtocol.GEMINI,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"inlineData\""))
        assertTrue(body.contains("\"mimeType\":\"image/jpeg\""))
        assertTrue(body.contains("\"data\":\"AAAA\""))
    }
}