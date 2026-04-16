package sh.haven.core.ssh

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ShellFileBrowser"

/**
 * Minimal remote file listing over an `exec` channel for servers that do
 * not expose the SFTP subsystem. Uses the already-connected [SshClient] to
 * run `ls -la --time-style=full-iso` and parse the output into
 * [ShellFileBrowser.Entry] rows.
 *
 * GNU / BusyBox / toybox `ls` all accept this flag combination; older
 * Solaris or AIX installations may not — those fall back to `ls -1` which
 * returns name-only rows (size=0, mtime=0, isDirectory=false, perms=empty).
 */
class ShellFileBrowser(private val client: SshClient) {

    data class Entry(
        val name: String,
        val size: Long,
        val modifiedTimeSeconds: Long,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val permissions: String,
        val owner: String = "",
        val group: String = "",
    )

    /**
     * List [path] on the remote. Returns a flat list excluding "." and "..".
     * Throws [IllegalStateException] if neither the long listing nor the
     * fallback produces any parseable rows.
     */
    suspend fun list(path: String): List<Entry> {
        val quoted = shellQuote(path)
        val longCmd = "LC_ALL=C ls -la --time-style=full-iso -- $quoted"
        val longResult = client.execCommand(longCmd)
        if (longResult.exitStatus == 0) {
            val parsed = parseLong(longResult.stdout)
            if (parsed.isNotEmpty() || longResult.stdout.trim().startsWith("total")) return parsed
        } else {
            Log.w(TAG, "ls -la exit=${longResult.exitStatus}: ${longResult.stderr.take(200)}")
        }

        // Fallback: names only
        val shortResult = client.execCommand("LC_ALL=C ls -1 -- $quoted")
        if (shortResult.exitStatus != 0) {
            throw IllegalStateException(
                "ls failed (long exit=${longResult.exitStatus}, short exit=${shortResult.exitStatus}): ${shortResult.stderr.take(200)}"
            )
        }
        return shortResult.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { name ->
                Entry(
                    name = name,
                    size = 0,
                    modifiedTimeSeconds = 0,
                    isDirectory = false,   // unknown; UI falls back on file icon
                    isSymlink = false,
                    permissions = "",
                )
            }
            .toList()
    }

    private fun parseLong(output: String): List<Entry> {
        val result = mutableListOf<Entry>()
        for (line in output.lineSequence()) {
            if (line.isEmpty() || line.startsWith("total ")) continue
            val entry = parseLongLine(line) ?: continue
            if (entry.name == "." || entry.name == "..") continue
            result += entry
        }
        return result
    }

    /**
     * Parse a single `ls -la --time-style=full-iso` row, e.g.:
     *
     *   drwxr-xr-x 2 ian ian 4096 2026-04-15 19:37:20.123456789 +0000 dirname
     *   -rw-r--r-- 1 ian ian  123 2026-04-15 19:37:20.000000000 +0000 file with spaces
     *   lrwxrwxrwx 1 ian ian    6 2026-04-15 19:37:20.000000000 +0000 link -> target
     *
     * Returns null if the line doesn't match the expected layout.
     */
    private fun parseLongLine(line: String): Entry? {
        // Split the row into at most 9 columns; filename keeps trailing spaces.
        // Columns: perms, links, user, group, size, date, time+frac, tz, name...
        val parts = splitColumns(line, maxSplits = 8) ?: return null
        if (parts.size < 9) return null

        val perms = parts[0]
        if (perms.length < 10) return null
        val type = perms[0]
        val size = parts[4].toLongOrNull() ?: return null
        val dateStr = parts[5]
        val timeStr = parts[6].substringBefore('.')   // drop nanoseconds
        val tzStr = parts[7]
        val nameRaw = parts[8]

        // Symlinks: "name -> target" — keep just the name.
        val isSymlink = type == 'l'
        val name = if (isSymlink) nameRaw.substringBefore(" -> ") else nameRaw

        val mtimeSeconds = parseIsoDateTime("$dateStr $timeStr $tzStr") ?: 0L
        val isDirectory = type == 'd'
        return Entry(
            name = name,
            size = size,
            modifiedTimeSeconds = mtimeSeconds,
            isDirectory = isDirectory,
            isSymlink = isSymlink,
            permissions = perms,
            owner = parts[2],
            group = parts[3],
        )
    }

    /**
     * Split on runs of whitespace, producing at most [maxSplits]+1 tokens.
     * Mirrors `"foo  bar  baz".split(Regex("\\s+"), limit = 3)` but is
     * easier to reason about for fixed-column output.
     */
    private fun splitColumns(line: String, maxSplits: Int): List<String>? {
        val result = mutableListOf<String>()
        var i = 0
        val len = line.length
        // Skip leading spaces
        while (i < len && line[i] == ' ') i++
        while (result.size < maxSplits && i < len) {
            val start = i
            while (i < len && line[i] != ' ') i++
            result += line.substring(start, i)
            while (i < len && line[i] == ' ') i++
        }
        if (i < len) result += line.substring(i).trimEnd()
        return if (result.size < 2) null else result
    }

    private fun parseIsoDateTime(full: String): Long? {
        // "2026-04-15 19:37:20 +0000"
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
            val d: Date = fmt.parse(full) ?: return@runCatching null
            d.time / 1000
        }.getOrNull()
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
