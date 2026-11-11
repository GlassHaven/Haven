package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.local.proot.PackageOps
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Boots a full **system** QEMU VM (real kernel, arbitrary arch) inside the
 * active proot and exposes its display over **VNC on loopback**, so Haven's
 * existing VNC viewer can render and drive it (#326). Distinct from
 * [QemuManager] (the USB-drive appliance VM, #287) and from qemu-**user**
 * (#325, per-binary translation, no VM).
 *
 * The x86_64 chain is proven end-to-end on-device (Phase 0 spike): a
 * native-arm64 proot runs `qemu-system-x86_64` under TCG, `-vnc 127.0.0.1:N
 * -vga std` binds a VNC server on loopback, and a Haven VNC connection to
 * `127.0.0.1:(5900+N)` renders the guest with working keyboard input.
 *
 * ### Guest architectures
 * Two targets, chosen per image at import time and stored alongside it:
 * - [VmArch.X86_64] — `-M pc -vga std`, the original path. On an arm64 phone
 *   this is *always* TCG: KVM cannot accelerate a foreign-arch guest.
 * - [VmArch.AARCH64] — `-M virt`, which is a materially different machine:
 *   no BIOS (a UEFI firmware image is required — see [ensureAarch64Firmware]),
 *   no VGA (`virtio-gpu-pci` instead), and no PS/2 controller, so keyboard and
 *   pointer have to be added as USB HID or the VNC viewer drives nothing.
 *
 * ### Acceleration
 * [probeKvm] looks for a usable `/dev/kvm`; [resolveAccel] combines that with
 * the guest/host arch match to pick KVM or TCG, and records *why* in
 * [VmState.accelReason] so the answer is visible rather than folded into a
 * timing complaint. On retail arm64 phones there is no `/dev/kvm` at all —
 * the vendor hypervisor owns EL2, so the kernel is not a KVM host and root
 * does not change that; devices with AVF/pKVM (Pixel 6+) do expose the node.
 * Whether such a device lets a *QEMU* process open it (rather than crosvm via
 * virtualizationservice) is NOT verified here — which is exactly why an
 * automatic KVM choice falls back to TCG instead of failing the boot.
 *
 * ### Caveats baked into the design
 * - **One VM at a time** — TCG plus phone RAM make concurrent system VMs
 *   impractical; [start] refuses while one is running.
 * - **Needs a VNC-capable qemu.** Alpine's qemu is a stripped build with no
 *   VNC; Debian's has it. We don't try to detect that statically — the VNC
 *   port simply never binds, which [start] surfaces as a clear error.
 * - The disk is a path **inside the active proot** (e.g. under `/tmp`, which
 *   is the app cacheDir). Image provisioning is a later phase; this manager
 *   only owns the VM lifecycle.
 */
@Singleton
class SystemVmManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    data class VmState(
        val diskPath: String,
        val status: Status,
        /** Loopback VNC port to point a Haven VNC connection at (127.0.0.1:vncPort). Null unless RUNNING. */
        val vncPort: Int? = null,
        val error: String? = null,
        val arch: VmArch = VmArch.X86_64,
        /** What the VM is actually running under — resolved, not requested. */
        val accel: Accel = Accel.TCG,
        /** Why [accel] came out that way, in the probe's own words. */
        val accelReason: String = "",
        /** Loopback TCP port of the phone's `usbredir` server wired into the VM via `-device usb-redir`, or null. */
        val usbRedirPort: Int? = null,
    )

    private val _state = MutableStateFlow<VmState?>(null)
    val state: StateFlow<VmState?> = _state.asStateFlow()

    private val mutex = Mutex()
    private val teardownScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    @Volatile
    private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /** What a look at `/dev/kvm` finds on THIS device, for callers that want to report it without booting anything. */
    fun kvmStatus(): KvmStatus = probeKvm()

    /** The host's own architecture — KVM is only possible for a guest that matches it. Null on a 32-bit ABI. */
    fun hostArch(): VmArch? = archFromAbi(runCatching { android.os.Build.SUPPORTED_ABIS?.firstOrNull() }.getOrNull())

    /**
     * Boot [diskGuestPath] (a path inside the active proot) as an [arch] system
     * VM with a VNC display on a free loopback port. Returns the running
     * [VmState] whose [VmState.vncPort] the caller wires into a Haven VNC
     * connection; throws [SystemVmException] on any failure (and leaves state
     * ERROR). One VM at a time — call [stop] first to replace.
     *
     * With [AccelMode.AUTO] (the default) a KVM boot that fails to come up is
     * retried once under TCG, because [probeKvm] can only check the node's
     * permissions — SELinux can still deny the `open()`, and a phone that
     * merely *has* `/dev/kvm` shouldn't lose the working TCG path over it. An
     * explicit [AccelMode.KVM] fails fast instead, so a deliberate KVM test
     * can't be silently answered with a slow TCG boot.
     */
    suspend fun start(
        diskGuestPath: String,
        diskFormat: String = "qcow2",
        memMb: Int = DEFAULT_MEM_MB,
        cpus: Int = DEFAULT_CPUS,
        arch: VmArch = VmArch.X86_64,
        accelMode: AccelMode = AccelMode.AUTO,
        usbRedirPort: Int? = null,
    ): VmState = mutex.withLock {
        if (process?.isAlive == true) {
            throw SystemVmException("A system VM is already running — stop it first.")
        }
        if (usbRedirPort != null && usbRedirPort !in 1..65535) {
            throw SystemVmException("usbRedirPort must be in 1..65535 (got $usbRedirPort)")
        }
        ensureQemuInstalled(arch)
        // `-M virt` has no BIOS: resolve (installing if need be) the UEFI
        // firmware BEFORE burning a boot attempt on a command that can't work.
        val bios = if (arch == VmArch.AARCH64) ensureAarch64Firmware() else null

        val plan = resolveAccel(accelMode, arch, hostArch(), probeKvm())
        val attempts = if (plan.accel == Accel.KVM && accelMode == AccelMode.AUTO) {
            listOf(plan, AccelPlan(Accel.TCG, "$KVM_NODE looked usable but qemu could not start with it (SELinux denies open() even when the permissions allow it) — fell back to TCG"))
        } else {
            listOf(plan)
        }

        var lastError = ""
        for ((index, attempt) in attempts.withIndex()) {
            // qemu `-vnc host:D` binds port 5900+D. Grab a free loopback port
            // and derive the display from it; ephemeral ports are well above
            // 5900, so the subtraction is always valid. Re-taken per attempt —
            // the failed attempt's port is gone with its process.
            val port = freeLoopbackPort()
            require(port >= VNC_BASE_PORT) { "no usable VNC port (got $port)" }
            val display = port - VNC_BASE_PORT

            _state.value = VmState(diskGuestPath, Status.STARTING, vncPort = port, arch = arch, accel = attempt.accel, accelReason = attempt.reason, usbRedirPort = usbRedirPort)
            val command = qemuVncCommand(diskGuestPath, display, memMb, cpus, diskFormat, arch, attempt.accel, bios, usbRedirPort)
            Log.i(TAG, "starting system VM (${arch.id}, ${attempt.accel}): $command")

            val proc = withContext(Dispatchers.IO) { prootManager.startCommandInProot(command) }
            process = proc

            // qemu binds the VNC server at startup (before the guest boots), so
            // a successful loopback connect means the display is up. A timeout
            // here is the empirical "this qemu build has no VNC" signal (e.g.
            // Alpine).
            val ready = withContext(Dispatchers.IO) { awaitPortOpen(port, VNC_BIND_TIMEOUT_MS) }
            if (ready && proc.isAlive) {
                return@withLock VmState(diskGuestPath, Status.RUNNING, port, null, arch, attempt.accel, attempt.reason, usbRedirPort)
                    .also { _state.value = it }
            }

            lastError = describeStartFailure(proc, port, attempt)
            stopLocked()
            if (index < attempts.lastIndex) Log.w(TAG, "$lastError — retrying under TCG")
        }

        _state.value = VmState(diskGuestPath, Status.ERROR, error = lastError, arch = arch, accel = attempts.last().accel, usbRedirPort = usbRedirPort)
        throw SystemVmException(lastError)
    }

    /**
     * Boot a stored image (from [importImage]) by id. Resolves it to its
     * in-proot qcow2 path and its recorded architecture, and delegates to
     * [start].
     */
    suspend fun startImage(
        imageId: String,
        memMb: Int = DEFAULT_MEM_MB,
        cpus: Int = DEFAULT_CPUS,
        accelMode: AccelMode = AccelMode.AUTO,
        usbRedirPort: Int? = null,
    ): VmState {
        if (!imageFile(imageId).exists()) throw SystemVmException("no such system-VM image: $imageId")
        return start(
            imageGuestPath(imageId),
            diskFormat = "qcow2",
            memMb = memMb,
            cpus = cpus,
            arch = storedArch(imageId),
            accelMode = accelMode,
            usbRedirPort = usbRedirPort,
        )
    }

    /** Power off / kill the running VM (idempotent). */
    suspend fun stop() = mutex.withLock { stopLocked() }

    /** Non-blocking teardown entry point for `ForegroundSessionParticipant.disconnectAll()`. */
    fun stopAsync() {
        teardownScope.launch { stop() }
    }

    private fun describeStartFailure(proc: Process, port: Int, plan: AccelPlan): String = when {
        !proc.isAlive ->
            "qemu exited before the VNC server came up (check the disk path / qemu args)" +
                if (plan.accel == Accel.KVM) " — this attempt used KVM" else ""
        else ->
            "VNC server never bound on 127.0.0.1:$port — this distro's qemu likely has no VNC support " +
                "(Alpine's does not; try Debian)"
    }

    // --- image store -------------------------------------------------------
    //
    // Bootable disk images live in cacheDir/system-vm (bound at /tmp/system-vm
    // in the proot, so qemu can read them). Everything is normalised to qcow2
    // on import so start() has one format and the base stays sparse. Same
    // cache-persistence caveat as the #287 appliance: Android can reclaim
    // cacheDir under storage pressure — re-import if that happens.
    //
    // Two sidecars per image: `$id.label` (display name) and `$id.arch` (guest
    // architecture). The arch has to be recorded at import because a qcow2 does
    // not say what CPU it's for, and startImage() has nothing else to go on —
    // booting an arm64 rootfs under `qemu-system-x86_64` just hangs on a
    // machine with no bootable device. A MISSING `$id.arch` means x86_64: every
    // image that predates this was, necessarily, x86_64.

    data class VmImage(val id: String, val label: String, val sizeBytes: Long, val arch: VmArch = VmArch.X86_64)

    private val imagesDir: File get() = File(context.cacheDir, "system-vm")
    private fun imageFile(id: String): File = File(imagesDir, "$id.qcow2")
    private fun imageGuestPath(id: String): String = "$IMAGE_GUEST_DIR/$id.qcow2"
    private fun archFile(id: String): File = File(imagesDir, "$id.arch")

    private fun storedArch(id: String): VmArch =
        VmArch.fromId(archFile(id).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() })

    /** Stored, ready-to-boot images. */
    fun listImages(): List<VmImage> =
        (imagesDir.listFiles { f -> f.isFile && f.extension == "qcow2" } ?: emptyArray())
            .map { f ->
                val id = f.nameWithoutExtension
                val label = File(imagesDir, "$id.label").takeIf { it.exists() }?.readText()?.trim() ?: id
                VmImage(id, label, f.length(), storedArch(id))
            }
            .sortedBy { it.id }

    /**
     * Import a bootable disk image from an http(s) URL or an on-device file
     * path, normalising it to qcow2 via `qemu-img convert`. [id] is a slug;
     * [expectedSha256] (of the SOURCE bytes) is verified when given. [arch] is
     * the guest architecture the image holds — recorded for [startImage], and
     * what decides which qemu target gets installed here. Returns the stored
     * [VmImage].
     */
    suspend fun importImage(
        id: String,
        label: String,
        source: String,
        expectedSha256: String? = null,
        arch: VmArch = VmArch.X86_64,
    ): VmImage = mutex.withLock {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) {
            "image id must be a slug (lowercase letters, digits, ., _, -): '$id'"
        }
        ensureQemuInstalled(arch) // provides qemu-img
        val dir = imagesDir.apply { mkdirs() }
        val srcFile = File(dir, "$id.src")
        val outFile = imageFile(id)
        try {
            withContext(Dispatchers.IO) { downloadOrCopy(source, srcFile, expectedSha256) }
            // Convert (raw/qcow2/vdi/vmdk → qcow2). Both paths are under
            // /tmp/system-vm inside the proot.
            val (out, code) = prootManager.runCommandInProot(
                "qemu-img convert -O qcow2 '$IMAGE_GUEST_DIR/$id.src' '$IMAGE_GUEST_DIR/$id.qcow2' 2>&1",
            )
            if (code != 0 || !outFile.exists()) {
                outFile.delete()
                throw SystemVmException("qemu-img convert failed (exit $code): ${out.takeLast(200)}")
            }
            File(dir, "$id.label").writeText(label)
            archFile(id).writeText(arch.id)
            VmImage(id, label, outFile.length(), arch)
        } finally {
            srcFile.delete()
        }
    }

    /** Delete a stored image (stopping the VM first if it's the one running). */
    suspend fun deleteImage(id: String) = mutex.withLock {
        if (process?.isAlive == true && _state.value?.diskPath == imageGuestPath(id)) {
            stopLocked()
        }
        imageFile(id).delete()
        File(imagesDir, "$id.label").delete()
        archFile(id).delete()
    }

    private fun downloadOrCopy(source: String, dest: File, expectedSha256: String?) {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            java.net.URL(source).openConnection().getInputStream().use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        } else {
            val src = File(source)
            require(src.isFile) { "source file not found: $source" }
            src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
        if (expectedSha256 != null) {
            val actual = sha256Hex(dest)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw SystemVmException("sha256 mismatch: expected $expectedSha256, got $actual")
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stopLocked() {
        process?.let { killProotProcessTree(it, TAG) }
        process = null
        _state.value = _state.value?.copy(status = Status.STOPPED, vncPort = null)
    }

    /**
     * Ensure BOTH the [arch] system emulator AND `qemu-img` (image convert,
     * used by [importImage]) are present in the active distro, installing the
     * family's package set if not. On Debian these live in SEPARATE packages —
     * `qemu-system-x86` vs `qemu-utils` — and qemu-utils is NOT pulled in as a
     * dependency, so checking only the emulator (as an earlier version did) let
     * a first import fail at `qemu-img convert` on a fresh Debian. VNC
     * *capability* is still verified empirically at boot (the port-bind wait),
     * not here.
     */
    private suspend fun ensureQemuInstalled(arch: VmArch) {
        if (qemuToolsPresent(arch)) return
        val family = prootManager.activeDistro.family
        val pkgs = qemuPackagesFor(family, arch)
        val ops = PackageOps.forFamily(family)
        Log.i(TAG, "installing ${pkgs.joinToString(" ")} in ${prootManager.activeDistroId}")
        val (out, code) = prootManager.runCommandInProot(
            "${ops.updateCmd()} >/dev/null 2>&1 ; ${ops.installCmd(pkgs)} 2>&1",
        )
        if (!qemuToolsPresent(arch)) {
            throw SystemVmException(
                "Could not install ${pkgs.joinToString(" ")} in ${prootManager.activeDistroId} " +
                    "(exit $code): ${out.takeLast(200)}",
            )
        }
    }

    /** True only when both the [arch] qemu emulator and qemu-img resolve on PATH. */
    private suspend fun qemuToolsPresent(arch: VmArch): Boolean {
        val (out, _) = prootManager.runCommandInProot(
            "command -v ${arch.qemuBin} >/dev/null 2>&1 && command -v qemu-img >/dev/null 2>&1 && echo QEMU_OK || true",
        )
        return out.contains("QEMU_OK")
    }

    /**
     * The in-proot path of an aarch64 UEFI firmware image, installing the
     * family's edk2/AAVMF package if none is on disk yet.
     *
     * `-M virt` has no BIOS, so `-bios <firmware>` is not optional: without it
     * qemu comes up on an empty machine and the disk never boots. The install
     * is a SEPARATE, deliberately non-fatal step rather than part of
     * [qemuPackagesFor]'s list — an unrecognised package name would make the
     * whole install command fail and take the (verified) emulator packages down
     * with it, and on Debian the emulator pulls `qemu-efi-aarch64` in by itself,
     * so the probe often succeeds with no extra package at all.
     *
     * Verified on-device for Debian trixie only: installing `qemu-system-arm`
     * left AAVMF_CODE.fd on disk, and a VM booted with it reached edk2. The
     * Alpine/Arch/Void package names are unverified.
     */
    private suspend fun ensureAarch64Firmware(): String {
        findAarch64Firmware()?.let { return it }
        val family = prootManager.activeDistro.family
        val fwPkgs = aarch64FirmwarePackagesFor(family)
        if (fwPkgs.isNotEmpty()) {
            val ops = PackageOps.forFamily(family)
            Log.i(TAG, "installing UEFI firmware ${fwPkgs.joinToString(" ")} in ${prootManager.activeDistroId}")
            prootManager.runCommandInProot("${ops.installCmd(fwPkgs)} >/dev/null 2>&1 ; true")
        }
        return findAarch64Firmware() ?: throw SystemVmException(
            "No aarch64 UEFI firmware in ${prootManager.activeDistroId}. QEMU's `-M virt` machine has no " +
                "BIOS, so an arm64 guest disk cannot boot without one. Looked for: " +
                AARCH64_FIRMWARE_CANDIDATES.joinToString(", ") +
                ". Install your distro's edk2 package (Debian: qemu-efi-aarch64, Alpine: aavmf, " +
                "Arch: edk2-aarch64) in the active proot and retry.",
        )
    }

    private suspend fun findAarch64Firmware(): String? =
        parseFirmwarePath(prootManager.runCommandInProot(firmwareProbeCommand()).first)

    /** Poll a loopback TCP connect until [port] accepts or [timeoutMs] elapses (or qemu dies). */
    private fun awaitPortOpen(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive == false) return false
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                true
            }.getOrDefault(false)
            if (ok) return true
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    companion object {
        private const val TAG = "SystemVmManager"
        /** cacheDir/system-vm is bound here in the proot (cacheDir → /tmp). */
        private const val IMAGE_GUEST_DIR = "/tmp/system-vm"
        const val VNC_BASE_PORT = 5900
        const val DEFAULT_MEM_MB = 2048
        const val DEFAULT_CPUS = 2
        private const val VNC_BIND_TIMEOUT_MS = 20_000L
    }
}

class SystemVmException(message: String) : Exception(message)

/**
 * A guest CPU architecture a system VM can be booted for — the qemu target
 * binary, and (via [qemuVncCommand]) a whole machine shape, not just a flag.
 */
enum class VmArch(val id: String, val qemuBin: String) {
    X86_64("x86_64", "qemu-system-x86_64"),
    AARCH64("aarch64", "qemu-system-aarch64"),
    ;

    companion object {
        /** Parses a stored/requested arch id. Anything unrecognised — including null — is x86_64, the original behaviour. */
        fun fromId(value: String?): VmArch {
            val v = value?.trim()?.lowercase() ?: return X86_64
            return entries.firstOrNull { it.id == v }
                ?: when (v) {
                    "amd64", "x64", "x86-64" -> X86_64
                    "arm64", "arm64-v8a", "armv8", "aarch64-v8a" -> AARCH64
                    else -> X86_64
                }
        }
    }
}

/** What a VM is actually executing under. */
enum class Accel { KVM, TCG }

/** What the *caller* wants — resolved into an [Accel] by [resolveAccel]. */
enum class AccelMode {
    /** Use KVM when it's genuinely possible, TCG otherwise; a failed KVM boot retries under TCG. */
    AUTO,

    /** Insist on KVM — fail fast (rather than boot slowly) if it isn't possible. */
    KVM,

    /** Insist on emulation, even where KVM is available. */
    TCG,
}

/** [Accel] plus the reason it was chosen, so "why is this slow" has an answer that isn't a guess. */
data class AccelPlan(val accel: Accel, val reason: String)

/** What a look at [KVM_NODE] found, and in what words to say so. */
data class KvmStatus(val usable: Boolean, val detail: String)

internal const val KVM_NODE = "/dev/kvm"

/**
 * Whether this device can host KVM at all, by looking at [node].
 *
 * On arm64 the answer is usually no, and not for a fixable reason: KVM needs
 * the kernel to own EL2, and on retail phones the vendor hypervisor
 * (Qualcomm's, Samsung's, MediaTek's) is already there, so Linux boots at EL1
 * and the node does not exist. Root cannot create it. Devices with AVF/pKVM
 * (Pixel 6+) do expose it.
 *
 * This checks the node's presence and DAC permissions ONLY — `access()`, which
 * knows nothing about SELinux. A "usable" verdict here is therefore a
 * *candidate*, not a guarantee, which is why [AccelMode.AUTO] keeps a TCG
 * fallback for the boot that follows.
 */
internal fun probeKvm(node: File = File(KVM_NODE)): KvmStatus = when {
    !node.exists() -> KvmStatus(
        false,
        "no $KVM_NODE — this kernel is not a KVM host (on arm64 the vendor hypervisor owns EL2; root does not change that)",
    )
    !node.canRead() || !node.canWrite() -> KvmStatus(
        false,
        "$KVM_NODE exists but is not readable+writable by Haven's uid — it needs to be opened up (root/SELinux) before qemu can use it",
    )
    else -> KvmStatus(true, "$KVM_NODE is present and readable+writable (SELinux may still deny open())")
}

/**
 * The host's own [VmArch] from its primary ABI, or null for an ABI with no
 * system-VM story (32-bit). Split out from the manager so it is testable
 * without an Android runtime.
 */
internal fun archFromAbi(abi: String?): VmArch? = when (abi?.trim()?.lowercase()) {
    "arm64-v8a" -> VmArch.AARCH64
    "x86_64" -> VmArch.X86_64
    else -> null
}

/**
 * Decide what to run [guest] under.
 *
 * The arch match is the part that's easy to forget: KVM virtualises, it does
 * not translate, so an x86_64 image on an arm64 phone is TCG no matter what
 * `/dev/kvm` says. [AccelMode.KVM] throws rather than quietly degrading —
 * a deliberate KVM test that answers with a two-minute TCG boot is worse than
 * an error.
 */
internal fun resolveAccel(mode: AccelMode, guest: VmArch, host: VmArch?, kvm: KvmStatus): AccelPlan {
    if (mode == AccelMode.TCG) return AccelPlan(Accel.TCG, "TCG requested explicitly")
    if (host == null || host != guest) {
        val why = "a ${guest.id} guest on a ${host?.id ?: "non-64-bit"} host — " +
            "KVM can only accelerate a same-arch guest, so this is emulated"
        if (mode == AccelMode.KVM) throw SystemVmException("KVM was requested but is impossible here: $why.")
        return AccelPlan(Accel.TCG, why)
    }
    if (!kvm.usable) {
        if (mode == AccelMode.KVM) throw SystemVmException("KVM was requested but is unavailable: ${kvm.detail}.")
        return AccelPlan(Accel.TCG, kvm.detail)
    }
    return AccelPlan(Accel.KVM, "KVM: same-arch (${guest.id}) guest, and ${kvm.detail}")
}

/**
 * Pure builder for the runtime qemu command. `exec` so the launcher process
 * *is* qemu (clean to signal); `-vnc 127.0.0.1:[display]` serves the display
 * over VNC on port `5900+display`; user-net gives the guest outbound
 * networking. Extracted top-level so it unit-tests without an Android Context.
 *
 * The two machines differ by more than a target binary:
 * - **x86_64** keeps the exact arg set the on-device spike proved (`-M pc`,
 *   `-vga std`, `-boot c`); under TCG the string is byte-identical to the
 *   pre-aarch64 version, so the proven path cannot regress.
 * - **aarch64** is `-M virt`, which has no BIOS (hence the required
 *   [biosGuestPath]), no VGA (`virtio-gpu-pci`), no PS/2 controller (hence USB
 *   HID — without `usb-kbd`/`usb-tablet` a VNC client connects to a guest it
 *   cannot type into), and no `-boot c` to honour, since UEFI does the
 *   choosing.
 */
internal fun qemuVncCommand(
    diskGuestPath: String,
    display: Int,
    memMb: Int,
    cpus: Int,
    diskFormat: String = "qcow2",
    arch: VmArch = VmArch.X86_64,
    accel: Accel = Accel.TCG,
    biosGuestPath: String? = null,
    usbRedirPort: Int? = null,
): String {
    if (usbRedirPort != null) {
        require(usbRedirPort in 1..65535) { "usbRedirPort must be in 1..65535 (got $usbRedirPort)" }
    }
    return when (arch) {
        VmArch.X86_64 ->
            "exec qemu-system-x86_64 -M pc " +
                (if (accel == Accel.KVM) "-enable-kvm -cpu host " else "") +
                "-m $memMb -smp $cpus -monitor none " +
                "-drive file=$diskGuestPath,if=virtio,format=$diskFormat " +
                "-vga std -vnc 127.0.0.1:$display " +
                "-netdev user,id=n0 -device virtio-net-pci,netdev=n0 " +
                (if (usbRedirPort != null) "-device qemu-xhci -chardev socket,id=usbredir0,host=127.0.0.1,port=$usbRedirPort -device usb-redir,chardev=usbredir0 " else "") +
                "-boot c -no-reboot"

        VmArch.AARCH64 -> {
            require(!biosGuestPath.isNullOrBlank()) {
                "aarch64 needs a UEFI firmware image: `-M virt` has no BIOS, so there is nothing to boot the disk"
            }
            // `-cpu host` is KVM-only; TCG needs a concrete model, and `max` is the
            // permissive one — an ARMv8.0 model (cortex-a57/a72) is refused by
            // guest kernels built for newer baselines.
            "exec qemu-system-aarch64 -M virt " +
                (if (accel == Accel.KVM) "-enable-kvm -cpu host " else "-cpu $AARCH64_TCG_CPU ") +
                "-m $memMb -smp $cpus -monitor none " +
                "-bios $biosGuestPath " +
                "-drive file=$diskGuestPath,if=virtio,format=$diskFormat " +
                "-device virtio-gpu-pci -vnc 127.0.0.1:$display " +
                "-device qemu-xhci -device usb-kbd -device usb-tablet " +
                (if (usbRedirPort != null) "-chardev socket,id=usbredir0,host=127.0.0.1,port=$usbRedirPort -device usb-redir,chardev=usbredir0 " else "") +
                "-netdev user,id=n0 -device virtio-net-pci,netdev=n0 " +
                "-no-reboot"
        }
    }
}

/** The TCG CPU model for aarch64 guests — see [qemuVncCommand]. */
internal const val AARCH64_TCG_CPU = "max"

/**
 * The qemu packages to install for a distro family and guest [arch]: the
 * system emulator (Debian's is VNC-capable; Alpine's is not) PLUS qemu-img.
 * Debian splits qemu-img into `qemu-utils`; Alpine/Arch ship it as `qemu-img`;
 * Void's single `qemu` package bundles both. Debian's aarch64 emulator lives in
 * `qemu-system-arm` (verified present in trixie), not a `-aarch64` package.
 *
 * UEFI firmware is deliberately NOT here — see
 * [SystemVmManager.ensureAarch64Firmware] for why it's installed separately.
 */
internal fun qemuPackagesFor(family: PackageFamily, arch: VmArch = VmArch.X86_64): List<String> = when (arch) {
    VmArch.X86_64 -> when (family) {
        PackageFamily.APK -> listOf("qemu-system-x86_64", "qemu-img")
        PackageFamily.APT -> listOf("qemu-system-x86", "qemu-utils")
        PackageFamily.PACMAN -> listOf("qemu-system-x86", "qemu-img")
        PackageFamily.XBPS -> listOf("qemu")
        else -> listOf("qemu")
    }
    VmArch.AARCH64 -> when (family) {
        PackageFamily.APK -> listOf("qemu-system-aarch64", "qemu-img")
        PackageFamily.APT -> listOf("qemu-system-arm", "qemu-utils")
        PackageFamily.PACMAN -> listOf("qemu-system-aarch64", "qemu-img")
        PackageFamily.XBPS -> listOf("qemu")
        else -> listOf("qemu")
    }
}

/**
 * Best-effort edk2/AAVMF package for a family, installed on its own so a wrong
 * name can't sink the emulator install. Empty where the name isn't known —
 * silence beats a fabricated package that fails the whole command.
 */
internal fun aarch64FirmwarePackagesFor(family: PackageFamily): List<String> = when (family) {
    PackageFamily.APT -> listOf("qemu-efi-aarch64") // verified: real candidate in Debian trixie
    PackageFamily.APK -> listOf("aavmf")
    PackageFamily.PACMAN -> listOf("edk2-aarch64")
    else -> emptyList()
}

/**
 * Where an aarch64 UEFI firmware image might already be, most-likely first.
 *
 * The head entry is measured, not assumed: on a Debian trixie proot,
 * `apt-get install qemu-system-arm` pulls in `qemu-efi-aarch64` on its own
 * (`dpkg -S` confirms it owns the file), so the common case needs no extra
 * package — and a VM booted with it reached the edk2 boot menu. Note qemu's own
 * `/usr/share/qemu/edk2-aarch64-code.fd` is NOT present there; it stays in the
 * list for the builds that do ship it, just not at the front.
 */
internal val AARCH64_FIRMWARE_CANDIDATES: List<String> = listOf(
    "/usr/share/AAVMF/AAVMF_CODE.fd", // Debian/Ubuntu qemu-efi-aarch64 — verified on trixie
    "/usr/share/qemu/edk2-aarch64-code.fd", // some qemu builds bundle their own
    "/usr/share/AAVMF/QEMU_EFI.fd", // Alpine aavmf
    "/usr/share/edk2/aarch64/QEMU_EFI.fd", // Arch edk2-aarch64
    "/usr/share/edk2/aarch64/QEMU_EFI.silent.fd",
    "/usr/share/edk2-armvirt/aarch64/QEMU_EFI.fd", // Void edk2-armvirt
    "/usr/share/qemu-efi-aarch64/QEMU_EFI.fd",
)

/** Emits `HVNFW:<path>` for the first candidate that exists; exits 0 either way so the caller reads the marker, not a code. */
internal fun firmwareProbeCommand(candidates: List<String> = AARCH64_FIRMWARE_CANDIDATES): String =
    "for f in " + candidates.joinToString(" ") { "'$it'" } +
        "; do [ -f \"\$f\" ] && { echo \"HVNFW:\$f\"; break; }; done; true"

/** Reads back [firmwareProbeCommand]'s marker. Null when no candidate was found. */
internal fun parseFirmwarePath(output: String): String? {
    val re = Regex("^HVNFW:(\\S+)$")
    return output.lineSequence()
        .map { it.trim().trimEnd('\r') }
        .firstNotNullOfOrNull { re.find(it)?.groupValues?.get(1) }
}

/** A free loopback TCP port (bound momentarily, then released for qemu to claim). */
private fun freeLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
