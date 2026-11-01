package sh.haven.feature.terminal

import android.util.Log
import sh.haven.core.data.keystore.BiometricGate
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.security.HardwareKeySource
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SudoPasswordUnlocker"

sealed class SudoUnlockResult {
    data class Unlocked(val password: String, val hardwareBacked: Boolean) : SudoUnlockResult()
    data class NoPasswordStored(val profileLabel: String) : SudoUnlockResult()
    data class Declined(val reason: String) : SudoUnlockResult()
}

/**
 * Resolves a remote session's stored password (from [ConnectionRepository] or
 * its attached [SshIdentityRepository] identity) and gates every `sudo` fill
 * behind a fresh per-operation biometric ceremony.
 *
 * When Class 3 (`BIOMETRIC_STRONG`) biometrics are available on the device,
 * the prompt is bound via `BiometricPrompt.CryptoObject` to a hardware-backed
 * Android Keystore key ([SUDO_HARDWARE_KEY_ALIAS] in StrongBox/TEE with
 * per-operation authentication validity `-1`) and verified by signing a
 * challenge after the prompt succeeds. On devices without `BIOMETRIC_STRONG`,
 * it falls back to a fresh (`requireFreshAuth = true`) [BiometricGate]
 * request so device credential / biometric still gates every fill.
 */
@Singleton
class SudoPasswordUnlocker(
    private val connectionRepository: ConnectionRepository,
    private val sshIdentityRepository: SshIdentityRepository,
    private val biometricGate: BiometricGate,
    private val hardwareKeySource: HardwareKeySource,
    private val strongBiometricsAvailable: () -> Boolean,
) {
    @Inject
    constructor(
        connectionRepository: ConnectionRepository,
        sshIdentityRepository: SshIdentityRepository,
        biometricGate: BiometricGate,
        hardwareKeySource: HardwareKeySource,
        biometricAuthenticator: BiometricAuthenticator,
    ) : this(
        connectionRepository = connectionRepository,
        sshIdentityRepository = sshIdentityRepository,
        biometricGate = biometricGate,
        hardwareKeySource = hardwareKeySource,
        strongBiometricsAvailable = { biometricAuthenticator.canAuthorizeWithStrongBiometrics() },
    )

    suspend fun unlock(profileId: String): SudoUnlockResult {
        val rawProfile = connectionRepository.getById(profileId)
            ?: return SudoUnlockResult.NoPasswordStored("")
        val resolved = sshIdentityRepository.applyTo(rawProfile)
        val password = resolved.sshPassword?.takeIf { it.isNotEmpty() }
            ?: return SudoUnlockResult.NoPasswordStored(rawProfile.label)

        val signature = if (strongBiometricsAvailable()) {
            prepareHardwareSignature()
        } else {
            null
        }

        val decision = biometricGate.request(
            label = "Fill sudo password",
            detail = rawProfile.label,
            signature = signature,
            requireFreshAuth = true,
        )

        return when (decision) {
            BiometricGate.Decision.ALLOW -> {
                if (signature != null) {
                    try {
                        signature.update(CHALLENGE_BYTES)
                        signature.sign()
                    } catch (e: Exception) {
                        Log.w(TAG, "Hardware signature verification failed after biometric ALLOW: ${e.message}")
                        return SudoUnlockResult.Declined("Hardware key verification failed.")
                    }
                }
                SudoUnlockResult.Unlocked(
                    password = password,
                    hardwareBacked = signature != null,
                )
            }
            BiometricGate.Decision.DENY ->
                SudoUnlockResult.Declined("Authentication was declined.")
            BiometricGate.Decision.UNAVAILABLE ->
                SudoUnlockResult.Declined("Authentication prompt could not be shown.")
        }
    }

    private fun prepareHardwareSignature(): Signature? {
        return try {
            if (!hardwareKeySource.contains(SUDO_HARDWARE_KEY_ALIAS)) {
                hardwareKeySource.generate(SUDO_HARDWARE_KEY_ALIAS, preferStrongBox = true)
            }
            try {
                hardwareKeySource.initSignature(SUDO_HARDWARE_KEY_ALIAS)
            } catch (e: Exception) {
                // Key invalidated by new biometric enrollment or corrupt;
                // rotate the gate key once and retry.
                Log.i(TAG, "Rotating invalidated sudo hardware gate key: ${e.message}")
                hardwareKeySource.delete(SUDO_HARDWARE_KEY_ALIAS)
                hardwareKeySource.generate(SUDO_HARDWARE_KEY_ALIAS, preferStrongBox = true)
                hardwareKeySource.initSignature(SUDO_HARDWARE_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hardware gate key unavailable, falling back to non-crypto prompt: ${e.message}")
            null
        }
    }

    companion object {
        const val SUDO_HARDWARE_KEY_ALIAS = "haven_sudo_hw_gate"
        private val CHALLENGE_BYTES = "haven-sudo-auth-v1".toByteArray(Charsets.UTF_8)
    }
}
