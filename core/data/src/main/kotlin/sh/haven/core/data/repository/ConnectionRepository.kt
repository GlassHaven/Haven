package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.entities.ConnectionProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
    private val connectionDao: ConnectionDao,
) {
    fun observeAll(): Flow<List<ConnectionProfile>> = connectionDao.observeAll()

    suspend fun getAll(): List<ConnectionProfile> = connectionDao.getAll()

    suspend fun getById(id: String): ConnectionProfile? = connectionDao.getById(id)

    suspend fun save(profile: ConnectionProfile) = connectionDao.upsert(profile)

    suspend fun delete(id: String) = connectionDao.deleteById(id)

    suspend fun markConnected(id: String) = connectionDao.updateLastConnected(id)

    suspend fun updateSortOrder(id: String, sortOrder: Int) = connectionDao.updateSortOrder(id, sortOrder)
}
