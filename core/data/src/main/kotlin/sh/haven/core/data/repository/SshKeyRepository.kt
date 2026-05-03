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

    /**
     * Get every stored key with decrypted private bytes. Routes each
     * row through [Keystore.fetch] so any biometric-protected entry
     * triggers its prompt before its bytes are returned. A row whose
     * fetch failed (denied prompt, decrypt error) is silently dropped
     * — callers walking the list (e.g. ConnectionsViewModel's "try
     * every key" fallback when no explicit key is assigned) treat a
     * missing key the same as a key the server doesn't accept.
     */
    suspend fun getAllDecrypted(): List<SshKey> = sshKeyDao.getAll().mapNotNull { key ->
        when (val r = keystore.fetch(KeystoreStore.SSH_KEYS, key.id)) {
            is KeystoreFetch.Bytes -> key.copy(privateKeyBytes = r.data)
            is KeystoreFetch.NotFound -> null
            is KeystoreFetch.Failed -> {
                Log.w(TAG, "getAllDecrypted: fetch for ${key.id} failed (${r.reason})")
                null
            }
            is KeystoreFetch.Password -> null
        }
    }

    suspend fun delete(id: String) = sshKeyDao.deleteById(id)
}
