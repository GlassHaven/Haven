package sh.haven.core.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Intercepts Compose platform text input startup to set the integer bit
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] (`0x01000000`) on [EditorInfo.imeOptions].
 *
 * Compose's `PlatformImeOptions("flagNoPersonalizedLearning")` only writes a literal string to
 * [EditorInfo.privateImeOptions], which mainstream Android IMEs (Gboard, Samsung Keyboard,
 * HeliBoard, FlorisBoard, AOSP LatinIME) ignore while `EditorInfo_androidKt.update` overwrites
 * [EditorInfo.imeOptions] without setting [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING].
 *
 * Because `PlatformTextInputModifierNode.establishTextInputSession` resolves interceptors via
 * a `CompositionLocal` (`LocalChainedPlatformTextInputInterceptor`) on the text field's
 * `LayoutNode`, wrapping the text field in [PrivateKeyboard] (which emits no extra `LayoutNode`)
 * intercepts the inner `CoreTextField` / `BasicTextField` input session without affecting parent
 * `Row` / `Column` layout modifiers such as `weight`.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal val PrivateKeyboardInterceptor = PlatformTextInputInterceptor { request, nextSession ->
    val privateRequest = PlatformTextInputMethodRequest { outAttrs ->
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        request.createInputConnection(outAttrs).also {
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }
    nextSession.startInputMethod(privateRequest)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = PrivateKeyboardInterceptor,
        content = content,
    )
}
