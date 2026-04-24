package sh.haven.core.rdp

import android.graphics.Bitmap
import android.util.Log
import sh.haven.rdp.FrameCallback
import sh.haven.rdp.FrameData
import sh.haven.rdp.MouseButton
import sh.haven.rdp.RdpClient
import sh.haven.rdp.RdpConfig
import sh.haven.rdp.RdpException
import sh.haven.rdp.SessionCallback
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "RdpSession"

/**
 * Wraps an [RdpClient] (IronRDP via UniFFI) with Android-specific
 * bitmap management and lifecycle.
 *
 * Similar pattern to VncClient but adapted for RDP:
 * - RDP uses scancodes, not X11 KeySyms
 * - Frame delivery via polling getFramebuffer() + callback for dirty rects
 * - No mid-session resize (RDP requires reconnect)
 */
class RdpSession(
    val sessionId: String,
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val useNla: Boolean = true,
    private val onDisconnected: (() -> Unit)? = null,
    private val verboseBuffer: ConcurrentLinkedQueue<String>? = null,
) : Closeable {

    @Volatile
    private var closed = false
    private var client: RdpClient? = null
    private var currentBitmap: Bitmap? = null
    private val startTime = System.currentTimeMillis()

    private fun log(level: String, msg: String) {
        if (level == "E") Log.e(TAG, msg) else Log.d(TAG, msg)
        verboseBuffer?.add("+${System.currentTimeMillis() - startTime}ms [$TAG] $level: $msg")
    }

    /** Called on frame updates. Set by the ViewModel. */
    var onFrameUpdate: ((Bitmap) -> Unit)? = null

    /** Called when an error occurs. */
    var onError: ((Exception) -> Unit)? = null

    /**
     * Called once the RDP handshake + capability exchange completes. Prior to
     * this the session is still in the "Connecting" phase even though
     * [start] has returned — the Rust connect() only spawns the worker
     * thread. The UI uses this to flip from "Connecting…" to the rendered
     * framebuffer.
     */
    var onConnected: ((Int, Int) -> Unit)? = null

    /**
     * Start the RDP session. Call from `Dispatchers.IO`.
     *
     * Returns once the TCP connection is established and the worker thread
     * is spawned — this is NOT equivalent to a usable RDP session. The
     * handshake, TLS upgrade and capability exchange happen asynchronously
     * on the worker thread; [onConnected] fires when it completes, [onError]
     * fires if it fails.
     */
    fun start() {
        if (closed) return
        log("D", "Starting RDP session $sessionId: $host:$port user=$username")

        try {
            val config = RdpConfig(
                username = username,
                password = password,
                domain = domain,
                width = width.toUShort(),
                height = height.toUShort(),
                colorDepth = 32u,
                enableCredssp = useNla,
            )

            val c = RdpClient(config)
            client = c

            c.setFrameCallback(object : FrameCallback {
                override fun onFrameUpdate(x: UShort, y: UShort, w: UShort, h: UShort) {
                    if (closed) return
                    try {
                        refreshBitmap()
                    } catch (e: Exception) {
                        log("E", "Frame update failed (${x},${y} ${w}x${h}): ${e.message}")
                        onError?.invoke(e)
                    }
                }

                override fun onResize(width: UShort, height: UShort) {
                    if (closed) return
                    log("D", "Desktop resized: ${width}x${height}")
                    try {
                        synchronized(this@RdpSession) {
                            currentBitmap?.recycle()
                            currentBitmap = null
                        }
                        refreshBitmap()
                    } catch (e: Exception) {
                        log("E", "Resize failed (${width}x${height}): ${e.message}")
                        onError?.invoke(e)
                    }
                }
            })

            c.setSessionCallback(object : SessionCallback {
                override fun onConnected(width: UShort, height: UShort) {
                    if (closed) return
                    log("D", "RDP handshake complete: ${width}x${height}")
                    onConnected?.invoke(width.toInt(), height.toInt())
                }

                override fun onError(message: String) {
                    if (closed) return
                    log("E", "RDP session error: $message")
                    this@RdpSession.onError?.invoke(RuntimeException(message))
                }

                override fun onDisconnected() {
                    if (closed) return
                    log("D", "RDP session ended cleanly")
                    onDisconnected?.invoke()
                }
            })

            log("D", "Connecting to $host:$port (worker thread will handle handshake)")
            c.connect(host, port.toUShort())
        } catch (e: UnsatisfiedLinkError) {
            val msg = "RDP native library failed to load: ${e.message}"
            log("E", msg)
            val wrapped = RuntimeException(msg, e)
            onError?.invoke(wrapped)
            onDisconnected?.invoke()
            throw wrapped
        } catch (e: Exception) {
            log("E", "RDP connect dispatch failed: ${e.message}")
            onError?.invoke(e)
            onDisconnected?.invoke()
            throw e
        }
    }

    private fun refreshBitmap() {
        val c = client ?: return
        val frame = try {
            c.getFramebuffer() ?: return
        } catch (e: Exception) {
            log("E", "getFramebuffer() failed: ${e.message}")
            onError?.invoke(e)
            return
        }
        val bitmap = try {
            frameToBitmap(frame)
        } catch (e: Exception) {
            log("E", "frameToBitmap() failed (${frame.width}x${frame.height}, ${frame.pixels.size} bytes): ${e.message}")
            onError?.invoke(e)
            return
        }
        synchronized(this) {
            currentBitmap = bitmap
        }
        onFrameUpdate?.invoke(bitmap)
    }

    /**
     * Convert FrameData (ARGB_8888 byte array) to Android Bitmap.
     */
    private fun frameToBitmap(frame: FrameData): Bitmap {
        val w = frame.width.toInt()
        val h = frame.height.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.pixels))
        return bitmap
    }

    /** Get the current frame as a Bitmap. */
    fun getFrame(): Bitmap? = synchronized(this) { currentBitmap }

    // --- Input forwarding ---

    fun sendKey(scancode: Int, pressed: Boolean) {
        if (closed) return
        client?.sendKey(scancode.toUShort(), pressed)
    }

    fun sendUnicodeKey(codepoint: Int, pressed: Boolean) {
        if (closed) return
        client?.sendUnicodeKey(codepoint.toUInt(), pressed)
    }

    fun sendMouseMove(x: Int, y: Int) {
        if (closed) return
        client?.sendMouseMove(x.toUShort(), y.toUShort())
    }

    fun sendMouseButton(button: MouseButton, pressed: Boolean) {
        if (closed) return
        client?.sendMouseButton(button, pressed)
    }

    fun sendMouseClick(x: Int, y: Int, button: MouseButton = MouseButton.LEFT) {
        if (closed) return
        client?.sendMouseMove(x.toUShort(), y.toUShort())
        client?.sendMouseButton(button, true)
        client?.sendMouseButton(button, false)
    }

    fun sendMouseWheel(vertical: Boolean, delta: Int) {
        if (closed) return
        client?.sendMouseWheel(vertical, delta.toShort())
    }

    fun sendClipboardText(text: String) {
        if (closed) return
        client?.sendClipboardText(text)
    }

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
        log("D", "Closing RDP session $sessionId")
        try {
            client?.disconnect()
        } catch (e: Exception) {
            log("E", "Error disconnecting RDP: ${e.message}")
        }
        client = null
        synchronized(this) {
            currentBitmap?.recycle()
            currentBitmap = null
        }
    }
}
