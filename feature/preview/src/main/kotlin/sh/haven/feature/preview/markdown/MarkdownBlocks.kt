package sh.haven.feature.preview.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.commonmark.ext.gfm.alerts.Alert
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as TextNode
import org.commonmark.node.ThematicBreak
import sh.haven.feature.preview.R

/** What every block needs from the screen hosting the document. */
@Stable
class MarkdownRenderContext(
    val documentPath: String,
    val imageLoader: MarkdownImageLoader,
    /** Receives every tapped link and linked image, unresolved. */
    val onLink: (href: String) -> Unit,
)

private val BlockSpacing = 12.dp

/** Renders one block-level node; containers recurse through [MarkdownBlocks]. */
@Composable
internal fun MarkdownBlock(node: Node, context: MarkdownRenderContext, modifier: Modifier = Modifier) {
    when (node) {
        is Heading -> MarkdownHeading(node, context, modifier)
        is Paragraph -> MarkdownParagraph(node, context, modifier)
        is Alert -> MarkdownAlert(node, context, modifier)
        is BlockQuote -> MarkdownQuote(node, context, modifier)
        is ListBlock -> MarkdownList(node, context, modifier)
        is FencedCodeBlock -> MarkdownCode(
            code = node.literal.orEmpty(),
            language = node.info?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() },
            modifier = modifier,
        )
        is IndentedCodeBlock -> MarkdownCode(node.literal.orEmpty(), null, modifier)
        is HtmlBlock -> MarkdownHtml(node, context, modifier)
        is ThematicBreak -> HorizontalDivider(
            modifier = modifier.padding(vertical = 4.dp),
            thickness = 2.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        is TableBlock -> MarkdownTable(node, context, modifier)
        else -> Unit
    }
}

/** The block children of a container (list item, quote, alert). */
@Composable
private fun MarkdownBlocks(parent: Node, context: MarkdownRenderContext, spacing: Dp = BlockSpacing) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        var child = parent.firstChild
        while (child != null) {
            if (child !is TaskListItemMarker) MarkdownBlock(child, context)
            child = child.next
        }
    }
}

@Composable
private fun bodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)

