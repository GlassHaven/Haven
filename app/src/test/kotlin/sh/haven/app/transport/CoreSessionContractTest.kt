package sh.haven.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Track 4.2 architectural regression tests:
 *  - `:core:session` owns `Session`, `SessionStatus`, `Transport`,
 *    `TransportSessionManager`, `ForegroundSessionInfo`,
 *    `ForegroundSessionParticipant`, `ForegroundKeepAlive`, and
 *    `SessionManagerRegistry` with zero dependency on `:core:ssh`.
 *  - Cross-transport wiring (`:app` transport modules, `:core:tunnel`,
 *    `:feature:connections`) consumes `sh.haven.core.session.*` rather than
 *    importing the cross-transport contract from `sh.haven.core.ssh`.
 *  - `TerminalViewModel` deduplicates the 3x Serial (`BTSERIAL`/`BLESERIAL`/
 *    `USBSERIAL`) and 2x Roaming-shell (`MOSH`/`ET`) tab-builder clones.
 */
class CoreSessionContractTest {

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: error("Repo root not found from ${File("").absolutePath}")
        }
        return dir
    }

    @Test
    fun `core_session module exists and owns session contract types without depending on core_ssh`() {
        val root = repoRoot()
        val settings = File(root, "settings.gradle.kts").readText()
        assertTrue("settings.gradle.kts must include :core:session", settings.contains("""include(":core:session")"""))

        val sessionBuild = File(root, "core/session/build.gradle.kts")
        assertTrue("core/session/build.gradle.kts must exist", sessionBuild.exists())
        assertFalse(
            ":core:session must not depend on :core:ssh",
            sessionBuild.readText().contains(":core:ssh"),
        )

        val expectedClasses = listOf(
            "sh.haven.core.session.Session",
            "sh.haven.core.session.SessionStatus",
            "sh.haven.core.session.Transport",
            "sh.haven.core.session.TransportSessionManager",
            "sh.haven.core.session.ForegroundSessionInfo",
            "sh.haven.core.session.ForegroundSessionParticipant",
            "sh.haven.core.session.ForegroundKeepAlive",
            "sh.haven.core.session.SessionManagerRegistry",
        )
        for (fqcn in expectedClasses) {
            val loaded = runCatching { Class.forName(fqcn) }.getOrNull()
            assertTrue("Expected $fqcn to be loadable on classpath", loaded != null)
        }
    }

    @Test
    fun `cross-transport consumers import contract from sh_haven_core_session`() {
        val root = repoRoot()
        val files = listOf(
            "app/src/main/kotlin/sh/haven/app/transport/TransportSessionManagerModule.kt",
            "app/src/main/kotlin/sh/haven/app/transport/ForegroundSessionParticipantModule.kt",
            "app/src/main/kotlin/sh/haven/app/transport/SessionAdapters.kt",
            "app/src/full/kotlin/sh/haven/app/transport/DesktopTransportModule.kt",
            "core/tunnel/src/main/kotlin/sh/haven/core/tunnel/AnyConnectForeground.kt",
            "feature/connections/src/main/kotlin/sh/haven/feature/connections/usb/UsbForegroundParticipantModule.kt",
        ).filter { File(root, it).exists() }

        assertTrue("Expected transport wiring files to exist", files.size >= 5)
        val legacyImportRegex = Regex(
            """^import\s+sh\.haven\.core\.ssh\.(Session|SessionStatus|Transport|TransportSessionManager|ForegroundSessionInfo|ForegroundSessionParticipant|ForegroundKeepAlive|SessionManagerRegistry)\b""",
            RegexOption.MULTILINE,
        )
        for (rel in files) {
            val src = File(root, rel).readText()
            assertFalse(
                "$rel still imports cross-transport contract from sh.haven.core.ssh",
                legacyImportRegex.containsMatchIn(src),
            )
            assertTrue(
                "$rel should import sh.haven.core.session",
                src.contains("import sh.haven.core.session."),
            )
        }
    }

    @Test
    fun `TerminalViewModel deduplicates 3x Serial and 2x Roaming-shell tab builders`() {
        val vmSrc = File(
            repoRoot(),
            "feature/terminal/src/main/kotlin/sh/haven/feature/terminal/TerminalViewModel.kt",
        ).readText()
        assertTrue(
            "TerminalViewModel should share a buildSerialTabs helper across BTSERIAL/BLESERIAL/USBSERIAL",
            vmSrc.contains("private fun buildSerialTabs("),
        )
        assertTrue(
            "TerminalViewModel should share a buildRoamingShellTabs helper across MOSH/ET",
            vmSrc.contains("private fun buildRoamingShellTabs("),
        )
        val rawSerialResizeNoops = Regex("""/\*\s*raw serial:\s*no resize channel\s*\*/""").findAll(vmSrc).count()
        assertEquals(
            "Expected raw-serial no-resize comment only in the single shared builder (2 sites: onResize + resize), got $rawSerialResizeNoops",
            2,
            rawSerialResizeNoops,
        )
    }
}
