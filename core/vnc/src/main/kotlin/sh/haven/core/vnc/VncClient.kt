package sh.haven.core.vnc

import android.graphics.Bitmap
import android.util.Log
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

    /** Copy text to the remote clipboard. */
    fun copyText(text: String) {
        session?.sendClientCutText(text)
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
                    if (got) Thread.sleep(interval)
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
