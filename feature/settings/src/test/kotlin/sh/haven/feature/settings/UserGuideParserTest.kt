package sh.haven.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserGuideParserTest {

    @Test
    fun `parses frontmatter title and strips frontmatter block`() {
        val raw = """
            ---
            layout: default
            title: Terminal
            ---

            # Terminal

            Multi-tab **SSH** and `mosh` sessions.
        """.trimIndent()

        val page = UserGuideParser.parsePage("features/terminal.md", raw)
        assertEquals("Terminal", page.title)
        assertFalse(page.plainText.contains("layout: default"))
        val heading = page.blocks.filterIsInstance<MdBlock.Heading>().firstOrNull()
        assertNotNull(heading)
        assertEquals(1, heading!!.level)
        assertEquals("terminal", heading.anchorId)
    }

    @Test
    fun `falls back to first h1 when frontmatter is absent`() {
        val raw = """
            # GPU acceleration on Android — the venus deep dive

            Haven runs guest Linux GL/Vulkan apps.
        """.trimIndent()

        val page = UserGuideParser.parsePage("gpu-acceleration.md", raw)
        assertEquals("GPU acceleration on Android — the venus deep dive", page.title)
    }

    @Test
    fun `parses pipe table with escaped pipe inside inline code`() {
        val raw = """
            | OSC | Function | Example |
            |-----|----------|---------|
            | 52 | Set clipboard | `printf '\e]52;c;%s\a' "$(echo -n text \| base64)"` |
        """.trimIndent()

        val page = UserGuideParser.parsePage("features/terminal.md", raw)
        val table = page.blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(3, table.headers.size)
        assertEquals(1, table.rows.size)
        assertEquals(3, table.rows[0].size)
        val codeSpan = table.rows[0][2].filterIsInstance<MdSpan.Code>().single()
        assertTrue(codeSpan.code.contains("| base64"))
        assertFalse(codeSpan.code.contains("\\|"))
    }

    @Test
    fun `parses nested list items fenced code blocks and inline links`() {
        val raw = """
            ## Serial consoles (Bluetooth, BLE, USB)

            - **[USB serial](usb.md)** — plug over OTG
              - Nested detail with *emphasis*

            ```bash
            ssh -L 8080:localhost:80 host
            ```
        """.trimIndent()

        val page = UserGuideParser.parsePage("features/terminal.md", raw)
        val heading = page.blocks.filterIsInstance<MdBlock.Heading>().single()
        assertEquals("serial-consoles-bluetooth-ble-usb", heading.anchorId)

        val items = page.blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(2, items.size)
        assertEquals(0, items[0].indentLevel)
        assertEquals(1, items[1].indentLevel)

        val code = page.blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("bash", code.language)
        assertEquals("ssh -L 8080:localhost:80 host", code.code)
    }

    @Test
    fun `resolves relative anchor fallback and external links accurately`() {
        val available = setOf(
            "FEATURES.md",
            "index.md",
            "backup-format.md",
            "gpu-acceleration.md",
            "features/terminal.md",
            "features/connections.md",
            "features/usb.md",
            "features/usb-recovery-live.md",
        )

        assertEquals(
            GuideLinkTarget.Internal("features/terminal.md", "serial-consoles-bluetooth-ble-usb"),
            UserGuideParser.resolveLink(
                "features/connections.md",
                "terminal.md#serial-consoles-bluetooth-ble-usb",
                available,
            ),
        )
        assertEquals(
            GuideLinkTarget.Internal("FEATURES.md", null),
            UserGuideParser.resolveLink("features/terminal.md", "../FEATURES.md", available),
        )
        // usb-recovery-live.md links "FEATURES.md" without "../"
        assertEquals(
            GuideLinkTarget.Internal("FEATURES.md", null),
            UserGuideParser.resolveLink("features/usb-recovery-live.md", "FEATURES.md", available),
        )
        assertEquals(
            GuideLinkTarget.Internal("features/usb.md", "device-class-support"),
            UserGuideParser.resolveLink("features/usb.md", "#device-class-support", available),
        )
        assertEquals(
            GuideLinkTarget.External("https://github.com/GlassHaven/Haven/blob/main/VISION.md"),
            UserGuideParser.resolveLink("gpu-acceleration.md", "../VISION.md", available),
        )
        assertEquals(
            GuideLinkTarget.External("https://github.com/GlassHaven/Haven/blob/main/docs/mcp-tools.md"),
            UserGuideParser.resolveLink("features/terminal.md", "../mcp-tools.md", available),
        )
    }

    @Test
    fun `parses all 19 real repository docs pages cleanly and searches them`() {
        val repoDocs = sequenceOf(
            File("../../docs"),
            File("docs"),
        ).firstOrNull { it.resolve("FEATURES.md").isFile }
        assertNotNull("Expected docs/ directory to exist", repoDocs)
        val docsDir = repoDocs!!

        val paths = buildList {
            add("FEATURES.md")
            add("index.md")
            add("backup-format.md")
            add("gpu-acceleration.md")
            docsDir.resolve("features")
                .listFiles { f -> f.isFile && f.extension == "md" }
                ?.sortedBy { it.name }
                ?.forEach { add("features/${it.name}") }
        }
        assertEquals(19, paths.size)

        val pages = paths.map { rel ->
            UserGuideParser.parsePage(rel, docsDir.resolve(rel).readText(Charsets.UTF_8))
        }
        val available = paths.toSet()

        for (page in pages) {
            assertTrue("Blank title for ${page.path}", page.title.isNotBlank())
            assertTrue("Empty blocks for ${page.path}", page.blocks.isNotEmpty())
            assertFalse("Leaked frontmatter in ${page.path}", page.plainText.contains("layout: default"))
            assertFalse("Leaked <video> tag in ${page.path}", page.plainText.contains("<video"))
        }

        val hits = UserGuideParser.searchPages(pages, "WireGuard")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.any { it.page.path == "features/connections.md" })

        val titleHits = UserGuideParser.searchPages(pages, "Reticulum")
        assertEquals("features/reticulum.md", titleHits.first().page.path)

        // Verify all internal .md links (excluding external VISION.md and unbundled mcp-tools.md) resolve internally
        for (page in pages) {
            val links = collectLinks(page.blocks)
            for (href in links) {
                if (href.startsWith("http://") || href.startsWith("https://")) continue
                if (href.endsWith(".html") || href.endsWith("VISION.md") || href.endsWith("mcp-tools.md")) continue
                val resolved = UserGuideParser.resolveLink(page.path, href, available)
                assertTrue(
                    "Expected internal target for ${page.path} -> $href, got $resolved",
                    resolved is GuideLinkTarget.Internal,
                )
            }
        }
    }

    private fun collectLinks(blocks: List<MdBlock>): List<String> {
        val out = mutableListOf<String>()
        fun walkSpans(spans: List<MdSpan>) {
            for (span in spans) {
                when (span) {
                    is MdSpan.Link -> {
                        out += span.href
                        walkSpans(span.spans)
                    }
                    is MdSpan.Bold -> walkSpans(span.spans)
                    is MdSpan.Italic -> walkSpans(span.spans)
                    is MdSpan.Text, is MdSpan.Code -> Unit
                }
            }
        }
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> walkSpans(block.spans)
                is MdBlock.Paragraph -> walkSpans(block.spans)
                is MdBlock.ListItem -> walkSpans(block.spans)
                is MdBlock.BlockQuote -> walkSpans(block.spans)
                is MdBlock.Table -> {
                    block.headers.forEach(::walkSpans)
                    block.rows.forEach { row -> row.forEach(::walkSpans) }
                }
                is MdBlock.CodeBlock, MdBlock.Divider -> Unit
            }
        }
        return out
    }
}
