package sh.haven.feature.terminal

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.connectbot.terminal.SelectionController
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarKey
import kotlinx.coroutines.delay
import sh.haven.core.data.preferences.ToolbarLayout

// VT100/xterm escape sequences for special keys
private const val ESC = "\u001b"
private val KEY_ESC = byteArrayOf(0x1b)
private val KEY_TAB = byteArrayOf(0x09)
private val KEY_SHIFT_TAB = "$ESC[Z".toByteArray()
private val KEY_HOME = "$ESC[H".toByteArray()
private val KEY_END = "$ESC[F".toByteArray()
private val KEY_PGUP = "$ESC[5~".toByteArray()
private val KEY_PGDN = "$ESC[6~".toByteArray()

/** Keys that form the aligned navigation block across rows. */
private val NAV_KEYS = setOf(
    ToolbarKey.ARROW_UP, ToolbarKey.ARROW_DOWN,
    ToolbarKey.ARROW_LEFT, ToolbarKey.ARROW_RIGHT,
    ToolbarKey.HOME, ToolbarKey.END,
    ToolbarKey.PGUP, ToolbarKey.PGDN,
)

/**
 * Fixed grid positions for the nav block.
 * Row 0 (top):    [Home] [ ↑ ] [End] [PgUp]
 * Row 1 (bottom): [ ← ] [ ↓ ] [ → ] [PgDn]
 */
private val NAV_GRID_TOP = arrayOf(
    ToolbarKey.HOME,
    ToolbarKey.ARROW_UP,
    ToolbarKey.END,
    ToolbarKey.PGUP,
)
private val NAV_GRID_BOTTOM = arrayOf(
    ToolbarKey.ARROW_LEFT,
    ToolbarKey.ARROW_DOWN,
    ToolbarKey.ARROW_RIGHT,
    ToolbarKey.PGDN,
)

/** Width of each cell in the nav block grid. */
private val NAV_CELL_WIDTH = 44.dp

/** Bundled callbacks threaded through the toolbar composable hierarchy. */
data class ToolbarCallbacks(
    val onSendBytes: (ByteArray) -> Unit,
    val onDispatchKey: (Int, Int) -> Unit,
    val onToggleCtrl: () -> Unit,
    val onToggleAlt: () -> Unit,
    val onToggleShift: () -> Unit,
    val onShiftUsed: () -> Unit,
)

