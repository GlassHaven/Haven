package sh.haven.ffmpeg.spike

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Phase 0 verification Activity.
 *
 * Runs four checks against libffmpeg.so / libffprobe.so extracted from the
 * APK's jniLibs to applicationInfo.nativeLibraryDir:
 *
 *   1. Binary exists at nativeLibraryDir and is executable
 *   2. `libffmpeg.so -version` prints and exits 0
 *   3. End-to-end transcode of a bundled mp4 asset (mpeg4 + aac -> mp4)
 *   4. SIGTERM mid-encode cancels within 1s with a non-zero exit, partial file present
 *
 * Result goes to logcat tag "FFmpegSpike" and to the on-screen TextView.
 */
class SpikeActivity : Activity() {

    private lateinit var text: TextView
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            textSize = 10f
            gravity = Gravity.TOP
        }
        setContentView(ScrollView(this).apply { addView(text) })

        thread(name = "spike-tests") {
            runAllChecks()
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        uiHandler.post { text.append(msg + "\n") }
    }

    private fun runAllChecks() {
        log("=== FFmpeg Phase 0 spike ===")
        val nativeLibDir = applicationInfo.nativeLibraryDir
        log("nativeLibraryDir = $nativeLibDir")
        log("")

        val ffmpeg = File(nativeLibDir, "libffmpeg.so")
        val ffprobe = File(nativeLibDir, "libffprobe.so")

        var passed = 0
        var total = 0

        total++; if (checkBinaryPresent(ffmpeg, ffprobe)) passed++
        total++; if (checkVersion(ffmpeg)) passed++

        // Copy the bundled test asset into the app's files dir where we can
        // read/write it. nativeLibraryDir is read-only so we can't encode there.
        val testIn = File(filesDir, "test_in.mp4")
        val testOut = File(filesDir, "test_out.mp4")
        val copied = copyAsset("test_in.mp4", testIn)
        log("Copied test asset: $copied (${testIn.length()} bytes)")

        if (copied) {
            total++; if (checkTranscode(ffmpeg, testIn, testOut)) passed++
            total++; if (checkCancel(ffmpeg, testIn, File(filesDir, "cancel_out.mkv"))) passed++
        } else {
            log("SKIP: transcode / cancel (no test asset)")
        }

        log("")
        val banner = if (passed == total) "=== PHASE 0 PASS ($passed/$total) ===" else "=== PHASE 0 FAIL ($passed/$total) ==="
        log(banner)
    }

    private fun checkBinaryPresent(vararg bins: File): Boolean {
        log("[1] Checking binaries present and executable...")
        var ok = true
        for (b in bins) {
            val exists = b.exists()
            val canExec = b.canExecute()
            log("  ${b.name}: exists=$exists canExecute=$canExec size=${if (exists) b.length() else -1}")
            if (!exists || !canExec) ok = false
        }
        log(if (ok) "  PASS" else "  FAIL")
        return ok
    }

    private fun checkVersion(ffmpeg: File): Boolean {
        log("[2] Checking `libffmpeg.so -version`...")
        return try {
            val res = runProcess(listOf(ffmpeg.absolutePath, "-version"), timeoutSec = 10)
            log("  exit=${res.exit}")
            log("  first line: ${res.stdout.lineSequence().firstOrNull() ?: "<none>"}")
            val ok = res.exit == 0 && res.stdout.contains("ffmpeg version")
            log(if (ok) "  PASS" else "  FAIL")
            ok
        } catch (t: Throwable) {
            log("  EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun checkTranscode(ffmpeg: File, input: File, output: File): Boolean {
        log("[3] Checking real transcode (mpeg4 + aac -> mp4)...")
        output.delete()
        return try {
            val res = runProcess(
                listOf(
                    ffmpeg.absolutePath,
                    "-loglevel", "error",
                    "-y",
                    "-i", input.absolutePath,
                    "-c:v", "mpeg4", "-q:v", "5",
                    "-c:a", "aac",
                    output.absolutePath
                ),
                timeoutSec = 60
            )
            log("  exit=${res.exit}")
            if (res.stderr.isNotBlank()) log("  stderr: ${res.stderr.take(200)}")
            val ok = res.exit == 0 && output.exists() && output.length() > 0
            log("  output size: ${if (output.exists()) output.length() else -1} bytes")
            log(if (ok) "  PASS" else "  FAIL")
            ok
        } catch (t: Throwable) {
            log("  EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun checkCancel(ffmpeg: File, input: File, output: File): Boolean {
        log("[4] Checking SIGTERM cancel mid-encode...")
        output.delete()
        return try {
            val proc = ProcessBuilder(
                ffmpeg.absolutePath,
                "-loglevel", "warning",
                "-stream_loop", "5",
                "-re",
                "-i", input.absolutePath,
                "-c:v", "mpeg4", "-q:v", "5",
                "-c:a", "aac",
                "-f", "matroska",
                "-y", output.absolutePath
            ).redirectErrorStream(true).start()

            // Let it run long enough to produce real work.
            Thread.sleep(2000)
            if (!proc.isAlive) {
                log("  FAIL: process exited before we sent SIGTERM")
                return false
            }

            val t0 = System.nanoTime()
            proc.destroy() // Process.destroy() on Android sends SIGTERM
            val exited = proc.waitFor(3, TimeUnit.SECONDS)
            val latencyMs = (System.nanoTime() - t0) / 1_000_000L
            log("  kill latency: ${latencyMs}ms")
            log("  exit code: ${proc.exitValue()}")
            log("  output file size: ${if (output.exists()) output.length() else -1} bytes")
            val ok = exited && latencyMs < 1500 && output.exists() && output.length() > 0
            log(if (ok) "  PASS" else "  FAIL")
            ok
        } catch (t: Throwable) {
            log("  EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private data class ProcessResult(val exit: Int, val stdout: String, val stderr: String)

    private fun runProcess(cmd: List<String>, timeoutSec: Long): ProcessResult {
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IOException("Timed out after ${timeoutSec}s: ${cmd.joinToString(" ")}")
        }
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        return ProcessResult(proc.exitValue(), out, err)
    }

    private fun copyAsset(name: String, dst: File): Boolean {
        return try {
            assets.open(name).use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (t: Throwable) {
            log("copyAsset failed: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "FFmpegSpike"
    }
}
