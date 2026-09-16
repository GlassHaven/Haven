package sh.haven.app.agent

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentConsentManager
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

/**
 * #469 — a `Throwable` that is an `Error` (the on-device case:
 * `UnsatisfiedLinkError` from an unloaded JNI lib) escaping a tool handler
 * must come back to the client as a JSON-RPC -32603 like any other handler
 * failure. Before the fix, dispatch only caught `Exception`, so the Error
 * unwound past the response writer and the client saw a dead socket —
 * losing the MCP session over a single bad tool call.
 */
class McpDispatchErrorContainmentTest {

    private fun newServer(connectionRepository: ConnectionRepository): McpServer {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.mcpAllowedClients } returns flowOf(emptySet())
        every { prefs.mcpClientTokenHashes } returns flowOf(emptyMap())
        coEvery { prefs.addMcpAllowedClient(any()) } returns Unit

        return McpServer(
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
            preferencesRepository = prefs,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            auditRecorder = mockk<AgentAuditRecorder>(relaxed = true),
            consentManager = AgentConsentManager(),
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
            transportSelector = mockk<sh.haven.feature.sftp.transport.TransportSelector>(relaxed = true),
            workspaceRepository = mockk<sh.haven.core.data.repository.WorkspaceRepository>(relaxed = true),
            workspaceLauncher = mockk<sh.haven.app.workspace.WorkspaceLauncher>(relaxed = true),
            tunnelConfigRepository = mockk<sh.haven.core.data.repository.TunnelConfigRepository>(relaxed = true),
            tunnelManager = mockk<sh.haven.core.tunnel.TunnelManager>(relaxed = true),
            terminalSessionRegistry = sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            portKnocker = mockk<sh.haven.core.knock.PortKnocker>(relaxed = true),
            spaSender = mockk<sh.haven.core.spa.SpaSender>(relaxed = true),
            connectionLogRepository = mockk<sh.haven.core.data.repository.ConnectionLogRepository>(relaxed = true),
            servedFileTracker = mockk<sh.haven.core.data.agent.ServedFileTracker>(relaxed = true),
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
            standingPolicyEnforcer = StandingPolicyEnforcer(mockk(relaxed = true)),
            standingPolicyRepository = mockk(relaxed = true),
            mcpStatusHolder = sh.haven.core.data.agent.McpStatusHolder(),
            mcpTunnelManager = mockk(relaxed = true),
            btSerialSessionManager = mockk(relaxed = true),
            bleSerialSessionManager = mockk(relaxed = true),
            usbSerialSessionManager = mockk(relaxed = true),
            headlessSshExec = mockk(relaxed = true),
            reticulumSessionManager = mockk(relaxed = true),
            reticulumForwardServer = mockk(relaxed = true),
            mailRuleRepository = mockk(relaxed = true),
            mailWatchManager = mockk(relaxed = true),
            openAiSessionManager = mockk<sh.haven.core.openai.OpenAiSessionManager>(relaxed = true),
            tunnelResolver = mockk<sh.haven.core.tunnel.TunnelResolver>(relaxed = true),
            agentActivityHolder = mockk(relaxed = true),
        )
    }

    private fun toolsCallBody(name: String, args: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args))
            .toString()

    @Test
    fun `an Error escaping a tool handler returns -32603 instead of killing the request`() {
        val repo = mockk<ConnectionRepository>(relaxed = true)
        coEvery { repo.getAll() } throws
            UnsatisfiedLinkError("No implementation found for nativeStartVirglServer")
        val server = newServer(connectionRepository = repo)
        server.setTrustLoopbackEnabled(true)

        val outcome = server.handleJsonRpc(
            toolsCallBody("list_connections", JSONObject()),
            requestSessionId = null,
            origin = McpOrigin.DEVICE,
        )

        val error = JSONObject(outcome.body).getJSONObject("error")
        assertEquals(-32603, error.getInt("code"))
        assertTrue(
            "error message should carry the cause, got: ${error.getString("message")}",
            error.getString("message").contains("No implementation found"),
        )
    }
}
