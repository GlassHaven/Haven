package sh.haven.core.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.net.SocketFactory
import sh.haven.core.redact.LogRedact

private const val TAG = "SmbClient"

data class SmbFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: Long,
    val permissions: String,
)

class SmbClient : Closeable {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    /**
     * Connection parameters retained so the share can be re-established on use.
     * Over a flaky carrier (WireGuard) the underlying TCP drops and smbj closes
     * the DiskShare, but a cached handle is reused by every operation — reads
     * then fail with "DiskShare has already been closed" even though the session
     * still looks connected. Keeping the params lets [ensureShare] silently
     * reconnect. The password lives here for the session lifetime (cleared in
     * [close]); same trade-off the SSH session manager makes for reconnect.
     */
    private data class ConnParams(
        val host: String,
        val port: Int,
        val shareName: String,
        val username: String,
        val password: String,
        val domain: String,
        val socketFactory: SocketFactory?,
    )

    private var params: ConnParams? = null
    private val lock = Any()

    val isConnected: Boolean
        get() = healthy() || params != null

    /** A live share + connection, by smbj's own reckoning. */
    private fun healthy(): Boolean =
        share?.isConnected == true && connection?.isConnected == true

    /** (Re)establish client/connection/session/share from [params]. Caller holds [lock]. */
    private fun openShare() {
        val p = params ?: throw IllegalStateException("Not configured")
        // Drop any stale handles first so a reconnect doesn't leak the old ones.
        closeHandles()
        // socketFactory is non-null when the profile routes through a
        // WireGuard / Tailscale tunnel (#149). smbj dials via the configured
        // factory; for direct connections we let smbj use its default.
        val smbClient = if (p.socketFactory != null) {
            SMBClient(SmbConfig.builder().withSocketFactory(p.socketFactory).build())
        } else {
            SMBClient()
        }
        val conn = smbClient.connect(p.host, p.port)
        val auth = AuthenticationContext(p.username, p.password.toCharArray(), p.domain)
        val sess = conn.authenticate(auth)
        val diskShare = sess.connectShare(p.shareName) as DiskShare

        client = smbClient
        connection = conn
        session = sess
        share = diskShare
        Log.d(TAG, "Connected to \\\\${LogRedact.of(p.host)}\\${p.shareName}")
    }

    /**
     * Return a live [DiskShare], reconnecting first if the cached one went stale
     * (the WireGuard-drop case above). Synchronised so a reconnect is exclusive.
     */
    private fun ensureShare(): DiskShare = synchronized(lock) {
        if (!healthy()) {
            Log.w(TAG, "SMB share stale — reconnecting before use")
            openShare()
        }
        share ?: throw IllegalStateException("Not connected")
    }

    /** Close the live handles without clearing [params] (so a reconnect can reuse them). */
    private fun closeHandles() {
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null
        session = null
        connection = null
        client = null
    }

    fun connect(
        host: String,
        port: Int,
        shareName: String,
        username: String,
        password: String,
        domain: String,
        socketFactory: SocketFactory? = null,
    ) {
        params = ConnParams(host, port, shareName, username, password, domain, socketFactory)
        synchronized(lock) { openShare() }
    }

    fun listDirectory(path: String): List<SmbFileEntry> {
        val diskShare = ensureShare()
        val smbPath = toSmbPath(path)
        val entries = diskShare.list(smbPath)
        return entries
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info -> toSmbFileEntry(info, path) }
    }

    fun download(
        remotePath: String,
        output: OutputStream,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ) {
        val diskShare = ensureShare()
        val smbPath = toSmbPath(remotePath)
        val file = diskShare.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        file.use { f ->
            val size = f.fileInformation.standardInformation.endOfFile
            val inputStream = f.inputStream
            val buffer = ByteArray(65536)
            var transferred = 0L
            onProgress(0, size)
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                transferred += read
                onProgress(transferred, size)
            }
            output.flush()
        }
    }

    /**
     * Open a streaming [InputStream] over [remotePath], skipping the
     * first [offset] bytes. Caller owns the stream and must close it;
     * closing it releases the underlying smbj File handle.
     *
     * Used by the MCP `serve_file` tool (via `SmbFileBackend.openInputStream`).
     */
    fun openInputStream(remotePath: String, offset: Long = 0): InputStream {
        val diskShare = ensureShare()
        val smbPath = toSmbPath(remotePath)
        val file = diskShare.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val inner = file.inputStream
        if (offset > 0) {
            var remaining = offset
            while (remaining > 0) {
                val skipped = inner.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
        }
        // Wrap so closing the returned stream also closes the SMB file
        // handle — otherwise the share would leak open files.
        return object : InputStream() {
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun available(): Int = inner.available()
            override fun close() {
                try { inner.close() } finally { file.close() }
            }
        }
    }

    fun upload(
        input: InputStream,
        remotePath: String,
        size: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ) {
        val diskShare = ensureShare()
        val smbPath = toSmbPath(remotePath)
        val file = diskShare.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        file.use { f ->
            val outputStream = f.outputStream
            val buffer = ByteArray(65536)
            var transferred = 0L
            onProgress(0, size)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                outputStream.write(buffer, 0, read)
                transferred += read
                onProgress(transferred, size)
            }
            outputStream.flush()
        }
    }

    fun delete(path: String, isDirectory: Boolean) {
        val diskShare = ensureShare()
        val smbPath = toSmbPath(path)
        if (isDirectory) {
            diskShare.rmdir(smbPath, true)
        } else {
            diskShare.rm(smbPath)
        }
    }

    fun mkdir(path: String) {
        val diskShare = ensureShare()
        val smbPath = toSmbPath(path)
        diskShare.mkdir(smbPath)
    }

    fun rename(oldPath: String, newPath: String) {
        val diskShare = ensureShare()
        val smbOld = toSmbPath(oldPath)
        val smbNew = toSmbPath(newPath)
        val file = diskShare.openFile(
            smbOld,
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        file.use { it.rename(smbNew) }
    }

    override fun close() = synchronized(lock) {
        closeHandles()
        // Clear retained credentials/params so a closed client can't silently
        // reconnect — close() is a real teardown, not a transient drop.
        params = null
    }

    private fun toSmbPath(path: String): String {
        // SMB uses backslash paths; our UI uses forward slashes
        // Root is "" in smbj (not "/" or "\")
        val trimmed = path.trim('/')
        return trimmed.replace('/', '\\')
    }

    private fun toSmbFileEntry(
        info: FileIdBothDirectoryInformation,
        parentPath: String,
    ): SmbFileEntry {
        val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
        val childPath = parentPath.trimEnd('/') + "/" + info.fileName
        return SmbFileEntry(
            name = info.fileName,
            path = childPath,
            isDirectory = isDir,
            size = info.endOfFile,
            modifiedTime = info.lastWriteTime.toEpochMillis() / 1000,
            permissions = if (isDir) "d" else "-",
        )
    }
}
