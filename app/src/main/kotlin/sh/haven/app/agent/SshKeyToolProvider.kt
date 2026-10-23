package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.repository.SshKeyRepository

/**
 * The SSH-key MCP tools (#mcp-backbone Stage 5, Layer E): import / list /
 * delete saved SSH keys and toggle their per-key auth options (enabledForAuth,
 * and the FIDO2/SK verify-required PIN flag). A self-contained domain over
 * [SshKeyRepository] with no shared McpTools helpers. Private key bytes never
 * cross the wire — only public metadata (label, type, OpenSSH public line,
 * SHA-256 fingerprint, flags) is returned; FIDO2/SK keys can't be imported as
 * pasteable text and are directed to the on-device Discover flow.
 */
internal class SshKeyToolProvider(
    private val sshKeyRepository: SshKeyRepository,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "list_ssh_keys" to ToolHandler(
            description = "List saved SSH keys available for SSH / Mosh / SFTP profiles. Returns id, label, keyType (e.g. ed25519, rsa, sk-ssh-ed25519@openssh.com), publicKeyOpenSsh, fingerprintSha256, isEncrypted (passphrase-protected), biometricProtected, hardwareBacked (true when the private half lives in the device's secure chip — such keys can't be exported or backed up, and every sign-in requires a fresh biometric confirmation on the phone), enabledForAuth (whether it's offered in 'any saved key' auto-auth), verifyRequired (FIDO2/SK keys only: requires its PIN at sign-in), and createdAt. Set enabledForAuth / verifyRequired via set_ssh_key_option. Private key bytes are NEVER returned — they stay encrypted at rest.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSshKeys() },

        "import_ssh_key" to ToolHandler(
            description = "Import an OpenSSH / PEM / PKCS#8 / PuTTY PPK private key into the Haven key store. Pass `privateKey` (the text body, e.g. starting with `-----BEGIN OPENSSH PRIVATE KEY-----`), `label` (user-facing name), and optional `passphrase` (only if the key is encrypted). Returns the new key id, keyType, publicKeyOpenSsh (suitable for an `authorized_keys` line), and fingerprintSha256.",
            inputSchema = objectSchema {
                string("privateKey", "Private key body in OpenSSH / PEM / PKCS#8 / PuTTY format. Pass the file's text contents verbatim, including BEGIN/END lines.", required = true)
                string("label", "User-facing label shown on the Keys screen and in profile pickers.", required = true)
                string("passphrase", "Optional. Only required if the private key is encrypted at rest. Stored only briefly to decrypt the key for parsing; the saved entity keeps the original (still-encrypted) bytes.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val label = args.optString("label", "(unnamed)")
                "Import SSH key \"$label\" into the Haven key store?"
            },
        ) { args -> importSshKey(args) },

        "delete_ssh_key" to ToolHandler(
            description = "Delete a saved SSH key by id. Profiles that referenced it via sshKeyId will fall through to password auth (or fail) on next connect — no cascade rewrite. Irreversible: the encrypted private key bytes are removed.",
            inputSchema = objectSchema {
                string("sshKeyId", "SSH key id from list_ssh_keys.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("sshKeyId")
                val label = runBlocking { sshKeyRepository.getById(id)?.label } ?: id.take(8) + "…"
                "Delete SSH key \"$label\"? Cannot be undone."
            },
        ) { args -> deleteSshKey(args) },

        "set_ssh_key_option" to ToolHandler(
            description = "Set per-key options on a saved SSH key (the toggles on the Keys screen). " +
                "`keyId` (from list_ssh_keys) is required; pass either or both of: " +
                "`enabledForAuth` (bool) — whether the key takes part in 'any saved key' auto-auth (off = only used when a profile pins it); " +
                "`verifyRequired` (bool) — FIDO2/SK keys only — whether the key requires its PIN at every sign-in (true) or is touch-only (false); flips the SK flag in place without re-registering; " +
                "`storedPassphrase` (string) — encrypted keys only — store the key's passphrase on-device (encrypted at rest) so connects don't prompt and the key can be exposed over SSH agent forwarding (#377); empty string clears a stored passphrase. " +
                "Returns the key's resulting enabledForAuth and verifyRequired. Biometric-protected SK keys can't have verifyRequired changed over MCP (no prompt available).",
            inputSchema = objectSchema {
                string("keyId", "SSH key id from list_ssh_keys.", required = true)
                boolean("enabledForAuth", "Include this key in 'any saved key' auto-auth.")
                boolean("verifyRequired", "FIDO2/SK only: require the key's PIN at every sign-in.")
                string("storedPassphrase", "Encrypted keys only: passphrase to store on-device (empty string clears).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val id = args.optString("keyId")
                val label = runBlocking { sshKeyRepository.getById(id)?.label } ?: id.take(8) + "…"
                "Change auth options for SSH key \"$label\"?"
            },
        ) { args -> setSshKeyOption(args) },
    )

    private suspend fun listSshKeys(): JSONObject {
        val keys = sshKeyRepository.getAll()
        val arr = JSONArray()
        for (k in keys) {
            arr.put(JSONObject().apply {
                put("id", k.id)
                put("label", k.label)
                put("keyType", k.keyType)
                put("publicKeyOpenSsh", k.publicKeyOpenSsh)
                put("fingerprintSha256", k.fingerprintSha256)
                put("isEncrypted", k.isEncrypted)
                put("biometricProtected", k.biometricProtected)
                put("hardwareBacked", k.hardwareAlias != null)
                put("enabledForAuth", k.enabledForAuth)
                // verify-required (PIN) lives in the SK blob's flags; decrypt
                // non-biometric SK keys to surface it (null for non-SK or when
                // unreadable). Biometric keys are skipped to avoid a prompt.
                if (k.keyType.startsWith("sk-")) {
                    val vr = if (!k.biometricProtected) {
                        runCatching {
                            sshKeyRepository.getDecryptedKeyBytes(k.id)?.let {
                                sh.haven.core.fido.SkKeyData.deserialize(it).flags.toInt() and 0x04 != 0
                            }
                        }.getOrNull()
                    } else null
                    put("verifyRequired", vr ?: JSONObject.NULL)
                }
                put("hasCertificate", k.certificateBytes != null)
                put("caConfigId", k.caConfigId ?: JSONObject.NULL)
                put("certIssuedAt", k.certIssuedAt ?: JSONObject.NULL)
                put("createdAt", k.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", keys.size)
            put("keys", arr)
        }
    }

    private suspend fun setSshKeyOption(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val keyId = args.optString("keyId").ifBlank { throw IllegalArgumentException("keyId required") }
        val key = sshKeyRepository.getById(keyId)
            ?: throw IllegalArgumentException("SSH key not found: $keyId")
        val changed = JSONArray()

        if (args.has("enabledForAuth")) {
            sshKeyRepository.setEnabledForAuth(keyId, args.getBoolean("enabledForAuth"))
            changed.put("enabledForAuth=${args.getBoolean("enabledForAuth")}")
        }
        if (args.has("verifyRequired")) {
            if (!key.keyType.startsWith("sk-")) {
                throw IllegalArgumentException("verifyRequired only applies to FIDO2/SK keys")
            }
            val required = args.getBoolean("verifyRequired")
            val plain = sshKeyRepository.getDecryptedKeyBytes(keyId)
                ?: throw IllegalArgumentException(
                    "Couldn't read the key (biometric-protected keys can't be changed over MCP)",
                )
            val sk = sh.haven.core.fido.SkKeyData.deserialize(plain)
            val newFlags: Byte = if (required) 0x05 else 0x01
            if (sk.flags != newFlags) {
                sshKeyRepository.save(
                    key.copy(
                        privateKeyBytes =
                            sh.haven.core.fido.SkKeyData.serialize(sk.copy(flags = newFlags)),
                    ),
                )
            }
            changed.put("verifyRequired=$required")
        }
        if (args.has("storedPassphrase")) {
            if (!key.isEncrypted) {
                throw IllegalArgumentException("storedPassphrase only applies to passphrase-protected keys")
            }
            val value = args.getString("storedPassphrase")
            sshKeyRepository.setStoredPassphrase(keyId, value.takeIf { it.isNotEmpty() })
            changed.put(if (value.isEmpty()) "storedPassphrase=cleared" else "storedPassphrase=set")
        }

        val updated = sshKeyRepository.getById(keyId)!!
        val vr = if (updated.keyType.startsWith("sk-") && !updated.biometricProtected) {
            runCatching {
                sshKeyRepository.getDecryptedKeyBytes(keyId)?.let {
                    sh.haven.core.fido.SkKeyData.deserialize(it).flags.toInt() and 0x04 != 0
                }
            }.getOrNull()
        } else {
            null
        }
        JSONObject().apply {
            put("id", keyId)
            put("label", updated.label)
            put("keyType", updated.keyType)
            put("enabledForAuth", updated.enabledForAuth)
            put("verifyRequired", vr ?: JSONObject.NULL)
            put("changed", changed)
        }
    }

    private suspend fun importSshKey(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val privateKey = args.optString("privateKey").ifBlank {
            throw IllegalArgumentException("privateKey required")
        }
        val label = args.optString("label").ifBlank {
            throw IllegalArgumentException("label required")
        }
        val passphrase = args.optString("passphrase").takeIf { it.isNotEmpty() }

        val fileBytes = privateKey.toByteArray(Charsets.UTF_8)
        val imported = try {
            sh.haven.core.ssh.SshKeyImporter.import(fileBytes, passphrase)
        } catch (e: sh.haven.core.ssh.SshKeyImporter.EncryptedKeyException) {
            throw IllegalArgumentException(
                "Key is passphrase-encrypted — pass `passphrase` to import.",
            )
        } catch (e: sh.haven.core.ssh.SshKeyImporter.SkKeyDetectedException) {
            throw IllegalArgumentException(
                "FIDO2 SK keys must be imported via the Keys → Discover from security key " +
                    "flow on-device — they aren't pasteable text.",
            )
        }

        val entity = sh.haven.core.data.db.entities.SshKey(
            label = label,
            keyType = imported.keyType,
            privateKeyBytes = imported.privateKeyBytes,
            publicKeyOpenSsh = imported.publicKeyOpenSsh,
            fingerprintSha256 = imported.fingerprintSha256,
            isEncrypted = imported.isEncrypted,
        )
        sshKeyRepository.save(entity)

        JSONObject().apply {
            put("id", entity.id)
            put("label", entity.label)
            put("keyType", entity.keyType)
            put("publicKeyOpenSsh", entity.publicKeyOpenSsh)
            put("fingerprintSha256", entity.fingerprintSha256)
            put("isEncrypted", entity.isEncrypted)
        }
    }

    private suspend fun deleteSshKey(args: JSONObject): JSONObject {
        val id = args.optString("sshKeyId").ifBlank {
            throw IllegalArgumentException("sshKeyId required")
        }
        val existing = sshKeyRepository.getById(id)
            ?: throw IllegalArgumentException("No SSH key with id $id")
        sshKeyRepository.delete(id)
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }
}
