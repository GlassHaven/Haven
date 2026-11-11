package sh.haven.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.TunnelConfigDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.security.CredentialEncryption

@RunWith(RobolectricTestRunner::class)
class ConnectionRepositoryCredentialTest {

    private lateinit var context: Context
    private lateinit var connectionDao: ConnectionDao
    private lateinit var tunnelConfigDao: TunnelConfigDao
    private lateinit var chatRepository: ChatRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        connectionDao = mockk(relaxed = true)
        tunnelConfigDao = mockk(relaxed = true)
        chatRepository = mockk(relaxed = true)
        coEvery { connectionDao.getAll() } returns emptyList()
    }

    @Test
    fun `save encrypts and getById decrypts all 13 credential fields including spicePassword`() = runTest {
        val repo = ConnectionRepository(connectionDao, tunnelConfigDao, chatRepository, context)
        var profile = ConnectionProfile(id = "p-cred", label = "Spice VM", host = "10.0.0.2", username = "root")
        for (field in ConnectionProfile.CREDENTIAL_FIELDS) {
            profile = field.write(profile, "plain-${field.name}")
        }

        val storedSlot = slot<ConnectionProfile>()
        coEvery { connectionDao.upsert(capture(storedSlot)) } returns Unit
        repo.save(profile)

        val atRest = storedSlot.captured
        for (field in ConnectionProfile.CREDENTIAL_FIELDS) {
            val raw = field.read(atRest)!!
            assertTrue(
                "${field.name} must be encrypted at rest, got $raw",
                CredentialEncryption.isEncrypted(raw),
            )
        }

        coEvery { connectionDao.getById("p-cred") } returns atRest
        val loaded = repo.getById("p-cred")!!
        for (field in ConnectionProfile.CREDENTIAL_FIELDS) {
            assertEquals("plain-${field.name}", field.read(loaded))
        }
    }

    @Test
    fun `migrateLegacyPlaintextCredentials upgrades plaintext spicePassword without corrupting encrypted fields`() = runTest {
        val repo = ConnectionRepository(connectionDao, tunnelConfigDao, chatRepository, context)
        val alreadyEncryptedSsh = CredentialEncryption.encrypt(context, "ssh-secret")
        val legacyRow = ConnectionProfile(
            id = "p-legacy",
            label = "Legacy SPICE",
            host = "10.0.0.3",
            username = "u",
            sshPassword = alreadyEncryptedSsh,
            spicePassword = "legacy-spice-plaintext",
            spaKey = "legacy-spa-plaintext",
        )
        coEvery { connectionDao.getAll() } returns listOf(legacyRow)
        val migratedSlot = slot<ConnectionProfile>()
        coEvery { connectionDao.upsert(capture(migratedSlot)) } returns Unit

        repo.migrateLegacyPlaintextCredentials()

        val migrated = migratedSlot.captured
        assertEquals("Already-encrypted ciphertext must not be re-wrapped", alreadyEncryptedSsh, migrated.sshPassword)
        assertTrue(CredentialEncryption.isEncrypted(migrated.spicePassword!!))
        assertTrue(CredentialEncryption.isEncrypted(migrated.spaKey!!))
        assertEquals("legacy-spice-plaintext", CredentialEncryption.decrypt(context, migrated.spicePassword!!))
        assertEquals("legacy-spa-plaintext", CredentialEncryption.decrypt(context, migrated.spaKey!!))
    }

    @Test
    fun `save refuses to persist ephemeral profiles`() = runTest {
        val repo = ConnectionRepository(connectionDao, tunnelConfigDao, chatRepository, context)
        val ephemeral = ConnectionProfile(
            id = "ephem-1",
            label = "Quick",
            host = "10.0.0.4",
            username = "root",
            sshPassword = "pw",
        ).also { it.ephemeral = true }

        repo.save(ephemeral)

        coVerify(exactly = 0) { connectionDao.upsert(any()) }
    }
}
