package sh.haven.feature.rdp

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sh.haven.core.data.preferences.ToolbarKey
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.ui.CursorOverlay
import sh.haven.feature.rdp.R
import androidx.compose.ui.res.stringResource
import kotlin.math.abs

/**
 * Stateless RDP session content — takes StateFlows and input lambdas directly.
 * Used by DesktopViewModel's multi-tab system. All connection management
 * is handled externally; this composable only renders and forwards input.
 */
@Composable
fun RdpSessionContent(
    connected: StateFlow<Boolean>,
    frame: StateFlow<Bitmap?>,
    /**
     * #422: [frame] now carries a single bitmap that is mutated in place, so its
     * identity stops changing per update and nothing downstream would repaint.
     * This counter is what invalidates the draw.
     */
    frameSeq: StateFlow<Long> = NO_FRAME_SEQ,
    error: StateFlow<String?>,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onTap: (Int, Int) -> Unit,
    /** Two-finger tap → middle-button click at the tapped point (X11 button 2). */
    onMiddleClick: (Int, Int) -> Unit = { _, _ -> },
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onTypeChar: (Char) -> Unit,
    onKeyDown: (scancode: Int) -> Unit,
    onKeyUp: (scancode: Int) -> Unit,
    onDisconnect: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
    /** Server-pushed cursor shape, drawn at the tracked pointer position (#212). */
    cursor: StateFlow<CursorOverlay?>? = null,
    pointerPos: StateFlow<Pair<Int, Int>>? = null,
    inputMode: String = "DIRECT",
    /** Switch DIRECT/TOUCHPAD from the toolbar (#183/#212). Null hides the toggle. */
    onSetInputMode: ((String) -> Unit)? = null,
    /**
     * Activity orientation constant
     * (`ActivityInfo.SCREEN_ORIENTATION_*`) currently in effect for
     * this session. The owner is responsible for applying it to the
     * Activity (via `requestedOrientation = ...`) — this composable
     * only renders the toolbar button reflecting the value.
     */
    currentOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
    /**
     * Cycle landscape -> portrait -> auto. The owner mutates its
     * stored orientation; this composable just calls back.
     */
    onCycleOrientation: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    /** Fullscreen menu-chip anchor; hold-drag to move, persisted by the host (#528). */
    chipAnchor: sh.haven.core.data.preferences.RdpChipAnchor =
        sh.haven.core.data.preferences.RdpChipAnchor.DEFAULT,
    onChipAnchorChange: (sh.haven.core.data.preferences.RdpChipAnchor) -> Unit = {},
) {
    val connectedState by connected.collectAsState()
    val frameState by frame.collectAsState()
    val errorState by error.collectAsState()
    val pointerState = pointerPos?.collectAsState()?.value ?: (0 to 0)
    val cursorState = cursor?.collectAsState()?.value

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

    LaunchedEffect(connectedState) {
        if (!connectedState && fullscreen) fullscreen = false
    }

    DisposableEffect(Unit) {
        onDispose {
            if (fullscreen && window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
                onFullscreenChanged(false)
            }
        }
    }

    val frameSeqState = frameSeq.collectAsState()

    if (connectedState && frameState != null) {
        RdpViewer(
            frame = frameState!!,
            frameSeq = frameSeqState,
            fullscreen = fullscreen,
            toolbarLayout = toolbarLayout,
            onTap = onTap,
            onMiddleClick = onMiddleClick,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onScrollUp = onScrollUp,
            onScrollDown = onScrollDown,
            onTypeChar = onTypeChar,
            onKeyDown = onKeyDown,
            onKeyUp = onKeyUp,
            onToggleFullscreen = { fullscreen = !fullscreen },
            onDisconnect = onDisconnect,
            cursor = cursorState,
            pointerPos = pointerState,
            inputMode = inputMode,
            onSetInputMode = onSetInputMode,
            currentOrientation = currentOrientation,
            onCycleOrientation = onCycleOrientation,
            chipAnchor = chipAnchor,
            onChipAnchorChange = onChipAnchorChange,
        )
    } else {
        DesktopPlaceholder(
            protocol = "RDP",
            error = errorState,
            progressState = when {
                errorState != null -> ProgressState.Error
                !connectedState -> ProgressState.Connecting
                else -> ProgressState.WaitingForFrame
            },
            onDisconnect = onDisconnect,
            onRetry = onRetry,
        )
    }
}

