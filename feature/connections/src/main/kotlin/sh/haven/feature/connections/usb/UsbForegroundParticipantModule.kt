package sh.haven.feature.connections.usb

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import sh.haven.core.session.ForegroundSessionInfo
import sh.haven.core.session.ForegroundSessionParticipant
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbIpServer
import sh.haven.core.usb.UsbRedirServer

/**
 * Contributes USB/IP export cleanup to the foreground-session participant set.
 *
 * A USB export is not a notification-listed session ([activeSessions] is always
 * empty), but [ForegroundSessionParticipant.disconnectAll] — invoked on
 * "Disconnect All" and on `SshConnectionService` teardown — is the single hook
 * that stops the export and releases the brokered device handle, so an open
 * connection doesn't leak when the foreground service is destroyed. ([UsbBroker]
 * already drops a stale handle on physical detach; this covers process/FGS death
 * while a device is still attached.)
 *
 * The binding lives here rather than in core:ssh's `ForegroundSessionParticipantModule`
 * because core:ssh does not depend on core:usb; Hilt aggregates @IntoSet
 * contributions across modules into the same `Set<ForegroundSessionParticipant>`.
 */
@Module
@InstallIn(SingletonComponent::class)
object UsbForegroundParticipantModule {
    @Provides
    @IntoSet
    fun usb(
        broker: UsbBroker,
        server: UsbIpServer,
        redirServer: UsbRedirServer,
    ): ForegroundSessionParticipant =
        object : ForegroundSessionParticipant {
            override val activeSessions: List<ForegroundSessionInfo> = emptyList()
            override fun disconnectAll() {
                runCatching { server.stop() }
                runCatching { redirServer.stop() }
                runCatching { broker.closeAll() }
            }
        }
}
