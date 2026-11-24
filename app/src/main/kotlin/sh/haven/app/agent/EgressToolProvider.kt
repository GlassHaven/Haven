package sh.haven.app.agent

import android.content.Context
import org.json.JSONObject
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.EgressDecision
import sh.haven.core.data.agent.EgressTarget
import sh.haven.core.data.agent.McpToolCategory
import sh.haven.core.mcp.McpError
import sh.haven.core.security.EgressGrantSigner
import sh.haven.core.security.EgressSigningKeyStore
import java.security.SecureRandom
import java.util.UUID

/**
 * Controlled-egress consent tools (Phase 2 of the conductor's internet-egress
 * gate). The root egress proxy parks an arbitrary-host request from a sandboxed
 * agent and calls [REQUEST_EGRESS_GRANT] here; Ian approves on the phone via a
 * dedicated NON-BYPASSABLE sheet, and the decision is returned HMAC-signed so
 * the proxy can verify it against a root-owned shared secret (the uid-1000
 * agent cannot forge a signed allow).
 *
 * [clientHintProvider] reads the calling MCP client name for the sheet's
 * "From:" line; egress consent ignores it for bypass purposes entirely.
 */
internal class EgressToolProvider(
    private val consentManager: AgentConsentManager,
    private val context: Context,
    private val clientHintProvider: () -> String?,
    /** Secret source; injectable so the handler glue unit-tests without Keystore. */
    private val secretProvider: () -> ByteArray = { EgressSigningKeyStore.getOrCreateSecret(context) },
) : ToolProvider {

    override val defaultCategory = McpToolCategory.CONNECTIONS

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        REQUEST_EGRESS_GRANT to ToolHandler(
            description = "Request a human-approved internet-egress grant for a sandboxed agent's " +
                "Bash request to host:port. Raises a dedicated non-bypassable consent sheet on the " +
                "phone (Allow once / Allow session / Deny); fails closed to a signed deny on timeout " +
                "or when Haven is unreachable. The decision is HMAC-SHA256 signed for the root egress " +
                "proxy to verify against its provisioned secret. Called by the claude-egress-proxy " +
                "client, not by the sandboxed model directly.",
            inputSchema = objectSchema {
                string("request_id", "Proxy-generated unique id for this parked request.", required = true)
                string("sandbox_id", "Identifier of the requesting sandbox.", required = true)
                string("host", "Target host exactly as the proxy will dial it (no normalisation).", required = true)
                integer("port", "Target port.", required = true)
                string("scheme", "https or http.", enum = listOf("https", "http"), required = true)
                string("reason", "Proxy-generated display reason (untrusted text).")
            },
            // EVERY_CALL for an honest catalog; the dispatcher skips its own
            // bypassable consent because nonBypassable=true, and the handler
            // raises the dedicated non-bypassable egress sheet below.
            consentLevel = sh.haven.core.data.agent.ConsentLevel.EVERY_CALL,
            nonBypassable = true,
            summarise = { args ->
                "Allow internet egress to ${args.optString("host", "?")}:${args.optInt("port", 0)}?"
            },
        ) { args -> requestEgressGrant(args) },

        GET_EGRESS_SIGNING_SECRET to ToolHandler(
            description = "Reveal the egress decision signing secret so it can be provisioned " +
                "root-owned (0600) to the host's /usr/local/lib/claude-egress/haven.key. Consent-gated: " +
                "Ian approves the reveal on the phone. Returns the base64 secret, a key id, and the " +
                "canonical message format. Haven never writes host root files itself.",
            inputSchema = emptyObjectSchema(),
            consentLevel = sh.haven.core.data.agent.ConsentLevel.EVERY_CALL,
            summarise = { "Reveal the egress signing secret to provision the host?" },
        ) { egressSigningSecret() },
    )

    private suspend fun requestEgressGrant(args: JSONObject): JSONObject {
        val requestId = args.optString("request_id").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "request_id is required")
        val sandboxId = args.optString("sandbox_id").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "sandbox_id is required")
        val host = args.optString("host").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "host is required")
        val port = args.optInt("port", -1).takeIf { it in 1..65535 }
            ?: throw McpError(-32602, "port must be 1..65535")
        val scheme = args.optString("scheme", "https").takeIf { it == "https" || it == "http" }
            ?: throw McpError(-32602, "scheme must be https or http")
        val reason = args.optString("reason", "Bash egress to $host")

        val target = EgressTarget(requestId, sandboxId, host, port, scheme, reason)
        val decision = consentManager.requestEgressConsent(target, clientHintProvider(), timeoutMs = 60_000)

        val now = System.currentTimeMillis()
        val decisionStr: String
        val expiryMs: Long?
        when (decision) {
            EgressDecision.ONCE -> { decisionStr = EgressGrantSigner.DECISION_ONCE; expiryMs = now + ONCE_TTL_MS }
            EgressDecision.SESSION -> { decisionStr = EgressGrantSigner.DECISION_SESSION; expiryMs = now + SESSION_TTL_MS }
            EgressDecision.DENY -> { decisionStr = EgressGrantSigner.DECISION_DENY; expiryMs = null }
        }
        val grantId = UUID.randomUUID().toString()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val secret = secretProvider()
        val signature = EgressGrantSigner.signDecision(
            secret, requestId, sandboxId, host, port, decisionStr, expiryMs, grantId, nonce,
        )
        return JSONObject().apply {
            put("decision", decisionStr)
            put("host", host)
            put("port", port)
            put("scheme", scheme)
            put("sandbox_id", sandboxId)
            put("request_id", requestId)
            put("expiry_ms", expiryMs ?: JSONObject.NULL)
            put("grant_id", grantId)
            put("nonce", nonce)
            put("signature", signature)
        }
    }

    private fun egressSigningSecret(): JSONObject = JSONObject().apply {
        put("algorithm", EgressGrantSigner.ALGORITHM)
        put("secret_base64", EgressSigningKeyStore.secretBase64(context))
        put("key_id", EgressSigningKeyStore.keyId(context))
        put(
            "canonical_format",
            "request_id\\nsandbox_id\\nhost\\nport\\ndecision\\nexpiry_ms(empty if null)\\ngrant_id\\nnonce",
        )
    }

    companion object {
        const val REQUEST_EGRESS_GRANT = "request_egress_grant"
        const val GET_EGRESS_SIGNING_SECRET = "get_egress_signing_secret"

        /** A single-use grant must be consumed promptly; bounds replay of a captured decision. */
        private const val ONCE_TTL_MS = 120_000L

        /** A session grant lives for the sandbox lifetime, bounded so a long-lived sandbox re-asks. */
        private const val SESSION_TTL_MS = 8 * 60 * 60_000L
    }
}
