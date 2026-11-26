package sh.haven.core.ui

import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Verifies against real Compose text-input sessions that [PrivateKeyboard] and [PasswordField]
 * set the integer [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] (`0x01000000`) bit on
 * [EditorInfo.imeOptions] — both while masked and after revealing cleartext via the eye toggle.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
class PrivateKeyboardTest {

    private fun mountCompose(content: @Composable () -> Unit): ComposeView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val composeView = ComposeView(activity).apply {
            setContent(content)
        }
        activity.setContentView(
            composeView,
            ViewGroup.LayoutParams(400, 400),
        )
        composeView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
        )
        composeView.layout(0, 0, 400, 400)
        drainMainLooper()
        return composeView
    }

    private fun drainMainLooper() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun findNodeByContentDescription(node: SemanticsNode, desc: String): SemanticsNode? {
        val list = node.config.getOrNull(SemanticsProperties.ContentDescription)
        if (list != null && desc in list) return node
        for (child in node.children) {
            findNodeByContentDescription(child, desc)?.let { return it }
        }
        return null
    }

    @Test
    fun `PasswordField sets IME_FLAG_NO_PERSONALIZED_LEARNING when masked and uses VisiblePassword when revealed`() {
        var latestRequest: PlatformTextInputMethodRequest? = null
        val captureInterceptor = PlatformTextInputInterceptor { request, _ ->
            latestRequest = request
            awaitCancellation()
        }
        val focusRequester = FocusRequester()
        var value by mutableStateOf("correct-horse-battery")

        val composeView = mountCompose {
            InterceptPlatformTextInput(interceptor = captureInterceptor) {
                PasswordField(
                    value = value,
                    onValueChange = { value = it },
                    label = "Password",
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        }

        assertTrue(composeView.requestFocus())
        focusRequester.requestFocus()
        drainMainLooper()

        val maskedRequest = latestRequest
        assertNotNull("Expected PasswordField focus to start an input session", maskedRequest)
        val maskedEditorInfo = EditorInfo()
        maskedRequest!!.createInputConnection(maskedEditorInfo)

        assertTrue(
            "Expected IME_FLAG_NO_PERSONALIZED_LEARNING (0x01000000) in masked imeOptions=0x${maskedEditorInfo.imeOptions.toString(16)}",
            (maskedEditorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
        )
        assertEquals(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            maskedEditorInfo.inputType and InputType.TYPE_MASK_VARIATION,
        )
        assertEquals(
            "Masked PasswordField must not set TYPE_TEXT_FLAG_AUTO_CORRECT",
            0,
            maskedEditorInfo.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
        )

        // Tap the eye icon via merged SemanticsActions.OnClick to reveal cleartext:
        val rootForTest = composeView.getChildAt(0) as RootForTest
        val showNode = findNodeByContentDescription(rootForTest.semanticsOwner.rootSemanticsNode, "Show password")
        assertNotNull("Expected 'Show password' trailing icon button semantics node", showNode)
        val clicked = showNode!!.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        assertEquals(true, clicked)
        drainMainLooper()

        val revealedRequest = latestRequest
        assertNotNull("Expected revealed PasswordField to keep/restart input session", revealedRequest)
        val revealedEditorInfo = EditorInfo()
        revealedRequest!!.createInputConnection(revealedEditorInfo)

        assertTrue(
            "Expected IME_FLAG_NO_PERSONALIZED_LEARNING (0x01000000) in revealed imeOptions=0x${revealedEditorInfo.imeOptions.toString(16)}",
            (revealedEditorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
        )
        assertEquals(
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            revealedEditorInfo.inputType and InputType.TYPE_MASK_VARIATION,
        )
        assertEquals(
            "Revealed PasswordField must not set TYPE_TEXT_FLAG_AUTO_CORRECT",
            0,
            revealedEditorInfo.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
        )
    }

    @Test
    fun `PrivateKeyboard sets IME_FLAG_NO_PERSONALIZED_LEARNING on OutlinedTextField and BasicTextField`() {
        var latestRequest: PlatformTextInputMethodRequest? = null
        val captureInterceptor = PlatformTextInputInterceptor { request, _ ->
            latestRequest = request
            awaitCancellation()
        }
        val wgFocus = FocusRequester()
        val overlayFocus = FocusRequester()

        val composeView = mountCompose {
            InterceptPlatformTextInput(interceptor = captureInterceptor) {
                PrivateKeyboard {
                    Column {
                        OutlinedTextField(
                            value = "[Interface]\nPrivateKey = secret",
                            onValueChange = {},
                            modifier = Modifier.focusRequester(wgFocus),
                        )
                        BasicTextField(
                            value = "compose-overlay",
                            onValueChange = {},
                            modifier = Modifier.focusRequester(overlayFocus),
                        )
                    }
                }
            }
        }

        assertTrue(composeView.requestFocus())
        wgFocus.requestFocus()
        drainMainLooper()
        val wgInfo = EditorInfo()
        assertNotNull(latestRequest)
        latestRequest!!.createInputConnection(wgInfo)
        assertTrue(
            "Expected IME_FLAG_NO_PERSONALIZED_LEARNING on PrivateKeyboard OutlinedTextField",
            (wgInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
        )

        overlayFocus.requestFocus()
        drainMainLooper()
        val overlayInfo = EditorInfo()
        assertNotNull(latestRequest)
        latestRequest!!.createInputConnection(overlayInfo)
        assertTrue(
            "Expected IME_FLAG_NO_PERSONALIZED_LEARNING on PrivateKeyboard BasicTextField",
            (overlayInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
        )
    }
}
