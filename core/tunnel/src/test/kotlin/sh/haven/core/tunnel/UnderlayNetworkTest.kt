package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UnderlayNetworkTest {

    @Test
    fun `preference parses case-insensitively and rejects unknown values in strict mode`() {
        assertEquals(UnderlayNetworkPreference.DEFAULT, UnderlayNetworkPreference.fromStorage(null))
        assertEquals(UnderlayNetworkPreference.DEFAULT, UnderlayNetworkPreference.fromStorage("  "))
        assertEquals(UnderlayNetworkPreference.WIFI, UnderlayNetworkPreference.fromStorage("wifi"))
        assertEquals(UnderlayNetworkPreference.CELLULAR, UnderlayNetworkPreference.fromStorage(" CELLULAR "))
        assertEquals(UnderlayNetworkPreference.ETHERNET, UnderlayNetworkPreference.fromStorage("Ethernet"))
        assertEquals(UnderlayNetworkPreference.DEFAULT, UnderlayNetworkPreference.fromStorage("bogus"))

        assertEquals(UnderlayNetworkPreference.DEFAULT, UnderlayNetworkPreference.parseStrict(""))
        assertEquals(UnderlayNetworkPreference.DEFAULT, UnderlayNetworkPreference.parseStrict("default"))
        assertEquals(UnderlayNetworkPreference.WIFI, UnderlayNetworkPreference.parseStrict("wi-fi"))
        assertEquals(UnderlayNetworkPreference.WIFI, UnderlayNetworkPreference.parseStrict("WIFI"))
        assertNull(UnderlayNetworkPreference.parseStrict("bluetooth"))
    }

    @Test
    fun `WireguardUnderlayConfig round-trips directive cleanly and strips when reset to default`() {
        val cleanIni = """
            [Interface]
            PrivateKey = ABC=
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = DEF=
            Endpoint = 192.168.1.1:51820
        """.trimIndent()

        assertEquals(UnderlayNetworkPreference.DEFAULT, WireguardUnderlayConfig.extract(cleanIni))
        assertEquals(cleanIni, WireguardUnderlayConfig.withPreference(cleanIni, UnderlayNetworkPreference.DEFAULT))

        val withWifi = WireguardUnderlayConfig.withPreference(cleanIni, UnderlayNetworkPreference.WIFI)
        assertTrue(withWifi.startsWith("# haven-underlay-network: WIFI\n"))
        assertEquals(UnderlayNetworkPreference.WIFI, WireguardUnderlayConfig.extract(withWifi))
        assertEquals(cleanIni, WireguardUnderlayConfig.stripDirective(withWifi))

        // Updating WIFI -> ETHERNET replaces rather than duplicating the comment line.
        val withEth = WireguardUnderlayConfig.withPreference(withWifi, UnderlayNetworkPreference.ETHERNET)
        assertEquals(UnderlayNetworkPreference.ETHERNET, WireguardUnderlayConfig.extract(withEth))
        assertEquals(1, Regex("haven-underlay-network", RegexOption.IGNORE_CASE).findAll(withEth).count())

        // Resetting to DEFAULT removes the directive completely.
        val backToDefault = WireguardUnderlayConfig.withPreference(withEth, UnderlayNetworkPreference.DEFAULT)
        assertEquals(cleanIni, backToDefault)
        assertFalse(backToDefault.contains("haven-underlay-network"))
    }

    @Test
    fun `selectHandle picks LAN-only Wi-Fi even when unvalidated and Cellular is active default route`() {
        val lanWifi = CandidateUnderlayNetwork(
            handle = 430_000_000_001L,
            isNotVpn = true,
            isValidated = false, // LAN-only AP without internet — exact #876 Option B scenario
            hasInternet = false,
            hasWifi = true,
            hasCellular = false,
            hasEthernet = false,
        )
        val activeCellular = CandidateUnderlayNetwork(
            handle = 430_000_000_002L,
            isNotVpn = true,
            isValidated = true,
            hasInternet = true,
            hasWifi = false,
            hasCellular = true,
            hasEthernet = false,
        )
        val vpnOverlay = CandidateUnderlayNetwork(
            handle = 430_000_000_003L,
            isNotVpn = false,
            isValidated = true,
            hasInternet = true,
            hasWifi = true,
            hasCellular = false,
            hasEthernet = false,
        )

        val candidates = listOf(vpnOverlay, activeCellular, lanWifi)
        assertEquals(
            430_000_000_001L,
            AndroidUnderlayNetworkResolver.selectHandle(
                UnderlayNetworkPreference.WIFI,
                activeHandle = activeCellular.handle,
                candidates = candidates,
            ),
        )
        assertEquals(
            430_000_000_002L,
            AndroidUnderlayNetworkResolver.selectHandle(
                UnderlayNetworkPreference.CELLULAR,
                activeHandle = activeCellular.handle,
                candidates = candidates,
            ),
        )
        assertEquals(
            0L,
            AndroidUnderlayNetworkResolver.selectHandle(
                UnderlayNetworkPreference.ETHERNET,
                activeHandle = activeCellular.handle,
                candidates = candidates,
            ),
        )
    }

    @Test
    fun `selectHandle ignores VPN networks masquerading with transport flags`() {
        val vpnWifiOnly = listOf(
            CandidateUnderlayNetwork(
                handle = 999L,
                isNotVpn = false,
                isValidated = true,
                hasInternet = true,
                hasWifi = true,
                hasCellular = false,
                hasEthernet = false,
            ),
        )
        assertEquals(
            0L,
            AndroidUnderlayNetworkResolver.selectHandle(
                UnderlayNetworkPreference.WIFI,
                activeHandle = 999L,
                candidates = vpnWifiOnly,
            ),
        )
    }

    @Test
    fun `resolver throws actionable IOException when requested transport is disconnected`() {
        var candidates = emptyList<CandidateUnderlayNetwork>()
        val resolver = AndroidUnderlayNetworkResolver(
            context = null,
            snapshotProvider = { 500L to candidates },
        )
        assertEquals(0L, resolver.resolveRequiredHandle(UnderlayNetworkPreference.DEFAULT, "Home LAN"))
        assertEquals(500L, resolver.currentNetworkKey(UnderlayNetworkPreference.DEFAULT))

        val ex = assertThrows(IOException::class.java) {
            resolver.resolveRequiredHandle(UnderlayNetworkPreference.WIFI, "Home LAN")
        }
        assertTrue(ex.message!!.contains("Home LAN"))
        assertTrue(ex.message!!.contains("Wi-Fi is not connected"))

        candidates = listOf(
            CandidateUnderlayNetwork(
                handle = 777L,
                isNotVpn = true,
                isValidated = false,
                hasInternet = false,
                hasWifi = true,
                hasCellular = false,
                hasEthernet = false,
            ),
        )
        assertEquals(777L, resolver.resolveRequiredHandle(UnderlayNetworkPreference.WIFI, "Home LAN"))
        assertEquals(777L, resolver.currentNetworkKey(UnderlayNetworkPreference.WIFI))
    }
}
