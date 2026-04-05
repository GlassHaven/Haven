package sh.haven.core.rclone

/** Metadata about an rclone storage provider (e.g. Google Drive, S3). */
data class ProviderInfo(
    /** Internal name, e.g. "drive", "s3", "dropbox". */
    val name: String,
    /** Human-readable description. */
    val description: String,
    /** Prefix for this provider's environment variables. */
    val prefix: String,
    /** Configuration options supported by this provider. */
    val options: List<ProviderOption>,
)

/** A single configuration option for a provider. */
data class ProviderOption(
    val name: String,
    val help: String,
    val provider: String,
    val default: String,
    val required: Boolean,
    val isPassword: Boolean,
    val advanced: Boolean,
    val type: String,
    val examples: List<ProviderOptionExample>,
)

/** Example value for a provider option (shown in dropdowns). */
data class ProviderOptionExample(
    val value: String,
    val help: String,
)

/** A file or directory entry returned by rclone's operations/list. */
data class RcloneFileEntry(
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String,
    val modTime: String,
    val isDir: Boolean,
)

/** Space usage information for a remote. */
data class RemoteInfo(
    val total: Long,
    val used: Long,
    val free: Long,
)

/** Current transfer statistics. */
data class TransferStats(
    val bytes: Long,
    val totalBytes: Long,
    val speed: Double,
    val transfers: Int,
    val totalTransfers: Int,
    val errors: Int,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** State returned by config/create during interactive configuration. */
data class ConfigState(
    val state: String,
    val option: ConfigOption?,
    val error: String,
) {
    /** Extract an OAuth URL from the option help text, if present. */
    val authUrl: String?
        get() = option?.help?.let { help ->
            val urlRegex = Regex("""https?://\S+""")
            urlRegex.find(help)?.value
        }
}

/** A configuration question requiring user input. */
data class ConfigOption(
    val name: String,
    val help: String,
    val default: String,
    val required: Boolean,
    val isPassword: Boolean,
    val type: String,
)

// ── Sync operations ─────────────────────────────────────────────────

enum class SyncMode(val rcMethod: String, val label: String) {
    COPY("sync/copy", "Copy"),
    SYNC("sync/sync", "Sync"),
    MOVE("sync/move", "Move"),
}

data class SyncFilters(
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val minSize: String? = null,
    val maxSize: String? = null,
    val bandwidthLimit: String? = null,
)

data class SyncConfig(
    val srcFs: String,
    val dstFs: String,
    val mode: SyncMode,
    val filters: SyncFilters = SyncFilters(),
    val dryRun: Boolean = false,
)

data class SyncJobStatus(
    val jobId: Long,
    val finished: Boolean,
    val success: Boolean,
    val error: String?,
    val duration: Double,
)

data class SyncProgress(
    val jobId: Long,
    val mode: SyncMode,
    val bytes: Long,
    val totalBytes: Long,
    val speed: Double,
    val eta: Long,
    val transfersCompleted: Int,
    val totalTransfers: Int,
    val errors: Int,
    val finished: Boolean,
    val success: Boolean,
    val errorMessage: String?,
    val dryRun: Boolean,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val etaFormatted: String
        get() {
            if (eta <= 0) return ""
            val h = eta / 3600
            val m = (eta % 3600) / 60
            val s = eta % 60
            return when {
                h > 0 -> "${h}h${m}m"
                m > 0 -> "${m}m${s}s"
                else -> "${s}s"
            }
        }
}
