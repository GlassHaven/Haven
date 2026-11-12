package sh.haven.core.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/** Trackpad sensitivity slider range (#696): 0.2 (fine) up to 3.0 (fast) in 0.1 steps. */
const val TOUCHPAD_SENSITIVITY_MIN = 0.2f
const val TOUCHPAD_SENSITIVITY_MAX = 3f

/**
 * Mouse button action bound to a desktop touch gesture (#836).
 * Stored in DataStore by [storageValue].
 */
enum class DesktopGestureAction(val storageValue: String) {
    RIGHT_CLICK("RIGHT_CLICK"),
    MIDDLE_CLICK("MIDDLE_CLICK"),
    LEFT_CLICK("LEFT_CLICK"),
    NONE("NONE");

    companion object {
        fun fromStorage(value: String?, default: DesktopGestureAction): DesktopGestureAction =
            entries.firstOrNull { it.storageValue == value } ?: default
    }
}

/**
 * Configurable touch gesture bindings shared across VNC, RDP, SPICE, GLKVM,
 * and app-window remote viewports (#836).
 */
data class DesktopMouseGestureConfig(
    val longPressAction: DesktopGestureAction = DesktopGestureAction.RIGHT_CLICK,
    val twoFingerTapAction: DesktopGestureAction = DesktopGestureAction.MIDDLE_CLICK,
    val threeFingerTapAction: DesktopGestureAction = DesktopGestureAction.NONE,
) {
    companion object {
        val DEFAULT = DesktopMouseGestureConfig()
    }
}

fun dispatchDesktopGestureAction(
    action: DesktopGestureAction,
    x: Int,
    y: Int,
    onLeftClick: (Int, Int) -> Unit,
    onMiddleClick: (Int, Int) -> Unit,
    onRightClick: (Int, Int) -> Unit,
) {
    when (action) {
        DesktopGestureAction.LEFT_CLICK -> onLeftClick(x, y)
        DesktopGestureAction.MIDDLE_CLICK -> onMiddleClick(x, y)
        DesktopGestureAction.RIGHT_CLICK -> onRightClick(x, y)
        DesktopGestureAction.NONE -> Unit
    }
}

fun resolveRemoteMultiFingerTapAction(
    isMouseDevice: Boolean,
    totalFingers: Int,
    multiFingerMovement: Float,
    touchSlopPx: Float,
    longPressFired: Boolean,
    heldButton: Int?,
    gestures: DesktopMouseGestureConfig,
): DesktopGestureAction {
    if (isMouseDevice || longPressFired || heldButton != null || multiFingerMovement >= touchSlopPx) {
        return DesktopGestureAction.NONE
    }
    return when (totalFingers) {
        2 -> gestures.twoFingerTapAction
        3 -> gestures.threeFingerTapAction
        else -> DesktopGestureAction.NONE
    }
}

data class RemoteViewportTransform(
    val zoom: Float,
    val panX: Float,
    val panY: Float,
)

/**
 * Three-state orientation cycle shared across VNC, RDP, SPICE, and GLKVM
 * session toolbars (`Landscape -> Portrait -> Auto`).
 */
enum class DesktopOrientationMode(
    val activityValue: Int,
    val icon: ImageVector,
) {
    Landscape(
        activityValue = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        icon = Icons.Default.ScreenLockLandscape,
    ),
    Portrait(
        activityValue = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        icon = Icons.Default.ScreenLockPortrait,
    ),
    Auto(
        activityValue = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        icon = Icons.Default.ScreenRotation,
    );

    fun next(): DesktopOrientationMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromActivityValue(value: Int): DesktopOrientationMode =
            entries.firstOrNull { it.activityValue == value } ?: Landscape
    }
}

/**
 * Diff one IME commit against the text the field held before it, returning
 * `(addedCharactersOrNull, backspaceCount)`.
 *
 * Trimming the common prefix and common suffix handles append, backspace,
 * CJK pinyin-to-ideograph commit replacement, autocorrect word replacement,
 * and mid-string edits without resetting the field to a sentinel (bug #5).
 */
fun diffRemoteImeCommit(oldText: String, newText: String): Pair<String?, Int> {
    if (oldText == newText) return null to 0
    var prefix = 0
    while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) {
        prefix++
    }
    var suffix = 0
    while (
        suffix < oldText.length - prefix &&
        suffix < newText.length - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }
    val added = newText.substring(prefix, newText.length - suffix)
    val deleted = oldText.length - prefix - suffix
    return added.ifEmpty { null } to deleted
}

/**
 * Map a screen coordinate to remote framebuffer coordinates, reversing the
 * Canvas `graphicsLayer` zoom/pan transform and accounting for contain or
 * `coverFill` scaling.
 */
fun screenToRemoteDesktop(
    offset: Offset,
    viewSize: IntSize,
    fbWidth: Int,
    fbHeight: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
    coverFill: Boolean = false,
): Pair<Int, Int> {
    if (viewSize.width <= 0 || viewSize.height <= 0 || fbWidth <= 0 || fbHeight <= 0 || zoom <= 0f) {
        return 0 to 0
    }
    val viewW = viewSize.width.toFloat()
    val viewH = viewSize.height.toFloat()

    val cx = viewW / 2f
    val cy = viewH / 2f
    val localX = (offset.x - cx - panX) / zoom + cx
    val localY = (offset.y - cy - panY) / zoom + cy

    val fitScale = if (coverFill) {
        maxOf(viewW / fbWidth, viewH / fbHeight)
    } else {
        minOf(viewW / fbWidth, viewH / fbHeight)
    }
    val dstW = fbWidth * fitScale
    val dstH = fbHeight * fitScale
    val offsetX = (viewW - dstW) / 2f
    val offsetY = (viewH - dstH) / 2f

    val rx = ((localX - offsetX) / fitScale).toInt().coerceIn(0, fbWidth - 1)
    val ry = ((localY - offsetY) / fitScale).toInt().coerceIn(0, fbHeight - 1)
    return rx to ry
}

fun applyRemoteViewportPinch(
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
    coverFill: Boolean = false,
    minZoom: Float = 1f,
    maxZoom: Float = 5f,
): RemoteViewportTransform {
    if (prevSpan <= 0f || span <= 0f || fbWidth <= 0 || fbHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
        return RemoteViewportTransform(zoom, panX, panY)
    }
    val effectiveMin = if (coverFill) maxOf(1f, minZoom) else minZoom
    val newZoom = (zoom * (span / prevSpan)).coerceIn(effectiveMin, maxZoom)
    val actualScale = if (zoom > 0f) newZoom / zoom else 1f
    val cx = viewWidth / 2f
    val cy = viewHeight / 2f
    val rawPanX = (centroid.x - cx) * (1f - actualScale) + panX * actualScale
    val rawPanY = (centroid.y - cy) * (1f - actualScale) + panY * actualScale
    val fit = if (coverFill) {
        maxOf(viewWidth / fbWidth, viewHeight / fbHeight)
    } else {
        minOf(viewWidth / fbWidth, viewHeight / fbHeight)
    }
    val clampedPanX = clampPanToCover(rawPanX, fbWidth * fit, viewWidth, newZoom)
    val clampedPanY = clampPanToCover(rawPanY, fbHeight * fit, viewHeight, newZoom)
    return RemoteViewportTransform(newZoom, clampedPanX, clampedPanY)
}

