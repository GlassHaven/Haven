package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.PasteQueueEntry

@Dao
interface PasteQueueDao {

    @Query("SELECT * FROM paste_queue_entries ORDER BY indexInBatch ASC")
    fun observeAll(): Flow<List<PasteQueueEntry>>

    @Query("SELECT * FROM paste_queue_entries ORDER BY indexInBatch ASC")
    suspend fun getAll(): List<PasteQueueEntry>

    @Query("SELECT * FROM paste_queue_entries WHERE status = :status ORDER BY indexInBatch ASC")
    suspend fun getByStatus(status: String): List<PasteQueueEntry>

    @Query("SELECT COUNT(*) FROM paste_queue_entries WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM paste_queue_entries WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(sourceSize - bytesTransferred), 0) FROM paste_queue_entries WHERE status = :status")
    fun observePendingBytes(status: String): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<PasteQueueEntry>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PasteQueueEntry)

    @Query("UPDATE paste_queue_entries SET bytesTransferred = :bytes, updatedAt = :now WHERE id = :id")
    suspend fun updateBytesTransferred(id: Long, bytes: Long, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE paste_queue_entries SET status = :status, bytesTransferred = :bytes, " +
            "lastError = :error, updatedAt = :now WHERE id = :id",
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        bytes: Long,
        error: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM paste_queue_entries")
    suspend fun clear()

    @Query("DELETE FROM paste_queue_entries WHERE status = :status")
    suspend fun clearByStatus(status: String)
}
