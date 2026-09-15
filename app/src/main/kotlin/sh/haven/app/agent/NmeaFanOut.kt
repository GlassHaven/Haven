package sh.haven.app.agent

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * The NMEA broadcast policy behind [GpsGuestServer]: every attached reader
 * gets every sentence, and a slow reader can never stall the producer —
 * `onNmea` runs on the GNSS HandlerThread, so `publish` must never block.
 *
 * The policy is per-client bounded queues with drop-on-full (and a running
 * drop count, surfaced in status). Dropping whole sentences is safe: NMEA
 * sentences are self-delimited records, so a reader that catches up just
 * misses some interleaved detail — no corruption, no stream re-sync.
 * Pure class: no Android types, so JVM tests can drive it directly.
 */
internal class NmeaFanOut(private val capacity: Int = DEFAULT_CAPACITY) {

    class Subscriber internal constructor(
        val queue: LinkedBlockingQueue<String>,
    ) {
        /** Sentences dropped because this reader's queue was full. */
        val dropped: AtomicLong = AtomicLong(0)
        /** True until [close]; a closed subscriber stops receiving. */
        @Volatile internal var active: Boolean = true
    }

    private val clients = java.util.concurrent.CopyOnWriteArrayList<Subscriber>()

    val clientCount: Int get() = clients.count { it.active }

    /** Sentences dropped for at least one reader since the last reset. */
    val droppedTotal: Long get() = clients.sumOf { it.dropped.get() }

    /** Add one reader with its own bounded queue. */
    fun attach(): Subscriber {
        val s = Subscriber(LinkedBlockingQueue(capacity))
        clients.add(s)
        return s
    }

    /** Remove a reader (also marks it closed so in-flight publishes skip it). */
    fun detach(s: Subscriber) {
        s.active = false
        clients.remove(s)
    }

    /**
     * Broadcast one sentence to every reader. Non-blocking: a full queue
     * drops the sentence for that reader and counts it. Returns the number
     * of readers the sentence was queued for.
     */
    fun publish(sentence: String): Int {
        var delivered = 0
        for (c in clients) {
            if (!c.active) continue
            if (c.queue.offer(sentence)) delivered++ else c.dropped.incrementAndGet()
        }
        return delivered
    }

    companion object {
        /** Per-reader queue cap: ~64 sentences ≈ 2 s of full-rate output —
         * enough burst absorption for a reader that briefly blocks, small
         * enough that a wedged reader's memory is bounded. */
        const val DEFAULT_CAPACITY = 64
    }
}