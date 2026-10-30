package sh.haven.core.data.desktop

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live connection state of a remote-desktop (VNC/RDP) tab. DISCONNECTED means
 * the tab is still open but its session ended (server logoff, transport death)
 * — distinct from ERROR (a surfaced failure) and from absence (tab closed).
 */
enum class DesktopStatus { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

/** Current server cursor shape for a desktop tab. */
data class CursorSnapshot(val bitmap: Bitmap, val hotspotX: Int, val hotspotY: Int)

/**
 * A capturable handle to a live remote-desktop (VNC/RDP) tab's rendered output,
 * so the MCP layer can screenshot what the *viewer* shows — colours and all —
 * without coupling to `DesktopViewModel`. The tab registers lazy accessors; the
 * MCP `capture_desktop_tab` tool reads them on demand. Returns raw `Bitmap` +
 * primitives only (no UI types) to keep `core:data` UI-free.
 */
class DesktopFrameHandle(
    val protocol: String,
    val frame: () -> Bitmap?,
    val cursor: () -> CursorSnapshot?,
    val pointer: () -> Pair<Int, Int>,
    /**
     * Optional on-demand snapshot pull for poll-based protocols (GLKVM), invoked
     * before `capture_desktop_tab` / `inspect_desktop_tab` so a screenshot taken
     * right after input or while the viewer tab is paused reflects the latest
     * remote screen state.
     */
    val refreshFrame: ((force: Boolean) -> Bitmap?)? = null,
    /**
     * Optional active horizontal desktop bounds `(activeLeftPx, activeWidthPx)` for
     * pillarboxed HDMI capture sessions (GLKVM), surfaced in `capture_desktop_tab`
     * and `inspect_desktop_tab`.
     */
    val activeBounds: (() -> Pair<Int, Int>)? = null,
)

/**
 * An input handle to a live remote-desktop (VNC/RDP) tab, so the MCP layer can
 * inject mouse/clipboard input without coupling to `DesktopViewModel` or the
 * app-module `RemoteDesktopSession` type. The tab registers closures; the MCP
 * input tools call them. Mirrors [DesktopFrameHandle]. Mouse buttons follow the
 * X11 convention (1=left, 2=middle, 3=right). Keyboard input is out of scope
 * until `RemoteDesktopSession` grows a keysym/scancode verb — EXCEPT text
 * typing, which a protocol that can type as keystrokes may expose via the
 * optional [typeText] (GLKVM's `typeText` does; VNC/RDP/SPICE leave it null
 * and the MCP tool reports "not supported on this protocol").
 */
class DesktopInputHandle(
    val protocol: String,
    val mouseMove: (x: Int, y: Int) -> Unit,
    val mouseClick: (x: Int, y: Int, button: Int) -> Unit,
    val mouseWheel: (deltaY: Int) -> Unit,
    val clipboard: (text: String) -> Unit,
    var typeText: ((text: String) -> Unit)? = null,
    val mouseButton: (button: Int, pressed: Boolean) -> Unit = { _, _ -> },
    var sendKey: ((code: String, pressed: Boolean) -> Unit)? = null,
)

/**
 * A single user-originated input event on a live remote-desktop tab (distinct
 * from MCP-injected agent input), surfaced by `inspect_desktop_tab` and
 * `capture_desktop_tab` so an agent sharing the session can tell whether the
 * user has collaborated or intervened.
 */
data class DesktopUserInteraction(
    val timestampMs: Long,
    val type: String,
    val x: Int? = null,
    val y: Int? = null,
    val detail: String? = null,
)

/**
 * App-scoped mirror of the live state of remote-desktop tabs, keyed by the
 * connection profile id.
 *
 * Desktop tabs live in `DesktopViewModel`, but the connections list (a
 * separate ViewModel) needs to reflect whether a VNC/RDP profile's desktop is
 * connecting / connected so its row shows an accurate indicator. Rather than
 * coupling the two ViewModels, `DesktopViewModel` publishes per-profile status
 * here and `ConnectionsViewModel` observes it. This is the *real* desktop
 * status (the actual handshake), as opposed to merely "the SSH tunnel is up".
 */
@Singleton
class DesktopSessionRegistry @Inject constructor() {
    private val _statuses = MutableStateFlow<Map<String, DesktopStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, DesktopStatus>> = _statuses.asStateFlow()

    /** Record [status] for [profileId] (no-op when [profileId] is null). */
    fun setStatus(profileId: String?, status: DesktopStatus) {
        if (profileId == null) return
        _statuses.update { it + (profileId to status) }
    }

    /** Drop any status for [profileId] — the desktop tab is gone. */
    fun clear(profileId: String?) {
        if (profileId == null) return
        _statuses.update { it - profileId }
    }

    // --- Capturable frame handles (for MCP capture_desktop_tab) ---

    private val frameHandles = ConcurrentHashMap<String, DesktopFrameHandle>()

