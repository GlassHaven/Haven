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
    private val chatRepository: ChatRepository,
    @ApplicationContext private val context: Context,
) {
    init {
        // Encrypt any plaintext credentials left from legacy installs or newly added columns.
        CoroutineScope(Dispatchers.IO).launch {
            migrateLegacyPlaintextCredentials()
        }
    }

    internal suspend fun migrateLegacyPlaintextCredentials() {
        for (profile in connectionDao.getAll()) {
            val hasPlaintext = ConnectionProfile.CREDENTIAL_FIELDS.any { field ->
                val raw = field.read(profile)
                !raw.isNullOrEmpty() && !CredentialEncryption.isEncrypted(raw)
            }
            if (hasPlaintext) {
                val migrated = ConnectionProfile.CREDENTIAL_FIELDS.fold(profile) { acc, field ->
                    val raw = field.read(acc)
                    if (!raw.isNullOrEmpty() && !CredentialEncryption.isEncrypted(raw)) {
                        field.write(acc, CredentialEncryption.encrypt(context, raw))
                    } else {
                        acc
                    }
                }
                connectionDao.upsert(migrated)
            }
        }
    }

    fun observeAll(): Flow<List<ConnectionProfile>> =
        connectionDao.observeAll().map { profiles -> profiles.map { decryptPasswords(it) } }

    suspend fun getAll(): List<ConnectionProfile> =
        connectionDao.getAll().map { decryptPasswords(it) }

    suspend fun getById(id: String): ConnectionProfile? =
        connectionDao.getById(id)?.let { decryptPasswords(it) }

    suspend fun save(profile: ConnectionProfile) {
        if (profile.ephemeral) return
        connectionDao.upsert(encryptPasswords(profile))
    }

    suspend fun delete(id: String) {
        tunnelConfigDao.deleteByOwner(id)
        // Saved chats must not dangle with a dead profileId.
        chatRepository.deleteByProfileId(id)
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

    private fun encryptPasswords(profile: ConnectionProfile): ConnectionProfile =
        ConnectionProfile.CREDENTIAL_FIELDS.fold(profile) { acc, field ->
            val raw = field.read(acc)
            if (raw != null) field.write(acc, CredentialEncryption.encrypt(context, raw)) else acc
        }

    /**
     * Decrypt every credential field for display/use.
     *
     * Uses [CredentialEncryption.decryptOrNull] so a permanently unreadable
     * value reads as "no credential" rather than taking the process down when
     * the connection list loads (#579). A transient Keystore failure still
     * throws — nulling it would let the next save overwrite ciphertext that is
     * about to become readable again.
     */
    private fun decryptPasswords(profile: ConnectionProfile): ConnectionProfile =
        ConnectionProfile.CREDENTIAL_FIELDS.fold(profile) { acc, field ->
            val raw = field.read(acc)
            if (raw != null) field.write(acc, CredentialEncryption.decryptOrNull(context, raw)) else acc
        }
}
