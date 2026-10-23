package sh.haven.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class BiometricAuthenticator @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    companion object {
        /** Accepts biometric (strong or weak) or device credential (PIN/password/pattern). */
        private const val ALLOWED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        /**
         * CryptoObject prompts accept BIOMETRIC_STRONG only — weak
         * biometrics and device credential cannot authorize a per-use
         * Keystore operation, so androidx rejects the combination.
         */
        private const val CRYPTO_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG
    }

    enum class Availability { AVAILABLE, NO_HARDWARE, NOT_ENROLLED }

    sealed class AuthResult {
        data object Success : AuthResult()
        data class Failure(val message: String) : AuthResult()
        data object Cancelled : AuthResult()
    }

    fun checkAvailability(context: Context): Availability {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(ALLOWED_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            else -> Availability.NO_HARDWARE
        }
    }

    /**
     * Whether a CryptoObject ceremony — the per-op authorization a
     * hardware-resident Keystore key demands at every signature — can run
     * at all on this device: a BIOMETRIC_STRONG (Class 3) biometric is
     * enrolled and usable. Weak biometrics and device credential cannot
     * authorize a Keystore [Signature], so minting a chip key without this
     * only defers the failure to the first connect. Callers gate key
     * *generation* on this, with a message that names the remedy.
     */
    fun canAuthorizeWithStrongBiometrics(): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(CRYPTO_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Haven",
        subtitle: String = "Authenticate to continue",
    ): AuthResult = showPrompt(activity, promptInfo(title, subtitle, ALLOWED_AUTHENTICATORS), crypto = null)

    /**
     * Crypto variant for hardware-resident Keystore keys: the prompt
     * authorizes [signature] via `BiometricPrompt.CryptoObject`. On
     * [AuthResult.Success] the caller's [signature] instance is
     * authorized in place — Keystore binds the auth to the object, so
     * proceed to `update`/`sign` on it directly; nothing is handed back.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        signature: Signature,
    ): AuthResult = showPrompt(
        activity,
        promptInfo(title, subtitle, CRYPTO_AUTHENTICATORS),
        crypto = BiometricPrompt.CryptoObject(signature),
    )

    private fun promptInfo(title: String, subtitle: String, authenticators: Int) =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()

    private suspend fun showPrompt(
        activity: FragmentActivity,
        info: BiometricPrompt.PromptInfo,
        crypto: BiometricPrompt.CryptoObject?,
    ): AuthResult = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(AuthResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                val isCancellation = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (isCancellation) {
                    cont.resume(AuthResult.Cancelled)
                } else {
                    cont.resume(AuthResult.Failure(errString.toString()))
                }
            }

            // onAuthenticationFailed: no-op — prompt handles retry internally
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)

        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
