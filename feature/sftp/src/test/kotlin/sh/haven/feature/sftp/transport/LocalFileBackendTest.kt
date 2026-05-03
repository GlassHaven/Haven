package sh.haven.feature.sftp.transport

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stage 2 of issue #126: pin the structural ops on [LocalFileBackend] —
 * [LocalFileBackend.delete], [LocalFileBackend.mkdir], and
 * [LocalFileBackend.rename]. Synthetic-root listing is covered elsewhere
 * (it requires Android Environment lookup); here we exercise the
 * filesystem ops on a real temp tree because they're pure JVM.
 */
class LocalFileBackendTest {

    private lateinit var backend: LocalFileBackend
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("haven-localfb-").toFile()
        // Context is unused by delete/mkdir/rename; only listRoots() touches it.
        backend = LocalFileBackend(mockk<Context>(relaxed = true))
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `delete file removes the path`() = runTest {
        val target = File(tempRoot, "file.txt").apply { writeText("hello") }
        assertTrue(target.exists())

        backend.delete(target.absolutePath, isDirectory = false)

        assertFalse(target.exists())
    }

    @Test
    fun `delete directory recurses`() = runTest {
        // The contract is "rm -rf"-equivalent — deleting a non-empty
        // directory must succeed. The legacy SftpViewModel path used
        // File.deleteRecursively for this same reason.
        val dir = File(tempRoot, "tree").apply { mkdirs() }
        File(dir, "child").writeText("x")
        File(dir, "subdir").apply { mkdirs() }
        File(File(dir, "subdir"), "grandchild").writeText("y")

        backend.delete(dir.absolutePath, isDirectory = true)

        assertFalse(dir.exists())
    }

    @Test(expected = java.io.IOException::class)
    fun `delete on missing path throws`() = runTest {
        backend.delete(File(tempRoot, "nope").absolutePath, isDirectory = false)
    }

    @Test
    fun `mkdir creates intermediate parents`() = runTest {
        // mkdir -p semantics: missing parents are created.
        val target = File(tempRoot, "a/b/c")
        backend.mkdir(target.absolutePath)
        assertTrue("intermediate parents must exist", File(tempRoot, "a/b").exists())
        assertTrue(target.isDirectory)
    }

    @Test
    fun `mkdir on existing directory is a no-op`() = runTest {
        // Already-exists must not throw — matches the legacy
        // `!mkdirs() && !isDirectory` check the ViewModel had.
        val target = File(tempRoot, "exists").apply { mkdirs() }
        backend.mkdir(target.absolutePath)
        assertTrue(target.isDirectory)
    }

    @Test(expected = java.io.IOException::class)
    fun `mkdir over an existing file throws`() = runTest {
        val collision = File(tempRoot, "file").apply { writeText("not a dir") }
        backend.mkdir(collision.absolutePath)
    }

    @Test
    fun `rename moves a file to a new path`() = runTest {
        val src = File(tempRoot, "old.txt").apply { writeText("data") }
        val dst = File(tempRoot, "new.txt")

        backend.rename(src.absolutePath, dst.absolutePath)

        assertFalse(src.exists())
        assertTrue(dst.exists())
        assertEquals("data", dst.readText())
    }

    @Test
    fun `rename works on a directory`() = runTest {
        val src = File(tempRoot, "old-dir").apply { mkdirs() }
        File(src, "child").writeText("x")
        val dst = File(tempRoot, "new-dir")

        backend.rename(src.absolutePath, dst.absolutePath)

        assertFalse(src.exists())
        assertTrue(dst.isDirectory)
        assertEquals("x", File(dst, "child").readText())
    }

    @Test(expected = java.io.IOException::class)
    fun `rename of missing source throws`() = runTest {
        backend.rename(
            File(tempRoot, "ghost").absolutePath,
            File(tempRoot, "anywhere").absolutePath,
        )
    }
}
