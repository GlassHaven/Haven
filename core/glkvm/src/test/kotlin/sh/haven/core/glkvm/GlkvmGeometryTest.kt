package sh.haven.core.glkvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GlkvmGeometryTest {

    private val w = 2560
    private val h = 1440

    @Test
    fun `left edge maps to axis min`() {
        assertEquals(-32768, GlkvmGeometry.vFromPxX(0.0, w))
    }

    @Test
    fun `right edge maps to axis max`() {
        assertEquals(32767, GlkvmGeometry.vFromPxX(w.toDouble(), w))
    }

    @Test
    fun `frame centre maps to axis centre`() {
        val v = GlkvmGeometry.vFromPxX(w / 2.0, w)
        assertTrue("centre v=$v expected |v| < 2", abs(v) < 2)
        assertEquals(0, GlkvmGeometry.vFromPxY(h / 2.0, h))
    }

    @Test
    fun `full height spans the y axis`() {
        assertEquals(-32768, GlkvmGeometry.vFromPxY(0.0, h))
        assertEquals(32767, GlkvmGeometry.vFromPxY(h.toDouble(), h))
    }

    @Test
    fun `round trip stays within a pixel across the full frame`() {
        for (x in listOf(0, 30, 128, 400, 850, 1280, 2000, 2432, 2560)) {
            val v = GlkvmGeometry.vFromPxX(x.toDouble(), w)
            val (bx, _) = GlkvmGeometry.pxFromV(v, 0, w, h)
            assertTrue("x=$x round trip ${abs(bx - x)}px", abs(bx - x) <= 1.0)
        }
        for (y in listOf(0, 100, 720, 1300, 1440)) {
            val v = GlkvmGeometry.vFromPxY(y.toDouble(), h)
            val (_, by) = GlkvmGeometry.pxFromV(0, v, w, h)
            assertTrue("y=$y round trip ${abs(by - y)}px", abs(by - y) <= 1.0)
        }
    }

    @Test
    fun `clamps stay inside int16`() {
        assertEquals(-32768, GlkvmGeometry.vFromPxX(-50.0, w))
        assertEquals(32767, GlkvmGeometry.vFromPxX(999_999.0, w))
        assertEquals(-32768, GlkvmGeometry.vFromPxY(-1.0, h))
        assertEquals(32767, GlkvmGeometry.vFromPxY(9_999.0, h))
    }

    @Test
    fun `values match linear full-width mapping for known points`() {
        // At 2560x1440, x=850 maps to round(850 * 65535 / 2560) - 32768 = -11007,
        // which lands at 850px on a full-bleed 16:9 target display.
        val expected = Math.round(850.0 * 65535.0 / 2560.0) - 32768
        assertEquals(expected, GlkvmGeometry.vFromPxX(850.0, w).toLong())
    }

    @Test
    fun `detectHorizontalPillarbox identifies symmetric 16_10 bars in 16_9 frame`() {
        // 16:10 display (2304x1440) centered inside 2560x1440 has 128px black bars on each side.
        val (activeLeft, activeWidth) = GlkvmGeometry.detectHorizontalPillarbox(w) { x ->
            x < 128 || x >= 2432
        }
        assertEquals(128, activeLeft)
        assertEquals(2304, activeWidth)

        // Left active edge (128) -> -32768, center (1280) -> 0, right active edge (2432) -> 32767
        assertEquals(-32768, GlkvmGeometry.vFromPxX(128.0, w, activeLeft, activeWidth))
        assertTrue(abs(GlkvmGeometry.vFromPxX(1280.0, w, activeLeft, activeWidth)) < 2)
        assertEquals(32767, GlkvmGeometry.vFromPxX(2432.0, w, activeLeft, activeWidth))
    }

    @Test
    fun `detectHorizontalPillarbox returns full width when no symmetric bars exist`() {
        val (fullLeft, fullWidth) = GlkvmGeometry.detectHorizontalPillarbox(w) { false }
        assertEquals(0, fullLeft)
        assertEquals(w, fullWidth)

        // Asymmetric dark side (e.g. dark sidebar on left only) must not trigger pillarbox rescaling
        val (asymLeft, asymWidth) = GlkvmGeometry.detectHorizontalPillarbox(w) { x -> x < 180 }
        assertEquals(0, asymLeft)
        assertEquals(w, asymWidth)
    }

    @Test
    fun `isPillarboxBlackRgb accepts full-range and studio-range HDMI black and rejects tinted UI`() {
        // Full-range HDMI black (0, 0, 0)
        assertTrue(GlkvmGeometry.isPillarboxBlackRgb(0, 0, 0))
        // Studio/limited-range BT.709 HDMI black (16, 16, 16) observed on RM10
        assertTrue(GlkvmGeometry.isPillarboxBlackRgb(16, 16, 16))
        // 4:2:0 JPEG ringing on the 1-2px pillarbox transition (13, 17, 20)
        assertTrue(GlkvmGeometry.isPillarboxBlackRgb(13, 17, 20))
        // Dark-mode desktop UI (30, 30, 30) or tinted dark sidebar (12, 18, 22)
        org.junit.Assert.assertFalse(GlkvmGeometry.isPillarboxBlackRgb(30, 30, 30))
        org.junit.Assert.assertFalse(GlkvmGeometry.isPillarboxBlackRgb(12, 18, 22))
    }
}