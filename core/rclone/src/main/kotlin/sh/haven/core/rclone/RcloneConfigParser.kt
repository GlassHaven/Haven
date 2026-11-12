package sh.haven.core.rclone

/** One remote section parsed from an `rclone.conf` file (#251). */
data class ParsedRemote(
    val name: String,
    /** rclone backend type, e.g. "drive", "s3". Empty if the section had no `type`. */
    val type: String,
    /** Remaining config keys verbatim (already-obscured secrets included). */
    val params: Map<String, String>,
)

/** Outcome of parsing an `rclone.conf` (#251). */
sealed interface RcloneConfigParseResult {
    data class Success(val remotes: List<ParsedRemote>) : RcloneConfigParseResult

    /**
     * The file is a password-encrypted rclone config (`RCLONE_ENCRYPT_V0`).
     * We can't import it without the config password, which v1 doesn't ask for.
     */
    data object Encrypted : RcloneConfigParseResult
}

/**
 * Parses the standard INI-shaped `rclone.conf` so a user can import their
 * Linux remotes into Haven without re-configuring them (#251).
 *
 * Single-line `key = value` only — which is all rclone writes; OAuth tokens
 * sit on one line as raw JSON, so we split on the FIRST `=` to keep values
 * (base64, `=`-padded tokens) intact. Values are copied verbatim, including
 * already-obscured passwords, so the import must create remotes with
 * `noObscure` to avoid double-obscuring.
 */
object RcloneConfigParser {

    fun parse(text: String): RcloneConfigParseResult {
        val remotes = linkedMapOf<String, ParsedRemote>()
        var name: String? = null
        var type = ""
        var params = mutableMapOf<String, String>()

        fun flush() {
            name?.let { remotes[it] = ParsedRemote(it, type, params) }
            name = null
            type = ""
            params = mutableMapOf()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("RCLONE_ENCRYPT_V0")) return RcloneConfigParseResult.Encrypted

            if (line.startsWith("[") && line.endsWith("]")) {
                flush()
                name = line.substring(1, line.length - 1).trim()
                continue
            }

            val eq = line.indexOf('=')
            if (eq <= 0 || name == null) continue // stray line outside a section, or no key
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key.equals("type", ignoreCase = true)) type = value else params[key] = value
        }
        flush()
        return RcloneConfigParseResult.Success(remotes.values.toList())
    }
}
