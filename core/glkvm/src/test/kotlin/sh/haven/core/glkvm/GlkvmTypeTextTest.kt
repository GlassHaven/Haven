package sh.haven.core.glkvm

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * typeText() split semantics: table-covered characters become send_key
 * press/release sequences (Shift held around shifted taps); uncovered
 * characters go to /api/hid/print as UTF-8. The print body must carry the
 * chunk verbatim.
 */
class GlkvmTypeTextTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GlkvmClient

    private val sent = mutableListOf<Triple<String, String?, String?>>() // (kind, key, char)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            var loginCount = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath
                    ?: return MockResponse().setResponseCode(400)
                if (path == "/api/auth/login") {
                    loginCount++
                    return MockResponse().setBody("""{"ok":true,"result":{"token":"tok-x$loginCount"}}""")
                }
                val url = request.requestUrl!!
                return when {
                    path == "/api/hid/events/send_key" -> {
                        synchronized(sent) { sent.add(Triple("key", url.queryParameter("key"), url.queryParameter("state"))) }
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    path == "/api/hid/print" -> {
                        synchronized(sent) { sent.add(Triple("print", null, request.body.readUtf8())) }
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = GlkvmClient.Builder().apply {
            baseUrlOverride = "http://127.0.0.1:${server.port}"
            user = "u"
            password = "p"
        }.build()
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `mapped chars type as position keys with shift`() {
        client.typeText("aB")
        assertEquals(
            listOf(
                Triple("key", "KeyA", "1"),
                Triple("key", "KeyA", "0"),
                Triple("key", "ShiftLeft", "1"),
                Triple("key", "KeyB", "1"),
                Triple("key", "KeyB", "0"),
                Triple("key", "ShiftLeft", "0"),
            ),
            synchronized(sent) { sent.toList() },
        )
    }

    @Test
    fun `uk at-sign is quote-with-shift, backslash the iso key`() {
        client.typeText("@\\", "uk")
        assertEquals(
            listOf(
                Triple("key", "ShiftLeft", "1"),
                Triple("key", "Quote", "1"),
                Triple("key", "Quote", "0"),
                Triple("key", "ShiftLeft", "0"),
                Triple("key", "IntlBackslash", "1"),
                Triple("key", "IntlBackslash", "0"),
            ),
            synchronized(sent) { sent.toList() },
        )
    }

    @Test
    fun `unmapped chars go to the printer verbatim`() {
        client.typeText("a£")
        val print = synchronized(sent) { sent.filter { it.first == "print" } }
        assertEquals(1, print.size)
        assertEquals("£", print[0].third)
        // mapped char still goes through send_key
        assertEquals("KeyA", synchronized(sent) { sent[0].second })
    }

    @Test
    fun `layout selection matches the profile default`() {
        // No layout argument = UK table (the Haven GLKVM profile default).
        client.typeText("@")
        synchronized(sent) {
            assertTrue(
                "should include Quote (UK@)",
                sent.any { it.first == "key" && it.second == "Quote" },
            )
        }
    }

    @Test
    fun `us layout types the at symbol on digit two`() {
        client.typeText("@", "us")
        synchronized(sent) {
            assertTrue(
                "should include Digit2 (US shift-2)",
                sent.any { it.first == "key" && it.second == "Digit2" },
            )
        }
    }

    @Test
    fun `consecutive unmapped chars coalesce into one print call`() {
        client.typeText("a£€¥b", delay = 0)
        val events = synchronized(sent) { sent.toList() }
        val prints = events.filter { it.first == "print" }
        assertEquals(1, prints.size)
        assertEquals("£€¥", prints[0].third)
    }

    @Test
    fun `typeText stops immediately when cancelled mid-paste`() {
        var count = 0
        client.typeText("abcdef", delay = 0, isCancelled = { count++ >= 2 })
        val keyDowns = synchronized(sent) { sent.filter { it.first == "key" && it.third == "1" } }
        assertEquals(2, keyDowns.size)
    }

    @Test
    fun `consecutive shifted chars hold ShiftLeft across the run`() {
        client.typeText("PRa", delay = 0)
        assertEquals(
            listOf(
                Triple("key", "ShiftLeft", "1"),
                Triple("key", "KeyP", "1"),
                Triple("key", "KeyP", "0"),
                Triple("key", "KeyR", "1"),
                Triple("key", "KeyR", "0"),
                Triple("key", "ShiftLeft", "0"),
                Triple("key", "KeyA", "1"),
                Triple("key", "KeyA", "0"),
            ),
            synchronized(sent) { sent.toList() },
        )
    }
}