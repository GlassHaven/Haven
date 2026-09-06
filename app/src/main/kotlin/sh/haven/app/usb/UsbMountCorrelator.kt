package sh.haven.app.usb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One mounted storage volume, normalized for matching. [key] is the stable
 * identity used for baseline diffs and claim bookkeeping (StorageVolume UUID
 * when available, else the /storage/<fsuuid> path segment).
 */
data class UsbVolumeSnapshot(
    val key: String,
    val path: String,
    val description: String?,
    /** Volume mounted read-only (MEDIA_MOUNTED_READ_ONLY). */
    val readOnly: Boolean,
)

enum class UsbMountConfidence {
    /**
     * A volume appeared after this device's attach event was seen — the
     * strong signal that Android (vold) mounted *this* drive.
     */
    ATTACH_DIFF,

    /**
     * No attach-time baseline (device attached before app start, or the
     * attach broadcast raced the mount), but exactly one mass-storage device
     * is attached and exactly one volume that appeared during this app
     * session is unclaimed. A guess, not a proof — callers should label it.
     */
    UNCLAIMED_VOLUME,
}

/** A volume matched to a USB device, before the writable check is applied. */
data class UsbMountCandidate(
    val volume: UsbVolumeSnapshot,
    val confidence: UsbMountConfidence,
)

/** A volume matched to a specific USB mass-storage device, ready to act on. */
data class UsbMountMatch(
    val deviceName: String,
    val volume: UsbVolumeSnapshot,
    val confidence: UsbMountConfidence,
    /** !volume.readOnly && the app holds All-files access (MANAGE_EXTERNAL_STORAGE). */
    val writable: Boolean,
)

/**
 * Pure, Android-free matching logic for [UsbMountCorrelator] — the unit-test
 * surface. Rules:
 *
 *  - Attach-diff (strong): [baselineKeys] is the volume set recorded when the
 *    device attached; exactly one volume present now but absent then → match.
 *  - Unclaimed-volume (weak): no baseline, exactly one attached mass-storage
 *    device, and exactly one unclaimed volume that was NOT already present at
 *    app start ([preexistingKeys]) → match. Two unclaimed candidates (e.g. a
 *    microSD plus the stick) or none → null; ambiguity is refused, not guessed.
 *  - A volume already claimed by another device is subtracted in both paths.
 */
object UsbMountMatcher {

    fun mountedAfter(baselineKeys: Set<String>, current: List<UsbVolumeSnapshot>): List<UsbVolumeSnapshot> =
        current.filter { it.key !in baselineKeys }

    fun unclaimed(current: List<UsbVolumeSnapshot>, claimedKeys: Set<String>): List<UsbVolumeSnapshot> =
        current.filter { it.key !in claimedKeys }

    fun match(
        deviceCount: Int,
        baselineKeys: Set<String>?,
        current: List<UsbVolumeSnapshot>,
        claimedKeys: Set<String>,
        preexistingKeys: Set<String> = emptySet(),
    ): UsbMountCandidate? {
        if (baselineKeys != null) {
            val appeared = mountedAfter(baselineKeys, current).filter { it.key !in claimedKeys }
            return if (appeared.size == 1) UsbMountCandidate(appeared.single(), UsbMountConfidence.ATTACH_DIFF) else null
        }
        if (deviceCount != 1) return null
        val candidates = unclaimed(current, claimedKeys).filter { it.key !in preexistingKeys }
        return if (candidates.size == 1) UsbMountCandidate(candidates.single(), UsbMountConfidence.UNCLAIMED_VOLUME) else null
    }
}

/**
 * Correlates a USB mass-storage device with the storage volume Android
 * mounted for it — the pre-boot knowledge #603's route picker needs. There is
 * no API linking a [android.os.storage.StorageVolume] to the [android.hardware.usb.UsbDevice]
 * that backs it, so this is a heuristic:
 *
 *  - On attach (fed by the ACTION_USB_DEVICE_ATTACHED receiver in HavenApp)
 *    the currently-mounted volume keys are recorded as that device's
 *    baseline; a volume that appears afterwards is matched with
 *    [UsbMountConfidence.ATTACH_DIFF] (vold typically mounts 0.5–2s after
 *    attach, so [awaitMatch] polls rather than deciding immediately).
 *  - Without a baseline (device already attached at app start, or the
 *    broadcast raced the mount), the weak [UsbMountConfidence.UNCLAIMED_VOLUME]
 *    path applies: exactly one attached mass-storage device and exactly one
 *    volume that appeared during this app session. Volumes present at app
 *    start are excluded — a long-mounted microSD must never claim the stick.
 *
 * Matching is claim-scoped: once a device is matched to a volume, that volume
 * is subtracted from every other device's candidates until detach.
 *
 * Everything here is gated on API 30+ (StorageVolume.directory/state/uuid are
 * API 30); on older devices enumeration returns an empty list and every match
 * is null, so callers fall back to the VM-only flow unchanged.
 */
