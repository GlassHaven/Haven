package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.TunnelConfig

@Dao
interface TunnelConfigDao {

    @Query("SELECT * FROM tunnel_configs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TunnelConfig>>

    @Query("SELECT * FROM tunnel_configs ORDER BY createdAt DESC")
    suspend fun getAll(): List<TunnelConfig>

    @Query("SELECT * FROM tunnel_configs WHERE id = :id")
    suspend fun getById(id: String): TunnelConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: TunnelConfig)

    @Query("DELETE FROM tunnel_configs WHERE id = :id")
    suspend fun deleteById(id: String)
}
