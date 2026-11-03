package sh.haven.feature.rdp

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpInputModeTest {

    @Test
    fun `effectiveRdpInputMode preserves TOUCHSCREEN when touchscreenCapable is true`() {
        assertEquals("DIRECT", effectiveRdpInputMode("DIRECT", touchscreenCapable = true))
        assertEquals("TOUCHPAD", effectiveRdpInputMode("TOUCHPAD", touchscreenCapable = true))
        assertEquals("TOUCHSCREEN", effectiveRdpInputMode("TOUCHSCREEN", touchscreenCapable = true))
    }

    @Test
    fun `effectiveRdpInputMode falls back from TOUCHSCREEN to DIRECT when not capable`() {
        assertEquals("DIRECT", effectiveRdpInputMode("DIRECT", touchscreenCapable = false))
        assertEquals("TOUCHPAD", effectiveRdpInputMode("TOUCHPAD", touchscreenCapable = false))
        assertEquals("DIRECT", effectiveRdpInputMode("TOUCHSCREEN", touchscreenCapable = false))
    }

    @Test
    fun `nextRdpInputMode cycles 3-way when touchscreenCapable is true`() {
        val step1 = nextRdpInputMode("DIRECT", touchscreenCapable = true)
        assertEquals("TOUCHPAD", step1)
        val step2 = nextRdpInputMode(step1, touchscreenCapable = true)
        assertEquals("TOUCHSCREEN", step2)
        val step3 = nextRdpInputMode(step2, touchscreenCapable = true)
        assertEquals("DIRECT", step3)
    }

    @Test
    fun `nextRdpInputMode cycles 2-way when touchscreenCapable is false`() {
        val step1 = nextRdpInputMode("DIRECT", touchscreenCapable = false)
        assertEquals("TOUCHPAD", step1)
        val step2 = nextRdpInputMode(step1, touchscreenCapable = false)
        assertEquals("DIRECT", step2)
    }

    @Test
    fun `shouldForwardTouchscreenContacts pauses remote touch passthrough when viewport navigation is active`() {
        assertTrue(
            shouldForwardTouchscreenContacts(
                effectiveInputMode = "TOUCHSCREEN",
                hasTouchCallback = true,
                touchscreenViewportNav = false,
            ),
        )
        assertFalse(
            shouldForwardTouchscreenContacts(
                effectiveInputMode = "TOUCHSCREEN",
                hasTouchCallback = true,
                touchscreenViewportNav = true,
            ),
        )
        assertFalse(
            shouldForwardTouchscreenContacts(
                effectiveInputMode = "DIRECT",
                hasTouchCallback = true,
                touchscreenViewportNav = false,
            ),
        )
        assertFalse(
            shouldForwardTouchscreenContacts(
                effectiveInputMode = "TOUCHSCREEN",
                hasTouchCallback = false,
                touchscreenViewportNav = false,
            ),
        )
    }

    @Test
    fun `applyViewportPinch and applyViewportPan zoom and pan local viewport within cover bounds`() {
        val pinched = applyViewportPinch(
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            prevSpan = 100f,
            span = 200f,
            centroid = Offset(960f, 540f),
            viewWidth = 1920f,
            viewHeight = 1080f,
            fbWidth = 1920,
            fbHeight = 1080,
        )
        assertEquals(2f, pinched.zoom, 0.001f)
        assertEquals(0f, pinched.panX, 0.001f)
        assertEquals(0f, pinched.panY, 0.001f)

        val panned = applyViewportPan(
            zoom = pinched.zoom,
            panX = pinched.panX,
            panY = pinched.panY,
            dx = 150f,
            dy = -80f,
            viewWidth = 1920f,
            viewHeight = 1080f,
            fbWidth = 1920,
            fbHeight = 1080,
        )
        assertEquals(2f, panned.zoom, 0.001f)
        assertEquals(150f, panned.panX, 0.001f)
        assertEquals(-80f, panned.panY, 0.001f)
    }
}
