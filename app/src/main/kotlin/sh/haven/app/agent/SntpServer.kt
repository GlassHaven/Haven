package sh.haven.app.agent

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * The SNTP responder behind `start_ntp_service`: one UDP socket answering
 * NTPv4 client polls with GPS-disciplined time from [GpsDiscipliner].
 *
 * Binding policy mirrors [sh.haven.app.agent.McpServer] (`bindLan` /
 * `pickLanAddress`): loopback always; with `bindLan = true`, the device's
 * Wi-Fi/Ethernet site-local IPv4 — never 0.0.0.0, so the endpoint can't
 * surface on the mobile (rmnet) interface. UDP/123 is a privileged port on
 * Android (app uid → EACCES), so the port is ≥1024 — clients point at
 * `phone:<port>` explicitly (chrony: `server <ip> port <port>`).
 *
 * One daemon thread owns the socket and answers inline: an NTP answer is
 * two timestamps bracketing a constant-time encode, so per-request cost is
 * dominated by the socket syscall, and a thread-per-request pool would
 * widen the receive→transmit window without buying throughput. The
 * receive→transmit bracket is stamped around the answer build, which is
 * what the client uses to cancel queueing delay.
 *
 * Behaviour when the discipliner has no current estimate (GPS lost): the
 * answer's LI goes to 3 (unsynchronised) and rootDispersion grows with
 * holdover, so a client (chrony/ntpd) disqualifies the source on its own
 * terms instead of us guessing a threshold.
 */
