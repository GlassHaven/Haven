package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.proot.LaunchSpec
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.wayland.WaylandBridge
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DesktopManager"

/**
 * Manages multiple desktop environment processes running simultaneously.
 * Each desktop gets its own X11 display number and VNC port.
 * Native Wayland is limited to one instance (WaylandBridge is singleton).
 *
 * X11/VNC desktops use software rendering to avoid virgl contention
 * with the native Wayland compositor.
 */
@Singleton
class DesktopManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    enum class DesktopState { STOPPED, STARTING, RUNNING, ERROR }

    /** An exit this soon after launch is reported as a startup failure even
     * if the VNC port check already flipped the row to RUNNING (#550). */
    private val EARLY_DEATH_WINDOW_MS = 15_000L

    data class DesktopInstance(
        val de: ProotManager.DesktopEnvironment,
        val displayNumber: Int,
        val vncPort: Int,
        val state: DesktopState,
        val errorMessage: String? = null,
    )

    private val _desktops = MutableStateFlow<Map<ProotManager.DesktopEnvironment, DesktopInstance>>(emptyMap())
    val desktops: StateFlow<Map<ProotManager.DesktopEnvironment, DesktopInstance>> = _desktops.asStateFlow()

    // Internal process tracking (not exposed in DesktopInstance)
    private val processes = mutableMapOf<ProotManager.DesktopEnvironment, Process>()

    // Rolling tail of stdout/stderr per DE (most recent 20 lines), so that
    // a script-exit-during-startup can be reported with context rather
    // than just "didn't come up". Used by the exit thread when state is
    // still STARTING at process exit (#162 bug B, Arch silent fail —
    // there is no early signal otherwise).
    private val logTails = mutableMapOf<ProotManager.DesktopEnvironment, ArrayDeque<String>>()
    private val logTailLimit = 20

    // Display number allocation for X11 desktops
    private val usedDisplays = mutableSetOf<Int>()

    // Per-(distro, DE) VNC port preference. Empty when the user accepted
    // the default at install time; non-zero when the install dialog's
    // port field was edited. Reads/writes go through getPortPreference /
    // setPortPreference so the SharedPreferences instance is consulted
    // once per startDesktop rather than once per accessor.
    private val portPrefs by lazy {
        context.getSharedPreferences("desktop-port-prefs", Context.MODE_PRIVATE)
    }

    init {
        // Kill orphaned Xvnc processes from previous app instances
        killAllOrphanedXvnc()
    }

    // Synchronized: concurrent app windows (multiple cages) can call
    // allocate/release at once, and a racing read-then-add on the plain
    // mutableSet could hand two sessions the same display → same VNC port.
    @Synchronized
    private fun allocateDisplay(): Int {
        var display = 1
        while (display in usedDisplays || 5900 + display in allPinnedPorts()) display++
        usedDisplays.add(display)
        return display
    }

    /**
     * Ports pinned by ANY family in the shared pref store — proot decks
     * (`<distro>_<de>`) and the UML guest's desktop pins (`uml_<kind>`,
     * set by UmlDesktopManager) live in one space. Cross-family skips here
     * are what keep a fresh proot install from landing on the port a UML
     * desktop was pinned to.
     */
    private fun allPinnedPorts(excludeKey: String? = null): Set<Int> =
        portPrefs.all.entries.asSequence()
            .filter { it.key != excludeKey }
            .mapNotNull { (it.value as? Int)?.takeIf { v -> v in 5901..5999 } }
            .toSet()

    @Synchronized
    private fun releaseDisplay(display: Int) {
        usedDisplays.remove(display)
    }

    /**
     * Per-(distro, DE) port preference. Returns 0 when no preference is
     * stored — caller falls back to [allocateDisplay] + 5900. Stored
     * keys are `<distroId>_<deId>` so the same DE on different distros
     * (Alpine xfce4 vs Debian xfce4) keeps independent ports.
     */
    fun getPortPreference(distroId: String, deId: String): Int {
        return portPrefs.getInt("${distroId}_${deId}", 0)
    }

    fun setPortPreference(distroId: String, deId: String, port: Int) {
        portPrefs.edit().putInt("${distroId}_${deId}", port).apply()
    }

    /**
     * Suggest the next free VNC port for a new install. Considers ports
     * currently in use by running DEs and ports already pinned by other
     * installed DEs' preferences — across ALL pref keys, not just
     * [distroId]'s: the UML guest desktops (UmlDesktopManager, keys
     * `uml_<kind>`) and imported-distro DEs share this one loopback port
     * space, so a suggestion blind to them collides. [distroId] param kept
     * for call-site compatibility with the per-distro UI. Returns the first
     * unused 5900+N; defaults to 5901 when nothing is in play.
     */
    fun suggestNextVncPort(distroId: String): Int {
        val takenByRunning = _desktops.value.values.map { it.vncPort }.toSet()
        val taken = takenByRunning + allPinnedPorts()
        var port = 5901
        while (port in taken) port++
        return port
    }

    /**
     * Start a desktop environment.
     * X11/VNC desktops use software rendering (no virgl).
     * Native Wayland uses the JNI compositor + virgl for GPU acceleration.
     */
    fun startDesktop(
        de: ProotManager.DesktopEnvironment,
        shellCommand: String = "/bin/sh -l",
    ) {
        // Stop any existing instance first (handles stale state from crashes)
        if (_desktops.value.containsKey(de)) {
            stopDesktop(de)
        }

        when (de.spec.launch) {
            is LaunchSpec.NativeCompositor -> {
                if (!WaylandBridge.available) {
                    // liblabwc_android.so is built for arm64 only, so it is
                    // absent on other devices — but since #510 it is also
                    // absent from the terminal build on arm64. Telling an
                    // arm64 user their device is unsupported would send them
                    // looking for the wrong thing entirely, so distinguish
                    // the two rather than reuse one message for both.
                    val arm64 = android.os.Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
                    _desktops.update { it + (de to DesktopInstance(
                        de, 0, 0, DesktopState.ERROR,
                        errorMessage = if (arm64) {
                            "This build of Haven doesn't include the native Wayland desktop. " +
                                "Desktops that run over VNC still work."
                        } else {
                            "The native Wayland desktop requires an arm64 device"
                        },
                    )) }
                    return
                }
                if (WaylandBridge.nativeIsRunning()) {
                    _desktops.update { it + (de to DesktopInstance(
                        de, 0, 0, DesktopState.ERROR,
                        errorMessage = "Native compositor already running",
                    )) }
                    return
                }
                startNativeCompositor(de, shellCommand)
                return
            }
            is LaunchSpec.NestedWayland -> {
                // Phase 4: launch is structurally identical to X11Vnc up to
                // the per-DE process-spawn step. Allocate display + port
                // through the same code, then call launchNestedWayland().
                // Fall through to the shared X11/Nested branch below; the
                // per-DE process spawner is dispatched on launch type.
                //
                // Migrate a superseded Haven config first (e.g. the
                // fuzzel→foot autostart swap, #162) so existing installs
                // with a frozen write-if-absent config pick up the fix on
                // the next start rather than only on a fresh install.
                prootManager.migrateDesktopConfigs(de)
            }
            is LaunchSpec.X11Vnc -> {
                // Falls through to the X11/VNC launch below.
            }
        }

        // Honour the user's per-DE port preference (set at install time
        // in the Manage view) when present. The preference is stored as
        // an absolute port (5900-base); display = port - 5900 maps it
        // back into the X11 display-number space the Xvnc command line
        // expects. Falls back to allocateDisplay when unset OR when the
        // preferred display is already in use (multiple DEs pinned to
        // the same port — Manage view should prevent this but defend).
        val preferredPort = getPortPreference(prootManager.activeDistroId, de.spec.id)
        val display: Int
        val port: Int
        if (preferredPort in 5901..5999) {
            val candidateDisplay = preferredPort - 5900
            // Own pref excluded from the pinned scan (this IS the pref);
            // another family's pin — e.g. a UML desktop — still blocks.
            val pinnedElsewhere = allPinnedPorts(
                excludeKey = "${prootManager.activeDistroId}_${de.spec.id}",
            ) + usedDisplays.map { 5900 + it }
            if (preferredPort !in pinnedElsewhere) {
                display = candidateDisplay
                port = preferredPort
                usedDisplays.add(display)
            } else {
                Log.w(TAG, "preferred port $preferredPort for ${de.spec.id} already in use; falling back")
                display = allocateDisplay()
                port = 5900 + display
            }
        } else {
            display = allocateDisplay()
            port = 5900 + display
        }
        _desktops.update { it + (de to DesktopInstance(de, display, port, DesktopState.STARTING)) }
        synchronized(logTails) { logTails[de] = ArrayDeque() }

        try {
            val process = when (val spec = de.spec.launch) {
                is LaunchSpec.NestedWayland -> launchNestedWayland(spec.compositorCmd, de.label, display, port)
                is LaunchSpec.X11Vnc -> launchX11Desktop(de, display, shellCommand)
                is LaunchSpec.NativeCompositor ->
                    error("NativeCompositor handled above; unreachable")
            }
            processes[de] = process
            val launchedAt = System.currentTimeMillis()
            // Stays at STARTING — flips to RUNNING via `markRunning(de)`
            // once the caller (DesktopViewModel.startDesktop) confirms the
            // VNC port is listening. If the proot script exits before
            // that signal arrives, the exit thread transitions to ERROR
            // with the log tail attached, so the UI gets a real diagnosis
            // instead of an 8-second silent timeout (#162 bug B).

            // Log output on a background thread, also tee'd into the
            // rolling tail buffer so the exit handler can attach context.
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "${de.label}[:$display]: $line")
                        synchronized(logTails) {
                            val tail = logTails[de] ?: return@synchronized
                            tail.addLast(line)
                            while (tail.size > logTailLimit) tail.removeFirst()
                        }
                    }
                } catch (_: Exception) {}
                val exitCode = process.waitFor()
                Log.d(TAG, "${de.label}[:$display] exited: $exitCode")
                _desktops.update { current ->
                    val instance = current[de] ?: return@update current
                    // #550: an exit shortly after the port check flipped us to
                    // RUNNING is still a startup failure — the VNC server was
                    // up but the session command died (the `sleep 3` in the
                    // launch script means the port poll always wins that
                    // race). Removing the row silently here is exactly the
                    // appear-then-vanish the reporter filmed.
                    val earlyDeath = instance.state == DesktopState.RUNNING &&
                        System.currentTimeMillis() - launchedAt < EARLY_DEATH_WINDOW_MS
                    when {
                        instance.state == DesktopState.RUNNING && !earlyDeath -> {
                            releaseDisplay(display)
                            processes.remove(de)
                            synchronized(logTails) { logTails.remove(de) }
                            current - de
                        }
                        instance.state == DesktopState.STARTING || earlyDeath -> {
                            releaseDisplay(display)
                            processes.remove(de)
                            val tail = synchronized(logTails) {
                                logTails.remove(de)?.toList().orEmpty()
                            }
                            val message = buildString {
                                append("${de.label} exited during startup (code $exitCode)")
                                if (tail.isNotEmpty()) {
                                    append(":\n")
                                    append(tail.takeLast(8).joinToString("\n"))
                                }
                            }
                            current + (de to DesktopInstance(
                                de, display, port, DesktopState.ERROR, errorMessage = message,
                            ))
                        }
                        else -> current
                    }
                }
            }, "desktop-${de.name}-log").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ${de.label}", e)
            releaseDisplay(display)
            synchronized(logTails) { logTails.remove(de) }
            _desktops.update { it + (de to DesktopInstance(
                de, display, port, DesktopState.ERROR,
                errorMessage = e.message,
            )) }
        }
    }

    /**
     * Caller (DesktopViewModel) signals that the desktop has reached a
     * usable state — VNC port listening for X11Vnc/NestedWayland, or
     * the native compositor reporting alive. Flips state from STARTING
     * to RUNNING. No-op if the entry is no longer STARTING (e.g. the
     * exit thread already transitioned it to ERROR because the script
     * crashed in the same window).
     */
    fun markRunning(de: ProotManager.DesktopEnvironment) {
        _desktops.update { current ->
            val instance = current[de] ?: return@update current
            if (instance.state == DesktopState.STARTING) {
                current + (de to instance.copy(state = DesktopState.RUNNING))
            } else current
        }
    }

    /**
     * Runtime log of a nested-Wayland desktop's compositor — the contents
     * of the compositor.log that [launchNestedWayland] redirects the
     * sway/Hyprland/niri process's stdout+stderr into
     * (`$XDG_RUNTIME_DIR/compositor.log`, where XDG_RUNTIME_DIR is
     * `/tmp/xdg-runtime-<display>` and /tmp is bound to the app cacheDir).
     * Returns null for non-nested-Wayland DEs, or when the instance/log
     * isn't present. Exposed for the read_desktop_log MCP endpoint so
     * grey-screen / no-frames diagnosis doesn't need a proot shell.
     */
    fun compositorLogFor(de: ProotManager.DesktopEnvironment): String? {
        if (de.spec.launch !is LaunchSpec.NestedWayland) return null
        val display = _desktops.value[de]?.displayNumber ?: return null
        val logFile = File(context.cacheDir, "xdg-runtime-$display/compositor.log")
        return if (logFile.exists()) logFile.readText() else null
    }

    /**
     * Rolling tail (last [logTailLimit] lines) of the launch process's
     * own stdout/stderr — the `[haven]` progress markers, wayvnc output,
     * and any pre-compositor errors. Captured live in [startDesktop]'s
     * reader thread; cleared when the process exits cleanly.
     */
    fun capturedOutputFor(de: ProotManager.DesktopEnvironment): List<String> =
        synchronized(logTails) { logTails[de]?.toList().orEmpty() }

    /**
     * Stop a running desktop environment.
     */
    fun stopDesktop(de: ProotManager.DesktopEnvironment) {
        val instance = _desktops.value[de] ?: return
        val launch = de.spec.launch
        if (launch is LaunchSpec.NativeCompositor && WaylandBridge.available) {
            if (WaylandBridge.nativeIsRunning()) {
                WaylandBridge.nativeStop()
            }
            WaylandBridge.nativeStopVirglServer()
            WaylandSocketHelper.tryRemoveSymlink()
        }
        // #551: the row-removal is in `finally` — Stop must ALWAYS clear the
        // session entry. If any sweep below throws, a thrown-past removal
        // left a permanent zombie row whose Stop button repeated the same
        // throw forever; only force-killing the app cleared it.
        try {
            processes[de]?.destroyForcibly()
            processes.remove(de)
            if (launch !is LaunchSpec.NativeCompositor) {
                // destroyForcibly() only kills the proot launch shell —
                // children (Xvnc / sway / Hyprland / niri / wayvnc / foot)
                // become orphans reparented to the app, kept running until
                // they exit on their own. Sweep them explicitly so a
                // subsequent start_desktop doesn't hit "Another wayvnc
                // process is already running" on the control socket or a
                // wedged port 590x.
                if (launch is LaunchSpec.X11Vnc) {
                    killOrphanedXvnc(instance.displayNumber)
                } else if (launch is LaunchSpec.NestedWayland) {
                    killOrphanedNestedWayland(launch.compositorCmd)
                }
                releaseDisplay(instance.displayNumber)
                cleanupDisplayRuntime(instance.displayNumber)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopDesktop sweep failed for ${de.label} (row cleared anyway)", e)
        } finally {
            _desktops.update { it - de }
        }
    }

    /**
     * Remove a finished desktop's per-display runtime debris from the app
     * cache (the host side of the guest `/tmp` bind): the XDG_RUNTIME_DIR
     * `xdg-runtime-<display>` with its accumulating `wayland-N` +
     * `sway-ipc.*.sock` — wlroots compositors never unlink their IPC socket on
     * exit, so without this they pile up (169 stale sockets observed across
     * repeated start/stop) — plus the Xwayland socket and X lock. Call only
     * once the compositor and its orphans for [display] are dead, so we never
     * unlink a live socket directory.
     */
    private fun cleanupDisplayRuntime(display: Int) {
        val xdg = File(context.cacheDir, "xdg-runtime-$display")
        if (xdg.exists()) forceDeleteRecursively(xdg)
        File(context.cacheDir, ".X11-unix/X$display").delete()
        File(context.cacheDir, ".X$display-lock").delete()
    }

    /**
     * Stop all running desktops.
     */
    fun stopAll() {
        _desktops.value.keys.toList().forEach { stopDesktop(it) }
    }

    /**
     * Get the VNC port allocated for a desktop. Returns the port for
     * both STARTING and RUNNING entries — callers polling the socket
     * to confirm readiness (DesktopViewModel.startDesktop) need to know
     * the target port before the state has flipped to RUNNING. Returns
     * null for STOPPED / ERROR / missing entries.
     */
    fun getVncPort(de: ProotManager.DesktopEnvironment): Int? =
        _desktops.value[de]?.takeIf {
            it.state == DesktopState.RUNNING || it.state == DesktopState.STARTING
        }?.vncPort

    // ---- Agent capture / window enumeration (X11/VNC desktops) ----

    /** A visible top-level X11 window on a running desktop. */
    data class WindowInfo(
        val id: String,
        val title: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Resolve a running X11/VNC desktop's display number, throwing a
     * caller-friendly message otherwise. Capture and enumeration only
     * make sense for X11Vnc desktops driven by Xvnc — nested-Wayland
     * desktops render through wayvnc and have no X display to talk to.
     */
    private fun requireX11Display(de: ProotManager.DesktopEnvironment): Int {
        val instance = _desktops.value[de]
            ?: throw IllegalStateException("Desktop '${de.spec.id}' is not running")
        if (de.spec.launch !is LaunchSpec.X11Vnc) {
            throw IllegalStateException(
                "Capture/enumeration is only supported for X11/VNC desktops; " +
                    "'${de.spec.id}' is ${de.spec.launch::class.simpleName}",
            )
        }
        return instance.displayNumber
    }

    /**
     * Resolve the client environment a process needs to talk to a RUNNING
     * desktop session — DISPLAY / WAYLAND_DISPLAY / XDG_RUNTIME_DIR — so a
     * local shell (or any guest process) can drive the desktop's apps from
     * the command line (#285). The desktop's sockets live under the shared
     * app cacheDir (bound at /tmp in every proot launch), so a plain local
     * shell already SEES them; it just needs these vars pointed at the right
     * socket. Wayland socket names aren't a fixed number, so they're
     * discovered now via a one-shot `ls` in the active proot.
     *
     * Returns concrete KEY→VALUE env. Throws if [de] is not running.
     */
    suspend fun resolveClientEnv(de: ProotManager.DesktopEnvironment): Map<String, String> {
        val instance = _desktops.value[de]
            ?: throw IllegalStateException("Desktop '${de.spec.id}' is not running")
        return when (de.spec.launch) {
            is LaunchSpec.X11Vnc -> mapOf(
                "DISPLAY" to ":${instance.displayNumber}",
                "XAUTHORITY" to "/root/.Xauthority",
            )
            is LaunchSpec.NestedWayland -> {
                val xdg = "/tmp/xdg-runtime-${instance.displayNumber}"
                val sock = resolveSock(xdg, Regex("^wayland-[0-9]+$"))
                    ?: throw IllegalStateException(
                        "No wayland socket under $xdg — is '${de.spec.id}' fully up?",
                    )
                mapOf("XDG_RUNTIME_DIR" to xdg, "WAYLAND_DISPLAY" to sock)
            }
            is LaunchSpec.NativeCompositor -> {
                // The native compositor binds cacheDir/wayland-xdg at
                // /tmp/xdg-runtime inside ITS OWN proot; a shell that only binds
                // cacheDir→/tmp sees the same dir at /tmp/wayland-xdg. The Wayland
                // socket is the fixed wayland-0. For X11 apps, DISPLAY is whatever
                // X socket labwc's Xwayland created under the shared /tmp/.X11-unix.
                val env = mutableMapOf(
                    "XDG_RUNTIME_DIR" to "/tmp/wayland-xdg",
                    "WAYLAND_DISPLAY" to "wayland-0",
                )
                resolveSock("/tmp/.X11-unix", Regex("^X[0-9]+$"))?.let {
                    env["DISPLAY"] = ":" + it.removePrefix("X")
                }
                env
            }
        }
    }

    /**
     * Discover a socket filename under [dir] (inside the active proot)
     * matching [pattern], via a one-shot `ls`. Filters to trimmed lines that
     * match so any proot-warning prefix on stdout is ignored. Null if none.
     */
    private suspend fun resolveSock(dir: String, pattern: Regex): String? {
        val (out, _) = prootManager.runCommandInProot("ls $dir 2>/dev/null")
        return out.lineSequence()
            .map { it.trim() }
            .firstOrNull { pattern.matches(it) }
    }

    /**
     * Enumerate visible top-level windows on a running X11 desktop using
     * `xdotool`. Each window is emitted as a single pipe-delimited line
     * (window names have '|' and newlines squashed to spaces) so the
     * output parses unambiguously. Installs the capture toolset on first
     * use via [ProotManager.ensureCaptureTools].
     */
    suspend fun listWindows(de: ProotManager.DesktopEnvironment): List<WindowInfo> =
        when (de.spec.launch) {
            is LaunchSpec.X11Vnc -> listWindowsX11(de)
            is LaunchSpec.NestedWayland -> listWindowsWayland(de)
            else -> throw IllegalStateException(
                "Window enumeration is supported for X11/VNC and Sway desktops; " +
                    "'${de.spec.id}' is ${de.spec.launch::class.simpleName}",
            )
        }

    private suspend fun listWindowsX11(de: ProotManager.DesktopEnvironment): List<WindowInfo> {
        val display = requireX11Display(de)
        val (ready, detail) = prootManager.ensureCaptureTools()
        if (!ready) throw IllegalStateException(detail)
        // For each visible named window: eval the --shell geometry (sets
        // X/Y/WIDTH/HEIGHT), squash the name to one field, emit one line.
        val script = buildString {
            append("export DISPLAY=:$display; export XAUTHORITY=/root/.Xauthority; ")
            append("for id in \$(xdotool search --onlyvisible --name '.*' 2>/dev/null); do ")
            append("g=\$(xdotool getwindowgeometry --shell \$id 2>/dev/null) || continue; ")
            append("eval \"\$g\"; ")
            append("n=\$(xdotool getwindowname \$id 2>/dev/null | tr '|\\n' '  '); ")
            append("echo \"WIN|\$id|\$X|\$Y|\$WIDTH|\$HEIGHT|\$n\"; ")
            append("done")
        }
        val (out, _) = prootManager.runCommandInProot(script)
        return out.lineSequence()
            .filter { it.startsWith("WIN|") }
            .mapNotNull { line ->
                val p = line.split("|")
                if (p.size < 7) return@mapNotNull null
                val x = p[2].toIntOrNull() ?: return@mapNotNull null
                val y = p[3].toIntOrNull() ?: return@mapNotNull null
                val w = p[4].toIntOrNull() ?: return@mapNotNull null
                val h = p[5].toIntOrNull() ?: return@mapNotNull null
                WindowInfo(
                    id = p[1],
                    title = p.drop(6).joinToString("|").trim(),
                    x = x, y = y, width = w, height = h,
                )
            }
            .toList()
    }

    /**
     * Enumerate windows on a running nested-Wayland desktop. There's no
     * portable wlroots window list, so this is compositor-specific: Sway
     * exposes its layout tree over `swaymsg -t get_tree` (shipped with the
     * sway package), which we walk for leaf view nodes. Other nested-Wayland
     * compositors (Hyprland / niri / cage) aren't wired up yet — for those,
     * use capture_desktop (whole output) which needs no enumeration. The
     * returned id is the Sway container id and the rect is in output pixels
     * (1x scale on the headless backend), so it doubles as a crop rect for
     * capture_desktop.
     */
    private suspend fun listWindowsWayland(de: ProotManager.DesktopEnvironment): List<WindowInfo> {
        val launch = de.spec.launch as? LaunchSpec.NestedWayland
            ?: throw IllegalStateException("'${de.spec.id}' is not a nested-Wayland desktop")
        val compositor = launch.compositorCmd.substringBefore(' ').substringAfterLast('/')
        if (compositor != "sway") {
            throw IllegalStateException(
                "Window enumeration for '$compositor' isn't supported yet (Sway is); " +
                    "use capture_desktop for a whole-output screenshot.",
            )
        }
        val instance = _desktops.value[de]
            ?: throw IllegalStateException("Desktop '${de.spec.id}' is not running")
        val xdg = "/tmp/xdg-runtime-${instance.displayNumber}"
        val script = buildString {
            append("export XDG_RUNTIME_DIR=$xdg; ")
            append("WL=\$(ls $xdg 2>/dev/null | grep -E '^wayland-[0-9]+\$' | head -1); ")
            append("export WAYLAND_DISPLAY=\$WL; ")
            append("swaymsg -t get_tree -r 2>/dev/null")
        }
        val (out, _) = prootManager.runCommandInProot(script)
        // Strip any proot warning prefix before the JSON object.
        val start = out.indexOf('{')
        if (start < 0) return emptyList()
        val views = mutableListOf<WindowInfo>()
        fun walk(node: org.json.JSONObject) {
            val nodes = node.optJSONArray("nodes")
            val floating = node.optJSONArray("floating_nodes")
            val childCount = (nodes?.length() ?: 0) + (floating?.length() ?: 0)
            // A view leaf has a pid and no child containers.
            val isView = !node.isNull("pid") && childCount == 0 &&
                (node.optString("app_id").isNotEmpty() || node.optString("name").isNotEmpty())
            if (isView) {
                val rect = node.optJSONObject("rect")
                views.add(
                    WindowInfo(
                        id = node.optInt("id").toString(),
                        title = node.optString("name").ifEmpty { node.optString("app_id") },
                        x = rect?.optInt("x") ?: 0,
                        y = rect?.optInt("y") ?: 0,
                        width = rect?.optInt("width") ?: 0,
                        height = rect?.optInt("height") ?: 0,
                    ),
                )
            }
            nodes?.let { for (i in 0 until it.length()) walk(it.getJSONObject(i)) }
            floating?.let { for (i in 0 until it.length()) walk(it.getJSONObject(i)) }
        }
        try {
            walk(org.json.JSONObject(out.substring(start)))
        } catch (e: Exception) {
            Log.w(TAG, "swaymsg tree parse failed: ${e.message}")
        }
        return views
    }

    /**
     * Capture the whole root window of a running X11 desktop as PNG bytes
     * via ImageMagick's `import`. Window-level cropping is done by the
     * caller (which has the window geometry from [listWindows]) so this
     * stays a single, reliable code path — `import -window root` always
     * works on bare Xvnc, whereas per-window-id capture is finicky.
     *
     * The PNG is written to `/tmp` (bound to the app cacheDir), read back
     * directly off the app's filesystem, then deleted.
     */
    suspend fun capture(de: ProotManager.DesktopEnvironment): ByteArray =
        when (de.spec.launch) {
            is LaunchSpec.X11Vnc -> captureX11(de)
            is LaunchSpec.NestedWayland -> captureNestedWayland(de)
            else -> throw IllegalStateException(
                "Capture is supported for X11/VNC and nested-Wayland desktops; " +
                    "'${de.spec.id}' is ${de.spec.launch::class.simpleName}",
            )
        }

    private suspend fun captureX11(de: ProotManager.DesktopEnvironment): ByteArray {
        val display = requireX11Display(de)
        val (ready, detail) = prootManager.ensureCaptureTools()
        if (!ready) throw IllegalStateException(detail)
        val fname = "haven-cap-${System.currentTimeMillis()}.png"
        val script =
            "export DISPLAY=:$display; export XAUTHORITY=/root/.Xauthority; " +
                "rm -f /tmp/$fname; " +
                "import -window root +repage /tmp/$fname > /tmp/$fname.log 2>&1; echo EXIT:\$?"
        return runCaptureScript(script, fname, tool = "import")
    }

    /**
     * Capture a running nested-Wayland desktop (Sway / Hyprland / niri / cage)
     * as PNG bytes via `grim`. grim runs as a fresh wayland client — without
     * the wayvnc shim — so it uses the wlr-screencopy path the wlroots
     * headless backend actually supports (the ext-image-copy path the shim
     * blocks reports zero buffer formats, #246). It connects to the
     * compositor's socket under `XDG_RUNTIME_DIR=/tmp/xdg-runtime-<display>`,
     * which is reachable here because runCommandInProot binds the same
     * cacheDir at /tmp as the compositor launch.
     */
    private suspend fun captureNestedWayland(de: ProotManager.DesktopEnvironment): ByteArray {
        val instance = _desktops.value[de]
            ?: throw IllegalStateException("Desktop '${de.spec.id}' is not running")
        val (ready, detail) = prootManager.ensureWaylandCaptureTools()
        if (!ready) throw IllegalStateException(detail)
        val xdg = "/tmp/xdg-runtime-${instance.displayNumber}"
        val fname = "haven-cap-${System.currentTimeMillis()}.png"
        val script = buildString {
            append("export XDG_RUNTIME_DIR=$xdg; ")
            // The socket number isn't fixed (cage→wayland-0, Sway→wayland-1),
            // so resolve whichever the compositor created, same as launch.
            append("WL=\$(ls $xdg 2>/dev/null | grep -E '^wayland-[0-9]+\$' | head -1); ")
            append("if [ -z \"\$WL\" ]; then echo 'no wayland socket in '$xdg; exit 1; fi; ")
            append("export WAYLAND_DISPLAY=\$WL; ")
            append("rm -f /tmp/$fname; ")
            append("grim /tmp/$fname > /tmp/$fname.log 2>&1; echo EXIT:\$?")
        }
        return runCaptureScript(script, fname, tool = "grim")
    }

    /** Shared tail of the capture paths: run the script, read the PNG written
     *  to /tmp (bound to cacheDir), surface the tool log on failure, clean up. */
    private suspend fun runCaptureScript(script: String, fname: String, tool: String): ByteArray {
        val (out, _) = prootManager.runCommandInProot(script)
        val file = File(context.cacheDir, fname)
        val logFile = File(context.cacheDir, "$fname.log")
        try {
            if (!file.exists() || file.length() == 0L) {
                val log = if (logFile.exists()) logFile.readText() else ""
                throw IllegalStateException(
                    "Capture produced no image ($tool: ${log.take(400).trim()}; $out)",
                )
            }
            return file.readBytes()
        } finally {
            file.delete()
            logFile.delete()
        }
    }

    // Apps launched into a running desktop via launch_app_in_desktop,
    // keyed by an opaque appId. We hold the Process so its reader thread
    // keeps draining the combined stdout+stderr (so a chatty GL app never
    // blocks on a full pipe); proot stays alive for the app's lifetime
    // because the command runs in the foreground (exec), so --kill-on-exit
    // doesn't reap the GUI app.
    private val appProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()

    /**
     * Launch a GUI [command] into a running desktop [de] with the right
     * display env and the software-GL fallback exported, so GPU-less GL apps
     * (KiCad/eeschema) don't crash their canvas. Returns an opaque appId.
     * Fire-and-forget: callers poll [listWindows] to learn when the window
     * appears (X11 and Sway support enumeration; other nested-Wayland
     * compositors don't, so the caller just won't get a windowId back).
     *
     * X11Vnc desktops get DISPLAY/XAUTHORITY; nested-Wayland desktops get
     * XDG_RUNTIME_DIR + WAYLAND_DISPLAY (resolving the compositor's
     * `wayland-N` socket, which isn't a fixed number). Throws if [de] is not
     * a running X11/VNC or nested-Wayland desktop.
     */
    fun launchApp(
        de: ProotManager.DesktopEnvironment,
        command: String,
        gpu: Boolean = false,
    ): String {
        val instance = _desktops.value[de]
            ?: throw IllegalStateException("Desktop '${de.spec.id}' is not running")
        // Default: force software GL so GPU-less GL apps (KiCad/eeschema) don't
        // crash their canvas. gpu=true instead routes through the venus/virpipe
        // passthrough env (the gl_smoke_test path) so we can exercise the real
        // GPU present pipeline rather than llvmpipe.
        val glEnv = if (gpu) {
            "export HOME=/root; " + gpuPassthroughEnv("; ")
        } else {
            "export HOME=/root; export LIBGL_ALWAYS_SOFTWARE=1; export GALLIUM_DRIVER=llvmpipe; "
        }
        val displayLabel: String
        val launchCmd: String
        when (de.spec.launch) {
            is LaunchSpec.X11Vnc -> {
                val display = requireX11Display(de)
                displayLabel = ":$display"
                launchCmd =
                    "export DISPLAY=:$display; export XAUTHORITY=/root/.Xauthority; $glEnv exec $command"
            }
            is LaunchSpec.NestedWayland -> {
                val xdg = "/tmp/xdg-runtime-${instance.displayNumber}"
                displayLabel = "wayland@$xdg"
                launchCmd = "export XDG_RUNTIME_DIR=$xdg; " +
                    "WL=\$(ls $xdg 2>/dev/null | grep -E '^wayland-[0-9]+\$' | head -1); " +
                    "export WAYLAND_DISPLAY=\$WL; $glEnv exec $command"
            }
            else -> throw IllegalStateException(
                "launch_app_in_desktop supports X11/VNC and nested-Wayland desktops; " +
                    "'${de.spec.id}' is ${de.spec.launch::class.simpleName}",
            )
        }
        val appId = "deapp-${System.currentTimeMillis()}"
        val proc = prootManager.startCommandInProot(launchCmd)
        appProcesses[appId] = proc
        Thread({
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "$appId[$displayLabel]: $line")
                }
            } catch (_: Exception) {
            } finally {
                appProcesses.remove(appId)
            }
        }, appId).apply { isDaemon = true }.start()
        return appId
    }

    // ---- X11/VNC desktop launch (software rendering, no virgl) ----

    /**
     * `/usr/bin/env -i` prefix giving guest desktop sessions a clean,
     * guest-semantic environment. ProcessBuilder.environment() inherits the
     * whole Android process env, and proot copies the outer env into the
     * tracee — so every desktop session carried BOOTCLASSPATH, ANDROID_*,
     * zygote sockets etc. and no LANG (#373; 13 leaked vars measured on
     * device). Resetting inside the guest matches the terminal path
     * (LocalSessionManager env array) and the one-shot path
     * (ProotManager.runCommandInProot's `env -i`). Launch scripts re-export
     * their own DISPLAY/XDG_RUNTIME_DIR/etc. on top of this base.
     */
    private fun cleanGuestEnv(): List<String> {
        val locale = runBlocking { userPreferencesRepository.terminalLocale.first() }
        return listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=$locale",
            "LC_ALL=$locale",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "SHELL=/bin/sh",
            "TMPDIR=/tmp",
            "XDG_RUNTIME_DIR=/tmp",
            "XDG_DATA_HOME=/root/.local/share",
            "XDG_DATA_DIRS=/usr/local/share:/usr/share",
            "XDG_CONFIG_HOME=/root/.config",
            "XDG_CACHE_HOME=/root/.cache",
        )
    }

    private fun launchX11Desktop(
        de: ProotManager.DesktopEnvironment,
        display: Int,
        shellCommand: String,
    ): Process {
        val prootBin = prootManager.prootBinary
            ?: throw IllegalStateException("PRoot not available")
        val loaderPath = File(
            context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
        ).absolutePath
        val rootfsDir = prootManager.activeRootfsDir
        val rootHome = File(rootfsDir, "root").apply { mkdirs() }

        val launch = de.spec.launch as LaunchSpec.X11Vnc

        // #361: the Custom DE's startCommands placeholder is empty — the real
        // session command is the user's preference, read here at launch so
        // edits apply on the next start without reinstalling. `; ` (not the
        // catalog entries' trailing `&`) separates it from the shell's `wait`,
        // so a blocking command like `dbus-launch startxfce4` doesn't swallow
        // `wait` as an argument. Blank = misconfigured: fail loudly into the
        // DE row's error state rather than render an empty desktop.
        val startCommands = if (de == ProotManager.DesktopEnvironment.CUSTOM_X11) {
            val cmd = runBlocking { userPreferencesRepository.customDesktopCommand.first() }
            if (cmd.isBlank()) {
                throw IllegalStateException("No custom desktop command set — configure it in the Desktops view")
            }
            // #550/#551: the user's command IS the session. Without the kill,
            // a command that dies instantly (bad flags, broken guest libs)
            // leaves Xvnc holding `wait` — the launch shell never exits, the
            // VNC port checks out, and the row turns RUNNING over a corpse
            // that Stop-then-restart is the only way out of. Killing Xvnc on
            // command exit makes the shell exit, and the exit thread does the
            // right thing: ERROR with the command's own output during
            // startup, a clean row removal later.
            "$cmd ; kill \$XVNC_PID 2>/dev/null ; "
        } else {
            launch.startCommands
        }

        // Clean lock files for this display
        File(context.cacheDir, ".X${display}-lock").delete()
        File(rootHome, ".ICEauthority").apply { if (!exists()) createNewFile() }
        File(rootHome, ".Xauthority").apply { if (!exists()) createNewFile() }

        val passwdFile = File(rootfsDir, "root/.vnc/passwd")
        val useAuth = passwdFile.exists() && passwdFile.length() >= 8
        val securityArg = if (useAuth) {
            "-SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd"
        } else {
            "-SecurityTypes None"
        }
        Log.d(TAG, "Starting Xvnc :$display: useAuth=$useAuth")

        val shellCmd =
            // `;` not `&&` before the backgrounded Xvnc: with `&&` the `&`
            // backgrounds the whole `rm && Xvnc` list as a subshell, so `\$!`
            // is the SUBSHELL's pid — the custom-command kill then reaped the
            // subshell, orphaned Xvnc, and proot (alive while any tracee
            // lives) never exited: the RUNNING-forever zombie of #550/#551.
            "rm -f /tmp/.X${display}-lock /tmp/.X11-unix/X${display} ; " +
                "Xvnc :${display} -geometry 1280x720 " +
                "$securityArg " +
                "-BlacklistThreshold 10000 " +
                "-localhost 0 & " +
                // The Custom-X11 tail kills this PID when the session command
                // exits (#550/#551); catalog DEs never reference it.
                "XVNC_PID=\$! ; " +
                "sleep 3; " +
                "export DISPLAY=:${display}; " +
                "export HOME=/root; " +
                // #370: establish a session dbus bus so desktop components that
                // need one (Xfce4's settings/session daemons, and whatever the
                // Custom-X11 command launches) find DBUS_SESSION_BUS_ADDRESS
                // instead of failing to start. Best-effort: `command -v` guards
                // desktops whose package set has no dbus-launch (e.g. Openbox,
                // which doesn't need one), so this never breaks their launch.
                // Mirrors the native-Wayland path's dbus-run-session; here the
                // exports are inherited by the backgrounded WM/panel children.
                // --exit-with-x11: the dbus-daemon dies when the X server does, so a
                // force-stopped session (SIGKILL on proot bypasses
                // --kill-on-exit) can't leak one dbus-daemon per stop (#551).
                "{ command -v dbus-launch >/dev/null 2>&1 && eval \"\$(dbus-launch --sh-syntax --exit-with-x11)\"; } || true; " +
                "[ -f /etc/profile.d/pulse.sh ] && . /etc/profile.d/pulse.sh; " +
                // NO virgl — software rendering for VNC desktops. Force the
                // Mesa software rasteriser so GPU-less GL apps (KiCad/eeschema)
                // don't crash their OpenGL canvas, and paint a solid root so a
                // never-drawn desktop isn't a pure-black capture. Both
                // best-effort — never fail the launch if the tool is absent.
                "export LIBGL_ALWAYS_SOFTWARE=1; export GALLIUM_DRIVER=llvmpipe; " +
                "xsetroot -solid '#202830' 2>/dev/null || true; " +
                "$startCommands " +
                "wait"

        val prootArgs = mutableListOf(
            prootBin, "-0", "--link2symlink", "--sysvipc",
            // #550/#551: when the launch script exits, kill every remaining
            // tracee. Without this, the dbus-daemon that `dbus-launch` forks
            // outlives the session, proot stays alive tracing it, the host
            // Process never exits — and the exit thread that would surface
            // the failure (or clear the row) never runs. Also stops each
            // desktop start from leaking one dbus-daemon forever.
            "--kill-on-exit",
            // #325: foreign-arch rootfs runs through the bundled qemu-user loader.
            *prootManager.qemuUserArgs(prootManager.activeDistroId).toTypedArray(),
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            // Mask /sys/fs/selinux (Android enforces SELinux; exposing enforce=1
            // breaks guest cp -Z/restorecon in package postinst scripts, #283).
            "-b", prootManager.selinuxMaskBind(rootfsDir),
            // Writable /dev/shm (Android /dev is read-only, no shm) so GL/Mono
            // desktop apps that use POSIX shared memory work. See ProotManager.
            "-b", "${prootManager.ensureDevShm()}:/dev/shm",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
            // #304: surface the device model at the devicetree path fastfetch reads.
            *(prootManager.deviceModelDevicetreeBind()?.let { arrayOf("-b", it) } ?: emptyArray()),
            // #304 part 2: optionally expose Android's system partitions (opt-in).
            *prootManager.androidSystemBindArgs(longForm = false),
        )
        // #300: remap privileged binds (<1024) up by +2000 when opted in.
        if (prootManager.remapLowPorts) prootArgs.add("-p")
        // #420: expose the user's shared storage (/storage, /sdcard) — same binds
        // the interactive shell uses, gated on the same opt-out. Without this the
        // desktop file manager saw an empty /sdcard.
        prootArgs.addAll(prootManager.storageBindShortArgs())
        // #301: per-distro user-defined extra binds.
        prootArgs.addAll(prootManager.customBindShortArgs(prootManager.activeDistroId))
        // /bin/sh works on both Alpine (symlink to busybox) and Debian
        // (symlink to dash). See ProotManager.runCommandInProot.
        prootArgs.addAll(listOf("-w", "/root") + cleanGuestEnv() + listOf("/bin/sh", "-c", shellCmd))

        return ProcessBuilder(prootArgs).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                // Pin TMPDIR to the cacheDir-backed /tmp bind; else the desktop
                // session (and terminals opened from it) inherit the Android
                // process TMPDIR (app cache host path), unreachable inside proot
                // → mktemp fails in desktop-environment terminals (#283).
                put("TMPDIR", "/tmp")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
                put("PROOT_L2S_DIR", prootManager.l2sEnvValue(prootManager.activeDistroId))
            }
            redirectErrorStream(true)
        }.start()
    }

    // ---- Nested Wayland (wlroots headless backend + wayvnc) ----

    /**
     * GPU passthrough env vars for accelerated Wayland/cage GL, shared by the
     * cage and native-compositor launch paths. Default (off) is virgl/virpipe —
     * GL 2.1, but present works on every path. When the experimental
     * `gpu_use_venus` pref is on, route Mesa through venus (guest Vulkan → vtest
     * → host Mali) + zink (modern GL, ~3.2 core), and force Mesa's wl_shm
     * CPU-copy WSI present path (`MESA_VK_WSI_DEBUG=sw`): no Android-EGL wlroots
     * compositor can import dma-bufs, so the GPU device must present over wl_shm.
     * Native Vulkan apps present fully (a visible cube); GL-via-zink renders at
     * GL 3.2 core (glmark2 ~180 fps) once BOTH fence AND semaphore feedback are
     * disabled — over vtest+AHB on Mali the guest never observes the feedback
     * writes and spins "stuck in fence wait" / "stuck in semaphore wait" (the
     * latter is the first-draw blocker fixed here; device-verified 2026-06-16).
     * Note: zink GL is fine OFFSCREEN (FBO/compute — verified by glReadPixels)
     * but a zink GL *window* doesn't present over these compositors. Root-caused
     * (2026-06-16): the cage/native compositors advertise only wl_shm (pixman, no
     * linux-dmabuf), so Mesa's EGL can't route zink+venus onto the kopper
     * (Vulkan-WSI-sw) present that DOES work — it falls back to zink-on-swrast,
     * whose drisw present never copies the rendered pixels into the committed
     * wl_shm buffer (flicker / stale frames). Not a venus or WSI bug: native
     * Vulkan (vkcube) presents fully over the identical path. Windowed GL needs
     * a dmabuf/GPU-capable compositor (deferred Phase C) or an upstream Mesa fix;
     * any guest-Mesa patch is per-distro and non-shippable. The flags are a no-op
     * for virpipe. [sep] is the per-site statement separator (" ; " for the cage
     * builder, "; " for the native buildString).
     */
    private fun gpuPassthroughEnv(sep: String): String =
        if (runBlocking { userPreferencesRepository.gpuUseVenus.first() }) {
            "export VTEST_SOCKET=/tmp/.virgl_test$sep" +
                "export VN_DEBUG=vtest$sep" +
                // Prefer the mesa-venus-fix venus ICD (libvulkan_virtio) if the
                // guest built+cached it: it writes back the guest CPU cache for
                // mapped host-visible memory before each submit so geometry-
                // animating GL (streamed vertex/uniform data) stops flickering
                // over vtest (project_virgl_cage_gpu_accel R6). Else the stock ICD.
                "if [ -f /usr/local/lib/haven/mesa-venus-fix/virtio_icd.json ]; then " +
                "export VK_DRIVER_FILES=/usr/local/lib/haven/mesa-venus-fix/virtio_icd.json; " +
                "else export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/virtio_icd.json; fi$sep" +
                "export GALLIUM_DRIVER=zink$sep" +
                "export MESA_LOADER_DRIVER_OVERRIDE=zink$sep" +
                "export VN_PERF=no_fence_feedback,no_semaphore_feedback$sep" +
                "export MESA_VK_WSI_DEBUG=sw$sep" +
                // If the guest built+cached the patched zink (libgallium) via the
                // mesa-venus-fix build, LD_PRELOAD it so the per-frame UBO is
                // re-issued through the command stream — venus host-visible memory
                // isn't reliably GPU-visible over vtest, so without this the model
                // flickers off-screen ~2/3 of frames (project_virgl_cage_gpu_accel
                // R5). Absent cache => empty `preload` test fails => stock zink.
                // Path mirrors build.sh's PREFIX; see ProotManager.stageMesaVenusFix.
                "if [ -s /usr/local/lib/haven/mesa-venus-fix/preload ]; then " +
                "export LD_PRELOAD=\"\$(cat /usr/local/lib/haven/mesa-venus-fix/preload)\"; fi$sep"
        } else {
            "export GALLIUM_DRIVER=virpipe$sep" +
                "export VTEST_SOCKET=/tmp/.virgl_test$sep" +
                "export VN_PERF=no_fence_feedback$sep"
        }

    /**
     * Launch a wlroots-based compositor (Sway / Hyprland / niri) on
     * the headless backend inside the proot rootfs, exposed to the
     * outside world via `wayvnc` listening on [port]. The in-app VNC
     * client connects to localhost:port through the same path it uses
     * for Xvnc desktops, so the connect-side UI is unchanged.
     *
     * Auth model: this initial Phase 4 launches wayvnc with no
     * password (matches the existing X11 "-SecurityTypes None" fallback
     * when no .vnc/passwd exists). wayvnc's username/password auth
     * landed in 0.7+; supported distro packages range from 0.5 to 0.9,
     * so wiring auth is deferred until the version floor lifts.
     *
     * Singleton-friendly: each running NestedWayland DE gets its own
     * XDG_RUNTIME_DIR under `/tmp/xdg-runtime-<display>`. Multiple
     * nested compositors can run concurrently as long as their
     * displays differ.
     */
    private fun launchNestedWayland(
        compositorCmd: String,
        label: String,
        display: Int,
        port: Int,
    ): Process {
        val prootBin = prootManager.prootBinary
            ?: throw IllegalStateException("PRoot not available")
        val loaderPath = File(
            context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
        ).absolutePath
        val rootfsDir = prootManager.activeRootfsDir
        File(rootfsDir, "root").mkdirs()

        val xdgInProot = "/tmp/xdg-runtime-$display"
        // Clear stale per-display debris from any prior unclean stop (a crash
        // that skipped stopDesktop) before this session repopulates the dir.
        cleanupDisplayRuntime(display)

        // wlroots' SHM allocator (the only buffer path on the headless
        // backend with WLR_RENDERER=pixman) calls POSIX shm_open() which
        // glibc maps to "/dev/shm/<random>". Android's /dev tmpfs has no
        // /dev/shm entry, so every buffer allocation fails:
        //   [wlr] [render/swapchain.c:105] Failed to allocate buffer
        // and the headless output never moves out of OUTPUT_POWER_OFF —
        // wayvnc connects but "Pauses frame capture" and the VNC client
        // sees only a grey rectangle. Bind a writable directory from
        // the app cache as /dev/shm inside the proot.
        val devShmHost = File(context.cacheDir, "dev-shm").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }

        // Wlroots headless setup:
        //  - WLR_BACKENDS=headless: skip DRM/libinput entirely.
        //  - WLR_HEADLESS_OUTPUTS=1: create one virtual output at boot.
        //    Without this, wlroots starts with zero outputs and the
        //    compositor's config-time `output HEADLESS-1 ...` directive
        //    has nothing to apply to.
        //  - WLR_LIBINPUT_NO_DEVICES=1: belt-and-braces for the input
        //    side; headless backend already provides virtual seat.
        //  - XKB_DEFAULT_LAYOUT=us: avoid xkbcommon falling back to
        //    /etc/default/keyboard which proot environments lack.
        //
        // niri uses smithay rather than wlroots and reads only some of
        // the above; passing them is harmless and the niri-specific
        // setup (auto-detect headless when no Wayland/DRM is found)
        // does the rest.
        //
        // Script structure: statements are separated by `;`, NOT `&&`.
        // An `&&`-chain that ends with `& ` (background operator) is
        // parsed by POSIX sh as "background the whole list", which would
        // background the env exports as well — so wayvnc would later run
        // with no XDG_RUNTIME_DIR. The semicolons keep each statement in
        // the foreground; only the compositor itself is backgrounded.
        // GPU broker: point the cage app's Mesa at Haven's virgl_test_server
        // (a surfaceless EGL/GLES2 context on the Android GPU, forked into
        // Haven's own process where libEGL reaches Mali). Idempotent — the JNI
        // tracks a single global server, so this shares the one already used by
        // the native compositor. Gated on the .so being present (only arm64
        // ships it) AND on the labwc JNI lib having loaded — calling the
        // native method without it throws UnsatisfiedLinkError and killed
        // the whole cage/present_app start (#469). Either way the skip is
        // soft: the cage stays on llvmpipe. The compositor itself stays on
        // pixman; only the app's GL is accelerated.
        val virglBin = File(
            context.applicationInfo.nativeLibraryDir, "libvirgl_test_server.so",
        )
        val virglEnv = if (WaylandBridge.available && virglBin.canExecute()) {
            WaylandBridge.nativeStartVirglServer(
                virglBin.absolutePath, File(context.cacheDir, ".virgl_test").absolutePath,
            )
            gpuPassthroughEnv(" ; ")
        } else {
            if (!WaylandBridge.available) {
                Log.w(TAG, "virgl skipped — labwc lib unavailable (${WaylandBridge.loadError}); cage app stays on llvmpipe")
            }
            ""
        }

        val shellCmd = buildString {
            append("set -e ; ")
            // The compositor proot runs as uid 1000 (`-i 1000:1000`, so
            // Sway/Hyprland's anti-root check passes), but no distro rootfs
            // ships a uid-1000 user. getpwuid(1000) then returns NULL,
            // which foot survives ("falling back to 'sh'") but fuzzel
            // dereferenced and segfaulted (#162 grey screen). Ensure a
            // uid-1000 passwd/group entry exists — idempotent (grep-guarded)
            // so it appends once and works for existing installs too. The
            // real Android app uid owns the rootfs, so the guest can write.
            append("grep -q '^haven:' /etc/passwd 2>/dev/null || echo 'haven:x:1000:1000:Haven:/root:/bin/bash' >> /etc/passwd ; ")
            append("grep -q '^haven:' /etc/group 2>/dev/null || echo 'haven:x:1000:' >> /etc/group ; ")
            append("mkdir -p $xdgInProot ; chmod 700 $xdgInProot ; ")
            append("export XDG_RUNTIME_DIR=$xdgInProot ; ")
            append("export HOME=/root ; ")
            append("[ -f /etc/profile.d/pulse.sh ] && . /etc/profile.d/pulse.sh ; ")
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ; ")
            append("export WLR_BACKENDS=headless ; ")
            append("export WLR_HEADLESS_OUTPUTS=1 ; ")
            append("export WLR_LIBINPUT_NO_DEVICES=1 ; ")
            // Proot environments don't have DRM nodes, so the GLES2
            // renderer can't allocate DMA-buf-backed framebuffers and
            // wayvnc fails with "No supported buffer formats were found"
            // on the ext-image-copy-capture-v1 protocol. The pixman
            // software renderer exposes plain SHM buffers (ARGB8888 /
            // XRGB8888) that wayvnc can capture without GPU help.
            append("export WLR_RENDERER=pixman ; ")
            // App-level GPU accel for cage GL apps (see virglEnv above). Empty
            // string when the virgl server isn't available, so the cage falls
            // back to the unchanged llvmpipe software path.
            append(virglEnv)
            // Some wlroots versions also honour WLR_NO_HARDWARE_CURSORS
            // and refuse to start without it when running headless on
            // a system that lacks DRM nodes.
            append("export WLR_NO_HARDWARE_CURSORS=1 ; ")
            append("export XKB_DEFAULT_LAYOUT=us ; ")
            append("export XKB_DEFAULT_RULES=evdev ; ")
            append("export XDG_SESSION_TYPE=wayland ; ")
            // XWayland (started eagerly by some compositors, e.g. cage)
            // refuses to bind unless /tmp/.X11-unix carries the sticky bit.
            // proot's /tmp is the app cacheDir (ext4, honours S_ISVTX), so
            // create it 1777 up front. Harmless for compositors that don't
            // touch XWayland (Sway/Hyprland/niri here run Wayland-only).
            append("mkdir -p /tmp/.X11-unix ; chmod 1777 /tmp/.X11-unix 2>/dev/null ; ")
            // Clean any stale wayland sockets from a previous launch so the
            // wait loop polls for a fresh one and the "did the compositor
            // start?" branch is honest. Clean ALL wayland-N, not just -1:
            // different compositors land on different socket numbers
            // (Sway → wayland-1, cage → wayland-0).
            append("rm -f $xdgInProot/wayland-[0-9]* ; ")
            // `set +e` around the compositor launch so a non-zero exit
            // doesn't kill the script before the wait/diagnostic block.
            append("set +e ; ")
            append("$compositorCmd > $xdgInProot/compositor.log 2>&1 & ")
            append("comp_pid=\$! ; ")
            // Wait up to ~10 s for ANY wayland-N socket to appear, then bind
            // wayvnc to whichever one the compositor actually created
            // (cage → wayland-0, Sway → wayland-1), rather than assuming -1.
            append("i=0 ; while [ -z \"\$(ls $xdgInProot 2>/dev/null | grep -E '^wayland-[0-9]+\$')\" ] && [ \$i -lt 20 ]; do sleep 0.5; i=\$((i+1)); done ; ")
            append("WL_SOCK=\$(ls $xdgInProot 2>/dev/null | grep -E '^wayland-[0-9]+\$' | head -1) ; ")
            append("if [ -z \"\$WL_SOCK\" ]; then ")
            append("echo '[haven] compositor did not create a wayland socket in $xdgInProot — log tail:' ; ")
            append("tail -n 50 $xdgInProot/compositor.log 2>&1 ; ")
            append("kill \$comp_pid 2>/dev/null ; ")
            append("exit 1 ; ")
            append("fi ; ")
            append("echo \"[haven] compositor up (socket \$WL_SOCK), starting wayvnc on $port\" ; ")
            append("export WAYLAND_DISPLAY=\$WL_SOCK ; ")
            // ~2 s grace period after the wayland socket appears, then
            // launch wayvnc. wayvnc 0.5 (Debian Bookworm) doesn't accept
            // --max-fps; restrict to flags supported across 0.5–0.9.
            // wayvnc exits when the compositor dies.
            append("sleep 2 ; ")
            // Capture-fallback shim: hides ext-image-copy-capture-v1
            // from wayvnc so it falls back to zwlr_screencopy_manager_v1.
            // The wlroots-0.19 headless backend reports zero buffer
            // formats on the ext path and wayvnc would bail with
            // "No supported buffer formats were found". The shim is
            // extracted in ProotManager.setupDesktop when a nested
            // Wayland DE is installed; a missing file does not block
            // the launch (LD_PRELOAD silently ignores nonexistent
            // libraries via the ld.so error path — wayvnc will still
            // come up, just back on the ext path that may grey-screen).
            append("export LD_PRELOAD=/usr/local/lib/haven/libhaven_wayvnc_shim.so ; ")
            append("exec wayvnc --render-cursor 0.0.0.0 $port")
        }

        // Present a non-root uid/gid (1000) instead of the usual `-0`
        // fake-root. Sway's drop_permissions() refuses to start unless
        // `setuid(0)` FAILS (proof that root can't be regained); under
        // `-0` setuid(0) is a no-op success, so Sway bails with "Unable
        // to drop root … refusing to start" (sway/main.c:170) — the #162
        // bug-B failure. `-i 1000:1000` makes the guest a normal user so
        // setuid(0) fails and the check passes. proot doesn't enforce DAC
        // (the real Android app uid owns the whole rootfs), so /root + the
        // seeded config stay readable, and the headless wlroots/pixman
        // path needs no real privileges. Verified: Sway now boots and
        // creates its HEADLESS-1 output.
        val prootArgs = mutableListOf(
            prootBin, "-i", "1000:1000", "--link2symlink", "--sysvipc",
            // #325: foreign-arch rootfs runs through the bundled qemu-user loader.
            *prootManager.qemuUserArgs(prootManager.activeDistroId).toTypedArray(),
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            // Mask /sys/fs/selinux (Android enforces SELinux; exposing enforce=1
            // breaks guest cp -Z/restorecon in package postinst scripts, #283).
            "-b", prootManager.selinuxMaskBind(rootfsDir),
            "-b", "${context.cacheDir.absolutePath}:/tmp",
            "-b", "${devShmHost.absolutePath}:/dev/shm",
            // #304: surface the device model at the devicetree path fastfetch reads.
            *(prootManager.deviceModelDevicetreeBind()?.let { arrayOf("-b", it) } ?: emptyArray()),
            // #304 part 2: optionally expose Android's system partitions (opt-in).
            *prootManager.androidSystemBindArgs(longForm = false),
        )
        // #300: remap privileged binds (<1024) up by +2000 when opted in.
        if (prootManager.remapLowPorts) prootArgs.add("-p")
        // #420: expose the user's shared storage (/storage, /sdcard) — same binds
        // the interactive shell uses, gated on the same opt-out. Without this the
        // desktop file manager saw an empty /sdcard.
        prootArgs.addAll(prootManager.storageBindShortArgs())
        // #301: per-distro user-defined extra binds.
        prootArgs.addAll(prootManager.customBindShortArgs(prootManager.activeDistroId))
        prootArgs.addAll(listOf("-w", "/root") + cleanGuestEnv() + listOf("/bin/sh", "-c", shellCmd))

        Log.d(TAG, "Starting $label (nested wayland) on port $port (display $display)")

        return ProcessBuilder(prootArgs).apply {
            environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                // Pin TMPDIR to the cacheDir-backed /tmp bind; else the desktop
                // session (and terminals opened from it) inherit the Android
                // process TMPDIR (app cache host path), unreachable inside proot
                // → mktemp fails in desktop-environment terminals (#283).
                put("TMPDIR", "/tmp")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PROOT_LOADER", loaderPath)
                put("PROOT_L2S_DIR", prootManager.l2sEnvValue(prootManager.activeDistroId))
            }
            redirectErrorStream(true)
        }.start()
    }

    // ---- App windows: one GUI app in a cage kiosk, surfaced in the
    //      present_media overlay (not a full desktop). Reuses the nested-
    //      Wayland launch path with a per-call compositor command and is
    //      tracked by an opaque sessionId in a parallel registry, so it
    //      needs no compile-time DesktopEnvironment enum entry. ----

    /** State of one single-app cage kiosk surfaced as an overlay window. */
    data class AppWindowSession(
        val sessionId: String,
        /** The app command cage runs, e.g. "imv /root/x.png". */
        val command: String,
        val displayNumber: Int,
        val vncPort: Int,
        val state: DesktopState,
        val errorMessage: String? = null,
        /** Current sway output scale (updated live by [setAppWindowScale]). */
        val scale: Float = 1f,
    )

    private val _appWindows = MutableStateFlow<Map<String, AppWindowSession>>(emptyMap())
    val appWindows: StateFlow<Map<String, AppWindowSession>> = _appWindows.asStateFlow()
    private val appWindowProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()

    /**
     * Make the active guest able to run a cage app as root: install `fakeroot`
     * (whose `fakeroot-tcp` works under proot — the default sysv variant needs
     * SysV IPC, which Android lacks) and confirm `fakeroot-tcp` is on PATH.
     * Returns true if it's available, so the caller passes the result as
     * [startAppWindow]'s `runAsRoot` (only wrap when the wrapper really exists).
     *
     * APT-only today (synaptic's use case is verified there); other families
     * package `fakeroot` differently and aren't validated under proot yet.
     */
    suspend fun ensureRunAsRoot(): Boolean {
        val installCmd = when (prootManager.activeDistro.family) {
            PackageFamily.APT -> "DEBIAN_FRONTEND=noninteractive apt-get install -y fakeroot"
            else -> return false
        }
        // The install runs as root (runCommandInProot uses --root-id); the cage
        // that later wraps the app in fakeroot-tcp stays the non-root user.
        val (_, code) = prootManager.runCommandInProot(
            "command -v fakeroot-tcp >/dev/null 2>&1 || { $installCmd >/dev/null 2>&1; }; " +
                "command -v fakeroot-tcp >/dev/null 2>&1",
        )
        return code == 0
    }

    /**
     * True when the cage runtime (the Sway DE — `sway` + `wayvnc`) is installed
     * in the active distro. A cheap file-existence check (no proot spawn), so
     * callers can decide whether to surface "installing…" progress before the
     * slow [ensureCageRuntime] install.
     */
    fun isCageRuntimeReady(): Boolean =
        prootManager.isDesktopInstalled(ProotManager.DesktopEnvironment.SWAY)

    /**
     * Ensure the cage runtime is installed before [startAppWindow]. App windows
     * run the app as a single-app sway kiosk, so without `sway`+`wayvnc` the
     * kiosk exits with "sway: command not found". Mirrors [ensureRunAsRoot]: an
     * on-demand install of the Sway DE for the active family — broader than
     * [ensureRunAsRoot] (APT-only) because the Sway spec ships package lists for
     * APK/APT/PACMAN/XBPS. Reuses [ProotManager.setupDesktop], which drives the
     * `desktopState` progress flow the Desktop screen shows.
     *
     * Heavy and non-streaming (the Sway DE is ~60–200 MB) — call
     * [isCageRuntimeReady] first and surface progress; don't hang a launch on it
     * silently. Returns true if the runtime is present (already, or installed).
     */
    suspend fun ensureCageRuntime(): Boolean {
        if (isCageRuntimeReady()) return true
        prootManager.setupDesktop(vncPassword = "", de = ProotManager.DesktopEnvironment.SWAY)
        return isCageRuntimeReady()
    }

    /**
     * Launch [command] as a single-app `cage` kiosk and expose it over
     * wayvnc. Blocks (on the caller's IO thread) until the VNC port accepts
     * connections — state RUNNING — or the launch fails / times out — state
     * ERROR — then returns the session.
     *
     * Multiple windows run concurrently: each gets its own display (so its own
     * per-display `XDG_RUNTIME_DIR`/wayland socket and `5900+display` VNC port)
     * and is torn down independently by [stopAppWindow]. The UI keeps one
     * focused in the overlay and the rest docked as edge icons.
     */
    fun startAppWindow(
        command: String,
        resolution: String = "auto",
        scale: Float = 1f,
        runAsRoot: Boolean = false,
        multiWindow: Boolean = false,
        swayRules: List<String> = emptyList(),
        timeoutMs: Long = 15_000,
    ): AppWindowSession {

        val sessionId = "appwin-${System.currentTimeMillis()}"
        // The cage runs the compositor as a non-root user (sway refuses to start
        // as root), so the app inherits uid 1000 and system tools (synaptic) go
        // read-only. `fakeroot-tcp` (LD_PRELOAD) makes the app's getuid() report
        // 0; proot doesn't enforce DAC so the writes still land. Caller passes
        // runAsRoot=true only after ensureRunAsRoot() confirmed fakeroot-tcp.
        val execCommand = if (runAsRoot) "fakeroot-tcp $command" else command
        val display = allocateDisplay()
        val port = 5900 + display
        // Headless output geometry: matching the framebuffer to the device aspect
        // makes the VNC viewer fill the screen (no letterbox); `scale` enlarges
        // fonts/UI (wlroots HiDPI, honoured by foot/GTK). "auto" derives a portrait
        // framebuffer at a ~720p budget so the pixman software renderer stays light.
        val dm = context.resources.displayMetrics
        val (outW, outH) = resolveCageResolution(resolution, dm.widthPixels, dm.heightPixels)
        val scaleStr = formatCageScale(scale)
        // cage 0.1.4 (Debian Bookworm) does its own scan-out / back_buffer
        // management that aborts on the headless backend (assert back_buffer
        // != NULL when it direct-scans-out the single view; == NULL when it
        // composites). sway drives the headless backend through wlr_scene,
        // which manages buffers correctly — verified rendering here where cage
        // crashes. Run sway as a single-app kiosk: a generated one-shot config
        // runs the app fullscreen with no chrome and exits sway when the app
        // closes, matching cage's lifecycle. sway also starts Xwayland, so X11
        // GUIs work too. The config lives under cacheDir (bound to /tmp).
        // The app + exit hook go through a launcher script, not inline in the
        // config: sway's parser splits an `exec a; b` line at the ';' and
        // rejected `swaymsg exit` as an unknown config command (#471) — the
        // app half still ran, so nobody noticed that exit-on-app-close never
        // registered and every self-closing app leaked its kiosk. A script
        // also sidesteps quoting arbitrary commands. SWAYSOCK is inherited
        // from sway, so swaymsg reaches the right compositor.
        val kioskRun = File(context.cacheDir, "haven-kiosk-$display.run")
        kioskRun.writeText("$execCommand\nswaymsg exit\n")
        val kioskConfig = File(context.cacheDir, "haven-kiosk-$display.config")
        kioskConfig.writeText(
            """
            |output HEADLESS-1 mode ${outW}x${outH}@60Hz
            |output HEADLESS-1 scale $scaleStr
            |output HEADLESS-1 enable
            |default_border none
            |default_floating_border none
            |hide_edge_borders both
            |gaps inner 0
            |gaps outer 0
            |${
                // Single-window apps get kiosk-style forced fullscreen. A
                // multi-window app (qmmp's skinned main/EQ/playlist deck)
                // must float instead: fullscreen-everything stacks its
                // windows so only the last-raised one is visible, while
                // floating honours the app's own placement (Winamp docking).
                if (multiWindow) {
                    """for_window [app_id=".*"] floating enable
                      |for_window [class=".*"] floating enable""".trimMargin()
                } else {
                    """for_window [app_id=".*"] fullscreen enable
                      |for_window [class=".*"] fullscreen enable""".trimMargin()
                }
            }
            |${
                // App-specific placement (pack/def-provided): sway centers
                // every floating window, so a multi-window deck maps stacked;
                // per-title `move position` rules place each window at map.
                // Newlines are stripped per rule so a rule can't smuggle in
                // extra config lines.
                swayRules.joinToString("\n") { it.replace('\n', ' ') }
            }
            |exec sh /tmp/haven-kiosk-$display.run
            |""".trimMargin(),
        )
        val compositorCmd = "sway -c /tmp/haven-kiosk-$display.config"
        var session = AppWindowSession(sessionId, command, display, port, DesktopState.STARTING, scale = scale)
        _appWindows.update { it + (sessionId to session) }

        val process = try {
            launchNestedWayland(compositorCmd, "app:$command", display, port)
        } catch (e: Exception) {
            releaseDisplay(display)
            session = session.copy(state = DesktopState.ERROR, errorMessage = e.message)
            _appWindows.update { it + (sessionId to session) }
            return session
        }
        appWindowProcesses[sessionId] = process

        // Drain the launch process output to Logcat and notice an early exit
        // (cage gone = app closed or never came up).
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "$sessionId[:$display]: $line")
                }
            } catch (_: Exception) {}
            val code = process.waitFor()
            Log.d(TAG, "$sessionId launch process exited: $code")
            appWindowProcesses.remove(sessionId)
            releaseDisplay(display)
            _appWindows.update { current ->
                val s = current[sessionId] ?: return@update current
                when (s.state) {
                    // Crashed before the port came up → surface as ERROR.
                    DesktopState.STARTING -> current + (sessionId to s.copy(
                        state = DesktopState.ERROR,
                        errorMessage = "cage kiosk exited during startup (code $code)",
                    ))
                    // App was running and then exited (kiosk closes with it)
                    // → drop the session so the overlay can react.
                    DesktopState.RUNNING -> current - sessionId
                    else -> current
                }
            }
        }, "appwin-$sessionId").apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            _appWindows.value[sessionId]?.let { if (it.state == DesktopState.ERROR) return it }
            if (isPortListening(port)) {
                session = session.copy(state = DesktopState.RUNNING)
                _appWindows.update { it + (sessionId to session) }
                return session
            }
            Thread.sleep(300)
        }
        stopAppWindow(sessionId)
        val errored = AppWindowSession(
            sessionId, command, display, port, DesktopState.ERROR,
            errorMessage = "VNC port $port never came up within ${timeoutMs}ms",
        )
        _appWindows.update { it + (sessionId to errored) }
        return errored
    }

    /** Stop an app-window kiosk and release its display. Idempotent. */
    fun stopAppWindow(sessionId: String) {
        val session = _appWindows.value[sessionId] ?: return
        appWindowProcesses[sessionId]?.destroyForcibly()
        appWindowProcesses.remove(sessionId)
        // destroyForcibly only kills the proot launch shell (now wayvnc);
        // cage + the app become orphans. Sweep them PER SESSION (not by name)
        // so concurrent app windows — and a nested-Wayland desktop — survive:
        // every process in this session's cage tree carries this session's
        // unique XDG_RUNTIME_DIR (/tmp/xdg-runtime-<display>) in its environ.
        killAppWindowSession(session.displayNumber)
        releaseDisplay(session.displayNumber)
        _appWindows.update { it - sessionId }
    }

    /**
     * Live-change a running app window's sway output scale (the 3-finger pinch
     * gesture). Only the output *scale* changes — the headless `mode`
     * (framebuffer pixel size) is unchanged, so wayvnc keeps the same size and
     * the VNC view just re-renders the app bigger/smaller with no reconnect.
     * No-op if the session isn't known.
     *
     * Stale `sway-ipc.*.sock` accumulate under the per-display XDG dir (display
     * numbers are reused and the launch only cleans `wayland-*`), so the live
     * socket is found by connect-testing each with `-t get_version`.
     */
    suspend fun setAppWindowScale(sessionId: String, scale: Float) {
        val session = _appWindows.value[sessionId] ?: return
        val xdg = "/tmp/xdg-runtime-${session.displayNumber}"
        val s = formatCageScale(scale)
        val cmd = buildString {
            append("export XDG_RUNTIME_DIR=$xdg; ")
            append("for k in $xdg/sway-ipc.*.sock; do ")
            append("swaymsg -s \"\$k\" -t get_version >/dev/null 2>&1 && { ")
            append("swaymsg -s \"\$k\" output HEADLESS-1 scale $s >/dev/null 2>&1; break; }; ")
            append("done")
        }
        prootManager.runCommandInProot(cmd)
        _appWindows.update { it + (sessionId to session.copy(scale = scale)) }
    }

    /**
     * Refit a running app window to a screen area of [screenW]x[screenH] (the
     * device's current usable/safe area) by re-moding its sway output to the
     * aspect-matched [computeAutoResolution] — used on fullscreen-enter and on
     * rotation. wayvnc resizes its framebuffer and the VNC client adapts
     * (verified). No-op if unknown. Socket found the connect-test way as
     * [setAppWindowScale].
     */
    suspend fun setAppWindowResolution(sessionId: String, screenW: Int, screenH: Int) {
        val session = _appWindows.value[sessionId] ?: return
        val (w, h) = computeAutoResolution(screenW, screenH)
        val xdg = "/tmp/xdg-runtime-${session.displayNumber}"
        val cmd = buildString {
            append("export XDG_RUNTIME_DIR=$xdg; ")
            append("for k in $xdg/sway-ipc.*.sock; do ")
            append("swaymsg -s \"\$k\" -t get_version >/dev/null 2>&1 && { ")
            append("swaymsg -s \"\$k\" output HEADLESS-1 mode ${w}x${h}@60Hz >/dev/null 2>&1; break; }; ")
            append("done")
        }
        prootManager.runCommandInProot(cmd)
    }

    /**
     * Kill exactly the cage-kiosk process tree for one app window, identified
     * by its per-session XDG_RUNTIME_DIR (/tmp/xdg-runtime-[display]). cage,
     * the kiosk app, and wayvnc are all exec'd with that env var exported, so
     * an exact match on it scopes the kill to this session and leaves other
     * concurrent app windows (and any nested-Wayland desktop) untouched —
     * unlike the name-based [killOrphanedNestedWayland].
     */
    private fun killAppWindowSession(display: Int) {
        val xdg = "/tmp/xdg-runtime-$display"
        // environ is NUL-separated; tr → newlines, then exact-line match on the
        // full var so display 1 doesn't also match display 11.
        val scanScript = appWindowSessionScanScript(xdg)
        try {
            val proc = ProcessBuilder("sh", "-c", scanScript)
                .redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing app-window session (display $display): ${pids.replace('\n', ' ')}")
                for (pid in pids.lines()) {
                    try {
                        ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                    } catch (_: Exception) {}
                }
            } else {
                Log.d(TAG, "No app-window processes for display $display")
            }
        } catch (e: Exception) {
            Log.w(TAG, "killAppWindowSession($display) failed: ${e.message}")
        }
    }

    /** Compositor log for an app-window session (grey-screen diagnostics). */
    fun appWindowCompositorLog(sessionId: String): String? {
        val display = _appWindows.value[sessionId]?.displayNumber ?: return null
        val logFile = File(context.cacheDir, "xdg-runtime-$display/compositor.log")
        return if (logFile.exists()) logFile.readText() else null
    }

    /**
     * Newest app-window compositor.log across all displays. The cage redirects
     * BOTH the compositor and the app it runs (stdout+stderr merged) here, so
     * this is the app's own output — the fallback for read_app_window_log when
     * the session has already ended: a crashed/exited cage app drops its session
     * (so [appWindowCompositorLog] can't resolve its display), but the log file
     * survives under cacheDir. Returns (display, file) or null if none exists.
     */
    fun latestAppWindowLog(): Pair<Int, File>? =
        context.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("xdg-runtime-") }
            ?.mapNotNull { dir ->
                val log = File(dir, "compositor.log")
                val display = dir.name.removePrefix("xdg-runtime-").toIntOrNull()
                if (log.exists() && display != null) display to log else null
            }
            ?.maxByOrNull { it.second.lastModified() }

    /** True when something accepts a TCP connection on 127.0.0.1:[port]. */
    private fun isPortListening(port: Int): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
            true
        }
    } catch (_: Exception) {
        false
    }

    // ---- Native Wayland compositor (uses JNI + virgl) ----

    private fun startNativeCompositor(
        de: ProotManager.DesktopEnvironment,
        shellCommand: String = "/bin/sh -l",
    ) {
        _desktops.update { it + (de to DesktopInstance(de, 0, 0, DesktopState.STARTING)) }

        try {
            val bridge = WaylandBridge

            // Prepare XDG runtime dir (must be mode 0700, owned by app)
            val xdgDir = File(context.cacheDir, "wayland-xdg").apply {
                mkdirs()
                setReadable(true, true)
                setWritable(true, true)
                setExecutable(true, true)
            }
            // Clean stale sockets
            File(xdgDir, "wayland-0").delete()
            File(xdgDir, "wayland-0.lock").delete()

            // Extract XKB data from assets on first use
            val xkbDir = File(context.filesDir, "xkb")
            if (!File(xkbDir, "rules/evdev").exists()) {
                Log.d(TAG, "Extracting XKB data...")
                extractAssetsDir(context, "xkb", xkbDir)
            }

            // Fontconfig pointing to system fonts
            val fontconfFile = File(context.cacheDir, "fonts.conf")
            if (!fontconfFile.exists()) {
                fontconfFile.writeText("""
                    <?xml version="1.0"?>
                    <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
                    <fontconfig>
                      <dir>/system/fonts</dir>
                      <cachedir>${context.cacheDir.absolutePath}/fontconfig-cache</cachedir>
                    </fontconfig>
                """.trimIndent())
                File(context.cacheDir, "fontconfig-cache").mkdirs()
            }

            // Set up native XWayland wrapper binary
            val xwaylandWrapper = File(
                context.applicationInfo.nativeLibraryDir, "libxwayland_wrapper.so",
            )
            val loaderPathXw = File(
                context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
            ).absolutePath
            val rootfsDir = prootManager.activeRootfsDir
            android.system.Os.setenv("WLR_XWAYLAND", xwaylandWrapper.absolutePath, true)
            android.system.Os.setenv("HAVEN_PROOT_BIN", prootManager.prootBinary ?: "", true)
            android.system.Os.setenv("HAVEN_PROOT_LOADER", loaderPathXw, true)
            android.system.Os.setenv("HAVEN_PROOT_ROOTFS", rootfsDir.absolutePath, true)
            android.system.Os.setenv("HAVEN_CACHE_DIR", context.cacheDir.absolutePath, true)
            android.system.Os.setenv("HAVEN_XDG_DIR", xdgDir.absolutePath, true)
            // Clean stale X11 sockets before compositor starts XWayland
            val x11UnixDir = File(context.cacheDir, ".X11-unix")
            x11UnixDir.deleteRecursively()
            x11UnixDir.mkdirs()
            Log.d(TAG, "Starting native compositor: XDG_RUNTIME_DIR=${xdgDir.absolutePath}")
            bridge.nativeStart(
                xdgRuntimeDir = xdgDir.absolutePath,
                xkbConfigRoot = xkbDir.absolutePath,
                fontconfigFile = fontconfFile.absolutePath,
            )

            // Wait for socket to appear
            val socket = File(xdgDir, "wayland-0")
            var waited = 0
            while (!socket.exists() && waited < 10) {
                Thread.sleep(500)
                waited++
            }
            if (socket.exists()) {
                Log.d(TAG, "Native compositor started, socket: ${socket.absolutePath}")
                WaylandSocketHelper.tryCreateSymlink(socket.absolutePath)
            } else {
                Log.e(TAG, "Native compositor socket not created after ${waited * 500}ms")
            }

            // Start virgl_test_server for GPU-accelerated OpenGL in PRoot apps
            val virglBin = File(context.applicationInfo.nativeLibraryDir, "libvirgl_test_server.so")
            val virglSocket = File(context.cacheDir, ".virgl_test")
            virglSocket.delete()
            if (virglBin.canExecute()) {
                Log.d(TAG, "Starting virgl_test_server...")
                bridge.nativeStartVirglServer(virglBin.absolutePath, virglSocket.absolutePath)
            }

            // Start PRoot with Wayland clients, bind-mounting the native socket
            val prootBin = prootManager.prootBinary ?: run {
                _desktops.update { it + (de to DesktopInstance(
                    de, 0, 0, DesktopState.ERROR,
                    errorMessage = "PRoot not available",
                )) }
                return
            }
            val loaderPath = File(
                context.applicationInfo.nativeLibraryDir, "libproot_loader.so",
            ).absolutePath
            val rootHome = File(rootfsDir, "root").apply { mkdirs() }

            // Autostart differs by native DE: the X11 desktop (#268) lands the
            // user in an X terminal via Xwayland (DISPLAY is exported below);
            // labwc manages the rootless-Xwayland windows, so no in-guest X WM
            // is needed. The Wayland native desktop autostarts its
            // waybar/thunar/foot set instead.
            val nativeAutostart = if (de.id == "x11-native") {
                "if command -v xterm >/dev/null 2>&1; then xterm -e $shellCommand 2>&1; " +
                    "else $shellCommand 2>&1; fi; "
            } else {
                "if [ -x /usr/bin/waybar ]; then " +
                    "dbus-run-session waybar >/tmp/waybar.log 2>&1 & sleep 2; " +
                    "fi; " +
                    "[ -x /usr/bin/thunar ] && thunar --daemon & " +
                    "foot -e $shellCommand 2>&1; "
            }

            // #300: remap privileged binds (<1024) up by +2000 when opted in.
            val portRemap = if (prootManager.remapLowPorts) arrayOf("-p") else emptyArray()
            // #301: per-distro user-defined extra binds.
            val customBinds = prootManager.customBindShortArgs(prootManager.activeDistroId).toTypedArray()
            val process = ProcessBuilder(
                prootBin, "-0", "--link2symlink", "--sysvipc",
                // #325: foreign-arch rootfs runs through the bundled qemu-user loader.
                *prootManager.qemuUserArgs(prootManager.activeDistroId).toTypedArray(),
                *portRemap,
                "-r", rootfsDir.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                // Mask /sys/fs/selinux (#283) — see ProotManager.selinuxMaskBind.
                "-b", prootManager.selinuxMaskBind(rootfsDir),
                "-b", "${context.cacheDir.absolutePath}:/tmp",
                "-b", "${xdgDir.absolutePath}:/tmp/xdg-runtime",
                // #304: surface the device model at the devicetree path fastfetch reads.
                *(prootManager.deviceModelDevicetreeBind()?.let { arrayOf("-b", it) } ?: emptyArray()),
                // #304 part 2: optionally expose Android's system partitions (opt-in).
                *prootManager.androidSystemBindArgs(longForm = false),
                // #420: expose the user's shared storage (/storage, /sdcard), same
                // as the shell — else the desktop file manager sees an empty /sdcard.
                *prootManager.storageBindShortArgs().toTypedArray(),
                *customBinds,
                "-w", "/root",
                *cleanGuestEnv().toTypedArray(),
                "/bin/sh", "-c",
                "export HOME=/root; " +
                    // Audio bridge (#257): pick up PULSE_SERVER if the bridge
                    // is running (it writes this file); harmless no-op otherwise.
                    "[ -f /etc/profile.d/pulse.sh ] && . /etc/profile.d/pulse.sh; " +
                    "export XDG_RUNTIME_DIR=/tmp/xdg-runtime; " +
                    "export XDG_DATA_HOME=/root/.local/share; " +
                    "export XDG_DATA_DIRS=/usr/local/share:/usr/share; " +
                    "export GDK_BACKEND=wayland,x11; " +
                    "export WAYLAND_DISPLAY=wayland-0; " +
                    "unset FONTCONFIG_FILE; " +
                    "unset XKB_CONFIG_ROOT; " +
                    "export TERM=xterm-256color; " +
                    "export SHELL=${shellCommand.split(" ").first()}; " +
                    // GPU passthrough env (virpipe default, or venus+zink when the
                    // gpu_use_venus pref is on) — see gpuPassthroughEnv.
                    gpuPassthroughEnv("; ") +
                    // App launcher wrapper — invoked via fuzzel's
                    // `launch-prefix=/usr/local/bin/launch` and by labwc's
                    // menu Execute actions. Records each invocation +
                    // launched PID + the spawned command's own stderr to
                    // /tmp/haven-launch.log so we can diagnose tap-launch
                    // failures (see GlassHaven/Haven#161 follow-up: blank
                    // glyphs / fuzzel-doesn't-launch-on-tap). Without
                    // this, fuzzel forks the prefix script with no record
                    // of whether the click was even received.
                    "mkdir -p /usr/local/bin; cat > /usr/local/bin/launch <<'LAUNCHEOF'\n#!/bin/sh\nLOG=/tmp/haven-launch.log\necho \"[\$(date +%H:%M:%S)] launch \$*\" >> \"\$LOG\" 2>/dev/null\n\"\$@\" >> \"\$LOG\" 2>&1 &\necho \"[\$(date +%H:%M:%S)] launched pid=\$!\" >> \"\$LOG\" 2>/dev/null\nLAUNCHEOF\nchmod +x /usr/local/bin/launch; " +
                    // Set up XWayland for X11 app compatibility
                    "mkdir -p /tmp/.X11-unix; " +
                    "i=0; while ! ls /tmp/.X11-unix/X* >/dev/null 2>&1 && [ \$i -lt 5 ]; do sleep 1; i=\$((i+1)); done; " +
                    "if ls /tmp/.X11-unix/X* >/dev/null 2>&1; then " +
                        "XDISP=\$(ls /tmp/.X11-unix/ | sort -r | head -1 | sed 's/X//'); " +
                        "export DISPLAY=:\$XDISP; " +
                        "mkdir -p /etc/profile.d; echo \"export DISPLAY=:\$XDISP\" > /etc/profile.d/display.sh; " +
                    "fi; " +
                    // Auto-start desktop components (DE-specific — see nativeAutostart)
                    nativeAutostart +
                    "wait",
            ).apply {
                environment().apply {
                    put("HOME", "/root")
                    put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                    // Pin TMPDIR to the cacheDir-backed /tmp bind (see #283):
                    // without it the native desktop session and its terminals
                    // inherit the Android app-cache TMPDIR, unreachable in proot.
                    put("TMPDIR", "/tmp")
                    put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                    put("PROOT_LOADER", loaderPath)
                    put("PROOT_L2S_DIR", prootManager.l2sEnvValue(prootManager.activeDistroId))
                    remove("FONTCONFIG_FILE")
                    remove("XKB_CONFIG_ROOT")
                }
                redirectErrorStream(true)
            }.start()

            processes[de] = process
            _desktops.update { it + (de to DesktopInstance(de, 0, 0, DesktopState.RUNNING)) }

            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "NativeWayland: $line")
                    }
                } catch (_: Exception) {}
                Log.d(TAG, "NativeWayland PRoot exited: ${process.waitFor()}")
                processes.remove(de)
                _desktops.update { it - de }
            }, "native-wayland-log").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native compositor", e)
            _desktops.update { it + (de to DesktopInstance(
                de, 0, 0, DesktopState.ERROR,
                errorMessage = e.message,
            )) }
        }
    }

    // ---- Helpers ----

    /** Kill orphaned Xvnc process for a specific display number. */
    private fun killOrphanedXvnc(display: Int) {
        killPids("Xvnc[:$display]", scanCmdlines().filter { xvncMatches(it.cmdline, display) }.map { it.pid })
    }

    /**
     * Sweep the rootfs's compositor + wayvnc + foot leftovers after the
     * proot launcher has been destroyForcibly()'d. Without this they
     * survive as orphans and block the next start_desktop on the
     * wayvncctl control socket. compositorCmd is the binary name from
     * LaunchSpec.NestedWayland (sway / Hyprland / niri); foot is the
     * auto-launched terminal; swaynag pops up on sway config errors.
     *
     * Matched via /proc/<pid>/cmdline rather than COMM because Android's
     * toybox `ps -A -o comm` wraps proot-launched binaries in [brackets]
     * (the kernel sees libproot_loader.so as the loaded ELF and only
     * sets the comm via prctl), so an exact equality match on COMM
     * misses them. cmdline always reflects the program's own argv[0]
     * (e.g. "/usr/sbin/wayvnc --render-cursor 0.0.0.0 5901").
     */
    private fun killOrphanedNestedWayland(compositorCmd: String) {
        // Match against the basename of cmdline argv[0]. The targets
        // include both the active compositor and its standard children
        // (wayvnc, foot, swaynag) so the next launch starts clean.
        // PREFIX match (`token*`) so wrapper binaries are caught too — e.g.
        // the `imv` launcher execs `imv-wayland`, which an exact match misses.
        val targets = listOf(compositorCmd, "wayvnc", "foot", "swaynag")
        val scanScript = nestedWaylandScanScript(targets)
        try {
            val proc = ProcessBuilder("sh", "-c", scanScript)
                .redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (pids.isNotEmpty()) {
                Log.d(TAG, "Killing nested-wayland orphans ($compositorCmd tree): ${pids.replace('\n', ' ')}")
                for (pid in pids.lines()) {
                    try {
                        ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                    } catch (_: Exception) {}
                }
            } else {
                Log.d(TAG, "No nested-wayland orphans for $compositorCmd")
            }
        } catch (e: Exception) {
            Log.w(TAG, "killOrphanedNestedWayland($compositorCmd) failed: ${e.message}")
        }
    }

    /** Kill all orphaned Xvnc processes. */
    fun killAllOrphanedXvnc() {
        killPids("Xvnc", scanCmdlines().filter { xvncMatches(it.cmdline, display = null) }.map { it.pid })
    }

    /**
     * Every readable process as (pid, full command line), argv separated by
     * spaces.
     *
     * `ps -A` cannot be used for this. It prints the command *name* and no
     * arguments, so a filter on an Xvnc display (`:1`) matched nothing, ever
     * — which is why the desktop's stop button appeared to do nothing and
     * left Xvnc running (#501). `/proc/<pid>/cmdline` is also what
     * [killOrphanedNestedWayland] already had to switch to, because Android
     * shows proot-launched binaries under a bracketed COMM.
     */
    private fun scanCmdlines(): List<ProcessCmdline> = try {
        val proc = ProcessBuilder("sh", "-c", CMDLINE_SCAN_SCRIPT).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else ProcessCmdline(line.substring(0, tab), line.substring(tab + 1).trim())
        }.filter { it.cmdline.isNotEmpty() }.toList()
    } catch (e: Exception) {
        Log.w(TAG, "scanCmdlines failed: ${e.message}")
        emptyList()
    }

    private fun killPids(label: String, pids: List<String>) {
        if (pids.isEmpty()) {
            Log.d(TAG, "No $label processes to kill")
            return
        }
        Log.d(TAG, "Killing $label PIDs: ${pids.joinToString(" ")}")
        for (pid in pids) {
            try {
                ProcessBuilder("kill", "-9", pid).start().waitFor()
            } catch (_: Exception) {}
        }
    }

    /** Recursively extract an assets directory to the filesystem. */
    private fun extractAssetsDir(ctx: Context, assetPath: String, destDir: File) {
        val assets = ctx.assets
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            destDir.mkdirs()
            for (child in list) {
                extractAssetsDir(ctx, "$assetPath/$child", File(destDir, child))
            }
        }
    }
}

