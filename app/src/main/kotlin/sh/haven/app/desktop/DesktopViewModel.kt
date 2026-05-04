package sh.haven.app.desktop

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rdp.RdpSession
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncClient
import sh.haven.core.vnc.VncConfig
import sh.haven.feature.rdp.RdpViewModel
import sh.haven.feature.vnc.VncViewModel
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

private const val TAG = "DesktopViewModel"

/** An active SSH session that can be used for tunneling. */
data class SshTunnelOption(
    val sessionId: String,
    val label: String,
    val profileId: String,
)

@HiltViewModel
class DesktopViewModel @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val connectionLogRepository: ConnectionLogRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    private val _tabs = MutableStateFlow<List<DesktopTab>>(emptyList())
    val tabs: StateFlow<List<DesktopTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    /** The currently active tab, derived for convenience. */
    val activeTab: StateFlow<DesktopTab?> = combine(_tabs, _activeTabIndex) { tabs, idx ->
        tabs.getOrNull(idx)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Whether the active tab is connected (used to disable pager swipe). */
    val activeTabConnected: StateFlow<Boolean> = combine(_tabs, _activeTabIndex) { tabs, idx ->
        tabs.getOrNull(idx)?.connected?.value == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- Tab management ---

    fun selectTab(index: Int) {
        val tabs = _tabs.value
        if (index in tabs.indices) {
            pauseAllExcept(index)
            _activeTabIndex.value = index
        }
    }

    fun moveTab(fromIndex: Int, direction: Int) {
        val tabs = _tabs.value.toMutableList()
        val toIndex = fromIndex + direction
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return
        val tab = tabs.removeAt(fromIndex)
        tabs.add(toIndex, tab)
        _tabs.value = tabs
        if (_activeTabIndex.value == fromIndex) _activeTabIndex.value = toIndex
        else if (_activeTabIndex.value == toIndex) _activeTabIndex.value = fromIndex
    }

    /**
     * User dismissed the bandwidth-suggestion banner — clear it; the
     * session-side bandwidthSuggestionFired flag stops it re-firing.
     */
    fun dismissBandwidthSuggestion(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } as? DesktopTab.Vnc ?: return
        tab._bandwidthSuggestion.value = null
    }

    /**
     * User accepted the bandwidth-suggestion banner — persist the new
     * colour depth on the profile (if any), close the existing tab, and
     * reconnect with the new depth.
     */
    fun acceptBandwidthSuggestion(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } as? DesktopTab.Vnc ?: return
        val newDepth = tab._bandwidthSuggestion.value ?: return
        val pid = tab.profileId
        viewModelScope.launch(Dispatchers.IO) {
            if (pid != null) {
                connectionRepository.getById(pid)?.let { existing ->
                    if (existing.vncColorDepth != newDepth) {
                        connectionRepository.save(existing.copy(vncColorDepth = newDepth))
                    }
                }
            }
            // Snapshot before close, since closeTab disposes the tab.
            val host = tab.originalHost.ifEmpty { return@launch }
            val port = tab.originalPort
            val username = tab.originalUsername
            val password = tab.originalPassword
            val sshForward = tab.sshForward
            val sshSessionId = tab.sshSessionId
            withContext(Dispatchers.Main) { closeTab(tabId) }
            addVncSession(
                host = host,
                port = port,
                password = password,
                username = username,
                sshForward = sshForward,
                sshSessionId = sshSessionId,
                profileId = pid,
                colorDepth = newDepth,
            )
        }
    }

    fun closeTab(tabId: String) {
        val tabs = _tabs.value.toMutableList()
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        disconnectTab(tab)
        _tabs.value = tabs
        if (_activeTabIndex.value >= tabs.size && tabs.isNotEmpty()) {
            _activeTabIndex.value = tabs.size - 1
        }
        pauseAllExcept(_activeTabIndex.value)
    }

    // --- Tab deduplication ---

    /**
     * Find an existing tab matching a connection. Matches by profileId first,
     * then by host:port for VNC or host:port:username for RDP.
     * Returns the tab index, or -1 if not found.
     */
    private fun findExistingTab(
        profileId: String?,
        host: String,
        port: Int,
        protocol: String,
        username: String? = null,
    ): Int {
        val tabs = _tabs.value
        // Match by profileId if available
        if (profileId != null) {
            val idx = tabs.indexOfFirst { tab ->
                when (tab) {
                    is DesktopTab.Vnc -> tab.profileId == profileId
                    is DesktopTab.Rdp -> tab.profileId == profileId
                    else -> false
                }
            }
            if (idx >= 0) return idx
        }
        // Match by host+port (and username for RDP)
        return tabs.indexOfFirst { tab ->
            when {
                protocol == "VNC" && tab is DesktopTab.Vnc && tab.profileId == null ->
                    tab.label == "$host:$port"
                protocol == "RDP" && tab is DesktopTab.Rdp && tab.profileId == null ->
                    tab.label == "$host:$port"
                else -> false
            }
        }
    }

    // --- VNC sessions ---

    fun addVncSession(
        host: String,
        port: Int,
        password: String?,
        username: String? = null,
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
        colorDepth: String = "BPP_24_TRUE",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Deduplicate: if a tab for the same connection exists, reuse or replace
            val existingIdx = findExistingTab(profileId, host, port, "VNC")
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    // Already connected — just switch to it
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                // Disconnected/errored — close old tab before creating new one
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()
            val tab = DesktopTab.Vnc(
                id = tabId,
                label = label,
                colorTag = colorTag,
                client = VncClient(VncConfig()), // placeholder, replaced in doConnect
                profileId = profileId,
            )

            try {
                val actualHost: String
                val actualPort: Int
                var tunnelPort: Int? = null
                var tunnelSessionId: String? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    // Tunnel target is 127.0.0.1 on the SSH server, not the
                    // SSH profile's external host. VNC servers like wayvnc
                    // typically bind loopback only; using the SSH host's
                    // external address makes sshd try to connect back to
                    // itself over the public interface and hit ECONNREFUSED.
                    // This was #104's actual cause; the earlier 127.0.0.1
                    // fix in VncScreen covered the pure VNC→tunnel path
                    // but missed the saved-on-SSH Desktop-tab path.
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", port)
                    tunnelPort = lp
                    tunnelSessionId = sshSessionId
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "VNC SSH tunnel: localhost:$lp -> 127.0.0.1:$port (via $host)")
                } else {
                    actualHost = host
                    actualPort = port
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)
                val cursor = MutableStateFlow<sh.haven.feature.vnc.CursorOverlay?>(null)
                val pointerPos = MutableStateFlow(0 to 0)
                val bandwidthSuggestion = MutableStateFlow<String?>(null)

                val config = VncConfig().apply {
                    this.colorDepth = runCatching { ColorDepth.valueOf(colorDepth) }
                        .getOrDefault(ColorDepth.BPP_24_TRUE)
                    shared = true
                    if (!password.isNullOrEmpty()) passwordSupplier = { password }
                    if (!username.isNullOrEmpty()) usernameSupplier = { username }
                    onScreenUpdate = { bitmap -> frame.value = bitmap }
                    onCursorUpdate = { bmp, hx, hy ->
                        cursor.value = if (bmp == null) null else sh.haven.feature.vnc.CursorOverlay(bmp, hx, hy)
                    }
                    onBandwidthSuggestion = { suggested ->
                        // Only surface if the global preference is on. If
                        // the user's already dismissed the banner this
                        // session, the StateFlow is set to null and the
                        // session-side flag (bandwidthSuggestionFired)
                        // prevents re-firing.
                        viewModelScope.launch {
                            if (preferencesRepository.bandwidthAutoSuggest.first()) {
                                bandwidthSuggestion.value = suggested.name
                            }
                        }
                    }
                    onError = { e ->
                        Log.e(TAG, "VNC error on tab $tabId", e)
                        error.value = VncViewModel.describeError(e, host, port)
                        connected.value = false
                    }
                    onRemoteClipboard = { text ->
                        Log.d(TAG, "VNC clipboard ($tabId): ${text.take(50)}")
                    }
                }

                val client = VncClient(config)
                client.start(actualHost, actualPort)
                connected.value = true

                val connectedTab = tab.copy(
                    client = client,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    _bandwidthSuggestion = bandwidthSuggestion,
                    tunnelPort = tunnelPort,
                    tunnelSessionId = tunnelSessionId,
                    originalHost = host,
                    originalPort = port,
                    originalUsername = username,
                    originalPassword = password,
                    sshForward = sshForward,
                    sshSessionId = sshSessionId,
                    colorDepth = colorDepth,
                )

                val tabs = _tabs.value.toMutableList()
                tabs.add(connectedTab)
                _tabs.value = tabs
                pauseAllExcept(tabs.size - 1)
                _activeTabIndex.value = tabs.size - 1
            } catch (e: Exception) {
                Log.e(TAG, "VNC connect failed", e)
                // Add tab in error state so user sees the error
                val errorTab = tab.copy(
                    _error = MutableStateFlow(VncViewModel.describeError(e, host, port)),
                )
                val tabs = _tabs.value.toMutableList()
                tabs.add(errorTab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
            }
        }
    }

    // --- RDP sessions ---

    fun addRdpSession(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String = "",
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        sshProfileId: String? = null,
        profileId: String? = null,
        useNla: Boolean = true,
        colorDepth: Int = 16,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Deduplicate: if a tab for the same connection exists, reuse or replace
            val existingIdx = findExistingTab(profileId, host, port, "RDP", username)
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()

            try {
                val actualHost: String
                val actualPort: Int
                var tunnelPort: Int? = null
                var tunnelSessionId: String? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    tunnelPort = lp
                    tunnelSessionId = sshSessionId
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "RDP SSH tunnel: localhost:$lp -> $host:$port")
                } else {
                    actualHost = host
                    actualPort = port
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)

                val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
                val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null

                val session = RdpSession(
                    sessionId = "rdp-$tabId",
                    host = actualHost,
                    port = actualPort,
                    username = username,
                    password = password,
                    domain = domain,
                    useNla = useNla,
                    colorDepth = colorDepth,
                    verboseBuffer = verboseBuffer,
                )
                session.onFrameUpdate = { bitmap -> frame.value = bitmap }
                session.onError = { e ->
                    Log.e(TAG, "RDP error on tab $tabId", e)
                    error.value = RdpViewModel.describeError(e, host, port)
                    connected.value = false
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                        }
                    }
                }
                session.onConnected = { _, _ ->
                    // Real handshake complete — only now flip the tab to
                    // "connected". Before this the UI stays on a Connecting
                    // state rather than a misleading empty framebuffer.
                    connected.value = true
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val startLog = session.drainVerboseLog()
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
                        }
                    }
                }

                session.start()
                // NB: intentionally no `connected.value = true` here — that
                // happens in session.onConnected once the Rust worker
                // thread completes the handshake.

                val tab = DesktopTab.Rdp(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = session,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    tunnelPort = tunnelPort,
                    tunnelSessionId = tunnelSessionId,
                    profileId = profileId,
                )

                val tabs = _tabs.value.toMutableList()
                tabs.add(tab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
            } catch (e: Exception) {
                Log.e(TAG, "RDP connect failed", e)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                // Show error in a temporary tab (no session to close)
                val errorTab = DesktopTab.Rdp(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = RdpSession("err", host, port, username, password, domain),
                    _error = MutableStateFlow(RdpViewModel.describeError(e, host, port)),
                    profileId = profileId,
                )
                val tabs = _tabs.value.toMutableList()
                tabs.add(errorTab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
            }
        }
    }

    // --- Wayland ---

    fun addWaylandTab() {
        val tabs = _tabs.value
        val existing = tabs.indexOfFirst { it is DesktopTab.Wayland }
        if (existing >= 0) {
            selectTab(existing)
            return
        }
        val newTabs = tabs.toMutableList()
        newTabs.add(DesktopTab.Wayland())
        _tabs.value = newTabs
        _activeTabIndex.value = newTabs.size - 1
    }

    fun removeWaylandTab() {
        val tabs = _tabs.value.toMutableList()
        val index = tabs.indexOfFirst { it is DesktopTab.Wayland }
        if (index >= 0) {
            tabs.removeAt(index)
            _tabs.value = tabs
            if (_activeTabIndex.value >= tabs.size && tabs.isNotEmpty()) {
                _activeTabIndex.value = tabs.size - 1
            }
        }
    }

    // --- Input forwarding (operates on active tab) ---

    fun sendPointer(x: Int, y: Int) {
        // Mirror the latest pointer into per-tab state so the UI overlay
        // (cursor / virtual cursor seed) repaints immediately, without
        // waiting for the IO dispatch round-trip.
        when (val tab = activeTab.value) {
            is DesktopTab.Vnc -> tab._pointerPos.value = x to y
            is DesktopTab.Rdp -> tab._pointerPos.value = x to y
            else -> {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseMove(x, y)
        }
    }

    fun pressButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseButton(button, pressed = true)
        }
    }

    fun releaseButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseButton(button, pressed = false)
        }
    }

    fun sendClick(x: Int, y: Int, button: Int = 1) {
        when (val tab = activeTab.value) {
            is DesktopTab.Vnc -> tab._pointerPos.value = x to y
            is DesktopTab.Rdp -> tab._pointerPos.value = x to y
            else -> {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseClick(x, y, button)
        }
    }

    fun sendVncKey(keySym: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Vnc)?.client?.updateKey(keySym, pressed)
        }
    }

    fun typeVncKey(keySym: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Vnc)?.client?.type(keySym)
        }
    }

    /**
     * Type a string sequentially via the active VNC tab. Single coroutine
     * so key down/up events stay in source order on the wire — see
     * [VncClient.typeText]. Also pushes the text to the remote VNC
     * clipboard as defence-in-depth (Ctrl+V on the remote then works
     * regardless of synth-typing fidelity).
     */
    fun typeVncText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val client = (activeTab.value as? DesktopTab.Vnc)?.client ?: return@launch
            client.copyText(text)
            client.typeText(text)
        }
    }

    fun sendRdpKey(scancode: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Rdp)?.session?.sendKey(scancode, pressed)
        }
    }

    fun typeRdpUnicode(codepoint: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = (activeTab.value as? DesktopTab.Rdp)?.session ?: return@launch
            s.sendUnicodeKey(codepoint, true)
            s.sendUnicodeKey(codepoint, false)
        }
    }

    /**
     * Per-Desktop-view orientation override. Stored in the ViewModel
     * so it survives any composable subtree recreation that happens
     * when the conditional tab-bar in DesktopScreen comes/goes during
     * an orientation change — that recreation reset a Composable-
     * local `remember` state to its initial value (Landscape) and
     * the LaunchedEffect immediately wrote Landscape back, so the
     * lock never took effect.
     *
     * Values are the raw `ActivityInfo.SCREEN_ORIENTATION_*`
     * constants since the enum is private to feature modules. Cycle
     * order matches the toolbar button: Landscape -> Portrait -> Auto.
     */
    private val _desktopOrientation = MutableStateFlow(
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    )
    val desktopOrientation: StateFlow<Int> = _desktopOrientation.asStateFlow()

    fun cycleDesktopOrientation() {
        _desktopOrientation.value = when (_desktopOrientation.value) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    fun scrollUp() {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseWheel(deltaY = 1)
        }
    }

    fun scrollDown() {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseWheel(deltaY = -1)
        }
    }

    // --- SSH tunnel helpers ---

    fun getActiveSshSessions(): List<SshTunnelOption> {
        val ssh = sshSessionManager.activeSessions.map { session ->
            SshTunnelOption(session.sessionId, session.label, session.profileId)
        }
        val mosh = moshSessionManager.activeSessions
            .filter { it.sshClient != null }
            .map { SshTunnelOption(it.sessionId, "${it.label} (Mosh)", it.profileId) }
        val et = etSessionManager.activeSessions
            .filter { it.sshClient != null }
            .map { SshTunnelOption(it.sessionId, "${it.label} (ET)", it.profileId) }
        return ssh + mosh + et
    }

    private fun findSshClient(sessionId: String): SshClient? {
        sshSessionManager.getSession(sessionId)?.let { return it.client }
        moshSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        etSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        return null
    }

    // --- Lifecycle ---

    private fun disconnectTab(tab: DesktopTab) {
        viewModelScope.launch(Dispatchers.IO) {
            when (tab) {
                is DesktopTab.Vnc -> {
                    tab.client.stop()
                    tearDownTunnel(tab.tunnelPort, tab.tunnelSessionId)
                }
                is DesktopTab.Rdp -> {
                    if (tab.profileId != null) {
                        val verboseLog = tab.session.drainVerboseLog()
                        connectionLogRepository.logEvent(tab.profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                    }
                    tab.session.close()
                    tearDownTunnel(tab.tunnelPort, tab.tunnelSessionId)
                }
                is DesktopTab.Wayland -> {} // compositor lifecycle managed externally
            }
        }
    }

    private fun tearDownTunnel(port: Int?, sessionId: String?) {
        if (port != null && sessionId != null) {
            try {
                findSshClient(sessionId)?.delPortForwardingL("127.0.0.1", port)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove SSH tunnel", e)
            }
        }
    }

    private fun pauseAllExcept(activeIndex: Int) {
        _tabs.value.forEachIndexed { index, tab ->
            val rd = tab.remoteDesktop ?: return@forEachIndexed
            if (index == activeIndex) rd.resume() else rd.pause()
        }
    }

    private suspend fun resolveLabel(profileId: String?): String? {
        if (profileId == null) return null
        return try {
            connectionRepository.getById(profileId)?.label
        } catch (_: Exception) { null }
    }

    private suspend fun resolveColorTag(profileId: String?): Int {
        if (profileId == null) return 0
        return try {
            connectionRepository.getById(profileId)?.colorTag ?: 0
        } catch (_: Exception) { 0 }
    }

    override fun onCleared() {
        super.onCleared()
        _tabs.value.forEach { disconnectTab(it) }
    }
}
