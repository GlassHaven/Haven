package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
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
}
