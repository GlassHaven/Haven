package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthHandler
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient as SshlibClient
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HavenProxy
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.KeyboardInteractiveAnswerer
import sh.haven.core.ssh.KeyboardInteractiveChallenge
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshIoException
import java.util.Base64

/**
 * Dials a dedicated sshlib connection for SFTP (#58, phase 1).
 *
 * Capability-gated: [unsupportedReason] decides BEFORE dialing whether this
 * config can run on sshlib; a non-null reason means the caller falls back to
 * JSch and logs why. Dial/auth failures after that decision throw
 * [SshIoException] — they are real errors, never a silent fallback.
 *
 * Host keys are fail-closed: the sshlib connection only accepts a key that
 * Haven's [HostKeyVerifier] already reports as [HostKeyResult.Trusted]. The
 * interactive JSch connect that preceded this dial established TOFU trust,
 * so no prompting path is needed here.
 */
internal object SshlibSftpConnector {

    /**
     * Why this config cannot use the sshlib engine yet, or null when it can.
     * Phase-1 scope: direct-TCP dials with password auth, plain private keys
     * (unencrypted or passphrase-carrying), or the try-any-key pool without
     * OpenSSH certificates.
     */
    /**
     * [hasJump] / [hasProxy] gate the DEDICATED SFTP dial only, and must keep
     * doing so: that dial connects straight to `config.host` with no proxy, so
     * running it for a tunnelled profile would bypass the tunnel and — with a
     * private-range or duplicated hostname — could land on a different machine
     * entirely. A whole [SshlibConnection] carries the proxy itself (see
     * [JschProxyTransportFactory]) and so passes neither.
     */
    fun unsupportedReason(
        config: ConnectionConfig,
        hasJump: Boolean = false,
        hasProxy: Boolean = false,
    ): String? {
        if (hasJump) return "jump-host connections"
        if (hasProxy) return "proxied connections"
        return unsupportedAuthReason(config.authMethod)
    }

    private fun unsupportedAuthReason(method: ConnectionConfig.AuthMethod): String? = when (method) {
        is ConnectionConfig.AuthMethod.Password -> null
        is ConnectionConfig.AuthMethod.PrivateKey ->
            if (method.certificateBytes != null) "OpenSSH certificate auth" else null
        is ConnectionConfig.AuthMethod.PrivateKeys ->
            if (method.keys.any { it.certificateBytes != null }) "OpenSSH certificate auth" else null
        is ConnectionConfig.AuthMethod.FidoKey -> "FIDO2 hardware keys"
        // Same reason as FIDO: the signing delegation lives on the JSch
        // engine only, so this connection falls back rather than
        // failing to authenticate with a key that is perfectly valid (#487).
        is ConnectionConfig.AuthMethod.ProviderKey -> "keys held in another app"
        // Chains run as their sub-methods in order plus a keyboard-interactive
        // follow-up (see authenticate); a sub-method we cannot do still gates.
        is ConnectionConfig.AuthMethod.Multi ->
            method.methods.firstNotNullOfOrNull { unsupportedAuthReason(it) }
    }

    /**
     * Dial + authenticate a sshlib connection, returning the connected client.
     * The caller owns the connection and must [org.connectbot.sshlib.SshClient.disconnect]
     * it. Callers must have cleared [unsupportedReason] first.
     */
    suspend fun dialAndAuth(
        config: ConnectionConfig,
        hostKeyVerifier: HostKeyVerifier,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    ): SshlibClient = dialAndAuth(
        config,
        TrustedOnlyVerifier(hostKeyVerifier, config.host, config.port),
        connectTimeoutMs,
    )

