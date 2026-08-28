package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.local.proot.PackageOps
import sh.haven.core.security.posixShellQuote
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Runs an on-device QEMU VM that gives phone-attached USB drives a **real
 * Linux kernel** — so mass-storage / block / ext4 / GPT, which proot has no
 * kernel for, work (#287). The VM is the USB/IP *client* the proot guest
 * can't be (Android ships no vhci-hcd): it imports the device(s) the shipped
 * [sh.haven.core.usb.UsbIpServer] exports, mounts them, and serves the files
 * over sshd. Haven points an ordinary loopback SSH/SFTP profile at it — no
 * new transport, no new UI; each drive's files appear in the normal file
 * browser.
 *
 * **One shared VM serves every open drive**, not one VM per drive. QEMU's
 * own image locking refuses a second process opening the same appliance
 * disk file (`Failed to get "write" lock` — confirmed live), and a second
 * full VM boot per drive would double RAM/boot-time for no reason: USB/IP
 * already supports attaching multiple devices to one guest (each
 * `usbip attach` opens its own connection to [sh.haven.core.usb.UsbIpServer],
 * which already dispatches by busid over one shared socket), so an
 * additional drive is just another attach+mount inside the already-running
 * guest. Up to [MAX_CONCURRENT_DRIVES] drives can be attached at once.
 *
 * Delivery = **serial-console auto-drive** (not an Alpine apkovl, which didn't
 * auto-apply from a separate disk reliably): qemu runs `-serial stdio`, so the
 * proot launcher Process's streams *are* the VM serial. We wait for `login:`,
 * send `root`, then a one-shot bootstrap script (network → usbip+openssh →
 * sshd), then a per-drive attach+mount script for each busid. This mirrors
 * what was proven by hand in the #287 spike.
 *
 * Unrooted Android = no /dev/kvm, so qemu runs TCG (slow but correct) — fine
 * for pulling files off a drive. Isochronous USB still can't pass (the broker
 * has no isochronous API).
 *
 * Orchestration note: the export (UsbIpServer) lives in `core:usb`, which this
 * module doesn't depend on, so the app layer starts the export and passes us
 * the `busid`; we only own the VM.
 */
