package sh.haven.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneLayoutTest {

    @Test
    fun `splitWith from Single puts active on left and target focused on right`() {
        val next = PaneLayout.Single.splitWith(
            activeSessionId = "ssh-workstation",
            targetSessionId = "local-shell",
        )
        val split = next as PaneLayout.SplitH
        assertEquals("ssh-workstation", split.leftSessionId)
        assertEquals("local-shell", split.rightSessionId)
        assertEquals(SplitSide.RIGHT, split.focusedSide)
        assertEquals("local-shell", split.focusedSessionId)
        assertEquals(0.5f, split.splitFraction, 0.0001f)
        assertTrue(split.contains("ssh-workstation"))
        assertTrue(split.contains("local-shell"))
        assertFalse(split.contains("other"))
    }

    @Test
    fun `splitWith ignores identical or blank session IDs`() {
        assertSame(PaneLayout.Single, PaneLayout.Single.splitWith("s1", "s1"))
        assertSame(PaneLayout.Single, PaneLayout.Single.splitWith("", "s2"))
        assertSame(PaneLayout.Single, PaneLayout.Single.splitWith("s1", " "))

        val existing = PaneLayout.SplitH("s1", "s2", focusedSide = SplitSide.LEFT)
        assertSame(existing, existing.splitWith("s1", "s1"))
    }

    @Test
    fun `splitWith on existing SplitH focuses existing pane or replaces unfocused side`() {
        val initial = PaneLayout.SplitH(
            leftSessionId = "s1",
            rightSessionId = "s2",
            focusedSide = SplitSide.RIGHT,
            splitFraction = 0.6f,
        )

        // Splitting with s1 (already on the left) simply focuses LEFT and preserves fraction.
        val focusLeft = initial.splitWith(activeSessionId = "s2", targetSessionId = "s1") as PaneLayout.SplitH
        assertEquals("s1", focusLeft.leftSessionId)
        assertEquals("s2", focusLeft.rightSessionId)
        assertEquals(SplitSide.LEFT, focusLeft.focusedSide)
        assertEquals(0.6f, focusLeft.splitFraction, 0.0001f)

        // With LEFT focused ("s1"), splitting with third session "s3" keeps "s1" on left and puts "s3" on right.
        val withThird = focusLeft.splitWith(activeSessionId = "s1", targetSessionId = "s3") as PaneLayout.SplitH
        assertEquals("s1", withThird.leftSessionId)
        assertEquals("s3", withThird.rightSessionId)
        assertEquals(SplitSide.RIGHT, withThird.focusedSide)
        assertEquals(0.6f, withThird.splitFraction, 0.0001f)
    }

    @Test
    fun `focusSide and unsplit transition cleanly`() {
        val split = PaneLayout.SplitH("s1", "s2", focusedSide = SplitSide.RIGHT)
        val leftFocused = split.focusSide(SplitSide.LEFT) as PaneLayout.SplitH
        assertEquals(SplitSide.LEFT, leftFocused.focusedSide)
        assertEquals("s1", leftFocused.focusedSessionId)
        assertSame(PaneLayout.Single, leftFocused.unsplit())
    }

    @Test
    fun `resizeSplit clamps fraction between 0_2 and 0_8`() {
        val split = PaneLayout.SplitH("s1", "s2")
        assertEquals(0.2f, (split.resizeSplit(0.05f) as PaneLayout.SplitH).splitFraction, 0.0001f)
        assertEquals(0.8f, (split.resizeSplit(0.95f) as PaneLayout.SplitH).splitFraction, 0.0001f)
        assertEquals(0.35f, (split.resizeSplit(0.35f) as PaneLayout.SplitH).splitFraction, 0.0001f)
        assertSame(PaneLayout.Single, PaneLayout.Single.resizeSplit(0.4f))
    }

    @Test
    fun `onSelectTab focuses matching pane or swaps third tab into focused slot`() {
        val split = PaneLayout.SplitH("s1", "s2", focusedSide = SplitSide.RIGHT)

        val selectLeft = split.onSelectTab("s1") as PaneLayout.SplitH
        assertEquals(SplitSide.LEFT, selectLeft.focusedSide)
        assertEquals("s1", selectLeft.leftSessionId)
        assertEquals("s2", selectLeft.rightSessionId)

        // With LEFT focused, selecting s3 replaces LEFT ("s1" -> "s3") while keeping "s2" on RIGHT.
        val replaceFocused = selectLeft.onSelectTab("s3") as PaneLayout.SplitH
        assertEquals("s3", replaceFocused.leftSessionId)
        assertEquals("s2", replaceFocused.rightSessionId)
        assertEquals(SplitSide.LEFT, replaceFocused.focusedSide)
    }

    @Test
    fun `reconcile auto-unsplits to surviving pane when a session closes or disconnects`() {
        val split = PaneLayout.SplitH("s1", "s2", focusedSide = SplitSide.RIGHT)

        // Both alive -> unchanged, focused session preserved.
        val (bothLayout, bothFocus) = split.reconcile(setOf("s1", "s2", "s3"))
        assertEquals(split, bothLayout)
        assertEquals("s2", bothFocus)

        // Right pane closed -> collapses to Single focused on surviving left pane ("s1").
        val (leftSurvives, leftFocus) = split.reconcile(setOf("s1", "s3"))
        assertSame(PaneLayout.Single, leftSurvives)
        assertEquals("s1", leftFocus)

        // Left pane closed -> collapses to Single focused on surviving right pane ("s2").
        val (rightSurvives, rightFocus) = split.reconcile(setOf("s2"))
        assertSame(PaneLayout.Single, rightSurvives)
        assertEquals("s2", rightFocus)

        // Both closed -> Single with null focus hint.
        val (noneSurvive, noneFocus) = split.reconcile(emptySet())
        assertSame(PaneLayout.Single, noneSurvive)
        assertNull(noneFocus)
    }

    @Test
    fun `effectiveForWidth auto-collapses below 600dp portrait threshold without losing SplitH`() {
        val split = PaneLayout.SplitH("s1", "s2", focusedSide = SplitSide.LEFT)
        assertSame(PaneLayout.Single, split.effectiveForWidth(390))
        assertSame(PaneLayout.Single, split.effectiveForWidth(599))
        assertSame(split, split.effectiveForWidth(600))
        assertSame(split, split.effectiveForWidth(960))
    }
}
