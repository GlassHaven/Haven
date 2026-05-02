package sh.haven.core.ssh

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rdp.RdpSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.smb.SmbSessionManager

private data class SessionInfo(
    override val profileId: String,
    override val label: String,
) : ForegroundSessionInfo

@Module
@InstallIn(SingletonComponent::class)
object ForegroundSessionParticipantModule {

    @Provides @IntoSet
    fun ssh(m: SshSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions: List<ForegroundSessionInfo>
            get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun reticulum(m: ReticulumSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions: List<ForegroundSessionInfo>
            get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun mosh(m: MoshSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions: List<ForegroundSessionInfo>
            get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun et(m: EtSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions: List<ForegroundSessionInfo>
            get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun local(m: LocalSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions: List<ForegroundSessionInfo>
            get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun rdp(m: RdpSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions: List<ForegroundSessionInfo>
            get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override fun disconnectAll() = m.disconnectAll()
    }

    @Provides @IntoSet
    fun smb(m: SmbSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions: List<ForegroundSessionInfo>
            get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override fun disconnectAll() = m.disconnectAll()
    }
}
