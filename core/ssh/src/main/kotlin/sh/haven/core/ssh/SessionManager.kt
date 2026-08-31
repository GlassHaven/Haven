package sh.haven.core.ssh

/**
 * Session manager options for wrapping SSH shells in persistent sessions.
 * @param label Display name for logging.
 * @param command Template that produces an attach-or-create shell command given a session name,
 *                or null for no session manager.
 * @param listCommand Shell command to list existing sessions, or null if not applicable.
 * @param killCommand Template that produces a command to kill/delete a session by name.
 */
enum class SessionManager(
    val label: String,
    val command: ((String) -> String)?,
    val listCommand: String?,
    val killCommand: ((String) -> String)? = null,
    val renameCommand: ((old: String, new: String) -> String)? = null,
) {
    NONE("None", null, null),
    TMUX("tmux",
        { name -> "exec sh -c 'if ! command -v tmux >/dev/null 2>&1; then echo \"Haven: tmux not found. Install it (e.g. apt install tmux) or change session manager in connection settings.\"; else exec tmux new-session -A -s $name \\; set -gq allow-passthrough on \\; set -gq mouse on; fi'" },
        "sh -c 'tmux ls -F \"#{session_name}\" 2>/dev/null'",
        { name -> "sh -c 'tmux kill-session -t $name'" },
        { old, new -> "sh -c 'tmux rename-session -t $old $new'" },
    ),
    ZELLIJ("zellij",
        { name -> "exec sh -c 'if ! command -v zellij >/dev/null 2>&1; then echo \"Haven: zellij not found. See https://zellij.dev/documentation/installation or change session manager in connection settings.\"; else exec zellij attach $name --create; fi'" },
        "sh -c 'zellij ls 2>/dev/null'",
        { name -> "sh -c 'zellij kill-session $name 2>/dev/null; zellij delete-session $name 2>/dev/null'" },
        { old, new -> "sh -c 'zellij delete-session $new 2>/dev/null; zellij --session $old action rename-session $new'" },
    ),
    SCREEN("screen",
        { name -> "exec sh -c 'if ! command -v screen >/dev/null 2>&1; then echo \"Haven: screen not found. Install it (e.g. apt install screen) or change session manager in connection settings.\"; else exec screen -dRR $name; fi'" },
        "sh -c 'screen -ls 2>/dev/null'",
        { name -> "sh -c 'screen -S $name -X quit'" },
        { old, new -> "sh -c 'screen -S $old -X sessionname $new'" },
    ),
    BYOBU("byobu",
        { name -> "exec sh -c 'if ! command -v byobu >/dev/null 2>&1; then echo \"Haven: byobu not found. Install it (e.g. apt install byobu) or change session manager in connection settings.\"; else exec byobu new-session -A -s $name \\; set -gq mouse on; fi'" },
        "sh -c 'byobu ls -F \"#{session_name}\" 2>/dev/null'",
        { name -> "sh -c 'byobu kill-session -t $name'" },
        { old, new -> "sh -c 'byobu rename-session -t $old $new'" },
    ),
    HERDR("Herdr",
        { name -> "exec sh -c 'if ! command -v herdr >/dev/null 2>&1; then echo \"Haven: Herdr not found. See https://herdr.dev or change session manager in connection settings.\"; else exec herdr --session $name; fi'" },
        "sh -c 'herdr session list --json 2>/dev/null'",
        { name -> "sh -c 'herdr session stop $name --json >/dev/null 2>&1 || true; herdr session delete $name --json >/dev/null 2>&1'" },
    );

    companion object {
        /**
         * Sanitize a raw label for use as a tmux/screen/zellij session name.
         * No '.' or ':' — tmux treats them as session:window.pane separators,
         * so an auto-name derived from user@host with an IP host
         * ("user-10.0.0.5") fails to attach and the exec'd shell closes (#358).
         */
        fun sanitizeSessionName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9_-]"), "-")

        /** Strip ANSI escape sequences (colors, bold, etc.) from a string. */
        private val ANSI_REGEX = Regex("\\x1B\\[[0-9;]*[a-zA-Z]")
        private fun stripAnsi(s: String): String = s.replace(ANSI_REGEX, "")

        private val HERDR_SESSION_LIST_REGEX = Regex(
            "^\\s*\\{.*\"sessions\"\\s*:\\s*\\[(.*)]\\s*}\\s*$",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val HERDR_SESSION_NAME_REGEX =
            Regex("\"name\"\\s*:\\s*\"([A-Za-z0-9._-]{1,64})\"")

        /**
         * Parse session list output into session names.
         * Returns empty list if output is blank or unparseable.
         */
        fun parseSessionList(manager: SessionManager, output: String): List<String> {
            val clean = stripAnsi(output)
            if (clean.isBlank()) return emptyList()
            return when (manager) {
                NONE -> emptyList()
                TMUX, BYOBU -> clean.lines().filter { it.isNotBlank() }
                ZELLIJ -> clean.lines()
                    .filter { it.isNotBlank() && !it.contains("EXITED") }
                    .map { it.trim().split(Regex("\\s+")).first() }
                    .filter { it.isNotBlank() && !it.startsWith("No ") }
                    .sorted()
                SCREEN -> clean.lines()
                    .map { it.trim() }
                    .filter { it.contains(".") && (it.contains("Detached") || it.contains("Attached")) }
                    .mapNotNull { line ->
                        val firstPart = line.split(Regex("\\s+")).firstOrNull() ?: return@mapNotNull null
                        val dotIdx = firstPart.indexOf('.')
                        if (dotIdx >= 0) firstPart.substring(dotIdx + 1) else null
                    }
                HERDR -> {
                    val sessions = HERDR_SESSION_LIST_REGEX.find(clean)?.groupValues?.get(1)
                        ?: return emptyList()
                    HERDR_SESSION_NAME_REGEX.findAll(sessions)
                        .map { it.groupValues[1] }
                        .toList()
                }
            }
        }
    }
}
