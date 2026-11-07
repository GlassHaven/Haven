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

    @Test
    fun `projection keeps running across viewer detach when user exits fullscreen and switches tabs`() {
        val state = ExternalDisplayProjectionController()
        state.onPresentationDisplaysChanged(listOf(2))

        val tab1View = ExternalDisplaySessionView(state, "tab-1")
        state.onViewerAttached("tab-1")
        assertTrue(state.isViewerMounted("tab-1"))
        assertEquals("tab-1", state.activeSessionKey)
        assertTrue(tab1View.isProjecting)

        state.updateSessionFrame(
            sessionKey = "tab-1",
            frame = null,
            frameSeq = 10L,
            pointerX = 120,
            pointerY = 80,
        )
        assertEquals(10L, state.currentFrameState?.frameSeq)

        // User exits fullscreen mode and switches to Terminal / SFTP / Connections tab:
        // viewer leaves composition, but external display projection stays active and
        // continues accepting background frame updates from the session.
        state.onViewerDetached("tab-1")
        assertFalse(state.isViewerMounted("tab-1"))
        assertEquals("tab-1", state.activeSessionKey)
        assertTrue(state.isProjectingSession("tab-1"))
        assertTrue(tab1View.isProjecting)

        state.updateSessionFrame(
            sessionKey = "tab-1",
            frame = null,
            frameSeq = 11L,
            pointerX = 240,
            pointerY = 160,
        )
        assertEquals(11L, state.currentFrameState?.frameSeq)
        assertEquals(240, state.currentFrameState?.pointerX)
        assertEquals(160, state.currentFrameState?.pointerY)
    }

    @Test
    fun `pinned session stays on external display when switching to another desktop tab until explicitly toggled`() {
        val state = ExternalDisplayProjectionController()
        state.onPresentationDisplaysChanged(listOf(2))

        val tab1View = ExternalDisplaySessionView(state, "tab-1")
        val tab2View = ExternalDisplaySessionView(state, "tab-2")

        state.onViewerAttached("tab-1")
        state.updateSessionFrame(sessionKey = "tab-1", frame = null, frameSeq = 1L)
        assertTrue(tab1View.isProjecting)
        assertFalse(tab2View.isProjecting)

        // Switch phone UI from tab-1 to tab-2: tab-1 stays projected on HDMI,
        // while tab-2 renders on the phone screen (isProjecting == false).
        state.onViewerDetached("tab-1")
        state.onViewerAttached("tab-2")
        state.updateSessionFrame(sessionKey = "tab-2", frame = null, frameSeq = 99L)
        assertEquals("tab-1", state.activeSessionKey)
        assertEquals(1L, state.currentFrameState?.frameSeq)
        assertTrue(tab1View.isProjecting)
        assertFalse(tab2View.isProjecting)

        // Tapping the Tv toggle on tab-2 hands external projection over to tab-2
        tab2View.toggleProjection()
        assertEquals("tab-2", state.activeSessionKey)
        assertFalse(tab1View.isProjecting)
        assertTrue(tab2View.isProjecting)

        // Closing tab-2 releases the session and falls back to tab-1
        state.releaseSession("tab-2", fallbackSessionKey = "tab-1")
        assertEquals("tab-1", state.activeSessionKey)
        assertNull(state.currentFrameState)
        assertTrue(tab1View.isProjecting)
    }
}
