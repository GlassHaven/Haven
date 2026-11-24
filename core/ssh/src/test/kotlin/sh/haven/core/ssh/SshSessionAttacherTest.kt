package sh.haven.core.ssh

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository

class SshSessionAttacherTest {

    private val profile = ConnectionProfile(
        id = "p1",
        label = "host-1",
        host = "192.0.2.1",
        username = "ian",
    )

    private fun sessionState(
        sessionId: String,
        status: SshSessionManager.SessionState.Status = SshSessionManager.SessionState.Status.CONNECTED,
        chosenSessionName: String? = null,
        manager: SessionManager = SessionManager.TMUX,
    ) = SshSessionManager.SessionState(
        sessionId = sessionId,
        profileId = "p1",
        label = "host-1",
        status = status,
        client = mockk(),
        sessionManager = manager,
        chosenSessionName = chosenSessionName,
    )

    private fun repos(): Pair<ConnectionRepository, UserPreferencesRepository> {
        val connectionRepository = mockk<ConnectionRepository>()
        coEvery { connectionRepository.getById("p1") } returns profile
        coEvery { connectionRepository.save(any()) } just Runs
        val prefs = mockk<UserPreferencesRepository>()
        every { prefs.sessionManager } returns flowOf(UserPreferencesRepository.SessionManager.TMUX)
        return connectionRepository to prefs
    }

    @Test
    fun missingProfileFails() = runBlocking {
        val (repo, prefs) = repos()
        coEvery { repo.getById("nope") } returns null
        val attacher = SshSessionAttacher(mockk(), repo, prefs)

        val result = attacher.ensureAttached("nope", "main")

        assertTrue(result is SshSessionAttacher.Result.Failed)
    }

    @Test
    fun liveTerminalOnSameNameReturnsAlreadyLiveWithoutRegistering() = runBlocking {
        val sm = mockk<SshSessionManager>()
        every { sm.getSessionsForProfile("p1") } returns listOf(
            sessionState("s-live", chosenSessionName = "main"),
        )
        every { sm.isLiveTerminal("s-live") } returns true
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val result = attacher.ensureAttached("p1", "main")

        assertEquals(SshSessionAttacher.Result.AlreadyLive("s-live"), result)
        coVerify(exactly = 0) { sm.awaitReusableClient(any(), any()) }
    }

    @Test
    fun sessionNameMatchIsSanitized() = runBlocking {
        val sm = mockk<SshSessionManager>()
        every { sm.getSessionsForProfile("p1") } returns listOf(
            sessionState("s-live", chosenSessionName = "my session"),
        )
        every { sm.isLiveTerminal("s-live") } returns true
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val result = attacher.ensureAttached("p1", "my.session")

        assertEquals(SshSessionAttacher.Result.AlreadyLive("s-live"), result)
    }

    @Test
    fun deadSessionWithSameNameIsNotReused() = runBlocking {
        val sm = mockk<SshSessionManager>()
        every { sm.getSessionsForProfile("p1") } returns listOf(
            sessionState("s-dead", chosenSessionName = "main"),
        )
        every { sm.isLiveTerminal("s-dead") } returns false
        coEvery { sm.awaitReusableClient("p1", any()) } returns null
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val result = attacher.ensureAttached("p1", "main")

        assertEquals(SshSessionAttacher.Result.NoLiveConnection, result)
    }

    @Test
    fun noReusableClientReturnsNoLiveConnection() = runBlocking {
        val sm = mockk<SshSessionManager>()
        every { sm.getSessionsForProfile("p1") } returns emptyList()
        coEvery { sm.awaitReusableClient("p1", any()) } returns null
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val result = attacher.ensureAttached("p1", "main")

        assertEquals(SshSessionAttacher.Result.NoLiveConnection, result)
    }

    @Test
    fun attachHappyPathRegistersNamesShellAndPersists() = runBlocking {
        val sm = mockk<SshSessionManager>(relaxed = true)
        val attached = sessionState("s-new", chosenSessionName = "main")
        every { sm.getSessionsForProfile("p1") } returnsMany listOf(
            emptyList(), // idempotency check
            listOf(attached, sessionState("s-old", chosenSessionName = "other")), // persist
        )
        coEvery { sm.awaitReusableClient("p1", any()) } returns mockk<SshClient>()
        every { sm.registerSession("p1", "host-1", any()) } returns "s-new"
        coEvery { sm.openShellAndAwaitReady("s-new", any(), any()) } returns ShellOutcome.Ready("s-new")
        val (repo, prefs) = repos()
        val saved = slot<ConnectionProfile>()
        coEvery { repo.save(capture(saved)) } just Runs
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val result = attacher.ensureAttached("p1", "main")

        assertEquals(SshSessionAttacher.Result.Attached("s-new"), result)
        coVerify { sm.storeReuseConfig("s-new", "p1", SessionManager.TMUX) }
        coVerify { sm.setChosenSessionName("s-new", "main") }
        coVerify { sm.updateStatus("s-new", SshSessionManager.SessionState.Status.CONNECTED) }
        assertEquals("main|other", saved.captured.lastSessionName)
    }

