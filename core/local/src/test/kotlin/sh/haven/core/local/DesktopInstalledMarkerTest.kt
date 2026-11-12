package sh.haven.core.local

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.core.data.repository.ProotInstallLogRepository
import sh.haven.core.local.ProotManager.DesktopEnvironment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    @Test
    fun `provisionGuestUser merges newly seeded desktop configs into existing user dot-config without overwriting edits (#827)`() {
        val rootfs = tmp.newFolder("rootfs-827-config")
        File(rootfs, "etc/passwd").apply { parentFile?.mkdirs(); writeText("root:x:0:0:root:/root:/bin/sh\n") }
        File(rootfs, "etc/group").writeText("root:x:0:\n")
        // User already ran Xfce4 first, so /home/nazy/.config/xfce4 exists before Sway is installed.
        File(rootfs, "home/nazy/.config/xfce4").apply { mkdirs() }
        File(rootfs, "home/nazy/.config/xfce4/panel.xml").writeText("user-panel")
        File(rootfs, "home/nazy/.config/sway").apply { mkdirs() }
        File(rootfs, "home/nazy/.config/sway/custom").writeText("keep-me")

        // Installing Sway later writes /root/.config/sway/config.
        File(rootfs, "root/.config/sway").apply { mkdirs() }
        File(rootfs, "root/.config/sway/config").writeText("output HEADLESS-1 enable\n")
        File(rootfs, "root/.config/sway/custom").writeText("default-do-not-overwrite")

        ProotManager.provisionGuestUser(rootfs, "nazy")

        assertEquals(
            "Newly seeded sway/config must be copied into existing /home/nazy/.config",
            "output HEADLESS-1 enable\n",
            File(rootfs, "home/nazy/.config/sway/config").readText(),
        )
        assertEquals(
            "Existing user files under /home/nazy/.config must not be overwritten",
            "keep-me",
            File(rootfs, "home/nazy/.config/sway/custom").readText(),
        )
        assertEquals("user-panel", File(rootfs, "home/nazy/.config/xfce4/panel.xml").readText())

        val machineId = File(rootfs, "etc/machine-id").readText().trim()
        assertEquals("machine-id must be a 32-hex UUID (#827)", 32, machineId.length)
        assertTrue(machineId.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `proot sandbox env disables Mozilla content and utility sandboxes alongside Electron and Glycin (#827)`() {
        val keys = ProotManager.PROOT_SANDBOX_ENV.associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        assertEquals("i-know-the-risks", keys["GLYCIN_DISABLE_SANDBOX"])
        assertEquals("1", keys["ELECTRON_DISABLE_SANDBOX"])
        assertEquals("1", keys["MOZ_DISABLE_CONTENT_SANDBOX"])
        assertEquals("1", keys["MOZ_DISABLE_GMP_SANDBOX"])
        assertEquals("1", keys["MOZ_DISABLE_RDD_SANDBOX"])
        assertEquals("1", keys["MOZ_DISABLE_SOCKET_PROCESS_SANDBOX"])
        assertEquals("1", keys["MOZ_DISABLE_UTILITY_SANDBOX"])
    }

    @Test
    fun `per-distro default login user isolates distros, honours legacy fallback, and clears on delete (#567)`() {
        val filesDir = tmp.newFolder("files-567")
        val cacheDir = tmp.newFolder("cache-567")
        val store = ConcurrentHashMap<String, Any?>()
        val prefs = inMemoryPrefs(store)
        val ctx = mockk<Context>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
            every { this@mockk.cacheDir } returns cacheDir
            every { getSharedPreferences("proot-manager", Context.MODE_PRIVATE) } returns prefs
        }

        val mgr = ProotManager(
            context = ctx,
            installLogRepository = mockk<ProotInstallLogRepository>(relaxed = true),
        )

        // Fresh state: all distros default to root ("").
        assertEquals("", mgr.guestUsernameFor("archlinux"))
        assertEquals("", mgr.guestUsernameFor("alpine-3.21"))
        assertFalse(mgr.hasGuestUsernameOverride("archlinux"))

        // Setting Arch Linux's user leaves Alpine on root.
        val revBefore = mgr.guestUserRev.value
        mgr.setGuestUsernameFor("archlinux", "ArchUser")
        assertTrue(mgr.guestUserRev.value > revBefore)
        assertTrue(mgr.hasGuestUsernameOverride("archlinux"))
        assertEquals("archuser", mgr.guestUsernameFor("archlinux"))
        assertEquals("", mgr.guestUsernameFor("alpine-3.21"))

        val archRootfs = File(filesDir, "proot/rootfs/archlinux").apply {
            File(this, "etc").mkdirs()
            File(this, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
            File(this, "etc/group").writeText("root:x:0:\n")
            File(this, "etc/shadow").writeText("root:*:19000:0:99999:7:::\n")
            File(this, "bin").mkdirs()
            File(this, "bin/bash").writeText("#!/bin/sh\n")
        }
        val alpineRootfs = File(filesDir, "proot/rootfs/alpine-3.21").apply {
            File(this, "etc").mkdirs()
            File(this, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/ash\n")
        }

        val archSpec = mgr.ensureGuestUser(archRootfs, "archlinux")
        assertEquals("archuser", archSpec.username)
        assertFalse(archSpec.isRoot)
        assertTrue(File(archRootfs, "home/archuser").isDirectory)

        val alpineSpec = mgr.ensureGuestUser(alpineRootfs, "alpine-3.21")
        assertTrue(alpineSpec.isRoot)
        assertFalse(File(alpineRootfs, "home").exists())

        // Legacy global guest_username (#718) acts as fallback for distros without an override,
        // while an explicit "root" override on one distro keeps that distro as root.
        mgr.setGuestUsername("haven")
        assertEquals("haven", mgr.guestUsernameFor("debian-trixie"))
        mgr.setGuestUsernameFor("alpine-3.21", "root")
        assertTrue(mgr.hasGuestUsernameOverride("alpine-3.21"))
        assertEquals("", mgr.guestUsernameFor("alpine-3.21"))

        // Clearing an override restores fallback; deleting a distro removes its per-distro user key.
        mgr.clearGuestUsernameFor("alpine-3.21")
        assertFalse(mgr.hasGuestUsernameOverride("alpine-3.21"))
        assertEquals("haven", mgr.guestUsernameFor("alpine-3.21"))

        mgr.deleteDistro("archlinux")
        assertFalse(mgr.hasGuestUsernameOverride("archlinux"))
        assertEquals("haven", mgr.guestUsernameFor("archlinux"))
    }

    private fun inMemoryPrefs(backing: ConcurrentHashMap<String, Any?>): SharedPreferences =
        mockk<SharedPreferences>(relaxed = true) {
            every { contains(any()) } answers { backing.containsKey(firstArg<String>()) }
            every { getString(any(), any()) } answers {
                val key = firstArg<String>()
                if (backing.containsKey(key)) backing[key] as? String else secondArg()
            }
            every { getBoolean(any(), any()) } answers {
                val key = firstArg<String>()
                (backing[key] as? Boolean) ?: secondArg()
            }
            every { edit() } answers {
                object : SharedPreferences.Editor {
                    private val staged = LinkedHashMap<String, Any?>()
                    private val removals = HashSet<String>()
                    private var clearAll = false

                    override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                        removals.remove(key)
                        staged[key] = value
                    }
                    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                        removals.remove(key)
                        staged[key] = values?.toSet()
                    }
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                        removals.remove(key)
                        staged[key] = value
                    }
                    override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                        removals.remove(key)
                        staged[key] = value
                    }
                    override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                        removals.remove(key)
                        staged[key] = value
                    }
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                        removals.remove(key)
                        staged[key] = value
                    }
                    override fun remove(key: String): SharedPreferences.Editor = apply {
                        staged.remove(key)
                        removals.add(key)
                    }
                    override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
                    override fun commit(): Boolean {
                        if (clearAll) backing.clear()
                        removals.forEach { backing.remove(it) }
                        staged.forEach { (k, v) -> if (v == null) backing.remove(k) else backing[k] = v }
                        return true
                    }
                    override fun apply() {
                        commit()
                    }
                }
            }
        }
}
