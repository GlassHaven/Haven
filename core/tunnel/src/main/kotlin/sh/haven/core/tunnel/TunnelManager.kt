package sh.haven.core.tunnel

import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum
import sh.haven.core.data.repository.TunnelConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a [TunnelConfig] (stored by id on a connection profile) into a
 * usable [Tunnel]. Caches live tunnels so multiple profiles sharing the
 * same config reuse a single tunnel instance.
 *
 * Dispatches by [TunnelConfigType.type]:
 *  - [TunnelConfigType.WIREGUARD] → [WireguardTunnel] (native, follow-up).
 *  - [TunnelConfigType.TAILSCALE] → [TailscaleTunnel] (follow-up PR).
 *
 * Initial implementation returns a stub that throws on `dial` so the
 * plumbing can ship under test before the native bits land.
 */
@Singleton
class TunnelManager @Inject constructor(
    private val tunnelConfigRepository: TunnelConfigRepository,
) {

    private val liveTunnels = mutableMapOf<String, Tunnel>()

    /**
     * Get or create a [Tunnel] for the config with the given id. Returns
     * null if the config doesn't exist — callers should treat that as
     * "profile referenced a deleted config, fall through to direct".
     */
    suspend fun getTunnel(configId: String): Tunnel? {
        liveTunnels[configId]?.let { return it }
        val config = tunnelConfigRepository.getById(configId) ?: return null
        val tunnel = startTunnel(config)
        liveTunnels[configId] = tunnel
        return tunnel
    }

    /**
     * Release a previously-acquired tunnel. With the current single-user
     * model this closes immediately; reference-counting is a follow-up if
     * we share tunnels across concurrent connections.
     */
    fun release(configId: String) {
        liveTunnels.remove(configId)?.close()
    }

    private fun startTunnel(config: TunnelConfig): Tunnel = when (config.typeEnum) {
        TunnelConfigType.WIREGUARD -> NotImplementedTunnel(
            "WireGuard tunnel backend not yet wired up — see #102",
        )
        TunnelConfigType.TAILSCALE -> NotImplementedTunnel(
            "Tailscale tunnel backend not yet implemented — follow-up to #102",
        )
    }
}

/**
 * Placeholder [Tunnel] that always throws. Lets us ship the data-model +
 * VM wiring under test before the native wireguard-go + netstack code
 * lands. Replaced by `WireguardTunnel` in the native PR.
 */
internal class NotImplementedTunnel(private val reason: String) : Tunnel {
    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection =
        throw UnsupportedOperationException(reason)

    override fun close() { /* no-op */ }
}