/** Legacy RdpScreen with ViewModel — delegates to RdpSessionContent. */
@Composable
fun RdpScreen(
    isActive: Boolean = true,
    pendingHost: String? = null,
    pendingPort: Int? = null,
    pendingUsername: String? = null,
    pendingPassword: String? = null,
    pendingDomain: String? = null,
    pendingSshForward: Boolean = false,
    pendingSshSessionId: String? = null,
    pendingSshProfileId: String? = null,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onPendingConsumed: () -> Unit = {},
    onFullscreenChanged: (Boolean) -> Unit = {},
    viewModel: RdpViewModel = hiltViewModel(),
) {
    LaunchedEffect(pendingHost, pendingSshSessionId) {
        if (pendingHost != null && pendingPassword != null) {
            if (pendingSshForward && pendingSshSessionId != null) {
                viewModel.connectViaSsh(
                    pendingSshSessionId,
                    pendingHost,
                    pendingPort ?: 3389,
                    pendingUsername ?: "",
                    pendingPassword,
                    pendingDomain ?: "",
                )
            } else if (!pendingSshForward) {
                viewModel.connect(
                    pendingHost,
                    pendingPort ?: 3389,
                    pendingUsername ?: "",
                    pendingPassword,
                    pendingDomain ?: "",
                )
            }
            onPendingConsumed()
        } else if (pendingHost != null) {
            onPendingConsumed()
        }
    }

    // Standalone-path orientation state. Lives in RdpScreen (the
    // outer composable) so it sits above any conditional siblings
    // inside RdpSessionContent / RdpViewer that would otherwise tear
    // down a `remember` on slot-position shifts. Apply to the
    // Activity directly.
    val activity = androidx.activity.compose.LocalActivity.current
    var orientationValue by remember {
        mutableStateOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }
    LaunchedEffect(orientationValue, activity) {
        activity?.requestedOrientation = orientationValue
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    RdpSessionContent(
        connected = viewModel.connected,
        frame = viewModel.frame,
        frameSeq = viewModel.frameSeq,
        error = viewModel.error,
        toolbarLayout = toolbarLayout,
        onTap = { x, y -> viewModel.sendClick(x, y) },
        onDragStart = { x, y ->
            viewModel.sendPointer(x, y)
            viewModel.pressButton()
        },
        onDrag = { x, y -> viewModel.sendPointer(x, y) },
        onDragEnd = { viewModel.releaseButton() },
        onScrollUp = { viewModel.scrollUp() },
        onScrollDown = { viewModel.scrollDown() },
        onTypeChar = { ch ->
            typeRdpChar(
                ch = ch,
                sendKey = { sc, pressed -> viewModel.sendKey(sc, pressed) },
                sendUnicode = { codepoint -> viewModel.typeUnicode(codepoint) },
            )
        },
        onKeyDown = { scancode -> viewModel.sendKey(scancode, true) },
        onKeyUp = { scancode -> viewModel.sendKey(scancode, false) },
        onDisconnect = { viewModel.disconnect() },
        onFullscreenChanged = onFullscreenChanged,
        currentOrientation = orientationValue,
        onCycleOrientation = { orientationValue = cycleRdpOrientation(orientationValue) },
    )
}

internal enum class ProgressState { Connecting, WaitingForFrame, Error }

@Composable
private fun DesktopPlaceholder(
    protocol: String,
    error: String?,
    progressState: ProgressState = ProgressState.Error,
    onDisconnect: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(protocol, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        when (progressState) {
            ProgressState.Connecting -> {
                androidx.compose.material3.CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.rdp_status_connecting),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.rdp_status_connecting_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            ProgressState.WaitingForFrame -> {
                androidx.compose.material3.CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.rdp_status_waiting_for_frame),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProgressState.Error -> {
                Text(
                    stringResource(R.string.rdp_status_connection_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (error != null) {
            // Friendly diagnosis above the raw error text for known
            // failure patterns. Detected by substring match on the
            // server's error string — cheap, robust, and the raw text
            // remains visible underneath for anything else.
            val hint = rdpErrorHint(error)
            if (hint != null) {
                Spacer(Modifier.height(16.dp))
                val uriHandler = LocalUriHandler.current
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            text = stringResource(hint.titleRes),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(hint.bodyRes),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (hint.linkUrl != null && hint.linkLabelRes != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { uriHandler.openUri(hint.linkUrl) }) {
                                Text(stringResource(hint.linkLabelRes!!))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        val showButtons = progressState == ProgressState.Error || progressState == ProgressState.Connecting
        if (showButtons && (onDisconnect != null || onRetry != null)) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDisconnect != null) {
                    TextButton(onClick = onDisconnect) {
                        Text(if (progressState == ProgressState.Error) stringResource(R.string.rdp_action_close) else stringResource(R.string.rdp_action_cancel))
                    }
                }
                // Retry only makes sense once it's failed, not mid-handshake.
                if (onRetry != null && progressState == ProgressState.Error) {
                    Button(onClick = onRetry) { Text(stringResource(R.string.rdp_action_retry)) }
                }
            }
        }
    }
}

/**
 * Default for callers whose frames still arrive as a fresh bitmap each update —
 * SPICE reuses this renderer — where the changing identity already invalidates
 * the draw and no counter is needed (#422).
 */
private val NO_FRAME_SEQ: StateFlow<Long> = MutableStateFlow(0L)

// --- RDP Desktop Viewer ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RdpViewer(
    frame: Bitmap,
    /**
     * Read ONLY inside the draw scope, so an in-place bitmap change invalidates
     * the draw phase without recomposing this whole subtree every frame (#422).
     */
    frameSeq: State<Long>,
    fullscreen: Boolean,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onTap: (Int, Int) -> Unit,
    onMiddleClick: (Int, Int) -> Unit = { _, _ -> },
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onTypeChar: (Char) -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onToggleFullscreen: () -> Unit,
    onDisconnect: () -> Unit,
    cursor: CursorOverlay? = null,
    pointerPos: Pair<Int, Int> = 0 to 0,
    inputMode: String = "DIRECT",
    onSetInputMode: ((String) -> Unit)? = null,
    currentOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
    onCycleOrientation: () -> Unit = {},
    chipAnchor: sh.haven.core.data.preferences.RdpChipAnchor =
        sh.haven.core.data.preferences.RdpChipAnchor.DEFAULT,
    onChipAnchorChange: (sh.haven.core.data.preferences.RdpChipAnchor) -> Unit = {},
) {
    // Map the activity-orientation constant to the local enum for
    // icon/description rendering. Source-of-truth for the value lives
    // outside this composable (RdpScreen for the standalone path,
    // DesktopViewModel for the multi-session Desktop view) so it
    // survives recomposition cycles that would tear down a `remember`
    // here.
    val orientationMode = OrientationMode.fromActivityValue(currentOrientation)
    val orientationDesc = when (orientationMode) {
        OrientationMode.Landscape -> stringResource(R.string.rdp_orientation_landscape_desc)
        OrientationMode.Portrait -> stringResource(R.string.rdp_orientation_portrait_desc)
        OrientationMode.Auto -> stringResource(R.string.rdp_orientation_auto_desc)
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(frame) { frame.asImageBitmap() }
    val cursorImage = remember(cursor) { cursor?.bitmap?.asImageBitmap() }

    // Zoom & pan state
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }
    // Two-finger drag target: false = pan the local viewport (when zoomed),
    // true = forward as remote scroll-wheel. Pinch always zooms. Toolbar
    // toggle (#286 — 3-finger gestures are unreliable on OnePlus/OxygenOS).
    var twoFingerScroll by rememberSaveable { mutableStateOf(false) }

    // Touchpad-mode virtual cursor — composable scope so it survives lifts.
    var virtualCursor by remember(inputMode) { mutableStateOf(pointerPos) }

    // #598: the server only sends a cursor SHAPE when the guest first moves its
    // own cursor, so a freshly connected session has input but nothing to draw
    // (the guest's current shape is not replayed on channel init). Until a
    // shape arrives, draw a built-in arrow once the user has pointed somewhere.
    // RdpViewer is torn down on disconnect, so both `remember`s reset per
    // session. `hasReceivedCursorShape` is what bounds the fallback to the
    // connect window: once ANY shape has been delivered, a null cursor means
    // the guest deliberately hid it and must not be papered over with the
    // fallback arrow.
    var pointerActive by remember { mutableStateOf(false) }
    var hasReceivedCursorShape by remember { mutableStateOf(false) }
    LaunchedEffect(cursor) {
        if (cursor != null) hasReceivedCursorShape = true
    }
    // #572: guests with software cursors (W98, plain VNC-style) draw their
    // pointer into the framebuffer and never send a CURSOR_SET, so
    // `hasReceivedCursorShape` stays false and the fallback arrow rides on
    // top of the guest's own — two pointers. The fallback is for the connect
    // window only, so stand it down after a few seconds of motion whether or
    // not a shape ever arrives: if the server was going to replay one, the
    // guest's next cursor update (which any interaction triggers) will have
    // arrived by then; if none did, the guest is drawing its own.
    var fallbackExpired by remember { mutableStateOf(false) }
    LaunchedEffect(pointerActive) {
        if (pointerActive && !hasReceivedCursorShape) {
            delay(6000)
            fallbackExpired = true
        }
    }

    // Tap-then-drag state — see VncScreen for the full rationale. RDP
    // has no long-press right-click branch, so we only need the
    // follow-up window for triggering button-1 drag.
    var lastTapUpMs by remember { mutableStateOf(0L) }

    // Mario-camera viewport pan: when in touchpad mode and zoomed, snap the
    // pan so the cursor always stays inside the inner dead-zone of the view.
    LaunchedEffect(virtualCursor, inputMode, zoom, viewSize, frame.width, frame.height) {
        if (inputMode == "TOUCHPAD" && zoom > 1f && viewSize.width > 0 && viewSize.height > 0) {
            val (newPanX, newPanY) = cameraFollow(
                cursorFbX = virtualCursor.first,
                cursorFbY = virtualCursor.second,
                fbWidth = frame.width,
                fbHeight = frame.height,
                viewW = viewSize.width.toFloat(),
                viewH = viewSize.height.toFloat(),
                zoom = zoom,
                panX = panX,
                panY = panY,
            )
            if (newPanX != panX) panX = newPanX
            if (newPanY != panY) panY = newPanY
        }
    }

    // Keyboard
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var keyboardVisible by remember { mutableStateOf(false) }

    // #507: with a physical keyboard there is no reason to toggle the soft one,
    // and the soft-keyboard toggle was the ONLY thing that ever focused the
    // hidden text field — so nothing on this screen held focus, and hardware
    // Enter/Space activated whatever Compose had focused instead (the nav
    // drawer button, per @pawlosck's repro: "when I can't write, the menu
    // opens every time"). The canvas is focusable so the screen can hold
    // focus WITHOUT summoning the IME (focusing the text field shows it),
    // and the scancode handler lives on the root Box, which sees the preview
    // pass for every descendant — the text field included — so one handler
    // covers both focus holders.
    val hardwareKeyFocus = remember { FocusRequester() }
    // Declared before the key handler because the #606 guard below reads
    // `fieldFocused` to tell soft-keyboard echoes from physical keys.
    var canvasFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val handleHardwareKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        // #606: while the soft keyboard is attached to the hidden text field,
        // the commit path (onValueChange → onTypeChar) is the single sender for
        // printable characters. Some IMEs (AOSP's Spanish layout among them)
        // ALSO fire a synthetic hardware key event for the same press, which
        // this preview handler sees — the guest gets the character twice (the
        // `!!@@` flip), and when the IME repeats the event on its flush
        // cadence, a stream of keystrokes. Drop printable events while the
        // field holds focus; non-printable keys (arrows, F-keys, Enter, Tab,
        // modifiers) have no commit-path equivalent and still pass through.
        // termlib's terminal input path guards the same race with a
        // commit-then-suppress queue (ImeInputView.dispatchKeyEvent).
        val native = event.nativeKeyEvent
        val committed = native.getUnicodeChar(native.metaState)
        // Enter and Tab are excluded: the soft keyboard delivers them as
        // editor actions, not text commits, so onValueChange never sees them
        // and the scancode path below is their only sender.
        if (fieldFocused && committed > 0 &&
            committed != '\r'.code && committed != '\n'.code && committed != '\t'.code
        ) {
            true
        } else {
            val scancode = androidKeyToScancode(event.key)
            if (scancode != null) {
                when (event.type) {
                    KeyEventType.KeyDown -> onKeyDown(scancode)
                    KeyEventType.KeyUp -> onKeyUp(scancode)
                }
                true
            } else {
                false
            }
        }
    }
    LaunchedEffect(Unit) { hardwareKeyFocus.requestFocus() }

    // The system re-shows the IME across a configuration change (rotation)
    // even when the user dismissed it: this activity handles orientation in
    // configChanges, so `keyboardVisible` survives the rotation, but the
    // framework's re-show doesn't consult it. Enforce the invariant — the IME
    // is up only when we asked for it — which covers all three toggle sites
    // (key toolbar, bottom toolbar, fullscreen overlay) at once. Scoped to
    // this screen's own focus holders: `isImeVisible` is window-global, and
    // this content stays composed behind the other tabs, so without the scope
    // the terminal tab's keyboard would be hidden (and its focus stolen) by
    // this effect.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible, keyboardVisible, canvasFocused, fieldFocused) {
        if (!keyboardVisible && imeVisible && (canvasFocused || fieldFocused)) {
            keyboardController?.hide()
            hardwareKeyFocus.requestFocus()
        }
    }

    // Modifier state for key toolbar
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    var winActive by remember { mutableStateOf(false) }

    // Fullscreen overlay toolbar
    var overlayVisible by remember { mutableStateOf(false) }

    // Auto-hide overlay after 4 seconds
    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            delay(4000)
            overlayVisible = false
        }
    }

    // #528: the summon chip dims to a ghost after a few idle seconds. It used
    // to sit at 40% alpha forever — @pawlosck asked to move or hide it because
    // it was parked over the remote's own window controls (top-right is where
    // Windows keeps minimise/restore/close; the chip now lives top-centre,
    // mstsc's convention, for the same reason). Full alpha returns whenever
    // the overlay opens, so finding it again is one glance, not a hunt.
    var chipDimmed by remember { mutableStateOf(false) }
    LaunchedEffect(fullscreen, overlayVisible) {
        chipDimmed = false
        if (fullscreen && !overlayVisible) {
            delay(5000)
            chipDimmed = true
        }
    }
    val chipAlpha by animateFloatAsState(
        targetValue = if (chipDimmed) 0.35f else 1f,
        animationSpec = tween(durationMillis = 800),
        label = "fullscreenChipAlpha",
    )

    // Sentinel for the hidden text field
    val sentinel = " "
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length)))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(handleHardwareKey),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // RDP canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .focusRequester(hardwareKeyFocus)
                .focusable()
                .onFocusChanged { canvasFocused = it.isFocused }
                .onSizeChanged { viewSize = it }
                .pointerInput(frame.width, frame.height, viewSize, inputMode) {
                    val touchSlopPx = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        firstDown.consume()
                        pointerActive = true
                        // #507: a canvas tap re-claims hardware-key focus from
                        // whatever took it (drawer, toolbar button) — but not
                        // while the soft keyboard is up, where moving focus
                        // off the text field would dismiss it.
                        if (!keyboardVisible) hardwareKeyFocus.requestFocus()
                        var totalFingers = 1
                        var prevCentroid = firstDown.position
                        var prevSpan = 0f
                        var prevCount = 0
                        var gestureStarted = false
                        var cumulativeScrollY = 0f
                        var totalMovement = 0f
                        // Centroid travel while ≥2 fingers are down; a near-zero
                        // total means a 2-finger tap → middle click (#286).
                        var twoFingerMovement = 0f
                        var lastSinglePos = firstDown.position
                        var dragging = false
                        // Tap-then-drag follow-up window (touchpad mode only).
                        val isFollowUpTouch = inputMode == "TOUCHPAD" &&
                            (firstDown.uptimeMillis - lastTapUpMs) <= 300L
                        var dragButtonPressed = false

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointers = event.changes.filter { it.pressed }
                            val count = pointers.size

                            if (count >= 2) {
                                if (dragging) {
                                    onDragEnd()
                                    dragging = false
                                }
                                totalFingers = maxOf(totalFingers, count)
                                val centroid = Offset(
                                    pointers.map { it.position.x }.average().toFloat(),
                                    pointers.map { it.position.y }.average().toFloat(),
                                )
                                val span = pointers.map {
                                    (it.position - centroid).getDistance()
                                }.average().toFloat()

                                // Skip delta on the frame where pointer count
                                // changed — the centroid recomputes over a
                                // different pointer set, so the apparent jump
                                // would feed in as a real pan/zoom delta.
                                if (gestureStarted && count == prevCount) {
                                    val dx = centroid.x - prevCentroid.x
                                    val dy = centroid.y - prevCentroid.y
                                    twoFingerMovement += abs(dx) + abs(dy)
                                    // Distinguish a pinch (span changing) from a
                                    // drag (fingers translating together).
                                    val spanDelta = if (prevSpan > 0f) abs(span - prevSpan) else 0f
                                    val pinching = spanDelta > 2f && spanDelta >= abs(dx) + abs(dy)
                                    if (pinching && prevSpan > 0f && span > 0f) {
                                        // Pinch → zoom local viewport (both modes).
                                        // graphicsLayer's TransformOrigin is the view
                                        // centre, so pivot at (cx, cy); use the actual
                                        // (clamp-aware) scale so we don't over-pan at a limit.
                                        // Floor at 1×: drawRemoteFrame letterboxes the
                                        // full screen at zoom 1, so zooming out further
                                        // only enlarges the bars (Refs #600).
                                        val newZoom = (zoom * (span / prevSpan)).coerceIn(1f, 5f)
                                        val actualScale = if (zoom > 0f) newZoom / zoom else 1f
                                        val cx = viewSize.width / 2f
                                        val cy = viewSize.height / 2f
                                        panX = (centroid.x - cx) * (1 - actualScale) + panX * actualScale
                                        panY = (centroid.y - cy) * (1 - actualScale) + panY * actualScale
                                        zoom = newZoom
                                    } else if (twoFingerScroll) {
                                        // Windows-scroll mode: drag = remote wheel.
                                        // Finger-down (dy>0) → wheel-up so the page
                                        // tracks the fingers (touchscreen convention).
                                        cumulativeScrollY += dy
                                        if (abs(cumulativeScrollY) > 40f) {
                                            if (cumulativeScrollY < 0) onScrollDown() else onScrollUp()
                                            cumulativeScrollY = 0f
                                        }
                                    } else if (zoom > 1f) {
                                        // Viewport mode: drag = pan (only when zoomed in).
                                        panX += dx
                                        panY += dy
                                    }
                                }

                                gestureStarted = true
                                prevCentroid = centroid
                                prevSpan = span
                                prevCount = count

                                pointers.forEach { it.consume() }
                            } else if (count == 1 && totalFingers == 1) {
                                val change = pointers.first()
                                val deltaScreen = change.positionChange()
                                totalMovement += deltaScreen.getDistance()
                                lastSinglePos = change.position
                                if (inputMode == "TOUCHPAD") {
                                    val scale = if (zoom > 0f) zoom else 1f
                                    val nx = (virtualCursor.first + (deltaScreen.x / scale).toInt())
                                        .coerceIn(0, frame.width - 1)
                                    val ny = (virtualCursor.second + (deltaScreen.y / scale).toInt())
                                        .coerceIn(0, frame.height - 1)
                                    virtualCursor = nx to ny
                                    if (totalMovement >= touchSlopPx) {
                                        if (isFollowUpTouch && !dragButtonPressed) {
                                            onDragStart(nx, ny)
                                            dragButtonPressed = true
                                        } else {
                                            onDrag(nx, ny)
                                        }
                                    }
                                } else {
                                    val pos = screenToRemote(
                                        change.position, viewSize,
                                        frame.width, frame.height,
                                        zoom, panX, panY,
                                    )
                                    if (!dragging && totalMovement >= touchSlopPx) {
                                        onDragStart(pos.first, pos.second)
                                        dragging = true
                                    } else if (dragging) {
                                        onDrag(pos.first, pos.second)
                                    }
                                }
                                change.consume()
                            } else {
                                // Residual finger after a multi-finger pinch
                                // (count == 1 but totalFingers already >= 2), or
                                // the final all-up frame. Consume it so the
                                // lingering single-finger slide can't leak up to
                                // the HorizontalPager and swipe the Haven screen
                                // sideways during/after a zoom. It isn't a tap or
                                // drag in this state, so consuming costs nothing.
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        if (dragging || dragButtonPressed) {
                            onDragEnd()
                        }

                        if (totalFingers == 1 && totalMovement < touchSlopPx) {
                            val (vx, vy) = if (inputMode == "TOUCHPAD") {
                                virtualCursor
                            } else {
                                screenToRemote(
                                    lastSinglePos, viewSize,
                                    frame.width, frame.height,
                                    zoom, panX, panY,
                                )
                            }
                            onTap(vx, vy)
                            if (inputMode == "TOUCHPAD" && !isFollowUpTouch) {
                                lastTapUpMs = SystemClock.uptimeMillis()
                            } else {
                                lastTapUpMs = 0L
                            }
                        } else if (totalFingers == 2 && twoFingerMovement < touchSlopPx) {
                            // Two fingers down + up with no travel = middle click (#286).
                            val (mx, my) = if (inputMode == "TOUCHPAD") {
                                virtualCursor
                            } else {
                                screenToRemote(
                                    prevCentroid, viewSize,
                                    frame.width, frame.height,
                                    zoom, panX, panY,
                                )
                            }
                            onMiddleClick(mx, my)
                            lastTapUpMs = 0L
                        } else {
                            lastTapUpMs = 0L
                        }
                    }
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX
                        translationY = panY
                        // Clip so zoomed/panned pixels don't escape into the
                        // sibling bottom toolbar (see #107 for VNC equivalent).
                        clip = true
                    },
            ) {
                // The bitmap is mutated in place, so its identity cannot signal a
                // change; this read is what invalidates the draw. Removing it
                // freezes the picture on the first frame.
                @Suppress("UNUSED_EXPRESSION")
                frameSeq.value
                drawRemoteFrame(imageBitmap, frame.width, frame.height)
                // Server cursor overlay (#212). Draw at the touchpad-tracked
                // virtual cursor in TOUCHPAD mode (the position the user is
                // steering), else at the server-reported pointer position.
                val (px, py) = if (inputMode == "TOUCHPAD") virtualCursor else pointerPos
                if (cursorImage != null && cursor != null) {
                    drawRdpCursor(
                        cursor = cursorImage,
                        cursorW = cursor.bitmap.width,
                        cursorH = cursor.bitmap.height,
                        hotspotX = cursor.hotspotX,
                        hotspotY = cursor.hotspotY,
                        pointerX = px,
                        pointerY = py,
                        fbWidth = frame.width,
                        fbHeight = frame.height,
                    )
                } else if (pointerActive && !hasReceivedCursorShape && !fallbackExpired) {
                    // #598: the server has not pushed a shape yet — draw the
                    // built-in arrow at the tracked position instead.
                    drawFallbackCursor(px, py, frame.width, frame.height)
                }
            }
        }

        // Hidden text field for keyboard input capture
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val newText = newValue.text

                if (newText.length > oldText.length) {
                    val added = newText.substring(oldText.length)
                    for (ch in added) {
                        onTypeChar(ch)
                    }
                } else if (newText.length < oldText.length) {
                    val deleted = oldText.length - newText.length
                    repeat(deleted) {
                        onKeyDown(SC_BACKSPACE)
                        onKeyUp(SC_BACKSPACE)
                    }
                }

                textFieldValue = TextFieldValue(sentinel, TextRange(sentinel.length))
            },
            // Hardware keys are handled by the root Box's onPreviewKeyEvent
            // (#507) — an ancestor of this field, so its preview pass fires
            // whether focus sits here (soft keyboard) or on the canvas.
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { fieldFocused = it.isFocused },
        )

        // RDP key toolbar — hidden in fullscreen, and also hidden when the
        // soft keyboard isn't visible (keyboard-extension rows shouldn't eat
        // screen space when there's no keyboard to extend).
        if (!fullscreen && imeVisible) {
            RdpKeyToolbar(
                layout = toolbarLayout,
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                winActive = winActive,
                onToggleCtrl = {
                    ctrlActive = !ctrlActive
                    if (!ctrlActive) onKeyUp(SC_CTRL_L) else onKeyDown(SC_CTRL_L)
                },
                onToggleAlt = {
                    altActive = !altActive
                    if (!altActive) onKeyUp(SC_ALT_L) else onKeyDown(SC_ALT_L)
                },
                onToggleShift = {
                    shiftActive = !shiftActive
                    if (!shiftActive) onKeyUp(SC_SHIFT_L) else onKeyDown(SC_SHIFT_L)
                },
                onToggleWin = {
                    winActive = !winActive
                    if (!winActive) onKeyUp(SC_WIN_L) else onKeyDown(SC_WIN_L)
                },
                onRdpKey = { scancode ->
                    onKeyDown(scancode)
                    onKeyUp(scancode)
                    // Auto-release modifiers
                    if (ctrlActive) { onKeyUp(SC_CTRL_L); ctrlActive = false }
                    if (altActive) { onKeyUp(SC_ALT_L); altActive = false }
                    if (shiftActive) { onKeyUp(SC_SHIFT_L); shiftActive = false }
                    if (winActive) { onKeyUp(SC_WIN_L); winActive = false }
                },
                onToggleKeyboard = {
                    keyboardVisible = !keyboardVisible
                    if (keyboardVisible) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    } else {
                        keyboardController?.hide()
                        hardwareKeyFocus.requestFocus()
                    }
                },
            )
        }

        // Bottom toolbar (hidden in fullscreen). Solid background so a
        // zoomed framebuffer doesn't show through (matches the fix to
        // VNC's equivalent toolbar from #107).
        if (!fullscreen) {
            // Surface (not a bare .background) so the toolbar also sets its
            // content colour: without onSurface the icons fall back to the
            // default (black) and vanish on a dark surface (#286).
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.rdp_cd_disconnect))
                }

                IconButton(onClick = {
                    keyboardVisible = !keyboardVisible
                    if (keyboardVisible) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    } else {
                        keyboardController?.hide()
                        hardwareKeyFocus.requestFocus()
                    }
                }) {
                    Icon(
                        if (keyboardVisible) Icons.Default.KeyboardHide
                        else Icons.Default.Keyboard,
                        contentDescription = stringResource(R.string.rdp_cd_toggle_keyboard),
                    )
                }

                IconButton(onClick = onCycleOrientation) {
                    Icon(orientationMode.icon, contentDescription = orientationDesc)
                }

                // Direct/trackpad input-mode toggle — #183/#212.
                onSetInputMode?.let { InputModeToggle(inputMode, it) }
                // Two-finger drag: viewport pan vs remote scroll — #286.
                ScrollModeToggle(twoFingerScroll) { twoFingerScroll = !twoFingerScroll }

                Spacer(Modifier.weight(1f))

                if (zoom != 1f || panX != 0f || panY != 0f) {
                    IconButton(onClick = {
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                    }) {
                        Icon(Icons.Default.FitScreen, contentDescription = stringResource(R.string.rdp_cd_reset_zoom))
                    }
                }

                IconButton(onClick = onToggleFullscreen) {
                    Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.rdp_cd_fullscreen))
                }
            }
            }
        }
    } // end Column

    // Fullscreen corner hotspot and overlay toolbar
    if (fullscreen) {
        if (overlayVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { overlayVisible = false },
            )
        }

        // Top-centre by default, not top-end: the remote's OWN window controls
        // live in the top-right corner (Windows minimise/restore/close), and
        // the chip was sitting exactly on top of them (#528). mstsc parks its
        // connection pill top-centre for the same reason. Hold-and-drag moves
        // it to any of six edge anchors — same idiom as the terminal's
        // fullscreen button (#445) — snapping to wherever it's released
        // nearest and remembering the spot.
        var chipDragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var chipCoords by remember {
            mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)
        }
        AnimatedVisibility(
            visible = !overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(chipAnchor.toComposeAlignment())
                .offset { androidx.compose.ui.unit.IntOffset(chipDragOffset.x.toInt(), chipDragOffset.y.toInt()) }
                .onGloballyPositioned { chipCoords = it }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, delta ->
                            change.consume()
                            chipDragOffset += delta
                        },
                        onDragEnd = {
                            val coords = chipCoords
                            val parent = coords?.parentLayoutCoordinates
                            if (coords != null && parent != null) {
                                val c = coords.boundsInParent().center
                                onChipAnchorChange(
                                    sh.haven.core.data.preferences.RdpChipAnchor.nearest(
                                        c.x, c.y,
                                        parent.size.width.toFloat(),
                                        parent.size.height.toFloat(),
                                    ),
                                )
                            }
                            chipDragOffset = androidx.compose.ui.geometry.Offset.Zero
                        },
                    )
                }
                .graphicsLayer { alpha = chipAlpha },
        ) {
            Surface(
                onClick = { overlayVisible = true },
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(R.string.rdp_cd_session_menu),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(chipAnchor.toComposeAlignment()),
        ) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                // alpha-copied colour defeats contentColorFor, so set it
                // explicitly or the icons render black on the dark sheet (#286).
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = {
                        overlayVisible = false
                        onDisconnect()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.rdp_cd_disconnect))
                    }
                    IconButton(onClick = {
                        keyboardVisible = !keyboardVisible
                        if (keyboardVisible) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        } else {
                            keyboardController?.hide()
                            hardwareKeyFocus.requestFocus()
                        }
                    }) {
                        Icon(
                            if (keyboardVisible) Icons.Default.KeyboardHide
                            else Icons.Default.Keyboard,
                            contentDescription = stringResource(R.string.rdp_cd_toggle_keyboard),
                        )
                    }
                    IconButton(onClick = onCycleOrientation) {
                        Icon(orientationMode.icon, contentDescription = orientationDesc)
                    }
                    // Direct/trackpad input-mode toggle — #183/#212.
                    onSetInputMode?.let { InputModeToggle(inputMode, it) }
                    // Two-finger drag: viewport pan vs remote scroll — #286.
                    ScrollModeToggle(twoFingerScroll) { twoFingerScroll = !twoFingerScroll }
                    if (zoom != 1f || panX != 0f || panY != 0f) {
                        IconButton(onClick = {
                            zoom = 1f
                            panX = 0f
                            panY = 0f
                        }) {
                            Icon(
                                Icons.Default.FitScreen,
                                contentDescription = stringResource(R.string.rdp_cd_reset_zoom),
                            )
                        }
                    }
                    IconButton(onClick = {
                        overlayVisible = false
                        onToggleFullscreen()
                    }) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.rdp_cd_exit_fullscreen))
                    }
                }
            }
        }
    }
    } // end Box
}

