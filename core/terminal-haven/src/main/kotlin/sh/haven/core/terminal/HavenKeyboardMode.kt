package sh.haven.core.terminal

/**
 * How Haven wants the soft keyboard to behave inside the terminal. Folded
 * into one sealed type so call sites pass a single value instead of a pair
 * of booleans — and so future modes (e.g. "accessibility") have a single
 * place to land.
 *
 * Translates to termlib's existing `allowStandardKeyboard` and
 * `rawKeyboardMode` flags on the public [org.connectbot.terminal.Terminal]
 * composable. Mapping lives in [HavenTerminal]; callers never touch the
 * termlib booleans directly.
 */
sealed interface HavenKeyboardMode {
    /**
     * Default. `TYPE_NULL`-class input with `NO_SUGGESTIONS` and
     * `NO_PERSONALIZED_LEARNING` — Gboard's mic, suggestion strip, and
     * on-device writing assist all stay off. CJK composition still works
     * because the terminal hosts its own composition flow on top.
     */
    data object Secure : HavenKeyboardMode

    /**
     * Standard Android soft keyboard. Voice input, swipe typing, and
     * autocomplete are all enabled. Useful when the user is typing long
     * free-form prose into a shell editor and wants full IME features.
     */
    data object Standard : HavenKeyboardMode

    /**
     * No `InputConnection` at all — the IME has nothing to attach to and
     * Gboard's decorations disappear entirely. Physical keyboards still
     * work via `View.dispatchKeyEvent`. Soft-keyboard input comes through
     * as raw key events only, which means no IME composition, so this is
     * for users who want the hardest possible IME lock-out and don't need
     * CJK. Equivalent to ConnectBot's old `TYPE_NULL` behaviour.
     */
    data object Raw : HavenKeyboardMode

    /**
     * User-configured EditorInfo flag set. Same plumbing as Secure but
     * with each underlying bit individually toggleable so power users
     * can tune around IME-specific quirks (#115 follow-up): get voice
     * input on Gboard while keeping autocorrect off, or unblock
     * Samsung's commit-text gate without enabling autocap, etc.
     */
    data class Custom(val flags: ImeFlagSet) : HavenKeyboardMode
}

/**
 * Individual IME flag toggles for [HavenKeyboardMode.Custom]. Each
 * boolean maps to one EditorInfo bit; [ImeInputView] reads this struct
 * and assembles `inputType` / `imeOptions` from it directly when the
 * mode is Custom.
 *
 * Defaults match what the Secure preset would have set, so flipping
 * into Custom mode without further tweaks preserves behaviour.
 */
data class ImeFlagSet(
    /** `TYPE_TEXT_FLAG_NO_SUGGESTIONS` — hide the suggestion strip. */
    val noSuggestions: Boolean = true,
    /**
     * `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` — strongest "don't rewrite
     * my input" hint. Universally honoured by Gboard; keeps autocap,
     * autospace, and word-substitution autocorrect off. Mutually
     * exclusive with composition (CJK / voice / swipe) on most IMEs.
     */
    val visiblePassword: Boolean = true,
    /**
     * `TYPE_TEXT_FLAG_AUTO_CORRECT` — required by Samsung Honeyboard's
     * IMM gate to dispatch input at all (#110), but explicitly enables
     * autocorrect-on-space on Gboard. Off by default.
     */
    val autoCorrect: Boolean = false,
    /**
     * Give `BaseInputConnection` a real Editable so the IME can do
     * composition (voice input, swipe typing). Implies
     * non-`VISIBLE_PASSWORD` input type — the user picks one or the
     * other.
     */
    val fullEditor: Boolean = false,
    /** `IME_FLAG_NO_EXTRACT_UI` — suppress fullscreen IME in landscape. */
    val noExtractUi: Boolean = true,
    /** `IME_FLAG_NO_PERSONALIZED_LEARNING` — privacy: IME doesn't learn input. */
    val noPersonalizedLearning: Boolean = true,
)

/**
 * The default [HavenKeyboardMode] on this device — what Secure resolves to
 * when the user has picked no explicit mode.
 *
 * Secure's recipe includes `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, and some
 * OEM builds answer that variation by replacing the IME outright with a
 * locked minimal system keypad that offers no way to switch IMEs or compose
 * CJK text (#684: Huawei, confirmed on a P60 Art on both the Android and
 * HarmonyOS builds; the reporter's own tests showed Compose and a Custom
 * set without the visible-password bit both keep the ordinary keyboard).
 * There the default drops only the visible-password bit; every other part
 * of the Secure recipe stays.
 *
 * The trade-off is real: the password variation was what kept silent
 * autocorrect off on Gboard (#115), so a Huawei device running Gboard gets
 * autocorrect back in the terminal. That is the irreducible choice between
 * "system keypad, no CJK" and "normal IME, Gboard rewrites"; Raw and Custom
 * modes remain as escape hatches in both directions.
 *
 * Deliberately narrow: only manufacturers with a confirmed report qualify.
 * Extend on evidence, not guesswork — a false positive silently downgrades
 * the default on an unrelated device. HONOR builds are not covered until
 * someone reports them.
 */
fun defaultKeyboardModeForDevice(manufacturer: String): HavenKeyboardMode =
    if (manufacturer.isNotBlank() && manufacturer.contains("HUAWEI", ignoreCase = true))
        HavenKeyboardMode.Custom(ImeFlagSet(visiblePassword = false))
    else HavenKeyboardMode.Secure
