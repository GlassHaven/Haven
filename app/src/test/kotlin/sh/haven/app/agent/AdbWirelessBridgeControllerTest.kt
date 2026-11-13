package sh.haven.app.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.mcp.McpError
import sh.haven.core.security.McpLocalTlsManager
import sh.haven.core.security.adb.AdbKeyPairStore
import sh.haven.core.security.adb.AdbPairingClient
import sh.haven.core.security.adb.AdbTlsBridge

class AdbWirelessBridgeControllerTest {

    private object IdentityProtector : McpLocalTlsManager.ByteProtector {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(cipher: ByteArray): ByteArray = cipher
    }

    private fun tempKeyStore(): AdbKeyPairStore =
        AdbKeyPairStore(Files.createTempDirectory("haven-adb-ctrl-test").toFile(), IdentityProtector)

    private fun testDiscovery(resolvedHost: String?, resolvedPort: Int): AdbPairingDiscovery {
        val nsd = object : AdbPairingDiscovery.Nsd {
            override fun discover(serviceType: String, listener: NsdManager.DiscoveryListener) {
                if (resolvedHost != null && resolvedPort > 0) {
                    val info = mockk<NsdServiceInfo>(relaxed = true) {
                        every { serviceName } returns "adb-tls-connect"
                        @Suppress("DEPRECATION")
                        every { host } returns InetAddress.getByName(resolvedHost)
                        every { port } returns resolvedPort
                    }
                    listener.onServiceFound(info)
                }
            }
            override fun stopDiscovery(listener: NsdManager.DiscoveryListener) = Unit
            override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
                listener.onServiceResolved(info)
            }
        }
        return AdbPairingDiscovery(nsd)
    }

    @Test
    fun `buildPairingCodeResponse pairs Haven key in-app by default`() = runTest {
        val store = tempKeyStore()
        val pairingClient = mockk<AdbPairingClient>()
        every {
            pairingClient.pair("192.168.0.155", 42217, "481920", store.adbPublicKeyLine(), any())
        } returns AdbPairingClient.PairingResult("adb-haven-guid")

        val controller = AdbWirelessBridgeController(
            context = mockk<Context>(relaxed = true),
            keyPairStoreOverride = store,
            pairingClientOverride = pairingClient,
            inAppPairingSupportedOverride = true,
        )

        val out = controller.buildPairingCodeResponse(
            code = "481920",
            endpoint = AdbPairingDiscovery.Endpoint("192.168.0.155", 42217),
            pairInApp = true,
            requestedPubKey = null,
        )

        assertTrue(out.getBoolean("pairedInApp"))
        assertEquals("adb-haven-guid", out.getString("guid"))
        assertEquals("haven-bridge", out.getString("pairedKeyTarget"))
        assertEquals("adb pair 192.168.0.155:42217 481920", out.getString("pairCommand"))
    }

    @Test
    fun `buildPairingCodeResponse leaves pairing port untouched when pairInApp is false`() = runTest {
        val store = tempKeyStore()
        val pairingClient = mockk<AdbPairingClient>(relaxed = true)
        val controller = AdbWirelessBridgeController(
            context = mockk<Context>(relaxed = true),
            keyPairStoreOverride = store,
            pairingClientOverride = pairingClient,
            inAppPairingSupportedOverride = true,
        )

        val out = controller.buildPairingCodeResponse(
            code = "112233",
            endpoint = AdbPairingDiscovery.Endpoint("192.168.0.155", 42217),
            pairInApp = false,
            requestedPubKey = null,
        )

        assertFalse(out.getBoolean("pairedInApp"))
        verify(exactly = 0) { pairingClient.pair(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `activateLocalAdbEndpoint falls back to wireless TLS bridge when Shizuku lacks root`() = runTest {
        val store = tempKeyStore()
        val bridge = AdbTlsBridge(
            keyPairStore = store,
            resolveConnectEndpoint = { AdbTlsBridge.ConnectEndpoint("192.168.0.155", 39235) },
        )
        val controller = AdbWirelessBridgeController(
            context = mockk<Context>(relaxed = true),
            keyPairStoreOverride = store,
            tlsBridgeOverride = bridge,
            inAppPairingSupportedOverride = true,
        ).apply {
            discoveryFactory = { testDiscovery("192.168.0.155", 39235) }
        }

        val freePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        try {
            val activation = controller.activateLocalAdbEndpoint(freePort) {
                throw McpError(-32603, "Shizuku shell mode cannot restart adbd.")
            }
            assertEquals("wireless-tls-bridge", activation.method)
            assertEquals("192.168.0.155:39235", activation.wirelessEndpoint)
            assertEquals(freePort, bridge.boundPort)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `activateLocalAdbEndpoint throws actionable McpError when both Shizuku and Wireless Debugging are off`() = runTest {
        val store = tempKeyStore()
        val controller = AdbWirelessBridgeController(
            context = mockk<Context>(relaxed = true),
            keyPairStoreOverride = store,
            inAppPairingSupportedOverride = true,
        ).apply {
            discoveryFactory = { testDiscovery(null, 0) }
        }

        try {
            controller.activateLocalAdbEndpoint(15555) {
                throw McpError(-32603, "Shizuku not running.")
            }
            fail("Expected McpError when neither Shizuku root nor _adb-tls-connect._tcp is available")
        } catch (err: McpError) {
            assertNotNull(err.message)
            assertTrue(err.message!!.contains("_adb-tls-connect._tcp"))
        }
    }
}
