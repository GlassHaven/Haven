package sh.haven.app.agent

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncClient
import sh.haven.core.vnc.VncConfig
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped owner of live app-window VNC connections, keyed by the
 * DesktopManager sessionId. A connection's lifetime is the
 * [PresentedMedia][sh.haven.core.data.agent.PresentedMedia] — created when
 * the window is first shown, released on dismiss — **not** the overlay
 * composition. That's what lets it survive the overlay→Picture-in-Picture→
 * overlay transition, where the bottom sheet leaves composition (which would
 * otherwise tear the `VncClient` down). Both the interactive overlay
 * ([VncSessionContent][sh.haven.feature.vnc.VncSessionContent]) and the
 * passive PiP view read the same controller's `frame` StateFlow.
 */
@Singleton
class AppWindowConnectionStore @Inject constructor() {
    private val controllers = ConcurrentHashMap<String, AppWindowVncController>()

    /** Get the controller for [sessionId], creating + connecting on first call. */
    fun controllerFor(sessionId: String, host: String, port: Int): AppWindowVncController =
        controllers.getOrPut(sessionId) {
            AppWindowVncController().also { it.connect(host, port) }
        }

    /** Stop and drop the connection for [sessionId]. Idempotent. */
    fun release(sessionId: String) {
        controllers.remove(sessionId)?.stop()
    }
}

/**
 * Owns a [VncClient] for one app window: connects to the cage-kiosk wayvnc at
 * host:port and exposes frame/connected/error. All client I/O runs on a
 * private IO scope — socket sends and the [VncClient.typeText] pacing must
 * never touch the main thread. Lifecycle is managed by [AppWindowConnectionStore].
 */
class AppWindowVncController {
    val frame = MutableStateFlow<Bitmap?>(null)
    val connected = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var client: VncClient? = null

    fun connect(host: String, port: Int) {
        scope.launch {
            val config = VncConfig().apply {
                colorDepth = ColorDepth.BPP_24_TRUE
                shared = true
                onScreenUpdate = { bmp -> frame.value = bmp }
                onError = { e ->
                    error.value = e.message ?: "VNC connection error"
                    connected.value = false
                }
            }
            val c = VncClient(config)
            client = c
            try {
                c.start(host, port)
                connected.value = true
            } catch (e: Exception) {
                error.value = e.message ?: "Failed to connect to the app window"
            }
        }
    }

    fun click(x: Int, y: Int, button: Int) {
        scope.launch { client?.moveMouse(x, y); client?.click(button) }
    }

    fun dragStart(x: Int, y: Int) {
        scope.launch { client?.moveMouse(x, y); client?.updateMouseButton(1, true) }
    }

    fun drag(x: Int, y: Int) {
        scope.launch { client?.moveMouse(x, y) }
    }

    fun dragEnd() {
        scope.launch { client?.updateMouseButton(1, false) }
    }

    /** Hold/release an arbitrary mouse button (1=L, 2=M, 3=R) for the
     *  toolbar's explicit mouse-button toggles (#183). */
    fun pressButton(button: Int) {
        scope.launch { client?.updateMouseButton(button, true) }
    }

    fun releaseButton(button: Int) {
        scope.launch { client?.updateMouseButton(button, false) }
    }

    fun scroll(up: Boolean) {
        scope.launch { client?.click(if (up) 4 else 5) }
    }

    fun key(sym: Int, down: Boolean) {
        scope.launch { client?.updateKey(sym, down) }
    }

    fun typeText(s: String) {
        scope.launch { client?.typeText(s) }
    }

    fun copyText(s: String) {
        scope.launch { client?.copyText(s) }
    }

    fun stop() {
        connected.value = false
        val c = client
        client = null
        // VncClient.stop() joins its event-loop threads (up to ~1s) — never
        // on the main thread. Run it off-thread, then cancel the IO scope.
        Thread {
            runCatching { c?.stop() }
            scope.cancel()
        }.apply { isDaemon = true }.start()
    }
}
