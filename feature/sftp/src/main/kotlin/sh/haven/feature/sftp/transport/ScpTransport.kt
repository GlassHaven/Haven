package sh.haven.feature.sftp.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.ssh.ScpClient
import sh.haven.core.ssh.ShellFileBrowser
import sh.haven.core.ssh.SshClient
import sh.haven.feature.sftp.SftpEntry
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private const val TAG = "ScpTransport"

/**
 * [RemoteFileTransport] speaking legacy SCP. List operations use shell `ls`
 * via [ShellFileBrowser]; mkdir/rename/delete use one-shot shell commands
 * via [SshClient.execCommand]. Upload / download spool through a cache
 * file since legacy SCP's wire protocol is size-prefixed and needs a known
 * length up-front.
 */
class ScpTransport(
    private val scp: ScpClient,
    private val sshClient: SshClient,
    private val cacheDir: File,
) : RemoteFileTransport {

    override val label: String = "SCP"

    private val browser = ShellFileBrowser(sshClient)

    override suspend fun list(path: String): List<SftpEntry> {
        return browser.list(path).map {
            SftpEntry(
                name = it.name,
                path = path.trimEnd('/') + "/" + it.name,
                isDirectory = it.isDirectory,
                size = it.size,
                modifiedTime = it.modifiedTimeSeconds,
                permissions = it.permissions,
                owner = it.owner,
                group = it.group,
            )
        }
    }

    override suspend fun upload(
        input: InputStream,
        sizeHint: Long,
        destPath: String,
        onBytes: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // Spool to a temp file so ScpClient can hand the known size to the
        // remote sink. sizeHint is only used for the initial progress tick;
        // the authoritative count comes from the temp file length.
        val spool = File(cacheDir, "scp_ul_${UUID.randomUUID()}")
        try {
            var spoolBytes = 0L
            val spoolTotal = if (sizeHint > 0) sizeHint else -1L
            onBytes(0, spoolTotal.coerceAtLeast(0))
            spool.outputStream().use { fos ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    spoolBytes += n
                }
            }
            val finalSize = spool.length()
            scp.uploadFile(
                localFile = spool,
                remotePath = destPath,
                preserveTimes = false,       // source has no meaningful mtime
            ) { transferred, total ->
                onBytes(transferred, total)
            }
            onBytes(finalSize, finalSize)
        } finally {
            if (!spool.delete()) Log.w(TAG, "Failed to delete spool $spool")
        }
    }

    override suspend fun download(
        srcPath: String,
        output: OutputStream,
        sizeHint: Long,
        onBytes: (Long, Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val spool = File(cacheDir, "scp_dl_${UUID.randomUUID()}")
            try {
                scp.downloadFile(
                    remotePath = srcPath,
                    localFile = spool,
                    preserveTimes = false,
                ) { transferred, total ->
                    onBytes(transferred, total)
                }
                // Copy the spooled bytes out to the caller's stream.
                spool.inputStream().use { it.copyTo(output) }
            } finally {
                if (!spool.delete()) Log.w(TAG, "Failed to delete spool $spool")
            }
        }
    }

    override suspend fun mkdir(path: String) {
        val cmd = "mkdir -p -- ${shellQuote(path)}"
        val r = sshClient.execCommand(cmd)
        if (r.exitStatus != 0) throw java.io.IOException("mkdir failed: ${r.stderr.trim()}")
    }

    override suspend fun rename(from: String, to: String) {
        val cmd = "mv -- ${shellQuote(from)} ${shellQuote(to)}"
        val r = sshClient.execCommand(cmd)
        if (r.exitStatus != 0) throw java.io.IOException("mv failed: ${r.stderr.trim()}")
    }

    override suspend fun delete(path: String, isDirectory: Boolean) {
        val cmd = if (isDirectory) {
            "rm -rf -- ${shellQuote(path)}"
        } else {
            "rm -f -- ${shellQuote(path)}"
        }
        val r = sshClient.execCommand(cmd)
        if (r.exitStatus != 0) throw java.io.IOException("rm failed: ${r.stderr.trim()}")
    }

    override suspend fun chmod(path: String, mode: Int) {
        // %04o renders 0..07777 as zero-padded octal including setuid/setgid/sticky.
        val octal = "%04o".format(mode and 0xFFF)
        val cmd = "chmod $octal -- ${shellQuote(path)}"
        val r = sshClient.execCommand(cmd)
        if (r.exitStatus != 0) throw java.io.IOException("chmod failed: ${r.stderr.trim()}")
    }

    override suspend fun chown(path: String, owner: String) {
        val cmd = "chown ${shellQuote(owner)} -- ${shellQuote(path)}"
        val r = sshClient.execCommand(cmd)
        if (r.exitStatus != 0) throw java.io.IOException("chown failed: ${r.stderr.trim()}")
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
