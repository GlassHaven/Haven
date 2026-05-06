package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Named, ordered composition of [WorkspaceItem]s the user can launch in
 * one tap. Stored alongside connection profiles; cascade-deletes its
 * items when removed.
 */
@Entity(tableName = "workspace_profile")
data class WorkspaceProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
