package sh.haven.app.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.ssh.SshSessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generic out-of-turn terminal input queue (#161). The MCP
 * `queue_terminal_input` tool lets an agent inject text into any
 * connected SSH session — by watching the session's stdout for a
 * configurable prompt pattern and typing the queued text plus a
 * configurable submit key when the pattern appears.
 *
 * Motivating use cases (peer examples — neither is privileged):
 *
 * - **Drive an interactive install / script.** A `dpkg --configure`
 *   or `apt install` prompts `Continue? [y/N]`; agent queues `y`
 *   against that session id with pattern `\[y/N\]\s*\$`. When the
 *   next prompt asks for a path, the agent queues `/usr/local`
 *   against the same session with a different pattern.
 *
 * - **Drive a database / REPL.** A `psql` or `python3` shell is
 *   running in a session; agent queues SQL or Python snippets
 *   matched against `=\#\s*\$` or `>>>\s*\$` respectively.
 *
 * - **Inject input into the agent's own REPL session.** When the
 *   SSH session carrying the MCP reverse tunnel is the one running
 *   the agent's REPL (Claude Code or equivalent), queued text
 *   submits as the user's next input — useful for slash commands
 *   like `/mcp reconnect haven` that only fire from user input.
 *
 * Safety: pre-delivery consent fires on the phone before any typing
 * happens, with an "Allow for N min" option so a power-user
 * collaborative window doesn't re-prompt per queue. The session-wide
 * "Allow all from <client>" bypass that AgentConsentManager already
 * tracks covers this too.
 *
 * Polling-based rather than callback-based — simpler, and the tail
 * of the per-session scrollback ring is cheap to read every 200 ms.
 */
@Singleton
class TerminalInputQueue @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val consentManager: AgentConsentManager,
) {
    private data class QueuedInput(
        val id: String,
        val sessionId: String,
        val text: String,
        val promptRegex: Regex,
        val deadline: Long,
        /**
         * Monotonic total-bytes-appended on the scrollback ring at
         * enqueue time. The watcher only fires once this value has
         * grown — guards against firing on a prompt that was already
         * on screen at enqueue time. Uses the ring's lifetime counter
         * rather than its snapshot size so it stays usable after the
         * ring fills to its capacity cap.
         */
        val baselineTotalBytes: Long,
        /**
         * Submit key sent after the queued text. TUIs in raw mode
         * (Claude Code, most readline-style REPLs) treat `\r` (CR) as
         * Enter; some line-buffered programs prefer `\n` (LF); pass
         * an empty string to type text without submitting (e.g. into
         * a search box). Default `\r`.
         */
        val submitKey: String,
        val clientHint: String?,
    )

    private val queue = ConcurrentHashMap<String, QueuedInput>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollLock = Any()
    @Volatile
    private var pollJob: Job? = null

    private fun ensurePolling() {
        synchronized(pollLock) {
            if (queue.isEmpty() || pollJob?.isActive == true) return
            Log.i(TAG, "TerminalInputQueue starting poll loop (interval=${POLL_INTERVAL_MS}ms)")
            pollJob = scope.launch {
                try {
                    pollLoop()
                } catch (e: Throwable) {
                    Log.e(TAG, "poll loop died", e)
                }
            }
        }
    }

    /**
     * Schedule [text] to be typed into [sessionId]'s SSH terminal when
     * the next output matching [promptPattern] appears at the tail of
     * the scrollback. Returns a queue id the caller can use to
     * [cancel] before delivery.
     *
     * Captures the ring's monotonic byte counter at enqueue time as
     * the baseline — the watcher only fires when new bytes have been
     * written since, so a prompt that was already on screen at the
     * moment the agent enqueued won't trigger an immediate fire.
     */
    fun enqueue(
        sessionId: String,
        text: String,
        promptPattern: String,
        timeoutSeconds: Int,
        submitKey: String = "\r",
        clientHint: String? = null,
    ): String {
        val baseline = sshSessionManager.agentScrollbackTotalBytes(sessionId) ?: 0L
        val id = UUID.randomUUID().toString()
        queue[id] = QueuedInput(
            id = id,
            sessionId = sessionId,
            text = text,
            promptRegex = Regex(promptPattern, RegexOption.MULTILINE),
            deadline = System.currentTimeMillis() + timeoutSeconds.coerceAtLeast(1) * 1000L,
            baselineTotalBytes = baseline,
            submitKey = submitKey,
            clientHint = clientHint,
        )
        // Length, not content: what gets queued here is typed into a shell, and a
        // password sent this way would otherwise sit in logcat verbatim (#518).
        Log.i(TAG, "enqueue $id: sessionId=$sessionId textLen=${text.length} pattern=$promptPattern timeout=${timeoutSeconds}s baselineTotalBytes=$baseline submitKey=${submitKey.toByteArray().joinToString { "0x%02x".format(it) }}")
        ensurePolling()
        return id
    }

    fun cancel(id: String): Boolean = queue.remove(id) != null

    fun pending(): List<String> = queue.keys.toList()

    private suspend fun pollLoop() {
        var lastTickLog = 0L
        while (true) {
            synchronized(pollLock) {
                if (queue.isEmpty()) {
                    pollJob = null
                    return
                }
            }
            delay(POLL_INTERVAL_MS)
            val now = System.currentTimeMillis()
            for ((id, msg) in queue.toMap()) {
                if (now > msg.deadline) {
                    queue.remove(id)
                    Log.i(TAG, "queue $id timed out without prompt match (pattern=${msg.promptRegex.pattern})")
                    continue
                }
                val currentTotal = sshSessionManager.agentScrollbackTotalBytes(msg.sessionId)
                if (currentTotal == null) {
                    if (now - lastTickLog > LOG_TICK_INTERVAL_MS) {
                        Log.i(TAG, "queue $id: no scrollback ring for session ${msg.sessionId}")
                        lastTickLog = now
                    }
                    continue
                }
                if (currentTotal <= msg.baselineTotalBytes) {
                    if (now - lastTickLog > LOG_TICK_INTERVAL_MS) {
                        Log.i(TAG, "queue $id: currentTotal=$currentTotal <= baseline=${msg.baselineTotalBytes}; waiting for new bytes")
                        lastTickLog = now
                    }
                    continue
                }
                val scrollback = sshSessionManager.readAgentScrollback(msg.sessionId, MAX_TAIL_BYTES) ?: continue
                val stripped = scrollback.decodeToString().let { stripAnsi(it) }
                // Look only at the last few lines — false positives from
                // earlier scrollback (`>` redirects in commands, code
                // snippets containing prompts) shouldn't ever fire.
                val tail = stripped.takeLast(TAIL_MATCH_CHARS)
                val matched = msg.promptRegex.containsMatchIn(tail)
                if (now - lastTickLog > LOG_TICK_INTERVAL_MS) {
                    val visible = tail.takeLast(200).map { c ->
                        when {
                            c == '\n' -> "\\n"
                            c == '\r' -> "\\r"
                            c == '\t' -> "\\t"
                            c.code < 0x20 -> "[%02x]".format(c.code)
                            else -> c.toString()
                        }
                    }.joinToString("")
                    Log.i(TAG, "queue $id tick: matched=$matched currentTotal=$currentTotal baseline=${msg.baselineTotalBytes} pattern=${msg.promptRegex.pattern} tail.last200=«$visible»")
                    lastTickLog = now
                }
                if (matched) {
                    // Pre-delivery consent — the typing target is also
                    // potentially the user's input field. Without this
                    // gate we'd risk garbling whatever the user is
                    // mid-typing. The consent sheet offers an extra
                    // "Allow for N min" action so a power user iterating
                    // with an agent can authorise the agent to drive
                    // the REPL for a short collaborative window without
                    // the prompt re-firing every queue. (#161)
                    if (queue.remove(id) != null) {
                        val display = if (msg.text.length > 80) msg.text.take(77) + "…" else msg.text
                        val decision = try {
                            consentManager.requestConsent(
                                toolName = DELIVERY_CONSENT_TOOL_NAME,
                                clientHint = msg.clientHint,
                                summary = "Type into the SSH session «${msg.sessionId.take(8)}…»:\n\n$display",
                                level = ConsentLevel.EVERY_CALL,
                                offerTimedAllow = true,
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "queue $id consent prompt failed: ${e.message}")
                            ConsentDecision.DENY
                        }
                        if (decision != ConsentDecision.ALLOW) {
                            Log.d(TAG, "queue $id denied at pre-delivery consent")
                            continue
                        }
                        try {
                            sshSessionManager.sendInput(msg.sessionId, msg.text + msg.submitKey)
                            Log.i(TAG, "queue $id delivered to ${msg.sessionId} (${msg.text.length} chars + ${msg.submitKey.length}-byte submit)")
                        } catch (e: Exception) {
                            Log.w(TAG, "queue $id delivery failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun stripAnsi(s: String): String = ANSI_REGEX.replace(s, "")

    companion object {
        private const val TAG = "TerminalInputQueue"
        private const val POLL_INTERVAL_MS = 200L
        private const val MAX_TAIL_BYTES = 4096
        // Throttle the per-tick diagnostic log so a 60s polling cycle
        // produces ~30 log lines per queued message, not 300.
        private const val LOG_TICK_INTERVAL_MS = 2_000L
        private const val TAIL_MATCH_CHARS = 512
        // Synthetic tool name used for the pre-delivery consent prompt.
        // Distinct from `queue_terminal_input` (the enqueue-time tool)
        // so the AgentConsentManager memoisation / timed-allow keys
        // don't collide.
        const val DELIVERY_CONSENT_TOOL_NAME = "queue_terminal_input:deliver"
        private val ANSI_REGEX = Regex("\\[[0-9;?]*[a-zA-Z]")
    }
}
