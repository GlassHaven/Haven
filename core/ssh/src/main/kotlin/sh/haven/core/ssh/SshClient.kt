package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.fido.FidoIdentity
import sh.haven.core.fido.SkKeyData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer


private const val TAG = "SshClient"

data class ExecResult(
    val exitStatus: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Wrapper around JSch providing coroutine-based SSH connectivity.
 */
class SshClient : Closeable {
    private val jsch = JSch()
    /** Set before connecting with a FidoKey auth method. */
    var fidoAuthenticator: FidoAuthenticator? = null
    /** Set before connect() to capture verbose SSH protocol output. */
    var verboseLogger: SshVerboseLogger? = null
    private var session: Session? = null
    /** Whether the active session should set agent forwarding on newly opened shell/exec channels. */
    private var agentForwardingEnabled = false

    val isConnected: Boolean
        get() = session?.isConnected == true

    /** The underlying JSch session, for creating ProxyJump tunnels. */
    internal val jschSession: Session?
        get() = session

    /**
     * Connect to an SSH server using the given config.
     * This suspends on Dispatchers.IO.
     * Returns the host key as a [KnownHostEntry] for TOFU verification.
     */
    suspend fun connect(
        config: ConnectionConfig,
        connectTimeoutMs: Int = 10_000,
        proxy: Proxy? = null,
    ): KnownHostEntry = withContext(Dispatchers.IO) {
        disconnect()
        verboseLogger?.let { jsch.setInstanceLogger(it) }

        val resolvedIp = if (proxy != null) config.host else resolveHost(config.host)
        val sess = jsch.getSession(config.username, resolvedIp, config.port)
        if (proxy != null) sess.setProxy(proxy)
        // Accept any key at the JSch level; we verify post-connect ourselves (TOFU)
        sess.setConfig("StrictHostKeyChecking", "no")
        // Disable GSSAPI auth — it causes multi-second timeouts on most servers
        sess.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                sess.setPassword(String(auth.password))
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                jsch.addIdentity(
                    "haven-key",
                    auth.keyBytes,
                    null,
                    auth.passphrase.ifEmpty { null }?.toByteArray(),
                )
            }
            is ConnectionConfig.AuthMethod.PrivateKeys -> {
                auth.keys.forEachIndexed { i, (label, keyBytes) ->
                    jsch.addIdentity("haven-key-$i-$label", keyBytes, null, null)
                }
            }
            is ConnectionConfig.AuthMethod.FidoKey -> {
                val skData = SkKeyData.deserialize(auth.skKeyData)
                Log.d(TAG, "FIDO2 SK key: alg=${skData.algorithmName}, app=${skData.application}")
                val fidoIdentity = FidoIdentity(skData, fidoAuthenticator!!)
                jsch.addIdentity(fidoIdentity, null)
                val currentAlgs = sess.getConfig("PubkeyAcceptedAlgorithms") ?: ""
                val skAlgs = "sk-ssh-ed25519@openssh.com,sk-ecdsa-sha2-nistp256@openssh.com"
                sess.setConfig("PubkeyAcceptedAlgorithms",
                    if (currentAlgs.isNotEmpty()) "$skAlgs,$currentAlgs" else skAlgs)
                Log.d(TAG, "PubkeyAcceptedAlgorithms: ${sess.getConfig("PubkeyAcceptedAlgorithms")}")
            }
        }

        // Apply user SSH options (overrides defaults above)
        config.sshOptions.forEach { (key, value) -> sess.setConfig(key, value) }

        try {
            sess.connect(connectTimeoutMs)
        } catch (e: JSchException) {
            // KEX may have completed before the auth step failed — e.g. encrypted
            // keys tried with null passphrase, MaxAuthTries tripped, or wrong
            // remembered password. In that case JSch already has the server's
            // host key, and the caller needs it to drive TOFU verification so
            // the user sees a fingerprint prompt on first contact instead of
            // just an opaque "Auth fail" error. See GlassOnTin/Haven#75 follow-up.
            val capturedHostKey = tryExtractHostKey(sess, config.host, config.port)
            try { sess.disconnect() } catch (_: Throwable) { /* best effort */ }
            if (capturedHostKey != null) throw HostKeyAuthFailure(capturedHostKey, e)
            throw e
        }
        session = sess
        registerAgentIdentities(config)
        extractHostKey(sess, config.host, config.port)
    }

    /**
     * Open an interactive shell channel on the current SSH session.
     * Must be called after [connect].
     */
    fun openShellChannel(
        term: String = "xterm-256color",
        cols: Int = 80,
        rows: Int = 24,
    ): ChannelShell {
        val sess = session ?: throw IllegalStateException("Not connected")
        val channel = sess.openChannel("shell") as ChannelShell
        channel.setPtyType(term, cols, rows, 0, 0)
        if (agentForwardingEnabled) channel.setAgentForwarding(true)
        channel.connect()
        return channel
    }

    /**
     * Resize the PTY of an open shell channel.
     */
    fun resizeShell(channel: ChannelShell, cols: Int, rows: Int) {
        channel.setPtySize(cols, rows, 0, 0)
    }

    /**
     * Open an SFTP channel on the current SSH session.
     * Must be called after [connect].
     */
    fun openSftpChannel(): ChannelSftp {
        val sess = session ?: throw IllegalStateException("Not connected")
        val channel = sess.openChannel("sftp") as ChannelSftp
        channel.connect()
        return channel
    }

    /**
     * Execute a command on the remote host and return stdout, stderr, and exit status.
     * Must be called after [connect].
     */
    suspend fun execCommand(command: String): ExecResult = withContext(Dispatchers.IO) {
        val sess = session ?: throw IllegalStateException("Not connected")
        val channel = sess.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        if (agentForwardingEnabled) channel.setAgentForwarding(true)
        channel.inputStream = null

        val stdout = channel.inputStream
        val stderr = channel.errStream

        channel.connect()

        val outBytes = stdout.readBytes()
        val errBytes = stderr.readBytes()

        // Wait for channel to close so exitStatus is available
        while (!channel.isClosed) {
            Thread.sleep(50)
        }

        val result = ExecResult(
            exitStatus = channel.exitStatus,
            stdout = outBytes.decodeToString(),
            stderr = errBytes.decodeToString(),
        )
        channel.disconnect()
        result
    }

    /**
     * Connect synchronously (for use on background threads like reconnect).
     * Same as [connect] but without the coroutine wrapper.
     * Returns the host key as a [KnownHostEntry] for TOFU verification.
     */
    fun connectBlocking(config: ConnectionConfig, connectTimeoutMs: Int = 10_000, proxy: Proxy? = null): KnownHostEntry {
        disconnect()
        verboseLogger?.let { jsch.setInstanceLogger(it) }

        val resolvedIp = if (proxy != null) config.host else resolveHost(config.host)
        val sess = jsch.getSession(config.username, resolvedIp, config.port)
        if (proxy != null) sess.setProxy(proxy)
        sess.setConfig("StrictHostKeyChecking", "no")
        sess.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                sess.setPassword(String(auth.password))
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                jsch.addIdentity(
                    "haven-key-${System.nanoTime()}",
                    auth.keyBytes,
                    null,
                    auth.passphrase.ifEmpty { null }?.toByteArray(),
                )
            }
            is ConnectionConfig.AuthMethod.PrivateKeys -> {
                auth.keys.forEachIndexed { i, (label, keyBytes) ->
                    jsch.addIdentity("haven-key-$i-$label", keyBytes, null, null)
                }
            }
            is ConnectionConfig.AuthMethod.FidoKey -> {
                val skData = SkKeyData.deserialize(auth.skKeyData)
                Log.d(TAG, "FIDO2 SK key (reconnect): alg=${skData.algorithmName}")
                val fidoIdentity = FidoIdentity(skData, fidoAuthenticator!!)
                jsch.addIdentity(fidoIdentity, null)
                val currentAlgs = sess.getConfig("PubkeyAcceptedAlgorithms") ?: ""
                val skAlgs = "sk-ssh-ed25519@openssh.com,sk-ecdsa-sha2-nistp256@openssh.com"
                sess.setConfig("PubkeyAcceptedAlgorithms",
                    if (currentAlgs.isNotEmpty()) "$skAlgs,$currentAlgs" else skAlgs)
            }
        }

        config.sshOptions.forEach { (key, value) -> sess.setConfig(key, value) }

        try {
            sess.connect(connectTimeoutMs)
        } catch (e: JSchException) {
            // Mirror of the async connect() path — see the comment there.
            val capturedHostKey = tryExtractHostKey(sess, config.host, config.port)
            try { sess.disconnect() } catch (_: Throwable) { /* best effort */ }
            if (capturedHostKey != null) throw HostKeyAuthFailure(capturedHostKey, e)
            throw e
        }
        session = sess
        registerAgentIdentities(config)
        return extractHostKey(sess, config.host, config.port)
    }

    /**
     * Enable agent forwarding for this session and add the configured identities to the
     * JSch-wide identity repository so JSch's ChannelAgentForwarding can answer forwarded
     * SSH_AGENTC_REQUEST_IDENTITIES / SIGN_REQUEST messages from the remote.
     *
     * Must be called AFTER [Session.connect] so the identities are never tried as
     * candidate keys during public-key auth — otherwise a profile with many stored keys
     * could trip `MaxAuthTries` and be rejected with "Too many authentication failures".
     */
    private fun registerAgentIdentities(config: ConnectionConfig) {
        agentForwardingEnabled = config.forwardAgent
        if (!config.forwardAgent) return
        if (config.agentIdentities.isEmpty()) {
            Log.w(TAG, "Agent forwarding requested but no identities supplied — forwarded agent will be empty")
            return
        }
        config.agentIdentities.forEachIndexed { i, (label, keyBytes) ->
            try {
                jsch.addIdentity("haven-agent-$i-$label", keyBytes, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping agent key '$label' — could not parse: ${e.message}")
            }
        }
    }

    private fun extractHostKey(sess: Session, host: String, port: Int): KnownHostEntry {
        val hk = sess.hostKey
        return KnownHostEntry(
            hostname = host,
            port = port,
            keyType = hk.type,
            // JSch HostKey.getKey() returns the base64-encoded public key
            publicKeyBase64 = hk.key,
        )
    }

    /**
     * Nullable variant used when we can't be sure KEX completed — e.g. called
     * from the catch block after a failed [Session.connect]. JSch exposes
     * [Session.getHostKey] only after the KEX init response arrives, so a
     * null return here means the failure happened earlier in the handshake
     * (connect refused, bad version, KEX alg mismatch, etc.) and there is no
     * host key for the caller to verify.
     */
    private fun tryExtractHostKey(sess: Session, host: String, port: Int): KnownHostEntry? {
        return try {
            val hk = sess.hostKey ?: return null
            KnownHostEntry(
                hostname = host,
                port = port,
                keyType = hk.type,
                publicKeyBase64 = hk.key,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Set up local port forwarding (ssh -L).
     * Returns the actual bound port (useful if bindPort is 0 for ephemeral).
     */
    fun setPortForwardingL(bindAddress: String, localPort: Int, remoteHost: String, remotePort: Int): Int {
        val sess = session ?: throw IllegalStateException("Not connected")
        return sess.setPortForwardingL(bindAddress, localPort, remoteHost, remotePort)
    }

    /**
     * Set up remote port forwarding (ssh -R).
     */
    fun setPortForwardingR(bindAddress: String, remotePort: Int, localHost: String, localPort: Int) {
        val sess = session ?: throw IllegalStateException("Not connected")
        sess.setPortForwardingR(bindAddress, remotePort, localHost, localPort)
    }

    /**
     * Remove a local port forward.
     */
    fun delPortForwardingL(bindAddress: String, localPort: Int) {
        val sess = session ?: throw IllegalStateException("Not connected")
        sess.delPortForwardingL(bindAddress, localPort)
    }

    /**
     * Remove a remote port forward.
     */
    fun delPortForwardingR(remotePort: Int) {
        val sess = session ?: throw IllegalStateException("Not connected")
        sess.delPortForwardingR(remotePort)
    }

    /**
     * Disconnect the current session and clear loaded identities.
     */
    fun disconnect() {
        session?.disconnect()
        session = null
        agentForwardingEnabled = false
        jsch.removeAllIdentity()
    }

    override fun close() = disconnect()

    companion object {
        /** No-op, kept for API compatibility. DNS is resolved fresh on each connection. */
        fun clearDnsCache() { }

        /**
         * Resolve a hostname to an IP address string.
         * For .local hostnames, tries a direct mDNS query first (fast, ~50-100ms)
         * before falling back to the system resolver.
         * Resolved fresh each time — no application-level caching — so network
         * changes (e.g. switching between local and remote DNS) take effect
         * without restarting the app.
         */
        fun resolveHost(hostname: String): String {
            // Already an IP literal — skip resolution
            if (hostname.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return hostname

            // .onion addresses must not be resolved locally — they require a SOCKS proxy
            if (hostname.endsWith(".onion")) return hostname

            val ip = if (hostname.endsWith(".local") || hostname.endsWith(".local.")) {
                resolveMdns(hostname) ?: resolveSystem(hostname)
            } else {
                resolveSystem(hostname)
            }

            if (ip != null) return ip

            throw java.net.UnknownHostException("Could not resolve hostname: $hostname")
        }

        private fun resolveSystem(hostname: String): String? {
            return try {
                // InetAddress.getByName has no timeout — run it in a thread with a deadline
                val future = java.util.concurrent.CompletableFuture.supplyAsync {
                    InetAddress.getByName(hostname).hostAddress
                }
                future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                Log.w(TAG, "DNS resolve timed out for $hostname")
                null
            } catch (e: Exception) {
                val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
                Log.w(TAG, "System DNS resolve failed for $hostname", cause)
                null
            }
        }

        /**
         * Direct mDNS query for .local hostnames.
         * Sends a unicast-response mDNS query to 224.0.0.251:5353 and parses
         * the A record from the response. Timeout 1.5s (vs ~4s system fallback).
         */
        private fun resolveMdns(hostname: String): String? {
            val name = hostname.removeSuffix(".")
            return try {
                val query = buildMdnsQuery(name)
                val socket = DatagramSocket()
                socket.soTimeout = 1500
                try {
                    val mdnsAddr = InetAddress.getByName("224.0.0.251")
                    socket.send(DatagramPacket(query, query.size, mdnsAddr, 5353))
                    val buf = ByteArray(512)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    parseMdnsARecord(buf, resp.length)
                } finally {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.d(TAG, "mDNS resolve failed for $hostname: ${e.message}")
                null
            }
        }

        /**
         * Build a minimal DNS query packet for an A record.
         * Transaction ID = 0, QR=0 (query), QDCOUNT=1, one question for [name] type A class IN.
         */
        private fun buildMdnsQuery(name: String): ByteArray {
            val buf = ByteBuffer.allocate(256)
            // Header: ID=0, flags=0, QDCOUNT=1
            buf.putShort(0) // ID
            buf.putShort(0) // Flags (standard query)
            buf.putShort(1) // QDCOUNT
            buf.putShort(0) // ANCOUNT
            buf.putShort(0) // NSCOUNT
            buf.putShort(0) // ARCOUNT
            // Question: name labels
            for (label in name.split('.')) {
                buf.put(label.length.toByte())
                buf.put(label.toByteArray(Charsets.US_ASCII))
            }
            buf.put(0.toByte()) // terminator
            buf.putShort(1) // QTYPE = A
            buf.putShort(1) // QCLASS = IN
            return buf.array().copyOf(buf.position())
        }

        /**
         * Parse an mDNS response and extract the first A record (IPv4 address).
         */
        private fun parseMdnsARecord(data: ByteArray, length: Int): String? {
            if (length < 12) return null
            val buf = ByteBuffer.wrap(data, 0, length)
            buf.position(2) // skip ID
            buf.short // flags
            val qdCount = buf.short.toInt() and 0xFFFF
            val anCount = buf.short.toInt() and 0xFFFF
            buf.short // nscount
            buf.short // arcount

            // Skip questions
            repeat(qdCount) {
                skipDnsName(buf)
                if (buf.remaining() < 4) return null
                buf.short // qtype
                buf.short // qclass
            }

            // Parse answers
            repeat(anCount) {
                skipDnsName(buf)
                if (buf.remaining() < 10) return null
                val type = buf.short.toInt() and 0xFFFF
                buf.short // class
                buf.int   // TTL
                val rdLength = buf.short.toInt() and 0xFFFF
                if (type == 1 && rdLength == 4 && buf.remaining() >= 4) {
                    // A record — 4 bytes IPv4
                    val a = buf.get().toInt() and 0xFF
                    val b = buf.get().toInt() and 0xFF
                    val c = buf.get().toInt() and 0xFF
                    val d = buf.get().toInt() and 0xFF
                    return "$a.$b.$c.$d"
                }
                if (buf.remaining() >= rdLength) {
                    buf.position(buf.position() + rdLength)
                } else return null
            }
            return null
        }

        private fun skipDnsName(buf: ByteBuffer) {
            while (buf.hasRemaining()) {
                val len = buf.get().toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 == 0xC0) {
                    // Compression pointer — one more byte
                    if (buf.hasRemaining()) buf.get()
                    break
                }
                if (buf.remaining() >= len) {
                    buf.position(buf.position() + len)
                } else break
            }
        }
    }
}
