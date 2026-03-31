package sh.haven.core.wayland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.toolbar.KeyboardToolbar

/** Evdev keycodes for vterm key constants used by the terminal toolbar. */
private const val VTERM_KEY_UP = 5
private const val VTERM_KEY_DOWN = 6
private const val VTERM_KEY_LEFT = 7
private const val VTERM_KEY_RIGHT = 8
private const val VTERM_KEY_HOME = 11
private const val VTERM_KEY_END = 12
private const val VTERM_KEY_PAGEUP = 13
private const val VTERM_KEY_PAGEDOWN = 14
private const val VTERM_KEY_DEL = 15
private const val VTERM_KEY_INS = 16
private const val VTERM_KEY_FUNCTION_0 = 256

private fun vtermKeyToEvdev(key: Int): Int = when (key) {
    VTERM_KEY_UP -> 103
    VTERM_KEY_DOWN -> 108
    VTERM_KEY_LEFT -> 105
    VTERM_KEY_RIGHT -> 106
    VTERM_KEY_HOME -> 102
    VTERM_KEY_END -> 107
    VTERM_KEY_PAGEUP -> 104
    VTERM_KEY_PAGEDOWN -> 109
    VTERM_KEY_DEL -> 111
    VTERM_KEY_INS -> 110
    in (VTERM_KEY_FUNCTION_0 + 1)..(VTERM_KEY_FUNCTION_0 + 12) -> {
        val fn = key - VTERM_KEY_FUNCTION_0 // 1-12
        if (fn <= 10) 58 + fn else 85 + fn // F1=59..F10=68, F11=87, F12=88
    }
    else -> -1
}

/**
 * Wraps the shared [KeyboardToolbar] for the Wayland desktop.
 * Translates the terminal's byte/vterm callbacks into evdev keycodes
 * sent via [WaylandBridge.nativeSendKey].
 */
@Composable
fun WaylandToolbar(
    layout: ToolbarLayout = ToolbarLayout.DEFAULT,
    navBlockMode: NavBlockMode = NavBlockMode.ALIGNED,
    modifier: Modifier = Modifier,
) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    KeyboardToolbar(
        onSendBytes = { bytes ->
            when {
                bytes.contentEquals(byteArrayOf(0x1b)) -> sendEvdevPress(1) // Esc
                bytes.contentEquals(byteArrayOf(0x09)) -> sendEvdevPress(15) // Tab
                // Ctrl+letter (0x01-0x1a) — Ctrl already held via toggle
                bytes.size == 1 && bytes[0] in 1..26 -> {
                    val ch = 'a' + (bytes[0].toInt() - 1)
                    sendCharAsEvdev(ch)
                }
                else -> String(bytes, Charsets.UTF_8).forEach { ch -> sendCharAsEvdev(ch) }
            }
        },
        onDispatchKey = { _, key ->
            val evdev = vtermKeyToEvdev(key)
            if (evdev > 0) sendEvdevPress(evdev)
        },
        focusRequester = focusRequester,
        ctrlActive = ctrlActive,
        altActive = altActive,
        layout = layout,
        navBlockMode = navBlockMode,
        onToggleCtrl = {
            ctrlActive = !ctrlActive
            WaylandBridge.nativeSendKey(29, if (!ctrlActive) 0 else 1)
        },
        onToggleAlt = {
            altActive = !altActive
            WaylandBridge.nativeSendKey(56, if (!altActive) 0 else 1)
        },
        modifier = modifier,
    )
}

private fun sendEvdevPress(evdev: Int) {
    WaylandBridge.nativeSendKey(evdev, 1)
    WaylandBridge.nativeSendKey(evdev, 0)
}
