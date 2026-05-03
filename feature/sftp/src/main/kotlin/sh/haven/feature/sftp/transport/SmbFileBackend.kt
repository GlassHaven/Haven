package sh.haven.feature.sftp.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.smb.SmbClient
import sh.haven.feature.sftp.SftpEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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

    override suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        client.delete(path, isDirectory)
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        client.mkdir(path)
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        client.rename(from, to)
    }

    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArrayOutputStream()
        client.download(path, buffer) { _, _ -> }
        buffer.toByteArray()
    }

    override suspend fun writeBytes(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        ByteArrayInputStream(data).use { input ->
            client.upload(input, path, data.size.toLong()) { _, _ -> }
        }
    }
}
