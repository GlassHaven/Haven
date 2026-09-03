package sh.haven.feature.vnc

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.preferences.ToolbarKey
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.ui.CursorOverlay
import sh.haven.feature.vnc.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stateless VNC session content — takes StateFlows and input lambdas directly.
 * Used by DesktopViewModel's multi-tab system. All connection management
 * is handled externally; this composable only renders and forwards input.
 */
@Composable
fun VncSessionContent(
    connected: StateFlow<Boolean>,
    frame: StateFlow<Bitmap?>,
    error: StateFlow<String?>,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onTap: (Int, Int) -> Unit,
    /** Two-finger tap → middle-button click at the tapped point (X11 button 2). */
    onMiddleClick: (Int, Int) -> Unit = { _, _ -> },
    onLongPress: (Int, Int) -> Unit,
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    /** Hold/release a mouse button (1=L, 2=M, 3=R) for the toolbar's
     *  explicit mouse-button toggles (#183). Default no-op. */
    onPressButton: (Int) -> Unit = {},
    onReleaseButton: (Int) -> Unit = {},
    onTypeChar: (Char) -> Unit,
    onTypeText: (String) -> Unit = { s -> s.forEach(onTypeChar) },
    onKeyDown: (keySym: Int) -> Unit,
    onKeyUp: (keySym: Int) -> Unit,
    onDisconnect: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
    cursor: StateFlow<CursorOverlay?>? = null,
    pointerPos: StateFlow<Pair<Int, Int>>? = null,
    inputMode: String = "DIRECT",
    /** Switch DIRECT/TOUCHPAD input mode from the toolbar (#183). */
    onSetInputMode: ((String) -> Unit)? = null,
    bandwidthSuggestion: StateFlow<String?>? = null,
    onAcceptBandwidthSuggestion: (() -> Unit)? = null,
    onDismissBandwidthSuggestion: (() -> Unit)? = null,
    securityWarning: StateFlow<String?>? = null,
    onDismissSecurityWarning: (() -> Unit)? = null,
    currentOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
    onCycleOrientation: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    /**
     * App-window-only toolbar actions. When non-null the bottom toolbar shows a
     * minimize (background to edge icon) and/or a Picture-in-Picture button.
     * Full desktops leave these null and don't show them.
     */
    onMinimize: (() -> Unit)? = null,
    onPictureInPicture: (() -> Unit)? = null,
    /** See [VncViewer]: 2-finger pinch-zoom for single-app windows. */
    twoFingerZoom: Boolean = false,
    /**
     * App-window-only: when non-null, fullscreen is host-controlled (the host
     * promotes the window into a full-screen Dialog that escapes the bottom
     * sheet). The desktop viewer leaves this null and keeps its own internal
     * fullscreen state. [onFullscreenChanged] is then the toggle-request channel
     * back to the host.
     */
    fullscreenOverride: Boolean? = null,
    /** App-window fullscreen: 3-finger pinch → live cage output scale. See [VncViewer]. */
    onChangeScale: ((Float) -> Unit)? = null,
    currentScale: Float = 1f,
    /** App-window fullscreen: persist the current output scale as the app's default. See [VncViewer]. */
    onSaveDefault: ((Float) -> Unit)? = null,
) {
    val connectedState by connected.collectAsState()
    val frameState by frame.collectAsState()
    val errorState by error.collectAsState()
    val cursorState = cursor?.collectAsState()?.value
    val pointerState = pointerPos?.collectAsState()?.value ?: (0 to 0)

    // Fullscreen is normally self-managed (the desktop viewer). When a caller
    // passes [fullscreenOverride] (the app-window case) the host owns the state
    // and the immersive window — VncSessionContent only mirrors it and routes
    // toggle requests up via [onFullscreenChanged].
    var localFullscreen by rememberSaveable { mutableStateOf(false) }
    val fullscreen = fullscreenOverride ?: localFullscreen
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window

    LaunchedEffect(fullscreen) {
        if (fullscreenOverride != null) return@LaunchedEffect
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
        if (!connectedState && fullscreenOverride == null && localFullscreen) localFullscreen = false
    }

    DisposableEffect(Unit) {
        onDispose {
            // Reads `localFullscreen`, NOT `fullscreen`. DisposableEffect(Unit) keeps the
            // effect lambda from the first composition, so a plain val like `fullscreen`
            // is frozen at its first-composition value (false) and this would never fire.
            // `localFullscreen` is a state delegate, so its getter yields the live value.
            // Without this, closing a fullscreen tab left onFullscreenChanged(false)
            // uncalled: the host kept hiding the app bar and bottom nav, and pager swipe
            // stays disabled while it thinks we're fullscreen — no way out. (#386)
            if (fullscreenOverride == null && localFullscreen && window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
                onFullscreenChanged(false)
            }
        }
    }

    if (connectedState && frameState != null) {
        VncViewer(
            frame = frameState!!,
            fullscreen = fullscreen,
            toolbarLayout = toolbarLayout,
            onTap = onTap,
            onMiddleClick = onMiddleClick,
            onLongPress = onLongPress,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onScrollUp = onScrollUp,
            onScrollDown = onScrollDown,
            onPressButton = onPressButton,
            onReleaseButton = onReleaseButton,
            onTypeChar = onTypeChar,
            onTypeText = onTypeText,
            onKeyDown = onKeyDown,
            onKeyUp = onKeyUp,
            onToggleFullscreen = {
                if (fullscreenOverride != null) onFullscreenChanged(!fullscreen)
                else localFullscreen = !localFullscreen
            },
            onDisconnect = onDisconnect,
            cursor = cursorState,
            pointerPos = pointerState,
            inputMode = inputMode,
            onSetInputMode = onSetInputMode,
            bandwidthSuggestion = bandwidthSuggestion?.collectAsState()?.value,
            onAcceptBandwidthSuggestion = onAcceptBandwidthSuggestion,
            onDismissBandwidthSuggestion = onDismissBandwidthSuggestion,
            securityWarning = securityWarning?.collectAsState()?.value,
            onDismissSecurityWarning = onDismissSecurityWarning,
            currentOrientation = currentOrientation,
            onCycleOrientation = onCycleOrientation,
            onMinimize = onMinimize,
            onPictureInPicture = onPictureInPicture,
            twoFingerZoom = twoFingerZoom,
            onChangeScale = onChangeScale,
            currentScale = currentScale,
            onSaveDefault = onSaveDefault,
        )
    } else {
        VncPlaceholder(
            error = errorState,
            onRetry = onRetry,
            onClose = onClose,
        )
    }
}