@Singleton
class QemuManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    data class DriveSession(
        val busid: String,
        val sshPort: Int,
        val mounts: List<String>,
        val state: State,
        val error: String? = null,
        val readOnly: Boolean = true,
        /** Partitions found LUKS-encrypted (mount-dir name, e.g. "sdb2") and not yet unlocked. */
        val locked: List<String> = emptyList(),
    )

    /**
     * The one VM currently serving every open drive. Read/written ONLY under
     * [vmMutex] — a plain nullable var (not a concurrent collection) is
     * correct and sufficient because the mutex is the sole source of the
     * memory-visibility guarantee; a lock-free structure here would just
     * signal a safety the code doesn't actually have.
     */
    private data class SharedVm(
        val instance: VmInstance,
        val port: Int,
        val attachedBusids: MutableSet<String> = mutableSetOf(),
        /** busid -> vhci_hcd port index, captured at attach time so close() detaches the right one, not a guess. */
        val vhciPortByBusid: MutableMap<String, Int> = mutableMapOf(),
    )
    private var sharedVm: SharedVm? = null

    /**
     * Guards [sharedVm] AND every byte written to its serial console.
     * [VmInstance.send] has no synchronization of its own, so two concurrent
     * command sequences (e.g. [openDrive] attaching a new drive while
     * [unlockPartition] is mid-passphrase on another) would otherwise
     * interleave writes on the same process stdin and corrupt both.
     * [openDrive]/[closeDrive]/[unlockPartition]/[closeAllDrives] each hold
     * this for their *entire* duration, not just a boot decision —
     * `Mutex.withLock` suspends the coroutine rather than blocking a thread,
     * so a second caller simply queues behind the first's multi-minute boot
     * without stalling the app. NOT reentrant: every public function here is
     * a thin `vmMutex.withLock { xLocked(...) }` wrapper, and nothing under
     * an "xLocked" helper may call back into a public lock-taking function.
     */
    private val vmMutex = Mutex()

    private val _sessions = MutableStateFlow<Map<String, DriveSession>>(emptyMap())
    val sessions: StateFlow<Map<String, DriveSession>> = _sessions.asStateFlow()

    private fun updateSession(busid: String, transform: (DriveSession?) -> DriveSession) {
        _sessions.update { it + (busid to transform(it[busid])) }
    }

    // Provisioning/upgrading the shared appliance disk is a maintenance
    // operation on ONE file, unrelated to any particular busid — its own
    // dedicated VmInstance, serialized by [provisioningMutex]. Always
    // acquired from inside [vmMutex] (openDrive -> ensureProvisionedAppliance),
    // never the other way around, anywhere in this class — no lock-ordering
    // hazard. A version-mismatch upgrade can only be observed by a fresh
    // process (the marker is on-disk, compile-time version is in the APK),
    // and [sharedVm] is in-memory-only, so an upgrade boot and an
    // already-running shared drive VM can never coexist.
    private val provisioning = VmInstance()
    private val provisioningMutex = Mutex()

    /**
     * Attach [busid] to the shared VM (booting it first if this is the first
     * drive), mount its partitions (read-only unless [readOnly] is false),
     * and authorize [authorizedPubKey] for it. Returns the loopback ssh port
     * (shared across every open drive) + the mounted paths (any
     * LUKS-encrypted partition is left unmounted and reported in
     * [DriveSession.locked] instead — see [unlockPartition]). Suspends
     * through the (TCG) boot on the first drive + one-time package install;
     * throws on failure (caller stops the export). Up to
     * [MAX_CONCURRENT_DRIVES] drives can be attached at once.
     */
    suspend fun openDrive(
        busid: String,
        authorizedPubKey: String,
        readOnly: Boolean = true,
        onStage: (String) -> Unit = {},
    ): DriveSession = withContext(Dispatchers.IO) {
        onStage("Waiting for the shared VM…")
        vmMutex.withLock { openDriveLocked(busid, authorizedPubKey, readOnly, onStage) }
    }

    private suspend fun openDriveLocked(
        busid: String,
        authorizedPubKey: String,
        readOnly: Boolean,
        onStage: (String) -> Unit,
    ): DriveSession {
        check(sharedVm?.attachedBusids?.contains(busid) != true) { "A USB-drive VM for $busid is already running; close it first." }
        check((sharedVm?.attachedBusids?.size ?: 0) < MAX_CONCURRENT_DRIVES) {
            "Already have $MAX_CONCURRENT_DRIVES USB drive(s) attached — that's the concurrency limit. Close one first."
        }
        updateSession(busid) { DriveSession(busid, 0, emptyList(), State.STARTING, readOnly = readOnly) }
        return try {
            val disk = ensureProvisionedAppliance(onStage)

            var vm = sharedVm
            if (vm == null || !vm.instance.isAlive) {
                vm = bootSharedVm(disk, authorizedPubKey, busid, onStage)
                sharedVm = vm
            } else {
                // VM already up — just authorize this drive's own key on it.
                // Tagged with the busid so closeDrive can revoke precisely
                // this line later without disturbing other open drives' keys.
                onStage("Adding your key…")
                val marker = vm.instance.sendAndAwaitRegex(
                    "echo '$authorizedPubKey haven-usb:$busid' >> /root/.ssh/authorized_keys; echo HAVEN_KEY_OK",
                    "HAVEN_KEY_OK",
                    KEY_ADD_TIMEOUT_MS,
                )
                if (marker == null) fail("Couldn't authorize your key against the running VM.")
            }

            onStage("Attaching and mounting your drive…")
            val since = vm.instance.bufferSnapshot().length
            val marker = vm.instance.sendAndAwaitRegex(
                attachAndMountScript(busid, readOnly),
                "HAVEN_ATTACH_OK|HAVEN_ATTACH_FAIL",
                SETUP_TIMEOUT_MS,
            ) { sec -> onStage("Attaching and mounting your drive (${sec}s)…") }
            if (marker == null || marker == "HAVEN_ATTACH_FAIL") fail("Couldn't attach/mount $busid within ${SETUP_TIMEOUT_MS / 1000}s.")
            val mounts = vm.instance.parseMounts(since)
            val locked = vm.instance.parseLocked(since)
            val vhciPort = vm.instance.parseVhciPort(since)

            vm.attachedBusids.add(busid)
            if (vhciPort != null) vm.vhciPortByBusid[busid] = vhciPort

            DriveSession(busid, vm.port, mounts, State.RUNNING, readOnly = readOnly, locked = locked)
                .also { session -> updateSession(busid) { session } }
        } catch (e: Exception) {
            Log.w(TAG, "openDrive($busid) failed: ${e.message}")
            updateSession(busid) { DriveSession(busid, 0, emptyList(), State.ERROR, e.message, readOnly = readOnly) }
            sharedVm?.attachedBusids?.remove(busid)
            if (sharedVm?.attachedBusids?.isEmpty() != false) {
                sharedVm?.instance?.stop()
                sharedVm = null
            }
            throw e
        }
    }

    /** Boots a fresh shared VM: login, bootstrap script (network/keys/sshd), confirm sshd answers. */
    private fun bootSharedVm(disk: String, firstPubKey: String, firstBusid: String, onStage: (String) -> Unit): SharedVm {
        val instance = VmInstance()
        val port = freeLoopbackPort()

        onStage("Starting the virtual machine…")
        instance.start(qemuRuntimeCommand(disk, port)) { prootManager.startCommandInProot(it) }

        onStage("Booting the USB helper…")
        val bootOk = instance.awaitMarker("login:", BOOT_TIMEOUT_MS, nudge = true) { sec ->
            val hint = instance.bufferSnapshot().let { s ->
                when {
                    s.contains("Welcome to Alpine") || s.contains("Alpine Linux") -> "kernel up, reaching login"
                    s.contains("SeaBIOS") || s.contains("ISOLINUX") -> "loading the kernel"
                    else -> "starting"
                }
            }
            onStage("Booting Linux — $hint (${sec}s)…")
        }
        if (!bootOk) {
            fail(
                instance.describeWaitFailure("The USB-drive VM never reached a login prompt") +
                    "\n\nIf it simply ran out of time, the emulated boot is slow and varies with phone " +
                    "load — try again with less else running.",
            )
        }
        instance.send("root\n")
        Thread.sleep(1500)
        // The tty echoes typed input back into the same stream awaitMarker/
        // sendAndAwaitRegex scan — since every script we send literally
        // contains its own marker text (e.g. "echo HAVEN_BOOTSTRAP_OK"), the
        // echo alone can satisfy the marker wait before the command has
        // even run. Kill echo once, right after login, for every script
        // sent for the rest of this VM's life (bootstrap, every attach,
        // every unlock, close).
        instance.send("stty -echo\n")
        Thread.sleep(300)

        onStage("Setting up the VM…")
        val setupMarker = instance.sendAndAwaitRegex(
            vmBootstrapScript(firstPubKey, busidComment = firstBusid),
            BOOTSTRAP_MARKER_REGEX,
            SETUP_TIMEOUT_MS,
        )
        if (setupMarker == null || setupMarker != "HAVEN_BOOTSTRAP_OK") {
            // #506: say which stage failed. The script now checks the lease and
            // sshd itself, so a named FAIL is the guest's own diagnosis; a
            // null (timeout) still gets the serial tail.
            val reason = setupMarker?.removePrefix("HAVEN_BOOTSTRAP_FAIL:").orEmpty()
            if (reason.isNotEmpty()) fail(bootstrapFailureMessage(reason))
            fail(instance.describeWaitFailure("VM setup (network/sshd) did not finish"))
        }

        onStage("Almost ready — connecting…")
        // sshd is confirmed listening inside the guest by the bootstrap gate,
        // so a failure here is the host<->guest port forward not relaying —
        // say that, not just the port (#506: "sshd never answered on
        // 127.0.0.1:<random port>" gave the reader nothing to act on).
        if (!awaitSshBanner(port, SSH_TIMEOUT_MS)) {
            fail(
                "The VM's SSH server is running but its port forward on the phone did not relay " +
                    "to it within ${SSH_TIMEOUT_MS / 1000}s. Try again; if it keeps happening, a reboot " +
                    "of the phone clears stale virtual-network state.",
            )
        }

        return SharedVm(instance, port)
    }

    /**
     * Unlock a LUKS-encrypted partition reported in [DriveSession.locked] for
     * [busid]'s drive and mount it, against the *already-running* shared VM
     * (no reboot — this is a follow-up command over the same serial
     * session). Returns the updated session; throws (wrong passphrase, or
     * the VM isn't running) without altering [sessions] so the caller can
     * re-prompt.
     */
    suspend fun unlockPartition(busid: String, devicePath: String, passphrase: String): DriveSession =
        withContext(Dispatchers.IO) { vmMutex.withLock { unlockPartitionLocked(busid, devicePath, passphrase) } }

    private fun unlockPartitionLocked(busid: String, devicePath: String, passphrase: String): DriveSession {
        val vm = sharedVm ?: fail("No USB-drive VM is running for $busid.")
        val current = _sessions.value[busid] ?: fail("No USB-drive VM is running for $busid.")
        check(busid in vm.attachedBusids && vm.instance.isAlive) { "The USB-drive VM for $busid isn't running." }
        val name = File(devicePath).name
        if (name !in current.locked) fail("$name isn't a locked partition on this drive.")
        // Passphrase piped via stdin (-d -), never as a cryptsetup argument (which
        // would leak it into `ps`); it does still transit the serial console into
        // the in-memory (never persisted) serial buffer, same trust model as
        // the ephemeral SSH key already sent the same way.
        // Mount matches the session's readOnly (the same rw+sync risk tradeoff
        // as the plain mount loop in attachAndMountScript applies here too).
        val mapperMount = if (current.readOnly) {
            // Same non-ext4/xfs noload gotcha as attachAndMountScript's mount loop.
            "t=\$(blkid -o value -s TYPE \"/dev/mapper/crypt_$name\" 2>/dev/null); " +
                "mount -o ro \"/dev/mapper/crypt_$name\" \"/mnt/$name\" 2>/dev/null || " +
                "if [ \"\$t\" = ext4 ] || [ \"\$t\" = xfs ]; then mount -o ro,noload \"/dev/mapper/crypt_$name\" \"/mnt/$name\" 2>/dev/null; fi"
        } else {
            "mount -o rw,sync \"/dev/mapper/crypt_$name\" \"/mnt/$name\" 2>/dev/null"
        }
        val script = "modprobe dm_crypt 2>/dev/null; " +
            "printf '%s' " + posixShellQuote(passphrase) + " | cryptsetup luksOpen \"$devicePath\" \"crypt_$name\" -d - 2>/dev/null; " +
            "mkdir -p \"/mnt/$name\"; { $mapperMount; }; " +
            // The real, final check — not the `&&` chain's short-circuit state,
            // which a stale/echoed marker match could otherwise satisfy without
            // ever confirming the mount actually happened.
            "mountpoint -q \"/mnt/$name\" 2>/dev/null && echo HAVEN_UNLOCK_OK || echo HAVEN_UNLOCK_FAIL"
        val marker = vm.instance.sendAndAwaitRegex(script, "HAVEN_UNLOCK_OK|HAVEN_UNLOCK_FAIL", UNLOCK_TIMEOUT_MS)
        if (marker == null || marker == "HAVEN_UNLOCK_FAIL") fail("Wrong passphrase, or the partition failed to mount.")
        // Deterministic merge instead of a shell-side rescan: HAVEN_UNLOCK_OK
        // already proves (via mountpoint -q) that exactly "$name" is now
        // mounted — update just that one partition's status against this
        // drive's own already-known lists, rather than re-deriving via a
        // fresh report (which, scoped to only this one partition, could
        // otherwise incorrectly forget this drive's OTHER still-locked
        // partitions, if it has more than one).
        return current.copy(mounts = current.mounts + "/mnt/$name", locked = current.locked - name)
            .also { session -> updateSession(busid) { session } }
    }

    /**
     * Detach + unmount [busid] from the shared VM and stop the export.
     * Idempotent — a no-op if [busid] was never actually attached (its
     * [openDrive] never completed, or it's already closed). Only powers off
     * the shared VM once the *last* attached drive is closed; otherwise it
     * stays up serving the rest.
     *
     * Explicitly `sync`s and unmounts this busid's mounts before detaching,
     * and waits (bounded) for confirmation, rather than trusting a shared
     * poweroff to finish before the force-kill ceiling. This matters most
     * for a writable mount: racing a force-kill against an in-flight write
     * is exactly how you corrupt a filesystem. Best-effort — a hung VM just
     * times out and falls through.
     */
    suspend fun closeDrive(busid: String): Unit = withContext(Dispatchers.IO) { vmMutex.withLock { closeDriveLocked(busid) } }

    private fun closeDriveLocked(busid: String) {
        val vm = sharedVm ?: return
        if (busid !in vm.attachedBusids) return
        if (vm.instance.isAlive) {
            val mounts = _sessions.value[busid]?.mounts ?: emptyList()
            val vhciPort = vm.vhciPortByBusid[busid]
            val umounts = mounts.joinToString("") { "mountpoint -q \"$it\" 2>/dev/null && umount \"$it\" 2>/dev/null; " }
            val detach = if (vhciPort != null) "usbip detach -p $vhciPort 2>/dev/null; " else ""
            val script = "sync; $umounts$detach" +
                // Strip only this busid's own key line — other open drives'
                // keys (and their still-live sessions) are untouched.
                "sed -i \"/ haven-usb:$busid\$/d\" /root/.ssh/authorized_keys 2>/dev/null; " +
                "sync; echo HAVEN_CLOSE_DONE"
            vm.instance.sendAndAwaitRegex(script, "HAVEN_CLOSE_DONE", UNMOUNT_TIMEOUT_MS)
        }
        vm.attachedBusids.remove(busid)
        vm.vhciPortByBusid.remove(busid)
        _sessions.update { it - busid }

        if (vm.attachedBusids.isEmpty()) {
            runCatching { vm.instance.send("\npoweroff\n") }
            vm.instance.waitFor(4)
            vm.instance.stop()
            sharedVm = null
        }
    }

    /** Close every currently-attached drive (used before [deleteAppliance], which all of them depend on). */
    suspend fun closeAllDrives(): Unit = withContext(Dispatchers.IO) {
        vmMutex.withLock {
            sharedVm?.attachedBusids?.toList()?.forEach { closeDriveLocked(it) }
        }
    }

    // --- appliance provisioning -------------------------------------------

    // Provisioning boot: ISO + the blank appliance disk (virtio = /dev/vda).
    // No hostfwd — provisioning is serial-only (install Alpine to /dev/vda).
    private fun qemuProvisionCommand(isoGuestPath: String, diskGuestPath: String): String =
        "exec qemu-system-x86_64 -M pc -m $VM_MEM_MB -display none -monitor none " +
            "-serial stdio -no-reboot -boot d -cdrom $isoGuestPath " +
            "-drive file=$diskGuestPath,if=virtio,format=raw " +
            "-netdev user,id=n0 -device virtio-net-pci,netdev=n0"

    // Runtime boot: the provisioned appliance disk ONLY (no ISO, no re-install).
    // -boot c boots the installed system from /dev/vda; hostfwd exposes its sshd.
    private fun qemuRuntimeCommand(diskGuestPath: String, port: Int): String =
        // exec → the launcher process *is* qemu (clean to signal). Serial on
        // stdio = our Process streams; headless; -no-reboot so poweroff exits.
        "exec qemu-system-x86_64 -M pc -m $VM_MEM_MB -display none -monitor none " +
            "-serial stdio -no-reboot -boot c " +
            "-drive file=$diskGuestPath,if=virtio,format=raw " +
            "-netdev user,id=n0,hostfwd=tcp:127.0.0.1:$port-:22 -device virtio-net-pci,netdev=n0"

    private suspend fun ensureQemu(onStage: (String) -> Unit = {}) {
        onStage("Checking the VM engine…")
        val (out, _) = prootManager.runCommandInProot("command -v qemu-system-x86_64 || true")
        if (out.contains("qemu-system-x86_64")) return
        val family = prootManager.activeDistro.family
        val pkg = qemuPackageFor(family)
        val ops = PackageOps.forFamily(family)
        onStage("Installing the VM engine (one-time)…")
        Log.i(TAG, "installing $pkg (${family})")
        val (instOut, code) = prootManager.runCommandInProot("${ops.updateCmd()} >/dev/null 2>&1 ; ${ops.installCmd(listOf(pkg))} 2>&1")
        val (check, _) = prootManager.runCommandInProot("command -v qemu-system-x86_64 || true")
        if (!check.contains("qemu-system-x86_64")) {
            throw IllegalStateException("Could not install $pkg in ${prootManager.activeDistroId} (exit $code): ${instOut.takeLast(200)}")
        }
    }

    private fun qemuPackageFor(family: PackageFamily): String = when (family) {
        PackageFamily.APK -> "qemu-system-x86_64"
        PackageFamily.APT -> "qemu-system-x86"
        PackageFamily.PACMAN -> "qemu-system-x86"
        PackageFamily.XBPS -> "qemu"
        else -> "qemu"
    }

    /** Download + verify the appliance ISO once into cacheDir (bound at /tmp in proot). */
    private fun ensureAppliance(onStage: (String) -> Unit = {}): String {
        val dir = File(context.cacheDir, "haven-vm").apply { mkdirs() }
        val iso = File(dir, APPLIANCE_NAME)
        val marker = File(dir, "$APPLIANCE_NAME.ok")
        if (iso.exists() && marker.exists()) return "/tmp/haven-vm/$APPLIANCE_NAME"
        iso.delete(); marker.delete()
        onStage("Downloading the Linux image (one-time, ~270 MB)…")
        Log.i(TAG, "downloading appliance ISO …")
        val conn = URL(APPLIANCE_URL).openConnection() as java.net.HttpURLConnection
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: APPLIANCE_SIZE
        conn.inputStream.use { input ->
            iso.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var read = 0L; var lastPct = -1
                var n = input.read(buf)
                while (n >= 0) {
                    out.write(buf, 0, n); read += n
                    val pct = ((read * 100) / total).toInt()
                    if (pct >= lastPct + 5) { onStage("Downloading the Linux image (one-time)… $pct%"); lastPct = pct }
                    n = input.read(buf)
                }
            }
        }
        onStage("Verifying the download…")
        val sha = MessageDigest.getInstance("SHA-256").let { md ->
            iso.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16); var n = ins.read(buf)
                while (n >= 0) { md.update(buf, 0, n); n = ins.read(buf) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!sha.equals(APPLIANCE_SHA256, ignoreCase = true)) {
            iso.delete(); throw SecurityException("appliance ISO checksum mismatch: $sha")
        }
        marker.writeText("ok\n")
        return "/tmp/haven-vm/$APPLIANCE_NAME"
    }

    /** True once the persistent appliance disk has been provisioned. */
    val isApplianceProvisioned: Boolean
        get() = File(File(context.cacheDir, "haven-vm"), "$APPLIANCE_DISK.ok").exists()

    /**
     * Provision the persistent appliance disk once — install Alpine + the
     * extra package set to it (via `setup-disk -m sys`) — or return it
     * immediately if already provisioned. After this, every [openDrive] boots
     * the disk directly (no ISO, no apk, no network), so repeat opens are just
     * a kernel boot. The user keeps the appliance until they [deleteAppliance].
     * Serialized by [provisioningMutex] — with multiple drives able to open at
     * once, two callers could otherwise both see "not provisioned" and race
     * the same provisioning boot.
     *
     * The whole sequence (install + serial-console + passwordless-root config)
     * was validated locally under KVM; see scratch/qemu-appliance.
     *
     * Public so callers can provision BEFORE starting the USB/IP export — the
     * one-time provision takes minutes, and holding the export open across it
     * stales the drive (it re-imports at the wrong speed and never enumerates).
     * openDrive() calls this again, but it's a no-op once provisioned.
     */
    suspend fun ensureProvisionedAppliance(onStage: (String) -> Unit): String = provisioningMutex.withLock {
        // The engine check belongs here, not in the caller (#506). It used to
        // live only in openDriveLocked, and UsbDriveVmManager calls this one
        // directly as its documented pre-step — so on a guest without QEMU the
        // provisioning VM was launched anyway and died in a second with
        // `/bin/sh: 1: exec: qemu-system-x86_64: not found`, reported as a
        // 420-second boot timeout. A reporter chased three different flash
        // drives before the message said what actually happened.
        //
        // Before the early returns below, deliberately: an appliance can be
        // provisioned while the guest that provisioned it has since been
        // reinstalled, and the caller boots the VM on that path too.
        ensureQemu(onStage)
        val dir = File(context.cacheDir, "haven-vm").apply { mkdirs() }
        val disk = File(dir, APPLIANCE_DISK)
        val marker = File(dir, "$APPLIANCE_DISK.ok")
        val guestDisk = "/tmp/haven-vm/$APPLIANCE_DISK"
        if (disk.exists() && markerVersion(marker) == APPLIANCE_PROVISION_VERSION) return@withLock guestDisk
        if (disk.exists() && marker.exists()) {
            // Already provisioned, just missing a package added since (e.g.
            // cryptsetup for LUKS) — a much cheaper in-place upgrade boot than
            // a full ISO re-provision.
            upgradeProvisionedAppliance(guestDisk, onStage)
            marker.writeText("$APPLIANCE_PROVISION_VERSION\n")
            return@withLock guestDisk
        }

        // (Re-)provision from scratch. Needs the ISO; drop it again afterwards.
        disk.delete(); marker.delete()
        val iso = ensureAppliance(onStage)
        onStage("Building the USB helper Linux (one-time)…")
        Log.i(TAG, "provisioning appliance disk …")
        // Raw sparse image (truncate is universal — no qemu-img dependency).
        prootManager.runCommandInProot("rm -f $guestDisk && truncate -s $APPLIANCE_DISK_SIZE $guestDisk")
        provisioning.start(qemuProvisionCommand(iso, guestDisk)) { prootManager.startCommandInProot(it) }
        try {
            val bootOk = provisioning.awaitMarker("login:", BOOT_TIMEOUT_MS, nudge = true) { sec ->
                onStage("Building the USB helper Linux — booting (${sec}s)…")
            }
            if (!bootOk) fail(provisioning.describeWaitFailure("Appliance provisioning: the VM never reached a login prompt"))
            provisioning.send("root\n"); Thread.sleep(1500)
            provisioning.send("stty -echo\n"); Thread.sleep(300) // see bootSharedVm's comment
            onStage("Building the USB helper Linux — installing (one-time)…")
            provisioning.send(provisionScript + "\n")
            val ok = provisioning.awaitMarker("HAVEN_PROVISION_DONE", PROVISION_TIMEOUT_MS) { sec ->
                onStage("Building the USB helper Linux — installing (${sec}s)…")
            }
            if (!ok) fail(provisioning.describeWaitFailure("Appliance provisioning did not finish"))
        } finally {
            runCatching { provisioning.send("\npoweroff\n") }
            provisioning.waitFor(6)
            provisioning.stop()
        }
        // setup-disk grows the sparse image well past 20 MB; a tiny file means
        // the install never happened (marker can't gate that — the host file is
        // what we boot next).
        if (!disk.exists() || disk.length() < 20_000_000L) fail("appliance disk wasn't provisioned")
        marker.writeText("$APPLIANCE_PROVISION_VERSION\n")
        // The appliance is self-contained now; reclaim the ~270 MB ISO. Deleting
        // the appliance re-downloads + re-provisions.
        runCatching { File(dir, APPLIANCE_NAME).delete(); File(dir, "$APPLIANCE_NAME.ok").delete() }
        Log.i(TAG, "appliance provisioned (${disk.length() / 1024 / 1024} MB)")
        guestDisk
    }

    /** Delete the persistent appliance; the next [openDrive] re-provisions it. Closes every live drive first (they all depend on this one disk). */
    suspend fun deleteAppliance() {
        closeAllDrives()
        val dir = File(context.cacheDir, "haven-vm")
        val removed = File(dir, APPLIANCE_DISK).delete()
        File(dir, "$APPLIANCE_DISK.ok").delete()
        Log.i(TAG, "deleteAppliance: removed=$removed")
    }

    /** Boot the already-installed appliance disk just to `apk add` packages added since it was provisioned. */
    private fun upgradeProvisionedAppliance(guestDisk: String, onStage: (String) -> Unit) {
        onStage("Updating the USB helper Linux (one-time)…")
        Log.i(TAG, "upgrading appliance package set …")
        provisioning.start(qemuRuntimeCommand(guestDisk, freeLoopbackPort())) { prootManager.startCommandInProot(it) }
        try {
            val bootOk = provisioning.awaitMarker("login:", BOOT_TIMEOUT_MS, nudge = true) { sec ->
                onStage("Updating the USB helper Linux — booting (${sec}s)…")
            }
            if (!bootOk) fail(provisioning.describeWaitFailure("Appliance upgrade: the VM never reached a login prompt"))
            provisioning.send("root\n"); Thread.sleep(1500)
            provisioning.send("stty -echo\n"); Thread.sleep(300) // see bootSharedVm's comment
            onStage("Updating the USB helper Linux (one-time)…")
            // Re-installs the FULL extra-package set (not just what's new since
            // the caller's prior version) — simpler than tracking per-version
            // deltas, and `apk add` on an already-installed package is a cheap
            // no-op, so this costs nothing extra for anyone who's already current
            // on some of them.
            val script = networkUpFragment() +
                "apk update -q && apk add -q $APPLIANCE_EXTRA_PACKAGES && echo HAVEN_UPGRADE_OK || echo HAVEN_UPGRADE_FAIL"
            val marker = provisioning.sendAndAwaitRegex(script, "HAVEN_UPGRADE_OK|HAVEN_UPGRADE_FAIL", PROVISION_TIMEOUT_MS)
            if (marker != "HAVEN_UPGRADE_OK") fail("appliance upgrade (installing $APPLIANCE_EXTRA_PACKAGES) failed or timed out")
        } finally {
            runCatching { provisioning.send("\npoweroff\n") }
            provisioning.waitFor(6)
            provisioning.stop()
        }
    }

    // The one-time install line typed at the ISO's root shell with a blank
    // /dev/vda attached. Validated locally under KVM (scratch/qemu-appliance):
    // installs the live system to disk (sys mode), keeps console=ttyS0 +
    // passwordless serial root, and gates the done-marker on success so a failed
    // setup-disk can't be read as provisioned.
    private val provisionScript: String =
        networkUpFragment() +
            "printf 'https://dl-cdn.alpinelinux.org/alpine/v3.21/main\\n" +
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/community\\n' > /etc/apk/repositories; " +
            "apk update -q && apk add -q linux-tools-usbip openssh e2fsprogs syslinux $APPLIANCE_EXTRA_PACKAGES && " +
            "export ERASE_DISKS=/dev/vda BOOTLOADER=syslinux && " +
            "setup-disk -m sys -s 0 /dev/vda && " +
            "R=\$(blkid | grep /dev/vda | grep -i ext4 | head -1 | cut -d: -f1) && [ -n \"\$R\" ] && " +
            "mount \$R /mnt && chroot /mnt rc-update add sshd default 2>/dev/null; " +
            "if [ -d /mnt/etc ]; then chroot /mnt rc-update add networking default 2>/dev/null; " +
            "grep -q ttyS0 /mnt/etc/inittab || echo 'ttyS0::respawn:/sbin/getty -L 0 ttyS0 vt100' >> /mnt/etc/inittab; " +
            "for f in /mnt/boot/extlinux.conf /mnt/extlinux.conf; do [ -f \"\$f\" ] && " +
            "{ grep -q console=ttyS0 \"\$f\" || sed -i 's|\\(APPEND .*\\)|\\1 console=ttyS0,115200|' \"\$f\"; }; done; " +
            "printf 'auto eth0\\niface eth0 inet dhcp\\n' > /mnt/etc/network/interfaces; " +
            "mkdir -p /mnt/root/.ssh; chmod 700 /mnt/root/.ssh; sync; umount /mnt && echo HAVEN_PROVISION_DONE; fi"

    // --- misc internals ----------------------------------------------------

    private fun freeLoopbackPort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun fail(msg: String): Nothing = throw IllegalStateException(msg)

    private fun awaitSshBanner(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                    s.soTimeout = 2000
                    val b = ByteArray(4)
                    val n = s.getInputStream().read(b)
                    n >= 4 && String(b) == "SSH-"
                }
            }.getOrDefault(false)
            if (ok) return true
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    companion object {
        private const val TAG = "QemuManager"
        private const val VM_MEM_MB = 768
        // The old cap existed for RAM reasons (each VM was its own 768MB
        // machine). That's gone — one shared VM regardless of drive count —
        // so this instead reflects the guest kernel's vhci_hcd port count
        // (typically 8 by default) and practical UX (a phone OTG port, or a
        // small hub), with headroom kept deliberately conservative.
        const val MAX_CONCURRENT_DRIVES = 4
        private const val SERIAL_CAP = 256 * 1024
        // Generous: TCG boot (no KVM) is slow and varies a lot with phone load —
        // 4 min was marginal and timed out under load before reaching login.
        private const val BOOT_TIMEOUT_MS = 420_000L
        private const val SETUP_TIMEOUT_MS = 360_000L
        // How long (s) to wait INSIDE the VM for a drive to enumerate after
        // usbip attach — a ceiling, not a fixed sleep (we mount the instant it
        // appears). Generous so a slow CPU/large drive still makes it; stays
        // within SETUP_TIMEOUT_MS.
        private const val ENUM_WAIT_S = 180
        // One-time: apk download + setup-disk install over TCG can take a while.
        private const val PROVISION_TIMEOUT_MS = 720_000L
        // Persistent installed appliance (raw sparse image; usbip+ssh baked in).
        internal const val APPLIANCE_DISK = "usb_vm_appliance.img"
        private const val APPLIANCE_DISK_SIZE = "2G"
        // Bump when APPLIANCE_EXTRA_PACKAGES changes — an already-provisioned
        // appliance with an older/missing version runs a cheap in-place
        // `apk add` upgrade boot instead of a full ISO re-provision. Markers
        // from before this scheme existed ("ok\n") parse as version 0.
        internal const val APPLIANCE_PROVISION_VERSION = 3
        // cryptsetup = LUKS (unlockPartition). testdisk = partition-table +
        // deleted-file recovery (bundles photorec). gptfdisk = gdisk/sgdisk
        // (GPT repair). parted = MBR/GPT editing. smartmontools = smartctl
        // (drive health/forensic info). ddrescue = imaging a failing drive.
        // ntfs-3g/dosfstools = NTFS/FAT repair (fsck.ext4 already comes from
        // e2fsprogs above). All usable today via the terminal Haven already
        // gives you into the VM — this just makes them available without an
        // ad-hoc `apk add` every session. NOT yet verified on-device that every
        // one of these resolves cleanly from the pinned v3.21 repos.
        private const val APPLIANCE_EXTRA_PACKAGES =
            "cryptsetup testdisk gptfdisk parted smartmontools ddrescue ntfs-3g dosfstools"
        private const val SSH_TIMEOUT_MS = 30_000L
        private const val UNLOCK_TIMEOUT_MS = 30_000L
        private const val KEY_ADD_TIMEOUT_MS = 15_000L
        // Bounded wait for the explicit sync+umount+detach in closeDrive() —
        // generous enough to flush a real pending write, but an eject still
        // has to end.
        private const val UNMOUNT_TIMEOUT_MS = 20_000L
        private const val PROGRESS_TICK_MS = 15_000L
        private const val APPLIANCE_NAME = "alpine-standard-3.21.7-x86_64.iso"
        private const val APPLIANCE_SIZE = 278_921_216L // fallback when no Content-Length
        private const val APPLIANCE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-standard-3.21.7-x86_64.iso"
        // Pinned point release; rotates when Alpine supersedes it (re-pin then).
        private const val APPLIANCE_SHA256 =
            "f1a3a93628927b382d31e7b173b12801342641f711d8c591b88582be1b29954a"
    }
}

