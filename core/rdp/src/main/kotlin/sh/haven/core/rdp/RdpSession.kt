package sh.haven.core.rdp

import android.graphics.Bitmap
import android.util.Log
import sh.haven.rdp.FrameCallback
import sh.haven.rdp.FrameData
import sh.haven.rdp.MouseButton
import sh.haven.rdp.RdpClient
import sh.haven.rdp.RdpConfig
import sh.haven.rdp.RdpException
import java.io.Closeable
import java.nio.ByteBuffer

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
    private val onDisconnected: (() -> Unit)? = null,
) : Closeable {

    @Volatile
    private var closed = false
    private var client: RdpClient? = null
    private var currentBitmap: Bitmap? = null

    /** Called on frame updates. Set by the ViewModel. */
    var onFrameUpdate: ((Bitmap) -> Unit)? = null

    /** Called when an error occurs. */
    var onError: ((Exception) -> Unit)? = null

    /**
     * Start the RDP connection on the current thread.
     * Call from Dispatchers.IO.
     */
    fun start() {
        if (closed) return
        Log.d(TAG, "Starting RDP session $sessionId: $host:$port user=$username")

        try {
            val config = RdpConfig(
                username = username,
                password = password,
                domain = domain,
                width = width.toUShort(),
                height = height.toUShort(),
                colorDepth = 32u,
            )

            val c = RdpClient(config)
            client = c

            c.setFrameCallback(object : FrameCallback {
                override fun onFrameUpdate(x: UShort, y: UShort, w: UShort, h: UShort) {
                    if (closed) return
                    try {
                        refreshBitmap()
                    } catch (e: Exception) {
                        Log.e(TAG, "Frame update failed (${x},${y} ${w}x${h})", e)
                        onError?.invoke(e)
                    }
                }

                override fun onResize(width: UShort, height: UShort) {
                    if (closed) return
                    Log.d(TAG, "Desktop resized: ${width}x${height}")
                    try {
                        synchronized(this@RdpSession) {
                            currentBitmap?.recycle()
                            currentBitmap = null
                        }
                        refreshBitmap()
                    } catch (e: Exception) {
                        Log.e(TAG, "Resize failed (${width}x${height})", e)
                        onError?.invoke(e)
                    }
                }
            })

            Log.d(TAG, "Connecting to $host:$port...")
            c.connect(host, port.toUShort())
            Log.d(TAG, "RDP connected to $host:$port")

            // Initial frame
            refreshBitmap()
            Log.d(TAG, "Initial frame received")
        } catch (e: Exception) {
            Log.e(TAG, "RDP connection failed", e)
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
            Log.e(TAG, "getFramebuffer() failed", e)
            onError?.invoke(e)
            return
        }
        val bitmap = try {
            frameToBitmap(frame)
        } catch (e: Exception) {
            Log.e(TAG, "frameToBitmap() failed (${frame.width}x${frame.height}, ${frame.pixels.size} bytes)", e)
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

    override fun close() {
        if (closed) return
        closed = true
        Log.d(TAG, "Closing RDP session $sessionId")
        try {
            client?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting RDP", e)
        }
        client = null
        synchronized(this) {
            currentBitmap?.recycle()
            currentBitmap = null
        }
    }
}
