package sh.haven.app.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.mcp.McpError
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The phone's own senses, brokered to the agent (VISION §1a "Sensor / IMU /
 * camera brokers", bridges.md capability matrix GPS/sensors/camera→Agent
 * ⚠️ feasible-unbuilt rows): one-shot device state, motion/environment
 * sensors, a single location fix, and a single camera frame.
 *
 * All four are *point verbs*, not a streaming feed: each call captures one
 * sample and closes the resource, which keeps the consent story simple
 * ("read battery and memory" / "take one photo") and matches the broker
 * pattern — Haven mediates Android's permission model and re-exposes the
 * capability over MCP. Streaming/continuous versions stay out until a
 * consumer asks for them.
 *
 * Permission strategy for location/camera: the manifest declares the
 * permission; the tool checks [Context.checkSelfPermission] first. When it
 * is missing and Shizuku is running with Haven granted, the tool grants
 * itself via `pm grant` — the per-call consent sheet for THIS tool is the
 * human gate for that (pm grant itself is silent). Without Shizuku the tool
 * errors with the Settings path instead of failing obscurely later.
 *
 * Motion and environment sensors need no Android runtime permission, and
 * neither does device state.
 */
internal class SensesToolProvider(
    private val context: Context,
    /**
     * Grant a runtime permission to Haven via Shizuku `pm grant`. Returns
     * null on success, or a human-readable failure message. Wired from
     * [McpTools.runShizukuOrThrow] so the Shizuku-missing hint stays in one
     * place; injected as a lambda so tests can pass a stub.
     */
    private val shizukuGrant: (permission: String) -> String?,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "get_device_state" to ToolHandler(
            description = "One-shot snapshot of the phone's own state: battery (percent, charging, plugged source, temperature, voltage), thermal status, memory (total/available/low-memory), storage (internal data partition total/free, plus the user-visible external dir when mounted), active network (transports, validated, metered), and device identity (model, Android version, uptime). Read-only, no Android permission needed. The natural first call when diagnosing 'the phone is slow / hot / about to die' before digging into sessions or logs.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getDeviceState() },

        "read_sensors" to ToolHandler(
            description = "One-shot read of the phone's motion and environment sensors — accelerometer (m/s²), gyroscope (rad/s), magnetometer (µT), pressure (hPa), light (lux), ambient temperature (°C), relative humidity (%), proximity (cm). Registers a short sampling window (default 300 ms) and returns the latest value plus the sample count for each sensor present; sensors the device lacks are simply absent from the result. Needs no Android permission (normal-rate sampling, not high-rate). A single sample is NOT an orientation solution — no fusion/rotation vector is computed here; request sensors explicitly when you only need one. Gated once per session like read_logcat.",
            inputSchema = objectSchema {
                stringArray("sensors", "Optional filter: read only these sensors (names: accelerometer, gyroscope, magnetometer, pressure, light, ambient_temperature, relative_humidity, proximity). Omit to read every sensor the device has.")
                integer("windowMs", "Sampling window in milliseconds (50–2000, default 300). More window = more samples for a steadier 'latest' value, at the cost of the call's wall time.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val wanted = args.optJSONArray("sensors")
                    ?.let { a -> (0 until a.length()).mapNotNull { a.optString(it) } }
                    ?.joinToString(", ")
                    ?.takeIf { it.isNotBlank() }
                if (wanted == null) "Read the phone's motion/environment sensors once?"
                else "Read sensors once ($wanted)?"
            },
        ) { args -> readSensors(args) },

        "get_location" to ToolHandler(
            description = "Take one location fix: latitude, longitude, accuracy, altitude/speed/bearing when available, the providing fix source (gps/network), and the fix age. Tries a fresh fix (GPS preferred) for up to `timeoutMs`, and always reports the best last-known fix as `lastKnown` with its `ageMs` so a stale-but-present fix is usable context rather than a hard failure; `found` is false when neither exists. Requires location permission — if Haven doesn't hold it and Shizuku is running, the call grants it silently (this consent sheet is the gate); otherwise the error names the Settings path. Background location throttling still applies to fresh fixes when Haven is backgrounded — lastKnown usually still answers.",
            inputSchema = objectSchema {
                integer("timeoutMs", "How long to wait for a fresh GPS/network fix (1000–30000, default 8000). The call waits this long only when no fix arrives sooner.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Take one location fix from this phone?" },
        ) { args -> getLocation(args) },

        "capture_camera_frame" to ToolHandler(
            description = "Capture a single still frame from the phone's camera and return it inline as an image (like capture_haven_ui) — the agent's eyes for a QR code, a machine display, a wiring diagram, or 'what does the camera see right now'. Opens Camera2, grabs one JPEG, and closes the camera immediately: one frame per call, no stream. Choose `lensFacing` (back/front/external, default back); the frame is downscaled to `maxWidth` for the transport. Requires the CAMERA permission — if Haven doesn't hold it and Shizuku is running, the call grants it silently (this consent sheet is the gate); otherwise the error names the Settings path. Haven's foreground service satisfies Android's background camera restriction, so this works while the screen is off.",
            inputSchema = objectSchema {
                string("lensFacing", "Which camera: \"back\" (default), \"front\", or \"external\".")
                integer("maxWidth", "Downscale the returned image to at most this many pixels wide (160–4096, default 1024) — the same knob capture_haven_ui exposes.")
                integer("timeoutMs", "Max wait for the camera to open and deliver a frame (1000–15000, default 4000).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Capture one frame from the phone's ${args.optString("lensFacing", "back")} camera?" },
        ) { args -> captureCameraFrame(args) },
    )

    // --- get_device_state ---

    private fun getDeviceState(): JSONObject {
        val out = JSONObject()
        out.put("battery", batteryState(context))
        out.put("thermal", thermalState())
        out.put("memory", memoryState())
        out.put("storage", storageState())
        out.put("network", networkState())
        out.put("device", JSONObject().apply {
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("uptimeMs", SystemClock.elapsedRealtime())
        })
        return out
    }

    private fun batteryState(ctx: Context): JSONObject = JSONObject().apply {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            put("available", false)
            return this
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        put("available", true)
        put("percent", batteryPercent(level, scale))
        put("status", batteryStatusName(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)))
        put("plugged", batteryPluggedName(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)))
        put("health", batteryHealthName(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)))
        // Tenths of a degree C from the vendor; -1 when unset.
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths != Int.MIN_VALUE) put("temperatureC", tenths / 10.0)
        val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        if (mv != Int.MIN_VALUE) put("voltageMv", mv)
        // BatteryManager.EXTRA_CHARGE_COUNTER is hidden from the public
        // API surface this module compiles against, but the extra's value
        // is the documented "charge_counter" string (µAh, API 21+).
        val ua = intent.getIntExtra("charge_counter", Int.MIN_VALUE)
        if (ua != Int.MIN_VALUE) put("chargeCounterUah", ua)
    }

    private fun thermalState(): JSONObject = JSONObject().apply {
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val status = pm.currentThermalStatus
            put("status", thermalStatusName(status))
            put("throttling", status >= PowerManager.THERMAL_STATUS_MODERATE)
        } else {
            put("status", "unavailable")
            put("reason", "requires Android 10")
        }
    }

    private fun memoryState(): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return JSONObject().apply {
            put("totalBytes", info.totalMem)
            put("availableBytes", info.availMem)
            put("thresholdBytes", info.threshold)
            put("lowMemory", info.lowMemory)
        }
    }

    private fun storageState(): JSONObject = JSONObject().apply {
        val data = Environment.getDataDirectory()
        val stat = StatFs(data.path)
        put("dataPath", data.path)
        put("dataTotalBytes", stat.totalBytes)
        put("dataFreeBytes", stat.availableBytes)
        // The user-visible external storage (shared storage) when mounted —
        // this is what a file manager shows, distinct from the raw data
        // partition above.
        runCatching {
            val ext = context.getExternalFilesDir(null)
            if (ext != null) {
                val es = StatFs(ext.path)
                put("sharedTotalBytes", es.totalBytes)
                put("sharedFreeBytes", es.availableBytes)
            }
        }
    }

    private fun networkState(): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
            ?: return JSONObject().apply { put("connected", false) }
        val caps = cm.getNetworkCapabilities(network)
            ?: return JSONObject().apply { put("connected", false) }
        return JSONObject().apply {
            put("connected", true)
            put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            put("metered", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not())
            put("transports", JSONArray(networkTransportNames { caps.hasTransport(it) }))
            val bw = caps.linkDownstreamBandwidthKbps
            if (bw > 0) put("downstreamKbps", bw)
        }
    }

    // --- read_sensors ---

    private fun readSensors(args: JSONObject): JSONObject {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val windowMs = args.optInt("windowMs", 300).coerceIn(50, 2000)
        val wanted = args.optJSONArray("sensors")
            ?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).lowercase() } }
            ?.toSet()
            ?.ifEmpty { null }

        val available = SENSORS.mapNotNull { (name, type) ->
            sm.getDefaultSensor(type)?.let { name to it }
        }
        val selected = available.filter { (name, _) -> wanted == null || name in wanted }
        if (selected.isEmpty()) {
            throw McpError(
                -32603,
                "No matching sensors on this device. Available: " +
                    available.joinToString(", ") { it.first }.ifEmpty { "none" },
            )
        }

        // One-shot window on a private thread: register, collect the latest
        // sample per sensor, then unregister. CountDownLatch keeps this a
        // plain (suspend-friendly) blocking wait instead of a callback dance.
        val thread = HandlerThread("haven-mcp-sensors").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        val samples = HashMap<String, Sample>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val name = SENSORS.entries.firstOrNull { it.value == event.sensor.type }?.key ?: return
                samples[name] = Sample(
                    values = event.values.take(6).map { it.toDouble() },
                    count = (samples[name]?.count ?: 0) + 1,
                    timestampNs = event.timestamp,
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            for ((_, sensor) in selected) {
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
            handler.postDelayed({ done.countDown() }, windowMs.toLong())
            done.await(windowMs + 1000L, TimeUnit.MILLISECONDS)
            handler.removeCallbacksAndMessages(null)
        } finally {
            runCatching { sm.unregisterListener(listener) }
            thread.quitSafely()
        }

        val arr = JSONArray()
        for ((name, sample) in samples) {
            val values = JSONArray()
            for (v in sample.values) values.put(round(v, 4))
            arr.put(JSONObject().apply {
                put("sensor", name)
                put("unit", UNITS[name] ?: "")
                put("values", values)
                put("samples", sample.count)
            })
        }
        return JSONObject().apply {
            put("windowMs", windowMs)
            put("available", JSONArray(available.map { it.first }))
            put("readings", arr)
            put("count", arr.length())
        }
    }

    private data class Sample(val values: List<Double>, val count: Int, val timestampNs: Long)

    // --- get_location ---

    @SuppressLint("MissingPermission")
    private fun getLocation(args: JSONObject): JSONObject {
        val timeoutMs = args.optInt("timeoutMs", 8000).coerceIn(1000, 30000)
        if (!ensurePermissions(listOf(ACCESS_FINE, ACCESS_COARSE))) {
            throw McpError(-32603, LOCATION_PERMISSION_MESSAGE)
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        // Best last-known fix across providers, whatever happens with the
        // fresh one — a stale fix is context, not an error.
        var lastKnown: android.location.Location? = null
        for (provider in listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        )) {
            runCatching {
                val l = lm.getLastKnownLocation(provider)
                if (l != null && (lastKnown == null || l.time > lastKnown!!.time)) lastKnown = l
            }
        }

        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val fresh = if (providers.isEmpty()) null else requestFreshFix(lm, providers, timeoutMs)
        val chosen = fresh ?: lastKnown

        return JSONObject().apply {
            if (chosen == null) {
                put("found", false)
            } else {
                put("found", true)
                put("latitude", round(chosen.latitude, 6))
                put("longitude", round(chosen.longitude, 6))
                if (chosen.hasAccuracy()) put("accuracyM", round(chosen.accuracy.toDouble(), 1))
                if (chosen.hasAltitude()) put("altitudeM", round(chosen.altitude, 1))
                if (chosen.hasSpeed()) put("speedMs", round(chosen.speed.toDouble(), 2))
                if (chosen.hasBearing()) put("bearingDeg", round(chosen.bearing.toDouble(), 1))
                put("provider", chosen.provider)
                put("timestampMs", chosen.time)
                put("ageMs", System.currentTimeMillis() - chosen.time)
                put("fresh", fresh != null)
            }
            lastKnown?.let {
                put("lastKnown", JSONObject().apply {
                    put("latitude", round(it.latitude, 6))
                    put("longitude", round(it.longitude, 6))
                    put("ageMs", System.currentTimeMillis() - it.time)
                    put("provider", it.provider)
                })
            }
            if (fresh == null) {
                put("note", if (lastKnown == null)
                    "No fresh fix and no last-known location — GPS may be off or the device has never had a fix."
                else "No fresh fix within ${timeoutMs}ms; reporting the last-known fix above.")
            }
        }
    }

    /**
     * Wait up to [timeoutMs] for the first fix on any of [providers], GPS
     * preferred (it is listed first, and the first callback wins unless a
     * later one is meaningfully fresher — for a one-shot verb, first-wins
     * on the earliest callback is honest enough).
     */
    @SuppressLint("MissingPermission")
    private fun requestFreshFix(
        lm: android.location.LocationManager,
        providers: List<String>,
        timeoutMs: Int,
    ): android.location.Location? {
        val handler = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var fix: android.location.Location? = null
        val updates = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (fix == null || location.time > fix!!.time) {
                    fix = location
                }
                // Don't count down on the very first callback from a slow
                // provider if GPS might land shortly — but simplicity wins:
                // first fix closes the wait. A one-shot verb that returns
                // quickly beats one that lingers for a perfect fix.
                done.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        try {
            for (provider in providers) {
                runCatching {
                    lm.requestLocationUpdates(
                        provider, 1000L, 0f, updates, Looper.getMainLooper(),
                    )
                }
            }
            done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            runCatching { lm.removeUpdates(updates) }
        }
        return fix
    }

    // --- capture_camera_frame ---

    @SuppressLint("MissingPermission")
    private fun captureCameraFrame(args: JSONObject): ToolResult {
        val facingName = args.optString("lensFacing", "back").lowercase()
        val facing = when (facingName) {
            "back" -> CameraCharacteristics.LENS_FACING_BACK
            "front" -> CameraCharacteristics.LENS_FACING_FRONT
            "external" -> CameraCharacteristics.LENS_FACING_EXTERNAL
            else -> throw McpError(-32603, "lensFacing must be back, front, or external (got \"$facingName\")")
        }
        val maxWidth = args.optInt("maxWidth", 1024).coerceIn(160, 4096)
        val timeoutMs = args.optInt("timeoutMs", 4000).coerceIn(1000, 15000)
        val format = args.optString("format", "jpeg").lowercase().ifBlank { "jpeg" }
        if (format !in setOf("jpeg", "png")) {
            throw McpError(-32603, "format must be jpeg or png (got \"$format\")")
        }

        if (!ensurePermissions(listOf(CAMERA_PERMISSION))) {
            throw McpError(-32603, CAMERA_PERMISSION_MESSAGE)
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val startedAt = System.currentTimeMillis()
        val cameraId = pickCamera(cm, facing)
            ?: throw McpError(
                -32603,
                "No ${facingName}-facing camera on this device. Cameras: " + cameraSummary(cm),
            )
        val characteristics = cm.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
        if (sizes.isNullOrEmpty()) {
            throw McpError(
                -32603,
                "Camera $cameraId does not report JPEG output sizes (ImageReader path unimplemented for YUV-only cameras).",
            )
        }
        val size = sizes.minByOrNull { kotlin.math.abs(it.width - TARGET_CAPTURE_WIDTH) }
            ?: sizes[0]

        val thread = HandlerThread("haven-mcp-camera").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var jpeg: ByteArray? = null
        var failure: String? = null
        try {
            reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ r ->
                val img: Image? = try { r.acquireLatestImage() } catch (e: Exception) { null }
                if (img != null) {
                    try {
                        val plane = img.planes[0]
                        val buf = plane.buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        if (jpeg == null) jpeg = bytes
                    } finally {
                        runCatching { img.close() }
                    }
                    done.countDown()
                }
            }, handler)

            val openLatch = CountDownLatch(1)
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    openLatch.countDown()
                }
                override fun onDisconnected(d: CameraDevice) {
                    runCatching { d.close() }
                    openLatch.countDown()
                }
                override fun onError(d: CameraDevice, error: Int) {
                    failure = "camera open error $error (${cameraErrorName(error)})"
                    runCatching { d.close() }
                    openLatch.countDown()
                }
            }, handler)
            if (!openLatch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS) || device == null) {
                throw McpError(
                    -32603,
                    "Camera did not open within ${timeoutMs}ms" + (failure?.let { ": $it" } ?: ""),
                )
            }

            val sessionLatch = CountDownLatch(1)
            device!!.createCaptureSession(
                listOf(reader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        sessionLatch.countDown()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        failure = "capture session configuration failed"
                        sessionLatch.countDown()
                    }
                },
                handler,
            )
            if (!sessionLatch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS) || session == null) {
                throw McpError(
                    -32603,
                    "Capture session did not configure within ${timeoutMs}ms" + (failure?.let { ": $it" } ?: ""),
                )
            }

            val request = session!!.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader!!.surface)
                // Continuous AF when the lens supports it; a preview-template
                // single capture converges fast enough for a one-shot frame.
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                runCatching {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }
            session!!.setRepeatingRequest(request.build(), null, handler)

            if (!done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS) || jpeg == null) {
                throw McpError(
                    -32603,
                    "Camera delivered no frame within ${timeoutMs}ms" + (failure?.let { ": $it" } ?: ""),
                )
            }
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }

        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size)
            ?: throw McpError(-32603, "Captured ${jpeg!!.size} bytes but they did not decode as a JPEG.")
        val captureW = bmp.width
        val captureH = bmp.height
        val (base64, outW, outH) = withContextDownscale(bmp, maxWidth, format)
        return ToolResult.Image(
            base64 = base64,
            mimeType = if (format == "jpeg") "image/jpeg" else "image/png",
            structured = JSONObject().apply {
                put("cameraId", cameraId)
                put("lensFacing", facingName)
                put("captureWidth", captureW)
                put("captureHeight", captureH)
                put("imageWidth", outW)
                put("imageHeight", outH)
                put("format", format)
                put("maxWidth", maxWidth)
                put("captureMs", System.currentTimeMillis() - startedAt)
            },
        )
    }

    /**
     * The bitmap-encode half of [capture_camera_frame], split out of the
     * camera plumbing so the downscale choice (only when needed, recycle the
     * scratch) is testable and identical to capture_haven_ui's behaviour.
     */
    private fun withContextDownscale(bmp: Bitmap, maxWidth: Int, format: String): Triple<String, Int, Int> {
        val out = if (maxWidth in 1 until bmp.width) {
            val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
        } else {
            bmp
        }
        val baos = ByteArrayOutputStream()
        if (format == "jpeg") out.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        else out.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val result = Triple(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP), out.width, out.height)
        if (out !== bmp) out.recycle()
        return result
    }

    private fun pickCamera(cm: CameraManager, facing: Int): String? {
        val matches = mutableListOf<String>()
        val others = mutableListOf<Pair<String, Int>>()
        for (id in cm.cameraIdList) {
            val f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                ?: continue
            if (f == facing) matches.add(id) else others.add(id to f)
        }
        if (matches.isNotEmpty()) return matches.first()
        // Fall back to any camera rather than failing — the agent asked for
        // "a frame", and the result names what it actually used.
        return others.firstOrNull()?.first
    }

    private fun cameraSummary(cm: CameraManager): String =
        cm.cameraIdList.mapNotNull { id ->
            val f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            "${lensFacingName(f)}(id=$id)"
        }.joinToString(", ").ifEmpty { "none" }

    // --- permission plumbing ---

    /** True when at least one of [permissions] is already granted. */
    private fun hasAny(permissions: List<String>): Boolean =
        permissions.any {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Ensure at least one of [permissions] is granted, granting via Shizuku
     * when the manifest-declared permission isn't held yet. Returns the
     * final grant state. The consent sheet that authorised THIS call is the
     * human gate for the silent pm grant — that ordering is deliberate.
     */
    private fun ensurePermissions(permissions: List<String>): Boolean {
        if (hasAny(permissions)) return true
        for (permission in permissions) {
            val error = shizukuGrant("pm grant ${context.packageName} $permission")
            if (error != null) continue // fall through to the final check/message
        }
        return hasAny(permissions)
    }

    private companion object {
        const val ACCESS_FINE = "android.permission.ACCESS_FINE_LOCATION"
        const val ACCESS_COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        const val CAMERA_PERMISSION = "android.permission.CAMERA"
        const val TARGET_CAPTURE_WIDTH = 1600

        val LOCATION_PERMISSION_MESSAGE =
            "Location permission is not granted and could not be granted via Shizuku. " +
                "Either open Settings → Apps → Haven → Permissions → Location and allow it, " +
                "or install Shizuku (https://shizuku.rikka.app) and grant Haven permission so " +
                "the tool can grant itself on your behalf (this consent sheet is the gate)."

        val CAMERA_PERMISSION_MESSAGE =
            "Camera permission is not granted and could not be granted via Shizuku. " +
                "Either open Settings → Apps → Haven → Permissions → Camera and allow it, " +
                "or install Shizuku (https://shizuku.rikka.app) and grant Haven permission so " +
                "the tool can grant itself on your behalf (this consent sheet is the gate)."

        val SENSORS: Map<String, Int> = linkedMapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
            "pressure" to Sensor.TYPE_PRESSURE,
            "light" to Sensor.TYPE_LIGHT,
            "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
            "relative_humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "proximity" to Sensor.TYPE_PROXIMITY,
        )

        val UNITS: Map<String, String> = mapOf(
            "accelerometer" to "m/s2",
            "gyroscope" to "rad/s",
            "magnetometer" to "uT",
            "pressure" to "hPa",
            "light" to "lux",
            "ambient_temperature" to "C",
            "relative_humidity" to "%",
            "proximity" to "cm",
        )
    }
}

