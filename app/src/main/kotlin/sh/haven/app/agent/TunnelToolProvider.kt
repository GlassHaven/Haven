package sh.haven.app.agent

import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.tunnel.TunnelManager

/**
 * The saved-tunnel MCP tools (#mcp-backbone Stage 5, Layer E): CRUD over
 * WireGuard / Tailscale / Cloudflare-Access tunnel configs used for
 * Route-through on connection profiles, plus the live-tunnel refcount
 * snapshot (#149). A self-contained domain over [TunnelConfigRepository] and
 * [TunnelManager] with no shared McpTools helpers. `set_profile_routing`
 * stays in McpTools: it edits a connection profile (uses the shared
 * profileLabel/connection helpers), not a tunnel config. Encrypted config
 * blobs (wg-quick payload, Tailscale authkey, Access JWT) never cross the
 * wire; only id/label/type/createdAt are returned.
 */
internal class TunnelToolProvider(
    private val tunnelConfigRepository: TunnelConfigRepository,
    private val tunnelManager: TunnelManager,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "list_tunnels" to ToolHandler(
            description = "List saved WireGuard / Tailscale tunnel configs available for Route-through on connection profiles. Returns id, label, type (WIREGUARD or TAILSCALE), and createdAt for each. The encrypted configText (wg-quick payload or Tailscale authkey blob) is NOT returned.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listTunnels() },

        "list_live_tunnels" to ToolHandler(
            description = "Return the live-tunnel snapshot from TunnelManager — every tunnel currently up, paired with the set of profile ids holding it. Useful for verifying refcount semantics in #149 integration tests: confirm the tunnel stays open while a sibling transport keeps it acquired, and that it tears down on the last release.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listLiveTunnels() },

        "create_tunnel" to ToolHandler(
            description = "Add a new WireGuard, Tailscale, NetBird, or Cloudflare Tunnel config. WIREGUARD: pass `configText` (wg-quick INI body). TAILSCALE: pass `tailscaleAuthKey` (and optional `tailscaleControlUrl` for Headscale). NETBIRD: pass `netbirdSetupKey` (and optional `netbirdManagementUrl` for a self-hosted management server). CLOUDFLARE_ACCESS: pass `accessHostname`; for Access-protected routes also pass `accessJwt` (from `cloudflared access token --app https://<host>`); optional `accessJumpDestination` for bastion-mode multi-target tunnels. Returns the new tunnel id, which can then be passed to set_profile_routing.",
            inputSchema = objectSchema {
                string("label", "User-facing label (also used to derive the tunnel hostname).", required = true)
                string("type", "WIREGUARD, TAILSCALE, NETBIRD, or CLOUDFLARE_ACCESS.", required = true)
                string("configText", "WireGuard wg-quick INI body. Required when type=WIREGUARD.")
                string("tailscaleAuthKey", "Tailscale single-use authkey (tskey-auth-...). Required when type=TAILSCALE.")
                string("tailscaleControlUrl", "Self-hosted Headscale coordination URL. Optional — empty defaults to controlplane.tailscale.com.")
                string("netbirdSetupKey", "NetBird setup key from the NetBird dashboard. Required when type=NETBIRD.")
                string("netbirdManagementUrl", "Self-hosted NetBird management URL (https://...). Optional — empty defaults to the hosted management.")
                string("accessHostname", "Cloudflare Tunnel published hostname (e.g. ssh.example.com). Required when type=CLOUDFLARE_ACCESS.")
                string("accessJwt", "Cloudflare Access JWT (`CF_Authorization` value). Optional — only needed when the Tunnel route is Access-protected.")
                string("accessTeamDomain", "Cloudflare Access team domain (myteam.cloudflareaccess.com). Optional; only meaningful for Access-protected routes.")
                integer("accessExpiresAt", "Optional explicit JWT expiry (Unix epoch seconds). Defaults to parsing the `exp` claim out of accessJwt.")
                string("accessJumpDestination", "Optional `Cf-Access-Jump-Destination` value for bastion-mode multi-target tunnels (e.g. internal-host:22).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val type = args.optString("type")
                val label = args.optString("label", "(unnamed)")
                "Add $type tunnel \"$label\" to the keystore?"
            },
        ) { args -> createTunnel(args) },

        "delete_tunnel" to ToolHandler(
            description = "Delete a saved tunnel config by id. Profiles that referenced it via tunnelConfigId will fall through to direct dialling on next connect.",
            inputSchema = objectSchema {
                string("tunnelConfigId", "Tunnel id from list_tunnels.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Delete tunnel ${args.optString("tunnelConfigId").take(8)}…?" },
        ) { args -> deleteTunnel(args) },
    )

    private suspend fun listTunnels(): JSONObject {
        val tunnels = tunnelConfigRepository.getAll()
        val arr = JSONArray()
        for (t in tunnels) {
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("label", t.label)
                put("type", t.type)
                put("createdAt", t.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", tunnels.size)
            put("tunnels", arr)
        }
    }

    private suspend fun listLiveTunnels(): JSONObject {
        val live = tunnelManager.liveSnapshot()
        val arr = JSONArray()
        for (entry in live) {
            arr.put(JSONObject().apply {
                put("configId", entry.configId)
                put("dependentCount", entry.dependentProfileIds.size)
                put("dependentProfileIds", JSONArray().apply {
                    entry.dependentProfileIds.forEach { put(it) }
                })
            })
        }
        return JSONObject().apply {
            put("count", live.size)
            put("liveTunnels", arr)
        }
    }

    private suspend fun createTunnel(args: JSONObject): JSONObject {
        val label = args.optString("label").ifBlank { throw IllegalArgumentException("label required") }
        val typeRaw = args.optString("type").uppercase()
        val type = when (typeRaw) {
            "WIREGUARD" -> TunnelConfigType.WIREGUARD
            "TAILSCALE" -> TunnelConfigType.TAILSCALE
            "NETBIRD" -> TunnelConfigType.NETBIRD
            "CLOUDFLARE_ACCESS" -> TunnelConfigType.CLOUDFLARE_ACCESS
            else -> throw IllegalArgumentException(
                "type must be WIREGUARD, TAILSCALE, NETBIRD, or CLOUDFLARE_ACCESS"
            )
        }
        val configBytes: ByteArray = when (type) {
            TunnelConfigType.WIREGUARD -> {
                val wgQuick = args.optString("configText")
                if (wgQuick.isBlank()) {
                    throw IllegalArgumentException("configText required for WIREGUARD type")
                }
                wgQuick.toByteArray()
            }
            TunnelConfigType.CLOUDFLARE_ACCESS -> {
                // MCP path supports unprotected Cloudflare Tunnel routes
                // (hostname only) as well as Access-protected ones
                // (additionally requiring a JWT). The in-app WebView
                // sign-in flow is interactive and can't be driven by an
                // agent — agents wanting Access auth must already hold
                // a JWT (e.g. from `cloudflared access token --app <host>`).
                val hostname = args.optString("accessHostname")
                if (hostname.isBlank()) {
                    throw IllegalArgumentException(
                        "accessHostname required for CLOUDFLARE_ACCESS type"
                    )
                }
                val jwt = args.optString("accessJwt")
                val teamDomain = args.optString("accessTeamDomain")
                val jumpDestination = args.optString("accessJumpDestination")
                val explicitExpiry = args.optLong("accessExpiresAt", 0L)
                val derivedExpiry = if (explicitExpiry > 0) {
                    explicitExpiry
                } else if (jwt.isNotBlank()) {
                    sh.haven.core.security.JwtPayload.parse(jwt)
                        ?.expiresAtSeconds ?: 0L
                } else 0L
                sh.haven.core.tunnel.CloudflareAccessConfigBlob(
                    hostname = hostname,
                    teamDomain = teamDomain,
                    jwt = jwt,
                    jwtExpiresAt = derivedExpiry,
                    jumpDestination = jumpDestination,
                ).encode()
            }
            TunnelConfigType.TAILSCALE -> {
                val authKey = args.optString("tailscaleAuthKey")
                if (authKey.isBlank()) {
                    throw IllegalArgumentException("tailscaleAuthKey required for TAILSCALE type")
                }
                val controlUrl = args.optString("tailscaleControlUrl")
                sh.haven.core.tunnel.TailscaleConfigBlob(authKey, controlUrl).encode()
            }
            TunnelConfigType.NETBIRD -> {
                val setupKey = args.optString("netbirdSetupKey")
                if (setupKey.isBlank()) {
                    throw IllegalArgumentException("netbirdSetupKey required for NETBIRD type")
                }
                val managementUrl = args.optString("netbirdManagementUrl")
                sh.haven.core.tunnel.NetbirdConfigBlob(setupKey, managementUrl).encode()
            }
        }
        val config = TunnelConfig(
            label = label,
            type = type.name,
            configText = configBytes,
        )
        tunnelConfigRepository.save(config)
        return JSONObject().apply {
            put("id", config.id)
            put("label", config.label)
            put("type", config.type)
            put("createdAt", config.createdAt)
        }
    }

    private suspend fun deleteTunnel(args: JSONObject): JSONObject {
        val id = args.optString("tunnelConfigId").ifBlank {
            throw IllegalArgumentException("tunnelConfigId required")
        }
        val existing = tunnelConfigRepository.getById(id)
            ?: return JSONObject().apply { put("deleted", false); put("reason", "not found") }
        tunnelConfigRepository.delete(id)
        // If a live tunnel depends on this config, ask TunnelManager to
        // release every dependent — they fall through to direct on next
        // connect. (The current TunnelManager API is profileId-keyed so
        // the simplest correct thing is to release each dependent.)
        tunnelManager.liveSnapshot()
            .firstOrNull { it.configId == id }
            ?.dependentProfileIds
            ?.forEach { tunnelManager.release(it) }
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }
}
