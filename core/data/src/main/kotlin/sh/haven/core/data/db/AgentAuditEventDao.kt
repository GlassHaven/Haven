package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.AgentAuditEvent

@Dao
interface AgentAuditEventDao {

    @Insert
    suspend fun insert(event: AgentAuditEvent): Long

    @Query("SELECT * FROM agent_audit_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AgentAuditEvent>>

    @Query("SELECT * FROM agent_audit_events WHERE id = :id")
    suspend fun getById(id: Long): AgentAuditEvent?

    @Query("SELECT COUNT(*) FROM agent_audit_events")
    suspend fun count(): Int

    @Query("SELECT MAX(timestamp) FROM agent_audit_events")
    suspend fun latestTimestamp(): Long?

    @Query("SELECT MAX(timestamp) FROM agent_audit_events")
    fun observeLatestTimestamp(): Flow<Long?>

    /**
     * Drop everything older than [olderThan] AND then keep only the
     * newest [keepNewest] rows. The two-step trim handles both the
     * time-based and the count-based ceiling in a single call.
     */
    @Query(
        "DELETE FROM agent_audit_events WHERE id NOT IN (" +
            "SELECT id FROM agent_audit_events " +
            "WHERE timestamp >= :olderThan " +
            "ORDER BY timestamp DESC LIMIT :keepNewest" +
        ")"
    )
    suspend fun trim(olderThan: Long, keepNewest: Int)

    @Query("DELETE FROM agent_audit_events")
    suspend fun deleteAll()
}
