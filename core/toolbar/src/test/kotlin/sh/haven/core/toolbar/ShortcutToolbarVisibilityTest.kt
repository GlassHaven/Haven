package sh.haven.core.toolbar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository.TerminalShortcutToolbar.AUTO
import sh.haven.core.data.preferences.UserPreferencesRepository.TerminalShortcutToolbar.HIDE
import sh.haven.core.data.preferences.UserPreferencesRepository.TerminalShortcutToolbar.SHOW

/**
 * #870 — hide the extra key rows when a physical keyboard makes them
 * redundant. Pins the visibility decision the TerminalScreen gate uses:
 *
 *  - SHOW keeps the pre-#870 behaviour in every state.
 *  - AUTO hides only while a physical keyboard is attached AND usable
 *    (the same Configuration test that suppresses soft-keyboard auto-show,
 *    #511 — a folded/docked keyboard does not count).
 *  - HIDE always hides.
 *  - The per-session reveal handle and an active text selection override
 *    any hide: selection owns the copy/paste content, so hiding it would
 *    strand the user with selected text and no way to copy it.
 */
class ShortcutToolbarVisibilityTest {

    @Test
    fun `SHOW is visible regardless of keyboard or reveal state`() {
        for (attached in listOf(false, true)) {
            assertTrue(shortcutToolbarBaseVisible(SHOW, attached))
            assertTrue(shortcutToolbarVisible(SHOW, attached, revealed = false, selectionActive = false))
        }
    }

    @Test
    fun `AUTO hides only while a physical keyboard is attached`() {
        assertTrue(shortcutToolbarBaseVisible(AUTO, physicalKeyboardAttached = false))
        assertFalse(shortcutToolbarBaseVisible(AUTO, physicalKeyboardAttached = true))
    }

    @Test
    fun `HIDE hides regardless of keyboard`() {
        for (attached in listOf(false, true)) {
            assertFalse(shortcutToolbarBaseVisible(HIDE, attached))
        }
    }

    @Test
    fun `reveal handle overrides AUTO-hide and HIDE`() {
        assertTrue(shortcutToolbarVisible(AUTO, physicalKeyboardAttached = true, revealed = true, selectionActive = false))
        assertTrue(shortcutToolbarVisible(HIDE, physicalKeyboardAttached = false, revealed = true, selectionActive = false))
    }

    @Test
    fun `selection overrides AUTO-hide and HIDE so copy-paste is never stranded`() {
        assertTrue(shortcutToolbarVisible(AUTO, physicalKeyboardAttached = true, revealed = false, selectionActive = true))
        assertTrue(shortcutToolbarVisible(HIDE, physicalKeyboardAttached = true, revealed = false, selectionActive = true))
    }

    @Test
    fun `hidden states stay hidden without reveal or selection`() {
        assertFalse(shortcutToolbarVisible(AUTO, physicalKeyboardAttached = true, revealed = false, selectionActive = false))
        assertFalse(shortcutToolbarVisible(HIDE, physicalKeyboardAttached = false, revealed = false, selectionActive = false))
        assertFalse(shortcutToolbarVisible(HIDE, physicalKeyboardAttached = true, revealed = false, selectionActive = false))
    }
}
