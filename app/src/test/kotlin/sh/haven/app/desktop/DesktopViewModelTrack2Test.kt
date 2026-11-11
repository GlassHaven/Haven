package sh.haven.app.desktop

import io.mockk.coEvery
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.app.usb.UsbDriveVmManager
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.desktop.DesktopSessionRegistry
import sh.haven.core.data.desktop.DesktopStatus
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.SystemVmManager
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.tunnel.TunnelResolver
import sh.haven.core.vnc.VncClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopViewModelTrack2Test {

    private val testDispatcher = StandardTestDispatcher()

    private val prootManager = mockk<ProotManager>(relaxed = true)
    private val localSessionManager = mockk<LocalSessionManager>(relaxed = true)
    private val commandBus = AgentUiCommandBus()
    private val usbSessions = MutableStateFlow<Map<String, UsbDriveVmManager.Status>>(emptyMap())
    private val usbDriveVmManager = mockk<UsbDriveVmManager>(relaxed = true)
    private val umlDesktopState =
        MutableStateFlow<Map<sh.haven.core.local.uml.UmlDesktopManager.Kind,
            sh.haven.core.local.uml.UmlDesktopManager.DesktopState>>(emptyMap())
    private val umlDesktopManager = mockk<sh.haven.core.local.uml.UmlDesktopManager>(relaxed = true)
    private val connectionRepository = mockk<ConnectionRepository>(relaxed = true)
    private val tunnelResolver = mockk<TunnelResolver>(relaxed = true)
    private val sshSessionManager = mockk<SshSessionManager>(relaxed = true)
    private val preferencesRepository = mockk<UserPreferencesRepository>(relaxed = true)
    private val registry = DesktopSessionRegistry()

    private lateinit var vm: DesktopViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { localSessionManager.prootManager } returns prootManager
        every { prootManager.activeDistroId } returns "alpine-3.21"
        every { usbDriveVmManager.sessions } returns usbSessions
        every { umlDesktopManager.state } returns umlDesktopState
        every { prootManager.state } returns MutableStateFlow(ProotManager.SetupState.Ready)
        every { preferencesRepository.verboseLoggingEnabled } returns flowOf(false)

        vm = DesktopViewModel(
            sshSessionManager = sshSessionManager,
            moshSessionManager = mockk(relaxed = true),
            etSessionManager = mockk(relaxed = true),
            connectionLogRepository = mockk(relaxed = true),
            preferencesRepository = preferencesRepository,
            connectionRepository = connectionRepository,
            tunnelResolver = tunnelResolver,
            portKnocker = mockk(relaxed = true),
            agentUiCommandBus = commandBus,
            localSessionManager = localSessionManager,
            desktopSessionRegistry = registry,
            presentationManager = mockk(relaxed = true),
            appWindowLauncher = mockk(relaxed = true),
            appWindowShortcutManager = mockk(relaxed = true),
            usbDriveVmManager = usbDriveVmManager,
            umlRecoveryManager = mockk(relaxed = true),
            systemVmManager = mockk<SystemVmManager>(relaxed = true),
            umlDesktopManager = umlDesktopManager,
            tlsCertVerifier = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addSpiceSession and addGlkvmSession fail closed when tunnelResolver fails`() = runTest {
        val spiceProfile = ConnectionProfile(
            id = "spice-wg",
            label = "SPICE over WG",
            host = "10.99.0.10",
            username = "",
            connectionType = "SPICE",
            spicePort = 5900,
            tunnelConfigId = "wg-1",
        )
        val glkvmProfile = ConnectionProfile(
            id = "glkvm-wg",
            label = "GLKVM over WG",
            host = "10.99.0.11",
            username = "admin",
            connectionType = "GLKVM",
            glkvmPort = 443,
            tunnelConfigId = "wg-1",
        )
        coEvery { connectionRepository.getById("spice-wg") } returns spiceProfile
        coEvery { connectionRepository.getById("glkvm-wg") } returns glkvmProfile
        coEvery { tunnelResolver.socketFactory(spiceProfile) } throws IOException("WireGuard handshake failed")
        coEvery { tunnelResolver.socketFactory(glkvmProfile) } throws IOException("WireGuard handshake failed")

        vm.addSpiceSession(host = "10.99.0.10", port = 5900, password = null, profileId = "spice-wg")
        vm.addGlkvmSession(host = "10.99.0.11", port = 443, user = "admin", password = "pw", profileId = "glkvm-wg")

        val deadline = System.currentTimeMillis() + 5_000
        while ((registry.statuses.value["spice-wg"] != DesktopStatus.ERROR ||
                registry.statuses.value["glkvm-wg"] != DesktopStatus.ERROR) &&
            System.currentTimeMillis() < deadline
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }

        coVerify(atLeast = 1) { tunnelResolver.socketFactory(spiceProfile) }
        coVerify(atLeast = 1) { tunnelResolver.socketFactory(glkvmProfile) }
        assertEquals(DesktopStatus.ERROR, registry.statuses.value["spice-wg"])
        assertEquals(DesktopStatus.ERROR, registry.statuses.value["glkvm-wg"])
        // Both failed tabs must surface an error message rather than vanishing silently.
        val spiceTab = vm.tabs.value.firstOrNull { it.profileId == "spice-wg" }
        val glkvmTab = vm.tabs.value.firstOrNull { it.profileId == "glkvm-wg" }
        assertNotNull("Failed SPICE tunnel setup must surface an error tab", spiceTab)
        assertNotNull("Failed GLKVM tunnel setup must surface an error tab", glkvmTab)
        assertTrue(spiceTab!!.error.value.orEmpty().contains("WireGuard"))
        assertTrue(glkvmTab!!.error.value.orEmpty().contains("WireGuard"))
    }

    @Test
    fun `onCleared closes active desktop sessions and releases tunnel dependents`() {
        val clientStopped = CountDownLatch(1)
        val leaseClosed = CountDownLatch(1)
        val tunnelReleased = CountDownLatch(1)

        val vncClient = mockk<VncClient>(relaxed = true) {
            every { stop() } answers { clientStopped.countDown() }
        }
        val lease = mockk<SshSessionManager.TunnelLease>(relaxed = true) {
            every { close() } answers { leaseClosed.countDown() }
        }
        coEvery { tunnelResolver.release("vnc-1") } coAnswers {
            tunnelReleased.countDown()
        }

        val tab = DesktopTab.Vnc(
            id = "tab-1",
            label = "VNC 1",
            colorTag = 0,
            client = vncClient,
            _connected = MutableStateFlow(true),
            _frame = MutableStateFlow(null),
            _error = MutableStateFlow(null),
            _cursor = MutableStateFlow(null),
            _pointerPos = MutableStateFlow(0 to 0),
            _bandwidthSuggestion = MutableStateFlow(null),
            tunnelLease = lease,
            profileId = "vnc-1",
        )
        registry.setStatus("vnc-1", DesktopStatus.CONNECTED)
        // Populate _tabs via reflection so we isolate onCleared() teardown from network connect.
        val tabsField = DesktopViewModel::class.java.getDeclaredField("_tabs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (tabsField.get(vm) as MutableStateFlow<List<DesktopTab>>).value = listOf(tab)

        // Simulate ViewModelStore.clear() — ViewModel.clear$lifecycle_viewmodel_release cancels
        // viewModelScope BEFORE invoking onCleared(), which is why launching on viewModelScope leaked.
        val clearMethod = androidx.lifecycle.ViewModel::class.java.declaredMethods
            .first { it.name.startsWith("clear") && it.parameterCount == 0 }
            .apply { isAccessible = true }
        clearMethod.invoke(vm)

        assertTrue("VNC client.stop() must execute during onCleared()", clientStopped.await(3, TimeUnit.SECONDS))
        assertTrue("SSH tunnelLease.close() must execute during onCleared()", leaseClosed.await(3, TimeUnit.SECONDS))
        assertTrue("tunnelResolver.release() must execute during onCleared()", tunnelReleased.await(3, TimeUnit.SECONDS))
        verify { sshSessionManager.releaseTunnelDependent("vnc-1") }
    }
}
