package sh.haven.feature.terminal

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.TerminalAttachment
import sh.haven.core.ssh.TerminalAttachmentProvider
import sh.haven.core.ssh.TerminalSession
import sh.haven.feature.terminal.agent.TerminalSessionRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshTermEmulatorOwner"

/**
 * Creates the terminal emulator for an SSH session at **connect time** and owns
 * it across [TerminalViewModel] lifecycles (#290 issue #2).
 *
 * The bug: tmux's attach-time capability probes (DA2 `ESC[>0;100;0c`, XTVERSION
 * `ESC P>|libvterm(0.3)`) were answered *late* — the emulator used to be created
 * only when the nav-scoped ViewModel mounted the Terminal tab and replayed the
 * #289 connect-window backlog into it. By then tmux's probe window had closed,
 * so it forwarded the late responses to the inner shell, which echoed them as
 * `0c)\` on the prompt.
 *
 * The fix: this app-scoped `@Singleton` registers a [TerminalAttachmentProvider]
 * with [SshSessionManager], so [SshSessionManager.createTerminalSession] wires
 * the emulator as the session's data callback BEFORE the reader starts. The
 * emulator parses output — and answers probes — live from the first byte. The
 * ViewModel then *adopts* this emulator on tab-mount instead of creating its own,
 * and re-adopts it after Activity recreation (so #272 needs no scrollback replay:
 * the same emulator kept its screen the whole time).
 *
 * The emulator's `onKeyboardInput`/`onResize` are fixed at construction, so they
 * delegate to per-session mutable [Bundle.inputSink]/[Bundle.resizeSink]. Default
 * (connect, no UI): input/resize go straight to the session. On adopt, the VM
 * swaps them to route user input through its keyboard-modifier state and to
 * resize all tabs; on VM teardown the VM calls [resetSinks] to repoint them back
 * at the session, so a remote app's query is still answered while no UI exists.
 *
 * SSH only — other transports keep the VM-creates-emulator model.
 */
