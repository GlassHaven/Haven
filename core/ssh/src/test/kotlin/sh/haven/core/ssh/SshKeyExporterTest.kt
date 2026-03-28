package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom

class SshKeyExporterTest {

    // ---- Ed25519 raw seed (32 bytes) → OpenSSH PEM ----

    private fun generateEd25519Seed(): ByteArray {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        // Use getEncoded() for 32-byte raw seed. On some BC/JDK combinations,
        // .encoded may return ASN.1-wrapped form; fall back to extracting it.
        val encoded = priv.encoded
        return if (encoded.size == 32) encoded
        else {
            // ASN.1 wrapped — regenerate from the private key parameters directly
            val seed = ByteArray(32)
            priv.encode(seed, 0)
            seed
        }
    }

    @Test
    fun `toPem with Ed25519 raw seed produces OpenSSH private key`() {
        val seed = generateEd25519Seed()
        val pem = SshKeyExporter.toPem(seed, "ssh-ed25519")
        val pemStr = pem.decodeToString()
        assertTrue(
            "Expected OpenSSH PEM header, got: ${pemStr.take(40)}",
            pemStr.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
        )
        assertTrue(
            "Expected OpenSSH PEM footer",
            pemStr.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----")
        )
    }

    @Test
    fun `toPem with Ed25519 raw seed is loadable by JSch`() {
        val seed = generateEd25519Seed()
        val pem = SshKeyExporter.toPem(seed, "ssh-ed25519")
        val jsch = JSch()
        val kpair = KeyPair.load(jsch, pem, null)
        assertNotNull("JSch should parse the exported PEM", kpair)
        assertEquals("Key type should be ED25519", KeyPair.ED25519, kpair.keyType)
        kpair.dispose()
    }

    @Test
    fun `toPem with Ed25519 raw seed round trips through import`() {
        val seed = generateEd25519Seed()
        val pem = SshKeyExporter.toPem(seed, "ssh-ed25519")
        val imported = SshKeyImporter.import(pem)
        assertEquals("ssh-ed25519", imported.keyType)
        assertTrue(imported.fingerprintSha256.startsWith("SHA256:"))
        assertTrue(imported.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
    }

    // ---- RSA PKCS#8 DER → PEM ----

    private fun generateRsaDer(): ByteArray {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom()) // 2048 for speed in tests
        return kpg.generateKeyPair().private.encoded // PKCS#8 DER
    }

    @Test
    fun `toPem with RSA PKCS8 DER produces PEM`() {
        val der = generateRsaDer()
        val pem = SshKeyExporter.toPem(der, "ssh-rsa")
        val pemStr = pem.decodeToString()
        assertTrue(
            "Expected PEM header, got: ${pemStr.take(40)}",
            pemStr.startsWith("-----BEGIN ")
        )
    }

    @Test
    fun `toPem with RSA PKCS8 DER is loadable by JSch`() {
        val der = generateRsaDer()
        val pem = SshKeyExporter.toPem(der, "ssh-rsa")
        val jsch = JSch()
        val kpair = KeyPair.load(jsch, pem, null)
        assertNotNull("JSch should parse the exported PEM", kpair)
        assertEquals("Key type should be RSA", KeyPair.RSA, kpair.keyType)
        kpair.dispose()
    }

    @Test
    fun `toPem with RSA PKCS8 DER round trips through import`() {
        val der = generateRsaDer()
        val pem = SshKeyExporter.toPem(der, "ssh-rsa")
        val imported = SshKeyImporter.import(pem)
        assertEquals("ssh-rsa", imported.keyType)
        assertTrue(imported.fingerprintSha256.startsWith("SHA256:"))
    }

    // ---- ECDSA PKCS#8 DER → PEM ----

    private fun generateEcdsaDer(): ByteArray {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(384, SecureRandom())
        return kpg.generateKeyPair().private.encoded
    }

    @Test
    fun `toPem with ECDSA PKCS8 DER produces PEM`() {
        val der = generateEcdsaDer()
        val pem = SshKeyExporter.toPem(der, "ecdsa-sha2-nistp384")
        val pemStr = pem.decodeToString()
        assertTrue(
            "Expected PEM header",
            pemStr.startsWith("-----BEGIN ")
        )
    }

