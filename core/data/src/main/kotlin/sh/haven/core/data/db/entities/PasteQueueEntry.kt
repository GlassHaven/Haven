package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single pending (or completed) file copy in the paste queue.
 *
 * The queue is populated at the start of every cross-backend paste batch and
 * each row is marked [STATUS_DONE] as its transfer completes. Rows that fail
 * (network drop, source unreachable) stay [STATUS_PENDING] with [lastError]
 * set; they become eligible for the next "Resume paste" attempt.
 *
 * Persistence is what lets a paste survive app backgrounding, process death,
 * or a reboot. Combined with [bytesTransferred], the resume path can pick up
 * mid-file via `ChannelSftp.RESUME` (SFTP) or append mode (local) instead
 * of starting the entire file over.
 *
 * Only one batch lives at a time. Starting a new paste while the queue still
 * has pending rows is handled at the caller level — either confirm with the
 * user or append to the existing queue.
 */
@Entity(
    tableName = "paste_queue_entries",
    indices = [Index(value = ["status"])],
)
data class PasteQueueEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Ordering within the batch. Incremented for each leaf file as the
     * clipboard is expanded — directories contribute their children in the
     * same order they would have been walked in-memory.
     */
    val indexInBatch: Int,

    // ---- Source ----
    /** BackendType enum name — LOCAL / SFTP / SMB / RCLONE. */
    val sourceBackendType: String,
    val sourceProfileId: String,
    val sourceRemoteName: String? = null,
    val sourcePath: String,
    val sourceName: String,
    val sourceSize: Long,
    val sourceIsDirectory: Boolean = false,

    // ---- Destination ----
    val destBackendType: String,
    val destProfileId: String,
    val destRemote: String? = null,
    val destPath: String,

    // ---- Operation flags ----
    val isCut: Boolean = false,
    /** ConflictAction enum name the user picked (or implicit OVERWRITE). */
    val conflictAction: String = "OVERWRITE",

    // ---- Progress ----
    /**
     * Bytes already written to the destination. Used by the resume path to
     * skip the matching prefix of the source (SFTP: ChannelSftp.RESUME; local:
     * FileOutputStream append + input.skip()). Updated periodically during
     * transfer to cap replay work on process death.
     */
    val bytesTransferred: Long = 0,

    /** [STATUS_PENDING] or [STATUS_DONE]. */
    val status: String = STATUS_PENDING,

    /** Last failure message shown in the resume banner, if the row is still pending. */
    val lastError: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_DONE = "DONE"
    }
}
