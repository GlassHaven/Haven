package sh.haven.core.toolbar

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.connectbot.terminal.SelectionController
import sh.haven.core.data.preferences.EditModeControlsPlacement
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarKey
import kotlinx.coroutines.delay
import sh.haven.core.data.preferences.ToolbarLayout
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import sh.haven.core.data.preferences.MACRO_PRESETS
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

// VT100/xterm escape sequences for special keys
private const val ESC = "\u001b"
private val KEY_ESC = byteArrayOf(0x1b)
private val KEY_TAB = byteArrayOf(0x09)
// Carriage return — what the physical Enter key sends; the tty line
// discipline maps it to newline. Lets the user submit a command (e.g. after
// recalling it with the Up-arrow key) without raising the soft keyboard (#184).
private val KEY_ENTER = byteArrayOf(0x0d)
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
    val bracketPasteMode: Boolean = false,
    val clipboardManager: ClipboardManager? = null,
    val onPaste: (String) -> Unit = {},
    val onEnterReorderMode: () -> Unit = {},
    val onAddCustomKey: (ToolbarItem.Custom) -> Unit = {},
    val snippets: List<ToolbarItem.Custom> = emptyList(),
    val onSnippetTap: (ToolbarItem.Custom) -> Unit = {},
    val onAddSnippet: (ToolbarItem.Custom) -> Unit = {},
    val onDeleteSnippet: (ToolbarItem.Custom) -> Unit = {},
    /**
     * Current state of the "standard keyboard" preference — when true the
     * terminal's IME runs in full-featured mode (voice input, swipe,
     * autocomplete); when false it's the secure/terminal mode with no
     * suggestions. Drives the visual on/off tint of the Voice toolbar key.
     */
    val allowStandardKeyboard: Boolean = false,
    /** Flip [allowStandardKeyboard]. Usually persisted to preferences. */
    val onToggleStandardKeyboard: () -> Unit = {},
    /**
     * #524: swipe-arrows mode — while on, a vertical swipe sends ↑/↓ even at
     * a plain shell prompt instead of only on the alternate screen. Latched
     * and persisted (its point is to free the arrow-key slots), so it renders
     * as an on/off tint, not a one-shot modifier.
     */
    val swipeArrowsActive: Boolean = false,
    /** Flip [swipeArrowsActive]. Persisted to preferences by the host. */
    val onToggleSwipeArrows: () -> Unit = {},
    /**
     * Raw keyboard mode — when true the terminal returns no InputConnection
     * at all, so Gboard has nothing to decorate and its mic, suggestion
     * strip and AI Core writing assist cannot appear. Drives the Raw
     * toolbar key's on/off tint. Mutually exclusive with
     * [allowStandardKeyboard].
     */
    val rawKeyboardMode: Boolean = false,
    /** Flip [rawKeyboardMode]. */
    val onToggleRawKeyboard: () -> Unit = {},
    /**
     * Whether termlib's local compose mode is active for the current tab.
     * Compose mode buffers typed text (CJK / accents) in an overlay at the
     * cursor and commits it on Enter; while it's on the IME also gets a
     * composition-friendly InputConnection. Drives the Compose key's tint.
     */
    val composeModeActive: Boolean = false,
    /** Toggle compose mode on the active tab's ComposeController. */
    val onToggleComposeMode: () -> Unit = {},
    /**
     * Tap on the paperclip / attach key. Opens the source picker (SAF) so the
     * user can send a local file into the active session and inject a usable
     * reference (path or share URL) at the cursor.
     */
    val onAttachTap: () -> Unit = {},
    /**
     * Tap on the floating text-input key. Opens the draggable/resizable
     * text-entry dialog over the terminal so a full line can be composed
     * with the normal IME and sent to the session in one shot.
     */
    val onOpenTextInput: () -> Unit = {},
    /**
     * Tap on the Dictate (microphone) key (#604). Launches the system speech
     * recognizer directly — unlike the Voice lock toggle, this does not depend
     * on the active IME having a mic, so it works on devices that force their
     * own keyboard (Supernote + Futo, etc.).
     */
    val onDictateTap: () -> Unit = {},
)

val LocalToolbarCallbacks = compositionLocalOf<ToolbarCallbacks> {
    error("ToolbarCallbacks not provided")
}

/** Default minimum width for a toolbar key (0 = hug content; overridable via the Haven setting). */
val DEFAULT_MIN_KEY_WIDTH = 0.dp

/**
 * Minimum width every toolbar key is stretched to, threaded from the user's
 * "minimum key width" setting via [KeyboardToolbar]. Read by the shared
 * [ToolbarKeyButton] primitive so every key honours it uniformly.
 */
private val LocalToolbarMinKeyWidth = compositionLocalOf { DEFAULT_MIN_KEY_WIDTH }

/**
 * True inside the uniform-grid layout (#372): keys fill their fixed cell
 * instead of sizing to content, so every key is the same width and the whole
 * cell is the tap target (Termux-style).
 */
private val LocalToolbarFillCell = compositionLocalOf { false }

/** Which modifiers are locked on rather than armed for one keystroke (#522). */
internal data class LockedModifiers(val ctrl: Boolean = false, val alt: Boolean = false)

/**
 * Read by the Ctrl/Alt keys so a lock looks different from a one-shot tap —
 * both are "active", but only one survives the next keystroke, and a modifier
 * that silently outlives its keypress is the confusing state to be stuck in.
 */
