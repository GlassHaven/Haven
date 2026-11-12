package sh.haven.core.local

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Loopback DNS-over-proxy bridge for PRoot distributions (#838).
 *
 * Standard guest resolvers (`/etc/resolv.conf`, `dig`, `nslookup`, Go `net.Resolver`)
 * emit unproxied UDP or TCP port-53 packets that either leak outside environment-level
 * proxy variables or fail with `EACCES` when `--net-policy=lockdown` is active.
 *
 * For each active proxy configuration, [ProotDnsProxyBridge] binds loopback UDP and TCP
 * listeners on `127.0.0.1` (and `[::1]` when IPv6 loopback is available), strips the
 * optional 28-byte `HVNDNS01` original-destination trailer appended by PRoot's
 * `net_policy` extension on redirected UDP sends, frames the DNS message as RFC 1035
 * §4.2.2 DNS-over-TCP (`[2-byte length][DNS payload]`), and tunnels it through the
 * distro's SOCKS5 (`CONNECT`) or HTTP(S) (`CONNECT`) proxy to port 53 (or a custom
 * upstream port) on the target DNS server.
 */
class ProotDnsProxyBridge(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : Closeable {

    data class ListenerAddresses(
        val ipv4Port: Int,
        val ipv6Port: Int?,
    ) {
        val ipv4Spec: String get() = "127.0.0.1:$ipv4Port"
        val ipv6Spec: String? get() = ipv6Port?.let { "[::1]:$it" }
    }

    data class DecodedQuery(
        val dnsPayload: ByteArray,
        val explicitTarget: ProotDns.DnsEndpoint?,
    )

    private data class BridgeKey(
        val proxy: ProotRouting.ParsedProxyEndpoint,
        val upstreamServers: List<ProotDns.DnsEndpoint>,
    )

    private val instances = ConcurrentHashMap<BridgeKey, BridgeInstance>()
    private val closed = AtomicBoolean(false)

    /**
     * Return (or lazily start) loopback UDP+TCP bridge listeners for [proxy] and
     * fallback [upstreamServers]. Safe to call repeatedly across guest command launches.
     */
    fun ensureStarted(
        proxy: ProotRouting.ParsedProxyEndpoint,
        upstreamServers: List<ProotDns.DnsEndpoint>,
    ): ListenerAddresses {
        check(!closed.get()) { "ProotDnsProxyBridge is closed" }
        val normalizedUpstreams = upstreamServers.ifEmpty {
            ProotDns.PUBLIC_SERVERS.map { ProotDns.DnsEndpoint(it, ProotDns.DEFAULT_PORT) }
        }
        val key = BridgeKey(proxy, normalizedUpstreams)
        val existing = instances[key]
        if (existing != null && existing.isHealthy) {
            return existing.addresses
        }
        synchronized(this) {
            val again = instances[key]
            if (again != null && again.isHealthy) {
                return again.addresses
            }
            again?.close()
            val started = BridgeInstance(
                proxy = proxy,
                upstreamServers = normalizedUpstreams,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            )
            instances[key] = started
            return started.addresses
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            instances.values.forEach { it.close() }
            instances.clear()
        }
    }

    private class BridgeInstance(
        private val proxy: ProotRouting.ParsedProxyEndpoint,
        private val upstreamServers: List<ProotDns.DnsEndpoint>,
        private val connectTimeoutMs: Int,
        private val readTimeoutMs: Int,
    ) : Closeable {

        private val running = AtomicBoolean(true)
        private val inFlight = Semaphore(MAX_CONCURRENT_QUERIES)
        private val workerPool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "haven-dns-proxy-worker").apply { isDaemon = true }
        }

        private val ipv4Loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val udpV4: DatagramSocket
        private val tcpV4: ServerSocket

        private val udpV6: DatagramSocket?
        private val tcpV6: ServerSocket?

        val addresses: ListenerAddresses

        val isHealthy: Boolean
            get() = running.get() && !udpV4.isClosed && !tcpV4.isClosed

        init {
            val v4Pair = bindMatchingPortPair(ipv4Loopback)
            udpV4 = v4Pair.first
            tcpV4 = v4Pair.second

            var boundUdp6: DatagramSocket? = null
            var boundTcp6: ServerSocket? = null
            try {
                val ipv6Loopback = InetAddress.getByAddress(
                    ByteArray(16).apply { this[15] = 1 },
                )
                val v6Pair = bindMatchingPortPair(ipv6Loopback)
                boundUdp6 = v6Pair.first
                boundTcp6 = v6Pair.second
            } catch (_: Exception) {
                boundUdp6?.close()
                boundTcp6?.close()
                boundUdp6 = null
                boundTcp6 = null
            }

            udpV6 = boundUdp6
            tcpV6 = boundTcp6
            addresses = ListenerAddresses(
                ipv4Port = udpV4.localPort,
                ipv6Port = boundUdp6?.localPort,
            )

            startUdpAcceptLoop(udpV4, "haven-dns-udp4")
            startTcpAcceptLoop(tcpV4, "haven-dns-tcp4")
            if (boundUdp6 != null && boundTcp6 != null) {
                startUdpAcceptLoop(boundUdp6, "haven-dns-udp6")
                startTcpAcceptLoop(boundTcp6, "haven-dns-tcp6")
            }
        }

        private fun startUdpAcceptLoop(socket: DatagramSocket, threadName: String) {
            Thread(
                {
                    val recvBuf = ByteArray(MAX_UDP_PACKET_BYTES)
                    while (running.get() && !socket.isClosed) {
                        try {
                            val packet = DatagramPacket(recvBuf, recvBuf.size)
                            socket.receive(packet)
                            val raw = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            val clientAddr = packet.address
                            val clientPort = packet.port
                            if (!inFlight.tryAcquire()) continue
                            workerPool.execute {
                                try {
                                    handleUdpQuery(socket, raw, clientAddr, clientPort)
                                } finally {
                                    inFlight.release()
                                }
                            }
                        } catch (_: IOException) {
                            if (!running.get() || socket.isClosed) break
                        }
                    }
                },
                threadName,
            ).apply {
                isDaemon = true
                start()
            }
        }

        private fun startTcpAcceptLoop(server: ServerSocket, threadName: String) {
            Thread(
                {
                    while (running.get() && !server.isClosed) {
                        try {
                            val client = server.accept()
                            if (!inFlight.tryAcquire()) {
                                runCatching { client.close() }
                                continue
                            }
                            workerPool.execute {
                                try {
                                    handleTcpClient(client)
                                } finally {
                                    inFlight.release()
                                }
                            }
                        } catch (_: IOException) {
                            if (!running.get() || server.isClosed) break
                        }
                    }
                },
                threadName,
            ).apply {
                isDaemon = true
                start()
            }
        }

        private fun handleUdpQuery(
            socket: DatagramSocket,
            rawDatagram: ByteArray,
            clientAddr: InetAddress,
            clientPort: Int,
        ) {
            val decoded = decodeDatagram(rawDatagram) ?: return
            val candidates = selectTargetCandidates(decoded.explicitTarget)
            val response = forwardQueryWithFailover(decoded.dnsPayload, candidates) ?: return
            if (response.size > MAX_UDP_RESPONSE_BYTES) return
            runCatching {
                socket.send(DatagramPacket(response, response.size, clientAddr, clientPort))
            }
        }

        private fun handleTcpClient(client: Socket) {
            client.use { sock ->
                sock.soTimeout = readTimeoutMs
                val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
                val output = BufferedOutputStream(sock.getOutputStream())
                while (running.get() && !sock.isClosed) {
                    val queryLen = try {
                        input.readUnsignedShort()
                    } catch (_: EOFException) {
                        break
                    } catch (_: IOException) {
                        break
                    }
                    if (queryLen < MIN_DNS_HEADER_BYTES || queryLen > MAX_DNS_MESSAGE_BYTES) break
                    val query = ByteArray(queryLen)
                    try {
                        input.readFully(query)
                    } catch (_: IOException) {
                        break
                    }
                    val response = forwardQueryWithFailover(query, upstreamServers) ?: break
                    try {
                        output.write((response.size ushr 8) and 0xFF)
                        output.write(response.size and 0xFF)
                        output.write(response)
                        output.flush()
                    } catch (_: IOException) {
                        break
                    }
                }
            }
        }

        private fun selectTargetCandidates(
            explicitTarget: ProotDns.DnsEndpoint?,
        ): List<ProotDns.DnsEndpoint> {
            if (explicitTarget != null && !ProotDns.isLoopbackIp(explicitTarget.ip)) {
                return listOf(explicitTarget)
            }
            return upstreamServers
        }

        private fun forwardQueryWithFailover(
            dnsQuery: ByteArray,
            candidates: List<ProotDns.DnsEndpoint>,
        ): ByteArray? {
            for (target in candidates) {
                try {
                    return queryUpstreamOverProxy(
                        proxy = proxy,
                        target = target,
                        dnsQuery = dnsQuery,
                        connectTimeoutMs = connectTimeoutMs,
                        readTimeoutMs = readTimeoutMs,
                    )
                } catch (e: IOException) {
                    Log.d(TAG, "Proxy DNS query to ${target.hostPortSpec} failed: ${e.message}")
                }
            }
            return null
        }

        override fun close() {
            if (!running.compareAndSet(true, false)) return
            runCatching { udpV4.close() }
            runCatching { tcpV4.close() }
            runCatching { udpV6?.close() }
            runCatching { tcpV6?.close() }
            workerPool.shutdownNow()
        }
    }

    companion object {
        private const val TAG = "ProotDnsProxyBridge"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 8_000
        private const val TCP_BACKLOG = 32
        private const val MAX_CONCURRENT_QUERIES = 32
        private const val MIN_DNS_HEADER_BYTES = 12
        private const val MAX_DNS_MESSAGE_BYTES = 65_535
        private const val MAX_UDP_RESPONSE_BYTES = 65_507
        private const val MAX_UDP_PACKET_BYTES = 65_535

        /**
         * 28-byte optional trailer appended by `net_policy.c` on redirected UDP sends:
         * - `0..7`: ASCII `"HVNDNS01"`
         * - `8`: `4` (IPv4) or `6` (IPv6)
         * - `9`: `0` (reserved)
         * - `10..11`: original port (big-endian; `0` falls back to `53`)
         * - `12..27`: network-order IPv4 (`12..15`) or IPv6 (`12..27`)
         */
        val TRAILER_MAGIC = byteArrayOf(
            0x48, 0x56, 0x4E, 0x44, 0x4E, 0x53, 0x30, 0x31, // "HVNDNS01"
        )
        const val TRAILER_BYTES = 28

        fun encodeTrailer(endpoint: ProotDns.DnsEndpoint): ByteArray {
            val addrBytes = numericIpBytes(endpoint.ip)
                ?: throw IllegalArgumentException("Invalid IP literal: ${endpoint.ip}")
            val out = ByteArray(TRAILER_BYTES)
            System.arraycopy(TRAILER_MAGIC, 0, out, 0, TRAILER_MAGIC.size)
            out[8] = if (addrBytes.size == 4) 4 else 6
            out[9] = 0
            out[10] = ((endpoint.port ushr 8) and 0xFF).toByte()
            out[11] = (endpoint.port and 0xFF).toByte()
            System.arraycopy(addrBytes, 0, out, 12, addrBytes.size)
            return out
        }

        fun decodeDatagram(raw: ByteArray): DecodedQuery? {
            if (raw.size < MIN_DNS_HEADER_BYTES) return null
            if (raw.size >= MIN_DNS_HEADER_BYTES + TRAILER_BYTES) {
                val trailerOffset = raw.size - TRAILER_BYTES
                var magicMatch = true
                for (i in TRAILER_MAGIC.indices) {
                    if (raw[trailerOffset + i] != TRAILER_MAGIC[i]) {
                        magicMatch = false
                        break
                    }
                }
                if (magicMatch) {
                    val addrType = raw[trailerOffset + 8].toInt() and 0xFF
                    val rawPort = ((raw[trailerOffset + 10].toInt() and 0xFF) shl 8) or
                        (raw[trailerOffset + 11].toInt() and 0xFF)
                    val port = if (rawPort in 1..65535) rawPort else ProotDns.DEFAULT_PORT
                    val targetIp = when (addrType) {
                        4 -> InetAddress.getByAddress(
                            raw.copyOfRange(trailerOffset + 12, trailerOffset + 16),
                        ).hostAddress
                        6 -> Inet6Address.getByAddress(
                            null,
                            raw.copyOfRange(trailerOffset + 12, trailerOffset + 28),
                            -1,
                        ).hostAddress
                        else -> null
                    }
                    if (targetIp != null) {
                        val payload = raw.copyOfRange(0, trailerOffset)
                        return DecodedQuery(
                            dnsPayload = payload,
                            explicitTarget = ProotDns.DnsEndpoint(targetIp, port),
                        )
                    }
                }
            }
            return DecodedQuery(dnsPayload = raw, explicitTarget = null)
        }

        internal fun queryUpstreamOverProxy(
            proxy: ProotRouting.ParsedProxyEndpoint,
            target: ProotDns.DnsEndpoint,
            dnsQuery: ByteArray,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
            readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        ): ByteArray {
            require(dnsQuery.size in MIN_DNS_HEADER_BYTES..MAX_DNS_MESSAGE_BYTES) {
                "Invalid DNS query length: ${dnsQuery.size}"
            }
            openTunnelSocket(proxy, target, connectTimeoutMs, readTimeoutMs).use { tunnel ->
                val out = BufferedOutputStream(tunnel.getOutputStream())
                out.write((dnsQuery.size ushr 8) and 0xFF)
                out.write(dnsQuery.size and 0xFF)
                out.write(dnsQuery)
                out.flush()

                val input = DataInputStream(BufferedInputStream(tunnel.getInputStream()))
                val respLen = input.readUnsignedShort()
                if (respLen < MIN_DNS_HEADER_BYTES || respLen > MAX_DNS_MESSAGE_BYTES) {
                    throw IOException("Invalid DNS-over-TCP response length: $respLen")
                }
                val resp = ByteArray(respLen)
                input.readFully(resp)
                return resp
            }
        }

        private fun openTunnelSocket(
            proxy: ProotRouting.ParsedProxyEndpoint,
            target: ProotDns.DnsEndpoint,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): Socket {
            val base = Socket()
            try {
                base.tcpNoDelay = true
                base.soTimeout = readTimeoutMs
                base.connect(InetSocketAddress(proxy.host, proxy.port), connectTimeoutMs)
                val socket: Socket = if (proxy.scheme == "https") {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(base, proxy.host, proxy.port, true) as SSLSocket
                    ssl.soTimeout = readTimeoutMs
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(proxy.host, ssl.session)) {
                        ssl.close()
                        throw IOException("HTTPS proxy hostname verification failed for ${proxy.host}")
                    }
                    ssl
                } else {
                    base
                }
                if (proxy.isSocks) {
                    performSocks5Connect(socket.getInputStream(), socket.getOutputStream(), proxy, target)
                } else {
                    performHttpConnect(socket.getInputStream(), socket.getOutputStream(), proxy, target)
                }
                return socket
            } catch (e: Exception) {
                runCatching { base.close() }
                if (e is IOException) throw e
                throw IOException("Failed to open DNS tunnel via ${proxy.scheme}://${proxy.host}:${proxy.port}", e)
            }
        }

        private fun performSocks5Connect(
            rawIn: InputStream,
            rawOut: OutputStream,
            proxy: ProotRouting.ParsedProxyEndpoint,
            target: ProotDns.DnsEndpoint,
        ) {
            val input = DataInputStream(rawIn)
            val hasAuth = !proxy.username.isNullOrEmpty()
            if (hasAuth) {
                rawOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                rawOut.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            rawOut.flush()

            val greetingVer = input.readUnsignedByte()
            val chosenMethod = input.readUnsignedByte()
            if (greetingVer != 0x05) {
                throw IOException("Unexpected SOCKS version: $greetingVer")
            }
            when (chosenMethod) {
                0x00 -> Unit
                0x02 -> {
                    val userBytes = proxy.username.orEmpty().toByteArray(StandardCharsets.UTF_8)
                    val passBytes = proxy.password.orEmpty().toByteArray(StandardCharsets.UTF_8)
                    if (userBytes.isEmpty() || userBytes.size > 255 || passBytes.size > 255) {
                        throw IOException("SOCKS5 credentials exceed RFC 1929 bounds")
                    }
                    val authReq = ByteArray(3 + userBytes.size + passBytes.size)
                    authReq[0] = 0x01
                    authReq[1] = userBytes.size.toByte()
                    System.arraycopy(userBytes, 0, authReq, 2, userBytes.size)
                    authReq[2 + userBytes.size] = passBytes.size.toByte()
                    System.arraycopy(passBytes, 0, authReq, 3 + userBytes.size, passBytes.size)
                    rawOut.write(authReq)
                    rawOut.flush()

                    val authVer = input.readUnsignedByte()
                    val authStatus = input.readUnsignedByte()
                    if (authVer != 0x01 || authStatus != 0x00) {
                        throw IOException("SOCKS5 authentication rejected (status=$authStatus)")
                    }
                }
                else -> throw IOException("SOCKS5 proxy rejected authentication methods (method=$chosenMethod)")
            }

            val addrBytes = numericIpBytes(target.ip)
                ?: throw IOException("Target DNS server must be an IP literal: ${target.ip}")
            val atyp: Byte = when (addrBytes.size) {
                4 -> 0x01
                16 -> 0x04
                else -> throw IOException("Unsupported address length: ${addrBytes.size}")
            }
            val connectReq = ByteArray(4 + addrBytes.size + 2)
            connectReq[0] = 0x05
            connectReq[1] = 0x01 // CONNECT
            connectReq[2] = 0x00 // RSV
            connectReq[3] = atyp
            System.arraycopy(addrBytes, 0, connectReq, 4, addrBytes.size)
            connectReq[4 + addrBytes.size] = ((target.port ushr 8) and 0xFF).toByte()
            connectReq[5 + addrBytes.size] = (target.port and 0xFF).toByte()
            rawOut.write(connectReq)
            rawOut.flush()

            val repVer = input.readUnsignedByte()
            val repCode = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val repAtyp = input.readUnsignedByte()
            if (repVer != 0x05 || repCode != 0x00) {
                throw IOException("SOCKS5 CONNECT to ${target.hostPortSpec} failed (reply=$repCode)")
            }
            val bindDiscardBytes = when (repAtyp) {
                0x01 -> 4 + 2
                0x04 -> 16 + 2
                0x03 -> input.readUnsignedByte() + 2
                else -> throw IOException("Unknown SOCKS5 reply ATYP: $repAtyp")
            }
            input.skipFully(bindDiscardBytes)
        }

        private fun performHttpConnect(
            rawIn: InputStream,
            rawOut: OutputStream,
            proxy: ProotRouting.ParsedProxyEndpoint,
            target: ProotDns.DnsEndpoint,
        ) {
            val authority = target.hostPortSpec
            val req = buildString {
                append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                append("Host: ").append(authority).append("\r\n")
                if (!proxy.username.isNullOrEmpty()) {
                    val creds = "${proxy.username}:${proxy.password.orEmpty()}"
                    val encoded = Base64.getEncoder().encodeToString(creds.toByteArray(StandardCharsets.UTF_8))
                    append("Proxy-Authorization: Basic ").append(encoded).append("\r\n")
                }
                append("Proxy-Connection: Keep-Alive\r\n\r\n")
            }
            rawOut.write(req.toByteArray(StandardCharsets.ISO_8859_1))
            rawOut.flush()

            val statusLine = readHttpHeaderLine(rawIn)
                ?: throw IOException("EOF waiting for HTTP CONNECT status line")
            val parts = statusLine.split(' ', limit = 3)
            val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: -1
            if (statusCode !in 200..299) {
                throw IOException("HTTP CONNECT to $authority failed: $statusLine")
            }
            while (true) {
                val header = readHttpHeaderLine(rawIn)
                    ?: throw IOException("EOF inside HTTP CONNECT response headers")
                if (header.isEmpty()) break
            }
        }

        private fun readHttpHeaderLine(input: InputStream): String? {
            val sb = StringBuilder(128)
            while (sb.length < 8192) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
                if (b == '\n'.code) {
                    return sb.toString().trimEnd('\r')
                }
                sb.append(b.toChar())
            }
            throw IOException("HTTP CONNECT header line exceeded 8 KiB")
        }

        private fun DataInputStream.skipFully(count: Int) {
            val buf = ByteArray(count)
            readFully(buf)
        }

        private fun bindMatchingPortPair(bindAddr: InetAddress): Pair<DatagramSocket, ServerSocket> {
            repeat(8) {
                val tcp = ServerSocket(0, TCP_BACKLOG, bindAddr)
                try {
                    val udp = DatagramSocket(tcp.localPort, bindAddr)
                    return udp to tcp
                } catch (_: IOException) {
                    tcp.close()
                }
            }
            val tcp = ServerSocket(0, TCP_BACKLOG, bindAddr)
            val udp = DatagramSocket(0, bindAddr)
            return udp to tcp
        }

        /**
         * Parse a validated numeric IPv4 or IPv6 literal to raw network-order bytes
         * without performing any network DNS lookup.
         */
        internal fun numericIpBytes(ip: String): ByteArray? {
            val cleaned = ip.substringBefore('%').trim()
            if (!ProotDns.isIpLiteral(cleaned) && cleaned != "::1") return null
            return runCatching { InetAddress.getByName(cleaned).address }.getOrNull()
        }
    }
}
