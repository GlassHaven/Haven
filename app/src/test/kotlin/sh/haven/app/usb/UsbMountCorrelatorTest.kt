package sh.haven.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [UsbMountMatcher] — the matching core of the #603
 * USB mount-route correlator (no Android framework involved).
 */
class UsbMountCorrelatorTest {

    private fun vol(key: String, path: String = "/storage/$key") =
        UsbVolumeSnapshot(key = key, path = path, description = "Volume $key", readOnly = false)

    @Test
    fun `attach-diff matches the single volume that appeared after attach`() {
        val baseline = setOf("MICROSD")
        val current = listOf(vol("MICROSD"), vol("STICK1"))
        val match = UsbMountMatcher.match(deviceCount = 1, baselineKeys = baseline, current = current, claimedKeys = emptySet())
        assertNotNull(match)
        assertEquals("STICK1", match!!.volume.key)
        assertEquals(UsbMountConfidence.ATTACH_DIFF, match.confidence)
    }

    @Test
    fun `no new volume since attach means no match`() {
        val baseline = setOf("STICK1")
        val current = listOf(vol("STICK1"))
        assertNull(UsbMountMatcher.match(deviceCount = 1, baselineKeys = baseline, current = current, claimedKeys = emptySet()))
    }

    @Test
    fun `two volumes appearing after attach is ambiguous and refused`() {
        val baseline = emptySet<String>()
        val current = listOf(vol("STICK1"), vol("STICK2"))
        assertNull(UsbMountMatcher.match(deviceCount = 2, baselineKeys = baseline, current = current, claimedKeys = emptySet()))
    }

    @Test
    fun `weak unclaimed-volume match when one device and one session-new volume`() {
        // Device attached before app start (no baseline), volume mounted after
        // app start, no other candidates.
        val match = UsbMountMatcher.match(
            deviceCount = 1,
            baselineKeys = null,
            current = listOf(vol("STICK1")),
            claimedKeys = emptySet(),
            preexistingKeys = emptySet(),
        )
        assertNotNull(match)
        assertEquals("STICK1", match!!.volume.key)
        assertEquals(UsbMountConfidence.UNCLAIMED_VOLUME, match.confidence)
    }

    @Test
    fun `weak match refuses a volume that existed at app start (microSD must not claim the stick)`() {
        // ext4 stick not mounted by Android; the only mounted removable is a
        // long-standing microSD. Without the preexisting filter this would
        // falsely weak-match the microSD.
        val match = UsbMountMatcher.match(
            deviceCount = 1,
            baselineKeys = null,
            current = listOf(vol("MICROSD")),
            claimedKeys = emptySet(),
            preexistingKeys = setOf("MICROSD"),
        )
        assertNull(match)
    }

    @Test
    fun `weak match refuses two session-new volumes`() {
        val match = UsbMountMatcher.match(
            deviceCount = 1,
            baselineKeys = null,
            current = listOf(vol("STICK1"), vol("STICK2")),
            claimedKeys = emptySet(),
            preexistingKeys = emptySet(),
        )
        assertNull(match)
    }

    @Test
    fun `weak match requires exactly one attached device`() {
        assertNull(
            UsbMountMatcher.match(
                deviceCount = 2,
                baselineKeys = null,
                current = listOf(vol("STICK1")),
                claimedKeys = emptySet(),
                preexistingKeys = emptySet(),
            ),
        )
    }

    @Test
    fun `a claimed volume is subtracted from the other device's candidates`() {
        val current = listOf(vol("STICK1"), vol("STICK2"))
        val claimed = setOf("STICK1")
        // Device B has a baseline that missed both mounts (attach raced), so
        // the strong path finds nothing new…
        assertNull(UsbMountMatcher.match(deviceCount = 1, baselineKeys = setOf("STICK1", "STICK2"), current = current, claimedKeys = claimed))
        // …and the weak path (both devices attached pre-app-start) is refused
        // because after claim subtraction nothing unclaimed remains for B.
        assertNull(UsbMountMatcher.match(deviceCount = 1, baselineKeys = null, current = current, claimedKeys = claimed, preexistingKeys = setOf("STICK1", "STICK2")))
        // But B's own session-new volume is still matchable around the claim.
        val b = UsbMountMatcher.match(deviceCount = 1, baselineKeys = null, current = current, claimedKeys = claimed, preexistingKeys = setOf("STICK1"))
        assertNotNull(b)
        assertEquals("STICK2", b!!.volume.key)
    }

    @Test
    fun `attach-diff ignores a volume already claimed by another device`() {
        val current = listOf(vol("STICK2"))
        val match = UsbMountMatcher.match(
            deviceCount = 1,
            baselineKeys = emptySet(),
            current = current,
            claimedKeys = setOf("STICK2"),
        )
        assertNull(match)
    }

    @Test
    fun `re-matching the claiming device still finds its own claimed volume`() {
        // Regression: the caller builds claimedKeys from all claims including
        // the device's own. awaitMatch claims the volume, then a later
        // currentMatch for the SAME device (e.g. list_usb_drives snapshot)
        // must still return it — only other devices' claims are subtracted.
        val current = listOf(vol("STICK1"))
        val first = UsbMountMatcher.match(deviceCount = 1, baselineKeys = emptySet(), current = current, claimedKeys = emptySet())
        assertNotNull(first)
        val rematch = UsbMountMatcher.match(deviceCount = 1, baselineKeys = emptySet(), current = current, claimedKeys = setOf("STICK1"))
        assertNull(rematch) // if the caller passes its own claim — the old bug
        val ownExcluded = UsbMountMatcher.match(deviceCount = 1, baselineKeys = emptySet(), current = current, claimedKeys = emptySet())
        assertNotNull(ownExcluded) // caller excludes own claim → match again
    }

    @Test
    fun `baseline recorded after the mount already landed falls to the weak path`() {
        // The attach broadcast raced vold: the baseline already contains the
        // stick's volume, so no attach-diff. With one device and the volume
        // being session-new, the weak path still finds it.
        val current = listOf(vol("STICK1"))
        assertNull(UsbMountMatcher.match(deviceCount = 1, baselineKeys = setOf("STICK1"), current = current, claimedKeys = emptySet()))
        val weak = UsbMountMatcher.match(deviceCount = 1, baselineKeys = null, current = current, claimedKeys = emptySet(), preexistingKeys = emptySet())
        assertNotNull(weak)
        assertEquals(UsbMountConfidence.UNCLAIMED_VOLUME, weak!!.confidence)
    }

    @Test
    fun `read-only volume key and fields survive the snapshot round trip`() {
        val v = UsbVolumeSnapshot(key = "ABCD-1234", path = "/storage/ABCD-1234", description = "SanDisk", readOnly = true)
        assertEquals("ABCD-1234", v.key)
        assertTrue(v.readOnly)
        assertEquals("SanDisk", v.description)
    }
}