package sh.haven.core.data.repository

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.KeyEncryption
import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreStore

/**
 * Hardware-resident SSH keys (`hardwareAlias != null`): the repository
 * must dispatch them to [KeyMaterial.Hardware] instead of routing them
 * through the decrypting keystore fetch, and they must never surface in
 * any decrypted-private-bytes result set (`getAllDecrypted*`), which
 * backs export, backup and "any saved key" auto-auth.
 */
class SshKeyHardwareKeyTest {

    private val context: Context = mockk(relaxed = true)
    private val keystore: Keystore = mockk(relaxed = true)
    private val dao: SshKeyDao = mockk(relaxed = true)
    private val hardwareKeys = FakeHardwareKeySource()

    @Before
    fun mockKeyEncryption() {
        // KeyEncryption needs the real Android Keystore; the object mock
        // keeps save() tests on the JVM and lets them assert whether
        // encryption ran at all.
        mockkObject(KeyEncryption)
    }

    @After
    fun unmockKeyEncryption() {
        unmockkObject(KeyEncryption)
    }

    private fun softwareKey(id: String) = SshKey(
        id = id,
        label = id,
        keyType = "ssh-ed25519",
        privateKeyBytes = byteArrayOf(1, 2, 3),
        publicKeyOpenSsh = "ssh-ed25519 AAAA",
        fingerprintSha256 = "SHA256:$id",
    )

    /** Storage convention: empty privateKeyBytes + non-null hardwareAlias. */
    private fun hardwareKey(id: String, alias: String = "haven-hw-$id") = SshKey(
        id = id,
        label = id,
        keyType = "ecdsa-sha2-nistp256",
        privateKeyBytes = ByteArray(0),
        publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA",
        fingerprintSha256 = "SHA256:$id",
        hardwareAlias = alias,
    )

    @Test
    fun `fetchKeyMaterial dispatches hardware keys without touching the keystore`() = runBlocking {
        coEvery { dao.getById("hw") } returns hardwareKey("hw", "alias-1")

        val result = SshKeyRepository(dao, context, keystore, hardwareKeys).fetchKeyMaterial("hw")

        assertEquals(KeyMaterial.Hardware("alias-1"), result)
        // No decrypt attempt — the private key is not on this device.
        coVerify(exactly = 0) { keystore.fetch(KeystoreStore.SSH_KEYS, any()) }
    }

    @Test
    fun `fetchKeyMaterial still routes software keys through the keystore`() = runBlocking {
        coEvery { dao.getById("sw") } returns softwareKey("sw")
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "sw") } returns
            KeystoreFetch.Bytes(byteArrayOf(9, 9))

        val result = SshKeyRepository(dao, context, keystore, hardwareKeys).fetchKeyMaterial("sw")

        assertTrue(result is KeyMaterial.Available)
        assertTrue((result as KeyMaterial.Available).bytes.contentEquals(byteArrayOf(9, 9)))
    }

    @Test
    fun `a missing row is still Missing`() = runBlocking {
        coEvery { dao.getById("ghost") } returns null

        assertEquals(
            KeyMaterial.Missing,
            SshKeyRepository(dao, context, keystore, hardwareKeys).fetchKeyMaterial("ghost"),
        )
        coVerify(exactly = 0) { keystore.fetch(KeystoreStore.SSH_KEYS, any()) }
    }

    @Test
    fun `getDecryptedKeyBytes yields null for hardware keys`() = runBlocking {
        // Hardware keys export no local private bytes — the lenient
        // accessor must not hand out an empty array that a caller could
        // mistake for loadable key material.
        coEvery { dao.getById("hw") } returns hardwareKey("hw")

        assertNull(SshKeyRepository(dao, context, keystore, hardwareKeys).getDecryptedKeyBytes("hw"))
    }

    @Test
    fun `getAllDecryptedDetailed classifies hardware keys into their own pool`() = runBlocking {
        coEvery { dao.getAll() } returns listOf(softwareKey("sw"), hardwareKey("hw"))
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "sw") } returns
            KeystoreFetch.Bytes(byteArrayOf(1))

        val result = SshKeyRepository(dao, context, keystore, hardwareKeys).getAllDecryptedDetailed()

        // The hardware key joins the auth-usable pool via its alias, but it
        // is NOT part of the decrypted-bytes pool (which backs export /
        // backup / PEM paths) and can never be a decline — its ceremony is
        // per-signature, later, inside KeystoreSshIdentity.
        assertEquals(listOf("sw"), result.keys.map { it.id })
        assertEquals(listOf("hw"), result.hardware.map { it.id })
        assertTrue(result.declined.isEmpty())
        coVerify(exactly = 0) { keystore.fetch(KeystoreStore.SSH_KEYS, "hw") }
    }

    @Test
    fun `getAllDecrypted keeps excluding hardware keys`() = runBlocking {
        coEvery { dao.getAll() } returns listOf(hardwareKey("hw"))
        coEvery { keystore.fetch(KeystoreStore.SSH_KEYS, "hw") } returns
            KeystoreFetch.Bytes(byteArrayOf(1))

        val keys = SshKeyRepository(dao, context, keystore, hardwareKeys).getAllDecrypted()

        assertTrue(keys.isEmpty())
    }

    @Test
    fun `save passes hardware keys' empty bytes through unencrypted`() = runBlocking {
        val stored = slot<SshKey>()
        coEvery { dao.upsert(capture(stored)) } returns Unit
        every { KeyEncryption.encrypt(any(), any()) } returns byteArrayOf(7, 7)

        SshKeyRepository(dao, context, keystore, hardwareKeys).save(hardwareKey("hw"))

        // Stored verbatim: empty stays empty, no misleading ciphertext.
        assertEquals(0, stored.captured.privateKeyBytes.size)
        io.mockk.verify(exactly = 0) { KeyEncryption.encrypt(any(), any()) }
    }

    @Test
    fun `save still encrypts non-empty software key bytes`() = runBlocking {
        val stored = slot<SshKey>()
        coEvery { dao.upsert(capture(stored)) } returns Unit
        every { KeyEncryption.encrypt(any(), any()) } returns byteArrayOf(7, 7)

        SshKeyRepository(dao, context, keystore, hardwareKeys).save(softwareKey("sw"))

        assertTrue(stored.captured.privateKeyBytes.contentEquals(byteArrayOf(7, 7)))
    }

    // ---------- delete: SSOT for the Keystore-alias cleanup ----------

    @Test
    fun `delete removes the row and the hardware alias`() = runBlocking {
        coEvery { dao.getById("hw") } returns hardwareKey("hw", "alias-1")

        SshKeyRepository(dao, context, keystore, hardwareKeys).delete("hw")

        coVerify(exactly = 1) { dao.deleteById("hw") }
        assertEquals(listOf("alias-1"), hardwareKeys.deletedAliases)
    }

    @Test
    fun `delete of a software key never touches the hardware source`() = runBlocking {
        coEvery { dao.getById("sw") } returns softwareKey("sw")

        SshKeyRepository(dao, context, keystore, hardwareKeys).delete("sw")

        coVerify(exactly = 1) { dao.deleteById("sw") }
        assertTrue(hardwareKeys.deletedAliases.isEmpty())
    }

    @Test
    fun `delete of a missing id is a no-op and deletes no alias`() = runBlocking {
        coEvery { dao.getById("ghost") } returns null

        SshKeyRepository(dao, context, keystore, hardwareKeys).delete("ghost")

        coVerify(exactly = 1) { dao.deleteById("ghost") }
        assertTrue(hardwareKeys.deletedAliases.isEmpty())
    }
}
