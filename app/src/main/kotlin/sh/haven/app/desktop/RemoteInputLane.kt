package sh.haven.app.desktop

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val TAG = "RemoteInputLane"

/**
 * Delivers remote-desktop input events off the main thread, one at a time,
 * in the order they were submitted.
 *
 * Input used to go out as one `launch(Dispatchers.IO)` per event. That pool
 * is multi-threaded, so a key release could reach the server before its own
 * press. The server then holds the key down: it auto-repeats forever, the
 * same key on the server's physical keyboard stops working (the virtual
 * device still holds it), and only disconnecting releases it (#504). Mouse
 * button pairs and move-then-press sequences break the same way.
 *
 * Callers resolve the target session when they submit, not inside the
 * event. Resolving it when the event runs could send a release to a
 * different tab than its press if the user switched tabs in between.
 *
 * A failing event is logged and skipped so it cannot stop the lane for
 * the events after it.
 */
class RemoteInputLane(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val events = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) {
            for (event in events) {
                try {
                    event()
                } catch (e: Exception) {
                    Log.w(TAG, "Remote input event failed", e)
                }
            }
        }
    }

    fun submit(event: () -> Unit) {
        events.trySend(event)
    }
}
