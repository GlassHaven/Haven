package sh.haven.feature.rdp

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sh.haven.core.data.preferences.ToolbarKey
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.ui.CursorOverlay
import sh.haven.core.ui.DesktopOrientationMode
import sh.haven.core.ui.RemoteDesktopCameraFollowEffect
import sh.haven.core.ui.RemoteDesktopImeField
import sh.haven.core.ui.RemoteDesktopViewport
import sh.haven.core.ui.RemoteScrollModeToggle
import sh.haven.core.ui.RemoteTrackpadSensitivityDialog
import sh.haven.core.ui.applyRemoteViewportPan
import sh.haven.core.ui.applyRemoteViewportPinch
import sh.haven.core.ui.rememberRemoteDesktopViewportState
import sh.haven.core.ui.screenToRemoteDesktop
import sh.haven.feature.rdp.R

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
    navBlockMode: sh.haven.core.data.preferences.NavBlockMode = sh.haven.core.data.preferences.NavBlockMode.ALIGNED,
    toolbarUniformGrid: Boolean = false,
    toolbarMinKeyWidth: Int = 0,
    toolbarKeyScale: Float = 1f,
    editModeControlsPlacement: sh.haven.core.data.preferences.EditModeControlsPlacement = sh.haven.core.data.preferences.EditModeControlsPlacement.LEFT,
    snippetLibrary: List<sh.haven.core.data.preferences.ToolbarItem.Custom> = emptyList(),
    onToolbarLayoutChanged: (ToolbarLayout) -> Unit = {},
    onSnippetLibraryChanged: (List<sh.haven.core.data.preferences.ToolbarItem.Custom>) -> Unit = {},
    onOpenToolbarSettings: () -> Unit = {},
    onTap: (Int, Int) -> Unit,
    /** Two-finger tap or middle mouse button → middle-button click at the tapped point (X11 button 2). */
    onMiddleClick: (Int, Int) -> Unit = { _, _ -> },
    /** Touch long-press or right mouse button click at the target point (X11 button 3, #660, #828). */
    onRightClick: (Int, Int) -> Unit = { _, _ -> },
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onPressButton: (Int) -> Unit = {},
    onReleaseButton: (Int) -> Unit = {},
    onTypeChar: (Char) -> Unit,
    onTypeText: (String) -> Unit = { s -> s.forEach(onTypeChar) },
    onSendClipboard: ((String) -> Unit)? = null,
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
    /** TOUCHPAD-mode speed multiplier; gesture math scales deltas by it (#696). */
    touchpadSensitivity: Float = 1f,
    /** Persist trackpad sensitivity from the toolbar (#696). Null hides the control. */
    onSetTouchpadSensitivity: ((Float) -> Unit)? = null,
    /**
     * Multi-touch MS-RDPEI (`Microsoft::Windows::RDS::Input`) frame callback (#778).
     * Non-null enables `"TOUCHSCREEN"` in the input-mode toggle and forwards
     * all active pointers directly as remote touch contacts.
     */
    onTouchFrame: ((List<sh.haven.core.rdp.RdpTouchContactSample>) -> Unit)? = null,
    onCancelTouches: (() -> Unit)? = null,
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
    isActive: Boolean = true,
    sessionKey: String? = null,
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
            navBlockMode = navBlockMode,
            toolbarUniformGrid = toolbarUniformGrid,
            toolbarMinKeyWidth = toolbarMinKeyWidth,
            toolbarKeyScale = toolbarKeyScale,
            editModeControlsPlacement = editModeControlsPlacement,
            snippetLibrary = snippetLibrary,
            onToolbarLayoutChanged = onToolbarLayoutChanged,
            onSnippetLibraryChanged = onSnippetLibraryChanged,
            onOpenToolbarSettings = onOpenToolbarSettings,
            onTap = onTap,
            onMiddleClick = onMiddleClick,
            onRightClick = onRightClick,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onScrollUp = onScrollUp,
            onScrollDown = onScrollDown,
            onPressButton = onPressButton,
            onReleaseButton = onReleaseButton,
            onTypeChar = onTypeChar,
            onTypeText = onTypeText,
            onSendClipboard = onSendClipboard,
            onKeyDown = onKeyDown,
            onKeyUp = onKeyUp,
            onToggleFullscreen = { fullscreen = !fullscreen },
            onDisconnect = onDisconnect,
            cursor = cursorState,
            pointerPos = pointerState,
            inputMode = inputMode,
            onSetInputMode = onSetInputMode,
            touchpadSensitivity = touchpadSensitivity,
            onSetTouchpadSensitivity = onSetTouchpadSensitivity,
            onTouchFrame = onTouchFrame,
            onCancelTouches = onCancelTouches,
            currentOrientation = currentOrientation,
            onCycleOrientation = onCycleOrientation,
            chipAnchor = chipAnchor,
            onChipAnchorChange = onChipAnchorChange,
            isActive = isActive,
            sessionKey = sessionKey,
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
    navBlockMode: sh.haven.core.data.preferences.NavBlockMode = sh.haven.core.data.preferences.NavBlockMode.ALIGNED,
    toolbarUniformGrid: Boolean = false,
    toolbarMinKeyWidth: Int = 0,
    toolbarKeyScale: Float = 1f,
    editModeControlsPlacement: sh.haven.core.data.preferences.EditModeControlsPlacement = sh.haven.core.data.preferences.EditModeControlsPlacement.LEFT,
    snippetLibrary: List<sh.haven.core.data.preferences.ToolbarItem.Custom> = emptyList(),
    onToolbarLayoutChanged: (ToolbarLayout) -> Unit = {},
    onSnippetLibraryChanged: (List<sh.haven.core.data.preferences.ToolbarItem.Custom>) -> Unit = {},
    onOpenToolbarSettings: () -> Unit = {},
    onTap: (Int, Int) -> Unit,
    onMiddleClick: (Int, Int) -> Unit = { _, _ -> },
    onRightClick: (Int, Int) -> Unit = { _, _ -> },
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onPressButton: (Int) -> Unit = {},
    onReleaseButton: (Int) -> Unit = {},
    onTypeChar: (Char) -> Unit,
    onTypeText: (String) -> Unit = { s -> s.forEach(onTypeChar) },
    onSendClipboard: ((String) -> Unit)? = null,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onToggleFullscreen: () -> Unit,
    onDisconnect: () -> Unit,
    cursor: CursorOverlay? = null,
    pointerPos: Pair<Int, Int> = 0 to 0,
    inputMode: String = "DIRECT",
    onSetInputMode: ((String) -> Unit)? = null,
    /** TOUCHPAD-mode speed multiplier; gesture math scales deltas by it (#696). */
    touchpadSensitivity: Float = 1f,
    /** Persist trackpad sensitivity from the toolbar (#696). Null hides the control. */
    onSetTouchpadSensitivity: ((Float) -> Unit)? = null,
    onTouchFrame: ((List<sh.haven.core.rdp.RdpTouchContactSample>) -> Unit)? = null,
    onCancelTouches: (() -> Unit)? = null,
    currentOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
    onCycleOrientation: () -> Unit = {},
    chipAnchor: sh.haven.core.data.preferences.RdpChipAnchor =
        sh.haven.core.data.preferences.RdpChipAnchor.DEFAULT,
    onChipAnchorChange: (sh.haven.core.data.preferences.RdpChipAnchor) -> Unit = {},
    isActive: Boolean = true,
    sessionKey: String? = null,
) {
    // SPICE and GLKVM reuse RdpViewer without MS-RDPEI; treat TOUCHSCREEN as
    // DIRECT when no multi-touch channel is attached (#778).
    val touchscreenCapable = onTouchFrame != null
    val effectiveInputMode = effectiveRdpInputMode(inputMode, touchscreenCapable)
    // In TOUCHSCREEN mode (#778), all gestures normally forward to the remote
    // digitizer. Toggling this toolbar button temporarily switches canvas
    // gestures to local viewport navigation (pinch to zoom, 1- or 2-finger drag
    // to pan) without leaving TOUCHSCREEN mode.
    var touchscreenViewportNav by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(effectiveInputMode) {
        if (effectiveInputMode != "TOUCHSCREEN") {
            touchscreenViewportNav = false
            onCancelTouches?.invoke()
        }
    }
    LaunchedEffect(touchscreenViewportNav) {
        if (touchscreenViewportNav) {
            onCancelTouches?.invoke()
        }
    }
    val orientationMode = DesktopOrientationMode.fromActivityValue(currentOrientation)
    val orientationDesc = when (orientationMode) {
        DesktopOrientationMode.Landscape -> stringResource(R.string.rdp_orientation_landscape_desc)
        DesktopOrientationMode.Portrait -> stringResource(R.string.rdp_orientation_portrait_desc)
        DesktopOrientationMode.Auto -> stringResource(R.string.rdp_orientation_auto_desc)
    }
    val viewportState = rememberRemoteDesktopViewportState(
        inputMode = effectiveInputMode,
        pointerPos = pointerPos,
        saveable = true,
    )
    // Two-finger drag target: false = pan the local viewport (when zoomed),
    // true = forward as remote scroll-wheel. Pinch always zooms. Toolbar
    // toggle (#286 — 3-finger gestures are unreliable on OnePlus/OxygenOS).
    var twoFingerScroll by rememberSaveable { mutableStateOf(false) }

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
    val (externalCursorX, externalCursorY) =
        if (effectiveInputMode == "TOUCHPAD") viewportState.virtualCursor else pointerPos
    val externalDisplay = sh.haven.core.ui.rememberExternalDisplayProjection(
        frame = frame,
        frameSeq = frameSeq.value,
        cursor = cursor,
        pointerX = externalCursorX,
        pointerY = externalCursorY,
        showCursor = effectiveInputMode != "TOUCHSCREEN",
        showFallbackCursor = pointerActive && !hasReceivedCursorShape && !fallbackExpired,
        sessionKey = sessionKey,
    )

    RemoteDesktopCameraFollowEffect(
        state = viewportState,
        cursorPos = viewportState.virtualCursor,
        enabled = effectiveInputMode == "TOUCHPAD",
        fbWidth = frame.width,
        fbHeight = frame.height,
    )

    // Keyboard
    // #511: where a real keyboard is attached and usable, the keyboard icon
    // must not raise the IME — it enables hardware-key passthrough instead.
    // The soft keyboard over a desktop is an obstruction, not the input
    // method. hardKeyboardHidden is part of the test on purpose: a
    // docked/folded device reports KEYBOARD_QWERTY with the keys physically
    // inaccessible, and there the soft keyboard is the only way to type.
    // Same predicate the terminal tab uses for #511 (TerminalScreen).
    val screenConfiguration = LocalConfiguration.current
    val physicalKeyboardAttached =
        screenConfiguration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            screenConfiguration.hardKeyboardHidden ==
            android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // With a hardware keyboard attached the screen starts in passthrough-on
    // (AVNC parity: click inside the desktop and type immediately); without
    // one it starts with the IME down, as before. Keyed on the predicate so a
    // mid-session hotplug resets the bit to the new mode's default.
    var keyboardVisible by remember(physicalKeyboardAttached) { mutableStateOf(physicalKeyboardAttached) }

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
    LaunchedEffect(isActive, physicalKeyboardAttached) {
        if (isActive) {
            if (keyboardVisible == physicalKeyboardAttached) hardwareKeyFocus.requestFocus()
        } else {
            if (!physicalKeyboardAttached) keyboardVisible = false
        }
    }

    // The keyboard icon's meaning is context-dependent (#511). With a
    // hardware keyboard attached it toggles passthrough: on = the canvas
    // holds focus and keys go straight to the remote (the IME is never
    // raised); off = focus is released so keys stop reaching the session.
    // Without a hardware keyboard it is the soft-keyboard show/hide toggle
    // it has always been. keyboardVisible doubles as the on/off bit for
    // both modes; the IME-invariant effect below only acts while the IME
    // is actually up, so reusing it here is safe.
    val toggleKeyboard: () -> Unit = {
        keyboardVisible = !keyboardVisible
        if (physicalKeyboardAttached) {
            if (keyboardVisible) {
                hardwareKeyFocus.requestFocus()
            } else {
                focusManager.clearFocus(force = true)
            }
        } else if (keyboardVisible) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            hardwareKeyFocus.requestFocus()
        }
    }

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
    var wasImeVisible by remember { mutableStateOf(imeVisible) }
    var toolbarPopupOpen by remember { mutableStateOf(false) }
    var wasToolbarPopupOpen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, toolbarPopupOpen) {
        val step = sh.haven.core.toolbar.stepDesktopImeVisibility(
            keyboardVisible = keyboardVisible,
            wasImeVisible = wasImeVisible,
            imeVisible = imeVisible,
            physicalKeyboardAttached = physicalKeyboardAttached,
            toolbarPopupOpen = toolbarPopupOpen,
            wasToolbarPopupOpen = wasToolbarPopupOpen,
        )
        keyboardVisible = step.keyboardVisible
        wasImeVisible = step.wasImeVisible
        wasToolbarPopupOpen = toolbarPopupOpen
        if (step.transferFocusToCanvas && isActive && (fieldFocused || canvasFocused)) {
            hardwareKeyFocus.requestFocus()
        } else if (step.restoreSoftKeyboard && isActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    LaunchedEffect(imeVisible, keyboardVisible, canvasFocused, fieldFocused, isActive, toolbarPopupOpen) {
        if (isActive && !keyboardVisible && !toolbarPopupOpen && imeVisible && (canvasFocused || fieldFocused)) {
            keyboardController?.hide()
            hardwareKeyFocus.requestFocus()
        }
    }
    val currentImeVisible by rememberUpdatedState(imeVisible)
    val currentFieldFocused by rememberUpdatedState(fieldFocused)
    val currentKeyboardVisible by rememberUpdatedState(keyboardVisible)
    val currentPhysicalKeyboardAttached by rememberUpdatedState(physicalKeyboardAttached)

    // Modifier state for key toolbar
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    var winActive by remember { mutableStateOf(false) }

    // Fullscreen overlay toolbar
    var overlayVisible by remember { mutableStateOf(false) }
    // Trackpad sensitivity dialog (#696) — opened from the speed button next
    // to the input-mode toggle while TOUCHPAD is active.
    var showSensitivityDialog by remember { mutableStateOf(false) }

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

    val clipboardManager = LocalClipboardManager.current
    val pasteClipboard: () -> Unit = {
        val text = clipboardManager.getText()?.text
        if (!text.isNullOrEmpty()) {
            onSendClipboard?.invoke(text)
            onTypeText(text)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(handleHardwareKey),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Shared remote-desktop canvas & gesture state machine (:core:ui)
        RemoteDesktopViewport(
            frame = frame,
            state = viewportState,
            inputMode = effectiveInputMode,
            touchpadSensitivity = touchpadSensitivity,
            twoFingerScroll = twoFingerScroll,
            minZoom = 1f,
            maxZoom = 5f,
            frameSeq = frameSeq,
            isProjectingExternal = externalDisplay.isProjecting,
            cursor = cursor,
            pointerPos = pointerPos,
            showCursor = effectiveInputMode != "TOUCHSCREEN",
            showFallbackCursor = pointerActive && !hasReceivedCursorShape && !fallbackExpired,
            useVirtualCursorPositionForOverlay = effectiveInputMode == "TOUCHPAD",
            hardwareKeyFocus = hardwareKeyFocus,
            onCanvasFocusChanged = { canvasFocused = it },
            shouldReclaimHardwareKeyFocusOnTap = {
                !currentImeVisible && !currentFieldFocused &&
                    currentKeyboardVisible == currentPhysicalKeyboardAttached
            },
            onPointerActivated = { pointerActive = true },
            onTap = onTap,
            onMiddleClick = onMiddleClick,
            onRightClick = onRightClick,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onScrollUp = onScrollUp,
            onScrollDown = onScrollDown,
            onPressButton = onPressButton,
            onReleaseButton = onReleaseButton,
            gestureKey = touchscreenViewportNav,
            customTouchGestureHandler = { firstDown, viewSize ->
                if (shouldForwardTouchscreenContacts(effectiveInputMode, onTouchFrame != null, touchscreenViewportNav)) {
                    // #778: native MS-RDPEI multi-touch passthrough.
                    val touchCallback = onTouchFrame!!
                    val activeTouchIds = HashSet<Long>()
                    try {
                        val firstPos = screenToRemoteDesktop(
                            offset = firstDown.position,
                            viewSize = viewSize,
                            fbWidth = frame.width,
                            fbHeight = frame.height,
                            zoom = viewportState.zoom,
                            panX = viewportState.panX,
                            panY = viewportState.panY,
                        )
                        activeTouchIds.add(firstDown.id.value)
                        touchCallback(
                            listOf(
                                sh.haven.core.rdp.RdpTouchContactSample(
                                    pointerId = firstDown.id.value,
                                    phase = sh.haven.core.rdp.RdpTouchContactPhase.STARTED,
                                    x = firstPos.first,
                                    y = firstPos.second,
                                ),
                            ),
                        )
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val samples = ArrayList<sh.haven.core.rdp.RdpTouchContactSample>(event.changes.size)
                            for (change in event.changes) {
                                val id = change.id.value
                                val pos = screenToRemoteDesktop(
                                    offset = change.position,
                                    viewSize = viewSize,
                                    fbWidth = frame.width,
                                    fbHeight = frame.height,
                                    zoom = viewportState.zoom,
                                    panX = viewportState.panX,
                                    panY = viewportState.panY,
                                )
                                if (change.pressed) {
                                    val isNew = activeTouchIds.add(id)
                                    samples.add(
                                        sh.haven.core.rdp.RdpTouchContactSample(
                                            pointerId = id,
                                            phase = if (isNew) {
                                                sh.haven.core.rdp.RdpTouchContactPhase.STARTED
                                            } else {
                                                sh.haven.core.rdp.RdpTouchContactPhase.MOVED
                                            },
                                            x = pos.first,
                                            y = pos.second,
                                        ),
                                    )
                                } else if (activeTouchIds.remove(id)) {
                                    samples.add(
                                        sh.haven.core.rdp.RdpTouchContactSample(
                                            pointerId = id,
                                            phase = sh.haven.core.rdp.RdpTouchContactPhase.ENDED,
                                            x = pos.first,
                                            y = pos.second,
                                        ),
                                    )
                                }
                                change.consume()
                            }
                            if (samples.isNotEmpty()) {
                                touchCallback(samples)
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        if (activeTouchIds.isNotEmpty()) {
                            onCancelTouches?.invoke()
                        }
                    }
                    true
                } else if (effectiveInputMode == "TOUCHSCREEN" && touchscreenViewportNav) {
                    // #778 follow-up: temporary local viewport zoom/pan inside TOUCHSCREEN mode.
                    var totalFingers = 1
                    var prevCentroid = firstDown.position
                    var prevSpan = 0f
                    var prevCount = 0
                    var gestureStarted = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointers = event.changes.filter { it.pressed }
                        val count = pointers.size
                        if (count >= 2) {
                            totalFingers = maxOf(totalFingers, count)
                            val centroid = Offset(
                                pointers.map { it.position.x }.average().toFloat(),
                                pointers.map { it.position.y }.average().toFloat(),
                            )
                            val span = pointers.map {
                                (it.position - centroid).getDistance()
                            }.average().toFloat()
                            if (gestureStarted && count == prevCount) {
                                val dx = centroid.x - prevCentroid.x
                                val dy = centroid.y - prevCentroid.y
                                val spanDelta = if (prevSpan > 0f) abs(span - prevSpan) else 0f
                                val pinching = spanDelta > 2f && spanDelta >= abs(dx) + abs(dy)
                                if (pinching && prevSpan > 0f && span > 0f) {
                                    viewportState.applyTransform(
                                        applyRemoteViewportPinch(
                                            zoom = viewportState.zoom,
                                            panX = viewportState.panX,
                                            panY = viewportState.panY,
                                            prevSpan = prevSpan,
                                            span = span,
                                            centroid = centroid,
                                            viewWidth = viewSize.width.toFloat(),
                                            viewHeight = viewSize.height.toFloat(),
                                            fbWidth = frame.width,
                                            fbHeight = frame.height,
                                        ),
                                    )
                                } else if (viewportState.zoom > 1f) {
                                    viewportState.applyTransform(
                                        applyRemoteViewportPan(
                                            zoom = viewportState.zoom,
                                            panX = viewportState.panX,
                                            panY = viewportState.panY,
                                            dx = dx,
                                            dy = dy,
                                            viewWidth = viewSize.width.toFloat(),
                                            viewHeight = viewSize.height.toFloat(),
                                            fbWidth = frame.width,
                                            fbHeight = frame.height,
                                        ),
                                    )
                                }
                            }
                            gestureStarted = true
                            prevCentroid = centroid
                            prevSpan = span
                            prevCount = count
                            pointers.forEach { it.consume() }
                        } else if (count == 1 && totalFingers == 1) {
                            val change = pointers.first()
                            val delta = change.positionChange()
                            if (viewportState.zoom > 1f && (delta.x != 0f || delta.y != 0f)) {
                                viewportState.applyTransform(
                                    applyRemoteViewportPan(
                                        zoom = viewportState.zoom,
                                        panX = viewportState.panX,
                                        panY = viewportState.panY,
                                        dx = delta.x,
                                        dy = delta.y,
                                        viewWidth = viewSize.width.toFloat(),
                                        viewHeight = viewSize.height.toFloat(),
                                        fbWidth = frame.width,
                                        fbHeight = frame.height,
                                    ),
                                )
                            }
                            change.consume()
                        } else {
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    true
                } else {
                    false
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        val releaseActiveModifiers: () -> Unit = {
            if (ctrlActive) { onKeyUp(SC_CTRL_L); ctrlActive = false }
            if (altActive) { onKeyUp(SC_ALT_L); altActive = false }
            if (shiftActive) { onKeyUp(SC_SHIFT_L); shiftActive = false }
            if (winActive) { onKeyUp(SC_WIN_L); winActive = false }
        }

        RemoteDesktopImeField(
            focusRequester = focusRequester,
            onFieldFocusChanged = { fieldFocused = it },
            hasActiveModifier = ctrlActive || altActive || shiftActive || winActive,
            onBackspace = {
                onKeyDown(SC_BACKSPACE)
                onKeyUp(SC_BACKSPACE)
            },
            onTypeChar = onTypeChar,
            onTypeText = onTypeText,
            releaseActiveModifiers = releaseActiveModifiers,
        )

        var reorderMode by remember { mutableStateOf(false) }

        // RDP/SPICE/GLKVM key extension rows. Shown when the user has toggled
        // the keyboard on (keyboardVisible) OR the soft keyboard's insets are
        // actually present (imeVisible), including in fullscreen (parity with
        // VncScreen #171). Uses the shared KeyboardToolbar from :core:toolbar
        // so the layout, column alignment, uniform grid, and key scale match
        // the Terminal tab's extended key rows.
        if (keyboardVisible || imeVisible) {
            sh.haven.core.toolbar.KeyboardToolbar(
                onSendBytes = { bytes ->
                    handleRdpToolbarBytes(
                        bytes = bytes,
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        shiftActive = shiftActive,
                        winActive = winActive,
                        onKeyDown = onKeyDown,
                        onKeyUp = onKeyUp,
                        onTypeChar = onTypeChar,
                        onTypeText = onTypeText,
                        releaseModifiers = releaseActiveModifiers,
                    )
                },
                onDispatchKey = { _, key ->
                    val scancode = vtermKeyToScancode(key)
                    if (scancode != null) {
                        onKeyDown(scancode)
                        onKeyUp(scancode)
                        releaseActiveModifiers()
                    }
                },
                focusRequester = focusRequester,
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
                onShiftUsed = {
                    if (shiftActive) {
                        onKeyUp(SC_SHIFT_L)
                        shiftActive = false
                    }
                },
                onToggleWin = {
                    winActive = !winActive
                    if (!winActive) onKeyUp(SC_WIN_L) else onKeyDown(SC_WIN_L)
                },
                layout = toolbarLayout,
                navBlockMode = navBlockMode,
                uniformGrid = toolbarUniformGrid,
                editModeControlsPlacement = editModeControlsPlacement,
                minKeyWidth = toolbarMinKeyWidth.dp,
                keyScale = toolbarKeyScale,
                onPaste = { text ->
                    if (text.isNotEmpty()) {
                        onSendClipboard?.invoke(text)
                        onTypeText(text)
                        releaseActiveModifiers()
                    }
                },
                reorderMode = reorderMode,
                onReorderModeChanged = { reorderMode = it },
                onToolbarLayoutChanged = onToolbarLayoutChanged,
                snippetLibrary = snippetLibrary,
                onSnippetLibraryChanged = onSnippetLibraryChanged,
                onOpenSettings = onOpenToolbarSettings,
                onToggleKeyboard = toggleKeyboard,
                onPopupOpenChanged = { toolbarPopupOpen = it },
                modifier = Modifier.fillMaxWidth(),
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

                IconButton(onClick = toggleKeyboard) {
                    Icon(
                        if (keyboardVisible) Icons.Default.KeyboardHide
                        else Icons.Default.Keyboard,
                        contentDescription = stringResource(R.string.rdp_cd_toggle_keyboard),
                    )
                }

                IconButton(onClick = pasteClipboard) {
                    Icon(Icons.Default.ContentPaste, contentDescription = ToolbarKey.PASTE.label)
                }

                IconButton(onClick = onCycleOrientation) {
                    Icon(orientationMode.icon, contentDescription = orientationDesc)
                }

                // Direct/trackpad/touchscreen input-mode toggle + speed button — #183/#696/#778.
                InputModeToggle(
                    effectiveInputMode, onSetInputMode, onSetTouchpadSensitivity,
                    onOpenSensitivity = { showSensitivityDialog = true },
                    touchscreenCapable = touchscreenCapable,
                )
                // In TOUCHSCREEN mode (#778), toggle temporary local viewport
                // navigation (pinch to zoom, drag to pan) vs forwarding all
                // touches to the remote digitizer. In DIRECT/TOUCHPAD mode,
                // toggle two-finger drag between viewport pan and remote scroll (#286).
                if (effectiveInputMode == "TOUCHSCREEN") {
                    TouchscreenViewportNavToggle(touchscreenViewportNav) {
                        touchscreenViewportNav = !touchscreenViewportNav
                    }
                } else {
                    RemoteScrollModeToggle(
                        twoFingerScroll = twoFingerScroll,
                        scrollOnDescription = stringResource(R.string.rdp_cd_two_finger_scroll_on),
                        viewportDescription = stringResource(R.string.rdp_cd_two_finger_viewport),
                    ) { twoFingerScroll = !twoFingerScroll }
                }
                if (externalDisplay.hasExternalDisplay) {
                    sh.haven.core.ui.ExternalDisplayToggle(externalDisplay.isProjecting) {
                        externalDisplay.toggleProjection()
                    }
                }

                Spacer(Modifier.weight(1f))

                if (viewportState.isTransformed) {
                    IconButton(onClick = { viewportState.resetTransform() }) {
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
                    IconButton(onClick = toggleKeyboard) {
                        Icon(
                            if (keyboardVisible) Icons.Default.KeyboardHide
                            else Icons.Default.Keyboard,
                            contentDescription = stringResource(R.string.rdp_cd_toggle_keyboard),
                        )
                    }
                    IconButton(onClick = {
                        overlayVisible = false
                        pasteClipboard()
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = ToolbarKey.PASTE.label)
                    }
                    IconButton(onClick = onCycleOrientation) {
                        Icon(orientationMode.icon, contentDescription = orientationDesc)
                    }
                    // Direct/trackpad/touchscreen input-mode toggle + speed button — #183/#696/#778.
                    InputModeToggle(
                        effectiveInputMode, onSetInputMode, onSetTouchpadSensitivity, vertical = true,
                        onOpenSensitivity = { showSensitivityDialog = true },
                        touchscreenCapable = touchscreenCapable,
                    )
                    // In TOUCHSCREEN mode (#778), toggle temporary local viewport
                    // navigation vs remote touch passthrough; otherwise two-finger scroll/pan (#286).
                    if (effectiveInputMode == "TOUCHSCREEN") {
                        TouchscreenViewportNavToggle(touchscreenViewportNav) {
                            touchscreenViewportNav = !touchscreenViewportNav
                        }
                    } else {
                        RemoteScrollModeToggle(
                            twoFingerScroll = twoFingerScroll,
                            scrollOnDescription = stringResource(R.string.rdp_cd_two_finger_scroll_on),
                            viewportDescription = stringResource(R.string.rdp_cd_two_finger_viewport),
                        ) { twoFingerScroll = !twoFingerScroll }
                    }
                    if (externalDisplay.hasExternalDisplay) {
                        sh.haven.core.ui.ExternalDisplayToggle(externalDisplay.isProjecting) {
                            externalDisplay.toggleProjection()
                        }
                    }
                    if (viewportState.isTransformed) {
                        IconButton(onClick = { viewportState.resetTransform() }) {
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

    if (showSensitivityDialog) {
        RemoteTrackpadSensitivityDialog(
            sensitivity = touchpadSensitivity,
            title = stringResource(R.string.rdp_cd_trackpad_sensitivity),
            closeLabel = stringResource(R.string.rdp_action_close),
            onSetSensitivity = { onSetTouchpadSensitivity?.invoke(it) },
            onDismiss = { showSensitivityDialog = false },
        )
    }
}

internal fun effectiveRdpInputMode(inputMode: String, touchscreenCapable: Boolean): String =
    if (inputMode == "TOUCHSCREEN" && !touchscreenCapable) "DIRECT" else inputMode

internal fun nextRdpInputMode(inputMode: String, touchscreenCapable: Boolean): String = when {
    inputMode == "TOUCHPAD" && touchscreenCapable -> "TOUCHSCREEN"
    inputMode == "TOUCHPAD" -> "DIRECT"
    touchscreenCapable && inputMode == "TOUCHSCREEN" -> "DIRECT"
    else -> "TOUCHPAD"
}

/**
 * True when canvas gestures should be forwarded to the remote MS-RDPEI
 * digitizer (#778); false when [touchscreenViewportNav] is active (so pinch
 * and drag adjust the local viewport instead of sending remote touches) or
 * when not in TOUCHSCREEN mode.
 */
internal fun shouldForwardTouchscreenContacts(
    effectiveInputMode: String,
    hasTouchCallback: Boolean,
    touchscreenViewportNav: Boolean,
): Boolean = effectiveInputMode == "TOUCHSCREEN" && hasTouchCallback && !touchscreenViewportNav

internal fun remainingTouchLongPressTimeoutMs(
    isMouseDevice: Boolean,
    totalFingers: Int,
    longPressFired: Boolean,
    dragging: Boolean,
    totalMovement: Float,
    touchSlopPx: Float,
    isFollowUpTouch: Boolean,
    longPressMs: Long,
    elapsedMs: Long,
): Long = sh.haven.core.ui.remainingRemoteTouchLongPressTimeoutMs(
    isMouseDevice = isMouseDevice,
    totalFingers = totalFingers,
    longPressFired = longPressFired,
    dragging = dragging,
    totalMovement = totalMovement,
    touchSlopPx = touchSlopPx,
    isFollowUpTouch = isFollowUpTouch,
    longPressMs = longPressMs,
    elapsedMs = elapsedMs,
)

internal fun shouldFireTouchLongPressRightClick(
    isMouseDevice: Boolean,
    totalFingers: Int,
    longPressFired: Boolean,
    dragging: Boolean,
    totalMovement: Float,
    touchSlopPx: Float,
    isFollowUpTouch: Boolean,
): Boolean = sh.haven.core.ui.shouldFireRemoteTouchLongPressRightClick(
    isMouseDevice = isMouseDevice,
    totalFingers = totalFingers,
    longPressFired = longPressFired,
    dragging = dragging,
    totalMovement = totalMovement,
    touchSlopPx = touchSlopPx,
    isFollowUpTouch = isFollowUpTouch,
)

internal inline fun resolveTouchLongPressTarget(
    effectiveInputMode: String,
    virtualCursor: Pair<Int, Int>,
    directPos: () -> Pair<Int, Int>,
): Pair<Int, Int> = sh.haven.core.ui.resolveRemoteTouchLongPressTarget(
    effectiveInputMode = effectiveInputMode,
    virtualCursor = virtualCursor,
    directPos = directPos,
)

internal fun shouldFirePostGestureTap(
    totalFingers: Int,
    totalMovement: Float,
    touchSlopPx: Float,
    longPressFired: Boolean,
): Boolean = sh.haven.core.ui.shouldFireRemotePostGestureTap(
    totalFingers = totalFingers,
    totalMovement = totalMovement,
    touchSlopPx = touchSlopPx,
    longPressFired = longPressFired,
)

internal fun isHardwareMouseGesture(
    pointerType: PointerType,
    buttons: PointerButtons,
    motionSource: Int,
): Boolean = sh.haven.core.ui.isRemoteHardwareMouseGesture(pointerType, buttons, motionSource)

internal fun resolveHardwareMouseButton(
    buttons: PointerButtons,
    motionButtonState: Int,
): Int = sh.haven.core.ui.resolveRemoteHardwareMouseButton(buttons, motionButtonState)

internal typealias DesktopWheelNotchAccumulator = sh.haven.core.ui.RemoteWheelNotchAccumulator

internal typealias ViewportTransform = sh.haven.core.ui.RemoteViewportTransform

internal fun applyViewportPinch(
    zoom: Float,
    panX: Float,
    panY: Float,
    prevSpan: Float,
    span: Float,
    centroid: Offset,
    viewWidth: Float,
    viewHeight: Float,
    fbWidth: Int,
    fbHeight: Int,
): ViewportTransform = applyRemoteViewportPinch(
    zoom = zoom,
    panX = panX,
    panY = panY,
    prevSpan = prevSpan,
    span = span,
    centroid = centroid,
    viewWidth = viewWidth,
    viewHeight = viewHeight,
    fbWidth = fbWidth,
    fbHeight = fbHeight,
)

internal fun applyViewportPan(
    zoom: Float,
    panX: Float,
    panY: Float,
    dx: Float,
    dy: Float,
    viewWidth: Float,
    viewHeight: Float,
    fbWidth: Int,
    fbHeight: Int,
): ViewportTransform = applyRemoteViewportPan(
    zoom = zoom,
    panX = panX,
    panY = panY,
    dx = dx,
    dy = dy,
    viewWidth = viewWidth,
    viewHeight = viewHeight,
    fbWidth = fbWidth,
    fbHeight = fbHeight,
)

/**
 * Cycle DIRECT (absolute: finger = cursor) -> TOUCHPAD (relative: drag glides
 * the cursor) -> TOUCHSCREEN (native MS-RDPEI multi-touch passthrough, when
 * [touchscreenCapable]) from the viewer (#183/#212/#778).
 * Checked (highlighted) = trackpad or touchscreen mode.
 */
@Composable
private fun InputModeToggle(
    inputMode: String,
    onSetInputMode: ((String) -> Unit)?,
    onSetTouchpadSensitivity: ((Float) -> Unit)?,
    onOpenSensitivity: () -> Unit,
    vertical: Boolean = false,
    touchscreenCapable: Boolean = false,
) {
    if (onSetInputMode == null) return
    val touchpad = inputMode == "TOUCHPAD"
    val touchscreen = touchscreenCapable && inputMode == "TOUCHSCREEN"
    val nextMode = nextRdpInputMode(inputMode, touchscreenCapable)
    // #696 reporter follow-up: in the corner-docked fullscreen overlay the
    // toggle sits inside a vertical panel — stacking here keeps the strip's
    // width at one button instead of widening it for a horizontal row.
    val content: @Composable () -> Unit = {
        FilledIconToggleButton(
            checked = touchpad || touchscreen,
            onCheckedChange = { onSetInputMode(nextMode) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                if (touchscreen) Icons.Default.PanTool else Icons.Default.TouchApp,
                contentDescription = when {
                    touchscreen -> stringResource(R.string.rdp_cd_input_mode_touchscreen_on)
                    touchpad -> stringResource(R.string.rdp_cd_input_mode_trackpad_on)
                    else -> stringResource(R.string.rdp_cd_input_mode_direct)
                },
            )
        }
        // Speed control (#696): only while trackpad mode is on — direct touch
        // and native touchscreen have no speed to tune. Opens the sensitivity dialog.
        if (touchpad && onSetTouchpadSensitivity != null) {
            val speedDesc = stringResource(R.string.rdp_cd_trackpad_sensitivity)
            IconButton(onClick = onOpenSensitivity) {
                Icon(Icons.Default.Speed, contentDescription = speedDesc)
            }
        }
    }
    if (vertical) {
        Column { content() }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/**
 * In native TOUCHSCREEN mode (#778), temporarily switch canvas gestures from
 * remote MS-RDPEI touch passthrough to local viewport zoom/pan (pinch to zoom,
 * drag to pan) without leaving TOUCHSCREEN mode.
 */
@Composable
private fun TouchscreenViewportNavToggle(active: Boolean, onToggle: () -> Unit) {
    FilledIconToggleButton(
        checked = active,
        onCheckedChange = { onToggle() },
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            Icons.Default.OpenWith,
            contentDescription = if (active) {
                stringResource(R.string.rdp_cd_touchscreen_viewport_nav_on)
            } else {
                stringResource(R.string.rdp_cd_touchscreen_viewport_nav_off)
            },
        )
    }
}

internal fun cameraFollow(
    cursorFbX: Int, cursorFbY: Int,
    fbWidth: Int, fbHeight: Int,
    viewW: Float, viewH: Float,
    zoom: Float, panX: Float, panY: Float,
    deadZoneFraction: Float = 0.30f,
): Pair<Float, Float> = sh.haven.core.ui.remoteCameraFollow(
    cursorFbX = cursorFbX,
    cursorFbY = cursorFbY,
    fbWidth = fbWidth,
    fbHeight = fbHeight,
    viewW = viewW,
    viewH = viewH,
    zoom = zoom,
    panX = panX,
    panY = panY,
    deadZoneFraction = deadZoneFraction,
)

/** Map a libvterm key constant from KeyboardToolbar.onDispatchKey to a Set-1 scancode. */
internal fun vtermKeyToScancode(key: Int): Int? = when (key) {
    5 -> SC_UP
    6 -> SC_DOWN
    7 -> SC_LEFT
    8 -> SC_RIGHT
    9 -> SC_INSERT
    10 -> SC_DELETE
    11 -> SC_HOME
    12 -> SC_END
    13 -> SC_PGUP
    14 -> SC_PGDN
    in 257..268 -> {
        val fn = key - 256 // 1..12
        when {
            fn <= 10 -> SC_F1 + (fn - 1)
            fn == 11 -> SC_F11
            else -> SC_F12
        }
    }
    else -> null
}

/** Translate KeyboardToolbar.onSendBytes payloads into RDP/SPICE/GLKVM key events. */
internal fun handleRdpToolbarBytes(
    bytes: ByteArray,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    winActive: Boolean = false,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onTypeChar: (Char) -> Unit,
    onTypeText: (String) -> Unit,
    releaseModifiers: () -> Unit,
) {
    if (bytes.isEmpty()) return
    // Strip leading ESC (0x1b) added by KeyboardToolbar.sendChar when Alt is active
    // and a single-char symbol/custom key was tapped (SC_ALT_L is already held down).
    val payload = if (altActive && bytes.size == 2 && bytes[0] == 0x1b.toByte() && bytes[1] != '['.code.toByte()) {
        byteArrayOf(bytes[1])
    } else {
        bytes
    }
    val str = String(payload, Charsets.UTF_8)
    when {
        payload.contentEquals(byteArrayOf(0x1b)) -> {
            onKeyDown(SC_ESCAPE)
            onKeyUp(SC_ESCAPE)
            releaseModifiers()
        }
        payload.contentEquals(byteArrayOf(0x09)) -> {
            onKeyDown(SC_TAB)
            onKeyUp(SC_TAB)
            releaseModifiers()
        }
        payload.contentEquals(byteArrayOf(0x0d)) -> {
            onKeyDown(SC_RETURN)
            onKeyUp(SC_RETURN)
            releaseModifiers()
        }
        str == "\u001b[Z" -> {
            if (!shiftActive) onKeyDown(SC_SHIFT_L)
            onKeyDown(SC_TAB)
            onKeyUp(SC_TAB)
            if (!shiftActive) onKeyUp(SC_SHIFT_L)
            releaseModifiers()
        }
        str == "\u001b[3;8~" -> {
            if (!ctrlActive) onKeyDown(SC_CTRL_L)
            if (!altActive) onKeyDown(SC_ALT_L)
            onKeyDown(SC_DELETE)
            onKeyUp(SC_DELETE)
            if (!altActive) onKeyUp(SC_ALT_L)
            if (!ctrlActive) onKeyUp(SC_CTRL_L)
            releaseModifiers()
        }
        str == "\u001b[3;6~" -> {
            if (!ctrlActive) onKeyDown(SC_CTRL_L)
            onKeyDown(SC_DELETE)
            onKeyUp(SC_DELETE)
            if (!ctrlActive) onKeyUp(SC_CTRL_L)
            releaseModifiers()
        }
        str == "\u001b[2;5~" -> {
            if (!ctrlActive) onKeyDown(SC_CTRL_L)
            onKeyDown(SC_INSERT)
            onKeyUp(SC_INSERT)
            if (!ctrlActive) onKeyUp(SC_CTRL_L)
            releaseModifiers()
        }
        str == "\u001b[2;2~" -> {
            if (!shiftActive) onKeyDown(SC_SHIFT_L)
            onKeyDown(SC_INSERT)
            onKeyUp(SC_INSERT)
            if (!shiftActive) onKeyUp(SC_SHIFT_L)
            releaseModifiers()
        }
        payload.size == 1 && payload[0] in 1..26 -> {
            val ch = 'a' + (payload[0].toInt() - 1)
            if (!ctrlActive) {
                onKeyDown(SC_CTRL_L)
                onTypeChar(ch)
                onKeyUp(SC_CTRL_L)
                releaseModifiers()
            } else {
                onTypeChar(ch)
                if (winActive || shiftActive) releaseModifiers()
            }
        }
        payload.size == 1 && (ctrlActive || altActive) -> {
            onTypeChar(payload[0].toInt().toChar())
            if (winActive || shiftActive) releaseModifiers()
        }
        payload.size == 1 && (winActive || shiftActive) -> {
            onTypeChar(payload[0].toInt().toChar())
            releaseModifiers()
        }
        else -> {
            onTypeText(str)
            releaseModifiers()
        }
    }
}

internal fun diffImeCommit(oldText: String, newText: String): Pair<String?, Int> =
    sh.haven.core.ui.diffRemoteImeCommit(oldText, newText)

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
