package sh.haven.app.agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.mcp.McpError
import sh.haven.core.openai.AiProtocol
import sh.haven.core.openai.ChatMessage
import sh.haven.core.openai.ModelInfo
import sh.haven.core.openai.OpenAiSessionManager
import sh.haven.core.tunnel.TunnelResolver
import javax.net.SocketFactory

/**
 * The OpenAI MCP tools' contract: consent levels (list = ONCE_PER_SESSION,
 * chat = EVERY_CALL), the not-connected guards, the fail-closed tunnel check,
 * and the reply shape — which must carry the assistant text and nothing else,
 * so the profile's API key can never ride out on a tool result. Persistence
 * is asserted structurally: [OpenAiToolProvider]'s constructor takes no
 * [sh.haven.core.data.repository.ChatRepository], so it cannot persist.
 */
class OpenAiToolProviderTest {

    private val sessionManager = mockk<OpenAiSessionManager>()

    private val connectionRepository = mockk<ConnectionRepository>()

    private val openAiClient = mockk<sh.haven.core.openai.OpenAiClient>()

    private val tunnelResolver = mockk<TunnelResolver>()

    private fun provider(): OpenAiToolProvider = OpenAiToolProvider(
        ctx = ToolContext(
            profileLabel = { "label($it)" },
            backgroundScope = CoroutineScope(SupervisorJob()),
            attachAgentShell = { _, _, _ -> error("unused in these tests") },
        ),
        openAiSessionManager = sessionManager,
        connectionRepository = connectionRepository,
        openAiClient = openAiClient,
        tunnelResolver = tunnelResolver,
    )

    private fun tools(): Map<String, ToolHandler> = provider().tools()

    private fun connectedSession(models: List<ModelInfo> = listOf(ModelInfo("m1"))) =
        OpenAiSessionManager.SessionState(
            sessionId = "s1", profileId = "p1", label = "Llama",
            status = OpenAiSessionManager.SessionState.Status.CONNECTED,
            models = models, baseUrl = "http://e:8090",
        )

    private fun stubConnected(models: List<ModelInfo> = listOf(ModelInfo("m1"))) {
        every { sessionManager.sessions } returns MutableStateFlow(mapOf("s1" to connectedSession(models)))
        every { sessionManager.modelsForProfile("p1") } returns models
        coEvery { connectionRepository.getById("p1") } returns sh.haven.core.data.db.entities.ConnectionProfile(
            id = "p1", label = "Llama", host = "e", username = "u",
        )
        // A non-tunnel profile still goes through the resolver (the guard runs
        // before argument validation) — no factory is the correct answer.
        coEvery { tunnelResolver.socketFactory(any()) } returns null
    }

    private fun stubNoSession() {
        every { sessionManager.sessions } returns MutableStateFlow(emptyMap())
        every { sessionManager.modelsForProfile("p1") } returns emptyList()
    }

    @Test
    fun `list_models has ONCE_PER_SESSION consent and chat has EVERY_CALL`() {
        val tools = tools()
        assertEquals(ConsentLevel.ONCE_PER_SESSION, tools["openai_list_models"]!!.consentLevel)
        assertEquals(ConsentLevel.EVERY_CALL, tools["openai_chat"]!!.consentLevel)
    }

    @Test
    fun `list_models returns cached models`() = runTest {
        stubConnected(listOf(ModelInfo("qwen3"), ModelInfo("m2", "test")))
        val result = tools().getValue("openai_list_models").handle(JSONObject().put("profileId", "p1"))
        val json = (result as ToolResult.Structured).structured
        assertEquals(2, json.getInt("count"))
        assertEquals("qwen3", json.getJSONArray("models").getJSONObject(0).getString("id"))
        assertEquals("test", json.getJSONArray("models").getJSONObject(1).getString("ownedBy"))
    }

    @Test
    fun `list_models without a connected session throws with the connect hint`() = runTest {
        stubNoSession()
        val thrown = runCatching {
            tools().getValue("openai_list_models").handle(JSONObject().put("profileId", "p1"))
        }
        val error = thrown.exceptionOrNull()
        assertTrue(error is McpError)
        assertEquals(-32603, (error as McpError).code)
        assertTrue(error.message!!.contains("connect_profile"))
    }

