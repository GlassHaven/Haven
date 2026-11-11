package sh.haven.core.local.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.local.PackageInstallProgressTracker
import sh.haven.core.local.ProotManager

/**
 * Regression coverage for [PackageOps.Pacman] (#718, #808, #809, #814):
 * 1. When an Arch rootfs has not been upgraded across a package split
 *    (e.g. `gcc-libs` -> `libgcc` / `libstdc++` in `archlinux-x86_64`),
 *    `pacman -Sy && pacman -S ...` fails with `conflicting files` /
 *    `exists in filesystem (owned by ...)`. [PackageOps.Pacman.looksLikeStaleDb]
 *    must recognise this so `runInstallWithSelfRepair` runs `pacman -Syyu --noconfirm`.
 * 2. Desktop uninstall on Arch includes packages that base system packages still
 *    depend on (`dbus` required by `systemd`, `xfconf` required by `libxfce4ui`).
 *    `pacman -R` aborts the whole transaction; `pacman -Rsu --noconfirm` removes
 *    unneeded dependencies while skipping packages still required by the system.
 * 3. Under `proot --link2symlink`, GnuPG dotlock fails unless `lock-never ` (with
 *    trailing space so `archlinux-keyring.install`'s `grep -qx 'lock-never'` hook
 *    does not strip it) is present in `/etc/pacman.d/gnupg/gpg.conf`, and
 *    `pacman-key --init && pacman-key --populate` must run before `archlinux-keyring`
 *    can locally sign rotated packager keys (#814).
 */
class PacmanPackageOpsTest {

    private val pacman = PackageOps.forFamily(PackageFamily.PACMAN)

    @Test
    fun `looksLikeStaleDb matches file conflict from un-upgraded package split`() {
        val sampleOutput = """
            error: failed to commit transaction (conflicting files)
            libgcc: /usr/lib/libgcc_s.so.1 exists in filesystem (owned by gcc-libs)
            libstdc++: /usr/lib/libstdc++.so.6 exists in filesystem (owned by gcc-libs)
            Errors occurred, no packages were upgraded.
        """.trimIndent()
        assertTrue(pacman.looksLikeStaleDb(sampleOutput))
    }

    @Test
    fun `looksLikeStaleDb still matches existing dependency and mirror errors`() {
        assertTrue(pacman.looksLikeStaleDb("warning: unable to satisfy dependency 'libstdc++'"))
        assertTrue(pacman.looksLikeStaleDb("error: target not found: foo"))
        assertTrue(pacman.looksLikeStaleDb("error: failed retrieving file 'core.db'"))
        assertFalse(pacman.looksLikeStaleDb("Packages (1) xterm-397-1\nTotal Installed Size: 1.20 MiB"))
    }

    @Test
    fun `removeCmd uses -Rsu --noconfirm so shared dependencies do not abort removal`() {
        val cmd = pacman.removeCmd(listOf("tigervnc", "xfce4", "dbus"))
        assertTrue("Expected -Rsu in removeCmd, got: $cmd", cmd.contains("pacman -Rsu --noconfirm tigervnc xfce4 dbus"))
    }

    @Test
    fun `updateCmd initializes gnupg with trailing-space lock-never before refreshing keyring and glibc`() {
        val cmd = pacman.updateCmd()
        val lockNeverIdx = cmd.indexOf("printf 'lock-never \\n' >> /etc/pacman.d/gnupg/gpg.conf")
        val initIdx = cmd.indexOf("pacman-key --init")
        val populateIdx = cmd.indexOf("pacman-key --populate")
        val killAgentIdx = cmd.indexOf("gpgconf --homedir /etc/pacman.d/gnupg --kill all")
        val keyringIdx = cmd.indexOf("pacman -Sy --noconfirm --needed archlinux-keyring")
        val gccLibsIdx = cmd.indexOf("pacman -S --noconfirm --needed glibc gcc-libs")
        assertTrue("Expected trailing-space lock-never in updateCmd, got: $cmd", lockNeverIdx >= 0)
        assertTrue("Expected pacman-key --init after lock-never, got: $cmd", initIdx > lockNeverIdx)
        assertTrue("Expected pacman-key --populate after --init, got: $cmd", populateIdx > initIdx)
        assertTrue("Expected gpgconf --kill all after --populate, got: $cmd", killAgentIdx > populateIdx)
        assertTrue("Expected archlinux-keyring refresh after keyring init, got: $cmd", keyringIdx > killAgentIdx)
        assertTrue("Expected glibc + gcc-libs update in updateCmd, got: $cmd", gccLibsIdx > keyringIdx)
    }

