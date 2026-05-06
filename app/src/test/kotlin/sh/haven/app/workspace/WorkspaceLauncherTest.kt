package sh.haven.app.workspace

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.data.repository.WorkspaceWithItems

class WorkspaceLauncherTest {

    @Test
    fun sortPutsTerminalsFirstThenWaylandFileBrowserDesktop() {
        val ws = "ws-1"
        val items = listOf(
            item(ws, "d", WorkspaceItem.Kind.DESKTOP, "vnc-prof"),
            item(ws, "f", WorkspaceItem.Kind.FILE_BROWSER, "ssh-prof"),
            item(ws, "w", WorkspaceItem.Kind.WAYLAND, null),
            item(ws, "t", WorkspaceItem.Kind.TERMINAL, "ssh-prof"),
        )

        val sorted = WorkspaceLauncher.sortByKindPriority(items)

        assertEquals(
            listOf("t", "w", "f", "d"),
            sorted.map { it.id },
        )
    }

    @Test
    fun sortIsStableWithinAKindUsingSortOrderThenId() {
        val ws = "ws-1"
        val a = item(ws, "a", WorkspaceItem.Kind.TERMINAL, "p1", sortOrder = 1)
        val b = item(ws, "b", WorkspaceItem.Kind.TERMINAL, "p2", sortOrder = 0)
        val c = item(ws, "c", WorkspaceItem.Kind.TERMINAL, "p3", sortOrder = 0)

        val sorted = WorkspaceLauncher.sortByKindPriority(listOf(a, c, b))

        // sortOrder 0 comes before 1; tie at 0 broken by id (b < c).
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun launchUnknownWorkspaceReportsFailedState() = runBlocking {
        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("missing") } returns null
        val launcher = WorkspaceLauncher(
            workspaceRepository = workspaceRepo,
            connectionRepository = mockk(),
            agentUiCommandBus = mockk(),
        )

        launcher.launch("missing")

        val state = launcher.state.value
        assertTrue("expected Failed, got $state", state is WorkspaceLaunchState.Failed)
        assertEquals(
            "workspace not found",
            (state as WorkspaceLaunchState.Failed).reason,
        )
    }

