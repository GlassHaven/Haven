package sh.haven.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM pins for the SNTP answer build: the discipliner's estimate must
 * reach the wire with its sub-ms field intact.
 */
class SntpServerTest {

    @Test
    fun `answer encodes the discipliner's sub-ms residual at the right scale`() {
        // Regression: utcSubMs is MICROseconds (ns/1e3), ntpFromUnixMs takes
        // sub-MILLseconds. Unconverted, ±300 µs of residual encoded as ±300 ms
        // of the NTP fraction — measured on-device as ±490 ms answer swings
        // around a ±4 ms discipliner, with the offset mean riding the mean
        // residual (−61 µs → −61 ms on the wire).
        val utcMs = 1_700_000_000_000L
        val server = SntpServer(
            port = 10123,
            bindLan = false,
            timeSource = { GpsDiscipliner.NowEstimate(
                utcMs = utcMs,
                utcSubMs = 750.0, // µs → 0.75 ms sub-ms
                jitterMs = 5.0,
                uncertaintyMs = 5.5,
                holdoverMs = 100,
                source = GpsDiscipliner.Source.GNSS_CLOCK,
                samples = 42,
            ) },
            referenceTimeMs = { utcMs - 1_000 },
        )
        server.buildAnswer(
            SntpPacket.ClientRequest(
                leap = 0, version = 4, mode = 3,
                transmit = ByteArray(8) { it.toByte() },
            ),
            version = 4,
        ).let { out ->
            val secs = ((out[32].toLong() and 0xFF) shl 24) or ((out[33].toLong() and 0xFF) shl 16) or
                ((out[34].toLong() and 0xFF) shl 8) or (out[35].toLong() and 0xFF)
            val frac = ((out[36].toLong() and 0xFF) shl 24) or ((out[37].toLong() and 0xFF) shl 16) or
                ((out[38].toLong() and 0xFF) shl 8) or (out[39].toLong() and 0xFF)
            assertEquals(1_700_000_000L + SntpPacket.NTP_UNIX_OFFSET_S, secs)
            // 0.75 ms of a second = 0.00075 * 2^32 ≈ 3_221_225.
            val expected = (0.75 / 1000.0 * (1L shl 32).toDouble()).toLong()
            assertEquals(expected, frac.toLong() and 0xFFFFFFFFL)
        }
    }
}