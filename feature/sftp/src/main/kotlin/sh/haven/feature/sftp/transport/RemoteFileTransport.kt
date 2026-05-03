package sh.haven.feature.sftp.transport

import sh.haven.feature.sftp.SftpEntry
import java.io.InputStream
import java.io.OutputStream

/**
 * Abstraction over the SSH-backed file operations the SFTP screen needs —
 * the richer cousin of [FileBackend] for transports that can do more than
 * list. Two implementations live alongside this interface:
 *  - [SftpTransport] — wraps JSch's ChannelSftp (the historical happy path)
 *  - [ScpTransport]  — speaks legacy SCP (scp -t / -f) over an exec channel
 *                       plus `ls -la` for directory listings
 *
 * The rclone / SMB / local backends share the [FileBackend.list] surface
 * with this interface but stay on their own per-backend dispatch for
 * upload, download, mkdir, rename, delete, chmod and chown. Each of those
 * operations gets promoted to [FileBackend] once it generalises across
 * every backend (issue #126, stages 2 onwards).
 */
interface RemoteFileTransport : FileBackend {
    /** Whether this transport supports recursive operations (always true). */
    val supportsRecursive: Boolean get() = true

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

    /**
     * Apply POSIX permissions to [path]. [mode] is the usual 0..07777
     * integer (setuid/setgid/sticky plus rwx for owner/group/other).
     * Both transports map this onto a single remote syscall — no
     * recursion, caller is responsible for walking a tree.
     */
    suspend fun chmod(path: String, mode: Int)

    /**
     * Change ownership of [path]. [owner] is a `user` or `user:group`
     * string, passed straight to the remote `chown` command. Name-to-UID
     * resolution happens on the server, so either names or numeric IDs
     * are accepted. Typically requires root on the remote side.
     */
    suspend fun chown(path: String, owner: String)
}
