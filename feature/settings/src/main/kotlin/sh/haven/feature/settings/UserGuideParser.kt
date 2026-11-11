package sh.haven.feature.settings

data class GuidePage(
    val path: String,
    val title: String,
    val blocks: List<MdBlock>,
    val plainText: String,
)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>, val anchorId: String) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class ListItem(val indentLevel: Int, val bullet: String, val spans: List<MdSpan>) : MdBlock
    data class BlockQuote(val spans: List<MdSpan>) : MdBlock
    data class Table(
        val headers: List<List<MdSpan>>,
        val rows: List<List<List<MdSpan>>>,
    ) : MdBlock
    data object Divider : MdBlock
}

sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val spans: List<MdSpan>) : MdSpan
    data class Italic(val spans: List<MdSpan>) : MdSpan
    data class Code(val code: String) : MdSpan
    data class Link(val spans: List<MdSpan>, val href: String) : MdSpan
}

sealed interface GuideLinkTarget {
    data class Internal(val pagePath: String, val anchor: String?) : GuideLinkTarget
    data class External(val url: String) : GuideLinkTarget
}

data class GuideSearchHit(
    val page: GuidePage,
    val snippet: String,
    val score: Int,
)

object UserGuideParser {
    private const val DOCS_WEB_BASE = "https://glasshaven.github.io/Haven/"
    private const val REPO_BLOB_BASE = "https://github.com/GlassHaven/Haven/blob/main/"

    private val videoBlockRegex = Regex("<video\\b[\\s\\S]*?</video>", RegexOption.IGNORE_CASE)
    private val htmlTableRegex = Regex("<table\\b[^>]*>([\\s\\S]*?)</table>", RegexOption.IGNORE_CASE)
    private val htmlRowRegex = Regex("<tr\\b[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    private val htmlCellRegex = Regex("<t([hd])\\b[^>]*>([\\s\\S]*?)</t\\1>", RegexOption.IGNORE_CASE)
    private val htmlParagraphRegex = Regex("<p\\b[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
    private val htmlLinkRegex = Regex("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("</?(?:b|i|em|strong|sub|sup|br|img)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val orderedListRegex = Regex("^(\\s*)(\\d+\\.)\\s+(.*)$")
    private val unorderedListRegex = Regex("^(\\s*)([-*])\\s+(.*)$")
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val hrRegex = Regex("^\\s*(?:---+|-\\s+-\\s+-|\\*\\*\\*+|___+)\\s*$")
    private val tableSepCellRegex = Regex("^:?-{2,}:?$")

    fun parsePage(path: String, rawMarkdown: String): GuidePage {
        val (frontmatterTitle, bodyRaw) = stripFrontmatter(rawMarkdown)
        val cleaned = cleanEmbeddedHtml(bodyRaw)
        val lines = cleaned.lines()
        val blocks = parseBlocks(lines)

        val firstH1 = blocks.filterIsInstance<MdBlock.Heading>()
            .firstOrNull { it.level == 1 }
            ?.let { spansToPlainText(it.spans).trim() }

        val fallbackTitle = path.substringAfterLast('/')
            .removeSuffix(".md")
            .replace('-', ' ')
            .replaceFirstChar { it.uppercase() }

        val title = frontmatterTitle?.takeIf { it.isNotBlank() }
            ?: firstH1?.takeIf { it.isNotBlank() }
            ?: fallbackTitle

        val plainText = buildString {
            for (b in blocks) {
                when (b) {
                    is MdBlock.Heading -> appendLine(spansToPlainText(b.spans))
                    is MdBlock.Paragraph -> appendLine(spansToPlainText(b.spans))
                    is MdBlock.ListItem -> appendLine(spansToPlainText(b.spans))
                    is MdBlock.BlockQuote -> appendLine(spansToPlainText(b.spans))
                    is MdBlock.CodeBlock -> appendLine(b.code)
                    is MdBlock.Table -> {
                        b.headers.forEach { append(spansToPlainText(it)).append(' ') }
                        appendLine()
                        b.rows.forEach { row ->
                            row.forEach { append(spansToPlainText(it)).append(' ') }
                            appendLine()
                        }
                    }
                    MdBlock.Divider -> Unit
                }
            }
        }.trim()

        return GuidePage(
            path = path,
            title = title,
            blocks = blocks,
            plainText = plainText,
        )
    }

    private fun stripFrontmatter(raw: String): Pair<String?, String> {
        val normalized = raw.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return null to normalized
        val endIdx = normalized.indexOf("\n---\n", startIndex = 4)
        if (endIdx < 0) return null to normalized
        val fmLines = normalized.substring(4, endIdx).lines()
        var title: String? = null
        for (line in fmLines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("title:")) {
                title = trimmed.removePrefix("title:").trim().trim('"', '\'')
            }
        }
        val rest = normalized.substring(endIdx + 5)
        return title to rest
    }

    private fun cleanEmbeddedHtml(input: String): String {
        var text = videoBlockRegex.replace(input, "")
        text = htmlTableRegex.replace(text) { match ->
            val rows = htmlRowRegex.findAll(match.groupValues[1]).map { tr ->
                htmlCellRegex.findAll(tr.groupValues[1]).map { td ->
                    htmlInlineToMarkdown(td.groupValues[2]).replace("\n", " ").trim()
                }.toList()
            }.filter { it.isNotEmpty() }.toList()
            if (rows.isEmpty()) {
                ""
            } else {
                buildString {
                    val header = rows.first()
                    append("| ").append(header.joinToString(" | ")).appendLine(" |")
                    append("| ").append(header.joinToString(" | ") { "---" }).appendLine(" |")
                    for (row in rows.drop(1)) {
                        append("| ").append(row.joinToString(" | ")).appendLine(" |")
                    }
                }
            }
        }
        text = htmlParagraphRegex.replace(text) { match ->
            val inner = match.groupValues[1]
            if (inner.contains("<img", ignoreCase = true) || inner.contains("href=\"zh/\"")) {
                ""
            } else {
                htmlInlineToMarkdown(inner).trim()
            }
        }
        return text
    }

    private fun htmlInlineToMarkdown(html: String): String {
        var out = htmlLinkRegex.replace(html) { m ->
            val href = m.groupValues[1].trim()
            val label = htmlTagRegex.replace(m.groupValues[2], "").trim()
            if (label.isEmpty()) "" else "[$label]($href)"
        }
        out = out.replace(Regex("<b>(.*?)</b>", RegexOption.IGNORE_CASE), "**$1**")
        out = htmlTagRegex.replace(out, "")
        out = out.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
        return out
    }

    private fun parseBlocks(lines: List<String>): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // Fenced code block
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines += lines[i]
                    i++
                }
                if (i < lines.size) i++ // consume closing fence
                out += MdBlock.CodeBlock(lang, codeLines.joinToString("\n"))
                continue
            }

