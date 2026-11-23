package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #870 — the shortcut-toolbar visibility policy defaults to SHOW (the
 * pre-#870 behaviour: the extra key rows are always composed), and the
 * stored enum name round-trips for the Settings chips row and the MCP
 * set_preference verb. An unknown stored value falls back to SHOW rather
 * than hiding the toolbar out from under the user.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalShortcutToolbarPrefTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun repo(): UserPreferencesRepository {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
        }
        return UserPreferencesRepository(org.robolectric.RuntimeEnvironment.getApplication(), ds)
    }

    @Test
    fun defaultIsShow() = runBlocking {
        assertEquals(
            "existing users must keep the toolbar until they opt in (#870)",
            UserPreferencesRepository.TerminalShortcutToolbar.SHOW,
            repo().terminalShortcutToolbar.first(),
        )
    }

    @Test
    fun roundTripsAllModes() = runBlocking {
        val repo = repo()
        for (mode in UserPreferencesRepository.TerminalShortcutToolbar.entries) {
            repo.setTerminalShortcutToolbar(mode)
            assertEquals(mode, repo.terminalShortcutToolbar.first())
        }
    }

    @Test
    fun unknownStoredValueFallsBackToShow() {
        assertEquals(
            UserPreferencesRepository.TerminalShortcutToolbar.SHOW,
            UserPreferencesRepository.TerminalShortcutToolbar.fromString("BOGUS"),
        )
        assertEquals(
            UserPreferencesRepository.TerminalShortcutToolbar.SHOW,
            UserPreferencesRepository.TerminalShortcutToolbar.fromString(null),
        )
    }
}
