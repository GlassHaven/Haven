package sh.haven.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.tunnel.DefaultTunnelFactory
import sh.haven.core.tunnel.OpenVpnConfigBlob
import sh.haven.core.tunnel.OpenVpnTunnel
import sh.haven.core.tunnel.SystemVpnAddressProbe
import sh.haven.core.tunnel.TunnelAuthBroker

/**
 * On-device instrumented verification for [OpenVpnTunnel] on Android hardware
 * (`libgojni.so` + `libopenvpn3_android_arm64.a`).
 *
 * When invoked with `-e ovpnProfileBase64 <base64>` pointing at a live
 * OpenVPN server on the LAN, exercises the full end-to-end data plane:
 * - TLS 1.3 handshake + `PUSH_REPLY` address assignment (`localAddress()`)
 * - Pushed `dhcp-option DNS` resolution (`adapter.cpp` `tun_builder_set_dns_options`
 *   -> `OvpnTunnelSetup.AddDnsServer` -> gVisor `netstack`) + TCP `dial()`
 * - UDP `listenUdp()` (`NativeOvpnDatagramSocket` used by Mosh)
 * - Inbound TCP `listenTcp()` (`NativeOvpnTunneledServerSocket`)
 * - Local SOCKS5 listener (`socksAddress()`)
 * - Clean `close()` teardown
 */
@RunWith(AndroidJUnit4::class)
class OpenVpnDeviceTunnelTest {

    private fun newFactory(): DefaultTunnelFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return DefaultTunnelFactory(
            context = context,
            httpClient = OkHttpClient(),
            authBroker = TunnelAuthBroker(context),
            systemVpnAddressProbe = SystemVpnAddressProbe(context),
        )
    }

    @Test
    fun nativeJniRejectsInvalidProfileCleanly() = runBlocking {
        val factory = newFactory()
        val badConfig = TunnelConfig(
            id = "bad-ovpn",
            label = "bad-ovpn",
            type = TunnelConfigType.OPENVPN.name,
            configText = OpenVpnConfigBlob(
                ovpnConfigText = "not a valid openvpn profile\nremote 127.0.0.1 1\n",
            ).encode(),
        )
        val result = runCatching { factory.create(badConfig) }
        assertTrue("Expected invalid OpenVPN profile to fail cleanly over JNI", result.isFailure)
    }

    @Test
    fun liveOpenVpnHandshakeDnsTcpUdpAndInboundTcpOnDevice() = runBlocking {
        val b64 = InstrumentationRegistry.getArguments().getString("ovpnProfileBase64")
        assumeTrue(
            "Pass -e ovpnProfileBase64 <base64> to run live OpenVPN e2e test against host server",
            !b64.isNullOrBlank(),
        )
        val profileText = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)

        val config = TunnelConfig(
            id = "device-live-ovpn",
            label = "device-live-ovpn",
            type = TunnelConfigType.OPENVPN.name,
            configText = OpenVpnConfigBlob(ovpnConfigText = profileText).encode(),
        )
        val tunnel = newFactory().create(config) as OpenVpnTunnel
        try {
            val bindAddr = tunnel.localAddress().orEmpty()
            assertTrue("Expected 10.77.0.x tunnel address, got $bindAddr", bindAddr.startsWith("10.77.0."))
            assertFalse("Live tunnel must not report isDead", tunnel.isDead)

            // 1. Pushed DNS resolution (echo.vpn.internal -> 10.77.0.1) + TCP echo via dial()
            val tcpConn = tunnel.dial("echo.vpn.internal", 7007, 5_000)
            try {
                assertEquals("10.77.0.1", tcpConn.remoteAddress)
                val payload = "hello-from-device-arm64".toByteArray(Charsets.UTF_8)
                tcpConn.outputStream.write(payload)
                tcpConn.outputStream.flush()
                val readBuf = ByteArray(payload.size)
                var off = 0
                while (off < readBuf.size) {
                    val n = tcpConn.inputStream.read(readBuf, off, readBuf.size - off)
                    assertTrue("Unexpected EOF from TCP echo server", n > 0)
                    off += n
                }
                assertEquals("hello-from-device-arm64", String(readBuf, Charsets.UTF_8))
            } finally {
                tcpConn.close()
            }

            // 2. UDP echo via listenUdp() (Mosh datagram socket path)
            val udpSock = checkNotNull(tunnel.listenUdp())
            try {
                val udpPayload = "mosh-datagram-from-device".toByteArray(Charsets.UTF_8)
                udpSock.send(udpPayload, "10.77.0.1", 8008)
                val rxBuf = ByteArray(2048)
                val rxPkt = udpSock.receive(rxBuf, 5_000)
                assertNotNull("Timed out waiting for UDP echo over OpenVPN", rxPkt)
                assertEquals("mosh-datagram-from-device", String(rxBuf, 0, rxPkt!!.length, Charsets.UTF_8))
                assertEquals("10.77.0.1", rxPkt.srcHost)
                assertEquals(8008, rxPkt.srcPort)

                // 3. Inbound TCP via listenTcp(9009)
                val listener = checkNotNull(tunnel.listenTcp(9009))
                try {
                    val acceptedFuture = CompletableFuture<String>()
                    Thread {
                        try {
                            val inbound = listener.accept()
                            try {
                                val buf = ByteArray(19)
                                var r = 0
                                while (r < buf.size) {
                                    val n = inbound.inputStream.read(buf, r, buf.size - r)
                                    if (n <= 0) break
                                    r += n
                                }
                                acceptedFuture.complete(String(buf, 0, r, Charsets.UTF_8))
                            } finally {
                                inbound.close()
                            }
                        } catch (t: Throwable) {
                            acceptedFuture.completeExceptionally(t)
                        }
                    }.start()

                    // Ask the server on 10.77.0.1:8008 to dial back to our tunnel IP on TCP 9009
                    val trigger = "trigger-inbound:9009".toByteArray(Charsets.UTF_8)
                    udpSock.send(trigger, "10.77.0.1", 8008)
                    val inboundMsg = acceptedFuture.get(5, TimeUnit.SECONDS)
                    assertEquals("inbound-from-server", inboundMsg)
                } finally {
                    listener.close()
                }
            } finally {
                udpSock.close()
            }

            // 4. Local SOCKS5 listener
            val socksAddr = tunnel.socksAddress()
            assertNotNull("Expected valid local SOCKS5 address", socksAddr)
            assertTrue("Expected valid local SOCKS5 port, got ${socksAddr!!.port}", socksAddr.port in 1..65535)
        } finally {
            tunnel.close()
        }
    }
}
