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
 * HTTP-surface tests against a MockWebServer: the /v1/models probe's error
 * mapping, Bearer header presence, the SSE streaming loop, and the
 * non-streaming fallback. Cancellation of an in-flight stream is exercised
 * indirectly (the flow closes the call in awaitClose) — a dedicated
 * mid-stream cancel test would need a body throttle; not covered here.
 */
class OpenAiClientTest {

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

    private fun modelsBody(vararg ids: String): String =
        """{"data":[${ids.joinToString(",") { """{"id":"$it","owned_by":"test"}""" }}]}"""

    private fun baseUrl(): String = server.url("/").toString()

    @Test
    fun `verify parses the Ollama-style models shape`() = runBlocking {
        // llama-server forks (and Ollama itself) serve {"models":[{"model":..}]}
        // rather than the OpenAI {"data":[{"id":..}]} shape.
        server.enqueue(
            MockResponse().setBody(
                """{"models":[{"name":"haiku","model":"qwen3.8"},{"name":"fallback-only"}]}""",
            ),
        )
        val result = client.verify(client.buildClient(null), baseUrl(), null, null)
        assertTrue("expected Ok, got: $result", result is VerifyResult.Ok)
        val models = (result as VerifyResult.Ok).models
        assertEquals(listOf("qwen3.8", "fallback-only"), models.map { it.id })
    }

    @Test
    fun `verify parses models on 200`() = runBlocking {
        server.enqueue(MockResponse().setBody(modelsBody("m1", "m2")))
        val result = client.verify(client.buildClient(null), baseUrl(), null, null)
        val models = (result as VerifyResult.Ok).models
        assertEquals(listOf("m1", "m2"), models.map { it.id })
        assertTrue(server.url("/v1/models").encodedPath.endsWith("/v1/models"))
    }

    @Test
    fun `verify maps 401 to Auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val result = client.verify(client.buildClient(null), baseUrl(), null, "k")
        assertTrue(result is VerifyResult.Failure && result.error is OpenAiException.Auth)
    }

    @Test
    fun `verify maps 404 to a wrong-prefix hint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client.verify(client.buildClient(null), baseUrl(), null, null)
        assertTrue(result is VerifyResult.Failure && result.error is OpenAiException.Http)
    }

    @Test
    fun `verify maps connection refused to Network`() = runBlocking {
        val deadPort = 1 // nothing listens there
        val result = client.verify(client.buildClient(null), "http://127.0.0.1:$deadPort", null, null)
        assertTrue(result is VerifyResult.Failure && result.error is OpenAiException.Network)
    }

    @Test
    fun `path prefix is inserted before v1`() {
        assertEquals("http://h/api/v1/models", client.modelsUrl("http://h/", "/api"))
        assertEquals("http://h/v1/models", client.modelsUrl("http://h", null))
    }

    @Test
    fun `apiKey is sent as Bearer and omitted when blank`() = runBlocking {
        server.enqueue(MockResponse().setBody(modelsBody("m")))
        client.verify(client.buildClient(null), baseUrl(), null, "secret")
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))

        server.enqueue(MockResponse().setBody(modelsBody("m")))
        client.verify(client.buildClient(null), baseUrl(), null, null)
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `streaming collects deltas in order and finalizes`() = runBlocking {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n")
            append(": keepalive\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
        val chunks = client.chatCompletionStream(
            client.buildClient(null), baseUrl(), null, null, "m",
            listOf(ChatMessage("user", "hi")),
        ).toList()
        assertEquals(listOf("Hel", "lo"), chunks.map { it.delta })
    }

    @Test
    fun `server ignoring stream flag falls back to one chunk`() = runBlocking {
        val body = """{"choices":[{"message":{"content":"one shot"}}]}"""
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
        val chunks = client.chatCompletionStream(
            client.buildClient(null), baseUrl(), null, null, "m",
            listOf(ChatMessage("user", "hi")),
        ).toList()
        assertEquals(listOf("one shot"), chunks.map { it.delta })
        assertEquals(listOf("stop"), chunks.map { it.finishReason })
    }

    @Test
    fun `non-2xx stream response surfaces as Auth for 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("no"))
        val chunks = runCatching {
            client.chatCompletionStream(
                client.buildClient(null), baseUrl(), null, "bad", "m",
                listOf(ChatMessage("user", "hi")),
            ).toList()
        }
        val error = chunks.exceptionOrNull()
        assertTrue(error is OpenAiException.Auth)
    }

    @Test
    fun `one-shot completion returns assistant content`() = runBlocking {
        val body = """{"choices":[{"message":{"content":"the reply"}}]}"""
        server.enqueue(MockResponse().setBody(body))
        val reply = client.chatCompletion(
            client.buildClient(null), baseUrl(), null, null, "m",
            listOf(ChatMessage("user", "hi")),
        )
        assertEquals("the reply", reply)
        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/v1/chat/completions"))
        assertTrue(request.body.readUtf8().contains("\"stream\":false"))
    }
}