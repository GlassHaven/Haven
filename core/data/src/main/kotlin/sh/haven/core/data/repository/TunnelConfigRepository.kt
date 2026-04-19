package sh.haven.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.TunnelConfigDao
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.security.KeyEncryption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores and retrieves [TunnelConfig]s, encrypting [TunnelConfig.configText]
 * at rest. Mirrors [SshKeyRepository] — same Android keystore–backed
 * encryption, same legacy-passthrough for unencrypted blobs.
 */
@Singleton
class TunnelConfigRepository @Inject constructor(
    private val tunnelConfigDao: TunnelConfigDao,
    @ApplicationContext private val context: Context,
) {
    fun observeAll(): Flow<List<TunnelConfig>> = tunnelConfigDao.observeAll()

    suspend fun getAll(): List<TunnelConfig> = tunnelConfigDao.getAll()

    /** Return the decrypted config, or null if no row. */
    suspend fun getById(id: String): TunnelConfig? {
        val row = tunnelConfigDao.getById(id) ?: return null
        return if (KeyEncryption.isEncrypted(row.configText)) {
            row.copy(configText = KeyEncryption.decrypt(context, row.configText))
        } else {
            row
        }
    }

    /** Save a config, encrypting [TunnelConfig.configText] before persistence. */
    suspend fun save(config: TunnelConfig) {
        tunnelConfigDao.upsert(
            config.copy(configText = KeyEncryption.encrypt(context, config.configText)),
        )
    }

    suspend fun delete(id: String) = tunnelConfigDao.deleteById(id)
}
