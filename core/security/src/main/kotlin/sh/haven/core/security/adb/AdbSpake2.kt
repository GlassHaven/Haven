package sh.haven.core.security.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin implementation of BoringSSL's `spake25519` (`SPAKE2_CTX_new`,
 * `SPAKE2_generate_msg`, `SPAKE2_process_msg`) and AOSP `adbd`'s
 * `PairingAuthCtx` / `PairingConnectionCtx` framing (#575 v2).
 *
 * Verified bit-for-bit against AOSP `adb pair` (`37.0.0`):
 * - Fixed masking points `M` and `N` are `SHA-256("edwards25519 point generation seed (M/N)")`,
 *   NOT RFC 9382's constants (BoringSSL's `spake25519.cc` predates RFC 9382).
 * - Role identifiers include their trailing NUL byte (`"adb pair client\u0000"`,
 *   `"adb pair server\u0000"`, 16 bytes each).
 * - Private scalar `x` is `(rand512 mod L) * 8`; password scalar `w` is
 *   `(SHA-512(password) mod L) + k * L` chosen so `w % 8 == 0`, clearing the
 *   Ed25519 cofactor 8 on both masks and the shared point `K`.
 * - Transcript feeds `uint64_le(len) || bytes` for `(aliceName, bobName, aliceMsg,
 *   bobMsg, K, SHA-512(password))` into `SHA-512`.
 */
