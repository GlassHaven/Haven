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
 * no reconnect logic — the transport itself survives network outages of
 * any length (it keeps re-syncing against the same key/port and rebinds
 * its socket for IP roaming), so a DISCONNECTED status here always means
 * the session is genuinely over (shell exited or fatal transport error).
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
         * How the session reached DISCONNECTED: true when the server
         * announced shutdown (shell exited — the mosh-server is gone, so
         * any saved re-attach tuple for the profile is now useless and
         * gets wiped, #371); false for a fatal local transport error
         * (the server may still be alive, so the tuple is kept for a
         * later re-attach). Meaningless while the session is live.
         */
        val cleanExit: Boolean = false,
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
                // The transport never gives up on network silence — it
                // retries with the same (port, key) until connectivity
                // returns and SSP re-syncs, so outages of any length
                // never reach this callback. It fires only when the
                // server announced shutdown (shell exited, clean=true)
                // or the transport hit a fatal local error (clean=false).
                // Either way the session is over; DISCONNECTED removes
                // the tab.
                Log.d(TAG, "Mosh session $sessionId disconnected — " +
                    "clean=$cleanExit, server=${session.serverIp}:${session.moshPort}")
                _sessions.update { map ->
                    val existing = map[sessionId] ?: return@update map
                    map + (sessionId to existing.copy(
                        status = SessionState.Status.DISCONNECTED,
                        cleanExit = cleanExit,
                    ))
                }
            },
            verboseBuffer = session.verboseBuffer,
            socketProvider = session.socketProvider
                ?: UdpSocketProvider { sh.haven.mosh.network.AndroidUdpAdapter() },
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(moshSession = moshSession))
        }

        return moshSession
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
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

    /**
     * Send [text] as UTF-8 to the mosh session's input. Throws when no
     * session exists or its transport is gone — matches the
     * SshSessionManager/LocalSessionManager sendInput contract so the
     * agent input dispatcher can chain transports (#366).
     */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("No mosh session: $sessionId")
        val mosh = session.moshSession
            ?: throw IllegalStateException("Mosh session $sessionId has no live transport")
        mosh.sendInput(text.toByteArray(Charsets.UTF_8))
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
