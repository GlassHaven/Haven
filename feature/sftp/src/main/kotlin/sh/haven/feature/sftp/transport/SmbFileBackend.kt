package sh.haven.feature.sftp.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.smb.SmbClient
import sh.haven.feature.sftp.SftpEntry

/**
 * [FileBackend] over a connected [SmbClient]. Constructed per-resolution
 * with the client baked in — the selector looks up the active client for
 * the profile via [sh.haven.core.smb.SmbSessionManager] and hands it here.
 */
class SmbFileBackend(
    private val client: SmbClient,
) : FileBackend {

    override val label: String = "SMB"

    override suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        client.listDirectory(path).map { entry ->
            SftpEntry(
                name = entry.name,
                path = entry.path,
                isDirectory = entry.isDirectory,
                size = entry.size,
                modifiedTime = entry.modifiedTime,
                permissions = entry.permissions,
            )
        }
    }
}
