package sh.haven.app.agent

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.ConnectionProfile
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
 * Pin the consent metadata + summary builders for every registered MCP
 * tool. The audit log relies on consent-gating decisions made *before*
 * the handler runs, so a regression here (e.g. dropping a level back to
 * NEVER on a destructive tool, or breaking the summary template) would
 * silently weaken the wheel-stays-with-the-user invariant from
 * VISION.md §85.
 *
 * We construct a real [McpTools] with relaxed mocks so the registry is
 * the actual production registry — same code path the dispatcher
 * consults via `consentFor(name)`.
 */
class McpToolsConsentTest {

    private fun newTools(
        preferencesRepository: UserPreferencesRepository = mockk(relaxed = true),
    ): McpTools {
        val connectionRepository = mockk<ConnectionRepository>(relaxed = true)
        // profileLabel(...) hits this; return a stable label so the
        // summary templates that interpolate it are predictable.
        coEvery { connectionRepository.getById(any()) } returns
            ConnectionProfile(
                id = "p1",
                label = "test-host",
                host = "10.0.0.1",
                username = "u",
            )
        return McpTools(
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
            preferencesRepository = preferencesRepository,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
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
            usbBroker = mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
            usbIpServer = mockk<sh.haven.core.usb.UsbIpServer>(relaxed = true),
            usbDriveVmManager = mockk<sh.haven.app.usb.UsbDriveVmManager>(relaxed = true),
            presentationManager = sh.haven.core.data.agent.AgentPresentationManager(),
            havenUiBridge = mockk(relaxed = true),
            standingPolicyRepository = mockk(relaxed = true),
            mcpTunnelManager = mockk(relaxed = true),
            // Real holder: get_app_info reads typed StateFlow values a relaxed
            // mock answers with bare Objects (ClassCastException).
            mcpStatusHolder = sh.haven.core.data.agent.McpStatusHolder(),
            reticulumSessionManager = mockk(relaxed = true),
            reticulumForwardServer = mockk(relaxed = true),
            mailRuleRepository = mockk(relaxed = true),
            mailWatchManager = mockk(relaxed = true),
            agentActivityHolder = mockk(relaxed = true),
        )
    }

    @Test
    fun `read-only and tap-equivalent tools are NEVER`() {
        val tools = newTools()
        for (name in listOf(
            "get_app_info",
            "list_connections",
            "list_sessions",
            "list_rclone_remotes",
            // Unified backend-agnostic listing (#127). The two
            // per-backend tools below are deprecated aliases pointing
            // at this one.
            "list_directory",
            "list_rclone_directory",
            "list_sftp_directory",
            "read_terminal_scrollback",
            // Navigation + dialog-staging: open a screen at a path or
            // stage a dialog with prefilled args. The destructive action
            // (transcode, in the convert case) still requires the user
            // to tap Convert in the dialog. Tap-equivalent, no consent.
            "navigate_sftp_browser",
            "focus_terminal_session",
            "open_convert_dialog_with_args",
            // Listing the user's saved workspaces is read-only and
            // surfaces no secrets — no consent.
            "list_workspaces",
            // Enumerating attached USB devices is read-only; descriptor
            // strings stay hidden until permission is separately granted.
            "list_usb_devices",
            // raise_notification posts to a dedicated, user-mutable
            // channel and the result is deliberately user-visible — same
            // shape as present_media: agent→user surface, no prompt.
            "raise_notification",
            // Enumerating installed guest apps reads .desktop files; no
            // secrets, no side effects — read-only, no prompt.
            "list_guest_apps",
            // Observing the consent queue is read-only and prompting for
            // permission to observe a prompt would deadlock (#355). It
            // reveals only requests the agent itself caused.
            "get_pending_consent",
            // Enumerating active serial↔TCP bridges is read-only.
            "list_serial_bridges",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, c.level)
        }
    }

