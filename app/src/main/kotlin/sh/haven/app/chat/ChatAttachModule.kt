package sh.haven.app.chat

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.data.attach.ChatRemoteReader
import sh.haven.feature.sftp.transport.TransportSelector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-layer [ChatRemoteReader]: resolves the Files tab's backend for the
 * picked profile, stats the file to enforce [MAX_BYTES] before transfer,
 * then spools the stream with a mid-stream bound (not `readBytes`, which
 * would trust the whole remote file into memory). Lives in `app` (not
 * `feature/chat`) because the backend surface it reads through
 * (TransportSelector / FileBackend) is `:feature:sftp` code.
 */
@Singleton
class DefaultChatRemoteReader @Inject constructor(
    private val transportSelector: TransportSelector,
) : ChatRemoteReader {

    override suspend fun read(profileId: String, path: String, maxBytes: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val backend = transportSelector.resolveFileBackend(profileId)?.backend
                ?: error("No connected backend for the picked file's profile")
            val entry = backend.stat(path)
            if (entry.size > maxBytes) {
                error(
                    "${entry.name} is ${entry.size / (1024 * 1024)} MiB — the chat attach cap is ${maxBytes / (1024 * 1024)} MiB",
                )
            }
            backend.openInputStream(path).use { input ->
                val spool = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } >= 0) {
                    if (spool.size() + read > maxBytes) {
                        error(
                            "${entry.name} grew past the ${maxBytes / (1024 * 1024)} MiB chat attach cap mid-read",
                        )
                    }
                    spool.write(buf, 0, read)
                }
                spool.toByteArray()
            }
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatAttachModule {
    @Binds
    abstract fun bindChatRemoteReader(impl: DefaultChatRemoteReader): ChatRemoteReader
}