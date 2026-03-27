package sh.haven.feature.rdp

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.rdp.RdpSession
import sh.haven.core.rdp.RdpSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.rdp.MouseButton
import kotlinx.coroutines.flow.first
import java.net.ConnectException
import java.util.concurrent.ConcurrentLinkedQueue
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

private const val TAG = "RdpViewModel"

/** An active SSH session that can be used for tunneling. */
data class SshTunnelOption(
    val sessionId: String,
    val label: String,
    val profileId: String,
)

@HiltViewModel
class RdpViewModel @Inject constructor(
    private val rdpSessionManager: RdpSessionManager,
    private val sshSessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val connectionLogRepository: ConnectionLogRepository,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var rdpSession: RdpSession? = null
    private var rdpVerboseBuffer: ConcurrentLinkedQueue<String>? = null
    private var rdpProfileId: String? = null
    private var tunnelPort: Int? = null
    private var tunnelSessionId: String? = null

    /** List active sessions with SSH clients available for tunneling. */
    fun getActiveSshSessions(): List<SshTunnelOption> {
        val ssh = sshSessionManager.activeSessions.map { session ->
            SshTunnelOption(
                sessionId = session.sessionId,
                label = session.label,
                profileId = session.profileId,
            )
        }
        val mosh = moshSessionManager.activeSessions
            .filter { it.sshClient != null }
            .map { session ->
                SshTunnelOption(
                    sessionId = session.sessionId,
                    label = "${session.label} (Mosh)",
                    profileId = session.profileId,
                )
            }
        val et = etSessionManager.activeSessions
            .filter { it.sshClient != null }
            .map { session ->
                SshTunnelOption(
                    sessionId = session.sessionId,
                    label = "${session.label} (ET)",
                    profileId = session.profileId,
                )
            }
        return ssh + mosh + et
    }

    /**
     * Connect RDP through an SSH tunnel.
     * Creates a local port forward and connects RDP to localhost.
     */
    fun connectViaSsh(
        sessionId: String,
        remoteHost: String,
        remotePort: Int,
        username: String,
        password: String,
        domain: String = "",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _error.value = null
                val client = findSshClient(sessionId)
                    ?: throw IllegalStateException(
                        "SSH session not found. Return to the Terminal tab and check the connection is still active."
                    )
                val localPort = client.setPortForwardingL(
                    "127.0.0.1", 0, remoteHost, remotePort,
                )
                tunnelPort = localPort
                tunnelSessionId = sessionId
                Log.d(TAG, "SSH tunnel: localhost:$localPort -> $remoteHost:$remotePort")
                doConnect("127.0.0.1", localPort, username, password, domain)
            } catch (e: Exception) {
                Log.e(TAG, "SSH tunnel setup failed", e)
                _error.value = describeError(e, remoteHost, remotePort)
            }
        }
    }

    /** Set the connection profile ID for logging. Call before connect(). */
    fun setProfileId(profileId: String) {
        rdpProfileId = profileId
    }

    fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String = "",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _error.value = null
                doConnect(host, port, username, password, domain)
            } catch (e: Exception) {
                Log.e(TAG, "RDP connect failed", e)
                _error.value = describeError(e, host, port)
            }
        }
    }

    private fun doConnect(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
    ) {
        Log.d(TAG, "doConnect: $host:$port user=$username domain=$domain")
        val verboseEnabled = kotlinx.coroutines.runBlocking { preferencesRepository.verboseLoggingEnabled.first() }
        val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null
        rdpVerboseBuffer = verboseBuffer
        val session = RdpSession(
            sessionId = "rdp-${System.currentTimeMillis()}",
            host = host,
            port = port,
            username = username,
            password = password,
            domain = domain,
            verboseBuffer = verboseBuffer,
        )

        session.onFrameUpdate = { bitmap ->
            _frame.value = bitmap
        }
        session.onError = { e ->
            Log.e(TAG, "RDP error", e)
            _error.value = describeError(e, host, port)
            _connected.value = false
        }

        rdpSession = session
        try {
            session.start()
            _connected.value = true
            Log.d(TAG, "RDP session started")
        } catch (e: Exception) {
            Log.e(TAG, "RDP session.start() threw", e)
            _error.value = describeError(e, host, port)
            rdpSession = null
        }
    }

    /** Find the SSH client for a session across all session managers. */
    private fun findSshClient(sessionId: String): SshClient? {
        sshSessionManager.getSession(sessionId)?.let { return it.client }
        moshSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        etSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        return null
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            val verboseLog = rdpSession?.drainVerboseLog()
            rdpSession?.close()
            rdpSession = null
            val profileId = rdpProfileId
            if (profileId != null) {
                connectionLogRepository.logEvent(profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                rdpProfileId = null
            }
            // Tear down SSH tunnel if one was created
            val tp = tunnelPort
            val tsId = tunnelSessionId
            if (tp != null && tsId != null) {
                try {
                    findSshClient(tsId)?.delPortForwardingL("127.0.0.1", tp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove SSH tunnel", e)
                }
                tunnelPort = null
                tunnelSessionId = null
            }
            _connected.value = false
            _frame.value = null
        }
    }

    // --- Input forwarding ---

    fun sendPointer(x: Int, y: Int) {
        viewModelScope.launch(Dispatchers.IO) { rdpSession?.sendMouseMove(x, y) }
    }

    fun sendClick(x: Int, y: Int, button: MouseButton = MouseButton.LEFT) {
        viewModelScope.launch(Dispatchers.IO) { rdpSession?.sendMouseClick(x, y, button) }
    }

    fun pressButton(button: MouseButton = MouseButton.LEFT) {
        viewModelScope.launch(Dispatchers.IO) { rdpSession?.sendMouseButton(button, true) }
    }

    fun releaseButton(button: MouseButton = MouseButton.LEFT) {
        viewModelScope.launch(Dispatchers.IO) { rdpSession?.sendMouseButton(button, false) }
    }

    fun sendKey(scancode: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { rdpSession?.sendKey(scancode, pressed) }
    }

    fun typeKey(scancode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            rdpSession?.sendKey(scancode, true)
            rdpSession?.sendKey(scancode, false)
        }
    }

    fun typeUnicode(codepoint: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            rdpSession?.sendUnicodeKey(codepoint, true)
            rdpSession?.sendUnicodeKey(codepoint, false)
        }
    }

    fun scrollUp() {
        viewModelScope.launch(Dispatchers.IO) { rdpSession?.sendMouseWheel(true, 120) }
    }

    fun scrollDown() {
        viewModelScope.launch(Dispatchers.IO) { rdpSession?.sendMouseWheel(true, -120) }
    }

    fun sendClipboardText(text: String) {
        viewModelScope.launch(Dispatchers.IO) { rdpSession?.sendClipboardText(text) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }

    companion object {
        /** Map RDP/network exceptions to user-friendly messages. */
        fun describeError(e: Exception, host: String? = null, port: Int? = null): String {
            val portStr = port?.toString() ?: "3389"
            val hostStr = host ?: "the remote host"
            return when (e) {
                is ConnectException -> buildString {
                    append("Connection refused")
                    if (e.message?.contains("refused", ignoreCase = true) == true) {
                        append(". No RDP server appears to be listening on $hostStr:$portStr.\n\n")
                        append("Check:\n")
                        append("  - Remote Desktop is enabled on the target machine\n")
                        append("  - Linux: xrdp is installed and running\n")
                        append("  - Port $portStr is not blocked by a firewall\n")
                        append("  - Verify: ss -tlnp | grep $portStr")
                    }
                }
                is SocketTimeoutException -> buildString {
                    append("Connection timed out reaching $hostStr:$portStr.\n\n")
                    append("Check:\n")
                    append("  - Host address is correct\n")
                    append("  - Port $portStr is not blocked by a firewall\n")
                    append("  - If tunneling through SSH, the SSH session is still connected")
                }
                is UnknownHostException ->
                    "Could not resolve hostname \"$hostStr\". Check the address is correct."
                is NoRouteToHostException ->
                    "No route to $hostStr. Check your network connection and that the host is reachable."
                else -> {
                    val msg = e.message ?: "Unknown error"
                    when {
                        msg.contains("Authentication", ignoreCase = true) -> buildString {
                            append("Authentication failed.\n\n")
                            append("Check your username and password.\n")
                            append("For xrdp, the username/password should match a system account.\n")
                            append("Domain can usually be left empty for Linux/xrdp connections.")
                        }
                        msg.contains("TLS", ignoreCase = true) || msg.contains("SSL", ignoreCase = true) ->
                            "TLS negotiation failed. The server may require specific security settings."
                        else -> msg
                    }
                }
            }
        }
    }
}
