package sh.haven.core.vnc.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Apple Remote Desktop authentication (RFB security type 30). Each test plays
 * the server's side of the Diffie-Hellman exchange and decrypts what the
 * client sent, so the wire format is checked end to end rather than against
 * the implementation's own helpers.
 */
class ArdAuthTest {

    /** Simulated macOS Screen Sharing server: DH parameters plus its private key. */
    internal class FakeArdServer(keyLength: Int = 128, seed: Long = 42) {
        private val rnd = Random(seed)
        val keyLength = keyLength
        val prime: BigInteger = BigInteger.probablePrime(keyLength * 8, rnd)
        val generator: BigInteger = BigInteger.valueOf(2)
        private val privateKey = BigInteger(keyLength * 8 - 2, rnd).add(BigInteger.TWO)
        val publicKey: BigInteger = generator.modPow(privateKey, prime)

        fun params() = ArdAuth.Params(generator, prime, keyLength, publicKey)

        /** The bytes the server sends after the client selects type 30. */
        fun wireParams(): ByteArray = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).apply {
                write(ArdAuth.toFixedLength(generator, 2))
                writeShort(keyLength)
                write(ArdAuth.toFixedLength(prime, keyLength))
                write(ArdAuth.toFixedLength(publicKey, keyLength))
            }
        }.toByteArray()

        /** Decrypt the client's reply into the 128-byte credentials block. */
        fun decrypt(reply: ByteArray): ByteArray {
            assertEquals(128 + keyLength, reply.size)
            val clientPublic = BigInteger(1, reply.copyOfRange(128, reply.size))
            val shared = ArdAuth.toFixedLength(clientPublic.modPow(privateKey, prime), keyLength)
            val key = MessageDigest.getInstance("MD5").digest(shared)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            return cipher.doFinal(reply.copyOfRange(0, 128))
        }
    }

    /** Field at [offset]: the bytes up to the NUL terminator. */
    private fun field(block: ByteArray, offset: Int): ByteArray {
        val end = (offset until offset + 64).first { block[it] == 0.toByte() }
        return block.copyOfRange(offset, end)
    }

    private val server = FakeArdServer()

    @Test
    fun `server decrypts the username and password the client sent`() {
        val block = server.decrypt(ArdAuth.response(server.params(), "alice", "correct horse"))

        assertEquals("alice", String(field(block, 0), Charsets.UTF_8))
        assertEquals("correct horse", String(field(block, 64), Charsets.UTF_8))
    }

    @Test
    fun `non-ASCII credentials travel as UTF-8`() {
        val block = server.decrypt(ArdAuth.response(server.params(), "张三", "pässwörd"))

        assertEquals("张三", String(field(block, 0), Charsets.UTF_8))
        assertEquals("pässwörd", String(field(block, 64), Charsets.UTF_8))
    }

    @Test
    fun `fresh randomness each time — the same credentials never repeat on the wire`() {
        val a = ArdAuth.response(server.params(), "alice", "pw", SecureRandom())
        val b = ArdAuth.response(server.params(), "alice", "pw", SecureRandom())

        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `63-byte credentials fit and 64-byte ones are refused, not truncated`() {
        val max = "x".repeat(63)
        val block = server.decrypt(ArdAuth.response(server.params(), max, max))
        assertEquals(max, String(field(block, 0), Charsets.UTF_8))
        assertEquals(max, String(field(block, 64), Charsets.UTF_8))

        assertThrows(AuthenticationFailedException::class.java) {
            ArdAuth.response(server.params(), "alice", "x".repeat(64))
        }
        assertThrows(AuthenticationFailedException::class.java) {
            ArdAuth.response(server.params(), "x".repeat(64), "pw")
        }
    }

    @Test
    fun `a degenerate server public key is rejected`() {
        val p = server.prime
        for (bad in listOf(BigInteger.ZERO, BigInteger.ONE, p - BigInteger.ONE, p)) {
            val params = ArdAuth.Params(server.generator, p, server.keyLength, bad)
            assertThrows(HandshakingFailedException::class.java) {
                ArdAuth.response(params, "alice", "pw")
            }
        }
    }

    @Test
    fun `readParams parses the server's wire format`() {
        val params = ArdAuth.readParams(DataInputStream(ByteArrayInputStream(server.wireParams())))

        assertEquals(server.generator, params.generator)
        assertEquals(server.keyLength, params.keyLength)
        assertEquals(server.prime, params.prime)
        assertEquals(server.publicKey, params.serverPublicKey)
    }

    @Test
    fun `readParams refuses a zero key length`() {
        val wire = byteArrayOf(0, 2, 0, 0)
        assertThrows(HandshakingFailedException::class.java) {
            ArdAuth.readParams(DataInputStream(ByteArrayInputStream(wire)))
        }
    }

    @Test
    fun `toFixedLength strips the sign byte and left-pads`() {
        // 0x80 has its top bit set, so BigInteger.toByteArray() adds a 0x00 sign byte.
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte()), ArdAuth.toFixedLength(BigInteger.valueOf(0x80), 3))
        assertArrayEquals(byteArrayOf(0, 0x01, 0x02), ArdAuth.toFixedLength(BigInteger.valueOf(0x0102), 3))
        assertArrayEquals(byteArrayOf(0, 0), ArdAuth.toFixedLength(BigInteger.ZERO, 2))
        assertThrows(IllegalArgumentException::class.java) {
            ArdAuth.toFixedLength(BigInteger.valueOf(0x010203), 2)
        }
    }
}
