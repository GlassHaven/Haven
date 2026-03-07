package sh.haven.core.reticulum

import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "ReticulumSession"

/**
 * Bridges an rnsh session (via Python/Reticulum) to a terminal emulator.
 *
 * Parallel to [sh.haven.core.ssh.TerminalSession] but uses the embedded
 * Python Reticulum stack instead of JSch/SSH. A reader thread polls the
 * Python output queue and delivers data via [onDataReceived].
 */
class ReticulumSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val bridge: ReticulumBridge,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private var readerThread: Thread? = null

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rns-writer-$sessionId").apply { isDaemon = true }
    }

    /**
     * Start the reader thread that polls Python for output data.
     * Call after the session has been created via [ReticulumBridge.createSession].
     */
    fun start() {
        readerThread = thread(
            name = "rns-reader-$sessionId",
            isDaemon = true,
        ) {
            readLoop()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val data = bridge.readOutput(sessionId, timeoutMs = 1000)

                if (data == null) {
                    // Disconnected
                    break
                }

                if (data.isNotEmpty()) {
                    onDataReceived(data, 0, data.size)
                }
                // Empty data = timeout, loop again
            }
        } catch (e: Exception) {
            if (!closed) {
                Log.e(TAG, "readLoop exception for $sessionId", e)
            }
        }

        if (!closed) {
            Log.d(TAG, "readLoop ended for $sessionId")
            onDisconnected?.invoke(true)
        }
    }

    /**
     * Send keyboard input to the remote shell.
     * Safe to call from any thread.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        val copy = data.copyOf()
        try {
            writeExecutor.execute {
                if (closed) return@execute
                try {
                    bridge.sendInput(sessionId, copy)
                } catch (e: Exception) {
                    Log.e(TAG, "sendInput failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (closed) return
        try {
            writeExecutor.execute {
                if (closed) return@execute
                try {
                    bridge.resizeSession(sessionId, cols, rows)
                } catch (e: Exception) {
                    Log.e(TAG, "resize failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down
        }
    }

    /**
     * Detach without closing the underlying rnsh session.
     * Stops the reader/writer but leaves the Python session alive.
     */
    fun detach() {
        if (closed) return
        closed = true
        writeExecutor.shutdown()
        readerThread?.interrupt()
    }

    override fun close() {
        if (closed) return
        closed = true
        writeExecutor.shutdown()
        try {
            bridge.closeSession(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "close failed", e)
        }
        readerThread?.interrupt()
    }
}