class AdbSpake2(
    val role: Role,
    private val random: SecureRandom = SecureRandom(),
) {

    enum class Role {
        CLIENT,
        SERVER,
    }

    private var privateScalar: BigInteger? = null
    private var passwordScalar: BigInteger? = null
    private var passwordHash: ByteArray? = null
    private var myMsg: ByteArray? = null

    fun generateMessage(password: ByteArray): ByteArray {
        check(myMsg == null) { "SPAKE2 message already generated" }

        val rand64 = ByteArray(64).also(random::nextBytes)
        val x = fromLittleEndian(rand64).mod(L).shiftLeft(3)
        privateScalar = x

        val pwHash = MessageDigest.getInstance("SHA-512").digest(password)
        passwordHash = pwHash

        var w = fromLittleEndian(pwHash).mod(L)
        if (w.testBit(0)) w = w.add(L)
        if (w.testBit(1)) w = w.add(L.shiftLeft(1))
        if (w.testBit(2)) w = w.add(L.shiftLeft(2))
        passwordScalar = w

        val myMask = if (role == Role.CLIENT) M_POINT else N_POINT
        val pub = edAdd(edMul(G_POINT, x), edMul(myMask, w))
        val encoded = compress(pub)
        myMsg = encoded
        return encoded.copyOf()
    }

    fun processMessage(theirMsg: ByteArray): ByteArray {
        require(theirMsg.size == MSG_SIZE) { "Expected $MSG_SIZE-byte SPAKE2 message, got ${theirMsg.size}" }
        val x = checkNotNull(privateScalar) { "generateMessage must be called before processMessage" }
        val w = checkNotNull(passwordScalar)
        val pwHash = checkNotNull(passwordHash)
        val localMsg = checkNotNull(myMsg)

        val theirPoint = decompress(theirMsg)
        val theirMask = if (role == Role.CLIENT) N_POINT else M_POINT
        val unmasked = edAdd(theirPoint, edNeg(edMul(theirMask, w)))
        val kBytes = compress(edMul(unmasked, x))

        val transcript = MessageDigest.getInstance("SHA-512")
        if (role == Role.CLIENT) {
            updateLenPrefixed(transcript, CLIENT_NAME_WITH_NUL)
            updateLenPrefixed(transcript, SERVER_NAME_WITH_NUL)
            updateLenPrefixed(transcript, localMsg)
            updateLenPrefixed(transcript, theirMsg)
        } else {
            updateLenPrefixed(transcript, CLIENT_NAME_WITH_NUL)
            updateLenPrefixed(transcript, SERVER_NAME_WITH_NUL)
            updateLenPrefixed(transcript, theirMsg)
            updateLenPrefixed(transcript, localMsg)
        }
        updateLenPrefixed(transcript, kBytes)
        updateLenPrefixed(transcript, pwHash)
        return transcript.digest()
    }

    companion object {
        const val MSG_SIZE = 32
        const val EXPORTED_KEY_SIZE = 64
        const val EXPORT_KEY_LABEL = "adb-label\u0000"

        internal val CLIENT_NAME_WITH_NUL = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        internal val SERVER_NAME_WITH_NUL = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))
        private val D: BigInteger = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P)
        private val SQRT_M1: BigInteger =
            BigInteger.valueOf(2L).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

        private data class EdPoint(val x: BigInteger, val y: BigInteger)

        private val IDENTITY = EdPoint(BigInteger.ZERO, BigInteger.ONE)
        private val G_POINT = decompress(
            hexToBytes("5866666666666666666666666666666666666666666666666666666666666666"),
        )
        private val M_POINT = decompress(
            hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"),
        )
        private val N_POINT = decompress(
            hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"),
        )

        private fun edAdd(p1: EdPoint, p2: EdPoint): EdPoint {
            val x1y2 = p1.x.multiply(p2.y).mod(P)
            val y1x2 = p1.y.multiply(p2.x).mod(P)
            val y1y2 = p1.y.multiply(p2.y).mod(P)
            val x1x2 = p1.x.multiply(p2.x).mod(P)
            val dxxyy = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)
            val x3 = x1y2.add(y1x2)
                .multiply(BigInteger.ONE.add(dxxyy).modInverse(P))
                .mod(P)
            val y3 = y1y2.add(x1x2)
                .multiply(BigInteger.ONE.subtract(dxxyy).mod(P).modInverse(P))
                .mod(P)
            return EdPoint(x3, y3)
        }

        private fun edNeg(pt: EdPoint): EdPoint = EdPoint(P.subtract(pt.x).mod(P), pt.y)

        private fun edMul(base: EdPoint, scalar: BigInteger): EdPoint {
            var res = IDENTITY
            var addend = base
            var k = scalar
            while (k.signum() > 0) {
                if (k.testBit(0)) res = edAdd(res, addend)
                addend = edAdd(addend, addend)
                k = k.shiftRight(1)
            }
            return res
        }

        private fun compress(pt: EdPoint): ByteArray {
            val yLe = toLittleEndian(pt.y, 32)
            if (pt.x.testBit(0)) {
                yLe[31] = (yLe[31].toInt() or 0x80).toByte()
            }
            return yLe
        }

        private fun decompress(bytes: ByteArray): EdPoint {
            require(bytes.size == 32) { "Expected 32-byte Ed25519 point" }
            val sign = (bytes[31].toInt() ushr 7) and 1
            val copy = bytes.copyOf()
            copy[31] = (copy[31].toInt() and 0x7f).toByte()
            val y = fromLittleEndian(copy)
            require(y < P) { "Invalid Ed25519 y coordinate >= p" }

            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val v3 = v.modPow(BigInteger.valueOf(3), P)
            val v7 = v.modPow(BigInteger.valueOf(7), P)
            var x = u.multiply(v3).mod(P)
                .multiply(u.multiply(v7).mod(P).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P))
                .mod(P)
            var vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
            if (vx2 == P.subtract(u).mod(P)) {
                x = x.multiply(SQRT_M1).mod(P)
                vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
            }
            require(vx2 == u) { "Point is not on Ed25519 curve" }
            require(!(x == BigInteger.ZERO && sign == 1)) { "Invalid Ed25519 x=0 with sign=1" }
            if ((if (x.testBit(0)) 1 else 0) != sign) {
                x = P.subtract(x)
            }
            return EdPoint(x, y)
        }

        private fun fromLittleEndian(le: ByteArray): BigInteger {
            val be = ByteArray(le.size + 1)
            for (i in le.indices) {
                be[le.size - i] = le[i]
            }
            return BigInteger(be)
        }

        private fun toLittleEndian(n: BigInteger, len: Int): ByteArray {
            val out = ByteArray(len)
            var cur = n
            for (i in 0 until len) {
                out[i] = cur.toByte()
                cur = cur.shiftRight(8)
            }
            return out
        }

        private fun updateLenPrefixed(md: MessageDigest, data: ByteArray) {
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(data.size.toLong())
                .array()
            md.update(lenBuf)
            md.update(data)
        }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}

