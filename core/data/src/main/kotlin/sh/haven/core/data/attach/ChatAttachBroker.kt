package sh.haven.core.data.attach

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mediates the chat "attach from Files" flow (the namespace-attach half of
 * the chat coupling): the chat screen arms a pick and jumps to the Files
 * tab; the SFTP screen shows a pick banner and routes file taps through
 * [confirmPick] until [cancelPick] resolves the wait.
 *
 * Singleton-scoped so ChatViewModel (owning the awaiting coroutine) and
 * SftpViewModel (owning the tap surface) observe the same state — same
 * shape as the terminal's TerminalAttachCoordinator, minus the upload,
 * because the chat flow reads the file instead of writing it.
 */
@Singleton
class ChatAttachBroker @Inject constructor() {

    /** What the Files tab resolved the pick to. */
    data class Pick(
        val profileId: String,
        val path: String,
        val name: String,
        val size: Long,
    )

    private val _pending = MutableStateFlow(false)
    /** True while a pick is armed and the Files tab should show its banner. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<Pick?>? = null

    /**
     * Arm a pick and wait for the Files tab to resolve it. Returns the
     * confirmed [Pick], or null when the user cancelled (or the awaiting
     * caller was itself torn down — the `finally` clears the banner either
     * way, so a dead ViewModel can't leave the Files tab wedged).
     * A second arm while one is pending refuses (null): one pick at a time.
     */
    suspend fun awaitPick(): Pick? {
        val d: CompletableDeferred<Pick?>
        synchronized(this) {
            if (deferred != null) return null
            d = CompletableDeferred()
            deferred = d
            _pending.value = true
        }
        try {
            return d.await()
        } finally {
            synchronized(this) {
                if (deferred === d) {
                    deferred = null
                    _pending.value = false
                }
            }
        }
    }

    /** Called by the SFTP screen when the user taps a file to attach. */
    fun confirmPick(profileId: String, path: String, name: String, size: Long) {
        deferred?.complete(Pick(profileId, path, name, size))
    }

    /** Called by the SFTP screen when the user backs out of pick mode. */
    fun cancelPick() {
        deferred?.complete(null)
    }
}