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
                val profileId = rdpProfileId
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = "SSH tunnel: ${e.message}")
                }
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
                val profileId = rdpProfileId
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
            }
        }
    }

    private suspend fun doConnect(
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
            // Log the connection with any verbose data captured during start()
            val profileId = rdpProfileId
            if (profileId != null) {
                val startLog = session.drainVerboseLog()
                connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RDP session.start() threw", e)
            _error.value = describeError(e, host, port)
            val profileId = rdpProfileId
            if (profileId != null) {
                val failLog = session.drainVerboseLog()
                connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message, verboseLog = failLog)
            }
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
        /**
         * Render a Rust-side rustls failure message into something a human can act on.
         * The Rust layer (`rdp-kotlin/rust/src/lib.rs::diagnose_tls_error`) already
         * names the specific failure mode; we re-shape it for the end user and tack
         * on a hint about what to try server-side. (#109)
         */
        private fun describeTlsFailure(msg: String): String {
            val classified = when {
                msg.contains("no shared TLS parameters", ignoreCase = true) ||
                    msg.contains("PeerIncompatible", ignoreCase = true) -> buildString {
                    append("TLS negotiation failed: no shared cipher / key-exchange / signature with the server.\n\n")
                    append("Haven uses the rustls/ring TLS stack, which supports a narrower cipher set than ")
                    append("Microsoft's Windows RDP client (SChannel) or OpenSSL. The most compatible server-side ")
                    append("setting is ECDHE-RSA + AES-GCM over TLS 1.2 or 1.3.")
                }
                msg.contains("HandshakeFailure", ignoreCase = true) -> buildString {
                    append("TLS handshake failed: server rejected our cipher list.\n\n")
                    append("This usually means the server has no cipher suite in common with Haven. ")
                    append("Enable ECDHE-RSA + AES-GCM (TLS 1.2 or 1.3) on the server, or check the server's RDP TLS configuration.")
                }
                msg.contains("certificate problem", ignoreCase = true) ||
                    msg.contains("InvalidCertificate", ignoreCase = true) -> buildString {
                    append("TLS handshake failed: server certificate problem.\n\n")
                    append("Detail: $msg")
                }
                msg.contains("ALPN", ignoreCase = true) -> buildString {
                    append("TLS negotiation failed: server requires an application protocol Haven doesn't advertise.\n\n")
                    append("Detail: $msg")
                }
                else -> "TLS negotiation failed.\n\nDetail: $msg"
            }
            return classified + "\n\nIf this persists please file an issue at https://github.com/GlassHaven/Haven/issues with the message above."
        }

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
                        // CredSSP MessageAltered = pub_key_auth hash mismatch.
                        // Most often caused by gnome-remote-desktop /
                        // FreeRDP-server interop bugs. The fix is server-side
                        // (uncheck NLA on the connection profile).
                        msg.contains("MessageAltered", ignoreCase = true) ||
                            msg.contains("public-key hash", ignoreCase = true) -> buildString {
                            append("Authentication failed: server rejected the CredSSP public-key hash.\n\n")
                            append("This is usually a server-side interop bug — Linux gnome-remote-desktop ")
                            append("and certain xrdp builds compute the hash differently than Haven's ironrdp/sspi-rs.\n\n")
                            append("Workaround: edit this connection profile and uncheck \"Network Level Authentication (NLA)\". ")
                            append("Without NLA, RDP authenticates after channel setup instead — slower but unaffected by this mismatch.\n\n")
                            append("Detail: $msg")
                        }
                        msg.contains("LogonDenied", ignoreCase = true) ||
                            msg.contains("wrong username", ignoreCase = true) -> buildString {
                            append("Authentication failed: wrong username or password.\n\n")
                            append("For Linux/xrdp the credentials should match a system account; ")
                            append("Domain can usually be left empty.\n\nDetail: $msg")
                        }
                        msg.contains("TimeSkew", ignoreCase = true) -> buildString {
                            append("Authentication failed: clock skew with server is too large.\n\n")
                            append("Check that the device's clock is correct (Settings → Date & time → Set automatically).\n\n")
                            append("Detail: $msg")
                        }
                        msg.contains("(CredSSP)", ignoreCase = true) ||
                            msg.contains("Credssp", ignoreCase = true) -> buildString {
                            append("Authentication failed during CredSSP/NLA.\n\n")
                            append("Detail: $msg\n\n")
                            append("If this is a Linux server, try unchecking \"Network Level Authentication (NLA)\" in the connection profile.")
                        }
                        msg.contains("Authentication", ignoreCase = true) -> buildString {
                            append("Authentication failed.\n\n")
                            append("Check your username and password.\n")
                            append("For xrdp, the username/password should match a system account.\n")
                            append("Domain can usually be left empty for Linux/xrdp connections.\n\n")
                            append("Detail: $msg")
                        }
                        msg.contains("TLS", ignoreCase = true) || msg.contains("SSL", ignoreCase = true) ->
                            describeTlsFailure(msg)
                        msg.contains("RDP security negotiation", ignoreCase = true) -> buildString {
                            append("RDP security negotiation failed.\n\n")
                            append("Detail: $msg")
                        }
                        else -> msg
                    }
                }
            }
        }
    }
}
