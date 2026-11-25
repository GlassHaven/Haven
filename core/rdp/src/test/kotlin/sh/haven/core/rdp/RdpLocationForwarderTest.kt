package sh.haven.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpLocationForwarderTest {

    private class FakeLocationSource(
        var permitted: Boolean = false,
        var lastKnown: RdpLocationForwarder.Fix? = null,
    ) : RdpLocationForwarder.LocationSourceAdapter {
        var callback: ((RdpLocationForwarder.Fix) -> Unit)? = null
        var stops = 0

        override fun hasLocationPermission(): Boolean = permitted
        override fun bestLastKnownFix(): RdpLocationForwarder.Fix? = lastKnown

        override fun startUpdates(onFix: (RdpLocationForwarder.Fix) -> Unit): Boolean {
            callback = onFix
            return true
        }

        override fun stopUpdates() {
            callback = null
            stops++
        }
    }

    @Test
    fun `waits for permission then emits lastKnown live updates and stops on close`() {
        val fake = FakeLocationSource(
            permitted = false,
            lastKnown = RdpLocationForwarder.Fix(51.5074, -0.1278, 15, 1_000L),
        )
        val received = mutableListOf<Triple<Double, Double, Int>>()
        val forwarder = RdpLocationForwarder(
            source = fake,
            sink = { lat, lon, alt -> received.add(Triple(lat, lon, alt)) },
        )

        assertFalse(forwarder.startIfPermitted())
        assertTrue(received.isEmpty())

        // User grants runtime permission mid-session -> startIfPermitted succeeds.
        fake.permitted = true
        assertTrue(forwarder.startIfPermitted())
        assertEquals(1, received.size)
        assertEquals(Triple(51.5074, -0.1278, 15), received[0])

        fake.callback?.invoke(RdpLocationForwarder.Fix(95.0, -190.0, 22, 2_000L))
        assertEquals(2, received.size)
        assertEquals(Triple(90.0, -180.0, 22), received[1])

        forwarder.close()
        assertEquals(1, fake.stops)

        // Post-close fixes are ignored.
        received.clear()
        assertFalse(forwarder.startIfPermitted())
        assertTrue(received.isEmpty())
    }
}
