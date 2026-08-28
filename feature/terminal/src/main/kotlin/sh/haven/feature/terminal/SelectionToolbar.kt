package sh.haven.feature.terminal

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.SelectionController

private const val TAG = "SelectionToolbar"

/**
 * Helper for moving individual selection anchors via the public
 * SelectionController API (no reflection needed).
 */
private class AnchorMover(private val controller: SelectionController) {
    fun moveStart(dCol: Int, dRow: Int) {
        val range = controller.getSelectionRange() ?: return
        controller.updateSelectionStart(range.startRow + dRow, range.startCol + dCol)
    }
    fun moveEnd(dCol: Int, dRow: Int) {
        val range = controller.getSelectionRange() ?: return
        controller.updateSelectionEnd(range.endRow + dRow, range.endCol + dCol)
    }
}

/**
 * Expand a single-character selection to the word (contiguous non-whitespace
 * token) under the cursor. Called immediately after long-press starts selection.
 *
 * If the token reaches a row edge and adjacent rows contribute URL-safe
 * characters that together form a URL (scheme-prefixed or `www.`), the
 * selection is extended across those continuation rows so long-pressing a
 * wrapped URL selects the whole URL instead of the visible fragment. Fixes
 * the wrapped-URL smartcopy truncation reported against v5.20.x.
 *
 * Uses the public SelectionController API for anchor manipulation.
 */
internal fun expandSelectionToWord(
    controller: SelectionController,
    emulator: org.connectbot.terminal.TerminalEmulator,
) {
    try {
        val range = controller.getSelectionRange() ?: return
        val row = range.startRow
        val col = range.startCol

        val lines = getSnapshotLines(emulator) ?: return
        if (row < 0 || row >= lines.size) return
        val text = lines[row]
        if (col < 0 || col >= text.length) return

        // Don't expand if long-pressed on whitespace
        if (text[col].isWhitespace()) return

        // Expand to contiguous non-whitespace (selects full tokens: paths, URLs, etc.)
        var startCol = col
        while (startCol > 0 && !text[startCol - 1].isWhitespace()) startCol--
        var endCol = col
        while (endCol < text.length - 1 && !text[endCol + 1].isWhitespace()) endCol++

        // Try to extend across URL wrap boundaries when the token reaches a
        // row edge and joins into a URL-looking string on neighbouring rows.
        val columns = try { emulator.dimensions.columns } catch (_: Exception) { 0 }
        val urlRange = expandAcrossUrlWrap(lines, row, startCol, endCol, columns)
        if (urlRange != null) {
            controller.updateSelectionStart(urlRange.startRow, urlRange.startCol)
            controller.updateSelectionEnd(urlRange.endRow, urlRange.endCol)
            return
        }

        // Update selection anchors if expanded
        if (startCol != col || endCol != col) {
            controller.updateSelectionStart(row, startCol)
            controller.updateSelectionEnd(row, endCol)
        }
    } catch (e: Exception) {
        Log.d(TAG, "expandSelectionToWord: ${e.message}")
    }
}

/**
 * Bounds of the full URL a single-row word belongs to, walked outward across
 * wrap-continuation rows. Returns null if the word doesn't extend beyond its
 * row, or if the joined text across rows doesn't look like a URL.
 *
 * A continuation row is one whose terminal-padding-trimmed text ends with a
 * URL-safe character (heading backward) or starts with a URL-safe character
 * at column 0 (heading forward). The final joined text must contain a URL
 * scheme (`://`) or a bare `www.` prefix to guard against sprawling a
 * selection into adjacent prose that happens to lack whitespace at the row
 * boundary.
 */
internal data class UrlSpan(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
)

