package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalSessionManager"

/**
 * The `sh -c` wrapper that launches the picked session manager ([cmd], whose
 * first word is [bin]) and degrades gracefully so the tab never dies silently
 * (#294 — "pick tmux, open a proot shell, it exits instantly"). Two failure
 * modes are handled:
 *  - binary not installed → fall straight to a login shell.
 *  - installed but exits non-zero — tmux can't create its socket under proot,
 *    an unknown option on an old build, etc. — leave the error on screen (it's
 *    also captured in the connection log) and drop to a login shell instead of
 *    ejecting the user. tmux/zellij/screen/byobu all exit 0 on a normal detach
 *    or quit, so the fallback only trips on a real startup failure, not on the
 *    user ending the session.
 *
 * [cmd] is run (not exec'd) so the `||` fallback can fire; on a clean exit the
 * wrapper simply ends and the PTY closes as before.
 */
/**
 * Exec a login shell in the guest, preferring `bash` when the distro ships one.
 *
 * `/bin/sh` was execed unconditionally, which on Debian is dash and on
 * Alpine/Void is busybox — so a guest with bash installed still dropped the
 * user into a minimal shell every time (#501). The test is made in the guest
 * rather than by looking for a binary from the app, so it holds for any distro
 * added under #162 without a per-distro table, and it falls back to exactly
 * the previous behaviour when there is no bash.
 */
internal const val GUEST_LOGIN_SHELL: String =
    "exec \"${'$'}(command -v bash 2>/dev/null || echo /bin/sh)\" -l"

internal fun sessionManagerWrapper(bin: String, cmd: String): String =
    "if command -v $bin >/dev/null 2>&1; then " +
        "$cmd || { echo \"[haven] $bin exited unexpectedly - falling back to a shell\" >&2; $GUEST_LOGIN_SHELL; }; " +
        "else $GUEST_LOGIN_SHELL; fi"

/**
 * Merge [overlay] into [base] env entries with KEY-OVERRIDE semantics: an
 * overlay key replaces the matching base `KEY=...` entry rather than relying on
 * execve duplicate-key ordering (which is libc-defined). New keys append. Used
 * to point a local shell at a running desktop's display sockets (#285).
 */
internal fun mergeEnv(base: Array<String>, overlay: Map<String, String>): Array<String> {
    val merged = LinkedHashMap<String, String>(base.size + overlay.size)
    for (entry in base) {
        val eq = entry.indexOf('=')
        if (eq < 0) merged[entry] = "" else merged[entry.substring(0, eq)] = entry.substring(eq + 1)
    }
    overlay.forEach { (k, v) -> merged[k] = v }
    return merged.map { (k, v) -> "$k=$v" }.toTypedArray()
}

/**
 * Manages active local terminal sessions.
 * Follows the same lifecycle pattern as MoshSessionManager.
 */
