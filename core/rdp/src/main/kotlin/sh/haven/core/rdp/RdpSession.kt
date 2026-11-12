package sh.haven.core.rdp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import sh.haven.rdp.FrameCallback
import sh.haven.rdp.FrameData
import sh.haven.rdp.MouseButton
import sh.haven.rdp.PointerCallback
import sh.haven.rdp.RdpClient
import sh.haven.rdp.RdpConfig
import sh.haven.rdp.RdpException
import sh.haven.rdp.RdpRect
import sh.haven.rdp.RdpTouchPhase
import sh.haven.rdp.RdpTouchPoint
import sh.haven.rdp.SessionCallback
import sh.haven.rdp.SocksProxyConfig
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "RdpSession"

/** Phase of a touch contact sample for MS-RDPEI multi-touch forwarding (#778). */
enum class RdpTouchContactPhase {
    STARTED,
    MOVED,
    ENDED,
    CANCELED,
}

/** One finger contact sample in remote desktop coordinates (#778). */
data class RdpTouchContactSample(
    val pointerId: Long,
    val phase: RdpTouchContactPhase,
    val x: Int,
    val y: Int,
    /** Normalized contact pressure in 0..1024, or null when not reported. */
    val pressure: Int? = null,
)

/**
 * Wraps an [RdpClient] (IronRDP via UniFFI) with Android-specific
 * bitmap management and lifecycle.
 *
 * Similar pattern to VncClient but adapted for RDP:
 * - RDP uses scancodes, not X11 KeySyms
 * - Frame delivery via polling getFramebuffer() + callback for dirty rects
 * - No mid-session resize (RDP requires reconnect)
 */
