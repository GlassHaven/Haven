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
    private val proxyType: String,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val connectTimeoutMs: Int = 30_000,
    private val proxyUser: String? = null,
    private val proxyPassword: String? = null,
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
        // Manual authenticated handshake for SOCKS5 when credentials are present;
        // unauthenticated SOCKS5 and SOCKS4 keep the JDK SOCKS path.
        if (!proxyUser.isNullOrEmpty() && type == "SOCKS5") {
            return socks5Authenticated(host, port)
        }
        val proxyAddress = InetSocketAddress(proxyHost, proxyPort)
        val javaProxy = when (type) {
            "SOCKS5", "SOCKS4" -> Proxy(Proxy.Type.SOCKS, proxyAddress)
            else -> throw IOException("Unsupported proxy type: $proxyType")
        }
        val socket = Socket(javaProxy)
        // Unresolved address so the proxy resolves the hostname remotely.
        socket.connect(InetSocketAddress.createUnresolved(host, port), connectTimeoutMs)
        return socket
    }

    /** SOCKS5 with RFC 1929 username/password auth (#227). */
    private fun socks5Authenticated(host: String, port: Int): Socket {
        val socket = Socket()
        var ok = false
        try {
            socket.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            val out = socket.getOutputStream()
            val ins = socket.getInputStream()

            // Greeting: offer no-auth (0x00) and username/password (0x02).
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            out.flush()
            val ver = readByte(ins)
            if (ver != 0x05) throw IOException("SOCKS5 proxy bad version 0x${ver.toString(16)}")
            when (val method = readByte(ins)) {
                0x00 -> { /* server accepts no-auth */ }
                0x02 -> {
                    // UTF-8 to match curl / JSch (str2byte) and what most SOCKS5
                    // servers store; the length octets below come from the byte
                    // arrays, so non-ASCII credentials stay correct (#227).
                    val u = (proxyUser ?: "").toByteArray(Charsets.UTF_8)
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

            // CONNECT request, domain-name address type (remote DNS).
            val dst = host.toByteArray(Charsets.ISO_8859_1)
            if (dst.size > 255) throw IOException("SOCKS5 destination host too long")
            val req = ByteArrayOutputStream()
            req.write(0x05); req.write(0x01); req.write(0x00); req.write(0x03)
            req.write(dst.size); req.write(dst)
            req.write((port ushr 8) and 0xFF); req.write(port and 0xFF)
            out.write(req.toByteArray())
            out.flush()

            if (readByte(ins) != 0x05) throw IOException("SOCKS5 proxy bad reply version")
            val rep = readByte(ins)
            if (rep != 0x00) throw IOException("SOCKS5 CONNECT failed (reply code $rep)")
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
}
