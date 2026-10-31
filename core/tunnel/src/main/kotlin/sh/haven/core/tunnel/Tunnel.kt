package sh.haven.core.tunnel

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * A userspace network tunnel. Implementations can be backed by WireGuard,
 * Tailscale (follow-up), or any other backend that can produce a
 * [TunneledConnection] for a `(host, port)` pair.
 *
 * Tunnels are expected to be reference-counted by a [TunnelManager] — the
 * same tunnel handle may be used by multiple in-flight connections.
 */
interface Tunnel {
    /**
     * Dial a host through this tunnel. Must not block indefinitely — obey
     * [timeoutMs] and throw on timeout. The returned [TunneledConnection] is
     * owned by the caller and must be closed when done.
     */
    fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection

    /**
     * Open an unconnected UDP socket through the tunnel. Caller supplies
     * the destination on every send; the inbound source is surfaced on
     * every receive. Returned socket is owned by the caller and must be
     * closed when done.
     *
     * Default implementation returns null — backends that can't carry
     * UDP (Cloudflare Access via websocket, SOCKS/HTTP legacy proxies)
     * opt out by inheriting the default and the caller falls through to
     * a raw [java.net.DatagramSocket].
     *
     * Used by Mosh (#164) so packets traverse the tunnel rather than
     * the device's default route.
     */
    fun listenUdp(): TunneledDatagramSocket? = null

    /**
     * Lazily start a localhost SOCKS5 listener fronting this tunnel and
     * return its bound address (always 127.0.0.1, OS-assigned port).
     * Idempotent — subsequent calls return the same address. Closing the
     * tunnel also closes the listener.
     *
     * Used by transports that can't be intercepted at the Kotlin Socket
     * layer (rclone via HTTPS_PROXY, IronRDP via a vendored SOCKS5
     * client) so a single SOCKS5 endpoint fronts every TCP transport.
     *
     * Default implementation returns null — implementations that don't
     * meaningfully expose a SOCKS surface (test fakes) opt out by
     * inheriting the default.
     */
    fun socksAddress(): InetSocketAddress? = null

    /**
     * Bind a TCP listener on the tunnel's own interface address inside the
     * userspace netstack and return it, or null if the backend can't accept
     * inbound connections. Lets an on-device server (the MCP endpoint, #176)
     * accept connections from tunnel peers at [localAddress]:[port], stable
     * across the device's WiFi/hotspot roams. Caller owns the returned
     * socket and must close it. Closing the tunnel also tears it down.
     */
    fun listenTcp(port: Int): TunneledServerSocket? = null

    /**
     * The tunnel's own interface address (the WireGuard `[Interface]`
     * Address) — both the bind host for [listenTcp] and the host a peer
     * dials to reach a server bound on it. Null when the backend has no
     * meaningful local address.
     */
    fun localAddress(): String? = null

    /**
     * Keep this tunnel up after its last dependent releases it, until it is
     * closed explicitly ([TunnelManager.disconnect]). AnyConnect sets this
     * when the user wants it to behave like Cisco's always-on client; for
     * the other backends re-establishing is cheap and silent, so they don't.
     */
    val lingerAfterRelease: Boolean get() = false

    /**
     * True once the underlying tunnel session has terminated permanently on
     * its own (e.g. fatal server disconnect or rejected credentials). When
     * true, [TunnelManager.acquire] evicts and replaces the cached handle
     * rather than handing out a dead tunnel.
     */
    val isDead: Boolean get() = false

    /**
     * Tear down the tunnel. All outstanding connections are invalidated.
     * Idempotent.
     */
    fun close()
}

/**
 * An accepting socket bound inside a [Tunnel]'s netstack. Each [accept]
 * blocks until a peer connects, returning a [TunneledConnection]. Mirrors
 * [java.net.ServerSocket] without exposing a kernel socket.
 */
interface TunneledServerSocket {
    /** Block until a peer connects. Throws [java.io.IOException] once closed. */
    fun accept(): TunneledConnection

    /** Stop accepting. Idempotent. Unblocks a pending [accept] with an error. */
    fun close()
}

/**
 * A byte-stream through a [Tunnel]. Mirrors [java.net.Socket] but without
 * exposing a real [java.net.Socket] — our WireGuard implementation routes
 * data through an in-process userspace netstack, not the kernel socket
 * layer, so `getSocket()` on the JSch side returns null.
 */
interface TunneledConnection {
    val inputStream: InputStream
    val outputStream: OutputStream

    /**
     * The literal IP this connection was actually established to — the
     * tunnel-resolved form of whatever name was dialled (Tailscale MagicDNS
     * included), or null when the backend can't say. Callers that must
     * satisfy an IP-literal contract (#539: the mosh UDP destination) use
     * this instead of re-resolving the profile host in the wrong namespace.
     */
    val remoteAddress: String? get() = null

    /** Close the connection. Idempotent. */
    fun close()
}
