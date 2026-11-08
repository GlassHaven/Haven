package sh.haven.core.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The stranded state is exactly what an interop teardown leaves when no re-add
 * or `update` follows: the view keeps its focus flag, every ancestor has
 * forgotten it, so the window has no focused view for the IME to serve.
 */
@RunWith(RobolectricTestRunner::class)
class FocusChainRepairTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class Fixture(context: Context) {
        val other = View(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val interop = View(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val holder = FrameLayout(context)
        val root = LinearLayout(context).apply {
            addView(other, ViewGroup.LayoutParams(SIZE, SIZE))
            addView(holder, ViewGroup.LayoutParams(SIZE, SIZE))
        }

        init {
            holder.addView(interop, ViewGroup.LayoutParams(SIZE, SIZE))
            Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(root)
            // A zero-sized view refuses focus once laid out (see InteropFocusGuardTest).
            listOf(root, other, holder, interop).forEach { it.layout(0, 0, SIZE, SIZE) }
        }

        /** What ImeInputView.onInteropReset / InteropFocusGuard.onReset leave behind. */
        fun strand() {
            assertTrue("precondition: interop view focused", interop.requestFocus())
            holder.clearChildFocus(interop)
            assertTrue("precondition: view keeps its own focus flag", interop.isFocused)
            assertNull("precondition: window has no served focus", root.findFocus())
        }

        private companion object {
            const val SIZE = 16
        }
    }

    @Test
    fun strandedFocusIsRelinkedSoTheWindowFindsIt() {
        val f = Fixture(context)
        f.strand()

        assertTrue(repairBrokenFocusChain(f.root.rootView))

        assertSame(f.interop, f.root.findFocus())
        assertSame(f.interop, f.holder.focusedChild)
        assertFalse("repair must not move focus elsewhere", f.other.isFocused)
    }

    @Test
    fun intactChainIsLeftAlone() {
        val f = Fixture(context)
        assertTrue(f.interop.requestFocus())

        assertFalse(repairBrokenFocusChain(f.root.rootView))
        assertSame(f.interop, f.root.findFocus())
    }

    @Test
    fun neverStealsFocusFromAGenuinelyFocusedView() {
        val f = Fixture(context)
        f.strand()
        // Another editor takes real focus while the stale flag lingers on the
        // stranded view: the repair must not pull focus back to it.
        assertTrue(f.other.requestFocus())

        assertFalse(repairBrokenFocusChain(f.root.rootView))
        assertSame(f.other, f.root.findFocus())
    }

    @Test
    fun nothingFocusedIsANoOp() {
        val f = Fixture(context)
        // The window hands initial focus to the first focusable view; take
        // focusability away so the window genuinely holds no focus at all.
        f.other.isFocusable = false
        f.interop.isFocusable = false
        assertNull("precondition: nothing focused", f.root.findFocus())

        assertFalse(repairBrokenFocusChain(f.root.rootView))
        assertNull(f.root.findFocus())
    }
}
