package sh.haven.feature.vnc

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.preferences.ToolbarKey
import sh.haven.core.data.preferences.ToolbarLayout
import kotlin.math.abs

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
    onLongPress: (Int, Int) -> Unit,
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onTypeChar: (Char) -> Unit,
    onKeyDown: (keySym: Int) -> Unit,
    onKeyUp: (keySym: Int) -> Unit,
    onDisconnect: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
    cursor: StateFlow<CursorOverlay?>? = null,
    pointerPos: StateFlow<Pair<Int, Int>>? = null,
    inputMode: String = "DIRECT",
    bandwidthSuggestion: StateFlow<String?>? = null,
    onAcceptBandwidthSuggestion: (() -> Unit)? = null,
    onDismissBandwidthSuggestion: (() -> Unit)? = null,
) {
    val connectedState by connected.collectAsState()
    val frameState by frame.collectAsState()
    val errorState by error.collectAsState()
    val cursorState = cursor?.collectAsState()?.value
    val pointerState = pointerPos?.collectAsState()?.value ?: (0 to 0)

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

    if (connectedState && frameState != null) {
        VncViewer(
            frame = frameState!!,
            fullscreen = fullscreen,
            toolbarLayout = toolbarLayout,
            onTap = onTap,
            onLongPress = onLongPress,
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
            bandwidthSuggestion = bandwidthSuggestion?.collectAsState()?.value,
            onAcceptBandwidthSuggestion = onAcceptBandwidthSuggestion,
            onDismissBandwidthSuggestion = onDismissBandwidthSuggestion,
        )
    } else {
        VncPlaceholder(error = errorState)
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
        onTypeChar = { ch -> viewModel.typeKey(charToKeySym(ch)) },
        onKeyDown = { keySym -> viewModel.sendKey(keySym, true) },
        onKeyUp = { keySym -> viewModel.sendKey(keySym, false) },
        onDisconnect = { viewModel.disconnect() },
        onFullscreenChanged = onFullscreenChanged,
        cursor = viewModel.cursor,
        pointerPos = viewModel.pointerPos,
    )
}

@Composable
private fun VncPlaceholder(error: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("VNC", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Add a connection on the Connections tab",
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
        }
    }
}

