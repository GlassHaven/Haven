package sh.haven.app.reticulum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared-instance interface is registered before its read loop has
 * connected, and Haven sends the rnsh link request within milliseconds of
 * `init` returning. Device-observed twice in a row on 2026-08-25:
 *
 *   [Transport] Sending to <dest> via path (1 hops) on Sideband
 *   [Sideband] processOutgoing called but interface not online
 *              (online=false, detached=true)
 *   [Transport] Transmit error on Sideband: Interface is not online
 *   [Link] Link request <id> sent to <dest>          <- nothing was sent
 *
 * A link request is not retried, so the session then waited out the full 30s
 * establishment timeout having transmitted nothing.
 */
class AwaitOnlineTest {

    private class FakeClock {
        var now: Long = 0
        val slept = mutableListOf<Long>()
        fun sleep(ms: Long) { slept += ms; now += ms }
    }

    @Test
    fun `already online returns immediately without sleeping`() {
        val clock = FakeClock()
        val ok = awaitOnline(5_000, { clock.now }, clock::sleep) { true }
        assertTrue(ok)
        assertTrue("must not sleep when already online", clock.slept.isEmpty())
    }

    @Test
    fun `comes online part way through`() {
        val clock = FakeClock()
        val ok = awaitOnline(5_000, { clock.now }, clock::sleep) { clock.now >= 200 }
        assertTrue(ok)
        assertEquals(200, clock.now)
    }

    @Test
    fun `never online gives up at the timeout`() {
        val clock = FakeClock()
        val ok = awaitOnline(5_000, { clock.now }, clock::sleep) { false }
        assertFalse("must not block past the timeout", ok)
        assertTrue("should not overshoot much: ${clock.now}", clock.now in 5_000..5_050)
    }

    /** A zero timeout must still check once, and must not loop. */
    @Test
    fun `zero timeout checks once`() {
        val clock = FakeClock()
        assertTrue(awaitOnline(0, { clock.now }, clock::sleep) { true })
        assertFalse(awaitOnline(0, { clock.now }, clock::sleep) { false })
        assertTrue(clock.slept.isEmpty())
    }
}