@Composable
private fun MarkdownHeading(heading: Heading, context: MarkdownRenderContext, modifier: Modifier) {
    val base = MaterialTheme.typography
    val style = when (heading.level) {
        1 -> base.headlineSmall.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        2 -> base.titleLarge.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
        3 -> base.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        4 -> base.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        5 -> base.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        else -> base.titleSmall.copy(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(modifier.padding(top = if (heading.level <= 2) 12.dp else 6.dp)) {
        InlineText(heading, context, style)
        if (heading.level <= 2) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** A run of a paragraph: inline text, or an image (optionally wrapped in a link). */
private sealed interface ParagraphPart {
    class Inline(val nodes: List<Node>) : ParagraphPart
    class Picture(val image: Image, val href: String?) : ParagraphPart
}

/** Splits a paragraph so images become their own boxes instead of text. */
private fun paragraphParts(paragraph: Node): List<ParagraphPart> {
    val parts = mutableListOf<ParagraphPart>()
    val run = mutableListOf<Node>()
    fun flush() {
        if (run.any { !it.isBlankInline() }) parts += ParagraphPart.Inline(run.toList())
        run.clear()
    }
    var child = paragraph.firstChild
    while (child != null) {
        val linkedImage = (child as? Link)?.singleImage()
        when {
            child is Image -> { flush(); parts += ParagraphPart.Picture(child, null) }
            linkedImage != null -> { flush(); parts += ParagraphPart.Picture(linkedImage, (child as Link).destination) }
            else -> run += child
        }
        child = child.next
    }
    flush()
    return parts
}

private fun Node.isBlankInline(): Boolean =
    this is SoftLineBreak || this is HardLineBreak || (this is TextNode && literal.isBlank())

private fun Link.singleImage(): Image? {
    val children = generateSequence(firstChild) { it.next }.filterNot { it.isBlankInline() }.toList()
    return children.singleOrNull() as? Image
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkdownParagraph(paragraph: Paragraph, context: MarkdownRenderContext, modifier: Modifier) {
    val parts = remember(paragraph) { paragraphParts(paragraph) }
    when {
        parts.isEmpty() -> Unit
        parts.size == 1 && parts[0] is ParagraphPart.Inline -> InlineText(paragraph, context, bodyStyle(), modifier)
        parts.all { it is ParagraphPart.Picture } && parts.size > 1 -> {
            // A row of badges: lay them out like words, wrapping as needed.
            FlowRow(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                parts.forEach { part ->
                    part as ParagraphPart.Picture
                    MarkdownImage(
                        src = part.image.destination.orEmpty(),
                        alt = MarkdownParser.plainText(part.image),
                        context = context,
                        linkHref = part.href,
                        modifier = Modifier.widthIn(max = 480.dp),
                    )
                }
            }
        }
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEach { part ->
                when (part) {
                    is ParagraphPart.Inline -> InlineText(part.nodes, context, bodyStyle())
                    is ParagraphPart.Picture -> MarkdownImage(
                        src = part.image.destination.orEmpty(),
                        alt = MarkdownParser.plainText(part.image),
                        context = context,
                        linkHref = part.href,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownQuote(quote: BlockQuote, context: MarkdownRenderContext, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(12.dp))
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Box(Modifier.weight(1f)) { MarkdownBlocks(quote, context) }
        }
    }
}

private class AlertKind(val title: Int, val icon: ImageVector, val light: Color, val dark: Color)

private fun alertKind(type: String): AlertKind = when (type.uppercase()) {
    "TIP" -> AlertKind(R.string.preview_markdown_alert_tip, Icons.Outlined.Lightbulb, Color(0xFF1A7F37), Color(0xFF3FB950))
    "IMPORTANT" -> AlertKind(R.string.preview_markdown_alert_important, Icons.Outlined.Feedback, Color(0xFF8250DF), Color(0xFFAB7DF8))
    "WARNING" -> AlertKind(R.string.preview_markdown_alert_warning, Icons.Outlined.WarningAmber, Color(0xFF9A6700), Color(0xFFD29922))
    "CAUTION" -> AlertKind(R.string.preview_markdown_alert_caution, Icons.Outlined.Report, Color(0xFFCF222E), Color(0xFFF85149))
    else -> AlertKind(R.string.preview_markdown_alert_note, Icons.Outlined.Info, Color(0xFF0969DA), Color(0xFF4493F8))
}

/** GitHub's `> [!NOTE]` callouts, in GitHub's colours so they read the same here as there. */
@Composable
private fun MarkdownAlert(alert: Alert, context: MarkdownRenderContext, modifier: Modifier) {
    val kind = alertKind(alert.type)
    val accent = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) kind.dark else kind.light
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(accent.copy(alpha = 0.08f)),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(kind.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(kind.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                )
            }
            MarkdownBlocks(alert, context, spacing = 8.dp)
        }
    }
}

@Composable
private fun MarkdownList(list: ListBlock, context: MarkdownRenderContext, modifier: Modifier) {
    val depth = remember(list) { generateSequence(list.parent) { it.parent }.count { it is ListBlock } }
    val ordered = list as? OrderedList
    val delimiter = ordered?.markerDelimiter ?: "."
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (list.isTight) 4.dp else 10.dp)) {
        var number = ordered?.markerStartNumber ?: 1
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val task = item.taskMarker()
                Row(Modifier.fillMaxWidth()) {
                    val markerModifier = Modifier.widthIn(min = 24.dp).padding(end = 6.dp)
                    when {
                        task != null -> Icon(
                            imageVector = if (task.isChecked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = stringResource(
                                if (task.isChecked) R.string.preview_markdown_task_done else R.string.preview_markdown_task_open,
                            ),
                            tint = if (task.isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = markerModifier.padding(top = 3.dp).size(18.dp),
                        )
                        ordered != null -> Text(
                            text = "$number$delimiter",
                            style = bodyStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = markerModifier,
                        )
                        else -> Text(
                            text = BULLETS[depth.coerceAtMost(BULLETS.lastIndex)],
                            style = bodyStyle(),
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = markerModifier,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        MarkdownBlocks(item, context, spacing = if (list.isTight) 4.dp else BlockSpacing)
                    }
                }
                number++
            }
            item = item.next
        }
    }
}

private val BULLETS = listOf("•", "◦", "▪")

private fun ListItem.taskMarker(): TaskListItemMarker? =
    firstChild as? TaskListItemMarker ?: firstChild?.firstChild as? TaskListItemMarker

@Composable
private fun MarkdownCode(code: String, language: String?, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val text = code.trimEnd('\n')
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(
                            if (copied) R.string.preview_markdown_code_copied else R.string.preview_markdown_copy_code,
                        ),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = text,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

private val centeredHtmlRegex = Regex("align\\s*=\\s*[\"']?center", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkdownHtml(block: HtmlBlock, context: MarkdownRenderContext, modifier: Modifier) {
    val literal = block.literal.orEmpty()
    val pieces = remember(block) { HtmlFragments.parse(literal) }
    val centered = remember(block) { centeredHtmlRegex.containsMatchIn(literal) }
    // Consecutive images share a row, like badges in a centred <p>.
    val groups = remember(pieces) {
        buildList<List<HtmlPiece>> {
            for (piece in pieces) {
                val last = lastOrNull()
                if (piece is HtmlPiece.Img && last != null && last.first() is HtmlPiece.Img) {
                    set(lastIndex, last + piece)
                } else {
                    add(listOf(piece))
                }
            }
        }
    }
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { group ->
            when (val first = group.first()) {
                is HtmlPiece.Text -> Text(
                    text = first.text,
                    style = bodyStyle(),
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                )
                is HtmlPiece.Img -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    group.forEach { piece ->
                        piece as HtmlPiece.Img
                        MarkdownImage(
                            src = piece.src,
                            alt = piece.alt,
                            context = context,
                            modifier = if (group.size > 1) Modifier.widthIn(max = 480.dp) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock, context: MarkdownRenderContext, modifier: Modifier) {
    val rows: List<List<TableCell>> = remember(table) {
        buildList {
            fun collect(node: Node) {
                var child = node.firstChild
                while (child != null) {
                    if (child is TableRow) {
                        add(generateSequence(child.firstChild) { it.next }.filterIsInstance<TableCell>().toList())
                    } else {
                        collect(child)
                    }
                    child = child.next
                }
            }
            collect(table)
        }
    }
    val columns = rows.maxOfOrNull { it.size } ?: 0
    if (columns == 0) return
    val border = MaterialTheme.colorScheme.outlineVariant
    // Tints of the text colour, so stripes read lighter than the page in a
    // dark theme and darker in a light one, whatever the background.
    val headerBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val stripeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
    val cellStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)

    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Layout(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, border, RoundedCornerShape(6.dp)),
            content = {
                rows.forEachIndexed { r, row ->
                    for (c in 0 until columns) {
                        val cell = row.getOrNull(c)
                        val header = cell?.isHeader == true || (r == 0 && rows[0].firstOrNull()?.isHeader == true)
                        val align = when (cell?.alignment) {
                            TableCell.Alignment.CENTER -> TextAlign.Center
                            TableCell.Alignment.RIGHT -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        val lastRow = r == rows.lastIndex
                        val lastColumn = c == columns - 1
                        Box(
                            Modifier
                                .background(
                                    when {
                                        header -> headerBackground
                                        r % 2 == 0 -> stripeBackground
                                        else -> Color.Transparent
                                    },
                                )
                                .drawBehind {
                                    val stroke = 1.dp.toPx()
                                    if (!lastRow) {
                                        drawLine(border, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                                    }
                                    if (!lastColumn) {
                                        drawLine(border, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (cell != null) {
                                InlineText(
                                    node = cell,
                                    context = context,
                                    style = if (header) headerStyle else cellStyle,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = align,
                                )
                            }
                        }
                    }
                }
            },
        ) { measurables, _ ->
            // Size each column to its widest cell (within limits) and each row
            // to its tallest, then lay the cells out as a grid — what a
            // browser's auto table layout does, which Row/Column cannot.
            val minWidth = 56.dp.roundToPx()
            val maxWidth = 320.dp.roundToPx()
            val widths = IntArray(columns) { c ->
                rows.indices.maxOf { r -> measurables[r * columns + c].maxIntrinsicWidth(Constraints.Infinity) }
                    .coerceIn(minWidth, maxWidth)
            }
            val heights = IntArray(rows.size) { r ->
                (0 until columns).maxOf { c -> measurables[r * columns + c].minIntrinsicHeight(widths[c]) }
            }
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixed(widths[i % columns], heights[i / columns]))
            }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (c in 0 until columns) {
                        placeables[r * columns + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}

/** Inline content of [node] as one styled, link-aware text. */
@Composable
private fun InlineText(
    node: Node,
    context: MarkdownRenderContext,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val nodes = remember(node) { generateSequence(node.firstChild) { it.next }.toList() }
    InlineText(nodes, context, style, modifier, textAlign)
}

@Composable
private fun InlineText(
    nodes: List<Node>,
    context: MarkdownRenderContext,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val colors = MaterialTheme.colorScheme
    val onLink by rememberUpdatedState(context.onLink)
    val text = remember(nodes, colors) {
        val inlineStyles = InlineStyles(
            links = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
            code = SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.9.em,
                background = colors.surfaceContainerHighest,
            ),
        )
        buildAnnotatedString { appendInline(nodes, inlineStyles) { href -> onLink(href) } }
    }
    Text(
        text = text,
        style = style,
        modifier = modifier,
        textAlign = textAlign ?: TextAlign.Unspecified,
    )
}

private class InlineStyles(val links: TextLinkStyles, val code: SpanStyle)

private fun AnnotatedString.Builder.appendInline(nodes: Iterable<Node>, styles: InlineStyles, onLink: (String) -> Unit) {
    for (node in nodes) appendInline(node, styles, onLink)
}

private fun AnnotatedString.Builder.appendInline(node: Node, styles: InlineStyles, onLink: (String) -> Unit) {
    fun children() = appendInline(generateSequence(node.firstChild) { it.next }.asIterable(), styles, onLink)
    when (node) {
        is TextNode -> append(node.literal)
        is Code -> withStyle(styles.code) { append("\u2009${node.literal}\u2009") }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children() }
        is Link -> {
            val href = node.destination.orEmpty()
            withLink(LinkAnnotation.Clickable(tag = href, styles = styles.links) { onLink(href) }) { children() }
        }
        // Images inside running text (rare: an icon mid-sentence) keep their alt text.
        is Image -> children()
        is SoftLineBreak -> append(' ')
        is HardLineBreak -> append('\n')
        is HtmlInline -> append(HtmlFragments.inlineReplacement(node.literal.orEmpty()))
        is TaskListItemMarker -> Unit
        else -> children()
    }
}
