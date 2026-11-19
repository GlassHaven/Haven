package sh.haven.feature.preview.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlFragmentsTest {

    @Test
    fun `centred README header keeps its images and text`() {
        val pieces = HtmlFragments.parse(
            """
            <p align="center">
              <img src="docs/logo.png" alt="Haven logo" width="120">
              <br>
              <b>SSH, files and desktops</b>
              in one app
            </p>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                HtmlPiece.Img("docs/logo.png", "Haven logo"),
                HtmlPiece.Text("SSH, files and desktops in one app"),
            ),
            pieces,
        )
    }

    @Test
    fun `comments, scripts and empty markup produce nothing`() {
        assertEquals(emptyList<HtmlPiece>(), HtmlFragments.parse("<!-- badges -->"))
        assertEquals(emptyList<HtmlPiece>(), HtmlFragments.parse("<div>\n</div>"))
        assertEquals(emptyList<HtmlPiece>(), HtmlFragments.parse("<script>alert(1)</script>"))
    }

    @Test
    fun `attributes are read in any quoting style and data-src is not src`() {
        assertEquals(
            listOf(HtmlPiece.Img("a.png", "A & B")),
            HtmlFragments.parse("<img data-src='x.png' src=a.png alt=\"A &amp; B\">"),
        )
    }

    @Test
    fun `br and block ends break lines`() {
        assertEquals(
            listOf(HtmlPiece.Text("one\ntwo\nthree")),
            HtmlFragments.parse("<details><summary>one</summary>two<br/>three</details>"),
        )
        assertEquals("\n", HtmlFragments.inlineReplacement("<br>"))
        assertEquals("", HtmlFragments.inlineReplacement("<kbd>"))
    }
}
