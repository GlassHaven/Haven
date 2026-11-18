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
/**
 * The connection a tab belongs to, as a second line under its title — or null
 * when the title already says it.
 *
 * Tabs attached to a session manager are titled by the session name, and
 * herdr/tmux hand out the same default ("default", "0") on every host, so a
 * strip of three remote tabs could read "default default default" with
 * nothing saying which machine each one is (likewise when a program's OSC
 * title replaces the label). Repeating the host under a title that already is
 * the host would only add noise, so it is shown only when the two differ.
 */
fun resolveTabSubtitle(title: String, hostLabel: String?): String? =
    hostLabel?.trim()?.takeIf { it.isNotEmpty() && it != title.trim() }
