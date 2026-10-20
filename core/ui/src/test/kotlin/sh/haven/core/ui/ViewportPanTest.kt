package sh.haven.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #738: zooming out after an off-centre pinch must not leave the viewport
 * centred on black. The cover rule is what the gesture code lacked.
 */
class ViewportPanTest {

    @Test
    fun zoomOneForcesCentredContent() {
        // Letterboxed content at zoom 1: any residual pan from an off-centre
        // pinch collapses to 0, so the bars stay symmetric and no edge is black.
        assertEquals(0f, clampPanToCover(pan = 120f, contentSize = 800f, viewSize = 1080f, zoom = 1f), 0f)
        assertEquals(0f, clampPanToCover(pan = -45f, contentSize = 800f, viewSize = 1080f, zoom = 1f), 0f)
    }

    @Test
    fun exactFitAxisCollapsesToZeroAtZoomOne() {
        // Axis where content exactly fills the view at zoom 1 (the phone's
        // long axis): max pan is 0 until zoomed in.
        assertEquals(0f, clampPanToCover(pan = 30f, contentSize = 1080f, viewSize = 1080f, zoom = 1f), 0f)
    }

    @Test
    fun zoomedInAllowsPanUpToCoverBound() {
        // content 1080 wide, view 1080, zoom 2 → scaled 2160, max pan 540.
        assertEquals(540f, clampPanToCover(pan = 900f, contentSize = 1080f, viewSize = 1080f, zoom = 2f), 0f)
        assertEquals(-540f, clampPanToCover(pan = -900f, contentSize = 1080f, viewSize = 1080f, zoom = 2f), 0f)
        assertEquals(200f, clampPanToCover(pan = 200f, contentSize = 1080f, viewSize = 1080f, zoom = 2f), 0f)
    }

    @Test
    fun letterboxAxisStaysCentredUntilZoomCoversIt() {
        // Portrait phone, landscape desktop: short axis letterboxed (content
        // 608 in a 1080 view). Even at zoom 1.5 (912) it still doesn't cover —
        // pan stays 0; at zoom 2 (1216) panning opens up.
        assertEquals(0f, clampPanToCover(pan = 100f, contentSize = 608f, viewSize = 1080f, zoom = 1.5f), 0f)
        assertEquals(68f, clampPanToCover(pan = 100f, contentSize = 608f, viewSize = 1080f, zoom = 2f), 0f)
    }

    @Test
    fun degenerateInputsCollapseToZero() {
        assertEquals(0f, clampPanToCover(pan = 50f, contentSize = 0f, viewSize = 1080f, zoom = 2f), 0f)
        assertEquals(0f, clampPanToCover(pan = 50f, contentSize = 800f, viewSize = 0f, zoom = 2f), 0f)
        assertEquals(0f, clampPanToCover(pan = 50f, contentSize = 800f, viewSize = 1080f, zoom = 0f), 0f)
    }
}