    /** Register (or replace) the capturable handle for [profileId]. */
    fun registerFrameHandle(profileId: String?, handle: DesktopFrameHandle) {
        if (profileId == null) return
        frameHandles[profileId] = handle
    }

    /** Drop the frame handle for [profileId] — the desktop tab is gone. */
    fun clearFrameHandle(profileId: String?) {
        if (profileId == null) return
        frameHandles.remove(profileId)
    }

    /** The frame handle for [profileId], or null if none registered. */
    fun frameHandle(profileId: String): DesktopFrameHandle? = frameHandles[profileId]

    /** All registered frame handles, keyed by profileId. */
    fun frameHandles(): Map<String, DesktopFrameHandle> = frameHandles.toMap()

    // --- Input handles (for MCP remote-desktop input tools) ---

    private val inputHandles = ConcurrentHashMap<String, DesktopInputHandle>()

    /** Register (or replace) the input handle for [profileId]. */
    fun registerInputHandle(profileId: String?, handle: DesktopInputHandle) {
        if (profileId == null) return
        inputHandles[profileId] = handle
    }

    /** Drop the input handle for [profileId] — the desktop tab is gone. */
    fun clearInputHandle(profileId: String?) {
        if (profileId == null) return
        inputHandles.remove(profileId)
    }

    /** The input handle for [profileId], or null if none registered. */
    fun inputHandle(profileId: String): DesktopInputHandle? = inputHandles[profileId]

    /** All registered input handles, keyed by profileId. */
    fun inputHandles(): Map<String, DesktopInputHandle> = inputHandles.toMap()

    // --- Close handles (for MCP disconnect_profile, #437) ---
    //
    // Direct (non-SSH-tunnelled) desktop tabs have no tunnel lease, so the
    // MCP disconnect path had no way to close them — the tab survived with a
    // stale status. The tab registers a closure that closes it; mirrors the
    // frame/input handle pattern.

    private val closeHandles = ConcurrentHashMap<String, () -> Unit>()

    /** Register (or replace) the tab-close closure for [profileId]. */
    fun registerCloseHandle(profileId: String?, handle: () -> Unit) {
        if (profileId == null) return
        closeHandles[profileId] = handle
    }

    /** Drop the close handle for [profileId] — the desktop tab is gone. */
    fun clearCloseHandle(profileId: String?) {
        if (profileId == null) return
        closeHandles.remove(profileId)
    }

    /** The close handle for [profileId], or null if none registered. */
    fun closeHandle(profileId: String): (() -> Unit)? = closeHandles[profileId]

    // --- User vs. Agent interaction log (for collaborative MCP inspection) ---

    private val userInteractions = ConcurrentHashMap<String, ArrayDeque<DesktopUserInteraction>>()
    private val lastAgentActionMs = ConcurrentHashMap<String, Long>()

    /**
     * Record a user-originated input action on [profileId]. Rapid consecutive
     * `"move"` / `"drag"` samples within 250ms coalesce into the latest position
     * so clicks, scrolls, and keystrokes remain visible in the bounded ring buffer.
     */
    fun recordUserInteraction(
        profileId: String?,
        type: String,
        x: Int? = null,
        y: Int? = null,
        detail: String? = null,
        atMs: Long = System.currentTimeMillis(),
    ) {
        if (profileId.isNullOrBlank()) return
        val deque = userInteractions.getOrPut(profileId) { ArrayDeque() }
        synchronized(deque) {
            val last = deque.lastOrNull()
            if (last != null && last.type == type &&
                (type == "move" || type == "drag") &&
                (atMs - last.timestampMs) in 0L..250L
            ) {
                deque.removeLast()
            }
            deque.addLast(
                DesktopUserInteraction(
                    timestampMs = atMs,
                    type = type,
                    x = x,
                    y = y,
                    detail = detail,
                ),
            )
            while (deque.size > MAX_USER_INTERACTIONS) {
                deque.removeFirst()
            }
        }
    }

    /** Most recent user interactions on [profileId], newest first. */
    fun recentUserInteractions(profileId: String, limit: Int = 10): List<DesktopUserInteraction> {
        val deque = userInteractions[profileId] ?: return emptyList()
        return synchronized(deque) {
            deque.toList().asReversed().take(limit.coerceAtLeast(0))
        }
    }

    /** Record the timestamp of the latest MCP agent action on [profileId]. */
    fun recordAgentAction(profileId: String?, atMs: Long = System.currentTimeMillis()) {
        if (profileId.isNullOrBlank()) return
        lastAgentActionMs[profileId] = atMs
    }

    /** Timestamp of the latest MCP agent action on [profileId], or null if none. */
    fun lastAgentActionAt(profileId: String): Long? = lastAgentActionMs[profileId]

    companion object {
        const val MAX_USER_INTERACTIONS = 16
    }
}