internal fun expandAcrossUrlWrap(
    lines: List<String>,
    row: Int,
    wordStartCol: Int,
    wordEndCol: Int,
    columns: Int = 0,
): UrlSpan? {
    val currentText = lines[row]
    val trimmedLen = currentText.trimTerminalPadding().length

    var startRow = row
    var startCol = wordStartCol
    var endRow = row
    var endCol = wordEndCol

    // Walk backward into previous rows while the word we're tracking is the
    // first non-whitespace run on its row (column-0 soft-wrap, or a
    // hanging-indent continuation) and the previous row's last non-padding
    // character is URL-safe.
    while (startRow > 0) {
        if (lines[startRow].indexOfFirst { !it.isWhitespace() } != startCol) break
        val prev = lines[startRow - 1].trimTerminalPadding()
        if (prev.isEmpty() || !prev.last().isUrlSafe()) break
        // Find where the URL-safe run starts on `prev` (last whitespace
        // boundary before end). That's our new start column.
        var s = prev.length
        while (s > 0 && !prev[s - 1].isWhitespace() && prev[s - 1].isUrlSafe()) s--
        startRow -= 1
        startCol = s
    }

    // Walk forward into next rows while we're at this row's trimmed edge and
    // the next row contributes a URL-safe run. A column-0 run is a classic
    // soft-wrap. An *indented* run is only treated as a wrap tail when it's a
    // single short run on an otherwise-blank line — the shape of a
    // hanging-indent wrap, as opposed to indented prose (a line full of
    // words) or a table cell (owned by the border/panel path).
    while (endCol >= trimmedLen - 1 && endRow < lines.size - 1) {
        val next = lines[endRow + 1]
        val t0 = next.indexOfFirst { !it.isWhitespace() }
        if (t0 < 0 || !next[t0].isUrlSafe()) break
        // Find where the URL-safe run ends on `next`.
        var e = t0
        while (e < next.length && !next[e].isWhitespace() && next[e].isUrlSafe()) e++
        if (t0 > 0) {
            val afterRun = next.substring(e)
            val runLen = e - t0
            val width = if (columns > 0) columns else next.length
            // More content after the run (prose), or a run that fills much of
            // the line, means this isn't a hanging-indent wrap tail.
            if (afterRun.isNotBlank() || runLen > (width / 2).coerceAtLeast(1)) break
        }
        endRow += 1
        endCol = (e - 1).coerceAtLeast(t0)
    }

    // No continuation found → fall back to single-row word.
    if (startRow == row && endRow == row) return null

    // Verify the joined text actually looks like a URL before using the
    // expanded bounds. "iss" + "ues/89" can form a URL continuation, but we
    // only want to override the word walk when the result is definitely a
    // URL and not just adjacent prose. Continuation rows contribute from
    // their first non-whitespace column so a hanging indent never leaks in.
    val joined = buildString {
        for (r in startRow..endRow) {
            val line = lines[r]
            val from = if (r == startRow) startCol else line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val toExclusive = if (r == endRow) {
                (endCol + 1).coerceAtMost(line.length)
            } else {
                line.trimTerminalPadding().length
            }
            if (from < toExclusive) append(line.substring(from, toExclusive))
        }
    }
    if (!looksLikeFullUrl(joined)) return null

    return UrlSpan(startRow, startCol, endRow, endCol)
}

/** Strip trailing whitespace AND NUL padding that the emulator uses for empty cells. */
private fun String.trimTerminalPadding(): String =
    trimEnd { it.isWhitespace() || it == '\u0000' }

/**
 * Whether a character commonly appears inside URLs. Mirrors termlib's internal
 * helper; duplicated here because termlib marks it `internal` and Haven lives
 * in a different Gradle module.
 */
private fun Char.isUrlSafe(): Boolean =
    isLetterOrDigit() || this in "/:@!$&'()*+,;=-._~%?#[]"

/**
 * Whether a whitespace-free string looks like a *complete* URL token (scheme- or
 * `www.`-prefixed, a dotted host, optional path/query). Pure Kotlin on purpose —
 * `android.util.Patterns.WEB_URL`'s class initializer isn't available in plain
 * JVM unit tests, and the wrap-join logic must stay offline-testable. This is a
 * sanity gate on the joined result, not the primary discriminator (that's the
 * single-run / mostly-blank-line shape test in [expandAcrossUrlWrap]).
 */
private val URL_TOKEN_RE = Regex(
    "^(?:https?://|www\\.)[\\w-]+(?:\\.[\\w-]+)+(?:[/:?#@!\$&'()*+,;=._~%\\[\\]-]\\S*)?$",
    RegexOption.IGNORE_CASE,
)

internal fun looksLikeFullUrl(s: String): Boolean = URL_TOKEN_RE.matches(s)

