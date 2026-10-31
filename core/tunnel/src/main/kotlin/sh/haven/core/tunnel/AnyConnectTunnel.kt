package sh.haven.core.tunnel

import sh.haven.rclone.binding.ocbridge.Conn as NativeConn
import sh.haven.rclone.binding.ocbridge.Listener as NativeListener
import sh.haven.rclone.binding.ocbridge.Ocbridge
import sh.haven.rclone.binding.ocbridge.Prompter
import sh.haven.rclone.binding.ocbridge.TunnelHandle as NativeHandle
import sh.haven.rclone.binding.ocbridge.UDPConn as NativeUDPConn
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * [Tunnel] implementation for Cisco AnyConnect and ocserv gateways, backed
 * by the gomobile-bound `ocbridge` package (rclone-android/go/ocbridge/):
 * Cisco's aggregate XML auth, then CSTP (IP packets framed over TLS) into a
 * private gVisor netstack. Only profiles routed through this tunnel use the
 * VPN — the rest of the phone, and Haven's other profiles, don't — and no
 * VpnService or Cisco app is involved.
 *
 * Construction blocks until the session is up (up to 60 s: TLS, one to
 * four auth rounds, CONNECT) — call on a background dispatcher. After that
 * the native side keeps the session alive by itself: DPD/keepalives, and on
 * a network change a reconnect with the session cookie (or a fresh login if
 * the gateway expired it) while open TCP streams in the netstack wait it out.
 *
 * Supported: username/password, auth group, TOTP, fixed or interactive
 * (SMS) second factor, certificate pinning. Not supported (fails fast with a clear message):
 * SAML/SSO, client certificates, HostScan/posture. TLS only, no DTLS.
 */
class AnyConnectTunnel internal constructor(
    config: AnyConnectConfigBlob,
    /** The tunnel's user-facing name, for prompts and the notification. */
    val label: String = "",
    authBroker: TunnelAuthBroker? = null,
) : Tunnel {

    override val lingerAfterRelease: Boolean = config.stayConnected

    // Questions the stored config can't answer (an SMS code, an unstored
    // password) go to the broker, which shows a dialog and a heads-up
    // notification. Called on a Go thread; blocks until answered.
    private val prompter: Prompter? = authBroker?.let { broker ->
        object : Prompter {
            override fun prompt(title: String?, message: String?, label: String?, secret: Boolean): String =
                broker.promptBlocking(
                    tunnelLabel = this@AnyConnectTunnel.label,
                    account = config.username,
                    title = title.orEmpty(),
                    message = message.orEmpty(),
                    fieldLabel = label.orEmpty(),
                    secret = secret,
                ) ?: ""
        }
    }

    private val native: NativeHandle = try {
        Ocbridge.startTunnelWithPrompter(config.toNativeConfig(), prompter)
    } catch (e: Exception) {
        throw IOException("AnyConnect: ${e.message}", e)
    }

    @Volatile
    private var socksCached: InetSocketAddress? = null

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val conn = try {
            native.dial(host, port.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            throw IOException("AnyConnect dial $host:$port failed: ${e.message}", e)
        }
        return AnyConnectConnection(conn)
    }

    override fun listenUdp(): TunneledDatagramSocket? {
        val udp = try {
            native.listenUDP()
        } catch (e: Exception) {
            throw IOException("AnyConnect listenUDP failed: ${e.message}", e)
        }
        return AnyConnectDatagramSocket(udp)
    }

    override fun socksAddress(): InetSocketAddress? {
        socksCached?.let { return it }
        return synchronized(this) {
            socksCached?.let { return@synchronized it }
            val port = try {
                native.startSocksListener().toInt()
            } catch (e: Exception) {
                throw IOException("AnyConnect SOCKS5 listener failed: ${e.message}", e)
            }
            InetSocketAddress("127.0.0.1", port).also { socksCached = it }
        }
    }

    override fun listenTcp(port: Int): TunneledServerSocket? {
        val ln = try {
            native.listenTCP(port.toLong())
        } catch (e: Exception) {
            throw IOException("AnyConnect listenTCP $port failed: ${e.message}", e)
        }
        return AnyConnectTunneledServerSocket(ln)
    }

    override fun localAddress(): String? =
        runCatching { native.bindAddr() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** True once the session has ended on its own; see [lastError]. */
    override val isDead: Boolean get() = lastError() != null

    /**
     * Why the session ended on its own (credentials no longer accepted,
     * gateway terminated it, address changed on reconnect), or null while
     * it is up. Dials after such a failure already fail with this cause.
     */
    fun lastError(): String? =
        runCatching { native.lastError() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Drop the connection but leave the gateway session resumable: the next
     * start for this config reuses it without a password or second factor,
     * for as long as the gateway keeps it (its idle timeout).
     */
    override fun close() {
        try {
            native.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }

    /** Close and end the session on the gateway; the next start signs in. */
    fun signOut() {
        try {
            native.logout()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }

    /**
     * After a network change or return to the foreground: probe the gateway
     * and reconnect at once if the connection died silently. Non-blocking.
     */
    fun revive() {
        runCatching { native.revive() }
    }

    companion object {
        /**
         * Sign out a session kept resumable for [config] when no tunnel is
         * using it (idle "Sign out", or the config was edited / deleted).
         * Makes one HTTPS request — call off the main thread.
         */
        fun endSession(config: AnyConnectConfigBlob) {
            runCatching { Ocbridge.endSession(config.toNativeConfig()) }
        }

        /** True if starting [config] would resume rather than sign in. */
        fun hasResumableSession(config: AnyConnectConfigBlob): Boolean =
            runCatching { Ocbridge.hasResumableSession(config.toNativeConfig()) }.getOrDefault(false)
    }
}

/**
 * Wraps a native [NativeConn] as [TunneledConnection]. Same gomobile
 * copy-out-of-fresh-slice pattern as the WireGuard wrapper.
 */
private class AnyConnectConnection(
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
                throw IOException("AnyConnect write failed: ${e.message}", e)
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
private class AnyConnectDatagramSocket(
    private val udp: NativeUDPConn,
) : TunneledDatagramSocket {

    override fun send(data: ByteArray, host: String, port: Int) {
        try {
            udp.writeTo(data, host, port.toLong())
        } catch (e: Exception) {
            throw IOException("AnyConnect UDP writeTo $host:$port failed: ${e.message}", e)
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket? {
        val result = try {
            udp.readFrom(buf.size.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            if (isTimeoutException(e)) return null
            throw IOException("AnyConnect UDP readFrom failed: ${e.message}", e)
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
private class AnyConnectTunneledServerSocket(
    private val ln: NativeListener,
) : TunneledServerSocket {

    override fun accept(): TunneledConnection {
        val conn = try {
            ln.accept()
        } catch (e: Exception) {
            throw IOException("AnyConnect accept failed: ${e.message}", e)
        }
        return AnyConnectConnection(conn)
    }

    override fun close() {
        try {
            ln.close()
        } catch (_: Throwable) { /* idempotent */ }
    }
}