private val LocalToolbarLockedModifiers = compositionLocalOf { LockedModifiers() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardToolbar(
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (modifiers: Int, key: Int) -> Unit = { _, _ -> },
    focusRequester: FocusRequester,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    ctrlLocked: Boolean = false,
    altLocked: Boolean = false,
    bracketPasteMode: Boolean = false,
    layout: ToolbarLayout = ToolbarLayout.DEFAULT,
    navBlockMode: sh.haven.core.data.preferences.NavBlockMode = sh.haven.core.data.preferences.NavBlockMode.ALIGNED,
    uniformGrid: Boolean = false,
    editModeControlsPlacement: sh.haven.core.data.preferences.EditModeControlsPlacement = sh.haven.core.data.preferences.EditModeControlsPlacement.LEFT,
    desktopKeyPlacement: sh.haven.core.data.preferences.DesktopKeyPlacement = sh.haven.core.data.preferences.DesktopKeyPlacement.LEFT,
    minKeyWidth: Dp = DEFAULT_MIN_KEY_WIDTH,
    onToggleCtrl: () -> Unit = {},
    onToggleAlt: () -> Unit = {},
    onVncTap: (() -> Unit)? = null,
    vncLoading: Boolean = false,
    selectionController: SelectionController? = null,
    selectionActive: Boolean = false,
    hyperlinkUri: String? = null,
    onPaste: (String) -> Unit = {},
    reorderMode: Boolean = false,
    onReorderModeChanged: (Boolean) -> Unit = {},
    onToolbarLayoutChanged: (ToolbarLayout) -> Unit = {},
    snippetLibrary: List<ToolbarItem.Custom> = emptyList(),
    onSnippetLibraryChanged: (List<ToolbarItem.Custom>) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    selectionContent: (@Composable () -> Unit)? = null,
    allowStandardKeyboard: Boolean = false,
    onToggleStandardKeyboard: () -> Unit = {},
    rawKeyboardMode: Boolean = false,
    onToggleRawKeyboard: () -> Unit = {},
    composeModeActive: Boolean = false,
    onToggleComposeMode: () -> Unit = {},
    swipeArrowsActive: Boolean = false,
    onToggleSwipeArrows: () -> Unit = {},
    onAttachTap: () -> Unit = {},
    onOpenTextInput: () -> Unit = {},
    onDictateTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var shiftActive by remember { mutableStateOf(false) }
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible

    val clipboardManager = remember {
        view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    // Scissors-sheet list = snippets placed on the toolbar PLUS the off-toolbar
    // library (#244), so a snippet toggled "Off" in settings is still reachable.
    val allSnippets = remember(layout, snippetLibrary) {
        sh.haven.core.data.preferences.SnippetOps.allSnippets(layout, snippetLibrary)
    }

    val callbacks = ToolbarCallbacks(
        onSendBytes = onSendBytes,
        onDispatchKey = onDispatchKey,
        onToggleCtrl = onToggleCtrl,
        onToggleAlt = onToggleAlt,
        onToggleShift = { shiftActive = !shiftActive },
        onShiftUsed = { shiftActive = false },
        bracketPasteMode = bracketPasteMode,
        clipboardManager = clipboardManager,
        onPaste = onPaste,
        onEnterReorderMode = { onReorderModeChanged(true) },
        onAddCustomKey = { customKey ->
            // Add to last row and persist
            val newRows = layout.rows.toMutableList()
            if (newRows.isNotEmpty()) {
                val targetRow = newRows.last().toMutableList()
                targetRow.add(customKey)
                newRows[newRows.lastIndex] = targetRow
            }
            onToolbarLayoutChanged(ToolbarLayout(newRows))
        },
        snippets = allSnippets,
        onSnippetTap = { snippet ->
            if (snippet.send == "PASTE") {
                val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
                if (text != null) {
                    if (bracketPasteMode) {
                        onSendBytes(("\u001b[200~$text\u001b[201~").toByteArray())
                    } else {
                        onSendBytes(text.toByteArray())
                    }
                }
            } else {
                onSendBytes(snippet.send.toByteArray())
            }
        },
        allowStandardKeyboard = allowStandardKeyboard,
        onToggleStandardKeyboard = onToggleStandardKeyboard,
        rawKeyboardMode = rawKeyboardMode,
        onToggleRawKeyboard = onToggleRawKeyboard,
        composeModeActive = composeModeActive,
        onToggleComposeMode = onToggleComposeMode,
        swipeArrowsActive = swipeArrowsActive,
        onToggleSwipeArrows = onToggleSwipeArrows,
        onAddSnippet = { snippet ->
            // Adding from the scissors sheet creates a library entry (no toolbar
            // button) — the user pins it as a button via toolbar settings if they
            // want one. Stops every added snippet from cluttering the bar (#244).
            onSnippetLibraryChanged(
                sh.haven.core.data.preferences.SnippetOps.addToLibrary(layout, snippetLibrary, snippet),
            )
        },
        onDeleteSnippet = { snippet ->
            val (newLayout, newLibrary) =
                sh.haven.core.data.preferences.SnippetOps.delete(layout, snippetLibrary, snippet)
            onToolbarLayoutChanged(newLayout)
            onSnippetLibraryChanged(newLibrary)
        },
        onAttachTap = onAttachTap,
        onOpenTextInput = onOpenTextInput,
        onDictateTap = onDictateTap,
    )

    CompositionLocalProvider(
        LocalToolbarCallbacks provides callbacks,
        LocalToolbarMinKeyWidth provides minKeyWidth,
        LocalToolbarLockedModifiers provides LockedModifiers(ctrlLocked, altLocked),
    ) {
        Surface(
            tonalElevation = if (reorderMode) 4.dp else 2.dp,
            modifier = modifier.pointerInput(reorderMode) {
                if (reorderMode) return@pointerInput
                detectVerticalDragGestures { _, dragAmount ->
                    val window = (view.context as? android.app.Activity)?.window
                        ?: return@detectVerticalDragGestures
                    val controller = WindowCompat.getInsetsController(window, view)
                    if (dragAmount > 15f) {
                        controller.hide(WindowInsetsCompat.Type.ime())
                    } else if (dragAmount < -15f) {
                        focusRequester.requestFocus()
                        controller.show(WindowInsetsCompat.Type.ime())
                    }
                }
            },
        ) {
            if (reorderMode) {
                ReorderToolbarContent(
                    layout = layout,
                    onSave = onToolbarLayoutChanged,
                    onDone = { onReorderModeChanged(false) },
                    onOpenSettings = onOpenSettings,
                    showVncIcon = onVncTap != null &&
                        desktopKeyPlacement != sh.haven.core.data.preferences.DesktopKeyPlacement.HIDDEN,
                    placement = editModeControlsPlacement,
                )
            } else if (selectionActive && selectionController != null) {
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
                            vncLoading = vncLoading,
                            desktopKeyPlacement = desktopKeyPlacement,
                        )
                    }
                    selectionContent?.invoke()
                }
            } else if (uniformGrid) {
                // #372: Termux-style fixed grid — overrides the nav-block mode
                // (nav keys become ordinary grid cells).
                GridToolbarContent(
                    layout = layout,
                    focusRequester = focusRequester,
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    shiftActive = shiftActive,
                    imeVisible = imeVisible,
                    view = view,
                    onVncTap = onVncTap,
                    vncLoading = vncLoading,
                    desktopKeyPlacement = desktopKeyPlacement,
                )
            } else if (layout.rows.size >= 2 && navBlockMode == sh.haven.core.data.preferences.NavBlockMode.ALIGNED) {
                AlignedToolbarContent(
                    layout = layout,
                    focusRequester = focusRequester,
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    shiftActive = shiftActive,
                    imeVisible = imeVisible,
                    view = view,
                    onVncTap = onVncTap,
                    vncLoading = vncLoading,
                    desktopKeyPlacement = desktopKeyPlacement,
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
                                vncLoading = vncLoading,
                                desktopKeyPlacement = desktopKeyPlacement,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One column cell: stretches its key to the column width (the wider of the two
 * stacked keys) via [Box.propagateMinConstraints], so both keys sharing a column
 * are the same width — not just centred in it (#184 follow-up). A null [content]
 * leaves an aligned 32dp placeholder.
 */
@Composable
private fun KeyCell(content: (@Composable () -> Unit)?) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) {
        if (content != null) content() else Spacer(Modifier.size(32.dp))
    }
}

/** A single toolbar column: the row-1 key stacked over the row-2 key, sized to
 *  the wider of the two so columns line up across both rows. */
@Composable
private fun KeyColumn(top: (@Composable () -> Unit)?, bottom: (@Composable () -> Unit)?) {
    Column(modifier = Modifier.width(IntrinsicSize.Max)) {
        KeyCell(top)
        KeyCell(bottom)
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
    vncLoading: Boolean = false,
    desktopKeyPlacement: sh.haven.core.data.preferences.DesktopKeyPlacement =
        sh.haven.core.data.preferences.DesktopKeyPlacement.LEFT,
) {
    val cb = LocalToolbarCallbacks.current
    // Split each row into: left (non-nav), right (non-nav after nav keys)
    val (row1Left, row1Right) = splitAroundNav(layout.row1)
    val (row2Left, row2Right) = splitAroundNav(layout.row2)

    // Pin the leading icon keys to a fixed 2-column grid so the Attach
    // (row 1) and Voice/secure-keyboard (row 2) icons always stack at
    // column 2 — col 1 holds the keyboard toggle (row 1) / VNC-desktop
    // (row 2), col 2 holds Attach / Voice. Placeholders keep the columns
    // aligned even when one leading icon is absent (e.g. no VNC on a
    // plain SSH profile), which previously slid Voice under column 1.
    fun ToolbarItem.isKey(k: ToolbarKey) = this is ToolbarItem.BuiltIn && this.key == k
    val r1Keyboard = row1Left.firstOrNull { it.isKey(ToolbarKey.KEYBOARD) }
    val r1Attach = row1Left.firstOrNull { it.isKey(ToolbarKey.ATTACH) }
    val r2Voice = row2Left.firstOrNull { it.isKey(ToolbarKey.VOICE_KEYBOARD) }
    // Pin Attach (row 1) over Voice (row 2) into the fixed leading column ONLY
    // when both are present. If one is disabled, the survivor reflows into its
    // row's normal keys instead of being stranded in a paired column with an
    // empty cell next to it; when both are off the column isn't drawn at all.
    // So turning a key off actually gives its space back rather than leaving a
    // dead box (#245).
    val pinAttachVoice = r1Attach != null && r2Voice != null
    val r1Rest = row1Left.filterNot {
        it.isKey(ToolbarKey.KEYBOARD) || (pinAttachVoice && it.isKey(ToolbarKey.ATTACH))
    }
    val r2Rest = row2Left.filterNot { pinAttachVoice && it.isKey(ToolbarKey.VOICE_KEYBOARD) }

    // Collect which nav keys are present across all rows
    val presentNavKeys = layout.rows.flatten()
        .filterIsInstance<ToolbarItem.BuiltIn>()
        .filter { it.key in NAV_KEYS }
        .map { it.key }
        .toSet()

    // If no nav keys present, fall back to simple rows
    if (presentNavKeys.isEmpty()) {
        Column {
            ToolbarRow(layout.row1, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view, onVncTap, vncLoading)
            ToolbarRow(layout.row2, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view, onVncTap, vncLoading)
        }
        return
    }

    // All three columns scroll together for alignment
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        // Left keys, column-aligned via the file-level KeyColumn/KeyCell helpers:
        // each column stacks its row-1 key over its row-2 key in a Column sized to
        // the wider of the two (IntrinsicSize.Max), so the two rows line up in tidy
        // columns at minimum width — no 58dp pills, no misaligned rows (#184).
        fun itemRenderer(item: ToolbarItem?): (@Composable () -> Unit)? = item?.let {
            { RenderItem(it, focusRequester, ctrlActive, altActive, shiftActive, imeVisible, view) }
        }
        // Nav (cursor) keys rendered through the same column grid as the rest,
        // so the cursor block lines up vertically with the other rows instead of
        // sitting in its own fixed-cell grid. Arrows/keys dispatch via
        // onDispatchKey so libvterm applies DECCKM (vim/mutt). (#184 follow-up)
        fun navRenderer(key: ToolbarKey): (@Composable () -> Unit)? {
            if (key !in presentNavKeys) return null
            return {
                val cb = LocalToolbarCallbacks.current
                when (key) {
                    ToolbarKey.ARROW_LEFT -> ToolbarArrowButton("←") { cb.onDispatchKey(0, VTERM_KEY_LEFT) }
                    ToolbarKey.ARROW_UP -> ToolbarArrowButton("↑") { cb.onDispatchKey(0, VTERM_KEY_UP) }
                    ToolbarKey.ARROW_DOWN -> ToolbarArrowButton("↓") { cb.onDispatchKey(0, VTERM_KEY_DOWN) }
                    ToolbarKey.ARROW_RIGHT -> ToolbarArrowButton("→") { cb.onDispatchKey(0, VTERM_KEY_RIGHT) }
                    ToolbarKey.HOME -> ToolbarIconNavButton(Icons.Filled.FirstPage, "Home") { cb.onDispatchKey(0, VTERM_KEY_HOME) }
                    ToolbarKey.END -> ToolbarIconNavButton(Icons.Filled.LastPage, "End") { cb.onDispatchKey(0, VTERM_KEY_END) }
                    ToolbarKey.PGUP -> ToolbarTextButton("PgUp") { cb.onDispatchKey(0, VTERM_KEY_PAGEUP) }
                    ToolbarKey.PGDN -> ToolbarTextButton("PgDn") { cb.onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
                    else -> {}
                }
            }
        }

        // The auto-shown desktop (VNC/RDP) key. Placed on the leading edge (LEFT,
        // under the keyboard toggle), the trailing edge (RIGHT), or hidden (#245).
        val showDesktop = onVncTap != null &&
            desktopKeyPlacement != sh.haven.core.data.preferences.DesktopKeyPlacement.HIDDEN
        val desktopRenderer: (@Composable () -> Unit)? = if (showDesktop) {
            {
                if (vncLoading) {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    ToolbarIconButton(Icons.Filled.DesktopWindows, stringResource(R.string.toolbar_vnc_desktop), onVncTap!!)
                }
            }
        } else {
            null
        }
        val desktopOnLeft = desktopRenderer
            ?.takeIf { desktopKeyPlacement == sh.haven.core.data.preferences.DesktopKeyPlacement.LEFT }

        // Col 0: keyboard toggle (top) / desktop icon (bottom, when LEFT). Skip the
        // whole column when neither is present so it leaves no empty box (#245).
        if (r1Keyboard != null || desktopOnLeft != null) {
            KeyColumn(top = itemRenderer(r1Keyboard), bottom = desktopOnLeft)
        }
        // Col 1: Attach (top) / Voice toggle (bottom) — only when both are
        // present; a lone survivor reflows into the rest columns below (#245).
        if (pinAttachVoice) {
            KeyColumn(top = itemRenderer(r1Attach), bottom = itemRenderer(r2Voice))
        }
        // Remaining columns: row-1 key over row-2 key, paired by position.
        val restColumns = maxOf(r1Rest.size, r2Rest.size)
        for (i in 0 until restColumns) {
            KeyColumn(top = itemRenderer(r1Rest.getOrNull(i)), bottom = itemRenderer(r2Rest.getOrNull(i)))
        }

        // Nav (cursor) block — same KeyColumn grid as the keys above, so the two
        // cursor rows line up with the rest. Columns: Home/←, ↑/↓, End/→, PgUp/PgDn.
        for (i in NAV_GRID_TOP.indices) {
            val topKey = NAV_GRID_TOP[i]
            val bottomKey = NAV_GRID_BOTTOM[i]
            if (topKey in presentNavKeys || bottomKey in presentNavKeys) {
                KeyColumn(top = navRenderer(topKey), bottom = navRenderer(bottomKey))
            }
        }
        // Right keys (symbols) — same column grid, so they align with the rest
        // even when present only on row 2.
        val rightColumns = maxOf(row1Right.size, row2Right.size)
        for (i in 0 until rightColumns) {
            KeyColumn(top = itemRenderer(row1Right.getOrNull(i)), bottom = itemRenderer(row2Right.getOrNull(i)))
        }
        // Desktop key on the trailing edge (RIGHT placement) — bottom row, lined
        // up with the other row-2 keys, just before the fixed controls (#245).
        if (desktopRenderer != null &&
            desktopKeyPlacement == sh.haven.core.data.preferences.DesktopKeyPlacement.RIGHT
        ) {
            KeyColumn(top = null, bottom = desktopRenderer)
        }
        Column(modifier = Modifier.align(Alignment.Bottom)) {
            AddKeyButton()
            ReorderEditButton()
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
        ToolbarKey.ARROW_LEFT -> ToolbarArrowButton("\u2190") { cb.onDispatchKey(0, VTERM_KEY_LEFT) }
        ToolbarKey.ARROW_UP -> ToolbarArrowButton("\u2191") { cb.onDispatchKey(0, VTERM_KEY_UP) }
        ToolbarKey.ARROW_DOWN -> ToolbarArrowButton("\u2193") { cb.onDispatchKey(0, VTERM_KEY_DOWN) }
        ToolbarKey.ARROW_RIGHT -> ToolbarArrowButton("\u2192") { cb.onDispatchKey(0, VTERM_KEY_RIGHT) }
        ToolbarKey.HOME -> ToolbarIconNavButton(Icons.Filled.FirstPage, "Home") { cb.onDispatchKey(0, VTERM_KEY_HOME) }
        ToolbarKey.END -> ToolbarIconNavButton(Icons.Filled.LastPage, "End") { cb.onDispatchKey(0, VTERM_KEY_END) }
        ToolbarKey.PGUP -> ToolbarTextButton("PgUp") { cb.onDispatchKey(0, VTERM_KEY_PAGEUP) }
        ToolbarKey.PGDN -> ToolbarTextButton("PgDn") { cb.onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
        else -> Spacer(Modifier.width(NAV_CELL_WIDTH))
    }
}

// VTermKey constants (from libvterm/include/vterm_keycodes.h)
private const val VTERM_KEY_UP = 5
private const val VTERM_KEY_DOWN = 6
private const val VTERM_KEY_LEFT = 7
private const val VTERM_KEY_RIGHT = 8
private const val VTERM_KEY_INS = 9
private const val VTERM_KEY_DEL = 10
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
            if (item.send == "PASTE") {
                SymbolButton(item.label) {
                    val text = cb.clipboardManager?.primaryClip
                        ?.getItemAt(0)?.text?.toString()
                    if (text != null) {
                        if (cb.bracketPasteMode) {
                            cb.onSendBytes(("\u001b[200~$text\u001b[201~").toByteArray())
                        } else {
                            cb.onSendBytes(text.toByteArray())
                        }
                    }
                }
            } else {
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
    vncLoading: Boolean = false,
    desktopKeyPlacement: sh.haven.core.data.preferences.DesktopKeyPlacement =
        sh.haven.core.data.preferences.DesktopKeyPlacement.LEFT,
) {
    val showDesktop = onVncTap != null &&
        desktopKeyPlacement != sh.haven.core.data.preferences.DesktopKeyPlacement.HIDDEN
    val desktopKey: @Composable () -> Unit = {
        if (vncLoading) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else {
            ToolbarIconButton(Icons.Filled.DesktopWindows, stringResource(R.string.toolbar_vnc_desktop), onVncTap!!)
        }
    }
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) {
            RenderItem(item, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view)
            // LEFT placement: desktop key follows the keyboard toggle.
            if (showDesktop &&
                desktopKeyPlacement == sh.haven.core.data.preferences.DesktopKeyPlacement.LEFT &&
                item is ToolbarItem.BuiltIn && item.key == ToolbarKey.KEYBOARD
            ) {
                desktopKey()
            }
        }
        // RIGHT placement: desktop key sits with the trailing controls (#245).
        if (showDesktop &&
            desktopKeyPlacement == sh.haven.core.data.preferences.DesktopKeyPlacement.RIGHT
        ) {
            desktopKey()
        }
        AddKeyButton()
        ReorderEditButton()
    }
}

/**
 * Termux-style uniform grid (#372): every key occupies an equal-width cell of
 * the full row width — no horizontal scroll — and rows are padded to a common
 * column count so the columns line up. Long labels wrap inside their cell
 * (ToolbarKeyText has no line cap and the cell clamps its width). Item set and
 * ordering mirror [ToolbarRow] exactly: same desktop-key insertion rules, same
 * trailing add/edit controls.
 */
@Composable
private fun GridToolbarContent(
    layout: sh.haven.core.data.preferences.ToolbarLayout,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onVncTap: (() -> Unit)?,
    vncLoading: Boolean,
    desktopKeyPlacement: sh.haven.core.data.preferences.DesktopKeyPlacement,
) {
    val showDesktop = onVncTap != null &&
        desktopKeyPlacement != sh.haven.core.data.preferences.DesktopKeyPlacement.HIDDEN
    val desktopKey: @Composable () -> Unit = {
        if (vncLoading) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else {
            ToolbarIconButton(Icons.Filled.DesktopWindows, stringResource(R.string.toolbar_vnc_desktop), onVncTap!!)
        }
    }
    val visibleRows = layout.rows.filter { it.isNotEmpty() }
    val rows: List<List<@Composable () -> Unit>> = visibleRows
        .mapIndexed { rowIdx, row ->
            buildList {
                for (item in row) {
                    add { RenderItem(item, focusRequester, ctrlActive, altActive, shiftActive, imeVisible, view) }
                    if (showDesktop &&
                        desktopKeyPlacement == sh.haven.core.data.preferences.DesktopKeyPlacement.LEFT &&
                        item is ToolbarItem.BuiltIn && item.key == ToolbarKey.KEYBOARD
                    ) {
                        add(desktopKey)
                    }
                }
                if (showDesktop &&
                    desktopKeyPlacement == sh.haven.core.data.preferences.DesktopKeyPlacement.RIGHT &&
                    rowIdx == 0
                ) {
                    add(desktopKey)
                }
                // Unlike the scrolling rows (which append these to every row,
                // off-screen at the scroll end), grid columns are a hard
                // budget — one add/edit pair on the last row is enough.
                if (rowIdx == visibleRows.lastIndex) {
                    add { AddKeyButton() }
                    add { ReorderEditButton() }
                }
            }
        }
    val maxCols = rows.maxOfOrNull { it.size } ?: return
    CompositionLocalProvider(LocalToolbarFillCell provides true) {
        Column {
            for (cells in rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (cell in cells) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            cell()
                        }
                    }
                    repeat(maxCols - cells.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddKeyButton() {
    val cb = LocalToolbarCallbacks.current
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AddCustomKeyDialog(
            onDismiss = { showDialog = false },
            onSave = { customKey ->
                showDialog = false
                cb.onAddCustomKey(customKey)
            },
        )
    }

    FilledTonalButton(
        onClick = { showDialog = true },
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = stringResource(R.string.toolbar_add_custom_key),
            modifier = Modifier.size(16.dp),
        )
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
        @OptIn(ExperimentalFoundationApi::class)
        ToolbarKey.KEYBOARD -> {
            // Bespoke (not the shared primitive) because it needs combinedClickable
            // for the long-press → reorder gesture, but styled to match the
            // primitive so it sits flush with the other keys.
            Surface(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .height(32.dp)
                    .then(
                        if (LocalToolbarFillCell.current) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.widthIn(min = LocalToolbarMinKeyWidth.current)
                        }
                    )
                    .combinedClickable(
                        onClick = {
                            val window = (view.context as? Activity)?.window ?: return@combinedClickable
                            val controller = WindowCompat.getInsetsController(window, view)
                            if (imeVisible) {
                                controller.hide(WindowInsetsCompat.Type.ime())
                            } else {
                                focusRequester.requestFocus()
                                controller.show(WindowInsetsCompat.Type.ime())
                            }
                        },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            cb.onEnterReorderMode()
                        },
                    ),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolbarKeyIcon(Icons.Filled.Keyboard, stringResource(R.string.toolbar_toggle_keyboard))
                }
            }
        }
        ToolbarKey.ESC_KEY -> ToolbarTextButton("Esc") { cb.onSendBytes(KEY_ESC) }
        // Rendered with the larger, bold glyph style the arrow keys use (16sp
        // vs the 11sp text buttons) so the return symbol is clearly legible (#184).
        ToolbarKey.ENTER_KEY -> ToolbarArrowButton("⏎") { cb.onSendBytes(KEY_ENTER) }
        ToolbarKey.TAB_KEY -> ToolbarTextButton("Tab") {
            if (shiftActive) {
                cb.onSendBytes(KEY_SHIFT_TAB)
                cb.onShiftUsed()
            } else {
                cb.onSendBytes(KEY_TAB)
            }
        }
        ToolbarKey.VOICE_KEYBOARD -> ToolbarIconToggleButton(
            // Padlock icon: closed when Secure (default — no suggestions, no
            // autocorrect, no learning, no extract UI); open when Standard
            // mode is active (voice + suggestions + autocorrect all enabled).
            // The previous "Voice" text label was misleading because voice
            // input is just one of the things the toggle unlocks. (#115)
            icon = if (cb.allowStandardKeyboard) Icons.Filled.LockOpen else Icons.Filled.Lock,
            contentDescription = if (cb.allowStandardKeyboard)
                stringResource(R.string.toolbar_keyboard_standard_desc)
            else
                stringResource(R.string.toolbar_keyboard_secure_desc),
            active = cb.allowStandardKeyboard,
            onClick = cb.onToggleStandardKeyboard,
        )
        ToolbarKey.RAW_KEYBOARD -> ToolbarToggleButton(
            label = "Raw",
            active = cb.rawKeyboardMode,
            onClick = cb.onToggleRawKeyboard,
        )
        ToolbarKey.COMPOSE -> ToolbarToggleButton(
            // "中" — toggles termlib's local compose buffer for CJK / accent
            // input. On = a tinted button + the IME gets a composition-friendly
            // connection; Enter commits the buffered text, which clears the tint.
            label = "中",
            active = cb.composeModeActive,
            onClick = cb.onToggleComposeMode,
        )
        ToolbarKey.PASTE -> ToolbarTextButton("Paste") {
            val text = cb.clipboardManager?.primaryClip
                ?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                if (cb.bracketPasteMode) {
                    cb.onPaste("\u001b[200~$text\u001b[201~")
                } else {
                    cb.onPaste(text)
                }
            }
        }
        ToolbarKey.SNIPPETS -> {
            var showSheet by remember { mutableStateOf(false) }
            ToolbarTextButton("\u2702") { showSheet = true }
            if (showSheet) {
                SnippetsBottomSheet(
                    snippets = cb.snippets,
                    onDismiss = { showSheet = false },
                    onSnippetTap = { snippet ->
                        cb.onSnippetTap(snippet)
                        showSheet = false
                    },
                    onAddSnippet = cb.onAddSnippet,
                    onDeleteSnippet = cb.onDeleteSnippet,
                )
            }
        }
        ToolbarKey.ATTACH -> ToolbarIconButton(
            icon = Icons.Filled.AttachFile,
            description = stringResource(R.string.toolbar_attach_desc),
            onClick = cb.onAttachTap,
        )
        ToolbarKey.TEXT_INPUT -> ToolbarIconButton(
            icon = Icons.Filled.EditNote,
            description = stringResource(R.string.toolbar_text_input_desc),
            onClick = cb.onOpenTextInput,
        )
        ToolbarKey.VOICE_INPUT -> ToolbarIconButton(
            icon = Icons.Filled.Mic,
            description = stringResource(R.string.toolbar_dictate_desc),
            onClick = cb.onDictateTap,
        )
        ToolbarKey.SHIFT -> ToolbarToggleButton("Shift", shiftActive, onClick = cb.onToggleShift)
        ToolbarKey.CTRL -> ToolbarToggleButton(
            "Ctrl",
            ctrlActive,
            locked = LocalToolbarLockedModifiers.current.ctrl,
            onClick = cb.onToggleCtrl,
        )
        ToolbarKey.ALT -> ToolbarToggleButton(
            "Alt",
            altActive,
            locked = LocalToolbarLockedModifiers.current.alt,
            onClick = cb.onToggleAlt,
        )
        ToolbarKey.SWIPE_ARROWS -> ToolbarToggleButton(
            "Swipe",
            cb.swipeArrowsActive,
            onClick = cb.onToggleSwipeArrows,
        )
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
        ToolbarKey.HOME -> ToolbarIconNavButton(Icons.Filled.FirstPage, "Home") { cb.onDispatchKey(0, VTERM_KEY_HOME) }
        ToolbarKey.END -> ToolbarIconNavButton(Icons.Filled.LastPage, "End") { cb.onDispatchKey(0, VTERM_KEY_END) }
        ToolbarKey.PGUP -> ToolbarTextButton("PgUp") { cb.onDispatchKey(0, VTERM_KEY_PAGEUP) }
        ToolbarKey.PGDN -> ToolbarTextButton("PgDn") { cb.onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
        ToolbarKey.INSERT -> ToolbarTextButton("Ins") { cb.onDispatchKey(0, VTERM_KEY_INS) }
        ToolbarKey.DELETE -> ToolbarTextButton("Del") { cb.onDispatchKey(0, VTERM_KEY_DEL) }
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

// --- Key repeat ---

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 80L

/** The new flag state after one touch event, and what the caller owes the key. */
internal data class KeyTouchResult(
    val pressed: Boolean,
    val didRepeat: Boolean,
    val emitClick: Boolean,
    val consumed: Boolean,
)

/**
 * The whole press/release decision for a repeating key, as a pure function of the
 * masked action and the two flags.
 *
 * It lives out here rather than inline in the touch filter because #515 was a
 * latch in exactly this transition table and there was no way to test it: the
 * flags were composable-local, the failing sequence needs multi-touch, and
 * `core:toolbar` had no test source set at all. A pure transition can be driven
 * event by event from a plain JVM test, so the two latches now have a regression
 * each in `KeyTouchTest`.
 *
 * Two properties carry the fix, and both are asserted there:
 *  - a release is a release whichever pointer it belongs to, so ACTION_POINTER_UP
 *    has to clear the flags the same way ACTION_UP does;
 *  - every terminal event returns `didRepeat = false`, so the flag cannot outlive
 *    the touch that set it no matter how composition schedules the repeat effect.
 */
internal fun keyTouch(action: Int, pressed: Boolean, didRepeat: Boolean): KeyTouchResult =
    // Masked here rather than by the caller. Passing the raw `action` in and
    // stripping the pointer index inside is what makes latch 1 unrepeatable: a
    // call site cannot forget a step it does not perform.
    when (action and android.view.MotionEvent.ACTION_MASK) {
        android.view.MotionEvent.ACTION_DOWN,
        android.view.MotionEvent.ACTION_POINTER_DOWN ->
            // Consumed so this node also receives the matching UP.
            KeyTouchResult(pressed = true, didRepeat = didRepeat, emitClick = false, consumed = true)

        android.view.MotionEvent.ACTION_UP,
        android.view.MotionEvent.ACTION_POINTER_UP ->
            // A hold has already delivered its keys; only a plain tap clicks here.
            KeyTouchResult(
                pressed = false,
                didRepeat = false,
                emitClick = !didRepeat,
                consumed = true,
            )

        android.view.MotionEvent.ACTION_CANCEL ->
            KeyTouchResult(pressed = false, didRepeat = false, emitClick = false, consumed = true)

        else -> KeyTouchResult(pressed, didRepeat, emitClick = false, consumed = false)
    }

/**
 * FilledTonalButton with key repeat. Uses Android MotionEvent interop to detect
 * press/release, bypassing Compose's gesture system (which the horizontalScroll
 * parent intercepts). Consumes touch events and handles both tap and repeat.
 */
/**
 * The single toolbar-key primitive every key renders through, so all keys share
 * one height, padding, shape, min-width, selected-styling and repeat behaviour
 * (the previous per-key composables had drifted to 30-vs-32dp, 6-vs-8dp,
 * 58dp Material minimums, etc. — the root of a run of alignment/width bugs).
 *
 * Backed by a tonal [Surface] (not FilledTonalButton, which forces a ~58dp
 * minimum width). Width = content + 8dp padding, floored at [LocalToolbarMinKeyWidth]
 * (the user's "minimum key width" setting). [selected] paints the active toggle
 * state; [repeating] enables press-and-hold key repeat via the Android
 * MotionEvent interop (Compose gestures get stolen by the scrolling parent).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ToolbarKeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    repeating: Boolean = false,
    locked: Boolean = false,
    content: @Composable () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    // True only between the repeat loop starting and the touch ending, so the
    // release handler can tell a tap from the tail of a hold. That handler is
    // its sole owner — see the latch note on the touch filter below.
    var didRepeat by remember { mutableStateOf(false) }
    // The repeat loop can outlive the composition that launched it, and `onClick`
    // closes over the active tab; without this it would go on firing the lambda
    // captured at launch, aimed at a session the user has already left.
    val currentOnClick by rememberUpdatedState(onClick)
    // Aliased so the semantics `onClick` DSL below can invoke the key's action
    // without colliding with the receiver's own `onClick` builder.
    val onClickAction = onClick

    LaunchedEffect(isPressed, repeating) {
        if (isPressed && repeating) {
            delay(REPEAT_DELAY_MS)
            didRepeat = true
            // Guarded on isPressed as well as on the effect's own cancellation,
            // so a release that recomposition has not yet delivered still stops
            // the loop rather than typing into the session.
            while (isPressed) {
                currentOnClick()
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    Surface(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .height(32.dp)
            .then(
                if (LocalToolbarFillCell.current) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(min = LocalToolbarMinKeyWidth.current)
                }
            )
            // Expose the key as an actionable button in the semantics tree. The
            // touch path below is a bare pointerInteropFilter (for press-and-hold
            // repeat), which leaves the key clickable:false — so TalkBack can't
            // activate it and neither can node-based drivers (the MCP self-hosting
            // loop). A merged onClick action makes the key operable both ways
            // without adding a competing gesture detector.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { onClickAction(); true }
            }
            // #515 — two ways this used to latch, both leaving a key that
            // highlights on touch and sends nothing:
            //
            // 1. `actionMasked`, never `action`. Compose hands the callback the
            //    window's original MotionEvent, so a second finger anywhere makes
            //    the release arrive as ACTION_POINTER_UP (0x0106 — the pointer
            //    index is packed into bits 8-15). That never equalled ACTION_UP
            //    (1), so it fell through to `else` and neither flag was cleared.
            // 2. Releasing has to clear `didRepeat`. Resetting it only at the top
            //    of the repeat effect was a race: a DOWN and UP landing in the
            //    same frame leave `isPressed` unchanged as far as composition can
            //    see, so the effect never relaunches and the flag stays true.
            //
            // Either way a latched `didRepeat` makes every later tap hit
            // `if (!didRepeat)` and do nothing. It survived until the session id
            // changed, because the toolbar sits inside key(sessionId) — hence the
            // report that only closing and reopening the session brought the
            // arrow back.
            //
            // The same primitive is copy-pasted into VncScreen and RdpScreen;
            // both were fixed with it. Extracting it needs a core:toolbar
            // dependency those modules do not have yet.
            .pointerInteropFilter { motionEvent ->
                val result = keyTouch(motionEvent.action, isPressed, didRepeat)
                if (result.emitClick) onClick()
                isPressed = result.pressed
                didRepeat = result.didRepeat
                result.consumed
            },
        shape = RoundedCornerShape(8.dp),
        // A locked modifier outlives its keystroke, so it needs to read as more
        // than "active" at a glance — an outline round the already-highlighted
        // key, rather than a second shade nobody can tell from the first.
        border = if (locked) BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary) else null,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = if (LocalToolbarFillCell.current) 2.dp else 8.dp,
            ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** Uniform key label. [glyph] = the larger bold style for arrows/symbols/⏎. */
@Composable
private fun ToolbarKeyText(label: String, glyph: Boolean = false) {
    Text(
        label,
        fontSize = if (glyph) 16.sp else 12.sp,
        lineHeight = if (glyph) 16.sp else 12.sp,
        fontWeight = if (glyph) androidx.compose.ui.text.font.FontWeight.Bold else null,
        // Grid cells (#372) clamp the width, so long labels wrap — keep the
        // wrapped lines centred. No effect on single-line labels.
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** Uniform key icon (18dp) for icon keys. */
@Composable
private fun ToolbarKeyIcon(icon: ImageVector, contentDescription: String? = null) {
    Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
}

/**
 * The icon for keys drawn as an icon rather than text. Used by both the live
 * render and the reorder-mode chips so a key looks identical in either mode.
 * Voice is represented by the secure-state lock. Returns null for text keys.
 */
private fun keyIcon(key: ToolbarKey): ImageVector? = when (key) {
    ToolbarKey.KEYBOARD -> Icons.Filled.Keyboard
    ToolbarKey.ATTACH -> Icons.Filled.AttachFile
    ToolbarKey.TEXT_INPUT -> Icons.Filled.EditNote
    ToolbarKey.VOICE_KEYBOARD -> Icons.Filled.Lock
    ToolbarKey.VOICE_INPUT -> Icons.Filled.Mic
    ToolbarKey.HOME -> Icons.Filled.FirstPage
    ToolbarKey.END -> Icons.Filled.LastPage
    else -> null
}

/**
 * The visual style of a key — icon vs glyph-text vs plain-text — decided once and
 * shared by the live render and reorder/edit mode so a key looks identical in both.
 * The glyph/icon choices mirror the live [RenderItem] leaves verbatim:
 *  - icon keys (Keyboard/Attach/Voice/Home/End) → [keyIcon];
 *  - Enter "⏎" and the arrows → the larger 16sp bold glyph;
 *  - single-char symbols → bold glyph (matches [SymbolButton]'s length<=1 rule);
 *  - Snippets → its "✂" glyph (plain weight, as live);
 *  - everything else (Esc/Tab/Paste/PgUp/Fn/Ins/Del/Shift/Ctrl/Alt/Raw) → 12sp text.
 */
private sealed interface KeyVisual {
    data class IconV(val icon: ImageVector, val desc: String?) : KeyVisual
    data class TextV(val label: String, val glyph: Boolean) : KeyVisual
}

private fun toolbarKeyVisual(item: ToolbarItem): KeyVisual = when (item) {
    is ToolbarItem.Custom -> KeyVisual.TextV(item.label, glyph = item.label.length <= 1)
    is ToolbarItem.BuiltIn -> when (item.key) {
        ToolbarKey.KEYBOARD -> KeyVisual.IconV(Icons.Filled.Keyboard, null)
        ToolbarKey.ATTACH -> KeyVisual.IconV(Icons.Filled.AttachFile, null)
        ToolbarKey.TEXT_INPUT -> KeyVisual.IconV(Icons.Filled.EditNote, null)
        ToolbarKey.VOICE_KEYBOARD -> KeyVisual.IconV(Icons.Filled.Lock, null)
        ToolbarKey.VOICE_INPUT -> KeyVisual.IconV(Icons.Filled.Mic, null)
        ToolbarKey.COMPOSE -> KeyVisual.TextV("中", glyph = true)
        ToolbarKey.HOME -> KeyVisual.IconV(Icons.Filled.FirstPage, "Home")
        ToolbarKey.END -> KeyVisual.IconV(Icons.Filled.LastPage, "End")
        ToolbarKey.ENTER_KEY -> KeyVisual.TextV("⏎", glyph = true) // ⏎
        ToolbarKey.ARROW_LEFT -> KeyVisual.TextV("←", glyph = true)
        ToolbarKey.ARROW_UP -> KeyVisual.TextV("↑", glyph = true)
        ToolbarKey.ARROW_DOWN -> KeyVisual.TextV("↓", glyph = true)
        ToolbarKey.ARROW_RIGHT -> KeyVisual.TextV("→", glyph = true)
        ToolbarKey.SNIPPETS -> KeyVisual.TextV("✂", glyph = false) // ✂
        else -> item.key.char?.let { KeyVisual.TextV(it.toString(), glyph = true) }
            ?: KeyVisual.TextV(item.key.label, glyph = false)
    }
}

@Composable
private fun ToolbarKeyContent(v: KeyVisual) = when (v) {
    is KeyVisual.IconV -> ToolbarKeyIcon(v.icon, v.desc)
    is KeyVisual.TextV -> ToolbarKeyText(v.label, glyph = v.glyph)
}

// --- Standard buttons (variable width) ---

@Composable
private fun ToolbarArrowButton(label: String, onClick: () -> Unit) {
    ToolbarKeyButton(onClick = onClick, repeating = true) {
        ToolbarKeyText(label, glyph = true)
    }
}

/**
 * Icon-rendered nav key (Home/End) with key repeat — same 18dp icon as the
 * other icon keys, so it occupies the same narrow cell width as the arrows and
 * keeps the cursor block's columns symmetric (the "Home" text label was wider
 * than "End", leaving the left column fatter than the right).
 */
@Composable
private fun ToolbarIconNavButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    ToolbarKeyButton(onClick = onClick, repeating = true) {
        ToolbarKeyIcon(icon, description)
    }
}

@Composable
private fun ToolbarTextButton(label: String, onClick: () -> Unit) {
    ToolbarKeyButton(onClick = onClick, repeating = true) {
        ToolbarKeyText(label)
    }
}

@Composable
private fun ToolbarToggleButton(
    label: String,
    active: Boolean,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    ToolbarKeyButton(onClick = onClick, selected = active, locked = locked) {
        ToolbarKeyText(label)
    }
}

/**
 * Icon-based toggle button. Bare icon (no surrounding pill) so it sits
 * flush with the other icon-only keys like Attach (#115 follow-up). The
 * on/off state is communicated via tint: primary when active, the usual
 * onSurfaceVariant when inactive.
 */
@Composable
private fun ToolbarIconToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    ToolbarKeyButton(onClick = onClick, selected = active) {
        ToolbarKeyIcon(icon, contentDescription)
    }
}

@Composable
private fun SymbolButton(label: String, onClick: () -> Unit) {
    // Single-character keys (built-in symbols like | ~) use the larger bold
    // glyph; multi-character labels (custom keys like "⇧Tab", "mcp") use the
    // normal text weight so they match the built-in word keys (Esc/Tab/…).
    ToolbarKeyButton(onClick = onClick, repeating = true) {
        ToolbarKeyText(label, glyph = label.length <= 1)
    }
}

/** Edit button at the end of the toolbar — tapping enters reorder mode. */
@Composable
private fun ReorderEditButton() {
    val cb = LocalToolbarCallbacks.current
    FilledTonalButton(
        onClick = { cb.onEnterReorderMode() },
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = stringResource(R.string.toolbar_reorder_keys),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ToolbarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    ToolbarKeyButton(onClick = onClick) {
        ToolbarKeyIcon(icon, description)
    }
}

// --- Toolbar reorder mode ---

/** Return the index range of contiguous nav keys in a row, or null if none. */
private fun navBounds(row: List<ToolbarItem>): IntRange? {
    val first = row.indexOfFirst { it is ToolbarItem.BuiltIn && it.key in NAV_KEYS }
    if (first == -1) return null
    val last = row.indexOfLast { it is ToolbarItem.BuiltIn && it.key in NAV_KEYS }
    return first..last
}

/**
 * The fixed (non-draggable) done-✓ over the desktop icon — mirrors live col 0.
 * Tapping ✓ exits reorder mode; the desktop glyph is just a position marker.
 */
@Composable
private fun FixedDoneDesktopColumn(showVncIcon: Boolean, onDone: () -> Unit) {
    KeyColumn(
        top = {
            IconButton(onClick = onDone, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.toolbar_done_reordering),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        bottom = if (showVncIcon) {
            {
                Icon(
                    Icons.Filled.DesktopWindows,
                    contentDescription = stringResource(R.string.toolbar_vnc_desktop),
                    modifier = Modifier.size(32.dp).padding(7.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
    )
}

/** The fixed add-custom-key over open-settings buttons. */
@Composable
private fun FixedAddSettingsColumn(
    onAddKey: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        FilledTonalButton(
            onClick = onAddKey,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.toolbar_add_custom_key),
                modifier = Modifier.size(18.dp),
            )
        }
        FilledTonalButton(
            onClick = onSettings,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.toolbar_settings),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Vertical separator between the fixed controls and the draggable keys (#224). */
@Composable
private fun EditModeDivider(modifier: Modifier = Modifier) {
    VerticalDivider(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .height(60.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun ReorderToolbarContent(
    layout: ToolbarLayout,
    onSave: (ToolbarLayout) -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit = {},
    showVncIcon: Boolean = false,
    placement: EditModeControlsPlacement = EditModeControlsPlacement.LEFT,
) {
    val rows = remember(layout) {
        // Pin the leading icon keys to match the live render's fixed 2-column
        // grid (col 1 = keyboard / VNC, col 2 = Attach / Voice). In edit mode the
        // done-✓ button replaces KEYBOARD *in place* and the VNC icon is prepended
        // to row 2 outside the data, so the data order must be:
        //   row 1: [KEYBOARD, ATTACH, …]  -> [done, attach, …]
        //   row 2: [VOICE,    …]          -> [VNC,  voice,  …]
        // so Attach (row 1) and Voice (row 2) both land at column 2 and align.
        fun MutableList<ToolbarItem>.pinToFront(key: ToolbarKey, target: Int) {
            val idx = indexOfFirst { it is ToolbarItem.BuiltIn && it.key == key }
            if (idx >= 0 && idx != target) add(target.coerceAtMost(size), removeAt(idx))
        }
        layout.rows.mapIndexed { i, row ->
            val list = row.toMutableList()
            when (i) {
                0 -> {
                    list.pinToFront(ToolbarKey.KEYBOARD, 0)
                    list.pinToFront(ToolbarKey.ATTACH, 1)
                }
                1 -> list.pinToFront(ToolbarKey.VOICE_KEYBOARD, 0)
            }
            list.toMutableStateList()
        }
    }

    fun saveAndExit() {
        onSave(ToolbarLayout(rows.map { it.toList() }))
        onDone()
    }

    BackHandler { saveAndExit() }

    var showAddKeyDialog by remember { mutableStateOf(false) }

    if (showAddKeyDialog) {
        AddCustomKeyDialog(
            onDismiss = { showAddKeyDialog = false },
            onSave = { customKey ->
                showAddKeyDialog = false
                // Add to row 2 if available, else row 1 (at the end)
                val targetRow = rows.getOrElse(1) { rows[0] }
                targetRow.add(customKey)
            },
        )
    }

    val row1 = rows.getOrNull(0) ?: return
    val row2 = rows.getOrNull(1)
    val nav1 = navBounds(row1)
    val nav2 = if (row2 != null) navBounds(row2) else null

    // KEYBOARD is pinned to row1[0] and rendered as the fixed done-✓ cell (col 0),
    // so the draggable row-1 left segment starts just after it; row-2's left
    // segment starts at 0 (Voice). This makes the two segments pair column-for-column.
    val r1HasKeyboard = (row1.getOrNull(0) as? ToolbarItem.BuiltIn)?.key == ToolbarKey.KEYBOARD
    val r1LeftStart = if (r1HasKeyboard) 1 else 0
    // Per-column max-width oracles shared between row 1 and row 2 within each block.
    val leftColWidths = remember(layout) { mutableStateMapOf<Int, Float>() }
    val rightColWidths = remember(layout) { mutableStateMapOf<Int, Float>() }

    // If no nav keys or single row, fall back to flat rows
    if (nav1 == null && nav2 == null || row2 == null) {
        Column {
            rows.forEachIndexed { i, items ->
                DraggableSegment(
                    items = items,
                    range = 0 until items.size,
                    showDoneButton = i == 0,
                    onDone = ::saveAndExit,
                )
            }
        }
        return
    }

    // Aligned three-column layout: [left keys] [nav block] [right keys]
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        // Helper: transfer an item across the nav block
        fun transferRight(row: MutableList<ToolbarItem>, fromIndex: Int) {
            val item = row.removeAt(fromIndex)
            val insertAt = navBounds(row)?.let { it.last + 1 } ?: row.size
            row.add(insertAt, item)
        }
        fun transferLeft(row: MutableList<ToolbarItem>, fromIndex: Int) {
            val item = row.removeAt(fromIndex)
            val insertAt = navBounds(row)?.first ?: 0
            row.add(insertAt, item)
        }

        /** Move item from one row to the same segment in the other row. */
        fun transferBetweenRows(
            sourceRow: MutableList<ToolbarItem>,
            targetRow: MutableList<ToolbarItem>,
            fromIndex: Int,
            isLeftSegment: Boolean,
        ) {
            val item = sourceRow.removeAt(fromIndex)
            val targetNav = navBounds(targetRow)
            val insertAt = if (isLeftSegment) {
                targetNav?.first ?: targetRow.size
            } else {
                targetRow.size
            }
            targetRow.add(insertAt, item)
        }

        // Left block. The fixed controls lead here for SPLIT (done-✓/desktop) and
        // LEFT (the whole cluster); for RIGHT nothing leads and the draggable keys
        // start at the edge. The draggable segments pair index-for-index in a Column
        // sized to the wider of the two, mirroring live col 0. (#224)
        Row(verticalAlignment = Alignment.Top) {
            when (placement) {
                EditModeControlsPlacement.SPLIT -> {
                    FixedDoneDesktopColumn(showVncIcon, ::saveAndExit)
                    EditModeDivider()
                }
                EditModeControlsPlacement.LEFT -> {
                    FixedDoneDesktopColumn(showVncIcon, ::saveAndExit)
                    FixedAddSettingsColumn(
                        onAddKey = { showAddKeyDialog = true },
                        onSettings = { saveAndExit(); onOpenSettings() },
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    EditModeDivider()
                }
                EditModeControlsPlacement.RIGHT -> {}
            }
            Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                DraggableSegment(
                    items = row1,
                    range = r1LeftStart until (nav1?.first ?: row1.size),
                    onTransferRight = if (nav1 != null) { idx -> transferRight(row1, idx) } else null,
                    onTransferToOtherRow = if (row2 != null) { idx ->
                        transferBetweenRows(row1, row2, idx, isLeftSegment = true)
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    columnWidths = leftColWidths,
                )
                if (row2 != null) {
                    DraggableSegment(
                        items = row2,
                        range = 0 until (nav2?.first ?: row2.size),
                        onTransferRight = if (nav2 != null) { idx -> transferRight(row2, idx) } else null,
                        onTransferToOtherRow = { idx ->
                            transferBetweenRows(row2, row1, idx, isLeftSegment = true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        columnWidths = leftColWidths,
                    )
                }
            }
        }

        // Nav block — fixed-width cells matching live mode grid
        val presentNavKeys = rows.flatten()
            .filterIsInstance<ToolbarItem.BuiltIn>()
            .filter { it.key in NAV_KEYS }
            .map { it.key }
            .toSet()

        Column(
            modifier = Modifier
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    RoundedCornerShape(12.dp),
                ),
        ) {
            KeyRow {
                for (key in NAV_GRID_TOP) {
                    if (key in presentNavKeys) ReorderNavCell(key) else NavCell {}
                }
            }
            KeyRow {
                for (key in NAV_GRID_BOTTOM) {
                    if (key in presentNavKeys) ReorderNavCell(key) else NavCell {}
                }
            }
        }

        // Right column
        val r1RightStart = (nav1?.last?.plus(1)) ?: row1.size
        val r2RightStart = if (row2 != null) (nav2?.last?.plus(1)) ?: row2.size else 0
        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            DraggableSegment(
                items = row1,
                range = r1RightStart until row1.size,
                onTransferLeft = if (nav1 != null) { idx -> transferLeft(row1, idx) } else null,
                onTransferToOtherRow = if (row2 != null) { idx ->
                    transferBetweenRows(row1, row2, idx, isLeftSegment = false)
                } else null,
                modifier = Modifier.fillMaxWidth(),
                columnWidths = rightColWidths,
            )
            if (row2 != null) {
                DraggableSegment(
                    items = row2,
                    range = r2RightStart until row2.size,
                    onTransferLeft = if (nav2 != null) { idx -> transferLeft(row2, idx) } else null,
                    onTransferToOtherRow = { idx ->
                        transferBetweenRows(row2, row1, idx, isLeftSegment = false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    columnWidths = rightColWidths,
                )
            }
        }

        // Trailing fixed controls: add/settings for SPLIT, the whole cluster for
        // RIGHT, nothing for LEFT (everything leads there). (#224)
        when (placement) {
            EditModeControlsPlacement.SPLIT -> {
                EditModeDivider(Modifier.align(Alignment.CenterVertically))
                FixedAddSettingsColumn(
                    onAddKey = { showAddKeyDialog = true },
                    onSettings = { saveAndExit(); onOpenSettings() },
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(start = 4.dp),
                )
            }
            EditModeControlsPlacement.RIGHT -> {
                EditModeDivider(Modifier.align(Alignment.CenterVertically))
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(start = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    FixedDoneDesktopColumn(showVncIcon, ::saveAndExit)
                    FixedAddSettingsColumn(
                        onAddKey = { showAddKeyDialog = true },
                        onSettings = { saveAndExit(); onOpenSettings() },
                    )
                }
            }
            EditModeControlsPlacement.LEFT -> {}
        }
    }
}

/** A draggable row of items from a sub-range of a row's item list. */
@Composable
private fun DraggableSegment(
    items: MutableList<ToolbarItem>,
    range: IntRange,
    showDoneButton: Boolean = false,
    onDone: () -> Unit = {},
    onTransferRight: ((Int) -> Unit)? = null,
    onTransferLeft: ((Int) -> Unit)? = null,
    onTransferToOtherRow: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    // Per-column max-width oracle shared with the segment in the OTHER row, keyed
    // by column index (idx - range.first). Each key reports its width as a running
    // max and inflates to that, so a column's two stacked keys end up the same
    // width and line up — reproducing live mode's IntrinsicSize.Max grid across the
    // two independent flat rows. Null disables equalization.
    columnWidths: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Float>? = null,
) {
    if (range.isEmpty()) {
        // Empty segment — still need height for alignment
        Spacer(modifier.height(34.dp))
        return
    }

    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemWidths = remember { mutableStateMapOf<Int, Float>() }
    val itemLeftEdges = remember { mutableStateMapOf<Int, Float>() }
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val transferThreshold = 60f // pixels — ~22dp, enough to indicate intent

    fun checkSwaps() {
        if (draggedIndex < 0) return
        val left = itemLeftEdges[draggedIndex] ?: return
        val width = itemWidths[draggedIndex] ?: return
        val center = left + width / 2 + dragOffset
        if (draggedIndex < range.last) {
            val nL = itemLeftEdges[draggedIndex + 1]
            val nW = itemWidths[draggedIndex + 1]
            if (nL != null && nW != null && center > nL + nW / 2) {
                val from = draggedIndex
                items.add(from + 1, items.removeAt(from))
                draggedIndex = from + 1
                dragOffset = 0f
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        if (draggedIndex > range.first) {
            val pL = itemLeftEdges[draggedIndex - 1]
            val pW = itemWidths[draggedIndex - 1]
            if (pL != null && pW != null && center < pL + pW / 2) {
                val from = draggedIndex
                items.add(from - 1, items.removeAt(from))
                draggedIndex = from - 1
                dragOffset = 0f
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        // Cross-nav transfer: drag past segment boundary
        if (onTransferRight != null && draggedIndex == range.last &&
            dragOffset > transferThreshold
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTransferRight(draggedIndex)
            draggedIndex = -1
            dragOffset = 0f
            dragOffsetY = 0f
        }
        if (onTransferLeft != null && draggedIndex == range.first &&
            dragOffset < -transferThreshold
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTransferLeft(draggedIndex)
            draggedIndex = -1
            dragOffset = 0f
            dragOffsetY = 0f
        }
        // Cross-row transfer: drag up or down
        if (onTransferToOtherRow != null &&
            (dragOffsetY > transferThreshold || dragOffsetY < -transferThreshold)
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTransferToOtherRow(draggedIndex)
            draggedIndex = -1
            dragOffset = 0f
            dragOffsetY = 0f
        }
    }

    Row(
        modifier = modifier
            .padding(vertical = 1.dp)
            .pointerInput(range) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val hit = range.find { idx ->
                            val l = itemLeftEdges[idx] ?: return@find false
                            val w = itemWidths[idx] ?: return@find false
                            offset.x >= l && offset.x < l + w
                        } ?: -1
                        if (hit >= 0) {
                            val item = items[hit]
                            val skip = item is ToolbarItem.BuiltIn &&
                                item.key == ToolbarKey.KEYBOARD
                            if (!skip) {
                                draggedIndex = hit
                                dragOffset = 0f
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (draggedIndex >= 0) {
                            dragOffset += amount.x
                            dragOffsetY += amount.y
                            checkSwaps()
                        }
                    },
                    onDragEnd = { draggedIndex = -1; dragOffset = 0f; dragOffsetY = 0f },
                    onDragCancel = { draggedIndex = -1; dragOffset = 0f; dragOffsetY = 0f },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (idx in range) {
            val item = items[idx]
            val isDoneBtn = showDoneButton &&
                item is ToolbarItem.BuiltIn && item.key == ToolbarKey.KEYBOARD
            if (isDoneBtn) {
                IconButton(
                    onClick = onDone,
                    modifier = Modifier
                        .size(32.dp)
                        .onGloballyPositioned { c ->
                            itemWidths[idx] = c.size.width.toFloat()
                            itemLeftEdges[idx] = c.positionInParent().x
                        },
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.toolbar_done_reordering),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                val col = idx - range.first
                val colMin = columnWidths?.get(col)?.let { with(density) { it.toDp() } } ?: 0.dp
                Box(
                    modifier = Modifier
                        .widthIn(min = colMin)
                        .onGloballyPositioned { c ->
                            val w = c.size.width.toFloat()
                            itemWidths[idx] = w
                            itemLeftEdges[idx] = c.positionInParent().x
                            if (columnWidths != null) {
                                val prev = columnWidths[col]
                                if (prev == null || w > prev) columnWidths[col] = w
                            }
                        }
                        .then(
                            if (idx == draggedIndex) {
                                Modifier
                                    .offset { IntOffset(dragOffset.roundToInt(), 0) }
                                    .zIndex(1f)
                                    .graphicsLayer {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                            } else Modifier
                        ),
                    propagateMinConstraints = true,
                ) {
                    ReorderModeKey(item)
                }
            }
        }
    }
}

@Composable
private fun ReorderModeKey(item: ToolbarItem) {
    // Render through the shared visual so a key looks the same in reorder mode as
    // in live mode — icons stay icons, and Enter/arrows/symbols keep their glyph.
    Surface(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp)
            .widthIn(min = LocalToolbarMinKeyWidth.current),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            ToolbarKeyContent(toolbarKeyVisual(item))
        }
    }
}

/** Nav cell for reorder mode — fixed 44dp width matching live mode. */
@Composable
private fun ReorderNavCell(key: ToolbarKey) {
    val isArrow = key in setOf(
        ToolbarKey.ARROW_LEFT, ToolbarKey.ARROW_UP,
        ToolbarKey.ARROW_DOWN, ToolbarKey.ARROW_RIGHT,
    )
    val label = when (key) {
        ToolbarKey.ARROW_LEFT -> "\u2190"
        ToolbarKey.ARROW_UP -> "\u2191"
        ToolbarKey.ARROW_DOWN -> "\u2193"
        ToolbarKey.ARROW_RIGHT -> "\u2192"
        else -> key.label
    }
    // Home/End render as icons here too, so the chip matches the live key.
    val icon = keyIcon(key)
    NavCell {
        FilledTonalButton(
            onClick = {},
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (icon != null) {
                ToolbarKeyIcon(icon, key.label)
            } else {
                Text(
                    label,
                    fontSize = if (isArrow) 16.sp else 11.sp,
                    lineHeight = if (isArrow) 16.sp else 11.sp,
                    fontWeight = if (isArrow) {
                        androidx.compose.ui.text.font.FontWeight.Bold
                    } else null,
                )
            }
        }
    }
}

// --- Add custom key dialog ---

internal fun displaySendSequence(send: String): String {
    if (send == "PASTE") return "Paste clipboard"
    return send.map { ch ->
        when {
            ch.code < 0x20 -> "\\u${ch.code.toString(16).padStart(4, '0')}"
            else -> ch.toString()
        }
    }.joinToString("")
}

internal fun parseSendSequence(input: String): String {
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
                        if (code != null) { sb.append(code.toChar()); i += 6 }
                        else { sb.append(input[i]); i++ }
                    } else { sb.append(input[i]); i++ }
                }
                else -> { sb.append(input[i]); i++ }
            }
        } else { sb.append(input[i]); i++ }
    }
    return sb.toString()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddCustomKeyDialog(
    onDismiss: () -> Unit,
    onSave: (ToolbarItem.Custom) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var sendText by remember { mutableStateOf("") }
    var presetExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolbar_custom_key_dialog_title)) },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it },
                ) {
                    OutlinedTextField(
                        value = stringResource(R.string.toolbar_presets_label),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded)
                        },
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
                    label = { Text(stringResource(R.string.toolbar_custom_key_label)) },
                    placeholder = { Text(stringResource(R.string.toolbar_custom_key_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    label = { Text(stringResource(R.string.toolbar_custom_key_sequence)) },
                    placeholder = { Text("e.g. \\u0003") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                Text(stringResource(R.string.toolbar_custom_key_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.toolbar_custom_key_cancel)) }
        },
    )
}
