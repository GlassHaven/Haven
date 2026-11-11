package sh.haven.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_DIR = "user-guide"
private const val HOME_PAGE = "FEATURES.md"

private sealed interface GuideLoadState {
    data object Loading : GuideLoadState
    data class Ready(val pages: List<GuidePage>, val byPath: Map<String, GuidePage>) : GuideLoadState
    data object Error : GuideLoadState
}

private fun loadBundledGuidePages(context: Context): List<GuidePage> {
    val assets = context.assets
    val manifestPaths = runCatching {
        assets.open("$ASSET_DIR/manifest.txt").bufferedReader(Charsets.UTF_8).use { reader ->
            reader.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }.getOrDefault(emptyList())

    val paths = manifestPaths.ifEmpty { listOf(HOME_PAGE, "index.md") }
    return paths.mapNotNull { relPath ->
        runCatching {
            val raw = assets.open("$ASSET_DIR/$relPath")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            UserGuideParser.parsePage(relPath, raw)
        }.getOrNull()
    }
}

@Composable
internal fun UserGuideDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val loadState by produceState<GuideLoadState>(initialValue = GuideLoadState.Loading, context) {
        val loaded = withContext(Dispatchers.IO) { loadBundledGuidePages(context) }
        value = if (loaded.isEmpty()) {
            GuideLoadState.Error
        } else {
            GuideLoadState.Ready(loaded, loaded.associateBy { it.path })
        }
    }

    var currentPath by remember { mutableStateOf(HOME_PAGE) }
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    var navCounter by remember { mutableStateOf(0) }
    val history = remember { mutableStateListOf<String>() }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun openPage(targetPath: String, anchor: String? = null) {
        if (targetPath != currentPath) {
            history.add(currentPath)
            currentPath = targetPath
        }
        pendingAnchor = anchor
        searchActive = false
        navCounter++
    }

    fun stepBack() {
        when {
            searchActive -> {
                searchActive = false
                searchQuery = ""
            }
            history.isNotEmpty() -> {
                currentPath = history.removeAt(history.lastIndex)
                pendingAnchor = null
                navCounter++
            }
            else -> onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
        ),
    ) {
        BackHandler(enabled = searchActive || history.isNotEmpty()) {
            stepBack()
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                val ready = loadState as? GuideLoadState.Ready
                val currentPage = ready?.let {
                    it.byPath[currentPath] ?: it.pages.firstOrNull()
                }

                // Top bar
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val canGoBack = searchActive || history.isNotEmpty()
                        IconButton(onClick = { if (canGoBack) stepBack() else onDismiss() }) {
                            Icon(
                                imageVector = if (canGoBack) {
                                    Icons.AutoMirrored.Filled.ArrowBack
                                } else {
                                    Icons.Filled.Close
                                },
                                contentDescription = if (canGoBack) {
                                    stringResource(R.string.settings_user_guide_back_cd)
                                } else {
                                    stringResource(R.string.common_close)
                                },
                            )
                        }

                        if (searchActive) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(stringResource(R.string.settings_user_guide_search_hint))
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp),
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.common_close),
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = currentPage?.title
                                    ?: stringResource(R.string.settings_user_guide_title),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                            )
                            if (ready != null && currentPage != null && currentPage.path != HOME_PAGE) {
                                TextButton(onClick = { openPage(HOME_PAGE) }) {
                                    Text(stringResource(R.string.settings_user_guide_home))
                                }
                            }
                            IconButton(
                                onClick = {
                                    searchActive = true
                                    searchQuery = ""
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.settings_user_guide_search_cd),
                                )
                            }
                            if (history.isNotEmpty()) {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.common_close),
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                when (val state = loadState) {
                    GuideLoadState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    GuideLoadState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_user_guide_load_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is GuideLoadState.Ready -> {
                        if (searchActive) {
                            val hits = remember(state.pages, searchQuery) {
                                if (searchQuery.isBlank()) {
                                    state.pages.map {
                                        GuideSearchHit(
                                            page = it,
                                            snippet = it.plainText.lineSequence().firstOrNull()
                                                .orEmpty()
                                                .take(120),
                                            score = 0,
                                        )
                                    }
                                } else {
                                    UserGuideParser.searchPages(state.pages, searchQuery)
                                }
                            }
                            if (hits.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_user_guide_no_results),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                ) {
                                    items(hits, key = { it.page.path }) { hit ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { openPage(hit.page.path) }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                        ) {
                                            Text(
                                                text = hit.page.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            if (hit.snippet.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = hit.snippet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        } else if (currentPage != null) {
                            val available = remember(state.pages) {
                                state.pages.mapTo(mutableSetOf()) { it.path }
                            }
                            LaunchedEffect(currentPage.path, navCounter) {
                                val anchor = pendingAnchor
                                val targetIndex = if (anchor != null) {
                                    currentPage.blocks.indexOfFirst {
                                        it is MdBlock.Heading && it.anchorId == anchor
                                    }.takeIf { it >= 0 } ?: 0
                                } else {
                                    0
                                }
                                listState.scrollToItem(targetIndex)
                            }

                            val onLinkClick: (String) -> Unit = { href ->
                                when (val target = UserGuideParser.resolveLink(currentPage.path, href, available)) {
                                    is GuideLinkTarget.Internal -> openPage(target.pagePath, target.anchor)
                                    is GuideLinkTarget.External -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(target.url)),
                                            )
                                        }
                                    }
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(currentPage.blocks) { _, block ->
                                    MarkdownBlockView(
                                        block = block,
                                        onLinkClick = onLinkClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MdBlock,
    onLinkClick: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            }
            val topPad = if (block.level <= 2) 8.dp else 4.dp
            Column(modifier = Modifier.padding(top = topPad)) {
                RichSpansText(
                    spans = block.spans,
                    style = style,
                    color = colorScheme.onBackground,
                    onLinkClick = onLinkClick,
                )
                if (block.level <= 2) {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = colorScheme.outlineVariant)
                }
            }
        }
        is MdBlock.Paragraph -> {
            RichSpansText(
                spans = block.spans,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onBackground,
                onLinkClick = onLinkClick,
            )
        }
        is MdBlock.ListItem -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (block.indentLevel * 16).dp),
            ) {
                Text(
                    text = block.bullet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                RichSpansText(
                    spans = block.spans,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onBackground,
                    onLinkClick = onLinkClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is MdBlock.BlockQuote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(colorScheme.primary, RoundedCornerShape(2.dp)),
                )
                Spacer(modifier = Modifier.width(10.dp))
                RichSpansText(
                    spans = block.spans,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    onLinkClick = onLinkClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is MdBlock.CodeBlock -> {
            Surface(
                color = colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        is MdBlock.Table -> {
            Surface(
                color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .background(
                                colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        block.headers.forEach { headerSpans ->
                            RichSpansText(
                                spans = headerSpans,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = colorScheme.onSurface,
                                onLinkClick = onLinkClick,
                                modifier = Modifier.widthIn(min = 96.dp, max = 280.dp),
                            )
                        }
                    }
                    block.rows.forEach { row ->
                        HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            row.forEach { cellSpans ->
                                RichSpansText(
                                    spans = cellSpans,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurface,
                                    onLinkClick = onLinkClick,
                                    modifier = Modifier.widthIn(min = 96.dp, max = 280.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        MdBlock.Divider -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun RichSpansText(
    spans: List<MdSpan>,
    style: TextStyle,
    color: Color,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeFg = MaterialTheme.colorScheme.onSurfaceVariant
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)

    val annotated = remember(spans, linkColor, codeBg, codeFg) {
        val linkStyles = TextLinkStyles(
            style = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            ),
        )
        buildAnnotatedString {
            appendSpans(
                spans = spans,
                linkStyles = linkStyles,
                codeBg = codeBg,
                codeFg = codeFg,
                onLinkClick = { href -> currentOnLinkClick(href) },
            )
        }
    }

    Text(
        text = annotated,
        style = style.copy(color = color),
        modifier = modifier,
    )
}

private fun AnnotatedString.Builder.appendSpans(
    spans: List<MdSpan>,
    linkStyles: TextLinkStyles,
    codeBg: Color,
    codeFg: Color,
    onLinkClick: (String) -> Unit,
) {
    for (span in spans) {
        when (span) {
            is MdSpan.Text -> append(span.text)
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendSpans(span.spans, linkStyles, codeBg, codeFg, onLinkClick)
            }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendSpans(span.spans, linkStyles, codeBg, codeFg, onLinkClick)
            }
            is MdSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBg,
                    color = codeFg,
                ),
            ) {
                append("\u00A0${span.code}\u00A0")
            }
            is MdSpan.Link -> {
                val href = span.href
                withLink(
                    LinkAnnotation.Clickable(
                        tag = href,
                        styles = linkStyles,
                        linkInteractionListener = { onLinkClick(href) },
                    ),
                ) {
                    appendSpans(span.spans, linkStyles, codeBg, codeFg, onLinkClick)
                }
            }
        }
    }
}