    @Test
    fun `connection lifecycle tools are ONCE_PER_SESSION`() {
        val tools = newTools()
        for (name in listOf(
            "disconnect_profile",
            "add_port_forward",
            "remove_port_forward",
            // compose_workspace can launch many sessions in one go;
            // gate it once per (client, tool) so the user grants the
            // bundle and isn't re-prompted per-item underneath.
            "compose_workspace",
            // send_terminal_input types into a live terminal; gated once per
            // (client, tool) so an agent isn't re-prompted on every keystroke
            // batch. Shipped behaviour since the queue_terminal_input split;
            // this assertion was masked while the suite didn't compile.
            "send_terminal_input",
            // request_usb_permission pops the system grant dialog + opens
            // the device; gate once per (client, tool) so the agent isn't
            // re-prompted each time it reopens the same device.
            "request_usb_permission",
            // usb_attach_to_guest opens the device + exposes it to the guest;
            // a session-scoped grant matches the permission grant above.
            "usb_attach_to_guest",
            // test_spa sends a single SPA packet; one grant per (client, tool)
            // mirrors test_port_knock.
            "test_spa",
            // read_logcat surfaces system-wide PII; gate per (client, tool)
            // so a tester session grants once and isn't re-prompted across
            // the before/after captures that a single review needs.
            "read_logcat",
            // bridge_serial_to_tcp exposes a serial device on a loopback port;
            // stop tears it down. Session-scoped like add/remove_port_forward.
            "bridge_serial_to_tcp",
            "stop_serial_bridge",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals(
                "$name should re-prompt only once per (client, tool)",
                ConsentLevel.ONCE_PER_SESSION,
                c.level,
            )
        }
    }

    @Test
    fun `destructive write tools are EVERY_CALL`() {
        val tools = newTools()
        for (name in listOf(
            // Unified backend-agnostic write/delete (#127).
            "upload_file",
            "delete_file",
            // Deprecated SSH-only originals — kept for backward compat,
            // still EVERY_CALL.
            "upload_file_to_sftp",
            "delete_sftp_file",
            "convert_file",
            "set_terminal_font_from_url",
            "install_apk_from_url",
            "enable_wireless_adb",
            // Raw USB I/O to a device — re-confirm every transfer.
            "usb_control_transfer",
            "usb_bulk_transfer",
            // set_spa writes SPA key material onto a profile.
            "set_spa",
            // Arbitrary remote exec on a saved SSH profile (#367) — the
            // per-call prompt is what a standing policy scopes away.
            "run_command",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals(
                "$name must prompt on every call",
                ConsentLevel.EVERY_CALL,
                c.level,
            )
        }
    }

    @Test
    fun `deprecated tool descriptions point at the unified replacement`() {
        // Pin the deprecation breadcrumbs so a future renamer doesn't
        // silently drop the migration hint. Agents that still use the
        // old tool names should be nudged toward the new ones via the
        // tools/list response.
        val tools = newTools()
        val defs = tools.definitions().associateBy { it.optString("name") }
        for ((deprecated, replacement) in mapOf(
            "list_sftp_directory" to "list_directory",
            "list_rclone_directory" to "list_directory",
            "upload_file_to_sftp" to "upload_file",
            "delete_sftp_file" to "delete_file",
        )) {
            val desc = defs[deprecated]?.optString("description")
                ?: error("$deprecated missing")
            assertTrue(
                "$deprecated description must mention DEPRECATED, got: $desc",
                desc.contains("DEPRECATED", ignoreCase = true),
            )
            assertTrue(
                "$deprecated description must point at $replacement, got: $desc",
                desc.contains(replacement),
            )
        }
    }

    @Test
    fun `unknown tool returns null`() {
        assertNull(newTools().consentFor("does_not_exist"))
    }

    @Test
    fun `disconnect_profile summary names the profile label`() {
        val tools = newTools()
        val c = tools.consentFor("disconnect_profile")!!
        val summary = c.summary(JSONObject().put("profileId", "p1"))
        assertTrue(
            "summary must mention the human label, got: $summary",
            summary.contains("test-host"),
        )
        assertTrue(summary.contains("Disconnect"))
    }

    @Test
    fun `add_port_forward summary describes the rule`() {
        val tools = newTools()
        val c = tools.consentFor("add_port_forward")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("type", "LOCAL")
            .put("bindPort", 8443)
            .put("targetHost", "10.0.0.5")
            .put("targetPort", 443))
        assertTrue("got: $summary", summary.contains("LOCAL"))
        assertTrue("got: $summary", summary.contains("8443"))
        assertTrue("got: $summary", summary.contains("10.0.0.5:443"))
        assertTrue("got: $summary", summary.contains("test-host"))
    }

    @Test
    fun `add_port_forward summary handles DYNAMIC without target host`() {
        val tools = newTools()
        val c = tools.consentFor("add_port_forward")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("type", "DYNAMIC")
            .put("bindPort", 1080))
        // No targetHost on DYNAMIC, but the builder must not crash and
        // must still mention the bind port.
        assertTrue("got: $summary", summary.contains("DYNAMIC"))
        assertTrue("got: $summary", summary.contains("1080"))
        assertTrue("got: $summary", summary.contains("(SOCKS)"))
    }