/** Legacy VncScreen with ViewModel — delegates to VncSessionContent. */
@Composable
fun VncScreen(
    isActive: Boolean = true,
    pendingHost: String? = null,
    pendingPort: Int? = null,
    pendingPassword: String? = null,
    pendingUsername: String? = null,
    pendingSshForward: Boolean = false,
    pendingSshSessionId: String? = null,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onPendingConsumed: () -> Unit = {},
    onFullscreenChanged: (Boolean) -> Unit = {},
    viewModel: VncViewModel = hiltViewModel(),
) {
    LaunchedEffect(isActive) { viewModel.setActive(isActive) }

    LaunchedEffect(pendingHost) {
        if (pendingHost != null) {
            if (pendingSshForward && pendingSshSessionId != null) {
                // 127.0.0.1 rather than "localhost" so sshd doesn't resolve
                // through getaddrinfo and hit the IPv6 loopback first — a
                // VNC server bound only to 127.0.0.1 (common for wayvnc)
                // rejects the ::1 attempt and depending on the sshd version
                // the retry can surface as a closed channel rather than
                // a successful fallback (#104).
                viewModel.connectViaSsh(
                    pendingSshSessionId, "127.0.0.1", pendingPort ?: 5900, pendingPassword,
                )
            } else {
                viewModel.connect(pendingHost, pendingPort ?: 5900, pendingPassword, pendingUsername)
            }
            onPendingConsumed()
        }
    }

    // Standalone-path orientation state. Lives in VncScreen (the
    // outer composable) so it sits above any conditional siblings
    // inside VncSessionContent / VncViewer. Mirrors RdpScreen.
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

    VncSessionContent(
        connected = viewModel.connected,
        frame = viewModel.frame,
        error = viewModel.error,
        toolbarLayout = toolbarLayout,
        onTap = { x, y -> viewModel.sendClick(x, y) },
        onLongPress = { x, y -> viewModel.sendClick(x, y, button = 3) },
        onDragStart = { x, y ->
            viewModel.sendPointer(x, y)
            viewModel.pressButton(1)
        },
        onDrag = { x, y -> viewModel.sendPointer(x, y) },
        onDragEnd = { viewModel.releaseButton(1) },
        onScrollUp = { viewModel.scrollUp() },
        onScrollDown = { viewModel.scrollDown() },
        onPressButton = { btn -> viewModel.pressButton(btn) },
        onReleaseButton = { btn -> viewModel.releaseButton(btn) },
        onTypeChar = { ch -> viewModel.typeKey(charToKeySym(ch)) },
        onTypeText = { text -> viewModel.typeText(text) },
        onKeyDown = { keySym -> viewModel.sendKey(keySym, true) },
        onKeyUp = { keySym -> viewModel.sendKey(keySym, false) },
        onDisconnect = { viewModel.disconnect() },
        onFullscreenChanged = onFullscreenChanged,
        cursor = viewModel.cursor,
        pointerPos = viewModel.pointerPos,
        securityWarning = viewModel.securityWarning,
        onDismissSecurityWarning = { viewModel.dismissSecurityWarning() },
        currentOrientation = orientationValue,
        onCycleOrientation = { orientationValue = cycleVncOrientation(orientationValue) },
    )
}

/**
 * Explicit mouse-button toggles (L / M / R) for the VNC toolbar (#183).
 * Each is a sticky hold: tapping arms that button down on the remote so a
 * finger drag moves with it held; tapping again releases. Mutually exclusive.
 * Lets the user move the pointer with no button pressed, or hold any button
 * and drag — the "use real mouse buttons like SSH" request.
 */