/**
 * Toggle DIRECT (absolute: finger = cursor) vs TOUCHPAD (relative: drag glides
 * the cursor) input from the viewer (#183/#212). Mirrors the VNC toggle so the
 * cursor-in-touchpad-mode fix is reachable without digging into Settings.
 * Checked (highlighted) = trackpad mode.
 */
@Composable
private fun InputModeToggle(inputMode: String, onSetInputMode: (String) -> Unit) {
    val touchpad = inputMode == "TOUCHPAD"
    FilledIconToggleButton(
        checked = touchpad,
        onCheckedChange = { onSetInputMode(if (touchpad) "DIRECT" else "TOUCHPAD") },
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            Icons.Default.TouchApp,
            contentDescription = if (touchpad) stringResource(R.string.rdp_cd_input_mode_trackpad_on)
                                 else stringResource(R.string.rdp_cd_input_mode_direct),
        )
    }
}

/** Two-finger drag target: viewport pan (off) vs remote scroll-wheel (on). */
@Composable
private fun ScrollModeToggle(twoFingerScroll: Boolean, onToggle: () -> Unit) {
    FilledIconToggleButton(
        checked = twoFingerScroll,
        onCheckedChange = { onToggle() },
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            if (twoFingerScroll) Icons.Default.SwapVert else Icons.Default.OpenWith,
            contentDescription = if (twoFingerScroll) stringResource(R.string.rdp_cd_two_finger_scroll_on)
                                 else stringResource(R.string.rdp_cd_two_finger_viewport),
        )
    }
}

