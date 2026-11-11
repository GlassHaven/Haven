package sh.haven.core.wayland

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JNI bridge to the native labwc Wayland compositor.
 * The compositor runs on a dedicated native thread.
 *
 * `liblabwc_android.so` is only built for arm64-v8a. On other ABIs
 * (x86_64, armeabi-v7a) it's absent, so [available] is false. Callers
 * MUST gate on [available] before invoking any `native*` method — the
 * methods are bound implicitly (JNI `Java_..._native*` symbols) and calling
 * one when the library didn't load throws `UnsatisfiedLinkError` ("No
 * implementation found for native method").
 */
object WaylandBridge {
    private const val TAG = "WaylandBridge"

    /** True only if the native compositor library loaded (arm64-v8a). */
    @JvmStatic
    var available: Boolean = false
        private set

    /** When [available] is false: the loadLibrary failure reason, so gate
     *  logs can say WHY the native desktop is disabled (#469). */
    @JvmStatic
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("labwc_android")
            available = true
            Log.i(TAG, "liblabwc_android.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
            Log.w(TAG, "liblabwc_android.so failed to load — native Wayland desktop disabled: ${e.message}")
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

    private val _compositorRunning = MutableStateFlow(false)

    /** Reactive compositor status so UI observers do not have to poll JNI on a timer. */
    val compositorRunning: StateFlow<Boolean> = _compositorRunning.asStateFlow()

    /**
     * Safe status query for callers that just want to know if the compositor
     * is up: returns false (instead of throwing) on ABIs without the native
     * lib. Prefer this over calling [nativeIsRunning] directly.
     */
    fun isCompositorRunning(): Boolean {
        val running = available && nativeIsRunning()
        _compositorRunning.value = running
        return running
    }

    /** Re-read native compositor status and publish any transition to [compositorRunning]. */
    fun refreshCompositorState(): Boolean = isCompositorRunning()

    /** Set the Android Surface for compositor output. Pass null to detach. */
    external fun nativeSetSurface(surface: android.view.Surface?)

    /** Send touch event. action: 0=DOWN, 1=UP, 2=MOVE. x,y: 0..1 normalized. */
    external fun nativeSendTouch(action: Int, x: Float, y: Float)

    /** Send key event. linuxKeyCode: evdev keycode. pressed: 1=down, 0=up. */
    external fun nativeSendKey(linuxKeyCode: Int, pressed: Int)

    /** Send scroll event. axis: 0=vertical, 1=horizontal. value: positive=down/right. */
    external fun nativeSendScroll(axis: Int, value: Float)

    /** Resize compositor output without recreating the surface. width/height in physical pixels. */
    external fun nativeResize(width: Int, height: Int)

    /** Set zoom level in permille (500-4000, where 1000 = 1:1). Higher = bigger text.
     *  commit=true reflows the terminal (use on pinch end). */
    external fun nativeSetZoom(zoomPermille: Int, commit: Boolean)

    /** Set viewport offset in compositor buffer pixels (for pan/scroll). */
    external fun nativeSetViewport(x: Int, y: Int)

    /** Toggle a native Wayland client binary (e.g. GPU benchmark). Returns true if started, false if stopped. */
    external fun nativeLaunchBenchmark(binaryPath: String): Boolean

    /** Start virgl_test_server for GPU-accelerated OpenGL in PRoot apps. */
    external fun nativeStartVirglServer(binaryPath: String, socketPath: String)

    /** Stop the virgl_test_server process. */
    external fun nativeStopVirglServer()

    /** Check if virgl_test_server is still running. */
    external fun nativeIsVirglRunning(): Boolean
}
