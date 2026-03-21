package sh.haven.feature.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import sh.haven.core.ui.KeyEventInterceptor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    navigateToProfileId: String? = null,
    newSessionProfileId: String? = null,
    isActive: Boolean = false,
    terminalModifier: Modifier = Modifier,
    fontSize: Int = UserPreferencesRepository.DEFAULT_FONT_SIZE,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onNavigateToConnections: () -> Unit = {},
    onNavigateToVnc: (host: String, port: Int, password: String?, sshForward: Boolean, sshSessionId: String?) -> Unit = { _, _, _, _, _ -> },
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsState()
    val activeTabIndex by viewModel.activeTabIndex.collectAsState()
    val ctrlActive by viewModel.ctrlActive.collectAsState()
    val altActive by viewModel.altActive.collectAsState()
    val colorScheme by viewModel.terminalColorScheme.collectAsState()
    val navigateToConnections by viewModel.navigateToConnections.collectAsState()
    val newTabSessionPicker by viewModel.newTabSessionPicker.collectAsState()
    val newTabLoading by viewModel.newTabLoading.collectAsState()
    var vncDialogInfo by remember { mutableStateOf<VncInfo?>(null) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val context = LocalContext.current
    val hackTypeface = remember {
        ResourcesCompat.getFont(context, sh.haven.core.ui.R.font.hack_regular)
            ?: android.graphics.Typeface.MONOSPACE
    }
    val view = LocalView.current

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
            initialPassword = info.password,
            initialSshForward = info.sshForward,
            onConnect = { port, password, sshForward, save ->
                if (save) {
                    viewModel.saveVncSettings(info.profileId, port, password, sshForward)
                }
                vncDialogInfo = null
                onNavigateToVnc(info.host, port, password, sshForward, info.sessionId)
            },
            onDismiss = { vncDialogInfo = null },
        )
    }

    // Session picker dialog for new tab
    newTabSessionPicker?.let { selection ->
        NewTabSessionPickerDialog(
            managerLabel = selection.managerLabel,
            sessionNames = selection.sessionNames,
            canKill = selection.manager.killCommand != null,
            canRename = selection.manager.renameCommand != null,
            error = selection.error,
            onSelect = { name -> viewModel.onNewTabSessionSelected(selection.sessionId, name) },
            onKill = { name -> viewModel.killRemoteSession(name) },
            onRename = { old, new -> viewModel.renameRemoteSession(old, new) },
            onNewSession = { viewModel.onNewTabSessionSelected(selection.sessionId, null) },
            onDismiss = { viewModel.dismissNewTabSessionPicker() },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (tabs.isEmpty()) {
            EmptyTerminalState(
                fontSize = fontSize,
                backgroundColor = Color(colorScheme.background),
                foregroundColor = Color(colorScheme.foreground),
            )
        } else {
            // Tab row — always show when tabs exist so "+" button is accessible
            val profileColors = remember(tabs) {
                tabs.map { it.profileId }.distinct()
                    .withIndex().associate { (i, id) ->
                        id to TAB_GROUP_COLORS[i % TAB_GROUP_COLORS.size]
                    }
            }
            val clampedIndex = activeTabIndex.coerceIn(0, tabs.size - 1)
            val indicatorColor = profileColors[tabs.getOrNull(clampedIndex)?.profileId]

            PrimaryScrollableTabRow(
                selectedTabIndex = clampedIndex,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 8.dp,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(clampedIndex),
                        color = indicatorColor ?: MaterialTheme.colorScheme.primary,
                    )
                },
            ) {

                tabs.forEachIndexed { index, tab ->
                    val reconnecting by tab.isReconnecting.collectAsState()
                    Tab(
                        selected = activeTabIndex == index,
                        onClick = { viewModel.selectTab(index) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                profileColors[tab.profileId]?.let { color ->
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(16.dp)
                                            .background(color, CircleShape),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                if (reconnecting) {
                                    Icon(
                                        Icons.Filled.Autorenew,
                                        contentDescription = "Reconnecting",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Text(tab.label, maxLines = 1)
                                if (activeTabIndex == index) {
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { viewModel.addTab() },
                                        enabled = !newTabLoading,
                                        modifier = Modifier.size(20.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "Clone tab",
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.closeTab(tab.sessionId) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Close tab",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        },
                    )
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
                    val hasHardwareKeyboard by rememberUpdatedState(
                        context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS,
                    )
                    DisposableEffect(activeTab) {
                        val interceptor = { event: android.view.KeyEvent ->
                            handleLayoutAwareKeyEvent(
                                event, activeTab,
                                currentSelectionActive, hasHardwareKeyboard, viewModel,
                            )
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
                    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

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
                            .onSizeChanged { surfaceSize = it }
                            .pointerInput(activeTab.sessionId, isMouseMode) {
                                terminalGestureInterceptor(
                                    activeTab = activeTab,
                                    mouseMode = isMouseMode,
                                    isSelectionActive = { selectionController?.isSelectionActive == true },
                                    selectionController = { selectionController },
                                    surfaceSize = { surfaceSize },
                                )
                            }
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

                        CompositionLocalProvider(LocalClipboardManager provides smartClipboard) {
                            Terminal(
                                terminalEmulator = activeTab.emulator,
                                modifier = Modifier.fillMaxSize(),
                                initialFontSize = fontSize.sp,
                                typeface = hackTypeface,
                                keyboardEnabled = true,
                                backgroundColor = Color(colorScheme.background),
                                foregroundColor = Color(colorScheme.foreground),
                                focusRequester = focusRequester,
                                modifierManager = modifierManager,
                                onSelectionControllerAvailable = { selectionController = it },
                            )
                        }

                    }

                    KeyboardToolbar(
                        onSendBytes = { bytes -> activeTab.sendInput(bytes) },
                        focusRequester = focusRequester,
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        bracketPasteMode = isBracketPaste,
                        layout = toolbarLayout,
                        onToggleCtrl = viewModel::toggleCtrl,
                        onToggleAlt = viewModel::toggleAlt,
                        onVncTap = if (activeTab.transportType == "SSH") {{
                            coroutineScope.launch {
                                val info = viewModel.getActiveVncInfo() ?: return@launch
                                if (info.stored) {
                                    onNavigateToVnc(info.host, info.port, info.password, info.sshForward, info.sessionId)
                                } else {
                                    vncDialogInfo = info
                                }
                            }
                        }} else null,
                        selectionController = selectionController,
                        selectionActive = selectionActive,
                        hyperlinkUri = currentHyperlinkUri,
                        onPaste = { text -> activeTab.sendInput(text.toByteArray()) },
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
            text = "Connect to a server to start a session.",
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
    canKill: Boolean = false,
    canRename: Boolean = false,
    error: String? = null,
    onSelect: (String) -> Unit,
    onKill: (String) -> Unit = {},
    onRename: (old: String, new: String) -> Unit = { _, _ -> },
    onNewSession: () -> Unit,
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
        title = { Text("$managerLabel sessions") },
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
                                            contentDescription = "Rename session",
                                        )
                                    }
                                }
                                if (canKill) {
                                    IconButton(onClick = { onKill(name) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Kill session",
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
                ListItem(
                    headlineContent = {
                        Text(
                            "New session",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable { onNewSession() },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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
    if (!hasHardwareKeyboard) return false

    // Only remap physical keyboard input. IME/software keyboard events must
    // flow through InputConnection (commitText), otherwise composing input
    // like Chinese Pinyin gets sent as raw Latin letters.
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

/** Pixels of vertical drag accumulated before emitting one scroll event. */
private const val SCROLL_THRESHOLD_PX = 40f

/** Millis after a drag gesture before long-press selection is allowed again. */
private const val DRAG_SELECTION_COOLDOWN_MS = 600L

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

/** Gesture kind — once classified per touch, locked for the touch lifetime. */
private enum class GestureKind { UNDECIDED, TAB_SWIPE, SCROLL, SELECTION }

/** Fraction of terminal height at top/bottom that triggers edge-scroll during selection drag. */
private const val EDGE_SCROLL_ZONE = 0.12f

/** Millis between edge-scroll steps while finger is in the zone. */
private const val EDGE_SCROLL_INTERVAL_MS = 150L

/**
 * Unified gesture interceptor for the terminal surface.
 *
 * Runs on PointerEventPass.Initial so it sees events before the Terminal
 * composable's internal gesture handler.
 *
 * Gesture classification is mutually exclusive — once a touch is classified
 * as tab-swipe, scroll, or selection-extend, it stays that way until the
 * finger lifts:
 *
 * - Hold still (undecided): passes through to Terminal for long-press detection
 * - Horizontal drag (TAB_SWIPE): passes through for page swiping / tab navigation
 * - Vertical drag (SCROLL): consumed, emits SGR mouse wheel sequences (mouse mode)
 * - Selection active (SELECTION): consumed, extends selection like a highlighter;
 *   dragging near top/bottom edge auto-scrolls the terminal slowly
 *
 * [isSelectionActive] and [selectionController] are read dynamically so the
 * handler doesn't restart when selection state changes mid-gesture.
 */
private suspend fun PointerInputScope.terminalGestureInterceptor(
    activeTab: TerminalTab,
    mouseMode: Boolean,
    isSelectionActive: () -> Boolean,
    selectionController: () -> org.connectbot.terminal.SelectionController?,
    surfaceSize: () -> IntSize,
) {
    val touchSlop = viewConfiguration.touchSlop
    var lastDragEndTime = 0L

    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            val firstChange = down.changes.firstOrNull() ?: continue
            if (!firstChange.pressed) continue

            // If selection is already active at touch-start (handle manipulation),
            // don't intercept — let Terminal handle its own selection UI.
            if (isSelectionActive()) continue

            val inCooldown = System.currentTimeMillis() - lastDragEndTime < DRAG_SELECTION_COOLDOWN_MS

            val startX = firstChange.position.x
            val startY = firstChange.position.y
            var kind = GestureKind.UNDECIDED
            var accumulatedY = 0f
            var wasDrag = false
            var lastEdgeScrollTime = 0L

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break

                // Selection always takes priority — the Terminal's long-press
                // detector runs on a later PointerEventPass, so isSelectionActive()
                // may lag our Initial pass by one event.  Checking every event and
                // allowing override of TAB_SWIPE/SCROLL prevents both gestures from
                // firing simultaneously.
                if (isSelectionActive() && kind != GestureKind.SELECTION) {
                    kind = GestureKind.SELECTION
                }

                // Classify by first significant movement
                if (kind == GestureKind.UNDECIDED) {
                    val dx = change.position.x - startX
                    val dy = change.position.y - startY
                    val absDx = abs(dx)
                    val absDy = abs(dy)

                    if (absDx > touchSlop || absDy > touchSlop) {
                        wasDrag = true
                        kind = if (absDx > absDy) GestureKind.TAB_SWIPE
                               else GestureKind.SCROLL
                        if (kind == GestureKind.SCROLL && mouseMode) {
                            accumulatedY = dy
                        }
                    }
                }

                when (kind) {
                    GestureKind.UNDECIDED -> {
                        if (inCooldown) change.consume()
                    }

                    GestureKind.TAB_SWIPE -> {
                        // Don't consume, don't break — events pass through to
                        // the pager naturally.  Staying in the loop lets us
                        // detect late selection activation and override.
                    }

                    GestureKind.SCROLL -> {
                        if (mouseMode) {
                            change.consume()
                            accumulatedY += change.position.y - change.previousPosition.y

                            while (abs(accumulatedY) >= SCROLL_THRESHOLD_PX) {
                                val draggedUp = accumulatedY < 0
                                accumulatedY += if (draggedUp) SCROLL_THRESHOLD_PX else -SCROLL_THRESHOLD_PX
                                val scrollUp = !draggedUp

                                val size = surfaceSize()
                                if (size.width > 0 && size.height > 0) {
                                    val dims = activeTab.emulator.dimensions
                                    val col = ((change.position.x / size.width) * dims.columns)
                                        .toInt().coerceIn(1, dims.columns)
                                    val row = ((change.position.y / size.height) * dims.rows)
                                        .toInt().coerceIn(1, dims.rows)
                                    activeTab.sendInput(sgrMouseWheel(scrollUp, col, row))
                                }
                            }
                        }
                    }

                    GestureKind.SELECTION -> {
                        change.consume()
                        val ctrl = selectionController() ?: continue
                        val size = surfaceSize()
                        if (size.width <= 0 || size.height <= 0) continue

                        val dims = activeTab.emulator.dimensions
                        val col = ((change.position.x / size.width) * dims.columns)
                            .toInt().coerceIn(0, dims.columns - 1)
                        val row = ((change.position.y / size.height) * dims.rows)
                            .toInt().coerceIn(0, dims.rows - 1)
                        updateSelectionEndAbsolute(ctrl, row, col)

                        // Edge-scroll: when finger is near top/bottom and mouse
                        // mode is active, emit slow scroll events so the user
                        // can extend selection beyond the visible screen.
                        // Only in mouse mode — without it, SGR sequences would
                        // appear as literal text on the prompt.
                        if (mouseMode) {
                            val relY = change.position.y / size.height
                            val now = System.currentTimeMillis()
                            if ((relY < EDGE_SCROLL_ZONE || relY > 1f - EDGE_SCROLL_ZONE) &&
                                now - lastEdgeScrollTime >= EDGE_SCROLL_INTERVAL_MS
                            ) {
                                val scrollUp = relY < EDGE_SCROLL_ZONE
                                activeTab.sendInput(sgrMouseWheel(scrollUp, col + 1, row + 1))
                                lastEdgeScrollTime = now
                            }
                        }
                    }
                }
            }

            if (wasDrag) {
                lastDragEndTime = System.currentTimeMillis()
            }
        }
    }
}

@Composable
private fun VncSettingsDialog(
    host: String,
    initialPort: Int,
    initialPassword: String?,
    initialSshForward: Boolean,
    onConnect: (port: Int, password: String?, sshForward: Boolean, save: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var port by remember { mutableStateOf(initialPort.toString()) }
    var password by remember { mutableStateOf(initialPassword ?: "") }
    var sshForward by remember { mutableStateOf(initialSshForward) }
    var save by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VNC Desktop") },
        text = {
            Column {
                Text("Connect to $host", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = sshForward,
                        onCheckedChange = { sshForward = it },
                    )
                    Text("Tunnel through SSH")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = save,
                        onCheckedChange = { save = it },
                    )
                    Text("Save for this connection")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull() ?: 5900
                    onConnect(p, password.ifEmpty { null }, sshForward, save)
                },
            ) {
                Text("Connect")
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
private fun RenameSessionDialog(
    currentLabel: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(label) },
                enabled = label.isNotBlank() && label != currentLabel,
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

