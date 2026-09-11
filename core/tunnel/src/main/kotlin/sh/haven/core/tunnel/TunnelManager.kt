package sh.haven.core.tunnel

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum
import sh.haven.core.data.repository.TunnelConfigRepository
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TunnelManager"

/**
 * Constructs a live [Tunnel] from a stored [TunnelConfig]. Extracted
 * behind an interface so tests can swap in fakes — production code goes
 * through the native gomobile bridges, which can't be exercised from a
 * JVM-only unit test without Robolectric.
 */
interface TunnelFactory {
    fun create(config: TunnelConfig): Tunnel
}

/**
 * Resolves a [TunnelConfig] (stored by id on a connection profile) into a
 * usable [Tunnel] and reference-counts the result so multiple profiles
 * sharing the same config share a single live tunnel handle.
 *
 * **Lifecycle:**
 *  - [acquire] gets-or-creates the [Tunnel] for the given config and
 *    records `profileId` as a dependent. Idempotent for the same
 *    `(configId, profileId)` pair.
 *  - [release] removes a dependent. The tunnel is torn down only when
 *    its last dependent releases. Releasing a profile that never
 *    acquired is a no-op.
 *  - If a profile re-acquires with a *different* config without
 *    releasing first, the prior slot is auto-released. Useful for the
 *    edge case of a user editing `tunnelConfigId` on a live profile.
 *
 * **Why this matters:** WireGuard handshakes take 1–2 s and Tailscale's
 * tsnet startup blocks up to 30 s. Without refcounting, a transport
 * tearing down its connection would close the handle out from under any
 * sibling transport sharing the same config (e.g. SSH + VNC in a
 * workspace profile both routed through one WireGuard tunnel).
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
@Singleton
class TunnelManager @Inject constructor(
    private val tunnelConfigRepository: TunnelConfigRepository,
    private val tunnelFactory: TunnelFactory,
) {
    private val mutex = Mutex()
    private val liveTunnels = mutableMapOf<String, Tunnel>()
    /** configId → set of profileIds currently depending on this tunnel. */
    private val dependents = mutableMapOf<String, MutableSet<String>>()
    /** profileId → the configId it last acquired. One tunnel per profile, mirroring the data model. */
    private val acquiredBy = mutableMapOf<String, String>()

    /**
     * Get-or-create the tunnel for [configId] and register [profileId] as
     * a dependent. Returns null if the config was deleted from storage —
     * callers should treat that as "fall through to direct dialling".
     *
     * Idempotent: re-acquiring with the same (configId, profileId) pair
     * returns the same tunnel and leaves the dependent set unchanged.
     *
     * If [profileId] previously acquired a *different* config, that prior
     * slot is auto-released first (and its tunnel torn down if it had no
     * other dependents). Profiles only ever hold one tunnel at a time.
     */
    suspend fun acquire(configId: String, profileId: String): Tunnel? {
        var staleToClose: Tunnel? = null
        val acquired = mutex.withLock {
            val previous = acquiredBy[profileId]
            if (previous != null && previous != configId) {
                Log.w(TAG, "Profile $profileId switching tunnel $previous → $configId without explicit release; auto-releasing prior")
                staleToClose = internalRelease(previous, profileId)
            }
            val tunnel = liveTunnels[configId] ?: run {
                val config = tunnelConfigRepository.getById(configId) ?: return@withLock null
                tunnelFactory.create(config).also { liveTunnels[configId] = it }
            }
            dependents.getOrPut(configId) { mutableSetOf() }.add(profileId)
            acquiredBy[profileId] = configId
            tunnel
        }
        staleToClose?.close()
        return acquired
    }

    /**
     * Release whatever tunnel [profileId] currently holds. Tears down the
     * underlying tunnel only if this was its last dependent. No-op if the
     * profile has no live acquire.
     *
     * The actual tunnel close happens *outside* the manager's lock — close
     * may make a native call (gomobile) and we don't want to hold the lock
     * for that.
     */
    suspend fun release(profileId: String) {
        val toClose = mutex.withLock {
            val configId = acquiredBy[profileId] ?: return@withLock null
            internalRelease(configId, profileId)
        }
        toClose?.close()
    }

    /**
     * Caller must hold [mutex]. Returns the [Tunnel] that should be closed
     * outside the lock if this drained the dependent set, or null otherwise.
     */
    private fun internalRelease(configId: String, profileId: String): Tunnel? {
        acquiredBy.remove(profileId)
        val deps = dependents[configId] ?: return null
        deps.remove(profileId)
        if (deps.isEmpty()) {
            dependents.remove(configId)
            return liveTunnels.remove(configId)
        }
        return null
    }

    /**
     * Register [profileId] as a dependent of whichever WireGuard tunnel is
     * currently live (the first, if several) and return it — or null if no
     * WireGuard tunnel is up. Used by the MCP server (#176) to bind its
     * endpoint listener on an already-active WG tunnel and keep that tunnel
     * alive (via the refcount) for as long as MCP is bound, even after the
     * profile that originally brought it up disconnects. Release with the
     * same [profileId] via [release].
     */
    suspend fun acquireFirstWireguard(profileId: String): Tunnel? = mutex.withLock {
        val entry = liveTunnels.entries.firstOrNull { it.value is WireguardTunnel }
            ?: return@withLock null
        val configId = entry.key
        // A synthetic MCP profileId only ever holds this one tunnel, so the
        // acquiredBy bookkeeping mirrors the normal acquire path.
        dependents.getOrPut(configId) { mutableSetOf() }.add(profileId)
        acquiredBy[profileId] = configId
        entry.value
    }

    /** Test / introspection — number of live dependents on the given config. */
    suspend fun dependentCount(configId: String): Int = mutex.withLock {
        dependents[configId]?.size ?: 0
    }

    /**
     * True iff at least one WireGuard tunnel is currently live. The MCP
     * reverse-tunnel manager gates on this to stand its (redundant, fragile)
     * SSH `-R` down while WireGuard is exposing the MCP endpoint directly, and
     * to revive the `-R` fallback if the WG tunnel later drops.
     */
    suspend fun hasLiveWireguard(): Boolean = mutex.withLock {
        liveTunnels.values.any { it is WireguardTunnel }
    }

    /**
     * Snapshot of the live tunnel state — every configId that currently
     * has at least one active dependent, paired with the set of profile
     * ids holding it. For agent / debug tooling that wants to verify
     * refcount semantics end-to-end (#149 integration tests).
     */
    suspend fun liveSnapshot(): List<LiveTunnelEntry> = mutex.withLock {
        dependents
            .filterValues { it.isNotEmpty() }
            .map { (configId, deps) -> LiveTunnelEntry(configId, deps.toSet()) }
    }
}

