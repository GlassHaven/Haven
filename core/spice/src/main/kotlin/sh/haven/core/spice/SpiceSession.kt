package sh.haven.core.spice

import android.graphics.Bitmap
import android.util.Log
import sh.haven.spice.CursorCallback
import sh.haven.spice.CursorData
import sh.haven.spice.FrameCallback
import sh.haven.spice.FrameData
import sh.haven.spice.MouseButton
import sh.haven.spice.SessionCallback
import sh.haven.spice.SpiceClient
import sh.haven.spice.SpiceConfig
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "SpiceSession"

/**
 * Wraps a [SpiceClient] (pure-Rust SPICE client via UniFFI, #286) with
 * Android-specific bitmap management and lifecycle. Mirrors [sh.haven.core.rdp.RdpSession]
 * so the desktop viewer adapter (Stage 4) is identical across RDP/VNC/SPICE.
 *
 * Differs from RDP in two ways that come from the transport:
 * - SPICE delivers the *whole* surface on each update ([FrameCallback.onFrame]),
 *   so there's no dirty-rect poll — the frame bytes arrive directly.
 * - A single [CursorCallback.onCursor] carries shape, position and visibility.
 */
class SpiceSession(
    val sessionId: String,
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val width: Int = 1920,
    private val height: Int = 1080,
    onDisconnected: (() -> Unit)? = null,
    private val verboseBuffer: ConcurrentLinkedQueue<String>? = null,
) : Closeable {

    @Volatile
    private var closed = false
    private var client: SpiceClient? = null
    private var currentBitmap: Bitmap? = null
    private val startTime = System.currentTimeMillis()

    private fun log(level: String, msg: String) {
        if (level == "E") Log.e(TAG, msg) else Log.d(TAG, msg)
        verboseBuffer?.add("+${System.currentTimeMillis() - startTime}ms [$TAG] $level: $msg")
    }

    /** Called on each full-frame update. Set by the ViewModel. */
    var onFrameUpdate: ((Bitmap) -> Unit)? = null

    /**
     * Called when the server pushes a new cursor shape / visibility. Args mirror
     * RDP/VNC: (cursor bitmap or null to hide, hotspot x, hotspot y). The app
     * layer wraps this into a CursorOverlay.
     */
    var onCursorUpdate: ((Bitmap?, Int, Int) -> Unit)? = null

    /** Called when the server moves the pointer. Advisory in touchpad mode. */
    var onCursorPosition: ((Int, Int) -> Unit)? = null

    /** Last non-null cursor shape, re-emitted when a position-only update arrives. */
    private var lastCursor: Triple<Bitmap, Int, Int>? = null

    /** Called when an error occurs. */
    var onError: ((Exception) -> Unit)? = null

    /**
     * Called when the native session loop ends without a surfaced error — a
     * server-side close, or a socket death the loop treats as a clean exit.
     * Settable post-construction (like [onError]) so the desktop tab can mark
     * itself dead instead of staying "connected" forever (#437).
     */
    var onDisconnected: (() -> Unit)? = onDisconnected

    /**
     * Called once the SPICE session is fully established. Unlike RDP, the Rust
     * [SpiceClient.connect] blocks until the channels are up, so this fires from
     * within [start] just before connect returns.
     */
    var onConnected: ((Int, Int) -> Unit)? = null

    /**
     * Start the SPICE session. Call from `Dispatchers.IO` — [SpiceClient.connect]
     * blocks until the session is established (or throws), driving the Tokio
     * runtime internally. Frame/cursor callbacks then fire from runtime worker
     * threads; [onConnected] fires on this thread as connect returns.
     */
    fun start() {
        if (closed) return
        log("D", "Starting SPICE session $sessionId: $host:$port")

        try {
            val c = SpiceClient(SpiceConfig(width.toUShort(), height.toUShort()))
            client = c

            c.setFrameCallback(object : FrameCallback {
                override fun onFrame(frame: FrameData) {
                    if (closed) return
                    try {
                        val bmp = frameToBitmap(frame)
                        synchronized(this@SpiceSession) { currentBitmap = bmp }
                        onFrameUpdate?.invoke(bmp)
                    } catch (e: Exception) {
                        log("E", "frameToBitmap failed (${frame.width}x${frame.height}, ${frame.pixels.size} bytes): ${e.message}")
                        onError?.invoke(e)
                    }
                }
            })

            c.setCursorCallback(object : CursorCallback {
                override fun onCursor(cursor: CursorData) {
                    if (closed) return
                    onCursorPosition?.invoke(cursor.x, cursor.y)
                    val w = cursor.width.toInt()
                    val h = cursor.height.toInt()
                    if (!cursor.visible || w <= 0 || h <= 0 || cursor.pixels.size < w * h * 4) {
                        onCursorUpdate?.invoke(null, 0, 0)
                        return
                    }
                    try {
                        val bmp = cursorToBitmap(cursor, w, h)
                        lastCursor = Triple(bmp, cursor.hotX.toInt(), cursor.hotY.toInt())
                        onCursorUpdate?.invoke(bmp, cursor.hotX.toInt(), cursor.hotY.toInt())
                    } catch (e: Exception) {
                        log("E", "cursorToBitmap failed (${w}x$h): ${e.message}")
                    }
                }
            })

            c.setSessionCallback(object : SessionCallback {
                override fun onConnected(width: UShort, height: UShort) {
                    if (closed) return
                    log("D", "SPICE session established: ${width}x${height}")
                    onConnected?.invoke(width.toInt(), height.toInt())
                }

                override fun onError(message: String) {
                    if (closed) return
                    log("E", "SPICE session error: $message")
                    this@SpiceSession.onError?.invoke(RuntimeException(message))
                }

                override fun onDisconnected() {
                    if (closed) return
                    log("D", "SPICE session ended cleanly")
                    onDisconnected?.invoke()
                }
            })

            log("D", "Connecting to $host:$port (blocks until established)")
            c.connect(host, port.toUShort(), password)
        } catch (e: UnsatisfiedLinkError) {
            val msg = "SPICE native library failed to load: ${e.message}"
            log("E", msg)
            val wrapped = RuntimeException(msg, e)
            onError?.invoke(wrapped)
            onDisconnected?.invoke()
            throw wrapped
        } catch (e: Exception) {
            // Class name + trace, not just the message: a bare NPE logs
            // "null" and the log can't say WHERE it died (#549 was exactly
            // that — undebuggable from a reporter's log until this line).
            log(
                "E",
                "SPICE connect failed: ${e::class.java.name}: ${e.message}\n" +
                    e.stackTraceToString().take(2000),
            )
            onError?.invoke(e)
            onDisconnected?.invoke()
            throw e
        }
    }

    /** Convert FrameData (RGBA, opaque) to an Android Bitmap. */
    private fun frameToBitmap(frame: FrameData): Bitmap {
        val w = frame.width.toInt()
        val h = frame.height.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.pixels))
        return bitmap
    }

    /**
     * Convert a SPICE cursor shape (RGBA) to an Android Bitmap.
     * ponytail: SPICE ALPHA cursors are *premultiplied* RGBA, so copy the bytes
     * verbatim into a (premultiplied) ARGB_8888 bitmap — the IntArray
     * `createBitmap` path RDP uses assumes non-premultiplied and would
     * double-premultiply (dark cursor edges).
     */
    private fun cursorToBitmap(cursor: CursorData, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(cursor.pixels))
        return bitmap
    }

    /** Get the current frame as a Bitmap. */
    fun getFrame(): Bitmap? = synchronized(this) { currentBitmap }

    // --- Input forwarding ---

    // #549: input events sampled into the verbose log. The reporter's "no
    // detailed log for this entry" left the pointer investigation blind on
    // the user side — whether the touch layer even called sendMouseMove was
    // unknowable from their reports. Moves are throttled (first 10, then
    // every 100th) because they arrive at drag rate; buttons/keys are rare
    // enough to log every one.
    private var mouseMoveCount = 0L

    fun sendKey(scancode: Int, pressed: Boolean) {
        if (closed) return
        log("D", "input: key 0x${scancode.toString(16)} ${if (pressed) "down" else "up"}")
        client?.sendKey(scancode.toUInt(), pressed)
    }

    fun sendMouseMove(x: Int, y: Int) {
        if (closed) return
        val n = ++mouseMoveCount
        if (n <= 10 || n % 100 == 0L) {
            log("D", "input: mouse move #$n -> ($x,$y)")
        }
        client?.sendMouseMove(x, y)
    }

    fun sendMouseButton(button: MouseButton, pressed: Boolean) {
        if (closed) return
        log("D", "input: mouse $button ${if (pressed) "down" else "up"} (after $mouseMoveCount moves)")
        client?.sendMouseButton(button, pressed)
    }

    fun sendMouseClick(x: Int, y: Int, button: MouseButton = MouseButton.LEFT) {
        if (closed) return
        client?.sendMouseMove(x, y)
        client?.sendMouseButton(button, true)
        client?.sendMouseButton(button, false)
    }

    /** Vertical wheel: positive scrolls up, negative down. */
    fun sendMouseWheel(delta: Int) {
        if (closed) return
        client?.sendMouseWheel(delta)
    }

    // ponytail: SPICE wrapper has no clipboard/unicode verbs yet — omitted (added if
    // the transport gains them). RemoteDesktopSession.sendClipboardText is a no-op at Stage 4.

    /** Drain captured verbose logs. Returns null if verbose logging was not enabled. */
    fun drainVerboseLog(): String? {
        val buf = verboseBuffer ?: return null
        if (buf.isEmpty()) return null
        val sb = StringBuilder()
        while (true) {
            val line = buf.poll() ?: break
            sb.appendLine(line)
        }
        return sb.toString().trimEnd()
    }

    override fun close() {
        if (closed) return
        closed = true
        log("D", "Closing SPICE session $sessionId")
        try {
            client?.disconnect()
        } catch (e: Exception) {
            log("E", "Error disconnecting SPICE: ${e.message}")
        }
        client = null
        synchronized(this) {
            currentBitmap?.recycle()
            currentBitmap = null
        }
    }
}