/**
 * One QEMU Process's serial console — the shared VM's boot + every attach/
 * unlock/close command sent to it over this VM's lifetime, plus one
 * dedicated instance for appliance provisioning. Same wait/send/reap logic
 * as before the #287 multi-drive-VM-sharing refactor, just now genuinely
 * long-lived (a whole multi-drive session, not one drive's boot+setup).
 */
internal class VmInstance {
    @Volatile var process: Process? = null
        private set
    @Volatile private var readerThread: Thread? = null
    private val serial = StringBuilder()
    private val serialLock = Any()

    val isAlive: Boolean get() = process?.isAlive == true

    /** Start [launch]([command]) and begin draining its serial. */
    fun start(command: String, launch: (String) -> Process) {
        synchronized(serialLock) { serial.setLength(0) }
        val proc = launch(command)
        process = proc
        readerThread = thread(name = "haven-qemu-serial", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    val chunk = CharArray(1024)
                    while (true) {
                        val n = r.read(chunk); if (n < 0) break
                        synchronized(serialLock) {
                            serial.append(chunk, 0, n)
                            if (serial.length > SERIAL_CAP_INTERNAL) serial.delete(0, serial.length - SERIAL_CAP_INTERNAL)
                        }
                    }
                }
            }
        }
    }

    fun send(s: String) {
        runCatching { process?.outputStream?.apply { write(s.toByteArray()); flush() } }
    }

    fun bufferSnapshot(): String = synchronized(serialLock) { serial.toString() }

    /**
     * Why the last [awaitMarker] gave up. Empty until one has.
     *
     * [awaitMarker] returns false for two unrelated things — the VM died, or
     * the deadline passed — and every caller reported the deadline. A
     * GrapheneOS reporter was told their VM "didn't reach a login prompt in
     * 420s" **1.7 seconds** after it started (#506), which sent them looking
     * for a slow boot instead of a crash.
     */
    @Volatile
    var lastWaitFailure: String = ""
        private set

    /**
     * A failure message for [what] that says what actually happened and shows
     * the VM's own last words.
     *
     * The serial buffer held the only evidence of the crash the whole time and
     * was never put in front of anyone.
     */
    fun describeWaitFailure(what: String): String {
        val why = lastWaitFailure.ifEmpty { "it did not complete" }
        val tail = bufferSnapshot().trimEnd().takeLast(SERIAL_TAIL_IN_ERROR)
        return if (tail.isEmpty()) {
            "$what: $why, and the VM produced no output at all"
        } else {
            "$what: $why. Last output from the VM:\n$tail"
        }
    }

    /**
     * [since] restricts parsing to bytes at/after that buffer offset — the
     * report is a fresh, complete re-scan each time it's emitted (see
     * [attachAndMountScript]/[reportScript]), so scanning the whole
     * VM-lifetime buffer would resurrect a partition's now-stale earlier
     * state (e.g. a partition unlocked since an earlier report would still
     * show up as locked forever). NOTE: the buffer is a fixed 256KB ring —
     * an absolute [since] offset can in principle be invalidated by trimming
     * on a very long-lived shared VM (this instance now lives for a whole
     * multi-drive session, not just one drive's boot). Not observed in
     * practice (each capture-and-consume window is short, bounded by that
     * one call's own timeout), but worth knowing if this ever needs revisiting.
     */
    fun parseMounts(since: Int = 0): List<String> {
        val re = Regex("^HVNMOUNT:(/mnt/\\S+)$")
        return bufferSnapshot().drop(since).lineSequence()
            .map { it.trim().trimEnd('\r') }
            .mapNotNull { re.find(it)?.groupValues?.get(1) }
            .distinct().toList()
    }

    fun parseLocked(since: Int = 0): List<String> {
        val re = Regex("^HVNLOCKED:(\\S+)$")
        return bufferSnapshot().drop(since).lineSequence()
            .map { it.trim().trimEnd('\r') }
            .mapNotNull { re.find(it)?.groupValues?.get(1) }
            .distinct().toList()
    }

    /** Parses the `HVNPORT:<n>` line [attachAndMountScript] reports — the vhci_hcd port index this attach was assigned. */
    fun parseVhciPort(since: Int = 0): Int? {
        val re = Regex("^HVNPORT:(\\d+)$")
        return bufferSnapshot().drop(since).lineSequence()
            .map { it.trim().trimEnd('\r') }
            .firstNotNullOfOrNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    fun awaitMarker(
        marker: String,
        timeoutMs: Long,
        nudge: Boolean = false,
        onProgress: ((elapsedSec: Long) -> Unit)? = null,
    ): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var nextTick = start + PROGRESS_TICK_MS_INTERNAL
        fun secsSoFar() = (System.currentTimeMillis() - start) / 1000
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive) {
                lastWaitFailure = "the VM exited after ${secsSoFar()}s"
                return false
            }
            synchronized(serialLock) { if (serial.contains(marker)) return true }
            if (nudge) send("\n")
            val now = System.currentTimeMillis()
            if (onProgress != null && now >= nextTick) {
                onProgress((now - start) / 1000)
                nextTick = now + PROGRESS_TICK_MS_INTERNAL
            }
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                lastWaitFailure = "the wait was interrupted after ${secsSoFar()}s"
                return false
            }
        }
        val found = synchronized(serialLock) { serial.contains(marker) }
        if (!found) lastWaitFailure = "nothing matched in ${timeoutMs / 1000}s"
        return found
    }

    /**
     * Send [script] and wait for [markerRegex] (an alternation like `"A|B"`)
     * to appear in the serial buffer, returning which alternative matched (or
     * null on timeout/VM death). Only bytes written *after* this call's own
     * [send] count — this VM instance is long-lived (boot, every attach,
     * every unlock, close all share it), and a repeat command with the same
     * marker text (e.g. two different drives' attach scripts both using
     * `HAVEN_ATTACH_OK`) would otherwise match a stale occurrence left over
     * from an earlier command instead of its own real output.
     */
    fun sendAndAwaitRegex(
        script: String,
        markerRegex: String,
        timeoutMs: Long,
        onProgress: ((elapsedSec: Long) -> Unit)? = null,
    ): String? {
        val since = synchronized(serialLock) { serial.length }
        send(script + "\n")
        val re = Regex(markerRegex)
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var nextTick = start + PROGRESS_TICK_MS_INTERNAL
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive) return null
            synchronized(serialLock) {
                re.find(serial, since.coerceAtMost(serial.length))?.let { return it.value }
            }
            val now = System.currentTimeMillis()
            if (onProgress != null && now >= nextTick) {
                onProgress((now - start) / 1000)
                nextTick = now + PROGRESS_TICK_MS_INTERNAL
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return null }
        }
        return synchronized(serialLock) { re.find(serial, since.coerceAtMost(serial.length))?.value }
    }

    /** Blocks up to [seconds] for the process to exit; true if it did (or there's none to wait for). */
    fun waitFor(seconds: Long): Boolean = runCatching { process?.waitFor(seconds, TimeUnit.SECONDS) != false }.getOrDefault(true)

    fun stop() {
        val proc = process ?: return
        process = null
        val launcher = pidOf(proc) ?: -1
        val tracees = if (launcher > 0) descendantPids(launcher) else emptyList()
        proc.destroy()
        if (runCatching { !proc.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(true)) proc.destroyForcibly()
        // proot's --kill-on-exit doesn't reap the ptrace tracee when the launcher
        // is force-destroyed; signal the snapshotted qemu tree directly.
        if (tracees.isNotEmpty()) {
            tracees.forEach { runCatching { android.os.Process.sendSignal(it, 15) } }
            runCatching { Thread.sleep(300) }
            tracees.forEach { runCatching { android.os.Process.killProcess(it) } }
            Log.d("QemuManager", "reaped ${tracees.size} qemu tracee(s): $tracees")
        }
        readerThread?.interrupt(); readerThread = null
    }
}

