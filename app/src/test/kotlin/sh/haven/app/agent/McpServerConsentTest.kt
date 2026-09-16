package sh.haven.app.agent

import android.content.Context
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
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

    /**
     * The client name every test in this suite uses for its `initialize`
     * step. Its pairing-token hash is pre-populated in the prefs mock so
     * a bearer-authenticated `initialize` short-circuits the pairing
     * prompt and tests can focus on the per-tool consent flow
     * (#mcp-backbone Stage 3).
     */
    private val testClientName = "test-host"
    private val testToken = "test-pairing-token"
    private val testTokenHash = sha256HexOf(testToken)

    /** Session id minted by [pair]; carried on subsequent requests the way
     *  a real streamable-HTTP client echoes `Mcp-Session-Id`. */
    private var sid: String? = null

    private fun newServer(
        consentManager: AgentConsentManager = AgentConsentManager(),
        auditRecorder: AgentAuditRecorder = mockk(relaxed = true),
        pairedClients: Set<String> = setOf(testClientName),
        standingPolicyRepository: sh.haven.core.data.repository.StandingPolicyRepository = mockk(relaxed = true),
    ): Pair<McpServer, AgentAuditRecorder> {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        // The allowlist mirror seeded into the server, plus the pairing-token
        // hashes that actually authenticate a client (#mcp-backbone Stage 3):
        // every name in [pairedClients] is given the shared test token.
        every { prefs.mcpAllowedClients } returns flowOf(pairedClients)
        every { prefs.mcpClientTokenHashes } returns
            flowOf(pairedClients.associateWith { testTokenHash })
        coEvery { prefs.addMcpAllowedClient(any()) } returns Unit

        val server = McpServer(
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
            preferencesRepository = prefs,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            auditRecorder = auditRecorder,
            consentManager = consentManager,
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
            usbBroker = mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
            usbIpServer = mockk<sh.haven.core.usb.UsbIpServer>(relaxed = true),
            usbDriveVmManager = mockk<sh.haven.app.usb.UsbDriveVmManager>(relaxed = true),
            umlRecoveryManager = mockk<sh.haven.app.usb.UmlRecoveryManager>(relaxed = true),
            presentationManager = sh.haven.core.data.agent.AgentPresentationManager(),
            havenUiBridge = mockk(relaxed = true),
            standingPolicyEnforcer = StandingPolicyEnforcer(standingPolicyRepository),
            standingPolicyRepository = standingPolicyRepository,
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
        // #337 mechanism 3: an unanswered consent HOLDS for the wait
        // budget. Shrink it so no-foreground tests take ~2s, not ~55s.
        server.consentWaitMs = 2_000
        return server to auditRecorder
    }

    /**
     * Send a spec-shaped `initialize` request so the dispatch-time
     * pairing gate sees the test client as paired. Most tests start
     * with this; the pairing-gate tests below send their own variant
     * to exercise the unpaired path.
     */
    private fun initBody(clientName: String = testClientName): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 0)
            .put("method", "initialize")
            .put("params", JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("clientInfo", JSONObject()
                    .put("name", clientName)
                    .put("version", "1.0")))
            .toString()

    private fun McpServer.pair(clientName: String = testClientName) {
        // A real client authenticates its initialize with the pairing token
        // minted at first pairing; the minted session id is echoed on every
        // later request (#mcp-backbone Stage 3).
        val outcome = handleJsonRpc(initBody(clientName), requestSessionId = null, bearerToken = testToken)
        sid = outcome.responseSessionId
    }

    /** Dispatch [body] the way a paired client would: carrying the session
     *  id [pair] minted (null before pairing — the unauthenticated path). */
    private fun McpServer.call(body: String): String = handleJsonRpc(body, sid).body

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
        // Pair first — the pairing gate would otherwise return -32001
        // before the per-tool consent gate gets a chance to run. Drop
        // the audit records left behind by `initialize` so the verify
        // block below only sees the tools/call event.
        server.pair()
        clearMocks(auditRecorder, recordedCalls = true, answers = false)

        val response = server.call(
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
        server.pair()
        clearMocks(auditRecorder, recordedCalls = true, answers = false)

        // Spawn the call on a background thread so we can race the
        // "user taps Deny" response against the dispatcher's blocking
        // wait inside requestConsent.
        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            server.call(
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
        server.pair()
        val response = server.call(
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
        val response = server.call("")
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
        val response = server.call(
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
        // Pair first so the dispatch-time pairing gate doesn't block
        // the listing — once paired, tools/list itself never prompts.
        server.pair()

        val response = server.call(
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

    // --- Pairing gate ---

    @Test
    fun `initialize with a valid pairing token returns success without prompting`() {
        val consentManager = AgentConsentManager() // foreground=false — would deny pairing if it ran
        val (server, _) = newServer(consentManager = consentManager)

        val outcome = server.handleJsonRpc(initBody(), requestSessionId = null, bearerToken = testToken)
        val obj = JSONObject(outcome.body)
        assertNull(
            "token-paired client should initialize cleanly, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
        val result = obj.getJSONObject("result")
        assertEquals("haven-agent", result.getJSONObject("serverInfo").getString("name"))
        // No prompt should have queued on the consent manager.
        assertTrue(consentManager.pending.value.isEmpty())
    }

    @Test
    fun `legacy name-only allowlist membership no longer skips the pairing prompt`() {
        // #mcp-backbone Stage 3 (one-time re-pair migration): a client name
        // in the pre-token allowlist arriving WITHOUT its bearer token gets
        // the pairing prompt — foreground=false fails it closed. A bare
        // clientInfo.name is spoofable, so it is not a credential.
        val consentManager = AgentConsentManager()
        val (server, _) = newServer(consentManager = consentManager)

        val outcome = server.handleJsonRpc(initBody(), requestSessionId = null, bearerToken = null)
        val error = JSONObject(outcome.body).optJSONObject("error")
            ?: error("expected re-pair prompt failure, got: ${outcome.body}")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `a wrong bearer token does not authenticate a paired name`() {
        val consentManager = AgentConsentManager()
        val (server, _) = newServer(consentManager = consentManager)

        val outcome = server.handleJsonRpc(initBody(), requestSessionId = null, bearerToken = "not-the-token")
        val error = JSONObject(outcome.body).optJSONObject("error")
            ?: error("expected pairing failure for a bad token, got: ${outcome.body}")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `initialize from an unpaired client without foreground returns -32001`() {
        val consentManager = AgentConsentManager() // foreground=false — pairing fails closed
        val (server, _) = newServer(
            consentManager = consentManager,
            pairedClients = emptySet(),
        )

        val response = server.call(initBody("rogue-client"))
        val obj = JSONObject(response)
        val error = obj.optJSONObject("error")
            ?: error("expected error response, got: $response")
        assertEquals(-32001, error.optInt("code"))
        assertTrue(
            "message should mention pairing, got: ${error.optString("message")}",
            error.optString("message").contains("pair", ignoreCase = true),
        )
    }

    @Test
    fun `initialize with missing clientInfo name returns -32002`() {
        val (server, _) = newServer()

        val response = server.call(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 0)
                .put("method", "initialize")
                .put("params", JSONObject().put("protocolVersion", "2025-06-18"))
                .toString(),
        )
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32002, error.optInt("code"))
    }

    @Test
    fun `tools_list before initialize returns -32001`() {
        val (server, _) = newServer()

        val response = server.call(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list")
                .toString(),
        )
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `tools_call before initialize returns -32001`() {
        val (server, _) = newServer()

        val response = server.call(
            toolsCallBody("disconnect_profile", JSONObject().put("profileId", "p1")),
        )
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `ping is reachable before pairing`() {
        // Lets a client probe the server without identifying itself.
        // Stays cheap so it can't be used to enumerate state.
        val (server, _) = newServer()

        val response = server.call(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 9)
                .put("method", "ping")
                .toString(),
        )
        val obj = JSONObject(response)
        assertNull(
            "ping should succeed pre-pairing, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
    }

    @Test
    fun `initialize with new clientName triggers pairing - ALLOW persists`() {
        val consentManager = AgentConsentManager().apply { setForegroundActive(true) }
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        // Start with an empty allowlist — a fresh install scenario.
        every { prefs.mcpAllowedClients } returns flowOf(emptySet())
        coEvery { prefs.addMcpAllowedClient(any()) } returns Unit
        val auditRecorder = mockk<AgentAuditRecorder>(relaxed = true)
        val server = McpServer(
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
            preferencesRepository = prefs,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            auditRecorder = auditRecorder,
            consentManager = consentManager,
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
        // #337 mechanism 3: an unanswered consent HOLDS for the wait
        // budget. Shrink it so no-foreground tests take ~2s, not ~55s.
        server.consentWaitMs = 2_000

        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            server.call(initBody("fresh-client"))
        }

        // Wait for the pairing prompt to queue, then ALLOW it.
        val deadline = System.currentTimeMillis() + 5_000
        var pending = consentManager.pending.value
        while (pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            pending = consentManager.pending.value
        }
        assertFalse("pairing prompt never queued", pending.isEmpty())
        val req = pending.first()
        assertEquals(AgentConsentManager.PAIRING_TOOL_NAME, req.toolName)
        kotlinx.coroutines.runBlocking {
            consentManager.respond(req.id, sh.haven.core.data.agent.ConsentDecision.ALLOW)
        }

        val response = responseFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val obj = JSONObject(response)
        assertNull(
            "initialize should succeed after pairing ALLOW, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
        // And the persisted allowlist should have been written.
        kotlinx.coroutines.runBlocking {
            io.mockk.coVerify { prefs.addMcpAllowedClient("fresh-client") }
        }
        // The approval minted a pairing token: returned ONCE in _meta, and
        // only its hash persisted (#mcp-backbone Stage 3).
        val token = obj.getJSONObject("result").getJSONObject("_meta")
            .getString("sh.haven/pairingToken")
        assertTrue("pairing token should be non-trivial", token.length >= 32)
        kotlinx.coroutines.runBlocking {
            io.mockk.coVerify { prefs.setMcpClientTokenHash("fresh-client", sha256HexOf(token)) }
        }
    }

    // --- Mcp-Session-Id streamable-HTTP session flow ---
    //
    // The pre-fix behaviour was: handleClient parsed the request body
    // and called dispatch with the global `lastClientHint`. On Haven
    // restart `lastClientHint` resets to null, but Claude Code's
    // cached transport keeps sending tools/* calls without re-doing
    // `initialize`. Every call hit dispatch's "not paired" gate,
    // surfaced -32001 to the user, and stayed wedged until they
    // dropped the whole Claude Code session (which forced the
    // transport to re-init).
    //
    // The fix wires `Mcp-Session-Id` per the 2025-06-18 streamable-
    // HTTP spec. These tests pin the new behaviour:
    //  - successful `initialize` returns a UUID in responseSessionId
    //  - a request carrying that UUID resolves clientName from the
    //    session map (not from the global lastClientHint)
    //  - a request carrying an unknown UUID returns httpStatus=404
    //    so the client per spec re-initialises automatically
    //  - a request with neither a session id nor a bearer token is
    //    rejected — the old lastClientHint fallback authenticated a
    //    server-side sticky NAME, which any peer on the same carrier
    //    could free-ride (#mcp-backbone Stage 3)

    @Test
    fun `initialize returns Mcp-Session-Id in JsonRpcOutcome`() {
        val (server, _) = newServer()
        val outcome = server.handleJsonRpc(initBody(), requestSessionId = null, bearerToken = testToken)
        assertEquals(200, outcome.httpStatus)
        assertEquals(
            "initialize should mint a session id",
            true,
            !outcome.responseSessionId.isNullOrBlank(),
        )
    }

    @Test
    fun `request with valid session id resolves without falling back to lastClientHint`() {
        val (server, _) = newServer()
        val initOutcome = server.handleJsonRpc(initBody(), requestSessionId = null, bearerToken = testToken)
        val sid = initOutcome.responseSessionId
            ?: error("initialize did not return a session id")

        // Spec-shaped tools/list call carrying the session id. Use
        // tools/list (no extra consent gate) to keep the test focused
        // on the session resolution path.
        val toolsList = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 7)
            .put("method", "tools/list")
            .toString()
        val outcome = server.handleJsonRpc(toolsList, requestSessionId = sid)
        assertEquals(200, outcome.httpStatus)
        val obj = JSONObject(outcome.body)
        assertNull(
            "tools/list with a valid session id should succeed, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
    }

    @Test
    fun `unknown session id returns 404 to trigger client re-initialize`() {
        val (server, _) = newServer()
        // Don't initialize first — the session map starts empty, so
        // any presented id is unknown.
        val toolsList = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 11)
            .put("method", "tools/list")
            .toString()
        val outcome = server.handleJsonRpc(
            toolsList,
            requestSessionId = "stale-id-from-a-previous-server-instance",
        )
        assertEquals(
            "unknown session id must produce HTTP 404 so the client re-initialises",
            404,
            outcome.httpStatus,
        )
        assertEquals("404 body should be empty per spec hint", "", outcome.body)
        assertNull("no new session id minted on a 404", outcome.responseSessionId)
    }

    @Test
    fun `request with neither session id nor token is rejected even after another client paired`() {
        // The old lastClientHint fallback: after ANY client initialized, a
        // request with no credential at all inherited that client's identity.
        // A rogue peer on the same multiplexed carrier (LAN, the -R tunnel
        // endpoint) could free-ride it. Now it must fail closed
        // (#mcp-backbone Stage 3).
        val (server, _) = newServer()
        server.pair() // a legit client pairs with its token
        val toolsList = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 13)
            .put("method", "tools/list")
            .toString()
        val outcome = server.handleJsonRpc(toolsList, requestSessionId = null)
        val error = JSONObject(outcome.body).optJSONObject("error")
            ?: error("expected -32001 for a credential-less request, got: ${outcome.body}")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `a bearer token authenticates a request without any session id`() {
        // Headless/stateless callers (curl, cron agents) can present the
        // token alone — no initialize round-trip needed in this process.
        val (server, _) = newServer()
        val toolsList = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 14)
            .put("method", "tools/list")
            .toString()
        val outcome = server.handleJsonRpc(toolsList, requestSessionId = null, bearerToken = testToken)
        assertEquals(200, outcome.httpStatus)
        val obj = JSONObject(outcome.body)
        assertNull(
            "token-bearing request should succeed without a session, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
    }

    // --- resources/* (ui://haven/screen) ---

    private fun rpc(method: String, params: JSONObject? = null): String =
        JSONObject().put("jsonrpc", "2.0").put("id", 30).put("method", method)
            .apply { if (params != null) put("params", params) }.toString()

    @Test
    fun `initialize advertises the resources capability`() {
        val (server, _) = newServer()
        val response = server.handleJsonRpc(initBody(), requestSessionId = null, bearerToken = testToken).body
        val caps = JSONObject(response).getJSONObject("result").getJSONObject("capabilities")
        assertTrue("resources capability must be advertised", caps.has("resources"))
    }

    @Test
    fun `resources_list returns the Haven UI screen resource`() {
        val (server, _) = newServer()
        server.pair()
        val response = server.call(rpc("resources/list"))
        val resources = JSONObject(response).getJSONObject("result").getJSONArray("resources")
        assertEquals(1, resources.length())
        assertEquals("ui://haven/screen", resources.getJSONObject(0).getString("uri"))
    }

    @Test
    fun `resources_read rejects an unknown uri`() {
        val (server, _) = newServer()
        server.pair()
        val response = server.call(
            rpc("resources/read", JSONObject().put("uri", "ui://haven/bogus")),
        )
        assertEquals(-32602, JSONObject(response).getJSONObject("error").getInt("code"))
    }

    @Test
    fun `resources_read of the screen with no foreground is denied by consent`() {
        // The screen resource shares capture_haven_ui's ONCE_PER_SESSION gate;
        // a default AgentConsentManager has foregroundActive=false, so it fails
        // closed (DENIED) before the bridge is ever touched.
        val (server, _) = newServer()
        server.pair()
        val response = server.call(
            rpc("resources/read", JSONObject().put("uri", "ui://haven/screen")),
        )
        assertEquals(-32000, JSONObject(response).getJSONObject("error").getInt("code"))
    }

    // --- Tier-3 standing policies at the consent gate ---

    private fun policyRepo(vararg policies: sh.haven.core.data.db.entities.StandingPolicy):
        sh.haven.core.data.repository.StandingPolicyRepository {
        val repo = mockk<sh.haven.core.data.repository.StandingPolicyRepository>(relaxed = true)
        coEvery { repo.activePolicies(any()) } returns policies.toList()
        return repo
    }

    private fun testPolicy(tool: String, rate: Int = 10) =
        sh.haven.core.data.db.entities.StandingPolicy(
            clientHint = testClientName,
            description = "test grant",
            toolNamesJson = """["$tool"]""",
            maxCallsPerMinute = rate,
            expiresAt = Long.MAX_VALUE,
        )

    @Test
    fun `a standing policy lets a covered non-NEVER tool run without a prompt`() {
        // foregroundActive=false would otherwise fail the prompt closed
        // (-32000, as the first test in this file pins). With an installed
        // policy covering the tool for this client, the gate is skipped and
        // the handler runs (it may fail on its relaxed mocks — any error but
        // the consent codes proves the prompt never happened).
        val (server, _) = newServer(
            standingPolicyRepository = policyRepo(testPolicy("disconnect_profile")),
        )
        server.pair()
        val response = server.call(
            toolsCallBody("disconnect_profile", JSONObject().put("profileId", "p1")),
        )
        val error = JSONObject(response).optJSONObject("error")
        if (error != null) {
            val code = error.getInt("code")
            assertTrue(
                "policy-covered call must not hit the consent gate, got $code: ${error.optString("message")}",
                code != -32000 && code != -32012,
            )
        }
    }

    @Test
    fun `beyond the rate ceiling calls fall back to the consent prompt`() {
        val (server, _) = newServer(
            standingPolicyRepository = policyRepo(testPolicy("disconnect_profile", rate = 1)),
        )
        server.pair()
        // First call consumes the window…
        server.call(toolsCallBody("disconnect_profile", JSONObject().put("profileId", "p1")))
        // …second call exceeds it → prompt path → backgrounded → DENY.
        val second = server.call(
            toolsCallBody("disconnect_profile", JSONObject().put("profileId", "p1")),
        )
        assertEquals(-32000, JSONObject(second).getJSONObject("error").getInt("code"))
    }

    @Test
    fun `a policy for another client does not skip the prompt`() {
        val foreign = testPolicy("disconnect_profile").copy(clientHint = "someone-else")
        val (server, _) = newServer(standingPolicyRepository = policyRepo(foreign))
        server.pair()
        val response = server.call(
            toolsCallBody("disconnect_profile", JSONObject().put("profileId", "p1")),
        )
        assertEquals(-32000, JSONObject(response).getJSONObject("error").getInt("code"))
    }
}

/** Same digest the server stores for a pairing token — SHA-256 as lowercase hex. */
internal fun sha256HexOf(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
