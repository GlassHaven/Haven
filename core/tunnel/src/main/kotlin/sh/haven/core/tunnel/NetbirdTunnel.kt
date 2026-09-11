package sh.haven.core.tunnel

import sh.haven.rclone.binding.nbbridge.Conn as NativeConn
import sh.haven.rclone.binding.nbbridge.Listener as NativeListener
import sh.haven.rclone.binding.nbbridge.Nbbridge
import sh.haven.rclone.binding.nbbridge.TunnelHandle as NativeHandle
import sh.haven.rclone.binding.nbbridge.UDPConn as NativeUDPConn
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * [Tunnel] implementation backed by NetBird's `client/embed` library via
 * the gomobile-bound `nbbridge` package (rclone-android/go/nbbridge/).
 *
 * Construction blocks until the client has joined the network (up to 60
 * seconds on the Go side: a management handshake, a signal connection and
 * the first peer sync) — call on a background dispatcher.
 *
 * Auth is a setup key, consumed on first join; the peer's device identity
 * lives in [stateDir] (`config.json` + `state.json`) and is reused on
 * subsequent starts, so the key is not re-consumed. [managementURL]
 * points at a self-hosted NetBird management server; empty keeps the
 * hosted default.
 */
class NetbirdTunnel internal constructor(
    setupKey: String,
    stateDir: File,
    hostname: String,
    managementURL: String = "",
) : Tunnel {

    private val native: NativeHandle = try {
        Nbbridge.startTunnel(setupKey, stateDir.absolutePath, hostname, managementURL)
    } catch (e: Exception) {
        throw IOException("Failed to start NetBird tunnel: ${e.message}", e)
    }

    @Volatile
    private var socksCached: InetSocketAddress? = null

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val conn = try {
            native.dial(host, port.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            throw IOException("NetBird dial $host:$port failed: ${e.message}", e)
        }
        return NetbirdConnection(conn)
    }

    override fun listenUdp(): TunneledDatagramSocket? {
        val udp = try {
            native.listenUDP()
        } catch (e: Exception) {
            throw IOException("NetBird listenUDP failed: ${e.message}", e)
        }
        return NetbirdDatagramSocket(udp)
    }

    override fun socksAddress(): InetSocketAddress? {
        socksCached?.let { return it }
        return synchronized(this) {
            socksCached?.let { return@synchronized it }
            val port = try {
                native.startSocksListener().toInt()
            } catch (e: Exception) {
                throw IOException("NetBird SOCKS5 listener failed: ${e.message}", e)
            }
            InetSocketAddress("127.0.0.1", port).also { socksCached = it }
        }
    }

    override fun listenTcp(port: Int): TunneledServerSocket? {
        val ln = try {
            native.listenTCP(port.toLong())
        } catch (e: Exception) {
            throw IOException("NetBird listenTCP $port failed: ${e.message}", e)
        }
        return NetbirdTunneledServerSocket(ln)
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
 * copy-out-of-fresh-slice pattern as the Tailscale/WireGuard wrappers.
 * nbbridge's `Conn` exposes no remote-address accessor, so
 * [remoteAddress] stays null (the interface default).
 */
private class NetbirdConnection(
    private val conn: NativeConn,
) : TunneledConnection {

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
                throw IOException("NetBird write failed: ${e.message}", e)
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
 * timeout-translation contract as the other wrappers: Go's "i/o timeout"
 * / "deadline exceeded" becomes a null [TunneledDatagramSocket.receive]
 * return, which the Mosh receive loop requires.
 */
private class NetbirdDatagramSocket(
    private val udp: NativeUDPConn,
) : TunneledDatagramSocket {

    override fun send(data: ByteArray, host: String, port: Int) {
        try {
            udp.writeTo(data, host, port.toLong())
        } catch (e: Exception) {
            throw IOException("NetBird UDP writeTo $host:$port failed: ${e.message}", e)
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket? {
        val result = try {
            udp.readFrom(buf.size.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            if (isTimeoutException(e)) return null
            throw IOException("NetBird UDP readFrom failed: ${e.message}", e)
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
private class NetbirdTunneledServerSocket(
    private val ln: NativeListener,
) : TunneledServerSocket {

    override fun accept(): TunneledConnection {
        val conn = try {
            ln.accept()
        } catch (e: Exception) {
            throw IOException("NetBird accept failed: ${e.message}", e)
        }
        return NetbirdConnection(conn)
    }

    override fun close() {
        try {
            ln.close()
        } catch (_: Throwable) { /* idempotent */ }
    }
}