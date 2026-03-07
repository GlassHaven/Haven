package sh.haven.feature.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import sh.haven.core.data.preferences.UserPreferencesRepository

@Composable
fun TerminalScreen(
    navigateToProfileId: String? = null,
    isActive: Boolean = false,
    terminalModifier: Modifier = Modifier,
    fontSize: Int = UserPreferencesRepository.DEFAULT_FONT_SIZE,
    onNavigateToConnections: () -> Unit = {},
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

    // Navigate to specific tab if requested
    LaunchedEffect(navigateToProfileId) {
        if (navigateToProfileId != null) {
            viewModel.selectTabByProfileId(navigateToProfileId)
        }
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
            PrimaryScrollableTabRow(
                selectedTabIndex = activeTabIndex.coerceIn(0, tabs.size - 1),
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 8.dp,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = activeTabIndex == index,
                        onClick = { viewModel.selectTab(index) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.label, maxLines = 1)
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
                // "+" tab for adding new tab
                Tab(
                    selected = false,
                    onClick = { viewModel.addTab() },
                    enabled = !newTabLoading,
                    text = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New tab",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
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
                    val currentHyperlinkUri by activeTab.hyperlinkUri.collectAsState()

                    LaunchedEffect(selectionActive) {
                        onSelectionActiveChanged(selectionActive)
                        if (selectionActive && selectionController != null) {
                            expandSelectionToWord(selectionController!!, activeTab.emulator)
                        }
                    }

                    val isMouseMode by activeTab.mouseMode.collectAsState()
                    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .onSizeChanged { surfaceSize = it }
                            .pointerInput(activeTab.sessionId, isMouseMode, selectionActive) {
                                terminalGestureInterceptor(
                                    activeTab = activeTab,
                                    mouseMode = isMouseMode,
                                    selectionActive = selectionActive,
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

                    // Selection toolbar replaces keyboard toolbar during selection
                    if (selectionActive && selectionController != null) {
                        SelectionToolbar(
                            controller = selectionController!!,
                            hyperlinkUri = currentHyperlinkUri,
                            onPaste = { text ->
                                activeTab.sendInput(text.toByteArray())
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        KeyboardToolbar(
                            onSendBytes = { bytes -> activeTab.sendInput(bytes) },
                            focusRequester = focusRequester,
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            onToggleCtrl = viewModel::toggleCtrl,
                            onToggleAlt = viewModel::toggleAlt,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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

/**
 * Unified gesture interceptor for the terminal surface.
 *
 * Runs on PointerEventPass.Initial so it sees events before the Terminal
 * composable's internal gesture handler. Behavior:
 *
 * - Long press (hold still): passes through to Terminal for selection
 * - Horizontal drag: passes through for page swiping / tab navigation
 * - Vertical drag + mouse mode: consumed, emits SGR mouse wheel sequences
 * - Vertical drag + no mouse mode: ignored (no touch scrollback)
 * - Selection already active: passes through for handle drag
 */
private suspend fun PointerInputScope.terminalGestureInterceptor(
    activeTab: TerminalTab,
    mouseMode: Boolean,
    selectionActive: Boolean,
    surfaceSize: () -> IntSize,
) {
    // When selection is active, let the Terminal handle handle-drag etc.
    if (selectionActive) return

    val touchSlop = viewConfiguration.touchSlop
    var lastDragEndTime = 0L

    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            val firstChange = down.changes.firstOrNull() ?: continue
            if (!firstChange.pressed) continue

            // If a drag just ended, consume the next touch-down so
            // Terminal doesn't start a long-press selection immediately.
            val inCooldown = System.currentTimeMillis() - lastDragEndTime < DRAG_SELECTION_COOLDOWN_MS

            val startX = firstChange.position.x
            val startY = firstChange.position.y
            var classified = false
            var isHorizontal = false
            var isVertical = false
            var accumulatedY = 0f
            var wasDrag = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break

                if (!classified) {
                    val dx = change.position.x - startX
                    val dy = change.position.y - startY
                    val absDx = abs(dx)
                    val absDy = abs(dy)

                    if (absDx > touchSlop || absDy > touchSlop) {
                        classified = true
                        isHorizontal = absDx > absDy
                        isVertical = !isHorizontal
                        wasDrag = true
                        if (isVertical && mouseMode) {
                            accumulatedY = dy
                        }
                    }
                }

                if (!classified) {
                    // During cooldown, consume hold-still events to
                    // prevent Terminal from starting a long-press selection.
                    if (inCooldown) change.consume()
                    continue
                }

                if (isHorizontal) {
                    // Let horizontal drags pass through for page/tab swiping
                    break
                }

                if (isVertical && mouseMode) {
                    // Consume vertical drags and emit SGR wheel events.
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
                    continue
                }
            }

            if (wasDrag) {
                lastDragEndTime = System.currentTimeMillis()
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

