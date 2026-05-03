package sh.haven.core.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.KeyEncryption
import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreStore
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshKeyRepository"

@Singleton
class SshKeyRepository @Inject constructor(
    private val sshKeyDao: SshKeyDao,
    @ApplicationContext private val context: Context,
    private val keystore: Keystore,
) {
    fun observeAll(): Flow<List<SshKey>> = sshKeyDao.observeAll()

    suspend fun getAll(): List<SshKey> = sshKeyDao.getAll()

    suspend fun getById(id: String): SshKey? = sshKeyDao.getById(id)

    /** Save a key, encrypting the private key bytes at rest. */
    suspend fun save(key: SshKey): Unit = sshKeyDao.upsert(
        key.copy(privateKeyBytes = KeyEncryption.encrypt(context, key.privateKeyBytes))
    )

    /**
     * Get decrypted private key bytes for use during SSH auth.
     *
     * Routes through the unified [Keystore.fetch] so any biometric
     * gating set on the entry (#129 stage 5) actually fires before
     * the bytes are returned. A denied prompt (or a backgrounded app
     * with no Activity to host the prompt) surfaces as null — same
     * as a missing key — so the SSH auth path falls through cleanly.
     */
    suspend fun getDecryptedKeyBytes(id: String): ByteArray? {
        val result = keystore.fetch(KeystoreStore.SSH_KEYS, id)
        return when (result) {
            is KeystoreFetch.Bytes -> result.data
            is KeystoreFetch.NotFound -> null
            is KeystoreFetch.Failed -> {
                Log.w(TAG, "fetch for key $id failed: ${result.reason}")
                null
            }
            // Password is the wrong shape for SSH-keys; treat as missing.
            is KeystoreFetch.Password -> null
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

    suspend fun delete(id: String) = sshKeyDao.deleteById(id)
}
