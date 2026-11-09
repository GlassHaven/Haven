package sh.haven.core.vnc.protocol

import java.io.DataInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Apple Remote Desktop authentication — RFB security type 30, offered by the
 * macOS built-in Screen Sharing server.
 *
 * Unlike classic VncAuth (type 2), which checks a separate VNC password that
 * belongs to no account, type 30 logs into a macOS *user account*. That is
 * what makes Screen Sharing hand over that user's console session: a
 * password-only VncAuth client is not tied to any user, and on current macOS
 * the server parks it on a separate, not-logged-in login-window session whose
 * picture never refreshes.
 *
 * Exchange, after the client selects type 30:
 *
 *  1. server → client: generator (2 bytes), key length `n` (2 bytes),
 *     prime modulus (`n` bytes), server DH public key (`n` bytes); all
 *     unsigned big-endian.
 *  2. The client picks a DH private key, derives the shared secret
 *     (`n` bytes, left-padded) and hashes it with MD5 into an AES-128 key.
 *  3. client → server: a 128-byte credentials block encrypted with
 *     AES-128-ECB (no padding) — username NUL-terminated at offset 0,
 *     password NUL-terminated at offset 64, every other byte random —
 *     followed by the client DH public key (`n` bytes).
 *  4. The usual SecurityResult follows (read by [Handshaker]).
 */
internal object ArdAuth {

    /** Each of username and password gets a 64-byte field, NUL included. */
    private const val FIELD = 64
    private const val MAX_CREDENTIAL_BYTES = FIELD - 1

    /** Upper bound on the advertised DH key length (macOS uses 128 bytes). */
    private const val MAX_KEY_LENGTH = 1024

    /** The server's Diffie-Hellman parameters, as read off the wire. */
    class Params(
        val generator: BigInteger,
        val prime: BigInteger,
        val keyLength: Int,
        val serverPublicKey: BigInteger,
    )

    fun readParams(input: DataInputStream): Params {
        val generator = BigInteger(1, ByteArray(2).also(input::readFully))
        val keyLength = input.readUnsignedShort()
        if (keyLength == 0 || keyLength > MAX_KEY_LENGTH) {
            throw HandshakingFailedException("Apple authentication: invalid key length $keyLength")
        }
        val prime = BigInteger(1, ByteArray(keyLength).also(input::readFully))
        val serverPublicKey = BigInteger(1, ByteArray(keyLength).also(input::readFully))
        return Params(generator, prime, keyLength, serverPublicKey)
    }

    /**
     * The client's reply: the 128-byte encrypted credentials block followed by
     * the client public key, ready to be written to the socket as-is.
     */
    fun response(
        params: Params,
        username: String,
        password: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val p = params.prime
        val y = params.serverPublicKey
        // Reject degenerate parameters: a public key of 0, 1 or p-1 forces a
        // shared secret an eavesdropper can predict.
        if (p.bitLength() < 2 || params.generator.signum() <= 0 ||
            y <= BigInteger.ONE || y >= p - BigInteger.ONE
        ) {
            throw HandshakingFailedException("Apple authentication: invalid Diffie-Hellman parameters")
        }

        val userBytes = encodeField(username, "Username")
        val passBytes = encodeField(password, "Password")

        // Private key uniformly in [2, p-2].
        val range = p - BigInteger.valueOf(3)
        var x: BigInteger
        do {
            x = BigInteger(p.bitLength(), random)
        } while (x >= range)
        x += BigInteger.valueOf(2) // BigInteger.TWO needs API 33; minSdk is 26

        val clientPublicKey = params.generator.modPow(x, p)
        val shared = toFixedLength(y.modPow(x, p), params.keyLength)
        val aesKey = MessageDigest.getInstance("MD5").digest(shared)
        shared.fill(0)

        val plain = ByteArray(2 * FIELD).also(random::nextBytes)
        userBytes.copyInto(plain, 0)
        plain[userBytes.size] = 0
        passBytes.copyInto(plain, FIELD)
        plain[FIELD + passBytes.size] = 0
        userBytes.fill(0)
        passBytes.fill(0)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        aesKey.fill(0)
        val encrypted = cipher.doFinal(plain)
        plain.fill(0)

        return encrypted + toFixedLength(clientPublicKey, params.keyLength)
    }

    /** UTF-8 bytes of [value]; refuses rather than silently truncates. */
    private fun encodeField(value: String, what: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CREDENTIAL_BYTES) {
            bytes.fill(0)
            throw AuthenticationFailedException(
                "$what is too long for Apple authentication (max $MAX_CREDENTIAL_BYTES bytes)",
            )
        }
        return bytes
    }

    /** Unsigned big-endian encoding of [value], left-padded to [length] bytes. */
    internal fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray() // may carry a leading 0x00 sign byte
        val start = if (raw.size > 1 && raw[0] == 0.toByte()) 1 else 0
        val significant = raw.size - start
        require(significant <= length) { "value does not fit in $length bytes" }
        val out = ByteArray(length)
        System.arraycopy(raw, start, out, length - significant, significant)
        return out
    }
}
