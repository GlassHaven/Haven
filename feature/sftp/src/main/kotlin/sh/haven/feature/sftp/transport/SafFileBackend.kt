package sh.haven.feature.sftp.transport

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.feature.sftp.SftpEntry
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * [FileBackend] over a persisted SAF `DocumentsProvider` tree (issue #415):
 * a folder the user grants once via `OpenDocumentTree` (Termux's home being
 * the motivating case, but any provider works — USB, Nextcloud, the Downloads
 * tree). The grant is persisted with `takePersistableUriPermission` at pick
 * time, so this backend just needs the tree [Uri].
 *
 * **Path model.** The browser builds child / rename / mkdir targets by string
 * concatenation (`SftpViewModel` does `parent + "/" + name`), so paths here are
 * tree-relative POSIX-like strings — `"/"`, `"/foo"`, `"/foo/bar.txt"` — not raw
 * document ids. Each op resolves its path to a document id via [resolveSafDocId],
 * which walks from the tree root one `query()` per directory level and memoises
 * every id it sees along the way (into [docIdCache]) — so after listing a
 * directory, navigating into it and its children costs no extra queries.
 *
 * The pure walk/normalise logic lives in the top-level [resolveSafDocId] /
 * [normalizeSafPath] functions (injectable `queryChildren`) so it's unit-testable
 * without Robolectric, mirroring `scanDocumentTree` (#273). The framework calls
 * (`DocumentsContract`, `ContentResolver`) stay in this class.
 */
class SafFileBackend(
    private val appContext: Context,
    private val treeUri: Uri,
) : FileBackend {

    override val label: String = "Folder"

    private val resolver get() = appContext.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** Tree-relative path → document id. Seeded with the root; grown by every list/resolve. */
    private val docIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        .apply { put("/", rootDocId) }

    private fun childrenUri(parentDocId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private val queryChildren: (String) -> Cursor? = { parentDocId ->
        resolver.query(childrenUri(parentDocId), SAF_PROJECTION, null, null, null)
    }

    private fun resolve(path: String): String? =
        resolveSafDocId(path, rootDocId, docIdCache, queryChildren)

    override suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        val p = normalizeSafPath(path)
        val docId = resolve(p) ?: throw FileNotFoundException(p)
        val out = mutableListOf<SftpEntry>()
        (queryChildren(docId) ?: throw FileNotFoundException(p)).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val childId = c.getString(0)
                val mime = c.getString(2) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val childPath = if (p == "/") "/$name" else "$p/$name"
                docIdCache[childPath] = childId
                out.add(
                    SftpEntry(
                        name = name,
                        path = childPath,
                        isDirectory = isDir,
                        size = if (isDir || c.isNull(3)) 0 else c.getLong(3),
                        modifiedTime = if (c.isNull(4)) 0 else c.getLong(4) / 1000,
                        permissions = "",
                        mimeType = if (isDir) "" else mime,
                    ),
                )
            }
        }
        out
    }

    override suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        val p = normalizeSafPath(path)
        val docId = resolve(p) ?: throw FileNotFoundException(p)
        // deleteDocument is recursive for directories on compliant providers.
        val ok = DocumentsContract.deleteDocument(resolver, docUri(docId))
        if (!ok) throw IOException("Could not delete $p")
        docIdCache.remove(p)
        Unit
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        mkdirp(normalizeSafPath(path))
        Unit
    }

    /** `mkdir -p`: create missing parents, no-op on an existing dir. Returns the leaf doc id. */
    private fun mkdirp(p: String): String {
        if (p == "/") return rootDocId
        resolve(p)?.let { return it }
        val parentPath = p.substringBeforeLast('/').ifEmpty { "/" }
        val name = p.substringAfterLast('/')
        val parentDoc = mkdirp(parentPath)
        val created = DocumentsContract.createDocument(
            resolver, docUri(parentDoc), DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: throw IOException("Could not create $p")
        val id = DocumentsContract.getDocumentId(created) ?: throw IOException("Could not resolve created $p")
        docIdCache[p] = id
        return id
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        val fromN = normalizeSafPath(from)
        val toN = normalizeSafPath(to)
        val fromParent = fromN.substringBeforeLast('/').ifEmpty { "/" }
        val toParent = toN.substringBeforeLast('/').ifEmpty { "/" }
        // SAF renameDocument only changes the display name in place. The browser's
        // rename keeps the same parent; a cross-folder move is a separate op (Phase 2).
        if (fromParent != toParent) {
            throw UnsupportedOperationException("Moving across folders isn't supported on this location yet")
        }
        val docId = resolve(fromN) ?: throw FileNotFoundException(fromN)
        DocumentsContract.renameDocument(resolver, docUri(docId), toN.substringAfterLast('/'))
            ?: throw IOException("Could not rename $fromN")
        // The renamed node (and any cached descendant paths) now hold stale ids; drop
        // everything but the root and let the next list() repopulate.
        docIdCache.keys.retainAll(setOf("/"))
        Unit
    }

    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val docId = resolve(path) ?: throw FileNotFoundException(path)
        (resolver.openInputStream(docUri(docId)) ?: throw IOException("Cannot open $path"))
            .use { it.readBytes() }
    }

    override suspend fun writeBytes(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val p = normalizeSafPath(path)
        // "wt" truncates — required so shortening a file in the editor doesn't leave a
        // stale tail. A provider that rejects the 't' flag surfaces as an error here
        // rather than silently corrupting; none seen so far (Termux, ExternalStorage).
        (resolver.openOutputStream(createOrResolveForWrite(p), "wt") ?: throw IOException("Cannot write $p"))
            .use { it.write(data) }
    }

    /**
     * Streaming write for cross-backend transfers (#415 phase 2): same
     * create-or-truncate semantics as [writeBytes], but pumps [input] in
     * chunks and reports cumulative bytes via [onBytes] — the file never
     * sits in memory whole. Returns the byte count written.
     */
    suspend fun writeStream(
        path: String,
        input: InputStream,
        onBytes: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val p = normalizeSafPath(path)
        var total = 0L
        (resolver.openOutputStream(createOrResolveForWrite(p), "wt") ?: throw IOException("Cannot write $p"))
            .use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onBytes(total)
                }
            }
        total
    }

    /** Existing document's Uri at [p], or create it (parent must exist). */
    private fun createOrResolveForWrite(p: String): Uri {
        val existing = resolve(p)
        return if (existing != null) {
            docUri(existing)
        } else {
            val parentPath = p.substringBeforeLast('/').ifEmpty { "/" }
            val name = p.substringAfterLast('/')
            val parentDoc = resolve(parentPath) ?: throw FileNotFoundException(parentPath)
            val created = DocumentsContract.createDocument(resolver, docUri(parentDoc), guessMime(name), name)
                ?: throw IOException("Cannot create $p")
            DocumentsContract.getDocumentId(created)?.let { docIdCache[p] = it }
            created
        }
    }

    override suspend fun stat(path: String): SftpEntry = withContext(Dispatchers.IO) {
        val p = normalizeSafPath(path)
        if (p == "/") return@withContext SftpEntry("/", "/", true, 0, 0, "")
        val docId = resolve(p) ?: throw FileNotFoundException(p)
        resolver.query(docUri(docId), SAF_PROJECTION, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val mime = c.getString(2) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                return@withContext SftpEntry(
                    name = p.substringAfterLast('/'),
                    path = p,
                    isDirectory = isDir,
                    size = if (isDir || c.isNull(3)) 0 else c.getLong(3),
                    modifiedTime = if (c.isNull(4)) 0 else c.getLong(4) / 1000,
                    permissions = "",
                    mimeType = if (isDir) "" else mime,
                )
            }
        }
        throw FileNotFoundException(p)
    }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            val docId = resolve(path) ?: throw FileNotFoundException(path)
            val stream = resolver.openInputStream(docUri(docId)) ?: throw IOException("Cannot open $path")
            if (offset > 0) {
                var remaining = offset
                while (remaining > 0) {
                    val skipped = stream.skip(remaining)
                    if (skipped <= 0) {
                        stream.close()
                        throw UnsupportedOperationException("Cannot seek to $offset in $path")
                    }
                    remaining -= skipped
                }
            }
            stream
        }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "opus" -> "audio/opus"
            else -> "application/octet-stream"
        }
    }
}

