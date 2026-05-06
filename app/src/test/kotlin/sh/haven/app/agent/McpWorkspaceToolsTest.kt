package sh.haven.app.agent

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.app.workspace.WorkspaceLaunchState
import sh.haven.app.workspace.WorkspaceLauncher
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.data.repository.WorkspaceWithItems
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer

/**
 * End-to-end coverage for the workspace MCP verbs (#143). Drives
 * [McpServer.handleJsonRpc] directly so the JSON-RPC layer, consent
 * gate, and tool body all exercise together — without spinning up
 * the real loopback HTTP transport (which is the same input shape and
 * is independently covered by the consent-gate tests in
 * [McpServerConsentTest]).
 */
class McpWorkspaceToolsTest {

    private fun newServer(
        workspaceRepository: WorkspaceRepository = mockk(relaxed = true),
        workspaceLauncher: WorkspaceLauncher = mockk(relaxed = true),
        consentManager: AgentConsentManager = AgentConsentManager(),
    ): McpServer {
        return McpServer(
            context = mockk<Context>(relaxed = true),
            connectionRepository = mockk<ConnectionRepository>(relaxed = true),
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = mockk<SshSessionManager>(relaxed = true),
            sessionManagerRegistry = mockk<SessionManagerRegistry>(relaxed = true),
            rcloneClient = mockk<RcloneClient>(relaxed = true),
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            auditRecorder = mockk(relaxed = true),
            consentManager = consentManager,
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
            transportSelector = mockk<sh.haven.feature.sftp.transport.TransportSelector>(relaxed = true),
            workspaceRepository = workspaceRepository,
            workspaceLauncher = workspaceLauncher,
        )
    }