    @Test
    fun `delete_sftp_file summary shows path and host`() {
        val tools = newTools()
        val c = tools.consentFor("delete_sftp_file")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("path", "/var/log/build.log"))
        assertTrue("got: $summary", summary.contains("/var/log/build.log"))
        assertTrue("got: $summary", summary.contains("test-host"))
    }

    @Test
    fun `send_terminal_input summary shows literal payload (truncated)`() {
        val tools = newTools()
        val c = tools.consentFor("send_terminal_input")!!
        val short = c.summary(JSONObject()
            .put("sessionId", "s1")
            .put("text", "ls -la\n"))
        assertTrue("must show the typed text literally, got: $short",
            short.contains("ls -la"))
        // Newline is rendered as \n so the user sees it. Otherwise an
        // input ending with Enter looks the same as one that doesn't.
        assertTrue("newline must be visible in the summary, got: $short",
            short.contains("\\n"))

        // Long input is truncated so the prompt stays readable.
        val long = "x".repeat(200)
        val summary = c.summary(JSONObject().put("sessionId", "s1").put("text", long))
        assertTrue("long input must be truncated with ellipsis, got: ${summary.length} chars",
            summary.length < 200)
        assertTrue("got: $summary", summary.contains("…"))
    }

    @Test
    fun `convert_file summary names the codecs`() {
        val tools = newTools()
        val c = tools.consentFor("convert_file")!!
        val summary = c.summary(JSONObject()
            .put("sourceUrl", "http://127.0.0.1:8080/in.mkv")
            .put("container", "mp4")
            .put("videoEncoder", "libx264")
            .put("audioEncoder", "aac"))
        assertTrue("got: $summary", summary.contains("mp4"))
        assertTrue("got: $summary", summary.contains("libx264"))
        assertTrue("got: $summary", summary.contains("aac"))
    }

    @Test
    fun `summary builder is robust to missing optional args`() {
        val tools = newTools()
        // upload_file_to_sftp summary opens File(localPath) — passing a
        // non-existent path must yield a sane string, not a crash.
        val upload = tools.consentFor("upload_file_to_sftp")!!
        val s = upload.summary(JSONObject()
            .put("profileId", "p1")
            .put("localPath", "/no/such/file")
            .put("remotePath", "/dest"))
        assertTrue("got: $s", s.contains("/dest"))
        assertTrue("got: $s", s.contains("test-host"))
        assertFalse(
            "must not crash on missing file — should report unknown size",
            s.isBlank(),
        )
    }

    @Test
    fun `read_logcat summary names the scope`() {
        val tools = newTools()
        val c = tools.consentFor("read_logcat")!!
        val sysWide = c.summary(JSONObject())
        assertTrue("got: $sysWide", sysWide.contains("system-wide"))
        val perPkg = c.summary(JSONObject().put("packageName", "com.example.app"))
        assertTrue("got: $perPkg", perPkg.contains("com.example.app"))
        val perPid = c.summary(JSONObject().put("pid", 4242))
        assertTrue("got: $perPid", perPid.contains("4242"))
    }

    @Test
    fun `install_apk_from_url summary surfaces the source as a warning`() {
        val tools = newTools()
        val c = tools.consentFor("install_apk_from_url")!!
        val s = c.summary(JSONObject()
            .put("url", "https://example.com/app.apk"))
        // The summary needs to make the trust call obvious — APK install
        // is the highest-risk verb in the registry.
        assertTrue("got: $s", s.contains("APK") || s.contains("install"))
        assertTrue("got: $s", s.contains("example.com") || s.contains("trusted"))
    }

    // --- Registry-wide invariants (#mcp-backbone Stage 5, Layer E) ---
    // Blanket checks across all ~182 tools, made cheap by the schema DSL:
    // a typo'd required name or a forgotten consent summary is a silent
    // contract/security bug the per-tool tests above can't catch for tools
    // added later.

    @Test
    fun `every tool schema is a well-formed object schema`() {
        fun checkObjectSchema(where: String, schema: JSONObject) {
            assertEquals("$where: type", "object", schema.getString("type"))
            val properties = schema.getJSONObject("properties")
            schema.optJSONArray("required")?.let { required ->
                for (i in 0 until required.length()) {
                    val r = required.getString(i)
                    assertTrue("$where: required '$r' missing from properties", properties.has(r))
                }
            }
            for (key in properties.keys()) {
                val items = properties.getJSONObject(key).optJSONObject("items")
                if (items != null && items.optString("type") == "object" && items.has("properties")) {
                    checkObjectSchema("$where.$key.items", items)
                }
            }
        }
        val tools = newTools()
        val defs = tools.definitions()
        assertTrue("registry unexpectedly small: ${defs.size}", defs.size > 150)
        for (def in defs) {
            checkObjectSchema(def.getString("name"), def.getJSONObject("inputSchema"))
        }
    }

    @Test
    fun `every consent-gated tool overrides the default summary`() {
        val tools = newTools()
        // ToolHandler's default summarise returns the literal "tool call";
        // a gated tool must say what the user is approving. A summary that
        // throws on empty args still proves an override.
        val offenders = tools.definitions().map { it.getString("name") }.filter { name ->
            val consent = tools.consentFor(name)!!
            consent.level != ConsentLevel.NEVER &&
                runCatching { consent.summary(JSONObject()) }.getOrNull() == "tool call"
        }
        assertTrue("consent-gated tools with the placeholder summary: $offenders", offenders.isEmpty())
    }

    // --- Auto-generated tool documentation (docs/mcp-tools.md) ---
    // The registry is the source of truth for what an agent can do to the
    // user's phone, so the user-facing reference is GENERATED from it and
    // this test keeps the committed file in sync: when a tool is added or
    // its consent level changes, the test rewrites docs/mcp-tools.md and
    // fails, so CI blocks any registry change whose documentation diff
    // wasn't reviewed and committed.

    @Test
    fun `docs mcp-tools_md is generated from the live registry`() {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        // Capabilities OFF so capabilityDenial() yields the Settings-pointing
        // message for gated tools — reused as the doc's "extra switch" line.
        every { prefs.agentAllowFileRead } returns flowOf(false)
        every { prefs.agentAllowTerminalInputQueue } returns flowOf(false)
        val tools = newTools(prefs)
        val md = renderToolDocs(tools)

        var dir = java.io.File("").absoluteFile
        while (!java.io.File(dir, ".git").exists()) {
            dir = dir.parentFile ?: throw AssertionError("repo root not found from ${java.io.File("").absolutePath}")
        }
        val doc = java.io.File(dir, "docs/mcp-tools.md")
        val existing = if (doc.exists()) doc.readText() else ""
        if (existing != md) {
            doc.writeText(md)
            throw AssertionError(
                "docs/mcp-tools.md was stale — regenerated from the live tool registry. " +
                    "Review the diff and commit it.",
            )
        }
    }

    private fun renderToolDocs(tools: McpTools): String {
        data class Doc(val name: String, val description: String, val schema: JSONObject,
                       val level: ConsentLevel, val gate: String?)
        val docs = tools.definitions().map { def ->
            val name = def.getString("name")
            Doc(
                name = name,
                description = def.getString("description"),
                schema = def.getJSONObject("inputSchema"),
                level = tools.consentFor(name)!!.level,
                gate = runBlocking { tools.capabilityDenial(name) },
            )
        }
        // Functional sections form the page's tree. Each tool is filed into
        // the FIRST section (by [Section.priority], specific-domain-first) whose
        // keyword is a substring of its name — so a generic word like "file" or
        // "session" can't poach a domain tool (e.g. read_guest_file → Linux
        // guest, not Files). Sections render in list order; matching walks
        // priority order. A tool that matches nothing fails this test — add a
        // keyword when you add a tool.
        data class Section(
            val slug: String, val title: String, val priority: Int,
            val blurb: String, val keywords: List<String>,
        )
        val sections = listOf(
            Section("connections", "Connections & profiles", 9,
                "The saved SSH/SFTP/RDP/VNC/… connection profiles and their live connect/disconnect state.",
                listOf("connection", "connect_profile", "disconnect_profile", "run_command", "bluetooth")),
            Section("terminal", "Terminal, selection & sessions", 8,
                "Reading and driving terminal sessions: input, scrollback, text selection, snippets, and workspace layouts.",
                listOf("terminal", "selection", "snippet", "list_sessions", "auth_prompt", "session_picker", "workspace", "compose", "open_local_shell", "exited_session")),
            Section("files", "Files, media & clipboard", 10,
                "The unified file browser (local and SFTP), format conversion, media playback/streaming, encryption, and the clipboard.",
                listOf("file", "directory", "sftp", "convert", "play_", "stream", "editor", "serve_", "encrypt", "decrypt", "clipboard", "view_file")),
            Section("rclone", "Cloud storage (rclone)", 4,
                "rclone remotes and the saved sync jobs that run between them.",
                listOf("rclone", "sync_profile")),
            Section("email", "Email", 3,
                "Mailboxes, messages, drafts, and inbound Mail Rules automation.",
                listOf("mail")),
            Section("linux", "Linux guest (proot) & desktops", 2,
                "The on-device Linux distros, their desktop environments and windows, guest services, the audio bridge, and guest-file access.",
                listOf("proot", "distro", "desktop", "guest", "system_vm", "audio_bridge", "gl_smoke", "custom_binds", "app_window", "app_pack", "launch_app_in_desktop", "inspect_proot")),
            Section("networking", "Networking — tunnels & port forwarding", 5,
                "SSH tunnels, port forwards, the serial↔TCP bridge, and the port-knock / single-packet-auth gates.",
                listOf("tunnel", "port_forward", "_forward", "port_knock", "knock", "_spa", "profile_routing", "serial")),
            // Priority 1 (ahead of Linux guest) so usb_attach_to_guest files
            // here by its usb_ prefix, not under "guest".
            Section("usb", "USB & host-device brokers", 1,
                "USB devices and drives, USB/IP export, the adb-over-VPN bridge, and adb pairing.",
                // "list_bridges" (not "bridges") so the serial↔TCP bridge tools
                // file under Networking, not here. "overlay_permission" files
                // the pairing code box's grant alongside the adb tools it
                // exists for, rather than leaving it sectionless (#575).
                listOf("usb", "adb", "list_bridges", "overlay_permission")),
            Section("security", "Security — SSH keys, host keys, TOTP & age", 6,
                "The SSH key store, pinned host keys (TOFU), trusted host CAs, TOTP secrets, and age encryption identities.",
                listOf("ssh_key", "known_host", "forget_known_host", "trusted_host_ca", "totp", "age_identit")),
            Section("agent-you", "Agent ↔ you (attention & self-drive)", 7,
                "How an agent reaches your attention (present_*, notifications, the agent-to-agent turn tools) and drives Haven's own UI.",
                listOf("present_", "haven_ui", "raise_notification", "self_message", "send_to_agent", "await_turn", "read_last_turn")),
            Section("agent-endpoint", "Agent endpoint, device & diagnostics", 11,
                "Pairing, standing policies, app info/update, preferences, and device diagnostics.",
                listOf("standing_polic", "pair", "consent", "install_apk", "restart_app", "app_info", "preference", "developer_settings", "logcat", "native_crash", "process_exit", "notification")),
        )
        val byPriority = sections.sortedBy { it.priority }
        fun sectionOf(name: String): Section? =
            byPriority.firstOrNull { s -> s.keywords.any { it in name } }
        val uncategorized = docs.map { it.name }.filter { sectionOf(it) == null }.sorted()
        if (uncategorized.isNotEmpty()) {
            throw AssertionError(
                "MCP tools with no doc section: $uncategorized — add a keyword to a Section in renderToolDocs().",
            )
        }
        fun consentLabel(level: ConsentLevel): String = when (level) {
            ConsentLevel.EVERY_CALL -> "asks every call"
            ConsentLevel.ONCE_PER_SESSION -> "asks once per session"
            ConsentLevel.NEVER -> "no per-call prompt"
        }
        fun typeName(p: JSONObject): String = when (val t = p.optString("type")) {
            "array" -> when (p.optJSONObject("items")?.optString("type")) {
                "string" -> "string[]"
                "object" -> "object[]"
                else -> "array"
            }
            "" -> "any"
            else -> t
        }
        return buildString {
            // Jekyll frontmatter: this generated file IS the MCP feature page
            // on the docs site (the hand-written features/agent-mcp.md was
            // retired in its favour), so it needs a layout to render there.
            appendLine("---")
            appendLine("layout: default")
            appendLine("title: Agent transport (MCP)")
            appendLine("---")
            appendLine()
            appendLine("# Haven's MCP tools — what an agent can do, and when you are asked")
            appendLine()
            appendLine("<!-- GENERATED FILE — do not edit. Rendered from the live tool registry by")
            appendLine("     McpToolsConsentTest.`docs mcp-tools_md is generated from the live registry`.")
            appendLine("     Regenerate: ./gradlew :app:testX64DebugUnitTest --tests '*.McpToolsConsentTest' -->")
            appendLine()
            appendLine("Haven exposes an [MCP](https://modelcontextprotocol.io) endpoint so an AI agent")
            appendLine("(Claude Code, or anything speaking MCP over HTTP) can drive the phone side of your")
            appendLine("sessions. This page is the complete tool surface, generated from the same registry")
            appendLine("the server dispatches — there are no undocumented tools.")
            appendLine()
            appendLine("## The security model, in order")
            appendLine()
            appendLine("1. **Off by default.** Nothing listens until you enable Settings → Agent endpoint.")
            appendLine("2. **Loopback only by default.** The endpoint binds 127.0.0.1 (device ports 8730–8739,")
            appendLine("   SSH-tunneled clients 8740–8749). LAN/WireGuard exposure is a separate, explicit opt-in.")
            appendLine("3. **Pairing.** A new client's first request raises an Allow/Deny prompt on the phone.")
            appendLine("   Allowing mints a per-client 256-bit bearer token, shown once; Haven stores only its")
            appendLine("   SHA-256. Unpairing (Settings → Agent endpoint → Paired clients) revokes it.")
            appendLine("4. **Capability switches.** File reading and terminal-input queuing have their own")
            appendLine("   Settings toggles on top of everything else; tools behind them are marked below.")
            appendLine("5. **Per-call consent.** Every tool carries one of three consent levels — *asks every")
            appendLine("   call*, *asks once per session*, or *no per-call prompt* — shown as a tag on each tool")
            appendLine("   below. Consent sheets are rendered by Haven itself, and agent-injected taps are")
            appendLine("   refused while one is showing — an agent cannot approve its own prompt.")
            appendLine("6. **Standing policies.** An agent may request a time-boxed, rate-limited pre-approval")
            appendLine("   for named tools (create_standing_policy) — it is itself consent-gated on every call,")
            appendLine("   shows exactly what would be allowed, and has a kill-switch on the Agent activity screen.")
            appendLine("7. **Audit.** Every call crossing the agent transport is recorded on-device with")
            appendLine("   arguments redacted (Settings → Agent activity — the same screen that lists and")
            appendLine("   revokes standing policies). Haven is the dashboard, not a black box.")
            appendLine()
            appendLine("## How to read this page")
            appendLine()
            appendLine("Tools are grouped into sections by what they touch, and each tool is collapsed —")
            appendLine("expand one for its description and arguments. The tag after each name is its")
            appendLine("consent level:")
            appendLine()
            appendLine("- **asks every call** — side-effectful or sensitive; a consent sheet describing the " +
                "specific action on every call (${docs.count { it.level == ConsentLevel.EVERY_CALL }} tools).")
            appendLine("- **asks once per session** — reversible actions and screen-reading; prompts the first " +
                "time each session, then proceeds (${docs.count { it.level == ConsentLevel.ONCE_PER_SESSION }} tools).")
            appendLine("- **no per-call prompt** — read-only queries and tap-equivalent UI actions; still behind " +
                "the endpoint being enabled and the client paired (${docs.count { it.level == ConsentLevel.NEVER }} tools).")
            appendLine()
            appendLine("## Sections")
            appendLine()
            for (s in sections) {
                val n = docs.count { sectionOf(it.name) === s }
                appendLine("- [**${s.title}**](#sec-${s.slug}) — $n tools")
            }
            for (s in sections) {
                // Byte-stable: sections in list order, tools sorted by name.
                val secDocs = docs.filter { sectionOf(it.name) === s }.sortedBy { it.name }
                appendLine()
                appendLine("<a id=\"sec-${s.slug}\"></a>")
                appendLine()
                appendLine("## ${s.title} (${secDocs.size})")
                appendLine()
                appendLine(s.blurb)
                for (d in secDocs) {
                    appendLine()
                    // markdown="1" so kramdown (Jekyll) parses the inner
                    // markdown; GitHub parses it regardless given the blank
                    // line after <summary>.
                    appendLine("<details markdown=\"1\">")
                    appendLine("<summary><code>${d.name}</code> · ${consentLabel(d.level)}</summary>")
                    appendLine()
                    d.gate?.let { appendLine("*Extra capability switch — off by default: $it.*"); appendLine() }
                    appendLine(d.description)
                    val props = d.schema.getJSONObject("properties")
                    val required = d.schema.optJSONArray("required")
                        ?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty().toSet()
                    // org.json's JVM key order is a HashMap's — sort so the
                    // rendered file is byte-stable across JVMs (required
                    // arguments first, then alphabetical).
                    val names = props.keys().asSequence().toList()
                        .sortedWith(compareBy({ it !in required }, { it }))
                    if (names.isNotEmpty()) {
                        appendLine()
                        for (p in names) {
                            val prop = props.getJSONObject(p)
                            val bits = buildList {
                                add(typeName(prop))
                                if (p in required) add("required")
                                prop.optJSONArray("enum")?.let { e ->
                                    add("one of: " + (0 until e.length()).joinToString(" | ") { e.getString(it) })
                                }
                            }
                            val desc = prop.optString("description")
                            appendLine("- `$p` (${bits.joinToString(", ")})${if (desc.isNotEmpty()) " — $desc" else ""}")
                        }
                    }
                    appendLine()
                    appendLine("</details>")
                }
            }
        }
    }

    // --- Declarative capability gate (#mcp-backbone Stage 5) ---

    @Test
    fun `serve_file capability denial is null when file read is enabled`() {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.agentAllowFileRead } returns flowOf(true)
        assertNull(runBlocking { newTools(prefs).capabilityDenial("serve_file") })
    }

    @Test
    fun `serve_file is denied with a Settings-pointing message when file read is off`() {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.agentAllowFileRead } returns flowOf(false)
        val msg = runBlocking { newTools(prefs).capabilityDenial("serve_file") }
        assertNotNull("serve_file must be gated when the toggle is off", msg)
        assertTrue("got: $msg", msg!!.contains("file read is disabled"))
    }

    @Test
    fun `queue_terminal_input and its deprecated alias share the terminal-input capability`() {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.agentAllowTerminalInputQueue } returns flowOf(false)
        for (name in listOf("queue_terminal_input", "queue_self_message")) {
            val msg = runBlocking { newTools(prefs).capabilityDenial(name) }
            assertNotNull("$name should be gated", msg)
            assertTrue("got: $msg", msg!!.contains("queue_terminal_input is disabled"))
        }
    }

    @Test
    fun `an ungated tool returns no denial without ever reading a pref`() {
        // No pref stubs on the relaxed mock: capabilityDenial must short-
        // circuit on the null capability BEFORE touching preferences, which
        // is why gating every tool through it doesn't perturb other tests.
        assertNull(runBlocking { newTools().capabilityDenial("list_connections") })
    }

    // --- Key-store provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `key-store tools are aggregated into the registry with their consent levels intact`() {
        // The TOTP + age tools now live in KeyStoreToolProvider, not McpTools'
        // own map. This asserts the aggregation surfaces them unchanged —
        // same names, same consent levels, still in definitions().
        val tools = newTools()
        val definitionNames = tools.definitions().map { it.getString("name") }.toSet()
        val readOnly = listOf("list_totp_secrets", "list_age_identities")
        val everyCall = listOf(
            "create_totp_secret", "delete_totp_secret", "generate_totp_code",
            "create_age_identity", "encrypt_file", "decrypt_file",
        )
        for (name in readOnly) {
            assertTrue("$name missing from definitions()", name in definitionNames)
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        for (name in everyCall) {
            assertTrue("$name missing from definitions()", name in definitionNames)
            assertEquals("$name must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor(name)!!.level)
        }
    }

    @Test
    fun `a provider tool dispatches through the aggregated call path`() {
        // Proves the provider's handler is reachable via McpTools.call — the
        // same entry point the transport uses — not just registered.
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        val tools = newTools(prefs)
        val out = runBlocking { tools.call("list_totp_secrets", JSONObject()) }
        // Relaxed TotpSecretRepository.getAll() returns an empty list.
        assertEquals(0, out.getInt("count"))
    }

    // --- Tunnel provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `tunnel-config tools are aggregated with their consent levels intact`() {
        // list_tunnels/list_live_tunnels/create_tunnel/delete_tunnel moved to
        // TunnelToolProvider; set_profile_routing stayed in McpTools (it edits
        // a connection profile, not a tunnel config). Assert both facts.
        val tools = newTools()
        val definitionNames = tools.definitions().map { it.getString("name") }.toSet()
        for (name in listOf("list_tunnels", "list_live_tunnels")) {
            assertTrue("$name missing from definitions()", name in definitionNames)
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        assertEquals("create_tunnel must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor("create_tunnel")!!.level)
        assertEquals("delete_tunnel must be ONCE_PER_SESSION", ConsentLevel.ONCE_PER_SESSION, tools.consentFor("delete_tunnel")!!.level)
        // set_profile_routing is NOT in the tunnel provider — still registered by McpTools.
        assertTrue("set_profile_routing must remain registered", "set_profile_routing" in definitionNames)
        assertEquals(ConsentLevel.ONCE_PER_SESSION, tools.consentFor("set_profile_routing")!!.level)
    }

    @Test
    fun `list_tunnels dispatches through the aggregated call path`() {
        val tools = newTools(mockk(relaxed = true))
        val out = runBlocking { tools.call("list_tunnels", JSONObject()) }
        // Relaxed TunnelConfigRepository.getAll() returns an empty list.
        assertEquals(0, out.getInt("count"))
    }

    // --- SSH-key provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `ssh-key tools are aggregated with their consent levels intact`() {
        val tools = newTools()
        val definitionNames = tools.definitions().map { it.getString("name") }.toSet()
        assertTrue("list_ssh_keys missing", "list_ssh_keys" in definitionNames)
        assertEquals("list_ssh_keys must be NEVER", ConsentLevel.NEVER, tools.consentFor("list_ssh_keys")!!.level)
        assertEquals("import_ssh_key must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor("import_ssh_key")!!.level)
        assertEquals("delete_ssh_key must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor("delete_ssh_key")!!.level)
        assertEquals("set_ssh_key_option must be ONCE_PER_SESSION", ConsentLevel.ONCE_PER_SESSION, tools.consentFor("set_ssh_key_option")!!.level)
    }

    @Test
    fun `list_ssh_keys dispatches through the aggregated call path`() {
        val tools = newTools(mockk(relaxed = true))
        val out = runBlocking { tools.call("list_ssh_keys", JSONObject()) }
        // Relaxed SshKeyRepository.getAll() returns an empty list.
        assertEquals(0, out.getInt("count"))
    }

    // --- Rclone provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `rclone tools are aggregated with their consent levels intact`() {
        val tools = newTools()
        val names = tools.definitions().map { it.getString("name") }.toSet()
        // A representative slice across the domain's three impl clusters:
        // remote-config CRUD, sync jobs/stats, and saved sync profiles.
        val readOnly = listOf(
            "list_rclone_remotes", "list_rclone_directory", "list_rclone_provider_options",
            "get_rclone_sync_status", "get_rclone_stats", "list_saved_sync_profiles",
        )
        val everyCall = listOf(
            "start_rclone_sync", "save_sync_profile",
            // Destructive → EVERY_CALL (the saved config / remote is gone after).
            "delete_sync_profile", "delete_rclone_remote",
        )
        for (name in readOnly) {
            assertTrue("$name missing from definitions()", name in names)
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        for (name in everyCall) {
            assertTrue("$name missing from definitions()", name in names)
            assertEquals("$name must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor(name)!!.level)
        }
        // All 15 rclone tools present.
        assertTrue("expected all 15 rclone tools", names.count { it.contains("rclone") || it.endsWith("sync_profile") || it.endsWith("sync_profiles") } >= 15)
    }

    @Test
    fun `list_rclone_remotes dispatches through the aggregated call path`() {
        val tools = newTools(mockk(relaxed = true))
        val out = runBlocking { tools.call("list_rclone_remotes", JSONObject()) }
        // Relaxed RcloneClient.listRemotes() returns an empty list → count 0.
        assertEquals(0, out.getInt("count"))
    }

    // --- USB provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `usb tools are aggregated with their consent levels intact`() {
        val tools = newTools()
        val names = tools.definitions().map { it.getString("name") }.toSet()
        // list_usb_devices is read-only; the transfer/attach/export/drive verbs
        // gate. A representative slice across the broker / usbip / drive-VM
        // clusters.
        assertTrue("list_usb_devices missing", "list_usb_devices" in names)
        assertEquals("list_usb_devices must be NEVER", ConsentLevel.NEVER, tools.consentFor("list_usb_devices")!!.level)
        for (name in listOf(
            "request_usb_permission", "usb_control_transfer", "usb_bulk_transfer",
            "usb_attach_to_guest", "start_usbip_export", "open_usb_drive",
            "unlock_usb_drive_partition", "delete_usb_appliance",
        )) {
            assertTrue("$name missing from definitions()", name in names)
            assertNotEquals("$name should gate (not NEVER)", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        // list_bridges (the Bridges registry) stays in McpTools, not the USB provider.
        assertTrue("list_bridges must remain registered", "list_bridges" in names)
    }

    @Test
    fun `list_usb_devices dispatches through the aggregated call path`() {
        val tools = newTools(mockk(relaxed = true))
        val out = runBlocking { tools.call("list_usb_devices", JSONObject()) }
        // Relaxed UsbBroker.listDevices() returns an empty list → count 0.
        assertEquals(0, out.getInt("count"))
    }

    // --- Desktop provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `desktop tools are aggregated with their consent levels intact`() {
        val tools = newTools()
        val names = tools.definitions().map { it.getString("name") }.toSet()
        // Read-only listings + tap-equivalent opens are NEVER
        // (open_desktop_terminal just surfaces a terminal; the guest shell it
        // attaches has its own gate).
        for (name in listOf(
            "list_desktop_sessions", "list_guest_apps", "list_desktop_environments",
            "read_desktop_log", "open_desktop_terminal",
        )) {
            assertTrue("$name missing from definitions()", name in names)
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        // The lifecycle / capture / interaction verbs gate (not NEVER) —
        // capture_* / list_desktop_windows are ONCE_PER_SESSION, tap is
        // EVERY_CALL, etc.
        for (name in listOf(
            "list_desktop_windows",
            "install_desktop", "uninstall_desktop", "start_desktop", "stop_desktop",
            "capture_desktop", "capture_desktop_tab", "tap_desktop_tab", "scroll_desktop_tab",
            "send_desktop_clipboard", "launch_app_in_desktop",
        )) {
            assertTrue("$name missing from definitions()", name in names)
            assertNotEquals("$name should gate (not NEVER)", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        // The distro / haven-ui / standing-policy tools interleaved among the
        // desktop registrations stay in McpTools, not the desktop provider.
        // (Dispatch via McpTools.call is proven by the five other provider
        // dispatch tests; the desktop reads hit a StateFlow / prootManager a
        // relaxed mock can't satisfy, so this asserts registration + consent.)
        for (name in listOf("set_active_distro", "capture_haven_ui", "create_standing_policy")) {
            assertTrue("$name must remain registered", name in names)
        }
    }

    // --- Mail provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `mail tools are aggregated with their consent levels intact`() {
        val tools = newTools()
        val names = tools.definitions().map { it.getString("name") }.toSet()
        // Read-only listings/status + poke are NEVER (poke just nudges the
        // watcher; its rule actions carry their own pre-authorisation).
        for (name in listOf(
            "list_mail_rules", "get_mail_automation_status", "poke_mail_watch",
            "list_mail_folders", "list_mail_messages", "read_mail_message", "search_mail",
        )) {
            assertTrue("$name missing from definitions()", name in names)
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        // The write / send / rule-mutation verbs gate (not NEVER).
        for (name in listOf(
            "create_mail_rule", "delete_mail_rule",
            "send_mail", "save_mail_attachment", "modify_mail_message",
            "save_mail_draft", "create_mail_folder", "delete_mail_folder",
        )) {
            assertTrue("$name missing from definitions()", name in names)
            assertNotEquals("$name should gate (not NEVER)", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        // Dispatch via McpTools.call is proven by the five other provider
        // dispatch tests; the mail reads hit a Flow/session a relaxed mock
        // can't satisfy, so this asserts registration + consent.
    }

    /** #469: get_app_info must report the REAL Wayland-bridge state, not a
     *  hardcoded capability — the loadError is the only remotely-reachable
     *  diagnostic when liblabwc_android.so fails to load (logcat needs
     *  Shizuku). On the host JVM the lib never loads, which exercises
     *  exactly the unavailable branch. Lives here for the newTools harness,
     *  not because it's a consent behaviour. */
    @Test
    fun `get_app_info reports wayland unavailable with the load error`() {
        val tools = newTools()
        val out = runBlocking { tools.call("get_app_info", JSONObject()) }
        val caps = (0 until out.getJSONArray("capabilities").length())
            .map { out.getJSONArray("capabilities").getString(it) }
        assertFalse("wayland must not be advertised when the lib didn't load", "wayland" in caps)
        assertTrue(
            "waylandLoadError must carry the loadLibrary failure reason",
            out.getString("waylandLoadError").isNotBlank(),
        )
    }
}
