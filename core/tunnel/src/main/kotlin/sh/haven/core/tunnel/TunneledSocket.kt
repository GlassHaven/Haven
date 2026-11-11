package sh.haven.core.tunnel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * java.net.Socket adapter backed by a [TunneledConnection].
 *
 * Userspace tunnels (WireGuard / Tailscale) don't expose kernel sockets;
 * a [TunneledConnection] is the byte-stream surface they offer instead.
 * Some upstream libraries hard-code a Socket dependency though — VNC's
 * VeNCrypt upgrade calls `SSLSocketFactory.createSocket(socket, host,
 * port, true)` (the layered-SSL form) and smbj's SmbConfig takes a
 * javax.net.SocketFactory whose contract returns Sockets. This class
 * bridges both: hand a TunneledSocket to those APIs and they read/write
 * via the underlying TunneledConnection.
 *
 * The connection is pre-dialled — [connect] and [bind] therefore throw
 * [UnsupportedOperationException]. [close] is idempotent.
 */
class TunneledSocket(
    private val connection: TunneledConnection,
    private val host: String,
    private val port: Int,
) : Socket() {

    @Volatile
    private var closed: Boolean = false

    override fun getInputStream(): InputStream {
        if (closed) throw IOException("Socket is closed")
        return connection.inputStream
    }

    override fun getOutputStream(): OutputStream {
        if (closed) throw IOException("Socket is closed")
        return connection.outputStream
    }

    override fun getInetAddress(): InetAddress? {
        // #539: answer from the tunnel's namespace when it can say what it
        // actually connected to, without ever touching the phone's DNS
        // resolver (which lives in clearnet/LAN rather than the tunnel).
        return tunnelInetAddressNoDns(host, connection.remoteAddress ?: host)
    }

    override fun getPort(): Int = port

    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getLocalPort(): Int = -1

    override fun isConnected(): Boolean = !closed

    override fun isBound(): Boolean = false

    override fun isClosed(): Boolean = closed

    override fun isInputShutdown(): Boolean = closed

    override fun isOutputShutdown(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (_: Throwable) {
            // best-effort teardown
        }
    }

    override fun connect(endpoint: SocketAddress?) {
        throw UnsupportedOperationException("TunneledSocket is pre-connected")
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        throw UnsupportedOperationException("TunneledSocket is pre-connected")
    }

    override fun bind(bindpoint: SocketAddress?) {
        throw UnsupportedOperationException("TunneledSocket cannot be bound")
    }

    override fun shutdownInput() {
        // No-op — userspace tunnels don't expose half-close.
    }

    override fun shutdownOutput() {
        // No-op — userspace tunnels don't expose half-close.
    }

    // TCP-level socket options below — userspace tunnels (WireGuard /
    // Tailscale) handle their own buffering, keepalives, and shutdown
    // semantics inside the netstack, so kernel-socket knobs have no
    // backing implementation. We accept-and-ignore rather than throw,
    // because callers like reticulum-kt's TCPClientInterface set these
    // unconditionally on every dial.

    override fun setTcpNoDelay(on: Boolean) { /* no-op */ }
    override fun setKeepAlive(on: Boolean) { /* no-op */ }
    override fun setSoTimeout(timeout: Int) { /* no-op */ }
    override fun setSoLinger(on: Boolean, linger: Int) { /* no-op */ }
    override fun setReuseAddress(on: Boolean) { /* no-op */ }
    override fun setOOBInline(on: Boolean) { /* no-op */ }
    override fun setReceiveBufferSize(size: Int) { /* no-op */ }
    override fun setSendBufferSize(size: Int) { /* no-op */ }
    override fun setTrafficClass(tc: Int) { /* no-op */ }

    override fun getTcpNoDelay(): Boolean = false
    override fun getKeepAlive(): Boolean = false
    override fun getSoTimeout(): Int = 0
    override fun getSoLinger(): Int = -1
    override fun getReuseAddress(): Boolean = false
    override fun getOOBInline(): Boolean = false

    override fun getRemoteSocketAddress(): java.net.SocketAddress? {
        // Resolved from the tunnel's own report when available (#539);
        // otherwise the unresolved holder it always was.
        val peer = connection.remoteAddress ?: return java.net.InetSocketAddress.createUnresolved(host, port)
        val resolved = tunnelInetAddressNoDns(host, peer)
        return if (resolved != null) {
            java.net.InetSocketAddress(resolved, port)
        } else {
            java.net.InetSocketAddress.createUnresolved(host, port)
        }
    }
}

