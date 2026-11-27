package sh.haven.core.ssh

import android.util.Log

private const val KI_TAG = "HavenKI"

/**
 * Decides how one keyboard-interactive round is answered: from stored secrets
 * where they cover it (the profile's saved password for a password prompt, a
 * freshly generated TOTP code for an OTP-looking prompt), otherwise by handing
 * the round to the UI [prompter].
 *
 * Engine-neutral on purpose. [KeyboardInteractiveUserInfo] adapts it to JSch's
 * synchronous `UIKeyboardInteractive`, and the sshlib engine calls it directly
 * from its suspending `KeyboardInteractiveCallback`. The OTP keyword list is
 * exactly the sort of thing that drifts once it exists in two places, so it
 * lives here and neither engine owns a copy.
 *
 * An [fallbackPassword] auto-answers a prompt that looks like a password
 * prompt, so a user with a saved password doesn't retype it just because the
 * server routed "Password:" through keyboard-interactive instead of password
 * auth. A [totpCodeProvider] (#178) answers OTP prompts. When every prompt in
 * the round is covered and [autoSubmit] is true, the round is answered with no
 * UI at all; otherwise the prompter is invoked with the partial answers carried
 * in [KeyboardInteractiveChallenge.prefilled] so the dialog can pre-populate
 * (the per-profile "confirm OTP before sending" path).
 */
internal class KeyboardInteractiveAnswerer(
    private val destination: String,
    private val prompter: KeyboardInteractivePrompter,
    private val fallbackPassword: CharArray? = null,
    private val totpCodeProvider: (() -> String)? = null,
    private val autoSubmit: Boolean = true,
) {

    private val usableFallbackPassword: CharArray? = fallbackPassword?.takeIf { it.isNotEmpty() }

    /**
     * Responses for this round in prompt order, or null to abort
     * keyboard-interactive (the user declined).
     */
    suspend fun answer(
        name: String,
        instruction: String,
        prompts: List<KeyboardInteractiveChallenge.Prompt>,
        /** The engine's own destination string when it has one; null uses ours. */
        destination: String? = null,
    ): List<String>? {
        Log.d(
            KI_TAG,
            "KI round name='$name' instruction='$instruction' " +
                "prompts=${prompts.map { "${it.text}(echo=${it.echo})" }}",
        )

        // Generated at this instant so an OTP code is current for the window
        // the server will validate it against. Check OTP keywords BEFORE
        // generic "password" matching (#892): PAM modules like pam_oath prompt
        // "One-time password:", which contains "password" yet expects the TOTP
        // code, not the static login password.
        val autoAnswers: List<String?> = prompts.map { p ->
            when {
                totpCodeProvider != null && p.looksLikeOtpPrompt() -> totpCodeProvider.invoke()
                usableFallbackPassword != null && p.isSavablePasswordPrompt() ->
                    String(usableFallbackPassword)
                else -> null
            }
        }

        val holdForOtpConfirmation = !autoSubmit && prompts.any { it.looksLikeOtpPrompt() }
        if (!holdForOtpConfirmation && prompts.isNotEmpty() && autoAnswers.all { it != null }) {
            Log.d(KI_TAG, "  auto-answering ${prompts.size} prompt(s) from stored secrets")
            return autoAnswers.map { it!! }
        }

        val challenge = KeyboardInteractiveChallenge(
            destination = destination ?: this.destination,
            name = name,
            instruction = instruction,
            prompts = prompts,
            prefilled = if (autoAnswers.any { it != null }) autoAnswers else emptyList(),
        )
        Log.d(KI_TAG, "  dispatching to prompter")
        val responses = prompter.prompt(challenge)
        Log.d(
            KI_TAG,
            "  prompter returned: ${if (responses == null) "null (cancel)" else "${responses.size} responses, " +
                "lengths=${responses.map { it.length }}"}",
        )
        return responses
    }
}
