package sh.haven.core.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscodeCommandTest {

    @Test
    fun `h264 preset builds correct args`() {
        val args = TranscodeCommand.h264("/in.mkv", "/out.mp4").build()
        assertEquals(
            listOf("-y", "-i", "/in.mkv", "-c:v", "libx264", "-c:a", "aac", "-crf", "23", "-preset", "medium", "/out.mp4"),
            args,
        )
    }

    @Test
    fun `h265 with custom crf`() {
        val args = TranscodeCommand.h265("/in.avi", "/out.mp4", crf = 20).build()
        assert(args.contains("libx265"))
        assert(args.contains("20"))
    }

    @Test
    fun `vp9 includes zero bitrate flag`() {
        val args = TranscodeCommand.vp9("/in.mp4", "/out.webm").build()
        val bvIndex = args.indexOf("-b:v")
        assertEquals("0", args[bvIndex + 1])
    }

    @Test
    fun `mp3 extraction drops video`() {
        val args = TranscodeCommand.mp3("/in.mp4", "/out.mp3").build()
        assert("-vn" in args)
        assert("libmp3lame" in args)
        assert("192k" in args)
    }

    @Test
    fun `copy mode`() {
        val args = TranscodeCommand("/in.mkv", "/out.mp4").copy().build()
        assertEquals(listOf("-y", "-i", "/in.mkv", "-c:v", "copy", "-c:a", "copy", "/out.mp4"), args)
    }

    @Test
    fun `custom builder chain`() {
        val args = TranscodeCommand("/in.mov", "/out.mp4")
            .videoCodec("libx264")
            .audioCodec("aac")
            .videoBitrate("2M")
            .audioBitrate("128k")
            .scale("1280:-1")
            .extra("-movflags", "+faststart")
            .build()

        assert("-b:v" in args && args[args.indexOf("-b:v") + 1] == "2M")
        assert("-vf" in args && args[args.indexOf("-vf") + 1] == "scale=1280:-1")
        assert("-movflags" in args)
    }

    @Test
    fun `no overwrite flag`() {
        val args = TranscodeCommand("/in.mp4", "/out.mp4").overwrite(false).build()
        assert("-y" !in args)
    }
}
