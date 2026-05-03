package sh.haven.feature.sftp.transport

import sh.haven.feature.sftp.SftpEntry

/**
 * Backend-agnostic file operations. Implementations exist for every browser
 * surface Haven exposes: SFTP / SCP over SSH (see [RemoteFileTransport]),
 * the local Android filesystem, SMB shares, and rclone-managed cloud
 * remotes. The [SftpViewModel] dispatches its listing path through this
 * interface so the per-backend `when` blocks collapse to a single call.
 *
 * Stage 1 of issue #126 covers listing only — copy/paste, delete, rename,
 * mkdir, chmod and chown still go through the existing per-backend code.
 * As each operation generalises across all four backends it gets promoted
 * to a method on this interface.
 */
interface FileBackend {
    /** Display badge — "SFTP", "SCP", "Local", "SMB", or "Rclone". */
    val label: String

    /**
     * List the directory at [path]. Path conventions vary by backend
     * (rclone treats the empty string as remote root, local uses "/" as
     * a synthetic Android-storage-roots view) — implementations handle
     * their own normalisation so callers can pass through whatever the
     * UI's `currentPath` says.
     */
    suspend fun list(path: String): List<SftpEntry>
}
