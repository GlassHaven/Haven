package sh.haven.core.data.keystore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the @Singleton [sh.haven.core.security.Keystore] (which has
 * no Activity context) to an Activity-bound `BiometricPrompt`. The
 * Keystore section calls [request], the Activity collects [pending]
 * and renders the prompt, and the Activity calls [respond] with the
 * outcome — same shape as `AgentConsentManager` for agent consent.
 *
 * ### Failure model
 *
 * If no Activity is in the foreground (the user backgrounded Haven
 * between starting an SSH connect and the auth path reaching here),
 * we fail closed: [request] returns [Decision.UNAVAILABLE] immediately
 * and the Keystore.fetch reports
 * [sh.haven.core.security.KeystoreFetch.Failed]. The user has to come
 * back into the app and retry, which preserves "key never leaves the
 * store without an explicit human ack."
 */
@Singleton
class BiometricGate @Inject constructor() {

    enum class Decision { ALLOW, DENY, UNAVAILABLE }

    /**
     * One pending biometric request the Activity is expected to render
     * and resolve. [id] keys the response back to the suspending caller.
     */
    data class Request(
        val id: Long,
        /** Short label for the prompt subtitle ("Unlock <key>"). */
        val label: String,
        /** Optional second line — fingerprint or algorithm — for the prompt. */
        val detail: String? = null,
    )

    private val nextId = AtomicLong(1)
    private val mutex = Mutex()
    private val pendingDeferreds = mutableMapOf<Long, CompletableDeferred<Decision>>()

    @Volatile
    private var foregroundActive: Boolean = false

    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    /**
     * Activity layer reports its visibility through this. Without a
     * foreground host the prompt cannot render, so [request] fails
     * closed when this is false.
     */
    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
    }

    /**
     * Suspend until the user resolves the prompt for the queued
     * request, or [timeoutMs] elapses. Returns [Decision.UNAVAILABLE]
     * if no Activity is foregrounded; [Decision.DENY] on timeout or
     * explicit cancel.
     */
    suspend fun request(
        label: String,
        detail: String? = null,
        timeoutMs: Long = 60_000,
    ): Decision {
        if (!foregroundActive) return Decision.UNAVAILABLE

        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Decision>()
        val request = Request(id = id, label = label, detail = detail)

        mutex.withLock {
            pendingDeferreds[id] = deferred
            _pending.value = _pending.value + request
        }

        val decision = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: Decision.DENY

        mutex.withLock {
            pendingDeferreds.remove(id)
            _pending.value = _pending.value.filterNot { it.id == id }
        }
        return decision
    }

    /** Called by the Activity host when the prompt resolves. */
    suspend fun respond(requestId: Long, decision: Decision) {
        mutex.withLock {
            pendingDeferreds[requestId]?.complete(decision)
        }
    }
}
