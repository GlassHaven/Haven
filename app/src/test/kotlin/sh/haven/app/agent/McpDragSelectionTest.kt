package sh.haven.app.agent

import sh.haven.core.mcp.McpError
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.connectbot.terminal.ScrollController
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.SelectionRange
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import sh.haven.feature.terminal.agent.TerminalSessionRegistry

/**
 * Pin the agent-side drag-into-scrollback primitive (`drag_selection_to`)
 * against direct calls into its underlying SelectionController +
 * ScrollController interactions. The Compose gesture handler at
 * Terminal.kt:1340–1357 is the corresponding user-facing path; both
 * must remain in lockstep (scroll one row, shift the start anchor one
 * row) so the selection's logical content stays anchored as the
 * viewport scrolls.
 */
class McpDragSelectionTest {

    private val sessionId = "ses-1"
    private val rows = 24

    private fun emulator(scrollbackSize: Int = 0): TerminalEmulator = mockk(relaxed = true) {
        every { dimensions } returns TerminalDimensions(rows = rows, columns = 80)
        // `dragSelectionTo` reads the clamp threshold from the emulator's
        // live agent snapshot (not the Compose-side ScrollController),
        // so each test pins the visible scrollback depth here.
        every { buildAgentSnapshot(any(), any()) } returns org.connectbot.terminal.AgentSnapshot(
            rows = rows,
            cols = 80,
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            terminalTitle = "",
            scrollbackSize = scrollbackSize,
            lines = emptyList(),
        )
    }

    private fun selectionController(
        rangeAfterDrag: SelectionRange = SelectionRange(10, 0, 0, 5),
        selectedText: String = "drag-result",
    ): SelectionController = mockk(relaxed = true) {
        every { getSelectionRange() } returns rangeAfterDrag
        every { getSelectedText() } returns selectedText
    }

    private fun scrollController(
        initialPosition: Int,
        maxScrollback: Int,
    ): ScrollController {
        // Use a fake that mutates so the loop's clamp-check (against
        // scroll.scrollbackPosition) reflects each step. mockk can do
        // this too but a tiny in-memory fake is more legible.
        var position = initialPosition
        return object : ScrollController {
            override val scrollbackPosition: Int get() = position
            override val maxScrollback: Int = maxScrollback
            override fun scrollToBottom() { position = 0 }
            override fun scrollToTop() { position = maxScrollback }
            override fun scrollBy(lines: Int) {
                position = (position + lines).coerceIn(0, maxScrollback)
            }
        }
    }