private fun DrawScope.drawRemoteFrame(
    image: androidx.compose.ui.graphics.ImageBitmap,
    srcWidth: Int,
    srcHeight: Int,
) {
    val viewW = size.width
    val viewH = size.height
    val scale = minOf(viewW / srcWidth, viewH / srcHeight)
    val dstW = srcWidth * scale
    val dstH = srcHeight * scale
    val offsetX = (viewW - dstW) / 2
    val offsetY = (viewH - dstH) / 2

    drawImage(
        image = image,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(srcWidth, srcHeight),
        dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt(), dstH.toInt()),
    )
}

/**
 * Draw the server cursor at the tracked pointer position (#212). Uses the same
 * fit-scale/centre math as [drawRemoteFrame] so it lands in the framebuffer's
 * local coordinate space; the enclosing Canvas's graphicsLayer then applies
 * zoom/pan uniformly. Mirror of VncScreen's drawVncCursor.
 */
private fun DrawScope.drawRdpCursor(
    cursor: androidx.compose.ui.graphics.ImageBitmap,
    cursorW: Int,
    cursorH: Int,
    hotspotX: Int,
    hotspotY: Int,
    pointerX: Int,
    pointerY: Int,
    fbWidth: Int,
    fbHeight: Int,
) {
    val viewW = size.width
    val viewH = size.height
    val scale = minOf(viewW / fbWidth, viewH / fbHeight)
    val fbOffsetX = (viewW - fbWidth * scale) / 2
    val fbOffsetY = (viewH - fbHeight * scale) / 2

    val cx = fbOffsetX + (pointerX - hotspotX) * scale
    val cy = fbOffsetY + (pointerY - hotspotY) * scale
    val dstW = cursorW * scale
    val dstH = cursorH * scale

    drawImage(
        image = cursor,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(cursorW, cursorH),
        dstOffset = androidx.compose.ui.unit.IntOffset(cx.toInt(), cy.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt().coerceAtLeast(1), dstH.toInt().coerceAtLeast(1)),
    )
}

