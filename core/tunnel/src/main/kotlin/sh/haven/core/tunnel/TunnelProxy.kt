package sh.haven.core.tunnel

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * JSch [Proxy] adapter over a [Tunnel]. JSch hands us `(host, port)` at
 * connect time; we dial through the tunnel and surface the resulting
 * streams. `getSocket()` returns null — our connections are not backed by
 * a kernel socket (see [TunneledConnection]), and [Proxy] contract allows
 * null there.
 *
 * Mirrors the shape of [sh.haven.core.ssh.ProxyJump] — the existing JSch
 * proxy for jump-host tunneling. Same lifecycle (`connect` → stream I/O →
 * `close`), just a different source of bytes.
 */
class TunnelProxy(private val tunnel: Tunnel) : Proxy {

    private var connection: TunneledConnection? = null

    override fun connect(factory: SocketFactory?, host: String, port: Int, timeout: Int) {
        connection = tunnel.dial(host, port, timeout)
    }

    override fun getInputStream(): InputStream =
        connection?.inputStream ?: error("TunnelProxy.connect() not called")

    override fun getOutputStream(): OutputStream =
        connection?.outputStream ?: error("TunnelProxy.connect() not called")

    override fun getSocket(): Socket? = null

    override fun close() {
        connection?.close()
        connection = null
    }
}
