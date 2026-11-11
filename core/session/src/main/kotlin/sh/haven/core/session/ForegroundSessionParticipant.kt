package sh.haven.core.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Minimal view of an active session as it appears in the foreground notification.
 * Only the fields the notification needs — transport-specific session state stays
 * in each manager's own SessionState type.
 */
interface ForegroundSessionInfo {
    val profileId: String
    val label: String
}

/**
 * Implemented (via adapter or directly) by every session manager whose sessions
 * count as "active connections" for the foreground notification.
 */
interface ForegroundSessionParticipant {
    /** Sessions in CONNECTED / CONNECTING / RECONNECTING state. */
    val activeSessions: List<ForegroundSessionInfo>

    /**
     * #757: emits whenever the active-session set may have changed, so the
     * foreground notification can be re-posted when a session ENDS, not only
     * when one starts.
     */
    val changes: Flow<Unit>
        get() = emptyFlow()

    /** Tear down all sessions; called on Disconnect All and on service destruction. */
    fun disconnectAll()
}
