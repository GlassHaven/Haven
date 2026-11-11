package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.repository.AgeIdentityRepository
import sh.haven.core.data.repository.TotpSecretRepository

/**
 * The Haven key store's MCP tools (#mcp-backbone Stage 5, Layer E): OATH-TOTP
 * authenticator secrets (#178) and age file-encryption identities (VISION §2).
 * A cohesive, self-contained domain — every tool is backed by a repository or
 * the UI command bus, with no shared McpTools helpers — which makes it the
 * first slice extracted from the God file into a [ToolProvider]. The private
 * key material (base32 TOTP secret, AGE-SECRET-KEY) never crosses the wire;
 * only public recipients and metadata are returned.
 *
 * One deliberate exception: `generate_totp_code` returns a *derived* code —
 * never the secret it came from — for the 2FA prompts the `TOTP:<id>` auth
 * path cannot reach, and asks the user every single time it does.
 */
internal class KeyStoreToolProvider(
    private val totpSecretRepository: TotpSecretRepository,
    private val ageIdentityRepository: AgeIdentityRepository,
    private val agentUiCommandBus: AgentUiCommandBus,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "list_totp_secrets" to ToolHandler(
            description = "List saved OATH-TOTP authenticator secrets (#178). Returns id, label, issuer, accountName, algorithm, digits, periodSeconds, and createdAt. The base32 secret itself is NEVER returned — it stays encrypted at rest. Reference an id as a `TOTP:<id>` token in create_connection's authMethods to auto-fill the SSH 'Verification code:' prompt.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listTotpSecrets() },

        "create_totp_secret" to ToolHandler(
            description = "Store an OATH-TOTP secret so it can auto-fill an SSH keyboard-interactive OTP prompt (#178). Pass `otpauth` (an `otpauth://totp/...` URI) OR `secret` (a raw base32 string) plus an optional `label`. Returns the new secret id; reference it via a `TOTP:<id>` token in create_connection's authMethods.",
            inputSchema = objectSchema {
                string("otpauth", "An otpauth://totp/Issuer:account?secret=...&... URI. Mutually exclusive with `secret`.")
                string("secret", "A raw base32 TOTP secret (SHA1, 6 digits, 30s period assumed). Use `otpauth` instead when you have the full URI.")
                string("label", "Optional user-facing label. Defaults to the otpauth issuer/account or 'Authenticator'.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val l = args.optString("label").ifBlank { "(from otpauth)" }
                "Store a TOTP authenticator secret \"$l\" in the Haven key store?"
            },
        ) { args -> createTotpSecret(args) },

        "generate_totp_code" to ToolHandler(
            description = "Return the code a saved TOTP secret is showing right now (#178). This is the only verb that hands a live credential to the caller, so it asks every time. " +
                "For SSH, prefer a `TOTP:<id>` token in create_connection's authMethods — Haven fills the prompt itself and the code never leaves the device. " +
                "Use this for the prompts that path cannot reach: a 2FA field in a guest desktop, a web login, an app. " +
                "Check `secondsRemaining` before using the code — a code with two seconds left will be rejected by the time it is typed; call again after it rolls over.",
            inputSchema = objectSchema {
                string("totpSecretId", "TOTP secret id from list_totp_secrets.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("totpSecretId")
                val label = runBlocking { totpSecretRepository.getById(id)?.label } ?: id.take(8) + "…"
                "Reveal the current TOTP code for \"$label\"? It will be sent to the connected MCP client."
            },
        ) { args -> generateTotpCode(args) },

        "delete_totp_secret" to ToolHandler(
            description = "Delete a saved TOTP secret by id. Profiles referencing it via a TOTP auth element fall through to a manual OTP prompt on next connect. Irreversible.",
            inputSchema = objectSchema {
                string("totpSecretId", "TOTP secret id from list_totp_secrets.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("totpSecretId")
                val label = runBlocking { totpSecretRepository.getById(id)?.label } ?: id.take(8) + "…"
                "Delete TOTP secret \"$label\"? Cannot be undone."
            },
        ) { args -> deleteTotpSecret(args) },

        "list_age_identities" to ToolHandler(
            description = "List saved age file-encryption identities (VISION §2). Returns id, label, the public `age1…` recipient (encrypt to this with encrypt_file or the file browser's Encrypt action), and createdAt. The private key (AGE-SECRET-KEY-1…) is NEVER returned — it stays encrypted at rest.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listAgeIdentities() },

        "create_age_identity" to ToolHandler(
            description = "Generate and store a new age X25519 encryption identity (VISION §2). Optional `label`. Returns the new id and its public `age1…` recipient. Tap-equivalent to Keys → + → Generate age identity.",
            inputSchema = objectSchema {
                string("label", "Optional user-facing label. Defaults to 'age identity'.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val l = args.optString("label").ifBlank { "age identity" }
                "Generate a new age encryption identity \"$l\" in the Haven key store?"
            },
        ) { args -> createAgeIdentity(args) },

        "encrypt_file" to ToolHandler(
            description = "Encrypt the file at `path` on `profileId` to age recipients, producing `<name>.age` in the same folder (VISION §2 — works on every backend: local, SFTP, SMB, rclone). `recipients` (optional) is a list of `age1…` strings; omit it to encrypt to ALL of your stored age identities (so you can decrypt it back). Drives the file browser's Encrypt (age) action via the UI command bus — the user sees it run and the output appear. Non-destructive (the original is kept). Use list_age_identities for recipients and list_directory to find paths.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile id (or 'local'). From list_connections.", required = true)
                string("path", "Absolute path to the file to encrypt.", required = true)
                stringArray("recipients", "age1… recipients. Omit to encrypt to all stored identities.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Encrypt \"${args.optString("path")}\" with age?" },
        ) { args -> encryptFileViaAgent(args) },

        "decrypt_file" to ToolHandler(
            description = "Decrypt the `.age` file at `path` on `profileId` in place (strips `.age`) using any stored age identity (VISION §2). Drives the file browser's Decrypt (age) action via the UI command bus — the user sees it run. Fails to produce output if no stored identity matches the file's recipients.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile id (or 'local').", required = true)
                string("path", "Absolute path to the .age file.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Decrypt \"${args.optString("path")}\" with age?" },
        ) { args -> decryptFileViaAgent(args) },
    )

    private suspend fun listTotpSecrets(): JSONObject {
        val secrets = totpSecretRepository.getAll()
        val arr = JSONArray()
        for (s in secrets) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                put("issuer", s.issuer ?: JSONObject.NULL)
                put("accountName", s.accountName ?: JSONObject.NULL)
                put("algorithm", s.algorithm)
                put("digits", s.digits)
                put("periodSeconds", s.periodSeconds)
                put("createdAt", s.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", secrets.size)
            put("secrets", arr)
        }
    }

    private suspend fun listAgeIdentities(): JSONObject {
        val ids = ageIdentityRepository.getAll()
        val arr = JSONArray()
        for (i in ids) {
            arr.put(JSONObject().apply {
                put("id", i.id)
                put("label", i.label)
                put("recipient", i.recipient)
                put("createdAt", i.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", ids.size)
            put("identities", arr)
        }
    }

    private suspend fun createAgeIdentity(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val label = args.optString("label").takeIf { it.isNotBlank() } ?: "age identity"
        val row = ageIdentityRepository.create(label)
        JSONObject().apply {
            put("id", row.id)
            put("label", row.label)
            put("recipient", row.recipient)
        }
    }

    private suspend fun encryptFileViaAgent(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank { throw IllegalArgumentException("profileId required") }
        val path = args.optString("path").ifBlank { throw IllegalArgumentException("path required") }
        val recipientsArg = args.optJSONArray("recipients")
        val recipients = if (recipientsArg != null && recipientsArg.length() > 0) {
            (0 until recipientsArg.length()).map { recipientsArg.getString(it) }
        } else {
            ageIdentityRepository.getAll().map { it.recipient }
        }
        if (recipients.isEmpty()) {
            throw IllegalArgumentException(
                "No recipients given and no stored age identities — create one with create_age_identity or pass recipients.",
            )
        }
        val delivered = agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.EncryptFile(profileId, path, recipients),
        )
        return JSONObject().apply {
            put("dispatched", delivered)
            put("path", path)
            put("output", "$path.age")
            put("recipientCount", recipients.size)
            put("note", "Encrypting in the file browser; confirm with list_directory once it completes.")
        }
    }

    private suspend fun decryptFileViaAgent(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank { throw IllegalArgumentException("profileId required") }
        val path = args.optString("path").ifBlank { throw IllegalArgumentException("path required") }
        val delivered = agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.DecryptFile(profileId, path),
        )
        return JSONObject().apply {
            put("dispatched", delivered)
            put("path", path)
            put("output", path.removeSuffix(".age"))
            put("note", "Decrypting in the file browser; confirm with list_directory once it completes.")
        }
    }

    private suspend fun createTotpSecret(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val otpauth = args.optString("otpauth").takeIf { it.isNotBlank() }
        val rawSecret = args.optString("secret").takeIf { it.isNotBlank() }
        val input = otpauth ?: rawSecret
            ?: throw IllegalArgumentException("Pass either `otpauth` or `secret`")
        val parsed = sh.haven.core.security.OtpAuthUri.parse(input)
            ?: throw IllegalArgumentException("Not a valid otpauth:// URI or base32 secret")
        val labelOverride = args.optString("label").takeIf { it.isNotBlank() }
        val entity = sh.haven.core.data.db.entities.TotpSecret(
            label = labelOverride ?: parsed.label,
            secret = parsed.secret,
            issuer = parsed.issuer,
            accountName = parsed.accountName,
            algorithm = parsed.algorithm.name,
            digits = parsed.digits,
            periodSeconds = parsed.periodSeconds,
        )
        totpSecretRepository.save(entity)
        JSONObject().apply {
            put("id", entity.id)
            put("label", entity.label)
            put("issuer", entity.issuer ?: JSONObject.NULL)
            put("accountName", entity.accountName ?: JSONObject.NULL)
            put("algorithm", entity.algorithm)
            put("digits", entity.digits)
            put("periodSeconds", entity.periodSeconds)
            put("authMethodToken", "TOTP:${entity.id}")
        }
    }

    /**
     * The stored algorithm/digits/period are honoured rather than assumed. A
     * SHA256 or 8-digit authenticator is uncommon but not rare, and defaulting
     * would hand back six plausible-looking wrong digits — a failure that reads
     * as "the server rejected it" rather than as a bug here.
     */
    private suspend fun generateTotpCode(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = args.optString("totpSecretId").ifBlank {
            throw IllegalArgumentException("totpSecretId required")
        }
        val entity = totpSecretRepository.getById(id)
            ?: throw IllegalArgumentException("No TOTP secret with id $id")
        val secret = totpSecretRepository.getDecryptedSecret(id)
            ?: throw IllegalArgumentException("TOTP secret $id is stored but could not be decrypted")
        val algorithm = runCatching { sh.haven.core.security.Totp.Algorithm.valueOf(entity.algorithm) }
            .getOrDefault(sh.haven.core.security.Totp.Algorithm.SHA1)
        val now = System.currentTimeMillis()
        val period = entity.periodSeconds
        val code = sh.haven.core.security.Totp.generate(
            secretBase32 = secret,
            atMillis = now,
            algorithm = algorithm,
            digits = entity.digits,
            periodSeconds = period,
        )
        JSONObject().apply {
            put("id", entity.id)
            put("label", entity.label)
            put("code", code)
            put("digits", entity.digits)
            put("periodSeconds", period)
            put("secondsRemaining", period - ((now / 1000L) % period).toInt())
        }
    }

    private suspend fun deleteTotpSecret(args: JSONObject): JSONObject {
        val id = args.optString("totpSecretId").ifBlank {
            throw IllegalArgumentException("totpSecretId required")
        }
        val existing = totpSecretRepository.getById(id)
            ?: throw IllegalArgumentException("No TOTP secret with id $id")
        totpSecretRepository.delete(id)
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }
}
