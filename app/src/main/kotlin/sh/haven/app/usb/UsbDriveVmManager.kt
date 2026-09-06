package sh.haven.app.usb

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.local.QemuManager
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbDeviceInfo
import sh.haven.core.usb.UsbIpServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the user-facing "Open USB drive" flow (#287): export the
 * attached USB mass-storage device over the shipped [UsbIpServer], boot the
 * [QemuManager] appliance VM (which imports + mounts it), and surface the
 * drive's files through an ordinary loopback SSH/SFTP [ConnectionProfile] — so
 * the existing file browser, terminal, and MCP file verbs all work unchanged.
 *
 * The VM boot is slow (TCG, no KVM unrooted), so [open] does the fast checks
 * (USB permission, mass-storage class) synchronously and then boots in the
 * background; callers poll [sessions]. Up to [QemuManager.MAX_CONCURRENT_DRIVES]
 * drives can be open at once, sharing ONE running VM (each additional drive
 * is just another USB/IP attach inside it, not a second boot — see
 * [QemuManager]'s class doc for why).
 *
 * The saved "USB: …" connection is a durable **bookmark** (tagged with the
 * drive's serial via [ConnectionProfile.usbDriveSerial]), not a live session.
 * [close]ing a drive always ends its own reachability (its key is revoked and
 * its mounts unmounted even if other drives keep the shared VM up); the VM
 * itself — and the port every open drive's profile currently shares — only
 * actually stops once the *last* attached drive closes (or on app-restart:
 * VM state doesn't survive the process). [reopenForProfile] (driven by
 * [UsbDriveConnectionPreflight]) reboots/reattaches and refreshes the profile
 * the moment the user clicks a dead bookmark again, instead of leaving a
 * connection that just fails.
 */
@Singleton
class UsbDriveVmManager @Inject constructor(
    private val qemuManager: QemuManager,
    private val usbIpServer: UsbIpServer,
    private val usbBroker: UsbBroker,
    private val connectionRepository: ConnectionRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val sshSessionManager: sh.haven.core.ssh.SshSessionManager,
    private val usbMountCorrelator: UsbMountCorrelator,
) {
    enum class Phase { IDLE, OPENING, READY, ERROR }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val deviceName: String? = null,
        val productName: String? = null,
        val busid: String? = null,
        val profileId: String? = null,
        val keyId: String? = null,
        val mounts: List<String> = emptyList(),
        val sshPort: Int = 0,
        /** Human-readable progress while [phase] is OPENING (the boot is slow). */
        val stage: String = "",
        val error: String? = null,
        val readOnly: Boolean = true,
        /** LUKS-encrypted partitions found but not yet unlocked (mount-dir name, e.g. "sdb2"). */
        val locked: List<String> = emptyList(),
    )

    // Keyed by busid — one entry per drive that's ever been opened this app
    // session (removed on [close]). Up to QemuManager.MAX_CONCURRENT_DRIVES can
    // be OPENING/READY at once.
    private val _sessions = MutableStateFlow<Map<String, Status>>(emptyMap())
    val sessions: StateFlow<Map<String, Status>> = _sessions.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // A physical unplug closes the broker handle but doesn't unwind our own
        // export + VM session — without this the export map, VM-side attach, and
        // the READY status leak until an explicit close. The broker signals the
        // detach here (dependency-inverted: core:usb doesn't know about us).
        // close() no-ops when no session exists for the busid, so an unplug of a
        // never-opened device is harmless.
        scope.launch {
            usbBroker.detached.collect { deviceName ->
                // Release the #603 mount claim/baseline so a replug (new
                // /dev/bus/usb name) or another drive can claim the volume.
                runCatching { usbMountCorrelator.onDeviceDetached(deviceName) }
                runCatching { close(busidOf(deviceName)) }
            }
        }
        // Drive keys minted before enabledForAuth=false landed are still in
        // the try-every-key pool, burning sshd auth attempts on every
        // connection. Idempotent, so it just runs on each construction.
        scope.launch { runCatching { disableStaleDriveKeyOffers() } }
    }

    /** Take every previously-minted drive key out of the try-every-key pool. */
    internal suspend fun disableStaleDriveKeyOffers() {
        sshKeyRepository.getAll()
            .filter { it.label == SshKey.USB_DRIVE_VM_LABEL && it.enabledForAuth }
            .forEach { key ->
                Log.i(TAG, "Disabling offer-for-connections on stale drive key ${key.id}")
                sshKeyRepository.setEnabledForAuth(key.id, false)
            }
    }

    private fun updateStatus(busid: String, transform: (Status) -> Status) {
        _sessions.update { it + (busid to transform(it[busid] ?: Status())) }
    }

    /** Update the progress line, but only while still opening (ignore late callbacks). */
    private fun stage(busid: String, text: String) {
        updateStatus(busid) { if (it.phase == Phase.OPENING) it.copy(stage = text) else it }
    }

    class UsbVmException(message: String) : Exception(message)

    /** USB mass-storage devices attached to the phone (the "open in a VM" candidates). */
    fun massStorageDevices(): List<UsbDeviceInfo> =
        usbBroker.listDevices().filter { it.interfaces.any { i -> i.interfaceClass == USB_CLASS_MASS_STORAGE } }

    private fun activeCount(): Int = _sessions.value.values.count { it.phase == Phase.OPENING || it.phase == Phase.READY }

    /**
     * Validate + open the device + start the VM boot in the background. Returns
     * the resolved deviceName immediately once the (fast) checks pass; the VM is
     * still booting — poll [sessions] until phase READY (profileId set) or ERROR.
     */
    suspend fun open(deviceName: String?, writable: Boolean = false): String {
        if (activeCount() >= QemuManager.MAX_CONCURRENT_DRIVES) {
            throw UsbVmException("Already have ${QemuManager.MAX_CONCURRENT_DRIVES} USB drive(s) attached — that's the concurrency limit. Close one first.")
        }
        val target = resolveDrive(deviceName)
        val info = try {
            usbBroker.openDevice(target)
        } catch (e: Exception) {
            throw UsbVmException("USB open failed: ${e.message}")
        }
        if (info.interfaces.none { it.interfaceClass == USB_CLASS_MASS_STORAGE }) {
            throw UsbVmException("$target is not a USB mass-storage device (class 8). Use usb_attach_to_guest for HID/serial devices.")
        }
        val busid = busidOf(target)
        if (_sessions.value[busid]?.phase.let { it == Phase.OPENING || it == Phase.READY }) {
            throw UsbVmException("$target is already open in a VM; close it first.")
        }
        // Reuse an existing bookmark for this physical drive (matched by USB
        // serial) instead of stamping out a new ConnectionProfile every open.
        // busid can't be used for this — it's derived from Android's
        // /dev/bus/usb/BBB/DDD path, which changes on every physical
        // unplug/replug even for the same stick, so busid-based dedup alone
        // let duplicates pile up in the Connections list across repeat opens.
        val existingBookmark = info.serialNumber?.let { serial ->
            connectionRepository.getAll().firstOrNull { it.usbDriveSerial == serial }
        }
        _sessions.update { it + (busid to Status(Phase.OPENING, target, info.productName, busid, stage = "Preparing…", readOnly = !writable)) }
        startKeepAlive(target, busid)
        scope.launch {
            try {
                val (profile, mounts) = bootAndAttach(target, info, busid, existingProfile = existingBookmark, readOnly = !writable)
                autoOpenInFiles(profile, mounts, busid)
            } catch (e: Exception) {
                Log.w(TAG, "USB drive VM boot failed: ${e.message}")
                stopKeepAlive(busid)
                updateStatus(busid) { Status(Phase.ERROR, target, info.productName, busid, error = e.message, readOnly = !writable) }
            }
        }
        return target
    }

    /**
     * Unlock a LUKS-encrypted partition reported in a session's [Status.locked]
     * (against the still-running VM from [open]) and refresh its status. Throws
     * on a wrong passphrase or if that drive isn't open.
     */
    suspend fun unlockPartition(busid: String, devicePath: String, passphrase: String) {
        val s = _sessions.value[busid]
        if (s == null || s.phase != Phase.READY) throw UsbVmException("No USB-drive VM is ready to unlock a partition on for $busid.")
        try {
            val session = qemuManager.unlockPartition(busid, devicePath, passphrase)
            updateStatus(busid) { it.copy(mounts = session.mounts, locked = session.locked) }
        } catch (e: Exception) {
            throw UsbVmException(e.message ?: "Unlock failed")
        }
    }

    /**
     * Re-open the VM for an already-saved "USB: …" bookmark whose VM has
     * stopped — called by [UsbDriveConnectionPreflight] just before the
     * profile is dialed. Suspends until the drive is mounted + sshd answers
     * (or throws); the caller's own connect flow supplies the "connecting…"
     * UI, so this has no separate progress surface beyond [sessions] (also
     * visible via MCP `list_usb_drives`).
     *
     * Returns the profile with its `port`/`keyId` refreshed to the new VM
     * session (the ephemeral key is single-boot-scoped — a fresh one is
     * minted on every reopen). Caller is responsible for saving nothing
     * further; [bootAndAttach] already persisted the update.
     */
    suspend fun reopenForProfile(profile: ConnectionProfile, deviceName: String): ConnectionProfile {
        val busid = busidOf(deviceName)
        if (_sessions.value[busid]?.phase == Phase.OPENING) {
            throw UsbVmException("This USB drive is already booting; wait for it to finish.")
        }
        val info = try {
            usbBroker.openDevice(deviceName)
        } catch (e: Exception) {
            throw UsbVmException("USB open failed: ${e.message}")
        }
        // Reopening a bookmark always comes back read-only — writable is an
        // explicit per-open choice (the "Open USB drive (writable)" action),
        // not something remembered on the bookmark.
        _sessions.update { it + (busid to Status(Phase.OPENING, deviceName, info.productName, busid, stage = "Reopening the drive…")) }
        startKeepAlive(deviceName, busid)
        return try {
            bootAndAttach(deviceName, info, busid, existingProfile = profile, readOnly = true).first
        } catch (e: Exception) {
            stopKeepAlive(busid)
            updateStatus(busid) { Status(Phase.ERROR, deviceName, info.productName, busid, error = e.message) }
            throw UsbVmException("Couldn't reopen the USB drive VM: ${e.message}")
        }
    }

    /**
     * Keep the OTG-attached drive from being power-suspended by Android while
     * it's open for this session — a trivial, side-effect-free `GET_STATUS`
     * control transfer every [KEEP_ALIVE_INTERVAL_MS], purely to keep bus
     * traffic flowing. Some OEM builds (this was found on OxygenOS) suspend
     * an idle host-mode USB device more aggressively than stock Linux
     * autosuspend, and a suspended stick fails every later USB/IP enumeration
     * attempt until physically replugged — the long TCG boot + setup window
     * has no real traffic otherwise, so it's exactly the idle stretch that
     * triggers this. Best-effort: this can reduce how often it happens, not
     * guarantee it never does (Android gives apps no API to disable OTG
     * autosuspend outright without root).
     */
    private val keepAliveJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

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
                    // Stop poking a wedged/half-dead device every interval — the
                    // transfers keep failing and only add contention.
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
     * The shared VM-boot core for both a fresh "Open USB drive…" tap
     * ([existingProfile] null → a new bookmark is created, tagged with the
     * drive's serial) and reopening a saved bookmark ([existingProfile] set →
     * its port/key are refreshed in place, preserving the row's id so every
     * other reference to it — Files tabs, workspaces — keeps working). Only
     * the freshly-minted ephemeral key is cleaned up on failure; an
     * [existingProfile]'s prior (still relevant until we succeed) key is left
     * alone until the new one is confirmed working.
     */
    private suspend fun bootAndAttach(
        deviceName: String,
        info: UsbDeviceInfo,
        busid: String,
        existingProfile: ConnectionProfile?,
        readOnly: Boolean,
    ): Pair<ConnectionProfile, List<String>> {
        qemuManager.ensureProvisionedAppliance { stage(busid, it) }
        stage(busid, "Sharing the drive with the VM…")
        var keyId: String? = null
        try {
            // Export alongside any other open drive's export on the same
            // shared UsbIpServer (#287 multi-drive) rather than replacing it.
            usbIpServer.export(deviceName)
            val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, "Haven USB drive")
            val keyEntity = SshKey(
                label = SshKey.USB_DRIVE_VM_LABEL,
                keyType = key.type.sshName,
                privateKeyBytes = key.privateKeyBytes,
                publicKeyOpenSsh = key.publicKeyOpenSsh,
                fingerprintSha256 = key.fingerprintSha256,
                // Only valid for the VM's loopback root account, and the drive
                // profile references it by id — never offer it in the
                // try-every-key pool. Stale ones accumulate (one per drive
                // bookmark) and each is a wasted auth attempt: five of them
                // hit sshd's MaxAuthTries before the user's real key is tried.
                enabledForAuth = false,
            )
            sshKeyRepository.save(keyEntity); keyId = keyEntity.id

            val session = qemuManager.openDrive(busid, key.publicKeyOpenSsh, readOnly = readOnly, onStage = { stage(busid, it) })

            val oldKeyId = existingProfile?.keyId
            val profile = existingProfile?.copy(
                host = "127.0.0.1", port = session.sshPort, username = "root",
                authType = ConnectionProfile.AuthType.KEY, keyId = keyEntity.id,
            ) ?: ConnectionProfile(
                label = driveLabel(info, readOnly),
                host = "127.0.0.1",
                port = session.sshPort,
                username = "root",
                authType = ConnectionProfile.AuthType.KEY,
                keyId = keyEntity.id,
                connectionType = "SSH",
                usbDriveSerial = info.serialNumber,
            )
            connectionRepository.save(profile) // upsert — works for both new and existing ids
            if (oldKeyId != null && oldKeyId != keyEntity.id) {
                runCatching { sshKeyRepository.delete(oldKeyId) }
            }

            updateStatus(busid) {
                Status(
                    phase = Phase.READY, deviceName = deviceName, productName = info.productName,
                    busid = busid, profileId = profile.id, keyId = keyEntity.id,
                    mounts = session.mounts, sshPort = session.sshPort,
                    readOnly = session.readOnly, locked = session.locked,
                )
            }
            Log.i(TAG, "USB drive VM ready: $deviceName → profile ${profile.id}, mounts ${session.mounts}")
            return profile to session.mounts
        } catch (e: Exception) {
            runCatching { qemuManager.closeDrive(busid) }
            runCatching { usbIpServer.unexport(busid) }
            // Only the fresh key we just minted — an existingProfile's prior
            // key is still whatever it was before this attempt, untouched.
            keyId?.let { runCatching { sshKeyRepository.delete(it) } }
            throw e
        }
    }

    /**
     * Surface the drive into the Files browser. Two steps, because the file
     * browser opens an SFTP *channel* on an already-connected SSH session —
     * it never dials one (SshSessionManager.openSftpSession only returns a
     * CONNECTED session). So:
     *  1. ConnectProfile — establish the SSH session via the same path a
     *     Connections tap uses (route-through/auth all apply). This also
     *     gives the "terminal into the VM for free".
     *  2. once CONNECTED, NavigateToSftpPath — switch to Files, open the
     *     SFTP channel on that session, and land on the mount.
     * Without (1), NavigateToSftpPath lands on Files but the listing fails
     * with "Not connected". Only used for the explicit "Open USB drive…" tap
     * — reopening a bookmark via [reopenForProfile] lets the caller's own
     * connect flow (a normal Terminal-tab SSH connect) drive the UI instead.
     */
    private suspend fun autoOpenInFiles(profile: ConnectionProfile, mounts: List<String>, busid: String) {
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profile.id),
        )
        // Ceiling only — we navigate the instant CONNECTED arrives. Generous
        // so a slow VM's SSH handshake still lands the auto-open; if it does
        // time out the drive is still connected + shows in Files (this only
        // gates the convenience navigation).
        val connected = withTimeoutOrNull(90_000) {
            sshSessionManager.sessions.first { m ->
                m.values.any {
                    it.profileId == profile.id &&
                        it.status == sh.haven.core.ssh.SshSessionManager.SessionState.Status.CONNECTED
                }
            }
        }
        if (connected != null) {
            // ConnectProfile switches the pager to the new VM terminal tab
            // as the session connects. Wait a beat so the Files navigation
            // below lands last and wins, instead of being overridden back
            // to Terminal. ponytail: a small settle delay beats threading a
            // "don't-switch-to-terminal" flag through the whole connect path.
            kotlinx.coroutines.delay(1500)
            // Re-check for this busid's own mount landing late (see
            // awaitMountsScoped's doc comment for why this reads
            // QemuManager's busid-scoped state rather than a live,
            // VM-wide SSH query). Update the status and land the auto-open
            // on the actual mount.
            val liveMounts = awaitMountsScoped(busid)
            if (liveMounts.isNotEmpty() && liveMounts != mounts) {
                updateStatus(busid) { it.copy(mounts = liveMounts) }
            }
            val target = liveMounts.singleOrNull() ?: mounts.singleOrNull() ?: "/mnt"
            agentUiCommandBus.emit(
                sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath(profile.id, target),
            )
        }
    }

    /**
     * Tear down [busid]'s drive: detach + unmount it and revoke its key on
     * the shared VM, stop its export. The shared VM itself only actually
     * powers off once the *last* attached drive is closed — see
     * [QemuManager.closeDrive]. The bookmarked "USB: …" connection (and its
     * now-stale key/port) is kept — clicking it again re-opens/reattaches via
     * [reopenForProfile] and refreshes both. A dead profile between eject and
     * the next click is the point of the bookmark.
     */
    suspend fun close(busid: String) {
        val s = _sessions.value[busid] ?: return
        if (s.phase == Phase.IDLE) return
        stopKeepAlive(busid)
        runCatching { qemuManager.closeDrive(busid) }
        runCatching { usbIpServer.unexport(busid) }
        _sessions.update { it - busid }
    }

    /** Close every open drive (used before [deleteAppliance], which all of them depend on). */
    suspend fun closeAll() {
        _sessions.value.keys.toList().forEach { close(it) }
    }

    /** True once the persistent USB-helper appliance has been provisioned. */
    val applianceProvisioned: Boolean get() = qemuManager.isApplianceProvisioned

    /**
     * Delete the persistent USB-helper appliance (the installed Alpine that
     * mounts drives). The next [open] re-provisions it (one-time, slow again).
     * Closes every live VM first (they all depend on this one disk).
     */
    suspend fun deleteAppliance() {
        runCatching { closeAll() }
        qemuManager.deleteAppliance()
    }

    /**
     * Wait briefly for this busid's mount to land in QemuManager's own
     * (authoritative, per-busid) [QemuManager.DriveSession.mounts], in case
     * it arrives just after [bootAndAttach] already captured its snapshot
     * (slow, retried enumeration).
     *
     * This used to instead run `cat /proc/mounts` over the drive's own SSH
     * session — reliable on its own, but **not busid-scoped**: once multiple
     * drives can share one VM (#287 multi-drive), that command reads the
     * WHOLE shared VM's mount table, not just this busid's own. Reproduced
     * live: unlocking one drive's LUKS partition stamped its mount path into
     * a completely different, unrelated drive's status, because this
     * function's un-scoped "live" re-query overwrote the correctly-scoped
     * value [bootAndAttach] had already recorded. QemuManager's own
     * [QemuManager.sessions] is already busid-scoped (and, after the
     * shared-VM rework, its attach report is itself delta-scoped and
     * verified rather than a blind rescan), so reading it directly is both
     * simpler and correct where the SSH re-query wasn't.
     */
    private suspend fun awaitMountsScoped(busid: String): List<String> {
        repeat(20) { attempt ->
            val mounts = qemuManager.sessions.value[busid]?.mounts.orEmpty()
            if (mounts.isNotEmpty()) { Log.i(TAG, "awaitMountsScoped($busid): $mounts (attempt $attempt)"); return mounts }
            kotlinx.coroutines.delay(2000)
        }
        Log.w(TAG, "awaitMountsScoped($busid): no /mnt mount appeared within ~40s")
        return emptyList()
    }

    /** Attached mass-storage device whose serial matches [serial], if any (bookmark re-open lookup). */
    fun findAttachedBySerial(serial: String): UsbDeviceInfo? =
        massStorageDevices().firstOrNull { it.serialNumber == serial }

    /**
     * The Android-side (vold) mount for [deviceName], if one can be correlated
     * with it — the pre-boot knowledge the #603 mount-route picker needs.
     * Enumeration only: [resolveDrive] never opens the USB device or prompts
     * for permission (vold mounts the drive with or without our permission).
     * Returns null when Android hasn't mounted it (ext4/GPT/LUKS, or no
     * matchable volume) — the caller then has no cheaper route than the VM.
     */
    suspend fun androidMount(deviceName: String?, timeoutMs: Long = 3_000): UsbMountMatch? {
        val target = resolveDrive(deviceName)
        return usbMountCorrelator.awaitMatch(target, massStorageDevices().size, timeoutMs)
    }

    /** No-wait [androidMount] variant for MCP snapshots (null, not a throw, when the target is ambiguous). */
    fun androidMountSnapshot(deviceName: String?): UsbMountMatch? {
        val target = if (!deviceName.isNullOrBlank()) deviceName else massStorageDevices().singleOrNull()?.deviceName ?: return null
        return usbMountCorrelator.currentMatch(target, massStorageDevices().size)
    }

    /** The session (if any) currently tied to [profileId] — used by [UsbDriveConnectionPreflight]. */
    fun sessionForProfile(profileId: String): Status? = _sessions.value.values.firstOrNull { it.profileId == profileId }

    private fun resolveDrive(deviceName: String?): String {
        if (!deviceName.isNullOrBlank()) return deviceName
        val drives = massStorageDevices()
        return when (drives.size) {
            0 -> throw UsbVmException("No USB mass-storage drive attached.")
            1 -> drives.single().deviceName
            else -> throw UsbVmException(
                "Multiple USB drives attached — pass deviceName. Found: ${drives.joinToString { it.deviceName }}",
            )
        }
    }

    private fun driveLabel(info: UsbDeviceInfo, readOnly: Boolean): String {
        val base = info.productName?.takeIf { it.isNotBlank() }?.let { "USB: $it" } ?: "USB drive"
        return if (readOnly) base else "$base (writable)"
    }

    // /dev/bus/usb/BBB/DDD → "B-D" (matches usbip + start_usbip_export).
    private fun busidOf(deviceName: String): String {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 1
        return "$bus-$dev"
    }

    companion object {
        private const val TAG = "UsbDriveVmManager"
        const val USB_CLASS_MASS_STORAGE = 8
        // GET_STATUS(device): bmRequestType=IN|Standard|Device, bRequest=0.
        private const val GET_STATUS_REQUEST_TYPE = 0x80
        private const val GET_STATUS_REQUEST = 0x00
        // OTG autosuspend timeouts are typically a few seconds of idle; keep
        // comfortably under that without hammering the bus.
        private const val KEEP_ALIVE_INTERVAL_MS = 5_000L
        // Stop the keepalive after this many consecutive failed pokes so a
        // wedged/half-dead device isn't polled forever.
        private const val KEEP_ALIVE_MAX_FAILURES = 3
    }
}
