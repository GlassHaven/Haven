package sh.haven.core.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #859 — sshlib drops ssh-mldsa44-ed25519 from the offer list when the
 * platform cannot perform the composite signature, so auth falls back to
 * password. The bundled BouncyCastle must be registered exactly when the
 * platform lacks the service, and left alone otherwise.
 */
class MldsaProviderAvailabilityTest {

    @Test
    fun `no registration when the platform serves every composite`() {
        var registered = false
        val attempted = MldsaProviderAvailability.applyWith(
            probe = { true },
            registerBouncyCastle = { registered = true; true },
        )
        assertFalse(attempted)
        assertFalse(registered)
    }

    @Test
    fun `registers when the mldsa44 composite is missing`() {
        var registered = false
        val attempted = MldsaProviderAvailability.applyWith(
            probe = { it != "MLDSA44-Ed25519-SHA512" },
            registerBouncyCastle = { registered = true; true },
        )
        assertTrue(attempted)
        assertTrue(registered)
    }

    @Test
    fun `registers when the mldsa87 composite is missing`() {
        var registered = false
        val attempted = MldsaProviderAvailability.applyWith(
            probe = { it != "MLDSA87-ECDSA-P384-SHA512" },
            registerBouncyCastle = { registered = true; true },
        )
        assertTrue(attempted)
        assertTrue(registered)
    }

    @Test
    fun `reports a failed registration`() {
        val registered = MldsaProviderAvailability.applyWith(
            probe = { false },
            registerBouncyCastle = { false },
        )
        assertFalse(registered)
    }

    @Test
    fun `probe list matches the sshlib service names`() {
        assertTrue(MldsaProviderAvailability.COMPOSITE_ALGORITHMS.contains("MLDSA44-Ed25519-SHA512"))
        assertTrue(MldsaProviderAvailability.COMPOSITE_ALGORITHMS.contains("MLDSA87-ECDSA-P384-SHA512"))
    }
}
