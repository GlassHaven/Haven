package sh.haven.core.openai

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Session lifecycle against a MockWebServer: register → connect → state,
 * the fail-closed tunnel contract (re-asserted in [OpenAiSessionManager.connectSession]),
 * error propagation into the session state, and profile lookups.
 * `android.util.Log` is stubbed by the module's `unitTests.isReturnDefaultValues`.
 */
class OpenAiSessionManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var manager: OpenAiSessionManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        manager = OpenAiSessionManager(OpenAiClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString()

    private fun connectedSession(): Pair<String, OpenAiConnectParams> {
        val sessionId = manager.registerSession("p1", "Llama")
        val params = OpenAiConnectParams(baseUrl = baseUrl())
        return sessionId to params
    }

    @Test
    fun `register starts CONNECTING and connect populates models`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"qwen3"}]}"""),
        )
        val (sessionId, params) = connectedSession()
        assertEquals(OpenAiSessionManager.SessionState.Status.CONNECTING, manager.sessions.value[sessionId]!!.status)

        manager.connectSession(sessionId, params)

        val state = manager.sessions.value[sessionId]!!
        assertEquals(OpenAiSessionManager.SessionState.Status.CONNECTED, state.status)
        assertEquals(listOf("qwen3"), state.models.map { it.id })
        assertEquals(baseUrl(), state.baseUrl)
        assertNull(state.errorMessage)
        assertTrue(manager.isProfileConnected("p1"))
        assertEquals(sessionId, manager.getSessionIdForProfile("p1"))
        assertEquals(listOf("qwen3"), manager.modelsForProfile("p1").map { it.id })
    }

    @Test
    fun `fail-closed tunnel contract refuses direct dial`() = runTest {
        val (sessionId, params) = connectedSession()
        val refused = params.copy(tunnelConfigured = true, socketFactory = null)

        val thrown = runCatching { manager.connectSession(sessionId, refused) }

        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(
            OpenAiSessionManager.SessionState.Status.ERROR,
            manager.sessions.value[sessionId]!!.status,
        )
        assertTrue(
            manager.sessions.value[sessionId]!!.errorMessage!!
                .startsWith("Tunnel configured but provides no socket factory"),
        )
        assertFalse(manager.isProfileConnected("p1"))
        // No HTTP attempt: the server saw no request at all.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tunnel with a socket factory is allowed through`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val sessionId = manager.registerSession("p1", "tunneled")
        manager.connectSession(
            sessionId,
            OpenAiConnectParams(
                baseUrl = baseUrl(),
                socketFactory = javax.net.SocketFactory.getDefault(),
                tunnelConfigured = true,
            ),
        )
        assertEquals(OpenAiSessionManager.SessionState.Status.CONNECTED, manager.sessions.value[sessionId]!!.status)
    }

    @Test
    fun `verify failure marks session ERROR and rethrows`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val (sessionId, params) = connectedSession()

        val thrown = runCatching { manager.connectSession(sessionId, params) }

        assertTrue(thrown.exceptionOrNull() is OpenAiException.Auth)
        assertEquals(OpenAiSessionManager.SessionState.Status.ERROR, manager.sessions.value[sessionId]!!.status)
        assertEquals(
            "Endpoint rejected the API key (HTTP 401)",
            manager.sessions.value[sessionId]!!.errorMessage,
        )
        assertFalse(manager.isProfileConnected("p1"))
    }

    @Test
    fun `connectSession on unknown session id throws without touching state`() = runTest {
        val thrown = runCatching {
            manager.connectSession("missing", OpenAiConnectParams(baseUrl = baseUrl()))
        }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(0, server.requestCount)
        assertTrue(manager.sessions.value.isEmpty())
    }

    @Test
    fun `removal helpers keep other profiles intact`() {
        val s1 = manager.registerSession("p1", "one")
        val s2 = manager.registerSession("p2", "two")
        manager.removeSession(s1)
        assertNull(manager.sessions.value[s1])
        manager.removeAllSessionsForProfile("p2")
        assertNull(manager.sessions.value[s2])
        assertTrue(manager.sessions.value.isEmpty())
    }

    // --- AI route carrier recording (SessionState.routeSocketFactory) ---

    private val routeFactory: javax.net.SocketFactory =
        javax.net.SocketFactory.getDefault()

    @Test
    fun `routed connect records routeType and routeSocketFactory on the session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"qwen3"}]}"""))
        val sessionId = manager.registerSession("p1", "Llama")

        manager.connectSession(
            sessionId,
            OpenAiConnectParams(
                baseUrl = baseUrl(),
                socketFactory = routeFactory,
                routeType = "SSH",
            ),
        )

        val state = manager.sessions.value[sessionId]!!
        assertEquals(OpenAiSessionManager.SessionState.Status.CONNECTED, state.status)
        assertEquals("SSH", state.routeType)
        assertEquals(routeFactory, state.routeSocketFactory)
    }

    @Test
    fun `routed connect without a factory refuses direct dial`() = runTest {
        val sessionId = manager.registerSession("p1", "Llama")

        val thrown = runCatching {
            manager.connectSession(
                sessionId,
                OpenAiConnectParams(baseUrl = baseUrl(), routeType = "SSH"),
            )
        }

        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(OpenAiSessionManager.SessionState.Status.ERROR, manager.sessions.value[sessionId]!!.status)
        assertTrue(manager.sessions.value[sessionId]!!.errorMessage!!.contains("AI route carrier"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unrouted connect leaves route fields null`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"qwen3"}]}"""))
        val (sessionId, params) = connectedSession()
        manager.connectSession(sessionId, params)
        val state = manager.sessions.value[sessionId]!!
        assertNull(state.routeType)
        assertNull(state.routeSocketFactory)
    }

    @Test
    fun `failSessionsForProfile marks sessions ERROR and clears the route factory`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"}]}"""))
        val s1 = manager.registerSession("p1", "one")
        val s2 = manager.registerSession("p1", "two")
        manager.connectSession(s1, OpenAiConnectParams(baseUrl = baseUrl(), socketFactory = routeFactory, routeType = "SSH"))
        manager.connectSession(s2, OpenAiConnectParams(baseUrl = baseUrl()))

        manager.failSessionsForProfile("p1", "carrier gone")

        val first = manager.sessions.value[s1]!!
        val second = manager.sessions.value[s2]!!
        assertEquals(OpenAiSessionManager.SessionState.Status.ERROR, first.status)
        assertEquals("carrier gone", first.errorMessage)
        assertNull(first.routeSocketFactory)
        assertEquals(OpenAiSessionManager.SessionState.Status.ERROR, second.status)
        assertFalse(manager.isProfileConnected("p1"))
    }

    @Test
    fun `failSessionsForProfile leaves other profiles alone`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"}]}"""))
        val s1 = manager.registerSession("p1", "one")
        manager.connectSession(s1, OpenAiConnectParams(baseUrl = baseUrl()))

        manager.failSessionsForProfile("p-other", "carrier gone")

        assertEquals(OpenAiSessionManager.SessionState.Status.CONNECTED, manager.sessions.value[s1]!!.status)
    }
}