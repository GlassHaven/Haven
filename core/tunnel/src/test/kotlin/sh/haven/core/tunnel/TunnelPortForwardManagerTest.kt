package sh.haven.core.tunnel

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.repository.TunnelConfigRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TunnelPortForwardManagerTest {

    private lateinit var cacheDir: File
    private lateinit var repo: TunnelConfigRepository
    private lateinit var fakeTunnel: FakeForwardTunnel
    private lateinit var tunnelManager: TunnelManager
    private lateinit var forwardManager: TunnelPortForwardManager

    private class FakeUdpSocket : TunneledDatagramSocket {
        data class SentDatagram(val data: ByteArray, val host: String, val port: Int)
        val sent = LinkedBlockingQueue<SentDatagram>()
        val inbound = LinkedBlockingQueue<ReceivedPacketWithData>()
        @Volatile var closed = false

        data class ReceivedPacketWithData(val data: ByteArray, val srcHost: String, val srcPort: Int)

        override fun send(data: ByteArray, host: String, port: Int) {
            sent.offer(SentDatagram(data.copyOf(), host, port))
            // Echo back with prefix "echo:"
            val reply = ("echo:" + String(data, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
            inbound.offer(ReceivedPacketWithData(reply, host, port))
        }

        override fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket? {
            if (closed) throw java.io.IOException("closed")
            val item = inbound.poll(timeoutMs.toLong().coerceAtLeast(10L), TimeUnit.MILLISECONDS)
                ?: return null
            val len = minOf(buf.size, item.data.size)
            System.arraycopy(item.data, 0, buf, 0, len)
            return ReceivedPacket(len, item.srcHost, item.srcPort)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeForwardTunnel(
        private val socksPort: Int = 45123,
    ) : Tunnel {
        @Volatile var lastDialHost: String? = null
        @Volatile var lastDialPort: Int = -1
        val lastReceivedBytes = ByteArrayOutputStream()
        val udpSocket = FakeUdpSocket()
        @Volatile var closed = false

        override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
            lastDialHost = host
            lastDialPort = port
            val clientToRemoteIn = PipedInputStream()
            val clientToRemoteOut = PipedOutputStream(clientToRemoteIn)
            val remoteToClientIn = PipedInputStream()
            val remoteToClientOut = PipedOutputStream(remoteToClientIn)

            Thread {
                try {
                    val buf = ByteArray(1024)
                    val n = clientToRemoteIn.read(buf)
                    if (n > 0) {
                        synchronized(lastReceivedBytes) {
                            lastReceivedBytes.write(buf, 0, n)
                        }
                        val req = String(buf, 0, n, Charsets.UTF_8)
                        val resp = "TUNNELED:$host:$port:$req".toByteArray(Charsets.UTF_8)
                        remoteToClientOut.write(resp)
                        remoteToClientOut.flush()
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { remoteToClientOut.close() }
                }
            }.apply { isDaemon = true; start() }

            return object : TunneledConnection {
                override val inputStream: InputStream = remoteToClientIn
                override val outputStream: OutputStream = clientToRemoteOut
                override fun close() {
                    runCatching { clientToRemoteOut.close() }
                    runCatching { remoteToClientIn.close() }
                }
            }
        }

        override fun listenUdp(): TunneledDatagramSocket = udpSocket

        override fun socksAddress(): InetSocketAddress = InetSocketAddress("127.0.0.1", socksPort)

        override fun close() {
            closed = true
        }
    }

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("tunnel-fwd-test").toFile()
        fakeTunnel = FakeForwardTunnel(socksPort = 45678)
        repo = mockk()
        coEvery { repo.getById("wg-1") } returns TunnelConfig(
            id = "wg-1",
            label = "Test WG",
            type = TunnelConfigType.WIREGUARD.name,
            configText = "[Interface]".toByteArray(),
            createdAt = 1000L,
        )
        tunnelManager = TunnelManager(
            tunnelConfigRepository = repo,
            tunnelFactory = object : TunnelFactory {
                override fun create(config: TunnelConfig): Tunnel = fakeTunnel
            },
        )
        forwardManager = TunnelPortForwardManager(
            tunnelManager = tunnelManager,
            cacheDir = cacheDir,
        )
    }

    @Test
    fun `connectStandalone starts socks5 marker and disconnectStandalone clears it`() = runBlocking {
        val tunnel = forwardManager.connectStandalone("wg-1")
        assertNotNull(tunnel)
        assertTrue(tunnelManager.hasStandaloneDependents)
        assertEquals(45678, forwardManager.socksPorts.value["wg-1"])

        val marker = File(cacheDir, TunnelPortForwardManager.GUEST_SOCKS_MARKER)
        assertTrue(marker.isFile)
        assertEquals("127.0.0.1:45678", marker.readText().trim())

        forwardManager.disconnectStandalone("wg-1")
        // Wait briefly for live flow collector to clear marker
        var attempts = 0
        while (marker.exists() && attempts++ < 20) {
            Thread.sleep(20)
        }
        assertFalse(tunnelManager.hasStandaloneDependents)
        assertFalse(marker.exists())
    }

    @Test
    fun `setAutoProxyGuests toggles guest auto proxy marker file`() {
        val marker = File(cacheDir, TunnelPortForwardManager.GUEST_AUTO_PROXY_MARKER)
        assertFalse(marker.exists())

        forwardManager.setAutoProxyGuests(true)
        assertTrue(forwardManager.autoProxyGuests.value)
        assertTrue(marker.isFile)

        forwardManager.setAutoProxyGuests(false)
        assertFalse(forwardManager.autoProxyGuests.value)
        assertFalse(marker.exists())
    }

    @Test
    fun `plain TCP port forward bridges localhost socket to tunnel dial`() = runBlocking {
        val rule = forwardManager.addRule(
            tunnelConfigId = "wg-1",
            protocol = TunnelForwardProtocol.TCP,
            localPort = 0,
            remoteHost = "10.20.30.40",
            remotePort = 8080,
        )
        val boundPort = forwardManager.boundPorts.value[rule.id]
        assertNotNull("Expected bound TCP port", boundPort)
        assertTrue(boundPort!! > 0)

        Socket("127.0.0.1", boundPort).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write("hello-http".toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            val reply = client.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertEquals("TUNNELED:10.20.30.40:8080:hello-http", reply)
        }
        assertEquals("10.20.30.40", fakeTunnel.lastDialHost)
        assertEquals(8080, fakeTunnel.lastDialPort)

        forwardManager.removeRule(rule.id)
        assertFalse(forwardManager.boundPorts.value.containsKey(rule.id))
    }

    @Test
    fun `plain UDP port forward bridges localhost datagrams to tunnel listenUdp`() = runBlocking {
        val rule = forwardManager.addRule(
            tunnelConfigId = "wg-1",
            protocol = TunnelForwardProtocol.UDP,
            localPort = 0,
            remoteHost = "10.20.30.50",
            remotePort = 5353,
        )
        val boundPort = forwardManager.boundPorts.value[rule.id]
        assertNotNull("Expected bound UDP port", boundPort)
        assertTrue(boundPort!! > 0)

        DatagramSocket().use { client ->
            client.soTimeout = 5_000
            val payload = "ping-udp".toByteArray(Charsets.UTF_8)
            val pkt = DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), boundPort)
            client.send(pkt)

            val rxBuf = ByteArray(1024)
            val rxPkt = DatagramPacket(rxBuf, rxBuf.size)
            client.receive(rxPkt)
            val reply = String(rxPkt.data, 0, rxPkt.length, Charsets.UTF_8)
            assertEquals("echo:ping-udp", reply)
        }

        val sent = fakeTunnel.udpSocket.sent.poll(2, TimeUnit.SECONDS)
        assertNotNull(sent)
        assertEquals("10.20.30.50", sent!!.host)
        assertEquals(5353, sent.port)
        assertArrayEquals("ping-udp".toByteArray(Charsets.UTF_8), sent.data)

        forwardManager.removeRule(rule.id)
        assertFalse(forwardManager.boundPorts.value.containsKey(rule.id))
    }
}
