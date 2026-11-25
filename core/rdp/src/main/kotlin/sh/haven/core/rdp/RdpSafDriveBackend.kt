package sh.haven.core.rdp

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import sh.haven.rdp.DriveCallback
import sh.haven.rdp.RdpDriveEntry
import sh.haven.rdp.RdpDriveListResult
import sh.haven.rdp.RdpDriveReadResult
import sh.haven.rdp.RdpDriveStatus
import sh.haven.rdp.RdpDriveWriteResult
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

internal data class SafDocNode(
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMs: Long,
    val flags: Int,
) {
    val isReadOnly: Boolean
        get() = !isDir && (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) == 0 && flags != 0
}

/**
 * Injectable Storage Access Framework document-tree operations so
 * [RdpSafDriveBackend] path resolution, descriptor caching, and MS-RDPEFS
 * status mapping can be unit-tested on the JVM without Robolectric.
 */
internal interface SafTreeOps : Closeable {
    val rootDocId: String
    val rootDisplayName: String
    fun queryDoc(docId: String): SafDocNode?
    fun queryChildren(parentDocId: String): List<SafDocNode>?
    fun createDoc(parentDocId: String, mimeType: String, displayName: String): String?
    fun deleteDoc(docId: String): Boolean
    fun renameDoc(docId: String, newDisplayName: String): String?
    fun moveDoc(docId: String, sourceParentDocId: String, targetParentDocId: String): String?
    fun readAt(docId: String, offset: Long, length: Int): ByteArray
    fun writeAt(docId: String, offset: Long, data: ByteArray): Int
    fun truncate(docId: String, size: Long)
    fun closeCached(docId: String)
}

/**
 * MS-RDPEFS ([DriveCallback]) implementation backed by a persisted Android SAF
 * `DocumentsProvider` tree (`OpenDocumentTree`, #890).
 *
 * Requires zero Android manifest permissions: access is scoped strictly to the
 * single folder URI granted by the user on the connection profile.
 */
