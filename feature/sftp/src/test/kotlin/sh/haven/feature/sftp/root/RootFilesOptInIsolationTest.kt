package sh.haven.feature.sftp.root

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.feature.sftp.SftpViewModel
import sh.haven.feature.sftp.transport.LocalFileBackend
import sh.haven.feature.sftp.transport.TransportSelector
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the opt-in and agent-isolation security boundaries of `Root (/)` (#799):
 * 1. [TransportSelector.resolveFileBackend] never resolves `"root"` (isolating MCP & Chat).
 * 2. [SftpViewModel] hides `Root (/)` by default and never spawns `su` automatically,
 *    even after `rootFilesEnabled` is turned on, until `selectProfile("root")` is called.
 * 3. Turning `rootFilesEnabled` back off shuts down the worker and drops back to `"local"`.
 * 4. Agent UI bus commands targeting `"root"` are ignored even when `rootFilesEnabled` is true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootFilesOptInIsolationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val spawnCount = AtomicInteger(0)
    private lateinit var realClient: RootFsClient

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")!!
        realClient = RootFsClient(
            processFactory = {
                spawnCount.incrementAndGet()
                ProcessBuilder(
                    javaBin,
                    "-cp",
                    classpath,
                    "sh.haven.feature.sftp.root.RootFsDaemon",
                ).start()
            },
            testMarker = true,
        )
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.runBlocking { realClient.shutdown() }
        Dispatchers.resetMain()
    }

    @Test
    fun `TransportSelector resolveFileBackend returns null for root profile id`() = runTest(dispatcher) {
        val repo = mockk<ConnectionRepository>(relaxed = true)
        coEvery { repo.getById("root") } returns null
        val selector = TransportSelector(
            sessionManager = mockk(relaxed = true),
            moshSessionManager = mockk(relaxed = true),
            etSessionManager = mockk(relaxed = true),
            smbSessionManager = mockk(relaxed = true) { every { isProfileConnected(any()) } returns false },
            rcloneSessionManager = mockk(relaxed = true) { every { isProfileConnected(any()) } returns false },
            reticulumSessionManager = mockk(relaxed = true) { every { isProfileConnected(any()) } returns false },
            reticulumTransport = mockk(relaxed = true),
            rcloneClient = mockk(relaxed = true),
            connectionRepository = repo,
            appContext = mockk(relaxed = true),
        )
        assertNull("TransportSelector must never expose RootFileBackend to MCP/Chat", selector.resolveFileBackend("root"))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.awaitUntil(
        timeoutMs: Long = 5_000L,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (predicate()) break
            kotlinx.coroutines.withContext(Dispatchers.IO) { Thread.sleep(15) }
            advanceUntilIdle()
        }
    }

    @Test
    fun `Root tab stays hidden when disabled and never auto-spawns su when enabled`() = runTest(dispatcher) {
        val rootEnabledFlow = MutableStateFlow(false)
        val bus = MutableSharedFlow<AgentUiCommand>()
        val vm = buildViewModel(rootEnabledFlow, bus)

        vm.syncConnectedProfiles()
        advanceUntilIdle()
        awaitUntil { vm.activeProfileId.value == "local" }
        advanceUntilIdle()

        assertEquals(listOf("local"), vm.connectedProfiles.value.map { it.id })
        assertEquals("local", vm.activeProfileId.value)
        assertEquals(0, spawnCount.get())

        // Enabling the toggle exposes the Root (/) tab, keeps active selection on
        // "local", and still does NOT spawn su until the user taps Root (/).
        rootEnabledFlow.value = true
        advanceUntilIdle()
        awaitUntil { vm.connectedProfiles.value.map { it.id } == listOf("local", "root") }
        advanceUntilIdle()

        assertEquals(listOf("local", "root"), vm.connectedProfiles.value.map { it.id })
        assertEquals("local", vm.activeProfileId.value)
        assertEquals("Enabling toggle alone must not spawn su", 0, spawnCount.get())

        // Agent UI bus commands targeting "root" are rejected even while enabled.
        bus.emit(AgentUiCommand.NavigateToSftpPath(profileId = "root", path = tmp.root.absolutePath))
        bus.emit(AgentUiCommand.OpenInEditor(profileId = "root", path = "/etc/hosts"))
        advanceUntilIdle()
        assertEquals("local", vm.activeProfileId.value)
        assertEquals("Agent UI commands must never trigger su", 0, spawnCount.get())

        // User explicitly selects Root (/) -> spawns daemon lazily and lists directory.
        val rootDir = tmp.newFolder("fake_root")
        File(rootDir, "build.prop").writeText("ro.secure=1\n")
        vm.selectProfile("root", initialPath = rootDir.absolutePath)
        advanceUntilIdle()
        awaitUntil { vm.entries.value.isNotEmpty() }
        advanceUntilIdle()

        assertTrue(vm.isRootProfile())
        assertTrue(vm.supportsPermissions())
        assertTrue(vm.supportsOwnership())
        assertEquals("Root", vm.activeTransportLabel.value)
        assertEquals(1, spawnCount.get())
        assertEquals(listOf("build.prop"), vm.entries.value.map { it.name })

        // Turning the toggle back off shuts down the worker, drops the tab, and reverts to Local.
        rootEnabledFlow.value = false
        advanceUntilIdle()
        awaitUntil { vm.connectedProfiles.value.map { it.id } == listOf("local") }
        advanceUntilIdle()

        assertFalse(vm.isRootProfile())
        assertEquals("local", vm.activeProfileId.value)
        assertEquals(listOf("local"), vm.connectedProfiles.value.map { it.id })
    }

    private fun buildViewModel(
        rootEnabledFlow: MutableStateFlow<Boolean>,
        commandFlow: MutableSharedFlow<AgentUiCommand>,
    ): SftpViewModel {
        val bus = mockk<AgentUiCommandBus>(relaxed = true)
        every { bus.commands } returns commandFlow
        every { bus.takePendingSftpCommand() } returns null

        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.sftpSortMode } returns flowOf("NAME_ASC")
        every { prefs.mediaExtensions } returns flowOf("mp4 mp3")
        every { prefs.terminalColorScheme } returns flowOf(UserPreferencesRepository.TerminalColorScheme.HAVEN)
        every { prefs.terminalAutoSwitchScheme } returns flowOf(false)
        every { prefs.terminalLightColorScheme } returns flowOf(UserPreferencesRepository.TerminalColorScheme.LIGHT)
        every { prefs.terminalDarkColorScheme } returns flowOf(UserPreferencesRepository.TerminalColorScheme.HAVEN)
        every { prefs.rootFilesEnabled } returns rootEnabledFlow

        val app = mockk<Context>(relaxed = true)
        val localDir = tmp.newFolder("local_sandbox")
        every { app.filesDir } returns localDir

        val repo = mockk<ConnectionRepository>(relaxed = true)
        coEvery { repo.getAll() } returns emptyList()

        val selector = mockk<TransportSelector>(relaxed = true)
        coEvery { selector.resolveFileBackend("local") } returns
            TransportSelector.FileBackendResolution(LocalFileBackend(app))
        coEvery { selector.resolveFileBackend("root") } returns null

        return SftpViewModel(
            sessionManager = mockk(relaxed = true) { every { sessions } returns MutableStateFlow(emptyMap()) },
            moshSessionManager = mockk(relaxed = true) { every { sessions } returns MutableStateFlow(emptyMap()) },
            etSessionManager = mockk(relaxed = true) { every { sessions } returns MutableStateFlow(emptyMap()) },
            smbSessionManager = mockk(relaxed = true) {
                every { sessions } returns MutableStateFlow(emptyMap())
                every { isProfileConnected(any()) } returns false
            },
            rcloneSessionManager = mockk(relaxed = true) {
                every { sessions } returns MutableStateFlow(emptyMap())
                every { isProfileConnected(any()) } returns false
            },
            reticulumSessionManager = mockk(relaxed = true) {
                every { sessions } returns MutableStateFlow(emptyMap())
                every { isProfileConnected(any()) } returns false
            },
            rcloneClient = mockk(relaxed = true),
            repository = repo,
            connectionLogRepository = mockk(relaxed = true),
            syncProfileRepository = mockk(relaxed = true) { every { observeAll() } returns flowOf(emptyList()) },
            ageIdentityRepository = mockk(relaxed = true) { every { observeAll() } returns flowOf(emptyList()) },
            preferencesRepository = prefs,
            transportSelector = selector,
            ffmpegExecutor = mockk(relaxed = true),
            hlsStreamServer = mockk(relaxed = true),
            sftpStreamServer = mockk(relaxed = true),
            pasteQueueDao = mockk(relaxed = true) {
                every { observeCountByStatus(any()) } returns flowOf(0)
                every { observePendingBytes(any()) } returns flowOf(0L)
            },
            agentUiCommandBus = bus,
            attachCoordinator = mockk(relaxed = true) { every { pendingRequest } returns MutableStateFlow(null); every { progress } returns MutableStateFlow(null) },
            chatAttachBroker = mockk(relaxed = true) { every { pending } returns MutableStateFlow(false) },
            servedFileTracker = mockk(relaxed = true) { every { active } returns MutableStateFlow(emptySet()) },
            rootFsClient = realClient,
            appContext = app,
        )
    }
}
