package sh.haven.app.agent

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.local.WaylandSocketHelper
import sh.haven.core.mcp.McpError
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inbound presence (VISION §1a): the phone's notification shade, brokered
 * to the agent. A [NotificationListenerService] keeps a bounded ring of
 * recent notifications; [NotificationToolProvider] exposes one read verb
 * over MCP.
 *
 * Listener access is a special Android grant (Settings → Notification
 * access), not a runtime permission, so the usual checkSelfPermission path
 * doesn't apply: the tool checks whether the service is actually connected.
 * If not, it attempts the Shizuku privileged equivalent
 * (`cmd notification allow_listener`) — the consent sheet for THIS call is
 * the human gate — and otherwise errors with the Settings path. After a
 * first-time grant the service binds asynchronously, so the first call may
 * legitimately return { connected: true, count: 0 } with a "call again"
 * note rather than data.
 *
 * The ring holds only what arrived while the listener was bound: no
 * history, no persistence, nothing leaves the phone except through the
 * consented tool call. Sensitive fields (actions, large text, extras) are
 * not captured — title and text only.
 */
internal class NotificationMirrorService : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationMirror.connected.set(true)
        // Seed the ring with what's already in the shade, so a fresh grant
        // isn't an empty page until the next notification arrives.
        NotificationMirror.capture(activeNotifications.toList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationMirror.capture(listOf(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationMirror.remove(sbn)
    }

    override fun onDestroy() {
        NotificationMirror.connected.set(false)
        super.onDestroy()
    }
}

/** Process-wide ring of recent notifications, written by the mirror service. */
internal object NotificationMirror {
    private const val RING_SIZE = 300

    val connected = AtomicBoolean(false)

    /** key → record, insertion-ordered oldest→newest (synchronized). */
    private val ring = LinkedHashMap<String, Record>()

    data class Record(
        val key: String,
        val packageName: String,
        val postTimeMs: Long,
        val title: String?,
        val text: String?,
        val ongoing: Boolean,
        val clearable: Boolean,
        val category: String?,
        val removed: Boolean,
    )

    @Synchronized
    fun capture(sbnList: List<StatusBarNotification>) {
        for (sbn in sbnList) {
            ring[sbn.key] = fromSbn(sbn, removed = false)
        }
        trim()
    }

    @Synchronized
    fun remove(sbn: StatusBarNotification) {
        // Keep removals as tombstones (removed=true) rather than deleting:
        // an agent polling notices "dismissed" as a fact, not a gap.
        ring[sbn.key]?.let { ring[sbn.key] = it.copy(removed = true) }
        trim()
    }

    @Synchronized
    fun snapshot(): List<Record> = ring.values.toList()

    @Synchronized
    fun clear() = ring.clear()

    private fun trim() {
        while (ring.size > RING_SIZE) {
            ring.remove(ring.keys.first())
        }
    }

    private fun fromSbn(sbn: StatusBarNotification, removed: Boolean): Record {
        val extras = sbn.notification.extras
        val big = extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        val bigTitle = extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        return Record(
            key = sbn.key,
            packageName = sbn.packageName,
            postTimeMs = sbn.postTime,
            title = bigTitle.toString().takeIf { it.isNotBlank() },
            text = big.toString().takeIf { it.isNotBlank() },
            ongoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            clearable = sbn.isClearable,
            category = sbn.notification.category,
            removed = removed,
        )
    }
}

/**
 * MCP tools over the [NotificationMirrorService] ring. One tool: a filtered
 * read of recent notifications. Sensitive by nature (the shade holds the
 * user's messages and OTP codes), so EVERY_CALL consent — an agent reading
 * the shade is always an explicit, per-call decision.
 */
internal class NotificationToolProvider(
    private val context: Context,
    /**
     * Privileged exec (Shizuku), injected from [McpTools.runShizukuOrThrow]
     * the same way [SensesToolProvider] gets its grant: a null return means
     * Shizuku is unavailable/failed, otherwise the exec result carries the
     * exit code.
     */
    private val shizukuExec: (cmd: String) -> WaylandSocketHelper.ShizukuExecResult?,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "list_notifications" to ToolHandler(
            description = "Read recent Android notifications — the phone's inbound attention: app, title, body text, post time, ongoing/clearable flags. Backed by a notification-listener ring (last 300, memory only, no history across Haven restarts); removed notifications stay visible with `removed: true` so polling reads dismissals as facts. Optional filters: `app` (package-name substring, e.g. \"whatsapp\"), `text` (substring in title or body), `sinceMs`, `limit`. Requires Notification-listener access — a special Android grant, not a runtime permission: if not yet granted, the call attempts it via Shizuku (this consent sheet is the gate) or errors with the Settings path; after a first grant the listener binds asynchronously, so the first call may return {connected:true, count:0} and a re-call is needed.",
            inputSchema = objectSchema {
                string("app", "Only notifications whose package name contains this substring (case-insensitive).")
                string("text", "Only notifications whose title or body contains this substring (case-insensitive).")
                integer("sinceMs", "Only notifications posted at/after this Unix-ms timestamp.")
                integer("limit", "Max notifications returned, newest first (1–200, default 50).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val app = args.optString("app").takeIf { it.isNotBlank() }
                    ?.let { " from apps matching \"$it\"" } ?: ""
                "Read the phone's notifications$app?"
            },
        ) { args -> listNotifications(args) },
    )

    private fun listNotifications(args: JSONObject): JSONObject {
        if (!NotificationMirror.connected.get()) {
            val granted = tryEnableListenerViaShizuku()
            if (granted) {
                return JSONObject().apply {
                    put("connected", false)
                    put("grantedNow", true)
                    put("note", "Notification access was just granted; the listener binds " +
                        "asynchronously. Call again shortly to read the shade.")
                }
            }
            throw McpError(
                -32603,
                "Notification listener is not connected — Haven lacks Notification access. " +
                    "Open Settings → Apps → Haven → Notifications and enable " +
                    "\"Notification access\" (or install Shizuku and grant Haven permission so " +
                    "the tool can enable it for you).",
            )
        }

        val appFilter = args.optString("app").takeIf { it.isNotBlank() }?.lowercase()
        val textFilter = args.optString("text").takeIf { it.isNotBlank() }?.lowercase()
        val sinceMs = args.optLong("sinceMs", 0L)
        val limit = args.optInt("limit", 50).coerceIn(1, 200)

        val records = NotificationMirror.snapshot()
            .asReversed() // newest first
            .filter { appFilter == null || it.packageName.lowercase().contains(appFilter) }
            .filter { r ->
                if (textFilter == null) true
                else r.title?.contains(textFilter, ignoreCase = true) == true ||
                    r.text?.contains(textFilter, ignoreCase = true) == true
            }
            .filter { it.postTimeMs >= sinceMs }
            .take(limit)

        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("packageName", r.packageName)
                appNameOf(r)?.let { put("appName", it) }
                put("postTimeMs", r.postTimeMs)
                r.title?.let { put("title", it) }
                r.text?.let { put("text", it) }
                put("ongoing", r.ongoing)
                put("clearable", r.clearable)
                r.category?.let { put("category", it) }
                put("removed", r.removed)
            })
        }
        return JSONObject().apply {
            put("connected", true)
            put("count", arr.length())
            put("notifications", arr)
            put("note", "Read-only view; dismissing/interacting with notifications is not implemented.")
        }
    }

    private fun appNameOf(r: NotificationMirror.Record): String? = runCatching {
        context.packageManager
            .getApplicationLabel(context.packageManager.getApplicationInfo(r.packageName, 0))
            .toString()
    }.getOrNull()

    /**
     * The privileged grant for listener access: `cmd notification
     * allow_listener <component>`. Returns true when the command exited 0.
     * Wired through the same Shizuku lambda the senses tools use; failures
     * (Shizuku absent, command unsupported on this Android version) just
     * return false so the caller falls back to the Settings-path message.
     */
    private fun tryEnableListenerViaShizuku(): Boolean =
        runCatching {
            shizukuExec("cmd notification allow_listener ${componentName()}")
        }.getOrNull()?.exitCode == 0

    private fun componentName(): String =
        "${context.packageName}/${NotificationMirrorService::class.java.name}"
}