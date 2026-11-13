package sh.haven.core.knock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class PortKnockerTest {

    private val tcpListeners = mutableListOf<ServerSocket>()
    private val udpListeners = mutableListOf<DatagramSocket>()

    @After
    fun tearDown() {
        tcpListeners.forEach { runCatching { it.close() } }
        udpListeners.forEach { runCatching { it.close() } }
    }

    @Test
    fun `tcp knocks land on every port in the sequence`() = runBlocking {
        // We deliberately don't assert the order of the recorded ports —
        // each accept() runs in its own coroutine on Dispatchers.IO and
        // the relative timing of "kernel hands SYN to listening socket"
        // vs "JVM schedules the +=" is not deterministic across hosts
        // (CI runners are notably more sensitive than dev machines).
        // The knocker's contract is "fire every step in order"; the
        // mixed-protocol test below uses one socket per type and covers
        // the ordering aspect.
        val ports = (1..3).map {
            ServerSocket(0).also { tcpListeners += it; it.soTimeout = 2000 }.localPort
        }
        val acceptedPorts = CopyOnWriteArrayList<Int>()

        val accepts = tcpListeners.map { server ->
            async(Dispatchers.IO) {
                runCatching {
                    server.accept().use { acceptedPorts += server.localPort }
                }
            }
        }

        val sequence = KnockSequence(
            ports.map { KnockStep(it, KnockStep.Protocol.TCP) },
            interKnockDelayMs = 0,
        )
        val result = withTimeout(5000) {
            DefaultPortKnocker().knock("127.0.0.1", sequence)
        }

        // Wait for the accept side to finish recording.
        withTimeout(2000) { accepts.forEach { it.await() } }

        assertNull("knock returned error: ${result.error}", result.error)
        assertEquals(ports.size, result.sentSteps)
        assertEquals(ports.toSet(), acceptedPorts.toSet())
    }

    @Test
    fun `udp knocks deliver a datagram to every port in the sequence`() = runBlocking {
        // Same ordering caveat as the TCP test above.
        val sockets = (1..3).map {
            DatagramSocket(0).also { udpListeners += it; it.soTimeout = 2000 }
        }
        val ports = sockets.map { it.localPort }
        val received = CopyOnWriteArrayList<Int>()

        val receivers = sockets.map { sock ->
            async(Dispatchers.IO) {
                val buf = ByteArray(16)
                val pkt = DatagramPacket(buf, buf.size)
                runCatching {
                    sock.receive(pkt)
                    received += sock.localPort
                }
            }
        }

        val sequence = KnockSequence(
            ports.map { KnockStep(it, KnockStep.Protocol.UDP) },
            interKnockDelayMs = 0,
        )
        val result = withTimeout(5000) {
            DefaultPortKnocker().knock("127.0.0.1", sequence)
        }

        withTimeout(2000) { receivers.forEach { it.await() } }

        assertNull("knock returned error: ${result.error}", result.error)
        assertEquals(ports.size, result.sentSteps)
        assertEquals(ports.toSet(), received.toSet())
    }

    @Test
    fun `mixed tcp and udp both delivered`() = runBlocking {
        val tcp = ServerSocket(0).also { tcpListeners += it; it.soTimeout = 2000 }
        val udp = DatagramSocket(0).also { udpListeners += it; it.soTimeout = 2000 }

        val tcpAccepted = async(Dispatchers.IO) {
            runCatching { tcp.accept().use { } }.isSuccess
        }
        val udpReceived = async(Dispatchers.IO) {
            val buf = ByteArray(16)
            runCatching { udp.receive(DatagramPacket(buf, buf.size)) }.isSuccess
        }

        val sequence = KnockSequence(
            listOf(
                KnockStep(tcp.localPort, KnockStep.Protocol.TCP),
                KnockStep(udp.localPort, KnockStep.Protocol.UDP),
            ),
            interKnockDelayMs = 0,
        )
        val result = withTimeout(5000) {
            DefaultPortKnocker().knock("127.0.0.1", sequence)
        }

        assertTrue("tcp not accepted", withTimeout(2000) { tcpAccepted.await() })
        assertTrue("udp not received", withTimeout(2000) { udpReceived.await() })
        assertNull(result.error)
        assertEquals(2, result.sentSteps)
    }

    @Test
    fun `icmp echo packet builder encodes v4 and v6 headers and exact payload length`() {
        val v4 = buildIcmpEchoPacket(ipv6 = false, sequenceNumber = 0x0102, payloadBytes = 495)
        assertEquals(503, v4.size)
        assertEquals(8.toByte(), v4[0])
        assertEquals(0.toByte(), v4[1])
        assertEquals(0.toByte(), v4[2])
        assertEquals(0.toByte(), v4[3])
        assertEquals(1.toByte(), v4[6])
        assertEquals(2.toByte(), v4[7])
        assertTrue(v4.drop(8).all { it == 0.toByte() })

        val v6 = buildIcmpEchoPacket(ipv6 = true, sequenceNumber = 7, payloadBytes = 42)
        assertEquals(50, v6.size)
        assertEquals(128.toByte(), v6[0])
        assertEquals(0.toByte(), v6[6])
        assertEquals(7.toByte(), v6[7])
    }

    @Test
    fun `icmp knocks dispatch exact payload sizes and incrementing sequence numbers`() = runBlocking {
        val sentEchos = CopyOnWriteArrayList<Triple<String, Int, Int>>()
        val knocker = DefaultPortKnocker { addr, seqNum, payloadBytes ->
            sentEchos += Triple(addr.hostAddress, seqNum, payloadBytes)
        }
        val sequence = KnockSequence.parse(
            "495x1:ICMP, 792x1:ICMP, 503/icmp+h, 523/icmp+ip",
            delayMs = 0,
        ).getOrThrow()!!

        val result = withTimeout(5000) {
            knocker.knock("127.0.0.1", sequence)
        }

        assertNull("knock returned error: ${result.error}", result.error)
        assertEquals(4, result.sentSteps)
        assertEquals(
            listOf(
                Triple("127.0.0.1", 1, 495),
                Triple("127.0.0.1", 2, 792),
                Triple("127.0.0.1", 3, 495),
                Triple("127.0.0.1", 4, 495),
            ),
            sentEchos.toList(),
        )
    }

    @Test
    fun `icmp ipv6 header-inclusive size below 48 bytes surfaces error in KnockResult`() = runBlocking {
        val knocker = DefaultPortKnocker { _, _, _ -> }
        val sequence = KnockSequence.parse("40/icmp+ip", delayMs = 0).getOrThrow()!!
        val result = knocker.knock("::1", sequence)
        assertTrue(!result.ok)
        assertEquals(0, result.sentSteps)
    }

    @Test
    fun `icmp sender IOException surfaces in KnockResult with partial sentSteps`() = runBlocking {
        var calls = 0
        val knocker = DefaultPortKnocker { _, _, _ ->
            calls++
            if (calls == 2) throw IOException("EACCES: ping_group_range")
        }
        val sequence = KnockSequence.parse("495x1:ICMP, 792x1:ICMP, 42x1:ICMP", delayMs = 0).getOrThrow()!!
        val result = knocker.knock("127.0.0.1", sequence)
        assertTrue(!result.ok)
        assertEquals(1, result.sentSteps)
    }

    @Test
    fun `unresolvable host returns error in result, does not throw`() = runBlocking {
        val sequence = KnockSequence(
            listOf(KnockStep(7000, KnockStep.Protocol.TCP)),
            interKnockDelayMs = 0,
        )
        val result = withContext(Dispatchers.IO) {
            DefaultPortKnocker().knock(
                "no-such-host.invalid.example.test.haven.local-fail",
                sequence,
            )
        }
        assertTrue("expected error for unresolvable host, got $result", !result.ok)
        assertEquals(0, result.sentSteps)
    }
}
