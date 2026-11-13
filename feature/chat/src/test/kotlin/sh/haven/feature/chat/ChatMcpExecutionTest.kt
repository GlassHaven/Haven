package sh.haven.feature.chat

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.ChatMcpServerConfig
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ChatRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.openai.ChatToolSpec
import sh.haven.core.openai.ModelInfo
import sh.haven.core.openai.OpenAiClient
import sh.haven.core.openai.OpenAiSessionManager

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMcpExecutionTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var aiServer: MockWebServer
    private lateinit var mcpServer: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        aiServer = MockWebServer()
        mcpServer = MockWebServer()
        aiServer.start()
        mcpServer.start()
    }

    @After
    fun tearDown() {
        aiServer.shutdown()
        mcpServer.shutdown()
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `ChatMcpToolRouter keeps unique names clean and disambiguates collisions`() {
        val s1 = ChatMcpServerConfig("1", "Home Assistant", "https://ha/mcp")
        val s2 = ChatMcpServerConfig("2", "Grafana Cloud", "https://graf/mcp")
        val bound = ChatMcpToolRouter.bindTools(
            listOf(
                s1 to listOf(
                    ChatToolSpec("toggle_light", "Toggle light"),
                    ChatToolSpec("status", "HA status"),
                ),
                s2 to listOf(
                    ChatToolSpec("status", "Grafana status"),
                ),
            ),
        )
        assertEquals(setOf("toggle_light", "home_assistant__status", "grafana_cloud__status"), bound.keys)
        assertEquals("status", bound.getValue("home_assistant__status").remoteToolName)
        assertEquals("1", bound.getValue("home_assistant__status").server.id)
        assertEquals("2", bound.getValue("grafana_cloud__status").server.id)
    }

    @Test
    fun `ChatViewModel discovers external MCP tools, executes tool_calls, and streams final reply`() {
        // MCP server: 1) initialize, 2) notifications/initialized, 3) tools/list, 4) tools/call
        mcpServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "mcp-s1")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}"""),
        )
        mcpServer.enqueue(MockResponse().setResponseCode(202))
        mcpServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"sensor_temp","description":"Read room sensor","inputSchema":{"type":"object","properties":{"room":{"type":"string"}}}}]}}""",
                ),
        )
        mcpServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"21.4 C"}],"isError":false}}""",
                ),
        )

        // AI server: Round 1 emits a tool_call for sensor_temp; Round 2 emits the final assistant text
        aiServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_42\",\"type\":\"function\",\"function\":{\"name\":\"sensor_temp\",\"arguments\":\"{\\\"room\\\":\\\"lab\\\"}\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        aiServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Lab is 21.4 C.\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        val serversFlow = MutableStateFlow(
            listOf(
                ChatMcpServerConfig(
                    id = "srv-1",
                    name = "Lab MCP",
                    url = mcpServer.url("/mcp").toString(),
                    bearerToken = "lab-secret",
                    enabled = true,
                ),
            ),
        )
        val userPrefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { userPrefs.chatMcpServers } returns serversFlow

        val sessionManager = mockk<OpenAiSessionManager>()
        every { sessionManager.getSessionsForProfile("prof-ai") } returns listOf(
            OpenAiSessionManager.SessionState(
                sessionId = "sess-ai",
                profileId = "prof-ai",
                label = "Local OpenAI",
                baseUrl = aiServer.url("/").toString(),
                status = OpenAiSessionManager.SessionState.Status.CONNECTED,
                models = listOf(ModelInfo("gpt-4o-mini")),
            ),
        )
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("prof-ai") } returns ConnectionProfile(
            id = "prof-ai",
            label = "Local OpenAI",
            host = "127.0.0.1",
            port = aiServer.port,
            username = "",
            connectionType = "OPENAI",
            openaiApiKey = "sk-test",
        )
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        every { chatRepo.observeAll() } returns flowOf(emptyList())

        val vm = ChatViewModel(
            openAiSessionManager = sessionManager,
            connectionRepository = connRepo,
            chatRepository = chatRepo,
            openAiClient = OpenAiClient(),
            tunnelResolver = mockk(relaxed = true) {
                coEvery { socketFactory(any()) } returns null
            },
            chatAttachBroker = sh.haven.core.data.attach.ChatAttachBroker(),
            chatRemoteReader = mockk(relaxed = true),
            userPreferencesRepository = userPrefs,
            context = mockk(relaxed = true),
        )

        vm.attach("prof-ai")
        vm.send("What is the lab temperature?")

        // Wait up to 3s for OkHttp background callbacks + IO tool roundtrip to complete
        val deadline = System.currentTimeMillis() + 3_000L
        while ((vm.ui.value.streaming || vm.ui.value.messages.any { it.pending }) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        val state = vm.ui.value
        assertNull("unexpected error: ${state.error}", state.error)
        assertFalse(state.streaming)
        assertNull(state.activeToolCallSummary)
        assertEquals(2, state.messages.size)
        assertEquals("Lab is 21.4 C.", state.messages.last().text)

        // Verify the second AI request included the tool result from the MCP server
        aiServer.takeRequest() // round 1
        val round2AiBody = JSONObject(aiServer.takeRequest().body.readUtf8())
        val round2Msgs = round2AiBody.getJSONArray("messages")
        val toolResultMsg = round2Msgs.getJSONObject(round2Msgs.length() - 1)
        assertEquals("tool", toolResultMsg.getString("role"))
        assertEquals("call_42", toolResultMsg.getString("tool_call_id"))
        assertEquals("21.4 C", toolResultMsg.getString("content"))

        // Verify MCP tools/call carried Bearer auth and the parsed arguments
        repeat(3) { mcpServer.takeRequest() } // init, initialized, tools/list
        val callReq = mcpServer.takeRequest()
        assertEquals("Bearer lab-secret", callReq.getHeader("Authorization"))
        val callParams = JSONObject(callReq.body.readUtf8()).getJSONObject("params")
        assertEquals("sensor_temp", callParams.getString("name"))
        assertEquals("lab", callParams.getJSONObject("arguments").getString("room"))
    }
}
