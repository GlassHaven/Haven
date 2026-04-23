package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val biometricEnabledKey = booleanPreferencesKey("biometric_enabled")
    private val terminalFontSizeKey = intPreferencesKey("terminal_font_size")
    private val themeKey = stringPreferencesKey("theme")
    private val sessionManagerKey = stringPreferencesKey("session_manager")
    private val reticulumRpcKeyKey = stringPreferencesKey("reticulum_rpc_key")
    private val reticulumHostKey = stringPreferencesKey("reticulum_host")
    private val reticulumPortKey = intPreferencesKey("reticulum_port")
    private val terminalColorSchemeKey = stringPreferencesKey("terminal_color_scheme")
    private val toolbarRowsKey = intPreferencesKey("toolbar_rows") // legacy
    private val toolbarRow1Key = stringPreferencesKey("toolbar_row1") // legacy
    private val toolbarRow2Key = stringPreferencesKey("toolbar_row2") // legacy
    private val toolbarLayoutKey = stringPreferencesKey("toolbar_layout")
    private val navBlockModeKey = stringPreferencesKey("nav_block_mode")
    private val sessionCommandOverrideKey = stringPreferencesKey("session_command_override")
    private val sftpSortModeKey = stringPreferencesKey("sftp_sort_mode")
    private val lockTimeoutKey = stringPreferencesKey("lock_timeout")
    private val screenSecurityKey = booleanPreferencesKey("screen_security")
    private val showSearchButtonKey = booleanPreferencesKey("show_search_button")
    private val showCopyOutputButtonKey = booleanPreferencesKey("show_copy_output_button")
    private val connectionLoggingEnabledKey = booleanPreferencesKey("connection_logging_enabled")
    private val verboseLoggingEnabledKey = booleanPreferencesKey("verbose_logging_enabled")
    private val mouseInputEnabledKey = booleanPreferencesKey("mouse_input_enabled")
    private val terminalRightClickKey = booleanPreferencesKey("terminal_right_click")
    private val allowStandardKeyboardKey = booleanPreferencesKey("allow_standard_keyboard")
    private val rawKeyboardModeKey = booleanPreferencesKey("raw_keyboard_mode")
    private val interceptCtrlShiftVKey = booleanPreferencesKey("intercept_ctrl_shift_v")
    private val showTerminalTabBarKey = booleanPreferencesKey("show_terminal_tab_bar")
    private val reorderHintShownKey = booleanPreferencesKey("reorder_hint_shown")
    private val screenOrderKey = stringPreferencesKey("screen_order")
    private val waylandShellCommandKey = stringPreferencesKey("wayland_shell_command")
    private val batteryPromptDismissedKey = booleanPreferencesKey("battery_prompt_dismissed")
    private val showLinuxVmCardKey = booleanPreferencesKey("show_linux_vm_card")
    private val showDesktopsCardKey = booleanPreferencesKey("show_desktops_card")
    private val mediaExtensionsKey = stringPreferencesKey("media_extensions")
    private val desktopInputModeKey = stringPreferencesKey("desktop_input_mode")
    private val lastMediaServerPortKey = intPreferencesKey("last_media_server_port")
    private val mcpAgentEndpointEnabledKey = booleanPreferencesKey("mcp_agent_endpoint_enabled")
    private val lastViewedAgentAuditTimestampKey = longPreferencesKey("last_viewed_agent_audit_timestamp")
    private val requireAgentConsentForWritesKey = booleanPreferencesKey("require_agent_consent_for_writes")

    val biometricEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[biometricEnabledKey] ?: false
    }

    /** Prevent screenshots and screen recording (FLAG_SECURE). */
    val screenSecurity: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[screenSecurityKey] ?: false
    }

    suspend fun setScreenSecurity(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[screenSecurityKey] = enabled
        }
    }

    /** Show search button in terminal tab bar. Sends session manager's native search keys. */
    val showSearchButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showSearchButtonKey] ?: false
    }

    suspend fun setShowSearchButton(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showSearchButtonKey] = enabled
        }
    }

    /** Show copy-last-output button in terminal tab bar. Requires shell OSC 133 integration. */
    val showCopyOutputButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showCopyOutputButtonKey] ?: false
    }

    suspend fun setShowCopyOutputButton(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showCopyOutputButtonKey] = enabled
        }
    }

    /** Record connection events (connect, disconnect, errors). Off by default. */
    val connectionLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[connectionLoggingEnabledKey] ?: false
    }

    suspend fun setConnectionLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[connectionLoggingEnabledKey] = enabled
        }
    }

    /** Capture SSH protocol details (key exchange, auth, ciphers). Off by default. */
    val verboseLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[verboseLoggingEnabledKey] ?: false
    }

    suspend fun setVerboseLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[verboseLoggingEnabledKey] = enabled
        }
    }

    /**
     * VNC/RDP desktop input style. "DIRECT" (default) — finger position is
     * the pointer position. "TOUCHPAD" — drag moves a remote cursor
     * relatively (laptop-trackpad style), tap clicks at the *cursor*
     * position. The cursor is also auto-followed by the viewport when
     * zoomed.
     */
    val desktopInputMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[desktopInputModeKey] ?: "DIRECT"
    }

    suspend fun setDesktopInputMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[desktopInputModeKey] = mode
        }
    }

    /** Forward taps/long-press as mouse clicks to TUI apps (htop, mc, vim). */
    val mouseInputEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mouseInputEnabledKey] ?: true
    }

    suspend fun setMouseInputEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mouseInputEnabledKey] = enabled
        }
    }

    /** Send long-press as right-click to TUI apps instead of starting text selection. */
    val terminalRightClick: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalRightClickKey] ?: false
    }

    suspend fun setTerminalRightClick(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalRightClickKey] = enabled
        }
    }

    /** Use standard keyboard (voice, swipe, autocomplete) instead of secure password-style input. */
    val allowStandardKeyboard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[allowStandardKeyboardKey] ?: false
    }

    suspend fun setAllowStandardKeyboard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[allowStandardKeyboardKey] = enabled
            // Raw and Standard are mutually exclusive; turning one on
            // automatically turns the other off so the toolbar state
            // stays consistent with the IME behaviour.
            if (enabled) prefs[rawKeyboardModeKey] = false
        }
    }

    /**
     * When true, the terminal returns no InputConnection at all — Gboard
     * has nothing to decorate, so its mic, suggestion strip, and AI Core
     * writing assist cannot appear. Physical keyboard input still flows
     * through `View.dispatchKeyEvent`. Soft-keyboard input comes through as
     * raw key events only; no IME composition (so no CJK).
     */
    val rawKeyboardMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rawKeyboardModeKey] ?: false
    }

    suspend fun setRawKeyboardMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rawKeyboardModeKey] = enabled
            if (enabled) prefs[allowStandardKeyboardKey] = false
        }
    }

    /**
     * Intercept Ctrl+Shift+V from a hardware keyboard as "paste from Android
     * clipboard". When off, the key combo is forwarded to the remote shell
     * unchanged — useful if a remote app binds Ctrl+Shift+V itself.
     */
    val interceptCtrlShiftV: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[interceptCtrlShiftVKey] ?: true
    }

    suspend fun setInterceptCtrlShiftV(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[interceptCtrlShiftVKey] = enabled
        }
    }

    /** Whether the terminal session tab bar is shown above the terminal. */
    val showTerminalTabBar: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showTerminalTabBarKey] ?: true
    }

    suspend fun setShowTerminalTabBar(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showTerminalTabBarKey] = enabled
        }
    }

    val reorderHintShown: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[reorderHintShownKey] ?: false
    }

    suspend fun setReorderHintShown() {
        dataStore.edit { prefs ->
            prefs[reorderHintShownKey] = true
        }
    }

    /** Comma-separated route names defining bottom navigation tab order. */
    val screenOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[screenOrderKey]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun setScreenOrder(routes: List<String>) {
        dataStore.edit { prefs ->
            prefs[screenOrderKey] = routes.joinToString(",")
        }
    }

    /** Whether the user has dismissed the battery optimization prompt. */
    val batteryPromptDismissed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[batteryPromptDismissedKey] ?: false
    }

    suspend fun setBatteryPromptDismissed() {
        dataStore.edit { prefs ->
            prefs[batteryPromptDismissedKey] = true
        }
    }

    /** Whether the Linux VM card is shown on the Connections screen. */
    val showLinuxVmCard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showLinuxVmCardKey] ?: true
    }

    suspend fun setShowLinuxVmCard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showLinuxVmCardKey] = enabled
        }
    }

    /** Whether the Desktops card is shown on the Connections screen. */
    val showDesktopsCard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showDesktopsCardKey] ?: true
    }

    suspend fun setShowDesktopsCard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showDesktopsCardKey] = enabled
        }
    }

    /** Shell command to run in the Wayland desktop (default: /bin/sh -l). */
    val waylandShellCommand: Flow<String> = dataStore.data.map { prefs ->
        prefs[waylandShellCommandKey] ?: "/bin/sh -l"
    }

    suspend fun setWaylandShellCommand(command: String) {
        dataStore.edit { prefs ->
            prefs[waylandShellCommandKey] = command
        }
    }

    /** Space-separated file extensions to treat as streamable media (e.g. "mp3 mp4 flac"). */
    val mediaExtensions: Flow<String> = dataStore.data.map { prefs ->
        prefs[mediaExtensionsKey] ?: DEFAULT_MEDIA_EXTENSIONS
    }

    suspend fun setMediaExtensions(extensions: String) {
        dataStore.edit { prefs ->
            prefs[mediaExtensionsKey] = extensions
        }
    }

    /** Last port used by the media streaming server (for reconnection after restart). */
    val lastMediaServerPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[lastMediaServerPortKey] ?: 0
    }

    suspend fun setLastMediaServerPort(port: Int) {
        dataStore.edit { prefs ->
            prefs[lastMediaServerPortKey] = port
        }
    }

    /**
     * Whether the MCP agent endpoint server is enabled. Defaults to **false** —
     * the agent transport gives programmatic access to state an AI agent
     * or any local process can read, and must be an explicit opt-in.
     */
    val mcpAgentEndpointEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mcpAgentEndpointEnabledKey] ?: false
    }

    suspend fun setMcpAgentEndpointEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mcpAgentEndpointEnabledKey] = enabled
        }
    }

    /**
     * Wall-clock timestamp of the most recent visit to the agent
     * activity log. The Settings badge compares this against the
     * latest event in [sh.haven.core.data.db.AgentAuditEventDao] to
     * decide whether to show an "unseen" dot.
     */
    val lastViewedAgentAuditTimestamp: Flow<Long> = dataStore.data.map { prefs ->
        prefs[lastViewedAgentAuditTimestampKey] ?: 0L
    }

    suspend fun setLastViewedAgentAuditTimestamp(timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[lastViewedAgentAuditTimestampKey] = timestamp
        }
    }

    /**
     * Whether destructive agent actions (writes, deletes, uploads,
     * disconnects) must be confirmed by the user. Default **true** —
     * the §85 rule from VISION.md says the user always keeps the
     * wheel, and that means an explicit per-action gate by default.
     * No mutating MCP tools exist yet; the toggle is in place so the
     * first one that does inherits the right default.
     */
    val requireAgentConsentForWrites: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[requireAgentConsentForWritesKey] ?: true
    }

    suspend fun setRequireAgentConsentForWrites(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[requireAgentConsentForWritesKey] = enabled
        }
    }

    val terminalFontSize: Flow<Int> = dataStore.data.map { prefs ->
        prefs[terminalFontSizeKey] ?: DEFAULT_FONT_SIZE
    }

    val sessionManager: Flow<SessionManager> = dataStore.data.map { prefs ->
        SessionManager.fromString(prefs[sessionManagerKey])
    }

    suspend fun setSessionManager(manager: SessionManager) {
        dataStore.edit { prefs ->
            prefs[sessionManagerKey] = manager.name
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[biometricEnabledKey] = enabled
        }
    }

    val theme: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromString(prefs[themeKey])
    }

    suspend fun setTerminalFontSize(sizeSp: Int) {
        dataStore.edit { prefs ->
            prefs[terminalFontSizeKey] = sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        }
    }

    suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[themeKey] = mode.name
        }
    }

    val reticulumRpcKey: Flow<String?> = dataStore.data.map { prefs ->
        prefs[reticulumRpcKeyKey]
    }

    val reticulumHost: Flow<String> = dataStore.data.map { prefs ->
        prefs[reticulumHostKey] ?: DEFAULT_RETICULUM_HOST
    }

    val reticulumPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[reticulumPortKey] ?: DEFAULT_RETICULUM_PORT
    }

    val reticulumConfigured: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[reticulumRpcKeyKey] != null
    }

    suspend fun setReticulumConfig(rpcKey: String, host: String, port: Int) {
        dataStore.edit { prefs ->
            prefs[reticulumRpcKeyKey] = rpcKey
            prefs[reticulumHostKey] = host
            prefs[reticulumPortKey] = port
        }
    }

    suspend fun clearReticulumConfig() {
        dataStore.edit { prefs ->
            prefs.remove(reticulumRpcKeyKey)
        }
    }

    /**
     * Toolbar layout as a [ToolbarLayout]. Migrates from legacy row1/row2
     * comma-separated format on first read if needed.
     */
    val toolbarLayout: Flow<ToolbarLayout> = dataStore.data.map { prefs ->
        val json = prefs[toolbarLayoutKey]
        if (json != null) {
            ToolbarLayout.fromJson(json)
        } else {
            // Migrate from legacy formats
            val row1 = prefs[toolbarRow1Key]
            val row2 = prefs[toolbarRow2Key]
            if (row1 != null || row2 != null) {
                ToolbarLayout.fromLegacy(
                    row1 ?: DEFAULT_TOOLBAR_ROW1,
                    row2 ?: DEFAULT_TOOLBAR_ROW2,
                )
            } else {
                ToolbarLayout.DEFAULT
            }
        }
    }

    val toolbarLayoutJson: Flow<String> = dataStore.data.map { prefs ->
        prefs[toolbarLayoutKey] ?: ToolbarLayout.DEFAULT.toJson()
    }

    suspend fun setToolbarLayout(layout: ToolbarLayout) {
        dataStore.edit { prefs ->
            prefs[toolbarLayoutKey] = layout.toJson()
            // Clear legacy keys
            prefs.remove(toolbarRow1Key)
            prefs.remove(toolbarRow2Key)
            prefs.remove(toolbarRowsKey)
        }
    }

    suspend fun setToolbarLayoutJson(json: String) {
        dataStore.edit { prefs ->
            prefs[toolbarLayoutKey] = json
            prefs.remove(toolbarRow1Key)
            prefs.remove(toolbarRow2Key)
            prefs.remove(toolbarRowsKey)
        }
    }

    val navBlockMode: Flow<NavBlockMode> = dataStore.data.map { prefs ->
        prefs[navBlockModeKey]?.let { NavBlockMode.fromId(it) } ?: NavBlockMode.ALIGNED
    }

    suspend fun setNavBlockMode(mode: NavBlockMode) {
        dataStore.edit { prefs ->
            prefs[navBlockModeKey] = mode.id
        }
    }

    /**
     * User override for the session manager command template.
     * If non-null, replaces the built-in command. Use {name} for session name.
     */
    val sessionCommandOverride: Flow<String?> = dataStore.data.map { prefs ->
        prefs[sessionCommandOverrideKey]
    }

    suspend fun setSessionCommandOverride(command: String?) {
        dataStore.edit { prefs ->
            if (command.isNullOrBlank()) {
                prefs.remove(sessionCommandOverrideKey)
            } else {
                prefs[sessionCommandOverrideKey] = command
            }
        }
    }

    val sftpSortMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[sftpSortModeKey] ?: "NAME_ASC"
    }

    suspend fun setSftpSortMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[sftpSortModeKey] = mode
        }
    }

    val terminalColorScheme: Flow<TerminalColorScheme> = dataStore.data.map { prefs ->
        TerminalColorScheme.fromString(prefs[terminalColorSchemeKey])
    }

    suspend fun setTerminalColorScheme(scheme: TerminalColorScheme) {
        dataStore.edit { prefs ->
            prefs[terminalColorSchemeKey] = scheme.name
        }
    }

    enum class TerminalColorScheme(
        val label: String,
        val background: Long,
        val foreground: Long,
    ) {
        HAVEN("Haven", 0xFF1A1A2E, 0xFF00E676),
        CLASSIC_GREEN("Classic Green", 0xFF000000, 0xFF00FF00),
        LIGHT("Light", 0xFFFFFFFF, 0xFF1A1A1A),
        SOLARIZED_DARK("Solarized Dark", 0xFF002B36, 0xFF839496),
        DRACULA("Dracula", 0xFF282A36, 0xFFF8F8F2),
        MONOKAI("Monokai", 0xFF272822, 0xFFF8F8F2),
        NORD("Nord", 0xFF2E3440, 0xFFD8DEE9),
        GRUVBOX("Gruvbox", 0xFF282828, 0xFFEBDBB2),
        TOKYO_NIGHT("Tokyo Night", 0xFF1A1B26, 0xFFA9B1D6),
        QBASIC("QBasic", 0xFF0000AA, 0xFFAAAAAA),
        AMBER("Amber", 0xFF1A1000, 0xFFFFB000),
        PINK("Pink", 0xFF2D001E, 0xFFFF9EC6),
        LAVENDER("Lavender", 0xFF1E1629, 0xFFCDB4DB),
        OCEAN("Ocean", 0xFF0A192F, 0xFF64FFDA);

        companion object {
            fun fromString(value: String?): TerminalColorScheme =
                entries.find { it.name == value } ?: HAVEN
        }
    }

    val lockTimeout: Flow<LockTimeout> = dataStore.data.map { prefs ->
        LockTimeout.fromString(prefs[lockTimeoutKey])
    }

    suspend fun setLockTimeout(timeout: LockTimeout) {
        dataStore.edit { prefs ->
            prefs[lockTimeoutKey] = timeout.name
        }
    }

    enum class LockTimeout(val label: String, val seconds: Long) {
        IMMEDIATE("Immediately", 0),
        THIRTY_SECONDS("30 seconds", 30),
        ONE_MINUTE("1 minute", 60),
        FIVE_MINUTES("5 minutes", 300),
        NEVER("Never", Long.MAX_VALUE);

        companion object {
            fun fromString(value: String?): LockTimeout =
                entries.find { it.name == value } ?: IMMEDIATE
        }
    }

    enum class ThemeMode(val label: String) {
        SYSTEM("System default"),
        LIGHT("Light"),
        DARK("Dark");

        companion object {
            fun fromString(value: String?): ThemeMode =
                entries.find { it.name == value } ?: SYSTEM
        }
    }

    enum class SessionManager(
        val label: String,
        val url: String?,
        val command: ((String) -> String)?,
        val supportsScrollback: Boolean = true,
    ) {
        NONE("None", null, null, supportsScrollback = false),
        TMUX("tmux", "https://github.com/tmux/tmux/wiki", { name -> "tmux new-session -A -s $name \\; set -gq allow-passthrough on \\; set -gq mouse on" }),
        ZELLIJ("zellij", "https://zellij.dev", { name -> "zellij attach $name --create" }),
        SCREEN("screen", "https://www.gnu.org/software/screen/", { name -> "screen -dRR $name" }, supportsScrollback = false),
        BYOBU("byobu", "https://www.byobu.org", { name -> "byobu new-session -A -s $name \\; set -gq mouse on" });

        companion object {
            fun fromString(value: String?): SessionManager =
                entries.find { it.name == value } ?: NONE
        }
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32
        const val DEFAULT_TOOLBAR_ROWS = 2 // legacy
        const val DEFAULT_RETICULUM_HOST = "127.0.0.1"
        const val DEFAULT_RETICULUM_PORT = 37428
        const val DEFAULT_TOOLBAR_ROW1 = "keyboard,esc,tab,shift,ctrl,alt" // legacy
        const val DEFAULT_TOOLBAR_ROW2 = "arrow_left,arrow_up,arrow_down,arrow_right,sym_pipe,sym_tilde,sym_slash,sym_backslash,sym_backtick" // legacy
        const val DEFAULT_MEDIA_EXTENSIONS = "mp3 flac ogg opus m4a aac wma wav aiff alac ape mka mp4 mkv avi mov wmv flv webm m4v ts mpg mpeg 3gp"
    }
}
