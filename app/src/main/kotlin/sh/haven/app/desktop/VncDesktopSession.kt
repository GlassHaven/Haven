package sh.haven.app.desktop

import sh.haven.core.vnc.VncClient

/**
 * [RemoteDesktopSession] adapter over [VncClient]. All methods route to
 * the existing client surface — see `VncClient.kt` lines 78–88, 157,
 * 194–198 for the targets. Nothing in `core/vnc` changes for #128.
 */
class VncDesktopSession(private val client: VncClient) : RemoteDesktopSession {
    override fun sendMouseMove(x: Int, y: Int) {
        client.moveMouse(x, y)
    }

    override fun sendMouseButton(button: Int, pressed: Boolean) {
        client.updateMouseButton(button, pressed)
    }

    override fun sendMouseClick(x: Int, y: Int, button: Int) {
        client.moveMouse(x, y)
        client.click(button)
    }

    override fun sendMouseWheel(deltaY: Int) {
        // VNC encodes scroll as a click on button 4 (up) or 5 (down)
        // at the current pointer position — the server already tracks
        // the pointer, so no explicit move is needed. Mirrors the
        // pre-abstraction `tab.client.click(4 / 5)` calls in
        // DesktopViewModel.scrollUp / scrollDown.
        when {
            deltaY > 0 -> client.click(4)
            deltaY < 0 -> client.click(5)
            // deltaY == 0 → no-op
        }
    }

    override fun sendClipboardText(text: String) {
        client.copyText(text)
    }

    override fun pause() {
        client.paused = true
    }

    override fun resume() {
        client.paused = false
    }

    override fun close() {
        client.stop()
    }
}
