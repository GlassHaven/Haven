package sh.haven.feature.preview.markdown

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.feature.preview.R

sealed interface MarkdownPreviewState {
    data class Loading(val fileName: String) : MarkdownPreviewState

    data class Loaded(
        val fileName: String,
        val filePath: String,
        val content: String,
        /** Heading to scroll to once laid out, from a `file.md#heading` link. */
        val anchor: String? = null,
        /** Came back to this document: restore where the reader left it. */
        val restoreScroll: Boolean = false,
    ) : MarkdownPreviewState

    data class Error(val fileName: String, val message: String) : MarkdownPreviewState
}

private val ReadingWidth = 760.dp

/**
 * Rendered, read-only view of a Markdown file from any file backend.
 *
 * Links behave as they do on a code-hosting site: `#heading` scrolls,
 * a relative path goes to [onOpenFile] (the host opens another document in
 * place, or whatever the file is), anything with a scheme goes to the system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownPreviewScreen(
    state: MarkdownPreviewState,
    /** Reads an image the document references; null when it is missing or too large. */
    readFile: suspend (path: String) -> ByteArray?,
    onBack: () -> Unit,
    onOpenFile: (path: String, anchor: String?) -> Unit,
    onEdit: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null,
    /** A message for the reader (a link that leads nowhere), shown once as a snackbar. */
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnNoticeShown by rememberUpdatedState(onNoticeShown)
    LaunchedEffect(notice) {
        if (notice != null) {
            // Cleared only once dismissed: clearing first would change the
            // key and cancel this effect, taking the snackbar with it.
            snackbarHostState.showSnackbar(notice)
            currentOnNoticeShown()
        }
    }
    val currentReadFile by rememberUpdatedState(readFile)
    val imageLoader = remember { MarkdownImageLoader { path -> currentReadFile(path) } }
    // Scroll position per document, so following a link and coming back
    // returns the reader to where they were.
    val positions = remember { mutableMapOf<String, Pair<Int, Int>>() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var showOutline by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val loaded = state as? MarkdownPreviewState.Loaded
    // Parsed off the main thread, and tagged with its source so a document
    // just navigated to never renders with the previous one's blocks.
    val parsed by produceState<Pair<String, MarkdownDocument>?>(null, loaded?.content) {
        val content = loaded?.content ?: return@produceState
        value = content to withContext(Dispatchers.Default) { MarkdownParser.parse(content) }
    }
    val document = parsed?.takeIf { it.first == loaded?.content }?.second
    val fileName = when (state) {
        is MarkdownPreviewState.Loading -> state.fileName
        is MarkdownPreviewState.Loaded -> state.fileName
        is MarkdownPreviewState.Error -> state.fileName
    }
    val listStateHolder = remember { mutableStateOf<LazyListState?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Column {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val folder = loaded?.filePath?.substringBeforeLast('/', "")
                        if (!folder.isNullOrEmpty()) {
                            Text(
                                text = folder,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (document?.outline?.isNotEmpty() == true) {
                        IconButton(onClick = { showOutline = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Toc,
                                contentDescription = stringResource(R.string.preview_markdown_outline),
                            )
                        }
                    }
                    if (onEdit != null) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.preview_markdown_edit))
                        }
                    }
                    if (onOpenWith != null) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.preview_markdown_more))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.preview_markdown_open_with)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        onOpenWith()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            val listState = listStateHolder.value
            val scope = rememberCoroutineScope()
            val showTop by remember(listState) {
                derivedStateOf { (listState?.firstVisibleItemIndex ?: 0) > 3 }
            }
            AnimatedVisibility(visible = showTop, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState?.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.preview_markdown_back_to_top),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val doc = document
            when {
                state is MarkdownPreviewState.Error -> CenteredMessage(
                    icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(40.dp)) },
                    message = state.message,
                    action = onOpenWith?.let { open ->
                        { TextButton(onClick = open) { Text(stringResource(R.string.preview_markdown_open_with)) } }
                    },
                )
                loaded == null || doc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                doc.blocks.isEmpty() && doc.frontMatter == null -> CenteredMessage(
                    icon = { Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(40.dp)) },
                    message = stringResource(R.string.preview_markdown_empty),
                )
                else -> key(loaded.filePath) {
                    DocumentList(
                        document = doc,
                        loaded = loaded,
                        positions = positions,
                        imageLoader = imageLoader,
                        onOpenFile = onOpenFile,
                        onListState = { listStateHolder.value = it },
                    )
                }
            }
        }
    }

    val listState = listStateHolder.value
    if (showOutline && document != null && listState != null) {
        OutlineSheet(
            document = document,
            listState = listState,
            onDismiss = { showOutline = false },
        )
    }
}

