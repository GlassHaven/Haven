package sh.haven.feature.vnc

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.ui.CursorOverlay
import sh.haven.core.ui.DesktopOrientationMode
import sh.haven.core.ui.RemoteDesktopCameraFollowEffect
import sh.haven.core.ui.RemoteDesktopImeField
import sh.haven.core.ui.RemoteDesktopViewport
import sh.haven.core.ui.RemoteMouseButtonToggles
import sh.haven.core.ui.RemoteScrollModeToggle
import sh.haven.core.ui.RemoteTrackpadSensitivityDialog
import sh.haven.core.ui.rememberRemoteDesktopViewportState
import sh.haven.core.vnc.protocol.AuthenticationFailedException
import sh.haven.core.vnc.protocol.HandshakingFailedException
import sh.haven.core.vnc.protocol.VncException
import sh.haven.feature.vnc.R

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
    /** Push text to the remote VNC clipboard via RFB ClientCutText (#718). */
    onSendClipboard: ((String) -> Unit)? = null,
    onKeyDown: (keySym: Int) -> Unit,
    onKeyUp: (keySym: Int) -> Unit,
    onDisconnect: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
    cursor: StateFlow<CursorOverlay?>? = null,
    pointerPos: StateFlow<Pair<Int, Int>>? = null,
    inputMode: String = "DIRECT",
    /** Switch DIRECT/TOUCHPAD input mode from the toolbar (#183). */
    onSetInputMode: ((String) -> Unit)? = null,
    /** TOUCHPAD-mode speed multiplier; gesture math scales deltas by it (#696). */
    touchpadSensitivity: Float = 1f,
    /** Persist trackpad sensitivity from the toolbar (#696). Null hides the control. */
    onSetTouchpadSensitivity: ((Float) -> Unit)? = null,
    /** Configurable touch gesture click bindings (#836). */
    mouseGestures: sh.haven.core.ui.DesktopMouseGestureConfig = sh.haven.core.ui.DesktopMouseGestureConfig.DEFAULT,
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
    isActive: Boolean = true,
    sessionKey: String? = null,
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
            onSendClipboard = onSendClipboard,
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
            touchpadSensitivity = touchpadSensitivity,
            onSetTouchpadSensitivity = onSetTouchpadSensitivity,
            mouseGestures = mouseGestures,
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
            isActive = isActive,
            sessionKey = sessionKey,
        )
    } else {
        VncPlaceholder(
            error = errorState,
            onRetry = onRetry,
            onClose = onClose,
        )
    }
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
private fun InputModeToggle(
    inputMode: String,
    onSetInputMode: ((String) -> Unit)?,
    onSetTouchpadSensitivity: ((Float) -> Unit)?,
    onOpenSensitivity: () -> Unit,
    vertical: Boolean = false,
) {
    if (onSetInputMode == null) return
    val touchpad = inputMode == "TOUCHPAD"
    // #696 reporter follow-up: in the corner-docked fullscreen overlay the
    // toggle sits inside a vertical panel — stacking here keeps the strip's
    // width at one button instead of widening it for a horizontal row.
    val content: @Composable () -> Unit = {
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
        // Speed control (#696): only while trackpad mode is on — direct touch
        // has no speed to tune. Opens the sensitivity dialog.
        if (touchpad && onSetTouchpadSensitivity != null) {
            val speedDesc = stringResource(R.string.vnc_cd_trackpad_sensitivity)
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
    onSendClipboard: ((String) -> Unit)? = null,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onToggleFullscreen: () -> Unit,
    onDisconnect: () -> Unit,
    cursor: CursorOverlay? = null,
    pointerPos: Pair<Int, Int> = 0 to 0,
    inputMode: String = "DIRECT",
    /** TOUCHPAD-mode speed multiplier; gesture math scales deltas by it (#696). */
    touchpadSensitivity: Float = 1f,
    /** Persist trackpad sensitivity from the toolbar (#696). Null hides the control. */
    onSetTouchpadSensitivity: ((Float) -> Unit)? = null,
    /** Configurable touch gesture click bindings (#836). */
    mouseGestures: sh.haven.core.ui.DesktopMouseGestureConfig = sh.haven.core.ui.DesktopMouseGestureConfig.DEFAULT,
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
    isActive: Boolean = true,
    sessionKey: String? = null,
) {
    val orientationMode = DesktopOrientationMode.fromActivityValue(currentOrientation)
    val orientationDesc = when (orientationMode) {
        DesktopOrientationMode.Landscape -> stringResource(R.string.vnc_orientation_landscape_desc)
        DesktopOrientationMode.Portrait -> stringResource(R.string.vnc_orientation_portrait_desc)
        DesktopOrientationMode.Auto -> stringResource(R.string.vnc_orientation_auto_desc)
    }
    // App-window fullscreen: cover/crop-fill the screen (no letterbox — phones
    // have rounded corners so losing a sliver is fine) and floor the digital
    // zoom at the fill scale so a pinch-out can't expose a black border. The
    // desktop viewer stays contain so its edges/taskbar aren't cropped.
    val coverFill = fullscreen && twoFingerZoom
    val viewportState = rememberRemoteDesktopViewportState(
        inputMode = inputMode,
        pointerPos = pointerPos,
        saveable = false,
    )
    val externalDisplay = sh.haven.core.ui.rememberExternalDisplayProjection(
        frame = frame,
        cursor = cursor,
        pointerX = pointerPos.first,
        pointerY = pointerPos.second,
        showCursor = true,
        coverFill = coverFill,
        sessionKey = sessionKey,
    )

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
    // space. Snap back to the fitted view (zoom 1, no pan) on any size change
    // in app-window mode.
    if (twoFingerZoom) {
        LaunchedEffect(frame.width, frame.height) {
            viewportState.resetTransform()
        }
    }

    RemoteDesktopCameraFollowEffect(
        state = viewportState,
        cursorPos = pointerPos,
        enabled = inputMode == "TOUCHPAD",
        fbWidth = frame.width,
        fbHeight = frame.height,
        coverFill = coverFill,
    )

    // (Orientation state lives outside this composable — owners are
    // VncScreen for the standalone path and DesktopViewModel for the
    // multi-session Desktop view. We only render the icon based on
    // the value passed in.)

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
    // Modifier state for VNC key toolbar (hoisted above handleHardwareKey so
    // hardware key events can read sticky Shift and release sticky modifiers).
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    // Super/Logo (Mod4) — needed to drive nested-Wayland compositor
    // keybinds (Sway/Hyprland/niri all use Super for $mod), which the
    // in-app VNC viewer surfaces (#171).
    var superActive by remember { mutableStateOf(false) }
    val releaseActiveModifiers: () -> Unit = {
        if (ctrlActive) { onKeyUp(XK_CONTROL_L); ctrlActive = false }
        if (altActive) { onKeyUp(XK_ALT_L); altActive = false }
        if (shiftActive) { onKeyUp(XK_SHIFT_L); shiftActive = false }
        if (superActive) { onKeyUp(XK_SUPER_L); superActive = false }
    }

    val hardwareKeyFocus = remember { FocusRequester() }
    var canvasFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val pressedHardwareKeySyms = remember { mutableMapOf<Key, Int>() }
    val handleHardwareKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (fieldFocused) {
            // Soft keyboard attached: the field's own onPreviewKeyEvent /
            // onValueChange commit path handles input. Defer.
            false
        } else {
            val native = event.nativeKeyEvent
            val shiftHeld = shiftActive ||
                (native.metaState and AndroidKeyEvent.META_SHIFT_MASK) != 0
            // Strip Ctrl/Alt/Meta when querying getUnicodeChar so Ctrl+C still
            // resolves to 'c' while XK_CONTROL_L is held on the wire (#774).
            val charMeta = (native.metaState and (
                AndroidKeyEvent.META_CTRL_MASK or
                    AndroidKeyEvent.META_ALT_MASK or
                    AndroidKeyEvent.META_META_MASK
                ).inv()) or (if (shiftActive) AndroidKeyEvent.META_SHIFT_ON else 0)
            val resolvedKeySym = hardwareKeyEventToKeySym(
                key = event.key,
                unicodeChar = native.getUnicodeChar(charMeta),
                shiftHeld = shiftHeld,
            )
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (resolvedKeySym != null) {
                        pressedHardwareKeySyms[event.key] = resolvedKeySym
                        onKeyDown(resolvedKeySym)
                        true
                    } else {
                        false
                    }
                }
                KeyEventType.KeyUp -> {
                    val keySym = pressedHardwareKeySyms.remove(event.key) ?: resolvedKeySym
                    if (keySym != null) {
                        onKeyUp(keySym)
                        if (!isModifierKeySym(keySym)) {
                            releaseActiveModifiers()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
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

    // The system re-shows the IME across a configuration change even when the
    // user dismissed it; enforce the invariant that the IME is up only when we
    // asked for it. Scoped to this screen's own focus holders so the terminal
    // tab's keyboard isn't hidden by this effect (isImeVisible is window-global).
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

    // Fullscreen overlay toolbar
    var overlayVisible by remember { mutableStateOf(false) }
    var showGestureHelp by remember { mutableStateOf(false) }
    // Trackpad sensitivity dialog (#696) — opened from the speed button next
    // to the input-mode toggle while TOUCHPAD is active.
    var showSensitivityDialog by remember { mutableStateOf(false) }
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

    // Compose-then-send bar (VncComposeBar): type and check text locally,
    // then send it in one go. Replaces the key toolbar while open.
    var composeOpen by remember { mutableStateOf(false) }
    val openCompose: () -> Unit = { composeOpen = true }
    val closeCompose: () -> Unit = {
        composeOpen = false
        if (!physicalKeyboardAttached) {
            keyboardVisible = false
            keyboardController?.hide()
        }
        hardwareKeyFocus.requestFocus()
    }
    val sendComposed: (String) -> Unit = { keystrokes ->
        // Release sticky modifiers and a held mouse button *before* typing:
        // the composed text must arrive as plain keystrokes, not Ctrl+letter.
        releaseActiveModifiers()
        heldButton?.let { onReleaseButton(it); heldButton = null }
        onTypeText(keystrokes)
    }

    // Auto-hide overlay after 4 seconds
    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            delay(4000)
            overlayVisible = false
        }
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
        // Shared remote-desktop canvas & gesture state machine (:core:ui)
        RemoteDesktopViewport(
            frame = frame,
            state = viewportState,
            inputMode = inputMode,
            touchpadSensitivity = touchpadSensitivity,
            mouseGestures = mouseGestures,
            twoFingerScroll = twoFingerScroll,
            scrollWhenUnzoomed = twoFingerZoom,
            coverFill = coverFill,
            minZoom = if (coverFill) 1f else 0.5f,
            maxZoom = 10f,
            heldButton = heldButton,
            isProjectingExternal = externalDisplay.isProjecting,
            cursor = cursor,
            pointerPos = pointerPos,
            hardwareKeyFocus = hardwareKeyFocus,
            onCanvasFocusChanged = { canvasFocused = it },
            shouldReclaimHardwareKeyFocusOnTap = {
                !currentImeVisible && !currentFieldFocused &&
                    currentKeyboardVisible == currentPhysicalKeyboardAttached
            },
            onTap = onTap,
            onMiddleClick = onMiddleClick,
            onRightClick = onLongPress,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onScrollUp = onScrollUp,
            onScrollDown = onScrollDown,
            onPressButton = onPressButton,
            onReleaseButton = onReleaseButton,
            onThreeFingerPinchRatio = if (coverFill && onChangeScale != null) {
                { ratio ->
                    outputScale = (outputScale * ratio).coerceIn(0.5f, 3f)
                    val q = Math.round(outputScale / 0.25f) * 0.25f
                    if (q != lastSentScale) {
                        lastSentScale = q
                        onChangeScale(q)
                    }
                }
            } else {
                null
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        RemoteDesktopImeField(
            focusRequester = focusRequester,
            onFieldFocusChanged = { fieldFocused = it },
            hasActiveModifier = ctrlActive || altActive || shiftActive || superActive,
            onBackspace = {
                onKeyDown(XK_BACKSPACE)
                onKeyUp(XK_BACKSPACE)
            },
            onTypeChar = onTypeChar,
            onTypeText = onTypeText,
            releaseActiveModifiers = releaseActiveModifiers,
            onPreviewKey = { event ->
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

        val clipboardManager = LocalClipboardManager.current
        val pasteClipboard: () -> Unit = {
            val text = clipboardManager.getText()?.text
            if (!text.isNullOrEmpty()) {
                onSendClipboard?.invoke(text)
                onTypeText(text)
                releaseActiveModifiers()
            }
        }

        var reorderMode by remember { mutableStateOf(false) }

        if (composeOpen) {
            VncComposeBar(onSend = sendComposed, onDismiss = closeCompose)
        } else if (keyboardVisible || imeVisible) {
            sh.haven.core.toolbar.KeyboardToolbar(
                onSendBytes = { bytes ->
                    handleVncToolbarBytes(
                        bytes = bytes,
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        shiftActive = shiftActive,
                        superActive = superActive,
                        onKeyDown = onKeyDown,
                        onKeyUp = onKeyUp,
                        onTypeChar = onTypeChar,
                        onTypeText = onTypeText,
                        releaseModifiers = releaseActiveModifiers,
                    )
                },
                onDispatchKey = { _, key ->
                    val keySym = vtermKeyToKeySym(key)
                    if (keySym != null) {
                        onKeyDown(keySym)
                        onKeyUp(keySym)
                        releaseActiveModifiers()
                    }
                },
                focusRequester = focusRequester,
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                winActive = superActive,
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
                onShiftUsed = {
                    if (shiftActive) {
                        onKeyUp(XK_SHIFT_L)
                        shiftActive = false
                    }
                },
                onToggleWin = {
                    superActive = !superActive
                    if (!superActive) onKeyUp(XK_SUPER_L) else onKeyDown(XK_SUPER_L)
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
                IconButton(onClick = toggleKeyboard) {
                    Icon(
                        if (keyboardVisible) Icons.Default.KeyboardHide
                        else Icons.Default.Keyboard,
                        contentDescription = stringResource(R.string.vnc_cd_toggle_keyboard),
                    )
                }

                // Paste from Android clipboard (#718): pushes text to remote RFB
                // CutText (for Ctrl+V / middle-click paste via vncconfig) AND types
                // it into the focused window.
                IconButton(onClick = pasteClipboard) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = stringResource(R.string.vnc_cd_paste),
                    )
                }

                // Compose-then-send text (VncComposeBar) — same icon as the
                // terminal toolbar's text-input key.
                IconButton(onClick = openCompose) {
                    Icon(
                        Icons.Default.EditNote,
                        contentDescription = stringResource(R.string.vnc_compose_open),
                    )
                }

                IconButton(onClick = onCycleOrientation) {
                    Icon(orientationMode.icon, contentDescription = orientationDesc)
                }

                // Explicit mouse-button hold toggles (L/M/R) — #183.
                RemoteMouseButtonToggles(held = heldButton, onToggle = toggleHeldButton)

                // Direct/trackpad input-mode toggle + speed button — #183/#696.
                InputModeToggle(
                    inputMode, onSetInputMode, onSetTouchpadSensitivity,
                    onOpenSensitivity = { showSensitivityDialog = true },
                )
                // Desktop only: two-finger drag = viewport pan vs remote scroll — #286.
                if (!twoFingerZoom) {
                    RemoteScrollModeToggle(
                        twoFingerScroll = twoFingerScroll,
                        scrollOnDescription = stringResource(R.string.vnc_cd_two_finger_scroll_on),
                        viewportDescription = stringResource(R.string.vnc_cd_two_finger_viewport),
                    ) { twoFingerScroll = !twoFingerScroll }
                }
                if (externalDisplay.hasExternalDisplay) {
                    sh.haven.core.ui.ExternalDisplayToggle(externalDisplay.isProjecting) {
                        externalDisplay.toggleProjection()
                    }
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
                if (viewportState.isTransformed) {
                    IconButton(onClick = { viewportState.resetTransform() }) {
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
                val clipboardManager = LocalClipboardManager.current
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
                    IconButton(onClick = toggleKeyboard) {
                        Icon(
                            if (keyboardVisible) Icons.Default.KeyboardHide
                            else Icons.Default.Keyboard,
                            contentDescription = stringResource(R.string.vnc_cd_toggle_keyboard),
                        )
                    }
                    IconButton(onClick = {
                        overlayVisible = false
                        val text = clipboardManager.getText()?.text
                        if (!text.isNullOrEmpty()) {
                            onSendClipboard?.invoke(text)
                            onTypeText(text)
                        }
                    }) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = stringResource(R.string.vnc_cd_paste),
                        )
                    }
                    IconButton(onClick = {
                        overlayVisible = false
                        openCompose()
                    }) {
                        Icon(
                            Icons.Default.EditNote,
                            contentDescription = stringResource(R.string.vnc_compose_open),
                        )
                    }
                    IconButton(onClick = onCycleOrientation) {
                        Icon(orientationMode.icon, contentDescription = orientationDesc)
                    }
                    // Explicit mouse-button hold toggles (L/M/R) — #183.
                    RemoteMouseButtonToggles(held = heldButton, onToggle = toggleHeldButton)
                    // Direct/trackpad input-mode toggle + speed button — #183/#696.
                    InputModeToggle(
                        inputMode, onSetInputMode, onSetTouchpadSensitivity, vertical = true,
                        onOpenSensitivity = { showSensitivityDialog = true },
                    )
                    // Desktop only: two-finger drag = viewport pan vs remote scroll — #286.
                    if (!twoFingerZoom) {
                        RemoteScrollModeToggle(
                            twoFingerScroll = twoFingerScroll,
                            scrollOnDescription = stringResource(R.string.vnc_cd_two_finger_scroll_on),
                            viewportDescription = stringResource(R.string.vnc_cd_two_finger_viewport),
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

    if (showSensitivityDialog) {
        RemoteTrackpadSensitivityDialog(
            sensitivity = touchpadSensitivity,
            title = stringResource(R.string.vnc_cd_trackpad_sensitivity),
            closeLabel = stringResource(R.string.vnc_action_close),
            onSetSensitivity = { onSetTouchpadSensitivity?.invoke(it) },
            onDismiss = { showSensitivityDialog = false },
        )
    }
    } // end Box
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

internal fun isHardwareMouseGesture(
    pointerType: PointerType,
    buttons: PointerButtons,
    motionSource: Int,
): Boolean = sh.haven.core.ui.isRemoteHardwareMouseGesture(pointerType, buttons, motionSource)

internal fun resolveHardwareMouseButton(
    buttons: PointerButtons,
    motionButtonState: Int,
): Int = sh.haven.core.ui.resolveRemoteHardwareMouseButton(buttons, motionButtonState)

internal typealias VncWheelNotchAccumulator = sh.haven.core.ui.RemoteWheelNotchAccumulator

/** Map a libvterm key constant from KeyboardToolbar.onDispatchKey to an X11 KeySym. */
internal fun vtermKeyToKeySym(key: Int): Int? = when (key) {
    5 -> XK_UP
    6 -> XK_DOWN
    7 -> XK_LEFT
    8 -> XK_RIGHT
    9 -> XK_INSERT
    10 -> XK_DELETE
    11 -> XK_HOME
    12 -> XK_END
    13 -> XK_PAGE_UP
    14 -> XK_PAGE_DOWN
    in 257..268 -> {
        val fn = key - 256 // 1..12
        XK_F1 + (fn - 1)
    }
    else -> null
}

/** Translate KeyboardToolbar.onSendBytes payloads into VNC KeySym events. */
internal fun handleVncToolbarBytes(
    bytes: ByteArray,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    superActive: Boolean = false,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onTypeChar: (Char) -> Unit,
    onTypeText: (String) -> Unit,
    releaseModifiers: () -> Unit,
) {
    if (bytes.isEmpty()) return
    val payload = if (altActive && bytes.size == 2 && bytes[0] == 0x1b.toByte() && bytes[1] != '['.code.toByte()) {
        byteArrayOf(bytes[1])
    } else {
        bytes
    }
    val str = String(payload, Charsets.UTF_8)
    when {
        payload.contentEquals(byteArrayOf(0x1b)) -> {
            onKeyDown(XK_ESCAPE)
            onKeyUp(XK_ESCAPE)
            releaseModifiers()
        }
        payload.contentEquals(byteArrayOf(0x09)) -> {
            onKeyDown(XK_TAB)
            onKeyUp(XK_TAB)
            releaseModifiers()
        }
        payload.contentEquals(byteArrayOf(0x0d)) -> {
            onKeyDown(XK_RETURN)
            onKeyUp(XK_RETURN)
            releaseModifiers()
        }
        str == "\u001b[Z" -> {
            if (!shiftActive) onKeyDown(XK_SHIFT_L)
            onKeyDown(XK_TAB)
            onKeyUp(XK_TAB)
            if (!shiftActive) onKeyUp(XK_SHIFT_L)
            releaseModifiers()
        }
        str == "\u001b[3;8~" -> {
            if (!ctrlActive) onKeyDown(XK_CONTROL_L)
            if (!altActive) onKeyDown(XK_ALT_L)
            onKeyDown(XK_DELETE)
            onKeyUp(XK_DELETE)
            if (!altActive) onKeyUp(XK_ALT_L)
            if (!ctrlActive) onKeyUp(XK_CONTROL_L)
            releaseModifiers()
        }
        str == "\u001b[3;6~" -> {
            if (!ctrlActive) onKeyDown(XK_CONTROL_L)
            onKeyDown(XK_DELETE)
            onKeyUp(XK_DELETE)
            if (!ctrlActive) onKeyUp(XK_CONTROL_L)
            releaseModifiers()
        }
        str == "\u001b[2;5~" -> {
            if (!ctrlActive) onKeyDown(XK_CONTROL_L)
            onKeyDown(XK_INSERT)
            onKeyUp(XK_INSERT)
            if (!ctrlActive) onKeyUp(XK_CONTROL_L)
            releaseModifiers()
        }
        str == "\u001b[2;2~" -> {
            if (!shiftActive) onKeyDown(XK_SHIFT_L)
            onKeyDown(XK_INSERT)
            onKeyUp(XK_INSERT)
            if (!shiftActive) onKeyUp(XK_SHIFT_L)
            releaseModifiers()
        }
        payload.size == 1 && payload[0] in 1..26 -> {
            val ch = 'a' + (payload[0].toInt() - 1)
            if (!ctrlActive) {
                onKeyDown(XK_CONTROL_L)
                onTypeChar(ch)
                onKeyUp(XK_CONTROL_L)
                releaseModifiers()
            } else {
                onTypeChar(ch)
                if (superActive || shiftActive) releaseModifiers()
            }
        }
        payload.size == 1 && (ctrlActive || altActive) -> {
            onTypeChar(payload[0].toInt().toChar())
            if (superActive || shiftActive) releaseModifiers()
        }
        payload.size == 1 && (superActive || shiftActive) -> {
            onTypeChar(payload[0].toInt().toChar())
            releaseModifiers()
        }
        else -> {
            onTypeText(str)
            releaseModifiers()
        }
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
private const val XK_PRINT = 0xff61
private const val XK_INSERT = 0xff63
private const val XK_MENU = 0xff67
private const val XK_NUM_LOCK = 0xff7f
private const val XK_PAUSE = 0xff13
private const val XK_SCROLL_LOCK = 0xff14
private const val XK_SHIFT_L = 0xffe1
private const val XK_SHIFT_R = 0xffe2
private const val XK_CONTROL_L = 0xffe3
private const val XK_CONTROL_R = 0xffe4
private const val XK_CAPS_LOCK = 0xffe5
private const val XK_ALT_L = 0xffe9
private const val XK_ALT_R = 0xffea
private const val XK_SUPER_L = 0xffeb
private const val XK_SUPER_R = 0xffec
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

/**
 * Diff one IME commit against the text the field held before it, returning
 * the characters to type and the number of backspaces to send.
 *
 * The hidden VNC text field mirrors whatever the IME reports, so the delta
 * between two consecutive values is exactly what changed — but only if the
 * baseline is the previous value. The old code reset the field to a `" "`
 * sentinel after every commit and diffed against that, so a growing IME
 * buffer was sliced at a fixed offset: characters were dropped and earlier
 * ones re-emitted (bug #5 — the capture rig saw `a, c, A, AB, C, C1, C12`
 * for `abcABC123`).
 *
 * Trimming the common prefix and common suffix handles every commit shape:
 * append (the tail), backspace (the removed run), autocorrect word
 * replacement (delete the old interior, type the new one), and mid-string
 * edits.
 */
internal fun diffImeCommit(oldText: String, newText: String): Pair<String?, Int> =
    sh.haven.core.ui.diffRemoteImeCommit(oldText, newText)

/** Convert a printable character to its X11 KeySym. */
fun charToKeySym(ch: Char): Int = when (ch) {
    '\n', '\r' -> XK_RETURN
    '\t' -> XK_TAB
    '\b' -> XK_BACKSPACE
    else -> ch.code // Latin-1 characters map directly to Unicode code point
}

/**
 * Cycle the activity-orientation constant `LANDSCAPE -> PORTRAIT ->
 * UNSPECIFIED -> LANDSCAPE`. Public so external owners (e.g.
 * DesktopViewModel) can use the same cycle order as the toolbar
 * button.
 */
fun cycleVncOrientation(current: Int): Int =
    DesktopOrientationMode.fromActivityValue(current).next().activityValue

/**
 * Convert a Unicode code point from [AndroidKeyEvent.getUnicodeChar] to its
 * X11 RFB KeySym (#774).
 *
 * - Printable ASCII (`0x20..0x7e`) and Latin-1 (`0xa0..0xff`) map 1:1 to their
 *   code point.
 * - Other BMP / supplementary Unicode characters (`0x0100..0x10ffff`) use the
 *   standard X11 Unicode keysym encoding `0x01000000 or codePoint`.
 * - Control characters (`< 0x20`, `0x7f..0x9f`) and dead-key combining accents
 *   ([android.view.KeyCharacterMap.COMBINING_ACCENT]) return `null`.
 */
internal fun unicodeToKeySym(codePoint: Int): Int? {
    if (codePoint <= 0 || (codePoint and android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) {
        return null
    }
    return when (codePoint) {
        in 0x20..0x7e, in 0xa0..0xff -> codePoint
        in 0x0100..0x10ffff -> 0x01000000 or codePoint
        else -> null
    }
}

/**
 * Resolve a physical/hardware [Key] event to its X11 KeySym (#774).
 *
 * Non-printable special keys (arrows, F1..F12, modifiers, Enter, Tab, Backspace,
 * Escape, Delete, Home/End/PgUp/PgDn/Insert, NumPad Enter) are matched first via
 * [androidKeyToKeySym] so they never depend on `getUnicodeChar`. Printable keys
 * use [unicodeChar] (queried from the native `KeyEvent` with Ctrl/Alt/Meta
 * stripped so `Ctrl+C` still resolves to `'c'` while `XK_CONTROL_L` is held on
 * the wire), falling back to [androidPrintableKeyToKeySym] when the device's
 * key character map reports `0`.
 */
internal fun hardwareKeyEventToKeySym(
    key: Key,
    unicodeChar: Int,
    shiftHeld: Boolean = false,
): Int? {
    androidKeyToKeySym(key)?.let { return it }
    unicodeToKeySym(unicodeChar)?.let { return it }
    return androidPrintableKeyToKeySym(key, shiftHeld)
}

internal fun androidPrintableKeyToKeySym(key: Key, shiftHeld: Boolean = false): Int? = when (key) {
    Key.A -> if (shiftHeld) 'A'.code else 'a'.code
    Key.B -> if (shiftHeld) 'B'.code else 'b'.code
    Key.C -> if (shiftHeld) 'C'.code else 'c'.code
    Key.D -> if (shiftHeld) 'D'.code else 'd'.code
    Key.E -> if (shiftHeld) 'E'.code else 'e'.code
    Key.F -> if (shiftHeld) 'F'.code else 'f'.code
    Key.G -> if (shiftHeld) 'G'.code else 'g'.code
    Key.H -> if (shiftHeld) 'H'.code else 'h'.code
    Key.I -> if (shiftHeld) 'I'.code else 'i'.code
    Key.J -> if (shiftHeld) 'J'.code else 'j'.code
    Key.K -> if (shiftHeld) 'K'.code else 'k'.code
    Key.L -> if (shiftHeld) 'L'.code else 'l'.code
    Key.M -> if (shiftHeld) 'M'.code else 'm'.code
    Key.N -> if (shiftHeld) 'N'.code else 'n'.code
    Key.O -> if (shiftHeld) 'O'.code else 'o'.code
    Key.P -> if (shiftHeld) 'P'.code else 'p'.code
    Key.Q -> if (shiftHeld) 'Q'.code else 'q'.code
    Key.R -> if (shiftHeld) 'R'.code else 'r'.code
    Key.S -> if (shiftHeld) 'S'.code else 's'.code
    Key.T -> if (shiftHeld) 'T'.code else 't'.code
    Key.U -> if (shiftHeld) 'U'.code else 'u'.code
    Key.V -> if (shiftHeld) 'V'.code else 'v'.code
    Key.W -> if (shiftHeld) 'W'.code else 'w'.code
    Key.X -> if (shiftHeld) 'X'.code else 'x'.code
    Key.Y -> if (shiftHeld) 'Y'.code else 'y'.code
    Key.Z -> if (shiftHeld) 'Z'.code else 'z'.code
    Key.Zero -> if (shiftHeld) ')'.code else '0'.code
    Key.One -> if (shiftHeld) '!'.code else '1'.code
    Key.Two -> if (shiftHeld) '@'.code else '2'.code
    Key.Three -> if (shiftHeld) '#'.code else '3'.code
    Key.Four -> if (shiftHeld) '$'.code else '4'.code
    Key.Five -> if (shiftHeld) '%'.code else '5'.code
    Key.Six -> if (shiftHeld) '^'.code else '6'.code
    Key.Seven -> if (shiftHeld) '&'.code else '7'.code
    Key.Eight -> if (shiftHeld) '*'.code else '8'.code
    Key.Nine -> if (shiftHeld) '('.code else '9'.code
    Key.Spacebar -> ' '.code
    Key.Minus -> if (shiftHeld) '_'.code else '-'.code
    Key.Equals -> if (shiftHeld) '+'.code else '='.code
    Key.LeftBracket -> if (shiftHeld) '{'.code else '['.code
    Key.RightBracket -> if (shiftHeld) '}'.code else ']'.code
    Key.Backslash -> if (shiftHeld) '|'.code else '\\'.code
    Key.Semicolon -> if (shiftHeld) ':'.code else ';'.code
    Key.Apostrophe -> if (shiftHeld) '"'.code else '\''.code
    Key.Grave -> if (shiftHeld) '~'.code else '`'.code
    Key.Comma -> if (shiftHeld) '<'.code else ','.code
    Key.Period -> if (shiftHeld) '>'.code else '.'.code
    Key.Slash -> if (shiftHeld) '?'.code else '/'.code
    Key.NumPad0 -> '0'.code
    Key.NumPad1 -> '1'.code
    Key.NumPad2 -> '2'.code
    Key.NumPad3 -> '3'.code
    Key.NumPad4 -> '4'.code
    Key.NumPad5 -> '5'.code
    Key.NumPad6 -> '6'.code
    Key.NumPad7 -> '7'.code
    Key.NumPad8 -> '8'.code
    Key.NumPad9 -> '9'.code
    Key.NumPadAdd -> '+'.code
    Key.NumPadSubtract -> '-'.code
    Key.NumPadMultiply -> '*'.code
    Key.NumPadDivide -> '/'.code
    Key.NumPadDot -> '.'.code
    Key.NumPadEquals -> '='.code
    else -> null
}

internal fun isModifierKeySym(keySym: Int): Boolean = when (keySym) {
    XK_SHIFT_L, XK_SHIFT_R,
    XK_CONTROL_L, XK_CONTROL_R,
    XK_ALT_L, XK_ALT_R,
    XK_SUPER_L, XK_SUPER_R,
    XK_CAPS_LOCK, XK_NUM_LOCK, XK_SCROLL_LOCK,
    -> true
    else -> false
}

internal fun androidKeyToKeySym(key: Key): Int? = when (key) {
    Key.Enter, Key.NumPadEnter -> XK_RETURN
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
    Key.ShiftLeft -> XK_SHIFT_L
    Key.ShiftRight -> XK_SHIFT_R
    Key.CtrlLeft -> XK_CONTROL_L
    Key.CtrlRight -> XK_CONTROL_R
    Key.AltLeft -> XK_ALT_L
    Key.AltRight -> XK_ALT_R
    Key.MetaLeft -> XK_SUPER_L
    Key.MetaRight -> XK_SUPER_R
    Key.CapsLock -> XK_CAPS_LOCK
    Key.NumLock -> XK_NUM_LOCK
    Key.ScrollLock -> XK_SCROLL_LOCK
    Key.PrintScreen -> XK_PRINT
    Key.Break -> XK_PAUSE
    Key.Menu -> XK_MENU
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

/** Map VNC/network exceptions to user-friendly messages with troubleshooting hints. */
fun describeVncError(e: Exception, host: String? = null, port: Int? = null): String {
    val portStr = port?.toString() ?: "5900"
    val hostStr = host ?: "the remote host"
    return when (e) {
        is ConnectException -> buildString {
            append("Connection refused")
            if (e.message?.contains("refused", ignoreCase = true) == true) {
                append(". No VNC server appears to be listening on $hostStr:$portStr.\n\n")
                append("To start a VNC server on the remote host:\n")
                append("  TigerVNC:  vncserver :1\n")
                append("  x11vnc:    x11vnc -display :0 -rfbport $portStr\n")
                append("  wayvnc:    wayvnc 0.0.0.0 $portStr\n\n")
                append("Check: ss -tlnp | grep $portStr")
            }
        }
        is SocketTimeoutException -> buildString {
            append("Connection timed out reaching $hostStr:$portStr.\n\n")
            append("Check:\n")
            append("  - Host address is correct\n")
            append("  - Port $portStr is not blocked by a firewall\n")
            append("  - If tunneling through SSH, the SSH session is still connected")
        }
        is UnknownHostException ->
            "Could not resolve hostname \"$hostStr\". Check the address is correct."
        is NoRouteToHostException ->
            "No route to $hostStr. Check your network connection and that the host is reachable."
        is AuthenticationFailedException -> buildString {
            append("Authentication failed")
            val msg = e.message
            if (msg != null && msg != "Authentication failed") append(": $msg")
            append(".\n\n")
            append("Check your VNC password. VNC passwords are limited to 8 characters.\n")
            append("To reset: vncpasswd ~/.vnc/passwd")
        }
        is HandshakingFailedException -> buildString {
            append("VNC handshake failed: ${e.message}\n\n")
            append("This may indicate:\n")
            append("  - An incompatible VNC server version\n")
            append("  - Something other than a VNC server on port $portStr\n")
            append("  - A web-based VNC proxy (noVNC) instead of a raw VNC port")
        }
        is VncException -> buildString {
            val msg = e.message ?: "Unknown protocol error"
            append("VNC protocol error: $msg\n\n")
            if (msg.contains("encoding", ignoreCase = true)) {
                append("The server is using an encoding Haven doesn't support.\n")
                append("Try setting the server to use Raw or Hextile encoding.")
            } else if (msg.contains("Unexpected end", ignoreCase = true)) {
                append("The server closed the connection unexpectedly.\n")
                append("Check the VNC server logs on the remote host for errors.")
            }
        }
        is java.io.EOFException, is java.io.IOException -> {
            val msg = e.message ?: ""
            if (msg.contains("Broken pipe") || msg.contains("reset by peer") ||
                msg.contains("end of stream", ignoreCase = true) || e is java.io.EOFException
            ) {
                "Connection lost. The VNC server may have stopped or the network dropped."
            } else {
                "Network error: $msg"
            }
        }
        else -> e.message ?: "Unknown error"
    }
}


