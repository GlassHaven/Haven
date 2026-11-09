package sh.haven.feature.sftp

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.PasteQueueDao
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.SshConnection
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.transport.FileBackend
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Regression coverage for GH#798: cutting or copying files/directories inside
 * the same remote SFTP session must operate directly on the remote host
 * (via SFTP `rename` / `mv -f` for move and `cp -Rfp` for copy) instead of
 * downloading every file to the phone's cache and uploading it back.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SameRemotePasteTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val fakeBackend = mockk<FileBackend>(relaxed = true)
    private val sshManager = mockk<SshSessionManager>(relaxed = true)
    private val sshConnection = mockk<SshConnection>(relaxed = true)
    private val pasteQueueDao = mockk<PasteQueueDao>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { sshManager.sessions } returns MutableStateFlow(emptyMap())
        coEvery { fakeBackend.list(any()) } returns emptyList()
        coEvery { fakeBackend.stat(any()) } throws FileNotFoundException("absent")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(withSshConnection: Boolean = true): SftpViewModel {
        val bus = mockk<AgentUiCommandBus>(relaxed = true)
        every { bus.commands } returns MutableSharedFlow()
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.sftpSortMode } returns flowOf("NAME_ASC")
        val app = mockk<Context>(relaxed = true)
        every { sshManager.getSshClientForProfile("prof-1") } returns
            if (withSshConnection) sshConnection else null
        return SftpViewModel(
            sessionManager = sshManager,
            moshSessionManager = mockk(relaxed = true) {
                every { sessions } returns MutableStateFlow(emptyMap())
                every { getSshClientForProfile(any()) } returns null
            },
            etSessionManager = mockk(relaxed = true) {
                every { sessions } returns MutableStateFlow(emptyMap())
                every { getSshClientForProfile(any()) } returns null
            },
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
            repository = mockk(relaxed = true),
            connectionLogRepository = mockk(relaxed = true),
            syncProfileRepository = mockk(relaxed = true),
            ageIdentityRepository = mockk(relaxed = true),
            preferencesRepository = prefs,
            transportSelector = mockk(relaxed = true),
            ffmpegExecutor = mockk(relaxed = true),
            hlsStreamServer = mockk(relaxed = true),
            sftpStreamServer = mockk(relaxed = true),
            pasteQueueDao = pasteQueueDao,
            agentUiCommandBus = bus,
            attachCoordinator = mockk(relaxed = true),
            chatAttachBroker = mockk(relaxed = true),
            servedFileTracker = mockk(relaxed = true),
            appContext = app,
        ).also { it.backendOverride = { fakeBackend } }
    }

    private fun fileEntry(name: String, dir: String = "/src", size: Long = 1024L) = SftpEntry(
        name = name,
        path = "$dir/$name",
        isDirectory = false,
        size = size,
        modifiedTime = 1_700_000_000L,
        permissions = "-rw-r--r--",
    )

    private fun dirEntry(name: String, dir: String = "/src") = SftpEntry(
        name = name,
        path = "$dir/$name",
        isDirectory = true,
        size = 0L,
        modifiedTime = 1_700_000_000L,
        permissions = "drwxr-xr-x",
    )

    private suspend fun selectRemoteProfile(vm: SftpViewModel, profileId: String, initialPath: String) {
        vm.selectProfile(profileId, initialPath = initialPath)
        withContext(Dispatchers.Default) {
            vm.loading.first { !it }
        }
    }

    private suspend fun navigateAndAwait(vm: SftpViewModel, path: String) {
        vm.navigateTo(path)
        withContext(Dispatchers.Default) {
            vm.loading.first { !it }
        }
    }

    private suspend fun pasteAndAwait(vm: SftpViewModel) {
        vm.pasteFromClipboard()
        withContext(Dispatchers.Default) {
            vm.clipboard.first { it == null }
            vm.loading.first { !it }
        }
    }

    @Test
    fun `same-session cut and paste renames on remote server without downloading`() = runTest(dispatcher) {
        val vm = newViewModel()
        selectRemoteProfile(vm, "prof-1", "/src")

        vm.copyToClipboard(listOf(fileEntry("report.pdf"), dirEntry("photos")), isCut = true)
        navigateAndAwait(vm, "/dst")

        pasteAndAwait(vm)

        coVerify(exactly = 1) { fakeBackend.rename("/src/report.pdf", "/dst/report.pdf") }
        coVerify(exactly = 1) { fakeBackend.rename("/src/photos", "/dst/photos") }
        coVerify(exactly = 0) { pasteQueueDao.insertAll(any()) }
        assertNull(vm.clipboard.value)
        assertEquals("Moved 2 files", vm.message.value)
    }

    @Test
    fun `same-session cut falls back to mv -f when SFTP rename fails across mountpoints`() = runTest(dispatcher) {
        coEvery { fakeBackend.rename(any(), any()) } throws IOException("SSH_FX_FAILURE")
        coEvery { sshConnection.execCommand(any(), any()) } returns ExecResult(0, "", "")
        val vm = newViewModel()
        selectRemoteProfile(vm, "prof-1", "/mnt/disk1")

        vm.copyToClipboard(listOf(fileEntry("archive.tar.zst", dir = "/mnt/disk1")), isCut = true)
        navigateAndAwait(vm, "/mnt/disk2")

        pasteAndAwait(vm)

        coVerify(exactly = 1) {
            sshConnection.execCommand("mv -f -- '/mnt/disk1/archive.tar.zst' '/mnt/disk2/archive.tar.zst'", any())
        }
        coVerify(exactly = 0) { pasteQueueDao.insertAll(any()) }
        assertEquals("Moved 1 files", vm.message.value)
    }

    @Test
    fun `same-session cut with overwrite deletes existing destination file before SFTP rename`() = runTest(dispatcher) {
        coEvery { fakeBackend.stat("/dst/notes.txt") } returns fileEntry("notes.txt", dir = "/dst", size = 512L)
        val vm = newViewModel()
        selectRemoteProfile(vm, "prof-1", "/src")

        vm.copyToClipboard(listOf(fileEntry("notes.txt")), isCut = true)
        navigateAndAwait(vm, "/dst")

        backgroundScope.launch(Dispatchers.Default) {
            vm.conflictPrompt.first { it != null }?.onChoice(ConflictAction.OVERWRITE, false)
        }
        pasteAndAwait(vm)

        coVerify(exactly = 1) { fakeBackend.delete("/dst/notes.txt", false) }
        coVerify(exactly = 1) { fakeBackend.rename("/src/notes.txt", "/dst/notes.txt") }
    }

    @Test
    fun `same-session copy and paste runs cp -Rfp on remote server preserving metadata`() = runTest(dispatcher) {
        coEvery { sshConnection.execCommand(any(), any()) } returns ExecResult(0, "", "")
        val vm = newViewModel()
        selectRemoteProfile(vm, "prof-1", "/src")

        vm.copyToClipboard(listOf(fileEntry("data.csv"), dirEntry("logs")), isCut = false)
        navigateAndAwait(vm, "/dst")

        pasteAndAwait(vm)

        coVerify(exactly = 1) {
            sshConnection.execCommand("cp -Rfp -- '/src/data.csv' '/dst/data.csv'", any())
        }
        coVerify(exactly = 1) {
            sshConnection.execCommand("cp -Rfp -- '/src/logs' '/dst/'", any())
        }
        coVerify(exactly = 0) { pasteQueueDao.insertAll(any()) }
        assertNull(vm.clipboard.value)
        assertEquals("Copied 2 files", vm.message.value)
    }

    @Test
    fun `same-session copy falls back to stream queue when remote shell is disallowed`() = runTest(dispatcher) {
        coEvery { sshConnection.execCommand(any(), any()) } returns
            ExecResult(1, "", "This service allows sftp connections only.")
        coEvery { pasteQueueDao.getByStatus(any()) } returns emptyList()
        coEvery { pasteQueueDao.countByStatus(any()) } returns 0
        val vm = newViewModel()
        selectRemoteProfile(vm, "prof-1", "/src")

        vm.copyToClipboard(listOf(fileEntry("only-sftp.bin")), isCut = false)
        navigateAndAwait(vm, "/dst")

        pasteAndAwait(vm)

        coVerify(exactly = 1) { pasteQueueDao.insertAll(any()) }
    }

    @Test
    fun `directory listing populates remote folder item counts via single SSH batch exec`() = runTest(dispatcher) {
        coEvery { fakeBackend.list("/work") } returns listOf(
            dirEntry("docs", dir = "/work"),
            dirEntry("empty", dir = "/work"),
            fileEntry("readme.md", dir = "/work"),
        )
        coEvery { sshConnection.execCommand(match { it.contains("ls -1A -- '/work/docs'") }, any()) } returns
            ExecResult(0, "0 5 4\n1 0 0\n", "")

        val vm = newViewModel()
        selectRemoteProfile(vm, "prof-1", "/src")
        navigateAndAwait(vm, "/work")

        withContext(Dispatchers.Default) {
            vm.entries.first { list -> list.find { it.name == "docs" }?.childCount == 5 }
        }
        val byName = vm.entries.value.associateBy { it.name }
        assertEquals(5, byName.getValue("docs").childCount)
        assertEquals(4, byName.getValue("docs").visibleChildCount)
        assertEquals(0, byName.getValue("empty").childCount)
        assertEquals(0, byName.getValue("empty").visibleChildCount)
        assertNull(byName.getValue("readme.md").childCount)
    }

    @Test
    fun `directory listing falls back to backend list for folder item counts when SSH exec is disallowed`() = runTest(dispatcher) {
        coEvery { fakeBackend.list("/work") } returns listOf(
            dirEntry("sub", dir = "/work"),
        )
        coEvery { fakeBackend.list("/work/sub") } returns listOf(
            fileEntry("visible.txt", dir = "/work/sub"),
            fileEntry(".secret", dir = "/work/sub"),
        )
        coEvery { sshConnection.execCommand(any(), any()) } returns
            ExecResult(1, "", "sftp only")

        val vm = newViewModel()
        selectRemoteProfile(vm, "prof-1", "/src")
        navigateAndAwait(vm, "/work")

        withContext(Dispatchers.Default) {
            vm.entries.first { list -> list.find { it.name == "sub" }?.childCount == 2 }
        }
        val sub = vm.entries.value.single { it.name == "sub" }
        assertEquals(2, sub.childCount)
        assertEquals(1, sub.visibleChildCount)
    }
}
