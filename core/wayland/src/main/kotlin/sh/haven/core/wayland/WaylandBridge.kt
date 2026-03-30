package sh.haven.core.wayland

import android.util.Log

/**
 * JNI bridge to the native labwc Wayland compositor.
 * The compositor runs on a dedicated native thread.
 */
object WaylandBridge {
    private const val TAG = "WaylandBridge"

    init {
        try {
            System.loadLibrary("labwc_android")
            Log.i(TAG, "liblabwc_android.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load liblabwc_android.so", e)
        }
    }

    /**
     * Start the Wayland compositor on a background thread.
     * Creates a Wayland socket at [xdgRuntimeDir]/wayland-0.
     */
    external fun nativeStart(
        xdgRuntimeDir: String,
        xkbConfigRoot: String,
        fontconfigFile: String,
    )

    /** Stop the compositor and wait for the thread to exit. */
    external fun nativeStop()

    /** Returns the path to the Wayland socket (e.g. "/data/.../wayland-0"). */
    external fun nativeGetSocketPath(): String

    /** Returns true if the compositor event loop is running. */
    external fun nativeIsRunning(): Boolean

    /** Set the Android Surface for compositor output. Pass null to detach. */
    external fun nativeSetSurface(surface: android.view.Surface?)

    /** Send touch event. action: 0=DOWN, 1=UP, 2=MOVE. x,y: 0..1 normalized. */
    external fun nativeSendTouch(action: Int, x: Float, y: Float)

    /** Send key event. linuxKeyCode: evdev keycode. pressed: 1=down, 0=up. */
    external fun nativeSendKey(linuxKeyCode: Int, pressed: Int)

    /** Resize compositor output without recreating the surface. width/height in physical pixels. */
    external fun nativeResize(width: Int, height: Int)

    /** Set zoom level in permille (500-4000, where 1000 = 1:1). Higher = bigger text.
     *  commit=true reflows the terminal (use on pinch end). */
    external fun nativeSetZoom(zoomPermille: Int, commit: Boolean)

    /** Set viewport offset in compositor buffer pixels (for pan/scroll). */
    external fun nativeSetViewport(x: Int, y: Int)
}
