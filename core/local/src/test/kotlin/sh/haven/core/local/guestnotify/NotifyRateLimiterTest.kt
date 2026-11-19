package sh.haven.core.local.guestnotify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the notification token bucket (#851), driven by an
 * injectable clock so no real time passes (the [sh.haven.app.agent.NmeaFanOutTest]
 * idiom).
 */
class NotifyRateLimiterTest {

    private var nowMs = 0L
    private fun limiter(burst: Int = 5, refill: Double = 1.0) =
        NotifyRateLimiter(burst, refill) { nowMs }

    @Test
    fun `burst is allowed then denied`() {
        val l = limiter(burst = 3)
        assertTrue(l.tryAcquire())
        assertTrue(l.tryAcquire())
        assertTrue(l.tryAcquire())
        assertFalse(l.tryAcquire())
        assertFalse(l.tryAcquire())
    }

    @Test
    fun `tokens refill over time`() {
        val l = limiter(burst = 1, refill = 1.0)
        assertTrue(l.tryAcquire())
        assertFalse(l.tryAcquire())
        nowMs += 1000 // one second → one token
        assertTrue(l.tryAcquire())
        assertFalse(l.tryAcquire())
    }

    @Test
    fun `partial refill below one token does not grant`() {
        val l = limiter(burst = 1, refill = 1.0)
        assertTrue(l.tryAcquire())
        nowMs += 500 // half a token
        assertFalse(l.tryAcquire())
        nowMs += 500 // now a full token
        assertTrue(l.tryAcquire())
    }

    @Test
    fun `idle time does not accumulate beyond burst`() {
        val l = limiter(burst = 2, refill = 1.0)
        nowMs += 60_000 // a minute idle
        assertTrue(l.tryAcquire())
        assertTrue(l.tryAcquire())
        assertFalse(l.tryAcquire()) // capped at burst, not 60 tokens
    }

    @Test
    fun `sustained rate matches refill`() {
        val l = limiter(burst = 5, refill = 2.0)
        repeat(5) { assertTrue(l.tryAcquire()) } // drain burst
        assertFalse(l.tryAcquire())
        nowMs += 1000 // 2 tokens/sec
        assertTrue(l.tryAcquire())
        assertTrue(l.tryAcquire())
        assertFalse(l.tryAcquire())
    }

    @Test
    fun `clock moving backwards does not grant extra tokens`() {
        val l = limiter(burst = 1, refill = 1.0)
        assertTrue(l.tryAcquire())
        nowMs -= 5000 // clock jumps back
        assertFalse(l.tryAcquire())
    }
}
