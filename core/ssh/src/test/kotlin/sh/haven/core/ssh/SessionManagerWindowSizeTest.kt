package sh.haven.core.ssh

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Do NOT pin tmux's `window-size` on attach. This is a regression guard for a
 * fix that made things worse, kept because the reasoning is not obvious and
 * the option looks like an improvement.
 *
 * #479 reported terminal text cut off on the right when a desktop client was
 * attached to the same tmux session: tmux's default `window-size latest` sizes
 * the shared window for whichever client was used most recently, and a phone
 * being *read* rather than typed on loses that race. Setting `window-size
 * smallest` fixed the width and was released in v5.86.23.
 *
 * It then broke the height. `smallest` takes the minimum in each dimension
 * INDEPENDENTLY, so with a phone at 98x44 and a desktop at 189x42 the window
 * became 98x42 — the phone's width, the desktop's height — and the phone lost
 * rows it had room for. Measured on the reporter's own server:
 *
 *     phone clients    98x44
 *     desktop clients  189x42
 *     window           98x41   (42 minus the status line)
 *
 * The tempting alternative does not work either. Grouped sessions
 * (`new-session -t`) are the usual advice for devices of different sizes, but
 * a group shares its windows and therefore shares their size. Measured with
 * real attached clients on a scratch server:
 *
 *     client 189x42 on A, client 98x44 on grouped B
 *     -> window A 98x42, window B 98x42
 *
 * One window has one size; some client is always compromised. tmux's default
 * at least gives the device you are actually touching its exact size, which is
 * the behaviour asked for after both regressions were on the table.
 */
class SessionManagerWindowSizeTest {

    @Test
    fun `tmux attach does not pin window-size`() {
        val cmd = SessionManager.TMUX.command!!("work")
        assertFalse(
            "pinning window-size clamps the shared window per-dimension across all " +
                "attached clients — it cost the phone 3 rows in #479 follow-up. Got: $cmd",
            cmd.contains("window-size"),
        )
    }

    @Test
    fun `byobu attach does not pin window-size`() {
        val cmd = SessionManager.BYOBU.command!!("work")
        assertFalse(
            "byobu wraps tmux and inherits the same clamping. Got: $cmd",
            cmd.contains("window-size"),
        )
    }

    @Test
    fun `rmux attach does not pin window-size`() {
        val cmd = SessionManager.RMUX.command!!("work")
        assertFalse(
            "rmux shares tmux window sizing semantics. Got: $cmd",
            cmd.contains("window-size"),
        )
    }

    /** The options that ARE wanted must survive this revert. */
    @Test
    fun `the mouse and passthrough options are still set`() {
        val cmd = SessionManager.TMUX.command!!("work")
        assertFalse("mouse option lost", !cmd.contains("set -gq mouse on"))
        assertFalse("allow-passthrough lost", !cmd.contains("set -gq allow-passthrough on"))
    }
}
