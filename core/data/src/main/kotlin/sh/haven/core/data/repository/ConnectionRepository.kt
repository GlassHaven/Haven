package sh.haven.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.TunnelConfigDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
    private val connectionDao: ConnectionDao,
    private val tunnelConfigDao: TunnelConfigDao,
    @ApplicationContext private val context: Context,
) {
    init {
        // Encrypt any plaintext passwords left from before v3.3.0.
        CoroutineScope(Dispatchers.IO).launch {
            for (profile in connectionDao.getAll()) {
                val hasPlaintext = listOfNotNull(
                    profile.sshPassword, profile.vncPassword,
                    profile.rdpPassword, profile.smbPassword,
                    profile.proxyPassword, profile.reticulumPassphrase,
                    profile.emailPassword, profile.emailMailboxPassword,
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

    suspend fun delete(id: String) {
        tunnelConfigDao.deleteByOwner(id)
        connectionDao.deleteById(id)
    }

    suspend fun markConnected(id: String) = connectionDao.updateLastConnected(id)

    /** Toggle whether the MCP agent may operate on this connection (targeted, no re-encrypt). */
    suspend fun updateMcpEnabled(id: String, enabled: Boolean) =
        connectionDao.updateMcpEnabled(id, enabled)

    /** Cheap mcpEnabled lookup (no decryption) for the MCP dispatch hot path; null if unknown. */
    suspend fun isMcpEnabled(id: String): Boolean? = connectionDao.isMcpEnabled(id)

    suspend fun updateSortOrder(id: String, sortOrder: Int) = connectionDao.updateSortOrder(id, sortOrder)

    /** Targeted host update, no re-encrypt — #376 rediscovery follows a device to its new DHCP address. */
    suspend fun updateHost(id: String, host: String) = connectionDao.updateHost(id, host)

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
        proxyPassword = profile.proxyPassword?.let { CredentialEncryption.encrypt(context, it) },
        spaKey = profile.spaKey?.let { CredentialEncryption.encrypt(context, it) },
        spaHmacKey = profile.spaHmacKey?.let { CredentialEncryption.encrypt(context, it) },
        reticulumPassphrase = profile.reticulumPassphrase?.let { CredentialEncryption.encrypt(context, it) },
        emailPassword = profile.emailPassword?.let { CredentialEncryption.encrypt(context, it) },
        emailMailboxPassword = profile.emailMailboxPassword?.let { CredentialEncryption.encrypt(context, it) },
    )

    /**
     * Decrypt every credential field for display/use.
     *
     * Uses [CredentialEncryption.decryptOrNull] so a permanently unreadable
     * value reads as "no credential" rather than taking the process down when
     * the connection list loads (#579). A transient Keystore failure still
     * throws — nulling it would let the next save overwrite ciphertext that is
     * about to become readable again.
     */
    private fun decryptPasswords(profile: ConnectionProfile): ConnectionProfile = profile.copy(
        sshPassword = profile.sshPassword?.let { CredentialEncryption.decryptOrNull(context, it) },
        vncPassword = profile.vncPassword?.let { CredentialEncryption.decryptOrNull(context, it) },
        rdpPassword = profile.rdpPassword?.let { CredentialEncryption.decryptOrNull(context, it) },
        smbPassword = profile.smbPassword?.let { CredentialEncryption.decryptOrNull(context, it) },
        proxyPassword = profile.proxyPassword?.let { CredentialEncryption.decryptOrNull(context, it) },
        spaKey = profile.spaKey?.let { CredentialEncryption.decryptOrNull(context, it) },
        spaHmacKey = profile.spaHmacKey?.let { CredentialEncryption.decryptOrNull(context, it) },
        reticulumPassphrase = profile.reticulumPassphrase?.let { CredentialEncryption.decryptOrNull(context, it) },
        emailPassword = profile.emailPassword?.let { CredentialEncryption.decryptOrNull(context, it) },
        emailMailboxPassword = profile.emailMailboxPassword?.let { CredentialEncryption.decryptOrNull(context, it) },
    )
}
