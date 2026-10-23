package sh.haven.core.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SshKeyGenerator.encodeEcdsaPublicKey] curve generalization (P-256 +
 * P-384): golden wire blobs produced by OpenSSH `ssh-keygen` pin the
 * encoding byte-for-byte, so a hardware key minted in AndroidKeyStore is
 * byte-identical to what a server expects from OpenSSH itself.
 *
 * The encoder is a pure wire formatter over `ECPublicKey.w` — it never
 * validates the point against the curve — so tests feed a point-only key
 * stub rather than going through a JCA KeyFactory.
 */
class SshKeyGeneratorEcdsaEncodingTest {

    /** Bare-bounds [ECPublicKey]: the encoder reads only [getW]. */
    private class PointOnlyPublicKey(private val point: ECPoint) : ECPublicKey {
        override fun getW(): ECPoint = point
        override fun getAlgorithm(): String = "EC"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray? = null
        override fun getParams(): ECParameterSpec? = null
    }

    // ---- oracles ----

    private fun hex(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun bigInt(hex: String): BigInteger = BigInteger(1, hex(hex))

    /** Independent length-prefixed string concatenation. */
    private fun sshWire(vararg strings: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in strings) {
            val len = s.size
            out.write(byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()))
            out.write(s)
        }
        return out.toByteArray()
    }

    /** Splits an OpenSSH public key line's blob back into its wire strings. */
    private fun parseWireStrings(blob: ByteArray): List<ByteArray> {
        var pos = 0
        val strings = mutableListOf<ByteArray>()
        while (pos < blob.size) {
            val len = ((blob[pos].toInt() and 0xFF) shl 24) or
                ((blob[pos + 1].toInt() and 0xFF) shl 16) or
                ((blob[pos + 2].toInt() and 0xFF) shl 8) or
                (blob[pos + 3].toInt() and 0xFF)
            strings += blob.copyOfRange(pos + 4, pos + 4 + len)
            pos += 4 + len
        }
        return strings
    }

    // ssh-keygen -t ecdsa -b 256 (2026-10-23); blob is the base64 body of the .pub line.
    private val goldenP256X = "a5caedb2141803742b0d73cd7a465fd2ea33fd8e0acab191286386e0212d310d"
    private val goldenP256Y = "64412a549cd0d4ff6166a79b7c20a4142e598afa4d08ce408204fe7b2d67becb"
    private val goldenP256Fingerprint = "SHA256:gfSf5GuB1DyJNcV8n38u6x1BgNaEARlHfVdSPDyfO50"

    // ssh-keygen -t ecdsa -b 384 (2026-10-23).
    private val goldenP384X =
        "d4b9fa74d22521a7a0a9372a91ef2144037cc5203165c2766201984038f170ac1e2ec3771a062e5ef71927283ebfa9f3"
    private val goldenP384Y =
        "51eb94f144bccec4ef3b4d0bce5684b36920369510a209443032217782e08a68adba63e57c60f10349ab2e91ef7a2d9b"
    private val goldenP384Fingerprint = "SHA256:6L+eZc1S3nLLFh9Oaz18kPc9ym5UXPtqKqloqZfo7U0"

    private fun goldenP256Blob(): ByteArray = sshWire(
        "ecdsa-sha2-nistp256".toByteArray(),
        "nistp256".toByteArray(),
        byteArrayOf(0x04) + hex(goldenP256X) + hex(goldenP256Y),
    )

    private fun goldenP384Blob(): ByteArray = sshWire(
        "ecdsa-sha2-nistp384".toByteArray(),
        "nistp384".toByteArray(),
        byteArrayOf(0x04) + hex(goldenP384X) + hex(goldenP384Y),
    )

    // ---- golden vectors ----

    @Test
    fun `P-256 public key encodes to the ssh-keygen golden blob`() {
        val key = PointOnlyPublicKey(ECPoint(bigInt(goldenP256X), bigInt(goldenP256Y)))
        val blob = SshKeyGenerator.encodeEcdsaPublicKey(key, SshKeyGenerator.EcdsaCurve.NIST_P256)
        assertArrayEquals(goldenP256Blob(), blob)
    }

    @Test
    fun `P-256 golden blob fingerprints like ssh-keygen reports`() {
        assertEquals(goldenP256Fingerprint, SshKeyGenerator.fingerprintSha256(goldenP256Blob()))
    }

    @Test
    fun `P-384 public key encodes to the ssh-keygen golden blob`() {
        val key = PointOnlyPublicKey(ECPoint(bigInt(goldenP384X), bigInt(goldenP384Y)))
        val blob = SshKeyGenerator.encodeEcdsaPublicKey(key, SshKeyGenerator.EcdsaCurve.NIST_P384)
        assertArrayEquals(goldenP384Blob(), blob)
    }

    @Test
    fun `P-384 golden blob fingerprints like ssh-keygen reports`() {
        assertEquals(goldenP384Fingerprint, SshKeyGenerator.fingerprintSha256(goldenP384Blob()))
    }

    // ---- coordinate width edge cases ----

    @Test
    fun `short coordinate is left-padded to the field width`() {
        val x = bigInt("0102")
        val y = bigInt("03")
        val key = PointOnlyPublicKey(ECPoint(x, y))
        val blob = SshKeyGenerator.encodeEcdsaPublicKey(key, SshKeyGenerator.EcdsaCurve.NIST_P256)
        val expectedPoint = byteArrayOf(0x04) +
            ByteArray(30) { 0x00 } + hex("0102") +
            ByteArray(31) { 0x00 } + hex("03")
        assertArrayEquals(sshWire("ecdsa-sha2-nistp256".toByteArray(), "nistp256".toByteArray(), expectedPoint), blob)
    }

    @Test
    fun `coordinate with top bit set loses the BigInteger sign byte`() {
        // BigInteger.toByteArray() prepends 0x00 when the top bit is set;
        // the wire point wants exactly coordinateBytes per half.
        val xBytes = hex("89") + ByteArray(31) { 0xAB.toByte() }
        val yBytes = ByteArray(32) { 0x0C }
        val key = PointOnlyPublicKey(ECPoint(BigInteger(1, xBytes), BigInteger(1, yBytes)))
        val blob = SshKeyGenerator.encodeEcdsaPublicKey(key, SshKeyGenerator.EcdsaCurve.NIST_P256)
        assertEquals(4 + 19 + 4 + 8 + 4 + 65, blob.size)
        val point = blob.copyOfRange(blob.size - 65, blob.size)
        assertEquals(0x04, point[0].toInt() and 0xFF)
        assertArrayEquals(xBytes, point.copyOfRange(1, 33))
        assertArrayEquals(yBytes, point.copyOfRange(33, 65))
    }

    // ---- public key line formatting ----

    @Test
    fun `public key line is type, base64 blob and comment`() {
        val blob = goldenP256Blob()
        val line = SshKeyGenerator.openSshPublicKeyLine("ecdsa-sha2-nistp256", blob, "golden-p256")
        assertEquals(
            "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(blob)} golden-p256",
            line,
        )
    }

    @Test
    fun `public key line omits the comment slot when comment is empty`() {
        val line = SshKeyGenerator.openSshPublicKeyLine("ecdsa-sha2-nistp256", goldenP256Blob(), "")
        assertFalse(line.endsWith(" "))
        assertEquals("ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(goldenP256Blob())}", line)
    }

    // ---- the software ECDSA generator path stays on the generalized encoder ----

    @Test
    fun `generated ECDSA P-384 key parses as nistp384 with 48-byte halves`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ECDSA_384)
        val fields = key.publicKeyOpenSsh.split(" ")
        assertEquals("ecdsa-sha2-nistp384", fields[0])
        val strings = parseWireStrings(Base64.getDecoder().decode(fields[1]))
        assertEquals(listOf("ecdsa-sha2-nistp384", "nistp384"), strings.take(2).map { it.toString(Charsets.UTF_8) })
        val point = strings[2]
        assertEquals("point is 0x04 || X(48) || Y(48)", 97, point.size)
        assertEquals(0x04, point[0].toInt() and 0xFF)
        assertTrue("fingerprint covers the wire blob", key.fingerprintSha256.startsWith("SHA256:"))
    }
}