val LocalToolbarCallbacks = compositionLocalOf<ToolbarCallbacks> {
    error("ToolbarCallbacks not provided")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardToolbar(
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (modifiers: Int, key: Int) -> Unit = { _, _ -> },
    focusRequester: FocusRequester,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    bracketPasteMode: Boolean = false,
    layout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onToggleCtrl: () -> Unit = {},
    onToggleAlt: () -> Unit = {},
    onVncTap: (() -> Unit)? = null,
    selectionToggleVisible: Boolean = false,
    selectionEnabled: Boolean = true,
    onSelectionEnabledChange: (Boolean) -> Unit = {},
    selectionController: SelectionController? = null,
    selectionActive: Boolean = false,
    hyperlinkUri: String? = null,
    onPaste: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var shiftActive by remember { mutableStateOf(false) }
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible

    val callbacks = ToolbarCallbacks(
        onSendBytes = onSendBytes,
        onDispatchKey = onDispatchKey,
        onToggleCtrl = onToggleCtrl,
        onToggleAlt = onToggleAlt,
        onToggleShift = { shiftActive = !shiftActive },
        onShiftUsed = { shiftActive = false },
    )

    CompositionLocalProvider(LocalToolbarCallbacks provides callbacks) {
        Surface(
            tonalElevation = 2.dp,
            modifier = modifier,
        ) {
            if (selectionActive && selectionController != null) {
                Column {
                    if (layout.rows.size >= 2) {
                        ToolbarRow(
                            items = layout.row1,
                            focusRequester = focusRequester,
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            shiftActive = shiftActive,
                            imeVisible = imeVisible,
                            view = view,
                            onVncTap = onVncTap,
                            selectionToggleVisible = selectionToggleVisible,
                            selectionEnabled = selectionEnabled,
                            onSelectionEnabledChange = onSelectionEnabledChange,
                        )
                    }
                    SelectionToolbarContent(
                        controller = selectionController,
                        hyperlinkUri = hyperlinkUri,
                        bracketPasteMode = bracketPasteMode,
                        onPaste = onPaste,
                    )
                }
            } else if (layout.rows.size >= 2) {
                AlignedToolbarContent(
                    layout = layout,
                    focusRequester = focusRequester,
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    shiftActive = shiftActive,
                    imeVisible = imeVisible,
                    view = view,
                    onVncTap = onVncTap,
                    selectionToggleVisible = selectionToggleVisible,
                    selectionEnabled = selectionEnabled,
                    onSelectionEnabledChange = onSelectionEnabledChange,
                )
            } else {
                Column {
                    for (row in layout.rows) {
                        if (row.isNotEmpty()) {
                            ToolbarRow(
                                items = row,
                                focusRequester = focusRequester,
                                ctrlActive = ctrlActive,
                                altActive = altActive,
                                shiftActive = shiftActive,
                                imeVisible = imeVisible,
                                view = view,
                                onVncTap = onVncTap,
                                selectionToggleVisible = selectionToggleVisible,
                                selectionEnabled = selectionEnabled,
                                onSelectionEnabledChange = onSelectionEnabledChange,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the two-row toolbar with an aligned navigation block.
 *
 * Layout: [left keys] [nav grid] [symbols]
 * Both rows scroll together so the nav block stays vertically aligned.
 */
@Composable
private fun AlignedToolbarContent(
    layout: ToolbarLayout,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onVncTap: (() -> Unit)?,
    selectionToggleVisible: Boolean,
    selectionEnabled: Boolean,
    onSelectionEnabledChange: (Boolean) -> Unit,
) {
    val cb = LocalToolbarCallbacks.current
    // Split each row into: left (non-nav), right (non-nav after nav keys)
    val (row1Left, row1Right) = splitAroundNav(layout.row1)
    val (row2Left, row2Right) = splitAroundNav(layout.row2)

    // Collect which nav keys are present across all rows
    val presentNavKeys = layout.rows.flatten()
        .filterIsInstance<ToolbarItem.BuiltIn>()
        .filter { it.key in NAV_KEYS }
        .map { it.key }
        .toSet()

    // If no nav keys present, fall back to simple rows
    if (presentNavKeys.isEmpty()) {
        Column {
            ToolbarRow(
                items = layout.row1,
                focusRequester = focusRequester,
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                imeVisible = imeVisible,
                view = view,
                onVncTap = onVncTap,
                selectionToggleVisible = selectionToggleVisible,
                selectionEnabled = selectionEnabled,
                onSelectionEnabledChange = onSelectionEnabledChange,
            )
            ToolbarRow(
                items = layout.row2,
                focusRequester = focusRequester,
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                imeVisible = imeVisible,
                view = view,
                onVncTap = onVncTap,
                selectionToggleVisible = selectionToggleVisible,
                selectionEnabled = selectionEnabled,
                onSelectionEnabledChange = onSelectionEnabledChange,
            )
        }
        return
    }

    // All three columns scroll together for alignment
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        // Left keys column — uses IntrinsicSize.Max so both rows have equal width
        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            KeyRow(Modifier.fillMaxWidth()) {
                for (item in row1Left) {
                    RenderItem(item, focusRequester, ctrlActive, altActive,
                        shiftActive, imeVisible, view)
                }
            }
            KeyRow(Modifier.fillMaxWidth()) {
                ToolbarLeadingAccessory(
                    onVncTap = onVncTap,
                    selectionToggleVisible = selectionToggleVisible,
                    selectionEnabled = selectionEnabled,
                    onSelectionEnabledChange = onSelectionEnabledChange,
                )
                for (item in row2Left) {
                    RenderItem(item, focusRequester, ctrlActive, altActive,
                        shiftActive, imeVisible, view)
                }
            }
        }

        // Nav block grid — fixed-width cells ensure vertical alignment
        Column {
            KeyRow {
                for (key in NAV_GRID_TOP) {
                    if (key != null && key in presentNavKeys) {
                        NavBuiltInKey(key, shiftActive)
                    } else {
                        NavCell {}
                    }
                }
            }
            KeyRow {
                for (key in NAV_GRID_BOTTOM) {
                    if (key != null && key in presentNavKeys) {
                        NavBuiltInKey(key, shiftActive)
                    } else {
                        NavCell {}
                    }
                }
            }
        }

        // Right keys (symbols) — typically only on row 2
        if (row1Right.isNotEmpty() || row2Right.isNotEmpty()) {
            Column {
                if (row1Right.isNotEmpty()) {
                    KeyRow {
                        for (item in row1Right) {
                            RenderItem(item, focusRequester, ctrlActive, altActive,
                                shiftActive, imeVisible, view)
                        }
                    }
                } else {
                    Spacer(Modifier.height(34.dp))
                }
                KeyRow {
                    for (item in row2Right) {
                        RenderItem(item, focusRequester, ctrlActive, altActive,
                            shiftActive, imeVisible, view)
                    }
                }
            }
        }
    }
}

/** A single toolbar key row with standard padding and alignment. */
@Composable
private fun KeyRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Split a row's items into (before nav keys, after nav keys). Nav keys themselves are excluded. */
private fun splitAroundNav(row: List<ToolbarItem>): Pair<List<ToolbarItem>, List<ToolbarItem>> {
    val firstNavIdx = row.indexOfFirst { it is ToolbarItem.BuiltIn && it.key in NAV_KEYS }
    val lastNavIdx = row.indexOfLast { it is ToolbarItem.BuiltIn && it.key in NAV_KEYS }
    if (firstNavIdx == -1) return row to emptyList()
    val left = row.subList(0, firstNavIdx)
    val right = if (lastNavIdx + 1 < row.size) row.subList(lastNavIdx + 1, row.size) else emptyList()
    return left to right
}

/** Render a nav-block key with fixed cell width. */
@Composable
private fun NavBuiltInKey(
    key: ToolbarKey,
    shiftActive: Boolean,
) {
    val cb = LocalToolbarCallbacks.current
    // Arrow and nav keys go through dispatchKey so libvterm applies
    // DECCKM (application cursor mode) — needed for Mutt, vim, etc.
    when (key) {
        ToolbarKey.ARROW_LEFT -> NavArrowButton("\u2190") { cb.onDispatchKey(0, VTERM_KEY_LEFT) }
        ToolbarKey.ARROW_UP -> NavArrowButton("\u2191") { cb.onDispatchKey(0, VTERM_KEY_UP) }
        ToolbarKey.ARROW_DOWN -> NavArrowButton("\u2193") { cb.onDispatchKey(0, VTERM_KEY_DOWN) }
        ToolbarKey.ARROW_RIGHT -> NavArrowButton("\u2192") { cb.onDispatchKey(0, VTERM_KEY_RIGHT) }
        ToolbarKey.HOME -> NavTextButton("Home") { cb.onDispatchKey(0, VTERM_KEY_HOME) }
        ToolbarKey.END -> NavTextButton("End") { cb.onDispatchKey(0, VTERM_KEY_END) }
        ToolbarKey.PGUP -> NavTextButton("PgUp") { cb.onDispatchKey(0, VTERM_KEY_PAGEUP) }
        ToolbarKey.PGDN -> NavTextButton("PgDn") { cb.onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
        else -> Spacer(Modifier.width(NAV_CELL_WIDTH))
    }
}

// VTermKey constants (from libvterm/include/vterm_keycodes.h)
private const val VTERM_KEY_UP = 5
private const val VTERM_KEY_DOWN = 6
private const val VTERM_KEY_LEFT = 7
private const val VTERM_KEY_RIGHT = 8
private const val VTERM_KEY_HOME = 11
private const val VTERM_KEY_END = 12
private const val VTERM_KEY_PAGEUP = 13
private const val VTERM_KEY_PAGEDOWN = 14
private const val VTERM_KEY_FUNCTION_0 = 256

/** Render any toolbar item (non-nav keys in the left/right sections). */
@Composable
private fun RenderItem(
    item: ToolbarItem,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
) {
    val cb = LocalToolbarCallbacks.current
    when (item) {
        is ToolbarItem.BuiltIn -> BuiltInKey(
            key = item.key,
            focusRequester = focusRequester,
            ctrlActive = ctrlActive,
            altActive = altActive,
            shiftActive = shiftActive,
            imeVisible = imeVisible,
            view = view,
        )
        is ToolbarItem.Custom -> {
            SymbolButton(item.label) {
                val bytes = item.send.toByteArray()
                if (ctrlActive || altActive) {
                    if (item.send.length == 1) {
                        sendChar(item.send[0], ctrlActive, altActive, cb.onSendBytes)
                    } else {
                        cb.onSendBytes(bytes)
                    }
                    if (ctrlActive) cb.onToggleCtrl()
                    if (altActive) cb.onToggleAlt()
                } else {
                    cb.onSendBytes(bytes)
                }
            }
        }
    }
}

@Composable
private fun ToolbarRow(
    items: List<ToolbarItem>,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onVncTap: (() -> Unit)? = null,
    selectionToggleVisible: Boolean = false,
    selectionEnabled: Boolean = true,
    onSelectionEnabledChange: (Boolean) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) {
            RenderItem(item, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view)
            if (item is ToolbarItem.BuiltIn && item.key == ToolbarKey.KEYBOARD) {
                ToolbarLeadingAccessory(
                    onVncTap = onVncTap,
                    selectionToggleVisible = selectionToggleVisible,
                    selectionEnabled = selectionEnabled,
                    onSelectionEnabledChange = onSelectionEnabledChange,
                )
            }
        }
    }
}

@Composable
private fun ToolbarLeadingAccessory(
    onVncTap: (() -> Unit)?,
    selectionToggleVisible: Boolean,
    selectionEnabled: Boolean,
    onSelectionEnabledChange: (Boolean) -> Unit,
) {
    when {
        selectionToggleVisible -> {
            ToolbarCheckbox(
                checked = selectionEnabled,
                description = if (selectionEnabled) {
                    "Disable text selection"
                } else {
                    "Enable text selection"
                },
                onCheckedChange = onSelectionEnabledChange,
            )
        }
        onVncTap != null -> {
            ToolbarIconButton(Icons.Filled.DesktopWindows, "VNC Desktop", onVncTap)
        }
    }
}

@Composable
private fun BuiltInKey(
    key: ToolbarKey,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
) {
    val cb = LocalToolbarCallbacks.current
    when (key) {
        ToolbarKey.KEYBOARD -> {
            ToolbarIconButton(Icons.Filled.Keyboard, "Toggle keyboard") {
                val window = (view.context as? Activity)?.window ?: return@ToolbarIconButton
                val controller = WindowCompat.getInsetsController(window, view)
                if (imeVisible) {
                    controller.hide(WindowInsetsCompat.Type.ime())
                } else {
                    focusRequester.requestFocus()
                    controller.show(WindowInsetsCompat.Type.ime())
                }
            }
        }
        ToolbarKey.ESC_KEY -> ToolbarTextButton("Esc") { cb.onSendBytes(KEY_ESC) }
        ToolbarKey.TAB_KEY -> ToolbarTextButton("Tab") {
            if (shiftActive) {
                cb.onSendBytes(KEY_SHIFT_TAB)
                cb.onShiftUsed()
            } else {
                cb.onSendBytes(KEY_TAB)
            }
        }
        ToolbarKey.SHIFT -> ToolbarToggleButton("Shift", shiftActive, onClick = cb.onToggleShift)
        ToolbarKey.CTRL -> ToolbarToggleButton("Ctrl", ctrlActive, onClick = cb.onToggleCtrl)
        ToolbarKey.ALT -> ToolbarToggleButton("Alt", altActive, onClick = cb.onToggleAlt)
        ToolbarKey.ALTGR -> {
            val altGrActive = ctrlActive && altActive
            ToolbarToggleButton("AltGr", altGrActive) {
                if (altGrActive) {
                    cb.onToggleCtrl()
                    cb.onToggleAlt()
                } else {
                    if (!ctrlActive) cb.onToggleCtrl()
                    if (!altActive) cb.onToggleAlt()
                }
            }
        }
        // Nav keys — routed through dispatchKey for DECCKM support
        ToolbarKey.ARROW_LEFT -> ToolbarArrowButton("\u2190") { cb.onDispatchKey(0, VTERM_KEY_LEFT) }
        ToolbarKey.ARROW_UP -> ToolbarArrowButton("\u2191") { cb.onDispatchKey(0, VTERM_KEY_UP) }
        ToolbarKey.ARROW_DOWN -> ToolbarArrowButton("\u2193") { cb.onDispatchKey(0, VTERM_KEY_DOWN) }
        ToolbarKey.ARROW_RIGHT -> ToolbarArrowButton("\u2192") { cb.onDispatchKey(0, VTERM_KEY_RIGHT) }
        ToolbarKey.HOME -> ToolbarTextButton("Home") { cb.onDispatchKey(0, VTERM_KEY_HOME) }
        ToolbarKey.END -> ToolbarTextButton("End") { cb.onDispatchKey(0, VTERM_KEY_END) }
        ToolbarKey.PGUP -> ToolbarTextButton("PgUp") { cb.onDispatchKey(0, VTERM_KEY_PAGEUP) }
        ToolbarKey.PGDN -> ToolbarTextButton("PgDn") { cb.onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
        // F-keys — routed through dispatchKey so libvterm generates correct sequences
        ToolbarKey.F1 -> ToolbarTextButton("F1") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 1) }
        ToolbarKey.F2 -> ToolbarTextButton("F2") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 2) }
        ToolbarKey.F3 -> ToolbarTextButton("F3") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 3) }
        ToolbarKey.F4 -> ToolbarTextButton("F4") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 4) }
        ToolbarKey.F5 -> ToolbarTextButton("F5") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 5) }
        ToolbarKey.F6 -> ToolbarTextButton("F6") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 6) }
        ToolbarKey.F7 -> ToolbarTextButton("F7") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 7) }
        ToolbarKey.F8 -> ToolbarTextButton("F8") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 8) }
        ToolbarKey.F9 -> ToolbarTextButton("F9") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 9) }
        ToolbarKey.F10 -> ToolbarTextButton("F10") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 10) }
        ToolbarKey.F11 -> ToolbarTextButton("F11") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 11) }
        ToolbarKey.F12 -> ToolbarTextButton("F12") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 12) }
        else -> {
            val ch = key.char ?: return
            SymbolButton(key.label) {
                sendChar(ch, ctrlActive, altActive, cb.onSendBytes)
                if (ctrlActive) cb.onToggleCtrl()
                if (altActive) cb.onToggleAlt()
            }
        }
    }
}

