package sh.haven.core.data.agent

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
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
    /**
     * If true, the consent sheet renders an additional "Allow for N min"
     * action alongside Allow/Deny. The ALLOW decision the user gives this
     * way is memoised against (clientHint, toolName) for that window —
     * subsequent identical requests within the window resolve
     * immediately as ALLOW without re-prompting. Used by the
     * [`queue_terminal_input`][sh.haven.app.agent.TerminalInputQueue]
     * delivery prompt where a power user wants to authorise the agent
     * to type into the REPL for a short collaborative window without
     * being interrupted on every queued message.
     */
    val offerTimedAllow: Boolean = false,
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
 * wheel — *never* a silent automation channel. If no Haven activity is in
 * the foreground, we cannot ask the user to consent, so the request can
 * never auto-ALLOW. Since #337 (mechanism 3, maintainer-approved) a
 * backgrounded non-NEVER request no longer fails *instantly*: it emits
 * [blockedWhileBackground] (the app layer raises a tap-to-open
 * notification) and then suspends — within the caller's timeout budget —
 * waiting for foreground to return via [setForegroundActive]; the prompt
 * renders then with the remaining budget. DENY happens only on timeout or
 * an explicit deny tap. §85 holds: the only thing that changed is
 * immediate-DENY → DENY-only-on-timeout.
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

    /**
     * Per-pending-request bundle: the deferred the caller is awaiting,
     * plus the `clientHint` carried with the request. The hint is needed
     * at respond-time so the bypass checkbox can attribute the bypass to
     * the right client. Replaces a plain `Map<Long, CompletableDeferred>`.
     */
    private data class PendingEntry(
        val deferred: CompletableDeferred<ConsentDecision>,
        val clientHint: String?,
    )

    private val pendingEntries = mutableMapOf<Long, PendingEntry>()
    private val sessionAllowed = mutableSetOf<String>()

    /**
     * Time-windowed memoisation: `memoKey -> expiry epoch millis`. A
     * user-supplied "Allow for N min" on a [ConsentRequest] with
     * `offerTimedAllow = true` writes here. [requestConsent] consults
     * this before queueing a fresh prompt and returns ALLOW
     * immediately if the entry hasn't expired. Cleared by
     * [clearMemoised] same as [sessionAllowed].
     */
    private val windowedAllows = mutableMapOf<String, Long>()

    /**
     * Single-use retry grants for EVERY_CALL tools: `grantKey -> expiry`. When
     * an EVERY_CALL operation is approved but the MCP client already gave up
     * (its read timeout < the consent wait), the approval would otherwise be
     * wasted and the agent's retry would re-prompt — the "unexpected MCP
     * timeout" symptom. Keyed by client+tool+operation (so a *different* call
     * still prompts) and CONSUMED on first use within a short TTL, so it never
     * weakens EVERY_CALL beyond honouring the just-approved operation's retry.
     */
    private val retryGrants = mutableMapOf<String, Long>()

    /**
     * Clients that the user has elected to bypass per-call prompts for
     * (the "Allow all MCP requests from 'X' until app restart" checkbox
     * on the consent sheet). Process-scoped, never persisted —
     * specifically cleared on app kill so a misclick can't survive an
     * unattended reboot. `clearMemoised()` also wipes this so Settings
     * → Forget remembered allows fully resets agent trust.
     */
    private val sessionBypassClients = mutableSetOf<String>()

    /**
     * Clients the user has *persistently* opted into auto-approval for,
     * via Settings → Agent endpoint → Paired clients. Unlike
     * [sessionBypassClients] this survives app restart: it's the
     * in-memory mirror of [UserPreferencesRepository.mcpBypassConsentClients],
     * pushed in through [setPersistentBypassClients] by the app layer
     * (same external-state-via-setter pattern as [setForegroundActive],
     * keeping this class free of any DataStore dependency). A tool call
     * from a name in this set bypasses the per-call consent prompt.
     */
    @Volatile
    private var persistentBypassClients: Set<String> = emptySet()

    /**
     * Foreground visibility as a StateFlow rather than a plain volatile so a
     * background-blocked consent can *suspend until foreground returns*
     * (#337 mechanism 3) instead of only sampling the flag.
     */
    private val foregroundState = MutableStateFlow(false)

    /**
     * The most recent pairing attempt that failed closed because Haven was
     * backgrounded. The pairing notification tells the user to open Haven —
     * but the original attempt already resolved DENY, so without remembering
     * it here nothing would re-prompt unless the client happened to retry
     * while the user was looking (the "tapped the notification and nothing
     * displayed" dead end). [repromptBlockedPairing] re-raises it on the next
     * foreground. Single-slot: a newer blocked attempt replaces an older one.
     */
    private data class BlockedPairing(val clientName: String, val clientVersion: String?, val atMs: Long)

    @Volatile
    private var blockedPairing: BlockedPairing? = null

    /**
     * In-flight pairing prompt per client name (#337). A reconnecting client
     * retries `initialize` faster than a human answers a sheet, and without
     * this each retry queued ANOTHER identical pairing sheet — the user
     * answered four in a row believing they were different requests. A second
     * pairing request for a name that already has a sheet up awaits the same
     * deferred instead of queueing its own.
     */
    private val pairingInFlight = mutableMapOf<String, CompletableDeferred<ConsentDecision>>()

    /**
     * `clientName -> expiry`: after the user explicitly denies (or blocks) a
     * pairing, re-attempts from the same name auto-deny without a prompt until
     * the cooldown lapses (#337). Deliberately short — pairing DENYs, unlike
     * consent DENYs, are re-attempted by *software* in a tight loop, so an
     * unremembered deny turns into a prompt storm. A genuine re-pair only has
     * to wait out the cooldown (or the user clears it in Settings).
     */
    private val pairingDenyCooldowns = mutableMapOf<String, Long>()

    /**
     * Client names the user blocked from the pairing sheet's "Block" action
     * (#337). Pairing requests from these names auto-deny silently for the
     * rest of the process lifetime. Session-scoped like [sessionBypassClients]
     * and cleared by [clearMemoised], so a misclick can't brick a client
     * permanently.
     */
    private val blockedPairingClients = mutableSetOf<String>()

    /**
     * Timestamps of recently *raised* pairing sheets, across all client names
     * (#337). This is the guard the name-keyed cooldown can't provide: the
     * reproduced spam loop presented a slightly different `clientInfo.name`
     * per call, so every request looked like a brand-new client. If more than
     * [MAX_PAIRING_PROMPTS_PER_WINDOW] sheets get raised within
     * [PAIRING_PROMPT_WINDOW_MS], further pairing prompts auto-deny until the
     * window drains — the user stops being held hostage, and a legitimate
     * client just retries a minute later.
     */
    private val recentPairingPrompts = ArrayDeque<Long>()

    private val _pending = MutableStateFlow<List<ConsentRequest>>(emptyList())
    /** All currently-waiting requests, oldest first. Drives the bottom sheet. */
    val pending: StateFlow<List<ConsentRequest>> = _pending.asStateFlow()

    private val _blockedWhileBackground = MutableSharedFlow<ConsentRequest>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * Emits whenever a non-[NEVER] request arrived while no Haven activity
     * was foreground to render the prompt (see [requestConsent] /
     * [requestClientPairing]). The app layer turns this into a heads-up,
     * tap-to-open notification so the user knows the agent is waiting on them —
     * the §85 design note's "notification explaining what was blocked".
     *
     * Since #337 mechanism 3, a consent request that triggers this is
     * *holding* for foreground rather than already denied — tapping the
     * notification opens Haven and the sheet renders. Pairing requests still
     * deny immediately (their re-ask runs through [repromptBlockedPairing]).
     * Either way this is a nudge, never a back-door approval.
     */
    val blockedWhileBackground: SharedFlow<ConsentRequest> = _blockedWhileBackground.asSharedFlow()

    /**
     * Activity layer reports its visibility through this so we can
     * fail-closed when no one can answer the prompt.
     */
    fun setForegroundActive(active: Boolean) {
        foregroundState.value = active
    }

    /**
     * Replace the set of persistently auto-approved clients. Called by
     * the app layer whenever [UserPreferencesRepository.mcpBypassConsentClients]
     * changes (collected in `HavenApp`). Idempotent; a plain volatile
     * write — the read path in [requestConsent] only needs a consistent
     * snapshot, not a lock.
     */
    fun setPersistentBypassClients(clients: Set<String>) {
        persistentBypassClients = clients
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
        offerTimedAllow: Boolean = false,
        operationKey: String? = null,
    ): ConsentDecision {
        if (level == ConsentLevel.NEVER) return ConsentDecision.ALLOW

        // Bypass: if the user has opted this client out of per-call
        // prompts — either persistently (Settings → Paired clients) or
        // for the session ("Allow all MCP requests from '<client>' until
        // app restart" checkbox) — skip prompts for any non-NEVER tool
        // from that client. Applies to EVERY_CALL too: that's the
        // "ill-advised" part the UX copy warns about. The persistent set
        // is a volatile snapshot fed from prefs; the session set is mutex
        // -guarded because the respond() path mutates it concurrently.
        if (clientHint != null) {
            if (clientHint in persistentBypassClients) return ConsentDecision.ALLOW
            mutex.withLock {
                if (clientHint in sessionBypassClients) return ConsentDecision.ALLOW
            }
        }

        val memoKey = memoKey(clientHint, toolName)
        if (level == ConsentLevel.ONCE_PER_SESSION) {
            mutex.withLock {
                if (memoKey in sessionAllowed) return ConsentDecision.ALLOW
            }
        }
        // Time-windowed memo (set by a previous "Allow for N min"
        // response). Checked for *all* levels because the timed allow
        // explicitly outranks the per-call gate within its window.
        mutex.withLock {
            val expiry = windowedAllows[memoKey]
            if (expiry != null) {
                if (expiry > System.currentTimeMillis()) return ConsentDecision.ALLOW
                windowedAllows.remove(memoKey) // expired — clean up opportunistically
            }
        }
        // Single-use retry grant for EVERY_CALL: honour a just-approved
        // operation whose client timed out before the result arrived, so the
        // agent's retry of the SAME operation proceeds instead of re-prompting.
        if (level == ConsentLevel.EVERY_CALL) {
            val grantKey = grantKey(memoKey, operationKey)
            mutex.withLock {
                val expiry = retryGrants.remove(grantKey) // consume regardless
                if (expiry != null && expiry > System.currentTimeMillis()) {
                    return ConsentDecision.ALLOW
                }
            }
        }

        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ConsentDecision>()
        val request = ConsentRequest(
            id = id,
            toolName = toolName,
            clientHint = clientHint,
            summary = summary,
            offerTimedAllow = offerTimedAllow,
        )

        // §85 forbids proceeding without the user; it does not require failing
        // instantly. Queue the request unconditionally and await the user's
        // answer within the caller's timeout: while Haven is backgrounded
        // nothing renders (ConsentHost isn't composing), and the sheet appears
        // for this SAME live request the moment an activity resumes. DENY only
        // on timeout or an explicit deny — never auto-ALLOW. (#337 mechanism 3.)
        //
        // Queueing before the foreground wait — rather than after it, as the
        // first cut did — is also what makes a held request observable via
        // `pending` / get_pending_consent (#355). Without it an agent cannot
        // distinguish "waiting for the user" from "denied", which is the whole
        // point of the hold. Caught by driving a real backgrounded call.
        mutex.withLock {
            pendingEntries[id] = PendingEntry(deferred, clientHint)
            _pending.value = _pending.value + request
        }
        if (!foregroundState.value) {
            // Nudge the user: nothing can render the sheet until they return.
            Log.i(LOG_TAG, "requestConsent('$toolName') id=$id: holding for foreground (denied on timeout)")
            _blockedWhileBackground.tryEmit(request)
        }

        // The caller (McpServer) wraps this in its own withTimeoutOrNull, so a
        // cancellation can hit us while suspended on the await below. The
        // pending-entry teardown MUST run anyway — otherwise the request is
        // orphaned in `_pending`, the consent sheet is never dismissed, and its
        // buttons resolve a dead deferred (the "UI frozen after a consent
        // timeout" bug). Hence finally + NonCancellable.
        // `raw` is null only when the wait elapsed. Keeping it separate from
        // `decision` is what lets the log below say WHICH kind of deny this was
        // — see the logging note there.
        val raw = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    pendingEntries.remove(id)
                    _pending.value = _pending.value.filterNot { it.id == id }
                }
            }
        }
        val decision = raw ?: ConsentDecision.DENY

        // Every outcome is logged, not just the ones with a special path.
        //
        // #556 ("first prompt after app restart auto-denies without being
        // shown") went days as unreproducible partly because the ordinary deny
        // was silent: an answered deny, a timeout, and a sheet that never
        // rendered all surfaced to the caller as the same "User denied", and
        // left nothing behind on the device. A denial the user did not make is
        // exactly the case that has to be legible after the fact, so the reason
        // is recorded even though — especially though — it is the boring path.
        val reason = when {
            raw == null -> "no answer within ${timeoutMs}ms (sheet shown? foreground=${foregroundState.value})"
            raw == ConsentDecision.ALLOW -> "user allowed"
            else -> "user denied"
        }
        Log.i(LOG_TAG, "requestConsent('$toolName') id=$id -> $decision: $reason")

        if (decision == ConsentDecision.ALLOW) {
            when (level) {
                ConsentLevel.ONCE_PER_SESSION -> mutex.withLock { sessionAllowed.add(memoKey) }
                // Arm a single-use retry grant so the approval survives a client
                // that timed out mid-wait (consumed on the retry; see retryGrants).
                //
                // Only when the caller scoped the approval to an operation. With
                // a null operationKey the grant would key on client+tool alone,
                // so the *next* call of that tool — a different command, a
                // different path — would consume an approval the user gave for
                // something else. That is precisely the EVERY_CALL contract, so
                // an unscoped caller gets no grant rather than a loose one.
                ConsentLevel.EVERY_CALL -> if (operationKey != null) {
                    val grantKey = grantKey(memoKey, operationKey)
                    mutex.withLock {
                        retryGrants[grantKey] = System.currentTimeMillis() + RETRY_GRANT_MS
                    }
                }
                else -> Unit
            }
        }
        return decision
    }

    /**
     * Suspend until the user resolves a pairing prompt for a new MCP
     * client, or [timeoutMs] elapses. Distinct from [requestConsent] —
     * a paired client is a *connection-time* gate (in `initialize`),
     * not a *per-tool* gate. Returns DENY on timeout / background.
     *
     * The caller (McpServer) is responsible for persisting the client
     * name on ALLOW; this manager doesn't touch DataStore.
     */
    suspend fun requestClientPairing(
        clientName: String,
        clientVersion: String?,
        timeoutMs: Long = 60_000,
    ): ConsentDecision {
        // Blocked outranks everything — it's the user's explicit "stop asking
        // me about this client" from the pairing sheet (#337).
        mutex.withLock {
            if (clientName in blockedPairingClients) {
                Log.w(LOG_TAG, "requestClientPairing('$clientName'): user-blocked — DENY without prompt")
                return ConsentDecision.DENY
            }
        }
        // A pairing approval granted from the foreground re-prompt
        // ([repromptBlockedPairing]): the client wasn't connected when the
        // user tapped Pair, so the grant waits in [windowedAllows] until the
        // client's next initialize lands here. Window-scoped and name-keyed —
        // same trust caliber as [armRetryWindow]. Checked before the deny
        // cooldown: the window means the user *just* tapped Pair, which
        // outranks a stale deny.
        mutex.withLock {
            val key = memoKey(clientName, PAIRING_TOOL_NAME)
            val expiry = windowedAllows[key]
            if (expiry != null) {
                if (expiry > System.currentTimeMillis()) {
                    Log.i(LOG_TAG, "requestClientPairing('$clientName'): armed pairing window — ALLOW without prompt")
                    return ConsentDecision.ALLOW
                }
                windowedAllows.remove(key)
            }
        }
        // Deny cooldown: the user already said no to this name moments ago;
        // don't let a retry loop turn that into a prompt storm (#337).
        mutex.withLock {
            val cooldownExpiry = pairingDenyCooldowns[clientName]
            if (cooldownExpiry != null) {
                if (cooldownExpiry > System.currentTimeMillis()) {
                    Log.w(LOG_TAG, "requestClientPairing('$clientName'): deny cooldown active — DENY without prompt")
                    return ConsentDecision.DENY
                }
                pairingDenyCooldowns.remove(clientName)
            }
        }
        // Coalesce / foreground / rate-limit are decided under ONE lock
        // acquisition, so two same-name retries can't both slip past the
        // coalesce check and raise duplicate sheets, and N parallel requests
        // can't all slip past the rate-limit cap (#337).
        val deferred = CompletableDeferred<ConsentDecision>()
        var joined: CompletableDeferred<ConsentDecision>? = null
        var backgrounded = false
        var rateLimited = false
        mutex.withLock {
            val existing = pairingInFlight[clientName]
            when {
                // A sheet for this exact name is already on screen (a
                // reconnecting client retrying initialize) — share its
                // outcome rather than queueing an identical second sheet.
                existing != null -> joined = existing
                !foregroundState.value -> backgrounded = true
                else -> {
                    // Rotating-name spam guard: a spammer that varies its
                    // clientInfo.name per call defeats the name-keyed
                    // cooldown, so cap sheets raised per sliding window.
                    val now = System.currentTimeMillis()
                    while (recentPairingPrompts.isNotEmpty() && now - recentPairingPrompts.first() > PAIRING_PROMPT_WINDOW_MS) {
                        recentPairingPrompts.removeFirst()
                    }
                    if (recentPairingPrompts.size >= MAX_PAIRING_PROMPTS_PER_WINDOW) {
                        rateLimited = true
                    } else {
                        recentPairingPrompts.addLast(now)
                        pairingInFlight[clientName] = deferred
                    }
                }
            }
        }
        joined?.let {
            Log.i(LOG_TAG, "requestClientPairing('$clientName'): prompt already in flight — awaiting its outcome")
            return withTimeoutOrNull(timeoutMs) { it.await() } ?: ConsentDecision.DENY
        }
        if (backgrounded) {
            Log.w(LOG_TAG, "requestClientPairing('$clientName'): foreground=false — failing closed + notifying")
            blockedPairing = BlockedPairing(clientName, clientVersion, System.currentTimeMillis())
            _blockedWhileBackground.tryEmit(
                ConsentRequest(
                    id = nextId.getAndIncrement(),
                    toolName = PAIRING_TOOL_NAME,
                    clientHint = clientName,
                    summary = "MCP client '$clientName' tried to connect to Haven.",
                ),
            )
            return ConsentDecision.DENY
        }
        if (rateLimited) {
            Log.w(
                LOG_TAG,
                "requestClientPairing('$clientName'): $MAX_PAIRING_PROMPTS_PER_WINDOW pairing prompts already raised " +
                    "in ${PAIRING_PROMPT_WINDOW_MS / 1000}s — rate-limited, DENY without prompt",
            )
            return ConsentDecision.DENY
        }

        val id = nextId.getAndIncrement()
        Log.i(LOG_TAG, "requestClientPairing('$clientName' v${clientVersion ?: "?"}): queueing prompt id=$id")
        val versionSuffix = clientVersion?.takeIf { it.isNotBlank() }?.let { " v$it" } ?: ""
        val request = ConsentRequest(
            id = id,
            toolName = PAIRING_TOOL_NAME,
            clientHint = clientName,
            summary = "MCP client '$clientName'$versionSuffix wants to connect to Haven. " +
                "Once paired, it will be able to call tools — each tool still asks for " +
                "consent unless you elect to bypass that with the per-call checkbox.",
        )

        mutex.withLock {
            pendingEntries[id] = PendingEntry(deferred, clientName)
            _pending.value = _pending.value + request
        }

        // finally + NonCancellable: see requestConsent — an outer cancellation
        // must not orphan the pending entry / leave the sheet stuck on screen.
        val raw = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            withContext(NonCancellable) {
                // Resolve coalesced waiters before deregistering: on timeout the
                // deferred is still open, and a waiter left awaiting it would
                // hang to its own timeout. complete() is a no-op if answered.
                deferred.complete(ConsentDecision.DENY)
                mutex.withLock {
                    pairingInFlight.remove(clientName)
                    pendingEntries.remove(id)
                    _pending.value = _pending.value.filterNot { it.id == id }
                }
            }
        }
        val decision = raw ?: ConsentDecision.DENY
        if (raw == ConsentDecision.DENY) {
            // Explicit deny → arm the cooldown so a client retry loop can't
            // re-raise the same sheet seconds later (#337). Timeout doesn't
            // arm it: an unanswered sheet isn't a user decision.
            mutex.withLock {
                pairingDenyCooldowns[clientName] = System.currentTimeMillis() + PAIRING_DENY_COOLDOWN_MS
            }
        }
        Log.i(LOG_TAG, "requestClientPairing('$clientName') id=$id resolved: ${if (raw == null) "TIMEOUT->DENY" else decision.toString()}")
        return decision
    }

    /**
     * Called by the bottom-sheet UI when the user taps Allow / Deny.
     * When [bypassClient] is true and the decision is ALLOW, the
     * pending request's clientHint is added to [sessionBypassClients]
     * so subsequent per-tool prompts from that client short-circuit
     * to ALLOW. The flag is ignored on DENY or when the request had no
     * clientHint (e.g. an `_pairing` request — bypass on pairing is a
     * contradiction the UI prevents at render time).
     */
    suspend fun respond(
        requestId: Long,
        decision: ConsentDecision,
        bypassClient: Boolean = false,
        allowForMinutes: Int? = null,
        blockClient: Boolean = false,
    ) {
        mutex.withLock {
            val entry = pendingEntries[requestId] ?: return
            if (decision == ConsentDecision.ALLOW && bypassClient && entry.clientHint != null) {
                sessionBypassClients.add(entry.clientHint)
            }
            // "Block" on the pairing sheet (#337): a one-tap escape from a
            // client spamming pairing requests. Deny-only and session-scoped;
            // only consulted by requestClientPairing, so it can't gate tools.
            if (decision == ConsentDecision.DENY && blockClient && entry.clientHint != null) {
                blockedPairingClients.add(entry.clientHint)
            }
            if (decision == ConsentDecision.ALLOW && allowForMinutes != null && allowForMinutes > 0) {
                // Set/refresh the windowed-allow expiry for this
                // (client, tool) pair. Future requests within the
                // window short-circuit to ALLOW via [requestConsent]
                // above without re-prompting.
                val pendingRequest = _pending.value.firstOrNull { it.id == requestId }
                val toolName = pendingRequest?.toolName
                if (toolName != null) {
                    val key = memoKey(entry.clientHint, toolName)
                    windowedAllows[key] = System.currentTimeMillis() + allowForMinutes * 60_000L
                }
            }
            entry.deferred.complete(decision)
        }
    }

    /**
     * Grant a short window during which calls to [toolName] from
     * [clientHint] auto-allow. Called when the user taps **Allow** on the
     * backgrounded-consent notification: the original call already failed
     * closed (instant DENY), so this lets the agent's retry proceed without
     * re-prompting. Reuses the [windowedAllows] mechanism — same as the
     * in-sheet "Allow for N min" — so it's bounded ([NOTIFICATION_ALLOW_WINDOW_MS])
     * and cleared by [clearMemoised]. Keeping instant-deny intact (rather
     * than queueing the call to wait) is deliberate — see the failure-model
     * note; the notification action is how the user gets a real choice
     * without the consent gate ever blocking.
     */
    suspend fun armRetryWindow(clientHint: String?, toolName: String) {
        val key = memoKey(clientHint, toolName)
        mutex.withLock {
            windowedAllows[key] = System.currentTimeMillis() + NOTIFICATION_ALLOW_WINDOW_MS
        }
        Log.i(LOG_TAG, "armRetryWindow('$key') — user tapped Allow on the blocked-action notification")
    }

    /**
     * Re-raise the pairing sheet for a pairing attempt that was blocked while
     * Haven was backgrounded — called by the activity layer on foreground
     * (right after [setForegroundActive]). This is what makes tapping the
     * pairing notification actually display the request instead of opening
     * Haven onto nothing.
     *
     * On Pair, arms a [NOTIFICATION_ALLOW_WINDOW_MS] auto-allow window for
     * the client name: the client isn't connected to receive its token right
     * now, so its next `initialize` consumes the window (see
     * [requestClientPairing]) and the token is minted then. Returns the
     * client name when a remembered attempt was re-prompted (so the caller
     * can clear its notification), or null when there was nothing to do.
     * Stale attempts (older than [BLOCKED_PAIRING_TTL_MS]) are dropped.
     */
    suspend fun repromptBlockedPairing(): String? {
        val blocked = blockedPairing ?: return null
        blockedPairing = null
        if (System.currentTimeMillis() - blocked.atMs > BLOCKED_PAIRING_TTL_MS) return null
        val decision = requestClientPairing(blocked.clientName, blocked.clientVersion)
        if (decision == ConsentDecision.ALLOW) {
            mutex.withLock {
                windowedAllows[memoKey(blocked.clientName, PAIRING_TOOL_NAME)] =
                    System.currentTimeMillis() + NOTIFICATION_ALLOW_WINDOW_MS
            }
            Log.i(LOG_TAG, "repromptBlockedPairing('${blocked.clientName}'): paired — window armed for the client's next initialize")
        }
        return blocked.clientName
    }

    /**
     * Used by Settings → "Forget remembered allows". Clears the session
     * caches here; the *persistent* bypass set lives in DataStore, so the
     * caller (`SettingsViewModel`) also wipes
     * [UserPreferencesRepository.mcpBypassConsentClients] — that change
     * then flows back here via [setPersistentBypassClients]. We also drop
     * the in-memory persistent snapshot immediately so the reset takes
     * effect without waiting for the collector to re-emit.
     */
    suspend fun clearMemoised() {
        persistentBypassClients = emptySet()
        mutex.withLock {
            sessionAllowed.clear()
            sessionBypassClients.clear()
            windowedAllows.clear()
            blockedPairingClients.clear()
            pairingDenyCooldowns.clear()
        }
    }

    private fun memoKey(clientHint: String?, toolName: String): String =
        "${clientHint ?: "unknown"}::$toolName"

    /**
     * Key for [retryGrants]. One function on purpose: the arming and consuming
     * sites had drifted to different separators (a NUL on one side, a space on
     * the other), so no grant ever matched its own retry and the whole
     * mechanism was inert. `\u0000` cannot occur in a tool or operation name,
     * so it can't be forged across the join. Written as an escape, never
     * the raw byte: a file containing a raw NUL reads as binary to grep, which
     * is how the two sides drifted apart unnoticed.
     */
    private fun grantKey(memoKey: String, operationKey: String?): String =
        "$memoKey\u0000${operationKey.orEmpty()}"

    companion object {
        /**
         * Sentinel tool name carried by client-pairing prompts so the UI
         * can render a "Pair MCP client?" header instead of an
         * "Agent action requested" one, and so the bypass checkbox can
         * be suppressed on the pairing prompt itself (the user isn't
         * agreeing to specific calls yet — they're agreeing to let this
         * client even speak).
         */
        const val PAIRING_TOOL_NAME = "_pairing"

        private const val LOG_TAG = "AgentConsent"

        /**
         * How long a just-approved EVERY_CALL operation's single-use retry grant
         * stays valid (long enough for the agent to retry after a client-side
         * timeout, short enough not to span unrelated activity).
         */
        private const val RETRY_GRANT_MS = 45_000L

        /**
         * How long the notification-"Allow" grant ([armRetryWindow]) keeps a
         * (client, tool) auto-allowed so the agent's retry of a backgrounded-
         * blocked action proceeds. Short — it's a window for the retry, not a
         * standing bypass.
         */
        const val NOTIFICATION_ALLOW_WINDOW_MS = 120_000L

        /**
         * How long an explicit pairing DENY auto-denies re-attempts from the
         * same client name without prompting (#337). Short: long enough to
         * break a retry loop, short enough that a genuinely wanted re-pair
         * isn't painful.
         */
        const val PAIRING_DENY_COOLDOWN_MS = 2 * 60_000L

        /**
         * Sliding window + cap for the rotating-name pairing spam guard
         * (#337): at most [MAX_PAIRING_PROMPTS_PER_WINDOW] pairing sheets may
         * be raised per [PAIRING_PROMPT_WINDOW_MS], across all client names.
         */
        const val PAIRING_PROMPT_WINDOW_MS = 60_000L
        const val MAX_PAIRING_PROMPTS_PER_WINDOW = 3

        /**
         * How long a background-blocked pairing attempt stays re-promptable
         * ([repromptBlockedPairing]). Long enough to see the notification and
         * open the app; short enough that a forgotten attempt doesn't ambush
         * the user hours later.
         */
        const val BLOCKED_PAIRING_TTL_MS = 10 * 60_000L
    }
}
