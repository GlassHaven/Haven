package sh.haven.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDisplayProjectionTest {

    @Test
    fun `computeExternalDisplayLayout letterboxes 4_3 framebuffer on 16_9 external monitor`() {
        val layout = computeExternalDisplayLayout(
            viewWidth = 1920,
            viewHeight = 1080,
            fbWidth = 1024,
            fbHeight = 768,
            coverFill = false,
        )
        // scale = min(1920/1024=1.875, 1080/768=1.40625) = 1.40625
        // dstWidth = 1024 * 1.40625 = 1440, dstHeight = 1080
        // left = (1920 - 1440) / 2 = 240, top = 0
        assertEquals(240, layout.left)
        assertEquals(0, layout.top)
        assertEquals(1680, layout.right)
        assertEquals(1080, layout.bottom)
        assertEquals(1.40625f, layout.scale, 1e-4f)
    }

    @Test
    fun `computeExternalCursorPosition maps framebuffer coordinates and hotspot onto external display`() {
        val layout = computeExternalDisplayLayout(
            viewWidth = 1920,
            viewHeight = 1080,
            fbWidth = 1920,
            fbHeight = 1080,
            coverFill = false,
        )
        val (cx, cy) = computeExternalCursorPosition(
            layout = layout,
            pointerX = 500,
            pointerY = 300,
            hotspotX = 4,
            hotspotY = 2,
            fbWidth = 1920,
            fbHeight = 1080,
        )
        assertEquals(496f, cx, 1e-3f)
        assertEquals(298f, cy, 1e-3f)
    }

    @Test
    fun `controller activates external projection when presentation display attaches and toggles cleanly`() {
        val state = ExternalDisplayProjectionController()
        assertFalse(state.hasExternalDisplay)
        assertFalse(state.isProjecting)
        assertNull(state.activeDisplayId)

        // Attach HDMI / USB-C presentation display #2
        state.onPresentationDisplaysChanged(listOf(2))
        assertTrue(state.hasExternalDisplay)
        assertTrue(state.projectionEnabled)
        assertTrue(state.isProjecting)
        assertEquals(2, state.activeDisplayId)

        // User toggles projection off to mirror back onto phone screen
        state.toggleProjection()
        assertTrue(state.hasExternalDisplay)
        assertFalse(state.projectionEnabled)
        assertFalse(state.isProjecting)
        assertNull(state.activeDisplayId)

        // User toggles projection back on
        state.toggleProjection()
        assertTrue(state.isProjecting)
        assertEquals(2, state.activeDisplayId)

        // Unplug external display
        state.onPresentationDisplaysChanged(emptyList())
        assertFalse(state.hasExternalDisplay)
        assertFalse(state.isProjecting)
        assertNull(state.activeDisplayId)
    }
}
