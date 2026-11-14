package sh.haven.feature.preview.markdown

/**
 * The little HTML that READMEs commonly embed — a centred `<p>` of badges, an
 * `<img>` with a width, `<br>`, `<details>` — reduced to text and images.
 * The preview has no HTML engine, so everything else is dropped and only its
 * text is kept.
 */
sealed interface HtmlPiece {
    data class Text(val text: String) : HtmlPiece
    data class Img(val src: String, val alt: String) : HtmlPiece
}

object HtmlFragments {
    private val commentRegex = Regex("<!--[\\s\\S]*?-->")
    private val dropWithContentRegex = Regex("<(script|style)\\b[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val imgRegex = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val breakRegex = Regex("<br\\s*/?>|</(?:p|div|h[1-6]|li|tr|summary)\\s*>", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("</?[a-zA-Z][^>]*>")
    private val whitespaceRegex = Regex("\\s+")
    private val blankLinesRegex = Regex("\\n\\s*\\n+")

    fun parse(html: String): List<HtmlPiece> {
        val cleaned = dropWithContentRegex.replace(commentRegex.replace(html, ""), "")
        val pieces = mutableListOf<HtmlPiece>()
        var last = 0
        for (match in imgRegex.findAll(cleaned)) {
            addText(pieces, cleaned.substring(last, match.range.first))
            val src = attribute(match.value, "src")
            if (!src.isNullOrBlank()) pieces += HtmlPiece.Img(src, attribute(match.value, "alt").orEmpty())
            last = match.range.last + 1
        }
        addText(pieces, cleaned.substring(last))
        return pieces
    }

    /** What an inline tag inside a paragraph turns into: a line break for `<br>`, nothing otherwise. */
    fun inlineReplacement(tag: String): String =
        if (breakRegex.matches(tag.trim())) "\n" else ""

    private fun addText(pieces: MutableList<HtmlPiece>, html: String) {
        // Source whitespace, newlines included, collapses as a browser would;
        // only <br> and closing block tags break lines.
        val collapsed = html.replace(whitespaceRegex, " ")
        val text = decodeEntities(tagRegex.replace(breakRegex.replace(collapsed, "\n"), ""))
            .lines().joinToString("\n") { it.trim() }
            .replace(blankLinesRegex, "\n")
            .trim()
        if (text.isNotEmpty()) pieces += HtmlPiece.Text(text)
    }

    private fun attribute(tag: String, name: String): String? {
        val match = Regex("(?<![\\w-])$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
            .find(tag) ?: return null
        return decodeEntities(match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty())
    }

    private fun decodeEntities(text: String): String =
        text.replace("&nbsp;", "\u00A0")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
