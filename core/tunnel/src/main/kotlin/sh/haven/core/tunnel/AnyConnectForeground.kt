package sh.haven.core.tunnel

import android.content.Context
import android.content.Intent
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.ssh.ForegroundKeepAlive
import sh.haven.core.ssh.ForegroundReviveHook
import sh.haven.core.ssh.ForegroundSessionInfo
import sh.haven.core.ssh.ForegroundSessionParticipant
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshConnectionService
import javax.inject.Inject
import javax.inject.Singleton

private data class AnyConnectSessionInfo(
    override val profileId: String,
    override val label: String,
) : ForegroundSessionInfo

/**
 * Plugs live AnyConnect tunnels into Haven's existing foreground-service
 * machinery, which is what makes them behave like Cisco's client:
 *
 *  - **Keep-alive:** while an AnyConnect tunnel is up — in use, or lingering
 *    with "Stay connected" after the last connection closed — the
 *    `specialUse` foreground service stays running, so Android keeps the
 *    process (and the gateway session) alive in the background, and the
 *    ongoing notification lists it ("Disconnect All" closes it).
 *  - **Revive:** the service fires revive hooks on network-available and on
 *    return to the foreground; each live tunnel probes the gateway and
 *    reconnects at once if the network change killed its connection,
 *    instead of waiting 30–90 s for dead-peer detection.
 *
 * All three bindings take [TunnelManager] lazily: [SessionManagerRegistry]
 * depends on the keep-alive set, so an eager edge would risk a cycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnyConnectForegroundModule {

    @Provides
    @IntoSet
    fun anyConnectKeepAlive(manager: Lazy<TunnelManager>): ForegroundKeepAlive =
        object : ForegroundKeepAlive {
            override val isActive: Boolean
                // Only tunnels that outlive their connections need the service
                // on their own account; one that closes with its last SSH
                // session is already covered by that session.
                get() = manager.get().live.value.values.any {
                    it is AnyConnectTunnel && it.lingerAfterRelease && !it.isDead
                }
        }

    @Provides
    @IntoSet
    fun anyConnectParticipant(manager: Lazy<TunnelManager>): ForegroundSessionParticipant =
        object : ForegroundSessionParticipant {
            override val activeSessions: List<ForegroundSessionInfo>
                get() = manager.get().live.value.mapNotNull { (id, tunnel) ->
                    (tunnel as? AnyConnectTunnel)?.takeUnless { it.isDead }?.let {
                        AnyConnectSessionInfo(
                            profileId = "anyconnect:$id",
                            label = "VPN: ${it.label.ifBlank { "AnyConnect" }}",
                        )
                    }
                }

            // Leaves the gateway sessions resumable: "Disconnect All" is about
            // this phone, and signing out would cost an SMS code next time.
            override fun disconnectAll() {
                manager.get().disconnectAllAnyConnectAsync()
            }
        }

    @Provides
    @IntoSet
    fun anyConnectRevive(manager: Lazy<TunnelManager>): ForegroundReviveHook =
        object : ForegroundReviveHook {
            override fun reviveNow() {
                manager.get().reviveAll()
            }
        }
}

/**
 * The Tunnels screen's handle on AnyConnect sessions: disconnect or sign
 * out a live or resumable session, and stop the foreground service when
 * that was the last thing keeping it up (nothing else stops it once the
 * last SSH session is already gone).
 */
@Singleton
class AnyConnectSessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnelManager: TunnelManager,
    private val repository: TunnelConfigRepository,
    private val registry: Lazy<SessionManagerRegistry>,
) {
    /**
     * Disconnect the tunnel for [configId]. With [signOut] the gateway
     * session ends too — needed after editing credentials, and what the user
     * wants from "Sign out"; the next connect signs in afresh. Also ends a
     * resumable session left over from an earlier connection.
     */
    suspend fun disconnect(configId: String, signOut: Boolean) {
        val wasLive = tunnelManager.disconnect(configId, signOut)
        if (signOut && !wasLive) {
            val config = repository.getById(configId)
            if (config?.typeEnum == TunnelConfigType.ANYCONNECT) {
                AnyConnectConfigBlob.parse(config.configText)?.let {
                    withContext(Dispatchers.IO) { AnyConnectTunnel.endSession(it) }
                }
            }
        }
        if (!registry.get().hasActiveSessions()) {
            context.stopService(Intent(context, SshConnectionService::class.java))
        }
    }
}
