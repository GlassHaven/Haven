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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.SelectionRange

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

/** True if the character is a vertical box-drawing border. */
private fun isVerticalBorder(ch: Char): Boolean {
    return ch == '│' || ch == '┃' || ch == '║' || ch == '|' ||
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
 * Extract the selected portion of each line and unwrap soft-wrapped lines.
 * A line that fills the full terminal width is assumed to be soft-wrapped.
 */
private fun extractWithSoftWrapUnwrap(
    fullTexts: List<String>,
    sel: SelectionRange,
    columns: Int,
): String {
    // Extract selected portion of each line
    val selectedTexts = fullTexts.mapIndexed { i, text ->
        val row = sel.startRow + i
        val start = if (row == sel.startRow) sel.startCol else 0
        val end = if (row == sel.endRow) (sel.endCol + 1).coerceAtMost(text.length) else text.length
        if (start < end && start < text.length) {
            text.substring(start, end.coerceAtMost(text.length))
        } else ""
    }

    val result = StringBuilder()
    for (i in selectedTexts.indices) {
        result.append(selectedTexts[i])
        if (i < selectedTexts.size - 1) {
            // If the full line fills the terminal width, it was likely soft-wrapped
            if (fullTexts[i].trimEnd().length >= columns) {
                // Soft-wrapped: join without newline
            } else {
                result.append('\n')
            }
        }
    }
    return result.toString()
}

/**
 * Smart copy: extracts text from the terminal selection with two enhancements:
 * 1. TUI border stripping — detects vertical box-drawing borders and extracts
 *    only the panel content where the selection started.
 * 2. Soft-wrap unwrapping — joins lines that were soft-wrapped at the terminal
 *    width boundary, so copied text reads as it would in a wider terminal.
 *
 * Returns null if the emulator snapshot is unavailable; callers should
 * fall back to [SelectionController.copySelection] in that case.
 */
internal fun smartCopy(
    controller: SelectionController,
    emulator: org.connectbot.terminal.TerminalEmulator,
): String? {
    val sel = controller.getSelectionRange() ?: return null
    val columns = emulator.dimensions.columns
    val snapshotLines = getSnapshotLines(emulator) ?: return null

    val fullTexts = (sel.startRow..sel.endRow).map { row ->
        if (row in snapshotLines.indices) snapshotLines[row] else ""
    }

    val borderCols = findConsistentBorderColumns(fullTexts)

    return if (borderCols.isNotEmpty()) {
        extractPanelContent(fullTexts, borderCols, sel.startCol)
    } else {
        extractWithSoftWrapUnwrap(fullTexts, sel, columns)
    }
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
            if (processed != null) {
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
        // Copy — smart processing happens in SmartTerminalClipboard interceptor
        SelectionIconButton(Icons.Filled.ContentCopy, "Copy") {
            val text = controller.copySelection()
            if (!text.isNullOrEmpty()) {
                clipboardManager.setText(AnnotatedString(text))
                controller.clearSelection()
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }

        // Paste (wrapped in bracket paste sequences when mode 2004 is active)
        SelectionIconButton(Icons.Filled.ContentPaste, "Paste") {
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
        SelectionIconButton(Icons.AutoMirrored.Filled.OpenInNew, "Open") {
            val raw = controller.copySelection()?.trim()
            val joined = raw?.replace(Regex("\\s*\\n\\s*"), "")
            val url = detectUrl(raw) ?: detectUrl(joined) ?: hyperlinkUri
            if (url != null) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    if (url.contains("://")) url else "https://$url"
                )))
                controller.clearSelection()
            } else {
                Toast.makeText(context, "No URL detected", Toast.LENGTH_SHORT).show()
            }
        }

        // Anchor target toggle: Start / End
        SelectionToggleButton(
                label = if (anchorTarget == AnchorTarget.START) "Start" else "End",
                active = anchorTarget == AnchorTarget.START,
                onClick = {
                    anchorTarget = if (anchorTarget == AnchorTarget.END)
                        AnchorTarget.START else AnchorTarget.END
                },
            )

        // D-pad arrows
        SelectionIconButton(Icons.Filled.KeyboardArrowUp, "Up") {
            moveAnchor(anchorMover, anchorTarget, 0, -1)
        }
        SelectionIconButton(Icons.Filled.KeyboardArrowDown, "Down") {
            moveAnchor(anchorMover, anchorTarget, 0, 1)
        }
        SelectionIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") {
            moveAnchor(anchorMover, anchorTarget, -1, 0)
        }
        SelectionIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") {
            moveAnchor(anchorMover, anchorTarget, 1, 0)
        }

        // Dismiss selection
        SelectionIconButton(Icons.Filled.Close, "Cancel") {
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
