package sh.haven.app.agent

import android.util.Log
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.mcp.McpError
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.isSshNetworkError
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact

/**
 * One-shot `command in, output out` execution on a saved SSH profile for the
 * `run_command` MCP tool (#367) — the automation-shaped verb (MacroDroid /
 * Tasker HTTP Request macros, cron-style agents) that the interactive
 * terminal tools (connect_profile → send_terminal_input → read_terminal_
 * snapshot) are the wrong shape for.
 *
 * Two paths, tried in order:
 *
 * 1. **Reuse**: the profile has a CONNECTED session (a live terminal tab or
 *    the MCP carrier) — multiplex an exec channel over that session. Also
 *    the only path for profiles whose credentials can't authenticate
 *    headlessly (FIDO2 hardware key, encrypted key): connect interactively
 *    first, then run_command rides the live connection.
 * 2. **Headless connect**: a fresh throwaway [SshClient], resolved with the
 *    same non-interactive credential rules as the MCP-tunnel carrier
 *    ([HeadlessSshAuth]), fail-closed TOFU (an unknown or changed host key
 *    is refused, never silently trusted — trust is established by
 *    connecting the profile interactively once, #5), exec, disconnect.
 *
 * Not covered (v1): port-knock/SPA-guarded profiles and tunnel-routed
 * profiles on the headless path — connect those interactively and let the
 * reuse path serve them.
 */