private fun sendChar(
    char: Char,
    ctrl: Boolean,
    alt: Boolean,
    onSendBytes: (ByteArray) -> Unit,
) {
    val byte = if (ctrl && char.code in 0x40..0x7F) {
        byteArrayOf((char.code and 0x1F).toByte())
    } else {
        char.toString().toByteArray()
    }

    if (alt) {
        onSendBytes(byteArrayOf(0x1b) + byte)
    } else {
        onSendBytes(byte)
    }
}

// --- Nav block buttons (fixed width) ---

/** Nav cell wrapper — ensures buttons and spacers occupy the exact same width. */
@Composable
private fun NavCell(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.width(NAV_CELL_WIDTH).height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun NavArrowButton(label: String, onClick: () -> Unit) {
    NavCell {
        RepeatingButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(
                label,
                fontSize = 16.sp,
                lineHeight = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun NavTextButton(label: String, onClick: () -> Unit) {
    NavCell {
        RepeatingButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(label, fontSize = 11.sp, lineHeight = 11.sp)
        }
    }
}

// --- Key repeat ---

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 80L

/**
 * FilledTonalButton with key repeat. Uses Android MotionEvent interop to detect
 * press/release, bypassing Compose's gesture system (which the horizontalScroll
 * parent intercepts). Consumes touch events and handles both tap and repeat.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RepeatingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    content: @Composable () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    var didRepeat by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            didRepeat = false
            delay(REPEAT_DELAY_MS)
            didRepeat = true
            while (true) {
                onClick()
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    FilledTonalButton(
        onClick = {}, // handled by pointerInteropFilter
        modifier = modifier.pointerInteropFilter { motionEvent ->
            when (motionEvent.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    true // consume to receive UP
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!didRepeat) onClick() // single tap
                    isPressed = false
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    true
                }
                else -> false
            }
        },
        contentPadding = contentPadding,
    ) {
        content()
    }
}

// --- Standard buttons (variable width) ---

@Composable
private fun ToolbarArrowButton(label: String, onClick: () -> Unit) {
    RepeatingButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp),
    ) {
        Text(
            label,
            fontSize = 16.sp,
            lineHeight = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}

@Composable
private fun ToolbarTextButton(label: String, onClick: () -> Unit) {
    RepeatingButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp),
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun ToolbarToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = if (active) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun SymbolButton(label: String, onClick: () -> Unit) {
    RepeatingButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(30.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 12.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun ToolbarCheckbox(
    checked: Boolean,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .size(32.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ToolbarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}
