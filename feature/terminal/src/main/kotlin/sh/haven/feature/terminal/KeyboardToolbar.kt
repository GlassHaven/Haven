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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
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
    selectionController: SelectionController? = null,
    selectionActive: Boolean = false,
    hyperlinkUri: String? = null,
    onPaste: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var shiftActive by remember { mutableStateOf(false) }
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        if (selectionActive && selectionController != null) {
            // Selection mode: show selection controls, optionally with row 1.
            Column {
                if (layout.rows.size >= 2) {
                    // Keep row 1 visible so height stays at 2 rows
                    ToolbarRow(
                        items = layout.row1,
                        onSendBytes = onSendBytes,
                        focusRequester = focusRequester,
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        shiftActive = shiftActive,
                        imeVisible = imeVisible,
                        view = view,
                        onToggleCtrl = onToggleCtrl,
                        onToggleAlt = onToggleAlt,
                        onToggleShift = { shiftActive = !shiftActive },
                        onShiftUsed = { shiftActive = false },
                        onVncTap = onVncTap,
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
            // Two-row layout: use aligned nav block grid
            AlignedToolbarContent(
                layout = layout,
                onSendBytes = onSendBytes,
                onDispatchKey = onDispatchKey,
                focusRequester = focusRequester,
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                imeVisible = imeVisible,
                view = view,
                onToggleCtrl = onToggleCtrl,
                onToggleAlt = onToggleAlt,
                onToggleShift = { shiftActive = !shiftActive },
                onShiftUsed = { shiftActive = false },
                onVncTap = onVncTap,
            )
        } else {
            // Single-row fallback
            Column {
                for (row in layout.rows) {
                    if (row.isNotEmpty()) {
                        ToolbarRow(
                            items = row,
                            onSendBytes = onSendBytes,
                            focusRequester = focusRequester,
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            shiftActive = shiftActive,
                            imeVisible = imeVisible,
                            view = view,
                            onToggleCtrl = onToggleCtrl,
                            onToggleAlt = onToggleAlt,
                            onToggleShift = { shiftActive = !shiftActive },
                            onShiftUsed = { shiftActive = false },
                            onVncTap = onVncTap,
                        )
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
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (Int, Int) -> Unit = { _, _ -> },
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onShiftUsed: () -> Unit,
    onVncTap: (() -> Unit)?,
) {
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
            ToolbarRow(layout.row1, onSendBytes, onDispatchKey, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view, onToggleCtrl, onToggleAlt, onToggleShift, onShiftUsed, onVncTap)
            ToolbarRow(layout.row2, onSendBytes, onDispatchKey, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view, onToggleCtrl, onToggleAlt, onToggleShift, onShiftUsed, onVncTap)
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
                    RenderItem(item, onSendBytes, onDispatchKey, focusRequester, ctrlActive, altActive,
                        shiftActive, imeVisible, view, onToggleCtrl, onToggleAlt, onToggleShift, onShiftUsed)
                }
            }
            KeyRow(Modifier.fillMaxWidth()) {
                // VNC Desktop icon at start of row 2
                if (onVncTap != null) {
                    ToolbarIconButton(Icons.Filled.DesktopWindows, "VNC Desktop", onVncTap)
                }
                for (item in row2Left) {
                    RenderItem(item, onSendBytes, onDispatchKey, focusRequester, ctrlActive, altActive,
                        shiftActive, imeVisible, view, onToggleCtrl, onToggleAlt, onToggleShift, onShiftUsed)
                }
            }
        }

        // Nav block grid — fixed-width cells ensure vertical alignment
        Column {
            KeyRow {
                for (key in NAV_GRID_TOP) {
                    if (key != null && key in presentNavKeys) {
                        NavBuiltInKey(key, onSendBytes, onDispatchKey, shiftActive, onShiftUsed)
                    } else {
                        NavCell {}
                    }
                }
            }
            KeyRow {
                for (key in NAV_GRID_BOTTOM) {
                    if (key != null && key in presentNavKeys) {
                        NavBuiltInKey(key, onSendBytes, onDispatchKey, shiftActive, onShiftUsed)
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
                            RenderItem(item, onSendBytes, onDispatchKey, focusRequester, ctrlActive, altActive,
                                shiftActive, imeVisible, view, onToggleCtrl, onToggleAlt, onToggleShift, onShiftUsed)
                        }
                    }
                } else {
                    Spacer(Modifier.height(34.dp))
                }
                KeyRow {
                    for (item in row2Right) {
                        RenderItem(item, onSendBytes, onDispatchKey, focusRequester, ctrlActive, altActive,
                            shiftActive, imeVisible, view, onToggleCtrl, onToggleAlt, onToggleShift, onShiftUsed)
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
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (Int, Int) -> Unit,
    shiftActive: Boolean,
    onShiftUsed: () -> Unit,
) {
    // Arrow and nav keys go through dispatchKey so libvterm applies
    // DECCKM (application cursor mode) — needed for Mutt, vim, etc.
    when (key) {
        ToolbarKey.ARROW_LEFT -> NavArrowButton("\u2190") { onDispatchKey(0, VTERM_KEY_LEFT) }
        ToolbarKey.ARROW_UP -> NavArrowButton("\u2191") { onDispatchKey(0, VTERM_KEY_UP) }
        ToolbarKey.ARROW_DOWN -> NavArrowButton("\u2193") { onDispatchKey(0, VTERM_KEY_DOWN) }
        ToolbarKey.ARROW_RIGHT -> NavArrowButton("\u2192") { onDispatchKey(0, VTERM_KEY_RIGHT) }
        ToolbarKey.HOME -> NavTextButton("Home") { onDispatchKey(0, VTERM_KEY_HOME) }
        ToolbarKey.END -> NavTextButton("End") { onDispatchKey(0, VTERM_KEY_END) }
        ToolbarKey.PGUP -> NavTextButton("PgUp") { onDispatchKey(0, VTERM_KEY_PAGEUP) }
        ToolbarKey.PGDN -> NavTextButton("PgDn") { onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
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

/** Render any toolbar item (non-nav keys in the left/right sections). */
@Composable
private fun RenderItem(
    item: ToolbarItem,
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (Int, Int) -> Unit = { _, _ -> },
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onShiftUsed: () -> Unit,
) {
    when (item) {
        is ToolbarItem.BuiltIn -> BuiltInKey(
            key = item.key,
            onSendBytes = onSendBytes,
            onDispatchKey = onDispatchKey,
            focusRequester = focusRequester,
            ctrlActive = ctrlActive,
            altActive = altActive,
            shiftActive = shiftActive,
            imeVisible = imeVisible,
            view = view,
            onToggleCtrl = onToggleCtrl,
            onToggleAlt = onToggleAlt,
            onToggleShift = onToggleShift,
            onShiftUsed = onShiftUsed,
        )
        is ToolbarItem.Custom -> {
            SymbolButton(item.label) {
                val bytes = item.send.toByteArray()
                if (ctrlActive || altActive) {
                    if (item.send.length == 1) {
                        sendChar(item.send[0], ctrlActive, altActive, onSendBytes)
                    } else {
                        onSendBytes(bytes)
                    }
                    if (ctrlActive) onToggleCtrl()
                    if (altActive) onToggleAlt()
                } else {
                    onSendBytes(bytes)
                }
            }
        }
    }
}

@Composable
private fun ToolbarRow(
    items: List<ToolbarItem>,
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (Int, Int) -> Unit = { _, _ -> },
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onShiftUsed: () -> Unit,
    onVncTap: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) {
            RenderItem(item, onSendBytes, onDispatchKey, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view, onToggleCtrl, onToggleAlt, onToggleShift, onShiftUsed)
            if (item is ToolbarItem.BuiltIn && item.key == ToolbarKey.KEYBOARD && onVncTap != null) {
                ToolbarIconButton(Icons.Filled.DesktopWindows, "VNC Desktop", onVncTap)
            }
        }
    }
}

@Composable
private fun BuiltInKey(
    key: ToolbarKey,
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (Int, Int) -> Unit = { _, _ -> },
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onShiftUsed: () -> Unit,
) {
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
        ToolbarKey.ESC_KEY -> ToolbarTextButton("Esc") { onSendBytes(KEY_ESC) }
        ToolbarKey.TAB_KEY -> ToolbarTextButton("Tab") {
            if (shiftActive) {
                onSendBytes(KEY_SHIFT_TAB)
                onShiftUsed()
            } else {
                onSendBytes(KEY_TAB)
            }
        }
        ToolbarKey.SHIFT -> ToolbarToggleButton("Shift", shiftActive, onClick = onToggleShift)
        ToolbarKey.CTRL -> ToolbarToggleButton("Ctrl", ctrlActive, onClick = onToggleCtrl)
        ToolbarKey.ALT -> ToolbarToggleButton("Alt", altActive, onClick = onToggleAlt)
        ToolbarKey.ALTGR -> {
            val altGrActive = ctrlActive && altActive
            ToolbarToggleButton("AltGr", altGrActive) {
                if (altGrActive) {
                    onToggleCtrl()
                    onToggleAlt()
                } else {
                    if (!ctrlActive) onToggleCtrl()
                    if (!altActive) onToggleAlt()
                }
            }
        }
        // Nav keys — routed through dispatchKey for DECCKM support
        ToolbarKey.ARROW_LEFT -> ToolbarArrowButton("\u2190") { onDispatchKey(0, VTERM_KEY_LEFT) }
        ToolbarKey.ARROW_UP -> ToolbarArrowButton("\u2191") { onDispatchKey(0, VTERM_KEY_UP) }
        ToolbarKey.ARROW_DOWN -> ToolbarArrowButton("\u2193") { onDispatchKey(0, VTERM_KEY_DOWN) }
        ToolbarKey.ARROW_RIGHT -> ToolbarArrowButton("\u2192") { onDispatchKey(0, VTERM_KEY_RIGHT) }
        ToolbarKey.HOME -> ToolbarTextButton("Home") { onDispatchKey(0, VTERM_KEY_HOME) }
        ToolbarKey.END -> ToolbarTextButton("End") { onDispatchKey(0, VTERM_KEY_END) }
        ToolbarKey.PGUP -> ToolbarTextButton("PgUp") { onDispatchKey(0, VTERM_KEY_PAGEUP) }
        ToolbarKey.PGDN -> ToolbarTextButton("PgDn") { onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
        else -> {
            val ch = key.char ?: return
            SymbolButton(key.label) {
                sendChar(ch, ctrlActive, altActive, onSendBytes)
                if (ctrlActive) onToggleCtrl()
                if (altActive) onToggleAlt()
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
private fun ToolbarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}