    @Test
    fun `toPem with ECDSA PKCS8 DER is loadable by JCA`() {
        val der = generateEcdsaDer()
        val pem = SshKeyExporter.toPem(der, "ecdsa-sha2-nistp384")
        val pemStr = pem.decodeToString()
        // Extract Base64 between PEM headers
        val b64 = pemStr.lines()
            .filter { !it.startsWith("-----") && it.isNotBlank() }
            .joinToString("")
        val decoded = java.util.Base64.getDecoder().decode(b64)
        // Verify JCA can load the PKCS#8 DER
        val kf = KeyFactory.getInstance("EC")
        val key = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(decoded))
        assertNotNull("JCA should parse the exported PKCS#8 PEM", key)
    }

    // ---- passthrough for existing PEM ----

    @Test
    fun `toPem with existing PEM returns bytes unchanged`() {
        val pemStr = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACAGDEAEY/2eTWPYbI21/d13lEIrFjHWa11tKBnYa6M6xgAAAJihYZxQoWGc
            UAAAAAtzc2gtZWQyNTUxOQAAACAGDEAEY/2eTWPYbI21/d13lEIrFjHWa11tKBnYa6M6xg
            AAAEASnxhlh0i/Gz1H26nWiojhTd888E1YQC1hgnYMnaZuuAYMQARj/Z5NY9hsjbX93XeU
            QisWMdZrXW0oGdhrozrGAAAAEGhhdmVuLXRlc3QtcGxhaW4BAgMEBQ==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent().toByteArray()
        val result = SshKeyExporter.toPem(pemStr, "ssh-ed25519")
        assertTrue(
            "Existing PEM should be returned unchanged",
            pemStr.contentEquals(result)
        )
    }

    // ---- Ed25519 export → import consistency ----

    @Test
    fun `exported Ed25519 key produces same fingerprint when reimported`() {
        val seed = generateEd25519Seed()
        val pem1 = SshKeyExporter.toPem(seed, "ssh-ed25519")
        val imported1 = SshKeyImporter.import(pem1)

        // Export again from imported bytes (should be passthrough since it's PEM now)
        val pem2 = SshKeyExporter.toPem(imported1.privateKeyBytes, "ssh-ed25519")
        val imported2 = SshKeyImporter.import(pem2)

        assertEquals(
            "Fingerprint should be stable across export/import cycles",
            imported1.fingerprintSha256, imported2.fingerprintSha256
        )
    }

    // ---- Full auth sequence: encrypted Ed25519 import → seed → toPem → JSch ----

    // Throwaway Ed25519 key encrypted with passphrase "test-ed25519-pass" for use as a test fixture only.
    // Same key as in SshKeyImporterTest.
    private val encryptedEd25519Pem = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABCS3fhvqX
        dOf0JZQYdTkkENAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIOnBvgn2SbqahNXp
        f4MYj7/fV1X5c3ZkeuRALlPF5DbbAAAAkFKTXlDYAaLvgux4vT8ZQA363ibu21QxUKVZEU
        O6p/yhMpBUTSE/bZhDBhzjKW1KacHT3j4uS4CFgS52HtJKHAo3gnFBHDMWmUPNN0QagmT1
        2Ohjr/FduMCU9VhS77D1jk3cxW14ryUDgKbtzM5QJ04D46zYGvgbxSgP2IV9JwAAVFshzM
        A2XAvQgB2Qe2XfKg==
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent().toByteArray()

    @Test
    fun `encrypted Ed25519 import stores original PEM and JSch loads with passphrase`() {
        // Step 1: Import encrypted key — stores original encrypted bytes
        val imported = SshKeyImporter.import(encryptedEd25519Pem, "test-ed25519-pass")
        assertEquals("ssh-ed25519", imported.keyType)
        assertTrue("isEncrypted should be true", imported.isEncrypted)
        assertTrue(
            "Stored bytes should be the original encrypted PEM",
            encryptedEd25519Pem.contentEquals(imported.privateKeyBytes),
        )

        // Step 2: Verify JSch can load the encrypted key with passphrase at connect time
        val jsch = JSch()
        val kpair = KeyPair.load(jsch, imported.privateKeyBytes, null)
        assertNotNull("JSch must be able to parse the encrypted PEM", kpair)
        assertTrue("Key must be encrypted", kpair.isEncrypted)
        assertTrue("Passphrase must decrypt the key", kpair.decrypt("test-ed25519-pass"))
        assertEquals("Key type must be ED25519", KeyPair.ED25519, kpair.keyType)
        kpair.dispose()
    }

    @Test
    fun `encrypted Ed25519 full sequence preserves fingerprint`() {
        // Import → store encrypted bytes → reimport with passphrase → same fingerprint
        val imported = SshKeyImporter.import(encryptedEd25519Pem, "test-ed25519-pass")
        val reimported = SshKeyImporter.import(imported.privateKeyBytes, "test-ed25519-pass")
        assertEquals(
            "Fingerprint must be stable through import → store → reimport",
            imported.fingerprintSha256, reimported.fingerprintSha256,
        )
    }

    @Test
    fun `unencrypted Ed25519 PEM passes through toPem unchanged and loads in JSch`() {
        // Unencrypted keys are stored as original file bytes (starts with '-')
        val unencryptedEd25519Pem = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACAGDEAEY/2eTWPYbI21/d13lEIrFjHWa11tKBnYa6M6xgAAAJihYZxQoWGc
            UAAAAAtzc2gtZWQyNTUxOQAAACAGDEAEY/2eTWPYbI21/d13lEIrFjHWa11tKBnYa6M6xg
            AAAEASnxhlh0i/Gz1H26nWiojhTd888E1YQC1hgnYMnaZuuAYMQARj/Z5NY9hsjbX93XeU
            QisWMdZrXW0oGdhrozrGAAAAEGhhdmVuLXRlc3QtcGxhaW4BAgMEBQ==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent().toByteArray()

        val imported = SshKeyImporter.import(unencryptedEd25519Pem)
        val authPem = SshKeyExporter.toPem(imported.privateKeyBytes, imported.keyType)

        // Should be passthrough (original PEM bytes)
        assertTrue(
            "Unencrypted PEM should pass through unchanged",
            unencryptedEd25519Pem.contentEquals(authPem),
        )

        // JSch must load it
        val jsch = JSch()
        val kpair = KeyPair.load(jsch, authPem, null)
        assertNotNull("JSch must parse unencrypted Ed25519 PEM", kpair)
        assertEquals(KeyPair.ED25519, kpair.keyType)
        kpair.dispose()
    }
}
