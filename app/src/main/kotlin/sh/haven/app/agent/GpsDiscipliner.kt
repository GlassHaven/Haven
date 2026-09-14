package sh.haven.app.agent

import android.location.GnssClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * GPS-disciplined time for the SNTP service (`start_ntp_service`): the
 * model of "what time is it" built exclusively from the phone's GNSS
 * samples, never from Android's own wall clock (which is network-synced
 * and can jump). Anchored in [SystemClock.elapsedRealtimeNanos] space so
 * wall-clock adjustments can't corrupt the estimate.
 *
 * Two sample sources, in descending quality order:
 *
 * 1. **GnssClock** (from `LocationManager.registerGnssMeasurementsCallback`)
 *    — the receiver's own clock, ns-class stated uncertainty. GPS time
 *    comes out as `timeNs - fullBiasNs - biasNs` (the documented
 *    reconstruction, [GnssClock]); UTC is GPS minus the leap second
 *    (18 s since 2017, or the clock's own `leapSecond` when it reports
 *    one).
 * 2. **NMEA `$xxRMC`** (fallback) — UTC to the ms from the sentence's
 *    own time+date fields; accuracy is delivery-latency-bound (ms-class
 *    jitter). Parsed by [parseRmcUtcMs], also pure and tested.
 *
 * Estimation is a least-squares line over a sliding sample window:
 * `utcNs = a + b * elapsedNs`, where the slope `b` is the local drift
 * (should sit within a few ppm of 1) and the residual spread is the
 * jitter. `nowUtcNs(elapsedNs)` evaluates that line, and grows the
 * uncertainty while the last sample ages (holdover), which is what the
 * SNTP answer advertises as rootDispersion — the honest number, not a
 * flattering constant.
 *
 * Pure class: no Android types beyond [GnssClock]'s numeric accessors,
 * which are fed in as plain longs by [GpsBroker] so JVM tests can drive
 * synthetic samples.
 */
internal class GpsDiscipliner {

    enum class Source { GNSS_CLOCK, NMEA }

    /** One disciplined sample: a GPS-UTC estimate paired with the elapsed clock. */
    data class Sample(
        val utcNs: Long,
        val elapsedNs: Long,
        val source: Source,
        /** Receiver's own stated uncertainty, ns (0 when unknown). */
        val receiverUncertaintyNs: Long,
    )

    /**
     * The current estimate, as [GpsBroker] and the SNTP server consume it.
     * `jitterMs` is the 1σ sample spread around the fitted line; the total
     * uncertainty adds receiver uncertainty, jitter, and holdover growth.
     */
    data class NowEstimate(
        val utcMs: Long,
        val utcSubMs: Double,
        val jitterMs: Double,
        val uncertaintyMs: Double,
        val holdoverMs: Long,
        val source: Source,
        val samples: Int,
    )

    private data class Fit(val interceptNs: Double, val slope: Double, val residualNs: Double, val n: Int)

    private val samples = ArrayDeque<Sample>()
    /** GPS-UTC ns of the newest sample — the reference timestamp the SNTP
     * answer reports, and the holdover anchor when no sample is current. */
    private var lastSample: Sample? = null
    private var nowSource: (() -> Long)? = null
    /** Consecutive outlier-gate rejections; reset on every acceptance. A
     * streak that hits [MAX_REJECTED_STREAK] suspends the gate — the
     * alternative (the anchor staying bad forever) is worse than briefly
     * trusting a noisy sample. */
    private var rejectedStreak = 0

    /** Leap seconds GPS→UTC. 18 since 2017-01-01; overridden by the receiver. */
    var leapSeconds: Int = 18
        private set

    /** Inject the elapsed-clock source (SystemClock.elapsedRealtimeNanos()) so
     * tests can drive virtual time. Defaults to the real clock. */
    fun setElapsedSource(elapsedNs: () -> Long) { nowSource = elapsedNs }