// --- Pure helpers, top-level so JVM unit tests can pin them without a
// Context (the vendor constants have no enum on the API surface).

internal fun batteryPercent(level: Int, scale: Int): Int? =
    if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null

internal fun batteryStatusName(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
    BatteryManager.BATTERY_STATUS_FULL -> "full"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
    BatteryManager.BATTERY_STATUS_UNKNOWN -> "unknown"
    else -> "unknown"
}

internal fun batteryPluggedName(plugged: Int): String = when (plugged) {
    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
    else -> "unplugged_or_unknown"
}

internal fun batteryHealthName(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
    else -> "unknown"
}

internal fun thermalStatusName(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "none"
    PowerManager.THERMAL_STATUS_LIGHT -> "light"
    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
    else -> "unknown"
}

/**
 * Transport-bit → name mapping, parameterised over the has-transport probe
 * (a lambda rather than a [NetworkCapabilities], whose add/get methods are
 * not on the public compile surface — this keeps the mapping JVM-testable).
 */
internal fun networkTransportNames(hasTransport: (Int) -> Boolean): List<String> {
    val names = mutableListOf<String>()
    if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names.add("wifi")
    if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names.add("cellular")
    if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names.add("ethernet")
    if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names.add("vpn")
    if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) names.add("bluetooth")
    if (hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) names.add("lowpan")
    return names.ifEmpty { listOf("unknown") }
}

internal fun lensFacingName(facing: Int): String = when (facing) {
    CameraCharacteristics.LENS_FACING_BACK -> "back"
    CameraCharacteristics.LENS_FACING_FRONT -> "front"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
    else -> "unknown"
}

internal fun cameraErrorName(error: Int): String = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera_in_use"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "max_cameras_in_use"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera_disabled"
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera_device_error"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera_service_error"
    else -> "unknown"
}

/** Round to [dp] decimal places, for stable JSON output. */
internal fun round(value: Double, dp: Int): Double =
    kotlin.math.round(value * Math.pow(10.0, dp.toDouble())) / Math.pow(10.0, dp.toDouble())