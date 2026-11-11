package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.app.workspace.WorkspaceLauncher
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.preferences.SnippetOps
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.mcp.McpError

/**
 * Workspace listing/launching (`list_workspaces`, `compose_workspace`) and
 * terminal keyboard-toolbar snippet management (`list_snippets`, `set_snippet`).
 */
internal class WorkspaceAndSnippetToolProvider(
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceLauncher: WorkspaceLauncher,
    private val preferencesRepository: UserPreferencesRepository,
) : ToolProvider {

    private fun workspaceLabel(workspaceId: String): String =
        runBlocking {
            workspaceRepository.getWorkspace(workspaceId)?.profile?.name
        } ?: workspaceId

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "list_workspaces" to ToolHandler(
            description = "List the user's saved workspace profiles — named bundles of terminal sessions, file-browser tabs, remote desktops, and Wayland that compose_workspace can reopen in one shot. Each entry has id, name, and itemCount. The kinds of items inside come from the same Kind enum the UI uses (TERMINAL / FILE_BROWSER / DESKTOP / WAYLAND).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listWorkspaces() },

        "compose_workspace" to ToolHandler(
            description = "Launch every item of a saved workspace through the same WorkspaceLauncher the user's tap goes through — terminal sessions, file-browser tabs, remote desktops, and the Wayland tab open in dependency-friendly order (TERMINAL first so tunneled DESKTOPs attach to live SSH sessions). Returns the workspace id and item count; progress is surfaced live in the Connections screen banner.",
            inputSchema = objectSchema {
                string("workspaceId", "Workspace id from list_workspaces.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val id = args.optString("workspaceId")
                val name = workspaceLabel(id)
                "Open workspace \"$name\"?"
            },
        ) { args -> composeWorkspace(args) },

        "list_snippets" to ToolHandler(
            description = "List terminal toolbar snippets (custom send-key macros reachable from the scissors button). " +
                "Returns each snippet's label, send (the literal text/escape sequence it types), and placement: " +
                "\"row1\"/\"row2\" (has a dedicated toolbar button on that row) or \"library\" (in the scissors sheet only, no button). " +
                "Manage them with set_snippet.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSnippets() },

        "set_snippet" to ToolHandler(
            description = "Add, move, update or delete a terminal toolbar snippet (#244). `label` is required. " +
                "To add/update: pass `send` (the text to type; JSON escapes like \\n for Enter and \\u001b for Esc work) and optional " +
                "`placement` — \"row1\"/\"row2\" gives it a toolbar button on that row, \"library\" (the default) keeps it in the scissors " +
                "sheet only. Re-passing an existing label moves/updates it. To remove it from both the toolbar and the library, pass " +
                "`delete`:true. Returns the affected snippet's resulting placement and the full snippet list.",
            inputSchema = objectSchema {
                string("label", "Snippet label (its name; also the button caption when placed on a row).", required = true)
                string("send", "Text the snippet types. Required for a new snippet. JSON escapes work: \\n = Enter, \\u001b = Esc.")
                string("placement", "\"row1\", \"row2\" (toolbar button) or \"library\" (scissors-only; default).")
                boolean("delete", "Remove the snippet from the toolbar and the library entirely.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val label = args.optString("label")
                if (args.optBoolean("delete")) "Delete toolbar snippet \"$label\"?"
                else "Add or update toolbar snippet \"$label\"?"
            },
        ) { args -> setSnippet(args) },
    )

    private suspend fun listWorkspaces(): JSONObject {
        val profiles = workspaceRepository.observeAll().first()
        val arr = JSONArray()
        for (profile in profiles) {
            val items = workspaceRepository.observeItems(profile.id).first()
            val kindCounts = items.groupingBy { it.kind.name }.eachCount()
            arr.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("itemCount", items.size)
                put("kinds", JSONObject().apply {
                    for ((kind, n) in kindCounts) put(kind, n)
                })
                put("createdAt", profile.createdAt)
                put("updatedAt", profile.updatedAt)
            })
        }
        return JSONObject().apply {
            put("count", profiles.size)
            put("workspaces", arr)
        }
    }

    private suspend fun composeWorkspace(args: JSONObject): JSONObject {
        val workspaceId = args.optString("workspaceId").ifEmpty {
            throw McpError(-32602, "Missing required argument: workspaceId")
        }
        val workspace = workspaceRepository.getWorkspace(workspaceId)
            ?: throw McpError(-32602, "No workspace with id $workspaceId")
        workspaceLauncher.launch(workspaceId)
        val finalState = workspaceLauncher.state.value
        return JSONObject().apply {
            put("workspaceId", workspaceId)
            put("workspaceName", workspace.profile.name)
            put("itemCount", workspace.items.size)
            put("outcome", finalState::class.simpleName ?: "Unknown")
        }
    }

    private fun snippetPlacement(layout: ToolbarLayout, item: ToolbarItem.Custom): String = when {
        layout.row1.contains(item) -> "row1"
        layout.row2.contains(item) -> "row2"
        else -> "library"
    }

    private fun snippetsArray(layout: ToolbarLayout, library: List<ToolbarItem.Custom>): JSONArray {
        val arr = JSONArray()
        SnippetOps.allSnippets(layout, library).forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("label", s.label)
                    put("send", s.send)
                    put("placement", snippetPlacement(layout, s))
                },
            )
        }
        return arr
    }

    private suspend fun listSnippets(): JSONObject = withContext(Dispatchers.IO) {
        val layout = preferencesRepository.toolbarLayout.first()
        val library = preferencesRepository.snippetLibrary.first()
        JSONObject().apply { put("snippets", snippetsArray(layout, library)) }
    }

    private suspend fun setSnippet(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val label = args.optString("label").trim()
        if (label.isEmpty()) throw IllegalArgumentException("label required")
        val layout = preferencesRepository.toolbarLayout.first()
        val library = preferencesRepository.snippetLibrary.first()
        val all = SnippetOps.allSnippets(layout, library)
        val sendArg = if (args.has("send")) args.getString("send") else null

        if (args.optBoolean("delete", false)) {
            val target = all.firstOrNull { it.label == label && (sendArg == null || it.send == sendArg) }
                ?: throw IllegalArgumentException("No snippet with label \"$label\"")
            val (newLayout, newLib) = SnippetOps.delete(layout, library, target)
            preferencesRepository.setToolbarLayout(newLayout)
            preferencesRepository.setSnippetLibrary(newLib)
            return@withContext JSONObject().apply {
                put("action", "deleted")
                put("label", label)
                put("snippets", snippetsArray(newLayout, newLib))
            }
        }

        val existing = all.firstOrNull { it.label == label }
        val send = sendArg
            ?: existing?.send
            ?: throw IllegalArgumentException("send required for new snippet \"$label\"")
        val item = ToolbarItem.Custom(label, send)
        val baseLayout = ToolbarLayout(
            layout.rows.map { r -> r.filterNot { it is ToolbarItem.Custom && it.label == label } },
        )
        val baseLib = library.filterNot { it.label == label }
        val rowIndex = when (args.optString("placement", "library").lowercase()) {
            "row1", "r1", "1", "top" -> 0
            "row2", "r2", "2", "bottom" -> 1
            else -> null
        }
        val (newLayout, newLib) = SnippetOps.place(baseLayout, baseLib, item, rowIndex)
        preferencesRepository.setToolbarLayout(newLayout)
        preferencesRepository.setSnippetLibrary(newLib)
        JSONObject().apply {
            put("action", if (existing == null) "added" else "updated")
            put("label", label)
            put("placement", snippetPlacement(newLayout, item))
            put("snippets", snippetsArray(newLayout, newLib))
        }
    }
}
