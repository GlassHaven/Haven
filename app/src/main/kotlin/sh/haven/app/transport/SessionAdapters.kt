package sh.haven.app.transport

import sh.haven.core.session.ForegroundSessionInfo
import sh.haven.core.session.Session
import sh.haven.core.session.SessionStatus
import sh.haven.core.session.Transport

/**
 * Shared plumbing for the transport adapters in this package. Each manager
 * keeps its own `SessionState` type; these map them onto the unified views
 * that [sh.haven.core.ssh.SessionManagerRegistry] and the foreground
 * notification consume.
 */
internal data class UnifiedSession(
    override val sessionId: String,
    override val profileId: String,
    override val label: String,
    override val status: SessionStatus,
    override val transport: Transport,
    override val sessionName: String? = null,
    override val sessionManagerLabel: String? = null,
) : Session

internal data class SessionInfo(
    override val profileId: String,
    override val label: String,
) : ForegroundSessionInfo

/**
 * Every manager declares its own status enum with the same constant names as
 * [SessionStatus]; matching by name keeps them independent of each other.
 */
internal fun mapStatus(name: String): SessionStatus = SessionStatus.valueOf(name)
