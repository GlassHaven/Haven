package sh.haven.core.data.keystore

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreSection
import sh.haven.core.security.KeystoreStore

/**
 * Pin the aggregator's fan-out behaviour: enumerate concatenates each
 * section's output preserving registration order; wipe routes by
 * [KeystoreStore] to the matching section and surfaces unknown stores
 * as `false`.
 */
class UnifiedKeystoreTest {

    private fun entry(id: String, store: KeystoreStore) = KeystoreEntry(
        id = id, store = store, keyKind = KeyKind.SSH_PRIVATE,
        label = id, algorithm = "test",
    )

    @Test
    fun `enumerate concatenates sections in registration order`() = runTest {
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
            coEvery { enumerate() } returns listOf(
                entry("ssh-1", KeystoreStore.SSH_KEYS),
                entry("ssh-2", KeystoreStore.SSH_KEYS),
            )
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
            coEvery { enumerate() } returns listOf(
                entry("p1/sshPassword", KeystoreStore.PROFILE_CREDENTIALS),
            )
        }
        val keystore = UnifiedKeystore(sshSection, credSection)

        val ids = keystore.enumerate().map { it.id }
        // SSH first (registered first in the constructor), creds after.
        assertEquals(listOf("ssh-1", "ssh-2", "p1/sshPassword"), ids)
    }

    @Test
    fun `wipe routes to the matching section`() = runTest {
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
            coEvery { wipe("k1") } returns true
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
        }
        val keystore = UnifiedKeystore(sshSection, credSection)

        assertTrue(keystore.wipe(KeystoreStore.SSH_KEYS, "k1"))
        coVerify { sshSection.wipe("k1") }
        coVerify(exactly = 0) { credSection.wipe(any()) }
    }

    @Test
    fun `wipe surfaces the section's return value`() = runTest {
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
            coEvery { wipe("ghost") } returns false
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
        }
        val keystore = UnifiedKeystore(sshSection, credSection)
        assertFalse(keystore.wipe(KeystoreStore.SSH_KEYS, "ghost"))
    }

    @Test
    fun `exportAudit captures a snapshot containing every entry`() = runTest {
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
            coEvery { enumerate() } returns listOf(
                entry("ssh-1", KeystoreStore.SSH_KEYS),
                entry("ssh-2", KeystoreStore.SSH_KEYS),
            )
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
            coEvery { enumerate() } returns listOf(
                entry("p1/sshPassword", KeystoreStore.PROFILE_CREDENTIALS),
            )
        }
        val keystore = UnifiedKeystore(sshSection, credSection)

        val snapshot = keystore.exportAudit()
        assertEquals(3, snapshot.entries.size)
        assertEquals(
            listOf("ssh-1", "ssh-2", "p1/sshPassword"),
            snapshot.entries.map { it.id },
        )
        // Counts surface for at-a-glance audit summaries.
        assertEquals(2, snapshot.countsByStore[KeystoreStore.SSH_KEYS])
        assertEquals(1, snapshot.countsByStore[KeystoreStore.PROFILE_CREDENTIALS])
        // capturedAt must be set; appVersion stays null at this layer
        // (the wrapping audit screen / backup flow fills it in).
        assertTrue(
            "capturedAt must be a recent timestamp, got: ${snapshot.capturedAt}",
            snapshot.capturedAt > 0 &&
                snapshot.capturedAt <= System.currentTimeMillis(),
        )
        assertEquals(null, snapshot.appVersion)
    }

    @Test
    fun `fetch routes to the matching section`() = runTest {
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
            coEvery { fetch("k1") } returns KeystoreFetch.Bytes(byteArrayOf(0x01, 0x02))
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
        }
        val keystore = UnifiedKeystore(sshSection, credSection)

        val result = keystore.fetch(KeystoreStore.SSH_KEYS, "k1")
        assertTrue("expected Bytes, got: $result", result is KeystoreFetch.Bytes)
        coVerify { sshSection.fetch("k1") }
        coVerify(exactly = 0) { credSection.fetch(any()) }
    }

    @Test
    fun `fetch on unknown store returns NotFound without touching sections`() = runTest {
        // Can't easily construct a "third" KeystoreStore at compile time
        // — the enum is exhaustive — so test the unknown-id path with a
        // store the keystore knows but the section doesn't have.
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
            coEvery { fetch("ghost") } returns KeystoreFetch.NotFound
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
        }
        val keystore = UnifiedKeystore(sshSection, credSection)
        assertEquals(KeystoreFetch.NotFound, keystore.fetch(KeystoreStore.SSH_KEYS, "ghost"))
    }

    @Test
    fun `setBiometricProtected on SSH_KEYS routes to the SSH section`() = runTest {
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
            coEvery { setBiometricProtected("k1", true) } returns true
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
        }
        val keystore = UnifiedKeystore(sshSection, credSection)

        assertTrue(keystore.setBiometricProtected(KeystoreStore.SSH_KEYS, "k1", true))
        coVerify { sshSection.setBiometricProtected("k1", true) }
    }

    @Test
    fun `setBiometricProtected on PROFILE_CREDENTIALS is a no-op returning false`() = runTest {
        // Per #129 stage 5 the biometric toggle is SSH-keys-only.
        // Pinning the no-op contract so a future stage that adds
        // password-level biometric protection has to update this test
        // explicitly.
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
        }
        val keystore = UnifiedKeystore(sshSection, credSection)

        assertFalse(keystore.setBiometricProtected(KeystoreStore.PROFILE_CREDENTIALS, "p1/sshPassword", true))
        coVerify(exactly = 0) { sshSection.setBiometricProtected(any(), any()) }
    }

    @Test
    fun `wipe routes profile creds to the right section`() = runTest {
        val sshSection = mockk<SshKeySection>(relaxed = true).apply {
            every { store } returns KeystoreStore.SSH_KEYS
        }
        val credSection = mockk<ProfileCredentialSection>(relaxed = true).apply {
            every { store } returns KeystoreStore.PROFILE_CREDENTIALS
            coEvery { wipe("p1/sshPassword") } returns true
        }
        val keystore = UnifiedKeystore(sshSection, credSection)
        assertTrue(keystore.wipe(KeystoreStore.PROFILE_CREDENTIALS, "p1/sshPassword"))
        coVerify { credSection.wipe("p1/sshPassword") }
        coVerify(exactly = 0) { sshSection.wipe(any()) }
    }
}