// Duplicated (not in QemuManager.Companion) so VmInstance — a top-level class,
// for clean multi-instance state — doesn't need an outer-class reference.
private const val SERIAL_CAP_INTERNAL = 256 * 1024
private const val PROGRESS_TICK_MS_INTERNAL = 15_000L

/**
 * How much of the VM's serial output to quote in a failure message (#506).
 *
 * Enough for a kernel panic or a qemu abort line and the few lines before it,
 * short enough to belong in an error a user reads rather than a log they
 * attach.
 */
internal const val SERIAL_TAIL_IN_ERROR = 800

// tracee reaping — same approach as GuestServiceManager (proot ptrace).
private fun descendantPids(rootPid: Int): List<Int> {
    val childrenOf = mutableMapOf<Int, MutableList<Int>>()
    val procDirs = File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) } ?: return emptyList()
    for (dir in procDirs) {
        val pid = dir.name.toIntOrNull() ?: continue
        val ppid = readPpid(File(dir, "stat")) ?: continue
        childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
    }
    val out = mutableListOf<Int>()
    val queue = ArrayDeque<Int>().apply { add(rootPid) }
    while (queue.isNotEmpty()) childrenOf[queue.removeFirst()]?.forEach { out.add(it); queue.add(it) }
    return out
}

private fun readPpid(statFile: File): Int? = try {
    val stat = statFile.readText()
    val after = stat.substringAfterLast(')').trim().split(" ")
    after.getOrNull(1)?.toIntOrNull()
} catch (_: Throwable) { null }

