package sh.haven.core.local.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [PackageOps.Pacman] (#718):
 * 1. When an Arch rootfs has not been upgraded across a package split
 *    (e.g. `gcc-libs` -> `libgcc` / `libstdc++` in `archlinux-x86_64`),
 *    `pacman -Sy && pacman -S ...` fails with `conflicting files` /
 *    `exists in filesystem (owned by ...)`. [PackageOps.Pacman.looksLikeStaleDb]
 *    must recognise this so `runInstallWithSelfRepair` runs `pacman -Syyu --noconfirm`.
 * 2. Desktop uninstall on Arch includes packages that base system packages still
 *    depend on (`dbus` required by `systemd`, `xfconf` required by `libxfce4ui`).
 *    `pacman -R` aborts the whole transaction; `pacman -Rsu --noconfirm` removes
 *    unneeded dependencies while skipping packages still required by the system.
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
    fun `updateCmd refreshes archlinux-keyring and updates glibc and gcc-libs before installs`() {
        val cmd = pacman.updateCmd()
        val keyringIdx = cmd.indexOf("pacman -Sy --noconfirm --needed archlinux-keyring")
        val gccLibsIdx = cmd.indexOf("pacman -S --noconfirm --needed glibc gcc-libs")
        assertTrue("Expected archlinux-keyring refresh in updateCmd, got: $cmd", keyringIdx >= 0)
        assertTrue("Expected glibc + gcc-libs update in updateCmd, got: $cmd", gccLibsIdx > keyringIdx)
    }

    @Test
    fun `upgradeCmd and Arch bootstrap hook refresh archlinux-keyring before full upgrade`() {
        val upgrade = pacman.upgradeCmd()
        val keyringIdx = upgrade.indexOf("pacman -Syy --noconfirm --needed archlinux-keyring")
        val suIdx = upgrade.indexOf("pacman -Su --noconfirm")
        assertTrue("Expected archlinux-keyring refresh in upgradeCmd, got: $upgrade", keyringIdx >= 0)
        assertTrue("Expected pacman -Su after keyring refresh in upgradeCmd, got: $upgrade", suIdx > keyringIdx)

        val hookCmd = DistroCatalog.ARCH_LINUX.postExtractHooks.first { it.id == "pacman -Syu" }.command
        val hookKeyringIdx = hookCmd.indexOf("pacman -Sy --noconfirm --needed archlinux-keyring")
        val hookSuIdx = hookCmd.indexOf("pacman -Su --noconfirm")
        assertTrue("Expected archlinux-keyring refresh in ARCH_LINUX hook, got: $hookCmd", hookKeyringIdx >= 0)
        assertTrue("Expected pacman -Su after keyring refresh in ARCH_LINUX hook, got: $hookCmd", hookSuIdx > hookKeyringIdx)
    }

    @Test
    fun `looksLikeStaleDb matches PGP signature and GLIBC symbol mismatch errors`() {
        assertTrue(
            pacman.looksLikeStaleDb(
                "error: pam: signature from \"David Runge <dvzrv@archlinux.org>\" is unknown trust\n" +
                    "error: failed to commit transaction (invalid or corrupted package (PGP signature))",
            ),
        )
        assertTrue(
            pacman.looksLikeStaleDb(
                "pacman: /usr/lib/libc.so.6: version `GLIBC_2.43' not found (required by /usr/lib/libcurl.so.4)",
            ),
        )
    }
}
