package sh.haven.app.agent

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mcp.McpError

/**
 * GPS tools: precise fixes, continuous logging, the GPS-disciplined NTP
 * service, and the guest device bridge — the continuous layer over
 * [SensesToolProvider]'s one-shot `get_location`, per bridges.md's GPS row.
 * All the Android plumbing lives in [GpsBroker]; this file is the tool
 * surface: argument validation, the permission gate, and JSON shaping.
 *
 * Permission strategy is the same as the senses provider: the manifest
 * declares ACCESS_FINE_LOCATION, [ensurePermissions] grants via Shizuku
 * `pm grant` when missing, and the per-call consent sheet for the tool that
 * triggered the grant is the human gate.
 */
internal class GpsToolProvider(
    private val context: Context,
    /** Shizuku `pm grant` mapper from [McpTools.runShizukuOrThrow]. */
    private val shizukuGrant: (permission: String) -> String?,
    private val preferencesRepository: UserPreferencesRepository,
    /** Proot access for staging the guest-side helper on attach. */
    private val localSessionManager: LocalSessionManager,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "get_gps_status" to ToolHandler(
            description = "Full GNSS status: the current fix (lat/lon/accuracy/altitude/speed/bearing with the fix's age), satellite detail from GnssStatus (in view / used in fix, per-constellation counts, mean C/N0 of used satellites, top C/N0 values), HDOP from the GGA sentence, the GPS time discipline (source gnss_clock|nmea, jitter, uncertainty, holdover — the model the NTP service serves), and the state of gps logging and the NTP service. When no GPS session is running, satellites/discipline are absent and only the last-known fix appears — start one via get_location_precise, start_gps_log, or start_ntp_service to wake the engine. Read of device metadata and the current fix; gated once per session like read_sensors.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Read GPS status (current fix, satellites, logging, NTP)?" },
        ) { _ -> getStatus() },

        "get_location_precise" to ToolHandler(
            description = "One high-quality GPS-only fix: waits up to timeoutMs while a short GPS session collects fixes, then reports the best (lowest accuracy radius) — or with averageWindowMs, the position average over that window, which beats any single phone fix for precision (reported accuracy shrinks ~√n over the fixes seen, floored at 3 m because averaging beats noise, not systematic error). Adds what get_location doesn't: vertical accuracy, speed/bearing, satellites used in fix, HDOP, and the GNSS time-disciplined UTC. Requires location permission — if Haven doesn't hold it and Shizuku is running, the call grants it silently (this consent sheet is the gate); otherwise the error names the Settings path. Background throttling applies as with get_location.",
            inputSchema = objectSchema {
                integer("timeoutMs", "How long to keep collecting fixes before returning the best (1000–60000, default 10000). A fix that reaches ≤15 m accuracy after ~2 s ends the wait early.")
                integer("averageWindowMs", "Average every fix seen in this trailing window instead of picking the best single fix (1000–60000). Position averaging is the phone-GPS precision trick: fewer metres of scatter, honest √n confidence. Default off.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Take one precise GPS fix (collects for several seconds)?" },
        ) { args -> getPreciseFix(args) },

        "start_gps_log" to ToolHandler(
            description = "Start continuous GPS logging in a location-type foreground service: every fix is appended as a JSONL record to gps-logs/<id>.jsonl under Haven's external files dir (readable by the agent via read_gps_log and by the guest's hostfs share). Each fix record: t, lat, lon, acc, alt, vAcc, spd, brg, satsFix, satsView, cn0Mean, hdop, gpsUtcMs (disciplined GPS time when available); optional `nmea` records the raw sentences, `raw` records GnssClock+measurements per second (large — minutes of raw logging is hundreds of MB). Pass `capBytes` (default 128 MiB) to bound it; the log stops with stoppedReason when the cap hits rather than rotating silently. The foreground-service notification shows live counters; stop with stop_gps_log. Requires location permission (Shizuku self-grant path as usual) — and the consent sheet tap that approves this call is what keeps the foreground-service start legal on Android 12+.",
            inputSchema = objectSchema {
                integer("intervalMs", "Fix interval in ms (1000–60000, default 1000). 1 Hz is the battery-friendly default; the raw-measurement stream is independent of this.")
                boolean("nmea", "Also record every NMEA sentence (default false).")
                boolean("raw", "Also record GnssMeasurements (clock + per-satellite raw) — large, for post-processing (default false).")
                integer("capBytes", "Stop at this file size (bytes). Default 134217728 (128 MiB).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val iv = args.optInt("intervalMs", 1000)
                "Start continuous GPS logging (1 fix / ${iv}ms) in the foreground service?"
            },
        ) { args -> startLog(args) },

        "stop_gps_log" to ToolHandler(
            description = "Stop the active GPS log (started with start_gps_log), closing its file and (when nothing else needs GPS) the foreground service and engine. Returns the id, file path, and the fix count written.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Stop GPS logging?" },
        ) { _ -> stopLog() },

        "list_gps_logs" to ToolHandler(
            description = "List GPS logs written by start_gps_log: id, path, size, record count, and which one is active. Metadata only — read_gps_log pulls records. Read-only.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listLogs() },

        "read_gps_log" to ToolHandler(
            description = "Read the last N records of a GPS log file (id from list_gps_logs): fix records with position/accuracy/satellite counts, plus optional nmea/raw records. A tail read, not a stream — the whole file's history is on disk at the returned path. Reading a log is reading a track of where the phone was, so it's gated once per session rather than free like list_gps_logs.",
            inputSchema = objectSchema {
                string("id", "Log id from list_gps_logs (the file name without .jsonl).", required = true)
                integer("lines", "How many trailing records to return (1–500, default 50).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Read the tail of GPS log ${args.optString("id", "?")}?" },
        ) { args -> readLog(args) },

        "start_ntp_service" to ToolHandler(
            description = "Start an NTP service on this phone, disciplined by GPS time: an SNTP responder (NTPv4 header) that answers client polls with stratum-1 time built from the phone's GNSS clock (GnssMeasurements' GnssClock when the chipset reports it, else NMEA RMC UTC) — never from Android's network-synced wall clock. Clients point at this phone like any NTP server, e.g. chrony: `server <phone-lan-ip> port <port> iburst`. UDP port 123 is privileged on Android, so the port defaults to 10123 and must be ≥1024 — name it explicitly in the client. bind \"loopback\" (default) serves 127.0.0.1 only; \"lan\" also binds the Wi-Fi/Ethernet site-local address so other devices on the network (or through Haven's tunnels) can use it. While GPS samples are flowing the answer is stratum 1 / refid GPS / rootDispersion = the measured uncertainty; after 30 s without a fix the answer flips to LI=3 (unsynchronised) so clients disqualify the source honestly rather than trusting a stale model. Needs a GPS session (started here) and location permission (Shizuku self-grant path as usual).",
            inputSchema = objectSchema {
                integer("port", "UDP port to serve on (1024–65535, default 10123). 123 needs root on Android — not offered.")
                string("bind", "\"loopback\" (default) or \"lan\" — the latter also binds the device's Wi-Fi/Ethernet IPv4 so LAN peers can query the service.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val port = args.optInt("port", 10123)
                val bind = args.optString("bind", "loopback")
                "Start the GPS-disciplined NTP service (UDP :$port, bind=$bind)?"
            },
        ) { args -> startNtp(args) },

        "stop_ntp_service" to ToolHandler(
            description = "Stop the NTP service and release its GPS session (unless logging still needs it).",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Stop the NTP service?" },
        ) { _ -> stopNtp() },

        "attach_gps_to_guest" to ToolHandler(
            description = "Expose this phone's GPS to the Haven Linux guest as a real NMEA device: an abstract-namespace socket (\\0haven-gps) streams the chipset's raw NMEA sentences (GGA/RMC/GSV/…) into the guest, and the staged `haven-gps` helper materialises them as a PTY at /run/haven/gps0 — gpsd, gpspipe, chrony, or any character-device consumer \"just sees a GPS\". The socket is abstract-namespace, not TCP loopback: only processes sharing Haven's network namespace (the proot guest) can reach it. Attaching starts a 1 Hz GPS session for the bridge; it keeps running (even screen-off, via the same session contract as start_gps_log) until detach_gps_from_guest. Returns socketName, the in-guest helperPath, a helperCommand to start the PTY, and a verifyCommand to confirm NMEA is flowing. Requires the master opt-in first: Settings → \"Expose GPS to the Linux guest\" (or the gps_guest_exposure_enabled preference) — once exposed, any process in the guest can read the phone's position, so the switch is deliberately separate from per-call consent.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Expose the phone's GPS to the Linux guest as an NMEA device?" },
        ) { _ -> attachGuestBridge() },

        "detach_gps_from_guest" to ToolHandler(
            description = "Stop the GPS→guest bridge started by attach_gps_to_guest: the \\0haven-gps socket closes, /run/haven/gps0 goes dead in the guest, and the bridge's GPS session is released (unless logging or NTP still needs one). Reports the sentences served and readers that connected. The teardown counterpart to attach_gps_to_guest.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> detachGuestBridge() },
    )

    // --- handlers ---

    private fun getStatus(): JSONObject = GpsBroker.statusJson()

    private fun getPreciseFix(args: JSONObject): JSONObject {
        ensureFinePermission()
        val timeoutMs = args.optInt("timeoutMs", 10_000).coerceIn(1_000, 60_000)
        val averageWindowMs = args.optInt("averageWindowMs", 0).coerceIn(0, 60_000)
        return GpsBroker.preciseFix(context, timeoutMs, averageWindowMs)
    }

    private fun startLog(args: JSONObject): JSONObject {
        ensureFinePermission()
        val intervalMs = args.optInt("intervalMs", 1_000).coerceIn(1_000, 60_000)
        val includeNmea = args.optBoolean("nmea", false)
        val includeRaw = args.optBoolean("raw", false)
        val capBytes = if (args.has("capBytes")) {
            args.optLong("capBytes", GpsBroker.DEFAULT_LOG_CAP_BYTES)
                .coerceIn(1_048_576L, 2L * 1024 * 1024 * 1024)
        } else null
        return GpsBroker.startLogging(context, intervalMs, includeNmea, includeRaw, capBytes)
    }

    private fun stopLog(): JSONObject = GpsBroker.stopLogging()

    private fun listLogs(): JSONObject = JSONObject().apply {
        val logs = GpsBroker.listLogs(context)
        put("logs", logs)
        put("count", logs.length())
    }

    private fun readLog(args: JSONObject): JSONObject {
        val id = args.optString("id").ifBlank { throw McpError(-32602, "id required (from list_gps_logs)") }
        val lines = args.optInt("lines", 50)
        return runCatching { GpsBroker.readLogTail(context, id, lines) }
            .getOrElse { e ->
                throw McpError(-32602, e.message ?: "read failed")
            }
    }

    private fun startNtp(args: JSONObject): JSONObject {
        ensureFinePermission()
        val port = args.optInt("port", 10123).coerceIn(1024, 65535)
        val bind = args.optString("bind", "loopback").lowercase()
        if (bind !in setOf("loopback", "lan")) {
            throw McpError(-32602, "bind must be \"loopback\" or \"lan\" (got \"$bind\")")
        }
        return GpsBroker.startNtp(context, port, bindLan = bind == "lan")
    }

    private fun stopNtp(): JSONObject = GpsBroker.stopNtp()

    // --- guest device bridge (mirrors UsbToolProvider.attach/detach) ---

    private suspend fun attachGuestBridge(): JSONObject {
        // Master opt-in gate (Settings → "Expose GPS to the Linux guest").
        // Off by default — once exposed, any guest process can read the
        // phone's position, so it needs a deliberate user switch on top of
        // the per-call consent sheet.
        if (!preferencesRepository.gpsGuestExposureEnabled.first()) {
            throw McpError(
                -32603,
                "GPS-to-guest is disabled. Enable Settings → \"Expose GPS to the Linux guest\" " +
                    "(or set the gps_guest_exposure_enabled preference) first.",
            )
        }
        ensureFinePermission()
        val result = GpsBroker.startGuestBridge(context)
        // Re-stage on every attach so an app update refreshes the helper
        // (the stageHavenUsbArtifacts idiom). null when there is no active
        // proot rootfs — the bridge still runs; nothing is staged.
        val helperPath = localSessionManager.prootManager.stageHavenGpsArtifacts()
        result.put("socketNamespace", "abstract")
        result.put("helperPath", helperPath ?: JSONObject.NULL)
        if (helperPath != null) {
            result.put("helperCommand", "haven-gps")
            result.put(
                "verifyCommand",
                "timeout 3 head -c 120 /run/haven/gps0",
            )
            result.put(
                "note",
                "Bridge live on abstract socket \\0${GpsGuestServer.SOCKET_NAME}. Run `haven-gps` " +
                    "via run_in_proot(background:true) to materialise the PTY at /run/haven/gps0 " +
                    "(needs socat in the guest), then verify with verifyCommand — expect raw NMEA " +
                    "(GGA/GSV/…) whose lat/lon matches get_gps_status. Silent output means no sky view — " +
                    "or Haven is not in the foreground: Android's while-in-use rule suspends the engine " +
                    "when Haven is backgrounded without its GPS-log foreground service (bring Haven " +
                    "forward, or start_gps_log alongside, then re-check get_gps_status).",
            )
        }
        return result
    }

    private fun detachGuestBridge(): JSONObject = GpsBroker.stopGuestBridge()

    // --- permission gate (same shape as SensesToolProvider) ---

    private fun hasFine(): Boolean =
        context.checkSelfPermission(FINE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Ensure ACCESS_FINE_LOCATION, granting via Shizuku when missing. The
     * consent sheet that authorised THIS call is the human gate for the
     * silent pm grant — the ordering is deliberate (SensesToolProvider).
     */
    private fun ensureFinePermission() {
        if (hasFine()) return
        shizukuGrant("pm grant ${context.packageName} $FINE_PERMISSION")
        if (!hasFine()) {
            throw McpError(-32603, FINE_PERMISSION_MESSAGE)
        }
    }

    companion object {
        const val FINE_PERMISSION = "android.permission.ACCESS_FINE_LOCATION"

        val FINE_PERMISSION_MESSAGE =
            "Location permission is not granted and could not be granted via Shizuku. " +
                "Either open Settings → Apps → Haven → Permissions → Location and allow it, " +
                "or install Shizuku (https://shizuku.rikka.app) and grant Haven permission so " +
                "the tool can grant itself on your behalf (this consent sheet is the gate)."
    }
}