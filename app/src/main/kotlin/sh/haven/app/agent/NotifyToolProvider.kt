package sh.haven.app.agent

import kotlinx.coroutines.flow.first
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.guestnotify.GuestNotifier
import sh.haven.core.local.guestnotify.GuestNotifyBridge
import sh.haven.core.mcp.McpError

/**
 * Guest → Android notification bridge tools (#851, command-first). The bridge
 * itself lives in core/local ([GuestNotifyBridge]); this is the MCP surface:
 * the master opt-in gate, helper staging, and JSON shaping. Mirrors
 * [GpsToolProvider]'s attach/detach pair, minus the permission gate — there is
 * no runtime permission to grant at attach (POST_NOTIFICATIONS is checked at
 * post time and reported back to the guest over the socket as an ack error).
 *
 * The bridge also auto-starts when a local session opens and the preference is
 * enabled ([LocalSessionManager.maybeStartNotifyBridge]); these verbs give the
 * agent explicit control and status on top of that.
 */
internal class NotifyToolProvider(
    private val preferencesRepository: UserPreferencesRepository,
    private val localSessionManager: LocalSessionManager,
    private val notifyBridge: GuestNotifyBridge,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "attach_notify_to_guest" to ToolHandler(
            description = "Let Linux guest programs post Android notifications: binds the abstract-namespace socket \\0haven-notify and stages the `haven-notify` helper into the guest. A guest command `haven-notify \"Title\" \"Body\"` (needs socat) then posts a notification on the dedicated '${GuestNotifier.CHANNEL_ID}' channel and prints a one-line JSON ack ({\"ok\":true,\"id\":N} or {\"ok\":false,\"error\":...}) so a failed delivery is visible. Works in a bare PRoot terminal — no D-Bus or desktop notification daemon. The socket is abstract-namespace, reachable only by processes sharing Haven's network namespace (the guest), not co-resident apps. A token-bucket rate limiter (~1/s, burst 5) caps spam; notifications carry no tap intent (guest-supplied content is not given a launch intent). Requires the master opt-in first: Settings → \"Guest notifications\" (or the notify_guest_exposure_enabled preference) — once enabled, any guest process can inject content into the notification shade, so the switch is deliberate. The bridge also auto-starts on local session start when enabled; this verb is idempotent and returns bridge status. POST_NOTIFICATIONS must be granted to Haven or posts fail with an ack error.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Let Linux guest programs post Android notifications via haven-notify?" },
        ) { _ -> attach() },

        "detach_notify_from_guest" to ToolHandler(
            description = "Stop the guest → Android notification bridge started by attach_notify_to_guest: the \\0haven-notify socket closes and `haven-notify` in the guest fails to connect until re-attached (or until the next local session start with the preference enabled). Reports the posted/rate-limited/error counters. Teardown counterpart to attach_notify_to_guest.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> detach() },
    )

    private suspend fun attach(): JSONObject {
        // Master opt-in gate (Settings → "Guest notifications"). Off by default:
        // once enabled, any guest process can inject content into the shade.
        if (!preferencesRepository.notifyGuestExposureEnabled.first()) {
            throw McpError(
                -32603,
                "Guest notifications are disabled. Enable Settings → \"Guest notifications\" " +
                    "(or set the notify_guest_exposure_enabled preference) first.",
            )
        }
        val result = notifyBridge.start()
        // Re-stage on every attach so an app update refreshes the helper
        // (the stageHavenGpsArtifacts idiom). null when there is no active
        // proot rootfs — the bridge still runs; nothing is staged.
        val helperPath = localSessionManager.prootManager.stageHavenNotifyArtifacts()
        result.put("helperPath", helperPath ?: JSONObject.NULL)
        if (helperPath != null) {
            result.put("helperCommand", "haven-notify \"Title\" \"Body\"")
            result.put("verifyCommand", "haven-notify \"Haven\" \"notification bridge test\"")
            result.put(
                "note",
                "Bridge live on abstract socket \\0${GuestNotifyBridge.SOCKET_NAME}. Run " +
                    "`haven-notify \"Title\" \"Body\"` via run_in_proot (needs socat in the guest) to post " +
                    "an Android notification on the '${GuestNotifier.CHANNEL_ID}' channel; the helper prints " +
                    "a JSON ack — {\"ok\":false,\"error\":\"rate limited\"} past ~1/s (burst 5), or a " +
                    "POST_NOTIFICATIONS error if notifications are disabled for Haven.",
            )
        }
        return result
    }

    private fun detach(): JSONObject = notifyBridge.stop()
}
