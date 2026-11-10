package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shell script Haven assembles for /proc scanning must parse.
 *
 * Added after a fix for #501 spliced a multi-line `if … fi` into a `|| { … }`
 * branch: it read correctly, described the right behaviour, and was not valid
 * shell. Nothing in a string-contains assertion notices that, and a script
 * that fails to parse simply finds nothing — which is the same silent
 * no-op that made #501 invisible for as long as it was.
 *
 * `sh -n` parses without executing, so this is safe to run over scripts that
 * would otherwise walk /proc and kill things.
 */
class GeneratedShellSyntaxTest {

    private fun parses(script: String): Boolean =
        ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start().waitFor() == 0

    @Test
    fun `the app-window session scanner parses`() {
        listOf("/tmp/xdg-runtime-1", "/tmp/xdg-runtime-11").forEach {
            assertTrue("rejected for xdg=$it", parses(appWindowSessionScanScript(it)))
        }
    }

    @Test
    fun `the nested-wayland scanner parses for every compositor we launch`() {
        // The case arm is built from these, so each is a chance to emit
        // something `case` cannot parse.
        listOf("sway", "labwc", "Hyprland", "niri", "cage").forEach { compositor ->
            val script = nestedWaylandScanScript(listOf(compositor, "wayvnc", "foot", "swaynag"))
            assertTrue("rejected for compositor=$compositor", parses(script))
        }
    }

    @Test
    fun `the cmdline scanner parses`() {
        assertTrue(parses(CMDLINE_SCAN_SCRIPT))
    }

    /**
     * Guards the test itself: `sh -n` has to actually reject something, or
     * every assertion above passes for the wrong reason.
     */
    @Test
    fun `sh -n rejects a script that does not parse`() {
        assertTrue("unbalanced quote must be rejected", !parses("echo \"unterminated"))
        assertTrue("dangling then must be rejected", !parses("if true; then"))
    }

    @Test
    fun `the cmdline scanner emits a tab between pid and argv`() {
        // The Kotlin side splits on the first tab, so a literal `\t` reaching
        // the shell as the two characters backslash-t would silently produce
        // no usable rows.
        val out = ProcessBuilder("sh", "-c", CMDLINE_SCAN_SCRIPT)
            .redirectErrorStream(true).start()
        val text = out.inputStream.bufferedReader().readText()
        out.waitFor()
        val rows = text.lineSequence().filter { it.contains('\t') }.toList()
        assertTrue("scanned /proc and found no tab-separated rows", rows.isNotEmpty())
        rows.take(20).forEach { row ->
            val pid = row.substringBefore('\t')
            assertEquals("first field must be a pid: '$row'", pid, pid.filter { it.isDigit() })
        }
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
