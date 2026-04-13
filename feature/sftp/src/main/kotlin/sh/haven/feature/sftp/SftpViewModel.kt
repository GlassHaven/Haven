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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
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
import sh.haven.core.rclone.SyncConfig
import sh.haven.core.rclone.SyncProgress
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
    val mimeType: String = "",
)

/**
 * Where the output of a media conversion should be saved.
 *
 * - [DOWNLOADS]: local device Downloads folder (MediaStore-backed on API 29+).
 * - [SOURCE_FOLDER]: same directory as the source file. For remote profiles
 *   (rclone/SFTP/SMB) this uploads the converted file back to the remote.
 */
enum class ConvertDestination { DOWNLOADS, SOURCE_FOLDER }

enum class BackendType { SFTP, SMB, RCLONE, LOCAL }

/** Clipboard for cross-filesystem copy/move. */
data class FileClipboard(
    val entries: List<SftpEntry>,
    val sourceProfileId: String,
    val sourceBackendType: BackendType,
    val sourceRemoteName: String?,
    val isCut: Boolean,
    /** Cached SFTP channel from source — survives profile switch. */
    @Transient val sourceSftpChannel: com.jcraft.jsch.ChannelSftp? = null,
    /** Cached SMB client from source — survives profile switch. */
    @Transient val sourceSmbClient: sh.haven.core.smb.SmbClient? = null,
)

enum class SortMode {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC
}

