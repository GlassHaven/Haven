package sh.haven.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Two-finger remote scrolling: notches track finger distance in remote pixels,
 * the remainder carries within a gesture, a flick glides and decelerates, and
 * stopping before lifting does not fling.
 */
class RemoteScrollPhysicsTest {

    private val per = RemoteScrollConfig.DEFAULT_PIXELS_PER_NOTCH

    @Test
    fun dragDistanceMapsToNotches() {
        val t = RemoteScrollTracker()
        var notches = 0
        // Fingers move down 10 notches' worth in 20 small steps.
        repeat(20) { i -> notches += t.drag(per / 2, 1000L + i * 8) }
        assertEquals("fingers down = wheel-up, one notch per step distance", 10, notches)
    }

    @Test
    fun smallMovementsAccumulateInsteadOfBeingDropped() {
        val t = RemoteScrollTracker()
        var notches = 0
        repeat(9) { i -> notches += t.drag(-per / 3, 1000L + i * 8) }
        assertEquals(-3, notches)
    }

    @Test
    fun speedScalesDistancePerNotch() {
        val fast = RemoteScrollTracker(RemoteScrollConfig(speed = 2f))
        assertEquals(4, fast.drag(per * 2, 1000L))
        val slow = RemoteScrollTracker(RemoteScrollConfig(speed = 0.5f))
        assertEquals(1, slow.drag(per * 2, 1000L))
    }

    @Test
    fun resetDropsTheLastGesturesRemainder() {
        val t = RemoteScrollTracker()
        t.drag(per * 0.9f, 1000L)
        t.reset()
        assertEquals(0, t.drag(per * 0.2f, 2000L))
    }

    @Test
    fun aFlickGlidesFurtherThanTheDragAndThenStops() {
        val t = RemoteScrollTracker()
        // 2400 px/s downward: 24 px every 10 ms.
        var dragNotches = 0
        for (i in 0..10) dragNotches += t.drag(24f, 1000L + i * 10)
        val v = t.releaseVelocity(1100L)
        assertEquals(2400f, v, 1f)

        val fling = t.fling(v)
        assertNotNull(fling)
        fling!!
        var glide = 0
        var frames = 0
        while (fling.isRunning && frames < 600) {
            glide += fling.step(16)
            frames++
        }
        assertTrue("momentum keeps scrolling the same way", glide > dragNotches)
        assertTrue("and eases to a stop within a few seconds", frames < 400)
    }

    @Test
    fun flingDistanceDoesNotDependOnFrameRate() {
        fun total(stepMs: Long): Int {
            val t = RemoteScrollTracker()
            val f = t.fling(3000f)!!
            var n = 0
            while (f.isRunning) n += f.step(stepMs)
            return n
        }
        assertTrue(abs(total(8) - total(33)) <= 1)
    }

    @Test
    fun stoppingBeforeLiftingDoesNotFling() {
        val t = RemoteScrollTracker()
        for (i in 0..10) t.drag(24f, 1000L + i * 10)
        // Fingers held still for 200 ms, then lifted.
        assertEquals(0f, t.releaseVelocity(1300L), 0f)
    }

    @Test
    fun slowReleaseOrInertiaOffGivesNoFling() {
        assertNull(RemoteScrollTracker().fling(100f))
        assertNull(RemoteScrollTracker(RemoteScrollConfig(inertia = false)).fling(5000f))
    }

    @Test
    fun aWildFlickIsCapped() {
        val t = RemoteScrollTracker()
        val capped = t.fling(1_000_000f)!!
        val reference = t.fling(RemoteScrollTracker.MAX_FLING_VELOCITY)!!
        assertEquals(reference.remainingDistance(), capped.remainingDistance(), 0.01f)
        assertTrue(capped.step(16) <= RemoteScrollTracker.MAX_NOTCHES_PER_EVENT)
    }

    @Test
    fun coarselyBatchedEventsStillFling() {
        // Some touch hardware (and the emulator) delivers a move only every
        // ~67 ms, with the lift ~60 ms after the last one. A flick must still
        // register as moving at the lift, not as "stopped before lifting".
        val t = RemoteScrollTracker()
        t.drag(-157f, 1000L)
        t.drag(-179f, 1067L)
        t.drag(-64f, 1134L)
        val v = t.releaseVelocity(1194L)
        assertTrue("moving up fast at the lift: $v", v < -1500f)
        assertNotNull(t.fling(v))
    }
}
