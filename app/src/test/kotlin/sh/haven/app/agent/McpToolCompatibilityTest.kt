package sh.haven.app.agent

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mcp.McpError
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.sftp.SftpAttrs
import sh.haven.core.ssh.sftp.SftpSession
import sh.haven.feature.sftp.SftpEntry
import sh.haven.feature.sftp.SftpStreamServer
import sh.haven.feature.sftp.transport.FileBackend
import sh.haven.feature.sftp.transport.TransportSelector
import java.io.File

/**
 * Compatibility pins for the deprecated MCP tool aliases (#127/#161).
 * Tool names are external contract: any paired client may still call
 * them regardless of internal call sites, so the registry must keep the
 * names, schema shapes, consent levels, and result shapes until a
 * deliberate deprecation cycle. The legacy handlers also differ
 * materially from their canonical replacements (path defaults, routing,
 * streaming vs in-memory upload, directory-refusing delete, result
 * fields) — a "collapse the aliases" cleanup would break paired clients,
 * so those differences are pinned here.
 */
class McpToolCompatibilityTest {

    private fun newTools(
        context: Context = mockk(relaxed = true),
        sshSessionManager: SshSessionManager = mockk(relaxed = true),
        transportSelector: TransportSelector = mockk(relaxed = true),
        terminalInputQueue: TerminalInputQueue = mockk(relaxed = true),
    ): McpTools {
        val connectionRepository = mockk<ConnectionRepository>(relaxed = true)
        coEvery { connectionRepository.getById(any()) } returns
            ConnectionProfile(id = "p1", label = "test-host", host = "10.0.0.1", username = "u")
        // Relaxed mockk returns false, which reads as "user disabled MCP for
        // this connection" and blocks every profile-targeting call.
        coEvery { connectionRepository.isMcpEnabled(any()) } returns true
        return McpTools(
            context = context,
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
            transportSelector = transportSelector,
            workspaceRepository = mockk<sh.haven.core.data.repository.WorkspaceRepository>(relaxed = true),
            workspaceLauncher = mockk<sh.haven.app.workspace.WorkspaceLauncher>(relaxed = true),
            tunnelConfigRepository = mockk<sh.haven.core.data.repository.TunnelConfigRepository>(relaxed = true),
            tunnelManager = mockk<sh.haven.core.tunnel.TunnelManager>(relaxed = true),
            terminalSessionRegistry = sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            portKnocker = mockk<sh.haven.core.knock.PortKnocker>(relaxed = true),
            spaSender = mockk<sh.haven.core.spa.SpaSender>(relaxed = true),
            connectionLogRepository = mockk<sh.haven.core.data.repository.ConnectionLogRepository>(relaxed = true),
            servedFileTracker = sh.haven.core.data.agent.ServedFileTracker(),
            syncProfileRepository = mockk<sh.haven.core.data.repository.SyncProfileRepository>(relaxed = true),
            terminalInputQueue = terminalInputQueue,
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

    private fun defs(tools: McpTools): Map<String, JSONObject> =
        tools.definitions().associateBy { it.getString("name") }

    // --- names and schemas are external contract ---

    @Test
    fun `legacy aliases and their canonical replacements are all registered`() {
        val d = defs(newTools())
        for (
            name in listOf(
                "queue_terminal_input", "queue_self_message",
                "list_directory", "list_sftp_directory", "list_rclone_directory",
                "upload_file", "upload_file_to_sftp",
                "delete_file", "delete_sftp_file",
            )
        ) {
            assertTrue("tool '$name' missing from tools/list", d.containsKey(name))
        }
    }

    @Test
    fun `list_rclone_directory takes remote not profileId`() {
        val d = defs(newTools())
        val legacyProps = d.getValue("list_rclone_directory")
            .getJSONObject("inputSchema").getJSONObject("properties")
        assertTrue(legacyProps.has("remote"))
        assertFalse(legacyProps.has("profileId"))
        val canonicalProps = d.getValue("list_directory")
            .getJSONObject("inputSchema").getJSONObject("properties")
        assertTrue(canonicalProps.has("profileId"))
    }

    @Test
    fun `queue_self_message keeps the queue_terminal_input consent contract`() {
        val tools = newTools()
        val canonical = tools.consentFor("queue_terminal_input")!!
        val alias = tools.consentFor("queue_self_message")!!
        assertEquals(canonical.level, alias.level)
        val args = JSONObject().put("text", "run the tests")
        assertEquals(canonical.summary(args), alias.summary(args))
    }

    // --- behavioural differences between legacy and canonical handlers ---

    @Test
    fun `queue_self_message routes through the same queue as queue_terminal_input`() = runBlocking {
        val smgr = mockk<SshSessionManager>(relaxed = true)
        every { smgr.findRemoteForwardSession(any()) } returns "sess-1"
        val queue = mockk<TerminalInputQueue>(relaxed = true)
        every {
            queue.enqueue(any(), any(), any(), any(), any(), any())
        } returns "q-1"
        val tools = newTools(sshSessionManager = smgr, terminalInputQueue = queue)

        val args = JSONObject().put("text", "y")
        val canonical = tools.call("queue_terminal_input", args)
        val alias = tools.call("queue_self_message", args)

        assertEquals(canonical.toString(), alias.toString())
        io.mockk.verify(exactly = 2) {
            queue.enqueue("sess-1", "y", any(), 60, "\r", any())
        }
    }

    @Test
    fun `canonical listing defaults to root, legacy SFTP listing defaults to dot`() = runBlocking {
        val backendPaths = mutableListOf<String>()
        val backend = mockk<FileBackend>()
        every { backend.label } returns "SFTP"
        coEvery { backend.list(any()) } answers {
            backendPaths.add(firstArg())
            listOf(SftpEntry("f.txt", "/f.txt", false, 3, 0, ""))
        }
        val selector = mockk<TransportSelector>()
        coEvery { selector.resolveFileBackend("p1") } returns
            TransportSelector.FileBackendResolution(backend)

        val sftpPaths = mutableListOf<String>()
        val session = mockk<SftpSession>()
        coEvery { session.list(any(), any()) } answers { sftpPaths.add(firstArg()) }
        val smgr = mockk<SshSessionManager>(relaxed = true)
        every { smgr.openSftpSession("p1") } returns session

        val tools = newTools(sshSessionManager = smgr, transportSelector = selector)
        val noPath = JSONObject().put("profileId", "p1")
        val canonical = tools.call("list_directory", noPath)
        val legacy = tools.call("list_sftp_directory", noPath)

        assertEquals(listOf("/"), backendPaths)
        assertEquals(listOf("."), sftpPaths)
        // Result shapes differ too: only the canonical tool reports the
        // resolved backend, and only its entries carry a full path.
        assertEquals("SFTP", canonical.getString("backend"))
        assertFalse(legacy.has("backend"))
        assertTrue(canonical.getJSONArray("entries").getJSONObject(0).has("path"))
    }

    @Test
    fun `delete_sftp_file stats first and refuses directories`(): Unit = runBlocking {
        val session = mockk<SftpSession>(relaxed = true)
        coEvery { session.stat("/srv") } returns
            SftpAttrs("srv", true, false, 0, 0, "drwxr-xr-x", 0, 0)
        val smgr = mockk<SshSessionManager>(relaxed = true)
        every { smgr.openSftpSession("p1") } returns session

        val tools = newTools(sshSessionManager = smgr)
        try {
            tools.call("delete_sftp_file", JSONObject().put("profileId", "p1").put("path", "/srv"))
            fail("expected the directory delete to be refused")
        } catch (e: McpError) {
            assertEquals(-32602, e.code)
        }
        coVerify(exactly = 0) { session.rm(any()) }
    }

    @Test
    fun `both upload tools confine the source to the app cache`(): Unit = runBlocking {
        val cacheDir = java.nio.file.Files.createTempDirectory("mcp-cache").toFile()
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.cacheDir } returns cacheDir
        val tools = newTools(context = ctx)
        for (name in listOf("upload_file", "upload_file_to_sftp")) {
            try {
                tools.call(
                    name,
                    JSONObject().put("profileId", "p1")
                        .put("localPath", "/etc/passwd").put("remotePath", "/tmp/x"),
                )
                fail("$name accepted a source outside the app cache")
            } catch (e: McpError) {
                assertEquals(-32602, e.code)
                assertTrue(e.message!!.contains("app cache"))
            }
        }
    }

    @Test
    fun `legacy upload streams via SFTP put, canonical writes via FileBackend`() = runBlocking {
        val cacheDir = java.nio.file.Files.createTempDirectory("mcp-cache").toFile()
        val src = File(cacheDir, "payload.bin").apply { writeBytes("hello".toByteArray()) }
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.cacheDir } returns cacheDir

        val session = mockk<SftpSession>(relaxed = true)
        val smgr = mockk<SshSessionManager>(relaxed = true)
        every { smgr.openSftpSession("p1") } returns session

        val backend = mockk<FileBackend>(relaxed = true)
        every { backend.label } returns "SFTP"
        val selector = mockk<TransportSelector>()
        coEvery { selector.resolveFileBackend("p1") } returns
            TransportSelector.FileBackendResolution(backend)

        val tools = newTools(context = ctx, sshSessionManager = smgr, transportSelector = selector)
        val args = JSONObject().put("profileId", "p1")
            .put("localPath", src.path).put("remotePath", "/dst")

        tools.call("upload_file_to_sftp", args)
        coVerify { session.upload(any(), eq(5L), eq("/dst"), any(), any()) }

        tools.call("upload_file", args)
        coVerify { backend.writeBytes("/dst", match { it.decodeToString() == "hello" }) }
    }
}
