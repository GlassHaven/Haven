package sh.haven.core.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Companion to the core:ssh test of the same name — this enum drives the local
 * (proot) wrapper, that one drives remote attaches, and they have drifted
 * before. See core:ssh for the measurements: `window-size smallest` clamps each
 * dimension independently and cost the phone rows, and grouped sessions share
 * their windows' size so they do not help either.
 */
class SessionManagerWindowSizeTest {

    @Test
    fun `tmux attach does not pin window-size`() {
        val cmd = UserPreferencesRepository.SessionManager.TMUX.command!!("work")
        assertFalse("pinning window-size clamps the shared window. Got: $cmd", cmd.contains("window-size"))
    }

    @Test
    fun `byobu attach does not pin window-size`() {
        val cmd = UserPreferencesRepository.SessionManager.BYOBU.command!!("work")
        assertFalse("pinning window-size clamps the shared window. Got: $cmd", cmd.contains("window-size"))
    }

    @Test
    fun `rmux attach does not pin window-size`() {
        val cmd = UserPreferencesRepository.SessionManager.RMUX.command!!("work")
        assertFalse("pinning window-size clamps the shared window. Got: $cmd", cmd.contains("window-size"))
    }
}
