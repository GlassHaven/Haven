package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #510 — the terminal build ships no RDP or SPICE client, so offering them
 * would create a profile that can only fail at connect with "native library
 * failed to load".
 */
class TransportAvailabilityTest {

    private val all = listOf(
        "SSH" to "SSH",
        "MOSH" to "Mosh",
        "LOCAL" to "Local Shell (PRoot)",
        "GUEST" to "Linux Guest (UML)",
        "VNC" to "VNC (Desktop)",
        "RDP" to "RDP (Desktop)",
        "SPICE" to "SPICE (Desktop)",
        "SMB" to "SMB (File Share)",
        "RCLONE" to "Cloud Storage (rclone)",
    )

    private fun values(
        rdp: Boolean,
        spice: Boolean,
        rclone: Boolean = true,
        uml: Boolean = true,
    ) = TransportAvailability.offered(all, rdp, spice, rclone, uml).map { it.first }

    @Test
    fun `a full build offers everything`() {
        assertEquals(all.map { it.first }, values(rdp = true, spice = true))
    }

    @Test
    fun `a build without the native clients drops RDP and SPICE`() {
        val offered = values(rdp = false, spice = false)

        assertFalse("RDP", "RDP" in offered)
        assertFalse("SPICE", "SPICE" in offered)
    }

    /**
     * The gate must remove ONLY those two. Filtering by "is it a desktop
     * type" would take VNC with it, and VNC's client is Kotlin — it works in
     * every build, including against a guest desktop the terminal build can
     * still run over X11Vnc.
     */
    @Test
    fun `nothing else is affected, VNC included`() {
        val offered = values(rdp = false, spice = false)

        assertEquals(
            listOf("SSH", "MOSH", "LOCAL", "GUEST", "VNC", "SMB", "RCLONE"),
            offered,
        )
        assertTrue("VNC must survive", "VNC" in offered)
    }

    @Test
    fun `the two transports are gated independently`() {
        assertTrue("RDP" in values(rdp = true, spice = false))
        assertFalse("SPICE" in values(rdp = true, spice = false))
        assertFalse("RDP" in values(rdp = false, spice = true))
        assertTrue("SPICE" in values(rdp = false, spice = true))
    }

    /** Order is the caller's; filtering must not reshuffle the menu. */
    @Test
    fun `order is preserved`() {
        assertEquals(
            listOf("SSH", "MOSH", "LOCAL", "GUEST", "VNC", "RDP", "SPICE", "SMB", "RCLONE"),
            values(rdp = true, spice = true),
        )
    }

    /**
     * Like RDP/SPICE, the guest is gated on a missing file (libvmlinux.so) —
     * the terminal flavour drops the kernel and F-Droid builds skip the fetch.
     */
    @Test
    fun `a build without the UML payload drops the guest transport`() {
        val offered = values(rdp = true, spice = true, uml = false)

        assertFalse("GUEST", "GUEST" in offered)
        assertTrue("LOCAL should survive", "LOCAL" in offered)
        assertTrue("SSH should survive", "SSH" in offered)
    }

    /**
     * rclone is gated on a probe rather than a missing file: the terminal
     * build ships libgojni.so, just built without the rclone package, so
     * looking for the library would wrongly report it present.
     */
    @Test
    fun `a build whose Go library omits rclone drops the rclone transport`() {
        val offered = values(rdp = true, spice = true, rclone = false)

        assertFalse("RCLONE", "RCLONE" in offered)
        assertTrue("SMB should survive", "SMB" in offered)
        assertTrue("RDP should survive", "RDP" in offered)
    }
}