/**
 * Like [TunneledSocket] but dials the tunnel lazily on [connect] rather than
 * wrapping a pre-dialled connection. Required by upstream libraries that obtain
 * a socket from a [javax.net.SocketFactory] via the no-arg `createSocket()` and
 * then call `connect(addr)` — notably JavaMail's `SocketFetcher`, which uses
 * that pattern for any custom base socket factory. A dead/blocked tunnel makes
 * the [connect] dial fail, so the caller fails closed (no clearnet fallback).
 */
class DeferredTunneledSocket(
    private val tunnel: Tunnel,
    private val dialTimeoutMs: Int,
) : Socket() {

    @Volatile
    private var connection: TunneledConnection? = null

    @Volatile
    private var host: String = ""

    @Volatile
    private var port: Int = -1

    @Volatile
    private var closed: Boolean = false

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (closed) throw IOException("Socket is closed")
        val addr = endpoint as? InetSocketAddress
            ?: throw IOException("Unsupported endpoint: $endpoint")
        host = addr.hostString ?: addr.address?.hostAddress
            ?: throw IOException("Unresolved endpoint host")
        port = addr.port
        connection = tunnel.dial(host, port, if (timeout > 0) timeout else dialTimeoutMs)
    }

    private fun conn(): TunneledConnection {
        if (closed) throw IOException("Socket is closed")
        return connection ?: throw IOException("Socket is not connected")
    }

    override fun getInputStream(): InputStream = conn().inputStream

    override fun getOutputStream(): OutputStream = conn().outputStream

    override fun getInetAddress(): InetAddress? =
        tunnelInetAddressNoDns(host, connection?.remoteAddress ?: host)

    override fun getPort(): Int = port

    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getLocalPort(): Int = -1

    override fun isConnected(): Boolean = connection != null && !closed

    override fun isBound(): Boolean = connection != null

    override fun isClosed(): Boolean = closed

    override fun isInputShutdown(): Boolean = closed

    override fun isOutputShutdown(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection?.close()
        } catch (_: Throwable) {
            // best-effort teardown
        }
    }

    override fun bind(bindpoint: SocketAddress?) {
        // No-op — the tunnel binds its own source on dial.
    }

    override fun shutdownInput() {}
    override fun shutdownOutput() {}

    // Socket options — same accept-and-ignore contract as [TunneledSocket].
    override fun setTcpNoDelay(on: Boolean) {}
    override fun setKeepAlive(on: Boolean) {}
    override fun setSoTimeout(timeout: Int) {}
    override fun setSoLinger(on: Boolean, linger: Int) {}
    override fun setReuseAddress(on: Boolean) {}
    override fun setOOBInline(on: Boolean) {}
    override fun setReceiveBufferSize(size: Int) {}
    override fun setSendBufferSize(size: Int) {}
    override fun setTrafficClass(tc: Int) {}

    override fun getTcpNoDelay(): Boolean = false
    override fun getKeepAlive(): Boolean = false
    override fun getSoTimeout(): Int = 0
    override fun getSoLinger(): Int = -1
    override fun getReuseAddress(): Boolean = false
    override fun getOOBInline(): Boolean = false

    override fun getRemoteSocketAddress(): SocketAddress? {
        if (port < 0) return null
        val peer = connection?.remoteAddress
        val resolved = if (peer != null) tunnelInetAddressNoDns(host, peer) else null
        return if (resolved != null) {
            InetSocketAddress(resolved, port)
        } else {
            InetSocketAddress.createUnresolved(host, port)
        }
    }
}

/**
 * Construct an [InetAddress] for a tunnel peer without consulting system DNS (#539).
 *
 * Returns a resolved [InetAddress] when [peerOrHost] is an IPv4 or IPv6 literal,
 * or `null` when only an un-resolved domain name is available (preventing leaks
 * or timeouts against the phone's clearnet/LAN DNS).
 */
internal fun tunnelInetAddressNoDns(hostnameLabel: String, peerOrHost: String?): InetAddress? {
    val candidate = peerOrHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val ipv4 = ProxySocketFactory.parseIpv4Literal(candidate)
    if (ipv4 != null) {
        return InetAddress.getByAddress(hostnameLabel.ifEmpty { candidate }, ipv4)
    }
    val ipv6 = ProxySocketFactory.parseIpv6Literal(candidate)
    if (ipv6 != null) {
        return InetAddress.getByAddress(hostnameLabel.ifEmpty { candidate }, ipv6)
    }
    return null
}
