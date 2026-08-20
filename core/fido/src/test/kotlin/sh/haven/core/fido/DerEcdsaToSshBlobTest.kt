package sh.haven.core.fido

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * sk-ecdsa signature encoding (#531): a CTAP2 ES256 assertion signature is DER,
 * but the SSH sk-ecdsa signature field wants mpint r || mpint s. These tests
 * pin the conversion, including the padding edge cases where DER and mpint
 * agree (both use minimal two's-complement) and the malformed-input rejects.
 */
class DerEcdsaToSshBlobTest {

    private fun derInteger(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return byteArrayOf(0x02, b.size.toByte()) + b
    }

    private fun derSequence(vararg parts: ByteArray): ByteArray {
        val body = parts.reduce { a, b -> a + b }
        require(body.size < 0x80)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun sshString(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, b.size.toByte()))
        out.write(b)
        return out.toByteArray()
    }

    private fun expectedBlob(r: BigInteger, s: BigInteger): ByteArray =
        sshString(r.toByteArray()) + sshString(s.toByteArray())

    @Test
    fun `small r and s round-trip`() {
        val r = BigInteger.valueOf(0x1234)
        val s = BigInteger.valueOf(0x56)
        val der = derSequence(derInteger(r), derInteger(s))
        assertArrayEquals(expectedBlob(r, s), derEcdsaToSshBlob(der))
    }

    @Test
    fun `high-bit values keep their sign byte`() {
        // 32-byte values with the top bit set: DER and mpint both need the
        // leading 0x00, so the 33-byte form must survive the conversion.
        val r = BigInteger(1, ByteArray(32) { 0xFF.toByte() })
        val s = BigInteger(1, byteArrayOf(0x80.toByte()) + ByteArray(31) { 0x11 })
        val der = derSequence(derInteger(r), derInteger(s))
        val blob = derEcdsaToSshBlob(der)
        assertArrayEquals(expectedBlob(r, s), blob)
        // First mpint is 33 bytes: 0x00 pad + 32 value bytes.
        assertArrayEquals(byteArrayOf(0, 0, 0, 33), blob.copyOfRange(0, 4))
    }

    @Test
    fun `short s from a lucky signature stays short`() {
        // ECDSA s can be numerically small; DER encodes it minimally and the
        // mpint must too, not zero-padded back to 32 bytes.
        val r = BigInteger(1, byteArrayOf(0x7F) + ByteArray(31) { 0x22 })
        val s = BigInteger.valueOf(0x0A)
        val der = derSequence(derInteger(r), derInteger(s))
        assertArrayEquals(expectedBlob(r, s), derEcdsaToSshBlob(der))
    }

    @Test
    fun `long-form sequence length parses`() {
        // A P-256 signature body maxes out at 70 bytes so short form always
        // suffices there, but larger curves (P-521) need the 0x81 length form;
        // accept it rather than bake in a P-256-only assumption.
        val r = BigInteger(1, ByteArray(32) { 0xF0.toByte() })
        val s = BigInteger(1, ByteArray(32) { 0x9A.toByte() })
        val body = derInteger(r) + derInteger(s)
        val der = byteArrayOf(0x30, 0x81.toByte(), body.size.toByte()) + body
        assertArrayEquals(expectedBlob(r, s), derEcdsaToSshBlob(der))
    }

    @Test
    fun `raw ed25519-style signature is rejected, not passed through`() {
        val raw = ByteArray(64) { 0x42 }
        assertThrows(IllegalArgumentException::class.java) { derEcdsaToSshBlob(raw) }
    }

    @Test
    fun `trailing bytes are rejected`() {
        val r = BigInteger.valueOf(5)
        val der = derSequence(derInteger(r), derInteger(r)) + byteArrayOf(0x00)
        assertThrows(IllegalArgumentException::class.java) { derEcdsaToSshBlob(der) }
    }
}
