package sh.haven.app.usb

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.local.uml.UmlGuestManager
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbDeviceInfo
import sh.haven.core.usb.massstorage.MassStorageBackend
import sh.haven.core.usb.massstorage.MassStorageDevice
import sh.haven.core.usb.massstorage.NbdServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fast "open a USB card live" route: instead of booting the QEMU helper
 * VM over USB/IP (minutes), start an [NbdServer] on host loopback that serves
 * the card's raw sectors through the SCSI/BBB layer, then boot a UML guest
 * whose init script ([haven-recover], baked into the rootfs) attaches it as
 * /dev/nbd0 and prints the ddrescue/mtools command lines. The guest kernel
 * boots in seconds, so the card is workable almost immediately. The card is
 * never mounted by the guest kernel (no vfat/exfat in it) and never synced to
 * a workspace — ddrescue images go out through the hostfs share
 * (`filesDir/uml/share`), which the app's Files tab reaches via the local
 * profile.
 *
 * The live session surfaces as a transient GUEST [ConnectionProfile] (label
 * "USB: <name> (live)") so the ordinary GUEST terminal tab and connect path
 * work unchanged: this manager registers + boots the session itself, and
 * connectGuest's existing-CONNECTED check just navigates to it. The profile
 * carries NO usbDriveSerial on purpose — that field routes a connect into
 * [UsbDriveConnectionPreflight]'s VM-reopen path, which is wrong for a guest
 * session, and the VM manager's own bookmark dedup would try to repurpose the
 * row. Dedup is by (GUEST, label) instead.
 *
 * Read-only by default: the export advertises NBD_FLAG_READ_ONLY and the NBD
 * server refuses writes before the SCSI layer ever sees one.
 */
