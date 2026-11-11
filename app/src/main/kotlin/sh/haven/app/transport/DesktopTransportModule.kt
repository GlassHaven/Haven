package sh.haven.app.transport

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.map
import sh.haven.core.data.desktop.DesktopSessionRegistry
import sh.haven.core.data.desktop.DesktopStatus
import sh.haven.core.ssh.ForegroundSessionParticipant
import sh.haven.core.ssh.Transport
import sh.haven.core.ssh.TransportSessionManager

/**
 * Remote-desktop (`VNC` / `RDP` / `SPICE` / `GLKVM`) bindings into
 * [sh.haven.core.ssh.SessionManagerRegistry] and [ForegroundSessionParticipant],
 * backed by the live [DesktopSessionRegistry] populated by `DesktopViewModel`.
 *
 * Kept apart from [TransportSessionManagerModule] so a terminal-only build
 * variant can omit remote-desktop registrations cleanly (#510).
 */
@Module
@InstallIn(SingletonComponent::class)
object DesktopTransportModule {

    private fun isActiveStatus(status: DesktopStatus): Boolean =
        status == DesktopStatus.CONNECTED || status == DesktopStatus.CONNECTING

    // No sendInput: desktop sessions route input through their own protocol handles, not a PTY.
    @Provides @IntoSet
    fun rdpTransport(registry: DesktopSessionRegistry): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.RDP
        override fun removeAllSessionsForProfile(profileId: String) = registry.close(profileId)
        override val activeSessionCount: Int
            get() = registry.statuses.value.values.count { isActiveStatus(it) }
        override val sessions
            get() = registry.statuses.value.map { (profileId, status) ->
                UnifiedSession(
                    sessionId = "desktop-$profileId",
                    profileId = profileId,
                    label = registry.label(profileId) ?: profileId,
                    status = mapStatus(status.name),
                    transport = Transport.RDP,
                )
            }
    }

    @Provides @IntoSet
    fun rdpForeground(registry: DesktopSessionRegistry): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions
            get() = registry.statuses.value
                .filterValues { isActiveStatus(it) }
                .map { (profileId, _) -> SessionInfo(profileId, registry.label(profileId) ?: profileId) }
        override val changes get() = registry.statuses.map { }
        override fun disconnectAll() = registry.closeAll()
    }
}
