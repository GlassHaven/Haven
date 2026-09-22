package sh.haven.core.tunnel

import sh.haven.rclone.binding.ovpnbridge.Conn as NativeConn
import sh.haven.rclone.binding.ovpnbridge.Ovpnbridge
import sh.haven.rclone.binding.ovpnbridge.TunnelHandle as NativeHandle
import java.io.IOException
import java.io.InputStream
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
 * on [close]. `ovpnbridge` doesn't expose inbound listeners in v1, so
 * [listenUdp]/[listenTcp] are left at [Tunnel]'s default `null`.
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

    override fun localAddress(): String? = native.bindAddr().takeIf { it.isNotBlank() }

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
