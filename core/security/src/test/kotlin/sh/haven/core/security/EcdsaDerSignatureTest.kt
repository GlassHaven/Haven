package sh.haven.core.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [derEcdsaToSshBlob] conversion: AndroidKeyStore (like CTAP2) hands us an
 * ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }`, while the SSH
 * `ecdsa-sha2-*` signature field is `mpint r || mpint s`. These tests pin
 * the conversion with hand-built vectors (including the minimal
 * two's-complement edge cases where DER and mpint encodings interact) and
 * a real JCA signature that must round-trip byte-identically — the exact
 * payload [HardwareSshKeyManager.initSignature] signatures will feed through
 * here once a BiometricPrompt authorizes them.
 */
class EcdsaDerSignatureTest {

    // ---- test-side oracles, independent of the implementation ----

    private fun derInteger(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return byteArrayOf(0x02, b.size.toByte()) + b
    }

    private fun derSequence(vararg parts: ByteArray): ByteArray {
        val body = parts.reduce { a, b -> a + b }
        require(body.size < 0x80) { "test oracle builds short-form DER only" }
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

    /** Reads a concatenation of SSH strings back into integers (round-trip oracle). */
    private fun readMpints(blob: ByteArray): List<BigInteger> {
        var pos = 0
        val values = mutableListOf<BigInteger>()
        while (pos < blob.size) {
            val len = ((blob[pos].toInt() and 0xFF) shl 24) or
                ((blob[pos + 1].toInt() and 0xFF) shl 16) or
                ((blob[pos + 2].toInt() and 0xFF) shl 8) or
                (blob[pos + 3].toInt() and 0xFF)
            values += BigInteger(blob.copyOfRange(pos + 4, pos + 4 + len))
            pos += 4 + len
        }
        return values
    }

    // ---- hand-built vectors ----

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

    // ---- malformed input is rejected, never passed through ----

    @Test
    fun `raw 64-byte signature is rejected`() {
        val raw = ByteArray(64) { 0x42 }
        assertThrows(IllegalArgumentException::class.java) { derEcdsaToSshBlob(raw) }
    }

    @Test
    fun `trailing bytes are rejected`() {
        val r = BigInteger.valueOf(5)
        val der = derSequence(derInteger(r), derInteger(r)) + byteArrayOf(0x00)
        assertThrows(IllegalArgumentException::class.java) { derEcdsaToSshBlob(der) }
    }

    @Test
    fun `non-positive r is rejected`() {
        val der = derSequence(derInteger(BigInteger.ZERO), derInteger(BigInteger.ONE))
        assertThrows(IllegalArgumentException::class.java) { derEcdsaToSshBlob(der) }
    }

    // ---- real signature round-trip ----

    @Test
    fun `JCA SHA256withECDSA signature round-trips through the blob`() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update("haven-hardware-key-test".toByteArray())
        }.sign()

        val blob = derEcdsaToSshBlob(der)

        // Structure: exactly two mpints, each a minimal positive P-256 value.
        val mpints = readMpints(blob)
        assertEquals(2, mpints.size)
        mpints.forEach { v ->
            assertTrue("r/s must be positive", v.signum() > 0)
            assertTrue("P-256 mpint is at most 33 bytes (0x00 pad + 32)", v.toByteArray().size <= 33)
        }
        // Round trip: rebuilding canonical DER from the mpints must reproduce
        // the provider's original DER byte-for-byte.
        val rebuilt = derSequence(derInteger(mpints[0]), derInteger(mpints[1]))
        assertArrayEquals(der, rebuilt)
    }
}
