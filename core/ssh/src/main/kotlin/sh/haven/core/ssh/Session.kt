package sh.haven.core.ssh

/**
 * Universal view of a session across all transports. Each manager's
 * transport-specific SessionState is mapped to this view by SessionManagerRegistry.
 *
 * Cross-cutting concerns (agent inspection, audit logging, future workspace
 * restore) operate against this interface rather than per-transport state types.
 */
interface Session {
    val sessionId: String
    val profileId: String
    val label: String
    val status: SessionStatus
    val transport: Transport
}

enum class SessionStatus { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }

enum class Transport { SSH, MOSH, ET, RETICULUM, LOCAL, RDP, SMB }
