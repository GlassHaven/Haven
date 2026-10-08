package sh.haven.core.local.uml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `atPrompt` against the console tails the stage-2 device run actually
 * produced. The first run failed "no console prompt within 45s" even
 * though the guest had booted: busybox ash trails its prompt with a DSR
 * cursor query (ESC [ 6 n) the headless pty never answers, so the raw
 * tail ends in `6n` forever and a plain endsWith("#") never fires.
 */
class UmlDesktopPromptTest {

    @Test
    fun promptWithUnansweredCursorQueryIsDetected() {
        val tail = "UML rootfs ready\r\n/bin/ash: can't access tty; " +
            "job control turned off\r\numl:~# \u001b[6n"
        assertTrue(UmlDesktopManager.atPrompt(tail))
    }

    @Test
    fun plainPromptIsDetected() {
        assertTrue(UmlDesktopManager.atPrompt("uml:~# "))
    }

    @Test
    fun bootNoiseIsNotAPrompt() {
        assertFalse(UmlDesktopManager.atPrompt("EXT4-fs (ubda): recovery complete\n"))
        assertFalse(UmlDesktopManager.atPrompt("VFS: Mounted root (ext4 filesystem) on device 98:0."))
    }

    @Test
    fun recipeOutputIsNotAPrompt() {
        assertFalse(UmlDesktopManager.atPrompt("HDESKTOP:done\n"))
        assertFalse(UmlDesktopManager.atPrompt("( 5%|██▌ | 1.2MB/s eta 0:01:04"))
    }

    @Test
    fun otherEscapesAreStripped() {
        assertTrue(UmlDesktopManager.atPrompt("uml:~# \u001b[1m\u001b[31m\u001b[6n"))
        assertTrue(UmlDesktopManager.atPrompt("uml:~# \u001b]0;title\u0007"))
    }
}