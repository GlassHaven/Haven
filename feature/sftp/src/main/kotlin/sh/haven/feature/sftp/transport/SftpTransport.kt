package sh.haven.feature.sftp.transport

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.ssh.SshClient
import sh.haven.feature.sftp.SftpEntry

/**
 * [RemoteFileTransport] backed by JSch's SFTP channel. A thin wrapper that
 * lifts the channel operations out of SftpViewModel so the view model's
 * SSH code path becomes transport-agnostic.
 *
 * [sshClient] is optional — it is only consulted for [chown], which
 * needs a shell exec channel because JSch's `ChannelSftp.chown` requires
 * a numeric UID and users usually want to chown by name.
 */
class SftpTransport(
    private val channelProvider: () -> ChannelSftp,
    private val sshClient: SshClient? = null,
) : RemoteFileTransport {

    override val label: String = "SFTP"

    override suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        val channel = channelProvider()
        val results = mutableListOf<SftpEntry>()
        val symlinkIndices = mutableListOf<Int>()
        channel.ls(path) { lsEntry ->
            val name = lsEntry.filename
            if (name != "." && name != "..") {
                val attrs = lsEntry.attrs
                val fullPath = path.trimEnd('/') + "/" + name
                if (attrs.isLink) symlinkIndices.add(results.size)
                results.add(
                    SftpEntry(
                        name = name,
                        path = fullPath,
                        isDirectory = attrs.isDir,
                        size = attrs.size,
                        modifiedTime = attrs.mTime.toLong(),
                        permissions = attrs.permissionsString ?: "",
                        // JSch reports UID/GID as integers — no name lookup over
                        // the SFTP subsystem. Users can still type a name in
                        // the chown dialog; the server resolves it.
                        owner = attrs.uId.toString(),
                        group = attrs.gId.toString(),
                    )
                )
            }
            ChannelSftp.LsEntrySelector.CONTINUE
        }
        // Resolve symlinks AFTER ls() completes — calling stat() inside the ls
        // callback corrupts JSch's read buffer (interleaved SFTP requests).
        for (i in symlinkIndices) {
            try {
                if (channel.stat(results[i].path).isDir) {
                    results[i] = results[i].copy(isDirectory = true)
                }
            } catch (_: Exception) {
                // broken symlink or permission denied
            }
        }
        results
    }

    override suspend fun upload(
        input: java.io.InputStream,
        sizeHint: Long,
        destPath: String,
        onBytes: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val channel = channelProvider()
        val monitor = object : SftpProgressMonitor {
            private var total = 0L
            private var transferred = 0L
            override fun init(op: Int, src: String, dest: String, max: Long) {
                total = if (max == SftpProgressMonitor.UNKNOWN_SIZE) sizeHint else max
                transferred = 0
                onBytes(0, total)
            }
            override fun count(bytes: Long): Boolean {
                transferred += bytes
                onBytes(transferred, total)
                return true
            }
            override fun end() {
                onBytes(total, total)
            }
        }
        channel.put(input, destPath, monitor)
    }

    override suspend fun download(
        srcPath: String,
        output: java.io.OutputStream,
        sizeHint: Long,
        onBytes: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val channel = channelProvider()
        val monitor = object : SftpProgressMonitor {
            private var total = 0L
            private var transferred = 0L
            override fun init(op: Int, src: String, dest: String, max: Long) {
                total = if (max == SftpProgressMonitor.UNKNOWN_SIZE) sizeHint else max
                transferred = 0
                onBytes(0, total)
            }
            override fun count(bytes: Long): Boolean {
                transferred += bytes
                onBytes(transferred, total)
                return true
            }
            override fun end() {
                onBytes(total, total)
            }
        }
        channel.get(srcPath, output, monitor)
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        channelProvider().mkdir(path)
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        channelProvider().rename(from, to)
    }

    override suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        val channel = channelProvider()
        if (isDirectory) channel.rmdir(path) else channel.rm(path)
    }

    override suspend fun chmod(path: String, mode: Int) = withContext(Dispatchers.IO) {
        channelProvider().chmod(mode, path)
    }

    override suspend fun chown(path: String, owner: String) {
        val ssh = sshClient
            ?: throw UnsupportedOperationException("chown requires a shell channel — not available on this session")
        val cmd = "chown ${shellQuote(owner)} -- ${shellQuote(path)}"
        val r = ssh.execCommand(cmd)
        if (r.exitStatus != 0) throw java.io.IOException("chown failed: ${r.stderr.trim()}")
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
