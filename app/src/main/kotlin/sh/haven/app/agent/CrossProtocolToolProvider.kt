package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.mcp.McpError
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SessionStatus
import sh.haven.core.ssh.Transport
import sh.haven.core.wayland.WaylandBridge
import sh.haven.feature.sftp.SftpEntry
import sh.haven.feature.sftp.transport.TransportSelector

/**
 * Cross-protocol verbs (VISION §1b): tools whose whole point is that Haven
 * sits between two otherwise-unconnected surfaces.
 *
 * - `save_workspace` — the MCP half of the save-workspace dialog: snapshot
 *   every currently-connected transport session (and a running Wayland
 *   compositor) into a named workspace the user (or compose_workspace)
 *   can relaunch later. Same capture rules as WorkspaceViewModel:
 *   CONNECTED sessions only, per-transport Kind mapping, terminal
 *   session-manager names remembered so restore reattaches by name.
 *   Deliberately NOT the interactive dialog — an agent can't pick from a
 *   checklist, so the tool captures everything live. If nothing is
 *   connected it fails rather than saving an empty workspace.
 *
 * - `mirror_directory_with_fallback` — copy a directory tree between two
 *   different backends (SSH → rclone, local → SMB, …) via the same
 *   TransportSelector the file browser uses; if the primary destination
 *   is unreachable, retry the whole copy against a fallback destination.
 *   Copy semantics are the small-file surface ([FileBackend.readBytes] /
 *   [writeBytes]): files above [maxFileMb] are skipped and reported, not
 *   silently dropped. Existing destination files with the same size are
 *   skipped so a re-run resumes cheaply.
 *
 * `move_session` (also named in §1b) is NOT here: Haven has no
 * session-migration machinery to hang a tool on — moving a session between
 * devices/transport means a new subsystem (transfer a PTY's scrollback +
 * foreground state to a session on another transport), not a verb over
 * existing pieces. It stays a vision candidate rather than a stub.
 */
