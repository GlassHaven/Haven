package sh.haven.core.rdp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import java.io.Closeable
import kotlin.math.roundToInt

/**
 * Forwards Android GNSS / network location fixes into an active RDP session's
 * `Microsoft::Windows::RDS::Location` (`MS-RDPEL`) channel (#890).
 *
 * Raw coordinates are never written to logs (`#518` PII scrub policy).
 */
class RdpLocationForwarder internal constructor(
    private val source: LocationSourceAdapter,
    private val sink: (latitude: Double, longitude: Double, altitudeMeters: Int) -> Unit,
    private val log: (level: String, msg: String) -> Unit = { _, _ -> },
) : Closeable {

    internal data class Fix(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Int,
        val timeMs: Long,
    )

    internal interface LocationSourceAdapter {
        fun hasLocationPermission(): Boolean
        fun bestLastKnownFix(): Fix?
        fun startUpdates(onFix: (Fix) -> Unit): Boolean
        fun stopUpdates()
    }

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    /**
     * Start (or retry starting, e.g. right after runtime permission is granted)
     * forwarding fixes to [sink]. Safe to call multiple times.
     */
    @Synchronized
    fun startIfPermitted(): Boolean {
        if (closed) return false
        if (started) return true
        if (!source.hasLocationPermission()) {
            log("D", "RDP location forwarding waiting for location permission")
            return false
        }
        source.bestLastKnownFix()?.let { initial ->
            deliver(initial)
        }
        started = source.startUpdates(::deliver)
        if (started) {
            log("D", "RDP location forwarding active")
        }
        return started
    }

    private fun deliver(fix: Fix) {
        if (closed) return
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) return
        sink(
            fix.latitude.coerceIn(-90.0, 90.0),
            fix.longitude.coerceIn(-180.0, 180.0),
            fix.altitudeMeters,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (started) {
            started = false
            runCatching { source.stopUpdates() }
        }
    }

    companion object {
        fun create(
            context: Context,
            sink: (latitude: Double, longitude: Double, altitudeMeters: Int) -> Unit,
            log: (level: String, msg: String) -> Unit = { _, _ -> },
        ): RdpLocationForwarder {
            return RdpLocationForwarder(
                source = AndroidLocationSourceAdapter(context.applicationContext),
                sink = sink,
                log = log,
            )
        }
    }
}

private class AndroidLocationSourceAdapter(
    private val appContext: Context,
) : RdpLocationForwarder.LocationSourceAdapter {

    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var activeListener: LocationListener? = null

    override fun hasLocationPermission(): Boolean {
        val fine = appContext.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = appContext.checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun candidateProviders(lm: LocationManager): List<String> {
        val out = ArrayList<String>(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out.add(LocationManager.FUSED_PROVIDER)
        }
        out.add(LocationManager.GPS_PROVIDER)
        out.add(LocationManager.NETWORK_PROVIDER)
        return out.filter { provider ->
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun Location.toFix(): RdpLocationForwarder.Fix = RdpLocationForwarder.Fix(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude() && altitude.isFinite()) altitude.roundToInt() else 0,
        timeMs = time,
    )

    @SuppressLint("MissingPermission")
    override fun bestLastKnownFix(): RdpLocationForwarder.Fix? {
        if (!hasLocationPermission()) return null
        val lm = locationManager ?: return null
        return candidateProviders(lm)
            .mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    @SuppressLint("MissingPermission")
    override fun startUpdates(onFix: (RdpLocationForwarder.Fix) -> Unit): Boolean {
        if (!hasLocationPermission()) return false
        val lm = locationManager ?: return false
        val providers = candidateProviders(lm)
        if (providers.isEmpty()) return false

        val listener = LocationListener { location ->
            onFix(location.toFix())
        }
        var registeredAny = false
        val looper = Looper.getMainLooper()
        for (provider in providers) {
            val ok = runCatching {
                lm.requestLocationUpdates(provider, 5_000L, 5f, listener, looper)
                true
            }.getOrDefault(false)
            if (ok) registeredAny = true
        }
        if (registeredAny) {
            activeListener = listener
        }
        return registeredAny
    }

    override fun stopUpdates() {
        val listener = activeListener ?: return
        activeListener = null
        locationManager?.let { lm ->
            runCatching { lm.removeUpdates(listener) }
        }
    }
}
