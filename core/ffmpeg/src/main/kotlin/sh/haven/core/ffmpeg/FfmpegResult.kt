package sh.haven.core.ffmpeg

/**
 * Result of an FFmpeg or FFprobe invocation.
 */
data class FfmpegResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val cancelled: Boolean,
    val elapsedMs: Long,
) {
    val success: Boolean get() = exitCode == 0 && !cancelled
}
