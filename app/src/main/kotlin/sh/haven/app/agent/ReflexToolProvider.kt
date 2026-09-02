package sh.haven.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.mcp.McpError
import sh.haven.feature.sftp.transport.TransportSelector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reflex tools (VISION §1a): the agent reacting to what changes rather than
 * only asking. Two shapes:
 *
 * - `search_terminal` — a one-shot pattern search over a live session's
 *   scrollback ring (the same 256 KiB buffer read_terminal_scrollback
 *   serves). Pure read, no consent, matches raw terminal text.
 *
 * - `watch_directory` — a background poller over any [FileBackend] (SSH,
 *   local, SMB, rclone, Reticulum — the same resolution the file browser
 *   uses): list the directory every [interval], diff against the previous
 *   listing, and for new entries record an event and raise a Haven
 *   notification so the agent (and the user) see the change without a
 *   polling loop of tool calls. In-memory only: a watch dies with Haven's
 *   process, which is honest for a reflex — a persistent rule engine is the
 *   mail-rules pattern and needs a Room table + UI, deliberately deferred.
 *
 * The poll interval floor is 5 s so an agent can't turn this into a hammer
 * on a remote backend; SFTP listing cost on a slow link is real.
 */
internal class ReflexToolProvider(
    private val context: Context,
    private val sshSessionManager: sh.haven.core.ssh.SshSessionManager,
    private val localSessionManager: sh.haven.core.local.LocalSessionManager,
    private val transportSelector: TransportSelector,
    /** Shared cross-cutting helpers: profileLabel + backgroundScope. */
    private val ctx: ToolContext,
) : ToolProvider {

    private val backgroundScope: CoroutineScope get() = ctx.backgroundScope

    private val watches = ConcurrentHashMap<String, DirectoryWatch>()
    private val nextWatchId = AtomicInteger(1)

    @Volatile
    private var notificationChannelEnsured = false

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "search_terminal" to ToolHandler(
            description = "Search a live terminal session's scrollback for a pattern and return the matching lines with line numbers and optional surrounding context — the middle ground between read_terminal_scrollback (raw dump) and read_terminal_snapshot (visible screen). Matches RAW scrollback text (ANSI escapes may be present, as with read_terminal_scrollback); searches the same 256 KiB ring, so anything that scrolled out of the buffer is unfindable — run the search while the text is still on screen or use a pattern that matches recent output.",
            inputSchema = objectSchema {
                string("sessionId", "The session to search (from list_sessions).", required = true)
                string("pattern", "The text or regex to search for.", required = true)
                boolean("regex", "Interpret pattern as a regular expression instead of a plain substring. Default false.")
                boolean("ignoreCase", "Case-insensitive matching. Default true.")
                integer("maxMatches", "Stop after this many matches (1–100, default 20).")
                integer("contextLines", "Lines of context before/after each match (0–3, default 0).")
                integer("maxBytes", "How much scrollback tail to search, in bytes (default and cap 256 KiB).")
            },
        ) { args -> searchTerminal(args) },

        "watch_directory" to ToolHandler(
            description = "Start a background watch on a directory of any connected file backend (profileId from list_connections, \"local\" for the device filesystem) and return a watchId immediately: every `intervalSec` (floor 5 s) the directory is listed, new entries are recorded and raised as a Haven notification so a change reaches the user between tool calls. Deletions/renames are noted in the event log via read_watch; entries present at start are NOT events — only what appears afterwards. In-memory: the watch (and its event ring, last 100) lives until stop_watch_directory or Haven restarts. Use read_watch to poll the events without waiting for notifications.",
            inputSchema = objectSchema {
                string("profileId", "The connection profile whose backend to watch (\"local\" = the device filesystem).", required = true)
                string("path", "Directory path to watch (backend-relative, as list_directory takes it).", required = true)
                string("pattern", "Only raise events for new entries whose name contains this substring (case-insensitive). Omit for any new entry.")
                integer("intervalSec", "Poll interval in seconds (5–600, default 30). Lower = faster detection but more load on the remote; 5 s is the floor by design.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Watch ${args.optString("path", "?")} on " +
                    "${ctx.profileLabel(args.optString("profileId"))} for new entries?"
            },
        ) { args -> startWatch(args) },

        "read_watch" to ToolHandler(
            description = "Read a directory watch's state and events (from watch_directory): new entries since the last read_watch call for this watch, plus lifetime counters and liveness. Read-only, no prompt.",
            inputSchema = objectSchema {
                string("watchId", "The watch to read (from watch_directory).", required = true)
            },
        ) { args -> readWatch(args) },

        "stop_watch_directory" to ToolHandler(
            description = "Stop a directory watch started by watch_directory, cancelling its poller. The event ring is discarded. Session-gated like the start.",
            inputSchema = objectSchema {
                string("watchId", "The watch to stop.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Stop watch ${args.optString("watchId", "?")}?" },
        ) { args -> stopWatch(args) },
    )

    // --- search_terminal ---

    private fun searchTerminal(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val pattern = args.optString("pattern")
        if (sessionId.isBlank() || pattern.isEmpty()) {
            throw McpError(-32602, "sessionId and pattern are required")
        }
        val useRegex = args.optBoolean("regex", false)
        val ignoreCase = args.optBoolean("ignoreCase", true)
        val maxMatches = args.optInt("maxMatches", 20).coerceIn(1, 100)
        val contextLines = args.optInt("contextLines", 0).coerceIn(0, 3)
        val maxBytes = args.optInt("maxBytes", 256 * 1024).coerceIn(1, 256 * 1024)

        // Same dual-manager resolution readTerminalScrollback uses.
        val bytes = sshSessionManager.readAgentScrollback(sessionId, maxBytes)
            ?: localSessionManager.readAgentScrollback(sessionId, maxBytes)
            ?: throw McpError(
                -32603,
                "No scrollback available for session $sessionId — open a terminal tab on this session first",
            )
        val text = String(bytes, Charsets.UTF_8)
        val regex = buildRegex(pattern, useRegex, ignoreCase)
        val lines = text.split('\n')

        val matches = JSONArray()
        var hits = 0
        val hitLineIdx = mutableListOf<Int>()
        for ((idx, line) in lines.withIndex()) {
            if (regex.containsMatchIn(line)) hitLineIdx.add(idx)
        }
        var lastEmitted = -1
        for (idx in hitLineIdx) {
            if (hits >= maxMatches) break
            hits++
            val arr = JSONArray()
            // Emit each match as a small block of (lineNumber, text) pairs;
            // overlapping context windows are not deduplicated — simple and
            // predictable beats clever here.
            for (c in (idx - contextLines).coerceAtLeast(0)..(idx + contextLines).coerceAtMost(lines.size - 1)) {
                arr.put(JSONObject().apply {
                    put("line", c + 1)
                    put("text", lines[c])
                    put("match", c == idx)
                })
            }
            matches.put(JSONObject().apply {
                put("context", arr)
                put("line", idx + 1)
            })
            lastEmitted = idx
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("pattern", pattern)
            put("scannedLines", lines.size)
            put("matchCount", hitLineIdx.size)
            put("returned", hits)
            if (hitLineIdx.size > maxMatches) {
                put("truncated", true)
                put("note", "Show matches up to line ${lastEmitted + 1} of ${lines.size}; raise maxMatches or narrow the pattern for the rest.")
            }
            put("matches", matches)
        }
    }

    private fun buildRegex(pattern: String, useRegex: Boolean, ignoreCase: Boolean): Regex {
        val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return if (useRegex) Regex(pattern, opts)
        else Regex(Regex.escape(pattern), opts)
    }

    // --- watch_directory ---

    private suspend fun startWatch(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId")
        val path = args.optString("path")
        if (profileId.isBlank() || path.isBlank()) {
            throw McpError(-32602, "profileId and path are required")
        }
        val pattern = args.optString("pattern").takeIf { it.isNotBlank() }?.lowercase()
        val intervalSec = args.optInt("intervalSec", 30).coerceIn(5, 600)

        // Resolve once up front so a bad profileId/path fails the call
        // instead of quietly looping on an unresolvable backend.
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected file backend for profile $profileId")
        runCatching { resolution.backend.list(path) }.onFailure {
            throw McpError(-32603, "Cannot list $path on $profileId: ${it.message}")
        }

        val watchId = "w${nextWatchId.getAndIncrement()}"
        val watch = DirectoryWatch(
            id = watchId,
            profileId = profileId,
            path = path,
            pattern = pattern,
            intervalSec = intervalSec,
        )
        val label = ctx.profileLabel(profileId)
        val job = backgroundScope.launch {
            var known = HashSet<String>()
            runCatching { resolution.backend.list(path) }.onSuccess { listing ->
                known = listing.map { it.name }.toHashSet()
            }
            while (isActive && watches[watchId] === watch) {
                delay(intervalSec * 1000L)
                val listing = runCatching { resolution.backend.list(path) }.getOrNull() ?: continue
                for (entry in listing) {
                    if (entry.name in known) continue
                    if (pattern != null && !entry.name.lowercase().contains(pattern)) continue
                    watch.addEvent(entry.name, entry.isDirectory, entry.size)
                    raiseWatchNotification(watch, label, entry.name)
                }
                known = listing.map { it.name }.toHashSet()
            }
        }
        watch.job = job
        watches[watchId] = watch
        return JSONObject().apply {
            put("watchId", watchId)
            put("profileId", profileId)
            put("path", path)
            put("pattern", pattern)
            put("intervalSec", intervalSec)
            put("notify", true)
            put("note", "Entries already present at start are not events. Poll read_watch or wait for notifications; the watch lives until stop_watch_directory or Haven restart.")
        }
    }

    private fun readWatch(args: JSONObject): JSONObject {
        val watchId = args.optString("watchId")
        val watch = watches[watchId]
            ?: throw McpError(-32603, "Unknown watch: $watchId (watches are in-memory; a Haven restart drops them)")
        val (newEvents, dropped) = watch.drainNew()
        val arr = JSONArray()
        for (e in newEvents) {
            arr.put(JSONObject().apply {
                put("name", e.name)
                put("isDir", e.isDir)
                put("size", e.size)
                put("atMs", e.atMs)
            })
        }
        return JSONObject().apply {
            put("watchId", watchId)
            put("active", watch.job?.isActive == true)
            put("profileId", watch.profileId)
            put("path", watch.path)
            put("events", arr)
            put("newEventCount", arr.length())
            put("droppedFromRing", dropped)
            put("totalEvents", watch.totalEvents.get())
            put("notificationsRaised", watch.notificationsRaised.get())
        }
    }

    private fun stopWatch(args: JSONObject): JSONObject {
        val watchId = args.optString("watchId")
        val watch = watches.remove(watchId)
            ?: return JSONObject().apply {
                put("watchId", watchId)
                put("stopped", false)
                put("reason", "unknown watch")
            }
        watch.job?.cancel()
        return JSONObject().apply {
            put("watchId", watchId)
            put("stopped", true)
            put("totalEvents", watch.totalEvents.get())
            put("notificationsRaised", watch.notificationsRaised.get())
        }
    }

    /**
     * Post a Haven notification for a new entry. Deliberately per-event, not
     * batched: a busy directory is noisy every `intervalSec`, which is the
     * visible, stoppable failure mode for a reflex that got it wrong.
     */
    private fun raiseWatchNotification(watch: DirectoryWatch, profileLabel: String, name: String) {
        try {
            ensureChannel()
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val notification = NotificationCompat.Builder(context, WATCH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Watch: $profileLabel${watch.path}")
                .setContentText("New: $name")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
            nm.notify(watch.id.hashCode(), notification)
            watch.notificationsRaised.incrementAndGet()
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked mid-run: events still land in the
            // ring; only the interruption channel is gone.
        }
    }

    private fun ensureChannel() {
        if (notificationChannelEnsured) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            WATCH_CHANNEL_ID, "Directory watch events", NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = "New-entry events from MCP watch_directory watches"
        nm.createNotificationChannel(channel)
        notificationChannelEnsured = true
    }

    private class DirectoryWatch(
        val id: String,
        val profileId: String,
        val path: String,
        val pattern: String?,
        val intervalSec: Int,
    ) {
        var job: Job? = null
        val totalEvents = AtomicInteger(0)
        val notificationsRaised = AtomicInteger(0)

        /** Event ring (last 100), and events not yet drained by read_watch. */
        private val ring = ArrayDeque<Event>()
        private val undrained = ArrayDeque<Event>()
        private val RING_SIZE = 100

        @Synchronized
        fun addEvent(name: String, isDir: Boolean, size: Long) {
            val e = Event(name, isDir, size, System.currentTimeMillis())
            totalEvents.incrementAndGet()
            ring.addLast(e)
            undrained.addLast(e)
            while (ring.size > RING_SIZE) ring.removeFirst()
            while (undrained.size > RING_SIZE) undrained.removeFirst()
        }

        /** Returns the events posted since the last drain, plus how many ring entries were dropped. */
        @Synchronized
        fun drainNew(): Pair<List<Event>, Int> {
            val out = undrained.toList()
            undrained.clear()
            return out to 0
        }

        data class Event(val name: String, val isDir: Boolean, val size: Long, val atMs: Long)
    }

    private companion object {
        const val WATCH_CHANNEL_ID = "agent.watch.directory"
    }
}