private fun pidOf(p: Process): Int? = try {
    val v = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.get(p)
    when (v) { is Int -> v; is Long -> v.toInt(); else -> null }
} catch (_: Throwable) { null }

// How long (s) to wait INSIDE the VM for a drive to enumerate after usbip
// attach — a ceiling, not a fixed sleep (we mount the instant it appears).
private const val ENUM_WAIT_S = 180

/** Marker content is the provisioned package-set version; pre-LUKS markers ("ok\n") parse as 0 → mismatch → upgrade. */
internal fun markerVersion(marker: File): Int = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0

/**
 * `ip link ... ; udhcpc ...` — shared by provisionScript/vmBootstrapScript/the appliance-upgrade script (previously three independently-drifting copies; one had a missing `2>/dev/null`).
 *
 * Retries until an address actually exists (#506): a single `-q -n` attempt
 * misses occasionally on a first boot under TCG, and nothing checked — the
 * failure surfaced 30s later as "sshd never answered", which named a port,
 * not the network.
 */
private fun networkUpFragment(): String =
    "ip link set eth0 up; " +
        "for i in 1 2 3 4 5; do " +
        "udhcpc -i eth0 -q -n 2>/dev/null; " +
        "ip -4 addr show dev eth0 2>/dev/null | grep -q 'inet ' && break; " +
        "sleep 2; " +
        "done; "

