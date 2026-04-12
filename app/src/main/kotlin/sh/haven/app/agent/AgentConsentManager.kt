package sh.haven.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How aggressively a tool gates a call on user consent.
 *
 * - [NEVER]              — read-only or otherwise harmless; no prompt.
 * - [ONCE_PER_SESSION]   — prompt once per (client, tool) pair, then
 *                          remember the decision until either the
 *                          session is cleared or the app is killed.
 * - [EVERY_CALL]         — prompt on every call. For the most
 *                          destructive operations.
 */
enum class ConsentLevel { NEVER, ONCE_PER_SESSION, EVERY_CALL }

/** Outcome the requesting RPC should act on. */
enum class ConsentDecision { ALLOW, DENY }

/**
 * One pending consent request the UI is expected to render and resolve.
 * `id` lets the UI route a tap on "Allow" / "Deny" back to the right
 * waiting RPC even when several pile up.
 */
data class ConsentRequest(
    val id: Long,
    val toolName: String,
    val clientHint: String?,
    /** Short human summary of what the tool will do, for the prompt body. */
    val summary: String,
    val requestedAt: Long = System.currentTimeMillis(),
)

/**
 * Coordinates "agent wants to do something destructive — does the user
 * consent?" interactions. Designed but not yet wired: every tool in
 * MCP v1 is read-only, so there are no callers today. The shape needs
 * to exist before the first write tool lands so we don't have to
 * retrofit it under pressure.
 *
 * ### Failure model
 *
 * The brand promise (VISION.md §85) is that the user always keeps the
 * wheel — *never* a silent automation channel. That has one
 * unforgiving consequence: if no Haven activity is in the foreground,
 * we cannot ask the user to consent, so the request **must** fail
 * closed. This manager tracks foreground state via
 * [setForegroundActive] and refuses non-NEVER requests when no
 * activity is visible. The user gets a notification (wired by the UI
 * layer when this becomes load-bearing) explaining what was blocked.
 *
 * ### Memoisation
 *
 * For [ConsentLevel.ONCE_PER_SESSION] decisions are cached against
 * the `(clientHint, toolName)` key for the lifetime of the manager
 * (i.e. until process death) or until [clearMemoised] is called from
 * Settings. The cache only stores ALLOW outcomes; a DENY is never
 * remembered, so a misclick can't lock the agent out forever.
 */
@Singleton
class AgentConsentManager @Inject constructor() {

    private val nextId = AtomicLong(1)
    private val mutex = Mutex()
    private val pendingDeferreds = mutableMapOf<Long, CompletableDeferred<ConsentDecision>>()
    private val sessionAllowed = mutableSetOf<String>()

    @Volatile
    private var foregroundActive: Boolean = false

    private val _pending = MutableStateFlow<List<ConsentRequest>>(emptyList())
    /** All currently-waiting requests, oldest first. Drives the bottom sheet. */
    val pending: StateFlow<List<ConsentRequest>> = _pending.asStateFlow()

    /**
     * Activity layer reports its visibility through this so we can
     * fail-closed when no one can answer the prompt.
     */
    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
    }

    /**
     * Suspend until the user resolves the consent prompt for [toolName],
     * or [timeoutMs] elapses. Returns DENY on timeout, on a backgrounded
     * app, or on an explicit deny tap. Returns ALLOW if memoised at
     * [ConsentLevel.ONCE_PER_SESSION] or if the user taps allow.
     *
     * [level] of [ConsentLevel.NEVER] always returns ALLOW immediately
     * without touching the queue — the parameter exists so callers can
     * thread the level uniformly through the dispatcher.
     */
    suspend fun requestConsent(
        toolName: String,
        clientHint: String?,
        summary: String,
        level: ConsentLevel,
        timeoutMs: Long = 60_000,
    ): ConsentDecision {
        if (level == ConsentLevel.NEVER) return ConsentDecision.ALLOW

        val memoKey = memoKey(clientHint, toolName)
        if (level == ConsentLevel.ONCE_PER_SESSION) {
            mutex.withLock {
                if (memoKey in sessionAllowed) return ConsentDecision.ALLOW
            }
        }

        if (!foregroundActive) {
            // Fail closed: nothing can render the prompt right now,
            // and the §85 rule forbids letting the call proceed
            // anyway. Caller will translate this into the audit log
            // as Outcome.DENIED.
            return ConsentDecision.DENY
        }

        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ConsentDecision>()
        val request = ConsentRequest(id = id, toolName = toolName, clientHint = clientHint, summary = summary)

        mutex.withLock {
            pendingDeferreds[id] = deferred
            _pending.value = _pending.value + request
        }

        val decision = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ConsentDecision.DENY

        mutex.withLock {
            pendingDeferreds.remove(id)
            _pending.value = _pending.value.filterNot { it.id == id }
            if (decision == ConsentDecision.ALLOW && level == ConsentLevel.ONCE_PER_SESSION) {
                sessionAllowed.add(memoKey)
            }
        }
        return decision
    }

    /** Called by the bottom-sheet UI when the user taps allow/deny. */
    suspend fun respond(requestId: Long, decision: ConsentDecision) {
        mutex.withLock {
            pendingDeferreds[requestId]?.complete(decision)
        }
    }

    /** Used by Settings → "Forget remembered allows". */
    suspend fun clearMemoised() {
        mutex.withLock { sessionAllowed.clear() }
    }

    private fun memoKey(clientHint: String?, toolName: String): String =
        "${clientHint ?: "unknown"}::$toolName"
}
