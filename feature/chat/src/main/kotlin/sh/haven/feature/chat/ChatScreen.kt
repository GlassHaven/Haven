package sh.haven.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.launch
import sh.haven.core.data.images.ChatImagePrep

/**
 * The chat surface for a CONNECTED OPENAI profile (Layer D). Reached by
 * tapping a CONNECTED OPENAI profile card; ephemeral by default, with an
 * opt-in save toggle in the top bar.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    pendingProfileId: String?,
    modifier: Modifier = Modifier,
    /** Switch to the Files tab — the FILES attach option arms the pick and jumps. */
    onOpenFilesTab: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    LaunchedEffect(pendingProfileId) {
        pendingProfileId?.let { viewModel.attach(it) }
    }
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current

    // Attach key → bottom sheet → gallery picker / camera capture. Same
    // launcher shape as the terminal attach flow: TakePicture writes into a
    // FileProvider cache URI we control; PickVisualMedia returns a transient
    // picker URI consumed immediately by stageImage.
    var attachSheetVisible by remember { mutableStateOf(false) }
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraOutputUri
        cameraOutputUri = null
        if (success && uri != null) viewModel.stageImage(uri)
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.stageImage(uri)
    }
    fun launchAttach(option: ChatAttachOption) {
        when (option) {
            ChatAttachOption.GALLERY -> galleryLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
            ChatAttachOption.CAMERA -> {
                val dir = File(context.cacheDir, "chat")
                dir.mkdirs()
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(dir, "chat_${System.currentTimeMillis()}.jpg"),
                )
                cameraOutputUri = uri
                try {
                    cameraLauncher.launch(uri)
                } catch (_: android.content.ActivityNotFoundException) {
                    cameraOutputUri = null
                }
            }
            ChatAttachOption.FILES -> {
                // Arms the broker pick (stageRemoteImage) and jumps to the
                // Files tab; the pick banner there routes the file tap back.
                viewModel.stageRemoteImage()
                onOpenFilesTab()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(ui.label.ifBlank { stringResource(R.string.chat_title) }) },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleSave() },
                        enabled = ui.profileId != null,
                    ) {
                        Icon(
                            imageVector = if (ui.saveEnabled) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = stringResource(
                                if (ui.saveEnabled) R.string.chat_stop_saving else R.string.chat_save_conversation,
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (ui.profileId == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.chat_no_connection))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ModelSelector(
                models = ui.models,
                selected = ui.selectedModel,
                onSelect = viewModel::selectModel,
            )
            ui.error?.let { error ->
                TextButton(
                    onClick = { viewModel.clearError() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.chat_error_dismiss, error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            MessageList(
                messages = ui.messages,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            Composer(
                streaming = ui.streaming,
                staged = ui.staged,
                onStage = { attachSheetVisible = true },
                onPasteImage = viewModel::stageImage,
                onRemoveStaged = viewModel::removeStaged,
                onSend = viewModel::send,
                onStop = viewModel::stopStreaming,
            )
        }
    }

    if (attachSheetVisible) {
        ChatAttachSheet(
            onDismiss = { attachSheetVisible = false },
            onSelect = { option ->
                attachSheetVisible = false
                launchAttach(option)
            },
        )
    }
}

@Composable
private fun ModelSelector(
    models: List<sh.haven.core.openai.ModelInfo>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected ?: stringResource(R.string.chat_no_model))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.id) },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatUiMessage>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedText = stringResource(R.string.chat_copied_text)
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    // One-tap copy lives on the newest finished assistant reply only; while a
    // reply streams its message is pending, so the affordance waits it out.
    val lastAssistantId = messages
        .lastOrNull { it.role == "assistant" && !it.pending && it.text.isNotBlank() }
        ?.id
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                isLastCompletedAssistant = message.id == lastAssistantId,
                onCopyLastReply = if (message.id == lastAssistantId) {
                    {
                        clipboard?.setText(AnnotatedString(message.text))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    null
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatUiMessage,
    isLastCompletedAssistant: Boolean = false,
    onCopyLastReply: (() -> Unit)? = null,
) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    val copiedText = stringResource(R.string.chat_copied_text)
    val copiedImage = stringResource(R.string.chat_copied_image)
    val copyImageFailed = stringResource(R.string.chat_copy_image_failed)
    val copyImageLabel = stringResource(R.string.chat_copy_image)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp,
                    ),
                )
                .background(
                    if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            message.images.forEach { img ->
                // Small hand-decoded preview (no image loader dependency);
                // keyed on the message id so it decodes once per bubble.
                val preview = remember(message.id) {
                    ChatImagePrep.decodePreview(img.base64)?.asImageBitmap()
                }
                preview?.let {
                    Image(
                        bitmap = it,
                        contentDescription = stringResource(R.string.chat_image),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
            if (message.pending && message.text.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else if (message.text.isNotEmpty()) {
                Text(message.text)
            }
            if (isLastCompletedAssistant) {
                IconButton(
                    onClick = { onCopyLastReply?.invoke() },
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.End),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy_last_reply),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            if (message.text.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_copy_text)) },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    onClick = {
                        clipboard?.setText(AnnotatedString(message.text))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        showMenu = false
                    },
                )
            }
            if (message.images.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_copy_image)) },
                    leadingIcon = { Icon(Icons.Filled.Photo, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        val img = message.images.first()
                        scope.launch {
                            val uri = ChatImagePrep.decodeToCache(context, img.base64, img.mimeType)
                            if (uri != null) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newUri(context.contentResolver, copyImageLabel, uri))
                                Toast.makeText(context, copiedImage, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, copyImageFailed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Composer(
    streaming: Boolean,
    staged: List<StagedImage>,
    onStage: () -> Unit,
    onPasteImage: (Uri) -> Unit,
    onRemoveStaged: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val view = LocalView.current
    val pasteUnavailable = stringResource(R.string.chat_paste_image_unavailable)
    val systemClipboard = remember {
        view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    // The clipboard isn't observable, and on Android 10+ it's only readable
    // while the window has focus — so sample it at the two moments the state
    // can change while the composer is on screen: resume and field focus.
    // A stale miss only hides the button; the click handler re-reads the clip.
    var clipboardHasImage by remember { mutableStateOf(false) }
    fun refreshClipboard() {
        clipboardHasImage = runCatching {
            ChatClipboard.hasImage(systemClipboard?.primaryClip)
        }.getOrDefault(false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(8.dp),
    ) {
        if (staged.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                staged.forEach { item ->
                    StagedThumbnail(item, onRemove = { onRemoveStaged(item.id) })
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { if (it.isFocused) refreshClipboard() },
                placeholder = { Text(stringResource(R.string.chat_message_hint)) },
                maxLines = 5,
            )
            IconButton(
                onClick = onStage,
                enabled = !streaming && staged.size < 4,
            ) {
                Icon(
                    Icons.Filled.AddPhotoAlternate,
                    contentDescription = stringResource(R.string.chat_attach_title),
                )
            }
            // Paste an image from the system clipboard into the staged row —
            // pairs with vision: copy a screenshot anywhere, send it to the
            // model. Same guards as attach; stageImage enforces the cap again.
            IconButton(
                onClick = {
                    val uri = runCatching {
                        ChatClipboard.firstImageUri(systemClipboard?.primaryClip)
                    }.getOrNull()
                    if (uri != null) {
                        onPasteImage(uri)
                    } else {
                        Toast.makeText(context, pasteUnavailable, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = clipboardHasImage && !streaming && staged.size < 4,
            ) {
                Icon(
                    Icons.Filled.ContentPaste,
                    contentDescription = stringResource(R.string.chat_paste_image),
                )
            }
            if (streaming) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop))
                }
            } else {
                IconButton(
                    onClick = {
                        onSend(text)
                        text = ""
                    },
                    enabled = text.isNotBlank() || staged.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun StagedThumbnail(item: StagedImage, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(64.dp)) {
        val preview = remember(item.id) { item.preview?.asImageBitmap() }
        preview?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.chat_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        // Remove affordance pinned to the corner so a mis-picked image can be
        // dropped before it costs a request.
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.chat_remove_image),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onRemove),
        )
    }
}