// --- Cage headless-output geometry (pure, unit-tested) ---

/** Round up to the nearest even integer (encoders/VNC prefer even dimensions). */
private fun evenUp(n: Int): Int = if (n % 2 == 0) n else n + 1

/**
 * Resolve a cage [resolution] token to an even (width, height) for the sway
 * headless output. `"WxH"` is parsed (clamped, even); anything else (incl.
 * `"auto"`) derives a framebuffer matching the device aspect via
 * [computeAutoResolution].
 */
internal fun resolveCageResolution(
    resolution: String,
    deviceW: Int,
    deviceH: Int,
    shortEdgeBudget: Int = 720,
    longEdgeCap: Int = 1600,
): Pair<Int, Int> =
    parseWxH(resolution) ?: computeAutoResolution(deviceW, deviceH, shortEdgeBudget, longEdgeCap)

/**
 * A framebuffer matching the device aspect with the **short edge** at
 * [shortEdgeBudget] (capping the long edge at [longEdgeCap]), preserving the
 * device's orientation. Matching the aspect lets the VNC viewer fill the screen
 * without letterboxing; the modest budget keeps the pixman software renderer light.
 */
internal fun computeAutoResolution(
    deviceW: Int,
    deviceH: Int,
    shortEdgeBudget: Int = 720,
    longEdgeCap: Int = 1600,
): Pair<Int, Int> {
    val w = if (deviceW > 0) deviceW else 1080
    val h = if (deviceH > 0) deviceH else 1920
    val shortPx = minOf(w, h).toDouble()
    val longPx = maxOf(w, h).toDouble()
    var shortOut = shortEdgeBudget
    var longOut = Math.round(shortEdgeBudget * longPx / shortPx).toInt()
    if (longOut > longEdgeCap) {
        longOut = longEdgeCap
        shortOut = Math.round(longEdgeCap * shortPx / longPx).toInt()
    }
    val es = evenUp(shortOut)
    val el = evenUp(longOut)
    // Portrait device (h >= w) → (width=short, height=long); landscape → swapped.
    return if (h >= w) es to el else el to es
}

