package sh.haven.core.local.uml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/**
 * `recipeLine` against the stage-2 device failure: the guest reached the
 * prompt, then `sh: can't open '/host/haven-desktop.sh': No such file or
 * directory` — the image's inittab mounts the share with a `&&`/`||` chain
 * busybox init runs without a shell, so /host was never mounted. The line
 * must mount the share itself and, when the recipe still isn't there, emit
 * an HDESKTOP: marker at line start so the marker wait fails fast instead
 * of burning its whole budget on a shell error that is not a marker.
 */
class UmlDesktopRecipeLineTest {

    @Test
    fun mountsShareBeforeRunningRecipe() {
        val line = UmlDesktopManager.recipeLine("install x11")
        val mount = line.indexOf("mount -t hostfs none /host")
        val run = line.indexOf("sh /host/haven-desktop.sh install x11")
        assertTrue("mount missing", mount >= 0)
        assertTrue("recipe run missing", run >= 0)
        assertTrue("mount must precede the recipe", mount < run)
    }

    @Test
    fun missingShareEmitsFailMarker() {
        val line = UmlDesktopManager.recipeLine("install x11")
        val marker = line.indexOf("HDESKTOP:fail")
        assertTrue("no fail marker", marker >= 0)
        assertTrue("fail marker must precede the recipe run", marker < line.indexOf("sh /host/"))
    }

    @Test
    fun submitsWithCarriageReturn() {
        assertTrue(UmlDesktopManager.recipeLine("start x11 5901").endsWith("\r"))
    }

    @Test
    fun failMarkerIsFoundByFirstMarker() {
        // The console ring holds the echoed command line and then the
        // shell's output; firstMarker only counts a marker at line start,
        // so the fail echo must be reachable with the typed command above it.
        val ring = UmlDesktopManager.ConsoleLog()
        val text = "uml:~# ${UmlDesktopManager.recipeLine("install x11")}" +
            "HDESKTOP:fail /host share not mounted\r\n"
        val b = text.toByteArray()
        ring.onBytes(b, 0, b.size)
        assertEquals("HDESKTOP:fail /host share not mounted", ring.firstMarker("HDESKTOP:"))
    }

    @Test
    fun echoedCommandAloneIsNotAMarker() {
        // The typed recipe line itself carries the fail string mid-line;
        // firstMarker must not treat it as the shell having failed.
        val ring = UmlDesktopManager.ConsoleLog()
        val b = ("uml:~# " + UmlDesktopManager.recipeLine("install x11")).toByteArray()
        ring.onBytes(b, 0, b.size)
        assertNull(ring.firstMarker("HDESKTOP:"))
    }
}