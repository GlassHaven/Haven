package sh.haven.feature.preview.markdown

import org.commonmark.ext.gfm.alerts.Alert
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `outline lists headings with GitHub ids and their block index`() {
        val doc = MarkdownParser.parse(
            """
            # Haven

            Intro.

            ## Getting started

            ### Install & run

            ## Getting started
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                OutlineEntry(1, "Haven", "haven", 0),
                OutlineEntry(2, "Getting started", "getting-started", 2),
                OutlineEntry(3, "Install & run", "install--run", 3),
                OutlineEntry(2, "Getting started", "getting-started-1", 4),
            ),
            doc.outline,
        )
        assertEquals(4, doc.blockIndexForAnchor("getting-started-1"))
        // A link written with the heading text rather than its id still lands.
        assertEquals(2, doc.blockIndexForAnchor("Getting started"))
    }

    @Test
    fun `slugify keeps letters of every script`() {
        assertEquals("快速开始", MarkdownParser.slugify("快速开始"))
        assertEquals("what-is-haven", MarkdownParser.slugify("What is Haven?"))
        assertEquals("v5-api_v2", MarkdownParser.slugify("v5 API_v2"))
    }

    @Test
    fun `front matter is split off and not rendered as a heading`() {
        val doc = MarkdownParser.parse("---\ntitle: Notes\ntags: [a, b]\n---\n# Body\n")
        assertEquals("title: Notes\ntags: [a, b]", doc.frontMatter)
        assertEquals(1, doc.blocks.size)
        assertTrue(doc.blocks[0] is Heading)
    }

    @Test
    fun `a thematic break at the top is not front matter`() {
        val doc = MarkdownParser.parse("---\n\nText\n")
        assertNull(doc.frontMatter)
    }

    @Test
    fun `GFM tables, alerts, task lists and fenced code are recognised`() {
        val doc = MarkdownParser.parse(
            """
            | a | b |
            |:--|--:|
            | 1 | 2 |

            > [!WARNING]
            > Careful.

            - [x] done
            - [ ] todo

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertTrue(doc.blocks[0] is TableBlock)
        assertEquals("WARNING", (doc.blocks[1] as Alert).type)
        val items = generateSequence((doc.blocks[2] as BulletList).firstChild) { it.next }.toList()
        val markers = items.map { item ->
            val marker = (item as ListItem).firstChild as? TaskListItemMarker
                ?: item.firstChild?.firstChild as? TaskListItemMarker
            marker?.isChecked
        }
        assertEquals(listOf(true, false), markers)
        assertEquals("kotlin", (doc.blocks[3] as FencedCodeBlock).info)
    }

    @Test
    fun `bare URLs become links`() {
        val doc = MarkdownParser.parse("See https://example.com/docs for more.")
        val link = generateSequence((doc.blocks[0] as Paragraph).firstChild) { it.next }
            .filterIsInstance<Link>()
            .single()
        assertEquals("https://example.com/docs", link.destination)
    }

    @Test
    fun `reference definitions and comments do not become empty blocks`() {
        val doc = MarkdownParser.parse("Text [x].\n\n<!-- hidden -->\n\n[x]: https://example.com\n")
        assertEquals(1, doc.blocks.size)
    }

    @Test
    fun `BOM and CRLF line endings are tolerated`() {
        val doc = MarkdownParser.parse("\uFEFF# Title\r\n\r\nBody\r\n")
        assertEquals("Title", doc.outline.single().title)
        assertEquals(2, doc.blocks.size)
    }
}
