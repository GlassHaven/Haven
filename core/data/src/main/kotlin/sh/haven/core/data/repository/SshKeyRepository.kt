package sh.haven.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.KeyEncryption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyRepository @Inject constructor(
    private val sshKeyDao: SshKeyDao,
    @ApplicationContext private val context: Context,
) {
    fun observeAll(): Flow<List<SshKey>> = sshKeyDao.observeAll()

    suspend fun getAll(): List<SshKey> = sshKeyDao.getAll()

    suspend fun getById(id: String): SshKey? = sshKeyDao.getById(id)

    /** Save a key, encrypting the private key bytes at rest. */
    suspend fun save(key: SshKey): Unit = sshKeyDao.upsert(
        key.copy(privateKeyBytes = KeyEncryption.encrypt(context, key.privateKeyBytes))
    )

    /** Get decrypted private key bytes for use during SSH auth. */
    suspend fun getDecryptedKeyBytes(id: String): ByteArray? {
        val key = sshKeyDao.getById(id) ?: return null
        return if (KeyEncryption.isEncrypted(key.privateKeyBytes)) {
            KeyEncryption.decrypt(context, key.privateKeyBytes)
        } else {
            // Legacy key stored before encryption was added
            key.privateKeyBytes
        }
    }

    /** Get all keys with decrypted private key bytes. */
    suspend fun getAllDecrypted(): List<SshKey> = sshKeyDao.getAll().map { key ->
        if (KeyEncryption.isEncrypted(key.privateKeyBytes)) {
            key.copy(privateKeyBytes = KeyEncryption.decrypt(context, key.privateKeyBytes))
        } else {
            key
        }
    }

    /** Attach a certificate to an existing key, encrypting at rest. */
    suspend fun attachCertificate(keyId: String, certBytes: ByteArray) {
        val key = sshKeyDao.getById(keyId) ?: return
        val encrypted = KeyEncryption.encrypt(context, certBytes)
        sshKeyDao.upsert(key.copy(certificateBytes = encrypted))
    }

    /** Remove certificate from a key. */
    suspend fun detachCertificate(keyId: String) {
        val key = sshKeyDao.getById(keyId) ?: return
        sshKeyDao.upsert(key.copy(certificateBytes = null))
    }

    /** Get decrypted certificate bytes for use during SSH auth. */
    suspend fun getDecryptedCertBytes(id: String): ByteArray? {
        val key = sshKeyDao.getById(id) ?: return null
        val certBytes = key.certificateBytes ?: return null
        return if (KeyEncryption.isEncrypted(certBytes)) {
            KeyEncryption.decrypt(context, certBytes)
        } else certBytes
    }

    suspend fun delete(id: String) = sshKeyDao.deleteById(id)
}
