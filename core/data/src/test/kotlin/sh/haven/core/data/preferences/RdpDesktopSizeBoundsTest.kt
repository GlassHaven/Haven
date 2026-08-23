package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository.Companion.MAX_RDP_DIMENSION
import sh.haven.core.data.preferences.UserPreferencesRepository.Companion.MIN_RDP_DIMENSION

/**
 * The RDP desktop size is clamped to [MIN_RDP_DIMENSION]..[MAX_RDP_DIMENSION],
 * and the same bound is applied to width and height. At 640 that silently
 * rewrote every mode shorter than 640 — a user asking for 800x600 got 800x640
 * and no explanation (#572).
 */
class RdpDesktopSizeBoundsTest {

    private fun clamp(v: Int) = v.coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)

    /** The mode that was actually being rewritten. */
    @Test
    fun `800x600 survives the clamp unchanged`() {
        assertEquals(800, clamp(800))
        assertEquals(600, clamp(600))
    }

    /** VGA. If a floor rules this out, the floor is wrong. */
    @Test
    fun `640x480 survives the clamp unchanged`() {
        assertEquals(640, clamp(640))
        assertEquals(480, clamp(480))
    }

    @Test
    fun `other standard short modes survive`() {
        for (h in listOf(480, 576, 600, 720, 768, 800)) {
            assertEquals("height $h must not be rewritten", h, clamp(h))
        }
    }

    /** The guard the bound exists for is still in place. */
    @Test
    fun `degenerate sizes are still rejected`() {
        assertEquals(MIN_RDP_DIMENSION, clamp(1))
        assertEquals(MIN_RDP_DIMENSION, clamp(0))
        assertEquals(MAX_RDP_DIMENSION, clamp(100_000))
    }

    @Test
    fun `the floor stays below every standard mode height`() {
        assertTrue(
            "a floor at or above 480 rewrites VGA",
            MIN_RDP_DIMENSION < 480,
        )
    }
}
