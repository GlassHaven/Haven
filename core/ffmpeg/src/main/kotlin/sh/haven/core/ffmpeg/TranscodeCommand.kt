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
    private var seekSeconds: Double? = null
    private var durationSeconds: Double? = null
    private var toSeconds: Double? = null
    private var framesLimit: Int? = null

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

    /** Seek to a position before decoding. Emitted before -i for fast keyframe seek. */
    fun seekTo(seconds: Double) = apply { seekSeconds = seconds }

    /** Limit output duration in seconds (-t flag). */
    fun duration(seconds: Double) = apply { durationSeconds = seconds }

    /**
     * Stop writing output when the input timestamp reaches [seconds] (-to flag,
     * absolute end time). Mutually exclusive with [duration]; if both are set,
     * whichever ffmpeg sees first wins — but note that -to is relative to the
     * seek point when -ss is used before -i.
     */
    fun to(seconds: Double) = apply { toSeconds = seconds }

    /** Limit output to N video frames (-frames:v flag). */
    fun frames(count: Int) = apply { framesLimit = count }

    /** Copy video and audio streams without re-encoding. */
    fun copy() = apply { vCodec = "copy"; aCodec = "copy" }

    fun build(): List<String> = buildList {
        if (overwrite) add("-y")
        // -ss before -i = fast keyframe seek (input seeking)
        seekSeconds?.let { add("-ss"); add(String.format(java.util.Locale.US, "%.3f", it)) }
        add("-i"); add(input)
        // -t after -i = limit output duration
        durationSeconds?.let { add("-t"); add(String.format(java.util.Locale.US, "%.3f", it)) }
        toSeconds?.let { add("-to"); add(String.format(java.util.Locale.US, "%.3f", it)) }

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

        framesLimit?.let { add("-frames:v"); add(it.toString()) }
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

        /**
         * Lossless trim: fast seek to [startSec], then stream-copy for
         * `endSec - startSec` seconds. Uses -t (duration) rather than -to
         * (absolute end) so the semantics are unambiguous regardless of how
         * ffmpeg interprets output timestamps after -ss.
         */
        fun trim(input: String, output: String, startSec: Double, endSec: Double) =
            TranscodeCommand(input, output)
                .seekTo(startSec)
                .duration((endSec - startSec).coerceAtLeast(0.0))
                .copy()

        /**
         * Audio-only extraction in the requested codec.
         *
         * @param codec one of "libmp3lame", "aac", "libopus", "flac", or "copy"
         *              (copy keeps the source audio stream bit-exact)
         * @param bitrate e.g. "192k"; ignored for "copy" and "flac"
         */
        fun extractAudio(
            input: String,
            output: String,
            codec: String = "libmp3lame",
            bitrate: String = "192k",
        ): TranscodeCommand {
            val cmd = TranscodeCommand(input, output)
                .extra("-vn")
                .audioCodec(codec)
            if (codec != "copy" && codec != "flac") cmd.audioBitrate(bitrate)
            return cmd
        }

        /**
         * Contact sheet: sample one frame every [sampleEverySec] seconds,
         * scale each sample to [tileWidth]x[tileHeight], and tile the
         * resulting frames into a [cols]x[rows] grid. Output is a
         * single-frame MP4 (same trick as [frameAt]) so the bundled ffmpeg
         * doesn't need mjpeg/image2 muxers; the caller decodes to a Bitmap
         * via MediaMetadataRetriever and saves as PNG.
         */
        fun contactSheet(
            input: String,
            output: String,
            sampleEverySec: Double,
            cols: Int,
            rows: Int,
            tileWidth: Int,
            tileHeight: Int,
        ): TranscodeCommand {
            val fps = if (sampleEverySec <= 0.0) 1.0 else 1.0 / sampleEverySec
            val vf = "fps=${String.format(java.util.Locale.US, "%.6f", fps)}," +
                    "scale=${tileWidth}:${tileHeight}:force_original_aspect_ratio=decrease," +
                    "pad=${tileWidth}:${tileHeight}:(ow-iw)/2:(oh-ih)/2," +
                    "tile=${cols}x${rows}"
            return TranscodeCommand(input, output)
                .videoCodec("libx264")
                .preset("ultrafast")
                .crf(18)
                .frames(1)
                // Force MP4 muxer so a caller-chosen output name with e.g. .png
                // doesn't trip ffmpeg's image2 auto-detection. The caller is
                // expected to use a .mp4 intermediate and decode to Bitmap.
                .extra("-an", "-vf", vf, "-f", "mp4", "-pix_fmt", "yuv420p")
        }

        /**
         * Extract a single frame as a 1-frame MP4 at the given seek position.
         * Uses libx264 ultrafast because the bundled ffmpeg may not include
         * the mjpeg encoder or image2 muxer. The caller decodes the MP4 to
         * a Bitmap via Android's MediaMetadataRetriever.
         */
        fun frameAt(input: String, output: String, seekSeconds: Double = 0.0) =
            TranscodeCommand(input, output)
                .seekTo(seekSeconds)
                .videoCodec("libx264")
                .preset("ultrafast")
                .crf(18)
                .frames(1)
                .extra("-an")
    }
}
