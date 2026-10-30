package sh.haven.app.desktop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import sh.haven.core.glkvm.GlkvmCertMismatch
import sh.haven.core.glkvm.GlkvmClient
import sh.haven.core.glkvm.GlkvmException
import java.io.Closeable

/**
 * [RemoteDesktopSession] over a [GlkvmClient] (GLKVM kvmd HTTPS API).
 *
 * Unlike VNC/SPICE the kvmd API has no push stream for this client — video
 * is a snapshot JPEG pulled on a timer, so this class owns the poll loop
 * and decodes into the caller's frame callback. The kvmd API exposes no
 * remote clipboard, so `sendClipboardText` is a no-op (same as SPICE's
 * adapter until it grows a verb).
 *
 * The interface's +deltaY-is-up wheel convention is flipped here: the
 * device convention (measured in kvmd.py) is delta_y = -3 per notch down.
 * KeyboardEvent.code lane: kvmd `send_key` takes position codes, dispatched
 * via [sendKey] beyond the [RemoteDesktopSession] surface (same shape as
 * SPICE's scancode lane).
 *
 * The GL absolute-mouse mode maps only the middle 90% of frame width, so
 * clicks in the outer 5% margins land at the reachable edge (see
 * [GlkvmGeometry] docs) — relative moves will close that gap later.
 */
