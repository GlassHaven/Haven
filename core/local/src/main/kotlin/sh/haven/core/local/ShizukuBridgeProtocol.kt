package sh.haven.core.local

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Framed wire protocol shared between `haven_shizuku.c` (`/usr/local/bin/shizuku`
 * and `/usr/local/bin/rish` inside PRoot) and [ShizukuBridgeServer] (#837).
 */
internal object ShizukuBridgeProtocol {

    val MAGIC = byteArrayOf('H'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte())
    const val GUEST_SOCKET_PATH = "/tmp/.haven-shizuku.sock"
    const val SOCKET_FILENAME = ".haven-shizuku.sock"
    const val SOCKET_ENV_VAR = "HAVEN_SHIZUKU_SOCK"

    const val MAX_FRAME_LEN = 65_536
    const val MAX_ARG_COUNT = 1_024
    const val MAX_ARG_BYTES = 131_072
    const val MAX_TERM_BYTES = 256

    const val MSG_STDIN: Int = 0x01
    const val MSG_STDIN_EOF: Int = 0x02
    const val MSG_RESIZE: Int = 0x03

    const val MSG_STDOUT: Int = 0x11
    const val MSG_STDERR: Int = 0x12
    const val MSG_ERROR: Int = 0x13
    const val MSG_EXIT: Int = 0x14

    data class Handshake(
        val usePty: Boolean,
        val rows: Int,
        val cols: Int,
        val term: String,
        val args: List<String>,
    )

    data class Frame(
        val type: Int,
        val payload: ByteArray,
    )

    fun readHandshake(rawIn: InputStream): Handshake {
        val dis = DataInputStream(rawIn)
        val magic = ByteArray(4)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("Invalid Shizuku bridge handshake magic")
        }
        val usePty = dis.readUnsignedByte() != 0
        val rows = dis.readUnsignedShort().let { if (it in 1..500) it else 24 }
        val cols = dis.readUnsignedShort().let { if (it in 1..500) it else 80 }
        val termLen = dis.readUnsignedShort()
        if (termLen > MAX_TERM_BYTES) {
            throw IOException("TERM exceeds $MAX_TERM_BYTES bytes ($termLen)")
        }
        val termBytes = ByteArray(termLen)
        if (termLen > 0) dis.readFully(termBytes)
        val term = String(termBytes, Charsets.UTF_8).ifBlank { "xterm-256color" }

        val argc = dis.readUnsignedShort()
        if (argc > MAX_ARG_COUNT) {
            throw IOException("Argument count exceeds $MAX_ARG_COUNT ($argc)")
        }
        val args = ArrayList<String>(argc)
        for (i in 0 until argc) {
            val argLen = dis.readInt()
            if (argLen < 0 || argLen > MAX_ARG_BYTES) {
                throw IOException("Argument $i length out of bounds ($argLen)")
            }
            val argBytes = ByteArray(argLen)
            if (argLen > 0) dis.readFully(argBytes)
            args.add(String(argBytes, Charsets.UTF_8))
        }
        return Handshake(
            usePty = usePty,
            rows = rows,
            cols = cols,
            term = term,
            args = args,
        )
    }

    fun writeHandshake(rawOut: OutputStream, handshake: Handshake) {
        val dos = DataOutputStream(rawOut)
        dos.write(MAGIC)
        dos.writeByte(if (handshake.usePty) 1 else 0)
        dos.writeShort(handshake.rows)
        dos.writeShort(handshake.cols)
        val termBytes = handshake.term.toByteArray(Charsets.UTF_8)
        require(termBytes.size <= MAX_TERM_BYTES) { "TERM too long" }
        dos.writeShort(termBytes.size)
        dos.write(termBytes)
        require(handshake.args.size <= MAX_ARG_COUNT) { "Too many args" }
        dos.writeShort(handshake.args.size)
        for (arg in handshake.args) {
            val bytes = arg.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_ARG_BYTES) { "Arg too long" }
            dos.writeInt(bytes.size)
            dos.write(bytes)
        }
        dos.flush()
    }

    fun readFrame(rawIn: InputStream): Frame? {
        val dis = DataInputStream(rawIn)
        val type = try {
            dis.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }
        val len = dis.readInt()
        if (len < 0 || len > MAX_FRAME_LEN) {
            throw IOException("Invalid frame length: $len")
        }
        val payload = if (len > 0) {
            ByteArray(len).also { dis.readFully(it) }
        } else {
            ByteArray(0)
        }
        return Frame(type = type, payload = payload)
    }

    fun writeFrame(rawOut: OutputStream, type: Int, payload: ByteArray = ByteArray(0), offset: Int = 0, length: Int = payload.size) {
        require(length in 0..MAX_FRAME_LEN) { "Frame payload out of range: $length" }
        synchronized(rawOut) {
            val hdr = ByteArray(5)
            hdr[0] = (type and 0xff).toByte()
            hdr[1] = ((length ushr 24) and 0xff).toByte()
            hdr[2] = ((length ushr 16) and 0xff).toByte()
            hdr[3] = ((length ushr 8) and 0xff).toByte()
            hdr[4] = (length and 0xff).toByte()
            rawOut.write(hdr)
            if (length > 0) {
                rawOut.write(payload, offset, length)
            }
            rawOut.flush()
        }
    }

    fun writeExitFrame(rawOut: OutputStream, exitCode: Int) {
        val buf = byteArrayOf(
            ((exitCode ushr 24) and 0xff).toByte(),
            ((exitCode ushr 16) and 0xff).toByte(),
            ((exitCode ushr 8) and 0xff).toByte(),
            (exitCode and 0xff).toByte(),
        )
        writeFrame(rawOut, MSG_EXIT, buf)
    }

    fun writeErrorFrame(rawOut: OutputStream, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val cappedLen = bytes.size.coerceAtMost(MAX_FRAME_LEN)
        writeFrame(rawOut, MSG_ERROR, bytes, 0, cappedLen)
    }

    fun parseResizePayload(payload: ByteArray): Pair<Int, Int>? {
        if (payload.size != 4) return null
        val rows = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val cols = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        if (rows <= 0 || cols <= 0) return null
        return rows.coerceAtMost(500) to cols.coerceAtMost(500)
    }
}
