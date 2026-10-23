package sh.haven.core.security

import android.os.Build
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for hardware-resident SSH keys' AndroidKeyStore
 * interaction.
 *
 * Boundary: generation / sign initialization / existence / deletion of
 * ECDSA P-256 keys in the Keystore. It does NOT do: biometric UI and
 * queueing (BiometricGate), DB persistence (core/data), or SSH protocol
 * wiring (core/ssh's KeystoreSshIdentity).
 *
 * Key design decisions:
 * - The private key never leaves the hardware: KeyPairGenerator runs with
 *   provider "AndroidKeyStore", so the process only ever sees the public
 *   key and an opaque private-key handle — an export path simply does not
 *   exist at any higher layer.
 * - Per-operation authentication: the generation policy fixes
 *   `setUserAuthenticationValidityDurationSeconds(-1)` (see
 *   [HardwareKeyGenParams.USER_AUTH_VALIDITY_PER_OPERATION]), so a
 *   Signature returned by [initSignature] must first go through
 *   BiometricPrompt + CryptoObject before it can sign — a hard contract
 *   for the biometric-gate and identity consumers.
 * - StrongBox fallback: `setIsStrongBoxBacked(true)` is attempted only
 *   when the system is >= API 28 and preferStrongBox is set (both
 *   StrongBox APIs exist only from 28; the version check is expanded
 *   inline so lint can verify it). A device without StrongBox
 *   ([StrongBoxUnavailableException]) silently retries once on the TEE,
 *   and [HardwareKeyInfo.strongBoxBacked] reports the actual backend.
 */
@Singleton
class HardwareSshKeyManager @Inject constructor() : HardwareKeySource {

    /**
     * Generates an ECDSA P-256 SSH key inside the AndroidKeyStore. An
     * existing alias is overwritten (uniqueness allocation is the caller
     * SshKeyRepository's job — it probes for collisions with [contains]
     * before minting).
     *
     * @param preferStrongBox try the StrongBox secure element first.
     *   Falls back to the TEE automatically when it is unavailable
     *   ([StrongBoxUnavailableException]) or the system is < API 28,
     *   retrying exactly once; the version check stays inline (not in a
     *   helper) so lint can verify it.
     */
    override fun generate(alias: String, preferStrongBox: Boolean): HardwareKeyInfo {
        require(alias.isNotBlank()) { "hardware key alias must not be blank" }
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateInAndroidKeyStore(
                    HardwareKeyGenParams.forHardwareSshKey(alias, strongBoxBacked = true),
                )
            } catch (e: StrongBoxUnavailableException) {
                // This device's Secure Element is unavailable (absent/busy):
                // silently fall back to the TEE and retry once.
            }
        }
        return generateInAndroidKeyStore(
            HardwareKeyGenParams.forHardwareSshKey(alias, strongBoxBacked = false),
        )
    }

    /**
     * Returns a SHA256withECDSA Signature bound to the alias's private key,
     * awaiting its per-operation biometric. The caller
     * (core/ssh's KeystoreSshIdentity) wraps it in a BiometricPrompt
     * CryptoObject and calls update+sign once authenticated; signing
     * without authentication throws UserNotAuthenticatedException.
     *
     * @throws android.security.keystore.KeyPermanentlyInvalidatedException
     *   thrown by the Keystore when newly enrolled biometrics invalidated
     *   the key; rethrown as-is (fail-closed — the layer above prompts the
     *   user to delete and re-create the key).
     * @throws java.security.UnrecoverableKeyException same failure family,
     *   rethrown as-is.
     * @throws IllegalArgumentException no hardware key exists under the alias.
     */
    override fun initSignature(alias: String): Signature {
        val privateKey = androidKeyStore().getKey(alias, null) as? PrivateKey
            ?: throw IllegalArgumentException("No hardware SSH key in AndroidKeyStore for alias '$alias'")
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply { initSign(privateKey) }
    }

    /** Deletes the alias from the Keystore (idempotent: a missing alias succeeds silently). */
    override fun delete(alias: String) {
        androidKeyStore().deleteEntry(alias)
    }

    override fun contains(alias: String): Boolean = androidKeyStore().containsAlias(alias)

    private fun generateInAndroidKeyStore(params: HardwareKeyGenParams): HardwareKeyInfo {
        val generator = KeyPairGenerator.getInstance(params.algorithm, ANDROID_KEYSTORE)
        generator.initialize(params.toKeyGenParameterSpec())
        val keyPair = generator.generateKeyPair()
        val curve = SshKeyGenerator.EcdsaCurve.NIST_P256
        val blob = SshKeyGenerator.encodeEcdsaPublicKey(keyPair.public as ECPublicKey, curve)
        return HardwareKeyInfo(
            alias = params.alias,
            publicKeyOpenSsh = SshKeyGenerator.openSshPublicKeyLine(curve.sshKeyType, blob, params.alias),
            fingerprintSha256 = SshKeyGenerator.fingerprintSha256(blob),
            strongBoxBacked = params.strongBoxBacked,
        )
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
