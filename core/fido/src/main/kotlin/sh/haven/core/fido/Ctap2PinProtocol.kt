package sh.haven.core.fido

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CTAP2 PIN/UV Auth Protocol implementations (v1 and v2) per CTAP 2.1 §6.5.
 *
 * The protocol establishes a shared secret with the authenticator over ECDH
 * (P-256), then uses that secret to encrypt a PIN hash and authenticate
 * subsequent commands. Once the authenticator returns a `pinUvAuthToken`, the
 * platform can sign request payloads (e.g. clientDataHash) with HMAC-SHA-256
 * to prove user verification was performed.
 *
 * Protocol v2 uses HKDF-derived split keys (32-byte HMAC + 32-byte AES) with
 * a random IV; v1 uses raw SHA-256(Z) for both with a zero IV. v2 is preferred
 * when advertised by the authenticator (`pinUvAuthProtocols` in GetInfo).
 */
sealed class Ctap2PinProtocol(val version: Int) {

    /**
     * Generate an ephemeral P-256 keypair. The public half is sent to the
     * authenticator as the platform's keyAgreement; the private half is used
     * with the authenticator's keyAgreement to derive the shared secret.
     */
    fun generateEphemeralKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    /** Compute the raw 32-byte ECDH shared X coordinate (Z in CTAP terms). */
    fun ecdh(privateKey: ECPrivateKey, peerPublic: ECPublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublic, true)
        return ka.generateSecret()
    }

    /**
     * Derive the protocol's "shared secret" from the raw ECDH output Z.
     * v1: SHA-256(Z) — 32 bytes, used as both AES key and HMAC key.
     * v2: HKDF-SHA-256(salt=zeros, IKM=Z) → 32B HMAC || 32B AES — 64 bytes.
     */
    abstract fun deriveSharedSecret(z: ByteArray): ByteArray

    /**
     * Encrypt with the protocol's AES key. For v1 the IV is fixed zeros and
     * not prepended; for v2 a random IV is prepended to the ciphertext.
     * The plaintext must be a multiple of 16 bytes (AES block size).
     */
    abstract fun encrypt(sharedSecret: ByteArray, plaintext: ByteArray): ByteArray

    /** Reverse of [encrypt]. */
    abstract fun decrypt(sharedSecret: ByteArray, ciphertext: ByteArray): ByteArray

    /**
     * Authenticate a message under the protocol's HMAC key. v1 truncates to
     * 16 bytes; v2 returns the full 32-byte HMAC-SHA-256.
     */
    abstract fun authenticate(sharedSecret: ByteArray, message: ByteArray): ByteArray

    /**
     * Decode an authenticator-supplied COSE_Key into a JCA ECPublicKey on
     * P-256. CTAP2 always sends EC2 / P-256 keyAgreements for PIN protocol.
     */
    fun coseKeyToEcPublic(x: ByteArray, y: ByteArray): ECPublicKey {
        require(x.size == 32 && y.size == 32) {
            "P-256 coords must be 32 bytes (got x=${x.size}, y=${y.size})"
        }
        val params = ecP256Params()
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        val spec = ECPublicKeySpec(point, params)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    /** Extract the 32-byte big-endian X and Y coordinates from a JCA ECPublicKey. */
    fun ecPublicToCoseCoords(pub: ECPublicKey): Pair<ByteArray, ByteArray> {
        val w = pub.w
        return padTo32(w.affineX.toByteArray()) to padTo32(w.affineY.toByteArray())
    }

    object V1 : Ctap2PinProtocol(1) {
        private val zeroIv = ByteArray(16)

        override fun deriveSharedSecret(z: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(z)

        override fun encrypt(sharedSecret: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(zeroIv))
            return cipher.doFinal(plaintext)
        }

        override fun decrypt(sharedSecret: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(zeroIv))
            return cipher.doFinal(ciphertext)
        }

        override fun authenticate(sharedSecret: ByteArray, message: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
            return mac.doFinal(message).copyOfRange(0, 16) // v1: truncated to 16 bytes
        }
    }

    object V2 : Ctap2PinProtocol(2) {
        private val rng = SecureRandom()

        override fun deriveSharedSecret(z: ByteArray): ByteArray {
            val prk = hmacSha256(ByteArray(32), z) // HKDF-Extract with zero salt
            val hmacKey = hkdfExpand(prk, "CTAP2 HMAC key".toByteArray(), 32)
            val aesKey = hkdfExpand(prk, "CTAP2 AES key".toByteArray(), 32)
            return hmacKey + aesKey
        }

        override fun encrypt(sharedSecret: ByteArray, plaintext: ByteArray): ByteArray {
            val aesKey = sharedSecret.copyOfRange(32, 64)
            val iv = ByteArray(16).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            return iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(sharedSecret: ByteArray, ciphertext: ByteArray): ByteArray {
            require(ciphertext.size >= 16) { "v2 ciphertext too short for IV: ${ciphertext.size}" }
            val aesKey = sharedSecret.copyOfRange(32, 64)
            val iv = ciphertext.copyOfRange(0, 16)
            val ct = ciphertext.copyOfRange(16, ciphertext.size)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(ct)
        }

        override fun authenticate(sharedSecret: ByteArray, message: ByteArray): ByteArray {
            val hmacKey = sharedSecret.copyOfRange(0, 32)
            return hmacSha256(hmacKey, message) // v2: full 32-byte tag
        }
    }

    companion object {
        /**
         * Pick the strongest protocol that both we and the authenticator support.
         * Falls back to v1 when v2 is not advertised. Returns null if neither is
         * supported (in which case the authenticator does not require PIN at all).
         */
        fun pick(authenticatorProtocols: List<Int>): Ctap2PinProtocol? = when {
            2 in authenticatorProtocols -> V2
            1 in authenticatorProtocols -> V1
            authenticatorProtocols.isEmpty() -> V2 // some keys omit the field but support v2
            else -> null
        }
    }
}

// --- Crypto helpers (private to the module) ---

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

/**
 * HKDF-Expand per RFC 5869 with SHA-256. Concatenates `T(1) || T(2) || …`
 * where `T(i) = HMAC(PRK, T(i-1) || info || i)` until reaching `length`.
 */
internal fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length <= 255 * 32) { "HKDF length too large" }
    val out = ByteArray(length)
    var t = ByteArray(0)
    var pos = 0
    var i = 1
    while (pos < length) {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(t)
        mac.update(info)
        mac.update(byteArrayOf(i.toByte()))
        t = mac.doFinal()
        val take = minOf(t.size, length - pos)
        t.copyInto(out, pos, 0, take)
        pos += take
        i++
    }
    return out
}

/** Pad an unsigned big-endian integer byte array to exactly 32 bytes. */
internal fun padTo32(bytes: ByteArray): ByteArray = when {
    bytes.size == 32 -> bytes
    bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33) // strip BigInteger sign byte
    bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
    else -> throw IllegalArgumentException("EC coord too large: ${bytes.size}")
}

private fun ecP256Params(): ECParameterSpec {
    val params = AlgorithmParameters.getInstance("EC")
    params.init(ECGenParameterSpec("secp256r1"))
    return params.getParameterSpec(ECParameterSpec::class.java)
}
