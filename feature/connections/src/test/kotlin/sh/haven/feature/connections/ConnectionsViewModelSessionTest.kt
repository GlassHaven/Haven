package sh.haven.feature.connections

import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumBridge
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SshSessionManager

/**
 * Tests that disconnect() and deleteConnection() clean up ALL session manager types.
 * Regression tests for the bug where local and SMB sessions survived disconnect/delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelSessionTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var appContext: Context
    private lateinit var repository: ConnectionRepository
    private lateinit var portForwardRepository: PortForwardRepository
    private lateinit var sshSessionManager: SshSessionManager
    private lateinit var reticulumSessionManager: ReticulumSessionManager
    private lateinit var moshSessionManager: MoshSessionManager
    private lateinit var etSessionManager: EtSessionManager
    private lateinit var smbSessionManager: SmbSessionManager
    private lateinit var localSessionManager: LocalSessionManager
    private lateinit var prootManager: ProotManager
    private lateinit var viewModel: ConnectionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        appContext = mockk(relaxed = true)
        repository = mockk(relaxed = true) {
            every { observeAll() } returns flowOf(emptyList())
        }
        portForwardRepository = mockk(relaxed = true)
        sshSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { hasActiveSessions } returns false
        }
        reticulumSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        moshSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        etSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        smbSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        prootManager = mockk(relaxed = true)
        localSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
            every { prootManager } returns this@ConnectionsViewModelSessionTest.prootManager
        }

        viewModel = ConnectionsViewModel(
            appContext = appContext,
            repository = repository,
            portForwardRepository = portForwardRepository,
            sshSessionManager = sshSessionManager,
            reticulumSessionManager = reticulumSessionManager,
            moshSessionManager = moshSessionManager,
            etSessionManager = etSessionManager,
            reticulumBridge = mockk(relaxed = true),
            smbSessionManager = smbSessionManager,
            fidoAuthenticator = mockk(relaxed = true),
            localSessionManager = localSessionManager,
            sshKeyRepository = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
            },
            preferencesRepository = mockk(relaxed = true) {
                every { sessionManager } returns flowOf(mockk(relaxed = true) {
                    every { label } returns "None"
                })
            },
            hostKeyVerifier = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `disconnect cleans up all session manager types`() {
        viewModel.disconnect("profile1")

        verify { sshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { reticulumSessionManager.removeAllSessionsForProfile("profile1") }
        verify { moshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { etSessionManager.removeAllSessionsForProfile("profile1") }
        verify { smbSessionManager.removeAllSessionsForProfile("profile1") }
        verify { localSessionManager.removeAllSessionsForProfile("profile1") }
    }

    @Test
    fun `disconnect stops VNC server`() {
        viewModel.disconnect("profile1")

        verify { prootManager.stopVncServer() }
    }

    @Test
    fun `deleteConnection cleans up all session manager types`() = runTest {
        viewModel.deleteConnection("profile1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { sshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { reticulumSessionManager.removeAllSessionsForProfile("profile1") }
        verify { moshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { etSessionManager.removeAllSessionsForProfile("profile1") }
        verify { smbSessionManager.removeAllSessionsForProfile("profile1") }
        verify { localSessionManager.removeAllSessionsForProfile("profile1") }
    }

    @Test
    fun `deleteConnection stops VNC server and deletes from repository`() = runTest {
        viewModel.deleteConnection("profile1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { prootManager.stopVncServer() }
        coVerify { repository.delete("profile1") }
    }
}
