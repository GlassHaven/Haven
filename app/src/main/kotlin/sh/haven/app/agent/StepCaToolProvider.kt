package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.data.repository.StepCaConfigRepository
import java.security.MessageDigest
import java.util.Base64

/**
 * MCP tools over the trusted SSH host-CA store (`StepCaConfig.sshHostCaPublicKey`,
 * #133). A trusted host CA lets Haven accept any server that presents an OpenSSH
 * host certificate signed by that CA — no per-host TOFU prompt. These verbs are
 * the data-plane surface for that trust store, mirroring [HostKeyToolProvider]'s
 * known-hosts verbs: without them, adding or removing a host CA could only be
 * done by hand-driving the Keys UI.
 *
 * Adding or removing a trust anchor is a security boundary (not preferences), so
 * both mutating verbs are EVERY_CALL-gated. [StepCaConfig] doubles as the store
 * for full OIDC user-cert provisioners; these verbs only create host-CA-only
 * entries (the #380 shape — name + CA key, no OIDC), but `delete` works on any
 * config by id so an agent can prune a host-CA entry it can see.
 */
internal class StepCaToolProvider(
    private val stepCaConfigRepository: StepCaConfigRepository,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "list_trusted_host_cas" to ToolHandler(
            description = "List the trusted SSH host-CA entries — the step-ca configs whose SSH host-CA public " +
                "key is set (#133). A server presenting an OpenSSH host certificate signed by one of these CAs " +
                "connects with no TOFU fingerprint prompt. Returns, per entry: id, name, keyType, and " +
                "fingerprint (SHA-256, OpenSSH format). Configs with no host-CA key are omitted. Use " +
                "add_trusted_host_ca / delete_trusted_host_ca to change the store.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listTrustedHostCas() },

        "add_trusted_host_ca" to ToolHandler(
            description = "Trust an SSH host CA (#133): register a CA public key so any server presenting an " +
                "OpenSSH host certificate signed by it connects without a TOFU prompt. `caPublicKey` is an " +
                "OpenSSH public-key line (\"ssh-ed25519 AAAA… [comment]\") or a bare base64 blob; `name` is a " +
                "label. Ed25519 and ECDSA host CAs are verified natively; RSA host CAs are stored but not yet " +
                "validated by the SSH library. Establishing trust is a security boundary — gated by consent.",
            inputSchema = objectSchema {
                string("name", "Label for this trusted CA (shown under Keys → Certificate authorities).", required = true)
                string("caPublicKey", "The CA's OpenSSH public key line, or bare base64 blob.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Trust SSH host CA \"${args.optString("name", "?")}\"? Any host with a certificate signed by it " +
                    "will connect without a fingerprint prompt."
            },
        ) { args -> addTrustedHostCa(args) },

        "delete_trusted_host_ca" to ToolHandler(
            description = "Remove a trusted SSH host CA by id (from list_trusted_host_cas). Servers signed by it " +
                "fall back to the usual per-host TOFU prompt on the next connect. Deletes the whole step-ca " +
                "config row for that id. No-op if none matches; returns removed=true/false. Gated by consent.",
            inputSchema = objectSchema {
                string("id", "The config id to remove (from list_trusted_host_cas).", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Remove trusted SSH host CA (id ${args.optString("id", "?")})? Hosts it signed will TOFU-prompt again."
            },
        ) { args -> deleteTrustedHostCa(args) },
    )

    private suspend fun listTrustedHostCas(): JSONObject = withContext(Dispatchers.IO) {
        val entries = stepCaConfigRepository.getAll()
            .filter { !it.sshHostCaPublicKey.isNullOrBlank() }
        val arr = JSONArray()
        for (c in entries) {
            val key = c.sshHostCaPublicKey!!.trim()
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("keyType", caKeyType(key) ?: JSONObject.NULL)
                    put("fingerprint", caFingerprint(key) ?: JSONObject.NULL)
                },
            )
        }
        JSONObject().apply {
            put("count", entries.size)
            put("trustedHostCas", arr)
        }
    }

    private suspend fun addTrustedHostCa(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val name = args.optString("name").ifBlank { throw IllegalArgumentException("name required") }
        val caPublicKey = args.optString("caPublicKey").ifBlank {
            throw IllegalArgumentException("caPublicKey required")
        }.trim()
        if (!isValidHostCaKey(caPublicKey)) {
            throw IllegalArgumentException(
                "caPublicKey is not a valid OpenSSH host-CA public key (expected an ssh-ed25519 / ecdsa-* line or base64 blob)",
            )
        }
        // Host-CA-only config (#380): every signing/OIDC field blank; the entity
        // columns are non-null String, so "" is the right empty. sshHostCaPublicKey
        // is what HostKeyVerifier.trustedHostCaKeys() reads.
        val config = StepCaConfig(
            name = name.trim(),
            caUrl = "",
            oidcIssuer = "",
            oidcAuthUrl = "",
            oidcTokenUrl = "",
            oidcClientId = "",
            provisioner = "",
            defaultPrincipals = "",
            rootCertPem = "",
            sshHostCaPublicKey = caPublicKey,
        )
        stepCaConfigRepository.save(config)
        JSONObject().apply {
            put("id", config.id)
            put("name", config.name)
            put("keyType", caKeyType(caPublicKey) ?: JSONObject.NULL)
            put("fingerprint", caFingerprint(caPublicKey) ?: JSONObject.NULL)
        }
    }

    private suspend fun deleteTrustedHostCa(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = args.optString("id").ifBlank { throw IllegalArgumentException("id required") }
        val existing = stepCaConfigRepository.getById(id)
        if (existing != null) stepCaConfigRepository.delete(id)
        JSONObject().apply {
            put("id", id)
            put("removed", existing != null)
            if (existing != null) put("name", existing.name)
        }
    }

    private companion object {
        private val HOST_CA_ALGOS = setOf(
            "ssh-ed25519", "ssh-rsa", "ssh-dss",
            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com",
        )

        /** The base64 blob token of an OpenSSH pubkey line, or the input if it's already bare. */
        private fun blobToken(line: String): String {
            val parts = line.trim().split(Regex("\\s+"))
            return if (parts.size >= 2 && parts[0] in HOST_CA_ALGOS) parts[1] else parts[0]
        }

        private fun decodeBlob(line: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(blobToken(line)) }.getOrNull()

        /** Recognised algorithm token + a base64 blob that decodes to a plausible key size. */
        fun isValidHostCaKey(line: String): Boolean {
            if (line.contains("PRIVATE KEY")) return false
            val parts = line.trim().split(Regex("\\s+"))
            // A typed line must carry a known algorithm; a bare token is accepted if it decodes.
            if (parts.size >= 2 && parts[0] !in HOST_CA_ALGOS) return false
            val blob = decodeBlob(line) ?: return false
            return blob.size >= 32
        }

        /** The algorithm token if the line is typed, else null (bare blob). */
        fun caKeyType(line: String): String? {
            val parts = line.trim().split(Regex("\\s+"))
            return if (parts.size >= 2 && parts[0] in HOST_CA_ALGOS) parts[0] else null
        }

        /** OpenSSH-style "SHA256:<base64-no-pad>" fingerprint of the key blob, or null. */
        fun caFingerprint(line: String): String? {
            val blob = decodeBlob(line) ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
            return "SHA256:$b64"
        }
    }
}