    /**
     * The sample clock: SystemClock.elapsedRealtimeNanos, the same base
     * callers stamp samples with. This must be one clock everywhere —
     * System.nanoTime() is a different base that excludes deep sleep (it
     * ran ~41.5 h behind elapsedRealtime on the verification device),
     * which silently shifted every estimate.
     */
    private fun elapsedNow(): Long =
        nowSource?.invoke() ?: android.os.SystemClock.elapsedRealtimeNanos()

    /**
     * Feed one GnssClock measurement. Returns the derived sample, or null
     * when the clock's fields aren't usable (flags missing, fullBias unset).
     */
    fun onGnssClock(
        timeNs: Long,
        fullBiasNs: Long?,
        biasNs: Double?,
        driftNsPerSec: Double?,
        clockUncertaintyNs: Double?,
        receivedElapsedNs: Long,
    ): Sample? {
        val fb = fullBiasNs ?: return null
        // GPS epoch time = hardware clock minus its stated biases. timeNs and
        // fullBiasNs are both signed ns; biasNs is the sub-ns residual.
        val gpsNs = timeNs - fb - (biasNs ?: 0.0).toLong()
        val utcNs = gpsNsToUtcNs(gpsNs)
        val unc = (clockUncertaintyNs ?: 0.0).toLong()
        return add(Sample(utcNs, receivedElapsedNs, Source.GNSS_CLOCK, unc))
    }

    /** Feed one NMEA RMC-derived UTC sample (ms since the Unix epoch). */
    fun onNmeaUtc(utcMs: Long, receivedElapsedNs: Long): Sample? {
        if (utcMs <= 0) return null
        return add(Sample(utcMs * 1_000_000L, receivedElapsedNs, Source.NMEA, 0))
    }

    /** Current estimate, or null until two or more usable samples exist. */
    fun now(elapsedNs: Long = elapsedNow()): NowEstimate? {
        val last = lastSample ?: return null
        val fit = fit(elapsedNs) ?: return null
        // Anchor to the newest sample, projected by the fitted slope. Not the
        // full-window intercept: sliding a 120 s window drops a high-leverage
        // sample and pivots the line by hundreds of ms (measured on-device:
        // ±300 ms wander, negative SNTP delays). Anchoring means consecutive
        // estimates only move by real elapsed time — the per-sample delivery
        // jitter is reported honestly in jitterMs/rootDispersion instead of
        // leaking into every answer.
        val utcNsNow = last.utcNs + fit.slope * (elapsedNs - last.elapsedNs)
        val holdoverMs = (elapsedNs - last.elapsedNs) / 1_000_000L
        // Uncertainty: receiver's own figure, plus the sample spread, plus
        // holdover growth (drift misestimate × holdover, bounded — a few
        // minutes of holdover stays ms-class, an hour does not).
        val holdoverUncNs = if (holdoverMs > 0) HOLD_OVER_UNC_NS_PER_MS * holdoverMs else 0.0
        val totalUncNs = last.receiverUncertaintyNs + fit.residualNs + holdoverUncNs
        // Round, don't truncate: the slope's float cancellation (~1e-10
        // relative) otherwise shaves a millisecond off whole-ms cases.
        val utcMs = Math.round(utcNsNow / 1_000_000.0)
        return NowEstimate(
            utcMs = utcMs,
            utcSubMs = (utcNsNow - utcMs * 1_000_000.0) / 1_000.0,
            jitterMs = fit.residualNs / 1_000_000.0,
            uncertaintyMs = totalUncNs / 1_000_000.0,
            holdoverMs = holdoverMs,
            source = last.source,
            samples = fit.n,
        )
    }

    /** UTC ms (Unix epoch) from GPS epoch ns: GPS + GPS↔Unix epoch offset − leap. */
    private fun gpsNsToUtcNs(gpsNs: Long): Long = gpsNsToUnixUtcNs(gpsNs, leapSeconds)

    /**
     * The newest raw sample's UTC ms — the SNTP reference timestamp (the
     * time of the last GPS observation), distinct from the *current*
     * estimated time. 0 when no sample has landed.
     */
    fun referenceUtcMs(): Long = lastSample?.let { it.utcNs / 1_000_000L } ?: 0L

