package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A named tunnel configuration the user manages in settings and references
 * from a [ConnectionProfile] via `tunnelConfigId`. Follows the same
 * pattern as [SshKey] — the sensitive payload ([configText], which holds
 * a WireGuard `.conf` with a private key, or a Tailscale authkey) is
 * encrypted at rest by [sh.haven.core.data.repository.TunnelConfigRepository].
 *
 * See GH #102.
 */
@Entity(tableName = "tunnel_configs")
data class TunnelConfig(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    /** String-typed on disk so Room doesn't need a custom converter. Valid
     *  values correspond to [TunnelConfigType] names. */
    val type: String,
    /** Encrypted-at-rest payload. For [TunnelConfigType.WIREGUARD] this is
     *  the raw `.conf` text; for [TunnelConfigType.TAILSCALE] (follow-up)
     *  it'll be the tsnet authkey + state JSON. */
    val configText: ByteArray,
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TunnelConfig) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

enum class TunnelConfigType {
    WIREGUARD,
    TAILSCALE,
    ;

    companion object {
        fun fromStorage(value: String): TunnelConfigType =
            entries.firstOrNull { it.name == value }
                ?: error("Unknown TunnelConfigType in DB: $value")
    }
}

/** Convenience on [TunnelConfig] so callers can read [type] as the enum. */
val TunnelConfig.typeEnum: TunnelConfigType
    get() = TunnelConfigType.fromStorage(type)
