package sh.haven.core.ssh

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
 * count as "active connections" for the foreground notification. SshConnectionService
 * iterates the full Set of participants — adding a new transport means contributing
 * one @IntoSet binding in ForegroundSessionParticipantModule, not editing the service.
 */
interface ForegroundSessionParticipant {
    /** Sessions in CONNECTED / CONNECTING / RECONNECTING state. */
    val activeSessions: List<ForegroundSessionInfo>

    /** Tear down all sessions; called on Disconnect All and on service destruction. */
    fun disconnectAll()
}