@Singleton
class SshTerminalEmulatorOwner @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val connectionRepository: ConnectionRepository,
    @ApplicationContext private val appContext: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val registry: TerminalSessionRegistry,
) {
    /** Per-session emulator + output pipeline, owned here across ViewModel cycles. */
    class Bundle(
        val sessionId: String,
        /** Kept so teardown can attribute the retained final screen (#58 soak). */
        val profileId: String?,
        val label: String?,
        val oscHandler: OscHandler,
        val cwdFlow: MutableStateFlow<String?>,
        val hyperlinkFlow: MutableStateFlow<String?>,
        val mouseTracker: MouseModeTracker,
        val recorder: TerminalRecorder?,
    ) {
        lateinit var emulator: TerminalEmulator
        lateinit var feedOutput: (ByteArray, Int, Int) -> Unit

        @Volatile var session: TerminalSession? = null

        /** Swapped between connect-default (→session) and UI-adopt (→VM) routing. */
        @Volatile var inputSink: (ByteArray) -> Unit = {}
        @Volatile var resizeSink: (TerminalDimensions) -> Unit = {}

        fun emulatorOrNull(): TerminalEmulator? = if (::emulator.isInitialized) emulator else null
    }

    /** Swap point for tests — defaults to the real (JNI) emulator factory. */
    fun interface EmulatorFactory {
        fun create(
            foreground: Color,
            background: Color,
            enableAltScreen: Boolean,
            maxScrollbackLines: Int,
            onKeyboardInput: (ByteArray) -> Unit,
            onResize: (TerminalDimensions) -> Unit,
        ): TerminalEmulator
    }

    internal var emulatorFactory: EmulatorFactory = EmulatorFactory { fg, bg, alt, scrollback, oki, ors ->
        TerminalEmulatorFactory.create(
            autoDetectUrls = true,
            initialRows = 24,
            initialCols = 80,
            defaultForeground = fg,
            defaultBackground = bg,
            enableAltScreen = alt,
            onKeyboardInput = oki,
            onResize = ors,
            maxScrollbackLines = scrollback,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val bundles = ConcurrentHashMap<String, Bundle>()

    // Caches populated from preferences/profiles so attach() (on the connect
    // thread) never blocks. Connect happens long after these first emit, so the
    // defaults only apply in the brief window right after app start.
    @Volatile private var profilesById: Map<String, ConnectionProfile> = emptyMap()
    @Volatile private var verboseLogging = false
    @Volatile private var scrollbackRows = UserPreferencesRepository.DEFAULT_SCROLLBACK_ROWS
    @Volatile private var globalScheme = UserPreferencesRepository.TerminalColorScheme.HAVEN

    /** Register the provider + start the caches. Call once from `HavenApp.onCreate`. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        sshSessionManager.terminalAttachmentProvider = TerminalAttachmentProvider { id -> attach(id) }
        scope.launch { connectionRepository.observeAll().collect { list -> profilesById = list.associateBy { it.id } } }
        scope.launch { preferencesRepository.verboseLoggingEnabled.collect { verboseLogging = it } }
        scope.launch { preferencesRepository.terminalScrollbackRows.collect { scrollbackRows = it } }
        scope.launch { preferencesRepository.terminalColorScheme.collect { globalScheme = it } }
        // Dispose a bundle only when its session is fully removed from the map
        // (the removeSession teardown). RECONNECTING / clean-exit-still-present
        // keep the same TerminalSession, so the bundle must survive them.
        scope.launch {
            sshSessionManager.sessions.collect { map ->
                val live = map.keys
                bundles.keys.filter { it !in live }.forEach { dispose(it) }
            }
        }
    }

    /**
     * Build the emulator + pipeline for [sessionId] and return the attachment
     * that [SshSessionManager.createTerminalSession] wires in. Called on the
     * connect thread before the reader starts.
     */
    fun attach(sessionId: String): TerminalAttachment? {
        val state = sshSessionManager.sessions.value[sessionId] ?: return null
        val profile = profilesById[state.profileId]

        val oscHandler = OscHandler()
        val cwdFlow = MutableStateFlow<String?>(null)
        val hyperlinkFlow = MutableStateFlow<String?>(null)
        oscHandler.onCwdChanged = { cwdFlow.value = it }
        oscHandler.onHyperlink = { uri -> hyperlinkFlow.value = uri }
        val mouseTracker = MouseModeTracker()
        val recorder = createRecorderIfEnabled(sessionId)

        val bundle = Bundle(
            sessionId,
            state.profileId,
            profile?.label,
            oscHandler,
            cwdFlow,
            hyperlinkFlow,
            mouseTracker,
            recorder,
        )
        val writeBuffer = EmulatorWriteBuffer({ bundle.emulatorOrNull() }, recorder)
        // OSC scan → mouse-mode scan → emulator. Same pipeline the agent's
        // feed_terminal_output goes through; synchronized on the OSC handler
        // because its scanner state is not thread-safe.
        val feedOutput: (ByteArray, Int, Int) -> Unit = { data, offset, length ->
            synchronized(oscHandler) {
                oscHandler.process(data, offset, length)
                mouseTracker.process(oscHandler.outputBuf, 0, oscHandler.outputLen)
                val len = oscHandler.outputLen
                if (len > 0) writeBuffer.append(oscHandler.outputBuf, 0, len)
            }
        }
        bundle.feedOutput = feedOutput

        val scheme = profile?.terminalColorScheme
            ?.let { runCatching { UserPreferencesRepository.TerminalColorScheme.valueOf(it) }.getOrNull() }
            ?: globalScheme
        val emulator = emulatorFactory.create(
            foreground = Color(scheme.foreground),
            background = Color(scheme.background),
            // alt-screen off for `screen` (no alt-screen) and profile opt-out.
            enableAltScreen = profile?.disableAltScreen != true && profile?.sessionManager != "screen",
            maxScrollbackLines = scrollbackRows,
            onKeyboardInput = { data -> bundle.inputSink(data) },
            onResize = { dims -> bundle.resizeSink(dims) },
        )
        bundle.emulator = emulator
        bundles[sessionId] = bundle

        // Pre-register so MCP reads (read_terminal_snapshot / feed_terminal_output)
        // work from connect time, before any UI tab mounts.
        registry.register(sessionId, emulator)
        registry.setAgentHandles(
            sessionId,
            mouseTracker.mouseMode,
            mouseTracker.activeMouseMode,
            mouseTracker.bracketPasteMode,
            mouseTracker.altScreen,
            mouseTracker.cursorKeyAppMode,
            oscHandler,
            feedOutput,
        )

        return object : TerminalAttachment {
            override val onData: (ByteArray, Int, Int) -> Unit = feedOutput
            override fun onReady(session: TerminalSession) {
                bundle.session = session
                // Default routing before the UI adopts: probe responses and
                // resizes go straight to the session.
                bundle.inputSink = { session.sendToSsh(it) }
                bundle.resizeSink = { dims -> session.resize(dims.columns, dims.rows) }
            }
        }
    }

    fun bundleFor(sessionId: String): Bundle? = bundles[sessionId]

    fun setInputSink(sessionId: String, sink: (ByteArray) -> Unit) {
        bundles[sessionId]?.inputSink = sink
    }

    fun setResizeSink(sessionId: String, sink: (TerminalDimensions) -> Unit) {
        bundles[sessionId]?.resizeSink = sink
    }

    /** Repoint sinks off a dying ViewModel back at the session (or no-op if gone). */
    fun resetSinks(sessionId: String) {
        val b = bundles[sessionId] ?: return
        val s = b.session
        b.inputSink = if (s != null) ({ s.sendToSsh(it) }) else ({})
        b.resizeSink = if (s != null) ({ dims -> s.resize(dims.columns, dims.rows) }) else ({})
    }

    internal fun dispose(sessionId: String) {
        val b = bundles.remove(sessionId) ?: return
        b.recorder?.close()
        registry.unregister(sessionId, b.profileId, b.label)
        // Frees the native terminal now rather than whenever the collector next
        // runs (#509). Unregistering first matters: it is what stops the MCP
        // agent handing out this emulator, so nothing new can arrive between
        // here and the close. `emulatorOrNull` because a bundle can be disposed
        // before `attach` ever built one.
        b.emulatorOrNull()?.close()
        Log.d(TAG, "Disposed SSH emulator bundle for $sessionId")
    }

    private fun createRecorderIfEnabled(sessionId: String): TerminalRecorder? {
        if (!verboseLogging) return null
        return runCatching {
            val dir = java.io.File(appContext.filesDir, "terminal-recordings").apply { mkdirs() }
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(dir, "session-$ts-${sessionId.take(8)}.bin")
            Log.d(TAG, "Recording terminal data to ${file.absolutePath}")
            TerminalRecorder(file)
        }.getOrNull()
    }
}
