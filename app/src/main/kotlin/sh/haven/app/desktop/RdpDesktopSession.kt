package sh.haven.app.desktop

import sh.haven.core.rdp.RdpSession
import sh.haven.rdp.MouseButton

/**
 * [RemoteDesktopSession] adapter over [RdpSession]. RDP exposes a
 * native API close to the abstraction — most methods are 1:1 passthroughs.
 *
 * Two translation points:
 *
 *  - `Int` button numbers (X11 convention) → RDP's `MouseButton` enum.
 *    Mirrors the existing `MouseButton.entries.getOrElse(button - 1)`
 *    mapping in `DesktopViewModel`, preserving its (slightly odd)
 *    behaviour where button=2 maps to RIGHT and button=3 to MIDDLE.
 *    Today no caller invokes 2 or 3 against an RDP tab, so this is
 *    inert — flagging for a future tidy-up rather than fixing
 *    silently in this PR.
 *  - Mouse wheel: the abstraction takes a `deltaY` whose sign carries
 *    the direction; the RDP backend wants `(vertical: Boolean,
 *    delta: Int)` where the delta is the absolute step (typical 120
 *    per notch, matching Windows WHEEL_DELTA).
 */
class RdpDesktopSession(private val session: RdpSession) : RemoteDesktopSession {
    override fun sendMouseMove(x: Int, y: Int) {
        session.sendMouseMove(x, y)
    }

    override fun sendMouseButton(button: Int, pressed: Boolean) {
        session.sendMouseButton(toRdpButton(button), pressed)
    }

    override fun sendMouseClick(x: Int, y: Int, button: Int) {
        session.sendMouseClick(x, y, toRdpButton(button))
    }

    override fun sendMouseWheel(deltaY: Int) {
        if (deltaY == 0) return
        val magnitude = if (deltaY > 0) 120 else -120
        session.sendMouseWheel(vertical = true, delta = magnitude)
    }

    override fun sendClipboardText(text: String) {
        session.sendClipboardText(text)
    }

    /** RDP is server-push; there is no client-side throttle. */
    override fun pause() {}

    override fun resume() {}

    override fun close() {
        session.close()
    }

    private fun toRdpButton(x11Button: Int): MouseButton =
        MouseButton.entries.getOrElse(x11Button - 1) { MouseButton.LEFT }
}