@Singleton
class UsbMountCorrelator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Volume keys present when [onAppStarted] ran. */
    private var preexistingKeys: Set<String> = emptySet()

    /** deviceName → volume keys mounted at attach time (the strong-path baseline). */
    private val baselines = HashMap<String, Set<String>>()

    /** deviceName → matched volume key (released on detach). */
    private val claims = HashMap<String, String>()

    /** Record the volume set present at startup; later-appearing volumes are weak-match candidates. */
    fun onAppStarted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        preexistingKeys = snapshotVolumes().map { it.key }.toSet()
    }

    /** Record this device's attach-time volume baseline (from the attach receiver). */
    fun onDeviceAttached(deviceName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        baselines[deviceName] = snapshotVolumes().map { it.key }.toSet()
    }

    /** Release this device's baseline and any volume claim it holds. */
    fun onDeviceDetached(deviceName: String) {
        baselines.remove(deviceName)
        claims.remove(deviceName)
    }

    /**
     * Poll current volumes until [UsbMountMatcher.match] yields a candidate
     * for [deviceName] or [timeoutMs] elapses. On a match the volume is
     * claimed for this device (subtracting it from other devices' candidates).
     */
    suspend fun awaitMatch(deviceName: String, deviceCount: Int, timeoutMs: Long = 3_000): UsbMountMatch? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // snapshotVolumes does file I/O (canRead) — keep it off the caller's thread.
            val match = withContext(Dispatchers.IO) { matchNow(deviceName, deviceCount) }
            match?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            delay(POLL_INTERVAL_MS)
        }
    }

    /** No-wait variant for MCP snapshots. */
    fun currentMatch(deviceName: String, deviceCount: Int): UsbMountMatch? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return matchNow(deviceName, deviceCount)
    }

    // Callers gate on SDK_INT >= R before reaching here (awaitMatch/currentMatch).
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun matchNow(deviceName: String, deviceCount: Int): UsbMountMatch? {
        val current = snapshotVolumes()
        // Subtract only OTHER devices' claims — re-reporting a device's own
        // match (awaitMatch then currentMatch) must still find its volume.
        val claimedKeys = claims.filterKeys { it != deviceName }.values.toSet()
        val candidate = UsbMountMatcher.match(
            deviceCount = deviceCount,
            baselineKeys = baselines[deviceName],
            current = current,
            claimedKeys = claimedKeys,
            preexistingKeys = preexistingKeys,
        ) ?: return null
        claims[deviceName] = candidate.volume.key
        return UsbMountMatch(
            deviceName = deviceName,
            volume = candidate.volume,
            confidence = candidate.confidence,
            writable = !candidate.volume.readOnly && Environment.isExternalStorageManager(),
        )
    }

    /** Mirrors LocalFileBackend.listRoots()'s removable-volume enumeration (API 30+). */
    private fun snapshotVolumes(): List<UsbVolumeSnapshot> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val primaryPath = Environment.getExternalStorageDirectory().absolutePath
        return sm.storageVolumes.mapNotNull { volume ->
            try {
                if (volume.isPrimary) return@mapNotNull null
                val state = volume.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
                val dir: File = volume.directory ?: return@mapNotNull null
                if (dir.absolutePath == primaryPath) return@mapNotNull null
                if (!dir.canRead()) return@mapNotNull null
                UsbVolumeSnapshot(
                    key = volume.uuid ?: dir.name,
                    path = dir.absolutePath,
                    description = volume.getDescription(context),
                    readOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping storage volume: ${e.message}")
                null
            }
        }
    }

    companion object {
        private const val TAG = "UsbMountCorrelator"
        private const val POLL_INTERVAL_MS = 500L
    }
}