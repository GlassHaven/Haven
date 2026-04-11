package sh.haven.core.ffmpeg

/**
 * Builds FFmpeg transcode command-line arguments.
 *
 * Usage:
 *   val args = TranscodeCommand(input = "/path/in.mkv", output = "/path/out.mp4")
 *       .videoCodec("libx264")
 *       .audioCodec("aac")
 *       .build()
 *   executor.execute(args)
 */
class TranscodeCommand(
    private val input: String,
    private val output: String,
) {
    private var vCodec: String? = null
    private var aCodec: String? = null
    private var vBitrate: String? = null
    private var aBitrate: String? = null
    private var crf: Int? = null
    private var preset: String? = null
    private var scale: String? = null
    private val vFilters = mutableListOf<VideoFilter>()
    private val aFilters = mutableListOf<AudioFilter>()
    private var extraArgs = mutableListOf<String>()
    private var overwrite = true

    fun videoCodec(codec: String) = apply { vCodec = codec }
    fun audioCodec(codec: String) = apply { aCodec = codec }
    fun videoBitrate(bitrate: String) = apply { vBitrate = bitrate }
    fun audioBitrate(bitrate: String) = apply { aBitrate = bitrate }
    fun crf(value: Int) = apply { crf = value }
    fun preset(value: String) = apply { preset = value }
    fun scale(widthxheight: String) = apply { scale = widthxheight }
    fun videoFilter(filter: VideoFilter) = apply { vFilters.add(filter) }
    fun videoFilters(filters: List<VideoFilter>) = apply { vFilters.addAll(filters) }
    fun audioFilter(filter: AudioFilter) = apply { aFilters.add(filter) }
    fun audioFilters(filters: List<AudioFilter>) = apply { aFilters.addAll(filters) }
    fun overwrite(value: Boolean) = apply { overwrite = value }
    fun extra(vararg args: String) = apply { extraArgs.addAll(args) }

    /** Copy video and audio streams without re-encoding. */
    fun copy() = apply { vCodec = "copy"; aCodec = "copy" }

    fun build(): List<String> = buildList {
        if (overwrite) add("-y")
        add("-i"); add(input)

        vCodec?.let { add("-c:v"); add(it) }
        aCodec?.let { add("-c:a"); add(it) }
        vBitrate?.let { add("-b:v"); add(it) }
        aBitrate?.let { add("-b:a"); add(it) }
        crf?.let { add("-crf"); add(it.toString()) }
        preset?.let { add("-preset"); add(it) }

        // Build -vf chain: combine explicit scale + VideoFilter list
        val allVf = buildList {
            scale?.let { add("scale=$it") }
            if (vFilters.isNotEmpty()) add(VideoFilter.chain(vFilters))
        }
        if (allVf.isNotEmpty()) {
            add("-vf"); add(allVf.joinToString(","))
        }

        // Build -af chain from AudioFilter list
        if (aFilters.isNotEmpty()) {
            add("-af"); add(AudioFilter.chain(aFilters))
        }

        addAll(extraArgs)
        add(output)
    }

    companion object {
        /** Quick H.264 + AAC transcode with sensible defaults. */
        fun h264(input: String, output: String, crf: Int = 23) =
            TranscodeCommand(input, output)
                .videoCodec("libx264")
                .audioCodec("aac")
                .crf(crf)
                .preset("medium")

        /** Quick H.265 + AAC transcode. */
        fun h265(input: String, output: String, crf: Int = 28) =
            TranscodeCommand(input, output)
                .videoCodec("libx265")
                .audioCodec("aac")
                .crf(crf)
                .preset("medium")

        /** VP9 + Opus for WebM. */
        fun vp9(input: String, output: String, crf: Int = 31) =
            TranscodeCommand(input, output)
                .videoCodec("libvpx-vp9")
                .audioCodec("libopus")
                .crf(crf)
                .extra("-b:v", "0")

        /** Audio-only MP3 extraction. */
        fun mp3(input: String, output: String, bitrate: String = "192k") =
            TranscodeCommand(input, output)
                .extra("-vn")
                .audioCodec("libmp3lame")
                .audioBitrate(bitrate)
    }
}
