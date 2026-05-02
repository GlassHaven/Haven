package sh.haven.core.tunnel

import org.json.JSONObject

/**
 * Storage envelope for Tailscale tunnel configs. Encoded into
 * [sh.haven.core.data.db.entities.TunnelConfig.configText] (which is then
 * encrypted at rest by the repository).
 *
 * The original v5.x storage format was the raw authkey bytes — useful
 * when we only spoke to controlplane.tailscale.com, but unable to carry
 * a Headscale URL alongside. v5.24.86 introduces a JSON envelope; old
 * configs are still read back via [parse]'s legacy fallback so existing
 * users don't have to re-add their tunnels.
 *
 * Format (UTF-8 JSON):
 * ```
 * { "authKey": "tskey-auth-…", "controlUrl": "https://headscale.example.com" }
 * ```
 *
 * `controlUrl` is optional; absent or empty means the default Tailscale
 * coordination server. Unknown JSON keys are ignored — adding fields
 * (tags, hostname overrides) won't break older clients reading newer
 * blobs.
 */
data class TailscaleConfigBlob(
    val authKey: String,
    val controlURL: String = "",
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            put("authKey", authKey)
            if (controlURL.isNotBlank()) put("controlUrl", controlURL)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Decode bytes from [TunnelConfig.configText]. Tries JSON first;
         * on parse failure or missing `authKey` field, treats the whole
         * blob as the legacy raw-authkey format and returns a blob with
         * an empty controlURL.
         */
        fun parse(bytes: ByteArray): TailscaleConfigBlob {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (text.startsWith("{")) {
                try {
                    val json = JSONObject(text)
                    val authKey = json.optString("authKey", "").trim()
                    if (authKey.isNotEmpty()) {
                        return TailscaleConfigBlob(
                            authKey = authKey,
                            controlURL = json.optString("controlUrl", "").trim(),
                        )
                    }
                } catch (_: Throwable) {
                    // fall through to legacy
                }
            }
            // Legacy format: bytes are the raw authkey, no control URL.
            return TailscaleConfigBlob(authKey = text)
        }
    }
}
