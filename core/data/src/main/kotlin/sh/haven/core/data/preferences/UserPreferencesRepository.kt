package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    private val reorderHintShownKey = booleanPreferencesKey("reorder_hint_shown")
    private val screenOrderKey = stringPreferencesKey("screen_order")

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
        MONOKAI("Monokai", 0xFF272822, 0xFFF8F8F2);

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
    }
}
