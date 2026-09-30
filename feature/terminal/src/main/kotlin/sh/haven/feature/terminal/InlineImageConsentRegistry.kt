package sh.haven.feature.terminal

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.terminal.InlineImageLimits
import org.connectbot.terminal.InlineImageRequest
import org.connectbot.terminal.InlineImages
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/** One pending inline-image consent prompt, surfaced to the terminal dialog. */
data class InlineImagePrompt(
    val request: InlineImageRequest,
    val answer: (allowed: Boolean, alwaysForTab: Boolean) -> Unit,
)

/**
 * Per-session consent state for inline images (#583). Emulators are created
 * in two places — [SshTerminalEmulatorOwner] at connect time and
 * [TerminalViewModel] for the other transports — while the consent dialog
 * lives in the terminal screen, so the prompt and the "always in this tab"
 * decision live here, keyed by sessionId, and both sides hand off through it.
 */
@Singleton
class InlineImageConsentRegistry @Inject constructor() {
    class SessionState {
        val prompt = MutableStateFlow<InlineImagePrompt?>(null)
        val alwaysInTab = MutableStateFlow(false)
    }

    private val states = ConcurrentHashMap<String, SessionState>()

    fun state(sessionId: String): SessionState = states.computeIfAbsent(sessionId) { SessionState() }

    /** Resolve the prompt with the dialog's answer, or with "deny" when none. */
    fun answer(sessionId: String, allowed: Boolean, alwaysInTab: Boolean) {
        val state = states[sessionId] ?: return
        state.prompt.value?.answer?.invoke(allowed, alwaysInTab)
    }

    /**
     * Forget a session: deny anything still pending (releasing the emulator's
     * consent gate slot) and free the state.
     */
    fun drop(sessionId: String) {
        states.remove(sessionId)?.prompt?.value?.answer?.invoke(false, false)
    }
}

/**
 * The inline-image policy for a fresh emulator, from the consent preference.
 *
 * ASK consults the session state: a decided "always in this tab" short-circuits,
 * otherwise the request is parked on the prompt flow for the dialog and the
 * coroutine parks on its deferred. The confirm coroutine is started on the main
 * looper (termlib posts it), so touching the flows here is main-confined.
 */
fun inlineImagesPolicy(
    preference: UserPreferencesRepository.TerminalInlineImages,
    session: InlineImageConsentRegistry.SessionState,
): InlineImages = when (preference) {
    UserPreferencesRepository.TerminalInlineImages.OFF -> InlineImages.Off
    UserPreferencesRepository.TerminalInlineImages.ALWAYS -> InlineImages.On(InlineImageLimits())
    UserPreferencesRepository.TerminalInlineImages.ASK -> InlineImages.Ask(limits = InlineImageLimits()) { request ->
        if (session.alwaysInTab.value) return@Ask true
        val answered = CompletableDeferred<Boolean>()
        // Single prompt slot: resolve a stale pending prompt first so its parked
        // confirm coroutine is not orphaned when the new request takes the slot.
        session.prompt.value?.answer?.invoke(false, false)
        session.prompt.value = InlineImagePrompt(request) { allowed, always ->
            if (always) session.alwaysInTab.value = true
            session.prompt.value = null
            answered.complete(allowed)
        }
        answered.await()
    }
}