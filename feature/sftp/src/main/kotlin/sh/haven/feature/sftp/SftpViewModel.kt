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
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
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

/** How many bytes must flow through a transfer before we update the queue row's
 *  `bytesTransferred` cursor. Smaller = tighter resume resolution at the cost of
 *  more DB writes. 1 MB strikes a balance — negligible replay on crash, cheap DB
 *  traffic on multi-GB SFTP uploads. */
private const val QUEUE_PROGRESS_PERSIST_BYTES = 1L * 1024 * 1024

/** Per-row retry budget for transient failures (connection drops, broken pipes).
 *  Permanent failures — missing source, permission denied — don't retry. */
private const val QUEUE_RETRY_ATTEMPTS = 3

/** First backoff in ms between retries; doubled each attempt (1s / 2s / 4s). */
private const val QUEUE_RETRY_BACKOFF_MS = 1_000L

data class SftpEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: Long,
    val permissions: String,
    val mimeType: String = "",
    /** Owner name (SCP `ls -la`) or numeric UID (SFTP `SftpATTRS`). Empty if unknown. */
    val owner: String = "",
    /** Group name (SCP `ls -la`) or numeric GID (SFTP `SftpATTRS`). Empty if unknown. */
    val group: String = "",
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

/**
 * Action to take when a paste target file already exists at the destination.
 *
 * Modelled on Windows Explorer's "File in Use" / "Replace or Skip" dialog,
 * driven from [SftpViewModel.conflictPrompt] and surfaced by the UI as a
 * single dialog with an "Apply to all" checkbox.
 *
 * [RESUME] is only offered when the destination is SFTP or LOCAL *and* the
 * existing file is smaller than the source — the only shape where an append-
 * from-offset actually recovers the partial transfer. SMB and rclone destinations
 * fall back to OVERWRITE / SKIP / RENAME because neither backend supports a
 * resumable append through Haven's current plumbing.
 */
enum class ConflictAction { RESUME, OVERWRITE, SKIP, RENAME }