/**
 * #598 fallback pointer. The guest's current cursor shape is not replayed on
 * channel init — the first CURSOR_SET only arrives on the guest's NEXT cursor
 * update — so a freshly connected session has working input but no glyph to
 * draw. Until the server pushes a shape, draw a built-in arrow at the tracked
 * position. Uses the same fit-scale/centre space as [drawRdpCursor]; the
 * enclosing Canvas's graphicsLayer applies zoom/pan uniformly.
 */
private fun DrawScope.drawFallbackCursor(
    pointerX: Int,
    pointerY: Int,
    fbWidth: Int,
    fbHeight: Int,
) {
    val viewW = size.width
    val viewH = size.height
    val scale = minOf(viewW / fbWidth, viewH / fbHeight)
    val originX = (viewW - fbWidth * scale) / 2f + pointerX * scale
    val originY = (viewH - fbHeight * scale) / 2f + pointerY * scale

    // Classic arrow in a 24x24 remote-pixel box, tip (hotspot) at (0, 0).
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 16f)
        lineTo(4.5f, 12.5f)
        lineTo(7.5f, 19.5f)
        lineTo(10.5f, 18f)
        lineTo(7.5f, 11f)
        lineTo(13f, 11f)
        close()
    }
    translate(originX, originY) {
        scale(scale, scale) {
            drawPath(path, Color.White, style = Fill)
            drawPath(path, Color.Black, style = Stroke(width = 1.5f / scale))
        }
    }
}

