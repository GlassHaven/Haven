package sh.haven.core.vnc

import android.graphics.Bitmap

/**
 * Configuration for a VNC client connection.
 */
class VncConfig {
    var passwordSupplier: (() -> String)? = null
    var usernameSupplier: (() -> String)? = null
    var onScreenUpdate: ((Bitmap) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null
    var onBell: (() -> Unit)? = null
    var onRemoteClipboard: ((String) -> Unit)? = null
    /**
     * Called when the server sends a new cursor shape via the Cursor
     * pseudo-encoding. Args: ARGB_8888 bitmap, hotspot x, hotspot y.
     * An empty cursor (0×0) is signalled as a null bitmap and hotspot 0,0.
     */
    var onCursorUpdate: ((Bitmap?, Int, Int) -> Unit)? = null
    var shared: Boolean = true
    var targetFps: Int = 30
    var colorDepth: ColorDepth = ColorDepth.BPP_24_TRUE
}

enum class ColorDepth(
    val bitsPerPixel: Int,
    val depth: Int,
    val trueColor: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int,
) {
    BPP_8_INDEXED(8, 8, false, 0, 0, 0, 0, 0, 0),
    BPP_8_TRUE(8, 8, true, 7, 3, 7, 0, 6, 3),
    BPP_16_TRUE(16, 16, true, 31, 63, 31, 11, 5, 0),
    BPP_24_TRUE(32, 24, true, 255, 255, 255, 16, 8, 0),
}
