package sh.haven.core.mosh

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sh.haven.mosh.network.UdpSocketProvider
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MoshSessionManager"

/**
 * Manages active Mosh sessions across the app.
 * Parallel to ReticulumSessionManager: simple connect/disconnect lifecycle,
 * no reconnect logic (mosh handles roaming internally over UDP).
 *
 * Uses pure Kotlin MoshTransport — no native binary or PTY needed.
 */
@Singleton
class MoshSessionManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val serverIp: String = "",
        val moshPort: Int = 0,
        val moshKey: String = "",
        val moshSession: MoshSession? = null,
        /** Shell command to run after mosh connects (e.g. session manager attach). */
        val initialCommand: String? = null,
        /** SSH client kept alive from bootstrap for SFTP access (opaque Closeable). */
        val sshClient: Closeable? = null,
        /** Buffer for capturing transport logs when verbose logging is enabled. */
        val verboseBuffer: ConcurrentLinkedQueue<String>? = null,
        /**
         * UDP socket factory resolved at connect time. When non-null, the
         * transport routes packets through it instead of a raw
         * [java.net.DatagramSocket] — used for Mosh-over-tunnel sessions
         * per #164. null = direct UDP (pre-#164 behaviour).
         */
        val socketProvider: UdpSocketProvider? = null,
        /**
         * Per-profile session-dead timeout in milliseconds. Null = use
         * the SSP transport hardcoded default (120s). Set from
         * ConnectionProfile.moshSessionTimeoutSec × 1000.
         */
        val sessionDeadMs: Long? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mosh-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    /**
     * Register a new session. Returns the generated sessionId.
     */
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

    /**
     * Connect a registered session using the pure Kotlin mosh transport.
     * Stores connection parameters; the actual transport starts when
     * [createTerminalSession] is called.
     */
    fun connectSession(
        sessionId: String,
        serverIp: String,
        moshPort: Int,
        moshKey: String,
        cols: Int,
        rows: Int,
        sshClient: Closeable? = null,
        verboseBuffer: ConcurrentLinkedQueue<String>? = null,
        socketProvider: UdpSocketProvider? = null,
        sessionDeadMs: Long? = null,
    ) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        Log.d(
            TAG,
            "Connecting mosh session: $serverIp:$moshPort " +
                "(ssh kept alive: ${sshClient != null}, " +
                "tunneled: ${socketProvider != null})",
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                serverIp = serverIp,
                moshPort = moshPort,
                moshKey = moshKey,
                sshClient = sshClient,
                verboseBuffer = verboseBuffer,
                socketProvider = socketProvider,
                sessionDeadMs = sessionDeadMs,
            ))
        }
    }

    /**
     * Get the SSH client for a connected mosh profile (for SFTP access).
     * Returns the opaque Closeable — caller must cast to SshClient.
     */
    fun getSshClientForProfile(profileId: String): Closeable? {
        return _sessions.value.values
            .filter { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            .firstOrNull { it.sshClient != null }
            ?.sshClient
    }

    /**
     * Create a [MoshSession] for a connected session.
     * The transport starts immediately and terminal output flows via [onDataReceived].
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): MoshSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.moshSession != null) return null
        if (session.serverIp.isEmpty()) return null

        val moshSession = MoshSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            serverIp = session.serverIp,
            moshPort = session.moshPort,
            moshKey = session.moshKey,
            onDataReceived = onDataReceived,
            onDisconnected = { cleanExit ->
                // Mosh's UDP transport handles roaming internally — short
                // network flips don't reach this callback. When this DOES
                // fire it's because the UDP path gave up after SESSION_DEAD_MS.
                //
                // If cleanExit=false (transport error) and we still have valid
                // connection params, transition to DISCONNECTED so the caller
                // can attempt reconnectTerminalSession() with the SAME key.
                // The server-side mosh-server is still alive (7-day TTL).
                //
                // If cleanExit=true, the server shut down cleanly — no reconnect.
                Log.d(TAG, "Mosh session $sessionId disconnected — " +
                    "clean=$cleanExit, server=${session.serverIp}:${session.moshPort}")
                updateStatus(sessionId, SessionState.Status.DISCONNECTED)
            },
            verboseBuffer = session.verboseBuffer,
            socketProvider = session.socketProvider
                ?: UdpSocketProvider { sh.haven.mosh.network.AndroidUdpAdapter() },
            sessionDeadMs = session.sessionDeadMs,
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(moshSession = moshSession))
        }

        return moshSession
    }

    /**
     * Check if a session is ready for a terminal to be attached.
     * Accepts CONNECTED (first attach) or DISCONNECTED (reconnect with saved key).
     */
    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return (session.status == SessionState.Status.CONNECTED ||
                session.status == SessionState.Status.DISCONNECTED) &&
            session.moshSession == null &&
            session.serverIp.isNotEmpty()
    }

    /**
     * Detach a terminal session without killing the mosh transport.
     */
    fun detachTerminalSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        session.moshSession?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(moshSession = null))
        }
    }

    /**
     * Reconnect a DISCONNECTED mosh session using the saved key and port.
     *
     * This is the "mosh attach" path: when SESSION_DEAD_MS fires and the
     * UDP transport gives up, the server-side mosh-server is still alive
     * (it lives 7 days). We can re-establish the UDP connection with the
     * SAME key and port — the SSP (State Synchronization Protocol) will
     * sync the client state to whatever the server has.
     *
     * Returns the new MoshSession or null if the session was not eligible.
     */
    fun reconnectTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): MoshSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.DISCONNECTED) return null
        if (session.serverIp.isEmpty() || session.moshPort == 0 || session.moshKey.isEmpty()) {
            Log.w(TAG, "Cannot reconnect session $sessionId: missing connection params")
            return null
        }

        Log.d(TAG, "Reconnecting mosh session $sessionId to ${session.serverIp}:${session.moshPort}")

        updateStatus(sessionId, SessionState.Status.CONNECTING)

        val moshSession = MoshSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            serverIp = session.serverIp,
            moshPort = session.moshPort,
            moshKey = session.moshKey,
            onDataReceived = onDataReceived,
            onDisconnected = { cleanExit ->
                Log.d(TAG, "Mosh session $sessionId reconnect lost (clean=$cleanExit)")
                updateStatus(sessionId, SessionState.Status.DISCONNECTED)
            },
            socketProvider = session.socketProvider
                ?: UdpSocketProvider { sh.haven.mosh.network.AndroidUdpAdapter() },
            sessionDeadMs = session.sessionDeadMs,
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                moshSession = moshSession,
            ))
        }

        return moshSession
    }

    fun setInitialCommand(sessionId: String, command: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(initialCommand = command))
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute {
            try {
                session.sshClient?.close()
                session.moshSession?.close()
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
                    session.sshClient?.close()
                    session.moshSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            snapshot.forEach { session ->
                try {
                    session.sshClient?.close()
                    session.moshSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    fun getProfileStatus(profileId: String): SessionState.Status? {
        val statuses = _sessions.value.values
            .filter { it.profileId == profileId }
            .map { it.status }
        if (statuses.isEmpty()) return null
        return when {
            SessionState.Status.CONNECTED in statuses -> SessionState.Status.CONNECTED
            SessionState.Status.CONNECTING in statuses -> SessionState.Status.CONNECTING
            SessionState.Status.ERROR in statuses -> SessionState.Status.ERROR
            else -> SessionState.Status.DISCONNECTED
        }
    }
}
