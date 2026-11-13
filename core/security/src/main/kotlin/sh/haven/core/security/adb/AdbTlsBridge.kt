package sh.haven.core.security.adb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Loopback plain-ADB-to-`A_STLS` bridge (#575 v2).
 *
 * Accepts classic unencrypted ADB TCP connections on `127.0.0.1:[listenPort]`
 * (for example from Haven's MCP SSH reverse tunnel when `adb connect localhost:5555`
 * runs on the workstation), resolves the device's live `_adb-tls-connect._tcp`
 * endpoint on each connection so rotated Wireless Debugging ports never go stale,
 * intercepts `adbd`'s `A_STLS` (`0x534c5453`) upgrade using Haven's paired RSA
 * certificate from [AdbKeyPairStore], and pipes raw ADB frames bidirectionally.
 */
class AdbTlsBridge(
    private val keyPairStore: AdbKeyPairStore,
    private val resolveConnectEndpoint: suspend () -> ConnectEndpoint?,
    private val sslContextFactory: () -> SSLContext = keyPairStore::createConnectSslContext,
) {

    data class ConnectEndpoint(val host: String, val port: Int)

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    val boundPort: Int?
        get() = synchronized(lock) { serverSocket?.takeIf { !it.isClosed }?.localPort }

    val isRunning: Boolean
        get() = boundPort != null

    @Throws(IOException::class)
    fun start(listenPort: Int): Int = synchronized(lock) {
        serverSocket?.takeIf { !it.isClosed }?.let { existing ->
            if (listenPort == 0 || existing.localPort == listenPort) {
                return existing.localPort
            }
            stop()
        }

        val srv = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 16)
        }
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverSocket = srv
        scope = bridgeScope

        bridgeScope.launch {
            while (isActive && !srv.isClosed) {
                val client = try {
                    srv.accept()
                } catch (_: IOException) {
                    break
                }
                activeSockets.add(client)
                launch {
                    try {
                        handleClient(client)
                    } finally {
                        activeSockets.remove(client)
                        runCatching { client.close() }
                    }
                }
            }
        }
        srv.localPort
    }

    fun stop() = synchronized(lock) {
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(activeSockets) {
            activeSockets.forEach { runCatching { it.close() } }
            activeSockets.clear()
        }
        scope?.cancel()
        scope = null
    }

    private suspend fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        val target = resolveConnectEndpoint() ?: return
        val rawUpstream = Socket()
        activeSockets.add(rawUpstream)
        try {
            rawUpstream.tcpNoDelay = true
            rawUpstream.soTimeout = HANDSHAKE_TIMEOUT_MS
            rawUpstream.connect(InetSocketAddress(target.host, target.port), HANDSHAKE_TIMEOUT_MS)

            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = DataOutputStream(client.getOutputStream())
            val upIn = DataInputStream(rawUpstream.getInputStream())
            val upOut = DataOutputStream(rawUpstream.getOutputStream())

            // 1. Read client's initial A_CNXN packet and forward to adbd.
            val cnxnPacket = readAdbPacket(clientIn)
            writeAdbPacket(upOut, cnxnPacket)

            // 2. Read adbd's first response: either A_STLS (Wireless Debugging) or A_CNXN/A_AUTH.
            val firstReply = readAdbPacket(upIn)
            val activeUpstream: Socket = if (firstReply.command == CMD_STLS) {
                writeAdbPacket(upOut, STLS_REPLY_PACKET)
                val ssl = (sslContextFactory().socketFactory.createSocket(
                    rawUpstream,
                    target.host,
                    target.port,
                    true,
                ) as SSLSocket).apply {
                    tcpNoDelay = true
                    soTimeout = HANDSHAKE_TIMEOUT_MS
                    enabledProtocols = arrayOf("TLSv1.3")
                }
                ssl.startHandshake()
                ssl.soTimeout = 0
                ssl
            } else {
                rawUpstream.soTimeout = 0
                writeAdbPacket(clientOut, firstReply)
                rawUpstream
            }

            activeUpstream.use { up ->
                kotlinx.coroutines.coroutineScope {
                    val c2u: Job = launch {
                        pump(client.getInputStream(), up.getOutputStream())
                        runCatching { up.close() }
                    }
                    val u2c: Job = launch {
                        pump(up.getInputStream(), client.getOutputStream())
                        runCatching { client.close() }
                    }
                    c2u.join()
                    u2c.join()
                }
            }
        } catch (_: Exception) {
            // Expected when client disconnects or pairing has not completed yet.
        } finally {
            activeSockets.remove(rawUpstream)
            runCatching { rawUpstream.close() }
        }
    }

    private data class AdbPacket(
        val header: ByteArray,
        val command: Int,
        val payload: ByteArray,
    )

    private fun readAdbPacket(input: DataInputStream): AdbPacket {
        val hdr = ByteArray(ADB_HEADER_BYTES)
        input.readFully(hdr)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.getInt(0)
        val dataLen = bb.getInt(12)
        if (dataLen < 0 || dataLen > MAX_ADB_PAYLOAD_BYTES) {
            throw IOException("Invalid ADB packet payload size $dataLen")
        }
        val payload = if (dataLen > 0) ByteArray(dataLen).also(input::readFully) else EMPTY_BYTES
        return AdbPacket(hdr, cmd, payload)
    }

    private fun writeAdbPacket(out: DataOutputStream, pkt: AdbPacket) {
        out.write(pkt.header)
        if (pkt.payload.isNotEmpty()) {
            out.write(pkt.payload)
        }
        out.flush()
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                input.read(buf)
            } catch (_: IOException) {
                -1
            }
            if (n <= 0) break
            try {
                output.write(buf, 0, n)
                output.flush()
            } catch (_: IOException) {
                break
            }
        }
    }

    companion object {
        private const val ADB_HEADER_BYTES = 24
        private const val MAX_ADB_PAYLOAD_BYTES = 1024 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        internal const val CMD_CNXN = 0x4e584e43
        internal const val CMD_STLS = 0x534c5453
        internal const val STLS_VERSION_1_0 = 0x01000000

        private val EMPTY_BYTES = ByteArray(0)

        private val STLS_REPLY_PACKET: AdbPacket = run {
            val hdr = ByteBuffer.allocate(ADB_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CMD_STLS)
                .putInt(STLS_VERSION_1_0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(CMD_STLS xor -1)
                .array()
            AdbPacket(hdr, CMD_STLS, EMPTY_BYTES)
        }
    }
}
