package sh.haven.core.mosh.crypto

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.OCBBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * AES-128-OCB encryption/decryption for the mosh protocol.
 *
 * Packet wire format: [8-byte nonce][ciphertext + 16-byte auth tag]
 * OCB nonce (12 bytes): [4 zero bytes][8-byte nonce value]
 * Nonce value: high bit = direction (0=to_server/client sends, 1=to_client/server sends),
 *              remaining 63 bits = sequence number.
 */
class MoshCrypto(keyBase64: String) {

    private val keyParam: KeyParameter

    init {
        val padded = when (keyBase64.length % 4) {
            2 -> keyBase64 + "=="
            3 -> keyBase64 + "="
            else -> keyBase64
        }
        val keyBytes = java.util.Base64.getDecoder().decode(padded)
        require(keyBytes.size == KEY_LEN) { "Key must be $KEY_LEN bytes, got ${keyBytes.size}" }
        keyParam = KeyParameter(keyBytes)
    }

    /**
     * Encrypt plaintext with the given nonce value.
     * @return packet bytes: 8-byte nonce + ciphertext + 16-byte tag
     */
    fun encrypt(nonceVal: Long, plaintext: ByteArray): ByteArray {
        val nonce12 = makeOcbNonce(nonceVal)
        val params = AEADParameters(keyParam, TAG_BITS, nonce12)

        val ocb = OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance())
        ocb.init(true, params)

        val output = ByteArray(ocb.getOutputSize(plaintext.size))
        var len = ocb.processBytes(plaintext, 0, plaintext.size, output, 0)
        len += ocb.doFinal(output, len)

        val packet = ByteArray(WIRE_NONCE_LEN + len)
        putBE64(packet, 0, nonceVal)
        System.arraycopy(output, 0, packet, WIRE_NONCE_LEN, len)
        return packet
    }

    /**
     * Decrypt a packet.
     * @param packet 8-byte nonce + ciphertext + 16-byte tag
     * @return (nonceVal, plaintext)
     * @throws org.bouncycastle.crypto.InvalidCipherTextException on auth failure
     */
    fun decrypt(packet: ByteArray): Pair<Long, ByteArray> {
        require(packet.size >= WIRE_NONCE_LEN + TAG_LEN) { "Packet too short: ${packet.size}" }

        val nonceVal = getBE64(packet, 0)
        val nonce12 = makeOcbNonce(nonceVal)
        val params = AEADParameters(keyParam, TAG_BITS, nonce12)

        val ocb = OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance())
        ocb.init(false, params)

        val ciphertextLen = packet.size - WIRE_NONCE_LEN
        val output = ByteArray(ocb.getOutputSize(ciphertextLen))
        var len = ocb.processBytes(packet, WIRE_NONCE_LEN, ciphertextLen, output, 0)
        len += ocb.doFinal(output, len)

        return nonceVal to output.copyOf(len)
    }

    private fun makeOcbNonce(val64: Long): ByteArray {
        val nonce = ByteArray(OCB_NONCE_LEN) // first 4 bytes are zero
        putBE64(nonce, 4, val64)
        return nonce
    }

    companion object {
        const val KEY_LEN = 16           // 128-bit key
        const val OCB_NONCE_LEN = 12     // OCB nonce length
        const val TAG_LEN = 16           // 128-bit auth tag
        const val TAG_BITS = 128
        const val WIRE_NONCE_LEN = 8     // nonce bytes in packet header

        /** Client→Server packets use direction bit 0 */
        const val DIRECTION_TO_SERVER: Long = 0L
        /** Server→Client packets use direction bit 1 (high bit set) */
        const val DIRECTION_TO_CLIENT: Long = 1L shl 63

        fun putBE64(buf: ByteArray, off: Int, v: Long) {
            buf[off]     = (v ushr 56).toByte()
            buf[off + 1] = (v ushr 48).toByte()
            buf[off + 2] = (v ushr 40).toByte()
            buf[off + 3] = (v ushr 32).toByte()
            buf[off + 4] = (v ushr 24).toByte()
            buf[off + 5] = (v ushr 16).toByte()
            buf[off + 6] = (v ushr 8).toByte()
            buf[off + 7] = v.toByte()
        }

        fun getBE64(buf: ByteArray, off: Int): Long {
            return ((buf[off].toLong() and 0xFF) shl 56) or
                ((buf[off + 1].toLong() and 0xFF) shl 48) or
                ((buf[off + 2].toLong() and 0xFF) shl 40) or
                ((buf[off + 3].toLong() and 0xFF) shl 32) or
                ((buf[off + 4].toLong() and 0xFF) shl 24) or
                ((buf[off + 5].toLong() and 0xFF) shl 16) or
                ((buf[off + 6].toLong() and 0xFF) shl 8) or
                (buf[off + 7].toLong() and 0xFF)
        }
    }
}
