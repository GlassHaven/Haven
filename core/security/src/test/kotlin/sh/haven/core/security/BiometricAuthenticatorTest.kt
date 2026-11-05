package sh.haven.core.security

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricAuthenticatorTest {

    private val context = mockk<Context> {
        every { getString(android.R.string.cancel) } returns "Cancel"
    }
    private val authenticator = BiometricAuthenticator(context)

    @Test
    fun `crypto promptInfo sets non-empty negativeButtonText so BiometricPrompt Builder does not throw`() {
        val info = authenticator.promptInfo(
            title = "Fill sudo password",
            subtitle = "workstation",
            authenticators = BiometricAuthenticator.CRYPTO_AUTHENTICATORS,
        )
        assertEquals("Fill sudo password", info.title.toString())
        assertEquals("workstation", info.subtitle.toString())
        assertEquals(BiometricAuthenticator.CRYPTO_AUTHENTICATORS, info.allowedAuthenticators)
        assertTrue(
            "Android BiometricPrompt.Builder.build() throws IllegalArgumentException when " +
                "DEVICE_CREDENTIAL is not allowed and negativeButtonText is empty",
            info.negativeButtonText.isNotEmpty(),
        )
        assertEquals("Cancel", info.negativeButtonText.toString())
    }

    @Test
    fun `device-credential promptInfo leaves negativeButtonText empty as required by BiometricPrompt`() {
        val info = authenticator.promptInfo(
            title = "Unlock Haven",
            subtitle = "Authenticate to continue",
            authenticators = BiometricAuthenticator.ALLOWED_AUTHENTICATORS,
        )
        assertEquals("Unlock Haven", info.title.toString())
        assertEquals("Authenticate to continue", info.subtitle.toString())
        assertEquals(BiometricAuthenticator.ALLOWED_AUTHENTICATORS, info.allowedAuthenticators)
        assertEquals("", info.negativeButtonText.toString())
    }
}
