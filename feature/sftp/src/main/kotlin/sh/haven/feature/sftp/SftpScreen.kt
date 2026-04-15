package sh.haven.feature.sftp

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import sh.haven.core.rclone.SyncConfig
import sh.haven.core.rclone.SyncFilters
import sh.haven.core.rclone.SyncMode
import sh.haven.feature.sftp.SftpViewModel.Companion.isMediaFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SftpScreen(
    pendingSmbProfileId: String? = null,
    pendingRcloneProfileId: String? = null,
    viewModel: SftpViewModel = hiltViewModel(),
) {
    val connectedProfiles by viewModel.connectedProfiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val lastDownload by viewModel.lastDownload.collectAsState()
    val uploadConflict by viewModel.uploadConflict.collectAsState()
    val fileClipboard by viewModel.clipboard.collectAsState()
    val isRclone by viewModel.isRcloneProfile.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val showSyncDialog by viewModel.showSyncDialog.collectAsState()
    val syncDialogSource by viewModel.syncDialogSource.collectAsState()
    val availableRemotes by viewModel.availableRemotes.collectAsState()
    val dryRunResult by viewModel.dryRunResult.collectAsState()
    val hasMediaFiles by viewModel.hasMediaFiles.collectAsState()
    val mediaExtensions by viewModel.mediaExtensionsSet.collectAsState()
    val capabilities by viewModel.remoteCapabilities.collectAsState()
    val folderSizeResult by viewModel.folderSizeResult.collectAsState()
    val folderSizeLoading by viewModel.folderSizeLoading.collectAsState()
    val dlnaRunning by viewModel.dlnaServerRunning.collectAsState()
    val previewState by viewModel.previewState.collectAsState()
    val previewDuration by viewModel.previewDuration.collectAsState()
    val convertDialogEntry by viewModel.convertDialogEntry.collectAsState()
    val mediaSheetEntry by viewModel.mediaSheetEntry.collectAsState()
    val trimDialogEntry by viewModel.trimDialogEntry.collectAsState()
    val extractAudioDialogEntry by viewModel.extractAudioDialogEntry.collectAsState()
    val contactSheetDialogEntry by viewModel.contactSheetDialogEntry.collectAsState()
    val mediaInfoState by viewModel.mediaInfoState.collectAsState()
    val showFullscreenPreview by viewModel.showFullscreenPreview.collectAsState()
    val audioPreviewState by viewModel.audioPreviewState.collectAsState()
    val inputHasVideo by viewModel.inputHasVideo.collectAsState()
    val previewIsRemote by viewModel.previewIsRemote.collectAsState()

    var showRenameDialog by remember { mutableStateOf<SftpEntry?>(null) }

    LaunchedEffect(pendingSmbProfileId) {
        pendingSmbProfileId?.let { viewModel.setPendingSmbProfile(it) }
    }

    LaunchedEffect(pendingRcloneProfileId) {
        pendingRcloneProfileId?.let { viewModel.setPendingRcloneProfile(it) }
    }

    viewModel.syncConnectedProfiles()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(lastDownload) {
        val dl = lastDownload ?: return@LaunchedEffect
        viewModel.dismissMessage() // clear the plain message so it doesn't double-show

        // Auto-install APK files directly
        if (dl.fileName.endsWith(".apk", ignoreCase = true)) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(dl.uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                @Suppress("LocalContextGetResourceValueCall")
                snackbarHostState.showSnackbar("Install failed: ${e.message}")
            }
            viewModel.clearLastDownload()
            return@LaunchedEffect
        }

        @Suppress("LocalContextGetResourceValueCall")
        val downloadedMessage = context.getString(R.string.sftp_downloaded, dl.fileName)
        @Suppress("LocalContextGetResourceValueCall")
        val openLabel = context.getString(R.string.sftp_open)
        val result = snackbarHostState.showSnackbar(
            message = downloadedMessage,
            actionLabel = openLabel,
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            try {
                val mimeType = context.contentResolver.getType(dl.uri) ?: "*/*"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(dl.uri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                @Suppress("LocalContextGetResourceValueCall")
                snackbarHostState.showSnackbar(context.getString(R.string.sftp_no_app_to_open))
            }
        }
        viewModel.clearLastDownload()
    }

    LaunchedEffect(message) {
        // Only show plain messages when there's no download result (download has its own snackbar)
        val msg = message ?: return@LaunchedEffect
        if (lastDownload == null) {
            snackbarHostState.showSnackbar(msg)
        }
        viewModel.dismissMessage()
    }

    // File picker for upload
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Query the actual display name from the content resolver
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
            viewModel.uploadFile(fileName, uri)
        }
    }

    // New folder dialog state
    var showNewFolderDialog by remember { mutableStateOf(false) }

    // Folder upload picker
    val folderUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadFolder(uri)
        }
    }

    // Directory picker for download
    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            pendingDownload?.let { entry ->
                viewModel.downloadFile(entry, uri)
            }
        }
        pendingDownload = null
    }

    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    var editingPath by remember { mutableStateOf(false) }
                    var pathText by remember(currentPath) { mutableStateOf(currentPath) }
                    if (editingPath) {
                        BasicTextField(
                            value = pathText,
                            onValueChange = { pathText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                editingPath = false
                                viewModel.navigateTo(pathText)
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            currentPath,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { editingPath = true },
                        )
                    }
                },
                navigationIcon = {
                    if (currentPath != "/" && activeProfileId != null) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.sftp_navigate_up))
                        }
                    }
                },
                actions = {
                    if (activeProfileId != null) {
                        IconButton(onClick = { viewModel.toggleShowHidden() }) {
                            Icon(
                                if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (showHidden) stringResource(R.string.sftp_hide_hidden_files) else stringResource(R.string.sftp_show_hidden_files),
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sftp_sort))
                            }
                            SortDropdown(
                                expanded = showSortMenu,
                                currentMode = sortMode,
                                onDismiss = { showSortMenu = false },
                                onSelect = { mode ->
                                    viewModel.setSortMode(mode)
                                    showSortMenu = false
                                },
                            )
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.sftp_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (activeProfileId != null) {
                var fabExpanded by remember { mutableStateOf(false) }
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(visible = fabExpanded) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                showNewFolderDialog = true
                            }) {
                                Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.sftp_new_folder))
                            }
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                folderUploadLauncher.launch(null)
                            }) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        stringResource(R.string.sftp_upload_folder),
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .padding(top = 2.dp),
                                        tint = MaterialTheme.colorScheme.primaryContainer,
                                    )
                                }
                            }
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                uploadLauncher.launch(arrayOf("*/*"))
                            }) {
                                Icon(Icons.Filled.Upload, stringResource(R.string.sftp_upload_file))
                            }
                            if (hasMediaFiles) {
                                SmallFloatingActionButton(onClick = {
                                    fabExpanded = false
                                    viewModel.playFolder()
                                }) {
                                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.sftp_play_folder))
                                }
                            }
                            if (isRclone) {
                                SmallFloatingActionButton(onClick = {
                                    fabExpanded = false
                                    viewModel.showSyncDialog()
                                }) {
                                    Icon(Icons.Filled.Sync, stringResource(R.string.sftp_sync))
                                }
                                SmallFloatingActionButton(onClick = {
                                    fabExpanded = false
                                    viewModel.toggleDlnaServer()
                                }) {
                                    Icon(
                                        if (dlnaRunning) Icons.Filled.Stop else Icons.Filled.CastConnected,
                                        stringResource(
                                            if (dlnaRunning) R.string.sftp_stop_dlna
                                            else R.string.sftp_start_dlna
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    if (fileClipboard != null) {
                        FloatingActionButton(onClick = { viewModel.pasteFromClipboard() }) {
                            Icon(Icons.Filled.ContentPaste, stringResource(R.string.sftp_paste))
                        }
                    } else {
                        FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                            Icon(
                                when {
                                    fabExpanded -> Icons.Filled.CreateNewFolder
                                    hasMediaFiles -> Icons.Filled.PlayArrow
                                    else -> Icons.Filled.Upload
                                },
                                if (fabExpanded) stringResource(R.string.sftp_fab_close) else stringResource(R.string.sftp_fab_actions),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val sp = syncProgress
            if (sp != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (sp.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { sp.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${sp.mode.label}: ${sp.transfersCompleted}/${sp.totalTransfers} files" +
                                if (sp.errors > 0) " (${sp.errors} errors)" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${Formatter.formatFileSize(context, sp.speed.toLong())}/s  ${sp.etaFormatted}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${Formatter.formatFileSize(context, sp.bytes)} / ${Formatter.formatFileSize(context, sp.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { viewModel.cancelSync() }) {
                            Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else if (loading) {
                val progress = transferProgress
                if (progress != null && (progress.totalBytes > 0 || progress.fileName.isNotEmpty())) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (progress.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                progress.fileName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (progress.totalBytes > 0) {
                                Text(
                                    if (progress.isPercentage) "${(progress.fraction * 100).toInt()}%"
                                    else "${Formatter.formatFileSize(context, progress.transferredBytes)} / ${Formatter.formatFileSize(context, progress.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (connectedProfiles.isEmpty()) {
                EmptyState()
            } else {
                // Server tabs
                if (connectedProfiles.size > 1) {
                    val activeIndex = connectedProfiles.indexOfFirst { it.id == activeProfileId }
                        .coerceAtLeast(0)
                    PrimaryScrollableTabRow(
                        selectedTabIndex = activeIndex,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 8.dp,
                    ) {
                        connectedProfiles.forEach { profile ->
                            Tab(
                                selected = profile.id == activeProfileId,
                                onClick = { viewModel.selectProfile(profile.id) },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (profile.isLocal) {
                                            Icon(
                                                Icons.Filled.Folder,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        } else if (profile.isRclone) {
                                            Icon(
                                                Icons.Filled.Cloud,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                        Text(profile.label, maxLines = 1)
                                    }
                                },
                            )
                        }
                    }
                }

                // Storage permission banner for local file browser
                if (viewModel.needsStoragePermission) {
                    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val intent = android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    )
                                    context.startActivity(intent)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Storage access needed", style = MaterialTheme.typography.bodyMedium)
                                Text("Tap to grant access to all files", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                            }
                        }
                    }
                }

                // Clipboard banner
                fileClipboard?.let { cb ->
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (cb.isCut) Icons.Filled.ContentCut else Icons.Filled.FileCopy,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (cb.isCut) {
                                    stringResource(
                                        if (cb.entries.size > 1) R.string.sftp_items_cut_plural else R.string.sftp_items_cut,
                                        cb.entries.size,
                                    )
                                } else {
                                    stringResource(
                                        if (cb.entries.size > 1) R.string.sftp_items_copied_plural else R.string.sftp_items_copied,
                                        cb.entries.size,
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.clearClipboard() }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    }
                }

                // File list — always renders the LazyColumn so the parent-dir
                // "..\" row stays available even when the directory is empty.
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // ".." parent directory entry
                    if (currentPath != "/" && currentPath.isNotEmpty()) {
                        item(key = "__parent__") {
                            ListItem(
                                headlineContent = { Text("..") },
                                supportingContent = { Text(stringResource(R.string.sftp_parent_directory)) },
                                leadingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.sftp_navigate_up_icon),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.navigateUp() },
                            )
                        }
                    }
                    if (entries.isEmpty() && !loading) {
                        item(key = "__empty__") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.sftp_empty_directory),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    itemsIndexed(entries, key = { index, entry -> "${activeProfileId}:${index}:${entry.path}" }) { _, entry ->
                            FileListItem(
                                entry = entry,
                                onTap = {
                                    if (entry.isDirectory) {
                                        viewModel.navigateTo(entry.path)
                                    } else if (isRclone && entry.isMediaFile(mediaExtensions)) {
                                        // Rclone media file: play via the rclone HTTP
                                        // media server (no full download needed).
                                        viewModel.playMediaFile(entry)
                                    } else if (viewModel.isLocalProfile()) {
                                        // Open local file with system app
                                        try {
                                            val file = java.io.File(entry.path)
                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                context, "${context.packageName}.fileprovider", file
                                            )
                                            val ext = file.extension.lowercase()
                                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                                .getMimeTypeFromExtension(ext) ?: "*/*"
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, mime)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            scope.launch {
                                                @Suppress("LocalContextGetResourceValueCall")
                                                snackbarHostState.showSnackbar(context.getString(R.string.sftp_no_app_to_open))
                                            }
                                        }
                                    }
                                },
                                onDownload = {
                                    pendingDownload = entry
                                    downloadLauncher.launch(entry.name)
                                },
                                onDelete = { viewModel.deleteEntry(entry) },
                                onCopyPath = {
                                    clipboardManager.setText(AnnotatedString(entry.path))
                                    @Suppress("LocalContextGetResourceValueCall")
                                    val pathCopiedMsg = context.getString(R.string.sftp_path_copied)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(pathCopiedMsg)
                                    }
                                },
                                onCopy = { viewModel.copyToClipboard(listOf(entry), isCut = false) },
                                onCut = { viewModel.copyToClipboard(listOf(entry), isCut = true) },
                                onMediaSheet = if (!entry.isDirectory && entry.isMediaFile(mediaExtensions)) {
                                    { viewModel.openMediaSheet(entry) }
                                } else null,
                                onStream = if (!entry.isDirectory && entry.isMediaFile(mediaExtensions) &&
                                    !viewModel.isSmbProfile()) {
                                    { viewModel.streamFile(entry) }
                                } else null,
                                onStreamFolder = if (entry.isDirectory && !viewModel.isSmbProfile()) {
                                    { viewModel.streamFolder(entry.path) }
                                } else null,
                                onPlay = if (isRclone && entry.isMediaFile(mediaExtensions)) {
                                    { viewModel.playMediaFile(entry) }
                                } else null,
                                onSync = if (isRclone && entry.isDirectory) {
                                    { viewModel.showSyncDialog(entry.path) }
                                } else null,
                                onRename = { showRenameDialog = entry },
                                onShareLink = if (isRclone && capabilities.publicLink) {
                                    { viewModel.sharePublicLink(entry) }
                                } else null,
                                onFolderSize = if (isRclone && entry.isDirectory) {
                                    { viewModel.calculateFolderSize(entry) }
                                } else null,
                            )
                        }
                }
            }
        }
    }

    // New Folder dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.sftp_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.sftp_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewFolderDialog = false
                        viewModel.createDirectory(folderName)
                    },
                    enabled = folderName.isNotBlank(),
                ) { Text(stringResource(R.string.sftp_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // Upload conflict dialog
    uploadConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP) },
            title = { Text(stringResource(R.string.sftp_file_already_exists)) },
            text = { Text(stringResource(R.string.sftp_file_exists_message, conflict.fileName)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.REPLACE) }) {
                        Text(stringResource(R.string.sftp_replace))
                    }
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.REPLACE_ALL) }) {
                        Text(stringResource(R.string.sftp_replace_all))
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP) }) {
                        Text(stringResource(R.string.sftp_skip))
                    }
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP_ALL) }) {
                        Text(stringResource(R.string.sftp_skip_all))
                    }
                }
            },
        )
    }

    // Sync dialog
    if (showSyncDialog) {
        SyncDialog(
            source = syncDialogSource ?: "",
            remotes = availableRemotes,
            onDismiss = { viewModel.dismissSyncDialog() },
            onStart = { config -> viewModel.startSync(config) },
        )
    }

    // Dry run results
    dryRunResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDryRunResult() },
            title = { Text(stringResource(R.string.sftp_dry_run_results)) },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDryRunResult() }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }

    // Rename dialog
    showRenameDialog?.let { entry ->
        var newName by remember(entry) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text(stringResource(R.string.sftp_rename_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.sftp_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = null
                        viewModel.renameEntry(entry, newName)
                    },
                    enabled = newName.isNotBlank() && newName != entry.name,
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // Media actions bottom sheet — primary entry for ffmpeg-powered features
    mediaSheetEntry?.let { entry ->
        MediaActionsSheet(
            entry = entry,
            onDismiss = { viewModel.dismissMediaSheet() },
            onMediaInfo = { viewModel.loadMediaInfo(entry) },
            onTrim = { viewModel.openTrimDialog(entry) },
            onExtractAudio = { viewModel.openExtractAudioDialog(entry) },
            onContactSheet = { viewModel.openContactSheetDialog(entry) },
            onConvert = { viewModel.openConvertDialog(entry) },
        )
    }

    mediaInfoState?.let { state ->
        MediaInfoDialog(state = state, onDismiss = { viewModel.dismissMediaInfo() })
    }

    trimDialogEntry?.let { entry ->
        TrimDialog(
            entry = entry,
            onDismiss = { viewModel.dismissTrimDialog() },
            onConfirm = { start, end, out ->
                viewModel.dismissTrimDialog()
                viewModel.trimFile(entry, start, end, out)
            },
        )
    }

    extractAudioDialogEntry?.let { entry ->
        ExtractAudioDialog(
            entry = entry,
            onDismiss = { viewModel.dismissExtractAudioDialog() },
            onConfirm = { codec, bitrate, out ->
                viewModel.dismissExtractAudioDialog()
                viewModel.extractAudio(entry, codec, bitrate, out)
            },
        )
    }

    contactSheetDialogEntry?.let { entry ->
        ContactSheetDialog(
            entry = entry,
            onDismiss = { viewModel.dismissContactSheetDialog() },
            onConfirm = { cols, rows, tw, th, out ->
                viewModel.dismissContactSheetDialog()
                viewModel.makeContactSheet(entry, cols, rows, tw, th, out)
            },
        )
    }

    // Convert format picker + filter UI + preview
    convertDialogEntry?.let { entry ->
        val isAudioOnlyInput = !inputHasVideo

        // Container format options
        val containers = if (isAudioOnlyInput) {
            listOf("mp3" to "MP3", "wav" to "WAV", "ogg" to "OGG", "opus" to "Opus", "flac" to "FLAC", "m4a" to "M4A")
        } else {
            listOf("mp4" to "MP4", "mkv" to "MKV", "webm" to "WebM", "mov" to "MOV", "avi" to "AVI", "mpegts" to "MPEG-TS")
        }
        // Video encoder options (filtered by container)
        val videoEncoders = listOf(
            "libx264" to "H.264 (x264)", "libx265" to "H.265 (x265)",
            "libvpx-vp9" to "VP9", "libvpx" to "VP8",
            "mpeg4" to "MPEG-4", "copy" to "Copy (no re-encode)",
        )
        // Audio encoder options
        val audioEncoders = listOf(
            "aac" to "AAC", "libmp3lame" to "MP3 (LAME)", "libopus" to "Opus",
            "libvorbis" to "Vorbis", "pcm_s16le" to "PCM 16-bit",
            "flac" to "FLAC", "copy" to "Copy (no re-encode)",
        )

        // Default container matches the source extension where possible so
        // that copy+copy remux works out of the box without changing anything.
        val sourceExt = entry.name.substringAfterLast('.', "").lowercase()
        val defaultContainer = when (sourceExt) {
            // Video containers we expose
            "mp4", "mkv", "webm", "mov", "avi" -> sourceExt
            "m4v" -> "mp4"
            "ts", "mpg", "mpeg" -> "mpegts"
            // Audio containers we expose
            "mp3", "wav", "ogg", "opus", "flac", "m4a" -> sourceExt
            "aac" -> "m4a"
            else -> if (isAudioOnlyInput) "mp3" else "mp4"
        }
        var selectedContainer by rememberSaveable { mutableStateOf(defaultContainer) }
        // Default both encoders to "copy" (stream remux) — fastest, lossless,
        // works instantly when the source codecs fit the chosen container.
        // User can switch to a real encoder if they need to transcode.
        var selectedVideoEnc by rememberSaveable { mutableStateOf("copy") }
        var selectedAudioEnc by rememberSaveable { mutableStateOf("copy") }
        val filterState = rememberSaveable(saver = FilterState.Saver) { FilterState() }
        val isAudioOnly = isAudioOnlyInput
        var previewSeek by rememberSaveable { mutableFloatStateOf(0f) }
        var previewStale by rememberSaveable { mutableStateOf(false) }
        // For rclone profiles: default to streaming over HTTP (fast); user
        // can force a full download for offline conversion or reliability.
        var downloadFirst by rememberSaveable { mutableStateOf(false) }
        // Where to save the transcoded file — Downloads (local) or alongside
        // the source (which uploads back to cloud/SFTP/SMB for remote profiles).
        var destinationKey by rememberSaveable { mutableStateOf("downloads") }

        // Prepare preview (probe duration, cache remote file) on dialog open
        LaunchedEffect(entry) {
            viewModel.preparePreview(entry)
        }

        // If the probe reveals the input is audio-only AND the current container
        // is a video-only container, switch to a sensible audio container
        // matching the source extension (keeping the copy+copy default intact).
        LaunchedEffect(isAudioOnlyInput) {
            if (isAudioOnlyInput && selectedContainer in listOf("mp4", "mkv", "webm", "mov", "avi", "mpegts")) {
                selectedContainer = when (sourceExt) {
                    "mp3", "wav", "ogg", "opus", "flac", "m4a" -> sourceExt
                    "aac" -> "m4a"
                    else -> "mp3"
                }
            }
        }

        // Set initial seek to 10% once duration is known
        LaunchedEffect(previewDuration) {
            if (previewDuration > 0 && previewSeek == 0f) {
                previewSeek = (previewDuration * 0.1).toFloat()
            }
        }

        val onDismiss = {
            viewModel.dismissConvertDialog()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.sftp_convert_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(entry.name, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))

                    // --- Preview area (video files only) ---
                    if (!isAudioOnly) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            when (val ps = previewState) {
                                is SftpViewModel.PreviewState.Idle -> {
                                    Text(
                                        "Preparing preview...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                is SftpViewModel.PreviewState.Generating -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                        if (previewIsRemote) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                "\u2601 Fetching from cloud\u2026",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                is SftpViewModel.PreviewState.Ready -> {
                                    val bitmap = remember(ps.imagePath) {
                                        BitmapFactory.decodeFile(ps.imagePath)
                                    }
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Preview frame — tap for fullscreen",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clickable { viewModel.setFullscreenPreview(true) },
                                        )
                                    }
                                    if (previewStale) {
                                        Text(
                                            "Tap Preview to refresh",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                                    shape = MaterialTheme.shapes.small,
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                is SftpViewModel.PreviewState.Failed -> {
                                    Text(
                                        ps.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }

                        // Seek slider + Preview button
                        if (previewDuration > 0) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Slider(
                                    value = previewSeek,
                                    onValueChange = { previewSeek = it; previewStale = true },
                                    valueRange = 0f..previewDuration.toFloat(),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatTimestamp(previewSeek.toDouble()),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }

                        // Preview button
                        FilledTonalButton(
                            onClick = {
                                previewStale = false
                                viewModel.previewFrame(
                                    previewSeek.toDouble(),
                                    filterState.buildVideoFilters(),
                                )
                            },
                            enabled = previewState !is SftpViewModel.PreviewState.Generating,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Preview")
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                    }

                    // Format selection — container + encoder dropdowns
                    Text("Container", style = MaterialTheme.typography.labelMedium)
                    DropdownSelector(
                        options = containers,
                        selected = selectedContainer,
                        onSelect = { selectedContainer = it },
                    )
                    Spacer(Modifier.height(8.dp))

                    if (!isAudioOnly) {
                        Text("Video encoder", style = MaterialTheme.typography.labelMedium)
                        DropdownSelector(
                            options = videoEncoders,
                            selected = selectedVideoEnc,
                            onSelect = { selectedVideoEnc = it },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Text("Audio encoder", style = MaterialTheme.typography.labelMedium)
                    DropdownSelector(
                        options = audioEncoders,
                        selected = selectedAudioEnc,
                        onSelect = { selectedAudioEnc = it },
                    )
                    Spacer(Modifier.height(12.dp))

                    // Output destination selector
                    Text("Save to", style = MaterialTheme.typography.labelMedium)
                    val sourceFolderLabel = when {
                        viewModel.isLocalProfile() -> "Same folder as source"
                        isRclone -> "Back to cloud (same folder)"
                        else -> "Back to server (same folder)"
                    }
                    val destinationOptions = listOf(
                        "downloads" to "Downloads (this device)",
                        "source" to sourceFolderLabel,
                    )
                    destinationOptions.forEach { (key, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { destinationKey = key }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(
                                selected = destinationKey == key,
                                onClick = { destinationKey = key },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // "Download first" toggle — rclone only. By default Haven
                    // streams the file directly into ffmpeg via HTTP (fast);
                    // enabling this forces a full download before transcode,
                    // useful for offline conversion or flaky connections.
                    if (isRclone) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { downloadFirst = !downloadFirst }
                                .padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Download first",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    if (downloadFirst) {
                                        "Downloads the whole file before transcoding"
                                    } else {
                                        "Streams from cloud (faster for large files)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = downloadFirst,
                                onCheckedChange = { downloadFirst = it },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Filter section (collapsible)
                    FilterSection(
                        state = filterState,
                        isAudioOnly = isAudioOnly,
                        onFilterChanged = { previewStale = true },
                    )

                    // Audio preview playback
                    if (previewDuration > 0) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            when (audioPreviewState) {
                                is SftpViewModel.AudioPreviewState.Playing -> {
                                    FilledTonalButton(
                                        onClick = { viewModel.stopAudioPreview() },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Stop")
                                    }
                                }
                                is SftpViewModel.AudioPreviewState.Generating -> {
                                    FilledTonalButton(
                                        onClick = {},
                                        enabled = false,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Preparing...")
                                    }
                                }
                                else -> {
                                    FilledTonalButton(
                                        onClick = {
                                            viewModel.previewAudio(
                                                previewSeek.toDouble(),
                                                filterState.buildAudioFilters(),
                                                filterState.buildVideoFilters(),
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Play 5s preview")
                                    }
                                }
                            }
                        }
                        if (audioPreviewState is SftpViewModel.AudioPreviewState.Failed) {
                            Text(
                                (audioPreviewState as SftpViewModel.AudioPreviewState.Failed).error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    // Live CLI preview
                    Spacer(Modifier.height(12.dp))
                    val cliPreview = remember(
                        selectedContainer, selectedVideoEnc, selectedAudioEnc,
                        filterState.brightness, filterState.contrast,
                        filterState.saturation, filterState.gamma,
                        filterState.sharpen, filterState.denoise,
                        filterState.stabilize, filterState.autoColor,
                        filterState.speed, filterState.rotation,
                        filterState.volume, filterState.normalizeAudio,
                    ) {
                        val cmd = sh.haven.core.ffmpeg.TranscodeCommand("input", "output.$selectedContainer")
                        if (!isAudioOnly) {
                            cmd.videoCodec(selectedVideoEnc)
                        } else {
                            cmd.extra("-vn")
                        }
                        cmd.audioCodec(selectedAudioEnc)
                            .videoFilters(filterState.buildVideoFilters())
                            .audioFilters(filterState.buildAudioFilters())
                        "ffmpeg " + cmd.build().joinToString(" ") { arg ->
                            if (arg.contains(',') || arg.contains('=')) "\"$arg\"" else arg
                        }
                    }
                    SelectionContainer {
                        Text(
                            cliPreview,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small,
                                )
                                .padding(8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissConvertDialog()
                    viewModel.convertFile(
                        entry = entry,
                        container = selectedContainer,
                        videoEncoder = if (isAudioOnly) null else selectedVideoEnc,
                        audioEncoder = selectedAudioEnc,
                        videoFilters = filterState.buildVideoFilters(),
                        audioFilters = filterState.buildAudioFilters(),
                        downloadFirst = downloadFirst,
                        destination = if (destinationKey == "source") {
                            ConvertDestination.SOURCE_FOLDER
                        } else {
                            ConvertDestination.DOWNLOADS
                        },
                    )
                }) { Text(stringResource(R.string.sftp_convert)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            },
        )

        // Fullscreen preview overlay
        if (showFullscreenPreview) {
            val ps = previewState
            if (ps is SftpViewModel.PreviewState.Ready) {
                val fullBitmap = remember(ps.imagePath) {
                    BitmapFactory.decodeFile(ps.imagePath)
                }
                if (fullBitmap != null) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { viewModel.setFullscreenPreview(false) },
                        properties = androidx.compose.ui.window.DialogProperties(
                            usePlatformDefaultWidth = false,
                        ),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
                                .clickable { viewModel.setFullscreenPreview(false) },
                        ) {
                            Image(
                                bitmap = fullBitmap.asImageBitmap(),
                                contentDescription = "Fullscreen preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                            )
                            // Timestamp badge
                            Text(
                                formatTimestamp(previewSeek.toDouble()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 32.dp)
                                    .background(
                                        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                                        shape = MaterialTheme.shapes.small,
                                    )
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // Folder size loading
    if (folderSizeLoading) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelFolderSize() },
            title = { Text(stringResource(R.string.sftp_folder_size_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.sftp_calculating_size))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.cancelFolderSize() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Folder size result
    folderSizeResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissFolderSize() },
            title = { Text(stringResource(R.string.sftp_folder_size_title)) },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissFolderSize() }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncDialog(
    source: String,
    remotes: List<String>,
    onDismiss: () -> Unit,
    onStart: (SyncConfig) -> Unit,
) {
    var srcFs by remember { mutableStateOf(source) }
    var dstRemote by remember { mutableStateOf(remotes.firstOrNull() ?: "") }
    var dstPath by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SyncMode.COPY) }
    var showFilters by remember { mutableStateOf(false) }
    var includeText by remember { mutableStateOf("") }
    var excludeText by remember { mutableStateOf("") }
    var minSize by remember { mutableStateOf("") }
    var maxSize by remember { mutableStateOf("") }
    var bwLimit by remember { mutableStateOf("") }
    var dryRun by remember { mutableStateOf(false) }
    var remoteExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_folder_sync)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Source
                OutlinedTextField(
                    value = srcFs,
                    onValueChange = { srcFs = it },
                    label = { Text(stringResource(R.string.sftp_source)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Destination remote + path
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ExposedDropdownMenuBox(
                        expanded = remoteExpanded,
                        onExpandedChange = { remoteExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = dstRemote,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.sftp_destination)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(remoteExpanded) },
                            singleLine = true,
                            modifier = Modifier.menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = remoteExpanded,
                            onDismissRequest = { remoteExpanded = false },
                        ) {
                            remotes.forEach { remote ->
                                DropdownMenuItem(
                                    text = { Text(remote) },
                                    onClick = {
                                        dstRemote = remote
                                        remoteExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = dstPath,
                        onValueChange = { dstPath = it },
                        label = { Text(stringResource(R.string.sftp_destination_path)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Mode selector
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SyncMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = {
                                Text(
                                    when (m) {
                                        SyncMode.COPY -> stringResource(R.string.sftp_mode_copy)
                                        SyncMode.SYNC -> stringResource(R.string.sftp_mode_sync)
                                        SyncMode.MOVE -> stringResource(R.string.sftp_mode_move)
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    when (mode) {
                        SyncMode.COPY -> stringResource(R.string.sftp_mode_copy_desc)
                        SyncMode.SYNC -> stringResource(R.string.sftp_mode_sync_desc)
                        SyncMode.MOVE -> stringResource(R.string.sftp_mode_move_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Advanced filters (collapsible)
                TextButton(onClick = { showFilters = !showFilters }) {
                    Icon(
                        if (showFilters) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sftp_advanced_filters))
                }

                if (showFilters) {
                    OutlinedTextField(
                        value = includeText,
                        onValueChange = { includeText = it },
                        label = { Text(stringResource(R.string.sftp_include_patterns)) },
                        placeholder = { Text("*.mp3\n*.flac") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = excludeText,
                        onValueChange = { excludeText = it },
                        label = { Text(stringResource(R.string.sftp_exclude_patterns)) },
                        placeholder = { Text("*.tmp\nThumbs.db") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minSize,
                            onValueChange = { minSize = it },
                            label = { Text(stringResource(R.string.sftp_min_size)) },
                            placeholder = { Text("e.g. 1M") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxSize,
                            onValueChange = { maxSize = it },
                            label = { Text(stringResource(R.string.sftp_max_size)) },
                            placeholder = { Text("e.g. 1G") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = bwLimit,
                        onValueChange = { bwLimit = it },
                        label = { Text(stringResource(R.string.sftp_bandwidth_limit)) },
                        placeholder = { Text("e.g. 10M") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Dry run checkbox
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dryRun, onCheckedChange = { dryRun = it })
                    Text(stringResource(R.string.sftp_dry_run))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val dstFs = if (dstPath.isNotBlank()) "$dstRemote:$dstPath" else "$dstRemote:"
                    onStart(
                        SyncConfig(
                            srcFs = srcFs,
                            dstFs = dstFs,
                            mode = mode,
                            filters = SyncFilters(
                                includePatterns = includeText.lines().filter { it.isNotBlank() },
                                excludePatterns = excludeText.lines().filter { it.isNotBlank() },
                                minSize = minSize.ifBlank { null },
                                maxSize = maxSize.ifBlank { null },
                                bandwidthLimit = bwLimit.ifBlank { null },
                            ),
                            dryRun = dryRun,
                        ),
                    )
                },
                enabled = srcFs.isNotBlank() && dstRemote.isNotBlank(),
            ) {
                Text(if (dryRun) stringResource(R.string.sftp_preview) else stringResource(R.string.sftp_start_sync))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    entry: SftpEntry,
    onTap: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    onPlay: (() -> Unit)? = null,
    onSync: (() -> Unit)? = null,
    onMediaSheet: (() -> Unit)? = null,
    onStream: (() -> Unit)? = null,
    onStreamFolder: (() -> Unit)? = null,
    onRename: () -> Unit = {},
    onShareLink: (() -> Unit)? = null,
    onFolderSize: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Box {
        ListItem(
            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                @Suppress("LocalContextGetResourceValueCall")
                val sizeText = if (entry.isDirectory) context.getString(R.string.sftp_directory) else Formatter.formatFileSize(context, entry.size)
                val dateText = dateFormat.format(Date(entry.modifiedTime * 1000))
                Text("$sizeText  $dateText")
            },
            leadingContent = {
                Icon(
                    if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                    contentDescription = stringResource(if (entry.isDirectory) R.string.sftp_directory_icon else R.string.sftp_file_icon),
                    tint = if (entry.isDirectory) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            modifier = Modifier.combinedClickable(
                onClick = onTap,
                onLongClick = { showMenu = true },
            ),
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            if (onPlay != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_play)) },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    onClick = { showMenu = false; onPlay() },
                )
            }
            if (onSync != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_sync)) },
                    leadingIcon = { Icon(Icons.Filled.Sync, null) },
                    onClick = { showMenu = false; onSync() },
                )
            }
            if (!entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_download)) },
                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                    onClick = { showMenu = false; onDownload() },
                )
            }
            if (onMediaSheet != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_media)) },
                    leadingIcon = { Icon(Icons.Filled.Movie, null) },
                    onClick = { showMenu = false; onMediaSheet() },
                )
            }
            if (onStream != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_stream)) },
                    leadingIcon = { Icon(Icons.Filled.CastConnected, null) },
                    onClick = { showMenu = false; onStream() },
                )
            }
            if (onStreamFolder != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_stream_folder)) },
                    leadingIcon = { Icon(Icons.Filled.CastConnected, null) },
                    onClick = { showMenu = false; onStreamFolder() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_copy)) },
                leadingIcon = { Icon(Icons.Filled.FileCopy, null) },
                onClick = { showMenu = false; onCopy() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_cut)) },
                leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
                onClick = { showMenu = false; onCut() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_rename)) },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { showMenu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_copy_path)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { showMenu = false; onCopyPath() },
            )
            if (onShareLink != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_share_link)) },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    onClick = { showMenu = false; onShareLink() },
                )
            }
            if (onFolderSize != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_folder_size)) },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                    onClick = { showMenu = false; onFolderSize() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun SortDropdown(
    expanded: Boolean,
    currentMode: SortMode,
    onDismiss: () -> Unit,
    onSelect: (SortMode) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortMode.entries.forEach { mode ->
            val label = when (mode) {
                SortMode.NAME_ASC -> stringResource(R.string.sftp_sort_name_asc)
                SortMode.NAME_DESC -> stringResource(R.string.sftp_sort_name_desc)
                SortMode.SIZE_ASC -> stringResource(R.string.sftp_sort_size_asc)
                SortMode.SIZE_DESC -> stringResource(R.string.sftp_sort_size_desc)
                SortMode.DATE_ASC -> stringResource(R.string.sftp_sort_date_asc)
                SortMode.DATE_DESC -> stringResource(R.string.sftp_sort_date_desc)
            }
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = {
                    RadioButton(
                        selected = mode == currentMode,
                        onClick = null,
                    )
                },
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.sftp_file_browser_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.sftp_connect_to_browse),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Format seconds as M:SS or H:MM:SS. */
/**
 * Compact dropdown selector for encoder/container choices.
 * Shows the selected label with a dropdown on tap.
 */
@Composable
private fun DropdownSelector(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(selectedLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(key); expanded = false },
                )
            }
        }
    }
}

private fun formatTimestamp(seconds: Double): String {
    val totalSec = seconds.toInt().coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