/**
 * Reconstruct a URL from a multi-row selection by stripping each row's
 * leading/trailing whitespace (the terminal padding and any hanging indent)
 * and joining without newlines. Returns the rebuilt URL only when it forms a
 * single complete URL token — otherwise null so the caller keeps the
 * controller's verbatim (newline-preserving) text. Mirrors the "Open URL"
 * button's existing `\s*\n\s*`-strip, applied to the Copy path.
 */
private fun rebuildWrappedUrl(
    lines: List<String>,
    startRow: Int,
    startCol: Int,
    endRow: Int,
    endCol: Int,
): String? {
    val sb = StringBuilder()
    for (r in startRow..endRow) {
        val line = lines.getOrNull(r) ?: return null
        val from = if (r == startRow) startCol.coerceIn(0, line.length) else 0
        val to = if (r == endRow) (endCol + 1).coerceIn(0, line.length) else line.length
        if (from < to) sb.append(line.substring(from, to).trim())
    }
    val joined = sb.toString()
    return if (looksLikeFullUrl(joined)) joined else null
}

/**
 * Plain-text lines of the terminal's current snapshot. Returns null on
 * any failure so callers can fall back cleanly. Previously used
 * reflection to read the internal `snapshot` StateFlow, which silently
 * broke under Kotlin's `$lib_release` visibility mangling in release
 * builds; now calls `TerminalEmulator.getSnapshotLineTexts()` directly.
 */
private fun getSnapshotLines(
    emulator: org.connectbot.terminal.TerminalEmulator,
): List<String>? {
    return try {
        emulator.getSnapshotLineTexts()
    } catch (e: Exception) {
        Log.d(TAG, "getSnapshotLines: ${e.message}")
        null
    }
}

/**
 * True if the character is a vertical box-drawing border.
 *
 * The plain ASCII pipe is deliberately NOT a border (#581): TUI panels draw
 * with Unicode box-drawing characters, while '|' in ordinary content (paths,
 * command output) at a consistent column used to trigger panel stripping and
 * silently cut the copied text at those columns.
 */
private fun isVerticalBorder(ch: Char): Boolean {
    return ch == '│' || ch == '┃' || ch == '║' ||
        ch == '┆' || ch == '┇' || ch == '┊' || ch == '┋'
}

/**
 * Find column positions where vertical border characters appear consistently
 * across selected lines, indicating TUI panel boundaries.
 */
private fun findConsistentBorderColumns(lines: List<String>): Set<Int> {
    if (lines.size < 2) return emptySet()

    val nonEmptyLines = lines.count { it.isNotBlank() }
    if (nonEmptyLines < 2) return emptySet()

    val maxLen = lines.maxOf { it.length }
    val borderCounts = IntArray(maxLen)

    for (line in lines) {
        for ((col, ch) in line.withIndex()) {
            if (isVerticalBorder(ch)) {
                borderCounts[col]++
            }
        }
    }

    // A column is a consistent border if it has a vertical border in >=60% of non-empty lines
    val threshold = (nonEmptyLines * 0.6).toInt().coerceAtLeast(2)
    return borderCounts.indices.filter { borderCounts[it] >= threshold }.toSet()
}

/**
 * Extract text from the panel that contains [startCol], bounded by
 * consistent vertical border columns.
 */
private fun extractPanelContent(
    lines: List<String>,
    borderCols: Set<Int>,
    startCol: Int,
): String {
    val sortedBorders = borderCols.sorted()
    val leftBorder = sortedBorders.lastOrNull { it < startCol } ?: -1
    val rightBorder = sortedBorders.firstOrNull { it > startCol }
        ?: (lines.maxOfOrNull { it.length } ?: 0)

    return lines.map { line ->
        val start = (leftBorder + 1).coerceAtLeast(0)
        val end = rightBorder.coerceAtMost(line.length)
        if (start < end) line.substring(start, end).trim() else ""
    }.joinToString("\n").trimEnd()
}

