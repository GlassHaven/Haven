package sh.haven.core.ssh

import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.runBlocking

/**
 * Bridges JSch's synchronous [UIKeyboardInteractive] callback (invoked on
 * JSch's internal IO thread during auth) to a suspending
 * [KeyboardInteractivePrompter]. The JSch thread blocks in [runBlocking]
 * until the prompter resumes — which, for a UI-backed prompter, means
 * until the user dismisses the dialog.
 *
 * The decision of what to auto-answer versus what to ask the user lives in
 * [KeyboardInteractiveAnswerer], shared with the sshlib engine; this class is
 * only the JSch adapter.
 *
 * Also implements [UserInfo] because JSch's API requires any interactive
 * callback to come in as a single object that implements both interfaces.
 * We deliberately return nulls / false from the [UserInfo] methods so JSch
 * falls back to the password set via `session.setPassword(...)` for
 * password auth, and cancels prompts we don't handle (host-key acceptance
 * is handled separately by [HostKeyVerifier]).
 */
internal class KeyboardInteractiveUserInfo(
    private val destination: String,
    private val prompter: KeyboardInteractivePrompter,
    fallbackPassword: CharArray? = null,
    totpCodeProvider: (() -> String)? = null,
    autoSubmit: Boolean = true,
    private val onBanner: ((String) -> Unit)? = null,
) : UserInfo, UIKeyboardInteractive {

    private val answerer = KeyboardInteractiveAnswerer(
        destination = destination,
        prompter = prompter,
        fallbackPassword = fallbackPassword,
        totpCodeProvider = totpCodeProvider,
        autoSubmit = autoSubmit,
    )

    override fun getPassphrase(): String? = null

    override fun getPassword(): String? = null

    override fun promptPassword(message: String?): Boolean = false

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) {
        val trimmed = message?.trim()?.takeIf { it.isNotEmpty() } ?: return
        onBanner?.invoke(trimmed)
        prompter.onBanner(destination, trimmed)
    }

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? {
        val prompts = (prompt ?: emptyArray()).mapIndexed { i, p ->
            KeyboardInteractiveChallenge.Prompt(
                text = p,
                echo = echo?.getOrNull(i) ?: true,
            )
        }
        val responses = runBlocking {
            answerer.answer(
                name = name ?: "",
                instruction = instruction ?: "",
                prompts = prompts,
                // JSch supplies its own "user@host:port" string; keep it when present.
                destination = destination,
            )
        }
        return responses?.toTypedArray()
    }
}
