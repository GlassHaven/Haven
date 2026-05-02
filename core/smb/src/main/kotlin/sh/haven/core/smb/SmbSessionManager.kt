package sh.haven.core.smb

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SmbSessionManager"

@Singleton
class SmbSessionManager @Inject constructor() {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val host: String = "",
        val port: Int = 445,
        val shareName: String = "",
        val username: String = "",
        val password: String = "",
        val domain: String = "",
        val smbClient: SmbClient? = null,
        /** SSH client kept alive for tunneled connections (opaque Closeable). */
        val sshClient: Closeable? = null,
        /** Local port for SSH tunnel, if tunneled. */
        val tunnelPort: Int? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "smb-session-io").apply { isDaemon = true }
    }

    fun registerSession(profileId: String, label: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
            ))
        }
        return sessionId
    }

    fun connectSession(
        sessionId: String,
        host: String,
        port: Int,
        shareName: String,
        username: String,
        password: String,
        domain: String = "",
        sshClient: Closeable? = null,
        tunnelPort: Int? = null,
    ) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        Log.d(TAG, "Connecting SMB session: \\\\$host\\$shareName as $username (ssh tunnel: ${sshClient != null})")

        val client = SmbClient()
        try {
            val connectHost = if (tunnelPort != null) "127.0.0.1" else host
            val connectPort = tunnelPort ?: port
            client.connect(connectHost, connectPort, shareName, username, password, domain)

            _sessions.update { map ->
                val existing = map[sessionId] ?: return@update map
                map + (sessionId to existing.copy(
                    status = SessionState.Status.CONNECTED,
                    host = host,
                    port = port,
                    shareName = shareName,
                    username = username,
                    password = password,
                    domain = domain,
                    smbClient = client,
                    sshClient = sshClient,
                    tunnelPort = tunnelPort,
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMB connection failed", e)
            client.close()
            _sessions.update { map ->
                val existing = map[sessionId] ?: return@update map
                map + (sessionId to existing.copy(status = SessionState.Status.ERROR, password = ""))
            }
            throw e
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            // Clear password from memory when session is no longer active
            val clearPwd = status == SessionState.Status.DISCONNECTED ||
                status == SessionState.Status.ERROR
            map + (sessionId to existing.copy(
                status = status,
                password = if (clearPwd) "" else existing.password,
            ))
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute {
            try {
                session.smbClient?.close()
                session.sshClient?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { session ->
                try {
                    session.smbClient?.close()
                    session.sshClient?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun disconnectAll() {
        val all = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            all.forEach { session ->
                try {
                    session.smbClient?.close()
                    session.sshClient?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun getClientForProfile(profileId: String): SmbClient? =
        _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.smbClient

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }
}
