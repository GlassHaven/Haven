package sh.haven.core.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FfmpegProgressTest {

    @Test
    fun `parse typical progress line`() {
        val line = "frame=  120 fps= 30 q=28.0 size=     256kB time=00:00:04.00 bitrate= 524.3kbits/s speed=1.50x"
        val p = FfmpegProgress.parse(line)
        assertNotNull(p)
        assertEquals(120L, p!!.frame)
        assertEquals(30.0, p.fps, 0.01)
        assertEquals("256kB", p.size)
        assertEquals(4.0, p.timeSeconds, 0.01)
        assertEquals("524.3kbits/s", p.bitrate)
        assertEquals("1.50x", p.speed)
    }

    @Test
    fun `parse progress with hours`() {
        val line = "frame= 5400 fps=60.0 q=23.0 size=  10240kB time=01:30:00.00 bitrate= 200.0kbits/s speed=2.00x"
        val p = FfmpegProgress.parse(line)
        assertNotNull(p)
        assertEquals(5400.0, p!!.timeSeconds, 0.01) // 1h30m = 5400s
        assertEquals(5400L, p.frame)
    }

    @Test
    fun `parse progress with sub-second time`() {
        val line = "frame=   10 fps=0.0 q=0.0 size=       0kB time=00:00:00.40 bitrate=   0.0kbits/s speed=N/A"
        val p = FfmpegProgress.parse(line)
        assertNotNull(p)
        assertEquals(0.4, p!!.timeSeconds, 0.001)
        assertEquals("N/A", p.speed)
    }

    @Test
    fun `returns null for non-progress line`() {
        assertNull(FfmpegProgress.parse("ffmpeg version 7.1.1 Copyright (c) 2000-2024"))
        assertNull(FfmpegProgress.parse("  built with gcc 14"))
        assertNull(FfmpegProgress.parse("Input #0, mov,mp4,m4a,3gp, from 'input.mp4':"))
        assertNull(FfmpegProgress.parse(""))
    }

    @Test
    fun `returns null for incomplete progress line`() {
        // Has time= but no bitrate=
        assertNull(FfmpegProgress.parse("time=00:00:01.00 speed=1x"))
    }

    @Test
    fun `parse compact progress format`() {
        val line = "frame=1 fps=0 q=0.0 size=0kB time=00:00:00.04 bitrate=0.0kbits/s speed=0.8x"
        val p = FfmpegProgress.parse(line)
        assertNotNull(p)
        assertEquals(1L, p!!.frame)
        assertEquals(0.04, p.timeSeconds, 0.001)
    }
}
