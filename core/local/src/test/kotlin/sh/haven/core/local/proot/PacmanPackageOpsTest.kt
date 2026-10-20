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
}
