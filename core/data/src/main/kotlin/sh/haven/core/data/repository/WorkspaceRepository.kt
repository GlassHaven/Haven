package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.WorkspaceDao
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceRepository @Inject constructor(
    private val dao: WorkspaceDao,
) {
    fun observeAll(): Flow<List<WorkspaceProfile>> = dao.observeAll()

    fun observeItems(workspaceId: String): Flow<List<WorkspaceItem>> =
        dao.observeItemsForWorkspace(workspaceId)

    suspend fun getWorkspace(id: String): WorkspaceWithItems? {
        val profile = dao.getById(id) ?: return null
        return WorkspaceWithItems(profile, dao.getItemsForWorkspace(id))
    }

    /**
     * Atomic save: writes the profile, drops any prior items for that
     * workspace, and writes the new items in their list order. Items
     * are renumbered sequentially before insert so [WorkspaceItem.sortOrder]
     * always reflects list position.
     */
    suspend fun save(profile: WorkspaceProfile, items: List<WorkspaceItem>) {
        require(items.isNotEmpty()) { "workspace must contain at least one item" }
        items.forEach { item ->
            when (item.kind) {
                WorkspaceItem.Kind.WAYLAND -> require(item.connectionProfileId == null) {
                    "WAYLAND items must not reference a connection profile"
                }
                else -> require(item.connectionProfileId != null) {
                    "${item.kind} items require a connectionProfileId"
                }
            }
        }
        val sealed = profile.copy(updatedAt = System.currentTimeMillis())
        val numbered = items.mapIndexed { index, item ->
            item.copy(workspaceId = profile.id, sortOrder = index)
        }
        dao.saveWorkspace(sealed, numbered)
    }

    suspend fun delete(id: String) = dao.deleteProfile(id)
}

data class WorkspaceWithItems(
    val profile: WorkspaceProfile,
    val items: List<WorkspaceItem>,
)
