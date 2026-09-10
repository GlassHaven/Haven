package sh.haven.app.transport

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import sh.haven.core.bleserial.BleSerialSessionManager
import sh.haven.core.btserial.BtSerialSessionManager
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mail.MailSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rclone.RcloneSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.Transport
import sh.haven.core.ssh.TransportSessionManager
import sh.haven.core.local.uml.UmlGuestManager
import sh.haven.core.usbserial.UsbSerialSessionManager

/**
 * Binds each transport into [sh.haven.core.ssh.SessionManagerRegistry].
 *
 * These adapters live in `:app` rather than `:core:ssh` because `:app` is the
 * module that already depends on every transport. Keeping them here is what
 * lets `:core:ssh` stop depending on `:core:rdp` — and so what lets a build
 * variant leave the remote-desktop transports out entirely (#510). RDP is
 * bound separately in the flavour-specific `DesktopTransportModule`.
 *
 * Each adapter answers only what its manager actually supports; the interface
 * defaults cover the rest.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportSessionManagerModule {

    @Provides @IntoSet
    fun ssh(m: SshSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.SSH
        override val inputName = "SSH"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        // Only SSH carries a session-manager (tmux/zellij/screen) name; other
        // transports have no equivalent, so their sessionName stays null.
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(
                    it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.SSH,
                    it.chosenSessionName,
                    sessionManagerLabel = it.sessionManager.takeIf { sm -> sm != SessionManager.NONE }?.label,
                )
            }
    }

    @Provides @IntoSet
    fun reticulum(m: ReticulumSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.RETICULUM
        override val inputName = "Reticulum"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.RETICULUM)
            }
    }

    @Provides @IntoSet
    fun mosh(m: MoshSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.MOSH
        override val inputName = "mosh"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.MOSH)
            }
    }

    @Provides @IntoSet
    fun et(m: EtSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.ET
        override val inputName = "ET"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.ET)
            }
    }

    @Provides @IntoSet
    fun btSerial(m: BtSerialSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.BTSERIAL
        override val inputName = "Bluetooth-serial"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.BTSERIAL)
            }
    }

    @Provides @IntoSet
    fun bleSerial(m: BleSerialSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.BLESERIAL
        override val inputName = "BLE-serial"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.BLESERIAL)
            }
    }

    @Provides @IntoSet
    fun usbSerial(m: UsbSerialSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.USBSERIAL
        override val inputName = "USB-serial"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.USBSERIAL)
            }
    }

    @Provides @IntoSet
    fun local(m: LocalSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.LOCAL
        override val inputName = "local"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.LOCAL)
            }
    }

    @Provides @IntoSet
    fun guest(m: UmlGuestManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.GUEST
        override val inputName = "guest"
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override fun sendInput(sessionId: String, text: String) = m.sendInput(sessionId, text)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.GUEST)
            }
    }

    // No sendInput: SMB is a file share, not a terminal.
    @Provides @IntoSet
    fun smb(m: SmbSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.SMB
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.SMB)
            }
    }

    @Provides @IntoSet
    fun mail(m: MailSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.MAIL
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.MAIL)
            }
    }

    /**
     * Disconnect-only. rclone remotes are storage handles, not live transport
     * sessions — they don't keep the FGS alive and the connections UI reads
     * RcloneSessionManager's flow directly, so rclone is deliberately absent
     * from hasActiveSessions/allSessions (it leaves [sessions] and
     * [activeSessionCount] at their inert defaults). Before this wiring,
     * disconnecting an rclone profile was a silent no-op and the card stayed
     * CONNECTED forever (#363).
     */
    @Provides @IntoSet
    fun rclone(m: RcloneSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.RCLONE
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
    }
}
