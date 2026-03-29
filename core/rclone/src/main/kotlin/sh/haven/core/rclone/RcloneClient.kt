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
