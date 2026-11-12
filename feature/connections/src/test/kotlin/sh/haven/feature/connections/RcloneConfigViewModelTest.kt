package sh.haven.feature.connections

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.rclone.ConfigState
import sh.haven.core.rclone.ParsedRemote
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.session.SessionManagerRegistry

@OptIn(ExperimentalCoroutinesApi::class)
class RcloneConfigViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var rcloneClient: RcloneClient
    private lateinit var connectionRepository: ConnectionRepository
    private lateinit var sessionManagerRegistry: SessionManagerRegistry
    private lateinit var viewModel: RcloneConfigViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        rcloneClient = mockk(relaxed = true)
        connectionRepository = mockk(relaxed = true)
        sessionManagerRegistry = mockk(relaxed = true)
        viewModel = RcloneConfigViewModel(
            rcloneClient = rcloneClient,
            connectionRepository = connectionRepository,
            sessionManagerRegistry = sessionManagerRegistry,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadConfig separates active UI profiles from orphaned rclone conf remotes`() = runTest {
        val activeProfile = ConnectionProfile(
            id = "p1",
            label = "active",
            host = "",
            username = "",
            connectionType = "RCLONE",
            rcloneRemoteName = "active",
            rcloneProvider = "drive",
        )
        coEvery { connectionRepository.getAll() } returns listOf(activeProfile)
        every { rcloneClient.listRemotes() } returns listOf("active", "orphan")

        viewModel.loadConfig(
            """
            [active]
            type = drive
            token = {"a":"1"}

            [orphan]
            type = s3
            provider = AWS

            [fresh]
            type = webdav
            url = https://dav.example.com
            """.trimIndent(),
        )

        val deadline = System.currentTimeMillis() + 3_000
        while (viewModel.importState.value !is RcloneConfigViewModel.ImportState.Loaded &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }

        val state = viewModel.importState.value as RcloneConfigViewModel.ImportState.Loaded
        assertEquals(setOf("active"), state.existing)
        assertEquals(setOf("orphan"), state.orphaned)
        assertEquals(listOf("active", "orphan", "fresh"), state.remotes.map { it.name })
    }

    @Test
    fun `importRemotes repairs orphaned remote and overwrites existing profile without duplicating row`() = runTest {
        val existingProfile = ConnectionProfile(
            id = "keep-id",
            label = "Custom Label",
            host = "",
            username = "",
            connectionType = "RCLONE",
            rcloneRemoteName = "active",
            rcloneProvider = "drive",
            colorTag = 3,
        )
        val savedProfiles = mutableListOf<ConnectionProfile>()
        coEvery { connectionRepository.getAll() } returns listOf(existingProfile)
        coEvery { connectionRepository.save(capture(savedProfiles)) } returns Unit
        every { rcloneClient.listRemotes() } returns listOf("active", "orphan")
        every {
            rcloneClient.createRemote(any(), any(), any(), noObscure = true, nonInteractive = true)
        } returns ConfigState(state = "", option = null, error = "")

        viewModel.importRemotes(
            listOf(
                ParsedRemote("orphan", "s3", mapOf("provider" to "AWS")),
                ParsedRemote("active", "s3", mapOf("provider" to "Cloudflare")),
            ),
        )

        val deadline = System.currentTimeMillis() + 3_000
        while (viewModel.importState.value !is RcloneConfigViewModel.ImportState.Done &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }

        val done = viewModel.importState.value as RcloneConfigViewModel.ImportState.Done
        assertEquals(listOf("orphan", "active"), done.created)
        assertTrue(done.skipped.isEmpty())
        assertTrue(done.failed.isEmpty())

        verify { rcloneClient.deleteRemote("orphan") }
        verify { rcloneClient.deleteRemote("active") }
        assertEquals(2, savedProfiles.size)

        val orphanSaved = savedProfiles.first { it.rcloneRemoteName == "orphan" }
        assertEquals("RCLONE", orphanSaved.connectionType)
        assertEquals("s3", orphanSaved.rcloneProvider)

        val activeSaved = savedProfiles.first { it.rcloneRemoteName == "active" }
        assertEquals("keep-id", activeSaved.id)
        assertEquals("Custom Label", activeSaved.label)
        assertEquals(3, activeSaved.colorTag)
        assertEquals("s3", activeSaved.rcloneProvider)
    }

    @Test
    fun `resetRcloneStorage deletes only RCLONE profiles and clears rclone conf remotes`() = runTest {
        val rcProfile = ConnectionProfile(
            id = "rc1",
            label = "Drive",
            host = "",
            username = "",
            connectionType = "RCLONE",
            rcloneRemoteName = "gdrive",
            rcloneProvider = "drive",
        )
        val sshProfile = ConnectionProfile(
            id = "ssh1",
            label = "Prod SSH",
            host = "10.0.0.1",
            username = "root",
            connectionType = "SSH",
        )
        coEvery { connectionRepository.getAll() } returns listOf(rcProfile, sshProfile)
        every { rcloneClient.listRemotes() } returns listOf("gdrive", "orphanUpper", "orphanlower")

        viewModel.resetRcloneStorage()

        val deadline = System.currentTimeMillis() + 3_000
        while (viewModel.importState.value !is RcloneConfigViewModel.ImportState.ResetDone &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }

        val state = viewModel.importState.value as RcloneConfigViewModel.ImportState.ResetDone
        assertEquals(1, state.removedProfiles)
        assertEquals(3, state.removedRemotes)

        verify { sessionManagerRegistry.disconnectProfile("rc1") }
        coVerify { connectionRepository.delete("rc1") }
        coVerify(exactly = 0) { connectionRepository.delete("ssh1") }
        verify { rcloneClient.deleteRemote("gdrive") }
        verify { rcloneClient.deleteRemote("orphanUpper") }
        verify { rcloneClient.deleteRemote("orphanlower") }
    }
}
