package sh.haven.core.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegResultTest {

    @Test
    fun `success is true when exit code is 0 and not cancelled`() {
        val result = FfmpegResult(
            exitCode = 0,
            stdout = "ffmpeg version 7.1.1",
            stderr = "",
            cancelled = false,
            elapsedMs = 50,
        )
        assertTrue(result.success)
    }

    @Test
    fun `success is false when exit code is non-zero`() {
        val result = FfmpegResult(
            exitCode = 1,
            stdout = "",
            stderr = "Error opening input",
            cancelled = false,
            elapsedMs = 100,
        )
        assertFalse(result.success)
    }

    @Test
    fun `success is false when cancelled even if exit code is 0`() {
        val result = FfmpegResult(
            exitCode = 0,
            stdout = "",
            stderr = "",
            cancelled = true,
            elapsedMs = 3000,
        )
        assertFalse(result.success)
    }

    @Test
    fun `data class equality works`() {
        val a = FfmpegResult(0, "out", "err", false, 100)
        val b = FfmpegResult(0, "out", "err", false, 100)
        assertEquals(a, b)
    }
}
