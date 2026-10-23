package sh.haven.core.data.repository

import sh.haven.core.security.HardwareKeyInfo
import sh.haven.core.security.HardwareKeySource

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

    /** Aliases [contains] should report as taken (collision-probe tests). */
    val takenAliases = mutableSetOf<String>()

    /**
     * Force the collision probe deterministically: when > 0, the next
     * distinct unknown candidates are answered "taken" (the repository
     * mints random uuid suffixes, so a fixed collision can't be spelled
     * in advance).
     */
    var candidatesToRefuse = 0
    private var refused = 0

    /** Every alias probed via [contains], in order. */
    val probedCandidates = mutableListOf<String>()

    override fun generate(alias: String, preferStrongBox: Boolean): HardwareKeyInfo {
        generateError?.let { throw it }
        generatedAliases += alias
        return HardwareKeyInfo(
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

    override fun contains(alias: String): Boolean {
        probedCandidates += alias
        if (alias in generatedAliases || alias in takenAliases) return true
        if (refused < candidatesToRefuse) {
            refused++
            return true
        }
        return false
    }
}
