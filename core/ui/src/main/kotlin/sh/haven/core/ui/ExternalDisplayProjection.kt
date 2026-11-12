package sh.haven.core.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Snapshot of the currently projected session's framebuffer and cursor overlay
 * for rendering on an external [Presentation] display (#790).
 */
data class ExternalDisplayFrameState(
    val sessionKey: String,
    val frame: Bitmap?,
    val frameSeq: Long = 0L,
    val cursor: CursorOverlay? = null,
    val pointerX: Int = 0,
    val pointerY: Int = 0,
    val showCursor: Boolean = true,
    val showFallbackCursor: Boolean = false,
    val coverFill: Boolean = false,
)

/**
 * Aspect-preserving destination rectangle and scale factor for rendering a
 * remote desktop framebuffer (`fbWidth` × `fbHeight`) onto an external
 * presentation display (`viewWidth` × `viewHeight`).
 */
data class ExternalDisplayLayout(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val scale: Float,
)

fun computeExternalDisplayLayout(
    viewWidth: Int,
    viewHeight: Int,
    fbWidth: Int,
    fbHeight: Int,
    coverFill: Boolean = false,
): ExternalDisplayLayout {
    if (viewWidth <= 0 || viewHeight <= 0 || fbWidth <= 0 || fbHeight <= 0) {
        return ExternalDisplayLayout(0, 0, 0, 0, 1f)
    }
    val scaleX = viewWidth.toFloat() / fbWidth
    val scaleY = viewHeight.toFloat() / fbHeight
    val scale = if (coverFill) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
    val dstW = (fbWidth * scale).roundToInt()
    val dstH = (fbHeight * scale).roundToInt()
    val left = (viewWidth - dstW) / 2
    val top = (viewHeight - dstH) / 2
    return ExternalDisplayLayout(
        left = left,
        top = top,
        right = left + dstW,
        bottom = top + dstH,
        scale = scale,
    )
}

fun computeExternalCursorPosition(
    layout: ExternalDisplayLayout,
    pointerX: Int,
    pointerY: Int,
    hotspotX: Int,
    hotspotY: Int,
    fbWidth: Int,
    fbHeight: Int,
): Pair<Float, Float> {
    if (fbWidth <= 0 || fbHeight <= 0) return 0f to 0f
    val clampedX = pointerX.coerceIn(0, fbWidth - 1)
    val clampedY = pointerY.coerceIn(0, fbHeight - 1)
    val x = layout.left + (clampedX - hotspotX) * layout.scale
    val y = layout.top + (clampedY - hotspotY) * layout.scale
    return x to y
}

/**
 * Pure state machine tracking attached `DISPLAY_CATEGORY_PRESENTATION` displays
 * (HDMI, USB-C DisplayPort alt-mode, wireless display), the user's external
 * projection toggle, and which desktop session is pinned to the external
 * monitor across fullscreen exits and tab switches (#790).
 */
class ExternalDisplayProjectionController(
    initialEnabled: Boolean = true,
) {
    var availableDisplayIds by mutableStateOf<List<Int>>(emptyList())
        private set

    var projectionEnabled by mutableStateOf(initialEnabled)
        private set

    var activeSessionKey by mutableStateOf<String?>(null)
        private set

    var currentFrameState by mutableStateOf<ExternalDisplayFrameState?>(null)
        private set

    var mountedViewerSessions by mutableStateOf<Set<String>>(emptySet())
        private set

    val hasExternalDisplay: Boolean
        get() = availableDisplayIds.isNotEmpty()

    val activeDisplayId: Int?
        get() = if (projectionEnabled) availableDisplayIds.firstOrNull() else null

    val isProjecting: Boolean
        get() = activeDisplayId != null

    fun isProjectingSession(sessionKey: String? = null): Boolean =
        if (sessionKey == null) {
            isProjecting
        } else {
            isProjecting && (activeSessionKey == null || activeSessionKey == sessionKey)
        }

    fun claimSessionIfUnset(sessionKey: String) {
        if (activeSessionKey == null) {
            activeSessionKey = sessionKey
        }
    }

    fun onViewerAttached(sessionKey: String) {
        mountedViewerSessions = mountedViewerSessions + sessionKey
        if (activeSessionKey == null) {
            activeSessionKey = sessionKey
        }
    }

    /**
     * Called when a viewer composable leaves composition (e.g. when the user
     * exits fullscreen mode and switches to Terminal, Connections, SFTP, Manage,
     * or another desktop tab). Keeps [activeSessionKey] and [currentFrameState]
     * intact so the external [Presentation] keeps running uninterrupted (#790).
     */
    fun onViewerDetached(sessionKey: String) {
        mountedViewerSessions = mountedViewerSessions - sessionKey
    }

    fun isViewerMounted(sessionKey: String): Boolean =
        sessionKey in mountedViewerSessions

    fun onPresentationDisplaysChanged(displayIds: List<Int>) {
        availableDisplayIds = displayIds
    }

    fun toggleProjection(sessionKey: String? = null) {
        if (sessionKey != null && projectionEnabled && activeSessionKey != null && activeSessionKey != sessionKey) {
            activeSessionKey = sessionKey
            currentFrameState = null
        } else {
            projectionEnabled = !projectionEnabled
            if (projectionEnabled && sessionKey != null && activeSessionKey != sessionKey) {
                activeSessionKey = sessionKey
                currentFrameState = null
            }
        }
    }

    fun updateSessionFrame(
        sessionKey: String,
        frame: Bitmap?,
        frameSeq: Long = 0L,
        cursor: CursorOverlay? = null,
        pointerX: Int = 0,
        pointerY: Int = 0,
        showCursor: Boolean = true,
        showFallbackCursor: Boolean = false,
        coverFill: Boolean = false,
    ) {
        if (activeSessionKey == null) {
            activeSessionKey = sessionKey
        }
        if (activeSessionKey == sessionKey) {
            currentFrameState = ExternalDisplayFrameState(
                sessionKey = sessionKey,
                frame = frame,
                frameSeq = frameSeq,
                cursor = cursor,
                pointerX = pointerX,
                pointerY = pointerY,
                showCursor = showCursor,
                showFallbackCursor = showFallbackCursor,
                coverFill = coverFill,
            )
        }
    }

    fun releaseSession(sessionKey: String, fallbackSessionKey: String? = null) {
        mountedViewerSessions = mountedViewerSessions - sessionKey
        if (activeSessionKey == sessionKey) {
            activeSessionKey = fallbackSessionKey
            currentFrameState = null
        }
    }
}

/**
 * Per-session view of [ExternalDisplayProjectionController] returned to
 * `VncViewer` / `RdpViewer` so each tab sees its own `isProjecting` state and
 * can claim or toggle external projection (#790).
 */
class ExternalDisplaySessionView(
    val controller: ExternalDisplayProjectionController,
    val sessionKey: String?,
) {
    val hasExternalDisplay: Boolean
        get() = controller.hasExternalDisplay

    val isProjecting: Boolean
        get() = controller.isProjectingSession(sessionKey)

    fun toggleProjection() {
        controller.toggleProjection(sessionKey)
    }
}

val LocalExternalDisplayProjectionController =
    staticCompositionLocalOf<ExternalDisplayProjectionController?> { null }

/**
 * Activity-root host that keeps the external [Presentation] window alive across
 * fullscreen toggles, orientation changes, and tab switches (#790).
 */
@Composable
fun ProvideExternalDisplayProjection(
    controller: ExternalDisplayProjectionController = remember { ExternalDisplayProjectionController() },
    content: @Composable () -> Unit,
) {
    BindExternalDisplayPresentation(controller)
    CompositionLocalProvider(LocalExternalDisplayProjectionController provides controller) {
        content()
    }
}

@Composable
private fun BindExternalDisplayPresentation(
    controller: ExternalDisplayProjectionController,
) {
    val context = LocalContext.current
    val displayManager = remember(context) {
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }
    var presentation by remember { mutableStateOf<ExternalDesktopPresentation?>(null) }

    DisposableEffect(displayManager, controller) {
        if (displayManager == null) {
            return@DisposableEffect onDispose {}
        }
        val handler = Handler(Looper.getMainLooper())
        fun syncDisplays() {
            val displays = displayManager
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .orEmpty()
            controller.onPresentationDisplaysChanged(displays.map { it.displayId })
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = syncDisplays()
            override fun onDisplayRemoved(displayId: Int) = syncDisplays()
            override fun onDisplayChanged(displayId: Int) = syncDisplays()
        }
        displayManager.registerDisplayListener(listener, handler)
        syncDisplays()
        onDispose {
            displayManager.unregisterDisplayListener(listener)
            runCatching { presentation?.dismiss() }
            presentation = null
        }
    }

    val frameState = controller.currentFrameState
    val hasValidFrame = frameState?.frame?.let { !it.isRecycled && it.width > 0 && it.height > 0 } == true
    val targetDisplayId = if (hasValidFrame) controller.activeDisplayId else null

    DisposableEffect(targetDisplayId, displayManager) {
        val current = presentation
        if (current != null && current.display.displayId != targetDisplayId) {
            runCatching { current.dismiss() }
            presentation = null
        }
        if (targetDisplayId != null && displayManager != null && presentation == null) {
            val targetDisplay: Display? = displayManager
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.firstOrNull { it.displayId == targetDisplayId }
            if (targetDisplay != null) {
                runCatching {
                    ExternalDesktopPresentation(context, targetDisplay).also { pres ->
                        pres.setOnDismissListener {
                            if (presentation === pres) {
                                presentation = null
                            }
                        }
                        pres.show()
                        presentation = pres
                    }
                }
            }
        }
        onDispose {}
    }

    SideEffect {
        if (frameState != null) {
            presentation?.updateState(
                frame = frameState.frame,
                frameSeq = frameState.frameSeq,
                cursor = frameState.cursor,
                pointerX = frameState.pointerX,
                pointerY = frameState.pointerY,
                showCursor = frameState.showCursor,
                showFallbackCursor = frameState.showFallbackCursor,
                coverFill = frameState.coverFill,
            )
        }
    }
}

/**
 * Toolbar / fullscreen-menu toggle button shown when an external presentation
 * display (HDMI / USB-C / wireless monitor) is connected (#790).
 *
 * When checked (`isProjecting == true`), the remote desktop renders on the
 * external monitor via [Presentation] and the phone's canvas blanks to pure
 * black (`#000000`) to save battery while continuing to act as the trackpad /
 * touch input surface.
 */
@Composable
fun ExternalDisplayToggle(
    active: Boolean,
    onToggle: () -> Unit,
) {
    FilledIconToggleButton(
        checked = active,
        onCheckedChange = { onToggle() },
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            Icons.Default.Tv,
            contentDescription = if (active) {
                stringResource(R.string.external_display_cd_on)
            } else {
                stringResource(R.string.external_display_cd_off)
            },
        )
    }
}