    private fun toolsCallBody(name: String, args: JSONObject = JSONObject()): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args))
            .toString()

    @Test
    fun `list_workspaces returns count name and per-kind tallies`() {
        val workspaces = listOf(
            WorkspaceProfile(id = "ws-work", name = "Work"),
            WorkspaceProfile(id = "ws-home", name = "Home"),
        )
        val workItems = listOf(
            WorkspaceItem(workspaceId = "ws-work", kind = WorkspaceItem.Kind.TERMINAL, connectionProfileId = "p1"),
            WorkspaceItem(workspaceId = "ws-work", kind = WorkspaceItem.Kind.TERMINAL, connectionProfileId = "p2"),
            WorkspaceItem(workspaceId = "ws-work", kind = WorkspaceItem.Kind.DESKTOP, connectionProfileId = "p3"),
        )
        val homeItems = listOf(
            WorkspaceItem(workspaceId = "ws-home", kind = WorkspaceItem.Kind.WAYLAND),
        )
        val repo = mockk<WorkspaceRepository>(relaxed = true).apply {
            every { observeAll() } returns flowOf(workspaces)
            every { observeItems("ws-work") } returns flowOf(workItems)
            every { observeItems("ws-home") } returns flowOf(homeItems)
        }
        val server = newServer(workspaceRepository = repo)

        val response = server.handleJsonRpc(toolsCallBody("list_workspaces"))
        val result = JSONObject(response).optJSONObject("result")
            ?: error("expected result, got: $response")

        // The MCP wrapper puts the structured payload at result.structuredContent.
        val structured = result.optJSONObject("structuredContent")
            ?: error("expected structuredContent, got: $result")
        assertEquals(2, structured.optInt("count"))

        val arr = structured.optJSONArray("workspaces")
        assertNotNull(arr)
        assertEquals(2, arr!!.length())

        val work = (0 until arr.length()).map { arr.getJSONObject(it) }
            .first { it.optString("id") == "ws-work" }
        assertEquals("Work", work.optString("name"))
        assertEquals(3, work.optInt("itemCount"))
        val workKinds = work.getJSONObject("kinds")
        assertEquals(2, workKinds.optInt("TERMINAL"))
        assertEquals(1, workKinds.optInt("DESKTOP"))

        val home = (0 until arr.length()).map { arr.getJSONObject(it) }
            .first { it.optString("id") == "ws-home" }
        assertEquals(1, home.optInt("itemCount"))
        assertEquals(1, home.getJSONObject("kinds").optInt("WAYLAND"))
    }

    @Test
    fun `compose_workspace allowed by user calls launcher and returns outcome`() {
        val workspace = WorkspaceWithItems(
            profile = WorkspaceProfile(id = "ws-work", name = "Work"),
            items = listOf(
                WorkspaceItem(workspaceId = "ws-work", kind = WorkspaceItem.Kind.TERMINAL, connectionProfileId = "p1"),
                WorkspaceItem(workspaceId = "ws-work", kind = WorkspaceItem.Kind.WAYLAND),
            ),
        )
        val repo = mockk<WorkspaceRepository>(relaxed = true).apply {
            coEvery { getWorkspace("ws-work") } returns workspace
        }
        val launcher = mockk<WorkspaceLauncher>(relaxed = true).apply {
            // Walk transitions: Idle → Completed once launch returns.
            every { state } returns MutableStateFlow(
                WorkspaceLaunchState.Completed("ws-work", "Work", emptyList()),
            )
            coEvery { launch("ws-work") } returns Unit
        }
        val consentManager = AgentConsentManager().apply { setForegroundActive(true) }
        val server = newServer(repo, launcher, consentManager)

        // compose_workspace is ONCE_PER_SESSION-gated. Race the request
        // against the user "tap Allow" on the consent prompt.
        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            server.handleJsonRpc(
                toolsCallBody(
                    "compose_workspace",
                    JSONObject().put("workspaceId", "ws-work"),
                ),
            )
        }
        val deadline = System.currentTimeMillis() + 5_000
        var pending = consentManager.pending.value
        while (pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            pending = consentManager.pending.value
        }
        assertTrue("dispatcher never queued a consent request", pending.isNotEmpty())
        kotlinx.coroutines.runBlocking {
            consentManager.respond(pending.first().id, ConsentDecision.ALLOW)
        }

        val response = responseFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val result = JSONObject(response).optJSONObject("result")
            ?: error("expected result, got: $response")
        val structured = result.optJSONObject("structuredContent")
            ?: error("expected structuredContent, got: $result")

        assertEquals("ws-work", structured.optString("workspaceId"))
        assertEquals("Work", structured.optString("workspaceName"))
        assertEquals(2, structured.optInt("itemCount"))
        assertEquals("Completed", structured.optString("outcome"))

        coVerify(exactly = 1) { launcher.launch("ws-work") }
    }

    @Test
    fun `compose_workspace fails closed with no foreground activity`() {
        val repo = mockk<WorkspaceRepository>(relaxed = true).apply {
            coEvery { getWorkspace(any()) } returns WorkspaceWithItems(
                profile = WorkspaceProfile(id = "ws", name = "X"),
                items = listOf(
                    WorkspaceItem(workspaceId = "ws", kind = WorkspaceItem.Kind.WAYLAND),
                ),
            )
        }
        val launcher = mockk<WorkspaceLauncher>(relaxed = true)
        // Default consent manager: foregroundActive = false → DENY.
        val server = newServer(repo, launcher)

        val response = server.handleJsonRpc(
            toolsCallBody("compose_workspace", JSONObject().put("workspaceId", "ws")),
        )

        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error response, got: $response")
        assertEquals(-32000, error.optInt("code"))
        // Launcher must not have been invoked when consent fails.
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test
    fun `compose_workspace rejects unknown workspace id with -32602`() {
        val repo = mockk<WorkspaceRepository>(relaxed = true).apply {
            coEvery { getWorkspace("missing") } returns null
        }
        val launcher = mockk<WorkspaceLauncher>(relaxed = true)
        val consentManager = AgentConsentManager().apply { setForegroundActive(true) }
        val server = newServer(repo, launcher, consentManager)

        // Even an unknown id has to clear the consent gate first; allow it.
        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            server.handleJsonRpc(
                toolsCallBody(
                    "compose_workspace",
                    JSONObject().put("workspaceId", "missing"),
                ),
            )
        }
        val deadline = System.currentTimeMillis() + 5_000
        var pending = consentManager.pending.value
        while (pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            pending = consentManager.pending.value
        }
        assertTrue("expected consent prompt", pending.isNotEmpty())
        kotlinx.coroutines.runBlocking {
            consentManager.respond(pending.first().id, ConsentDecision.ALLOW)
        }

        val response = responseFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error response, got: $response")
        // -32602 = Invalid params. The tool body raises this when
        // getWorkspace returns null.
        assertEquals(-32602, error.optInt("code"))
        coVerify(exactly = 0) { launcher.launch(any()) }
    }
}