    @Test
    fun nullSessionNameAttachesWithoutNaming() = runBlocking {
        val sm = mockk<SshSessionManager>(relaxed = true)
        every { sm.getSessionsForProfile("p1") } returns emptyList()
        coEvery { sm.awaitReusableClient("p1", any()) } returns mockk<SshClient>()
        every { sm.registerSession("p1", "host-1", any()) } returns "s-new"
        coEvery { sm.openShellAndAwaitReady("s-new", any(), any()) } returns ShellOutcome.Ready("s-new")
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val result = attacher.ensureAttached("p1", null)

        assertEquals(SshSessionAttacher.Result.Attached("s-new"), result)
        coVerify(exactly = 0) { sm.setChosenSessionName(any(), any()) }
    }

    @Test
    fun shellClosedRemovesSessionAndFails() = runBlocking {
        val sm = mockk<SshSessionManager>(relaxed = true)
        every { sm.getSessionsForProfile("p1") } returns emptyList()
        coEvery { sm.awaitReusableClient("p1", any()) } returns mockk<SshClient>()
        every { sm.registerSession("p1", "host-1", any()) } returns "s-new"
        coEvery { sm.openShellAndAwaitReady("s-new", any(), any()) } returns
            ShellOutcome.ShellClosed("s-new", exitStatus = 0)
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val result = attacher.ensureAttached("p1", "main")

        assertTrue(result is SshSessionAttacher.Result.Failed)
        coVerify { sm.removeSession("s-new") }
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun listRemoteSessionsParsesTheHostListCommand() = runBlocking {
        val client = mockk<SshClient>()
        coEvery { client.execCommand(any()) } returns
            ExecResult(exitStatus = 0, stdout = "cctv\nciv ic\n", stderr = "")
        val sm = mockk<SshSessionManager>()
        every { sm.getSshClientForProfile("p1") } returns client
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        assertEquals(listOf("cctv", "civ ic"), attacher.listRemoteSessions("p1"))
    }

    @Test
    fun listRemoteSessionsNullWhenNoLiveClient() = runBlocking {
        val sm = mockk<SshSessionManager>()
        every { sm.getSshClientForProfile("p1") } returns null
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        assertEquals(null, attacher.listRemoteSessions("p1"))
    }

    @Test
    fun listRemoteSessionsNullWithoutASessionManager() = runBlocking {
        val (repo, _) = repos()
        val prefs = mockk<UserPreferencesRepository>()
        every { prefs.sessionManager } returns flowOf(UserPreferencesRepository.SessionManager.NONE)
        // NONE has no list command — the client must never be consulted.
        val attacher = SshSessionAttacher(mockk(), repo, prefs)

        assertEquals(null, attacher.listRemoteSessions("p1"))
    }

    @Test
    fun listRemoteSessionsNullOnListCommandFailure() = runBlocking {
        val client = mockk<SshClient>()
        coEvery { client.execCommand(any()) } returns
            ExecResult(exitStatus = 1, stdout = "", stderr = "no server running")
        val sm = mockk<SshSessionManager>()
        every { sm.getSshClientForProfile("p1") } returns client
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        assertEquals(null, attacher.listRemoteSessions("p1"))
    }

    @Test
    fun shellFailureRemovesSessionAndFailsWithReason() = runBlocking {
        val sm = mockk<SshSessionManager>(relaxed = true)
        every { sm.getSessionsForProfile("p1") } returns emptyList()
        coEvery { sm.awaitReusableClient("p1", any()) } returns mockk<SshClient>()
        every { sm.registerSession("p1", "host-1", any()) } returns "s-new"
        coEvery { sm.openShellAndAwaitReady("s-new", any(), any()) } returns
            ShellOutcome.Failed("s-new", "channel dropped")
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val result = attacher.ensureAttached("p1", "main")

        assertEquals(SshSessionAttacher.Result.Failed("channel dropped"), result)
        coVerify { sm.removeSession("s-new") }
    }

    @Test
    fun shellFailureFlagsChannelRejectedOnlyWhenClientIsShared() = runBlocking {
        val sm = mockk<SshSessionManager>(relaxed = true)
        every { sm.getSessionsForProfile("p1") } returns emptyList()
        coEvery { sm.awaitReusableClient("p1", any()) } returns mockk<SshClient>()
        every { sm.registerSession("p1", "host-1", any()) } returnsMany listOf("s-shared", "s-sole")
        coEvery { sm.openShellAndAwaitReady("s-shared", any(), any()) } returns
            ShellOutcome.Failed("s-shared", "no more sessions", channelRejected = true)
        every { sm.isClientShared("s-shared") } returns true
        coEvery { sm.openShellAndAwaitReady("s-sole", any(), any()) } returns
            ShellOutcome.Failed("s-sole", "no more sessions", channelRejected = true)
        every { sm.isClientShared("s-sole") } returns false
        val (repo, prefs) = repos()
        val attacher = SshSessionAttacher(sm, repo, prefs)

        val sharedResult = attacher.ensureAttached("p1", "ten")
        val soleResult = attacher.ensureAttached("p1", "one")

        assertEquals(
            SshSessionAttacher.Result.Failed("no more sessions", channelRejected = true),
            sharedResult,
        )
        assertEquals(
            SshSessionAttacher.Result.Failed("no more sessions", channelRejected = false),
            soleResult,
        )
    }
}
