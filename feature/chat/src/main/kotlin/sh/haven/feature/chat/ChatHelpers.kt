package sh.haven.feature.chat

import sh.haven.core.data.preferences.ChatMcpServerConfig
import sh.haven.core.openai.ChatToolSpec
import sh.haven.core.openai.ModelInfo

/**
 * Pure helpers for model search filtering and routing/flavour suffixes
 * (`:free`, `:online`, `:nitro`, `:floor`, `:extended`, `:batch`) used by
 * OpenRouter and OpenAI-compatible aggregators (#807).
 */
object ChatModelFlavours {

    val FLAVOUR_SUFFIXES: List<String> = listOf(
        ":free",
        ":online",
        ":nitro",
        ":floor",
        ":extended",
        ":batch",
    )

    /** Returns the active flavour suffix (from [FLAVOUR_SUFFIXES]) on [modelId], or null. */
    fun activeFlavour(modelId: String?): String? {
        if (modelId.isNullOrBlank()) return null
        val lower = modelId.lowercase()
        return FLAVOUR_SUFFIXES.firstOrNull { lower.endsWith(it) }
    }

    /**
     * Strips any known flavour suffix from [modelId] while preserving non-flavour
     * tags like `:7b` or `:latest` on local Ollama models.
     */
    fun stripFlavour(modelId: String): String {
        val active = activeFlavour(modelId) ?: return modelId
        return modelId.dropLast(active.length)
    }

    /**
     * Toggles [suffix] on [modelId]: if [modelId] already ends with [suffix],
     * strips it; otherwise replaces any active flavour suffix with [suffix].
     */
    fun toggleFlavour(modelId: String, suffix: String): String {
        val normalized = if (suffix.startsWith(":")) suffix.lowercase() else ":${suffix.lowercase()}"
        val base = stripFlavour(modelId)
        if (base.isBlank()) return modelId
        return if (activeFlavour(modelId) == normalized) {
            base
        } else {
            base + normalized
        }
    }

    /**
     * Filters [models] by case-insensitive substring match against [query] (or
     * against [stripFlavour] of [query], so searching `deepseek:free` still
     * surfaces `deepseek/deepseek-chat`).
     */
    fun filterModels(models: List<ModelInfo>, query: String): List<ModelInfo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return models
        val baseQuery = stripFlavour(trimmed)
        return models.filter { model ->
            model.id.contains(trimmed, ignoreCase = true) ||
                (baseQuery.isNotEmpty() && model.id.contains(baseQuery, ignoreCase = true))
        }
    }
}

/**
 * Pure parser and autocomplete matcher for inline composer slash commands (#807).
 */
object ChatSlashCommands {

    enum class CommandType {
        NEW,
        CLEAR,
        MODEL,
        SAVE,
        HISTORY,
        MCP,
    }

    data class SlashSuggestion(
        val command: String,
        val type: CommandType,
        val takesArgument: Boolean = false,
    )

    sealed interface ParsedSlashCommand {
        data object NewSession : ParsedSlashCommand
        data object ClearTranscript : ParsedSlashCommand
        data object ToggleSave : ParsedSlashCommand
        data object OpenHistory : ParsedSlashCommand
        data object OpenMcpServers : ParsedSlashCommand
        data class SelectModel(val modelOrFlavour: String?) : ParsedSlashCommand
    }

    val COMMANDS: List<SlashSuggestion> = listOf(
        SlashSuggestion("/new", CommandType.NEW),
        SlashSuggestion("/clear", CommandType.CLEAR),
        SlashSuggestion("/model", CommandType.MODEL, takesArgument = true),
        SlashSuggestion("/save", CommandType.SAVE),
        SlashSuggestion("/history", CommandType.HISTORY),
        SlashSuggestion("/mcp", CommandType.MCP),
    )

    /**
     * Returns matching slash command suggestions while the user is typing a
     * single-line command starting with `/`.
     */
    fun suggestionsFor(input: String): List<SlashSuggestion> {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("/") || trimmed.contains('\n')) return emptyList()
        val token = trimmed.substringBefore(' ').lowercase()
        return COMMANDS.filter { it.command.startsWith(token) }
    }

    /**
     * Parses [input] if it is a recognized slash command, or returns null when
     * [input] should be sent as a normal chat message.
     */
    fun parse(input: String): ParsedSlashCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/") || trimmed.contains('\n')) return null
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return when (cmd) {
            "/new" -> if (arg == null) ParsedSlashCommand.NewSession else null
            "/clear" -> if (arg == null) ParsedSlashCommand.ClearTranscript else null
            "/save" -> if (arg == null) ParsedSlashCommand.ToggleSave else null
            "/history" -> if (arg == null) ParsedSlashCommand.OpenHistory else null
            "/mcp" -> if (arg == null) ParsedSlashCommand.OpenMcpServers else null
            "/model" -> ParsedSlashCommand.SelectModel(arg)
            else -> null
        }
    }
}

/**
 * Pure router mapping tools discovered across multiple external MCP servers
 * into unique, API-valid (`^[a-zA-Z0-9_-]{1,64}$`) function specs (#807).
 */
object ChatMcpToolRouter {

    data class BoundMcpTool(
        val wireName: String,
        val server: ChatMcpServerConfig,
        val remoteToolName: String,
        val spec: ChatToolSpec,
    )

    fun bindTools(
        discovered: List<Pair<ChatMcpServerConfig, List<ChatToolSpec>>>,
    ): Map<String, BoundMcpTool> {
        val flat = discovered.flatMap { (server, tools) ->
            tools.map { tool -> server to tool }
        }
        if (flat.isEmpty()) return emptyMap()

        val slugCounts = flat.groupingBy { (_, tool) -> sanitizeIdentifier(tool.name) }.eachCount()
        val result = LinkedHashMap<String, BoundMcpTool>(flat.size)

        for ((server, tool) in flat) {
            val cleanTool = sanitizeIdentifier(tool.name)
            val baseWire = if ((slugCounts[cleanTool] ?: 0) > 1) {
                val serverSlug = sanitizeIdentifier(server.name.lowercase()).take(24).trimEnd('_')
                    .ifEmpty { "mcp" }
                "${serverSlug}__${cleanTool}".take(64)
            } else {
                cleanTool.take(64)
            }
            var candidate = baseWire
            var suffix = 2
            while (result.containsKey(candidate)) {
                val tail = "_$suffix"
                candidate = baseWire.take(64 - tail.length) + tail
                suffix++
            }
            val desc = if (tool.description.isNotBlank()) {
                "[${server.name}] ${tool.description}"
            } else {
                "[${server.name}] ${tool.name}"
            }
            result[candidate] = BoundMcpTool(
                wireName = candidate,
                server = server,
                remoteToolName = tool.name,
                spec = ChatToolSpec(
                    name = candidate,
                    description = desc,
                    inputSchemaJson = tool.inputSchemaJson,
                ),
            )
        }
        return result
    }

    private fun sanitizeIdentifier(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]+"), "_").trim('_').ifEmpty { "tool" }
}
