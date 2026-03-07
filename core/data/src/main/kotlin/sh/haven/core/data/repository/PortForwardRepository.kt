package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.PortForwardRuleDao
import sh.haven.core.data.db.entities.PortForwardRule
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PortForwardRepository @Inject constructor(
    private val dao: PortForwardRuleDao,
) {
    fun observeForProfile(profileId: String): Flow<List<PortForwardRule>> =
        dao.observeForProfile(profileId)

    suspend fun getEnabledForProfile(profileId: String): List<PortForwardRule> =
        dao.getEnabledForProfile(profileId)

    suspend fun save(rule: PortForwardRule) = dao.upsert(rule)

    suspend fun delete(id: String) = dao.deleteById(id)
}
