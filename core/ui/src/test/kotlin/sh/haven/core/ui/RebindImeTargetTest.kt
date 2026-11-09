package sh.haven.core.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [rebindImeTarget] must leave the terminal's editor focused and restart input
 * on it — the effect of a background round trip — including when the first
 * Compose focus request is a no-op because another view kept Android focus.
 */
@RunWith(RobolectricTestRunner::class)
class RebindImeTargetTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** Stand-in for termlib's ImeInputView: a focusable text editor. */
    private class Editor(context: Context) : View(context) {
        override fun onCheckIsTextEditor() = true
    }

    private class Fixture(context: Context) {
        val other = View(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val editor = Editor(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val holder = FrameLayout(context)
        val root = LinearLayout(context).apply {
            addView(other, ViewGroup.LayoutParams(SIZE, SIZE))
            addView(holder, ViewGroup.LayoutParams(SIZE, SIZE))
        }
        val restarted = mutableListOf<View>()
        var clears = 0

        init {
            holder.addView(editor, ViewGroup.LayoutParams(SIZE, SIZE))
            Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(root)
            listOf(root, other, holder, editor).forEach { it.layout(0, 0, SIZE, SIZE) }
        }

        /** Run [rebindImeTarget]; [requestFocus] is the fake Compose request. */
        fun rebind(requestFocus: () -> Unit) {
            rebindImeTarget(
                root.rootView,
                requestFocus = requestFocus,
                clearFocus = { clears++ },
                restartInput = { restarted += it },
            )
            shadowOf(Looper.getMainLooper()).idle()
        }

        private companion object {
            const val SIZE = 16
        }
    }

    @Test
    fun focusedEditorJustGetsAFreshInputConnection() {
        val f = Fixture(context)
        assertTrue(f.editor.requestFocus())

        f.rebind { f.editor.requestFocus() }

        assertEquals(listOf<View>(f.editor), f.restarted)
        assertEquals("no clear needed when the editor already has focus", 0, f.clears)
    }

    @Test
    fun aNoOpFocusRequestIsRetriedAfterClearingFocus() {
        val f = Fixture(context)
        assertTrue(f.other.requestFocus()) // a non-editor kept Android focus
        var requests = 0

        // First request does nothing (Compose thinks the node is focused);
        // only the retry after clearFocus actually moves focus.
        f.rebind {
            requests++
            if (requests >= 2) f.editor.requestFocus()
        }

        assertEquals(1, f.clears)
        assertSame(f.editor, f.root.findFocus())
        assertEquals(listOf<View>(f.editor), f.restarted)
    }

    @Test
    fun aStrandedEditorIsRelinkedAndRestartedWithoutClearing() {
        val f = Fixture(context)
        assertTrue(f.editor.requestFocus())
        f.holder.clearChildFocus(f.editor) // what an interop teardown leaves

        f.rebind { /* Compose believes it is focused: no-op */ }

        assertEquals(0, f.clears)
        assertSame(f.editor, f.root.findFocus())
        assertEquals(listOf<View>(f.editor), f.restarted)
    }

    @Test
    fun withNoEditorToFocusItGivesUpAfterOneRetry() {
        val f = Fixture(context)
        assertTrue(f.other.requestFocus())

        f.rebind { /* never lands on an editor (e.g. raw keyboard mode) */ }

        assertEquals("retries once, never loops", 1, f.clears)
        assertTrue("never restarts input on a non-editor", f.restarted.isEmpty())
    }
}
