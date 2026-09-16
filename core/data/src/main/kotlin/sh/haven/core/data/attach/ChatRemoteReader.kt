package sh.haven.core.data.attach

/**
 * Reads a remote file's bytes for the chat attach flow, backend-agnostic.
 *
 * The interface lives in `:core:data` so ChatViewModel can depend on it
 * without a module edge to `:feature:sftp`; the production implementation
 * (`DefaultChatRemoteReader`, in `app`) wraps the Files tab's
 * TransportSelector/FileBackend surface, which is `:feature:sftp` code.
 */
interface ChatRemoteReader {

    /**
     * Read the whole file at [path] on [profileId]'s backend, up to
     * [maxBytes]. Throws when the backend is unavailable, the file is
     * missing, or the file (per stat) or its read exceeds [maxBytes] —
     * the caller surfaces the message through the chat error slot.
     */
    suspend fun read(profileId: String, path: String, maxBytes: Long): ByteArray
}