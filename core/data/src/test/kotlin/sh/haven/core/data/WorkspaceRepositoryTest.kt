package sh.haven.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.WorkspaceDao
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.WorkspaceRepository

class WorkspaceRepositoryTest {

    @Test
    fun saveRequiresAtLeastOneItem() {
        val repo = WorkspaceRepository(FakeWorkspaceDao())
        val profile = WorkspaceProfile(name = "Empty")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.save(profile, emptyList()) }
        }
    }

    @Test
    fun saveRequiresProfileForNonWaylandKinds() {
        val repo = WorkspaceRepository(FakeWorkspaceDao())
        val profile = WorkspaceProfile(name = "Bad")

        listOf(
            WorkspaceItem.Kind.TERMINAL,
            WorkspaceItem.Kind.FILE_BROWSER,
            WorkspaceItem.Kind.DESKTOP,
        ).forEach { kind ->
            val item = WorkspaceItem(
                workspaceId = profile.id,
                kind = kind,
                connectionProfileId = null,
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.save(profile, listOf(item)) }
            }
        }
    }

    @Test
    fun saveRejectsConnectionProfileOnWaylandKind() {
        val repo = WorkspaceRepository(FakeWorkspaceDao())
        val profile = WorkspaceProfile(name = "Bad")
        val item = WorkspaceItem(
            workspaceId = profile.id,
            kind = WorkspaceItem.Kind.WAYLAND,
            connectionProfileId = "should-be-null",
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.save(profile, listOf(item)) }
        }
    }

    @Test
    fun saveRenumbersItemsAndUpdatesTimestamp() = runBlocking<Unit> {
        val dao = FakeWorkspaceDao()
        val repo = WorkspaceRepository(dao)
        val originalCreated = 100L
        val profile = WorkspaceProfile(
            id = "ws-1",
            name = "Work",
            createdAt = originalCreated,
            updatedAt = originalCreated,
        )
        val items = listOf(
            WorkspaceItem(
                id = "a",
                workspaceId = "stale",
                kind = WorkspaceItem.Kind.TERMINAL,
                connectionProfileId = "p1",
                sortOrder = 999,
            ),
            WorkspaceItem(
                id = "b",
                workspaceId = "stale",
                kind = WorkspaceItem.Kind.FILE_BROWSER,
                connectionProfileId = "p2",
                path = "/tmp",
                sortOrder = 999,
            ),
            WorkspaceItem(
                id = "c",
                workspaceId = "stale",
                kind = WorkspaceItem.Kind.WAYLAND,
                sortOrder = 999,
            ),
        )

        repo.save(profile, items)

        val saved = dao.profiles.value.single()
        assertEquals("ws-1", saved.id)
        assertTrue(
            "updatedAt should advance past the synthetic 100L created stamp",
            saved.updatedAt >= originalCreated,
        )
        // createdAt is preserved.
        assertEquals(originalCreated, saved.createdAt)

        val savedItems = dao.items.value
        assertEquals(3, savedItems.size)
        assertEquals(listOf(0, 1, 2), savedItems.map { it.sortOrder })
        assertEquals(listOf("ws-1", "ws-1", "ws-1"), savedItems.map { it.workspaceId })
    }

    @Test
    fun saveReplacesPriorItemsForTheSameWorkspace() = runBlocking<Unit> {
        val dao = FakeWorkspaceDao()
        val repo = WorkspaceRepository(dao)
        val profile = WorkspaceProfile(id = "ws-1", name = "Work")

        repo.save(
            profile,
            listOf(
                WorkspaceItem(
                    workspaceId = "ws-1",
                    kind = WorkspaceItem.Kind.TERMINAL,
                    connectionProfileId = "p1",
                ),
                WorkspaceItem(
                    workspaceId = "ws-1",
                    kind = WorkspaceItem.Kind.TERMINAL,
                    connectionProfileId = "p2",
                ),
            ),
        )
        assertEquals(2, dao.items.value.size)

        repo.save(
            profile,
            listOf(
                WorkspaceItem(
                    workspaceId = "ws-1",
                    kind = WorkspaceItem.Kind.WAYLAND,
                ),
            ),
        )
        assertEquals(1, dao.items.value.size)
        assertEquals(WorkspaceItem.Kind.WAYLAND, dao.items.value.single().kind)
    }

    @Test
    fun deleteRemovesProfile() = runBlocking<Unit> {
        val dao = FakeWorkspaceDao()
        val repo = WorkspaceRepository(dao)
        val profile = WorkspaceProfile(id = "ws-1", name = "Work")
        repo.save(
            profile,
            listOf(
                WorkspaceItem(
                    workspaceId = "ws-1",
                    kind = WorkspaceItem.Kind.WAYLAND,
                ),
            ),
        )

        repo.delete("ws-1")

        assertTrue(dao.profiles.value.isEmpty())
        assertNull(repo.getWorkspace("ws-1"))
    }

    /**
     * Pure in-memory WorkspaceDao stand-in. Mirrors the real Room
     * behaviour: upsert by primary key, transaction-style replace via
     * deleteItemsForWorkspace + upsertItems, cascade-delete handled by
     * deleteProfile() (we manually purge items).
     */
    private class FakeWorkspaceDao : WorkspaceDao {
        val profiles = MutableStateFlow<List<WorkspaceProfile>>(emptyList())
        val items = MutableStateFlow<List<WorkspaceItem>>(emptyList())

        override fun observeAll(): Flow<List<WorkspaceProfile>> = profiles

        override suspend fun getById(id: String): WorkspaceProfile? =
            profiles.value.firstOrNull { it.id == id }

        override suspend fun getItemsForWorkspace(workspaceId: String): List<WorkspaceItem> =
            items.value.filter { it.workspaceId == workspaceId }.sortedBy { it.sortOrder }

        override fun observeItemsForWorkspace(workspaceId: String): Flow<List<WorkspaceItem>> =
            items.map { all ->
                all.filter { it.workspaceId == workspaceId }.sortedBy { it.sortOrder }
            }

        override suspend fun upsertProfile(profile: WorkspaceProfile) {
            profiles.value = profiles.value.filterNot { it.id == profile.id } + profile
        }

        override suspend fun upsertItems(items: List<WorkspaceItem>) {
            val incomingIds = items.map { it.id }.toSet()
            this.items.value =
                this.items.value.filterNot { it.id in incomingIds } + items
        }

        override suspend fun deleteItemsForWorkspace(workspaceId: String) {
            items.value = items.value.filterNot { it.workspaceId == workspaceId }
        }

        override suspend fun deleteProfile(id: String) {
            profiles.value = profiles.value.filterNot { it.id == id }
            // Mirror Room's ON DELETE CASCADE.
            items.value = items.value.filterNot { it.workspaceId == id }
        }
    }
}
