package sh.haven.feature.sftp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.feature.sftp.transport.FileBackend

/**
 * v5.87.63 crash: opening a 58 MB JSON file from the Files tab OOM-killed
 * the process (122 MB allocation against a 256 MB heap) inside
 * [SftpViewModel.openInEditor]'s whole-file read + editor materialisation.
 * The guard must refuse over-cap files BEFORE reading a byte.
 */
class EditorLargeFileGuardTest {

    private val dispatcher = StandardTestDispatcher()
    private val fakeBackend = mockk<FileBackend>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { fakeBackend.readBytes(any()) } returns "small file contents".toByteArray()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): SftpViewModel {
        val bus = mockk<AgentUiCommandBus>(relaxed = true)
        every { bus.commands } returns MutableSharedFlow()
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.sftpSortMode } returns flowOf("NAME_ASC")
        val app = mockk<Context>(relaxed = true)
        every {
            app.getString(eq(R.string.sftp_file_too_large_for_editor), any<String>())
        } returns "TOO_LARGE"
        return SftpViewModel(
            sessionManager = mockk(relaxed = true),
            moshSessionManager = mockk(relaxed = true),
            etSessionManager = mockk(relaxed = true),
            smbSessionManager = mockk(relaxed = true),
            rcloneSessionManager = mockk(relaxed = true),
            reticulumSessionManager = mockk(relaxed = true),
            rcloneClient = mockk(relaxed = true),
            repository = mockk(relaxed = true),
            connectionLogRepository = mockk(relaxed = true),
            syncProfileRepository = mockk(relaxed = true),
            ageIdentityRepository = mockk(relaxed = true),
            preferencesRepository = prefs,
            transportSelector = mockk(relaxed = true),
            ffmpegExecutor = mockk(relaxed = true),
            hlsStreamServer = mockk(relaxed = true),
            sftpStreamServer = mockk(relaxed = true),
            pasteQueueDao = mockk(relaxed = true),
            agentUiCommandBus = bus,
            attachCoordinator = mockk(relaxed = true),
            servedFileTracker = mockk(relaxed = true),
            appContext = app,
        ).also { it.backendOverride = { fakeBackend } }
    }

    private fun entry(size: Long) = SftpEntry(
        name = "conversations.json",
        path = "/home/ian/Downloads/conversations-001/conversations.json",
        isDirectory = false,
        size = size,
        modifiedTime = 0L,
        permissions = "-rw-r--r--",
    )

    @Test
    fun `58 MB file is refused and never read`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.openInEditor(entry(58L * 1024 * 1024))
        advanceUntilIdle()

        val state = vm.editorFile.value
        assertTrue("expected Error, got $state", state is SftpViewModel.EditorFileState.Error)
        assertEquals("TOO_LARGE", (state as SftpViewModel.EditorFileState.Error).message)
        coVerify(exactly = 0) { fakeBackend.readBytes(any()) }
    }

    @Test
    fun `file at the cap still opens`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.openInEditor(entry(100L))
        advanceUntilIdle()

        val state = vm.editorFile.value
        assertTrue("expected Open, got $state", state is SftpViewModel.EditorFileState.Open)
        assertEquals("small file contents", (state as SftpViewModel.EditorFileState.Open).content)
        coVerify(exactly = 1) { fakeBackend.readBytes(any()) }
    }

    @Test
    fun `agent-opened partial entry is stat'd and refused`() = runTest(dispatcher) {
        coEvery { fakeBackend.stat(any()) } returns entry(58L * 1024 * 1024)
        val vm = newViewModel()
        advanceUntilIdle()

        // AgentUiCommand.OpenInEditor builds the entry with size=0.
        vm.openInEditor(entry(0L))
        advanceUntilIdle()

        val state = vm.editorFile.value
        assertTrue("expected Error, got $state", state is SftpViewModel.EditorFileState.Error)
        assertEquals("TOO_LARGE", (state as SftpViewModel.EditorFileState.Error).message)
        coVerify(exactly = 0) { fakeBackend.readBytes(any()) }
    }

    @Test
    fun `formatSizeForMessage renders compact sizes`() {
        assertEquals("58 MB", SftpViewModel.formatSizeForMessage(58L * 1024 * 1024))
        assertEquals("1.5 KB", SftpViewModel.formatSizeForMessage(1536L))
        assertEquals("500 B", SftpViewModel.formatSizeForMessage(500L))
        assertEquals("1.2 GB", SftpViewModel.formatSizeForMessage((1.2 * 1024 * 1024 * 1024).toLong()))
    }
}
