package sh.haven.core.ssh

import android.util.Log
import java.util.Collections
import java.util.WeakHashMap

private const val TAG = "RemotePlatform"

/**
 * The shell a host's sshd runs commands through, which decides the syntax
 * Haven's session-manager commands must use.
 *
 * sshd runs exec requests and the interactive login through the same
 * configured shell, so probing an exec channel tells us what the PTY will
 * be typed into as well. On a stock Windows OpenSSH server that shell is
 * `cmd.exe` (or PowerShell when `DefaultShell` is set), where the POSIX
 * `exec sh -c '…'` templates fail with "The system cannot find the path
 * specified" even though a Windows-native manager such as Herdr is
 * installed and on PATH.
 */
enum class RemotePlatform {
    /** Probe not run, failed, or inconclusive. Treated as POSIX (pre-probe behaviour). */
    UNKNOWN,
    LINUX,
    MACOS,

    /** Some other POSIX shell (BSD, Git Bash/MSYS/Cygwin as the Windows login shell, …). */
    OTHER_POSIX,

    /** Windows with `cmd.exe` as the sshd shell (the Windows OpenSSH default). */
    WINDOWS_CMD,

    /** Windows with PowerShell (`powershell` or `pwsh`) as the sshd shell. */
    WINDOWS_POWERSHELL,
    ;

    val isWindowsShell: Boolean get() = this == WINDOWS_CMD || this == WINDOWS_POWERSHELL

    companion object {
        /**
         * One command that every shell family runs without error and answers
         * differently:
         *  - cmd expands `%OS%` to `Windows_NT`, leaves `$PSVersionTable`
         *    literal and does not treat `;` as a separator, so it echoes
         *    `Windows_NT$PSVersionTable; uname -s`;
         *  - PowerShell expands `$PSVersionTable` to a `…Hashtable` type name;
         *  - a POSIX shell prints `%OS%` (empty variable), then `uname -s`
         *    prints `Linux` / `Darwin` / … on its own line.
         */
        const val PROBE_COMMAND = "echo %OS%\$PSVersionTable; uname -s"

        private const val PROBE_TIMEOUT_MS = 8_000L

        fun parseProbe(output: String): RemotePlatform {
            val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return UNKNOWN
            val first = lines.first()
            if (first.contains("hashtable", ignoreCase = true)) return WINDOWS_POWERSHELL
            if (first.startsWith("Windows_NT") && first.contains("\$PSVersionTable")) return WINDOWS_CMD
            val uname = lines.firstOrNull { !it.startsWith("%OS%") && it != "%OS%" } ?: return UNKNOWN
            return when {
                uname.equals("Linux", ignoreCase = true) -> LINUX
                uname.equals("Darwin", ignoreCase = true) -> MACOS
                uname.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) -> OTHER_POSIX
                else -> UNKNOWN
            }
        }

        /** Results per live connection; a reconnect builds a new client and probes again. */
        private val cache: MutableMap<SshConnection, RemotePlatform> =
            Collections.synchronizedMap(WeakHashMap())

        /**
         * The platform behind [client], probing it once over an exec channel
         * and caching the answer for the connection's lifetime. Never throws:
         * a failed probe yields [UNKNOWN] (not cached, so a later call retries).
         */
        suspend fun detect(client: SshConnection): RemotePlatform {
            cache[client]?.let { return it }
            val detected = try {
                val r = client.execCommand(PROBE_COMMAND, PROBE_TIMEOUT_MS)
                parseProbe(r.stdout)
            } catch (e: Exception) {
                Log.d(TAG, "platform probe failed: ${e.message}")
                UNKNOWN
            }
            if (detected != UNKNOWN) cache[client] = detected
            Log.d(TAG, "remote platform: $detected")
            return detected
        }
    }
}

/** [SessionManager.listCommandFor] for the platform behind [client] (probed once per connection). */
suspend fun SessionManager.listCommandOn(client: SshConnection): String? =
    listCommandFor(RemotePlatform.detect(client))

/** [SessionManager.killCommandFor] for the platform behind [client] (probed once per connection). */
suspend fun SessionManager.killCommandOn(client: SshConnection, name: String): String? =
    killCommandFor(name, RemotePlatform.detect(client))