    private fun newTools(
        registry: TerminalSessionRegistry,
    ): McpTools {
        val connectionRepository = mockk<ConnectionRepository>(relaxed = true)
        coEvery { connectionRepository.getById(any()) } returns
            ConnectionProfile(id = "p1", label = "test-host", host = "10.0.0.1", username = "u")
        return McpTools(
            context = mockk<Context>(relaxed = true),
            connectionRepository = connectionRepository,
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = mockk<SshSessionManager>(relaxed = true),
            sessionManagerRegistry = mockk<SessionManagerRegistry>(relaxed = true),
            rcloneClient = mockk<RcloneClient>(relaxed = true),
            mailSessionManager = mockk<sh.haven.core.mail.MailSessionManager>(relaxed = true),
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
            transportSelector = mockk<sh.haven.feature.sftp.transport.TransportSelector>(relaxed = true),
            workspaceRepository = mockk<sh.haven.core.data.repository.WorkspaceRepository>(relaxed = true),
            workspaceLauncher = mockk<sh.haven.app.workspace.WorkspaceLauncher>(relaxed = true),
            tunnelConfigRepository = mockk<sh.haven.core.data.repository.TunnelConfigRepository>(relaxed = true),
            tunnelManager = mockk<sh.haven.core.tunnel.TunnelManager>(relaxed = true),
            terminalSessionRegistry = registry,
            portKnocker = mockk<sh.haven.core.knock.PortKnocker>(relaxed = true),
            spaSender = mockk<sh.haven.core.spa.SpaSender>(relaxed = true),
            connectionLogRepository = mockk<sh.haven.core.data.repository.ConnectionLogRepository>(relaxed = true),
            servedFileTracker = sh.haven.core.data.agent.ServedFileTracker(),
            syncProfileRepository = mockk<sh.haven.core.data.repository.SyncProfileRepository>(relaxed = true),
            terminalInputQueue = mockk<TerminalInputQueue>(relaxed = true),
            prootInstallLogRepository = mockk<sh.haven.core.data.repository.ProotInstallLogRepository>(relaxed = true),
            sshKeyRepository = mockk<sh.haven.core.data.repository.SshKeyRepository>(relaxed = true),
            knownHostDao = mockk(relaxed = true),
            stepCaConfigRepository = mockk<sh.haven.core.data.repository.StepCaConfigRepository>(relaxed = true),
            totpSecretRepository = mockk<sh.haven.core.data.repository.TotpSecretRepository>(relaxed = true),
            ageIdentityRepository = mockk<sh.haven.core.data.repository.AgeIdentityRepository>(relaxed = true),
            desktopSessionRegistry = mockk<sh.haven.core.data.desktop.DesktopSessionRegistry>(relaxed = true),
            aiRouteRegistry = sh.haven.core.openai.AiRouteRegistry(),
            usbBroker = mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
            usbIpServer = mockk<sh.haven.core.usb.UsbIpServer>(relaxed = true),
            usbDriveVmManager = mockk<sh.haven.app.usb.UsbDriveVmManager>(relaxed = true),
            umlRecoveryManager = mockk<sh.haven.app.usb.UmlRecoveryManager>(relaxed = true),
            presentationManager = sh.haven.core.data.agent.AgentPresentationManager(),
            havenUiBridge = mockk(relaxed = true),
            standingPolicyRepository = mockk(relaxed = true),
            mcpTunnelManager = mockk(relaxed = true),
            mcpStatusHolder = mockk(relaxed = true),
            reticulumSessionManager = mockk(relaxed = true),
            reticulumForwardServer = mockk(relaxed = true),
            mailRuleRepository = mockk(relaxed = true),
            mailWatchManager = mockk(relaxed = true),
            agentActivityHolder = mockk(relaxed = true),
        )
    }

    private fun JSONObject.range(): JSONObject = getJSONObject("range")

    @Test
    fun `drag_selection_to is gated ONCE_PER_SESSION`() {
        val tools = newTools(TerminalSessionRegistry())
        val consent = tools.consentFor("drag_selection_to")
            ?: error("drag_selection_to not registered")
        assertEquals(ConsentLevel.ONCE_PER_SESSION, consent.level)
    }

    @Test
    fun `in-viewport target skips the scroll loop`() = runTest {
        val registry = TerminalSessionRegistry()
        val emu = emulator(scrollbackSize = 100)
        registry.register(sessionId, emu)
        val sel = selectionController()
        val scroll = scrollController(initialPosition = 0, maxScrollback = 100)
        registry.setSelectionController(sessionId, sel)
        registry.setScrollController(sessionId, scroll)
        val tools = newTools(registry)

        val out = tools.call(
            "drag_selection_to",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("toRow", 5); put("toCol", 12)
            },
        )