class RdpSafDriveBackend internal constructor(
    private val ops: SafTreeOps,
) : DriveCallback, Closeable {

    val shareName: String
        get() = ops.rootDisplayName.ifBlank { "Haven" }

    /** Tree-relative POSIX path (`"/"` or `"/a/b"`) -> SAF document ID. */
    private val docIdCache = ConcurrentHashMap<String, String>().apply {
        put("/", ops.rootDocId)
    }

    private fun normalize(path: String): String {
        if (path.isEmpty() || path == "/") return "/"
        return "/" + path.trim('/')
    }

    private fun resolve(path: String): String? {
        val norm = normalize(path)
        docIdCache[norm]?.let { return it }
        var curPath = "/"
        var curDoc = docIdCache["/"] ?: ops.rootDocId
        for (seg in norm.trim('/').split('/').filter { it.isNotEmpty() }) {
            val childPath = if (curPath == "/") "/$seg" else "$curPath/$seg"
            val cached = docIdCache[childPath]
            if (cached != null) {
                curDoc = cached
                curPath = childPath
                continue
            }
            val children = ops.queryChildren(curDoc) ?: return null
            var match: String? = null
            for (child in children) {
                val cp = if (curPath == "/") "/${child.name}" else "$curPath/${child.name}"
                docIdCache[cp] = child.docId
                if (child.name == seg) {
                    match = child.docId
                }
            }
            if (match == null) {
                // Case-insensitive fallback for Windows callers probing mixed-case names.
                for (child in children) {
                    if (child.name.equals(seg, ignoreCase = true)) {
                        match = child.docId
                        docIdCache[childPath] = child.docId
                        break
                    }
                }
            }
            curDoc = match ?: return null
            curPath = childPath
        }
        return curDoc
    }

    private fun invalidateSubtree(path: String) {
        val norm = normalize(path)
        if (norm == "/") {
            docIdCache.keys.retainAll(setOf("/"))
            return
        }
        val prefix = "$norm/"
        docIdCache.keys.removeIf { key -> key == norm || key.startsWith(prefix) }
    }

    private fun SafDocNode.toEntry(): RdpDriveEntry = RdpDriveEntry(
        name = name,
        isDir = isDir,
        size = if (isDir || size < 0L) 0UL else size.toULong(),
        modifiedMs = modifiedMs,
        readOnly = isReadOnly,
    )

    override fun stat(path: String): RdpDriveEntry? = try {
        val norm = normalize(path)
        if (norm == "/") {
            val rootNode = ops.queryDoc(ops.rootDocId)
            RdpDriveEntry(
                name = "/",
                isDir = true,
                size = 0UL,
                modifiedMs = rootNode?.modifiedMs ?: 0L,
                readOnly = false,
            )
        } else {
            val docId = resolve(norm) ?: return null
            val node = ops.queryDoc(docId)
            if (node == null) {
                invalidateSubtree(norm)
                null
            } else {
                node.copy(name = norm.substringAfterLast('/')).toEntry()
            }
        }
    } catch (_: Exception) {
        null
    }

    override fun listDir(path: String): RdpDriveListResult = try {
        val norm = normalize(path)
        val docId = resolve(norm)
            ?: return RdpDriveListResult(RdpDriveStatus.NOT_FOUND, emptyList())
        val children = ops.queryChildren(docId)
            ?: return RdpDriveListResult(RdpDriveStatus.NOT_A_DIRECTORY, emptyList())
        val entries = ArrayList<RdpDriveEntry>(children.size)
        for (child in children) {
            if (child.name.isEmpty()) continue
            val childPath = if (norm == "/") "/${child.name}" else "$norm/${child.name}"
            docIdCache[childPath] = child.docId
            entries.add(child.toEntry())
        }
        RdpDriveListResult(RdpDriveStatus.OK, entries)
    } catch (e: SecurityException) {
        RdpDriveListResult(RdpDriveStatus.ACCESS_DENIED, emptyList())
    } catch (_: Exception) {
        RdpDriveListResult(RdpDriveStatus.IO_ERROR, emptyList())
    }

    override fun mkdir(path: String): RdpDriveStatus = try {
        val norm = normalize(path)
        if (norm == "/") {
            RdpDriveStatus.ALREADY_EXISTS
        } else if (resolve(norm) != null) {
            RdpDriveStatus.ALREADY_EXISTS
        } else {
            val parentPath = norm.substringBeforeLast('/').ifEmpty { "/" }
            val name = norm.substringAfterLast('/')
            val parentDocId = resolve(parentPath) ?: return RdpDriveStatus.PATH_NOT_FOUND
            val createdId = ops.createDoc(
                parentDocId,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: return RdpDriveStatus.IO_ERROR
            docIdCache[norm] = createdId
            RdpDriveStatus.OK
        }
    } catch (_: SecurityException) {
        RdpDriveStatus.ACCESS_DENIED
    } catch (_: Exception) {
        RdpDriveStatus.IO_ERROR
    }

    override fun createFile(path: String, overwrite: Boolean): RdpDriveStatus = try {
        val norm = normalize(path)
        if (norm == "/") {
            RdpDriveStatus.IS_A_DIRECTORY
        } else {
            val existingId = resolve(norm)
            if (existingId != null) {
                val node = ops.queryDoc(existingId)
                when {
                    node?.isDir == true -> RdpDriveStatus.IS_A_DIRECTORY
                    !overwrite -> RdpDriveStatus.ALREADY_EXISTS
                    else -> {
                        ops.truncate(existingId, 0L)
                        RdpDriveStatus.OK
                    }
                }
            } else {
                val parentPath = norm.substringBeforeLast('/').ifEmpty { "/" }
                val name = norm.substringAfterLast('/')
                val parentDocId = resolve(parentPath) ?: return RdpDriveStatus.PATH_NOT_FOUND
                val createdId = ops.createDoc(parentDocId, guessSafMimeType(name), name)
                    ?: return RdpDriveStatus.IO_ERROR
                docIdCache[norm] = createdId
                RdpDriveStatus.OK
            }
        }
    } catch (_: SecurityException) {
        RdpDriveStatus.ACCESS_DENIED
    } catch (_: Exception) {
        RdpDriveStatus.IO_ERROR
    }

    override fun readAt(path: String, offset: ULong, length: UInt): RdpDriveReadResult = try {
        val norm = normalize(path)
        val docId = resolve(norm)
            ?: return RdpDriveReadResult(RdpDriveStatus.NOT_FOUND, ByteArray(0))
        val data = ops.readAt(docId, offset.toLong(), length.toInt())
        RdpDriveReadResult(RdpDriveStatus.OK, data)
    } catch (_: FileNotFoundException) {
        RdpDriveReadResult(RdpDriveStatus.NOT_FOUND, ByteArray(0))
    } catch (_: SecurityException) {
        RdpDriveReadResult(RdpDriveStatus.ACCESS_DENIED, ByteArray(0))
    } catch (_: Exception) {
        RdpDriveReadResult(RdpDriveStatus.IO_ERROR, ByteArray(0))
    }

    override fun writeAt(path: String, offset: ULong, data: ByteArray): RdpDriveWriteResult = try {
        val norm = normalize(path)
        val docId = resolve(norm)
            ?: return RdpDriveWriteResult(RdpDriveStatus.NOT_FOUND, 0u)
        val written = ops.writeAt(docId, offset.toLong(), data)
        RdpDriveWriteResult(RdpDriveStatus.OK, written.coerceAtLeast(0).toUInt())
    } catch (_: FileNotFoundException) {
        RdpDriveWriteResult(RdpDriveStatus.NOT_FOUND, 0u)
    } catch (_: SecurityException) {
        RdpDriveWriteResult(RdpDriveStatus.ACCESS_DENIED, 0u)
    } catch (_: Exception) {
        RdpDriveWriteResult(RdpDriveStatus.IO_ERROR, 0u)
    }

    override fun truncate(path: String, size: ULong): RdpDriveStatus = try {
        val norm = normalize(path)
        val docId = resolve(norm) ?: return RdpDriveStatus.NOT_FOUND
        ops.truncate(docId, size.toLong())
        RdpDriveStatus.OK
    } catch (_: FileNotFoundException) {
        RdpDriveStatus.NOT_FOUND
    } catch (_: SecurityException) {
        RdpDriveStatus.ACCESS_DENIED
    } catch (_: Exception) {
        RdpDriveStatus.IO_ERROR
    }

    override fun deletePath(path: String, isDir: Boolean): RdpDriveStatus = try {
        val norm = normalize(path)
        if (norm == "/") {
            RdpDriveStatus.ACCESS_DENIED
        } else {
            val docId = resolve(norm) ?: return RdpDriveStatus.NOT_FOUND
            if (isDir) {
                val children = ops.queryChildren(docId)
                if (children != null && children.isNotEmpty()) {
                    return RdpDriveStatus.DIRECTORY_NOT_EMPTY
                }
            }
            ops.closeCached(docId)
            val deleted = ops.deleteDoc(docId)
            if (!deleted) {
                RdpDriveStatus.IO_ERROR
            } else {
                invalidateSubtree(norm)
                RdpDriveStatus.OK
            }
        }
    } catch (_: FileNotFoundException) {
        RdpDriveStatus.NOT_FOUND
    } catch (_: SecurityException) {
        RdpDriveStatus.ACCESS_DENIED
    } catch (_: Exception) {
        RdpDriveStatus.IO_ERROR
    }

    override fun renamePath(
        fromPath: String,
        toPath: String,
        replaceIfExists: Boolean,
    ): RdpDriveStatus = try {
        val fromNorm = normalize(fromPath)
        val toNorm = normalize(toPath)
        if (fromNorm == "/" || toNorm == "/") {
            return RdpDriveStatus.ACCESS_DENIED
        }
        val sourceDocId = resolve(fromNorm) ?: return RdpDriveStatus.NOT_FOUND
        ops.closeCached(sourceDocId)

        val existingTargetId = resolve(toNorm)
        if (existingTargetId != null && existingTargetId != sourceDocId) {
            val targetNode = ops.queryDoc(existingTargetId)
            if (targetNode != null) {
                if (!replaceIfExists) return RdpDriveStatus.ALREADY_EXISTS
                if (targetNode.isDir) return RdpDriveStatus.ACCESS_DENIED
                ops.closeCached(existingTargetId)
                if (!ops.deleteDoc(existingTargetId)) return RdpDriveStatus.IO_ERROR
                invalidateSubtree(toNorm)
            }
        }

        val fromParent = fromNorm.substringBeforeLast('/').ifEmpty { "/" }
        val toParent = toNorm.substringBeforeLast('/').ifEmpty { "/" }
        val fromName = fromNorm.substringAfterLast('/')
        val toName = toNorm.substringAfterLast('/')

        var activeDocId = sourceDocId
        if (fromParent != toParent) {
            val fromParentId = resolve(fromParent) ?: return RdpDriveStatus.PATH_NOT_FOUND
            val toParentId = resolve(toParent) ?: return RdpDriveStatus.PATH_NOT_FOUND
            activeDocId = ops.moveDoc(activeDocId, fromParentId, toParentId)
                ?: return RdpDriveStatus.ACCESS_DENIED
        }
        if (fromName != toName) {
            activeDocId = ops.renameDoc(activeDocId, toName)
                ?: return RdpDriveStatus.IO_ERROR
        }

        invalidateSubtree(fromNorm)
        invalidateSubtree(toNorm)
        docIdCache[toNorm] = activeDocId
        RdpDriveStatus.OK
    } catch (_: UnsupportedOperationException) {
        RdpDriveStatus.ACCESS_DENIED
    } catch (_: SecurityException) {
        RdpDriveStatus.ACCESS_DENIED
    } catch (_: Exception) {
        RdpDriveStatus.IO_ERROR
    }

    override fun closePath(path: String) {
        val norm = normalize(path)
        val docId = docIdCache[norm] ?: return
        ops.closeCached(docId)
    }

    override fun close() {
        docIdCache.clear()
        ops.close()
    }

    companion object {
        fun fromTreeUri(context: Context, treeUri: Uri): RdpSafDriveBackend {
            return RdpSafDriveBackend(AndroidSafTreeOps(context.applicationContext, treeUri))
        }
    }
}

private val RDP_SAF_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID, // 0
    DocumentsContract.Document.COLUMN_DISPLAY_NAME, // 1
    DocumentsContract.Document.COLUMN_MIME_TYPE, // 2
    DocumentsContract.Document.COLUMN_SIZE, // 3
    DocumentsContract.Document.COLUMN_LAST_MODIFIED, // 4
    DocumentsContract.Document.COLUMN_FLAGS, // 5
)

