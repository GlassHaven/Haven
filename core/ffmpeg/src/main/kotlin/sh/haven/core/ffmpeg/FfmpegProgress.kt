package sh.haven.core.ffmpeg

/**
 * Parsed progress from FFmpeg's stderr output.
 *
 * FFmpeg writes lines like:
 *   frame=  120 fps= 30 q=28.0 size=     256kB time=00:00:04.00 bitrate= 524.3kbits/s speed=1.50x
 */
data class FfmpegProgress(
    val frame: Long = 0,
    val fps: Double = 0.0,
    val size: String = "",
    val timeSeconds: Double = 0.0,
    val bitrate: String = "",
    val speed: String = "",
) {
    companion object {
        private val FRAME_RE = Regex("""frame=\s*(\d+)""")
        private val FPS_RE = Regex("""fps=\s*([\d.]+)""")
        private val SIZE_RE = Regex("""size=\s*(\S+)""")
        private val TIME_RE = Regex("""time=\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
        private val BITRATE_RE = Regex("""bitrate=\s*(\S+)""")
        private val SPEED_RE = Regex("""speed=\s*(\S+)""")

        /**
         * Parse a single stderr line from FFmpeg. Returns null if the line
         * is not a progress line (e.g. it's a header, warning, or error).
         */
        fun parse(line: String): FfmpegProgress? {
            // Progress lines always contain "time=" and "bitrate="
            if ("time=" !in line || "bitrate=" !in line) return null

            val hours = TIME_RE.find(line)?.let { m ->
                m.groupValues[1].toDouble() * 3600 +
                    m.groupValues[2].toDouble() * 60 +
                    m.groupValues[3].toDouble()
            } ?: return null

            return FfmpegProgress(
                frame = FRAME_RE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0,
                fps = FPS_RE.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                size = SIZE_RE.find(line)?.groupValues?.get(1) ?: "",
                timeSeconds = hours,
                bitrate = BITRATE_RE.find(line)?.groupValues?.get(1) ?: "",
                speed = SPEED_RE.find(line)?.groupValues?.get(1) ?: "",
            )
        }
    }
}