    @Test
    fun `chat defaults the model to the first advertised one and returns only the reply`() = runTest {
        stubConnected()
        coEvery { tunnelResolver.socketFactory(any()) } returns null
        coEvery {
            openAiClient.buildClient(null)
        } returns mockk()
        coEvery {
            openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "hello from qwen3"

        val result = tools().getValue("openai_chat").handle(
            JSONObject().put("profileId", "p1").put("message", "hi"),
        )
        val json = (result as ToolResult.Structured).structured
        assertEquals("hello from qwen3", json.getString("reply"))
        assertEquals("m1", json.getString("model"))
        assertEquals("p1", json.getString("profileId"))
        // The reply carries no key material — the full key set of the result.
        val keys = json.keys().asSequence().toSet()
        assertEquals(setOf("profileId", "model", "reply"), keys)
        coVerify {
            openAiClient.chatCompletion(any(), "http://e:8090", null, null, "m1", any(), null, null, AiProtocol.OPENAI)
        }
    }

    @Test
    fun `chat passes the profile's stored protocol through to the client`() = runTest {
        stubConnected()
        coEvery { connectionRepository.getById("p1") } returns sh.haven.core.data.db.entities.ConnectionProfile(
            id = "p1", label = "Llama", host = "e", username = "u", aiProtocol = "ANTHROPIC",
        )
        coEvery { tunnelResolver.socketFactory(any()) } returns null
        coEvery { openAiClient.buildClient(null) } returns mockk()
        coEvery {
            openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "hey"
        tools().getValue("openai_chat").handle(JSONObject().put("profileId", "p1").put("message", "hi"))
        coVerify {
            openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), AiProtocol.ANTHROPIC)
        }
    }

