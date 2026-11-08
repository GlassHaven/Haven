package sh.haven.core.ui

import android.view.View
import android.view.ViewGroup

/**
 * Re-link a view that still holds focus but has dropped out of its ancestors'
 * focus record, so the IME has an editor to serve. Returns true if it repaired.
 *
 * [InteropFocusGuard.onReset] (and termlib's `ImeInputView.onInteropReset`)
 * break the chain on purpose during a Compose interop teardown and keep the
 * view's own focus flag. When neither a re-add nor an `update` follows, the
 * view believes it is focused, so a Compose `focusRequester.requestFocus()` is
 * a no-op, while the window's `findFocus()` is null — and a keyboard raised
 * through `WindowInsetsController.show(ime())` then accepts no input at all
 * ("Ignoring showSoftInput() as view … is not served"). termlib repairs this
 * only on its own tap / `showIme()` paths, which a tmux mouse-mode tap never
 * reaches; a background round trip fixed it via window-visibility, which is
 * why the terminal "came back" after switching apps.
 *
 * Call right before raising the IME from the host. Only acts when nothing in
 * the window is genuinely focused, so it can never steal focus from a real
 * editor; the repair is a targeted [ViewGroup.requestChildFocus], not a focus
 * search, so it cannot re-enter a disposing hierarchy.
 */
fun repairBrokenFocusChain(root: View): Boolean {
    if (root.findFocus() != null) return false
    val stranded = findStrandedFocus(root) ?: return false
    val parent = stranded.parent as? ViewGroup ?: return false
    parent.requestChildFocus(stranded, stranded)
    return true
}

private fun findStrandedFocus(view: View): View? {
    if (view.isFocused) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            findStrandedFocus(view.getChildAt(i))?.let { return it }
        }
    }
    return null
}
