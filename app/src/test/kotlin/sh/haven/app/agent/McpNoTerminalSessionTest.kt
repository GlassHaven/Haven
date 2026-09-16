package sh.haven.app.agent

import sh.haven.core.mcp.McpError
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
 * #213 — terminal-driving MCP tools used to give an external agent nothing
 * actionable when no terminal session was connected: `read_terminal_snapshot`
 * / `read_terminal_scrollback` threw an opaque "Missing required argument:
 * sessionId" and `queue_terminal_input` threw an "MCP reverse tunnel on port
 * 8730" message that means nothing to a direct-HTTP client. An agent (e.g.
 * Xiaomi MiMo) then looped with no guidance instead of being told to connect
 * a profile first.
 *
 * These tests pin the new contract:
 *  - no terminal connected           → -32603 "No terminal session is connected …"
 *  - exactly one terminal, no id     → defaults to that session
 *  - several terminals, no id        → -32602 "Several terminal sessions are open …"
 *  - explicit but unknown id         → -32603 "No registered terminal tab …" (unchanged)
 */
class McpNoTerminalSessionTest {

    private fun emulator(): TerminalEmulator = mockk(relaxed = true) {
        every { dimensions } returns TerminalDimensions(rows = 24, columns = 80)
        every { buildAgentSnapshot(any(), any()) } returns org.connectbot.terminal.AgentSnapshot(
            rows = 24,
            cols = 80,
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            terminalTitle = "",
            scrollbackSize = 0,
            lines = emptyList(),
        )
    }

    private fun newTools(
        registry: TerminalSessionRegistry,
        sshSessionManager: SshSessionManager = mockk(relaxed = true),
    ): McpTools {
        val connectionRepository = mockk<ConnectionRepository>(relaxed = true)
        coEvery { connectionRepository.getById(any()) } returns
            ConnectionProfile(id = "p1", label = "test-host", host = "10.0.0.1", username = "u")
        return McpTools(
            context = mockk<Context>(relaxed = true),
            connectionRepository = connectionRepository,
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = sshSessionManager,
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

    private fun assertNoSessionError(tool: String, args: JSONObject = JSONObject()) = runTest {
        // queue_terminal_input first probes for an SSH session carrying the
        // MCP tunnel; with no terminal connected that lookup must miss so the
        // actionable fallback fires. (Relaxed mocks hand back "" for a
        // String? return, which would masquerade as a found session.)
        val ssh = mockk<SshSessionManager>(relaxed = true) {
            every { findRemoteForwardSession(any()) } returns null
        }
        val tools = newTools(TerminalSessionRegistry(), ssh)
        try {
            tools.call(tool, args)
            error("expected McpError for $tool with no terminal connected")
        } catch (e: McpError) {
            assertEquals("$tool should use the 'no session' code", -32603, e.code)
            val msg = e.message ?: ""
            assertTrue(
                "$tool error should name the no-session condition: \"$msg\"",
                msg.contains("No terminal session is connected"),
            )
            assertTrue(
                "$tool error should tell the agent how to recover: \"$msg\"",
                msg.contains("connect_profile") || msg.contains("Local Shell"),
            )
        }
    }

    @Test
    fun `read_terminal_snapshot with no session gives actionable error`() =
        assertNoSessionError("read_terminal_snapshot")

    @Test
    fun `read_terminal_scrollback with no session gives actionable error`() =
        assertNoSessionError("read_terminal_scrollback")

    @Test
    fun `send_terminal_input with no session gives actionable error`() =
        assertNoSessionError("send_terminal_input", JSONObject().put("text", "hello\n"))

    @Test
    fun `queue_terminal_input with no session gives actionable error`() =
        assertNoSessionError("queue_terminal_input", JSONObject().put("text", "hello"))

    @Test
    fun `read_terminal_snapshot defaults to the sole open session when id omitted`() = runTest {
        val registry = TerminalSessionRegistry()
        registry.register("only-one", emulator())
        val tools = newTools(registry)

        val out = tools.call("read_terminal_snapshot", JSONObject())

        assertEquals("only-one", out.getString("sessionId"))
        assertEquals(24, out.getInt("rows"))
    }

    @Test
    fun `several open sessions require an explicit sessionId`() = runTest {
        val registry = TerminalSessionRegistry()
        registry.register("ses-a", emulator())
        registry.register("ses-b", emulator())
        val tools = newTools(registry)

        try {
            tools.call("read_terminal_snapshot", JSONObject())
            error("expected McpError when several sessions are open and id omitted")
        } catch (e: McpError) {
            assertEquals(-32602, e.code)
            assertTrue(
                "error should ask the caller to pick: \"${e.message}\"",
                (e.message ?: "").contains("Several terminal sessions are open"),
            )
        }
    }

    @Test
    fun `explicit unknown sessionId still reports no registered tab`() = runTest {
        val registry = TerminalSessionRegistry()
        registry.register("real", emulator())
        val tools = newTools(registry)

        try {
            tools.call("read_terminal_snapshot", JSONObject().put("sessionId", "ghost"))
            error("expected McpError for an unknown explicit sessionId")
        } catch (e: McpError) {
            assertEquals(-32603, e.code)
            assertTrue(
                "error should name the stale tab: \"${e.message}\"",
                (e.message ?: "").contains("No registered terminal tab"),
            )
        }
    }
}
