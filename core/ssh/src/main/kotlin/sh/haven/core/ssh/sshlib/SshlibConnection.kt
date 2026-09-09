package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshClient as SshlibClient
import org.connectbot.sshlib.transport.TransportFactory
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.HavenProxy
import sh.haven.core.ssh.KeyboardInteractiveAnswerer
import sh.haven.core.ssh.KeyboardInteractivePrompter
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.ShellChannel
import sh.haven.core.ssh.SshConnection
import sh.haven.core.ssh.SshIoException
import sh.haven.core.ssh.SshVerboseLogger
import sh.haven.core.ssh.sftp.SftpSession
import java.util.Base64

/**
 * Appended to the error a user actually sees when a session channel cannot be
 * opened, so the failure carries a way out instead of looking like a random
 * disconnect.
 *
 * This used to describe connectbot/cbssh#238 — sshlib registered a channel
 * under the server's remote number and never released it on close, so a reused
 * number was rejected and took the connection with it. sshlib 0.4.1 fixes that,
 * and [SshlibSftpConnector] turns off `autoDisconnectOnLastChannelClose` so the
 * connection also survives its last channel closing. What remains is the
 * ordinary case: a server that refuses another channel, usually a per-session
 * channel limit.
 */
internal const val SSHLIB_CHANNEL_LIMITATION =
    "The server refused another channel on this connection — some servers cap how many a single " +
        "session may open. If it keeps happening, switch this profile's SSH engine back to JSch " +
        "(remove 'HavenSshEngine sshlib' from its SSH Options)."

/**
 * A whole connection on the sshlib engine (#58) — the **experimental** opt-in
 * alternative to the JSch [sh.haven.core.ssh.SshClient].
 *
 * Assembles the per-capability blocks that landed across phases 1–6 plus exec
 * (phase 3, unblocked by sshlib 0.4.0): [SshlibSftpConnector.dialAndAuth] to
 * dial and authenticate, [SshlibShell] for shell/PTY channels, [SshlibExec] for
 * one-shot exec, [SshlibSftpSession] for the SFTP subsystem, and
 * [SshlibPortForwarders] for L/R/dynamic forwards — all over ONE connection,
 * so the channels share a transport the way the JSch engine's do.
 *
 * **Honest by construction.** Anything this engine cannot actually do is
 * refused at [connect] via [SshlibSftpConnector.unsupportedReason] (FIDO2 keys,
 * OpenSSH certificates) rather than quietly doing something else.
 *
 * Jump hosts, tunnels and SOCKS/HTTP proxies all arrive as a [HavenProxy] and
 * ride a [JschProxyTransportFactory] — one adapter from JSch's `Proxy` to
 * sshlib's `Transport`, so every tunnel type Haven builds works here without
 * per-type code.
 *
 * Keyboard-interactive rounds (2FA prompts, TOTP auto-fill, and servers that
 * route "Password:" through KI) go through the same
 * [sh.haven.core.ssh.KeyboardInteractiveAnswerer] the JSch engine uses, so both
 * engines auto-answer and pre-fill by identical rules.
 *
 * **Host keys** follow the engine-neutral contract: the key is accepted and
 * returned from [connect] as a [KnownHostEntry] so the caller runs Haven's
 * normal TOFU prompt — the same accept-then-verify order JSch uses with
 * `StrictHostKeyChecking=no`. (The dedicated SFTP dial is stricter because a
 * prior interactive connect had already established trust; a whole connection
 * has no such predecessor, so it would otherwise refuse every new host.)
 */
internal class SshlibConnection : SshConnection {

    @Volatile
    private var client: SshlibClient? = null

    @Volatile
    private var transportUp = false

    private var forwarders: SshlibPortForwarders? = null

    /** Unused on this engine — FIDO2 auth is refused by the capability gate. */
    override var fidoAuthenticator: FidoAuthenticator? = null

    /** Unused on this engine — provider keys are refused by the same gate (#487). */
    override var openKeychainClients: sh.haven.core.ssh.OpenKeychainClientFactory? = null

    /** Unused on this engine — sshlib has no JSch-style protocol logger hook. */
    override var verboseLogger: SshVerboseLogger? = null

    override val isConnected: Boolean get() = transportUp && client != null

    /**
     * True when the live connection was dialed through a [HavenProxy] (tunnel,
     * SOCKS/HTTP proxy, or a jump host). `SshSessionManager.dialSftp` reads this
     * to keep the dedicated SFTP dial — which goes direct — off a tunnelled
     * profile, so it has to be real, not a constant.
     */
    @Volatile
    override var connectedViaProxy: Boolean = false
        private set

