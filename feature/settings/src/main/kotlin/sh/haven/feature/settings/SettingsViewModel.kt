package sh.haven.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import sh.haven.core.security.CredentialEncryption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.backup.BackupService
import sh.haven.core.data.db.AgentAuditEventDao
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.DesktopKeyPlacement
import sh.haven.core.data.preferences.EditModeControlsPlacement
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.update.UpdateChecker
import sh.haven.core.security.BiometricAuthenticator
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val authenticator: BiometricAuthenticator,
    private val backupService: BackupService,
    private val backupSyncManager: sh.haven.core.data.backup.BackupSyncManager,
    private val agentAuditEventDao: AgentAuditEventDao,
    private val agentConsentManager: AgentConsentManager,
    private val terminalFontInstaller: TerminalFontInstaller,
    private val connectionRepository: ConnectionRepository,
    private val mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder,
    private val biometricGate: sh.haven.core.data.keystore.BiometricGate,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val updateChecker: UpdateChecker,
) : ViewModel() {
    // --- Credential storage health + recovery (#579) ---

    private val KEYSTORE_TAG = "SettingsKeystore"

    /**
     * Whether the device Keystore can still serve Haven's credential master
     * key. Drives a recovery affordance that only appears when it is the
     * answer — a control that wipes every saved password has no business
     * sitting in Settings on a healthy device.
     */
    private val _credentialKeystore =
        MutableStateFlow(CredentialEncryption.Failure.NONE)
    val credentialKeystore: StateFlow<CredentialEncryption.Failure> =
        _credentialKeystore.asStateFlow()

    fun refreshCredentialKeystore() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = CredentialEncryption.probe(appContext)
            _credentialKeystore.value = result
            // Logged on every outcome, NONE included. A healthy device is the
            // control: without a positive "probe -> NONE" line there is no way
            // to tell "the keystore is fine" from "the probe never ran", and on
            // a device that IS broken (#579) this is the one line that says how
            // Haven classified it — which is what decides whether the repair is
            // offered at all. A reporter can read it back to us; we cannot
            // reach their Keystore any other way.
            Log.i(KEYSTORE_TAG, "credential keystore probe -> $result")
        }
    }

    /**
     * Drop the unusable master key so credentials can be stored again.
     *
     * Guarded on PERMANENT: on a transient failure the credentials are still
     * recoverable once the device is unlocked, and this would be the thing
     * that loses them. The UI does not offer the button in that state either,
     * so this is the second of two locks on the same door.
     */
    fun resetCredentialStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_credentialKeystore.value != CredentialEncryption.Failure.PERMANENT) {
                Log.w(KEYSTORE_TAG, "reset refused: keystore is ${_credentialKeystore.value}, not PERMANENT")
                return@launch
            }
            CredentialEncryption.resetCredentialStorage(appContext)
            _credentialKeystore.value = CredentialEncryption.probe(appContext)
            Log.i(KEYSTORE_TAG, "credential storage reset; keystore now ${_credentialKeystore.value}")
        }
    }

    // --- Opt-in update check (#578) ---

    /**
     * Which distribution the running copy was signed for. Decides whether the
     * update-check row is offered at all: on an F-Droid or self-built copy the
     * GitHub release cannot install over it, so there is nothing honest to
     * offer and the row says so instead of pretending.
     */
    val updateChannel: UpdateChecker.Channel by lazy { updateChecker.channel() }

    val updateCheckEnabled: StateFlow<Boolean> = preferencesRepository.updateCheckEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _updateCheckResult = MutableStateFlow<UpdateChecker.Result?>(null)

    /** Last "check now" outcome, or null if none has been run this session. */
    val updateCheckResult: StateFlow<UpdateChecker.Result?> = _updateCheckResult.asStateFlow()

    private val _updateCheckRunning = MutableStateFlow(false)
    val updateCheckRunning: StateFlow<Boolean> = _updateCheckRunning.asStateFlow()

    fun setUpdateCheckEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUpdateCheckEnabled(enabled)
        }
    }

    /**
     * Check on the user's explicit request. Deliberately not gated on the
     * preference — that switch governs the unattended launch check, and asking
     * for an answer now is a different act from agreeing to be asked daily.
     */
    fun checkForUpdateNow() {
        if (_updateCheckRunning.value) return
        viewModelScope.launch {
            _updateCheckRunning.value = true
            try {
                _updateCheckResult.value = updateChecker.check()
            } finally {
                _updateCheckRunning.value = false
            }
        }
    }


    val openBackupPasswordDialogEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0)

    init {
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                if (command is sh.haven.core.data.agent.AgentUiCommand.OpenBackupPasswordDialog) {
                    openBackupPasswordDialogEvent.emit(Unit)
                }
            }
        }
    }

    /**
     * Non-null when the WireGuard-exposed MCP endpoint is shadowed by another
     * app's system VPN holding the same address (so it's unreachable). Shown as
     * a warning in the MCP section. Published by the MCP server via
     * [sh.haven.core.data.agent.McpStatusHolder].
     */
    val mcpWireguardCollision: StateFlow<sh.haven.core.data.agent.WgCollisionInfo?> =
        mcpStatusHolder.wireguardCollision

    /**
     * Live state of the "near" (SSH) MCP carrier — whether it's actually
     * riding a connected interactive session right now, for the configured
     * `mcpTunnelEndpointProfileId`. Published by [sh.haven.app.agent.McpNearCarrier]
     * via [sh.haven.core.data.agent.McpStatusHolder].
     */
    val mcpNearCarrierStatus: StateFlow<sh.haven.core.data.agent.NearCarrierStatus> =
        mcpStatusHolder.nearCarrier

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

    /**
     * Drop every memoised ONCE_PER_SESSION consent so the next call to
     * each tool re-prompts. DENY decisions are never memoised, so this
     * only ever loosens the cache; nothing the user gave a one-time
     * blanket allow to gets stuck in a stricter state by clearing.
     * Also wipes the *persistent* per-client auto-approve set so "Forget
     * remembered allows" fully resets agent trust, not just the session
     * caches.
     */
    fun forgetMemoisedAgentAllows() {
        viewModelScope.launch {
            agentConsentManager.clearMemoised()
            preferencesRepository.clearMcpBypassConsentClients()
        }
    }

    /** Paired MCP clients (clientInfo.name), sorted for stable display. */
    val mcpAllowedClients: StateFlow<List<String>> = preferencesRepository.mcpAllowedClients
        .map { it.sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Subset of paired clients the user has opted into auto-approval for. */
    val mcpBypassConsentClients: StateFlow<Set<String>> =
        preferencesRepository.mcpBypassConsentClients
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Toggle persistent auto-approval (skip per-call consent prompts) for
     * a paired client. Off by default; opting a client in means its tool
     * calls — including destructive ones — run without a prompt until the
     * user toggles it back off or un-pairs the client.
     */
    fun setMcpClientConsentBypass(name: String, enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setMcpClientConsentBypass(name, enabled) }
    }

    /** Remove a client from the allowlist; it must re-pair to reconnect. */
    fun unpairMcpClient(name: String) {
        viewModelScope.launch { preferencesRepository.removeMcpAllowedClient(name) }
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
                // Crypto + the SAF output-stream write run on IO: a cloud-backed
                // DocumentsProvider target (e.g. a NextCloud-synced folder) does
                // network I/O on write, which throws NetworkOnMainThreadException
                // if left on the main thread. See #145.
                withContext(Dispatchers.IO) {
                    val data = backupService.export(password)
                    appContext.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                        ?: throw IllegalStateException("Could not open output stream")
                }
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
                // Reading the .enc via the SAF input stream must run off the main
                // thread: picking the file straight from a cloud-synced directory
                // (e.g. NextCloud) reads through a DocumentsProvider that performs
                // network I/O, which throws NetworkOnMainThreadException on the
                // main thread. Copying to local storage first was the only
                // workaround before this. See #145.
                val result = withContext(Dispatchers.IO) {
                    val data = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not open input stream")
                    backupService.import(data, password)
                }
                val msg = "Restored ${result.count} items" +
                    if (result.errors.isNotEmpty()) " (${result.errors.size} errors)" else ""
                _backupStatus.value = BackupStatus.Success(msg)
            } catch (e: javax.crypto.AEADBadTagException) {
                _backupStatus.value = BackupStatus.Error("Wrong password")
            } catch (e: Exception) {
                // Some Android exceptions (storage URI revocations, security
                // exceptions, certain native cipher failures) come through
                // with a null message. Don't swallow them as a bare "Import
                // failed" — surface the exception class as a fallback so a
                // bug report (e.g. #145) carries enough to localise the
                // failure mode without round-tripping through the user.
                val detail = e.message ?: "${e.javaClass.simpleName} (no message)"
                _backupStatus.value = BackupStatus.Error("Import failed: $detail")
            }
        }
    }

    fun clearBackupStatus() {
        _backupStatus.value = BackupStatus.Idle
    }

    // ── Encrypted backup sync to an existing remote (#323) ────────────────

    /** SFTP/SMB/rclone profiles eligible as a backup destination (id + label). */
    val backupSyncCandidates: StateFlow<List<Pair<String, String>>> =
        connectionRepository.observeAll()
            .map { list ->
                list.filter { it.connectionType in BACKUP_SYNC_TYPES }
                    .map { it.id to it.label }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backupSyncProfileId: StateFlow<String?> = preferencesRepository.backupSyncProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val backupSyncPath: StateFlow<String> = preferencesRepository.backupSyncPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "haven-backup.enc")

    fun setBackupSyncDestination(profileId: String?, path: String) {
        viewModelScope.launch { preferencesRepository.setBackupSyncDestination(profileId, path) }
    }

    val backupAutoSyncEnabled: StateFlow<Boolean> = preferencesRepository.backupAutoSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Enable auto-push (#359), storing [passphrase] encrypted for the
     * background job, or disable it (which deletes the stored passphrase).
     * The scheduling itself reacts to the preference in the app layer.
     */
    fun setBackupAutoSync(enabled: Boolean, passphrase: String?) {
        viewModelScope.launch { preferencesRepository.setBackupAutoSync(enabled, passphrase) }
    }

    val backupAutoPullEnabled: StateFlow<Boolean> = preferencesRepository.backupAutoPullEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setBackupAutoPull(enabled: Boolean, passphrase: String?) {
        viewModelScope.launch { preferencesRepository.setBackupAutoPull(enabled, passphrase) }
    }

    val backupAutoPullIntervalMinutes: StateFlow<Int> = preferencesRepository.backupAutoPullIntervalMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1440)

    fun setBackupAutoPullInterval(minutes: Int) {
        viewModelScope.launch { preferencesRepository.setBackupAutoPullInterval(minutes) }
    }

    val backupSyncPassphrase: StateFlow<String?> = preferencesRepository.backupSyncPassphraseFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun saveBackupSyncPassphrase(passphrase: String) {
        viewModelScope.launch { preferencesRepository.saveBackupSyncPassphrase(passphrase) }
    }

    fun clearBackupSyncPassphrase() {
        viewModelScope.launch { preferencesRepository.clearBackupSyncPassphrase() }
    }

    /** Encrypt the config and write it to the configured remote. */
    fun pushBackupToRemote(password: String) {
        val profileId = backupSyncProfileId.value
        if (profileId == null) {
            _backupStatus.value = BackupStatus.Error("Choose a backup destination first")
            return
        }
        viewModelScope.launch {
            _backupStatus.value = BackupStatus.InProgress
            try {
                withContext(Dispatchers.IO) {
                    backupSyncManager.push(profileId, backupSyncPath.value, password)
                }
                _backupStatus.value = BackupStatus.Success("Backup pushed to remote")
            } catch (e: Exception) {
                _backupStatus.value = BackupStatus.Error(backupSyncError(e))
            }
        }
    }

    /** Read the backup from the configured remote, decrypt, and restore it. */
    fun pullBackupFromRemote(password: String) {
        val profileId = backupSyncProfileId.value
        if (profileId == null) {
            _backupStatus.value = BackupStatus.Error("Choose a backup destination first")
            return
        }
        viewModelScope.launch {
            _backupStatus.value = BackupStatus.InProgress
            try {
                val result = withContext(Dispatchers.IO) {
                    backupSyncManager.pull(profileId, backupSyncPath.value, password)
                }
                val msg = "Restored ${result.count} items" +
                    if (result.errors.isNotEmpty()) " (${result.errors.size} errors)" else ""
                _backupStatus.value = BackupStatus.Success(msg)
            } catch (e: javax.crypto.AEADBadTagException) {
                _backupStatus.value = BackupStatus.Error("Wrong password")
            } catch (e: Exception) {
                _backupStatus.value = BackupStatus.Error(backupSyncError(e))
            }
        }
    }

    private fun backupSyncError(e: Exception): String =
        "Backup sync failed: ${e.message ?: "${e.javaClass.simpleName} (no message)"}"

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

    /** #418 debug: RDP RemoteFX-Progressive upgrade-tile decoding. */
    val rdpProgressiveUpgrade: StateFlow<Boolean> = preferencesRepository.rdpProgressiveUpgrade
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setRdpProgressiveUpgrade(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setRdpProgressiveUpgrade(enabled) }
    }

    /** #425: advertise RDP H.264/AVC420 so KRDP (H.264-only) can render. On by default. */
    val rdpAvcEnabled: StateFlow<Boolean> = preferencesRepository.rdpAvcEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setRdpAvcEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setRdpAvcEnabled(enabled) }
    }

    val keepScreenOnInTerminal: StateFlow<Boolean> = preferencesRepository.keepScreenOnInTerminal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hideNavBarInTerminal: StateFlow<Boolean> = preferencesRepository.hideNavBarInTerminal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val connectionLoggingEnabled: StateFlow<Boolean> = preferencesRepository.connectionLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val excludeFromRecents: StateFlow<Boolean> = preferencesRepository.excludeFromRecents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val alwaysShowAllTabs: StateFlow<Boolean> = preferencesRepository.alwaysShowAllTabs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val usbGuestExposureEnabled: StateFlow<Boolean> = preferencesRepository.usbGuestExposureEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val remoteClipboardToLocalEnabled: StateFlow<Boolean> = preferencesRepository.remoteClipboardToLocalEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val audioBridgeEnabled: StateFlow<Boolean> = preferencesRepository.audioBridgeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val verboseLoggingEnabled: StateFlow<Boolean> = preferencesRepository.verboseLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val mcpAgentEndpointEnabled: StateFlow<Boolean> = preferencesRepository.mcpAgentEndpointEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** SSH profiles eligible to host the dedicated MCP reverse tunnel. */
    val sshProfiles: StateFlow<List<McpTunnelEndpointOption>> =
        connectionRepository.observeAll()
            .map { profiles ->
                profiles.filter { it.isSsh }
                    .map { McpTunnelEndpointOption(it.id, it.label) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Currently-selected MCP reverse-tunnel endpoint profile id, or null. */
    val mcpTunnelEndpointProfileId: StateFlow<String?> =
        preferencesRepository.mcpTunnelEndpointProfileId
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Whether the MCP server also binds on the active WireGuard tunnel. */
    val mcpWireguardEnabled: StateFlow<Boolean> = preferencesRepository.mcpWireguardEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setMcpWireguardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMcpWireguardEnabled(enabled)
        }
    }

    /** Whether the MCP server also binds the device's Wi-Fi/LAN address. */
    val mcpLanBindEnabled: StateFlow<Boolean> = preferencesRepository.mcpLanBindEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setMcpLanBindEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMcpLanBindEnabled(enabled)
        }
    }

    /** Whether loopback (local) MCP clients skip pairing + consent prompts. */
    val trustLoopbackMcpClients: StateFlow<Boolean> = preferencesRepository.trustLoopbackMcpClients
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setTrustLoopbackMcpClients(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTrustLoopbackMcpClients(enabled)
        }
    }

    val agentAllowFileRead: StateFlow<Boolean> = preferencesRepository.agentAllowFileRead
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val agentAllowTerminalInputQueue: StateFlow<Boolean> =
        preferencesRepository.agentAllowTerminalInputQueue
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    /** Desktop size Haven asks RDP servers for (#422). */
    val rdpDesktopWidth: StateFlow<Int> = preferencesRepository.rdpDesktopWidth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1920)
    val rdpDesktopHeight: StateFlow<Int> = preferencesRepository.rdpDesktopHeight
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1080)

    fun setRdpDesktopSize(width: Int, height: Int) {
        viewModelScope.launch { preferencesRepository.setRdpDesktopSize(width, height) }
    }

    val gpuUseVenus: StateFlow<Boolean> = preferencesRepository.gpuUseVenus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setGpuUseVenus(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setGpuUseVenus(enabled) }
    }

    val bandwidthAutoSuggest: StateFlow<Boolean> = preferencesRepository.bandwidthAutoSuggest
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setBandwidthAutoSuggest(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setBandwidthAutoSuggest(enabled) }
    }

    val mouseInputEnabled: StateFlow<Boolean> = preferencesRepository.mouseInputEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val terminalRightClick: StateFlow<Boolean> = preferencesRepository.terminalRightClick
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val terminalTapToPositionCursor: StateFlow<Boolean> = preferencesRepository.terminalTapToPositionCursor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val allowStandardKeyboard: StateFlow<Boolean> = preferencesRepository.allowStandardKeyboard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val rawKeyboardMode: StateFlow<Boolean> = preferencesRepository.rawKeyboardMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val interceptCtrlShiftV: StateFlow<Boolean> = preferencesRepository.interceptCtrlShiftV
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val reflowTerminalOnKeyboard: StateFlow<Boolean> = preferencesRepository.reflowTerminalOnKeyboard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val terminalTabTitlesFollowSession: StateFlow<Boolean> =
        preferencesRepository.terminalTabTitlesFollowSession
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showTerminalTabBar: StateFlow<Boolean> = preferencesRepository.showTerminalTabBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val terminalFontSize: StateFlow<Int> = preferencesRepository.terminalFontSize
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.DEFAULT_FONT_SIZE,
        )

    val terminalScrollbackRows: StateFlow<Int> = preferencesRepository.terminalScrollbackRows
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.DEFAULT_SCROLLBACK_ROWS,
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

    val terminalBackgroundOpacity: StateFlow<Float> =
        preferencesRepository.terminalBackgroundOpacity
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    val terminalAutoSwitchScheme: StateFlow<Boolean> =
        preferencesRepository.terminalAutoSwitchScheme
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val terminalApplySchemePalette: StateFlow<Boolean> =
        preferencesRepository.terminalApplySchemePalette
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val terminalLightColorScheme: StateFlow<UserPreferencesRepository.TerminalColorScheme> =
        preferencesRepository.terminalLightColorScheme
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                UserPreferencesRepository.TerminalColorScheme.LIGHT,
            )

    val terminalDarkColorScheme: StateFlow<UserPreferencesRepository.TerminalColorScheme> =
        preferencesRepository.terminalDarkColorScheme
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

    /** Off-toolbar snippet library (#244) — snippets reachable from the scissors
     *  sheet without a dedicated toolbar button. */
    val snippetLibrary: StateFlow<List<ToolbarItem.Custom>> = preferencesRepository.snippetLibrary
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )

    val navBlockMode: StateFlow<NavBlockMode> = preferencesRepository.navBlockMode
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            NavBlockMode.ALIGNED,
        )

    val toolbarUniformGrid: StateFlow<Boolean> = preferencesRepository.toolbarUniformGrid
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    val editModeControlsPlacement: StateFlow<EditModeControlsPlacement> =
        preferencesRepository.editModeControlsPlacement
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                EditModeControlsPlacement.LEFT,
            )

    val desktopKeyPlacement: StateFlow<DesktopKeyPlacement> =
        preferencesRepository.desktopKeyPlacement
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                DesktopKeyPlacement.LEFT,
            )

    val toolbarMinButtonWidth: StateFlow<Int> = preferencesRepository.toolbarMinButtonWidth
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.DEFAULT_TOOLBAR_MIN_BUTTON_WIDTH,
        )

    val screenOrder: StateFlow<List<String>> = preferencesRepository.screenOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Turning the app lock OFF must itself be authenticated (#252):
            // otherwise anyone with the unlocked app strips the lock and
            // gains unprompted access to everything it guards. Enabling
            // needs no prior auth. Fail closed — a denied prompt leaves
            // the lock on (the bound switch reverts to its real state).
            if (!enabled && preferencesRepository.biometricEnabled.first()) {
                val decision = biometricGate.request(
                    label = "Disable app lock",
                    detail = "Authenticate to turn off biometric app lock",
                )
                if (decision != sh.haven.core.data.keystore.BiometricGate.Decision.ALLOW) return@launch
            }
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

    fun setHideNavBarInTerminal(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setHideNavBarInTerminal(enabled)
        }
    }

    fun setConnectionLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setConnectionLoggingEnabled(enabled)
        }
    }

    fun setExcludeFromRecents(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setExcludeFromRecents(enabled)
        }
    }

    fun setUsbGuestExposureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUsbGuestExposureEnabled(enabled)
        }
    }

    fun setRemoteClipboardToLocalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setRemoteClipboardToLocalEnabled(enabled)
        }
    }

    fun setAudioBridgeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAudioBridgeEnabled(enabled)
        }
    }

    fun setAlwaysShowAllTabs(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAlwaysShowAllTabs(enabled)
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

    fun setMcpTunnelEndpointProfileId(profileId: String?) {
        viewModelScope.launch {
            preferencesRepository.setMcpTunnelEndpointProfileId(profileId)
        }
    }

    fun setAgentAllowFileRead(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAgentAllowFileRead(enabled)
        }
    }

    fun setAgentAllowTerminalInputQueue(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAgentAllowTerminalInputQueue(enabled)
        }
    }

    fun setMouseInputEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMouseInputEnabled(enabled)
        }
    }

    fun setTerminalRightClick(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTerminalRightClick(enabled)
        }
    }

    fun setTerminalTapToPositionCursor(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTerminalTapToPositionCursor(enabled)
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

    fun setReflowTerminalOnKeyboard(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setReflowTerminalOnKeyboard(enabled)
        }
    }

    fun setTerminalTabTitlesFollowSession(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTerminalTabTitlesFollowSession(enabled)
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

    fun setTerminalScrollbackRows(rows: Int) {
        viewModelScope.launch {
            preferencesRepository.setTerminalScrollbackRows(rows)
        }
    }

    val prootIdleTimeoutMinutes: StateFlow<Int> = preferencesRepository.prootIdleTimeoutMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setProotIdleTimeoutMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setProotIdleTimeoutMinutes(minutes)
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

    fun setTerminalBackgroundOpacity(opacity: Float) {
        viewModelScope.launch {
            preferencesRepository.setTerminalBackgroundOpacity(opacity)
        }
    }

    fun setTerminalAutoSwitchScheme(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTerminalAutoSwitchScheme(enabled)
        }
    }

    fun setTerminalApplySchemePalette(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTerminalApplySchemePalette(enabled)
        }
    }

    fun setTerminalLightColorScheme(scheme: UserPreferencesRepository.TerminalColorScheme) {
        viewModelScope.launch {
            preferencesRepository.setTerminalLightColorScheme(scheme)
        }
    }

    fun setTerminalDarkColorScheme(scheme: UserPreferencesRepository.TerminalColorScheme) {
        viewModelScope.launch {
            preferencesRepository.setTerminalDarkColorScheme(scheme)
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

    fun setSnippetLibrary(items: List<ToolbarItem.Custom>) {
        viewModelScope.launch {
            preferencesRepository.setSnippetLibrary(items)
        }
    }

    fun setNavBlockMode(mode: NavBlockMode) {
        viewModelScope.launch {
            preferencesRepository.setNavBlockMode(mode)
        }
    }

    fun setToolbarUniformGrid(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setToolbarUniformGrid(enabled)
        }
    }

    fun setEditModeControlsPlacement(placement: EditModeControlsPlacement) {
        viewModelScope.launch {
            preferencesRepository.setEditModeControlsPlacement(placement)
        }
    }

    fun setDesktopKeyPlacement(placement: DesktopKeyPlacement) {
        viewModelScope.launch {
            preferencesRepository.setDesktopKeyPlacement(placement)
        }
    }

    fun setToolbarMinButtonWidth(dp: Int) {
        viewModelScope.launch {
            preferencesRepository.setToolbarMinButtonWidth(dp)
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

    val terminalPromptChars: StateFlow<String> = preferencesRepository.terminalPromptChars
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setTerminalPromptChars(chars: String) {
        viewModelScope.launch {
            preferencesRepository.setTerminalPromptChars(chars)
        }
    }

    val terminalLocale: StateFlow<String> = preferencesRepository.terminalLocale
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferencesRepository.DEFAULT_TERMINAL_LOCALE)

    fun setTerminalLocale(locale: String) {
        viewModelScope.launch {
            preferencesRepository.setTerminalLocale(locale)
        }
    }

    companion object {
        /** Connection types that can host a backup file (#323). LOCAL is excluded — it's the device itself, not a cross-device destination. */
        private val BACKUP_SYNC_TYPES = setOf("SSH", "SMB", "RCLONE")
    }
}

/** A selectable SSH profile for the MCP reverse-tunnel endpoint picker. */
data class McpTunnelEndpointOption(val id: String, val label: String)
