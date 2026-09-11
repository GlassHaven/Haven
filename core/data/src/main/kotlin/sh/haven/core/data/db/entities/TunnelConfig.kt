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
     *  the raw `.conf` text; for [TunnelConfigType.TAILSCALE] it's the
     *  tsnet authkey + control-URL JSON; for [TunnelConfigType.CLOUDFLARE_ACCESS]
     *  it's the Access hostname + cached IdP JWT JSON; for
     *  [TunnelConfigType.NETBIRD] it's the setup key + management URL JSON. */
    val configText: ByteArray,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * If non-null, this tunnel is owned by a single [ConnectionProfile] and
     * is hidden from the standalone Tunnels list — the user manages it via
     * the SSH profile editor as a transport, not as a stand-alone tunnel.
     * Deleted with the owning profile. See GH #154.
     */
    val ownerProfileId: String? = null,
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

    /** Cloudflare Tunnel published hostname. Per-hostname proxy that
     *  wraps SSH bytes in a WebSocket to `wss://<hostname>/` (binary
     *  frames carry raw TCP). Optional `Cf-Access-Token` header carries
     *  a JWT for Access-protected routes; without it, the route must be
     *  unprotected. The wire protocol is verified against cloudflared's
     *  `carrier/websocket.go` rather than guessed. Surfaced in the UI
     *  as "Cloudflare Tunnel". See GH #154. */
    CLOUDFLARE_ACCESS,

    /** NetBird via its client/embed userspace netstack, gomobile-bound as
     *  nbbridge. Setup-key auth, optional self-hosted management URL;
     *  per-config peer identity in filesDir/netbird-<id>. No VpnService
     *  and no second app. See GH #492. */
    NETBIRD,
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
