package sh.haven.core.vnc.io

import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Pass-through [InputStream] that counts bytes read. Used by [ThroughputTracker]
 * to estimate VNC session bandwidth — see #107 follow-up for the
 * "auto-suggest lower colour depth on slow links" feature.
 *
 * The counter is volatile + atomic so the tracker thread can read it
 * without locking the session's read thread.
 */
class CountingInputStream(private val delegate: InputStream) : InputStream() {

    private val counter = AtomicLong(0L)

    /** Total bytes successfully read from the delegate since construction. */
    val bytesRead: Long get() = counter.get()

    override fun read(): Int {
        val r = delegate.read()
        if (r >= 0) counter.incrementAndGet()
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) counter.addAndGet(n.toLong())
        return n
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}
