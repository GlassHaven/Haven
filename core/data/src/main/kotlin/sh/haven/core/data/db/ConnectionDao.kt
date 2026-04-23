package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.ConnectionProfile

@Dao
interface ConnectionDao {

    @Query("SELECT * FROM connection_profiles ORDER BY sortOrder ASC, label ASC")
    fun observeAll(): Flow<List<ConnectionProfile>>

    @Query("SELECT * FROM connection_profiles ORDER BY sortOrder ASC, label ASC")
    suspend fun getAll(): List<ConnectionProfile>

    @Query("SELECT * FROM connection_profiles WHERE id = :id")
    suspend fun getById(id: String): ConnectionProfile?

    @Upsert
    suspend fun upsert(profile: ConnectionProfile)

    @Update
    suspend fun update(profile: ConnectionProfile)

    @Delete
    suspend fun delete(profile: ConnectionProfile)

    @Query("DELETE FROM connection_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE connection_profiles SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE connection_profiles SET vncPort = :port, vncUsername = :username, vncPassword = :password, vncSshForward = :sshForward, vncSshProfileId = :sshProfileId WHERE id = :id")
    suspend fun updateVncSettings(id: String, port: Int, username: String?, password: String?, sshForward: Boolean = true, sshProfileId: String? = null)

    @Query("UPDATE connection_profiles SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)
}