internal class CrossProtocolToolProvider(
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val workspaceRepository: WorkspaceRepository,
    private val transportSelector: TransportSelector,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "save_workspace" to ToolHandler(
            description = "Snapshot every currently-connected transport session (terminals, file browsers, desktops) plus a running Wayland compositor into a named workspace that list_workspaces/compose_workspace can relaunch. Connects nothing and launches nothing — it records what is live right now, the same capture the Connections screen's save dialog performs. Fails when nothing is connected; returns saved:false + existingWorkspaceId when the name is taken and overwrite is not set.",
            inputSchema = objectSchema {
                string("name", "Workspace name (trimmed; blank is rejected).", required = true)
                boolean("overwrite", "If a workspace with this name already exists, replace its items instead of returning saved:false. Default false.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Save the current sessions as workspace \"${args.optString("name", "?")}\"?"
            },
        ) { args -> saveWorkspace(args) },

        "mirror_directory_with_fallback" to ToolHandler(
            description = "Copy a directory tree from one file backend to another (profileIds from list_connections — SSH, SMB, rclone, Reticulum, or \"local\"), creating missing destination directories. If the primary destination backend cannot be resolved, the copy is retried against fallbackProfileId/fallbackPath. Files already present in the destination with the same size are skipped (so a re-run resumes); files above maxFileMb (default 16, cap 64) are skipped and reported because the copy uses the small-file byte-array surface. Returns copied/skipped/failed counts plus per-failure reasons — verify the counts before declaring success.",
            inputSchema = objectSchema {
                string("srcProfileId", "Backend to copy from.", required = true)
                string("srcPath", "Directory to copy (backend-relative).", required = true)
                string("dstProfileId", "Primary backend to copy into.", required = true)
                string("dstPath", "Destination directory (created if missing).", required = true)
                string("fallbackProfileId", "Fallback backend used only when the primary destination cannot be resolved.")
                string("fallbackPath", "Destination directory on the fallback backend.")
                string("pattern", "Only copy files whose name contains this substring (case-insensitive). Directories are still recursed.")
                integer("maxFileMb", "Skip files larger than this (MiB). Default 16, cap 64.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val fb = args.optString("fallbackProfileId")
                "Copy ${args.optString("srcPath", "?")} → ${args.optString("dstPath", "?")}" +
                    (if (fb.isNotBlank()) " (fallback: $fb)" else "") + "?"
            },
        ) { args -> mirrorDirectory(args) },
    )

    // --- save_workspace ---

    private suspend fun saveWorkspace(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        if (name.isBlank()) throw McpError(-32602, "name is required")
        val overwrite = args.optBoolean("overwrite", false)

        val existing = workspaceRepository.observeAll().first().firstOrNull { it.name == name }
        if (existing != null && !overwrite) {
            return JSONObject().apply {
                put("saved", false)
                put("existingWorkspaceId", existing.id)
                put("note", "A workspace named \"$name\" already exists. Call again with overwrite:true to replace its items.")
            }
        }

        val items = captureLiveItems(workspaceId = existing?.id ?: "")
        if (items.isEmpty()) {
            throw McpError(
                -32603,
                "Nothing to save: no connected transport sessions and no running Wayland compositor",
            )
        }
        val profile = existing?.copy(name = name, updatedAt = System.currentTimeMillis())
            ?: WorkspaceProfile(name = name)
        workspaceRepository.save(profile, items.map { it.copy(workspaceId = profile.id) })

        val arr = JSONArray()
        for (item in items) {
            arr.put(item.kind.name.lowercase() + (item.connectionProfileId?.let { " ($it)" } ?: ""))
        }
        return JSONObject().apply {
            put("saved", true)
            put("workspaceId", profile.id)
            put("name", profile.name)
            put("itemCount", items.size)
            put("items", arr)
            put("note", "Launch later with compose_workspace.")
        }
    }

    /** Same per-transport Kind mapping as WorkspaceViewModel. */
    internal fun workspaceKind(transport: Transport): WorkspaceItem.Kind? = when (transport) {
        Transport.SSH, Transport.MOSH, Transport.ET, Transport.RETICULUM, Transport.LOCAL,
        Transport.BTSERIAL, Transport.BLESERIAL, Transport.USBSERIAL,
        -> WorkspaceItem.Kind.TERMINAL
        Transport.SMB -> WorkspaceItem.Kind.FILE_BROWSER
        Transport.RDP -> WorkspaceItem.Kind.DESKTOP
        Transport.MAIL, Transport.RCLONE -> null
    }

    private suspend fun captureLiveItems(workspaceId: String): List<WorkspaceItem> =
        withContext(Dispatchers.IO) {
            val sessions = sessionManagerRegistry.allSessions
                .filter { it.status == SessionStatus.CONNECTED }
            val items = mutableListOf<WorkspaceItem>()
            for ((index, session) in sessions.withIndex()) {
                val kind = workspaceKind(session.transport) ?: continue
                items += WorkspaceItem(
                    workspaceId = workspaceId,
                    kind = kind,
                    connectionProfileId = session.profileId,
                    sessionName = if (kind == WorkspaceItem.Kind.TERMINAL) session.sessionName else null,
                    sortOrder = index,
                )
            }
            if (waylandIsRunning()) {
                items += WorkspaceItem(
                    workspaceId = workspaceId,
                    kind = WorkspaceItem.Kind.WAYLAND,
                    connectionProfileId = null,
                    sortOrder = items.size,
                )
            }
            items
        }

    private fun waylandIsRunning(): Boolean = try {
        WaylandBridge.isCompositorRunning()
    } catch (e: UnsatisfiedLinkError) {
        // Native lib absent (unit tests); treat as not running.
        false
    }

    // --- mirror_directory_with_fallback ---

    private data class MirrorTarget(val profileId: String, val path: String)

    private suspend fun mirrorDirectory(args: JSONObject): JSONObject {
        val srcProfileId = args.optString("srcProfileId")
        val srcPath = args.optString("srcPath")
        if (srcProfileId.isBlank() || srcPath.isBlank()) {
            throw McpError(-32602, "srcProfileId and srcPath are required")
        }
        val maxFileMb = args.optInt("maxFileMb", 16).coerceIn(1, 64)
        val pattern = args.optString("pattern").takeIf { it.isNotBlank() }?.lowercase()

        val src = transportSelector.resolveFileBackend(srcProfileId)
            ?: throw McpError(-32603, "No connected file backend for source profile $srcProfileId")

        // Primary destination; fall back only when the primary can't be
        // resolved at all (not when individual files fail — a partial copy
        // to the primary must not be silently abandoned to a fallback).
        var primaryResolutionFailure: String? = null
        val dstProfileId = args.optString("dstProfileId")
        if (dstProfileId.isBlank()) throw McpError(-32602, "dstProfileId is required")
        val primary = transportSelector.resolveFileBackend(dstProfileId)
        val target: MirrorTarget
        val dstBackend: sh.haven.feature.sftp.transport.FileBackend
        if (primary == null) {
            primaryResolutionFailure = "No connected file backend for destination profile $dstProfileId"
            val fbProfileId = args.optString("fallbackProfileId")
            if (fbProfileId.isBlank()) throw McpError(-32603, primaryResolutionFailure)
            val fb = transportSelector.resolveFileBackend(fbProfileId)
                ?: throw McpError(
                    -32603,
                    "$primaryResolutionFailure; fallback profile $fbProfileId is also unresolvable",
                )
            val fbPath = args.optString("fallbackPath")
            if (fbPath.isBlank()) throw McpError(-32602, "fallbackProfileId given without fallbackPath")
            target = MirrorTarget(fbProfileId, fbPath)
            dstBackend = fb.backend
        } else {
            val dstPath = args.optString("dstPath")
            if (dstPath.isBlank()) throw McpError(-32602, "dstPath is required")
            target = MirrorTarget(dstProfileId, dstPath)
            dstBackend = primary.backend
        }
        val usedFallback = primaryResolutionFailure != null

        val result = withContext(Dispatchers.IO) {
            copyTree(src.backend, srcPath, dstBackend, target.path, pattern, maxFileMb * 1024L * 1024L)
        }
        return JSONObject().apply {
            put("source", "$srcProfileId:$srcPath")
            put("destination", "${target.profileId}:${target.path}")
            put("usedFallback", usedFallback)
            if (usedFallback) put("primaryResolutionFailure", primaryResolutionFailure)
            put("copied", result.copied)
            put("skippedSameSize", result.skippedSameSize)
            put("skippedTooLarge", result.skippedTooLarge)
            put("failed", result.failed)
            put("files", JSONArray().also { arr -> result.files.forEach { arr.put(it) } })
            put("failures", JSONArray().also { arr -> result.failures.forEach { arr.put(it) } })
            put("note", "Skipped-same-size files were already present in the destination; re-run resumes.")
        }
    }

    private class MirrorResult(
        var copied: Int = 0,
        var skippedSameSize: Int = 0,
        var skippedTooLarge: Int = 0,
        var failed: Int = 0,
        val files: MutableList<String> = mutableListOf(),
        val failures: MutableList<String> = mutableListOf(),
    )

    /**
     * Depth-first copy with the same symlink rule as the file browser's
     * paste walk: a top-level directory symlink is followed, one
     * discovered during the walk is skipped so link cycles terminate.
     */
    private suspend fun copyTree(
        src: sh.haven.feature.sftp.transport.FileBackend,
        srcPath: String,
        dst: sh.haven.feature.sftp.transport.FileBackend,
        dstPath: String,
        pattern: String?,
        maxFileBytes: Long,
    ): MirrorResult {
        val result = MirrorResult()
        val createdDirs = HashSet<String>()

        suspend fun ensureDir(path: String) {
            if (path.isBlank() || path == "/" || path in createdDirs) return
            ensureDir(path.substringBeforeLast('/', missingDelimiterValue = ""))
            if (path !in createdDirs) {
                dst.mkdir(path)
                createdDirs.add(path)
            }
        }

        suspend fun walk(entry: SftpEntry, destPath: String, isTopLevel: Boolean) {
            val recurse = entry.isDirectory && (isTopLevel || !entry.isSymlink)
            if (!recurse) {
                if (entry.isDirectory) return // symlinked dir treated as leaf → skip content
                if (pattern != null && !entry.name.lowercase().contains(pattern)) return
                try {
                    if (entry.size > maxFileBytes) {
                        result.skippedTooLarge++
                        result.files.add("$entry.path (skipped: ${entry.size} bytes > limit)")
                        return
                    }
                    val existing = runCatching { dst.stat(destPath) }.getOrNull()
                    if (existing != null && !existing.isDirectory && existing.size == entry.size) {
                        result.skippedSameSize++
                        return
                    }
                    ensureDir(destPath.substringBeforeLast('/', missingDelimiterValue = ""))
                    dst.writeBytes(destPath, src.readBytes(entry.path))
                    result.copied++
                    result.files.add(entry.path)
                } catch (e: Exception) {
                    result.failed++
                    result.failures.add("${entry.path}: ${e.message ?: e.javaClass.simpleName}")
                }
                return
            }
            val children = try {
                src.list(entry.path)
            } catch (e: Exception) {
                result.failed++
                result.failures.add("${entry.path}/: ${e.message ?: e.javaClass.simpleName}")
                return
            }
            for (child in children) {
                walk(child, destPath.trimEnd('/') + "/" + child.name, isTopLevel = false)
            }
        }

        val roots = src.list(srcPath)
        for (root in roots) {
            walk(root, dstPath.trimEnd('/') + "/" + root.name, isTopLevel = true)
        }
        return result
    }
}