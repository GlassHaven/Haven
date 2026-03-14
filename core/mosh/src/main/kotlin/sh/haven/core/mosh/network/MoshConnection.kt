package sh.haven.core.mosh.network

import sh.haven.core.mosh.crypto.MoshCrypto
import sh.haven.core.mosh.crypto.MoshCrypto.Companion.DIRECTION_TO_CLIENT
import sh.haven.core.mosh.crypto.MoshCrypto.Companion.DIRECTION_TO_SERVER
import sh.haven.core.mosh.crypto.MoshCrypto.Companion.getBE64
import sh.haven.core.mosh.crypto.MoshCrypto.Companion.putBE64
import sh.haven.core.mosh.proto.TransportInstruction
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * UDP connection with mosh packet encryption, timestamps, zlib compression,
 * and fragment reassembly.
 *
 * Packet wire format: [8-byte nonce][AES-128-OCB(plaintext) + 16-byte tag]
 * Plaintext: [2-byte timestamp][2-byte timestamp_reply][fragment data]
 * Fragment: [8-byte fragment_id][2-byte (final<<15 | frag_num)][payload]
 *
 * After fragment reassembly, the payload is zlib-decompressed, then parsed
 * as a TransportBuffers::Instruction protobuf.
 */
class MoshConnection(
    serverIp: String,
    port: Int,
    private val crypto: MoshCrypto,
) : Closeable {

    private val serverAddr = InetAddress.getByName(serverIp)
    private val serverPort = port
    // Use unconnected socket to avoid Android propagating ICMP errors as exceptions
    private val socket = DatagramSocket()

    private var sendNonceSeq = 0L
    private var fragmentIdCounter = 0

    // Timestamp tracking for RTT estimation
    @Volatile var lastReceivedTimestamp: Int = 0
        private set

    // Fragment reassembly buffer
    private val fragmentBuffer = mutableMapOf<Long, FragmentAssembly>()

    private class FragmentAssembly {
        var totalExpected: Int = -1
        val fragments = mutableMapOf<Int, ByteArray>()
    }

    /**
     * Send a TransportInstruction, compressing and fragmenting as needed.
     */
    fun sendInstruction(instruction: TransportInstruction) {
        val serialized = instruction.encode()
        val payload = zlibCompress(serialized)
        val fragId = (fragmentIdCounter++).toLong() and 0xFFFFL

        if (payload.size <= MAX_FRAG_PAYLOAD) {
            sendFragment(fragId, 0, true, payload)
        } else {
            val numFragments = (payload.size + MAX_FRAG_PAYLOAD - 1) / MAX_FRAG_PAYLOAD
            for (i in 0 until numFragments) {
                val start = i * MAX_FRAG_PAYLOAD
                val end = minOf(start + MAX_FRAG_PAYLOAD, payload.size)
                sendFragment(fragId, i, i == numFragments - 1,
                    payload.copyOfRange(start, end))
            }
        }
    }

    private fun sendFragment(
        fragmentId: Long, fragmentNum: Int, isFinal: Boolean, data: ByteArray,
    ) {
        // Build fragment: 8-byte ID + 2-byte combined + payload
        val fragData = ByteArray(FRAG_HEADER_LEN + data.size)
        putBE64(fragData, 0, fragmentId)
        val combined = ((if (isFinal) 1 else 0) shl 15) or (fragmentNum and 0x7FFF)
        fragData[8] = (combined ushr 8).toByte()
        fragData[9] = combined.toByte()
        System.arraycopy(data, 0, fragData, FRAG_HEADER_LEN, data.size)

        // Prepend 4-byte timestamps to the plaintext
        val timestamp = (System.currentTimeMillis() % 65536).toInt()
        val plaintext = ByteArray(TIMESTAMP_LEN + fragData.size)
        plaintext[0] = (timestamp ushr 8).toByte()
        plaintext[1] = timestamp.toByte()
        plaintext[2] = (lastReceivedTimestamp ushr 8).toByte()
        plaintext[3] = lastReceivedTimestamp.toByte()
        System.arraycopy(fragData, 0, plaintext, TIMESTAMP_LEN, fragData.size)

        // Encrypt with client→server direction
        val nonceVal = DIRECTION_TO_SERVER or sendNonceSeq++
        val packet = crypto.encrypt(nonceVal, plaintext)
        socket.send(DatagramPacket(packet, packet.size, serverAddr, serverPort))
    }

    /**
     * Receive and reassemble a complete TransportInstruction.
     * @return the instruction, or null on timeout
     */
    fun receiveInstruction(timeoutMs: Int): TransportInstruction? {
        socket.soTimeout = timeoutMs

        while (true) {
            val buf = ByteArray(RECV_BUF_SIZE)
            val dp = DatagramPacket(buf, buf.size)
            try {
                socket.receive(dp)
            } catch (_: SocketTimeoutException) {
                return null
            }

            val packet = dp.data.copyOf(dp.length)

            val (nonceVal, plaintext) = try {
                crypto.decrypt(packet)
            } catch (_: Exception) {
                continue // auth failure or malformed
            }

            // Validate direction: must be server→client (high bit set)
            if (nonceVal and DIRECTION_TO_CLIENT == 0L) continue

            // Need at least timestamp (4 bytes) + fragment header (10 bytes)
            if (plaintext.size < TIMESTAMP_LEN + FRAG_HEADER_LEN) continue

            // Extract timestamps
            val timestamp = ((plaintext[0].toInt() and 0xFF) shl 8) or
                (plaintext[1].toInt() and 0xFF)
            lastReceivedTimestamp = timestamp

            // Parse fragment (after timestamp bytes)
            val fragStart = TIMESTAMP_LEN
            val fragmentId = getBE64(plaintext, fragStart)
            val combined = ((plaintext[fragStart + 8].toInt() and 0xFF) shl 8) or
                (plaintext[fragStart + 9].toInt() and 0xFF)
            val isFinal = (combined and 0x8000) != 0
            val fragmentNum = combined and 0x7FFF
            val fragPayload = plaintext.copyOfRange(
                fragStart + FRAG_HEADER_LEN, plaintext.size
            )

            // Single-fragment message (common case)
            if (isFinal && fragmentNum == 0) {
                return try {
                    val decompressed = zlibDecompress(fragPayload)
                    TransportInstruction.decode(decompressed)
                } catch (_: Exception) {
                    continue
                }
            }

            // Multi-fragment reassembly
            val assembly = fragmentBuffer.getOrPut(fragmentId) { FragmentAssembly() }
            assembly.fragments[fragmentNum] = fragPayload
            if (isFinal) {
                assembly.totalExpected = fragmentNum + 1
            }

            if (assembly.totalExpected > 0 &&
                assembly.fragments.size == assembly.totalExpected
            ) {
                val full = ByteArrayOutputStream()
                for (i in 0 until assembly.totalExpected) {
                    full.write(assembly.fragments[i] ?: continue)
                }
                fragmentBuffer.remove(fragmentId)
                // Evict old incomplete assemblies
                if (fragmentBuffer.size > MAX_PENDING_ASSEMBLIES) {
                    fragmentBuffer.keys.firstOrNull()?.let { fragmentBuffer.remove(it) }
                }
                return try {
                    val decompressed = zlibDecompress(full.toByteArray())
                    TransportInstruction.decode(decompressed)
                } catch (_: Exception) {
                    continue
                }
            }
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        const val FRAG_HEADER_LEN = 10
        const val TIMESTAMP_LEN = 4
        // MTU 1280 - IP(40) - UDP(8) - nonce(8) - tag(16) - timestamp(4) - frag_hdr(10) = 1194
        const val MAX_FRAG_PAYLOAD = 1194
        const val RECV_BUF_SIZE = 2048
        const val MAX_PENDING_ASSEMBLIES = 16

        fun zlibCompress(data: ByteArray): ByteArray {
            val deflater = Deflater()
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream(data.size)
                val buf = ByteArray(1024)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buf)
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        fun zlibDecompress(data: ByteArray): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream(data.size * 2)
                val buf = ByteArray(4096)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && inflater.needsInput()) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}
