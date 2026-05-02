package sh.haven.core.tunnel

import sh.haven.rclone.binding.tsbridge.Conn as NativeConn
import sh.haven.rclone.binding.tsbridge.Tsbridge
import sh.haven.rclone.binding.tsbridge.TunnelHandle as NativeHandle
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [Tunnel] implementation backed by Tailscale's `tsnet` library via the
 * gomobile-bound `tsbridge` package (co-located in rclone-android's Go
 * module alongside wgbridge + rcbridge — see
 * rclone-android/go/tsbridge/).
 *
 * Construction blocks until the tailnet connection is established (up to
 * 30 seconds enforced on the Go side) — call on a background dispatcher.
 *
 * Unlike WireGuard's config blob, Tailscale auth is an authkey that's
 * only consumed on first use; persistent node state lives in [stateDir]
 * and is reused on subsequent starts of the same [TunnelConfig], so the
 * authkey stays valid for its configured lifetime in the admin console.
 *
 * [controlURL] points at a self-hosted Headscale (or other compatible)
 * coordination server; empty keeps the default controlplane.tailscale.com.
 */
class TailscaleTunnel internal constructor(
    authKey: String,
    stateDir: File,
    hostname: String,
    controlURL: String = "",
) : Tunnel {

    private val native: NativeHandle = try {
        Tsbridge.startTunnel(authKey, stateDir.absolutePath, hostname, controlURL)
    } catch (e: Exception) {
        throw IOException("Failed to start Tailscale tunnel: ${e.message}", e)
    }

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val conn = try {
            native.dial(host, port.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            throw IOException("Tailscale dial $host:$port failed: ${e.message}", e)
        }
        return TailscaleConnection(conn)
    }

    override fun close() {
        try {
            native.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }
}

/**
 * Wraps a native [NativeConn] as [TunneledConnection]. Mirrors the
 * WireGuard wrapper — gomobile copies `[]byte` across the JNI boundary,
 * so `Conn.read(size)` returns a fresh slice each call and we copy into
 * the caller's buffer in the [InputStream] shim.
 */
private class TailscaleConnection(
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
                throw IOException("Tailscale write failed: ${e.message}", e)
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (_: Throwable) { /* idempotent */ }
    }
}
