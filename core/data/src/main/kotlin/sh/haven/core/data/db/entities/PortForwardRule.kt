package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "port_forward_rules",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class PortForwardRule(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val type: Type,
    val bindAddress: String = "127.0.0.1",
    val bindPort: Int,
    val targetHost: String = "localhost",
    val targetPort: Int,
    val enabled: Boolean = true,
) {
    enum class Type { LOCAL, REMOTE }
}
