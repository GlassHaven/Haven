package sh.haven.core.data.repository

import sh.haven.core.security.HardwareKeySource
import sh.haven.core.security.HardwareSshKeyManager

/**
 * JVM fake for [HardwareKeySource], shared by the SshKeyRepository tests.
 * Generates deterministic metadata from the alias (no real Keystore), and
 * records deletions so tests can assert the SSOT cleanup — the real
 * manager talks to AndroidKeyStore, which a JVM unit test cannot touch.
 */
class FakeHardwareKeySource : HardwareKeySource {

    /** Aliases handed to [delete], in order (idempotent deletes included). */
    val deletedAliases = mutableListOf<String>()

    /** Set to make [generate] throw, exercising the failure path. */
    var generateError: Exception? = null

    val generatedAliases = mutableListOf<String>()

    override fun generate(alias: String, preferStrongBox: Boolean): HardwareSshKeyManager.HardwareKeyInfo {
        generateError?.let { throw it }
        generatedAliases += alias
        return HardwareSshKeyManager.HardwareKeyInfo(
            alias = alias,
            publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA $alias",
            fingerprintSha256 = "SHA256:fake-$alias",
            strongBoxBacked = preferStrongBox,
        )
    }

    override fun initSignature(alias: String) =
        throw UnsupportedOperationException("signature init is not part of repository tests")

    override fun delete(alias: String) {
        deletedAliases += alias
    }

    override fun contains(alias: String): Boolean = alias in generatedAliases
}
