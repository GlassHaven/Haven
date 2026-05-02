package sh.haven.core.data.terminal

/**
 * Fixed-capacity byte ring used as the agent transport's per-session
 * mirror of recent SSH stdout. Once full, every new byte evicts the
 * oldest one. Concurrent appends from the SSH reader thread are
 * serialised against [snapshot] reads via a single intrinsic lock — the
 * volume here is per-session terminal output, not a hot path, so the
 * lock cost is unmeasurable next to the SSH I/O it gates.
 *
 * Bytes are stored verbatim: ANSI escape sequences, OSC markers, and
 * other control bytes flow through unchanged so the agent (or a tool
 * that strips ANSI) can decide what to do with them.
 */
class ScrollbackRing(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val buffer = ByteArray(capacity)
    private val lock = Any()

    /** Index where the *next* byte will be written (0..capacity-1). */
    private var writePos = 0

    /** Bytes currently held; saturates at [capacity]. */
    private var size = 0

    /**
     * Append [length] bytes from [data] starting at [offset]. Wrapping
     * is handled in two `arraycopy`s rather than a per-byte loop so
     * large bursts (e.g. `cat`'ing a file) don't pay a lock-per-byte
     * cost.
     */
    fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            // If a single append is larger than capacity, only its tail
            // can possibly survive — fast-path that case directly.
            if (length >= capacity) {
                System.arraycopy(data, offset + length - capacity, buffer, 0, capacity)
                writePos = 0
                size = capacity
                return
            }
            val firstChunk = minOf(length, capacity - writePos)
            System.arraycopy(data, offset, buffer, writePos, firstChunk)
            val remaining = length - firstChunk
            if (remaining > 0) {
                System.arraycopy(data, offset + firstChunk, buffer, 0, remaining)
            }
            writePos = (writePos + length) % capacity
            size = minOf(size + length, capacity)
        }
    }

    /**
     * Return all currently-held bytes in chronological order. The
     * returned array is a fresh copy so callers can take their time
     * decoding without holding the lock.
     */
    fun snapshot(): ByteArray {
        synchronized(lock) {
            val out = ByteArray(size)
            if (size < capacity) {
                System.arraycopy(buffer, 0, out, 0, size)
            } else {
                // Ring is full — oldest byte sits at writePos.
                val tail = capacity - writePos
                System.arraycopy(buffer, writePos, out, 0, tail)
                if (writePos > 0) {
                    System.arraycopy(buffer, 0, out, tail, writePos)
                }
            }
            return out
        }
    }
}
