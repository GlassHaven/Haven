package sh.haven.core.knock

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a knock attempt. Carries the list of steps actually sent and timing. */
data class KnockResult(
    val sentSteps: Int,
    val totalDurationMs: Long,
    val error: Throwable? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * Sends a port-knock sequence to a host. Implementations open and immediately
 * close TCP/UDP/ICMP sockets for each step in order — the SYN (TCP), datagram
 * (UDP), or Echo Request length (ICMP) is what the remote firewall watches for;
 * we don't expect a response.
 *
 * Failures during knocking are returned in [KnockResult.error] rather than
 * thrown, so the caller can decide whether to abort the real connect or
 * continue regardless.
 */
interface PortKnocker {
    suspend fun knock(host: String, sequence: KnockSequence): KnockResult
}

/**
 * Low-level sender for unprivileged ICMP / ICMPv6 Echo Requests (#846).
 * Extracted as an internal seam so JVM unit tests can assert exact payload
 * lengths and header-mode math without requiring Android ART `libcore` JNI.
 */
internal fun interface IcmpEchoSender {
    @Throws(IOException::class)
    fun sendEcho(addr: InetAddress, sequenceNumber: Int, payloadBytes: Int)
}

/**
 * Android runtime sender using unprivileged `SOCK_DGRAM` `IPPROTO_ICMP` /
 * `IPPROTO_ICMPV6` sockets (`net.ipv4.ping_group_range` includes `aid_inet`,
 * so no root or external `ping` binary is needed). The Linux kernel fills the
 * Echo identifier and checksum and transmits the exact `8 + payloadBytes`
 * buffer passed to `sendto`.
 */
internal object AndroidOsIcmpEchoSender : IcmpEchoSender {
    override fun sendEcho(addr: InetAddress, sequenceNumber: Int, payloadBytes: Int) {
        val ipv6 = addr is Inet6Address
        val domain = if (ipv6) OsConstants.AF_INET6 else OsConstants.AF_INET
        val proto = if (ipv6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP
        val packet = buildIcmpEchoPacket(
            ipv6 = ipv6,
            sequenceNumber = sequenceNumber,
            payloadBytes = payloadBytes,
        )
        val fd = try {
            Os.socket(domain, OsConstants.SOCK_DGRAM, proto)
        } catch (e: ErrnoException) {
            throw IOException("ICMP socket failed (${Os.strerror(e.errno)}): ${e.message}", e)
        } ?: throw IOException("Os.socket returned null FileDescriptor for ICMP")
        try {
            val sent = Os.sendto(fd, packet, 0, packet.size, 0, addr, 0)
            if (sent != packet.size) {
                throw IOException("Short ICMP send: $sent/${packet.size} bytes")
            }
        } catch (e: ErrnoException) {
            throw IOException("ICMP sendto failed (${Os.strerror(e.errno)}): ${e.message}", e)
        } finally {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
                // ignore close errors
            }
        }
    }
}

/**
 * Construct an 8-byte ICMPv4 (`type = 8`) or ICMPv6 (`type = 128`) Echo Request
 * header followed by [payloadBytes] zero bytes, matching `ping -s` and Knock on
 * Ports' `SOCK_DGRAM` `IPPROTO_ICMP` wire layout.
 */
internal fun buildIcmpEchoPacket(
    ipv6: Boolean,
    sequenceNumber: Int,
    payloadBytes: Int,
): ByteArray {
    val maxPayload = if (ipv6) {
        KnockStep.MAX_IPV6_ICMP_PAYLOAD_BYTES
    } else {
        KnockStep.MAX_IPV4_ICMP_PAYLOAD_BYTES
    }
    require(payloadBytes in 0..maxPayload) {
        "ICMP payloadBytes out of range (0..$maxPayload): $payloadBytes"
    }
    val packet = ByteArray(KnockStep.ICMP_ECHO_HEADER_BYTES + payloadBytes)
    packet[0] = if (ipv6) ICMPV6_ECHO_REQUEST_TYPE else ICMPV4_ECHO_REQUEST_TYPE
    packet[1] = 0 // code = 0
    // packet[2..3] checksum = 0 (computed by kernel on SOCK_DGRAM IPPROTO_ICMP/ICMPV6)
    // packet[4..5] identifier = 0 (rewritten by kernel to socket local port)
    packet[6] = ((sequenceNumber ushr 8) and 0xff).toByte()
    packet[7] = (sequenceNumber and 0xff).toByte()
    return packet
}

private const val ICMPV4_ECHO_REQUEST_TYPE: Byte = 8
private const val ICMPV6_ECHO_REQUEST_TYPE: Byte = 128.toByte()

@Singleton
class DefaultPortKnocker internal constructor(
    private val icmpEchoSender: IcmpEchoSender,
) : PortKnocker {

    @Inject
    constructor() : this(AndroidOsIcmpEchoSender)

    /** Per-knock connect attempt — short enough that we don't block the UI on a stale host. */
    private val perKnockTimeoutMs = 250

    /** Wait after the last packet so the firewall has time to install its rule. */
    private val postKnockSettleMs = 200L

    override suspend fun knock(host: String, sequence: KnockSequence): KnockResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            var sent = 0
            var icmpSequenceNumber = 1
            try {
                val addr = InetAddress.getByName(host)
                for ((index, step) in sequence.steps.withIndex()) {
                    when (step.protocol) {
                        KnockStep.Protocol.TCP -> sendTcp(addr, step.port)
                        KnockStep.Protocol.UDP -> sendUdp(addr, step.port)
                        KnockStep.Protocol.ICMP -> {
                            val payloadBytes = step.icmpPayloadBytes(ipv6 = addr is Inet6Address)
                            icmpEchoSender.sendEcho(addr, icmpSequenceNumber++, payloadBytes)
                        }
                    }
                    sent++
                    if (index < sequence.steps.lastIndex && sequence.interKnockDelayMs > 0) {
                        delay(sequence.interKnockDelayMs.toLong())
                    }
                }
                delay(postKnockSettleMs)
                KnockResult(sent, System.currentTimeMillis() - started)
            } catch (t: Throwable) {
                KnockResult(sent, System.currentTimeMillis() - started, t)
            }
        }

    private fun sendTcp(addr: InetAddress, port: Int) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(addr, port), perKnockTimeoutMs)
        } catch (_: SocketTimeoutException) {
            // Expected: knockd doesn't accept the connection, the SYN is what matters.
        } catch (_: ConnectException) {
            // Expected: port is closed, the SYN reached the firewall.
        } catch (_: IOException) {
            // Other I/O errors (e.g. ECONNRESET) — also fine, the packet went out.
        } finally {
            try { socket.close() } catch (_: IOException) { /* ignore */ }
        }
    }

    private fun sendUdp(addr: InetAddress, port: Int) {
        val socket = DatagramSocket()
        try {
            // A single zero byte is enough for knockd to register the packet.
            val payload = byteArrayOf(0)
            socket.send(DatagramPacket(payload, payload.size, addr, port))
        } finally {
            socket.close()
        }
    }
}
