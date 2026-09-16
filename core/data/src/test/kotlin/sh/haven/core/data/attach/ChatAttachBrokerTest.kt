package sh.haven.core.data.attach

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat Files-pick broker: one arm at a time, confirm/cancel resolve the
 * waiter, and the pending flag (the SFTP banner's source) always clears.
 */
class ChatAttachBrokerTest {

    @Test
    fun `confirmPick resolves the awaiting pick and clears pending`() = runTest {
        val broker = ChatAttachBroker()
        var picked: ChatAttachBroker.Pick? = null
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            picked = broker.awaitPick()
        }
        // the arm publishes the banner before the wait suspends
        assertTrue(broker.pending.value)
        broker.confirmPick("prof1", "/srv/img.png", "img.png", 1234)
        job.join()
        assertEquals(ChatAttachBroker.Pick("prof1", "/srv/img.png", "img.png", 1234), picked)
        assertFalse(broker.pending.value)
    }

    @Test
    fun `cancelPick resolves null and clears pending`() = runTest {
        val broker = ChatAttachBroker()
        var picked: ChatAttachBroker.Pick? = ChatAttachBroker.Pick("x", "y", "z", 1)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            picked = broker.awaitPick()
        }
        assertTrue(broker.pending.value)
        broker.cancelPick()
        job.join()
        assertEquals(null, picked)
        assertFalse(broker.pending.value)
    }

    @Test
    fun `a second arm while one is pending refuses`() = runTest {
        val broker = ChatAttachBroker()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { broker.awaitPick() }
        // first arm is active
        assertEquals(null, broker.awaitPick())
        broker.cancelPick()
        job.join()
        // cleared: a fresh arm works again
        var picked: ChatAttachBroker.Pick? = null
        val second = launch(UnconfinedTestDispatcher(testScheduler)) { picked = broker.awaitPick() }
        broker.confirmPick("p", "/a", "a", 1)
        second.join()
        assertEquals("p", picked?.profileId)
    }

    @Test
    fun `cancelling the awaiting caller clears pending and releases the arm`() = runTest {
        val broker = ChatAttachBroker()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { broker.awaitPick() }
        assertTrue(broker.pending.value)
        job.cancel()
        job.join()
        assertFalse(broker.pending.value)
        // the arm is free: confirm with no waiter is a no-op, a new arm works
        broker.confirmPick("p", "/a", "a", 1)
        var picked: ChatAttachBroker.Pick? = null
        val second = launch(UnconfinedTestDispatcher(testScheduler)) { picked = broker.awaitPick() }
        broker.confirmPick("q", "/b", "b", 2)
        second.join()
        assertEquals("q", picked?.profileId)
    }

    @Test
    fun `confirm with no waiter is a no-op`() {
        val broker = ChatAttachBroker()
        broker.confirmPick("p", "/a", "a", 1)
        broker.cancelPick()
        assertFalse(broker.pending.value)
    }
}