package sh.haven.core.mosh.proto

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf encoder/decoder for the mosh wire format.
 *
 * Supports only the types used by mosh protobufs:
 * - VARINT (wire type 0): uint32, uint64, int32
 * - LEN (wire type 2): bytes, embedded messages
 */
object Protobuf {
    const val WIRE_VARINT = 0
    const val WIRE_64BIT = 1
    const val WIRE_LEN = 2
    const val WIRE_32BIT = 5

    fun encodeVarint(value: Long, out: ByteArrayOutputStream) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write((v.toInt() and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    fun encodeTag(fieldNumber: Int, wireType: Int, out: ByteArrayOutputStream) {
        encodeVarint(((fieldNumber shl 3) or wireType).toLong(), out)
    }

    fun encodeUInt64(fieldNumber: Int, value: Long, out: ByteArrayOutputStream) {
        if (value == 0L) return
        encodeTag(fieldNumber, WIRE_VARINT, out)
        encodeVarint(value, out)
    }

    /** Always encode, even when value is 0 (required for proto2 has_xxx() checks). */
    fun encodeUInt64Always(fieldNumber: Int, value: Long, out: ByteArrayOutputStream) {
        encodeTag(fieldNumber, WIRE_VARINT, out)
        encodeVarint(value, out)
    }

    fun encodeUInt32(fieldNumber: Int, value: Int, out: ByteArrayOutputStream) {
        if (value == 0) return
        encodeTag(fieldNumber, WIRE_VARINT, out)
        encodeVarint(value.toLong() and 0xFFFFFFFFL, out)
    }

    fun encodeInt32(fieldNumber: Int, value: Int, out: ByteArrayOutputStream) {
        if (value == 0) return
        encodeTag(fieldNumber, WIRE_VARINT, out)
        // proto2 int32: sign-extend to 64 bits for negative values
        encodeVarint(value.toLong(), out)
    }

    fun encodeBytes(fieldNumber: Int, value: ByteArray, out: ByteArrayOutputStream) {
        encodeTag(fieldNumber, WIRE_LEN, out)
        encodeVarint(value.size.toLong(), out)
        out.write(value)
    }

    fun encodeMessage(fieldNumber: Int, message: ByteArray, out: ByteArrayOutputStream) {
        encodeTag(fieldNumber, WIRE_LEN, out)
        encodeVarint(message.size.toLong(), out)
        out.write(message)
    }

    class Reader(private val data: ByteArray, private var pos: Int = 0) {
        val hasMore: Boolean get() = pos < data.size

        fun readTag(): Pair<Int, Int> {
            val tag = readVarint().toInt()
            return (tag ushr 3) to (tag and 0x07)
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("Varint too long")
            }
            throw IllegalStateException("Unexpected end of varint")
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > data.size) {
                throw IllegalStateException("Bytes overflow: len=$len pos=$pos size=${data.size}")
            }
            val result = data.copyOfRange(pos, pos + len)
            pos += len
            return result
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_64BIT -> pos += 8
                WIRE_LEN -> {
                    val len = readVarint().toInt()
                    pos += len
                }
                WIRE_32BIT -> pos += 4
                else -> throw IllegalStateException("Unknown wire type: $wireType")
            }
        }
    }
}

// ── TransportBuffers ─────────────────────────────────────────────

/**
 * TransportBuffers::Instruction (transportinstruction.proto)
 *
 * Fields:
 *   1: uint32 protocol_version
 *   2: uint64 old_num
 *   3: uint64 new_num
 *   4: uint64 ack_num
 *   5: uint64 throwaway_num
 *   6: bytes  diff
 *   7: bytes  chaff
 */
data class TransportInstruction(
    val protocolVersion: Int = 0,
    val oldNum: Long = 0,
    val newNum: Long = 0,
    val ackNum: Long = 0,
    val throwawayNum: Long = 0,
    val diff: ByteArray? = null,
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        Protobuf.encodeUInt32(1, protocolVersion, out)
        // Always write these fields — mosh server uses has_xxx() to check presence
        Protobuf.encodeUInt64Always(2, oldNum, out)
        Protobuf.encodeUInt64Always(3, newNum, out)
        Protobuf.encodeUInt64Always(4, ackNum, out)
        Protobuf.encodeUInt64Always(5, throwawayNum, out)
        if (diff != null && diff.isNotEmpty()) {
            Protobuf.encodeBytes(6, diff, out)
        }
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): TransportInstruction {
            val r = Protobuf.Reader(data)
            var pv = 0; var on = 0L; var nn = 0L; var an = 0L; var tn = 0L
            var diff: ByteArray? = null
            while (r.hasMore) {
                val (fn, wt) = r.readTag()
                when (fn) {
                    1 -> pv = r.readVarint().toInt()
                    2 -> on = r.readVarint()
                    3 -> nn = r.readVarint()
                    4 -> an = r.readVarint()
                    5 -> tn = r.readVarint()
                    6 -> diff = r.readBytes()
                    7 -> r.readBytes() // chaff — ignore
                    else -> r.skip(wt)
                }
            }
            return TransportInstruction(pv, on, nn, an, tn, diff)
        }
    }
}

