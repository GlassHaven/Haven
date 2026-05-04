package sh.haven.app.desktop

import java.io.Closeable

/**
 * Common abstraction over an active remote-desktop connection.
 *
 * Today: VNC and RDP. Tomorrow: Spice, RustDesk, or anything else
 * that exposes the same primitives. `VncClient` and `RdpSession` live
 * in protocol-pure modules (`core/vnc`, `core/rdp`) and are wrapped by
 * thin adapters in this package — see [VncDesktopSession],
 * [RdpDesktopSession]. The interface deliberately stays at the eight
 * verbs that [DesktopViewModel] used to type-dispatch, so the
 * abstraction is invisible to drivers and only collapses real
 * juxtapositions (VISION.md "places that look unified but aren't").
 *
 * Out of scope for v1:
 * - Keyboard input (VNC X11 keysym vs RDP scancode + Unicode is the
 *   most divergent surface; would require a `RemoteDesktopKeyEvent`
 *   ADT — separate work).
 * - Frame access / connection-state signalling (VNC pulls + throws,
 *   RDP pushes via callbacks — different signal patterns, separate
 *   design pass).
 *
 * Mouse-button numbering follows the X11 convention (1=left, 2=middle,
 * 3=right, 4=scroll-up, 5=scroll-down). Adapters translate to whatever
 * their underlying driver expects.
 */
interface RemoteDesktopSession : Closeable {
    /** Move the remote pointer to (x, y) in framebuffer coordinates. */
    fun sendMouseMove(x: Int, y: Int)

    /** Press or release a mouse button at the current pointer position. */
    fun sendMouseButton(button: Int, pressed: Boolean)

    /** Move to (x, y) and click [button] (press + release). */
    fun sendMouseClick(x: Int, y: Int, button: Int = 1)

    /**
     * Scroll the remote view. Positive [deltaY] scrolls up, negative
     * scrolls down. VNC encodes this as a click on synthetic button
     * 4/5 at the current pointer; RDP uses a native vertical wheel
     * event with a fixed magnitude (this v1 ignores the magnitude of
     * `deltaY` beyond its sign).
     */
    fun sendMouseWheel(deltaY: Int)

    /** Push [text] into the remote clipboard. */
    fun sendClipboardText(text: String)

    /**
     * Throttle background traffic when a tab is not the foreground.
     * VNC stops requesting framebuffer updates; RDP is a no-op
     * (server-driven push, no equivalent client-side knob). Symmetric
     * with [resume].
     */
    fun pause()

    /** Undo [pause]. */
    fun resume()
}