/**
 * Smart copy: extracts text from the terminal selection with two enhancements:
 * 1. TUI border stripping — detects vertical box-drawing borders and extracts
 *    only the panel content where the selection started.
 * 2. Soft-wrap unwrapping via [SelectionController.getSelectedText], which
 *    consults libvterm's authoritative `softWrapped` flag on each
 *    `TerminalLine`. Wrapped rows rejoin without `\n`; hard breaks keep
 *    their newlines regardless of whether the row happens to fill the
 *    viewport exactly. Selection rows that have scrolled out of view are
 *    resolved against the live `scrollbackPosition` by the same path.
 *
 * Returns null if the snapshot is unavailable or the controller has no
 * selection text to contribute; [SmartTerminalClipboard.setText] falls back
 * to the caller's `AnnotatedString` in that case.
 */
internal fun smartCopy(
    controller: SelectionController,
    emulator: org.connectbot.terminal.TerminalEmulator,
): String? {
    val sel = controller.getSelectionRange() ?: return null
    val snapshotLines = getSnapshotLines(emulator) ?: return null

    val fullTexts = (sel.startRow..sel.endRow).map { row ->
        if (row in snapshotLines.indices) snapshotLines[row] else ""
    }

    val borderCols = findConsistentBorderColumns(fullTexts)

    if (borderCols.isNotEmpty()) {
        // Border-strip path bypasses soft-wrap rejoin: the panel content is
        // bounded by vertical box-drawing characters, so we keep one line
        // per row regardless of wrap state.
        return extractPanelContent(fullTexts, borderCols, sel.startCol)
    }

    // Non-border path. When the selection spans multiple hard-broken rows that
    // rejoin (indent + newline stripped) into a single complete URL — the
    // shape of an app-wrapped URL like Claude Code's hanging indent —
    // reconstruct it. getSelectedText would otherwise keep the newline and
    // indent because the rows aren't softWrapped. Falls through to the
    // controller's verbatim text when it isn't a clean URL.
    if (sel.endRow > sel.startRow) {
        rebuildWrappedUrl(snapshotLines, sel.startRow, sel.startCol, sel.endRow, sel.endCol)
            ?.let { return it }
    }

    return controller.getSelectedText().ifEmpty { null }
}

/**
 * ClipboardManager wrapper that applies smart copy processing (border stripping,
 * soft-wrap unwrapping) to all text written from the terminal. Used via
 * CompositionLocalProvider to intercept both the toolbar copy button and the
 * library's own popup copy action.
 */
class SmartTerminalClipboard(
    private val delegate: androidx.compose.ui.platform.ClipboardManager,
    private val getEmulator: () -> org.connectbot.terminal.TerminalEmulator,
    private val getController: () -> SelectionController?,
) : androidx.compose.ui.platform.ClipboardManager by delegate {

    override fun setText(annotatedString: AnnotatedString) {
        val controller = getController()
        val emulator = getEmulator()
        if (controller != null) {
            val processed = smartCopy(controller, emulator)
            // Only substitute when smartCopy produced real content. Emptiness
            // means the emulator snapshot has drifted past the selection rows
            // (e.g. new output arrived between long-press and Copy tap) —
            // use the caller's text instead of clobbering the clipboard
            // with an empty string.
            if (!processed.isNullOrBlank()) {
                delegate.setText(AnnotatedString(processed))
                return
            }
        }
        delegate.setText(annotatedString)
    }
}

/** Which selection anchor the d-pad arrows control. */
private enum class AnchorTarget { START, END }

@Composable
fun SelectionToolbar(
    controller: SelectionController,
    hyperlinkUri: String? = null,
    bracketPasteMode: Boolean = false,
    onPaste: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        SelectionToolbarContent(
            controller = controller,
            hyperlinkUri = hyperlinkUri,
            bracketPasteMode = bracketPasteMode,
            onPaste = onPaste,
        )
    }
}

/**
 * Selection toolbar row content without a Surface wrapper.
 * Used by [KeyboardToolbar] to embed selection controls in place of a keyboard
 * row, keeping total toolbar height constant.
 */