internal class SntpServer(
    val port: Int,
    private val bindLan: Boolean,
    /** UTC answer source: epoch-ms + sub-ms + uncertainty seconds, or null
     * when not disciplined (the answer then carries LI=3). */
    private val timeSource: () -> GpsDiscipliner.NowEstimate?,
    /** Timestamp of the last GPS sample, for the reference-time field. */
    private val referenceTimeMs: () -> Long,
) {
    var socket: DatagramSocket? = null
        private set
    private var thread: Thread? = null
    @Volatile private var running = false

    val requestsTotal = AtomicLong(0)
    val answersTotal = AtomicLong(0)
    /** Last client address (for status). */
    @Volatile var lastClient: String? = null
        private set

    /** Bind and start serving. Throws IOException on bind failure. */
    fun start() {
        check(!running) { "already running" }
        val loopback = InetAddress.getByName("127.0.0.1")
        val ds = DatagramSocket(InetSocketAddress(loopback, port)).apply { reuseAddress = true }
        if (bindLan) {
            // A second socket on the LAN address — the same soft-skip
            // policy as the MCP LAN bind: no suitable interface is a
            // status field, not an error.
            try {
                lanSocket = DatagramSocket(InetSocketAddress(pickLanAddress(), port))
            } catch (e: Exception) {
                lanSocket = null
                Log.w(TAG, "NTP LAN bind failed (loopback still serving): ${e.message}")
            }
        }
        socket = ds
        running = true
        thread = Thread({ serve(ds, loopbackLabel = "loopback") }, "haven-ntp-loopback").apply {
            isDaemon = true
            start()
        }
        lanSocket?.let { ls ->
            lanThread = Thread({ serve(ls, loopbackLabel = "lan") }).apply {
                name = "haven-ntp-lan"
                isDaemon = true
                start()
            }
        }
        Log.i(TAG, "NTP service on 127.0.0.1:$port" + (if (lanSocket != null) " + LAN" else ""))
    }

    private var lanSocket: DatagramSocket? = null
    private var lanThread: Thread? = null

    /** The LAN address actually bound (null = loopback-only). */
    val lanBindAddress: String? get() = (lanSocket?.localAddress as? java.net.Inet4Address)?.hostAddress

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { lanSocket?.close() } catch (_: Exception) {}
        socket = null
        lanSocket = null
        thread = null
        lanThread = null
        Log.i(TAG, "NTP service stopped ($requestsTotal requests, $answersTotal answers)")
    }

    private fun serve(ds: DatagramSocket, loopbackLabel: String) {
        val buf = ByteArray(SntpPacket.SIZE)
        val packet = DatagramPacket(buf, buf.size)
        val out = ByteArray(SntpPacket.SIZE)
        while (running) {
            try {
                ds.receive(packet)
            } catch (_: java.io.IOException) {
                break // socket closed by stop()
            } catch (e: Exception) {
                Log.w(TAG, "$loopbackLabel receive failed: ${e.message}")
                continue
            }
            requestsTotal.incrementAndGet()
            lastClient = packet.address?.hostAddress
            val request = runCatching { SntpPacket.parseClient(buf.copyOf(packet.length)) }.getOrNull()
            if (request == null) continue
            val answer = buildAnswer(request, version = request.version.coerceIn(1, 4))
            runCatching {
                ds.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
            }
            answersTotal.incrementAndGet()
        }
    }

    /**
     * Build one response: LI=0/stratum 1/refId GPS when disciplined, LI=3
     * with grown dispersion on holdover. Precision is -9 (≈2 ms) with
     * NMEA-sourced discipline and -16 (µs-class) with the GNSS clock —
     * the measured jitter is carried in rootDispersion either way.
     */
    internal fun buildAnswer(request: SntpPacket.ClientRequest, version: Int): ByteArray {
        val out = ByteArray(SntpPacket.SIZE)
        // Receive/transmit stamps come from the disciplined time, not the
        // wall clock — serving the wall clock would defeat the whole point
        // (Android's own sync is typically tens of ms off and can jump).
        // Wall clock is the fallback only for the LI=3 degraded answer.
        // One estimate for both stamps: two calls could straddle a GPS
        // sample landing, and an rx→tx step backwards is an impossible
        // delay any sane client rejects.
        val estAtRx = timeSource()
        val disciplined = estAtRx != null && estAtRx.holdoverMs <= MAX_HOLDOVER_MS
        val rxTs = if (disciplined) {
            // utcSubMs is MICROSECONDS (ns/1e3 from the discipliner);
            // ntpFromUnixMs takes sub-MILLseconds — the /1000 here is the
            // whole game: unconverted, ±300 µs of sub-ms residual encoded
            // as ±300 ms of the NTP fraction (measured on the wire as
            // ±490 ms answer swings around a ±4 ms discipliner).
            SntpPacket.ntpFromUnixMs(estAtRx!!.utcMs, estAtRx.utcSubMs / 1000.0)
        } else SntpPacket.ntpFromUnixMs(System.currentTimeMillis())
        val txTs = rxTs
        if (disciplined) {
            // Reference timestamp: the last GPS sample that fed the model.
            val refTs = SntpPacket.ntpFromUnixMs(referenceTimeMs())
            SntpPacket.encodeServer(
                out = out,
                leap = SntpPacket.LI_NONE,
                version = version,
                stratum = SntpPacket.STRATUM_GPS,
                poll = 6, // 64 s — a typical client's default poll
                precision = if (estAtRx!!.source == GpsDiscipliner.Source.GNSS_CLOCK) -16 else -9,
                rootDelayS = 0.0,
                rootDispersionS = estAtRx.uncertaintyMs / 1000.0,
                refId = SntpPacket.REFID_GPS,
                referenceTs = refTs,
                originateTs = request.transmit,
                receiveTs = rxTs,
                transmitTs = txTs,
            )
        } else {
            // Holdover too long / never disciplined: admit it (LI=3,
            // stratum 0 kiss-of-death "GPS\0") so the client disqualifies
            // the source instead of trusting a stale model.
            SntpPacket.encodeServer(
                out = out,
                leap = SntpPacket.LI_UNSYNCHRONISED,
                version = version,
                stratum = 0,
                poll = 6,
                precision = -16,
                rootDelayS = 0.0,
                rootDispersionS = (estAtRx?.uncertaintyMs ?: 60_000.0) / 1000.0,
                refId = byteArrayOf(0x47, 0x50, 0x53, 0x00), // "GPS\0"
                referenceTs = SntpPacket.ntpFromUnixMs(referenceTimeMs()),
                originateTs = request.transmit,
                receiveTs = rxTs,
                transmitTs = txTs,
            )
        }
        return out
    }

    companion object {
        private const val TAG = "HavenNtp"
        /** Answer LI=0 only within this much GPS holdover; past it, LI=3. */
        const val MAX_HOLDOVER_MS = 30_000L

        /** Wi-Fi/Ethernet site-local IPv4, mirroring McpServer.pickLanAddress:
         * never rmnet/tun/wg, so LAN exposure stays on a deliberately-joined
         * network. (Duplicated rather than shared: pickLanAddress is private
         * to McpServer and both call sites want to drift together with their
         * own bind policy.) */
        internal fun pickLanAddress(): InetAddress? = try {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .filterNot { ni ->
                    val n = ni.name.lowercase()
                    n.startsWith("rmnet") || n.startsWith("tun") || n.startsWith("wg") ||
                        n.startsWith("dummy") || n.startsWith("p2p")
                }
                .sortedBy { ni -> if (ni.name.lowercase().startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { a -> a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress }
        } catch (e: Exception) {
            Log.w(TAG, "pickLanAddress failed: ${e.message}")
            null
        }
    }
}