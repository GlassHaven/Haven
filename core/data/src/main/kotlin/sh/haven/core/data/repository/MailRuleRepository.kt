package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.MailRuleDao
import sh.haven.core.data.db.MailRuleFiringDao
import sh.haven.core.data.db.MailRulePendingActionDao
import sh.haven.core.data.db.MailWatermarkDao
import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.db.entities.MailRuleFiring
import sh.haven.core.data.db.entities.MailRulePendingAction
import sh.haven.core.data.db.entities.MailWatermark
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates the four Mail-Rules DAOs (rules, watermarks, firing log, pending actions)
 * behind one injectable surface for the engine, MCP tools, and the UI.
 */
@Singleton
class MailRuleRepository @Inject constructor(
    private val ruleDao: MailRuleDao,
    private val watermarkDao: MailWatermarkDao,
    private val firingDao: MailRuleFiringDao,
    private val pendingDao: MailRulePendingActionDao,
) {
    companion object {
        /** Firing-log retention, matching the agent-audit recorder's soft cap. */
        const val FIRING_LOG_KEEP = 500
    }

    // rules
    fun observeRules(): Flow<List<MailRule>> = ruleDao.observeAll()
    suspend fun getAllRules(): List<MailRule> = ruleDao.getAll()
    suspend fun enabledForAccount(profileId: String): List<MailRule> = ruleDao.enabledForAccount(profileId)
    suspend fun allEnabled(): List<MailRule> = ruleDao.allEnabled()
    suspend fun getRule(id: String): MailRule? = ruleDao.getById(id)
    suspend fun enabledRuleCount(): Int = ruleDao.enabledCount()
    suspend fun saveRule(rule: MailRule) = ruleDao.upsert(rule)
    suspend fun touchLastFired(id: String, timestamp: Long = System.currentTimeMillis()) =
        ruleDao.touchLastFired(id, timestamp)
    suspend fun deleteRule(id: String) = ruleDao.deleteById(id)

    // watermarks
    suspend fun getWatermark(profileId: String, folderId: String): MailWatermark? =
        watermarkDao.get(profileId, folderId)
    suspend fun saveWatermark(watermark: MailWatermark) = watermarkDao.upsert(watermark)

    // firings
    suspend fun getFiring(ruleId: String, profileId: String, folderId: String, uid: Long): MailRuleFiring? =
        firingDao.getFiring(ruleId, profileId, folderId, uid)
    suspend fun insertFiring(firing: MailRuleFiring): Long = firingDao.insert(firing)
    suspend fun updateFiring(firing: MailRuleFiring) = firingDao.update(firing)
    fun observeRecentFirings(limit: Int = 100): Flow<List<MailRuleFiring>> = firingDao.observeRecent(limit)
    suspend fun recentFirings(limit: Int = 100): List<MailRuleFiring> = firingDao.recent(limit)
    suspend fun trimFiringLog() = firingDao.trimTo(FIRING_LOG_KEEP)

    // pending (queued destructive actions awaiting foreground approval)
    fun observePendingActions(): Flow<List<MailRulePendingAction>> = pendingDao.observeAll()
    suspend fun pendingActions(): List<MailRulePendingAction> = pendingDao.all()
    suspend fun queuePendingAction(action: MailRulePendingAction) = pendingDao.insert(action)
    suspend fun deletePendingAction(id: String) = pendingDao.deleteById(id)
}
