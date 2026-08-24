package sh.haven.feature.terminal

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.OpenKeychainClientFactory
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshSessionManager.SessionState
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import sh.haven.core.redact.LogRedact

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
    private var closed = false

    @Synchronized
    fun record(data: ByteArray, offset: Int, length: Int) {
        // A final write may race the owner's dispose() now that the emulator
        // (and its write buffer) can outlive the recorder; swallow it instead
        // of throwing on the main thread (#290 issue #2 lifecycle).
        if (closed) return
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
        if (closed) return
        closed = true
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
internal class EmulatorWriteBuffer(
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
 * Applies the historical IME double-fire workaround to a batch of
 * single-byte terminal input.
 *
 * Only duplicate printable ASCII bytes are eligible for deduplication.
 * Control bytes must be preserved because repeated control characters
 * can be intentional terminal input. In particular, Vietnamese Gboard
 * replacement can legitimately emit two consecutive DEL (0x7F) bytes.
 */
internal fun normalizeCoalescedInput(buffer: List<Byte>): ByteArray {
    val isImeDedupCandidate =
        buffer.size == 2 &&
            buffer[0] == buffer[1] &&
            (buffer[0].toInt() and 0xFF) in 0x20..0x7E

    return if (isImeDedupCandidate) {
        byteArrayOf(buffer[0])
    } else {
        buffer.toByteArray()
    }
}

/**
 * Coalesces rapid single-byte inputs into a batch and applies the historical
 * IME double-fire workaround to exactly two identical printable ASCII bytes.
 *
 * Android IMEs may fire both commitText and sendKeyEvent for the same
 * keystroke, causing onKeyboardInput to be called twice with the same byte.
 * Both calls happen within a single Handler message, so a posted Runnable
 * flushes after all input from the current message is processed.
 *
 * Repeated control bytes are intentionally preserved. They can represent
 * legitimate terminal input; for example, Vietnamese Gboard replacement may
 * emit two consecutive DEL (0x7F) bytes before sending replacement text.
 *
 * Paste "aa" still matches the printable-ASCII heuristic, while longer input
 * sequences such as "aab" and "43339" are preserved.
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
            bytes = normalizeCoalescedInput(buffer)
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
    /** True while the remote is on the alternate screen buffer (vim/less/…). (#175) */
    val altScreen: StateFlow<Boolean>,
    /** True while DECCKM (application cursor keys) is set — alt-screen swipe
     *  arrows are SS3-encoded (ESC O A) instead of CSI (ESC [ A). (#255) */
    val cursorKeyAppMode: StateFlow<Boolean>,
    val oscHandler: OscHandler,
    /** Injects raw bytes through this tab's real output pipeline (OSC scan
     *  → mouse-mode scan → emulator), as if received from the remote.
     *  Used by the MCP agent's feed_terminal_output test tool. */
    val feedOutput: (ByteArray, Int, Int) -> Unit,
    val cwd: StateFlow<String?>,
    val hyperlinkUri: StateFlow<String?>,
    val isReconnecting: StateFlow<Boolean>,
    /** Non-null when the transport has gone silent and is counting down to a
     *  forced disconnect. Currently only Mosh emits a value; other transports
     *  stay null. */
    val stallSeconds: StateFlow<Int?>,
    val sendInput: (ByteArray) -> Unit,
    val resize: (Int, Int) -> Unit,
    val close: () -> Unit,
    /** Effective terminal colour scheme for this tab — the profile's
     *  override (#144) when set, or null to follow the live global
     *  preference. The screen resolves null tabs to the global scheme
     *  (which itself respects the system-driven auto-switch toggle) so
     *  that flipping the system between light and dark mode repaints
     *  every tab that doesn't have an explicit override. */
    val colorScheme: UserPreferencesRepository.TerminalColorScheme? = null,
    /** Per-tab terminal background opacity override (0.0–1.0). Null = follow
     *  the live global preference. The screen resolves null at render time so
     *  changing the global slider repaints tabs without an explicit override. */
    val backgroundOpacity: Float? = null,
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
    private val sshSessionAttacher: sh.haven.core.ssh.SshSessionAttacher,
    private val reticulumSessionManager: ReticulumSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val btSerialSessionManager: sh.haven.core.btserial.BtSerialSessionManager,
    private val bleSerialSessionManager: sh.haven.core.bleserial.BleSerialSessionManager,
    private val usbSerialSessionManager: sh.haven.core.usbserial.UsbSerialSessionManager,
    private val usbBroker: sh.haven.core.usb.UsbBroker,
    private val localSessionManager: sh.haven.core.local.LocalSessionManager,
    private val hostKeyVerifier: HostKeyVerifier,
    /**
     * Security-key (FIDO2/SK) authenticator, wired onto the SshClients built
     * for new sessions/tabs below. Without it an SK-key profile NPEs in
     * SshClient.addFidoIdentity when opening a second session (the primary
     * connect path in ConnectionsViewModel already wires this).
     */
    private val fidoAuthenticator: sh.haven.core.fido.FidoAuthenticator,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: sh.haven.core.data.repository.ConnectionRepository,
    private val tunnelResolver: sh.haven.core.tunnel.TunnelResolver,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val userMessageBus: sh.haven.core.data.message.UserMessageBus,
    /**
     * Coordinator for the paperclip "attach" flow — see
     * [sh.haven.feature.sftp.attach.TerminalAttachCoordinator]. Singleton-
     * scoped, shared with [sh.haven.feature.sftp.SftpViewModel] so the
     * SFTP screen renders the folder picker while this VM awaits the
     * destination choice.
     */
    val attachCoordinator: sh.haven.feature.sftp.attach.TerminalAttachCoordinator,
    /**
     * Singleton index of live terminal handles by sessionId. Populated
     * on tab creation here and on selection/scroll controller hook from
     * [TerminalScreen]; consumed by the MCP agent transport so it can
     * inspect / drive the terminal without going through a Compose scope.
     */
    val terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
    /** Owns SSH emulators created at connect time; the VM adopts them (#290 issue #2). */
    private val sshEmulatorOwner: SshTerminalEmulatorOwner,
    /** ZXing-backed barcode / QR decoder; bitmap-in, string-or-null-out. */
    private val barcodeDecoder: sh.haven.core.scan.BarcodeDecoder,
    /** Tesseract-backed OCR engine; bitmap-in, string-or-null-out. */
    private val textRecognizer: sh.haven.core.scan.TextRecognizer,
) : ViewModel() {

    /**
     * Run the paperclip flow on this VM's scope so it survives the screen
     * leaving composition while the user navigates the SFTP picker. On
     * success the returned (already shell-quoted) payload is fed to the
     * active tab's stdin so it lands at the cursor in the live shell.
     */
    fun runAttachFlow(
        sourceUri: android.net.Uri,
        fileName: String,
        fileSize: Long,
        initialProfileId: String?,
    ) {
        viewModelScope.launch {
            val payload = attachCoordinator.attach(
                sourceUri = sourceUri,
                fileName = fileName,
                fileSize = fileSize,
                initialProfileId = initialProfileId,
            ) ?: return@launch
            _pendingAttachInjection.value = payload
        }
    }

    /** What kind of recognition to run over the [runScanFlow] image. */
    enum class ScanMode { BARCODE, OCR }

    /**
     * Recognised text waiting to be injected at the cursor. The screen
     * drains this on the main thread, wrapping in bracket-paste when the
     * active emulator has it enabled, and clears the flow via
     * [consumeScanInjection]. StateFlow rather than a Channel so a screen
     * recomposition that happens mid-recognition still sees the result.
     */
    private val _pendingScanInjection = MutableStateFlow<String?>(null)
    val pendingScanInjection: StateFlow<String?> = _pendingScanInjection.asStateFlow()
    fun consumeScanInjection() { _pendingScanInjection.value = null }

    /** Same as [pendingScanInjection], but for the Attach → "Send a file" flow. */
    private val _pendingAttachInjection = MutableStateFlow<String?>(null)
    val pendingAttachInjection: StateFlow<String?> = _pendingAttachInjection.asStateFlow()
    fun consumeAttachInjection() { _pendingAttachInjection.value = null }

    /**
     * Decode [sourceUri] into a bitmap, hand it to the appropriate scan
     * engine, and publish the result for the screen to paste. Empty
     * results / no-match outcomes surface via [newTabMessage] so the user
     * sees why nothing landed at the cursor.
     */
    fun runScanFlow(sourceUri: android.net.Uri, mode: ScanMode) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { decodeBitmapFromUri(sourceUri) }
            }.getOrElse {
                Log.w(TAG, "Scan: bitmap decode failed for $sourceUri", it)
                _newTabMessage.value = appContext.getString(
                    R.string.terminal_scan_failed,
                    it.message ?: it.javaClass.simpleName,
                )
                return@launch
            } ?: run {
                _newTabMessage.value = appContext.getString(
                    R.string.terminal_scan_failed,
                    "could not decode image",
                )
                return@launch
            }

            val result: String? = try {
                when (mode) {
                    ScanMode.BARCODE -> withContext(Dispatchers.Default) { barcodeDecoder.decode(bitmap) }
                    ScanMode.OCR -> textRecognizer.recognize(bitmap)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Scan: recognition failed", t)
                _newTabMessage.value = appContext.getString(
                    R.string.terminal_scan_failed,
                    t.message ?: t.javaClass.simpleName,
                )
                bitmap.recycle()
                return@launch
            }
            bitmap.recycle()

            if (result.isNullOrEmpty()) {
                _newTabMessage.value = appContext.getString(
                    when (mode) {
                        ScanMode.BARCODE -> R.string.terminal_scan_no_code
                        ScanMode.OCR -> R.string.terminal_scan_no_text
                    },
                )
                return@launch
            }
            _pendingScanInjection.value = result
        }
    }

    private fun decodeBitmapFromUri(uri: android.net.Uri): android.graphics.Bitmap? {
        return appContext.contentResolver.openInputStream(uri)?.use { input ->
            // ARGB_8888 by default; ZXing needs ARGB pixels, Tesseract handles
            // either. inMutable=false because we don't draw into the result.
            android.graphics.BitmapFactory.decodeStream(input)
        }
    }

    /**
     * Cached `verboseLoggingEnabled` so [createRecorderIfEnabled] — called from
     * syncSessions on the main thread during connection churn — doesn't do a
     * blocking DataStore read on the UI thread (cold reads were an ANR source).
     * (#208 finding 14)
     */
    @Volatile
    private var verboseLoggingCached = false

    init {
        viewModelScope.launch {
            preferencesRepository.verboseLoggingEnabled.collect { verboseLoggingCached = it }
        }
        // Cross-tab agent verbs: focus an existing tab (focus_terminal_session)
        // or open a new one for a profile (workspace launcher's
        // OpenTerminalSession). The matching pager switch happens in
        // HavenNavHost.
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                when (command) {
                    is sh.haven.core.data.agent.AgentUiCommand.FocusTerminalSession -> {
                        val index = _tabs.value.indexOfFirst { it.sessionId == command.sessionId }
                        if (index >= 0) selectTab(index)
                    }
                    is sh.haven.core.data.agent.AgentUiCommand.OpenTerminalSession -> {
                        val profile = connectionRepository.getById(command.profileId)
                        if (profile == null) {
                            Log.w(TAG, "OpenTerminalSession: profile ${command.profileId} not found")
                        } else {
                            when {
                                profile.isLocal ->
                                    addLocalTabForProfile(profile.id, profile.label)
                                profile.isBtSerial ->
                                    addBtSerialTabForProfile(profile.id, profile.label)
                                profile.isBleSerial ->
                                    addBleSerialTabForProfile(profile.id, profile.label)
                                profile.isUsbSerial ->
                                    addUsbSerialTabForProfile(profile.id, profile.label)
                                profile.isSsh ->
                                    // Adds a tab on an already-live SSH session
                                    // (reusing its connection); toasts and
                                    // no-ops if none is up. The workspace
                                    // launcher now dials cold profiles itself
                                    // (ConnectProfile) before emitting this, so
                                    // it only reaches here once connected. A
                                    // sessionName reattaches the tab to that
                                    // tmux/zellij session, skipping the picker.
                                    addSshTabForProfile(profile.id, command.sessionName)
                                else -> {
                                    _newTabMessage.value =
                                        "${profile.label}: ${profile.connectionType} doesn't support new tabs from a workspace yet"
                                }
                            }
                        }
                    }
                    else -> { /* handled by other collectors */ }
                }
            }
        }
    }

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
                "SSH" -> {
                    // The emulator lives in SshTerminalEmulatorOwner across ViewModel
                    // lifecycles now (#290 issue #2) — do NOT detach the session or
                    // unregister the emulator (the owner disposes both on session
                    // teardown). Just repoint the owner's input/resize sinks off this
                    // dying VM, back at the session, so a remote app's query is still
                    // answered while no UI exists; the new VM re-adopts on recreation.
                    sshEmulatorOwner.resetSinks(tab.sessionId)
                }
                "RETICULUM" -> reticulumSessionManager.detachTerminalSession(tab.sessionId)
                "MOSH" -> moshSessionManager.detachTerminalSession(tab.sessionId)
                "ET" -> etSessionManager.detachTerminalSession(tab.sessionId)
                "BTSERIAL" -> btSerialSessionManager.detachTerminalSession(tab.sessionId)
                "BLESERIAL" -> bleSerialSessionManager.detachTerminalSession(tab.sessionId)
                "USBSERIAL" -> usbSerialSessionManager.detachTerminalSession(tab.sessionId)
                "LOCAL" -> {
                    localSessionManager.detachTerminalSession(tab.sessionId)
                    // Drop the now-stale emulator from the singleton registry so a
                    // recreated ViewModel reattaches (fresh emulator + scrollback
                    // replay + live rewire) instead of re-adopting this torn-down
                    // emulator (#272). Because the registry is @Singleton and every
                    // tab registers into it, the adoption path always found an entry
                    // and short-circuited the reattach path — leaving the proot
                    // terminal blank on return-from-background despite the shell
                    // staying alive. read_terminal_scrollback (the agent ring) is
                    // unaffected; only the grid snapshot is briefly unavailable
                    // until the UI rebuilds, which is correct (the old grid is stale).
                    terminalSessionRegistry.unregister(tab.sessionId)
                }
            }
        }
        trackedSessionIds.clear()
    }

    private fun createRecorderIfEnabled(sessionId: String): TerminalRecorder? {
        if (!verboseLoggingCached) return null
        val dir = java.io.File(appContext.filesDir, "terminal-recordings").apply { mkdirs() }
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(dir, "session-${ts}-${sessionId.take(8)}.bin")
        Log.d(TAG, "Recording terminal data to ${file.absolutePath}")
        val recorder = TerminalRecorder(file)
        activeRecorders.add(recorder)
        return recorder
    }

    /**
     * Pushed down by [TerminalScreen] every time `isSystemInDarkTheme()`
     * recomposes — the ViewModel can't read Compose state itself, but
     * needs the value so [effectiveGlobalScheme] resolves correctly when
     * auto-switch is enabled.
     */
    private val systemIsDark = MutableStateFlow(true)

    /** Called from the screen on every recomposition that observes the system theme. */
    fun setSystemIsDark(isDark: Boolean) {
        systemIsDark.value = isDark
    }

    /**
     * Live global terminal colour scheme — respects the auto-switch toggle:
     * when enabled, follows the system light/dark mode (via [systemIsDark])
     * by picking the light or dark pref; otherwise returns the manual
     * [UserPreferencesRepository.terminalColorScheme] pref.
     */
    val terminalColorScheme: StateFlow<UserPreferencesRepository.TerminalColorScheme> =
        combine(
            preferencesRepository.terminalColorScheme,
            preferencesRepository.terminalAutoSwitchScheme,
            preferencesRepository.terminalLightColorScheme,
            preferencesRepository.terminalDarkColorScheme,
            systemIsDark,
        ) { manual, autoSwitch, light, dark, isDark ->
            if (autoSwitch) (if (isDark) dark else light) else manual
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.TerminalColorScheme.HAVEN,
        )

    /**
     * Whether to push the active scheme's ANSI palette to the emulator
     * (#407). Off by default — see [UserPreferencesRepository.terminalApplySchemePalette].
     */
    val terminalApplySchemePalette: StateFlow<Boolean> =
        preferencesRepository.terminalApplySchemePalette
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Live global terminal background opacity (0.0–1.0). 1.0 = opaque. */
    val terminalBackgroundOpacity: StateFlow<Float> =
        preferencesRepository.terminalBackgroundOpacity
            .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    /**
     * Scrollback ring size for newly created emulators (#151). Read at
     * construction by [TerminalEmulatorFactory.create]; existing tabs keep
     * the size they were created with.
     */
    private val terminalScrollbackRows: StateFlow<Int> =
        preferencesRepository.terminalScrollbackRows
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                UserPreferencesRepository.DEFAULT_SCROLLBACK_ROWS,
            )

    /**
     * Resolve the per-tab colour scheme for a tab built off [profile].
     * Returns the profile's override (#144) when set, or null to mean
     * "use the live global scheme" — the screen resolves null at
     * render time so auto-switch and manual changes both propagate
     * without us having to mutate existing tabs.
     */
    private fun effectiveColorScheme(
        profile: sh.haven.core.data.db.entities.ConnectionProfile?,
    ): UserPreferencesRepository.TerminalColorScheme? {
        return profile?.terminalColorScheme?.let { name ->
            runCatching {
                UserPreferencesRepository.TerminalColorScheme.valueOf(name)
            }.getOrNull()
        }
    }

    /**
     * Per-tab background opacity override for a tab built off [profile].
     * Returns the profile's value when set, or null to follow the live
     * global opacity (resolved by the screen at render time).
     */
    private fun effectiveOpacity(
        profile: sh.haven.core.data.db.entities.ConnectionProfile?,
    ): Float? = profile?.terminalBackgroundOpacity

    /**
     * Pick the scheme used to seed a brand-new emulator's default fg/bg.
     * Override wins; otherwise the current effective global is used so
     * the very first frame matches the surrounding chrome. The screen
     * replaces this via [TerminalEmulator.setDefaultColors] on the next
     * recomposition anyway, so any drift after construction is benign.
     */
    private fun initialEmulatorScheme(
        override: UserPreferencesRepository.TerminalColorScheme?,
    ): UserPreferencesRepository.TerminalColorScheme = override ?: terminalColorScheme.value

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

    /**
     * Refresh the list of remote sessions for the active tab's connection.
     * Resets [_remoteSessionNames] in every early-return path — the long-
     * press tab menu reads this list to surface attach-existing-session
     * shortcuts, and stale state (left over from a previous tab whose
     * profile was tmux/zellij/screen) was getting shown on tabs whose
     * profile is sessionManager=NONE. Picking a stale name then ran
     * openRemoteSession against the NONE-resolved manager so no tmux
     * attach actually happened and the user landed on bare bash.
     */
    fun refreshRemoteSessions() {
        val activeTab = _tabs.value.getOrNull(_activeTabIndex.value)
        if (activeTab == null || activeTab.transportType != "SSH") {
            _remoteSessionNames.value = emptyList()
            return
        }
        val configPair = sessionManager.getConnectionConfigForProfile(activeTab.profileId)
        if (configPair == null) {
            _remoteSessionNames.value = emptyList()
            return
        }
        val (_, sshSessionMgr) = configPair
        val listCmd = sshSessionMgr.listCommand
        if (listCmd == null) {
            _remoteSessionNames.value = emptyList()
            return
        }
        val session = sessionManager.getSession(activeTab.sessionId)
        if (session == null) {
            _remoteSessionNames.value = emptyList()
            return
        }
        val client = session.client
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.execCommand(listCmd) }
                _remoteSessionNames.value = if (result.exitStatus == 0) {
                    SessionManager.parseSessionList(sshSessionMgr, result.stdout)
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                _remoteSessionNames.value = emptyList()
            }
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

    /**
     * Locked modifiers survive more than one keystroke (#522). A lock is the
     * second consecutive tap of the same modifier, so the three states cycle
     * off → one-shot → locked → off on successive taps.
     */
    private val _ctrlLocked = MutableStateFlow(false)
    val ctrlLocked: StateFlow<Boolean> = _ctrlLocked.asStateFlow()

    private val _altLocked = MutableStateFlow(false)
    val altLocked: StateFlow<Boolean> = _altLocked.asStateFlow()

    fun toggleCtrl() {
        when {
            _ctrlLocked.value -> { _ctrlLocked.value = false; _ctrlActive.value = false }
            _ctrlActive.value -> _ctrlLocked.value = true
            else -> _ctrlActive.value = true
        }
    }

    fun toggleAlt() {
        when {
            _altLocked.value -> { _altLocked.value = false; _altActive.value = false }
            _altActive.value -> _altLocked.value = true
            else -> _altActive.value = true
        }
    }

    /**
     * The active modifiers as libvterm's dispatchKey mask — bit 0 Shift, bit 1
     * Alt, bit 2 Ctrl (`Terminal.cpp`). The toolbar's own keys dispatch a key
     * code rather than bytes, so this is what carries a tapped Ctrl to them;
     * without it Ctrl+End left as a bare End. Shift is the toolbar's own state
     * and is not folded in here.
     */
    fun toolbarModifierMask(): Int =
        (if (_altActive.value) 2 else 0) or (if (_ctrlActive.value) 4 else 0)

    /**
     * Clear the one-shot sticky Ctrl/Alt after a keystroke has consumed them.
     * Called from the terminal's [org.connectbot.terminal.ModifierManager.clearTransients]
     * so a tapped modifier can't get stuck — e.g. in Standard keyboard mode a
     * Ctrl that the IME composed past would otherwise persist and turn the next
     * Enter into Ctrl+Enter (`^[[13;5u`). (#298)
     *
     * A locked modifier is not transient at all: it applies to every keystroke
     * until the user taps it off — the requester's follow-up on #522 settled
     * that this, not a keystroke budget, is what "locked" means. Keeping the
     * release policy in this one function also lets every consume site call it
     * safely: it is idempotent, so a keystroke that passes through both the
     * key-event path and the byte path can't spend anything twice.
     */
    fun clearStickyModifiers() {
        if (!_ctrlLocked.value) _ctrlActive.value = false
        if (!_altLocked.value) _altActive.value = false
    }

    /**
     * #524: while on, a vertical swipe sends ↑/↓ even at a plain shell prompt
     * (command history without arrow keys on the toolbar), instead of only on
     * the alternate screen. A latched mode, not a modifier — it survives
     * session end and app restart, because its point is to permanently free
     * the four arrow-key toolbar slots.
     */
    val swipeArrowsMode: StateFlow<Boolean> = preferencesRepository.swipeArrowsMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleSwipeArrows() {
        viewModelScope.launch {
            preferencesRepository.setSwipeArrowsMode(!swipeArrowsMode.value)
        }
    }

    /**
     * Drop all modifier state, locks included. Runs when the tab list
     * reconciles to empty: a lock is scoped to the sessions it was locked
     * for, so exiting the last one (the #522 follow-up's Ctrl-locked
     * Ctrl+D) must not hand the next connection a pre-locked key. Not
     * called on individual tab closes — the toolbar state is shared, and
     * a surviving tab may be mid-use of the lock.
     */
    private fun resetModifiers() {
        _ctrlLocked.value = false
        _altLocked.value = false
        _ctrlActive.value = false
        _altActive.value = false
    }

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

    // sendRedrawIfZellij (auto Ctrl+L on keyboard hide, from abc8b1b2b) was
    // REMOVED for #554: it injected 0x0C into whatever the pane was running on
    // every IME visible->hidden transition — including app backgrounding and
    // the #515 restore's resume flicker — echoing ^L into foreground commands
    // and force-clearing the screen. Device-verified 2026-08-17 that zellij
    // 0.44 repaints the full viewport on a bare SIGWINCH after an IME-dismiss
    // resize, so the nudge is obsolete; every timing/size heuristic tried to
    // scope it raced one way or the other. Users who want a manual nudge have
    // the toolbar's ^L macro preset.

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
     * Apply Ctrl/Alt modifiers to keyboard input, then release them through
     * [clearStickyModifiers] so a lock survives. This function clearing the
     * state directly was the #522 follow-up bug: it knew nothing about locks,
     * so a locked Ctrl worked for exactly one keypress and then sat there
     * blue and inert — the byte path spent it while the visual said held.
     * Ctrl+letter -> char AND 0x1F (e.g. Ctrl+C = 0x03).
     * Alt+char -> ESC prefix.
     */
    private fun applyModifiers(data: ByteArray): ByteArray {
        val ctrl = _ctrlActive.value
        val alt = _altActive.value
        if (!ctrl && !alt) return data

        clearStickyModifiers()

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
            btSerialSessionManager.sessions.collect { syncSessions() }
        }
        viewModelScope.launch {
            bleSerialSessionManager.sessions.collect { syncSessions() }
        }
        viewModelScope.launch {
            usbSerialSessionManager.sessions.collect { syncSessions() }
        }
        viewModelScope.launch {
            localSessionManager.sessions.collect { syncSessions() }
        }
    }

    /**
     * Sync tabs with session manager state.
     * Creates emulator + terminal session for new CONNECTED sessions.
     */
    suspend fun syncSessions() {
        val sshSessions = sessionManager.sessions.value
        val rnsSessions = reticulumSessionManager.sessions.value
        val moshSessions = moshSessionManager.sessions.value
        val etSessions = etSessionManager.sessions.value
        val btSerialSessions = btSerialSessionManager.sessions.value
        val bleSerialSessions = bleSerialSessionManager.sessions.value
        val usbSerialSessions = usbSerialSessionManager.sessions.value
        val localSessions = localSessionManager.sessions.value

        // Resolve the display config (color scheme, alt-screen, color tag) for
        // every active session's profile in one off-main batch. The per-branch
        // tab setup below reads from this map instead of a per-session blocking
        // Room+Keystore lookup — syncSessions runs on the main thread from the
        // session collectors, where runBlocking was an ANR source. (#208 #14)
        val profilesById = withContext(Dispatchers.IO) {
            buildSet {
                sshSessions.values.forEach { add(it.profileId) }
                rnsSessions.values.forEach { add(it.profileId) }
                moshSessions.values.forEach { add(it.profileId) }
                etSessions.values.forEach { add(it.profileId) }
                btSerialSessions.values.forEach { add(it.profileId) }
                bleSerialSessions.values.forEach { add(it.profileId) }
                usbSerialSessions.values.forEach { add(it.profileId) }
                localSessions.values.forEach { add(it.profileId) }
            }.associateWith { connectionRepository.getById(it) }
        }

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

        // Find Bluetooth-serial sessions that are connected
        val activeBtIds = btSerialSessions.values
            .filter {
                it.status == sh.haven.core.btserial.BtSerialSessionManager.SessionState.Status.CONNECTED
            }
            .map { it.sessionId }
            .toSet()

        val activeBleIds = bleSerialSessions.values
            .filter {
                it.status == sh.haven.core.bleserial.BleSerialSessionManager.SessionState.Status.CONNECTED
            }
            .map { it.sessionId }
            .toSet()

        // Find USB-serial sessions that are connected
        val activeUsbIds = usbSerialSessions.values
            .filter {
                it.status == sh.haven.core.usbserial.UsbSerialSessionManager.SessionState.Status.CONNECTED
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

        val allActiveIds = activeSshIds + activeRnsIds + activeMoshIds + activeEtIds + activeBtIds + activeBleIds + activeUsbIds + activeLocalIds

        val currentTabs = _tabs.value.toMutableList()

        // Remove tabs for disconnected sessions
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
                "BTSERIAL" -> tab.sessionId !in activeBtIds ||
                    btSerialSessions[tab.sessionId]?.session == null
                "BLESERIAL" -> tab.sessionId !in activeBleIds ||
                    bleSerialSessions[tab.sessionId]?.session == null
                "USBSERIAL" -> tab.sessionId !in activeUsbIds ||
                    usbSerialSessions[tab.sessionId]?.session == null
                else -> true
            }
        }
        if (removed) {
            trackedSessionIds.retainAll(currentTabs.map { it.sessionId }.toSet())
        }

        // Adopt SSH emulators created at connect time by SshTerminalEmulatorOwner
        // (#290 issue #2). The emulator already exists and has been parsing output
        // — answering tmux's attach-time probes — since connect; here we wire it to
        // a tab and route user input/resize through the ViewModel. After Activity
        // recreation this re-adopts the SAME live emulator, so the screen comes back
        // intact with no scrollback replay (the old #272-SSH replay path is gone).
        for (sessionId in activeSshIds) {
            if (sessionId in trackedSessionIds) continue
            val b = sshEmulatorOwner.bundleFor(sessionId) ?: continue
            val session = sshSessions[sessionId] ?: continue
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs, sessionName = session.chosenSessionName)
            val sshProfile = profilesById[session.profileId]

            // User input goes through keyboard-modifier state + the IME-dedupe
            // coalescer; the owner's default sink (probe responses → session) is
            // swapped to this while the tab is mounted.
            val coalescer = InputCoalescer { data -> b.session?.sendToSsh(data) }
            sshEmulatorOwner.setInputSink(sessionId) { data -> coalescer.send(applyModifiers(data)) }
            sshEmulatorOwner.setResizeSink(sessionId) { dims ->
                // Only the active tab's Terminal composable fires onResize; resize
                // ALL tabs so inactive ones don't keep a stale PTY size.
                for (tab in _tabs.value) {
                    tab.resize(dims.columns, dims.rows)
                }
                b.session?.resize(dims.columns, dims.rows)
            }

            val reconnectingFlow = sessionManager.sessions
                .map { it[sessionId]?.status == SessionState.Status.RECONNECTING }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

            currentTabs.add(
                TerminalTab(
                    sessionId = sessionId,
                    profileId = session.profileId,
                    colorTag = sshProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "SSH",
                    emulator = b.emulator,
                    mouseMode = b.mouseTracker.mouseMode,
                    activeMouseMode = b.mouseTracker.activeMouseMode,
                    bracketPasteMode = b.mouseTracker.bracketPasteMode,
                    altScreen = b.mouseTracker.altScreen,
                    cursorKeyAppMode = b.mouseTracker.cursorKeyAppMode,
                    oscHandler = b.oscHandler,
                    feedOutput = b.feedOutput,
                    cwd = b.cwdFlow,
                    hyperlinkUri = b.hyperlinkFlow,
                    isReconnecting = reconnectingFlow,
                    stallSeconds = NEVER_STALLS,
                    sendInput = { data -> b.session?.sendToSsh(data) },
                    resize = { cols, rows -> b.session?.resize(cols, rows) },
                    close = { b.session?.close() },
                    colorScheme = effectiveColorScheme(sshProfile),
                    backgroundOpacity = effectiveOpacity(sshProfile),
                )
            )
            trackedSessionIds.add(sessionId)
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
            val rnsFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                synchronized(rnsOscHandler) {
                    rnsOscHandler.process(data, offset, length)
                    rnsMouseTracker.process(rnsOscHandler.outputBuf, 0, rnsOscHandler.outputLen)
                    val len = rnsOscHandler.outputLen
                    if (len > 0) {
                        rnsWriteBuffer.append(rnsOscHandler.outputBuf, 0, len)
                    }
                }
            }
            val rnsSession = reticulumSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    rnsFeedOutput(data, offset, length)
                },
            ) ?: continue

            val rnsCoalescer = InputCoalescer { data -> rnsSession.sendInput(data) }
            val rnsProfile = profilesById[session.profileId]
            val rnsScheme = effectiveColorScheme(rnsProfile)
            val rnsInitialScheme = initialEmulatorScheme(rnsScheme)
            emulator = TerminalEmulatorFactory.create(
                autoDetectUrls = true,
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(rnsInitialScheme.foreground),
                defaultBackground = Color(rnsInitialScheme.background),
                enableAltScreen = rnsProfile?.disableAltScreen != true && rnsProfile?.sessionManager != "screen",
                onKeyboardInput = { data -> rnsCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "RNS onResize: ${dims.columns}x${dims.rows}")
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    rnsSession.resize(dims.columns, dims.rows)
                },
                maxScrollbackLines = terminalScrollbackRows.value,
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
                    altScreen = rnsMouseTracker.altScreen,
                    cursorKeyAppMode = rnsMouseTracker.cursorKeyAppMode,
                    oscHandler = rnsOscHandler,
                    feedOutput = rnsFeedOutput,
                    cwd = rnsCwdFlow,
                    hyperlinkUri = rnsHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    stallSeconds = NEVER_STALLS,
                    sendInput = { data -> rnsSession.sendInput(data) },
                    resize = { cols, rows -> rnsSession.resize(cols, rows) },
                    close = { rnsSession.close() },
                    colorScheme = rnsScheme,
                    backgroundOpacity = effectiveOpacity(rnsProfile),
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new Bluetooth-serial sessions (#406). A raw serial link
        // has no window-size channel, so resize is a no-op (the console's width is
        // fixed on the far side).
        for (sessionId in activeBtIds) {
            if (sessionId in trackedSessionIds) continue
            if (!btSerialSessionManager.isReadyForTerminal(sessionId)) continue

            val session = btSerialSessions[sessionId] ?: continue
            val btProfile = profilesById[session.profileId]
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

            lateinit var emulator: TerminalEmulator
            val btWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val btMouseTracker = MouseModeTracker()
            val btOscHandler = OscHandler()
            val btCwdFlow = MutableStateFlow<String?>(null)
            val btHyperlinkFlow = MutableStateFlow<String?>(null)
            btOscHandler.onCwdChanged = { btCwdFlow.value = it }
            btOscHandler.onHyperlink = { uri -> btHyperlinkFlow.value = uri }
            val btFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                synchronized(btOscHandler) {
                    btOscHandler.process(data, offset, length)
                    btMouseTracker.process(btOscHandler.outputBuf, 0, btOscHandler.outputLen)
                    val len = btOscHandler.outputLen
                    if (len > 0) {
                        btWriteBuffer.append(btOscHandler.outputBuf, 0, len)
                    }
                }
            }
            val btSession = btSerialSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length -> btFeedOutput(data, offset, length) },
            ) ?: continue

            val btCoalescer = InputCoalescer { data -> btSession.sendInput(data) }
            val btScheme = effectiveColorScheme(btProfile)
            val btInitialScheme = initialEmulatorScheme(btScheme)
            emulator = TerminalEmulatorFactory.create(
                autoDetectUrls = true,
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(btInitialScheme.foreground),
                defaultBackground = Color(btInitialScheme.background),
                enableAltScreen = btProfile?.disableAltScreen != true,
                onKeyboardInput = { data -> btCoalescer.send(applyModifiers(data)) },
                onResize = { /* raw serial: no resize channel */ },
                maxScrollbackLines = terminalScrollbackRows.value,
            )

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    colorTag = btProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "BTSERIAL",
                    emulator = emulator,
                    mouseMode = btMouseTracker.mouseMode,
                    activeMouseMode = btMouseTracker.activeMouseMode,
                    bracketPasteMode = btMouseTracker.bracketPasteMode,
                    altScreen = btMouseTracker.altScreen,
                    cursorKeyAppMode = btMouseTracker.cursorKeyAppMode,
                    oscHandler = btOscHandler,
                    feedOutput = btFeedOutput,
                    cwd = btCwdFlow,
                    hyperlinkUri = btHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    stallSeconds = NEVER_STALLS,
                    sendInput = { data -> btSession.sendInput(data) },
                    resize = { _, _ -> /* raw serial: no resize channel */ },
                    close = { btSession.close() },
                    colorScheme = btScheme,
                    backgroundOpacity = effectiveOpacity(btProfile),
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new BLE-serial sessions (GATT). Same transport-agnostic
        // terminal setup as BTSERIAL; a BLE UART link has no resize channel.
        for (sessionId in activeBleIds) {
            if (sessionId in trackedSessionIds) continue
            if (!bleSerialSessionManager.isReadyForTerminal(sessionId)) continue

            val session = bleSerialSessions[sessionId] ?: continue
            val bleProfile = profilesById[session.profileId]
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

            lateinit var emulator: TerminalEmulator
            val bleWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val bleMouseTracker = MouseModeTracker()
            val bleOscHandler = OscHandler()
            val bleCwdFlow = MutableStateFlow<String?>(null)
            val bleHyperlinkFlow = MutableStateFlow<String?>(null)
            bleOscHandler.onCwdChanged = { bleCwdFlow.value = it }
            bleOscHandler.onHyperlink = { uri -> bleHyperlinkFlow.value = uri }
            val bleFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                synchronized(bleOscHandler) {
                    bleOscHandler.process(data, offset, length)
                    bleMouseTracker.process(bleOscHandler.outputBuf, 0, bleOscHandler.outputLen)
                    val len = bleOscHandler.outputLen
                    if (len > 0) {
                        bleWriteBuffer.append(bleOscHandler.outputBuf, 0, len)
                    }
                }
            }
            val bleSession = bleSerialSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length -> bleFeedOutput(data, offset, length) },
            ) ?: continue

            val bleCoalescer = InputCoalescer { data -> bleSession.sendInput(data) }
            val bleScheme = effectiveColorScheme(bleProfile)
            val bleInitialScheme = initialEmulatorScheme(bleScheme)
            emulator = TerminalEmulatorFactory.create(
                autoDetectUrls = true,
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(bleInitialScheme.foreground),
                defaultBackground = Color(bleInitialScheme.background),
                enableAltScreen = bleProfile?.disableAltScreen != true,
                onKeyboardInput = { data -> bleCoalescer.send(applyModifiers(data)) },
                onResize = { /* raw serial: no resize channel */ },
                maxScrollbackLines = terminalScrollbackRows.value,
            )

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    colorTag = bleProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "BLESERIAL",
                    emulator = emulator,
                    mouseMode = bleMouseTracker.mouseMode,
                    activeMouseMode = bleMouseTracker.activeMouseMode,
                    bracketPasteMode = bleMouseTracker.bracketPasteMode,
                    altScreen = bleMouseTracker.altScreen,
                    cursorKeyAppMode = bleMouseTracker.cursorKeyAppMode,
                    oscHandler = bleOscHandler,
                    feedOutput = bleFeedOutput,
                    cwd = bleCwdFlow,
                    hyperlinkUri = bleHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    stallSeconds = NEVER_STALLS,
                    sendInput = { data -> bleSession.sendInput(data) },
                    resize = { _, _ -> /* raw serial: no resize channel */ },
                    close = { bleSession.close() },
                    colorScheme = bleScheme,
                    backgroundOpacity = effectiveOpacity(bleProfile),
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new USB-serial sessions (#408). Same transport-agnostic
        // terminal setup as BTSERIAL; raw serial has no resize channel.
        for (sessionId in activeUsbIds) {
            if (sessionId in trackedSessionIds) continue
            if (!usbSerialSessionManager.isReadyForTerminal(sessionId)) continue

            val session = usbSerialSessions[sessionId] ?: continue
            val usbProfile = profilesById[session.profileId]
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

            lateinit var emulator: TerminalEmulator
            val usbWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val usbMouseTracker = MouseModeTracker()
            val usbOscHandler = OscHandler()
            val usbCwdFlow = MutableStateFlow<String?>(null)
            val usbHyperlinkFlow = MutableStateFlow<String?>(null)
            usbOscHandler.onCwdChanged = { usbCwdFlow.value = it }
            usbOscHandler.onHyperlink = { uri -> usbHyperlinkFlow.value = uri }
            val usbFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                synchronized(usbOscHandler) {
                    usbOscHandler.process(data, offset, length)
                    usbMouseTracker.process(usbOscHandler.outputBuf, 0, usbOscHandler.outputLen)
                    val len = usbOscHandler.outputLen
                    if (len > 0) {
                        usbWriteBuffer.append(usbOscHandler.outputBuf, 0, len)
                    }
                }
            }
            val usbSession = usbSerialSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length -> usbFeedOutput(data, offset, length) },
            ) ?: continue

            val usbCoalescer = InputCoalescer { data -> usbSession.sendInput(data) }
            val usbScheme = effectiveColorScheme(usbProfile)
            val usbInitialScheme = initialEmulatorScheme(usbScheme)
            emulator = TerminalEmulatorFactory.create(
                autoDetectUrls = true,
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(usbInitialScheme.foreground),
                defaultBackground = Color(usbInitialScheme.background),
                enableAltScreen = usbProfile?.disableAltScreen != true,
                onKeyboardInput = { data -> usbCoalescer.send(applyModifiers(data)) },
                onResize = { /* raw serial: no resize channel */ },
                maxScrollbackLines = terminalScrollbackRows.value,
            )

            currentTabs.add(
                TerminalTab(
                    sessionId = session.sessionId,
                    profileId = session.profileId,
                    colorTag = usbProfile?.colorTag ?: 0,
                    label = tabLabel,
                    transportType = "USBSERIAL",
                    emulator = emulator,
                    mouseMode = usbMouseTracker.mouseMode,
                    activeMouseMode = usbMouseTracker.activeMouseMode,
                    bracketPasteMode = usbMouseTracker.bracketPasteMode,
                    altScreen = usbMouseTracker.altScreen,
                    cursorKeyAppMode = usbMouseTracker.cursorKeyAppMode,
                    oscHandler = usbOscHandler,
                    feedOutput = usbFeedOutput,
                    cwd = usbCwdFlow,
                    hyperlinkUri = usbHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    stallSeconds = NEVER_STALLS,
                    sendInput = { data -> usbSession.sendInput(data) },
                    resize = { _, _ -> /* raw serial: no resize channel */ },
                    close = { usbSession.close() },
                    colorScheme = usbScheme,
                    backgroundOpacity = effectiveOpacity(usbProfile),
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new Mosh sessions
        for (sessionId in activeMoshIds) {
            if (sessionId in trackedSessionIds) continue
            if (!moshSessionManager.isReadyForTerminal(sessionId)) continue

            val session = moshSessions[sessionId] ?: continue
            val moshProfile = profilesById[session.profileId]
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs, sessionName = moshProfile?.lastSessionName)

            lateinit var emulator: TerminalEmulator
            val moshWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val moshMouseTracker = MouseModeTracker()
            val moshOscHandler = OscHandler()
            val moshCwdFlow = MutableStateFlow<String?>(null)
            val moshHyperlinkFlow = MutableStateFlow<String?>(null)
            moshOscHandler.onCwdChanged = { moshCwdFlow.value = it }
            moshOscHandler.onHyperlink = { uri -> moshHyperlinkFlow.value = uri }
            val moshFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                synchronized(moshOscHandler) {
                    moshOscHandler.process(data, offset, length)
                    moshMouseTracker.process(moshOscHandler.outputBuf, 0, moshOscHandler.outputLen)
                    val len = moshOscHandler.outputLen
                    if (len > 0) {
                        moshWriteBuffer.append(moshOscHandler.outputBuf, 0, len)
                    }
                }
            }

            // Defer initial command until shell prompt detected
            val moshInitialCmd = session.initialCommand
            val moshPendingSent = java.util.concurrent.atomic.AtomicBoolean(false)
            // Holder so lambda can reference session before it's assigned
            val moshSessionRef = arrayOfNulls<sh.haven.core.mosh.MoshSession>(1)

            val moshSession = moshSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    moshFeedOutput(data, offset, length)
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
            val moshScheme = effectiveColorScheme(moshProfile)
            val moshInitialScheme = initialEmulatorScheme(moshScheme)
            emulator = TerminalEmulatorFactory.create(
                autoDetectUrls = true,
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(moshInitialScheme.foreground),
                enableAltScreen = moshProfile?.disableAltScreen != true && moshProfile?.sessionManager != "screen",
                defaultBackground = Color(moshInitialScheme.background),
                onKeyboardInput = { data -> moshCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "MOSH onResize: ${dims.columns}x${dims.rows}")
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    moshSession.resize(dims.columns, dims.rows)
                },
                maxScrollbackLines = terminalScrollbackRows.value,
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
                    altScreen = moshMouseTracker.altScreen,
                    cursorKeyAppMode = moshMouseTracker.cursorKeyAppMode,
                    oscHandler = moshOscHandler,
                    feedOutput = moshFeedOutput,
                    cwd = moshCwdFlow,
                    hyperlinkUri = moshHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    stallSeconds = moshSession.stallSeconds,
                    sendInput = { data -> moshSession.sendInput(data) },
                    resize = { cols, rows -> moshSession.resize(cols, rows) },
                    close = { moshSession.close() },
                    colorScheme = moshScheme,
                    backgroundOpacity = effectiveOpacity(moshProfile),
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new ET sessions
        for (sessionId in activeEtIds) {
            if (sessionId in trackedSessionIds) continue
            if (!etSessionManager.isReadyForTerminal(sessionId)) continue

            val session = etSessions[sessionId] ?: continue
            val etProfile = profilesById[session.profileId]
            val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs, sessionName = etProfile?.lastSessionName)

            lateinit var emulator: TerminalEmulator
            val etWriteBuffer = EmulatorWriteBuffer({ emulator }, createRecorderIfEnabled(sessionId))
            val etMouseTracker = MouseModeTracker()
            val etOscHandler = OscHandler()
            val etCwdFlow = MutableStateFlow<String?>(null)
            val etHyperlinkFlow = MutableStateFlow<String?>(null)
            etOscHandler.onCwdChanged = { etCwdFlow.value = it }
            etOscHandler.onHyperlink = { uri -> etHyperlinkFlow.value = uri }
            val etFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                synchronized(etOscHandler) {
                    etOscHandler.process(data, offset, length)
                    etMouseTracker.process(etOscHandler.outputBuf, 0, etOscHandler.outputLen)
                    val len = etOscHandler.outputLen
                    if (len > 0) {
                        etWriteBuffer.append(etOscHandler.outputBuf, 0, len)
                    }
                }
            }

            // Defer initial command until shell prompt detected
            val etInitialCmd = session.initialCommand
            val etPendingSent = java.util.concurrent.atomic.AtomicBoolean(false)
            val etSessionRef = arrayOfNulls<sh.haven.core.et.EtSession>(1)

            val etSession = etSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    etFeedOutput(data, offset, length)
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
            val etScheme = effectiveColorScheme(etProfile)
            val etInitialScheme = initialEmulatorScheme(etScheme)
            emulator = TerminalEmulatorFactory.create(
                autoDetectUrls = true,
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(etInitialScheme.foreground),
                defaultBackground = Color(etInitialScheme.background),
                enableAltScreen = etProfile?.disableAltScreen != true && etProfile?.sessionManager != "screen",
                onKeyboardInput = { data -> etCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "ET onResize: ${dims.columns}x${dims.rows}")
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    etSession.resize(dims.columns, dims.rows)
                },
                maxScrollbackLines = terminalScrollbackRows.value,
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
                    altScreen = etMouseTracker.altScreen,
                    cursorKeyAppMode = etMouseTracker.cursorKeyAppMode,
                    oscHandler = etOscHandler,
                    feedOutput = etFeedOutput,
                    cwd = etCwdFlow,
                    hyperlinkUri = etHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    stallSeconds = NEVER_STALLS,
                    sendInput = { data -> etSession.sendInput(data) },
                    resize = { cols, rows -> etSession.resize(cols, rows) },
                    close = { etSession.close() },
                    colorScheme = etScheme,
                    backgroundOpacity = effectiveOpacity(etProfile),
                )
            )
            trackedSessionIds.add(session.sessionId)
        }

        // Create tabs for new Local sessions
        for (sessionId in activeLocalIds) {
            if (sessionId in trackedSessionIds) continue

            // Adoption path: the MCP agent's `open_local_shell` may already
            // have started a headless LocalSession and registered its
            // TerminalEmulator with [terminalSessionRegistry]. In that case
            // [isReadyForTerminal] is false (a PTY is attached) and we
            // can't legally re-create the session. Reuse the existing
            // emulator + LocalSession so the UI tab presents the same byte
            // stream the agent sees, and so this tab's HavenTerminal mounts
            // the SelectionController / ScrollController the agent transport
            // needs for `start_selection` / `drag_selection_to` to operate.
            //
            // OSC 7 / OSC 8 tracking is not retroactively wired — the
            // LocalSession's onDataReceived callback was set when the agent
            // started it headlessly and can't be teed into additional
            // handlers here. Mouse / bracketed-paste modes ARE live: the
            // agent's own MouseModeTracker sits on the PTY tee (#336) and
            // its flows are reused below. libvterm's native OSC 133 dispatch
            // still works because that runs inside the adopted emulator.
            val existingHeadless = localSessionManager.getActiveSession(sessionId)
            val agentRegistryEntry = if (existingHeadless != null) terminalSessionRegistry.get(sessionId) else null
            if (existingHeadless != null && agentRegistryEntry != null) {
                val session = localSessions[sessionId] ?: continue
                val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)
                val localProfile = profilesById[session.profileId]
                val localScheme = effectiveColorScheme(localProfile)
                // The adopted headless session's real onDataReceived belongs
                // to the agent transport and can't be teed here, so OSC /
                // mouse tracking aren't wired to the live stream. These
                // handlers exist only so the agent's feed_terminal_output
                // test tool still has a working pipeline on this tab.
                val adoptedOscHandler = OscHandler()
                val adoptedWriteBuffer = EmulatorWriteBuffer({ agentRegistryEntry.emulator })
                val adoptedFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                    synchronized(adoptedOscHandler) {
                        adoptedOscHandler.process(data, offset, length)
                        val len = adoptedOscHandler.outputLen
                        if (len > 0) {
                            adoptedWriteBuffer.append(adoptedOscHandler.outputBuf, 0, len)
                        }
                    }
                }
                currentTabs.add(
                    TerminalTab(
                        sessionId = session.sessionId,
                        profileId = session.profileId,
                        colorTag = localProfile?.colorTag ?: 0,
                        label = tabLabel,
                        transportType = "LOCAL",
                        emulator = agentRegistryEntry.emulator,
                        // The agent's headless shell runs its own MouseModeTracker
                        // on the PTY tee (#336) — reuse those flows so the tab's
                        // paste-wrapping / mouse routing track the live stream.
                        // Dead stubs only when the entry predates mode tracking.
                        mouseMode = agentRegistryEntry.mouseMode ?: MutableStateFlow(false),
                        activeMouseMode = agentRegistryEntry.activeMouseMode ?: MutableStateFlow<Int?>(null),
                        bracketPasteMode = agentRegistryEntry.bracketPasteMode ?: MutableStateFlow(false),
                        altScreen = MutableStateFlow(false),
                        cursorKeyAppMode = MutableStateFlow(false),
                        oscHandler = adoptedOscHandler,
                        feedOutput = adoptedFeedOutput,
                        cwd = MutableStateFlow(null),
                        hyperlinkUri = MutableStateFlow(null),
                        isReconnecting = MutableStateFlow(false),
                        stallSeconds = NEVER_STALLS,
                        sendInput = { data -> existingHeadless.sendInput(data) },
                        resize = { cols, rows -> existingHeadless.resize(cols, rows) },
                        close = { existingHeadless.close() },
                        colorScheme = localScheme,
                        backgroundOpacity = effectiveOpacity(localProfile),
                    )
                )
                trackedSessionIds.add(sessionId)
                continue
            }

            // UI reattach: the proot PTY survived a ViewModel teardown (Activity
            // destroyed while the process stayed alive). The shell is still
            // running but its old emulator died with the previous ViewModel.
            // Build a fresh emulator, replay the buffered output so the screen +
            // scrollback are restored, and rewire the live stream to it — instead
            // of killing the shell and starting a blank one (#272). Detected by a
            // live LocalSession with no agent-registry entry (handled above) and
            // isReadyForTerminal == false (a PTY is still attached).
            if (localSessionManager.getActiveSession(sessionId) != null &&
                !localSessionManager.isReadyForTerminal(sessionId)
            ) {
                val session = localSessions[sessionId] ?: continue
                val tabLabel = generateTabLabel(session.label, session.profileId, currentTabs)

                lateinit var reEmulator: TerminalEmulator
                val reWriteBuffer = EmulatorWriteBuffer({ reEmulator }, createRecorderIfEnabled(sessionId))
                val reMouseTracker = MouseModeTracker()
                val reOscHandler = OscHandler()
                val reCwdFlow = MutableStateFlow<String?>(null)
                val reHyperlinkFlow = MutableStateFlow<String?>(null)
                reOscHandler.onCwdChanged = { reCwdFlow.value = it }
                reOscHandler.onHyperlink = { uri -> reHyperlinkFlow.value = uri }
                val reFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                    synchronized(reOscHandler) {
                        reOscHandler.process(data, offset, length)
                        reMouseTracker.process(reOscHandler.outputBuf, 0, reOscHandler.outputLen)
                        val len = reOscHandler.outputLen
                        if (len > 0) reWriteBuffer.append(reOscHandler.outputBuf, 0, len)
                    }
                }
                val localProfile = profilesById[session.profileId]
                val localScheme = effectiveColorScheme(localProfile)
                val reInitialScheme = initialEmulatorScheme(localScheme)
                val reCoalescer = InputCoalescer { data -> localSessionManager.getActiveSession(sessionId)?.sendInput(data) }
                reEmulator = TerminalEmulatorFactory.create(
                    autoDetectUrls = true,
                    initialRows = 24,
                    initialCols = 80,
                    defaultForeground = Color(reInitialScheme.foreground),
                    defaultBackground = Color(reInitialScheme.background),
                    enableAltScreen = localProfile?.disableAltScreen != true && localProfile?.sessionManager != "screen",
                    onKeyboardInput = { data -> reCoalescer.send(applyModifiers(data)) },
                    onResize = { dims ->
                        for (tab in _tabs.value) tab.resize(dims.columns, dims.rows)
                        localSessionManager.getActiveSession(sessionId)?.resize(dims.columns, dims.rows)
                    },
                    maxScrollbackLines = terminalScrollbackRows.value,
                )
                // Replay buffered output into the fresh emulator BEFORE wiring the
                // live stream, so the restore and new output don't interleave.
                localSessionManager.snapshotScrollback(sessionId)?.let { buffered ->
                    reFeedOutput(buffered, 0, buffered.size)
                }
                val reattached = localSessionManager.reattachTerminalSession(sessionId) { data, offset, length ->
                    reFeedOutput(data, offset, length)
                } ?: continue
                currentTabs.add(
                    TerminalTab(
                        sessionId = session.sessionId,
                        profileId = session.profileId,
                        colorTag = localProfile?.colorTag ?: 0,
                        label = tabLabel,
                        transportType = "LOCAL",
                        emulator = reEmulator,
                        mouseMode = reMouseTracker.mouseMode,
                        activeMouseMode = reMouseTracker.activeMouseMode,
                        bracketPasteMode = reMouseTracker.bracketPasteMode,
                        altScreen = reMouseTracker.altScreen,
                        cursorKeyAppMode = reMouseTracker.cursorKeyAppMode,
                        oscHandler = reOscHandler,
                        feedOutput = reFeedOutput,
                        cwd = reCwdFlow,
                        hyperlinkUri = reHyperlinkFlow,
                        isReconnecting = MutableStateFlow(false),
                        stallSeconds = NEVER_STALLS,
                        sendInput = { data -> reattached.sendInput(data) },
                        resize = { cols, rows -> reattached.resize(cols, rows) },
                        close = { reattached.close() },
                        colorScheme = localScheme,
                        backgroundOpacity = effectiveOpacity(localProfile),
                    )
                )
                Log.d(TAG, "Reattached to existing local session $sessionId")
                trackedSessionIds.add(session.sessionId)
                continue
            }

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
            val localFeedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
                synchronized(localOscHandler) {
                    localOscHandler.process(data, offset, length)
                    localMouseTracker.process(localOscHandler.outputBuf, 0, localOscHandler.outputLen)
                    val len = localOscHandler.outputLen
                    if (len > 0) {
                        localWriteBuffer.append(localOscHandler.outputBuf, 0, len)
                    }
                }
            }
            val localSession = localSessionManager.createTerminalSession(
                sessionId = sessionId,
                onDataReceived = { data, offset, length ->
                    localFeedOutput(data, offset, length)
                },
                plain = plainSessionIds.remove(sessionId),
            ) ?: continue

            val localCoalescer = InputCoalescer { data -> localSession.sendInput(data) }
            val localProfile = profilesById[session.profileId]
            val localScheme = effectiveColorScheme(localProfile)
            val localInitialScheme = initialEmulatorScheme(localScheme)
            emulator = TerminalEmulatorFactory.create(
                autoDetectUrls = true,
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color(localInitialScheme.foreground),
                defaultBackground = Color(localInitialScheme.background),
                enableAltScreen = localProfile?.disableAltScreen != true && localProfile?.sessionManager != "screen",
                onKeyboardInput = { data -> localCoalescer.send(applyModifiers(data)) },
                onResize = { dims ->
                    Log.d(TAG, "LOCAL onResize: ${dims.columns}x${dims.rows}")
                    for (tab in _tabs.value) {
                        tab.resize(dims.columns, dims.rows)
                    }
                    localSession.resize(dims.columns, dims.rows)
                },
                maxScrollbackLines = terminalScrollbackRows.value,
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
                    altScreen = localMouseTracker.altScreen,
                    cursorKeyAppMode = localMouseTracker.cursorKeyAppMode,
                    oscHandler = localOscHandler,
                    feedOutput = localFeedOutput,
                    cwd = localCwdFlow,
                    hyperlinkUri = localHyperlinkFlow,
                    isReconnecting = MutableStateFlow(false),
                    stallSeconds = NEVER_STALLS,
                    sendInput = { data -> localSession.sendInput(data) },
                    resize = { cols, rows -> localSession.resize(cols, rows) },
                    close = { localSession.close() },
                    colorScheme = localScheme,
                    backgroundOpacity = effectiveOpacity(localProfile),
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

        // Tabs that just disappeared: their session is gone, so nothing will
        // write to their emulator again and the native terminal can go now
        // rather than whenever the collector next runs (#509).
        //
        // SSH is excluded deliberately — its emulator outlives this ViewModel
        // in SshTerminalEmulatorOwner (#290), which closes it in dispose(). A
        // tab vanishing here does not mean that session is finished.
        val droppedTabs = _tabs.value.filter { old ->
            old.transportType != "SSH" && currentTabs.none { it.sessionId == old.sessionId }
        }

        _tabs.value = currentTabs

        if (currentTabs.isEmpty()) resetModifiers()

        droppedTabs.forEach { tab ->
            runCatching { tab.emulator.close() }
                .onFailure { Log.w(TAG, "Closing emulator for ${tab.sessionId} failed", it) }
        }

        // Mirror tab → registry so the MCP agent can find each tab's
        // emulator by sessionId. Selection / scroll controllers come in
        // separately from the Compose layer's onXxxControllerAvailable
        // callbacks (see TerminalScreen.kt).
        val liveIds = currentTabs.map { it.sessionId }.toSet()
        for (tab in currentTabs) {
            val existing = terminalSessionRegistry.get(tab.sessionId)
            when {
                existing == null -> {
                    terminalSessionRegistry.register(tab.sessionId, tab.emulator)
                    terminalSessionRegistry.setAgentHandles(
                        tab.sessionId,
                        tab.mouseMode,
                        tab.activeMouseMode,
                        tab.bracketPasteMode,
                        tab.oscHandler,
                        tab.feedOutput,
                    )
                }
                existing.emulator !== tab.emulator -> {
                    // The entry holds an emulator that is NOT the one this tab
                    // renders — open_local_shell claimed the registry while this
                    // tab was being built (fresh-app-start race, #378). The tab's
                    // emulator is on screen, resized, and fed by its own pipeline;
                    // repoint the agent handles at it so feed_terminal_output /
                    // read_terminal_snapshot see what the user sees. Also drop the
                    // now-orphaned agent tee so PTY output stops double-feeding a
                    // headless emulator nothing reads.
                    terminalSessionRegistry.adoptTabHandles(
                        tab.sessionId,
                        tab.emulator,
                        tab.mouseMode,
                        tab.activeMouseMode,
                        tab.bracketPasteMode,
                        tab.oscHandler,
                        tab.feedOutput,
                    )
                    if (tab.transportType == "LOCAL") {
                        localSessionManager.clearAgentTee(tab.sessionId)
                    }
                }
                existing.oscHandler == null -> {
                    // Agent-headless entry adopted by a UI tab sharing the SAME
                    // emulator: attach the tab's OSC handler but keep the entry's
                    // feedOutput and mode flows — the agent's MouseModeTracker
                    // sits on the PTY tee (#336) and its feed writes to the shared
                    // emulator; a tab stub would leave bracketPasteMode dead.
                    terminalSessionRegistry.setAgentHandles(
                        tab.sessionId,
                        existing.mouseMode ?: tab.mouseMode,
                        existing.activeMouseMode ?: tab.activeMouseMode,
                        existing.bracketPasteMode ?: tab.bracketPasteMode,
                        tab.oscHandler,
                        existing.feedOutput ?: tab.feedOutput,
                    )
                }
            }
        }
        // Unregister registry entries whose underlying *session* has
        // disconnected — not just entries without a UI tab. Headless
        // shells opened by the MCP agent (open_local_shell) live in
        // localSessions but never appear in currentTabs; sweeping by
        // tab presence alone tore those out immediately and broke
        // every snapshot-style MCP tool against agent-owned shells.
        val knownSessionIds = sshSessions.keys + rnsSessions.keys +
            moshSessions.keys + etSessions.keys + btSerialSessions.keys + bleSerialSessions.keys + usbSerialSessions.keys + localSessions.keys
        for (id in terminalSessionRegistry.sessions.value.keys.toList()) {
            if (id !in knownSessionIds) terminalSessionRegistry.unregister(id)
        }

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
            // Re-query remote sessions for the new active tab — without
            // this the long-press tab menu kept showing tmux names from
            // a previously-active tab whose profile used a different
            // session manager (e.g. user switches from a tmux profile to
            // a NONE profile and the stale tmux list lingers).
            refreshRemoteSessions()
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
            // Navigation now only happens after the connect path confirmed a
            // live terminal (ConnectionsViewModel.finishConnect awaits the
            // shell outcome), so the tab will appear momentarily — this is a
            // short, silent bridge for compose/sync timing only. No timeout
            // error or Toast here: a "tab never appears" state can no longer
            // occur on a successful connect, and on failure we never navigate.
            viewModelScope.launch {
                withTimeoutOrNull(2000) {
                    _tabs.first { tabs -> tabs.any { it.profileId == profileId } }
                }
                _tabs.value.indexOfFirst { it.profileId == profileId }
                    .takeIf { it >= 0 }
                    ?.let { _activeTabIndex.value = it }
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
        } else if (btSerialSessionManager.sessions.value.containsKey(sessionId)) {
            btSerialSessionManager.removeSession(sessionId)
        } else if (bleSerialSessionManager.sessions.value.containsKey(sessionId)) {
            bleSerialSessionManager.removeSession(sessionId)
        } else if (usbSerialSessionManager.sessions.value.containsKey(sessionId)) {
            usbSerialSessionManager.removeSession(sessionId)
        } else {
            reticulumSessionManager.removeSession(sessionId)
        }
        trackedSessionIds.remove(sessionId)
        // Removal is synchronous; the tab reconciliation (now suspending, since
        // it resolves profiles off-main) runs in a launched coroutine. (#208 #14)
        viewModelScope.launch {
            syncSessions()
            // Returning to Connections fires HERE — on an EXPLICIT user tab
            // close that empties the list — not inside syncSessions(). A
            // session dying on its own (e.g. a flaky-WireGuard drop right after
            // connect) also runs syncSessions and removes its tab, but must NOT
            // bounce the user off the Terminal they just connected to. That
            // reconciliation-driven bounce was the connect→Terminal→Connections
            // flicker.
            if (_tabs.value.isEmpty()) {
                _navigateToConnections.value = true
            }
        }
    }

    private fun removeAllForProfileAndSync(profileId: String) {
        sessionManager.removeAllSessionsForProfile(profileId)
        reticulumSessionManager.removeAllSessionsForProfile(profileId)
        moshSessionManager.removeAllSessionsForProfile(profileId)
        etSessionManager.removeAllSessionsForProfile(profileId)
        localSessionManager.removeAllSessionsForProfile(profileId)
        btSerialSessionManager.removeAllSessionsForProfile(profileId)
        bleSerialSessionManager.removeAllSessionsForProfile(profileId)
        usbSerialSessionManager.removeAllSessionsForProfile(profileId)
        trackedSessionIds.removeAll(
            _tabs.value.filter { it.profileId == profileId }.map { it.sessionId }.toSet()
        )
        viewModelScope.launch {
            syncSessions()
            // Explicit close (Connections-screen disconnect) → return to
            // Connections only if it emptied the tab list. See removeTabAndSync.
            if (_tabs.value.isEmpty()) {
                _navigateToConnections.value = true
            }
        }
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
    fun showNewTabMessage(message: String) { _newTabMessage.value = message }

    /**
     * FIDO2/security-key touch/PIN prompt, surfaced so the terminal screen can
     * show the same dialog as the Connections screen when a new-tab fresh dial
     * needs a security-key assertion — otherwise that auth would block with no
     * UI. Backed by the singleton [sh.haven.core.fido.FidoAuthenticator].
     */
    val fidoTouchPrompt: StateFlow<sh.haven.core.fido.FidoTouchPrompt?> = fidoAuthenticator.touchPrompt
    fun cancelFido() = fidoAuthenticator.cancelPending()

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

    private fun addLocalTab(activeTab: TerminalTab) =
        addLocalTabForProfile(activeTab.profileId, activeTab.label)

    /**
     * Open a local shell tab for [profileId] from scratch, picking the
     * `useAndroidShell` flag from any existing local session for the same
     * profile (so the workspace launcher matches what the user picked
     * when they last set up the profile manually). [label] defaults to
     * the profile's display name when called from outside the
     * clone-current-tab path.
     */
    /**
     * Sessions opened as plain shells, consumed once when the PTY is created.
     *
     * The session manager takes `plain` at [LocalSessionManager.createTerminalSession] time,
     * which happens later in syncSessions than the call that asked for the shell, so the
     * request has to be parked somewhere in between. Removed on use so a session id that is
     * later reused cannot inherit it.
     */
    private val plainSessionIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * True when a plain shell is meaningful for this tab: a local shell (whose PTY can skip
     * the session-manager wrapper) or an SSH profile configured with one.
     *
     * The point of the affordance is escaping a multiplexer, so it appears wherever there is
     * a multiplexer to escape — not only on local tabs. Serial and other transports have no
     * session-manager concept and are left alone.
     */
    fun canOpenPlainShell(sessionId: String): Boolean {
        if (localSessionManager.sessions.value[sessionId] != null) return true
        val profileId = sessionManager.getSession(sessionId)?.profileId ?: return false
        return sessionManager.getConnectionConfigForProfile(profileId)?.second?.listCommand != null
    }

    /**
     * Open a second shell on this tab's profile with the user's session-manager preference
     * (tmux/zellij/screen/byobu) bypassed — a bare login shell.
     *
     * Worth having in reach: a multiplexer's status bar reserves the bottom row with DECSTBM,
     * which defeats Haven's own scrollback capture, and a wedged multiplexer is easiest to
     * debug from a shell that is not inside it. The agent-facing open_local_shell has had
     * `plain` since #285; this is the same thing for the person holding the phone.
     */
    fun addPlainShellTab(sessionId: String) {
        localSessionManager.sessions.value[sessionId]?.profileId?.let { profileId ->
            addLocalTabForProfile(profileId, plain = true)
            return
        }
        val profileId = sessionManager.getSession(sessionId)?.profileId ?: return
        addSshTabForProfile(profileId, plain = true)
    }

    fun addLocalTabForProfile(
        profileId: String,
        label: String? = null,
        desktopDeId: String? = null,
        plain: Boolean = false,
    ) {
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val existingSession = localSessionManager.sessions.value.values
                    .find { it.profileId == profileId }
                val profile = connectionRepository.getById(profileId)
                val resolvedLabel = label
                    ?: profile?.label
                    ?: profileId.take(8)
                // Join a running desktop session (#285) when a deId was supplied.
                val desktopEnv = localSessionManager.resolveDesktopEnv(desktopDeId)
                val sessionId = localSessionManager.registerSession(
                    profileId, resolvedLabel,
                    useAndroidShell = existingSession?.useAndroidShell ?: profile?.useAndroidShell ?: false,
                    prootDistroId = existingSession?.prootDistroId ?: profile?.prootDistroId,
                    desktopEnv = desktopEnv,
                )
                if (plain) plainSessionIds.add(sessionId)
                localSessionManager.connectSession(sessionId)
                syncSessions()
                selectTabBySessionId(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addLocalTabForProfile failed: ${e.message}", e)
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
     * Open a Bluetooth-serial terminal for a BTSERIAL profile (#406): register a
     * session, open the RFCOMM link (blocking, on IO), then let [syncSessions]
     * build the tab from the now-CONNECTED session. Mirrors [addLocalTabForProfile].
     */
    fun addBtSerialTabForProfile(profileId: String, label: String? = null) {
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val profile = connectionRepository.getById(profileId)
                    ?: throw IllegalStateException("profile $profileId not found")
                val resolvedLabel = label ?: profile.label.ifBlank { profile.host }
                val sessionId = btSerialSessionManager.registerSession(
                    profileId = profileId,
                    label = resolvedLabel,
                    deviceAddress = profile.btDeviceAddress,
                )
                btSerialSessionManager.connectSession(sessionId) // opens RFCOMM (IO); throws on failure
                syncSessions()
                selectTabBySessionId(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addBtSerialTabForProfile failed: ${e.message}", e)
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
     * Open a BLE-serial terminal for a BLESERIAL profile: register a session,
     * open the GATT link (blocking, on IO), then let [syncSessions] build the tab.
     * Mirrors [addBtSerialTabForProfile]; BLE UART is a separate GATT transport.
     */
    fun addBleSerialTabForProfile(profileId: String, label: String? = null) {
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val profile = connectionRepository.getById(profileId)
                    ?: throw IllegalStateException("profile $profileId not found")
                val resolvedLabel = label ?: profile.label.ifBlank { profile.host }
                val sessionId = bleSerialSessionManager.registerSession(
                    profileId = profileId,
                    label = resolvedLabel,
                    deviceAddress = profile.bleDeviceAddress,
                )
                bleSerialSessionManager.connectSession(sessionId) // GATT connect (IO); throws on failure
                syncSessions()
                selectTabBySessionId(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addBleSerialTabForProfile failed: ${e.message}", e)
                _newTabMessage.value = appContext.getString(
                    R.string.terminal_new_tab_connection_failed,
                    e.message ?: e.javaClass.simpleName,
                )
            } finally {
                _newTabLoading.value = false
            }
        }
    }

    /** Open a USB-serial terminal tab for a USBSERIAL profile (#408). Resolves the
     *  attached device by vid:pid, requests USB permission, opens the port. */
    fun addUsbSerialTabForProfile(profileId: String, label: String? = null) {
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                val profile = connectionRepository.getById(profileId)
                    ?: throw IllegalStateException("profile $profileId not found")
                val resolvedLabel = label ?: profile.label.ifBlank { profile.host }
                val key = profile.usbDeviceKey
                // Resolve + grant USB permission before opening (see connectUsbSerial).
                val info = usbBroker.listDevices().firstOrNull {
                    "%04x:%04x".format(it.vendorId, it.productId) == key
                } ?: throw IllegalStateException("USB device $key is not plugged in")
                if (!usbBroker.requestPermission(info.deviceName)) {
                    throw IllegalStateException("USB permission denied")
                }
                val sessionId = usbSerialSessionManager.registerSession(
                    profileId = profileId,
                    label = resolvedLabel,
                    deviceKey = key,
                    params = sh.haven.core.usbserial.UsbSerialParams.fromConfigString(
                        profile.usbBaudRate,
                        profile.usbSerialConfig,
                    ),
                )
                usbSerialSessionManager.connectSession(sessionId) // opens the port (IO); throws on failure
                syncSessions()
                selectTabBySessionId(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "addUsbSerialTabForProfile failed: ${e.message}", e)
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
    fun addSshTabForProfile(
        profileId: String,
        preselectedSessionName: String? = null,
        plain: Boolean = false,
    ) {
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
            // Named-reattach path (workspace restore / OpenTerminalSession with
            // a session name): the shared SshSessionAttacher rides the live
            // connection and is idempotent — relaunching a workspace reselects
            // the existing tab instead of duplicating it. NoLiveConnection
            // falls through to the fresh-dial path below.
            if (preselectedSessionName != null) {
                val attach = sshSessionAttacher.ensureAttached(profileId, preselectedSessionName)
                val attachedId = when (attach) {
                    is sh.haven.core.ssh.SshSessionAttacher.Result.Attached -> attach.sessionId
                    is sh.haven.core.ssh.SshSessionAttacher.Result.AlreadyLive -> attach.sessionId
                    else -> null
                }
                if (attachedId != null) {
                    syncSessions()
                    selectTabBySessionId(attachedId)
                    _newTabLoading.value = false
                    return@launch
                }
                if (attach is sh.haven.core.ssh.SshSessionAttacher.Result.Failed) {
                    _newTabMessage.value =
                        appContext.getString(R.string.terminal_new_tab_connection_failed, attach.message)
                    _newTabLoading.value = false
                    return@launch
                }
            }
            // Reuse a live SSH connection to this profile instead of dialing a
            // second flow — a 2nd dial over a WireGuard/Tailscale tunnel can't be
            // serviced by some peers (a FRITZ!Box drops the 2nd concurrent WG→LAN
            // flow), and one SSH connection carrying several tmux sessions is the
            // canonical model. Reused for tunnel-routed profiles AND any
            // session-manager profile (tmux/screen/byobu/zellij) — re-dialing the
            // latter would force a second authentication (e.g. a FIDO/YubiKey tap,
            // whose prompt isn't surfaced in the terminal screen, so it would hang).
            // Only "None" keeps independent-per-tab shells.
            val reuseClient = if (
                connectionRepository.getById(profileId)?.tunnelConfigId != null ||
                sshSessionMgr.listCommand != null
            ) {
                // awaitReusableClient also waits for an in-flight connect (the MCP
                // carrier on app launch) so a restored tab reuses it instead of
                // racing a second dial.
                sessionManager.awaitReusableClient(profileId)
            } else null
            val client = reuseClient ?: SshClient().apply {
                fidoAuthenticator = this@TerminalViewModel.fidoAuthenticator
                openKeychainClients = OpenKeychainClientFactory.from(appContext)
            }
            val sessionId = sessionManager.registerSession(profileId, label, client)
            try {
                if (reuseClient != null) {
                    Log.i(TAG, "Reusing live SSH connection to $label for a new tab (no new dial)")
                    sessionManager.storeReuseConfig(sessionId, profileId, sshSessionMgr)
                } else {
                    // Route through the profile's tunnel (WireGuard / Tailscale) or
                    // legacy SOCKS/HTTP proxy, same as the primary connect path
                    // (ConnectionsViewModel). Without this a second tab on a
                    // tunnel-routed profile dials the host directly and times out
                    // ("Session not connected") because the host is only reachable
                    // through the tunnel.
                    val proxy = connectionRepository.getById(profileId)
                        ?.let { tunnelResolver.havenProxy(it) }
                    val hostKeyEntry = withContext(Dispatchers.IO) {
                        client.connect(config, proxy = proxy, trustedHostCaKeys = hostKeyVerifier.trustedHostCaKeys())
                    }

                    // Silent TOFU is fail-closed: a new tab never silently trusts
                    // an unknown or changed host key; the user establishes trust
                    // via an interactive connect (host-key prompt). (#5)
                    when (val hkResult = if (hostKeyEntry == null) HostKeyResult.Trusted else hostKeyVerifier.verify(hostKeyEntry)) {
                        is HostKeyResult.Trusted -> { /* matches — continue */ }
                        is HostKeyResult.NewHost -> {
                            client.disconnect()
                            sessionManager.removeSession(sessionId)
                            Log.w(TAG, "Unknown host key for ${LogRedact.host(config.host, config.port)} — aborting new tab")
                            _newTabMessage.value = appContext.getString(
                                R.string.terminal_new_tab_host_key_unknown,
                                "${config.host}:${config.port}",
                            )
                            _newTabLoading.value = false
                            return@launch
                        }
                        is HostKeyResult.KeyChanged -> {
                            client.disconnect()
                            sessionManager.removeSession(sessionId)
                            Log.w(TAG, "Host key changed for ${LogRedact.host(config.host, config.port)} — aborting new tab")
                            _newTabMessage.value = appContext.getString(
                                R.string.terminal_new_tab_host_key_changed,
                                "${config.host}:${config.port}",
                            )
                            _newTabLoading.value = false
                            return@launch
                        }
                    }

                    sessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr)
                    // Re-resolve the profile's tunnel / proxy on auto-reconnect, the
                    // same way the initial connect did — otherwise a tunnel-routed
                    // tab reconnects directly and stalls when off-LAN.
                    sessionManager.setReconnectProxyProvider(sessionId) {
                        connectionRepository.getById(profileId)?.let { tunnelResolver.havenProxy(it) }
                    }
                }

                // Restore path: attach straight to the saved session and skip the
                // picker (mirrors ConnectionsViewModel.connectSsh's preselected
                // branch). tmux/zellij "new-session -A -s <name>" attaches if it
                // exists and creates it otherwise.
                if (preselectedSessionName != null) {
                    sessionManager.setChosenSessionName(sessionId, preselectedSessionName)
                }

                // "Plain shell": never offer the session list, and tell the SSH side to
                // ignore the profile's tmux/zellij/screen wrapper for this connection only.
                // Same two calls the picker's own Plain shell row makes, so there is one
                // implementation of what "plain" means on an SSH profile.
                if (plain) {
                    sessionManager.setBypassSessionManager(sessionId, true)
                    sessionManager.setChosenSessionName(sessionId, "shell")
                    finishNewSshTab(sessionId)
                    return@launch
                }
                val listCmd = sshSessionMgr.listCommand
                if (preselectedSessionName == null && listCmd != null) {
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
            // Reuse a live SSH connection to this profile instead of dialing a
            // second flow (a 2nd dial over a WireGuard tunnel can't be serviced
            // by some peers; one SSH connection carries multiple tmux sessions).
            // Reused for tunnel-routed AND session-manager profiles — re-dialing
            // the latter would force a second auth (e.g. a FIDO/YubiKey tap).
            val reuseClient = if (
                connectionRepository.getById(profileId)?.tunnelConfigId != null ||
                sshSessionMgr.listCommand != null
            ) {
                // awaitReusableClient also waits for an in-flight connect (the MCP
                // carrier on app launch) so a restored tab reuses it instead of
                // racing a second dial.
                sessionManager.awaitReusableClient(profileId)
            } else null
            val client = reuseClient ?: SshClient().apply {
                fidoAuthenticator = this@TerminalViewModel.fidoAuthenticator
                openKeychainClients = OpenKeychainClientFactory.from(appContext)
            }
            val sessionId = sessionManager.registerSession(profileId, label, client)
            try {
                if (reuseClient != null) {
                    Log.i(TAG, "Reusing live SSH connection to $label to attach session '$sessionName' (no new dial)")
                    sessionManager.storeReuseConfig(sessionId, profileId, sshSessionMgr)
                } else {
                    // Route through the profile's tunnel / proxy (see addSshTabForProfile).
                    val proxy = connectionRepository.getById(profileId)
                        ?.let { tunnelResolver.havenProxy(it) }
                    val hostKeyEntry = withContext(Dispatchers.IO) { client.connect(config, proxy = proxy, trustedHostCaKeys = hostKeyVerifier.trustedHostCaKeys()) }
                    when (val hkResult = if (hostKeyEntry == null) HostKeyResult.Trusted else hostKeyVerifier.verify(hostKeyEntry)) {
                        is HostKeyResult.Trusted -> {}
                        is HostKeyResult.NewHost -> {
                            // Fail closed on an unknown host in the silent new-tab
                            // path; trust is established interactively. (#5)
                            client.disconnect()
                            sessionManager.removeSession(sessionId)
                            _newTabMessage.value = appContext.getString(
                                R.string.terminal_new_tab_host_key_unknown,
                                "${config.host}:${config.port}",
                            )
                            _newTabLoading.value = false
                            return@launch
                        }
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
                    // Re-resolve the profile's tunnel / proxy on auto-reconnect.
                    sessionManager.setReconnectProxyProvider(sessionId) {
                        connectionRepository.getById(profileId)?.let { tunnelResolver.havenProxy(it) }
                    }
                }
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

    /**
     * "Plain shell" path from the new-tab picker — finish the SSH side
     * for [sessionId] without invoking the configured session manager.
     * Mirrors [sh.haven.feature.connections.ConnectionsViewModel.onPlainShellSelected]
     * for the long-press-tab flow, so a one-off bare bash on a tmux
     * profile is reachable from either entry point.
     */
    fun onNewTabPlainShellSelected(sessionId: String) {
        _newTabSessionPicker.value = null
        viewModelScope.launch {
            _newTabLoading.value = true
            try {
                sessionManager.setBypassSessionManager(sessionId, true)
                sessionManager.setChosenSessionName(sessionId, "shell")
                finishNewSshTab(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "onNewTabPlainShellSelected failed", e)
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
        val base = sanitizeSessionName(label)
        val existing = remoteNames.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base-$i" in existing) i++
        return "$base-$i"
    }

    fun killRemoteSession(sessionName: String) {
        val sel = _newTabSessionPicker.value ?: return
        // Session names come from server-controlled `tmux ls`/`screen -ls` output
        // and a free-form rename field; sanitize before they reach the `sh -c '…'`
        // command template, mirroring buildSessionManagerCommand. (#208 finding 5)
        val killCmd = sel.manager.killCommand?.invoke(sanitizeSessionName(sessionName)) ?: return
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
        // Sanitize both names before they hit the rename command template — the
        // new name is free-form user input, the old name is server-listed. (#208 #5)
        val renameCmd = sel.manager.renameCommand?.invoke(
            sanitizeSessionName(oldName), sanitizeSessionName(newName),
        ) ?: return
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
     * Three-layer "where am I?" snapshot for a live terminal tab:
     * door (connection card), path (transport), room (multiplexer session).
     */
    data class ConnectionWhereabouts(
        val sessionId: String,
        /** Door — connection profile label. */
        val doorLabel: String,
        val doorId: String,
        val doorHost: String,
        val doorUser: String,
        /** Path — SSH / Mosh / Eternal Terminal / … */
        val pathTransport: String,
        val pathDetail: String,
        /** Room — live multiplexer / tab session name when known. */
        val roomName: String?,
        /** Room the card's remoteCommand is pinned to (if any). */
        val roomPinnedOnCard: String?,
        /** True when live room differs from the card pin. */
        val roomMismatch: Boolean,
        val summaryText: String,
    )

    /**
     * Draft for "Save connection" — pin the live multiplexer session onto a
     * connection profile (name editable, id stable), with door/path/room context.
     */
    data class SaveConnectionUi(
        val draft: SaveConnectionFromSession.Draft,
        val sourceProfileId: String,
        val doorLabel: String,
        val pathTransport: String,
        val roomName: String,
        val roomPinnedOnCard: String?,
        val roomMismatch: Boolean,
    )

    private val _saveConnectionUi = MutableStateFlow<SaveConnectionUi?>(null)
    val saveConnectionUi: StateFlow<SaveConnectionUi?> = _saveConnectionUi.asStateFlow()

    private val _whereaboutsUi = MutableStateFlow<ConnectionWhereabouts?>(null)
    val whereaboutsUi: StateFlow<ConnectionWhereabouts?> = _whereaboutsUi.asStateFlow()

    fun dismissSaveConnection() {
        _saveConnectionUi.value = null
    }

    fun dismissWhereabouts() {
        _whereaboutsUi.value = null
    }

    /** Details is available for any SSH-family terminal tab. */
    fun canShowDetails(sessionId: String): Boolean {
        val tab = _tabs.value.find { it.sessionId == sessionId } ?: return false
        return tab.transportType in setOf("SSH", "MOSH", "ET")
    }

    /**
     * Whether the tab menu should offer Save connection. Cheap sync check;
     * full validation runs in [beginSaveConnection].
     */
    fun canSaveConnection(sessionId: String): Boolean {
        val ssh = sessionManager.getSession(sessionId)
        if (ssh != null &&
            ssh.sessionManager != SessionManager.NONE &&
            !ssh.chosenSessionName.isNullOrBlank()
        ) {
            return true
        }
        val tab = _tabs.value.find { it.sessionId == sessionId } ?: return false
        return tab.transportType in setOf("SSH", "MOSH", "ET")
    }

    /** Open the Details (where am I?) dialog for [sessionId]. */
    fun beginShowDetails(sessionId: String) {
        viewModelScope.launch {
            val w = buildWhereabouts(sessionId)
            if (w == null) {
                _newTabMessage.value =
                    appContext.getString(R.string.terminal_details_unavailable)
                return@launch
            }
            _whereaboutsUi.value = w
        }
    }

    /** Open the Save connection confirm dialog for [sessionId]. */
    fun beginSaveConnection(sessionId: String) {
        viewModelScope.launch {
            val pin = resolveSavePin(sessionId)
            if (pin == null) {
                _newTabMessage.value =
                    appContext.getString(R.string.terminal_save_connection_need_session)
                return@launch
            }
            val (source, sessionName, manager) = pin
            val existing = withContext(Dispatchers.IO) { connectionRepository.getAll() }
            // Prefer card label as default name when pinning the same room already
            // on the card; otherwise use the live room name (clearer for new pins).
            val cardPin = SaveConnectionFromSession.sessionNameFromRemoteCommand(source.remoteCommand)
            val defaultLabel = when {
                cardPin != null && cardPin == sessionName && source.label.isNotBlank() -> source.label
                else -> sessionName
            }
            val draft = SaveConnectionFromSession.draft(
                source = source,
                sessionName = sessionName,
                manager = manager,
                existing = existing,
                defaultLabel = defaultLabel,
            )
            if (draft == null) {
                _newTabMessage.value =
                    appContext.getString(R.string.terminal_save_connection_need_session)
                return@launch
            }
            val tab = _tabs.value.find { it.sessionId == sessionId }
            val path = formatTransport(tab?.transportType, source)
            val mismatch = cardPin != null && cardPin != sessionName
            _saveConnectionUi.value = SaveConnectionUi(
                draft = draft,
                sourceProfileId = source.id,
                doorLabel = source.label,
                pathTransport = path,
                roomName = sessionName,
                roomPinnedOnCard = cardPin,
                roomMismatch = mismatch,
            )
        }
    }

    private suspend fun buildWhereabouts(sessionId: String): ConnectionWhereabouts? {
        val tab = _tabs.value.find { it.sessionId == sessionId } ?: return null
        if (tab.transportType !in setOf("SSH", "MOSH", "ET")) return null
        val profile = withContext(Dispatchers.IO) {
            connectionRepository.getById(tab.profileId)
        } ?: return null
        val ssh = sessionManager.getSession(sessionId)
        val liveRoom = ssh?.chosenSessionName?.takeIf { it.isNotBlank() }
            ?: SaveConnectionFromSession.sessionNameFromRemoteCommand(profile.remoteCommand)
            ?: profile.lastSessionName?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.singleOrNull()
            ?: tab.label.takeIf { it.isNotBlank() && it != profile.label }
        val pinned = SaveConnectionFromSession.sessionNameFromRemoteCommand(profile.remoteCommand)
        val path = formatTransport(tab.transportType, profile)
        val pathDetail = buildString {
            append(profile.username)
            append('@')
            append(profile.host)
            append(':')
            append(profile.port)
            when {
                profile.useEternalTerminal -> append(" · etPort ").append(profile.etPort)
                profile.useMosh -> append(" · mosh")
            }
            if (!profile.remoteCommand.isNullOrBlank()) {
                append("\nremoteCommand: ").append(profile.remoteCommand)
            }
        }
        val mismatch = pinned != null && liveRoom != null &&
            SessionManager.sanitizeSessionName(pinned) != SessionManager.sanitizeSessionName(liveRoom)
        val summary = buildString {
            appendLine("DOOR (card)")
            appendLine("  ${profile.label}")
            appendLine("  id ${profile.id}")
            appendLine("  ${profile.username}@${profile.host}:${profile.port}")
            appendLine()
            appendLine("PATH (transport)")
            appendLine("  $path")
            appendLine("  ${profile.username}@${profile.host}:${profile.port}")
            when {
                profile.useEternalTerminal -> appendLine("  etPort ${profile.etPort}")
                profile.useMosh -> appendLine("  mosh")
            }
            appendLine()
            appendLine("ROOM (session)")
            appendLine("  live: ${liveRoom ?: "— unknown —"}")
            appendLine("  card pin: ${pinned ?: "— none —"}")
            if (mismatch) appendLine("  ⚠ live room differs from card pin")
        }.trimEnd()
        return ConnectionWhereabouts(
            sessionId = sessionId,
            doorLabel = profile.label,
            doorId = profile.id,
            doorHost = profile.host,
            doorUser = profile.username,
            pathTransport = path,
            pathDetail = pathDetail,
            roomName = liveRoom,
            roomPinnedOnCard = pinned,
            roomMismatch = mismatch,
            summaryText = summary,
        )
    }

    private fun formatTransport(
        transportType: String?,
        profile: sh.haven.core.data.db.entities.ConnectionProfile,
    ): String = when (transportType) {
        "ET" -> "Eternal Terminal"
        "MOSH" -> "Mosh"
        "SSH" -> when {
            profile.useEternalTerminal -> "Eternal Terminal"
            profile.useMosh -> "Mosh"
            else -> "SSH"
        }
        else -> transportType ?: when {
            profile.useEternalTerminal -> "Eternal Terminal"
            profile.useMosh -> "Mosh"
            else -> "SSH"
        }
    }

    /**
     * Persist the draft with the user-edited [displayName]. Upserts by
     * label+host+user so a second save with the same name updates the pin.
     */
    fun confirmSaveConnection(displayName: String) {
        val ui = _saveConnectionUi.value ?: return
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                connectionRepository.getById(ui.sourceProfileId)
            } ?: run {
                _saveConnectionUi.value = null
                return@launch
            }
            val existing = withContext(Dispatchers.IO) { connectionRepository.getAll() }
            val result = SaveConnectionFromSession.build(
                source = source,
                displayName = displayName,
                sessionName = ui.draft.sessionName,
                manager = ui.draft.sessionManager,
                existing = existing,
                preferredId = ui.draft.profileId,
            )
            if (result == null) {
                _newTabMessage.value =
                    appContext.getString(R.string.terminal_save_connection_need_session)
                _saveConnectionUi.value = null
                return@launch
            }
            withContext(Dispatchers.IO) { connectionRepository.save(result.profile) }
            _saveConnectionUi.value = null
            val msgRes = if (result.created) {
                R.string.terminal_save_connection_saved
            } else {
                R.string.terminal_save_connection_updated
            }
            _newTabMessage.value = appContext.getString(msgRes, result.profile.label)
        }
    }

    /**
     * Manager to use when writing a pin. Never [SessionManager.NONE] — a save
     * always needs a real attach command. Global pref NONE still defaults to
     * tmux (most common), otherwise dogfood of remoteCommand / plain-shell
     * profiles falsely shows "attach a multiplexer first" while already inside one.
     */
    private suspend fun resolveManagerForPin(source: sh.haven.core.data.db.entities.ConnectionProfile): SessionManager {
        SaveConnectionFromSession.parseSessionManager(source.sessionManager)
            ?.takeIf { it != SessionManager.NONE }
            ?.let { return it }
        return try {
            val pref = SessionManager.valueOf(preferencesRepository.sessionManager.first().name)
            if (pref == SessionManager.NONE) SessionManager.TMUX else pref
        } catch (_: IllegalArgumentException) {
            SessionManager.TMUX
        }
    }

    /**
     * Resolve the multiplexer session to pin from a live terminal tab.
     * Prefers an SSH [SessionManager] attach; falls back to profile
     * remoteCommand / lastSessionName / tab label (Mosh/ET + remoteCommand).
     */
    private suspend fun resolveSavePin(
        sessionId: String,
    ): Triple<sh.haven.core.data.db.entities.ConnectionProfile, String, SessionManager>? {
        val tab = _tabs.value.find { it.sessionId == sessionId }
        val ssh = sessionManager.getSession(sessionId)
        // Mosh/ET tabs don't live in SshSessionManager — profileId is only on the tab.
        val profileId = tab?.profileId ?: ssh?.profileId ?: return null
        val source = withContext(Dispatchers.IO) {
            connectionRepository.getById(profileId)
        } ?: return null
        if (!source.isSsh) return null

        if (ssh != null &&
            ssh.sessionManager != SessionManager.NONE &&
            !ssh.chosenSessionName.isNullOrBlank()
        ) {
            return Triple(source, ssh.chosenSessionName!!, ssh.sessionManager)
        }

        val mgr = resolveManagerForPin(source)

        // remoteCommand pin (incl. Mosh/ET profiles that skip SessionManager)
        SaveConnectionFromSession.sessionNameFromRemoteCommand(source.remoteCommand)?.let { name ->
            return Triple(source, name, mgr)
        }

        // lastSessionName: single, or pick the part matching the tab label
        val lastParts = source.lastSessionName
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (lastParts.size == 1) {
            return Triple(source, lastParts[0], mgr)
        }
        val tabLabel = tab?.label?.trim().orEmpty()
        if (lastParts.size > 1 && tabLabel.isNotEmpty()) {
            val match = lastParts.firstOrNull {
                it == tabLabel || SessionManager.sanitizeSessionName(it) ==
                    SessionManager.sanitizeSessionName(tabLabel)
            }
            if (match != null) return Triple(source, match, mgr)
        }

        // Tab label last resort (often equals the multiplexer / role name)
        if (tabLabel.isNotEmpty()) {
            val san = SessionManager.sanitizeSessionName(tabLabel)
            if (san.isNotBlank()) return Triple(source, san, mgr)
        }
        return null
    }

    /**
     * Rename the tmux/zellij/screen session backing a connected tab — no need
     * to open a duplicate session via the picker. On success, propagates the
     * new name to the profile's lastSessionName + chosenSessionName + tab label.
     */
    fun renameAttachedSession(sessionId: String, newName: String) {
        val s = sessionManager.getSession(sessionId) ?: return
        val oldName = s.chosenSessionName?.takeIf { it.isNotBlank() } ?: return
        // Sanitize before the rename template; newName is free-form input. (#208 #5)
        val cmd = s.sessionManager.renameCommand?.invoke(
            sanitizeSessionName(oldName), sanitizeSessionName(newName),
        ) ?: return
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
        SessionManager.sanitizeSessionName(name)

    fun dismissNewTabSessionPicker() {
        val sel = _newTabSessionPicker.value ?: return
        _newTabSessionPicker.value = null
        sessionManager.removeSession(sel.sessionId)
    }

    private suspend fun finishNewSshTab(sessionId: String) {
        // Await a definitive shell outcome (same contract as ConnectionsViewModel
        // .finishConnect): only add + select the tab on a confirmed live shell;
        // on an immediate shell-close show a clear, navigation-surviving error
        // instead of leaving a blank tab / stranding on another. (#215 follow-up)
        val outcome = withContext(Dispatchers.IO) {
            sessionManager.openShellAndAwaitReady(sessionId)
        }
        when (outcome) {
            is sh.haven.core.ssh.ShellOutcome.Ready -> {
                sessionManager.updateStatus(sessionId, SessionState.Status.CONNECTED)
                syncSessions()
                selectTabBySessionId(sessionId)
            }
            is sh.haven.core.ssh.ShellOutcome.ShellClosed -> {
                sessionManager.removeSession(sessionId)
                userMessageBus.error("Shell closed — is your session manager (tmux/zellij/screen) installed on this host?")
            }
            is sh.haven.core.ssh.ShellOutcome.Failed -> {
                sessionManager.removeSession(sessionId)
                userMessageBus.error(outcome.reason.ifBlank { "Failed to open the remote shell" })
            }
        }
    }
}