    @Test
    fun `chat on a tunnel profile with no factory is refused without dialling`() = runTest {
        stubConnected()
        val profile = sh.haven.core.data.db.entities.ConnectionProfile(
            id = "p1", label = "Llama", host = "e", username = "u", tunnelConfigId = "t1",
        )
        coEvery { connectionRepository.getById("p1") } returns profile
        coEvery { tunnelResolver.socketFactory(profile) } returns null

        val thrown = runCatching {
            tools().getValue("openai_chat").handle(
                JSONObject().put("profileId", "p1").put("message", "hi"),
            )
        }
        val error = thrown.exceptionOrNull()
        assertTrue(error is McpError && (error as McpError).code == -32603)
        assertTrue((error as McpError).message!!.contains("refusing"))
        coVerify(exactly = 0) { openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `chat on a tunnel profile with a factory dials through it`() = runTest {
        stubConnected()
        val profile = sh.haven.core.data.db.entities.ConnectionProfile(
            id = "p1", label = "Llama", host = "e", username = "u", tunnelConfigId = "t1",
        )
        coEvery { connectionRepository.getById("p1") } returns profile
        val factory = mockk<SocketFactory>()
        coEvery { tunnelResolver.socketFactory(profile) } returns factory
        val tunneledClient = mockk<okhttp3.OkHttpClient>()
        coEvery { openAiClient.buildClient(factory) } returns tunneledClient
        coEvery {
            openAiClient.chatCompletion(tunneledClient, any(), any(), any(), any(), any(), any(), any(), any())
        } returns "via tunnel"

        val result = tools().getValue("openai_chat").handle(
            JSONObject().put("profileId", "p1").put("message", "hi"),
        )
        assertEquals("via tunnel", (result as ToolResult.Structured).structured.getString("reply"))
    }

    @Test
    fun `messages array roles are validated`() = runTest {
        stubConnected()
        coEvery { tunnelResolver.socketFactory(any()) } returns null
        val bad = JSONObject().put("profileId", "p1").put(
            "messages",
            JSONArray().put(JSONObject().put("role", "tool").put("content", "x")),
        )
        val thrown = runCatching {
            tools().getValue("openai_chat").handle(bad)
        }
        assertTrue(thrown.exceptionOrNull() is McpError)
    }

    @Test
    fun `neither message nor messages is a client error`() = runTest {
        stubConnected()
        val thrown = runCatching {
            tools().getValue("openai_chat").handle(JSONObject().put("profileId", "p1"))
        }
        assertTrue(thrown.exceptionOrNull() is McpError)
        coVerify(exactly = 0) { openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `streaming path is unreachable from MCP and chat writes nothing anywhere`() = runTest {
        stubConnected()
        coEvery { tunnelResolver.socketFactory(any()) } returns null
        coEvery { openAiClient.buildClient(null) } returns mockk()
        coEvery {
            openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "r"

        tools().getValue("openai_chat").handle(JSONObject().put("profileId", "p1").put("message", "hi"))
        coVerify(exactly = 0) { openAiClient.chatCompletionStream(any(), any(), any(), any(), any(), any()) }
        // No persistence collaborator exists at all — assert the repository
        // saw only the getById lookup, nothing that could write.
        coVerify { connectionRepository.getById("p1") }
        coVerify(exactly = 0) { sessionManager.modelsForProfile(any()) }
    }

    // ---- openai_chat vision (imageBase64 / imageMimeType) ----

    private fun stubCompletionReturning(reply: String) {
        coEvery { tunnelResolver.socketFactory(any()) } returns null
        coEvery { openAiClient.buildClient(null) } returns mockk()
        coEvery {
            openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns reply
    }

    @Test
    fun `imageBase64 attaches to the user turn with a default mime type`() = runTest {
        stubConnected()
        stubCompletionReturning("it is a red square")
        val messagesSlot = slot<List<ChatMessage>>()
        coEvery {
            openAiClient.chatCompletion(any(), any(), any(), any(), any(), capture(messagesSlot), any(), any(), any())
        } returns "it is a red square"

        val result = tools().getValue("openai_chat").handle(
            JSONObject()
                .put("profileId", "p1")
                .put("message", "what is this?")
                .put("imageBase64", "AAAA"),
        )
        assertEquals("it is a red square", (result as ToolResult.Structured).structured.getString("reply"))
        val image = messagesSlot.captured.single().images.single()
        assertEquals("image/jpeg", image.mimeType)
        assertEquals("AAAA", image.base64)
        assertEquals("what is this?", messagesSlot.captured.single().content)
    }

    @Test
    fun `imageBase64 honours an explicit mime type`() = runTest {
        stubConnected()
        stubCompletionReturning("ok")
        val messagesSlot = slot<List<ChatMessage>>()
        coEvery {
            openAiClient.chatCompletion(any(), any(), any(), any(), any(), capture(messagesSlot), any(), any(), any())
        } returns "ok"
        tools().getValue("openai_chat").handle(
            JSONObject()
                .put("profileId", "p1")
                .put("message", "look")
                .put("imageBase64", "AAAA")
                .put("imageMimeType", "image/png"),
        )
        assertEquals("image/png", messagesSlot.captured.single().images.single().mimeType)
    }

    @Test
    fun `imageMimeType outside the allowed set is a client error before dialling`() = runTest {
        stubConnected()
        stubCompletionReturning("ok")
        val thrown = runCatching {
            tools().getValue("openai_chat").handle(
                JSONObject()
                    .put("profileId", "p1")
                    .put("message", "look")
                    .put("imageBase64", "AAAA")
                    .put("imageMimeType", "image/tiff"),
            )
        }
        val error = thrown.exceptionOrNull()
        assertTrue(error is McpError && (error as McpError).code == -32602)
        coVerify(exactly = 0) { openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `oversized imageBase64 is a client error before dialling`() = runTest {
        stubConnected()
        stubCompletionReturning("ok")
        // 4 chars per 3 bytes: anything past maxImageBytes/3*4 overflows 5 MB.
        val oversized = "A".repeat(5 * 1024 * 1024 / 3 * 4 + 4)
        val thrown = runCatching {
            tools().getValue("openai_chat").handle(
                JSONObject()
                    .put("profileId", "p1")
                    .put("message", "look")
                    .put("imageBase64", oversized),
            )
        }
        val error = thrown.exceptionOrNull()
        assertTrue(error is McpError && (error as McpError).code == -32602)
        coVerify(exactly = 0) { openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `imageBase64 with a messages array is a client error`() = runTest {
        stubConnected()
        stubCompletionReturning("ok")
        val thrown = runCatching {
            tools().getValue("openai_chat").handle(
                JSONObject()
                    .put("profileId", "p1")
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                    .put("imageBase64", "AAAA"),
            )
        }
        assertTrue(thrown.exceptionOrNull() is McpError)
        coVerify(exactly = 0) { openAiClient.chatCompletion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}