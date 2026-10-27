package sh.haven.app

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.runBlocking
import sh.haven.core.ssh.sftp.ListResult
import sh.haven.core.ssh.sftp.SftpSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.smb.SmbClient
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import java.io.File
import java.io.FileOutputStream

private const val TAG = "HavenDocProvider"

/**
 * Exposes connected SFTP and SMB sessions as document roots in the Android
 * system file picker. Other apps can browse, open, upload, and delete files
 * on remote servers through Haven's active connections.
 */
class HavenDocumentsProvider : DocumentsProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun sshSessionManager(): SshSessionManager
        fun moshSessionManager(): MoshSessionManager
        fun etSessionManager(): EtSessionManager
        fun smbSessionManager(): SmbSessionManager
        fun connectionRepository(): ConnectionRepository
    }

    private val entryPoint: ProviderEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context!!.applicationContext,
            ProviderEntryPoint::class.java,
        )
    }

    companion object {
        private val AUTHORITY = BuildConfig.APPLICATION_ID + ".provider"

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        // Document ID format: "profileId:path"
        // Root document ID: "profileId:/"
        private fun encodeDocId(profileId: String, path: String): String =
            "$profileId:$path"

        private fun decodeDocId(docId: String): Pair<String, String> {
            val colon = docId.indexOf(':')
            if (colon < 0) return docId to "/"
            return docId.substring(0, colon) to docId.substring(colon + 1)
        }
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: ROOT_PROJECTION
        val cursor = MatrixCursor(cols)

        val ssh = entryPoint.sshSessionManager()
        val mosh = entryPoint.moshSessionManager()
        val et = entryPoint.etSessionManager()
        val smb = entryPoint.smbSessionManager()

        // Collect connected profile IDs that can serve files
        val sftpProfileIds = mutableSetOf<String>()
        ssh.sessions.value.values
            .filter { it.status == SshSessionManager.SessionState.Status.CONNECTED }
            .forEach { sftpProfileIds.add(it.profileId) }
        mosh.sessions.value.values
            .filter {
                it.status == MoshSessionManager.SessionState.Status.CONNECTED &&
                    it.sshClient != null
            }
            .forEach { sftpProfileIds.add(it.profileId) }
        et.sessions.value.values
            .filter {
                it.status == EtSessionManager.SessionState.Status.CONNECTED &&
                    it.sshClient != null
            }
            .forEach { sftpProfileIds.add(it.profileId) }

        val smbProfileIds = smb.sessions.value.values
            .filter { it.status == SmbSessionManager.SessionState.Status.CONNECTED }
            .map { it.profileId }
            .toSet()

        // Build labels from session state (avoid blocking DB query in queryRoots)
        val sshLabels = ssh.sessions.value.values.associate { it.profileId to it.label }
        val moshLabels = mosh.sessions.value.values.associate { it.profileId to it.label }
        val etLabels = et.sessions.value.values.associate { it.profileId to it.label }
        val smbLabels = smb.sessions.value.values.associate { it.profileId to it.label }

        for (profileId in sftpProfileIds) {
            val label = sshLabels[profileId]
                ?: moshLabels[profileId]
                ?: etLabels[profileId]
                ?: profileId
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, profileId)
                add(Root.COLUMN_DOCUMENT_ID, encodeDocId(profileId, "/"))
                add(Root.COLUMN_TITLE, label)
                add(Root.COLUMN_SUMMARY, "SFTP")
                add(Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }

        for (profileId in smbProfileIds) {
            val label = smbLabels[profileId] ?: profileId
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, profileId)
                add(Root.COLUMN_DOCUMENT_ID, encodeDocId(profileId, "/"))
                add(Root.COLUMN_TITLE, label)
                add(Root.COLUMN_SUMMARY, "SMB")
                add(Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }

        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DOC_PROJECTION
        val cursor = MatrixCursor(cols)
        val (profileId, path) = decodeDocId(documentId)

        if (path == "/") {
            // Root document
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_DISPLAY_NAME, getRootLabel(profileId))
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_SIZE, 0L)
                add(Document.COLUMN_LAST_MODIFIED, 0L)
                add(Document.COLUMN_FLAGS,
                    Document.FLAG_DIR_SUPPORTS_CREATE)
            }
            return cursor
        }

        // Query single file/dir metadata
        val smbClient = getSmbClient(profileId)
        if (smbClient != null) {
            val parentPath = path.trimEnd('/').substringBeforeLast('/', "/")
            val name = path.trimEnd('/').substringAfterLast('/')
            val entries = smbClient.listDirectory(parentPath)
            val entry = entries.firstOrNull { it.name == name }
            if (entry != null) {
                addDocRow(cursor, profileId, entry.name, entry.path,
                    entry.isDirectory, entry.size, entry.modifiedTime)
            }
        } else {
            val session = getSftpSession(profileId)
            if (session != null) {
                val name = path.trimEnd('/').substringAfterLast('/')
                try {
                    val attrs = runBlocking { session.stat(path) }
                    addDocRow(cursor, profileId, name, path,
                        attrs.isDirectory, attrs.size, attrs.modifiedTimeSeconds.toLong())
                } catch (e: Exception) {
                    Log.e(TAG, "stat failed: $path", e)
                }
            }
        }

        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: DOC_PROJECTION
        val cursor = MatrixCursor(cols)
        val (profileId, path) = decodeDocId(parentDocumentId)

        val smbClient = getSmbClient(profileId)
        if (smbClient != null) {
            try {
                val entries = smbClient.listDirectory(path)
                for (entry in entries) {
                    addDocRow(cursor, profileId, entry.name, entry.path,
                        entry.isDirectory, entry.size, entry.modifiedTime)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMB listDirectory failed: $path", e)
            }
        } else {
            val session = getSftpSession(profileId)
            if (session != null) {
                try {
                    data class PendingEntry(val name: String, val path: String, val isLink: Boolean,
                        val isDir: Boolean, val size: Long, val mTime: Long)
                    val pending = mutableListOf<PendingEntry>()
                    runBlocking {
                        session.list(path) { attrs ->
                            val childPath = path.trimEnd('/') + "/" + attrs.filename
                            pending.add(PendingEntry(attrs.filename, childPath, attrs.isSymlink,
                                attrs.isDirectory, attrs.size, attrs.modifiedTimeSeconds.toLong()))
                            ListResult.CONTINUE
                        }
                    }
                    for (entry in pending) {
                        val isDir = if (entry.isLink && !entry.isDir) {
                            try { runBlocking { session.stat(entry.path).isDirectory } } catch (_: Exception) { false }
                        } else {
                            entry.isDir
                        }
                        addDocRow(cursor, profileId, entry.name, entry.path,
                            isDir, entry.size, entry.mTime)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SFTP ls failed: $path", e)
                }
            }
        }

        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val (profileId, path) = decodeDocId(documentId)
        val isWrite = mode.contains("w")

        val cacheFile = File(context!!.cacheDir, "haven_doc_${documentId.hashCode()}")

        val smbClient = getSmbClient(profileId)
        if (smbClient != null) {
            if (isWrite) {
                return openWritableSmbFile(smbClient, path, cacheFile)
            }
            FileOutputStream(cacheFile).use { out ->
                smbClient.download(path, out) { _, _ -> }
            }
        } else {
            val session = getSftpSession(profileId)
                ?: throw IllegalStateException("Not connected")
            if (isWrite) {
                return openWritableSftpFile(session, path, cacheFile)
            }
            FileOutputStream(cacheFile).use { out ->
                runBlocking { session.download(path, out) { _, _ -> } }
            }
        }

        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val (profileId, parentPath) = decodeDocId(parentDocumentId)
        val childPath = parentPath.trimEnd('/') + "/" + displayName
        val isDir = mimeType == Document.MIME_TYPE_DIR

        val smbClient = getSmbClient(profileId)
        if (smbClient != null) {
            if (isDir) {
                smbClient.mkdir(childPath)
            } else {
                // Create empty file
                "".byteInputStream().use { input ->
                    smbClient.upload(input, childPath, 0) { _, _ -> }
                }
            }
        } else {
            val session = getSftpSession(profileId)
                ?: throw IllegalStateException("Not connected")
            runBlocking {
                if (isDir) {
                    session.mkdir(childPath)
                } else {
                    // Create empty file via a zero-byte upload.
                    "".byteInputStream().use { input ->
                        session.upload(input, 0L, childPath) { _, _ -> }
                    }
                }
            }
        }

        val newDocId = encodeDocId(profileId, childPath)
        notifyChange(newDocId)
        return newDocId
    }

    override fun deleteDocument(documentId: String) {
        val (profileId, path) = decodeDocId(documentId)

        val smbClient = getSmbClient(profileId)
        if (smbClient != null) {
            // Check if directory by listing parent
            val parentPath = path.trimEnd('/').substringBeforeLast('/', "/")
            val name = path.trimEnd('/').substringAfterLast('/')
            val entries = smbClient.listDirectory(parentPath)
            val isDir = entries.firstOrNull { it.name == name }?.isDirectory == true
            smbClient.delete(path, isDir)
        } else {
            val session = getSftpSession(profileId)
                ?: throw IllegalStateException("Not connected")
            runBlocking {
                val attrs = session.stat(path)
                if (attrs.isDirectory) {
                    session.rmdir(path)
                } else {
                    session.rm(path)
                }
            }
        }

        notifyChange(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentProfile, parentPath) = decodeDocId(parentDocumentId)
        val (childProfile, childPath) = decodeDocId(documentId)
        if (parentProfile != childProfile) return false
        val normalParent = parentPath.trimEnd('/') + "/"
        return childPath.startsWith(normalParent)
    }

    override fun getDocumentType(documentId: String): String {
        val (_, path) = decodeDocId(documentId)
        if (path == "/" || path.endsWith("/")) return Document.MIME_TYPE_DIR
        return getMimeType(path)
    }

    // --- helpers ---

    private fun addDocRow(
        cursor: MatrixCursor,
        profileId: String,
        name: String,
        path: String,
        isDirectory: Boolean,
        size: Long,
        modifiedTime: Long,
    ) {
        val docId = encodeDocId(profileId, path)
        val mime = if (isDirectory) Document.MIME_TYPE_DIR else getMimeType(name)
        var flags = 0
        if (isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        }
        flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (!isDirectory) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, size)
            add(Document.COLUMN_LAST_MODIFIED, modifiedTime * 1000) // epoch seconds → ms
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun getRootLabel(profileId: String): String {
        val ssh = entryPoint.sshSessionManager()
        val mosh = entryPoint.moshSessionManager()
        val et = entryPoint.etSessionManager()
        val smb = entryPoint.smbSessionManager()
        return ssh.sessions.value.values.firstOrNull { it.profileId == profileId }?.label
            ?: mosh.sessions.value.values.firstOrNull { it.profileId == profileId }?.label
            ?: et.sessions.value.values.firstOrNull { it.profileId == profileId }?.label
            ?: smb.sessions.value.values.firstOrNull { it.profileId == profileId }?.label
            ?: profileId
    }

    private fun getSmbClient(profileId: String): SmbClient? {
        return entryPoint.smbSessionManager().getClientForProfile(profileId)
    }

    private fun getSftpSession(profileId: String): SftpSession? {
        val ssh = entryPoint.sshSessionManager()
        val mosh = entryPoint.moshSessionManager()
        val et = entryPoint.etSessionManager()

        return ssh.openSftpSession(profileId)
            ?: (mosh.getSshClientForProfile(profileId) as? SshClient)
                ?.openSftpSession()
            ?: (et.getSshClientForProfile(profileId) as? SshClient)
                ?.openSftpSession()
    }

    private fun openWritableSmbFile(
        client: SmbClient,
        remotePath: String,
        cacheFile: File,
    ): ParcelFileDescriptor {
        val handler = android.os.Handler(context!!.mainLooper)
        return ParcelFileDescriptor.open(
            cacheFile,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
            handler,
        ) { e ->
            // Called when the writing app closes the file descriptor
            if (e == null) {
                try {
                    cacheFile.inputStream().use { input ->
                        client.upload(input, remotePath, cacheFile.length()) { _, _ -> }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "SMB write-back failed: $remotePath", ex)
                }
            }
            cacheFile.delete()
        }
    }

    private fun openWritableSftpFile(
        session: SftpSession,
        remotePath: String,
        cacheFile: File,
    ): ParcelFileDescriptor {
        val handler = android.os.Handler(context!!.mainLooper)
        return ParcelFileDescriptor.open(
            cacheFile,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
            handler,
        ) { e ->
            if (e == null) {
                try {
                    cacheFile.inputStream().use { input ->
                        runBlocking {
                            session.upload(input, cacheFile.length(), remotePath) { _, _ -> }
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "SFTP write-back failed: $remotePath", ex)
                }
            }
            cacheFile.delete()
        }
    }

    private fun notifyChange(docId: String) {
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, docId)
        context?.contentResolver?.notifyChange(uri, null)
    }
}
