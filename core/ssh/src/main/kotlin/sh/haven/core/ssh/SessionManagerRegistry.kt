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
            rdp.activeSessions.isNotEmpty()
}