    private fun add(s: Sample): Sample {
        // Monotonic guard: a sample older than the last one (callback
        // reordering, a stale queue flush) would poison the fit.
        val last = lastSample
        if (last != null && s.elapsedNs <= last.elapsedNs) return last
        // Outlier gate, inside the continuous window only: a glitched
        // GnssClock (measured on-device: occasional ±300 ms steps) becomes
        // the estimate's anchor and sawtooths every answer for a second.
        // Within-window offsets differ by oscillator drift only (ppm ⇒
        // µs), so a 200 ms step is a bad sample, not real drift. Across a
        // gap longer than the window the offset may legitimately have
        // moved (hours of holdover at real drift), so don't gate that.
        // The gate needs an established baseline: until the window holds
        // 3 samples it stays off, or one bad *first* sample anchors the
        // window and every later good sample reads as "out of family" —
        // a permanent lockout with no estimate at all (observed on-device
        // as an LI=3 SNTP answer for the whole session).
        if (last != null && samples.size >= 3 && rejectedStreak < MAX_REJECTED_STREAK &&
            s.elapsedNs - last.elapsedNs <= WINDOW_NS
        ) {
            val stepNs = (s.utcNs - s.elapsedNs) - (last.utcNs - last.elapsedNs)
            if (abs(stepNs) > MAX_OFFSET_STEP_NS) {
                rejectedStreak++
                return last
            }
        }
        rejectedStreak = 0
        // NMEA is a fallback source: its UTC is delivery-latency-bound
        // (measured ±250 ms arrival jitter on-device against a wall clock),
        // so while GnssClock samples are current an NMEA sample would both
        // inflate the fit's residual and — worse — displace the estimate's
        // anchor, sawtoothing every SNTP answer at the NMEA rate. Admit it
        // to the window only once GnssClock samples have stalled.
        if (s.source == Source.NMEA) {
            val newestClock = samples.lastOrNull { it.source == Source.GNSS_CLOCK }
            if (newestClock != null && s.elapsedNs - newestClock.elapsedNs <= CLOCK_FALLBACK_NS) {
                return last ?: s
            }
        }
        samples.addLast(s)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        // Trim by age too, so a stalled GPS engine doesn't fit a dead line.
        while (samples.size > 2 && s.elapsedNs - samples.first().elapsedNs > WINDOW_NS) {
            samples.removeFirst()
        }
        lastSample = s
        return s
    }

    /**
     * Least-squares `utcNs = a + b * elapsedNs` over the window. Returns
     * null with fewer than 2 samples. The slope is clamped into [0.999,
     * 1.001] — a wilder fit means garbage samples, and clamping (rather
     * than trusting) keeps holdover behaviour sane.
     */
    private fun fit(nowElapsedNs: Long): Fit? {
        val pts = samples.filter { nowElapsedNs - it.elapsedNs <= WINDOW_NS }
        if (pts.size < 2) return null
        var se = 0.0
        var su = 0.0
        var see = 0.0
        var sue = 0.0
        for (p in pts) {
            se += p.elapsedNs.toDouble()
            su += p.utcNs.toDouble()
            see += p.elapsedNs.toDouble() * p.elapsedNs
            sue += p.elapsedNs.toDouble() * p.utcNs
        }
        val n = pts.size.toDouble()
        val meanE = se / n
        val meanU = su / n
        val denom = see - se * meanE
        if (abs(denom) < 1e6) {
            // Degenerate: all samples at effectively one timestamp (e.g. a
            // measurement burst). Hold the mean, slope 1.
            return Fit(meanU, 1.0, residualOf(meanU, 1.0, pts), pts.size)
        }
        val slope = (sue - se * meanU) / denom
        val slopeClamped = slope.coerceIn(0.999, 1.001)
        val intercept = meanU - slopeClamped * meanE
        return Fit(interceptNs = intercept, slope = slopeClamped,
            residualNs = residualOf(intercept, slopeClamped, pts), n = pts.size)
    }

