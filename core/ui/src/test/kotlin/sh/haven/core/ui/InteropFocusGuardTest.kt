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
 * The observable signature of the crashing branch is that removing a FOCUSED
 * child sends ViewGroup through `rootViewRequestFocus()` — a focus request from
 * the root of the tree, which on the device re-enters the half-disposed
 * AndroidComposeView. There is no Compose hierarchy here to re-enter, so a
 * plain focusable sibling acts as the canary: it can only gain focus if that
 * root-level request ran.
 */
@RunWith(RobolectricTestRunner::class)
class InteropFocusGuardTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class Fixture(context: Context) {
        val canary = View(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val interop = View(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val holder = FrameLayout(context)
        val root = LinearLayout(context).apply {
            addView(canary, ViewGroup.LayoutParams(SIZE, SIZE))
            addView(holder, ViewGroup.LayoutParams(SIZE, SIZE))
        }

        init {
            holder.addView(interop, ViewGroup.LayoutParams(SIZE, SIZE))
            Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(root)
            // View.canTakeFocus() refuses a zero-sized view once layout is
            // valid (sCanFocusZeroSized is false from targetSdk P). Robolectric
            // lays the content view out, so without a real size every
            // requestFocus() below silently returns false.
            listOf(root, canary, holder, interop).forEach { it.layout(0, 0, SIZE, SIZE) }
        }

        private companion object {
            const val SIZE = 16
        }
    }

    private fun fixture() = Fixture(context)

    @Test
    fun removingAFocusedInteropChildRefocusesFromRoot() {
        // Pins the branch the guard exists to avoid. If this ever stops
        // holding, the framework changed and the guard's rationale needs
        // re-checking — it does not mean the guard is unnecessary.
        val f = fixture()
        assertTrue("precondition: interop view focused", f.interop.requestFocus())

        f.holder.removeAllViewsInLayout()

        assertTrue(
            "removal of a focused child must re-request focus from the root",
            f.canary.isFocused,
        )
    }

    @Test
    fun droppingFocusableAlsoRefocusesFromRoot() {
        // The reason the guard cannot use the FOCUSABLE flag: View.setFlags
        // gives focus up through the public clearFocus(), which is
        // clearFocusInternal(refocus = true).
        val f = fixture()
        f.interop.requestFocus()

        f.interop.isFocusable = false

        assertTrue("setFocusable(false) runs a root focus request", f.canary.isFocused)
    }

    @Test
    fun onResetPreventsRootRefocusOnRemoval() {
        val f = fixture()
        assertTrue("precondition: interop view focused", f.interop.requestFocus())

        // Exactly what Compose does: reset block, then the removal.
        InteropFocusGuard().onReset(f.interop)

        // The ancestors' record of the focused child is what
        // removeAllViewsInLayout() reads; the view's own focus is kept on
        // purpose, so the chain can be restored when it is re-added.
        assertNull("holder has no focused child", f.holder.focusedChild)
        assertTrue("view keeps its own focus", f.interop.isFocused)
        assertTrue("FOCUSABLE untouched", f.interop.isFocusable)

        f.holder.removeAllViewsInLayout()

        assertFalse(
            "no root-level focus request may run during teardown",
            f.canary.isFocused,
        )
    }

    @Test
    fun teardownRoundTripRestoresTheFocusChain() {
        // The whole AndroidViewHolder.onDeactivate() / onReuse() cycle: reset,
        // removeAllViewsInLayout(), then addView() when the page comes back.
        val f = fixture()
        val guard = InteropFocusGuard()
        f.interop.requestFocus()

        guard.onReset(f.interop)
        f.holder.removeAllViewsInLayout()
        assertFalse("no root-level focus request during teardown", f.canary.isFocused)

        f.holder.addView(f.interop, ViewGroup.LayoutParams(16, 16))
        f.interop.layout(0, 0, 16, 16)
        guard.onUpdate(f.interop)

        assertSame("focus chain restored on re-add", f.interop, f.holder.focusedChild)
        assertTrue("view focused again", f.interop.isFocused)
    }

    @Test
    fun onUpdateRepairsTheChainAfterAResetWithoutDetach() {
        // onReuse() calls the reset block WITHOUT removing the view when it is
        // still parented, so no detach/attach pair follows and nothing else
        // would ever reconnect the chain.
        val f = fixture()
        val guard = InteropFocusGuard()
        f.interop.requestFocus()
        guard.onReset(f.interop)
        assertNull("precondition: chain broken", f.holder.focusedChild)

        guard.onUpdate(f.interop)

        assertSame("chain repaired", f.interop, f.holder.focusedChild)
        assertFalse("repair must not hand focus to a sibling", f.canary.isFocused)
    }

    @Test
    fun onUpdateIsANoOpOutsideATeardown() {
        val f = fixture()
        val guard = InteropFocusGuard()
        f.canary.requestFocus()

        guard.onUpdate(f.interop)

        assertTrue("must not steal focus on an ordinary update", f.canary.isFocused)
        assertFalse(f.interop.isFocused)
    }

    @Test
    fun onResetIsANoOpWhenTheViewIsNotFocused() {
        val f = fixture()
        val guard = InteropFocusGuard()
        f.canary.requestFocus()

        guard.onReset(f.interop)
        guard.onUpdate(f.interop)

        assertTrue("unrelated focus left alone", f.canary.isFocused)
        assertFalse(f.interop.isFocused)
    }
}
