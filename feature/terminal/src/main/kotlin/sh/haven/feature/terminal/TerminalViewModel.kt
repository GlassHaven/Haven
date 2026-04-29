package sh.haven.feature.terminal

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshSessionManager.SessionState
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject

private const val TAG = "TerminalViewModel"

/** Transports that keep the server-side PTY alive across client disconnects;
 *  closing one needs an explicit session-manager detach key first, otherwise
 *  zellij/tmux/screen never sees the client leave. SSH gets HUP for free. */
private val TRANSPORTS_NEEDING_EXPLICIT_DETACH = setOf("MOSH", "ET")

/** How long to wait for a Mosh/ET detach packet to land before tearing down. */
private const val SESSION_MANAGER_DETACH_DELAY_MS = 300L

/** Sentinel for transports that don't report a stall countdown. */
private val NEVER_STALLS: StateFlow<Int?> = MutableStateFlow(null)

/** Main-thread handler for posting emulator writes. */
private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

/**
 * Records terminal data to a file for replay/debugging.
 *
 * Format: sequential frames of [4-byte LE millis since start][4-byte LE length][data].
 * Replay by reading frames and feeding to TerminalEmulator.writeInput().
 *
 * Uses a ring-buffer approach: when the file exceeds [maxBytes], it rotates
 * to a new file and deletes the oldest, keeping at most 2 files (current + previous).
 * Default cap: 8MB per file, 16MB total.
 */
class TerminalRecorder(
    private val file: java.io.File,
    private val maxBytes: Long = 8L * 1024 * 1024,
) : java.io.Closeable {
    private val startTime = System.currentTimeMillis()
    private var out = java.io.BufferedOutputStream(file.outputStream())
    private val buf = ByteArray(8) // reusable header buffer
    private var bytesWritten = 0L
    private var generation = 0

    @Synchronized
    fun record(data: ByteArray, offset: Int, length: Int) {
        val elapsed = (System.currentTimeMillis() - startTime).toInt()
        buf[0] = (elapsed and 0xFF).toByte()
        buf[1] = (elapsed ushr 8 and 0xFF).toByte()
        buf[2] = (elapsed ushr 16 and 0xFF).toByte()
        buf[3] = (elapsed ushr 24 and 0xFF).toByte()
        buf[4] = (length and 0xFF).toByte()
        buf[5] = (length ushr 8 and 0xFF).toByte()
        buf[6] = (length ushr 16 and 0xFF).toByte()
        buf[7] = (length ushr 24 and 0xFF).toByte()
        out.write(buf)
        out.write(data, offset, length)
        bytesWritten += 8 + length
        if (bytesWritten >= maxBytes) {
            rotate()
        }
    }

    private fun rotate() {
        try { out.flush(); out.close() } catch (_: Exception) {}
        generation++
        // Delete the file from 2 generations ago (keep current + previous)
        val oldFile = generationFile(generation - 2)
        oldFile.delete()
        val newFile = generationFile(generation)
        out = java.io.BufferedOutputStream(newFile.outputStream())
        bytesWritten = 0
    }

    private fun generationFile(gen: Int): java.io.File {
        if (gen <= 0) return file
        val base = file.nameWithoutExtension
        val ext = file.extension
        return java.io.File(file.parent, "$base.$gen.$ext")
    }

    @Synchronized
    override fun close() {
        try { out.flush(); out.close() } catch (_: Exception) {}
    }
}

/**
 * Coalesces SSH/RNS data chunks into batched writes on the main thread.
 *
 * Without this, every onDataReceived callback posts a separate message to
 * the main looper. During fast output this floods the queue and delays
 * resize/layout events. This class accumulates bytes in a lock-protected
 * buffer and only keeps one pending main-thread drain in flight at a time.
 */
private class EmulatorWriteBuffer(
    private val emulator: () -> TerminalEmulator?,
    private val recorder: TerminalRecorder? = null,
) {
    private val lock = Any()
    private var buffer = ByteArray(8192)
    private var length = 0
    private var drainScheduled = false

    fun append(data: ByteArray, offset: Int, len: Int) {
        synchronized(lock) {
            if (length + len > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, length + len))
            }
            System.arraycopy(data, offset, buffer, length, len)
            length += len
            if (!drainScheduled) {
                drainScheduled = true
                mainHandler.post(::drain)
            }
        }
    }

    private fun drain() {
        val copy: ByteArray
        val copyLen: Int
        synchronized(lock) {
            copyLen = length
            copy = buffer.copyOf(copyLen)
            length = 0
            drainScheduled = false
        }
        if (copyLen > 0) {
            recorder?.record(copy, 0, copyLen)
            emulator()?.writeInput(copy, 0, copyLen)
        }
    }
}

/**
 * Coalesces rapid single-byte inputs into a batch, then deduplicates only
 * the exact IME double-fire pattern (buffer == [X, X]).
 *
 * Android IMEs often fire both commitText and sendKeyEvent for the same
 * keystroke, causing onKeyboardInput to be called twice with the same byte.
 * Both calls happen within a single Handler message, so a posted Runnable
 * flushes after all input from the current message is processed.
 *
 * The IME double-fire signature: exactly 2 identical bytes in the buffer.
 * Paste "aa" also matches this, but that's a rare edge case compared to
 * the constant double-fire on every keystroke. Longer paste sequences
 * (e.g., "aab", "43339") are preserved correctly.
 *
 * Multi-byte inputs (toolbar, escape sequences) bypass coalescing.
 */
private class InputCoalescer(private val sink: (ByteArray) -> Unit) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val buffer = mutableListOf<Byte>()

    private val flushRunnable = Runnable { flush() }

    fun send(data: ByteArray) {
        if (data.size != 1) {
            // Multi-byte input (toolbar key combos, etc.) — flush pending then send directly
            flush()
            sink(data)
            return
        }

        synchronized(buffer) {
            buffer.add(data[0])
        }

        // Post flush to run after the current message completes.
        // Both IME double-fire calls and paste iteration happen within
        // one message, so the flush sees the complete batch.
        handler.removeCallbacks(flushRunnable)
        handler.post(flushRunnable)
    }

    private fun flush() {
        handler.removeCallbacks(flushRunnable)
        val bytes: ByteArray
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            // IME double-fire signature: exactly 2 identical bytes
            if (buffer.size == 2 && buffer[0] == buffer[1]) {
                bytes = byteArrayOf(buffer[0])
            } else {
                bytes = buffer.toByteArray()
            }
            buffer.clear()
        }
        sink(bytes)
    }
}

data class TerminalTab(
    val sessionId: String,
    val profileId: String,
    val colorTag: Int = 0,
    val label: String,
    val transportType: String,
    val emulator: TerminalEmulator,
    val mouseMode: StateFlow<Boolean>,
    val activeMouseMode: StateFlow<Int?>,
    val bracketPasteMode: StateFlow<Boolean>,
    val oscHandler: OscHandler,
    val cwd: StateFlow<String?>,
    val hyperlinkUri: StateFlow<String?>,
    val isReconnecting: StateFlow<Boolean>,
    /** Non-null when the transport has gone silent and is counting down to a
     *  forced disconnect. Currently only Mosh emits a value; other transports
     *  stay null. */
    val secondsUntilDisconnect: StateFlow<Int?>,
    val sendInput: (ByteArray) -> Unit,
    val resize: (Int, Int) -> Unit,
    val close: () -> Unit,
)

