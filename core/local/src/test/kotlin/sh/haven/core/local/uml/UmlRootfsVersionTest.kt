package sh.haven.core.local.uml

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression for the on-device launch crash (2026-09-14): stagedVersion()
 * called useLines on the marker file without an existence check, so every
 * install with a staged v1 rootfs (512 MiB present, no marker file) threw
 * FileNotFoundException at DI time and the app died on launch.
 */
class UmlRootfsVersionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `absent marker file reads as v1 instead of throwing`() {
        // The crash condition: image staged, marker never written.
        val marker = File(tmp.root, "uml/rootfs.version")
        assertEquals(1, stagedVersionAt(marker))
    }

    @Test
    fun `marker v2 parses to 2`() {
        val marker = tmp.newFile("rootfs.version")
        marker.writeText("2\n")
        assertEquals(2, stagedVersionAt(marker))
    }

    @Test
    fun `unparseable marker falls back to v1`() {
        val marker = tmp.newFile("rootfs.version")
        marker.writeText("garbage\n")
        assertEquals(1, stagedVersionAt(marker))
    }

    @Test
    fun `empty marker file falls back to v1`() {
        val marker = tmp.newFile("rootfs.version")
        marker.writeText("")
        assertEquals(1, stagedVersionAt(marker))
    }
}