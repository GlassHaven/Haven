package sh.haven.core.vnc

import sh.haven.core.vnc.protocol.Encodable
import sh.haven.core.vnc.protocol.FramebufferUpdateRequest
import sh.haven.core.vnc.protocol.KeyEvent
import sh.haven.core.vnc.protocol.PixelFormat
import sh.haven.core.vnc.protocol.PointerEvent
import sh.haven.core.vnc.protocol.ClientCutText
import sh.haven.core.vnc.protocol.ProtocolVersion
import sh.haven.core.vnc.protocol.ServerInit
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock

/**
 * Holds the state of an active VNC session.
 */
class VncSession(
    val config: VncConfig,
    inputStream: InputStream,
    outputStream: OutputStream,
) {
    /** Streams are var so the handshaker can swap in TLS-wrapped versions for VeNCrypt. */
    var inputStream: InputStream = inputStream
        internal set
    var outputStream: OutputStream = outputStream
        internal set

    var protocolVersion: ProtocolVersion? = null
    var serverInit: ServerInit? = null
    var pixelFormat: PixelFormat? = null

    @Volatile var framebufferWidth: Int = 0
    @Volatile var framebufferHeight: Int = 0

    /** Set by Framebuffer.processUpdate — true when the last update contained rectangles with pixel data. */
    @Volatile var lastUpdateHadRectangles: Boolean = false

    private val outputLock = ReentrantLock(true)
    private val fbLock = ReentrantLock()
    private val fbCondition = fbLock.newCondition()
    @Volatile private var fbUpdated = false

    private val inputLock = ReentrantLock()
    private val inputCondition = inputLock.newCondition()
    @Volatile private var inputPending = false

    // Mouse state
    private val buttons = BooleanArray(8)
    private var mouseX = 0
    private var mouseY = 0

    fun waitForFramebufferUpdate() {
        fbLock.lock()
        try {
            while (!fbUpdated) fbCondition.await()
            fbUpdated = false
        } finally {
            fbLock.unlock()
        }
    }

    /**
     * Wait for a framebuffer update with a timeout.
     * Returns true if an update was received, false if timed out.
     */
    fun waitForFramebufferUpdate(timeoutMs: Long): Boolean {
        fbLock.lock()
        try {
            if (!fbUpdated) {
                fbCondition.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            val got = fbUpdated
            fbUpdated = false
            return got
        } finally {
            fbLock.unlock()
        }
    }

    fun framebufferUpdated() {
        fbLock.lock()
        try {
            fbUpdated = true
            fbCondition.signalAll()
        } finally {
            fbLock.unlock()
        }
    }

    /**
     * Wait for input to be sent by the UI, up to [timeoutMs].
     * Returns true if input was signalled, false on timeout. The client loop uses
     * this as a reactive pacing primitive: normally waits out the frame interval,
     * but wakes immediately after the user clicks / types so the next
     * FramebufferUpdateRequest goes out without delay.
     */
    fun waitForInput(timeoutMs: Long): Boolean {
        inputLock.lock()
        try {
            if (!inputPending) {
                inputCondition.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            val signalled = inputPending
            inputPending = false
            return signalled
        } finally {
            inputLock.unlock()
        }
    }

    private fun signalInputSent() {
        inputLock.lock()
        try {
            inputPending = true
            inputCondition.signalAll()
        } finally {
            inputLock.unlock()
        }
    }

    fun requestFramebufferUpdate(incremental: Boolean) {
        val msg = FramebufferUpdateRequest(incremental, 0, 0, framebufferWidth, framebufferHeight)
        sendMessage(msg)
    }

    fun sendPointerEvent(x: Int, y: Int) {
        mouseX = x
        mouseY = y
        sendMouseStatus()
    }

    fun updateMouseButton(button: Int, pressed: Boolean) {
        buttons[button - 1] = pressed
        sendMouseStatus()
    }

    /** Current pointer position (in framebuffer coords). */
    fun pointerPosition(): Pair<Int, Int> = mouseX to mouseY

    private fun sendMouseStatus() {
        var mask = 0
        for (i in buttons.indices) {
            if (buttons[i]) mask = mask or (1 shl i)
        }
        sendMessage(PointerEvent(mouseX, mouseY, mask))
        signalInputSent()
    }

    fun sendKeyEvent(keySym: Int, pressed: Boolean) {
        sendMessage(KeyEvent(keySym, pressed))
        signalInputSent()
    }

    fun sendClientCutText(text: String) {
        sendMessage(ClientCutText(text))
        signalInputSent()
    }

    fun sendMessage(msg: Encodable) {
        outputLock.lock()
        try {
            msg.encode(outputStream)
            outputStream.flush()
        } catch (_: java.io.IOException) {
            // Connection lost — will be picked up by the event loops
        } finally {
            outputLock.unlock()
        }
    }

    fun kill() {
        try { inputStream.close() } catch (_: Exception) {}
        try { outputStream.close() } catch (_: Exception) {}
    }
}
