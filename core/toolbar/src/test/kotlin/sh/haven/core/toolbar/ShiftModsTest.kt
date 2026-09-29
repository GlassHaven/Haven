package sh.haven.core.toolbar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #665 — "shift+left doesn't work in Codex in SSH."
 *
 * The toolbar dispatches nav keys straight into libvterm's dispatchKey with a
 * modifier bitmask (TerminalNative: 1=Shift, 2=Alt, 4=Ctrl). The sticky Shift
 * toggle lives here in the toolbar, so it can only reach dispatchKey through
 * these bits — previously every site passed 0 and Shift+Left collapsed to a
 * bare left arrow.
 *
 * This pins the bit convention at the toolbar boundary: bit 0, matching
 * MODIFIER_SHIFT in termlib's KeyboardHandler. A wrong bit (2=Alt) or a
 * dropped fold fails here rather than only on a device with a shell that
 * binds shift+arrow.
 */
class ShiftModsTest {
    @Test
    fun `sticky shift folds as bit 0`() {
        assertEquals(1, shiftMods(shiftActive = true))
    }

    @Test
    fun `no shift dispatches unmodified`() {
        assertEquals(0, shiftMods(shiftActive = false))
    }
}