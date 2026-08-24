package sh.haven.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import sh.haven.core.security.CredentialEncryption
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.haven.core.data.preferences.UserPreferencesRepository.Companion.MAX_RDP_DIMENSION
import sh.haven.core.data.preferences.UserPreferencesRepository.Companion.MIN_RDP_DIMENSION
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.ui.navigation.Screen
import sh.haven.core.data.NativeFeatures
import sh.haven.core.data.preferences.DesktopKeyPlacement
import sh.haven.core.data.preferences.EditModeControlsPlacement
import sh.haven.core.data.preferences.MACRO_PRESETS
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarEditorOps
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarKey
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.update.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    openToolbarConfig: Boolean = false,
    onToolbarConfigConsumed: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val credentialKeystore by viewModel.credentialKeystore.collectAsState()
    // Probed on entry rather than continuously: the answer only changes
    // when the device's Keystore state does, which is not a per-frame event.
    LaunchedEffect(Unit) { viewModel.refreshCredentialKeystore() }
    val screenSecurity by viewModel.screenSecurity.collectAsState()
    val updateCheckEnabled by viewModel.updateCheckEnabled.collectAsState()
    val updateCheckResult by viewModel.updateCheckResult.collectAsState()
    val updateCheckRunning by viewModel.updateCheckRunning.collectAsState()
    val showLinuxVmCard by viewModel.showLinuxVmCard.collectAsState()
    val showDesktopsCard by viewModel.showDesktopsCard.collectAsState()
    val lockTimeout by viewModel.lockTimeout.collectAsState()
    val fontSize by viewModel.terminalFontSize.collectAsState()
    val scrollbackRows by viewModel.terminalScrollbackRows.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val sessionManager by viewModel.sessionManager.collectAsState()
    val colorScheme by viewModel.terminalColorScheme.collectAsState()
    val autoSwitchColorScheme by viewModel.terminalAutoSwitchScheme.collectAsState()
    val applySchemePalette by viewModel.terminalApplySchemePalette.collectAsState()
    val lightColorScheme by viewModel.terminalLightColorScheme.collectAsState()
    val darkColorScheme by viewModel.terminalDarkColorScheme.collectAsState()
    val backgroundOpacity by viewModel.terminalBackgroundOpacity.collectAsState()
    val toolbarLayout by viewModel.toolbarLayout.collectAsState()
    val toolbarLayoutJson by viewModel.toolbarLayoutJson.collectAsState()
    val snippetLibrary by viewModel.snippetLibrary.collectAsState()
    val navBlockMode by viewModel.navBlockMode.collectAsState()
    val toolbarUniformGrid by viewModel.toolbarUniformGrid.collectAsState()
    val editModeControlsPlacement by viewModel.editModeControlsPlacement.collectAsState()
    val desktopKeyPlacement by viewModel.desktopKeyPlacement.collectAsState()
    val toolbarMinButtonWidth by viewModel.toolbarMinButtonWidth.collectAsState()
    val showSearchButton by viewModel.showSearchButton.collectAsState()
    val showCopyOutputButton by viewModel.showCopyOutputButton.collectAsState()
    val keepScreenOnInTerminal by viewModel.keepScreenOnInTerminal.collectAsState()
    val hideNavBarInTerminal by viewModel.hideNavBarInTerminal.collectAsState()
    val connectionLoggingEnabled by viewModel.connectionLoggingEnabled.collectAsState()
    val excludeFromRecents by viewModel.excludeFromRecents.collectAsState()
    val verboseLoggingEnabled by viewModel.verboseLoggingEnabled.collectAsState()
    val rdpProgressiveUpgrade by viewModel.rdpProgressiveUpgrade.collectAsState()
    val rdpAvcEnabled by viewModel.rdpAvcEnabled.collectAsState()
    val mcpAgentEndpointEnabled by viewModel.mcpAgentEndpointEnabled.collectAsState()
    val agentAllowFileRead by viewModel.agentAllowFileRead.collectAsState()
    val unseenAgentActivity by viewModel.unseenAgentActivity.collectAsState()
    val requireAgentConsentForWrites by viewModel.requireAgentConsentForWrites.collectAsState()
    val mouseInputEnabled by viewModel.mouseInputEnabled.collectAsState()
    val remoteClipboardToLocal by viewModel.remoteClipboardToLocalEnabled.collectAsState()
    val desktopInputMode by viewModel.desktopInputMode.collectAsState()
    val gpuUseVenus by viewModel.gpuUseVenus.collectAsState()
    val bandwidthAutoSuggest by viewModel.bandwidthAutoSuggest.collectAsState()
    val terminalRightClick by viewModel.terminalRightClick.collectAsState()
    val tapToPositionCursorOnPrompt by viewModel.terminalTapToPositionCursor.collectAsState()
    val allowStandardKeyboard by viewModel.allowStandardKeyboard.collectAsState()
    val rawKeyboardMode by viewModel.rawKeyboardMode.collectAsState()
    val keyboardCustomMode by viewModel.keyboardCustomMode.collectAsState()
    val interceptCtrlShiftV by viewModel.interceptCtrlShiftV.collectAsState()
    val reflowTerminalOnKeyboard by viewModel.reflowTerminalOnKeyboard.collectAsState()
    val showTerminalTabBar by viewModel.showTerminalTabBar.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val waylandShellCommand by viewModel.waylandShellCommand.collectAsState()
    val mediaExtensions by viewModel.mediaExtensions.collectAsState()
    val terminalPromptChars by viewModel.terminalPromptChars.collectAsState()
    val terminalLocale by viewModel.terminalLocale.collectAsState()
    var showAuditLog by remember { mutableStateOf(false) }
    var showProotInstallLog by remember { mutableStateOf(false) }
    var showAgentActivity by remember { mutableStateOf(false) }
    var showPairedClients by remember { mutableStateOf(false) }
    var showFontUrlDialog by remember { mutableStateOf(false) }
    var showRecommendedFontsDialog by remember { mutableStateOf(false) }
    // Lifted to SettingsScreen scope so a dialog's confirm handler can
    // dismiss the dialog AND still complete its async work + show the
    // result Toast. Earlier these scopes lived inside the `if (showX)`
    // blocks, which meant dismissing the dialog (showX = false) yanked
    // the if-branch out of composition and cancelled the scope before
    // the launched coroutine produced anything — silent install bug.
    val settingsScope = rememberCoroutineScope()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showWaylandShellDialog by remember { mutableStateOf(false) }
    var showProotIdleDialog by remember { mutableStateOf(false) }
    val prootIdleMinutes by viewModel.prootIdleTimeoutMinutes.collectAsState()
    var showMediaExtensionsDialog by remember { mutableStateOf(false) }
    var showPromptCharsDialog by remember { mutableStateOf(false) }
    var showLocaleDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showScrollbackRowsDialog by remember { mutableStateOf(false) }
    var showSessionManagerDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorSchemeDialog by remember { mutableStateOf(false) }
    var showLightColorSchemeDialog by remember { mutableStateOf(false) }
    var showDarkColorSchemeDialog by remember { mutableStateOf(false) }
    var showBackgroundOpacityDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showToolbarConfigDialog by remember { mutableStateOf(false) }
    var showKeyboardModeDialog by remember { mutableStateOf(false) }
    var showImeFlagsDialog by remember { mutableStateOf(false) }
    LaunchedEffect(openToolbarConfig) {
        if (openToolbarConfig) {
            showToolbarConfigDialog = true
            // Tell the host the request has been consumed so it can clear
            // its flag. Doing it from inside the effect (after the state
            // write) avoids the race where a sibling reset would cancel
            // this LaunchedEffect before the body had a chance to run.
            onToolbarConfigConsumed()
        }
    }
    var showBackupPasswordDialog by remember { mutableStateOf<BackupAction?>(null) }
    var showBackupSyncDialog by remember { mutableStateOf(false) }
    var showLockTimeoutDialog by remember { mutableStateOf(false) }
    var showCredentialResetDialog by remember { mutableStateOf(false) }
    var showOsc133SetupDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.openBackupPasswordDialogEvent.collect {
            showBackupPasswordDialog = BackupAction.PullRemote
        }
    }
    var showScreenOrderDialog by remember { mutableStateOf(false) }
    val screenOrder by viewModel.screenOrder.collectAsState()

    val context = LocalContext.current

    // #510: the terminal build ships no RDP client, no Wayland compositor and
    // no ffmpeg, so the settings that tune them are noise there — a toggle for
    // something that cannot run is worse than no toggle at all.
    //
    // Gated per row rather than by hiding whole sections: the Desktop section
    // also holds VNC settings ("Suggest 256 colours on slow VNC links",
    // touchpad input) and VNC works in every build, since that client is
    // Kotlin. Hiding the section would take working features with it.
    val nativeFeatures = remember(context) { NativeFeatures(context) }

    // Strings hoisted out of event callbacks / coroutines below: the Compose
    // LocalContextGetResourceValueCall lint forbids context.getString() at those
    // sites, and stringResource() (a @Composable) can't be called inside them.
    // Format-arg strings hoist the raw template; callers apply String.format().
    val fontSetToastTmpl = stringResource(R.string.settings_terminal_font_set_toast)
    val fontDecodeFailedMsg = stringResource(R.string.settings_terminal_font_decode_failed_toast)
    val fontInstalledToastTmpl = stringResource(R.string.settings_font_installed_toast)
    val fontInstallingToastTmpl = stringResource(R.string.settings_font_installing_toast)
    val fontNamedInstalledToastTmpl = stringResource(R.string.settings_font_named_installed_toast)
    val fontResetMsg = stringResource(R.string.settings_terminal_font_reset_toast)
    val mcpUrlCopiedMsg = stringResource(R.string.settings_mcp_url_copied_toast)
    val mcpConfigCopiedMsg = stringResource(R.string.settings_mcp_config_copied_toast)
    val agentAllowsClearedMsg = stringResource(R.string.settings_agent_allows_cleared_toast)

    // SAF launchers for backup/restore
    var pendingPassword by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) viewModel.exportBackup(uri, pendingPassword)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            showBackupPasswordDialog = BackupAction.Restore(uri)
        }
    }
    // Custom-terminal-font picker (#123). MIME filter is loose because
    // many file managers report TTF/OTF as application/octet-stream;
    // we re-validate via Typeface.createFromFile after import.
    val fontImportScope = rememberCoroutineScope()
    val fontImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val displayName = run {
                var n: String? = null
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (ix >= 0) n = c.getString(ix)
                        }
                    }
                }
                n ?: uri.lastPathSegment ?: "font.ttf"
            }
            fontImportScope.launch {
                val path = viewModel.importCustomTerminalFont(uri, displayName)
                if (path != null) {
                    Toast.makeText(context, String.format(fontSetToastTmpl, displayName), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        fontDecodeFailedMsg,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // Show toast on backup status changes
    LaunchedEffect(backupStatus) {
        when (val status = backupStatus) {
            is SettingsViewModel.BackupStatus.Success -> {
                Toast.makeText(context, status.message, Toast.LENGTH_SHORT).show()
                viewModel.clearBackupStatus()
            }
            is SettingsViewModel.BackupStatus.Error -> {
                Toast.makeText(context, status.message, Toast.LENGTH_LONG).show()
                viewModel.clearBackupStatus()
            }
            else -> {}
        }
    }

    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    if (showAuditLog) {
        AuditLogScreen(onBack = { showAuditLog = false })
        return
    }
    if (showProotInstallLog) {
        ProotInstallLogScreen(onBack = { showProotInstallLog = false })
        return
    }
    if (showAgentActivity) {
        AgentActivityScreen(onBack = { showAgentActivity = false })
        return
    }
    if (showPairedClients) {
        PairedClientsScreen(onBack = { showPairedClients = false })
        return
    }
    if (showFontUrlDialog) {
        FontFromUrlDialog(
            onInstall = { url ->
                showFontUrlDialog = false
                settingsScope.launch {
                    when (val r = viewModel.installTerminalFontFromUrl(url)) {
                        is sh.haven.core.data.font.TerminalFontInstaller.Result.Success ->
                            Toast.makeText(
                                context,
                                String.format(fontInstalledToastTmpl, (r.bytesInstalled / 1024).toInt()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        is sh.haven.core.data.font.TerminalFontInstaller.Result.Failure ->
                            Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showFontUrlDialog = false },
        )
    }
    if (showRecommendedFontsDialog) {
        RecommendedFontsDialog(
            onPick = { name, url ->
                showRecommendedFontsDialog = false
                Toast.makeText(context, String.format(fontInstallingToastTmpl, name), Toast.LENGTH_SHORT).show()
                settingsScope.launch {
                    when (val r = viewModel.installTerminalFontFromUrl(url)) {
                        is sh.haven.core.data.font.TerminalFontInstaller.Result.Success ->
                            Toast.makeText(
                                context,
                                String.format(fontNamedInstalledToastTmpl, name, (r.bytesInstalled / 1024).toInt()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        is sh.haven.core.data.font.TerminalFontInstaller.Result.Failure ->
                            Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showRecommendedFontsDialog = false },
        )
    }
    // Collapsible settings sections — all collapsed by default so the screen
    // opens compact and users expand only what they need.
    val settingsExpanded = remember {
        androidx.compose.runtime.mutableStateListOf(
            false, false, false, false, false, false, false, false, false, false,
            false, // [10] AI agent (MCP)
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        CollapsibleSettingsSection(stringResource(R.string.settings_section_security_privacy), settingsExpanded[0], { settingsExpanded[0] = !settingsExpanded[0] }) {
        if (viewModel.biometricAvailable) {
            SettingsToggleItem(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.settings_biometric_title),
                subtitle = stringResource(R.string.settings_biometric_subtitle),
                checked = biometricEnabled,
                onCheckedChange = viewModel::setBiometricEnabled,
            )
            if (biometricEnabled) {
                SettingsItem(
                    icon = Icons.Filled.Timer,
                    title = stringResource(R.string.settings_lock_timeout_title),
                    subtitle = lockTimeout.label,
                    onClick = { showLockTimeoutDialog = true },
                )
            }
        }
        SettingsToggleItem(
            icon = Icons.Filled.ScreenLockPortrait,
            title = stringResource(R.string.settings_prevent_screenshots_title),
            subtitle = stringResource(R.string.settings_prevent_screenshots_subtitle),
            checked = screenSecurity,
            onCheckedChange = viewModel::setScreenSecurity,
        )
        SettingsToggleItem(
            icon = Icons.Filled.ContentPaste,
            title = stringResource(R.string.settings_remote_clipboard_title),
            subtitle = stringResource(R.string.settings_remote_clipboard_subtitle),
            checked = remoteClipboardToLocal,
            onCheckedChange = viewModel::setRemoteClipboardToLocalEnabled,
        )
        // #579: only rendered when the Keystore has permanently lost Haven's
        // credential master key. A control that wipes every saved password is
        // not something to leave lying about in Settings on a healthy device,
        // and on a TRANSIENT failure the credentials are still recoverable —
        // offering the button then would be the thing that loses them.
        if (credentialKeystore == CredentialEncryption.Failure.PERMANENT) {
            SettingsItem(
                icon = Icons.Filled.Warning,
                title = stringResource(R.string.settings_credential_keystore_broken_title),
                subtitle = stringResource(R.string.settings_credential_keystore_broken_subtitle),
                onClick = { showCredentialResetDialog = true },
            )
        }
        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_appearance), settingsExpanded[1], { settingsExpanded[1] = !settingsExpanded[1] }) {
        SettingsItem(
            icon = Icons.Filled.ColorLens,
            title = stringResource(R.string.settings_theme_title),
            subtitle = theme.label,
            onClick = { showThemeDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.Reorder,
            title = stringResource(R.string.settings_screen_order_title),
            subtitle = stringResource(R.string.settings_screen_order_subtitle),
            onClick = { showScreenOrderDialog = true },
        )
        SettingsToggleItem(
            icon = Icons.Filled.Palette,
            title = stringResource(R.string.settings_color_scheme_auto_switch_title),
            subtitle = stringResource(R.string.settings_color_scheme_auto_switch_subtitle),
            checked = autoSwitchColorScheme,
            onCheckedChange = viewModel::setTerminalAutoSwitchScheme,
        )
        if (autoSwitchColorScheme) {
            SettingsItem(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.settings_color_scheme_light_title),
                subtitle = lightColorScheme.label,
                onClick = { showLightColorSchemeDialog = true },
            )
            SettingsItem(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.settings_color_scheme_dark_title),
                subtitle = darkColorScheme.label,
                onClick = { showDarkColorSchemeDialog = true },
            )
        } else {
            SettingsItem(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.settings_color_scheme_title),
                subtitle = colorScheme.label,
                onClick = { showColorSchemeDialog = true },
            )
        }
        SettingsToggleItem(
            icon = Icons.Filled.Palette,
            title = stringResource(R.string.settings_color_scheme_apply_palette_title),
            subtitle = stringResource(R.string.settings_color_scheme_apply_palette_subtitle),
            checked = applySchemePalette,
            onCheckedChange = viewModel::setTerminalApplySchemePalette,
        )
        SettingsItem(
            icon = Icons.Filled.Opacity,
            title = stringResource(R.string.settings_terminal_opacity_title),
            subtitle = stringResource(
                R.string.settings_terminal_opacity_value,
                (backgroundOpacity * 100).roundToInt(),
            ),
            onClick = { showBackgroundOpacityDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.TextFields,
            title = stringResource(R.string.settings_font_size_title),
            subtitle = stringResource(R.string.settings_font_size_subtitle, fontSize),
            onClick = { showFontSizeDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.History,
            title = stringResource(R.string.settings_scrollback_rows_title),
            subtitle = stringResource(R.string.settings_scrollback_rows_subtitle, scrollbackRows),
            onClick = { showScrollbackRowsDialog = true },
        )
        run {
            val customFontPath by viewModel.terminalFontPath.collectAsState()
            val activeFontLabel = if (customFontPath == null) {
                stringResource(R.string.settings_terminal_font_default_label)
            } else {
                stringResource(R.string.settings_terminal_font_custom_fmt, java.io.File(customFontPath!!).nameWithoutExtension)
            }
            SettingsItem(
                icon = Icons.Filled.FontDownload,
                title = stringResource(R.string.settings_terminal_font_title),
                subtitle = stringResource(R.string.settings_terminal_font_subtitle_fmt, activeFontLabel),
                onClick = {
                    // Loose MIME — many file managers report fonts as
                    // application/octet-stream; we revalidate post-import.
                    fontImportLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*"))
                },
            )
            SettingsItem(
                icon = Icons.Filled.FontDownload,
                title = stringResource(R.string.settings_recommended_fonts_title),
                subtitle = stringResource(R.string.settings_recommended_fonts_subtitle),
                onClick = { showRecommendedFontsDialog = true },
            )
            SettingsItem(
                icon = Icons.Filled.CloudDownload,
                title = stringResource(R.string.settings_font_from_url_title),
                subtitle = stringResource(R.string.settings_font_from_url_subtitle),
                onClick = { showFontUrlDialog = true },
            )
            if (customFontPath != null) {
                SettingsItem(
                    icon = Icons.Filled.RestartAlt,
                    title = stringResource(R.string.settings_reset_font_title),
                    subtitle = stringResource(R.string.settings_reset_font_subtitle),
                    onClick = {
                        viewModel.clearCustomTerminalFont()
                        Toast.makeText(context, fontResetMsg, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
        run {
            val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            val currentLang = if (currentLocale.isEmpty) stringResource(R.string.settings_language_system_default)
                else java.util.Locale.forLanguageTag(currentLocale.toLanguageTags()).displayLanguage
            SettingsItem(
                icon = Icons.Filled.Language,
                title = stringResource(R.string.settings_language_title),
                subtitle = currentLang,
                onClick = { showLanguageDialog = true },
            )
        }

        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_keyboard_input), settingsExpanded[2], { settingsExpanded[2] = !settingsExpanded[2] }) {
        SettingsItem(
            icon = Icons.Filled.KeyboardAlt,
            title = stringResource(R.string.settings_toolbar_title),
            subtitle = stringResource(R.string.settings_toolbar_subtitle),
            onClick = { showToolbarConfigDialog = true },
        )
        // Keyboard mode tri-state picker. Replaces the previous binary
        // "Voice input and autocomplete" toggle so users can find all
        // three modes (Secure / Standard / Raw) from one settings entry
        // — the Raw mode used to be reachable only via the keyboard
        // toolbar's Raw key, which most users never added.
        val keyboardModeId = when {
            rawKeyboardMode -> "RAW"
            keyboardCustomMode -> "CUSTOM"
            allowStandardKeyboard -> "STANDARD"
            else -> "SECURE"
        }
        val keyboardModeLabel = when (keyboardModeId) {
            "RAW" -> stringResource(R.string.settings_keyboard_mode_subtitle_raw)
            "STANDARD" -> stringResource(R.string.settings_keyboard_mode_subtitle_standard)
            "CUSTOM" -> stringResource(R.string.settings_keyboard_mode_subtitle_custom)
            else -> stringResource(R.string.settings_keyboard_mode_subtitle_secure)
        }
        SettingsItem(
            icon = Icons.Filled.Keyboard,
            title = stringResource(R.string.settings_keyboard_mode_title),
            subtitle = keyboardModeLabel,
            onClick = { showKeyboardModeDialog = true },
        )
        if (keyboardCustomMode) {
            SettingsItem(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.settings_ime_flags_title),
                subtitle = stringResource(R.string.settings_ime_flags_subtitle),
                onClick = { showImeFlagsDialog = true },
            )
        }
        SettingsToggleItem(
            icon = Icons.Filled.ContentPaste,
            title = stringResource(R.string.settings_ctrl_shift_v_title),
            subtitle = stringResource(R.string.settings_ctrl_shift_v_subtitle),
            checked = interceptCtrlShiftV,
            onCheckedChange = viewModel::setInterceptCtrlShiftV,
        )

        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_terminal), settingsExpanded[3], { settingsExpanded[3] = !settingsExpanded[3] }) {
        SettingsItem(
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.settings_session_persistence_title),
            subtitle = if (sessionManager == UserPreferencesRepository.SessionManager.NONE) {
                stringResource(R.string.settings_session_persistence_none)
            } else {
                sessionManager.label
            },
            onClick = { showSessionManagerDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.settings_prompt_chars_title),
            subtitle = if (terminalPromptChars.isBlank()) {
                stringResource(R.string.settings_prompt_chars_subtitle)
            } else {
                terminalPromptChars
            },
            onClick = { showPromptCharsDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.Language,
            title = stringResource(R.string.settings_terminal_locale_title),
            subtitle = terminalLocale,
            onClick = { showLocaleDialog = true },
        )
        SettingsToggleItem(
            icon = Icons.Filled.Search,
            title = stringResource(R.string.settings_search_button_title),
            subtitle = stringResource(R.string.settings_search_button_subtitle),
            checked = showSearchButton,
            onCheckedChange = viewModel::setShowSearchButton,
        )
        SettingsToggleItem(
            icon = Icons.Filled.ContentCopy,
            title = stringResource(R.string.settings_copy_output_title),
            subtitle = stringResource(R.string.settings_copy_output_subtitle),
            checked = showCopyOutputButton,
            onCheckedChange = { enabled ->
                viewModel.setShowCopyOutputButton(enabled)
                if (enabled) showOsc133SetupDialog = true
            },
        )
        SettingsToggleItem(
            icon = Icons.Filled.LightMode,
            title = stringResource(R.string.settings_keep_screen_on_title),
            subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
            checked = keepScreenOnInTerminal,
            onCheckedChange = viewModel::setKeepScreenOnInTerminal,
        )
        SettingsToggleItem(
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.settings_mouse_input_title),
            subtitle = stringResource(R.string.settings_mouse_input_subtitle),
            checked = mouseInputEnabled,
            onCheckedChange = viewModel::setMouseInputEnabled,
        )
        SettingsToggleItem(
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.settings_right_click_title),
            subtitle = stringResource(R.string.settings_right_click_subtitle),
            checked = terminalRightClick,
            onCheckedChange = viewModel::setTerminalRightClick,
        )
        SettingsToggleItem(
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.settings_tap_to_position_title),
            subtitle = stringResource(R.string.settings_tap_to_position_subtitle),
            checked = tapToPositionCursorOnPrompt,
            onCheckedChange = viewModel::setTerminalTapToPositionCursor,
        )
        SettingsToggleItem(
            icon = Icons.Filled.ListAlt,
            title = stringResource(R.string.settings_show_terminal_tab_bar_title),
            subtitle = stringResource(R.string.settings_show_terminal_tab_bar_subtitle),
            checked = showTerminalTabBar,
            onCheckedChange = viewModel::setShowTerminalTabBar,
        )
        SettingsToggleItem(
            icon = Icons.Filled.ListAlt,
            title = stringResource(R.string.settings_hide_nav_bar_in_terminal_title),
            subtitle = stringResource(R.string.settings_hide_nav_bar_in_terminal_subtitle),
            checked = hideNavBarInTerminal,
            onCheckedChange = viewModel::setHideNavBarInTerminal,
        )
        SettingsToggleItem(
            icon = Icons.Filled.Keyboard,
            title = stringResource(R.string.settings_reflow_on_keyboard_title),
            subtitle = stringResource(R.string.settings_reflow_on_keyboard_subtitle),
            checked = reflowTerminalOnKeyboard,
            onCheckedChange = viewModel::setReflowTerminalOnKeyboard,
        )

        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_desktop), settingsExpanded[4], { settingsExpanded[4] = !settingsExpanded[4] }) {
        val rdpW by viewModel.rdpDesktopWidth.collectAsState()
        val rdpH by viewModel.rdpDesktopHeight.collectAsState()
        var showRdpSizeDialog by remember { mutableStateOf(false) }
        // RDP-only: the size a remote desktop session negotiates.
        if (nativeFeatures.rdp) {
            SettingsItem(
                icon = Icons.Filled.DesktopWindows,
                title = stringResource(R.string.settings_rdp_desktop_size_title),
                subtitle = stringResource(R.string.settings_rdp_desktop_size_subtitle, rdpW, rdpH),
                onClick = { showRdpSizeDialog = true },
            )
        }
        if (showRdpSizeDialog) {
            RdpDesktopSizeDialog(
                width = rdpW,
                height = rdpH,
                onDismiss = { showRdpSizeDialog = false },
                onApply = { w, h ->
                    viewModel.setRdpDesktopSize(w, h)
                    showRdpSizeDialog = false
                },
            )
        }
        SettingsToggleItem(
            icon = Icons.Filled.Mouse,
            title = stringResource(R.string.settings_touchpad_input_title),
            subtitle = stringResource(
                if (desktopInputMode == "TOUCHPAD")
                    R.string.settings_touchpad_input_touchpad_subtitle
                else
                    R.string.settings_touchpad_input_direct_subtitle
            ),
            checked = desktopInputMode == "TOUCHPAD",
            onCheckedChange = { enabled ->
                viewModel.setDesktopInputMode(if (enabled) "TOUCHPAD" else "DIRECT")
            },
        )
        // Selects the GPU stack for the native compositor.
        if (nativeFeatures.wayland) {
            SettingsToggleItem(
                icon = Icons.Filled.DesktopWindows,
                title = stringResource(R.string.settings_gpu_venus_title),
                subtitle = stringResource(R.string.settings_gpu_venus_subtitle),
                checked = gpuUseVenus,
                onCheckedChange = viewModel::setGpuUseVenus,
            )
        }
        SettingsToggleItem(
            icon = Icons.Filled.CloudDownload,
            title = stringResource(R.string.settings_bandwidth_suggest_title),
            subtitle = stringResource(R.string.settings_bandwidth_suggest_subtitle),
            checked = bandwidthAutoSuggest,
            onCheckedChange = viewModel::setBandwidthAutoSuggest,
        )

        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_connections_screen), settingsExpanded[5], { settingsExpanded[5] = !settingsExpanded[5] }) {
        SettingsToggleItem(
            icon = Icons.Filled.DesktopWindows,
            title = stringResource(R.string.settings_show_desktops_title),
            subtitle = stringResource(R.string.settings_show_desktops_subtitle),
            checked = showDesktopsCard,
            onCheckedChange = viewModel::setShowDesktopsCard,
        )
        SettingsToggleItem(
            icon = Icons.Filled.Laptop,
            title = stringResource(R.string.settings_show_linux_vm_title),
            subtitle = stringResource(R.string.settings_show_linux_vm_subtitle),
            checked = showLinuxVmCard,
            onCheckedChange = viewModel::setShowLinuxVmCard,
        )
        // Issue #160 — by default the bottom-nav hides tabs whose
        // backing resource (terminal profiles, SSH keys, desktops) is
        // empty. Power users with single-purpose installs can pin all
        // tabs by turning this on.
        val alwaysShowAllTabs by viewModel.alwaysShowAllTabs.collectAsState()
        SettingsToggleItem(
            icon = Icons.Filled.ViewModule,
            title = stringResource(R.string.settings_always_show_all_tabs_title),
            subtitle = stringResource(R.string.settings_always_show_all_tabs_subtitle),
            checked = alwaysShowAllTabs,
            onCheckedChange = viewModel::setAlwaysShowAllTabs,
        )

        // USB-to-guest is a privileged capability: once on, any app in the
        // Linux guest can reach a USB device the agent attaches. Off by
        // default; each attach still asks for consent on top of this.
        val usbGuestExposure by viewModel.usbGuestExposureEnabled.collectAsState()
        SettingsToggleItem(
            icon = Icons.Filled.Usb,
            title = stringResource(R.string.settings_usb_guest_title),
            subtitle = stringResource(R.string.settings_usb_guest_subtitle),
            checked = usbGuestExposure,
            onCheckedChange = viewModel::setUsbGuestExposureEnabled,
        )

        // Audio bridge (#257): play Linux app sound through the speaker via an
        // in-guest PulseAudio daemon. Experimental — streams continuously while
        // on, so it's off by default.
        val audioBridge by viewModel.audioBridgeEnabled.collectAsState()
        SettingsToggleItem(
            icon = Icons.Filled.VolumeUp,
            title = stringResource(R.string.settings_audio_bridge_title),
            subtitle = stringResource(R.string.settings_audio_bridge_subtitle),
            checked = audioBridge,
            onCheckedChange = viewModel::setAudioBridgeEnabled,
        )

        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_diagnostics), settingsExpanded[6], { settingsExpanded[6] = !settingsExpanded[6] }) {
        SettingsToggleItem(
            icon = Icons.Filled.History,
            title = stringResource(R.string.settings_connection_logging_title),
            subtitle = stringResource(R.string.settings_connection_logging_subtitle),
            checked = connectionLoggingEnabled,
            onCheckedChange = viewModel::setConnectionLoggingEnabled,
        )
        // RDP-only decode tuning.
        if (nativeFeatures.rdp) {
            SettingsToggleItem(
                icon = Icons.Filled.BugReport,
                title = stringResource(R.string.settings_rdp_progressive_upgrade_title),
                subtitle = stringResource(R.string.settings_rdp_progressive_upgrade_subtitle),
                checked = rdpProgressiveUpgrade,
                onCheckedChange = viewModel::setRdpProgressiveUpgrade,
            )
        }
        // RDP-only decode tuning.
        if (nativeFeatures.rdp) {
            SettingsToggleItem(
                icon = Icons.Filled.BugReport,
                title = stringResource(R.string.settings_rdp_avc_title),
                subtitle = stringResource(R.string.settings_rdp_avc_subtitle),
                checked = rdpAvcEnabled,
                onCheckedChange = viewModel::setRdpAvcEnabled,
            )
        }
        if (connectionLoggingEnabled) {
            SettingsItem(
                icon = Icons.Filled.ListAlt,
                title = stringResource(R.string.settings_view_connection_log_title),
                subtitle = stringResource(R.string.settings_view_connection_log_subtitle),
                onClick = { showAuditLog = true },
            )
            SettingsItem(
                icon = Icons.Filled.ListAlt,
                title = stringResource(R.string.settings_proot_install_log_title),
                subtitle = stringResource(R.string.settings_proot_install_log_subtitle),
                onClick = { showProotInstallLog = true },
            )
            SettingsToggleItem(
                icon = Icons.Filled.BugReport,
                title = stringResource(R.string.settings_verbose_logging_title),
                subtitle = stringResource(R.string.settings_verbose_logging_subtitle),
                checked = verboseLoggingEnabled,
                onCheckedChange = viewModel::setVerboseLoggingEnabled,
            )
        }
        run {
            val context = LocalContext.current
            val helper = sh.haven.core.local.WaylandSocketHelper
            var capturing by remember { mutableStateOf(helper.isCapturingLogcat) }
            // Hoisted out of the onClick callback (lint LocalContextGetResourceValueCall).
            val logcatSavedMsg = stringResource(R.string.settings_logcat_saved_toast)
            val logcatStartedMsg = stringResource(R.string.settings_logcat_started_toast)
            val logcatStartFailedMsg = stringResource(R.string.settings_logcat_start_failed_toast)
            SettingsItem(
                icon = Icons.Filled.BugReport,
                title = stringResource(R.string.settings_logcat_capture_title),
                subtitle = if (capturing)
                    stringResource(R.string.settings_logcat_capture_subtitle_on)
                else
                    stringResource(R.string.settings_logcat_capture_subtitle_off),
                onClick = {
                    if (capturing) {
                        helper.stopLogcatCapture()
                        capturing = false
                        Toast.makeText(context, logcatSavedMsg, Toast.LENGTH_SHORT).show()
                    } else {
                        capturing = helper.startLogcatCapture()
                        if (capturing) {
                            Toast.makeText(context, logcatStartedMsg, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, logcatStartFailedMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }

        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_advanced), settingsExpanded[7], { settingsExpanded[7] = !settingsExpanded[7] }) {
        // The command the native compositor launches.
        if (nativeFeatures.wayland) {
            SettingsItem(
                icon = Icons.Filled.DesktopWindows,
                title = stringResource(R.string.settings_wayland_shell_title),
                subtitle = waylandShellCommand,
                onClick = { showWaylandShellDialog = true },
            )
        }
        SettingsItem(
            icon = Icons.Filled.Timer,
            title = stringResource(R.string.settings_proot_idle_title),
            subtitle = if (prootIdleMinutes <= 0) {
                stringResource(R.string.settings_proot_idle_off)
            } else {
                stringResource(R.string.settings_proot_idle_on, prootIdleMinutes)
            },
            onClick = { showProotIdleDialog = true },
        )
        run {
            val context = LocalContext.current
            val helper = sh.haven.core.local.WaylandSocketHelper
            val shizukuRunning = helper.isShizukuAvailable()
            val shizukuInstalled = shizukuRunning || helper.isShizukuInstalled(context)
            val shizukuGranted = shizukuRunning && helper.hasShizukuPermission()
            SettingsItem(
                icon = Icons.Filled.DesktopWindows,
                title = stringResource(R.string.settings_shizuku_title),
                subtitle = when {
                    shizukuGranted -> stringResource(R.string.settings_shizuku_enabled)
                    shizukuRunning -> stringResource(R.string.settings_shizuku_available)
                    shizukuInstalled -> stringResource(R.string.settings_shizuku_not_running)
                    else -> stringResource(R.string.settings_shizuku_not_installed)
                },
                onClick = {
                    when {
                        shizukuRunning && !shizukuGranted -> {
                            helper.requestPermission()
                        }
                        shizukuInstalled && !shizukuRunning -> {
                            // Open Shizuku app so user can start it
                            val intent = context.packageManager.getLaunchIntentForPackage(
                                "moe.shizuku.privileged.api"
                            )
                            if (intent != null) {
                                context.startActivity(intent)
                            }
                        }
                        !shizukuInstalled -> {
                            // Open Play Store or browser
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api"),
                            )
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            } else {
                                context.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://shizuku.rikka.app/download/"),
                                ))
                            }
                        }
                    }
                },
            )
        }

        // Hide the task card from recents while sessions keep running (#239).
        SettingsToggleItem(
            icon = Icons.Filled.VisibilityOff,
            title = stringResource(R.string.settings_exclude_recents_title),
            subtitle = stringResource(R.string.settings_exclude_recents_subtitle),
            checked = excludeFromRecents,
            onCheckedChange = viewModel::setExcludeFromRecents,
        )

        // Media extensions stays in Advanced (moved up from below the MCP
        // block when the MCP/agent settings were split into their own section).
        // Which file extensions get ffmpeg previews and streaming.
        if (nativeFeatures.ffmpeg) {
            SettingsItem(
                icon = Icons.Filled.PlayArrow,
                title = stringResource(R.string.settings_media_extensions_title),
                subtitle = mediaExtensions,
                onClick = { showMediaExtensionsDialog = true },
            )
        }
        }

        // ── AI agent (MCP) — its own top-level section ──
        // Every "lets an outside thing poke Haven via MCP" surface in one place:
        // the endpoint master switch, per-capability gates, network exposure,
        // trust-loopback, copy URL/config, paired clients and standing policies.
        CollapsibleSettingsSection(stringResource(R.string.settings_section_mcp), settingsExpanded[10], { settingsExpanded[10] = !settingsExpanded[10] }) {
        // MCP agent endpoint. OFF by default. Read tools run unprompted; write
        // tools require a per-call (or per-session) bottom-sheet consent.
        SettingsToggleItem(
            icon = Icons.Filled.Hub,
            title = stringResource(R.string.settings_agent_endpoint_title),
            subtitle = if (mcpAgentEndpointEnabled) {
                stringResource(R.string.settings_agent_endpoint_subtitle_on)
            } else {
                stringResource(R.string.settings_agent_endpoint_subtitle_off)
            },
            checked = mcpAgentEndpointEnabled,
            onCheckedChange = viewModel::setMcpAgentEndpointEnabled,
        )
        if (mcpAgentEndpointEnabled) {
            // Per-capability gate above per-call consent. Enabling the
            // endpoint isn't enough on its own — raw file reads need
            // this extra opt-in, because they let an agent exfiltrate
            // any file in one call where other tools are scoped to
            // narrower surfaces (terminal scrollback, list-only, etc.).
            SettingsToggleItem(
                icon = Icons.Filled.Hub,
                title = stringResource(R.string.settings_agent_file_read_title),
                subtitle = if (agentAllowFileRead) {
                    stringResource(R.string.settings_agent_file_read_subtitle_on)
                } else {
                    stringResource(R.string.settings_agent_file_read_subtitle_off)
                },
                checked = agentAllowFileRead,
                onCheckedChange = viewModel::setAgentAllowFileRead,
            )

            // Power-user gate for `queue_terminal_input` — lets agents
            // type text + ENTER into any connected SSH session at the
            // next matching prompt. Off by default; this is real
            // keystroke injection.
            val agentAllowTerminalInputQueue by viewModel.agentAllowTerminalInputQueue.collectAsState()
            SettingsToggleItem(
                icon = Icons.Filled.Hub,
                title = stringResource(R.string.settings_agent_terminal_input_title),
                subtitle = if (agentAllowTerminalInputQueue) {
                    stringResource(R.string.settings_agent_terminal_input_subtitle_on)
                } else {
                    stringResource(R.string.settings_agent_terminal_input_subtitle_off)
                },
                checked = agentAllowTerminalInputQueue,
                onCheckedChange = viewModel::setAgentAllowTerminalInputQueue,
            )

            // Dedicated reverse-tunnel endpoint. The MCP server only
            // binds loopback on-device; to reach it from a laptop as the
            // network roams, Haven brings up a headless, auto-reconnecting
            // `-R` forward over the chosen SSH profile (decoupled from any
            // interactive terminal session). Unset = on-device only.
            val sshProfiles by viewModel.sshProfiles.collectAsState()
            val mcpTunnelEndpointProfileId by viewModel.mcpTunnelEndpointProfileId.collectAsState()
            var tunnelEndpointExpanded by remember { mutableStateOf(false) }
            val selectedEndpointLabel = sshProfiles
                .firstOrNull { it.id == mcpTunnelEndpointProfileId }?.label
            Box {
                SettingsItem(
                    icon = Icons.Filled.Hub,
                    title = stringResource(R.string.settings_reverse_tunnel_title),
                    subtitle = selectedEndpointLabel?.let {
                        stringResource(R.string.settings_reverse_tunnel_subtitle_set, it)
                    } ?: stringResource(R.string.settings_reverse_tunnel_subtitle_unset),
                    onClick = { tunnelEndpointExpanded = true },
                )
                DropdownMenu(
                    expanded = tunnelEndpointExpanded,
                    onDismissRequest = { tunnelEndpointExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_reverse_tunnel_none)) },
                        onClick = {
                            viewModel.setMcpTunnelEndpointProfileId(null)
                            tunnelEndpointExpanded = false
                        },
                    )
                    sshProfiles.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                viewModel.setMcpTunnelEndpointProfileId(option.id)
                                tunnelEndpointExpanded = false
                            },
                        )
                    }
                }
            }

            // Live status: is MCP actually riding a connected session right
            // now? The picker above only says what's *configured* — this
            // says what's actually open, so "why isn't MCP reaching my
            // laptop" has an answer without checking logs.
            val nearCarrierStatus by viewModel.mcpNearCarrierStatus.collectAsState()
            nearCarrierStatus.profileLabel?.let { label ->
                Text(
                    text = if (nearCarrierStatus.active) {
                        stringResource(R.string.settings_reverse_tunnel_status_active, label)
                    } else {
                        stringResource(R.string.settings_reverse_tunnel_status_inactive, label)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nearCarrierStatus.active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
            }

            // Expose the endpoint on the active WireGuard tunnel too — a
            // stable netstack address WG peers can reach across roams, no
            // reverse forward. Off by default (wider surface than loopback).
            val mcpWireguardEnabled by viewModel.mcpWireguardEnabled.collectAsState()
            SettingsToggleItem(
                icon = Icons.Filled.Hub,
                title = stringResource(R.string.settings_mcp_wireguard_title),
                subtitle = if (mcpWireguardEnabled) {
                    stringResource(R.string.settings_mcp_wireguard_subtitle_on)
                } else {
                    stringResource(R.string.settings_mcp_wireguard_subtitle_off)
                },
                checked = mcpWireguardEnabled,
                onCheckedChange = viewModel::setMcpWireguardEnabled,
            )

            // Warn when another app's system VPN holds the same address Haven's
            // userspace WireGuard netstack binds — the kernel VPN shadows our
            // listener so the WG endpoint is silently unreachable.
            val mcpWireguardCollision by viewModel.mcpWireguardCollision.collectAsState()
            mcpWireguardCollision?.takeIf { mcpWireguardEnabled }?.let { collision ->
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(end = 12.dp, top = 2.dp)
                            .size(20.dp),
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_mcp_wireguard_collision,
                            collision.vpnInterface,
                            collision.address,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Also bind the device's Wi-Fi/LAN address so a same-network
            // client reaches the endpoint directly (no WireGuard, no reverse
            // forward). Off by default (wider surface than loopback, though
            // still gated by client pairing).
            val mcpLanBindEnabled by viewModel.mcpLanBindEnabled.collectAsState()
            SettingsToggleItem(
                icon = Icons.Filled.Wifi,
                title = stringResource(R.string.settings_mcp_lan_title),
                subtitle = if (mcpLanBindEnabled) {
                    stringResource(R.string.settings_mcp_lan_subtitle_on)
                } else {
                    stringResource(R.string.settings_mcp_lan_subtitle_off)
                },
                checked = mcpLanBindEnabled,
                onCheckedChange = viewModel::setMcpLanBindEnabled,
            )

            // Loopback auto-trust: local clients (127.0.0.1 via adb forward
            // or on-device) skip pairing + per-call consent prompts. On by
            // default — a local client is already as trusted as the device.
            // LAN / WireGuard clients always keep the full gate. (#214)
            val trustLoopbackMcpClients by viewModel.trustLoopbackMcpClients.collectAsState()
            SettingsToggleItem(
                icon = Icons.Filled.Devices,
                title = stringResource(R.string.settings_mcp_trust_loopback_title),
                subtitle = if (trustLoopbackMcpClients) {
                    stringResource(R.string.settings_mcp_trust_loopback_subtitle_on)
                } else {
                    stringResource(R.string.settings_mcp_trust_loopback_subtitle_off)
                },
                checked = trustLoopbackMcpClients,
                onCheckedChange = viewModel::setTrustLoopbackMcpClients,
            )

            // Endpoint URL is always the canonical port range start —
            // the server binds to the first free port in 8730..8739
            val endpointUrl = "http://127.0.0.1:8730/mcp"
            val mcpConfigJson = """
                {
                  "mcpServers": {
                    "haven": {
                      "type": "http",
                      "url": "$endpointUrl"
                    }
                  }
                }
            """.trimIndent()
            SettingsItem(
                icon = Icons.Filled.ContentCopy,
                title = stringResource(R.string.settings_mcp_copy_url_title),
                subtitle = endpointUrl,
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Haven MCP endpoint", endpointUrl),
                    )
                    Toast.makeText(context, mcpUrlCopiedMsg, Toast.LENGTH_SHORT).show()
                },
            )
            SettingsItem(
                icon = Icons.Filled.ContentCopy,
                title = stringResource(R.string.settings_mcp_copy_config_title),
                subtitle = stringResource(R.string.settings_mcp_copy_config_subtitle),
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "Haven MCP server config",
                            mcpConfigJson,
                        ),
                    )
                    Toast.makeText(context, mcpConfigCopiedMsg, Toast.LENGTH_SHORT).show()
                },
            )
            SettingsItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_mcp_proot_title),
                subtitle = "~/.config/haven/mcp-servers.json",
                onClick = {},
            )
            SettingsItem(
                icon = Icons.Filled.History,
                title = if (unseenAgentActivity)
                    stringResource(R.string.settings_agent_activity_title) + "  ●"
                else
                    stringResource(R.string.settings_agent_activity_title),
                subtitle = stringResource(R.string.settings_agent_activity_subtitle),
                onClick = { showAgentActivity = true },
            )
            SettingsItem(
                icon = Icons.Filled.Devices,
                title = stringResource(R.string.settings_paired_clients_title),
                subtitle = stringResource(R.string.settings_paired_clients_subtitle),
                onClick = { showPairedClients = true },
            )
            SettingsToggleItem(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.settings_agent_confirm_destructive_title),
                subtitle = stringResource(R.string.settings_agent_confirm_destructive_subtitle),
                checked = requireAgentConsentForWrites,
                onCheckedChange = viewModel::setRequireAgentConsentForWrites,
            )
            SettingsItem(
                icon = Icons.Filled.LockReset,
                title = stringResource(R.string.settings_agent_forget_allows_title),
                subtitle = stringResource(R.string.settings_agent_forget_allows_subtitle),
                onClick = {
                    viewModel.forgetMemoisedAgentAllows()
                    Toast.makeText(context, agentAllowsClearedMsg, Toast.LENGTH_SHORT).show()
                },
            )
        }

        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_backup), settingsExpanded[8], { settingsExpanded[8] = !settingsExpanded[8] }) {
        SettingsItem(
            icon = Icons.Filled.CloudUpload,
            title = stringResource(R.string.settings_export_backup_title),
            subtitle = stringResource(R.string.settings_export_backup_subtitle),
            onClick = {
                showBackupPasswordDialog = BackupAction.Export
            },
        )
        SettingsItem(
            icon = Icons.Filled.CloudDownload,
            title = stringResource(R.string.settings_restore_backup_title),
            subtitle = stringResource(R.string.settings_restore_backup_subtitle),
            onClick = {
                importLauncher.launch(arrayOf("*/*"))
            },
        )
        // Push/pull the encrypted backup to an existing SFTP/SMB/rclone
        // connection, so config moves between devices without shuffling files
        // by hand (#323).
        run {
            val syncCandidates by viewModel.backupSyncCandidates.collectAsState()
            val syncProfileId by viewModel.backupSyncProfileId.collectAsState()
            val syncPath by viewModel.backupSyncPath.collectAsState()
            val destLabel = syncCandidates.firstOrNull { it.first == syncProfileId }?.second
            SettingsItem(
                icon = Icons.Filled.Sync,
                title = stringResource(R.string.settings_backup_sync_title),
                subtitle = if (destLabel != null) {
                    stringResource(R.string.settings_backup_sync_subtitle_set, destLabel, syncPath)
                } else {
                    stringResource(R.string.settings_backup_sync_subtitle_unset)
                },
                onClick = { showBackupSyncDialog = true },
            )
        }

        if (backupStatus is SettingsViewModel.BackupStatus.InProgress) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_backup_working)) },
                leadingContent = {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        }
        CollapsibleSettingsSection(stringResource(R.string.settings_section_about), settingsExpanded[9], { settingsExpanded[9] = !settingsExpanded[9] }) {
        SettingsItem(
            icon = Icons.Filled.Info,
            title = stringResource(R.string.settings_about_title),
            subtitle = "v${packageInfo.versionName}",
            onClick = { showAboutDialog = true },
        )
        // #578. Only a copy signed with the GitHub-release key can install a
        // GitHub release over itself, so on any other channel the honest thing
        // is to say where updates come from rather than offer a download that
        // Android will refuse.
        if (viewModel.updateChannel == UpdateChecker.Channel.GITHUB_RELEASE) {
            SettingsToggleItem(
                icon = Icons.Filled.SystemUpdate,
                title = stringResource(R.string.settings_update_check_title),
                subtitle = stringResource(R.string.settings_update_check_subtitle),
                checked = updateCheckEnabled,
                onCheckedChange = viewModel::setUpdateCheckEnabled,
            )
            val updateSubtitle = when (val r = updateCheckResult) {
                null -> stringResource(R.string.settings_update_check_now_subtitle)
                is UpdateChecker.Result.Available ->
                    stringResource(R.string.settings_update_check_available)
                is UpdateChecker.Result.UpToDate ->
                    stringResource(R.string.settings_update_check_up_to_date, r.installedVersion)
                is UpdateChecker.Result.Failed ->
                    stringResource(R.string.settings_update_check_failed, r.message)
                UpdateChecker.Result.WrongChannel ->
                    stringResource(R.string.settings_update_check_other_channel)
            }
            val available = updateCheckResult as? UpdateChecker.Result.Available
            SettingsItem(
                icon = if (available != null) Icons.Filled.Download else Icons.Filled.Refresh,
                title = if (updateCheckRunning) {
                    stringResource(R.string.settings_update_checking)
                } else if (available != null) {
                    stringResource(R.string.settings_update_open_release, available.version)
                } else {
                    stringResource(R.string.settings_update_check_now_title)
                },
                subtitle = updateSubtitle,
                onClick = {
                    if (available != null) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(available.releaseUrl)),
                        )
                    } else if (!updateCheckRunning) {
                        viewModel.checkForUpdateNow()
                    }
                },
            )
        } else {
            SettingsItem(
                icon = Icons.Filled.SystemUpdate,
                title = stringResource(R.string.settings_update_check_title),
                subtitle = stringResource(R.string.settings_update_check_other_channel),
                onClick = {},
            )
        }
        SettingsItem(
            icon = Icons.Filled.Favorite,
            title = stringResource(R.string.settings_support_title),
            subtitle = stringResource(R.string.settings_support_subtitle),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
            },
        )

        }
    } // scrollable Column
    } // outer Column

    if (showAboutDialog) {
        AboutDialog(
            versionName = packageInfo.versionName ?: stringResource(R.string.settings_version_unknown),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            onDismiss = { showAboutDialog = false },
            onOpenGitHub = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
            },
            onOpenKofi = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
            },
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            currentTheme = theme,
            onDismiss = { showThemeDialog = false },
            onSelect = { selected ->
                viewModel.setTheme(selected)
                showThemeDialog = false
            },
        )
    }

    if (showKeyboardModeDialog) {
        val modes = listOf(
            Triple("SECURE", stringResource(R.string.settings_keyboard_mode_secure_label),
                stringResource(R.string.settings_keyboard_mode_secure_desc)),
            Triple("STANDARD", stringResource(R.string.settings_keyboard_mode_standard_label),
                stringResource(R.string.settings_keyboard_mode_standard_desc)),
            Triple("RAW", stringResource(R.string.settings_keyboard_mode_raw_label),
                stringResource(R.string.settings_keyboard_mode_raw_desc)),
            Triple("CUSTOM", stringResource(R.string.settings_keyboard_mode_custom_label),
                stringResource(R.string.settings_keyboard_mode_custom_desc)),
        )
        val current = when {
            rawKeyboardMode -> "RAW"
            keyboardCustomMode -> "CUSTOM"
            allowStandardKeyboard -> "STANDARD"
            else -> "SECURE"
        }
        AlertDialog(
            onDismissRequest = { showKeyboardModeDialog = false },
            title = { Text(stringResource(R.string.settings_keyboard_mode_title)) },
            text = {
                Column {
                    modes.forEach { (id, label, description) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setKeyboardMode(id)
                                    showKeyboardModeDialog = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            RadioButton(
                                selected = id == current,
                                onClick = null,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKeyboardModeDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showImeFlagsDialog) {
        ImeFlagsDialog(
            noSuggestions = viewModel.imeFlagNoSuggestions.collectAsState().value,
            visiblePassword = viewModel.imeFlagVisiblePassword.collectAsState().value,
            autoCorrect = viewModel.imeFlagAutoCorrect.collectAsState().value,
            fullEditor = viewModel.imeFlagFullEditor.collectAsState().value,
            noExtractUi = viewModel.imeFlagNoExtractUi.collectAsState().value,
            noPersonalizedLearning = viewModel.imeFlagNoPersonalizedLearning.collectAsState().value,
            onNoSuggestions = viewModel::setImeFlagNoSuggestions,
            onVisiblePassword = viewModel::setImeFlagVisiblePassword,
            onAutoCorrect = viewModel::setImeFlagAutoCorrect,
            onFullEditor = viewModel::setImeFlagFullEditor,
            onNoExtractUi = viewModel::setImeFlagNoExtractUi,
            onNoPersonalizedLearning = viewModel::setImeFlagNoPersonalizedLearning,
            onDismiss = { showImeFlagsDialog = false },
        )
    }

    if (showLanguageDialog) {
        val languages = listOf(
            "" to stringResource(R.string.settings_language_system_default),
            "en" to "English",
            "ar" to "العربية",
            "bn" to "বাংলা",
            "de" to "Deutsch",
            "es" to "Español",
            "fr" to "Français",
            "hi" to "हिन्दी",
            "ja" to "日本語",
            "ko" to "한국어",
            "pt" to "Português",
            "ru" to "Русский",
            "zh" to "中文",
        )
        val currentTag = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language_title)) },
            text = {
                Column {
                    languages.forEach { (tag, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val locales = if (tag.isEmpty()) {
                                        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                                    }
                                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        ) {
                            RadioButton(
                                selected = (tag.isEmpty() && currentTag.isEmpty()) || tag == currentTag,
                                onClick = null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showScreenOrderDialog) {
        ScreenOrderDialog(
            currentOrder = screenOrder,
            onDismiss = { showScreenOrderDialog = false },
            onSave = { newOrder ->
                viewModel.setScreenOrder(newOrder.map { it.route })
                showScreenOrderDialog = false
            },
        )
    }

    if (showWaylandShellDialog) {
        var shellCmd by rememberSaveable { mutableStateOf(waylandShellCommand) }
        val shellOptions = listOf("/bin/sh -l", "/bin/ash -l", "/bin/bash -l", "/bin/zsh", "/bin/fish")
        AlertDialog(
            onDismissRequest = { showWaylandShellDialog = false },
            title = { Text(stringResource(R.string.settings_wayland_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_wayland_dialog_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = shellCmd,
                        onValueChange = { v -> shellCmd = v },
                        label = { Text(stringResource(R.string.settings_wayland_shell_command_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    shellOptions.forEach { option ->
                        FilterChip(
                            selected = shellCmd == option,
                            onClick = { shellCmd = option },
                            label = { Text(option) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setWaylandShellCommand(shellCmd)
                    showWaylandShellDialog = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showWaylandShellDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showMediaExtensionsDialog) {
        var extText by rememberSaveable { mutableStateOf(mediaExtensions) }
        AlertDialog(
            onDismissRequest = { showMediaExtensionsDialog = false },
            title = { Text(stringResource(R.string.settings_media_extensions_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_media_extensions_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = extText,
                        onValueChange = { extText = it },
                        label = { Text(stringResource(R.string.settings_media_extensions_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                    )
                    TextButton(onClick = {
                        extText = UserPreferencesRepository.DEFAULT_MEDIA_EXTENSIONS
                    }) {
                        Text(stringResource(R.string.common_reset))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setMediaExtensions(extText.trim())
                    showMediaExtensionsDialog = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showMediaExtensionsDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showPromptCharsDialog) {
        var promptText by rememberSaveable { mutableStateOf(terminalPromptChars) }
        AlertDialog(
            onDismissRequest = { showPromptCharsDialog = false },
            title = { Text(stringResource(R.string.settings_prompt_chars_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_prompt_chars_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        label = { Text(stringResource(R.string.settings_prompt_chars_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTerminalPromptChars(promptText.filterNot { it.isWhitespace() })
                    showPromptCharsDialog = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showPromptCharsDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showLocaleDialog) {
        var localeText by rememberSaveable { mutableStateOf(terminalLocale) }
        AlertDialog(
            onDismissRequest = { showLocaleDialog = false },
            title = { Text(stringResource(R.string.settings_terminal_locale_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_terminal_locale_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = localeText,
                        onValueChange = { localeText = it },
                        label = { Text(stringResource(R.string.settings_terminal_locale_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        localeText = UserPreferencesRepository.DEFAULT_TERMINAL_LOCALE
                    }) {
                        Text(stringResource(R.string.common_reset))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTerminalLocale(localeText.trim())
                    showLocaleDialog = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showLocaleDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showColorSchemeDialog) {
        ColorSchemeDialog(
            currentScheme = colorScheme,
            onDismiss = { showColorSchemeDialog = false },
            onSelect = { selected ->
                viewModel.setTerminalColorScheme(selected)
                showColorSchemeDialog = false
            },
        )
    }

    if (showLightColorSchemeDialog) {
        ColorSchemeDialog(
            currentScheme = lightColorScheme,
            titleRes = R.string.settings_color_scheme_light_dialog_title,
            onDismiss = { showLightColorSchemeDialog = false },
            onSelect = { selected ->
                viewModel.setTerminalLightColorScheme(selected)
                showLightColorSchemeDialog = false
            },
        )
    }

    if (showDarkColorSchemeDialog) {
        ColorSchemeDialog(
            currentScheme = darkColorScheme,
            titleRes = R.string.settings_color_scheme_dark_dialog_title,
            onDismiss = { showDarkColorSchemeDialog = false },
            onSelect = { selected ->
                viewModel.setTerminalDarkColorScheme(selected)
                showDarkColorSchemeDialog = false
            },
        )
    }

    if (showBackgroundOpacityDialog) {
        TerminalOpacityDialog(
            currentOpacity = backgroundOpacity,
            onDismiss = { showBackgroundOpacityDialog = false },
            onConfirm = { selected ->
                viewModel.setTerminalBackgroundOpacity(selected)
                showBackgroundOpacityDialog = false
            },
        )
    }

    if (showCredentialResetDialog) {
        AlertDialog(
            onDismissRequest = { showCredentialResetDialog = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_credential_reset_title)) },
            text = { Text(stringResource(R.string.settings_credential_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showCredentialResetDialog = false
                    viewModel.resetCredentialStorage()
                }) {
                    Text(stringResource(R.string.settings_credential_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCredentialResetDialog = false }) {
                    Text(stringResource(R.string.settings_credential_reset_cancel))
                }
            },
        )
    }

    if (showLockTimeoutDialog) {
        AlertDialog(
            onDismissRequest = { showLockTimeoutDialog = false },
            title = { Text(stringResource(R.string.settings_lock_timeout_dialog_title)) },
            text = {
                Column {
                    UserPreferencesRepository.LockTimeout.entries.forEach { timeout ->
                        ListItem(
                            modifier = Modifier.clickable {
                                viewModel.setLockTimeout(timeout)
                                showLockTimeoutDialog = false
                            },
                            headlineContent = { Text(timeout.label) },
                            leadingContent = {
                                RadioButton(
                                    selected = lockTimeout == timeout,
                                    onClick = null,
                                )
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLockTimeoutDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showSessionManagerDialog) {
        val sessionCmdOverride by viewModel.sessionCommandOverride.collectAsState()
        SessionManagerDialog(
            current = sessionManager,
            commandOverride = sessionCmdOverride,
            onDismiss = { showSessionManagerDialog = false },
            onSelect = { selected ->
                viewModel.setSessionManager(selected)
                showSessionManagerDialog = false
            },
            onCommandOverrideChange = viewModel::setSessionCommandOverride,
        )
    }

    if (showFontSizeDialog) {
        FontSizeDialog(
            currentSize = fontSize,
            onDismiss = { showFontSizeDialog = false },
            onConfirm = { newSize ->
                viewModel.setTerminalFontSize(newSize)
                showFontSizeDialog = false
            },
        )
    }

    if (showScrollbackRowsDialog) {
        ScrollbackRowsDialog(
            currentRows = scrollbackRows,
            onDismiss = { showScrollbackRowsDialog = false },
            onConfirm = { newRows ->
                viewModel.setTerminalScrollbackRows(newRows)
                showScrollbackRowsDialog = false
            },
        )
    }

    if (showProotIdleDialog) {
        ProotIdleTimeoutDialog(
            currentMinutes = prootIdleMinutes,
            onDismiss = { showProotIdleDialog = false },
            onConfirm = { minutes ->
                viewModel.setProotIdleTimeoutMinutes(minutes)
                showProotIdleDialog = false
            },
        )
    }

    if (showToolbarConfigDialog) {
        ToolbarConfigDialog(
            layout = toolbarLayout,
            layoutJson = toolbarLayoutJson,
            snippetLibrary = snippetLibrary,
            navBlockMode = navBlockMode,
            uniformGrid = toolbarUniformGrid,
            editModeControlsPlacement = editModeControlsPlacement,
            desktopKeyPlacement = desktopKeyPlacement,
            minButtonWidth = toolbarMinButtonWidth,
            onMinButtonWidthChange = { viewModel.setToolbarMinButtonWidth(it) },
            onDismiss = { showToolbarConfigDialog = false },
            onSaveLayout = { layout ->
                viewModel.setToolbarLayout(layout)
                showToolbarConfigDialog = false
            },
            onSaveLibrary = { viewModel.setSnippetLibrary(it) },
            onSaveJson = { json ->
                viewModel.setToolbarLayoutJson(json)
                showToolbarConfigDialog = false
            },
            onNavBlockModeChange = { viewModel.setNavBlockMode(it) },
            onUniformGridChange = { viewModel.setToolbarUniformGrid(it) },
            onEditControlsPlacementChange = { viewModel.setEditModeControlsPlacement(it) },
            onDesktopKeyPlacementChange = { viewModel.setDesktopKeyPlacement(it) },
        )
    }

    val backupSyncPassphrase by viewModel.backupSyncPassphrase.collectAsState()

    showBackupPasswordDialog?.let { action ->
        BackupPasswordDialog(
            isExport = action !is BackupAction.Restore && action !is BackupAction.PullRemote && action !is BackupAction.EnableAutoPull,
            titleOverride = when (action) {
                is BackupAction.EnableAutoSync -> stringResource(R.string.settings_backup_auto_sync_dialog_title)
                is BackupAction.EnableAutoPull -> stringResource(R.string.settings_backup_auto_pull_dialog_title)
                else -> null
            },
            descriptionOverride = when (action) {
                is BackupAction.EnableAutoSync -> stringResource(R.string.settings_backup_auto_sync_dialog_description)
                is BackupAction.EnableAutoPull -> stringResource(R.string.settings_backup_auto_pull_dialog_description)
                else -> null
            },
            initialPassword = backupSyncPassphrase ?: "",
            onDismiss = { showBackupPasswordDialog = null },
            onConfirm = { password, remember ->
                showBackupPasswordDialog = null
                when (action) {
                    is BackupAction.Export -> {
                        pendingPassword = password
                        exportLauncher.launch("haven-backup.enc")
                    }
                    is BackupAction.Restore -> {
                        if (remember) {
                            viewModel.saveBackupSyncPassphrase(password)
                        } else {
                            viewModel.clearBackupSyncPassphrase()
                        }
                        viewModel.importBackup(action.uri, password)
                    }
                    is BackupAction.PushRemote -> {
                        viewModel.pushBackupToRemote(password)
                    }
                    is BackupAction.PullRemote -> {
                        if (remember) {
                            viewModel.saveBackupSyncPassphrase(password)
                        } else {
                            viewModel.clearBackupSyncPassphrase()
                        }
                        viewModel.pullBackupFromRemote(password)
                    }
                    is BackupAction.EnableAutoSync -> {
                        viewModel.setBackupAutoSync(true, password)
                    }
                    is BackupAction.EnableAutoPull -> {
                        viewModel.setBackupAutoPull(true, password)
                    }
                }
            },
        )
    }

    if (showBackupSyncDialog) {
        val syncCandidates by viewModel.backupSyncCandidates.collectAsState()
        val syncProfileId by viewModel.backupSyncProfileId.collectAsState()
        val syncPath by viewModel.backupSyncPath.collectAsState()
        val autoSyncEnabled by viewModel.backupAutoSyncEnabled.collectAsState()
        val autoPullEnabled by viewModel.backupAutoPullEnabled.collectAsState()
        val autoPullIntervalMinutes by viewModel.backupAutoPullIntervalMinutes.collectAsState()
        BackupSyncDialog(
            candidates = syncCandidates,
            selectedProfileId = syncProfileId,
            path = syncPath,
            autoSyncEnabled = autoSyncEnabled,
            autoPullEnabled = autoPullEnabled,
            autoPullIntervalMinutes = autoPullIntervalMinutes,
            onDestinationChange = { pid, p -> viewModel.setBackupSyncDestination(pid, p) },
            onAutoSyncChange = { enable ->
                if (enable) {
                    showBackupSyncDialog = false
                    showBackupPasswordDialog = BackupAction.EnableAutoSync
                } else {
                    viewModel.setBackupAutoSync(false, null)
                }
            },
            onAutoPullChange = { enable ->
                if (enable) {
                    showBackupSyncDialog = false
                    showBackupPasswordDialog = BackupAction.EnableAutoPull
                } else {
                    viewModel.setBackupAutoPull(false, null)
                }
            },
            onAutoPullIntervalChange = { minutes ->
                viewModel.setBackupAutoPullInterval(minutes)
            },
            onPush = {
                showBackupSyncDialog = false
                showBackupPasswordDialog = BackupAction.PushRemote
            },
            onPull = {
                showBackupSyncDialog = false
                showBackupPasswordDialog = BackupAction.PullRemote
            },
            onDismiss = { showBackupSyncDialog = false },
        )
    }

    if (showOsc133SetupDialog) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        // Emits OSC 133 prompt markers, wrapping them in tmux's DCS passthrough
        // when inside tmux ($TMUX set) so the markers actually reach Haven —
        // raw OSC 133 is otherwise consumed by tmux even with allow-passthrough
        // (which only forwards explicitly-wrapped sequences). Needs
        // `set -g allow-passthrough on` in tmux < 3.5a. (#243)
        val bashSnippet = """# Add to ~/.bashrc  (works inside tmux too)
__osc133() { if [ -n "${'$'}TMUX" ]; then printf '\ePtmux;\e\e]133;%s\a\e\\' "${'$'}1"; else printf '\e]133;%s\a' "${'$'}1"; fi; }
PS0='\[${'$'}(__osc133 C)\]'
PS1='\[${'$'}(__osc133 "D;${'$'}?")${'$'}(__osc133 A)\]'${'$'}PS1'\[${'$'}(__osc133 B)\]'"""
        val zshSnippet = """# Add to ~/.zshrc  (works inside tmux too)
__osc133() { if [ -n "${'$'}TMUX" ]; then printf '\ePtmux;\e\e]133;%s\a\e\\' "${'$'}1"; else printf '\e]133;%s\a' "${'$'}1"; fi; }
precmd()  { local r=${'$'}?; __osc133 "D;${'$'}r"; __osc133 A }
preexec() { __osc133 B; __osc133 C }"""
        val copiedBashMsg = stringResource(R.string.settings_osc133_copied_bash)
        val copiedZshMsg = stringResource(R.string.settings_osc133_copied_zsh)
        AlertDialog(
            onDismissRequest = { showOsc133SetupDialog = false },
            title = { Text(stringResource(R.string.settings_osc133_dialog_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.settings_osc133_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_osc133_bash), style = MaterialTheme.typography.titleSmall)
                    Text(
                        bashSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(8.dp)
                            .clickable {
                                clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("bash", bashSnippet))
                                android.widget.Toast.makeText(context, copiedBashMsg, android.widget.Toast.LENGTH_SHORT).show()
                            },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_osc133_zsh), style = MaterialTheme.typography.titleSmall)
                    Text(
                        zshSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(8.dp)
                            .clickable {
                                clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("zsh", zshSnippet))
                                android.widget.Toast.makeText(context, copiedZshMsg, android.widget.Toast.LENGTH_SHORT).show()
                            },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_osc133_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://gitlab.freedesktop.org/Per_Bothner/specifications/blob/master/proposals/semantic-prompts.md")))
                }) { Text(stringResource(R.string.settings_osc133_learn_more)) }
            },
            dismissButton = {
                TextButton(onClick = { showOsc133SetupDialog = false }) { Text(stringResource(R.string.common_done)) }
            },
        )
    }
}

private sealed interface BackupAction {
    data object Export : BackupAction
    data class Restore(val uri: Uri) : BackupAction

    /** Push/pull to the configured remote (#323). Push encrypts (confirm password); Pull decrypts. */
    data object PushRemote : BackupAction
    data object PullRemote : BackupAction

    /** Turning on auto-push (#359): collects the passphrase the background job will encrypt with. */
    data object EnableAutoSync : BackupAction
    data object EnableAutoPull : BackupAction
}

/**
 * Configure and trigger encrypted backup push/pull to an existing remote (#323).
 * Destination = one of the user's SFTP/SMB/rclone connections + a file path;
 * changes persist immediately. Push/Pull hand off to [BackupPasswordDialog] for
 * the passphrase (Push encrypts, Pull decrypts + restores).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSyncDialog(
    candidates: List<Pair<String, String>>,
    selectedProfileId: String?,
    path: String,
    autoSyncEnabled: Boolean,
    autoPullEnabled: Boolean,
    autoPullIntervalMinutes: Int,
    onDestinationChange: (String?, String) -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onAutoPullChange: (Boolean) -> Unit,
    onAutoPullIntervalChange: (Int) -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pathField by remember(path) { mutableStateOf(path) }
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = candidates.firstOrNull { it.first == selectedProfileId }?.second
    val hasDestination = selectedProfileId != null && candidates.any { it.first == selectedProfileId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_sync_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_backup_sync_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_backup_sync_no_remotes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selectedLabel ?: stringResource(R.string.settings_backup_sync_choose_remote),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.settings_backup_sync_remote_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            candidates.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        onDestinationChange(id, pathField)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pathField,
                        onValueChange = {
                            pathField = it
                            onDestinationChange(selectedProfileId, it)
                        },
                        label = { Text(stringResource(R.string.settings_backup_sync_path_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_backup_sync_connect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_backup_auto_sync_label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = autoSyncEnabled,
                            onCheckedChange = onAutoSyncChange,
                            enabled = hasDestination || autoSyncEnabled,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_backup_auto_sync_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_backup_auto_pull_label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = autoPullEnabled,
                            onCheckedChange = onAutoPullChange,
                            enabled = hasDestination || autoPullEnabled,
                        )
                    }
                    if (autoPullEnabled) {
                        Spacer(Modifier.height(8.dp))
                        var intervalExpanded by remember { mutableStateOf(false) }
                        val intervals = listOf(15, 60, 360, 1440)
                        val intervalLabels = mapOf(
                            15 to stringResource(R.string.settings_backup_auto_pull_interval_15m),
                            60 to stringResource(R.string.settings_backup_auto_pull_interval_1h),
                            360 to stringResource(R.string.settings_backup_auto_pull_interval_6h),
                            1440 to stringResource(R.string.settings_backup_auto_pull_interval_24h)
                        )
                        ExposedDropdownMenuBox(
                            expanded = intervalExpanded,
                            onExpandedChange = { intervalExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = intervalLabels[autoPullIntervalMinutes] ?: intervalLabels[1440]!!,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.settings_backup_auto_pull_interval_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(intervalExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = intervalExpanded,
                                onDismissRequest = { intervalExpanded = false }
                            ) {
                                intervals.forEach { minutes ->
                                    DropdownMenuItem(
                                        text = { Text(intervalLabels[minutes] ?: "") },
                                        onClick = {
                                            onAutoPullIntervalChange(minutes)
                                            intervalExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_backup_auto_pull_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPull, enabled = hasDestination) {
                    Text(stringResource(R.string.settings_backup_sync_pull_button))
                }
                TextButton(onClick = onPush, enabled = hasDestination) {
                    Text(stringResource(R.string.settings_backup_sync_push_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun BackupPasswordDialog(
    isExport: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
    titleOverride: String? = null,
    descriptionOverride: String? = null,
    initialPassword: String = "",
) {
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }
    var confirmPassword by remember { mutableStateOf("") }
    var rememberPassword by remember(initialPassword) { mutableStateOf(initialPassword.isNotEmpty()) }
    val title = titleOverride
        ?: stringResource(if (isExport) R.string.settings_backup_export_dialog_title else R.string.settings_backup_restore_dialog_title)
    val passwordError = if (isExport && password.length in 1..5) stringResource(R.string.settings_backup_password_min_length) else null
    val confirmError = if (isExport && confirmPassword.isNotEmpty() && confirmPassword != password) {
        stringResource(R.string.settings_backup_passwords_mismatch)
    } else null
    val canConfirm = if (isExport) {
        password.length >= 6 && password == confirmPassword
    } else {
        password.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = descriptionOverride
                        ?: stringResource(if (isExport) R.string.settings_backup_export_description else R.string.settings_backup_restore_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                sh.haven.core.ui.PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.settings_backup_password_label),
                    isError = passwordError != null,
                    supportingText = passwordError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isExport) {
                    Spacer(Modifier.height(8.dp))
                    sh.haven.core.ui.PasswordField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = stringResource(R.string.settings_backup_confirm_password_label),
                        isError = confirmError != null,
                        supportingText = confirmError,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberPassword = !rememberPassword }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { rememberPassword = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_backup_remember_password_label),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password, rememberPassword) }, enabled = canConfirm) {
                Text(stringResource(if (isExport) R.string.settings_backup_export_button else R.string.settings_backup_restore_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private const val GITHUB_URL = "https://github.com/GlassOnTin/Haven"
private const val KOFI_URL = "https://ko-fi.com/glassontin"

@Composable
private fun AboutDialog(
    versionName: String,
    versionCode: Long,
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenKofi: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_about_dialog_title)) },
        text = {
            // The library list alone is taller than the dialog on a phone, and
            // AlertDialog clips its text slot rather than scrolling it — so
            // without this the last entries were simply unreachable (#485).
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.settings_about_app_name),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_about_version, versionName, versionCode),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.settings_about_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_about_libraries_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val libraries = listOf(
                    "rclone" to "Cloud storage engine (60+ providers) — MIT",
                    "IronRDP" to "RDP protocol (Rust/UniFFI) — MIT/Apache-2.0",
                    "JSch" to "SSH/SFTP protocol — BSD",
                    "smbj" to "SMB/CIFS protocol — Apache-2.0",
                    "ConnectBot termlib" to "Terminal emulator — Apache-2.0",
                    "PRoot" to "Local Linux shell — GPL-2.0",
                    "Jetpack Compose" to "UI toolkit — Apache-2.0",
                    "Hack Nerd Font Mono" to "Terminal default font — Hack (MIT/Bitstream Vera) + Nerd Fonts patcher (MIT) + Font Awesome (CC-BY 4.0) + Material Design Icons (Apache-2.0) + Devicons/Octicons/Codicons/Powerline/Pomicons/Seti UI (MIT) + Weather Icons (SIL OFL 1.1) + IEC Power Symbols (Public Domain). See NOTICE-Fonts.md.",
                )
                libraries.forEach { (name, desc) ->
                    Text(
                        text = "$name — $desc",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenKofi) {
                    Text(stringResource(R.string.common_support))
                }
                TextButton(onClick = onOpenGitHub) {
                    Text(stringResource(R.string.common_github))
                }
            }
        },
    )
}

@Composable
private fun ScreenOrderDialog(
    currentOrder: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<Screen>) -> Unit,
) {
    val allScreens = Screen.entries.toList()
    val initial = if (currentOrder.isNotEmpty()) {
        val byRoute = currentOrder.mapNotNull { route ->
            allScreens.find { it.route == route }
        }
        val missing = allScreens.filter { it !in byRoute }
        byRoute + missing
    } else {
        allScreens
    }
    val order = remember { mutableStateListOf(*initial.toTypedArray()) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val itemTops = remember { mutableStateMapOf<Int, Float>() }
    val itemHeights = remember { mutableStateMapOf<Int, Float>() }
    val haptic = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_order_dialog_title)) },
        text = {
            Column {
                order.forEachIndexed { index, screen ->
                    val isDragged = index == draggedIndex
                    ListItem(
                        headlineContent = { Text(stringResource(screen.labelRes)) },
                        leadingContent = {
                            Icon(
                                screen.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = stringResource(R.string.settings_screen_order_drag_description),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                itemTops[index] = coords.positionInParent().y
                                itemHeights[index] = coords.size.height.toFloat()
                            }
                            .then(
                                if (isDragged) {
                                    Modifier
                                        .zIndex(1f)
                                        .offset { IntOffset(0, dragOffset.roundToInt()) }
                                        .shadow(8.dp, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                },
                            )
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedIndex = order.indexOf(screen)
                                        dragOffset = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, offset ->
                                        change.consume()
                                        dragOffset += offset.y
                                        val di = draggedIndex
                                        if (di < 0) return@detectDragGesturesAfterLongPress
                                        val myTop = itemTops[di] ?: return@detectDragGesturesAfterLongPress
                                        val myH = itemHeights[di] ?: return@detectDragGesturesAfterLongPress
                                        val myCenter = myTop + myH / 2 + dragOffset
                                        // Check swap with neighbor below
                                        if (di < order.size - 1) {
                                            val nextTop = itemTops[di + 1] ?: 0f
                                            val nextH = itemHeights[di + 1] ?: 0f
                                            val nextCenter = nextTop + nextH / 2
                                            if (myCenter > nextCenter) {
                                                val item = order.removeAt(di)
                                                order.add(di + 1, item)
                                                dragOffset -= nextH
                                                // Update positions after swap
                                                itemTops[di] = myTop
                                                itemTops[di + 1] = myTop + nextH
                                                draggedIndex = di + 1
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                        // Check swap with neighbor above
                                        if (di > 0) {
                                            val prevTop = itemTops[di - 1] ?: 0f
                                            val prevH = itemHeights[di - 1] ?: 0f
                                            val prevCenter = prevTop + prevH / 2
                                            if (myCenter < prevCenter) {
                                                val item = order.removeAt(di)
                                                order.add(di - 1, item)
                                                dragOffset += prevH
                                                itemTops[di - 1] = myTop - prevH
                                                itemTops[di] = myTop
                                                draggedIndex = di - 1
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggedIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggedIndex = -1
                                        dragOffset = 0f
                                    },
                                )
                            },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(order.toList()) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun ThemeDialog(
    currentTheme: UserPreferencesRepository.ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (UserPreferencesRepository.ThemeMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme_dialog_title)) },
        text = {
            Column {
                UserPreferencesRepository.ThemeMode.entries.forEach { mode ->
                    ListItem(
                        headlineContent = { Text(mode.label) },
                        leadingContent = {
                            RadioButton(
                                selected = mode == currentTheme,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable(role = Role.RadioButton) {
                            onSelect(mode)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun ColorSchemeDialog(
    currentScheme: UserPreferencesRepository.TerminalColorScheme,
    onDismiss: () -> Unit,
    onSelect: (UserPreferencesRepository.TerminalColorScheme) -> Unit,
    titleRes: Int = R.string.settings_color_scheme_dialog_title,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                UserPreferencesRepository.TerminalColorScheme.entries.forEach { scheme ->
                    // MATERIAL_YOU's "background" / "foreground" longs are
                    // sentinel placeholders — derive the swatch from the
                    // live MaterialTheme so the preview matches what the
                    // terminal will actually look like.
                    val previewBg = if (scheme.isDynamic) MaterialTheme.colorScheme.surface
                        else Color(scheme.background)
                    val previewFg = if (scheme.isDynamic) MaterialTheme.colorScheme.onSurface
                        else Color(scheme.foreground)
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(previewBg)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(4.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "A",
                                        color = previewFg,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(scheme.label)
                            }
                        },
                        leadingContent = {
                            RadioButton(
                                selected = scheme == currentScheme,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable(role = Role.RadioButton) {
                            onSelect(scheme)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun SessionManagerDialog(
    current: UserPreferencesRepository.SessionManager,
    commandOverride: String?,
    onDismiss: () -> Unit,
    onSelect: (UserPreferencesRepository.SessionManager) -> Unit,
    onCommandOverrideChange: (String?) -> Unit,
) {
    val context = LocalContext.current
    var overrideText by remember(commandOverride) { mutableStateOf(commandOverride ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_session_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                UserPreferencesRepository.SessionManager.entries.forEach { manager ->
                    ListItem(
                        headlineContent = {
                            if (manager.url != null) {
                                Text(
                                    text = manager.label,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(manager.url))
                                        )
                                    },
                                )
                            } else {
                                Text(manager.label)
                            }
                        },
                        supportingContent = if (!manager.supportsScrollback) {
                            { Text(stringResource(R.string.settings_session_no_scrollback)) }
                        } else null,
                        leadingContent = {
                            RadioButton(
                                selected = manager == current,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable(role = Role.RadioButton) {
                            onSelect(manager)
                        },
                    )
                }

                if (current != UserPreferencesRepository.SessionManager.NONE) {
                    Spacer(Modifier.height(12.dp))
                    val defaultCommand = current.command?.invoke("{name}") ?: ""
                    OutlinedTextField(
                        value = overrideText,
                        onValueChange = { overrideText = it },
                        label = { Text(stringResource(R.string.settings_session_custom_command_label)) },
                        placeholder = { Text(defaultCommand, maxLines = 1) },
                        supportingText = {
                            Text(stringResource(R.string.settings_session_custom_command_hint))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (overrideText != (commandOverride ?: "")) {
                        TextButton(
                            onClick = {
                                onCommandOverrideChange(overrideText.ifBlank { null })
                            },
                        ) {
                            Text(stringResource(R.string.settings_session_save_command))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun TerminalOpacityDialog(
    currentOpacity: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(currentOpacity) }
    val pct = (sliderValue * 100).roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_terminal_opacity_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.settings_terminal_opacity_value, pct),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.settings_terminal_opacity_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sliderValue) }) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun FontSizeDialog(
    currentSize: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(currentSize.toFloat()) }
    val displaySize = sliderValue.toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_font_size_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.settings_font_size_sample),
                    fontFamily = FontFamily.Monospace,
                    fontSize = displaySize.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Text(
                    text = stringResource(R.string.settings_font_size_value, displaySize),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = UserPreferencesRepository.MIN_FONT_SIZE.toFloat()..
                        UserPreferencesRepository.MAX_FONT_SIZE.toFloat(),
                    steps = UserPreferencesRepository.MAX_FONT_SIZE -
                        UserPreferencesRepository.MIN_FONT_SIZE - 1,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(displaySize) }) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun ScrollbackRowsDialog(
    currentRows: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val presets = UserPreferencesRepository.SCROLLBACK_ROWS_PRESETS
    var selected by remember { mutableIntStateOf(currentRows) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_scrollback_rows_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_scrollback_rows_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                presets.forEach { preset ->
                    val label = stringResource(R.string.settings_scrollback_rows_value, preset)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = preset }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(
                            selected = selected == preset,
                            onClick = { selected = preset },
                        )
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Idle-timeout picker for the PRoot auto-stop (#409). Presets in minutes; 0 = off.
 */
@Composable
private fun ProotIdleTimeoutDialog(
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val presets = listOf(0, 5, 15, 30, 60)
    var selected by remember { mutableIntStateOf(currentMinutes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_proot_idle_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_proot_idle_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                presets.forEach { preset ->
                    val label = if (preset <= 0) {
                        stringResource(R.string.settings_proot_idle_off_label)
                    } else {
                        pluralStringResource(R.plurals.settings_proot_idle_minutes, preset, preset)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = preset }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(
                            selected = selected == preset,
                            onClick = { selected = preset },
                        )
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Section header rendered above a group of settings rows. Replaces
 * `HorizontalDivider` as the visual grouping mechanism — a labelled
 * header gives more orientation than a thin line, especially in a
 * long scrolling list.
 */
@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A [SettingsSection] header that folds its [content] away (collapsed by default). */
@Composable
private fun CollapsibleSettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onToggle() }
            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    if (expanded) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp),
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    )
}

/** Assignment for a key in the toolbar config dialog. */
private enum class KeyAssignment { ROW1, ROW2, OFF }

/** String resource for an [EditModeControlsPlacement] chip label. */
@androidx.annotation.StringRes
private fun editControlsPlacementLabel(placement: EditModeControlsPlacement): Int = when (placement) {
    EditModeControlsPlacement.SPLIT -> R.string.settings_edit_controls_split
    EditModeControlsPlacement.LEFT -> R.string.settings_edit_controls_left
    EditModeControlsPlacement.RIGHT -> R.string.settings_edit_controls_right
}

/** String resource for a [DesktopKeyPlacement] chip label. */
@androidx.annotation.StringRes
private fun desktopKeyPlacementLabel(placement: DesktopKeyPlacement): Int = when (placement) {
    DesktopKeyPlacement.LEFT -> R.string.settings_desktop_key_left
    DesktopKeyPlacement.RIGHT -> R.string.settings_desktop_key_right
    DesktopKeyPlacement.HIDDEN -> R.string.settings_desktop_key_hidden
}

@Composable
private fun ToolbarConfigDialog(
    layout: ToolbarLayout,
    layoutJson: String,
    snippetLibrary: List<ToolbarItem.Custom>,
    navBlockMode: NavBlockMode,
    uniformGrid: Boolean,
    editModeControlsPlacement: EditModeControlsPlacement,
    desktopKeyPlacement: DesktopKeyPlacement,
    minButtonWidth: Int,
    onMinButtonWidthChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSaveLayout: (ToolbarLayout) -> Unit,
    onSaveLibrary: (List<ToolbarItem.Custom>) -> Unit,
    onSaveJson: (String) -> Unit,
    onNavBlockModeChange: (NavBlockMode) -> Unit,
    onUniformGridChange: (Boolean) -> Unit,
    onEditControlsPlacementChange: (EditModeControlsPlacement) -> Unit,
    onDesktopKeyPlacementChange: (DesktopKeyPlacement) -> Unit,
) {
    var advancedMode by remember { mutableStateOf(false) }

    if (advancedMode) {
        ToolbarJsonEditor(
            json = layoutJson,
            onDismiss = onDismiss,
            onSave = onSaveJson,
            onSimpleMode = { advancedMode = false },
        )
    } else {
        ToolbarSimpleEditor(
            layout = layout,
            snippetLibrary = snippetLibrary,
            navBlockMode = navBlockMode,
            uniformGrid = uniformGrid,
            editModeControlsPlacement = editModeControlsPlacement,
            desktopKeyPlacement = desktopKeyPlacement,
            minButtonWidth = minButtonWidth,
            onMinButtonWidthChange = onMinButtonWidthChange,
            onDismiss = onDismiss,
            onSave = { newLayout, newLibrary ->
                onSaveLayout(newLayout)
                onSaveLibrary(newLibrary)
            },
            onAdvancedMode = { advancedMode = true },
            onNavBlockModeChange = onNavBlockModeChange,
            onUniformGridChange = onUniformGridChange,
            onEditControlsPlacementChange = onEditControlsPlacementChange,
            onDesktopKeyPlacementChange = onDesktopKeyPlacementChange,
        )
    }
}

private data class CustomKeyState(
    val item: ToolbarItem.Custom,
    val row: KeyAssignment,
)

@Composable
private fun ToolbarSimpleEditor(
    layout: ToolbarLayout,
    snippetLibrary: List<ToolbarItem.Custom>,
    navBlockMode: NavBlockMode,
    uniformGrid: Boolean,
    editModeControlsPlacement: EditModeControlsPlacement,
    desktopKeyPlacement: DesktopKeyPlacement,
    minButtonWidth: Int,
    onMinButtonWidthChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ToolbarLayout, List<ToolbarItem.Custom>) -> Unit,
    onAdvancedMode: () -> Unit,
    onNavBlockModeChange: (NavBlockMode) -> Unit,
    onUniformGridChange: (Boolean) -> Unit,
    onEditControlsPlacementChange: (EditModeControlsPlacement) -> Unit,
    onDesktopKeyPlacementChange: (DesktopKeyPlacement) -> Unit,
) {
    // Build assignment map from current layout (built-in keys only)
    val row1BuiltIns = remember(layout) {
        layout.row1.filterIsInstance<ToolbarItem.BuiltIn>().map { it.key }.toSet()
    }
    val row2BuiltIns = remember(layout) {
        layout.row2.filterIsInstance<ToolbarItem.BuiltIn>().map { it.key }.toSet()
    }

    var assignments by remember(layout) {
        mutableStateOf(
            ToolbarKey.entries.associateWith { key ->
                when (key) {
                    in row1BuiltIns -> KeyAssignment.ROW1
                    in row2BuiltIns -> KeyAssignment.ROW2
                    else -> KeyAssignment.OFF
                }
            }
        )
    }

    // Custom keys (snippets) state — placed ones from the layout, plus the
    // off-toolbar library shown as OFF so they can be promoted to a row or
    // left in the scissors-only library (#244).
    val customKeys = remember(layout, snippetLibrary) {
        mutableStateListOf<CustomKeyState>().apply {
            layout.row1.filterIsInstance<ToolbarItem.Custom>().forEach {
                add(CustomKeyState(it, KeyAssignment.ROW1))
            }
            layout.row2.filterIsInstance<ToolbarItem.Custom>().forEach {
                add(CustomKeyState(it, KeyAssignment.ROW2))
            }
            snippetLibrary.forEach { add(CustomKeyState(it, KeyAssignment.OFF)) }
        }
    }

    // Dialog state for creating/editing custom keys
    var showCustomKeyDialog by remember { mutableStateOf(false) }
    var editingCustomKeyIndex by remember { mutableStateOf(-1) }

    if (showCustomKeyDialog) {
        CustomKeyDialog(
            initial = if (editingCustomKeyIndex >= 0) customKeys[editingCustomKeyIndex].item else null,
            onDismiss = {
                showCustomKeyDialog = false
                editingCustomKeyIndex = -1
            },
            onSave = { newItem ->
                if (editingCustomKeyIndex >= 0) {
                    customKeys[editingCustomKeyIndex] = customKeys[editingCustomKeyIndex].copy(item = newItem)
                } else {
                    customKeys.add(CustomKeyState(newItem, KeyAssignment.ROW2))
                }
                showCustomKeyDialog = false
                editingCustomKeyIndex = -1
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_toolbar_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.settings_toolbar_assign_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                Text(
                    stringResource(R.string.settings_toolbar_arrow_keys),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                Row(modifier = Modifier.padding(bottom = 4.dp)) {
                    NavBlockMode.entries.forEach { mode ->
                        FilterChip(
                            selected = navBlockMode == mode,
                            onClick = { onNavBlockModeChange(mode) },
                            label = { Text(mode.label, fontSize = 11.sp) },
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }

                // Termux-style uniform key grid (#372) — overrides the arrow-key
                // block mode above while enabled (nav keys become grid cells).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_toolbar_uniform_grid),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.settings_toolbar_uniform_grid_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uniformGrid,
                        onCheckedChange = onUniformGridChange,
                    )
                }

                Text(
                    stringResource(R.string.settings_toolbar_edit_controls),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                Row(modifier = Modifier.padding(bottom = 4.dp)) {
                    EditModeControlsPlacement.entries.forEach { placement ->
                        FilterChip(
                            selected = editModeControlsPlacement == placement,
                            onClick = { onEditControlsPlacementChange(placement) },
                            label = {
                                Text(stringResource(editControlsPlacementLabel(placement)), fontSize = 11.sp)
                            },
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }

                // Desktop (VNC/RDP) key placement — left / right / hidden (#245).
                Text(
                    stringResource(R.string.settings_toolbar_desktop_key),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                Row(modifier = Modifier.padding(bottom = 4.dp)) {
                    DesktopKeyPlacement.entries.forEach { placement ->
                        FilterChip(
                            selected = desktopKeyPlacement == placement,
                            onClick = { onDesktopKeyPlacementChange(placement) },
                            label = {
                                Text(stringResource(desktopKeyPlacementLabel(placement)), fontSize = 11.sp)
                            },
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }

                // Minimum key width — widens every key uniformly for tappability.
                var minWidthSlider by remember(minButtonWidth) {
                    mutableStateOf(minButtonWidth.toFloat())
                }
                Text(
                    stringResource(R.string.settings_toolbar_min_key_width, minWidthSlider.roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                Slider(
                    value = minWidthSlider,
                    onValueChange = { minWidthSlider = it },
                    onValueChangeFinished = { onMinButtonWidthChange(minWidthSlider.roundToInt()) },
                    valueRange = 0f..64f,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                Text(
                    stringResource(R.string.settings_toolbar_function_keys),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                ToolbarKey.entries.filter { it.isAction || it.isModifier }.forEach { key ->
                    ToolbarKeyRow(
                        label = key.label,
                        assignment = assignments[key] ?: KeyAssignment.OFF,
                        onAssign = { assignments = assignments + (key to it) },
                        chipMinWidth = minWidthSlider.roundToInt().dp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    stringResource(R.string.settings_toolbar_symbols),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ToolbarKey.entries.filter { !it.isAction && !it.isModifier }.forEach { key ->
                    ToolbarKeyRow(
                        label = key.label,
                        assignment = assignments[key] ?: KeyAssignment.OFF,
                        onAssign = { assignments = assignments + (key to it) },
                        chipMinWidth = minWidthSlider.roundToInt().dp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    stringResource(R.string.settings_toolbar_custom_keys),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                customKeys.forEachIndexed { index, ck ->
                    CustomKeyRow(
                        state = ck,
                        onAssign = { customKeys[index] = ck.copy(row = it) },
                        onEdit = {
                            editingCustomKeyIndex = index
                            showCustomKeyDialog = true
                        },
                        onDelete = { customKeys.removeAt(index) },
                        chipMinWidth = minWidthSlider.roundToInt().dp,
                    )
                }

                TextButton(
                    onClick = {
                        editingCustomKeyIndex = -1
                        showCustomKeyDialog = true
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_toolbar_add_custom_key))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // OFF snippets go to the library (kept in the scissors sheet)
                // rather than being discarded (#244).
                val library = customKeys
                    .filter { it.row == KeyAssignment.OFF }
                    .map { it.item }
                // Rebuild rows preserving the existing on-toolbar order — toggling
                // one key must not reset a hand-arranged toolbar to defaults (#245).
                fun KeyAssignment.toRowIndex(): Int? = when (this) {
                    KeyAssignment.ROW1 -> 0
                    KeyAssignment.ROW2 -> 1
                    KeyAssignment.OFF -> null
                }
                val builtinRows = assignments.mapValues { it.value.toRowIndex() }
                val customRows = customKeys.map { it.item to it.row.toRowIndex() }
                val (newRow1, newRow2) = ToolbarEditorOps.rebuildRows(layout, builtinRows, customRows)
                onSave(ToolbarLayout(listOf(newRow1, newRow2)), library)
            }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            // FlowRow so longer translated labels (de: "Zurücksetzen / JSON bearbeiten /
            // Abbrechen") wrap to a second line instead of crushing into vertical-letter
            // strips and inflating the row height — which clipped the function-keys list
            // above on (#118).
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    assignments = ToolbarKey.entries.associateWith { key ->
                        when (key) {
                            in ToolbarKey.DEFAULT_ROW1 -> KeyAssignment.ROW1
                            in ToolbarKey.DEFAULT_ROW2 -> KeyAssignment.ROW2
                            else -> KeyAssignment.OFF
                        }
                    }
                    // Reset clears the toolbar back to defaults but keeps the
                    // user's snippets — move them off the bar into the library
                    // rather than deleting them (#244).
                    for (i in customKeys.indices) {
                        customKeys[i] = customKeys[i].copy(row = KeyAssignment.OFF)
                    }
                }) {
                    Text(stringResource(R.string.common_reset))
                }
                TextButton(onClick = onAdvancedMode) {
                    Text(stringResource(R.string.settings_toolbar_edit_json))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

@Composable
private fun CustomKeyRow(
    state: CustomKeyState,
    onAssign: (KeyAssignment) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    chipMinWidth: Dp = 0.dp,
) {
    // Stack the label/preview ABOVE the R1/R2/Off chips + edit/delete icons rather than
    // sharing one row: three chips at chipMinWidth plus two icon buttons leave almost no
    // width for the label column on a portrait phone, squeezing it to "…" (and, before the
    // maxLines cap, wrapping a long snippet send one glyph per line and exploding the row).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = state.item.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = displaySendSequence(state.item.send, stringResource(R.string.settings_toolbar_paste_clipboard)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyAssignment.entries.forEach { option ->
                FilterChip(
                    selected = state.row == option,
                    onClick = { onAssign(option) },
                    label = {
                        Box(modifier = Modifier.widthIn(min = chipMinWidth), contentAlignment = Alignment.Center) {
                            Text(
                                when (option) {
                                    KeyAssignment.ROW1 -> stringResource(R.string.settings_toolbar_row1)
                                    KeyAssignment.ROW2 -> stringResource(R.string.settings_toolbar_row2)
                                    KeyAssignment.OFF -> stringResource(R.string.settings_toolbar_off)
                                },
                                fontSize = 11.sp,
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 1.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.common_edit), modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Show a human-readable representation of a send sequence. */
private fun displaySendSequence(send: String, pasteLabel: String = "Paste clipboard"): String {
    if (send == "PASTE") return pasteLabel
    return send.map { ch ->
        when {
            ch.code < 0x20 -> "\\u${ch.code.toString(16).padStart(4, '0')}"
            else -> ch.toString()
        }
    }.joinToString("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomKeyDialog(
    initial: ToolbarItem.Custom? = null,
    onDismiss: () -> Unit,
    onSave: (ToolbarItem.Custom) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    val pasteLabel = stringResource(R.string.settings_toolbar_paste_clipboard)
    var sendText by remember { mutableStateOf(initial?.let { displaySendSequence(it.send, pasteLabel) } ?: "") }
    var presetExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial != null) R.string.settings_custom_key_edit_title else R.string.settings_custom_key_add_title)) },
        text = {
            Column {
                // Preset dropdown
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it },
                ) {
                    OutlinedTextField(
                        value = stringResource(R.string.settings_custom_key_presets),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    ExposedDropdownMenu(
                        expanded = presetExpanded,
                        onDismissRequest = { presetExpanded = false },
                    ) {
                        MACRO_PRESETS.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.description) },
                                onClick = {
                                    label = preset.label
                                    sendText = displaySendSequence(preset.send, pasteLabel)
                                    presetExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.settings_custom_key_label)) },
                    placeholder = { Text(stringResource(R.string.settings_custom_key_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    label = { Text(stringResource(R.string.settings_custom_key_sequence_label)) },
                    placeholder = { Text(stringResource(R.string.settings_custom_key_sequence_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )

                Text(
                    text = stringResource(R.string.settings_custom_key_escape_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = parseSendSequence(sendText)
                    onSave(ToolbarItem.Custom(label.trim(), parsed))
                },
                enabled = label.isNotBlank() && sendText.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Parse user-entered escape notation back to raw string. */
private fun parseSendSequence(input: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < input.length) {
        if (i + 1 < input.length && input[i] == '\\') {
            when (input[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                'u' -> {
                    if (i + 5 < input.length) {
                        val hex = input.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(input[i])
                            i++
                        }
                    } else {
                        sb.append(input[i])
                        i++
                    }
                }
                else -> { sb.append(input[i]); i++ }
            }
        } else {
            sb.append(input[i])
            i++
        }
    }
    return sb.toString()
}

@Composable
private fun ToolbarJsonEditor(
    json: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onSimpleMode: () -> Unit,
) {
    var text by remember(json) { mutableStateOf(json) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_json_editor_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.settings_json_editor_format_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.settings_json_editor_builtin_ids),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val validationError = ToolbarLayout.validate(text)
                if (validationError != null) {
                    error = validationError
                } else {
                    onSave(text)
                }
            }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    text = ToolbarLayout.DEFAULT.toJson()
                    error = null
                }) {
                    Text(stringResource(R.string.common_reset))
                }
                TextButton(onClick = onSimpleMode) {
                    Text(stringResource(R.string.common_simple))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

@Composable
private fun ToolbarKeyRow(
    label: String,
    assignment: KeyAssignment,
    onAssign: (KeyAssignment) -> Unit,
    chipMinWidth: Dp = 0.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        KeyAssignment.entries.forEach { option ->
            FilterChip(
                selected = assignment == option,
                onClick = { onAssign(option) },
                label = {
                    // Centre the label so the chip previews the key width as it
                    // grows with the "minimum key width" slider.
                    Box(modifier = Modifier.widthIn(min = chipMinWidth), contentAlignment = Alignment.Center) {
                        Text(
                            when (option) {
                                KeyAssignment.ROW1 -> stringResource(R.string.settings_toolbar_row1)
                                KeyAssignment.ROW2 -> stringResource(R.string.settings_toolbar_row2)
                                KeyAssignment.OFF -> stringResource(R.string.settings_toolbar_off)
                            },
                            fontSize = 11.sp,
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun DevInstallDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("http://192.168.0.:8080/haven-debug.apk") }
    var status by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = { Text(stringResource(R.string.settings_dev_install_title)) },
        text = {
            Column {
                Text(
                    "Download and install APK via Shizuku.\nServe with: python3 -m http.server 8080",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_dev_install_apk_url)) },
                    singleLine = true,
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status!!.startsWith("Error") || status!!.startsWith("pm install"))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                if (installing) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    installing = true
                    status = "Starting..."
                    sh.haven.core.local.WaylandSocketHelper.installApkFromUrl(
                        context = context,
                        url = url.trim(),
                        onProgress = { msg -> status = msg },
                        onResult = { success, msg ->
                            status = msg
                            installing = false
                        },
                    )
                },
                enabled = !installing && url.isNotBlank(),
            ) { Text(stringResource(R.string.common_install)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !installing,
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Dialog asking the user for a TTF/OTF URL. The actual download +
 * validation + persistence happens in TerminalFontInstaller via
 * SettingsViewModel.installTerminalFontFromUrl. The dialog itself just
 * collects the URL string and shows a one-line hint.
 */
@Composable
private fun FontFromUrlDialog(
    onInstall: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val canSubmit = url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_font_install_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_font_from_url_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    placeholder = { Text(stringResource(R.string.settings_font_from_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onInstall(url) },
                enabled = canSubmit,
            ) { Text(stringResource(R.string.common_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Curated monospace coding fonts with verified direct-TTF URLs — jsDelivr
 * mirrors of each project's GitHub repo, pinned to a stable ref (each was
 * checked to return `200 font/ttf`). One-tap installs go through the same
 * [TerminalFontInstaller] download/validate path as the URL dialog and the
 * agent's `set_terminal_font_from_url` tool. Source Code Pro resolves #147;
 * the rest are common requests.
 */
private val RECOMMENDED_TERMINAL_FONTS: List<Pair<String, String>> = listOf(
    "Source Code Pro" to "https://cdn.jsdelivr.net/gh/adobe-fonts/source-code-pro@release/TTF/SourceCodePro-Regular.ttf",
    "JetBrains Mono" to "https://cdn.jsdelivr.net/gh/JetBrains/JetBrainsMono@master/fonts/ttf/JetBrainsMono-Regular.ttf",
    "Hack" to "https://cdn.jsdelivr.net/gh/source-foundry/Hack@master/build/ttf/Hack-Regular.ttf",
    "Inconsolata" to "https://cdn.jsdelivr.net/gh/googlefonts/Inconsolata@main/fonts/ttf/Inconsolata-Regular.ttf",
)

/**
 * One-tap picker for a few popular coding fonts (#147). Each entry hands its
 * verified TTF URL to [onPick], which installs it via the shared installer.
 */
@Composable
private fun RecommendedFontsDialog(
    onPick: (name: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_recommended_fonts_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_recommended_fonts_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                RECOMMENDED_TERMINAL_FONTS.forEach { (name, url) ->
                    TextButton(
                        onClick = { onPick(name, url) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(name, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        // No confirm button — picking a font is the action.
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Per-flag toggles for the Custom keyboard mode (#115 follow-up).
 * Each row is one EditorInfo bit with a one-line explanation; the
 * matrix is documented at the top of NOTICE-Fonts.md... no wait, the
 * comment in HavenKeyboardMode.kt + the dialog text below is enough.
 *
 * No "save" button — toggles persist on tap so the user can iterate.
 * Conflicting selections (e.g. visiblePassword + fullEditor) are
 * preserved verbatim and the user discovers what their IME does with
 * the combination by trying it.
 */
@Composable
private fun ImeFlagsDialog(
    noSuggestions: Boolean,
    visiblePassword: Boolean,
    autoCorrect: Boolean,
    fullEditor: Boolean,
    noExtractUi: Boolean,
    noPersonalizedLearning: Boolean,
    onNoSuggestions: (Boolean) -> Unit,
    onVisiblePassword: (Boolean) -> Unit,
    onAutoCorrect: (Boolean) -> Unit,
    onFullEditor: (Boolean) -> Unit,
    onNoExtractUi: (Boolean) -> Unit,
    onNoPersonalizedLearning: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ime_flags_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.settings_ime_flags_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ImeFlagToggle(
                    title = stringResource(R.string.settings_ime_flag_no_suggestions_title),
                    detail = stringResource(R.string.settings_ime_flag_no_suggestions_detail),
                    checked = noSuggestions,
                    onCheckedChange = onNoSuggestions,
                )
                ImeFlagToggle(
                    title = stringResource(R.string.settings_ime_flag_visible_password_title),
                    detail = stringResource(R.string.settings_ime_flag_visible_password_detail),
                    checked = visiblePassword,
                    onCheckedChange = onVisiblePassword,
                )
                ImeFlagToggle(
                    title = stringResource(R.string.settings_ime_flag_auto_correct_title),
                    detail = stringResource(R.string.settings_ime_flag_auto_correct_detail),
                    checked = autoCorrect,
                    onCheckedChange = onAutoCorrect,
                )
                ImeFlagToggle(
                    title = stringResource(R.string.settings_ime_flag_full_editor_title),
                    detail = stringResource(R.string.settings_ime_flag_full_editor_detail),
                    checked = fullEditor,
                    onCheckedChange = onFullEditor,
                )
                ImeFlagToggle(
                    title = stringResource(R.string.settings_ime_flag_no_extract_ui_title),
                    detail = stringResource(R.string.settings_ime_flag_no_extract_ui_detail),
                    checked = noExtractUi,
                    onCheckedChange = onNoExtractUi,
                )
                ImeFlagToggle(
                    title = stringResource(R.string.settings_ime_flag_no_learning_title),
                    detail = stringResource(R.string.settings_ime_flag_no_learning_detail),
                    checked = noPersonalizedLearning,
                    onCheckedChange = onNoPersonalizedLearning,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun ImeFlagToggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Pick the desktop size Haven asks RDP servers for (#422).
 *
 * This existed only as a hardcoded 1920x1080 before. A server that draws a
 * different size — VirtualBox defaults to 2560x1600 and never announces a
 * resize — had every update outside that area silently discarded, which looks
 * like a screen that stops refreshing rather than like a size mismatch.
 */
@Composable
private fun RdpDesktopSizeDialog(
    width: Int,
    height: Int,
    onDismiss: () -> Unit,
    onApply: (Int, Int) -> Unit,
) {
    var w by remember { mutableStateOf(width.toString()) }
    var h by remember { mutableStateOf(height.toString()) }
    val wv = w.toIntOrNull()
    val hv = h.toIntOrNull()
    val valid = wv != null && hv != null &&
        wv in MIN_RDP_DIMENSION..MAX_RDP_DIMENSION &&
        hv in MIN_RDP_DIMENSION..MAX_RDP_DIMENSION

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_rdp_desktop_size_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_rdp_desktop_size_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = w,
                        onValueChange = { w = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.settings_rdp_desktop_size_width)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = h,
                        onValueChange = { h = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.settings_rdp_desktop_size_height)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                // The sizes people actually hit, so the common cases are one tap
                // rather than typing four digits twice on a phone.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1920 to 1080, 2560 to 1600, 1280 to 720).forEach { (pw, ph) ->
                        TextButton(onClick = { w = pw.toString(); h = ph.toString() }) {
                            Text("$pw×$ph", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onApply(wv!!, hv!!) },
                enabled = valid,
            ) { Text(stringResource(R.string.common_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
