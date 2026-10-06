package sh.haven.core.ssh

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "TerminalSession"

/**
 * Bridges a [ShellChannel] to a terminal emulator.
 *
 * Reads SSH output on a background thread and delivers it via [onDataReceived].
 * Call [sendToSsh] to forward keyboard input to the remote shell.
 */
class TerminalSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    @Volatile private var shell: ShellChannel,
    @Volatile private var client: SshConnection,
    @Volatile private var onDataReceived: (ByteArray, Int, Int) -> Unit,
    /**
     * Permanent, non-swappable mirror of every output chunk (the agent
     * scrollback ring). Unlike [onDataReceived] it is NOT replaced by
     * [replaceDataCallback], so it survives terminal reattach — required now
     * that SSH sessions are created at connect time and the emulator attaches
     * afterwards. No-op by default.
     */
    private val onMirror: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
    pendingCommands: List<String> = emptyList(),
    /**
     * Optional reconnect-lifecycle breadcrumb sink. Emits short human-readable
     * notes (channel swapped, pending/reattach command sent, reader ended) that
     * the session manager routes to the connection log — so a reconnect that
     * leaves the pane blank can be diagnosed on a release build, where app
     * logcat is stripped. No-op when null.
     */
    private val onBreadcrumb: ((String) -> Unit)? = null,
) : Closeable {

    /** Queue of commands to send one-by-one as shell prompts are detected. */
    private val _pendingCommands: MutableList<String> = pendingCommands.toMutableList()

    /** Replace the pending command queue (used on reconnect). */
    fun setPendingCommands(commands: List<String>) {
        synchronized(_pendingCommands) {
            _pendingCommands.clear()
            _pendingCommands.addAll(commands)
        }
    }

    // Streams bound before the channel was connected; re-fetching them from
    // the channel here would install a fresh pipe and drop the shell's first
    // output (#382). See [ShellChannel].
    @Volatile private var sshInput: InputStream = shell.input
    @Volatile private var sshOutput: OutputStream = shell.output

    /** Single-thread executor for serialising writes and scheduling debounced resizes. */
    private val writeExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ssh-writer-$sessionId").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    /** Pending debounced resize — cancelled on each new resize call. */
    @Volatile
    private var pendingResize: ScheduledFuture<*>? = null

    /** Pending "shell went quiet" fallback for the command queue — see [armPendingIdleFallback]. */
    @Volatile
    private var pendingIdleCheck: ScheduledFuture<*>? = null

    /**
     * Last PTY size we asked the remote for. Tracked so [reconnect] can
     * replay it on the freshly-opened channel — without this, the reader
     * comes up with an 80×24 default while the local canvas is at its
     * actual size, and the divergence shows up as wrap-mismatch garbage
     * in scrollback after a WiFi jump (#120).
     * 0 means "no resize seen yet"; replay only when we have a real size.
     */
    @Volatile private var lastCols: Int = 0
    @Volatile private var lastRows: Int = 0

    private var readerThread: Thread? = null

    /**
     * Monotonic token identifying the current reader. [reconnect] bumps it
     * before swapping the channel, so a *previous* reader — still blocked in
     * `read()` on a silently-dead socket when a probe-driven reconnect runs —
     * exits without reading the new stream or firing [onDisconnected] for an
     * already-reconnected session. (#208 finding 2)
     */
    @Volatile private var readerGeneration = 0

    /**
     * Set by [reconnect] so the next time the pending-command queue drains
     * (i.e. the tmux/zellij/screen reattach command has been sent and any
     * post-login command after it), we force the freshly-attached session to
     * repaint. tmux only does a full redraw when a client attaches or its size
     * changes; on reconnect the sole resize replay lands on the bare login
     * shell *before* the reattach fires, so the multiplexer never receives a
     * post-attach SIGWINCH. If the attach-time redraw is then lost (e.g. a
     * second reconnect swaps the channel ~seconds later, orphaning the bytes
     * in flight) the pane stays blank until the user manually resizes or opens
     * a fresh tab. A post-reattach window-change closes that gap.
     */
    @Volatile private var redrawAfterPendingDrain = false

    /**
     * Start the reader thread that delivers SSH output to [onDataReceived].
     * Call this after all wiring (e.g., emulator setup) is complete.
     */
    /**
     * Replace the data callback. The reader thread continues running and uses
     * the new callback immediately. No connect-window replay: the emulator is
     * now wired as the data callback at connect time (via
     * [TerminalAttachmentProvider]), so output is parsed —
     * and terminal probes answered — live from the first byte. [onDataReceived]
     * is `@Volatile`, so the swap is visible to the reader without locking.
     */
    fun replaceDataCallback(callback: (ByteArray, Int, Int) -> Unit) {
        onDataReceived = callback
    }

    fun start() {
        val generation = ++readerGeneration
        readerThread = thread(
            name = "ssh-reader-$sessionId",
            isDaemon = true,
        ) {
            readLoop(generation)
        }
    }

    private fun readLoop(generation: Int) {
        val buffer = ByteArray(8192)
        var pendingSent = false
        var gotEof = false
        var gotException = false
        try {
            while (!closed && readerGeneration == generation && shell.isConnected) {
                val bytesRead = sshInput.read(buffer)
                if (bytesRead == -1) {
                    gotEof = true
                    break
                }
                if (bytesRead > 0) {
                    onMirror(buffer, 0, bytesRead)
                    onDataReceived(buffer, 0, bytesRead)

                    // After delivering output, check if we have pending commands
                    // to send once a shell prompt appears.
                    // Strip ANSI/OSC escape sequences before checking for prompt chars,
                    // since shell integration (OSC 133) wraps the prompt in escape codes
                    // that would mask the trailing $ / # / % / > character.
                    // Check each line individually: tmux status bars or other
                    // output after the prompt would mask the prompt char if we
                    // only checked the last character of the entire chunk.
                    val hasCmd = synchronized(_pendingCommands) { _pendingCommands.isNotEmpty() }
                    if (hasCmd) {
                        val raw = String(buffer, 0, bytesRead)
                        val stripped = raw.replace(Regex("\u001b(?:\\[[^a-zA-Z]*[a-zA-Z]|][^\u0007]*\u0007|[()][A-Za-z])"), "")
                        val promptChar = stripped.split('\n')
                            .map { it.trimEnd() }
                            .filter { it.isNotEmpty() }
                            .mapNotNull { line -> line.last().takeIf { it in promptTerminators } }
                            .firstOrNull()
                        if (promptChar != null) {
                            Log.d(TAG, "Shell prompt detected ('$promptChar'), sending pending command")
                            sendNextPendingCommand("prompt '$promptChar'")
                        } else {
                            // No recognised prompt in this chunk. The character
                            // heuristic can't cover every prompt (#482), so arm a
                            // fallback that fires once the shell goes quiet —
                            // otherwise the attach command sits queued until some
                            // unrelated later output happens to end in $ # % > ❯,
                            // which is what "the command appears far too late"
                            // looks like from the user's side.
                            armPendingIdleFallback()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            gotException = true
        }
        // Skip the disconnect/reconnect signal if a newer reader has taken over
        // (this is a stale reader that just unblocked after a reconnect). (#208 #2)
        if (!closed && readerGeneration == generation) {
            // Wait briefly for channel to fully close and exit status to propagate
            for (i in 1..10) {
                if (shell.isClosed) break
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
            val exitStatus = shell.exitStatus
            // Clean exit: remote sent a real exit status (>= 0), e.g. shell
            // exited normally.  A network drop produces EOF with exitStatus -1
            // (no status received) — that must trigger reconnection.
            val cleanExit = exitStatus >= 0 && !gotException
            Log.d(TAG, "readLoop ended for $sessionId — eof=$gotEof exception=$gotException exitStatus=$exitStatus cleanExit=$cleanExit")
            onBreadcrumb?.invoke(
                "shell reader ended eof=$gotEof exc=$gotException exit=$exitStatus clean=$cleanExit",
            )
            onDisconnected?.invoke(cleanExit)
        }
    }

    /**
     * Forward keyboard input to the remote shell.
     * Safe to call from any thread — writes are dispatched to a background thread
     * to avoid NetworkOnMainThreadException.
     */
    fun sendToSsh(data: ByteArray) {
        if (closed || !shell.isConnected) {
            Log.d(TAG, "sendToSsh: dropping ${data.size} bytes (closed=$closed connected=${shell.isConnected})")
            return
        }

        val copy = data.copyOf()
        try {
            writeExecutor.execute {
                if (closed || !shell.isConnected) return@execute
                try {
                    sshOutput.write(copy)
                    sshOutput.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "sendToSsh: write failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down — drop the write
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (closed || writeExecutor.isShutdown) return
        // Remember the latest size so reconnect() can replay it (#120).
        lastCols = cols
        lastRows = rows
        // Debounce: cancel any pending resize and schedule a new one.
        // During keyboard/tab animations the terminal view resizes every frame;
        // only the final size matters for the remote PTY.
        pendingResize?.cancel(false)
        try {
            pendingResize = writeExecutor.schedule({
                try {
                    Log.d(TAG, "setPtySize: ${cols}x${rows}")
                    shell.resize(cols, rows)
                } catch (e: Exception) {
                    Log.e(TAG, "resize failed", e)
                }
            }, 150, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down between check and execute — ignore
        }
    }

    /**
     * Swap the underlying SSH channel after a reconnect.
     * The old reader thread has already exited (which triggered the reconnect).
     * Starts a new reader on the new channel.
     */
    fun reconnect(newShell: ShellChannel, newClient: SshConnection) {
        // Invalidate any still-running previous reader *before* swapping the
        // channel, so when it unblocks it sees a newer generation and exits
        // instead of reading the new stream or re-triggering reconnect. (#208 #2)
        val generation = ++readerGeneration
        val oldReader = readerThread
        shell = newShell
        client = newClient
        sshInput = newShell.input
        sshOutput = newShell.output
        oldReader?.interrupt()
        // Arm the post-reattach repaint: fires once the queued session-manager
        // reattach command drains (see [redrawAfterPendingDrain]). No-op for a
        // plain-shell reconnect, which carries no pending commands.
        redrawAfterPendingDrain = true
        Log.d(TAG, "reconnect: swapped channel for $sessionId, starting new reader")
        // The new channel was opened with the default 80×24 PTY size
        // (SshClient.openShellChannel defaults). If the user had resized
        // since session start, replay the last known size on the new
        // channel so the server's terminal matches the local canvas.
        // Without this, post-WiFi-jump output wraps at 80 columns while
        // the client renders at the real width — visible as garbage in
        // scrollback (#120).
        val replayCols = lastCols
        val replayRows = lastRows
        if (replayCols > 0 && replayRows > 0 && !writeExecutor.isShutdown) {
            try {
                writeExecutor.submit {
                    try {
                        Log.d(TAG, "reconnect: replaying setPtySize ${replayCols}x${replayRows}")
                        newShell.resize(replayCols, replayRows)
                    } catch (e: Exception) {
                        Log.w(TAG, "reconnect: setPtySize replay failed", e)
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Executor shutdown raced — ignore
            }
        }
        readerThread = thread(
            name = "ssh-reader-$sessionId",
            isDaemon = true,
        ) {
            readLoop(generation)
        }
        onBreadcrumb?.invoke("reconnect: channel swapped, reader restarted (replay ${lastCols}x${lastRows})")
    }

    /**
     * Force the freshly-reattached multiplexer to repaint by delivering a
     * window-change after the reattach command has been sent. A same-size
     * resize is not reliably honoured by tmux, so we wobble one row and back —
     * two genuine size changes guarantee a SIGWINCH-driven full redraw, then
     * restore the real geometry. Scheduled (not immediate) to give the attach
     * a moment to complete first. Skipped until a real size is known.
     */
    private fun schedulePostReattachRedraw() {
        val cols = lastCols
        val rows = lastRows
        if (cols <= 0 || rows <= 0 || closed || writeExecutor.isShutdown) return
        val wobbleRows = if (rows > 1) rows - 1 else rows + 1
        try {
            writeExecutor.schedule({
                if (closed || !shell.isConnected) return@schedule
                try {
                    shell.resize(cols, wobbleRows)
                } catch (e: Exception) {
                    Log.w(TAG, "post-reattach redraw nudge failed", e)
                }
            }, POST_REATTACH_REDRAW_DELAY_MS, TimeUnit.MILLISECONDS)
            writeExecutor.schedule({
                if (closed || !shell.isConnected) return@schedule
                try {
                    shell.resize(cols, rows)
                } catch (e: Exception) {
                    Log.w(TAG, "post-reattach redraw restore failed", e)
                }
            }, POST_REATTACH_REDRAW_DELAY_MS + POST_REATTACH_REDRAW_WOBBLE_MS, TimeUnit.MILLISECONDS)
            onBreadcrumb?.invoke(
                "reconnect: forced repaint (resize ${cols}x$wobbleRows→${cols}x$rows) after reattach",
            )
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down between check and schedule — ignore
        }
    }

    /**
     * Detach from the channel without disconnecting it.
     * Stops the reader thread and write executor but leaves the shell channel
     * alive so a new TerminalSession can be attached to it.
     * Used when TerminalViewModel is cleared but the SSH connection persists
     * (e.g., Activity destroyed while foreground service keeps the process alive).
     */
    fun detach() {
        if (closed) return
        closed = true
        writeExecutor.shutdown()
        readerThread?.interrupt()
    }

    /**
     * Send the next queued command, if any. Shared by the prompt-character path
     * and the idle fallback so both drain the same queue under the same lock —
     * whichever fires first wins and the other finds it empty.
     */
    private fun sendNextPendingCommand(trigger: String) {
        if (closed) return
        val cmd = synchronized(_pendingCommands) { _pendingCommands.removeFirstOrNull() } ?: return
        // Submit with CR — the byte a terminal's Enter key sends into a pty.
        // Windows cmd.exe over OpenSSH ignores a bare LF, so a queued post-login
        // command sat unsubmitted until the user pressed Enter themselves (#680);
        // POSIX line discipline (ICRNL) folds CR back to NL, so POSIX shells
        // accept it exactly as before.
        sendToSsh((cmd + "\r").toByteArray())
        onBreadcrumb?.invoke("pending/reattach sent on $trigger: ${cmd.take(48)}")
        // Last pending command sent on a reconnect: the session manager is
        // reattached now, so force a repaint (see [redrawAfterPendingDrain]).
        val drained = synchronized(_pendingCommands) { _pendingCommands.isEmpty() }
        if (drained) {
            if (redrawAfterPendingDrain) {
                redrawAfterPendingDrain = false
                schedulePostReattachRedraw()
            }
        } else {
            // More queued. Re-arm so a shell that prints nothing we recognise
            // still drains the rest instead of stalling half-way.
            armPendingIdleFallback()
        }
    }

    /**
     * Arm the "shell went quiet" fallback for the pending queue, replacing any
     * previously armed one — so the timer measures silence since the LAST byte,
     * not since the first.
     *
     * The prompt-character heuristic ([promptTerminators]) cannot enumerate
     * every prompt anyone uses; a prompt ending in a powerline glyph, a bracket
     * or a non-Latin character is simply never recognised, and before this the
     * queued attach command waited indefinitely (#482). Waiting for the output
     * to stop is independent of what the prompt looks like: a shell that has
     * printed something and then gone silent is a shell waiting for input.
     */
    private fun armPendingIdleFallback() {
        if (closed) return
        if (synchronized(_pendingCommands) { _pendingCommands.isEmpty() }) return
        pendingIdleCheck?.cancel(false)
        pendingIdleCheck = try {
            writeExecutor.schedule(
                { sendNextPendingCommand("idle ${PENDING_IDLE_FALLBACK_MS}ms") },
                PENDING_IDLE_FALLBACK_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            null // executor already shut down (session closing) — nothing to do
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingIdleCheck?.cancel(false)
        writeExecutor.shutdown()
        try { shell.disconnect() } catch (_: Exception) {}
        readerThread?.interrupt()
    }

    companion object {
        /** Delay after the reattach command before the repaint nudge, to let the attach complete. */
        private const val POST_REATTACH_REDRAW_DELAY_MS = 450L

        /** Gap between the wobble resize and the restore resize. */
        private const val POST_REATTACH_REDRAW_WOBBLE_MS = 150L

        /** Built-in trailing prompt characters: `$ # % >` and `❯`. */
        val DEFAULT_PROMPT_TERMINATORS: Set<Char> = setOf('$', '#', '%', '>', '❯')

        /**
         * How long the shell must be silent before a queued command is sent
         * without having recognised a prompt (#482).
         *
         * Long enough not to fire into a prompt still being painted in pieces
         * (colours, git status, a slow right-hand prompt), short enough that a
         * user who picked a session from the list is not left sitting in a bare
         * shell wondering what happened. The prompt-character path still wins
         * whenever it matches, so this only governs prompts the heuristic
         * cannot see.
         */
        private const val PENDING_IDLE_FALLBACK_MS = 1200L

        /**
         * Trailing characters that mark a shell prompt, used to decide when a
         * queued command may be sent on (re)attach. Defaults to
         * [DEFAULT_PROMPT_TERMINATORS]; the app layer extends it from the
         * user's "custom prompt characters" preference so prompts ending in
         * e.g. `»` or `尺` are recognised (#280). Process-wide (the heuristic
         * is identical for every session); read on each output chunk.
         */
        @Volatile
        var promptTerminators: Set<Char> = DEFAULT_PROMPT_TERMINATORS
    }
}
