package sh.haven.feature.editor

import android.content.Context
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * [CodeEditor] whose event callbacks can be re-pointed after construction.
 *
 * The hosting `AndroidView` supplies an `onReset` block (the focus guard for
 * Compose's interop teardown), which makes it a *reusable* Compose node: a
 * reused node keeps this View and does not re-run the factory. Subscriptions
 * made in the factory would then still be calling the previous composition's
 * lambdas, quietly writing cursor position and dirty state into a composition
 * that no longer exists. Subscribing once here and refreshing the two
 * properties from the `update` block keeps them pointed at the live ones —
 * on an ordinary recomposition as well as after a reuse.
 */
internal class HavenCodeEditor(context: Context) : CodeEditor(context) {

    /** Reported 1-based, as the editor UI shows them. */
    var onCursorChange: (line: Int, column: Int) -> Unit = { _, _ -> }

    /** Not called for the programmatic whole-document set that loads a file. */
    var onContentChanged: () -> Unit = {}

    init {
        subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            val cursor = event.editor.cursor
            onCursorChange(cursor.leftLine + 1, cursor.leftColumn + 1)
        }

        subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                onContentChanged()
            }
        }
    }
}
