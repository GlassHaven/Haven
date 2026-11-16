package sh.haven.core.local.guestnotify

/**
 * Token-bucket rate limiter for the guest notification bridge (#851).
 *
 * A guest process can loop `haven-notify` arbitrarily, so posting every request
 * straight to the shade would let it spam (or phish) the user. This caps the
 * sustained rate while absorbing a short burst: [burst] tokens up front, refilled
 * at [refillPerSec]. Over-limit requests are rejected (the server acks
 * "rate limited" and counts them) rather than queued.
 *
 * Pure class with an injectable millisecond clock, so JVM tests drive it without
 * sleeping (the [sh.haven.app.agent.NmeaFanOut] purity idiom). Thread-safe: the
 * server's per-connection threads call [tryAcquire] concurrently.
 */
class NotifyRateLimiter(
    private val burst: Int = DEFAULT_BURST,
    private val refillPerSec: Double = DEFAULT_REFILL_PER_SEC,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var tokens: Double = burst.toDouble()
    private var lastMs: Long = clock()

    /** Consume one token if available; false when the bucket is empty. */
    fun tryAcquire(): Boolean = synchronized(lock) {
        val now = clock()
        val elapsedSec = (now - lastMs).coerceAtLeast(0) / 1000.0
        tokens = minOf(burst.toDouble(), tokens + elapsedSec * refillPerSec)
        lastMs = now
        if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    companion object {
        /** Short burst a script can fire at once (e.g. a few step completions). */
        const val DEFAULT_BURST: Int = 5

        /** Sustained rate: ~1 notification/second keeps the shade usable. */
        const val DEFAULT_REFILL_PER_SEC: Double = 1.0
    }
}
