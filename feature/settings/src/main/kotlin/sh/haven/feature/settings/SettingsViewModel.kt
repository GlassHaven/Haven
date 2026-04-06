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
import sh.haven.core.data.backup.BackupService
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.security.BiometricAuthenticator
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val authenticator: BiometricAuthenticator,
    private val backupService: BackupService,
) : ViewModel() {

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

    val connectionLoggingEnabled: StateFlow<Boolean> = preferencesRepository.connectionLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val verboseLoggingEnabled: StateFlow<Boolean> = preferencesRepository.verboseLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val mouseInputEnabled: StateFlow<Boolean> = preferencesRepository.mouseInputEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val terminalRightClick: StateFlow<Boolean> = preferencesRepository.terminalRightClick
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
