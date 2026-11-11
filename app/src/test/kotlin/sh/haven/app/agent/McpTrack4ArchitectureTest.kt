package sh.haven.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Track 4.1 architectural regression tests:
 *  - Intra-provider and cross-provider duplicate tool-name detection (retiring
 *    the silent duplicate `list_mail_folders` registration in MailToolProvider).
 *  - Generated `docs/mcp-tools.md` coverage of `openai_list_models` and `openai_chat`.
 *  - `McpServer` DI constructor slimming (`@Inject` constructor <= 12 params,
 *    delegating tool wiring to `@Singleton McpTools` + `@IntoSet ToolProvider`s).
 *  - Extraction of cohesive domain providers out of `McpTools.kt` so the 64 KiB
 *    `toolsPart3()` / `toolsPart4()` split workarounds are retired.
 */
class McpTrack4ArchitectureTest {

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: error("Repo root not found from ${File("").absolutePath}")
        }
        return dir
    }

    @Test
    fun `MailToolProvider registers list_mail_folders once and uses duplicate-checked toolMapOf`() {
        val mailSrc = File(repoRoot(), "app/src/main/kotlin/sh/haven/app/agent/MailToolProvider.kt").readText()
        val occurrences = Regex(""""list_mail_folders"\s+to\s+ToolHandler\(""").findAll(mailSrc).count()
        assertEquals("list_mail_folders must be registered once in MailToolProvider", 1, occurrences)
        assertTrue(
            "MailToolProvider should build its map via duplicate-checked toolMapOf(...)",
            mailSrc.contains("toolMapOf("),
        )
    }

    @Test
    fun `ToolProvider exposes duplicate-rejecting toolMapOf and McpTools rejects cross-provider collisions`() {
        val toolProviderSrc = File(repoRoot(), "app/src/main/kotlin/sh/haven/app/agent/ToolProvider.kt").readText()
        assertTrue(
            "ToolProvider.kt must define toolMapOf(...) rejecting duplicate tool names",
            toolProviderSrc.contains("fun toolMapOf("),
        )
        val mcpToolsSrc = File(repoRoot(), "app/src/main/kotlin/sh/haven/app/agent/McpTools.kt").readText()
        assertTrue(
            "McpTools must reject duplicate tool names across inline + ToolProvider registrations",
            mcpToolsSrc.contains("Duplicate MCP tool registration"),
        )

        // Behavioral intra-provider check: toolMapOf throws on duplicate names.
        val dummyHandler = ToolHandler(
            description = "dummy",
            inputSchema = emptyObjectSchema(),
        ) { org.json.JSONObject() }
        val intraCollision = runCatching {
            toolMapOf("dup_tool" to dummyHandler, "dup_tool" to dummyHandler)
        }.exceptionOrNull()
        assertTrue(
            "toolMapOf must fail fast on duplicate tool name, got $intraCollision",
            intraCollision is IllegalStateException &&
                intraCollision.message?.contains("Duplicate MCP tool registration: dup_tool") == true,
        )

        // Behavioral cross-provider check: McpTools throws when a provider collides with another registration.
        val collidingProvider = object : ToolProvider {
            override fun tools(): Map<String, ToolHandler> = toolMapOf("list_connections" to dummyHandler)
        }
        val crossCollision = runCatching {
            McpTools(
                context = io.mockk.mockk(relaxed = true),
                connectionRepository = io.mockk.mockk(relaxed = true),
                portForwardRepository = io.mockk.mockk(relaxed = true),
                sshSessionManager = io.mockk.mockk(relaxed = true),
                sessionManagerRegistry = io.mockk.mockk(relaxed = true),
                reticulumSessionManager = io.mockk.mockk(relaxed = true),
                reticulumForwardServer = io.mockk.mockk(relaxed = true),
                rcloneClient = io.mockk.mockk(relaxed = true),
                mailSessionManager = io.mockk.mockk(relaxed = true),
                sftpStreamServer = io.mockk.mockk(relaxed = true),
                hlsStreamServer = io.mockk.mockk(relaxed = true),
                ffmpegExecutor = io.mockk.mockk(relaxed = true),
                preferencesRepository = io.mockk.mockk(relaxed = true),
                terminalFontInstaller = io.mockk.mockk(relaxed = true),
                localSessionManager = io.mockk.mockk(relaxed = true),
                agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
                transportSelector = io.mockk.mockk(relaxed = true),
                workspaceRepository = io.mockk.mockk(relaxed = true),
                workspaceLauncher = io.mockk.mockk(relaxed = true),
                tunnelConfigRepository = io.mockk.mockk(relaxed = true),
                tunnelManager = io.mockk.mockk(relaxed = true),
                terminalSessionRegistry = sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
                portKnocker = io.mockk.mockk(relaxed = true),
                spaSender = io.mockk.mockk(relaxed = true),
                connectionLogRepository = io.mockk.mockk(relaxed = true),
                servedFileTracker = sh.haven.core.data.agent.ServedFileTracker(),
                syncProfileRepository = io.mockk.mockk(relaxed = true),
                mailRuleRepository = io.mockk.mockk(relaxed = true),
                mailWatchManager = io.mockk.mockk(relaxed = true),
                agentActivityHolder = sh.haven.core.data.agent.AgentActivityHolder(),
                terminalInputQueue = io.mockk.mockk(relaxed = true),
                prootInstallLogRepository = io.mockk.mockk(relaxed = true),
                sshKeyRepository = io.mockk.mockk(relaxed = true),
                knownHostDao = io.mockk.mockk(relaxed = true),
                stepCaConfigRepository = io.mockk.mockk(relaxed = true),
                totpSecretRepository = io.mockk.mockk(relaxed = true),
                ageIdentityRepository = io.mockk.mockk(relaxed = true),
                desktopSessionRegistry = io.mockk.mockk(relaxed = true),
                aiRouteRegistry = sh.haven.core.openai.AiRouteRegistry(),
                usbBroker = io.mockk.mockk(relaxed = true),
                usbIpServer = io.mockk.mockk(relaxed = true),
                usbDriveVmManager = io.mockk.mockk(relaxed = true),
                umlRecoveryManager = io.mockk.mockk(relaxed = true),
                presentationManager = sh.haven.core.data.agent.AgentPresentationManager(),
                havenUiBridge = io.mockk.mockk(relaxed = true),
                standingPolicyRepository = io.mockk.mockk(relaxed = true),
                mcpTunnelManager = io.mockk.mockk(relaxed = true),
                mcpStatusHolder = sh.haven.core.data.agent.McpStatusHolder(),
                extraProviders = setOf(collidingProvider),
            )
        }.exceptionOrNull()
        assertTrue(
            "McpTools must fail fast on cross-provider duplicate tool registration, got $crossCollision",
            crossCollision is IllegalStateException &&
                crossCollision.message?.contains("Duplicate MCP tool registration: list_connections") == true,
        )
    }

    @Test
    fun `docs mcp-tools_md documents openai_list_models and openai_chat`() {
        val doc = File(repoRoot(), "docs/mcp-tools.md").readText()
        assertTrue("docs/mcp-tools.md must include openai_list_models", doc.contains("<code>openai_list_models</code>"))
        assertTrue("docs/mcp-tools.md must include openai_chat", doc.contains("<code>openai_chat</code>"))
    }

    @Test
    fun `McpServer Inject constructor is slimmed and McpToolProviderModule binds ToolProviders IntoSet`() {
        val injectCtors = McpServer::class.java.declaredConstructors.filter {
            it.isAnnotationPresent(Inject::class.java)
        }
        assertEquals("McpServer must have a single @Inject constructor", 1, injectCtors.size)
        val injectCtor = injectCtors.single()
        assertTrue(
            "McpServer @Inject constructor had ${injectCtor.parameterCount} params (expected <= 12)",
            injectCtor.parameterCount <= 12,
        )
        assertTrue(
            "McpServer @Inject constructor must inject McpTools directly",
            injectCtor.parameterTypes.any { it.simpleName == "McpTools" },
        )

        val moduleFile = File(repoRoot(), "app/src/main/kotlin/sh/haven/app/agent/McpToolProviderModule.kt")
        assertTrue("McpToolProviderModule.kt must exist", moduleFile.exists())
        val moduleSrc = moduleFile.readText()
        assertTrue("McpToolProviderModule must contribute @IntoSet ToolProvider bindings", moduleSrc.contains("@IntoSet"))
    }

    @Test
    fun `McpTools extracts cohesive domain providers and retires toolsPart3 and toolsPart4`() {
        val agentDir = File(repoRoot(), "app/src/main/kotlin/sh/haven/app/agent")
        val expectedProviders = listOf(
            "HavenUiAndPolicyToolProvider.kt",
            "ProotAndGuestToolProvider.kt",
            "WorkspaceAndSnippetToolProvider.kt",
            "ConnectionsToolProvider.kt",
        )
        for (fileName in expectedProviders) {
            assertTrue("Expected extracted provider $fileName", File(agentDir, fileName).exists())
        }

        val mcpToolsFile = File(agentDir, "McpTools.kt")
        val lines = mcpToolsFile.readLines()
        assertTrue(
            "McpTools.kt has ${lines.size} lines (expected < 5800 after domain extraction)",
            lines.size < 5800,
        )
        val mcpToolsSrc = lines.joinToString("\n")
        assertFalse("toolsPart3() 64 KiB workaround should be retired", mcpToolsSrc.contains("fun toolsPart3("))
        assertFalse("toolsPart4() 64 KiB workaround should be retired", mcpToolsSrc.contains("fun toolsPart4("))
    }
}
