package sh.haven.core.local.guestnotify

import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide owner of the guest notification bridge (#851). Idempotent
 * start/stop so both the MCP attach/detach verbs and the local-session
 * auto-start path can drive it without double-binding the abstract socket.
 *
 * Unlike the GPS broker there is no hardware engine to refcount — the bridge
 * is cheap, so it simply runs while started and stops on detach. Its process
 * lifetime rides the existing specialUse foreground-service participants (the
 * MCP endpoint and local sessions); it starts no service of its own.
 *
 * A Hilt singleton (its only dependency, [GuestNotifier], is injectable), unlike
 * `GpsBroker` which is a plain object because it closes over McpTools-owned
 * callbacks. Reached from `LocalSessionManager` (auto-start) and the MCP
 * `NotifyToolProvider`.
 */
@Singleton
class GuestNotifyBridge @Inject constructor(
    private val notifier: GuestNotifier,
) {
    private val lock = Any()
    private var server: NotifyGuestServer? = null

    val isRunning: Boolean get() = synchronized(lock) { server != null }

    /** Bind the abstract socket (idempotent). Returns bridge status JSON. */
    fun start(): JSONObject = synchronized(lock) {
        val existing = server
        val s = existing ?: NotifyGuestServer(notifier).also {
            it.start()
            server = it
        }
        statusJson(s).put(if (existing != null) "alreadyRunning" else "attached", true)
    }

    /** Tear down the bridge. Returns counters; a no-op when not running. */
    fun stop(): JSONObject = synchronized(lock) {
        val s = server ?: return JSONObject().put("detached", false).put("reason", "not running")
        s.stop()
        server = null
        JSONObject()
            .put("detached", true)
            .put("posted", s.postedTotal.get())
            .put("rateLimited", s.rateLimitedTotal.get())
            .put("errors", s.errorsTotal.get())
    }

    fun statusJson(): JSONObject = synchronized(lock) {
        server?.let { statusJson(it) } ?: JSONObject().put("running", false)
    }

    /** The `list_bridges` row for this bridge (source/sink/transport + counters). */
    fun bridgeRow(): JSONObject {
        val s = statusJson()
        return JSONObject()
            .put("source", "Notifications").put("sourceKind", "notify")
            .put("sink", "linux-guest").put("transport", "android-notification")
            .put("state", "active")
            .put("socketName", s.optString("socketName"))
            .put("channel", s.optString("channel"))
            .put("posted", s.optLong("posted"))
            .put("rateLimited", s.optLong("rateLimited"))
            .put("errors", s.optLong("errors"))
    }

    companion object {
        /** Abstract-namespace socket name the guest `haven-notify` helper connects to. */
        const val SOCKET_NAME: String = "haven-notify"
    }

    private fun statusJson(s: NotifyGuestServer): JSONObject = JSONObject()
        .put("running", true)
        .put("socketName", SOCKET_NAME)
        .put("socketNamespace", "abstract")
        .put("channel", GuestNotifier.CHANNEL_ID)
        .put("posted", s.postedTotal.get())
        .put("rateLimited", s.rateLimitedTotal.get())
        .put("errors", s.errorsTotal.get())
        .put("clients", s.clientsConnectedTotal.get())
}
