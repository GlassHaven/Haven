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
 * #675 — the soft keyboard auto-shows when a terminal tab becomes active. The
 * new toggle lets that be turned off for read-only sessions; it defaults to on
 * so existing behaviour is unchanged until the user opts out, and the boolean
 * round-trips for the Settings row.
 */
@RunWith(RobolectricTestRunner::class)
class AutoShowKeyboardPrefTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun repo(): UserPreferencesRepository {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
        }
        return UserPreferencesRepository(org.robolectric.RuntimeEnvironment.getApplication(), ds)
    }

    @Test
    fun defaultIsOn() = runBlocking {
        assertEquals(
            "auto-show must stay on by default so existing behaviour is unchanged (#675)",
            true,
            repo().autoShowKeyboardInTerminal.first(),
        )
    }

    @Test
    fun roundTripsBothWays() = runBlocking {
        val repo = repo()
        for (value in listOf(false, true)) {
            repo.setAutoShowKeyboardInTerminal(value)
            assertEquals(value, repo.autoShowKeyboardInTerminal.first())
        }
    }
}
