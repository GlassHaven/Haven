package sh.haven.feature.sftp.root

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.feature.sftp.SftpViewModel
import sh.haven.feature.sftp.transport.RootFileBackend
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end integration tests for [RootFsDaemon], [RootFsClient], and
 * [RootFileBackend] (#799).
 *
 * Spawns the REAL [RootFsDaemon.main] entry point in a separate child JVM
 * process over OS stdin/stdout pipes — including pre-handshake stdout banner
 * noise to simulate verbose `su` / shell wrappers — rather than mocking IPC.
 */
class RootFileBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spawnCount = AtomicInteger(0)
    private lateinit var client: RootFsClient
    private lateinit var backend: RootFileBackend

    @Before
    fun setUp() {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")!!
        client = RootFsClient(
            processFactory = {
                spawnCount.incrementAndGet()
                ProcessBuilder(
                    javaBin,
                    "-cp",
                    classpath,
                    "sh.haven.feature.sftp.root.RootFsDaemon",
                    "--banner=[magisk] uid=0 global-mnt\n",
                ).start()
            },
            testMarker = true,
        )
        backend = RootFileBackend(client)
    }

    @After
    fun tearDown() = runBlocking {
        client.shutdown()
    }

    @Test
    fun `lazy startup does not spawn daemon until first operation`() = runBlocking {
        assertEquals("Constructing client + backend must not probe su", 0, spawnCount.get())
        val entries = backend.list(tmp.root.absolutePath)
        assertEquals(1, spawnCount.get())
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `mkdir writeBytes readBytes stat list and range openInputStream round-trip over real pipe`() = runBlocking {
        val nestedDir = File(tmp.root, "a/b/c").absolutePath
        backend.mkdir(nestedDir)

        val filePath = "$nestedDir/payload.bin"
        val payload = ByteArray(150_000) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        backend.writeBytes(filePath, payload)
        File(nestedDir, ".hidden").writeText("secret")

        assertArrayEquals(payload, backend.readBytes(filePath))

        val st = backend.stat(filePath)
        assertEquals("payload.bin", st.name)
        assertEquals(payload.size.toLong(), st.size)
        assertFalse(st.isDirectory)

        val offsetSlice = backend.openInputStream(filePath, offset = 100_000L).use { it.readBytes() }
        assertArrayEquals(payload.copyOfRange(100_000, payload.size), offsetSlice)

        val bEntries = backend.list(File(tmp.root, "a/b").absolutePath)
        val cDir = bEntries.single { it.name == "c" }
        assertTrue(cDir.isDirectory)
        assertEquals("2 total children (payload.bin + .hidden)", 2, cDir.childCount)
        assertEquals("1 visible child (payload.bin)", 1, cDir.visibleChildCount)
    }

    @Test
    fun `writeStream streams multi-chunk payload and reports progress`() = runBlocking {
        val dest = File(tmp.root, "streamed.dat").absolutePath
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        var lastReported = 0L
        backend.writeStream(dest, ByteArrayInputStream(payload)) { bytes ->
            lastReported = bytes
        }
        assertEquals(payload.size.toLong(), lastReported)
        assertArrayEquals(payload, File(dest).readBytes())
    }

    @Test
    fun `delete removes symlink without deleting target directory contents`() = runBlocking {
        val realDir = tmp.newFolder("real_target")
        val keepFile = File(realDir, "important.txt").apply { writeText("do not delete") }
        val symlink = File(tmp.root, "sdcard_link")
        Files.createSymbolicLink(symlink.toPath(), realDir.toPath())

        // Listing treats a symlink-to-directory as navigable (isDirectory=true),
        // so SftpViewModel passes isDirectory=true when deleting it — RootFsDaemon
        // MUST unlink only the symlink and leave real_target intact!
        val listed = backend.list(tmp.root.absolutePath).single { it.name == "sdcard_link" }
        assertTrue(listed.isDirectory)
        assertTrue("Expected 'l' prefix on symlink mode: ${listed.permissions}", listed.permissions.startsWith("l"))

        backend.delete(symlink.absolutePath, isDirectory = true)

        assertFalse("Symlink itself should be removed", Files.exists(symlink.toPath()))
        assertTrue("Target directory must survive symlink deletion", realDir.isDirectory)
        assertEquals("do not delete", keepFile.readText())
    }

    @Test
    fun `chmod updates mode and round-trips through SftpViewModel permissionsStringToMode`() = runBlocking {
        val script = File(tmp.root, "run.sh").apply { writeText("#!/bin/sh\n") }
        backend.chmod(script.absolutePath, 0b111_101_000 /* 0750 */)

        val st = backend.stat(script.absolutePath)
        assertEquals("-rwxr-x---", st.permissions)
        assertEquals(0b111_101_000, SftpViewModel.permissionsStringToMode(st.permissions))
    }

    @Test
    fun `rename moves files and directories`() = runBlocking {
        val src = File(tmp.root, "old.txt").apply { writeText("hello") }
        val dst = File(tmp.root, "new.txt")
        backend.rename(src.absolutePath, dst.absolutePath)
        assertFalse(src.exists())
        assertEquals("hello", dst.readText())
    }

    @Test(expected = FileNotFoundException::class)
    fun `stat throws FileNotFoundException on missing path`(): Unit = runBlocking {
        backend.stat(File(tmp.root, "does-not-exist").absolutePath)
    }

    @Test
    fun `shutdown stops helper and next call transparently spawns a new one`() = runBlocking {
        backend.list(tmp.root.absolutePath)
        assertEquals(1, spawnCount.get())
        client.shutdown()
        backend.list(tmp.root.absolutePath)
        assertEquals(2, spawnCount.get())
    }
}
