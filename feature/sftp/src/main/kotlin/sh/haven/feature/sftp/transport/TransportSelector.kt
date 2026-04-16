package sh.haven.feature.sftp.transport

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.SshSessionManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransportSelector"

/**
 * Chooses a [RemoteFileTransport] for an SSH profile, honouring the per-
 * profile [ConnectionProfile.fileTransportEnum] preference plus an "auto"
 * fallback from SFTP → SCP when the SFTP subsystem is unavailable.
 *
 * Each [resolve] call is cheap; callers are expected to invoke it before
 * every operation so that a failed SFTP channel open naturally promotes
 * the profile to SCP for that run.
 */
@Singleton
class TransportSelector @Inject constructor(
    private val sessionManager: SshSessionManager,
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
}
