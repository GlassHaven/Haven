package sh.haven.core.local

import sh.haven.core.data.terminal.ScrollbackRing
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent-scope mirror of PTY output, shared by the two PTY-backed session
 * managers ([LocalSessionManager] for LOCAL and
 * [sh.haven.core.local.uml.UmlGuestManager] for GUEST): a scrollback ring per
 * session that `read_terminal_scrollback` consumes, plus a permanent
 * agent-emulator tee that survives the UI's replaceable data-callback swap.
 *
 * Both transports build their [LocalSession]s with [mirror] as the data
 * callback, so EVERY session — UI-opened or headless — feeds the ring even
 * when no tab has ever been on top of it. Kept in one place so a UI feature
 * added on the LOCAL side lands on GUEST (and any future PTY transport) for
 * free instead of being rediscovered per transport.
 */
class AgentSessionMirror(private val scrollbackBytes: Int = 256 * 1024) {

    private val rings = ConcurrentHashMap<String, ScrollbackRing>()

    /**
     * Permanent agent-emulator tee, keyed like [rings] and invoked from the
     * SAME mirrors (create + reattach). It must NOT live inside the replaceable
     * data callback: reattachTerminalSession (#272) swaps that callback for the
     * new UI tab's pipeline, which silently disconnected the headless emulator
     * the MCP snapshot tools read (found testing #226).
     */
    private val tees = ConcurrentHashMap<String, (ByteArray, Int, Int) -> Unit>()

    /**
     * Per-session lock serializing `(ring.append + tee)` against [setTee] so
     * replaying buffered scrollback when a tee attaches neither misses nor
     * duplicates a concurrent PTY write (#555).
     */
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(sessionId: String): Any =
        locks.computeIfAbsent(sessionId) { Any() }

    /** The session's ring, created on first use (create + reattach both call this). */
    fun ring(sessionId: String): ScrollbackRing =
        rings.computeIfAbsent(sessionId) { ScrollbackRing(scrollbackBytes) }

    /**
     * The create/reattach data callback: ring + tee + the caller's sink, in
     * that order. Installed at [LocalSessionManager.createTerminalSession] so
     * the ring is fed before any UI attaches (matches SshSessionManager's
     * permanent onMirror tee) and reinstalled by reattach on the new pipeline.
     */
    fun mirror(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): (ByteArray, Int, Int) -> Unit = { data, off, len ->
        synchronized(lockFor(sessionId)) {
            ring(sessionId).append(data, off, len)
            tees[sessionId]?.invoke(data, off, len)
        }
        onDataReceived(data, off, len)
    }

    /**
     * Register the agent-emulator tee for [sessionId], surviving later
     * callback swaps. Registered even when a UI tab already owns the session —
     * when [replayExisting] is true (the default), any bytes already buffered
     * in [sessionId]'s scrollback ring are replayed into [sink] atomically
     * before live chunks start streaming, so an agent attaching to a UI-opened
     * or post-teardown session does not start from a blank grid (#555).
     * Never cleared for the session's life: a UI tab adopting the session still
     * leaves the agent shell consuming PTY output behind it, which is what
     * keeps the emulator current when the tab's ViewModel teardown hands the
     * registry entry back to the shell (#555).
     */
    fun setTee(
        sessionId: String,
        sink: (ByteArray, Int, Int) -> Unit,
        replayExisting: Boolean = true,
    ) {
        synchronized(lockFor(sessionId)) {
            if (tees[sessionId] === sink) return
            if (replayExisting) {
                val buffered = rings[sessionId]?.snapshot()
                if (buffered != null && buffered.isNotEmpty()) {
                    sink(buffered, 0, buffered.size)
                }
            }
            tees[sessionId] = sink
        }
    }

    /**
     * The bytes buffered in [sessionId]'s scrollback ring (raw PTY output), or
     * null if empty — replayed into a fresh emulator on reattach (#272).
     */
    fun snapshot(sessionId: String): ByteArray? =
        rings[sessionId]?.snapshot()?.takeIf { it.isNotEmpty() }

    /**
     * The last [bytes] of ring output as text, or null when empty/undecodable —
     * the diagnosis channel an exiting session's connection-log entry carries
     * (a tmux error, a kernel panic, a "command not found").
     */
    fun tail(sessionId: String, bytes: Int = 2048): String? = runCatching {
        val snap = rings[sessionId]?.snapshot() ?: return null
        val from = maxOf(0, snap.size - bytes)
        String(snap, from, snap.size - from, Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** True once any output has reached the session's ring. */
    fun hasOutput(sessionId: String): Boolean =
        (rings[sessionId]?.totalBytesAppended ?: 0L) > 0L

    /**
     * Read the most recent [maxBytes] of ring output for [sessionId], or null
     * if no ring exists yet (no headless or UI tab has run on this session).
     */
    fun read(sessionId: String, maxBytes: Int): ByteArray? {
        val ring = rings[sessionId] ?: return null
        val full = ring.snapshot()
        return if (full.size <= maxBytes) full
        else full.copyOfRange(full.size - maxBytes, full.size)
    }

    /** Free the ring and tee for a session leaving the map for good. */
    fun remove(sessionId: String) {
        val lock = locks.remove(sessionId)
        if (lock != null) {
            synchronized(lock) {
                rings.remove(sessionId)
                tees.remove(sessionId)
            }
        } else {
            rings.remove(sessionId)
            tees.remove(sessionId)
        }
    }
}
