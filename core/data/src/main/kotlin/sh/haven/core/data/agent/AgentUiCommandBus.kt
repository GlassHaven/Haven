package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton dispatch channel for [AgentUiCommand]. The agent transport
 * (`McpTools`) publishes here when the user — via an MCP client —
 * invokes a navigation/state-promotion verb. UI collectors (`HavenNavHost`,
 * `SftpViewModel`, future feature ViewModels) subscribe and react.
 *
 * Modeled on [sh.haven.app.navigation.DebugNavEvents] but Hilt-scoped
 * because it has real (not debug-only) wiring and needs to be injectable
 * into MCP tool handlers.
 *
 * ### Buffering
 *
 * `extraBufferCapacity = 1` and `replay = 0` means:
 *   - The most recent emission is briefly held while collectors process,
 *     so a fast-following burst does not drop on a slow collector.
 *   - New collectors do **not** see prior emissions, which is the right
 *     semantics here — UI verbs are *commands*, not state, and replaying
 *     them on a screen rotation would re-fire the navigation.
 *
 * `tryEmit` is non-suspending and never blocks the caller. If a UI is
 * not currently mounted (e.g. app backgrounded) the emission is simply
 * dropped, which is the correct fail-closed behaviour for this surface.
 */
@Singleton
class AgentUiCommandBus @Inject constructor() {
    private val _commands = MutableSharedFlow<AgentUiCommand>(
        extraBufferCapacity = 1,
        replay = 0,
    )
    val commands: SharedFlow<AgentUiCommand> = _commands.asSharedFlow()

    /** Returns true when the command was buffered/delivered, false on overflow. */
    fun emit(command: AgentUiCommand): Boolean = _commands.tryEmit(command)
}