// ── ClientBuffers (userinput.proto) ──────────────────────────────

/**
 * Client → Server state diff.
 *
 * UserMessage { repeated Instruction instruction = 1; }
 * Instruction { extensions 2 to max; }
 * extend Instruction { optional Keystroke keystroke = 2; optional ResizeMessage resize = 3; }
 * Keystroke { optional bytes keys = 4; }
 * ResizeMessage { optional int32 width = 5; optional int32 height = 6; }
 */
sealed class UserInstruction {
    data class Keystroke(val keys: ByteArray) : UserInstruction()
    data class Resize(val width: Int, val height: Int) : UserInstruction()
}

data class UserMessage(val instructions: List<UserInstruction>) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        for (inst in instructions) {
            val instBytes = ByteArrayOutputStream()
            when (inst) {
                is UserInstruction.Keystroke -> {
                    val ksBytes = ByteArrayOutputStream()
                    Protobuf.encodeBytes(4, inst.keys, ksBytes) // Keystroke.keys = 4
                    Protobuf.encodeMessage(2, ksBytes.toByteArray(), instBytes) // ext field 2
                }
                is UserInstruction.Resize -> {
                    val rsBytes = ByteArrayOutputStream()
                    Protobuf.encodeInt32(5, inst.width, rsBytes) // ResizeMessage.width = 5
                    Protobuf.encodeInt32(6, inst.height, rsBytes) // ResizeMessage.height = 6
                    Protobuf.encodeMessage(3, rsBytes.toByteArray(), instBytes) // ext field 3
                }
            }
            Protobuf.encodeMessage(1, instBytes.toByteArray(), out) // UserMessage.instruction = 1
        }
        return out.toByteArray()
    }
}

// ── ServerBuffers (hostinput.proto) ──────────────────────────────

/**
 * Server → Client state diff.
 *
 * HostMessage { repeated Instruction instruction = 1; }
 * Instruction { extensions 2 to max; }
 * extend Instruction { optional HostBytes hostbytes = 2; optional ResizeMessage resize = 3;
 *                       optional EchoAck echoack = 7; }
 * HostBytes { optional bytes hoststring = 4; }
 * ResizeMessage { optional int32 width = 5; optional int32 height = 6; }
 * EchoAck { optional uint64 echo_ack_num = 8; }
 */
sealed class HostInstruction {
    data class HostBytes(val data: ByteArray) : HostInstruction()
    data class Resize(val width: Int, val height: Int) : HostInstruction()
    data class EchoAck(val echoAckNum: Long) : HostInstruction()
}

data class HostMessage(val instructions: List<HostInstruction>) {
    companion object {
        fun decode(data: ByteArray): HostMessage {
            val r = Protobuf.Reader(data)
            val instructions = mutableListOf<HostInstruction>()
            while (r.hasMore) {
                val (fn, wt) = r.readTag()
                when (fn) {
                    1 -> {
                        val instData = r.readBytes()
                        decodeInstruction(instData)?.let { instructions.add(it) }
                    }
                    else -> r.skip(wt)
                }
            }
            return HostMessage(instructions)
        }

        private fun decodeInstruction(data: ByteArray): HostInstruction? {
            val r = Protobuf.Reader(data)
            while (r.hasMore) {
                val (fn, wt) = r.readTag()
                when (fn) {
                    2 -> { // HostBytes extension
                        val hbData = r.readBytes()
                        return decodeHostBytes(hbData)
                    }
                    3 -> { // ResizeMessage extension
                        val rsData = r.readBytes()
                        return decodeResize(rsData)
                    }
                    7 -> { // EchoAck extension
                        val eaData = r.readBytes()
                        return decodeEchoAck(eaData)
                    }
                    else -> r.skip(wt)
                }
            }
            return null
        }

        private fun decodeHostBytes(data: ByteArray): HostInstruction.HostBytes? {
            val r = Protobuf.Reader(data)
            while (r.hasMore) {
                val (fn, wt) = r.readTag()
                when (fn) {
                    4 -> return HostInstruction.HostBytes(r.readBytes()) // hoststring = 4
                    else -> r.skip(wt)
                }
            }
            return null
        }

        private fun decodeResize(data: ByteArray): HostInstruction.Resize {
            val r = Protobuf.Reader(data)
            var w = 0; var h = 0
            while (r.hasMore) {
                val (fn, wt) = r.readTag()
                when (fn) {
                    5 -> w = r.readVarint().toInt()  // width = 5
                    6 -> h = r.readVarint().toInt()  // height = 6
                    else -> r.skip(wt)
                }
            }
            return HostInstruction.Resize(w, h)
        }

        private fun decodeEchoAck(data: ByteArray): HostInstruction.EchoAck {
            val r = Protobuf.Reader(data)
            var num = 0L
            while (r.hasMore) {
                val (fn, wt) = r.readTag()
                when (fn) {
                    8 -> num = r.readVarint() // echo_ack_num = 8
                    else -> r.skip(wt)
                }
            }
            return HostInstruction.EchoAck(num)
        }
    }
}
