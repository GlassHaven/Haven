package sh.haven.core.vnc.io

/**
 * Rolling-window throughput estimator for VNC session bandwidth (#107
 * follow-up).
 *
 * The session's [CountingInputStream.bytesRead] is sampled at regular
 * intervals; throughput is computed as bytes-delta over time-delta across
 * the oldest and newest samples in the window. A 10 s window gives a
 * stable enough average to make a colour-depth-suggestion decision
 * without reacting to bursty activity.
 *
 * Not thread-safe — the caller (one of the VncClient event loops)
 * sequences sample/compute calls.
 */
class ThroughputTracker(
    private val windowMs: Long = 10_000L,
) {
    private data class Sample(val timeMs: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()

    /** Add a new sample and drop any older than the window. */
    fun sample(timeMs: Long, bytesRead: Long) {
        samples.addLast(Sample(timeMs, bytesRead))
        val cutoff = timeMs - windowMs
        while (samples.size > 2 && samples.first().timeMs < cutoff) {
            samples.removeFirst()
        }
    }

    /**
     * Bytes per second over the current window, or null if there isn't
     * enough data yet (need at least 2 samples spanning at least 1s).
     */
    fun throughputBytesPerSec(): Double? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val dt = last.timeMs - first.timeMs
        if (dt < 1_000) return null
        val db = last.bytes - first.bytes
        return db.toDouble() * 1000.0 / dt.toDouble()
    }

    /** Window span in ms across the current samples (0 if <2 samples). */
    fun windowSpanMs(): Long {
        if (samples.size < 2) return 0L
        return samples.last().timeMs - samples.first().timeMs
    }
}