/**
 * Runs once, the first time the shared VM boots for a session (not per
 * drive): network, authorize the first drive's key, sshd, and the kernel
 * modules every subsequent attach needs. Top-level (not a QemuManager
 * member) — pure string-building, so it's unit-testable without an Android
 * Context. [busidComment] is unused today (the first key is authorized
 * without a busid tag — see the call site) but kept as a parameter so a
 * future caller can't accidentally authorize an untagged key that
 * [closeDriveLocked]'s sed can't ever revoke; pass null to mean "no tag."
 */
internal fun vmBootstrapScript(pubKey: String, busidComment: String?): String {
    val taggedKey = if (busidComment != null) "$pubKey haven-usb:$busidComment" else pubKey
    // pubKey is a single "ssh-ed25519 AAAA... comment" line (no single quotes).
    return networkUpFragment() +
        // Quiet the kernel console. The appliance boots with console=ttyS0,
        // so vhci_hcd/usb enumeration spam floods the serial we scrape — it
        // can trim a report out of the capped buffer before we read it.
        // Emergency-only from here (we're already past login).
        "dmesg -n 1 2>/dev/null; " +
        "mkdir -p /root/.ssh; echo '$taggedKey' > /root/.ssh/authorized_keys; " +
        "chmod 700 /root/.ssh; chmod 600 /root/.ssh/authorized_keys; " +
        "ssh-keygen -A >/dev/null 2>&1; " +
        "sed -i 's/^#*PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config; " +
        "rc-service sshd restart >/dev/null 2>&1 || /usr/sbin/sshd; " +
        "modprobe vhci_hcd; " +
        // busybox `mount` auto-detect only works for filesystems already in
        // /proc/filesystems — it doesn't modprobe — so load the common ones
        // up front or an ext4 stick mounts as an empty dir.
        "for m in ext4 vfat exfat ntfs3; do modprobe \$m 2>/dev/null; done; " +
        // Gate BOOT_OK on the two facts the next step depends on (#506). The
        // script used to echo BOOT_OK unconditionally, so "setup finished"
        // proved nothing: the failure surfaced 30s later as "sshd never
        // answered on 127.0.0.1:<port>", which named a port but not whether
        // the guest got no address or sshd simply didn't come up. Check the
        // address first: the hostfwd goes through QEMU's userspace network,
        // which needs the guest's IP to relay to, so a leaseless guest fails
        // the banner probe even with sshd listening.
        // busybox `nc` lacks -z; netstat -tln is in the base image and shows
        // the LISTEN line once sshd has bound (sshd forks a moment after the
        // rc-service call returns, hence the bounded wait).
        "listening=0; for i in 1 2 3 4 5 6 7 8 9 10; do netstat -tln 2>/dev/null | grep -q ':22 ' && { listening=1; break; }; sleep 1; done; " +
        "if ! ip -4 addr show dev eth0 2>/dev/null | grep -q 'inet '; then echo HAVEN_BOOTSTRAP_FAIL:no-network; " +
        "elif [ \"\$listening\" = 1 ]; then echo HAVEN_BOOTSTRAP_OK; " +
        "else echo HAVEN_BOOTSTRAP_FAIL:sshd-not-listening; fi"
}

