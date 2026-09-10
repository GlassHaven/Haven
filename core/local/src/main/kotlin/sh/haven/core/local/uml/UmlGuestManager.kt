package sh.haven.core.local.uml

import android.content.Context
import android.os.StatFs
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
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.terminal.ScrollbackRing
import sh.haven.core.local.LocalSession
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UmlGuestManager"

/**
 * Manages UML guest sessions — a real Linux kernel running as a user process
 * in Haven's own sandbox (PROTOTYPE.md). Sessions follow the LOCAL pattern:
 * the guest boots with its console on a pty forked by PtyBridge.nativeForkPty,
 * so every PTY plumbing (resize, reattach, agent ring) is reused unchanged.
 *
 * The host side runs libuml-net.so, which spawns passt and hands both ends of
 * a socketpair to the kernel's vec0 vector-fd transport. passt is killed by
 * PR_SET_PDEATHSIG when the kernel exits, so closeGuest()'s descendant sweep
 * in LocalSession.close() has nothing left behind.
 */
@Singleton
class UmlGuestManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionLog: ConnectionLogRepository,
) {

    sealed class SetupState {
        data object NotStaged : SetupState()
        data class Unpacking(val progress: Int) : SetupState()
        data object Ready : SetupState()
        data class Error(val phase: String, val message: String) : SetupState()
    }

    private val _state = MutableStateFlow<SetupState>(SetupState.NotStaged)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        _state.value = if (rootfsReady) SetupState.Ready else SetupState.NotStaged
    }

    /** The staged rootfs image; 512 MiB, the mkfs'd size of the asset. */
    val rootfsFile: File
        get() = File(context.filesDir, "uml/rootfs.ext4")

    private val rootfsReady: Boolean
        get() = rootfsFile.length() == ROOTFS_SIZE_BYTES

    /**
     * True when this build actually ships the guest payload. The terminal
     * flavour drops the UML libraries and the asset (app/build.gradle.kts
     * variant excludes), and F-Droid builds skip the fetch (fetch-uml.sh) —
     * both leave this false and the transport hidden.
     */
    fun isAvailable(): Boolean = sh.haven.core.data.NativeFeatures(context).uml

    /**
     * Stage the rootfs from the APK asset on first use. Idempotent: an intact
     * staged image (exact size) is reused and never re-unpacked, so user data
     * survives Haven updates. A partial write from an interrupted unpack is the
     * one thing re-staged. Throws when storage is short or the unpack fails.
     */
    suspend fun ensureRootfs() {
        _state.value = if (rootfsReady) SetupState.Ready else SetupState.NotStaged
        if (rootfsReady) return

        withContext(Dispatchers.IO) {
            try {
                val stat = StatFs(context.filesDir.absolutePath)
                val available = stat.availableBytes
                if (available < ROOTFS_FREE_SPACE_BYTES) {
                    throw IllegalStateException(
                        "Not enough free space for the guest rootfs: " +
                            "${available / (1024 * 1024)} MB available, " +
                            "need ${ROOTFS_FREE_SPACE_BYTES / (1024 * 1024)} MB")
                }
                _state.value = SetupState.Unpacking(0)
                context.filesDir.resolve("uml").mkdirs()
                val tmp = File(context.filesDir, "uml/rootfs.ext4.unpack")
                val asset = context.assets.open(ASSET_PATH)
                asset.use { input ->
                    // The APK asset is the raw ext4, not the repo's .gz file:
                    // AGP's mergeAssets decompresses .gz assets during the
                    // merge (verified on 9.2.1 — the packaged entry is the
                    // plain 512 MB image, DEFLATE'd by the zip layer). So the
                    // unpack is a straight copy; the gzip step only ever
                    // existed to keep the 512 MB image out of git.
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(UNPACK_BUFFER_SIZE)
                        var written = 0L
                        var read = input.read(buf)
                        while (read >= 0) {
                            out.write(buf, 0, read)
                            written += read
                            _state.value = SetupState.Unpacking(
                                (written * 100 / ROOTFS_SIZE_BYTES).toInt().coerceIn(0, 100))
                            read = input.read(buf)
                        }
                    }
                }
                if (tmp.length() != ROOTFS_SIZE_BYTES) {
                    tmp.delete()
                    throw IllegalStateException(
                        "Unpacked rootfs is ${tmp.length()} bytes, expected $ROOTFS_SIZE_BYTES")
                }
                if (!tmp.renameTo(rootfsFile)) {
                    tmp.delete()
                    throw IllegalStateException("Could not move the staged rootfs into place")
                }
                _state.value = SetupState.Ready
            } catch (e: Exception) {
                File(context.filesDir, "uml/rootfs.ext4.unpack").delete()
                _state.value = SetupState.Error(
                    phase = "unpack",
                    message = e.message ?: "Rootfs staging failed",
                )
                throw e
            }
        }
    }

    /**
     * Delete the staged rootfs. User data inside it goes with it — this is the
     * explicit reset for a corrupted image or an asset-version bump, not
     * something any close path may call.
     */
    fun deleteRootfs() {
        rootfsFile.delete()
        _state.value = SetupState.NotStaged
    }

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val localSession: LocalSession? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "uml-guest-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    fun registerSession(profileId: String, label: String): String {
        reapDeadSessionsForProfile(profileId)
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
            ))
        }
        return sessionId
    }

    private fun reapDeadSessionsForProfile(profileId: String) {
        val deadIds = _sessions.value.values
            .filter { it.profileId == profileId && it.status == SessionState.Status.DISCONNECTED }
            .map { it.sessionId }
        if (deadIds.isEmpty()) return
        _sessions.update { map -> map - deadIds.toSet() }
        deadIds.forEach {
            agentScrollback.remove(it)
            agentTee.remove(it)
        }
    }

    /** Mark a session ready to boot; the kernel starts in [createTerminalSession]. */
    fun connectSession(sessionId: String) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = SessionState.Status.CONNECTED))
        }
    }

    /**
     * argv for libuml-net.so. Contract (core/local/src/main/cpp/uml_net.c):
     * `uml-net <passt> <passt-log> <linux> <kernel-args...>`. The launcher
     * appends the vec0 transport argument itself. No con0/con arguments: UML's
     * stdio console sits on the pty nativeForkPty forked, and no init= — the
     * rootfs busybox init takes over.
     *
     * Note args[0] is the launcher itself: PtyBridge execve's `command` with
     * `args` as the raw argv.
     */
    fun buildLaunchCommand(sessionId: String): Triple<String, Array<String>, Array<String>> {
        val natDir = context.applicationInfo.nativeLibraryDir
        val cacheDir = context.cacheDir.absolutePath
        val nat = { lib: String -> File(natDir, lib).absolutePath }
        val cmd = nat("libuml-net.so")
        val args = arrayOf(
            cmd,
            nat("libuml-passt.so"),
            File(context.cacheDir, "uml/passt-$sessionId.log").absolutePath,
            nat("libvmlinux.so"),
            "mem=384M",
            "ubd0=${rootfsFile.absolutePath}",
            "root=/dev/ubda",
            "rw",
            "stub_exe=${nat("libuml-stub.so")}",
        )
        val env = arrayOf(
            "TMPDIR=$cacheDir",
            "HOME=${context.filesDir.absolutePath}",
            "PATH=/system/bin:/vendor/bin:$natDir",
            "TERM=xterm-256color",
            "PASST_NO_SANDBOX=1",
        )
        return Triple(cmd, args, env)
    }

    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
        rows: Int = 24,
        cols: Int = 80,
    ): LocalSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.localSession != null) return null

        val (cmd, args, env) = buildLaunchCommand(sessionId)
        // passt's log dir; uml_net.c only creates the file.
        File(context.cacheDir, "uml").mkdirs()

        // Agent-scope mirror of the console, identical to the LOCAL wiring so
        // read_terminal_scrollback works on guest sessions the user opened.
        val ring = agentScrollback.computeIfAbsent(sessionId) {
            ScrollbackRing(agentScrollbackBytes)
        }
        val mirroredOnData: (ByteArray, Int, Int) -> Unit = { data, off, len ->
            ring.append(data, off, len)
            agentTee[sessionId]?.invoke(data, off, len)
            onDataReceived(data, off, len)
        }

        val localSession = LocalSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            command = cmd,
            args = args,
            env = env,
            onDataReceived = mirroredOnData,
            onExited = { exitCode ->
                Log.d(TAG, "Guest $sessionId process exited: $exitCode")
                _sessions.update { map ->
                    val existing = map[sessionId] ?: return@update map
                    existing.localSession?.close()
                    map + (sessionId to existing.copy(
                        status = SessionState.Status.DISCONNECTED,
                        localSession = null,
                    ))
                }
                // Same diagnosis channel as LOCAL: the scrollback tail usually
                // carries the kernel panic or the passt error that killed the
                // guest.
                val tail = runCatching {
                    val snap = ring.snapshot()
                    val from = maxOf(0, snap.size - 2048)
                    String(snap, from, snap.size - from, Charsets.UTF_8)
                }.getOrNull()?.takeIf { it.isNotBlank() }
                prefScope.launch {
                    connectionLog.logEvent(
                        profileId = session.profileId,
                        status = if (exitCode == 0) ConnectionLog.Status.DISCONNECTED
                        else ConnectionLog.Status.FAILED,
                        details = "Guest exited (exit code $exitCode)",
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
        return session.status == SessionState.Status.CONNECTED && session.localSession == null
    }

    fun getActiveSession(sessionId: String): LocalSession? =
        _sessions.value[sessionId]?.localSession

    /** No-op by design, matching LOCAL (#272): the guest keeps running detached. */
    fun detachTerminalSession(sessionId: String) {}

    fun reattachTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): LocalSession? {
        val live = _sessions.value[sessionId]?.localSession ?: return null
        val ring = agentScrollback.computeIfAbsent(sessionId) { ScrollbackRing(agentScrollbackBytes) }
        live.replaceDataCallback { data, off, len ->
            ring.append(data, off, len)
            agentTee[sessionId]?.invoke(data, off, len)
            onDataReceived(data, off, len)
        }
        return live
    }

    fun snapshotScrollback(sessionId: String): ByteArray? =
        agentScrollback[sessionId]?.snapshot()?.takeIf { it.isNotEmpty() }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    // --- Agent transport entry points (mirror LocalSessionManager) ---------

    private val agentScrollback = ConcurrentHashMap<String, ScrollbackRing>()

    private val agentTee = ConcurrentHashMap<String, (ByteArray, Int, Int) -> Unit>()

    private val agentScrollbackBytes = 256 * 1024

    /**
     * Start the guest's PTY without a UI tab. Idempotent — a guest the user
     * already opened a tab for is left alone.
     */
    fun startHeadlessShell(
        sessionId: String,
        extraOnData: ((ByteArray, Int, Int) -> Unit)? = null,
    ) {
        val session = _sessions.value[sessionId] ?: return
        extraOnData?.let { agentTee[sessionId] = it }
        if (session.localSession != null) return
        val ls = createTerminalSession(sessionId, onDataReceived = { _, _, _ -> }) ?: return
        ls.start(rows = 24, cols = 80)
    }

    fun clearAgentTee(sessionId: String) {
        agentTee.remove(sessionId)
    }

    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("No guest session: $sessionId")
        val localSession = session.localSession
            ?: throw IllegalStateException(
                "Guest session $sessionId has ended — connect the profile again")
        if (!localSession.isAlive()) {
            throw IllegalStateException(
                "Guest session $sessionId has ended — connect the profile again")
        }
        localSession.sendInput(text.toByteArray(Charsets.UTF_8))
    }

    suspend fun awaitFirstOutput(sessionId: String, timeoutMs: Long = 4000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun hasOutput() = (agentScrollback[sessionId]?.totalBytesAppended ?: 0L) > 0L
        while (System.currentTimeMillis() < deadline) {
            if (hasOutput()) return true
            val status = _sessions.value[sessionId]?.status
            if (status != null && status != SessionState.Status.CONNECTED) break
            delay(40)
        }
        return hasOutput()
    }

    fun readAgentScrollback(sessionId: String, maxBytes: Int): ByteArray? {
        val ring = agentScrollback[sessionId] ?: return null
        val full = ring.snapshot()
        return if (full.size <= maxBytes) full
        else full.copyOfRange(full.size - maxBytes, full.size)
    }

    /**
     * Close a guest: ask init to power off, give the kernel up to five seconds
     * to unmount and exit, then fall back to the hard kill in LocalSession.close().
     * The graceful path matters more here than for a shell — the guest holds the
     * ext4 rootfs open, and an unclean kill risks the image (journaling bounds
     * the damage, but a clean poweroff is free when init cooperates).
     */
    fun closeGuest(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        if (session.localSession?.isAlive() == true) {
            try {
                session.localSession.sendInput("poweroff\n".toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
            }
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val current = _sessions.value[sessionId]
                if (current == null || current.localSession == null) return
                if (!current.localSession.isAlive()) return
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.w(TAG, "Guest $sessionId did not power off within 5s; killing")
        }
        removeSession(sessionId)
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        agentScrollback.remove(sessionId)
        agentTee.remove(sessionId)
        ioExecutor.execute {
            try {
                session.localSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val ids = _sessions.value.values.filter { it.profileId == profileId }.map { it.sessionId }
        if (ids.isEmpty()) return
        // closeGuest waits up to 5 s for the guest to power off; UI paths call
        // this on the main thread, so the wait happens on [ioExecutor].
        ids.forEach { id -> ioExecutor.execute { closeGuest(id) } }
    }

    /**
     * Shut every guest down on process teardown. Uses the same graceful-first
     * path as closeGuest so the rootfs images get a clean unmount. Synchronous
     * on purpose: the caller is the foreground-service teardown, and an async
     * poweroff could be cut short by the process dying mid-flight.
     */
    fun disconnectAll() {
        val ids = _sessions.value.keys.toList()
        ids.forEach { closeGuest(it) }
    }

    private val prefScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // Not ".gz": AGP's mergeAssets decompresses .gz assets, so the APK
        // ships the raw ext4 image under this name.
        private const val ASSET_PATH = "uml/rootfs-aarch64.ext4"

        /** mkfs'd image size — the staging idempotency check. */
        const val ROOTFS_SIZE_BYTES = 536_870_912L

        /** Space check before unpacking: image + headroom for writes. */
        const val ROOTFS_FREE_SPACE_BYTES = 600L * 1024 * 1024

        private const val UNPACK_BUFFER_SIZE = 1 shl 16
    }
}