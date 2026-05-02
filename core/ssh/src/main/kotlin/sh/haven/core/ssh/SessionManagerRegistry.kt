package sh.haven.core.ssh

import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rdp.RdpSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.smb.SmbSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all transport session managers.
 *
 * Provides operations that apply across all transports, preventing
 * bugs where a new transport is added but forgotten in disconnect/cleanup paths.
 * When adding a new transport, add it here and all call sites are covered.
 */
@Singleton
class SessionManagerRegistry @Inject constructor(
    private val ssh: SshSessionManager,
    private val reticulum: ReticulumSessionManager,
    private val mosh: MoshSessionManager,
    private val et: EtSessionManager,
    private val smb: SmbSessionManager,
    private val local: LocalSessionManager,
    private val rdp: RdpSessionManager,
) {
    /** Disconnect all sessions for a profile across all transports. */
    fun disconnectProfile(profileId: String) {
        ssh.removeAllSessionsForProfile(profileId)
        reticulum.removeAllSessionsForProfile(profileId)
        mosh.removeAllSessionsForProfile(profileId)
        et.removeAllSessionsForProfile(profileId)
        smb.removeAllSessionsForProfile(profileId)
        local.removeAllSessionsForProfile(profileId)
        rdp.removeAllSessionsForProfile(profileId)
    }

    /** True if any transport has active (connected/connecting) sessions. */
    fun hasActiveSessions(): Boolean =
        ssh.hasActiveSessions ||
            reticulum.activeSessions.isNotEmpty() ||
            mosh.activeSessions.isNotEmpty() ||
            et.activeSessions.isNotEmpty() ||
            local.activeSessions.isNotEmpty() ||
            rdp.activeSessions.isNotEmpty() ||
            smb.activeSessions.isNotEmpty()

    /**
     * All sessions across all transports as a unified [Session] view.
     * Includes inactive sessions (DISCONNECTED, ERROR) so consumers can present
     * a full registered-session list, not just live ones.
     */
    val allSessions: List<Session>
        get() = ssh.sessions.value.values.map { it.toSession() } +
            reticulum.sessions.value.values.map { it.toSession() } +
            mosh.sessions.value.values.map { it.toSession() } +
            et.sessions.value.values.map { it.toSession() } +
            smb.sessions.value.values.map { it.toSession() } +
            local.sessions.value.values.map { it.toSession() } +
            rdp.sessions.value.values.map { it.toSession() }

    /** All sessions belonging to a single profile, across all transports. */
    fun sessionsForProfile(profileId: String): List<Session> =
        allSessions.filter { it.profileId == profileId }
}

private data class UnifiedSession(
    override val sessionId: String,
    override val profileId: String,
    override val label: String,
    override val status: SessionStatus,
    override val transport: Transport,
) : Session

private fun mapStatus(name: String): SessionStatus = SessionStatus.valueOf(name)

private fun SshSessionManager.SessionState.toSession() =
    UnifiedSession(sessionId, profileId, label, mapStatus(status.name), Transport.SSH)

private fun MoshSessionManager.SessionState.toSession() =
    UnifiedSession(sessionId, profileId, label, mapStatus(status.name), Transport.MOSH)

private fun EtSessionManager.SessionState.toSession() =
    UnifiedSession(sessionId, profileId, label, mapStatus(status.name), Transport.ET)

private fun ReticulumSessionManager.SessionState.toSession() =
    UnifiedSession(sessionId, profileId, label, mapStatus(status.name), Transport.RETICULUM)

private fun LocalSessionManager.SessionState.toSession() =
    UnifiedSession(sessionId, profileId, label, mapStatus(status.name), Transport.LOCAL)

private fun RdpSessionManager.SessionState.toSession() =
    UnifiedSession(sessionId, profileId, label, mapStatus(status.name), Transport.RDP)

private fun SmbSessionManager.SessionState.toSession() =
    UnifiedSession(sessionId, profileId, label, mapStatus(status.name), Transport.SMB)
