package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shell script Haven assembles for PRoot guest launches must parse.
 *
 * `sh -n` parses without executing, catching quote/control-flow syntax errors
 * that string-contains assertions miss. (Host-side `/proc` scanning moved to
 * pure Kotlin in #893 and is covered in [XvncScanEndToEndTest].)
 */
class GeneratedShellSyntaxTest {

    private fun parses(script: String): Boolean =
        ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start().waitFor() == 0

    /**
     * Guards the test itself: `sh -n` has to actually reject something, or
     * every assertion below passes for the wrong reason.
     */
    @Test
    fun `sh -n rejects a script that does not parse`() {
        assertTrue("unbalanced quote must be rejected", !parses("echo \"unterminated"))
        assertTrue("dangling then must be rejected", !parses("if true; then"))
    }

    @Test
    fun `isSimpleMozillaBrowserCommand detects bare and wrapped firefox commands unless profile is explicit`() {
        assertTrue(isSimpleMozillaBrowserCommand("firefox"))
        assertTrue(isSimpleMozillaBrowserCommand("/usr/bin/firefox-esr https://example.com"))
        assertTrue(isSimpleMozillaBrowserCommand("env GDK_BACKEND=wayland firefox"))
        assertTrue(isSimpleMozillaBrowserCommand("fakeroot-tcp librewolf"))
        assertTrue(!isSimpleMozillaBrowserCommand("firefox --profile /tmp/custom"))
        assertTrue(!isSimpleMozillaBrowserCommand("firefox -P dev"))
        assertTrue(!isSimpleMozillaBrowserCommand("mkdir -p /tmp/x && firefox"))
        assertTrue(!isSimpleMozillaBrowserCommand("galculator"))
    }

    @Test
    fun `buildProotKioskRunScript parses under sh -n for mozilla and non-mozilla commands`() {
        val mozScript = buildProotKioskRunScript("env GDK_BACKEND=wayland firefox", 2)
        assertTrue("mozilla kiosk script rejected by sh -n:\n$mozScript", parses(mozScript))
        assertTrue(mozScript.contains("--no-remote --profile"))
        assertTrue(mozScript.contains(".parentlock"))

        val plainScript = buildProotKioskRunScript("galculator", 2)
        assertTrue("plain kiosk script rejected by sh -n:\n$plainScript", parses(plainScript))
        assertEquals("galculator\nswaymsg exit\n", plainScript)
    }

    @Test
    fun `resolveNestedWaylandUser honours configured guest user and avoids USER=root fallback (#827)`() {
        val fromRoot = resolveNestedWaylandUser(ProotManager.GuestUserSpec.ROOT)
        assertEquals("haven", fromRoot.username)
        assertEquals(1000, fromRoot.uid)
        assertEquals(1000, fromRoot.gid)
        assertEquals("/root", fromRoot.home)

        val configured = ProotManager.GuestUserSpec(
            username = "nazy",
            uid = 1000,
            gid = 1000,
            home = "/home/nazy",
        )
        val fromConfigured = resolveNestedWaylandUser(configured)
        assertEquals(configured, fromConfigured)
    }

    @Test
    fun `resolveAppWindowExecCommand never wraps Mozilla browsers in fakeroot (#827)`() {
        assertEquals("fakeroot-tcp synaptic", resolveAppWindowExecCommand("synaptic", runAsRoot = true))
        assertEquals("firefox", resolveAppWindowExecCommand("firefox", runAsRoot = true))
        assertEquals(
            "env GDK_BACKEND=wayland firefox https://example.com",
            resolveAppWindowExecCommand("env GDK_BACKEND=wayland firefox https://example.com", runAsRoot = true),
        )
        assertEquals(
            "librewolf",
            resolveAppWindowExecCommand("fakeroot-tcp librewolf", runAsRoot = false),
        )
    }
}
