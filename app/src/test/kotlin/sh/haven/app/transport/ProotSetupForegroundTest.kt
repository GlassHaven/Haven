package sh.haven.app.transport

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.SystemVmManager
import sh.haven.core.local.VmArch
import sh.haven.core.local.proot.DistroCatalog
import sh.haven.core.session.SessionManagerRegistry

/**
 * Regression coverage for #832: first-time PRoot distro setup (`Downloading`,
 * `Extracting`, `Initializing`) and desktop package installation
 * (`DesktopSetupState.Installing`) must register with the silent ongoing
 * foreground service (`SshConnectionService`) via both
 * `ForegroundSessionParticipant` and `ForegroundKeepAlive` so Android does not
 * reclaim the Haven process mid-`pacman` run when the user backgrounds the app.
 */
class ProotSetupForegroundTest {

    @Test
    fun `prootSetupSessionInfo reflects active rootfs and desktop install steps`() {
        assertNull(
            prootSetupSessionInfo(
                distroLabel = "Arch Linux",
                rootfsState = ProotManager.SetupState.NotInstalled,
                desktopState = ProotManager.DesktopSetupState.Idle,
            ),
        )
        assertNull(
            prootSetupSessionInfo(
                distroLabel = "Arch Linux",
                rootfsState = ProotManager.SetupState.Ready,
                desktopState = ProotManager.DesktopSetupState.Complete,
            ),
        )
        assertNull(
            prootSetupSessionInfo(
                distroLabel = "Arch Linux",
                rootfsState = ProotManager.SetupState.Error(ProotManager.Phase.BootstrapHook, "boom"),
                desktopState = ProotManager.DesktopSetupState.Error(ProotManager.DePhase.Packages, "boom"),
            ),
        )

        val dl = prootSetupSessionInfo(
            distroLabel = "Arch Linux",
            rootfsState = ProotManager.SetupState.Downloading(42),
            desktopState = ProotManager.DesktopSetupState.Idle,
        )
        assertEquals("Installing Arch Linux: downloading (42%)", dl?.label)

        val ext = prootSetupSessionInfo(
            distroLabel = "Arch Linux",
            rootfsState = ProotManager.SetupState.Extracting,
            desktopState = ProotManager.DesktopSetupState.Idle,
        )
        assertEquals("Installing Arch Linux: extracting rootfs…", ext?.label)

        val init = prootSetupSessionInfo(
            distroLabel = "Arch Linux",
            rootfsState = ProotManager.SetupState.Initializing(
                step = "pacman -Syu: Downloading glibc (3/120)...",
                progress = 0.12f,
            ),
            desktopState = ProotManager.DesktopSetupState.Idle,
        )
        assertEquals(
            "Installing Arch Linux: pacman -Syu: Downloading glibc (3/120)... (12%)",
            init?.label,
        )

        val de = prootSetupSessionInfo(
            distroLabel = "Arch Linux",
            rootfsState = ProotManager.SetupState.Ready,
            desktopState = ProotManager.DesktopSetupState.Installing(
                step = "Sway: Installing sway (4/18)...",
                de = ProotManager.DesktopEnvironment.SWAY,
                progress = 0.61f,
            ),
        )
        assertEquals("Sway: Installing sway (4/18)... (61%)", de?.label)
    }

    @Test
    fun `prootSetup participant and localWorkKeepAlive hold SessionManagerRegistry active during setup and running desktops`() {
        val rootfsFlow = MutableStateFlow<ProotManager.SetupState>(ProotManager.SetupState.NotInstalled)
        val deSetupFlow = MutableStateFlow<ProotManager.DesktopSetupState>(ProotManager.DesktopSetupState.Idle)
        val desktopsFlow = MutableStateFlow<Map<ProotManager.DesktopEnvironment, DesktopManager.DesktopInstance>>(emptyMap())
        val vmFlow = MutableStateFlow<SystemVmManager.VmState?>(null)

        val proot = mockk<ProotManager>(relaxed = true) {
            every { activeDistro } returns DistroCatalog.ARCH_LINUX
            every { state } returns rootfsFlow
            every { desktopState } returns deSetupFlow
        }
        val desktops = mockk<DesktopManager>(relaxed = true) {
            every { this@mockk.desktops } returns desktopsFlow
        }
        val systemVm = mockk<SystemVmManager>(relaxed = true) {
            every { state } returns vmFlow
        }

        val participant = ForegroundSessionParticipantModule.prootSetup(proot)
        val keepAlive = ForegroundSessionParticipantModule.localWorkKeepAlive(
            proot = dagger.Lazy { proot },
            desktops = dagger.Lazy { desktops },
            systemVm = dagger.Lazy { systemVm },
        )
        val registry = SessionManagerRegistry(transports = emptySet(), keepAlives = setOf(keepAlive))

        assertTrue(participant.activeSessions.isEmpty())
        assertFalse(registry.hasActiveSessions())

        // 1. Rootfs bootstrap (`pacman -Syu`) holds the foreground service alive
        rootfsFlow.value = ProotManager.SetupState.Initializing("pacman -Syu", 0.35f)
        assertEquals(1, participant.activeSessions.size)
        assertTrue(registry.hasActiveSessions())

        // 2. Rootfs finishes, DE install begins -> stays active
        rootfsFlow.value = ProotManager.SetupState.Ready
        assertFalse(registry.hasActiveSessions())
        deSetupFlow.value = ProotManager.DesktopSetupState.Installing(
            "Sway: Installing sway...",
            ProotManager.DesktopEnvironment.SWAY,
            0.50f,
        )
        assertEquals(1, participant.activeSessions.size)
        assertTrue(registry.hasActiveSessions())

        // 3. DE install finishes -> idle unless a desktop or VM is running
        deSetupFlow.value = ProotManager.DesktopSetupState.Complete
        assertTrue(participant.activeSessions.isEmpty())
        assertFalse(registry.hasActiveSessions())

        // 4. Running Sway desktop or System VM also keeps FGS alive via keepAlive
        desktopsFlow.value = mapOf(
            ProotManager.DesktopEnvironment.SWAY to DesktopManager.DesktopInstance(
                de = ProotManager.DesktopEnvironment.SWAY,
                displayNumber = 51,
                vncPort = 5951,
                state = DesktopManager.DesktopState.RUNNING,
            ),
        )
        assertTrue(registry.hasActiveSessions())
        desktopsFlow.value = emptyMap()
        assertFalse(registry.hasActiveSessions())

        vmFlow.value = SystemVmManager.VmState(
            diskPath = "/tmp/win11.qcow2",
            status = SystemVmManager.Status.STARTING,
            arch = VmArch.X86_64,
            vncPort = 5909,
        )
        assertTrue(registry.hasActiveSessions())
    }
}
