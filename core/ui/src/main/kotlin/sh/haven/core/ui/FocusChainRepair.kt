package sh.haven.core.ui

import android.util.Log
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
 * Give focusability back to text editors that are on screen but cannot take
 * focus. Returns how many it re-armed.
 *
 * termlib's ImeInputView drops FOCUSABLE while its window is hidden (the
 * Android 16 warm-return crash guard) and restores it on a fixed 100ms timer
 * that requests focus once and never retries. When that restore misses, the
 * editor stays unfocusable: a Compose focus request cannot land on it, so
 * [rebindImeTarget] never reaches restartInput, and a keyboard raised through
 * `WindowInsetsController.show(ime())` comes up with no editor behind it
 * ("Ignoring showSoftInput() as view … is not served"). It shows candidates
 * and accepts taps, and nothing reaches the terminal — every raise path the
 * user has (toolbar key, swipe-up, double-tap) hits the same dead state, and
 * only a background round trip, which re-runs the timer, gets out of it.
 * termlib's own showIme() re-arms the flag the same way before showing; this
 * does it for the host's raise paths, which can't reach that internal view.
 *
 * Only while the window is visible (the guard's whole point is the hidden
 * window) and only for enabled, shown editors, so it can't touch a field that
 * is unfocusable on purpose. A raw-keyboard terminal reports no text editor
 * and is left alone.
 */
fun rearmUnfocusableEditors(
    root: View,
    windowVisible: Boolean = root.windowVisibility == View.VISIBLE,
): Int {
    if (!windowVisible) return 0
    var rearmed = 0
    fun visit(view: View) {
        if (!view.isFocusable && view.isEnabled && view.isShown && view.onCheckIsTextEditor()) {
            view.isFocusableInTouchMode = true // also restores isFocusable
            rearmed++
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
    }
    visit(root)
    return rearmed
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
    windowVisible: Boolean = root.windowVisibility == View.VISIBLE,
) {
    val rearmed = rearmUnfocusableEditors(root, windowVisible)
    val relinked = repairBrokenFocusChain(root)
    requestFocus()
    root.post {
        if (root.findFocus()?.onCheckIsTextEditor() == true) {
            restartInput(root.findFocus()!!)
            logRebind(rearmed, relinked, retried = false, restarted = true)
            return@post
        }
        clearFocus()
        requestFocus()
        // A tab switch recreates the pane (key(sessionId)); the fresh
        // ImeInputView attaches a few frames later, so a single retry races
        // it and gives up with restarted=false even though focus lands on
        // the editor shortly after (#869). Poll across real frames (a plain
        // post() drains faster than the frame rate) until the editor holds
        // focus, then restart input on it.
        pollRestartInput(root, requestFocus, restartInput, rearmed, relinked, attemptsLeft = 30)
    }
}

private fun pollRestartInput(
    root: View,
    requestFocus: () -> Unit,
    restartInput: (View) -> Unit,
    rearmed: Int,
    relinked: Boolean,
    attemptsLeft: Int,
) {
    val editor = root.findFocus()?.takeIf { it.onCheckIsTextEditor() }
    if (editor != null) {
        restartInput(editor)
        logRebind(rearmed, relinked, retried = true, restarted = true)
        return
    }
    if (attemptsLeft <= 0) {
        logRebind(rearmed, relinked, retried = true, restarted = false)
        return
    }
    requestFocus()
    root.postDelayed({ pollRestartInput(root, requestFocus, restartInput, rearmed, relinked, attemptsLeft - 1) }, 16)
}

// Counts and booleans only — this line is meant for logs users attach to
// issues (#518). restarted=false means the keyboard went up with no editor
// behind it: the "keyboard composes, nothing reaches the terminal" state.
private fun logRebind(rearmed: Int, relinked: Boolean, retried: Boolean, restarted: Boolean) {
    Log.i(
        "TerminalIme",
        "rebind: rearmed=$rearmed relinked=$relinked retried=$retried restarted=$restarted",
    )
}
