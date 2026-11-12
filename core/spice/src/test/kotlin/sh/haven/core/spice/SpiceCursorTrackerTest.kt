package sh.haven.core.spice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import sh.haven.spice.CursorData

class SpiceCursorTrackerTest {

    private class FakeBitmap(val tag: String)

    private fun cursorData(
        width: Int = 2,
        height: Int = 2,
        hotX: Int = 1,
        hotY: Int = 1,
        x: Int = 10,
        y: Int = 20,
        visible: Boolean = true,
        fillByte: Byte = 0x7F,
    ): CursorData = CursorData(
        width = width.toUShort(),
        height = height.toUShort(),
        hotX = hotX.toUShort(),
        hotY = hotY.toUShort(),
        x = x,
        y = y,
        visible = visible,
        pixels = ByteArray((width * height * 4).coerceAtLeast(0)) { fillByte },
    )

    @Test
    fun `position-only cursor moves decode shape once and emit position on every move`() {
        var decodeCalls = 0
        val tracker = SpiceCursorTracker { _, w, h ->
            decodeCalls++
            FakeBitmap("cursor-${decodeCalls}-${w}x$h")
        }
        val positions = mutableListOf<Pair<Int, Int>>()
        val shapes = mutableListOf<Triple<FakeBitmap?, Int, Int>>()

        tracker.onCursor(
            cursor = cursorData(x = 10, y = 20, fillByte = 0x11),
            onPosition = { x, y -> positions.add(x to y) },
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        tracker.onCursor(
            cursor = cursorData(x = 30, y = 40, fillByte = 0x11),
            onPosition = { x, y -> positions.add(x to y) },
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        tracker.onCursor(
            cursor = cursorData(x = 50, y = 60, fillByte = 0x11),
            onPosition = { x, y -> positions.add(x to y) },
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )

        assertEquals(listOf(10 to 20, 30 to 40, 50 to 60), positions)
        assertEquals(1, decodeCalls)
        assertEquals(1, shapes.size)
        assertEquals("cursor-1-2x2", shapes.single().first?.tag)
        assertEquals(1, shapes.single().second)
        assertEquals(1, shapes.single().third)
    }

    @Test
    fun `pixel or dimension change decodes new shape while hotspot-only change reuses decoded bitmap`() {
        var decodeCalls = 0
        val tracker = SpiceCursorTracker { _, w, h ->
            decodeCalls++
            FakeBitmap("cursor-${decodeCalls}-${w}x$h")
        }
        val shapes = mutableListOf<Triple<FakeBitmap?, Int, Int>>()

        tracker.onCursor(
            cursor = cursorData(hotX = 1, hotY = 2, fillByte = 0x22),
            onPosition = null,
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        // Hotspot-only update with identical pixels: re-emits onShape with the cached bitmap.
        tracker.onCursor(
            cursor = cursorData(hotX = 3, hotY = 4, fillByte = 0x22),
            onPosition = null,
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        // New pixel data: decodes a fresh bitmap.
        tracker.onCursor(
            cursor = cursorData(hotX = 3, hotY = 4, fillByte = 0x33),
            onPosition = null,
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )

        assertEquals(2, decodeCalls)
        assertEquals(3, shapes.size)
        assertSame(shapes[0].first, shapes[1].first)
        assertEquals(3 to 4, shapes[1].second to shapes[1].third)
        assertEquals("cursor-2-2x2", shapes[2].first?.tag)
    }

    @Test
    fun `hide emits null once and re-showing same shape re-emits cached bitmap without re-decoding`() {
        var decodeCalls = 0
        val tracker = SpiceCursorTracker { _, _, _ ->
            decodeCalls++
            FakeBitmap("bmp-$decodeCalls")
        }
        val shapes = mutableListOf<Triple<FakeBitmap?, Int, Int>>()

        // Initial connect CURSOR_INIT(visible=false, empty) emits null once (#598).
        tracker.onCursor(
            cursor = CursorData(0u, 0u, 0u, 0u, 0, 0, visible = false, pixels = ByteArray(0)),
            onPosition = null,
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        // Repeated hidden cursor moves do not spam null emissions.
        tracker.onCursor(
            cursor = CursorData(0u, 0u, 0u, 0u, 5, 6, visible = false, pixels = ByteArray(0)),
            onPosition = null,
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        assertEquals(1, shapes.size)
        assertEquals(null, shapes[0].first)

        // Show shape -> decode #1.
        tracker.onCursor(
            cursor = cursorData(hotX = 2, hotY = 3, visible = true, fillByte = 0x44),
            onPosition = null,
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        val firstBmp = shapes[1].first
        assertEquals(1, decodeCalls)

        // Hide -> emits null once.
        tracker.onCursor(
            cursor = cursorData(visible = false, fillByte = 0x44),
            onPosition = null,
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        assertEquals(3, shapes.size)
        assertEquals(null, shapes[2].first)

        // Show identical shape again -> re-emits cached bitmap with zero extra decode calls.
        tracker.onCursor(
            cursor = cursorData(hotX = 2, hotY = 3, visible = true, fillByte = 0x44),
            onPosition = null,
            onShape = { bmp, hx, hy -> shapes.add(Triple(bmp, hx, hy)) },
        )
        assertEquals(1, decodeCalls)
        assertEquals(4, shapes.size)
        assertSame(firstBmp, shapes[3].first)
        assertEquals(2 to 3, shapes[3].second to shapes[3].third)
    }

    @Test
    fun `mutating caller pixel array after onCursor does not fool change detection`() {
        var decodeCalls = 0
        val tracker = SpiceCursorTracker { _, _, _ ->
            decodeCalls++
            FakeBitmap("bmp-$decodeCalls")
        }
        val pixels = ByteArray(16) { 0x55 }
        tracker.onCursor(
            cursor = CursorData(2u, 2u, 0u, 0u, 0, 0, visible = true, pixels = pixels),
            onPosition = null,
            onShape = null,
        )
        pixels[0] = 0x66
        tracker.onCursor(
            cursor = CursorData(2u, 2u, 0u, 0u, 1, 1, visible = true, pixels = pixels),
            onPosition = null,
            onShape = null,
        )
        assertEquals(2, decodeCalls)
    }

    @Test
    fun `clear drops cached bitmap and resets initial visibility emission`() {
        var decodeCalls = 0
        val tracker = SpiceCursorTracker { _, _, _ ->
            decodeCalls++
            FakeBitmap("bmp-$decodeCalls")
        }
        val shapes = mutableListOf<FakeBitmap?>()

        tracker.onCursor(
            cursor = cursorData(fillByte = 0x77),
            onPosition = null,
            onShape = { bmp, _, _ -> shapes.add(bmp) },
        )
        tracker.clear()
        tracker.onCursor(
            cursor = cursorData(fillByte = 0x77),
            onPosition = null,
            onShape = { bmp, _, _ -> shapes.add(bmp) },
        )

        assertEquals(2, decodeCalls)
        assertEquals(listOf("bmp-1", "bmp-2"), shapes.map { it?.tag })
    }

    @Test
    fun `sendMouseClick records move and button press release in verbose log`() {
        val buf = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val session = SpiceSession(
            sessionId = "test-click-log",
            host = "127.0.0.1",
            port = 5900,
            password = null,
            verboseBuffer = buf,
        )
        session.sendMouseClick(320, 240)
        val logText = session.drainVerboseLog().orEmpty()
        org.junit.Assert.assertTrue(logText.contains("input: mouse move #1 -> (320,240)"))
        org.junit.Assert.assertTrue(logText.contains("input: mouse LEFT down (after 1 moves)"))
        org.junit.Assert.assertTrue(logText.contains("input: mouse LEFT up (after 1 moves)"))
    }
}
