package sh.haven.core.ssh

import org.json.JSONObject

/**
 * Prefix for the list / kill / rename commands, which run over an SSH exec
 * channel rather than the interactive shell. sshd gives that non-login `sh` a
 * bare PATH (macOS: /usr/bin:/bin:/usr/sbin:/sbin), so a Homebrew-installed
 * manager was "not found" (exit 127) there even though the login shell had
 * just started it: the attach probe reported "tmux not running" over a live
 * tmux session and the picker listed nothing. Append (never prepend) the
 * Homebrew bin directories so a binary already on PATH keeps winning. The
 * start commands are typed into the login shell and already see its PATH.
 */
private const val EXEC_SH = "sh -c 'PATH=\"\$PATH:/opt/homebrew/bin:/usr/local/bin:\$HOME/.local/bin:\$HOME/.cargo/bin\"; "

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
        "${EXEC_SH}tmux ls -F \"#{session_name}\" 2>/dev/null'",
        { name -> "${EXEC_SH}tmux kill-session -t $name'" },
        { old, new -> "${EXEC_SH}tmux rename-session -t $old $new'" },
    ),
    ZELLIJ("zellij",
        { name -> "exec sh -c 'if ! command -v zellij >/dev/null 2>&1; then echo \"Haven: zellij not found. See https://zellij.dev/documentation/installation or change session manager in connection settings.\"; else exec zellij attach $name --create; fi'" },
        "${EXEC_SH}zellij ls 2>/dev/null'",
        { name -> "${EXEC_SH}zellij kill-session $name 2>/dev/null; zellij delete-session $name 2>/dev/null'" },
        { old, new -> "${EXEC_SH}zellij delete-session $new 2>/dev/null; zellij --session $old action rename-session $new'" },
    ),
    SCREEN("screen",
        { name -> "exec sh -c 'if ! command -v screen >/dev/null 2>&1; then echo \"Haven: screen not found. Install it (e.g. apt install screen) or change session manager in connection settings.\"; else exec screen -dRR $name; fi'" },
        "${EXEC_SH}screen -ls 2>/dev/null'",
        { name -> "${EXEC_SH}screen -S $name -X quit'" },
        { old, new -> "${EXEC_SH}screen -S $old -X sessionname $new'" },
    ),
    BYOBU("byobu",
        { name -> "exec sh -c 'if ! command -v byobu >/dev/null 2>&1; then echo \"Haven: byobu not found. Install it (e.g. apt install byobu) or change session manager in connection settings.\"; else exec byobu new-session -A -s $name \\; set -gq mouse on; fi'" },
        "${EXEC_SH}byobu ls -F \"#{session_name}\" 2>/dev/null'",
        { name -> "${EXEC_SH}byobu kill-session -t $name'" },
        { old, new -> "${EXEC_SH}byobu rename-session -t $old $new'" },
    ),
    HERDR("herdr",
        { name -> "exec sh -c 'if ! command -v herdr >/dev/null 2>&1; then echo \"Haven: Herdr not found. See https://herdr.dev or change session manager in connection settings.\"; else exec herdr --session $name; fi'" },
        "${EXEC_SH}herdr session list --json 2>/dev/null'",
        { name -> "${EXEC_SH}herdr session stop $name --json >/dev/null 2>&1 || true; herdr session delete $name --json >/dev/null 2>&1'" },
    ),
    // psmux's CLI differs from tmux twice: `ls` prints "NAME: N windows
    // (created ...)" unless asked for -F, and `rename-session` takes only the
    // new name and acts on the current session, so no target-based rename.
    PSMUX("psmux",
        { name -> "exec sh -c 'if ! command -v psmux >/dev/null 2>&1; then echo \"Haven: psmux not found. See https://github.com/psmux/psmux or change session manager in connection settings.\"; else exec psmux attach -t $name || exec psmux new-session -s $name; fi'" },
        "${EXEC_SH}psmux ls -F \"#{session_name}\" 2>/dev/null'",
        { name -> "${EXEC_SH}psmux kill-session -t $name'" },
    ),
    RMUX("rmux",
        { name -> "exec sh -c 'PATH=\"\$PATH:\$HOME/.local/bin:\$HOME/.cargo/bin\"; if ! command -v rmux >/dev/null 2>&1; then echo \"Haven: rmux not found. See https://github.com/Helvesec/rmux or change session manager in connection settings.\"; else exec rmux new-session -A -s $name \\; set -gq allow-passthrough on \\; set -gq mouse on; fi'" },
        "${EXEC_SH}rmux ls -F \"#{session_name}\" 2>/dev/null'",
        { name -> "${EXEC_SH}rmux kill-session -t $name'" },
        { old, new -> "${EXEC_SH}rmux rename-session -t $old $new'" },
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

        /**
         * Parse session list output into session names.
         * Returns empty list if output is blank or unparseable.
         */
        fun parseSessionList(manager: SessionManager, output: String): List<String> {
            val clean = stripAnsi(output)
            if (clean.isBlank()) return emptyList()
            return when (manager) {
                NONE -> emptyList()
                TMUX, BYOBU, PSMUX, RMUX -> clean.lines().filter { it.isNotBlank() }
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
                    // `herdr session list --json` prints {"sessions":[{"name":…,
                    // "default":…,"running":…,"socket_path":…,"session_dir":…}]}
                    // (herdrdev/herdr src/cli.rs session_list). Parse it as JSON —
                    // a regex over the whole document would also match "name"
                    // fields if the shape ever nests workspaces/tabs/panes.
                    val sessions = try {
                        JSONObject(clean).getJSONArray("sessions")
                    } catch (_: Exception) {
                        return emptyList()
                    }
                    buildList {
                        for (i in 0 until sessions.length()) {
                            val name = sessions.optJSONObject(i)?.optString("name") ?: continue
                            // Mirror Herdr's validate_name: ASCII alnum . _ -, max 64.
                            if (name.isNotEmpty() && name.length <= 64 &&
                                name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-" }
                            ) {
                                add(name)
                            }
                        }
                    }
                }
            }
        }
    }
}
