package sh.haven.core.mosh.transport

import sh.haven.core.mosh.proto.UserInstruction
import sh.haven.core.mosh.proto.UserMessage

/**
 * Client-side state for the State Synchronization Protocol.
 *
 * Accumulates user input (keystrokes and resize events) and computes
 * diffs between state versions. Each action increments the state number.
 */
class UserStream {
    private val actions = mutableListOf<UserInstruction>()

    val size: Long
        get() = synchronized(this) { actions.size.toLong() }

    fun pushKeystroke(bytes: ByteArray) {
        synchronized(this) {
            // Each byte is a separate UserEvent, matching mosh's C++ client behavior
            for (b in bytes) {
                actions.add(UserInstruction.Keystroke(byteArrayOf(b)))
            }
        }
    }

    fun pushResize(width: Int, height: Int) {
        synchronized(this) {
            actions.add(UserInstruction.Resize(width, height))
        }
    }

    /**
     * Compute serialized UserMessage diff from state [oldNum] to current state.
     */
    fun diffFrom(oldNum: Long): ByteArray {
        synchronized(this) {
            val fromIdx = oldNum.toInt().coerceIn(0, actions.size)
            val newActions = if (fromIdx < actions.size) {
                actions.subList(fromIdx, actions.size).toList()
            } else {
                emptyList()
            }
            return UserMessage(newActions).encode()
        }
    }
}