/** VNC connection info for the active terminal's host. */
data class VncInfo(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val sshForward: Boolean,
    val profileId: String,
    val sessionId: String,
    val stored: Boolean,
    val colorDepth: String = "BPP_24_TRUE",
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val sessionManager: SshSessionManager,
    private val reticulumSessionManager: ReticulumSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val localSessionManager: sh.haven.core.local.LocalSessionManager,
    private val hostKeyVerifier: HostKeyVerifier,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: sh.haven.core.data.repository.ConnectionRepository,
) : ViewModel() {

    private val activeRecorders = mutableListOf<TerminalRecorder>()

    override fun onCleared() {
        super.onCleared()
        activeRecorders.forEach { it.close() }
        activeRecorders.clear()
        // Detach terminal sessions so they can be re-picked up by a new ViewModel.
        // This happens when the Activity is destroyed but the process stays alive
        // (foreground service keeps SSH connections open).
        for (tab in _tabs.value) {
            when (tab.transportType) {
                "SSH" -> sessionManager.detachTerminalSession(tab.sessionId)
                "RETICULUM" -> reticulumSessionManager.detachTerminalSession(tab.sessionId)
                "MOSH" -> moshSessionManager.detachTerminalSession(tab.sessionId)
                "ET" -> etSessionManager.detachTerminalSession(tab.sessionId)
                "LOCAL" -> localSessionManager.detachTerminalSession(tab.sessionId)
            }
        }
        trackedSessionIds.clear()
    }

    private fun createRecorderIfEnabled(sessionId: String): TerminalRecorder? {
        val enabled = runBlocking(Dispatchers.IO) { preferencesRepository.verboseLoggingEnabled.first() }
        if (!enabled) return null
        val dir = java.io.File(appContext.filesDir, "terminal-recordings").apply { mkdirs() }
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(dir, "session-${ts}-${sessionId.take(8)}.bin")
        Log.d(TAG, "Recording terminal data to ${file.absolutePath}")
        val recorder = TerminalRecorder(file)
        activeRecorders.add(recorder)
        return recorder
    }

    val terminalColorScheme: StateFlow<UserPreferencesRepository.TerminalColorScheme> =
        preferencesRepository.terminalColorScheme
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                UserPreferencesRepository.TerminalColorScheme.HAVEN,
            )

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    /** Connected sessions that don't have an open terminal tab.
     *  Shown in the tab long-press popup to quickly open a tab. */
    data class AvailableSession(val profileId: String, val label: String, val sessionId: String)

    val untabbedSessions: StateFlow<List<AvailableSession>> =
        combine(
            sessionManager.sessions,
            reticulumSessionManager.sessions,
            moshSessionManager.sessions,
            etSessionManager.sessions,
            _tabs,
        ) { ssh, rns, mosh, et, tabs ->
            val tabbedSessionIds = tabs.map { it.sessionId }.toSet()
            val available = mutableListOf<AvailableSession>()
            for ((id, state) in ssh) {
                if (id !in tabbedSessionIds && state.status == SshSessionManager.SessionState.Status.CONNECTED) {
                    available.add(AvailableSession(state.profileId, state.label, id))
                }
            }
            for ((id, state) in rns) {
                if (id !in tabbedSessionIds && state.status == ReticulumSessionManager.SessionState.Status.CONNECTED) {
                    available.add(AvailableSession(state.profileId, state.label, id))
                }
            }
            for ((id, state) in mosh) {
                if (id !in tabbedSessionIds && state.status == MoshSessionManager.SessionState.Status.CONNECTED) {
                    available.add(AvailableSession(state.profileId, state.label, id))
                }
            }
            for ((id, state) in et) {
                if (id !in tabbedSessionIds && state.status == EtSessionManager.SessionState.Status.CONNECTED) {
                    available.add(AvailableSession(state.profileId, state.label, id))
                }
            }
            available
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Remote session manager session names (tmux/zellij/screen) for the active tab's connection. */
    private val _remoteSessionNames = MutableStateFlow<List<String>>(emptyList())
    val remoteSessionNames: StateFlow<List<String>> = _remoteSessionNames.asStateFlow()

    /** Refresh the list of remote sessions for the active tab's connection. */
    fun refreshRemoteSessions() {
        val activeTab = _tabs.value.getOrNull(_activeTabIndex.value) ?: return
        if (activeTab.transportType != "SSH") return
        val configPair = sessionManager.getConnectionConfigForProfile(activeTab.profileId) ?: return
        val (_, sshSessionMgr) = configPair
        val listCmd = sshSessionMgr.listCommand ?: return
        val session = sessionManager.getSession(activeTab.sessionId) ?: return
        val client = session.client
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.execCommand(listCmd) }
                if (result.exitStatus == 0) {
                    _remoteSessionNames.value = SessionManager.parseSessionList(sshSessionMgr, result.stdout)
                }
            } catch (_: Exception) {}
        }
    }

    /** Emitted once when a session closes and no tabs remain. */
    private val _navigateToConnections = MutableStateFlow(false)
    val navigateToConnections: StateFlow<Boolean> = _navigateToConnections.asStateFlow()

    fun onNavigatedToConnections() {
        _navigateToConnections.value = false
    }

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    // Modifier key state — read by onKeyboardInput callback, toggled by toolbar
    private val _ctrlActive = MutableStateFlow(false)
    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()

    private val _altActive = MutableStateFlow(false)
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()

    fun toggleCtrl() { _ctrlActive.value = !_ctrlActive.value }
    fun toggleAlt() { _altActive.value = !_altActive.value }

    fun setFontSize(sizeSp: Int) {
        viewModelScope.launch {
            preferencesRepository.setTerminalFontSize(sizeSp)
        }
    }

    /** True when any PRoot desktop environment is installed. */
    val isLocalDesktopInstalled: Boolean
        get() = localSessionManager.prootManager.hasAnyDesktopInstalled

    /** Start the first installed desktop via DesktopManager. */
    suspend fun startLocalVncServer() {
        val de = localSessionManager.prootManager.installedDesktop ?: return
        withContext(Dispatchers.IO) {
            localSessionManager.desktopManager.startDesktop(de)
        }
    }

    /** No-op — VNC profiles are no longer needed for local desktops. */
    suspend fun ensureLocalVncProfile() { }

    /** Get the stored VNC password for local desktop (localhost:5901). */
    suspend fun getLocalVncPassword(): String? =
        connectionRepository.getAll()
            .find { it.connectionType == "VNC" && it.host == "localhost" && it.vncPort == 5901 }
            ?.vncPassword

    /** Get VNC connection info for the active terminal tab's SSH host. */
    suspend fun getActiveVncInfo(): VncInfo? {
        val tab = _tabs.value.getOrNull(_activeTabIndex.value) ?: return null
        val profile = connectionRepository.getById(tab.profileId)

        // For SSH tabs, use the stored connection config; for mosh/ET, use the profile directly
        val host = sessionManager.getConnectionConfigForProfile(tab.profileId)?.first?.host
            ?: profile?.host
            ?: return null
        return VncInfo(
            host = host,
            port = profile?.vncPort ?: 5900,
            username = profile?.vncUsername,
            password = profile?.vncPassword,
            sshForward = profile?.vncSshForward ?: true,
            profileId = tab.profileId,
            sessionId = tab.sessionId,
            stored = profile?.vncPort != null,
            colorDepth = profile?.vncColorDepth ?: "BPP_24_TRUE",
        )
    }

    /**
     * Send Ctrl+L to the active tab if its profile uses Zellij as session manager.
     * Called by TerminalScreen when the keyboard hides to trigger a full redraw,
     * working around Zellij not reflowing content on alternate screen resize.
     */
    fun sendRedrawIfZellij() {
        val tab = _tabs.value.getOrNull(_activeTabIndex.value) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val profile = connectionRepository.getById(tab.profileId)
            val smOverride = profile?.sessionManager
            val isZellij = if (smOverride != null) {
                smOverride.equals("ZELLIJ", ignoreCase = true)
            } else {
                preferencesRepository.sessionManager.first().name
                    .equals("ZELLIJ", ignoreCase = true)
            }
            if (isZellij) {
                // Ctrl+L = 0x0C (form feed) — triggers shell clear/redraw inside Zellij pane
                kotlinx.coroutines.delay(300) // wait for resize debounce + SIGWINCH
                tab.sendInput(byteArrayOf(0x0C))
            }
        }
    }

    /**
     * Send the session manager's native search key sequence to the active tab.
     * Falls back to Ctrl+R (shell reverse search) when no session manager is configured.
     * Uses default prefix keys (Ctrl+B for tmux, Ctrl+A for screen/byobu).
     */
    fun sendSearchKeys() {
        val tab = _tabs.value.getOrNull(_activeTabIndex.value) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val profile = connectionRepository.getById(tab.profileId)
            val smOverride = profile?.sessionManager
            val smName = if (smOverride != null) {
                smOverride.uppercase()
            } else {
                preferencesRepository.sessionManager.first().name
            }

            when (smName) {
                "TMUX" -> {
                    tab.sendInput(byteArrayOf(0x02)) // Ctrl+B (tmux prefix)
                    kotlinx.coroutines.delay(50)
                    tab.sendInput("[/".toByteArray()) // copy-mode + forward search
                }
                "ZELLIJ" -> {
                    tab.sendInput(byteArrayOf(0x13)) // Ctrl+S (search mode)
                }
                "SCREEN", "BYOBU" -> {
                    tab.sendInput(byteArrayOf(0x01)) // Ctrl+A (screen/byobu prefix)
                    kotlinx.coroutines.delay(50)
                    tab.sendInput("[/".toByteArray()) // copy-mode + forward search
                }
                else -> {
                    tab.sendInput(byteArrayOf(0x12)) // Ctrl+R (shell reverse search)
                }
            }
        }
    }

    /**
     * Copy the last completed command's output to clipboard.
     * Uses OSC 133 semantic markers (COMMAND_INPUT → COMMAND_FINISHED) to extract output.
     * Returns the output text, or null if no completed command found (shell needs
     * OSC 133 support, e.g. bash/zsh with shell integration configured).
     */
    fun copyLastCommandOutput(): String? {
        val tab = _tabs.value.getOrNull(_activeTabIndex.value) ?: return null
        return tab.emulator.getLastCommandOutput()
    }

    /** Save VNC settings for a profile. */
    fun saveVncSettings(
        profileId: String,
        port: Int,
        username: String?,
        password: String?,
        sshForward: Boolean,
        colorDepth: String = "BPP_24_TRUE",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            connectionRepository.updateVncSettings(profileId, port, username, password, sshForward)
            // Colour depth lives on the profile but isn't part of the
            // single-call updateVncSettings signature in
            // ConnectionRepository — patch it via a fresh getById/upsert
            // so the VNC-on-SSH save path doesn't lose the new setting.
            val existing = connectionRepository.getById(profileId)
            if (existing != null && existing.vncColorDepth != colorDepth) {
                connectionRepository.save(existing.copy(vncColorDepth = colorDepth))
            }
        }
    }

    /**
     * Apply Ctrl/Alt modifiers to keyboard input, then reset them (one-shot).
     * Ctrl+letter -> char AND 0x1F (e.g. Ctrl+C = 0x03).
     * Alt+char -> ESC prefix.
     */
    private fun applyModifiers(data: ByteArray): ByteArray {
        val ctrl = _ctrlActive.value
        val alt = _altActive.value
        if (!ctrl && !alt) return data

        _ctrlActive.value = false
        _altActive.value = false

        var result = data
        if (ctrl && result.size == 1) {
            val b = result[0].toInt() and 0xFF
            if (b in 0x40..0x7F) {
                result = byteArrayOf((b and 0x1F).toByte())
            }
        }
        if (alt) {
            result = byteArrayOf(0x1b) + result
        }
        return result
    }

    private val trackedSessionIds = mutableSetOf<String>()

    init {
        // React to session state changes (e.g., "Disconnect All" from notification)
        // even when the TerminalScreen isn't actively composing.
        viewModelScope.launch {
            sessionManager.sessions.collect { syncSessions() }
        }
        viewModelScope.launch {
            reticulumSessionManager.sessions.collect { syncSessions() }
        }
        viewModelScope.launch {
            moshSessionManager.sessions.collect { syncSessions() }
        }
        viewModelScope.launch {
            etSessionManager.sessions.collect { syncSessions() }
        }
        viewModelScope.launch {
            localSessionManager.sessions.collect { syncSessions() }
        }
    }

    /**
     * Sync tabs with session manager state.
     * Creates emulator + terminal session for new CONNECTED sessions.
     */
    fun syncSessions() {
        val sshSessions = sessionManager.sessions.value
        val rnsSessions = reticulumSessionManager.sessions.value
        val moshSessions = moshSessionManager.sessions.value
        val etSessions = etSessionManager.sessions.value
        val localSessions = localSessionManager.sessions.value

        // Find SSH sessions that are connected or reconnecting
        val activeSshIds = sshSessions.values
            .filter {
                it.status == SessionState.Status.CONNECTED ||
                    it.status == SessionState.Status.RECONNECTING
            }
            .map { it.sessionId }
            .toSet()

        // Find Reticulum sessions that are connected
        val activeRnsIds = rnsSessions.values
            .filter {
                it.status == ReticulumSessionManager.SessionState.Status.CONNECTED
            }
            .map { it.sessionId }
            .toSet()

        // Find Mosh sessions that are connected
        val activeMoshIds = moshSessions.values
            .filter {
                it.status == MoshSessionManager.SessionState.Status.CONNECTED
            }
            .map { it.sessionId }
            .toSet()

        // Find ET sessions that are connected
        val activeEtIds = etSessions.values
            .filter {
                it.status == EtSessionManager.SessionState.Status.CONNECTED
            }
            .map { it.sessionId }
            .toSet()

        // Find Local sessions that are connected
        val activeLocalIds = localSessions.values
            .filter {
                it.status == sh.haven.core.local.LocalSessionManager.SessionState.Status.CONNECTED
            }
            .map { it.sessionId }
            .toSet()

        val allActiveIds = activeSshIds + activeRnsIds + activeMoshIds + activeEtIds + activeLocalIds

        val currentTabs = _tabs.value.toMutableList()

        // Remove tabs for disconnected sessions
        val hadTabs = currentTabs.isNotEmpty()
        val removed = currentTabs.removeAll { tab ->
            when (tab.transportType) {
                "SSH" -> tab.sessionId !in activeSshIds ||
                    sshSessions[tab.sessionId]?.terminalSession == null
                "RETICULUM" -> tab.sessionId !in activeRnsIds ||
                    rnsSessions[tab.sessionId]?.reticulumSession == null
                "MOSH" -> tab.sessionId !in activeMoshIds ||
                    moshSessions[tab.sessionId]?.moshSession == null
                "ET" -> tab.sessionId !in activeEtIds ||
                    etSessions[tab.sessionId]?.etSession == null
                "LOCAL" -> tab.sessionId !in activeLocalIds ||
                    localSessions[tab.sessionId]?.localSession == null
                else -> true
            }
        }
        if (removed) {
            trackedSessionIds.retainAll(currentTabs.map { it.sessionId }.toSet())
            if (hadTabs && currentTabs.isEmpty()) {
                _navigateToConnections.value = true
            }
        }

        // Create tabs for new SSH sessions (or reattach to existing ones after Activity recreation)
        for (sessionId in activeSshIds) {
            if (sessionId in trackedSessionIds) continue

            // Reattach to an existing terminal session (ViewModel recreated after background)
            val existingTermSession = if (!sessionManager.isReadyForTerminal(sessionId) &&
                sessionManager.hasExistingTerminalSession(sessionId)
            ) {
                sessionManager.getExistingTerminalSession(sessionId)
            } else {
                null
            }

            if (existingTermSession == null && !sessionManager.isReadyForTerminal(sessionId)) continue

            val session = sshSessions[sessionId] ?: continue
            val baseLabel = session.label
            val tabLabel = generateTabLabel(baseLabel, session.profileId, currentTabs, sessionName = session.chosenSessionName)

            lateinit var emulator: TerminalEmulator
            val writeBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val mouseTracker = MouseModeTracker()
            val oscHandler = OscHandler()
            val cwdFlow = MutableStateFlow<String?>(null)
            val hyperlinkFlow = MutableStateFlow<String?>(null)
            oscHandler.onCwdChanged = { cwdFlow.value = it }
            oscHandler.onHyperlink = { uri -> hyperlinkFlow.value = uri }

            val termSession = existingTermSession?.also {
                // Reattach: rewire data callback to the new emulator/OSC handler
                it.replaceDataCallback { data, offset, length ->
                    oscHandler.process(data, offset, length)
                    mouseTracker.process(oscHandler.outputBuf, 0, oscHandler.outputLen)
                    val len = oscHandler.outputLen
                    if (len > 0) {
                        writeBuffer.append(oscHandler.outputBuf, 0, len)
                    }
                }
                Log.d(TAG, "Reattached to existing terminal session $sessionId")
            } ?: sessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    oscHandler.process(data, offset, length)
                    mouseTracker.process(oscHandler.outputBuf, 0, oscHandler.outputLen)
                    val len = oscHandler.outputLen
                    if (len > 0) {
                        writeBuffer.append(oscHandler.outputBuf, 0, len)
                    }
                },
            ) ?: continue

            val coalescer = InputCoalescer { data -> termSession.sendToSsh(data) }
            val scheme = terminalColorScheme.value
            val sshProfile = runBlocking(Dispatchers.IO) { connectionRepository.getById(session.profileId) }
            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(scheme.foreground),
                defaultBackground = Color(scheme.background),
                enableAltScreen = sshProfile?.disableAltScreen != true && sshProfile?.sessionManager != "screen",
                onKeyboardInput = { data -> coalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "SSH onResize: ${dims.columns}x${dims.rows}")
                    // Resize ALL tabs — only the active tab's Terminal composable
                    // fires onResize, so inactive tabs would keep stale PTY sizes
                    // unless we propagate here.
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    // Also resize the just-created session (not yet in _tabs)
                    termSession.resize(dims.columns, dims.rows)
                },
            )

            if (existingTermSession == null) {
                termSession.start()
            }

            val sshSessionId = session.sessionId
            val reconnectingFlow = sessionManager.sessions
                .map { it[sshSessionId]?.status == SessionState.Status.RECONNECTING }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

            currentTabs.add(
                TerminalTab(
                    sessionId = sshSessionId,
                    profileId = session.profileId,
                    colorTag = sshProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "SSH",
                    emulator = emulator,
                    mouseMode = mouseTracker.mouseMode,
                    activeMouseMode = mouseTracker.activeMouseMode,
                    bracketPasteMode = mouseTracker.bracketPasteMode,
                    oscHandler = oscHandler,
                    cwd = cwdFlow,
                    hyperlinkUri = hyperlinkFlow,
                    isReconnecting = reconnectingFlow,
                    secondsUntilDisconnect = NEVER_STALLS,
                    sendInput = { data -> termSession.sendToSsh(data) },
                    resize = { cols, rows -> termSession.resize(cols, rows) },
                    close = { termSession.close() },
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new Reticulum sessions
        for (sessionId in activeRnsIds) {
            if (sessionId in trackedSessionIds) continue
            if (!reticulumSessionManager.isReadyForTerminal(sessionId)) continue

            val session = rnsSessions[sessionId] ?: continue
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

            lateinit var emulator: TerminalEmulator
            val rnsWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val rnsMouseTracker = MouseModeTracker()
            val rnsOscHandler = OscHandler()
            val rnsCwdFlow = MutableStateFlow<String?>(null)
            val rnsHyperlinkFlow = MutableStateFlow<String?>(null)
            rnsOscHandler.onCwdChanged = { rnsCwdFlow.value = it }
            rnsOscHandler.onHyperlink = { uri -> rnsHyperlinkFlow.value = uri }
            val rnsSession = reticulumSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    rnsOscHandler.process(data, offset, length)
                    rnsMouseTracker.process(rnsOscHandler.outputBuf, 0, rnsOscHandler.outputLen)
                    val len = rnsOscHandler.outputLen
                    if (len > 0) {
                        rnsWriteBuffer.append(rnsOscHandler.outputBuf, 0, len)
                    }
                },
            ) ?: continue

            val rnsCoalescer = InputCoalescer { data -> rnsSession.sendInput(data) }
            val rnsScheme = terminalColorScheme.value
            val rnsProfile = runBlocking(Dispatchers.IO) { connectionRepository.getById(session.profileId) }
            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(rnsScheme.foreground),
                defaultBackground = Color(rnsScheme.background),
                enableAltScreen = rnsProfile?.disableAltScreen != true && rnsProfile?.sessionManager != "screen",
                onKeyboardInput = { data -> rnsCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "RNS onResize: ${dims.columns}x${dims.rows}")
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    rnsSession.resize(dims.columns, dims.rows)
                },
            )

            rnsSession.start()

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    colorTag = rnsProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "RETICULUM",
                    emulator = emulator,
                    mouseMode = rnsMouseTracker.mouseMode,
                    activeMouseMode = rnsMouseTracker.activeMouseMode,
                    bracketPasteMode = rnsMouseTracker.bracketPasteMode,
                    oscHandler = rnsOscHandler,
                    cwd = rnsCwdFlow,
                    hyperlinkUri = rnsHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    secondsUntilDisconnect = NEVER_STALLS,
                    sendInput = { data -> rnsSession.sendInput(data) },
                    resize = { cols, rows -> rnsSession.resize(cols, rows) },
                    close = { rnsSession.close() },
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new Mosh sessions
        for (sessionId in activeMoshIds) {
            if (sessionId in trackedSessionIds) continue
            if (!moshSessionManager.isReadyForTerminal(sessionId)) continue

            val session = moshSessions[sessionId] ?: continue
            val moshProfile = runBlocking(Dispatchers.IO) { connectionRepository.getById(session.profileId) }
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs, sessionName = moshProfile?.lastSessionName)

            lateinit var emulator: TerminalEmulator
            val moshWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val moshMouseTracker = MouseModeTracker()
            val moshOscHandler = OscHandler()
            val moshCwdFlow = MutableStateFlow<String?>(null)
            val moshHyperlinkFlow = MutableStateFlow<String?>(null)
            moshOscHandler.onCwdChanged = { moshCwdFlow.value = it }
            moshOscHandler.onHyperlink = { uri -> moshHyperlinkFlow.value = uri }

            // Defer initial command until shell prompt detected
            val moshInitialCmd = session.initialCommand
            val moshPendingSent = java.util.concurrent.atomic.AtomicBoolean(false)
            // Holder so lambda can reference session before it's assigned
            val moshSessionRef = arrayOfNulls<sh.haven.core.mosh.MoshSession>(1)

            val moshSession = moshSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    moshOscHandler.process(data, offset, length)
                    moshMouseTracker.process(moshOscHandler.outputBuf, 0, moshOscHandler.outputLen)
                    val len = moshOscHandler.outputLen
                    if (len > 0) {
                        moshWriteBuffer.append(moshOscHandler.outputBuf, 0, len)
                    }
                    // Send session manager command once shell prompt detected
                    if (moshInitialCmd != null && !moshPendingSent.get()) {
                        val raw = String(data, offset, length)
                        val stripped = raw.replace(Regex("\u001b(?:\\[[^a-zA-Z]*[a-zA-Z]|][^\u0007]*\u0007)"), "").trimEnd()
                        if (stripped.isNotEmpty()) {
                            val last = stripped.last()
                            if (last == '$' || last == '#' || last == '%' || last == '>') {
                                if (moshPendingSent.compareAndSet(false, true)) {
                                    Log.d(TAG, "Mosh: shell prompt detected ('$last'), sending session manager command")
                                    moshSessionRef[0]?.sendInput((moshInitialCmd + "\n").toByteArray())
                                }
                            }
                        }
                    }
                },
            ) ?: continue
            moshSessionRef[0] = moshSession

            val moshCoalescer = InputCoalescer { data -> moshSession.sendInput(data) }
            val moshScheme = terminalColorScheme.value
            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(moshScheme.foreground),
                enableAltScreen = moshProfile?.disableAltScreen != true && moshProfile?.sessionManager != "screen",
                defaultBackground = Color(moshScheme.background),
                onKeyboardInput = { data -> moshCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "MOSH onResize: ${dims.columns}x${dims.rows}")
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    moshSession.resize(dims.columns, dims.rows)
                },
            )

            moshSession.start()

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    colorTag = moshProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "MOSH",
                    emulator = emulator,
                    mouseMode = moshMouseTracker.mouseMode,
                    activeMouseMode = moshMouseTracker.activeMouseMode,
                    bracketPasteMode = moshMouseTracker.bracketPasteMode,
                    oscHandler = moshOscHandler,
                    cwd = moshCwdFlow,
                    hyperlinkUri = moshHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    secondsUntilDisconnect = moshSession.secondsUntilDisconnect,
                    sendInput = { data -> moshSession.sendInput(data) },
                    resize = { cols, rows -> moshSession.resize(cols, rows) },
                    close = { moshSession.close() },
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new ET sessions
        for (sessionId in activeEtIds) {
            if (sessionId in trackedSessionIds) continue
            if (!etSessionManager.isReadyForTerminal(sessionId)) continue

            val session = etSessions[sessionId] ?: continue
            val etProfile = runBlocking(Dispatchers.IO) { connectionRepository.getById(session.profileId) }
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs, sessionName = etProfile?.lastSessionName)

            lateinit var emulator: TerminalEmulator
            val etWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val etMouseTracker = MouseModeTracker()
            val etOscHandler = OscHandler()
            val etCwdFlow = MutableStateFlow<String?>(null)
            val etHyperlinkFlow = MutableStateFlow<String?>(null)
            etOscHandler.onCwdChanged = { etCwdFlow.value = it }
            etOscHandler.onHyperlink = { uri -> etHyperlinkFlow.value = uri }

            // Defer initial command until shell prompt detected
            val etInitialCmd = session.initialCommand
            val etPendingSent = java.util.concurrent.atomic.AtomicBoolean(false)
            val etSessionRef = arrayOfNulls<sh.haven.core.et.EtSession>(1)

            val etSession = etSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    etOscHandler.process(data, offset, length)
                    etMouseTracker.process(etOscHandler.outputBuf, 0, etOscHandler.outputLen)
                    val len = etOscHandler.outputLen
                    if (len > 0) {
                        etWriteBuffer.append(etOscHandler.outputBuf, 0, len)
                    }
                    // Send session manager command once shell prompt detected
                    if (etInitialCmd != null && !etPendingSent.get()) {
                        val raw = String(data, offset, length)
                        val stripped = raw.replace(Regex("\u001b(?:\\[[^a-zA-Z]*[a-zA-Z]|][^\u0007]*\u0007)"), "").trimEnd()
                        if (stripped.isNotEmpty()) {
                            val last = stripped.last()
                            if (last == '$' || last == '#' || last == '%' || last == '>') {
                                if (etPendingSent.compareAndSet(false, true)) {
                                    Log.d(TAG, "ET: shell prompt detected ('$last'), sending session manager command")
                                    etSessionRef[0]?.sendInput((etInitialCmd + "\n").toByteArray())
                                }
                            }
                        }
                    }
                },
            ) ?: continue
            etSessionRef[0] = etSession

            val etCoalescer = InputCoalescer { data -> etSession.sendInput(data) }
            val etScheme = terminalColorScheme.value
            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(etScheme.foreground),
                defaultBackground = Color(etScheme.background),
                enableAltScreen = etProfile?.disableAltScreen != true && etProfile?.sessionManager != "screen",
                onKeyboardInput = { data -> etCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "ET onResize: ${dims.columns}x${dims.rows}")
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    etSession.resize(dims.columns, dims.rows)
                },
            )

            etSession.start()

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    colorTag = etProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "ET",
                    emulator = emulator,
                    mouseMode = etMouseTracker.mouseMode,
                    activeMouseMode = etMouseTracker.activeMouseMode,
                    bracketPasteMode = etMouseTracker.bracketPasteMode,
                    oscHandler = etOscHandler,
                    cwd = etCwdFlow,
                    hyperlinkUri = etHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    secondsUntilDisconnect = NEVER_STALLS,
                    sendInput = { data -> etSession.sendInput(data) },
                    resize = { cols, rows -> etSession.resize(cols, rows) },
                    close = { etSession.close() },
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new Local sessions
        for (sessionId in activeLocalIds) {
            if (sessionId in trackedSessionIds) continue
            if (!localSessionManager.isReadyForTerminal(sessionId)) continue

            val session = localSessions[sessionId] ?: continue
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

            lateinit var emulator: TerminalEmulator
            val localWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val localMouseTracker = MouseModeTracker()
            val localOscHandler = OscHandler()
            val localCwdFlow = MutableStateFlow<String?>(null)
            val localHyperlinkFlow = MutableStateFlow<String?>(null)
            localOscHandler.onCwdChanged = { localCwdFlow.value = it }
            localOscHandler.onHyperlink = { uri -> localHyperlinkFlow.value = uri }
            val localSession = localSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    localOscHandler.process(data, offset, length)
                    localMouseTracker.process(localOscHandler.outputBuf, 0, localOscHandler.outputLen)
                    val len = localOscHandler.outputLen
                    if (len > 0) {
                        localWriteBuffer.append(localOscHandler.outputBuf, 0, len)
                    }
                },
            ) ?: continue

            val localCoalescer = InputCoalescer { data -> localSession.sendInput(data) }
            val localScheme = terminalColorScheme.value
            val localProfile = runBlocking(Dispatchers.IO) { connectionRepository.getById(session.profileId) }
            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(localScheme.foreground),
                defaultBackground = Color(localScheme.background),
                enableAltScreen = localProfile?.disableAltScreen != true && localProfile?.sessionManager != "screen",
                onKeyboardInput = { data -> localCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "LOCAL onResize: ${dims.columns}x${dims.rows}")
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    localSession.resize(dims.columns, dims.rows)
                },
            )

            localSession.start()

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    colorTag = localProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "LOCAL",
                    emulator = emulator,
                    mouseMode = localMouseTracker.mouseMode,
                    activeMouseMode = localMouseTracker.activeMouseMode,
                    bracketPasteMode = localMouseTracker.bracketPasteMode,
                    oscHandler = localOscHandler,
                    cwd = localCwdFlow,
                    hyperlinkUri = localHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    secondsUntilDisconnect = NEVER_STALLS,
                    sendInput = { data -> localSession.sendInput(data) },
                    resize = { cols, rows -> localSession.resize(cols, rows) },
                    close = { localSession.close() },
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Refresh SSH tab labels when the underlying session's chosenSessionName changes
        // (e.g. after a remote rename). Only updates label-shaped tabs; leaves bespoke
        // labels alone.
        for (i in currentTabs.indices) {
            val tab = currentTabs[i]
            if (tab.transportType != "SSH") continue
            val name = sshSessions[tab.sessionId]?.chosenSessionName
            if (!name.isNullOrBlank() && tab.label != name) {
                currentTabs[i] = tab.copy(label = name)
            }
        }

        _tabs.value = currentTabs

        // Clamp active index
        if (_activeTabIndex.value >= currentTabs.size && currentTabs.isNotEmpty()) {
            _activeTabIndex.value = currentTabs.size - 1
        }
    }

    /**
     * Generate a tab label using the session name when available.
     * Falls back to connection label with numeric suffix for duplicates.
     */
    private fun generateTabLabel(
        baseLabel: String,
        profileId: String,
        existingTabs: List<TerminalTab>,
        sessionName: String? = null,
    ): String {
        if (!sessionName.isNullOrBlank()) return sessionName
        return baseLabel
    }

    fun selectTab(index: Int) {
        if (index in _tabs.value.indices) {
            _activeTabIndex.value = index
        }
    }

    fun moveTab(fromIndex: Int, direction: Int) {
        val toIndex = fromIndex + direction
        val tabs = _tabs.value.toMutableList()
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return
        tabs.add(toIndex, tabs.removeAt(fromIndex))
        _tabs.value = tabs
        // Keep the moved tab selected
        if (_activeTabIndex.value == fromIndex) {
            _activeTabIndex.value = toIndex
        } else if (_activeTabIndex.value == toIndex) {
            _activeTabIndex.value = fromIndex
        }
    }

    fun selectTabByProfileId(profileId: String) {
        val index = _tabs.value.indexOfFirst { it.profileId == profileId }
        if (index >= 0) {
            _activeTabIndex.value = index
        } else {
            // Tab not yet created by syncSessions — wait for it
            viewModelScope.launch {
                try {
                    withTimeout(5000) {
                        _tabs.first { tabs -> tabs.any { it.profileId == profileId } }
                    }
                    val newIndex = _tabs.value.indexOfFirst { it.profileId == profileId }
                    if (newIndex >= 0) {
                        _activeTabIndex.value = newIndex
                    }
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "selectTabByProfileId: tab for $profileId not created within 5s")
                    val profile = runCatching { connectionRepository.getById(profileId) }.getOrNull()
                    val mgr = profile?.sessionManager ?: "tmux"
                    val msg = if (profile?.isLocal == true) {
                        "Terminal failed to open — install $mgr inside the local PRoot " +
                            "(e.g. \"apt install $mgr\"), or change the session manager " +
                            "in the connection's settings."
                    } else {
                        "Terminal failed to open — install $mgr on the remote host " +
                            "(e.g. \"sudo apt install $mgr\" or \"sudo dnf install $mgr\"), " +
                            "or change the session manager in the connection's settings."
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun selectTabBySessionId(sessionId: String) {
        val index = _tabs.value.indexOfFirst { it.sessionId == sessionId }
        if (index >= 0) {
            _activeTabIndex.value = index
        }
    }

    fun closeTab(sessionId: String) {
        val tab = _tabs.value.firstOrNull { it.sessionId == sessionId }
        if (tab != null && tab.transportType in TRANSPORTS_NEEDING_EXPLICIT_DETACH) {
            viewModelScope.launch(Dispatchers.IO) {
                sendSessionManagerDetach(tab)
                removeTabAndSync(sessionId)
            }
        } else {
            removeTabAndSync(sessionId)
        }
    }

    /** Close all sessions for a profile (called from connections disconnect). */
    fun closeSession(profileId: String) {
        val profileTabs = _tabs.value.filter { it.profileId == profileId }
        val needsDetach = profileTabs.filter { it.transportType in TRANSPORTS_NEEDING_EXPLICIT_DETACH }
        if (needsDetach.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val smName = effectiveSessionManagerName(profileId)
                val detachBytes = detachBytesFor(smName)
                if (detachBytes != null) {
                    needsDetach.forEach { it.sendInput(detachBytes) }
                    kotlinx.coroutines.delay(SESSION_MANAGER_DETACH_DELAY_MS)
                }
                removeAllForProfileAndSync(profileId)
            }
        } else {
            removeAllForProfileAndSync(profileId)
        }
    }

    private fun removeTabAndSync(sessionId: String) {
        if (sessionManager.sessions.value.containsKey(sessionId)) {
            sessionManager.removeSession(sessionId)
        } else if (moshSessionManager.sessions.value.containsKey(sessionId)) {
            moshSessionManager.removeSession(sessionId)
        } else if (etSessionManager.sessions.value.containsKey(sessionId)) {
            etSessionManager.removeSession(sessionId)
        } else if (localSessionManager.sessions.value.containsKey(sessionId)) {
            localSessionManager.removeSession(sessionId)
        } else {
            reticulumSessionManager.removeSession(sessionId)
        }
        trackedSessionIds.remove(sessionId)
        syncSessions()
    }

    private fun removeAllForProfileAndSync(profileId: String) {
        sessionManager.removeAllSessionsForProfile(profileId)
        reticulumSessionManager.removeAllSessionsForProfile(profileId)
        moshSessionManager.removeAllSessionsForProfile(profileId)
        etSessionManager.removeAllSessionsForProfile(profileId)
        localSessionManager.removeAllSessionsForProfile(profileId)
        trackedSessionIds.removeAll(
            _tabs.value.filter { it.profileId == profileId }.map { it.sessionId }.toSet()
        )
        syncSessions()
    }

    /**
     * Send the session manager's detach key sequence over a Mosh or ET tab so
     * the server-side multiplexer (zellij/tmux/screen) drops this client before
     * we tear the transport down. SSH gets HUP for free when the channel
     * closes; Mosh and ET keep the PTY alive across disconnects, so without
     * this nudge the next reconnect looks like a second concurrent client.
     */
    private suspend fun sendSessionManagerDetach(tab: TerminalTab) {
        val smName = effectiveSessionManagerName(tab.profileId)
        val detachBytes = detachBytesFor(smName) ?: return
        tab.sendInput(detachBytes)
        kotlinx.coroutines.delay(SESSION_MANAGER_DETACH_DELAY_MS)
    }

    private suspend fun effectiveSessionManagerName(profileId: String): String {
        val profile = connectionRepository.getById(profileId)
        return profile?.sessionManager?.uppercase()
            ?: preferencesRepository.sessionManager.first().name
    }

    private fun detachBytesFor(smName: String): ByteArray? = when (smName) {
        "TMUX", "BYOBU" -> byteArrayOf(0x02, 'd'.code.toByte()) // Ctrl+B d
        "ZELLIJ" -> byteArrayOf(0x0F, 'd'.code.toByte())        // Ctrl+O d
        "SCREEN" -> byteArrayOf(0x01, 'd'.code.toByte())        // Ctrl+A d
        else -> null
    }

    /** When non-null, the UI should show a session picker for a new tab. */
    data class NewTabSessionSelection(
        val profileId: String,
        val managerLabel: String,
        val sessionNames: List<String>,
        val sessionId: String,
        val manager: SessionManager = SessionManager.NONE,
        val error: String? = null,
        /** Pre-filled name for the "Create new session" text field. (#112) */
        val suggestedNewName: String = "",
    )

    private val _newTabSessionPicker = MutableStateFlow<NewTabSessionSelection?>(null)
    val newTabSessionPicker: StateFlow<NewTabSessionSelection?> = _newTabSessionPicker.asStateFlow()

    private val _newTabLoading = MutableStateFlow(false)
    val newTabLoading: StateFlow<Boolean> = _newTabLoading.asStateFlow()

    private val _newTabMessage = MutableStateFlow<String?>(null)
    val newTabMessage: StateFlow<String?> = _newTabMessage.asStateFlow()
    fun dismissNewTabMessage() { _newTabMessage.value = null }

    /**
     * Add a new tab by creating a fresh connection to the same server as the current tab.
     */
    fun addTab() {
        val activeTab = _tabs.value.getOrNull(_activeTabIndex.value)
        if (activeTab == null) {
            Log.w(TAG, "addTab: no active tab (index=${_activeTabIndex.value}, tabs=${_tabs.value.size})")
            _newTabMessage.value = appContext.getString(R.string.terminal_new_tab_no_active)
            return
        }

        if (activeTab.transportType == "RETICULUM") {
            addReticulumTab(activeTab)
            return
        }

        if (activeTab.transportType == "MOSH") {
            // Mosh tunnels each session via its own UDP exchange, so a "new
            // tab" means a fresh Mosh connection — same shape as SSH multi-
            // tab. We don't support that yet. Within the existing tab,
            // Zellij/tmux session-switching keys still work to add named
            // sessions to the same tunnel. (#113)
            _newTabMessage.value = "Mosh supports one tab per connection. " +
                "To open another session inside this tab, use Zellij " +
                "(Ctrl-o then s) or tmux's prefix. For multiple tabs, " +
                "switch the profile to SSH transport."
            return
        }

        if (activeTab.transportType == "ET") {
            // Same shape as Mosh — ET tunnels each session via its own SSH
            // bootstrap, so a new tab means a new connection. The picker
            // dialog's "Create new session" option is for sessions inside
            // the existing tunnel, not for new tabs — clarify the
            // distinction. (#113)
            _newTabMessage.value = "Eternal Terminal supports one tab per " +
                "connection. To open another session inside this tab, use " +
                "Zellij (Ctrl-o then s) or tmux's prefix. For multiple " +
                "tabs, switch the profile to SSH transport."
            return
        }

        if (activeTab.transportType == "LOCAL") {
            addLocalTab(activeTab)
            return
        }

        addSshTabForProfile(activeTab.profileId)
    }

    private fun addLocalTab(activeTab: TerminalTab) {
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val label = activeTab.label
                val existingSession = localSessionManager.sessions.value.values
                    .find { it.profileId == activeTab.profileId }
                val sessionId = localSessionManager.registerSession(
                    activeTab.profileId, label,
                    useAndroidShell = existingSession?.useAndroidShell ?: false,
                )
                localSessionManager.connectSession(sessionId)
                syncSessions()
                selectTabBySessionId(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addLocalTab failed: ${e.message}", e)
                _newTabMessage.value = appContext.getString(
                    R.string.terminal_new_tab_connection_failed,
                    e.message ?: e.javaClass.simpleName,
                )
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    /**
     * Add a new SSH tab for a profile by creating a fresh connection.
     * Called from [addTab] (clone current tab) and from the Connections screen
     * "New Session" context menu item.
     */
    fun addSshTabForProfile(profileId: String) {
        val configPair = sessionManager.getConnectionConfigForProfile(profileId)
        if (configPair == null) {
            Log.w(TAG, "addSshTabForProfile: no connection config for profile $profileId")
            _newTabMessage.value = appContext.getString(R.string.terminal_new_tab_no_config)
            return
        }
        val (config, sshSessionMgr) = configPair

        // Derive label from an existing tab or the profile's config host
        val existingTab = _tabs.value.firstOrNull { it.profileId == profileId }
        val label = existingTab?.label ?: config.host

        viewModelScope.launch {
            _newTabLoading.value = true
            val client = SshClient()
            val sessionId = sessionManager.registerSession(profileId, label, client)
            try {
                val hostKeyEntry = withContext(Dispatchers.IO) {
                    client.connect(config)
                }

                // Silent TOFU: auto-accept new hosts, reject key changes
                when (val hkResult = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> { /* matches — continue */ }
                    is HostKeyResult.NewHost -> {
                        hostKeyVerifier.accept(hkResult.entry)
                    }
                    is HostKeyResult.KeyChanged -> {
                        client.disconnect()
                        sessionManager.removeSession(sessionId)
                        Log.w(TAG, "Host key changed for ${config.host}:${config.port} — aborting new tab")
                        _newTabMessage.value = appContext.getString(
                            R.string.terminal_new_tab_host_key_changed,
                            "${config.host}:${config.port}",
                        )
                        _newTabLoading.value = false
                        return@launch
                    }
                }

                sessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr)

                val listCmd = sshSessionMgr.listCommand
                if (listCmd != null) {
                    val existingSessions = withContext(Dispatchers.IO) {
                        try {
                            val result = client.execCommand(listCmd)
                            if (result.exitStatus == 0) {
                                SessionManager.parseSessionList(sshSessionMgr, result.stdout)
                            } else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    if (existingSessions.isNotEmpty()) {
                        val baseLabel = sessionManager.getSession(sessionId)?.label
                            ?: sessionId.take(8)
                        _newTabSessionPicker.value = NewTabSessionSelection(
                            profileId = profileId,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                            sessionId = sessionId,
                            manager = sshSessionMgr,
                            suggestedNewName = generateUniqueSessionName(baseLabel, existingSessions),
                        )
                        _newTabLoading.value = false
                        return@launch
                    }
                }

                finishNewSshTab(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addSshTabForProfile failed", e)
                sessionManager.removeSession(sessionId)
                val detail = e.message ?: e.javaClass.simpleName
                _newTabMessage.value = appContext.getString(R.string.terminal_new_tab_connection_failed, detail)
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    /**
     * Add a new Reticulum tab to the same destination as the current tab.
     */
    private fun addReticulumTab(activeTab: TerminalTab) {
        val profileId = activeTab.profileId
        val rnsSession = reticulumSessionManager.sessions.value.values
            .firstOrNull { it.profileId == profileId }
        if (rnsSession == null) {
            _newTabMessage.value = appContext.getString(R.string.terminal_new_tab_reticulum_no_session)
            return
        }
        val label = activeTab.label
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val sessionId = reticulumSessionManager.registerSession(
                    profileId = profileId,
                    label = label,
                    destinationHash = rnsSession.destinationHash,
                )
                withContext(Dispatchers.IO) {
                    reticulumSessionManager.connectSession(
                        sessionId = sessionId,
                        configDir = "", // Already initialised
                        host = "",
                        port = 0,
                    )
                }
                syncSessions()
                selectTabBySessionId(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addReticulumTab failed", e)
                _newTabMessage.value = appContext.getString(
                    R.string.terminal_new_tab_connection_failed,
                    e.message ?: e.javaClass.simpleName,
                )
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    /** Open a new tab attached to a named remote session (tmux/zellij/screen).
     *  Creates a new SSH connection to the same profile and attaches to the named session. */
    fun openRemoteSession(profileId: String, sessionName: String) {
        val configPair = sessionManager.getConnectionConfigForProfile(profileId)
        if (configPair == null) {
            _newTabMessage.value = appContext.getString(R.string.terminal_new_tab_no_config)
            return
        }
        val (config, sshSessionMgr) = configPair
        val existingTab = _tabs.value.firstOrNull { it.profileId == profileId }
        val label = existingTab?.label ?: config.host

        viewModelScope.launch {
            _newTabLoading.value = true
            val client = SshClient()
            val sessionId = sessionManager.registerSession(profileId, label, client)
            try {
                val hostKeyEntry = withContext(Dispatchers.IO) { client.connect(config) }
                when (val hkResult = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> hostKeyVerifier.accept(hkResult.entry)
                    is HostKeyResult.KeyChanged -> {
                        client.disconnect()
                        sessionManager.removeSession(sessionId)
                        _newTabMessage.value = appContext.getString(
                            R.string.terminal_new_tab_host_key_changed,
                            "${config.host}:${config.port}",
                        )
                        _newTabLoading.value = false
                        return@launch
                    }
                }
                sessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr)
                sessionManager.setChosenSessionName(sessionId, sessionName)
                finishNewSshTab(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "openRemoteSession failed", e)
                sessionManager.removeSession(sessionId)
                _newTabMessage.value = appContext.getString(
                    R.string.terminal_new_tab_connection_failed,
                    e.message ?: e.javaClass.simpleName,
                )
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    fun onNewTabSessionSelected(sessionId: String, sessionName: String?) {
        val remoteNames = _newTabSessionPicker.value?.sessionNames ?: emptyList()
        _newTabSessionPicker.value = null
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val effectiveName = sessionName ?: generateUniqueSessionName(
                    sessionManager.getSession(sessionId)?.label ?: sessionId.take(8),
                    remoteNames,
                )
                sessionManager.setChosenSessionName(sessionId, effectiveName)
                finishNewSshTab(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "onNewTabSessionSelected failed", e)
                sessionManager.removeSession(sessionId)
                _newTabMessage.value = appContext.getString(
                    R.string.terminal_new_tab_shell_failed,
                    e.message ?: e.javaClass.simpleName,
                )
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    private fun generateUniqueSessionName(label: String, remoteNames: List<String>): String {
        val base = label.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val existing = remoteNames.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base-$i" in existing) i++
        return "$base-$i"
    }

    fun killRemoteSession(sessionName: String) {
        val sel = _newTabSessionPicker.value ?: return
        val killCmd = sel.manager.killCommand?.invoke(sessionName) ?: return
        val session = sessionManager.getSession(sel.sessionId) ?: return

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    session.client.execCommand(killCmd)
                }
                val listCmd = sel.manager.listCommand ?: return@launch
                val updated = withContext(Dispatchers.IO) {
                    try {
                        val result = session.client.execCommand(listCmd)
                        if (result.exitStatus == 0) {
                            SessionManager.parseSessionList(sel.manager, result.stdout)
                        } else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                if (updated.isNotEmpty()) {
                    _newTabSessionPicker.value = sel.copy(sessionNames = updated)
                } else {
                    _newTabSessionPicker.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "killRemoteSession failed", e)
            }
        }
    }

    fun renameRemoteSession(oldName: String, newName: String) {
        val sel = _newTabSessionPicker.value ?: return
        val renameCmd = sel.manager.renameCommand?.invoke(oldName, newName) ?: return
        val session = sessionManager.getSession(sel.sessionId) ?: return

        viewModelScope.launch {
            try {
                val renameResult = withContext(Dispatchers.IO) {
                    session.client.execCommand(renameCmd)
                }
                val renameSucceeded = renameResult.exitStatus == 0
                val renameError = if (!renameSucceeded) {
                    Log.w(TAG, "renameRemoteSession failed: exit=${renameResult.exitStatus} stderr='${renameResult.stderr}'")

                    renameResult.stderr.ifBlank { "Rename failed (exit ${renameResult.exitStatus})" }
                } else null
                // Give the session manager a moment to propagate the rename
                kotlinx.coroutines.delay(500)
                val listCmd = sel.manager.listCommand ?: return@launch
                val updated = withContext(Dispatchers.IO) {
                    try {
                        val result = session.client.execCommand(listCmd)
                        if (result.exitStatus == 0) {
                            SessionManager.parseSessionList(sel.manager, result.stdout)
                        } else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                if (renameSucceeded) {
                    propagateSessionRename(sel.profileId, oldName, newName)
                }
                if (updated.isNotEmpty()) {
                    _newTabSessionPicker.value = sel.copy(sessionNames = updated, error = renameError)
                } else {
                    _newTabSessionPicker.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "renameRemoteSession failed", e)
            }
        }
    }

    /**
     * Returns the current chosen session name if the tab's underlying SSH
     * session is wrapped in a session manager (tmux/zellij/screen/byobu) that
     * supports rename. Null if there's nothing to rename — used by the long-
     * press menu to decide whether to surface the Rename action.
     */
    fun renameableSessionName(sessionId: String): String? {
        val s = sessionManager.getSession(sessionId) ?: return null
        val name = s.chosenSessionName?.takeIf { it.isNotBlank() } ?: return null
        if (s.sessionManager.renameCommand == null) return null
        return name
    }

    /**
     * Rename the tmux/zellij/screen session backing a connected tab — no need
     * to open a duplicate session via the picker. On success, propagates the
     * new name to the profile's lastSessionName + chosenSessionName + tab label.
     */
    fun renameAttachedSession(sessionId: String, newName: String) {
        val s = sessionManager.getSession(sessionId) ?: return
        val oldName = s.chosenSessionName?.takeIf { it.isNotBlank() } ?: return
        val cmd = s.sessionManager.renameCommand?.invoke(oldName, newName) ?: return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { s.client.execCommand(cmd) }
                if (result.exitStatus != 0) {
                    Log.w(TAG, "renameAttachedSession failed: exit=${result.exitStatus} stderr='${result.stderr}'")
                    _newTabMessage.value = result.stderr.ifBlank { "Rename failed (exit ${result.exitStatus})" }
                    return@launch
                }
                propagateSessionRename(s.profileId, oldName, newName)
            } catch (e: Exception) {
                Log.e(TAG, "renameAttachedSession failed", e)
                _newTabMessage.value = "Failed to rename session: ${e.message}"
            }
        }
    }

    /**
     * After a successful remote rename, update the cached session name everywhere
     * Haven still references the old name: the profile's stored `lastSessionName`
     * (used for restore-on-reconnect), and any live SSH session record on this
     * profile whose `chosenSessionName` matches. Tab labels follow via syncSessions.
     */
    private suspend fun propagateSessionRename(profileId: String, oldName: String, newName: String) {
        val oldSan = sanitizeSessionName(oldName)
        val newSan = sanitizeSessionName(newName)
        if (oldSan == newSan) return
        val profile = connectionRepository.getById(profileId) ?: return
        profile.lastSessionName?.takeIf { it.isNotBlank() }?.let { current ->
            val parts = current.split("|")
            if (oldSan in parts) {
                val updated = parts.map { if (it == oldSan) newSan else it }.joinToString("|")
                if (updated != current) connectionRepository.save(profile.copy(lastSessionName = updated))
            }
        }
        sessionManager.sessions.value.values
            .filter { it.profileId == profileId && sanitizeSessionName(it.chosenSessionName ?: "") == oldSan }
            .forEach { sessionManager.setChosenSessionName(it.sessionId, newName) }
    }

    private fun sanitizeSessionName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "-")

    fun dismissNewTabSessionPicker() {
        val sel = _newTabSessionPicker.value ?: return
        _newTabSessionPicker.value = null
        sessionManager.removeSession(sel.sessionId)
    }

    private suspend fun finishNewSshTab(sessionId: String) {
        withContext(Dispatchers.IO) {
            sessionManager.openShellForSession(sessionId)
        }
        sessionManager.updateStatus(sessionId, SessionState.Status.CONNECTED)
        syncSessions()
        selectTabBySessionId(sessionId)
    }
}