    /** Always false: OpenSSH certificates are refused, so no host is CA-verified on this engine. */
    override val hostVerifiedByCa: Boolean = false

    override suspend fun connect(
        config: ConnectionConfig,
        connectTimeoutMs: Int,
        proxy: HavenProxy?,
        keyboardInteractivePrompter: KeyboardInteractivePrompter?,
        totpCodeProvider: (() -> String)?,
        confirmOtp: Boolean,
        preConnect: (suspend () -> Unit)?,
        trustedHostCaKeys: List<String>,
    ): KnownHostEntry? {
        disconnect()
        // Jump and proxy dials are supported now: both arrive as a HavenProxy
        // and ride a JschProxyTransportFactory, so neither is passed here.
        // sshlib 0.4.2 has no local-bind API on its transport (KtorTcpTransport
        // takes host+ipVersion only), so a bound profile is refused up front
        // rather than silently unbound (#636).
        if (!config.bindAddress.isNullOrBlank()) {
            throw SshIoException(
                "sshlib engine (experimental) does not support binding the " +
                    "outgoing socket to a local address (ssh -b) — " +
                    "set this profile's SSH engine back to JSch",
            )
        }
        SshlibSftpConnector.unsupportedReason(config)?.let { reason ->
            throw SshIoException(
                "sshlib engine (experimental) does not support $reason — " +
                    "set this profile's SSH engine back to JSch",
            )
        }
        preConnect?.invoke()
        val gate = CapturingHostKeyGate(config.host, config.port)
        val connected = SshlibSftpConnector.dialAndAuth(
            config,
            gate,
            connectTimeoutMs.toLong(),
            ki = keyboardInteractivePrompter?.let { prompter ->
                KeyboardInteractiveAnswerer(
                    destination = "${config.username}@${config.host}:${config.port}",
                    prompter = prompter,
                    fallbackPassword = savedPassword(config),
                    totpCodeProvider = totpCodeProvider,
                    autoSubmit = !confirmOtp,
                )
            },
            proxy = proxy,
        )
        client = connected
        forwarders = SshlibPortForwarders(connected)
        connectedViaProxy = proxy != null
        transportUp = true
        return gate.seen
    }

    override fun connectBlocking(
        config: ConnectionConfig,
        connectTimeoutMs: Int,
        proxy: HavenProxy?,
        keyboardInteractivePrompter: KeyboardInteractivePrompter?,
        totpCodeProvider: (() -> String)?,
        confirmOtp: Boolean,
        preConnect: (() -> Unit)?,
        trustedHostCaKeys: List<String>,
    ): KnownHostEntry? = runBlocking {
        connect(
            config = config,
            connectTimeoutMs = connectTimeoutMs,
            proxy = proxy,
            keyboardInteractivePrompter = keyboardInteractivePrompter,
            totpCodeProvider = totpCodeProvider,
            confirmOtp = confirmOtp,
            preConnect = preConnect?.let { blocking -> { blocking() } },
            trustedHostCaKeys = trustedHostCaKeys,
        )
    }

    /**
     * Liveness by round trip: opening and immediately closing a session channel
     * proves the transport still answers, which a cached flag cannot.
     */
    override suspend fun isAlive(timeoutMs: Long): Boolean {
        val connected = client ?: return false
        if (!transportUp) return false
        return withTimeoutOrNull(timeoutMs) {
            runCatching {
                val probe = connected.openSession() ?: return@runCatching false
                probe.close()
                true
            }.getOrDefault(false)
        } ?: false
    }

    override fun openShellChannel(term: String, cols: Int, rows: Int): ShellChannel =
        openTerminalChannel(remoteCommand = null, requestPty = true, term = term, cols = cols, rows = rows)

    override fun openTerminalChannel(
        remoteCommand: String?,
        requestPty: Boolean,
        term: String,
        cols: Int,
        rows: Int,
    ): ShellChannel = runBlocking {
        val connected = requireClient()
        val session = connected.openSession()
            ?: throw SshIoException("sshlib: server refused a session channel. $SSHLIB_CHANNEL_LIMITATION")
        try {
            if (remoteCommand.isNullOrBlank()) {
                SshlibShell.requestShell(session, term, cols, rows)
            } else {
                // RemoteCommand path: exec runs before shell startup files, so a
                // `tmux new -A -s work` cannot race an auto-tmux hook.
                if (requestPty && !session.requestPty(term, cols, rows)) {
                    throw SshIoException("sshlib: server rejected PTY request")
                }
                if (!session.requestExec(remoteCommand)) {
                    throw SshIoException("sshlib: server rejected exec request: ${remoteCommand.take(64)}")
                }
            }
        } catch (t: Throwable) {
            runCatching { session.close() }
            throw t
        }
        // ownsClient = false: closing one terminal must not drop the shared
        // connection carrying this profile's other channels and forwards.
        SshlibShell.open(connected, session, term, cols, rows, ownsClient = false)
    }