/**
 * AOSP `adb::pairing::Aes128Gcm`: derives a 16-byte AES-128-GCM key via
 * `HKDF-SHA256(ikm = spake2Key, salt = empty, info = "adb pairing_auth aes-128-gcm key")`
 * and seals/opens frames using a 12-byte `[seq_le64, 0, 0, 0, 0]` counter nonce.
 */
class AdbPairingCipher(spake2SharedKey: ByteArray) {

    private val keySpec: SecretKeySpec
    private var encSeq: Long = 0L
    private var decSeq: Long = 0L

    init {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(spake2SharedKey)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(HKDF_INFO)
        mac.update(1.toByte())
        val okm = mac.doFinal()
        keySpec = SecretKeySpec(okm.copyOf(16), "AES")
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(0, encSeq++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(0, decSeq++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Wire framing for `PairingPacketHeader` (6 bytes: `version=1`, `type`, `uint32_be payloadSize`)
 * and `PeerInfo` (8192 bytes: `uint8 type`, `uint8 data[8191]` NUL-terminated).
 */
object AdbPairingFraming {
    const val HEADER_VERSION: Byte = 1
    const val PACKET_TYPE_SPAKE2_MSG: Byte = 0
    const val PACKET_TYPE_PEER_INFO: Byte = 1
    const val MAX_PAYLOAD_SIZE = 16_384

    const val PEER_INFO_SIZE = 8_192
    const val PEER_TYPE_RSA_PUB_KEY: Byte = 0
    const val PEER_TYPE_DEVICE_GUID: Byte = 1

    fun writePacket(out: DataOutputStream, type: Byte, payload: ByteArray) {
        require(payload.size in 1..MAX_PAYLOAD_SIZE) { "Invalid pairing payload length ${payload.size}" }
        val hdr = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_VERSION)
            .put(type)
            .putInt(payload.size)
            .array()
        out.write(hdr)
        out.write(payload)
        out.flush()
    }

    fun readPacket(input: DataInputStream, expectedType: Byte): ByteArray {
        val hdrBytes = ByteArray(6)
        input.readFully(hdrBytes)
        val hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.BIG_ENDIAN)
        val ver = hdr.get()
        val type = hdr.get()
        val len = hdr.int
        if (ver != HEADER_VERSION) {
            throw IOException("Unsupported ADB pairing packet version $ver")
        }
        if (type != expectedType) {
            throw IOException("Expected ADB pairing packet type $expectedType, got $type")
        }
        if (len !in 1..MAX_PAYLOAD_SIZE) {
            throw IOException("Invalid ADB pairing payload size $len")
        }
        return ByteArray(len).also(input::readFully)
    }

    fun encodePeerInfo(type: Byte, value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        require(raw.size < PEER_INFO_SIZE - 1) { "PeerInfo payload exceeds ${PEER_INFO_SIZE - 2} bytes" }
        val buf = ByteArray(PEER_INFO_SIZE)
        buf[0] = type
        System.arraycopy(raw, 0, buf, 1, raw.size)
        return buf
    }

    fun decodePeerInfo(bytes: ByteArray): Pair<Byte, String> {
        if (bytes.size != PEER_INFO_SIZE) {
            throw IOException("Expected $PEER_INFO_SIZE-byte PeerInfo, got ${bytes.size}")
        }
        val type = bytes[0]
        var end = 1
        while (end < bytes.size && bytes[end].toInt() != 0) {
            end++
        }
        return type to String(bytes, 1, end - 1, Charsets.UTF_8)
    }
}
