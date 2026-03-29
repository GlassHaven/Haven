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
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val screenSecurity by viewModel.screenSecurity.collectAsState()
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
    val connectionLoggingEnabled by viewModel.connectionLoggingEnabled.collectAsState()
    val verboseLoggingEnabled by viewModel.verboseLoggingEnabled.collectAsState()
    val mouseInputEnabled by viewModel.mouseInputEnabled.collectAsState()
    val terminalRightClick by viewModel.terminalRightClick.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    var showAuditLog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showSessionManagerDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorSchemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showToolbarConfigDialog by remember { mutableStateOf(false) }
    LaunchedEffect(openToolbarConfig) {
        if (openToolbarConfig) showToolbarConfigDialog = true
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (viewModel.biometricAvailable) {
            SettingsToggleItem(
                icon = Icons.Filled.Fingerprint,
                title = "Biometric unlock",
                subtitle = "Require biometrics to open Haven",
                checked = biometricEnabled,
                onCheckedChange = viewModel::setBiometricEnabled,
            )
            if (biometricEnabled) {
                SettingsItem(
                    icon = Icons.Filled.Timer,
                    title = "Lock timeout",
                    subtitle = lockTimeout.label,
                    onClick = { showLockTimeoutDialog = true },
                )
            }
        }
        SettingsToggleItem(
            icon = Icons.Filled.ScreenLockPortrait,
            title = "Prevent screenshots",
            subtitle = "Block screen capture and task switcher preview",
            checked = screenSecurity,
            onCheckedChange = viewModel::setScreenSecurity,
        )
        SettingsToggleItem(
            icon = Icons.Filled.History,
            title = "Connection logging",
            subtitle = "Record connection events (connect, disconnect, errors). Stored locally, protected by device encryption",
            checked = connectionLoggingEnabled,
            onCheckedChange = viewModel::setConnectionLoggingEnabled,
        )
        if (connectionLoggingEnabled) {
            SettingsItem(
                icon = Icons.Filled.ListAlt,
                title = "View connection log",
                subtitle = "Connection history and events",
                onClick = { showAuditLog = true },
            )
            SettingsToggleItem(
                icon = Icons.Filled.BugReport,
                title = "Verbose connection logging",
                subtitle = "Capture protocol details for SSH, Mosh, and ET connections. May include hostnames and usernames",
                checked = verboseLoggingEnabled,
                onCheckedChange = viewModel::setVerboseLoggingEnabled,
            )
        }
        SettingsItem(
            icon = Icons.Filled.Terminal,
            title = "Session persistence",
            subtitle = if (sessionManager == UserPreferencesRepository.SessionManager.NONE) {
                "None"
            } else {
                sessionManager.label
            },
            onClick = { showSessionManagerDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.TextFields,
            title = "Terminal font size",
            subtitle = "${fontSize}sp",
            onClick = { showFontSizeDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.Palette,
            title = "Terminal color scheme",
            subtitle = colorScheme.label,
            onClick = { showColorSchemeDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.KeyboardAlt,
            title = "Keyboard toolbar",
            subtitle = "Configure toolbar keys and layout",
            onClick = { showToolbarConfigDialog = true },
        )
        SettingsToggleItem(
            icon = Icons.Filled.Search,
            title = "Search button",
            subtitle = "Show search icon in tab bar — sends your session manager's native search keys (tmux/zellij/screen) or shell Ctrl+R",
            checked = showSearchButton,
            onCheckedChange = viewModel::setShowSearchButton,
        )
        SettingsToggleItem(
            icon = Icons.Filled.ContentCopy,
            title = "Copy last output",
            subtitle = "Show copy icon in tab bar — copies the last command's output. Requires shell integration (tap for setup)",
            checked = showCopyOutputButton,
            onCheckedChange = { enabled ->
                viewModel.setShowCopyOutputButton(enabled)
                if (enabled) showOsc133SetupDialog = true
            },
        )
        SettingsToggleItem(
            icon = Icons.Filled.Terminal,
            title = "Mouse input in TUI apps",
            subtitle = "Forward taps as clicks and long-press as right-click when apps like htop, mc, or vim enable mouse tracking",
            checked = mouseInputEnabled,
            onCheckedChange = viewModel::setMouseInputEnabled,
        )
        SettingsToggleItem(
            icon = Icons.Filled.Terminal,
            title = "Long-press sends right-click",
            subtitle = "Send right-click to terminal instead of starting text selection. Useful for tmux context menus",
            checked = terminalRightClick,
            onCheckedChange = viewModel::setTerminalRightClick,
        )
        SettingsItem(
            icon = Icons.Filled.ColorLens,
            title = "Theme",
            subtitle = theme.label,
            onClick = { showThemeDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.Reorder,
            title = "Screen order",
            subtitle = "Reorder bottom navigation tabs",
            onClick = { showScreenOrderDialog = true },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        SettingsItem(
            icon = Icons.Filled.CloudUpload,
            title = "Export backup",
            subtitle = "Keys, connections, and settings",
            onClick = {
                showBackupPasswordDialog = BackupAction.Export
            },
        )
        SettingsItem(
            icon = Icons.Filled.CloudDownload,
            title = "Restore backup",
            subtitle = "Import from a backup file",
            onClick = {
                importLauncher.launch(arrayOf("*/*"))
            },
        )

        if (backupStatus is SettingsViewModel.BackupStatus.InProgress) {
            ListItem(
                headlineContent = { Text("Working...") },
                leadingContent = {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        SettingsItem(
            icon = Icons.Filled.Info,
            title = "About Haven",
            subtitle = "v${packageInfo.versionName}",
            onClick = { showAboutDialog = true },
        )
        SettingsItem(
            icon = Icons.Filled.Favorite,
            title = "Support Haven",
            subtitle = "Buy the developer a coffee",
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)))
            },
        )

    } // scrollable Column
    } // outer Column

    if (showAboutDialog) {
        AboutDialog(
            versionName = packageInfo.versionName ?: "unknown",
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
            title = { Text("Lock timeout") },
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
                    Text("Cancel")
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
        AlertDialog(
            onDismissRequest = { showOsc133SetupDialog = false },
            title = { Text("Shell integration setup") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Copy last output requires your shell to emit OSC 133 markers. " +
                        "Add one of these snippets to your shell config:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Bash", style = MaterialTheme.typography.titleSmall)
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
                                android.widget.Toast.makeText(context, "Copied bash snippet", android.widget.Toast.LENGTH_SHORT).show()
                            },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Zsh", style = MaterialTheme.typography.titleSmall)
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
                                android.widget.Toast.makeText(context, "Copied zsh snippet", android.widget.Toast.LENGTH_SHORT).show()
                            },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap a snippet to copy it. Many tools (iTerm2, VS Code, WezTerm) include these markers by default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://gitlab.freedesktop.org/Per_Bothner/specifications/blob/master/proposals/semantic-prompts.md")))
                }) { Text("Learn more") }
            },
            dismissButton = {
                TextButton(onClick = { showOsc133SetupDialog = false }) { Text("Done") }
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
    val title = if (isExport) "Export Backup" else "Restore Backup"
    val passwordError = if (isExport && password.length in 1..5) "At least 6 characters" else null
    val confirmError = if (isExport && confirmPassword.isNotEmpty() && confirmPassword != password) {
        "Passwords don't match"
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
                    text = if (isExport) {
                        "Encrypt your backup with a password. This protects your SSH keys and connection data."
                    } else {
                        "Enter the password used when the backup was created."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = passwordError != null,
                    supportingText = passwordError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isExport) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = confirmError != null,
                        supportingText = confirmError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = canConfirm) {
                Text(if (isExport) "Export" else "Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text("About Haven") },
        text = {
            Column {
                Text(
                    text = "Haven",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Open source SSH, VNC, RDP & cloud storage client for Android",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Version $versionName (build $versionCode)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Open Source Libraries",
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
                Text("Close")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenKofi) {
                    Text("Support")
                }
                TextButton(onClick = onOpenGitHub) {
                    Text("GitHub")
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
        title = { Text("Screen order") },
        text = {
            Column {
                order.forEachIndexed { index, screen ->
                    val isDragged = index == draggedIndex
                    ListItem(
                        headlineContent = { Text(screen.label) },
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
                                contentDescription = "Drag to reorder",
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
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
        title = { Text("Theme") },
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
                Text("Cancel")
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
        title = { Text("Terminal color scheme") },
        text = {
            Column {
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
                Text("Cancel")
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
        title = { Text("Session persistence") },
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
                            { Text("No touch scrollback") }
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
                        label = { Text("Custom command") },
                        placeholder = { Text(defaultCommand, maxLines = 1) },
                        supportingText = {
                            Text("Use {name} for session name. Leave blank for default.")
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
                            Text("Save command")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
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
        title = { Text("Terminal font size") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Sample text",
                    fontFamily = FontFamily.Monospace,
                    fontSize = displaySize.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Text(
                    text = "${displaySize}sp",
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
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
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
        title = { Text("Keyboard toolbar") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Assign each key to Row 1, Row 2, or Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                Text(
                    "Arrow keys layout",
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
                    "Function keys",
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
                    "Symbols",
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
                    "Custom keys",
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
                    Text("Add custom key")
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
                Text("Save")
            }
        },
        dismissButton = {
            Row {
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
                    Text("Reset")
                }
                TextButton(onClick = onAdvancedMode) {
                    Text("Edit JSON")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
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
                text = displaySendSequence(state.item.send),
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
                            KeyAssignment.ROW1 -> "R1"
                            KeyAssignment.ROW2 -> "R2"
                            KeyAssignment.OFF -> "Off"
                        },
                        fontSize = 11.sp,
                    )
                },
                modifier = Modifier.padding(horizontal = 1.dp),
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
        }
    }
}

/** Show a human-readable representation of a send sequence. */
private fun displaySendSequence(send: String): String {
    if (send == "PASTE") return "Paste clipboard"
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
    var sendText by remember { mutableStateOf(initial?.let { displaySendSequence(it.send) } ?: "") }
    var presetExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "Edit custom key" else "Add custom key") },
        text = {
            Column {
                // Preset dropdown
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it },
                ) {
                    OutlinedTextField(
                        value = "Presets",
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
                                    sendText = displaySendSequence(preset.send)
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
                    label = { Text("Label") },
                    placeholder = { Text("e.g. ^C, Paste") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    label = { Text("Sequence") },
                    placeholder = { Text("e.g. \\u0003") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )

                Text(
                    text = "Use \\u001b for Escape, \\u0003 for Ctrl+C, \\n for Enter",
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
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
        title = { Text("Edit toolbar JSON") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "String = built-in key ID, Object = custom key {\"label\": \"...\", \"send\": \"...\"}",
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
                    text = "Built-in IDs: keyboard, esc, tab, shift, ctrl, alt, arrow_left, arrow_up, arrow_down, arrow_right, home, end, pgup, pgdn, sym_pipe, sym_tilde, sym_slash, sym_dash, sym_underscore, sym_equals, sym_plus, sym_backslash, sym_squote, sym_dquote, sym_semicolon, sym_colon, sym_bang, sym_question, sym_at, sym_hash, sym_dollar, sym_percent, sym_caret, sym_amp, sym_star, sym_lparen, sym_rparen, sym_lbracket, sym_rbracket, sym_lbrace, sym_rbrace, sym_lt, sym_gt, sym_backtick",
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
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    text = ToolbarLayout.DEFAULT.toJson()
                    error = null
                }) {
                    Text("Reset")
                }
                TextButton(onClick = onSimpleMode) {
                    Text("Simple")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
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
                            KeyAssignment.ROW1 -> "R1"
                            KeyAssignment.ROW2 -> "R2"
                            KeyAssignment.OFF -> "Off"
                        },
                        fontSize = 11.sp,
                    )
                },
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
