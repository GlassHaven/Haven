package sh.haven.core.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarKey
import sh.haven.core.data.preferences.ToolbarLayout

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

    /** #772: Desktop sessions expose the Win modifier after Alt in row 2 by default. */
    @Test
    fun `effectiveToolbarLayout inserts WIN after ALT when hasWinKey is true`() {
        val effective = effectiveToolbarLayout(ToolbarLayout.DEFAULT, hasWinKey = true)
        val row2Keys = effective.row2.filterIsInstance<ToolbarItem.BuiltIn>().map { it.key }
        val altIdx = row2Keys.indexOf(ToolbarKey.ALT)
        assertTrue("ALT must be present in default row 2", altIdx >= 0)
        assertEquals(ToolbarKey.WIN, row2Keys[altIdx + 1])
    }

    /** #772: User-placed WIN key is kept at its chosen position on Desktop sessions. */
    @Test
    fun `effectiveToolbarLayout preserves user-placed WIN key`() {
        val customLayout = ToolbarLayout(
            listOf(
                listOf(ToolbarItem.BuiltIn(ToolbarKey.WIN), ToolbarItem.BuiltIn(ToolbarKey.ESC_KEY)),
                listOf(ToolbarItem.BuiltIn(ToolbarKey.CTRL), ToolbarItem.BuiltIn(ToolbarKey.ALT)),
            ),
        )
        val effective = effectiveToolbarLayout(customLayout, hasWinKey = true)
        assertEquals(customLayout, effective)
    }

    /** #772: Terminal sessions filter out WIN so it does not leave an empty cell. */
    @Test
    fun `effectiveToolbarLayout strips WIN when hasWinKey is false`() {
        val withWin = effectiveToolbarLayout(ToolbarLayout.DEFAULT, hasWinKey = true)
        val terminalLayout = effectiveToolbarLayout(withWin, hasWinKey = false)
        val allKeys = terminalLayout.rows.flatten()
            .filterIsInstance<ToolbarItem.BuiltIn>()
            .map { it.key }
        assertFalse(ToolbarKey.WIN in allKeys)
    }
}