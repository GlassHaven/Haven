package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.db.entities.MailRuleFiring
import sh.haven.core.data.db.entities.MailRulePendingAction
import sh.haven.core.data.db.entities.MailWatermark

@Dao
interface MailRuleDao {
    @Query("SELECT * FROM mail_rules ORDER BY orderIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<MailRule>>

    @Query("SELECT * FROM mail_rules ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun getAll(): List<MailRule>

    /** Enabled rules in evaluation order for a given account (its own rules + ANY-account rules). */
    @Query(
        "SELECT * FROM mail_rules WHERE enabled = 1 AND (accountProfileId IS NULL OR accountProfileId = :profileId) " +
            "ORDER BY orderIndex ASC, createdAt ASC",
    )
    suspend fun enabledForAccount(profileId: String): List<MailRule>

    @Query("SELECT * FROM mail_rules WHERE enabled = 1")
    suspend fun allEnabled(): List<MailRule>

    @Query("SELECT * FROM mail_rules WHERE id = :id")
    suspend fun getById(id: String): MailRule?

    @Query("SELECT COUNT(*) FROM mail_rules WHERE enabled = 1")
    suspend fun enabledCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MailRule)

    @Query("UPDATE mail_rules SET lastFiredAt = :timestamp WHERE id = :id")
    suspend fun touchLastFired(id: String, timestamp: Long)

    @Query("DELETE FROM mail_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MailWatermarkDao {
    @Query("SELECT * FROM mail_watermarks WHERE profileId = :profileId AND folderId = :folderId")
    suspend fun get(profileId: String, folderId: String): MailWatermark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(watermark: MailWatermark)

    @Query("DELETE FROM mail_watermarks WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)
}

@Dao
interface MailRuleFiringDao {
    @Insert
    suspend fun insert(firing: MailRuleFiring): Long

    @Update
    suspend fun update(firing: MailRuleFiring)

    /** The crash-safety belt: the existing firing row for this (rule, message), if any. */
    @Query(
        "SELECT * FROM mail_rule_firings WHERE ruleId = :ruleId AND profileId = :profileId " +
            "AND folderId = :folderId AND uid = :uid LIMIT 1",
    )
    suspend fun getFiring(ruleId: String, profileId: String, folderId: String, uid: Long): MailRuleFiring?

    @Query("SELECT * FROM mail_rule_firings ORDER BY firedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MailRuleFiring>>

    @Query("SELECT * FROM mail_rule_firings ORDER BY firedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MailRuleFiring>

    /** Trim to the most-recent [keep] rows (mirrors the agent-audit retention cap). */
    @Query(
        "DELETE FROM mail_rule_firings WHERE id NOT IN " +
            "(SELECT id FROM mail_rule_firings ORDER BY firedAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}

@Dao
interface MailRulePendingActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: MailRulePendingAction)

    @Query("SELECT * FROM mail_rule_pending_actions ORDER BY queuedAt ASC")
    fun observeAll(): Flow<List<MailRulePendingAction>>

    @Query("SELECT * FROM mail_rule_pending_actions ORDER BY queuedAt ASC")
    suspend fun all(): List<MailRulePendingAction>

    @Query("DELETE FROM mail_rule_pending_actions WHERE id = :id")
    suspend fun deleteById(id: String)
}
