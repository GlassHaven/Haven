package sh.haven.feature.sftp

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Byte-copy core extracted from [SftpViewModel.writeLocalFileWithProgress]
 * so the resume-vs-overwrite branching is testable without spinning up a
 * Room DAO and a `_transferProgress` MutableStateFlow.
 *
 * ### Resume semantics
 *
 * - [transferOffset] = 0 → overwrite mode: destination is truncated, full
 *   source written.
 * - [transferOffset] > 0 → append mode: source is `skip()`-ed past
 *   [transferOffset] bytes, destination is opened with `append=true` so
 *   the remaining source bytes land after whatever was already there.
 *   Used for resumable paste — the queue's `bytesTransferred` cursor
 *   tells us how many destination bytes already exist after a crash.
 *
 * Returning the number of bytes actually written lets the caller drive
 * its progress display independently of the resume offset.
 *
 * ### Why this exists separately from the function on the ViewModel
 *
 * The phase-2 path of [SftpViewModel.crossCopyFile] for remote → local
 * paste used to pass `transferOffset = downloaded` (the temp file's
 * size) when it really only wanted to seed the *progress bar* past the
 * download phase — not skip the temp file's bytes during the actual
 * write. The result was a 0-byte destination because `input.skip()`
 * advanced past every byte of the temp file before any were written.
 * GH#142.
 *
 * Splitting this out forces callers to express the two concerns
 * separately: progress display offset is the ViewModel's concern;
 * transfer-skip offset is this function's.
 */
internal fun writeFileWithOptionalResume(
    source: File,
    destPath: String,
    transferOffset: Long,
    onChunk: (writtenSoFar: Long) -> Unit = {},
): Long {
    require(transferOffset >= 0) { "transferOffset must be non-negative, got $transferOffset" }
    File(destPath).parentFile?.mkdirs()
    return if (transferOffset == 0L) {
        source.inputStream().use { input ->
            FileOutputStream(destPath, false).use { out ->
                copyStream(input, out, onChunk)
            }
        }
    } else {
        source.inputStream().use { input ->
            val skipped = input.skip(transferOffset)
            require(skipped == transferOffset) {
                "wanted to skip $transferOffset source bytes but only skipped $skipped"
            }
            FileOutputStream(destPath, true).use { out ->
                copyStream(input, out, onChunk)
            }
        }
    }
}

private fun copyStream(
    input: InputStream,
    out: OutputStream,
    onChunk: (Long) -> Unit,
): Long {
    val buf = ByteArray(64 * 1024)
    var written = 0L
    while (true) {
        val n = input.read(buf)
        if (n == -1) break
        out.write(buf, 0, n)
        written += n
        onChunk(written)
    }
    out.flush()
    return written
}
