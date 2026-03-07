package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.PortForwardRule

@Dao
interface PortForwardRuleDao {

    @Query("SELECT * FROM port_forward_rules WHERE profileId = :profileId")
    fun observeForProfile(profileId: String): Flow<List<PortForwardRule>>

    @Query("SELECT * FROM port_forward_rules WHERE profileId = :profileId AND enabled = 1")
    suspend fun getEnabledForProfile(profileId: String): List<PortForwardRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: PortForwardRule)

    @Query("DELETE FROM port_forward_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}