@Singleton
class HeadlessSshExec @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val sshSessionManager: SshSessionManager,
    private val sshKeyRepository: SshKeyRepository,
    private val sshIdentityRepository: SshIdentityRepository,
    private val hostKeyVerifier: HostKeyVerifier,
    private val hostRediscovery: sh.haven.feature.connections.HostRediscovery,
) {
    /** Test seam — unit tests swap this to avoid opening real sockets. */
    internal var clientFactory: () -> SshClient = { SshClient() }

    data class Outcome(val exec: ExecResult, val reusedLiveConnection: Boolean)

    /**
     * Run [command] on [profileId], bounded by [timeoutMs] (a timeout
     * force-closes the exec channel and returns the partial output with
     * `timedOut = true` rather than failing). Throws [McpError] with an
     * actionable message on every non-exec failure.
     */
    suspend fun run(profileId: String, command: String, timeoutMs: Long): Outcome {
        val stored = connectionRepository.getById(profileId)
            ?: throw McpError(-32602, "Unknown connection profile: $profileId (see list_connections)")
        if (!stored.isSsh) {
            throw McpError(-32602, "Profile '${stored.label}' is ${stored.connectionType}, not SSH — run_command needs an SSH profile")
        }

        sshSessionManager.getSshClientForProfile(profileId)?.let { live ->
            try {
                return Outcome(live.execCommand(command, timeoutMs), reusedLiveConnection = true)
            } catch (e: IllegalStateException) {
                // "Not connected": the session status raced the transport
                // (e.g. a mosh session whose bootstrap SSH client is gone).
                // Fall through to a fresh headless connect.
                Log.i(TAG, "Live client for '${LogRedact.of(stored.label)}' not usable (${e.message}) — trying headless connect")
            }
        }

        val profile = sshIdentityRepository.applyTo(stored)
        val auth = HeadlessSshAuth.resolve(profile, sshKeyRepository)
            ?: throw McpError(
                -32603,
                "Profile '${profile.label}' has no headless-usable credential (needs a stored password or an " +
                    "unencrypted non-FIDO2 key). Connect it in Haven first — run_command reuses a live connection.",
            )
        val config = ConnectionConfig(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            authMethod = auth,
            sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
            addressFamily = ConnectionConfig.AddressFamily.valueOf(profile.addressFamilyEnum.name),
            bindAddress = profile.bindAddress,
            reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = false),
        )
        val client = clientFactory()
        try {
            val hostKeyEntry = try {
                client.connect(config, trustedHostCaKeys = hostKeyVerifier.trustedHostCaKeys())
            } catch (e: Exception) {
                // #376: a network failure on a private address may just mean
                // DHCP moved the device — follow its host key once, so
                // automations (MacroDroid run_command against a hotspot
                // client) survive address rotation without macro changes.
                val newHost = if (e.isSshNetworkError()) {
                    runCatching { hostRediscovery.rediscover(profile) }.getOrNull()
                } else null
                if (newHost == null) {
                    throw McpError(-32603, "Connect to '${profile.label}' failed: ${e.message ?: e.cause?.message ?: e.javaClass.simpleName}")
                }
                Log.i(TAG, "'${LogRedact.of(profile.label)}' host rediscovered ${LogRedact.of(profile.host)} → ${LogRedact.of(newHost)} — retrying")
                try {
                    client.connect(config.copy(host = newHost), trustedHostCaKeys = hostKeyVerifier.trustedHostCaKeys())
                } catch (e2: Exception) {
                    throw McpError(-32603, "Connect to '${profile.label}' failed after host rediscovery ($newHost): ${e2.message ?: e2.javaClass.simpleName}")
                }
            }
            when (if (hostKeyEntry == null) HostKeyResult.Trusted else hostKeyVerifier.verify(hostKeyEntry)) {
                is HostKeyResult.Trusted -> { /* ok */ }
                is HostKeyResult.NewHost -> throw McpError(
                    -32603,
                    "Unknown host key for '${profile.label}' — refusing (fail-closed TOFU). " +
                        "Connect the profile interactively once to trust the host, then retry.",
                )
                is HostKeyResult.KeyChanged -> throw McpError(
                    -32603,
                    "HOST KEY CHANGED for '${profile.label}' — refusing (possible MITM). " +
                        "Reconnect interactively in Haven to review the new key.",
                )
            }
            return Outcome(client.execCommand(command, timeoutMs), reusedLiveConnection = false)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private companion object {
        const val TAG = "HeadlessSshExec"
    }
}

/**
 * Resolve a non-interactive [ConnectionConfig.AuthMethod] for a profile, or
 * null if authenticating would require a UI prompt (encrypted/biometric key,
 * FIDO2 hardware key, or no stored credential at all). FIDO2 (`sk-`) and
 * encrypted keys are excluded deliberately — nothing is present to answer
 * their touch/passphrase prompts on a headless connect.
 *
 * Shared by [HeadlessSshExec] and [McpTunnelManager] so the two headless
 * connectors can never drift on what counts as a usable credential.
 */
internal object HeadlessSshAuth {
    suspend fun resolve(
        profile: ConnectionProfile,
        sshKeyRepository: SshKeyRepository,
    ): ConnectionConfig.AuthMethod? {
        val keyId = profile.keyId
        if (keyId != null) {
            val key = sshKeyRepository.getById(keyId) ?: return null
            if (key.keyType.startsWith("sk-") || key.isEncrypted) return null
            val keyBytes = sshKeyRepository.getDecryptedKeyBytes(keyId) ?: return null
            val certBytes = sshKeyRepository.getCertificateBytes(keyId)
            return ConnectionConfig.AuthMethod.PrivateKey(
                keyBytes = SshKeyExporter.toPem(keyBytes, key.keyType),
                certificateBytes = certBytes,
            )
        }
        // No explicit key — try every stored unencrypted, non-FIDO key.
        val usableKeys = sshKeyRepository.getAllDecrypted()
            .filter { !it.isEncrypted && !it.keyType.startsWith("sk-") }
        if (usableKeys.isNotEmpty()) {
            return ConnectionConfig.AuthMethod.PrivateKeys(
                keys = usableKeys.map {
                    ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                        label = it.label,
                        keyBytes = SshKeyExporter.toPem(it.privateKeyBytes, it.keyType),
                        certificateBytes = it.certificateBytes, // CA-only servers (#185)
                    )
                },
            )
        }
        val password = profile.sshPassword
        if (!password.isNullOrEmpty()) {
            return ConnectionConfig.AuthMethod.Password(password)
        }
        return null
    }
}