    /**
     * As [dialAndAuth], but with the sshlib-level host-key gate supplied
     * directly so a caller can apply a different trust policy. The SFTP path
     * uses the fail-closed [TrustedOnlyVerifier] above (trust was already
     * established by the interactive JSch connect that preceded it);
     * [SshlibConnection] has no such predecessor, so it passes a gate that
     * captures the key and hands it back for Haven's normal TOFU prompt —
     * the same accept-then-verify order the JSch engine uses.
     */
    suspend fun dialAndAuth(
        config: ConnectionConfig,
        hostKeyGate: org.connectbot.sshlib.HostKeyVerifier,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        ki: KeyboardInteractiveAnswerer? = null,
        proxy: HavenProxy? = null,
    ): SshlibClient {
        // A proxied dial must NOT resolve the target locally: the name is
        // resolved at the far end of the tunnel (and for .onion cannot be
        // resolved here at all) — the same rule the JSch engine follows.
        val host = if (proxy != null) config.host else SshClient.resolveHost(config.host, config.addressFamily, config.port)
        val client = SshlibClient(
            org.connectbot.sshlib.SshClientConfig {
                this.host = host
                this.port = config.port
                this.hostKeyVerifier = hostKeyGate
                proxy?.let {
                    // A sshlib jump session gives us a native direct-tcpip
                    // transport; anything else is a JSch Proxy we adapt.
                    this.transportFactory = it.sshlibJump?.invoke(host, config.port)
                        ?: JschProxyTransportFactory(
                            proxy = requireNotNull(it.jschProxy) { "HavenProxy carries neither shape" },
                            host = host,
                            port = config.port,
                            connectTimeoutMs = connectTimeoutMs.toInt(),
                        )
                }
                // Rekey thresholds are sshlib's defaults (1 GiB / 1 h) again.
                // 0.3.1 had client-initiated rekey broken both ways — a
                // byte-limit rekey mid-transfer killed the channel, an interval
                // rekey wedged an idle session (connectbot/cbssh#231) — so both
                // were pushed out of reach. 0.4.0 fixes it (strict-KEX packet
                // numbering); the SshlibCapabilitySpikeTest probes flipped, so
                // the mitigation is gone and keys rotate normally again.

                // Haven owns this connection's lifetime and multiplexes over it
                // — an interactive shell, an exec for a command, SFTP on the
                // same transport, another exec later. sshlib's default is to
                // disconnect once the last channel closes, which is right for a
                // one-shot `ssh host command` client and fatal here: the first
                // channel to leave the registry empty takes the connection with
                // it, so only one session ever works (connectbot/cbssh#238).
                // Haven closes the connection explicitly when it is done.
                this.autoDisconnectOnLastChannelClose = false
            },
        )
        try {
            when (val result = withTimeout(connectTimeoutMs) { client.connect() }) {
                is ConnectResult.Success -> Unit
                is ConnectResult.HostKeyRejected -> throw SshIoException(
                    "sshlib: host key for ${config.host} is not in Haven's trusted known hosts",
                )
                is ConnectResult.AlgorithmMismatch ->
                    throw SshIoException("sshlib: algorithm negotiation failed: ${result.message}")
                is ConnectResult.TransportError ->
                    throw SshIoException("sshlib: connect failed: ${result.cause.message}", result.cause)
                is ConnectResult.ProtocolError ->
                    throw SshIoException("sshlib: protocol error: ${result.message}", result.cause)
            }
            authenticate(client, config, ki)
            return client
        } catch (t: Throwable) {
            try { client.disconnect() } catch (_: Exception) { /* best effort */ }
            throw t
        }
    }

    /**
     * Dial + authenticate + open the SFTP subsystem. The returned session owns
     * the connection. Callers must have cleared [unsupportedReason] first.
     */
    suspend fun connect(
        config: ConnectionConfig,
        hostKeyVerifier: HostKeyVerifier,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    ): SshlibSftpSession {
        val client = dialAndAuth(config, hostKeyVerifier, connectTimeoutMs)
        try {
            val sftp = when (val r = client.openSftp()) {
                is org.connectbot.sshlib.SftpResult.Success -> r.value
                is org.connectbot.sshlib.SftpResult.ServerError ->
                    throw SshIoException("sshlib: SFTP subsystem rejected: ${r.message}")
                is org.connectbot.sshlib.SftpResult.ProtocolError ->
                    throw SshIoException("sshlib: SFTP open failed: ${r.message}")
                is org.connectbot.sshlib.SftpResult.IoError ->
                    throw SshIoException("sshlib: SFTP open failed: ${r.cause.message}", r.cause)
            }
            return SshlibSftpSession(client, sftp)
        } catch (t: Throwable) {
            try { client.disconnect() } catch (_: Exception) { /* best effort */ }
            throw t
        }
    }

    /**
     * Run the profile's key(s), then let sshlib's [org.connectbot.sshlib.AuthHandler]
     * flow drive whatever password / keyboard-interactive the SERVER says it
     * wants.
     *
     * The split is deliberate. Keys go through [SshlibClient.authenticatePublicKey]
     * because that path negotiates `rsa-sha2-*` from the server's `server-sig-algs`
     * and does host-bound auth; the handler flow exposes neither, so routing keys
     * through it would regress RSA. Everything else goes through the handler flow
     * because it probes with `none` FIRST and only sends a method the server
     * advertised — sending an unadvertised one is what broke here: a MINA sshd
     * offering only keyboard-interactive answered a `password` request with a
     * failure carrying NO method list, so a "then try KI" follow-up could not
     * even tell KI was available.
     *
     * Running the flow after a rejected key is also what makes a second factor
     * work — an OpenSSH server with `AuthenticationMethods publickey,keyboard-interactive`
     * answers the accepted key with a failure that still lists KI, which sshlib's
     * public [AuthResult.Failure] cannot distinguish from a flat rejection — and
     * it means [ConnectionConfig.AuthMethod.Multi] needs nothing beyond running
     * its sub-methods in order.
     *
     * [ki] is null when no prompter was supplied (the dedicated SFTP dial), in
     * which case KI prompts are answered with the saved password — no UI, which
     * covers the common "server routes Password: through KI" case.
     */
    private suspend fun authenticate(
        client: SshlibClient,
        config: ConnectionConfig,
        ki: KeyboardInteractiveAnswerer? = null,
    ) {
        val methods = when (val method = config.authMethod) {
            is ConnectionConfig.AuthMethod.Multi -> method.methods
            else -> listOf(method)
        }
        var last: AuthResult = AuthResult.Failure(emptySet())
        for (method in methods.filterNot { it is ConnectionConfig.AuthMethod.Password }) {
            last = attemptKey(client, config.username, method)
            if (last is AuthResult.Success) return
        }
        val password = fallbackPassword(methods)
        if (password != null || ki != null) {
            last = client.authenticate(
                config.username,
                PasswordOrKeyboardHandler(password, ki),
            )
            if (last is AuthResult.Success) return
        }
        last.requireSuccess()
    }