private class AndroidSafTreeOps(
    context: Context,
    private val treeUri: Uri,
) : SafTreeOps {

    private val resolver: ContentResolver = context.contentResolver
    override val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override val rootDisplayName: String by lazy {
        queryDoc(rootDocId)?.name?.takeIf { it.isNotBlank() }
            ?: rootDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Haven" }
    }

    private data class CachedPfd(
        val pfd: ParcelFileDescriptor,
        val writable: Boolean,
    )

    private val pfdCache = ConcurrentHashMap<String, CachedPfd>()
    private val pfdLock = Any()

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun childrenUri(parentDocId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

    override fun queryDoc(docId: String): SafDocNode? {
        resolver.query(docUri(docId), RDP_SAF_PROJECTION, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getString(0) ?: docId
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (isDir || c.isNull(3)) 0L else c.getLong(3)
                val modMs = if (c.isNull(4)) 0L else c.getLong(4)
                val flags = if (c.isNull(5)) 0 else c.getInt(5)
                return SafDocNode(id, name, isDir, size, modMs, flags)
            }
        }
        return null
    }

    override fun queryChildren(parentDocId: String): List<SafDocNode>? {
        val out = ArrayList<SafDocNode>()
        val cursor = resolver.query(childrenUri(parentDocId), RDP_SAF_PROJECTION, null, null, null)
            ?: return null
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (isDir || c.isNull(3)) 0L else c.getLong(3)
                val modMs = if (c.isNull(4)) 0L else c.getLong(4)
                val flags = if (c.isNull(5)) 0 else c.getInt(5)
                out.add(SafDocNode(id, name, isDir, size, modMs, flags))
            }
        }
        return out
    }

    override fun createDoc(parentDocId: String, mimeType: String, displayName: String): String? {
        val created = DocumentsContract.createDocument(
            resolver,
            docUri(parentDocId),
            mimeType,
            displayName,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    override fun deleteDoc(docId: String): Boolean =
        DocumentsContract.deleteDocument(resolver, docUri(docId))

    override fun renameDoc(docId: String, newDisplayName: String): String? {
        val renamed = DocumentsContract.renameDocument(resolver, docUri(docId), newDisplayName)
            ?: return null
        return DocumentsContract.getDocumentId(renamed)
    }

    override fun moveDoc(
        docId: String,
        sourceParentDocId: String,
        targetParentDocId: String,
    ): String? {
        val moved = DocumentsContract.moveDocument(
            resolver,
            docUri(docId),
            docUri(sourceParentDocId),
            docUri(targetParentDocId),
        ) ?: return null
        return DocumentsContract.getDocumentId(moved)
    }

    private fun acquirePfd(docId: String, needWrite: Boolean): ParcelFileDescriptor? {
        synchronized(pfdLock) {
            val existing = pfdCache[docId]
            if (existing != null && (!needWrite || existing.writable)) {
                return existing.pfd
            }
            existing?.let {
                pfdCache.remove(docId)
                runCatching { it.pfd.close() }
            }
            if (pfdCache.size >= 32) {
                val victimKey = pfdCache.keys.firstOrNull()
                if (victimKey != null) {
                    pfdCache.remove(victimKey)?.let { runCatching { it.pfd.close() } }
                }
            }
            val mode = if (needWrite) "rw" else "r"
            val opened = runCatching { resolver.openFileDescriptor(docUri(docId), mode) }.getOrNull()
                ?: return null
            pfdCache[docId] = CachedPfd(opened, needWrite)
            return opened
        }
    }

    override fun readAt(docId: String, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val pfd = acquirePfd(docId, needWrite = false)
        if (pfd != null) {
            try {
                val buf = ByteArray(length)
                var total = 0
                while (total < length) {
                    val n = Os.pread(pfd.fileDescriptor, buf, total, length - total, offset + total)
                    if (n <= 0) break
                    total += n
                }
                return if (total == length) buf else buf.copyOf(total)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESPIPE && e.errno != OsConstants.EINVAL) {
                    throw e
                }
                closeCached(docId)
            }
        }
        // Fallback for non-seekable / stream-only SAF DocumentsProviders.
        val stream = resolver.openInputStream(docUri(docId)) ?: throw FileNotFoundException()
        stream.use { input ->
            var remainingSkip = offset
            while (remainingSkip > 0) {
                val skipped = input.skip(remainingSkip)
                if (skipped <= 0) return ByteArray(0)
                remainingSkip -= skipped
            }
            val buf = ByteArray(length)
            var total = 0
            while (total < length) {
                val n = input.read(buf, total, length - total)
                if (n < 0) break
                total += n
            }
            return if (total == length) buf else buf.copyOf(total)
        }
    }

    override fun writeAt(docId: String, offset: Long, data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val pfd = acquirePfd(docId, needWrite = true)
        if (pfd != null) {
            try {
                var total = 0
                while (total < data.size) {
                    val n = Os.pwrite(
                        pfd.fileDescriptor,
                        data,
                        total,
                        data.size - total,
                        offset + total,
                    )
                    if (n <= 0) break
                    total += n
                }
                return total
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESPIPE && e.errno != OsConstants.EINVAL) {
                    throw e
                }
                closeCached(docId)
            }
        }
        // Fallback for stream-only SAF DocumentsProviders when writing from offset 0.
        if (offset == 0L) {
            val out = resolver.openOutputStream(docUri(docId), "wt") ?: throw FileNotFoundException()
            out.use { it.write(data) }
            return data.size
        }
        throw UnsupportedOperationException("Non-seekable SAF provider cannot random-write at $offset")
    }

    override fun truncate(docId: String, size: Long) {
        val pfd = acquirePfd(docId, needWrite = true)
        if (pfd != null) {
            try {
                Os.ftruncate(pfd.fileDescriptor, size)
                return
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINVAL && e.errno != OsConstants.ESPIPE) {
                    throw e
                }
                closeCached(docId)
            }
        }
        if (size == 0L) {
            resolver.openOutputStream(docUri(docId), "wt")?.close() ?: throw FileNotFoundException()
            return
        }
        FileOutputStream(
            (resolver.openFileDescriptor(docUri(docId), "rw") ?: throw FileNotFoundException())
                .use { p ->
                    p.fileDescriptor
                },
        )
    }

    override fun closeCached(docId: String) {
        val removed = synchronized(pfdLock) { pfdCache.remove(docId) }
        if (removed != null) {
            runCatching {
                if (removed.writable) {
                    Os.fsync(removed.pfd.fileDescriptor)
                }
            }
            runCatching { removed.pfd.close() }
        }
    }

    override fun close() {
        val all = synchronized(pfdLock) {
            val values = pfdCache.values.toList()
            pfdCache.clear()
            values
        }
        for (entry in all) {
            runCatching {
                if (entry.writable) {
                    Os.fsync(entry.pfd.fileDescriptor)
                }
            }
            runCatching { entry.pfd.close() }
        }
    }
}

internal fun guessSafMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return "application/octet-stream"
    runCatching { MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) }
        .getOrNull()
        ?.let { return it }
    return when (ext) {
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}
