package sh.haven.core.security

import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robolectric cannot host a real AndroidKeyStore, so the JVM-testable surface
 * of [HardwareSshKeyManager] is its *policy*: [HardwareKeyGenParams] (what
 * every KeyGenParameterSpec field will be set to) and the StrongBox version
 * gate in [HardwareSshKeyManager.generate] (API 28+, inlined there so lint
 * can verify the SDK_INT check). The Keystore-touching shell itself stays a
 * thin, review-audited wrapper.
 */
class HardwareSshKeyManagerTest {

    @Test
    fun `params request EC P-256 sign and verify with SHA-256 digest only`() {
        val p = HardwareKeyGenParams.forHardwareSshKey("hw-test", strongBoxBacked = true)
        assertEquals("EC", p.algorithm)
        assertEquals("secp256r1", p.ecCurveName)
        assertEquals(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY, p.purposes)
        assertEquals("PURPOSE_SIGN(4) | PURPOSE_VERIFY(8), pinned literally", 12, p.purposes)
        assertEquals(KeyProperties.DIGEST_SHA256, p.digest)
        assertEquals("digest pinned literally (ecdsa-sha2-nistp256 mandates SHA-256)", "SHA-256", p.digest)
    }

    @Test
    fun `params demand per-operation user authentication`() {
        val p = HardwareKeyGenParams.forHardwareSshKey("hw-test", strongBoxBacked = false)
        assertTrue(p.userAuthenticationRequired)
        // -1 = every use needs its own CryptoObject authorization; a 30 s
        // validity window would only pretend to batch them (see design doc).
        assertEquals(-1, p.userAuthenticationValidityDurationSeconds)
    }

    @Test
    fun `params invalidate the key on new biometric enrollment`() {
        val p = HardwareKeyGenParams.forHardwareSshKey("hw-test", strongBoxBacked = false)
        assertTrue(p.invalidatedByBiometricEnrollment)
    }

    @Test
    fun `strongbox flag is the only difference between the two variants`() {
        val strongBox = HardwareKeyGenParams.forHardwareSshKey("hw-test", strongBoxBacked = true)
        val tee = HardwareKeyGenParams.forHardwareSshKey("hw-test", strongBoxBacked = false)
        assertTrue(strongBox.strongBoxBacked)
        assertFalse(tee.strongBoxBacked)
        assertEquals("everything except the backend flag is identical", strongBox, tee.copy(strongBoxBacked = true))
        assertEquals("hw-test", strongBox.alias)
    }
}
