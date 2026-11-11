package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.KnownHostDao

/**
 * MCP tools over the SSH host-key trust store (`known_hosts`). Haven pins a
 * server's host key on first connect (TOFU) and warns on a later mismatch;
 * these tools let an agent read that store and forget an entry — needed when a
 * server legitimately rotates its host key, or to clear a stale pin (e.g. a
 * throwaway test server on a reused host:port). Forgetting an entry drops
 * Haven back to first-use trust for that host:port on the next connect, so it
 * is EVERY_CALL-gated: the store is a security boundary, not preferences.
 */
internal class HostKeyToolProvider(
    private val knownHostDao: KnownHostDao,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "list_known_hosts" to ToolHandler(
            description = "List pinned SSH host keys (the TOFU known_hosts store). Returns hostname, port, keyType, " +
                "fingerprint (SHA-256), and firstSeen (epoch ms). Use forget_known_host to remove one.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listKnownHosts() },

        "forget_known_host" to ToolHandler(
            description = "Forget a pinned SSH host key by hostname + port, so the next connect re-pins on " +
                "first-use trust (TOFU). Use when a server has legitimately rotated its host key, or to clear " +
                "a stale pin. `hostname` and `port` are required (from list_known_hosts). No-op if none matches; " +
                "returns removed=true/false.",
            inputSchema = objectSchema {
                string("hostname", "Host of the pinned key (from list_known_hosts).", required = true)
                integer("port", "Port of the pinned key (SSH default 22).", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val host = args.optString("hostname")
                val port = if (args.has("port")) args.optInt("port") else 22
                "Forget the pinned SSH host key for \"$host:$port\"? Next connect re-pins on first use."
            },
        ) { args -> forgetKnownHost(args) },
    )

    private suspend fun listKnownHosts(): JSONObject = withContext(Dispatchers.IO) {
        val hosts = knownHostDao.getAll()
        val arr = JSONArray()
        for (h in hosts) {
            arr.put(
                JSONObject().apply {
                    put("hostname", h.hostname)
                    put("port", h.port)
                    put("keyType", h.keyType)
                    put("fingerprint", h.fingerprint)
                    put("firstSeen", h.firstSeen)
                },
            )
        }
        JSONObject().apply {
            put("count", hosts.size)
            put("hosts", arr)
        }
    }

    private suspend fun forgetKnownHost(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val hostname = args.optString("hostname").ifBlank {
            throw IllegalArgumentException("hostname required")
        }
        if (!args.has("port")) throw IllegalArgumentException("port required")
        val port = args.getInt("port")
        val existing = knownHostDao.findByHostPort(hostname, port)
        if (existing != null) {
            knownHostDao.deleteByHostPort(hostname, port)
        }
        JSONObject().apply {
            put("hostname", hostname)
            put("port", port)
            put("removed", existing != null)
            if (existing != null) put("fingerprint", existing.fingerprint)
        }
    }
}
