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
        assertTrue(swipeArrowIsUp(scrollUp = false, latchedSwipeArrows = true, altScreen = false))
        assertFalse(swipeArrowIsUp(scrollUp = true, latchedSwipeArrows = true, altScreen = false))
    }

    @Test
    fun `automatic alt-screen path keeps natural-scroll mapping`() {
        // Dragging content down in a pager keeps sending ↑ — unchanged (#255).
        assertTrue(swipeArrowIsUp(scrollUp = true, latchedSwipeArrows = false, altScreen = false))
        assertFalse(swipeArrowIsUp(scrollUp = false, latchedSwipeArrows = false, altScreen = false))
    }

    /**
     * #736 auto-detection: inside an alternate-screen app (vim/htop/lnav) the
     * latched mode follows the page-slide direction — the reporter's page feel:
     * swipe up = next page = ↓ — which is the natural-scroll mapping. At a bare
     * prompt the literal #524 mapping stands.
     */
    @Test
    fun `latched mode inside an alt-screen app follows page-slide direction`() {
        assertTrue(swipeArrowIsUp(scrollUp = true, latchedSwipeArrows = true, altScreen = true))
        assertFalse(swipeArrowIsUp(scrollUp = false, latchedSwipeArrows = true, altScreen = true))
    }

    /** #736: held vertical swipes flip with the same auto-detection rule. */
    @Test
    fun `held swipe direction flips inside alt screen only when latched`() {
        val up = org.connectbot.terminal.SwipeHoldDirection.Up
        val down = org.connectbot.terminal.SwipeHoldDirection.Down
        val left = org.connectbot.terminal.SwipeHoldDirection.Left
        assertEquals(down, swipeHoldArrowDirection(up, latchedSwipeArrows = true, altScreen = true))
        assertEquals(up, swipeHoldArrowDirection(down, latchedSwipeArrows = true, altScreen = true))
        // At a prompt, and in the automatic path, holds stay literal.
        assertEquals(up, swipeHoldArrowDirection(up, latchedSwipeArrows = true, altScreen = false))
        assertEquals(up, swipeHoldArrowDirection(up, latchedSwipeArrows = false, altScreen = true))
        // Horizontal holds are never remapped (pager question is separate).
        assertEquals(left, swipeHoldArrowDirection(left, latchedSwipeArrows = true, altScreen = true))
    }

    /** The wire bytes for the latched round trip: swipe up at a prompt = CSI A. */
    @Test
    fun `latched swipe up at a normal prompt walks history back`() {
        val up = swipeArrowIsUp(scrollUp = false, latchedSwipeArrows = true, altScreen = false)
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

    /**
     * #736 lnav: Shift+arrow reduces the scroll step, Shift+swipe did nothing
     * because the swipe path never carried the toolbar's Shift. The bytes must
     * match what the toolbar's arrow buttons send — libvterm encodes a
     * modified cursor key as CSI 1;<mods+1><letter> in BOTH cursor modes
     * (keyboard.c: mod != 0 falls through to case_CSI), so Shift+Up is
     * ESC[1;2A whether or not DECCKM is set.
     */
    @Test
    fun `shift swipe sends the modified-arrow form the arrow buttons send`() {
        fun tail(bytes: ByteArray) = String(bytes.copyOfRange(1, bytes.size))
        // Plain arrows keep their shipped forms.
        assertEquals("[A", tail(arrowKeyBytes(up = true, appMode = false, shift = false)))
        assertEquals("OA", tail(arrowKeyBytes(up = true, appMode = true, shift = false)))
        // Shift folds into the CSI parameter, mods+1 = 2, in both modes.
        assertEquals("[1;2A", tail(arrowKeyBytes(up = true, appMode = false, shift = true)))
        assertEquals("[1;2B", tail(arrowKeyBytes(up = false, appMode = false, shift = true)))
        assertEquals("[1;2A", tail(arrowKeyBytes(up = true, appMode = true, shift = true)))
        assertEquals("[1;2C", tail(arrowKeyBytesFor(org.connectbot.terminal.SwipeHoldDirection.Right, appMode = false, shift = true)))
        assertEquals("[1;2D", tail(arrowKeyBytesFor(org.connectbot.terminal.SwipeHoldDirection.Left, appMode = true, shift = true)))
    }

    /**
     * #736 vim/htop: the per-swipe step ignored font size. The quantum is a
     * multiple of one text line's height, so a bigger font needs proportionally
     * more travel per arrow instead of flashing through history. At the
     * default font size the shipped feel is unchanged.
     */
    @Test
    fun `swipe quantum scales with font size and keeps the default feel`() {
        assertEquals(SWIPE_ARROWS_SCROLL_QUANTUM, swipeArrowsQuantumMultiplier(14), 0.001f)
        assertEquals(SWIPE_ARROWS_SCROLL_QUANTUM * 2f, swipeArrowsQuantumMultiplier(28), 0.001f)
        // Half the default font halves the travel, but never below the
        // content-scroll default — a sub-1 multiplier would make scrolling
        // twitchier than plain content scroll (termlib clamps it anyway).
        assertEquals(SWIPE_ARROWS_SCROLL_QUANTUM / 2f, swipeArrowsQuantumMultiplier(7), 0.001f)
        assertTrue(swipeArrowsQuantumMultiplier(4) >= 1f)
    }
}
