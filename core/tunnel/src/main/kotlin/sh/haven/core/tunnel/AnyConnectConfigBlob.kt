package sh.haven.core.tunnel

import org.json.JSONObject

/**
 * Storage envelope for Cisco AnyConnect / ocserv tunnel configs. Encoded
 * into [sh.haven.core.data.db.entities.TunnelConfig.configText] (encrypted
 * at rest by the repository) and handed verbatim to the native side, whose
 * `ocbridge.Config` uses the same JSON keys:
 * ```
 * {
 *   "server": "vpn.example.com/engineering",
 *   "username": "alice", "password": "…",
 *   "authGroup": "Engineering",            // optional
 *   "otpSecret": "JBSW… or otpauth://…",   // optional, TOTP second factor
 *   "secondaryPassword": "push",           // optional, fixed second factor
 *   "serverCert": "pin-sha256:…",          // optional, self-signed gateways
 *   "userAgent": "AnyConnect Linux_64 …",  // optional
 *   "stayConnected": true                  // Haven-only, not sent to ocbridge
 * }
 * ```
 * An empty password or missing second factor is asked for at sign-in
 * (dialog + notification) — that is how SMS codes work. Optional keys are
 * omitted when blank. Unknown keys are ignored in both
 * directions, so fields can be added later without breaking older builds.
 */
data class AnyConnectConfigBlob(
    val server: String,
    val username: String,
    val password: String,
    val authGroup: String = "",
    val otpSecret: String = "",
    val secondaryPassword: String = "",
    val serverCert: String = "",
    val userAgent: String = "",
    /**
     * Keep the tunnel up after the last connection using it closes, like
     * Cisco's own client, instead of dropping it (and resuming the gateway
     * session on next use). Shown in Haven's ongoing notification.
     */
    val stayConnected: Boolean = true,
) {
    fun encode(): ByteArray =
        toJson().apply { if (!stayConnected) put("stayConnected", false) }
            .toString().toByteArray(Charsets.UTF_8)

    /** The JSON string `Ocbridge.startTunnel` takes (Haven-only keys left out). */
    fun toNativeConfig(): String = toJson().toString()

    private fun toJson() = JSONObject().apply {
        put("server", server)
        put("username", username)
        put("password", password)
        if (authGroup.isNotBlank()) put("authGroup", authGroup)
        if (otpSecret.isNotBlank()) put("otpSecret", otpSecret)
        if (secondaryPassword.isNotBlank()) put("secondaryPassword", secondaryPassword)
        if (serverCert.isNotBlank()) put("serverCert", serverCert)
        if (userAgent.isNotBlank()) put("userAgent", userAgent)
    }

    companion object {
        /**
         * Decode bytes from [TunnelConfig.configText]. Returns null when the
         * blob is not JSON or lacks a server or username, so the caller can
         * surface a clear error instead of a native-side parse failure.
         * The password may legitimately be empty (OTP-only gateways).
         */
        fun parse(bytes: ByteArray): AnyConnectConfigBlob? {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (!text.startsWith("{")) return null
            return try {
                val json = JSONObject(text)
                val server = json.optString("server", "").trim()
                val username = json.optString("username", "").trim()
                if (server.isEmpty() || username.isEmpty()) return null
                AnyConnectConfigBlob(
                    server = server,
                    username = username,
                    password = json.optString("password", ""),
                    authGroup = json.optString("authGroup", "").trim(),
                    otpSecret = json.optString("otpSecret", "").trim(),
                    secondaryPassword = json.optString("secondaryPassword", ""),
                    serverCert = json.optString("serverCert", "").trim(),
                    userAgent = json.optString("userAgent", "").trim(),
                    stayConnected = json.optBoolean("stayConnected", true),
                )
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Normalise what a user pastes as the gateway: trims, and drops a
         * leading `https://` (re-added natively) while keeping a port and a
         * group-URL path. Returns null for `http://`, which AnyConnect never
         * uses and which would otherwise silently fail.
         */
        fun normalizeServer(input: String): String? {
            val s = input.trim()
            if (s.startsWith("http://", ignoreCase = true)) return null
            return s.removePrefix("https://").removePrefix("HTTPS://").trimEnd('/')
                .takeIf { it.isNotEmpty() }
        }
    }
}
