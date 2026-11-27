package sh.haven.core.tunnel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage envelope for Yggdrasil tunnel configs. Encoded into
 * [sh.haven.core.data.db.entities.TunnelConfig.configText] (encrypted at rest
 * by the repository). [encode] produces the yggdrasil NodeConfig JSON that
 * `yggbridge.StartTunnel` consumes (PascalCase keys, as yggdrasil's config
 * struct expects); [parse] reads it back into structured fields for the editor.
 *
 * The node identity is the [privateKey] (hex ed25519). An empty key makes
 * yggdrasil generate a fresh identity on each start, so the mesh address would
 * change across restarts — the editor generates and persists a key once on
 * create to keep the 200::/7 address stable. [peers] are outbound underlay
 * connection strings (e.g. `tls://host:port`); [listen] accepts inbound
 * peerings. [underlayTunnelId] optionally names another saved Haven tunnel
 * whose loopback SOCKS5 listener carries TCP/TLS peer links when the phone is
 * off-LAN (#876).
 *
 * Unknown JSON keys are ignored — adding fields later won't break older
 * clients reading newer blobs.
 */
data class YggdrasilConfigBlob(
    val privateKey: String = "",
    val peers: List<String> = emptyList(),
    val listen: List<String> = emptyList(),
    val ifMTU: Int = 0,
    val groupPassword: String = "",
    val underlayTunnelId: String = "",
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            if (privateKey.isNotBlank()) put("PrivateKey", privateKey)
            put("Peers", JSONArray(peers))
            put("Listen", JSONArray(listen))
            if (ifMTU > 0) put("IfMTU", ifMTU)
            if (groupPassword.isNotBlank()) put("GroupPassword", groupPassword)
            if (underlayTunnelId.isNotBlank()) put(KEY_UNDERLAY_TUNNEL_ID, underlayTunnelId)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Return a copy with `tcp://` and `tls://` [peers] rewritten to `socks://`
     * and `sockstls://` through `socksHost:socksPort` (#876). Non-TCP peer
     * schemes (`quic://`, `ws://`, `wss://`, already-proxied `socks*://`) are
     * preserved unchanged.
     */
    fun withCarrierSocks(socksHost: String, socksPort: Int): YggdrasilConfigBlob =
        copy(peers = peers.map { rewriteYggdrasilPeerForSocks(it, socksHost, socksPort) })

    companion object {
        private const val KEY_UNDERLAY_TUNNEL_ID = "havenUnderlayTunnelId"
        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val HAS_SNI_PARAM = Regex("""(^|&)sni=""", RegexOption.IGNORE_CASE)

        /**
         * Decode bytes from [TunnelConfig.configText]. Returns null only on
         * malformed JSON (empty/whitespace/non-object), so the caller can
         * surface a clear error. A valid object with an empty PrivateKey is
         * accepted — yggdrasil generates an identity (the editor persists a
         * key for address stability).
         */
        fun parse(bytes: ByteArray): YggdrasilConfigBlob? {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (!text.startsWith("{")) return null
            return try {
                val json = JSONObject(text)
                YggdrasilConfigBlob(
                    privateKey = json.optString("PrivateKey", "").trim(),
                    peers = json.optJSONArray("Peers").toStringList(),
                    listen = json.optJSONArray("Listen").toStringList(),
                    ifMTU = json.optInt("IfMTU", 0),
                    groupPassword = json.optString("GroupPassword", "").trim(),
                    underlayTunnelId = json.optString(KEY_UNDERLAY_TUNNEL_ID, "").trim(),
                )
            } catch (_: Throwable) {
                null
            }
        }

        internal fun rewriteYggdrasilPeerForSocks(
            peerUri: String,
            socksHost: String,
            socksPort: Int,
        ): String {
            val trimmed = peerUri.trim()
            val schemeSep = trimmed.indexOf("://")
            if (schemeSep <= 0) return trimmed
            val scheme = trimmed.substring(0, schemeSep).lowercase()
            val newScheme = when (scheme) {
                "tcp" -> "socks"
                "tls" -> "sockstls"
                else -> return trimmed
            }
            val rest = trimmed.substring(schemeSep + 3)
            val queryIdx = rest.indexOf('?')
            val authority = (if (queryIdx >= 0) rest.substring(0, queryIdx) else rest).trimEnd('/')
            val rawQuery = if (queryIdx >= 0) rest.substring(queryIdx + 1) else ""
            if (authority.isEmpty()) return trimmed

            val finalQuery = if (newScheme == "sockstls") {
                // yggdrasil-go's link_socks.go sets TLS ServerName to url.Hostname()
                // (the SOCKS proxy host) unless ?sni= is supplied; inject sni=<peerHost>
                // when the peer target is a DNS hostname and no explicit sni= is set.
                val peerHost = extractAuthorityHost(authority)
                if (peerHost.isNotEmpty() && !isIpLiteral(peerHost) && !HAS_SNI_PARAM.containsMatchIn(rawQuery)) {
                    if (rawQuery.isEmpty()) "sni=$peerHost" else "$rawQuery&sni=$peerHost"
                } else {
                    rawQuery
                }
            } else {
                rawQuery
            }

            val proxyHost = if (socksHost.contains(':') && !socksHost.startsWith("[")) "[$socksHost]" else socksHost
            return buildString {
                append(newScheme)
                append("://")
                append(proxyHost)
                append(':')
                append(socksPort)
                append('/')
                append(authority)
                if (finalQuery.isNotEmpty()) {
                    append('?')
                    append(finalQuery)
                }
            }
        }

        private fun extractAuthorityHost(authority: String): String {
            val withoutUser = authority.substringAfterLast('@')
            if (withoutUser.startsWith("[")) {
                val close = withoutUser.indexOf(']')
                return if (close > 1) withoutUser.substring(1, close) else withoutUser
            }
            val colon = withoutUser.lastIndexOf(':')
            return if (colon > 0) withoutUser.substring(0, colon) else withoutUser
        }

        private fun isIpLiteral(host: String): Boolean =
            host.contains(':') || IPV4_LITERAL.matches(host)

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { optString(it, "") }.filter { it.isNotBlank() }
        }
    }
}