    /** 1σ residual of the points around the fitted line, ns. */
    private fun residualOf(intercept: Double, slope: Double, pts: List<Sample>): Double {
        if (pts.size < 2) return 0.0
        var sum = 0.0
        for (p in pts) {
            val r = p.utcNs - (intercept + slope * p.elapsedNs)
            sum += r * r
        }
        return sqrt(sum / (pts.size - 1))
    }

    companion object {
        /** Fit window: 120 s of samples. */
        const val WINDOW_NS: Long = 120_000_000_000L
        private const val MAX_SAMPLES = 256

        /** Max offset step between consecutive samples inside the window:
         * larger is a glitched sample, dropped. */
        const val MAX_OFFSET_STEP_NS: Long = 200_000_000L

        /** How long after the newest GnssClock sample an NMEA sample still
         * counts as redundant. Past it GnssClock has stopped, and NMEA
         * becomes the live source. */
        const val CLOCK_FALLBACK_NS: Long = 5_000_000_000L

        /** Consecutive outlier rejections that suspend the gate. */
        const val MAX_REJECTED_STREAK = 5

        /** Holdover uncertainty growth: 0.4 ms per second of holdover
         * (0.5 ppm drift misestimate × 2σ headroom) = 400 ns per ms.
         * Conservative by design. */
        const val HOLD_OVER_UNC_NS_PER_MS: Double = 400.0

        /** GPS epoch (1980-01-06) in Unix ns. */
        const val GPS_EPOCH_UNIX_NS: Long = 315_964_800L * 1_000_000_000L

        /** UTC ns (Unix epoch) from GPS time-of-week ns since the GPS epoch. */
        fun gpsNsToUnixUtcNs(gpsNs: Long, leapSeconds: Int = 18): Long =
            gpsNs + GPS_EPOCH_UNIX_NS - leapSeconds * 1_000_000_000L

        /**
         * Parse one NMEA `$xxRMC` sentence's UTC (hhmmss.sss) + date (ddmmyy)
         * into Unix ms. Returns 0 for void fix / unparseable — the caller
         * treats 0 as "no sample".
         */
        fun parseRmcUtcMs(sentence: String): Long {
            val f = sentence.trim().split(",")
            if (f.size < 10) return 0
            if (!f[0].startsWith("$") || !f[0].endsWith("RMC")) return 0
            val time = f[1]
            val date = f[9]
            if (time.length < 6 || date.length != 6) return 0
            val hh = time.substring(0, 2).toIntOrNull() ?: return 0
            val mm = time.substring(2, 4).toIntOrNull() ?: return 0
            val ss = time.substring(4, 6).toIntOrNull() ?: return 0
            val ms = if (time.contains('.')) {
                val frac = time.substringAfter('.').padEnd(3, '0').take(3)
                frac.toIntOrNull() ?: 0
            } else 0
            val yy = date.substring(4, 6).toIntOrNull() ?: return 0
            val mo = date.substring(2, 4).toIntOrNull() ?: return 0
            val dd = date.substring(0, 2).toIntOrNull() ?: return 0
            // 2-digit year window (RMC carries no century): 80–99 → 1980s,
            // 00–79 → 2000s. GPS's own epoch starts in 1980, so this is safe
            // until 2079.
            val year = if (yy >= 80) 1900 + yy else 2000 + yy
            if (mo !in 1..12 || dd !in 1..31) return 0
            return utcMillis(year, mo, dd, hh, mm, ss, ms)
        }
    }
}

/** UTC ms for a civil date-time (java.time-free so the class stays identical on JVM + ART). */
internal fun utcMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int): Long {
    // Days from civil (Howard Hinnant's algorithm), then ms.
    val yy = y.toLong()
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val mp = (mo + 9) % 12
    val doy = (153 * mp + 2) / 5 + (d - 1).toLong()
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097 + doe - 719468
    return days * 86_400_000L + h * 3_600_000L + mi * 60_000L + s * 1_000L + ms
}