package sh.haven.core.et

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import sh.haven.et.EtLogger
import sh.haven.et.transport.EtTransport
import java.io.Closeable

private const val TAG = "EtSession"

/** Bridges EtLogger to android.util.Log. */
private object AndroidEtLogger : EtLogger {
    override fun d(tag: String, msg: String) { Log.d(tag, msg) }
    override fun e(tag: String, msg: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
    }
}

/**
 * Bridges an [EtTransport] session to the terminal emulator.
 *
 * Parallel to MoshSession: manages a transport instance and
 * shuttles terminal data between the ET server and termlib.
 * No native code — the pure Kotlin EtTransport handles
 * TCP, encryption, and protocol framing in-process.
 */
class EtSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val serverHost: String,
    private val etPort: Int,
    private val clientId: String,
    private val passkey: String,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: EtTransport? = null

    fun start() {
        if (closed) return
        Log.d(TAG, "Starting ET session for $sessionId: $serverHost:$etPort")

        val t = EtTransport(
            serverHost = serverHost,
            port = etPort,
            clientId = clientId,
            passkey = passkey,
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
            logger = AndroidEtLogger,
        )
        transport = t
        t.start(scope)
    }

    fun sendInput(data: ByteArray) {
        if (closed) return
        transport?.sendInput(data)
    }

    fun resize(cols: Int, rows: Int) {
        if (closed) return
        transport?.resize(cols, rows)
    }

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