class RdpSession(
    val sessionId: String,
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val useNla: Boolean = true,
    private val colorDepth: Int = 16,
    onDisconnected: (() -> Unit)? = null,
    private val verboseBuffer: ConcurrentLinkedQueue<String>? = null,
    /**
     * Optional SOCKS5 endpoint for routing IronRDP's TCP through a
     * userspace tunnel — typically the WireGuard / Tailscale tunnel's
     * 127.0.0.1 listener (#149). Null = direct kernel dial.
     */
    private val socksProxy: SocksProxyConfig? = null,
    /**
     * TOFU verifier for the server's TLS certificate (security-review #2). The
     * pinned fingerprint is looked up before connect and a changed cert is
     * rejected inside the native handshake before credentials are sent; the
     * observed cert is pinned on first use. Null disables pinning.
     */
    private val tlsCertVerifier: sh.haven.core.data.agent.TlsCertVerifier? = null,
    /**
     * Stable server identity for the cert pin, independent of the socket
     * target. For an SSH-tunnelled session [host] is 127.0.0.1, so the caller
     * passes the real remote here; defaults to [host]:[port] for direct dials.
     */
    private val certHost: String = host,
    private val certPort: Int = port,
    /**
     * #606: optional per-profile keyboard layout override (BCP-47 tag such as
     * `"es-ES"`, `"en-US"`, `"de-DE"`, `"fr-FR"`). Null / blank uses the device
     * locale via [keyboardLayoutKlid].
     */
    private val keyboardLayout: String? = null,
) : Closeable {

    @Volatile
    private var closed = false
    private var client: RdpClient? = null
    private var currentBitmap: Bitmap? = null

    /**
     * Framebuffer size as last announced by the server, so the dirty-rect path
     * can tell whether [currentBitmap] is still the right size WITHOUT fetching
     * the whole framebuffer to ask (#422). Updated from onConnected/onResize,
     * which are the only things that change it.
     */
    @Volatile
    private var fbWidth: Int = 0

    @Volatile
    private var fbHeight: Int = 0
    // #425: MediaCodec H.264 decoder, created only when AVC is enabled.
    private var avcDecoder: Avc420MediaCodecDecoder? = null
    private val startTime = System.currentTimeMillis()

    private fun log(level: String, msg: String) {
        if (level == "E") Log.e(TAG, msg) else Log.d(TAG, msg)
        verboseBuffer?.add("+${System.currentTimeMillis() - startTime}ms [$TAG] $level: $msg")
    }

    /**
     * Called on frame updates. Set by the ViewModel.
     *
     * ★The bitmap is now the SAME instance for the life of a framebuffer size —
     * it is mutated in place (#422). Anything keyed on its identity (a
     * `StateFlow`, `remember(bitmap)`) will therefore not see a change; observe
     * [frameSeq] to know when to redraw.
     */
    var onFrameUpdate: ((Bitmap) -> Unit)? = null

    /**
     * Bumped once per framebuffer change, so a viewer holding the stable bitmap
     * from [onFrameUpdate] knows to repaint.
     *
     * #422: previously every update allocated a fresh full-screen bitmap purely
     * so the `StateFlow<Bitmap?>` carrying it would emit — it conflates on
     * equality, and re-publishing one instance emits nothing. On a 1920x1080
     * session that meant an 8.29 MB allocation plus two full-frame copies for
     * updates whose median size was 127x82 (about 41 KB), roughly 400x more
     * work than the update contained, at 14-21 updates/sec. That is what made a
     * VirtualBox UEFI menu take seconds to repaint and eventually wedge.
     */
    val frameSeq: StateFlow<Long> get() = _frameSeq
    private val _frameSeq = MutableStateFlow(0L)

    /**
     * Called when the server pushes a new cursor shape / visibility (#212).
     * Args mirror VncConfig.onCursorUpdate: (cursor bitmap or null to hide,
     * hotspot x, hotspot y). The app layer wraps this into a CursorOverlay.
     */
    var onCursorUpdate: ((Bitmap?, Int, Int) -> Unit)? = null

    /**
     * Called when the server moves the pointer (DIRECT mode). Touchpad mode
     * drives the cursor position client-side, so this is advisory.
     */
    var onCursorPosition: ((Int, Int) -> Unit)? = null

    /** Last non-null cursor shape, re-emitted on PointerDefault. */
    private var lastCursor: Triple<Bitmap, Int, Int>? = null

    /** Called when an error occurs. */
    var onError: ((Exception) -> Unit)? = null

    /**
     * Called when the native session loop ends without a surfaced error — a
     * server-side logoff/close, or a socket death the loop treats as a clean
     * exit. Settable post-construction (like [onError]) so the desktop tab
     * can mark itself dead instead of staying "connected" forever (#437).
     */
    var onDisconnected: (() -> Unit)? = onDisconnected

    /**
     * Called once the RDP handshake + capability exchange completes. Prior to
     * this the session is still in the "Connecting" phase even though
     * [start] has returned — the Rust connect() only spawns the worker
     * thread. The UI uses this to flip from "Connecting…" to the rendered
     * framebuffer.
     */
    var onConnected: ((Int, Int) -> Unit)? = null

    /**
     * Start the RDP session. Call from `Dispatchers.IO`.
     *
     * Returns once the TCP connection is established and the worker thread
     * is spawned — this is NOT equivalent to a usable RDP session. The
     * handshake, TLS upgrade and capability exchange happen asynchronously
     * on the worker thread; [onConnected] fires when it completes, [onError]
     * fires if it fails.
     */
    fun start() {
        if (closed) return
        log("D", "Starting RDP session $sessionId: ${redactHost(host, port)} user=${redactUsername(username)}")

        try {
            // Trust-on-first-use pin: the fingerprint remembered from a prior
            // connection, or null on first use. A short blocking DB read.
            val pinnedCert = tlsCertVerifier?.let { v ->
                kotlinx.coroutines.runBlocking { v.pinnedFingerprint(certHost, certPort) }
            }
            // #461: a `MicrosoftAccount\you@example.com` or `CORP\alice` typed
            // into the username box has to reach the Client Info PDU as two
            // fields, or Windows authenticates the transport and then shows the
            // lock screen. Splitting only — a bare UPN is left alone.
            val logon = qualifyRdpLogon(username, domain)
            val config = RdpConfig(
                username = logon.username,
                password = password,
                domain = logon.domain,
                width = width.toUShort(),
                height = height.toUShort(),
                // Per-profile colour depth (#109). Default 16 is xrdp-
                // safe; user can switch to 32 for Windows servers to get
                // RemoteFX-driven smooth updates. Picker lives in the
                // RDP block of ConnectionEditDialog.
                colorDepth = colorDepth.toUByte(),
                enableCredssp = useNla,
                pinnedCertSha256 = pinnedCert,
                // #418 debug opt-in: WBT_TILE_UPGRADE refinement decoding.
                // Bridged from Settings → Diagnostics via RdpDebugToggles.
                progressiveUpgrade = RdpDebugToggles.progressiveUpgrade,
                // #425: advertise H.264/AVC420 (KRDP). Only meaningful with a
                // decoder registered below, so keep the two in lock-step.
                avcEnabled = RdpDebugToggles.avcEnabled,
                // #504 / #606: announce the profile's explicit keyboard layout
                // (or the device locale's layout when unset) for servers that
                // honour the Client Core Data announcement.
                keyboardLayout = keyboardLayoutKlidForTag(keyboardLayout),
            )

            // #477: record which settings this session actually ran with. Two
            // reporter logs labelled "AVC420 enabled" negotiated different
            // pipelines, and nothing in either log said which toggle was live —
            // so whether the setting had failed to apply or the label was wrong
            // could not be told apart without asking.
            log(
                "D",
                "RDP settings: avc420=${config.avcEnabled} progressiveUpgrade=" +
                    "${config.progressiveUpgrade} colorDepth=$colorDepth nla=$useNla " +
                    "klid=0x${config.keyboardLayout.toString(16)}",
            )

            val c = RdpClient(config)
            client = c

            // #425: register the MediaCodec AVC420 decoder before connect so
            // negotiated H.264 tiles have somewhere to decode. The native side
            // calls it (blocking) on the session thread per frame.
            if (RdpDebugToggles.avcEnabled) {
                val dec = Avc420MediaCodecDecoder()
                // #466: route the decode split into the verbose log, so the
                // number that settles this reaches a reporter without adb.
                dec.perfSink = { line -> log("D", line) }
                avcDecoder = dec
                c.setAvcDecoder(dec)
            }

            c.setFrameCallback(object : FrameCallback {
                override fun onFrameUpdate(x: UShort, y: UShort, w: UShort, h: UShort) {
                    if (closed) return
                    try {
                        refreshBitmap(RdpRect(x, y, w, h))
                    } catch (e: Exception) {
                        log("E", "Frame update failed (${x},${y} ${w}x${h}): ${e.message}")
                        onError?.invoke(e)
                    }
                }

                override fun onResize(width: UShort, height: UShort) {
                    if (closed) return
                    log("D", "Desktop resized: ${width}x${height}")
                    fbWidth = width.toInt()
                    fbHeight = height.toInt()
                    try {
                        synchronized(this@RdpSession) {
                            // #425: don't recycle() — this bitmap may still be
                            // referenced by the Compose viewer (asImageBitmap);
                            // recycling under an in-flight draw crashes with
                            // "trying to use a recycled bitmap". refreshBitmap
                            // already leaks superseded frames to GC, so just drop
                            // the ref. Continuous H.264 streaming (KRDP) makes the
                            // draw-vs-recycle race fire where sporadic RDP didn't.
                            currentBitmap = null
                        }
                        refreshBitmap()
                    } catch (e: Exception) {
                        log("E", "Resize failed (${width}x${height}): ${e.message}")
                        onError?.invoke(e)
                    }
                }
            })

            c.setSessionCallback(object : SessionCallback {
                override fun onConnected(width: UShort, height: UShort) {
                    if (closed) return
                    log("D", "RDP handshake complete: ${width}x${height}")
                    fbWidth = width.toInt()
                    fbHeight = height.toInt()
                    onConnected?.invoke(width.toInt(), height.toInt())
                }

                override fun onError(message: String) {
                    if (closed) return
                    log("E", "RDP session error: $message")
                    this@RdpSession.onError?.invoke(RuntimeException(message))
                }

                override fun onDisconnected() {
                    if (closed) return
                    log("D", "RDP session ended cleanly")
                    onDisconnected?.invoke()
                }

                override fun onServerCert(sha256: String) {
                    // First-use TOFU pin (a changed cert was already rejected
                    // in the native handshake, so this only fires on match or
                    // first connect). Idempotent for an already-pinned cert.
                    val v = tlsCertVerifier ?: return
                    try {
                        kotlinx.coroutines.runBlocking { v.accept(certHost, certPort, sha256) }
                        log("D", "Pinned RDP TLS cert for $certHost:$certPort: ${sha256.take(16)}…")
                    } catch (e: Exception) {
                        log("E", "Failed to pin RDP TLS cert: ${e.message}")
                    }
                }
            })

            c.setPointerCallback(object : PointerCallback {
                override fun onPointerBitmap(
                    width: UShort,
                    height: UShort,
                    hotspotX: UShort,
                    hotspotY: UShort,
                    rgba: ByteArray,
                ) {
                    if (closed) return
                    val w = width.toInt()
                    val h = height.toInt()
                    if (w <= 0 || h <= 0 || rgba.size < w * h * 4) {
                        log("E", "Bad pointer bitmap ${w}x$h, ${rgba.size} bytes")
                        return
                    }
                    try {
                        val bmp = pointerToBitmap(rgba, w, h)
                        lastCursor = Triple(bmp, hotspotX.toInt(), hotspotY.toInt())
                        onCursorUpdate?.invoke(bmp, hotspotX.toInt(), hotspotY.toInt())
                    } catch (e: Exception) {
                        log("E", "pointerToBitmap failed (${w}x$h): ${e.message}")
                    }
                }

                override fun onPointerHidden() {
                    if (closed) return
                    // Temporary hide (video/games) — drop the overlay but keep
                    // lastCursor so a subsequent PointerDefault can restore it.
                    onCursorUpdate?.invoke(null, 0, 0)
                }

                override fun onPointerDefault() {
                    if (closed) return
                    lastCursor?.let { (bmp, hx, hy) -> onCursorUpdate?.invoke(bmp, hx, hy) }
                }

                override fun onPointerPosition(x: UShort, y: UShort) {
                    if (closed) return
                    onCursorPosition?.invoke(x.toInt(), y.toInt())
                }
            })

            log("D", "Connecting to ${redactHost(host, port)} (worker thread will handle handshake, socks=${socksProxy != null})")
            c.connect(host, port.toUShort(), socksProxy)
        } catch (e: UnsatisfiedLinkError) {
            val msg = "RDP native library failed to load: ${e.message}"
            log("E", msg)
            val wrapped = RuntimeException(msg, e)
            onError?.invoke(wrapped)
            onDisconnected?.invoke()
            throw wrapped
        } catch (e: Exception) {
            log("E", "RDP connect dispatch failed: ${e.message}")
            onError?.invoke(e)
            onDisconnected?.invoke()
            throw e
        }
    }

    /**
     * Bring the on-screen bitmap up to date. [dirty] is the region the server
     * actually changed; only that region is written, and only into a bitmap
     * that already exists at the right size (#422).
     */
    private fun refreshBitmap(dirty: RdpRect? = null) {
        val c = client ?: return
        val bitmap = try {
            val existing = synchronized(this) { currentBitmap }
            val reusable = existing != null &&
                !existing.isRecycled &&
                existing.width == fbWidth &&
                existing.height == fbHeight
            // The common path: fetch ONLY the changed region and paint it into
            // the bitmap the viewer already holds. Fetching the whole
            // framebuffer here was the last full-frame copy per update (#422).
            // Fastest path: let native write straight into the bitmap we
            // already hold, skipping the Vec, the FFI marshalling and the
            // Kotlin-side copy entirely (#466). Returns false for anything
            // recoverable — wrong size, wrong format, session gone — and we
            // fall through to the byte-array path below.
            val blitted = reusable && dirty != null && RdpBitmapBridge.available &&
                runCatching {
                    RdpBitmapBridge.blitRegion(
                        existing!!,
                        c.bitmapBridgeId(),
                        dirty.x.toInt(),
                        dirty.y.toInt(),
                        dirty.width.toInt(),
                        dirty.height.toInt(),
                    )
                }.getOrDefault(false)

            val region = if (!blitted && reusable && dirty != null) {
                c.getFramebufferRegion(dirty.x, dirty.y, dirty.width, dirty.height)
            } else {
                null
            }
            if (blitted) {
                existing!!
            } else if (region != null) {
                existing!!.also {
                    // A region covering the whole bitmap is the common case, not
                    // the exception: progressive and AVC both hand us a
                    // full-screen dirty bbox every frame. drawTile would
                    // allocate a second full-size bitmap, copy into it, then
                    // blit that over the destination — two 8.3MB copies and an
                    // 8.3MB allocation per frame, to land the pixels exactly
                    // where copyPixelsFromBuffer puts them in one. Measured at
                    // 1080p the whole publish step was ~72ms/frame (#466).
                    if (dirty!!.x.toInt() == 0 && dirty.y.toInt() == 0 &&
                        region.width.toInt() == it.width && region.height.toInt() == it.height
                    ) {
                        it.copyPixelsFromBuffer(ByteBuffer.wrap(region.pixels))
                    } else {
                        drawTile(it, region, dirty.x.toInt(), dirty.y.toInt())
                    }
                }
            } else {
                // First frame, a resize, or a rect the native side rejected:
                // fall back to a full repaint.
                val frame = c.getFramebuffer() ?: return
                if (reusable && existing!!.width == frame.width.toInt() &&
                    existing.height == frame.height.toInt()
                ) {
                    existing.also { it.copyPixelsFromBuffer(ByteBuffer.wrap(frame.pixels)) }
                } else {
                    frameToBitmap(frame)
                }
            }
        } catch (e: Exception) {
            log("E", "frame paint failed (dirty=${dirty?.width}x${dirty?.height}): ${e.message}")
            onError?.invoke(e)
            return
        }
        synchronized(this) {
            currentBitmap = bitmap
        }
        // Identity may be unchanged, so the counter is what tells the viewer to
        // repaint. Bumped before the callback so an observer that reads it
        // synchronously sees this frame, not the previous one.
        _frameSeq.value = _frameSeq.value + 1
        onFrameUpdate?.invoke(bitmap)
    }

    /**
     * Paint an already tightly-packed RGBA [region] into [dst] at ([x], [y]).
     *
     * Goes via a tile bitmap and [Canvas] rather than `setPixels`, because
     * `copyPixelsFromBuffer` keeps the same RGBA byte order the full-frame path
     * has always used — `setPixels` takes packed ARGB ints and would reintroduce
     * the red/blue swap of #212.
     */
    private fun drawTile(dst: Bitmap, region: FrameData, x: Int, y: Int) {
        val w = region.width.toInt()
        val h = region.height.toInt()
        if (w <= 0 || h <= 0) return
        val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            tile.copyPixelsFromBuffer(ByteBuffer.wrap(region.pixels))
            Canvas(dst).drawBitmap(tile, x.toFloat(), y.toFloat(), null)
        } finally {
            tile.recycle()
        }
    }

    /**
     * Convert FrameData (ARGB_8888 byte array) to Android Bitmap.
     */
    private fun frameToBitmap(frame: FrameData): Bitmap {
        val w = frame.width.toInt()
        val h = frame.height.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.pixels))
        return bitmap
    }

    /**
     * Convert an IronRDP DecodedPointer bitmap (RGBA bytes, non-premultiplied
     * alpha — `pointer_software_rendering` is off) to an Android Bitmap. Packs
     * each RGBA pixel into an ARGB colour int; `createBitmap(IntArray, …)`
     * treats the input as non-premultiplied and stores it premultiplied, so the
     * Compose overlay alpha-blends correctly. Mirrors the VNC cursor path
     * (Framebuffer.renderCursor).
     */
    private fun pointerToBitmap(rgba: ByteArray, w: Int, h: Int): Bitmap {
        val argb = IntArray(w * h)
        for (i in 0 until w * h) {
            val o = i * 4
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            val a = rgba[o + 3].toInt() and 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Get the current frame as a Bitmap. */
    fun getFrame(): Bitmap? = synchronized(this) { currentBitmap }

    // --- Input forwarding ---

    fun sendKey(scancode: Int, pressed: Boolean) {
        if (closed) return
        client?.sendKey(scancode.toUShort(), pressed)
    }

    fun sendUnicodeKey(codepoint: Int, pressed: Boolean) {
        if (closed) return
        client?.sendUnicodeKey(codepoint.toUInt(), pressed)
    }

    fun sendMouseMove(x: Int, y: Int) {
        if (closed) return
        client?.sendMouseMove(x.toUShort(), y.toUShort())
    }

    fun sendMouseButton(button: MouseButton, pressed: Boolean) {
        if (closed) return
        client?.sendMouseButton(button, pressed)
    }

    fun sendMouseClick(x: Int, y: Int, button: MouseButton = MouseButton.LEFT) {
        if (closed) return
        client?.sendMouseMove(x.toUShort(), y.toUShort())
        client?.sendMouseButton(button, true)
        client?.sendMouseButton(button, false)
    }

    fun sendMouseWheel(vertical: Boolean, delta: Int) {
        if (closed) return
        client?.sendMouseWheel(vertical, delta.toShort())
    }

    fun sendClipboardText(text: String) {
        if (closed) return
        client?.sendClipboardText(text)
    }

    fun sendTouchFrame(points: List<RdpTouchContactSample>) {
        if (closed || points.isEmpty()) return
        val nativePoints = points.map { pt ->
            RdpTouchPoint(
                pointerId = pt.pointerId.toULong(),
                phase = when (pt.phase) {
                    RdpTouchContactPhase.STARTED -> RdpTouchPhase.STARTED
                    RdpTouchContactPhase.MOVED -> RdpTouchPhase.MOVED
                    RdpTouchContactPhase.ENDED -> RdpTouchPhase.ENDED
                    RdpTouchContactPhase.CANCELED -> RdpTouchPhase.CANCELED
                },
                x = pt.x,
                y = pt.y,
                pressure = pt.pressure?.coerceIn(0, 1024)?.toUInt(),
            )
        }
        client?.sendTouchFrame(nativePoints)
    }

    fun cancelTouches() {
        if (closed) return
        client?.cancelTouches()
    }

    fun isTouchReady(): Boolean {
        if (closed) return false
        return client?.isTouchReady() == true
    }

    /**
     * Pull the native EGFX frame timings into the verbose buffer (#477).
     *
     * These say whether decode is actually the bottleneck, and they used to go
     * only to the Android log — needing adb to read, so the one measurement
     * that settles a lag report was invisible to the people filing them.
     *
     * Best-effort: a diagnostic that breaks the log it is being written into
     * would be worse than no diagnostic.
     */
    private fun drainNativePerfLog() {
        val lines = try {
            client?.takePerfLog().orEmpty()
        } catch (e: Exception) {
            log("E", "Could not read native perf log: ${e.message}")
            return
        }
        lines.forEach { log("D", it) }
    }

    /** Drain captured verbose logs. Returns null if verbose logging was not enabled. */
    fun drainVerboseLog(): String? {
        val buf = verboseBuffer ?: return null
        // Before the empty check, not after: a session whose only entries are
        // perf lines would otherwise report having nothing to say.
        drainNativePerfLog()
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
        log("D", "Closing RDP session $sessionId")
        try {
            client?.disconnect()
        } catch (e: Exception) {
            log("E", "Error disconnecting RDP: ${e.message}")
        }
        client = null
        try {
            avcDecoder?.close()
        } catch (e: Exception) {
            log("E", "Error releasing AVC decoder: ${e.message}")
        }
        avcDecoder = null
        synchronized(this) {
            // #425: never recycle a bitmap the Compose viewer may still be
            // drawing (see onResize) — drop the ref and let GC reclaim it.
            currentBitmap = null
        }
    }
}
