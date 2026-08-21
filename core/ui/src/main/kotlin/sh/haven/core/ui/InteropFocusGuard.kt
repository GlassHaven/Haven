package sh.haven.core.ui

import android.view.View
import android.view.ViewGroup

/**
 * Keeps an `AndroidView`'s interop child out of the focus chain for the moment
 * Compose tears the composition down.
 *
 * Compose 1.12.0's `AndroidViewHolder.onDeactivate()` is, in full,
 * `reset(); removeAllViewsInLayout()` — verified from the shipped bytecode. If
 * the interop child holds focus when that removal runs, `ViewGroup` takes its
 * `rootViewRequestFocus()` branch, and on HyperOS/Android 16 that focus request
 * re-enters the half-disposed `AndroidComposeView`:
 * `IllegalStateException: Searching for active node in inactive hierarchy`.
 * Because `reset` runs on the same stack frame as the removal, wiring [onReset]
 * in as the `onReset`/`onRelease` block closes that window with no dependence
 * on frame timing.
 *
 * The one primitive that works here is [ViewGroup.clearChildFocus]: it nulls
 * `mFocused` up the parent chain, and `ViewRootImpl` answers it by only
 * scheduling a traversal. `clearFocus()` is not usable — nor is dropping the
 * FOCUSABLE flag, which amounts to the same thing, because `View.setFlags`
 * gives focus up by calling the public `clearFocus()`, and that calls
 * `rootViewRequestFocus()` itself. A root-level focus request inside the
 * teardown is precisely the crash.
 *
 * The view keeps its own focus flag on purpose. `ViewGroup.addViewInner()`
 * re-establishes the chain for a re-added child that still has focus, so when
 * `AndroidViewHolder.onReuse()` puts the view back, focus returns by itself.
 *
 * Not thread-safe, and not meant to be: every call happens on the main thread,
 * from Compose's applier.
 *
 * Usage:
 * ```
 * val focusGuard = remember { InteropFocusGuard() }
 * AndroidView(
 *     factory = { ... },
 *     onReset = { view -> focusGuard.onReset(view) },
 *     onRelease = { view -> focusGuard.onReset(view) },
 *     update = { view -> ...; focusGuard.onUpdate(view) },
 * )
 * ```
 *
 * termlib's `ImeInputView` carries its own copy of this logic rather than
 * calling in here: it is a separate Gradle project and cannot depend on
 * `core:ui`.
 */
class InteropFocusGuard {

    /** True from [onReset] until the next [onUpdate]. */
    private var teardown = false

    /**
     * Leave the focus chain. Call from the `onReset` and `onRelease` blocks of
     * the hosting `AndroidView`.
     */
    fun onReset(view: View) {
        if (!view.hasFocus()) return
        teardown = true
        (view.parent as? ViewGroup)?.clearChildFocus(view)
    }

    /**
     * Put the chain back if the teardown did not actually detach the view.
     * Call at the tail of the hosting `AndroidView`'s `update` block.
     *
     * `AndroidViewHolder.onReuse()` calls the reset block *without* removing
     * the view when it finds it still parented, so no detach/attach pair
     * follows and `addViewInner()` never gets to restore what [onReset] broke.
     * Left alone the view would believe it holds focus while every ancestor
     * believes nothing does — a view that silently refuses input. The
     * composition is active again by the time `update` runs, so a targeted
     * `requestChildFocus` is safe: it repairs the chain rather than searching
     * for a focus target, and cannot reach the root.
     *
     * The `focusedChild` test is what separates the two cases. After a real
     * detach and re-add, `addViewInner()` has already restored the chain and
     * there is nothing to do.
     */
    fun onUpdate(view: View) {
        if (!teardown) return
        teardown = false
        val parent = view.parent as? ViewGroup ?: return
        if (view.hasFocus() && parent.focusedChild !== view) {
            parent.requestChildFocus(view, view.findFocus() ?: view)
        }
    }
}
