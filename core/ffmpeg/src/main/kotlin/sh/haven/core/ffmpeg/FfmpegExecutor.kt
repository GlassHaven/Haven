package sh.haven.core.ffmpeg

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs FFmpeg and FFprobe binaries bundled in the APK's nativeLibraryDir.
 *
 * The binaries are cross-compiled ELF executables renamed to lib*.so so
 * Android's package installer extracts them to a directory where execve
 * is permitted (the W^X workaround shared with PRoot).
 */
@Singleton
class FfmpegExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val nativeLibDir: String
        get() = context.applicationInfo.nativeLibraryDir

    private val ffmpegBinary: File
        get() = File(nativeLibDir, "libffmpeg.so")

    private val ffprobeBinary: File
        get() = File(nativeLibDir, "libffprobe.so")

    /** True if both ffmpeg and ffprobe binaries exist and are executable. */
    fun isAvailable(): Boolean =
        ffmpegBinary.canExecute() && ffprobeBinary.canExecute()

    /**
     * Run ffmpeg with the given arguments. Blocks until completion.
     *
     * @param args FFmpeg arguments (e.g. ["-i", "input.mp4", "-c:v", "libx264", "output.mp4"])
     * @param onStderr Line-by-line callback for progress/diagnostic output
     * @return Result with exit code, captured output, and timing
     */
    fun execute(args: List<String>, onStderr: (String) -> Unit = {}): FfmpegResult {
        return startJob(args, onStderr).await()
    }

    /**
     * Run ffprobe with the given arguments. Blocks until completion.
     *
     * @param args FFprobe arguments (e.g. ["-show_format", "-of", "json", "input.mp4"])
     * @param onStderr Line-by-line callback for diagnostic output
     * @return Result with exit code, captured output, and timing
     */
    fun probe(args: List<String>, onStderr: (String) -> Unit = {}): FfmpegResult {
        return startProbeJob(args, onStderr).await()
    }

    /**
     * Start a non-blocking ffmpeg job that can be cancelled.
     */
    fun startJob(args: List<String>, onStderr: (String) -> Unit = {}): FfmpegJob {
        check(ffmpegBinary.canExecute()) { "libffmpeg.so not found or not executable" }
        return FfmpegJob.start(ffmpegBinary, args, nativeLibDir, onStderr = onStderr)
    }

    /**
     * Start a non-blocking ffprobe job that can be cancelled.
     */
    fun startProbeJob(args: List<String>, onStderr: (String) -> Unit = {}): FfmpegJob {
        check(ffprobeBinary.canExecute()) { "libffprobe.so not found or not executable" }
        return FfmpegJob.start(ffprobeBinary, args, nativeLibDir, onStderr = onStderr)
    }
}
