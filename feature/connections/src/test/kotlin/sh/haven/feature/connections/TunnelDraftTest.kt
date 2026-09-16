package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SSH-tunnel editor behaviour shared by the VNC / RDP / SPICE /
 * SMB rows in ConnectionEditDialog — extracted verbatim from the four
 * previously-duplicated inline blocks.
 */
class TunnelDraftTest {

    // --- initial toggle state (strict guard used by VNC and SPICE) ---

    @Test fun strictInitRequiresMatchingTypeFlagAndCarrier() {
        assertTrue(strictTunnelInitialEnabled("VNC", "VNC", true, "ssh-1"))
    }

    @Test fun strictInitFalseWhenStoredTypeDiffers() {
        // A profile switched from RDP can carry a stale flag for the old type.
        assertFalse(strictTunnelInitialEnabled("RDP", "VNC", true, "ssh-1"))
    }

    @Test fun strictInitFalseWithoutCarrier() {
        // vncSshForward historically defaulted true; without a carrier the
        // toggle must start off rather than blocking Save on a hidden field.
        assertFalse(strictTunnelInitialEnabled("VNC", "VNC", true, null))
    }

    @Test fun strictInitFalseWhenFlagCleared() {
        assertFalse(strictTunnelInitialEnabled("VNC", "VNC", false, "ssh-1"))
    }

    // --- host transitions on toggle ---

    @Test fun enablingDefaultsBlankHostToIpv4Loopback() {
        assertEquals("127.0.0.1", tunnelHostOnToggle(true, ""))
        assertEquals("127.0.0.1", tunnelHostOnToggle(true, "  "))
    }

    @Test fun enablingRewritesLocalhostToIpv4Loopback() {
        // Not "localhost": remote sshd may resolve it to ::1 while the
        // server listens on IPv4 only (v5.24.14).
        assertEquals("127.0.0.1", tunnelHostOnToggle(true, "localhost"))
    }

    @Test fun enablingKeepsCustomHost() {
        assertEquals("192.168.1.100", tunnelHostOnToggle(true, "192.168.1.100"))
    }

    @Test fun disablingClearsOnlyLoopbackHosts() {
        assertEquals("", tunnelHostOnToggle(false, "127.0.0.1"))
        assertEquals("", tunnelHostOnToggle(false, "localhost"))
        assertEquals("192.168.1.100", tunnelHostOnToggle(false, "192.168.1.100"))
    }

    // --- save gating and normalisation ---

    @Test fun enabledTunnelWithoutCarrierBlocksSave() {
        // This is what keeps the RDP/SMB raw-flag initialisation safe: a
        // stored flag with a missing carrier forces a dropdown choice.
        assertFalse(tunnelComplete(enabled = true, carrierId = null))
        assertTrue(tunnelComplete(enabled = true, carrierId = "ssh-1"))
        assertTrue(tunnelComplete(enabled = false, carrierId = null))
    }

    @Test fun disabledTunnelNeverPersistsACarrier() {
        assertNull(tunnelCarrierForSave(enabled = false, carrierId = "ssh-1"))
        assertEquals("ssh-1", tunnelCarrierForSave(enabled = true, carrierId = "ssh-1"))
        assertNull(tunnelCarrierForSave(enabled = true, carrierId = null))
    }

    // --- Route-through picker persistence (#527) ---

    private fun profile() = sh.haven.core.data.db.entities.ConnectionProfile(
        label = "rdp", host = "10.0.0.5", username = "u",
    )

    @Test fun routingSelectionPersistsAPickedTunnel() {
        // #527: a WireGuard tunnel picked in the RDP editor reverted to
        // "None (direct)" on save — the save branch never wrote the field.
        val saved = profile().withRoutingSelection(
            proxyType = null, proxyHost = "", proxyPort = "1080",
            proxyUser = "", proxyPassword = "", tunnelConfigId = "wg-home",
        )
        assertEquals("wg-home", saved.tunnelConfigId)
        assertNull(saved.proxyType)
    }