    @Test
    fun launchEmitsKindAppropriateCommandsInPriorityOrder() = runBlocking {
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val vncProfile = profile("vnc-1", connectionType = "VNC")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "vnc-item", WorkspaceItem.Kind.DESKTOP, "vnc-1"),
            item("ws-1", "wayland-item", WorkspaceItem.Kind.WAYLAND, null),
            item("ws-1", "ssh-item", WorkspaceItem.Kind.TERMINAL, "ssh-1"),
            item("ws-1", "sftp-item", WorkspaceItem.Kind.FILE_BROWSER, "ssh-1", path = "/srv"),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)

        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        coEvery { connRepo.getById("vnc-1") } returns vncProfile

        val bus = mockk<AgentUiCommandBus>()
        val emitted = mutableListOf<AgentUiCommand>()
        every { bus.emit(any()) } answers {
            emitted += firstArg<AgentUiCommand>()
            true
        }

        val launcher = WorkspaceLauncher(workspaceRepo, connRepo, bus)
        launcher.launch("ws-1")

        val state = launcher.state.value
        assertTrue("expected Completed, got $state", state is WorkspaceLaunchState.Completed)
        // Priority order: TERMINAL → WAYLAND → FILE_BROWSER → DESKTOP.
        assertEquals(4, emitted.size)
        assertTrue("first should be OpenTerminalSession",
            emitted[0] is AgentUiCommand.OpenTerminalSession)
        assertTrue("second should be OpenWaylandDesktop",
            emitted[1] is AgentUiCommand.OpenWaylandDesktop)
        assertTrue("third should be NavigateToSftpPath",
            emitted[2] is AgentUiCommand.NavigateToSftpPath)
        assertTrue("fourth should be OpenRemoteDesktop",
            emitted[3] is AgentUiCommand.OpenRemoteDesktop)
        // SFTP item carries the path through.
        assertEquals(
            "/srv",
            (emitted[2] as AgentUiCommand.NavigateToSftpPath).path,
        )
    }

    @Test
    fun launchMarksItemFailedWhenProfileIsMissing() = runBlocking {
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term-item", WorkspaceItem.Kind.TERMINAL, "missing-profile"),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("missing-profile") } returns null
        val bus = mockk<AgentUiCommandBus>(relaxed = true)
        every { bus.emit(any()) } returns true

        val launcher = WorkspaceLauncher(workspaceRepo, connRepo, bus)
        launcher.launch("ws-1")

        val state = launcher.state.value
        assertTrue("expected Completed (with failure inside)", state is WorkspaceLaunchState.Completed)
        val itemProgress = (state as WorkspaceLaunchState.Completed).items.single()
        assertEquals(ItemProgress.Status.Failed, itemProgress.status)
        assertEquals("profile missing", itemProgress.message)
    }

    @Test
    fun launchMarksItemFailedWhenProfileKindMismatches() = runBlocking {
        // SSH profile referenced as a DESKTOP — wrong kind.
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "bad", WorkspaceItem.Kind.DESKTOP, "ssh-1"),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val bus = mockk<AgentUiCommandBus>()
        every { bus.emit(any()) } returns true

        val launcher = WorkspaceLauncher(workspaceRepo, connRepo, bus)
        launcher.launch("ws-1")

        val itemProgress = (launcher.state.value as WorkspaceLaunchState.Completed).items.single()
        assertEquals(ItemProgress.Status.Failed, itemProgress.status)
        assertNotNull(itemProgress.message)
        assertTrue(itemProgress.message!!.contains("not a desktop"))
    }

    @Test
    fun cancelMarksRemainingItemsSkipped() = runBlocking {
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val vncProfile = profile("vnc-1", connectionType = "VNC")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(
            item("ws-1", "term", WorkspaceItem.Kind.TERMINAL, "ssh-1"),
            item("ws-1", "vnc", WorkspaceItem.Kind.DESKTOP, "vnc-1"),
        )

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        coEvery { connRepo.getById("vnc-1") } returns vncProfile
        val bus = mockk<AgentUiCommandBus>()
        // Forward reference: assigned right after construction, used by
        // the lambda below at call time (not at every {} setup time).
        lateinit var launcher: WorkspaceLauncher
        every { bus.emit(any()) } answers {
            // Cancel after the first emit so the second item is reached
            // with cancelRequested already set.
            launcher.cancel()
            true
        }
        launcher = WorkspaceLauncher(workspaceRepo, connRepo, bus)

        launcher.launch("ws-1")

        val state = launcher.state.value
        assertTrue("expected Cancelled, got $state", state is WorkspaceLaunchState.Cancelled)
        val items2 = (state as WorkspaceLaunchState.Cancelled).items
        assertEquals(ItemProgress.Status.Succeeded, items2[0].status) // term ran
        assertEquals(ItemProgress.Status.Skipped, items2[1].status)   // vnc skipped
        assertEquals("cancelled", items2[1].message)
    }

    @Test
    fun launchPropagatesBusOverflowAsFailure() = runBlocking {
        val sshProfile = profile("ssh-1", connectionType = "SSH")
        val ws = WorkspaceProfile(id = "ws-1", name = "Work")
        val items = listOf(item("ws-1", "term", WorkspaceItem.Kind.TERMINAL, "ssh-1"))

        val workspaceRepo = mockk<WorkspaceRepository>()
        coEvery { workspaceRepo.getWorkspace("ws-1") } returns
            WorkspaceWithItems(ws, items)
        val connRepo = mockk<ConnectionRepository>()
        coEvery { connRepo.getById("ssh-1") } returns sshProfile
        val bus = mockk<AgentUiCommandBus>()
        every { bus.emit(any()) } returns false  // overflow

        val launcher = WorkspaceLauncher(workspaceRepo, connRepo, bus)
        launcher.launch("ws-1")

        val itemProgress = (launcher.state.value as WorkspaceLaunchState.Completed).items.single()
        assertEquals(ItemProgress.Status.Failed, itemProgress.status)
        assertEquals("ui bus overflow", itemProgress.message)
    }

    private fun item(
        workspaceId: String,
        id: String,
        kind: WorkspaceItem.Kind,
        connectionProfileId: String?,
        path: String? = null,
        sortOrder: Int = 0,
    ) = WorkspaceItem(
        id = id,
        workspaceId = workspaceId,
        kind = kind,
        connectionProfileId = connectionProfileId,
        path = path,
        sortOrder = sortOrder,
    )

    private fun profile(
        id: String,
        connectionType: String,
    ) = ConnectionProfile(
        id = id,
        label = "$id label",
        host = "host.example",
        port = 22,
        username = "user",
        connectionType = connectionType,
    )
}
