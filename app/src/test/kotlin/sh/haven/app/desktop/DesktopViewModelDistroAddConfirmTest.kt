package sh.haven.app.desktop

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.agent.AgentPresentationManager
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.desktop.DesktopSessionRegistry
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.knock.PortKnocker
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.SystemVmManager
import sh.haven.core.local.proot.Arch
import sh.haven.core.local.proot.Distro
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.local.proot.RootfsSource
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.tunnel.TunnelResolver
import sh.haven.app.usb.UsbDriveVmManager

/**
 * #620: tapping "+ <name> (~MB)" in the distro dropdown must NOT start the
 * few-hundred-MB rootfs download directly — it parks the request in
 * [DesktopViewModel.distroPendingAdd] and only downloads on confirm.
 * Regression tests for the download-on-select complaint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopViewModelDistroAddConfirmTest {

    private val testDispatcher = StandardTestDispatcher()

    private val prootManager = mockk<ProotManager>(relaxed = true)
    private val localSessionManager = mockk<LocalSessionManager>(relaxed = true)
    // The VM's init blocks collect these two flows; a relaxed mock returns
    // null for them and the collectors throw, so stub real (empty) ones.
    private val commandBus = sh.haven.core.data.agent.AgentUiCommandBus()
    private val usbSessions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, UsbDriveVmManager.Status>>(emptyMap())
    private val usbDriveVmManager = mockk<UsbDriveVmManager>(relaxed = true)

    private val testDistro = Distro(
        id = "test-distro",
        label = "Test Distro",
        family = PackageFamily.APT,
        rootfsSources = mapOf(
            Arch.X86_64 to RootfsSource(url = "https://example.test/test.tar.gz", sha256 = "abc"),
            Arch.ARM to RootfsSource(url = "https://example.test/test.tar.gz", sha256 = "abc"),
        ),
        baselinePackages = emptyList(),
        sizeEstimateMb = 42,
    )

    private lateinit var vm: DesktopViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { localSessionManager.prootManager } returns prootManager
        every { prootManager.activeDistroId } returns "alpine-3.21"
        every { usbDriveVmManager.sessions } returns usbSessions
        every { prootManager.state } returns MutableStateFlow(ProotManager.SetupState.Ready)
        coEvery { prootManager.installRootfs() } returns Unit
        coEvery { prootManager.importRootfs(any(), any(), any(), any(), any(), any(), any()) } returns Unit
        // foreignDistroId is pure logic on the real manager; a relaxed mock
        // returns "" so the derived-id verify below would never match.
        every { prootManager.foreignDistroId(testDistro, Arch.ARM) } returns "test-distro-armv7"
        vm = DesktopViewModel(
            sshSessionManager = mockk(relaxed = true),
            moshSessionManager = mockk(relaxed = true),
            etSessionManager = mockk(relaxed = true),
            connectionLogRepository = mockk(relaxed = true),
            preferencesRepository = mockk(relaxed = true),
            connectionRepository = mockk(relaxed = true),
            tunnelResolver = mockk(relaxed = true),
            portKnocker = mockk(relaxed = true),
            agentUiCommandBus = commandBus,
            localSessionManager = localSessionManager,
            desktopSessionRegistry = mockk(relaxed = true),
            presentationManager = mockk(relaxed = true),
            appWindowLauncher = mockk(relaxed = true),
            appWindowShortcutManager = mockk(relaxed = true),
            usbDriveVmManager = usbDriveVmManager,
            systemVmManager = mockk<SystemVmManager>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `requestAddDistro parks the request and does not download`() = runTest {
        vm.requestAddDistro(testDistro)
        advanceUntilIdle()
        assertEquals(
            DesktopViewModel.PendingAdd.Native(testDistro),
            vm.distroPendingAdd.value,
        )
        coVerify(exactly = 0) { prootManager.installRootfs() }
    }

    @Test
    fun `confirmAddDistro downloads once and clears the pending request`() = runTest {
        vm.requestAddDistro(testDistro)
        vm.confirmDistroPendingAdd()
        advanceUntilIdle()
        coVerify(exactly = 1) { prootManager.installRootfs() }
        assertNull(vm.distroPendingAdd.value)
    }

    @Test
    fun `dismissAddDistro never downloads and clears the pending request`() = runTest {
        vm.requestAddDistro(testDistro)
        vm.dismissDistroPendingAdd()
        advanceUntilIdle()
        coVerify(exactly = 0) { prootManager.installRootfs() }
        assertNull(vm.distroPendingAdd.value)
    }

    @Test
    fun `confirm of a foreign add imports under the derived arch id`() = runTest {
        vm.requestAddForeignDistro(testDistro, Arch.ARM)
        assertEquals(
            DesktopViewModel.PendingAdd.Foreign(testDistro, Arch.ARM),
            vm.distroPendingAdd.value,
        )
        coVerify(exactly = 0) { prootManager.importRootfs(any(), any(), any(), any(), any(), any(), any()) }
        vm.confirmDistroPendingAdd()
        advanceUntilIdle()
        coVerify(exactly = 1) {
            prootManager.importRootfs(
                id = "test-distro-armv7",
                label = any(),
                family = PackageFamily.APT,
                source = "https://example.test/test.tar.gz",
                format = any(),
                stripComponents = any(),
                expectedSha256 = "abc",
            )
        }
        assertNull(vm.distroPendingAdd.value)
    }

    @Test
    fun `confirm with nothing pending is a no-op`() = runTest {
        vm.confirmDistroPendingAdd()
        advanceUntilIdle()
        coVerify(exactly = 0) { prootManager.installRootfs() }
        coVerify(exactly = 0) { prootManager.importRootfs(any(), any(), any(), any(), any(), any(), any()) }
    }
}
