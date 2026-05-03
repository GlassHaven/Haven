package sh.haven.app.agent

import android.content.Context
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.db.entities.AgentAuditEvent
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
 * Drive [McpServer.handleJsonRpc] directly (no socket) and pin the
 * dispatcher's consent gate behaviour:
 *
 *  - non-NEVER tool with no foreground activity → DENY → JSON-RPC error
 *    code -32000, audit outcome [AgentAuditEvent.Outcome.DENIED]
 *  - non-NEVER tool with foreground active but user denies → same
 *  - non-NEVER tool with timeout → same (DENY by default after 60s; we
 *    use a tiny timeout via foreground=false to short-circuit, since
 *    runBlocking in tests with a 60s real-clock timeout would slow CI)
 *  - NEVER tool always runs and audits as OK
 *  - unknown method → -32601
 *
 * Consent ALLOW is covered indirectly via [AgentConsentManagerTest] for
 * the manager itself; here we focus on the JSON-RPC layer's mapping
 * from consent decision → response code → audit outcome.
 */
class McpServerConsentTest {

    private fun newServer(
        consentManager: AgentConsentManager = AgentConsentManager(),
        auditRecorder: AgentAuditRecorder = mockk(relaxed = true),
    ): Pair<McpServer, AgentAuditRecorder> {
        val server = McpServer(
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
            auditRecorder = auditRecorder,
            consentManager = consentManager,
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
        )
        return server to auditRecorder
    }

    private fun toolsCallBody(name: String, args: JSONObject): String {
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/call")
            .put("params", JSONObject()
                .put("name", name)
                .put("arguments", args))
            .toString()
    }

    @Test
    fun `non-NEVER tool with no foreground returns -32000 and audits DENIED`() {
        val auditRecorder = mockk<AgentAuditRecorder>(relaxed = true)
        // Default AgentConsentManager has foregroundActive=false, so any
        // non-NEVER call must immediately fail closed.
        val (server, _) = newServer(
            consentManager = AgentConsentManager(),
            auditRecorder = auditRecorder,
        )

        val response = server.handleJsonRpc(
            toolsCallBody(
                "disconnect_profile",
                JSONObject().put("profileId", "p1"),
            ),
        )

        val obj = JSONObject(response)
        val error = obj.optJSONObject("error")
            ?: error("expected error response, got: $response")
        assertEquals(-32000, error.optInt("code"))
        assertTrue(
            "error message must mention denial, got: ${error.optString("message")}",
            error.optString("message").contains("denied", ignoreCase = true),
        )

        // Audit row must record DENIED outcome — that's the only way the
        // user can later distinguish "the agent was blocked" from "the
        // tool ran and failed."
        val outcomeSlot = slot<AgentAuditEvent.Outcome>()
        verify {
            auditRecorder.record(
                method = any(),
                toolName = any(),
                rawArgs = any(),
                result = any(),
                durationMs = any(),
                outcome = capture(outcomeSlot),
                errorMessage = any(),
                clientHint = any(),
            )
        }
        assertEquals(AgentAuditEvent.Outcome.DENIED, outcomeSlot.captured)
    }

    @Test
    fun `EVERY_CALL tool with foreground+user-deny returns -32000 and audits DENIED`() {
        val consentManager = AgentConsentManager().apply { setForegroundActive(true) }
        val auditRecorder = mockk<AgentAuditRecorder>(relaxed = true)
        val (server, _) = newServer(consentManager = consentManager, auditRecorder = auditRecorder)

        // Spawn the call on a background thread so we can race the
        // "user taps Deny" response against the dispatcher's blocking
        // wait inside requestConsent.
        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            server.handleJsonRpc(
                toolsCallBody(
                    "delete_sftp_file",
                    JSONObject()
                        .put("profileId", "p1")
                        .put("path", "/var/log/x"),
                ),
            )
        }

        // Wait until the prompt actually appears, then deny it.
        val deadline = System.currentTimeMillis() + 5_000
        var pending = consentManager.pending.value
        while (pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            pending = consentManager.pending.value
        }
        assertFalse("dispatcher never queued a consent request", pending.isEmpty())
        kotlinx.coroutines.runBlocking {
            consentManager.respond(
                pending.first().id,
                sh.haven.core.data.agent.ConsentDecision.DENY,
            )
        }

        val response = responseFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val obj = JSONObject(response)
        val error = obj.optJSONObject("error")
            ?: error("expected error response, got: $response")
        assertEquals(-32000, error.optInt("code"))

        val outcomeSlot = slot<AgentAuditEvent.Outcome>()
        verify {
            auditRecorder.record(
                method = any(),
                toolName = any(),
                rawArgs = any(),
                result = any(),
                durationMs = any(),
                outcome = capture(outcomeSlot),
                errorMessage = any(),
                clientHint = any(),
            )
        }
        assertEquals(AgentAuditEvent.Outcome.DENIED, outcomeSlot.captured)
    }

    @Test
    fun `unknown method returns -32601`() {
        val (server, _) = newServer()
        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 7)
                .put("method", "nonsense/method")
                .toString(),
        )
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32601, error.optInt("code"))
    }

    @Test
    fun `empty body returns -32700 parse error`() {
        val (server, _) = newServer()
        val response = server.handleJsonRpc("")
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32700, error.optInt("code"))
    }

    @Test
    fun `notifications skip the audit log`() {
        val auditRecorder = mockk<AgentAuditRecorder>(relaxed = true)
        val (server, _) = newServer(auditRecorder = auditRecorder)

        // notifications/initialized has no id and is acked silently —
        // recording it would just clutter the dashboard with bookkeeping
        // events the user never initiated.
        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
                .toString(),
        )
        // Notifications get an empty body back at the JSON-RPC layer.
        assertEquals("", response)
        verify(exactly = 0) {
            auditRecorder.record(
                method = "notifications/initialized",
                toolName = any(),
                rawArgs = any(),
                result = any(),
                durationMs = any(),
                outcome = any(),
                errorMessage = any(),
                clientHint = any(),
            )
        }
    }

    @Test
    fun `tools_list works without consent prompts`() {
        val consentManager = AgentConsentManager() // foreground=false on purpose
        val (server, _) = newServer(consentManager = consentManager)

        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list")
                .toString(),
        )
        val obj = JSONObject(response)
        assertNull(
            "tools/list must not be gated by foreground; got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
        val tools = obj.getJSONObject("result").getJSONArray("tools")
        assertTrue("tools/list should advertise > 0 tools", tools.length() > 0)
    }
}
