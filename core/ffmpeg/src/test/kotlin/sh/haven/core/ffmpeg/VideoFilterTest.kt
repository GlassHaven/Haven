package sh.haven.core.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFilterTest {

    @Test
    fun `brightness serializes correctly`() {
        assertEquals("eq=brightness=0.5", VideoFilter.Brightness(0.5f).toFfmpeg())
        assertEquals("eq=brightness=-0.3", VideoFilter.Brightness(-0.3f).toFfmpeg())
        assertEquals("eq=brightness=0", VideoFilter.Brightness(0f).toFfmpeg())
    }

    @Test
    fun `contrast serializes correctly`() {
        assertEquals("eq=contrast=1.5", VideoFilter.Contrast(1.5f).toFfmpeg())
    }

    @Test
    fun `saturation zero produces grayscale`() {
        assertEquals("eq=saturation=0", VideoFilter.Saturation(0f).toFfmpeg())
    }

    @Test
    fun `gamma serializes correctly`() {
        assertEquals("eq=gamma=2.2", VideoFilter.Gamma(2.2f).toFfmpeg())
    }

    @Test
    fun `auto color is normalize`() {
        assertEquals("normalize", VideoFilter.AutoColor.toFfmpeg())
    }

    @Test
    fun `stabilize defaults`() {
        assertEquals("deshake=rx=16:ry=16", VideoFilter.Stabilize().toFfmpeg())
    }

    @Test
    fun `sharpen serializes correctly`() {
        assertEquals("unsharp=5:5:1:5:5:1", VideoFilter.Sharpen(1f).toFfmpeg())
    }

    @Test
    fun `denoise serializes correctly`() {
        assertEquals("nlmeans=10", VideoFilter.Denoise(10).toFfmpeg())
    }

    @Test
    fun `scale with aspect ratio preservation`() {
        assertEquals("scale=1280:-2", VideoFilter.Scale(1280).toFfmpeg())
        assertEquals("scale=720:480", VideoFilter.Scale(720, 480).toFfmpeg())
    }

    @Test
    fun `crop serializes correctly`() {
        assertEquals("crop=640:480:100:50", VideoFilter.Crop(640, 480, 100, 50).toFfmpeg())
    }

    @Test
    fun `transpose 90 CW`() {
        assertEquals("transpose=1", VideoFilter.Transpose(1).toFfmpeg())
    }

    @Test
    fun `speed 2x`() {
        assertEquals("setpts=PTS/2", VideoFilter.Speed(2f).toFfmpeg())
    }

    @Test
    fun `fade in`() {
        assertEquals("fade=in:d=1.5", VideoFilter.FadeIn(1.5f).toFfmpeg())
    }

    @Test
    fun `chain combines multiple filters`() {
        val chain = VideoFilter.chain(listOf(
            VideoFilter.Stabilize(),
            VideoFilter.Brightness(0.1f),
            VideoFilter.AutoColor,
        ))
        assertEquals("deshake=rx=16:ry=16,eq=brightness=0.1,normalize", chain)
    }

    @Test
    fun `empty chain produces empty string`() {
        assertEquals("", VideoFilter.chain(emptyList()))
    }

    // --- Audio filters ---

    @Test
    fun `volume in dB`() {
        assertEquals("volume=3dB", AudioFilter.Volume(3f).toFfmpeg())
        assertEquals("volume=-6dB", AudioFilter.Volume(-6f).toFfmpeg())
    }

    @Test
    fun `normalize is loudnorm`() {
        assertEquals("loudnorm", AudioFilter.Normalize.toFfmpeg())
    }

    @Test
    fun `audio speed`() {
        assertEquals("atempo=1.5", AudioFilter.Speed(1.5f).toFfmpeg())
    }

    @Test
    fun `audio chain`() {
        val chain = AudioFilter.chain(listOf(
            AudioFilter.Normalize,
            AudioFilter.Volume(3f),
        ))
        assertEquals("loudnorm,volume=3dB", chain)
    }

    @Test
    fun `color balance serializes all 9 params`() {
        val cb = VideoFilter.ColorBalance(
            rs = 0.1f, gs = -0.1f, bs = 0f,
            rm = 0f, gm = 0.2f, bm = 0f,
            rh = 0f, gh = 0f, bh = -0.3f,
        )
        assertEquals(
            "colorbalance=rs=0.1:gs=-0.1:bs=0:rm=0:gm=0.2:bm=0:rh=0:gh=0:bh=-0.3",
            cb.toFfmpeg(),
        )
    }
}