/**
 * Toggle between DIRECT (absolute: finger position = cursor, drag presses the
 * button) and TOUCHPAD (relative: drag glides the cursor with no button held)
 * input modes from inside the viewer (#183). The capability already exists as
 * the `desktopInputMode` preference; this surfaces it in-context so users hit
 * by "swiping always clicks" can switch without digging into Settings. Checked
 * (highlighted) = trackpad mode.
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
            contentDescription = if (touchpad) stringResource(R.string.vnc_cd_input_mode_trackpad_on)
                                 else stringResource(R.string.vnc_cd_input_mode_direct),
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
            contentDescription = if (twoFingerScroll) stringResource(R.string.vnc_cd_two_finger_scroll_on)
                                 else stringResource(R.string.vnc_cd_two_finger_viewport),
        )
    }
}

@Composable
private fun MouseButtonToggles(held: Int?, onToggle: (Int) -> Unit) {
    listOf(1 to "L", 2 to "M", 3 to "R").forEach { (btn, label) ->
        FilledIconToggleButton(
            checked = held == btn,
            onCheckedChange = { onToggle(btn) },
            modifier = Modifier.size(40.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun VncPlaceholder(
    error: String?,
    onRetry: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.vnc_placeholder_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.vnc_placeholder_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
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
            // Inline recovery so a lost connection isn't a long-press-to-close
            // dead-end (#121, KoriKraut). Retry re-runs the connect; Close drops
            // the tab and releases any lingering SSH tunnel.
            if (onRetry != null || onClose != null) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onClose != null) {
                        TextButton(onClick = onClose) { Text(stringResource(R.string.vnc_action_close)) }
                    }
                    if (onRetry != null) {
                        Button(onClick = onRetry) { Text(stringResource(R.string.vnc_action_retry)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VncViewer(
    frame: Bitmap,
    fullscreen: Boolean,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onTap: (Int, Int) -> Unit,
    onMiddleClick: (Int, Int) -> Unit = { _, _ -> },
    onLongPress: (Int, Int) -> Unit,
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onPressButton: (Int) -> Unit = {},
    onReleaseButton: (Int) -> Unit = {},
    onTypeChar: (Char) -> Unit,
    onTypeText: (String) -> Unit = { s -> s.forEach(onTypeChar) },
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onToggleFullscreen: () -> Unit,
    onDisconnect: () -> Unit,
    cursor: CursorOverlay? = null,
    pointerPos: Pair<Int, Int> = 0 to 0,
    inputMode: String = "DIRECT",
    bandwidthSuggestion: String? = null,
    onAcceptBandwidthSuggestion: (() -> Unit)? = null,
    onDismissBandwidthSuggestion: (() -> Unit)? = null,
    securityWarning: String? = null,
    onDismissSecurityWarning: (() -> Unit)? = null,
    currentOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
    onCycleOrientation: () -> Unit = {},
    /** Switch DIRECT/TOUCHPAD input mode from the toolbar (#183). When null
     *  the toggle is hidden (e.g. the legacy single-session path). */
    onSetInputMode: ((String) -> Unit)? = null,
    onMinimize: (() -> Unit)? = null,
    onPictureInPicture: (() -> Unit)? = null,
    /**
     * Local-viewport zoom/pan on TWO fingers instead of the default three.
     * For single-app windows (present_app) where users expect ordinary
     * pinch-to-zoom; the two-finger remote scroll-wheel is then unavailable.
     * The full desktop viewer leaves this false (2 fingers = remote scroll,
     * 3 fingers = local zoom/pan).
     */
    twoFingerZoom: Boolean = false,
    /**
     * App-window fullscreen only: a 3-finger pinch adjusts the cage's live
     * output scale (re-render bigger/smaller) instead of digital zoom-out.
     * [currentScale] seeds the gesture; [onChangeScale] pushes quantized changes.
     */
    onChangeScale: ((Float) -> Unit)? = null,
    currentScale: Float = 1f,
    /**
     * App-window fullscreen only: when non-null, the overlay shows a "Save as
     * default" button that persists the current cage output scale as this app's
     * per-app default. Receives the current applied scale.
     */
    onSaveDefault: ((Float) -> Unit)? = null,
) {
    val orientationMode = OrientationMode.fromActivityValue(currentOrientation)
    val orientationDesc = when (orientationMode) {
        OrientationMode.Landscape -> stringResource(R.string.vnc_orientation_landscape_desc)
        OrientationMode.Portrait -> stringResource(R.string.vnc_orientation_portrait_desc)
        OrientationMode.Auto -> stringResource(R.string.vnc_orientation_auto_desc)
    }
    // App-window fullscreen: cover/crop-fill the screen (no letterbox — phones
    // have rounded corners so losing a sliver is fine) and floor the digital
    // zoom at the fill scale so a pinch-out can't expose a black border. The
    // desktop viewer stays contain so its edges/taskbar aren't cropped.
    val coverFill = fullscreen && twoFingerZoom
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(frame) { frame.asImageBitmap() }
    val cursorImage = remember(cursor?.bitmap) { cursor?.bitmap?.asImageBitmap() }

    // Zoom & pan state
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    // Desktop only: two-finger drag = viewport pan (false) vs remote
    // scroll-wheel (true). Pinch always zooms. Toolbar toggle (#286 —
    // 3-finger gestures are unreliable on OnePlus/OxygenOS).
    var twoFingerScroll by rememberSaveable { mutableStateOf(false) }
    // 3-finger fullscreen output-scale gesture (app windows): accumulates the
    // pinch and pushes quantized scale changes to the live cage via onChangeScale.
    var outputScale by remember { mutableFloatStateOf(currentScale) }
    var lastSentScale by remember { mutableFloatStateOf(currentScale) }

    // A framebuffer resize — the fullscreen refit's re-mode (on enter/rotate),
    // a per-app resolution change, or any cage output `mode` change — leaves the
    // digital zoom/pan expressed in the *previous* framebuffer's coordinate
    // space. The pan-clamp in the gesture handler only re-runs while a gesture
    // is active, so a stale offset pans the freshly-rendered app off-screen and
    // reads as a black screen (e.g. pinch-zoom, then a 3-finger/rotation re-mode).
    // Snap back to the fitted view (zoom 1, no pan) on any size change. App-window
    // mode only — the desktop viewer keeps its free pan + mario-camera.
    if (twoFingerZoom) {
        LaunchedEffect(frame.width, frame.height) {
            zoom = 1f
            panX = 0f
            panY = 0f
        }
    }

    // Mario-camera viewport pan: when in touchpad mode and zoomed, snap the
    // pan so the cursor always stays inside the inner dead-zone of the view.
    LaunchedEffect(pointerPos, inputMode, zoom, viewSize, frame.width, frame.height) {
        if (inputMode == "TOUCHPAD" && zoom > 1f && viewSize.width > 0 && viewSize.height > 0) {
            val (newPanX, newPanY) = cameraFollow(
                cursorFbX = pointerPos.first,
                cursorFbY = pointerPos.second,
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

    // (Orientation state lives outside this composable — owners are
    // VncScreen for the standalone path and DesktopViewModel for the
    // multi-session Desktop view. We only render the icon based on
    // the value passed in.)

    // Keyboard
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var keyboardVisible by remember { mutableStateOf(false) }

    // #511(b): physical-keyboard support. The hidden text field was the only
    // focusable thing on this screen, and it was focused only when the soft
    // keyboard toggle was tapped — so with the soft keyboard closed nothing
    // held focus, and hardware keys went nowhere (sae13: keys reach the remote
    // only when the on-screen keyboard is open). The canvas is focusable so the
    // screen can hold focus WITHOUT summoning the IME, and the key handler
    // lives on the root Box, whose preview pass fires for every descendant —
    // the text field included. When the field holds focus (soft keyboard up)
    // the root handler defers to it so the commit path stays the single
    // sender for printable characters; otherwise the root handler is the
    // sender. Same shape as the RDP viewer's #507 fix.
    val hardwareKeyFocus = remember { FocusRequester() }
    var canvasFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val handleHardwareKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (fieldFocused) {
            // Soft keyboard attached: the field's own onPreviewKeyEvent /
            // onValueChange commit path handles input. Defer.
            false
        } else {
            val keySym = androidKeyToKeySym(event.key)
            if (keySym != null) {
                when (event.type) {
                    KeyEventType.KeyDown -> onKeyDown(keySym)
                    KeyEventType.KeyUp -> onKeyUp(keySym)
                }
                true
            } else {
                false
            }
        }
    }
    LaunchedEffect(Unit) { hardwareKeyFocus.requestFocus() }

    // The system re-shows the IME across a configuration change even when the
    // user dismissed it; enforce the invariant that the IME is up only when we
    // asked for it. Scoped to this screen's own focus holders so the terminal
    // tab's keyboard isn't hidden by this effect (isImeVisible is window-global).
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible, keyboardVisible, canvasFocused, fieldFocused) {
        if (!keyboardVisible && imeVisible && (canvasFocused || fieldFocused)) {
            keyboardController?.hide()
            hardwareKeyFocus.requestFocus()
        }
    }

    // Modifier state for VNC key toolbar
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    // Super/Logo (Mod4) — needed to drive nested-Wayland compositor
    // keybinds (Sway/Hyprland/niri all use Super for $mod), which the
    // in-app VNC viewer surfaces (#171). Not in the configurable layout
    // (that's shared with the terminal, where Super is meaningless) —
    // rendered as a fixed toggle on the VNC toolbar.
    var superActive by remember { mutableStateOf(false) }

    // Fullscreen overlay toolbar
    var overlayVisible by remember { mutableStateOf(false) }
    var showGestureHelp by remember { mutableStateOf(false) }
    // Draggable fullscreen menu button: offset from the top-right corner
    // (x grows left/negative, y grows down). Survives rotation/recompose.
    var menuOffX by rememberSaveable { mutableStateOf(0f) }
    var menuOffY by rememberSaveable { mutableStateOf(0f) }
    var rootBoxSize by remember { mutableStateOf(IntSize.Zero) }
    // Measured sizes so the opened toolbar can grow UP when the button sits in
    // the lower half (otherwise its downward column runs off the screen bottom).
    var menuBtnH by remember { mutableStateOf(0) }
    var menuToolbarH by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    // Explicit mouse-button hold (#183): when non-null (1=L, 2=M, 3=R) that
    // button is held down on the remote, so finger movement drags with it
    // held instead of the implicit button-1-on-drag. Lets the user move the
    // cursor with no button pressed (no toggle) or hold any button and drag —
    // most natural in TOUCHPAD mode where movement doesn't imply a press.
    // Mutually exclusive: toggling one releases any other. Read live inside
    // the gesture loop, so it isn't a pointerInput key.
    var heldButton by remember { mutableStateOf<Int?>(null) }
    val toggleHeldButton: (Int) -> Unit = { btn ->
        val current = heldButton
        when (current) {
            btn -> { onReleaseButton(btn); heldButton = null }
            else -> {
                if (current != null) onReleaseButton(current)
                onPressButton(btn)
                heldButton = btn
            }
        }
    }
    // Release a held button if the viewer leaves composition with one engaged,
    // so the remote isn't left with a stuck button.
    DisposableEffect(Unit) {
        onDispose { heldButton?.let(onReleaseButton) }
    }

    // Virtual cursor position for TOUCHPAD mode. Hoisted to composable
    // scope so it persists across gesture lifts — Nesos-ita reported on
    // #107 that cursor "teleported back" on each finger lift, because
    // the previous implementation re-seeded it inside the pointerInput
    // closure (which captures pointerPos as a snapshot at composition).
    // Reset when inputMode changes so re-entering touchpad picks up the
    // latest server-reported pointer.
    var virtualCursor by remember(inputMode) { mutableStateOf(pointerPos) }

    // Tap-then-drag state: the uptime at which the previous short-tap
    // ended. A touch-down within TAP_THEN_DRAG_WINDOW_MS of this
    // timestamp is treated as a follow-up gesture — if it moves, it's a
    // drag with button 1 held (RealVNC convention); if it stays still,
    // the long-press right-click is suppressed so tap-tap-hold-still
    // resolves as a double-click via two onTap callbacks instead.
    var lastTapUpMs by remember { mutableStateOf(0L) }

    // Auto-hide overlay after 4 seconds
    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            delay(4000)
            overlayVisible = false
        }
    }

    // Sentinel for the hidden text field — keep a space so backspace has something to delete
    val sentinel = " "
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length)))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(handleHardwareKey)
            .onSizeChanged { rootBoxSize = it },
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Security banner: the server's identity is unverified (anonymous TLS
        // or security type None). Non-blocking; the connection still works but
        // the user should know it isn't authenticated (security-review #1/#11).
        if (!fullscreen && securityWarning != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = securityWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    if (onDismissSecurityWarning != null) {
                        TextButton(onClick = onDismissSecurityWarning) {
                            Text(stringResource(R.string.vnc_action_dismiss))
                        }
                    }
                }
            }
        }
        // Bandwidth-suggestion banner (#107 follow-up). Non-blocking; user
        // chooses Switch (clean reconnect at 256 colours) or Dismiss.
        if (!fullscreen && bandwidthSuggestion != null && onAcceptBandwidthSuggestion != null && onDismissBandwidthSuggestion != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.vnc_bandwidth_suggestion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAcceptBandwidthSuggestion) { Text(stringResource(R.string.vnc_action_switch)) }
                    TextButton(onClick = onDismissBandwidthSuggestion) { Text(stringResource(R.string.vnc_action_dismiss)) }
                }
            }
        }
        // VNC canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .focusRequester(hardwareKeyFocus)
                .focusable()
                .onFocusChanged { canvasFocused = it.isFocused }
                .onSizeChanged { viewSize = it }
                // All touch handling: tap, drag, pinch-to-zoom, two-finger pan/scroll.
                // Uses Initial pass and consumes all events so the pager can't steal them.
                .pointerInput(frame.width, frame.height, viewSize, inputMode) {
                    val touchSlopPx = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        firstDown.consume()
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
                        var longPressFired = false
                        val longPressMs = viewConfiguration.longPressTimeoutMillis
                        val downUptimeMs = firstDown.uptimeMillis
                        // Tap-then-drag follow-up window: this touch came
                        // within 300 ms of the previous tap's lift. In
                        // touchpad mode that signals "drag with button 1"
                        // (RealVNC convention).
                        val isFollowUpTouch = inputMode == "TOUCHPAD" &&
                            (downUptimeMs - lastTapUpMs) <= 300L
                        var dragButtonPressed = false
                        // virtualCursor is hoisted to composable scope so
                        // it survives across gesture lifts — the
                        // pointerInput closure captures pointerPos as a
                        // snapshot, so a local re-seed here would reset
                        // the cursor on every finger-down (Nesos-ita's
                        // touchpad report on #107).

                        var event: PointerEvent
                        do {
                            // Use timeout to detect long press when finger is still
                            val remaining = if (!longPressFired && totalMovement < touchSlopPx)
                                (longPressMs - (SystemClock.uptimeMillis() - downUptimeMs)).coerceAtLeast(1)
                            else Long.MAX_VALUE

                            val timedEvent = withTimeoutOrNull(remaining) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }

                            if (timedEvent == null) {
                                // Timeout — fire long press (right-click), but
                                // only when this gesture has stayed single-finger
                                // AND isn't a follow-up to a recent tap (in which
                                // case we want to allow the user to start a drag
                                // with button 1, not get a phantom right-click).
                                if (totalFingers == 1 && !longPressFired && !dragging && totalMovement < touchSlopPx && !isFollowUpTouch && heldButton == null) {
                                    val (vx, vy) = if (inputMode == "TOUCHPAD") {
                                        virtualCursor
                                    } else {
                                        screenToVnc(
                                            lastSinglePos, viewSize,
                                            frame.width, frame.height,
                                            zoom, panX, panY, coverFill,
                                        )
                                    }
                                    onLongPress(vx, vy)
                                    longPressFired = true
                                }
                                // Wait for the actual next event
                                event = awaitPointerEvent(PointerEventPass.Initial)
                            } else {
                                event = timedEvent
                            }

                            val pointers = event.changes.filter { it.pressed }
                            val count = pointers.size

                            if (count >= 2) {
                                // If we were dragging with button 1, release it
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

                                // Skip delta application on the frame where the
                                // pointer-count changed. Otherwise the centroid
                                // (and span) recompute over a different set of
                                // pointers and the apparent jump gets fed in as
                                // a real pan/zoom delta — which made the
                                // viewport visibly jump as fingers lifted.
                                if (gestureStarted && count == prevCount) {
                                    val dx = centroid.x - prevCentroid.x
                                    val dy = centroid.y - prevCentroid.y
                                    twoFingerMovement += abs(dx) + abs(dy)
                                    if (coverFill && onChangeScale != null && totalFingers >= 3) {
                                        // App-window 3-finger fullscreen: adjust the live
                                        // cage output scale (re-render bigger/smaller),
                                        // not the pointless local digital zoom-out.
                                        if (prevSpan > 0f && span > 0f) {
                                            outputScale = (outputScale * (span / prevSpan)).coerceIn(0.5f, 3f)
                                            val q = Math.round(outputScale / 0.25f) * 0.25f
                                            if (q != lastSentScale) {
                                                lastSentScale = q
                                                onChangeScale(q)
                                            }
                                        }
                                    } else {
                                        // Two fingers: pinch (span changing) = zoom; a
                                        // same-span drag = remote scroll (scroll-mode
                                        // toggle, or an app window not yet zoomed) or
                                        // viewport pan (zoomed). Everything is on two
                                        // fingers — 3-finger desktop zoom was unreliable
                                        // on OxygenOS (#286).
                                        val spanDelta = if (prevSpan > 0f) abs(span - prevSpan) else 0f
                                        val pinching = spanDelta > 2f && spanDelta >= abs(dx) + abs(dy)
                                        if (pinching && prevSpan > 0f && span > 0f) {
                                            // Pinch-zoom about the centroid. Cover-fill
                                            // floors zoom at 1× (so a pinch-out can't
                                            // expose black); otherwise 0.5× min, 10× max.
                                            val newZoom = (zoom * (span / prevSpan))
                                                .coerceIn(if (coverFill) 1f else 0.5f, 10f)
                                            val actualScale = if (zoom > 0f) newZoom / zoom else 1f
                                            val cx = viewSize.width / 2f
                                            val cy = viewSize.height / 2f
                                            panX = (centroid.x - cx) * (1 - actualScale) + panX * actualScale
                                            panY = (centroid.y - cy) * (1 - actualScale) + panY * actualScale
                                            zoom = newZoom
                                        } else if (twoFingerScroll || (twoFingerZoom && zoom <= 1.01f)) {
                                            // Remote wheel-scroll: desktop scroll-mode
                                            // toggle, or an app window not yet zoomed.
                                            // Finger-down (dy>0) → wheel-up so content
                                            // tracks the fingers (touchscreen convention).
                                            cumulativeScrollY += dy
                                            if (abs(cumulativeScrollY) > 40f) {
                                                if (cumulativeScrollY < 0) onScrollDown() else onScrollUp()
                                                cumulativeScrollY = 0f
                                            }
                                        } else if (zoom > 1f) {
                                            // Viewport pan (zoomed). App windows clamp so a
                                            // corner of the scaled content can't be dragged
                                            // inside the viewport leaving empty space.
                                            panX += dx
                                            panY += dy
                                            if (twoFingerZoom && viewSize.width > 0 && viewSize.height > 0) {
                                                val fit = if (coverFill) maxOf(
                                                    viewSize.width.toFloat() / frame.width,
                                                    viewSize.height.toFloat() / frame.height,
                                                ) else minOf(
                                                    viewSize.width.toFloat() / frame.width,
                                                    viewSize.height.toFloat() / frame.height,
                                                )
                                                val maxPanX = maxOf(0f, (frame.width * fit * zoom - viewSize.width) / 2f)
                                                val maxPanY = maxOf(0f, (frame.height * fit * zoom - viewSize.height) / 2f)
                                                panX = panX.coerceIn(-maxPanX, maxPanX)
                                                panY = panY.coerceIn(-maxPanY, maxPanY)
                                            }
                                        }
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
                                    // Integrate finger delta into virtualCursor in
                                    // framebuffer coords (screen delta / zoom).
                                    // If this gesture is a tap-then-touch
                                    // follow-up, the first time we cross the
                                    // touch slop we press button 1 (drag with
                                    // button), then continue normally.
                                    val scale = if (zoom > 0f) zoom else 1f
                                    val nx = (virtualCursor.first + (deltaScreen.x / scale).toInt())
                                        .coerceIn(0, frame.width - 1)
                                    val ny = (virtualCursor.second + (deltaScreen.y / scale).toInt())
                                        .coerceIn(0, frame.height - 1)
                                    virtualCursor = nx to ny
                                    if (!longPressFired && totalMovement >= touchSlopPx) {
                                        when {
                                            // A toolbar button is held — just move
                                            // the pointer; the held button rides
                                            // along in the mask, so this drags
                                            // with that button (#183).
                                            heldButton != null -> onDrag(nx, ny)
                                            isFollowUpTouch && !dragButtonPressed -> {
                                                onDragStart(nx, ny)
                                                dragButtonPressed = true
                                            }
                                            else -> onDrag(nx, ny)
                                        }
                                    }
                                } else {
                                    val pos = screenToVnc(
                                        change.position, viewSize,
                                        frame.width, frame.height,
                                        zoom, panX, panY, coverFill,
                                    )
                                    when {
                                        // Held toolbar button: move only (no
                                        // implicit button-1 press), so the drag
                                        // uses the held button instead (#183).
                                        heldButton != null -> onDrag(pos.first, pos.second)
                                        // Start drag (button 1 press) once movement exceeds touch slop
                                        !longPressFired && !dragging && totalMovement >= touchSlopPx -> {
                                            onDragStart(pos.first, pos.second)
                                            dragging = true
                                        }
                                        dragging -> onDrag(pos.first, pos.second)
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

                        // Release button 1 if drag was active (direct-mode
                        // drag or touchpad tap-then-drag).
                        if (dragging || dragButtonPressed) {
                            onDragEnd()
                        }

                        // Short tap with little movement = click (skip if
                        // long press fired, or a toolbar button is held — the
                        // held button is the press, so a tap mustn't inject an
                        // extra button-1 click, #183). Record the lift time so
                        // a subsequent touch within 300 ms is treated as a
                        // tap-then-drag follow-up.
                        if (totalFingers == 1 && totalMovement < touchSlopPx && !longPressFired && heldButton == null) {
                            val (vx, vy) = if (inputMode == "TOUCHPAD") {
                                virtualCursor
                            } else {
                                screenToVnc(
                                    lastSinglePos, viewSize,
                                    frame.width, frame.height,
                                    zoom, panX, panY, coverFill,
                                )
                            }
                            onTap(vx, vy)
                            if (inputMode == "TOUCHPAD" && !isFollowUpTouch) {
                                lastTapUpMs = SystemClock.uptimeMillis()
                            } else {
                                // Either non-touchpad mode (no follow-up
                                // semantics) or this *was* the follow-up
                                // tap (don't chain into a third touch).
                                lastTapUpMs = 0L
                            }
                        } else if (totalFingers == 2 && twoFingerMovement < touchSlopPx &&
                            !longPressFired && heldButton == null) {
                            // Two fingers down + up with no travel = middle click (#286).
                            val (mx, my) = if (inputMode == "TOUCHPAD") {
                                virtualCursor
                            } else {
                                screenToVnc(
                                    prevCentroid, viewSize,
                                    frame.width, frame.height,
                                    zoom, panX, panY, coverFill,
                                )
                            }
                            onMiddleClick(mx, my)
                            lastTapUpMs = 0L
                        } else {
                            // Drag, multi-finger, or long-press fired: this
                            // gesture didn't end with a clean tap, so don't
                            // chain to a follow-up.
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
                        // Without this, zoomed/panned pixels escape the Canvas's
                        // layout bounds and paint over the sibling bottom toolbar
                        // in the enclosing Column (reported by Nesos-ita on #107).
                        clip = true
                    },
            ) {
                drawVncFrame(imageBitmap, frame.width, frame.height, coverFill)
                if (cursorImage != null && cursor != null) {
                    drawVncCursor(
                        cursor = cursorImage,
                        cursorW = cursor.bitmap.width,
                        cursorH = cursor.bitmap.height,
                        hotspotX = cursor.hotspotX,
                        hotspotY = cursor.hotspotY,
                        pointerX = pointerPos.first,
                        pointerY = pointerPos.second,
                        fbWidth = frame.width,
                        fbHeight = frame.height,
                        coverFill = coverFill,
                    )
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
                    // Characters were typed (or pasted). Route through
                    // onTypeText so multi-char input goes via the
                    // serialized typeText path — the previous one-
                    // launch-per-char model interleaved key events on
                    // the wire and Windows VNC produced "half-capitals".
                    val added = newText.substring(oldText.length)
                    onTypeText(added)
                } else if (newText.length < oldText.length) {
                    // Backspace
                    val deleted = oldText.length - newText.length
                    repeat(deleted) {
                        onKeyDown(XK_BACKSPACE)
                        onKeyUp(XK_BACKSPACE)
                    }
                }

                // Reset to sentinel
                textFieldValue = TextFieldValue(sentinel, TextRange(sentinel.length))
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { fieldFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val keySym = androidKeyToKeySym(event.key)
                    if (keySym != null) {
                        when (event.type) {
                            KeyEventType.KeyDown -> onKeyDown(keySym)
                            KeyEventType.KeyUp -> onKeyUp(keySym)
                        }
                        true
                    } else {
                        false
                    }
                },
        )

        // VNC key extension rows (incl. the Super/Mod4 key, #171). Shown
        // when the user has toggled the keyboard on (keyboardVisible) OR the
        // soft keyboard's insets are actually present (imeVisible). Gating on
        // the explicit toggle — not imeVisible alone — is what makes the
        // persistent keyboard button a reliable way to reach Super on a
        // nested-Wayland desktop, where the IME may not raise (or report
        // insets) the way it does over a focused text field (#171). Still
        // hidden by default so the rows don't eat screen space unsummoned;
        // allowed in fullscreen too, since the immersive desktop is exactly
        // where the compositor keybinds (Super+D / Super+Return) are needed.
        if (keyboardVisible || imeVisible) {
            VncKeyToolbar(
                layout = toolbarLayout,
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                superActive = superActive,
                onToggleCtrl = {
                    ctrlActive = !ctrlActive
                    if (!ctrlActive) onKeyUp(XK_CONTROL_L) else onKeyDown(XK_CONTROL_L)
                },
                onToggleAlt = {
                    altActive = !altActive
                    if (!altActive) onKeyUp(XK_ALT_L) else onKeyDown(XK_ALT_L)
                },
                onToggleShift = {
                    shiftActive = !shiftActive
                    if (!shiftActive) onKeyUp(XK_SHIFT_L) else onKeyDown(XK_SHIFT_L)
                },
                onToggleSuper = {
                    superActive = !superActive
                    if (!superActive) onKeyUp(XK_SUPER_L) else onKeyDown(XK_SUPER_L)
                },
                onVncKey = { keySym ->
                    onKeyDown(keySym)
                    onKeyUp(keySym)
                    // Auto-release modifiers after key press
                    if (ctrlActive) { onKeyUp(XK_CONTROL_L); ctrlActive = false }
                    if (altActive) { onKeyUp(XK_ALT_L); altActive = false }
                    if (shiftActive) { onKeyUp(XK_SHIFT_L); shiftActive = false }
                    if (superActive) { onKeyUp(XK_SUPER_L); superActive = false }
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

        // Bottom toolbar (hidden in fullscreen).
        // Solid background so a zoomed framebuffer doesn't show through —
        // Nesos-ita reported on #107 that the toolbar looked like the
        // image was overpainting it. The image is correctly clipped to
        // the Canvas (clip = true on the graphicsLayer); the leak was
        // visual transparency on this Row, which previously had no
        // background at all.
        if (!fullscreen) {
            // Surface (not a bare .background) so the toolbar also sets its
            // content colour: without onSurface the icons fall back to the
            // default (black) and vanish on a dark surface (#286).
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
            // FlowRow, not Row: an app window's toolbar carries ~10 buttons
            // (close/keyboard/orientation/L-M-R/input-mode/minimize/PiP/
            // fullscreen), which is wider than a portrait phone. A plain Row
            // silently clipped the tail — users reported the fullscreen
            // button as missing. Overflow now wraps to a second line.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.vnc_cd_disconnect))
                }

                // Keyboard toggle
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
                        contentDescription = stringResource(R.string.vnc_cd_toggle_keyboard),
                    )
                }

                IconButton(onClick = onCycleOrientation) {
                    Icon(orientationMode.icon, contentDescription = orientationDesc)
                }

                // Explicit mouse-button hold toggles (L/M/R) — #183.
                MouseButtonToggles(held = heldButton, onToggle = toggleHeldButton)

                // Direct/trackpad input-mode toggle — #183.
                onSetInputMode?.let { InputModeToggle(inputMode, it) }
                // Desktop only: two-finger drag = viewport pan vs remote scroll — #286.
                if (!twoFingerZoom) {
                    ScrollModeToggle(twoFingerScroll) { twoFingerScroll = !twoFingerScroll }
                }

                // App-window-only: background to an edge icon (keeps it alive).
                onMinimize?.let { minimize ->
                    IconButton(onClick = minimize) {
                        Icon(Icons.Default.Minimize, contentDescription = stringResource(R.string.vnc_cd_background_to_edge))
                    }
                }
                // App-window-only: enter system Picture-in-Picture.
                onPictureInPicture?.let { pip ->
                    IconButton(onClick = pip) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = stringResource(R.string.vnc_cd_picture_in_picture))
                    }
                }

                Spacer(Modifier.weight(1f))

                // Reset zoom
                if (zoom != 1f || panX != 0f || panY != 0f) {
                    IconButton(onClick = {
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                    }) {
                        Icon(Icons.Default.FitScreen, contentDescription = stringResource(R.string.vnc_cd_reset_zoom))
                    }
                }

                // Fullscreen button
                IconButton(onClick = onToggleFullscreen) {
                    Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.vnc_cd_fullscreen))
                }
            }
            }
        }
    } // end Column

    // Fullscreen corner hotspot and overlay toolbar
    if (fullscreen) {
        // Dismiss scrim — rendered first so toolbar buttons are on top
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

        // Corner hotspot — defaults to top-right; drag to reposition.
        AnimatedVisibility(
            visible = !overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(menuOffX.roundToInt(), menuOffY.roundToInt()) },
        ) {
            val btnPx = with(density) { 48.dp.toPx() }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                modifier = Modifier
                    .onSizeChanged { menuBtnH = it.height }
                    // Tap opens the toolbar; drag moves the button out of the
                    // app's way (clamped to the viewport so it can't be lost).
                    .pointerInput(Unit) {
                        detectTapGestures { overlayVisible = true }
                    }
                    .pointerInput(rootBoxSize) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val minX = -(rootBoxSize.width - btnPx).coerceAtLeast(0f)
                            val maxY = (rootBoxSize.height - btnPx).coerceAtLeast(0f)
                            menuOffX = (menuOffX + drag.x).coerceIn(minX, 0f)
                            menuOffY = (menuOffY + drag.y).coerceIn(0f, maxY)
                        }
                    },
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(R.string.vnc_cd_session_menu),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
        }

        // Floating toolbar overlay — rendered last so it's on top of the dismiss
        // scrim; follows the (draggable) menu button's position. When the button
        // is in the lower half the column would run off the bottom, so anchor the
        // toolbar's BOTTOM to the button and grow upward instead.
        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .onSizeChanged { menuToolbarH = it.height }
                .offset {
                    val invert = rootBoxSize.height > 0 &&
                        (menuOffY + menuBtnH / 2f) > rootBoxSize.height / 2f
                    val ty = if (invert) {
                        (menuOffY + menuBtnH - menuToolbarH).coerceAtLeast(0f)
                    } else {
                        menuOffY
                    }
                    IntOffset(menuOffX.roundToInt(), ty.roundToInt())
                },
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
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
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.vnc_cd_disconnect))
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
                            contentDescription = stringResource(R.string.vnc_cd_toggle_keyboard),
                        )
                    }
                    IconButton(onClick = onCycleOrientation) {
                        Icon(orientationMode.icon, contentDescription = orientationDesc)
                    }
                    // Explicit mouse-button hold toggles (L/M/R) — #183.
                    MouseButtonToggles(held = heldButton, onToggle = toggleHeldButton)
                    // Direct/trackpad input-mode toggle — #183.
                    onSetInputMode?.let { InputModeToggle(inputMode, it) }
                    // Desktop only: two-finger drag = viewport pan vs remote scroll — #286.
                    if (!twoFingerZoom) {
                        ScrollModeToggle(twoFingerScroll) { twoFingerScroll = !twoFingerScroll }
                    }
                    if (zoom != 1f || panX != 0f || panY != 0f) {
                        IconButton(onClick = {
                            zoom = 1f
                            panX = 0f
                            panY = 0f
                        }) {
                            Icon(
                                Icons.Default.FitScreen,
                                contentDescription = stringResource(R.string.vnc_cd_reset_zoom),
                            )
                        }
                    }
                    // App-window gestures aren't obvious — surface them (#3-finger resize).
                    if (onChangeScale != null) {
                        IconButton(onClick = { showGestureHelp = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = stringResource(R.string.vnc_cd_gesture_help),
                            )
                        }
                    }
                    // App windows: pin the current output scale as this app's default.
                    onSaveDefault?.let { save ->
                        IconButton(onClick = {
                            overlayVisible = false
                            save(lastSentScale)
                        }) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = stringResource(R.string.vnc_cd_save_default),
                            )
                        }
                    }
                    IconButton(onClick = {
                        overlayVisible = false
                        onToggleFullscreen()
                    }) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.vnc_cd_exit_fullscreen))
                    }
                }
            }
        }

        if (showGestureHelp) {
            AlertDialog(
                onDismissRequest = { showGestureHelp = false },
                confirmButton = {
                    TextButton(onClick = { showGestureHelp = false }) {
                        Text(stringResource(R.string.vnc_action_close))
                    }
                },
                icon = { Icon(Icons.Default.TouchApp, contentDescription = null) },
                title = { Text(stringResource(R.string.vnc_gesture_help_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vnc_gesture_help_two_finger))
                        Text(stringResource(R.string.vnc_gesture_help_three_finger))
                    }
                },
            )
        }
    }
    } // end Box
}