/**
 * Marker wait for [vmBootstrapScript]'s output. A shared constant so the
 * script's emitted markers and the caller's wait can't drift apart; the
 * `:\\S*` captures the failure reason the script names.
 */
internal const val BOOTSTRAP_MARKER_REGEX = "HAVEN_BOOTSTRAP_OK|HAVEN_BOOTSTRAP_FAIL:\\S*"

/** User-facing text for the reasons [vmBootstrapScript] can report. */
internal fun bootstrapFailureMessage(reason: String): String = when (reason) {
    "no-network" ->
        "The VM could not get an address on its virtual network, even after retrying. " +
            "This is the VM's own internal network, not your Wi-Fi."
    "sshd-not-listening" ->
        "The VM booted and got its network, but its SSH server did not come up inside the VM."
    else -> "VM setup (network/sshd) failed" + if (reason.isNotEmpty()) ": $reason" else "."
}

/**
 * Attach [busid] over USB/IP to the already-bootstrapped shared VM, mount
 * its new partitions ([readOnly] ? "ro" : "rw"), and report the delta (this
 * attach's own new mounts/locked-LUKS-partitions/vhci-port) — never a global
 * rescan, so this can run again for a second/third drive without disturbing
 * or re-reporting an earlier drive's already-mounted state. A LUKS-encrypted
 * partition is left unmounted and reported via HVNLOCKED —
 * QemuManager.unlockPartition mounts it afterwards. Top-level — pure
 * string-building, unit-testable without an Android Context.
 */