/** Parse a `"WxH"` token to a clamped, even (w, h), or null if not that shape. */
internal fun parseWxH(token: String): Pair<Int, Int>? {
    val m = Regex("""^(\d{2,5})x(\d{2,5})$""").matchEntire(token.trim()) ?: return null
    val w = evenUp(m.groupValues[1].toInt().coerceIn(160, 4096))
    val h = evenUp(m.groupValues[2].toInt().coerceIn(160, 4096))
    return w to h
}

/** Format a wlroots output scale for the sway config (`1`, `1.5`, `2`). */
internal fun formatCageScale(scale: Float): String {
    val c = scale.coerceIn(0.5f, 3f)
    return if (c == c.toInt().toFloat()) c.toInt().toString() else c.toString()
}

/**
 * The /proc scanners are shell source assembled by interpolation, and shell
 * that reads correctly is not necessarily shell that parses — a splice that
 * looked fine turned out to be invalid while fixing #501. Kept as pure
 * functions of their inputs so a test can hand each one to `sh -n`, which is
 * the only check that covers generated shell.
 */

/** PIDs whose environment names [xdg] as XDG_RUNTIME_DIR — one app-window session. */
internal fun appWindowSessionScanScript(xdg: String): String = """
    for p in /proc/[0-9]*; do
        [ -r ${'$'}p/environ ] || continue
        if tr '\0' '\n' < ${'$'}p/environ 2>/dev/null | grep -qx "XDG_RUNTIME_DIR=$xdg"; then
            echo "${'$'}{p##*/}"
        fi
    done
""".trimIndent()

