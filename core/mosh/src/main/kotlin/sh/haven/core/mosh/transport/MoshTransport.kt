package sh.haven.core.mosh.transport

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.haven.core.mosh.crypto.MoshCrypto
import sh.haven.core.mosh.network.MoshConnection
import sh.haven.core.mosh.proto.HostInstruction
import sh.haven.core.mosh.proto.HostMessage
import sh.haven.core.mosh.proto.TransportInstruction
import java.io.Closeable

private const val TAG = "MoshTransport"

/**
 * Pure Kotlin mosh transport implementing the State Synchronization Protocol.
 *
 * Replaces the native mosh-client binary. Handles UDP communication,
 * AES-128-OCB encryption, SSP state tracking, and protobuf framing.
 *
 * Terminal output (VT100 sequences from the server) is delivered via
 * [onOutput] and fed directly to connectbot's termlib emulator.
 */
class MoshTransport(
    private val serverIp: String,
    private val port: Int,
    key: String,
    private val onOutput: (ByteArray, Int, Int) -> Unit,
    private val onDisconnect: ((cleanExit: Boolean) -> Unit)?,
) : Closeable {

    private val crypto = MoshCrypto(key)
    private val userStream = UserStream()

    // Connection created on IO thread in start() to avoid main-thread network StrictMode
    @Volatile private var connection: MoshConnection? = null

    // SSP state tracking
    @Volatile private var remoteStateNum: Long = 0    // latest state number received from server
    @Volatile private var serverAckedOurNum: Long = 0 // server's ack of our state
    @Volatile private var lastAckSent: Long = 0       // last ack we sent to server
    @Volatile private var lastSendTimeMs: Long = 0

    // Track whether we have genuinely new data vs just retransmitting
    @Volatile private var lastSentNewNum: Long = 0
    @Volatile private var retransmitCount: Int = 0

    @Volatile private var closed = false
    private var receiveJob: Job? = null
    private var sendJob: Job? = null

    /**
     * Start the transport: opens UDP socket on IO thread, begins receive and send loops.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            if (closed) return@launch
            try {
                connection = MoshConnection(serverIp, port, crypto)
                Log.d(TAG, "UDP socket connected to $serverIp:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create UDP connection", e)
                onDisconnect?.invoke(false)
                return@launch
            }
            // Only one coroutine sends — no race on nonce counter
            receiveJob = launch { receiveLoop() }
            sendJob = launch { sendLoop() }
        }
    }

    /** Enqueue user keystrokes for delivery to the server. */
    fun sendInput(data: ByteArray) {
        if (closed) return
        userStream.pushKeystroke(data)
    }

    /** Enqueue a terminal resize event. */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        userStream.pushResize(cols, rows)
    }

    override fun close() {
        if (closed) return
        closed = true
        receiveJob?.cancel()
        sendJob?.cancel()
        try { connection?.close() } catch (_: Exception) {}
    }

    private suspend fun receiveLoop() {
        try {
            while (!closed) {
                val conn = connection ?: break
                val instruction = try {
                    conn.receiveInstruction(RECV_TIMEOUT_MS)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (e: Exception) {
                    if (!closed) Log.e(TAG, "Receive error: ${e.message}")
                    continue
                }

                if (instruction == null) continue // timeout
                processInstruction(instruction)
            }
        } catch (_: CancellationException) {
            // normal shutdown
        } catch (e: Exception) {
            if (!closed) {
                Log.e(TAG, "Receive loop failed", e)
                onDisconnect?.invoke(false)
            }
        }
    }

    private fun processInstruction(inst: TransportInstruction) {
        // Update server's acknowledgement of our state
        if (inst.ackNum > serverAckedOurNum) {
            serverAckedOurNum = inst.ackNum
            retransmitCount = 0 // server got our data, reset backoff
        }

        // Process terminal output if this advances the remote state
        val diff = inst.diff
        if (diff != null && diff.isNotEmpty() && inst.newNum > remoteStateNum) {
            try {
                val hostMsg = HostMessage.decode(diff)
                for (hi in hostMsg.instructions) {
                    when (hi) {
                        is HostInstruction.HostBytes -> {
                            onOutput(hi.data, 0, hi.data.size)
                        }
                        is HostInstruction.Resize -> {}
                        is HostInstruction.EchoAck -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode HostMessage", e)
            }
            remoteStateNum = inst.newNum
        }
    }

    private suspend fun sendLoop() {
        try {
            // Send initial keepalive immediately
            sendState()

            while (!closed) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastSendTimeMs
                val currentNum = userStream.size
                val hasNewInput = currentNum != lastSentNewNum
                val hasNewAck = remoteStateNum > lastAckSent
                val needsRetransmit = currentNum > serverAckedOurNum

                when {
                    // New keystrokes: send promptly
                    hasNewInput && elapsed >= SEND_MIN_INTERVAL_MS -> sendState()
                    // New ack to send: send soon
                    hasNewAck && elapsed >= ACK_DELAY_MS -> sendState()
                    // Retransmit unacked data: back off exponentially
                    needsRetransmit && elapsed >= retransmitInterval() -> sendState()
                    // Keepalive
                    elapsed >= KEEPALIVE_INTERVAL_MS -> sendState()
                    else -> {
                        // Cap sleep at POLL_INTERVAL_MS so we notice incoming acks quickly
                        val wait = when {
                            hasNewInput -> SEND_MIN_INTERVAL_MS - elapsed
                            hasNewAck -> ACK_DELAY_MS - elapsed
                            needsRetransmit -> retransmitInterval() - elapsed
                            else -> minOf(KEEPALIVE_INTERVAL_MS - elapsed, POLL_INTERVAL_MS)
                        }
                        delay(maxOf(5L, wait))
                    }
                }
            }
        } catch (_: CancellationException) {
            // normal shutdown
        }
    }

    /** Exponential backoff for retransmissions: 100ms, 200ms, 400ms, capped at 1000ms. */
    private fun retransmitInterval(): Long {
        val base = 100L
        val interval = base shl minOf(retransmitCount, 3) // 100, 200, 400, 800
        return minOf(interval, 1000L)
    }

    private fun sendState() {
        if (closed) return
        try {
            val currentNum = userStream.size
            val diff = userStream.diffFrom(serverAckedOurNum)
            val instruction = TransportInstruction(
                protocolVersion = PROTOCOL_VERSION,
                oldNum = serverAckedOurNum,
                newNum = currentNum,
                ackNum = remoteStateNum,
                throwawayNum = serverAckedOurNum,
                diff = diff,
            )
            connection?.sendInstruction(instruction) ?: return
            lastSendTimeMs = System.currentTimeMillis()
            lastAckSent = remoteStateNum

            if (currentNum == lastSentNewNum && currentNum > serverAckedOurNum) {
                retransmitCount++ // same data resent
            } else {
                retransmitCount = 0
            }
            lastSentNewNum = currentNum
        } catch (e: Exception) {
            if (!closed) Log.e(TAG, "Send error", e)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 2
        const val SEND_MIN_INTERVAL_MS = 20L
        const val ACK_DELAY_MS = 20L
        const val POLL_INTERVAL_MS = 100L   // max sleep between send-loop iterations
        const val KEEPALIVE_INTERVAL_MS = 3000L
        const val RECV_TIMEOUT_MS = 250
    }
}
