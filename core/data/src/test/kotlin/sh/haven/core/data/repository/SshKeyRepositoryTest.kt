package sh.haven.core.data.repository

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.Keystore

/**
 * Unit coverage for [SshKeyRepository.rename] (#231). The rename path
 * never touches the [Keystore] or [Context] — it reads the stored row and
 * upserts it back with a new label — so a mocked DAO is enough.
 */
class SshKeyRepositoryTest {

    private val context: Context = mockk(relaxed = true)
    private val keystore: Keystore = mockk(relaxed = true)

    private fun repo(dao: SshKeyDao) = SshKeyRepository(dao, context, keystore, FakeHardwareKeySource())

    private fun storedKey() = SshKey(
        id = "key-1",
        label = "FIDO2: ssh:vpn",
        keyType = "sk-ssh-ed25519@openssh.com",
        // Stand in for the encrypted-at-rest private bytes read back from
        // the DB. rename must pass these through verbatim.
        privateKeyBytes = byteArrayOf(1, 2, 3, 4, 5),
        publicKeyOpenSsh = "sk-ssh-ed25519@openssh.com AAAA...",
        fingerprintSha256 = "SHA256:abc",
    )

    @Test
    fun renameChangesLabelAndPassesEncryptedBytesThroughUntouched() = runBlocking {
        val dao: SshKeyDao = mockk()
        val existing = storedKey()
        coEvery { dao.getById("key-1") } returns existing
        val captured = slot<SshKey>()
        coEvery { dao.upsert(capture(captured)) } just Runs

        repo(dao).rename("key-1", "YK vpn carry A")

        val saved = captured.captured
        assertEquals("YK vpn carry A", saved.label)
        assertEquals("key-1", saved.id)
        // Bytes are not re-encrypted: a regression to save()-via-copy would
        // run KeyEncryption.encrypt over the already-encrypted bytes here.
        assertTrue(existing.privateKeyBytes.contentEquals(saved.privateKeyBytes))
    }

    @Test
    fun renameOnMissingKeyIsANoOp() = runBlocking {
        val dao: SshKeyDao = mockk()
        coEvery { dao.getById("gone") } returns null

        repo(dao).rename("gone", "whatever")

        coVerify(exactly = 0) { dao.upsert(any()) }
    }
}
