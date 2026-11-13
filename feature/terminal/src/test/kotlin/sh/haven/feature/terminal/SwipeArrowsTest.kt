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
        val right = org.connectbot.terminal.SwipeHoldDirection.Right
        assertEquals(down, swipeHoldArrowDirection(up, latchedSwipeArrows = true, altScreen = true))
        assertEquals(up, swipeHoldArrowDirection(down, latchedSwipeArrows = true, altScreen = true))
        // At a prompt, and in the automatic path, holds stay literal.
        assertEquals(up, swipeHoldArrowDirection(up, latchedSwipeArrows = true, altScreen = false))
        assertEquals(up, swipeHoldArrowDirection(up, latchedSwipeArrows = false, altScreen = true))
        // #736 follow-up: horizontal holds flip under the same rule — inside
        // htop a hold left walks the view right, matching the page-slide feel
        // the vertical flip established (reporter: "they should be inverted,
        // too"). The pager question (gap 2) is about the unclaimed automatic
        // path, not this latched mapping.
        assertEquals(right, swipeHoldArrowDirection(left, latchedSwipeArrows = true, altScreen = true))
        assertEquals(left, swipeHoldArrowDirection(right, latchedSwipeArrows = true, altScreen = true))
        // Horizontal holds stay literal at a prompt and in the automatic path.
        assertEquals(left, swipeHoldArrowDirection(left, latchedSwipeArrows = true, altScreen = false))
        assertEquals(left, swipeHoldArrowDirection(left, latchedSwipeArrows = false, altScreen = true))
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

    /**
     * #736 htop / lnav / vim (mouse=a): these TUIs enable DECSET ?1000 mouse
     * tracking (`isMouseMode = true`) on startup. Previously `if (isMouseMode)`
     * built a callback without `onSwipeHoldStart` and hardcoded `sgrMouseWheel`
     * in `onScroll`, so latching the toolbar Swipe button (`swipeArrowsMode =
     * true`) dropped horizontal swipes on the floor in `htop` and ignored
     * `Shift`+swipe in `lnav`.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `latched swipeArrowsMode claims horizontal and vertical swipes even in mouse mode`() =
        kotlinx.coroutines.test.runTest {
            val sent = mutableListOf<String>()
            var shift = false
            val cb = buildTerminalGestureCallback(
                isMouseMode = true,
                isAltScreen = true,
                mouseInputEnabled = true,
                terminalRightClick = false,
                swipeArrowsMode = true,
                isEmulatorAltScreenActive = { true },
                cursorKeyAppMode = { false },
                isShiftActive = { shift },
                coroutineScope = this,
                sendInput = { sent += String(it, Charsets.ISO_8859_1) },
            )!!

            // Horizontal left swipe in htop (altScreen=true) flips to Right arrow (ESC[C).
            assertTrue(cb.onSwipeHoldStart(4, 2, org.connectbot.terminal.SwipeHoldDirection.Left))
            testScheduler.runCurrent()
            assertEquals(listOf("\u001b[C"), sent)
            cb.onSwipeHoldEnd()
            sent.clear()

            // Shift + horizontal left swipe in lnav sends ESC[1;2C even in mouse mode.
            shift = true
            assertTrue(cb.onSwipeHoldStart(4, 2, org.connectbot.terminal.SwipeHoldDirection.Left))
            testScheduler.runCurrent()
            assertEquals(listOf("\u001b[1;2C"), sent)
            cb.onSwipeHoldEnd()
            sent.clear()

            // Mouse taps and long-press mouse drags still work alongside latched swipe-arrows,
            // and edge-zone onScroll during an active MouseDrag still sends SGR mouse wheel.
            shift = false
            assertTrue(cb.onTap(5, 3))
            assertEquals(listOf("\u001b[<0;6;4M", "\u001b[<0;6;4m"), sent)
            sent.clear()

            assertTrue(cb.onMouseDrag(2, 1, org.connectbot.terminal.MouseDragPhase.Start))
            assertTrue(cb.onScroll(2, 0, scrollUp = true))
            assertTrue(cb.onMouseDrag(2, 0, org.connectbot.terminal.MouseDragPhase.End))
            assertEquals(
                listOf("\u001b[<0;3;2M", "\u001b[<64;3;1M", "\u001b[<0;3;1m"),
                sent,
            )
            sent.clear()

            // Outside MouseDrag, onScroll in latched swipeArrowsMode sends arrow keys, not wheel.
            assertTrue(cb.onScroll(2, 1, scrollUp = false))
            assertEquals(listOf("\u001b[B"), sent)
        }

    @Test
    fun `unlatched mouse mode declines swipe-hold and keeps SGR wheel`() =
        kotlinx.coroutines.test.runTest {
            val sent = mutableListOf<String>()
            val cb = buildTerminalGestureCallback(
                isMouseMode = true,
                isAltScreen = true,
                mouseInputEnabled = true,
                terminalRightClick = false,
                swipeArrowsMode = false,
                isEmulatorAltScreenActive = { true },
                cursorKeyAppMode = { false },
                isShiftActive = { false },
                coroutineScope = this,
                sendInput = { sent += String(it, Charsets.ISO_8859_1) },
            )!!

            assertFalse(cb.onSwipeHoldStart(4, 2, org.connectbot.terminal.SwipeHoldDirection.Left))
            assertFalse(cb.prefersRowHeightScrollQuantum())
            assertFalse(cb.onHorizontalScroll(4, 2, scrollLeft = false))
            assertTrue(cb.onScroll(4, 2, scrollUp = true))
            assertEquals(listOf("\u001b[<64;5;3M"), sent)
        }

    /**
     * #736 second half: latched swipeArrowsMode requests row-height continuous
     * drag quantization (`prefersRowHeightScrollQuantum() == true`) and maps
     * single-touch `onHorizontalScroll` with the same prompt-literal vs
     * alt-screen page-slide rule and Shift modifier as vertical/held swipes.
     */
    @Test
    fun `latched swipeArrowsMode enables row-height quantum and routes horizontal continuous scroll`() =
        kotlinx.coroutines.test.runTest {
            val sent = mutableListOf<String>()
            var altScreen = true
            var shift = false
            val cb = buildTerminalGestureCallback(
                isMouseMode = true,
                isAltScreen = true,
                mouseInputEnabled = true,
                terminalRightClick = false,
                swipeArrowsMode = true,
                isEmulatorAltScreenActive = { altScreen },
                cursorKeyAppMode = { false },
                isShiftActive = { shift },
                coroutineScope = this,
                sendInput = { sent += String(it, Charsets.ISO_8859_1) },
            )!!

            assertTrue(cb.prefersRowHeightScrollQuantum())
            // Finger dragged LEFT (`scrollLeft = false`) inside alt-screen TUI (`htop`/`lnav`)
            // slides the page left -> view moves right -> Right arrow (`ESC[C`).
            assertTrue(cb.onHorizontalScroll(4, 2, scrollLeft = false))
            assertEquals(listOf("\u001b[C"), sent)
            sent.clear()

            // Shift + horizontal drag in `lnav` sends Shift+Right (`ESC[1;2C`).
            shift = true
            assertTrue(cb.onHorizontalScroll(4, 2, scrollLeft = false))
            assertEquals(listOf("\u001b[1;2C"), sent)
            sent.clear()

            // At a bare shell prompt (`isAltScreen = false`), finger dragged LEFT sends literal Left (`ESC[D`).
            val promptCb = buildTerminalGestureCallback(
                isMouseMode = false,
                isAltScreen = false,
                mouseInputEnabled = true,
                terminalRightClick = false,
                swipeArrowsMode = true,
                isEmulatorAltScreenActive = { false },
                cursorKeyAppMode = { false },
                isShiftActive = { false },
                coroutineScope = this,
                sendInput = { sent += String(it, Charsets.ISO_8859_1) },
            )!!
            assertTrue(promptCb.prefersRowHeightScrollQuantum())
            assertTrue(promptCb.onHorizontalScroll(4, 2, scrollLeft = false))
            assertEquals(listOf("\u001b[D"), sent)
        }
}
