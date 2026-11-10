package sh.haven.feature.sftp.transport

import sh.haven.feature.sftp.SftpEntry
import sh.haven.feature.sftp.root.RootFsClient
import java.io.InputStream

/**
 * Opt-in `su` / `app_process`-backed [FileBackend] exposing the full Android `/`
 * filesystem inside the interactive Files tab (#799).
 *
 * Deliberately NOT registered in [TransportSelector.resolveFileBackend]: only
 * `SftpViewModel` resolves this backend when `UserPreferencesRepository.rootFilesEnabled`
 * is true and the user explicitly selects the `Root (/)` tab, keeping MCP agent
 * file tools and `HavenDocumentsProvider` isolated from UID-0 filesystem access.
 */
class RootFileBackend(
    private val client: RootFsClient,
) : FileBackend {

    override val label: String = "Root"

    override suspend fun list(path: String): List<SftpEntry> =
        client.list(path.ifBlank { "/" })

    override suspend fun delete(path: String, isDirectory: Boolean) =
        client.delete(path)

    override suspend fun mkdir(path: String) =
        client.mkdir(path)

    override suspend fun rename(from: String, to: String) =
        client.rename(from, to)

    override suspend fun readBytes(path: String): ByteArray =
        client.readBytes(path)

    override suspend fun writeBytes(path: String, data: ByteArray) =
        client.writeBytes(path, data)

    override suspend fun stat(path: String): SftpEntry =
        client.stat(path)

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        client.openRangeInputStream(path, offset)

    suspend fun writeStream(
        path: String,
        input: InputStream,
        append: Boolean = false,
        startOffset: Long = 0L,
        onBytes: ((Long) -> Unit)? = null,
    ) = client.writeStream(path, input, append = append, startOffset = startOffset, onBytes = onBytes)

    suspend fun chmod(path: String, mode: Int) =
        client.chmod(path, mode)

    suspend fun chown(path: String, ownerSpec: String) =
        client.chown(path, ownerSpec)
}
