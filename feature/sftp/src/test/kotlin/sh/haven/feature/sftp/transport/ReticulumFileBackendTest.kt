package sh.haven.feature.sftp.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.reticulum.DiscoveredDestination
import sh.haven.core.reticulum.ReticulumExecSession
import sh.haven.core.reticulum.ReticulumIdentityImport
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.core.reticulum.RnshShellSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * Unit tests for [ReticulumFileBackend] — the `ls -la` parser (GNU and
 * busybox dialects), the HRC sentinel exit-code path, and shell quoting.
 * Uses a scripted fake transport; no network.
 */
class ReticulumFileBackendTest {

    /** Captures the last command argv and replays a scripted stdout/stderr. */
    private class FakeTransport(
        private val stdout: ByteArray,
        private val stderr: ByteArray,
    ) : ReticulumTransport {
        var lastCommand: List<String>? = null
            private set
        /** Captures everything fed over stdin (the upload script). */
        val stdinFed = java.io.ByteArrayOutputStream()

        override suspend fun execCommand(
            destinationHash: String,
            command: List<String>,
        ): ReticulumExecSession {
            lastCommand = command
            return object : ReticulumExecSession {
                override val stdout: Flow<ByteArray> = flowOf(this@FakeTransport.stdout)
                override val stderr: Flow<ByteArray> = flowOf(this@FakeTransport.stderr)
                override val exitCode = CompletableDeferred(0)
                override suspend fun writeStdin(data: ByteArray) { stdinFed.write(data) }
                override suspend fun closeStdin() {}
                override fun close() {}
            }
        }

        // Unused surface.
        override suspend fun init(c: String, h: String, p: Int, n: String?, k: String?, d: ((String, Int, Int) -> java.net.Socket)?): String = ""
        override val isInitialised: Boolean = true
        override suspend fun openSession(destinationHash: String, rows: Int, cols: Int): RnshShellSession = throw NotImplementedError()
        override val discoveredDestinations: StateFlow<List<DiscoveredDestination>> = MutableStateFlow(emptyList())
        override suspend fun requestPath(destinationHashHex: String): Boolean = true
        override suspend fun probeSideband(configDir: String): Boolean = false
        override suspend fun clientIdentityHash(configDir: String): String? =
            throw NotImplementedError("identity storage is not part of this test")

        override suspend fun importClientIdentity(
            configDir: String,
            source: java.io.File,
        ): ReticulumIdentityImport =
            throw NotImplementedError("identity storage is not part of this test")

        override suspend fun closeAll() {}
    }

    private fun backend(stdout: String, stderr: String = "HRC=0") =
        ReticulumFileBackend(FakeTransport(stdout.toByteArray(), stderr.toByteArray()), "deadbeef")

    @Test
    fun `parses GNU ls -la`() = runBlocking {
        val out = """
            total 12
            drwxr-xr-x  3 ian  ian  4096 May 31 13:00 .
            drwxr-xr-x 20 ian  ian  4096 May 31 12:00 ..
            -rw-r--r--  1 ian  ian   123 May 31 13:00 hello.txt
            drwxr-xr-x  2 ian  ian  4096 May 31 13:00 subdir
            lrwxrwxrwx  1 ian  ian     5 May 31 13:00 link -> there
        """.trimIndent()
        val entries = backend(out).list("/tmp/x")

        // "." and ".." dropped; symlink kept as a file named "link".
        assertEquals(listOf("hello.txt", "subdir", "link"), entries.map { it.name })
        val hello = entries.first { it.name == "hello.txt" }
        assertEquals("/tmp/x/hello.txt", hello.path)
        assertFalse(hello.isDirectory)
        assertEquals(123L, hello.size)
        assertTrue(entries.first { it.name == "subdir" }.isDirectory)
        assertFalse(entries.first { it.name == "link" }.isDirectory)
    }

