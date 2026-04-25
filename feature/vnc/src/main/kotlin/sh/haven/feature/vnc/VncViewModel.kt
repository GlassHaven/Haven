package sh.haven.feature.vnc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncClient
import sh.haven.core.vnc.VncConfig
import sh.haven.core.vnc.protocol.AuthenticationFailedException
import sh.haven.core.vnc.protocol.HandshakingFailedException
import sh.haven.core.vnc.protocol.VncException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

private const val TAG = "VncViewModel"

/** An active SSH session that can be used for tunneling. */
data class SshTunnelOption(
    val sessionId: String,
    val label: String,
    val profileId: String,
)

/** Remote cursor shape, to be drawn at the local pointer position. */
data class CursorOverlay(
    val bitmap: Bitmap,
    val hotspotX: Int,
    val hotspotY: Int,
)

@HiltViewModel
class VncViewModel @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _serverName = MutableStateFlow<String?>(null)
    val serverName: StateFlow<String?> = _serverName.asStateFlow()

    /** Most recent cursor shape from the server, or null if none received. */
    private val _cursor = MutableStateFlow<CursorOverlay?>(null)
    val cursor: StateFlow<CursorOverlay?> = _cursor.asStateFlow()

    /** Current pointer position in framebuffer coords — updated every time we send one. */
    private val _pointerPos = MutableStateFlow(0 to 0)
    val pointerPos: StateFlow<Pair<Int, Int>> = _pointerPos.asStateFlow()

    private var client: VncClient? = null
    private var tunnelPort: Int? = null
    private var tunnelSessionId: String? = null

    fun setActive(active: Boolean) {
        client?.paused = !active
    }

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
     * Connect VNC through an SSH tunnel.
     * Creates a local port forward and connects VNC to localhost.
     */
    fun connectViaSsh(sessionId: String, remoteHost: String, remotePort: Int, password: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _error.value = null
                // Find the SSH client from whichever session manager owns this session
                val client = findSshClient(sessionId)
                    ?: throw IllegalStateException("SSH session not found. Return to the Terminal tab and check the connection is still active.")
                val localPort = client.setPortForwardingL(
                    "127.0.0.1", 0, remoteHost, remotePort,
                )
                tunnelPort = localPort
                tunnelSessionId = sessionId
                Log.d(TAG, "SSH tunnel: localhost:$localPort -> $remoteHost:$remotePort")
                doConnect("127.0.0.1", localPort, password, displayHost = remoteHost, displayPort = remotePort)
            } catch (e: Exception) {
                Log.e(TAG, "SSH tunnel setup failed", e)
                _error.value = describeError(e, remoteHost, remotePort)
            }
        }
    }

    fun connect(host: String, port: Int, password: String?, username: String? = null) {
        // Guard: skip if already connected to the same target
        if (_connected.value && client?.running == true) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _error.value = null
                doConnect(host, port, password, username = username, displayHost = host, displayPort = port)
            } catch (e: Exception) {
                Log.e(TAG, "VNC connect failed", e)
                _error.value = describeError(e, host, port)
            }
        }
    }

    private fun doConnect(
        host: String, port: Int, password: String?, username: String? = null,
        displayHost: String? = null, displayPort: Int? = null,
    ) {
        // Stop any previous client to avoid orphaned threads
        client?.stop()

        val config = VncConfig().apply {
            colorDepth = ColorDepth.BPP_24_TRUE
            shared = true
            if (!password.isNullOrEmpty()) {
                passwordSupplier = { password }
            }
            if (!username.isNullOrEmpty()) {
                usernameSupplier = { username }
            }
            onScreenUpdate = { bitmap ->
                _frame.value = bitmap
            }
            onCursorUpdate = { bitmap, hotspotX, hotspotY ->
                _cursor.value = if (bitmap == null) null else CursorOverlay(bitmap, hotspotX, hotspotY)
            }
            onError = { e ->
                Log.e(TAG, "VNC error", e)
                _error.value = describeError(e, displayHost ?: host, displayPort ?: port)
                _connected.value = false
            }
            onRemoteClipboard = { text ->
                if (text.isNotEmpty()) {
                    try {
                        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("VNC", text))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to copy remote clipboard to system", e)
                    }
                }
            }
        }
        val c = VncClient(config)
        client = c
        c.start(host, port)
        _serverName.value = c.toString()
        _connected.value = true
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
            client?.stop()
            client = null
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
            _cursor.value = null
            _pointerPos.value = 0 to 0
        }
    }

    fun sendPointer(x: Int, y: Int) {
        _pointerPos.value = x to y
        viewModelScope.launch(Dispatchers.IO) { client?.moveMouse(x, y) }
    }

    fun pressButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) { client?.updateMouseButton(button, true) }
    }

    fun releaseButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) { client?.updateMouseButton(button, false) }
    }

    fun sendClick(x: Int, y: Int, button: Int = 1) {
        _pointerPos.value = x to y
        viewModelScope.launch(Dispatchers.IO) {
            client?.moveMouse(x, y)
            client?.click(button)
        }
    }

    fun sendKey(keySym: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { client?.updateKey(keySym, pressed) }
    }

    fun typeKey(keySym: Int) {
        viewModelScope.launch(Dispatchers.IO) { client?.type(keySym) }
    }

    /**
     * Type a string sequentially in a single coroutine and also push it
     * to the remote VNC clipboard. The clipboard sync is defence-in-depth
     * for apps where Ctrl+V works better than synth-typed key events.
     */
    fun typeText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            client?.copyText(text)
            client?.typeText(text)
        }
    }

    fun scrollUp() {
        viewModelScope.launch(Dispatchers.IO) { client?.click(4) }
    }

    fun scrollDown() {
        viewModelScope.launch(Dispatchers.IO) { client?.click(5) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }

    companion object {
        /** Map VNC/network exceptions to user-friendly messages with troubleshooting hints. */
        fun describeError(e: Exception, host: String? = null, port: Int? = null): String {
            val portStr = port?.toString() ?: "5900"
            val hostStr = host ?: "the remote host"
            return when (e) {
                is ConnectException -> buildString {
                    append("Connection refused")
                    if (e.message?.contains("refused", ignoreCase = true) == true) {
                        append(". No VNC server appears to be listening on $hostStr:$portStr.\n\n")
                        append("To start a VNC server on the remote host:\n")
                        append("  TigerVNC:  vncserver :1\n")
                        append("  x11vnc:    x11vnc -display :0 -rfbport $portStr\n")
                        append("  wayvnc:    wayvnc 0.0.0.0 $portStr\n\n")
                        append("Check: ss -tlnp | grep $portStr")
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
                is AuthenticationFailedException -> buildString {
                    append("Authentication failed")
                    val msg = e.message
                    if (msg != null && msg != "Authentication failed") append(": $msg")
                    append(".\n\n")
                    append("Check your VNC password. VNC passwords are limited to 8 characters.\n")
                    append("To reset: vncpasswd ~/.vnc/passwd")
                }
                is HandshakingFailedException -> buildString {
                    append("VNC handshake failed: ${e.message}\n\n")
                    append("This may indicate:\n")
                    append("  - An incompatible VNC server version\n")
                    append("  - Something other than a VNC server on port $portStr\n")
                    append("  - A web-based VNC proxy (noVNC) instead of a raw VNC port")
                }
                is VncException -> buildString {
                    val msg = e.message ?: "Unknown protocol error"
                    append("VNC protocol error: $msg\n\n")
                    if (msg.contains("encoding", ignoreCase = true)) {
                        append("The server is using an encoding Haven doesn't support.\n")
                        append("Try setting the server to use Raw or Hextile encoding.")
                    } else if (msg.contains("Unexpected end", ignoreCase = true)) {
                        append("The server closed the connection unexpectedly.\n")
                        append("Check the VNC server logs on the remote host for errors.")
                    }
                }
                is java.io.EOFException, is java.io.IOException -> {
                    val msg = e.message ?: ""
                    if (msg.contains("Broken pipe") || msg.contains("reset by peer") ||
                        msg.contains("end of stream", ignoreCase = true) || e is java.io.EOFException
                    ) {
                        "Connection lost. The VNC server may have stopped or the network dropped."
                    } else {
                        "Network error: $msg"
                    }
                }
                else -> e.message ?: "Unknown error"
            }
        }
    }
}