/** Projection column order for SAF cursors — indices are read positionally in [SafFileBackend]. */
internal val SAF_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,    // 0
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,   // 1
    DocumentsContract.Document.COLUMN_MIME_TYPE,      // 2
    DocumentsContract.Document.COLUMN_SIZE,           // 3
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,  // 4
)

/** Normalise a browser path to a canonical tree-relative form: `"/"`, or `"/a/b"` (no trailing slash). */
internal fun normalizeSafPath(path: String): String {
    if (path.isEmpty() || path == "/") return "/"
    return "/" + path.trim('/')
}

/**
 * Resolve a tree-relative [path] to a SAF document id, walking from the root one
 * `queryChildren()` per directory level and memoising every child id it sees into
 * [cache] (keyed by tree-relative path). Returns null if any segment is missing.
 *
 * Pure and framework-free (the cursor source is injected) so it's unit-testable —
 * [queryChildren] returns a cursor whose column 0 is the document id and column 1
 * the display name (see [SAF_PROJECTION]).
 */
internal fun resolveSafDocId(
    path: String,
    rootDocId: String,
    cache: MutableMap<String, String>,
    queryChildren: (parentDocId: String) -> Cursor?,
): String? {
    val p = normalizeSafPath(path)
    cache[p]?.let { return it }
    var curPath = "/"
    var curDoc = cache["/"] ?: rootDocId
    for (seg in p.trim('/').split('/').filter { it.isNotEmpty() }) {
        val childPath = if (curPath == "/") "/$seg" else "$curPath/$seg"
        val cached = cache[childPath]
        if (cached != null) {
            curDoc = cached
            curPath = childPath
            continue
        }
        var found: String? = null
        (queryChildren(curDoc) ?: return null).use { c ->
            while (c.moveToNext()) {
                val childName = c.getString(1) ?: continue
                val childId = c.getString(0)
                val cp = if (curPath == "/") "/$childName" else "$curPath/$childName"
                cache[cp] = childId
                if (childName == seg) found = childId
            }
        }
        curDoc = found ?: return null
        curPath = childPath
    }
    return curDoc
}
