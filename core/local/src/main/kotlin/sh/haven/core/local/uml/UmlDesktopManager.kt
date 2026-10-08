package sh.haven.core.local.uml

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.haven.core.local.DesktopManager
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UmlDesktop"

/**
 * Desktops inside the UML guest: the mirror of [DesktopManager] for a
 * rootfs it can't touch. A proot desktop is a set of processes in the
 * app's namespace; a UML desktop runs inside its own kernel, so the whole
 * lifecycle goes through [UmlGuestManager] — register a session booted
 * with `haven.desktop=1` and `PASST_TFWD=<port>`, write the recipe into
 * the hostfs share, and drive the guest's console with one typed command.
 *
 * One desktop at a time: each is a full guest (2 GiB mem cap —
 * UmlGuestManager), unlike proot where desktops are neighbours in one
 * namespace. Console parking, package sets and the start commands are the
 * stage-1 device-verified recipe from docs/plans/uml-dm.md, moved into a
 * script this manager writes into the share before every action.
 */
@Singleton
class UmlDesktopManager @Inject constructor(
    private val guestManager: UmlGuestManager,
    private val desktopManager: DesktopManager,
) {

    enum class Kind(val id: String, val title: String) {
        /** Xvnc + openbox + xterm, stage-1's verified X11 stack. */
        X11("x11", "X11"),

        /** Headless sway + wayvnc + foot, stage-1's verified Wayland stack. */
        WAYLAND("wayland", "Wayland"),
    }

    /**
     * Pref group under which [DesktopManager.setPortPreference] pins are
     * written for UML desktops (`uml_<kind>`); DesktopManager's port
     * suggestion scans all pref keys, so proot installs stay clear of them.
     */
    private val portGroup = "uml"

    data class DesktopState(
        val kind: Kind,
        val installed: Boolean = false,
        val status: Status = Status.STOPPED,
        val port: Int? = null,
        val error: String? = null,
    ) {
        enum class Status {
            /** Recipe packages not installed, or the guest is down. */
            STOPPED,
            /** Boot + apk add in flight. */
            INSTALLING,
            /** Boot + recipe start script + RFB wait in flight. */
            STARTING,
            RUNNING,
            ERROR,
        }
    }

    private val _state = MutableStateFlow<Map<Kind, DesktopState>>(emptyMap())
    val state: StateFlow<Map<Kind, DesktopState>> = _state.asStateFlow()

    val isAvailable: Boolean
        get() = guestManager.isAvailable()

    init {
        _state.value = Kind.values().associate { kind ->
            kind to DesktopState(kind = kind, installed = markerFor(kind).exists())
        }
    }

    private fun markerFor(kind: Kind): File =
        File(guestManager.guestShareDir, "haven-desktop-${kind.id}.ok")

    private fun update(kind: Kind, block: (DesktopState) -> DesktopState) {
        _state.update { map ->
            val current = map[kind] ?: DesktopState(kind = kind)
            map + (kind to block(current))
        }
    }

    // ---------------------------------------------------------------- actions

    /**
     * Reap after an app restart: a UML kernel dies with the process, so a
     * session that isn't registered anymore means STOPPED, regardless of
     * what the last run left behind.
     */
    private fun markStopped(kind: Kind) {
        update(kind) {
            DesktopState(
                kind = kind,
                installed = markerFor(kind).exists(),
                status = DesktopState.Status.STOPPED,
                port = null,
            )
        }
    }

    fun installDesktop(kind: Kind) {
        if (!beginBusy(kind, DesktopState.Status.INSTALLING)) return
        writeRecipeScript()
        driveJobs[kind] = ioScope.launch { runInstall(kind) }
    }

    fun startDesktop(kind: Kind) {
        if (!beginBusy(kind, DesktopState.Status.STARTING)) return
        val port = pickPort(kind)
        // Sticky, like a proot install-time pin: the next start, and every
        // proot install suggestion, scans this same shared pref store.
        desktopManager.setPortPreference(portGroup, kind.id, port)
        writeRecipeScript()
        driveJobs[kind] = ioScope.launch { runStart(kind, port) }
    }

    fun stopDesktop(kind: Kind) {
        when (state.value[kind]?.status) {
            DesktopState.Status.RUNNING -> {
                // Console sits at its login-shell prompt (the recipe
                // backgrounds its servers and exits rather than holding the
                // console); closeGuest types poweroff and falls back to the
                // hard kill in LocalSession.close().
                ioScope.launch {
                    sessionIds.remove(kind)?.let { guestManager.closeGuest(it) }
                    markStopped(kind)
                }
            }
            DesktopState.Status.STARTING, DesktopState.Status.INSTALLING -> {
                driveJobs.remove(kind)?.cancel()
                ioScope.launch {
                    sessionIds.remove(kind)?.let { guestManager.closeGuest(it) }
                    markStopped(kind)
                }
            }
            else -> markStopped(kind)
        }
    }

    /** True when the action takes the slot; false leaves state untouched. */
    private fun beginBusy(kind: Kind, target: DesktopState.Status): Boolean {
        val busy = _state.value.values.any {
            it.status == DesktopState.Status.INSTALLING ||
                it.status == DesktopState.Status.STARTING
        }
        val running = _state.value.values.any { it.status == DesktopState.Status.RUNNING }
        if (busy || running || !isAvailable) return false
        update(kind) { it.copy(status = target, error = null) }
        return true
    }

    // ---------------------------------------------------------------- drivers

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val driveJobs = mutableMapOf<Kind, Job>()
    private val sessionIds = mutableMapOf<Kind, String>()

    private suspend fun runStart(kind: Kind, port: Int) {
        try {
            val sessionId = bootGuest(
                profileId = "uml-desktop-${kind.id}",
                label = "UML ${kind.title} desktop",
                extraEnv = listOf("PASST_TFWD=$port"),
            )
            try {
                val console = startConsole(sessionId)
                    ?: throw IllegalStateException("guest console didn't come up")
                awaitConsolePrompt(console, sessionId, timeoutMs = 45_000)
                guestManager.sendInput(sessionId, recipeLine("start ${kind.id} $port"))
                val marker = awaitAnyMarker(console, sessionId, "HDESKTOP:", timeoutMs = 120_000)
                if (marker != "HDESKTOP:started") {
                    throw IllegalStateException(
                        "start script did not come up. Last console output:\n${console.tail(2_000)}",
                    )
                }
                awaitRfb(sessionId, port, timeoutMs = 60_000)
                update(kind) { it.copy(status = DesktopState.Status.RUNNING, port = port) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(kind, "Desktop start failed", e, sessionId)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(kind, "Guest boot failed", e, null)
        }
    }

    private suspend fun runInstall(kind: Kind) {
        try {
            val sessionId = bootGuest(
                profileId = "uml-install-${kind.id}",
                label = "UML ${kind.title} install",
                extraEnv = emptyList(),
            )
            try {
                val console = startConsole(sessionId)
                    ?: throw IllegalStateException("guest console didn't come up")
                awaitConsolePrompt(console, sessionId, timeoutMs = 45_000)
                guestManager.sendInput(sessionId, recipeLine("install ${kind.id}"))
                val marker = awaitAnyMarker(console, sessionId, "HDESKTOP:", timeoutMs = 600_000)
                if (marker != "HDESKTOP:done" || !markerFor(kind).exists()) {
                    fail(
                        kind,
                        "Package install failed" + if (marker == null) " (timed out)" else "",
                        null,
                        sessionId,
                    )
                } else {
                    update(kind) { it.copy(status = DesktopState.Status.STOPPED, installed = true) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(kind, "Install failed", e, sessionId)
            } finally {
                guestManager.closeGuest(sessionId)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(kind, "Guest boot failed", e, null)
        }
    }

    /** registerSession + connectSession; the kernel boots when the pty starts. */
    private fun bootGuest(profileId: String, label: String, extraEnv: List<String>): String {
        val sessionId = guestManager.registerSession(
            profileId = profileId,
            label = label,
            extraKernelArgs = listOf(DESKTOP_KERNEL_ARG),
            extraEnv = extraEnv,
        )
        guestManager.connectSession(sessionId)
        return sessionId
    }

    /** Headless pty with its output teed into a [ConsoleLog]. */
    private fun startConsole(sessionId: String): ConsoleLog? {
        val console = ConsoleLog()
        guestManager.startHeadlessShell(sessionId, console::onBytes)
        if (guestManager.getActiveSession(sessionId) == null) return null
        return console
    }

    // ------------------------------------------------------------ console waits

    /**
     * Wait for the login-shell prompt without knowing its exact text: the
     * guest parks at `uml:~#` (haven.desktop=1 gates the agent launcher
     * off), so wait for a line ending in `#` that stays unchanged for
     * a beat. Kernel boot noise rarely ends in #, and the recipe output
     * that follows doesn't.
     */
    private suspend fun awaitConsolePrompt(
        console: ConsoleLog,
        sessionId: String,
        timeoutMs: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionDead(sessionId)) {
                throw IllegalStateException("guest exited during boot:\n${console.tail(1_000)}")
            }
            val tail = console.tail(120)
            if (atPrompt(tail)) {
                delay(700)
                if (console.tail(120) == tail) return
            } else {
                delay(200)
            }
        }
        throw IllegalStateException("no console prompt within ${timeoutMs / 1000}s")
    }

    /**
     * First [prefix]-marked line to appear in the console within
     * [timeoutMs]; null on deadline or guest exit.
     */
    private suspend fun awaitAnyMarker(
        console: ConsoleLog,
        sessionId: String,
        prefix: String,
        timeoutMs: Long,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionDead(sessionId)) return null
            console.firstMarker(prefix)?.let { return it }
            delay(500)
        }
        return null
    }

    /**
     * The RFB banner must really be served on [port]. passt's listeners
     * accept connects from boot onward, so a bare connect proves nothing;
     * only the RFB prefix back on the wire does (stage-0 and stage-1
     * finding).
     */
    private suspend fun awaitRfb(sessionId: String, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionDead(sessionId)) {
                throw IllegalStateException("guest exited before the desktop came up")
            }
            if (rfbBannerServed(port)) return
            delay(750)
        }
        throw IllegalStateException(
            "no VNC banner on 127.0.0.1:$port within ${timeoutMs / 1000}s " +
                "(last console lines and the guest's /host/haven-desktop.log " +
                "carry the cause)",
        )
    }

    private fun rfbBannerServed(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 1500)
            s.soTimeout = 1500
            val buf = ByteArray(4)
            var total = 0
            while (total < buf.size) {
                val n = s.getInputStream().read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            total == 4 && String(buf, 0, 4, Charsets.US_ASCII) == "RFB "
        }
    } catch (_: Exception) {
        false
    }

    private fun sessionDead(sessionId: String): Boolean =
        guestManager.sessions.value[sessionId]?.status?.let {
            it == UmlGuestManager.SessionState.Status.DISCONNECTED
        } ?: true

    /** ERROR state with the cause line first and (when held) the console tail after. */
    private fun fail(kind: Kind, base: String, e: Exception?, sessionId: String?) {
        Log.e(TAG, base, e)
        val tail = sessionId?.let { guestManager.readAgentScrollback(it, 2_000) }
            // agentMirror rings are wiped by closeGuest; a removed session's
            // bytes are gone. The console tail is best-effort here.
        update(kind) {
            it.copy(
                status = DesktopState.Status.ERROR,
                error = composeError(base, tail, e),
            )
        }
    }

    private fun composeError(base: String, tail: ByteArray?, e: Exception?): String {
        val sb = StringBuilder(base)
        e?.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
        if (tail != null) {
            val text = String(tail, Charsets.UTF_8).trimEnd()
            if (text.isNotBlank()) sb.append("\n-- console tail --\n").append(text)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ port pick

    /**
     * The sticky pref when set, else the allocator's suggestion, walked
     * past any port a live host-side listener already holds. A proot
     * desktop on an unpinned display is invisible to the pref scan — only
     * its actual listener proves it, and a colliding passt forward would
     * otherwise fail silently at bind time.
     */
    private fun pickPort(kind: Kind): Int {
        var port = desktopManager.getPortPreference(portGroup, kind.id)
            .takeIf { it in 5901..5999 }
            ?: desktopManager.suggestNextVncPort(portGroup)
        var hops = 0
        while (listenerHoldsLoopback(port) && hops < 50) {
            port++
            hops++
        }
        return port
    }

    /**
     * True when a listener already binds loopback on [port] (proot Xvnc,
     * a live passt forward, anything). A temporary ServerSocket bind is the
     * cheapest unambiguous test — wildcard listeners collide with a
     * loopback bind too, absent SO_REUSEPORT.
     */
    private fun listenerHoldsLoopback(port: Int): Boolean = try {
        ServerSocket(port, 0, InetAddress.getLoopbackAddress()).close()
        false
    } catch (_: Exception) {
        true
    }

    // ------------------------------------------------------------ the script

    /**
     * The guest recipe, written into [UmlGuestManager.guestShareDir] before
     * every action. The console only ever types one command; everything
     * else lives here so a recipe fix is an app update, not a rootfs one.
     */
    private fun writeRecipeScript() {
        guestManager.guestShareDir.apply { mkdirs() }
            .resolve(RECIPE_NAME)
            .writeText(RECIPE_SCRIPT)
    }

    internal companion object {
        /** $ as a val: a raw Kotlin template can't carry a bare $. */
        private const val DS = "$"

        /**
         * CSI/OSC escape sequences as they appear in console output. The
         * guest's shell trails its prompt with a DSR cursor query
         * (`ESC [ 6 n`) that the headless pty never answers, so the raw
         * tail ends in `6n` forever — device-verified on the first stage-2
         * run. Strip them before looking for the prompt.
         */
        private val ANSI = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)")

        /** True when the console tail ends at a root-shell prompt. */
        fun atPrompt(tail: String): Boolean =
            ANSI.replace(tail, "").trimEnd().endsWith("#")

        /** Kernel arg gating the agent launcher off (uml-guest-9 inittab hook). */
        private const val DESKTOP_KERNEL_ARG = "haven.desktop=1"

        /** Name under [UmlGuestManager.guestShareDir]; the guest runs /host/filename. */
        private const val RECIPE_NAME = "haven-desktop.sh"

        /**
         * The console line that runs the recipe. The image's inittab mounts
         * the share with `mount -t hostfs none /host || echo HOSTFSFAIL`,
         * but busybox init splits an action line on whitespace and runs it
         * without a shell — the `&&`/`||` chain never executes, which is the
         * "hostfs mount failed silently on every boot" the uml-transport
         * README records. Mount it here (idempotent: an already-mounted
         * share makes the mount fail harmlessly), and echo a fail marker
         * when the recipe still isn't visible rather than burning the whole
         * marker budget on the shell's "No such file or directory" (device,
         * 2026-10-08: the install reached the prompt, then could not open
         * /host/haven-desktop.sh).
         */
        fun recipeLine(args: String): String =
            "mount -t hostfs none /host 2>/dev/null; " +
                "[ -f /host/$RECIPE_NAME ] || echo 'HDESKTOP:fail /host share not mounted'; " +
                "sh /host/$RECIPE_NAME $args\r"

    /**
     * Written to the guest's /host share and run from the console. HDESKTOP:
     * markers are the app's completion signal; server logs land in
     * /host/haven-desktop.log so failures read out of the share, and the
     * console ring is short (kernel dmesg + apk progress fills it fast).
     *
     * Servers are BACKGROUNDed and the script exits, so the console returns
     * to the login-shell prompt where `poweroff` is meaningful — the guest's
     * console line discipline has ISIG off (stage-1), so nothing typed at a
     * process-holding console can interrupt it.
     */
    internal val RECIPE_SCRIPT = """#!/bin/sh
# Haven's UML desktop recipe, written by the app into the hostfs share.
#   sh /host/haven-desktop.sh install x11|wayland
#   sh /host/haven-desktop.sh start x11|wayland <port>
# Anything the app needs comes out as an HDESKTOP: marker.
set -u
APPLOG=/host/haven-desktop.log

x11_pkgs() {
    # font-misc-misc must be installed BEFORE Xvnc starts: Xvnc snapshots
    # its font path at startup, and xterm maps a bitmap font from it
    # (stage-1: xterm rendered unmapped glyphs without it).
    apk add --no-cache font-misc-misc tigervnc openbox xterm xsetroot xwininfo xprop font-noto
}
wayland_pkgs() {
    apk add --no-cache sway wayvnc foot jq font-noto
}

case "${DS}1" in
install)
    case "${DS}2" in
    x11)     x11_pkgs && touch /host/haven-desktop-x11.ok ;;
    wayland) wayland_pkgs && touch /host/haven-desktop-wayland.ok ;;
    *) echo "HDESKTOP:fail unknown kind: ${DS}2"; exit 1 ;;
    esac && echo HDESKTOP:done || echo "HDESKTOP:fail apk add exited nonzero"
    ;;
start)
    kind="${DS}2"; port="${DS}3"
    display=${DS}((port - 5900))
    case "${DS}kind" in
    x11)
        command -v Xvnc >/dev/null 2>&1 || { echo "HDESKTOP:fail Xvnc not installed"; exit 1; }
        # xprop joined the install set after the WM-ready wait; a guest
        # installed before that carries the .ok marker, so Install never
        # re-runs and the tool is missing. Self-heal here — without it the
        # wait below spins its full bound and xterm maps unmanaged again.
        command -v xprop >/dev/null 2>&1 || apk add --no-cache xprop >>"${DS}APPLOG" 2>&1
        # A torn-down previous run leaves its X lock on the persistent
        # rootfs; UML restarts PIDs from scratch each boot, so the stale
        # lock's pid can look live and Xvnc refuses the display. Clear it
        # before starting.
        rm -f "/tmp/.X${DS}display-lock" "/tmp/.X11-unix/X${DS}display"
        Xvnc ":${DS}display" -geometry 1280x720 -depth 24 -SecurityTypes None >>"${DS}APPLOG" 2>&1 &
        xvnc_pid=${DS}!
        sleep 2
        # If Xvnc died on startup the app-side RFB wait just burns its 60s
        # and the reason sits in the share log the console never shows.
        # Fail fast with the log tail on the console instead (stage-2
        # device run: "no VNC banner within 60s", cause unreadable).
        kill -0 ${DS}xvnc_pid 2>/dev/null || {
            echo "HDESKTOP:fail Xvnc died at startup"
            tail -20 "${DS}APPLOG"
            exit 1
        }
        (DISPLAY=:${DS}display openbox >>"${DS}APPLOG" 2>&1) &
        # xterm must map AFTER openbox has taken over the WM: mapped before
        # the WM grabs it the window stays IsUnMapped forever (stage-2
        # device run: black VNC screen with a live cursor, xterm alive but
        # unmapped). openbox sets _NET_SUPPORTING_WM_CHECK on the root when
        # it grabs; xprop prints "no such atom" until then. xprop ships in
        # x11_pkgs for exactly this wait.
        n=0
        while [ ${DS}n -lt 15 ]; do
            DISPLAY=:${DS}display xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' && break
            sleep 1; n=${DS}((n + 1))
        done
        (DISPLAY=:${DS}display xterm >>"${DS}APPLOG" 2>&1) &
        ;;
    wayland)
        command -v sway >/dev/null 2>&1 || { echo "HDESKTOP:fail sway not installed"; exit 1; }
        mkdir -p /tmp/wr
        (WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman \
            XDG_RUNTIME_DIR=/tmp/wr sway >>"${DS}APPLOG" 2>&1) &
        # wayvnc dies instantly without a compositor socket; wait for one.
        n=0
        while [ ${DS}n -lt 15 ] && ! ls /tmp/wr/wayland-* >/dev/null 2>&1; do
            sleep 1; n=${DS}((n + 1))
        done
        (XDG_RUNTIME_DIR=/tmp/wr wayvnc 0.0.0.0:${DS}port >>"${DS}APPLOG" 2>&1) &
        # the Wayland application the plan's done-when asks for
        (XDG_RUNTIME_DIR=/tmp/wr foot >>"${DS}APPLOG" 2>&1) &
        ;;
    *)
        echo "HDESKTOP:fail unknown kind: ${DS}kind"; exit 1 ;;
    esac
    echo HDESKTOP:started
    ;;
*)
    echo "HDESKTOP:fail usage: ${DS}0 install|start <kind> [<port>]"; exit 1
    ;;
esac
"""
    }

    /** Console ring for a headless session's tee. */
    internal class ConsoleLog {
        private val sb = StringBuilder()

        @Synchronized
        fun onBytes(bytes: ByteArray, off: Int, len: Int) {
            sb.append(String(bytes, off, len, Charsets.UTF_8))
            if (sb.length > 262_144) sb.delete(0, sb.length - 131_072)
        }

        @Synchronized
        fun tail(n: Int): String =
            if (sb.length <= n) sb.toString() else sb.substring(sb.length - n)

        @Synchronized
        fun contains(m: String): Boolean = sb.contains(m)

        /**
         * The first line in the ring beginning with [prefix]; markers only
         * count at line start so other text can't fake them.
         */
        @Synchronized
        fun firstMarker(prefix: String): String? {
            var at = 0
            while (true) {
                at = sb.indexOf(prefix, at)
                if (at < 0) return null
                if (at == 0 || sb[at - 1] == '\n' || sb[at - 1] == '\r') {
                    val end = sb.indexOf('\n', at).let { if (it < 0) sb.length else it }
                    return sb.substring(at, end).trim()
                }
                at++
            }
        }
    }
}