/** One pending conflict-resolution prompt. UI calls [onChoice] exactly once. */
data class ConflictPrompt(
    val fileName: String,
    val sourceSize: Long,
    val destSize: Long,
    val canResume: Boolean,
    val onChoice: (action: ConflictAction, applyToAll: Boolean) -> Unit,
)

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
    private val connectionLogRepository: ConnectionLogRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val ffmpegExecutor: sh.haven.core.ffmpeg.FfmpegExecutor,
    val hlsStreamServer: sh.haven.core.ffmpeg.HlsStreamServer,
    private val sftpStreamServer: SftpStreamServer,
    private val pasteQueueDao: sh.haven.core.data.db.PasteQueueDao,
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

    enum class FilterMode { GLOB, REGEX }
    private val _fileFilter = MutableStateFlow("")
    val fileFilter: StateFlow<String> = _fileFilter.asStateFlow()
    private val _filterMode = MutableStateFlow(FilterMode.GLOB)
    val filterMode: StateFlow<FilterMode> = _filterMode.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
    val transferProgress: StateFlow<TransferProgress?> = _transferProgress.asStateFlow()

    /** Non-null while a paste-time file conflict is awaiting the user's choice. */
    private val _conflictPrompt = MutableStateFlow<ConflictPrompt?>(null)
    val conflictPrompt: StateFlow<ConflictPrompt?> = _conflictPrompt.asStateFlow()

    /**
     * Resolution the user chose with "Apply to all" for the current paste batch.
     * Reset to null at the start of every [pasteFromClipboard] call. When set,
     * subsequent conflicts in this batch silently apply this action instead of
     * prompting again.
     */
    private var batchResolution: ConflictAction? = null

    /**
     * Partial wake lock held for the duration of an active paste. Without this,
     * the CPU can drop to a low-power state mid-transfer (phone locked, screen
     * off) and the simultaneous USB + network IO becomes unreliable — which on
     * some devices escalates all the way to a kernel watchdog reboot.
     */
    private var pasteWakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Observable count of files still pending in the persistent paste queue.
     * Drives the "Unfinished paste: N files remaining" banner at the top of
     * the SFTP screen. Non-zero means a previous paste was interrupted
     * (network drop, process death, reboot) and the user can tap Resume.
     */
    val pastePendingCount: StateFlow<Int> =
        pasteQueueDao.observeCountByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Observable total bytes remaining across pending queue rows — the
     * banner shows this in a human-readable form.
     */
    val pastePendingBytes: StateFlow<Long> =
        pasteQueueDao.observePendingBytes(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** ID of the queue row currently being transferred, so the progress monitor
     *  can throttle `bytesTransferred` updates back to the DB. Null when no
     *  transfer is in flight. */
    @Volatile
    private var currentQueueRowId: Long? = null
    @Volatile
    private var lastQueueRowBytesPersist: Long = 0L

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

    /**
     * Label of the transport currently servicing the active SSH profile —
     * `"SFTP"`, `"SCP"`, or null while non-SSH backends are active. Shown
     * as a badge in the path bar so users always know what they're on.
     */
    private val _activeTransportLabel = MutableStateFlow<String?>(null)
    val activeTransportLabel: StateFlow<String?> = _activeTransportLabel.asStateFlow()

    fun openConvertDialog(entry: SftpEntry) { _convertDialogEntry.value = entry }
    fun dismissConvertDialog() { _convertDialogEntry.value = null; clearPreview() }

    /** Which entry the media-actions bottom sheet is showing, if any. */
    private val _mediaSheetEntry = MutableStateFlow<SftpEntry?>(null)
    val mediaSheetEntry: StateFlow<SftpEntry?> = _mediaSheetEntry.asStateFlow()
    fun openMediaSheet(entry: SftpEntry) { _mediaSheetEntry.value = entry }
    fun dismissMediaSheet() { _mediaSheetEntry.value = null }

    /** Trim dialog state. */
    private val _trimDialogEntry = MutableStateFlow<SftpEntry?>(null)
    val trimDialogEntry: StateFlow<SftpEntry?> = _trimDialogEntry.asStateFlow()
    fun openTrimDialog(entry: SftpEntry) { _trimDialogEntry.value = entry }
    fun dismissTrimDialog() { _trimDialogEntry.value = null }

    /** Extract-audio dialog state. */
    private val _extractAudioDialogEntry = MutableStateFlow<SftpEntry?>(null)
    val extractAudioDialogEntry: StateFlow<SftpEntry?> = _extractAudioDialogEntry.asStateFlow()
    fun openExtractAudioDialog(entry: SftpEntry) { _extractAudioDialogEntry.value = entry }
    fun dismissExtractAudioDialog() { _extractAudioDialogEntry.value = null }

    /** Contact-sheet dialog state. */
    private val _contactSheetDialogEntry = MutableStateFlow<SftpEntry?>(null)
    val contactSheetDialogEntry: StateFlow<SftpEntry?> = _contactSheetDialogEntry.asStateFlow()
    fun openContactSheetDialog(entry: SftpEntry) { _contactSheetDialogEntry.value = entry }
    fun dismissContactSheetDialog() { _contactSheetDialogEntry.value = null }

    /** Media-info panel state: null = hidden; Loading = probing; Loaded = show result. */
    sealed class MediaInfoState {
        data class Loading(val entry: SftpEntry) : MediaInfoState()
        data class Loaded(val entry: SftpEntry, val info: sh.haven.core.ffmpeg.MediaInfo) : MediaInfoState()
        data class Failed(val entry: SftpEntry, val reason: String) : MediaInfoState()
    }
    private val _mediaInfoState = MutableStateFlow<MediaInfoState?>(null)
    val mediaInfoState: StateFlow<MediaInfoState?> = _mediaInfoState.asStateFlow()
    fun dismissMediaInfo() { _mediaInfoState.value = null }

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

    /** Editor overlay state: file content loaded for viewing. */
    sealed class EditorFileState {
        data object Closed : EditorFileState()
        data object Loading : EditorFileState()
        data class Open(val fileName: String, val filePath: String, val content: String) : EditorFileState()
        data class Error(val message: String) : EditorFileState()
    }
    private val _editorFile = MutableStateFlow<EditorFileState>(EditorFileState.Closed)
    val editorFile: StateFlow<EditorFileState> = _editorFile.asStateFlow()

    private val _editorSaving = MutableStateFlow(false)
    val editorSaving: StateFlow<Boolean> = _editorSaving.asStateFlow()

    val terminalColorScheme: StateFlow<UserPreferencesRepository.TerminalColorScheme> =
        preferencesRepository.terminalColorScheme
            .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferencesRepository.TerminalColorScheme.HAVEN)

    private var editorEntry: SftpEntry? = null

    fun openInEditor(entry: SftpEntry) {
        if (entry.isDirectory) return
        editorEntry = entry
        _editorFile.value = EditorFileState.Loading
        viewModelScope.launch {
            try {
                val bytes = java.io.ByteArrayOutputStream()
                if (_isLocalProfile.value) {
                    withContext(Dispatchers.IO) {
                        java.io.File(entry.path).inputStream().use { it.copyTo(bytes) }
                    }
                } else if (_isRcloneProfile.value) {
                    withContext(Dispatchers.IO) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        val tempFile = java.io.File(appContext.cacheDir, "editor_${entry.name}")
                        try {
                            rcloneClient.copyFile(remote, entry.path, tempFile.parent!!, tempFile.name)
                            tempFile.inputStream().use { it.copyTo(bytes) }
                        } finally {
                            tempFile.delete()
                        }
                    }
                } else if (_isSmbProfile.value) {
                    withContext(Dispatchers.IO) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        client.download(entry.path, bytes) { _, _ -> }
                    }
                } else {
                    val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                    transport.download(entry.path, bytes, entry.size) { _, _ -> }
                }
                val content = bytes.toByteArray().toString(Charsets.UTF_8)
                _editorFile.value = EditorFileState.Open(entry.name, entry.path, content)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load file for editor", e)
                _editorFile.value = EditorFileState.Error(e.message ?: "Failed to load file")
            }
        }
    }

    fun saveEditorContent(content: String) {
        val entry = editorEntry ?: return
        _editorSaving.value = true
        viewModelScope.launch {
            try {
                val data = content.toByteArray(Charsets.UTF_8)
                if (_isLocalProfile.value) {
                    withContext(Dispatchers.IO) {
                        java.io.File(entry.path).writeBytes(data)
                    }
                } else if (_isRcloneProfile.value) {
                    withContext(Dispatchers.IO) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        val tempFile = java.io.File(appContext.cacheDir, "editor_save_${entry.name}")
                        try {
                            tempFile.writeBytes(data)
                            rcloneClient.copyFile(
                                tempFile.parent!!, tempFile.name,
                                remote, entry.path,
                            )
                        } finally {
                            tempFile.delete()
                        }
                    }
                } else if (_isSmbProfile.value) {
                    withContext(Dispatchers.IO) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        java.io.ByteArrayInputStream(data).use { input ->
                            client.upload(input, entry.path, data.size.toLong()) { _, _ -> }
                        }
                    }
                } else {
                    val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                    java.io.ByteArrayInputStream(data).use { input ->
                        transport.upload(input, data.size.toLong(), entry.path) { _, _ -> }
                    }
                }
                _editorFile.value = EditorFileState.Open(entry.name, entry.path, content)
                _message.value = "Saved ${entry.name}"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save file from editor", e)
                _error.value = "Save failed: ${e.message}"
            } finally {
                _editorSaving.value = false
            }
        }
    }

    fun closeEditor() {
        _editorFile.value = EditorFileState.Closed
        editorEntry = null
    }

    /** Image tools overlay state. */
    sealed class ImageToolFileState {
        data object Closed : ImageToolFileState()
        data object Loading : ImageToolFileState()
        data class Open(
            val fileName: String,
            val filePath: String,
            val cachePath: String,
            val bitmap: android.graphics.Bitmap,
        ) : ImageToolFileState()
        data class Preview(
            val fileName: String,
            val originalBitmap: android.graphics.Bitmap,
            val resultBitmap: android.graphics.Bitmap,
            val resultCachePath: String,
        ) : ImageToolFileState()
        data class Processing(val label: String) : ImageToolFileState()
        data class Error(val message: String) : ImageToolFileState()
    }
    private val _imageToolFile = MutableStateFlow<ImageToolFileState>(ImageToolFileState.Closed)
    val imageToolFile: StateFlow<ImageToolFileState> = _imageToolFile.asStateFlow()

    private val _imageToolSaving = MutableStateFlow(false)
    val imageToolSaving: StateFlow<Boolean> = _imageToolSaving.asStateFlow()

    private var imageToolEntry: SftpEntry? = null

    fun openInImageTools(entry: SftpEntry) {
        if (entry.isDirectory) return
        imageToolEntry = entry
        _imageToolFile.value = ImageToolFileState.Loading
        viewModelScope.launch {
            try {
                val cachePath = java.io.File(appContext.cacheDir, "imgtools_${entry.name}").absolutePath
                val bytes = java.io.ByteArrayOutputStream()
                if (_isLocalProfile.value) {
                    withContext(Dispatchers.IO) {
                        java.io.File(entry.path).inputStream().use { it.copyTo(bytes) }
                    }
                } else if (_isRcloneProfile.value) {
                    withContext(Dispatchers.IO) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        val tempFile = java.io.File(cachePath)
                        rcloneClient.copyFile(remote, entry.path, tempFile.parent!!, tempFile.name)
                        tempFile.inputStream().use { it.copyTo(bytes) }
                    }
                } else if (_isSmbProfile.value) {
                    withContext(Dispatchers.IO) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        client.download(entry.path, bytes) { _, _ -> }
                    }
                } else {
                    val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                    transport.download(entry.path, bytes, entry.size) { _, _ -> }
                }
                val data = bytes.toByteArray()
                withContext(Dispatchers.IO) {
                    java.io.File(cachePath).writeBytes(data)
                }
                val bitmap = withContext(Dispatchers.IO) {
                    val opts = android.graphics.BitmapFactory.Options()
                    opts.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    val maxDim = 2048
                    var sampleSize = 1
                    while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                        sampleSize *= 2
                    }
                    val decodeOpts = android.graphics.BitmapFactory.Options()
                    decodeOpts.inSampleSize = sampleSize
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts)
                        ?: throw IllegalStateException("Cannot decode image")
                }
                _imageToolFile.value = ImageToolFileState.Open(
                    entry.name, entry.path, cachePath, bitmap,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load image for tools", e)
                _imageToolFile.value = ImageToolFileState.Error(e.message ?: "Failed to load image")
            }
        }
    }

    fun applyPerspective(corners: List<androidx.compose.ui.geometry.Offset>, imgWidth: Int, imgHeight: Int) {
        val current = _imageToolFile.value
        if (current !is ImageToolFileState.Open) return
        _imageToolFile.value = ImageToolFileState.Processing("Applying perspective…")
        viewModelScope.launch {
            try {
                val c = corners
                val filter = sh.haven.core.ffmpeg.VideoFilter.Perspective(
                    x0 = c[0].x * imgWidth, y0 = c[0].y * imgHeight,
                    x1 = c[1].x * imgWidth, y1 = c[1].y * imgHeight,
                    x2 = c[2].x * imgWidth, y2 = c[2].y * imgHeight,
                    x3 = c[3].x * imgWidth, y3 = c[3].y * imgHeight,
                )
                val ext = current.fileName.substringAfterLast('.', "jpg").lowercase()
                val outPath = java.io.File(appContext.cacheDir, "imgtools_result_${current.fileName}").absolutePath
                val result = withContext(Dispatchers.IO) {
                    ffmpegExecutor.execute(
                        listOf(
                            "-y", "-i", current.cachePath,
                            "-vf", filter.toFfmpeg(),
                            "-frames:v", "1",
                            "-q:v", "2",
                            outPath,
                        )
                    )
                }
                if (result.exitCode != 0) {
                    throw IllegalStateException("FFmpeg failed (exit ${result.exitCode})")
                }
                val resultBitmap = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(outPath)
                        ?: throw IllegalStateException("Cannot decode result")
                }
                _imageToolFile.value = ImageToolFileState.Preview(
                    current.fileName, current.bitmap, resultBitmap, outPath,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Perspective transform failed", e)
                _imageToolFile.value = ImageToolFileState.Error(e.message ?: "Transform failed")
            }
        }
    }

    fun applyCrop(left: Float, top: Float, right: Float, bottom: Float, imgWidth: Int, imgHeight: Int) {
        val current = _imageToolFile.value
        if (current !is ImageToolFileState.Open) return
        _imageToolFile.value = ImageToolFileState.Processing("Cropping…")
        viewModelScope.launch {
            try {
                val x = (left * imgWidth).toInt().coerceAtLeast(0)
                val y = (top * imgHeight).toInt().coerceAtLeast(0)
                val w = ((right - left) * imgWidth).toInt().coerceAtLeast(1)
                val h = ((bottom - top) * imgHeight).toInt().coerceAtLeast(1)
                val filter = sh.haven.core.ffmpeg.VideoFilter.Crop(w, h, x, y)
                val outPath = java.io.File(appContext.cacheDir, "imgtools_result_${current.fileName}").absolutePath
                val result = withContext(Dispatchers.IO) {
                    ffmpegExecutor.execute(listOf("-y", "-i", current.cachePath, "-vf", filter.toFfmpeg(), "-frames:v", "1", "-q:v", "2", outPath))
                }
                if (result.exitCode != 0) throw IllegalStateException("FFmpeg failed (exit ${result.exitCode})")
                val resultBitmap = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(outPath) ?: throw IllegalStateException("Cannot decode result")
                }
                _imageToolFile.value = ImageToolFileState.Preview(current.fileName, current.bitmap, resultBitmap, outPath)
            } catch (e: Exception) {
                Log.e(TAG, "Crop failed", e)
                _imageToolFile.value = ImageToolFileState.Error(e.message ?: "Crop failed")
            }
        }
    }

    fun applyRotate(degrees: Float, imgWidth: Int, imgHeight: Int) {
        val current = _imageToolFile.value
        if (current !is ImageToolFileState.Open) return
        _imageToolFile.value = ImageToolFileState.Processing("Rotating…")
        viewModelScope.launch {
            try {
                val filter = sh.haven.core.ffmpeg.VideoFilter.Rotate(degrees)
                val outPath = java.io.File(appContext.cacheDir, "imgtools_result_${current.fileName}").absolutePath
                val result = withContext(Dispatchers.IO) {
                    ffmpegExecutor.execute(listOf("-y", "-i", current.cachePath, "-vf", filter.toFfmpeg(), "-frames:v", "1", "-q:v", "2", outPath))
                }
                if (result.exitCode != 0) throw IllegalStateException("FFmpeg failed (exit ${result.exitCode})")
                val resultBitmap = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(outPath) ?: throw IllegalStateException("Cannot decode result")
                }
                _imageToolFile.value = ImageToolFileState.Preview(current.fileName, current.bitmap, resultBitmap, outPath)
            } catch (e: Exception) {
                Log.e(TAG, "Rotate failed", e)
                _imageToolFile.value = ImageToolFileState.Error(e.message ?: "Rotate failed")
            }
        }
    }

    fun resetImageTool() {
        val current = _imageToolFile.value
        if (current is ImageToolFileState.Preview) {
            val open = _imageToolFile.value
            // Reload from the original cached file
            viewModelScope.launch {
                try {
                    val entry = imageToolEntry ?: return@launch
                    val cachePath = java.io.File(appContext.cacheDir, "imgtools_${entry.name}").absolutePath
                    val bitmap = withContext(Dispatchers.IO) {
                        android.graphics.BitmapFactory.decodeFile(cachePath)
                            ?: throw IllegalStateException("Cannot reload image")
                    }
                    _imageToolFile.value = ImageToolFileState.Open(
                        entry.name, entry.path, cachePath, bitmap,
                    )
                } catch (e: Exception) {
                    _imageToolFile.value = ImageToolFileState.Error(e.message ?: "Reset failed")
                }
            }
        }
    }

    fun saveImageToolResult() {
        val current = _imageToolFile.value
        if (current !is ImageToolFileState.Preview) return
        val entry = imageToolEntry ?: return
        _imageToolSaving.value = true
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    java.io.File(current.resultCachePath).readBytes()
                }
                if (_isLocalProfile.value) {
                    withContext(Dispatchers.IO) {
                        java.io.File(entry.path).writeBytes(data)
                    }
                } else if (_isRcloneProfile.value) {
                    withContext(Dispatchers.IO) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        rcloneClient.copyFile(
                            java.io.File(current.resultCachePath).parent!!, java.io.File(current.resultCachePath).name,
                            remote, entry.path,
                        )
                    }
                } else if (_isSmbProfile.value) {
                    withContext(Dispatchers.IO) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        java.io.ByteArrayInputStream(data).use { input ->
                            client.upload(input, entry.path, data.size.toLong()) { _, _ -> }
                        }
                    }
                } else {
                    val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                    java.io.ByteArrayInputStream(data).use { input ->
                        transport.upload(input, data.size.toLong(), entry.path) { _, _ -> }
                    }
                }
                _message.value = "Saved ${entry.name}"
                closeImageTools()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image tool result", e)
                _error.value = "Save failed: ${e.message}"
            } finally {
                _imageToolSaving.value = false
            }
        }
    }

    fun closeImageTools() {
        _imageToolFile.value = ImageToolFileState.Closed
        imageToolEntry = null
    }

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
        _selectedPaths.value = emptySet()
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

    fun setFileFilter(pattern: String) {
        _fileFilter.value = pattern
        applyFilter()
    }

    fun setFilterMode(mode: FilterMode) {
        _filterMode.value = mode
        applyFilter()
    }

    private fun applyFilter() {
        val all = _allEntries.value
        var filtered = if (_showHidden.value) all else all.filter { !it.name.startsWith(".") }

        val pattern = _fileFilter.value
        if (pattern.isNotEmpty()) {
            val regex = try {
                when (_filterMode.value) {
                    FilterMode.REGEX -> Regex(pattern, RegexOption.IGNORE_CASE)
                    FilterMode.GLOB -> globToRegex(pattern)
                }
            } catch (_: Exception) {
                null
            }
            if (regex != null) {
                filtered = filtered.filter { it.isDirectory || regex.containsMatchIn(it.name) }
            }
        }

        _entries.value = filtered
        _hasMediaFiles.value = _isRcloneProfile.value && filtered.any { it.isMediaFile(mediaExtensionsSet.value) }
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (glob[i]) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.' -> sb.append("\\.")
                '[' -> {
                    sb.append('[')
                    i++
                    if (i < glob.length && glob[i] == '!') {
                        sb.append('^')
                        i++
                    }
                    while (i < glob.length && glob[i] != ']') {
                        if (glob[i] == '\\' && i + 1 < glob.length) {
                            sb.append("\\${glob[i + 1]}")
                            i++
                        } else {
                            sb.append(glob[i])
                        }
                        i++
                    }
                    sb.append(']')
                }
                '\\' -> {
                    i++
                    if (i < glob.length) sb.append(Regex.escape(glob[i].toString()))
                }
                else -> {
                    if (glob[i] in "(){}+|^$") sb.append("\\")
                    sb.append(glob[i])
                }
            }
            i++
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
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
        // Removable storage — USB SD card readers, USB flash drives, and
        // (on some phones) physical microSD slots. StorageManager enumerates
        // all mounted volumes; each one with a readable `.directory`
        // (API 30+) is surfaced as its own root. The primary emulated volume
        // is deliberately skipped because `Internal Storage` above already
        // covers it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val sm = appContext.getSystemService(android.os.storage.StorageManager::class.java)
            val primaryPath = storage.absolutePath
            sm?.storageVolumes?.forEach { volume ->
                try {
                    if (volume.isPrimary) return@forEach
                    val state = volume.state
                    if (state != android.os.Environment.MEDIA_MOUNTED &&
                        state != android.os.Environment.MEDIA_MOUNTED_READ_ONLY) {
                        return@forEach
                    }
                    val dir = volume.directory ?: return@forEach
                    if (dir.absolutePath == primaryPath) return@forEach
                    if (!dir.canRead()) return@forEach
                    val label = volume.getDescription(appContext)
                        ?: dir.name
                        ?: "Removable Storage"
                    roots.add(
                        SftpEntry(
                            name = label,
                            path = dir.absolutePath,
                            isDirectory = true,
                            size = 0,
                            modifiedTime = dir.lastModified() / 1000,
                            permissions = "",
                        ),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping storage volume: ${e.message}")
                }
            }
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
                val outputStream: OutputStream = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(destinationUri)
                        ?: throw IllegalStateException("Cannot open output stream")
                }
                outputStream.use { out ->
                    if (_isRcloneProfile.value) {
                        withContext(Dispatchers.IO) {
                            val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                            val tempFile = java.io.File(appContext.cacheDir, "rclone_dl_${entry.name}")
                            try {
                                rcloneClient.copyFile(remote, entry.path, tempFile.parent!!, tempFile.name)
                                tempFile.inputStream().use { it.copyTo(out) }
                                _transferProgress.value = TransferProgress(entry.name, entry.size, entry.size)
                            } finally {
                                tempFile.delete()
                            }
                        }
                    } else if (_isSmbProfile.value) {
                        withContext(Dispatchers.IO) {
                            val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                            client.download(entry.path, out) { transferred, total ->
                                _transferProgress.value = TransferProgress(entry.name, total, transferred)
                            }
                        }
                    } else {
                        val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                        transport.download(entry.path, out, entry.size) { transferred, total ->
                            _transferProgress.value = TransferProgress(entry.name, total, transferred)
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
        /** User-chosen quality (CRF); 0 means "let the encoder default decide". */
        crf: Int = 0,
        /** libx264/libx265 speed preset (ultrafast..veryslow). Ignored by VP9. */
        preset: String? = null,
        /** Target output height in pixels, or null to keep source resolution. */
        scaleHeight: Int? = null,
        /** Audio bitrate like "192k", or null for the encoder default. */
        audioBitrate: String? = null,
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
                    // Apply user-chosen quality if present, else fall back to
                    // encoder defaults. For VP9 we keep the -b:v 0 trick so CRF
                    // is honoured as a quality ceiling.
                    val effectiveCrf = if (crf > 0) crf else when (videoEncoder) {
                        "libx264" -> 23; "libx265" -> 28; "libvpx-vp9" -> 31
                        else -> 0
                    }
                    val effectivePreset = preset ?: when (videoEncoder) {
                        "libx264", "libx265" -> "medium"
                        else -> null
                    }
                    if (effectiveCrf > 0 && videoEncoder != "copy") cmd.crf(effectiveCrf)
                    if (effectivePreset != null && (videoEncoder == "libx264" || videoEncoder == "libx265")) {
                        cmd.preset(effectivePreset)
                    }
                    if (videoEncoder == "libvpx-vp9") cmd.extra("-b:v", "0")
                    // Scale preserving aspect ratio; -2 keeps the derived
                    // dimension even so H.264/H.265 encoders don't complain.
                    scaleHeight?.let { h -> cmd.scale("-2:$h") }
                } else {
                    cmd.extra("-vn")
                }
                cmd.audioCodec(audioEncoder)
                    .videoFilters(videoFilters)
                    .audioFilters(audioFilters)
                if (audioBitrate != null && audioEncoder != "copy" && audioEncoder != "flac") {
                    cmd.audioBitrate(audioBitrate)
                }

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

    // ── Media info / trim / extract audio / contact sheet ────────────────

    /**
     * Persist a media-job event to the ConnectionLog store so its captured
     * ffmpeg stderr + command line is visible in the existing Audit Log UI.
     * No-op for the synthetic "local" profile since it isn't in the DB and
     * the foreign-key insert would fail.
     *
     * Gated on the user's connectionLoggingEnabled preference — we call the
     * repository which honours that gate, so logging is silently skipped if
     * the user hasn't opted in.
     */
    private suspend fun logMediaEvent(
        entry: SftpEntry,
        label: String,
        status: ConnectionLog.Status,
        startMs: Long,
        verboseLog: String,
        extra: String?,
    ) {
        val profileId = _activeProfileId.value ?: return
        if (profileId == "local") return
        try {
            connectionLogRepository.logEvent(
                profileId = profileId,
                status = status,
                durationMs = System.currentTimeMillis() - startMs,
                details = buildString {
                    append(label.trim())
                    append(": ").append(entry.name)
                    if (!extra.isNullOrBlank()) append(" — ").append(extra)
                },
                verboseLog = verboseLog,
            )
        } catch (e: Exception) {
            Log.w(TAG, "logMediaEvent failed", e)
        }
    }


    /**
     * Resolve an [entry] to an ffmpeg-readable input string without
     * downloading the whole file:
     *  - local → absolute path
     *  - rclone → loopback HTTP URL via ensureMediaServer
     *  - SFTP → loopback HTTP URL via sftpStreamServer
     *  - SMB → throws (no HTTP bridge yet)
     */
    private suspend fun resolveStreamInput(entry: SftpEntry): String {
        return when {
            _isLocalProfile.value -> entry.path
            _isRcloneProfile.value -> {
                val port = ensureMediaServer()
                val encodedPath = entry.path
                    .trimStart('/')
                    .split('/')
                    .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                "http://127.0.0.1:$port/$encodedPath"
            }
            _isSmbProfile.value -> throw IllegalStateException("SMB not supported for this action")
            else -> {
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
    }

    /** Upload [cacheOutput] to the chosen destination. Mirrors convertFile phase 3. */
    private suspend fun saveProcessedOutput(
        entry: SftpEntry,
        cacheOutput: java.io.File,
        outName: String,
        mimeType: String,
        destination: ConvertDestination,
    ): String {
        val profileId = _activeProfileId.value
        return when (destination) {
            ConvertDestination.DOWNLOADS -> {
                withContext(Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, outName)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                        }
                        val uri = appContext.contentResolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                        ) ?: throw IllegalStateException("Failed to create Downloads entry")
                        appContext.contentResolver.openOutputStream(uri)?.use { out ->
                            cacheOutput.inputStream().use { it.copyTo(out) }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dlDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS,
                        )
                        cacheOutput.copyTo(java.io.File(dlDir, outName), overwrite = true)
                    }
                }
                "Downloads"
            }
            ConvertDestination.SOURCE_FOLDER -> {
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
                        val channel = getOrOpenChannel(profileId!!)
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
    }

    /**
     * Shared execution helper for trim / extractAudio / contactSheet:
     * resolve input, run the caller-built TranscodeCommand, optionally
     * post-process the output, then save to the destination.
     *
     * @param postProcess optional transformation; returns the final file to upload
     *                    (e.g. contact sheet decodes 1-frame MP4 → PNG)
     */
    private fun runMediaJob(
        entry: SftpEntry,
        outName: String,
        outMimeType: String,
        destination: ConvertDestination,
        label: String,
        buildCommand: (input: String, output: String) -> sh.haven.core.ffmpeg.TranscodeCommand,
        postProcess: (suspend (java.io.File) -> java.io.File)? = null,
        /**
         * Name ffmpeg writes to inside the cache dir. If null the cache file
         * uses [outName], which works only when the ffmpeg output format
         * matches the user-facing extension. Contact sheet in particular
         * needs a .mp4 intermediate even though outName ends in .png.
         */
        intermediateName: String? = null,
    ) {
        viewModelScope.launch {
            var cacheOutput: java.io.File? = null
            var finalOutput: java.io.File? = null
            val logBuffer = StringBuilder()
            val startTime = System.currentTimeMillis()
            fun appendLog(line: String) {
                val elapsed = System.currentTimeMillis() - startTime
                logBuffer.append("+${elapsed}ms ").append(line).append('\n')
            }
            try {
                _loading.value = true
                val ffmpegInput = resolveStreamInput(entry)
                appendLog("input=$ffmpegInput")
                cacheOutput = java.io.File(appContext.cacheDir, "ffmpeg_out_${intermediateName ?: outName}")

                val durationSec = withContext(Dispatchers.IO) {
                    val probeResult = ffmpegExecutor.probe(sh.haven.core.ffmpeg.ProbeCommand.durationOnly(ffmpegInput))
                    probeResult.stdout.trim().toDoubleOrNull() ?: 0.0
                }

                _transferProgress.value = if (durationSec > 0) {
                    TransferProgress(label, 100, 0, isPercentage = true)
                } else {
                    TransferProgress(label, 0, 0)
                }

                val cmd = buildCommand(ffmpegInput, cacheOutput.absolutePath)
                val args = cmd.build()
                Log.d(TAG, "$label: ffmpeg ${args.joinToString(" ")}")
                appendLog("cmd=ffmpeg ${args.joinToString(" ")}")
                val result = withContext(Dispatchers.IO) {
                    ffmpegExecutor.execute(args) { stderrLine ->
                        Log.d(TAG, "ffmpeg: $stderrLine")
                        appendLog("ffmpeg: $stderrLine")
                        val progress = sh.haven.core.ffmpeg.FfmpegProgress.parse(stderrLine)
                        if (progress != null && durationSec > 0) {
                            val pct = ((progress.timeSeconds / durationSec) * 100).toLong().coerceIn(0, 99)
                            _transferProgress.value = TransferProgress(label, 100, pct, isPercentage = true)
                        }
                    }
                }
                val outSize = cacheOutput.takeIf { it.exists() }?.length() ?: -1
                Log.d(TAG, "$label: ffmpeg exit=${result.exitCode} outputSize=$outSize")
                appendLog("exit=${result.exitCode} outputSize=$outSize")
                if (!result.success) {
                    _error.value = "$label failed (exit ${result.exitCode})"
                    logMediaEvent(entry, label, ConnectionLog.Status.FAILED, startTime, logBuffer.toString(), "exit ${result.exitCode}")
                    return@launch
                }

                finalOutput = postProcess?.invoke(cacheOutput) ?: cacheOutput
                appendLog("postProcess done → ${finalOutput.name} size=${finalOutput.length()}")
                val savedLocation = saveProcessedOutput(
                    entry = entry,
                    cacheOutput = finalOutput,
                    outName = finalOutput.name,
                    mimeType = outMimeType,
                    destination = destination,
                )
                appendLog("saved → $savedLocation")
                _message.value = "Saved ${finalOutput.name} to $savedLocation"
                logMediaEvent(entry, label, ConnectionLog.Status.CONNECTED, startTime, logBuffer.toString(), savedLocation)
                if (destination == ConvertDestination.SOURCE_FOLDER) {
                    val sourceDir = entry.path.substringBeforeLast('/', "").ifEmpty { "/" }
                    if (_currentPath.value == sourceDir) refresh()
                }
            } catch (e: Exception) {
                Log.e(TAG, "$label failed; preserving cacheOutput=${cacheOutput?.absolutePath} size=${cacheOutput?.takeIf { it.exists() }?.length() ?: -1}", e)
                appendLog("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                logMediaEvent(entry, label, ConnectionLog.Status.FAILED, startTime, logBuffer.toString(), e.message)
                _error.value = "$label failed: ${e.message}"
                return@launch  // don't delete on failure — leave for inspection
            } finally {
                _loading.value = false
                _transferProgress.value = null
            }
            // Success-only cleanup
            cacheOutput?.takeIf { it.exists() }?.delete()
            if (finalOutput != null && finalOutput != cacheOutput) {
                finalOutput.takeIf { it.exists() }?.delete()
            }
        }
    }

    /** Probe a remote media file and surface the result via [mediaInfoState]. */
    fun loadMediaInfo(entry: SftpEntry) {
        _mediaInfoState.value = MediaInfoState.Loading(entry)
        viewModelScope.launch {
            try {
                val input = resolveStreamInput(entry)
                val result = withContext(Dispatchers.IO) {
                    ffmpegExecutor.probe(sh.haven.core.ffmpeg.ProbeCommand.fullInfo(input))
                }
                if (!result.success) {
                    _mediaInfoState.value = MediaInfoState.Failed(entry, "ffprobe exit ${result.exitCode}")
                    return@launch
                }
                val info = sh.haven.core.ffmpeg.ProbeCommand.parse(result.stdout)
                _mediaInfoState.value = MediaInfoState.Loaded(entry, info)
            } catch (e: Exception) {
                Log.e(TAG, "loadMediaInfo failed", e)
                _mediaInfoState.value = MediaInfoState.Failed(entry, e.message ?: "unknown error")
            }
        }
    }

    /** Lossless trim (-c copy) writing an output clip. */
    fun trimFile(
        entry: SftpEntry,
        startSec: Double,
        endSec: Double,
        outName: String,
        destination: ConvertDestination = ConvertDestination.SOURCE_FOLDER,
    ) {
        if (endSec <= startSec) {
            _error.value = "Trim end must be after start"
            return
        }
        val ext = outName.substringAfterLast('.', "mp4")
        val mime = when (ext.lowercase()) {
            "mp4", "m4a" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
        runMediaJob(
            entry = entry,
            outName = outName,
            outMimeType = mime,
            destination = destination,
            label = "\u2702 Trimming ${entry.name}",
            buildCommand = { input, output ->
                sh.haven.core.ffmpeg.TranscodeCommand.trim(input, output, startSec, endSec)
            },
        )
    }

    /** Audio-only extraction to the chosen codec/bitrate. */
    fun extractAudio(
        entry: SftpEntry,
        codec: String,
        bitrate: String,
        outName: String,
        destination: ConvertDestination = ConvertDestination.SOURCE_FOLDER,
    ) {
        val mime = when (codec) {
            "libmp3lame" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "libopus" -> "audio/opus"
            "flac" -> "audio/flac"
            "copy" -> "audio/octet-stream"
            else -> "audio/octet-stream"
        }
        runMediaJob(
            entry = entry,
            outName = outName,
            outMimeType = mime,
            destination = destination,
            label = "\u266B Extracting audio from ${entry.name}",
            buildCommand = { input, output ->
                sh.haven.core.ffmpeg.TranscodeCommand.extractAudio(input, output, codec, bitrate)
            },
        )
    }

    /**
     * Produce a contact-sheet PNG: samples frames evenly across the file's
     * duration (if known) or every [fallbackSampleEverySec] seconds otherwise,
     * tiles them into a [cols]x[rows] grid, then decodes the single-frame MP4
     * output to a Bitmap and compresses to PNG.
     */
    fun makeContactSheet(
        entry: SftpEntry,
        cols: Int,
        rows: Int,
        tileWidth: Int,
        tileHeight: Int,
        outName: String,
        fallbackSampleEverySec: Double = 10.0,
        destination: ConvertDestination = ConvertDestination.SOURCE_FOLDER,
    ) {
        runMediaJob(
            entry = entry,
            outName = outName,
            outMimeType = "image/png",
            destination = destination,
            label = "\u25A6 Building contact sheet",
            // Intermediate is an MP4 — ffmpeg picks the muxer from the
            // extension, so we must NOT write to a .png file directly.
            intermediateName = "contact_sheet_${System.currentTimeMillis()}.mp4",
            buildCommand = { input, output ->
                // Probe duration to pick a sampling interval that fits cols*rows
                // frames evenly across the file. Falls back to a fixed interval
                // if the probe returns 0 (live streams, broken headers).
                //
                // Tight minimum (0.04 s = 25 fps) so short clips still sample
                // enough frames to fill the tile grid. A 5-second clip with
                // 4x4=16 tiles needs sampleEverySec ≈ 0.31 s.
                val every = runCatching {
                    val r = ffmpegExecutor.probe(sh.haven.core.ffmpeg.ProbeCommand.durationOnly(input))
                    val dur = r.stdout.trim().toDoubleOrNull() ?: 0.0
                    val tiles = (cols * rows).coerceAtLeast(1)
                    if (dur > 0) (dur / tiles).coerceAtLeast(0.04) else fallbackSampleEverySec
                }.getOrDefault(fallbackSampleEverySec)
                Log.d(TAG, "contactSheet: cols=$cols rows=$rows every=${"%.3f".format(every)}s tile=${tileWidth}x$tileHeight")
                sh.haven.core.ffmpeg.TranscodeCommand.contactSheet(
                    input = input,
                    output = output,
                    sampleEverySec = every,
                    cols = cols,
                    rows = rows,
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                )
            },
            postProcess = { mp4 ->
                // Decode the 1-frame MP4 to a Bitmap and save as PNG.
                withContext(Dispatchers.IO) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(mp4.absolutePath)
                        val bitmap = retriever.getFrameAtTime(0)
                            ?: throw IllegalStateException("Failed to decode contact sheet frame")
                        val png = java.io.File(appContext.cacheDir, outName)
                        png.outputStream().use { out ->
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                                throw IllegalStateException("PNG compression failed")
                            }
                        }
                        bitmap.recycle()
                        png
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
            },
        )
    }

    /**
     * Stream every media file in [folderPath] as an HLS playlist. The in-app
     * HLS server queues the items and exposes a playlist sidebar in the web
     * player so the viewer can skip between them; each switch transparently
     * restarts ffmpeg on the chosen file.
     */
    fun streamFolder(folderPath: String) {
        Log.w(TAG, "streamFolder: $folderPath")
        if (_isSmbProfile.value) {
            _error.value = "Streaming is not supported for SMB yet"
            return
        }
        viewModelScope.launch {
            val logBuffer = StringBuilder()
            val startTime = System.currentTimeMillis()
            fun appendLog(line: String) {
                val elapsed = System.currentTimeMillis() - startTime
                logBuffer.append("+${elapsed}ms ").append(line).append('\n')
            }
            try {
                _loading.value = true
                val mediaEntries = _entries.value
                    .filter { !it.isDirectory && it.isMediaFile(mediaExtensionsSet.value) }
                    .sortedWith(compareBy(NATURAL_SORT_COMPARATOR) { it.name })
                if (mediaEntries.isEmpty()) {
                    _error.value = "No media files in this folder"
                    return@launch
                }

                val items = mediaEntries.map { entry ->
                    sh.haven.core.ffmpeg.HlsStreamServer.PlaylistItem(
                        title = entry.name,
                        input = resolveStreamInput(entry),
                    )
                }
                appendLog("streamFolder: ${items.size} items in $folderPath")
                items.forEachIndexed { i, it -> appendLog("  [$i] ${it.title} → ${it.input}") }
                hlsStreamServer.onStderr = { line -> appendLog("ffmpeg: $line") }
                val port = hlsStreamServer.startPlaylist(items)
                val ip = withContext(Dispatchers.IO) {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.filter { it.isUp && !it.isLoopback }
                        ?.flatMap { it.inetAddresses.toList() }
                        ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        ?.hostAddress ?: "127.0.0.1"
                }
                val url = "http://$ip:$port/"
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Haven stream URL", url),
                )
                _message.value = "Streaming ${items.size} files on $url (copied to clipboard)"
                appendLog("streaming at $url")
                // Synthesize an entry for the folder so the event lands under
                // the right profile; entry.name is the folder basename.
                val folderName = folderPath.trimEnd('/').substringAfterLast('/')
                    .ifEmpty { folderPath }
                val syntheticEntry = SftpEntry(
                    name = folderName,
                    path = folderPath,
                    isDirectory = true,
                    size = 0,
                    modifiedTime = 0,
                    permissions = "",
                )
                logMediaEvent(syntheticEntry, "\u25B6 Stream playlist", ConnectionLog.Status.CONNECTED, startTime, logBuffer.toString(), "$url (${items.size} files)")
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "streamFolder failed", e)
                appendLog("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                val folderName = folderPath.trimEnd('/').substringAfterLast('/').ifEmpty { folderPath }
                val syntheticEntry = SftpEntry(
                    name = folderName, path = folderPath, isDirectory = true,
                    size = 0, modifiedTime = 0, permissions = "",
                )
                logMediaEvent(syntheticEntry, "\u25B6 Stream playlist", ConnectionLog.Status.FAILED, startTime, logBuffer.toString(), e.message)
                _error.value = "Stream folder failed: ${e.message}"
            } finally {
                _loading.value = false
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
    /**
     * Play a media file in the device's browser via loopback-only HLS.
     * The server binds to 127.0.0.1 so it's not reachable from the network.
     */
    fun playInBrowser(entry: SftpEntry) = streamFile(entry, localOnly = true)

    fun streamFile(entry: SftpEntry, localOnly: Boolean = false) {
        Log.w(TAG, "streamFile: ${entry.path} localOnly=$localOnly isLocal=${_isLocalProfile.value} isRclone=${_isRcloneProfile.value} isSmb=${_isSmbProfile.value} ffmpegAvail=${ffmpegExecutor.isAvailable()}")
        if (_isSmbProfile.value) {
            _error.value = "Streaming is not supported for SMB yet"
            return
        }
        viewModelScope.launch {
            val logBuffer = StringBuilder()
            val startTime = System.currentTimeMillis()
            fun appendLog(line: String) {
                val elapsed = System.currentTimeMillis() - startTime
                logBuffer.append("+${elapsed}ms ").append(line).append('\n')
            }
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
                Log.w(TAG, "Starting HLS stream for $streamInput (localOnly=$localOnly)")
                appendLog("streamFile: input=$streamInput localOnly=$localOnly")
                hlsStreamServer.onStderr = { line -> appendLog("ffmpeg: $line") }
                val port = hlsStreamServer.startFile(streamInput, localOnly = localOnly)

                val url: String
                if (localOnly) {
                    // Wait for ffmpeg to produce the m3u8 before handing the
                    // URL to Chrome — otherwise the player fetches a 404 on
                    // the manifest and gives up with MEDIA_ERR_SRC_NOT_SUPPORTED.
                    val m3u8 = java.io.File(appContext.cacheDir, "hls_stream/stream.m3u8")
                    withContext(Dispatchers.IO) {
                        var waited = 0
                        while (!m3u8.exists() && waited < 10_000) {
                            Thread.sleep(100)
                            waited += 100
                        }
                    }
                    url = "http://127.0.0.1:$port/"
                    _message.value = "Playing in browser (loopback only)"
                } else {
                    val ip = withContext(Dispatchers.IO) {
                        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                            ?.filter { it.isUp && !it.isLoopback }
                            ?.flatMap { it.inetAddresses.toList() }
                            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                            ?.hostAddress ?: "127.0.0.1"
                    }
                    url = "http://$ip:$port/"
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Haven stream URL", url)
                    )
                    _message.value = "Streaming on $url (copied to clipboard)"
                }
                appendLog("streaming at $url")
                logMediaEvent(entry, "\u25B6 Stream", ConnectionLog.Status.CONNECTED, startTime, logBuffer.toString(), url)
                // Open the shareable URL in the browser — the address bar will
                // show it, so the user can copy/share from there too.
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Stream failed", e)
                appendLog("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                logMediaEvent(entry, "\u25B6 Stream", ConnectionLog.Status.FAILED, startTime, logBuffer.toString(), e.message)
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
                            val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                            transport.upload(input, fileSize, destPath) { transferred, total ->
                                _transferProgress.value = TransferProgress(fileName, total, transferred)
                            }
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
                if (_isLocalProfile.value) {
                    withContext(Dispatchers.IO) {
                        val f = java.io.File(entry.path)
                        val ok = if (entry.isDirectory) f.deleteRecursively() else f.delete()
                        if (!ok) throw java.io.IOException("Could not delete ${entry.path}")
                    }
                } else if (_isRcloneProfile.value) {
                    withContext(Dispatchers.IO) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        if (entry.isDirectory) {
                            rcloneClient.deleteDir(remote, entry.path)
                        } else {
                            rcloneClient.deleteFile(remote, entry.path)
                        }
                    }
                } else if (_isSmbProfile.value) {
                    withContext(Dispatchers.IO) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        client.delete(entry.path, entry.isDirectory)
                    }
                } else {
                    val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                    transport.delete(entry.path, entry.isDirectory)
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
                if (_isLocalProfile.value) {
                    withContext(Dispatchers.IO) {
                        val ok = java.io.File(entry.path).renameTo(java.io.File(newPath))
                        if (!ok) throw java.io.IOException("Could not rename ${entry.path}")
                    }
                } else if (_isRcloneProfile.value) {
                    withContext(Dispatchers.IO) {
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
                    }
                } else if (_isSmbProfile.value) {
                    withContext(Dispatchers.IO) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        client.rename(entry.path, newPath)
                    }
                } else {
                    val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                    transport.rename(entry.path, newPath)
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

    // ===== Multi-select =====

    /**
     * Paths (absolute, matching [SftpEntry.path]) currently selected in the
     * file list. Non-empty means the UI is in selection mode — the top bar
     * switches to a contextual action bar and taps toggle selection instead
     * of navigating / opening.
     */
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    /**
     * Target for the permissions dialog. Non-null opens the dialog. When
     * a single entry is supplied the dialog edits that entry's mode and
     * writes it back via [chmodEntry]; when [batch] is true it applies the
     * mode to the full selection via [chmodSelected].
     */
    data class ChmodRequest(val entry: SftpEntry?, val currentMode: Int, val batch: Boolean)

    private val _chmodRequest = MutableStateFlow<ChmodRequest?>(null)
    val chmodRequest: StateFlow<ChmodRequest?> = _chmodRequest.asStateFlow()

    /** Open the permissions dialog for a single entry. */
    fun openChmodDialog(entry: SftpEntry) {
        val mode = permissionsStringToMode(entry.permissions) ?: MODE_0644
        _chmodRequest.value = ChmodRequest(entry = entry, currentMode = mode, batch = false)
    }

    /**
     * Open the permissions dialog for the current multi-selection. The
     * seed mode is the first selected entry's mode if unambiguous, else
     * 0644 as a neutral default.
     */
    fun openChmodDialogForSelection() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        val modes = targets.mapNotNull { permissionsStringToMode(it.permissions) }.toSet()
        val seed = if (modes.size == 1) modes.single() else MODE_0644
        _chmodRequest.value = ChmodRequest(entry = null, currentMode = seed, batch = true)
    }

    fun dismissChmodDialog() { _chmodRequest.value = null }

    /**
     * Target for the chown dialog. When [batch] is true, applies the
     * entered `user` / `user:group` string to every selected entry;
     * otherwise just to [entry]. [currentOwner] is a pre-fill for the
     * text field — parsed from the first 8 chars of the permissions
     * string is too fragile, so we leave it blank unless we can parse
     * it cheaply.
     */
    data class ChownRequest(val entry: SftpEntry?, val currentOwner: String, val batch: Boolean)

    private val _chownRequest = MutableStateFlow<ChownRequest?>(null)
    val chownRequest: StateFlow<ChownRequest?> = _chownRequest.asStateFlow()

    fun openChownDialog(entry: SftpEntry) {
        val seed = formatOwnerGroup(entry.owner, entry.group)
        _chownRequest.value = ChownRequest(entry = entry, currentOwner = seed, batch = false)
        // Over the SFTP subsystem JSch only exposes numeric UID/GID. Resolve
        // to human-readable names asynchronously via a remote `ls -ld`
        // so the dialog first appears with "1000:1000" and then swaps in
        // "ian:ian" once the lookup returns.
        if (looksNumeric(seed) && !_isLocalProfile.value && !_isRcloneProfile.value && !_isSmbProfile.value) {
            viewModelScope.launch {
                resolveOwnerName(entry.path)?.let { resolved ->
                    if (_chownRequest.value?.entry == entry) {
                        _chownRequest.value = ChownRequest(entry = entry, currentOwner = resolved, batch = false)
                    }
                }
            }
        }
    }

    fun openChownDialogForSelection() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        // Pre-fill only if the whole selection shares the same owner:group,
        // otherwise leave blank so the user has to type it explicitly.
        val owners = targets.map { formatOwnerGroup(it.owner, it.group) }.toSet()
        val seed = if (owners.size == 1) owners.single() else ""
        _chownRequest.value = ChownRequest(entry = null, currentOwner = seed, batch = true)
        if (owners.size == 1 && looksNumeric(seed)) {
            viewModelScope.launch {
                resolveOwnerName(targets.first().path)?.let { resolved ->
                    if (_chownRequest.value?.batch == true) {
                        _chownRequest.value = ChownRequest(entry = null, currentOwner = resolved, batch = true)
                    }
                }
            }
        }
    }

    private fun looksNumeric(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isDigit() || it == ':' }

    /**
     * Portable remote lookup: `ls -ld -- PATH` columns 3 and 4 are user
     * and group. Works on GNU, BSD, busybox, macOS. Returns null if the
     * command fails or the output doesn't parse — in that case the
     * dialog keeps its numeric placeholder.
     */
    private suspend fun resolveOwnerName(path: String): String? {
        val profileId = _activeProfileId.value ?: return null
        val ssh = sessionManager.getSshClientForProfile(profileId) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val cmd = "LC_ALL=C ls -ld -- '${path.replace("'", "'\\''")}'"
                val r = ssh.execCommand(cmd)
                if (r.exitStatus != 0) return@withContext null
                val parts = r.stdout.trim().split(Regex("\\s+"))
                if (parts.size < 4) null else "${parts[2]}:${parts[3]}"
            } catch (e: Exception) {
                Log.w(TAG, "resolveOwnerName failed for $path: ${e.message}")
                null
            }
        }
    }

    private fun formatOwnerGroup(owner: String, group: String): String = when {
        owner.isEmpty() && group.isEmpty() -> ""
        group.isEmpty() -> owner
        else -> "$owner:$group"
    }

    fun dismissChownDialog() { _chownRequest.value = null }

    /** True while at least one entry is selected. */
    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleSelection(entry: SftpEntry) {
        _selectedPaths.value = _selectedPaths.value.toMutableSet().apply {
            if (contains(entry.path)) remove(entry.path) else add(entry.path)
        }
    }

    fun selectAll() {
        _selectedPaths.value = _entries.value.map { it.path }.toSet()
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    /** Resolve current selection to the matching [SftpEntry] objects, preserving list order. */
    private fun selectedEntries(): List<SftpEntry> {
        val selected = _selectedPaths.value
        return _entries.value.filter { it.path in selected }
    }

    /**
     * Delete every selected entry. Keeps going on individual failures and
     * reports aggregate success/failure counts through [_message] / [_error]
     * at the end. Clears the selection and refreshes the listing on
     * completion.
     */
    fun deleteSelected() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            var deleted = 0
            val failures = mutableListOf<String>()
            for (entry in targets) {
                try {
                    deleteOne(entry)
                    deleted++
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed for ${entry.path}", e)
                    failures.add("${entry.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            clearSelection()
            if (failures.isEmpty()) {
                _message.value = "Deleted $deleted item${if (deleted != 1) "s" else ""}"
            } else {
                _error.value = "Deleted $deleted, failed ${failures.size}: ${failures.first()}"
            }
            _loading.value = false
            refresh()
        }
    }

    /**
     * Chmod every selected entry to [mode]. Skips backends that do not
     * carry POSIX permissions (SMB, rclone) and reports how many were
     * applied vs skipped. Directories are chmod'd non-recursively —
     * entries inside a selected directory keep their existing modes.
     */
    fun chmodSelected(mode: Int) {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            var applied = 0
            val failures = mutableListOf<String>()
            for (entry in targets) {
                try {
                    chmodOne(entry, mode)
                    applied++
                } catch (e: UnsupportedOperationException) {
                    failures.add("${entry.name}: permissions not supported on this backend")
                    break // same answer for every entry on this backend
                } catch (e: Exception) {
                    Log.e(TAG, "chmod failed for ${entry.path}", e)
                    failures.add("${entry.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            clearSelection()
            if (failures.isEmpty()) {
                _message.value = "Permissions set on $applied item${if (applied != 1) "s" else ""}"
            } else {
                _error.value = "Applied $applied, failed ${failures.size}: ${failures.first()}"
            }
            _loading.value = false
            refresh()
        }
    }

    fun chownSelected(owner: String) {
        val targets = selectedEntries()
        if (targets.isEmpty() || owner.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            var applied = 0
            val failures = mutableListOf<String>()
            for (entry in targets) {
                try {
                    chownOne(entry, owner)
                    applied++
                } catch (e: UnsupportedOperationException) {
                    failures.add("${entry.name}: ownership not supported on this backend")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "chown failed for ${entry.path}", e)
                    failures.add("${entry.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            clearSelection()
            if (failures.isEmpty()) {
                _message.value = "Owner set on $applied item${if (applied != 1) "s" else ""}"
            } else {
                _error.value = "Applied $applied, failed ${failures.size}: ${failures.first()}"
            }
            _loading.value = false
            refresh()
        }
    }

    /** chown a single entry via shell `chown user:group -- path` on the remote. */
    fun chownEntry(entry: SftpEntry, owner: String) {
        if (owner.isBlank()) return
        viewModelScope.launch {
            try {
                _loading.value = true
                chownOne(entry, owner)
                _message.value = "Owner updated on ${entry.name}"
                refresh()
            } catch (e: UnsupportedOperationException) {
                _error.value = "Ownership not supported on this backend"
            } catch (e: Exception) {
                Log.e(TAG, "chown failed", e)
                _error.value = "chown failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    /** chmod a single entry (used by both single-entry and batch paths). */
    fun chmodEntry(entry: SftpEntry, mode: Int) {
        viewModelScope.launch {
            try {
                _loading.value = true
                chmodOne(entry, mode)
                _message.value = "Permissions updated on ${entry.name}"
                refresh()
            } catch (e: UnsupportedOperationException) {
                _error.value = "Permissions not supported on this backend"
            } catch (e: Exception) {
                Log.e(TAG, "chmod failed", e)
                _error.value = "chmod failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun deleteOne(entry: SftpEntry) {
        if (_isLocalProfile.value) {
            withContext(Dispatchers.IO) {
                val f = java.io.File(entry.path)
                val ok = if (entry.isDirectory) f.deleteRecursively() else f.delete()
                if (!ok) throw java.io.IOException("Could not delete ${entry.path}")
            }
        } else if (_isRcloneProfile.value) {
            withContext(Dispatchers.IO) {
                val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                if (entry.isDirectory) rcloneClient.deleteDir(remote, entry.path)
                else rcloneClient.deleteFile(remote, entry.path)
            }
        } else if (_isSmbProfile.value) {
            withContext(Dispatchers.IO) {
                val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                client.delete(entry.path, entry.isDirectory)
            }
        } else {
            val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
            transport.delete(entry.path, entry.isDirectory)
        }
    }

    private suspend fun chmodOne(entry: SftpEntry, mode: Int) {
        when {
            _isLocalProfile.value -> withContext(Dispatchers.IO) {
                android.system.Os.chmod(entry.path, mode)
            }
            _isRcloneProfile.value ->
                throw UnsupportedOperationException("chmod not supported on rclone remotes")
            _isSmbProfile.value ->
                throw UnsupportedOperationException("chmod not supported on SMB shares")
            else -> {
                val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                transport.chmod(entry.path, mode)
            }
        }
    }

    /** Whether the active backend understands POSIX permissions. */
    fun supportsPermissions(): Boolean =
        _isLocalProfile.value || (!_isRcloneProfile.value && !_isSmbProfile.value)

    /**
     * Whether the active backend supports changing ownership. Same set
     * as [supportsPermissions] minus local files — an unrooted Android
     * app can't chown outside its own UID.
     */
    fun supportsOwnership(): Boolean =
        !_isLocalProfile.value && !_isRcloneProfile.value && !_isSmbProfile.value

    private suspend fun chownOne(entry: SftpEntry, owner: String) {
        when {
            _isLocalProfile.value ->
                throw UnsupportedOperationException("chown not supported on local files")
            _isRcloneProfile.value ->
                throw UnsupportedOperationException("chown not supported on rclone remotes")
            _isSmbProfile.value ->
                throw UnsupportedOperationException("chown not supported on SMB shares")
            else -> {
                val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                transport.chown(entry.path, owner)
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
                if (_isLocalProfile.value) {
                    withContext(Dispatchers.IO) {
                        if (!java.io.File(fullPath).mkdirs() && !java.io.File(fullPath).isDirectory) {
                            throw java.io.IOException("Could not create $fullPath")
                        }
                    }
                } else if (_isRcloneProfile.value) {
                    withContext(Dispatchers.IO) {
                        val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                        rcloneClient.mkdir(remote, fullPath)
                    }
                } else if (_isSmbProfile.value) {
                    withContext(Dispatchers.IO) {
                        val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                        client.mkdir(fullPath)
                    }
                } else {
                    val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                    transport.mkdir(fullPath)
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
    /** Pre-walked total number of files in the paste selection, or 0 when unknown. */
    private val pasteTotalFiles = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Called as each file transfer *starts*, before bytes move. Shows
     * "Uploading N of M: filename" when we know the total (pre-walked),
     * otherwise "Uploading N: filename". Replaces the old post-completion
     * progress update so the user sees live movement through the batch
     * instead of a long "Preparing..." on the first (often largest) file.
     */
    private fun beforePasteFile(fileName: String, fileSize: Long = 0, resumeFrom: Long = 0) {
        val current = pasteFileCount.incrementAndGet()
        val total = pasteTotalFiles.get()
        val label = if (total > 0) {
            "Uploading $current of $total: $fileName"
        } else {
            "Uploading $current: $fileName"
        }
        _transferProgress.value = TransferProgress(label, fileSize, resumeFrom)
    }

    /**
     * Probe the destination backend for an existing file at [destPath].
     *
     * Returns the size in bytes if a regular file exists, null if the path is
     * absent, a directory, or the probe itself failed (treat as "not known to
     * exist" so we default to the existing silent-overwrite behaviour rather
     * than blocking the paste on a flaky backend).
     */
    private fun probeDestFile(
        destType: BackendType,
        destProfileId: String,
        destRemote: String?,
        destPath: String,
    ): Long? = try {
        when (destType) {
            BackendType.LOCAL -> {
                val f = java.io.File(destPath)
                if (f.exists() && f.isFile) f.length() else null
            }
            BackendType.SFTP -> {
                val channel = getOrOpenChannel(destProfileId) ?: return null
                try {
                    val attrs = channel.stat(destPath)
                    if (attrs.isDir) null else attrs.size
                } catch (_: Exception) { null }
            }
            BackendType.SMB -> {
                val client = activeSmbClient ?: return null
                val parent = destPath.substringBeforeLast('/', "")
                val name = destPath.substringAfterLast('/')
                if (parent.isEmpty()) null
                else client.listDirectory(parent)
                    .firstOrNull { it.name == name && !it.isDirectory }
                    ?.size
            }
            BackendType.RCLONE -> {
                val remote = destRemote ?: return null
                val parent = destPath.substringBeforeLast('/', "")
                val name = destPath.substringAfterLast('/')
                if (parent.isEmpty()) null
                else rcloneClient.listDirectory(remote, parent)
                    .firstOrNull { it.name == name && !it.isDir }
                    ?.size
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "probeDestFile failed for $destPath: ${e.message}")
        null
    }

    /**
     * Generate a Windows-style unique destination path by appending `(1)`, `(2)`
     * etc. before any extension, looping until [probeDestFile] returns null.
     * Falls back to the original path after 999 attempts (which would also be
     * a sign something else is wrong).
     */
    private fun findUniqueName(
        destType: BackendType,
        destProfileId: String,
        destRemote: String?,
        destPath: String,
    ): String {
        val lastSlash = destPath.lastIndexOf('/')
        val dir = if (lastSlash >= 0) destPath.substring(0, lastSlash) else ""
        val name = if (lastSlash >= 0) destPath.substring(lastSlash + 1) else destPath
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..999) {
            val candidate = if (dir.isEmpty()) "$stem ($i)$ext" else "$dir/$stem ($i)$ext"
            if (probeDestFile(destType, destProfileId, destRemote, candidate) == null) return candidate
        }
        return destPath
    }

    /**
     * If [destPath] already exists, suspend until the user picks a [ConflictAction]
     * (unless [batchResolution] is already set by "Apply to all"). Returns null
     * when the destination is free and the caller should proceed as normal.
     *
     * Must be called from a coroutine — suspends on the UI via a
     * [CompletableDeferred] completed by the dialog.
     */
    private suspend fun resolveConflictIfExists(
        entry: SftpEntry,
        destType: BackendType,
        destProfileId: String,
        destRemote: String?,
        destPath: String,
    ): Pair<ConflictAction, Long>? {
        val destSize = withContext(Dispatchers.IO) {
            probeDestFile(destType, destProfileId, destRemote, destPath)
        } ?: return null

        batchResolution?.let { return it to destSize }

        val deferred = CompletableDeferred<Pair<ConflictAction, Boolean>>()
        _conflictPrompt.value = ConflictPrompt(
            fileName = entry.name,
            sourceSize = entry.size,
            destSize = destSize,
            canResume = (destType == BackendType.SFTP || destType == BackendType.LOCAL) &&
                destSize in 1 until entry.size,
            onChoice = { action, applyToAll -> deferred.complete(action to applyToAll) },
        )
        val (choice, applyToAll) = deferred.await()
        _conflictPrompt.value = null
        if (applyToAll) batchResolution = choice
        return choice to destSize
    }

    private fun acquirePasteWakeLock() {
        if (pasteWakeLock?.isHeld == true) return
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pasteWakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "sh.haven.app:paste",
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 1 hour safety cap; released in finally regardless
            }
        } catch (e: SecurityException) {
            // WAKE_LOCK permission missing (pre-v5.24.11 builds didn't declare
            // it in the manifest). Paste should still work — the wake lock is
            // a stability-on-device nice-to-have, not a correctness dependency.
            Log.w(TAG, "Could not acquire paste wake lock: ${e.message}")
            pasteWakeLock = null
        }
    }

    private fun releasePasteWakeLock() {
        try {
            pasteWakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Releasing paste wake lock failed: ${e.message}")
        }
        pasteWakeLock = null
    }

    /**
     * Pre-walk the clipboard entries to count total leaf files. Best-effort:
     * any listing error yields a partial count (or zero for that subtree),
     * which is fine — the UI falls back to "Uploading N:" without a total.
     */
    private fun countPasteFiles(cb: FileClipboard): Int = cb.entries.sumOf { entry ->
        try {
            countLeafFiles(cb, entry)
        } catch (e: Exception) {
            Log.w(TAG, "Pre-walk failed for ${entry.path}: ${e.message}")
            0
        }
    }

    private fun countLeafFiles(cb: FileClipboard, entry: SftpEntry): Int {
        if (!entry.isDirectory) return 1
        val children = when (cb.sourceBackendType) {
            BackendType.LOCAL -> {
                java.io.File(entry.path).listFiles()?.map {
                    SftpEntry(it.name, it.absolutePath, it.isDirectory, 0, 0, "")
                } ?: emptyList()
            }
            BackendType.RCLONE -> {
                rcloneClient.listDirectory(cb.sourceRemoteName!!, entry.path).map { rc ->
                    SftpEntry(rc.name, "${entry.path.trimEnd('/')}/${rc.name}", rc.isDir, 0, 0, "")
                }
            }
            BackendType.SFTP -> {
                val channel = cb.sourceSftpChannel
                    ?: sessionManager.openSftpForProfile(cb.sourceProfileId) ?: return 0
                val results = mutableListOf<SftpEntry>()
                channel.ls(entry.path) { lsEntry ->
                    val name = lsEntry.filename
                    if (name != "." && name != "..") {
                        results.add(SftpEntry(name, "${entry.path.trimEnd('/')}/$name", lsEntry.attrs.isDir, 0, 0, ""))
                    }
                    com.jcraft.jsch.ChannelSftp.LsEntrySelector.CONTINUE
                }
                results
            }
            BackendType.SMB -> {
                val client = cb.sourceSmbClient
                    ?: smbSessionManager.getClientForProfile(cb.sourceProfileId) ?: return 0
                client.listDirectory(entry.path).map { smb ->
                    SftpEntry(smb.name, smb.path, smb.isDirectory, 0, 0, "")
                }
            }
        }
        return children.sumOf { countLeafFiles(cb, it) }
    }


    /** One flattened leaf-file operation waiting to be persisted to the paste queue. */
    private data class PasteLeaf(
        val sourcePath: String,
        val sourceName: String,
        val size: Long,
        val destPath: String,
        val isTopLevel: Boolean,
    )

    /**
     * Walk a clipboard into a flat list of leaf-file paste operations, expanding
     * directories in order. The returned [PasteLeaf.destPath] bakes in any
     * directory prefix so the executor can treat each leaf atomically — it
     * only needs to mkdir-p the parent before the copy.
     *
     * For the rclone-to-rclone case the old server-side copyFile path is still
     * the fastest option (no bytes flow through the phone), so we return an
     * empty list and let the caller fall through to the non-queue path.
     */
    private fun enumerateLeaves(
        cb: FileClipboard,
        destType: BackendType,
        destRootPath: String,
    ): List<PasteLeaf> {
        val leaves = mutableListOf<PasteLeaf>()
        for (entry in cb.entries) {
            val destTop = destRootPath.trimEnd('/') + "/" + entry.name
            walkEntry(cb, entry, destTop, isTopLevel = true, leaves)
        }
        return leaves
    }

    private fun walkEntry(
        cb: FileClipboard,
        entry: SftpEntry,
        destPath: String,
        isTopLevel: Boolean,
        out: MutableList<PasteLeaf>,
    ) {
        if (!entry.isDirectory) {
            out.add(PasteLeaf(entry.path, entry.name, entry.size, destPath, isTopLevel))
            return
        }
        val children: List<SftpEntry> = when (cb.sourceBackendType) {
            BackendType.LOCAL -> {
                java.io.File(entry.path).listFiles()?.map { f ->
                    SftpEntry(f.name, f.absolutePath, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified() / 1000, "")
                } ?: emptyList()
            }
            BackendType.RCLONE -> {
                rcloneClient.listDirectory(cb.sourceRemoteName!!, entry.path).map { rc ->
                    SftpEntry(rc.name, "${entry.path.trimEnd('/')}/${rc.name}", rc.isDir, rc.size, 0, "")
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
        for (child in children) {
            val childDest = destPath.trimEnd('/') + "/" + child.name
            walkEntry(cb, child, childDest, isTopLevel = false, out)
        }
    }

    /**
     * mkdir-p equivalent for the destination backend. Safe to call for a path
     * that already exists — each layer ignores "already exists" errors.
     */
    private fun ensureDestParent(
        destType: BackendType,
        destProfileId: String,
        destRemote: String?,
        destPath: String,
    ) {
        val parent = destPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it != "/" }
            ?: return
        try {
            when (destType) {
                BackendType.LOCAL -> java.io.File(parent).mkdirs()
                BackendType.SFTP -> {
                    val channel = getOrOpenChannel(destProfileId) ?: return
                    // Walk parents shallowest-first, ignoring "already exists".
                    val parts = parent.split("/").filter { it.isNotEmpty() }
                    val isAbsolute = parent.startsWith("/")
                    var acc = if (isAbsolute) "" else "."
                    for (p in parts) {
                        acc = if (acc.isEmpty()) "/$p" else "$acc/$p"
                        try { channel.mkdir(acc) } catch (_: Exception) { /* exists or permission */ }
                    }
                }
                BackendType.SMB -> {
                    activeSmbClient?.mkdir(parent)
                }
                BackendType.RCLONE -> destRemote?.let { rcloneClient.mkdir(it, parent) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureDestParent failed for $parent: ${e.message}")
        }
    }

    /**
     * Delete a single leaf file at the source after a cut+paste has copied
     * it to the destination. Directories left behind by cut operations are
     * not rmdir'd — the queue works on leaves only and a future pass can
     * plumb per-batch directory cleanup.
     */
    private fun deleteSourceLeaf(
        sourceType: BackendType,
        sourceProfileId: String,
        sourceRemoteName: String?,
        sourcePath: String,
    ) {
        try {
            when (sourceType) {
                BackendType.LOCAL -> java.io.File(sourcePath).delete()
                BackendType.RCLONE -> sourceRemoteName?.let { rcloneClient.deleteFile(it, sourcePath) }
                BackendType.SFTP -> {
                    val channel = sessionManager.openSftpForProfile(sourceProfileId)
                    channel?.rm(sourcePath)
                }
                BackendType.SMB -> {
                    val client = smbSessionManager.getClientForProfile(sourceProfileId)
                    client?.delete(sourcePath, isDirectory = false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteSourceLeaf failed for $sourcePath: ${e.message}")
        }
    }

    fun pasteFromClipboard() {
        val cb = _clipboard.value ?: return
        val destProfileId = _activeProfileId.value ?: return
        val destPath = _currentPath.value
        Log.d(TAG, "pasteFromClipboard: ${cb.entries.size} entries from ${cb.sourceBackendType}(${cb.sourceProfileId}) " +
            "to dest=$destProfileId, destPath=$destPath, isRclone=${_isRcloneProfile.value}, isSmb=${_isSmbProfile.value}")

        val destType = when {
            _isLocalProfile.value -> BackendType.LOCAL
            _isRcloneProfile.value -> BackendType.RCLONE
            _isSmbProfile.value -> BackendType.SMB
            else -> BackendType.SFTP
        }
        val destRemote = activeRcloneRemote

        // Same-remote rclone: stays on the server-side copyFile fast path
        // (no bytes flow through the phone, so there's nothing meaningful to
        // resume and queue persistence adds latency for free).
        if (cb.sourceBackendType == BackendType.RCLONE && destType == BackendType.RCLONE) {
            viewModelScope.launch { pasteFromClipboardRcloneToRclone(cb, destProfileId, destRemote, destPath) }
            return
        }

        viewModelScope.launch {
            try {
                _loading.value = true
                pasteInProgress.set(true)
                pasteFileCount.set(0)
                pasteTotalFiles.set(0)
                batchResolution = null
                acquirePasteWakeLock()
                _transferProgress.value = TransferProgress("Scanning selection…", 0, 0)

                val leaves = withContext(Dispatchers.IO) {
                    enumerateLeaves(cb, destType, destPath)
                }

                // Apply conflict resolution to top-level leaves (anything a
                // user directly put on the clipboard). Deeper descendants
                // inherit the batch-resolution if set, otherwise default to
                // OVERWRITE — matching the old crossCopyDir behaviour.
                val resolved = mutableListOf<PasteLeaf>()
                val actions = mutableListOf<ConflictAction>()
                val resumeBytes = mutableListOf<Long>()
                var skipped = 0
                for (leaf in leaves) {
                    if (!leaf.isTopLevel) {
                        resolved.add(leaf)
                        actions.add(batchResolution ?: ConflictAction.OVERWRITE)
                        resumeBytes.add(0L)
                        continue
                    }
                    val virtualEntry = SftpEntry(leaf.sourceName, leaf.sourcePath, false, leaf.size, 0, "")
                    val check = resolveConflictIfExists(virtualEntry, destType, destProfileId, destRemote, leaf.destPath)
                    if (check == null) {
                        resolved.add(leaf)
                        actions.add(ConflictAction.OVERWRITE)
                        resumeBytes.add(0L)
                        continue
                    }
                    val (action, destSize) = check
                    when (action) {
                        ConflictAction.SKIP -> skipped++
                        ConflictAction.RENAME -> {
                            val newPath = withContext(Dispatchers.IO) {
                                findUniqueName(destType, destProfileId, destRemote, leaf.destPath)
                            }
                            resolved.add(leaf.copy(destPath = newPath))
                            actions.add(ConflictAction.RENAME)
                            resumeBytes.add(0L)
                        }
                        ConflictAction.RESUME -> {
                            resolved.add(leaf)
                            actions.add(ConflictAction.RESUME)
                            resumeBytes.add(destSize)
                        }
                        ConflictAction.OVERWRITE -> {
                            resolved.add(leaf)
                            actions.add(ConflictAction.OVERWRITE)
                            resumeBytes.add(0L)
                        }
                    }
                }

                val rows = resolved.mapIndexed { i, leaf ->
                    sh.haven.core.data.db.entities.PasteQueueEntry(
                        indexInBatch = i,
                        sourceBackendType = cb.sourceBackendType.name,
                        sourceProfileId = cb.sourceProfileId,
                        sourceRemoteName = cb.sourceRemoteName,
                        sourcePath = leaf.sourcePath,
                        sourceName = leaf.sourceName,
                        sourceSize = leaf.size,
                        destBackendType = destType.name,
                        destProfileId = destProfileId,
                        destRemote = destRemote,
                        destPath = leaf.destPath,
                        isCut = cb.isCut,
                        conflictAction = actions[i].name,
                        bytesTransferred = resumeBytes[i],
                    )
                }

                withContext(Dispatchers.IO) {
                    pasteQueueDao.clear()
                    pasteQueueDao.insertAll(rows)
                }
                _clipboard.value = null

                pasteTotalFiles.set(rows.size)
                executePasteQueue()

                val copied = pasteFileCount.get()
                val stillPending = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                }
                val verb = if (cb.isCut) "Moved" else "Copied"
                _message.value = when {
                    stillPending > 0 -> "$verb $copied — $stillPending still pending (Resume to retry)"
                    skipped > 0 -> "$verb $copied files — skipped $skipped"
                    else -> "$verb $copied files"
                }
                if (stillPending == 0) {
                    withContext(Dispatchers.IO) { pasteQueueDao.clear() }
                }
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Paste failed", e)
                _error.value = "Paste failed: ${e.message}"
            } finally {
                pasteInProgress.set(false)
                _loading.value = false
                _transferProgress.value = null
                _conflictPrompt.value = null
                batchResolution = null
                releasePasteWakeLock()
            }
        }
    }

    /** Same-remote rclone paste — unchanged server-side copy path. */
    private suspend fun pasteFromClipboardRcloneToRclone(
        cb: FileClipboard,
        destProfileId: String,
        destRemote: String?,
        destPath: String,
    ) {
        try {
            _loading.value = true
            pasteInProgress.set(true)
            pasteFileCount.set(0)
            pasteTotalFiles.set(0)
            acquirePasteWakeLock()
            val total = withContext(Dispatchers.IO) { countPasteFiles(cb) }
            pasteTotalFiles.set(total)
            _transferProgress.value = TransferProgress(
                if (total > 0) "Uploading 0 of $total…" else "Uploading…", 0, 0,
            )
            for (entry in cb.entries) {
                val destEntryPath = destPath.trimEnd('/') + "/" + entry.name
                withContext(Dispatchers.IO) {
                    val srcRemote = cb.sourceRemoteName ?: throw IllegalStateException("No source remote")
                    val dstRemote = destRemote ?: throw IllegalStateException("No dest remote")
                    if (entry.isDirectory) {
                        rcloneClient.mkdir(dstRemote, destEntryPath)
                        copyRcloneDir(srcRemote, entry.path, dstRemote, destEntryPath)
                    } else {
                        beforePasteFile(entry.name, entry.size, 0)
                        rcloneClient.copyFile(srcRemote, entry.path, dstRemote, destEntryPath)
                    }
                }
                if (cb.isCut) withContext(Dispatchers.IO) { deleteSourceEntry(cb, entry) }
            }
            _clipboard.value = null
            _message.value = "${if (cb.isCut) "Moved" else "Copied"} ${pasteFileCount.get()} files"
            refresh()
        } catch (e: Exception) {
            Log.e(TAG, "rclone→rclone paste failed", e)
            _error.value = "Paste failed: ${e.message}"
        } finally {
            pasteInProgress.set(false)
            _loading.value = false
            _transferProgress.value = null
            releasePasteWakeLock()
        }
    }

    /**
     * Drive the persisted queue end-to-end. Called from both the initial
     * paste and [resumePasteQueue]. Continues across per-row failures — a
     * single flaky file doesn't block the rest of the batch.
     */
    private suspend fun executePasteQueue() {
        while (true) {
            val row = withContext(Dispatchers.IO) {
                pasteQueueDao.getByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING).firstOrNull()
            } ?: break
            withContext(Dispatchers.IO) { executeQueueRow(row) }
        }
    }

    /**
     * Execute a single paste-queue row. Retries transient failures (connection
     * drops, per-call IO errors) up to [QUEUE_RETRY_ATTEMPTS] times with
     * exponential backoff, then marks the row failed-but-pending so the user
     * can retry via the resume banner.
     */
    private suspend fun executeQueueRow(row: sh.haven.core.data.db.entities.PasteQueueEntry) {
        val sourceType = BackendType.valueOf(row.sourceBackendType)
        val destType = BackendType.valueOf(row.destBackendType)
        val virtualCb = FileClipboard(
            entries = emptyList(),
            sourceProfileId = row.sourceProfileId,
            sourceBackendType = sourceType,
            sourceRemoteName = row.sourceRemoteName,
            isCut = row.isCut,
        )
        val virtualEntry = SftpEntry(
            name = row.sourceName, path = row.sourcePath, isDirectory = false,
            size = row.sourceSize, modifiedTime = 0, permissions = "",
        )
        val action = try { ConflictAction.valueOf(row.conflictAction) } catch (_: Exception) { ConflictAction.OVERWRITE }
        var resumeFromByte = if (action == ConflictAction.RESUME) row.bytesTransferred else 0L

        currentQueueRowId = row.id
        lastQueueRowBytesPersist = resumeFromByte
        var lastError: Throwable? = null
        try {
            ensureDestParent(destType, row.destProfileId, row.destRemote, row.destPath)
            var attempt = 0
            while (true) {
                try {
                    crossCopyFile(
                        virtualCb, virtualEntry,
                        destType, row.destProfileId, row.destRemote,
                        row.destPath, resumeFromByte,
                    )
                    break
                } catch (e: Exception) {
                    if (!isTransientTransferError(e) || attempt >= QUEUE_RETRY_ATTEMPTS - 1) throw e
                    attempt++
                    lastError = e
                    val delayMs = QUEUE_RETRY_BACKOFF_MS * (1L shl (attempt - 1)) // 1s, 3s, 9s-ish
                    Log.w(TAG, "Paste row ${row.id} attempt $attempt failed (${e.message}); retrying in ${delayMs}ms")
                    kotlinx.coroutines.delay(delayMs)
                    // Resume from whatever the monitor last persisted — gives us
                    // a free mid-file retry via ChannelSftp.RESUME / append.
                    val current = withContext(Dispatchers.IO) {
                        pasteQueueDao.getByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                            .firstOrNull { it.id == row.id }
                    }
                    resumeFromByte = current?.bytesTransferred ?: resumeFromByte
                }
            }
            // Success
            pasteQueueDao.updateStatus(
                row.id, sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_DONE, row.sourceSize, null,
            )
            if (row.isCut) {
                deleteSourceLeaf(sourceType, row.sourceProfileId, row.sourceRemoteName, row.sourcePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Paste row ${row.id} failed permanently: ${e.message}")
            val latest = withContext(Dispatchers.IO) {
                pasteQueueDao.getByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                    .firstOrNull { it.id == row.id }
            }
            pasteQueueDao.updateStatus(
                row.id, sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING,
                latest?.bytesTransferred ?: resumeFromByte,
                (lastError ?: e).message ?: "failed",
            )
        } finally {
            currentQueueRowId = null
        }
    }

    /**
     * Heuristic: which exceptions warrant a same-row retry? Network drops and
     * mid-stream IO failures retry; everything else (FileNotFoundException for
     * missing source, permission errors) surfaces to the resume banner so the
     * user can fix the root cause.
     */
    private fun isTransientTransferError(e: Throwable): Boolean {
        return when (e) {
            is com.jcraft.jsch.JSchException,
            is com.jcraft.jsch.SftpException,
            is java.net.SocketException,
            is java.net.SocketTimeoutException,
            is javax.net.ssl.SSLException,
            -> true
            is java.io.IOException -> {
                val msg = e.message.orEmpty()
                // Fresh channel timeout, SSH closure, and "broken pipe" — all
                // transient. FileNotFoundException is a subclass but shouldn't
                // retry (source is gone).
                e !is java.io.FileNotFoundException && (
                    msg.contains("broken pipe", ignoreCase = true) ||
                        msg.contains("reset", ignoreCase = true) ||
                        msg.contains("closed", ignoreCase = true) ||
                        msg.contains("timeout", ignoreCase = true)
                    )
            }
            else -> false
        }
    }

    /** Resume a persisted paste queue — typically invoked from the banner's Resume button. */
    fun resumePasteQueue() {
        viewModelScope.launch {
            try {
                _loading.value = true
                pasteInProgress.set(true)
                batchResolution = null
                acquirePasteWakeLock()
                val pending = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                }
                val done = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_DONE)
                }
                pasteTotalFiles.set(pending + done)
                pasteFileCount.set(done)
                _transferProgress.value = TransferProgress("Resuming: $pending pending…", 0, 0)

                executePasteQueue()

                val stillPending = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                }
                val nowDone = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_DONE)
                }
                _message.value = when {
                    stillPending > 0 -> "Resumed $nowDone — $stillPending still pending"
                    else -> "Resume complete: $nowDone files"
                }
                if (stillPending == 0) {
                    withContext(Dispatchers.IO) { pasteQueueDao.clear() }
                }
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Resume failed", e)
                _error.value = "Resume failed: ${e.message}"
            } finally {
                pasteInProgress.set(false)
                _loading.value = false
                _transferProgress.value = null
                releasePasteWakeLock()
            }
        }
    }

    /** Explicitly drop the paste queue — banner's Discard button. */
    fun discardPasteQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            pasteQueueDao.clear()
        }
    }

    /**
     * Copy a single file between backends.
     *
     * Fast path: when the source is LOCAL, stream directly from the on-disk
     * source file to the destination backend. Avoids a pointless copy to
     * `appContext.cacheDir/cross_copy_...` that used to run before every
     * upload — expensive for large files (fills internal storage with a
     * temporary duplicate) and especially wasteful when the source is on
     * an external volume like a USB SD card.
     *
     * Slow path: for remote sources (SFTP / SMB / rclone) we still stage
     * through a cache file, because the source stream can't be held open
     * for an unbounded time while we open a destination channel and the
     * cached file lets us query size and reopen cleanly.
     */
    private fun crossCopyFile(
        cb: FileClipboard, entry: SftpEntry,
        destType: BackendType, destProfileId: String, destRemote: String?,
        destPath: String,
        resumeFromByte: Long = 0,
    ) {
        if (cb.sourceBackendType == BackendType.LOCAL) {
            val srcFile = java.io.File(entry.path)
            val total = srcFile.length()
            beforePasteFile(entry.name, total, resumeFromByte)
            val label = _transferProgress.value?.fileName ?: entry.name
            when (destType) {
                BackendType.LOCAL -> {
                    writeLocalFileWithProgress(srcFile, destPath, total, resumeFromByte, label)
                }
                BackendType.RCLONE -> {
                    // rclone's copyFile is opaque — we can't surface per-byte
                    // progress, so leave the bar at the known size/0 set by
                    // beforePasteFile.
                    rcloneClient.copyFile(srcFile.parent!!, srcFile.name, destRemote!!, destPath)
                }
                BackendType.SFTP -> {
                    val channel = getOrOpenChannel(destProfileId) ?: throw IllegalStateException("SFTP not connected")
                    srcFile.inputStream().use { input ->
                        val monitor = sftpProgressMonitor(label, total, resumeFromByte)
                        val mode = if (resumeFromByte > 0) ChannelSftp.RESUME else ChannelSftp.OVERWRITE
                        // RESUME mode: JSch skips source up to destination size
                        // and appends the remainder, so we pass the full stream
                        // (no caller-side skip).
                        channel.put(input, destPath, monitor, mode)
                    }
                }
                BackendType.SMB -> {
                    val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                    srcFile.inputStream().use { input ->
                        client.upload(input, destPath, total) { transferred, max ->
                            _transferProgress.value = TransferProgress(label, max, transferred)
                            maybePersistQueueProgress(transferred)
                        }
                    }
                }
            }
            return
        }

        // Remote source — two-phase copy via a cache file. Progress bar covers
        // download (phase 1) and upload (phase 2) with a combined label so the
        // user sees continuous movement rather than two jumps.
        val tempFile = java.io.File(appContext.cacheDir, "cross_copy_${entry.name}")
        val knownSize = if (entry.size > 0) entry.size else 0L
        beforePasteFile(entry.name, knownSize * 2, 0) // doubled: download + upload phases
        val baseLabel = _transferProgress.value?.fileName ?: entry.name
        try {
            when (cb.sourceBackendType) {
                BackendType.LOCAL -> {
                    // unreachable — handled by fast-path above
                }
                BackendType.RCLONE -> {
                    val srcRemote = cb.sourceRemoteName!!
                    rcloneClient.copyFile(srcRemote, entry.path, tempFile.parent!!, tempFile.name)
                }
                BackendType.SFTP -> {
                    val channel = cb.sourceSftpChannel
                        ?: sessionManager.openSftpForProfile(cb.sourceProfileId)
                        ?: throw IllegalStateException("SFTP not connected")
                    tempFile.outputStream().use { out ->
                        val monitor = sftpProgressMonitor("\u2B07 $baseLabel", knownSize * 2, 0)
                        channel.get(entry.path, out, monitor)
                    }
                }
                BackendType.SMB -> {
                    val client = cb.sourceSmbClient
                        ?: smbSessionManager.getClientForProfile(cb.sourceProfileId)
                        ?: throw IllegalStateException("SMB not connected")
                    tempFile.outputStream().use { out ->
                        client.download(entry.path, out) { transferred, max ->
                            _transferProgress.value = TransferProgress(
                                "\u2B07 $baseLabel",
                                if (max > 0) max * 2 else knownSize * 2,
                                transferred,
                            )
                        }
                    }
                }
            }

            // Phase 2: upload from cache. Downloaded size seeds the bar's starting
            // point so progress continues rather than restarting.
            val downloaded = tempFile.length()
            when (destType) {
                BackendType.LOCAL -> {
                    writeLocalFileWithProgress(tempFile, destPath, downloaded * 2, downloaded, "\u2B06 $baseLabel")
                }
                BackendType.RCLONE -> {
                    rcloneClient.copyFile(tempFile.parent!!, tempFile.name, destRemote!!, destPath)
                }
                BackendType.SFTP -> {
                    val channel = getOrOpenChannel(destProfileId) ?: throw IllegalStateException("SFTP not connected")
                    tempFile.inputStream().use { input ->
                        val monitor = sftpProgressMonitor("\u2B06 $baseLabel", downloaded * 2, downloaded)
                        val mode = if (resumeFromByte > 0) ChannelSftp.RESUME else ChannelSftp.OVERWRITE
                        channel.put(input, destPath, monitor, mode)
                    }
                }
                BackendType.SMB -> {
                    val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                    tempFile.inputStream().use { input ->
                        client.upload(input, destPath, downloaded) { transferred, _ ->
                            _transferProgress.value = TransferProgress("\u2B06 $baseLabel", downloaded * 2, downloaded + transferred)
                        }
                    }
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Inline-built [SftpProgressMonitor] that updates [_transferProgress] with
     * the same label/total throughout a single put or get call. Pre-seeds the
     * running byte counter with [initialTransferred] so resume-mode transfers
     * show a continuous bar from destination-already-has-this-many-bytes
     * rather than restarting at zero.
     */
    private fun sftpProgressMonitor(
        label: String,
        total: Long,
        initialTransferred: Long,
    ): SftpProgressMonitor = object : SftpProgressMonitor {
        private var transferred = initialTransferred
        override fun init(op: Int, src: String, dest: String, max: Long) {
            _transferProgress.value = TransferProgress(label, total, transferred)
        }
        override fun count(bytes: Long): Boolean {
            transferred += bytes
            _transferProgress.value = TransferProgress(label, total, transferred)
            maybePersistQueueProgress(transferred)
            return true
        }
        override fun end() {}
    }

    /**
     * Throttled `bytesTransferred` writer for the in-flight queue row. Writes
     * at most once per [QUEUE_PROGRESS_PERSIST_BYTES] so we cap DB traffic
     * during fast local transfers while still keeping the resume cursor close
     * to current — a crash costs at most that many bytes of replay.
     */
    private fun maybePersistQueueProgress(transferred: Long) {
        val rowId = currentQueueRowId ?: return
        if (transferred - lastQueueRowBytesPersist < QUEUE_PROGRESS_PERSIST_BYTES) return
        lastQueueRowBytesPersist = transferred
        // Fire-and-forget on the DB thread. Dropping a write is fine — the
        // next one picks up the latest value.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pasteQueueDao.updateBytesTransferred(rowId, transferred)
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * LOCAL-to-LOCAL copy with per-chunk progress reporting and support for
     * resume (append from [resumeFromByte]). Falls back to the MediaStore
     * delete-and-reinsert path if the direct write fails with a permissions
     * error under Downloads/.
     */
    private fun writeLocalFileWithProgress(
        source: java.io.File,
        destPath: String,
        total: Long,
        resumeFromByte: Long,
        label: String,
    ) {
        if (resumeFromByte == 0L) {
            try {
                java.io.File(destPath).parentFile?.mkdirs()
                source.inputStream().use { input ->
                    java.io.FileOutputStream(destPath, false).use { out ->
                        copyStreamWithProgress(input, out, total, 0L, label)
                    }
                }
                return
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Direct write to $destPath failed (${e.message}); falling back to MediaStore path")
                writeLocalFileWithMediaStoreFallback(source, destPath)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Direct write to $destPath denied; falling back to MediaStore path")
                writeLocalFileWithMediaStoreFallback(source, destPath)
                return
            }
        }
        // Resume: open destination in append mode, skip the source stream to
        // the matching offset, and count progress from there. MediaStore
        // append isn't supported, so resume is silently downgraded to overwrite
        // on that path.
        source.inputStream().use { input ->
            input.skip(resumeFromByte)
            java.io.FileOutputStream(destPath, true).use { out ->
                copyStreamWithProgress(input, out, total, resumeFromByte, label)
            }
        }
    }

    private fun copyStreamWithProgress(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        total: Long,
        startOffset: Long,
        label: String,
    ) {
        val buffer = ByteArray(64 * 1024)
        var transferred = startOffset
        _transferProgress.value = TransferProgress(label, total, transferred)
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            transferred += n
            _transferProgress.value = TransferProgress(label, total, transferred)
            maybePersistQueueProgress(transferred)
        }
        out.flush()
    }

    /**
     * Write [source] to [destPath] on the local filesystem. If a direct
     * write fails (typically because the destination is a MediaStore-owned
     * file under /storage/emulated/0/Download/ that the app can no longer
     * delete on Android Q+), fall back to writing via the MediaStore
     * Downloads collection — deleting the existing row through the content
     * resolver first.
     */
    // Caller is expected to be on Dispatchers.IO — crossCopyFile is not a
    // suspend function and already runs inside a withContext(Dispatchers.IO)
    // block at its caller (pasteFromClipboard).
    private fun writeLocalFileWithMediaStoreFallback(source: java.io.File, destPath: String) {
        val destFile = java.io.File(destPath)
        // Try streaming copy — this avoids Kotlin's File.copyTo, which
        // calls target.delete() under the hood.
        try {
            destFile.parentFile?.mkdirs()
            java.io.FileOutputStream(destFile, false).use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            return
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Direct write to $destPath failed (${e.message}); trying MediaStore", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Direct write to $destPath denied; trying MediaStore", e)
        }

        // MediaStore fallback — only applies under Download/.
        val downloads = "/storage/emulated/0/Download/"
        if (!destFile.absolutePath.startsWith(downloads)) {
            throw java.io.IOException("Cannot write to $destPath and path is not under Downloads/")
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            throw java.io.IOException("Cannot overwrite $destPath on this Android version")
        }

        val resolver = appContext.contentResolver
        val displayName = destFile.name
        // Delete any pre-existing entry under Downloads with this display name.
        // Scoped storage + MediaStore ownership means a direct File.delete()
        // fails, but the content-resolver path works for files the app owns.
        val queryUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            queryUri,
            arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val existing = android.content.ContentUris.withAppendedId(queryUri, id)
                try {
                    resolver.delete(existing, null, null)
                } catch (_: Exception) { /* keep going */ }
            }
        }

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, guessContentType(displayName))
        }
        val newUri = resolver.insert(queryUri, values)
            ?: throw java.io.IOException("Failed to create MediaStore entry for $displayName")
        resolver.openOutputStream(newUri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw java.io.IOException("Failed to open output stream for $newUri")
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
                beforePasteFile(child.name)
                rcloneClient.copyFile(srcRemote, childSrc, dstRemote, childDst)
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

                // All JSch calls below open or write to channels over the
                // network, so they must run off the Main dispatcher.
                //
                // Probe for a home directory: SFTP exposes it cheaply via
                // channel.home; when SFTP is unavailable (SCP-only servers)
                // we shell out to `echo "$HOME"` over exec instead. Fall
                // back to "/" as a last resort.
                val home: String = withContext(Dispatchers.IO) {
                    val sftpChannelForHome = sessionManager.openSftpForProfile(profileId)
                        ?: openMoshSftpChannel(profileId)
                    if (sftpChannelForHome != null) {
                        sftpChannel = sftpChannelForHome
                        sftpChannelForHome.home
                    } else {
                        val ssh = sessionManager.getSshClientForProfile(profileId)
                            ?: throw IllegalStateException("Session not connected")
                        val probe = ssh.execCommand("echo \"\$HOME\"")
                        probe.stdout.trim().ifEmpty { "/" }
                    }
                }

                _currentPath.value = home
                val transport = currentSshTransport() ?: throw IllegalStateException("Session not connected")
                val results = transport.list(home)
                _allEntries.value = sortEntries(results, _sortMode.value)
                applyFilter()
            } catch (e: Exception) {
                Log.e(TAG, "SFTP open failed", e)
                _error.value = "File browser failed: ${e.message}"
            } finally {
                if (!pasteInProgress.get()) _loading.value = false
            }
        }
    }

    /**
     * Pick a transport (SFTP or SCP) for the active SSH profile, honouring
     * the per-profile `fileTransport` preference plus Auto fallback. Emits
     * the one-shot snackbar announcement on first fallback and updates
     * [activeTransportLabel] so the UI can show a badge.
     *
     * Returns null when:
     *  - no profile is active
     *  - the profile cannot be loaded from the DB
     *  - neither SFTP nor SCP can be opened (no connected SSH session)
     */
    private suspend fun currentSshTransport(): sh.haven.feature.sftp.transport.RemoteFileTransport? {
        val profileId = _activeProfileId.value ?: return null
        // Do the DB read AND the channel-opening on IO — transportSelector.resolve
        // calls into JSch which blocks a socket to open the SFTP channel, so
        // it must not run on the Main dispatcher.
        val resolution = withContext(Dispatchers.IO) {
            val profile = repository.getById(profileId) ?: return@withContext null
            transportSelector.resolve(profile)
        } ?: return null
        resolution.announceFallback?.let { _message.value = it }
        _activeTransportLabel.value = resolution.transport.label
        return resolution.transport
    }

    private fun listDirectory(profileId: String, path: String) {
        viewModelScope.launch {
            try {
                if (!pasteInProgress.get()) _loading.value = true
                val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                val results = transport.list(path)
                _allEntries.value = sortEntries(results, _sortMode.value)
                applyFilter()
            } catch (e: Exception) {
                Log.e(TAG, "List directory failed: path='$path'", e)
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
                        owner = attrs.uId.toString(),
                        group = attrs.gId.toString(),
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

        // Kotlin has no octal literal syntax, so POSIX mode bits are
        // declared as hex with their octal meaning in the name.
        private const val MODE_SETUID  = 0x800 // 04000
        private const val MODE_SETGID  = 0x400 // 02000
        private const val MODE_STICKY  = 0x200 // 01000
        private const val MODE_O_READ  = 0x100 // 00400
        private const val MODE_O_WRITE = 0x080 // 00200
        private const val MODE_O_EXEC  = 0x040 // 00100
        private const val MODE_G_READ  = 0x020 // 00040
        private const val MODE_G_WRITE = 0x010 // 00020
        private const val MODE_G_EXEC  = 0x008 // 00010
        private const val MODE_R_READ  = 0x004 // 00004
        private const val MODE_R_WRITE = 0x002 // 00002
        private const val MODE_R_EXEC  = 0x001 // 00001
        const val MODE_MASK = 0xFFF            // 07777
        const val MODE_0644 = MODE_O_READ or MODE_O_WRITE or MODE_G_READ or MODE_R_READ

        /**
         * Parse the 10-char Unix permissions string ("-rwxr-xr-x",
         * "drwx------", "-rwsr-xr-x") into the numeric mode bits used by
         * chmod. Returns null for strings that don't look like the
         * expected format (e.g. JSch occasionally returns an empty
         * string for symlinked entries). Only the low 12 bits are set —
         * setuid/setgid/sticky from s/S/t/T are preserved, the file-type
         * nibble is not.
         */
        fun permissionsStringToMode(perms: String): Int? {
            if (perms.length < 10) return null
            var mode = 0
            fun bit(ch: Char, on: Char, value: Int) {
                if (ch == on) mode = mode or value
            }
            bit(perms[1], 'r', MODE_O_READ)
            bit(perms[2], 'w', MODE_O_WRITE)
            when (perms[3]) {
                'x' -> mode = mode or MODE_O_EXEC
                's' -> mode = mode or MODE_O_EXEC or MODE_SETUID
                'S' -> mode = mode or MODE_SETUID
            }
            bit(perms[4], 'r', MODE_G_READ)
            bit(perms[5], 'w', MODE_G_WRITE)
            when (perms[6]) {
                'x' -> mode = mode or MODE_G_EXEC
                's' -> mode = mode or MODE_G_EXEC or MODE_SETGID
                'S' -> mode = mode or MODE_SETGID
            }
            bit(perms[7], 'r', MODE_R_READ)
            bit(perms[8], 'w', MODE_R_WRITE)
            when (perms[9]) {
                'x' -> mode = mode or MODE_R_EXEC
                't' -> mode = mode or MODE_R_EXEC or MODE_STICKY
                'T' -> mode = mode or MODE_STICKY
            }
            return mode
        }

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
