package sh.haven.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM pins for the GPS→UTC reconstruction, the sample fit, and the NMEA parser. */
class GpsDisciplinerTest {

    // --- GPS epoch math ---

    @Test
    fun `gps epoch time converts to unix utc with the leap correction`() {
        // 1000 s after the GPS epoch (1980-01-06 00:00:00 UTC):
        // Unix = GPS epoch (315964800) + 1000 − 18 leap = 315965782.
        val gpsNs = 1_000_000_000_000L
        val unixNs = GpsDiscipliner.gpsNsToUnixUtcNs(gpsNs)
        assertEquals(315_965_782_000_000_000L, unixNs)
    }

    // --- GnssClock reconstruction ---

    @Test
    fun `gnss clock samples discipline the estimate`() {
        val d = GpsDiscipliner()
        // A virtual "true" time: utc starts at 1_000_000 ms and advances 1:1
        // with the elapsed clock. The device's wall clock is NOT consulted.
        var elapsedNs = 0L
        d.setElapsedSource { elapsedNs }
        val startUtcMs = 1_000_000_000_000L // 2001-09-09, epoch ms
        // Two samples, gps == utc + leap (fullBias 0 ⇒ timeNs IS gps time).
        // gpsNs = utcNs − GPS_EPOCH_UNIX_NS + leap.
        d.onGnssClock(
            timeNs = startUtcMs * 1_000_000L - GpsDiscipliner.GPS_EPOCH_UNIX_NS + 18_000_000_000L,
            fullBiasNs = 0L,
            biasNs = null,
            driftNsPerSec = null,
            clockUncertaintyNs = 1_000_000.0, // 1 ms
            receivedElapsedNs = 0L,
        )
        elapsedNs = 1_000_000_000L // 1 s later
        d.onGnssClock(
            timeNs = startUtcMs * 1_000_000L + 1_000_000_000L -
                GpsDiscipliner.GPS_EPOCH_UNIX_NS + 18_000_000_000L,
            fullBiasNs = 0L,
            biasNs = null,
            driftNsPerSec = null,
            clockUncertaintyNs = 1_000_000.0,
            receivedElapsedNs = 1_000_000_000L,
        )
        val est = d.now(elapsedNs = 2_000_000_000L)!!
        assertEquals("gps estimate tracks the fit", startUtcMs + 2_000, est.utcMs)
        assertEquals("source is the gnss clock", GpsDiscipliner.Source.GNSS_CLOCK, est.source)
        assertEquals("holdover starts at the newest sample", 1_000L, est.holdoverMs)
        assertTrue("uncertainty at least the receiver figure", est.uncertaintyMs >= 1.0)
    }

    @Test
    fun `fullBias reconstruction maps a biased clock onto true gps time`() {
        val d = GpsDiscipliner()
        var elapsedNs = 0L
        d.setElapsedSource { elapsedNs }
        // Hardware clock reads 5 s ahead of true GPS time; fullBiasNs = +5e9.
        // gps time = timeNs - fullBiasNs.
        val trueGpsNs = 1_000_000_000_000L
        d.onGnssClock(
            timeNs = trueGpsNs + 5_000_000_000L,
            fullBiasNs = 5_000_000_000L,
            biasNs = null,
            driftNsPerSec = null,
            clockUncertaintyNs = null,
            receivedElapsedNs = 0L,
        )
        elapsedNs = 500_000_000L
        d.onGnssClock(
            timeNs = trueGpsNs + 5_500_000_000L,
            fullBiasNs = 5_000_000_000L,
            biasNs = null,
            driftNsPerSec = null,
            clockUncertaintyNs = null,
            receivedElapsedNs = 500_000_000L,
        )
        val est = d.now()!!
        val expectedUnixMs = (trueGpsNs + GpsDiscipliner.GPS_EPOCH_UNIX_NS - 18_000_000_000L) / 1_000_000L + 500
        assertEquals(expectedUnixMs, est.utcMs)
    }

    // --- fit / holdover ---

    @Test
    fun `estimate is null before two samples`() {
        val d = GpsDiscipliner()
        assertNull(d.now())
        d.onNmeaUtc(1_000_000_000_000L, 0L)
        assertNull(d.now())
    }

    @Test
    fun `stale samples are rejected monotonically`() {
        val d = GpsDiscipliner()
        assertNotNull(d.onNmeaUtc(1_000_000_000_000L, 1_000L))
        // Earlier elapsed: rejected, keeps the first sample as last.
        assertNotNull(d.onNmeaUtc(1_000_000_000_000L + 5_000_000_000L, 500L))
        val est = d.now(elapsedNs = 1_000L)
        // Two valid samples needed — the stale one can't complete the fit.
        assertNull(est)
    }

