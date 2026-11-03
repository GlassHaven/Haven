package sh.haven.core.tunnel

import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * JSch [ProxySOCKS5] adapter that reaches the SSH server through a SOCKS5
 * proxy via [ProxySocketFactory], instead of JSch's own `ProxySOCKS5.connect`.
 *
 * Why bypass JSch (#227, #777):
 *  1. mwiede/jsch's `ProxySOCKS5` builds the RFC 1929 username/password
 *     sub-negotiation using `String.length()` (the **character** count) for
 *     the length octets and the `arraycopy` length, while the field content is
 *     `Util.str2byte(...)` (**UTF-8 bytes**). For any non-ASCII credential the
 *     two disagree, so a malformed auth packet goes out and the proxy answers
 *     `server status = 01` (#227).
 *  2. `ProxySOCKS5` unconditionally encodes the destination `host` as
 *     `ATYP = 0x03` (`DOMAINNAME`), even when `host` is an IPv4 or IPv6
 *     literal (`192.168.x.x`, `10.x.x.x`, `fd00::1`). Proxies with DNS
 *     rebinding protection or IP-based LAN routing rules reject or misroute
 *     private IP literals sent as domain names (#777). [ProxySocketFactory]
 *     encodes literal IPv4 as `ATYP = 0x01`, literal IPv6 as `ATYP = 0x04`,
 *     and domain names as `ATYP = 0x03`.
 *
 * Subclasses [ProxySOCKS5] so existing callers/tests that check `is ProxySOCKS5`
 * continue to match while `connect` delegates to [ProxySocketFactory.createSocket].
 */
class AuthenticatedProxy(
    internal val factory: ProxySocketFactory,
) : ProxySOCKS5(factory.proxyHost, factory.proxyPort) {

    private var connectedSocket: Socket? = null

    init {
        if (!factory.proxyUser.isNullOrEmpty()) {
            setUserPasswd(factory.proxyUser, factory.proxyPassword ?: "")
        }
    }

    override fun connect(socketFactory: SocketFactory?, host: String, port: Int, timeout: Int) {
        // ProxySocketFactory owns its own connect timeout; JSch's `timeout`
        // (default 10 s) is ignored in favour of the factory's 30 s default,
        // matching TunnelProxy's "more headroom for proxy setup" stance.
        connectedSocket = factory.createSocket(host, port)
    }

    override fun getInputStream(): InputStream =
        connectedSocket?.getInputStream() ?: error("AuthenticatedProxy.connect() not called")

    override fun getOutputStream(): OutputStream =
        connectedSocket?.getOutputStream() ?: error("AuthenticatedProxy.connect() not called")

    override fun getSocket(): Socket? = connectedSocket

    override fun close() {
        try { connectedSocket?.close() } catch (_: Throwable) { /* best-effort */ }
        connectedSocket = null
    }
}

/**
 * JSch [ProxyHTTP] adapter that routes HTTP CONNECT proxies through
 * [ProxySocketFactory] so SSH connections send an `HTTP/1.1` `CONNECT`
 * request with a `Host:` header (JSch's `ProxyHTTP` sends `HTTP/1.0` with no
 * `Host:` header, which strict HTTP/1.1 proxies reject) and UTF-8 Basic auth.
 */
class HttpConnectProxy(
    internal val factory: ProxySocketFactory,
) : ProxyHTTP(factory.proxyHost, factory.proxyPort) {

    private var connectedSocket: Socket? = null

    init {
        if (!factory.proxyUser.isNullOrEmpty()) {
            setUserPasswd(factory.proxyUser, factory.proxyPassword ?: "")
        }
    }

    override fun connect(socketFactory: SocketFactory?, host: String, port: Int, timeout: Int) {
        connectedSocket = factory.createSocket(host, port)
    }

    override fun getInputStream(): InputStream =
        connectedSocket?.getInputStream() ?: error("HttpConnectProxy.connect() not called")

    override fun getOutputStream(): OutputStream =
        connectedSocket?.getOutputStream() ?: error("HttpConnectProxy.connect() not called")

    override fun getSocket(): Socket? = connectedSocket

    override fun close() {
        try { connectedSocket?.close() } catch (_: Throwable) { /* best-effort */ }
        connectedSocket = null
    }
}
