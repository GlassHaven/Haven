package sh.haven.app.agent

import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/**
 * Stateful, cross-cutting helpers shared across MCP tool providers
 * (#mcp-backbone Stage 5, Layer E). A handful of McpTools internals are needed
 * by more than one domain but belong to none of them and can't be plain
 * top-level functions (they close over McpTools state). Providers that need
 * them take a single `ToolContext` instead of threading these lambdas through
 * their constructors one by one.
 *
 * Pure shared helpers (requireIntArg, encodeCapture, desktopByIdOrThrow,
 * desktopToJson) are NOT here — they were promoted to top-level `internal`
 * functions in McpTools.kt and are called directly.
 */
internal class ToolContext(
    /** Human label for a connection profile id (falls back to the id itself). */
    val profileLabel: (String) -> String,
    /** Shared scope for fire-and-forget provider work (e.g. async DE install). */
    val backgroundScope: CoroutineScope,
    /**
     * Attach an agent-driven guest shell (the open_local_shell /
     * open_desktop_terminal handler): `(plain, desktopEnv, reuse) -> result`.
     */
    val attachAgentShell: suspend (Boolean, Map<String, String>?, Boolean) -> JSONObject,
    /** Force guest MCP tool discovery cache invalidation after service mutations. */
    val invalidateGuestTools: () -> Unit = {},
)
