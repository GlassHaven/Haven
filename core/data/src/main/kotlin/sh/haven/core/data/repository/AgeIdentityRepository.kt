package sh.haven.core.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.AgeIdentityDao
import sh.haven.core.data.db.entities.AgeIdentityEntity
import sh.haven.core.security.AgeFile
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AgeIdentityRepository"

/**
 * CRUD for age [AgeIdentityEntity] rows. The private key
 * ([AgeIdentityEntity.secret]) is encrypted at rest with
 * [CredentialEncryption]; the public [AgeIdentityEntity.recipient] is
 * stored in the clear. [observeAll] returns rows with the secret still
 * encrypted — the UI only needs the label and recipient. Callers that
 * actually decrypt a file fetch the plaintext key via [fetchSecret].
 */
@Singleton
class AgeIdentityRepository @Inject constructor(
    private val ageIdentityDao: AgeIdentityDao,
    @ApplicationContext private val context: Context,
) {
    /** Observe all identities (secret left encrypted — UI uses label + recipient). */
    fun observeAll(): Flow<List<AgeIdentityEntity>> = ageIdentityDao.observeAll()

    suspend fun getAll(): List<AgeIdentityEntity> = ageIdentityDao.getAll()

    /** All identities with [AgeIdentityEntity.secret] decrypted — for encrypted backup export only. */
    suspend fun getAllDecrypted(): List<AgeIdentityEntity> = ageIdentityDao.getAll().mapNotNull { row ->
        try {
            row.copy(secret = CredentialEncryption.decrypt(context, row.secret))
        } catch (e: Exception) {
            Log.w(TAG, "age identity '${row.id}' decrypt failed during export: ${e.message}")
            null
        }
    }

    /** Store an imported identity whose [AgeIdentityEntity.secret] is currently plaintext, encrypting at rest. */
    suspend fun savePlaintext(identity: AgeIdentityEntity) {
        ageIdentityDao.upsert(
            identity.copy(secret = CredentialEncryption.encrypt(context, identity.secret)),
        )
    }

    /** Generate a new age identity, store it (secret encrypted), and return the stored row. */
    suspend fun create(label: String): AgeIdentityEntity {
        val identity = AgeFile.generateIdentity()
        val row = AgeIdentityEntity(
            label = label,
            recipient = identity.recipient,
            secret = CredentialEncryption.encrypt(context, identity.secret),
        )
        ageIdentityDao.upsert(row)
        return row
    }

    /** Public `age1…` recipient for [id], or null if missing. */
    suspend fun getRecipient(id: String): String? = ageIdentityDao.getById(id)?.recipient

    /** Plaintext `AGE-SECRET-KEY-1…` for [id], or null if missing / undecryptable. */
    suspend fun fetchSecret(id: String): String? {
        val row = ageIdentityDao.getById(id) ?: return null
        return try {
            CredentialEncryption.decrypt(context, row.secret)
        } catch (e: Exception) {
            Log.w(TAG, "age identity decrypt failed: ${e.message}")
            null
        }
    }

    suspend fun delete(id: String) = ageIdentityDao.deleteById(id)
}
