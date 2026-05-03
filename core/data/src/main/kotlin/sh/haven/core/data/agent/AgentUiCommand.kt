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
}
