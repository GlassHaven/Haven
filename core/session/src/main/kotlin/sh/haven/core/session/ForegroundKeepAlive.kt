package sh.haven.core.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pluggable keep-alive signal for the foreground connection service.
 *
 * Anything that needs the FGS to stay running but isn't a session
 * (e.g. the MCP agent endpoint or an active AnyConnect tunnel) implements
 * this interface and contributes it via Hilt `@IntoSet`.
 */
interface ForegroundKeepAlive {
    val isActive: Boolean

    /**
     * #757: emits whenever [isActive] may have changed, so the foreground
     * notification can follow the keep-alive up and down.
     */
    val changes: Flow<Unit>
        get() = emptyFlow()
}
