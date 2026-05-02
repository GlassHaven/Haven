package sh.haven.core.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum
import sh.haven.core.data.repository.TunnelConfigRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a [TunnelConfig] (stored by id on a connection profile) into a
 * usable [Tunnel]. Caches live tunnels so multiple profiles sharing the
 * same config reuse a single tunnel instance.
 *
 * Dispatches by [TunnelConfigType.type]:
 *  - [TunnelConfigType.WIREGUARD] → [WireguardTunnel] (wireguard-go +
 *    gVisor netstack, bundled into libgojni.so).
 *  - [TunnelConfigType.TAILSCALE] → [TailscaleTunnel] (tsnet, same .so).
 *
 * Tailscale needs a per-config writable state directory for node keys +
 * cert cache. Created under `<filesDir>/tailscale-<configId>/` the first
 * time a given config is started and reused on subsequent starts so the
 * authkey is only consumed once.
 */
/**
 * Constructs a live [Tunnel] from a stored [TunnelConfig]. Extracted
 * behind an interface so tests can swap in fakes — production code goes
 * through the native gomobile bridges, which can't be exercised from a
 * JVM-only unit test without Robolectric.
 */
interface TunnelFactory {
    fun create(config: TunnelConfig): Tunnel
}

@Singleton
class TunnelManager @Inject constructor(
    private val tunnelConfigRepository: TunnelConfigRepository,
    private val tunnelFactory: TunnelFactory,
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
        val tunnel = tunnelFactory.create(config)
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
}

/**
 * Production [TunnelFactory] — dispatches by config type and wires up
 * per-config Tailscale state directories under the app's private files
 * dir. Hilt-provides the singleton; tests construct [TunnelManager]
 * directly with their own factory.
 */
@Singleton
class DefaultTunnelFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : TunnelFactory {
    override fun create(config: TunnelConfig): Tunnel = when (config.typeEnum) {
        TunnelConfigType.WIREGUARD -> WireguardTunnel(String(config.configText))
        TunnelConfigType.TAILSCALE -> {
            val parsed = TailscaleConfigBlob.parse(config.configText)
            TailscaleTunnel(
                authKey = parsed.authKey,
                stateDir = File(context.filesDir, "tailscale-${config.id}").also { it.mkdirs() },
                hostname = deriveHostname(config.label),
                controlURL = parsed.controlURL,
            )
        }
    }

    /**
     * Tailscale nodes appear in the tailnet admin console by hostname.
     * Derive from the config label so users can tell their entries apart;
     * sanitise to DNS-compatible characters because Tailscale enforces that.
     */
    private fun deriveHostname(label: String): String {
        val safe = label.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
        return if (safe.isBlank()) "haven-android" else "haven-$safe"
    }
}
