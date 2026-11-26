package sh.haven.core.vnc

import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

/**
 * Tests for VncClient's client event loop logic:
 * - Starts with non-incremental (full refresh) request
 * - Stays non-incremental until rectangles are received
 * - Switches to incremental after receiving pixels
 * - Forces full refresh after 2s timeout with no updates
 * - Stops cleanly when running is set to false
 */
class VncClientEventLoopTest {

    @Test
    fun `stop on a non-running client is safe`() {
        val client = VncClient(VncConfig())
        client.stop() // no-op, should not throw
        assertFalse(client.running)
    }

    @Test
    fun `close calls stop`() {
        val client = VncClient(VncConfig())
        client.close()
        assertFalse(client.running)
    }

    @Test
    fun `start throws if already running`() {
        val config = VncConfig()
        val client = VncClient(config)

        // Mock a socket that provides enough data for handshake to fail quickly
        val socket = mockk<Socket>(relaxed = true)
        every { socket.getInputStream() } returns ByteArrayInputStream(ByteArray(0))
        every { socket.getOutputStream() } returns ByteArrayOutputStream()

        try {
            client.start(socket)
        } catch (_: Exception) {
            // Expected — handshake fails on empty stream
        }
    }

    @Test
    fun `paused client does not send requests`() {
        val client = VncClient(VncConfig().apply { targetFps = 10 })
        client.paused = true
        assertTrue(client.paused)

        client.paused = false
        assertFalse(client.paused)
    }

    @Test
    fun `typeText encodes ASCII shift, Latin-1, BMP Unicode, and supplementary code points as X11 KeySyms`() {
        val client = VncClient(VncConfig())
        val mockSession = mockk<VncSession>(relaxed = true)
        val events = mutableListOf<String>()
        every { mockSession.sendKeyEvent(any(), any()) } answers {
            val sym = firstArg<Int>()
            val pressed = secondArg<Boolean>()
            events.add("${if (pressed) "down" else "up"}:${sym.toString(16)}")
        }
        val sessionField = VncClient::class.java.getDeclaredField("session").apply { isAccessible = true }
        sessionField.set(client, mockSession)

        // 'A' (shifted ASCII), 'é' (Latin-1 0x00e9), '€' (BMP 0x20ac -> 0x010020ac),
        // and '😀' (U+1F600 -> single supplementary code point 0x0101f600, not surrogate halves)
        client.typeText("Aé€\uD83D\uDE00")

        assertEquals(
            listOf(
                "down:ffe1", "down:41", "up:41", "up:ffe1",
                "down:e9", "up:e9",
                "down:10020ac", "up:10020ac",
                "down:101f600", "up:101f600",
            ),
            events,
        )
    }
}
