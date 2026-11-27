package sh.haven.core.tunnel

import sh.haven.rclone.binding.wgbridge.Conn as NativeConn
import sh.haven.rclone.binding.wgbridge.Listener as NativeListener
import sh.haven.rclone.binding.wgbridge.TunnelHandle as GomobileHandle
import sh.haven.rclone.binding.wgbridge.UDPConn as NativeUDPConn
import sh.haven.rclone.binding.wgbridge.Wgbridge
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * Kotlin-side view of the gomobile tunnel handle. The binding's classes are
 * final and JNI-backed (class init links libgojni, absent in JVM unit
 * tests), so [WireguardTunnel] works against these seams and the gomobile
 * types appear only inside the [GomobileWgNative] adapter — tests inject a
 * pure-Kotlin fake via the tunnel's `starter`.
 *
 * The shape mirrors the Go API one-to-one (rclone-android/go/wgbridge/wgbridge.go):
 * one handle, dial / listenUDP / startSocksListener / listenTCP.
 */
internal interface WgNativeDevice : AutoCloseable {
    fun dial(host: String, port: Long, timeoutMs: Long): WgNativeConn
    fun listenUdp(): WgNativeUdpConn
    fun startSocksListener(): Long
    fun listenTcp(port: Long): WgNativeListener
    fun bindAddr(): String
}

internal interface WgNativeConn : AutoCloseable {
    fun read(size: Int): ByteArray?
    fun write(data: ByteArray)
    fun remoteAddrHost(): String
    override fun close()
}

internal interface WgNativeUdpConn : AutoCloseable {
    fun readFrom(size: Int, timeoutMs: Int): WgUdpPacket?
    fun writeTo(data: ByteArray, host: String, port: Int)
    override fun close()
}

internal class WgUdpPacket(
    val data: ByteArray?,
    val fromHost: String?,
    val fromPort: Int,
)

internal interface WgNativeListener : AutoCloseable {
    fun accept(): WgNativeConn
    override fun close()
}

/**
 * Production [WgNativeDevice]: over the gomobile binding. Static methods
 * ([GomobileWgNative.start]) are the only place the JNI-backed classes are
 * touched.
 */
internal object GomobileWgNative {
    fun start(preparedConfig: String, networkHandle: Long = 0L): WgNativeDevice =
        if (networkHandle != 0L) {
            impl(Wgbridge.startTunnelOnNetwork(preparedConfig, networkHandle))
        } else {
            impl(Wgbridge.startTunnel(preparedConfig))
        }

    fun impl(handle: GomobileHandle): WgNativeDevice = object : WgNativeDevice {
        override fun dial(host: String, port: Long, timeoutMs: Long): WgNativeConn =
            ConnBinding(handle.dial(host, port, timeoutMs))

        override fun listenUdp(): WgNativeUdpConn = UdpBinding(handle.listenUDP())

        override fun startSocksListener(): Long = handle.startSocksListener()

        override fun listenTcp(port: Long): WgNativeListener =
            ListenerBinding(handle.listenTCP(port))

        override fun bindAddr(): String = handle.bindAddr()

        override fun close() {
            handle.close()
        }
    }
}

private class ConnBinding(private val conn: NativeConn) : WgNativeConn {
    override fun read(size: Int): ByteArray? = conn.read(size.toLong())
    override fun write(data: ByteArray) {
        conn.write(data)
    }
    override fun remoteAddrHost(): String = conn.remoteAddrHost()
    override fun close() {
        conn.close()
    }
}

private class UdpBinding(private val udp: NativeUDPConn) : WgNativeUdpConn {
    override fun readFrom(size: Int, timeoutMs: Int): WgUdpPacket? {
        val r = udp.readFrom(size.toLong(), timeoutMs.toLong()) ?: return null
        return WgUdpPacket(r.data, r.fromHost, r.fromPort.toInt())
    }
    override fun writeTo(data: ByteArray, host: String, port: Int) {
        udp.writeTo(data, host, port.toLong())
    }
    override fun close() {
        udp.close()
    }
}

private class ListenerBinding(private val listener: NativeListener) : WgNativeListener {
    override fun accept(): WgNativeConn = ConnBinding(listener.accept())
    override fun close() {
        listener.close()
    }
}

