package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SyncProfileRepository
import sh.haven.core.mcp.McpError
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.rclone.RcloneConfigParseResult
import sh.haven.core.rclone.RcloneConfigParser
import sh.haven.core.ssh.SessionManagerRegistry

/**
 * The rclone MCP tools (#mcp-backbone Stage 5, Layer E): remote-config CRUD
 * (list/configure/update/delete/import), directory listing, sync jobs
 * (start/status/cancel) with transfer stats, and saved sync profiles. The
 * rclone-only helpers ensureRcloneReady + syncProfileToJson travel with it.
 * Config secrets stay in rclone's own config; only remote names/metadata are
 * returned. [connectionRepository] / [sessionManagerRegistry] are needed for
 * the #269 remote↔connection-profile pairing (a configured remote gets a
 * paired Haven profile, and deletion rolls back the inverse orphan).
 */
internal class RcloneToolProvider(
    private val rcloneClient: RcloneClient,
    private val syncProfileRepository: SyncProfileRepository,
    private val connectionRepository: ConnectionRepository,
    private val sessionManagerRegistry: SessionManagerRegistry,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "list_rclone_remotes" to ToolHandler(
            description = "List rclone cloud storage remotes configured in Haven.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listRcloneRemotes() },

        "list_rclone_provider_options" to ToolHandler(
            description = "List a credentials-based rclone provider's basic config fields — the non-advanced options needed to configure a non-OAuth remote (ftp, sftp, webdav, s3, b2, mega, filen, …). Each entry has name, help, required, isPassword, default, type. Feed the collected values into configure_rclone_remote's `parameters`. OAuth providers (drive, dropbox, onedrive, box, pcloud) are configured via the in-app browser sign-in, not this. (#181)",
            inputSchema = objectSchema {
                string("provider", "rclone provider/type, e.g. 'ftp', 'sftp', 's3', 'filen'.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> listRcloneProviderOptions(args) },

        "configure_rclone_remote" to ToolHandler(
            description = "Create (or replace) a credentials-based rclone remote and verify it by listing the root. Pass remoteName, provider (ftp/sftp/webdav/s3/b2/mega/filen/…), and parameters — an option→value map (see list_rclone_provider_options; rclone obscures password fields server-side). For OAuth providers (drive/dropbox/…) use the in-app browser sign-in instead. Returns { created, verified, entryCount } or an error. Makes the remote usable by the rclone list/sync tools. (#181)",
            inputSchema = objectSchema {
                string("remoteName", "Name for the remote in rclone.conf, e.g. 'myftp'. Replaces an existing remote of the same name.", required = true)
                string("provider", "rclone provider/type, e.g. 'ftp', 'sftp', 's3', 'filen'.", required = true)
                property(
                    "parameters",
                    JSONObject().put("type", "object").put("description", "Option→value map for the provider (from list_rclone_provider_options), e.g. {\"host\":\"…\",\"user\":\"…\",\"pass\":\"…\",\"port\":\"2121\"}. Password fields are obscured by rclone."),
                    required = true,
                )
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Configure rclone remote \"${args.optString("remoteName")}\" (${args.optString("provider")})?"
            },
        ) { args -> configureRcloneRemote(args) },

        "delete_rclone_remote" to ToolHandler(
            description = "Delete an rclone remote from rclone.conf by name, and any RCLONE connection profile that references it. The inverse of configure_rclone_remote / the rclone-config import — use it to clean up a remote (e.g. a test or a failed/ghost import that left a remote with no usable profile). Returns { deleted, remoteName, removedProfiles }. deleted is false if no such remote existed (matching profiles are still removed).",
            inputSchema = objectSchema {
                string("remoteName", "Remote name in rclone.conf (see list_rclone_remotes).", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete rclone remote \"${args.optString("remoteName")}\" and any profile using it?" },
        ) { args -> deleteRcloneRemote(args) },

        "import_rclone_config" to ToolHandler(
            description = "Import remotes from a Linux rclone.conf (the headless equivalent of the in-app Import rclone config dialog, #269). Pass configText (the file contents). Each chosen remote becomes an rclone remote (token/creds copied verbatim, non-interactively — OAuth remotes don't block on the browser flow) plus a matching RCLONE connection profile; a half-created remote is rolled back on failure so a failed import leaves no ghost. Skips typeless sections and names already configured. Optional `names` limits the import to those remote names. Returns { created, skipped, failed }. Returns an error if the config is password-encrypted.",
            inputSchema = objectSchema {
                string("configText", "Contents of an rclone.conf to import.", required = true)
                stringArray("names", "Optional: only import remotes with these names. Omit to import all importable remotes.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Import rclone remotes from a pasted rclone.conf?" },
        ) { args -> importRcloneConfig(args) },

        "update_rclone_remote" to ToolHandler(
            description = "Update an existing rclone remote's config in place (rclone config/update) — unlike configure_rclone_remote, which replaces the whole remote. Pass remoteName and a parameters option→value map of just the fields to change (rclone obscures password fields). Returns { updated, remoteName }.",
            inputSchema = objectSchema {
                string("remoteName", "Existing remote name (see list_rclone_remotes).", required = true)
                property(
                    "parameters",
                    JSONObject().put("type", "object").put("description", "Option→value map of fields to change, e.g. {\"host\":\"…\"}."),
                    required = true,
                )
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Update rclone remote \"${args.optString("remoteName")}\"?" },
        ) { args -> updateRcloneRemote(args) },

        "list_rclone_directory" to ToolHandler(
            description = "DEPRECATED: prefer list_directory(profileId=..., path=...). List files and subdirectories at a given path on an rclone remote. Returns name, isDir, size, mimeType, and modTime for each entry.",
            inputSchema = objectSchema {
                string("remote", "Name of the rclone remote, e.g. 'gdrive'.", required = true)
                string("path", "Path within the remote, relative to the remote root. Use '' for the root.")
            },
        ) { args -> listRcloneDirectory(args) },

        "start_rclone_sync" to ToolHandler(
            description = "Start an async rclone transfer between two remote paths. mode=copy adds new/updated files (no deletes); mode=sync (a.k.a. \"Mirror\" in the UI) makes destination identical to source and deletes extras; mode=move copies then removes source files. srcFs/dstFs use rclone's remote-prefixed notation, e.g. \"gdrive:Backup/Photos\" or \"gdrive:\" for the remote root. Returns { jobId, mode } — poll get_rclone_sync_status to read finished/success and the transfer/delete counters. Honours the same optional filter and dryRun fields the SFTP sync dialog exposes.",
            inputSchema = objectSchema {
                string("srcFs", "Source remote path, e.g. \"gdrive:Backup/Photos\".", required = true)
                string("dstFs", "Destination remote path, e.g. \"gdrive:Mirror/Photos\".", required = true)
                string("mode", "copy = add/update only, sync = mirror (deletes extras in dst), move = copy then delete from src.", enum = listOf("copy", "sync", "move"), required = true)
                boolean("dryRun", "If true, simulate without writing.")
                stringArray("includePatterns", "Optional include globs, e.g. [\"*.jpg\"].")
                stringArray("excludePatterns", "Optional exclude globs.")
                string("minSize", "Optional minimum file size, e.g. \"1K\", \"5M\".")
                string("maxSize", "Optional maximum file size.")
                string("bandwidthLimit", "Optional bandwidth limit, e.g. \"10M\".")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val mode = args.optString("mode", "?")
                val src = args.optString("srcFs", "?")
                val dst = args.optString("dstFs", "?")
                val dry = if (args.optBoolean("dryRun")) " (dry run)" else ""
                "rclone $mode$dry: $src → $dst?"
            },
        ) { args -> startRcloneSync(args) },

        "get_rclone_sync_status" to ToolHandler(
            description = "Poll the status of an async rclone job started by start_rclone_sync. Returns finished/success/error plus live transfer stats: bytes, totalBytes, speed, transfers, totalTransfers, errors, deletes, deletedDirs. If jobId is omitted, reports on the most recently started job (or returns active=false if none).",
            inputSchema = objectSchema {
                integer("jobId", "Job id returned by start_rclone_sync. Optional — defaults to the active job.")
            },
        ) { args -> getRcloneSyncStatus(args) },

        "cancel_rclone_sync" to ToolHandler(
            description = "Cancel a running rclone job started by start_rclone_sync. If jobId is omitted, cancels the active job. No-op if the job is already finished or never existed.",
            inputSchema = objectSchema {
                integer("jobId", "Job id to cancel. Optional — defaults to the active job.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Cancel the running rclone sync job?" },
        ) { args -> cancelRcloneSync(args) },

        "get_rclone_stats" to ToolHandler(
            description = "Return rclone's global transfer counters (bytes, totalBytes, speed, transfers, totalTransfers, errors, deletes, deletedDirs). These reset only when reset_rclone_stats is called or a new sync resets them at start.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getRcloneStats() },

        "reset_rclone_stats" to ToolHandler(
            description = "Reset rclone's global transfer counters to zero. Useful when running ad-hoc operations outside start_rclone_sync (which already resets on start).",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> resetRcloneStats() },

        "list_saved_sync_profiles" to ToolHandler(
            description = "List the user's saved rclone sync configurations (#159) — the named src/dst/mode/filters bundles surfaced in the SFTP folder-sync dialog's dropdown. Returns id, name, srcFs, dstFs, mode (copy/sync/move), include/exclude patterns, optional minSize/maxSize/bandwidthLimit, createdAt, lastRunAt. Sorted most-recently-run first.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSavedSyncProfiles() },

        "save_sync_profile" to ToolHandler(
            description = "Create or update a named rclone sync configuration (#159). Pass an `id` to overwrite an existing one; omit it to create. mode accepts copy/sync/move (or \"mirror\" as a sync alias). includePatterns/excludePatterns are arrays of glob strings. Returns the saved profile's id and the full resolved fields. Mutates Haven state, gated by EVERY_CALL consent.",
            inputSchema = objectSchema {
                string("id", "Existing profile id to overwrite. Omit to create a new one.")
                string("name", "Display name shown in the dropdown.", required = true)
                string("srcFs", "Source remote path, e.g. \"gdrive:Backup/Photos\".", required = true)
                string("dstFs", "Destination remote path.", required = true)
                string("mode", "copy/sync/move; \"mirror\" is accepted as a sync alias.", enum = listOf("copy", "sync", "move"), required = true)
                stringArray("includePatterns")
                stringArray("excludePatterns")
                string("minSize")
                string("maxSize")
                string("bandwidthLimit")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val name = args.optString("name", "?")
                val mode = args.optString("mode", "?")
                val verb = if (args.has("id") && !args.isNull("id")) "Update" else "Save"
                "$verb \"$name\" ($mode: ${args.optString("srcFs", "?")} → ${args.optString("dstFs", "?")})?"
            },
        ) { args -> saveSyncProfile(args) },

        "delete_sync_profile" to ToolHandler(
            description = "Delete a saved rclone sync configuration by id. The dialog's dropdown updates immediately. EVERY_CALL consent because it's destructive (the user's saved config is gone after).",
            inputSchema = objectSchema {
                string("id", "Profile id from list_saved_sync_profiles.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete saved sync configuration ${args.optString("id")}?" },
        ) { args -> deleteSyncProfile(args) },
    )

    private fun ensureRcloneReady() {
        rcloneClient.initialize()
    }

    private fun listRcloneRemotes(): JSONObject {
        return try {
            ensureRcloneReady()
            val remotes = rcloneClient.listRemotes()
            JSONObject().apply {
                put("count", remotes.size)
                put("remotes", JSONArray().apply { remotes.forEach { put(it) } })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list rclone remotes: ${e.message}")
        }
    }

    private suspend fun startRcloneSync(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val srcFs = args.optString("srcFs").ifEmpty {
            throw McpError(-32602, "Missing required argument: srcFs")
        }
        val dstFs = args.optString("dstFs").ifEmpty {
            throw McpError(-32602, "Missing required argument: dstFs")
        }
        val modeStr = args.optString("mode").ifEmpty {
            throw McpError(-32602, "Missing required argument: mode")
        }
        val mode = when (modeStr.lowercase()) {
            "copy" -> sh.haven.core.rclone.SyncMode.COPY
            "sync", "mirror" -> sh.haven.core.rclone.SyncMode.SYNC
            "move" -> sh.haven.core.rclone.SyncMode.MOVE
            else -> throw McpError(-32602, "Unknown mode: $modeStr (expected copy/sync/move)")
        }
        val includes = args.optJSONArray("includePatterns")?.let { arr ->
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val excludes = args.optJSONArray("excludePatterns")?.let { arr ->
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val filters = sh.haven.core.rclone.SyncFilters(
            includePatterns = includes,
            excludePatterns = excludes,
            minSize = args.optString("minSize").ifBlank { null },
            maxSize = args.optString("maxSize").ifBlank { null },
            bandwidthLimit = args.optString("bandwidthLimit").ifBlank { null },
        )
        val config = sh.haven.core.rclone.SyncConfig(
            srcFs = srcFs,
            dstFs = dstFs,
            mode = mode,
            filters = filters,
            dryRun = args.optBoolean("dryRun", false),
        )
        try {
            ensureRcloneReady()
            rcloneClient.resetStats()
            val jobId = rcloneClient.startSync(config)
            JSONObject().apply {
                put("jobId", jobId)
                put("mode", mode.name.lowercase())
                put("rcMethod", mode.rcMethod)
                put("srcFs", srcFs)
                put("dstFs", dstFs)
                put("dryRun", config.dryRun)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to start rclone sync: ${e.message}")
        }
    }

    private suspend fun getRcloneSyncStatus(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            ensureRcloneReady()
            val jobId = if (args.has("jobId") && !args.isNull("jobId")) {
                args.optLong("jobId")
            } else {
                rcloneClient.activeSyncJobId ?: return@withContext JSONObject().apply {
                    put("active", false)
                }
            }
            val status = rcloneClient.getJobStatus(jobId)
            val stats = rcloneClient.getStats()
            JSONObject().apply {
                put("active", true)
                put("jobId", jobId)
                put("finished", status.finished)
                put("success", status.success)
                status.error?.let { put("error", it) }
                put("duration", status.duration)
                put("stats", JSONObject().apply {
                    put("bytes", stats.bytes)
                    put("totalBytes", stats.totalBytes)
                    put("speed", stats.speed)
                    put("transfers", stats.transfers)
                    put("totalTransfers", stats.totalTransfers)
                    put("errors", stats.errors)
                    put("deletes", stats.deletes)
                    put("deletedDirs", stats.deletedDirs)
                    if (stats.lastError.isNotEmpty()) put("lastError", stats.lastError)
                })
                // Per-file errors with their object names — the diagnostic that
                // core/stats.lastError can't give (#157).
                if (stats.errors > 0) {
                    val failed = rcloneClient.getErroredTransfers()
                    if (failed.isNotEmpty()) {
                        put("failedFiles", org.json.JSONArray().apply {
                            failed.forEach { f ->
                                put(JSONObject().apply {
                                    put("name", f.name)
                                    put("error", f.error)
                                })
                            }
                        })
                    }
                }
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to get rclone sync status: ${e.message}")
        }
    }

    private suspend fun cancelRcloneSync(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            ensureRcloneReady()
            val jobId = if (args.has("jobId") && !args.isNull("jobId")) {
                args.optLong("jobId")
            } else {
                rcloneClient.activeSyncJobId ?: return@withContext JSONObject().apply {
                    put("cancelled", false)
                    put("reason", "no active job")
                }
            }
            rcloneClient.cancelJob(jobId)
            JSONObject().apply {
                put("cancelled", true)
                put("jobId", jobId)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to cancel rclone sync: ${e.message}")
        }
    }

    private fun getRcloneStats(): JSONObject {
        return try {
            ensureRcloneReady()
            val s = rcloneClient.getStats()
            JSONObject().apply {
                put("bytes", s.bytes)
                put("totalBytes", s.totalBytes)
                put("speed", s.speed)
                put("transfers", s.transfers)
                put("totalTransfers", s.totalTransfers)
                put("errors", s.errors)
                put("deletes", s.deletes)
                put("deletedDirs", s.deletedDirs)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to get rclone stats: ${e.message}")
        }
    }

    private fun resetRcloneStats(): JSONObject {
        return try {
            ensureRcloneReady()
            rcloneClient.resetStats()
            JSONObject().apply { put("reset", true) }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to reset rclone stats: ${e.message}")
        }
    }

    private suspend fun listSavedSyncProfiles(): JSONObject = withContext(Dispatchers.IO) {
        val rows = syncProfileRepository.observeAll().first()
        val arr = JSONArray()
        for (p in rows) {
            arr.put(syncProfileToJson(p))
        }
        JSONObject().apply {
            put("count", rows.size)
            put("profiles", arr)
        }
    }

    private suspend fun saveSyncProfile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val name = args.optString("name").ifBlank {
            throw McpError(-32602, "Missing required argument: name")
        }
        val srcFs = args.optString("srcFs").ifBlank {
            throw McpError(-32602, "Missing required argument: srcFs")
        }
        val dstFs = args.optString("dstFs").ifBlank {
            throw McpError(-32602, "Missing required argument: dstFs")
        }
        val modeStr = args.optString("mode").ifBlank {
            throw McpError(-32602, "Missing required argument: mode")
        }
        val mode = when (modeStr.lowercase()) {
            "copy" -> "COPY"
            "sync", "mirror" -> "SYNC"
            "move" -> "MOVE"
            else -> throw McpError(-32602, "Unknown mode: $modeStr (expected copy/sync/move)")
        }
        val includes = args.optJSONArray("includePatterns")?.let { a ->
            List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val excludes = args.optJSONArray("excludePatterns")?.let { a ->
            List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val existingId = if (args.has("id") && !args.isNull("id")) args.optString("id").ifBlank { null } else null
        val existing = existingId?.let { syncProfileRepository.getById(it) }
        val profile = sh.haven.core.data.db.entities.SyncProfile(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            srcFs = srcFs,
            dstFs = dstFs,
            mode = mode,
            includePatterns = includes.joinToString("\n"),
            excludePatterns = excludes.joinToString("\n"),
            minSize = args.optString("minSize").ifBlank { null },
            maxSize = args.optString("maxSize").ifBlank { null },
            bandwidthLimit = args.optString("bandwidthLimit").ifBlank { null },
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastRunAt = existing?.lastRunAt,
        )
        syncProfileRepository.save(profile)
        syncProfileToJson(profile)
    }

    private suspend fun deleteSyncProfile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = args.optString("id").ifBlank {
            throw McpError(-32602, "Missing required argument: id")
        }
        val existed = syncProfileRepository.getById(id) != null
        syncProfileRepository.delete(id)
        JSONObject().apply {
            put("deleted", existed)
            put("id", id)
        }
    }

    private fun syncProfileToJson(p: sh.haven.core.data.db.entities.SyncProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("srcFs", p.srcFs)
        put("dstFs", p.dstFs)
        put("mode", p.mode.lowercase())
        put("includePatterns", JSONArray().apply {
            p.includePatterns.split("\n").filter { it.isNotBlank() }.forEach { put(it) }
        })
        put("excludePatterns", JSONArray().apply {
            p.excludePatterns.split("\n").filter { it.isNotBlank() }.forEach { put(it) }
        })
        p.minSize?.let { put("minSize", it) }
        p.maxSize?.let { put("maxSize", it) }
        p.bandwidthLimit?.let { put("bandwidthLimit", it) }
        put("createdAt", p.createdAt)
        p.lastRunAt?.let { put("lastRunAt", it) }
    }

    private fun listRcloneDirectory(args: JSONObject): JSONObject {
        val remote = args.optString("remote").ifEmpty {
            throw McpError(-32602, "Missing required argument: remote")
        }
        val path = args.optString("path", "")
        return try {
            ensureRcloneReady()
            val entries = rcloneClient.listDirectory(remote, path)
            JSONObject().apply {
                put("remote", remote)
                put("path", path)
                put("count", entries.size)
                put("entries", JSONArray().apply {
                    for (e in entries) {
                        put(JSONObject().apply {
                            put("name", e.name)
                            put("path", e.path)
                            put("isDir", e.isDir)
                            put("size", e.size)
                            put("mimeType", e.mimeType)
                            put("modTime", e.modTime)
                        })
                    }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $remote:$path : ${e.message}")
        }
    }

    private fun listRcloneProviderOptions(args: JSONObject): JSONObject {
        val provider = args.optString("provider").ifEmpty {
            throw McpError(-32602, "Missing required argument: provider")
        }
        return try {
            ensureRcloneReady()
            val info = rcloneClient.listProviders().firstOrNull { it.name == provider }
                ?: throw McpError(-32603, "Unknown rclone provider: $provider")
            JSONObject().apply {
                put("provider", provider)
                put("description", info.description)
                put("options", JSONArray().apply {
                    info.options.filter { !it.advanced }.forEach { o ->
                        put(JSONObject().apply {
                            put("name", o.name)
                            put("help", o.help)
                            put("required", o.required)
                            put("isPassword", o.isPassword)
                            put("default", o.default)
                            put("type", o.type)
                        })
                    }
                })
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list provider options for $provider: ${e.message}")
        }
    }

    private fun configureRcloneRemote(args: JSONObject): JSONObject {
        val name = args.optString("remoteName").ifEmpty {
            throw McpError(-32602, "Missing required argument: remoteName")
        }
        val provider = args.optString("provider").ifEmpty {
            throw McpError(-32602, "Missing required argument: provider")
        }
        val paramsJson = args.optJSONObject("parameters")
            ?: throw McpError(-32602, "Missing required argument: parameters")
        val params = mutableMapOf<String, String>()
        paramsJson.keys().forEach { k -> params[k] = paramsJson.get(k).toString() }
        return try {
            ensureRcloneReady()
            if (name in rcloneClient.listRemotes()) rcloneClient.deleteRemote(name)
            val state = rcloneClient.createRemote(name, provider, params)
            if (state.error.isNotEmpty()) {
                throw McpError(-32603, "rclone config/create error: ${state.error}")
            }
            state.option?.let {
                throw McpError(-32603, "rclone still needs option '${it.name}' — add it to parameters")
            }
            val entries = rcloneClient.listDirectory(name, "")
            JSONObject().apply {
                put("created", true)
                put("verified", true)
                put("remoteName", name)
                put("provider", provider)
                put("entryCount", entries.size)
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "configure_rclone_remote failed: ${e.message}")
        }
    }

    private suspend fun deleteRcloneRemote(args: JSONObject): JSONObject {
        val name = args.optString("remoteName").ifBlank {
            throw McpError(-32602, "Missing required argument: remoteName")
        }
        return try {
            ensureRcloneReady()
            val existed = name in rcloneClient.listRemotes()
            if (existed) rcloneClient.deleteRemote(name)
            // Symmetric cleanup: drop any RCLONE profile pointing at this remote so
            // deletion can't leave the inverse ghost (a profile with no remote) (#269).
            val removedProfiles = mutableListOf<String>()
            for (p in connectionRepository.getAll()) {
                if (p.connectionType == "RCLONE" && p.rcloneRemoteName == name) {
                    sessionManagerRegistry.disconnectProfile(p.id)
                    connectionRepository.delete(p.id)
                    removedProfiles += p.id
                }
            }
            JSONObject().apply {
                put("deleted", existed)
                put("remoteName", name)
                put("removedProfiles", JSONArray().apply { removedProfiles.forEach { put(it) } })
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "delete_rclone_remote failed: ${e.message}")
        }
    }

    private suspend fun importRcloneConfig(args: JSONObject): JSONObject {
        val configText = args.optString("configText").ifBlank {
            throw McpError(-32602, "Missing required argument: configText")
        }
        val onlyNames = args.optJSONArray("names")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet() }
        val parsed = when (val r = RcloneConfigParser.parse(configText)) {
            is RcloneConfigParseResult.Encrypted ->
                throw McpError(-32602, "That rclone.conf is password-encrypted; decrypt it first.")
            is RcloneConfigParseResult.Success -> r.remotes
        }
        return try {
            ensureRcloneReady()
            val created = JSONArray()
            val skipped = JSONArray()
            val failed = JSONObject()
            val existing = runCatching { rcloneClient.listRemotes().toSet() }.getOrDefault(emptySet())
            for (remote in parsed) {
                if (onlyNames != null && remote.name !in onlyNames) continue
                if (remote.type.isBlank() || remote.name in existing) { skipped.put(remote.name); continue }
                try {
                    val state = rcloneClient.createRemote(
                        remote.name, remote.type, remote.params,
                        noObscure = true, nonInteractive = true,
                    )
                    if (state.error.isNotEmpty()) error(state.error)
                    connectionRepository.save(
                        ConnectionProfile(
                            label = remote.name,
                            host = "",
                            username = "",
                            connectionType = "RCLONE",
                            rcloneRemoteName = remote.name,
                            rcloneProvider = remote.type,
                        ),
                    )
                    created.put(remote.name)
                } catch (e: Exception) {
                    runCatching { rcloneClient.deleteRemote(remote.name) } // roll back any ghost
                    failed.put(remote.name, e.message ?: "failed")
                }
            }
            JSONObject().apply {
                put("created", created)
                put("skipped", skipped)
                put("failed", failed)
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "import_rclone_config failed: ${e.message}")
        }
    }

    private fun updateRcloneRemote(args: JSONObject): JSONObject {
        val name = args.optString("remoteName").ifBlank {
            throw McpError(-32602, "Missing required argument: remoteName")
        }
        val paramsJson = args.optJSONObject("parameters")
            ?: throw McpError(-32602, "Missing required argument: parameters")
        val params = mutableMapOf<String, String>()
        paramsJson.keys().forEach { k -> params[k] = paramsJson.get(k).toString() }
        return try {
            ensureRcloneReady()
            if (name !in rcloneClient.listRemotes()) {
                throw McpError(-32602, "No rclone remote named '$name' (see list_rclone_remotes).")
            }
            rcloneClient.updateRemote(name, params)
            JSONObject().apply {
                put("updated", true)
                put("remoteName", name)
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "update_rclone_remote failed: ${e.message}")
        }
    }
}