/**
 * Binds a remote desktop viewer (`VncViewer` / `RdpViewer`) to the shared
 * [LocalExternalDisplayProjectionController] (or a local fallback controller
 * when rendered outside [ProvideExternalDisplayProjection]) (#790).
 *
 * When [sessionKey] is provided and hosted inside [ProvideExternalDisplayProjection],
 * leaving composition (e.g. exiting fullscreen and switching to another tab)
 * detaches only the viewer without dismissing the external [Presentation].
 */
@Composable
fun rememberExternalDisplayProjection(
    frame: Bitmap?,
    frameSeq: Long = 0L,
    frameSeqState: State<Long>? = null,
    cursor: CursorOverlay? = null,
    pointerX: Int = 0,
    pointerY: Int = 0,
    showCursor: Boolean = true,
    showFallbackCursor: Boolean = false,
    coverFill: Boolean = false,
    sessionKey: String? = null,
): ExternalDisplaySessionView {
    val sharedController = LocalExternalDisplayProjectionController.current
    val fallbackController = if (sharedController == null) {
        remember { ExternalDisplayProjectionController() }.also {
            BindExternalDisplayPresentation(it)
        }
    } else {
        null
    }
    val controller = sharedController ?: fallbackController!!
    val effectiveKey = sessionKey ?: "default"

    DisposableEffect(controller, effectiveKey, sessionKey != null) {
        controller.onViewerAttached(effectiveKey)
        onDispose {
            if (sharedController != null && sessionKey != null) {
                controller.onViewerDetached(effectiveKey)
            } else {
                controller.releaseSession(effectiveKey)
            }
        }
    }

    // Isolate `frameSeqState.value` inside a tiny restartable leaf composable so
    // `RdpViewer` (and its `!fullscreen` bottom toolbar, #607) does not recompose
    // on every in-place framebuffer update.
    SyncExternalDisplaySessionFrame(
        controller = controller,
        effectiveKey = effectiveKey,
        frame = frame,
        frameSeq = frameSeq,
        frameSeqState = frameSeqState,
        cursor = cursor,
        pointerX = pointerX,
        pointerY = pointerY,
        showCursor = showCursor,
        showFallbackCursor = showFallbackCursor,
        coverFill = coverFill,
    )

    return remember(controller, sessionKey) {
        ExternalDisplaySessionView(controller, sessionKey)
    }
}

