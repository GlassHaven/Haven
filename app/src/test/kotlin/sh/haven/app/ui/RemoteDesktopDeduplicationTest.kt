package sh.haven.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Track 5 architectural regression guard: VNC and RDP/SPICE/GLKVM viewers must
 * share `RemoteDesktopViewport` and `RemoteDesktopImeField` in `:core:ui`
 * instead of maintaining ~1,800 lines of duplicated canvas/gesture/IME state
 * machines and unreferenced legacy key toolbars.
 */
class RemoteDesktopDeduplicationTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: break
        }
        return dir
    }

    @Test
    fun `VncScreen and RdpScreen delegate canvas gestures and IME diffing to shared RemoteDesktopViewport`() {
        val root = repoRoot()
        val sharedViewport = File(root, "core/ui/src/main/kotlin/sh/haven/core/ui/RemoteDesktopViewport.kt")
        val vncScreen = File(root, "feature/vnc/src/main/kotlin/sh/haven/feature/vnc/VncScreen.kt")
        val rdpScreen = File(root, "feature/rdp/src/main/kotlin/sh/haven/feature/rdp/RdpScreen.kt")

        assertTrue("Expected shared RemoteDesktopViewport.kt in :core:ui", sharedViewport.isFile)
        val sharedSrc = sharedViewport.readText()
        assertTrue(sharedSrc.contains("fun RemoteDesktopViewport("))
        assertTrue(sharedSrc.contains("fun RemoteDesktopImeField("))

        val vncSrc = vncScreen.readText()
        val rdpSrc = rdpScreen.readText()

        assertTrue("VncScreen.kt must delegate to RemoteDesktopViewport", vncSrc.contains("RemoteDesktopViewport("))
        assertTrue("RdpScreen.kt must delegate to RemoteDesktopViewport", rdpSrc.contains("RemoteDesktopViewport("))
        assertTrue("VncScreen.kt must delegate to RemoteDesktopImeField", vncSrc.contains("RemoteDesktopImeField("))
        assertTrue("RdpScreen.kt must delegate to RemoteDesktopImeField", rdpSrc.contains("RemoteDesktopImeField("))

        // Duplicated gesture state machines and dead legacy key toolbars must stay retired
        assertFalse("VncScreen.kt should not duplicate awaitEachGesture", vncSrc.contains("awaitEachGesture"))
        assertFalse("RdpScreen.kt should not duplicate awaitEachGesture", rdpSrc.contains("awaitEachGesture"))
        assertFalse("Dead VncKeyToolbar should stay removed", vncSrc.contains("private fun VncKeyToolbar("))
        assertFalse("Dead RdpKeyToolbar should stay removed", rdpSrc.contains("private fun RdpKeyToolbar("))

        val vncLines = vncSrc.lines().size
        val rdpLines = rdpSrc.lines().size
        assertTrue("Expected VncScreen.kt < 2200 lines after Track 5 dedup (was 3070, got $vncLines)", vncLines < 2200)
        assertTrue("Expected RdpScreen.kt < 2300 lines after Track 5 dedup (was 3289, got $rdpLines)", rdpLines < 2300)
    }
}
