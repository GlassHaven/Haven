package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #navbar-visibility — DataStore-level behaviour of the per-tab visibility map
 * and the read-time migration from the retired "always show all tabs" master
 * toggle.
 */
@RunWith(RobolectricTestRunner::class)
class TabVisibilityPrefTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun repo(): UserPreferencesRepository {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
        }
        return UserPreferencesRepository(app(), ds)
    }

    /** A repository seeded with the retired master toggle (legacy installs). */
    private fun legacyRepo(): UserPreferencesRepository {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("legacy_${System.nanoTime()}.preferences_pb")
        }
        runBlocking {
            ds.edit { prefs -> prefs[booleanPreferencesKey("always_show_all_tabs")] = true }
        }
        return UserPreferencesRepository(app(), ds)
    }

    @Test
    fun defaultsToAutoForEveryTabOnAFreshInstall() = runBlocking {
        // No per-tab preference and no legacy master toggle → empty map (all AUTO).
        assertTrue(repo().tabVisibility.first().isEmpty())
    }

    @Test
    fun setNavigationTabsPersistsOrderAndVisibilityTogether() = runBlocking {
        val repo = repo()
        repo.setNavigationTabs(
            order = listOf("settings", "terminal", "connections"),
            visibility = mapOf(
                "terminal" to TabVisibility.HIDE,
                "mail" to TabVisibility.SHOW,
                // AUTO is not stored — it restores the built-in usage rule.
                "desktop" to TabVisibility.AUTO,
            )
        )
        assertEquals(listOf("settings", "terminal", "connections"), repo.screenOrder.first())
        val map = repo.tabVisibility.first()
        assertEquals(TabVisibility.HIDE, map["terminal"])
        assertEquals(TabVisibility.SHOW, map["mail"])
        assertEquals(null, map["desktop"])
        assertEquals(2, map.size)
    }

    @Test
    fun setNavigationTabsReplacesTheWholeVisibilityMap() = runBlocking {
        val repo = repo()
        repo.setNavigationTabs(
            emptyList(),
            mapOf("terminal" to TabVisibility.SHOW, "mail" to TabVisibility.HIDE)
        )

        // Save a new draft: terminal now hidden, mail now shown, desktop back to
        // AUTO. The whole map is replaced, not merged.
        repo.setNavigationTabs(
            emptyList(),
            mapOf(
                "terminal" to TabVisibility.HIDE,
                "mail" to TabVisibility.SHOW,
                "desktop" to TabVisibility.AUTO,
            )
        )
        val map = repo.tabVisibility.first()
        assertEquals(TabVisibility.HIDE, map["terminal"])
        assertEquals(TabVisibility.SHOW, map["mail"])
        assertEquals(null, map["desktop"])
        assertEquals(2, map.size)
    }

    @Test
    fun savingEveryTabAsAutoClearsTheMapButKeepsTheLegacyFallbackOff() = runBlocking {
        val repo = legacyRepo()
        // Legacy fallback pins the five dependent tabs to SHOW.
        assertEquals(5, repo.tabVisibility.first().size)

        // The user opens the dialog and saves everything on AUTO. The map is
        // written as present-but-empty, so the legacy fallback never applies
        // again (it only fires while the key itself is absent).
        repo.setNavigationTabs(emptyList(), emptyMap())
        assertTrue(repo.tabVisibility.first().isEmpty())
    }

    /**
     * Migration: a user who had the old master "always show all tabs" toggle ON
     * and has not yet chosen per-tab settings gets SHOW for every non-always-
     * visible tab (the tabs the old toggle used to force on). Connections and
     * Settings are always visible, so they have nothing to migrate.
     */
    @Test
    fun legacyMasterToggleOnFallsBackToShowForDependentTabs() = runBlocking {
        val map = legacyRepo().tabVisibility.first()
        // Every tab that isn't always-visible is pinned to SHOW.
        listOf("terminal", "desktop", "keys", "sftp", "mail").forEach { route ->
            assertEquals(TabVisibility.SHOW, map[route])
        }
        // Always-visible tabs are not stored.
        assertEquals(null, map["connections"])
        assertEquals(null, map["settings"])
    }

    /**
     * Migration is a read-time fallback: the moment the user saves any per-tab
     * choice, the map is written and the legacy key no longer influences it.
     */
    @Test
    fun savingAnyPerTabChoiceDisablesTheLegacyFallback() = runBlocking {
        val repo = legacyRepo()
        assertEquals(5, repo.tabVisibility.first().size)

        // Hiding Terminal is an explicit per-tab choice — only it remains.
        repo.setNavigationTabs(emptyList(), mapOf("terminal" to TabVisibility.HIDE))
        val map = repo.tabVisibility.first()
        assertEquals(TabVisibility.HIDE, map["terminal"])
        assertEquals(1, map.size)
    }
}
