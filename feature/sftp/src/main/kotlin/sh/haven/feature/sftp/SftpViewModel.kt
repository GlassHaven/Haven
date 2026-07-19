package sh.haven.feature.sftp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import sh.haven.core.ssh.SshIoException
import sh.haven.core.ssh.sftp.SftpSession
import sh.haven.core.ssh.sftp.SftpWriteMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import sh.haven.core.security.posixShellQuote
import sh.haven.core.smb.SmbClient
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshSessionManager.SessionState
import sh.haven.feature.sftp.transport.FileBackend
import sh.haven.feature.sftp.transport.LocalFileBackend
import sh.haven.feature.sftp.transport.RcloneFileBackend
import sh.haven.feature.sftp.transport.SftpTransport
import sh.haven.feature.sftp.transport.SmbFileBackend
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
    /**
     * The entry is a symlink. Directory symlinks are still presented as
     * navigable ([isDirectory] reflects the resolved target where the
     * backend supports it), but the paste walker never recurses through a
     * symlink discovered mid-walk — link cycles must terminate.
     */
    val isSymlink: Boolean = false,
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
    /** Cached SFTP session from source — survives profile switch. */
    @Transient val sourceSftpSession: SftpSession? = null,
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
/** Columns [scanDocumentTree] needs — the whole point is fetching them in ONE query per directory. */
internal val SCAN_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
)

/** One file found by [scanDocumentTree]. `docId` is a SAF document id, not a URI. */
internal data class ScannedFile(val docId: String, val relativePath: String, val length: Long)

/**
 * Walk a SAF document tree, issuing exactly ONE [queryChildren] call per directory
 * (#273). [queryChildren] must return a cursor over [SCAN_PROJECTION] for a document
 * id, or null; it is also where the caller checks for cancellation. Iterative, so a
 * deep tree can't overflow the stack. Directories are not emitted, only leaf files.
 */