    @Test fun routingSelectionPersistsAPickedProxy() {
        val saved = profile().withRoutingSelection(
            proxyType = "SOCKS5", proxyHost = "proxy.lan", proxyPort = "9050",
            proxyUser = "u", proxyPassword = "p", tunnelConfigId = null,
        )
        assertEquals("SOCKS5", saved.proxyType)
        assertEquals("proxy.lan", saved.proxyHost)
        assertEquals(9050, saved.proxyPort)
        assertEquals("u", saved.proxyUser)
        assertEquals("p", saved.proxyPassword)
        assertNull(saved.tunnelConfigId)
    }

    @Test fun routingSelectionNoneClearsStaleProxyFields() {
        // Picking "None (direct)" must not leave orphaned credentials from a
        // previous proxy choice behind on the profile.
        val saved = profile().copy(
            proxyType = "SOCKS5", proxyHost = "old.lan", proxyUser = "u", proxyPassword = "p",
        ).withRoutingSelection(
            proxyType = null, proxyHost = "old.lan", proxyPort = "1080",
            proxyUser = "u", proxyPassword = "p", tunnelConfigId = null,
        )
        assertNull(saved.proxyType)
        assertNull(saved.proxyHost)
        assertNull(saved.proxyUser)
        assertNull(saved.proxyPassword)
    }

    @Test fun routingSelectionSocks4NeverKeepsAPassword() {
        // SOCKS4 has no password field on the wire; mirrors the SSH branch.
        val saved = profile().withRoutingSelection(
            proxyType = "SOCKS4", proxyHost = "proxy.lan", proxyPort = "1080",
            proxyUser = "u", proxyPassword = "p", tunnelConfigId = null,
        )
        assertEquals("u", saved.proxyUser)
        assertNull(saved.proxyPassword)
    }

    @Test fun routingSelectionBadPortFallsBackToDefault() {
        assertEquals(
            1080,
            profile().withRoutingSelection(
                proxyType = "SOCKS5", proxyHost = "h", proxyPort = "not-a-port",
                proxyUser = "", proxyPassword = "", tunnelConfigId = null,
            ).proxyPort,
        )
    }

    // AI route carrier (OPENAI counterpart of the desktop tunnel rows).

    @Test fun aiRouteTypeSaveNoneMeansUnrouted() {
        assertNull(aiRouteTypeForSave("NONE"))
        assertEquals("SSH", aiRouteTypeForSave("SSH"))
        assertEquals("RETICULUM", aiRouteTypeForSave("RETICULUM"))
    }

    @Test fun aiRouteInitialModeReadsCoherentPair() {
        assertEquals("SSH", aiRouteInitialMode("SSH", "carrier"))
        assertEquals("RETICULUM", aiRouteInitialMode("RETICULUM", "carrier"))
    }

    @Test fun aiRouteInitialModeDropsStaleRows() {
        // Type without a carrier — reads Direct, stale half dropped on save.
        assertEquals("NONE", aiRouteInitialMode("SSH", null))
        // Carrier without a recognised type — same.
        assertEquals("NONE", aiRouteInitialMode(null, "carrier"))
        assertEquals("NONE", aiRouteInitialMode("BOGUS", "carrier"))
    }

    @Test fun aiRouteCompleteBlocksModeWithoutCarrier() {
        assertTrue(aiRouteComplete("NONE", null))
        assertTrue(aiRouteComplete("SSH", "carrier"))
        assertFalse(aiRouteComplete("SSH", null))
        assertFalse(aiRouteComplete("RETICULUM", null))
    }

    @Test fun aiRouteCarrierForSaveDropsCarrierOnNone() {
        assertNull(aiRouteCarrierForSave("NONE", "carrier"))
        assertEquals("carrier", aiRouteCarrierForSave("SSH", "carrier"))
    }
}
