package sh.haven.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM pins for the NMEA fan-out: broadcast, bounded queues, drop accounting. */
class NmeaFanOutTest {

    @Test
    fun `every attached reader gets every sentence`() {
        val fan = NmeaFanOut()
        val a = fan.attach()
        val b = fan.attach()
        assertEquals(2, fan.publish("\$GPGGA,1*47"))
        assertEquals(2, fan.clientCount)
        assertEquals("\$GPGGA,1*47", a.queue.poll())
        assertEquals("\$GPGGA,1*47", b.queue.poll())
        // Detaching stops delivery for that reader only.
        fan.detach(a)
        assertEquals(1, fan.publish("\$GPGGA,2*48"))
        assertEquals(1, fan.clientCount)
        assertTrue(a.queue.isEmpty())
        assertEquals("\$GPGGA,2*48", b.queue.poll())
    }

    @Test
    fun `a slow reader drops and counts instead of blocking the producer`() {
        val fan = NmeaFanOut(capacity = 2)
        val slow = fan.attach()
        fan.attach() // second reader never drained — exercises the same cap
        // Overflow both queues: with capacity 2, 10 sentences → 8 drops per reader.
        repeat(10) { fan.publish("\$GPGGA,$it") }
        assertEquals(2, slow.queue.size)
        assertEquals(8, slow.dropped.get())
        assertEquals(16, fan.droppedTotal)
        // Detached readers stop counting; a fresh reader sees a clean slate.
        fan.detach(slow)
        assertEquals(0, fan.publish("\$GPGGA,fresh"))
    }

    @Test
    fun `drop counting is per reader not shared`() {
        val fan = NmeaFanOut(capacity = 1)
        val idle = fan.attach()
        val draining = fan.attach()
        fan.publish("s1")
        fan.publish("s2") // overflows both
        assertEquals(1, idle.dropped.get())
        assertEquals(1, draining.dropped.get())
        // Drain one reader; the next publish drops only for the idle one.
        draining.queue.poll()
        fan.publish("s3")
        assertEquals(2, idle.dropped.get())
        assertEquals(1, draining.dropped.get())
    }
}