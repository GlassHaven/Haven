package sh.haven.feature.terminal

/**
 * Resolves what a tab strip shows for a tab.
 *
 * Precedence:
 *  - With "prefer session names" on, a tab attached to a session manager
 *    (tmux/zellij/screen/byobu/herdr) always shows the multiplexer's session
 *    name — the label the user's Rename action sets — ignoring titles set by
 *    running programs (#625).
 *  - Otherwise the program-set OSC 0/2 title wins when present, falling back
 *    to the session label. Tabs without a multiplexer name (plain SSH, local
 *    shells) are unaffected by the preference: their labels carry no user
 *    labelling, so program titles still win.
 */
fun resolveTabTitle(
    programTitle: String?,
    label: String,
    multiplexerName: String?,
    followSession: Boolean,
): String = when {
    followSession && !multiplexerName.isNullOrBlank() -> label
    else -> programTitle?.takeIf { it.isNotBlank() } ?: label
}