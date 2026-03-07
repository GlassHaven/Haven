package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

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
    private var session: Session? = null

    val isConnected: Boolean
        get() = session?.isConnected == true

    /**
     * Connect to an SSH server using the given config.
     * This suspends on Dispatchers.IO.
     * Returns the host key as a [KnownHostEntry] for TOFU verification.
     */
    suspend fun connect(
        config: ConnectionConfig,
        connectTimeoutMs: Int = 10_000,
    ): KnownHostEntry = withContext(Dispatchers.IO) {
        disconnect()

        val resolvedIp = resolveHost(config.host)
        val sess = jsch.getSession(config.username, resolvedIp, config.port)
        // Accept any key at the JSch level; we verify post-connect ourselves (TOFU)
        sess.setConfig("StrictHostKeyChecking", "no")
        // Disable GSSAPI auth — it causes multi-second timeouts on most servers
        sess.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                sess.setPassword(auth.password)
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                jsch.addIdentity(
                    "haven-key",
                    auth.keyBytes,
                    null,
                    auth.passphrase.ifEmpty { null }?.toByteArray(),
                )
            }
        }

        sess.connect(connectTimeoutMs)
        session = sess
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
    fun connectBlocking(config: ConnectionConfig, connectTimeoutMs: Int = 10_000): KnownHostEntry {
        disconnect()

        val resolvedIp = resolveHost(config.host)
        val sess = jsch.getSession(config.username, resolvedIp, config.port)
        sess.setConfig("StrictHostKeyChecking", "no")
        sess.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                sess.setPassword(auth.password)
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                jsch.addIdentity(
                    "haven-key-${System.nanoTime()}",
                    auth.keyBytes,
                    null,
                    auth.passphrase.ifEmpty { null }?.toByteArray(),
                )
            }
        }

        sess.connect(connectTimeoutMs)
        session = sess
        return extractHostKey(sess, config.host, config.port)
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
        jsch.removeAllIdentity()
    }

    override fun close() = disconnect()

    companion object {
        /**
         * Cache resolved hostnames → IP addresses across all SshClient instances.
         * Avoids repeated slow DNS lookups (especially for .local mDNS names on Android,
         * where the system resolver tries unicast DNS first with a ~4s timeout).
         */
        private val dnsCache = ConcurrentHashMap<String, String>()

        fun clearDnsCache() { dnsCache.clear() }

        /**
         * Resolve a hostname to an IP address string.
         * For .local hostnames, tries a direct mDNS query first (fast, ~50-100ms)
         * before falling back to the system resolver.
         * Results are cached for the lifetime of the process.
         */
        fun resolveHost(hostname: String): String {
            // Already an IP literal — skip resolution
            if (hostname.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return hostname

            dnsCache[hostname]?.let { return it }

            val ip = if (hostname.endsWith(".local") || hostname.endsWith(".local.")) {
                resolveMdns(hostname) ?: resolveSystem(hostname)
            } else {
                resolveSystem(hostname)
            }

            if (ip != null) {
                dnsCache[hostname] = ip
                return ip
            }

            Log.w(TAG, "Failed to resolve $hostname, using as-is")
            return hostname
        }

        private fun resolveSystem(hostname: String): String? {
            return try {
                InetAddress.getByName(hostname).hostAddress
            } catch (e: Exception) {
                Log.w(TAG, "System DNS resolve failed for $hostname", e)
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
