package sh.haven.core.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProotDnsProxyBridgeTest {

    @Test
    fun `trailer round-trips IPv4 and IPv6 target endpoints and leaves plain packets untouched`() {
        val sampleQuery = buildMinimalDnsQuery(txId = 0x1234)

        val plainDecoded = ProotDnsProxyBridge.decodeDatagram(sampleQuery)
        assertNotNull(plainDecoded)
        assertArrayEquals(sampleQuery, plainDecoded!!.dnsPayload)
        assertNull(plainDecoded.explicitTarget)

        val v4Trailer = ProotDnsProxyBridge.encodeTrailer(ProotDns.DnsEndpoint("9.9.9.9", 53))
        assertEquals(ProotDnsProxyBridge.TRAILER_BYTES, v4Trailer.size)
        val v4Decoded = ProotDnsProxyBridge.decodeDatagram(sampleQuery + v4Trailer)
        assertNotNull(v4Decoded)
        assertArrayEquals(sampleQuery, v4Decoded!!.dnsPayload)
        assertEquals(ProotDns.DnsEndpoint("9.9.9.9", 53), v4Decoded.explicitTarget)

        val v6Trailer = ProotDnsProxyBridge.encodeTrailer(
            ProotDns.DnsEndpoint("2606:4700:4700::1111", 5353),
        )
        val v6Decoded = ProotDnsProxyBridge.decodeDatagram(sampleQuery + v6Trailer)
        assertNotNull(v6Decoded)
        val v6Target = checkNotNull(v6Decoded!!.explicitTarget)
        assertArrayEquals(sampleQuery, v6Decoded.dnsPayload)
        assertEquals(
            InetAddress.getByName("2606:4700:4700::1111"),
            InetAddress.getByName(v6Target.ip),
        )
        assertEquals(5353, v6Target.port)
    }

    @Test
    fun `isGloballyRoutableIp and proxyUpstreamEndpoints filter private LAN resolvers`() {
        assertFalse(ProotDns.isGloballyRoutableIp("127.0.0.1"))
        assertFalse(ProotDns.isGloballyRoutableIp("::1"))
        assertFalse(ProotDns.isGloballyRoutableIp("192.168.1.1"))
        assertFalse(ProotDns.isGloballyRoutableIp("10.0.0.53"))
        assertFalse(ProotDns.isGloballyRoutableIp("172.20.0.1"))
        assertFalse(ProotDns.isGloballyRoutableIp("100.64.1.1"))
        assertFalse(ProotDns.isGloballyRoutableIp("169.254.1.1"))
        assertFalse(ProotDns.isGloballyRoutableIp("fe80::1"))
        assertFalse(ProotDns.isGloballyRoutableIp("fd00::53"))
        assertTrue(ProotDns.isGloballyRoutableIp("1.1.1.1"))
        assertTrue(ProotDns.isGloballyRoutableIp("9.9.9.9"))
        assertTrue(ProotDns.isGloballyRoutableIp("2606:4700:4700::1111"))

        // System DHCP handing out private LAN IP falls back to PUBLIC_SERVERS when proxied.
        assertEquals(
            listOf("8.8.8.8", "1.1.1.1"),
            ProotDns.nameservers(ProotDnsMode.SYSTEM, null, proxyActive = true) {
                listOf("192.168.1.1", "fe80::1")
            },
        )
        assertEquals(
            listOf(ProotDns.DnsEndpoint("9.9.9.9", 53)),
            ProotDns.proxyUpstreamEndpoints(ProotDnsMode.SYSTEM, null) {
                listOf("192.168.1.1", "9.9.9.9")
            },
        )
        // Custom loopback-only listener (Tor DNSPort 127.0.0.1:5400) keeps fallback public upstreams.
        assertEquals(
            ProotDns.PUBLIC_SERVERS.map { ProotDns.DnsEndpoint(it, 53) },
            ProotDns.proxyUpstreamEndpoints(ProotDnsMode.CUSTOM, "127.0.0.1:5400") { emptyList() },
        )
        assertEquals(
            listOf(ProotDns.DnsEndpoint("9.9.9.9", 53)),
            ProotDns.proxyUpstreamEndpoints(ProotDnsMode.CUSTOM, "127.0.0.1:5400, 9.9.9.9") { emptyList() },
        )
    }

    @Test
    fun `UDP queries route default and explicit per-server dig targets over SOCKS5 with auth and failover`() {
        MockSocks5DnsProxy(
            expectedUser = "havenuser",
            expectedPass = "s3cr3t",
            refuseTargets = setOf("198.51.100.1:53"),
        ).use { socksServer ->
            ProotDnsProxyBridge(connectTimeoutMs = 2_000, readTimeoutMs = 2_000).use { bridge ->
                val proxy = ProotRouting.ParsedProxyEndpoint(
                    scheme = "socks5h",
                    host = "127.0.0.1",
                    port = socksServer.port,
                    username = "havenuser",
                    password = "s3cr3t",
                )
                val upstreams = listOf(
                    ProotDns.DnsEndpoint("198.51.100.1", 53), // fails -> exercises failover
                    ProotDns.DnsEndpoint("8.8.8.8", 53),
                )
                val listeners = bridge.ensureStarted(proxy, upstreams)

                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { client ->
                    client.soTimeout = 3_000
                    val bridgeAddr = InetAddress.getByName("127.0.0.1")

                    // 1. Plain / loopback-trailered query uses configured upstream after failover to 8.8.8.8.
                    val q1 = buildMinimalDnsQuery(txId = 0x2201) +
                        ProotDnsProxyBridge.encodeTrailer(ProotDns.DnsEndpoint("127.0.0.1", 53))
                    client.send(DatagramPacket(q1, q1.size, bridgeAddr, listeners.ipv4Port))
                    val r1 = receivePacket(client)
                    assertEquals(0x2201, readTxId(r1))
                    assertEquals("8.8.8.8", readAnswerIp(r1))

                    // 2. Explicit `dig @1.1.1.1` trailer tunnels to 1.1.1.1:53 directly!
                    val q2 = buildMinimalDnsQuery(txId = 0x2202) +
                        ProotDnsProxyBridge.encodeTrailer(ProotDns.DnsEndpoint("1.1.1.1", 53))
                    client.send(DatagramPacket(q2, q2.size, bridgeAddr, listeners.ipv4Port))
                    val r2 = receivePacket(client)
                    assertEquals(0x2202, readTxId(r2))
                    assertEquals("1.1.1.1", readAnswerIp(r2))

                    // 3. Explicit `dig @9.9.9.9` trailer tunnels to 9.9.9.9:53 directly!
                    val q3 = buildMinimalDnsQuery(txId = 0x2203) +
                        ProotDnsProxyBridge.encodeTrailer(ProotDns.DnsEndpoint("9.9.9.9", 53))
                    client.send(DatagramPacket(q3, q3.size, bridgeAddr, listeners.ipv4Port))
                    val r3 = receivePacket(client)
                    assertEquals(0x2203, readTxId(r3))
                    assertEquals("9.9.9.9", readAnswerIp(r3))
                }

                // 4. IPv6 listener + IPv6 SOCKS5 ATYP=0x04 target (`dig @2606:4700:4700::1111`) when IPv6 loopback exists.
                val v6Port = listeners.ipv6Port
                if (v6Port != null) {
                    val v6Loopback = Inet6Address.getByAddress(null, ByteArray(16).apply { this[15] = 1 }, -1)
                    DatagramSocket(0, v6Loopback).use { client6 ->
                        client6.soTimeout = 3_000
                        val q4 = buildMinimalDnsQuery(txId = 0x2204) +
                            ProotDnsProxyBridge.encodeTrailer(
                                ProotDns.DnsEndpoint("2606:4700:4700::1111", 53),
                            )
                        client6.send(DatagramPacket(q4, q4.size, v6Loopback, v6Port))
                        val r4 = receivePacket(client6)
                        assertEquals(0x2204, readTxId(r4))
                        assertTrue(
                            socksServer.observedTargets.any { it.endsWith(":53") && "2606:4700:4700" in it },
                        )
                    }
                }

                assertTrue(socksServer.observedTargets.contains("198.51.100.1:53"))
                assertTrue(socksServer.observedTargets.contains("8.8.8.8:53"))
                assertTrue(socksServer.observedTargets.contains("1.1.1.1:53"))
                assertTrue(socksServer.observedTargets.contains("9.9.9.9:53"))
            }
        }
    }

    @Test
    fun `TCP DNS queries tunnel through HTTP CONNECT proxy with Basic authentication`() {
        MockHttpConnectDnsProxy(expectedAuth = "proxyuser:proxypass").use { httpProxy ->
            ProotDnsProxyBridge(connectTimeoutMs = 2_000, readTimeoutMs = 2_000).use { bridge ->
                val proxy = ProotRouting.ParsedProxyEndpoint(
                    scheme = "http",
                    host = "127.0.0.1",
                    port = httpProxy.port,
                    username = "proxyuser",
                    password = "proxypass",
                )
                val upstreams = listOf(ProotDns.DnsEndpoint("9.9.9.9", 53))
                val listeners = bridge.ensureStarted(proxy, upstreams)

                Socket("127.0.0.1", listeners.ipv4Port).use { tcpClient ->
                    tcpClient.soTimeout = 3_000
                    val query = buildMinimalDnsQuery(txId = 0x4410)
                    val out = BufferedOutputStream(tcpClient.getOutputStream())
                    out.write((query.size ushr 8) and 0xFF)
                    out.write(query.size and 0xFF)
                    out.write(query)
                    out.flush()

                    val input = DataInputStream(BufferedInputStream(tcpClient.getInputStream()))
                    val respLen = input.readUnsignedShort()
                    val resp = ByteArray(respLen)
                    input.readFully(resp)
                    assertEquals(0x4410, readTxId(resp))
                    assertEquals("9.9.9.9", readAnswerIp(resp))
                }
                assertEquals(listOf("9.9.9.9:53"), httpProxy.observedAuthorities)
            }
        }
    }

    private fun buildMinimalDnsQuery(txId: Int): ByteArray {
        // 12-byte header + QNAME "\x07example\x03com\x00" + QTYPE A (1) + QCLASS IN (1)
        val qname = byteArrayOf(
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0,
            0, 1,
            0, 1,
        )
        val header = byteArrayOf(
            ((txId ushr 8) and 0xFF).toByte(),
            (txId and 0xFF).toByte(),
            0x01, 0x00, // RD=1
            0x00, 0x01, // QDCOUNT=1
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
        )
        return header + qname
    }

    private fun buildSyntheticDnsResponse(query: ByteArray, responderIp: String): ByteArray {
        val answerV4 = runCatching {
            val bytes = InetAddress.getByName(responderIp).address
            if (bytes.size == 4) bytes else byteArrayOf(104.toByte(), 16, 0, 1)
        }.getOrDefault(byteArrayOf(104.toByte(), 16, 0, 1))
        val respHeader = query.copyOf()
        respHeader[2] = 0x81.toByte() // QR=1, RD=1
        respHeader[3] = 0x80.toByte() // RA=1, NOERROR
        respHeader[6] = 0x00
        respHeader[7] = 0x01 // ANCOUNT=1
        val answerRr = byteArrayOf(
            0xC0.toByte(), 0x0C, // pointer to QNAME at offset 12
            0x00, 0x01, // TYPE A
            0x00, 0x01, // CLASS IN
            0x00, 0x00, 0x00, 0x3C, // TTL 60
            0x00, 0x04, // RDLENGTH 4
            answerV4[0], answerV4[1], answerV4[2], answerV4[3],
        )
        return respHeader + answerRr
    }

    private fun receivePacket(socket: DatagramSocket): ByteArray {
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        socket.receive(pkt)
        return pkt.data.copyOfRange(0, pkt.length)
    }

    private fun readTxId(dns: ByteArray): Int =
        ((dns[0].toInt() and 0xFF) shl 8) or (dns[1].toInt() and 0xFF)

    private fun readAnswerIp(dns: ByteArray): String =
        InetAddress.getByAddress(dns.copyOfRange(dns.size - 4, dns.size)).hostAddress.orEmpty()

    private inner class MockSocks5DnsProxy(
        private val expectedUser: String?,
        private val expectedPass: String?,
        private val refuseTargets: Set<String> = emptySet(),
    ) : Closeable {
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        val observedTargets: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val port: Int get() = server.localPort

        init {
            Thread {
                while (running.get() && !server.isClosed) {
                    val sock = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    pool.execute { handleClient(sock) }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun handleClient(sock: Socket) {
            sock.use { s ->
                s.soTimeout = 3_000
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                val out = BufferedOutputStream(s.getOutputStream())

                val ver = input.readUnsignedByte()
                val nMethods = input.readUnsignedByte()
                val methods = ByteArray(nMethods)
                input.readFully(methods)
                if (ver != 0x05) return

                if (expectedUser != null) {
                    if (!methods.contains(0x02)) {
                        out.write(byteArrayOf(0x05, 0xFF.toByte()))
                        out.flush()
                        return
                    }
                    out.write(byteArrayOf(0x05, 0x02))
                    out.flush()
                    val authVer = input.readUnsignedByte()
                    val ulen = input.readUnsignedByte()
                    val ubytes = ByteArray(ulen).also { input.readFully(it) }
                    val plen = input.readUnsignedByte()
                    val pbytes = ByteArray(plen).also { input.readFully(it) }
                    val ok = authVer == 0x01 &&
                        String(ubytes, StandardCharsets.UTF_8) == expectedUser &&
                        String(pbytes, StandardCharsets.UTF_8) == expectedPass
                    out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                    out.flush()
                    if (!ok) return
                } else {
                    out.write(byteArrayOf(0x05, 0x00))
                    out.flush()
                }

                val reqVer = input.readUnsignedByte()
                val cmd = input.readUnsignedByte()
                input.readUnsignedByte() // rsv
                val atyp = input.readUnsignedByte()
                if (reqVer != 0x05 || cmd != 0x01) return
                val hostIp = when (atyp) {
                    0x01 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                    0x04 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                    else -> return
                }
                val dstPort = input.readUnsignedShort()
                val spec = if (':' in hostIp) "[$hostIp]:$dstPort" else "$hostIp:$dstPort"
                observedTargets += spec

                if (spec in refuseTargets) {
                    out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    return
                }
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 53))
                out.flush()

                val qLen = input.readUnsignedShort()
                val qBytes = ByteArray(qLen).also { input.readFully(it) }
                val resp = buildSyntheticDnsResponse(qBytes, hostIp)
                out.write((resp.size ushr 8) and 0xFF)
                out.write(resp.size and 0xFF)
                out.write(resp)
                out.flush()
            }
        }

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            pool.shutdownNow()
        }
    }

    private inner class MockHttpConnectDnsProxy(
        private val expectedAuth: String,
    ) : Closeable {
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        val observedAuthorities: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val port: Int get() = server.localPort

        init {
            Thread {
                while (running.get() && !server.isClosed) {
                    val sock = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    pool.execute { handleClient(sock) }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun handleClient(sock: Socket) {
            sock.use { s ->
                s.soTimeout = 3_000
                val rawIn = s.getInputStream()
                val out = BufferedOutputStream(s.getOutputStream())
                val reqLine = readLine(rawIn) ?: return
                val authority = reqLine.split(' ')[1]
                var authed = false
                val expectedHeader = "Basic " +
                    Base64.getEncoder().encodeToString(expectedAuth.toByteArray(StandardCharsets.UTF_8))
                while (true) {
                    val line = readLine(rawIn) ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Proxy-Authorization:", ignoreCase = true)) {
                        authed = line.substringAfter(':').trim() == expectedHeader
                    }
                }
                if (!authed) {
                    out.write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                observedAuthorities += authority
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                out.flush()

                val dis = DataInputStream(rawIn)
                val qLen = dis.readUnsignedShort()
                val qBytes = ByteArray(qLen).also { dis.readFully(it) }
                val targetIp = authority.substringBeforeLast(':')
                val resp = buildSyntheticDnsResponse(qBytes, targetIp)
                out.write((resp.size ushr 8) and 0xFF)
                out.write(resp.size and 0xFF)
                out.write(resp)
                out.flush()
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            pool.shutdownNow()
        }
    }
}
