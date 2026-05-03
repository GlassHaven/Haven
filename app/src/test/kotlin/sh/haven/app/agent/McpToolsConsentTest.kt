package sh.haven.app.agent

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun newTools(): McpTools {
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
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
            transportSelector = mockk<sh.haven.feature.sftp.transport.TransportSelector>(relaxed = true),
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
            "send_terminal_input",
            "convert_file",
            "set_terminal_font_from_url",
            "install_apk_from_url",
            "enable_wireless_adb",
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
}