    @Test
    fun `holdover grows uncertainty and reports holdover ms`() {
        val d = GpsDiscipliner()
        var elapsedNs = 0L
        d.setElapsedSource { elapsedNs }
        d.onNmeaUtc(1_000_000_000_000L, 0L)
        elapsedNs = 10_000_000_000L
        d.onNmeaUtc(1_000_000_010_000L, elapsedNs) // +10,000 ms = 10 s
        val fresh = d.now(elapsedNs = elapsedNs)!!
        val holdover = d.now(elapsedNs = elapsedNs + 60_000_000_000L)!! // 60 s later
        assertEquals(60_000L, holdover.holdoverMs)
        assertTrue(
            "holdover uncertainty must exceed fresh (${holdover.uncertaintyMs} vs ${fresh.uncertaintyMs})",
            holdover.uncertaintyMs > fresh.uncertaintyMs,
        )
        // The estimate still advances 1:1.
        assertEquals(fresh.utcMs + 60_000, holdover.utcMs)
    }

    @Test
    fun `a drifting clock is tracked by the slope`() {
        val d = GpsDiscipliner()
        var elapsedNs = 0L
        d.setElapsedSource { elapsedNs }
        // UTC advances 0.999 s per 1 s of elapsed clock (1000 ppm — far
        // beyond a real receiver, but proves the slope carries it).
        val utc0 = 2_000_000_000_000L
        d.onNmeaUtc(utc0, 0L)
        elapsedNs = 1_000_000_000L
        d.onNmeaUtc(utc0 + 999L, elapsedNs)
        // Two points determine the line exactly: utc(2 s) = utc0 + 1998 ms.
        val est = d.now(elapsedNs = 2_000_000_000L)!!
        assertEquals(utc0 + 1_998, est.utcMs)
    }

    @Test
    fun `estimate is anchored to the newest sample not the window intercept`() {
        val d = GpsDiscipliner()
        var elapsedNs = 0L
        d.setElapsedSource { elapsedNs }
        d.onNmeaUtc(1_000_000_000_000L, 0L)
        d.onNmeaUtc(1_000_000_001_000L, 1_000_000_000L) // consistent 1 Hz
        // Half a second past the newest sample: the anchored estimate
        // advances exactly with elapsed time — a window slide dropping a
        // jittered old sample must not pivot the served time.
        val est = d.now(elapsedNs = 1_500_000_000L)!!
        assertEquals(1_000_000_001_500L, est.utcMs)
    }

    @Test
    fun `nmea does not displace the gnss clock anchor while the clock is current`() {
        // Regression: NMEA's UTC is delivery-latency-bound (±250 ms arrival
        // jitter measured on-device), and it lands ~1 Hz just after the fix —
        // so it kept displacing GnssClock samples as the anchor and the SNTP
        // answers sawtoothed at the NMEA rate (wire stdev 254 ms).
        val d = GpsDiscipliner()
        var elapsedNs = 0L
        d.setElapsedSource { elapsedNs }
        val utc0 = 1_000_000_000_000L
        // Two clock samples establish the fit.
        d.onGnssClock(
            timeNs = utc0 * 1_000_000L - GpsDiscipliner.GPS_EPOCH_UNIX_NS + 18_000_000_000L,
            fullBiasNs = 0L, biasNs = null, driftNsPerSec = null,
            clockUncertaintyNs = 1_000_000.0, receivedElapsedNs = 0L,
        )
        elapsedNs = 1_000_000_000L
        d.onGnssClock(
            timeNs = (utc0 + 1_000) * 1_000_000L - GpsDiscipliner.GPS_EPOCH_UNIX_NS + 18_000_000_000L,
            fullBiasNs = 0L, biasNs = null, driftNsPerSec = null,
            clockUncertaintyNs = 1_000_000.0, receivedElapsedNs = elapsedNs,
        )
        // An NMEA sample 200 ms late (its UTC reads 200 ms behind truth,
        // arrival 100 ms after the clock sample): within the fallback
        // window it must NOT become the anchor.
        elapsedNs = 1_100_000_000L
        d.onNmeaUtc(utc0 + 900, elapsedNs)
        val est = d.now(elapsedNs = 1_500_000_000L)!!
        assertEquals("anchor stays the gnss clock sample", GpsDiscipliner.Source.GNSS_CLOCK, est.source)
        assertEquals(utc0 + 1_500, est.utcMs)
        // 6 s after the newest clock sample NMEA is admitted and anchors.
        elapsedNs = 8_000_000_000L
        assertNotNull(d.onNmeaUtc(utc0 + 8_000, elapsedNs))
        assertEquals(GpsDiscipliner.Source.NMEA, d.now(elapsedNs = elapsedNs)!!.source)
    }

