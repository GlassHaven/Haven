package sh.haven.core.vnc

import android.graphics.Bitmap
import android.util.Log
import sh.haven.core.vnc.io.CountingInputStream
import sh.haven.core.vnc.io.ThroughputTracker
import sh.haven.core.vnc.protocol.Handshaker
import sh.haven.core.vnc.protocol.Initializer
import sh.haven.core.vnc.rendering.Framebuffer
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

private const val TAG = "VncClient"

/**
 * VNC client that connects to a VNC server and delivers framebuffer updates
 * as Android Bitmaps. Ported from vernacular-vnc (MIT) with AWT replaced
 * by android.graphics.
 */
class VncClient(private val config: VncConfig) : Closeable {

    private var session: VncSession? = null
    private var serverEventLoop: Thread? = null
    private var clientEventLoop: Thread? = null

    @Volatile
    var running = false
        private set

    @Volatile
    var paused = false

    fun start(host: String, port: Int) {
        start(Socket(host, port), host)
    }

    /** Start with an existing socket. [host] is used for TLS SNI / certificate checks. */
    fun start(socket: Socket, host: String = socket.inetAddress?.hostAddress ?: "localhost") {
        if (running) throw IllegalStateException("VNC client is already running")
        running = true

        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val sess = VncSession(config, input, output)

            Log.d(TAG, "Handshaking...")
            Handshaker.handshake(sess, socket, host)
            Log.d(TAG, "Initialising...")
            Initializer.initialise(sess)
            Log.d(TAG, "Connected: ${sess.serverInit?.name} ${sess.serverInit?.framebufferWidth}x${sess.serverInit?.framebufferHeight}")

            // Wrap the (now-final) input stream with a byte counter for the
            // bandwidth-suggestion feature (#107). On VeNCrypt-TLS sessions
            // the stream we wrap is the SSLSocket's stream — i.e. counted
            // bytes are the post-decryption payload, not raw on-the-wire
            // bytes. The TLS overhead is small and bandwidth-decisions
            // care about magnitude, so this is fine.
            val counter = CountingInputStream(sess.inputStream)
            sess.inputStream = counter
            sess.bandwidthCounter = counter

            session = sess
            val framebuffer = Framebuffer(sess)

            startServerEventLoop(sess, framebuffer)
            startClientEventLoop(sess)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed during setup", e)
            handleError(e)
        }
    }

    /** Move the remote mouse pointer. */
    fun moveMouse(x: Int, y: Int) {
        session?.sendPointerEvent(x, y)
    }

    /** Press or release a mouse button (1=left, 2=middle, 3=right, 4/5=scroll). */
    fun updateMouseButton(button: Int, pressed: Boolean) {
        session?.updateMouseButton(button, pressed)
    }

    /** Click (press + release) a mouse button. */
    fun click(button: Int) {
        updateMouseButton(button, true)
        updateMouseButton(button, false)
    }

    /** Press or release a key by X11 KeySym. */
    fun updateKey(keySym: Int, pressed: Boolean) {
        session?.sendKeyEvent(keySym, pressed)
    }

    /** Type (press + release) a key by X11 KeySym. */
    fun type(keySym: Int) {
        updateKey(keySym, true)
        updateKey(keySym, false)
    }

    /**
     * Type a string of printable characters using explicit shift-pair
     * encoding: every shifted character (uppercase letter, `!@#$%^&*` …)
     * is sent as `Shift-down → base key down/up → Shift-up`. We never
     * hand the server a "shifted-form" keysym like `0x41` ('A') or
     * `0x3a` (':') and let it figure out the modifier — many VNC
     * servers (notably QEMU's built-in one) misinterpret those and
     * produce mojibake like "iWr HTTP!8765/ENABLE…" for "iwr http:8765/
     * enable…". Driving the shift modifier ourselves matches what
     * RealVNC and TigerVNC do for the same compatibility reason.
     *
     * All chars go through a single call (single thread of execution)
     * so on-wire ordering is guaranteed. A 5 ms inter-char gap is
     * conservative pacing for servers that inject at the scancode
     * level and drop bursts above ~1 kHz. Assumes a US keyboard layout
     * for the shift map; non-US layouts will need a per-layout table.
     */
    fun typeText(text: String) {
        // Send the literal X11 keysym for each character (e.g. 0x41 for
        // 'A', 0x3a for ':') and explicitly wrap shifted chars with
        // Shift_L down/up events. QEMU's keymap requires both:
        //   - the keysym must be the shifted form, because the keymap
        //     entry is `colon 0x27 shift` / `A 0x1e shift` (no entries
        //     for "send the base keysym and we'll auto-shift")
        //   - the shift modifier must be held, because without it QEMU
        //     falls through to the unshifted entry on the same scancode
        //     (so ':' would render as ';')
        //
        // Pacing: 10 ms after shift-down so the modifier registers
        // before the key, 5 ms key down→up, 15 ms char→char. Bursts
        // faster than this drop chars against QEMU's VNC.
        for (ch in text) {
            val (keySym, needsShift) = charToKeyEvent(ch)
            if (needsShift) {
                session?.sendKeyEvent(SHIFT_L, true)
                if (sleep(10)) return
            }
            session?.sendKeyEvent(keySym, true)
            if (sleep(5)) return
            session?.sendKeyEvent(keySym, false)
            if (needsShift) {
                if (sleep(5)) return
                session?.sendKeyEvent(SHIFT_L, false)
            }
            if (sleep(15)) return
        }
    }

    /** Returns true if interrupted (caller should bail). */
    private fun sleep(ms: Long): Boolean {
        return try { Thread.sleep(ms); false } catch (_: InterruptedException) { true }
    }

    /** Copy text to the remote clipboard. */
    fun copyText(text: String) {
        session?.sendClientCutText(text)
    }

    /**
     * Map a printable character to (literal X11 keysym, needsShift).
     * The keysym is *always* the literal X11 keysym for the printed
     * character (e.g. 0x41 for 'A', 0x3a for ':'); needsShift tells us
     * whether to wrap with Shift_L events. QEMU's keymap relies on both
     * being correct — see [typeText].
     */
    private fun charToKeyEvent(ch: Char): Pair<Int, Boolean> = when (ch) {
        '\n', '\r' -> RETURN to false
        '\t'       -> TAB to false
        '\b'       -> BACKSPACE to false
        ' '        -> 0x20 to false
        in 'a'..'z' -> ch.code to false
        in 'A'..'Z' -> ch.code to true
        in '0'..'9' -> ch.code to false
        // Unshifted US-layout symbols
        '-', '=', '[', ']', '\\', ';', '\'', ',', '.', '/', '`' -> ch.code to false
        // Shifted US-layout symbols
        '!', '@', '#', '$', '%', '^', '&', '*', '(', ')',
        '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '~' -> ch.code to true
        // Latin-1 / Unicode pass-through, no shift. Won't render
        // correctly on non-US server keymaps; needs per-layout work.
        else -> ch.code to false
    }

    private companion object {
        const val RETURN = 0xff0d
        const val TAB = 0xff09
        const val BACKSPACE = 0xff08
        const val SHIFT_L = 0xffe1
    }

    override fun close() {
        stop()
    }

    fun stop() {
        running = false
        serverEventLoop?.join(1000)
        clientEventLoop?.let {
            it.interrupt()
            it.join(1000)
        }
        session?.kill()
        session = null
    }

    private fun startServerEventLoop(sess: VncSession, framebuffer: Framebuffer) {
        serverEventLoop = Thread({
            val input = java.io.PushbackInputStream(sess.inputStream)
            try {
                while (running) {
                    val messageType = input.read()
                    if (messageType == -1) break
                    input.unread(messageType)

                    when (messageType) {
                        0x00 -> {
                            val update = sh.haven.core.vnc.protocol.FramebufferUpdate.decode(input)
                            framebuffer.processUpdate(update)
                        }
                        0x01 -> {
                            val colorMap = sh.haven.core.vnc.protocol.SetColorMapEntries.decode(input)
                            framebuffer.updateColorMap(colorMap)
                        }
                        0x02 -> {
                            // Bell
                            input.read() // consume the type byte
                            config.onBell?.invoke()
                        }
                        0x03 -> {
                            val cutText = sh.haven.core.vnc.protocol.ServerCutText.decode(input)
                            if (cutText.isNotEmpty()) {
                                config.onRemoteClipboard?.invoke(cutText)
                            }
                        }
                        else -> {
                            Log.w(TAG, "Unknown VNC message type: $messageType")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) handleError(e)
            } finally {
                running = false
            }
        }, "vnc-server-events").also { it.isDaemon = true; it.start() }
    }

    private fun startClientEventLoop(sess: VncSession) {
        clientEventLoop = Thread({
            try {
                var receivedPixels = false
                var lastPixelTime = System.currentTimeMillis()
                // Bandwidth-suggestion state (#107). Sample bytes-received
                // every loop iteration into the tracker. Once we've measured
                // for at least 10s and throughput is sustained below the
                // suggest-downshift threshold, fire one suggestion. The
                // ViewModel debounces and the UI shows a banner.
                val tracker = ThroughputTracker(windowMs = 10_000L)
                val sessionStartMs = System.currentTimeMillis()
                var bandwidthSuggestionFired = false
                while (running) {
                    if (paused) {
                        Thread.sleep(200)
                        continue
                    }
                    val interval = 1000L / config.targetFps
                    val now = System.currentTimeMillis()

                    // If we haven't received pixel data in 2s, force a full refresh
                    val forceFullRefresh = receivedPixels && (now - lastPixelTime > 2000)
                    val incremental = receivedPixels && !forceFullRefresh

                    sess.requestFramebufferUpdate(incremental = incremental)

                    // Use timeout so we don't block forever on incremental requests
                    val got = sess.waitForFramebufferUpdate(2000)
                    if (got && sess.lastUpdateHadRectangles) {
                        receivedPixels = true
                        lastPixelTime = System.currentTimeMillis()
                    }

                    // Bandwidth check.
                    sess.bandwidthCounter?.let { counter ->
                        tracker.sample(now, counter.bytesRead)
                        if (!bandwidthSuggestionFired &&
                            (now - sessionStartMs) > 10_000L &&
                            tracker.windowSpanMs() >= 10_000L &&
                            config.colorDepth == ColorDepth.BPP_24_TRUE
                        ) {
                            val bps = tracker.throughputBytesPerSec() ?: 0.0
                            // <1 Mbps = 125_000 bytes/sec.
                            if (bps in 1.0..125_000.0) {
                                bandwidthSuggestionFired = true
                                config.onBandwidthSuggestion?.invoke(ColorDepth.BPP_8_INDEXED)
                            }
                        }
                    }

                    // Pace the next request: wait up to `interval` ms, but wake
                    // immediately if the UI sent input so the next refresh goes
                    // out on the RTT instead of on the scheduled tick.
                    if (got) sess.waitForInput(interval)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                if (running) handleError(e)
            } finally {
                running = false
            }
        }, "vnc-client-events").also { it.isDaemon = true; it.start() }
    }

    private fun handleError(e: Exception) {
        Log.e(TAG, "VNC error", e)
        config.onError?.invoke(e)
        stop()
    }
}
