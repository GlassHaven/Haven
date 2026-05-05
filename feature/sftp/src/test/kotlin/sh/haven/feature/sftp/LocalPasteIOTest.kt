package sh.haven.feature.sftp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression coverage for GH#142 — SFTP → local paste yielded a
 * zero-byte destination because [SftpViewModel.crossCopyFile] passed
 * the phase-1 download size as the resume offset to the local writer,
 * which then `input.skip()`-ed past every byte of the source temp file
 * before any were written.
 *
 * The split between progress-display offset (ViewModel concern) and
 * transfer-skip offset (this layer's concern) has to be enforced here:
 * if [writeFileWithOptionalResume] silently degrades to "skip all
 * source bytes" again, this test fires.
 */
class LocalPasteIOTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `transferOffset=0 overwrites destination with full source content`() {
        val src = tmp.newFile("src").apply {
            writeText("hello world")
        }
        val dest = tmp.newFile("dest").apply {
            writeText("stale content that should be overwritten")
        }

        val written = writeFileWithOptionalResume(
            source = src,
            destPath = dest.absolutePath,
            transferOffset = 0L,
        )

        assertEquals(11L, written)
        assertEquals("hello world", dest.readText())
    }

    @Test
    fun `transferOffset=0 creates the destination when it doesn't exist (GH-142 repro)`() {
        // Phase-2 of remote→local paste: the temp file holds the whole
        // source, the destination is brand new. Pre-fix the call site
        // passed transferOffset=downloaded (i.e. source.length), which
        // dropped the entire payload on the floor — exactly the
        // zero-byte file the user reported. With transferOffset=0, the
        // full source must land on disk.
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val src = tmp.newFile("downloaded").apply {
            writeBytes(payload)
        }
        val dest = java.io.File(tmp.root, "fresh-dest")
        // Sanity: dest doesn't exist yet.
        assertEquals(false, dest.exists())

        val written = writeFileWithOptionalResume(
            source = src,
            destPath = dest.absolutePath,
            transferOffset = 0L,
        )

        assertEquals(4096L, written)
        assertEquals(true, dest.exists())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `transferOffset greater than zero appends remainder of source past the offset`() {
        // Resume case: queue cursor says destination already has the
        // first 5 bytes of "hello world"; on retry we should skip 5
        // source bytes and append " world" to the existing destination.
        val src = tmp.newFile("src").apply { writeText("hello world") }
        val dest = tmp.newFile("dest").apply { writeText("hello") }

        val written = writeFileWithOptionalResume(
            source = src,
            destPath = dest.absolutePath,
            transferOffset = 5L,
        )

        assertEquals(6L, written)
        assertEquals("hello world", dest.readText())
    }

    @Test
    fun `onChunk reports cumulative bytes written for the current call`() {
        val payload = ByteArray(200_000) { it.toByte() }
        val src = tmp.newFile("src").apply { writeBytes(payload) }
        val dest = tmp.newFile("dest")
        val reports = mutableListOf<Long>()

        writeFileWithOptionalResume(
            source = src,
            destPath = dest.absolutePath,
            transferOffset = 0L,
            onChunk = { reports.add(it) },
        )

        // Buffer is 64 KiB, payload is ~200 KiB → expect at least 2
        // intermediate reports plus the final value.
        assertEquals(true, reports.size >= 2)
        assertEquals(200_000L, reports.last())
        // Reports are monotonically non-decreasing.
        for (i in 1 until reports.size) {
            assertEquals(true, reports[i] >= reports[i - 1])
        }
    }

    @Test
    fun `negative transferOffset is rejected`() {
        val src = tmp.newFile("src").apply { writeText("x") }
        val dest = tmp.newFile("dest")
        assertThrows(IllegalArgumentException::class.java) {
            writeFileWithOptionalResume(src, dest.absolutePath, -1L)
        }
    }
}
