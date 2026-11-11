package sh.haven.app.agent

import sh.haven.core.data.agent.McpToolCategory

/**
 * A cohesive domain's slice of the MCP tool registry (#mcp-backbone Stage 5,
 * Layer E). [McpTools] aggregates providers together with its own
 * not-yet-extracted tools into one registry, so consent, capability gating,
 * and dispatch stay uniform across every tool regardless of where it lives.
 *
 * Extracting a domain into a provider is how the monolithic registry file is
 * dismantled incrementally: a provider owns its tools' registrations, their
 * handler implementations, and only the dependencies those tools need —
 * without changing [McpTools]'s public surface, so the transport and every
 * server test stay untouched.
 */
interface ToolProvider {
    /**
     * Optional default Settings switch category ([McpToolCategory]) for tools
     * in this provider when neither [ToolHandler.category] nor
     * [McpToolCategory.of] specifies one.
     */
    val defaultCategory: McpToolCategory? get() = null

    /** This domain's tools, keyed by MCP tool name. */
    fun tools(): Map<String, ToolHandler>
}

/**
 * Build a deterministic [Map] of MCP tool handlers while failing fast on
 * intra-provider duplicate tool names (unlike [mapOf]/[linkedMapOf], which
 * silently overwrite the earlier registration).
 */
internal fun toolMapOf(vararg entries: Pair<String, ToolHandler>): Map<String, ToolHandler> {
    val map = LinkedHashMap<String, ToolHandler>(entries.size)
    for ((name, handler) in entries) {
        check(map.put(name, handler) == null) {
            "Duplicate MCP tool registration: $name"
        }
    }
    return map
}
