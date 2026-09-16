package sh.haven.app.agent

import sh.haven.core.mcp.McpError
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.StandingPolicy
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.StandingPolicyRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import sh.haven.feature.terminal.agent.TerminalSessionRegistry

/**
 * Pins create_standing_policy's validation contract: a policy proposal must
 * name an existing, coverable tool set; it binds to the CALLING client; and
 * rate/expiry are clamped server-side so an agent can't propose an unbounded
 * grant. (Gate behaviour — what an installed policy actually skips — is
 * pinned in [McpServerConsentTest]; pure matching in [StandingPolicyEnforcerTest].)
 */
class StandingPolicyToolsTest {

    private fun newTools(repo: StandingPolicyRepository): McpTools = McpTools(
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
        havenUiBridge = mockk(relaxed = true),
        standingPolicyRepository = repo,
        mcpTunnelManager = mockk(relaxed = true),
        mcpStatusHolder = mockk(relaxed = true),
        reticulumSessionManager = mockk(relaxed = true),
        reticulumForwardServer = mockk(relaxed = true),
        mailRuleRepository = mockk(relaxed = true),
        mailWatchManager = mockk(relaxed = true),
        agentActivityHolder = mockk(relaxed = true),
    )

    private fun createArgs(vararg toolNames: String): JSONObject = JSONObject().apply {
        put("description", "test grant")
        put("toolNames", JSONArray().apply { toolNames.forEach { put(it) } })
    }

    @Test
    fun `binds to the calling client and clamps rate and expiry`() = runTest {
        val repo = mockk<StandingPolicyRepository>(relaxed = true)
        val saved = slot<StandingPolicy>()
        coEvery { repo.savePolicy(capture(saved)) } returns Unit
        val before = System.currentTimeMillis()

        val out = newTools(repo).call(
            "create_standing_policy",
            createArgs("tap_haven_ui", "swipe_haven_ui")
                .put("maxCallsPerMinute", 100_000) // clamped to 600
                .put("expiresInMinutes", 999_999), // clamped to 1440
            clientHint = "drive-loop-client",
        )

        assertEquals("drive-loop-client", saved.captured.clientHint)
        assertEquals(600, saved.captured.maxCallsPerMinute)
        val maxExpiry = before + 1441 * 60_000L
        assertTrue(
            "expiry must be clamped to <=24h, got ${saved.captured.expiresAt}",
            saved.captured.expiresAt <= maxExpiry,
        )
        assertEquals(saved.captured.id, out.getString("id"))
    }

    @Test
    fun `rejects denylisted tools`() = runTest {
        val repo = mockk<StandingPolicyRepository>(relaxed = true)
        try {
            newTools(repo).call(
                "create_standing_policy",
                createArgs("install_apk_from_backend"),
                clientHint = "c",
            )
            error("expected McpError for a denylisted tool")
        } catch (e: McpError) {
            assertEquals(-32602, e.code)
            assertTrue((e.message ?: "").contains("never be covered"))
        }
        coVerify(exactly = 0) { repo.savePolicy(any()) }
    }

    @Test
    fun `rejects unknown tools`() = runTest {
        val repo = mockk<StandingPolicyRepository>(relaxed = true)
        try {
            newTools(repo).call("create_standing_policy", createArgs("no_such_tool"), clientHint = "c")
            error("expected McpError for an unknown tool")
        } catch (e: McpError) {
            assertEquals(-32602, e.code)
            assertTrue((e.message ?: "").contains("Unknown tool"))
        }
    }

    @Test
    fun `requires a client identity`() = runTest {
        val repo = mockk<StandingPolicyRepository>(relaxed = true)
        try {
            newTools(repo).call("create_standing_policy", createArgs("tap_haven_ui"))
            error("expected McpError without a clientHint")
        } catch (e: McpError) {
            assertEquals(-32602, e.code)
            assertTrue((e.message ?: "").contains("client identity"))
        }
    }

    @Test
    fun `revoke deletes and reports the policy`() = runTest {
        val repo = mockk<StandingPolicyRepository>(relaxed = true)
        coEvery { repo.getPolicy("pid") } returns StandingPolicy(
            id = "pid",
            clientHint = "c",
            description = "old grant",
            toolNamesJson = """["tap_haven_ui"]""",
            maxCallsPerMinute = 10,
            expiresAt = Long.MAX_VALUE,
        )

        val out = newTools(repo).call("revoke_standing_policy", JSONObject().put("id", "pid"))

        assertTrue(out.getBoolean("revoked"))
        assertEquals("old grant", out.getString("description"))
        coVerify { repo.deletePolicy("pid") }
    }
}
