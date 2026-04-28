package sh.haven.feature.rdp

import android.graphics.Bitmap
import android.os.SystemClock
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
import kotlinx.coroutines.flow.StateFlow
import sh.haven.core.data.preferences.ToolbarKey
import sh.haven.core.data.preferences.ToolbarLayout
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
    error: StateFlow<String?>,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onTap: (Int, Int) -> Unit,
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
    pointerPos: StateFlow<Pair<Int, Int>>? = null,
    inputMode: String = "DIRECT",
) {
    val connectedState by connected.collectAsState()
    val frameState by frame.collectAsState()
    val errorState by error.collectAsState()
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
        RdpViewer(
            frame = frameState!!,
            fullscreen = fullscreen,
            toolbarLayout = toolbarLayout,
            onTap = onTap,
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
            pointerPos = pointerState,
            inputMode = inputMode,
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

    RdpSessionContent(
        connected = viewModel.connected,
        frame = viewModel.frame,
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
        onTypeChar = { ch -> viewModel.typeUnicode(ch.code) },
        onKeyDown = { scancode -> viewModel.sendKey(scancode, true) },
        onKeyUp = { scancode -> viewModel.sendKey(scancode, false) },
        onDisconnect = { viewModel.disconnect() },
        onFullscreenChanged = onFullscreenChanged,
    )
}

internal enum class ProgressState { Connecting, WaitingForFrame, Error }

@Composable
private fun DesktopPlaceholder(
    protocol: String,
    error: String?,
    progressState: ProgressState = ProgressState.Error,
    onDisconnect: (() -> Unit)? = null,
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
                    "Connecting…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Handshake in progress. If this hangs, enable verbose connection logging in Settings and retry to see the underlying error.",
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
                    "Connected — waiting for first frame…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProgressState.Error -> {
                Text(
                    "Connection failed",
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
                            text = hint.title,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = hint.body,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (hint.linkUrl != null && hint.linkLabel != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { uriHandler.openUri(hint.linkUrl) }) {
                                Text(hint.linkLabel)
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
        if (onDisconnect != null && (progressState == ProgressState.Error || progressState == ProgressState.Connecting)) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDisconnect) {
                Text(if (progressState == ProgressState.Error) "Close" else "Cancel")
            }
        }
    }
}

