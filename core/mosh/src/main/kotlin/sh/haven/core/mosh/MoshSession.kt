package sh.haven.core.mosh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import sh.haven.mosh.MoshLogger
import sh.haven.mosh.transport.MoshTransport
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "MoshSession"

/**
 * Bridges a mosh transport session to the terminal emulator.
 *
 * Parallel to ReticulumSession: manages a transport instance and
 * shuttles terminal data between the mosh server and termlib.
 * No PTY or native code — the pure Kotlin MoshTransport handles
 * UDP, encryption, and protocol framing in-process.
 */
class MoshSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val serverIp: String,
    private val moshPort: Int,
    private val moshKey: String,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
    private val initialCols: Int = 80,
    private val initialRows: Int = 24,
    private val verboseBuffer: ConcurrentLinkedQueue<String>? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private val startTime = System.currentTimeMillis()
    private val logger = object : MoshLogger {
        override fun d(tag: String, msg: String) {
            Log.d(tag, msg)
            verboseBuffer?.add("+${System.currentTimeMillis() - startTime}ms [$tag] $msg")
        }
        override fun e(tag: String, msg: String, throwable: Throwable?) {
            if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
            verboseBuffer?.add("+${System.currentTimeMillis() - startTime}ms [$tag] ERROR: $msg${throwable?.let { " (${it.message})" } ?: ""}")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: MoshTransport? = null

    /**
     * Start the mosh transport: opens UDP socket, begins send/receive loops.
     *
     * Before wiring up the transport we also push one synthetic byte
     * sequence — [DECCKM_ON] — into the client-side emulator. See the
     * companion comment on that constant for why. This is the fix for
     * GlassOnTin/Haven#73.
     */
    fun start() {
        if (closed) return
        Log.d(TAG, "Starting mosh transport for $sessionId: $serverIp:$moshPort")

        // Put the client-side terminal into application cursor key mode
        // (DECCKM = on) BEFORE any server diff bytes arrive. Without this,
        // libvterm stays in normal cursor key mode for the entire mosh
        // session and Up/Down/Left/Right arrow keys come out as CSI
        // sequences (ESC [ A) instead of SS3 sequences (ESC O A), which
        // breaks Mutt, Emacs, less, and anything else that calls `tput
        // smkx`. See the DECCKM_ON companion for the full causal chain.
        if (!closed) {
            onDataReceived(DECCKM_ON, 0, DECCKM_ON.size)
        }

        val t = MoshTransport(
            serverIp = serverIp,
            port = moshPort,
            key = moshKey,
            onOutput = { data, offset, len ->
                if (!closed) {
                    onDataReceived(data, offset, len)
                }
            },
            onDisconnect = { cleanExit ->
                if (!closed) {
                    Log.d(TAG, "Transport disconnected for $sessionId (clean=$cleanExit)")
                    onDisconnected?.invoke(cleanExit)
                }
            },
            logger = logger,
            initialCols = initialCols,
            initialRows = initialRows,
        )
        transport = t
        t.start(scope)
    }

    /**
     * Send keyboard input to the mosh server.
     * Safe to call from any thread.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        transport?.sendInput(data)
    }

    /**
     * Notify the mosh server of a terminal resize.
     */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        transport?.resize(cols, rows)
    }

    /**
     * Detach without closing the transport.
     * The mosh server keeps the session alive; we can reattach later.
     */
    fun detach() {
        if (closed) return
        closed = true
        transport?.close()
        transport = null
    }

    /** Drain captured transport logs. Returns null if verbose logging was not enabled. */
    fun drainTransportLog(): String? {
        val buf = verboseBuffer ?: return null
        if (buf.isEmpty()) return null
        val sb = StringBuilder()
        while (true) {
            val line = buf.poll() ?: break
            sb.appendLine(line)
        }
        return sb.toString().trimEnd()
    }

    override fun close() {
        if (closed) return
        closed = true
        transport?.close()
        transport = null
    }

    companion object {
        /**
         * DECSET 1 — `ESC [ ? 1 h` — application cursor keys (DECCKM = on).
         *
         * We push this into the client-side emulator at session start.
         * It looks like an odd thing to do because nothing on the wire
         * ever asked for it, but it's the fix for a fundamental mismatch
         * between how mosh synchronises terminal state and how Haven's
         * emulator interprets that state:
         *
         * 1. Upstream mosh-server runs a real terminal emulator against
         *    the pty. When mutt or emacs invokes `tput smkx`, the server
         *    parses `ESC [ ? 1 h` and updates `Framebuffer::DrawState::
         *    application_mode_cursor_keys = true`. Those bytes are then
         *    CONSUMED by the server-side emulator; they do not enter the
         *    wire protocol.
         *
         * 2. When mosh-server diffs two framebuffer states it uses
         *    `Display::new_frame()` (mosh/src/terminal/terminaldisplay.cc)
         *    to produce the VT100 sequence that transforms the old frame
         *    into the new one. That sequence only emits cursor motion,
         *    cell content, scroll, and window title. It DOES NOT emit
         *    DECCKM, DECKPAM, or any other terminal mode command.
         *
         * 3. Upstream mosh-client compensates for this by writing
         *    `display.open()` — which hard-codes `ESC [ ? 1 h` — to its
         *    host terminal's STDOUT at startup (mosh/src/frontend/stmclient.cc
         *    line ~76). Upstream mosh effectively says: "we always run
         *    in application cursor key mode, starting from session
         *    connect, regardless of what the server's DrawState looks
         *    like right now."
         *
         * 4. Upstream mosh-client then forwards raw STDIN bytes to the
         *    server as UserBytes (no translation). Because the host
         *    terminal is in application mode, pressing Up produces
         *    `ESC O A`, which travels through the wire, into the pty,
         *    into mutt, and is recognised correctly.
         *
         * 5. Haven's mosh port is different: there's no host terminal,
         *    libvterm IS the emulator. Libvterm runs
         *    `vt->state->mode.cursor` as its DECCKM flag (termlib/.../
         *    libvterm/src/keyboard.c `KEYCODE_CSI_CURSOR`). That flag
         *    is only updated when libvterm's parser sees `ESC [ ? 1 h`
         *    on its input stream. Because mosh strips that byte pattern
         *    from the wire (step 2), libvterm never sees it and stays
         *    in normal mode forever.
         *
         * 6. Pressing Up in Haven's mosh session therefore produces
         *    `ESC [ A` (normal mode) instead of `ESC O A` (application
         *    mode). Mutt, which `tput smkx`-ed on startup, expected
         *    application mode and reports "Key is not bound".
         *
         * The minimal fix is to do what upstream mosh-client does at
         * step 3: push `ESC [ ? 1 h` into the local emulator at session
         * start, independently of the wire. Bash's readline binds
         * previous-history to both `ESC [ A` AND `ESC O A`, so forcing
         * application mode doesn't break bash either.
         */
        internal val DECCKM_ON: ByteArray = byteArrayOf(
            0x1B, '['.code.toByte(), '?'.code.toByte(), '1'.code.toByte(), 'h'.code.toByte()
        )
    }
}
