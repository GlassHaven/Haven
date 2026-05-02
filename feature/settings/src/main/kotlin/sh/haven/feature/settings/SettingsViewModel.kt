package sh.haven.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.backup.BackupService
import sh.haven.core.data.db.AgentAuditEventDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.ssh.SshSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Loopback port the MCP server prefers; matches `McpServer.bindLoopback`. */
private const val MCP_PORT = 8730

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val authenticator: BiometricAuthenticator,
    private val backupService: BackupService,
    private val agentAuditEventDao: AgentAuditEventDao,
    private val connectionRepository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val agentConsentManager: AgentConsentManager,
    private val terminalFontInstaller: TerminalFontInstaller,
) : ViewModel() {

    val terminalFontPath: StateFlow<String?> = preferencesRepository.terminalFontPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Import a TTF/OTF from a SAF content URI. Thin wrapper over
     * [TerminalFontInstaller] so the SAF picker and the URL dialog
     * (and the agent transport's `set_terminal_font_from_url` tool)
     * all funnel through the same install path.
     *
     * Returns the saved path on success, or null if the import failed.
     * Caller surfaces a Toast.
     */
    suspend fun importCustomTerminalFont(uri: Uri, displayName: String): String? {
        return when (val r = terminalFontInstaller.installFromContentUri(uri, displayName)) {
            is TerminalFontInstaller.Result.Success -> r.path
            is TerminalFontInstaller.Result.Failure -> null
        }
    }

    /**
     * Download a font from [urlString] and install it. Returns the
     * installer result so the dialog can render Success vs Failure
     * with a meaningful Toast — the failure path is too rich to
     * collapse to null/non-null.
     */
    suspend fun installTerminalFontFromUrl(urlString: String): TerminalFontInstaller.Result =
        terminalFontInstaller.installFromUrl(urlString)

    /** Reset to the bundled Hack Regular by clearing the saved path. */
    fun clearCustomTerminalFont() {
        viewModelScope.launch { terminalFontInstaller.reset() }
    }

    /** Outcome of a "Tunnel MCP through this profile" tap. */
    data class McpTunnelResult(
        /** True if the rule was live-applied to a connected session. */
        val activated: Boolean,
        /**
         * Server-side bound port reported by the live session, if
         * activated. May differ from [MCP_PORT] when the rule was saved
         * with `bindPort=0` (OS-pick) or when the server retried a
         * conflicting bind.
         */
        val actualBoundPort: Int? = null,
    )

    /**
     * Drop every memoised ONCE_PER_SESSION consent so the next call to
     * each tool re-prompts. DENY decisions are never memoised, so this
     * only ever loosens the cache; nothing the user gave a one-time
     * blanket allow to gets stuck in a stricter state by clearing.
     */
    fun forgetMemoisedAgentAllows() {
        viewModelScope.launch { agentConsentManager.clearMemoised() }
    }

    val biometricAvailable: Boolean =
        authenticator.checkAvailability(appContext) == BiometricAuthenticator.Availability.AVAILABLE

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

    sealed interface BackupStatus {
        data object Idle : BackupStatus
        data object InProgress : BackupStatus
        data class Success(val message: String) : BackupStatus
        data class Error(val message: String) : BackupStatus
    }

    fun exportBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            _backupStatus.value = BackupStatus.InProgress
            try {
                val data = backupService.export(password)
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    ?: throw IllegalStateException("Could not open output stream")
                _backupStatus.value = BackupStatus.Success("Backup exported")
            } catch (e: Exception) {
                _backupStatus.value = BackupStatus.Error(e.message ?: "Export failed")
            }
        }
    }

    fun importBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            _backupStatus.value = BackupStatus.InProgress
            try {
                val data = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not open input stream")
                val result = backupService.import(data, password)
                val msg = "Restored ${result.count} items" +
                    if (result.errors.isNotEmpty()) " (${result.errors.size} errors)" else ""
                _backupStatus.value = BackupStatus.Success(msg)
            } catch (e: javax.crypto.AEADBadTagException) {
                _backupStatus.value = BackupStatus.Error("Wrong password")
            } catch (e: Exception) {
                _backupStatus.value = BackupStatus.Error(e.message ?: "Import failed")
            }
        }
    }

    fun clearBackupStatus() {
        _backupStatus.value = BackupStatus.Idle
    }

    val biometricEnabled: StateFlow<Boolean> = preferencesRepository.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val screenSecurity: StateFlow<Boolean> = preferencesRepository.screenSecurity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showLinuxVmCard: StateFlow<Boolean> = preferencesRepository.showLinuxVmCard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showDesktopsCard: StateFlow<Boolean> = preferencesRepository.showDesktopsCard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showSearchButton: StateFlow<Boolean> = preferencesRepository.showSearchButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showCopyOutputButton: StateFlow<Boolean> = preferencesRepository.showCopyOutputButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val keepScreenOnInTerminal: StateFlow<Boolean> = preferencesRepository.keepScreenOnInTerminal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val connectionLoggingEnabled: StateFlow<Boolean> = preferencesRepository.connectionLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val verboseLoggingEnabled: StateFlow<Boolean> = preferencesRepository.verboseLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val mcpAgentEndpointEnabled: StateFlow<Boolean> = preferencesRepository.mcpAgentEndpointEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * SSH-family profiles eligible for the "Tunnel MCP through this
     * profile" shortcut. The shortcut creates a remote port-forward
     * (`-R`) on the chosen session, binding [MCP_PORT] on the server
     * back to phone-side `127.0.0.1:[MCP_PORT]`. From an MCP client
     * running on the remote host this looks like a plain
     * `http://127.0.0.1:8730/mcp` endpoint — no MCP-server change
     * needed.
     */
    val sshProfilesForTunnel: StateFlow<List<ConnectionProfile>> = connectionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Save a `-R [MCP_PORT]:127.0.0.1:[MCP_PORT]` rule on [profileId]
     * and, if the profile has a connected SSH session, install it on
     * the live channel immediately so the user doesn't have to
     * reconnect. Mirrors `ConnectionsViewModel.savePortForwardRule`'s
     * live-apply path; on a fresh (unconnected) profile the rule is
     * still persisted and will activate on the next connect.
     */
    suspend fun createMcpReverseTunnel(profileId: String): McpTunnelResult {
        val rule = PortForwardRule(
            profileId = profileId,
            type = PortForwardRule.Type.REMOTE,
            bindAddress = "127.0.0.1",
            bindPort = MCP_PORT,
            targetHost = "127.0.0.1",
            targetPort = MCP_PORT,
            enabled = true,
        )
        portForwardRepository.save(rule)

        val session = sshSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
            ?: return McpTunnelResult(activated = false)
        val info = SshSessionManager.PortForwardInfo(
            ruleId = rule.id,
            type = SshSessionManager.PortForwardType.REMOTE,
            bindAddress = rule.bindAddress,
            bindPort = rule.bindPort,
            targetHost = rule.targetHost,
            targetPort = rule.targetPort,
        )
        withContext(Dispatchers.IO) {
            sshSessionManager.applyPortForwards(session.sessionId, listOf(info))
        }
        val bound = sshSessionManager.getSession(session.sessionId)
            ?.activeForwards
            ?.firstOrNull { it.ruleId == rule.id }
            ?.actualBoundPort
        return McpTunnelResult(activated = true, actualBoundPort = bound)
    }

    /**
     * True when there is at least one agent audit event the user
     * hasn't visited the activity screen since. Drives the unseen
     * dot on the "View agent activity" row in Settings.
     */
    val requireAgentConsentForWrites: StateFlow<Boolean> = preferencesRepository.requireAgentConsentForWrites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setRequireAgentConsentForWrites(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setRequireAgentConsentForWrites(enabled) }
    }

    val unseenAgentActivity: StateFlow<Boolean> = combine(
        agentAuditEventDao.observeLatestTimestamp(),
        preferencesRepository.lastViewedAgentAuditTimestamp,
    ) { latest, lastViewed ->
        latest != null && latest > lastViewed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val desktopInputMode: StateFlow<String> = preferencesRepository.desktopInputMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DIRECT")

    fun setDesktopInputMode(mode: String) {
        viewModelScope.launch { preferencesRepository.setDesktopInputMode(mode) }
    }

    val bandwidthAutoSuggest: StateFlow<Boolean> = preferencesRepository.bandwidthAutoSuggest
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setBandwidthAutoSuggest(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setBandwidthAutoSuggest(enabled) }
    }

    val mouseInputEnabled: StateFlow<Boolean> = preferencesRepository.mouseInputEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val mouseDragSelects: StateFlow<Boolean> = preferencesRepository.mouseDragSelects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val terminalRightClick: StateFlow<Boolean> = preferencesRepository.terminalRightClick
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val allowStandardKeyboard: StateFlow<Boolean> = preferencesRepository.allowStandardKeyboard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val rawKeyboardMode: StateFlow<Boolean> = preferencesRepository.rawKeyboardMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val interceptCtrlShiftV: StateFlow<Boolean> = preferencesRepository.interceptCtrlShiftV
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showTerminalTabBar: StateFlow<Boolean> = preferencesRepository.showTerminalTabBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val terminalFontSize: StateFlow<Int> = preferencesRepository.terminalFontSize
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.DEFAULT_FONT_SIZE,
        )

    val theme: StateFlow<UserPreferencesRepository.ThemeMode> = preferencesRepository.theme
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.ThemeMode.SYSTEM,
        )

    val sessionManager: StateFlow<UserPreferencesRepository.SessionManager> =
        preferencesRepository.sessionManager
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                UserPreferencesRepository.SessionManager.NONE,
            )

    val lockTimeout: StateFlow<UserPreferencesRepository.LockTimeout> =
        preferencesRepository.lockTimeout
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                UserPreferencesRepository.LockTimeout.IMMEDIATE,
            )

    fun setLockTimeout(timeout: UserPreferencesRepository.LockTimeout) {
        viewModelScope.launch { preferencesRepository.setLockTimeout(timeout) }
    }

    val sessionCommandOverride: StateFlow<String?> = preferencesRepository.sessionCommandOverride
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setSessionCommandOverride(command: String?) {
        viewModelScope.launch {
            preferencesRepository.setSessionCommandOverride(command)
        }
    }

    val terminalColorScheme: StateFlow<UserPreferencesRepository.TerminalColorScheme> =
        preferencesRepository.terminalColorScheme
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                UserPreferencesRepository.TerminalColorScheme.HAVEN,
            )

    val toolbarLayout: StateFlow<ToolbarLayout> = preferencesRepository.toolbarLayout
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ToolbarLayout.DEFAULT,
        )

    val toolbarLayoutJson: StateFlow<String> = preferencesRepository.toolbarLayoutJson
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ToolbarLayout.DEFAULT.toJson(),
        )

    val navBlockMode: StateFlow<NavBlockMode> = preferencesRepository.navBlockMode
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            NavBlockMode.ALIGNED,
        )

    val screenOrder: StateFlow<List<String>> = preferencesRepository.screenOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBiometricEnabled(enabled)
        }
    }

    fun setScreenSecurity(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setScreenSecurity(enabled)
        }
    }

    fun setShowLinuxVmCard(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowLinuxVmCard(enabled)
        }
    }

    fun setShowDesktopsCard(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowDesktopsCard(enabled)
        }
    }

    fun setShowSearchButton(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowSearchButton(enabled)
        }
    }

    fun setShowCopyOutputButton(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowCopyOutputButton(enabled)
        }
    }

    fun setKeepScreenOnInTerminal(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setKeepScreenOnInTerminal(enabled)
        }
    }

    fun setConnectionLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setConnectionLoggingEnabled(enabled)
        }
    }

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setVerboseLoggingEnabled(enabled)
        }
    }

    fun setMcpAgentEndpointEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMcpAgentEndpointEnabled(enabled)
        }
    }

    fun setMouseInputEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMouseInputEnabled(enabled)
        }
    }

    fun setMouseDragSelects(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMouseDragSelects(enabled)
        }
    }

    fun setTerminalRightClick(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTerminalRightClick(enabled)
        }
    }

    fun setAllowStandardKeyboard(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAllowStandardKeyboard(enabled)
        }
    }

    fun setRawKeyboardMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setRawKeyboardMode(enabled)
        }
    }

    /**
     * Set the keyboard mode by name. The repo already enforces mutual
     * exclusion between raw and standard, so the order of writes here
     * doesn't matter functionally — we set the new mode last so the
     * UI state lands deterministically.
     */
    fun setKeyboardMode(mode: String) {
        viewModelScope.launch {
            when (mode) {
                "RAW" -> {
                    preferencesRepository.setAllowStandardKeyboard(false)
                    preferencesRepository.setKeyboardCustomMode(false)
                    preferencesRepository.setRawKeyboardMode(true)
                }
                "STANDARD" -> {
                    preferencesRepository.setRawKeyboardMode(false)
                    preferencesRepository.setKeyboardCustomMode(false)
                    preferencesRepository.setAllowStandardKeyboard(true)
                }
                "CUSTOM" -> {
                    preferencesRepository.setRawKeyboardMode(false)
                    preferencesRepository.setAllowStandardKeyboard(false)
                    preferencesRepository.setKeyboardCustomMode(true)
                }
                else -> { // SECURE — all off
                    preferencesRepository.setAllowStandardKeyboard(false)
                    preferencesRepository.setRawKeyboardMode(false)
                    preferencesRepository.setKeyboardCustomMode(false)
                }
            }
        }
    }

    // --- Custom IME flag toggles (visible when keyboard mode = Custom) ---

    val keyboardCustomMode: StateFlow<Boolean> = preferencesRepository.keyboardCustomMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val imeFlagNoSuggestions: StateFlow<Boolean> = preferencesRepository.imeFlagNoSuggestions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val imeFlagVisiblePassword: StateFlow<Boolean> = preferencesRepository.imeFlagVisiblePassword
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val imeFlagAutoCorrect: StateFlow<Boolean> = preferencesRepository.imeFlagAutoCorrect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val imeFlagFullEditor: StateFlow<Boolean> = preferencesRepository.imeFlagFullEditor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val imeFlagNoExtractUi: StateFlow<Boolean> = preferencesRepository.imeFlagNoExtractUi
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val imeFlagNoPersonalizedLearning: StateFlow<Boolean> = preferencesRepository.imeFlagNoPersonalizedLearning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setImeFlagNoSuggestions(v: Boolean) { viewModelScope.launch { preferencesRepository.setImeFlagNoSuggestions(v) } }
    fun setImeFlagVisiblePassword(v: Boolean) { viewModelScope.launch { preferencesRepository.setImeFlagVisiblePassword(v) } }
    fun setImeFlagAutoCorrect(v: Boolean) { viewModelScope.launch { preferencesRepository.setImeFlagAutoCorrect(v) } }
    fun setImeFlagFullEditor(v: Boolean) { viewModelScope.launch { preferencesRepository.setImeFlagFullEditor(v) } }
    fun setImeFlagNoExtractUi(v: Boolean) { viewModelScope.launch { preferencesRepository.setImeFlagNoExtractUi(v) } }
    fun setImeFlagNoPersonalizedLearning(v: Boolean) { viewModelScope.launch { preferencesRepository.setImeFlagNoPersonalizedLearning(v) } }

    fun setInterceptCtrlShiftV(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setInterceptCtrlShiftV(enabled)
        }
    }

    fun setShowTerminalTabBar(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowTerminalTabBar(enabled)
        }
    }

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch {
            preferencesRepository.setTerminalFontSize(sizeSp)
        }
    }

    fun setTheme(mode: UserPreferencesRepository.ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setTheme(mode)
        }
    }

    fun setSessionManager(manager: UserPreferencesRepository.SessionManager) {
        viewModelScope.launch {
            preferencesRepository.setSessionManager(manager)
        }
    }

    fun setTerminalColorScheme(scheme: UserPreferencesRepository.TerminalColorScheme) {
        viewModelScope.launch {
            preferencesRepository.setTerminalColorScheme(scheme)
        }
    }

    fun setToolbarLayout(layout: ToolbarLayout) {
        viewModelScope.launch {
            preferencesRepository.setToolbarLayout(layout)
        }
    }

    fun setToolbarLayoutJson(json: String) {
        viewModelScope.launch {
            preferencesRepository.setToolbarLayoutJson(json)
        }
    }

    fun setNavBlockMode(mode: NavBlockMode) {
        viewModelScope.launch {
            preferencesRepository.setNavBlockMode(mode)
        }
    }

    fun setScreenOrder(routes: List<String>) {
        viewModelScope.launch {
            preferencesRepository.setScreenOrder(routes)
        }
    }

    val waylandShellCommand: StateFlow<String> = preferencesRepository.waylandShellCommand
        .stateIn(viewModelScope, SharingStarted.Eagerly, "/bin/sh -l")

    fun setWaylandShellCommand(command: String) {
        viewModelScope.launch {
            preferencesRepository.setWaylandShellCommand(command)
        }
    }

    val mediaExtensions: StateFlow<String> = preferencesRepository.mediaExtensions
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferencesRepository.DEFAULT_MEDIA_EXTENSIONS)

    fun setMediaExtensions(extensions: String) {
        viewModelScope.launch {
            preferencesRepository.setMediaExtensions(extensions)
        }
    }
}