fun applyRemoteViewportPan(
    zoom: Float,
    panX: Float,
    panY: Float,
    dx: Float,
    dy: Float,
    viewWidth: Float,
    viewHeight: Float,
    fbWidth: Int,
    fbHeight: Int,
    coverFill: Boolean = false,
): RemoteViewportTransform {
    if (zoom <= 1f || fbWidth <= 0 || fbHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
        return RemoteViewportTransform(zoom, panX, panY)
    }
    val fit = if (coverFill) {
        maxOf(viewWidth / fbWidth, viewHeight / fbHeight)
    } else {
        minOf(viewWidth / fbWidth, viewHeight / fbHeight)
    }
    val clampedPanX = clampPanToCover(panX + dx, fbWidth * fit, viewWidth, zoom)
    val clampedPanY = clampPanToCover(panY + dy, fbHeight * fit, viewHeight, zoom)
    return RemoteViewportTransform(zoom, clampedPanX, clampedPanY)
}

/**
 * Mario-camera viewport pan: keep the cursor inside an inner dead-zone of the
 * view when zoomed in.
 */
fun remoteCameraFollow(
    cursorFbX: Int,
    cursorFbY: Int,
    fbWidth: Int,
    fbHeight: Int,
    viewW: Float,
    viewH: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    coverFill: Boolean = false,
    deadZoneFraction: Float = 0.30f,
): Pair<Float, Float> {
    if (zoom <= 1f) return panX to panY
    if (viewW <= 0f || viewH <= 0f || fbWidth <= 0 || fbHeight <= 0) return panX to panY

    val cx = viewW / 2f
    val cy = viewH / 2f
    val fitScale = if (coverFill) {
        maxOf(viewW / fbWidth, viewH / fbHeight)
    } else {
        minOf(viewW / fbWidth, viewH / fbHeight)
    }
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

fun remoteCameraFollowClamped(
    cursorFbX: Int,
    cursorFbY: Int,
    fbWidth: Int,
    fbHeight: Int,
    viewW: Float,
    viewH: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    coverFill: Boolean = false,
    deadZoneFraction: Float = 0.30f,
): Pair<Float, Float> {
    if (zoom <= 1f || viewW <= 0f || viewH <= 0f || fbWidth <= 0 || fbHeight <= 0) {
        return panX to panY
    }
    val (newPanX, newPanY) = remoteCameraFollow(
        cursorFbX = cursorFbX,
        cursorFbY = cursorFbY,
        fbWidth = fbWidth,
        fbHeight = fbHeight,
        viewW = viewW,
        viewH = viewH,
        zoom = zoom,
        panX = panX,
        panY = panY,
        coverFill = coverFill,
        deadZoneFraction = deadZoneFraction,
    )
    val fit = if (coverFill) {
        maxOf(viewW / fbWidth, viewH / fbHeight)
    } else {
        minOf(viewW / fbWidth, viewH / fbHeight)
    }
    val clampedX = clampPanToCover(newPanX, fbWidth * fit, viewW, zoom)
    val clampedY = clampPanToCover(newPanY, fbHeight * fit, viewH, zoom)
    return clampedX to clampedY
}

/**
 * True when a down/move stream originates from a physical mouse or a
 * pointer-mode hardware touchpad (`SOURCE_MOUSE`) rather than the touchscreen (#660, #622).
 */
fun isRemoteHardwareMouseGesture(
    pointerType: PointerType,
    buttons: PointerButtons,
    motionSource: Int,
): Boolean =
    pointerType == PointerType.Mouse ||
        buttons.areAnyPressed ||
        (motionSource and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE

/**
 * Map Compose [PointerButtons] and Android [MotionEvent.getButtonState] to X11
 * mouse button numbers (1 = left, 2 = middle, 3 = right, 0 = buttonless trackpad).
 */
fun resolveRemoteHardwareMouseButton(
    buttons: PointerButtons,
    motionButtonState: Int,
): Int = when {
    buttons.isSecondaryPressed ||
        (motionButtonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0 -> 3
    buttons.isTertiaryPressed ||
        (motionButtonState and MotionEvent.BUTTON_TERTIARY) != 0 -> 2
    buttons.isPrimaryPressed ||
        (motionButtonState and (MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0 -> 1
    else -> 0
}

/**
 * Accumulates Compose `scrollDelta.y` values into whole wheel notches (#660).
 * Negative `scrollDelta.y` is wheel-up (+1); positive is wheel-down (-1).
 */
class RemoteWheelNotchAccumulator {
    private var accumulated = 0f

    fun feed(scrollDeltaY: Float): Int {
        if (scrollDeltaY.isNaN() || scrollDeltaY == 0f) return 0
        accumulated += scrollDeltaY
        var notches = 0
        while (abs(accumulated) >= 1f) {
            if (accumulated <= -1f) {
                notches += 1
                accumulated += 1f
            } else {
                notches -= 1
                accumulated -= 1f
            }
        }
        return notches
    }
}

fun remainingRemoteTouchLongPressTimeoutMs(
    isMouseDevice: Boolean,
    totalFingers: Int,
    longPressFired: Boolean,
    dragging: Boolean,
    totalMovement: Float,
    touchSlopPx: Float,
    isFollowUpTouch: Boolean,
    heldButton: Int? = null,
    longPressAction: DesktopGestureAction = DesktopGestureAction.RIGHT_CLICK,
    longPressMs: Long,
    elapsedMs: Long,
): Long = if (
    shouldFireRemoteTouchLongPressRightClick(
        isMouseDevice = isMouseDevice,
        totalFingers = totalFingers,
        longPressFired = longPressFired,
        dragging = dragging,
        totalMovement = totalMovement,
        touchSlopPx = touchSlopPx,
        isFollowUpTouch = isFollowUpTouch,
        heldButton = heldButton,
        longPressAction = longPressAction,
    )
) {
    (longPressMs - elapsedMs).coerceAtLeast(1L)
} else {
    Long.MAX_VALUE
}

fun shouldFireRemoteTouchLongPressRightClick(
    isMouseDevice: Boolean,
    totalFingers: Int,
    longPressFired: Boolean,
    dragging: Boolean,
    totalMovement: Float,
    touchSlopPx: Float,
    isFollowUpTouch: Boolean,
    heldButton: Int? = null,
    longPressAction: DesktopGestureAction = DesktopGestureAction.RIGHT_CLICK,
): Boolean =
    longPressAction != DesktopGestureAction.NONE &&
        !isMouseDevice &&
        totalFingers == 1 &&
        !longPressFired &&
        !dragging &&
        totalMovement < touchSlopPx &&
        !isFollowUpTouch &&
        heldButton == null

inline fun resolveRemoteTouchLongPressTarget(
    effectiveInputMode: String,
    virtualCursor: Pair<Int, Int>,
    directPos: () -> Pair<Int, Int>,
): Pair<Int, Int> = if (effectiveInputMode == "TOUCHPAD") virtualCursor else directPos()

fun shouldFireRemotePostGestureTap(
    totalFingers: Int,
    totalMovement: Float,
    touchSlopPx: Float,
    longPressFired: Boolean,
    heldButton: Int? = null,
    isMouseDevice: Boolean = false,
    longPressDisabled: Boolean = false,
    gestureDurationMs: Long = 0L,
    longPressMs: Long = 400L,
): Boolean =
    totalFingers == 1 &&
        totalMovement < touchSlopPx &&
        !longPressFired &&
        heldButton == null &&
        !(longPressDisabled && !isMouseDevice && gestureDurationMs >= longPressMs)

/**
 * Mutable viewport zoom, pan, view size, and touchpad virtual-cursor state
 * shared by `VncViewer` and `RdpViewer`.
 */
class RemoteDesktopViewportState(
    initialZoom: Float = 1f,
    initialPanX: Float = 0f,
    initialPanY: Float = 0f,
    initialCursor: Pair<Int, Int> = 0 to 0,
    initialCursorF: Offset = Offset(initialCursor.first.toFloat(), initialCursor.second.toFloat()),
    initialHasUserMovedCursor: Boolean = false,
    initialSeededInputMode: String? = null,
) {
    var zoom by mutableFloatStateOf(initialZoom)
    var panX by mutableFloatStateOf(initialPanX)
    var panY by mutableFloatStateOf(initialPanY)
    var viewSize by mutableStateOf(IntSize.Zero)
    var virtualCursor by mutableStateOf(initialCursor)
    var virtualCursorF by mutableStateOf(initialCursorF)
    internal var lastSeededInputMode: String? = initialSeededInputMode
    internal var hasUserMovedCursor: Boolean = initialHasUserMovedCursor

    val isTransformed: Boolean
        get() = zoom != 1f || panX != 0f || panY != 0f

    fun resetTransform() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    fun applyTransform(transform: RemoteViewportTransform) {
        zoom = transform.zoom
        panX = transform.panX
        panY = transform.panY
    }

    fun setVirtualCursorPosition(pos: Pair<Int, Int>, fromUser: Boolean = true) {
        virtualCursor = pos
        virtualCursorF = Offset(pos.first.toFloat(), pos.second.toFloat())
        hasUserMovedCursor = fromUser
    }

    /**
     * Keep [virtualCursor] aligned with the server's reported [pointerPos]
     * whenever [inputMode] changes OR before the user has moved the cursor in
     * the current mode (#608). SPICE delivers `CURSOR_INIT` on a separate TCP
     * channel that can arrive after the first display frame mounted the
     * viewport with `pointerPos == (0, 0)`; without adopting that late seed,
     * `SPICE_MOUSE_MODE_SERVER` relative deltas start from `(0, 0)` while the
     * guest pointer sits at its initial offset, clamping the reachable area.
     */
    fun syncVirtualCursorSeed(inputMode: String, pointerPos: Pair<Int, Int>) {
        if (lastSeededInputMode != inputMode) {
            lastSeededInputMode = inputMode
            setVirtualCursorPosition(pointerPos, fromUser = false)
        } else if (!hasUserMovedCursor && pointerPos != virtualCursor) {
            setVirtualCursorPosition(pointerPos, fromUser = false)
        }
    }

    fun stepVirtualCursor(deltaX: Float, deltaY: Float, fbWidth: Int, fbHeight: Int): Pair<Int, Int> {
        val maxX = (fbWidth - 1).coerceAtLeast(0)
        val maxY = (fbHeight - 1).coerceAtLeast(0)
        val fx = (virtualCursorF.x + deltaX).coerceIn(0f, maxX.toFloat())
        val fy = (virtualCursorF.y + deltaY).coerceIn(0f, maxY.toFloat())
        virtualCursorF = Offset(fx, fy)
        val pos = fx.toInt().coerceIn(0, maxX) to fy.toInt().coerceIn(0, maxY)
        virtualCursor = pos
        hasUserMovedCursor = true
        return pos
    }

    companion object {
        val Saver: Saver<RemoteDesktopViewportState, ArrayList<Any?>> = Saver(
            save = {
                arrayListOf(
                    it.zoom,
                    it.panX,
                    it.panY,
                    it.virtualCursorF.x,
                    it.virtualCursorF.y,
                    it.hasUserMovedCursor,
                    it.lastSeededInputMode,
                )
            },
            restore = { saved ->
                val zoom = (saved.getOrNull(0) as? Number)?.toFloat() ?: 1f
                val panX = (saved.getOrNull(1) as? Number)?.toFloat() ?: 0f
                val panY = (saved.getOrNull(2) as? Number)?.toFloat() ?: 0f
                val cursorFx = (saved.getOrNull(3) as? Number)?.toFloat() ?: 0f
                val cursorFy = (saved.getOrNull(4) as? Number)?.toFloat() ?: 0f
                val userMoved = saved.getOrNull(5) as? Boolean ?: false
                val seededMode = saved.getOrNull(6) as? String
                RemoteDesktopViewportState(
                    initialZoom = zoom,
                    initialPanX = panX,
                    initialPanY = panY,
                    initialCursor = cursorFx.toInt() to cursorFy.toInt(),
                    initialCursorF = Offset(cursorFx, cursorFy),
                    initialHasUserMovedCursor = userMoved,
                    initialSeededInputMode = seededMode,
                )
            },
        )
    }
}

@Composable
fun rememberRemoteDesktopViewportState(
    inputMode: String,
    pointerPos: Pair<Int, Int>,
    saveable: Boolean = false,
): RemoteDesktopViewportState {
    val state = if (saveable) {
        rememberSaveable(saver = RemoteDesktopViewportState.Saver) {
            RemoteDesktopViewportState(initialCursor = pointerPos)
        }
    } else {
        remember { RemoteDesktopViewportState(initialCursor = pointerPos) }
    }
    state.syncVirtualCursorSeed(inputMode = inputMode, pointerPos = pointerPos)
    return state
}

/**
 * Snap viewport pan when zoomed in `TOUCHPAD` mode so the tracked cursor stays
 * inside the inner dead-zone without exposing black letterbox edges (#738).
 */
@Composable
fun RemoteDesktopCameraFollowEffect(
    state: RemoteDesktopViewportState,
    cursorPos: Pair<Int, Int>,
    enabled: Boolean,
    fbWidth: Int,
    fbHeight: Int,
    coverFill: Boolean = false,
) {
    LaunchedEffect(cursorPos, enabled, state.zoom, state.viewSize, fbWidth, fbHeight, coverFill) {
        if (enabled && state.zoom > 1f && state.viewSize.width > 0 && state.viewSize.height > 0) {
            val (clampedX, clampedY) = remoteCameraFollowClamped(
                cursorFbX = cursorPos.first,
                cursorFbY = cursorPos.second,
                fbWidth = fbWidth,
                fbHeight = fbHeight,
                viewW = state.viewSize.width.toFloat(),
                viewH = state.viewSize.height.toFloat(),
                zoom = state.zoom,
                panX = state.panX,
                panY = state.panY,
                coverFill = coverFill,
            )
            if (clampedX != state.panX) state.panX = clampedX
            if (clampedY != state.panY) state.panY = clampedY
        }
    }
}

/**
 * Unified remote-desktop canvas, pointer/gesture/wheel/mouse state machine, and
 * cursor overlay renderer shared across VNC, RDP, SPICE, and GLKVM.
 */
@Composable
fun RemoteDesktopViewport(
    frame: Bitmap,
    state: RemoteDesktopViewportState,
    inputMode: String,
    touchpadSensitivity: Float = 1f,
    mouseGestures: DesktopMouseGestureConfig = DesktopMouseGestureConfig.DEFAULT,
    twoFingerScroll: Boolean = false,
    scrollWhenUnzoomed: Boolean = false,
    coverFill: Boolean = false,
    minZoom: Float = 1f,
    maxZoom: Float = 5f,
    heldButton: Int? = null,
    frameSeq: State<Long>? = null,
    isProjectingExternal: Boolean = false,
    cursor: CursorOverlay? = null,
    pointerPos: Pair<Int, Int> = 0 to 0,
    showCursor: Boolean = true,
    showFallbackCursor: Boolean = false,
    useVirtualCursorPositionForOverlay: Boolean = false,
    hardwareKeyFocus: FocusRequester,
    onCanvasFocusChanged: (Boolean) -> Unit,
    shouldReclaimHardwareKeyFocusOnTap: () -> Boolean,
    onPointerActivated: () -> Unit = {},
    onTap: (Int, Int) -> Unit,
    onMiddleClick: (Int, Int) -> Unit = { _, _ -> },
    onRightClick: (Int, Int) -> Unit,
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onPressButton: (Int) -> Unit = {},
    onReleaseButton: (Int) -> Unit = {},
    onThreeFingerPinchRatio: ((Float) -> Unit)? = null,
    gestureKey: Any? = Unit,
    customTouchGestureHandler: (suspend AwaitPointerEventScope.(firstDown: PointerInputChange, viewSize: IntSize) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(frame) { frame.asImageBitmap() }
    val cursorImage = remember(cursor?.bitmap) { cursor?.bitmap?.asImageBitmap() }
    var lastTapUpMs by remember { mutableStateOf(0L) }

    val currentCoverFill by rememberUpdatedState(coverFill)
    val currentMinZoom by rememberUpdatedState(minZoom)
    val currentMaxZoom by rememberUpdatedState(maxZoom)
    val currentMouseGestures by rememberUpdatedState(mouseGestures)
    val currentTwoFingerScroll by rememberUpdatedState(twoFingerScroll)
    val currentScrollWhenUnzoomed by rememberUpdatedState(scrollWhenUnzoomed)
    val currentHeldButton by rememberUpdatedState(heldButton)
    val currentShouldReclaimFocus by rememberUpdatedState(shouldReclaimHardwareKeyFocusOnTap)
    val currentOnPointerActivated by rememberUpdatedState(onPointerActivated)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnMiddleClick by rememberUpdatedState(onMiddleClick)
    val currentOnRightClick by rememberUpdatedState(onRightClick)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnScrollUp by rememberUpdatedState(onScrollUp)
    val currentOnScrollDown by rememberUpdatedState(onScrollDown)
    val currentOnPressButton by rememberUpdatedState(onPressButton)
    val currentOnReleaseButton by rememberUpdatedState(onReleaseButton)
    val currentThreeFingerPinch by rememberUpdatedState(onThreeFingerPinchRatio)
    val currentCustomTouchHandler by rememberUpdatedState(customTouchGestureHandler)

    Box(
        modifier = modifier
            .background(Color.Black)
            .focusRequester(hardwareKeyFocus)
            .focusable()
            .onFocusChanged { onCanvasFocusChanged(it.isFocused) }
            .onSizeChanged { state.viewSize = it }
            // #660/#622: unpressed hardware mouse / trackpad hover motion and
            // mouse wheel scroll carry no pressed pointer, so awaitFirstDown()
            // never wakes for them.
            .pointerInput(frame.width, frame.height, state.viewSize, inputMode) {
                val wheelNotches = RemoteWheelNotchAccumulator()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (event.type == PointerEventType.Scroll) {
                            val pos = screenToRemoteDesktop(
                                offset = change.position,
                                viewSize = state.viewSize,
                                fbWidth = frame.width,
                                fbHeight = frame.height,
                                zoom = state.zoom,
                                panX = state.panX,
                                panY = state.panY,
                                coverFill = currentCoverFill,
                            )
                            currentOnPointerActivated()
                            state.setVirtualCursorPosition(pos)
                            currentOnDrag(pos.first, pos.second)
                            val steps = wheelNotches.feed(change.scrollDelta.y)
                            change.consume()
                            if (steps > 0) {
                                repeat(steps) { currentOnScrollUp() }
                            } else if (steps < 0) {
                                repeat(-steps) { currentOnScrollDown() }
                            }
                        } else if (event.type == PointerEventType.Move && event.changes.none { it.pressed }) {
                            val pos = screenToRemoteDesktop(
                                offset = change.position,
                                viewSize = state.viewSize,
                                fbWidth = frame.width,
                                fbHeight = frame.height,
                                zoom = state.zoom,
                                panX = state.panX,
                                panY = state.panY,
                                coverFill = currentCoverFill,
                            )
                            currentOnPointerActivated()
                            state.setVirtualCursorPosition(pos)
                            currentOnDrag(pos.first, pos.second)
                            change.consume()
                        }
                    }
                }
            }
            .pointerInput(frame.width, frame.height, state.viewSize, inputMode, touchpadSensitivity, gestureKey) {
                val touchSlopPx = viewConfiguration.touchSlop
                awaitEachGesture {
                    val firstDown = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val downMotionEvent = currentEvent.motionEvent
                    val isMouseDevice = isRemoteHardwareMouseGesture(
                        pointerType = firstDown.type,
                        buttons = currentEvent.buttons,
                        motionSource = downMotionEvent?.source ?: 0,
                    )
                    var mouseButton = if (isMouseDevice) {
                        resolveRemoteHardwareMouseButton(
                            buttons = currentEvent.buttons,
                            motionButtonState = downMotionEvent?.buttonState ?: 0,
                        )
                    } else {
                        0
                    }
                    firstDown.consume()
                    currentOnPointerActivated()
                    if (currentShouldReclaimFocus()) {
                        hardwareKeyFocus.requestFocus()
                    }
                    val customHandler = currentCustomTouchHandler
                    if (!isMouseDevice && customHandler != null && customHandler(firstDown, state.viewSize)) {
                        return@awaitEachGesture
                    }

                    var totalFingers = 1
                    var prevCentroid = firstDown.position
                    var prevSpan = 0f
                    var prevCount = 0
                    var gestureStarted = false
                    var cumulativeScrollY = 0f
                    var totalMovement = 0f
                    var twoFingerMovement = 0f
                    var lastSinglePos = firstDown.position
                    var dragging = false
                    var longPressFired = false
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    val downUptimeMs = firstDown.uptimeMillis
                    val isFollowUpTouch = !isMouseDevice && inputMode == "TOUCHPAD" &&
                        (downUptimeMs - lastTapUpMs) <= 300L
                    var dragButtonPressed = false

                    var event: PointerEvent
                    do {
                        val activeLongPressAction = currentMouseGestures.longPressAction
                        val remaining = remainingRemoteTouchLongPressTimeoutMs(
                            isMouseDevice = isMouseDevice,
                            totalFingers = totalFingers,
                            longPressFired = longPressFired,
                            dragging = dragging,
                            totalMovement = totalMovement,
                            touchSlopPx = touchSlopPx,
                            isFollowUpTouch = isFollowUpTouch,
                            heldButton = currentHeldButton,
                            longPressAction = activeLongPressAction,
                            longPressMs = longPressMs,
                            elapsedMs = SystemClock.uptimeMillis() - downUptimeMs,
                        )
                        val timedEvent = if (remaining == Long.MAX_VALUE) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        } else {
                            withTimeoutOrNull(remaining) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                        }
                        if (timedEvent == null) {
                            if (
                                shouldFireRemoteTouchLongPressRightClick(
                                    isMouseDevice = isMouseDevice,
                                    totalFingers = totalFingers,
                                    longPressFired = longPressFired,
                                    dragging = dragging,
                                    totalMovement = totalMovement,
                                    touchSlopPx = touchSlopPx,
                                    isFollowUpTouch = isFollowUpTouch,
                                    heldButton = currentHeldButton,
                                    longPressAction = activeLongPressAction,
                                )
                            ) {
                                val (vx, vy) = resolveRemoteTouchLongPressTarget(
                                    effectiveInputMode = inputMode,
                                    virtualCursor = state.virtualCursor,
                                    directPos = {
                                        val pos = screenToRemoteDesktop(
                                            offset = lastSinglePos,
                                            viewSize = state.viewSize,
                                            fbWidth = frame.width,
                                            fbHeight = frame.height,
                                            zoom = state.zoom,
                                            panX = state.panX,
                                            panY = state.panY,
                                            coverFill = currentCoverFill,
                                        )
                                        state.setVirtualCursorPosition(pos)
                                        pos
                                    },
                                )
                                dispatchDesktopGestureAction(
                                    action = activeLongPressAction,
                                    x = vx,
                                    y = vy,
                                    onLeftClick = currentOnTap,
                                    onMiddleClick = currentOnMiddleClick,
                                    onRightClick = currentOnRightClick,
                                )
                                longPressFired = true
                            }
                            event = awaitPointerEvent(PointerEventPass.Initial)
                        } else {
                            event = timedEvent
                        }
                        if (isMouseDevice && mouseButton == 0) {
                            mouseButton = resolveRemoteHardwareMouseButton(
                                buttons = event.buttons,
                                motionButtonState = event.motionEvent?.buttonState ?: 0,
                            )
                        }

                        val pointers = event.changes.filter { it.pressed }
                        val count = pointers.size

                        if (count >= 2) {
                            if (dragging) {
                                if (isMouseDevice && mouseButton in 2..3) {
                                    currentOnReleaseButton(mouseButton)
                                } else {
                                    currentOnDragEnd()
                                }
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

                            if (gestureStarted && count == prevCount) {
                                val dx = centroid.x - prevCentroid.x
                                val dy = centroid.y - prevCentroid.y
                                val spanDelta = if (prevSpan > 0f) abs(span - prevSpan) else 0f
                                twoFingerMovement += abs(dx) + abs(dy) + spanDelta
                                val threeFingerCallback = currentThreeFingerPinch
                                if (threeFingerCallback != null && totalFingers >= 3) {
                                    if (prevSpan > 0f && span > 0f) {
                                        threeFingerCallback(span / prevSpan)
                                    }
                                } else {
                                    val pinching = spanDelta > 2f && spanDelta >= abs(dx) + abs(dy)
                                    if (pinching && prevSpan > 0f && span > 0f) {
                                        state.applyTransform(
                                            applyRemoteViewportPinch(
                                                zoom = state.zoom,
                                                panX = state.panX,
                                                panY = state.panY,
                                                prevSpan = prevSpan,
                                                span = span,
                                                centroid = centroid,
                                                viewWidth = state.viewSize.width.toFloat(),
                                                viewHeight = state.viewSize.height.toFloat(),
                                                fbWidth = frame.width,
                                                fbHeight = frame.height,
                                                coverFill = currentCoverFill,
                                                minZoom = currentMinZoom,
                                                maxZoom = currentMaxZoom,
                                            ),
                                        )
                                    } else if (currentTwoFingerScroll || (currentScrollWhenUnzoomed && state.zoom <= 1.01f)) {
                                        cumulativeScrollY += dy
                                        if (abs(cumulativeScrollY) > 40f) {
                                            if (cumulativeScrollY < 0f) currentOnScrollDown() else currentOnScrollUp()
                                            cumulativeScrollY = 0f
                                        }
                                    } else if (state.zoom > 1f) {
                                        state.applyTransform(
                                            applyRemoteViewportPan(
                                                zoom = state.zoom,
                                                panX = state.panX,
                                                panY = state.panY,
                                                dx = dx,
                                                dy = dy,
                                                viewWidth = state.viewSize.width.toFloat(),
                                                viewHeight = state.viewSize.height.toFloat(),
                                                fbWidth = frame.width,
                                                fbHeight = frame.height,
                                                coverFill = currentCoverFill,
                                            ),
                                        )
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
                            val activeHeld = currentHeldButton
                            if (isMouseDevice) {
                                val pos = screenToRemoteDesktop(
                                    offset = change.position,
                                    viewSize = state.viewSize,
                                    fbWidth = frame.width,
                                    fbHeight = frame.height,
                                    zoom = state.zoom,
                                    panX = state.panX,
                                    panY = state.panY,
                                    coverFill = currentCoverFill,
                                )
                                state.setVirtualCursorPosition(pos)
                                when {
                                    activeHeld != null -> currentOnDrag(pos.first, pos.second)
                                    mouseButton == 0 -> {
                                        if (totalMovement >= touchSlopPx) {
                                            currentOnDrag(pos.first, pos.second)
                                        }
                                    }
                                    !dragging && totalMovement >= touchSlopPx -> {
                                        if (mouseButton == 1) {
                                            currentOnDragStart(pos.first, pos.second)
                                        } else {
                                            currentOnDrag(pos.first, pos.second)
                                            currentOnPressButton(mouseButton)
                                        }
                                        dragging = true
                                    }
                                    dragging -> currentOnDrag(pos.first, pos.second)
                                }
                            } else if (inputMode == "TOUCHPAD") {
                                if (!longPressFired) {
                                    val scale = if (state.zoom > 0f) state.zoom else 1f
                                    val (nx, ny) = state.stepVirtualCursor(
                                        deltaX = deltaScreen.x / scale * touchpadSensitivity,
                                        deltaY = deltaScreen.y / scale * touchpadSensitivity,
                                        fbWidth = frame.width,
                                        fbHeight = frame.height,
                                    )
                                    if (totalMovement >= touchSlopPx) {
                                        when {
                                            activeHeld != null -> currentOnDrag(nx, ny)
                                            isFollowUpTouch && !dragButtonPressed -> {
                                                currentOnDragStart(nx, ny)
                                                dragButtonPressed = true
                                            }
                                            else -> currentOnDrag(nx, ny)
                                        }
                                    }
                                }
                            } else {
                                val pos = screenToRemoteDesktop(
                                    offset = change.position,
                                    viewSize = state.viewSize,
                                    fbWidth = frame.width,
                                    fbHeight = frame.height,
                                    zoom = state.zoom,
                                    panX = state.panX,
                                    panY = state.panY,
                                    coverFill = currentCoverFill,
                                )
                                when {
                                    activeHeld != null -> currentOnDrag(pos.first, pos.second)
                                    !longPressFired && !dragging && totalMovement >= touchSlopPx -> {
                                        currentOnDragStart(pos.first, pos.second)
                                        dragging = true
                                    }
                                    dragging -> currentOnDrag(pos.first, pos.second)
                                }
                            }
                            change.consume()
                        } else {
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (dragging || dragButtonPressed) {
                        if (isMouseDevice && mouseButton in 2..3) {
                            currentOnReleaseButton(mouseButton)
                        } else {
                            currentOnDragEnd()
                        }
                    }

                    val activeHeld = currentHeldButton
                    val activeGestures = currentMouseGestures
                    val upUptimeMs = event.changes.maxOfOrNull { it.uptimeMillis } ?: SystemClock.uptimeMillis()
                    val gestureDurationMs = (upUptimeMs - downUptimeMs).coerceAtLeast(0L)
                    if (
                        shouldFireRemotePostGestureTap(
                            totalFingers = totalFingers,
                            totalMovement = totalMovement,
                            touchSlopPx = touchSlopPx,
                            longPressFired = longPressFired,
                            heldButton = activeHeld,
                            isMouseDevice = isMouseDevice,
                            longPressDisabled = activeGestures.longPressAction == DesktopGestureAction.NONE,
                            gestureDurationMs = gestureDurationMs,
                            longPressMs = longPressMs,
                        )
                    ) {
                        val (vx, vy) = if (!isMouseDevice && inputMode == "TOUCHPAD") {
                            state.virtualCursor
                        } else {
                            val pos = screenToRemoteDesktop(
                                offset = lastSinglePos,
                                viewSize = state.viewSize,
                                fbWidth = frame.width,
                                fbHeight = frame.height,
                                zoom = state.zoom,
                                panX = state.panX,
                                panY = state.panY,
                                coverFill = currentCoverFill,
                            )
                            state.setVirtualCursorPosition(pos)
                            pos
                        }
                        when {
                            isMouseDevice && mouseButton == 3 -> currentOnRightClick(vx, vy)
                            isMouseDevice && mouseButton == 2 -> currentOnMiddleClick(vx, vy)
                            else -> currentOnTap(vx, vy)
                        }
                        lastTapUpMs = if (!isMouseDevice && inputMode == "TOUCHPAD" && !isFollowUpTouch) {
                            SystemClock.uptimeMillis()
                        } else {
                            0L
                        }
                    } else {
                        val multiTapAction = resolveRemoteMultiFingerTapAction(
                            isMouseDevice = isMouseDevice,
                            totalFingers = totalFingers,
                            multiFingerMovement = twoFingerMovement,
                            touchSlopPx = touchSlopPx,
                            longPressFired = longPressFired,
                            heldButton = activeHeld,
                            gestures = activeGestures,
                        )
                        if (multiTapAction != DesktopGestureAction.NONE) {
                            val (mx, my) = if (inputMode == "TOUCHPAD") {
                                state.virtualCursor
                            } else {
                                val pos = screenToRemoteDesktop(
                                    offset = prevCentroid,
                                    viewSize = state.viewSize,
                                    fbWidth = frame.width,
                                    fbHeight = frame.height,
                                    zoom = state.zoom,
                                    panX = state.panX,
                                    panY = state.panY,
                                    coverFill = currentCoverFill,
                                )
                                state.setVirtualCursorPosition(pos)
                                pos
                            }
                            dispatchDesktopGestureAction(
                                action = multiTapAction,
                                x = mx,
                                y = my,
                                onLeftClick = currentOnTap,
                                onMiddleClick = currentOnMiddleClick,
                                onRightClick = currentOnRightClick,
                            )
                        }
                        lastTapUpMs = 0L
                    }
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.zoom
                    scaleY = state.zoom
                    translationX = state.panX
                    translationY = state.panY
                    clip = true
                },
        ) {
            @Suppress("UNUSED_EXPRESSION")
            frameSeq?.value
            if (!isProjectingExternal) {
                drawRemoteDesktopFrame(imageBitmap, frame.width, frame.height, coverFill)
                if (showCursor) {
                    val (px, py) = if (useVirtualCursorPositionForOverlay) state.virtualCursor else pointerPos
                    if (cursorImage != null && cursor != null) {
                        drawRemoteDesktopCursor(
                            cursor = cursorImage,
                            cursorW = cursor.bitmap.width,
                            cursorH = cursor.bitmap.height,
                            hotspotX = cursor.hotspotX,
                            hotspotY = cursor.hotspotY,
                            pointerX = px,
                            pointerY = py,
                            fbWidth = frame.width,
                            fbHeight = frame.height,
                            coverFill = coverFill,
                        )
                    } else if (showFallbackCursor) {
                        drawRemoteDesktopFallbackCursor(px, py, frame.width, frame.height, coverFill)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRemoteDesktopFrame(
    image: ImageBitmap,
    srcWidth: Int,
    srcHeight: Int,
    coverFill: Boolean,
) {
    if (srcWidth <= 0 || srcHeight <= 0) return
    val viewW = size.width
    val viewH = size.height
    val scale = if (coverFill) maxOf(viewW / srcWidth, viewH / srcHeight)
    else minOf(viewW / srcWidth, viewH / srcHeight)
    val dstW = srcWidth * scale
    val dstH = srcHeight * scale
    val offsetX = (viewW - dstW) / 2f
    val offsetY = (viewH - dstH) / 2f

    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
        dstSize = IntSize(dstW.toInt(), dstH.toInt()),
    )
}

private fun DrawScope.drawRemoteDesktopCursor(
    cursor: ImageBitmap,
    cursorW: Int,
    cursorH: Int,
    hotspotX: Int,
    hotspotY: Int,
    pointerX: Int,
    pointerY: Int,
    fbWidth: Int,
    fbHeight: Int,
    coverFill: Boolean,
) {
    if (fbWidth <= 0 || fbHeight <= 0) return
    val viewW = size.width
    val viewH = size.height
    val scale = if (coverFill) maxOf(viewW / fbWidth, viewH / fbHeight)
    else minOf(viewW / fbWidth, viewH / fbHeight)
    val fbOffsetX = (viewW - fbWidth * scale) / 2f
    val fbOffsetY = (viewH - fbHeight * scale) / 2f

    val cx = fbOffsetX + (pointerX - hotspotX) * scale
    val cy = fbOffsetY + (pointerY - hotspotY) * scale
    val dstW = cursorW * scale
    val dstH = cursorH * scale

    drawImage(
        image = cursor,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(cursorW, cursorH),
        dstOffset = IntOffset(cx.toInt(), cy.toInt()),
        dstSize = IntSize(dstW.toInt().coerceAtLeast(1), dstH.toInt().coerceAtLeast(1)),
    )
}

private fun DrawScope.drawRemoteDesktopFallbackCursor(
    pointerX: Int,
    pointerY: Int,
    fbWidth: Int,
    fbHeight: Int,
    coverFill: Boolean,
) {
    if (fbWidth <= 0 || fbHeight <= 0) return
    val viewW = size.width
    val viewH = size.height
    val scale = if (coverFill) maxOf(viewW / fbWidth, viewH / fbHeight)
    else minOf(viewW / fbWidth, viewH / fbHeight)
    val originX = (viewW - fbWidth * scale) / 2f + pointerX * scale
    val originY = (viewH - fbHeight * scale) / 2f + pointerY * scale

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

private const val REMOTE_IME_BUFFER_MAX_CHARS = 512

/**
 * Stateful soft-keyboard controller for [RemoteDesktopImeField] (#606).
 *
 * Bridges two Android IME delivery paths into a single character/special-key
 * stream without dropping keys or doubling echoes:
 * 1. `InputConnection.commitText` / `setComposingText` (`BasicTextField.onValueChange`):
 *    diffs against [text] via [diffRemoteImeCommit] and queues committed printable
 *    characters into [committedThisTurn] (cleared on a two-hop main-looper drain,
 *    matching `termlib`'s `ImeInputView.consumeSameMessagePrintableEcho`). If the
 *    IME also fires a synthetic `KeyEvent` echo (or held-echo repeat stream, e.g.
 *    AOSP Spanish layout), only the echo is consumed.
 * 2. `InputConnection.sendKeyEvent` (`onPreviewKeyEvent`): soft keyboards send the
 *    top number bar (`0`–`9`), shifted capitals, and many symbols via `sendKeyEvent`
 *    without calling `commitText`. When [committedThisTurn] does not match, the
 *    resolved Unicode character is emitted via [onTypeChar]/[onTypeText] and
 *    appended to [text] so surrounding-text queries stay in sync without
 *    double-firing `onValueChange`.
 *
 * Also keeps [text] in lock-step on `Backspace` (`KEYCODE_DEL`), clears [text] on
 * `Enter` so separate lines never fuse into one run-on token, and ignores bare
 * soft-keyboard `ShiftLeft`/`ShiftRight` events so an unpaired `ACTION_DOWN` can
 * never leave `Shift` stuck down on the remote host.
 */
internal class RemoteImeController(
    private val scheduleTwoHopDrain: ((() -> Unit) -> Unit)? = null,
) {
    var text: String = ""
        private set

    private val committedThisTurn = ArrayDeque<Char>()
    private var echoDrainScheduled = false
    private var suppressedEchoKeyCode: Int = -1
    private var consumedPrintableKeyCode: Int = -1

    fun onValueChanged(
        newText: String,
        hasActiveModifier: Boolean,
        onBackspace: () -> Unit,
        onTypeChar: (Char) -> Unit,
        onTypeText: (String) -> Unit,
        releaseActiveModifiers: () -> Unit,
    ): String {
        val (added, deleted) = diffRemoteImeCommit(text, newText)
        text = trimBufferIfOversized(newText)
        if (deleted > 0) {
            repeat(deleted) { onBackspace() }
        }
        if (added != null) {
            rememberCommittedPrintableEcho(added)
            emitTypedString(added, hasActiveModifier, onTypeChar, onTypeText, releaseActiveModifiers)
        }
        return text
    }

    fun onPreviewKeyEvent(
        event: KeyEvent,
        hasActiveModifier: Boolean,
        onBackspace: () -> Unit,
        onTypeChar: (Char) -> Unit,
        onTypeText: (String) -> Unit,
        releaseActiveModifiers: () -> Unit,
        onSpecialKey: ((KeyEvent) -> Boolean)?,
    ): Boolean {
        val native = event.nativeKeyEvent
        if (native.action == AndroidKeyEvent.ACTION_MULTIPLE) {
            @Suppress("DEPRECATION")
            val chars = native.characters
            if (!chars.isNullOrEmpty()) {
                text = trimBufferIfOversized(text + chars)
                emitTypedString(chars, hasActiveModifier, onTypeChar, onTypeText, releaseActiveModifiers)
                return true
            }
            return false
        }

        val isDown = native.action == AndroidKeyEvent.ACTION_DOWN
        val isUp = native.action == AndroidKeyEvent.ACTION_UP
        if (!isDown && !isUp) return false

        val key = event.key
        if (key == Key.Backspace) {
            if (isDown) {
                if (text.isNotEmpty()) {
                    text = deleteLastCodePoint(text)
                }
                onBackspace()
                releaseActiveModifiers()
            }
            return true
        }

        if (key == Key.Enter || key == Key.NumPadEnter) {
            if (isDown) {
                text = ""
            }
            val handled = onSpecialKey?.invoke(event) ?: false
            if (isUp && handled) {
                releaseActiveModifiers()
            }
            return handled
        }

        val hasCtrlAltMeta = (native.metaState and (
            AndroidKeyEvent.META_CTRL_MASK or
                AndroidKeyEvent.META_ALT_MASK or
                AndroidKeyEvent.META_META_MASK
            )) != 0

        if (!hasCtrlAltMeta && (key == Key.ShiftLeft || key == Key.ShiftRight)) {
            // Do not leak soft-keyboard Shift scancodes onto the remote wire:
            // soft keyboards frequently emit KEYCODE_SHIFT_LEFT ACTION_DOWN
            // without a matching ACTION_UP when the character commits via
            // commitText, leaving Shift stuck down on the guest (#606).
            return false
        }

        if (!hasCtrlAltMeta) {
            if (isUp) {
                if (native.keyCode == suppressedEchoKeyCode) {
                    suppressedEchoKeyCode = -1
                    return true
                }
                if (native.keyCode == consumedPrintableKeyCode) {
                    consumedPrintableKeyCode = -1
                    return true
                }
            } else {
                if (native.repeatCount > 0 && native.keyCode == suppressedEchoKeyCode) {
                    return true
                }
                val cp = resolvePrintableImeCodePoint(native)
                if (cp != 0) {
                    if (committedThisTurn.isNotEmpty()) {
                        if (committedThisTurn.first().code == cp) {
                            committedThisTurn.removeFirst()
                            suppressedEchoKeyCode = native.keyCode
                            return true
                        }
                        committedThisTurn.clear()
                    }
                    val committedStr = String(Character.toChars(cp))
                    text = trimBufferIfOversized(text + committedStr)
                    consumedPrintableKeyCode = native.keyCode
                    emitTypedString(
                        committedStr,
                        hasActiveModifier,
                        onTypeChar,
                        onTypeText,
                        releaseActiveModifiers,
                    )
                    return true
                }
            }
        }

        return onSpecialKey?.invoke(event) ?: false
    }

    fun drainEchoQueueNow() {
        committedThisTurn.clear()
        echoDrainScheduled = false
    }

    private fun rememberCommittedPrintableEcho(added: String) {
        for (ch in added) {
            if (ch.code >= 0x20) {
                committedThisTurn.addLast(ch)
            }
        }
        if (committedThisTurn.isNotEmpty() && !echoDrainScheduled) {
            echoDrainScheduled = true
            val clear = { drainEchoQueueNow() }
            if (scheduleTwoHopDrain != null) {
                scheduleTwoHopDrain.invoke(clear)
            } else {
                val mainLooper = Looper.getMainLooper()
                if (mainLooper != null) {
                    val handler = Handler(mainLooper)
                    handler.post { handler.post(clear) }
                } else {
                    clear()
                }
            }
        }
    }

    private fun emitTypedString(
        added: String,
        hasActiveModifier: Boolean,
        onTypeChar: (Char) -> Unit,
        onTypeText: (String) -> Unit,
        releaseActiveModifiers: () -> Unit,
    ) {
        if (added.length == 1 && hasActiveModifier) {
            onTypeChar(added[0])
            releaseActiveModifiers()
        } else {
            onTypeText(added)
            releaseActiveModifiers()
        }
    }

    private fun deleteLastCodePoint(s: String): String {
        if (s.isEmpty()) return s
        val drop = if (s.length >= 2 && Character.isSurrogatePair(s[s.length - 2], s[s.length - 1])) 2 else 1
        return s.substring(0, s.length - drop)
    }

    private fun trimBufferIfOversized(s: String): String =
        if (s.length <= REMOTE_IME_BUFFER_MAX_CHARS) s else s.takeLast(REMOTE_IME_BUFFER_MAX_CHARS / 2)
}

/**
 * Resolve a printable Unicode code point from [event], excluding Enter/Tab/control
 * characters and dead-key combining accents. Falls back to `keyCode` + Shift state
 * when `getUnicodeChar` returns 0 (e.g. mockable `android.jar` in JVM unit tests).
 */
internal fun resolvePrintableImeCodePoint(event: AndroidKeyEvent): Int {
    val u = event.getUnicodeChar(event.metaState)
    if (u != 0) {
        if ((u and KeyCharacterMap.COMBINING_ACCENT) != 0) return 0
        if (u < 0x20 || u == '\r'.code || u == '\n'.code || u == '\t'.code || u == 0x7F) return 0
        return u
    }
    val shifted = (event.metaState and AndroidKeyEvent.META_SHIFT_MASK) != 0
    return when (event.keyCode) {
        in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z -> {
            val base = if (shifted) 'A'.code else 'a'.code
            base + (event.keyCode - AndroidKeyEvent.KEYCODE_A)
        }
        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
            val idx = event.keyCode - AndroidKeyEvent.KEYCODE_0
            if (shifted) {
                ")!@#$%^&*("[idx].code
            } else {
                '0'.code + idx
            }
        }
        AndroidKeyEvent.KEYCODE_SPACE -> ' '.code
        AndroidKeyEvent.KEYCODE_AT -> '@'.code
        AndroidKeyEvent.KEYCODE_POUND -> '#'.code
        AndroidKeyEvent.KEYCODE_STAR -> '*'.code
        AndroidKeyEvent.KEYCODE_PLUS -> '+'.code
        AndroidKeyEvent.KEYCODE_MINUS -> (if (shifted) '_' else '-').code
        AndroidKeyEvent.KEYCODE_EQUALS -> (if (shifted) '+' else '=').code
        AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> (if (shifted) '{' else '[').code
        AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> (if (shifted) '}' else ']').code
        AndroidKeyEvent.KEYCODE_BACKSLASH -> (if (shifted) '|' else '\\').code
        AndroidKeyEvent.KEYCODE_SEMICOLON -> (if (shifted) ':' else ';').code
        AndroidKeyEvent.KEYCODE_APOSTROPHE -> (if (shifted) '"' else '\'').code
        AndroidKeyEvent.KEYCODE_SLASH -> (if (shifted) '?' else '/').code
        AndroidKeyEvent.KEYCODE_COMMA -> (if (shifted) '<' else ',').code
        AndroidKeyEvent.KEYCODE_PERIOD -> (if (shifted) '>' else '.').code
        AndroidKeyEvent.KEYCODE_GRAVE -> (if (shifted) '~' else '`').code
        else -> 0
    }
}

/**
 * Hidden 1.dp [BasicTextField] that mirrors the IME buffer and diffs each commit
 * via [diffRemoteImeCommit] without resetting to a sentinel (bug #5), while also
 * forwarding `sendKeyEvent` digits/symbols/capitals and suppressing duplicate
 * `commitText` + `KeyEvent` echoes (#606).
 */
@Composable
fun RemoteDesktopImeField(
    focusRequester: FocusRequester,
    onFieldFocusChanged: (Boolean) -> Unit,
    hasActiveModifier: Boolean,
    onBackspace: () -> Unit,
    onTypeChar: (Char) -> Unit,
    onTypeText: (String) -> Unit,
    releaseActiveModifiers: () -> Unit,
    onPreviewKey: ((KeyEvent) -> Boolean)? = null,
) {
    val controller = remember { RemoteImeController() }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue("", TextRange(0)))
    }
    val currentHasActiveModifier by rememberUpdatedState(hasActiveModifier)
    val currentOnBackspace by rememberUpdatedState(onBackspace)
    val currentOnTypeChar by rememberUpdatedState(onTypeChar)
    val currentOnTypeText by rememberUpdatedState(onTypeText)
    val currentReleaseModifiers by rememberUpdatedState(releaseActiveModifiers)
    val currentOnPreviewKey by rememberUpdatedState(onPreviewKey)

    val fieldModifier = Modifier
        .size(1.dp)
        .focusRequester(focusRequester)
        .onFocusChanged { onFieldFocusChanged(it.isFocused) }
        .onPreviewKeyEvent { event ->
            val handled = controller.onPreviewKeyEvent(
                event = event,
                hasActiveModifier = currentHasActiveModifier,
                onBackspace = currentOnBackspace,
                onTypeChar = currentOnTypeChar,
                onTypeText = currentOnTypeText,
                releaseActiveModifiers = currentReleaseModifiers,
                onSpecialKey = currentOnPreviewKey,
            )
            if (textFieldValue.text != controller.text) {
                textFieldValue = TextFieldValue(controller.text, TextRange(controller.text.length))
            }
            handled
        }

    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val synced = controller.onValueChanged(
                newText = newValue.text,
                hasActiveModifier = currentHasActiveModifier,
                onBackspace = currentOnBackspace,
                onTypeChar = currentOnTypeChar,
                onTypeText = currentOnTypeText,
                releaseActiveModifiers = currentReleaseModifiers,
            )
            textFieldValue = if (synced == newValue.text) {
                newValue
            } else {
                TextFieldValue(synced, TextRange(synced.length))
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
        ),
        modifier = fieldModifier,
    )
}

/** Shared trackpad sensitivity slider dialog (#696) for VNC and RDP/SPICE/GLKVM. */
@Composable
fun RemoteTrackpadSensitivityDialog(
    sensitivity: Float,
    title: String,
    closeLabel: String,
    onSetSensitivity: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember {
        mutableStateOf(sensitivity.coerceIn(TOUCHPAD_SENSITIVITY_MIN, TOUCHPAD_SENSITIVITY_MAX))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSetSensitivity(value); onDismiss() }) {
                Text(closeLabel)
            }
        },
        icon = { Icon(Icons.Default.Speed, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "%.1f×".format(value),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onSetSensitivity(value) },
                    valueRange = TOUCHPAD_SENSITIVITY_MIN..TOUCHPAD_SENSITIVITY_MAX,
                    steps = 27,
                )
            }
        },
    )
}

/** Shared two-finger drag mode toggle: viewport pan (off) vs remote scroll-wheel (on). */
@Composable
fun RemoteScrollModeToggle(
    twoFingerScroll: Boolean,
    scrollOnDescription: String,
    viewportDescription: String,
    onToggle: () -> Unit,
) {
    FilledIconToggleButton(
        checked = twoFingerScroll,
        onCheckedChange = { onToggle() },
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            if (twoFingerScroll) Icons.Default.SwapVert else Icons.Default.OpenWith,
            contentDescription = if (twoFingerScroll) scrollOnDescription else viewportDescription,
        )
    }
}

/** Shared sticky mouse-button hold toggles (L / M / R, #183). */
@Composable
fun RemoteMouseButtonToggles(
    held: Int?,
    onToggle: (Int) -> Unit,
) {
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
