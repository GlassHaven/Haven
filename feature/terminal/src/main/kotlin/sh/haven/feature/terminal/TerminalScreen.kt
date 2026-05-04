package sh.haven.feature.terminal

import sh.haven.core.toolbar.KeyboardToolbar
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import sh.haven.core.ui.KeyEventInterceptor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.connectbot.terminal.ModifierManager
import sh.haven.core.terminal.HavenKeyboardMode
import sh.haven.core.terminal.HavenTerminal
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository

/** Distinct colors for grouping tabs by connection profile. */
private val TAB_GROUP_COLORS = listOf(
    Color(0xFF42A5F5), // blue
    Color(0xFF66BB6A), // green
    Color(0xFFFF7043), // orange
    Color(0xFFAB47BC), // purple
    Color(0xFFFFCA28), // amber
    Color(0xFF26C6DA), // cyan
    Color(0xFFEF5350), // red
    Color(0xFF8D6E63), // brown
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    navigateToProfileId: String? = null,
    newSessionProfileId: String? = null,
    isActive: Boolean = false,
    terminalModifier: Modifier = Modifier,
    fontSize: Int = UserPreferencesRepository.DEFAULT_FONT_SIZE,
    /**
     * Optional override for the terminal typeface — Settings → Terminal
     * font lets the user pick a Nerd Font (or any TTF/OTF) so prompts
     * with extended glyphs render instead of showing tofu boxes (#123).
     * Null means "fall back to the bundled Hack Regular".
     */
    terminalFontPath: String? = null,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    navBlockMode: sh.haven.core.data.preferences.NavBlockMode = sh.haven.core.data.preferences.NavBlockMode.ALIGNED,
    showSearchButton: Boolean = false,
    showCopyOutputButton: Boolean = false,
    keepScreenOnInTerminal: Boolean = false,
    mouseInputEnabled: Boolean = true,
    mouseDragSelects: Boolean = true,
    terminalRightClick: Boolean = false,
    allowStandardKeyboard: Boolean = false,
    onToggleStandardKeyboard: () -> Unit = {},
    rawKeyboardMode: Boolean = false,
    onToggleRawKeyboard: () -> Unit = {},
    /**
     * Non-null = user has selected the Custom keyboard mode and these
     * are the EditorInfo flag toggles to apply. Null = use one of the
     * preset modes (Secure/Standard/Raw above). #115 follow-up.
     */
    customKeyboardFlags: sh.haven.core.terminal.ImeFlagSet? = null,
    interceptCtrlShiftV: Boolean = true,
    showTabBar: Boolean = true,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onNavigateToConnections: () -> Unit = {},
    onNavigateToVnc: (host: String, port: Int, username: String?, password: String?, sshForward: Boolean, sshSessionId: String?, colorDepth: String) -> Unit = { _, _, _, _, _, _, _ -> },
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    onReorderModeChanged: (Boolean) -> Unit = {},
    onToolbarLayoutChanged: (ToolbarLayout) -> Unit = {},
    onOpenToolbarSettings: () -> Unit = {},
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    var reorderMode by remember { mutableStateOf(false) }
    // Fullscreen state — survives rotation via rememberSaveable.
    // Setting this true tells the parent to hide the bottom nav / side
    // rail and tells us to hide the tab bar; LaunchedEffect below also
    // hides the system status + nav bars. Mirrors the desktop fullscreen
    // pattern in VncScreen / RdpScreen.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window
    LaunchedEffect(fullscreen) {
        onFullscreenChanged(fullscreen)
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Belt-and-braces: if the screen is torn down while still
            // in fullscreen (e.g. user backgrounds the app), make sure
            // the system bars come back so the next surface isn't stuck
            // edge-to-edge.
            if (fullscreen && window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
                onFullscreenChanged(false)
            }
        }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    val tabs by viewModel.tabs.collectAsState()
    val activeTabIndex by viewModel.activeTabIndex.collectAsState()
    val ctrlActive by viewModel.ctrlActive.collectAsState()
    val altActive by viewModel.altActive.collectAsState()
    val colorScheme by viewModel.terminalColorScheme.collectAsState()
    val navigateToConnections by viewModel.navigateToConnections.collectAsState()
    val newTabSessionPicker by viewModel.newTabSessionPicker.collectAsState()
    val newTabLoading by viewModel.newTabLoading.collectAsState()
    val newTabMessage by viewModel.newTabMessage.collectAsState()
    var vncDialogInfo by remember { mutableStateOf<VncInfo?>(null) }
    var localVncLoading by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val context = LocalContext.current

    LaunchedEffect(newTabMessage) {
        newTabMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.dismissNewTabMessage()
        }
    }

    // Resolve the terminal typeface: user-supplied font path wins
    // (covers any TTF/OTF the user picked); falls back to the bundled
    // Hack Nerd Font Mono so Powerline / Devicons / Font Awesome
    // glyphs in shell prompts render out of the box (#123), then
    // platform monospace if even that fails to load. Re-read whenever
    // the path changes — callers observe the preference Flow, so a
    // Settings change triggers a recomposition with a new typeface
    // here.
    val hackTypeface = remember(terminalFontPath) {
        terminalFontPath
            ?.let { runCatching { android.graphics.Typeface.createFromFile(it) }.getOrNull() }
            ?: ResourcesCompat.getFont(context, sh.haven.core.ui.R.font.hack_nerd_font_mono_regular)
            ?: android.graphics.Typeface.MONOSPACE
    }

    // Keep the screen awake while a terminal tab is in the foreground when
    // the user has opted in (Settings → "Keep screen on in terminal", #122).
    // DisposableEffect tied to the View ensures the flag is cleared as soon
    // as TerminalScreen leaves composition (navigating to another tab,
    // process death recreating). Tabs without an active session still keep
    // the screen on — the request is "while in the terminal", not "while a
    // shell is connected", so a deliberately quiet session doesn't blank.
    DisposableEffect(view, keepScreenOnInTerminal) {
        view.keepScreenOn = keepScreenOnInTerminal
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(navigateToConnections) {
        if (navigateToConnections) {
            onNavigateToConnections()
            viewModel.onNavigatedToConnections()
        }
    }

    // Show/hide keyboard when this tab becomes active/inactive
    LaunchedEffect(isActive) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (isActive && tabs.isNotEmpty()) {
            controller.show(WindowInsetsCompat.Type.ime())
        } else if (!isActive) {
            controller.hide(WindowInsetsCompat.Type.ime())
        }
    }

    // Detect keyboard hide → send Ctrl+L for Zellij profiles to trigger redraw
    val imeVisible = WindowInsets.isImeVisible
    var wasImeVisible by remember { mutableStateOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        if (wasImeVisible && !imeVisible) {
            viewModel.sendRedrawIfZellij()
        }
        wasImeVisible = imeVisible
    }

    // Navigate to specific tab if requested
    LaunchedEffect(navigateToProfileId) {
        if (navigateToProfileId != null) {
            viewModel.selectTabByProfileId(navigateToProfileId)
        }
    }

    // Open new session (new tab) for profile if requested from Connections screen
    LaunchedEffect(newSessionProfileId) {
        if (newSessionProfileId != null) {
            viewModel.addSshTabForProfile(newSessionProfileId)
        }
    }

    // VNC settings dialog
    vncDialogInfo?.let { info ->
        VncSettingsDialog(
            host = info.host,
            initialPort = info.port,
            initialUsername = info.username,
            initialPassword = info.password,
            initialSshForward = info.sshForward,
            initialColorDepth = info.colorDepth,
            onConnect = { port, username, password, sshForward, colorDepth, save ->
                if (save) {
                    viewModel.saveVncSettings(info.profileId, port, username, password, sshForward, colorDepth)
                }
                vncDialogInfo = null
                onNavigateToVnc(info.host, port, username, password, sshForward, info.sessionId, colorDepth)
            },
            onDismiss = { vncDialogInfo = null },
        )
    }

    // Session picker dialog for new tab
    newTabSessionPicker?.let { selection ->
        NewTabSessionPickerDialog(
            managerLabel = selection.managerLabel,
            sessionNames = selection.sessionNames,
            suggestedNewName = selection.suggestedNewName,
            canKill = selection.manager.killCommand != null,
            canRename = selection.manager.renameCommand != null,
            error = selection.error,
            onSelect = { name -> viewModel.onNewTabSessionSelected(selection.sessionId, name) },
            onKill = { name -> viewModel.killRemoteSession(name) },
            onRename = { old, new -> viewModel.renameRemoteSession(old, new) },
            onNewSession = { name ->
                viewModel.onNewTabSessionSelected(selection.sessionId, name.takeIf { it.isNotBlank() })
            },
            onDismiss = { viewModel.dismissNewTabSessionPicker() },
        )
    }

    // Resolve the active terminal foreground/background. For static
    // schemes use the enum's fixed longs; for MATERIAL_YOU pull live
    // values from the system theme so the terminal stays in sync with
    // the wallpaper-driven dynamic palette.
    val terminalBg: Color = if (colorScheme.isDynamic) MaterialTheme.colorScheme.surface
        else Color(colorScheme.background)
    val terminalFg: Color = if (colorScheme.isDynamic) MaterialTheme.colorScheme.onSurface
        else Color(colorScheme.foreground)

    Column(modifier = Modifier.fillMaxSize()) {
        if (tabs.isEmpty()) {
            EmptyTerminalState(
                fontSize = fontSize,
                backgroundColor = terminalBg,
                foregroundColor = terminalFg,
            )
        } else {
            // Tab row — can be hidden via Settings when the user wants more terminal space
            val profileColors = remember(tabs) {
                tabs.associate { tab ->
                    val color = if (tab.colorTag in 1..TAB_GROUP_COLORS.size) {
                        TAB_GROUP_COLORS[tab.colorTag - 1]
                    } else {
                        null // No color assigned — use default theme
                    }
                    tab.profileId to color
                }
            }
            val clampedIndex = activeTabIndex.coerceIn(0, tabs.size - 1)
            val indicatorColor = profileColors[tabs.getOrNull(clampedIndex)?.profileId]

            if (showTabBar && !fullscreen) {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val reconnecting by tab.isReconnecting.collectAsState()
                            val selected = activeTabIndex == index
                            var showTabMenu by remember { mutableStateOf(false) }
                            var renameDialogFor by remember { mutableStateOf<String?>(null) }
                            val tabColor = profileColors[tab.profileId]

                            renameDialogFor?.let { name ->
                                RenameSessionDialog(
                                    currentLabel = name,
                                    onDismiss = { renameDialogFor = null },
                                    onRename = { newName ->
                                        viewModel.renameAttachedSession(tab.sessionId, newName)
                                        renameDialogFor = null
                                    },
                                )
                            }

                            Box {
                                Surface(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .combinedClickable(
                                            onClick = { viewModel.selectTab(index) },
                                            onLongClick = { showTabMenu = true },
                                        ),
                                    shape = MaterialTheme.shapes.small,
                                    color = if (selected) {
                                        tabColor?.copy(alpha = 0.55f)
                                            ?: MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        tabColor?.copy(alpha = 0.25f)
                                            ?: MaterialTheme.colorScheme.surface
                                    },
                                    contentColor = run {
                                        val bg = tabColor ?: return@run if (selected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        val alpha = if (selected) 0.55f else 0.25f
                                        // Blend tab color over surface to get effective luminance
                                        val surfaceLum = MaterialTheme.colorScheme.surface.luminance()
                                        val effectiveLum = surfaceLum * (1 - alpha) + bg.luminance() * alpha
                                        if (effectiveLum > 0.5f) Color.Black else Color.White
                                    },
                                    tonalElevation = if (selected) 4.dp else 0.dp,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 8.dp,
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (reconnecting) {
                                            Icon(
                                                Icons.Filled.Autorenew,
                                                contentDescription = stringResource(R.string.terminal_reconnecting),
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        Text(
                                            tab.label,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                                // Refresh remote sessions when popup opens
                                LaunchedEffect(showTabMenu) {
                                    if (showTabMenu) viewModel.refreshRemoteSessions()
                                }
                                // Long-press action bar
                                DropdownMenu(
                                    expanded = showTabMenu,
                                    onDismissRequest = { showTabMenu = false },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                showTabMenu = false
                                                viewModel.addTab()
                                            },
                                            enabled = !newTabLoading,
                                        ) {
                                            if (newTabLoading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                if (newTabLoading) stringResource(R.string.terminal_new_tab_connecting)
                                                else stringResource(R.string.terminal_sessions),
                                            )
                                        }
                                        Row {
                                            IconButton(
                                                onClick = { viewModel.moveTab(index, -1); showTabMenu = false },
                                                enabled = index > 0,
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.terminal_move_left), modifier = Modifier.size(18.dp))
                                            }
                                            IconButton(
                                                onClick = { viewModel.moveTab(index, 1); showTabMenu = false },
                                                enabled = index < tabs.size - 1,
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.terminal_move_right), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        TextButton(
                                            onClick = {
                                                showTabMenu = false
                                                viewModel.closeTab(tab.sessionId)
                                            },
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error,
                                            ),
                                        ) {
                                            Text(stringResource(R.string.terminal_close))
                                            Spacer(Modifier.width(4.dp))
                                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    // Rename action for the current tab — only when the underlying SSH
                                    // session is wrapped in a session manager (tmux/zellij/screen/byobu)
                                    // that supports rename. Avoids forcing the user to open the picker
                                    // via a duplicate connection just to rename an existing session.
                                    val renameableName = viewModel.renameableSessionName(tab.sessionId)
                                    if (renameableName != null) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.common_rename)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.DriveFileRenameOutline,
                                                    null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                showTabMenu = false
                                                renameDialogFor = renameableName
                                            },
                                        )
                                    }
                                    // Show connected sessions without tabs + remote sessions (tmux/zellij)
                                    val untabbed by viewModel.untabbedSessions.collectAsState()
                                    val remoteSessions by viewModel.remoteSessionNames.collectAsState()
                                    val tabbedRemoteSessions = tabs.map { it.label }.toSet()
                                    val untabbedRemote = remoteSessions.filter { it !in tabbedRemoteSessions }
                                    if (untabbed.isNotEmpty() || untabbedRemote.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        // Remote sessions (tmux/zellij/screen) on current connection
                                        untabbedRemote.forEach { name ->
                                            DropdownMenuItem(
                                                text = { Text(name, style = MaterialTheme.typography.bodySmall) },
                                                leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)) },
                                                onClick = {
                                                    showTabMenu = false
                                                    viewModel.openRemoteSession(tab.profileId, name)
                                                },
                                            )
                                        }
                                        // Other SSH connections without tabs
                                        untabbed.forEach { session ->
                                            DropdownMenuItem(
                                                text = { Text(session.label, style = MaterialTheme.typography.bodySmall) },
                                                leadingIcon = { Icon(Icons.Filled.Cable, null, modifier = Modifier.size(16.dp)) },
                                                onClick = {
                                                    showTabMenu = false
                                                    viewModel.selectTabByProfileId(session.profileId)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Action buttons after tabs
                        if (showCopyOutputButton) {
                            IconButton(
                                onClick = {
                                    val output = viewModel.copyLastCommandOutput()
                                    if (output != null) {
                                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clip.setPrimaryClip(ClipData.newPlainText("command output", output))
                                        @Suppress("LocalContextGetResourceValueCall")
                                        android.widget.Toast.makeText(context, context.getString(R.string.terminal_copied_output, output.length), android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        @Suppress("LocalContextGetResourceValueCall")
                                        android.widget.Toast.makeText(context, context.getString(R.string.terminal_no_command_output), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.terminal_copy_last_output),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (showSearchButton) {
                            IconButton(
                                onClick = { viewModel.sendSearchKeys() },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.terminal_search),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Terminal area
            val activeTab = tabs.getOrNull(activeTabIndex)
            if (activeTab != null) {
                // key() forces Terminal recreation when switching tabs, ensuring
                // the emulator and keyboard input are bound to the correct session.
                key(activeTab.sessionId) {
                    // Wire OSC handler callbacks
                    val clipboard = remember {
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    }
                    activeTab.oscHandler.onClipboardSet = { text ->
                        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
                    }
                    activeTab.oscHandler.onNotification = { title, body ->
                        showTerminalNotification(context, title, body, activeTab.label)
                    }

                    val focusRequester = remember { FocusRequester() }

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }

                    var selectionController by remember {
                        mutableStateOf<org.connectbot.terminal.SelectionController?>(null)
                    }

                    // Notify parent when selection state changes.
                    // isSelectionActive is backed by Compose MutableState, so
                    // this block recomposes when selection starts/ends.
                    val selectionActive = selectionController?.isSelectionActive == true

                    // Register Activity-level key interceptor for layout-aware
                    // character mapping. This fires in dispatchKeyEvent() BEFORE
                    // the View hierarchy, bypassing termlib's hardcoded US QWERTY
                    // symbol table.
                    val currentSelectionActive by rememberUpdatedState(selectionActive)
                    val configuration = LocalConfiguration.current
                    val hasHardwareKeyboard by rememberUpdatedState(
                        configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS,
                    )
                    val currentFullscreen by rememberUpdatedState(fullscreen)
                    DisposableEffect(activeTab) {
                        val interceptor = { event: android.view.KeyEvent ->
                            // F11 toggles fullscreen (#138). Caught here, before
                            // handleLayoutAwareKeyEvent forwards it to the shell —
                            // tradeoff: TUI apps that use F11 won't see it. Pinpox
                            // confirmed this scope; revisit if users push back.
                            if (event.keyCode == android.view.KeyEvent.KEYCODE_F11 &&
                                event.action == android.view.KeyEvent.ACTION_DOWN
                            ) {
                                fullscreen = !currentFullscreen
                                true
                            } else if (event.keyCode == android.view.KeyEvent.KEYCODE_F11) {
                                // Swallow the matching ACTION_UP so the shell never
                                // sees a half key event.
                                true
                            } else {
                                handleLayoutAwareKeyEvent(
                                    event, activeTab,
                                    currentSelectionActive, hasHardwareKeyboard, viewModel,
                                )
                            }
                        }
                        KeyEventInterceptor.handler = interceptor
                        onDispose {
                            if (KeyEventInterceptor.handler === interceptor) {
                                KeyEventInterceptor.handler = null
                            }
                        }
                    }
                    val currentHyperlinkUri by activeTab.hyperlinkUri.collectAsState()

                    LaunchedEffect(selectionActive) {
                        onSelectionActiveChanged(selectionActive)
                        if (selectionActive && selectionController != null) {
                            expandSelectionToWord(selectionController!!, activeTab.emulator)
                        }
                    }

                    val isMouseMode by activeTab.mouseMode.collectAsState()
                    val isBracketPaste by activeTab.bracketPasteMode.collectAsState()

                    // Build gesture callback when mouse mode is active.
                    // Taps and long-presses are gated by mouseInputEnabled;
                    // scroll wheel always works when the TUI requests mouse mode.
                    val gestureCallback = remember(activeTab, isMouseMode, mouseInputEnabled, mouseDragSelects, terminalRightClick) {
                        if (isMouseMode) object : org.connectbot.terminal.TerminalGestureCallback {
                            override fun onTap(col: Int, row: Int): Boolean {
                                if (!mouseInputEnabled) return false
                                activeTab.sendInput(sgrMouseButton(0, col + 1, row + 1, true))
                                activeTab.sendInput(sgrMouseButton(0, col + 1, row + 1, false))
                                return true
                            }
                            override fun onLongPress(col: Int, row: Int): Boolean {
                                if (!mouseInputEnabled) return false
                                if (!terminalRightClick) return false // allow text selection instead
                                activeTab.sendInput(sgrMouseButton(2, col + 1, row + 1, true))
                                activeTab.sendInput(sgrMouseButton(2, col + 1, row + 1, false))
                                return true // suppress text selection
                            }
                            override fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean {
                                activeTab.sendInput(sgrMouseWheel(scrollUp, col + 1, row + 1))
                                return true // suppress scrollback
                            }
                            override fun onMouseDrag(
                                col: Int,
                                row: Int,
                                phase: org.connectbot.terminal.MouseDragPhase,
                            ): Boolean {
                                if (!mouseInputEnabled || !mouseDragSelects) return false
                                when (phase) {
                                    org.connectbot.terminal.MouseDragPhase.Start ->
                                        activeTab.sendInput(sgrMouseButton(0, col + 1, row + 1, true))
                                    org.connectbot.terminal.MouseDragPhase.Move ->
                                        activeTab.sendInput(sgrMouseMotion(0, col + 1, row + 1))
                                    org.connectbot.terminal.MouseDragPhase.End ->
                                        activeTab.sendInput(sgrMouseButton(0, col + 1, row + 1, false))
                                }
                                return true // claim the drag — terminal stops scroll fallback
                            }
                        } else null
                    }

                    // Smart clipboard intercepts all terminal copy operations
                    // (toolbar button + library popup) to strip TUI borders and
                    // unwrap soft-wrapped lines.
                    val realClipboard = LocalClipboardManager.current
                    val smartClipboard = remember(activeTab, realClipboard) {
                        SmartTerminalClipboard(
                            delegate = realClipboard,
                            getEmulator = { activeTab.emulator },
                            getController = { selectionController },
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(terminalModifier),
                    ) {
                        // ModifierManager bridges our Ctrl/Alt toolbar state to the
                        // library's KeyboardHandler. clearTransients is a no-op here
                        // because the library calls it during recomposition, not after
                        // key processing. We clear modifiers ourselves via
                        // onInputProcessed below.
                        val modifierManager = remember(viewModel) {
                            object : ModifierManager {
                                override fun isCtrlActive() = viewModel.ctrlActive.value
                                override fun isAltActive() = viewModel.altActive.value
                                override fun isShiftActive() = false
                                override fun clearTransients() { /* no-op */ }
                            }
                        }

                        // Update native emulator colors when scheme changes.
                        // MATERIAL_YOU: pulled from MaterialTheme above and
                        // re-applied whenever the system theme shifts (light /
                        // dark / wallpaper change), so the terminal repaints.
                        val terminalFgArgb = terminalFg.toArgb()
                        val terminalBgArgb = terminalBg.toArgb()
                        LaunchedEffect(terminalFgArgb, terminalBgArgb, activeTab.emulator) {
                            activeTab.emulator?.setDefaultColors(
                                terminalFgArgb,
                                terminalBgArgb,
                            )
                        }

                        // Force terminal redraw on resume from background
                        androidx.lifecycle.compose.LifecycleResumeEffect(activeTab.emulator) {
                            activeTab.emulator?.setDefaultColors(
                                terminalFgArgb,
                                terminalBgArgb,
                            )
                            onPauseOrDispose {}
                        }

                        // Shared paste action: read the (real, non-smart) system
                        // clipboard and send via sendInput, wrapping in bracketed-
                        // paste when the remote app has mode 2004 active.
                        val doPaste: () -> Unit = {
                            val text = realClipboard.getText()?.text
                            if (!text.isNullOrEmpty()) {
                                val payload = if (isBracketPaste) {
                                    "\u001b[200~$text\u001b[201~"
                                } else {
                                    text
                                }
                                activeTab.sendInput(payload.toByteArray())
                            }
                        }

                        // Hardware Ctrl+Shift+V shortcut. Null when the user has
                        // turned the shortcut off, so the V key passes through to
                        // the shell unchanged.
                        val pasteShortcut: (() -> Unit)? = if (interceptCtrlShiftV) doPaste else null

                        val keyboardMode = when {
                            rawKeyboardMode -> HavenKeyboardMode.Raw
                            allowStandardKeyboard -> HavenKeyboardMode.Standard
                            customKeyboardFlags != null -> HavenKeyboardMode.Custom(customKeyboardFlags!!)
                            else -> HavenKeyboardMode.Secure
                        }
                        CompositionLocalProvider(LocalClipboardManager provides smartClipboard) {
                            HavenTerminal(
                                terminalEmulator = activeTab.emulator,
                                modifier = Modifier.fillMaxSize(),
                                initialFontSize = fontSize.sp,
                                typeface = hackTypeface,
                                keyboardEnabled = true,
                                backgroundColor = terminalBg,
                                foregroundColor = terminalFg,
                                focusRequester = focusRequester,
                                modifierManager = modifierManager,
                                onPasteShortcut = pasteShortcut,
                                onPasteRequest = doPaste,
                                onSelectionControllerAvailable = { selectionController = it },
                                onTerminalDoubleTap = {
                                    val window = (view.context as? Activity)?.window ?: return@HavenTerminal
                                    val controller = WindowCompat.getInsetsController(window, view)
                                    val rootView = window.decorView
                                    val imeShowing = androidx.core.view.ViewCompat
                                        .getRootWindowInsets(rootView)
                                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                                    if (imeShowing) {
                                        controller.hide(WindowInsetsCompat.Type.ime())
                                    } else {
                                        focusRequester.requestFocus()
                                        controller.show(WindowInsetsCompat.Type.ime())
                                    }
                                },
                                onFontSizeChanged = { newSize ->
                                    viewModel.setFontSize(newSize.value.toInt())
                                },
                                onHyperlinkClick = { url ->
                                    val finalUrl = if (url.contains("://")) url else "https://$url"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)))
                                },
                                gestureCallback = gestureCallback,
                                keyboardMode = keyboardMode,
                            )
                        }

                        DisconnectCountdownBanner(
                            stallFlow = activeTab.secondsUntilDisconnect,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )

                        // Fullscreen toggle (#138). Small low-opacity overlay
                        // top-right; the TopCenter slot is taken by the
                        // disconnect banner so they never collide.
                        IconButton(
                            onClick = { fullscreen = !fullscreen },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (fullscreen) Icons.Filled.FullscreenExit
                                else Icons.Filled.Fullscreen,
                                contentDescription = stringResource(
                                    if (fullscreen) R.string.terminal_exit_fullscreen
                                    else R.string.terminal_enter_fullscreen,
                                ),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (!fullscreen) KeyboardToolbar(
                        onSendBytes = { bytes -> activeTab.sendInput(bytes) },
                        onDispatchKey = { mods, key -> activeTab.emulator?.dispatchKey(mods, key) },
                        focusRequester = focusRequester,
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        bracketPasteMode = isBracketPaste,
                        layout = toolbarLayout,
                        navBlockMode = navBlockMode,
                        onToggleCtrl = viewModel::toggleCtrl,
                        onToggleAlt = viewModel::toggleAlt,
                        onVncTap = if (activeTab.transportType == "SSH") {{
                            coroutineScope.launch {
                                val info = viewModel.getActiveVncInfo() ?: return@launch
                                if (info.stored) {
                                    onNavigateToVnc(info.host, info.port, info.username, info.password, info.sshForward, info.sessionId, info.colorDepth)
                                } else {
                                    vncDialogInfo = info
                                }
                            }
                        }} else if (activeTab.transportType == "LOCAL" && viewModel.isLocalDesktopInstalled) {{
                            if (!localVncLoading) {
                                localVncLoading = true
                                coroutineScope.launch {
                                    viewModel.ensureLocalVncProfile()
                                    viewModel.startLocalVncServer()
                                    kotlinx.coroutines.delay(4000)
                                    val pwd = viewModel.getLocalVncPassword()
                                    localVncLoading = false
                                    onNavigateToVnc("localhost", 5901, null, pwd, false, null, "BPP_24_TRUE")
                                }
                            }
                        }} else null,
                        vncLoading = localVncLoading,
                        selectionController = selectionController,
                        selectionActive = selectionActive,
                        hyperlinkUri = currentHyperlinkUri,
                        onPaste = { text -> activeTab.sendInput(text.toByteArray()) },
                        reorderMode = reorderMode,
                        onReorderModeChanged = {
                            reorderMode = it
                            onReorderModeChanged(it)
                        },
                        onToolbarLayoutChanged = onToolbarLayoutChanged,
                        onOpenSettings = onOpenToolbarSettings,
                        allowStandardKeyboard = allowStandardKeyboard,
                        onToggleStandardKeyboard = onToggleStandardKeyboard,
                        rawKeyboardMode = rawKeyboardMode,
                        onToggleRawKeyboard = onToggleRawKeyboard,
                        selectionContent = selectionController?.let { ctrl -> {
                            SelectionToolbarContent(
                                controller = ctrl,
                                hyperlinkUri = currentHyperlinkUri,
                                bracketPasteMode = isBracketPaste,
                                onPaste = { text -> activeTab.sendInput(text.toByteArray()) },
                            )
                        } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTerminalState(
    fontSize: Int,
    backgroundColor: Color,
    foregroundColor: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.terminal_connect_prompt),
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            color = foregroundColor,
        )
    }
}

@Composable
private fun NewTabSessionPickerDialog(
    managerLabel: String,
    sessionNames: List<String>,
    suggestedNewName: String = "",
    canKill: Boolean = false,
    canRename: Boolean = false,
    error: String? = null,
    onSelect: (String) -> Unit,
    onKill: (String) -> Unit = {},
    onRename: (old: String, new: String) -> Unit = { _, _ -> },
    onNewSession: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renamingSession by remember { mutableStateOf<String?>(null) }

    renamingSession?.let { name ->
        RenameSessionDialog(
            currentLabel = name,
            onDismiss = { renamingSession = null },
            onRename = { newName ->
                onRename(name, newName)
                renamingSession = null
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_sessions_title, managerLabel)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                sessionNames.forEach { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = {
                            Row {
                                if (canRename) {
                                    IconButton(onClick = { renamingSession = name }) {
                                        Icon(
                                            Icons.Filled.DriveFileRenameOutline,
                                            contentDescription = stringResource(R.string.terminal_rename_session),
                                        )
                                    }
                                }
                                if (canKill) {
                                    IconButton(onClick = { onKill(name) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.terminal_kill_session),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable { onSelect(name) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NewSessionInlineRow(
                    suggestedName = suggestedNewName,
                    onCreate = onNewSession,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * The "Create new session" row at the bottom of [NewTabSessionPickerDialog].
 * A pre-filled text field with a Create button. The field's text is
 * selected on first focus so a tap-then-type immediately replaces the
 * suggestion. Keyboard "Done" / Enter triggers Create. Mirrored from
 * the connections-module SessionPickerDialog for consistency. (#112)
 */
@Composable
private fun NewSessionInlineRow(
    suggestedName: String,
    onCreate: (String) -> Unit,
) {
    var fieldValue by remember(suggestedName) {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = suggestedName,
                selection = androidx.compose.ui.text.TextRange(0, suggestedName.length),
            )
        )
    }
    var hasBeenFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { fieldValue = it },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused && !hasBeenFocused) {
                        hasBeenFocused = true
                        fieldValue = fieldValue.copy(
                            selection = androidx.compose.ui.text.TextRange(0, fieldValue.text.length),
                        )
                    } else if (!focusState.isFocused) {
                        hasBeenFocused = false
                    }
                },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    if (fieldValue.text.isNotBlank()) onCreate(fieldValue.text)
                },
            ),
        )
        IconButton(
            onClick = { onCreate(fieldValue.text) },
            enabled = fieldValue.text.isNotBlank(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.terminal_new_session_create),
            )
        }
    }
}

/**
 * Intercepts hardware keyboard events to fix character mapping for non-US layouts.
 *
 * Called from [Activity.dispatchKeyEvent] BEFORE the View hierarchy processes events,
 * bypassing termlib's hardcoded US QWERTY symbol mappings in KeyboardHandler.
 *
 * Uses Android's [android.view.KeyEvent.getUnicodeChar] which respects the device's
 * KeyCharacterMap for layout-correct characters (e.g. Shift+2 → '"' on QWERTZ
 * instead of '@').
 *
 * Also fixes AltGr (right Alt) combinations — termlib treats all Alt as ESC prefix,
 * but AltGr produces composed characters (e.g. AltGr+Q → '@' on German QWERTZ).
 *
 * Special keys (Enter, Tab, arrows, F-keys) and Ctrl/Alt combos pass through to
 * termlib which handles them correctly via key codes.
 */
private fun handleLayoutAwareKeyEvent(
    event: android.view.KeyEvent,
    activeTab: TerminalTab,
    selectionActive: Boolean,
    hasHardwareKeyboard: Boolean,
    viewModel: TerminalViewModel,
): Boolean {
    if (event.action != android.view.KeyEvent.ACTION_DOWN) return false

    // Only intercept physical keyboard input. Software keyboard / IME events
    // must flow through InputConnection (commitText), otherwise composing input
    // like Chinese Pinyin gets sent as raw Latin letters.
    if (!hasHardwareKeyboard) return false
    if ((event.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false
    if (event.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) return false
    if (!event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD)) return false

    // Don't intercept during text selection — termlib manages selection keys
    if (selectionActive) return false

    val keyCode = event.keyCode

    // Skip modifier-only key presses
    when (keyCode) {
        android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
        android.view.KeyEvent.KEYCODE_SHIFT_RIGHT,
        android.view.KeyEvent.KEYCODE_CTRL_LEFT,
        android.view.KeyEvent.KEYCODE_CTRL_RIGHT,
        android.view.KeyEvent.KEYCODE_ALT_LEFT,
        android.view.KeyEvent.KEYCODE_ALT_RIGHT,
        android.view.KeyEvent.KEYCODE_META_LEFT,
        android.view.KeyEvent.KEYCODE_META_RIGHT,
        android.view.KeyEvent.KEYCODE_CAPS_LOCK,
        android.view.KeyEvent.KEYCODE_NUM_LOCK,
        android.view.KeyEvent.KEYCODE_SCROLL_LOCK,
        android.view.KeyEvent.KEYCODE_FUNCTION,
        -> return false
    }

    // Let termlib handle special terminal keys (navigation, function keys, numpad)
    if (isSpecialTerminalKey(keyCode)) return false

    // Let termlib handle Ctrl+key and left-Alt+key natively.
    // Control codes (Ctrl+C) and ESC prefix (Alt+x) are key-code based,
    // not layout-dependent. Exception: AltGr (right Alt) produces composed
    // characters via the KeyCharacterMap.
    val meta = event.metaState
    val hasAltGr = (meta and android.view.KeyEvent.META_ALT_RIGHT_ON) != 0
    if ((meta and android.view.KeyEvent.META_CTRL_ON) != 0 && !hasAltGr) return false
    if ((meta and android.view.KeyEvent.META_ALT_ON) != 0 && !hasAltGr) return false

    // Get the layout-correct character from Android's KeyCharacterMap.
    // Returns 0 for non-character keys, negative for combining/dead keys.
    val unicodeChar = event.getUnicodeChar(meta)
    if (unicodeChar <= 0) return false

    val char = unicodeChar.toChar()

    // Build modifier mask from toolbar state (shift/AltGr already baked
    // into the character by getUnicodeChar).
    var modifiers = 0
    if (viewModel.ctrlActive.value) modifiers = modifiers or 4
    if (viewModel.altActive.value) modifiers = modifiers or 2

    activeTab.emulator.dispatchCharacter(modifiers, char)

    // Clear toolbar modifiers after use (one-shot toggle)
    if (viewModel.ctrlActive.value) viewModel.toggleCtrl()
    if (viewModel.altActive.value) viewModel.toggleAlt()

    return true
}

/** Keys that termlib maps to VTermKey codes — let termlib handle these directly. */
private fun isSpecialTerminalKey(keyCode: Int): Boolean {
    if (keyCode in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12) return true
    if (keyCode in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS) return true
    return when (keyCode) {
        android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
        android.view.KeyEvent.KEYCODE_TAB,
        android.view.KeyEvent.KEYCODE_ESCAPE,
        android.view.KeyEvent.KEYCODE_DEL,         // Backspace
        android.view.KeyEvent.KEYCODE_FORWARD_DEL, // Delete
        android.view.KeyEvent.KEYCODE_DPAD_UP,
        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_MOVE_HOME,
        android.view.KeyEvent.KEYCODE_MOVE_END,
        android.view.KeyEvent.KEYCODE_PAGE_UP,
        android.view.KeyEvent.KEYCODE_PAGE_DOWN,
        android.view.KeyEvent.KEYCODE_INSERT,
        -> true
        else -> false
    }
}

/**
 * Build an SGR-encoded mouse wheel escape sequence.
 * Scroll up: button 64, scroll down: button 65.
 * Format: ESC [ < button ; col ; row M
 * col and row are 1-based terminal coordinates.
 */
private fun sgrMouseWheel(scrollUp: Boolean, col: Int, row: Int): ByteArray {
    val button = if (scrollUp) 64 else 65
    return "\u001b[<$button;$col;${row}M".toByteArray()
}

/**
 * SGR mouse button press/release sequence.
 * Format: ESC [ < button ; col ; row M (press) or m (release)
 * Button 0 = left, 2 = right. col and row are 1-based.
 */
/**
 * SGR mouse motion-while-button-held sequence (xterm button-event mode 1002).
 * Format: ESC [ < (button | 32) ; col ; row M
 * The +32 mask flags the event as motion-while-held rather than press; tmux
 * uses it to extend a running selection in copy-mode. col/row are 1-based.
 *
 * (#94 — without these, the remote sees only press+release and can't track
 * a drag-to-select.)
 */
private fun sgrMouseMotion(button: Int, col: Int, row: Int): ByteArray {
    val code = button or 32
    return "[<$code;$col;${row}M".toByteArray()
}

private fun sgrMouseButton(button: Int, col: Int, row: Int, pressed: Boolean): ByteArray {
    val suffix = if (pressed) 'M' else 'm'
    return "\u001b[<$button;$col;${row}$suffix".toByteArray()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VncSettingsDialog(
    host: String,
    initialPort: Int,
    initialUsername: String?,
    initialPassword: String?,
    initialSshForward: Boolean,
    initialColorDepth: String = "BPP_24_TRUE",
    onConnect: (port: Int, username: String?, password: String?, sshForward: Boolean, colorDepth: String, save: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var port by remember { mutableStateOf(initialPort.toString()) }
    var username by remember { mutableStateOf(initialUsername ?: "") }
    var password by remember { mutableStateOf(initialPassword ?: "") }
    var sshForward by remember { mutableStateOf(initialSshForward) }
    var colorDepth by remember { mutableStateOf(initialColorDepth) }
    var save by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_vnc_desktop)) },
        text = {
            Column {
                Text(stringResource(R.string.terminal_connect_to_host, host), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.terminal_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (optional — VeNCrypt only)") },
                    placeholder = { Text("Leave blank for classic VncAuth") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.terminal_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = sshForward,
                        onCheckedChange = { sshForward = it },
                    )
                    Text(stringResource(R.string.terminal_tunnel_through_ssh))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                val depthOptions = listOf(
                    "BPP_24_TRUE" to "24-bit colour (best quality)",
                    "BPP_16_TRUE" to "16-bit colour (faster)",
                    "BPP_8_INDEXED" to "256 colours (lowest bandwidth)",
                )
                var depthExpanded by remember { mutableStateOf(false) }
                val selectedDepth = depthOptions.firstOrNull { it.first == colorDepth } ?: depthOptions.first()
                ExposedDropdownMenuBox(
                    expanded = depthExpanded,
                    onExpandedChange = { depthExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedDepth.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Colour depth") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(depthExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = depthExpanded,
                        onDismissRequest = { depthExpanded = false },
                    ) {
                        depthOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    colorDepth = value
                                    depthExpanded = false
                                },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = save,
                        onCheckedChange = { save = it },
                    )
                    Text(stringResource(R.string.terminal_save_for_connection))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull() ?: 5900
                    onConnect(p, username.ifEmpty { null }, password.ifEmpty { null }, sshForward, colorDepth, save)
                },
            ) {
                Text(stringResource(R.string.terminal_connect))
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
 * Top-aligned banner shown over the terminal when the transport has gone
 * silent and is counting down to a forced disconnect. Hidden when the flow
 * is null. Currently driven by Mosh; other transports always emit null.
 */
@Composable
private fun DisconnectCountdownBanner(
    stallFlow: kotlinx.coroutines.flow.StateFlow<Int?>,
    modifier: Modifier = Modifier,
) {
    val seconds by stallFlow.collectAsState()
    val visible = seconds != null
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier,
    ) {
        val s = seconds ?: 0
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.padding(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Filled.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (s > 0) {
                        stringResource(R.string.terminal_disconnect_countdown, s)
                    } else {
                        stringResource(R.string.terminal_disconnect_now)
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun RenameSessionDialog(
    currentLabel: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_rename_session_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.terminal_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(label) },
                enabled = label.isNotBlank() && label != currentLabel,
            ) {
                Text(stringResource(R.string.terminal_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

