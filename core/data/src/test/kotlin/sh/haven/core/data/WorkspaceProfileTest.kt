package sh.haven.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile

class WorkspaceProfileTest {

    @Test
    fun profileDefaultsAreFreshlyTimestamped() {
        val before = System.currentTimeMillis()
        val profile = WorkspaceProfile(name = "Work")
        val after = System.currentTimeMillis()

        assertEquals("Work", profile.name)
        assertEquals(0, profile.sortOrder)
        assertTrue(profile.createdAt in before..after)
        assertTrue(profile.updatedAt in before..after)
        // UUID strings are 36 characters with four dashes.
        assertEquals(36, profile.id.length)
    }

    @Test
    fun profileIdsAreUniquePerInstance() {
        val a = WorkspaceProfile(name = "a")
        val b = WorkspaceProfile(name = "a")
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun itemKindEnumValuesAreStable() {
        // These names are persisted via Room's TEXT column for `kind`.
        // Renaming an enum constant orphans existing rows.
        assertEquals("TERMINAL", WorkspaceItem.Kind.TERMINAL.name)
        assertEquals("FILE_BROWSER", WorkspaceItem.Kind.FILE_BROWSER.name)
        assertEquals("DESKTOP", WorkspaceItem.Kind.DESKTOP.name)
        assertEquals("WAYLAND", WorkspaceItem.Kind.WAYLAND.name)
        assertEquals(4, WorkspaceItem.Kind.values().size)
    }

    @Test
    fun itemDefaultsCarryWorkspaceLink() {
        val item = WorkspaceItem(
            workspaceId = "ws-1",
            kind = WorkspaceItem.Kind.TERMINAL,
            connectionProfileId = "profile-1",
        )
        assertEquals("ws-1", item.workspaceId)
        assertEquals(WorkspaceItem.Kind.TERMINAL, item.kind)
        assertEquals("profile-1", item.connectionProfileId)
        assertEquals(null, item.path)
        assertEquals(0, item.sortOrder)
    }
}
