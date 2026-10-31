package sh.haven.core.tunnel

import sh.haven.rclone.binding.ovpnbridge.Conn as NativeConn
import sh.haven.rclone.binding.ovpnbridge.Listener as NativeListener
import sh.haven.rclone.binding.ovpnbridge.Ovpnbridge
import sh.haven.rclone.binding.ovpnbridge.TunnelHandle as NativeHandle
import sh.haven.rclone.binding.ovpnbridge.UDPConn as NativeUDPConn
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * [Tunnel] implementation backed by OpenVPN3 Core + gVisor netstack via
 * the gomobile-bound `ovpnbridge` package (co-located in rclone-android so
 * both gomobile bindings ship in a single `libgojni.so` — see
 * rclone-android/go/ovpnbridge/).
 *
 * Holds one live native tunnel handle — created up-front from the user's
 * .ovpn profile text plus optional `auth-user-pass` credentials, torn down
 * on [close]. Supports outbound TCP ([dial]), UDP ([listenUdp] for Mosh),
 * localhost SOCKS5 ([socksAddress] for rclone / IronRDP), and inbound TCP
 * ([listenTcp] on the tunnel interface IP).
 */
class OpenVpnTunnel internal constructor(
    configText: String,
    username: String?,
    password: String?,
) : Tunnel {

    private val native: NativeHandle = try {
        Ovpnbridge.startTunnel(configText, username.orEmpty(), password.orEmpty())
    } catch (e: Exception) {
        throw IOException("Failed to start OpenVPN tunnel: ${e.message}", e)
    }

    @Volatile
    private var socksCached: InetSocketAddress? = null

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val conn = try {
            native.dial(host, port.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            throw IOException("OpenVPN dial $host:$port failed: ${e.message}", e)
        }
        return NativeOvpnTunneledConnection(conn)
    }

    override fun listenUdp(): TunneledDatagramSocket? {
        val udp = try {
            native.listenUDP()
        } catch (e: Exception) {
            throw IOException("OpenVPN listenUDP failed: ${e.message}", e)
        }
        return NativeOvpnDatagramSocket(udp)
    }

    override fun socksAddress(): InetSocketAddress? {
        socksCached?.let { return it }
        return synchronized(this) {
            socksCached?.let { return@synchronized it }
            val port = try {
                native.startSocksListener().toInt()
            } catch (e: Exception) {
                throw IOException("OpenVPN SOCKS5 listener failed: ${e.message}", e)
            }
            InetSocketAddress("127.0.0.1", port).also { socksCached = it }
        }
    }

    override fun listenTcp(port: Int): TunneledServerSocket? {
        val ln = try {
            native.listenTCP(port.toLong())
        } catch (e: Exception) {
            throw IOException("OpenVPN listenTCP $port failed: ${e.message}", e)
        }
        return NativeOvpnTunneledServerSocket(ln)
    }

    override fun localAddress(): String? =
        runCatching { native.bindAddr() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** True once the OpenVPN session has terminated fatally on its own; see [lastError]. */
    override val isDead: Boolean get() = lastError() != null

    /**
     * Fatal reason the OpenVPN session ended on its own (e.g. AUTH_FAILED or
     * server-side disconnect), or null while up.
     */
    fun lastError(): String? =
        runCatching { native.lastError() }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun close() {
        try {
            native.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }
}

/**
 * Wraps a native [NativeConn] as [TunneledConnection]. The Go side returns
 * fresh byte slices from `read(size)` because gomobile copies `[]byte`
 * arguments across the JNI boundary — the caller's buffer wouldn't be
 * updated otherwise. Same shape as WireguardTunnel's own wrapper.
 */
private class NativeOvpnTunneledConnection(
    private val conn: NativeConn,
) : TunneledConnection {

    override val remoteAddress: String?
        get() = runCatching { conn.remoteAddrHost() }.getOrNull()?.takeIf { it.isNotBlank() }

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
                throw IOException("OpenVPN write failed: ${e.message}", e)
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (_: Throwable) {
            // Already-closed connections raise here; ignore.
        }
    }
}

/**
 * Wraps a native [NativeUDPConn] as [TunneledDatagramSocket]. Go's "i/o
 * timeout" / "deadline exceeded" becomes a null [TunneledDatagramSocket.receive]
 * return, which the Mosh receive loop expects.
 */
private class NativeOvpnDatagramSocket(
    private val udp: NativeUDPConn,
) : TunneledDatagramSocket {

    override fun send(data: ByteArray, host: String, port: Int) {
        try {
            udp.writeTo(data, host, port.toLong())
        } catch (e: Exception) {
            throw IOException("OpenVPN UDP writeTo $host:$port failed: ${e.message}", e)
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket? {
        val result = try {
            udp.readFrom(buf.size.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            if (isTimeoutException(e)) return null
            throw IOException("OpenVPN UDP readFrom failed: ${e.message}", e)
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
        } catch (_: Throwable) {
            // Idempotent.
        }
    }

    private fun isTimeoutException(e: Throwable): Boolean {
        if (e is InterruptedIOException) return true
        val msg = e.message ?: return false
        return msg.contains("i/o timeout", ignoreCase = true) ||
            msg.contains("deadline exceeded", ignoreCase = true)
    }
}

/** Wraps a native [NativeListener] as [TunneledServerSocket]. */
private class NativeOvpnTunneledServerSocket(
    private val ln: NativeListener,
) : TunneledServerSocket {

    override fun accept(): TunneledConnection {
        val conn = try {
            ln.accept()
        } catch (e: Exception) {
            throw IOException("OpenVPN accept failed: ${e.message}", e)
        }
        return NativeOvpnTunneledConnection(conn)
    }

    override fun close() {
        try {
            ln.close()
        } catch (_: Throwable) {
            // Idempotent.
        }
    }
}