/**
 * Map a screen touch coordinate to remote desktop coordinates,
 * accounting for zoom and pan.
 */
/**
 * Mario-camera viewport pan: keep the cursor inside an inner dead-zone of
 * the view. Returns the (panX, panY) we should snap to. No-op when not
 * zoomed in (whole framebuffer fits, no point panning). Mirror of the VNC
 * helper of the same name; kept local because feature/rdp doesn't depend
 * on feature/vnc.
 */
internal fun cameraFollow(
    cursorFbX: Int, cursorFbY: Int,
    fbWidth: Int, fbHeight: Int,
    viewW: Float, viewH: Float,
    zoom: Float, panX: Float, panY: Float,
    deadZoneFraction: Float = 0.30f,
): Pair<Float, Float> {
    if (zoom <= 1f) return panX to panY
    if (viewW <= 0f || viewH <= 0f || fbWidth <= 0 || fbHeight <= 0) return panX to panY

    val cx = viewW / 2f
    val cy = viewH / 2f
    val fitScale = minOf(viewW / fbWidth, viewH / fbHeight)
    val fitOffsetX = (viewW - fbWidth * fitScale) / 2f
    val fitOffsetY = (viewH - fbHeight * fitScale) / 2f

    val localX = cursorFbX * fitScale + fitOffsetX
    val localY = cursorFbY * fitScale + fitOffsetY
    val screenX = (localX - cx) * zoom + cx + panX
    val screenY = (localY - cy) * zoom + cy + panY

    val marginX = viewW * (deadZoneFraction / 2f)
    val marginY = viewH * (deadZoneFraction / 2f)
    val minX = marginX
    val maxX = viewW - marginX
    val minY = marginY
    val maxY = viewH - marginY

    val newPanX = when {
        screenX < minX -> panX + (minX - screenX)
        screenX > maxX -> panX - (screenX - maxX)
        else -> panX
    }
    val newPanY = when {
        screenY < minY -> panY + (minY - screenY)
        screenY > maxY -> panY - (screenY - maxY)
        else -> panY
    }
    return newPanX to newPanY
}

private fun screenToRemote(
    offset: Offset,
    viewSize: IntSize,
    fbWidth: Int,
    fbHeight: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
): Pair<Int, Int> {
    if (viewSize.width == 0 || viewSize.height == 0) return 0 to 0
    val viewW = viewSize.width.toFloat()
    val viewH = viewSize.height.toFloat()

    val cx = viewW / 2f
    val cy = viewH / 2f
    val localX = (offset.x - cx - panX) / zoom + cx
    val localY = (offset.y - cy - panY) / zoom + cy

    val fitScale = minOf(viewW / fbWidth, viewH / fbHeight)
    val dstW = fbWidth * fitScale
    val dstH = fbHeight * fitScale
    val offsetX = (viewW - dstW) / 2
    val offsetY = (viewH - dstH) / 2

    val remoteX = ((localX - offsetX) / fitScale).toInt().coerceIn(0, fbWidth - 1)
    val remoteY = ((localY - offsetY) / fitScale).toInt().coerceIn(0, fbHeight - 1)
    return remoteX to remoteY
}

// --- RDP Key Toolbar ---

