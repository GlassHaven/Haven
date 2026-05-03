package sh.haven.core.data.keystore

import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreAuditSnapshot
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreSection
import sh.haven.core.security.KeystoreStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [Keystore] implementation that fans out across every
 * registered [KeystoreSection]. Issue #129 — the security audit screen,
 * agent transport, and any future export tooling consult this single
 * surface instead of three parallel store-specific paths.
 *
 * Sections are explicitly listed (not Hilt multibindings) so the order
 * and presence of each store stays obvious to the next reader. Adding
 * a new section is a one-line constructor change.
 */
@Singleton
class UnifiedKeystore @Inject constructor(
    private val sshKeySection: SshKeySection,
    private val profileCredentialSection: ProfileCredentialSection,
) : Keystore {

    private val sections: Map<KeystoreStore, KeystoreSection> = mapOf(
        sshKeySection.store to sshKeySection,
        profileCredentialSection.store to profileCredentialSection,
    )

    override suspend fun enumerate(): List<KeystoreEntry> {
        // Concatenate in the order the sections were registered so the
        // audit screen can rely on a deterministic top-to-bottom view.
        // Each section is responsible for ordering within itself.
        val out = mutableListOf<KeystoreEntry>()
        for (section in sections.values) {
            out.addAll(section.enumerate())
        }
        return out
    }

    override suspend fun wipe(store: KeystoreStore, entryId: String): Boolean {
        val section = sections[store] ?: return false
        return section.wipe(entryId)
    }

    /**
     * Capture a snapshot at [System.currentTimeMillis]. The version
     * label stays null here — `core/data` has no stable handle on the
     * app's BuildConfig. The audit screen / backup flow that wraps
     * this call is expected to fill in `appVersion` itself before
     * persisting.
     */
    override suspend fun exportAudit(): KeystoreAuditSnapshot {
        return KeystoreAuditSnapshot(
            capturedAt = System.currentTimeMillis(),
            appVersion = null,
            entries = enumerate(),
        )
    }

    override suspend fun fetch(store: KeystoreStore, entryId: String): KeystoreFetch {
        val section = sections[store] ?: return KeystoreFetch.NotFound
        return section.fetch(entryId)
    }

    /**
     * Per-entry biometric protection only applies to SSH keys today —
     * that's where high-security identities live and where the UI
     * surfaces the toggle. Other stores return false (no-op) so the
     * audit screen can render a uniform call without branching on
     * store type at the call site.
     */
    override suspend fun setBiometricProtected(
        store: KeystoreStore,
        entryId: String,
        protected: Boolean,
    ): Boolean {
        return when (store) {
            KeystoreStore.SSH_KEYS -> sshKeySection.setBiometricProtected(entryId, protected)
            KeystoreStore.PROFILE_CREDENTIALS -> false
        }
    }
}
