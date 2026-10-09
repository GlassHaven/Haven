package sh.haven.core.local.uml

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The x11 start branch against the stage-2 device run: the app's own Start
 * produced a black VNC screen with a live cursor — xterm was alive but
 * `Map State: IsUnMapped`, because the recipe launched it in the same
 * breath as openbox and it mapped before the window manager grabbed the
 * WM. Killing and relaunching xterm once openbox was up gave a managed,
 * viewable window, so the fix is ordering: wait for openbox to set
 * `_NET_SUPPORTING_WM_CHECK` on the root before launching xterm.
 */
class UmlDesktopRecipeOrderTest {

    private val script = UmlDesktopManager.RECIPE_SCRIPT

    @Test
    fun xtermLaunchesOnlyAfterTheWmReadyWait() {
        val openbox = script.indexOf("openbox >>")
        val wmWait = script.indexOf("_NET_SUPPORTING_WM_CHECK")
        val xterm = script.indexOf("xterm >>")
        assertTrue("openbox launch missing", openbox >= 0)
        assertTrue("xterm launch missing", xterm >= 0)
        assertTrue("WM-ready wait missing", wmWait >= 0)
        assertTrue("wait must come after openbox starts", openbox < wmWait)
        assertTrue("xterm must come after the WM-ready wait", wmWait < xterm)
    }

    @Test
    fun wmReadyWaitIsBounded() {
        // A wait that can hang would leave the console mid-script and the
        // HDESKTOP:started marker never printed — same failure mode as the
        // bug, so the loop must be bounded like the wayland socket wait.
        val x11 = script.substringAfter("start)").substringBefore("wayland)")
        val wait = x11.substringAfter("_NET_SUPPORTING_WM_CHECK")
        assertTrue("wait loop must have a bounded counter", Regex("n -lt \\d+").containsMatchIn(x11))
        assertTrue("wait must sleep between polls", wait.contains("sleep 1"))
    }

    @Test
    fun xvncStartsWithTheBlacklistDisabled() {
        // Device run: every connect answered "Too many security failures".
        // TigerVNC blacklists a source IP after repeated failed handshakes,
        // but with SecurityTypes None there is no credential to guess — the
        // only client that can ever trip it is the legitimate one. Every
        // host-side connection (Haven's VNC tab included) arrives through
        // the passt tap as the single address 169.254.2.2, so one burst of
        // aborted handshakes locks the user out of their own desktop until
        // Xvnc restarts. The launch must disable the blacklist.
        val xvnc = script.lineSequence().first { it.trimStart().startsWith("Xvnc ") }
        assertTrue("Xvnc must disable the security-failure blacklist", xvnc.contains("-BlacklistTimeout 0"))
    }

    @Test
    fun xpropIsInstalledWithTheX11Set() {
        // The wait uses xprop; if it isn't in the install set the grep can
        // never match and xterm again maps unmanaged.
        val install = script.substringAfter("x11_pkgs()").substringBefore("wayland_pkgs()")
        assertTrue("xprop missing from x11_pkgs", install.contains("xprop"))
    }
}