/**
 * [Tunnel] implementation backed by wireguard-go + gVisor netstack via
 * the gomobile-bound `wgbridge` package (co-located in rclone-android so
 * both gomobile bindings ship in a single `libgojni.so` — see
 * rclone-android/go/wgbridge/).
 *
 * Holds one live native tunnel handle — created up-front from the user's
 * wg-quick config text, torn down on [close]. Dials are safe to call
 * concurrently; the native layer serialises as needed.
 *
 * #WG-reliability: on repeated dial timeouts (roam onto a new network, the
 * peer endpoint's DDNS name moving) the handle bounces itself — a fresh
 * start re-resolves the endpoint and re-homes the UDP bind, which the
 * userspace device never does on its own. Cooldown-bounded so a dead path
 * can't thrash.
 */
class WireguardTunnel internal constructor(
    internal val configText: String,
    /**
     * Native bring-up seam. Null = the real binding ([GomobileWgNative]);
     * unit tests inject a pure-Kotlin fake (the real class init links
     * libgojni, absent on the JVM). Receives the keepalive-injected config.
     */
    private val nativeStarter: ((String) -> WgNativeDevice)? = null,
    private val clockMs: () -> Long = {
        SystemClock.elapsedRealtime().takeIf { it > 0L } ?: (System.nanoTime() / 1_000_000L)
    },
    /**
     * Returns an opaque identifier for the host's current default network
     * (`ConnectivityManager.activeNetwork?.networkHandle ?: 0L`). Used by
     * [reviveIfNetworkChanged] so periodic foreground-revive ticks are a
     * no-op while the phone remains on the same network (avoiding tearing
     * down active tunneled connections), while an actual Wi-Fi ↔ Cellular
     * transition bounces the handle immediately.
     */
    private val networkKeyProvider: () -> Long = { 0L },
    private val nativeStarterWithNetwork: ((String, Long) -> WgNativeDevice)? = null,
    private val underlayNetworkHandleProvider: () -> Long = { 0L },
) : Tunnel {

    private val nativeLock = Any()

    @Volatile
    private var boundNetworkKey: Long = networkKeyProvider()

    @Volatile
    private var native: WgNativeDevice = try {
        launchNative(ensurePeerKeepalive(configText))
    } catch (e: Exception) {
        throw IOException("Failed to start WireGuard tunnel: ${e.message}", e)
    }

    @Volatile
    private var socksCached: InetSocketAddress? = null

    /** Consecutive dial timeouts since the last successful dial (reset on success). */
    @Volatile
    private var consecutiveDialTimeouts = 0

    /** Monotonic ms of the last dial-timeout bounce; a cooldown keeps a dead path from thrashing. */
    @Volatile
    private var lastBounceMs = 0L

    /** Monotonic ms of the last network-change [revive] bounce. */
    @Volatile
    private var lastReviveMs = 0L

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val splitForBounce = timeoutMs >= MIN_SPLIT_TIMEOUT_MS && canBounceOnNextTimeout()
        val firstTimeoutMs = if (splitForBounce) {
            (timeoutMs / 2).coerceAtMost(FIRST_ATTEMPT_MAX_TIMEOUT_MS)
        } else {
            timeoutMs
        }
        val conn = try {
            val c = native.dial(host, port.toLong(), firstTimeoutMs.toLong())
            consecutiveDialTimeouts = 0
            c
        } catch (e: Exception) {
            if (isDialTimeout(e)) {
                // Roamed onto a new network / the endpoint hostname moved: the
                // userspace device keeps dialling into a dead bind with no
                // recovery of its own (the WG server simply drops the stale
                // session's frames). Bouncing the native handle forces a fresh
                // endpoint resolution + UDP rebind — what the official app's
                // on-demand re-resolution does.
                consecutiveDialTimeouts++
                if (maybeBounce("dial timeout to $host:$port")) {
                    val retryTimeoutMs = if (splitForBounce) {
                        maxOf(timeoutMs - firstTimeoutMs, timeoutMs / 2)
                    } else {
                        timeoutMs
                    }
                    try {
                        val retryConn = native.dial(host, port.toLong(), retryTimeoutMs.toLong())
                        consecutiveDialTimeouts = 0
                        return NativeTunneledConnection(retryConn)
                    } catch (retryEx: Exception) {
                        if (isDialTimeout(retryEx)) {
                            consecutiveDialTimeouts++
                        }
                        throw IOException("WireGuard dial $host:$port failed: ${retryEx.message}", retryEx)
                    }
                }
            }
            throw IOException("WireGuard dial $host:$port failed: ${e.message}", e)
        }
        return NativeTunneledConnection(conn)
    }

    /** Minimum dial timeouts in a row before bouncing. */
    internal var bounceAfterTimeouts: Int = BOUNCE_AFTER_TIMEOUTS

    private fun canBounceOnNextTimeout(): Boolean {
        if (consecutiveDialTimeouts + 1 < bounceAfterTimeouts) return false
        val now = clockMs()
        return lastBounceMs == 0L || now - lastBounceMs >= BOUNCE_COOLDOWN_MS
    }

    /**
     * Recreate the native tunnel handle when dials time out. The 60 s
     * cooldown bounds the churn. Listener/SOCKS objects minted from the
     * old netstack die with it — the MCP binder's retry loop re-binds them
     * from the fresh handle (acquireFirstWireguard finds the same Kotlin
     * Tunnel instance, whose native is now new).
     *
     * Returns true iff the native handle was replaced with a fresh one.
     */
    internal fun maybeBounce(why: String): Boolean {
        val now = clockMs()
        if (consecutiveDialTimeouts < bounceAfterTimeouts) return false
        if (lastBounceMs != 0L && now - lastBounceMs < BOUNCE_COOLDOWN_MS) return false
        synchronized(nativeLock) {
            val nowLocked = clockMs()
            // Re-check under the lock; another thread may have just bounced.
            if (consecutiveDialTimeouts < bounceAfterTimeouts) return false
            if (lastBounceMs != 0L && nowLocked - lastBounceMs < BOUNCE_COOLDOWN_MS) return false
            lastBounceMs = nowLocked
            return bounceLocked(why)
        }
    }

    /**
     * Bounce the native tunnel handle only if the host's active network
     * identifier has changed since the current handle was started. Safe to
     * call from periodic watchdog ticks without disrupting live sessions on
     * an unchanged network.
     */
    fun reviveIfNetworkChanged(reason: String = "network change"): Boolean {
        val currentKey = networkKeyProvider()
        if (currentKey == 0L || currentKey == boundNetworkKey) return false
        return revive("$reason ($boundNetworkKey -> $currentKey)")
    }

    /**
     * Proactively recreate the native tunnel handle when the host's active
     * network changes (Wi-Fi ↔ Cellular, VPN teardown, return to foreground).
     * Uses a short debounce ([REVIVE_COOLDOWN_MS]) to coalesce back-to-back
     * connectivity callbacks without waiting for dial timeouts.
     */
    fun revive(reason: String = "network change"): Boolean {
        val now = clockMs()
        if (lastReviveMs != 0L && now - lastReviveMs < REVIVE_COOLDOWN_MS) return false
        synchronized(nativeLock) {
            val nowLocked = clockMs()
            if (lastReviveMs != 0L && nowLocked - lastReviveMs < REVIVE_COOLDOWN_MS) return false
            lastReviveMs = nowLocked
            return bounceLocked("revive: $reason")
        }
    }

    /** Must hold [nativeLock]. */
    private fun bounceLocked(why: String): Boolean {
        return try {
            val fresh = launchNative(ensurePeerKeepalive(configText))
            native.close()
            native = fresh
            boundNetworkKey = networkKeyProvider()
            consecutiveDialTimeouts = 0
            socksCached = null // the old SOCKS listener died with the old netstack
            Log.i(TAG, "WG tunnel bounced ($why): endpoint re-resolved, bind re-homed")
            true
        } catch (e: Exception) {
            // Keep the old (dead) device rather than leaving nothing.
            Log.w(TAG, "WG bounce failed: ${e.message}")
            false
        }
    }

    private fun isDialTimeout(e: Exception): Boolean {
        // The Go side surfaces net.Dialer deadlines as "context deadline
        // exceeded" (ctx) or gVisor timeouts; accept both shapes.
        val msg = e.message.orEmpty()
        if (msg.contains("context deadline exceeded", ignoreCase = true)) return true
        if (msg.contains("i/o timeout", ignoreCase = true)) return true
        return e.cause?.message?.contains("context deadline exceeded", ignoreCase = true) == true
    }

    override fun listenUdp(): TunneledDatagramSocket? {
        val udp = try {
            native.listenUdp()
        } catch (e: Exception) {
            throw IOException("WireGuard listenUDP failed: ${e.message}", e)
        }
        return NativeTunneledDatagramSocket(udp)
    }

    override fun socksAddress(): InetSocketAddress? {
        socksCached?.let { return it }
        return synchronized(this) {
            socksCached?.let { return@synchronized it }
            val port = try {
                native.startSocksListener()
            } catch (e: Exception) {
                throw IOException("WireGuard SOCKS5 listener failed: ${e.message}", e)
            }
            InetSocketAddress("127.0.0.1", port.toInt()).also { socksCached = it }
        }
    }

    override fun listenTcp(port: Int): TunneledServerSocket? {
        val ln = try {
            native.listenTcp(port.toLong())
        } catch (e: Exception) {
            throw IOException("WireGuard listenTCP $port failed: ${e.message}", e)
        }
        return NativeTunneledServerSocket(ln)
    }

    override fun localAddress(): String? = native.bindAddr().takeIf { it.isNotBlank() }

    override fun close() {
        try {
            native.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }

    private fun launchNative(prepared: String): WgNativeDevice {
        val networkHandle = underlayNetworkHandleProvider()
        nativeStarterWithNetwork?.let { return it(prepared, networkHandle) }
        nativeStarter?.let { return it(prepared) }
        val summary = summarizeConfigForLog(prepared)
        Log.i(TAG, "Starting native WireGuard tunnel ($summary networkHandle=$networkHandle)")
        return GomobileWgNative.start(prepared, networkHandle)
    }

    companion object {
        private const val TAG = "WireguardTunnel"

        internal fun summarizeConfigForLog(configText: String): String {
            var addr = ""
            var dns = ""
            var endpoint = ""
            var allowedIps = ""
            var keepalive = ""
            for (raw in configText.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=', "").trim()
                when (key) {
                    "address" -> addr = value
                    "dns" -> dns = value
                    "endpoint" -> endpoint = value
                    "allowedips" -> allowedIps = value
                    "persistentkeepalive" -> keepalive = value
                }
            }
            return "address=$addr endpoint=$endpoint allowedIps=$allowedIps dns=$dns keepalive=$keepalive"
        }

        /**
         * WireGuard's standard NAT/firewall keepalive interval (seconds). Injected
         * into a [Peer] that omits one so the userspace tunnel stays warm.
         */
        internal const val DEFAULT_KEEPALIVE_SECONDS = 25

        /** Dial timeouts in a row before the tunnel recycles itself. */
        internal const val BOUNCE_AFTER_TIMEOUTS = 1

        /** Minimum spacing between dial-timeout bounces, regardless of failure rate. */
        internal const val BOUNCE_COOLDOWN_MS = 60_000L

        /** Minimum spacing between network-change [revive] bounces. */
        internal const val REVIVE_COOLDOWN_MS = 2_000L

        /** Minimum caller timeout (ms) before splitting into pre-bounce + post-bounce attempts. */
        internal const val MIN_SPLIT_TIMEOUT_MS = 10_000

        /** Cap (ms) on the first dial attempt when a bounce-and-retry is eligible. */
        internal const val FIRST_ATTEMPT_MAX_TIMEOUT_MS = 10_000

        private val SECTION_HEADER = Regex("""^\s*\[\s*(\w+)\s*]""")
        private val KEEPALIVE_LINE = Regex("""^\s*PersistentKeepalive\s*=""", RegexOption.IGNORE_CASE)

        /**
         * Ensure every `[Peer]` in a wg-quick [configText] carries a
         * `PersistentKeepalive`, injecting [seconds] into any that don't.
         *
         * Why: these are userspace tunnels on a roaming mobile device. When the
         * underlying transport changes (Wi-Fi↔mobile, hotspot hop, app restart),
         * a *cold* peer only re-homes on the next outbound data packet or after a
         * handshake timeout — a multi-second window during which the carrier's WG
         * address is unreachable and anything riding it (the MCP endpoint) times
         * out. A keepalive makes the device re-assert its endpoint to the peer
         * every [seconds], collapsing that window. An explicit user-set interval
         * is left untouched. The wgbridge parser maps this line to
         * `persistent_keepalive_interval` in the wireguard-go UAPI.
         */
        internal fun ensurePeerKeepalive(
            configText: String,
            seconds: Int = DEFAULT_KEEPALIVE_SECONDS,
        ): String {
            if (seconds <= 0) return configText
            val out = StringBuilder(configText.length + 32)
            val section = StringBuilder()
            var sectionName: String? = null
            var sectionHasKeepalive = false

            fun flushSection() {
                val isPeer = sectionName.equals("Peer", ignoreCase = true)
                if (isPeer && !sectionHasKeepalive && section.isNotBlank()) {
                    out.append(section.toString().trimEnd('\n')).append('\n')
                    out.append("PersistentKeepalive = ").append(seconds).append('\n')
                } else {
                    out.append(section)
                }
                section.setLength(0)
                sectionHasKeepalive = false
            }

            for (line in configText.lineSequence()) {
                if (SECTION_HEADER.containsMatchIn(line)) {
                    flushSection()
                    sectionName = SECTION_HEADER.find(line)!!.groupValues[1]
                }
                if (KEEPALIVE_LINE.containsMatchIn(line)) sectionHasKeepalive = true
                section.append(line).append('\n')
            }
            flushSection()
            return out.toString()
        }
    }
}

