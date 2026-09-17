package sh.haven.core.ssh.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.ssh.SshIoException
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * [SftpSession] backed by a JSch [ChannelSftp].
 *
 * The JSch channel is owned externally (by [sh.haven.core.ssh.SshSessionManager])
 * — this class does not connect or disconnect it. [isConnected] reflects the
 * channel's live state. Exception: [openInputStream] opens its own per-stream
 * "sftp" channel on the same SSH session and owns (disconnects) it, because an
 * aborted stream transfer poisons the channel it ran on (see there).
 *
 * Translates `com.jcraft.jsch.JSchException` and `com.jcraft.jsch.SftpException`
 * to [SshIoException] so callers do not need to import JSch types.
 */
internal class JschSftpSession(private val channel: ChannelSftp) : SftpSession {

    override val isConnected: Boolean
        get() = channel.isConnected

    override suspend fun list(path: String, onEntry: (SftpAttrs) -> ListResult) =
        withContext(Dispatchers.IO) {
            translatingJschErrors {
                channel.ls(path) { ls ->
                    val name = ls.filename
                    if (name == "." || name == "..") {
                        ChannelSftp.LsEntrySelector.CONTINUE
                    } else {
                        val attrs = ls.attrs
                        val verdict = onEntry(
                            SftpAttrs(
                                filename = name,
                                isDirectory = attrs.isDir,
                                isSymlink = attrs.isLink,
                                size = attrs.size,
                                modifiedTimeSeconds = attrs.mTime,
                                permissions = attrs.permissionsString ?: "",
                                uid = attrs.uId,
                                gid = attrs.gId,
                            ),
                        )
                        when (verdict) {
                            ListResult.CONTINUE -> ChannelSftp.LsEntrySelector.CONTINUE
                            ListResult.BREAK -> ChannelSftp.LsEntrySelector.BREAK
                        }
                    }
                }
            }
            Unit
        }

    override suspend fun stat(path: String): SftpAttrs = withContext(Dispatchers.IO) {
        translatingJschErrors {
            val attrs = channel.stat(path)
            SftpAttrs(
                filename = path.substringAfterLast('/').ifEmpty { path },
                isDirectory = attrs.isDir,
                isSymlink = attrs.isLink,
                size = attrs.size,
                modifiedTimeSeconds = attrs.mTime,
                permissions = attrs.permissionsString ?: "",
                uid = attrs.uId,
                gid = attrs.gId,
            )
        }
    }

    override suspend fun download(
        srcPath: String,
        output: OutputStream,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        translatingJschErrors {
            channel.get(srcPath, output, lambdaMonitor(sizeHint = -1, onBytes = onBytes))
        }
    }

