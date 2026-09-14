package sh.haven.core.usb.massstorage

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * A minimal NBD (network block device) server exporting one block backend on
 * host loopback, so the UML guest's `nbd-client` can reach a phone-attached
 * USB mass-storage card through passt (guest 169.254.2.2 maps to host
 * 127.0.0.1 — see uml-transport/PROTOTYPE.md).
 *
 * Fixed-newstyle handshake only: NBD_OPT_LIST / NBD_OPT_GO, with
 * NBD_OPT_STRUCTURED_REPLY answered NBD_REP_ERR_UNSUP so the client falls
 * back to simple replies, and NBD_OPT_EXPORT_NAME kept as the legacy
 * fallback. Transmission phase is simple replies for READ/WRITE/FLUSH/DISC.
 *
 * One instance serves one export. Connections are accepted serially but each
 * gets its own thread; all block access funnels through [NbdBackend], whose
 * real implementation serialises SCSI commands internally.
 */
class NbdServer(
    private val backend: NbdBackend,
    private val exportName: String = "haven-sd",
    /** Called on the server thread when a connection ends (null = clean DISC). */
    private val onSessionEnd: ((Throwable?) -> Unit)? = null,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val clientSockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    val isRunning: Boolean get() = serverSocket != null
    val port: Int get() = serverSocket?.localPort ?: -1

    /**
     * Bind 127.0.0.1 and start accepting. Pass [port] 0 to pick an ephemeral
     * port. SO_REUSEADDR is set before bind so a rapid restart (stop() then
     * start() while the old port sits in TIME_WAIT) succeeds.
     */
    @Synchronized
    fun start(port: Int = 0): Int {
        if (serverSocket != null) return serverSocket!!.localPort
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), BACKLOG)
        }
        serverSocket = socket
        thread(name = "haven-nbd-accept", isDaemon = true) {
            while (serverSocket === socket) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (serverSocket === socket) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                thread(name = "haven-nbd-conn", isDaemon = true) { serve(client) }
            }
        }
        Log.i(TAG, "NBD export \"$exportName\" (${backend.sizeBytes} B, readOnly=${backend.readOnly}) on 127.0.0.1:${socket.localPort}")
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        // accept() closing does not wake accepted connections blocked on read.
        synchronized(clientSockets) { clientSockets.toList() }.forEach { runCatching { it.close() } }
        clientSockets.clear()
    }

    private fun serve(client: Socket) {
        clientSockets.add(client)
        var failure: Throwable? = null
        try {
            client.use { sock ->
                sock.tcpNoDelay = true
                sock.soTimeout = SO_TIMEOUT_MS
                serveConnection(
                    DataInputStream(sock.getInputStream().buffered()),
                    DataOutputStream(sock.getOutputStream()),
                )
            }
        } catch (e: Exception) {
            failure = e
        } finally {
            clientSockets.remove(client)
            onSessionEnd?.invoke(failure)
        }
    }

    private fun serveConnection(input: DataInputStream, output: DataOutputStream) {
        // Phase 1: initial handshake. NBDMAGIC + IHAVEOPT + flags.
        output.writeLong(NBDMAGIC)
        output.writeLong(IHAVEOPT)
        output.writeShort(HS_FIXED_NEWSTYLE or HS_NO_ZEROES)
        output.flush()
        val clientFlags = input.readInt()
        val noZeroes = (clientFlags and HS_C_NO_ZEROES) != 0

        // Phase 2: option haggling until GO / EXPORT_NAME puts us in transmission.
        while (true) {
            val magic = input.readLong()
            if (magic != IHAVEOPT) throw IOException("bad option magic 0x${"%016x".format(magic)}")
            val option = input.readInt()
            val length = input.readInt()
            val payload = if (length > 0) ByteArray(length).also { input.readFully(it) } else ByteArray(0)
            when (option) {
                NBD_OPT_GO, NBD_OPT_INFO -> {
                    val name = optionExportName(payload)
                    if (name != exportName && name.isNotEmpty()) {
                        reply(output, option, NBD_REP_ERR_UNKNOWN_EXPORT, ByteArray(0))
                        continue
                    }
                    // NBD_INFO_EXPORT: size + transmission flags.
                    val info = ByteArray(2 + 8 + 2)
                    writeU16Be(info, 0, NBD_INFO_EXPORT)
                    writeU64Be(info, 2, backend.sizeBytes)
                    writeU16Be(info, 10, transmissionFlags())
                    reply(output, option, NBD_REP_INFO, info)
                    // NBD_INFO_BLOCK_SIZE: minimum / preferred / maximum request sizes.
                    val sizes = ByteArray(2 + 12)
                    writeU16Be(sizes, 0, NBD_INFO_BLOCK_SIZE)
                    writeU32Be(sizes, 2, 512L)
                    writeU32Be(sizes, 6, PREFERRED_BLOCK.toLong())
                    writeU32Be(sizes, 10, MAX_TRANSFER_BYTES.toLong())
                    reply(output, option, NBD_REP_INFO, sizes)
                    reply(output, option, NBD_REP_ACK, ByteArray(0))
                    return serveTransmission(input, output)
                }
                NBD_OPT_EXPORT_NAME -> {
                    // Legacy fallback: size + flags, then the client's 124-byte
                    // padding unless NO_ZEROES was negotiated.
                    val trailer = ByteArray(8 + 2)
                    writeU64Be(trailer, 0, backend.sizeBytes)
                    writeU16Be(trailer, 8, transmissionFlags())
                    output.write(trailer)
                    output.flush()
                    if (!noZeroes) {
                        input.readFully(ByteArray(EXPORT_NAME_ZEROES))
                    }
                    return serveTransmission(input, output)
                }
                NBD_OPT_LIST -> {
                    val name = exportName.toByteArray(Charsets.US_ASCII)
                    val serverReply = ByteArray(4 + name.size)
                    writeU32Be(serverReply, 0, name.size.toLong())
                    name.copyInto(serverReply, 4)
                    reply(output, option, NBD_REP_SERVER, serverReply)
                    reply(output, option, NBD_REP_ACK, ByteArray(0))
                }
                NBD_OPT_ABORT -> {
                    reply(output, option, NBD_REP_ACK, ByteArray(0))
                    return
                }
                // Structured replies, TLS, meta contexts, anything else: unsupported.
                else -> reply(output, option, NBD_REP_ERR_UNSUP, ByteArray(0))
            }
        }
    }

    /** Transmission phase: NBD_CMD_READ/WRITE/FLUSH/DISC with simple replies. */
    private fun serveTransmission(input: DataInputStream, output: DataOutputStream) {
        while (true) {
            val magic = input.readInt()
            if (magic != NBD_REQUEST_MAGIC) throw IOException("bad request magic 0x${"%08x".format(magic)}")
            val flags = input.readShort().toInt() and 0xFFFF
            val type = input.readShort().toInt() and 0xFFFF
            val cookie = input.readLong()
            val offset = input.readLong()
            val length = input.readInt()
            when (type) {
                NBD_CMD_READ -> {
                    val err = checkRequest(offset, length)
                    if (err != 0) {
                        replyTransmission(output, err, cookie)
                        continue
                    }
                    val data = try {
                        backend.readAt(offset, length)
                    } catch (e: IOException) {
                        Log.w(TAG, "READ at $offset+$length failed: ${e.message}")
                        replyTransmission(output, NBD_EIO, cookie)
                        continue
                    }
                    synchronized(output) {
                        output.writeInt(NBD_SIMPLE_REPLY_MAGIC)
                        output.writeInt(NBD_SUCCESS)
                        output.writeLong(cookie)
                        output.write(data)
                        output.flush()
                    }
                }
                NBD_CMD_WRITE -> {
                    val err = when {
                        backend.readOnly -> NBD_EPERM
                        else -> checkRequest(offset, length)
                    }
                    if (err != 0) {
                        // Drain the payload so the stream stays in sync, then report.
                        input.readFully(ByteArray(length))
                        replyTransmission(output, err, cookie)
                        continue
                    }
                    val data = ByteArray(length)
                    input.readFully(data)
                    try {
                        backend.writeAt(offset, data)
                        if (flags and NBD_CMD_FLAG_FUA != 0) backend.flush()
                        replyTransmission(output, NBD_SUCCESS, cookie)
                    } catch (e: IOException) {
                        Log.w(TAG, "WRITE at $offset+$length failed: ${e.message}")
                        replyTransmission(output, NBD_EIO, cookie)
                    }
                }
                NBD_CMD_FLUSH -> {
                    val err = try {
                        backend.flush()
                        NBD_SUCCESS
                    } catch (e: IOException) {
                        Log.w(TAG, "FLUSH failed: ${e.message}")
                        NBD_EIO
                    }
                    replyTransmission(output, err, cookie)
                }
                NBD_CMD_DISC -> return
                else -> replyTransmission(output, NBD_EINVAL, cookie)
            }
        }
    }

    /** 0 when the request is in range and within the size cap, else an errno. */
    private fun checkRequest(offset: Long, length: Int): Int = when {
        length < 0 || length > MAX_TRANSFER_BYTES -> NBD_EINVAL
        offset < 0 || length.toLong() > backend.sizeBytes - offset -> NBD_EINVAL
        else -> NBD_SUCCESS
    }

    private fun transmissionFlags(): Int {
        var flags = NBD_FLAG_HAS_FLAGS or NBD_FLAG_SEND_FLUSH
        if (backend.readOnly) flags = flags or NBD_FLAG_READ_ONLY
        return flags
    }

    /** Extract the export name a client named in NBD_OPT_GO/INFO data. */
    private fun optionExportName(payload: ByteArray): String {
        // NBD_OPT_GO data: length-prefixed name, then 32-bit info-count and pairs.
        if (payload.size < 4) return ""
        val nameLen = readU32Be(payload, 0).toInt()
        if (nameLen < 0 || 4L + nameLen > payload.size) return ""
        return String(payload, 4, nameLen, Charsets.US_ASCII)
    }

    private fun reply(output: DataOutputStream, option: Int, replyType: Int, data: ByteArray) {
        synchronized(output) {
            output.writeLong(NBD_REP_MAGIC)
            output.writeInt(option)
            output.writeInt(replyType)
            output.writeInt(data.size)
            output.write(data)
            output.flush()
        }
    }

    private fun replyTransmission(output: DataOutputStream, err: Int, cookie: Long) {
        synchronized(output) {
            output.writeInt(NBD_SIMPLE_REPLY_MAGIC)
            output.writeInt(err)
            output.writeLong(cookie)
            output.flush()
        }
    }

    companion object {
        private const val TAG = "haven-nbd"
        private const val BACKLOG = 4
        /** A wedged card errors out instead of hanging the connection forever. */
        private const val SO_TIMEOUT_MS = 120_000

        // Fixed-newstyle constants (NBD proto doc).
        const val NBDMAGIC = 0x4E42444D41474943 // "NBDMAGIC"
        const val IHAVEOPT = 0x49484156454F5054 // "IHAVEOPT"
        const val NBD_REP_MAGIC = 0x0003E889045565A9L
        const val NBD_REQUEST_MAGIC = 0x25609513
        const val NBD_SIMPLE_REPLY_MAGIC = 0x67446698

        const val HS_FIXED_NEWSTYLE = 1
        const val HS_NO_ZEROES = 2
        const val HS_C_FIXED_NEWSTYLE = 1
        const val HS_C_NO_ZEROES = 2

        const val NBD_OPT_EXPORT_NAME = 1
        const val NBD_OPT_ABORT = 2
        const val NBD_OPT_LIST = 3
        const val NBD_OPT_INFO = 6
        const val NBD_OPT_GO = 7
        const val NBD_OPT_STRUCTURED_REPLY = 8

        const val NBD_REP_ACK = 1
        const val NBD_REP_SERVER = 2
        const val NBD_REP_INFO = 3
        // High-bit reply types as signed 32-bit values.
        const val NBD_REP_ERR_UNSUP = -0x7FFFFFFF        // 0x80000001
        const val NBD_REP_ERR_UNKNOWN_EXPORT = -0x7FFFFFFB // 0x80000005

        const val NBD_FLAG_HAS_FLAGS = 1
        const val NBD_FLAG_READ_ONLY = 2
        const val NBD_FLAG_SEND_FLUSH = 4

        const val NBD_INFO_EXPORT = 0
        const val NBD_INFO_BLOCK_SIZE = 3

        const val NBD_CMD_READ = 0
        const val NBD_CMD_WRITE = 1
        const val NBD_CMD_DISC = 2
        const val NBD_CMD_FLUSH = 3
        const val NBD_CMD_FLAG_FUA = 1

        const val NBD_SUCCESS = 0
        const val NBD_EPERM = 1
        const val NBD_EIO = 5
        const val NBD_EINVAL = 22

        /** READ CAPACITY-derived size, but capped by READ(10)'s 16-bit count. */
        const val MAX_TRANSFER_BYTES = 0xFFFF * SECTOR_SIZE
        const val PREFERRED_BLOCK = 64 * 1024
        private const val EXPORT_NAME_ZEROES = 124
    }
}

