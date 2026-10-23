package sh.haven.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.spec.ECGenParameterSpec

/**
 * Keystore generation policy for hardware SSH keys (pure data) plus the
 * mechanical mapping onto the framework [KeyGenParameterSpec].
 *
 * Boundary: carries every "what we ask AndroidKeyStore to do" decision and
 * performs no Keystore I/O itself. It is a standalone data class because
 * Robolectric/JVM tests cannot host a real AndroidKeyStore — they can only
 * assert on the policy; [toKeyGenParameterSpec] is a thin per-field setter
 * wrapper kept honest by review, not by unit tests.
 */
internal data class HardwareKeyGenParams(
    val alias: String,
    val strongBoxBacked: Boolean,
    val algorithm: String = KeyProperties.KEY_ALGORITHM_EC,
    val ecCurveName: String = CURVE_SECP256R1,
    val purposes: Int = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
    val digest: String = KeyProperties.DIGEST_SHA256,
    val userAuthenticationRequired: Boolean = true,
    val userAuthenticationValidityDurationSeconds: Int = USER_AUTH_VALIDITY_PER_OPERATION,
    val invalidatedByBiometricEnrollment: Boolean = true,
) {
    fun toKeyGenParameterSpec(): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec(ecCurveName))
            .setDigests(digest)
            .setUserAuthenticationRequired(userAuthenticationRequired)
            .setUserAuthenticationValidityDurationSeconds(userAuthenticationValidityDurationSeconds)
            .setInvalidatedByBiometricEnrollment(invalidatedByBiometricEnrollment)
            .apply {
                // StrongBox exists only from API 28. The gate mirrors
                // HardwareSshKeyManager's generate() and is expanded inline
                // (rather than trusting the caller to check) so lint can
                // verify it and no future caller skips the version check.
                if (strongBoxBacked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

    companion object {
        /** The only curve the KeyMint/Keymaster hardware backends reliably support (StrongBox has no curve25519). */
        const val CURVE_SECP256R1 = "secp256r1"

        /**
         * -1 = every use of the key demands its own user authentication (the
         * CryptoObject per-op path). A positive validity window is
         * deliberately NOT used: BiometricPrompt authentication carrying a
         * CryptoObject is never exempted by the window (Android enforces one
         * BIOMETRIC_STRONG ceremony per CryptoObject), so a window would
         * only create the illusion of re-auth-free reuse; per-op is the
         * strongest confirmed semantics.
         */
        const val USER_AUTH_VALIDITY_PER_OPERATION = -1

        /** Default policy for a hardware SSH key: ECDSA P-256, sign+verify, per-op authentication. */
        fun forHardwareSshKey(alias: String, strongBoxBacked: Boolean): HardwareKeyGenParams =
            HardwareKeyGenParams(alias = alias, strongBoxBacked = strongBoxBacked)
    }
}
