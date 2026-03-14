package sh.haven.core.mosh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import sh.haven.core.mosh.transport.MoshTransport
import java.io.Closeable

private const val TAG = "MoshSession"

/**
 * Bridges a mosh transport session to the terminal emulator.
 *
 * Parallel to ReticulumSession: manages a transport instance and
 * shuttles terminal data between the mosh server and termlib.
 * No PTY or native code — the pure Kotlin MoshTransport handles
 * UDP, encryption, and protocol framing in-process.
 */
class MoshSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val serverIp: String,
    private val moshPort: Int,
    private val moshKey: String,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: MoshTransport? = null

    /**
     * Start the mosh transport: opens UDP socket, begins send/receive loops.
     */
    fun start() {
        if (closed) return
        Log.d(TAG, "Starting mosh transport for $sessionId: $serverIp:$moshPort")

        val t = MoshTransport(
            serverIp = serverIp,
            port = moshPort,
            key = moshKey,
            onOutput = { data, offset, len ->
                if (!closed) {
                    onDataReceived(data, offset, len)
                }
            },
            onDisconnect = { cleanExit ->
                if (!closed) {
                    Log.d(TAG, "Transport disconnected for $sessionId (clean=$cleanExit)")
                    onDisconnected?.invoke(cleanExit)
                }
            },
        )
        transport = t
        t.start(scope)
    }

    /**
     * Send keyboard input to the mosh server.
     * Safe to call from any thread.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        transport?.sendInput(data)
    }

    /**
     * Notify the mosh server of a terminal resize.
     */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        transport?.resize(cols, rows)
    }

    /**
     * Detach without closing the transport.
     * The mosh server keeps the session alive; we can reattach later.
     */
    fun detach() {
        if (closed) return
        closed = true
        transport?.close()
        transport = null
    }

    override fun close() {
        if (closed) return
        closed = true
        transport?.close()
        transport = null
    }
}
