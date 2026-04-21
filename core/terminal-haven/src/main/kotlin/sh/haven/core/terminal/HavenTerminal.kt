package sh.haven.core.terminal

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.ComposeController
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalGestureCallback

/**
 * Haven-owned wrapper around termlib's [Terminal] composable.
 *
 * The long-term goal is to keep termlib's surface upstream-shaped and land
 * Haven's opinionated UX (keyboard modes, future IME composer overlay
 * placement, toolbar wiring) here instead. Today the wrapper adapts Haven's
 * [HavenKeyboardMode] enum onto termlib's pair of booleans; the floating
 * IME composer overlay still lives inside termlib's Canvas draw block
 * because it needs internal cursor/char metrics. Once upstream exposes
 * those (PR-G in the realignment plan) the overlay moves here too and
 * termlib's fork patch drops.
 */
@Composable
fun HavenTerminal(
    terminalEmulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.MONOSPACE,
    initialFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 30.sp,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    keyboardEnabled: Boolean = false,
    showSoftKeyboard: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onTerminalTap: () -> Unit = {},
    onTerminalDoubleTap: () -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    forcedSize: Pair<Int, Int>? = null,
    modifierManager: ModifierManager? = null,
    onSelectionControllerAvailable: ((SelectionController) -> Unit)? = null,
    onHyperlinkClick: (String) -> Unit = {},
    onComposeControllerAvailable: ((ComposeController) -> Unit)? = null,
    onFontSizeChanged: ((TextUnit) -> Unit)? = null,
    gestureCallback: TerminalGestureCallback? = null,
    onPasteShortcut: (() -> Unit)? = null,
    onPasteRequest: (() -> Unit)? = null,
    keyboardMode: HavenKeyboardMode = HavenKeyboardMode.Secure,
) {
    Terminal(
        terminalEmulator = terminalEmulator,
        modifier = modifier,
        typeface = typeface,
        initialFontSize = initialFontSize,
        minFontSize = minFontSize,
        maxFontSize = maxFontSize,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
        keyboardEnabled = keyboardEnabled,
        showSoftKeyboard = showSoftKeyboard,
        focusRequester = focusRequester,
        onTerminalTap = onTerminalTap,
        onTerminalDoubleTap = onTerminalDoubleTap,
        onImeVisibilityChanged = onImeVisibilityChanged,
        forcedSize = forcedSize,
        modifierManager = modifierManager,
        onSelectionControllerAvailable = onSelectionControllerAvailable,
        onHyperlinkClick = onHyperlinkClick,
        onComposeControllerAvailable = onComposeControllerAvailable,
        onFontSizeChanged = onFontSizeChanged,
        gestureCallback = gestureCallback,
        allowStandardKeyboard = keyboardMode is HavenKeyboardMode.Standard,
        rawKeyboardMode = keyboardMode is HavenKeyboardMode.Raw,
        onPasteShortcut = onPasteShortcut,
        onPasteRequest = onPasteRequest,
    )
}
