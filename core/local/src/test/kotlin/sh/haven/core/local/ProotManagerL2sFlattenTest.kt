package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Regression coverage for #328: a rootfs built under a proot (Termux
 * proot-distro or a Haven guest) carries link2symlink artifacts — dpkg's
 * hard-linked backups became `name -> .l2s.nameNNNN -> payload` symlink
 * chains whose targets are ABSOLUTE paths of the build system. Imported
 * verbatim they dangle (or point into another app's private storage).
 * [flattenL2sLinks] must materialize the payload in place of the link,
 * resolving by basename in the link's own directory.
 */
class ProotManagerL2sFlattenTest {

    private fun tempDir(): File = Files.createTempDirectory("l2s-flatten-test").toFile()

    private fun symlink(at: File, target: String) {
        at.parentFile?.mkdirs()
        Files.createSymbolicLink(at.toPath(), Paths.get(target))
    }

    @Test
    fun `foreign absolute l2s link is materialized from the sibling payload`() {
        val root = tempDir()
        val dpkg = File(root, "var/lib/dpkg").apply { mkdirs() }
        File(dpkg, ".l2s.diversions0001").writeText("diversion payload")
        symlink(
            File(dpkg, "diversions-old"),
            "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/debian/var/lib/dpkg/.l2s.diversions0001",
        )

        assertEquals(1, flattenL2sLinks(root))

        val fixed = File(dpkg, "diversions-old")
        assertFalse(Files.isSymbolicLink(fixed.toPath()))
        assertEquals("diversion payload", fixed.readText())
        forceDeleteRecursively(root)
    }

    @Test
    fun `two-hop chain resolves through the middle to the payload`() {
        val root = tempDir()
        val dpkg = File(root, "var/lib/dpkg").apply { mkdirs() }
        File(dpkg, ".l2s.status0001.0001").writeText("status payload")
        symlink(File(dpkg, ".l2s.status0001"), "/var/lib/dpkg/.l2s.status0001.0001")
        symlink(File(dpkg, "status-old"), "/var/lib/dpkg/.l2s.status0001")

        // Both the user-visible link and the chain middle get materialized.
        assertEquals(2, flattenL2sLinks(root))

        val fixed = File(dpkg, "status-old")
        assertFalse(Files.isSymbolicLink(fixed.toPath()))
        assertEquals("status payload", fixed.readText())
        forceDeleteRecursively(root)
    }

    @Test
    fun `dangling l2s link is left alone`() {
        val root = tempDir()
        val dpkg = File(root, "var/lib/dpkg").apply { mkdirs() }
        symlink(File(dpkg, "status-old"), "/somewhere/else/.l2s.status0001")

        assertEquals(0, flattenL2sLinks(root))
        assertTrue(Files.isSymbolicLink(File(dpkg, "status-old").toPath()))
        forceDeleteRecursively(root)
    }

    @Test
    fun `ordinary symlinks are untouched`() {
        val root = tempDir()
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/busybox").writeText("elf")
        symlink(File(root, "bin"), "usr/bin")
        symlink(File(root, "usr/bin/sh"), "busybox")

        assertEquals(0, flattenL2sLinks(root))
        assertTrue(Files.isSymbolicLink(File(root, "bin").toPath()))
        assertTrue(Files.isSymbolicLink(File(root, "usr/bin/sh").toPath()))
        forceDeleteRecursively(root)
    }

    @Test
    fun `l2s-dir-style chain resolves through the rootfs-top payload store`() {
        // A rootfs built inside a Haven guest with PROOT_L2S_DIR carries its
        // payloads under /.l2s, not next to the link.
        val root = tempDir()
        val l2s = File(root, ".l2s").apply { mkdirs() }
        val dpkg = File(root, "var/lib/dpkg").apply { mkdirs() }
        File(l2s, ".l2s.status0001.0002").writeText("relocated payload")
        symlink(File(l2s, ".l2s.status0001"), "/data/user/0/sh.haven.app/files/proot/rootfs/debian-trixie/.l2s/.l2s.status0001.0002")
        symlink(File(dpkg, "status-old"), "/data/user/0/sh.haven.app/files/proot/rootfs/debian-trixie/.l2s/.l2s.status0001")

        // Only the user-visible link is materialized (the chain lives in
        // .l2s, which is dropped wholesale afterwards).
        assertTrue(flattenL2sLinks(root) >= 1)

        val fixed = File(dpkg, "status-old")
        assertFalse(Files.isSymbolicLink(fixed.toPath()))
        assertEquals("relocated payload", fixed.readText())
        forceDeleteRecursively(root)
    }

    @Test
    fun `leftover l2s dir is dropped after flattening`() {
        val root = tempDir()
        val l2s = File(root, ".l2s").apply { mkdirs() }
        File(l2s, ".l2s.orphan0001.0001").writeText("orphan payload")

        flattenL2sLinks(root)

        assertFalse(l2s.exists())
        forceDeleteRecursively(root)
    }

    @Test
    fun `payload mode is carried onto the materialized file`() {
        val root = tempDir()
        val bin = File(root, "usr/bin").apply { mkdirs() }
        val payload = File(bin, ".l2s.perl0001").apply { writeText("#!perl") }
        payload.setExecutable(true, false)
        symlink(File(bin, "perl"), "/data/data/com.termux/x/usr/bin/.l2s.perl0001")

        assertEquals(1, flattenL2sLinks(root))
        assertTrue(File(bin, "perl").canExecute())
        forceDeleteRecursively(root)
    }
}
