package sh.haven.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for what a consent-sheet dismissal means (#556).
 *
 * `ConsentHost` documented the sheet as non-skippable and then resolved every
 * dismissal as a refusal, so a tap on the scrim answered for the user. On a
 * device that showed up as the first tap of a reconnect silently denying a
 * prompt — and being recorded as a deliberate refusal, which arms the #337
 * cooldown.
 */
class ConsentDismissalTest {

    @Test
    fun `a stray dismissal of a pending request re-shows the sheet`() {
        assertTrue(
            "a dismissal the user did not answer must not resolve the request",
            shouldReshowOnDismiss(currentId = 7L, pendingIds = listOf(7L), answeredId = null),
        )
    }

    @Test
    fun `an answered request is not re-shown`() {
        // Resolving clears `pending`, which animates the sheet out and fires
        // the same callback. Re-showing there would trap the user in a sheet
        // they just answered.
        assertFalse(
            shouldReshowOnDismiss(currentId = 7L, pendingIds = listOf(7L), answeredId = 7L),
        )
    }

    @Test
    fun `a request already gone from pending is not re-shown`() {
        // Timed out, or resolved from somewhere else. Nothing left to ask.
        assertFalse(
            shouldReshowOnDismiss(currentId = 7L, pendingIds = emptyList(), answeredId = null),
        )
    }

    @Test
    fun `answering one request does not suppress the next one`() {
        // The host renders the oldest pending request; once id 7 is answered
        // id 8 slides up, and a stray tap on THAT must still re-show.
        assertTrue(
            shouldReshowOnDismiss(currentId = 8L, pendingIds = listOf(8L), answeredId = 7L),
        )
    }

    @Test
    fun `a queued request behind the current one is still re-shown`() {
        assertTrue(
            shouldReshowOnDismiss(currentId = 7L, pendingIds = listOf(7L, 8L), answeredId = null),
        )
    }
}