    private suspend fun attemptKey(
        client: SshlibClient,
        username: String,
        method: ConnectionConfig.AuthMethod,
    ): AuthResult = when (method) {
        is ConnectionConfig.AuthMethod.PrivateKey ->
            client.authenticatePublicKey(
                username,
                method.keyBytes,
                method.passphrase.takeIf { it.isNotEmpty() }?.let { String(it) },
            )
        is ConnectionConfig.AuthMethod.PrivateKeys -> {
            var last: AuthResult = AuthResult.Failure(emptySet())
            for (entry in method.keys) {
                last = client.authenticatePublicKey(
                    username, entry.keyBytes, entry.passphrase?.let { String(it, Charsets.UTF_8) },
                )
                if (last is AuthResult.Success) break
            }
            last
        }
        // FidoKey and ProviderKey are rejected by unsupportedReason before
        // dialing; Password is filtered out by the caller; nested Multi is not
        // a shape the profile editor can produce.
        is ConnectionConfig.AuthMethod.FidoKey,
        is ConnectionConfig.AuthMethod.ProviderKey,
        is ConnectionConfig.AuthMethod.Multi,
        is ConnectionConfig.AuthMethod.Password,
        -> throw SshIoException("sshlib: unsupported auth method ${method::class.simpleName}")
    }

    /**
     * The password / keyboard-interactive half of auth, as sshlib's
     * [org.connectbot.sshlib.AuthHandler]. Offers no public keys — those are
     * already done by then (see [authenticate]) — so the flow it drives is
     * `none` probe → whichever of KI / password the server advertised.
     */
    private class PasswordOrKeyboardHandler(
        private val password: String?,
        private val ki: KeyboardInteractiveAnswerer?,
    ) : AuthHandler {

        override suspend fun onPublicKeysNeeded(): List<AuthPublicKey> = emptyList()

        override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray? = null

        override suspend fun onPasswordNeeded(): String? = password

        override suspend fun onKeyboardInteractivePrompt(
            name: String,
            instruction: String,
            prompts: List<KeyboardInteractiveCallback.Prompt>,
        ): List<String>? = if (ki != null) {
            ki.answer(
                name = name,
                instruction = instruction,
                prompts = prompts.map {
                    KeyboardInteractiveChallenge.Prompt(text = it.text, echo = it.echo)
                },
            )
        } else {
            // No prompter (the dedicated SFTP dial): answer with the saved
            // password, or decline by returning null so KI ends rather than
            // looping on prompts nobody can answer.
            password?.let { pw -> prompts.map { pw } }
        }
    }

    /** The password a KI round can be auto-answered with, when the profile has one. */
    private fun fallbackPassword(methods: List<ConnectionConfig.AuthMethod>): String? =
        methods.filterIsInstance<ConnectionConfig.AuthMethod.Password>()
            .firstOrNull()
            ?.let { String(it.password) }

    private fun AuthResult.requireSuccess() {
        when (this) {
            is AuthResult.Success -> Unit
            is AuthResult.Failure -> throw SshIoException(
                "sshlib: authentication failed (server allows: ${allowedMethods.joinToString()})",
            )
            is AuthResult.Error -> throw SshIoException("sshlib: authentication error: $message", cause)
        }
    }

    /** sshlib host-key gate that delegates to Haven's TOFU store, fail-closed. */
    private class TrustedOnlyVerifier(
        private val verifier: HostKeyVerifier,
        private val hostname: String,
        private val port: Int,
    ) : org.connectbot.sshlib.HostKeyVerifier {
        override suspend fun verify(key: PublicKey): Boolean {
            val entry = KnownHostEntry(
                hostname = hostname,
                port = port,
                keyType = key.type,
                publicKeyBase64 = Base64.getEncoder().encodeToString(key.encoded),
            )
            return verifier.verify(entry) is HostKeyResult.Trusted
        }
    }

    private const val CONNECT_TIMEOUT_MS = 30_000L
}
