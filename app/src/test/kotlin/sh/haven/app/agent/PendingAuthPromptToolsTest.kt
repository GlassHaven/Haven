package sh.haven.app.agent

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.agent.PendingAuthPrompt
import sh.haven.core.data.agent.PendingAuthPromptHolder
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
 * Pins the JSON contract of the auth-prompt MCP verbs: get_pending_auth_prompt
 * reflects the [PendingAuthPromptHolder], and answer_auth_prompt posts an
 * [AgentUiCommand.AnswerAuthPrompt] (or no-ops when nothing is pending). The
 * actual re-drive of the connect is exercised by ConnectionsViewModel and
 * verified on-device.
 */
class PendingAuthPromptToolsTest {

    private fun newTools(holder: PendingAuthPromptHolder, bus: AgentUiCommandBus): McpTools = McpTools(
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
        agentUiCommandBus = bus,
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
        havenUiBridge = mockk(relaxed = true),
        standingPolicyRepository = mockk(relaxed = true),
        mcpTunnelManager = mockk(relaxed = true),
        mcpStatusHolder = mockk(relaxed = true),
        reticulumSessionManager = mockk(relaxed = true),
        reticulumForwardServer = mockk(relaxed = true),
        mailRuleRepository = mockk(relaxed = true),
        mailWatchManager = mockk(relaxed = true),
        agentActivityHolder = mockk(relaxed = true),
        pendingAuthPromptHolder = holder,
    )

    @Test
    fun `get_pending_auth_prompt reflects the holder`() = runTest {
        val holder = PendingAuthPromptHolder()
        val tools = newTools(holder, AgentUiCommandBus())

        assertFalse(
            tools.call("get_pending_auth_prompt", JSONObject()).optBoolean("pending"),
        )

        holder.set(PendingAuthPrompt(profileId = "p1", label = "Lab", requiresPassphrase = true))
        val got = tools.call("get_pending_auth_prompt", JSONObject())
        assertTrue(got.optBoolean("pending"))
        assertEquals("p1", got.optString("profileId"))
        assertEquals("Lab", got.optString("label"))
        assertTrue(got.optBoolean("requiresPassphrase"))
    }

    @Test
    fun `answer_auth_prompt posts AnswerAuthPrompt when a prompt is pending`() = runTest {
        val holder = PendingAuthPromptHolder().apply {
            set(PendingAuthPrompt(profileId = "p1", label = "Lab", requiresPassphrase = false))
        }
        val bus = AgentUiCommandBus()
        val tools = newTools(holder, bus)

        val received = mutableListOf<AgentUiCommand>()
        val job = launch { bus.commands.collect { received.add(it) } }
        runCurrent() // let the collector subscribe before we emit (replay = 0)

        val out = tools.call(
            "answer_auth_prompt",
            JSONObject().put("password", "secret").put("rememberPassword", true),
        )
        runCurrent()

        assertTrue(out.optBoolean("answered"))
        assertEquals("p1", out.optString("profileId"))
        val cmd = received.filterIsInstance<AgentUiCommand.AnswerAuthPrompt>().single()
        assertEquals("secret", cmd.password)
        assertTrue(cmd.rememberPassword)
        job.cancel()
    }

    @Test
    fun `answer_auth_prompt is a no-op when nothing is pending`() = runTest {
        val tools = newTools(PendingAuthPromptHolder(), AgentUiCommandBus())
        val out = tools.call("answer_auth_prompt", JSONObject().put("password", "x"))
        assertFalse(out.optBoolean("answered"))
    }
}
