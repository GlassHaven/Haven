package sh.haven.app.transport

import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import sh.haven.core.bleserial.BleSerialSessionManager
import sh.haven.core.btserial.BtSerialSessionManager
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.SystemVmManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.local.uml.UmlGuestManager
import sh.haven.core.session.ForegroundKeepAlive
import sh.haven.core.session.ForegroundSessionInfo
import sh.haven.core.session.ForegroundSessionParticipant
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.usbserial.UsbSerialSessionManager

/**
 * Ongoing notification entry for an in-flight PRoot rootfs or desktop package
 * installation (#832), or null when setup is idle / completed / errored.
 */
internal fun prootSetupSessionInfo(
    distroLabel: String,
    rootfsState: ProotManager.SetupState,
    desktopState: ProotManager.DesktopSetupState,
): ForegroundSessionInfo? {
    if (desktopState is ProotManager.DesktopSetupState.Installing) {
        val pct = desktopState.progress?.let { " (${(it * 100).toInt()}%)" }.orEmpty()
        val step = desktopState.step.ifBlank { "Installing desktop…" }
        return SessionInfo("proot-setup", "$step$pct")
    }
    return when (rootfsState) {
        is ProotManager.SetupState.Downloading ->
            SessionInfo("proot-setup", "Installing $distroLabel: downloading (${rootfsState.progress}%)")
        ProotManager.SetupState.Extracting ->
            SessionInfo("proot-setup", "Installing $distroLabel: extracting rootfs…")
        is ProotManager.SetupState.Initializing -> {
            val pct = rootfsState.progress?.let { " (${(it * 100).toInt()}%)" }.orEmpty()
            SessionInfo("proot-setup", "Installing $distroLabel: ${rootfsState.step}$pct")
        }
        ProotManager.SetupState.NotInstalled,
        ProotManager.SetupState.Ready,
        is ProotManager.SetupState.Error,
        -> null
    }
}

/**
 * True when any non-`TransportSessionManager` local workload (PRoot setup,
 * running PRoot desktop, or running System VM) needs [sh.haven.core.ssh.SshConnectionService]
 * kept alive (#832).
 */
internal fun isLocalWorkForegroundActive(
    rootfsState: ProotManager.SetupState,
    desktopSetupState: ProotManager.DesktopSetupState,
    desktops: Map<ProotManager.DesktopEnvironment, DesktopManager.DesktopInstance>,
    systemVm: SystemVmManager.VmState?,
): Boolean {
    if (prootSetupSessionInfo("", rootfsState, desktopSetupState) != null) return true
    if (desktops.values.any {
            it.state == DesktopManager.DesktopState.RUNNING ||
                it.state == DesktopManager.DesktopState.STARTING
        }
    ) {
        return true
    }
    return systemVm?.status == SystemVmManager.Status.RUNNING ||
        systemVm?.status == SystemVmManager.Status.STARTING
}

/**
 * Which transports count as "active connections" in the foreground
 * notification.
 *
 * Moved here from `:core:ssh` with the registry adapters (#510) — it named
 * `RdpSessionManager` directly, which was one of the two reasons `:core:ssh`
 * had a compile dependency on `:core:rdp`. RDP's participant now lives in
 * [DesktopTransportModule] so both of its registrations drop out of a
 * terminal-only build together.
 */
@Module
@InstallIn(SingletonComponent::class)
object ForegroundSessionParticipantModule {

    @Provides @IntoSet
    fun ssh(m: SshSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun reticulum(m: ReticulumSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun mosh(m: MoshSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun et(m: EtSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun local(m: LocalSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun smb(m: SmbSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun guest(m: UmlGuestManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun btSerial(m: BtSerialSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun bleSerial(m: BleSerialSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun usbSerial(m: UsbSerialSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun prootDesktops(m: DesktopManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions
            get() = m.desktops.value.values
                .filter {
                    it.state == DesktopManager.DesktopState.RUNNING ||
                        it.state == DesktopManager.DesktopState.STARTING
                }
                .map { SessionInfo("desktop-${it.de.spec.id}", "Local Desktop (${it.de.spec.label})") }
        override val changes get() = m.desktops.map { }
        override fun disconnectAll() = m.stopAll()
    }

    @Provides @IntoSet
    fun systemVm(m: SystemVmManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions
            get() = listOfNotNull(
                m.state.value?.takeIf {
                    it.status == SystemVmManager.Status.RUNNING ||
                        it.status == SystemVmManager.Status.STARTING
                }?.let { SessionInfo("system-vm", "System VM (${it.arch.name.lowercase()})") },
            )
        override val changes get() = m.state.map { }
        override fun disconnectAll() = m.stopAsync()
    }

    @Provides @IntoSet
    fun prootSetup(m: ProotManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions
            get() = listOfNotNull(
                prootSetupSessionInfo(
                    distroLabel = m.activeDistro.label,
                    rootfsState = m.state.value,
                    desktopState = m.desktopState.value,
                ),
            )
        override val changes get() = merge(m.state.map { }, m.desktopState.map { })
        override fun disconnectAll() {}
    }

    /**
     * Keep-alive contributor for non-[sh.haven.core.session.TransportSessionManager]
     * local workloads (in-flight PRoot setup, running PRoot desktops, and
     * running System VMs) so [sh.haven.core.session.SessionManagerRegistry.hasActiveSessions]
     * stays true and disconnecting an unrelated SSH profile does not stop the
     * foreground service mid-install (#832).
     */
    @Provides @IntoSet
    fun localWorkKeepAlive(
        proot: Lazy<ProotManager>,
        desktops: Lazy<DesktopManager>,
        systemVm: Lazy<SystemVmManager>,
    ): ForegroundKeepAlive = object : ForegroundKeepAlive {
        override val isActive: Boolean
            get() = isLocalWorkForegroundActive(
                rootfsState = proot.get().state.value,
                desktopSetupState = proot.get().desktopState.value,
                desktops = desktops.get().desktops.value,
                systemVm = systemVm.get().state.value,
            )
        override val changes
            get() = merge(
                proot.get().state.map { },
                proot.get().desktopState.map { },
                desktops.get().desktops.map { },
                systemVm.get().state.map { },
            )
    }
}
