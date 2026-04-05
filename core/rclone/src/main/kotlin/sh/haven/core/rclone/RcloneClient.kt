package sh.haven.core.rclone

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.rclone.bridge.RcloneBridge
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RcloneClient"

/**
 * High-level Kotlin API over rclone's RC (Remote Control) JSON-RPC interface.
 * All methods are blocking and should be called from a background thread.
 */
@Singleton
class RcloneClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var initialized = false

    /** Initialize the rclone library. Safe to call multiple times. */
    fun initialize() {
        if (initialized) return
        val configDir = File(context.filesDir, "rclone")
        configDir.mkdirs()
        val configPath = File(configDir, "rclone.conf").absolutePath
        RcloneBridge.initialize(configPath)
        initialized = true
        Log.d(TAG, "Initialized with config: $configPath")
    }

    /** Shut down the rclone library. */
    fun shutdown() {
        if (!initialized) return
        RcloneBridge.shutdown()
        initialized = false
    }

    // ── Config operations ───────────────────────────────────────────────

    /** List all configured remote names. */
    fun listRemotes(): List<String> {
        val result = rpc("config/listremotes")
        val remotes = result.optJSONArray("remotes") ?: return emptyList()
        return (0 until remotes.length()).map { remotes.getString(it) }
    }

    /** Get configuration for a specific remote. */
    fun getRemoteConfig(name: String): Map<String, String> {
        val result = rpc("config/get", JSONObject().put("name", name))
        val map = mutableMapOf<String, String>()
        result.keys().forEach { key -> map[key] = result.optString(key, "") }
        return map
    }

    /** List available provider types with their metadata. */
    fun listProviders(): List<ProviderInfo> {
        val result = rpc("config/providers")
        val providers = result.optJSONArray("providers") ?: return emptyList()
        return (0 until providers.length()).map { i ->
            val p = providers.getJSONObject(i)
            ProviderInfo(
                name = p.getString("Name"),
                description = p.getString("Description"),
                prefix = p.optString("Prefix", ""),
                options = parseOptions(p.optJSONArray("Options")),
            )
        }
    }

    /**
     * Create a new remote configuration.
     *
     * For OAuth providers, this initiates the OAuth flow. The returned
     * [ConfigState] may contain an [ConfigState.authUrl] that needs to be
     * opened in a browser.
     */
    fun createRemote(name: String, type: String, parameters: Map<String, String> = emptyMap()): ConfigState {
        val params = JSONObject()
        params.put("name", name)
        params.put("type", type)
        params.put("parameters", JSONObject(parameters))
        val result = rpc("config/create", params)
        return parseConfigState(result)
    }

    /** Update an existing remote's configuration. */
    fun updateRemote(name: String, parameters: Map<String, String>) {
        val params = JSONObject()
        params.put("name", name)
        params.put("parameters", JSONObject(parameters))
        rpc("config/update", params)
    }

    /** Delete a remote configuration. */
    fun deleteRemote(name: String) {
        rpc("config/delete", JSONObject().put("name", name))
    }

    // ── File operations ─────────────────────────────────────────────────

    /**
     * List entries in a directory.
     *
     * @param remote remote name (e.g. "gdrive")
     * @param path   directory path within the remote (e.g. "/" or "Documents")
     */
    fun listDirectory(remote: String, path: String): List<RcloneFileEntry> {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        val result = rpc("operations/list", params)
        val list = result.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            RcloneFileEntry(
                name = item.getString("Name"),
                path = item.getString("Path"),
                size = item.optLong("Size", 0),
                mimeType = item.optString("MimeType", ""),
                modTime = item.optString("ModTime", ""),
                isDir = item.optBoolean("IsDir", false),
            )
        }
    }

    /** Create a directory. */
    fun mkdir(remote: String, path: String) {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        rpc("operations/mkdir", params)
    }

    /**
     * Copy a single file between remotes or within a remote.
     *
     * For local filesystem paths, pass the absolute directory as the remote
     * and the filename as the path (e.g. srcRemote="/data/.../cache", srcPath="file.txt").
     * For cloud remotes, pass the remote name (e.g. "gdrive") and the full path.
     */
    fun copyFile(
        srcRemote: String, srcPath: String,
        dstRemote: String, dstPath: String,
    ) {
        val params = JSONObject()
        // Local paths (absolute) don't get a colon suffix; named remotes do
        params.put("srcFs", if (srcRemote.startsWith("/")) srcRemote else "$srcRemote:")
        params.put("srcRemote", srcPath.trimStart('/'))
        params.put("dstFs", if (dstRemote.startsWith("/")) dstRemote else "$dstRemote:")
        params.put("dstRemote", dstPath.trimStart('/'))
        rpc("operations/copyfile", params)
    }

    /** Delete a single file. */
    fun deleteFile(remote: String, path: String) {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        rpc("operations/deletefile", params)
    }

    /** Delete a directory and all its contents. */
    fun deleteDir(remote: String, path: String) {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        rpc("operations/purge", params)
    }

    /** Get information about the remote (total/used/free space). */
    fun about(remote: String): RemoteInfo {
        val result = rpc("operations/about", JSONObject().put("fs", "$remote:"))
        return RemoteInfo(
            total = result.optLong("total", -1),
            used = result.optLong("used", -1),
            free = result.optLong("free", -1),
        )
    }

    // ── Transfer stats ──────────────────────────────────────────────────

    /** Get current transfer statistics. */
    fun getStats(): TransferStats {
        val result = rpc("core/stats")
        return TransferStats(
            bytes = result.optLong("bytes", 0),
            totalBytes = result.optLong("totalBytes", 0),
            speed = result.optDouble("speed", 0.0),
            transfers = result.optInt("transfers", 0),
            totalTransfers = result.optInt("totalTransfers", 0),
            errors = result.optInt("errors", 0),
        )
    }

    // ── Sync operations ──────────────────────────────────────────────

    /** Active sync job ID, stored here (singleton) so it survives ViewModel recreation. */
    @Volatile
    var activeSyncJobId: Long? = null

    /**
     * Start an async rclone sync/copy/move operation.
     * Returns the job ID for status polling.
     */
    fun startSync(config: SyncConfig): Long {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val params = JSONObject()
        params.put("srcFs", config.srcFs)
        params.put("dstFs", config.dstFs)
        params.put("_async", true)

        // Build filter object
        val filters = config.filters
        if (filters.includePatterns.isNotEmpty() || filters.excludePatterns.isNotEmpty()
            || filters.minSize != null || filters.maxSize != null
        ) {
            val filterObj = JSONObject()
            if (filters.includePatterns.isNotEmpty()) {
                filterObj.put("IncludeRule", org.json.JSONArray(filters.includePatterns))
            }
            if (filters.excludePatterns.isNotEmpty()) {
                filterObj.put("ExcludeRule", org.json.JSONArray(filters.excludePatterns))
            }
            filters.minSize?.let { filterObj.put("MinSize", it) }
            filters.maxSize?.let { filterObj.put("MaxSize", it) }
            params.put("_filter", filterObj)
        }

        // Build config object (dry run, bandwidth limit)
        if (config.dryRun || filters.bandwidthLimit != null) {
            val configObj = JSONObject()
            if (config.dryRun) configObj.put("DryRun", true)
            filters.bandwidthLimit?.let { configObj.put("BwLimit", it) }
            params.put("_config", configObj)
        }

        val result = rpc(config.mode.rcMethod, params)
        val jobId = result.getLong("jobid")
        activeSyncJobId = jobId
        return jobId
    }

    /** Query the status of an async rclone job. */
    fun getJobStatus(jobId: Long): SyncJobStatus {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val result = rpc("job/status", JSONObject().put("jobid", jobId))
        return SyncJobStatus(
            jobId = jobId,
            finished = result.optBoolean("finished", false),
            success = result.optBoolean("success", false),
            error = result.optString("error", "").ifEmpty { null },
            duration = result.optDouble("duration", 0.0),
        )
    }

    /** Cancel a running async rclone job. */
    fun cancelJob(jobId: Long) {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        try {
            rpc("job/stop", JSONObject().put("jobid", jobId))
        } catch (_: Exception) {
            // Job may already be finished
        }
        if (activeSyncJobId == jobId) activeSyncJobId = null
    }

    /** Reset global transfer statistics (call before starting a new sync). */
    fun resetStats() {
        if (!initialized) return
        try {
            rpc("core/stats-reset", JSONObject())
        } catch (_: Exception) {
            // Not critical
        }
    }

    // ── Media server ──────────────────────────────────────────────────

    /**
     * Start a local HTTP server that streams files from the given remote
     * via rclone VFS. Returns the port number the server is listening on.
     * The server binds to 127.0.0.1 (loopback only).
     */
    fun startMediaServer(remote: String, preferredPort: Int = 0): Int {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val result = RcloneBridge.startMediaServer(remote, preferredPort.toLong())
        if (!result.isOk) {
            val error = try {
                JSONObject(result.output).optString("error", result.output)
            } catch (_: Exception) {
                result.output
            }
            throw RcloneException("startMediaServer", result.status, error)
        }
        return JSONObject(result.output).getInt("port")
    }

    /**
     * Get the current media server port for the given remote, or null if not running.
     * Does not start a new server.
     */
    fun mediaServerPort(remote: String): Int? {
        if (!initialized) return null
        val result = RcloneBridge.mediaServerStatus()
        if (!result.isOk) return null
        val json = JSONObject(result.output)
        val port = json.optInt("port", 0)
        if (port == 0) return null
        val running = json.optString("remote", "")
        return if (running == remote) port else null
    }

    /** Stop the local media streaming HTTP server if running. */
    fun stopMediaServer() {
        if (!initialized) return
        RcloneBridge.stopMediaServer()
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val result = RcloneBridge.rpc(method, params.toString())
        if (!result.isOk) {
            val error = try {
                JSONObject(result.output).optString("error", result.output)
            } catch (_: Exception) {
                result.output
            }
            Log.w(TAG, "RPC $method failed (${result.status}): $error")
            throw RcloneException(method, result.status, error)
        }
        return try {
            JSONObject(result.output)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun parseOptions(arr: JSONArray?): List<ProviderOption> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ProviderOption(
                name = o.getString("Name"),
                help = o.optString("Help", ""),
                provider = o.optString("Provider", ""),
                default = o.optString("Default", ""),
                required = o.optBoolean("Required", false),
                isPassword = o.optBoolean("IsPassword", false),
                advanced = o.optBoolean("Advanced", false),
                type = o.optString("Type", "string"),
                examples = parseExamples(o.optJSONArray("Examples")),
            )
        }
    }

    private fun parseExamples(arr: JSONArray?): List<ProviderOptionExample> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            ProviderOptionExample(
                value = e.optString("Value", ""),
                help = e.optString("Help", ""),
            )
        }
    }

    private fun parseConfigState(json: JSONObject): ConfigState {
        return ConfigState(
            state = json.optString("State", ""),
            option = json.optJSONObject("Option")?.let { opt ->
                ConfigOption(
                    name = opt.optString("Name", ""),
                    help = opt.optString("Help", ""),
                    default = opt.optString("Default", ""),
                    required = opt.optBoolean("Required", false),
                    isPassword = opt.optBoolean("IsPassword", false),
                    type = opt.optString("Type", ""),
                )
            },
            error = json.optString("error", ""),
        )
    }
}

class RcloneException(
    val method: String,
    val statusCode: Int,
    val error: String,
) : Exception("rclone $method failed ($statusCode): $error")
