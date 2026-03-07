package sh.haven.feature.terminal

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

// VT100/xterm escape sequences for special keys
private const val ESC = "\u001b"
private val KEY_ESC = byteArrayOf(0x1b)
private val KEY_TAB = byteArrayOf(0x09)
private val KEY_SHIFT_TAB = "$ESC[Z".toByteArray() // CSI Z = Shift+Tab (backtab)
private val KEY_UP = "$ESC[A".toByteArray()
private val KEY_DOWN = "$ESC[B".toByteArray()
private val KEY_RIGHT = "$ESC[C".toByteArray()
private val KEY_LEFT = "$ESC[D".toByteArray()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardToolbar(
    onSendBytes: (ByteArray) -> Unit,
    focusRequester: FocusRequester,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    onToggleCtrl: () -> Unit = {},
    onToggleAlt: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var shiftActive by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Toggle keyboard
            ToolbarIconButton(Icons.Filled.Keyboard, "Toggle keyboard") {
                val window = (view.context as? Activity)?.window ?: return@ToolbarIconButton
                val controller = WindowCompat.getInsetsController(window, view)
                if (imeVisible) {
                    controller.hide(WindowInsetsCompat.Type.ime())
                } else {
                    focusRequester.requestFocus()
                    controller.show(WindowInsetsCompat.Type.ime())
                }
            }

            // Paste
            ToolbarIconButton(Icons.Filled.ContentPaste, "Paste") {
                val text = clipboardManager.getText()?.text
                if (!text.isNullOrEmpty()) {
                    onSendBytes(text.toByteArray())
                }
            }

            // Esc
            ToolbarTextButton("Esc") { onSendBytes(KEY_ESC) }

            // Tab (sends Shift+Tab / backtab when shift is active)
            ToolbarTextButton("Tab") {
                if (shiftActive) {
                    onSendBytes(KEY_SHIFT_TAB)
                    shiftActive = false
                } else {
                    onSendBytes(KEY_TAB)
                }
            }

            // Shift (sticky toggle)
            ToolbarToggleButton(
                label = "Shift",
                active = shiftActive,
                onClick = { shiftActive = !shiftActive },
            )

            // Ctrl (sticky toggle)
            ToolbarToggleButton(
                label = "Ctrl",
                active = ctrlActive,
                onClick = onToggleCtrl,
            )

            // Alt (sticky toggle)
            ToolbarToggleButton(
                label = "Alt",
                active = altActive,
                onClick = onToggleAlt,
            )

            // Arrow keys
            ToolbarIconButton(Icons.Filled.KeyboardArrowUp, "Up") { onSendBytes(KEY_UP) }
            ToolbarIconButton(Icons.Filled.KeyboardArrowDown, "Down") { onSendBytes(KEY_DOWN) }
            ToolbarIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { onSendBytes(KEY_LEFT) }
            ToolbarIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { onSendBytes(KEY_RIGHT) }

            // Common symbols
            ToolbarTextButton("|") { sendChar('|', ctrlActive, altActive, onSendBytes); if (ctrlActive) onToggleCtrl(); if (altActive) onToggleAlt() }
            ToolbarTextButton("~") { sendChar('~', ctrlActive, altActive, onSendBytes); if (ctrlActive) onToggleCtrl(); if (altActive) onToggleAlt() }
            ToolbarTextButton("/") { sendChar('/', ctrlActive, altActive, onSendBytes); if (ctrlActive) onToggleCtrl(); if (altActive) onToggleAlt() }
            ToolbarTextButton("-") { sendChar('-', ctrlActive, altActive, onSendBytes); if (ctrlActive) onToggleCtrl(); if (altActive) onToggleAlt() }
        }
    }
}

private fun sendChar(
    char: Char,
    ctrl: Boolean,
    alt: Boolean,
    onSendBytes: (ByteArray) -> Unit,
) {
    val byte = if (ctrl && char.code in 0x40..0x7F) {
        // Ctrl+char = char AND 0x1F
        byteArrayOf((char.code and 0x1F).toByte())
    } else {
        char.toString().toByteArray()
    }

    if (alt) {
        // Alt prefix = ESC + char
        onSendBytes(byteArrayOf(0x1b) + byte)
    } else {
        onSendBytes(byte)
    }
}

@Composable
private fun ToolbarTextButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun ToolbarToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 2.dp),
        colors = if (active) {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun ToolbarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
