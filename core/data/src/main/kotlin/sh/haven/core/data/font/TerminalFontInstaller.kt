package sh.haven.core.data.font

import android.content.Context
import android.graphics.Typeface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.data.preferences.UserPreferencesRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Hard cap on a downloaded font so a misdirected URL can't fill the FS. */
private const val MAX_FONT_BYTES = 10L * 1024 * 1024

/** Buffer for streamed download. 8 KiB is a comfortable middle ground. */
private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024

/**
 * Single source of truth for installing a custom terminal font (#123).
 * Both the user-facing Settings flow and the agent-facing MCP tool call
 * the same routines here, so the two surfaces can never diverge — VISION
 * §85's "shared viewport" rule applies to the install path too, not just
 * the read/observe path.
 *
 * Owns:
 *  - URL download with size cap and timeout.
 *  - Typeface decode validation, so a bad input never poisons the
 *    Settings store with a path that would crash rendering.
 *  - Path persistence via [UserPreferencesRepository.setTerminalFontPath].
 *  - Cleanup of stale sibling files left behind by a prior import.
 */
@Singleton
class TerminalFontInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
) {

    /**
     * Outcome of an install attempt. Modeled as a sealed interface so
     * call sites can pattern-match instead of stringly checking.
     * [Failure.message] is suitable for direct surfacing in a Toast or
     * an MCP error code.
     */
    sealed interface Result {
        data class Success(val path: String, val bytesInstalled: Long) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Download a TTF/OTF from [urlString] over http(s), validate it,
     * persist it as the user's chosen terminal font. The URL must
     * resolve to font bytes — any HTML-wrapped landing page will fail
     * the Typeface decode and be rejected.
     */
    suspend fun installFromUrl(urlString: String): Result = withContext(Dispatchers.IO) {
        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            return@withContext Result.Failure("Invalid URL: ${e.message}")
        }
        if (url.protocol !in setOf("http", "https")) {
            return@withContext Result.Failure("Only http(s) URLs are supported (got ${url.protocol})")
        }
        val ext = url.path.substringAfterLast('.', "ttf")
            .substringBefore('?')
            .lowercase()
            .takeIf { it in setOf("ttf", "otf") } ?: "ttf"
        val target = prepareTargetFile(ext)
        val written = try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Haven/1.0 (TerminalFontInstaller)")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext Result.Failure("HTTP ${conn.responseCode} from ${url.host}")
            }
            try {
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_FONT_BYTES) {
                                return@withContext failAndClean(target, "Font exceeds ${MAX_FONT_BYTES / (1024 * 1024)} MiB cap")
                            }
                            output.write(buf, 0, n)
                        }
                        total
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return@withContext failAndClean(target, "Download failed: ${e.message}")
        }
        finishInstall(target, written)
    }

    /**
     * Copy a TTF/OTF chosen via the Storage Access Framework into
     * Haven's private files dir and persist it as the active terminal
     * font. Owning the file (rather than the source content URI) means
     * the chosen font keeps working across phone reboots, source-app
     * uninstalls, and SAF permission revocations.
     */
    suspend fun installFromContentUri(uri: android.net.Uri, displayName: String): Result = withContext(Dispatchers.IO) {
        val ext = displayName.substringAfterLast('.', "ttf")
            .lowercase()
            .takeIf { it in setOf("ttf", "otf") } ?: "ttf"
        val target = prepareTargetFile(ext)
        val written = try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.Failure("Could not open the selected file")
            input.use { stream ->
                target.outputStream().use { output ->
                    val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FONT_BYTES) {
                            return@withContext failAndClean(target, "Font exceeds ${MAX_FONT_BYTES / (1024 * 1024)} MiB cap")
                        }
                        output.write(buf, 0, n)
                    }
                    total
                }
            }
        } catch (e: Exception) {
            return@withContext failAndClean(target, "Import failed: ${e.message}")
        }
        finishInstall(target, written)
    }

    /** Reset to the bundled default font. Idempotent. */
    suspend fun reset() {
        preferencesRepository.setTerminalFontPath(null)
        runCatching {
            File(context.filesDir, "fonts").listFiles()?.forEach { it.delete() }
        }
    }

    // --- internals ---

    private fun prepareTargetFile(ext: String): File {
        val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
        return File(fontsDir, "terminal.$ext")
    }

    private suspend fun finishInstall(target: File, written: Long): Result {
        val decoded = runCatching { Typeface.createFromFile(target) }.getOrNull() != null
        if (!decoded) {
            target.delete()
            return Result.Failure(
                "Downloaded $written bytes but Android could not decode them as a Typeface " +
                    "(corrupt or non-font URL?)",
            )
        }
        // Drop sibling files left from a prior import so a .ttf install
        // doesn't collide with a stale .otf — the path preference only
        // points at one file and the orphan would leak storage.
        runCatching {
            target.parentFile?.listFiles()?.forEach {
                if (it.absolutePath != target.absolutePath) it.delete()
            }
        }
        preferencesRepository.setTerminalFontPath(target.absolutePath)
        return Result.Success(path = target.absolutePath, bytesInstalled = written)
    }

    private fun failAndClean(target: File, message: String): Result {
        runCatching { target.delete() }
        return Result.Failure(message)
    }
}