@Singleton
class LocalSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val prootManager: ProotManager,
    val desktopManager: DesktopManager,
    val systemVmManager: SystemVmManager,
    val guestServiceManager: GuestServiceManager,
    val audioBridge: AudioBridge,
    private val preferences: UserPreferencesRepository,
    private val connectionLog: ConnectionLogRepository,
) {

    /**
     * Latest user-configured session manager (tmux/zellij/screen/byobu/none).
     * Cached from the preferences flow on a private scope so [buildCommand]
     * can read it synchronously. Defaults to NONE until the first emission.
     *
     * Why this matters: when Haven is killed (process crash, force-stop,
     * Android lifecycle), every PRoot child dies — including any agent
     * the user was running inside the local session. Wrapping the shell
     * in a tmux/zellij/screen session means the next time Haven launches
     * the same profile, `tmux new-session -A` re-attaches if the server
     * survived (it does in some lifecycles) or starts cleanly otherwise,
     * preserving scrollback and process state across restarts.
     */
    @Volatile
    private var sessionManager: UserPreferencesRepository.SessionManager =
        UserPreferencesRepository.SessionManager.NONE

    /** LANG exported into newly-opened local shells (#282). */
    @Volatile
    private var terminalLocale: String = UserPreferencesRepository.DEFAULT_TERMINAL_LOCALE

    private val prefScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        prefScope.launch {
            preferences.sessionManager.collect { sessionManager = it }
        }
        prefScope.launch {
            preferences.terminalLocale.collect { terminalLocale = it }
        }
    }

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val localSession: LocalSession? = null,
        val useAndroidShell: Boolean = false,
        /** Distro to open the proot shell in; null = the active distro. */
        val prootDistroId: String? = null,
        /**
         * Extra env merged (key-override) into the proot launch so this shell
         * joins a running desktop session — DISPLAY / WAYLAND_DISPLAY /
         * XDG_RUNTIME_DIR (#285). Null = a plain shell. Resolved by the
         * suspend caller via [resolveDesktopEnv]; ignored for Android shells.
         */
        val desktopEnv: Map<String, String>? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    fun registerSession(
        profileId: String,
        label: String,
        useAndroidShell: Boolean = false,
        prootDistroId: String? = null,
        desktopEnv: Map<String, String>? = null,
    ): String {
        // Reap any superseded dead shells for this profile before minting a
        // fresh one. A local shell never reconnects, so a DISCONNECTED entry is
        // dead forever — its only value is letting the agent read the final
        // output, which is moot once a new shell opens on the same profile.
        // Without this, every disconnect→reconnect (or a proot stream drop that
        // exits the PTY via LocalSession.onExited) leaves the old DISCONNECTED
        // session and its ring behind, piling up in list_sessions.
        reapDeadSessionsForProfile(profileId)
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                useAndroidShell = useAndroidShell,
                prootDistroId = prootDistroId,
                desktopEnv = desktopEnv,
            ))
        }
        return sessionId
    }

    /**
     * Remove DISCONNECTED (process-exited) local sessions for [profileId] and
     * free their agent-scrollback rings. Live (CONNECTING / CONNECTED) sessions
     * are untouched. Called from [registerSession] so dead shells for the
     * profile being (re)opened don't accumulate in the session list.
     */
    private fun reapDeadSessionsForProfile(profileId: String) {
        val deadIds = _sessions.value.values
            .filter { it.profileId == profileId && it.status == SessionState.Status.DISCONNECTED }
            .map { it.sessionId }
        if (deadIds.isEmpty()) return
        _sessions.update { map -> map - deadIds.toSet() }
        deadIds.forEach { agentMirror.remove(it) }
    }

    /**
     * Mark a session as connected. The actual process starts when
     * [createTerminalSession] is called.
     */
    fun connectSession(sessionId: String) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = SessionState.Status.CONNECTED))
        }
    }

    /**
     * Returns the busybox sh argv for this session, optionally wrapped
     * in the user's chosen session manager. The wrapper uses
     * `command -v <bin>` so that picking tmux/zellij/etc without the
     * binary installed in the rootfs falls back to a plain login shell
     * instead of leaving the user with a dead PTY. The wrapper `exec`s
     * the session-manager binary so signals (SIGWINCH, SIGHUP) reach
     * tmux directly, not through an intermediate sh.
     *
     * When [plain] is true the user's `sessionManager` preference is
     * ignored entirely and a bare login shell is execed — needed for
     * agent-driven test sessions that depend on Haven's own scrollback
     * ring being filled (which a multiplexer's DECSTBM-based status bar
     * defeats).
     */
    private fun sessionManagerShellArgs(sessionName: String, plain: Boolean = false): Array<String> {
        // Use /bin/sh rather than /bin/busybox: Alpine's /bin/sh is a
        // symlink to busybox, Debian's is a symlink to dash, both
        // accept -l / -c with the same semantics. Hardcoding busybox
        // breaks any non-Alpine distro added under issue #162.
        if (plain) return arrayOf("/bin/sh", "-c", GUEST_LOGIN_SHELL)
        val mgr = sessionManager
        val template = mgr.command
        if (template == null) {
            return arrayOf("/bin/sh", "-c", GUEST_LOGIN_SHELL)
        }
        // No '.' or ':' — tmux treats them as session:window.pane separators (#358).
        val sanitizedName = sessionName.replace(Regex("[^A-Za-z0-9_-]"), "-")
        val cmd = template(sanitizedName)
        // First word of the command is the binary we test for. tmux,
        // zellij, screen, byobu — all single-word executables.
        val bin = cmd.substringBefore(' ')
        return arrayOf("/bin/sh", "-l", "-c", sessionManagerWrapper(bin, cmd))
    }

    /**
     * Build the shell command for a local session.
     * Uses proot if a rootfs is installed, otherwise falls back to /system/bin/sh.
     *
     * When [plain] is true the session-manager wrapper (tmux/zellij/screen/byobu)
     * is skipped and a bare login shell is execed — see
     * [sessionManagerShellArgs].
     */
    /**
     * `(id, label)` for every installed distro — the choices a LOCAL
     * profile's distro picker offers (#per-distro-local). Empty when no
     * rootfs is installed (the picker then just isn't shown).
     */
    fun installedDistros(): List<Pair<String, String>> =
        prootManager.installedDistros.map { it.id to it.label }

    fun buildCommand(
        useAndroidShell: Boolean = false,
        plain: Boolean = false,
        distroId: String? = null,
    ): Triple<String, Array<String>, Array<String>> {
        val prootBinary = prootManager.prootBinary
        // A LOCAL profile may pin a specific distro; otherwise follow the
        // global active distro (the original behaviour). If the pinned
        // distro isn't installed, fall through to the Android-shell branch
        // rather than launching a broken rootfs.
        val targetDistro = distroId ?: prootManager.activeDistroId

        return if (!useAndroidShell && prootBinary != null && prootManager.isRootfsInstalledFor(targetDistro)) {
            // PRoot with the target distro's rootfs
            val rootfsDir = prootManager.rootfsDirFor(targetDistro)

            // Android has no /etc/resolv.conf, so Haven owns the guest's. Rewrite it
            // on every launch from the configured DNS mode (#446) so changing the
            // setting takes effect without reinstalling the distro.
            prootManager.applyResolvConf(rootfsDir)
            val cmd = prootBinary
            // Append a session-manager wrapper if the user picked one
            // (tmux/zellij/screen/byobu). The wrapper falls back to a
            // plain login shell if the binary isn't installed in the
            // rootfs, so picking tmux without `apk add tmux` degrades
            // to today's behaviour rather than failing the session.
            val shellArgs: Array<String> = sessionManagerShellArgs("haven-local", plain = plain)
            // Writable /dev/shm overlay (Android's /dev is read-only with no shm),
            // matching the one-shot (runCommandInProot) and desktop launch paths.
            val devShm = prootManager.ensureDevShm()
            // #300: shift privileged (<1024) binds up by +2000 so guest
            // services on low ports are reachable despite Android blocking
            // the app uid from binding them. Opt-in (default off).
            val portRemap = if (prootManager.remapLowPorts) arrayOf("-p") else emptyArray()
            // #301: opt out of exposing the user's shared storage to the guest.
            // Same binds the desktop launchers use (#420) — see storageBindShortArgs.
            val storageBinds = prootManager.storageBindShortArgs().toTypedArray()
            // #301: per-distro user-defined extra binds (any Android path into
            // the guest). Keyed on the distro this session actually runs.
            val customBinds = prootManager.customBindShortArgs(targetDistro).toTypedArray()
            val args = arrayOf(
                prootBinary,
                "-0",                    // fake root
                "--link2symlink",        // fix link() for X11 lock files
                "--sysvipc",             // #375: SysV IPC emulation (fakeroot/makepkg)
                // #325: foreign-arch rootfs runs through the bundled qemu-user
                // loader. Empty for host-arch rootfses.
                *prootManager.qemuUserArgs(targetDistro).toTypedArray(),
                *portRemap,
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                // Device nodes Android's read-only /dev lacks. Without these the
                // interactive shell can't do POSIX shared memory or /dev/fd I/O,
                // so apps (browsers, .NET/Mono, anything using shm_open) fail —
                // these are the same binds the one-shot/desktop paths already use
                // on this proot binary (#266).
                "-b", "/dev/urandom:/dev/random",
                "-b", "$devShm:/dev/shm",
                "-b", "/proc/self/fd:/dev/fd",
                "-b", "/proc/self/fd/0:/dev/stdin",
                "-b", "/proc/self/fd/1:/dev/stdout",
                "-b", "/proc/self/fd/2:/dev/stderr",
                "-b", "/proc",
                "-b", "/sys",
                // Mask the guest's /sys/fs/selinux: Android is SELinux-enforcing,
                // and exposing enforce=1 makes guest coreutils fail cp -Z/mkdir -Z/
                // restorecon in package postinst scripts (e.g. openssh-server → no
                // /etc/ssh/sshd_config, #283). Matches the one-shot install path.
                "-b", prootManager.selinuxMaskBind(rootfsDir),
                *storageBinds,
                *customBinds,
                "-b", "${context.cacheDir.absolutePath}:/tmp",
                // #304: surface the device model at the devicetree path fastfetch reads.
                *(prootManager.deviceModelDevicetreeBind()?.let { arrayOf("-b", it) } ?: emptyArray()),
                // #304 part 2: optionally expose Android's system partitions (opt-in).
                *prootManager.androidSystemBindArgs(longForm = false),
                "-w", "/root",
            ) + shellArgs
            val env = arrayOf(
                "HOME=/root",
                "USER=root",
                "TERM=xterm-256color",
                "LANG=$terminalLocale",
                // LC_ALL outranks LANG for every locale category, and older seeded
                // .profiles force LC_ALL=C.UTF-8 when unset — set it too so the
                // user's chosen locale actually takes effect (#374).
                "LC_ALL=$terminalLocale",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "SHELL=/bin/sh",
                // XDG base dirs so desktop/X11 apps and `startx`/dbus-run-session
                // launched from the shell find their config/data and a writable
                // runtime dir instead of erroring (#261). RUNTIME_DIR == /tmp,
                // which is the cacheDir bind (writable).
                "XDG_RUNTIME_DIR=/tmp",
                "XDG_DATA_HOME=/root/.local/share",
                "XDG_DATA_DIRS=/usr/local/share:/usr/share",
                "XDG_CONFIG_HOME=/root/.config",
                "XDG_CACHE_HOME=/root/.cache",
                "PROOT_TMP_DIR=${context.cacheDir.absolutePath}",
                "PROOT_LOADER=${java.io.File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath}",
                "PROOT_L2S_DIR=${prootManager.l2sEnvValue(targetDistro)}",
            )
            Triple(cmd, args, env)
        } else {
            // Fallback: plain Android shell
            val cmd = "/system/bin/sh"
            // The PTY inherits the app process cwd ("/"), which an unprivileged
            // app can't list — users landed somewhere `ls` failed until they cd'd
            // (#270). Start the login shell in HOME (app-private, readable) instead.
            val home = context.filesDir.absolutePath
            val args = arrayOf(cmd, "-c", "cd '$home' 2>/dev/null; exec /system/bin/sh -l")
            val env = arrayOf(
                "HOME=${context.filesDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=$terminalLocale",
                "LC_ALL=$terminalLocale",
                "PATH=/system/bin:/vendor/bin",
                "SHELL=/system/bin/sh",
                "TMPDIR=${context.cacheDir.absolutePath}",
            )
            Triple(cmd, args, env)
        }
    }

    /**
     * Resolve the desktop client env (DISPLAY / WAYLAND_DISPLAY /
     * XDG_RUNTIME_DIR) for a running desktop [deId], for passing as
     * [registerSession]'s `desktopEnv` so the shell joins that desktop (#285).
     * Null when [deId] is null. Throws if the id is unknown or not running.
     * Suspend (the Wayland socket name is probed in-guest), so callers must be
     * suspend — done in the UI/MCP entry points, not [createTerminalSession].
     */
    suspend fun resolveDesktopEnv(deId: String?): Map<String, String>? {
        if (deId == null) return null
        val de = ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
            ?: throw IllegalArgumentException("Unknown desktop id: $deId")
        return desktopManager.resolveClientEnv(de)
    }

    /**
     * Create a [LocalSession] for a connected session.
     * The PTY process starts immediately and output flows via [onDataReceived].
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
        rows: Int = 24,
        cols: Int = 80,
        plain: Boolean = false,
    ): LocalSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.localSession != null) return null

        val (cmd, args, baseEnv) = buildCommand(session.useAndroidShell, plain = plain, distroId = session.prootDistroId)
        // Join a running desktop session (#285): override DISPLAY / WAYLAND_DISPLAY
        // / XDG_RUNTIME_DIR so the shell can drive the desktop's apps. The desktop's
        // sockets are in the shared cacheDir (bound at /tmp), so only the env needs
        // pointing. Irrelevant for Android shells (no proot /tmp bind).
        val env = session.desktopEnv
            ?.takeIf { it.isNotEmpty() && !session.useAndroidShell }
            ?.let { mergeEnv(baseEnv, it) }
            ?: baseEnv

        // Permanent agent-scope mirror of PTY stdout, wired here so EVERY local
        // shell — UI-opened or headless — feeds the ring that
        // read_terminal_scrollback consumes (matches SshSessionManager's
        // permanent onMirror tee). Previously only startHeadlessShell created
        // the ring, so a shell the user opened by tapping Terminal had no ring
        // and the agent saw "No scrollback available".
        val localSession = LocalSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            command = cmd,
            args = args,
            env = env,
            onDataReceived = agentMirror.mirror(sessionId, onDataReceived),
            onExited = { exitCode ->
                Log.d(TAG, "Session $sessionId process exited: $exitCode")
                // Mark DISCONNECTED and drop the dead LocalSession in one
                // update so sendInput / getActiveSession stop routing to a
                // closed fd (which would swallow writes and report a false
                // success). The agent ring is left intact so the
                // final output stays readable after the process exits.
                _sessions.update { map ->
                    val existing = map[sessionId] ?: return@update map
                    existing.localSession?.close()
                    map + (sessionId to existing.copy(
                        status = SessionState.Status.DISCONNECTED,
                        localSession = null,
                    ))
                }
                // Surface the exit in the connection log so an immediately
                // exiting local shell — e.g. a session manager that won't
                // start (#294) — is diagnosable via Settings → View connection
                // log, not just a release-stripped Log.d. The scrollback tail
                // usually carries the actual reason (a tmux/zellij error, a
                // "command not found", a broken profile script).
                val tail = agentMirror.tail(sessionId)
                prefScope.launch {
                    connectionLog.logEvent(
                        profileId = session.profileId,
                        status = if (exitCode == 0) ConnectionLog.Status.DISCONNECTED
                        else ConnectionLog.Status.FAILED,
                        details = "Local shell exited (exit code $exitCode)",
                        verboseLog = tail,
                    )
                }
            },
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(localSession = localSession))
        }

        return localSession
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.localSession == null
    }

    /**
     * The live [LocalSession] for [sessionId], or null if none is attached.
     * Distinct from [isReadyForTerminal]: this returns the session that's
     * *already* attached (e.g. an agent-side headless shell started by
     * `open_local_shell`), letting the UI tab adopt it instead of forking
     * a second PTY for the same session.
     */
    fun getActiveSession(sessionId: String): LocalSession? =
        _sessions.value[sessionId]?.localSession

    /**
     * Detach the terminal UI from a local session WITHOUT killing the proot
     * PTY. Called when the Activity (and its TerminalViewModel) is torn down
     * while the process stays alive — background under memory pressure, "Don't
     * keep activities", etc. Mirrors SSH, whose detach keeps the connection so
     * a new ViewModel can [reattachTerminalSession]; previously this closed the
     * PTY, so the shell + any running process were lost and the user came back
     * to a blank, freshly-spawned shell (#272).
     *
     * The live emulator is gone with the old ViewModel, so the output sink is
     * rewired to feed only the scrollback ring during the gap — those bytes are
     * replayed into the new emulator on reattach. localSession is kept non-null
     * (so [isReadyForTerminal] stays false and the next ViewModel reattaches
     * rather than forking a second shell).
     */
    fun detachTerminalSession(sessionId: String) {
        // No-op by design (#272): the proot PTY is intentionally kept running and
        // localSession kept non-null, so a new TerminalViewModel reattaches via
        // [reattachTerminalSession] instead of the shell being killed and
        // restarted blank. The old emulator's callback is left in place — it
        // harmlessly feeds the now-dead emulator (the PTY reader guards against
        // it throwing) and the persistent scrollback ring until reattach swaps
        // it. Agent-owned (headless) sessions are likewise undisturbed, so the
        // registry emulator the agent reads keeps updating.
    }

    /**
     * Rewire a detached [LocalSession] to a new emulator pipeline after the
     * Activity/ViewModel was recreated (#272). Same PTY/shell; re-establishes
     * the scrollback-ring mirror plus the new UI sink. Returns the live session,
     * or null if none is attached. Pair with [snapshotScrollback] to replay the
     * buffered output into the fresh emulator so the screen is restored.
     */
    fun reattachTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): LocalSession? {
        val live = _sessions.value[sessionId]?.localSession ?: return null
        live.replaceDataCallback(agentMirror.mirror(sessionId, onDataReceived))
        return live
    }

    /**
     * The bytes buffered in [sessionId]'s scrollback ring (raw PTY output), or
     * null if empty — replayed into a fresh emulator on reattach (#272).
     */
    fun snapshotScrollback(sessionId: String): ByteArray? =
        agentMirror.snapshot(sessionId)

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        agentMirror.remove(sessionId)
        ioExecutor.execute {
            try {
                session.localSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    // --- Agent transport entry points -----------------------------------
    //
    // The MCP open_local_shell tool wants to (a) start a real PTY without
    // a UI tab on top of it and (b) read/write that PTY through stable
    // sessionId-keyed APIs that mirror the SSH side. The trio below
    // (agent scrollback ring + headless start + sendInput) provides that;
    // each piece is intentionally narrow so a future first-class "shell
    // session that renders nowhere" mode can subsume them cleanly.

    // The ring + tee maps and their byte cap live in [AgentSessionMirror],
    // shared with UmlGuestManager so console-side UI features land once for
    // both PTY transports instead of being rediscovered per transport.
    private val agentMirror = AgentSessionMirror()

    /**
     * Spin up the PTY for [sessionId] without an attached UI surface,
     * mirroring stdout into [AgentSessionMirror] so
     * [readAgentScrollback] can return what the agent would see.
     * Idempotent — if a [LocalSession] already exists for [sessionId]
     * (e.g. the user opened a terminal tab for it), this is a no-op.
     *
     * [extraOnData], when non-null, is invoked for every chunk of PTY
     * output alongside the agent scrollback ring. Lets a caller
     * (notably the MCP `open_local_shell` handler) tee the stream
     * into a [TerminalEmulator] without losing the byte-ring that
     * `read_terminal_scrollback` consumes.
     */
    fun startHeadlessShell(
        sessionId: String,
        extraOnData: ((ByteArray, Int, Int) -> Unit)? = null,
        plain: Boolean = false,
    ) {
        val session = _sessions.value[sessionId] ?: return
        // The agent-emulator tee is registered in [AgentSessionMirror] so it
        // survives
        // reattachTerminalSession's callback swap; the create/reattach mirrors
        // invoke it alongside the scrollback ring. Registered even when a UI
        // tab already owns the LocalSession (the early return below) — the
        // mirrors look it up dynamically, so the agent emulator starts
        // receiving from here on (blank until then; no replay).
        extraOnData?.let { agentMirror.setTee(sessionId, it) }
        if (session.localSession != null) return
        val ls = createTerminalSession(
            sessionId,
            onDataReceived = { _, _, _ -> },
            plain = plain,
        ) ?: return
        // Default to a sensible PTY size; the UI will resize once a tab
        // attaches. 80x24 keeps line wrapping predictable for an agent
        // that's about to do `printf` glyph tests.
        ls.start(rows = 24, cols = 80)
    }

    /**
     * Send [text] as UTF-8 to the PTY for [sessionId]. Throws when no
     * session exists or no [LocalSession] is attached — the agent
     * transport surfaces those as JSON-RPC errors.
     */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("No local session: $sessionId")
        val localSession = session.localSession
            ?: throw IllegalStateException(
                "Local session $sessionId has ended — open_local_shell again")
        // The reader thread may have hit EOF (process exited) a beat before
        // onExited cleared the reference; reject loudly rather than writing
        // into a closed fd that swallows the bytes and reports false success.
        if (!localSession.isAlive()) {
            throw IllegalStateException(
                "Local session $sessionId has ended — open_local_shell again")
        }
        localSession.sendInput(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Suspend until the agent scrollback ring for [sessionId] has received
     * its first bytes (the shell printed its prompt/banner) or [timeoutMs]
     * elapses. Returns true if any output arrived. Lets `open_local_shell`
     * hand back a session that's immediately usable instead of racing the
     * asynchronously-started PTY reader thread.
     */
    suspend fun awaitFirstOutput(sessionId: String, timeoutMs: Long = 1500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun hasOutput() = agentMirror.hasOutput(sessionId)
        while (System.currentTimeMillis() < deadline) {
            if (hasOutput()) return true
            // A shell that exits instantly (e.g. a broken env) never prints a
            // prompt — stop waiting once it's no longer connected.
            val status = _sessions.value[sessionId]?.status
            if (status != null && status != SessionState.Status.CONNECTED) break
            delay(40)
        }
        return hasOutput()
    }

    /**
     * Read the most recent [maxBytes] of stdout from the agent-scope
     * ring for [sessionId], or null if no ring exists yet (no headless
     * or UI tab has run on this session).
     */
    fun readAgentScrollback(sessionId: String, maxBytes: Int): ByteArray? =
        agentMirror.read(sessionId, maxBytes)

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { session ->
                try {
                    session.localSession?.close()
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
                    session.localSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    /**
     * Kill every proot process tree this app launched that no live session
     * closed — the orphan a detached local shell leaves behind, and the root
     * of "proot is hard to kill" (#409/#411). proot launchers are forkpty
     * children of this process (comm `libproot.so`); each is signalled with its
     * ptrace tracees, which proot's own teardown doesn't reap.
     *
     * Reaps unconditionally, so the caller MUST first rule out anything
     * legitimately holding the guest up (a running guest service, System VM, or
     * desktop). Returns the number of proot trees reaped.
     */
    fun killOrphanedGuestProot(): Int {
        val myPid = android.os.Process.myPid()
        val launchers = descendantPidsOf(myPid).filter { commOf(it) == "libproot.so" }
        for (pid in launchers) {
            val tracees = runCatching { descendantPidsOf(pid) }.getOrDefault(emptyList())
            runCatching { android.os.Process.killProcess(pid) }
            tracees.forEach { runCatching { android.os.Process.killProcess(it) } }
        }
        if (launchers.isNotEmpty()) Log.d(TAG, "reaped ${launchers.size} orphaned proot tree(s): $launchers")
        return launchers.size
    }

    /**
     * Send a VNC start command to an active PRoot session's PTY.
     */
    fun startVncInSession(profileId: String) {
        val session = _sessions.value.values
            .find { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?: return
        session.localSession?.sendInput(
            "vncserver :1 2>&1 &\n".toByteArray()
        )
        Log.d(TAG, "Sent VNC start command to session ${session.sessionId}")
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }
}