    @Test
    fun `upgradeCmd and Arch bootstrap hook initialize keyring before refreshing archlinux-keyring and full upgrade`() {
        val upgrade = pacman.upgradeCmd()
        val lockNeverIdx = upgrade.indexOf("printf 'lock-never \\n' >> /etc/pacman.d/gnupg/gpg.conf")
        val keyringIdx = upgrade.indexOf("pacman -Syy --noconfirm --needed archlinux-keyring")
        val suIdx = upgrade.indexOf("pacman -Su --noconfirm")
        assertTrue("Expected lock-never in upgradeCmd, got: $upgrade", lockNeverIdx >= 0)
        assertTrue("Expected archlinux-keyring refresh after lock-never in upgradeCmd, got: $upgrade", keyringIdx > lockNeverIdx)
        assertTrue("Expected pacman -Su after keyring refresh in upgradeCmd, got: $upgrade", suIdx > keyringIdx)

        val hookCmd = DistroCatalog.ARCH_LINUX.postExtractHooks.first { it.id == "pacman -Syu" }.command
        val hookLockNeverIdx = hookCmd.indexOf("printf 'lock-never \\n' >> /etc/pacman.d/gnupg/gpg.conf")
        val hookKeyringIdx = hookCmd.indexOf("pacman -Sy --noconfirm --needed archlinux-keyring")
        val hookSuIdx = hookCmd.indexOf("pacman -Su --noconfirm")
        assertTrue("Expected lock-never in ARCH_LINUX hook, got: $hookCmd", hookLockNeverIdx >= 0)
        assertTrue("Expected archlinux-keyring refresh after lock-never in ARCH_LINUX hook, got: $hookCmd", hookKeyringIdx > hookLockNeverIdx)
        assertTrue("Expected pacman -Su after keyring refresh in ARCH_LINUX hook, got: $hookCmd", hookSuIdx > hookKeyringIdx)
    }

    @Test
    fun `looksLikeStaleDb matches PGP signature, missing keyring key, and GLIBC symbol mismatch errors`() {
        assertTrue(
            pacman.looksLikeStaleDb(
                "error: pam: signature from \"David Runge <dvzrv@archlinux.org>\" is unknown trust\n" +
                    "error: failed to commit transaction (invalid or corrupted package (PGP signature))",
            ),
        )
        assertTrue(
            pacman.looksLikeStaleDb(
                "error: key \"6D42BDD116E0068F\" could not be looked up remotely\n" +
                    "error: required key missing from keyring",
            ),
        )
        assertTrue(
            pacman.looksLikeStaleDb(
                "error: archlinux-keyring: missing required signature",
            ),
        )
        assertTrue(
            pacman.looksLikeStaleDb(
                "pacman: /usr/lib/libc.so.6: version `GLIBC_2.43' not found (required by /usr/lib/libcurl.so.4)",
            ),
        )
    }