/** A live tunnel as visible to introspection: the config it came from, and the profiles depending on it. */
data class LiveTunnelEntry(
    val configId: String,
    val dependentProfileIds: Set<String>,
)

/**
 * Production [TunnelFactory] — dispatches by config type and wires up
 * per-config Tailscale state directories under the app's private files
 * dir. Hilt-provides the singleton; tests construct [TunnelManager]
 * directly with their own factory.
 */
@Singleton
class DefaultTunnelFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
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
        TunnelConfigType.CLOUDFLARE_ACCESS -> {
            val parsed = CloudflareAccessConfigBlob.parse(config.configText)
            CloudflareAccessTunnel(
                hostname = parsed.hostname,
                jwt = parsed.jwt,
                jumpDestination = parsed.jumpDestination,
                httpClient = httpClient,
            )
        }
        TunnelConfigType.NETBIRD -> {
            val parsed = NetbirdConfigBlob.parse(config.configText)
                ?: throw IOException(
                    "NetBird tunnel '${config.label}' has no setup key — re-add the tunnel",
                )
            NetbirdTunnel(
                setupKey = parsed.setupKey,
                stateDir = File(context.filesDir, "netbird-${config.id}").also { it.mkdirs() },
                hostname = deriveHostname(config.label),
                managementURL = parsed.managementURL,
            )
        }
    }

    /**
     * Tailscale nodes appear in the tailnet admin console by hostname
     * (NetBird peers likewise), so derive from the config label to let
     * users tell entries apart; sanitise to DNS-compatible characters
     * because Tailscale enforces that and it is a sensible default
     * everywhere else.
     */
    private fun deriveHostname(label: String): String {
        val safe = label.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
        return if (safe.isBlank()) "haven-android" else "haven-$safe"
    }
}