internal fun attachAndMountScript(busid: String, readOnly: Boolean): String {
    val mountCmd = if (readOnly) {
        // noload (skip journal replay) is only a valid option for ext4/xfs —
        // vfat/exfat/ntfs/ntfs3 reject the whole mount with "Unknown
        // parameter" if it's passed, wasting the one fallback attempt a
        // non-ext4/xfs stick gets. $t (blkid TYPE) is already set by the
        // caller's loop before this runs.
        "mount -o ro \"\$p\" \"\$d\" 2>/dev/null || " +
            "if [ \"\$t\" = ext4 ] || [ \"\$t\" = xfs ]; then mount -o ro,noload \"\$p\" \"\$d\" 2>/dev/null; fi"
    } else {
        // `sync` = every write flushes immediately, no write-back cache to
        // lose. Slower, but this is an emulated full-speed-limited USB link
        // already, and the alternative is a kill (VM OOM'd, app backgrounded,
        // battery pull) losing buffered writes and corrupting the filesystem —
        // the risk closeDrive()'s explicit unmount only covers for a *clean* eject.
        "mount -o rw,sync \"\$p\" \"\$d\" 2>/dev/null"
    }
    return "beforeVp=/tmp/hvn_vp_before_$$; afterVp=/tmp/hvn_vp_after_$$; " +
        "beforeDev=/tmp/hvn_dev_before_$$; afterDev=/tmp/hvn_dev_after_$$; newDev=/tmp/hvn_dev_new_$$; " +
        // Snapshot which vhci ports are free (local_busid column "0-0")
        // BEFORE this attach, so we can identify exactly which port THIS
        // busid lands on afterward without parsing `usbip port`'s
        // human-formatted text (busid there sits in a URL-suffix line, not
        // reliably greppable) — read the same tab-separated sysfs file the
        // kernel itself exposes instead.
        "awk '\$7==\"0-0\"{print \$2}' /sys/devices/platform/vhci_hcd.0/status > \"\$beforeVp\" 2>/dev/null; " +
        // Snapshot which partition NODES exist before this attach too — the
        // actual list, not just a count, so the mount+report loop at the end
        // can be scoped to precisely this attach's own new nodes instead of
        // rescanning every currently-visible partition (which would
        // re-report an earlier drive's still-locked/still-mounted state as
        // if it belonged to THIS drive — a real bug reproduced live: two
        // drives attached to the same shared VM both reported the same
        // locked partition, because the previous version rescanned globally
        // and relied on the marker/offset scan to filter it, which only
        // filters stale TEXT, not a fact re-printed fresh every attach).
        "ls /dev/sd[a-z][0-9]* 2>/dev/null | sort > \"\$beforeDev\"; " +
        "beforeCount=\$(wc -l < \"\$beforeDev\"); " +
        "usbip attach -r 10.0.2.2 -b $busid 2>/dev/null; n=0; myPort=''; " +
        // Poll with a cheap COUNT check (one `ls`+`wc`, not a full sort+diff
        // every second — under TCG each extra forked process meaningfully
        // adds up across up to $ENUM_WAIT_S iterations) — count still tells
        // us "a genuinely new node appeared" (unlike a bare existence check,
        // which would falsely early-exit immediately once another drive's
        // partitions are already visible). The actual LIST diff (needed to
        // know which node is new, for mount+report scoping) is computed only
        // ONCE, after the loop exits below.
        "while [ \$n -lt $ENUM_WAIT_S ]; do " +
        "curCount=\$(ls /dev/sd[a-z][0-9]* 2>/dev/null | wc -l); [ \"\$curCount\" -gt \"\$beforeCount\" ] && break; " +
        "if [ \$n -gt 0 ] && [ \$((\$n % 20)) -eq 0 ]; then " +
        // Retry: detach only THIS busid's own just-assigned port (captured
        // via the same before/after vhci-status diff), never a blind port-0
        // detach — with multiple drives possibly attached, port 0 could
        // belong to an entirely different, already-working drive.
        "awk '\$7==\"0-0\"{print \$2}' /sys/devices/platform/vhci_hcd.0/status > \"\$afterVp\" 2>/dev/null; " +
        // Same empty-pattern-file gotcha as the device-list diff below — if
        // every vhci port happens to be occupied ($afterVp empty), fall back
        // to just using $beforeVp's first entry rather than silently
        // matching nothing.
        "myPort=\$([ -s \"\$afterVp\" ] && grep -vxFf \"\$afterVp\" \"\$beforeVp\" 2>/dev/null | head -1 || head -1 \"\$beforeVp\"); " +
        "myPort=\$(echo \"\$myPort\" | sed 's/^0*\\([0-9]\\)/\\1/'); " +
        "[ -n \"\$myPort\" ] && usbip detach -p \"\$myPort\" 2>/dev/null; sleep 1; " +
        "usbip attach -r 10.0.2.2 -b $busid 2>/dev/null; " +
        "fi; n=\$((n+1)); sleep 1; done; " +
        // Final port capture (covers the common case where enumeration
        // succeeded on the first try, so the retry branch above never ran).
        "awk '\$7==\"0-0\"{print \$2}' /sys/devices/platform/vhci_hcd.0/status > \"\$afterVp\" 2>/dev/null; " +
        // Same empty-pattern-file gotcha as the device-list diff below — if
        // every vhci port happens to be occupied ($afterVp empty), fall back
        // to just using $beforeVp's first entry rather than silently
        // matching nothing.
        "myPort=\$([ -s \"\$afterVp\" ] && grep -vxFf \"\$afterVp\" \"\$beforeVp\" 2>/dev/null | head -1 || head -1 \"\$beforeVp\"); " +
        "myPort=\$(echo \"\$myPort\" | sed 's/^0*\\([0-9]\\)/\\1/'); " +
        "[ -n \"\$myPort\" ] && echo \"HVNPORT:\$myPort\"; " +
        "ls /dev/sd[a-z][0-9]* 2>/dev/null | sort > \"\$afterDev\"; " +
        // `grep -vxFf emptyfile` is a real gotcha: an EMPTY pattern file is
        // treated as one empty pattern, which matches every line, so with
        // -v it excludes ALL of them — reproduced live as the very first
        // drive attached (whose "before" list is genuinely empty) always
        // getting zero results back here, silently, with no error anywhere.
        "if [ -s \"\$beforeDev\" ]; then grep -vxFf \"\$beforeDev\" \"\$afterDev\" 2>/dev/null > \"\$newDev\"; " +
        "else cp \"\$afterDev\" \"\$newDev\"; fi; " +
        "rm -f \"\$beforeVp\" \"\$afterVp\" \"\$beforeDev\" \"\$afterDev\"; " +
        // Mount + report loop scoped to $newDev (this attach's own new
        // nodes) ONLY — never a blind /dev/sd* glob, so it can't touch or
        // re-report an earlier drive's already-mounted/already-locked state.
        "mkdir -p /mnt; while read p; do [ -b \"\$p\" ] || continue; " +
        "l=\$(basename \"\$p\"); d=/mnt/\$l; " +
        "t=\$(blkid -o value -s TYPE \"\$p\" 2>/dev/null); " +
        // blkid can race a partition node that JUST appeared (the device
        // exists but its filesystem/LUKS signature hasn't settled into
        // blkid's probe yet) — reproduced live as an empty $t on a
        // definitely-LUKS partition immediately after enumeration. One
        // bounded retry, paid only when it's actually empty, not on every
        // partition or every poll iteration.
        "[ -z \"\$t\" ] && { sleep 1; t=\$(blkid -o value -s TYPE \"\$p\" 2>/dev/null); }; " +
        // LUKS partitions are reported and left for unlockPartition —
        // mounting a raw LUKS block device would just fail anyway.
        "if [ \"\$t\" = crypto_LUKS ]; then echo \"HVNLOCKED:\$l\"; continue; fi; " +
        "mkdir -p \"\$d\"; $mountCmd; " +
        "mountpoint -q \"\$d\" 2>/dev/null && echo \"HVNMOUNT:\$d\"; " +
        "done < \"\$newDev\"; rm -f \"\$newDev\"; " +
        "echo HAVEN_ATTACH_OK"
}
