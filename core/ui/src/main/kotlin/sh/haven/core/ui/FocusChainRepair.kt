package sh.haven.core.ui

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager

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

/**
 * Point the IME at the terminal's editor over a *fresh* InputConnection —
 * what a background round trip does, minus the round trip.
 *
 * Reported: after Attach → Send a file (which visits the Files tab and comes
 * back) the soft keyboard is up and composes, yet nothing reaches the
 * terminal until the app is sent to the background and brought back. On the
 * way back the window regains focus and the IMM starts input afresh on the
 * focused view, which is the only thing that round trip changes. A Compose
 * `focusRequester.requestFocus()` alone cannot reproduce it: it is a no-op
 * when Compose already believes the node is focused, and it never replaces a
 * stale InputConnection.
 *
 * Steps: re-link a stranded focus ([repairBrokenFocusChain]), request focus;
 * on the next frame, if the window's focused view still isn't a text editor,
 * clear and re-request once (forcing the interop focus enter); then restart
 * input on the editor. Call where the host raises the keyboard or the terminal
 * becomes active — never mid-composition, since restarting drops a preedit.
 */
fun rebindImeTarget(
    root: View,
    requestFocus: () -> Unit,
    clearFocus: () -> Unit,
    restartInput: (View) -> Unit = { editor ->
        editor.context.getSystemService(InputMethodManager::class.java)?.restartInput(editor)
    },
) {
    repairBrokenFocusChain(root)
    requestFocus()
    root.post {
        if (root.findFocus()?.onCheckIsTextEditor() == true) {
            restartInput(root.findFocus()!!)
            return@post
        }
        clearFocus()
        requestFocus()
        root.post {
            root.findFocus()?.takeIf { it.onCheckIsTextEditor() }?.let(restartInput)
        }
    }
}
