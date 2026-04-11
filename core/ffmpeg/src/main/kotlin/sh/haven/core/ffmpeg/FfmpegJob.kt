package sh.haven.core.ffmpeg

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A running FFmpeg process with cancel and progress semantics.
 *
 * Stderr is streamed line-by-line on a daemon thread to [onStderr]
 * (FFmpeg writes progress like "frame= 120 fps=..." to stderr).
 * Stdout is captured in full for commands like ffprobe -show_format.
 */
class FfmpegJob internal constructor(
    private val process: Process,
    private val startTimeMs: Long,
    onStderr: (String) -> Unit,
) {
    @Volatile
    private var cancelled = false

    private val stderrLines = mutableListOf<String>()
    private val stdoutBuilder = StringBuilder()

    private val stderrThread = Thread({
        // FFmpeg writes progress using \r (carriage return) to overwrite the
        // same line, not \n. BufferedReader.readLine() only splits on \n, so
        // progress updates would accumulate into one giant line that only
        // appears when ffmpeg finishes. Read char-by-char and split on both.
        val reader = process.errorStream.bufferedReader()
        val buf = StringBuilder()
        var ch: Int
        while (reader.read().also { ch = it } != -1) {
            if (ch == '\r'.code || ch == '\n'.code) {
                if (buf.isNotEmpty()) {
                    val line = buf.toString()
                    buf.clear()
                    synchronized(stderrLines) { stderrLines.add(line) }
                    onStderr(line)
                }
            } else {
                buf.append(ch.toChar())
            }
        }
        if (buf.isNotEmpty()) {
            val line = buf.toString()
            synchronized(stderrLines) { stderrLines.add(line) }
            onStderr(line)
        }
    }, "ffmpeg-stderr").apply { isDaemon = true; start() }

    private val stdoutThread = Thread({
        stdoutBuilder.append(process.inputStream.bufferedReader().readText())
    }, "ffmpeg-stdout").apply { isDaemon = true; start() }

    val isRunning: Boolean get() = process.isAlive

    /**
     * Send SIGTERM, wait up to 3s for graceful exit, then SIGKILL.
     * FFmpeg respects SIGTERM and flushes its output file.
     */
    fun cancel() {
        cancelled = true
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }

    /**
     * Block until the process exits and return the result.
     */
    fun await(): FfmpegResult {
        process.waitFor()
        stderrThread.join(2000)
        stdoutThread.join(2000)
        return FfmpegResult(
            exitCode = process.exitValue(),
            stdout = stdoutBuilder.toString(),
            stderr = synchronized(stderrLines) { stderrLines.joinToString("\n") },
            cancelled = cancelled,
            elapsedMs = System.currentTimeMillis() - startTimeMs,
        )
    }

    companion object {
        /**
         * Start a new FFmpeg/FFprobe process.
         *
         * @param binary Path to libffmpeg.so or libffprobe.so
         * @param args Command-line arguments (without the binary path)
         * @param nativeLibDir Directory containing libc++_shared.so
         * @param workDir Working directory for the process
         * @param onStderr Line-by-line callback for stderr output
         */
        internal fun start(
            binary: File,
            args: List<String>,
            nativeLibDir: String,
            workDir: File? = null,
            onStderr: (String) -> Unit = {},
        ): FfmpegJob {
            val cmd = listOf(binary.absolutePath) + args
            val pb = ProcessBuilder(cmd).apply {
                environment()["LD_LIBRARY_PATH"] = nativeLibDir
                workDir?.let { directory(it) }
            }
            val process = pb.start()
            return FfmpegJob(process, System.currentTimeMillis(), onStderr)
        }
    }
}
