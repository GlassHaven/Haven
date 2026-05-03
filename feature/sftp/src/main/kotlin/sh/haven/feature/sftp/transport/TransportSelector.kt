package sh.haven.feature.sftp.transport

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.rclone.RcloneSessionManager
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.ssh.SshSessionManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransportSelector"

/**
 * Chooses a file backend for the active profile.
 *
 * Two entry points:
 *  - [resolve] returns a [RemoteFileTransport] for SSH-backed profiles,
 *    honouring the per-profile [ConnectionProfile.fileTransportEnum]
 *    preference plus an "auto" fallback from SFTP → SCP. Callers that
 *    need upload/download/mkdir/etc still go through here.
 *  - [resolveFileBackend] returns the backend-agnostic [FileBackend] for
 *    the listing path — local, SMB, rclone, and SSH (delegating to
 *    [resolve] for the SSH branch). Issue #126 is gradually promoting
 *    operations from [RemoteFileTransport] up to [FileBackend] as they
 *    generalise across all backends.
 *
 * Each call is cheap; callers are expected to invoke a resolver before
 * every operation so that a failed SFTP channel open naturally promotes
 * the profile to SCP for that run.
 */
@Singleton
class TransportSelector @Inject constructor(
    private val sessionManager: SshSessionManager,
    private val smbSessionManager: SmbSessionManager,
    private val rcloneSessionManager: RcloneSessionManager,
    private val rcloneClient: RcloneClient,
    private val connectionRepository: ConnectionRepository,
    @ApplicationContext private val appContext: Context,
) {
    /**
     * Outcome of choosing a transport. [announceFallback] is non-null only
     * the first time an Auto profile silently drops back to SCP — the
     * caller should surface it as a one-shot snackbar.
     */
    data class Resolution(
        val transport: RemoteFileTransport,
        val announceFallback: String? = null,
    )

    /**
     * Outcome of choosing a backend-agnostic [FileBackend]. Mirrors
     * [Resolution] but typed to the narrower interface so non-SSH backends
     * can satisfy it. [announceFallback] is only ever populated by the
     * SSH branch (SFTP → SCP).
     */
    data class FileBackendResolution(
        val backend: FileBackend,
        val announceFallback: String? = null,
    )

    /** Profile IDs that have already surfaced the "SFTP → SCP" snackbar. */
    private val announcedAutoFallback = mutableSetOf<String>()

    fun resolve(profile: ConnectionProfile): Resolution? {
        return when (profile.fileTransportEnum) {
            ConnectionProfile.FileTransport.SFTP -> resolveSftp(profile)?.let { Resolution(it) }
            ConnectionProfile.FileTransport.SCP -> resolveScp(profile)?.let { Resolution(it) }
            ConnectionProfile.FileTransport.AUTO -> {
                resolveSftp(profile)?.let { return Resolution(it) }
                val scp = resolveScp(profile) ?: return null
                val announce = if (announcedAutoFallback.add(profile.id)) {
                    "SFTP unavailable — using SCP"
                } else null
                Log.d(TAG, "Auto-fallback to SCP for profile ${profile.id} (announce=$announce)")
                Resolution(scp, announceFallback = announce)
            }
        }
    }

    private fun resolveSftp(profile: ConnectionProfile): RemoteFileTransport? {
        val channel = sessionManager.openSftpForProfile(profile.id) ?: return null
        val ssh = sessionManager.getSshClientForProfile(profile.id)
        return SftpTransport({ channel }, ssh)
    }

    private fun resolveScp(profile: ConnectionProfile): RemoteFileTransport? {
        val scp = sessionManager.openScpForProfile(profile.id) ?: return null
        val ssh = sessionManager.getSshClientForProfile(profile.id) ?: return null
        val cache = File(appContext.cacheDir, "scp_spool").apply { mkdirs() }
        return ScpTransport(scp, ssh, cache)
    }

    /**
     * Pick the [FileBackend] for [profileId]. Local profiles short-circuit
     * (no DB row exists for "local"); SMB and rclone profiles look up the
     * active client/remote on their session managers; everything else
     * falls through to the SSH path via [resolve], which still requires
     * a [ConnectionProfile] from the DB.
     */
    suspend fun resolveFileBackend(profileId: String): FileBackendResolution? {
        if (profileId == "local") {
            return FileBackendResolution(LocalFileBackend(appContext))
        }
        if (smbSessionManager.isProfileConnected(profileId)) {
            val client = smbSessionManager.getClientForProfile(profileId) ?: return null
            return FileBackendResolution(SmbFileBackend(client))
        }
        if (rcloneSessionManager.isProfileConnected(profileId)) {
            val remote = rcloneSessionManager.getRemoteNameForProfile(profileId) ?: return null
            return FileBackendResolution(RcloneFileBackend(rcloneClient, remote, appContext))
        }
        val profile = connectionRepository.getById(profileId) ?: return null
        val resolution = resolve(profile) ?: return null
        return FileBackendResolution(resolution.transport, resolution.announceFallback)
    }
}
