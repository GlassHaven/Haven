package sh.haven.app.agent

import android.content.Intent
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.app.tasker.TaskerPlugin
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.AgentPresentationManager
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.agent.McpStatusHolder
import sh.haven.core.data.agent.ServedFileTracker
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.mcp.HttpResponse
import sh.haven.core.mcp.McpError
import sh.haven.core.mcp.ParsedHttpRequest

class McpTrack2SecurityTest {

    private fun newServer(): McpServer {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true).also {
            every { it.mcpAllowedClients } returns flowOf(emptySet())
            every { it.mcpClientTokenHashes } returns flowOf(emptyMap())
        }
        return McpServer(
            context = mockk(relaxed = true),
            connectionRepository = mockk(relaxed = true),
            portForwardRepository = mockk(relaxed = true),
            sshSessionManager = mockk(relaxed = true),
            sessionManagerRegistry = mockk(relaxed = true),
            rcloneClient = mockk(relaxed = true),
            mailSessionManager = mockk(relaxed = true),
            sftpStreamServer = mockk(relaxed = true),
            hlsStreamServer = mockk(relaxed = true),
            ffmpegExecutor = mockk(relaxed = true),
            preferencesRepository = prefs,
            terminalFontInstaller = mockk(relaxed = true),
            localSessionManager = mockk(relaxed = true),
            auditRecorder = mockk(relaxed = true),
            consentManager = AgentConsentManager(),
            agentUiCommandBus = AgentUiCommandBus(),
            transportSelector = mockk(relaxed = true),
            workspaceRepository = mockk(relaxed = true),
            workspaceLauncher = mockk(relaxed = true),
            tunnelConfigRepository = mockk(relaxed = true),
            tunnelManager = mockk(relaxed = true),
            terminalSessionRegistry = sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            portKnocker = mockk(relaxed = true),
            spaSender = mockk(relaxed = true),
            connectionLogRepository = mockk(relaxed = true),
            servedFileTracker = mockk<ServedFileTracker>(relaxed = true),
            syncProfileRepository = mockk(relaxed = true),
            terminalInputQueue = mockk(relaxed = true),
            prootInstallLogRepository = mockk(relaxed = true),
            sshKeyRepository = mockk(relaxed = true),
            knownHostDao = mockk(relaxed = true),
            stepCaConfigRepository = mockk(relaxed = true),
            totpSecretRepository = mockk(relaxed = true),
            ageIdentityRepository = mockk(relaxed = true),
            desktopSessionRegistry = mockk(relaxed = true),
            aiRouteRegistry = sh.haven.core.openai.AiRouteRegistry(),
            usbBroker = mockk(relaxed = true),
            usbIpServer = mockk(relaxed = true),
            usbRedirServer = mockk(relaxed = true),
            usbDriveVmManager = mockk(relaxed = true),
            umlRecoveryManager = mockk(relaxed = true),
            presentationManager = AgentPresentationManager(),
            havenUiBridge = mockk(relaxed = true),
            standingPolicyEnforcer = StandingPolicyEnforcer(mockk(relaxed = true)),
            standingPolicyRepository = mockk(relaxed = true),
            mcpStatusHolder = McpStatusHolder(),
            mcpTunnelManager = mockk(relaxed = true),
            btSerialSessionManager = mockk(relaxed = true),
            bleSerialSessionManager = mockk(relaxed = true),
            usbSerialSessionManager = mockk(relaxed = true),
            headlessSshExec = mockk(relaxed = true),
            hardwareKeySource = mockk(relaxed = true),
            reticulumSessionManager = mockk(relaxed = true),
            reticulumForwardServer = mockk(relaxed = true),
            mailRuleRepository = mockk(relaxed = true),
            mailWatchManager = mockk(relaxed = true),
            openAiSessionManager = mockk(relaxed = true),
            tunnelResolver = mockk(relaxed = true),
            agentActivityHolder = mockk(relaxed = true),
        ).apply { setTrustLoopbackEnabled(true) }
    }

    @Test
    fun `dispatchHttpRequest on DEVICE origin rejects DNS-rebinding Host header`() {
        val server = newServer()
        val dispatchMethod = McpServer::class.java.getDeclaredMethod(
            "dispatchHttpRequest",
            ParsedHttpRequest::class.java,
            McpOrigin::class.java,
        ).apply { isAccessible = true }

        val rebindReq = ParsedHttpRequest(
            method = "POST",
            path = "/mcp",
            headers = mapOf("host" to "rebind.attacker.tld:8730"),
            body = """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
        )
        val res = dispatchMethod.invoke(server, rebindReq, McpOrigin.DEVICE) as HttpResponse
        assertEquals("DNS-rebound Host header must be rejected on loopback listener", 403, res.status)

        val validReq = ParsedHttpRequest(
            method = "POST",
            path = "/mcp",
            headers = mapOf("host" to "127.0.0.1:8730"),
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"local-cli","version":"1.0"}}}""",
        )
        val okRes = dispatchMethod.invoke(server, validReq, McpOrigin.DEVICE) as HttpResponse
        assertEquals(200, okRes.status)
    }

    @Test
    fun `McpServer carries no shared mutable lastClientHint field`() {
        val fields = McpServer::class.java.declaredFields.map { it.name }
        assertTrue(
            "Shared mutable lastClientHint must be replaced by per-request caller context",
            "lastClientHint" !in fields,
        )
    }

    @Test
    fun `present_web rejects non-http schemes such as file and javascript`() {
        val tools = McpToolsConsentTest().let {
            // Instantiate McpTools with a real AgentPresentationManager via reflection on newServer().tools
            val server = newServer()
            val toolsField = McpServer::class.java.getDeclaredField("tools").apply { isAccessible = true }
            toolsField.get(server) as McpTools
        }
        val presentWebMethod = McpTools::class.java.getDeclaredMethod(
            "presentWeb",
            JSONObject::class.java,
        ).apply { isAccessible = true }

        for (badUrl in listOf(
            "file:///data/data/sh.haven.app/databases/haven.db",
            "content://sh.haven.app.documents/root",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
        )) {
            try {
                try {
                    presentWebMethod.invoke(tools, JSONObject().put("url", badUrl))
                    fail("present_web must reject unsafe scheme: $badUrl")
                } catch (inv: java.lang.reflect.InvocationTargetException) {
                    throw inv.targetException
                }
            } catch (e: McpError) {
                assertEquals(-32602, e.code)
            }
        }

        val ok = presentWebMethod.invoke(tools, JSONObject().put("url", "http://127.0.0.1:8080/chart.html")) as JSONObject
        assertTrue(ok.getBoolean("presented"))
    }

    private fun mapBackedBundle(store: MutableMap<String, Any?> = mutableMapOf()): Bundle =
        mockk<Bundle>(relaxed = true).also { b ->
            every { b.putString(any(), any()) } answers { store[firstArg()] = secondArg<String?>() }
            every { b.getString(any()) } answers { store[firstArg()] as? String }
            every { b.putBoolean(any(), any()) } answers { store[firstArg()] = secondArg<Boolean>() }
            every { b.getBoolean(any(), any()) } answers { (store[firstArg()] as? Boolean) ?: secondArg() }
            every { b.getBoolean(any()) } answers { (store[firstArg()] as? Boolean) ?: false }
        }

    private fun mapBackedIntent(
        nestedBundle: Bundle? = null,
        flatStrings: Map<String, String> = emptyMap(),
        flatBooleans: Map<String, Boolean> = emptyMap(),
    ): Intent = mockk<Intent>(relaxed = true).also { i ->
        every { i.getBundleExtra(TaskerPlugin.EXTRA_BUNDLE) } returns nestedBundle
        every { i.getStringExtra(any()) } answers { flatStrings[firstArg()] }
        every { i.getBooleanExtra(any(), any()) } answers { flatBooleans[firstArg()] ?: secondArg() }
    }

    @Test
    fun `TaskerPlugin rejects unsigned flat-extra and forged-bundle broadcasts`() {
        val flatForged = mapBackedIntent(
            nestedBundle = null,
            flatStrings = mapOf(
                TaskerPlugin.BUNDLE_PROFILE_ID to "prod-ssh",
                TaskerPlugin.BUNDLE_COMMAND to "rm -rf /",
            ),
        )
        val nestedUnsigned = mapBackedIntent(
            nestedBundle = mapBackedBundle(
                mutableMapOf(
                    TaskerPlugin.BUNDLE_PROFILE_ID to "prod-ssh",
                    TaskerPlugin.BUNDLE_COMMAND to "id",
                ),
            ),
        )
        assertNull(
            "Flat intent without capability token must be rejected",
            TaskerPlugin.verifiedConfigFrom(flatForged, secret = "device-secret-abc"),
        )
        assertNull(
            "Nested bundle without capability token must be rejected",
            TaskerPlugin.verifiedConfigFrom(nestedUnsigned, secret = "device-secret-abc"),
        )
        assertNull(
            "Any broadcast when Tasker plugin has never been configured (secret=null) must be rejected",
            TaskerPlugin.verifiedConfigFrom(nestedUnsigned, secret = null),
        )

        // Legitimate bundle minted with device secret survives %var substitution in command!
        val signedStore = mutableMapOf<String, Any?>()
        val signedBundle = TaskerPlugin.populateBundle(
            target = mapBackedBundle(signedStore),
            profileId = "prod-ssh",
            profileLabel = "Prod",
            command = "echo %myvar",
            overlay = false,
            block = true,
            secret = "device-secret-abc",
        )
        // Simulate Tasker substituting %myvar -> hello at fire time
        signedBundle.putString(TaskerPlugin.BUNDLE_COMMAND, "echo hello")
        val legitimateIntent = mapBackedIntent(nestedBundle = signedBundle)
        val verified = TaskerPlugin.verifiedConfigFrom(legitimateIntent, secret = "device-secret-abc")
        assertNotNull("Signed bundle with %var-substituted command must verify", verified)
        assertEquals("echo hello", verified!!.getString(TaskerPlugin.BUNDLE_COMMAND))

        // Tampering with profileId or overlay invalidates the capability token
        signedBundle.putString(TaskerPlugin.BUNDLE_PROFILE_ID, "other-ssh")
        assertNull(
            "Mutating profileId must invalidate capability token",
            TaskerPlugin.verifiedConfigFrom(legitimateIntent, secret = "device-secret-abc"),
        )
    }
}
