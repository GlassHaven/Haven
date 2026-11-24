package sh.haven.core.local.uml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Direct tests for the UML rootfs staging path ([stageRootfsImage]) — the part
 * a "UML fails" report points at. Previously only the version-marker parsing
 * was tested ([UmlRootfsVersionTest]); the copy/validate/rename loop had no
 * coverage. These pin the crash-safe behaviour: an exact-size image stages and
 * gets a version marker, a truncated or oversized asset is rejected with the
 * temp file cleaned up, and a re-stage overwrites a stale image.
 */
class UmlRootfsStagingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun umlDir(): File = tmp.newFolder("uml")

    @Test
    fun `stages an exact-size image and writes the version marker`() {
        val dir = umlDir()
        val image = ByteArray(4096) { (it % 251).toByte() }
        stageRootfsImage(ByteArrayInputStream(image), dir, expectedSize = 4096, version = 11)

        val target = File(dir, "rootfs.ext4")
        assertTrue(target.exists())
        assertEquals(4096L, target.length())
        assertTrue(target.readBytes().contentEquals(image))
        assertEquals("11", File(dir, "rootfs.version").readText())
        assertFalse("temp file must be renamed away", File(dir, "rootfs.ext4.unpack").exists())
    }

    @Test
    fun `rejects a truncated image and cleans up`() {
        val dir = umlDir()
        val err = runCatching {
            stageRootfsImage(ByteArrayInputStream(ByteArray(1000)), dir, expectedSize = 4096, version = 11)
        }.exceptionOrNull()
        assertTrue("truncated image must throw", err is IllegalStateException)
        assertFalse(File(dir, "rootfs.ext4.unpack").exists())
        assertFalse(File(dir, "rootfs.ext4").exists())
        assertFalse("no version marker on a failed stage", File(dir, "rootfs.version").exists())
    }

    @Test
    fun `rejects an oversized image and cleans up`() {
        val dir = umlDir()
        val err = runCatching {
            stageRootfsImage(ByteArrayInputStream(ByteArray(5000)), dir, expectedSize = 4096, version = 11)
        }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertFalse(File(dir, "rootfs.ext4.unpack").exists())
        assertFalse(File(dir, "rootfs.ext4").exists())
    }

    @Test
    fun `progress is monotonic and reaches 100`() {
        val dir = umlDir()
        val progress = mutableListOf<Int>()
        stageRootfsImage(
            ByteArrayInputStream(ByteArray(4096)), dir, expectedSize = 4096, version = 11,
            onProgress = { progress.add(it) },
        )
        assertTrue(progress.isNotEmpty())
        assertEquals(100, progress.last())
        assertTrue("progress must not decrease", progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `re-stage overwrites a stale image and bumps the marker`() {
        val dir = umlDir()
        // A stale v10 image of the right size, plus an old marker.
        File(dir, "rootfs.ext4").writeBytes(ByteArray(4096) { 1 })
        File(dir, "rootfs.version").writeText("10")

        val fresh = ByteArray(4096) { 2 }
        stageRootfsImage(ByteArrayInputStream(fresh), dir, expectedSize = 4096, version = 11)

        assertTrue(File(dir, "rootfs.ext4").readBytes().contentEquals(fresh))
        assertEquals("11", File(dir, "rootfs.version").readText())
    }
}