/** Transfer progress for download/upload operations. */
data class TransferProgress(
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    /** When true, display as percentage rather than bytes (for ffmpeg transcode). */
    val isPercentage: Boolean = false,
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
    private val ffmpegExecutor: sh.haven.core.ffmpeg.FfmpegExecutor,
    val hlsStreamServer: sh.haven.core.ffmpeg.HlsStreamServer,
    private val sftpStreamServer: SftpStreamServer,
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

    /** Whether ffmpeg binaries are available for media conversion. */
    val ffmpegAvailable: Boolean get() = ffmpegExecutor.isAvailable()

    /** Preview frame state for the convert dialog. */
    sealed class PreviewState {
        data object Idle : PreviewState()
        data object Generating : PreviewState()
        data class Ready(val imagePath: String) : PreviewState()
        data class Failed(val error: String) : PreviewState()
    }

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    /** Duration of the file currently open for preview (seconds), probed once. */
    private val _previewDuration = MutableStateFlow(0.0)
    val previewDuration: StateFlow<Double> = _previewDuration.asStateFlow()

    /**
     * Preview input source — may be a local file path, a downloaded cache
     * file path, or an http:// URL pointing at the rclone media server.
     * ffmpeg handles all three the same way via its protocol layer.
     */
    private var previewInputSource: String? = null

    /** Whether the input file has a real video stream (not just album art). */
    private val _inputHasVideo = MutableStateFlow(true)
    val inputHasVideo: StateFlow<Boolean> = _inputHasVideo.asStateFlow()

    /**
     * True when the preview is being generated from a remote URL (rclone).
     * UI uses this to show a "fetching from cloud" hint during Generating
     * states so the user understands why it's slower than local.
     */
    private val _previewIsRemote = MutableStateFlow(false)
    val previewIsRemote: StateFlow<Boolean> = _previewIsRemote.asStateFlow()

    /** Entry currently shown in the convert dialog — stored in ViewModel to survive rotation. */
    private val _convertDialogEntry = MutableStateFlow<SftpEntry?>(null)
    val convertDialogEntry: StateFlow<SftpEntry?> = _convertDialogEntry.asStateFlow()

    fun openConvertDialog(entry: SftpEntry) { _convertDialogEntry.value = entry }
    fun dismissConvertDialog() { _convertDialogEntry.value = null; clearPreview() }

    /** Whether the fullscreen preview overlay is showing — survives rotation. */
    private val _showFullscreenPreview = MutableStateFlow(false)
    val showFullscreenPreview: StateFlow<Boolean> = _showFullscreenPreview.asStateFlow()

    fun setFullscreenPreview(show: Boolean) { _showFullscreenPreview.value = show }

    /** Audio preview playback state. */
    sealed class AudioPreviewState {
        data object Idle : AudioPreviewState()
        data object Generating : AudioPreviewState()
        data object Playing : AudioPreviewState()
        data class Failed(val error: String) : AudioPreviewState()
    }

    private val _audioPreviewState = MutableStateFlow<AudioPreviewState>(AudioPreviewState.Idle)
    val audioPreviewState: StateFlow<AudioPreviewState> = _audioPreviewState.asStateFlow()

    private var audioPreviewPlayer: android.media.MediaPlayer? = null

    /** Parsed set of media extensions from user preferences. */
    val mediaExtensionsSet: StateFlow<Set<String>> = preferencesRepository.mediaExtensions
        .map { str -> str.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, parseMediaExtensions(UserPreferencesRepository.DEFAULT_MEDIA_EXTENSIONS))

    /** Sync progress for the active rclone sync operation. */
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    /** Controls sync dialog visibility. */
    private val _showSyncDialog = MutableStateFlow(false)
    val showSyncDialog: StateFlow<Boolean> = _showSyncDialog.asStateFlow()

    /** Pre-filled source for sync dialog. */
    private val _syncDialogSource = MutableStateFlow<String?>(null)
    val syncDialogSource: StateFlow<String?> = _syncDialogSource.asStateFlow()

    /** Available rclone remotes for sync destination picker. */
    private val _availableRemotes = MutableStateFlow<List<String>>(emptyList())
    val availableRemotes: StateFlow<List<String>> = _availableRemotes.asStateFlow()

    /** Dry run summary text. */
    private val _dryRunResult = MutableStateFlow<String?>(null)
    val dryRunResult: StateFlow<String?> = _dryRunResult.asStateFlow()
    fun dismissDryRunResult() { _dryRunResult.value = null }

    /** Whether the current folder contains playable media files (rclone only). */
    private val _hasMediaFiles = MutableStateFlow(false)
    val hasMediaFiles: StateFlow<Boolean> = _hasMediaFiles.asStateFlow()

    /** Feature flags for the current rclone remote. */
    private val _remoteCapabilities = MutableStateFlow(sh.haven.core.rclone.RemoteCapabilities())
    val remoteCapabilities: StateFlow<sh.haven.core.rclone.RemoteCapabilities> = _remoteCapabilities.asStateFlow()

    /** Folder size calculation result text. */
    private val _folderSizeResult = MutableStateFlow<String?>(null)
    val folderSizeResult: StateFlow<String?> = _folderSizeResult.asStateFlow()
    private val _folderSizeLoading = MutableStateFlow(false)
    val folderSizeLoading: StateFlow<Boolean> = _folderSizeLoading.asStateFlow()
    fun dismissFolderSize() { _folderSizeResult.value = null }

    /** DLNA server state. */
    private val _dlnaServerRunning = MutableStateFlow(false)
    val dlnaServerRunning: StateFlow<Boolean> = _dlnaServerRunning.asStateFlow()

    /** Port of the running media server, or null if not running. */
    private val _mediaServerPort = MutableStateFlow<Int?>(null)

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

    /** Cross-filesystem clipboard. */
    private val _clipboard = MutableStateFlow<FileClipboard?>(null)
    val clipboard: StateFlow<FileClipboard?> = _clipboard.asStateFlow()

    fun copyToClipboard(entries: List<SftpEntry>, isCut: Boolean) {
        val profileId = _activeProfileId.value ?: return
        Log.d(TAG, "copyToClipboard: ${entries.size} entries, isCut=$isCut, profile=$profileId, " +
            "isRclone=${_isRcloneProfile.value}, isSmb=${_isSmbProfile.value}, " +
            "rcloneRemote=$activeRcloneRemote, sftpChannel=${sftpChannel?.isConnected}, smbClient=${activeSmbClient != null}")
        val backendType = when {
            _isLocalProfile.value -> BackendType.LOCAL
            _isRcloneProfile.value -> BackendType.RCLONE
            _isSmbProfile.value -> BackendType.SMB
            else -> BackendType.SFTP
        }
        // Open a dedicated SFTP channel for copy (separate from browse channel)
        val copyChannel = if (backendType == BackendType.SFTP) {
            sessionManager.openSftpForProfile(profileId)
        } else null
        Log.d(TAG, "copyToClipboard: dedicated copy channel=${copyChannel?.isConnected}")
        _clipboard.value = FileClipboard(
            entries = entries,
            sourceProfileId = profileId,
            sourceBackendType = backendType,
            sourceRemoteName = activeRcloneRemote,
            isCut = isCut,
            sourceSftpChannel = copyChannel,
            sourceSmbClient = if (backendType == BackendType.SMB) activeSmbClient else null,
        )
        _message.value = "${entries.size} item${if (entries.size > 1) "s" else ""} ${if (isCut) "cut" else "copied"}"
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    private var sftpChannel: ChannelSftp? = null
    private var activeSmbClient: SmbClient? = null

    /**
     * Build an [SftpStreamServer.Opener] for [entry] on the currently
     * active profile. The opener captures [profileId] (not a channel) so
     * it re-resolves against whichever browse channel is live at read
     * time — important across reconnects.
     */
    private fun sftpOpener(profileId: String, path: String): SftpStreamServer.Opener =
        SftpStreamServer.Opener { offset ->
            val channel = getOrOpenChannel(profileId)
                ?: throw java.io.IOException("SFTP not connected")
            if (offset > 0) {
                channel.get(path, null as SftpProgressMonitor?, offset)
            } else {
                channel.get(path)
            }
        }

    private fun guessContentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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

    /** Tracks which active profile is SMB (vs SFTP). */
    private val _isSmbProfile = MutableStateFlow(false)

    /** Tracks which active profile is rclone (vs SFTP/SMB). */
    private val _isRcloneProfile = MutableStateFlow(false)
    val isRcloneProfile: StateFlow<Boolean> = _isRcloneProfile.asStateFlow()

    /** Tracks whether the active profile is the local filesystem. */
    private val _isLocalProfile = MutableStateFlow(false)

    /** Synthetic profile for the always-present "Local" tab. */
    private val localProfile = ConnectionProfile(
        id = "local",
        label = "Local",
        host = "",
        username = "",
        connectionType = "LOCAL",
    )

    /** rclone remote name for the active profile. */
    private var activeRcloneRemote: String? = null

    /** Pending SMB profile to auto-select when navigating to Files tab. */
    private val _pendingSmbProfileId = MutableStateFlow<String?>(null)

    /** Pending rclone profile to auto-select when navigating to Files tab. */
    private val _pendingRcloneProfileId = MutableStateFlow<String?>(null)

    /** Per-profile state cache so tab switching preserves path and entries. */
    private data class ProfileBrowseState(
        val path: String,
        val entries: List<SftpEntry>,
        val allEntries: List<SftpEntry>,
    )
    private val profileStateCache = mutableMapOf<String, ProfileBrowseState>()

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

            val profiles = withContext(Dispatchers.IO) { repository.getAll() }
            val remoteProfiles = profiles.filter { it.id in connectedProfileIds }
            // Always include "Local" as the first tab
            _connectedProfiles.value = listOf(localProfile) + remoteProfiles

            if (connectedProfileIds.isEmpty() && _activeProfileId.value == null) {
                // No remote connections — auto-select local
                selectProfile("local")
                return@launch
            }

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
        Log.d(TAG, "selectProfile: $profileId (prev=${_activeProfileId.value}, clipboard=${_clipboard.value != null})")

        // Save current profile's browse state before switching
        _activeProfileId.value?.let { prevId ->
            profileStateCache[prevId] = ProfileBrowseState(
                path = _currentPath.value,
                entries = _entries.value,
                allEntries = _allEntries.value,
            )
        }

        val isLocal = profileId == "local"
        val isSmb = !isLocal && smbSessionManager.isProfileConnected(profileId)
        val isRclone = !isLocal && rcloneSessionManager.isProfileConnected(profileId)
        _isLocalProfile.value = isLocal
        _isSmbProfile.value = isSmb
        _isRcloneProfile.value = isRclone
        _activeProfileId.value = profileId
        sftpChannel = null
        activeSmbClient = null
        activeRcloneRemote = null
        _remoteCapabilities.value = sh.haven.core.rclone.RemoteCapabilities()

        // Restore cached state if available
        val cached = profileStateCache[profileId]
        if (cached != null) {
            _currentPath.value = cached.path
            _allEntries.value = cached.allEntries
            _entries.value = cached.entries
            // Still need to re-establish the backend connection
            when {
                isLocal -> { /* no connection needed */ }
                isRclone -> {
                    val remoteName = rcloneSessionManager.getRemoteNameForProfile(profileId)
                    activeRcloneRemote = remoteName
                    if (remoteName != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try { _remoteCapabilities.value = rcloneClient.getCapabilities(remoteName) }
                            catch (_: Exception) { _remoteCapabilities.value = sh.haven.core.rclone.RemoteCapabilities() }
                        }
                    }
                }
                isSmb -> {
                    activeSmbClient = smbSessionManager.getClientForProfile(profileId)
                }
                else -> {
                    viewModelScope.launch(Dispatchers.IO) {
                        sftpChannel = sessionManager.openSftpForProfile(profileId)
                    }
                }
            }
        } else {
            _currentPath.value = "/"
            _allEntries.value = emptyList()
            _entries.value = emptyList()
            when {
                isLocal -> listLocalDirectory("/")
                isRclone -> openRcloneAndList(profileId)
                isSmb -> openSmbAndList(profileId)
                else -> openSftpAndList(profileId, "/")
            }
        }
    }

    fun navigateTo(path: String) {
        val profileId = _activeProfileId.value ?: return
        _currentPath.value = path
        when {
            _isLocalProfile.value -> listLocalDirectory(path)
            _isRcloneProfile.value -> listRcloneDirectory(path)
            _isSmbProfile.value -> listSmbDirectory(path)
            else -> listDirectory(profileId, path)
        }
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current == "/") return
        val parent = current.trimEnd('/').substringBeforeLast('/', "/")
        val target = if (parent.isEmpty()) "/" else parent
        // For local files, skip unreadable parent directories and jump to root
        if (_isLocalProfile.value && target != "/" && !java.io.File(target).canRead()) {
            navigateTo("/")
        } else {
            navigateTo(target)
        }
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
        val filtered = if (_showHidden.value) all else all.filter { !it.name.startsWith(".") }
        _entries.value = filtered
        _hasMediaFiles.value = _isRcloneProfile.value && filtered.any { it.isMediaFile(mediaExtensionsSet.value) }
    }

    fun refresh() {
        val profileId = _activeProfileId.value ?: return
        when {
            _isLocalProfile.value -> listLocalDirectory(_currentPath.value)
            _isRcloneProfile.value -> listRcloneDirectory(_currentPath.value)
            _isSmbProfile.value -> listSmbDirectory(_currentPath.value)
            else -> listDirectory(profileId, _currentPath.value)
        }
    }

    /**
     * List local device filesystem entries.
     * Uses "/" as the root showing common Android storage locations,
     * then standard java.io.File listing within directories.
     */
    private fun listLocalRoots(): List<SftpEntry> {
        val roots = mutableListOf<SftpEntry>()
        val storage = android.os.Environment.getExternalStorageDirectory()
        if (storage.canRead()) {
            roots.add(SftpEntry("Internal Storage", storage.absolutePath, true, 0, storage.lastModified() / 1000, ""))
        }
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (downloads.canRead()) {
            roots.add(SftpEntry("Downloads", downloads.absolutePath, true, 0, downloads.lastModified() / 1000, ""))
        }
        // PRoot Alpine rootfs — only surfaced when the rootfs has been
        // installed. Lands the user in /root (the shell's home dir)
        // rather than the rootfs top, since that's where ~/.profile,
        // ~/README.md, ~/.ssh/, and ~/.config/haven/ live. The user
        // can navigate up to see /etc, /usr, /var etc. if they want.
        val prootHome = java.io.File(appContext.filesDir, "proot/rootfs/alpine/root")
        if (prootHome.exists() && prootHome.canRead()) {
            roots.add(
                SftpEntry(
                    "PRoot (~/)", prootHome.absolutePath, true, 0,
                    prootHome.lastModified() / 1000, "",
                ),
            )
        }
        roots.add(SftpEntry("App Cache", appContext.cacheDir.absolutePath, true, 0, appContext.cacheDir.lastModified() / 1000, ""))
        return roots
    }

    /** Whether the active profile is the local filesystem. */
    fun isLocalProfile(): Boolean = _isLocalProfile.value
    fun isSmbProfile(): Boolean = _isSmbProfile.value

    /** True when the local file browser needs MANAGE_EXTERNAL_STORAGE permission. */
    val needsStoragePermission: Boolean
        get() = _isLocalProfile.value &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()

    private fun listLocalDirectory(path: String) {
        viewModelScope.launch {
            try {
                _loading.value = true
                val entries = withContext(Dispatchers.IO) {
                    val dir = if (path == "/") {
                        return@withContext listLocalRoots()
                    } else {
                        val file = java.io.File(path)
                        val files = file.listFiles()
                        if (files == null) {
                            // Can't read this directory — jump back to root
                            _currentPath.value = "/"
                            return@withContext listLocalRoots()
                        }
                        files.map { f ->
                            SftpEntry(
                                name = f.name,
                                path = f.absolutePath,
                                isDirectory = f.isDirectory,
                                size = if (f.isDirectory) 0 else f.length(),
                                modifiedTime = f.lastModified() / 1000,
                                permissions = buildString {
                                    if (f.canRead()) append('r') else append('-')
                                    if (f.canWrite()) append('w') else append('-')
                                    if (f.canExecute()) append('x') else append('-')
                                },
                            )
                        }
                    }
                    dir
                }
                val sorted = sortEntries(entries, _sortMode.value)
                _allEntries.value = sorted
                applyFilter()
            } catch (e: Exception) {
                Log.e(TAG, "Local listing failed", e)
                _error.value = "Failed to list directory: ${e.message}"
            } finally {
                _loading.value = false
            }
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

    /**
     * Transcode a media file with FFmpeg and save the result to Downloads.
     *
     * For **rclone** profiles the default behaviour is to stream the file
     * over HTTP via the rclone media server (fast start, no temp file).
     * Pass [downloadFirst] = true to force the legacy download-then-process
     * path — useful for offline conversion or when you want the bytes cached
     * locally for subsequent conversions.
     *
     * For **SFTP/SMB** the file is always downloaded to cache first (no HTTP
     * serve equivalent). For **local** files the on-disk path is used directly.
     */
    fun convertFile(
        entry: SftpEntry,
        container: String,
        videoEncoder: String? = null,
        audioEncoder: String = "aac",
        videoFilters: List<sh.haven.core.ffmpeg.VideoFilter> = emptyList(),
        audioFilters: List<sh.haven.core.ffmpeg.AudioFilter> = emptyList(),
        downloadFirst: Boolean = false,
        destination: ConvertDestination = ConvertDestination.DOWNLOADS,
    ) {
        val profileId = _activeProfileId.value ?: return
        viewModelScope.launch {
            try {
                _loading.value = true

                // Phase 1: Resolve the ffmpeg input — either a local path or an
                // http:// URL. Only SFTP/SMB and user-forced rclone downloads
                // produce a temp cache file here.
                val ffmpegInput: String
                if (_isLocalProfile.value) {
                    ffmpegInput = entry.path
                } else if (_isRcloneProfile.value && !downloadFirst) {
                    // Stream via the rclone HTTP media server — no bulk download.
                    // ffmpeg reads via Range requests, rclone's VFS disk cache
                    // handles the chunking.
                    val port = ensureMediaServer()
                    val encodedPath = entry.path
                        .trimStart('/')
                        .split('/')
                        .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                    ffmpegInput = "http://127.0.0.1:$port/$encodedPath"
                    Log.d(TAG, "convertFile: streaming rclone via $ffmpegInput")
                } else {
                    // SFTP, SMB, or rclone with downloadFirst=true — pull the
                    // whole file into cache, then hand the path to ffmpeg.
                    val dlLabel = "\u2B07 Downloading ${entry.name}"
                    _transferProgress.value = TransferProgress(dlLabel, entry.size, 0)
                    val cacheInput = java.io.File(appContext.cacheDir, "ffmpeg_in_${entry.name}")
                    withContext(Dispatchers.IO) {
                        cacheInput.outputStream().use { out ->
                            if (_isRcloneProfile.value) {
                                val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                                _transferProgress.value = TransferProgress(dlLabel, 0, 0)
                                rcloneClient.copyFile(remote, entry.path, cacheInput.parent!!, cacheInput.name)
                                _transferProgress.value = TransferProgress(dlLabel, entry.size, entry.size)
                            } else if (_isSmbProfile.value) {
                                val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                                client.download(entry.path, out) { transferred, total ->
                                    _transferProgress.value = TransferProgress(dlLabel, total, transferred)
                                }
                            } else {
                                val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                                channel.get(entry.path, out, object : SftpProgressMonitor {
                                    override fun init(op: Int, src: String, dest: String, max: Long) {
                                        _transferProgress.value = TransferProgress(dlLabel, max, 0)
                                    }
                                    override fun count(bytes: Long): Boolean {
                                        val prev = _transferProgress.value
                                        if (prev != null) _transferProgress.value = prev.copy(transferredBytes = prev.transferredBytes + bytes)
                                        return true
                                    }
                                    override fun end() {}
                                })
                            }
                        }
                    }
                    ffmpegInput = cacheInput.absolutePath
                }

                // Phase 2: Transcode
                val baseName = entry.name.substringBeforeLast('.')
                // Map container key to file extension
                val outExt = when (container) {
                    "mpegts" -> "ts"; "m4a" -> "m4a"; else -> container
                }
                val outName = "${baseName}_converted.$outExt"
                val cacheOutput = java.io.File(appContext.cacheDir, outName)

                val cmd = sh.haven.core.ffmpeg.TranscodeCommand(ffmpegInput, cacheOutput.absolutePath)
                if (videoEncoder != null) {
                    cmd.videoCodec(videoEncoder)
                    // Sensible defaults for common encoders
                    when (videoEncoder) {
                        "libx264" -> cmd.crf(23).preset("medium")
                        "libx265" -> cmd.crf(28).preset("medium")
                        "libvpx-vp9" -> cmd.crf(31).extra("-b:v", "0")
                    }
                } else {
                    cmd.extra("-vn")
                }
                cmd.audioCodec(audioEncoder)
                    .videoFilters(videoFilters)
                    .audioFilters(audioFilters)

                // Probe input duration for accurate progress (uses HTTP Range
                // requests when input is a URL — typically only reads ~200KB)
                val durationSec = withContext(Dispatchers.IO) {
                    val probeResult = ffmpegExecutor.probe(listOf(
                        "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1",
                        ffmpegInput,
                    ))
                    probeResult.stdout.trim().toDoubleOrNull() ?: 0.0
                }

                val convertLabel = "\u2699 Converting to $outExt"
                _transferProgress.value = if (durationSec > 0) {
                    TransferProgress(convertLabel, 100, 0, isPercentage = true)
                } else {
                    TransferProgress(convertLabel, 0, 0) // indeterminate
                }
                val result = withContext(Dispatchers.IO) {
                    ffmpegExecutor.execute(cmd.build()) { stderrLine ->
                        val progress = sh.haven.core.ffmpeg.FfmpegProgress.parse(stderrLine)
                        if (progress != null && durationSec > 0) {
                            val pct = ((progress.timeSeconds / durationSec) * 100).toLong().coerceIn(0, 99)
                            _transferProgress.value = TransferProgress(convertLabel, 100, pct, isPercentage = true)
                        }
                    }
                }

                if (!result.success) {
                    _error.value = "Conversion failed (exit ${result.exitCode})"
                    return@launch
                }

                // Phase 3: Save the output to the user's chosen destination.
                val mimeType = when (outExt) {
                    "mp4", "m4a" -> if (videoEncoder != null) "video/mp4" else "audio/mp4"
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    "mov" -> "video/quicktime"
                    "avi" -> "video/x-msvideo"
                    "ts" -> "video/mp2t"
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    "ogg" -> "audio/ogg"
                    "opus" -> "audio/opus"
                    "flac" -> "audio/flac"
                    else -> "application/octet-stream"
                }
                val savedLocation: String = when (destination) {
                    ConvertDestination.DOWNLOADS -> {
                        withContext(Dispatchers.IO) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                val values = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, outName)
                                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                                }
                                val uri = appContext.contentResolver.insert(
                                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                                ) ?: throw IllegalStateException("Failed to create Downloads entry")
                                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                                    cacheOutput.inputStream().use { it.copyTo(out) }
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val dlDir = android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                )
                                val dest = java.io.File(dlDir, outName)
                                cacheOutput.copyTo(dest, overwrite = true)
                            }
                        }
                        "Downloads"
                    }
                    ConvertDestination.SOURCE_FOLDER -> {
                        // The directory of the source file, expressed in that backend's native path
                        val sourceDir = entry.path.substringBeforeLast('/', "").ifEmpty { "/" }
                        val destPath = if (sourceDir == "/") "/$outName" else "$sourceDir/$outName"
                        when {
                            _isLocalProfile.value -> {
                                withContext(Dispatchers.IO) {
                                    cacheOutput.copyTo(java.io.File(destPath), overwrite = true)
                                }
                                sourceDir
                            }
                            _isRcloneProfile.value -> {
                                val remote = activeRcloneRemote
                                    ?: throw IllegalStateException("Rclone not connected")
                                val uploadLabel = "\u2B06 Uploading $outName"
                                _transferProgress.value = TransferProgress(uploadLabel, 0, 0)
                                withContext(Dispatchers.IO) {
                                    // rclone copyFile: local-abs-dir + filename -> remote + path
                                    rcloneClient.copyFile(
                                        cacheOutput.parent!!, cacheOutput.name,
                                        remote, destPath.trimStart('/'),
                                    )
                                }
                                "$remote:$sourceDir"
                            }
                            _isSmbProfile.value -> {
                                val client = activeSmbClient
                                    ?: throw IllegalStateException("SMB not connected")
                                val uploadLabel = "\u2B06 Uploading $outName"
                                _transferProgress.value = TransferProgress(uploadLabel, cacheOutput.length(), 0)
                                withContext(Dispatchers.IO) {
                                    cacheOutput.inputStream().use { input ->
                                        client.upload(input, destPath, cacheOutput.length()) { transferred, total ->
                                            _transferProgress.value = TransferProgress(uploadLabel, total, transferred)
                                        }
                                    }
                                }
                                sourceDir
                            }
                            else -> {
                                // SFTP
                                val channel = getOrOpenChannel(profileId)
                                    ?: throw IllegalStateException("Not connected")
                                val uploadLabel = "\u2B06 Uploading $outName"
                                _transferProgress.value = TransferProgress(uploadLabel, cacheOutput.length(), 0)
                                withContext(Dispatchers.IO) {
                                    cacheOutput.inputStream().use { input ->
                                        var transferred = 0L
                                        val total = cacheOutput.length()
                                        val monitor = object : SftpProgressMonitor {
                                            override fun init(op: Int, src: String, dest: String, max: Long) {}
                                            override fun count(bytes: Long): Boolean {
                                                transferred += bytes
                                                _transferProgress.value = TransferProgress(uploadLabel, total, transferred)
                                                return true
                                            }
                                            override fun end() {}
                                        }
                                        channel.put(input, destPath, monitor)
                                    }
                                }
                                sourceDir
                            }
                        }
                    }
                }

                _message.value = "Saved $outName to $savedLocation"
                // If we saved into the folder currently showing, refresh so the user sees it
                if (destination == ConvertDestination.SOURCE_FOLDER) {
                    val sourceDir = entry.path.substringBeforeLast('/', "").ifEmpty { "/" }
                    if (_currentPath.value == sourceDir) refresh()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Convert failed", e)
                _error.value = "Convert failed: ${e.message}"
            } finally {
                _loading.value = false
                _transferProgress.value = null
                // Clean up cache files (don't delete the original if it's a local file)
                if (!_isLocalProfile.value) {
                    java.io.File(appContext.cacheDir, "ffmpeg_in_${entry.name}").delete()
                }
            }
        }
    }

    /**
     * Start an HLS stream for the given file and open it in a browser.
     *
     * - **Local** files: ffmpeg reads the path directly.
     * - **Rclone** files: ffmpeg reads via the rclone HTTP media server
     *   (Range requests, VFS disk cache) — no bulk download.
     * - **SFTP** files: ffmpeg reads via the loopback [sftpStreamServer]
     *   which fronts `ChannelSftp.get(path, skip)` with HTTP Range support.
     * - **SMB**: not supported yet (no HTTP bridge).
     */
    fun streamFile(entry: SftpEntry) {
        Log.w(TAG, "streamFile: ${entry.path} isLocal=${_isLocalProfile.value} isRclone=${_isRcloneProfile.value} isSmb=${_isSmbProfile.value} ffmpegAvail=${ffmpegExecutor.isAvailable()}")
        if (_isSmbProfile.value) {
            _error.value = "Streaming is not supported for SMB yet"
            return
        }
        viewModelScope.launch {
            try {
                // Resolve the ffmpeg input path or URL
                val streamInput: String = when {
                    _isLocalProfile.value -> entry.path
                    _isRcloneProfile.value -> {
                        val port = ensureMediaServer()
                        val encodedPath = entry.path
                            .trimStart('/')
                            .split('/')
                            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                        "http://127.0.0.1:$port/$encodedPath"
                    }
                    else -> {
                        // SFTP via loopback HTTP bridge
                        val profileId = _activeProfileId.value
                            ?: throw IllegalStateException("No active profile")
                        val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                        val urlPath = sftpStreamServer.publish(
                            path = entry.path,
                            size = entry.size,
                            contentType = guessContentType(entry.name),
                            opener = sftpOpener(profileId, entry.path),
                        )
                        "http://127.0.0.1:$port$urlPath"
                    }
                }
                Log.w(TAG, "Starting HLS stream for $streamInput")
                val port = hlsStreamServer.startFile(streamInput)
                // Get the device's LAN IP so the URL is shareable with other
                // devices on the same network. Falls back to 127.0.0.1 if we
                // can't detect a usable interface (e.g. no WiFi/cellular).
                val ip = withContext(Dispatchers.IO) {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.filter { it.isUp && !it.isLoopback }
                        ?.flatMap { it.inetAddresses.toList() }
                        ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        ?.hostAddress ?: "127.0.0.1"
                }
                val url = "http://$ip:$port/"
                // Copy to the system clipboard so the user can paste it into
                // another device easily
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Haven stream URL", url)
                )
                _message.value = "Streaming on $url (copied to clipboard)"
                // Open the shareable URL in the browser — the address bar will
                // show it, so the user can copy/share from there too.
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Stream failed", e)
                _error.value = "Stream failed: ${e.message}"
            }
        }
    }

    fun stopStream() {
        hlsStreamServer.stop()
        _message.value = "Stream stopped"
    }

    /**
     * Probe the duration of a media file and set up the preview input source.
     *
     * For **local files**: uses the file path directly.
     * For **rclone**: starts (or reuses) the rclone media HTTP server and
     *   passes the `http://127.0.0.1:port/...` URL to ffmpeg. Rclone's VFS
     *   disk cache handles chunked Range reads so we avoid downloading the
     *   whole file just to show a preview frame.
     * For **SFTP/SMB**: still downloads to cache first (no HTTP stream option).
     *
     * ffmpeg treats file paths and http:// URLs interchangeably via its
     * protocol layer, so downstream preview generation is identical.
     */
    fun preparePreview(entry: SftpEntry) {
        val profileId = _activeProfileId.value ?: return
        viewModelScope.launch {
            try {
                _previewState.value = PreviewState.Generating

                // Determine the input source for ffmpeg: local path, HTTP URL, or cached download
                val inputSource: String
                val isRemote: Boolean
                when {
                    _isLocalProfile.value -> {
                        inputSource = entry.path
                        isRemote = false
                    }
                    _isRcloneProfile.value -> {
                        // Start the rclone media HTTP server on demand and
                        // hand ffmpeg the URL directly — no bulk download.
                        val port = ensureMediaServer()
                        // Encode each path segment but leave slashes intact
                        val encodedPath = entry.path
                            .trimStart('/')
                            .split('/')
                            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                        inputSource = "http://127.0.0.1:$port/$encodedPath"
                        isRemote = true
                        Log.d(TAG, "preparePreview: using rclone HTTP URL $inputSource")
                    }
                    _isSmbProfile.value -> {
                        // SMB — still downloads to cache; no HTTP bridge yet.
                        val cached = java.io.File(appContext.cacheDir, "ffmpeg_in_${entry.name}")
                        if (!cached.exists() || cached.length() == 0L) {
                            withContext(Dispatchers.IO) {
                                cached.outputStream().use { out ->
                                    val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                                    client.download(entry.path, out) { _, _ -> }
                                }
                            }
                        }
                        inputSource = cached.absolutePath
                        isRemote = false
                    }
                    else -> {
                        // SFTP — stream via loopback HTTP so ffmpeg uses Range
                        // requests (probe typically reads just the moov atom,
                        // a few hundred KB) instead of downloading the whole
                        // file to cache.
                        val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                        val urlPath = sftpStreamServer.publish(
                            path = entry.path,
                            size = entry.size,
                            contentType = guessContentType(entry.name),
                            opener = sftpOpener(profileId, entry.path),
                        )
                        inputSource = "http://127.0.0.1:$port$urlPath"
                        isRemote = true
                        Log.d(TAG, "preparePreview: using SFTP HTTP URL $inputSource")
                    }
                }
                previewInputSource = inputSource
                _previewIsRemote.value = isRemote

                // Probe duration and detect video streams (ffprobe uses HTTP Range requests
                // for remote URLs — typically only reads ~200KB of moov atom)
                val (durationSec, hasVideo) = withContext(Dispatchers.IO) {
                    val durResult = ffmpegExecutor.probe(listOf(
                        "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1",
                        inputSource,
                    ))
                    val dur = durResult.stdout.trim().toDoubleOrNull() ?: 0.0

                    // Check for real video stream (exclude attached pictures like album art)
                    val videoResult = ffmpegExecutor.probe(listOf(
                        "-v", "error", "-select_streams", "v",
                        "-show_entries", "stream=codec_type:stream_disposition=attached_pic",
                        "-of", "flat", inputSource,
                    ))
                    val probeOut = videoResult.stdout
                    val hasVideoStream = probeOut.contains("codec_type=\"video\"")
                    val isAttachedPic = probeOut.contains("attached_pic=1")
                    val realVideo = hasVideoStream && !isAttachedPic
                    Log.d(TAG, "preparePreview: duration=$dur hasVideo=$realVideo remote=$isRemote")
                    dur to realVideo
                }
                _previewDuration.value = durationSec
                _inputHasVideo.value = hasVideo

                // Generate initial frame at 10% into the file (video only)
                if (hasVideo) {
                    val seekPos = (durationSec * 0.1).coerceAtLeast(0.0)
                    generatePreviewFrame(inputSource, seekPos, emptyList())
                } else {
                    _previewState.value = PreviewState.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "preparePreview failed", e)
                _previewState.value = PreviewState.Failed(e.message ?: "Preview failed")
            }
        }
    }

    /**
     * Generate a single preview frame with the current filters at the given seek position.
     * Fast for local files; for rclone URLs this triggers a Range-request fetch
     * that completes in a few seconds (the moov atom and one keyframe).
     */
    fun previewFrame(
        seekSeconds: Double,
        videoFilters: List<sh.haven.core.ffmpeg.VideoFilter>,
    ) {
        val source = previewInputSource ?: return
        viewModelScope.launch {
            generatePreviewFrame(source, seekSeconds, videoFilters)
        }
    }

    private suspend fun generatePreviewFrame(
        inputSource: String,
        seekSeconds: Double,
        videoFilters: List<sh.haven.core.ffmpeg.VideoFilter>,
    ) {
        try {
            _previewState.value = PreviewState.Generating
            // Output a 1-frame MP4 (bundled ffmpeg has libx264 but not mjpeg encoder)
            val outputFile = java.io.File(appContext.cacheDir, "ffmpeg_preview.mp4")

            val cmd = sh.haven.core.ffmpeg.TranscodeCommand.frameAt(
                inputSource, outputFile.absolutePath, seekSeconds,
            ).videoFilters(videoFilters)

            val result = withContext(Dispatchers.IO) {
                ffmpegExecutor.execute(cmd.build())
            }

            if (result.success && outputFile.exists() && outputFile.length() > 0) {
                // Extract bitmap from the 1-frame MP4 via MediaMetadataRetriever
                val jpgFile = java.io.File(appContext.cacheDir, "ffmpeg_preview.jpg")
                withContext(Dispatchers.IO) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(outputFile.absolutePath)
                        val bitmap = retriever.getFrameAtTime(0)
                        if (bitmap != null) {
                            jpgFile.outputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            bitmap.recycle()
                        }
                    } finally {
                        retriever.release()
                    }
                }
                if (jpgFile.exists() && jpgFile.length() > 0) {
                    _previewState.value = PreviewState.Ready(jpgFile.absolutePath)
                } else {
                    _previewState.value = PreviewState.Failed("Failed to decode preview frame")
                }
            } else {
                // Log the TAIL of stderr where the real error lives — the head is
                // just the ffmpeg banner and configuration string.
                val tail = result.stderr.takeLast(2000)
                Log.e(TAG, "ffmpeg frame extraction failed: exit=${result.exitCode}\n--- stderr tail ---\n$tail")
                _previewState.value = PreviewState.Failed(
                    "Frame extraction failed (exit ${result.exitCode})"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "generatePreviewFrame failed", e)
            _previewState.value = PreviewState.Failed(e.message ?: "Preview failed")
        }
    }

    /** Reset preview state when the convert dialog is dismissed. */
    fun clearPreview() {
        _previewState.value = PreviewState.Idle
        _previewDuration.value = 0.0
        _inputHasVideo.value = true
        _previewIsRemote.value = false
        _showFullscreenPreview.value = false
        previewInputSource = null
        stopAudioPreview()
        // Don't delete cached download file — convertFile reuses it
    }

    /**
     * Generate a short audio clip with filters applied and play it.
     * Extracts ~5 seconds starting from the seek position.
     */
    fun previewAudio(
        seekSeconds: Double,
        audioFilters: List<sh.haven.core.ffmpeg.AudioFilter>,
        videoFilters: List<sh.haven.core.ffmpeg.VideoFilter> = emptyList(),
    ) {
        val source = previewInputSource ?: return
        stopAudioPreview()
        viewModelScope.launch {
            try {
                _audioPreviewState.value = AudioPreviewState.Generating
                val outputFile = java.io.File(appContext.cacheDir, "ffmpeg_audio_preview.mp4")

                // Build a short clip with audio filters
                val cmd = sh.haven.core.ffmpeg.TranscodeCommand(
                    source, outputFile.absolutePath,
                ).seekTo(seekSeconds)
                    .duration(5.0)
                    .audioCodec("aac")
                    .audioFilters(audioFilters)
                if (_inputHasVideo.value) {
                    cmd.videoCodec("libx264").preset("ultrafast").crf(28)
                        .videoFilters(videoFilters)
                } else {
                    cmd.extra("-vn")
                }

                val result = withContext(Dispatchers.IO) {
                    ffmpegExecutor.execute(cmd.build())
                }

                if (!result.success || !outputFile.exists() || outputFile.length() == 0L) {
                    Log.e(TAG, "Audio preview transcode failed: exit=${result.exitCode} stderr=${result.stderr.take(500)}")
                    _audioPreviewState.value = AudioPreviewState.Failed("Preview failed (exit ${result.exitCode})")
                    return@launch
                }

                // Play the clip
                withContext(Dispatchers.IO) {
                    val player = android.media.MediaPlayer()
                    player.setDataSource(outputFile.absolutePath)
                    player.setOnCompletionListener {
                        _audioPreviewState.value = AudioPreviewState.Idle
                        it.release()
                        audioPreviewPlayer = null
                    }
                    player.setOnErrorListener { mp, _, _ ->
                        _audioPreviewState.value = AudioPreviewState.Failed("Playback error")
                        mp.release()
                        audioPreviewPlayer = null
                        true
                    }
                    player.prepare()
                    player.start()
                    audioPreviewPlayer = player
                }
                _audioPreviewState.value = AudioPreviewState.Playing
            } catch (e: Exception) {
                Log.e(TAG, "previewAudio failed", e)
                _audioPreviewState.value = AudioPreviewState.Failed(e.message ?: "Preview failed")
            }
        }
    }

    fun stopAudioPreview() {
        try {
            audioPreviewPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        audioPreviewPlayer = null
        _audioPreviewState.value = AudioPreviewState.Idle
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

    fun renameEntry(entry: SftpEntry, newName: String) {
        val profileId = _activeProfileId.value ?: return
        viewModelScope.launch {
            try {
                _loading.value = true
                val parentPath = _currentPath.value
                val newPath = if (parentPath.isEmpty() || parentPath == "/") newName
                    else "${parentPath.trimEnd('/')}/$newName"
                withContext(Dispatchers.IO) {
                    if (_isRcloneProfile.value) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        if (entry.isDirectory) {
                            val config = SyncConfig(
                                srcFs = "$remote:${entry.path}",
                                dstFs = "$remote:$newPath",
                                mode = sh.haven.core.rclone.SyncMode.MOVE,
                            )
                            val jobId = rcloneClient.startSync(config)
                            while (true) {
                                delay(200)
                                val status = rcloneClient.getJobStatus(jobId)
                                if (status.finished) {
                                    if (!status.success) throw Exception(status.error ?: "Rename failed")
                                    break
                                }
                            }
                        } else {
                            rcloneClient.moveFile(remote, entry.path, remote, newPath)
                        }
                    } else if (_isSmbProfile.value) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        client.rename(entry.path, newPath)
                    } else {
                        val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                        channel.rename(entry.path, newPath)
                    }
                }
                _message.value = "Renamed to $newName"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Rename failed", e)
                _error.value = "Rename failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun sharePublicLink(entry: SftpEntry) {
        viewModelScope.launch {
            try {
                val remote = activeRcloneRemote ?: return@launch
                val url = withContext(Dispatchers.IO) { rcloneClient.publicLink(remote, entry.path) }
                val clip = android.content.ClipData.newPlainText("link", url)
                (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(clip)
                _message.value = "Link copied"
            } catch (e: Exception) {
                Log.e(TAG, "Public link failed", e)
                _error.value = "Share link not supported for this remote"
            }
        }
    }

    private var folderSizeJob: kotlinx.coroutines.Job? = null

    fun calculateFolderSize(entry: SftpEntry) {
        folderSizeJob?.cancel()
        folderSizeJob = viewModelScope.launch {
            try {
                _folderSizeLoading.value = true
                val remote = activeRcloneRemote ?: return@launch
                val size = withContext(Dispatchers.IO) { rcloneClient.directorySize(remote, entry.path) }
                val formattedSize = android.text.format.Formatter.formatFileSize(appContext, size.bytes)
                _folderSizeResult.value = "${entry.name}: $formattedSize (${size.count} files)"
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Folder size failed", e)
                _error.value = "Size calculation failed: ${e.message}"
            } finally {
                _folderSizeLoading.value = false
            }
        }
    }

    fun cancelFolderSize() {
        folderSizeJob?.cancel()
        folderSizeJob = null
        _folderSizeLoading.value = false
    }

    fun toggleDlnaServer() {
        viewModelScope.launch {
            try {
                if (_dlnaServerRunning.value) {
                    withContext(Dispatchers.IO) { rcloneClient.stopDlnaServer() }
                    _dlnaServerRunning.value = false
                    _message.value = "DLNA server stopped"
                } else {
                    val remote = activeRcloneRemote ?: return@launch
                    withContext(Dispatchers.IO) { rcloneClient.startDlnaServer(remote) }
                    _dlnaServerRunning.value = true
                    _message.value = "DLNA server started"
                }
            } catch (e: Exception) {
                Log.e(TAG, "DLNA toggle failed", e)
                _error.value = "DLNA server failed: ${e.message}"
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

    /** Shared counter for recursive copy progress. */
    private val pasteFileCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val pasteInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun updatePasteProgress(fileName: String) {
        val count = pasteFileCount.incrementAndGet()
        _transferProgress.value = TransferProgress(
            "$count files: $fileName",
            0,
            0,
        )
    }

    fun pasteFromClipboard() {
        val cb = _clipboard.value ?: return
        val destProfileId = _activeProfileId.value ?: return
        val destPath = _currentPath.value
        Log.d(TAG, "pasteFromClipboard: ${cb.entries.size} entries from ${cb.sourceBackendType}(${cb.sourceProfileId}) " +
            "to dest=$destProfileId, destPath=$destPath, isRclone=${_isRcloneProfile.value}, isSmb=${_isSmbProfile.value}, " +
            "srcSftp=${cb.sourceSftpChannel?.isConnected}, srcSmb=${cb.sourceSmbClient != null}, " +
            "dstRclone=$activeRcloneRemote, dstSftp=${sftpChannel?.isConnected}, dstSmb=${activeSmbClient != null}")

        val destType = when {
            _isLocalProfile.value -> BackendType.LOCAL
            _isRcloneProfile.value -> BackendType.RCLONE
            _isSmbProfile.value -> BackendType.SMB
            else -> BackendType.SFTP
        }
        val destRemote = activeRcloneRemote

        viewModelScope.launch {
            try {
                _loading.value = true
                pasteInProgress.set(true)
                pasteFileCount.set(0)
                _transferProgress.value = TransferProgress("Preparing...", 0, 0)

                for (entry in cb.entries) {
                    val destEntryPath = destPath.trimEnd('/') + "/" + entry.name

                    withContext(Dispatchers.IO) {
                        if (cb.sourceBackendType == BackendType.RCLONE && destType == BackendType.RCLONE) {
                            val srcRemote = cb.sourceRemoteName ?: throw IllegalStateException("No source remote")
                            val dstRemote = destRemote ?: throw IllegalStateException("No dest remote")
                            if (entry.isDirectory) {
                                rcloneClient.mkdir(dstRemote, destEntryPath)
                                copyRcloneDir(srcRemote, entry.path, dstRemote, destEntryPath)
                            } else {
                                rcloneClient.copyFile(srcRemote, entry.path, dstRemote, destEntryPath)
                                updatePasteProgress(entry.name)
                            }
                        } else {
                            if (entry.isDirectory) {
                                crossCopyDir(cb, entry, destType, destProfileId, destRemote, destEntryPath)
                            } else {
                                crossCopyFile(cb, entry, destType, destProfileId, destRemote, destEntryPath)
                            }
                        }
                    }

                    if (cb.isCut) {
                        withContext(Dispatchers.IO) {
                            deleteSourceEntry(cb, entry)
                        }
                    }
                }

                _clipboard.value = null
                _message.value = "${if (cb.isCut) "Moved" else "Copied"} ${pasteFileCount.get()} files"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Paste failed", e)
                _error.value = "Paste failed: ${e.message}"
            } finally {
                pasteInProgress.set(false)
                _loading.value = false
                _transferProgress.value = null
            }
        }
    }

    /** Copy a single file between backends via temp file. */
    private fun crossCopyFile(
        cb: FileClipboard, entry: SftpEntry,
        destType: BackendType, destProfileId: String, destRemote: String?,
        destPath: String,
    ) {
        val tempFile = java.io.File(appContext.cacheDir, "cross_copy_${entry.name}")
        try {
            // Download from source to temp
            when (cb.sourceBackendType) {
                BackendType.LOCAL -> {
                    java.io.File(entry.path).copyTo(tempFile, overwrite = true)
                }
                BackendType.RCLONE -> {
                    val srcRemote = cb.sourceRemoteName!!
                    rcloneClient.copyFile(srcRemote, entry.path, tempFile.parent!!, tempFile.name)
                }
                BackendType.SFTP -> {
                    val channel = cb.sourceSftpChannel
                        ?: sessionManager.openSftpForProfile(cb.sourceProfileId)
                        ?: throw IllegalStateException("SFTP not connected")
                    tempFile.outputStream().use { out -> channel.get(entry.path, out) }
                }
                BackendType.SMB -> {
                    val client = cb.sourceSmbClient
                        ?: smbSessionManager.getClientForProfile(cb.sourceProfileId)
                        ?: throw IllegalStateException("SMB not connected")
                    tempFile.outputStream().use { out -> client.download(entry.path, out) { _, _ -> } }
                }
            }

            // Upload from temp to destination
            when (destType) {
                BackendType.LOCAL -> {
                    tempFile.copyTo(java.io.File(destPath), overwrite = true)
                }
                BackendType.RCLONE -> {
                    rcloneClient.copyFile(tempFile.parent!!, tempFile.name, destRemote!!, destPath)
                }
                BackendType.SFTP -> {
                    val channel = getOrOpenChannel(destProfileId) ?: throw IllegalStateException("SFTP not connected")
                    tempFile.inputStream().use { input -> channel.put(input, destPath) }
                }
                BackendType.SMB -> {
                    val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                    tempFile.inputStream().use { input -> client.upload(input, destPath, tempFile.length()) { _, _ -> } }
                }
            }
            updatePasteProgress(entry.name)
        } finally {
            tempFile.delete()
        }
    }

    /** Recursively copy a directory between backends. */
    private fun crossCopyDir(
        cb: FileClipboard, entry: SftpEntry,
        destType: BackendType, destProfileId: String, destRemote: String?,
        destPath: String,
    ) {
        // Create destination directory
        when (destType) {
            BackendType.LOCAL -> java.io.File(destPath).mkdirs()
            BackendType.RCLONE -> rcloneClient.mkdir(destRemote!!, destPath)
            BackendType.SFTP -> {
                val channel = getOrOpenChannel(destProfileId) ?: return
                try { channel.mkdir(destPath) } catch (_: Exception) {}
            }
            BackendType.SMB -> {
                val client = activeSmbClient ?: return
                client.mkdir(destPath)
            }
        }

        // List source directory and copy contents
        Log.d(TAG, "crossCopyDir: listing ${cb.sourceBackendType} ${entry.path}")
        val children = when (cb.sourceBackendType) {
            BackendType.LOCAL -> {
                java.io.File(entry.path).listFiles()?.map { f ->
                    SftpEntry(f.name, f.absolutePath, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified() / 1000, "")
                } ?: emptyList()
            }
            BackendType.RCLONE -> {
                rcloneClient.listDirectory(cb.sourceRemoteName!!, entry.path).map { rc ->
                    val modTime = try { java.time.Instant.parse(rc.modTime).epochSecond } catch (_: Exception) { 0L }
                    SftpEntry(rc.name, "${entry.path.trimEnd('/')}/${rc.name}", rc.isDir, rc.size, modTime, "")
                }
            }
            BackendType.SFTP -> {
                val channel = cb.sourceSftpChannel
                    ?: sessionManager.openSftpForProfile(cb.sourceProfileId) ?: return
                val results = mutableListOf<SftpEntry>()
                channel.ls(entry.path) { lsEntry ->
                    val name = lsEntry.filename
                    if (name != "." && name != "..") {
                        results.add(SftpEntry(name, "${entry.path.trimEnd('/')}/$name", lsEntry.attrs.isDir, lsEntry.attrs.size, lsEntry.attrs.mTime.toLong(), ""))
                    }
                    com.jcraft.jsch.ChannelSftp.LsEntrySelector.CONTINUE
                }
                results
            }
            BackendType.SMB -> {
                val client = cb.sourceSmbClient
                    ?: smbSessionManager.getClientForProfile(cb.sourceProfileId) ?: return
                client.listDirectory(entry.path).map { smb ->
                    SftpEntry(smb.name, smb.path, smb.isDirectory, smb.size, smb.modifiedTime, "")
                }
            }
        }

        Log.d(TAG, "crossCopyDir: found ${children.size} children in ${entry.path}")
        for (child in children) {
            val childDest = "${destPath.trimEnd('/')}/${child.name}"
            if (child.isDirectory) {
                crossCopyDir(cb, child, destType, destProfileId, destRemote, childDest)
            } else {
                crossCopyFile(cb, child, destType, destProfileId, destRemote, childDest)
            }
        }
    }

    /** Recursively copy a directory within rclone (server-side). */
    private fun copyRcloneDir(srcRemote: String, srcPath: String, dstRemote: String, dstPath: String) {
        val children = rcloneClient.listDirectory(srcRemote, srcPath)
        for (child in children) {
            val childSrc = "${srcPath.trimEnd('/')}/${child.name}"
            val childDst = "${dstPath.trimEnd('/')}/${child.name}"
            if (child.isDir) {
                rcloneClient.mkdir(dstRemote, childDst)
                copyRcloneDir(srcRemote, childSrc, dstRemote, childDst)
            } else {
                rcloneClient.copyFile(srcRemote, childSrc, dstRemote, childDst)
                updatePasteProgress(child.name)
            }
        }
    }

    /** Delete a source entry (for cut/move operations). */
    private fun deleteSourceEntry(cb: FileClipboard, entry: SftpEntry) {
        when (cb.sourceBackendType) {
            BackendType.LOCAL -> {
                val f = java.io.File(entry.path)
                if (entry.isDirectory) f.deleteRecursively() else f.delete()
            }
            BackendType.RCLONE -> {
                if (entry.isDirectory) rcloneClient.deleteDir(cb.sourceRemoteName!!, entry.path)
                else rcloneClient.deleteFile(cb.sourceRemoteName!!, entry.path)
            }
            BackendType.SFTP -> {
                val channel = cb.sourceSftpChannel
                    ?: sessionManager.openSftpForProfile(cb.sourceProfileId) ?: return
                if (entry.isDirectory) channel.rmdir(entry.path) else channel.rm(entry.path)
            }
            BackendType.SMB -> {
                val client = cb.sourceSmbClient
                    ?: smbSessionManager.getClientForProfile(cb.sourceProfileId) ?: return
                client.delete(entry.path, entry.isDirectory)
            }
        }
    }

    fun dismissError() { _error.value = null }
    fun dismissMessage() { _message.value = null }

    private fun openSftpAndList(profileId: String, path: String) {
        viewModelScope.launch {
            try {
                if (!pasteInProgress.get()) _loading.value = true
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
                if (!pasteInProgress.get()) _loading.value = false
            }
        }
    }

    private fun listDirectory(profileId: String, path: String) {
        viewModelScope.launch {
            try {
                if (!pasteInProgress.get()) _loading.value = true
                withContext(Dispatchers.IO) {
                    val channel = getOrOpenChannel(profileId) ?: throw IllegalStateException("Not connected")
                    loadEntries(channel, path)
                }
            } catch (e: Exception) {
                Log.e(TAG, "List directory failed", e)
                _error.value = "Failed to list: ${e.message}"
            } finally {
                if (!pasteInProgress.get()) _loading.value = false
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
                if (!pasteInProgress.get()) _loading.value = true
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
                if (!pasteInProgress.get()) _loading.value = true
                withContext(Dispatchers.IO) {
                    val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                    loadSmbEntries(client, path)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMB list directory failed", e)
                _error.value = "Failed to list: ${e.message}"
            } finally {
                if (!pasteInProgress.get()) _loading.value = false
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
                if (!pasteInProgress.get()) _loading.value = true
                withContext(Dispatchers.IO) {
                    val remoteName = rcloneSessionManager.getRemoteNameForProfile(profileId)
                    Log.d(TAG, "openRcloneAndList: profileId=$profileId, remoteName=$remoteName, " +
                        "isConnected=${rcloneSessionManager.isProfileConnected(profileId)}")
                    if (remoteName == null) throw IllegalStateException("Rclone session not connected")
                    activeRcloneRemote = remoteName
                    try { _remoteCapabilities.value = rcloneClient.getCapabilities(remoteName) }
                    catch (_: Exception) { _remoteCapabilities.value = sh.haven.core.rclone.RemoteCapabilities() }
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
                if (!pasteInProgress.get()) _loading.value = true
                withContext(Dispatchers.IO) {
                    val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                    loadRcloneEntries(remote, path)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rclone list directory failed", e)
                _error.value = "Failed to list: ${e.message}"
            } finally {
                if (!pasteInProgress.get()) _loading.value = false
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
                mimeType = entry.mimeType,
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

    // ── Media streaming ─────────────────────────────────────────────────

    /**
     * Ensure the media server is running for the current rclone remote.
     *
     * The Go-side server is process-scoped and survives ViewModel recreation,
     * profile switches, and Haven going to background. If a server is already
     * running for the same remote it is reused (no restart). This keeps VLC
     * streaming even if Haven drops and reconnects the rclone session.
     */
    private suspend fun ensureMediaServer(): Int {
        val remote = activeRcloneRemote ?: error("No active rclone remote")

        // Fast path: we already know the port from this ViewModel instance.
        _mediaServerPort.value?.let { return it }

        // Check if the Go side still has a server running for this remote
        // (survives ViewModel recreation).
        val existing = withContext(Dispatchers.IO) { rcloneClient.mediaServerPort(remote) }
        if (existing != null) {
            _mediaServerPort.value = existing
            return existing
        }

        // Start a new server, preferring the last-known port so VLC can
        // reconnect after an app restart.
        val preferred = preferencesRepository.lastMediaServerPort.first()
        val port = withContext(Dispatchers.IO) {
            rcloneClient.startMediaServer(remote, preferred)
        }
        _mediaServerPort.value = port
        // Persist for next restart.
        preferencesRepository.setLastMediaServerPort(port)
        return port
    }

    /** Play a single media file via HTTP streaming through the rclone media server. */
    fun playMediaFile(entry: SftpEntry) {
        Log.d(TAG, "playMediaFile: ${entry.path}")
        viewModelScope.launch {
            try {
                val port = ensureMediaServer()
                // URL-encode each path segment so spaces / parentheses / unicode
                // don't break the intent URI. Without this, VLC (and Android's
                // intent parser) silently fail on file names with special chars.
                val encodedPath = entry.path
                    .trimStart('/')
                    .split('/')
                    .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                val url = "http://127.0.0.1:$port/$encodedPath"
                val mimeType = entry.mimeType.ifEmpty {
                    android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase())
                        ?: "video/*"
                }
                Log.d(TAG, "playMediaFile: launching $url ($mimeType)")
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(url), mimeType)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "playMediaFile: no activity to handle", e)
                _error.value = "No media player app installed"
            } catch (e: Exception) {
                Log.e(TAG, "Play media failed", e)
                _error.value = "Playback failed: ${e.message}"
            }
        }
    }

    /** Play all media files in the current folder as a sorted playlist. */
    fun playFolder() {
        viewModelScope.launch {
            try {
                val port = ensureMediaServer()
                val mediaEntries = _entries.value
                    .filter { it.isMediaFile(mediaExtensionsSet.value) }
                    .sortedWith(compareBy(NATURAL_SORT_COMPARATOR) { it.name })

                if (mediaEntries.isEmpty()) {
                    _error.value = "No media files in this folder"
                    return@launch
                }

                val playlist = buildString {
                    appendLine("#EXTM3U")
                    for (entry in mediaEntries) {
                        appendLine("#EXTINF:-1,${entry.name}")
                        appendLine("http://127.0.0.1:$port/${entry.path}")
                    }
                }

                val cacheDir = java.io.File(appContext.cacheDir, "playlists")
                cacheDir.mkdirs()
                val playlistFile = java.io.File(cacheDir, "playlist.m3u8")
                playlistFile.writeText(playlist)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    playlistFile,
                )

                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "audio/x-mpegurl")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                _error.value = "No media player app installed"
            } catch (e: Exception) {
                Log.e(TAG, "Play folder failed", e)
                _error.value = "Playback failed: ${e.message}"
            }
        }
    }

    // ── Folder sync ───────────────────────────────────────────────────

    fun showSyncDialog(sourcePath: String? = null) {
        val remote = activeRcloneRemote ?: return
        val path = sourcePath ?: _currentPath.value
        _syncDialogSource.value = "$remote:$path"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _availableRemotes.value = rcloneClient.listRemotes()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list remotes", e)
            }
        }
        _showSyncDialog.value = true
    }

    fun dismissSyncDialog() {
        _showSyncDialog.value = false
    }

    fun startSync(config: SyncConfig) {
        _showSyncDialog.value = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { rcloneClient.resetStats() }

                val jobId = withContext(Dispatchers.IO) { rcloneClient.startSync(config) }

                // Poll progress until finished
                while (true) {
                    delay(500)
                    val status = withContext(Dispatchers.IO) { rcloneClient.getJobStatus(jobId) }
                    val stats = withContext(Dispatchers.IO) { rcloneClient.getStats() }

                    val eta = if (stats.speed > 0 && stats.totalBytes > stats.bytes) {
                        ((stats.totalBytes - stats.bytes) / stats.speed).toLong()
                    } else 0L

                    _syncProgress.value = SyncProgress(
                        jobId = jobId,
                        mode = config.mode,
                        bytes = stats.bytes,
                        totalBytes = stats.totalBytes,
                        speed = stats.speed,
                        eta = eta,
                        transfersCompleted = stats.transfers,
                        totalTransfers = stats.totalTransfers,
                        errors = stats.errors,
                        finished = status.finished,
                        success = status.success,
                        errorMessage = status.error,
                        dryRun = config.dryRun,
                    )

                    if (status.finished) break
                }

                val final = _syncProgress.value
                _syncProgress.value = null
                rcloneClient.activeSyncJobId = null

                if (config.dryRun) {
                    val files = final?.totalTransfers ?: 0
                    val bytes = android.text.format.Formatter.formatFileSize(appContext, final?.totalBytes ?: 0)
                    _dryRunResult.value = "Would transfer $files files ($bytes)"
                } else if (final?.success == true) {
                    val files = final.transfersCompleted
                    _message.value = "Sync complete: $files files transferred"
                    refresh()
                } else {
                    _error.value = "Sync failed: ${final?.errorMessage ?: "unknown error"}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                _syncProgress.value = null
                rcloneClient.activeSyncJobId = null
                _error.value = "Sync failed: ${e.message}"
            }
        }
    }

    fun cancelSync() {
        val jobId = rcloneClient.activeSyncJobId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            rcloneClient.cancelJob(jobId)
        }
    }

    companion object {
        private val MEDIA_MIME_PREFIXES = listOf("audio/", "video/")

        fun parseMediaExtensions(str: String): Set<String> =
            str.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }.toSet()

        fun SftpEntry.isMediaFile(extensions: Set<String>): Boolean {
            if (isDirectory) return false
            if (mimeType.isNotEmpty()) {
                return MEDIA_MIME_PREFIXES.any { mimeType.startsWith(it) }
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in extensions
        }

        /** Natural sort: numeric chunks compared as numbers, text chunks compared lexicographically. */
        val NATURAL_SORT_COMPARATOR = Comparator<String> { a, b ->
            val regex = Regex("(\\d+)|(\\D+)")
            val aParts = regex.findAll(a.lowercase()).map { it.value }.toList()
            val bParts = regex.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(aParts.size, bParts.size)) {
                val ap = aParts[i]
                val bp = bParts[i]
                val aNum = ap.toLongOrNull()
                val bNum = bp.toLongOrNull()
                val cmp = when {
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    else -> ap.compareTo(bp)
                }
                if (cmp != 0) return@Comparator cmp
            }
            aParts.size.compareTo(bParts.size)
        }
    }
}
