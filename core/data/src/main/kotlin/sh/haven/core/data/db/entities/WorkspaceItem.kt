package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One launchable element of a [WorkspaceProfile]. The [kind] dictates
 * how the launcher dispatches it; [connectionProfileId] is required for
 * every kind except [Kind.WAYLAND] and is `SET_NULL`-ed when its
 * profile is deleted (the launcher reports such items as failed rather
 * than silently dropping them, so the workspace stays whole).
 */
@Entity(
    tableName = "workspace_item",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceProfile::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConnectionProfile::class,
            parentColumns = ["id"],
            childColumns = ["connectionProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("workspaceId"),
        Index("connectionProfileId"),
    ],
)
data class WorkspaceItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val workspaceId: String,
    val kind: Kind,
    val connectionProfileId: String? = null,
    /** Starting path for [Kind.FILE_BROWSER]; ignored otherwise. */
    val path: String? = null,
    val sortOrder: Int = 0,
) {
    /**
     * The four launchable surfaces a workspace can compose. The
     * underlying transport (SSH vs. Mosh vs. ET, SFTP vs. SMB vs.
     * Rclone, VNC vs. RDP) derives from the referenced
     * [ConnectionProfile.connectionType], so adding a new transport in
     * an existing kind doesn't expand this enum.
     */
    enum class Kind {
        TERMINAL,
        FILE_BROWSER,
        DESKTOP,
        WAYLAND,
    }
}
