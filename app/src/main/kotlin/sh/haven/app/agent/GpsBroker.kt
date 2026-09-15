package sh.haven.app.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The GPS broker: one GNSS engine shared by every consumer, on the
 * host-broker pattern (VISION §"The host as a privileged peer", bridges.md
 * GPS row). A session is refcounted so `get_location_precise` (one-shot),
 * `start_gps_log` (continuous), and `start_ntp_service` (time discipline)
 * share the same callbacks — and so the engine shuts down when the last
 * consumer leaves, which is the battery story.
 *
 * A session always registers GPS fixes, GnssStatus (satellite detail), and
 * NMEA (HDOP + the discipliner's fallback source). GnssMeasurements
 * registers when the hardware supports it — the precise time source for
 * NTP and the `raw` logging mode.
 *
 * An object, not a Hilt singleton: [McpTools] is constructed manually (and
 * many times in tests), so the broker is a process-wide singleton
 * initialised lazily with the first real context. The Android-observable
 * side effect — the foreground service Android requires for background
 * location — lives in [GpsLogService]; the broker is a state owner.
 *
 * JSONL log lines (gps-logs/<id>.jsonl under the app's external files dir,
 * so the agent and the guest share can both read them):
 * ```
 * {"kind":"fix","t":<utcMs>,"lat":..,"lon":..,"acc":..,"alt":..,"vAcc":..,
 *  "spd":..,"brg":..,"satsFix":n,"satsView":n,"cn0Mean":..,"hdop":..}
 * {"kind":"nmea","t":<utcMs>,"sentence":"$GPGGA,…"}   (opt-in)
 * {"kind":"raw","t":<utcMs>,"clock":{…},"measurements":[…]} (opt-in)
 * ```
 */
internal object GpsBroker {

    private const val TAG = "HavenGps"

    enum class Consumer { PRECISE, LOG, NTP, GUEST }

    private var appContext: Context? = null
    private var lm: LocationManager? = null

    /** GPS callbacks live on this thread — never on main. */
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val lock = Any()

    // --- session state (guard: lock) ---
    private val consumers = ConcurrentHashMap<Consumer, Int>()
    private var fixListener: LocationListener? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var measurementsCallback: GnssMeasurementsEvent.Callback? = null
    private var activeIntervalMs: Int = 0

    /** Ring of recent fixes (newest last), for best-of-window / averaging. */
    private val fixRing = ArrayDeque<Location>()
    /** Satellite snapshot from GnssStatus; null until the first report. */
    @Volatile private var sats: SatsSnapshot? = null
    /** HDOP from the newest GGA sentence. */
    @Volatile var hdop: Double? = null
        private set

    /** The discipliner fed by GnssMeasurements / NMEA RMC. */
    val discipliner = GpsDiscipliner()

    data class SatsSnapshot(
        val inView: Int,
        val used: Int,
        val byConstellation: Map<String, Int>,
        val cn0UsedMeanDbHz: Double,
        val cn0TopDbHz: List<Double>,
    )

    // --- logging state (guard: lock) ---
    private var logId: String? = null
    private var logFile: File? = null
    private var logWriter: BufferedWriter? = null
    private var logExecutor: java.util.concurrent.ExecutorService? = null
    private var logFixes = 0L
    private var logNmea = false
    private var logRaw = false
    private var logCapBytes = DEFAULT_LOG_CAP_BYTES
    private var logStartedMs = 0L
    private var logStoppedReason: String? = null

    // --- ntp state ---
    private var ntpServer: SntpServer? = null

    // --- guest bridge state ---
    private var guestServer: GpsGuestServer? = null

    // --- thread-safe state probes ---

    val isLogging: Boolean get() = logWriter != null
    val isNtpRunning: Boolean get() = ntpServer != null
    val isGuestBridgeRunning: Boolean get() = guestServer != null
    val isSessionActive: Boolean get() = consumers.isNotEmpty()
    val loggingId: String? get() = logId

    /** JSON status for `get_gps_status` (and the bridges rows' detail). */
    fun statusJson(): JSONObject = synchronized(lock) {
        val out = JSONObject()
        out.put("sessionActive", isSessionActive)
        out.put(
            "consumers",
            JSONArray(Consumer.values().filter { consumers.containsKey(it) }.map { it.name.lowercase() }),
        )
        if (isSessionActive) out.put("intervalMs", activeIntervalMs)
        val fix = latestFixForStatus()
        if (fix != null) out.put("lastFix", fixJson(fix))
        sats?.let { s ->
            out.put("satellites", JSONObject().apply {
                put("inView", s.inView)
                put("used", s.used)
                put("byConstellation", JSONObject(s.byConstellation))
                put("cn0UsedMeanDbHz", round(s.cn0UsedMeanDbHz, 1))
                put("cn0TopDbHz", JSONArray(s.cn0TopDbHz.map { round(it, 1) }))
            })
        }
        hdop?.let { out.put("hdop", round(it, 2)) }
        discipliner.now()?.let { est ->
            out.put("time", JSONObject().apply {
                put("source", if (est.source == GpsDiscipliner.Source.GNSS_CLOCK) "gnss_clock" else "nmea")
                put("utcMs", est.utcMs)
                put("jitterMs", round(est.jitterMs, 3))
                put("uncertaintyMs", round(est.uncertaintyMs, 3))
                put("holdoverMs", est.holdoverMs)
                put("samples", est.samples)
            })
        }
        logId?.let { id ->
            out.put("logging", JSONObject().apply {
                put("id", id)
                put("file", logFile?.absolutePath)
                put("fixes", logFixes)
                put("startedMs", logStartedMs)
                put("intervalMs", activeIntervalMs)
                put("nmea", logNmea)
                put("raw", logRaw)
                logStoppedReason?.let { put("stoppedReason", it) }
            })
        }
        ntpServer?.let { out.put("ntp", ntpJson(it)) }
        guestServer?.let { out.put("guestBridge", guestBridgeJson(it)) }
        return out
    }

    /** The freshest fix, or null when the ring is empty. */
    private fun latestFixForStatus(): Location? = synchronized(lock) { fixRing.lastOrNull() }

    // --- session lifecycle ---

    /**
     * Acquire the shared engine for [consumer] at [intervalMs]. Idempotent
     * per consumer; the engine's update rate is the fastest any active
     * consumer asked for. Caller must have verified the location permission.
     */
    fun acquire(context: Context, consumer: Consumer, intervalMs: Int) = synchronized(lock) {
        appContext = context.applicationContext
        ensureEngine(appContext!!)
        consumers[consumer] = intervalMs
        registerLocked()
    }

    /** Release a consumer; the engine stops when the last one leaves. */
    fun release(consumer: Consumer) = synchronized(lock) {
        consumers.remove(consumer)
        if (consumers.isEmpty()) unregisterLocked() else registerLocked()
    }

    private fun ensureEngine(context: Context) {
        if (lm == null) {
            lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val t = HandlerThread("haven-gps").apply { start() }
            handlerThread = t
            handler = Handler(t.looper)
        }
    }

    @SuppressLint("MissingPermission") // every caller checks FINE first
    private fun registerLocked() {
        val lm = this.lm ?: return
        val interval = (consumers.values.minOrNull() ?: NTP_SESSION_INTERVAL_MS).coerceIn(100, 60_000)
        activeIntervalMs = interval
        // Remove-and-re-request: a consumer joining at a faster rate must
        // take effect, and LocationManager has no per-listener rate change.
        fixListener?.let { runCatching { lm.removeUpdates(it) } }
        val fl = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onFix(location)
            }
            override fun onProviderDisabled(provider: String) {
                if (isLogging) stopLogging("gps provider disabled")
            }
        }
        fixListener = fl
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, interval.toLong(), 0f, fl, handler!!.looper,
            )
        }.onFailure { Log.w(TAG, "requestLocationUpdates failed: ${it.message}") }

        if (gnssCallback == null) {
            gnssCallback = object : GnssStatus.Callback() {
                override fun onStarted() = Unit
                override fun onStopped() = Unit
                override fun onFirstFix(ttffMillis: Int) = Unit
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    sats = snapshotSats(status)
                }
            }
            runCatching { lm.registerGnssStatusCallback(gnssCallback!!, handler!!) }
                .onFailure { Log.w(TAG, "registerGnssStatusCallback failed: ${it.message}") }
        }
        if (nmeaListener == null) {
            val nl = OnNmeaMessageListener { nmea: String, _: Long -> onNmea(nmea) }
            nmeaListener = nl
            runCatching { lm.addNmeaListener(nl, handler!!) }
                .onFailure { Log.w(TAG, "addOnNmeaListener failed: ${it.message}") }
        }
        if (measurementsCallback == null) {
            val cb = object : GnssMeasurementsEvent.Callback() {
                override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                    onMeasurements(event)
                }
                override fun onStatusChanged(status: Int) = Unit
            }
            measurementsCallback = cb
            // Some chipsets don't support raw measurements; the discipliner
            // then falls back to NMEA RMC and the `raw` log mode stays empty.
            runCatching { lm.registerGnssMeasurementsCallback(cb, handler!!) }
                .onFailure { Log.w(TAG, "registerGnssMeasurementsCallback failed: ${it.message}") }
        }
    }

    private fun unregisterLocked() {
        val lm = this.lm ?: return
        fixListener?.let { runCatching { lm.removeUpdates(it) } }
        fixListener = null
        gnssCallback?.let { cb -> runCatching { lm.unregisterGnssStatusCallback(cb) } }
        gnssCallback = null
        nmeaListener?.let { nl -> runCatching { lm.removeNmeaListener(nl) } }
        nmeaListener = null
        measurementsCallback?.let { cb -> runCatching { lm.unregisterGnssMeasurementsCallback(cb) } }
        measurementsCallback = null
        activeIntervalMs = 0
    }

    // --- callbacks (on the gps HandlerThread) ---

    private fun onFix(location: Location) = synchronized(lock) {
        fixRing.addLast(location)
        while (fixRing.size > FIX_RING_SIZE) fixRing.removeFirst()
        if (isLogging) {
            writeLogLine(fixJson(location, includeKind = true))
            logFixes++
            if (logFile != null && logFile!!.length() > logCapBytes) {
                stopLoggingLocked("size cap $logCapBytes reached")
                return@synchronized
            }
        }
    }

    private fun snapshotSats(status: GnssStatus): SatsSnapshot {
        val byConst = HashMap<String, Int>()
        var used = 0
        var cn0UsedSum = 0.0
        val cn0All = ArrayList<Double>(status.satelliteCount)
        for (i in 0 until status.satelliteCount) {
            val c = constellationName(status.getConstellationType(i))
            byConst[c] = (byConst[c] ?: 0) + 1
            cn0All.add(status.getCn0DbHz(i).toDouble())
            if (status.usedInFix(i)) {
                used++
                cn0UsedSum += status.getCn0DbHz(i).toDouble()
            }
        }
        return SatsSnapshot(
            inView = status.satelliteCount,
            used = used,
            byConstellation = byConst,
            cn0UsedMeanDbHz = if (used > 0) cn0UsedSum / used else 0.0,
            cn0TopDbHz = cn0All.sortedDescending().take(8),
        )
    }

    private fun onNmea(sentence: String) {
        // HDOP from GGA (field 8). GGA arrives at the fix rate, not per
        // satellite, so the split cost is bounded.
        if (sentence.contains("GGA")) {
            val f = sentence.split(",")
            if (f.size > 8) {
                val h = f[8].toDoubleOrNull()
                if (h != null && h > 0) hdop = h
            }
        }
        val logging = isLogging
        if (logging && logNmea) {
            writeLogLine(JSONObject().apply {
                put("kind", "nmea")
                put("t", System.currentTimeMillis())
                put("sentence", sentence.trim())
            })
        }
        // Discipliner fallback: RMC's UTC (hhmmss.sss + ddmmyy → full ms).
        if (sentence.contains("RMC")) {
            val utcMs = GpsDiscipliner.parseRmcUtcMs(sentence)
            if (utcMs > 0) discipliner.onNmeaUtc(utcMs, SystemClock.elapsedRealtimeNanos())
        }
        // Guest bridge: every sentence streams to connected readers. publish
        // is non-blocking (drop-on-full), safe on this GNSS HandlerThread.
        guestServer?.publish(sentence)
    }

    private fun onMeasurements(event: GnssMeasurementsEvent) {
        val clock = event.clock
        if (isLogging && logRaw) {
            writeLogLine(JSONObject().apply {
                put("kind", "raw")
                put("t", System.currentTimeMillis())
                put("clock", JSONObject().apply {
                    put("timeNs", clock.timeNanos)
                    runCatching { if (clock.hasFullBiasNanos()) put("fullBiasNs", clock.fullBiasNanos) }
                    runCatching { if (clock.hasBiasNanos()) put("biasNs", clock.biasNanos) }
                    runCatching { if (clock.hasDriftNanosPerSecond()) put("driftNsPerSec", clock.driftNanosPerSecond) }
                    runCatching { if (clock.hasBiasUncertaintyNanos()) put("biasUncertaintyNs", clock.biasUncertaintyNanos) }
                })
                put("measurements", JSONArray().apply {
                    for (m in event.measurements) {
                        put(JSONObject().apply {
                            put("svid", m.svid)
                            put("constellation", constellationName(m.constellationType))
                            put("cn0DbHz", round(m.cn0DbHz, 1))
                            put("pseudorangeRateMps", round(m.pseudorangeRateMetersPerSecond, 2))
                            put("receivedSvTimeNs", m.receivedSvTimeNanos)
                        })
                    }
                })
            })
        }
        if (clock.hasFullBiasNanos()) {
            discipliner.onGnssClock(
                timeNs = clock.timeNanos,
                fullBiasNs = clock.fullBiasNanos,
                biasNs = if (clock.hasBiasNanos()) clock.biasNanos else null,
                driftNsPerSec = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else null,
                clockUncertaintyNs = if (clock.hasBiasUncertaintyNanos()) clock.biasUncertaintyNanos else null,
                // The clock snapshot's own elapsed timestamp, not callback
                // time: duty-cycled chipsets deliver measurement events
                // hundreds of ms late (measured ~0.5 s on-device), and
                // pairing the GPS time with delivery time bakes that latency
                // into every sample. getElapsedRealtimeNanos exists for this.
                receivedElapsedNs =
                    if (clock.hasElapsedRealtimeNanos()) clock.elapsedRealtimeNanos
                    else SystemClock.elapsedRealtimeNanos(),
            )
        }
    }

    // --- get_location_precise ---

    /**
     * One precise-fix call: ensure a session, wait up to [timeoutMs] for a
     * fix better than [earlyAccM], then return the best seen — or, with
     * [averageWindowMs] > 0, the position average of the window's fixes.
     * The permission is the tool's job; this only drives the engine.
     */
    fun preciseFix(context: Context, timeoutMs: Int, averageWindowMs: Int): JSONObject {
        val startWall = System.currentTimeMillis()
        val deadline = startWall + timeoutMs
        acquire(context, Consumer.PRECISE, PRECISE_INTERVAL_MS)
        try {
            var best: Location? = null
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(PRECISE_POLL_MS)
                val cur = latestFixForStatus()
                if (cur != null && cur.time >= startWall - PRECISE_FRESH_SLACK_MS) {
                    best = bestOf(cur, best)
                    // Good-enough gate: once a fix lands well inside GPS's
                    // usual accuracy and at least minWait has passed, don't
                    // linger for marginal improvement. Best-single-fix mode
                    // only — averaging mode needs the full window of fixes,
                    // so it runs to the deadline (the gate would otherwise
                    // fire on the first sub-15 m fix and average one point).
                    if (averageWindowMs == 0 && best != null && best.accuracy <= PRECISE_EARLY_ACC &&
                        System.currentTimeMillis() - startWall >= PRECISE_MIN_WAIT_MS
                    ) break
                }
            }
            val fixes = synchronized(lock) { fixRing.toList() }
            val chosen = if (averageWindowMs > 0 && fixes.isNotEmpty()) averagedFix(fixes, averageWindowMs.toLong())
            else best ?: latestFixForStatus()
            return JSONObject().apply {
                if (chosen == null) {
                    put("found", false)
                    put(
                        "note",
                        "No GPS fix within ${timeoutMs}ms — outdoors/sky view helps; " +
                            "get_gps_status.satellites shows what the engine sees.",
                    )
                } else {
                    merge(fixJson(chosen))
                    put("found", true)
                }
                put("samplesConsidered", fixes.size)
                if (averageWindowMs > 0 && chosen != null) put("averagedWindowMs", averageWindowMs)
                put("timeoutMs", timeoutMs)
            }
        } finally {
            release(Consumer.PRECISE)
        }
    }

    private fun bestOf(a: Location?, b: Location?): Location? = when {
        a == null -> b
        b == null -> a
        a.accuracy <= b.accuracy -> a
        else -> b
    }

    /**
     * Position averaging over fixes within [windowMs]: mean lat/lon, with
     * the reported accuracy the mean-fix accuracy / √n, floored at 3 m —
     * averaging random noise can't beat systematic error, and claiming it
     * could would be dishonest.
     */
    private fun averagedFix(fixes: List<Location>, windowMs: Long): Location {
        val newest = fixes.maxOf { it.time }
        val pool = fixes.filter { newest - it.time <= windowMs }
        val mean = Location("gps-average")
        mean.latitude = pool.map { it.latitude }.average()
        mean.longitude = pool.map { it.longitude }.average()
        mean.time = newest
        val n = pool.size
        val meanAcc = pool.map { it.accuracy.toDouble() }.average()
        mean.accuracy = maxOf(meanAcc / Math.sqrt(n.toDouble()), 3.0).toFloat()
        if (pool.all { it.hasAltitude() }) mean.altitude = pool.map { it.altitude }.average()
        return mean
    }

    // --- logging ---

    /** Copy every key of [o] into this object (org.json has no putAll). */
    private fun JSONObject.merge(o: JSONObject): JSONObject {
        for (k in o.keys()) put(k, o.get(k))
        return this
    }

    /** Log id from the wall clock: yyyyMMdd-HHmmss, unique at fix granularity. */
    private fun newLogId(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())

    fun startLogging(
        context: Context,
        intervalMs: Int,
        includeNmea: Boolean,
        includeRaw: Boolean,
        capBytes: Long?,
    ): JSONObject = synchronized(lock) {
        if (isLogging) {
            return JSONObject().put("alreadyRunning", true).put("id", logId)
        }
        ensureEngine(context)
        logId = newLogId()
        val dir = File(context.getExternalFilesDir(null), "gps-logs").apply { mkdirs() }
        val file = File(dir, "$logId.jsonl")
        logWriter = file.bufferedWriter()
        logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "haven-gps-log").apply { isDaemon = true }
        }
        logFixes = 0
        logNmea = includeNmea
        logRaw = includeRaw
        logCapBytes = capBytes ?: DEFAULT_LOG_CAP_BYTES
        logStartedMs = System.currentTimeMillis()
        logStoppedReason = null
        logFile = file
        acquire(context, Consumer.LOG, intervalMs)
        // The FGS keeps updates flowing with Haven backgrounded. On
        // Android 12+ a background start would throw — every caller sits
        // behind a consent sheet, so the app is foreground here.
        runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, GpsLogService::class.java),
            )
        }.onFailure {
            Log.w(TAG, "FGS start failed (logging runs only while Haven is foreground): ${it.message}")
        }
        JSONObject().apply {
            put("started", true)
            put("id", logId)
            put("file", file.absolutePath)
            put("intervalMs", intervalMs)
            put("nmea", includeNmea)
            put("raw", includeRaw)
        }
    }

    fun stopLogging(reason: String? = null): JSONObject = synchronized(lock) {
        stopLoggingLocked(reason)
    }

    private fun stopLoggingLocked(reason: String?): JSONObject {
        val id = logId
        if (id == null) return JSONObject().put("stopped", false).put("reason", "not logging")
        logStoppedReason = reason ?: "stopped"
        val w = logWriter
        logWriter = null
        if (w != null) {
            runCatching { w.flush() }
            runCatching { w.close() }
        }
        logExecutor?.shutdown()
        logExecutor = null
        release(Consumer.LOG)
        appContext?.let { ctx ->
            runCatching { ctx.stopService(Intent(ctx, GpsLogService::class.java)) }
        }
        val fixes = logFixes
        val file = logFile
        logId = null
        logFile = null
        return JSONObject().apply {
            put("stopped", true)
            put("id", id)
            put("fixes", fixes)
            put("file", file?.absolutePath)
            logStoppedReason?.let { put("stopReason", it) }
        }
    }

    /**
     * Append one JSONL record on the single log-writer thread, so callback
     * threads (fix/NMEA/measurements) never block on disk. Each line flushes
     * so the size cap and tail reads see current data; if [stopLoggingLocked]
     * closes the writer underneath an enqueued write the failure is dropped.
     */
    private fun writeLogLine(line: JSONObject) {
        val w = logWriter ?: return
        logExecutor?.execute {
            runCatching {
                w.write(line.toString())
                w.write("\n")
                w.flush()
            }.onFailure { Log.w(TAG, "log write failed: ${it.message}") }
        }
    }

    fun listLogs(context: Context): JSONArray {
        val dir = File(context.getExternalFilesDir(null), "gps-logs")
        val arr = JSONArray()
        val active = logId
        for (f in dir.listFiles()?.sortedByDescending { it.name } ?: emptyList()) {
            if (!f.name.endsWith(".jsonl")) continue
            val records = runCatching { f.useLines { lines -> lines.count() } }.getOrDefault(0)
            arr.put(JSONObject().apply {
                put("id", f.name.removeSuffix(".jsonl"))
                put("file", f.absolutePath)
                put("bytes", f.length())
                put("records", records)
                put("modifiedMs", f.lastModified())
                put("active", f.name.removeSuffix(".jsonl") == active)
            })
        }
        return arr
    }

    /**
     * Bounded tail read: the last [lines] records of [id]'s log, read back
     * from the end so a huge log never loads whole.
     */
    fun readLogTail(context: Context, id: String, lines: Int): JSONObject {
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' }
        val dir = File(context.getExternalFilesDir(null), "gps-logs")
        val f = File(dir, "$safeId.jsonl")
        if (!f.exists()) throw IllegalArgumentException("no log: $id")
        val want = lines.coerceIn(1, 500)
        val raf = java.io.RandomAccessFile(f, "r")
        try {
            val start = maxOf(0L, raf.length() - TAIL_WINDOW_BYTES)
            raf.seek(start)
            if (start > 0) raf.readLine() // drop the partial line
            val rest = ByteArray((raf.length() - raf.filePointer).toInt())
            raf.readFully(rest)
            val tail = String(rest).lines().filter { it.isNotBlank() }.takeLast(want)
            return JSONObject().apply {
                put("id", safeId)
                put("file", f.absolutePath)
                put("records", JSONArray().apply { for (l in tail) put(JSONObject(l)) })
                put("returned", tail.size)
            }
        } finally {
            runCatching { raf.close() }
        }
    }

    // --- NTP ---

    fun startNtp(context: Context, port: Int, bindLan: Boolean): JSONObject = synchronized(lock) {
        ntpServer?.let { existing ->
            return JSONObject().put("alreadyRunning", true).merge(ntpJson(existing))
        }
        ensureEngine(context)
        val server = SntpServer(
            port = port,
            bindLan = bindLan,
            timeSource = { discipliner.now() },
            referenceTimeMs = { discipliner.referenceUtcMs() },
        )
        server.start()
        // NTP keeps a session running so the discipliner is fed; until the
        // first samples land the server answers LI=3 (unsynchronised).
        acquire(context, Consumer.NTP, NTP_SESSION_INTERVAL_MS)
        ntpServer = server
        JSONObject().put("started", true).merge(ntpJson(server))
    }

    fun stopNtp(): JSONObject = synchronized(lock) {
        val s = ntpServer
        if (s == null) return JSONObject().put("stopped", false).put("reason", "not running")
        s.stop()
        ntpServer = null
        release(Consumer.NTP)
        JSONObject().put("stopped", true).put("requests", s.requestsTotal.get())
    }

    private fun ntpJson(s: SntpServer): JSONObject = JSONObject().apply {
        put("port", s.port)
        put("lanBind", s.lanBindAddress ?: JSONObject.NULL)
        put("requests", s.requestsTotal.get())
        put("answers", s.answersTotal.get())
        s.lastClient?.let { put("lastClient", it) }
    }

    // --- guest device bridge ---

    fun startGuestBridge(context: Context): JSONObject = synchronized(lock) {
        guestServer?.let { existing ->
            return JSONObject().put("alreadyRunning", true).merge(guestBridgeJson(existing))
        }
        ensureEngine(context)
        val server = GpsGuestServer()
        server.start()
        // The bridge needs an NMEA session; without a fix in view the stream
        // is silent, which is what the reader sees (a GPS with no sky view).
        acquire(context, Consumer.GUEST, GpsGuestServer.GUEST_SESSION_INTERVAL_MS)
        guestServer = server
        JSONObject().put("attached", true).merge(guestBridgeJson(server))
    }

    fun stopGuestBridge(): JSONObject = synchronized(lock) {
        val s = guestServer
        if (s == null) return JSONObject().put("detached", false).put("reason", "not running")
        s.stop()
        guestServer = null
        release(Consumer.GUEST)
        JSONObject().put("detached", true).put("sentences", s.sentencesTotal.get())
    }

    private fun guestBridgeJson(s: GpsGuestServer): JSONObject = JSONObject().apply {
        put("socketName", GpsGuestServer.SOCKET_NAME)
        put("socketNamespace", "abstract")
        put("readers", s.clientCount)
        put("sentences", s.sentencesTotal.get())
        put("dropped", s.droppedTotal)
    }

    // --- foreground service bridge (called by GpsLogService) ---

    /** [GpsLogService.onStartCommand]: paint the FGS notification. */
    fun serviceAttached(service: GpsLogService) {
        service.startForegroundCompat()
    }

    /** [GpsLogService.onDestroy]. */
    fun serviceDetached() = Unit

    /** Notification counters for the service's refresh. */
    fun notificationText(): String {
        val parts = mutableListOf<String>()
        if (isLogging) parts += "${logFixes} fixes logged"
        ntpServer?.let { parts += "NTP :${it.port}" }
        guestServer?.let { parts += "GPS→guest (${it.clientCount} readers)" }
        discipliner.now()?.let { parts += "±${round(it.uncertaintyMs, 1)} ms GPS time" }
        return parts.joinToString("; ").ifEmpty { "starting…" }
    }

    private fun fixJson(location: Location, includeKind: Boolean = false): JSONObject = JSONObject().apply {
        if (includeKind) put("kind", "fix")
        put("t", location.time)
        put("lat", round(location.latitude, 7))
        put("lon", round(location.longitude, 7))
        if (location.hasAccuracy()) put("acc", round(location.accuracy.toDouble(), 1))
        if (location.hasAltitude()) put("alt", round(location.altitude, 2))
        if (location.hasVerticalAccuracy()) put("vAcc", round(location.verticalAccuracyMeters.toDouble(), 1))
        if (location.hasSpeed()) put("spd", round(location.speed.toDouble(), 2))
        if (location.hasBearing()) put("brg", round(location.bearing.toDouble(), 1))
        put("provider", location.provider)
        sats?.let {
            put("satsFix", it.used)
            put("satsView", it.inView)
            put("cn0Mean", round(it.cn0UsedMeanDbHz, 1))
        }
        hdop?.let { put("hdop", round(it, 2)) }
        discipliner.now()?.let { put("gpsUtcMs", it.utcMs) }
    }

    // --- constants (a standalone object can't take a companion) ---

    /** Default log size cap: 128 MiB. */
    const val DEFAULT_LOG_CAP_BYTES: Long = 128L * 1024 * 1024
    /** NTP session rate: 1 Hz keeps the discipliner fed cheaply. */
    const val NTP_SESSION_INTERVAL_MS = 1000
    /** Precise-fix session rate: 1 s. */
    const val PRECISE_INTERVAL_MS = 1000
    /** Precise-fix poll while waiting. */
    const val PRECISE_POLL_MS = 200L
    /** A fix from up to 2 s before the call still counts as fresh. */
    const val PRECISE_FRESH_SLACK_MS = 2_000L
    /** Accuracy (m) that ends the precise-fix wait early. */
    const val PRECISE_EARLY_ACC = 15f
    /** Minimum wait before the early return, so the engine can improve. */
    const val PRECISE_MIN_WAIT_MS = 2_000L
    /** read_log tail: read at most this much of the file's end. */
    const val TAIL_WINDOW_BYTES = 2L * 1024 * 1024
    private const val FIX_RING_SIZE = 64

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "gps"
        GnssStatus.CONSTELLATION_SBAS -> "sbas"
        GnssStatus.CONSTELLATION_GLONASS -> "glonass"
        GnssStatus.CONSTELLATION_QZSS -> "qzss"
        GnssStatus.CONSTELLATION_BEIDOU -> "beidou"
        GnssStatus.CONSTELLATION_GALILEO -> "galileo"
        GnssStatus.CONSTELLATION_IRNSS -> "navic"
        else -> "unknown"
    }
}