/**
 * PIDs whose argv[0] basename starts with any of [targets].
 *
 * Prefix match, so a wrapper binary is caught too — the `imv` launcher execs
 * `imv-wayland`, which an exact match misses.
 */
internal fun nestedWaylandScanScript(targets: List<String>): String {
    val caseArm = targets.joinToString("|") { "\"$it\"*" }
    return """
        for p in /proc/[0-9]*; do
            [ -r ${'$'}p/cmdline ] || continue
            first=${'$'}(tr '\0' ' ' < ${'$'}p/cmdline 2>/dev/null | awk '{print ${'$'}1}')
            case "${'$'}{first##*/}" in $caseArm) echo "${'$'}{p##*/}";; esac
        done
    """.trimIndent()
}

/** Every readable process as `pid<TAB>argv`, argv separated by spaces. */
internal val CMDLINE_SCAN_SCRIPT: String = """
    for p in /proc/[0-9]*; do
        [ -r ${'$'}p/cmdline ] || continue
        echo "${'$'}{p##*/}\t${'$'}(tr '\0' ' ' < ${'$'}p/cmdline 2>/dev/null)"
    done
""".trimIndent()

/** A process id paired with its full command line, argv separated by spaces. */
internal data class ProcessCmdline(val pid: String, val cmdline: String)

