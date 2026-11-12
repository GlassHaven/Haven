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

    /** Deduplicates cursor shape bitmaps across position-only `SPICE_MSG_CURSOR_MOVE` callbacks. */
    private val cursorTracker = SpiceCursorTracker { cursor, w, h ->
        cursorToBitmap(cursor, w, h)
    }

    @Volatile
    private var lastFrameWidth: Int = 0

    @Volatile
    private var lastFrameHeight: Int = 0

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
                        val frameW = frame.width.toInt()
                        val frameH = frame.height.toInt()
                        if (frameW != lastFrameWidth || frameH != lastFrameHeight) {
                            lastFrameWidth = frameW
                            lastFrameHeight = frameH
                            log("D", "surface size: ${frameW}x${frameH}")
                        }
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
                    try {
                        cursorTracker.onCursor(
                            cursor = cursor,
                            onPosition = onCursorPosition,
                            onShape = onCursorUpdate,
                        )
                    } catch (e: Exception) {
                        log("E", "cursorToBitmap failed (${cursor.width}x${cursor.height}): ${e.message}")
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
        if (closed || (scancode and 0x7F) == 0) return
        val wire = spiceWireScancode(scancode, pressed)
        log("D", "input: key 0x${scancode.toString(16)} (wire=0x${wire.toString(16)}) ${if (pressed) "down" else "up"}")
        client?.sendKey(wire, pressed)
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
        sendMouseMove(x, y)
        sendMouseButton(button, true)
        sendMouseButton(button, false)
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
        cursorTracker.clear()
        synchronized(this) {
            currentBitmap?.recycle()
            currentBitmap = null
        }
    }
}

/**
 * Deduplicates SPICE cursor shape updates across high-rate `SPICE_MSG_CURSOR_MOVE`
 * callbacks (`spice-client`'s `cursor.rs` invokes `on_cursor` with the full RGBA
 * shape buffer on every pointer move).
 *
 * - Always forwards `(cursor.x, cursor.y)` via [onPosition].
 * - Only decodes a new bitmap via [decodeShape] when `(width, height, pixels)`
 *   changes; hotspot-only changes reuse the decoded bitmap and re-emit [onShape].
 * - Suppresses duplicate [onShape] emissions when neither visibility, bitmap, nor
 *   hotspot changed, avoiding per-move `Bitmap.createBitmap` allocations,
 *   `CursorOverlay` `StateFlow` updates, and Compose `asImageBitmap()` texture uploads.
 * - Re-emits the cached bitmap without re-decoding when a hidden cursor becomes
 *   visible again with the same shape, and preserves `#598`'s initial `null`
 *   emission when `CURSOR_INIT(visible = false)` arrives at connect time.
 */
internal class SpiceCursorTracker<B : Any>(
    private val decodeShape: (CursorData, Int, Int) -> B,
) {
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cachedPixels: ByteArray? = null
    private var cachedBitmap: B? = null
    private var emittedHotX: Int = 0
    private var emittedHotY: Int = 0
    private var emittedVisible: Boolean? = null

    @Synchronized
    fun onCursor(
        cursor: CursorData,
        onPosition: ((Int, Int) -> Unit)?,
        onShape: ((B?, Int, Int) -> Unit)?,
    ) {
        onPosition?.invoke(cursor.x, cursor.y)
        val w = cursor.width.toInt()
        val h = cursor.height.toInt()
        if (!cursor.visible || w <= 0 || h <= 0 || cursor.pixels.size < w * h * 4) {
            if (emittedVisible != false) {
                emittedVisible = false
                onShape?.invoke(null, 0, 0)
            }
            return
        }

        val hotX = cursor.hotX.toInt()
        val hotY = cursor.hotY.toInt()
        val samePixels = cachedBitmap != null &&
            w == cachedWidth &&
            h == cachedHeight &&
            cachedPixels != null &&
            cachedPixels.contentEquals(cursor.pixels)

        if (samePixels && emittedVisible == true && hotX == emittedHotX && hotY == emittedHotY) {
            return
        }

        val bmp = if (samePixels) {
            cachedBitmap!!
        } else {
            val decoded = decodeShape(cursor, w, h)
            cachedWidth = w
            cachedHeight = h
            cachedPixels = cursor.pixels.copyOf()
            cachedBitmap = decoded
            decoded
        }
        emittedHotX = hotX
        emittedHotY = hotY
        emittedVisible = true
        onShape?.invoke(bmp, hotX, hotY)
    }

    @Synchronized
    fun clear() {
        cachedWidth = 0
        cachedHeight = 0
        cachedPixels = null
        cachedBitmap = null
        emittedHotX = 0
        emittedHotY = 0
        emittedVisible = null
    }
}

/**
 * Encode a PC XT/AT Set-1 scancode (with `0xE000` marking extended keys, matching
 * `RdpScreen` / `RdpKeyMapping`) into the 32-bit little-endian `code` word expected
 * by `SPICE_MSGC_INPUTS_KEY_DOWN` / `SPICE_MSGC_INPUTS_KEY_UP` (`spice-server`
 * `inputs-channel.cpp` + `spice-gtk` `spice_make_scancode`):
 *
 * - `spice-server` pushes `code` byte-by-byte (`(code >> (i * 8)) & 0xff`, stopping
 *   at `0x00`) straight into `kbd_push_scan` for BOTH `KEY_DOWN` and `KEY_UP`, so
 *   key releases MUST carry the PS/2 `0x80` break bit (`base | 0x80`). Sending the
 *   unmodified make code on `KEY_UP` pressed every key a second time and left keys
 *   and `Shift` stuck down in the guest (#606).
 * - Extended `0xE0`-prefixed keys must push `0xE0` first (bits 0..7) and
 *   `base` / `base | 0x80` second (bits 8..15), i.e. `0xE0 | (byte1 << 8)`.
 */
internal fun spiceWireScancode(scancode: Int, pressed: Boolean): UInt {
    val base = scancode and 0x7F
    val scanByte = if (pressed) base else (base or 0x80)
    return if ((scancode and 0xFF00) == 0xE000) {
        (0xE0 or (scanByte shl 8)).toUInt()
    } else {
        scanByte.toUInt()
    }
}
