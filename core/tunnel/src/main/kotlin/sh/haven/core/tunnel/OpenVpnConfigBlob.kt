package sh.haven.core.tunnel

import org.json.JSONObject

/**
 * Storage envelope for OpenVPN tunnel configs. Encoded into
 * [sh.haven.core.data.db.entities.TunnelConfig.configText] (which is then
 * encrypted at rest by the repository).
 *
 * Format (UTF-8 JSON):
 * ```
 * { "ovpnConfigText": "client\ndev tun\n…", "username": "…", "password": "…" }
 * ```
 *
 * `username`/`password` are optional — most `.ovpn` profiles carry a
 * client certificate and need neither; when the profile has `auth-user-pass`
 * without an inline credentials file, OpenVPN3 Core wants both supplied
 * up front rather than prompted for mid-handshake. Unknown JSON keys are
 * ignored — adding fields later won't break older clients reading newer
 * blobs.
 *
 * There is no legacy fallback: this is a new [sh.haven.core.data.db.entities.TunnelConfigType],
 * so the envelope is the only format it has ever had.
 */
data class OpenVpnConfigBlob(
    val ovpnConfigText: String,
    val username: String = "",
    val password: String = "",
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            put("ovpnConfigText", ovpnConfigText)
            if (username.isNotBlank()) put("username", username)
            if (password.isNotEmpty()) put("password", password)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Decode bytes from [TunnelConfig.configText]. Returns null when
         * the blob carries no usable profile text (empty config,
         * whitespace, non-JSON, or JSON without a non-blank
         * `ovpnConfigText`), so the caller can surface a clear error
         * rather than a native start that fails deep inside OpenVPN3 Core.
         */
        fun parse(bytes: ByteArray): OpenVpnConfigBlob? {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (!text.startsWith("{")) return null
            return try {
                val json = JSONObject(text)
                val configText = json.optString("ovpnConfigText", "").trim()
                if (configText.isEmpty()) null
                else OpenVpnConfigBlob(
                    ovpnConfigText = configText,
                    username = json.optString("username", "").trim(),
                    // Not trimmed: unlike username, a password's leading/
                    // trailing whitespace could be a real part of the secret.
                    password = json.optString("password", ""),
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
