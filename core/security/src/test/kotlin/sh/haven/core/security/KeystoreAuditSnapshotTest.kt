package sh.haven.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the [KeystoreAuditSnapshot] data class — particularly its
 * derived [KeystoreAuditSnapshot.countsByStore] and
 * [KeystoreAuditSnapshot.countsByKind] counters which the future audit
 * UI consumes for at-a-glance summaries.
 */
class KeystoreAuditSnapshotTest {

    private fun entry(
        id: String,
        store: KeystoreStore,
        kind: KeyKind,
    ) = KeystoreEntry(
        id = id, store = store, keyKind = kind,
        label = id, algorithm = "test",
    )

    @Test
    fun `countsByStore groups entries by store`() {
        val snapshot = KeystoreAuditSnapshot(
            capturedAt = 1000L,
            entries = listOf(
                entry("a", KeystoreStore.SSH_KEYS, KeyKind.SSH_PRIVATE),
                entry("b", KeystoreStore.SSH_KEYS, KeyKind.SSH_FIDO_SK),
                entry("c", KeystoreStore.PROFILE_CREDENTIALS, KeyKind.PROFILE_PASSWORD),
                entry("d", KeystoreStore.PROFILE_CREDENTIALS, KeyKind.PROFILE_PASSWORD),
                entry("e", KeystoreStore.PROFILE_CREDENTIALS, KeyKind.PROFILE_PASSWORD),
            ),
        )
        assertEquals(2, snapshot.countsByStore[KeystoreStore.SSH_KEYS])
        assertEquals(3, snapshot.countsByStore[KeystoreStore.PROFILE_CREDENTIALS])
    }

    @Test
    fun `countsByKind groups entries by key kind`() {
        // SSH_FIDO_SK and SSH_PRIVATE both live in SSH_KEYS but distinct
        // KeyKinds — the audit screen wants to surface "you have 1 FIDO
        // credential and 4 regular SSH keys" without re-walking the
        // entries list.
        val snapshot = KeystoreAuditSnapshot(
            capturedAt = 0L,
            entries = listOf(
                entry("a", KeystoreStore.SSH_KEYS, KeyKind.SSH_PRIVATE),
                entry("b", KeystoreStore.SSH_KEYS, KeyKind.SSH_PRIVATE),
                entry("c", KeystoreStore.SSH_KEYS, KeyKind.SSH_FIDO_SK),
                entry("d", KeystoreStore.PROFILE_CREDENTIALS, KeyKind.PROFILE_PASSWORD),
            ),
        )
        assertEquals(2, snapshot.countsByKind[KeyKind.SSH_PRIVATE])
        assertEquals(1, snapshot.countsByKind[KeyKind.SSH_FIDO_SK])
        assertEquals(1, snapshot.countsByKind[KeyKind.PROFILE_PASSWORD])
    }

    @Test
    fun `empty snapshot has empty count maps`() {
        val snapshot = KeystoreAuditSnapshot(capturedAt = 0L, entries = emptyList())
        assertTrue(snapshot.countsByStore.isEmpty())
        assertTrue(snapshot.countsByKind.isEmpty())
    }

    @Test
    fun `appVersion defaults to null when not supplied`() {
        // The aggregator at core/data has no stable handle on the app's
        // BuildConfig; its callers fill in the version label after the
        // snapshot returns. The default null preserves that contract.
        val snapshot = KeystoreAuditSnapshot(capturedAt = 0L, entries = emptyList())
        assertEquals(null, snapshot.appVersion)
    }
}
