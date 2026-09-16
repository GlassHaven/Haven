package sh.haven.app.agent

import sh.haven.core.mcp.McpError
import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
 * Pins the JSON-mapping + argument-validation contract of the
 * self-hosting-loop UI tools (capture_haven_ui / tap_haven_ui /
 * swipe_haven_ui). The [HavenUiBridge] is stubbed, so these run on the JVM
 * with no Activity. The success (Ok → image) path needs a real Bitmap +
 * PixelCopy and is verified on-device, not here.
 */
class HavenUiToolsTest {

    private fun newTools(bridge: HavenUiBridge): McpTools = McpTools(
        context = mockk<Context>(relaxed = true),
        connectionRepository = mockk<ConnectionRepository>(relaxed = true),
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
        terminalSessionRegistry = TerminalSessionRegistry(),
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
        havenUiBridge = bridge,
        standingPolicyRepository = mockk(relaxed = true),
        mcpTunnelManager = mockk(relaxed = true),
        mcpStatusHolder = mockk(relaxed = true),
        reticulumSessionManager = mockk(relaxed = true),
        reticulumForwardServer = mockk(relaxed = true),
        mailRuleRepository = mockk(relaxed = true),
        mailWatchManager = mockk(relaxed = true),
        agentActivityHolder = mockk(relaxed = true),
    )

    @Test
    fun `capture_haven_ui maps a secure window to a secure flag, not a black frame`() = runTest {
        val bridge = mockk<HavenUiBridge>()
        coEvery { bridge.captureScreen() } returns HavenUiBridge.CaptureResult.Secure
        // A secure window returns a structured result (secure flag), NOT an
        // image — proven on the typed path, not by an absent reserved key.
        val typed = newTools(bridge).callTyped("capture_haven_ui", JSONObject())
        assertTrue("secure window must not be an image", typed is ToolResult.Structured)
        assertTrue("secure window should be flagged", typed.structured.optBoolean("secure"))
    }

    @Test
    fun `capture_haven_ui surfaces no-foreground as an actionable -32603`() = runTest {
        val bridge = mockk<HavenUiBridge>()
        coEvery { bridge.captureScreen() } returns
            HavenUiBridge.CaptureResult.NoForeground("Haven is not in the foreground — bring the app forward, then retry.")
        try {
            newTools(bridge).call("capture_haven_ui", JSONObject())
            error("expected McpError when no activity is foreground")
        } catch (e: McpError) {
            assertEquals(-32603, e.code)
            assertTrue((e.message ?: "").contains("foreground"))
        }
    }

    @Test
    fun `tap_haven_ui requires x and y`() = runTest {
        val bridge = mockk<HavenUiBridge>()
        try {
            newTools(bridge).call("tap_haven_ui", JSONObject().put("x", 10))
            error("expected McpError when y is missing")
        } catch (e: McpError) {
            assertEquals(-32602, e.code)
            assertTrue((e.message ?: "").contains("y is required"))
        }
    }

    @Test
    fun `tap_haven_ui reports a refusal with its reason and does not throw`() = runTest {
        val bridge = mockk<HavenUiBridge>()
        coEvery { bridge.dispatchTap(any(), any(), any()) } returns
            HavenUiBridge.InjectResult.Refused("a consent prompt is showing")
        val out = newTools(bridge).call("tap_haven_ui", JSONObject().put("x", 5).put("y", 7))

        assertFalse(out.getBoolean("delivered"))
        assertTrue(out.getString("reason").contains("consent prompt"))
    }

    @Test
    fun `tap_haven_ui echoes coordinates on delivery`() = runTest {
        val bridge = mockk<HavenUiBridge>()
        coEvery { bridge.dispatchTap(any(), any(), any()) } returns HavenUiBridge.InjectResult.Delivered
        val out = newTools(bridge).call("tap_haven_ui", JSONObject().put("x", 42).put("y", 99))

        assertTrue(out.getBoolean("delivered"))
        assertEquals(42, out.getInt("x"))
        assertEquals(99, out.getInt("y"))
    }

    @Test
    fun `readUiScreenResource maps a secure window to a read error`() = runTest {
        val bridge = mockk<HavenUiBridge>()
        coEvery { bridge.captureScreen() } returns HavenUiBridge.CaptureResult.Secure
        try {
            newTools(bridge).readUiScreenResource()
            error("expected McpError when the window is secure")
        } catch (e: McpError) {
            assertEquals(-32603, e.code)
            assertTrue((e.message ?: "").contains("FLAG_SECURE"))
        }
    }

    @Test
    fun `readUiScreenResource maps no-foreground to a read error`() = runTest {
        val bridge = mockk<HavenUiBridge>()
        coEvery { bridge.captureScreen() } returns
            HavenUiBridge.CaptureResult.NoForeground("Haven is not in the foreground — bring the app forward, then retry.")
        try {
            newTools(bridge).readUiScreenResource()
            error("expected McpError when not foreground")
        } catch (e: McpError) {
            assertEquals(-32603, e.code)
            assertTrue((e.message ?: "").contains("foreground"))
        }
    }

    @Test
    fun `swipe_haven_ui requires all four coordinates`() = runTest {
        val bridge = mockk<HavenUiBridge>()
        try {
            newTools(bridge).call(
                "swipe_haven_ui",
                JSONObject().put("fromX", 1).put("fromY", 2).put("toX", 3),
            )
            error("expected McpError when toY is missing")
        } catch (e: McpError) {
            assertEquals(-32602, e.code)
            assertTrue((e.message ?: "").contains("toY is required"))
        }
    }
}
