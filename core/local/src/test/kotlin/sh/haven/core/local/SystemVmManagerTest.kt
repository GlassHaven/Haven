package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.core.local.proot.PackageFamily
import java.io.File

/**
 * [qemuVncCommand] / [qemuPackagesFor] are the pure, Context-free pieces of the
 * #326 system-VM launch — pinned here so the exact qemu args the on-device
 * spike proved (VNC on loopback, std VGA, boot-from-disk) can't silently
 * regress. The device path (proc launch, port-bind wait) is covered on-device.
 *
 * The aarch64 target and the KVM probe add the pieces that decide *which*
 * machine gets built and what it runs under; those are pinned here too, since
 * getting them wrong produces a VM that boots to nothing (no firmware) or one
 * nobody can type into (no USB HID) rather than a compile error.
 */
class SystemVmManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fw = "/usr/share/qemu/edk2-aarch64-code.fd"

    @Test
    fun `vnc command binds VNC on the derived display and boots the disk`() {
        val cmd = qemuVncCommand(diskGuestPath = "/tmp/system-vm/sys.qcow2", display = 7, memMb = 2048, cpus = 2)
        // Display 7 → qemu binds 127.0.0.1:5907. The VNC server is what Haven's
        // viewer connects to; std VGA is the surface it renders.
        assertTrue("must serve VNC on the derived display", cmd.contains("-vnc 127.0.0.1:7"))
        assertTrue("std VGA gives the client a surface to render", cmd.contains("-vga std"))
        assertTrue("boots the installed disk", cmd.contains("-boot c"))
        // Imported images are normalised to qcow2 (the default format).
        assertTrue("virtio disk in the default qcow2 format", cmd.contains("-drive file=/tmp/system-vm/sys.qcow2,if=virtio,format=qcow2"))
        assertTrue("user-net for guest outbound", cmd.contains("-netdev user,id=n0"))
        assertTrue("exec so the launcher process IS qemu (clean to signal)", cmd.startsWith("exec qemu-system-x86_64"))
    }

    /**
     * The x86_64 TCG string is the one the on-device spike proved. Adding a
     * second architecture must not perturb it by so much as a space — hence a
     * byte-for-byte pin rather than another set of `contains` checks.
     */
    @Test
    fun `x86_64 TCG args are byte-identical to the proven pre-aarch64 command`() {
        assertEquals(
            "exec qemu-system-x86_64 -M pc -m 2048 -smp 2 -monitor none " +
                "-drive file=/tmp/system-vm/sys.qcow2,if=virtio,format=qcow2 " +
                "-vga std -vnc 127.0.0.1:3 " +
                "-netdev user,id=n0 -device virtio-net-pci,netdev=n0 " +
                "-boot c -no-reboot",
            qemuVncCommand("/tmp/system-vm/sys.qcow2", display = 3, memMb = 2048, cpus = 2),
        )
    }

    @Test
    fun `disk format is honoured — raw for a hand-placed image, qcow2 by default`() {
        assertTrue(qemuVncCommand("/d.img", 1, 2048, 2, diskFormat = "raw").contains("format=raw"))
        assertTrue("default is qcow2 (imported images)", qemuVncCommand("/d.qcow2", 1, 2048, 2).contains("format=qcow2"))
    }

    @Test
    fun `vnc command honours mem and cpu sizing`() {
        val cmd = qemuVncCommand("/d.img", display = 0, memMb = 4096, cpus = 4)
        assertTrue(cmd.contains("-m 4096"))
        assertTrue(cmd.contains("-smp 4"))
        assertTrue("display 0 → 127.0.0.1:0 → port 5900", cmd.contains("-vnc 127.0.0.1:0"))
    }

    @Test
    fun `no serial-console redirection — a system VM is driven via VNC, not serial`() {
        val cmd = qemuVncCommand("/d.img", display = 1, memMb = 2048, cpus = 2)
        // Unlike the USB appliance (serial auto-drive), a user-facing system VM
        // is interactive over VNC; a `-serial stdio` here would fight that.
        assertFalse(cmd.contains("-serial stdio"))
    }

    // --- aarch64 target ----------------------------------------------------

    @Test
    fun `aarch64 builds a virt machine with firmware, virtio-gpu and USB HID`() {
        val cmd = qemuVncCommand(
            "/tmp/system-vm/arm.qcow2",
            display = 5,
            memMb = 3072,
            cpus = 4,
            arch = VmArch.AARCH64,
            biosGuestPath = fw,
        )
        assertTrue("the aarch64 target binary", cmd.startsWith("exec qemu-system-aarch64"))
        assertTrue("virt is the only sane aarch64 machine", cmd.contains("-M virt"))
        // `-M virt` has no BIOS: without -bios the disk never boots.
        assertTrue("UEFI firmware is mandatory on virt", cmd.contains("-bios $fw"))
        // …no VGA either.
        assertTrue("virt renders through virtio-gpu, not VGA", cmd.contains("-device virtio-gpu-pci"))
        assertFalse("-vga std is an x86 device", cmd.contains("-vga std"))
        // …and no PS/2 controller, so input has to arrive over USB or the VNC
        // viewer connects to a guest it cannot drive.
        assertTrue("keyboard over USB HID", cmd.contains("-device usb-kbd"))
        assertTrue("absolute pointer for a VNC client", cmd.contains("-device usb-tablet"))
        assertTrue("…which needs a controller", cmd.contains("-device qemu-xhci"))
        // UEFI picks the boot device; -boot c is BIOS-speak.
        assertFalse("-boot c means nothing under UEFI", cmd.contains("-boot c"))
        assertTrue(cmd.contains("-vnc 127.0.0.1:5"))
        assertTrue(cmd.contains("-m 3072"))
        assertTrue(cmd.contains("-smp 4"))
        assertTrue(cmd.contains("-drive file=/tmp/system-vm/arm.qcow2,if=virtio,format=qcow2"))
    }

    @Test
    fun `aarch64 without firmware is refused at build time, not at boot`() {
        // Failing here beats a qemu that comes up on an empty machine and sits
        // there while the user waits out the 20s VNC-bind timeout.
        listOf(null, "", "   ").forEach { bios ->
            runCatching { qemuVncCommand("/d.qcow2", 1, 2048, 2, arch = VmArch.AARCH64, biosGuestPath = bios) }
                .fold(
                    onSuccess = { throw AssertionError("expected a refusal for bios=<$bios>") },
                    onFailure = { assertTrue("wrong failure: $it", it is IllegalArgumentException) },
                )
        }
    }

    @Test
    fun `TCG picks a permissive CPU model, KVM passes the host through`() {
        val tcg = qemuVncCommand("/d.qcow2", 1, 2048, 2, arch = VmArch.AARCH64, accel = Accel.TCG, biosGuestPath = fw)
        assertTrue("a concrete model is required under TCG", tcg.contains("-cpu $AARCH64_TCG_CPU"))
        assertFalse(tcg.contains("-enable-kvm"))

        val kvm = qemuVncCommand("/d.qcow2", 1, 2048, 2, arch = VmArch.AARCH64, accel = Accel.KVM, biosGuestPath = fw)
        assertTrue(kvm.contains("-enable-kvm"))
        assertTrue("-cpu host is the point of KVM", kvm.contains("-cpu host"))
        assertFalse("…and is mutually exclusive with a TCG model", kvm.contains("-cpu $AARCH64_TCG_CPU"))
    }

    @Test
    fun `x86_64 under KVM also gets the acceleration flags`() {
        val cmd = qemuVncCommand("/d.qcow2", 1, 2048, 2, accel = Accel.KVM)
        assertTrue(cmd.contains("-enable-kvm"))
        assertTrue(cmd.contains("-cpu host"))
        assertTrue("still the x86 machine", cmd.contains("-M pc") && cmd.contains("-vga std"))
    }

    // --- /dev/kvm probe ----------------------------------------------------

    @Test
    fun `a missing node is unusable and says why root cannot fix it`() {
        val probe = probeKvm(File(tmp.root, "definitely-absent-kvm"))
        assertFalse(probe.usable)
        // The detail is the whole value of the probe: "no KVM" without "the
        // vendor hypervisor owns EL2" sends people looking for a root fix.
        assertTrue("must name EL2/host-kernel as the cause: ${probe.detail}", probe.detail.contains("EL2"))
        assertTrue(probe.detail.contains("root does not change that"))
    }

    @Test
    fun `a readable-writable node is usable, and still flags SELinux`() {
        val node = tmp.newFile("kvm")
        val probe = probeKvm(node)
        assertTrue(probe.detail, probe.usable)
        // access() is DAC-only, so "usable" is a candidate, not a promise —
        // which is what justifies the AUTO path's TCG fallback.
        assertTrue("must not overclaim: ${probe.detail}", probe.detail.contains("SELinux"))
    }

    @Test
    fun `a node we cannot open is unusable — existence alone is not the test`() {
        val node = tmp.newFile("kvm-locked")
        node.setReadable(false, false)
        // Running as root (some CI containers) ignores the mode bits entirely;
        // there is nothing to assert on such a host.
        Assume.assumeFalse("uid bypasses DAC — cannot exercise this branch", node.canRead())
        val probe = probeKvm(node)
        assertFalse(probe.usable)
        assertTrue(probe.detail, probe.detail.contains("not readable+writable"))
    }

    // --- accel resolution --------------------------------------------------

    @Test
    fun `KVM cannot accelerate a foreign-arch guest, however usable the node`() {
        val plan = resolveAccel(AccelMode.AUTO, VmArch.X86_64, VmArch.AARCH64, KvmStatus(true, "fine"))
        assertEquals(Accel.TCG, plan.accel)
        assertTrue("the reason must be the arch, not the node: ${plan.reason}", plan.reason.contains("same-arch"))
    }

    @Test
    fun `AUTO takes KVM for a same-arch guest on a usable node`() {
        val plan = resolveAccel(AccelMode.AUTO, VmArch.AARCH64, VmArch.AARCH64, KvmStatus(true, "node is fine"))
        assertEquals(Accel.KVM, plan.accel)
        assertTrue(plan.reason.contains("node is fine"))
    }

    @Test
    fun `AUTO degrades to TCG carrying the probe's own words`() {
        val plan = resolveAccel(AccelMode.AUTO, VmArch.AARCH64, VmArch.AARCH64, KvmStatus(false, "no /dev/kvm — not a KVM host"))
        assertEquals(Accel.TCG, plan.accel)
        assertEquals("no /dev/kvm — not a KVM host", plan.reason)
    }

    @Test
    fun `an explicit KVM request fails fast rather than booting slowly`() {
        // Both impossibility modes: no node, and a foreign-arch guest.
        listOf(
            Triple(VmArch.AARCH64, VmArch.AARCH64, KvmStatus(false, "no /dev/kvm")),
            Triple(VmArch.X86_64, VmArch.AARCH64, KvmStatus(true, "fine")),
        ).forEach { (guest, host, kvm) ->
            runCatching { resolveAccel(AccelMode.KVM, guest, host, kvm) }.fold(
                onSuccess = { throw AssertionError("expected a throw for $guest on $host, got $it") },
                onFailure = { assertTrue("wrong type: $it", it is SystemVmException) },
            )
        }
    }

    @Test
    fun `TCG can be forced on a KVM-capable device`() {
        val plan = resolveAccel(AccelMode.TCG, VmArch.AARCH64, VmArch.AARCH64, KvmStatus(true, "fine"))
        assertEquals(Accel.TCG, plan.accel)
    }

    @Test
    fun `a 32-bit host has no KVM story`() {
        assertNull(archFromAbi("armeabi-v7a"))
        assertNull(archFromAbi(null))
        assertEquals(VmArch.AARCH64, archFromAbi("arm64-v8a"))
        assertEquals(VmArch.X86_64, archFromAbi("x86_64"))
        assertEquals(
            Accel.TCG,
            resolveAccel(AccelMode.AUTO, VmArch.AARCH64, archFromAbi("armeabi-v7a"), KvmStatus(true, "fine")).accel,
        )
    }

    // --- image arch record -------------------------------------------------

    @Test
    fun `an unrecorded arch reads as x86_64 — every image predating the sidecar was`() {
        assertEquals(VmArch.X86_64, VmArch.fromId(null))
        assertEquals(VmArch.X86_64, VmArch.fromId(""))
        assertEquals(VmArch.X86_64, VmArch.fromId("wat"))
        assertEquals(VmArch.AARCH64, VmArch.fromId("aarch64"))
        assertEquals(VmArch.AARCH64, VmArch.fromId(" arm64\n"))
        assertEquals(VmArch.X86_64, VmArch.fromId("AMD64"))
    }

    // --- packages and firmware --------------------------------------------

    @Test
    fun `qemu packages are per-distro-family and include qemu-img (Debian's is qemu-utils)`() {
        // Both the emulator AND qemu-img — the convert in importImage needs
        // qemu-img, which Debian ships separately (qemu-utils, not a dep of
        // qemu-system-x86), so a first import used to fail without it.
        assertEquals(listOf("qemu-system-x86_64", "qemu-img"), qemuPackagesFor(PackageFamily.APK))
        assertEquals(listOf("qemu-system-x86", "qemu-utils"), qemuPackagesFor(PackageFamily.APT))
        assertEquals(listOf("qemu-system-x86", "qemu-img"), qemuPackagesFor(PackageFamily.PACMAN))
        assertEquals(listOf("qemu"), qemuPackagesFor(PackageFamily.XBPS))
    }

    @Test
    fun `Debian's aarch64 emulator is in qemu-system-arm, not a -aarch64 package`() {
        // Verified against a trixie proot: qemu-system-arm 1:10.0.11 provides
        // qemu-system-aarch64; there is no qemu-system-aarch64 package.
        assertEquals(listOf("qemu-system-arm", "qemu-utils"), qemuPackagesFor(PackageFamily.APT, VmArch.AARCH64))
        assertEquals(listOf("qemu-system-aarch64", "qemu-img"), qemuPackagesFor(PackageFamily.APK, VmArch.AARCH64))
        assertEquals(listOf("qemu-system-aarch64", "qemu-img"), qemuPackagesFor(PackageFamily.PACMAN, VmArch.AARCH64))
    }

    @Test
    fun `firmware is never bundled into the emulator install`() {
        // A wrong firmware package name in the emulator's install command would
        // fail the whole command and leave the (verified) emulator uninstalled,
        // so the two are separate — and unknown families get an EMPTY list
        // rather than a guessed package name.
        PackageFamily.entries.forEach { family ->
            VmArch.entries.forEach { arch ->
                val pkgs = qemuPackagesFor(family, arch)
                assertTrue(
                    "$family/$arch must not carry firmware: $pkgs",
                    pkgs.none { it.contains("efi") || it.contains("edk2") || it.contains("aavmf") },
                )
            }
        }
        assertEquals(listOf("qemu-efi-aarch64"), aarch64FirmwarePackagesFor(PackageFamily.APT))
        assertEquals(emptyList<String>(), aarch64FirmwarePackagesFor(PackageFamily.XBPS))
        assertEquals(emptyList<String>(), aarch64FirmwarePackagesFor(PackageFamily.NIX))
    }

    @Test
    fun `the firmware probe prefers the path measured on-device and always exits clean`() {
        // Measured, not assumed: on a Debian trixie proot `apt-get install
        // qemu-system-arm` pulls in qemu-efi-aarch64 by itself and leaves the
        // firmware here — while qemu's own edk2-aarch64-code.fd is absent — so
        // this is the path that makes the common case need no extra package.
        assertEquals("/usr/share/AAVMF/AAVMF_CODE.fd", AARCH64_FIRMWARE_CANDIDATES.first())
        val cmd = firmwareProbeCommand()
        AARCH64_FIRMWARE_CANDIDATES.forEach { assertTrue("candidate missing from probe: $it", cmd.contains("'$it'")) }
        // The caller reads the marker, not an exit code — a probe that found
        // nothing is a fact, not a command failure.
        assertTrue(cmd.trimEnd().endsWith("true"))
    }

    @Test
    fun `firmware parse takes the first marker and null when there is none`() {
        assertEquals(
            "/usr/share/AAVMF/AAVMF_CODE.fd",
            parseFirmwarePath("some apt noise\r\nHVNFW:/usr/share/AAVMF/AAVMF_CODE.fd\r\nHVNFW:/second/one.fd\n"),
        )
        assertNull(parseFirmwarePath("no firmware here\n"))
        assertNull(parseFirmwarePath(""))
    }

    // --- usbredir attachment (#613) ----------------------------------------

    @Test
    fun `usbRedirPort wires qemu-xhci and usb-redir on x86_64 and reuses existing xhci on aarch64`() {
        val x86 = qemuVncCommand("/d.qcow2", display = 2, memMb = 2048, cpus = 2, usbRedirPort = 4000)
        assertTrue(x86.contains("-device qemu-xhci -chardev socket,id=usbredir0,host=127.0.0.1,port=4000 -device usb-redir,chardev=usbredir0"))
        assertEquals(1, Regex("-device qemu-xhci").findAll(x86).count())

        val arm = qemuVncCommand(
            "/d.qcow2",
            display = 2,
            memMb = 2048,
            cpus = 2,
            arch = VmArch.AARCH64,
            biosGuestPath = fw,
            usbRedirPort = 4000,
        )
        assertTrue(arm.contains("-chardev socket,id=usbredir0,host=127.0.0.1,port=4000 -device usb-redir,chardev=usbredir0"))
        assertEquals("aarch64 machine already has one qemu-xhci for usb-kbd/usb-tablet", 1, Regex("-device qemu-xhci").findAll(arm).count())

        listOf(0, -1, 65536).forEach { badPort ->
            runCatching { qemuVncCommand("/d.qcow2", 1, 2048, 2, usbRedirPort = badPort) }.fold(
                onSuccess = { throw AssertionError("expected refusal for usbRedirPort=$badPort") },
                onFailure = { assertTrue("wrong exception: $it", it is IllegalArgumentException) },
            )
        }
    }

    // --- QEMU 11 + Virgl / Venus + Windows 11 UEFI/TPM2 (#829) -------------

    @Test
    fun `VmGpuMode parses stored and MCP identifiers with STD fallback`() {
        assertEquals(VmGpuMode.STD, VmGpuMode.fromId(null))
        assertEquals(VmGpuMode.STD, VmGpuMode.fromId(""))
        assertEquals(VmGpuMode.STD, VmGpuMode.fromId("std"))
        assertEquals(VmGpuMode.VIRGL, VmGpuMode.fromId("virgl"))
        assertEquals(VmGpuMode.VENUS, VmGpuMode.fromId(" VENUS\n"))
        assertEquals(VmGpuMode.STD, VmGpuMode.fromId("unknown"))
    }

    @Test
    fun `x86_64 VENUS mode wires opt-qemu11, surfaceless EGL, Venus socket, shared memfd, q35 and virtio-vga-gl`() {
        val cmd = qemuVncCommand(
            diskGuestPath = "/tmp/system-vm/win11.qcow2",
            display = 4,
            memMb = 4096,
            cpus = 4,
            arch = VmArch.X86_64,
            accel = Accel.TCG,
            usbRedirPort = 4000,
            gpuMode = VmGpuMode.VENUS,
            useOptQemu11 = true,
        )
        assertTrue("uses /opt/qemu11 binary", cmd.contains("exec /opt/qemu11/bin/qemu-system-x86_64"))
        assertTrue("loads /opt/qemu11 libvirglrenderer", cmd.contains("LD_LIBRARY_PATH=/opt/qemu11/lib"))
        assertTrue("surfaceless EGL for PRoot without /dev/dri", cmd.contains("EGL_PLATFORM=surfaceless"))
        assertTrue("points virglrenderer at host libvirgl_render_server.so socket", cmd.contains("VIRGL_RENDER_SERVER_SOCKET=$VENUS_GUEST_SOCKET"))
        assertFalse(
            "VENUS must NOT set GALLIUM_DRIVER=virpipe (llvmpipe provides EGL_EXT_image_dma_buf_import for 2D scanout blobs)",
            cmd.contains("GALLIUM_DRIVER=virpipe"),
        )
        assertTrue("q35 PCIe machine for virtio-vga-gl BARs and Windows 11", cmd.contains("-M q35"))
        assertTrue("Windows 11 24H2 TCG CPU baseline (+sse4.2,+popcnt)", cmd.contains("-cpu $X86_64_MODERN_TCG_CPU"))
        assertTrue(
            "shared memfd RAM is required for blob=on,venus=on SHM fd export",
            cmd.contains("-object memory-backend-memfd,id=mem,size=4096M,share=on -numa node,memdev=mem"),
        )
        assertTrue(
            "Venus 3D + 4G hostmem BAR with DRM disabled for PRoot",
            cmd.contains("-device virtio-vga-gl,drm_native_context=off,disable-legacy=on,hostmem=4G,blob=on,venus=on,xres=1920,yres=1080"),
        )
        assertFalse("virtio-vga-gl replaces -vga std", cmd.contains("-vga std"))
        assertTrue("egl-headless blits scanout into VNC", cmd.contains("-display egl-headless -vnc 127.0.0.1:4"))
        assertTrue("q35 gets USB HID for seamless VNC pointer", cmd.contains("-device usb-kbd -device usb-tablet"))
        assertEquals("reuses single qemu-xhci across USB HID and usbredir", 1, Regex("-device qemu-xhci").findAll(cmd).count())
    }

    @Test
    fun `aarch64 VENUS and VIRGL modes swap virtio-gpu-pci for virtio-gpu-gl-pci`() {
        val venus = qemuVncCommand(
            "/tmp/system-vm/arm.qcow2",
            display = 2,
            memMb = 4096,
            cpus = 4,
            arch = VmArch.AARCH64,
            biosGuestPath = fw,
            gpuMode = VmGpuMode.VENUS,
        )
        assertTrue(venus.contains("-device virtio-gpu-gl-pci,drm_native_context=off,disable-legacy=on,hostmem=4G,blob=on,venus=on,xres=1920,yres=1080"))
        assertFalse(venus.contains("-device virtio-gpu-pci "))
        assertTrue(venus.contains("-object memory-backend-memfd,id=mem,size=4096M,share=on -numa node,memdev=mem"))
        assertTrue(venus.contains("-display egl-headless -vnc 127.0.0.1:2"))

        val virgl = qemuVncCommand(
            "/tmp/system-vm/arm.qcow2",
            display = 3,
            memMb = 2048,
            cpus = 2,
            arch = VmArch.AARCH64,
            biosGuestPath = fw,
            gpuMode = VmGpuMode.VIRGL,
        )
        assertTrue(virgl.contains("EGL_PLATFORM=surfaceless GALLIUM_DRIVER=virpipe VTEST_SOCKET=$VIRGL_TEST_GUEST_SOCKET"))
        assertTrue(virgl.contains("-device virtio-gpu-gl-pci,drm_native_context=off,xres=1920,yres=1080"))
        assertFalse(virgl.contains("venus=on"))
    }

    @Test
    fun `Windows 11 UEFI pflash and swtpm TPM 2_0 wire cleanly on x86_64 and aarch64`() {
        val win11 = qemuVncCommand(
            diskGuestPath = "/tmp/system-vm/win11.qcow2",
            display = 1,
            memMb = 4096,
            cpus = 4,
            arch = VmArch.X86_64,
            gpuMode = VmGpuMode.VENUS,
            uefiCodeGuestPath = "/usr/share/OVMF/OVMF_CODE_4M.fd",
            uefiVarsGuestPath = "/tmp/system-vm/win11-efivars.fd",
            swtpmSocketGuestPath = "/tmp/system-vm/win11-swtpm.sock",
            useOptQemu11 = true,
        )
        assertTrue(win11.contains("-drive if=pflash,format=raw,readonly=on,file=/usr/share/OVMF/OVMF_CODE_4M.fd"))
        assertTrue(win11.contains("-drive if=pflash,format=raw,file=/tmp/system-vm/win11-efivars.fd"))
        assertFalse("UEFI handles boot order; BIOS -boot c is omitted", win11.contains("-boot c"))
        assertTrue(
            win11.contains("-chardev socket,id=chrtpm,path=/tmp/system-vm/win11-swtpm.sock -tpmdev emulator,id=tpm0,chardev=chrtpm -device tpm-tis,tpmdev=tpm0"),
        )

        val armTpm = qemuVncCommand(
            diskGuestPath = "/tmp/system-vm/arm-win11.qcow2",
            display = 1,
            memMb = 4096,
            cpus = 4,
            arch = VmArch.AARCH64,
            biosGuestPath = fw,
            swtpmSocketGuestPath = "/tmp/system-vm/arm-swtpm.sock",
        )
        assertTrue("aarch64 virt uses sysbus tpm-tis-device", armTpm.contains("-device tpm-tis-device,tpmdev=tpm0"))

        runCatching {
            qemuVncCommand(
                "/tmp/system-vm/win11.qcow2",
                display = 1,
                memMb = 4096,
                cpus = 2,
                uefiCodeGuestPath = "/usr/share/OVMF/OVMF_CODE_4M.fd",
                uefiVarsGuestPath = null,
            )
        }.fold(
            onSuccess = { throw AssertionError("expected refusal when uefiCodeGuestPath has no writable uefiVarsGuestPath") },
            onFailure = { assertTrue("wrong exception: $it", it is IllegalArgumentException) },
        )
    }

    @Test
    fun `qemu11-build assets pin 40-hex virgl commit, 64-hex QEMU 11 tarball sha256, and PRoot surfaceless patch`() {
        val assetsRoot = sequenceOf(
            File("src/main/assets/qemu11-build"),
            File("core/local/src/main/assets/qemu11-build"),
        ).firstOrNull { it.isDirectory }
            ?: throw AssertionError("qemu11-build asset dir not found from working dir ${File(".").absolutePath}")

        val script = File(assetsRoot, "build.sh").readText()
        val patch = File(assetsRoot, "qemu-egl-surfaceless.patch").readText()

        assertTrue("pins 40-char virglrenderer commit", Regex("VIRGL_COMMIT=[0-9a-f]{40}").containsMatchIn(script))
        assertTrue("pins 64-char QEMU 11 tarball sha256", Regex("QEMU_SHA256=[0-9a-f]{64}").containsMatchIn(script))
        assertTrue("enables Venus in virglrenderer", script.contains("-Dvenus=true"))
        assertTrue("patches Surfaceless PBUFFER config", patch.contains("EGL_PLATFORM_SURFACELESS_MESA") && patch.contains("EGL_PBUFFER_BIT"))
    }
}