    @Test
    fun `PackageInstallProgressTracker parses pacman download and install progress`() {
        val tracker = PackageInstallProgressTracker()
        val sync = tracker.onLine(":: Synchronizing package databases...")
        assertEquals("Synchronizing package databases...", sync?.first)
        assertEquals(0.02f, sync?.second ?: -1f, 0.001f)

        val header = tracker.onLine("Packages (4) tigervnc-1.15.0-1  openbox-3.6.1-11  xterm-397-1  noto-fonts-2025-1")
        assertEquals(4, tracker.totalPackages)
        assertEquals("Resolving 4 packages...", header?.first)

        val dl1 = tracker.onLine(" tigervnc-1.15.0-1-x86_64 downloading...")
        assertEquals("Downloading tigervnc-1.15.0-1-x86_64 (1/4)...", dl1?.first)
        assertNotNull(dl1?.second)
        assertTrue((dl1?.second ?: 0f) in 0.10f..0.30f)

        val inst2 = tracker.onLine("installing openbox...")
        assertEquals("Installing openbox (1/4)...", inst2?.first)
        assertTrue((inst2?.second ?: 0f) in 0.50f..0.70f)

        val hooks = tracker.onLine(":: Running post-transaction hooks...")
        assertEquals("Running post-install hooks...", hooks?.first)
        assertEquals(0.96f, hooks?.second ?: -1f, 0.001f)
    }

    @Test
    fun `PackageInstallProgressTracker parses apt apk and xbps output`() {
        val apt = PackageInstallProgressTracker()
        val summary = apt.onLine("0 upgraded, 10 newly installed, 0 to remove and 0 not upgraded.")
        assertEquals("Downloading 10 packages...", summary?.first)
        val get5 = apt.onLine("Get:5 http://deb.debian.org/debian bookworm/main arm64 xfce4-panel arm64 4.18.2-1 [912 kB]")
        assertEquals("Downloading xfce4-panel (5/10)...", get5?.first)
        assertEquals(0.275f, get5?.second ?: -1f, 0.01f)
        val unpack = apt.onLine("Unpacking xfce4-panel (4.18.2-1) ...")
        assertEquals("Unpacking xfce4-panel (1/10)...", unpack?.first)

        val apk = PackageInstallProgressTracker()
        val apkStep = apk.onLine("(6/12) Installing openbox (3.6.1-r12)")
        assertEquals("Installing openbox (6/12)...", apkStep?.first)
        assertEquals(0.50f, apkStep?.second ?: -1f, 0.01f)

        val xbps = PackageInstallProgressTracker()
        assertEquals("Downloading packages..." to 0.10f, xbps.onLine("[*] Downloading binary packages"))
        assertEquals("Configuring packages..." to 0.85f, xbps.onLine("[*] Configuring unpacked packages"))
    }

    @Test
    fun `Arch bootstrap keyring and system upgrade phases emit trackable progress lines`() {
        val cmd = pacman.updateCmd()
        val echoInitIdx = cmd.indexOf(":: Initializing pacman keyring...")
        val initIdx = cmd.indexOf("pacman-key --init")
        val echoPopIdx = cmd.indexOf(":: Populating Arch Linux keyring...")
        val populateIdx = cmd.indexOf("pacman-key --populate")
        assertTrue("Expected keyring init progress marker before pacman-key --init", echoInitIdx in 0 until initIdx)
        assertTrue("Expected keyring populate progress marker before pacman-key --populate", echoPopIdx in (initIdx + 1) until populateIdx)

        val tracker = PackageInstallProgressTracker()
        assertEquals(
            "Initializing pacman keyring..." to 0.01f,
            tracker.onLine(":: Initializing pacman keyring..."),
        )
        assertEquals(
            "Populating Arch Linux keyring..." to 0.02f,
            tracker.onLine(":: Populating Arch Linux keyring..."),
        )
        assertEquals(
            "Starting system upgrade..." to 0.04f,
            tracker.onLine(":: Starting full system upgrade..."),
        )
        assertEquals(
            "Generating locales..." to null,
            tracker.onLine("Generating locales..."),
        )

        val initState = ProotManager.SetupState.Initializing(
            step = "pacman -Syu: Downloading mesa (12/85)...",
            progress = 0.25f,
        )
        assertEquals(0.25f, initState.progress ?: -1f, 0.001f)
    }
}
