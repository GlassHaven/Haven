package sh.haven.feature.sftp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.rclone.RcloneSessionManager
import sh.haven.core.smb.SmbClient
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshSessionManager.SessionState
import java.io.OutputStream
import javax.inject.Inject

private const val TAG = "SftpViewModel"

data class SftpEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: Long,
    val permissions: String,
)

enum class SortMode {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC
}

/** Transfer progress for download/upload operations. */
data class TransferProgress(
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

@HiltViewModel
class SftpViewModel @Inject constructor(
    private val sessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val smbSessionManager: SmbSessionManager,
    private val rcloneSessionManager: RcloneSessionManager,
    private val rcloneClient: RcloneClient,
    private val repository: ConnectionRepository,
    private val preferencesRepository: UserPreferencesRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _connectedProfiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    val connectedProfiles: StateFlow<List<ConnectionProfile>> = _connectedProfiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _allEntries = MutableStateFlow<List<SftpEntry>>(emptyList())
    private val _entries = MutableStateFlow<List<SftpEntry>>(emptyList())
    val entries: StateFlow<List<SftpEntry>> = _entries.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
    val transferProgress: StateFlow<TransferProgress?> = _transferProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Emitted after a successful download with the destination URI for "Open" action. */
    data class DownloadResult(val fileName: String, val uri: Uri)
    private val _lastDownload = MutableStateFlow<DownloadResult?>(null)
    val lastDownload: StateFlow<DownloadResult?> = _lastDownload.asStateFlow()
    fun clearLastDownload() { _lastDownload.value = null }

    /** Upload conflict resolution. */
    enum class ConflictChoice { SKIP, REPLACE, REPLACE_ALL, SKIP_ALL }
    data class UploadConflict(
        val fileName: String,
        val deferred: CompletableDeferred<ConflictChoice>,
    )
    private val _uploadConflict = MutableStateFlow<UploadConflict?>(null)
    val uploadConflict: StateFlow<UploadConflict?> = _uploadConflict.asStateFlow()

    fun resolveConflict(choice: ConflictChoice) {
        _uploadConflict.value?.deferred?.complete(choice)
        _uploadConflict.value = null
    }

    private var sftpChannel: ChannelSftp? = null
    private var activeSmbClient: SmbClient? = null

    /** Tracks which active profile is SMB (vs SFTP). */
    private val _isSmbProfile = MutableStateFlow(false)

    /** Tracks which active profile is rclone (vs SFTP/SMB). */
    private val _isRcloneProfile = MutableStateFlow(false)

    /** rclone remote name for the active profile. */
    private var activeRcloneRemote: String? = null

    /** Pending SMB profile to auto-select when navigating to Files tab. */
    private val _pendingSmbProfileId = MutableStateFlow<String?>(null)

    /** Pending rclone profile to auto-select when navigating to Files tab. */
    private val _pendingRcloneProfileId = MutableStateFlow<String?>(null)

    init {
        // Restore persisted sort mode
        viewModelScope.launch {
            val saved = preferencesRepository.sftpSortMode.first()
            _sortMode.value = try {
                SortMode.valueOf(saved)
            } catch (_: IllegalArgumentException) {
                SortMode.NAME_ASC
            }
        }
    }

    fun syncConnectedProfiles() {
        viewModelScope.launch {
            // Collect profile IDs from SSH sessions
            val sshProfileIds = sessionManager.sessions.value.values
                .filter { it.status == SessionState.Status.CONNECTED }
                .map { it.profileId }
                .toSet()

            // Collect profile IDs from mosh sessions that have a live SSH client
            val moshProfileIds = moshSessionManager.sessions.value.values
                .filter {
                    it.status == MoshSessionManager.SessionState.Status.CONNECTED &&
                        it.sshClient != null
                }
                .map { it.profileId }
                .toSet()

            // Collect profile IDs from ET sessions that have a live SSH client
            val etProfileIds = etSessionManager.sessions.value.values
                .filter {
                    it.status == EtSessionManager.SessionState.Status.CONNECTED &&
                        it.sshClient != null
                }
                .map { it.profileId }
                .toSet()

            // Collect profile IDs from SMB sessions
            val smbProfileIds = smbSessionManager.sessions.value.values
                .filter { it.status == SmbSessionManager.SessionState.Status.CONNECTED }
                .map { it.profileId }
                .toSet()

            // Collect profile IDs from rclone sessions
            val rcloneProfileIds = rcloneSessionManager.sessions.value.values
                .filter { it.status == RcloneSessionManager.SessionState.Status.CONNECTED }
                .map { it.profileId }
                .toSet()

            val connectedProfileIds = sshProfileIds + moshProfileIds + etProfileIds + smbProfileIds + rcloneProfileIds

            if (connectedProfileIds.isEmpty()) {
                _connectedProfiles.value = emptyList()
                _activeProfileId.value = null
                sftpChannel = null
                activeSmbClient = null
                activeRcloneRemote = null
                return@launch
            }

            val profiles = withContext(Dispatchers.IO) { repository.getAll() }
            _connectedProfiles.value = profiles.filter { it.id in connectedProfileIds }

            // Handle pending SMB navigation
            val pendingSmb = _pendingSmbProfileId.value
            if (pendingSmb != null && pendingSmb in connectedProfileIds) {
                _pendingSmbProfileId.value = null
                selectProfile(pendingSmb)
                return@launch
            }

            // Handle pending rclone navigation
            val pendingRclone = _pendingRcloneProfileId.value
            if (pendingRclone != null && pendingRclone in connectedProfileIds) {
                _pendingRcloneProfileId.value = null
                selectProfile(pendingRclone)
                return@launch
            }

            // Auto-select first connected profile if none selected
            if (_activeProfileId.value == null || _activeProfileId.value !in connectedProfileIds) {
                _connectedProfiles.value.firstOrNull()?.let { selectProfile(it.id) }
            }
        }
    }

    fun setPendingSmbProfile(profileId: String) {
        _pendingSmbProfileId.value = profileId
    }

    fun setPendingRcloneProfile(profileId: String) {
        _pendingRcloneProfileId.value = profileId
    }

    fun selectProfile(profileId: String) {
        val isSmb = smbSessionManager.isProfileConnected(profileId)
        val isRclone = rcloneSessionManager.isProfileConnected(profileId)
        _isSmbProfile.value = isSmb
        _isRcloneProfile.value = isRclone

        _activeProfileId.value = profileId
        sftpChannel = null
        activeSmbClient = null
        activeRcloneRemote = null
        _currentPath.value = "/"
        _allEntries.value = emptyList()
        _entries.value = emptyList()

        when {
            isRclone -> openRcloneAndList(profileId)
            isSmb -> openSmbAndList(profileId)
            else -> openSftpAndList(profileId, "/")
        }
    }

    fun navigateTo(path: String) {
        val profileId = _activeProfileId.value ?: return
        _currentPath.value = path
        when {
            _isRcloneProfile.value -> listRcloneDirectory(path)
            _isSmbProfile.value -> listSmbDirectory(path)
            else -> listDirectory(profileId, path)
        }
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current == "/") return
        val parent = current.trimEnd('/').substringBeforeLast('/', "/")
        navigateTo(if (parent.isEmpty()) "/" else parent)
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        _allEntries.value = sortEntries(_allEntries.value, mode)
        applyFilter()
        // Persist the choice
        viewModelScope.launch {
            preferencesRepository.setSftpSortMode(mode.name)
        }
    }

    fun toggleShowHidden() {
        _showHidden.value = !_showHidden.value
        applyFilter()
    }

    private fun applyFilter() {
        val all = _allEntries.value
        _entries.value = if (_showHidden.value) all else all.filter { !it.name.startsWith(".") }
    }

    fun refresh() {
        val profileId = _activeProfileId.value ?: return
        when {
            _isRcloneProfile.value -> listRcloneDirectory(_currentPath.value)
            _isSmbProfile.value -> listSmbDirectory(_currentPath.value)
            else -> listDirectory(profileId, _currentPath.value)
        }
    }

    fun downloadFile(entry: SftpEntry, destinationUri: Uri) {
        val profileId = _activeProfileId.value ?: return
        viewModelScope.launch {
            try {
                _loading.value = true
                _transferProgress.value = TransferProgress(entry.name, entry.size, 0)
                withContext(Dispatchers.IO) {
                    val outputStream: OutputStream = appContext.contentResolver.openOutputStream(destinationUri)
                        ?: throw IllegalStateException("Cannot open output stream")
                    outputStream.use { out ->
                        if (_isRcloneProfile.value) {
                            val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                            val tempFile = java.io.File(appContext.cacheDir, "rclone_dl_${entry.name}")
                            try {
                                rcloneClient.copyFile(remote, entry.path, tempFile.parent!!, tempFile.name)
                                tempFile.inputStream().use { it.copyTo(out) }
                                _transferProgress.value = TransferProgress(entry.name, entry.size, entry.size)
                            } finally {
                                tempFile.delete()
                            }
                        } else if (_isSmbProfile.value) {
                            val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                            client.download(entry.path, out) { transferred, total ->
                                _transferProgress.value = TransferProgress(entry.name, total, transferred)
                            }
                        } else {
                            val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                            val monitor = object : SftpProgressMonitor {
                                private var total = 0L
                                private var transferred = 0L

                                override fun init(op: Int, src: String, dest: String, max: Long) {
                                    total = if (max == SftpProgressMonitor.UNKNOWN_SIZE) entry.size else max
                                    transferred = 0
                                    _transferProgress.value = TransferProgress(entry.name, total, 0)
                                }

                                override fun count(bytes: Long): Boolean {
                                    transferred += bytes
                                    _transferProgress.value = TransferProgress(entry.name, total, transferred)
                                    return true
                                }

                                override fun end() {
                                    _transferProgress.value = TransferProgress(entry.name, total, total)
                                }
                            }
                            channel.get(entry.path, out, monitor)
                        }
                    }
                }
                _lastDownload.value = DownloadResult(entry.name, destinationUri)
                _message.value = "Downloaded ${entry.name}"
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _error.value = "Download failed: ${e.message}"
            } finally {
                _loading.value = false
                _transferProgress.value = null
            }
        }
    }

    fun uploadFile(fileName: String, sourceUri: Uri) {
        val profileId = _activeProfileId.value ?: return
        val destPath = _currentPath.value.trimEnd('/') + "/" + fileName
        Log.d(TAG, "Upload: '$fileName' -> '$destPath' (source: $sourceUri)")
        viewModelScope.launch {
            try {
                // Check for conflict before uploading
                val (proceed, _) = checkConflict(fileName, null)
                if (!proceed) {
                    _message.value = "Skipped $fileName"
                    return@launch
                }
                _loading.value = true
                // Get source file size for progress
                val fileSize = appContext.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                } ?: -1L
                _transferProgress.value = TransferProgress(fileName, fileSize, 0)
                withContext(Dispatchers.IO) {
                    val inputStream = appContext.contentResolver.openInputStream(sourceUri)
                        ?: throw IllegalStateException("Cannot open input stream")
                    inputStream.use { input ->
                        if (_isRcloneProfile.value) {
                            val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                            val tempFile = java.io.File(appContext.cacheDir, "rclone_ul_$fileName")
                            try {
                                tempFile.outputStream().use { input.copyTo(it) }
                                rcloneClient.copyFile(tempFile.parent!!, tempFile.name, remote, destPath)
                                _transferProgress.value = TransferProgress(fileName, fileSize, fileSize)
                            } finally {
                                tempFile.delete()
                            }
                        } else if (_isSmbProfile.value) {
                            val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                            client.upload(input, destPath, fileSize) { transferred, total ->
                                _transferProgress.value = TransferProgress(fileName, total, transferred)
                            }
                        } else {
                            val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                            val monitor = object : SftpProgressMonitor {
                                private var total = 0L
                                private var transferred = 0L

                                override fun init(op: Int, src: String, dest: String, max: Long) {
                                    total = if (max == SftpProgressMonitor.UNKNOWN_SIZE) fileSize else max
                                    transferred = 0
                                    _transferProgress.value = TransferProgress(fileName, total, 0)
                                }

                                override fun count(bytes: Long): Boolean {
                                    transferred += bytes
                                    _transferProgress.value = TransferProgress(fileName, total, transferred)
                                    return true
                                }

                                override fun end() {
                                    _transferProgress.value = TransferProgress(fileName, total, total)
                                }
                            }
                            channel.put(input, destPath, monitor)
                        }
                    }
                    Log.d(TAG, "Upload complete: '$destPath'")
                }
                _message.value = "Uploaded $fileName"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                _error.value = "Upload failed: ${e.message}"
            } finally {
                _loading.value = false
                _transferProgress.value = null
            }
        }
    }

    fun deleteEntry(entry: SftpEntry) {
        val profileId = _activeProfileId.value ?: return
        viewModelScope.launch {
            try {
                _loading.value = true
                withContext(Dispatchers.IO) {
                    if (_isRcloneProfile.value) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        if (entry.isDirectory) {
                            rcloneClient.deleteDir(remote, entry.path)
                        } else {
                            rcloneClient.deleteFile(remote, entry.path)
                        }
                    } else if (_isSmbProfile.value) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        client.delete(entry.path, entry.isDirectory)
                    } else {
                        val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                        if (entry.isDirectory) {
                            channel.rmdir(entry.path)
                        } else {
                            channel.rm(entry.path)
                        }
                    }
                }
                _message.value = "Deleted ${entry.name}"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed", e)
                _error.value = "Delete failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun uploadFolder(folderUri: Uri) {
        val profileId = _activeProfileId.value ?: return
        val destBase = _currentPath.value
        viewModelScope.launch {
            try {
                _loading.value = true
                val folder = DocumentFile.fromTreeUri(appContext, folderUri) ?: return@launch
                val folderName = folder.name ?: "upload"

                // Collect all files recursively
                data class FileItem(val doc: DocumentFile, val relativePath: String)
                val files = mutableListOf<FileItem>()
                fun walk(dir: DocumentFile, prefix: String) {
                    for (child in dir.listFiles()) {
                        val childPath = if (prefix.isEmpty()) child.name!! else "$prefix/${child.name}"
                        if (child.isDirectory) {
                            walk(child, childPath)
                        } else {
                            files.add(FileItem(child, childPath))
                        }
                    }
                }
                walk(folder, folderName)

                // Check if the folder already exists in the destination
                val (proceed, _) = checkConflict(folderName, null)
                if (!proceed) {
                    _message.value = "Skipped $folderName"
                    return@launch
                }

                val totalFiles = files.size
                var completedFiles = 0
                var totalBytes = files.sumOf { it.doc.length() }
                var transferredBytes = 0L

                for (item in files) {
                    val destPath = destBase.trimEnd('/') + "/" + item.relativePath
                    val destDir = destPath.substringBeforeLast('/')
                    val fileName = item.doc.name ?: continue
                    val fileSize = item.doc.length()

                    _transferProgress.value = TransferProgress(
                        "${completedFiles + 1}/$totalFiles: $fileName",
                        totalBytes,
                        transferredBytes,
                    )

                    withContext(Dispatchers.IO) {
                        if (_isRcloneProfile.value) {
                            val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                            // Ensure parent directory exists
                            rcloneClient.mkdir(remote, destDir)
                            // Copy via temp file
                            val tempFile = java.io.File(appContext.cacheDir, "rclone_ul_$fileName")
                            try {
                                appContext.contentResolver.openInputStream(item.doc.uri)?.use { input ->
                                    tempFile.outputStream().use { input.copyTo(it) }
                                }
                                rcloneClient.copyFile(tempFile.parent!!, tempFile.name, remote, destPath)
                            } finally {
                                tempFile.delete()
                            }
                        } else if (_isSmbProfile.value) {
                            val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                            client.mkdir(destDir)
                            appContext.contentResolver.openInputStream(item.doc.uri)?.use { input ->
                                client.upload(input, destPath, fileSize) { _, _ -> }
                            }
                        } else {
                            val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                            // Create parent dirs recursively
                            try { channel.mkdir(destDir) } catch (_: Exception) {}
                            appContext.contentResolver.openInputStream(item.doc.uri)?.use { input ->
                                channel.put(input, destPath)
                            }
                        }
                    }

                    completedFiles++
                    transferredBytes += fileSize
                }

                _message.value = "Uploaded $totalFiles files from $folderName"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Folder upload failed", e)
                _error.value = "Folder upload failed: ${e.message}"
            } finally {
                _loading.value = false
                _transferProgress.value = null
            }
        }
    }

    fun createDirectory(name: String) {
        val profileId = _activeProfileId.value ?: return
        val parentPath = _currentPath.value
        val fullPath = parentPath.trimEnd('/') + "/" + name
        viewModelScope.launch {
            try {
                _loading.value = true
                withContext(Dispatchers.IO) {
                    if (_isRcloneProfile.value) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        rcloneClient.mkdir(remote, fullPath)
                    } else if (_isSmbProfile.value) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        client.mkdir(fullPath)
                    } else {
                        val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                        channel.mkdir(fullPath)
                    }
                }
                _message.value = "Created $name"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Create directory failed", e)
                _error.value = "Create folder failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Check if a file exists in the current directory listing.
     * Returns true if upload should proceed, false if skipped.
     * Shows a conflict dialog if the file already exists.
     */
    private suspend fun checkConflict(
        fileName: String,
        bulkChoice: ConflictChoice?,
    ): Pair<Boolean, ConflictChoice?> {
        // If user already chose Replace All or Skip All, use that
        if (bulkChoice == ConflictChoice.REPLACE_ALL) return true to bulkChoice
        if (bulkChoice == ConflictChoice.SKIP_ALL) return false to bulkChoice

        val existingNames = _allEntries.value.map { it.name }.toSet()
        if (fileName !in existingNames) return true to bulkChoice

        // File exists — ask the user
        val deferred = CompletableDeferred<ConflictChoice>()
        _uploadConflict.value = UploadConflict(fileName, deferred)
        val choice = deferred.await()
        return when (choice) {
            ConflictChoice.REPLACE, ConflictChoice.REPLACE_ALL -> true to choice
            ConflictChoice.SKIP, ConflictChoice.SKIP_ALL -> false to choice
        }
    }

    fun dismissError() { _error.value = null }
    fun dismissMessage() { _message.value = null }

    private fun openSftpAndList(profileId: String, path: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                withContext(Dispatchers.IO) {
                    val channel = sessionManager.openSftpForProfile(profileId)
                        ?: openMoshSftpChannel(profileId)
                        ?: throw IllegalStateException("Session not connected")
                    sftpChannel = channel
                    // Navigate to home directory on first connect
                    val home = channel.home
                    _currentPath.value = home
                    loadEntries(channel, home)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SFTP open failed", e)
                _error.value = "SFTP failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private fun listDirectory(profileId: String, path: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                withContext(Dispatchers.IO) {
                    val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                    loadEntries(channel, path)
                }
            } catch (e: Exception) {
                Log.e(TAG, "List directory failed", e)
                _error.value = "Failed to list: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private fun loadEntries(channel: ChannelSftp, path: String) {
        val results = mutableListOf<SftpEntry>()
        val symlinkIndices = mutableListOf<Int>()
        channel.ls(path) { lsEntry ->
            val name = lsEntry.filename
            if (name != "." && name != "..") {
                val attrs = lsEntry.attrs
                val fullPath = path.trimEnd('/') + "/" + name
                if (attrs.isLink) symlinkIndices.add(results.size)
                results.add(
                    SftpEntry(
                        name = name,
                        path = fullPath,
                        isDirectory = attrs.isDir,
                        size = attrs.size,
                        modifiedTime = attrs.mTime.toLong(),
                        permissions = attrs.permissionsString ?: "",
                    )
                )
            }
            ChannelSftp.LsEntrySelector.CONTINUE
        }
        // Resolve symlinks AFTER ls() completes — calling stat() inside the ls
        // callback corrupts JSch's read buffer (interleaved SFTP requests).
        for (i in symlinkIndices) {
            try {
                if (channel.stat(results[i].path).isDir) {
                    results[i] = results[i].copy(isDirectory = true)
                }
            } catch (_: Exception) {
                // broken symlink or permission denied
            }
        }
        _allEntries.value = sortEntries(results, _sortMode.value)
        applyFilter()
    }

    private fun getOrOpenChannel(profileId: String): ChannelSftp? {
        sftpChannel?.let { if (it.isConnected) return it }
        // Try SSH session first, then mosh/ET bootstrap SSH client
        val channel = sessionManager.openSftpForProfile(profileId)
            ?: openMoshSftpChannel(profileId)
            ?: openEtSftpChannel(profileId)
            ?: return null
        sftpChannel = channel
        return channel
    }

    private fun openMoshSftpChannel(profileId: String): ChannelSftp? {
        val client = moshSessionManager.getSshClientForProfile(profileId) as? SshClient
            ?: return null
        return try {
            client.openSftpChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SFTP channel via mosh SSH client", e)
            null
        }
    }

    private fun openEtSftpChannel(profileId: String): ChannelSftp? {
        val client = etSessionManager.getSshClientForProfile(profileId) as? SshClient
            ?: return null
        return try {
            client.openSftpChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SFTP channel via ET SSH client", e)
            null
        }
    }

    private fun openSmbAndList(profileId: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                withContext(Dispatchers.IO) {
                    val client = smbSessionManager.getClientForProfile(profileId)
                        ?: throw IllegalStateException("SMB session not connected")
                    activeSmbClient = client
                    _currentPath.value = "/"
                    loadSmbEntries(client, "/")
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMB open failed", e)
                _error.value = "SMB failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private fun listSmbDirectory(path: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                withContext(Dispatchers.IO) {
                    val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                    loadSmbEntries(client, path)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMB list directory failed", e)
                _error.value = "Failed to list: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private fun loadSmbEntries(client: SmbClient, path: String) {
        val smbEntries = client.listDirectory(path)
        val results = smbEntries.map { entry ->
            SftpEntry(
                name = entry.name,
                path = entry.path,
                isDirectory = entry.isDirectory,
                size = entry.size,
                modifiedTime = entry.modifiedTime,
                permissions = entry.permissions,
            )
        }
        _allEntries.value = sortEntries(results, _sortMode.value)
        applyFilter()
    }

    // ── Rclone helpers ────────────────────────────────────────────────

    private fun openRcloneAndList(profileId: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                withContext(Dispatchers.IO) {
                    val remoteName = rcloneSessionManager.getRemoteNameForProfile(profileId)
                        ?: throw IllegalStateException("Rclone session not connected")
                    activeRcloneRemote = remoteName
                    _currentPath.value = "/"
                    loadRcloneEntries(remoteName, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rclone open failed", e)
                _error.value = "Cloud storage failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private fun listRcloneDirectory(path: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                withContext(Dispatchers.IO) {
                    val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                    loadRcloneEntries(remote, path)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rclone list directory failed", e)
                _error.value = "Failed to list: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private fun loadRcloneEntries(remote: String, path: String) {
        val rcloneEntries = rcloneClient.listDirectory(remote, path)
        val results = rcloneEntries.map { entry ->
            val modTime = try {
                java.time.Instant.parse(entry.modTime).epochSecond
            } catch (_: Exception) {
                0L
            }
            SftpEntry(
                name = entry.name,
                path = if (path.isEmpty() || path == "/") entry.name else "${path.trimEnd('/')}/${entry.name}",
                isDirectory = entry.isDir,
                size = entry.size,
                modifiedTime = modTime,
                permissions = if (entry.isDir) "drwxr-xr-x" else "-rw-r--r--",
            )
        }
        _allEntries.value = sortEntries(results, _sortMode.value)
        applyFilter()
    }

    private fun sortEntries(entries: List<SftpEntry>, mode: SortMode): List<SftpEntry> {
        val dirs = entries.filter { it.isDirectory }
        val files = entries.filter { !it.isDirectory }
        val sortedDirs = when (mode) {
            SortMode.NAME_ASC -> dirs.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> dirs.sortedByDescending { it.name.lowercase() }
            SortMode.SIZE_ASC -> dirs.sortedBy { it.name.lowercase() }
            SortMode.SIZE_DESC -> dirs.sortedByDescending { it.name.lowercase() }
            SortMode.DATE_ASC -> dirs.sortedBy { it.modifiedTime }
            SortMode.DATE_DESC -> dirs.sortedByDescending { it.modifiedTime }
        }
        val sortedFiles = when (mode) {
            SortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            SortMode.SIZE_ASC -> files.sortedBy { it.size }
            SortMode.SIZE_DESC -> files.sortedByDescending { it.size }
            SortMode.DATE_ASC -> files.sortedBy { it.modifiedTime }
            SortMode.DATE_DESC -> files.sortedByDescending { it.modifiedTime }
        }
        return sortedDirs + sortedFiles
    }
}
