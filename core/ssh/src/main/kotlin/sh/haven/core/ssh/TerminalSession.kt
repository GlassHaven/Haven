package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "TerminalSession"

/**
 * Bridges a JSch [ChannelShell] to a terminal emulator.
 *
 * Reads SSH output on a background thread and delivers it via [onDataReceived].
 * Call [sendToSsh] to forward keyboard input to the remote shell.
 */
class TerminalSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    @Volatile private var channel: ChannelShell,
    @Volatile private var client: SshClient,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
    @Volatile var pendingCommand: String? = null,
) : Closeable {

    @Volatile private var sshInput: InputStream = channel.inputStream
    @Volatile private var sshOutput: OutputStream = channel.outputStream

    /** Single-thread executor for serialising writes off the main thread. */
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-writer-$sessionId").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    private var readerThread: Thread? = null

    /**
     * Start the reader thread that delivers SSH output to [onDataReceived].
     * Call this after all wiring (e.g., emulator setup) is complete.
     */
    fun start() {
        readerThread = thread(
            name = "ssh-reader-$sessionId",
            isDaemon = true,
        ) {
            readLoop()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(8192)
        var pendingSent = false
        var gotEof = false
        var gotException = false
        try {
            while (!closed && channel.isConnected) {
                val bytesRead = sshInput.read(buffer)
                if (bytesRead == -1) {
                    gotEof = true
                    break
                }
                if (bytesRead > 0) {
                    onDataReceived(buffer, 0, bytesRead)

                    // After delivering output, check if we have a pending session
                    // manager command to send once the shell prompt appears.
                    if (!pendingSent && pendingCommand != null) {
                        val text = String(buffer, 0, bytesRead).trimEnd()
                        if (text.isNotEmpty()) {
                            val last = text.last()
                            if (last == '$' || last == '#' || last == '%' || last == '>') {
                                Log.d(TAG, "Shell prompt detected ('$last'), sending pending command")
                                sendToSsh((pendingCommand!! + "\n").toByteArray())
                                pendingCommand = null
                                pendingSent = true
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            gotException = true
        }
        if (!closed) {
            // Wait briefly for channel to fully close and exit status to propagate
            for (i in 1..10) {
                if (channel.isClosed) break
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
            val exitStatus = channel.exitStatus
            // Clean exit: remote sent a real exit status (>= 0), e.g. shell
            // exited normally.  A network drop produces EOF with exitStatus -1
            // (no status received) — that must trigger reconnection.
            val cleanExit = exitStatus >= 0 && !gotException
            Log.d(TAG, "readLoop ended for $sessionId — eof=$gotEof exception=$gotException exitStatus=$exitStatus cleanExit=$cleanExit")
            onDisconnected?.invoke(cleanExit)
        }
    }

    /**
     * Forward keyboard input to the remote shell.
     * Safe to call from any thread — writes are dispatched to a background thread
     * to avoid NetworkOnMainThreadException.
     */
    fun sendToSsh(data: ByteArray) {
        if (closed || !channel.isConnected) {
            Log.d(TAG, "sendToSsh: dropping ${data.size} bytes (closed=$closed connected=${channel.isConnected})")
            return
        }

        val copy = data.copyOf()
        try {
            writeExecutor.execute {
                if (closed || !channel.isConnected) return@execute
                try {
                    sshOutput.write(copy)
                    sshOutput.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "sendToSsh: write failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down — drop the write
        }
    }

    fun resize(cols: Int, rows: Int) {
        Log.d(TAG, "resize requested: ${cols}x${rows} closed=$closed")
        if (closed || writeExecutor.isShutdown) return
        try {
            writeExecutor.execute {
                try {
                    Log.d(TAG, "setPtySize: ${cols}x${rows}")
                    client.resizeShell(channel, cols, rows)
                } catch (e: Exception) {
                    Log.e(TAG, "resize failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down between check and execute — ignore
        }
    }

    /**
     * Swap the underlying SSH channel after a reconnect.
     * The old reader thread has already exited (which triggered the reconnect).
     * Starts a new reader on the new channel.
     */
    fun reconnect(newChannel: ChannelShell, newClient: SshClient) {
        channel = newChannel
        client = newClient
        sshInput = newChannel.inputStream
        sshOutput = newChannel.outputStream
        Log.d(TAG, "reconnect: swapped channel for $sessionId, starting new reader")
        readerThread = thread(
            name = "ssh-reader-$sessionId",
            isDaemon = true,
        ) {
            readLoop()
        }
    }

    /**
     * Detach from the channel without disconnecting it.
     * Stops the reader thread and write executor but leaves the shell channel
     * alive so a new TerminalSession can be attached to it.
     * Used when TerminalViewModel is cleared but the SSH connection persists
     * (e.g., Activity destroyed while foreground service keeps the process alive).
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
        try { channel.disconnect() } catch (_: Exception) {}
        readerThread?.interrupt()
    }
}
