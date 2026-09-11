package sh.haven.core.tunnel

import org.json.JSONObject

/**
 * Storage envelope for NetBird tunnel configs. Encoded into
 * [sh.haven.core.data.db.entities.TunnelConfig.configText] (which is then
 * encrypted at rest by the repository).
 *
 * Format (UTF-8 JSON):
 * ```
 * { "setupKey": "…", "managementUrl": "https://netbird.example.com" }
 * ```
 *
 * Auth is a setup key, not Tailscale's authkey — confirmed with the
 * reporter on #492 that setup key (not SSO/JWT) is the mode to support.
 *
 * `managementUrl` is optional; absent or empty means NetBird's hosted
 * default, so self-hosted and hosted networks are the same code path.
 * Unknown JSON keys are ignored — adding fields later won't break older
 * clients reading newer blobs.
 *
 * There is no legacy fallback: Tailscale's raw-authkey bytes have a
 * pre-JSON generation of users to keep reading, this format starts with
 * the envelope.
 */
data class NetbirdConfigBlob(
    val setupKey: String,
    val managementURL: String = "",
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            put("setupKey", setupKey)
            if (managementURL.isNotBlank()) put("managementUrl", managementURL)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Decode bytes from [TunnelConfig.configText]. Returns null when
         * the blob carries no usable setup key (empty config, whitespace,
         * or JSON without a non-blank `setupKey`), so the caller can
         * surface a clear error rather than a 60-second native start that
         * fails on the setup key.
         */
        fun parse(bytes: ByteArray): NetbirdConfigBlob? {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (!text.startsWith("{")) return null
            return try {
                val json = JSONObject(text)
                val setupKey = json.optString("setupKey", "").trim()
                if (setupKey.isEmpty()) null
                else NetbirdConfigBlob(
                    setupKey = setupKey,
                    managementURL = json.optString("managementUrl", "").trim(),
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}