package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.mcp.McpError
import sh.haven.core.security.HardwareKeySource
import sh.haven.core.security.SshKeyGenerator

/**
 * Credential verbs: generating a keypair into the keystore and deploying
 * its public half to a host's `authorized_keys` over a real SSH session.
 *
 * The two are deliberately separate tools: generation is a local, offline
 * mutation (a key comes into being — the user may want it for something
 * else entirely), while deploy is a remote mutation (it changes who can
 * log in to a host). Both are per-call gated. The private key never leaves
 * the device in either — only the `user@host type base64` public line
 * crosses the wire, via an idempotent `grep -qxF` guard so re-running
 * deploy_key never appends a duplicate. generate_ssh_key also offers a
 * `hardwareBacked` branch: the keypair is minted inside the device's
 * secure chip ([HardwareKeySource]) and only its public half ever exists
 * in app storage.
 *
 * deploy_key rides [HeadlessSshExec]'s reuse path (exec channel multiplexed
 * over a live session) or its headless connect (fail-closed TOFU, same
 * credential rules as run_command) — connect the profile interactively
 * first for FIDO2/encrypted-key hosts.
 */
internal class CredentialToolProvider(
    private val sshKeyRepository: SshKeyRepository,
    private val headlessSshExec: HeadlessSshExec?,
    /** Secure-chip key generation; null in unit-test constructions — the
     *  hardware branch then answers "unavailable in this build". */
    private val hardwareKeys: HardwareKeySource?,
    /** Strong-biometry gate for the hardware branch; null in unit-test
     *  constructions skips the check (nothing exercises it there). */
    private val biometricAuthenticator: sh.haven.core.security.BiometricAuthenticator? = null,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "generate_ssh_key" to ToolHandler(
            description = "Generate a new SSH keypair and store it in Haven's key store (the Keys screen). Returns the key id, type, OpenSSH public line, and SHA-256 fingerprint — the private key bytes are never returned and never leave the device. Pass `hardwareBacked: true` to generate inside the device's secure chip (StrongBox when available, TEE otherwise): such keys are always ECDSA P-256 (`ecdsa-sha2-nistp256`), their private half can NEVER be exported or backed up (it never leaves the chip — a factory reset or re-enrolled biometrics destroys it, and it won't survive a backup/restore), and every SSH sign-in with one requires a fresh biometric confirmation (fingerprint / Class 3 face) on the phone — declining aborts that connection. Software keys are unencrypted at rest (matching the UI's generate flow); set a passphrase later via the UI or set_ssh_key_option. Pairs with deploy_key.",
            inputSchema = objectSchema {
                string("label", "Key label (used as the public-key comment too).", required = true)
                boolean("hardwareBacked", "Generate inside the device's secure chip instead of as a software key. Always ECDSA P-256; the private key never leaves the chip and every use requires a biometric confirmation. Mutually exclusive with keyType.")
                string("keyType", "Software keys only: one of ed25519 (default), rsa4096, ecdsa384. Hardware-backed keys are always ecdsa-sha2-nistp256 — passing a keyType together with hardwareBacked is an error.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                if (args.optBoolean("hardwareBacked", false)) {
                    "Generate a new security-chip SSH key \"${args.optString("label", "?")}\" " +
                        "in the key store? Its private half can never leave the device."
                } else {
                    "Generate a new ${args.optString("keyType", "ed25519")} SSH key " +
                        "\"${args.optString("label", "?")}\" in the key store?"
                }
            },
        ) { args -> generateKey(args) },

        "deploy_key" to ToolHandler(
            description = "Append a stored public key to the target host's ~/.ssh/authorized_keys over an SSH session on that profile (reuse path: an exec channel over the live session; headless path: fail-closed TOFU — connect the profile interactively once first for FIDO2/encrypted-key hosts). Idempotent: the key is only appended if not already present (exact-line grep). Creates ~/.ssh with 700 and authorized_keys with 600 if missing. Run deploy only over a profile whose host you are authorised to modify — this changes who can log in to it.",
            inputSchema = objectSchema {
                string("profileId", "SSH profile to deploy to (from list_connections).", required = true)
                string("keyId", "Key id from list_ssh_keys whose public half is appended.", required = true)
                integer("timeoutMs", "Exec timeout, ms (1000–300000, default 30000).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Append the public key to ~/.ssh/authorized_keys on " +
                    "${args.optString("profileId", "?")}?"
            },
        ) { args -> deployKey(args) },
    )

    private suspend fun generateKey(args: JSONObject): JSONObject {
        val label = args.optString("label").trim()
        if (label.isBlank()) throw McpError(-32602, "label is required")
        if (args.optBoolean("hardwareBacked", false)) return generateHardwareKey(label, args)

        val keyType = when (args.optString("keyType", "ed25519").lowercase()) {
            "", "ed25519" -> SshKeyGenerator.KeyType.ED25519
            "rsa4096", "rsa_4096", "rsa" -> SshKeyGenerator.KeyType.RSA_4096
            "ecdsa384", "ecdsa_384", "ecdsa" -> SshKeyGenerator.KeyType.ECDSA_384
            else -> throw McpError(-32602, "keyType must be ed25519, rsa4096, or ecdsa384")
        }
        val generated = withContext(Dispatchers.Default) {
            SshKeyGenerator.generate(keyType, label)
        }
        val entity = SshKey(
            label = label,
            keyType = generated.type.sshName,
            privateKeyBytes = generated.privateKeyBytes,
            publicKeyOpenSsh = generated.publicKeyOpenSsh,
            fingerprintSha256 = generated.fingerprintSha256,
        )
        sshKeyRepository.save(entity)
        return JSONObject().apply {
            put("keyId", entity.id)
            put("label", label)
            put("keyType", generated.type.sshName)
            put("publicKeyOpenSsh", generated.publicKeyOpenSsh)
            put("fingerprintSha256", generated.fingerprintSha256)
            put("hardwareBacked", false)
            put("note", "Private key stored encrypted in the key store and not returned. Deploy it with deploy_key.")
        }
    }

    /**
     * Hardware branch of generate_ssh_key: ECDSA P-256 inside the device's
     * secure chip (StrongBox preferred, TEE fallback — the source decides).
     * Thin delegation: the minting flow (alias minting + collision probe,
     * the hardwareAlias storage convention, orphan-alias cleanup) is the
     * repository's SSOT, shared with the Keys screen's generate flow.
     */
    private suspend fun generateHardwareKey(label: String, args: JSONObject): JSONObject {
        if (args.optString("keyType").isNotBlank()) {
            throw McpError(
                -32602,
                "hardwareBacked keys are always ecdsa-sha2-nistp256 — omit keyType",
            )
        }
        val source = hardwareKeys
            ?: throw McpError(-32603, "hardware-backed key generation is unavailable in this build")
        // Same gate as the Keys screen: chip keys authorize every signature
        // with a BIOMETRIC_STRONG CryptoObject, so without one enrolled the
        // key could never sign. Refuse at generation, naming the remedy.
        if (biometricAuthenticator?.canAuthorizeWithStrongBiometrics() == false) {
            throw McpError(
                -32603,
                "hardware-backed keys need a strong biometric (Class 3 fingerprint or " +
                    "face) enrolled on this device — enroll one, or generate a software key",
            )
        }
        val key = sshKeyRepository.generateHardwareKey(label)
        return JSONObject().apply {
            put("keyId", key.id)
            put("label", label)
            put("keyType", key.keyType)
            put("publicKeyOpenSsh", key.publicKeyOpenSsh)
            put("fingerprintSha256", key.fingerprintSha256)
            put("hardwareBacked", true)
            put("note", "The private half lives in the device's secure chip and never leaves it — " +
                "it cannot be exported or backed up, and every sign-in asks for a biometric on the phone. " +
                "Deploy the public half with deploy_key.")
        }
    }

    private suspend fun deployKey(args: JSONObject): JSONObject {
        val exec = headlessSshExec
            ?: throw McpError(-32603, "deploy_key is unavailable in this build")
        val profileId = args.optString("profileId").ifBlank {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val keyId = args.optString("keyId").ifBlank {
            throw McpError(-32602, "Missing required argument: keyId")
        }
        val timeoutMs = args.optLong("timeoutMs", 30_000L).coerceIn(1_000L, 300_000L)
        val key = sshKeyRepository.getById(keyId)
            ?: throw McpError(-32603, "Unknown key: $keyId")
        val pubLine = key.publicKeyOpenSsh.trim()
        if (pubLine.isEmpty()) {
            throw McpError(-32603, "Key $keyId has no public-key line to deploy")
        }
        // Single-quoted shell literal; the only ' is escaped as '\''.
        val shellPub = pubLine.replace("'", "'\\''")
        val command =
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                "touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
                "grep -qxF '$shellPub' ~/.ssh/authorized_keys || " +
                "echo '$shellPub' >> ~/.ssh/authorized_keys"
        val outcome = exec.run(profileId, command, timeoutMs)
        val succeeded = !outcome.exec.timedOut && outcome.exec.exitStatus == 0
        return JSONObject().apply {
            put("profileId", profileId)
            put("keyId", keyId)
            put("fingerprintSha256", key.fingerprintSha256)
            put("exitCode", if (outcome.exec.timedOut) JSONObject.NULL else outcome.exec.exitStatus)
            put("stderr", outcome.exec.stderr.takeLast(4000))
            put("timedOut", outcome.exec.timedOut)
            put("reusedLiveConnection", outcome.reusedLiveConnection)
            put("deployed", succeeded)
            if (!succeeded) {
                put("note", "The shell command failed — inspect stderr. The key was NOT confirmed appended; do not assume success.")
            }
        }
    }
}