@Composable
private fun VncViewer(
    frame: Bitmap,
    fullscreen: Boolean,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onTap: (Int, Int) -> Unit,
    onLongPress: (Int, Int) -> Unit,
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
    bandwidthSuggestion: String? = null,
    onAcceptBandwidthSuggestion: (() -> Unit)? = null,
    onDismissBandwidthSuggestion: (() -> Unit)? = null,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(frame) { frame.asImageBitmap() }
    val cursorImage = remember(cursor?.bitmap) { cursor?.bitmap?.asImageBitmap() }

    // Zoom & pan state
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

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

    // Keyboard
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var keyboardVisible by remember { mutableStateOf(false) }

    // Modifier state for VNC key toolbar
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }

    // Fullscreen overlay toolbar
    var overlayVisible by remember { mutableStateOf(false) }

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

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
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
                        text = "Slow connection — switch to 256 colours for usable performance?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAcceptBandwidthSuggestion) { Text("Switch") }
                    TextButton(onClick = onDismissBandwidthSuggestion) { Text("Dismiss") }
                }
            }
        }
        // VNC canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
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
                        var gestureStarted = false
                        var cumulativeScrollY = 0f
                        var totalMovement = 0f
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
                                if (totalFingers == 1 && !longPressFired && !dragging && totalMovement < touchSlopPx && !isFollowUpTouch) {
                                    val (vx, vy) = if (inputMode == "TOUCHPAD") {
                                        virtualCursor
                                    } else {
                                        screenToVnc(
                                            lastSinglePos, viewSize,
                                            frame.width, frame.height,
                                            zoom, panX, panY,
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

                                if (gestureStarted) {
                                    if (prevSpan > 0f && span > 0f) {
                                        val requestedScale = span / prevSpan
                                        val newZoom = (zoom * requestedScale).coerceIn(0.5f, 5f)
                                        // Keep the content point under the pinch
                                        // centroid stationary during the zoom.
                                        // graphicsLayer's default TransformOrigin
                                        // is the view center, so the pivot is
                                        // (cx, cy), not (0, 0).
                                        //   pan' = (centroid - center)(1 - r) + pan * r
                                        // where r is the *actual* applied scale
                                        // (may be less than the finger-requested
                                        // scale if we hit the min/max zoom clamp).
                                        val actualScale = if (zoom > 0f) newZoom / zoom else 1f
                                        val cx = viewSize.width / 2f
                                        val cy = viewSize.height / 2f
                                        panX = (centroid.x - cx) * (1 - actualScale) + panX * actualScale
                                        panY = (centroid.y - cy) * (1 - actualScale) + panY * actualScale
                                        zoom = newZoom
                                    }
                                    val dx = centroid.x - prevCentroid.x
                                    val dy = centroid.y - prevCentroid.y
                                    panX += dx
                                    panY += dy

                                    cumulativeScrollY += centroid.y - prevCentroid.y
                                    if (abs(cumulativeScrollY) > 40f) {
                                        if (cumulativeScrollY < 0) onScrollUp() else onScrollDown()
                                        cumulativeScrollY = 0f
                                    }
                                }

                                gestureStarted = true
                                prevCentroid = centroid
                                prevSpan = span

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
                                        if (isFollowUpTouch && !dragButtonPressed) {
                                            onDragStart(nx, ny)
                                            dragButtonPressed = true
                                        } else {
                                            onDrag(nx, ny)
                                        }
                                    }
                                } else {
                                    val pos = screenToVnc(
                                        change.position, viewSize,
                                        frame.width, frame.height,
                                        zoom, panX, panY,
                                    )
                                    // Start drag (button 1 press) once movement exceeds touch slop
                                    if (!longPressFired && !dragging && totalMovement >= touchSlopPx) {
                                        onDragStart(pos.first, pos.second)
                                        dragging = true
                                    } else if (dragging) {
                                        onDrag(pos.first, pos.second)
                                    }
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        // Release button 1 if drag was active (direct-mode
                        // drag or touchpad tap-then-drag).
                        if (dragging || dragButtonPressed) {
                            onDragEnd()
                        }

                        // Short tap with little movement = click (skip if
                        // long press fired). Record the lift time so a
                        // subsequent touch within 300 ms is treated as a
                        // tap-then-drag follow-up.
                        if (totalFingers == 1 && totalMovement < touchSlopPx && !longPressFired) {
                            val (vx, vy) = if (inputMode == "TOUCHPAD") {
                                virtualCursor
                            } else {
                                screenToVnc(
                                    lastSinglePos, viewSize,
                                    frame.width, frame.height,
                                    zoom, panX, panY,
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
                drawVncFrame(imageBitmap, frame.width, frame.height)
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
                    // Characters were typed
                    val added = newText.substring(oldText.length)
                    for (ch in added) {
                        onTypeChar(ch)
                    }
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

        // VNC key extension rows (hidden in fullscreen)
        if (!fullscreen) {
            VncKeyToolbar(
                layout = toolbarLayout,
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
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
                onVncKey = { keySym ->
                    onKeyDown(keySym)
                    onKeyUp(keySym)
                    // Auto-release modifiers after key press
                    if (ctrlActive) { onKeyUp(XK_CONTROL_L); ctrlActive = false }
                    if (altActive) { onKeyUp(XK_ALT_L); altActive = false }
                    if (shiftActive) { onKeyUp(XK_SHIFT_L); shiftActive = false }
                },
                onToggleKeyboard = {
                    keyboardVisible = !keyboardVisible
                    if (keyboardVisible) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    } else {
                        keyboardController?.hide()
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onDisconnect) {
                    Text("Disconnect")
                }

                Spacer(Modifier.width(8.dp))

                // Keyboard toggle
                IconButton(onClick = {
                    keyboardVisible = !keyboardVisible
                    if (keyboardVisible) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    } else {
                        keyboardController?.hide()
                    }
                }) {
                    Icon(
                        if (keyboardVisible) Icons.Default.KeyboardHide
                        else Icons.Default.Keyboard,
                        contentDescription = "Toggle keyboard",
                    )
                }

                Spacer(Modifier.weight(1f))

                // Reset zoom button
                if (zoom != 1f || panX != 0f || panY != 0f) {
                    Button(onClick = {
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                    }) {
                        Text("Reset Zoom")
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Fullscreen button
                IconButton(onClick = onToggleFullscreen) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
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

        // Corner hotspot — top-right (visible when overlay is hidden)
        AnimatedVisibility(
            visible = !overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Surface(
                onClick = { overlayVisible = true },
                shape = RoundedCornerShape(bottomStart = 12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Session menu",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
        }

        // Floating toolbar overlay — rendered last so it's on top of the dismiss scrim
        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
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
                        Icon(Icons.Default.Close, contentDescription = "Disconnect")
                    }
                    IconButton(onClick = {
                        keyboardVisible = !keyboardVisible
                        if (keyboardVisible) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        } else {
                            keyboardController?.hide()
                        }
                    }) {
                        Icon(
                            if (keyboardVisible) Icons.Default.KeyboardHide
                            else Icons.Default.Keyboard,
                            contentDescription = "Toggle keyboard",
                        )
                    }
                    if (zoom != 1f || panX != 0f || panY != 0f) {
                        IconButton(onClick = {
                            zoom = 1f
                            panX = 0f
                            panY = 0f
                        }) {
                            Icon(
                                Icons.Default.FitScreen,
                                contentDescription = "Reset zoom",
                            )
                        }
                    }
                    IconButton(onClick = {
                        overlayVisible = false
                        onToggleFullscreen()
                    }) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen")
                    }
                }
            }
        }
    }
    } // end Box
}

private fun DrawScope.drawVncFrame(
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
) {
    val viewW = size.width
    val viewH = size.height
    val scale = minOf(viewW / fbWidth, viewH / fbHeight)
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
    val fitScale = minOf(viewW / fbWidth, viewH / fbHeight)
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
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onVncKey: (keySym: Int) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val presentNavKeys = layout.rows.flatten()
        .filterIsInstance<sh.haven.core.data.preferences.ToolbarItem.BuiltIn>()
        .filter { it.key in VNC_NAV_KEYS }
        .map { it.key }
        .toSet()

    Surface(tonalElevation = 2.dp) {
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
                for (row in layout.rows) {
                    if (row.isEmpty()) continue
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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

    LaunchedEffect(isPressed) {
        if (isPressed) {
            didRepeat = false
            delay(VNC_REPEAT_DELAY_MS)
            didRepeat = true
            while (true) {
                onClick()
                delay(VNC_REPEAT_INTERVAL_MS)
            }
        }
    }

    FilledTonalButton(
        onClick = {}, // handled by pointerInteropFilter
        modifier = modifier.pointerInteropFilter { motionEvent ->
            when (motionEvent.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!didRepeat) onClick()
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
                Icon(Icons.Default.Keyboard, contentDescription = "Toggle keyboard", modifier = Modifier.size(18.dp))
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

/** Convert a printable character to its X11 KeySym. */
fun charToKeySym(ch: Char): Int = when (ch) {
    '\n', '\r' -> XK_RETURN
    '\t' -> XK_TAB
    '\b' -> XK_BACKSPACE
    else -> ch.code // Latin-1 characters map directly to Unicode code point
}

/** Map Android Key to X11 KeySym for special (non-printable) keys. */
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
    else -> null
}
