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
    private fun tarHeader(
        name: String,
        size: Long,
        typeFlag: Byte,
        mode: String = "0644",
        linkName: String = "",
    ): ByteArray {
        val b = ByteArray(512)
        name.toByteArray().copyInto(b, 0)
        mode.toByteArray().copyInto(b, 100)
        "0000000".toByteArray().copyInto(b, 108) // uid
        "0000000".toByteArray().copyInto(b, 116) // gid
        "%011o".format(size).toByteArray().copyInto(b, 124)
        "00000000000".toByteArray().copyInto(b, 136) // mtime
        b[156] = typeFlag
        if (linkName.isNotEmpty()) {
            linkName.toByteArray().copyInto(b, 157)
        }
        "ustar  ".toByteArray().copyInto(b, 257)
        "00".toByteArray().copyInto(b, 265)
        for (i in 148 until 156) b[i] = ' '.code.toByte()
        var sum = 0L
        for (x in b) sum += x.toLong()
        "%06o  ".format(sum).toByteArray().copyInto(b, 148)
        return b
    }

    private fun tarEntryBytes(name: String, body: ByteArray, typeFlag: Byte = 0): ByteArray {
        val pad = 512 - (body.size % 512)
        return tarHeader(name, body.size.toLong(), typeFlag) + body +
            if (pad == 512) ByteArray(0) else ByteArray(pad)
    }

    private fun tarHardLink(name: String, linkTarget: String): ByteArray =
        tarHeader(name, 0L, typeFlag = '1'.code.toByte(), mode = "0755", linkName = linkTarget)

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

    @Test
    fun `pax extended header with trailing slash in truncated ustar name extracts long path and subsequent entries`() {
        val dest = Files.createTempDirectory("tar-pax-dest").toFile()
        try {
            val fullPath = "debian-trixie-aarch64/usr/share/doc/libustr-1.0-1/examples/autoconf-ustr/m4-500-shr-mapped/ustr-import.m4.in"
            val paxRecordBody = "path=$fullPath\n"
            // Compute POSIX PAX record length prefix ("%d %s", totalLen, paxRecordBody)
            var totalLen = paxRecordBody.length + 3
            while ((totalLen.toString().length + 1 + paxRecordBody.length) != totalLen) {
                totalLen = totalLen.toString().length + 1 + paxRecordBody.length
            }
            val paxPayload = "$totalLen $paxRecordBody"
            // 100-char ustar name ending in '/' — the exact trigger from debian-trixie-aarch64-pd-v4.29.0.tar.xz (#721)
            val truncatedPaxHeaderName = "debian-trixie-aarch64/usr/share/doc/libustr-1.0-1/examples/autoconf-ustr/m4-500-shr-mapped/PaxHeader/"
                .also { assertEquals(101, it.length) }
                .drop(1) // 100 chars ending in '/'
            assertEquals('/', truncatedPaxHeaderName.last())

            val tarball = gzTar(
                "debian-trixie-aarch64/",
                tarEntry(truncatedPaxHeaderName, paxPayload, typeFlag = 'x'.code.toByte()),
                tarEntry("debian-trixie-aarch64/usr/share/doc/truncated", "m4-content", typeFlag = '0'.code.toByte()),
                tarEntry("debian-trixie-aarch64/usr/share/dpkg/cputable", "arm64\taarch64\n", typeFlag = '0'.code.toByte()),
            )
            extract(tarball, dest, stripComponents = 1)

            val longFile = File(dest, "usr/share/doc/libustr-1.0-1/examples/autoconf-ustr/m4-500-shr-mapped/ustr-import.m4.in")
            assertTrue("PAX path override must determine extracted file path", longFile.isFile)
            assertEquals("m4-content", longFile.readText())
            val cpuTable = File(dest, "usr/share/dpkg/cputable")
            assertTrue("Subsequent entries after PAX header must be extracted (#721)", cpuTable.isFile)
            assertEquals("arm64\taarch64\n", cpuTable.readText())
        } finally {
            forceDeleteRecursively(dest)
        }
    }

    @Test
    fun `large hard link extracts as relative symlink while small hard link copies`() {
        val dest = Files.createTempDirectory("tar-hardlink-dest").toFile()
        try {
            val largePayload = ByteArray(1_048_576) { (it and 0xFF).toByte() }
            val tarball = gzTar(
                tarEntryBytes("usr/bin/coreutils", largePayload),
                tarHardLink("usr/lib/cargo/bin/coreutils/ls", "usr/bin/coreutils"),
                tarEntry("etc/small-orig", "hello-small"),
                tarHardLink("etc/small-link", "etc/small-orig"),
            )
            extract(tarball, dest)

            val lsLink = File(dest, "usr/lib/cargo/bin/coreutils/ls").toPath()
            assertTrue("Large hard link (>= 1 MB) must be created as a symlink", Files.isSymbolicLink(lsLink))
            assertEquals("../../../../bin/coreutils", Files.readSymbolicLink(lsLink).toString())
            assertEquals(1_048_576L, lsLink.toFile().length())

            val smallLink = File(dest, "etc/small-link").toPath()
            assertTrue("Small hard link (< 1 MB) must remain an independent regular file", !Files.isSymbolicLink(smallLink))
            assertEquals("hello-small", smallLink.toFile().readText())
        } finally {
            forceDeleteRecursively(dest)
        }
    }

    @Test
    fun `ensureTunnelGuestHelper and guestTunnelEnvVars surface tunnel socks5 and auto proxy`() {
        val dest = Files.createTempDirectory("tar-tunnel-helper-dest").toFile()
        try {
            File(dest, "bin").mkdirs()
            ProotManager.ensureAptSandboxConfig(dest)

            val withTunnel = File(dest, "usr/local/bin/with-tunnel")
            val profileHook = File(dest, "etc/profile.d/haven-tunnel.sh")
            assertTrue("with-tunnel script must be created", withTunnel.isFile)
            assertTrue("haven-tunnel.sh profile hook must be created", profileHook.isFile)
            assertTrue(withTunnel.readText().contains("socks5h://"))

            assertTrue(manager.guestTunnelEnvVars().isEmpty())

            val cacheDir = manager.ensureDevShm().let { File(it).parentFile!! }
            File(cacheDir, ".haven_tunnel_socks5").writeText("127.0.0.1:45678\n")
            val varsWithoutAuto = manager.guestTunnelEnvVars()
            assertEquals("127.0.0.1:45678", varsWithoutAuto["HAVEN_TUNNEL_SOCKS5"])
            assertEquals("10.0.2.2:45678", varsWithoutAuto["HAVEN_TUNNEL_QEMU_SOCKS5"])
            assertTrue("ALL_PROXY must not be set when auto-proxy is off", !varsWithoutAuto.containsKey("ALL_PROXY"))

            File(cacheDir, ".haven_tunnel_auto_proxy").writeText("1\n")
            val varsWithAuto = manager.guestTunnelEnvVars()
            assertEquals("socks5h://127.0.0.1:45678", varsWithAuto["ALL_PROXY"])
            assertEquals("socks5h://127.0.0.1:45678", varsWithAuto["HTTPS_PROXY"])
            assertEquals("socks5h://127.0.0.1:45678", varsWithAuto["HTTP_PROXY"])
        } finally {
            forceDeleteRecursively(dest)
        }
    }
}