/**
 * Whether [cmdline] is an Xvnc serving [display] (any display when null).
 *
 * Matching on argv rather than on the process name is the point (#501): the
 * display only ever appears as an argument, so the previous `ps -A` filter —
 * which prints names and no arguments — could not match one and silently
 * killed nothing, leaving Xvnc running after the user pressed stop.
 *
 * The display is compared as a whole argument, not a substring, or stopping
 * `:1` would take `:10` and `:11` down with it.
 */
internal fun xvncMatches(cmdline: String, display: Int?): Boolean {
    // Two shapes are kill candidates: a plain Xvnc argv, and a proot HOST
    // process — whose argv[0] is libproot.so with the whole guest launch
    // script embedded, which is how every proot-launched Xvnc appears on the
    // host side (the old argv[0]-only match made the orphan sweep a silent
    // no-op for them, #551). Anything else that merely mentions Xvnc (a grep,
    // an echo) fails the argv[0] gate and is left alone.
    val argv = cmdline.trim().split(' ').filter { it.isNotEmpty() }
    val program = argv.firstOrNull()?.substringAfterLast('/') ?: return false
    val isPlainXvnc = program.startsWith("Xvnc")
    val isProotHost = program.contains("libproot")
    if (!isPlainXvnc && !isProotHost) return false
    if (display == null) return isPlainXvnc || cmdline.contains("Xvnc")
    // Whole-token display compare: ":1" must not claim ":10".
    val token = "Xvnc :$display"
    val idx = cmdline.indexOf(token)
    if (idx < 0) return false
    val after = idx + token.length
    return after >= cmdline.length || !cmdline[after].isDigit()
}
