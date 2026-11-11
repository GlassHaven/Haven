package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.SyncProfileDao
import sh.haven.core.data.db.entities.SyncProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncProfileRepository @Inject constructor(
    private val dao: SyncProfileDao,
) {
    fun observeAll(): Flow<List<SyncProfile>> = dao.observeAll()

    suspend fun getAll(): List<SyncProfile> = dao.getAll()

    suspend fun getById(id: String): SyncProfile? = dao.getById(id)

    suspend fun save(profile: SyncProfile) = dao.upsert(profile)

    /** Stamp `lastRunAt` so the entry sorts to the top on next observe. */
    suspend fun touchLastRun(id: String, timestamp: Long = System.currentTimeMillis()) =
        dao.touchLastRun(id, timestamp)

    suspend fun delete(id: String) = dao.deleteById(id)
}
