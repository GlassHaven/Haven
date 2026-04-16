package sh.haven.feature.sftp.transport

import sh.haven.feature.sftp.SftpEntry
import java.io.InputStream
import java.io.OutputStream

/**
 * Abstraction over the SSH-backed file operations the SFTP screen needs.
 * Two implementations live alongside this interface:
 *  - [SftpTransport] — wraps JSch's ChannelSftp (the historical happy path)
 *  - [ScpTransport]  — speaks legacy SCP (scp -t / -f) over an exec channel
 *                       plus `ls -la` for directory listings
 *
 * The rclone / SMB / local backends in SftpViewModel don't use this
 * interface — they predate it and have different semantics. Only the SSH
 * code paths dispatch through here.
 */
interface RemoteFileTransport {
    /** "SFTP" or "SCP". Shown in the path-bar badge. */
    val label: String

    /** Whether this transport supports recursive operations (always true). */
    val supportsRecursive: Boolean get() = true

    suspend fun list(path: String): List<SftpEntry>

    /**
     * Stream bytes from [input] to [destPath]. [sizeHint] is the declared
     * total size in bytes; SCP requires it to be known up-front (it is part
     * of the wire protocol), SFTP can run without it but still uses the
     * hint for progress reporting.
     */
    suspend fun upload(
        input: InputStream,
        sizeHint: Long,
        destPath: String,
        onBytes: (transferred: Long, total: Long) -> Unit,
    )

    /**
     * Stream [srcPath] into [output]. [sizeHint] is -1 if unknown (SFTP
     * only); SCP fills it in from the server-side control message before
     * reporting progress.
     */
    suspend fun download(
        srcPath: String,
        output: OutputStream,
        sizeHint: Long,
        onBytes: (transferred: Long, total: Long) -> Unit,
    )

    suspend fun mkdir(path: String)

    suspend fun rename(from: String, to: String)

    suspend fun delete(path: String, isDirectory: Boolean)

    /**
     * Apply POSIX permissions to [path]. [mode] is the usual 0..07777
     * integer (setuid/setgid/sticky plus rwx for owner/group/other).
     * Both transports map this onto a single remote syscall — no
     * recursion, caller is responsible for walking a tree.
     */
    suspend fun chmod(path: String, mode: Int)
}