    override suspend fun upload(
        input: InputStream,
        sizeHint: Long,
        destPath: String,
        mode: SftpWriteMode,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        translatingJschErrors {
            val jschMode = when (mode) {
                SftpWriteMode.OVERWRITE -> ChannelSftp.OVERWRITE
                SftpWriteMode.RESUME -> ChannelSftp.RESUME
            }
            channel.put(input, destPath, lambdaMonitor(sizeHint = sizeHint, onBytes = onBytes), jschMode)
        }
    }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            // jsch's get(path, monitor, offset) InputStream (ChannelSftp$2) has a
            // buffer-overflow bug: its 1024-byte `rest_byte` gets System.arraycopy'd
            // with a >1024 length on some servers/packet patterns, throwing
            // IndexOutOfBounds ("src.length=1024 … length=2048"), which then
            // desyncs the channel. Confirmed unfixed through jsch 2.28.2. So
            // stream via the non-buggy get(src, OutputStream, …, skip) overload on
            // a background thread and surface the bytes through a pipe. JSch only
            // honours `skip` when mode == RESUME (with OVERWRITE it silently reads
            // from 0 — caught by the #58 SftpSessionContractTest), so pick the
            // mode from the offset; offset 0 = whole file (install / serve_file).
            //
            // The get runs on its OWN channel, not the shared one: when a consumer
            // closes the stream mid-body (a Range client seeking, e.g. ffmpeg
            // probing an MP4 with a trailing moov), the get aborts without
            // draining its in-flight READ responses, which then queue up in the
            // channel, and the producer thread's death breaks io_in's pipe read
            // side. A later get() on the shared channel fails instantly with an
            // empty-message SftpException(SSH_FX_FAILURE) or "Pipe closed"
            // (reproduced against OpenSSH with jsch 2.28.7). Discarding the
            // stream's channel on close retires the poison with it.
            val streamChannel = openStreamChannel()
            val pipeIn = PipedInputStream(PIPE_BUFFER_BYTES)
            val pipeOut = PipedOutputStream(pipeIn)
            val failure = AtomicReference<Throwable?>(null)
            val producer = Thread({
                try {
                    translatingJschErrors {
                        val mode = if (offset > 0) ChannelSftp.RESUME else ChannelSftp.OVERWRITE
                        streamChannel.get(path, pipeOut, null, mode, offset)
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    try { pipeOut.close() } catch (_: Throwable) { /* best effort */ }
                }
            }, "sftp-get").apply { isDaemon = true }
            producer.start()
            // Re-raise a producer-side failure at EOF rather than truncating silently.
            object : InputStream() {
                override fun read(): Int = pipeIn.read().also { if (it < 0) raiseIfFailed() }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    pipeIn.read(b, off, len).also { if (it < 0) raiseIfFailed() }
                override fun available(): Int = pipeIn.available()
                override fun close() {
                    try { pipeIn.close() } catch (_: IOException) { /* already closed */ }
                    // The producer must not touch the channel after we retire it.
                    producer.join(STREAM_TEARDOWN_JOIN_MS)
                    if (producer.isAlive) {
                        // Get stuck on the network: disconnecting makes it fail.
                        try { streamChannel.disconnect() } catch (_: Exception) { /* best effort */ }
                        producer.join(STREAM_TEARDOWN_JOIN_MS)
                    }
                    try { streamChannel.disconnect() } catch (_: Exception) { /* best effort */ }
                }
                private fun raiseIfFailed() {
                    // Include the class: jsch throws SftpException(SSH_FX_FAILURE, "")
                    // for a desynced channel — message alone reads as nothing.
                    failure.get()?.let {
                        throw SshIoException(
                            "SFTP read failed: ${it.javaClass.simpleName}: ${it.message}",
                            it,
                        )
                    }
                }
            }
        }

    override suspend fun home(): String = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.home }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.mkdir(path) }
    }

    override suspend fun rmdir(path: String) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.rmdir(path) }
    }

    override suspend fun rm(path: String) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.rm(path) }
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.rename(from, to) }
    }

    override suspend fun chmod(path: String, mode: Int) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.chmod(mode, path) }
    }

    override fun close() {
        try {
            channel.disconnect()
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun lambdaMonitor(
        sizeHint: Long,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ): SftpProgressMonitor = object : SftpProgressMonitor {
        private var total = 0L
        private var transferred = 0L
        override fun init(op: Int, src: String, dest: String, max: Long) {
            total = if (max == SftpProgressMonitor.UNKNOWN_SIZE) sizeHint else max
            transferred = 0L
            onBytes(0L, total)
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

    /** Extra "sftp" channel on the SSH session, owned by one [openInputStream] stream. */
    private fun openStreamChannel(): ChannelSftp = try {
        (channel.session.openChannel("sftp") as ChannelSftp).apply { connect() }
    } catch (e: Exception) {
        throw SshIoException("SFTP stream channel: ${e.message}", e)
    }

    private inline fun <T> translatingJschErrors(block: () -> T): T = try {
        block()
    } catch (e: SftpException) {
        throw SshIoException(e.message, e)
    } catch (e: JSchException) {
        throw SshIoException(e.message, e)
    } catch (e: IOException) {
        throw SshIoException(e.message, e)
    }

    private companion object {
        /** Pipe capacity for the streamed SFTP download (back-pressures the producer). */
        const val PIPE_BUFFER_BYTES = 1 shl 20 // 1 MiB

        /** How long [close] waits for the stream's get thread before force-closing its channel. */
        const val STREAM_TEARDOWN_JOIN_MS = 5000L
    }
}