private fun DrawScope.drawVncFrame(
    image: androidx.compose.ui.graphics.ImageBitmap,
    srcWidth: Int,
    srcHeight: Int,
    coverFill: Boolean = false,
) {
    val viewW = size.width
    val viewH = size.height
    // contain (letterbox) by default; cover (crop-fill) for app-window fullscreen.
    val scale = if (coverFill) maxOf(viewW / srcWidth, viewH / srcHeight)
    else minOf(viewW / srcWidth, viewH / srcHeight)
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

private fun DrawScope.drawVncCursor(
    cursor: androidx.compose.ui.graphics.ImageBitmap,
    cursorW: Int,
    cursorH: Int,
    hotspotX: Int,
    hotspotY: Int,
    pointerX: Int,
    pointerY: Int,
    fbWidth: Int,
    fbHeight: Int,
    coverFill: Boolean = false,
) {
    val viewW = size.width
    val viewH = size.height
    val scale = if (coverFill) maxOf(viewW / fbWidth, viewH / fbHeight)
    else minOf(viewW / fbWidth, viewH / fbHeight)
    val fbOffsetX = (viewW - fbWidth * scale) / 2
    val fbOffsetY = (viewH - fbHeight * scale) / 2

    // Cursor origin in framebuffer coords; scale into view.
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
 * Mario-camera viewport pan: keep the cursor inside an inner dead-zone of
 * the view. Returns the (panX, panY) we should snap to. No-op when not
 * zoomed in (whole framebuffer fits, no point panning).
 *
 * Math: the forward transform from framebuffer (fbX, fbY) to screen pixel is:
 *   localX = fbX * fitScale + fitOffsetX
 *   screenX = (localX - cx) * zoom + cx + panX
 * (and analogously for Y). For each axis independently, if the cursor's
 * screen position lands outside the dead-zone, adjust pan so it lands on
 * the dead-zone boundary.
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

    // Dead-zone: centred rectangle of size view * (1 - margin).
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

/**
 * Map a screen touch coordinate to VNC framebuffer coordinates,
 * accounting for zoom and pan.
 */
private fun screenToVnc(
    offset: Offset,
    viewSize: IntSize,
    fbWidth: Int,
    fbHeight: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
    coverFill: Boolean = false,
): Pair<Int, Int> {
    if (viewSize.width == 0 || viewSize.height == 0) return 0 to 0
    val viewW = viewSize.width.toFloat()
    val viewH = viewSize.height.toFloat()

    // Reverse the graphicsLayer transform: the canvas is scaled by zoom and translated by pan.
    // The center of the view is the pivot point for graphicsLayer scaling.
    val cx = viewW / 2f
    val cy = viewH / 2f
    val localX = (offset.x - cx - panX) / zoom + cx
    val localY = (offset.y - cy - panY) / zoom + cy

    // Now map from view coordinates to VNC coordinates (same as before)
    val fitScale = if (coverFill) maxOf(viewW / fbWidth, viewH / fbHeight)
    else minOf(viewW / fbWidth, viewH / fbHeight)
    val dstW = fbWidth * fitScale
    val dstH = fbHeight * fitScale
    val offsetX = (viewW - dstW) / 2
    val offsetY = (viewH - dstH) / 2

    val vncX = ((localX - offsetX) / fitScale).toInt().coerceIn(0, fbWidth - 1)
    val vncY = ((localY - offsetY) / fitScale).toInt().coerceIn(0, fbHeight - 1)
    return vncX to vncY
}

/** Keys that form the aligned navigation block. */
private val VNC_NAV_KEYS = setOf(
    ToolbarKey.ARROW_UP, ToolbarKey.ARROW_DOWN,
    ToolbarKey.ARROW_LEFT, ToolbarKey.ARROW_RIGHT,
    ToolbarKey.HOME, ToolbarKey.END,
    ToolbarKey.PGUP, ToolbarKey.PGDN,
)

private val VNC_NAV_GRID_TOP = arrayOf(
    ToolbarKey.HOME, ToolbarKey.ARROW_UP, ToolbarKey.END, ToolbarKey.PGUP,
)
private val VNC_NAV_GRID_BOTTOM = arrayOf(
    ToolbarKey.ARROW_LEFT, ToolbarKey.ARROW_DOWN,
    ToolbarKey.ARROW_RIGHT, ToolbarKey.PGDN,
)

private val VNC_NAV_CELL_WIDTH = 44.dp

/** Map a ToolbarKey to its X11 KeySym for VNC. */
private fun toolbarKeyToKeySym(key: ToolbarKey): Int? = when (key) {
    ToolbarKey.ESC_KEY -> XK_ESCAPE
    ToolbarKey.TAB_KEY -> XK_TAB
    ToolbarKey.ARROW_LEFT -> XK_LEFT
    ToolbarKey.ARROW_UP -> XK_UP
    ToolbarKey.ARROW_DOWN -> XK_DOWN
    ToolbarKey.ARROW_RIGHT -> XK_RIGHT
    ToolbarKey.HOME -> XK_HOME
    ToolbarKey.END -> XK_END
    ToolbarKey.PGUP -> XK_PAGE_UP
    ToolbarKey.PGDN -> XK_PAGE_DOWN
    else -> key.char?.code
}

/** Split a row's items into (before nav keys, after nav keys). */
private fun vncSplitAroundNav(row: List<sh.haven.core.data.preferences.ToolbarItem>): Pair<List<sh.haven.core.data.preferences.ToolbarItem>, List<sh.haven.core.data.preferences.ToolbarItem>> {
    val firstNavIdx = row.indexOfFirst { it is sh.haven.core.data.preferences.ToolbarItem.BuiltIn && it.key in VNC_NAV_KEYS }
    val lastNavIdx = row.indexOfLast { it is sh.haven.core.data.preferences.ToolbarItem.BuiltIn && it.key in VNC_NAV_KEYS }
    if (firstNavIdx == -1) return row to emptyList()
    val left = row.subList(0, firstNavIdx)
    val right = if (lastNavIdx + 1 < row.size) row.subList(lastNavIdx + 1, row.size) else emptyList()
    return left to right
}

@Composable
private fun VncKeyToolbar(
    layout: ToolbarLayout,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    superActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleSuper: () -> Unit,
    onVncKey: (keySym: Int) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val presentNavKeys = layout.rows.flatten()
        .filterIsInstance<sh.haven.core.data.preferences.ToolbarItem.BuiltIn>()
        .filter { it.key in VNC_NAV_KEYS }
        .map { it.key }
        .toSet()

    Surface(tonalElevation = 2.dp) {
        // Super (Mod4) toggle for nested-Wayland compositor keybinds
        // (#171) is rendered inline as the first key of row 1 — NOT a
        // separate row, since the toolbar height budget can't afford a
        // third row. Kept out of the configurable layout because that
        // layout is shared with the terminal toolbar (where Super is
        // meaningless).
        if (layout.rows.size >= 2 && presentNavKeys.isNotEmpty()) {
            val (row1Left, row1Right) = vncSplitAroundNav(layout.row1)
            val (row2Left, row2Right) = vncSplitAroundNav(layout.row2)

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            ) {
                // Left keys column
                Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        VncToggleButton("Super", superActive, onToggleSuper)
                        for (item in row1Left) {
                            VncRenderItem(item, ctrlActive, altActive, shiftActive, onToggleCtrl, onToggleAlt, onToggleShift, onVncKey, onToggleKeyboard)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (item in row2Left) {
                            VncRenderItem(item, ctrlActive, altActive, shiftActive, onToggleCtrl, onToggleAlt, onToggleShift, onVncKey, onToggleKeyboard)
                        }
                    }
                }

                // Nav block grid
                Column {
                    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (key in VNC_NAV_GRID_TOP) {
                            if (key != null && key in presentNavKeys) {
                                val keySym = toolbarKeyToKeySym(key)
                                VncNavButton(key, keySym) { if (keySym != null) onVncKey(keySym) }
                            } else {
                                Spacer(Modifier.width(VNC_NAV_CELL_WIDTH).height(32.dp))
                            }
                        }
                    }
                    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (key in VNC_NAV_GRID_BOTTOM) {
                            if (key != null && key in presentNavKeys) {
                                val keySym = toolbarKeyToKeySym(key)
                                VncNavButton(key, keySym) { if (keySym != null) onVncKey(keySym) }
                            } else {
                                Spacer(Modifier.width(VNC_NAV_CELL_WIDTH).height(32.dp))
                            }
                        }
                    }
                }

                // Right keys (symbols)
                if (row1Right.isNotEmpty() || row2Right.isNotEmpty()) {
                    Column {
                        if (row1Right.isNotEmpty()) {
                            Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                for (item in row1Right) {
                                    VncRenderItem(item, ctrlActive, altActive, shiftActive, onToggleCtrl, onToggleAlt, onToggleShift, onVncKey, onToggleKeyboard)
                                }
                            }
                        } else {
                            Spacer(Modifier.height(34.dp))
                        }
                        Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (item in row2Right) {
                                VncRenderItem(item, ctrlActive, altActive, shiftActive, onToggleCtrl, onToggleAlt, onToggleShift, onVncKey, onToggleKeyboard)
                            }
                        }
                    }
                }
            }
        } else {
            // Fallback: flat rows
            Column {
                var superPlaced = false
                for (row in layout.rows) {
                    if (row.isEmpty()) continue
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Super inline as the first key of the first row
                        // (no extra row — see above).
                        if (!superPlaced) {
                            VncToggleButton("Super", superActive, onToggleSuper)
                            superPlaced = true
                        }
                        for (item in row) {
                            VncRenderItem(item, ctrlActive, altActive, shiftActive, onToggleCtrl, onToggleAlt, onToggleShift, onVncKey, onToggleKeyboard)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VncRenderItem(
    item: sh.haven.core.data.preferences.ToolbarItem,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onVncKey: (keySym: Int) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    when (item) {
        is sh.haven.core.data.preferences.ToolbarItem.BuiltIn -> {
            VncBuiltInKey(item.key, ctrlActive, altActive, shiftActive, onToggleCtrl, onToggleAlt, onToggleShift, onVncKey, onToggleKeyboard)
        }
        is sh.haven.core.data.preferences.ToolbarItem.Custom -> {
            VncSymbolButton(item.label) {
                for (ch in item.send) { onVncKey(ch.code) }
            }
        }
    }
}

/** Nav block button with fixed cell width for VNC toolbar. */
private const val VNC_REPEAT_DELAY_MS = 400L
private const val VNC_REPEAT_INTERVAL_MS = 80L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VncRepeatingButton(
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
            delay(VNC_REPEAT_DELAY_MS)
            didRepeat = true
            while (isPressed) {
                currentOnClick()
                delay(VNC_REPEAT_INTERVAL_MS)
            }
        }
    }

    FilledTonalButton(
        onClick = {}, // handled by pointerInteropFilter
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
private fun VncNavButton(key: ToolbarKey, keySym: Int?, onClick: () -> Unit) {
    val isArrow = key in setOf(ToolbarKey.ARROW_UP, ToolbarKey.ARROW_DOWN, ToolbarKey.ARROW_LEFT, ToolbarKey.ARROW_RIGHT)
    VncRepeatingButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 1.dp).width(VNC_NAV_CELL_WIDTH).height(32.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) {
        if (isArrow) {
            val label = when (key) {
                ToolbarKey.ARROW_UP -> "\u2191"
                ToolbarKey.ARROW_DOWN -> "\u2193"
                ToolbarKey.ARROW_LEFT -> "\u2190"
                ToolbarKey.ARROW_RIGHT -> "\u2192"
                else -> ""
            }
            Text(label, fontSize = 16.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(key.label, fontSize = 11.sp, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun VncBuiltInKey(
    key: ToolbarKey,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onVncKey: (keySym: Int) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    when (key) {
        ToolbarKey.KEYBOARD -> {
            IconButton(
                onClick = onToggleKeyboard,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = stringResource(R.string.vnc_cd_toggle_keyboard), modifier = Modifier.size(18.dp))
            }
        }
        ToolbarKey.CTRL -> VncToggleButton("Ctrl", ctrlActive, onToggleCtrl)
        ToolbarKey.ALT -> VncToggleButton("Alt", altActive, onToggleAlt)
        ToolbarKey.ALTGR -> {
            val altGrActive = ctrlActive && altActive
            VncToggleButton("AltGr", altGrActive) {
                if (altGrActive) {
                    onToggleCtrl()
                    onToggleAlt()
                } else {
                    if (!ctrlActive) onToggleCtrl()
                    if (!altActive) onToggleAlt()
                }
            }
        }
        ToolbarKey.SHIFT -> VncToggleButton("Shift", shiftActive, onToggleShift)
        ToolbarKey.ARROW_LEFT -> VncArrowButton("\u2190") { onVncKey(XK_LEFT) }
        ToolbarKey.ARROW_UP -> VncArrowButton("\u2191") { onVncKey(XK_UP) }
        ToolbarKey.ARROW_DOWN -> VncArrowButton("\u2193") { onVncKey(XK_DOWN) }
        ToolbarKey.ARROW_RIGHT -> VncArrowButton("\u2192") { onVncKey(XK_RIGHT) }
        else -> {
            val keySym = toolbarKeyToKeySym(key)
            if (keySym != null) {
                VncTextButton(key.label) { onVncKey(keySym) }
            }
        }
    }
}

@Composable
private fun VncTextButton(label: String, onClick: () -> Unit) {
    VncRepeatingButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 1.dp).height(32.dp),
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun VncArrowButton(label: String, onClick: () -> Unit) {
    VncRepeatingButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 1.dp).height(32.dp),
    ) {
        Text(label, fontSize = 16.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VncToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 1.dp).height(32.dp),
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
private fun VncSymbolButton(label: String, onClick: () -> Unit) {
    VncRepeatingButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 1.dp).height(30.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 12.sp, lineHeight = 12.sp)
    }
}

// X11 KeySym constants for special keys
private const val XK_BACKSPACE = 0xff08
private const val XK_TAB = 0xff09
private const val XK_RETURN = 0xff0d
private const val XK_ESCAPE = 0xff1b
private const val XK_DELETE = 0xffff
private const val XK_HOME = 0xff50
private const val XK_LEFT = 0xff51
private const val XK_UP = 0xff52
private const val XK_RIGHT = 0xff53
private const val XK_DOWN = 0xff54
private const val XK_PAGE_UP = 0xff55
private const val XK_PAGE_DOWN = 0xff56
private const val XK_END = 0xff57
private const val XK_INSERT = 0xff63
private const val XK_SHIFT_L = 0xffe1
private const val XK_CONTROL_L = 0xffe3
private const val XK_ALT_L = 0xffe9
private const val XK_SUPER_L = 0xffeb
private const val XK_F1 = 0xffbe
private const val XK_F2 = 0xffbf
private const val XK_F3 = 0xffc0
private const val XK_F4 = 0xffc1
private const val XK_F5 = 0xffc2
private const val XK_F6 = 0xffc3
private const val XK_F7 = 0xffc4
private const val XK_F8 = 0xffc5
private const val XK_F9 = 0xffc6
private const val XK_F10 = 0xffc7
private const val XK_F11 = 0xffc8
private const val XK_F12 = 0xffc9

/** Convert a printable character to its X11 KeySym. */
fun charToKeySym(ch: Char): Int = when (ch) {
    '\n', '\r' -> XK_RETURN
    '\t' -> XK_TAB
    '\b' -> XK_BACKSPACE
    else -> ch.code // Latin-1 characters map directly to Unicode code point
}

/** Map Android Key to X11 KeySym for special (non-printable) keys. */
/**
 * Three-state orientation cycle for the session toolbar's rotate
 * button. Defaults to Landscape (matches the pre-existing forced-
 * landscape behaviour in DesktopScreen.kt for #109/surf5726). The
 * button cycles Landscape -> Portrait -> Auto and back. `Auto` here
 * means "follow the system / device orientation".
 *
 * Mirror of the same enum in feature/rdp/RdpScreen.kt — kept private
 * in each module to avoid a cross-feature dependency for ~15 LOC.
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
 * UNSPECIFIED -> LANDSCAPE`. Public so external owners (e.g.
 * DesktopViewModel) can use the same cycle order as the toolbar
 * button.
 */
fun cycleVncOrientation(current: Int): Int =
    OrientationMode.fromActivityValue(current).next().activityValue

private fun androidKeyToKeySym(key: Key): Int? = when (key) {
    Key.Enter -> XK_RETURN
    Key.Tab -> XK_TAB
    Key.Escape -> XK_ESCAPE
    Key.Backspace -> XK_BACKSPACE
    Key.Delete -> XK_DELETE
    Key.DirectionLeft -> XK_LEFT
    Key.DirectionRight -> XK_RIGHT
    Key.DirectionUp -> XK_UP
    Key.DirectionDown -> XK_DOWN
    Key.MoveHome -> XK_HOME
    Key.MoveEnd -> XK_END
    Key.PageUp -> XK_PAGE_UP
    Key.PageDown -> XK_PAGE_DOWN
    Key.Insert -> XK_INSERT
    Key.ShiftLeft, Key.ShiftRight -> XK_SHIFT_L
    Key.CtrlLeft, Key.CtrlRight -> XK_CONTROL_L
    Key.AltLeft, Key.AltRight -> XK_ALT_L
    Key.MetaLeft, Key.MetaRight -> XK_SUPER_L
    Key.F1 -> XK_F1
    Key.F2 -> XK_F2
    Key.F3 -> XK_F3
    Key.F4 -> XK_F4
    Key.F5 -> XK_F5
    Key.F6 -> XK_F6
    Key.F7 -> XK_F7
    Key.F8 -> XK_F8
    Key.F9 -> XK_F9
    Key.F10 -> XK_F10
    Key.F11 -> XK_F11
    Key.F12 -> XK_F12
    else -> null
}

