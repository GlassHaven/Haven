package sh.haven.core.tunnel

import sh.haven.rclone.binding.yggbridge.Conn as NativeConn
import sh.haven.rclone.binding.yggbridge.Listener as NativeListener
import sh.haven.rclone.binding.yggbridge.TunnelHandle as NativeHandle
import sh.haven.rclone.binding.yggbridge.UDPConn as NativeUDPConn
import sh.haven.rclone.binding.yggbridge.Yggbridge
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * [Tunnel] implementation backed by the yggdrasil-go mesh router via the
 * gomobile-bound `yggbridge` package (rclone-android/go/yggbridge/).
 *
 * Construction starts the mesh node behind a userspace gVisor netstack — call
 * on a background dispatcher. Unlike NetBird there is no state dir: the node
 * identity is the config PrivateKey, so [configJSON] (a yggdrasil NodeConfig
 * JSON, see [YggdrasilConfigBlob]) fully determines the mesh address. An empty
 * PrivateKey generates a fresh identity each start.
 *
 * No VpnService: overlay traffic lives in the netstack and underlay peer links
 * ride the device default route, so this coexists with the other tunnel types
 * without contending for the Android VPN slot. Destinations are mesh IPv6
 * literals (200::/7); there is no DNS over the mesh.
 */
class YggdrasilTunnel internal constructor(
    configJSON: String,
) : Tunnel {

    private val native: NativeHandle = try {
        Yggbridge.startTunnel(configJSON)
    } catch (e: Exception) {
        throw IOException("Failed to start Yggdrasil tunnel: ${e.message}", e)
    }

    @Volatile
    private var socksCached: InetSocketAddress? = null

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val conn = try {
            native.dial(host, port.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            throw IOException(yggdrasilDialFailureMessage(host, port, e.message), e)
        }
        return YggdrasilConnection(conn)
    }

    override fun listenUdp(): TunneledDatagramSocket? {
        val udp = try {
            native.listenUDP()
        } catch (e: Exception) {
            throw IOException("Yggdrasil listenUDP failed: ${e.message}", e)
        }
        return YggdrasilDatagramSocket(udp)
    }

    override fun socksAddress(): InetSocketAddress? {
        socksCached?.let { return it }
        return synchronized(this) {
            socksCached?.let { return@synchronized it }
            val port = try {
                native.startSocksListener().toInt()
            } catch (e: Exception) {
                throw IOException("Yggdrasil SOCKS5 listener failed: ${e.message}", e)
            }
            InetSocketAddress("127.0.0.1", port).also { socksCached = it }
        }
    }

    override fun listenTcp(port: Int): TunneledServerSocket? {
        val ln = try {
            native.listenTCP(port.toLong())
        } catch (e: Exception) {
            throw IOException("Yggdrasil listenTCP $port failed: ${e.message}", e)
        }
        return YggdrasilTunneledServerSocket(ln)
    }

    override fun localAddress(): String? =
        runCatching { native.localAddress() }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun close() {
        try {
            native.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }
}

/**
 * Wraps a native [NativeConn] as [TunneledConnection]. Same gomobile
 * copy-out-of-fresh-slice pattern as the NetBird/Tailscale/WireGuard wrappers.
 * yggbridge's `Conn` exposes RemoteAddrHost, so [remoteAddress] is populated.
 */
private class YggdrasilConnection(
    private val conn: NativeConn,
) : TunneledConnection {

    override val remoteAddress: String? =
        runCatching { conn.remoteAddrHost() }.getOrNull()?.takeIf { it.isNotBlank() }

    override val inputStream: InputStream = object : InputStream() {
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (len == 0) return 0
            val bytes = try {
                conn.read(len.toLong())
            } catch (_: Exception) {
                eof = true
                return -1
            }
            if (bytes == null || bytes.isEmpty()) {
                eof = true
                return -1
            }
            val n = minOf(bytes.size, len)
            System.arraycopy(bytes, 0, b, off, n)
            return n
        }
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xFF).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            try {
                conn.write(slice)
            } catch (e: Exception) {
                throw IOException("Yggdrasil write failed: ${e.message}", e)
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (_: Throwable) { /* idempotent */ }
    }
}

/**
 * Wraps a native [NativeUDPConn] as [TunneledDatagramSocket]. Same
 * timeout-translation contract as the other wrappers: Go's "i/o timeout" /
 * "deadline exceeded" becomes a null receive return (the Mosh contract).
 */
private class YggdrasilDatagramSocket(
    private val udp: NativeUDPConn,
) : TunneledDatagramSocket {

    override fun send(data: ByteArray, host: String, port: Int) {
        try {
            udp.writeTo(data, host, port.toLong())
        } catch (e: Exception) {
            throw IOException("Yggdrasil UDP writeTo $host:$port failed: ${e.message}", e)
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket? {
        val result = try {
            udp.readFrom(buf.size.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            if (isTimeoutException(e)) return null
            throw IOException("Yggdrasil UDP readFrom failed: ${e.message}", e)
        }
        if (result == null) return null
        val bytes = result.data ?: return null
        val n = minOf(bytes.size, buf.size)
        System.arraycopy(bytes, 0, buf, 0, n)
        return ReceivedPacket(
            length = n,
            srcHost = result.fromHost,
            srcPort = result.fromPort.toInt(),
        )
    }

    override fun close() {
        try {
            udp.close()
        } catch (_: Throwable) { /* idempotent */ }
    }

    private fun isTimeoutException(e: Throwable): Boolean {
        if (e is InterruptedIOException) return true
        val msg = e.message ?: return false
        return msg.contains("i/o timeout", ignoreCase = true) ||
            msg.contains("deadline exceeded", ignoreCase = true)
    }
}

/** Wraps a native [NativeListener] as [TunneledServerSocket]. */
private class YggdrasilTunneledServerSocket(
    private val ln: NativeListener,
) : TunneledServerSocket {

    override fun accept(): TunneledConnection {
        val conn = try {
            ln.accept()
        } catch (e: Exception) {
            throw IOException("Yggdrasil accept failed: ${e.message}", e)
        }
        return YggdrasilConnection(conn)
    }

    override fun close() {
        try {
            ln.close()
        } catch (_: Throwable) { /* idempotent */ }
    }
}

/**
 * #876: a mesh dial timeout usually means the local node has no working
 * peer link, not that the destination is down. LAN-only peer addresses
 * are unreachable from a remote network (Haven's per-connection tunnels
 * do not carry the tunnel's own underlay sockets), so name that case
 * instead of leaving the user with a bare "context deadline exceeded".
 */
internal fun yggdrasilDialFailureMessage(host: String, port: Int, raw: String?): String {
    val base = "Yggdrasil dial $host:$port failed: ${raw ?: "unknown error"}"
    if (raw == null || !raw.contains("context deadline exceeded")) return base
    return "$base. The mesh node could not reach that address within the dial timeout. " +
        "Yggdrasil needs at least one working peer link: if the configured peers are LAN-only " +
        "addresses, they are unreachable from a remote network. Connect the phone to that LAN, " +
        "or add a public peer both ends can reach."
}