/** Lazy-list index of top-level block [blockIndex]: the front matter card, when shown, comes first. */
private fun MarkdownDocument.itemIndex(blockIndex: Int): Int = blockIndex + if (frontMatter != null) 1 else 0

@Composable
private fun DocumentList(
    document: MarkdownDocument,
    loaded: MarkdownPreviewState.Loaded,
    positions: MutableMap<String, Pair<Int, Int>>,
    imageLoader: MarkdownImageLoader,
    onOpenFile: (path: String, anchor: String?) -> Unit,
    onListState: (LazyListState) -> Unit,
) {
    val saved = if (loaded.restoreScroll) positions[loaded.filePath] else null
    val listState = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val currentOnOpenFile by rememberUpdatedState(onOpenFile)

    LaunchedEffect(listState) { onListState(listState) }
    DisposableEffect(listState) {
        onDispose {
            positions[loaded.filePath] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }
    LaunchedEffect(document, loaded.anchor) {
        val anchor = loaded.anchor ?: return@LaunchedEffect
        document.blockIndexForAnchor(anchor)?.let { listState.scrollToItem(document.itemIndex(it)) }
    }

    val context = remember(loaded.filePath, document, imageLoader) {
        MarkdownRenderContext(
            documentPath = loaded.filePath,
            imageLoader = imageLoader,
            onLink = { href ->
                when (val target = MarkdownLinks.resolveLink(loaded.filePath, href)) {
                    is LinkTarget.Anchor -> document.blockIndexForAnchor(target.anchor)?.let { index ->
                        scope.launch { listState.animateScrollToItem(document.itemIndex(index)) }
                    }
                    is LinkTarget.File -> if (target.path == loaded.filePath) {
                        target.anchor?.let(document::blockIndexForAnchor)?.let { index ->
                            scope.launch { listState.animateScrollToItem(document.itemIndex(index)) }
                        }
                    } else {
                        currentOnOpenFile(target.path, target.anchor)
                    }
                    // No app for the scheme (a mailto: with no mail app):
                    // nothing sensible to fall back to, so the tap is a no-op.
                    is LinkTarget.External -> runCatching { uriHandler.openUri(target.uri) }
                }
            },
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val itemModifier = Modifier.widthIn(max = ReadingWidth).fillMaxWidth().padding(horizontal = 16.dp)
        document.frontMatter?.let { frontMatter ->
            item(key = "front-matter") { FrontMatterCard(frontMatter, itemModifier) }
        }
        itemsIndexed(document.blocks, key = { index, _ -> index }) { _, block ->
            SelectionContainer(itemModifier) {
                MarkdownBlock(block, context)
            }
        }
    }
}

@Composable
private fun FrontMatterCard(frontMatter: String, modifier: Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.preview_markdown_front_matter),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        text = frontMatter,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(
    document: MarkdownDocument,
    listState: LazyListState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    // The section being read: the last heading at or above the top of the screen.
    val current by remember(document, listState) {
        derivedStateOf {
            document.outline.lastOrNull { document.itemIndex(it.blockIndex) <= listState.firstVisibleItemIndex }
        }
    }
    val minLevel = document.outline.minOf { it.level }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.preview_markdown_outline),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val outlineState = rememberLazyListState(
            initialFirstVisibleItemIndex = (document.outline.indexOf(current) - 2).coerceAtLeast(0),
        )
        LazyColumn(state = outlineState, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(document.outline) { _, entry ->
                val selected = entry == current
                Text(
                    text = entry.title,
                    style = if (entry.level == minLevel) {
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                        .clickable {
                            scope.launch {
                                listState.scrollToItem(document.itemIndex(entry.blockIndex))
                                sheetState.hide()
                                onDismiss()
                            }
                        }
                        .padding(
                            start = 12.dp + 16.dp * (entry.level - minLevel).coerceAtMost(4),
                            end = 12.dp,
                            top = 10.dp,
                            bottom = 10.dp,
                        ),
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    icon: @Composable () -> Unit,
    message: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            icon()
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        action?.invoke()
    }
}
