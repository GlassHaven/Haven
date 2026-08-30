package sh.haven.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #524 reporter feedback on the latched Swipe key: swiping UP sent ↓. The
 * gesture layer speaks content-scroll (finger dragged down = scrollUp=true);
 * the latched mode must map the finger literally, while the automatic
 * alt-screen path (#255) keeps the shipped natural-scroll mapping.
 */
class SwipeArrowsTest {

    @Test
    fun `latched mode - finger up sends arrow up`() {
        // Finger dragged UP arrives as scrollUp=false (content-scroll terms).
        assertTrue(swipeArrowIsUp(scrollUp = false, latchedSwipeArrows = true))
        assertFalse(swipeArrowIsUp(scrollUp = true, latchedSwipeArrows = true))
    }

    @Test
    fun `automatic alt-screen path keeps natural-scroll mapping`() {
        // Dragging content down in a pager keeps sending ↑ — unchanged (#255).
        assertTrue(swipeArrowIsUp(scrollUp = true, latchedSwipeArrows = false))
        assertFalse(swipeArrowIsUp(scrollUp = false, latchedSwipeArrows = false))
    }

    /** The wire bytes for the latched round trip: swipe up at a prompt = CSI A. */
    @Test
    fun `latched swipe up at a normal prompt walks history back`() {
        val up = swipeArrowIsUp(scrollUp = false, latchedSwipeArrows = true)
        val bytes = arrowKeyBytes(up, appMode = false)
        assertEquals(0x1b, bytes[0].toInt())
        assertEquals("[A", String(bytes.copyOfRange(1, bytes.size)))
    }

    /** #524b: all four held-swipe directions map literally, CSI and SS3. */
    @Test
    fun `held swipe directions map to the four arrows`() {
        fun tail(bytes: ByteArray) = String(bytes.copyOfRange(1, bytes.size))
        assertEquals("[A", tail(arrowKeyBytesFor(org.connectbot.terminal.SwipeHoldDirection.Up, appMode = false)))
        assertEquals("[B", tail(arrowKeyBytesFor(org.connectbot.terminal.SwipeHoldDirection.Down, appMode = false)))
        assertEquals("[C", tail(arrowKeyBytesFor(org.connectbot.terminal.SwipeHoldDirection.Right, appMode = false)))
        assertEquals("[D", tail(arrowKeyBytesFor(org.connectbot.terminal.SwipeHoldDirection.Left, appMode = false)))
        assertEquals("OA", tail(arrowKeyBytesFor(org.connectbot.terminal.SwipeHoldDirection.Up, appMode = true)))
        assertEquals("OD", tail(arrowKeyBytesFor(org.connectbot.terminal.SwipeHoldDirection.Left, appMode = true)))
    }

    /** #524b cadence sanity: arm delay is longer than the repeat period. */
    @Test
    fun `hold cadence arms before it repeats`() {
        assertTrue(SWIPE_HOLD_INITIAL_DELAY_MS > SWIPE_HOLD_REPEAT_MS)
    }

    /** #609: the latch owns the pager gesture only while a tab exists. */
    @Test
    fun `latch owns pager swipe only while a tab exists`() {
        assertTrue(pagerSwipeOwnedByArrows(latchedSwipeArrows = true, hasTab = true))
        assertFalse(pagerSwipeOwnedByArrows(latchedSwipeArrows = true, hasTab = false))
        assertFalse(pagerSwipeOwnedByArrows(latchedSwipeArrows = false, hasTab = true))
        assertFalse(pagerSwipeOwnedByArrows(latchedSwipeArrows = false, hasTab = false))
    }
}