@Singleton
class UmlRecoveryManager @Inject constructor(
    private val usbBroker: UsbBroker,
    private val umlGuestManager: UmlGuestManager,
    private val connectionRepository: ConnectionRepository,
) {
    enum class Phase { IDLE, OPENING, READY, ERROR }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val deviceName: String? = null,
        val productName: String? = null,
        val busid: String? = null,
        val profileId: String? = null,
        val sessionId: String? = null,
        val nbdPort: Int = 0,
        /** Human-readable progress while [phase] is OPENING. */
        val stage: String = "",
        val error: String? = null,
        val readOnly: Boolean = true,
        val capacity: String = "",
    )

    /** Keyed by busid, like [UsbDriveVmManager.sessions]. One live guest per drive. */
    private val _sessions = MutableStateFlow<Map<String, Status>>(emptyMap())
    val sessions: StateFlow<Map<String, Status>> = _sessions.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keepAliveJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    init {
        // Physical unplug unwinds the whole session (guest, export, handle).
        // close() no-ops for unknown busids, so other components' detach
        // handling is unaffected.
        scope.launch {
            usbBroker.detached.collect { deviceName ->
                runCatching { close(busidOf(deviceName)) }
            }
        }
    }

    class UmlRecoveryException(message: String) : Exception(message)

    private fun updateStatus(busid: String, transform: (Status) -> Status) {
        _sessions.update { it + (busid to transform(it[busid] ?: Status())) }
    }

    /** Attached mass-storage devices (the live-open candidates). */
    fun massStorageDevices(): List<UsbDeviceInfo> =
        usbBroker.listDevices().filter { it.interfaces.any { i -> i.interfaceClass == USB_CLASS_MASS_STORAGE } }

    /**
     * Open the card live: validate, start the NBD export, boot the recovery
     * guest. Returns the deviceName once the fast checks pass; the guest boot
     * continues in the background — poll [sessions] until READY (or open the
     * transient profile's terminal tab, which works either way).
     */
    suspend fun open(deviceName: String?, writable: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!umlGuestManager.isAvailable()) {
            throw UmlRecoveryException("This build does not include the Linux guest payload")
        }
        val target = resolveDrive(deviceName)
        val info = try {
            usbBroker.openDevice(target)
        } catch (e: Exception) {
            throw UmlRecoveryException("USB open failed: ${e.message}")
        }
        val busid = busidOf(target)
        if (_sessions.value[busid]?.phase.let { it == Phase.OPENING || it == Phase.READY }) {
            throw UmlRecoveryException("$target is already open live; close it first.")
        }
        // INQUIRY + READ CAPACITY: proves the card answers SCSI before we boot
        // a guest at it, and gives the status line its capacity.
        val device = try {
            MassStorageDevice.open(target, usbBroker)
        } catch (e: Exception) {
            throw UmlRecoveryException("Card didn't answer SCSI: ${e.message}")
        }
        val server = NbdServer(
            backend = MassStorageBackend(device, readOnly = !writable),
            exportName = NBD_EXPORT_NAME,
        )
        val port = try {
            server.start()
        } catch (e: Exception) {
            throw UmlRecoveryException("Couldn't bind the NBD export: ${e.message}")
        }

        val profile = ensureProfile(info)
        val label = liveLabel(info)
        _sessions.update {
            it + (busid to Status(
                phase = Phase.OPENING,
                deviceName = target,
                productName = info.productName,
                busid = busid,
                profileId = profile.id,
                nbdPort = port,
                stage = "Booting the recovery guest…",
                readOnly = !writable,
                capacity = describeCapacity(device),
            ))
        }
        startKeepAlive(target, busid)

        scope.launch {
            try {
                val sessionId = umlGuestManager.registerSession(
                    profileId = profile.id,
                    label = profile.label,
                    extraKernelArgs = listOf(
                        "haven_nbd_host=$NBD_HOST",
                        "haven_nbd_port=$port",
                    ),
                )
                umlGuestManager.connectSession(sessionId)
                updateStatus(busid) { it.copy(sessionId = sessionId) }
                // Boots the kernel now (headless); the terminal tab reattaches
                // to this same session when the user opens the profile.
                umlGuestManager.startHeadlessShell(sessionId)
                awaitRecoveryBanner(sessionId, busid)
            } catch (e: Exception) {
                Log.w(TAG, "Live recovery boot failed: ${e.message}")
                updateStatus(busid) { it.copy(phase = Phase.ERROR, error = e.message) }
            }
        }
        target
    }

    /**
     * Wait for the guest console to print the haven-recover banner (the NBD
     * attach succeeded). The guest usually gets there in well under a minute;
     * a timeout marks the session ERROR but leaves the guest running — the
     * console is still attachable and may simply be slow.
     */
    private suspend fun awaitRecoveryBanner(sessionId: String, busid: String) {
        val deadline = System.currentTimeMillis() + BANNER_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val tail = umlGuestManager.readAgentScrollback(sessionId, 64 * 1024)
            if (tail != null && BANNER_MARKER.toRegex(RegexOption.LITERAL).containsMatchIn(String(tail, Charsets.UTF_8))) {
                updateStatus(busid) { it.copy(phase = Phase.READY, stage = "") }
                Log.i(TAG, "Live recovery ready: $busid")
                return
            }
            val st = umlGuestManager.sessions.value[sessionId]
            if (st == null || st.status == UmlGuestManager.SessionState.Status.DISCONNECTED ||
                st.status == UmlGuestManager.SessionState.Status.ERROR
            ) {
                throw UmlRecoveryException("The recovery guest exited before it attached the card")
            }
            delay(500)
        }
        updateStatus(busid) {
            it.copy(
                phase = Phase.ERROR,
                error = "The guest didn't report the card within ${BANNER_TIMEOUT_MS / 1000}s — open its terminal to inspect.",
            )
        }
    }

    /**
     * Tear down a live session: power the guest off (its NBD connection to the
     * export goes with it), stop the export, release the broker handle.
     */
    suspend fun close(busid: String) {
        val s = _sessions.value[busid] ?: return
        if (s.phase == Phase.IDLE) return
        stopKeepAlive(busid)
        s.sessionId?.let { runCatching { umlGuestManager.closeGuest(it) } }
        val deviceName = s.deviceName
        withContext(Dispatchers.IO) {
            if (deviceName != null) runCatching { usbBroker.closeDevice(deviceName) }
        }
        _sessions.update { it - busid }
    }

    suspend fun closeAll() {
        _sessions.value.keys.toList().forEach { close(it) }
    }

    private fun startKeepAlive(deviceName: String, busid: String) {
        keepAliveJobs.remove(busid)?.cancel()
        keepAliveJobs[busid] = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (!usbBroker.isOpen(deviceName)) break
                val ok = runCatching {
                    usbBroker.controlTransfer(deviceName, GET_STATUS_REQUEST_TYPE, GET_STATUS_REQUEST, 0, 0, null, 2, 1000)
                }.isSuccess
                if (ok) {
                    consecutiveFailures = 0
                } else if (++consecutiveFailures >= KEEP_ALIVE_MAX_FAILURES) {
                    Log.w(TAG, "keepalive giving up on $deviceName after $consecutiveFailures failures")
                    break
                }
            }
        }
    }

    private fun stopKeepAlive(busid: String) {
        keepAliveJobs.remove(busid)?.cancel()
    }

    /**
     * Find or create the transient GUEST profile for this drive. Deduped by
     * (GUEST, label) — NOT by usbDriveSerial, which would route a later
     * connect into the VM preflight and let UsbDriveVmManager's bookmark
     * dedup repurpose the row for its own SSH session.
     */
    private suspend fun ensureProfile(info: UsbDeviceInfo): ConnectionProfile {
        val label = liveLabel(info)
        val existing = connectionRepository.getAll().firstOrNull {
            it.isGuest && it.label == label
        }
        if (existing != null) return existing
        val profile = ConnectionProfile(
            label = label,
            host = "localhost",
            port = 0,
            username = "",
            connectionType = "GUEST",
        )
        connectionRepository.save(profile)
        return profile
    }

    private fun resolveDrive(deviceName: String?): String {
        if (!deviceName.isNullOrBlank()) return deviceName
        val drives = massStorageDevices()
        return when (drives.size) {
            0 -> throw UmlRecoveryException("No USB mass-storage drive attached.")
            1 -> drives.single().deviceName
            else -> throw UmlRecoveryException(
                "Multiple USB drives attached — pass deviceName. Found: ${drives.joinToString { it.deviceName }}",
            )
        }
    }

    private fun describeCapacity(device: MassStorageDevice): String {
        val bytes = device.sectors * device.blockSize
        return when {
            bytes >= 1L shl 30 -> "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
            else -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
        }
    }

    /** "USB: <name> (live)" — the same string used to dedup the profile. */
    private fun liveLabel(info: UsbDeviceInfo): String =
        (info.productName?.takeIf { it.isNotBlank() }?.let { "USB: $it" } ?: "USB drive") + " (live)"

    // /dev/bus/usb/BBB/DDD → "B-D" (matches UsbIpServer + UsbDriveVmManager).
    private fun busidOf(deviceName: String): String {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 1
        return "$bus-$dev"
    }

    companion object {
        private const val TAG = "UmlRecoveryManager"
        const val USB_CLASS_MASS_STORAGE = 8

        /** The passt local-mode gateway: host loopback as seen from the guest. */
        const val NBD_HOST = "169.254.2.2"

        /** Must match haven-recover's `nbd-client -N` export name. */
        const val NBD_EXPORT_NAME = "haven-sd"

        /** haven-recover prints this once nbd-client has the card attached. */
        const val BANNER_MARKER = "Raw card attached at /dev/nbd0"

        // GET_STATUS(device): bmRequestType=IN|Standard|Device, bRequest=0 —
        // the keepalive poke (same shape as UsbDriveVmManager's).
        private const val GET_STATUS_REQUEST_TYPE = 0x80
        private const val GET_STATUS_REQUEST = 0x00

        private const val BANNER_TIMEOUT_MS = 120_000L

        private const val KEEP_ALIVE_INTERVAL_MS = 5_000L
        private const val KEEP_ALIVE_MAX_FAILURES = 3
    }
}