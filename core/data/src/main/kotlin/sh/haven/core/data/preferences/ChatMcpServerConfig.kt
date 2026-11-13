package sh.haven.core.data.preferences

import org.json.JSONArray
import org.json.JSONObject

/**
 * External streamable-HTTP MCP server configured for AI chat tool execution (#807).
 *
 * When stored in DataStore (`chat_mcp_servers`), [bearerToken] is wrapped with
 * `CredentialEncryption` (`ENC:...`). When exported by `BackupService`,
 * [bearerToken] is decrypted into the PBKDF2-AES-GCM backup envelope and
 * re-encrypted with the target device's Keystore on import.
 */
data class ChatMcpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val bearerToken: String? = null,
    val enabled: Boolean = true,
) {
    companion object {
        const val PREF_KEY_NAME = "chat_mcp_servers"

        fun encodeList(
            servers: List<ChatMcpServerConfig>,
            encryptToken: (String) -> String,
        ): String = JSONArray().apply {
            servers.forEach { s ->
                val cleanUrl = s.url.trim()
                if (cleanUrl.isEmpty()) return@forEach
                val cleanName = s.name.trim().ifEmpty { cleanUrl }
                val cleanToken = s.bearerToken?.trim()?.takeIf { it.isNotEmpty() }
                put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("name", cleanName)
                        put("url", cleanUrl)
                        put("bearerToken", cleanToken?.let(encryptToken) ?: JSONObject.NULL)
                        put("enabled", s.enabled)
                    },
                )
            }
        }.toString()

        fun decodeList(
            raw: String?,
            decryptToken: (String) -> String?,
        ): List<ChatMcpServerConfig> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").trim()
                        val url = obj.optString("url").trim()
                        if (id.isEmpty() || url.isEmpty()) continue
                        val name = obj.optString("name").trim().ifEmpty { url }
                        val rawToken = if (obj.isNull("bearerToken")) null else obj.optString("bearerToken").trim()
                        val token = rawToken?.takeIf { it.isNotEmpty() }?.let(decryptToken)
                        val enabled = obj.optBoolean("enabled", true)
                        add(
                            ChatMcpServerConfig(
                                id = id,
                                name = name,
                                url = url,
                                bearerToken = token,
                                enabled = enabled,
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