@Composable
private fun RdpKeyToolbar(
    layout: ToolbarLayout,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    winActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleWin: () -> Unit,
    onRdpKey: (scancode: Int) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column {
            // Modifier row
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onToggleKeyboard,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = stringResource(R.string.rdp_cd_toggle_keyboard), modifier = Modifier.size(18.dp))
                }
                RdpToggleButton("Ctrl", ctrlActive, onToggleCtrl)
                RdpToggleButton("Alt", altActive, onToggleAlt)
                RdpToggleButton("Shift", shiftActive, onToggleShift)
                RdpToggleButton("Win", winActive, onToggleWin)
                RdpKeyButton("Esc") { onRdpKey(SC_ESCAPE) }
                RdpKeyButton("Tab") { onRdpKey(SC_TAB) }
                RdpKeyButton("Del") { onRdpKey(SC_DELETE) }
                RdpKeyButton("Ins") { onRdpKey(SC_INSERT) }
            }
            // Navigation row
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RdpArrowButton("\u2190") { onRdpKey(SC_LEFT) }
                RdpArrowButton("\u2191") { onRdpKey(SC_UP) }
                RdpArrowButton("\u2193") { onRdpKey(SC_DOWN) }
                RdpArrowButton("\u2192") { onRdpKey(SC_RIGHT) }
                Spacer(Modifier.width(8.dp))
                RdpKeyButton("Home") { onRdpKey(SC_HOME) }
                RdpKeyButton("End") { onRdpKey(SC_END) }
                RdpKeyButton("PgUp") { onRdpKey(SC_PGUP) }
                RdpKeyButton("PgDn") { onRdpKey(SC_PGDN) }
                Spacer(Modifier.width(8.dp))
                for (i in 1..12) {
                    RdpKeyButton("F$i") { onRdpKey(SC_F1 + i - 1) }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RdpRepeatingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    content: @Composable () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    var didRepeat by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(400)
            didRepeat = true
            while (isPressed) {
                currentOnClick()
                delay(80)
            }
        }
    }

    FilledTonalButton(
        onClick = {},
        // #515 — the same latch fixed in core:toolbar's ToolbarKeyButton, which
        // carries the full explanation. Short version: `action` misses the
        // ACTION_POINTER_* forms a second finger produces, and `didRepeat` has to
        // be cleared on release rather than at the top of the effect. Leaving
        // either latched gives a button that highlights but sends nothing until
        // the screen is recreated.
        modifier = modifier.pointerInteropFilter { motionEvent ->
            when (motionEvent.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    isPressed = true
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    if (!didRepeat) onClick()
                    didRepeat = false
                    isPressed = false
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    didRepeat = false
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

@Composable
private fun RdpKeyButton(label: String, onClick: () -> Unit) {
    RdpRepeatingButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp),
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun RdpArrowButton(label: String, onClick: () -> Unit) {
    RdpRepeatingButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp),
    ) {
        Text(label, fontSize = 16.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RdpToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
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

/** Map Android Compose Key to Windows scancode for special (non-printable) keys. */
/**
 * Three-state orientation cycle for the session toolbar's rotate
 * button. Defaults to Landscape (matches the pre-existing forced-
 * landscape behaviour in DesktopScreen.kt for #109/surf5726). The
 * button cycles Landscape -> Portrait -> Auto and back. `Auto` here
 * means "follow the system / device orientation".
 */
private enum class OrientationMode(
    val activityValue: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val description: String,
) {
    Landscape(
        activityValue = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        icon = Icons.Default.ScreenLockLandscape,
        description = "Lock landscape (tap to switch to portrait)",
    ),
    Portrait(
        activityValue = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        icon = Icons.Default.ScreenLockPortrait,
        description = "Lock portrait (tap to switch to auto)",
    ),
    Auto(
        activityValue = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        icon = Icons.Default.ScreenRotation,
        description = "Auto rotate (tap to switch to landscape)",
    );

    fun next(): OrientationMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromActivityValue(value: Int): OrientationMode = entries.firstOrNull { it.activityValue == value } ?: Landscape
    }
}

/**
 * Cycle the activity-orientation constant `LANDSCAPE -> PORTRAIT ->
 * UNSPECIFIED -> LANDSCAPE`. Public so the Desktop multi-session
 * `DesktopViewModel.cycleDesktopOrientation` can share the same
 * cycle order as the standalone path's button.
 */
fun cycleRdpOrientation(current: Int): Int =
    OrientationMode.fromActivityValue(current).next().activityValue

internal fun androidKeyToScancode(key: Key): Int? = when (key) {
    Key.Enter -> SC_RETURN
    Key.Tab -> SC_TAB
    Key.Escape -> SC_ESCAPE
    Key.Backspace -> SC_BACKSPACE
    Key.Delete -> SC_DELETE
    Key.Insert -> SC_INSERT
    Key.DirectionLeft -> SC_LEFT
    Key.DirectionRight -> SC_RIGHT
    Key.DirectionUp -> SC_UP
    Key.DirectionDown -> SC_DOWN
    Key.MoveHome -> SC_HOME
    Key.MoveEnd -> SC_END
    Key.PageUp -> SC_PGUP
    Key.PageDown -> SC_PGDN
    // Right-hand modifiers are their own keys, not aliases for the left ones
    // (#504). A reporter ran `showkey` on the guest console and saw AltGr
    // arrive as scancode 56 — left Alt — which is why AltGr+o gave him nothing
    // instead of the Polish ó: the guest was told he pressed a modifier that
    // does not compose anything. Right Ctrl, Alt and Win are E0-prefixed;
    // right Shift is NOT extended, it is a separate base code (0x36).
    Key.ShiftLeft -> SC_SHIFT_L
    Key.ShiftRight -> SC_SHIFT_R
    Key.CtrlLeft -> SC_CTRL_L
    Key.CtrlRight -> SC_CTRL_R
    Key.AltLeft -> SC_ALT_L
    Key.AltRight -> SC_ALT_R
    Key.MetaLeft -> SC_WIN_L
    Key.MetaRight -> SC_WIN_R
    Key.F1 -> SC_F1
    Key.F2 -> SC_F2
    Key.F3 -> SC_F3
    Key.F4 -> SC_F4
    Key.F5 -> SC_F5
    Key.F6 -> SC_F6
    Key.F7 -> SC_F7
    Key.F8 -> SC_F8
    Key.F9 -> SC_F9
    Key.F10 -> SC_F10
    Key.F11 -> SC_F11
    Key.F12 -> SC_F12
    // Numpad (#507). None of these were mapped at all, so numpad Enter, the
    // digits and the arrows fell straight through to `else -> null` and reached
    // the guest as nothing — invisible on a phone, but people do attach real
    // keyboards, and a numeric keypad is exactly what someone doing data entry
    // over RDP reaches for.
    //
    // These are the BARE Set-1 codes, and that is not an oversight: the numpad
    // is what those values natively mean. The navigation cluster above borrows
    // the same numbers with the 0xE000 marker precisely because it is the
    // *extended* twin of this block. Numpad Enter and Divide are the two
    // exceptions — they ARE E0-prefixed, sharing their bare codes with Return
    // and the `/` key.
    Key.NumPad0 -> SC_NUMPAD_0
    Key.NumPad1 -> SC_NUMPAD_1
    Key.NumPad2 -> SC_NUMPAD_2
    Key.NumPad3 -> SC_NUMPAD_3
    Key.NumPad4 -> SC_NUMPAD_4
    Key.NumPad5 -> SC_NUMPAD_5
    Key.NumPad6 -> SC_NUMPAD_6
    Key.NumPad7 -> SC_NUMPAD_7
    Key.NumPad8 -> SC_NUMPAD_8
    Key.NumPad9 -> SC_NUMPAD_9
    Key.NumPadEnter -> SC_NUMPAD_ENTER
    Key.NumPadDivide -> SC_NUMPAD_DIVIDE
    Key.NumPadMultiply -> SC_NUMPAD_MULTIPLY
    Key.NumPadSubtract -> SC_NUMPAD_SUBTRACT
    Key.NumPadAdd -> SC_NUMPAD_ADD
    Key.NumPadDot -> SC_NUMPAD_DOT
    Key.NumLock -> SC_NUMLOCK
    // Main-row letters, digits and punctuation (#504). The #507 focus fix moved
    // hardware-key focus off the hidden text field, which had been the only
    // thing converting printable keypresses (via its IME InputConnection), so
    // every key NOT in this table started reaching the guest as nothing —
    // letters, digits and Space died while the mapped special keys kept
    // working.
    //
    // These are BASE scancodes on purpose: physical Shift and AltGr are
    // already forwarded as real modifier keys above, so the guest composes
    // shifted and AltGr characters itself. Wrapping our own Shift around a
    // pre-shifted character (the soft-keyboard path in typeRdpChar) would
    // release a Shift the user is still holding. Values are Set-1 / US
    // positions and must stay in agreement with asciiCharToRdpScancode's
    // tables — RdpScancodeTest cross-checks the two.
    Key.A -> 0x1E
    Key.B -> 0x30
    Key.C -> 0x2E
    Key.D -> 0x20
    Key.E -> 0x12
    Key.F -> 0x21
    Key.G -> 0x22
    Key.H -> 0x23
    Key.I -> 0x17
    Key.J -> 0x24
    Key.K -> 0x25
    Key.L -> 0x26
    Key.M -> 0x32
    Key.N -> 0x31
    Key.O -> 0x18
    Key.P -> 0x19
    Key.Q -> 0x10
    Key.R -> 0x13
    Key.S -> 0x1F
    Key.T -> 0x14
    Key.U -> 0x16
    Key.V -> 0x2F
    Key.W -> 0x11
    Key.X -> 0x2D
    Key.Y -> 0x15
    Key.Z -> 0x2C
    Key.One -> 0x02
    Key.Two -> 0x03
    Key.Three -> 0x04
    Key.Four -> 0x05
    Key.Five -> 0x06
    Key.Six -> 0x07
    Key.Seven -> 0x08
    Key.Eight -> 0x09
    Key.Nine -> 0x0A
    Key.Zero -> 0x0B
    Key.Spacebar -> 0x39
    Key.Grave -> 0x29
    Key.Minus -> 0x0C
    Key.Equals -> 0x0D
    Key.LeftBracket -> 0x1A
    Key.RightBracket -> 0x1B
    Key.Backslash -> 0x2B
    Key.Semicolon -> 0x27
    Key.Apostrophe -> 0x28
    Key.Comma -> 0x33
    Key.Period -> 0x34
    Key.Slash -> 0x35
    else -> null
}


// Windows scancodes (Set 1 / AT keyboard).
//
// Keys in the navigation cluster and the Windows key are E0-prefixed on a
// real keyboard; the bare codes below 0xE000 are their *numpad* twins. The
// native layer reads the 0xE000 marker (ironrdp Scancode::from_u16 treats
// `code & 0xE000 == 0xE000` as extended) and sets KBDFLAGS_EXTENDED, so
// sending the bare value silently presses the wrong key.
//
// NB (#422): the marker fixes which key is pressed, but it was NOT what made
// VirtualBox drop connections — VRDP rejects any lone fast-path scancode PDU
// ("Network packet length is incorrect 0x0004" in VBox.log) because it never
// advertises fast-path input. The native layer now falls back to slow-path
// TS_INPUT_PDUs for such servers.
internal const val EXT = 0xE000

// Numpad, Set 1. Bare values — these ARE the meaning of these codes; the
// navigation cluster above is the E0-prefixed twin of this same block.
internal const val SC_NUMPAD_7 = 0x47
internal const val SC_NUMPAD_8 = 0x48
internal const val SC_NUMPAD_9 = 0x49
internal const val SC_NUMPAD_SUBTRACT = 0x4A
internal const val SC_NUMPAD_4 = 0x4B
internal const val SC_NUMPAD_5 = 0x4C
internal const val SC_NUMPAD_6 = 0x4D
internal const val SC_NUMPAD_ADD = 0x4E
internal const val SC_NUMPAD_1 = 0x4F
internal const val SC_NUMPAD_2 = 0x50
internal const val SC_NUMPAD_3 = 0x51
internal const val SC_NUMPAD_0 = 0x52
internal const val SC_NUMPAD_DOT = 0x53
internal const val SC_NUMPAD_MULTIPLY = 0x37
internal const val SC_NUMLOCK = 0x45
// The two extended ones: their bare codes belong to Return and `/`.
internal const val SC_NUMPAD_ENTER = EXT or 0x1C
internal const val SC_NUMPAD_DIVIDE = EXT or 0x35
internal const val SC_ESCAPE = 0x01
internal const val SC_BACKSPACE = 0x0E
internal const val SC_TAB = 0x0F
internal const val SC_RETURN = 0x1C
internal const val SC_CTRL_L = 0x1D
internal const val SC_SHIFT_L = 0x2A
internal const val SC_ALT_L = 0x38
internal const val SC_DELETE = EXT or 0x53
internal const val SC_INSERT = EXT or 0x52
internal const val SC_HOME = EXT or 0x47
internal const val SC_END = EXT or 0x4F
internal const val SC_PGUP = EXT or 0x49
internal const val SC_PGDN = EXT or 0x51
internal const val SC_UP = EXT or 0x48
internal const val SC_DOWN = EXT or 0x50
internal const val SC_LEFT = EXT or 0x4B
internal const val SC_RIGHT = EXT or 0x4D
internal const val SC_WIN_L = EXT or 0x5B
// Right-hand modifiers (#504). AltGr is right Alt: a layout that puts
// characters on it — Polish, German, most of Europe — produces nothing at all
// when the guest is told left Alt instead.
internal const val SC_SHIFT_R = 0x36
internal const val SC_CTRL_R = EXT or 0x1D
internal const val SC_ALT_R = EXT or 0x38
internal const val SC_WIN_R = EXT or 0x5C
internal const val SC_F1 = 0x3B
private const val SC_F2 = 0x3C
private const val SC_F3 = 0x3D
private const val SC_F4 = 0x3E
private const val SC_F5 = 0x3F
private const val SC_F6 = 0x40
private const val SC_F7 = 0x41
private const val SC_F8 = 0x42
private const val SC_F9 = 0x43
private const val SC_F10 = 0x44
private const val SC_F11 = 0x57
internal const val SC_F12 = 0x58

/** A friendly diagnosis layered on top of an opaque server error string. */
internal data class RdpErrorHint(
    val titleRes: Int,
    val bodyRes: Int,
    val linkLabelRes: Int? = null,
    val linkUrl: String? = null,
)

/**
 * Map raw RDP failure strings to a human-readable diagnosis. Returns null
 * when no specific pattern matches; the caller still shows the raw error
 * underneath either way.
 */
internal fun rdpErrorHint(error: String): RdpErrorHint? = when {
    "STANDARD_RDP_SECURITY" in error -> RdpErrorHint(
        titleRes = R.string.rdp_hint_legacy_rdp_title,
        bodyRes = R.string.rdp_hint_legacy_rdp_body,
        linkLabelRes = R.string.rdp_hint_legacy_rdp_link,
        linkUrl = "https://github.com/GlassHaven/Haven/issues/106#issuecomment-4319030771",
    )
    "AlertReceived(InternalError)" in error -> RdpErrorHint(
        titleRes = R.string.rdp_hint_nla_internal_error_title,
        bodyRes = R.string.rdp_hint_nla_internal_error_body,
    )
    "STATUS_LOGON_FAILURE" in error || "0xc000006d" in error -> RdpErrorHint(
        titleRes = R.string.rdp_hint_logon_failure_title,
        bodyRes = R.string.rdp_hint_logon_failure_body,
    )
    "MessageAltered" in error || "public-key hash" in error -> RdpErrorHint(
        titleRes = R.string.rdp_hint_pubkey_hash_title,
        bodyRes = R.string.rdp_hint_pubkey_hash_body,
        linkLabelRes = R.string.rdp_hint_pubkey_hash_link,
        linkUrl = "https://github.com/GlassHaven/Haven/issues/109",
    )
    "TimeSkew" in error -> RdpErrorHint(
        titleRes = R.string.rdp_hint_time_skew_title,
        bodyRes = R.string.rdp_hint_time_skew_body,
    )
    "no shared TLS parameters" in error || "PeerIncompatible" in error -> RdpErrorHint(
        titleRes = R.string.rdp_hint_tls_ciphers_title,
        bodyRes = R.string.rdp_hint_tls_ciphers_body,
    )
    // RDP_NEG_FAILURE HYBRID_REQUIRED_BY_SERVER (MS-RDPBCGR 2.2.1.2.2): the
    // server insists on NLA and this profile has it off (#461).
    "FailureCode(5)" in error -> RdpErrorHint(
        titleRes = R.string.rdp_hint_nla_required_title,
        bodyRes = R.string.rdp_hint_nla_required_body,
    )
    // sspi-rs Username parser: MicrosoftAccount\you@example.com is refused as
    // MixedFormat, and a bare email is truncated at the @ (#461,
    // Devolutions/sspi-rs#718). Only NLA goes through that parser.
    "MixedFormat" in error || "invalid username" in error -> RdpErrorHint(
        titleRes = R.string.rdp_hint_mixed_username_title,
        bodyRes = R.string.rdp_hint_mixed_username_body,
        linkLabelRes = R.string.rdp_hint_mixed_username_link,
        linkUrl = "https://github.com/GlassHaven/Haven/issues/461",
    )
    else -> null
}

private fun sh.haven.core.data.preferences.RdpChipAnchor.toComposeAlignment(): Alignment = when (this) {
    sh.haven.core.data.preferences.RdpChipAnchor.TOP_START -> Alignment.TopStart
    sh.haven.core.data.preferences.RdpChipAnchor.TOP_CENTER -> Alignment.TopCenter
    sh.haven.core.data.preferences.RdpChipAnchor.TOP_END -> Alignment.TopEnd
    sh.haven.core.data.preferences.RdpChipAnchor.BOTTOM_START -> Alignment.BottomStart
    sh.haven.core.data.preferences.RdpChipAnchor.BOTTOM_CENTER -> Alignment.BottomCenter
    sh.haven.core.data.preferences.RdpChipAnchor.BOTTOM_END -> Alignment.BottomEnd
}
