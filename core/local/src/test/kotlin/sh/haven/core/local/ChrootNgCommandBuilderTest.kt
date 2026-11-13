package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChrootNgCommandBuilderTest {

    @Test
    fun `GuestEngine fromId defaults to PROOT and parses chroot-ng case-insensitively`() {
        assertEquals(ProotManager.GuestEngine.PROOT, ProotManager.GuestEngine.fromId(null))
        assertEquals(ProotManager.GuestEngine.PROOT, ProotManager.GuestEngine.fromId(""))
        assertEquals(ProotManager.GuestEngine.PROOT, ProotManager.GuestEngine.fromId("unknown"))
        assertEquals(ProotManager.GuestEngine.PROOT, ProotManager.GuestEngine.fromId("proot"))
        assertEquals(ProotManager.GuestEngine.CHROOT_NG, ProotManager.GuestEngine.fromId("chroot-ng"))
        assertEquals(ProotManager.GuestEngine.CHROOT_NG, ProotManager.GuestEngine.fromId("  CHROOT-NG "))
    }

    @Test
    fun `buildChrootNgCommand emits positional rootfs, filters builtin and missing binds, and isolates host TMPDIR`() {
        val tempRoot = Files.createTempDirectory("cng-test-").toFile()
        try {
            val rootfs = tempRoot.resolve("rootfs").apply { mkdirs() }
            val cngTmp = tempRoot.resolve("cng-tmp").apply { mkdirs() }
            val cacheTmp = tempRoot.resolve("cache").apply { mkdirs() }
            val selinuxEmpty = rootfs.resolve("sys/.empty").apply { mkdirs() }
            val existingStorage = tempRoot.resolve("storage-emulated-0").apply { mkdirs() }
            val missingPath = tempRoot.resolve("does-not-exist")

            val inv = buildChrootNgCommand(
                chrootNgBinary = "/data/app/lib/arm64/libchrootng.so",
                rootfsDir = rootfs,
                guestUser = ProotManager.GuestUserSpec.ROOT,
                workdir = "/root",
                rawBindSpecs = listOf(
                    "-b", "/dev",
                    "--bind=/dev/urandom:/dev/random",
                    "-b", "${cacheTmp.absolutePath}:/dev/shm",
                    "--bind=/proc",
                    "--bind=${rootfs.absolutePath}/proc/.version:/proc/version",
                    "-b", "/sys",
                    "--bind=${selinuxEmpty.absolutePath}:/sys/fs/selinux",
                    "-b", "${cacheTmp.absolutePath}:/tmp",
                    "--bind=${existingStorage.absolutePath}:/sdcard:ro",
                    "-b", "${missingPath.absolutePath}:/mnt/missing",
                ),
                guestEnv = listOf(
                    "HOME=/root",
                    "PATH=/usr/bin:/bin",
                    "TMPDIR=/tmp",
                    "TERM=xterm-256color",
                ),
                programAndArgs = listOf("/bin/sh", "-lc", "id"),
                cngTmpDir = cngTmp,
                hostPathExists = { path ->
                    path == "/sys" || java.io.File(path).exists()
                },
            )

            assertEquals("/data/app/lib/arm64/libchrootng.so", inv.binary)
            assertFalse("chroot-ng does not take proot -r flag", "-r" in inv.args)
            assertTrue(rootfs.resolve("root").isDirectory)

            val expectedPrefix = listOf(
                "/data/app/lib/arm64/libchrootng.so",
                "-u",
                "--setuid-root",
                "--setgid-root",
                "-l",
                "--shared-proc",
                "-b", "/sys:/sys",
                "-b", "${selinuxEmpty.absolutePath}:/sys/fs/selinux",
                "-b", "${cacheTmp.absolutePath}:/tmp",
                "-b", "${existingStorage.absolutePath}:/sdcard:ro",
                "-w", "/root",
                "-E", "HOME=/root",
                "-E", "PATH=/usr/bin:/bin",
                "-E", "TMPDIR=/tmp",
                "-E", "TERM=xterm-256color",
                rootfs.absolutePath,
                "/bin/sh", "-lc", "id",
            )
            assertEquals(expectedPrefix, inv.args)
            assertEquals(
                mapOf(
                    "TMPDIR" to cngTmp.absolutePath,
                    "TERM" to "xterm-256color",
                ),
                inv.hostEnv,
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `buildChrootNgCommand uses fake-id for non-root user, deduplicates env, and wraps relative program via env`() {
        val tempRoot = Files.createTempDirectory("cng-user-test-").toFile()
        try {
            val rootfs = tempRoot.resolve("rootfs").apply { mkdirs() }
            val cngTmp = tempRoot.resolve("cng-tmp").apply { mkdirs() }
            val user = ProotManager.GuestUserSpec(
                username = "haven",
                uid = 1000,
                gid = 1000,
                home = "/home/haven",
            )

            val inv = buildChrootNgCommand(
                chrootNgBinary = "/lib/arm64/libchrootng.so",
                rootfsDir = rootfs,
                guestUser = user,
                workdir = user.home,
                rawBindSpecs = emptyList(),
                guestEnv = listOf(
                    "LANG=C.UTF-8",
                    "LANG=en_US.UTF-8",
                    "TMPDIR=/tmp",
                ),
                programAndArgs = listOf("proxychains4", "-q", "-f", "/etc/proxychains-haven.conf", "/bin/sh", "-l"),
                cngTmpDir = cngTmp,
            )

            assertTrue(rootfs.resolve("home/haven").isDirectory)
            assertTrue("--fake-id=1000:1000" in inv.args)
            assertFalse("-u" in inv.args)
            assertTrue("--setuid-root" in inv.args)
            assertTrue("--setgid-root" in inv.args)

            // Duplicate LANG keeps the last override.
            val langEntries = inv.args.zipWithNext().filter { (flag, v) -> flag == "-E" && v.startsWith("LANG=") }
            assertEquals(listOf("-E" to "LANG=en_US.UTF-8"), langEntries)

            // Relative command ("proxychains4") is prefixed with /usr/bin/env after <rootfs>.
            val rootfsIdx = inv.args.indexOf(rootfs.absolutePath)
            assertTrue(rootfsIdx > 0)
            assertEquals(
                listOf("/usr/bin/env", "proxychains4", "-q", "-f", "/etc/proxychains-haven.conf", "/bin/sh", "-l"),
                inv.args.subList(rootfsIdx + 1, inv.args.size),
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `xvncMatches recognizes chroot-ng host processes and enforces whole display token`() {
        val cngCmdline =
            "/data/app/~~abc/sh.haven.app/lib/arm64/libchrootng.so -u --setuid-root --setgid-root -l " +
                "--shared-proc /data/user/0/sh.haven.app/files/proot/rootfs/debian /bin/sh -c " +
                "rm -f /tmp/.X1-lock ; Xvnc :1 -geometry 1280x720 -SecurityTypes None"
        assertTrue(xvncMatches(cngCmdline, display = 1))
        assertTrue(xvncMatches(cngCmdline, display = null))
        assertFalse(xvncMatches(cngCmdline, display = 10))
        assertFalse(xvncMatches("grep Xvnc :1", display = 1))
    }
}