    @Test
    fun `parses busybox ls -la`() = runBlocking {
        // busybox: wider column padding, root:root, no leading "total" guaranteed.
        val out = """
            drwxr-xr-x    3 root     root          4096 May 31 13:00 .
            drwxr-xr-x   20 root     root          4096 May 31 12:00 ..
            -rw-r--r--    1 root     root           456 May 31 13:00 data.bin
            drwxr-xr-x    2 root     root          4096 May 31 13:00 etc
        """.trimIndent()
        val entries = backend(out).list("/")

        assertEquals(listOf("data.bin", "etc"), entries.map { it.name })
        assertEquals("/data.bin", entries.first { it.name == "data.bin" }.path)
        assertEquals(456L, entries.first { it.name == "data.bin" }.size)
        assertTrue(entries.first { it.name == "etc" }.isDirectory)
    }

    @Test
    fun `non-zero exit via sentinel throws`() = runBlocking {
        val b = backend(
            stdout = "",
            stderr = "ls: cannot access '/nope': No such file or directory\nHRC=2",
        )
        try {
            b.list("/nope")
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue("message should carry rc + stderr", e.message!!.contains("rc=2"))
        }
    }

    @Test
    fun `missing sentinel throws (truncated output)`() = runBlocking {
        val b = backend(stdout = "drwxr-xr-x 2 a a 0 May 31 13:00 d", stderr = "")
        try {
            b.list("/x"); throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("sentinel"))
        }
    }

    @Test
    fun `writeBytes streams octal printf over one interactive sh, exiting cleanly`() = runBlocking {
        val ft = FakeTransport(ByteArray(0), "HRC=0".toByteArray())
        val b = ReticulumFileBackend(ft, "deadbeef")
        // tricky bytes: NUL, 'A', high byte 0xFF, newline, single-quote
        b.writeBytes("/tmp/it's", byteArrayOf(0x00, 0x41, 0xFF.toByte(), 0x0A, 0x27))

        // Interactive `sh` reading stdin — NOT `sh -c` (the script rides stdin).
        assertEquals(listOf("sh"), ft.lastCommand)
        val script = ft.stdinFed.toByteArray().decodeToString()
        // Each byte octal-escaped (\NNN), binary-clean.
        assertTrue("octal payload", script.contains("\\000\\101\\377\\012\\047"))
        // First (only) chunk creates via '>', path single-quote-escaped.
        assertTrue("create redirect", script.contains("' > '/tmp/it'\\''s'"))
        // HRC sentinel on stderr, then a clean shell exit (never a stdin-EOF).
        assertTrue("sentinel", script.contains("printf 'HRC=%s' \"\$?\" 1>&2"))
        assertTrue("clean exit", script.trimEnd().endsWith("exit"))
    }

    @Test
    fun `writeBytes appends with double-redirect past the first line`() = runBlocking {
        val ft = FakeTransport(ByteArray(0), "HRC=0".toByteArray())
        val b = ReticulumFileBackend(ft, "deadbeef")
        b.writeBytes("/f", ByteArray(700) { 0x62 }) // 700 > UPLOAD_LINE_BYTES(512) -> 2 lines
        val script = ft.stdinFed.toByteArray().decodeToString()
        assertTrue("first line creates", script.contains("' > '/f'"))
        assertTrue("second line appends", script.contains("' >> '/f'"))
    }

    @Test
    fun `writeBytes surfaces a non-zero HRC as IOException`() = runBlocking {
        val ft = FakeTransport(ByteArray(0), "sh: can't create /ro: Read-only\nHRC=1".toByteArray())
        val b = ReticulumFileBackend(ft, "deadbeef")
        try {
            b.writeBytes("/ro", byteArrayOf(1, 2, 3))
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("rc=1"))
        }
    }

    @Test
    fun `paths are single-quoted with embedded-quote escaping`() = runBlocking {
        val ft = FakeTransport(ByteArray(0), "HRC=0".toByteArray())
        val b = ReticulumFileBackend(ft, "deadbeef")
        b.mkdir("/tmp/it's a dir")
        val script = ft.lastCommand!!.last() // the `sh -c` script
        assertTrue("path single-quoted", script.contains("'/tmp/it'\\''s a dir'"))
    }
}