    /**
     * A direct-tcpip transport to [host]:[port] over THIS connection, so a
     * profile using this session as its jump host dials the target natively —
     * no JSch proxy, no stream bridging. Wired up by
     * `SshSessionManager.createProxyJump`.
     */
    internal fun openJumpTransport(host: String, port: Int): TransportFactory =
        // null means the jump connection is gone or unauthenticated — a real
        // failure, not something to pass along as a nullable factory.
        requireClient().openDirectTcpipTransport(host, port)
            ?: throw SshIoException("sshlib: jump host connection is not authenticated — cannot reach $host:$port")

    override fun openSftpSession(): SftpSession = runBlocking {
        val connected = requireClient()
        val sftp = when (val result = connected.openSftp()) {
            is SftpResult.Success -> result.value
            is SftpResult.ServerError ->
                throw SshIoException("sshlib: SFTP subsystem rejected: ${result.message}")
            is SftpResult.ProtocolError ->
                throw SshIoException("sshlib: SFTP open failed: ${result.message}")
            is SftpResult.IoError ->
                throw SshIoException("sshlib: SFTP open failed: ${result.cause.message}", result.cause)
        }
        SshlibSftpSession(connected, sftp, ownsClient = false)
    }

    override suspend fun execCommand(command: String, timeoutMs: Long?): ExecResult =
        SshlibExec.exec(requireClient(), command, timeoutMs)

    override fun setPortForwardingL(
        bindAddress: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): Int = requireForwarders().setLocal(bindAddress, localPort, remoteHost, remotePort)

    override fun setPortForwardingR(
        bindAddress: String,
        remotePort: Int,
        localHost: String,
        localPort: Int,
    ) = requireForwarders().setRemote(bindAddress, remotePort, localHost, localPort)

    override fun delPortForwardingL(bindAddress: String, localPort: Int) =
        requireForwarders().delLocal(bindAddress, localPort)

    override fun delPortForwardingR(remotePort: Int) = requireForwarders().delRemote(remotePort)

    override fun setPortForwardingDynamic(bindAddress: String, bindPort: Int): Int =
        requireForwarders().setDynamic(bindAddress, bindPort)

    override fun delPortForwardingDynamic(bindAddress: String, bindPort: Int) =
        requireForwarders().delDynamic(bindAddress, bindPort)

    override fun disconnect() {
        runCatching { forwarders?.closeAll() }
        forwarders = null
        val connected = client
        client = null
        transportUp = false
        connectedViaProxy = false
        runCatching { runBlocking { connected?.disconnect() } }
    }

    override fun close() = disconnect()

    private fun requireClient(): SshlibClient =
        client ?: throw IllegalStateException("sshlib: not connected")

    /**
     * The profile's stored password, so a single-prompt "Password:" KI round is
     * answered without asking the user to retype it — the same courtesy the
     * JSch engine's `fallbackKiPassword` does.
     */
    private fun savedPassword(config: ConnectionConfig): CharArray? {
        val methods = when (val method = config.authMethod) {
            is ConnectionConfig.AuthMethod.Multi -> method.methods
            else -> listOf(method)
        }
        return methods.filterIsInstance<ConnectionConfig.AuthMethod.Password>()
            .firstOrNull()
            ?.password
    }

    private fun requireForwarders(): SshlibPortForwarders =
        forwarders ?: throw IllegalStateException("sshlib: not connected")

    /**
     * Accepts the server's host key and records it, so [connect] can hand it
     * back for Haven's TOFU check. Deliberately permissive at this layer: the
     * caller is the one that decides trust, exactly as on the JSch engine.
     */
    private class CapturingHostKeyGate(
        private val hostname: String,
        private val port: Int,
    ) : org.connectbot.sshlib.HostKeyVerifier {

        @Volatile
        var seen: KnownHostEntry? = null
            private set

        override suspend fun verify(key: PublicKey): Boolean {
            seen = KnownHostEntry(
                hostname = hostname,
                port = port,
                keyType = key.type,
                publicKeyBase64 = Base64.getEncoder().encodeToString(key.encoded),
            )
            return true
        }
    }
}