@Composable
private fun SyncExternalDisplaySessionFrame(
    controller: ExternalDisplayProjectionController,
    effectiveKey: String,
    frame: Bitmap?,
    frameSeq: Long,
    frameSeqState: State<Long>?,
    cursor: CursorOverlay?,
    pointerX: Int,
    pointerY: Int,
    showCursor: Boolean,
    showFallbackCursor: Boolean,
    coverFill: Boolean,
) {
    val resolvedSeq = frameSeqState?.value ?: frameSeq
    SideEffect {
        controller.updateSessionFrame(
            sessionKey = effectiveKey,
            frame = frame,
            frameSeq = resolvedSeq,
            cursor = cursor,
            pointerX = pointerX,
            pointerY = pointerY,
            showCursor = showCursor,
            showFallbackCursor = showFallbackCursor,
            coverFill = coverFill,
        )
    }
}

private class ExternalDesktopPresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display) {

    private var desktopView: ExternalDesktopView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ExternalDesktopView(context)
        desktopView = view
        setContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun updateState(
        frame: Bitmap?,
        frameSeq: Long,
        cursor: CursorOverlay?,
        pointerX: Int,
        pointerY: Int,
        showCursor: Boolean,
        showFallbackCursor: Boolean,
        coverFill: Boolean,
    ) {
        desktopView?.update(
            frame = frame,
            frameSeq = frameSeq,
            cursor = cursor,
            pointerX = pointerX,
            pointerY = pointerY,
            showCursor = showCursor,
            showFallbackCursor = showFallbackCursor,
            coverFill = coverFill,
        )
    }
}