class GlkvmDesktopSession(
    private val client: GlkvmClient,
    /** Target machine's OS keyboard layout for IME char strokes ("uk"/"us", null = uk). */
    val targetLayout: String? = null,
    /** ms between snapshot fetches while the tab is live and foregrounded. */
    private val pollIntervalMs: Long = GLKVM_POLL_INTERVAL_MS,
    private val onFrame: (Bitmap) -> Unit,
    private val onConnected: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onDisconnected: () -> Unit,
    /** Optional test hook overriding the single-frame snapshot fetch+decode. */
    private val pollSnap: (() -> Unit)? = null,
) : RemoteDesktopSession, Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val pollLock = Any()
    private val eventSeq = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var paused = false

    @Volatile
    private var connecting = true

    @Volatile
    private var closed = false

    @Volatile
    private var lastFrameSeq = 0L

    @Volatile
    private var lastInputSeq = 0L

    @Volatile
    private var lastFrameAtMs = 0L

    @Volatile
    private var lastInputAtMs = 0L

    private val activeInputs = java.util.concurrent.atomic.AtomicInteger(0)

    /** Detected active horizontal bounds `[activeLeftPx, activeLeftPx + activeWidthPx]` within the captured frame. */
    val activeLeftPx: Int get() = client.activeLeftPx
    val activeWidthPx: Int get() = client.activeWidthPx

    /** Launch the snapshot poll loop. Non-blocking (unlike SPICE's blocking start()). */
    fun start() {
        check(pollJob == null) { "GlkvmDesktopSession.start() called twice" }
        pollJob = scope.launch { pollLoop() }
    }

    /** One snapshot fetch+decode+dispatch. */
    @Throws(Exception::class)
    internal fun fetchAndDispatchFrame() = pollOnce()

    /**
     * Ensure the latest frame reflects recent input or a paused tab before an
     * MCP capture/OCR call reads it. If an input completed after (or within
     * [minSettleMs] before) the last fetched snapshot, waits the remaining
     * settle window so the target OS has rendered the change, then pulls one
     * fresh snapshot synchronously.
     */
    internal fun refreshFrameIfNeeded(
        force: Boolean = false,
        minSettleMs: Long = GLKVM_INPUT_SETTLE_MS,
    ) {
        if (closed) return
        // Wait briefly if an input call is still in flight on another thread.
        val waitDeadline = System.currentTimeMillis() + 500L
        while (activeInputs.get() > 0 && System.currentTimeMillis() < waitDeadline) {
            Thread.sleep(15)
        }
        val now = System.currentTimeMillis()
        val inputSinceLastFrame = lastInputSeq > lastFrameSeq ||
            (lastInputAtMs > 0L && lastFrameAtMs < lastInputAtMs + minSettleMs)
        val needsRefresh = force || paused || lastFrameSeq == 0L || inputSinceLastFrame
        if (!needsRefresh) return

        if (lastInputAtMs > 0L && minSettleMs > 0L) {
            val elapsed = System.currentTimeMillis() - lastInputAtMs
            if (elapsed in 0 until minSettleMs) {
                Thread.sleep(minSettleMs - elapsed)
            }
        }
        runCatching { pollOnce() }
    }

    private suspend fun pollLoop() {
        try {
            // First snapshot decides "connected"; failures here surface via onError.
            pollOnce()
            connecting = false
            onConnected()
            while (coroutineContext.isActive) {
                if (paused) {
                    delay(GLKVM_PAUSE_IDLE_MS)
                    continue
                }
                delay(pollIntervalMs)
                runCatching { pollOnce() }
                    .onFailure { e ->
                        // While HID input (e.g. a multi-character paste) is actively
                        // executing against kvmd, snapshot requests can contend with
                        // kvmd's HTTP worker pool; don't count contended ticks toward
                        // the fatal disconnect threshold.
                        if (activeInputs.get() > 0) {
                            Log.d(TAG, "GLKVM snapshot tick contended during active input: ${e.message}")
                            return@onFailure
                        }
                        // A single failed tick (brief Wi-Fi drop, token flip)
                        // shouldn't kill the tab — mark and continue; on the
                        // next success the frame flows again. Persistent
                        // failures surface as a disconnect after a threshold.
                        consecutiveFailures++
                        Log.w(TAG, "GLKVM snapshot tick failed ($consecutiveFailures): ${e.message}")
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            throw e
                        }
                    }
                    .onSuccess { consecutiveFailures = 0 }
            }
            // Loop exited — cancel() was called.
            closed = true
            onDisconnected()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            closed = true
            throw ce // normal close
        } catch (e: Throwable) {
            closed = true
            Log.e(TAG, "GLKVM session error", e)
            onError(e)
        }
    }

    private fun pollOnce(): Unit = synchronized(pollLock) {
        val custom = pollSnap
        if (custom != null) {
            custom()
            lastFrameSeq = eventSeq.incrementAndGet()
            lastFrameAtMs = System.currentTimeMillis()
            return@synchronized
        }
        val jpeg = client.snapshot()
        if (jpeg.isEmpty()) throw GlkvmException("empty snapshot from /streamer/snapshot")
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw GlkvmException("snapshot JPEG failed to decode (${jpeg.size} bytes)")
        // Resolution can change with the target's mode switch — the mouse
        // calibration fractions are frame-relative, so keep them fresh.
        client.frameWidth = bmp.width
        client.frameHeight = bmp.height
        val (activeLeft, activeWidth) = detectActiveHorizontalBounds(bmp)
        client.activeLeftPx = activeLeft
        client.activeWidthPx = activeWidth
        onFrame(bmp)
        lastFrameSeq = eventSeq.incrementAndGet()
        lastFrameAtMs = System.currentTimeMillis()
    }

    private fun detectActiveHorizontalBounds(bmp: Bitmap): Pair<Int, Int> {
        val w = bmp.width
        val h = bmp.height
        if (w < 64 || h < 64) return 0 to w
        // Sample 16 vertical positions spanning the full height (including top titlebar & bottom taskbar).
        val sampleYs = IntArray(16) { i -> ((i + 0.5) * h / 16.0).toInt().coerceIn(0, h - 1) }
        return sh.haven.core.glkvm.GlkvmGeometry.detectHorizontalPillarbox(w) { x ->
            var allBlack = true
            for (y in sampleYs) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (!sh.haven.core.glkvm.GlkvmGeometry.isPillarboxBlackRgb(r, g, b)) {
                    allBlack = false
                    break
                }
            }
            allBlack
        }
    }

    private var consecutiveFailures = 0

    @Volatile
    private var lastTargetMouseX: Int = -1

    @Volatile
    private var lastTargetMouseY: Int = -1

    @Volatile
    private var lastMousePositionAtMs: Long = 0L

    // -- RemoteDesktopSession ------------------------------------------------

    override fun sendMouseMove(x: Int, y: Int) = runCatchingInput {
        lastTargetMouseX = x
        lastTargetMouseY = y
        lastMousePositionAtMs = System.currentTimeMillis()
        client.sendMouseMove(x, y)
    }

    override fun sendMouseButton(button: Int, pressed: Boolean) =
        runCatchingInput { client.sendMouseButton(toKvmdButton(button), pressed) }

    override fun sendMouseClick(x: Int, y: Int, button: Int) = runCatchingInput {
        lastTargetMouseX = x
        lastTargetMouseY = y
        lastMousePositionAtMs = System.currentTimeMillis()
        client.sendMouseClick(x, y, toKvmdButton(button))
    }

    /**
     * Abstraction: positive deltaY scrolls UP; kvmd device convention is opposite.
     * Re-asserts the last intentional target pointer position before scrolling if
     * enough time has elapsed for the GLKVM hardware mouse wobbler to drift the cursor.
     */
    override fun sendMouseWheel(deltaY: Int) {
        val notches = deltaY.coerceIn(-NOTCH_MAX, NOTCH_MAX)
        if (notches == 0) return
        runCatchingInput {
            val tx = lastTargetMouseX
            val ty = lastTargetMouseY
            val now = System.currentTimeMillis()
            if (tx >= 0 && ty >= 0 && now - lastMousePositionAtMs >= GLKVM_WOBBLE_REASSERT_MS) {
                client.sendMouseMove(tx, ty)
                lastMousePositionAtMs = now
            }
            client.sendWheel(-notches)
        }
    }

    // kvmd has no remote-clipboard verb; keep the no-op explicit.
    override fun sendClipboardText(text: String) {}

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun close() {
        closed = true
        scope.cancel()
        client.close()
    }

    // -- keyboard lane (beyond the RemoteDesktopSession surface, like SPICE) --

    /**
     * Press or release a key by KeyboardEvent.code name. Physical-key
     * dispatch converts Android key events → codes; IME text should use the
     * [sh.haven.core.glkvm.GlkvmLayout] sequences (phase-2 MCP verb, not the
     * UI lane).
     */
    fun sendKey(code: String, pressed: Boolean) = runCatchingInput {
        client.sendKey(code, pressed)
    }

    /**
     * Type [text] as real keystrokes (blocking; run through the input lane).
     * Layout-targeted: mapped chars are position codes + Shift, the rest go
     * to kvmd's US-layout printer.
     */
    fun typeText(text: String) = runCatchingInput {
        client.typeText(text, targetLayout, isCancelled = { closed || !scope.isActive })
    }

    fun describeCertError(e: Throwable): String? =
        (e as? GlkvmCertMismatch)?.message

    // -- helpers ---------------------------------------------------------------

    private inline fun runCatchingInput(block: () -> Unit) {
        if (closed) return
        activeInputs.incrementAndGet()
        try {
            block()
            consecutiveFailures = 0
        } catch (e: Exception) {
            // Input on a dead session fires from lane dispatch after a drop;
            // swallow and log so the lane keeps draining other tabs.
            Log.w(TAG, "GLKVM input dropped: ${e.message}")
        } finally {
            lastInputSeq = eventSeq.incrementAndGet()
            lastInputAtMs = System.currentTimeMillis()
            activeInputs.decrementAndGet()
        }
    }

    private fun toKvmdButton(x11Button: Int): String = when (x11Button) {
        2 -> "middle"
        3 -> "right"
        else -> "left"
    }

    companion object {
        private const val TAG = "GlkvmDesktopSession"

        /** ~4 fps default snapshot cadence — configurable at construction. */
        const val GLKVM_POLL_INTERVAL_MS = 250L

        /** Minimum target render-settle window after HID input before an on-demand MCP capture. */
        const val GLKVM_INPUT_SETTLE_MS = 120L

        /** Idle threshold after which sendMouseWheel re-asserts lastTargetMouseX/Y against hardware mouse-wobbler drift. */
        const val GLKVM_WOBBLE_REASSERT_MS = 750L

        /** Parked loop delay while paused (don't busy-spin, don't drift far either). */
        const val GLKVM_PAUSE_IDLE_MS = 500L

        /** Fail ticks before the session is declared dead. */
        const val MAX_CONSECUTIVE_FAILURES = 12

        /** Wheel notches sent per abstraction call. */
        const val NOTCH_MAX = 10
    }
}