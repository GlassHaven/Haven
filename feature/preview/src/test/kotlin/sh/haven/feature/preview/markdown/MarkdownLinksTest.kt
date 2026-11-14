package sh.haven.feature.preview.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinksTest {
    private val doc = "/home/user/project/docs/README.md"

    @Test
    fun `relative links resolve against the document folder`() {
        assertEquals(
            LinkTarget.File("/home/user/project/docs/guide/setup.md", null),
            MarkdownLinks.resolveLink(doc, "guide/setup.md"),
        )
        assertEquals(
            LinkTarget.File("/home/user/project/CHANGELOG.md", "v2"),
            MarkdownLinks.resolveLink(doc, "../CHANGELOG.md#v2"),
        )
        assertEquals(
            LinkTarget.File("/home/user/project/docs/My Notes.md", null),
            MarkdownLinks.resolveLink(doc, "./My%20Notes.md?plain=1"),
        )
    }

    @Test
    fun `dot-dot never climbs above the root`() {
        assertEquals(LinkTarget.File("/etc/hosts", null), MarkdownLinks.resolveLink("/a.md", "../../etc/hosts"))
    }

    @Test
    fun `leading slash is absolute on the backend`() {
        assertEquals(LinkTarget.File("/srv/www/index.md", null), MarkdownLinks.resolveLink(doc, "/srv/www/index.md"))
    }

    @Test
    fun `paths without a leading slash stay unrooted`() {
        // rclone remotes list paths relative to the remote root.
        assertEquals(LinkTarget.File("notes/b.md", null), MarkdownLinks.resolveLink("notes/a.md", "b.md"))
        assertEquals(LinkTarget.File("b.md", null), MarkdownLinks.resolveLink("notes/a.md", "../b.md"))
    }

    @Test
    fun `plus signs in paths are literal`() {
        assertEquals(LinkTarget.File("/home/user/project/docs/c++.md", null), MarkdownLinks.resolveLink(doc, "c++.md"))
    }

    @Test
    fun `anchors and schemes`() {
        assertEquals(LinkTarget.Anchor("install"), MarkdownLinks.resolveLink(doc, "#install"))
        assertEquals(LinkTarget.Anchor("快速开始"), MarkdownLinks.resolveLink(doc, "#%E5%BF%AB%E9%80%9F%E5%BC%80%E5%A7%8B"))
        assertEquals(LinkTarget.External("https://haven.sh"), MarkdownLinks.resolveLink(doc, "https://haven.sh"))
        assertEquals(LinkTarget.External("mailto:a@b.c"), MarkdownLinks.resolveLink(doc, "mailto:a@b.c"))
        assertEquals(LinkTarget.External("https://cdn.example/x"), MarkdownLinks.resolveLink(doc, "//cdn.example/x"))
    }

    @Test
    fun `images resolve to backend files, remote URLs or inline data`() {
        assertEquals(
            ImageSource.File("/home/user/project/docs/img/shot.png"),
            MarkdownLinks.resolveImage(doc, "img/shot.png"),
        )
        assertEquals(
            ImageSource.Remote("https://img.shields.io/badge/x-y-green"),
            MarkdownLinks.resolveImage(doc, "https://img.shields.io/badge/x-y-green"),
        )
        assertEquals(
            ImageSource.Inline(byteArrayOf(1, 2, 3)),
            MarkdownLinks.resolveImage(doc, "data:image/png;base64,AQID"),
        )
        assertEquals(ImageSource.Unsupported, MarkdownLinks.resolveImage(doc, "data:text/html,<b>x</b>"))
        assertEquals(ImageSource.Unsupported, MarkdownLinks.resolveImage(doc, "ftp://host/x.png"))
    }

    @Test
    fun `markdown file detection`() {
        assertTrue(MarkdownLinks.isMarkdownFile("README.md"))
        assertTrue(MarkdownLinks.isMarkdownFile("/x/notes.MARKDOWN"))
        assertFalse(MarkdownLinks.isMarkdownFile("component.mdx"))
        assertFalse(MarkdownLinks.isMarkdownFile("md"))
    }
}
