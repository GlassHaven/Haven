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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.ui.navigation.Screen
import sh.haven.core.data.preferences.MACRO_PRESETS
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarKey
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    openToolbarConfig: Boolean = false,
    onToolbarConfigConsumed: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val screenSecurity by viewModel.screenSecurity.collectAsState()
    val showLinuxVmCard by viewModel.showLinuxVmCard.collectAsState()
    val showDesktopsCard by viewModel.showDesktopsCard.collectAsState()
    val lockTimeout by viewModel.lockTimeout.collectAsState()
    val fontSize by viewModel.terminalFontSize.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val sessionManager by viewModel.sessionManager.collectAsState()
    val colorScheme by viewModel.terminalColorScheme.collectAsState()
    val toolbarLayout by viewModel.toolbarLayout.collectAsState()
    val toolbarLayoutJson by viewModel.toolbarLayoutJson.collectAsState()
    val navBlockMode by viewModel.navBlockMode.collectAsState()
    val showSearchButton by viewModel.showSearchButton.collectAsState()
    val showCopyOutputButton by viewModel.showCopyOutputButton.collectAsState()
    val keepScreenOnInTerminal by viewModel.keepScreenOnInTerminal.collectAsState()
    val connectionLoggingEnabled by viewModel.connectionLoggingEnabled.collectAsState()
    val verboseLoggingEnabled by viewModel.verboseLoggingEnabled.collectAsState()
    val mcpAgentEndpointEnabled by viewModel.mcpAgentEndpointEnabled.collectAsState()
    val unseenAgentActivity by viewModel.unseenAgentActivity.collectAsState()
    val requireAgentConsentForWrites by viewModel.requireAgentConsentForWrites.collectAsState()
    val mouseInputEnabled by viewModel.mouseInputEnabled.collectAsState()
    val mouseDragSelects by viewModel.mouseDragSelects.collectAsState()
    val desktopInputMode by viewModel.desktopInputMode.collectAsState()
    val bandwidthAutoSuggest by viewModel.bandwidthAutoSuggest.collectAsState()
    val terminalRightClick by viewModel.terminalRightClick.collectAsState()
    val allowStandardKeyboard by viewModel.allowStandardKeyboard.collectAsState()
    val rawKeyboardMode by viewModel.rawKeyboardMode.collectAsState()
    val interceptCtrlShiftV by viewModel.interceptCtrlShiftV.collectAsState()
    val showTerminalTabBar by viewModel.showTerminalTabBar.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val waylandShellCommand by viewModel.waylandShellCommand.collectAsState()
    val mediaExtensions by viewModel.mediaExtensions.collectAsState()
    var showAuditLog by remember { mutableStateOf(false) }
    var showAgentActivity by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showWaylandShellDialog by remember { mutableStateOf(false) }
    var showMediaExtensionsDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showSessionManagerDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorSchemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showToolbarConfigDialog by remember { mutableStateOf(false) }
    var showKeyboardModeDialog by remember { mutableStateOf(false) }
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
    var showLockTimeoutDialog by remember { mutableStateOf(false) }
    var showOsc133SetupDialog by remember { mutableStateOf(false) }
    var showScreenOrderDialog by remember { mutableStateOf(false) }
    val screenOrder by viewModel.screenOrder.collectAsState()

    val context = LocalContext.current

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
    if (showAgentActivity) {
        AgentActivityScreen(onBack = { showAgentActivity = false })
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        SettingsSection("Security & privacy")
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
        SettingsSection("Appearance")
        SettingsItem(
            icon = Icons.Filled.ColorLens,
            title = stringResource(R.string.settings_theme_title),
            subtitle = theme.label,
            onClick = { showThemeDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.Palette,
            title = stringResource(R.string.settings_color_scheme_title),
            subtitle = colorScheme.label,
            onClick = { showColorSchemeDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.TextFields,
            title = stringResource(R.string.settings_font_size_title),
            subtitle = stringResource(R.string.settings_font_size_subtitle, fontSize),
            onClick = { showFontSizeDialog = true },
        )
        run {
            val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            val currentLang = if (currentLocale.isEmpty) "System default"
                else java.util.Locale.forLanguageTag(currentLocale.toLanguageTags()).displayLanguage
            SettingsItem(
                icon = Icons.Filled.Language,
                title = stringResource(R.string.settings_language_title),
                subtitle = currentLang,
                onClick = { showLanguageDialog = true },
            )
        }

        SettingsSection("Keyboard & input")
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
            allowStandardKeyboard -> "STANDARD"
            else -> "SECURE"
        }
        val keyboardModeLabel = when (keyboardModeId) {
            "RAW" -> "Raw — physical keyboard only"
            "STANDARD" -> "Standard — full Gboard features"
            else -> "Secure — default, suppresses autocorrect"
        }
        SettingsItem(
            icon = Icons.Filled.Keyboard,
            title = "Keyboard mode",
            subtitle = keyboardModeLabel,
            onClick = { showKeyboardModeDialog = true },
        )
        SettingsToggleItem(
            icon = Icons.Filled.ContentPaste,
            title = "Ctrl+Shift+V pastes",
            subtitle = "Hardware keyboard Ctrl+Shift+V pastes from the Android clipboard. Off forwards the keys to the remote shell.",
            checked = interceptCtrlShiftV,
            onCheckedChange = viewModel::setInterceptCtrlShiftV,
        )

        SettingsSection("Terminal")
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
            title = "Drag-to-select in TUI mouse mode",
            subtitle = "Forward press+motion+release to tmux/zellij so selection extends across the viewport via the remote's own scrollback. When off, drag scrolls the remote (legacy behaviour).",
            checked = mouseDragSelects,
            onCheckedChange = viewModel::setMouseDragSelects,
        )
        SettingsToggleItem(
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.settings_right_click_title),
            subtitle = stringResource(R.string.settings_right_click_subtitle),
            checked = terminalRightClick,
            onCheckedChange = viewModel::setTerminalRightClick,
        )
        SettingsToggleItem(
            icon = Icons.Filled.ListAlt,
            title = "Show terminal tab bar",
            subtitle = "Show session tabs above the terminal",
            checked = showTerminalTabBar,
            onCheckedChange = viewModel::setShowTerminalTabBar,
        )

        SettingsSection("Desktop (VNC & RDP)")
        SettingsToggleItem(
            icon = Icons.Filled.Mouse,
            title = "Touchpad input",
            subtitle = if (desktopInputMode == "TOUCHPAD")
                "Drag moves the remote cursor; tap clicks at the cursor. The viewport follows the cursor when zoomed."
            else
                "Direct touch — finger position is the pointer. Toggle on for trackpad-style input.",
            checked = desktopInputMode == "TOUCHPAD",
            onCheckedChange = { enabled ->
                viewModel.setDesktopInputMode(if (enabled) "TOUCHPAD" else "DIRECT")
            },
        )
        SettingsToggleItem(
            icon = Icons.Filled.CloudDownload,
            title = "Suggest 256 colours on slow VNC links",
            subtitle = "Detects throughput below ~1 Mbps after a few seconds and offers a one-tap reconnect at 256 colours. Off silences the prompt.",
            checked = bandwidthAutoSuggest,
            onCheckedChange = viewModel::setBandwidthAutoSuggest,
        )

        SettingsSection("Connections screen")
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
        SettingsItem(
            icon = Icons.Filled.Reorder,
            title = stringResource(R.string.settings_screen_order_title),
            subtitle = stringResource(R.string.settings_screen_order_subtitle),
            onClick = { showScreenOrderDialog = true },
        )

        SettingsSection("Diagnostics")
        SettingsToggleItem(
            icon = Icons.Filled.History,
            title = stringResource(R.string.settings_connection_logging_title),
            subtitle = stringResource(R.string.settings_connection_logging_subtitle),
            checked = connectionLoggingEnabled,
            onCheckedChange = viewModel::setConnectionLoggingEnabled,
        )
        if (connectionLoggingEnabled) {
            SettingsItem(
                icon = Icons.Filled.ListAlt,
                title = stringResource(R.string.settings_view_connection_log_title),
                subtitle = stringResource(R.string.settings_view_connection_log_subtitle),
                onClick = { showAuditLog = true },
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
            SettingsItem(
                icon = Icons.Filled.BugReport,
                title = "Logcat Capture",
                subtitle = if (capturing)
                    "Recording to /sdcard/Download/haven-logcat.txt"
                else
                    "Capture logcat for remote debugging",
                onClick = {
                    if (capturing) {
                        helper.stopLogcatCapture()
                        capturing = false
                        Toast.makeText(context, "Logcat saved", Toast.LENGTH_SHORT).show()
                    } else {
                        capturing = helper.startLogcatCapture()
                        if (capturing) {
                            Toast.makeText(context, "Logcat capture started", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to start capture", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }

        SettingsSection("Advanced")
        SettingsItem(
            icon = Icons.Filled.DesktopWindows,
            title = stringResource(R.string.settings_wayland_shell_title),
            subtitle = waylandShellCommand,
            onClick = { showWaylandShellDialog = true },
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

        // MCP agent endpoint — grouped near Shizuku because both are
        // "lets an outside thing poke Haven" surfaces: Shizuku lets
        // privileged Android helpers reach in, MCP lets local AI
        // agents and scripts reach in. OFF by default. Read-only in
        // this build.
        SettingsToggleItem(
            icon = Icons.Filled.Hub,
            title = "Agent endpoint (MCP)",
            subtitle = if (mcpAgentEndpointEnabled) {
                "Enabled — local MCP server exposes read-only tools on loopback"
            } else {
                "Disabled — turn on to let local AI agents inspect Haven's state"
            },
            checked = mcpAgentEndpointEnabled,
            onCheckedChange = viewModel::setMcpAgentEndpointEnabled,
        )
        if (mcpAgentEndpointEnabled) {
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
                title = "Copy agent endpoint URL",
                subtitle = endpointUrl,
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Haven MCP endpoint", endpointUrl),
                    )
                    Toast.makeText(context, "Endpoint copied", Toast.LENGTH_SHORT).show()
                },
            )
            SettingsItem(
                icon = Icons.Filled.ContentCopy,
                title = "Copy MCP client config",
                subtitle = "Standard JSON snippet to paste into any MCP client",
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "Haven MCP server config",
                            mcpConfigJson,
                        ),
                    )
                    Toast.makeText(context, "Config copied", Toast.LENGTH_SHORT).show()
                },
            )
            SettingsItem(
                icon = Icons.Filled.Info,
                title = "Also available in PRoot",
                subtitle = "~/.config/haven/mcp-servers.json",
                onClick = {},
            )
            SettingsItem(
                icon = Icons.Filled.History,
                title = if (unseenAgentActivity) "View agent activity  ●" else "View agent activity",
                subtitle = "Every MCP call recorded with redacted args",
                onClick = { showAgentActivity = true },
            )
            SettingsToggleItem(
                icon = Icons.Filled.Fingerprint,
                title = "Confirm destructive agent actions",
                subtitle = "Prompt before any agent call that writes, " +
                    "deletes, uploads, or disconnects. Read-only calls " +
                    "(the only kind in this build) never prompt.",
                checked = requireAgentConsentForWrites,
                onCheckedChange = viewModel::setRequireAgentConsentForWrites,
            )
        }

        SettingsItem(
            icon = Icons.Filled.PlayArrow,
            title = stringResource(R.string.settings_media_extensions_title),
            subtitle = mediaExtensions,
            onClick = { showMediaExtensionsDialog = true },
        )

        SettingsSection("Backup")
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

        if (backupStatus is SettingsViewModel.BackupStatus.InProgress) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_backup_working)) },
                leadingContent = {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        SettingsSection("About")
        SettingsItem(
            icon = Icons.Filled.Info,
            title = stringResource(R.string.settings_about_title),
            subtitle = "v${packageInfo.versionName}",
            onClick = { showAboutDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.Favorite,
            title = stringResource(R.string.settings_support_title),
            subtitle = stringResource(R.string.settings_support_subtitle),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
            },
        )

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
            Triple("SECURE", "Secure (default)",
                "Real software keyboard with privacy flags set: Gboard mic, autocorrect, swipe typing and writing-assist are suppressed. Best for typing passwords; works with Bluetooth keyboards too."),
            Triple("STANDARD", "Standard",
                "Full Gboard features — voice input, swipe typing, autocorrect, word predictions. Pick this if your software keyboard isn't working in Secure mode, or if you want autocorrect."),
            Triple("RAW", "Raw — Bluetooth/USB only",
                "No software keyboard at all. Only physical keys (Bluetooth or USB) reach the terminal. Strongest privacy; on-screen keyboard won't appear when you tap the terminal."),
        )
        val current = when {
            rawKeyboardMode -> "RAW"
            allowStandardKeyboard -> "STANDARD"
            else -> "SECURE"
        }
        AlertDialog(
            onDismissRequest = { showKeyboardModeDialog = false },
            title = { Text("Keyboard mode") },
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

    if (showLanguageDialog) {
        val languages = listOf(
            "" to "System default",
            "en" to "English",
            "ar" to "العربية",
            "bn" to "বাংলা",
            "de" to "Deutsch",
            "es" to "Español",
            "fr" to "Français",
            "hi" to "हिन्दी",
            "ja" to "日本語",
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

    if (showToolbarConfigDialog) {
        ToolbarConfigDialog(
            layout = toolbarLayout,
            layoutJson = toolbarLayoutJson,
            navBlockMode = navBlockMode,
            onDismiss = { showToolbarConfigDialog = false },
            onSaveLayout = { layout ->
                viewModel.setToolbarLayout(layout)
                showToolbarConfigDialog = false
            },
            onSaveJson = { json ->
                viewModel.setToolbarLayoutJson(json)
                showToolbarConfigDialog = false
            },
            onNavBlockModeChange = { viewModel.setNavBlockMode(it) },
        )
    }

    showBackupPasswordDialog?.let { action ->
        BackupPasswordDialog(
            isExport = action is BackupAction.Export,
            onDismiss = { showBackupPasswordDialog = null },
            onConfirm = { password ->
                showBackupPasswordDialog = null
                when (action) {
                    is BackupAction.Export -> {
                        pendingPassword = password
                        exportLauncher.launch("haven-backup.enc")
                    }
                    is BackupAction.Restore -> {
                        viewModel.importBackup(action.uri, password)
                    }
                }
            },
        )
    }

    if (showOsc133SetupDialog) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val bashSnippet = """# Add to ~/.bashrc
PS0='\[\e]133;C\a\]'
PS1='\[\e]133;D;${'$'}?\a\e]133;A\a\]'${'$'}PS1'\[\e]133;B\a\]'"""
        val zshSnippet = """# Add to ~/.zshrc
precmd()  { print -Pn '\e]133;D;%?\a\e]133;A\a' }
preexec() { print -Pn '\e]133;B\a\e]133;C\a' }"""
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
}

@Composable
private fun BackupPasswordDialog(
    isExport: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val title = stringResource(if (isExport) R.string.settings_backup_export_dialog_title else R.string.settings_backup_restore_dialog_title)
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
                    text = stringResource(if (isExport) R.string.settings_backup_export_description else R.string.settings_backup_restore_description),
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
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = canConfirm) {
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
            Column {
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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_color_scheme_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                UserPreferencesRepository.TerminalColorScheme.entries.forEach { scheme ->
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(scheme.background))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(4.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "A",
                                        color = Color(scheme.foreground),
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

@Composable
private fun ToolbarConfigDialog(
    layout: ToolbarLayout,
    layoutJson: String,
    navBlockMode: NavBlockMode,
    onDismiss: () -> Unit,
    onSaveLayout: (ToolbarLayout) -> Unit,
    onSaveJson: (String) -> Unit,
    onNavBlockModeChange: (NavBlockMode) -> Unit,
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
            navBlockMode = navBlockMode,
            onDismiss = onDismiss,
            onSave = onSaveLayout,
            onAdvancedMode = { advancedMode = true },
            onNavBlockModeChange = onNavBlockModeChange,
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
    navBlockMode: NavBlockMode,
    onDismiss: () -> Unit,
    onSave: (ToolbarLayout) -> Unit,
    onAdvancedMode: () -> Unit,
    onNavBlockModeChange: (NavBlockMode) -> Unit,
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

    // Custom keys state — built from current layout
    val customKeys = remember(layout) {
        mutableStateListOf<CustomKeyState>().apply {
            layout.row1.filterIsInstance<ToolbarItem.Custom>().forEach {
                add(CustomKeyState(it, KeyAssignment.ROW1))
            }
            layout.row2.filterIsInstance<ToolbarItem.Custom>().forEach {
                add(CustomKeyState(it, KeyAssignment.ROW2))
            }
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
                val customRow1 = customKeys
                    .filter { it.row == KeyAssignment.ROW1 }
                    .map { it.item }
                val customRow2 = customKeys
                    .filter { it.row == KeyAssignment.ROW2 }
                    .map { it.item }
                val newRow1 = ToolbarKey.entries
                    .filter { assignments[it] == KeyAssignment.ROW1 }
                    .map { ToolbarItem.BuiltIn(it) } + customRow1
                val newRow2 = ToolbarKey.entries
                    .filter { assignments[it] == KeyAssignment.ROW2 }
                    .map { ToolbarItem.BuiltIn(it) } + customRow2
                onSave(ToolbarLayout(listOf(newRow1, newRow2)))
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
                    customKeys.clear()
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.item.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = displaySendSequence(state.item.send, stringResource(R.string.settings_toolbar_paste_clipboard)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KeyAssignment.entries.forEach { option ->
            FilterChip(
                selected = state.row == option,
                onClick = { onAssign(option) },
                label = {
                    Text(
                        when (option) {
                            KeyAssignment.ROW1 -> stringResource(R.string.settings_toolbar_row1)
                            KeyAssignment.ROW2 -> stringResource(R.string.settings_toolbar_row2)
                            KeyAssignment.OFF -> stringResource(R.string.settings_toolbar_off)
                        },
                        fontSize = 11.sp,
                    )
                },
                modifier = Modifier.padding(horizontal = 1.dp),
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.common_edit), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), modifier = Modifier.size(16.dp))
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
                    Text(
                        when (option) {
                            KeyAssignment.ROW1 -> stringResource(R.string.settings_toolbar_row1)
                            KeyAssignment.ROW2 -> stringResource(R.string.settings_toolbar_row2)
                            KeyAssignment.OFF -> stringResource(R.string.settings_toolbar_off)
                        },
                        fontSize = 11.sp,
                    )
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
        title = { Text("Dev Install") },
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
                    label = { Text("APK URL") },
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
            ) { Text("Install") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !installing,
            ) { Text("Cancel") }
        },
    )
}
