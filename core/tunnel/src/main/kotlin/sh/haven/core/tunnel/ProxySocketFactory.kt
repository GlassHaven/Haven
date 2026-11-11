package sh.haven.core.tunnel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * javax.net.SocketFactory that dials through a SOCKS5 / SOCKS4 / HTTP
 * proxy (the legacy `proxyType`/`proxyHost`/`proxyPort` columns on
 * ConnectionProfile).
 *
 * Two handshake paths:
 *
 *  - **No proxy auth** ([proxyUser] null): backed by the JDK's built-in
 *    [Proxy] support — `Socket(java.net.Proxy)` performs the SOCKS or
 *    HTTP-CONNECT handshake transparently when [Socket.connect] is called.
 *    Unchanged from before, byte-for-byte.
 *  - **Proxy auth** ([proxyUser] set, #227): the JDK only sources proxy
 *    credentials from a global `java.net.Authenticator` / system
 *    properties, which is racy across concurrent connections. So when
 *    credentials are present we run the handshake ourselves, per-connection:
 *    SOCKS5 → RFC 1929 username/password; HTTP → `Proxy-Authorization:
 *    Basic`. SOCKS4 carries only a userid and the JDK can't pass it without
 *    a global Authenticator, so SOCKS4-with-creds falls through to the JDK
 *    path (the SSH/JSch path handles SOCKS4 userid directly via
 *    `setUserPasswd`; SOCKS4-auth over non-SSH transports is out of scope).
 *
 * Hostnames are passed unresolved to the proxy so DNS lookups happen on
 * the proxy side, not locally — important for Tor (.onion addresses
 * have no public DNS) and for any setup where the proxy can resolve
 * names the local resolver can't. The manual SOCKS5 path uses the
 * domain-name address type (ATYP 0x03) and HTTP CONNECT names the host
 * directly, both preserving remote resolution.
 *
 * Used by [TunnelResolver] when the profile has proxy fields set but no
 * `tunnelConfigId` — the SSH path already had this routing for years
 * via [TunnelResolver.jschProxy]; this factory brings VNC, SMB, and
 * other non-JSch transports to the same place (#149).
 */
class ProxySocketFactory(
    internal val proxyType: String,
    internal val proxyHost: String,
    internal val proxyPort: Int,
    private val connectTimeoutMs: Int = 30_000,
    internal val proxyUser: String? = null,
    internal val proxyPassword: String? = null,
) : SocketFactory() {

    override fun createSocket(host: String, port: Int): Socket {
        val type = proxyType.uppercase()
        // #777: Android's java.net.Socket(Proxy) constructor throws
        // IllegalArgumentException("Invalid Proxy") for Proxy.Type.HTTP (only
        // SOCKS and DIRECT are accepted by libcore's Socket). Always run the
        // HTTP CONNECT handshake manually so both authenticated and
        // unauthenticated HTTP proxies work on Android.
        if (type == "HTTP") {
            return httpConnect(host, port)
        }
        // #227, #777: Always run the SOCKS5 handshake manually so:
        //  1. RFC 1929 username/password length octets use UTF-8 byte length
        //     instead of character count (#227).
        //  2. Literal IPv4 and IPv6 target addresses are encoded as ATYP 0x01
        //     (4-byte IPv4) and ATYP 0x04 (16-byte IPv6) per RFC 1928 §5
        //     instead of ATYP 0x03 (DOMAINNAME), which JSch's ProxySOCKS5 and
        //     JDK's unresolved SocksSocketImpl send unconditionally — causing
        //     proxies with DNS-rebinding filters or IP-based LAN routing rules
        //     to drop local/RFC 1918 addresses (#777).
        if (type == "SOCKS5") {
            return socks5Connect(host, port)
        }
        if (type != "SOCKS4") {
            throw IOException("Unsupported proxy type: $proxyType")
        }
        val proxyAddress = InetSocketAddress(proxyHost, proxyPort)
        val socket = Socket(Proxy(Proxy.Type.SOCKS, proxyAddress))
        val ipv4 = parseIpv4Literal(host)
        val endpoint = if (ipv4 != null) {
            InetSocketAddress(java.net.InetAddress.getByAddress(host, ipv4), port)
        } else {
            // Unresolved address so a SOCKS4a proxy resolves hostnames remotely.
            InetSocketAddress.createUnresolved(host, port)
        }
        socket.connect(endpoint, connectTimeoutMs)
        return socket
    }

    /** SOCKS5 CONNECT with optional RFC 1929 username/password auth (#227, #777). */
    private fun socks5Connect(host: String, port: Int): Socket {
        val socket = Socket()
        var ok = false
        try {
            socket.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            val out = socket.getOutputStream()
            val ins = socket.getInputStream()

            // Greeting: offer no-auth (0x00), plus username/password (0x02) when configured.
            val greeting = if (!proxyUser.isNullOrEmpty()) {
                byteArrayOf(0x05, 0x02, 0x00, 0x02)
            } else {
                byteArrayOf(0x05, 0x01, 0x00)
            }
            out.write(greeting)
            out.flush()
            val ver = readByte(ins)
            if (ver != 0x05) throw IOException("SOCKS5 proxy bad version 0x${ver.toString(16)}")
            when (val method = readByte(ins)) {
                0x00 -> { /* server accepts no-auth */ }
                0x02 -> {
                    if (proxyUser.isNullOrEmpty()) {
                        throw IOException("SOCKS5 proxy requested username/password authentication, but no credentials are configured")
                    }
                    // UTF-8 to match curl / JSch (str2byte) and what most SOCKS5
                    // servers store; the length octets below come from the byte
                    // arrays, so non-ASCII credentials stay correct (#227).
                    val u = proxyUser.toByteArray(Charsets.UTF_8)
                    val p = (proxyPassword ?: "").toByteArray(Charsets.UTF_8)
                    if (u.size > 255 || p.size > 255) {
                        throw IOException("SOCKS5 proxy credentials exceed 255 bytes")
                    }
                    val auth = ByteArrayOutputStream()
                    auth.write(0x01)           // auth subnegotiation version
                    auth.write(u.size); auth.write(u)
                    auth.write(p.size); auth.write(p)
                    out.write(auth.toByteArray())
                    out.flush()
                    readByte(ins) // auth version echo
                    if (readByte(ins) != 0x00) {
                        throw IOException("SOCKS5 proxy authentication failed (bad username/password)")
                    }
                }
                0xFF -> throw IOException("SOCKS5 proxy rejected the offered auth methods")
                else -> throw IOException("SOCKS5 proxy requested unsupported auth method 0x${method.toString(16)}")
            }

            // CONNECT request: encode literal IPv4 as ATYP 0x01, literal IPv6
            // as ATYP 0x04, and hostnames as ATYP 0x03 (remote DNS) (#777).
            val req = ByteArrayOutputStream()
            req.write(0x05); req.write(0x01); req.write(0x00)
            writeSocks5Destination(req, host)
            req.write((port ushr 8) and 0xFF); req.write(port and 0xFF)
            out.write(req.toByteArray())
            out.flush()

            if (readByte(ins) != 0x05) throw IOException("SOCKS5 proxy bad reply version")
            val rep = readByte(ins)
            if (rep != 0x00) {
                throw IOException("SOCKS5 CONNECT failed (reply code $rep: ${socks5ReplyReason(rep)})")
            }
            readByte(ins) // RSV
            when (val atyp = readByte(ins)) {
                0x01 -> skipExact(ins, 4 + 2)            // IPv4 + port
                0x03 -> skipExact(ins, readByte(ins) + 2) // domain len + port
                0x04 -> skipExact(ins, 16 + 2)           // IPv6 + port
                else -> throw IOException("SOCKS5 proxy bad reply ATYP 0x${atyp.toString(16)}")
            }
            socket.soTimeout = 0 // hand back a normal blocking socket
            ok = true
            return socket
        } finally {
            if (!ok) runCatching { socket.close() }
        }
    }

    /** HTTP CONNECT with optional Proxy-Authorization: Basic (#227, #777). */
    private fun httpConnect(host: String, port: Int): Socket {
        val socket = Socket()
        var ok = false
        try {
            socket.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            val out = socket.getOutputStream()
            val ins = socket.getInputStream()

            val target = "$host:$port"
            val request = buildString {
                append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(target).append("\r\n")
                if (!proxyUser.isNullOrEmpty()) {
                    val token = java.util.Base64.getEncoder()
                        .encodeToString("$proxyUser:${proxyPassword ?: ""}".toByteArray(Charsets.UTF_8))
                    append("Proxy-Authorization: Basic ").append(token).append("\r\n")
                }
                append("Proxy-Connection: keep-alive\r\n")
                append("\r\n")
            }
            out.write(request.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val statusLine = readLine(ins)
                ?: throw IOException("HTTP proxy closed connection before CONNECT reply")
            // Expect "HTTP/1.x 200 ..."; anything else (407, 403, 502) is failure.
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            if (code != 200) {
                throw IOException("HTTP proxy CONNECT failed: $statusLine")
            }
            // Drain the remaining response headers up to the blank line.
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) break
            }
            socket.soTimeout = 0
            ok = true
            return socket
        } finally {
            if (!ok) runCatching { socket.close() }
        }
    }

    private fun readByte(ins: InputStream): Int {
        val b = ins.read()
        if (b < 0) throw IOException("proxy closed connection unexpectedly")
        return b and 0xFF
    }

    private fun skipExact(ins: InputStream, n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = ins.skip(remaining.toLong())
            if (skipped <= 0) {
                if (ins.read() < 0) throw IOException("proxy closed connection unexpectedly")
                remaining--
            } else {
                remaining -= skipped.toInt()
            }
        }
    }

    /** Reads a single CRLF- (or LF-) terminated line as ISO-8859-1; null at EOF. */
    private fun readLine(ins: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sawAny) buf.toString("ISO-8859-1") else null
            sawAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("ISO-8859-1")
    }

    override fun createSocket(): Socket = DeferredProxySocket()

    override fun createSocket(
        host: String,
        port: Int,
        localHost: java.net.InetAddress?,
        localPort: Int,
    ): Socket = createSocket(host, port)

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
        createSocket(host.hostAddress ?: host.toString(), port)

    override fun createSocket(
        address: java.net.InetAddress,
        port: Int,
        localAddress: java.net.InetAddress?,
        localPort: Int,
    ): Socket = createSocket(address, port)

    /**
     * Unconnected [Socket] wrapper for callers (OkHttp, JavaMail) that invoke
     * zero-arg [createSocket] and then `connect(InetSocketAddress, timeout)`.
     * Preserves `InetSocketAddress.hostString` so SOCKS5/HTTP proxies resolve
     * target hostnames remotely without clearnet fallback.
     */
    private inner class DeferredProxySocket : Socket() {
        @Volatile private var delegate: Socket? = null
        @Volatile private var closed: Boolean = false
        @Volatile private var pendingSoTimeout: Int? = null
        @Volatile private var pendingTcpNoDelay: Boolean? = null
        @Volatile private var pendingKeepAlive: Boolean? = null

        override fun connect(endpoint: java.net.SocketAddress?) = connect(endpoint, 0)

        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
            if (closed) throw IOException("Socket is closed")
            val addr = endpoint as? InetSocketAddress
                ?: throw IOException("Unsupported endpoint: $endpoint")
            val targetHost = addr.hostString ?: addr.address?.hostAddress
                ?: throw IOException("Unresolved endpoint host")
            val connected = if (timeout > 0 && timeout != connectTimeoutMs) {
                ProxySocketFactory(
                    proxyType = proxyType,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    connectTimeoutMs = timeout,
                    proxyUser = proxyUser,
                    proxyPassword = proxyPassword,
                ).createSocket(targetHost, addr.port)
            } else {
                createSocket(targetHost, addr.port)
            }
            pendingSoTimeout?.let { runCatching { connected.soTimeout = it } }
            pendingTcpNoDelay?.let { runCatching { connected.tcpNoDelay = it } }
            pendingKeepAlive?.let { runCatching { connected.keepAlive = it } }
            delegate = connected
            if (closed) runCatching { connected.close() }
        }

        private fun requireConnected(): Socket {
            if (closed) throw IOException("Socket is closed")
            return delegate ?: throw IOException("Socket is not connected")
        }

        override fun getInputStream(): InputStream = requireConnected().getInputStream()
        override fun getOutputStream(): java.io.OutputStream = requireConnected().getOutputStream()
        override fun getInetAddress(): java.net.InetAddress? = delegate?.inetAddress
        override fun getPort(): Int = delegate?.port ?: 0
        override fun getLocalAddress(): java.net.InetAddress =
            delegate?.localAddress ?: java.net.InetAddress.getLoopbackAddress()
        override fun getLocalPort(): Int = delegate?.localPort ?: -1
        override fun getRemoteSocketAddress(): java.net.SocketAddress? = delegate?.remoteSocketAddress
        override fun getLocalSocketAddress(): java.net.SocketAddress? = delegate?.localSocketAddress
        override fun isConnected(): Boolean = delegate?.isConnected == true && !closed
        override fun isBound(): Boolean = delegate?.isBound == true
        override fun isClosed(): Boolean = closed || delegate?.isClosed == true
        override fun isInputShutdown(): Boolean = closed || delegate?.isInputShutdown == true
        override fun isOutputShutdown(): Boolean = closed || delegate?.isOutputShutdown == true

        override fun close() {
            if (closed) return
            closed = true
            runCatching { delegate?.close() }
        }

        override fun bind(bindpoint: java.net.SocketAddress?) {}
        override fun shutdownInput() { delegate?.shutdownInput() }
        override fun shutdownOutput() { delegate?.shutdownOutput() }

        override fun setSoTimeout(timeout: Int) {
            pendingSoTimeout = timeout
            delegate?.let { it.soTimeout = timeout }
        }
        override fun getSoTimeout(): Int = delegate?.soTimeout ?: pendingSoTimeout ?: 0

        override fun setTcpNoDelay(on: Boolean) {
            pendingTcpNoDelay = on
            delegate?.let { it.tcpNoDelay = on }
        }
        override fun getTcpNoDelay(): Boolean = delegate?.tcpNoDelay ?: pendingTcpNoDelay ?: false

        override fun setKeepAlive(on: Boolean) {
            pendingKeepAlive = on
            delegate?.let { it.keepAlive = on }
        }
        override fun getKeepAlive(): Boolean = delegate?.keepAlive ?: pendingKeepAlive ?: false

        override fun setSoLinger(on: Boolean, linger: Int) { delegate?.setSoLinger(on, linger) }
        override fun getSoLinger(): Int = delegate?.soLinger ?: -1
        override fun setReuseAddress(on: Boolean) { delegate?.let { it.reuseAddress = on } }
        override fun getReuseAddress(): Boolean = delegate?.reuseAddress ?: false
        override fun setOOBInline(on: Boolean) { delegate?.let { it.oobInline = on } }
        override fun getOOBInline(): Boolean = delegate?.oobInline ?: false
        override fun setReceiveBufferSize(size: Int) { delegate?.let { it.receiveBufferSize = size } }
        override fun setSendBufferSize(size: Int) { delegate?.let { it.sendBufferSize = size } }
        override fun setTrafficClass(tc: Int) { delegate?.let { it.trafficClass = tc } }
    }

    internal companion object {
        /**
         * Encode [host] into a SOCKS5 `ATYP + DST.ADDR` field per RFC 1928 §5:
         *  - `0x01` + 4 octets when [host] is a dotted-quad IPv4 literal
         *  - `0x04` + 16 octets when [host] is an IPv6 literal
         *  - `0x03` + length octet + ASCII bytes when [host] is a domain name
         *
         * Both literal checks are pure string parsers and never invoke DNS.
         */
        internal fun writeSocks5Destination(out: ByteArrayOutputStream, host: String) {
            val trimmed = host.trim()
            val ipv4 = parseIpv4Literal(trimmed)
            if (ipv4 != null) {
                out.write(0x01)
                out.write(ipv4)
                return
            }
            val ipv6 = parseIpv6Literal(trimmed)
            if (ipv6 != null) {
                out.write(0x04)
                out.write(ipv6)
                return
            }
            val dst = trimmed.toByteArray(Charsets.ISO_8859_1)
            if (dst.isEmpty() || dst.size > 255) {
                throw IOException("SOCKS5 destination host length out of range: ${dst.size}")
            }
            out.write(0x03)
            out.write(dst.size)
            out.write(dst)
        }

        /** Parse a strict dotted-quad IPv4 literal (`a.b.c.d`) without DNS, or null. */
        internal fun parseIpv4Literal(host: String): ByteArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val part = parts[i]
                if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
                // Reject leading zeros like "010" which some stacks interpret as octal.
                if (part.length > 1 && part[0] == '0') return null
                val n = part.toIntOrNull() ?: return null
                if (n !in 0..255) return null
                bytes[i] = n.toByte()
            }
            return bytes
        }

        /** Parse an IPv6 literal (with optional `[...]` brackets or `%zone`) without DNS, or null. */
        internal fun parseIpv6Literal(raw: String): ByteArray? {
            val clean = raw.trim().removeSurrounding("[", "]").substringBefore('%')
            if (!clean.contains(':')) return null
            var text = clean
            var ipv4Tail: ByteArray? = null
            val lastColon = text.lastIndexOf(':')
            if (lastColon >= 0 && text.indexOf('.', lastColon) >= 0) {
                ipv4Tail = parseIpv4Literal(text.substring(lastColon + 1)) ?: return null
                text = text.substring(0, lastColon) + ":0:0"
            }
            if (text.contains(":::")) return null
            val doubleColonCount = text.split("::").size - 1
            if (doubleColonCount > 1) return null
            val groups: List<Int> = if (doubleColonCount == 1) {
                val sides = text.split("::", limit = 2)
                val left = if (sides[0].isEmpty()) emptyList() else sides[0].split(':')
                val right = if (sides[1].isEmpty()) emptyList() else sides[1].split(':')
                val missing = 8 - (left.size + right.size)
                if (missing < 1) return null
                val all = left + List(missing) { "0" } + right
                all.map { parseHexGroup(it) ?: return null }
            } else {
                val parts = text.split(':')
                if (parts.size != 8) return null
                parts.map { parseHexGroup(it) ?: return null }
            }
            if (groups.size != 8) return null
            val out = ByteArray(16)
            for (i in 0 until 8) {
                out[i * 2] = ((groups[i] ushr 8) and 0xFF).toByte()
                out[i * 2 + 1] = (groups[i] and 0xFF).toByte()
            }
            if (ipv4Tail != null) {
                System.arraycopy(ipv4Tail, 0, out, 12, 4)
            }
            return out
        }

        private fun parseHexGroup(s: String): Int? {
            if (s.isEmpty() || s.length > 4) return null
            var v = 0
            for (ch in s) {
                val d = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> return null
                }
                v = (v shl 4) or d
            }
            return v
        }

        internal fun socks5ReplyReason(rep: Int): String = when (rep) {
            0x01 -> "general SOCKS server failure"
            0x02 -> "connection not allowed by ruleset"
            0x03 -> "network unreachable"
            0x04 -> "host unreachable"
            0x05 -> "connection refused"
            0x06 -> "TTL expired"
            0x07 -> "command not supported"
            0x08 -> "address type not supported"
            else -> "unknown error"
        }
    }
}
