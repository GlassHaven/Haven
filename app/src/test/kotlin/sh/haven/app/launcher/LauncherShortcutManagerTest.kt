package sh.haven.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.WorkspaceProfile

class LauncherShortcutManagerTest {

    private fun conn(id: String, label: String = id, lastConnected: Long? = null, type: String = "SSH") =
        ConnectionProfile(id = id, label = label, host = "$id.example.com", username = "u", connectionType = type, lastConnected = lastConnected)

    private fun ws(id: String, updatedAt: Long) =
        WorkspaceProfile(id = id, name = id, updatedAt = updatedAt)

    @Test
    fun mostRecentlyConnectedFirstAndNeverConnectedAfter() {
        val shortcuts = LauncherShortcutManager.selectShortcuts(
            connections = listOf(
                conn("old", lastConnected = 100),
                conn("new", lastConnected = 300),
                conn("mid", lastConnected = 200),
                conn("never"),
            ),
            workspaces = emptyList(),
        )
        assertEquals(listOf("new", "mid", "old", "never"),
            shortcuts.map { (it as ConnectionShortcut).profileId })
    }

    @Test
    fun interleavesConnectionsAndWorkspaces() {
        val shortcuts = LauncherShortcutManager.selectShortcuts(
            connections = listOf(conn("c1", lastConnected = 1), conn("c2", lastConnected = 2)),
            workspaces = listOf(ws("w1", 10), ws("w2", 20)), // w2 is MRU
        )
        assertEquals(listOf("c2", "w2", "c1", "w1"), shortcuts.map { it.shortcutId.toSpecId() })
    }

    @Test
    fun cappedAtFiveWithInterleavedOrder() {
        val conns = (1..8).map { conn("c$it", lastConnected = it.toLong() * 100) }
        val wss = (1..8).map { ws("w$it", it.toLong() * 100) }
        val ids = LauncherShortcutManager.selectShortcuts(conns, wss).map { it.shortcutId.toSpecId() }
        // c4 is the most-recent-... c8 desc: c8..c1; w8..w1; interleave then cap 5.
        assertEquals(listOf("c8", "w8", "c7", "w7", "c6"), ids)
    }

    @Test
    fun singleConnectionKeepsWorkspaceSlotsFilled() {
        val wss = (1..8).map { ws("w$it", it.toLong() * 100) }
        val ids = LauncherShortcutManager.selectShortcuts(listOf(conn("only", lastConnected = 5)), wss)
            .map { it.shortcutId.toSpecId() }
        // The lone connection leads; remaining workspaces fill the freed slots.
        assertEquals(listOf("only", "w8", "w7", "w6", "w5"), ids)
    }

    @Test
    fun noConnectionsReducesToPreviousWorkspaceBehaviour() {
        val wss = (1..8).map { ws("w$it", it.toLong() * 100) }
        val ids = LauncherShortcutManager.selectShortcuts(emptyList(), wss).map { it.shortcutId.toSpecId() }
        assertEquals(listOf("w8", "w7", "w6", "w5", "w4"), ids)
    }

    @Test
    fun excludesOpenaiAndEmailProfiles() {
        val conns = listOf(
            conn("ssh", lastConnected = 3),
            conn("box", lastConnected = 2, type = "OPENAI"),
            conn("mail", lastConnected = 1, type = "EMAIL"),
        )
        val ids = LauncherShortcutManager.selectShortcuts(conns, emptyList()).map { it.shortcutId.toSpecId() }
        assertEquals(listOf("ssh"), ids)
    }

    @Test
    fun idsAreNamespacedForPinnedShortcutReResolution() {
        val shortcuts = LauncherShortcutManager.selectShortcuts(
            listOf(conn("p1", lastConnected = 1)), listOf(ws("w1", 1)))
        assertTrue(shortcuts.all { it.shortcutId.startsWith("connection-") || it.shortcutId.startsWith("workspace-") })
        assertEquals(setOf("connection-p1", "workspace-w1"), shortcuts.map { it.shortcutId }.toSet())
    }

    @Test
    fun labelsCarryTheDisplayName() {
        val shortcuts = LauncherShortcutManager.selectShortcuts(
            listOf(conn("p1", label = "web box", lastConnected = 1)), listOf(ws("w1", 1)))
        val labels = shortcuts.associate { it.shortcutId.toSpecId() to it.label }
        assertEquals("web box", labels["p1"])
        assertEquals("w1", labels["w1"])
    }

    @Test
    fun maxDynamicShortcutsSubtractsManifestShortcutsFromPlatformCap() {
        // Android 8–9 (Pie) ShortcutService sets mMaxShortcuts = 5; with 1
        // static manifest shortcut (`pull_backup_now`), only 4 dynamic slots
        // remain (#775).
        assertEquals(4, LauncherShortcutManager.maxDynamicShortcuts(maxShortcutCountPerActivity = 5, manifestShortcutCount = 1))
        // OEM launchers with maxShortcutCountPerActivity = 4 leave 3 dynamic slots.
        assertEquals(3, LauncherShortcutManager.maxDynamicShortcuts(maxShortcutCountPerActivity = 4, manifestShortcutCount = 1))
        // Android 10+ sets mMaxShortcuts = 15; still capped at MAX_SHORTCUTS (5).
        assertEquals(5, LauncherShortcutManager.maxDynamicShortcuts(maxShortcutCountPerActivity = 15, manifestShortcutCount = 1))
        // Degenerate / zero cap never goes negative.
        assertEquals(0, LauncherShortcutManager.maxDynamicShortcuts(maxShortcutCountPerActivity = 1, manifestShortcutCount = 1))
        assertEquals(0, LauncherShortcutManager.maxDynamicShortcuts(maxShortcutCountPerActivity = 0, manifestShortcutCount = 1))
    }

    @Test
    fun selectShortcutsHonorsReducedMaxShortcutsBudget() {
        val conns = (1..6).map { conn("c$it", lastConnected = it.toLong() * 100) }
        val wss = (1..6).map { ws("w$it", it.toLong() * 100) }
        val budget = LauncherShortcutManager.maxDynamicShortcuts(maxShortcutCountPerActivity = 5, manifestShortcutCount = 1)
        val ids = LauncherShortcutManager.selectShortcuts(conns, wss, maxShortcuts = budget)
            .map { it.shortcutId.toSpecId() }
        assertEquals(listOf("c6", "w6", "c5", "w5"), ids)
        assertTrue(LauncherShortcutManager.selectShortcuts(conns, wss, maxShortcuts = 0).isEmpty())
    }

    /** "connection-p1" → "p1" (the first segment is the namespace prefix). */
    private fun String.toSpecId(): String = substringAfter('-')
}