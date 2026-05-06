package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile

@Dao
interface WorkspaceDao {

    @Query("SELECT * FROM workspace_profile ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<WorkspaceProfile>>

    @Query("SELECT * FROM workspace_profile WHERE id = :id")
    suspend fun getById(id: String): WorkspaceProfile?

    @Query("SELECT * FROM workspace_item WHERE workspaceId = :workspaceId ORDER BY sortOrder")
    suspend fun getItemsForWorkspace(workspaceId: String): List<WorkspaceItem>

    @Query("SELECT * FROM workspace_item WHERE workspaceId = :workspaceId ORDER BY sortOrder")
    fun observeItemsForWorkspace(workspaceId: String): Flow<List<WorkspaceItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: WorkspaceProfile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<WorkspaceItem>)

    @Query("DELETE FROM workspace_item WHERE workspaceId = :workspaceId")
    suspend fun deleteItemsForWorkspace(workspaceId: String)

    @Query("DELETE FROM workspace_profile WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Transaction
    suspend fun saveWorkspace(profile: WorkspaceProfile, items: List<WorkspaceItem>) {
        upsertProfile(profile)
        deleteItemsForWorkspace(profile.id)
        upsertItems(items)
    }
}
