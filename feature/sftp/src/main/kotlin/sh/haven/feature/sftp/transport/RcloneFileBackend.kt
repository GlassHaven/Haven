package sh.haven.feature.sftp.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.rclone.RcloneClient
import sh.haven.feature.sftp.SftpEntry
import java.time.Instant

/**
 * [FileBackend] over a connected rclone remote. The remote name (e.g.
 * `gdrive:`) is captured at resolution time; rclone treats the empty
 * string as remote root, so the synthetic `"/"` from `currentPath` is
 * normalised here rather than at the call site.
 */
class RcloneFileBackend(
    private val client: RcloneClient,
    private val remoteName: String,
) : FileBackend {

    override val label: String = "Rclone"

    override suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        val rclonePath = if (path == "/" || path.isEmpty()) "" else path
        client.listDirectory(remoteName, rclonePath).map { entry ->
            val modTime = try {
                Instant.parse(entry.modTime).epochSecond
            } catch (_: Exception) {
                0L
            }
            SftpEntry(
                name = entry.name,
                path = if (rclonePath.isEmpty()) entry.name else "${rclonePath.trimEnd('/')}/${entry.name}",
                isDirectory = entry.isDir,
                size = entry.size,
                modifiedTime = modTime,
                permissions = if (entry.isDir) "drwxr-xr-x" else "-rw-r--r--",
                mimeType = entry.mimeType,
            )
        }
    }
}