    @Test
    fun `glitched sample outside the outlier gate is rejected`() {
        val d = GpsDiscipliner()
        var elapsedNs = 0L
        d.setElapsedSource { elapsedNs }
        val utc0 = 1_000_000_000_000L
        // Three good 1 Hz samples establish the baseline the gate needs.
        d.onNmeaUtc(utc0, 0L)
        d.onNmeaUtc(utc0 + 1_000, 1_000_000_000L)
        d.onNmeaUtc(utc0 + 2_000, 2_000_000_000L)
        // A +300 ms step inside the window: rejected, anchor unchanged.
        elapsedNs = 3_000_000_000L
        d.onNmeaUtc(utc0 + 3_300, elapsedNs)
        val est = d.now(elapsedNs = elapsedNs)!!
        assertEquals(utc0 + 3_000, est.utcMs)
        assertEquals(3, est.samples)
        // Five consecutive rejections suspend the gate (lockout breaker):
        // if the anchor itself were bad, the stream must recover.
        repeat(5) { i ->
            elapsedNs = (4_000_000_000L + i * 1_000_000_000L)
            d.onNmeaUtc(utc0 + 4_300 + i * 1_000, elapsedNs)
        }
        elapsedNs = 9_000_000_000L
        // Gate suspended: the next sample is admitted even though it
        // steps +300 ms from the (stale) anchor.
        d.onNmeaUtc(utc0 + 9_300, elapsedNs)
        assertEquals(utc0 + 9_300, d.now(elapsedNs = elapsedNs)!!.utcMs)
    }

    @Test
    fun `a bad first sample does not lock out the discipliner`() {
        // Regression: with the gate active from sample one, a glitched
        // opening sample anchored the window and every later good sample
        // was rejected as out of family — no estimate, LI=3, forever
        // (observed on-device).
        val d = GpsDiscipliner()
        var elapsedNs = 0L
        d.setElapsedSource { elapsedNs }
        val utc0 = 1_000_000_000_000L
        d.onNmeaUtc(utc0 + 300, 0L) // bad first sample: 300 ms ahead
        for (i in 1..5) {
            elapsedNs = i * 1_000_000_000L
            d.onNmeaUtc(utc0 + i * 1_000, elapsedNs)
        }
        val est = d.now(elapsedNs = 5_000_000_000L)!!
        assertEquals(utc0 + 5_000, est.utcMs)
    }

    // --- NMEA RMC parser ---

    @Test
    fun `rmc parse gives full utc ms`() {
        // $GPRMC,123519,A,…,230394,… — 1994-03-23 12:35:19 UTC.
        val ms = GpsDiscipliner.parseRmcUtcMs("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A")
        assertEquals(utcMillis(1994, 3, 23, 12, 35, 19, 0), ms)
    }

    @Test
    fun `rmc parse keeps fractional seconds`() {
        val ms = GpsDiscipliner.parseRmcUtcMs("\$GPRMC,123519.500,A,4807.038,N,01131.000,E,,,230394,,*XX")
        assertEquals(utcMillis(1994, 3, 23, 12, 35, 19, 500), ms)
    }

    @Test
    fun `rmc parse rolls 2-digit years across the century`() {
        // 251259 010119 → 2019-01-19 01:01:09.
        val ms = GpsDiscipliner.parseRmcUtcMs("\$GPRMC,010109,A,0000.000,N,00000.000,E,,,190119,,*XX")
        assertEquals(utcMillis(2019, 1, 19, 1, 1, 9, 0), ms)
    }

    @Test
    fun `rmc void fix and junk sentences parse to zero`() {
        assertEquals(0L, GpsDiscipliner.parseRmcUtcMs("\$GPRMC,,,,,,V,,,,,,*XX"))
        assertEquals(0L, GpsDiscipliner.parseRmcUtcMs("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
        assertEquals(0L, GpsDiscipliner.parseRmcUtcMs("garbage"))
        assertEquals(0L, GpsDiscipliner.parseRmcUtcMs("\$GPRMC,2599xx,A,1,2,3,4,5,6,230394*XX"))
    }
}