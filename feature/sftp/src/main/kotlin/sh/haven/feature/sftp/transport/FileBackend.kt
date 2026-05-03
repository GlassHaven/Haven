package sh.haven.feature.sftp.transport

import sh.haven.feature.sftp.SftpEntry

/**
 * Backend-agnostic file operations. Implementations exist for every browser
 * surface Haven exposes: SFTP / SCP over SSH (see [RemoteFileTransport]),
 * the local Android filesystem, SMB shares, and rclone-managed cloud
 * remotes. The [SftpViewModel] dispatches its listing path through this
 * interface so the per-backend `when` blocks collapse to a single call.
 *
 * Stage 1 of issue #126 covered listing only. Stage 2 promotes the
 * non-streaming structural ops (delete, mkdir, rename) so creating /
 * deleting / renaming work the same way regardless of which backend the
 * user is browsing. Streaming ops (upload / download) and POSIX-only ops
 * (chmod / chown) still live on [RemoteFileTransport] and ship in later
 * stages.
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

    /**
     * Delete the entry at [path]. [isDirectory] is required because some
     * backends (rclone, SMB) take different code paths for files vs dirs;
     * callers always know which they're deleting from the [SftpEntry]
     * they had in hand. Recursive directory deletion is the contract —
     * SMB's `rmdir`, rclone's `purge`, and SSH's `rm -rf` all match.
     */
    suspend fun delete(path: String, isDirectory: Boolean)

    /**
     * Create the directory at [path]. `mkdir -p` semantics: missing
     * intermediate parents are created; an existing directory is a no-op
     * rather than an error. Errors only fire for genuinely impossible
     * cases (path is a file, no permission, etc).
     */
    suspend fun mkdir(path: String)

    /**
     * Rename [from] → [to]. For SSH and Local this is the obvious one-call
     * rename; SMB uses an open-with-DELETE handle; rclone splits internally
     * between `operations/movefile` (files) and a `sync MOVE` job
     * (directories), with implementations probing the source type as
     * needed.
     */
    suspend fun rename(from: String, to: String)

    /**
     * Read the file at [path] into a [ByteArray]. Intended for small
     * files — the editor's text content, a single image for the image
     * tools, a downloaded font. The legacy [SftpViewModel] paths used
     * `ByteArrayOutputStream` for the same use cases. Large files with
     * progress reporting still go through [RemoteFileTransport.download]
     * (SSH/SCP only); this surface is the small-file shorthand that
     * generalises to every backend.
     */
    suspend fun readBytes(path: String): ByteArray

    /**
     * Write [data] to [path], replacing any existing content. Intended
     * for small files (see [readBytes]). The file is created if it
     * doesn't exist; the parent directory must already exist.
     */
    suspend fun writeBytes(path: String, data: ByteArray)
}
