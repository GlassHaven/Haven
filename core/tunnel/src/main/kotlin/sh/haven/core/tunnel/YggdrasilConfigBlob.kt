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
 * peerings. Multicast LAN discovery is not configured here yet (manual peers).
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
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            if (privateKey.isNotBlank()) put("PrivateKey", privateKey)
            put("Peers", JSONArray(peers))
            put("Listen", JSONArray(listen))
            if (ifMTU > 0) put("IfMTU", ifMTU)
            if (groupPassword.isNotBlank()) put("GroupPassword", groupPassword)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
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
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { optString(it, "") }.filter { it.isNotBlank() }
        }
    }
}