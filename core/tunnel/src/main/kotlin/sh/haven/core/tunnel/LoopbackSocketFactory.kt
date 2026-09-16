package sh.haven.core.tunnel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * javax.net.SocketFactory that dials a fixed loopback bind regardless of
 * the endpoint the caller asks for.
 *
 * The AI route carriers (an SSH session's LOCAL forward, a Reticulum mesh
 * bridge) both present their remote endpoint as `127.0.0.1:<boundPort>`.
 * Routing an OPENAI profile's HTTP through this factory means the base URL
 * keeps the real endpoint name — TLS SNI and hostname verification still
 * run against it — while the actual dial lands on the carrier's forward.
 * Compare the SMB path, which rewrites the URL to loopback and must
 * therefore relax hostname verification; no rewrite, no relaxation here.
 *
 * Two dial shapes, mirroring [TunnelSocketFactory]:
 *
 *  - `createSocket(host, port)` (and friends) return an already-dialled
 *    socket.
 *  - `createSocket()` returns an unconnected socket whose [Socket.connect]
 *    ignores the requested endpoint and dials the loopback bind. OkHttp's
 *    connect path is exactly this: create the bare socket, then connect to
 *    the real endpoint address — which is the name we must NOT resolve and
 *    dial. [DeferredLoopbackSocket] bridges it, like
 *    [DeferredTunneledSocket] does for the tunnel factories.
 *
 * [dial] is injectable so tests can observe where the dial actually went.
 */
class LoopbackSocketFactory(
    private val port: Int,
    private val dialTimeoutMs: Int = 30_000,
    private val dial: (port: Int) -> Socket = { p ->
        Socket().apply {
            // IPv4 loopback by literal — on Android getLoopbackAddress() can
            // return ::1, and the carrier's forward binds 127.0.0.1 (JSch
            // LOCAL forwards bind IPv4), so an ::1 dial is refused.
            connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), p), dialTimeoutMs)
        }
    },
) : SocketFactory() {

    init {
        require(port > 0) { "Loopback bind port must be positive, got $port" }
    }

    override fun createSocket(): Socket = DeferredLoopbackSocket()

    override fun createSocket(host: String, port: Int): Socket = dial(this.port)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = dial(this.port)

    override fun createSocket(host: InetAddress, port: Int): Socket = dial(this.port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = dial(this.port)

    /**
     * Unconnected socket whose dial is deferred to [Socket.connect] and
     * pointed at the loopback bind whatever endpoint the caller names.
     */
    private inner class DeferredLoopbackSocket : Socket() {

        @Volatile
        private var closed: Boolean = false

        @Volatile
        private var socket: Socket? = null

        override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (closed) throw IOException("Socket is closed")
            // Endpoint ignored on purpose: OkHttp connects to the real
            // endpoint's address (it may even be unresolved-domain), and
            // the carrier's forward is at 127.0.0.1:<port>.
            socket = dial(this@LoopbackSocketFactory.port)
        }

        private fun conn(): Socket {
            if (closed) throw IOException("Socket is closed")
            return socket ?: throw IOException("Socket is not connected")
        }

        override fun getInputStream(): InputStream = conn().getInputStream()
        override fun getOutputStream(): OutputStream = conn().getOutputStream()

        override fun getInetAddress(): InetAddress? = socket?.inetAddress
        // Qualified: the unqualified name would resolve to the inherited
        // Socket.getPort() synthetic property (infinite recursion).
        override fun getPort(): Int = this@LoopbackSocketFactory.port
        override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun getLocalPort(): Int = socket?.localPort ?: -1

        override fun isConnected(): Boolean = socket != null && !closed
        override fun isBound(): Boolean = socket != null
        override fun isClosed(): Boolean = closed
        override fun isInputShutdown(): Boolean = closed || socket?.isInputShutdown == true
        override fun isOutputShutdown(): Boolean = closed || socket?.isOutputShutdown == true

        override fun close() {
            if (closed) return
            closed = true
            try {
                socket?.close()
            } catch (_: Throwable) {
                // best-effort teardown
            }
        }

        override fun bind(bindpoint: SocketAddress?) {
            // No-op — the loopback bind belongs to the carrier's forward.
        }

        override fun shutdownInput() { socket?.shutdownInput() }
        override fun shutdownOutput() { socket?.shutdownOutput() }

        // Socket options — OkHttp 5 (ConnectPlan.connectSocket) sets soTimeout
        // on the raw socket BEFORE connect(), and callers like reticulum-kt's
        // TCPClientInterface set them unconditionally on every dial (same
        // reason [DeferredTunneledSocket] accepts-and-ignores). Options set
        // before the deferred dial are dropped; ones set after it
        // (TcpNoDelay in particular) still reach the kernel socket.
        private fun apply(block: (Socket) -> Unit) {
            if (!closed) socket?.let(block)
        }

        override fun setTcpNoDelay(on: Boolean) = apply { it.tcpNoDelay = on }
        override fun setKeepAlive(on: Boolean) = apply { it.keepAlive = on }
        override fun setSoTimeout(timeout: Int) = apply { it.soTimeout = timeout }
        override fun setSoLinger(on: Boolean, linger: Int) = apply { it.setSoLinger(on, linger) }
        override fun setReuseAddress(on: Boolean) = apply { it.reuseAddress = on }
        override fun setOOBInline(on: Boolean) = apply { it.oobInline = on }
        override fun setReceiveBufferSize(size: Int) = apply { it.receiveBufferSize = size }
        override fun setSendBufferSize(size: Int) = apply { it.sendBufferSize = size }
        override fun setTrafficClass(tc: Int) = apply { it.trafficClass = tc }

        override fun getTcpNoDelay(): Boolean = socket?.tcpNoDelay ?: false
        override fun getKeepAlive(): Boolean = socket?.keepAlive ?: false
        override fun getSoTimeout(): Int = socket?.soTimeout ?: 0
        override fun getSoLinger(): Int = socket?.soLinger ?: -1
        override fun getReuseAddress(): Boolean = socket?.reuseAddress ?: false
        override fun getOOBInline(): Boolean = socket?.oobInline ?: false
        override fun getReceiveBufferSize(): Int = socket?.receiveBufferSize ?: 0
        override fun getSendBufferSize(): Int = socket?.sendBufferSize ?: 0
        override fun getTrafficClass(): Int = socket?.trafficClass ?: 0

        override fun getRemoteSocketAddress(): SocketAddress? =
            socket?.remoteSocketAddress
    }
}