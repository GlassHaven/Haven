package sh.haven.core.local.proot

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.local.ProotManager

/**
 * [CustomBind] parse/spec coverage (#301). The persistence layer stores
 * each bind as its proot `-b` spec string, so parse(spec) must round-trip.
 */
class CustomBindTest {

    @Test
    fun `host-only spec round-trips`() {
        val b = CustomBind.parse("/storage/emulated/0/Sync")
        assertEquals("/storage/emulated/0/Sync", b.host)
        assertEquals("", b.guest)
        assertEquals("/storage/emulated/0/Sync", b.spec())
    }

    @Test
    fun `host-and-guest spec round-trips`() {
        val b = CustomBind.parse("/storage/emulated/9/Docs:/mnt/docs")
        assertEquals("/storage/emulated/9/Docs", b.host)
        assertEquals("/mnt/docs", b.guest)
        assertEquals("/storage/emulated/9/Docs:/mnt/docs", b.spec())
    }

    @Test
    fun `blank guest collapses to host-only spec`() {
        assertEquals("/data/x", CustomBind("/data/x", "").spec())
        // guest == host is redundant — also collapses.
        assertEquals("/data/x", CustomBind("/data/x", "/data/x").spec())
    }

    @Test
    fun `first colon is the separator (paths have no colon)`() {
        // Absolute paths never contain ':', so splitting on the first colon
        // is unambiguous even for deep guest paths.
        val b = CustomBind.parse("/a/b/c:/x/y/z")
        assertEquals("/a/b/c", b.host)
        assertEquals("/x/y/z", b.guest)
    }

    /**
     * #780: Interactive shell (`LocalSessionManager`) and desktop (`DesktopManager`)
     * PRoot launches must overlay readable synthetic `/proc` files onto
     * `/proc/version`, `/proc/loadavg`, `/proc/stat`, `/proc/uptime`,
     * `/proc/vmstat`, and `/proc/sys/...` so tools like VS Code (`/usr/bin/code`
     * running `grep -qi Microsoft /proc/version`) do not fail with
     * `grep: /proc/version: Permission denied` under Android SELinux.
     */
    @Test
    fun `ensureFakeProcBindArgs seeds readable synthetic proc files and returns short and long bind flags`() {
        val rootfs = Files.createTempDirectory("haven-fake-proc-test").toFile()
        try {
            val shortArgs = ProotManager.ensureFakeProcBindArgs(rootfs, longForm = false).toList()
            assertEquals(16, shortArgs.size)
            assertTrue(shortArgs.chunked(2).all { it[0] == "-b" })

            val versionFile = File(rootfs, "proc/.version")
            assertTrue(versionFile.isFile)
            assertTrue(versionFile.canRead())
            assertTrue(versionFile.readText().contains("Linux version 6.2.1"))
            assertTrue(shortArgs.contains("${versionFile.absolutePath}:/proc/version"))

            val longArgs = ProotManager.ensureFakeProcBindArgs(rootfs, longForm = true).toList()
            assertEquals(8, longArgs.size)
            assertTrue(longArgs.contains("--bind=${versionFile.absolutePath}:/proc/version"))
            assertTrue(longArgs.contains("--bind=${File(rootfs, "proc/.loadavg").absolutePath}:/proc/loadavg"))
            assertTrue(longArgs.contains("--bind=${File(rootfs, "proc/.stat").absolutePath}:/proc/stat"))
            assertTrue(longArgs.contains("--bind=${File(rootfs, "proc/.uptime").absolutePath}:/proc/uptime"))
            assertTrue(longArgs.contains("--bind=${File(rootfs, "proc/.vmstat").absolutePath}:/proc/vmstat"))
            assertTrue(
                longArgs.contains(
                    "--bind=${File(rootfs, "proc/.sysctl_entry_cap_last_cap").absolutePath}:/proc/sys/kernel/cap_last_cap",
                ),
            )
            assertTrue(
                longArgs.contains(
                    "--bind=${File(rootfs, "proc/.sysctl_inotify_max_user_watches").absolutePath}:/proc/sys/fs/inotify/max_user_watches",
                ),
            )
            assertTrue(
                longArgs.contains(
                    "--bind=${File(rootfs, "proc/.sysctl_mmap_min_addr").absolutePath}:/proc/sys/vm/mmap_min_addr",
                ),
            )
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
