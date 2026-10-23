package sh.haven.core.security

import java.security.Signature
import javax.inject.Inject
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * External metadata of a hardware-resident SSH key (the return value of
 * [HardwareKeySource.generate]). `publicKeyOpenSsh` can be shown or pasted
 * into authorized_keys as-is. Top-level rather than nested in
 * [HardwareSshKeyManager]: it is part of the interface contract, and
 * consumers should not depend on the concrete class for a return type.
 */
data class HardwareKeyInfo(
    val alias: String,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val strongBoxBacked: Boolean,
)

/**
 * Thin interface over hardware-resident SSH keys (a swappable view of
 * [HardwareSshKeyManager]).
 *
 * Boundary: identical to [HardwareSshKeyManager] — it only gives the same
 * capabilities an interface type. It exists for testability:
 * [HardwareSshKeyManager] talks to the real AndroidKeyStore, which JVM unit
 * tests cannot touch; consumers (SshKeyRepository / KeystoreSshKeySigner)
 * depend on this interface, and tests inject a fake to cover the
 * generate/delete paths (the same injection pattern as
 * OpenKeychainProvider/Keystore).
 *
 * Contract (details in [HardwareSshKeyManager]'s KDoc, not repeated here):
 * - [generate] creates an ECDSA P-256 key in the secure chip; the private
 *   half never leaves the hardware and every use asks for a biometric.
 * - [delete] is idempotent: a missing alias succeeds silently.
 */
interface HardwareKeySource {
    fun generate(alias: String, preferStrongBox: Boolean): HardwareKeyInfo
    fun initSignature(alias: String): Signature
    fun delete(alias: String)
    fun contains(alias: String): Boolean
}

/**
 * Binds [HardwareSshKeyManager] as the app-wide [HardwareKeySource].
 * Consumers depend on the interface; tests swap in a fake (the same
 * binding pattern as KeystoreModule).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HardwareKeySourceModule {
    @Binds
    abstract fun bindHardwareKeySource(impl: HardwareSshKeyManager): HardwareKeySource
}