private class ExternalDesktopView(context: Context) : View(context) {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    private val dstRect = Rect()
    private val cursorDstRect = Rect()
    private val fallbackArrowPath = Path()

    private var frame: Bitmap? = null
    private var frameSeq: Long = 0L
    private var cursor: CursorOverlay? = null
    private var pointerX: Int = 0
    private var pointerY: Int = 0
    private var showCursor: Boolean = true
    private var showFallbackCursor: Boolean = false
    private var coverFill: Boolean = false

    init {
        setBackgroundColor(AndroidColor.BLACK)
    }

    fun update(
        frame: Bitmap?,
        frameSeq: Long,
        cursor: CursorOverlay?,
        pointerX: Int,
        pointerY: Int,
        showCursor: Boolean,
        showFallbackCursor: Boolean,
        coverFill: Boolean,
    ) {
        this.frame = frame
        this.frameSeq = frameSeq
        this.cursor = cursor
        this.pointerX = pointerX
        this.pointerY = pointerY
        this.showCursor = showCursor
        this.showFallbackCursor = showFallbackCursor
        this.coverFill = coverFill
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = frame ?: return
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return

        val layout = computeExternalDisplayLayout(
            viewWidth = width,
            viewHeight = height,
            fbWidth = bmp.width,
            fbHeight = bmp.height,
            coverFill = coverFill,
        )
        dstRect.set(layout.left, layout.top, layout.right, layout.bottom)
        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)

        if (!showCursor) return
        val cur = cursor
        if (cur != null && !cur.bitmap.isRecycled) {
            val (cx, cy) = computeExternalCursorPosition(
                layout = layout,
                pointerX = pointerX,
                pointerY = pointerY,
                hotspotX = cur.hotspotX,
                hotspotY = cur.hotspotY,
                fbWidth = bmp.width,
                fbHeight = bmp.height,
            )
            val cw = (cur.bitmap.width * layout.scale).roundToInt().coerceAtLeast(1)
            val ch = (cur.bitmap.height * layout.scale).roundToInt().coerceAtLeast(1)
            val left = cx.roundToInt()
            val top = cy.roundToInt()
            cursorDstRect.set(left, top, left + cw, top + ch)
            canvas.drawBitmap(cur.bitmap, null, cursorDstRect, bitmapPaint)
        } else if (showFallbackCursor) {
            val (cx, cy) = computeExternalCursorPosition(
                layout = layout,
                pointerX = pointerX,
                pointerY = pointerY,
                hotspotX = 0,
                hotspotY = 0,
                fbWidth = bmp.width,
                fbHeight = bmp.height,
            )
            val s = layout.scale.coerceAtLeast(1f)
            fallbackArrowPath.reset()
            fallbackArrowPath.moveTo(cx, cy)
            fallbackArrowPath.lineTo(cx, cy + 16f * s)
            fallbackArrowPath.lineTo(cx + 4.5f * s, cy + 12.5f * s)
            fallbackArrowPath.lineTo(cx + 7.5f * s, cy + 19f * s)
            fallbackArrowPath.lineTo(cx + 10f * s, cy + 18f * s)
            fallbackArrowPath.lineTo(cx + 7f * s, cy + 11.5f * s)
            fallbackArrowPath.lineTo(cx + 12f * s, cy + 11.5f * s)
            fallbackArrowPath.close()
            canvas.drawPath(fallbackArrowPath, cursorFillPaint)
            canvas.drawPath(fallbackArrowPath, cursorOutlinePaint)
        }
    }
}
