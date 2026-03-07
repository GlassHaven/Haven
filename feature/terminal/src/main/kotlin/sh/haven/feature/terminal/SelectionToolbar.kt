package sh.haven.feature.terminal

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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

private const val TAG = "SelectionToolbar"

/**
 * Reflective helper for moving individual selection anchors.
 *
 * The connectbot termlib library marks SelectionManager and SelectionRange as
 * `internal`, so we can't reference them at compile time. The anonymous
 * SelectionController implementation captures the SelectionManager in a field
 * named `$selectionManager`. We use reflection to read the current selection
 * range and call updateSelectionStart / updateSelectionEnd independently.
 */
private class AnchorMover(controller: SelectionController) {
    private val manager: Any? = try {
        val field = controller.javaClass.getDeclaredField("\$selectionManager")
        field.isAccessible = true
        field.get(controller)
    } catch (e: Exception) {
        Log.d(TAG, "Could not extract SelectionManager: ${e.message}")
        null
    }

    val available: Boolean = manager != null

    fun moveStart(dCol: Int, dRow: Int) = moveAnchorInternal("Start", dCol, dRow)
    fun moveEnd(dCol: Int, dRow: Int) = moveAnchorInternal("End", dCol, dRow)

    private fun moveAnchorInternal(which: String, dCol: Int, dRow: Int) {
        val mgr = manager ?: return
        try {
            val rangeGetter = mgr.javaClass.getMethod("getSelectionRange")
            val range = rangeGetter.invoke(mgr) ?: return
            val row = range.javaClass.getMethod("get${which}Row").invoke(range) as Int
            val col = range.javaClass.getMethod("get${which}Col").invoke(range) as Int
            val update = mgr.javaClass.getMethod("updateSelection$which", Int::class.java, Int::class.java)
            update.invoke(mgr, row + dRow, col + dCol)
        } catch (e: Exception) {
            Log.d(TAG, "AnchorMover.move$which failed: ${e.message}")
        }
    }
}

/**
 * Read the current selection range (startRow, startCol, endRow, endCol)
 * from the library-internal SelectionManager via reflection.
 * Returns null if no selection is active or reflection fails.
 */
internal fun getSelectionRange(
    controller: org.connectbot.terminal.SelectionController,
): SelectionPosition? {
    try {
        val mgrField = controller.javaClass.getDeclaredField("\$selectionManager")
        mgrField.isAccessible = true
        val mgr = mgrField.get(controller) ?: return null
        val range = mgr.javaClass.getMethod("getSelectionRange").invoke(mgr) ?: return null
        val startRow = range.javaClass.getMethod("getStartRow").invoke(range) as Int
        val startCol = range.javaClass.getMethod("getStartCol").invoke(range) as Int
        val endRow = range.javaClass.getMethod("getEndRow").invoke(range) as Int
        val endCol = range.javaClass.getMethod("getEndCol").invoke(range) as Int
        return SelectionPosition(startRow, startCol, endRow, endCol)
    } catch (e: Exception) {
        Log.d(TAG, "getSelectionRange: ${e.message}")
        return null
    }
}

internal data class SelectionPosition(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
)

/**
 * Expand a single-character selection to the word (contiguous non-whitespace
 * token) under the cursor. Called immediately after long-press starts selection.
 *
 * Uses reflection to access:
 * - SelectionManager from the SelectionController (library-internal)
 * - TerminalSnapshot from the TerminalEmulator (library-internal getSnapshot$lib)
 */
internal fun expandSelectionToWord(
    controller: SelectionController,
    emulator: org.connectbot.terminal.TerminalEmulator,
) {
    try {
        // Get SelectionManager from controller
        val mgrField = controller.javaClass.getDeclaredField("\$selectionManager")
        mgrField.isAccessible = true
        val mgr = mgrField.get(controller) ?: return

        // Get current selection position
        val range = mgr.javaClass.getMethod("getSelectionRange").invoke(mgr) ?: return
        val row = range.javaClass.getMethod("getStartRow").invoke(range) as Int
        val col = range.javaClass.getMethod("getStartCol").invoke(range) as Int

        // Get snapshot from emulator via reflection (getSnapshot$lib is library-internal)
        val snapshotFlow = emulator.javaClass.getMethod("getSnapshot\$lib").invoke(emulator)
        val snapshot = snapshotFlow.javaClass.getMethod("getValue").invoke(snapshotFlow)
            ?: return

        // Get line text at selection row
        @Suppress("UNCHECKED_CAST")
        val lines = snapshot.javaClass.getMethod("getLines").invoke(snapshot) as List<Any>
        if (row < 0 || row >= lines.size) return
        val line = lines[row]
        val text = line.javaClass.getMethod("getText").invoke(line) as String
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
            val updateStart = mgr.javaClass.getMethod(
                "updateSelectionStart", Int::class.java, Int::class.java,
            )
            val updateEnd = mgr.javaClass.getMethod(
                "updateSelectionEnd", Int::class.java, Int::class.java,
            )
            updateStart.invoke(mgr, row, startCol)
            updateEnd.invoke(mgr, row, endCol)
        }
    } catch (e: Exception) {
        Log.d(TAG, "expandSelectionToWord: ${e.message}")
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
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val anchorMover = remember(controller) { AnchorMover(controller) }
    var anchorTarget by remember { mutableStateOf(AnchorTarget.END) }

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Copy
            SelectionIconButton(Icons.Filled.ContentCopy, "Copy") {
                val text = controller.copySelection()
                if (!text.isNullOrEmpty()) {
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
                controller.clearSelection()
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
            SelectionIconButton(Icons.AutoMirrored.Filled.OpenInNew, "Open") {
                val text = controller.copySelection()?.trim()
                val url = detectUrl(text) ?: hyperlinkUri
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
            if (anchorMover.available) {
                SelectionToggleButton(
                    label = if (anchorTarget == AnchorTarget.START) "Start" else "End",
                    active = anchorTarget == AnchorTarget.START,
                    onClick = {
                        anchorTarget = if (anchorTarget == AnchorTarget.END)
                            AnchorTarget.START else AnchorTarget.END
                    },
                )
            }

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
        modifier = Modifier.padding(horizontal = 2.dp),
        colors = if (active) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun SelectionIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
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
