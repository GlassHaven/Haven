package sh.haven.core.data.repository

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreStore

/**
 * #559: a declined unlock must stay distinguishable from a missing key
 * all the way up. It used to flatten to null here, which is what let an
 * SSH connect answer "I said no" by reaching for the next credential.
 */
class SshKeyDeclinedFetchTest {

    private val context: Context = mockk(relaxed = true)

    private fun key(id: String, label: String) = SshKey(
        id = id,
        label = label,
        keyType = "ssh-ed25519",
        privateKeyBytes = byteArrayOf(9, 9, 9),
        publicKeyOpenSsh = "ssh-ed25519 AAAA...",
        fingerprintSha256 = "SHA256:$id",
    )

    @Test
    fun `a declined fetch is Declined, not Missing`() = runBlocking {
        val dao: SshKeyDao = mockk(relaxed = true)
        val keystore: Keystore = mockk(relaxed = true)
        coEvery { dao.getById("k1") } returns key("k1", "work")
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "k1") } returns
            KeystoreFetch.Denied("Authentication was declined for key \"work\".")

        val result = SshKeyRepository(dao, context, keystore).fetchKeyMaterial("k1")

        assertTrue("got $result", result is KeyMaterial.Declined)
        assertEquals(
            "Authentication was declined for key \"work\".",
            (result as KeyMaterial.Declined).reason,
        )
    }

    @Test
    fun `a decrypt failure is still Missing — only a human 'no' is Declined`() = runBlocking {
        val dao: SshKeyDao = mockk(relaxed = true)
        val keystore: Keystore = mockk(relaxed = true)
        coEvery { dao.getById("k1") } returns key("k1", "work")
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "k1") } returns
            KeystoreFetch.Failed("Decryption failed")

        val result = SshKeyRepository(dao, context, keystore).fetchKeyMaterial("k1")

        assertEquals(KeyMaterial.Missing, result)
    }

    @Test
    fun `the lenient accessor still flattens a decline to null`() = runBlocking {
        // Deliberate: backup and listing want this. The comment on
        // getDecryptedKeyBytes is what keeps auth paths off it.
        val dao: SshKeyDao = mockk(relaxed = true)
        val keystore: Keystore = mockk(relaxed = true)
        coEvery { dao.getById("k1") } returns key("k1", "work")
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "k1") } returns
            KeystoreFetch.Denied("declined")

        assertEquals(null, SshKeyRepository(dao, context, keystore).getDecryptedKeyBytes("k1"))
    }

    @Test
    fun `getAllDecryptedDetailed reports declines separately from usable keys`() = runBlocking {
        val dao: SshKeyDao = mockk(relaxed = true)
        val keystore: Keystore = mockk(relaxed = true)
        coEvery { dao.getAll() } returns listOf(key("k1", "plain"), key("k2", "protected"))
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "k1") } returns
            KeystoreFetch.Bytes(byteArrayOf(1, 2, 3))
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "k2") } returns
            KeystoreFetch.Denied("Authentication was declined for key \"protected\".")

        val result = SshKeyRepository(dao, context, keystore).getAllDecryptedDetailed()

        assertEquals(listOf("k1"), result.keys.map { it.id })
        assertEquals(1, result.declined.size)
        assertTrue(result.declined.single().reason.contains("protected"))
        // The usable key still carries its DECRYPTED bytes, not the stored ones.
        assertTrue(result.keys.single().privateKeyBytes.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `the lenient list drops declined keys, which is why auth must not use it`() = runBlocking {
        val dao: SshKeyDao = mockk(relaxed = true)
        val keystore: Keystore = mockk(relaxed = true)
        coEvery { dao.getAll() } returns listOf(key("k1", "plain"), key("k2", "protected"))
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "k1") } returns
            KeystoreFetch.Bytes(byteArrayOf(1))
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "k2") } returns
            KeystoreFetch.Denied("declined")

        val keys = SshKeyRepository(dao, context, keystore).getAllDecrypted()

        assertEquals(listOf("k1"), keys.map { it.id })
    }
}
