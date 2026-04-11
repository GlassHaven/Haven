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

    @Test
    fun `seekTo emits -ss before -i`() {
        val args = TranscodeCommand("/in.mp4", "/out.mp4")
            .videoCodec("libx264")
            .seekTo(12.5)
            .build()
        val ssIndex = args.indexOf("-ss")
        val iIndex = args.indexOf("-i")
        assert(ssIndex >= 0) { "-ss not found" }
        assert(ssIndex < iIndex) { "-ss should come before -i" }
        assertEquals("12.500", args[ssIndex + 1])
    }

    @Test
    fun `duration emits -t after -i`() {
        val args = TranscodeCommand("/in.mp4", "/out.mp4")
            .videoCodec("libx264")
            .duration(5.0)
            .build()
        val tIndex = args.indexOf("-t")
        val iIndex = args.indexOf("-i")
        assert(tIndex >= 0) { "-t not found" }
        assert(tIndex > iIndex) { "-t should come after -i" }
        assertEquals("5.000", args[tIndex + 1])
    }

    @Test
    fun `frames emits -frames_v`() {
        val args = TranscodeCommand("/in.mp4", "/out.jpg")
            .frames(1)
            .build()
        val framesIndex = args.indexOf("-frames:v")
        assert(framesIndex >= 0) { "-frames:v not found" }
        assertEquals("1", args[framesIndex + 1])
    }

    @Test
    fun `frameAt builds single-frame extraction command`() {
        val args = TranscodeCommand.frameAt("/in.mp4", "/out.mp4", seekSeconds = 10.0).build()
        // -ss before -i
        val ssIndex = args.indexOf("-ss")
        val iIndex = args.indexOf("-i")
        assert(ssIndex >= 0 && ssIndex < iIndex)
        assertEquals("10.000", args[ssIndex + 1])
        // -frames:v 1
        val framesIndex = args.indexOf("-frames:v")
        assert(framesIndex >= 0)
        assertEquals("1", args[framesIndex + 1])
        // Uses libx264 ultrafast for the 1-frame MP4
        assert("libx264" in args)
        assert("ultrafast" in args)
        // No audio
        assert("-an" in args)
    }

    @Test
    fun `seekTo and duration together`() {
        val args = TranscodeCommand.h264("/in.mp4", "/out.mp4")
            .seekTo(30.0)
            .duration(5.0)
            .build()
        val ssIndex = args.indexOf("-ss")
        val iIndex = args.indexOf("-i")
        val tIndex = args.indexOf("-t")
        assert(ssIndex < iIndex) { "-ss before -i" }
        assert(tIndex > iIndex) { "-t after -i" }
    }
}