// --- RDP Desktop Viewer ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RdpViewer(
    frame: Bitmap,
    fullscreen: Boolean,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onTap: (Int, Int) -> Unit,
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
    pointerPos: Pair<Int, Int> = 0 to 0,
    inputMode: String = "DIRECT",
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(frame) { frame.asImageBitmap() }

    // Zoom & pan state
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }

    // Touchpad-mode virtual cursor — composable scope so it survives lifts.
    var virtualCursor by remember(inputMode) { mutableStateOf(pointerPos) }

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

    // Sentinel for the hidden text field
    val sentinel = " "
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length)))
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // RDP canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { viewSize = it }
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
                                    // 3+ fingers = local viewport pan/zoom;
                                    // 2 fingers = remote scroll-wheel only.
                                    // See VncScreen for the full rationale.
                                    if (totalFingers >= 3) {
                                        if (prevSpan > 0f && span > 0f) {
                                            val requestedScale = span / prevSpan
                                            val newZoom = (zoom * requestedScale).coerceIn(0.5f, 5f)
                                            // graphicsLayer's default TransformOrigin
                                            // is the view center, so the scale pivot
                                            // is (cx, cy). Use the *actual* applied
                                            // scale (clamp-aware) so we don't over-pan
                                            // when the zoom hits a limit.
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
                                    } else {
                                        cumulativeScrollY += centroid.y - prevCentroid.y
                                        if (abs(cumulativeScrollY) > 40f) {
                                            if (cumulativeScrollY < 0) onScrollUp() else onScrollDown()
                                            cumulativeScrollY = 0f
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
                drawRemoteFrame(imageBitmap, frame.width, frame.height)
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
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    // Map Compose Key to Windows scancode for RDP
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
                },
        )

        // RDP key toolbar — hidden in fullscreen, and also hidden when the
        // soft keyboard isn't visible (keyboard-extension rows shouldn't eat
        // screen space when there's no keyboard to extend).
        val imeVisible = WindowInsets.isImeVisible
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
                    }
                },
            )
        }

        // Bottom toolbar (hidden in fullscreen). Solid background so a
        // zoomed framebuffer doesn't show through (matches the fix to
        // VNC's equivalent toolbar from #107).
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

                IconButton(onClick = onToggleFullscreen) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
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
                    Icon(Icons.Default.Keyboard, contentDescription = "Toggle keyboard", modifier = Modifier.size(18.dp))
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

    LaunchedEffect(isPressed) {
        if (isPressed) {
            didRepeat = false
            delay(400)
            didRepeat = true
            while (true) {
                onClick()
                delay(80)
            }
        }
    }

    FilledTonalButton(
        onClick = {},
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
private fun androidKeyToScancode(key: Key): Int? = when (key) {
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
    Key.ShiftLeft, Key.ShiftRight -> SC_SHIFT_L
    Key.CtrlLeft, Key.CtrlRight -> SC_CTRL_L
    Key.AltLeft, Key.AltRight -> SC_ALT_L
    else -> null
}

// Windows scancodes (Set 1 / AT keyboard)
private const val SC_ESCAPE = 0x01
private const val SC_BACKSPACE = 0x0E
private const val SC_TAB = 0x0F
private const val SC_RETURN = 0x1C
private const val SC_CTRL_L = 0x1D
private const val SC_SHIFT_L = 0x2A
private const val SC_ALT_L = 0x38
private const val SC_DELETE = 0x53
private const val SC_INSERT = 0x52
private const val SC_HOME = 0x47
private const val SC_END = 0x4F
private const val SC_PGUP = 0x49
private const val SC_PGDN = 0x51
private const val SC_UP = 0x48
private const val SC_DOWN = 0x50
private const val SC_LEFT = 0x4B
private const val SC_RIGHT = 0x4D
private const val SC_WIN_L = 0x5B
private const val SC_F1 = 0x3B

/** A friendly diagnosis layered on top of an opaque server error string. */
internal data class RdpErrorHint(
    val title: String,
    val body: String,
    val linkLabel: String? = null,
    val linkUrl: String? = null,
)

/**
 * Map raw RDP failure strings to a human-readable diagnosis. Returns null
 * when no specific pattern matches; the caller still shows the raw error
 * underneath either way.
 */
internal fun rdpErrorHint(error: String): RdpErrorHint? = when {
    "STANDARD_RDP_SECURITY" in error -> RdpErrorHint(
        title = "Server is using legacy unencrypted RDP",
        body = "Haven only supports TLS-encrypted RDP. Your server (commonly xrdp on Linux) " +
            "negotiated the deprecated STANDARD_RDP_SECURITY protocol — usually because " +
            "the server's TLS certificate is missing or unreadable. Regenerate /etc/xrdp/cert.pem " +
            "and /etc/xrdp/key.pem on the server, then restart xrdp.",
        linkLabel = "Server-side fix instructions",
        linkUrl = "https://github.com/GlassHaven/Haven/issues/106#issuecomment-4319030771",
    )
    "AlertReceived(InternalError)" in error -> RdpErrorHint(
        title = "Server rejected the TLS session during NLA",
        body = "This used to be the symptom of a Haven bug fixed in v5.24.37 — if you're on a " +
            "newer build and still see this, the server may not speak CredSSP at all. Try unticking " +
            "Network Level Authentication on the connection profile and reconnecting.",
    )
    "STATUS_LOGON_FAILURE" in error || "0xc000006d" in error -> RdpErrorHint(
        title = "Wrong username or password",
        body = "The server completed CredSSP cleanly and rejected your credentials. Check the " +
            "username (try DOMAIN\\User if the account is domain-joined) and password.",
    )
    "MessageAltered" in error || "public-key hash" in error -> RdpErrorHint(
        title = "Server rejected the CredSSP public-key hash",
        body = "Linux gnome-remote-desktop and certain xrdp builds compute the CredSSP " +
            "pub_key_auth hash differently than Haven's ironrdp/sspi-rs — the TLS handshake " +
            "succeeds but NLA fails one layer up.\n\n" +
            "Workaround: edit this connection's profile and uncheck \"Network Level " +
            "Authentication (NLA)\". RDP will fall back to authenticating after channel setup, " +
            "which doesn't go through the mismatched hash.",
        linkLabel = "Issue tracker",
        linkUrl = "https://github.com/GlassHaven/Haven/issues/109",
    )
    "TimeSkew" in error -> RdpErrorHint(
        title = "Device clock differs from server's",
        body = "CredSSP rejects the session when the device clock is more than a few minutes " +
            "off the server's. Check Settings → Date & time → Set automatically and reconnect.",
    )
    "no shared TLS parameters" in error || "PeerIncompatible" in error -> RdpErrorHint(
        title = "Server requires TLS ciphers Haven doesn't support",
        body = "Haven uses the rustls/ring TLS stack which has narrower cipher coverage than " +
            "Microsoft's RDP client (SChannel) or OpenSSL. The most compatible server-side " +
            "setting is ECDHE-RSA + AES-GCM over TLS 1.2 or 1.3.",
    )
    else -> null
}
