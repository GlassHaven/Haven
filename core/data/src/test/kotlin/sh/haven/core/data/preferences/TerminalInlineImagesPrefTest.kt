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
 * #583 — inline-image consent policy defaults to ASK, and the stored enum
 * name round-trips for the Settings chips row and the MCP set_preference verb.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalInlineImagesPrefTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun repo(): UserPreferencesRepository {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
        }
        return UserPreferencesRepository(org.robolectric.RuntimeEnvironment.getApplication(), ds)
    }

    @Test
    fun defaultIsAsk() = runBlocking {
        assertEquals(
            "a program-sent image must not render without consent by default (#583)",
            UserPreferencesRepository.TerminalInlineImages.ASK,
            repo().terminalInlineImages.first(),
        )
    }

    @Test
    fun roundTripsAllModes() = runBlocking {
        val repo = repo()
        for (mode in UserPreferencesRepository.TerminalInlineImages.entries) {
            repo.setTerminalInlineImages(mode)
            assertEquals(mode, repo.terminalInlineImages.first())
        }
    }
}