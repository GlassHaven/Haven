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
        keyboardInteractivePrompter: KeyboardInteractivePrompter? = null,
    ): KnownHostEntry = withContext(Dispatchers.IO) {
        disconnect()
        verboseLogger?.let { jsch.setInstanceLogger(it) }

        val resolvedIp = if (proxy != null) config.host else resolveHost(config.host, ipv4Only = config.forceIpv4)
        val sess = jsch.getSession(config.username, resolvedIp, config.port)
        if (proxy != null) sess.setProxy(proxy)
        // Accept any key at the JSch level; we verify post-connect ourselves (TOFU)
        sess.setConfig("StrictHostKeyChecking", "no")
        // Disable GSSAPI auth — it causes multi-second timeouts on most servers
        sess.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        var fallbackKiPassword: CharArray? = null
        when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                sess.setPassword(charsToUtf8Bytes(auth.password))
                // Silently satisfy single-prompt "Password:" KI rounds —
                // servers that advertise keyboard-interactive before
                // password auth route the password through the KI channel,
                // and we shouldn't make the user retype a saved password.
                fallbackKiPassword = auth.password
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                // Pass the OpenSSH cert (when present) as the third
                // public-key arg. JSch detects the cert magic bytes and
                // internally wraps the identity in
                // OpenSshCertificateAwareIdentityFile, so the server
                // gets cert+sig validated via its trusted CA. (#133)
                jsch.addIdentity(
                    "haven-key",
                    auth.keyBytes,
                    auth.certificateBytes,
                    if (auth.passphrase.isNotEmpty()) charsToUtf8Bytes(auth.passphrase) else null,
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

        if (keyboardInteractivePrompter != null) {
            sess.userInfo = KeyboardInteractiveUserInfo(
                destination = "${config.username}@${config.host}:${config.port}",
                prompter = keyboardInteractivePrompter,
                fallbackPassword = fallbackKiPassword,
            )
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
        if (agentForwardingEnabled) {
            channel.setAgentForwarding(true)
            diag("Shell channel opened with agent forwarding enabled")
        }
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
        if (agentForwardingEnabled) {
            channel.setAgentForwarding(true)
            diag("Exec channel opened with agent forwarding enabled: ${command.take(64)}")
        }
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
    fun connectBlocking(
        config: ConnectionConfig,
        connectTimeoutMs: Int = 10_000,
        proxy: Proxy? = null,
        keyboardInteractivePrompter: KeyboardInteractivePrompter? = null,
    ): KnownHostEntry {
        disconnect()
        verboseLogger?.let { jsch.setInstanceLogger(it) }

        val resolvedIp = if (proxy != null) config.host else resolveHost(config.host, ipv4Only = config.forceIpv4)
        val sess = jsch.getSession(config.username, resolvedIp, config.port)
        if (proxy != null) sess.setProxy(proxy)
        sess.setConfig("StrictHostKeyChecking", "no")
        sess.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        var fallbackKiPassword: CharArray? = null
        when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                sess.setPassword(charsToUtf8Bytes(auth.password))
                fallbackKiPassword = auth.password
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                jsch.addIdentity(
                    "haven-key-${System.nanoTime()}",
                    auth.keyBytes,
                    auth.certificateBytes,
                    if (auth.passphrase.isNotEmpty()) charsToUtf8Bytes(auth.passphrase) else null,
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

        if (keyboardInteractivePrompter != null) {
            sess.userInfo = KeyboardInteractiveUserInfo(
                destination = "${config.username}@${config.host}:${config.port}",
                prompter = keyboardInteractivePrompter,
                fallbackPassword = fallbackKiPassword,
            )
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
     * Log a line to Android logcat *and* the JSch verbose logger so the line
     * is captured in the per-connection verbose log that ships with the
     * Connection Log entry when users enable "Verbose SSH logging". This is
     * how we make agent-forwarding diagnostics visible to end users who want
     * to share logs via the connection log viewer.
     */
    private fun diag(message: String) {
        Log.d(TAG, message)
        verboseLogger?.log(com.jcraft.jsch.Logger.INFO, "[haven/agent] $message")
    }

    /**
     * Enable agent forwarding for this session and add the configured identities to the
     * JSch-wide identity repository so JSch's ChannelAgentForwarding can answer forwarded
     * SSH_AGENTC_REQUEST_IDENTITIES / SIGN_REQUEST messages from the remote.
     *
     * Must be called AFTER [Session.connect] so the identities are never tried as
     * candidate keys during public-key auth — otherwise a profile with many stored keys
     * could trip `MaxAuthTries` and be rejected with "Too many authentication failures".
     *
     * Emits diagnostic lines via [diag] so enabling Verbose SSH logging gives
     * users enough detail to file meaningful bug reports about forwarded-agent
     * behaviour (see #75 thread).
     */
    private fun registerAgentIdentities(config: ConnectionConfig) {
        agentForwardingEnabled = config.forwardAgent
        if (!config.forwardAgent) {
            diag("forwardAgent=false — not registering any agent identities")
            return
        }
        if (config.agentIdentities.isEmpty()) {
            diag(
                "forwardAgent=true but agentIdentities is empty — the forwarded " +
                    "agent channel will expose no keys. Typical cause: all stored " +
                    "SSH keys are passphrase-protected and were filtered out by " +
                    "the caller (ConnectionsViewModel.agentIdentitiesFor)."
            )
            // Log which identities are currently in JSch's repo (these came from
            // the primary auth step earlier in connect()). JSch's
            // ChannelAgentForwarding exposes THE ENTIRE repo over the forwarded
            // channel, so even with agentIdentities empty these primary-auth
            // identities may still be reachable from the remote.
            logJschIdentityRepo()
            return
        }
        var registered = 0
        var skipped = 0
        config.agentIdentities.forEachIndexed { i, (label, keyBytes) ->
            try {
                jsch.addIdentity("haven-agent-$i-$label", keyBytes, null, null)
                registered++
                diag("Registered agent identity #$i '$label' (${keyBytes.size} bytes)")
            } catch (e: Exception) {
                skipped++
                diag("Skipped agent identity #$i '$label' — ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        diag("Agent identities: $registered registered, $skipped skipped, ${config.agentIdentities.size} requested")
        logJschIdentityRepo()
    }

    /**
     * Dump the names of every identity currently in JSch's repository.
     * ChannelAgentForwarding answers SSH_AGENTC_REQUEST_IDENTITIES with the
     * full repo contents, so this is what the remote will actually see over
     * the forwarded agent socket. Useful for diagnosing cases where
     * `ssh-add -l` on the remote returns something different from what the
     * user configured under the "Forward SSH agent" toggle.
     */
    private fun logJschIdentityRepo() {
        try {
            val names = jsch.identityNames
            if (names.isEmpty()) {
                diag("JSch identity repo: EMPTY — forwarded agent will report no keys")
            } else {
                diag("JSch identity repo (${names.size} entries, will be exposed over forwarded agent):")
                for ((i, name) in names.withIndex()) {
                    diag("  [$i] $name")
                }
            }
        } catch (e: Throwable) {
            diag("Could not read JSch identity repo: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Encode a `CharArray` to its UTF-8 byte representation without
     * passing through `String`. JSch's `setPassword(byte[])` and
     * `addIdentity(..., byte[] passphrase)` overloads are used so the
     * secret never lands in an immutable `String` (which can't be
     * zeroed). The returned `ByteArray` is handed to JSch, which
     * keeps a reference for the lifetime of the auth attempt — the
     * caller is responsible for clearing the source `CharArray` via
     * `AuthMethod.Password.clear()` / `AuthMethod.PrivateKey.clear()`
     * once the session is established.
     */
    private fun charsToUtf8Bytes(chars: CharArray): ByteArray {
        val cb = java.nio.CharBuffer.wrap(chars)
        val bb = Charsets.UTF_8.encode(cb)
        val limit = bb.limit()
        val out = ByteArray(limit)
        bb.get(out)
        // Best-effort wipe of the temporary ByteBuffer's backing array
        // before it goes out of scope. No-op for a direct buffer.
        if (bb.hasArray()) {
            val backing = bb.array()
            val from = bb.arrayOffset()
            java.util.Arrays.fill(backing, from, from + limit, 0.toByte())
        }
        return out
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

    // --- Dynamic (SOCKS5) port forwarding (ssh -D) ---

    /** Active dynamic forwards keyed by (bindAddress, bindPort). */
    private val dynamicForwards = mutableMapOf<Pair<String, Int>, DynamicForwardServer>()

    /**
     * Start a SOCKS5 proxy server on the given local address/port. Each
     * accepted connection is tunneled through an SSH `direct-tcpip` channel.
     * Returns the port actually bound (useful if bindPort is 0).
     */
    fun setPortForwardingDynamic(bindAddress: String, bindPort: Int): Int {
        val sess = session ?: throw IllegalStateException("Not connected")
        val server = DynamicForwardServer(sess, bindAddress, bindPort)
        val actualPort = server.start()
        synchronized(dynamicForwards) {
            // Key by the originally requested port so removal is deterministic;
            // store under both keys if 0 was requested
            dynamicForwards[bindAddress to actualPort] = server
            if (bindPort == 0) {
                dynamicForwards[bindAddress to 0] = server
            }
        }
        return actualPort
    }

    /** Stop a dynamic forward previously started with [setPortForwardingDynamic]. */
    fun delPortForwardingDynamic(bindAddress: String, bindPort: Int) {
        synchronized(dynamicForwards) {
            val server = dynamicForwards.remove(bindAddress to bindPort)
            if (server != null) {
                // Also remove any alias entry
                dynamicForwards.entries.removeAll { it.value === server }
                try { server.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Disconnect the current session and clear loaded identities.
     */
    fun disconnect() {
        // Close any dynamic forward servers before tearing down the session
        synchronized(dynamicForwards) {
            dynamicForwards.values.toSet().forEach {
                try { it.close() } catch (_: Exception) {}
            }
            dynamicForwards.clear()
        }
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
        fun resolveHost(hostname: String, ipv4Only: Boolean = false): String {
            // Already an IP literal — skip resolution. (IPv6 literals contain ':',
            // never matched by the IPv4 regex; passing them through verbatim
            // is fine — JSch won't dial them when ipv4Only is set since the
            // input is already pinned.)
            if (hostname.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return hostname

            // .onion addresses must not be resolved locally — they require a SOCKS proxy
            if (hostname.endsWith(".onion")) return hostname

            val ip = if (hostname.endsWith(".local") || hostname.endsWith(".local.")) {
                resolveMdns(hostname) ?: resolveSystem(hostname, ipv4Only)
            } else {
                resolveSystem(hostname, ipv4Only)
            }

            if (ip != null) return ip

            val why = if (ipv4Only) " (no A record / IPv4 address found, force-IPv4 enabled)" else ""
            throw java.net.UnknownHostException("Could not resolve hostname: $hostname$why")
        }

        private fun resolveSystem(hostname: String, ipv4Only: Boolean = false): String? {
            return try {
                // InetAddress.getByName/getAllByName has no timeout — run it in a thread with a deadline
                val future = java.util.concurrent.CompletableFuture.supplyAsync {
                    if (ipv4Only) {
                        // Force-IPv4 (#137): walk every address the resolver
                        // returns, pick the first IPv4. If none, returning
                        // null surfaces as a clear "no A record" error rather
                        // than the JSch-level "could not connect" once we'd
                        // tried IPv6.
                        InetAddress.getAllByName(hostname)
                            .firstOrNull { it is java.net.Inet4Address }
                            ?.hostAddress
                    } else {
                        InetAddress.getByName(hostname).hostAddress
                    }
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