            // Heading
            val headingMatch = headingRegex.matchEntire(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val rawHeading = headingMatch.groupValues[2].trim()
                val spans = parseInline(rawHeading)
                out += MdBlock.Heading(
                    level = level,
                    spans = spans,
                    anchorId = slugify(spansToPlainText(spans)),
                )
                i++
                continue
            }

            // Horizontal rule
            if (hrRegex.matches(line)) {
                out += MdBlock.Divider
                i++
                continue
            }

            // Pipe table
            if (isTableRow(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim())) {
                val headers = splitTableCells(trimmed).map(::parseInline)
                i += 2
                val rows = mutableListOf<List<List<MdSpan>>>()
                while (i < lines.size && isTableRow(lines[i].trim())) {
                    val cells = splitTableCells(lines[i].trim()).map(::parseInline)
                    rows += cells
                    i++
                }
                out += MdBlock.Table(headers = headers, rows = rows)
                continue
            }

            // Blockquote
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                out += MdBlock.BlockQuote(parseInline(quoteLines.joinToString(" ")))
                continue
            }

            // List item (unordered or ordered)
            val ulMatch = unorderedListRegex.matchEntire(line)
            val olMatch = if (ulMatch == null) orderedListRegex.matchEntire(line) else null
            if (ulMatch != null || olMatch != null) {
                val match = (ulMatch ?: olMatch)!!
                val indentSpaces = match.groupValues[1].length
                val marker = match.groupValues[2]
                val bullet = if (marker == "-" || marker == "*") "•" else marker
                val itemText = StringBuilder(match.groupValues[3].trim())
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    val nextTrim = next.trim()
                    if (nextTrim.isEmpty() ||
                        nextTrim.startsWith("```") ||
                        headingRegex.matches(nextTrim) ||
                        hrRegex.matches(next) ||
                        nextTrim.startsWith(">") ||
                        unorderedListRegex.matches(next) ||
                        orderedListRegex.matches(next) ||
                        (isTableRow(nextTrim) && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim()))
                    ) {
                        break
                    }
                    itemText.append(' ').append(nextTrim)
                    i++
                }
                out += MdBlock.ListItem(
                    indentLevel = indentSpaces / 2,
                    bullet = bullet,
                    spans = parseInline(itemText.toString()),
                )
                continue
            }

            // Paragraph
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrim = cur.trim()
                if (curTrim.isEmpty() ||
                    curTrim.startsWith("```") ||
                    headingRegex.matches(curTrim) ||
                    hrRegex.matches(cur) ||
                    curTrim.startsWith(">") ||
                    unorderedListRegex.matches(cur) ||
                    orderedListRegex.matches(cur) ||
                    (isTableRow(curTrim) && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim()))
                ) {
                    break
                }
                paraLines += curTrim
                i++
            }
            if (paraLines.isNotEmpty()) {
                val spans = parseInline(paraLines.joinToString(" "))
                if (spansToPlainText(spans).isNotBlank()) {
                    out += MdBlock.Paragraph(spans)
                }
            }
        }
        return out
    }

    private fun isTableRow(trimmed: String): Boolean =
        trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length >= 2

    private fun isTableSeparator(trimmed: String): Boolean {
        if (!isTableRow(trimmed)) return false
        val cells = splitTableCells(trimmed)
        return cells.isNotEmpty() && cells.all { tableSepCellRegex.matches(it.trim()) }
    }

    private fun splitTableCells(trimmedRow: String): List<String> {
        val inner = trimmedRow.removePrefix("|").removeSuffix("|")
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inCode = false
        var idx = 0
        while (idx < inner.length) {
            val ch = inner[idx]
            if (ch == '`' && (idx == 0 || inner[idx - 1] != '\\')) {
                inCode = !inCode
                current.append(ch)
                idx++
            } else if (ch == '\\' && idx + 1 < inner.length && inner[idx + 1] == '|') {
                current.append('|')
                idx += 2
            } else if (ch == '|' && !inCode) {
                cells += current.toString().trim()
                current.setLength(0)
                idx++
            } else {
                current.append(ch)
                idx++
            }
        }
        cells += current.toString().trim()
        return cells
    }

    fun parseInline(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                spans += MdSpan.Text(plain.toString())
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            // Inline code `...`
            if (text[i] == '`') {
                val end = text.indexOf('`', startIndex = i + 1)
                if (end > i) {
                    flushPlain()
                    val code = text.substring(i + 1, end).replace("\\|", "|")
                    spans += MdSpan.Code(code)
                    i = end + 1
                    continue
                }
            }

            // Bold **...**
            if (text.startsWith("**", startIndex = i)) {
                val end = findClosingDelimiter(text, i + 2, "**")
                if (end > i + 2) {
                    flushPlain()
                    spans += MdSpan.Bold(parseInline(text.substring(i + 2, end)))
                    i = end + 2
                    continue
                }
            }

            // Link [label](href)
            if (text[i] == '[') {
                val closeBracket = findMatchingBracket(text, i)
                if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = findMatchingParen(text, closeBracket + 1)
                    if (closeParen > closeBracket + 2) {
                        flushPlain()
                        val label = text.substring(i + 1, closeBracket)
                        val href = text.substring(closeBracket + 2, closeParen).trim()
                        spans += MdSpan.Link(parseInline(label), href)
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Italic *...*
            if (text[i] == '*' && (i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '*')) {
                val end = findClosingSingleStar(text, i + 1)
                if (end > i + 1) {
                    flushPlain()
                    spans += MdSpan.Italic(parseInline(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            plain.append(text[i])
            i++
        }
        flushPlain()
        return spans
    }

    private fun findClosingDelimiter(text: String, start: Int, delim: String): Int {
        var inCode = false
        var idx = start
        while (idx <= text.length - delim.length) {
            if (text[idx] == '`') inCode = !inCode
            if (!inCode && text.startsWith(delim, startIndex = idx)) return idx
            idx++
        }
        return -1
    }

    private fun findClosingSingleStar(text: String, start: Int): Int {
        var inCode = false
        var idx = start
        while (idx < text.length) {
            if (text[idx] == '`') inCode = !inCode
            if (!inCode && text[idx] == '*') {
                val isDouble = (idx + 1 < text.length && text[idx + 1] == '*') ||
                    (idx > 0 && text[idx - 1] == '*')
                if (!isDouble && text[idx - 1] != ' ') return idx
            }
            idx++
        }
        return -1
    }

    private fun findMatchingBracket(text: String, openIdx: Int): Int {
        var depth = 0
        var inCode = false
        for (idx in openIdx until text.length) {
            val ch = text[idx]
            if (ch == '`') inCode = !inCode
            if (inCode) continue
            if (ch == '[') depth++
            else if (ch == ']') {
                depth--
                if (depth == 0) return idx
            }
        }
        return -1
    }

    private fun findMatchingParen(text: String, openIdx: Int): Int {
        var depth = 0
        for (idx in openIdx until text.length) {
            val ch = text[idx]
            if (ch == '(') depth++
            else if (ch == ')') {
                depth--
                if (depth == 0) return idx
            }
        }
        return -1
    }

    fun spansToPlainText(spans: List<MdSpan>): String = buildString {
        for (span in spans) {
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.Code -> append(span.code)
                is MdSpan.Bold -> append(spansToPlainText(span.spans))
                is MdSpan.Italic -> append(spansToPlainText(span.spans))
                is MdSpan.Link -> append(spansToPlainText(span.spans))
            }
        }
    }

    fun slugify(headingText: String): String =
        headingText.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")

    fun resolveLink(
        currentPagePath: String,
        href: String,
        availablePages: Set<String>,
    ): GuideLinkTarget {
        val trimmed = href.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return GuideLinkTarget.External(trimmed)
        }
        if (trimmed.startsWith("#")) {
            return GuideLinkTarget.Internal(currentPagePath, trimmed.removePrefix("#").ifBlank { null })
        }

        val pathPart = trimmed.substringBefore('#')
        val anchor = trimmed.substringAfter('#', missingDelimiterValue = "").ifBlank { null }

        val baseSegments = currentPagePath.split('/').dropLast(1).toMutableList()
        var escapedDocsRoot = false
        for (seg in pathPart.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    if (baseSegments.isNotEmpty()) {
                        baseSegments.removeAt(baseSegments.lastIndex)
                    } else {
                        escapedDocsRoot = true
                    }
                }
                else -> baseSegments += seg
            }
        }
        val normalized = baseSegments.joinToString("/")

        if (!escapedDocsRoot) {
            if (normalized in availablePages) {
                return GuideLinkTarget.Internal(normalized, anchor)
            }
            val fileName = pathPart.substringAfterLast('/')
            if (fileName in availablePages) {
                return GuideLinkTarget.Internal(fileName, anchor)
            }
            if ("features/$fileName" in availablePages) {
                return GuideLinkTarget.Internal("features/$fileName", anchor)
            }
        }

        if (escapedDocsRoot) {
            return GuideLinkTarget.External(REPO_BLOB_BASE + normalized)
        }
        return if (normalized.endsWith(".md")) {
            GuideLinkTarget.External("${REPO_BLOB_BASE}docs/$normalized")
        } else {
            GuideLinkTarget.External(DOCS_WEB_BASE + normalized)
        }
    }

    fun searchPages(pages: List<GuidePage>, query: String): List<GuideSearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<GuideSearchHit>()

        for (page in pages) {
            val titleMatch = page.title.contains(q, ignoreCase = true)
            val headingMatch = page.blocks.filterIsInstance<MdBlock.Heading>()
                .firstOrNull { spansToPlainText(it.spans).contains(q, ignoreCase = true) }
                ?.let { spansToPlainText(it.spans) }
            val bodyIdx = page.plainText.indexOf(q, ignoreCase = true)

            if (!titleMatch && headingMatch == null && bodyIdx < 0) continue

            val score = when {
                page.title.equals(q, ignoreCase = true) -> 100
                titleMatch -> 80
                headingMatch != null -> 50
                else -> 20
            }
            val snippet = when {
                bodyIdx >= 0 -> extractSnippet(page.plainText, bodyIdx, q.length)
                headingMatch != null -> headingMatch
                else -> page.plainText.lineSequence().firstOrNull().orEmpty().take(120)
            }
            hits += GuideSearchHit(page = page, snippet = snippet, score = score)
        }
        return hits.sortedWith(compareByDescending<GuideSearchHit> { it.score }.thenBy { it.page.title })
    }

    private fun extractSnippet(text: String, matchIdx: Int, matchLen: Int): String {
        val clean = text.replace('\n', ' ').replace(Regex("\\s+"), " ")
        val idx = clean.indexOf(text.substring(matchIdx, matchIdx + matchLen), ignoreCase = true)
            .coerceAtLeast(0)
        val start = (idx - 45).coerceAtLeast(0)
        val end = (idx + matchLen + 75).coerceAtMost(clean.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < clean.length) "…" else ""
        return prefix + clean.substring(start, end).trim() + suffix
    }
}
