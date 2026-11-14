package sh.haven.feature.preview.markdown

import androidx.compose.runtime.Immutable
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.alerts.AlertsExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Image
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/** One heading, for the outline sheet and for `#anchor` links. */
@Immutable
data class OutlineEntry(
    val level: Int,
    val title: String,
    val anchor: String,
    /** Index into [MarkdownDocument.blocks] of the top-level block holding the heading. */
    val blockIndex: Int,
)

/**
 * A parsed Markdown file. [blocks] are the top-level commonmark nodes, one
 * lazy-list item each, so a long README only composes what is on screen.
 * The nodes are never mutated after [MarkdownParser.parse] returns.
 */
@Immutable
class MarkdownDocument(
    /** Raw YAML between the leading `---` fences, without the fences; null when absent. */
    val frontMatter: String?,
    val blocks: List<Node>,
    val outline: List<OutlineEntry>,
) {
    private val anchors: Map<String, Int> = outline.associate { it.anchor to it.blockIndex }

    /** Top-level block index for [anchor] (a GitHub-style heading id), or null. */
    fun blockIndexForAnchor(anchor: String): Int? =
        anchors[anchor] ?: anchors[MarkdownParser.slugify(anchor)]
}

object MarkdownParser {
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AlertsExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .build()

    private val frontMatterRegex = Regex("\\A---[ \\t]*\\n([\\s\\S]*?)\\n(?:---|\\.\\.\\.)[ \\t]*(?:\\n|\\z)")

    fun parse(markdown: String): MarkdownDocument {
        val normalized = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
        val frontMatterMatch = frontMatterRegex.find(normalized)
        val body = if (frontMatterMatch != null) normalized.substring(frontMatterMatch.range.last + 1) else normalized
        val root = parser.parse(body)

        // Reference definitions and comment-only HTML draw nothing; dropping
        // them keeps the list free of empty, spaced-out items.
        val blocks = generateSequence(root.firstChild) { it.next }
            .filterNot { it is LinkReferenceDefinition }
            .filterNot { it is HtmlBlock && HtmlFragments.parse(it.literal.orEmpty()).isEmpty() }
            .toList()
        val outline = mutableListOf<OutlineEntry>()
        val slugs = SlugCounter()
        blocks.forEachIndexed { index, block ->
            block.accept(object : AbstractVisitor() {
                override fun visit(heading: Heading) {
                    val title = plainText(heading).trim()
                    outline += OutlineEntry(heading.level, title, slugs.next(title), index)
                }
            })
        }
        return MarkdownDocument(
            frontMatter = frontMatterMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            blocks = blocks,
            outline = outline,
        )
    }

    /** The visible text of [node] and its descendants: what a reader sees, minus markup. */
    fun plainText(node: Node): String = buildString {
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                append(text.literal)
            }

            override fun visit(code: Code) {
                append(code.literal)
            }

            override fun visit(image: Image) {
                visitChildren(image)
            }
        })
    }

    /**
     * GitHub's heading id: lower-cased, punctuation dropped (letters and
     * digits of every script are kept, so CJK headings still get an id),
     * spaces turned into hyphens.
     */
    fun slugify(text: String): String = buildString {
        for (ch in text.trim().lowercase()) {
            when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' -> append(ch)
                ch == ' ' -> append('-')
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> append(ch)
            }
        }
    }

    /** Assigns `-1`, `-2`… to repeated heading ids, as GitHub does. */
    private class SlugCounter {
        private val seen = mutableMapOf<String, Int>()

        fun next(title: String): String {
            val base = slugify(title)
            val count = seen[base]
            seen[base] = (count ?: 0) + 1
            return if (count == null) base else "$base-$count"
        }
    }
}
