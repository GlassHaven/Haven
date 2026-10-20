package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.core.local.ProotManager.DesktopEnvironment
import java.io.File

/**
 * Regression coverage for #718:
 * 1. Uninstalling the last desktop on a rootfs must not delete `root/.haven-desktop`
 *    and fall back to binary probing (`usr/bin/xterm`), which falsely marks the
 *    uninstalled desktop as still installed whenever `xterm` or another binary
 *    remains on disk. Writing an empty marker file (`""`) records an explicit
 *    empty installed set.
 * 2. Non-root guest user provisioning (`provisionGuestUser` / `sanitizeGuestUsername`)
 *    creates `/etc/passwd`, `/etc/group`, `/etc/shadow`, `/etc/sudoers.d/<user>`,
 *    and `/home/<user>` idempotently for PRoot `-i 1000:1000`.
 */
class DesktopInstalledMarkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `empty marker file reports zero installed desktops even when xterm binary remains`() {
        val rootfs = tmp.newFolder("rootfs")
        val xterm = File(rootfs, "usr/bin/xterm").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\n")
        }
        assertTrue(xterm.exists())

        // Legacy rootfs without a marker falls back to binary probing (CUSTOM_X11 and X11_NATIVE use usr/bin/xterm).
        val legacy = ProotManager.installedDesktopsInRootfs(rootfs)
        assertTrue("Legacy rootfs without marker should detect CUSTOM_X11 via binary fallback", legacy.contains(DesktopEnvironment.CUSTOM_X11))

        // Once the marker file exists and is empty (after uninstalling the last desktop),
        // binary fallback must NOT resurrect CUSTOM_X11 / X11_NATIVE.
        val marker = File(rootfs, "root/.haven-desktop").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        assertTrue(marker.exists())
        val afterUninstall = ProotManager.installedDesktopsInRootfs(rootfs)
        assertTrue("Empty marker must report no installed desktops, got: $afterUninstall", afterUninstall.isEmpty())
    }

    @Test
    fun `populated marker file reports only marked desktops whose binary exists`() {
        val rootfs = tmp.newFolder("rootfs-marked")
        File(rootfs, "usr/bin/xterm").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\n")
        }
        File(rootfs, "usr/bin/startxfce4").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\n")
        }
        File(rootfs, "root/.haven-desktop").apply {
            parentFile?.mkdirs()
            writeText("XFCE4\n")
        }

        val installed = ProotManager.installedDesktopsInRootfs(rootfs)
        assertEquals(setOf(DesktopEnvironment.XFCE4), installed)
    }

    @Test
    fun `sanitizeGuestUsername normalizes valid POSIX usernames and rejects root or invalid chars`() {
        assertEquals("", ProotManager.sanitizeGuestUsername(""))
        assertEquals("", ProotManager.sanitizeGuestUsername("   "))
        assertEquals("", ProotManager.sanitizeGuestUsername("root"))
        assertEquals("", ProotManager.sanitizeGuestUsername("ROOT"))
        assertEquals("haven", ProotManager.sanitizeGuestUsername("Haven"))
        assertEquals("arch_user-1", ProotManager.sanitizeGuestUsername("arch_user-1"))
        assertEquals("", ProotManager.sanitizeGuestUsername("1badstart"))
        assertEquals("", ProotManager.sanitizeGuestUsername("bad space"))
    }

    @Test
    fun `provisionGuestUser writes passwd group shadow sudoers and home directory idempotently`() {
        val rootfs = tmp.newFolder("rootfs-user")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        File(rootfs, "etc/group").writeText("root:x:0:\nwheel:x:10:root\n")
        File(rootfs, "etc/shadow").writeText("root:*:19000:0:99999:7:::\n")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("#!/bin/sh\n")

        val spec = ProotManager.provisionGuestUser(rootfs, "archuser")
        assertFalse(spec.isRoot)
        assertEquals("archuser", spec.username)
        assertEquals(1000, spec.uid)
        assertEquals(1000, spec.gid)
        assertEquals("/home/archuser", spec.home)
        assertEquals("-i 1000:1000", spec.prootIdentityFlag)

        assertTrue(File(rootfs, "home/archuser").isDirectory)
        val passwd = File(rootfs, "etc/passwd").readText()
        assertTrue(passwd.contains("archuser:x:1000:1000:Haven user:/home/archuser:/bin/bash"))
        val group = File(rootfs, "etc/group").readText()
        assertTrue(group.contains("archuser:x:1000:"))
        val sudoers = File(rootfs, "etc/sudoers.d/archuser")
        assertTrue(sudoers.exists())
        assertTrue(sudoers.readText().contains("archuser ALL=(ALL:ALL) NOPASSWD: ALL"))

        // Second call is idempotent (does not duplicate passwd lines).
        ProotManager.provisionGuestUser(rootfs, "archuser")
        val passwdLines = File(rootfs, "etc/passwd").readLines().filter { it.startsWith("archuser:") }
        assertEquals(1, passwdLines.size)

        // Blank or "root" returns root spec without modifying passwd.
        val rootSpec = ProotManager.provisionGuestUser(rootfs, "")
        assertTrue(rootSpec.isRoot)
        assertEquals("-0", rootSpec.prootIdentityFlag)
    }

    @Test
    fun `provisionGuestUser configures PAM su, unlocks root shadow, and writes apt nosandbox (#721)`() {
        val rootfs = tmp.newFolder("rootfs-721")
        File(rootfs, "etc/pam.d").mkdirs()
        File(rootfs, "etc/apt/apt.conf.d").mkdirs()
        File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        File(rootfs, "etc/group").writeText("root:x:0:\n")
        File(rootfs, "etc/shadow").writeText("root:*:19000:0:99999:7:::\n")
        File(rootfs, "etc/pam.d/su").writeText(
            "auth       sufficient pam_rootok.so\n" +
                "@include common-auth\n",
        )

        ProotManager.provisionGuestUser(rootfs, "debianuser")

        val pamSu = File(rootfs, "etc/pam.d/su").readText()
        assertTrue("PAM su must include pam_permit.so after pam_rootok.so", pamSu.contains("pam_permit.so"))
        val shadow = File(rootfs, "etc/shadow").readText()
        assertTrue("root shadow password field must be unlocked", shadow.contains("root::19000:0:99999:7:::"))
        val aptConf = File(rootfs, "etc/apt/apt.conf.d/99haven-nosandbox")
        assertTrue("apt nosandbox config must be written", aptConf.isFile)
        assertEquals("APT::Sandbox::User \"root\";\n", aptConf.readText())
    }

    @Test
    fun `isRootfsComplete rejects truncated APT rootfs missing dpkg cputable (#721)`() {
        val rootfs = tmp.newFolder("rootfs-truncated-apt")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("#!/bin/sh\n")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/apt").writeText("#!/bin/sh\n")
        File(rootfs, ROOTFS_READY_MARKER).writeText("ready\n")

        assertFalse(
            "APT rootfs missing usr/share/dpkg/cputable must be treated as incomplete (#721)",
            isRootfsComplete(rootfs),
        )

        File(rootfs, "usr/share/dpkg").mkdirs()
        File(rootfs, "usr/share/dpkg/cputable").writeText("arm64\taarch64\n")
        assertTrue(
            "Complete APT rootfs with usr/share/dpkg/cputable must be treated as complete",
            isRootfsComplete(rootfs),
        )
    }

    @Test
    fun `resolveGuestLocale falls back to C_UTF-8 when glibc locale is not yet generated (#721)`() {
        val alpineRootfs = tmp.newFolder("rootfs-alpine")
        assertEquals("en_US.UTF-8", resolveGuestLocale(alpineRootfs, "en_US.UTF-8"))

        val unconfiguredGlibcRootfs = tmp.newFolder("rootfs-glibc-unconfigured")
        File(unconfiguredGlibcRootfs, "usr/lib/locale/C.utf8").mkdirs()
        assertEquals("C.UTF-8", resolveGuestLocale(unconfiguredGlibcRootfs, "en_US.UTF-8"))

        File(unconfiguredGlibcRootfs, "usr/lib/locale/locale-archive").writeText("archive")
        assertEquals("en_US.UTF-8", resolveGuestLocale(unconfiguredGlibcRootfs, "en_US.UTF-8"))
    }
}
