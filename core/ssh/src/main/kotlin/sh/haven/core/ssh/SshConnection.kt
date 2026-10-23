package sh.haven.core.ssh

import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.ssh.sftp.SftpSession
import java.io.Closeable

/**
 * Engine-neutral view of one live SSH connection (#58, phase 4).
 *
 * [SshSessionManager] holds sessions as `SshConnection` rather than the
 * concrete JSch [SshClient], so a future sshlib-backed implementation can
 * enter the session pool without the manager (or the reconnect loop)
 * knowing which engine it is. New connections are built through
 * [SshConnectionFactory] — the single place the engine is chosen.
 *
 * This is the portable contract: connect/auth, a shell channel, exec, an
 * [SftpSession], port forwarding, liveness and teardown. Engine-specific
 * escape hatches that only JSch-era features use — the raw
 * `com.jcraft.jsch.ChannelSftp`, the underlying `com.jcraft.jsch.Session`
 * (jump hosts, SCP) — deliberately stay on [SshClient] and are reached by
 * narrowing, so those unported paths cannot leak onto the neutral seam.
 * The shell channel is still JSch-shaped ([ShellChannel]/[ChannelShell]);
 * that neutralisation is phase 5.
 */
interface SshConnection : Closeable {

    /** Set before connecting with a FidoKey auth method. */
    var fidoAuthenticator: FidoAuthenticator?

    /**
     * Set before connecting with a [ConnectionConfig.AuthMethod.HardwareKey]
     * (security-chip key). Supplies the Keystore Signature + biometric
     * ceremony the identity signs through; the same wiring is carried across
     * reconnects (see SshSessionManager).
     */
    var hardwareKeySigner: HardwareSshKeySigner?

    /**
     * Set before connecting with a ProviderKey auth method (#487). Supplies a
     * client for the app holding the key; a [android.content.Context] has to
     * come from outside, since binding to that app's service needs one.
     */
    var openKeychainClients: OpenKeychainClientFactory?

    /** Set before connect() to capture verbose SSH protocol output. */
    var verboseLogger: SshVerboseLogger?

    /** True while the underlying transport is connected. */
    val isConnected: Boolean

    /**
     * True when the last connect dialed through a proxy or jump chain rather
     * than direct TCP. Consulted before routing SFTP to the sshlib engine,
     * which needs a direct path for its own connection.
     */
    val connectedViaProxy: Boolean

    /** True when the last connect verified the host via a trusted CA host cert (#133). */
    val hostVerifiedByCa: Boolean

    suspend fun connect(
        config: ConnectionConfig,
        connectTimeoutMs: Int = 10_000,
        proxy: HavenProxy? = null,
        keyboardInteractivePrompter: KeyboardInteractivePrompter? = null,
        totpCodeProvider: (() -> String)? = null,
        confirmOtp: Boolean = false,
        preConnect: (suspend () -> Unit)? = null,
        trustedHostCaKeys: List<String> = emptyList(),
    ): KnownHostEntry?

    fun connectBlocking(
        config: ConnectionConfig,
        connectTimeoutMs: Int = 10_000,
        proxy: HavenProxy? = null,
        keyboardInteractivePrompter: KeyboardInteractivePrompter? = null,
        totpCodeProvider: (() -> String)? = null,
        confirmOtp: Boolean = false,
        preConnect: (() -> Unit)? = null,
        trustedHostCaKeys: List<String> = emptyList(),
    ): KnownHostEntry?

    suspend fun isAlive(timeoutMs: Long = 5_000L): Boolean

    fun openShellChannel(
        term: String = "xterm-256color",
        cols: Int = 80,
        rows: Int = 24,
    ): ShellChannel

    /**
     * Open the terminal-facing channel for a session. When [remoteCommand] is
     * null/blank this is an interactive shell (== [openShellChannel]); when set,
     * it's an SSH `exec` request for that command (the RemoteCommand path, e.g.
     * `tmux new -A -s work`), so it runs before shell-startup files instead of
     * racing them. [requestPty] allocates a PTY (required by tmux; ignored for a
     * plain shell). Both engines implement this: the experimental sshlib engine
     * gained the exec path once sshlib 0.4.0 surfaced exit status (#58,
     * connectbot/cbssh#232).
     */
    fun openTerminalChannel(
        remoteCommand: String?,
        requestPty: Boolean = true,
        term: String = "xterm-256color",
        cols: Int = 80,
        rows: Int = 24,
    ): ShellChannel

    fun openSftpSession(): SftpSession

    suspend fun execCommand(command: String, timeoutMs: Long? = null): ExecResult

    fun setPortForwardingL(bindAddress: String, localPort: Int, remoteHost: String, remotePort: Int): Int
    fun setPortForwardingR(bindAddress: String, remotePort: Int, localHost: String, localPort: Int)
    fun delPortForwardingL(bindAddress: String, localPort: Int)
    fun delPortForwardingR(remotePort: Int)
    fun setPortForwardingDynamic(bindAddress: String, bindPort: Int): Int
    fun delPortForwardingDynamic(bindAddress: String, bindPort: Int)

    fun disconnect()
}