internal suspend fun scanDocumentTree(
    rootDocId: String,
    rootName: String,
    queryChildren: suspend (docId: String) -> android.database.Cursor?,
    onProgress: (fileCount: Int) -> Unit = {},
): List<ScannedFile> {
    val files = mutableListOf<ScannedFile>()
    val pending = ArrayDeque<Pair<String, String>>() // docId -> relative prefix
    pending.addLast(rootDocId to rootName)
    while (pending.isNotEmpty()) {
        val (docId, prefix) = pending.removeLast()
        queryChildren(docId)?.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0) ?: continue
                val childName = c.getString(1) ?: continue
                val childPath = "$prefix/$childName"
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    pending.addLast(childId to childPath)
                } else {
                    files.add(ScannedFile(childId, childPath, if (c.isNull(3)) 0L else c.getLong(3)))
                    if (files.size % 25 == 0) onProgress(files.size)
                }
            }
        }
    }
    return files
}

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
    private val reticulumSessionManager: sh.haven.core.reticulum.ReticulumSessionManager,
    private val rcloneClient: RcloneClient,
    private val repository: ConnectionRepository,
    private val connectionLogRepository: ConnectionLogRepository,
    private val syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository,
    private val ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val ffmpegExecutor: sh.haven.core.ffmpeg.FfmpegExecutor,
    val hlsStreamServer: sh.haven.core.ffmpeg.HlsStreamServer,
    private val sftpStreamServer: SftpStreamServer,
    private val pasteQueueDao: sh.haven.core.data.db.PasteQueueDao,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val attachCoordinator: sh.haven.feature.sftp.attach.TerminalAttachCoordinator,
    private val servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * Re-exposes [TerminalAttachCoordinator.pendingRequest] so the screen
     * can switch into folder-pick mode while the terminal awaits a
     * destination choice.
     */
    val attachRequest: StateFlow<sh.haven.feature.sftp.attach.TerminalAttachCoordinator.AttachRequest?> =
        attachCoordinator.pendingRequest

    val attachProgress: StateFlow<sh.haven.feature.sftp.attach.TerminalAttachCoordinator.Progress?> =
        attachCoordinator.progress

    /**
     * "Use this folder" — confirms the current path on the active profile
     * as the attach destination. Reads transport flags directly so the
     * coordinator gets the rclone remote name when relevant.
     */
    fun confirmAttachFolder() {
        val profileId = _activeProfileId.value ?: return
        attachCoordinator.confirmFolder(
            profileId = profileId,
            isRclone = _isRcloneProfile.value,
            rcloneRemote = activeRcloneRemote,
            folderPath = _currentPath.value,
        )
    }

    /** Cancel the pending attach request — completes the deferred with null. */
    fun cancelAttach() {
        attachCoordinator.cancel()
    }

    init {
        // Subscribe to agent-driven UI commands so an MCP-issued navigation
        // verb can drive the file browser to a new profile/path. The
        // matching pager switch happens in HavenNavHost; both collectors
        // run in parallel against the same SharedFlow.
        viewModelScope.launch {
            agentUiCommandBus.commands
                // Cold-start race: an SFTP command emitted before this collector
                // subscribed (the Files screen hadn't mounted yet) is held in the
                // bus latch. onSubscription runs once we're subscribed — drain it.
                // It must NOT be handled inline: onSubscription executes
                // synchronously inside SftpViewModel.<init> (viewModelScope is
                // Main.immediate), before fields declared below the init block —
                // e.g. _activeProfileId — are initialized, and possibly during the
                // pager's forced remeasure when nav has just switched to Files.
                // Handle it on a FRESH dispatch — launch on the non-immediate
                // Dispatchers.Main (whose dispatch() always posts to the Looper, so
                // the body runs after construction unwinds) rather than the
                // viewModelScope default (Main.immediate, which would run it inline).
                // (Re-emitting is NOT enough — the buffered value is collected
                // synchronously within the same init run.) The live collect path
                // below is safe: nothing is buffered for it during init, so it only
                // ever runs post-construction. clearPendingSftp keeps the latch from
                // re-firing on a remount. See AgentUiCommandBus.
                .onSubscription {
                    agentUiCommandBus.takePendingSftpCommand()?.let { cmd ->
                        viewModelScope.launch(Dispatchers.Main) { handleAgentCommand(cmd) }
                    }
                }
                .collect { command ->
                    agentUiCommandBus.clearPendingSftp(command)
                    handleAgentCommand(command)
                }
        }
    }

    /**
     * Apply an agent-issued UI command to this file browser. Called live from
     * the bus collector and once on subscription to drain a cold-start command
     * the bus latched before this ViewModel existed (see AgentUiCommandBus).
     */
    private fun handleAgentCommand(command: sh.haven.core.data.agent.AgentUiCommand) {
                    when (command) {
                    is sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath -> {
                        if (_activeProfileId.value != command.profileId) {
                            // Pass the target path INTO selectProfile so the
                            // session-open lands there directly. Calling a
                            // separate navigateTo() after selectProfile races
                            // the async home-probe/list and usually loses — the
                            // tab would open on home (e.g. /root) instead of the
                            // requested path (e.g. a USB mount's /mnt/sda1).
                            selectProfile(command.profileId, initialPath = command.path)
                        } else {
                            navigateTo(command.path)
                        }
                    }
                    is sh.haven.core.data.agent.AgentUiCommand.OpenConvertDialog -> {
                        if (_activeProfileId.value != command.profileId) {
                            selectProfile(command.profileId)
                        }
                        // Construct a partial entry from just the path —
                        // the dialog's preview prep will probe actual
                        // size/mimeType/duration on its own. Name comes
                        // from the path basename so the dialog title
                        // makes sense.
                        val name = command.sourcePath.substringAfterLast('/')
                            .ifEmpty { command.sourcePath }
                        val entry = SftpEntry(
                            name = name,
                            path = command.sourcePath,
                            isDirectory = false,
                            size = 0L,
                            modifiedTime = 0L,
                            permissions = "",
                        )
                        openConvertDialogPrefilled(
                            entry = entry,
                            prefill = SftpViewModel.ConvertDialogPrefill(
                                container = command.container,
                                videoEncoder = command.videoEncoder,
                                audioEncoder = command.audioEncoder,
                            ),
                        )
                    }
                    is sh.haven.core.data.agent.AgentUiCommand.OpenInEditor -> {
                        if (_activeProfileId.value != command.profileId) {
                            selectProfile(command.profileId)
                        }
                        val name = command.path.substringAfterLast('/')
                            .ifEmpty { command.path }
                        val entry = SftpEntry(
                            name = name,
                            path = command.path,
                            isDirectory = false,
                            size = 0L,
                            modifiedTime = 0L,
                            permissions = "",
                        )
                        openInEditor(entry)
                    }
                    is sh.haven.core.data.agent.AgentUiCommand.EncryptFile -> {
                        if (_activeProfileId.value != command.profileId) {
                            selectProfile(command.profileId)
                        }
                        encryptFile(partialEntry(command.path), command.recipients)
                    }
                    is sh.haven.core.data.agent.AgentUiCommand.DecryptFile -> {
                        if (_activeProfileId.value != command.profileId) {
                            selectProfile(command.profileId)
                        }
                        decryptFile(partialEntry(command.path))
                    }
                    else -> Unit
                }
    }

    private val _connectedProfiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    val connectedProfiles: StateFlow<List<ConnectionProfile>> = _connectedProfiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    /**
     * Paths on the currently active profile that have been published via
     * the MCP `serve_file` tool and are still considered live (per the
     * tracker's TTL). The SFTP screen surfaces this as a chip on each
     * matching row so the user can see at a glance which files the
     * agent has fetched — or could fetch — through Haven.
     */
    val agentServedPaths: StateFlow<Set<String>> = combine(
        servedFileTracker.active,
        _activeProfileId,
    ) { entries, profileId ->
        if (profileId == null) emptySet()
        else entries.asSequence()
            .filter { it.profileId == profileId }
            .map { it.path }
            .toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    /** age encryption identities, for the per-file Encrypt picker (recipient is public). */
    val ageIdentities: StateFlow<List<sh.haven.core.data.db.entities.AgeIdentityEntity>> =
        ageIdentityRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
     * Optional initial values for the convert dialog's form fields. Set by
     * the agent's `open_convert_dialog_with_args` verb when it stages a
     * conversion for the user to review; null when the user opened the
     * dialog manually (in which case the existing extension-based defaults
     * apply). Read once on dialog composition.
     */
    data class ConvertDialogPrefill(
        val container: String? = null,
        val videoEncoder: String? = null,
        val audioEncoder: String? = null,
    )
    private val _convertDialogPrefill = MutableStateFlow<ConvertDialogPrefill?>(null)
    val convertDialogPrefill: StateFlow<ConvertDialogPrefill?> = _convertDialogPrefill.asStateFlow()

    /**
     * Label of the transport currently servicing the active SSH profile —
     * `"SFTP"`, `"SCP"`, or null while non-SSH backends are active. Shown
     * as a badge in the path bar so users always know what they're on.
     */
    private val _activeTransportLabel = MutableStateFlow<String?>(null)
    val activeTransportLabel: StateFlow<String?> = _activeTransportLabel.asStateFlow()

    fun openConvertDialog(entry: SftpEntry) {
        _convertDialogPrefill.value = null
        _convertDialogEntry.value = entry
    }

    /**
     * Open the convert dialog for [entry] with the given form-field
     * defaults pre-selected. Used by the agent's
     * `open_convert_dialog_with_args` verb so a remote MCP client can
     * stage a conversion for the user to review and confirm.
     */
    fun openConvertDialogPrefilled(entry: SftpEntry, prefill: ConvertDialogPrefill?) {
        _convertDialogPrefill.value = prefill
        _convertDialogEntry.value = entry
    }

    fun dismissConvertDialog() {
        _convertDialogEntry.value = null
        _convertDialogPrefill.value = null
        clearPreview()
    }

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

    /** Whether any rclone remote is configured. Gates the connection-independent
     *  "New sync" entry point (#277) — a sync doesn't need an open connection, so the
     *  entry shows whenever rclone has remotes, even with nothing connected. */
    private val _hasRcloneRemotes = MutableStateFlow(false)
    val hasRcloneRemotes: StateFlow<Boolean> = _hasRcloneRemotes.asStateFlow()

    fun refreshRcloneRemotesAvailable() {
        viewModelScope.launch(Dispatchers.IO) {
            _hasRcloneRemotes.value = try {
                rcloneClient.initialize()
                rcloneClient.listRemotes().isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "rclone remotes availability check failed: ${e.message}")
                false
            }
        }
    }

    /** Dry run summary text. */
    private val _dryRunResult = MutableStateFlow<String?>(null)
    val dryRunResult: StateFlow<String?> = _dryRunResult.asStateFlow()
    fun dismissDryRunResult() { _dryRunResult.value = null }

    /**
     * Saved rclone sync configurations (#159). Most-recently-run first,
     * collected from Room so changes in another dialog instance propagate
     * straight to the dropdown. Exposed as a StateFlow with an empty
     * starting value so the dialog can render before the first emit.
     */
    val savedSyncProfiles: StateFlow<List<sh.haven.core.data.db.entities.SyncProfile>> =
        syncProfileRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Track the currently-loaded saved profile so [startSync] can stamp lastRunAt. */
    private val _activeSavedSyncProfileId = MutableStateFlow<String?>(null)
    val activeSavedSyncProfileId: StateFlow<String?> = _activeSavedSyncProfileId.asStateFlow()
    fun setActiveSavedSyncProfileId(id: String?) { _activeSavedSyncProfileId.value = id }

    /**
     * Persist the dialog's current values as a saved sync profile. The id
     * is generated when [existingId] is null (Save-as new) and reused
     * when a saved profile is being overwritten (Save).
     */
    fun saveSyncProfile(
        existingId: String?,
        name: String,
        config: SyncConfig,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val mode = when (config.mode) {
                sh.haven.core.rclone.SyncMode.COPY -> "COPY"
                sh.haven.core.rclone.SyncMode.SYNC -> "SYNC"
                sh.haven.core.rclone.SyncMode.MOVE -> "MOVE"
            }
            val entity = sh.haven.core.data.db.entities.SyncProfile(
                id = existingId ?: java.util.UUID.randomUUID().toString(),
                name = name.trim(),
                srcFs = config.srcFs,
                dstFs = config.dstFs,
                mode = mode,
                includePatterns = config.filters.includePatterns.joinToString("\n"),
                excludePatterns = config.filters.excludePatterns.joinToString("\n"),
                minSize = config.filters.minSize,
                maxSize = config.filters.maxSize,
                bandwidthLimit = config.filters.bandwidthLimit,
            )
            syncProfileRepository.save(entity)
            _activeSavedSyncProfileId.value = entity.id
        }
    }

    fun deleteSyncProfile(id: String) {
        viewModelScope.launch {
            syncProfileRepository.delete(id)
            if (_activeSavedSyncProfileId.value == id) _activeSavedSyncProfileId.value = null
        }
    }

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

    /**
     * Pushed down by [SftpScreen] every time `isSystemInDarkTheme()`
     * recomposes — the ViewModel can't read Compose state itself, but
     * needs the value so [terminalColorScheme] resolves correctly when
     * auto-switch is enabled.
     */
    private val systemIsDark = MutableStateFlow(true)

    fun setSystemIsDark(isDark: Boolean) {
        systemIsDark.value = isDark
    }

    /** Effective terminal colour scheme, respecting auto-switch. */
    val terminalColorScheme: StateFlow<UserPreferencesRepository.TerminalColorScheme> =
        combine(
            preferencesRepository.terminalColorScheme,
            preferencesRepository.terminalAutoSwitchScheme,
            preferencesRepository.terminalLightColorScheme,
            preferencesRepository.terminalDarkColorScheme,
            systemIsDark,
        ) { manual, autoSwitch, light, dark, isDark ->
            if (autoSwitch) (if (isDark) dark else light) else manual
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferencesRepository.TerminalColorScheme.HAVEN)

    private var editorEntry: SftpEntry? = null

    fun openInEditor(entry: SftpEntry) {
        if (entry.isDirectory) return
        editorEntry = entry
        _editorFile.value = EditorFileState.Loading
        viewModelScope.launch {
            try {
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                val content = backend.readBytes(entry.path).toString(Charsets.UTF_8)
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
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                backend.writeBytes(entry.path, data)
                _editorFile.value = EditorFileState.Open(entry.name, entry.path, content)
                _message.value = appContext.getString(R.string.sftp_saved_file, entry.name)
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
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                val data = backend.readBytes(entry.path)
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
        _imageToolFile.value = ImageToolFileState.Processing(appContext.getString(R.string.sftp_processing_perspective))
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
        _imageToolFile.value = ImageToolFileState.Processing(appContext.getString(R.string.sftp_processing_crop))
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
        _imageToolFile.value = ImageToolFileState.Processing(appContext.getString(R.string.sftp_processing_rotate))
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
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                backend.writeBytes(entry.path, data)
                _message.value = appContext.getString(R.string.sftp_saved_file, entry.name)
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
            "rcloneRemote=$activeRcloneRemote, sftpSession=${sftpSession?.isConnected}, smbClient=${activeSmbClient != null}")
        val backendType = when {
            _isLocalProfile.value -> BackendType.LOCAL
            _isRcloneProfile.value -> BackendType.RCLONE
            _isSmbProfile.value -> BackendType.SMB
            else -> BackendType.SFTP
        }
        // Open a dedicated SFTP session for copy (separate from browse session)
        val copySession = if (backendType == BackendType.SFTP) {
            sessionManager.openSftpSession(profileId)
        } else null
        Log.d(TAG, "copyToClipboard: dedicated copy session=${copySession?.isConnected}")
        _clipboard.value = FileClipboard(
            entries = entries,
            sourceProfileId = profileId,
            sourceBackendType = backendType,
            sourceRemoteName = activeRcloneRemote,
            isCut = isCut,
            sourceSftpSession = copySession,
            sourceSmbClient = if (backendType == BackendType.SMB) activeSmbClient else null,
        )
        _message.value = "${entries.size} item${if (entries.size > 1) "s" else ""} ${if (isCut) "cut" else "copied"}"
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    private var sftpSession: SftpSession? = null
    private var activeSmbClient: SmbClient? = null

    /**
     * Build an [SftpStreamServer.Opener] for [entry] on the currently
     * active profile. The opener captures [profileId] (not a channel) so
     * it re-resolves against whichever browse channel is live at read
     * time — important across reconnects.
     */
    private fun sftpOpener(profileId: String, path: String): SftpStreamServer.Opener =
        SftpStreamServer.Opener { offset ->
            val session = getOrOpenSession(profileId)
                ?: throw java.io.IOException("SFTP not connected")
            // SftpStreamServer.Opener.open is a non-suspend functional
            // interface (called from the HTTP server's worker thread) —
            // bridge to SftpSession's suspend API with runBlocking. The
            // calling thread is already happy to block on I/O.
            kotlinx.coroutines.runBlocking {
                session.openInputStream(path, if (offset > 0) offset else 0L)
            }
        }

    /**
     * Range-capable opener for SMB media, so ffmpeg/players read over the same
     * loopback HTTP bridge SFTP uses instead of downloading the whole file
     * first (VISION §2). [SmbClient.openInputStream] honours the offset, so a
     * preview probe reads just the moov atom. Re-reads [activeSmbClient] each
     * call so it survives a reconnect.
     */
    private fun smbOpener(path: String): SftpStreamServer.Opener =
        SftpStreamServer.Opener { offset ->
            val client = activeSmbClient ?: throw java.io.IOException("SMB not connected")
            client.openInputStream(path, if (offset > 0) offset else 0L)
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

    /**
     * Best-effort MIME type for an "Open with…" hand-off (issue #173). Tries
     * the platform extension map first (covers documents/images/pdf), falls
     * back to the media table, then a wildcard so the chooser still appears
     * for types neither knows (e.g. .cbz).
     */
    private fun mimeTypeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        val media = guessContentType(name)
        return if (media != "application/octet-stream") media else "*/*"
    }

    /** Tracks which active profile is SMB (vs SFTP). */
    private val _isSmbProfile = MutableStateFlow(false)

    /** Tracks which active profile is rclone (vs SFTP/SMB). */
    private val _isRcloneProfile = MutableStateFlow(false)
    val isRcloneProfile: StateFlow<Boolean> = _isRcloneProfile.asStateFlow()

    /**
     * Tracks whether the active profile is a Reticulum profile. Set once at
     * [selectProfile] (like [_isSmbProfile]/[_isRcloneProfile]) so file ops
     * dispatch on a stable profile-TYPE flag, not a live
     * `reticulumSessionManager.isProfileConnected()` snapshot that can read
     * false mid-(re)connect and misroute to the SSH path.
     */
    private val _isReticulumProfile = MutableStateFlow(false)

    /** Tracks whether the active profile is the local filesystem. */
    private val _isLocalProfile = MutableStateFlow(false)

    /**
     * Tracks whether the active profile is a SAF "local folder" location (#415).
     * Like [_isLocalProfile] its backend is resolved on demand via
     * [TransportSelector] (from the persisted tree Uri), so there's no session to
     * open — listing and the editor go straight through `currentFileBackend()`.
     */
    private val _isSafProfile = MutableStateFlow(false)

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

            // Collect profile IDs from mosh sessions whose bootstrap SSH client
            // is still CONNECTED — mosh's own UDP transport can't carry SFTP, so
            // file browsing rides the SSH client kept alive from bootstrap. A
            // non-null-but-dropped client (server closed SSH after mosh-server
            // handed off) would otherwise show an empty Files tab (#414-adjacent
            // report: mosh tab appears but never lists).
            val moshProfileIds = moshSessionManager.sessions.value.values
                .filter {
                    it.status == MoshSessionManager.SessionState.Status.CONNECTED &&
                        (it.sshClient as? SshClient)?.isConnected == true
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

            // Collect profile IDs from Reticulum/rnsh sessions — file ops run
            // over the command-exec substrate (ReticulumFileBackend).
            val reticulumProfileIds = reticulumSessionManager.sessions.value.values
                .filter { it.status == sh.haven.core.reticulum.ReticulumSessionManager.SessionState.Status.CONNECTED }
                .map { it.profileId }
                .toSet()

            val connectedProfileIds = sshProfileIds + moshProfileIds + etProfileIds + smbProfileIds + rcloneProfileIds + reticulumProfileIds

            val profiles = withContext(Dispatchers.IO) { repository.getAll() }
            val remoteProfiles = profiles.filter { it.id in connectedProfileIds }
            // SAF "local folder" locations (#415) are always available — the grant
            // is persisted, there's no session to connect — so they show as tabs
            // unconditionally, right after the synthetic "Local" tab.
            val safProfiles = profiles.filter { it.isSaf }
            _connectedProfiles.value = listOf(localProfile) + safProfiles + remoteProfiles

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

            // Auto-select first connected profile if none selected. SAF "local
            // folder" profiles (#415) and the synthetic "local" tab are valid
            // active selections even though they're never in connectedProfileIds
            // (no session to connect). Without them here, this sweep silently
            // deselects an active SAF tab back to "local" — and because the tab's
            // entries stay on screen, the next file op then misroutes to
            // LocalFileBackend (opening the tree-relative path against the real
            // filesystem root: EROFS/ENOENT). Device-verified regression.
            val selectableIds = connectedProfileIds + safProfiles.map { it.id } + "local"
            if (_activeProfileId.value == null || _activeProfileId.value !in selectableIds) {
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

    /**
     * Register a SAF-picked folder (#415) as a persistent "local folder" Files
     * location. Persists the tree grant with `takePersistableUriPermission` so it
     * survives app restarts, saves a SAF [ConnectionProfile], and selects the new
     * tab. The tab list is updated synchronously here (not just via the async
     * [syncConnectedProfiles]) because [selectProfile] reads it to detect the SAF
     * type; a later sync re-sorts it into place.
     */
    fun addSafFolder(treeUri: Uri) {
        viewModelScope.launch {
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not persist SAF grant for $treeUri", e)
                _error.value = appContext.getString(R.string.sftp_saf_grant_failed)
                return@launch
            }
            val name = DocumentFile.fromTreeUri(appContext, treeUri)?.name
                ?: treeUri.lastPathSegment?.substringAfterLast('/')
                ?: "Folder"
            val profile = ConnectionProfile(
                id = java.util.UUID.randomUUID().toString(),
                label = name,
                host = "",
                username = "",
                connectionType = "SAF",
                safTreeUri = treeUri.toString(),
            )
            withContext(Dispatchers.IO) { repository.save(profile) }
            _connectedProfiles.value = _connectedProfiles.value + profile
            selectProfile(profile.id)
        }
    }

    /**
     * Remove a SAF "local folder" location (#415): release the persisted tree
     * grant, delete the profile, drop its tab, and fall back to Local if it was
     * active. Releasing the grant is best-effort — a since-revoked permission
     * throws, which is fine, the row still goes.
     */
    fun removeSafFolder(profileId: String) {
        viewModelScope.launch {
            val profile = _connectedProfiles.value.find { it.id == profileId } ?: return@launch
            if (!profile.isSaf) return@launch
            profile.safTreeUri?.let { uriStr ->
                try {
                    appContext.contentResolver.releasePersistableUriPermission(
                        Uri.parse(uriStr),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not release SAF grant for $uriStr", e)
                }
            }
            withContext(Dispatchers.IO) { repository.delete(profileId) }
            _connectedProfiles.value = _connectedProfiles.value.filterNot { it.id == profileId }
            profileStateCache.remove(profileId)
            if (_activeProfileId.value == profileId) selectProfile("local")
        }
    }

    fun selectProfile(profileId: String, initialPath: String? = null) {
        Log.d(TAG, "selectProfile: $profileId (prev=${_activeProfileId.value}, clipboard=${_clipboard.value != null}, initialPath=$initialPath)")

        // Save current profile's browse state before switching
        _activeProfileId.value?.let { prevId ->
            profileStateCache[prevId] = ProfileBrowseState(
                path = _currentPath.value,
                allEntries = _allEntries.value,
            )
        }

        val isLocal = profileId == "local"
        val isSaf = !isLocal && _connectedProfiles.value.find { it.id == profileId }?.isSaf == true
        val isSmb = !isLocal && smbSessionManager.isProfileConnected(profileId)
        val isRclone = !isLocal && rcloneSessionManager.isProfileConnected(profileId)
        val isReticulum = !isLocal && reticulumSessionManager.isProfileConnected(profileId)
        _isLocalProfile.value = isLocal
        _isSafProfile.value = isSaf
        _isSmbProfile.value = isSmb
        _isRcloneProfile.value = isRclone
        _isReticulumProfile.value = isReticulum
        _activeProfileId.value = profileId
        sftpSession = null
        activeSmbClient = null
        activeRcloneRemote = null
        _remoteCapabilities.value = sh.haven.core.rclone.RemoteCapabilities()

        // Restore cached state if available — but when an explicit initialPath
        // is requested (NavigateToSftpPath to a specific dir), bypass the cache
        // so we land on the requested path, not the last-browsed one.
        val cached = if (initialPath != null) null else profileStateCache[profileId]
        if (cached != null) {
            _currentPath.value = cached.path
            _allEntries.value = cached.allEntries
            // Re-derive the visible list from the CURRENT global filter (show-hidden /
            // name filter) rather than the list cached when this tab was last active —
            // otherwise the toolbar's show-hidden icon (global) and this tab's contents
            // disagree after you toggle it on another tab.
            applyFilter()
            // Still need to re-establish the backend connection
            when {
                isLocal -> { /* no connection needed */ }
                isSaf -> { /* backend resolved on demand from the persisted tree Uri */ }
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
                isReticulum -> { /* backend resolved on demand via TransportSelector */ }
                else -> {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            sftpSession = sessionManager.openSftpSession(profileId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to pre-warm SFTP session for $profileId", e)
                        }
                    }
                }
            }
        } else {
            val landing = initialPath ?: "/"
            _currentPath.value = landing
            _allEntries.value = emptyList()
            _entries.value = emptyList()
            when {
                isLocal -> loadDirectoryEntries(landing)
                isSaf -> loadDirectoryEntries(landing)
                isRclone -> openRcloneAndList(profileId)
                isSmb -> openSmbAndList(profileId)
                isReticulum -> loadDirectoryEntries(landing)
                else -> openSftpAndList(profileId, landing)
            }
        }
    }

    fun navigateTo(path: String) {
        _activeProfileId.value ?: return
        _currentPath.value = path
        _selectedPaths.value = emptySet()
        loadDirectoryEntries(path)
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
                filtered = filtered.filter { regex.containsMatchIn(it.name) }
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
        _activeProfileId.value ?: return
        loadDirectoryEntries(_currentPath.value)
    }

    /** Whether the active profile is the local filesystem. */
    fun isLocalProfile(): Boolean = _isLocalProfile.value
    fun isSmbProfile(): Boolean = _isSmbProfile.value

    /** True when the local file browser needs MANAGE_EXTERNAL_STORAGE permission. */
    val needsStoragePermission: Boolean
        get() = _isLocalProfile.value &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()


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
                _message.value = appContext.getString(R.string.sftp_downloaded_file, entry.name)
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
                } else if (_isSmbProfile.value && !downloadFirst) {
                    // Stream SMB via the loopback bridge — ffmpeg reads over Range
                    // requests instead of pre-downloading the whole file (VISION §2).
                    val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                    val urlPath = sftpStreamServer.publish(
                        path = entry.path,
                        size = entry.size,
                        contentType = guessContentType(entry.name),
                        opener = smbOpener(entry.path),
                        concurrentSafe = true,
                    )
                    ffmpegInput = "http://127.0.0.1:$port$urlPath"
                    Log.d(TAG, "convertFile: streaming SMB via $ffmpegInput")
                } else if (!downloadFirst) {
                    // SFTP — stream via the loopback bridge too (VISION §2). The
                    // download path below remains the downloadFirst opt-in for a
                    // long transcode over a flaky link.
                    val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                    val urlPath = sftpStreamServer.publish(
                        path = entry.path,
                        size = entry.size,
                        contentType = guessContentType(entry.name),
                        opener = sftpOpener(profileId, entry.path),
                    )
                    ffmpegInput = "http://127.0.0.1:$port$urlPath"
                    Log.d(TAG, "convertFile: streaming SFTP via $ffmpegInput")
                } else {
                    // rclone/SFTP/SMB with downloadFirst=true — pull the whole
                    // file into cache, then hand the path to ffmpeg.
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
                                val session = getOrOpenSession(profileId) ?: throw IllegalStateException("Not connected")
                                session.download(entry.path, out) { transferred, total ->
                                    _transferProgress.value = TransferProgress(dlLabel, total, transferred)
                                }
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
                        appContext.getString(R.string.sftp_location_downloads)
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
                                val session = getOrOpenSession(profileId)
                                    ?: throw IllegalStateException("Not connected")
                                val uploadLabel = "\u2B06 Uploading $outName"
                                val total = cacheOutput.length()
                                _transferProgress.value = TransferProgress(uploadLabel, total, 0)
                                cacheOutput.inputStream().use { input ->
                                    session.upload(input, total, destPath) { transferred, _ ->
                                        _transferProgress.value = TransferProgress(uploadLabel, total, transferred)
                                    }
                                }
                                sourceDir
                            }
                        }
                    }
                }

                _message.value = appContext.getString(R.string.sftp_saved_file_to, outName, savedLocation)
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

    // ── age file encryption (VISION §2) ──────────────────────────────────
    //
    // Encrypt/decrypt compose on the same download → transform → upload
    // scaffolding convertFile uses, via the shared [resolveInputFile] /
    // [uploadCacheFile] helpers, so they light up every backend at once.

    /** Build a minimal [SftpEntry] from a path for agent-driven (bus) encrypt/decrypt — size/mtime probed as needed by the op. */
    private fun partialEntry(path: String): SftpEntry = SftpEntry(
        name = path.substringAfterLast('/').ifEmpty { path },
        path = path,
        isDirectory = false,
        size = 0L,
        modifiedTime = 0L,
        permissions = "",
    )

    /** Encrypt [entry] to `<name>.age` in the same folder, for [recipients] (`age1…` strings). Non-destructive. */
    fun encryptFile(entry: SftpEntry, recipients: List<String>) {
        if (recipients.isEmpty()) {
            _error.value = appContext.getString(R.string.sftp_age_no_identity)
            return
        }
        val profileId = _activeProfileId.value ?: return
        val outName = "${entry.name}.age"
        runAgeJob(entry, profileId, outName, "🔒 Encrypting ${entry.name}") { input, output ->
            sh.haven.core.security.AgeFile.encrypt(input, output, recipients)
        }
    }

    /** Decrypt a `.age` [entry] in place (strips `.age`) using any stored identity. */
    fun decryptFile(entry: SftpEntry) {
        val profileId = _activeProfileId.value ?: return
        val outName = entry.name.removeSuffix(".age").ifBlank { "${entry.name}.decrypted" }
        viewModelScope.launch {
            val secrets = ageIdentityRepository.getAll().mapNotNull { ageIdentityRepository.fetchSecret(it.id) }
            if (secrets.isEmpty()) {
                _error.value = appContext.getString(R.string.sftp_age_no_identity)
                return@launch
            }
            runAgeJobSuspending(entry, profileId, outName, "🔓 Decrypting ${entry.name}") { input, output ->
                sh.haven.core.security.AgeFile.decrypt(input, output, secrets)
            }
        }
    }

    private fun runAgeJob(
        entry: SftpEntry,
        profileId: String,
        outName: String,
        progressLabel: String,
        transform: (java.io.InputStream, java.io.OutputStream) -> Unit,
    ) {
        viewModelScope.launch { runAgeJobSuspending(entry, profileId, outName, progressLabel, transform) }
    }

    private suspend fun runAgeJobSuspending(
        entry: SftpEntry,
        profileId: String,
        outName: String,
        progressLabel: String,
        transform: (java.io.InputStream, java.io.OutputStream) -> Unit,
    ) {
        val sourceDir = entry.path.substringBeforeLast('/', "").ifEmpty { "/" }
        val destPath = if (sourceDir == "/") "/$outName" else "$sourceDir/$outName"
        val cacheIn = java.io.File(appContext.cacheDir, "age_in_${entry.name}")
        val cacheOut = java.io.File(appContext.cacheDir, outName)
        try {
            _loading.value = true
            val srcFile = resolveInputFile(entry, profileId, cacheIn, "⬇ Reading ${entry.name}")
            _transferProgress.value = TransferProgress(progressLabel, 0, 0)
            withContext(Dispatchers.IO) {
                srcFile.inputStream().use { input ->
                    cacheOut.outputStream().use { output -> transform(input, output) }
                }
            }
            uploadCacheFile(cacheOut, destPath, profileId, "⬆ Uploading $outName")
            _message.value = appContext.getString(R.string.sftp_saved_file_to, outName, sourceDir)
            if (_currentPath.value == sourceDir) refresh()
        } catch (e: sh.haven.core.security.AgeFile.AgeException) {
            Log.w(TAG, "age job failed", e)
            _error.value = appContext.getString(R.string.sftp_age_failed)
        } catch (e: Exception) {
            Log.e(TAG, "age job failed", e)
            _error.value = appContext.getString(R.string.sftp_age_failed)
        } finally {
            _loading.value = false
            _transferProgress.value = null
            cacheIn.delete() // a no-op for local (srcFile is the original, never in cacheDir)
            cacheOut.delete()
        }
    }

    /** Return a readable [java.io.File] for [entry]: the original for local, else a downloaded cache copy at [cacheTarget]. */
    private suspend fun resolveInputFile(
        entry: SftpEntry,
        profileId: String,
        cacheTarget: java.io.File,
        label: String,
    ): java.io.File {
        if (_isLocalProfile.value) return java.io.File(entry.path)
        _transferProgress.value = TransferProgress(label, entry.size, 0)
        withContext(Dispatchers.IO) {
            when {
                _isRcloneProfile.value -> {
                    val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                    rcloneClient.copyFile(remote, entry.path, cacheTarget.parent!!, cacheTarget.name)
                }
                _isSmbProfile.value -> {
                    val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                    cacheTarget.outputStream().use { out ->
                        client.download(entry.path, out) { transferred, total ->
                            _transferProgress.value = TransferProgress(label, total, transferred)
                        }
                    }
                }
                else -> {
                    val session = getOrOpenSession(profileId) ?: throw IllegalStateException("Not connected")
                    cacheTarget.outputStream().use { out ->
                        session.download(entry.path, out) { transferred, total ->
                            _transferProgress.value = TransferProgress(label, total, transferred)
                        }
                    }
                }
            }
        }
        return cacheTarget
    }

    /** Upload [cacheFile] to [destPath] on the active backend. Mirrors convertFile's SOURCE_FOLDER dispatch. */
    private suspend fun uploadCacheFile(
        cacheFile: java.io.File,
        destPath: String,
        profileId: String,
        label: String,
    ) {
        when {
            _isLocalProfile.value -> withContext(Dispatchers.IO) {
                cacheFile.copyTo(java.io.File(destPath), overwrite = true)
            }
            _isRcloneProfile.value -> {
                val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                _transferProgress.value = TransferProgress(label, 0, 0)
                withContext(Dispatchers.IO) {
                    rcloneClient.copyFile(cacheFile.parent!!, cacheFile.name, remote, destPath.trimStart('/'))
                }
            }
            _isSmbProfile.value -> {
                val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                val len = cacheFile.length()
                _transferProgress.value = TransferProgress(label, len, 0)
                withContext(Dispatchers.IO) {
                    cacheFile.inputStream().use { input ->
                        client.upload(input, destPath, len) { transferred, total ->
                            _transferProgress.value = TransferProgress(label, total, transferred)
                        }
                    }
                }
            }
            else -> {
                val session = getOrOpenSession(profileId) ?: throw IllegalStateException("Not connected")
                val total = cacheFile.length()
                _transferProgress.value = TransferProgress(label, total, 0)
                cacheFile.inputStream().use { input ->
                    session.upload(input, total, destPath) { transferred, _ ->
                        _transferProgress.value = TransferProgress(label, total, transferred)
                    }
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
     *  - SMB → loopback HTTP URL via sftpStreamServer (smbOpener) — VISION §2
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
            _isSmbProfile.value -> {
                val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                val urlPath = sftpStreamServer.publish(
                    path = entry.path,
                    size = entry.size,
                    contentType = guessContentType(entry.name),
                    opener = smbOpener(entry.path),
                    concurrentSafe = true,
                )
                "http://127.0.0.1:$port$urlPath"
            }
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
                appContext.getString(R.string.sftp_location_downloads)
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
                        val session = getOrOpenSession(profileId!!)
                            ?: throw IllegalStateException("Not connected")
                        val uploadLabel = "\u2B06 Uploading $outName"
                        val total = cacheOutput.length()
                        _transferProgress.value = TransferProgress(uploadLabel, total, 0)
                        cacheOutput.inputStream().use { input ->
                            session.upload(input, total, destPath) { transferred, _ ->
                                _transferProgress.value = TransferProgress(uploadLabel, total, transferred)
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
                _message.value = appContext.getString(R.string.sftp_saved_file_to, finalOutput.name, savedLocation)
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
        viewModelScope.launch {
            val logBuffer = StringBuilder()
            val startTime = System.currentTimeMillis()
            fun appendLog(line: String) {
                val elapsed = System.currentTimeMillis() - startTime
                logBuffer.append("+${elapsed}ms ").append(line).append('\n')
            }
            try {
                _loading.value = true
                // List the long-clicked folder itself — not _entries.value,
                // which is the currently displayed directory (the folder's
                // parent). Using _entries here streamed the wrong folder.
                val backend = currentFileBackend()
                    ?: throw IllegalStateException("Not connected")
                val mediaEntries = backend.list(folderPath)
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
        viewModelScope.launch {
            val logBuffer = StringBuilder()
            val startTime = System.currentTimeMillis()
            fun appendLog(line: String) {
                val elapsed = System.currentTimeMillis() - startTime
                logBuffer.append("+${elapsed}ms ").append(line).append('\n')
            }
            try {
                // Resolve the ffmpeg input path or URL — one path for every
                // backend, including SMB via the loopback bridge (VISION §2).
                val streamInput: String = resolveStreamInput(entry)
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
                        // SMB — stream via the same loopback HTTP bridge as SFTP
                        // so ffmpeg uses Range requests (probe reads just the moov
                        // atom) instead of downloading the whole file (VISION §2).
                        val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                        val urlPath = sftpStreamServer.publish(
                            path = entry.path,
                            size = entry.size,
                            contentType = guessContentType(entry.name),
                            opener = smbOpener(entry.path),
                            concurrentSafe = true,
                        )
                        inputSource = "http://127.0.0.1:$port$urlPath"
                        isRemote = true
                        Log.d(TAG, "preparePreview: using SMB HTTP URL $inputSource")
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
                        } else if (_isReticulumProfile.value) {
                            // Reticulum has no streaming transport.upload(); route through the
                            // resolved FileBackend (Reticulum SFTP writeBytes, or the exec
                            // ReticulumFileBackend's octal-printf). Dispatch on the stable
                            // profile-TYPE flag (like rclone/SMB above), NOT a live
                            // isProfileConnected() snapshot — the latter can read false
                            // mid-(re)connect (e.g. after the SAF picker backgrounded Haven)
                            // and misroute a Reticulum upload to the SSH branch, throwing a
                            // misleading "Not connected". A genuinely-down session instead
                            // gets a correctly-attributed Reticulum error from currentFileBackend().
                            val backend = currentFileBackend()
                                ?: throw IllegalStateException("Reticulum profile not connected")
                            val data = input.readBytes()
                            backend.writeBytes(destPath, data)
                            _transferProgress.value = TransferProgress(fileName, data.size.toLong(), data.size.toLong())
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
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                backend.delete(entry.path, entry.isDirectory)
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
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                backend.rename(entry.path, newPath)
                _message.value = "Renamed to $newName"
                refresh()
            } catch (e: UnsupportedOperationException) {
                // #415: some DocumentsProviders don't implement rename (Termux's is
                // one — create/read/write/delete work, rename doesn't). Surface that
                // plainly instead of a raw "Rename failed: Rename not supported".
                Log.w(TAG, "Rename not supported by this backend", e)
                _error.value = appContext.getString(R.string.sftp_rename_not_supported)
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
                val cmd = "LC_ALL=C ls -ld -- ${posixShellQuote(path)}"
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
        val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
        backend.delete(entry.path, entry.isDirectory)
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

                // Enumerate the picked tree OFF the main thread (#273). DocumentFile
                // is the trap here, not just the thread: listFiles() throws away the
                // cursor's columns and rebuilds each child from its document id, so
                // every subsequent child.name / child.isDirectory / child.length()
                // costs a separate ContentResolver.query() — ~3 cross-process round
                // trips per file. Query each directory ONCE with a full projection
                // instead, and report scan progress so a slow provider looks busy
                // rather than hung.
                //
                // Measured against Termux's DocumentsProvider (OnePlus 13, flat tree):
                //   400 files:  1628 ms -> 155 ms
                //   4000 files: see #273 — the old walk is ~linear in file count,
                //               the new one is ~flat (one query per directory).
                data class FileItem(val uri: Uri, val relativePath: String, val length: Long)
                val enumerated = withContext(Dispatchers.IO) {
                    val rootId = runCatching { DocumentsContract.getTreeDocumentId(folderUri) }
                        .getOrNull() ?: return@withContext null
                    val rootName = DocumentFile.fromTreeUri(appContext, folderUri)?.name ?: "upload"
                    val scanned = scanDocumentTree(
                        rootDocId = rootId,
                        rootName = rootName,
                        queryChildren = { docId ->
                            currentCoroutineContext().ensureActive() // cancellable mid-scan
                            appContext.contentResolver.query(
                                DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId),
                                SCAN_PROJECTION, null, null, null,
                            )
                        },
                        onProgress = { n ->
                            _transferProgress.value = TransferProgress("Scanning… $n files", 0, 0)
                        },
                    )
                    val files = scanned.map {
                        FileItem(
                            DocumentsContract.buildDocumentUriUsingTree(folderUri, it.docId),
                            it.relativePath,
                            it.length,
                        )
                    }
                    Triple(rootName, files, files.sumOf { it.length })
                } ?: return@launch
                val (folderName, files, initialTotalBytes) = enumerated

                // Check if the folder already exists in the destination
                val (proceed, _) = checkConflict(folderName, null)
                if (!proceed) {
                    _message.value = "Skipped $folderName"
                    return@launch
                }

                val totalFiles = files.size
                var completedFiles = 0
                var totalBytes = initialTotalBytes
                var transferredBytes = 0L

                // Destination directories we've already created. Folder upload used
                // to call the backend's single-level mkdir per file, so the first
                // file inside a nested directory died with SSH_FX_NO_SUCH_FILE (its
                // grandparent didn't exist) — i.e. uploading ANY folder with
                // subdirectories to SFTP failed outright (#273, found on-device).
                // Route through ensureDestParent(), the mkdir-p helper the
                // copy/paste path already uses, once per directory rather than
                // once per file.
                val destType = when {
                    _isRcloneProfile.value -> BackendType.RCLONE
                    _isSmbProfile.value -> BackendType.SMB
                    else -> BackendType.SFTP
                }
                val ensuredDirs = mutableSetOf<String>()

                for (item in files) {
                    val destPath = destBase.trimEnd('/') + "/" + item.relativePath
                    val destDir = destPath.substringBeforeLast('/')
                    val fileName = item.relativePath.substringAfterLast('/')
                    val fileSize = item.length

                    if (ensuredDirs.add(destDir)) {
                        withContext(Dispatchers.IO) {
                            ensureDestParent(destType, profileId, activeRcloneRemote, destPath)
                        }
                    }

                    _transferProgress.value = TransferProgress(
                        "${completedFiles + 1}/$totalFiles: $fileName",
                        totalBytes,
                        transferredBytes,
                    )

                    withContext(Dispatchers.IO) {
                        if (_isRcloneProfile.value) {
                            val remote = activeRcloneRemote ?: throw IllegalStateException("Rclone not connected")
                            // Parent dirs already created by ensureDestParent above.
                            // Copy via temp file
                            val tempFile = java.io.File(appContext.cacheDir, "rclone_ul_$fileName")
                            try {
                                appContext.contentResolver.openInputStream(item.uri)?.use { input ->
                                    tempFile.outputStream().use { input.copyTo(it) }
                                }
                                rcloneClient.copyFile(tempFile.parent!!, tempFile.name, remote, destPath)
                            } finally {
                                tempFile.delete()
                            }
                        } else if (_isSmbProfile.value) {
                            val client = activeSmbClient ?: throw IllegalStateException("SMB not connected")
                            appContext.contentResolver.openInputStream(item.uri)?.use { input ->
                                client.upload(input, destPath, fileSize) { _, _ -> }
                            }
                        } else {
                            val session = getOrOpenSession(profileId) ?: throw IllegalStateException("Not connected")
                            appContext.contentResolver.openInputStream(item.uri)?.use { input ->
                                session.upload(input, fileSize, destPath) { _, _ -> }
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
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                backend.mkdir(fullPath)
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
     * Resolve a [FileBackend] for a paste destination described by queue-row
     * coordinates. Mirrors the legacy per-backend dispatch: LOCAL needs no
     * session, SFTP reuses/opens the cached session (with mosh/ET fallback),
     * SMB uses the active client, rclone the row's remote.
     */
    private fun destFileBackend(
        destType: BackendType,
        destProfileId: String,
        destRemote: String?,
    ): FileBackend? = when (destType) {
        BackendType.LOCAL -> LocalFileBackend(appContext)
        BackendType.SFTP -> getOrOpenSession(destProfileId)?.let { s -> SftpTransport({ s }) }
        BackendType.SMB -> activeSmbClient?.let { SmbFileBackend(it) }
        BackendType.RCLONE -> destRemote?.let { RcloneFileBackend(rcloneClient, it, appContext) }
    }

    /**
     * Probe the destination backend for an existing file at [destPath].
     *
     * Returns the size in bytes if a regular file exists, null if the path is
     * absent, a directory, or the probe itself failed (treat as "not known to
     * exist" so we default to the existing silent-overwrite behaviour rather
     * than blocking the paste on a flaky backend).
     */
    private suspend fun probeDestFile(
        destType: BackendType,
        destProfileId: String,
        destRemote: String?,
        destPath: String,
    ): Long? = try {
        destFileBackend(destType, destProfileId, destRemote)
            ?.stat(destPath)
            ?.takeIf { !it.isDirectory }
            ?.size
    } catch (_: Exception) {
        null // stat throws for missing entries; fail open on probe errors
    }

    /**
     * Generate a Windows-style unique destination path by appending `(1)`, `(2)`
     * etc. before any extension, looping until [probeDestFile] returns null.
     * Falls back to the original path after 999 attempts (which would also be
     * a sign something else is wrong).
     */
    private suspend fun findUniqueName(
        destType: BackendType,
        destProfileId: String,
        destRemote: String?,
        destPath: String,
    ): String {
        for (i in 1..999) {
            val candidate = uniqueNameCandidate(destPath, i)
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
     * Listing function for the clipboard's source backend, used by the
     * paste walker. LOCAL stays on raw [java.io.File] listing —
     * [LocalFileBackend.list] falls back to the synthetic storage-roots
     * view for unreadable paths, which must never leak into a recursive
     * copy. An unavailable session/client skips the subtree (empty list),
     * matching the legacy walker.
     */
    private fun sourceLister(cb: FileClipboard): suspend (String) -> List<SftpEntry> =
        when (cb.sourceBackendType) {
            BackendType.LOCAL -> { path: String ->
                java.io.File(path).listFiles()?.map { f ->
                    SftpEntry(f.name, f.absolutePath, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified() / 1000, "")
                } ?: emptyList()
            }
            BackendType.RCLONE -> { path: String ->
                RcloneFileBackend(rcloneClient, cb.sourceRemoteName!!, appContext).list(path)
            }
            BackendType.SFTP -> { path: String ->
                val session = cb.sourceSftpSession ?: sessionManager.openSftpSession(cb.sourceProfileId)
                session?.let { SftpTransport({ it }).list(path) } ?: emptyList()
            }
            BackendType.SMB -> { path: String ->
                val client = cb.sourceSmbClient ?: smbSessionManager.getClientForProfile(cb.sourceProfileId)
                client?.let { SmbFileBackend(it).list(path) } ?: emptyList()
            }
        }

    /**
     * Pre-walk the clipboard entries to count total leaf files. Best-effort:
     * any listing error yields a partial count (or zero for that subtree),
     * which is fine — the UI falls back to "Uploading N:" without a total.
     */
    private suspend fun countPasteFiles(cb: FileClipboard): Int {
        val lister = sourceLister(cb)
        return cb.entries.sumOf { entry ->
            try {
                walkPasteLeaves(listOf(entry), "", lister).size
            } catch (e: Exception) {
                Log.w(TAG, "Pre-walk failed for ${entry.path}: ${e.message}")
                0
            }
        }
    }

    /** Flatten the clipboard into ordered leaf paste operations (see [walkPasteLeaves]). */
    private suspend fun enumerateLeaves(cb: FileClipboard, destRootPath: String): List<PasteLeaf> =
        walkPasteLeaves(cb.entries, destRootPath, sourceLister(cb))

    /**
     * mkdir-p for the parent of [destPath] on the destination backend.
     * Best-effort — a failure is logged and left for the copy itself to
     * surface, matching the legacy behaviour.
     */
    private suspend fun ensureDestParent(
        destType: BackendType,
        destProfileId: String,
        destRemote: String?,
        destPath: String,
    ) {
        val parent = destPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it != "/" }
            ?: return
        try {
            destFileBackend(destType, destProfileId, destRemote)?.mkdir(parent)
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
                    val session = sessionManager.openSftpSession(sourceProfileId)
                    if (session != null) kotlinx.coroutines.runBlocking { session.rm(sourcePath) }
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
                    enumerateLeaves(cb, destPath)
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
            is SshIoException,
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
    private suspend fun crossCopyFile(
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
                    // LOCAL → LOCAL paste: progress and transfer offsets
                    // coincide — the queue cursor counts both bytes
                    // already shown to the user *and* bytes already on
                    // disk at the destination.
                    writeLocalFileWithProgress(
                        source = srcFile,
                        destPath = destPath,
                        total = total,
                        progressOffset = resumeFromByte,
                        transferOffset = resumeFromByte,
                        label = label,
                    )
                }
                BackendType.RCLONE -> {
                    // rclone's copyFile is opaque — we can't surface per-byte
                    // progress, so leave the bar at the known size/0 set by
                    // beforePasteFile.
                    rcloneClient.copyFile(srcFile.parent!!, srcFile.name, destRemote!!, destPath)
                }
                BackendType.SFTP -> {
                    val session = getOrOpenSession(destProfileId) ?: throw IllegalStateException("SFTP not connected")
                    srcFile.inputStream().use { input ->
                        val mode = if (resumeFromByte > 0) SftpWriteMode.RESUME else SftpWriteMode.OVERWRITE
                        // RESUME mode: the underlying library skips source up to
                        // destination size and appends the remainder, so we pass
                        // the full stream (no caller-side skip).
                        session.upload(
                            input,
                            sizeHint = total,
                            destPath = destPath,
                            mode = mode,
                            onBytes = sftpProgressCallback(label, total, resumeFromByte),
                        )
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
                    val session = cb.sourceSftpSession
                        ?: sessionManager.openSftpSession(cb.sourceProfileId)
                        ?: throw IllegalStateException("SFTP not connected")
                    tempFile.outputStream().use { out ->
                        session.download(
                            srcPath = entry.path,
                            output = out,
                            onBytes = sftpProgressCallback("\u2B07 $baseLabel", knownSize * 2, 0),
                        )
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
                    // Phase 2 of REMOTE \u2192 LOCAL paste: the temp file
                    // already holds the full source. Seed the progress
                    // bar past the download half (`progressOffset =
                    // downloaded`) but write the temp file's full
                    // contents to the destination
                    // (`transferOffset = 0`). GH#142.
                    writeLocalFileWithProgress(
                        source = tempFile,
                        destPath = destPath,
                        total = downloaded * 2,
                        progressOffset = downloaded,
                        transferOffset = 0L,
                        label = "\u2B06 $baseLabel",
                    )
                }
                BackendType.RCLONE -> {
                    rcloneClient.copyFile(tempFile.parent!!, tempFile.name, destRemote!!, destPath)
                }
                BackendType.SFTP -> {
                    val session = getOrOpenSession(destProfileId) ?: throw IllegalStateException("SFTP not connected")
                    tempFile.inputStream().use { input ->
                        val mode = if (resumeFromByte > 0) SftpWriteMode.RESUME else SftpWriteMode.OVERWRITE
                        session.upload(
                            input,
                            sizeHint = downloaded,
                            destPath = destPath,
                            mode = mode,
                            onBytes = sftpProgressCallback("\u2B06 $baseLabel", downloaded * 2, downloaded),
                        )
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
     * Lambda for [SftpSession.upload] / [SftpSession.download] `onBytes` that
     * updates [_transferProgress] with the same label/total throughout a
     * single put or get call. Pre-seeds the running byte counter with
     * [initialTransferred] so resume-mode transfers show a continuous bar
     * from destination-already-has-this-many-bytes rather than restarting
     * at zero.
     */
    private fun sftpProgressCallback(
        label: String,
        total: Long,
        initialTransferred: Long,
    ): (Long, Long) -> Unit = { transferred, _ ->
        val effective = initialTransferred + transferred
        _transferProgress.value = TransferProgress(label, total, effective)
        maybePersistQueueProgress(effective)
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
    /**
     * Stream [source] into [destPath] with progress updates. The two
     * offset parameters cover separate concerns:
     *
     * - [progressOffset] seeds the progress bar past bytes that an
     *   earlier phase already accounted for (e.g. the download phase
     *   of a cross-backend copy). Display only.
     * - [transferOffset] is a real resume cursor: when non-zero the
     *   source stream is skipped past it and the destination opens in
     *   append mode, so a crashed paste retried from the queue picks
     *   up where it left off.
     *
     * GH#142 was the conflation of the two: the phase-2 call site
     * passed the temp file's size as `resumeFromByte`, which got
     * interpreted as a transfer-skip offset and dropped every byte of
     * the payload on the floor.
     */
    private fun writeLocalFileWithProgress(
        source: java.io.File,
        destPath: String,
        total: Long,
        progressOffset: Long,
        transferOffset: Long,
        label: String,
    ) {
        _transferProgress.value = TransferProgress(label, total, progressOffset)
        if (transferOffset == 0L) {
            try {
                writeFileWithOptionalResume(source, destPath, 0L) { written ->
                    val displayed = progressOffset + written
                    _transferProgress.value = TransferProgress(label, total, displayed)
                    maybePersistQueueProgress(displayed)
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
        // Resume case: append mode + source skip. MediaStore append
        // isn't supported, so this path doesn't fall back — only the
        // first attempt for a path can hit MediaStore.
        writeFileWithOptionalResume(source, destPath, transferOffset) { written ->
            val displayed = progressOffset + written
            _transferProgress.value = TransferProgress(label, total, displayed)
            maybePersistQueueProgress(displayed)
        }
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
                val session = cb.sourceSftpSession
                    ?: sessionManager.openSftpSession(cb.sourceProfileId) ?: return
                kotlinx.coroutines.runBlocking {
                    if (entry.isDirectory) session.rmdir(entry.path) else session.rm(entry.path)
                }
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

                // SSH/SFTP calls below open or write to channels over the
                // network, so they must run off the Main dispatcher.
                //
                // Probe for a home directory: SFTP exposes it cheaply via
                // SftpSession.home(); when SFTP is unavailable (SCP-only
                // servers) we shell out to `echo "$HOME"` over exec instead.
                // Fall back to "/" as a last resort.
                val home: String = withContext(Dispatchers.IO) {
                    val sftpSessionForHome = sessionManager.openSftpSession(profileId)
                        ?: openMoshSftpSession(profileId)
                    if (sftpSessionForHome != null) {
                        sftpSession = sftpSessionForHome
                        sftpSessionForHome.home()
                    } else {
                        val ssh = sessionManager.getSshClientForProfile(profileId)
                            ?: throw IllegalStateException("Session not connected")
                        val probe = ssh.execCommand("echo \"\$HOME\"")
                        probe.stdout.trim().ifEmpty { "/" }
                    }
                }

                // Land on the requested path when one was supplied (e.g.
                // NavigateToSftpPath to a USB mount's /mnt/sda1); otherwise the
                // SFTP/SCP home directory. The `path` arg was previously dead —
                // this is what lets a freshly-selected profile open directly on
                // a target dir instead of home.
                val landing = path.takeIf { it.isNotBlank() && it != "/" } ?: home
                _currentPath.value = landing
                val transport = currentSshTransport() ?: throw IllegalStateException("Session not connected")
                val results = transport.list(landing)
                _allEntries.value = sortEntries(results, _sortMode.value)
                applyFilter()
            } catch (e: SshIoException) {
                Log.w(TAG, "SFTP open failed; resetting channel and retrying", e)
                resetSftpChannel()
                try {
                    val transport = currentSshTransport()
                        ?: throw IllegalStateException("Session not connected after reset")
                    val landing = _currentPath.value
                    val results = transport.list(landing)
                    _allEntries.value = sortEntries(results, _sortMode.value)
                    applyFilter()
                } catch (e2: Exception) {
                    Log.e(TAG, "SFTP open failed after retry", e2)
                    _error.value = "SFTP connection lost — please try again"
                }
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

    /**
     * Resolve the [FileBackend] for the active profile — local, SMB, rclone,
     * or SSH — and surface the SFTP→SCP fallback announcement / transport
     * badge as a side effect. Listing dispatches through here regardless of
     * backend; per-backend code paths only exist for upload, download,
     * mkdir, rename, delete, chmod and chown (issue #126 stages 2+).
     */
    private suspend fun currentFileBackend(): sh.haven.feature.sftp.transport.FileBackend? {
        val profileId = _activeProfileId.value ?: return null
        val resolution = withContext(Dispatchers.IO) {
            transportSelector.resolveFileBackend(profileId)
        } ?: return null
        resolution.announceFallback?.let { _message.value = it }
        _activeTransportLabel.value = resolution.backend.label
        return resolution.backend
    }

    /**
     * Mutex serialising directory listings so two concurrent navigations
     * don't share the underlying [ChannelSftp]. JSch's `ChannelSftp` is
     * not thread-safe — overlapping `ls` calls corrupt the internal
     * packet-buffer state, surfacing as
     * `IndexOutOfBoundsException` from `PipedInputStream.read`. Rapid
     * back-navigation (three taps in a row hitting `navigateUp`) is the
     * common trigger: the user reported it via #144.
     */
    private val listMutex = Mutex()

    /** Latest in-flight listing job; cancelled when a new navigation arrives. */
    private var listJob: Job? = null

    private fun loadDirectoryEntries(path: String) {
        // Cancel anything queued on the mutex from a previous tap so
        // we don't accumulate stale work behind the in-flight call.
        listJob?.cancel()
        listJob = viewModelScope.launch {
            listMutex.withLock {
                try {
                    if (!pasteInProgress.get()) _loading.value = true
                    val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                    val results = backend.list(path)
                    _allEntries.value = sortEntries(results, _sortMode.value)
                    applyFilter()
                } catch (e: CancellationException) {
                    throw e // propagate normal cancellation
                } catch (e: IndexOutOfBoundsException) {
                    // JSch's ChannelSftp.ls surfaces buffer corruption as
                    // IOOB (PipedInputStream.read with a negative length).
                    // Once the packet stream is desynchronised, every
                    // subsequent op on the same channel fails — close it
                    // so getOrOpenChannel rebuilds a fresh one next time.
                    Log.w(TAG, "SFTP channel corrupt at path='$path'; resetting", e)
                    resetSftpChannel()
                    _error.value = "SFTP channel error — please try again"
                } catch (e: SshIoException) {
                    // JSch's ChannelSftp.fill() throws IOException("inputstream is closed")
                    // when the underlying stream read returns <= 0 — a transient
                    // connection glitch or a slow server (mwiede/jsch#858). Reset the
                    // channel and retry once before surfacing the error to the user.
                    Log.w(TAG, "SFTP list failed at path='$path'; resetting and retrying", e)
                    resetSftpChannel()
                    try {
                        val backend = currentFileBackend()
                            ?: throw IllegalStateException("Not connected after reset")
                        val results = backend.list(path)
                        _allEntries.value = sortEntries(results, _sortMode.value)
                        applyFilter()
                    } catch (e2: Exception) {
                        Log.e(TAG, "List directory failed after retry: path='$path'", e2)
                        _error.value = "SFTP connection lost — please try again"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "List directory failed: path='$path'", e)
                    _error.value = "Failed to list: ${e.message}"
                } finally {
                    if (!pasteInProgress.get()) _loading.value = false
                }
            }
        }
    }

    private fun resetSftpChannel() {
        sftpSession?.close()
        sftpSession = null
    }

    private fun getOrOpenSession(profileId: String): SftpSession? {
        sftpSession?.let { if (it.isConnected) return it }
        // Try SSH session first, then mosh/ET bootstrap SSH client
        val session = sessionManager.openSftpSession(profileId)
            ?: openMoshSftpSession(profileId)
            ?: openEtSftpSession(profileId)
            ?: return null
        sftpSession = session
        return session
    }

    private fun openMoshSftpSession(profileId: String): SftpSession? {
        val client = moshSessionManager.getSshClientForProfile(profileId) as? SshClient
            ?: return null
        return try {
            client.openSftpSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SFTP session via mosh SSH client", e)
            null
        }
    }

    private fun openEtSftpSession(profileId: String): SftpSession? {
        val client = etSessionManager.getSshClientForProfile(profileId) as? SshClient
            ?: return null
        return try {
            client.openSftpSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SFTP session via ET SSH client", e)
            null
        }
    }

    private fun openSmbAndList(profileId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = smbSessionManager.getClientForProfile(profileId)
                        ?: throw IllegalStateException("SMB session not connected")
                    activeSmbClient = client
                }
                _currentPath.value = "/"
                loadDirectoryEntries("/")
            } catch (e: Exception) {
                Log.e(TAG, "SMB open failed", e)
                _error.value = "SMB failed: ${e.message}"
            }
        }
    }

    // ── Rclone helpers ────────────────────────────────────────────────

    private fun openRcloneAndList(profileId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val remoteName = rcloneSessionManager.getRemoteNameForProfile(profileId)
                    Log.d(TAG, "openRcloneAndList: profileId=$profileId, remoteName=$remoteName, " +
                        "isConnected=${rcloneSessionManager.isProfileConnected(profileId)}")
                    if (remoteName == null) throw IllegalStateException("Rclone session not connected")
                    activeRcloneRemote = remoteName
                    try { _remoteCapabilities.value = rcloneClient.getCapabilities(remoteName) }
                    catch (_: Exception) { _remoteCapabilities.value = sh.haven.core.rclone.RemoteCapabilities() }
                }
                _currentPath.value = "/"
                loadDirectoryEntries("/")
            } catch (e: Exception) {
                Log.e(TAG, "Rclone open failed", e)
                _error.value = "Cloud storage failed: ${e.message}"
            }
        }
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
                // One loopback URL per backend — rclone's media server, or the
                // SftpStreamServer bridge for SFTP/SMB (VISION §2). The external
                // player reads it over the device's own 127.0.0.1, Range-capable.
                val url = resolveStreamInput(entry)
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

    /**
     * Hand a file off to another installed app via an ACTION_VIEW chooser
     * ("Open with…", issue #173). Unlike [streamFile]/[playInBrowser] this
     * serves the file's *raw original bytes* over the existing loopback HTTP
     * bridge (no ffmpeg/HLS transcode) so a player like VLC/mpv can decode
     * containers Haven's built-in MediaCodec path can't, and non-media files
     * (.pdf/.epub/.cbz) open in their own viewer. The file is streamed
     * on-demand (HTTP Range) — never fully downloaded first.
     *
     * The loopback URL we publish carries [SftpStreamServer]'s secret token in
     * its path; we hand the external app the full URL, so it replays the token
     * on its own GET and the server accepts it (other apps can't guess it).
     */
    fun openWithExternalApp(entry: SftpEntry) {
        if (entry.isDirectory) return
        viewModelScope.launch {
            try {
                val mime = mimeTypeForName(entry.name)
                val data: android.net.Uri
                var grantRead = false
                when {
                    _isLocalProfile.value -> {
                        // Local backend has a real file → content:// via FileProvider.
                        val file = java.io.File(entry.path)
                        data = androidx.core.content.FileProvider.getUriForFile(
                            appContext, "${appContext.packageName}.fileprovider", file,
                        )
                        grantRead = true
                    }
                    _isRcloneProfile.value -> {
                        val port = ensureMediaServer()
                        val encodedPath = entry.path
                            .trimStart('/')
                            .split('/')
                            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                        data = android.net.Uri.parse("http://127.0.0.1:$port/$encodedPath")
                    }
                    _isSmbProfile.value -> {
                        // SMB via the loopback HTTP bridge (raw bytes, Range-capable) — VISION §2.
                        val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                        val urlPath = sftpStreamServer.publish(
                            path = entry.path,
                            size = entry.size,
                            contentType = mime,
                            opener = smbOpener(entry.path),
                            concurrentSafe = true,
                        )
                        data = android.net.Uri.parse("http://127.0.0.1:$port$urlPath")
                    }
                    else -> {
                        // SFTP via the loopback HTTP bridge (raw bytes, Range-capable).
                        val profileId = _activeProfileId.value
                            ?: throw IllegalStateException("No active profile")
                        val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                        val urlPath = sftpStreamServer.publish(
                            path = entry.path,
                            size = entry.size,
                            contentType = mime,
                            opener = sftpOpener(profileId, entry.path),
                        )
                        data = android.net.Uri.parse("http://127.0.0.1:$port$urlPath")
                    }
                }
                Log.d(TAG, "openWithExternalApp: ${entry.name} -> $data ($mime)")
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(data, mime)
                    if (grantRead) addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = android.content.Intent
                    .createChooser(view, appContext.getString(R.string.sftp_open_with))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (grantRead) chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                appContext.startActivity(chooser)
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "openWithExternalApp: no activity to handle ${entry.name}", e)
                _error.value = appContext.getString(R.string.sftp_no_app_to_open)
            } catch (e: Exception) {
                Log.e(TAG, "openWithExternalApp failed for ${entry.name}", e)
                _error.value = "Open with… failed: ${e.message}"
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
        // #277: a sync is config-driven and needs no open connection. When an rclone
        // profile is being browsed, pre-fill its remote+path as the source; otherwise
        // open with an empty source so the user picks both ends from the remotes
        // dropdown (which lists all configured remotes regardless of what's open).
        val remote = activeRcloneRemote
        _syncDialogSource.value = if (remote != null) "$remote:${sourcePath ?: _currentPath.value}" else ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rcloneClient.initialize() // idempotent; loads rclone.conf so listRemotes works with nothing open
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
        // If the dialog ran from a saved profile, mark it as freshly used
        // so it floats to the top of the dropdown on next observe.
        _activeSavedSyncProfileId.value?.let { id ->
            viewModelScope.launch { syncProfileRepository.touchLastRun(id) }
        }
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            // lastError needs to survive past the polling loop so we can log
            // it even when the job ended with rclone's "errors > 0,
            // deletes == 0" suppression — `_syncProgress` only carries the
            // public progress shape so we keep this here.
            var lastErrorMessage = ""
            try {
                withContext(Dispatchers.IO) { rcloneClient.resetStats() }

                val jobId = withContext(Dispatchers.IO) { rcloneClient.startSync(config) }

                // Poll progress until finished
                while (true) {
                    delay(500)
                    val status = withContext(Dispatchers.IO) { rcloneClient.getJobStatus(jobId) }
                    val stats = withContext(Dispatchers.IO) { rcloneClient.getStats() }
                    if (stats.lastError.isNotEmpty()) lastErrorMessage = stats.lastError

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
                        deletes = stats.deletes,
                        deletedDirs = stats.deletedDirs,
                        finished = status.finished,
                        success = status.success,
                        errorMessage = status.error,
                        dryRun = config.dryRun,
                    )

                    if (status.finished) break
                }

                val final = _syncProgress.value
                // Recover the per-file errors (with their paths) before the
                // next sync's resetStats() clears them — lastErrorMessage only
                // has rclone's bare message, not the offending file name (#157).
                val erroredFiles = if ((final?.errors ?: 0) > 0) {
                    withContext(Dispatchers.IO) { rcloneClient.getErroredTransfers() }
                } else emptyList()
                _syncProgress.value = null
                rcloneClient.activeSyncJobId = null

                if (config.dryRun) {
                    val files = final?.totalTransfers ?: 0
                    val bytes = android.text.format.Formatter.formatFileSize(appContext, final?.totalBytes ?: 0)
                    _dryRunResult.value = "Would transfer $files files ($bytes)"
                } else if (final?.success == true) {
                    // The Mirror-deletions-suppressed case (#157): rclone
                    // reports "success" but skips the entire delete pass
                    // when any source file errored. Detect with
                    // mode=SYNC, errors > 0, deletes == 0 *and* there were
                    // orphans (which we can't know directly, but a sync
                    // with zero deletes and >0 errors is almost always
                    // this path) — call it out in both the toast and the
                    // connection log entry.
                    val deletionsSuppressed = config.mode == sh.haven.core.rclone.SyncMode.SYNC &&
                        final.errors > 0 && final.deletes == 0 && final.deletedDirs == 0
                    val parts = buildList {
                        add("${final.transfersCompleted} files transferred")
                        if (final.deletes > 0 || final.deletedDirs > 0) {
                            val files = final.deletes
                            val dirs = final.deletedDirs
                            val deletedStr = when {
                                files > 0 && dirs > 0 -> "$files files, $dirs dirs deleted"
                                files > 0 -> "$files files deleted"
                                else -> "$dirs dirs deleted"
                            }
                            add(deletedStr)
                        }
                        if (final.errors > 0) add("${final.errors} errors")
                        if (deletionsSuppressed) add("deletions skipped — source errors")
                    }
                    _message.value = "Sync complete: ${parts.joinToString(", ")}"
                    refresh()
                    logSyncToConnectionLog(
                        config = config,
                        startedAt = startedAt,
                        final = final,
                        lastErrorMessage = lastErrorMessage,
                        erroredFiles = erroredFiles,
                        deletionsSuppressed = deletionsSuppressed,
                        success = true,
                    )
                } else {
                    _error.value = "Sync failed: ${final?.errorMessage ?: "unknown error"}"
                    logSyncToConnectionLog(
                        config = config,
                        startedAt = startedAt,
                        final = final,
                        lastErrorMessage = lastErrorMessage,
                        erroredFiles = erroredFiles,
                        deletionsSuppressed = false,
                        success = false,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                _syncProgress.value = null
                rcloneClient.activeSyncJobId = null
                _error.value = "Sync failed: ${e.message}"
                logSyncToConnectionLog(
                    config = config,
                    startedAt = startedAt,
                    final = null,
                    lastErrorMessage = e.message ?: lastErrorMessage,
                    deletionsSuppressed = false,
                    success = false,
                )
            }
        }
    }

    /**
     * Persist an audit-log entry summarising the just-finished sync job so
     * users can review it later under Settings → View connection log. Keyed
     * by the source profile id when the srcFs's remote name matches a
     * stored profile (falls back to dst, then to the active rclone
     * profile). Skips silently if no rclone-typed profile is reachable —
     * the entry would violate the FK on ConnectionLog.profileId. (#158)
     */
    private suspend fun logSyncToConnectionLog(
        config: SyncConfig,
        startedAt: Long,
        final: SyncProgress?,
        lastErrorMessage: String,
        erroredFiles: List<sh.haven.core.rclone.TransferError> = emptyList(),
        deletionsSuppressed: Boolean,
        success: Boolean,
    ) {
        val profileId = resolveSyncProfileId(config) ?: return
        val durationMs = System.currentTimeMillis() - startedAt
        val modeLabel = when (config.mode) {
            sh.haven.core.rclone.SyncMode.COPY -> "Copy"
            sh.haven.core.rclone.SyncMode.SYNC -> "Mirror"
            sh.haven.core.rclone.SyncMode.MOVE -> "Move"
        }
        val transfers = final?.transfersCompleted ?: 0
        val deletes = (final?.deletes ?: 0) + (final?.deletedDirs ?: 0)
        val errors = final?.errors ?: 0
        val detailsParts = buildList {
            add("$modeLabel: $transfers transferred")
            if (deletes > 0) add("$deletes deleted")
            if (errors > 0) add("$errors errors")
            if (deletionsSuppressed) add("deletions skipped (source errors)")
            // Name the first offending file in the collapsed summary so the
            // user sees *what* failed without expanding the entry (#157).
            erroredFiles.firstOrNull()?.let { add("first failed: ${it.name}") }
            if (!success && final?.errorMessage != null) add(final.errorMessage)
        }
        val verboseLog = buildString {
            appendLine("Mode:        $modeLabel (${config.mode.rcMethod})")
            appendLine("Source:      ${config.srcFs}")
            appendLine("Destination: ${config.dstFs}")
            if (config.dryRun) appendLine("Dry run:     yes")
            with(config.filters) {
                if (includePatterns.isNotEmpty()) appendLine("Include:     ${includePatterns.joinToString(", ")}")
                if (excludePatterns.isNotEmpty()) appendLine("Exclude:     ${excludePatterns.joinToString(", ")}")
                minSize?.let { appendLine("Min size:    $it") }
                maxSize?.let { appendLine("Max size:    $it") }
                bandwidthLimit?.let { appendLine("Bw limit:    $it") }
            }
            appendLine()
            appendLine("Transfers:   $transfers / ${final?.totalTransfers ?: 0}")
            appendLine("Bytes:       ${final?.bytes ?: 0} / ${final?.totalBytes ?: 0}")
            appendLine("Deletes:     ${final?.deletes ?: 0} files, ${final?.deletedDirs ?: 0} dirs")
            appendLine("Errors:      $errors")
            if (deletionsSuppressed) {
                appendLine()
                appendLine("rclone skipped the delete pass because of source errors.")
                appendLine("Extras in the destination were not removed. This matches")
                appendLine("rclone's internal `not deleting files as there were IO errors`")
                appendLine("behaviour — fix the failing source files and re-run to")
                appendLine("complete the mirror.")
            }
            if (erroredFiles.isNotEmpty()) {
                appendLine()
                appendLine("Failed files (${erroredFiles.size}):")
                erroredFiles.take(50).forEach { appendLine("  ${it.name}: ${it.error}") }
                if (erroredFiles.size > 50) appendLine("  … and ${erroredFiles.size - 50} more")
            }
            if (lastErrorMessage.isNotEmpty()) {
                appendLine()
                appendLine("Last error:  $lastErrorMessage")
            }
        }
        connectionLogRepository.logEvent(
            profileId = profileId,
            status = if (success) ConnectionLog.Status.SYNC_OK else ConnectionLog.Status.SYNC_FAILED,
            durationMs = durationMs,
            details = detailsParts.joinToString(", "),
            verboseLog = verboseLog,
        )
    }

    /**
     * Find a ConnectionProfile to key this sync's log entry to. Walks
     * srcFs, then dstFs, parsing the rclone-style "remote:" prefix and
     * matching against [ConnectionProfile.rcloneRemoteName]. Falls back to
     * the currently active rclone profile if neither side resolves.
     */
    private suspend fun resolveSyncProfileId(config: SyncConfig): String? {
        val remoteNames = listOfNotNull(parseRcloneRemoteName(config.srcFs), parseRcloneRemoteName(config.dstFs))
        val profiles = repository.getAll()
        if (remoteNames.isNotEmpty()) {
            for (remote in remoteNames) {
                profiles.firstOrNull { it.isRclone && it.rcloneRemoteName == remote }?.let { return it.id }
            }
        }
        // Neither side names a known rclone remote (both are `:local:` or
        // bare paths, or the remote isn't stored as a profile yet). Fall
        // back to the currently-browsed profile if it's rclone-typed —
        // logging an rclone sync under an unrelated SSH/SFTP profile
        // would clutter that profile's audit history with off-topic
        // events. Skip if no candidate is found.
        val active = _activeProfileId.value
        return active?.let { id -> profiles.firstOrNull { it.id == id && it.isRclone } }?.id
    }

    /**
     * Extract the remote name from an rclone fs spec, e.g. "gdrive:Backup"
     * → "gdrive". Returns null for paths that don't carry a named remote
     * (the anonymous `:local:` backend, bare paths, etc.) since those have
     * no matching ConnectionProfile to log against.
     */
    private fun parseRcloneRemoteName(fs: String): String? {
        if (fs.isEmpty() || fs.startsWith(":")) return null
        val idx = fs.indexOf(':')
        if (idx <= 0) return null
        return fs.substring(0, idx)
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