/** The block-level surface an [NbdServer] export serves. */
interface NbdBackend {
    val sizeBytes: Long
    val readOnly: Boolean
    fun readAt(offset: Long, length: Int): ByteArray
    fun writeAt(offset: Long, data: ByteArray)
    fun flush()
}

/** Export a USB mass-storage card through its SCSI layer. */
class MassStorageBackend(
    private val device: MassStorageDevice,
    /** Advertised via NBD_FLAG_READ_ONLY; the NbdServer refuses CMD_WRITE. */
    override val readOnly: Boolean = false,
) : NbdBackend {
    override val sizeBytes: Long get() = device.sectors * device.blockSize

    override fun readAt(offset: Long, length: Int): ByteArray {
        val (lba, blocks) = toBlocks(offset, length)
        return device.readBlocks(lba, blocks)
    }

    override fun writeAt(offset: Long, data: ByteArray) {
        val (lba, blocks) = toBlocks(offset, data.size)
        device.writeBlocks(lba, blocks, data)
    }

    override fun flush() {
        // USB mass storage has no cache-flush equivalent we can trust through
        // the broker; SYNCHRONIZE CACHE(10) is a candidate follow-up.
    }

    private fun toBlocks(offset: Long, length: Int): Pair<Long, Int> {
        require(length % device.blockSize == 0 && offset % device.blockSize == 0L) {
            "unaligned NBD request: offset $offset length $length"
        }
        return (offset / device.blockSize) to (length / device.blockSize)
    }
}

/** Big-endian field helpers for the wire format (NBD fields are BE; the
 *  32-bit variants live in ScsiBbb.kt alongside the SCSI builders). */
internal fun writeU16Be(b: ByteArray, off: Int, v: Int) {
    b[off] = ((v shr 8) and 0xFF).toByte()
    b[off + 1] = (v and 0xFF).toByte()
}

internal fun writeU64Be(b: ByteArray, off: Int, v: Long) {
    writeU32Be(b, off, (v ushr 32) and 0xFFFFFFFFL)
    writeU32Be(b, off + 4, v and 0xFFFFFFFFL)
}

internal fun readU16Be(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

internal fun readU64Be(b: ByteArray, off: Int): Long =
    (readU32Be(b, off) shl 32) or readU32Be(b, off + 4)