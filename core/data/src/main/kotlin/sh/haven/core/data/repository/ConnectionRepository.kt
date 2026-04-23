package sh.haven.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
    private val connectionDao: ConnectionDao,
    @ApplicationContext private val context: Context,
) {
    init {
        // Encrypt any plaintext passwords left from before v3.3.0.
        CoroutineScope(Dispatchers.IO).launch {
            for (profile in connectionDao.getAll()) {
                val hasPlaintext = listOfNotNull(
                    profile.sshPassword, profile.vncPassword,
                    profile.rdpPassword, profile.smbPassword,
                ).any { !CredentialEncryption.isEncrypted(it) }
                if (hasPlaintext) {
                    connectionDao.upsert(encryptPasswords(decryptPasswords(profile)))
                }
            }
        }
    }

    fun observeAll(): Flow<List<ConnectionProfile>> =
        connectionDao.observeAll().map { profiles -> profiles.map { decryptPasswords(it) } }

    suspend fun getAll(): List<ConnectionProfile> =
        connectionDao.getAll().map { decryptPasswords(it) }

    suspend fun getById(id: String): ConnectionProfile? =
        connectionDao.getById(id)?.let { decryptPasswords(it) }

    suspend fun save(profile: ConnectionProfile) =
        connectionDao.upsert(encryptPasswords(profile))

    suspend fun delete(id: String) = connectionDao.deleteById(id)

    suspend fun markConnected(id: String) = connectionDao.updateLastConnected(id)

    suspend fun updateSortOrder(id: String, sortOrder: Int) = connectionDao.updateSortOrder(id, sortOrder)

    suspend fun updateVncSettings(
        id: String,
        port: Int,
        username: String?,
        password: String?,
        sshForward: Boolean,
        sshProfileId: String? = null,
    ) = connectionDao.updateVncSettings(
        id, port, username,
        password?.let { CredentialEncryption.encrypt(context, it) },
        sshForward,
        sshProfileId,
    )

    private fun encryptPasswords(profile: ConnectionProfile): ConnectionProfile = profile.copy(
        sshPassword = profile.sshPassword?.let { CredentialEncryption.encrypt(context, it) },
        vncPassword = profile.vncPassword?.let { CredentialEncryption.encrypt(context, it) },
        rdpPassword = profile.rdpPassword?.let { CredentialEncryption.encrypt(context, it) },
        smbPassword = profile.smbPassword?.let { CredentialEncryption.encrypt(context, it) },
    )

    private fun decryptPasswords(profile: ConnectionProfile): ConnectionProfile = profile.copy(
        sshPassword = profile.sshPassword?.let { CredentialEncryption.decrypt(context, it) },
        vncPassword = profile.vncPassword?.let { CredentialEncryption.decrypt(context, it) },
        rdpPassword = profile.rdpPassword?.let { CredentialEncryption.decrypt(context, it) },
        smbPassword = profile.smbPassword?.let { CredentialEncryption.decrypt(context, it) },
    )
}
