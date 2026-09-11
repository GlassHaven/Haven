package sh.haven.core.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import sh.haven.core.local.proot.RootfsFormat
import sh.haven.core.local.proot.RootfsSource
import sh.haven.core.data.repository.ProotInstallLogRepository

/**
 * Regression coverage for #546: tar producers that write directory entries
 * as `dir/.` — sometimes with a plain regular-file typeflag — used to reach
 * `FileOutputStream("dir/.")`, which POSIX resolves to the already-existing
 * `dir` directory: EISDIR, whole import aborted. Entry names are now
 * normalized before use, so such entries extract as plain directories.
 *
 * Drives [ProotManager.extractTarball] directly with hand-built ustar
 * archives. extractTarball requires `bin/sh` to survive extraction (its own
 * postcondition), so every fixture includes it.
 */
class ProotManagerExtractDirDotTest {

    private lateinit var manager: ProotManager

    @Before
    fun setUp() {
        // The constructor runs init steps against real filesystem paths
        // (legacy-alpine migration, device-model info); give those a temp
        // base dir instead of the relaxed-mock null.
        val tmpBase = Files.createTempDirectory("proot-mgr").toFile()
        manager = ProotManager(
            context = mockk<Context>(relaxed = true) {
                every { filesDir } returns File(tmpBase, "files").apply { mkdirs() }
                every { cacheDir } returns File(tmpBase, "cache").apply { mkdirs() }
            },
            installLogRepository = mockk<ProotInstallLogRepository>(relaxed = true),
        )
    }

    /** Minimal POSIX ustar writer — enough for short-name, short-body entries. */
    private fun tarHeader(name: String, size: Long, typeFlag: Byte, mode: String = "0644"): ByteArray {
        val b = ByteArray(512)
        name.toByteArray().copyInto(b, 0)
        mode.toByteArray().copyInto(b, 100)
        "0000000".toByteArray().copyInto(b, 108) // uid
        "0000000".toByteArray().copyInto(b, 116) // gid
        "%011o".format(size).toByteArray().copyInto(b, 124)
        "00000000000".toByteArray().copyInto(b, 136) // mtime
        b[156] = typeFlag
        "ustar  ".toByteArray().copyInto(b, 257)
        "00".toByteArray().copyInto(b, 265)
        for (i in 148 until 156) b[i] = ' '.code.toByte()
        var sum = 0L
        for (x in b) sum += x.toLong()
        "%06o  ".format(sum).toByteArray().copyInto(b, 148)
        return b
    }

    private fun tarEntry(name: String, content: String, typeFlag: Byte = 0): ByteArray {
        val body = content.toByteArray()
        val pad = 512 - (body.size % 512)
        return tarHeader(name, body.size.toLong(), typeFlag) + body +
            if (pad == 512) ByteArray(0) else ByteArray(pad)
    }

    /** Every fixture carries bin/sh — extractTarball postcondition. */
    private fun gzTar(vararg entries: ByteArray): File = gzTar("", *entries)

    private fun gzTar(prefix: String, vararg entries: ByteArray): File {
        val tar = listOf(
            tarEntry("${prefix}bin/sh", "!/bin/busybox sh\n"),
            *entries,
        ).reduce { a, b -> a + b } + ByteArray(1024)
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar) }
        val dir = Files.createTempDirectory("tar-dirdot").toFile()
        return File(dir, "rootfs.tar.gz").apply { writeBytes(gz.toByteArray()) }
    }

    private fun extract(tarball: File, destDir: File, stripComponents: Int = 0) {
        manager.extractTarball(
            tarball,
            destDir,
            RootfsSource(
                url = "test://rootfs",
                sha256 = "",
                format = RootfsFormat.TAR_GZ,
                stripComponents = stripComponents,
            ),
        )
    }

    @Test
    fun `a regular-file entry named dir-dot extracts as a directory`() {
        val dest = Files.createTempDirectory("tar-dirdot-dest").toFile()
        try {
            val tarball = gzTar(tarEntry("gopd/.", "", typeFlag = 0))
            extract(tarball, dest)

            val gopd = File(dest, "gopd")
            assertTrue("gopd/. must come out as the gopd directory", gopd.isDirectory)
        } finally {
            forceDeleteRecursively(dest)
        }
    }

    @Test
    fun `dir-dot entry followed by its children extracts both`() {
        val dest = Files.createTempDirectory("tar-dirdot-dest").toFile()
        try {
            val tarball = gzTar(
                tarEntry("usr/share/gopd/.", "", typeFlag = 0),
                tarEntry("usr/share/gopd/.", "", typeFlag = '5'.code.toByte()),
                tarEntry("usr/share/gopd/config", "hello"),
            )
            extract(tarball, dest)

            assertTrue(File(dest, "usr/share/gopd").isDirectory)
            assertEquals("hello", File(dest, "usr/share/gopd/config").readText())
        } finally {
            forceDeleteRecursively(dest)
        }
    }

    @Test
    fun `trailing-slash dir entry still extracts as a directory`() {
        val dest = Files.createTempDirectory("tar-dirdot-dest").toFile()
        try {
            val tarball = gzTar(tarEntry("bin/", "", typeFlag = '5'.code.toByte()))
            extract(tarball, dest)

            assertTrue(File(dest, "bin").isDirectory)
        } finally {
            forceDeleteRecursively(dest)
        }
    }

    @Test
    fun `strip-components still works after normalization`() {
        val dest = Files.createTempDirectory("tar-dirdot-dest").toFile()
        try {
            val tarball = gzTar(
                "debian-13/",
                tarEntry("debian-13/usr/share/gopd/.", "", typeFlag = 0),
                tarEntry("debian-13/usr/bin/tool", "payload"),
            )
            extract(tarball, dest, stripComponents = 1)

            assertTrue(File(dest, "usr/share/gopd").isDirectory)
            assertEquals("payload", File(dest, "usr/bin/tool").readText())
        } finally {
            forceDeleteRecursively(dest)
        }
    }
}