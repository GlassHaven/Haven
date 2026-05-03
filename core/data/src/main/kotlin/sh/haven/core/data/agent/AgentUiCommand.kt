package sh.haven.core.data.agent

/**
 * UI-level commands the agent transport can post to drive the user's
 * existing surfaces. Distinct from [ConsentRequest] — these are
 * tap-equivalent navigation / state-promotion actions that reveal the
 * same UI the user already has access to. The agent layer publishes,
 * the UI layer collects (`HavenNavHost`, [sh.haven.feature.sftp.SftpViewModel]).
 *
 * Sealed so the collector exhaustively handles every variant. Adding a
 * new verb is intentionally a multi-site change: a new variant here, a
 * matching MCP tool, and a collector branch in whichever screen owns
 * the surface.
 */
sealed class AgentUiCommand {
    /**
     * Open the SFTP file browser for [profileId] at [path]. The
     * collector switches the pager to the SFTP page; the
     * [sh.haven.feature.sftp.SftpViewModel] reacts in parallel by
     * selecting the profile (if not already active) and calling
     * `navigateTo(path)`.
     *
     * Tap-equivalent: same effect as the user tapping the SFTP tab and
     * entering the path manually. [path] is interpreted by whichever
     * backend the profile resolves to — POSIX absolute for SSH and
     * Local, share-relative for SMB, remote-relative for rclone.
     */
    data class NavigateToSftpPath(
        val profileId: String,
        val path: String,
    ) : AgentUiCommand()

    /**
     * Bring the terminal tab for [sessionId] to the front. The
     * collector switches the pager to the Terminal page; the
     * [sh.haven.feature.terminal.TerminalViewModel] finds the matching
     * tab by sessionId and calls `selectTab(index)`.
     *
     * Tap-equivalent: same effect as the user tapping the Terminal tab
     * and tapping the right session header. No effect when no tab
     * matches — agents discover live sessionIds via `list_sessions`,
     * so a stale ID drops silently.
     */
    data class FocusTerminalSession(
        val sessionId: String,
    ) : AgentUiCommand()

    /**
     * Stage a conversion job in the SFTP screen's convert dialog with
     * the given form-field defaults. The user reviews and taps Convert
     * to actually run ffmpeg — the agent suggests, the user confirms.
     * Tap-equivalent because the destructive action (transcode) still
     * requires the user's tap on the dialog's Convert button.
     *
     * Any of [container] / [videoEncoder] / [audioEncoder] may be null,
     * in which case the dialog uses its existing defaults (extension-
     * based for container, "copy" for encoders). The dialog's audio-
     * only auto-correct still runs, so a video container suggested for
     * an audio-only source self-corrects on probe.
     *
     * VISION.md §1a names this verb explicitly as the example for
     * cross-tab agent-driven UI — opening a primitive's dialog with
     * prefilled args.
     */
    data class OpenConvertDialog(
        val profileId: String,
        val sourcePath: String,
        val container: String? = null,
        val videoEncoder: String? = null,
        val audioEncoder: String? = null,
    ) : AgentUiCommand()
}