        // No scrolling, no anchor shifting — just one updateSelectionEnd at the requested row.
        verify(exactly = 0) { sel.shiftSelectionStartByRows(any()) }
        verify(exactly = 1) { sel.updateSelectionEnd(5, 12) }
        assertEquals(0, out.getInt("scrollSteps"))
        assertEquals(0, out.getInt("scrollbackPosition"))
        assertFalse(out.getBoolean("clamped"))
    }

    @Test
    fun `negative toRow scrolls back and shifts start anchor in lockstep`() = runTest {
        val registry = TerminalSessionRegistry()
        registry.register(sessionId, emulator(scrollbackSize = 100))
        val sel = selectionController()
        val scroll = scrollController(initialPosition = 0, maxScrollback = 100)
        registry.setSelectionController(sessionId, sel)
        registry.setScrollController(sessionId, scroll)
        val tools = newTools(registry)

        val out = tools.call(
            "drag_selection_to",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("toRow", -5); put("toCol", 0)
            },
        )

        // Each of the 5 requested rows above the viewport should produce one
        // scroll-back step + one start-anchor shift, in lockstep.
        val shifts = mutableListOf<Int>()
        verify(exactly = 5) { sel.shiftSelectionStartByRows(capture(shifts)) }
        assertEquals(List(5) { +1 }, shifts.toList().take(5))
        // End anchor lands at viewport row 0 (top edge) — the gesture's
        // "your finger is at the top, the viewport scrolled past it" mapping.
        verify(exactly = 1) { sel.updateSelectionEnd(0, 0) }
        assertEquals(5, out.getInt("scrollbackPosition"))
        assertEquals(5, out.getInt("scrollSteps"))
        assertFalse(out.getBoolean("clamped"))
    }

    @Test
    fun `positive toRow beyond rows scrolls forward toward live`() = runTest {
        val registry = TerminalSessionRegistry()
        registry.register(sessionId, emulator(scrollbackSize = 100))
        val sel = selectionController()
        // Start scrolled back so there's room to scroll forward.
        val scroll = scrollController(initialPosition = 20, maxScrollback = 100)
        registry.setSelectionController(sessionId, sel)
        registry.setScrollController(sessionId, scroll)
        val tools = newTools(registry)

        // toRow=28 with rows=24 means 5 rows past the bottom edge
        // (28 - (rows-1=23) = 5).
        val out = tools.call(
            "drag_selection_to",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("toRow", 28); put("toCol", 79)
            },
        )

        val shifts = mutableListOf<Int>()
        verify(exactly = 5) { sel.shiftSelectionStartByRows(capture(shifts)) }
        assertEquals(List(5) { -1 }, shifts.toList().take(5))
        verify(exactly = 1) { sel.updateSelectionEnd(rows - 1, 79) }
        assertEquals(15, out.getInt("scrollbackPosition"))
        assertEquals(-5, out.getInt("scrollSteps"))
        assertFalse(out.getBoolean("clamped"))
    }

    @Test
    fun `clamps at scrollback size and reports clamped=true`() = runTest {
        val registry = TerminalSessionRegistry()
        registry.register(sessionId, emulator(scrollbackSize = 10))
        val sel = selectionController()
        val scroll = scrollController(initialPosition = 0, maxScrollback = 10)
        registry.setSelectionController(sessionId, sel)
        registry.setScrollController(sessionId, scroll)
        val tools = newTools(registry)

        // Requesting 50 rows back into a 10-row scrollback should yield
        // 10 scroll steps and clamped=true.
        val out = tools.call(
            "drag_selection_to",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("toRow", -50); put("toCol", 0)
            },
        )

        verify(exactly = 10) { sel.shiftSelectionStartByRows(+1) }
        verify(exactly = 1) { sel.updateSelectionEnd(0, 0) }
        assertEquals(10, out.getInt("scrollbackPosition"))
        assertEquals(10, out.getInt("scrollSteps"))
        assertTrue(out.getBoolean("clamped"))
    }

    @Test
    fun `missing tab is surfaced as a structured error`() = runTest {
        val tools = newTools(TerminalSessionRegistry())
        try {
            tools.call(
                "drag_selection_to",
                JSONObject().apply {
                    put("sessionId", "no-such")
                    put("toRow", 0); put("toCol", 0)
                },
            )
            error("expected McpError")
        } catch (e: McpError) {
            assertEquals(-32603, e.code)
        }
    }

    @Test
    fun `response carries the libvterm-aware selected text`() = runTest {
        val registry = TerminalSessionRegistry()
        registry.register(sessionId, emulator(scrollbackSize = 200))
        val sel = selectionController(
            rangeAfterDrag = SelectionRange(15, 0, 0, 9),
            selectedText = "line-170\nline-171\nline-172\nline-173\nline-174",
        )
        val scroll = scrollController(initialPosition = 0, maxScrollback = 200)
        registry.setSelectionController(sessionId, sel)
        registry.setScrollController(sessionId, scroll)
        val tools = newTools(registry)

        val out = tools.call(
            "drag_selection_to",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("toRow", -15); put("toCol", 9)
            },
        )

        // SelectionController.getSelectedText is the libvterm-flag-driven
        // path; the tool surfaces its result so an agent verifies what
        // would land on the clipboard without writing it.
        assertEquals(
            "line-170\nline-171\nline-172\nline-173\nline-174",
            out.getString("text"),
        )
    }
}
