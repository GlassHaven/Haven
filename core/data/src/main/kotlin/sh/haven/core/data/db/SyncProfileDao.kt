package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.SyncProfile

@Dao
interface SyncProfileDao {

    /** Most-recently-run first, falling back to most-recently-created. */
    @Query("SELECT * FROM sync_profiles ORDER BY COALESCE(lastRunAt, createdAt) DESC")
    fun observeAll(): Flow<List<SyncProfile>>

    @Query("SELECT * FROM sync_profiles ORDER BY COALESCE(lastRunAt, createdAt) DESC")
    suspend fun getAll(): List<SyncProfile>

    @Query("SELECT * FROM sync_profiles WHERE id = :id")
    suspend fun getById(id: String): SyncProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: SyncProfile)

    @Query("UPDATE sync_profiles SET lastRunAt = :timestamp WHERE id = :id")
    suspend fun touchLastRun(id: String, timestamp: Long)

    @Query("DELETE FROM sync_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}
