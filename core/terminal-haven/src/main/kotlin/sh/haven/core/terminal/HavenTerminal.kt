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
    ambiwidthMode: org.connectbot.terminal.AmbiwidthMode = org.connectbot.terminal.AmbiwidthMode.AUTO_NARROW,
    initialFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 30.sp,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    /** Alpha for the terminal's screen-fill background (0.0–1.0). Below 1 the
     *  empty areas reveal whatever sits behind the Canvas. Default 1 = opaque. */
    backgroundOpacity: Float = 1f,
    keyboardEnabled: Boolean = false,
    showSoftKeyboard: Boolean = true,
    imeRestoreTick: Int = 0,
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
    /** See Terminal's scrollQuantumMultiplier — >1 = coarser swipe-to-key steps (#524). */
    scrollQuantumMultiplier: Float = 1f,
    onPasteShortcut: (() -> Unit)? = null,
    onPasteRequest: (() -> Unit)? = null,
    keyboardMode: HavenKeyboardMode = HavenKeyboardMode.Secure,
    tapToPositionCursorOnPrompt: Boolean = false,
    onScrollControllerAvailable: ((org.connectbot.terminal.ScrollController) -> Unit)? = null,
    onGestureInjectorReady: ((org.connectbot.terminal.GestureInjector) -> Unit)? = null,
    reflowOnKeyboard: Boolean = false,
    /** True while the remote app has mouse tracking on — see Terminal. (#435) */
    mouseModeActive: Boolean = false,
) {
    Terminal(
        terminalEmulator = terminalEmulator,
        modifier = modifier,
        typeface = typeface,
        ambiwidthMode = ambiwidthMode,
        initialFontSize = initialFontSize,
        minFontSize = minFontSize,
        maxFontSize = maxFontSize,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
        backgroundOpacity = backgroundOpacity,
        keyboardEnabled = keyboardEnabled,
        showSoftKeyboard = showSoftKeyboard,
        imeRestoreTick = imeRestoreTick,
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
        scrollQuantumMultiplier = scrollQuantumMultiplier,
        mouseModeActive = mouseModeActive,
        allowStandardKeyboard = keyboardMode is HavenKeyboardMode.Standard,
        rawKeyboardMode = keyboardMode is HavenKeyboardMode.Raw,
        customImeFlags = (keyboardMode as? HavenKeyboardMode.Custom)?.let {
            org.connectbot.terminal.ImeFlagBundle(
                noSuggestions = it.flags.noSuggestions,
                visiblePassword = it.flags.visiblePassword,
                autoCorrect = it.flags.autoCorrect,
                fullEditor = it.flags.fullEditor,
                noExtractUi = it.flags.noExtractUi,
                noPersonalizedLearning = it.flags.noPersonalizedLearning,
            )
        },
        onPasteShortcut = onPasteShortcut,
        onPasteRequest = onPasteRequest,
        tapToPositionCursorOnPrompt = tapToPositionCursorOnPrompt,
        onScrollControllerAvailable = onScrollControllerAvailable,
        onGestureInjectorReady = onGestureInjectorReady,
        reflowOnKeyboard = reflowOnKeyboard,
    )
}