/** Wraps a [WgNativeListener] as [TunneledServerSocket]. */
private class NativeTunneledServerSocket(
    private val listener: WgNativeListener,
) : TunneledServerSocket {

    override fun accept(): TunneledConnection {
        val conn = try {
            listener.accept()
        } catch (e: Exception) {
            throw IOException("WireGuard accept failed: ${e.message}", e)
        }
        return NativeTunneledConnection(conn)
    }

    override fun close() {
        try {
            listener.close()
        } catch (_: Throwable) {
            // Already-closed listeners raise here; ignore.
        }
    }
}

/**
 * Wraps a [WgNativeConn] as [TunneledConnection]. The gomobile layer returns
 * fresh byte slices from `read(size)` because it copies `[]byte` arguments
 * across the JNI boundary — the caller's buffer wouldn't be updated otherwise.
 * The seam carries that convention through. The extra copy is immaterial
 * for SSH traffic (small frames, network round-trip dominates).
 */
private class NativeTunneledConnection(
    private val conn: WgNativeConn,
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
                conn.read(len)
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
                throw IOException("WireGuard write failed: ${e.message}", e)
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
 * Wraps a [WgNativeUdpConn] as [TunneledDatagramSocket]. Timeouts: the Go
 * side translates `timeoutMs <= 0` to "no deadline" and `timeoutMs > 0` to a
 * one-shot read deadline; a fired deadline appears as an exception whose
 * message contains "i/o timeout" here, recognised and returned as null.
 */
private class NativeTunneledDatagramSocket(
    private val udp: WgNativeUdpConn,
) : TunneledDatagramSocket {

    override fun send(data: ByteArray, host: String, port: Int) {
        try {
            udp.writeTo(data, host, port)
        } catch (e: Exception) {
            throw IOException("WireGuard UDP writeTo $host:$port failed: ${e.message}", e)
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket? {
        val result = try {
            udp.readFrom(buf.size, timeoutMs)
        } catch (e: Exception) {
            if (isTimeoutException(e)) return null
            throw IOException("WireGuard UDP readFrom failed: ${e.message}", e)
        }
        val bytes = result?.data ?: return null
        val n = minOf(bytes.size, buf.size)
        System.arraycopy(bytes, 0, buf, 0, n)
        return ReceivedPacket(
            length = n,
            srcHost = result.fromHost ?: "",
            srcPort = result.fromPort,
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