@Composable
fun SelectionToolbarContent(
    controller: SelectionController,
    hyperlinkUri: String? = null,
    bracketPasteMode: Boolean = false,
    onPaste: (String) -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val anchorMover = remember(controller) { AnchorMover(controller) }
    var anchorTarget by remember { mutableStateOf(AnchorTarget.END) }

    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val copyLabel = stringResource(R.string.terminal_selection_copy)
        val pasteLabel = stringResource(R.string.terminal_selection_paste)
        val openLabel = stringResource(R.string.terminal_selection_open)
        val copiedMsg = stringResource(R.string.terminal_selection_copied)
        val noUrlMsg = stringResource(R.string.terminal_selection_no_url)
        val anchorStartLabel = stringResource(R.string.terminal_selection_anchor_start)
        val anchorEndLabel = stringResource(R.string.terminal_selection_anchor_end)
        val upLabel = stringResource(R.string.terminal_selection_up)
        val downLabel = stringResource(R.string.terminal_selection_down)
        val leftLabel = stringResource(R.string.terminal_selection_left)
        val rightLabel = stringResource(R.string.terminal_selection_right)
        val cancelLabel = stringResource(R.string.terminal_selection_cancel)

        // Copy — smart processing happens in SmartTerminalClipboard interceptor
        SelectionIconButton(Icons.Filled.ContentCopy, copyLabel) {
            val text = controller.copySelection()
            if (!text.isNullOrEmpty()) {
                clipboardManager.setText(AnnotatedString(text))
                controller.clearSelection()
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            }
        }

        // Paste (wrapped in bracket paste sequences when mode 2004 is active)
        SelectionIconButton(Icons.Filled.ContentPaste, pasteLabel) {
            val text = clipboardManager.getText()?.text
            if (!text.isNullOrEmpty()) {
                controller.clearSelection()
                if (bracketPasteMode) {
                    onPaste("\u001b[200~$text\u001b[201~")
                } else {
                    onPaste(text)
                }
            }
        }

        // Open URL (detected in selection text, or from OSC 8 hyperlink)
        // Try the raw selection first, then with newlines stripped to handle
        // URLs split across lines by the program or terminal wrapping.
        SelectionIconButton(Icons.AutoMirrored.Filled.OpenInNew, openLabel) {
            val raw = controller.copySelection()?.trim()
            val joined = raw?.replace(Regex("\\s*\\n\\s*"), "")
            val url = detectUrl(raw) ?: detectUrl(joined) ?: hyperlinkUri
            if (url != null) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    if (url.contains("://")) url else "https://$url"
                )))
                controller.clearSelection()
            } else {
                Toast.makeText(context, noUrlMsg, Toast.LENGTH_SHORT).show()
            }
        }

        // Anchor target toggle: Start / End
        SelectionToggleButton(
                label = if (anchorTarget == AnchorTarget.START) anchorStartLabel else anchorEndLabel,
                active = anchorTarget == AnchorTarget.START,
                onClick = {
                    anchorTarget = if (anchorTarget == AnchorTarget.END)
                        AnchorTarget.START else AnchorTarget.END
                },
            )

        // D-pad arrows
        SelectionIconButton(Icons.Filled.KeyboardArrowUp, upLabel) {
            moveAnchor(anchorMover, anchorTarget, 0, -1)
        }
        SelectionIconButton(Icons.Filled.KeyboardArrowDown, downLabel) {
            moveAnchor(anchorMover, anchorTarget, 0, 1)
        }
        SelectionIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, leftLabel) {
            moveAnchor(anchorMover, anchorTarget, -1, 0)
        }
        SelectionIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, rightLabel) {
            moveAnchor(anchorMover, anchorTarget, 1, 0)
        }

        // Dismiss selection
        SelectionIconButton(Icons.Filled.Close, cancelLabel) {
            controller.clearSelection()
        }
    }
}

private fun moveAnchor(
    mover: AnchorMover,
    target: AnchorTarget,
    dCol: Int,
    dRow: Int,
) {
    if (target == AnchorTarget.START) {
        mover.moveStart(dCol, dRow)
    } else {
        mover.moveEnd(dCol, dRow)
    }
}

@Composable
private fun SelectionToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = if (active) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun SelectionIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

/**
 * Detect a URL in the selected text. Returns null if no URL found.
 * Auto-adds "https://" if the matched text has no scheme.
 */
internal fun detectUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val matcher = Patterns.WEB_URL.matcher(text)
    if (!matcher.find()) return null
    val url = matcher.group()
    return if (url.contains("://")) url else "https://$url"
}
