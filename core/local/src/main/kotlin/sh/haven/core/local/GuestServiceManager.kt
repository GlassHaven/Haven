package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GuestServiceManager"

/**
 * Supervises long-lived helper processes that run *inside* the proot guest
 * — most importantly app-native MCP servers (KiCad/FreeCAD/OpenSCAD) that
 * an agent drives for structured control. Modelled on [DesktopManager]'s
 * long-lived-process pattern (a tracked [Process] per service + a daemon
 * reader thread that tees output to a rolling tail and flips state on exit).
 *
 * Unlike the `run_in_proot` background-job path (which is tied to the MCP
 * tool's coroutine scope and never restarts), a registered service is owned
 * by this singleton and re-launched on app start via [startAutostart], so it
 * survives Haven restarts. The registry is persisted as a JSON marker in the
 * active distro's rootfs (`root/.haven-services`), mirroring how
 * [ProotManager] persists `root/.haven-desktop` / `root/.haven-addons` — so
 * it is naturally per-distro and survives restarts.
 *
 * Services run in the **active** distro (commands go through
 * [ProotManager.startCommandInProot], which targets `activeRootfsDir`). The
 * registry lives in that rootfs, so reading it only ever yields the active
 * distro's services.
 */
@Singleton
class GuestServiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class ServiceState { STOPPED, STARTING, RUNNING, ERROR }

    data class GuestServiceSpec(
        val id: String,
        val label: String,
        /** Shell command run via `/bin/sh -lc` in the active proot guest. */
        val command: String,
        /** Loopback TCP port the service listens on inside the guest. */
        val port: Int,
        /** Re-launch automatically when Haven's MCP endpoint comes up. */
        val autostart: Boolean,
        /**
         * True when this service is itself a streamable-HTTP MCP server
         * (e.g. a KiCad MCP). Haven aggregates such servers' tools into its
         * own MCP surface, namespaced, so the agent talks only to Haven.
         */
        val isMcp: Boolean = false,
        /** HTTP path of the guest MCP endpoint (when [isMcp]). */
        val mcpPath: String = "/mcp",
    )

    data class GuestServiceInstance(
        val spec: GuestServiceSpec,
        val state: ServiceState,
        val errorMessage: String? = null,
    )

    private val _services = MutableStateFlow<Map<String, GuestServiceInstance>>(emptyMap())
    val services: StateFlow<Map<String, GuestServiceInstance>> = _services.asStateFlow()

    private val _registryRevision = MutableStateFlow(0)
    /** Increments whenever [register] or [unregister] mutates `root/.haven-services`. */
    val registryRevision: StateFlow<Int> = _registryRevision.asStateFlow()

    private val processes = mutableMapOf<String, Process>()
    private val logTails = mutableMapOf<String, ArrayDeque<String>>()
    private val logTailLimit = 30

    // ---- registry persistence (active rootfs marker) ----

    private fun markerFile(): File = File(prootManager.activeRootfsDir, "root/.haven-services")

    /** Registered service specs for the active distro (filesystem-backed). */
    fun registered(): List<GuestServiceSpec> {
        val f = markerFile()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                GuestServiceSpec(
                    id = id,
                    label = o.optString("label", id),
                    command = o.optString("command"),
                    port = o.optInt("port"),
                    autostart = o.optBoolean("autostart", false),
                    isMcp = o.optBoolean("isMcp", false),
                    mcpPath = o.optString("mcpPath", "/mcp"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $f", e)
            emptyList()
        }
    }

    private fun persist(specs: List<GuestServiceSpec>) {
        val arr = JSONArray()
        specs.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("label", s.label)
                    put("command", s.command)
                    put("port", s.port)
                    put("autostart", s.autostart)
                    put("isMcp", s.isMcp)
                    put("mcpPath", s.mcpPath)
                },
            )
        }
        val f = markerFile()
        f.parentFile?.mkdirs()
        f.writeText(arr.toString())
    }

    /** Register (or replace) a service spec by id and persist it. */
    fun register(spec: GuestServiceSpec) {
        val updated = registered().filterNot { it.id == spec.id } + spec
        persist(updated)
        _registryRevision.update { it + 1 }
    }

    /** Stop (if running) and remove a service from the registry. */
    fun unregister(id: String) {
        stop(id)
        persist(registered().filterNot { it.id == id })
        _services.update { it - id }
        _registryRevision.update { it + 1 }
    }

    // ---- supervision ----

    /** Start a registered service by id. No-op if already running. */
    fun start(id: String) {
        val spec = registered().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("No registered guest service '$id'")
        if (processes.containsKey(id)) {
            Log.d(TAG, "Service ${spec.label} already running")
            return
        }
        _services.update { it + (id to GuestServiceInstance(spec, ServiceState.STARTING)) }
        synchronized(logTails) { logTails[id] = ArrayDeque() }
        try {
            val process = prootManager.startCommandInProot(spec.command)
            processes[id] = process
            _services.update { it + (id to GuestServiceInstance(spec, ServiceState.RUNNING)) }

            // Daemon reader: tee stdout/stderr into the rolling tail; on exit,
            // flip state. A long-lived server only exits on crash/stop, so an
            // exit while we still think it's RUNNING is surfaced as ERROR with
            // the tail attached (same shape as DesktopManager's exit thread).
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(logTails) {
                            val tail = logTails[id] ?: return@synchronized
                            tail.addLast(line)
                            while (tail.size > logTailLimit) tail.removeFirst()
                        }
                    }
                } catch (_: Exception) {
                }
                val exit = try { process.waitFor() } catch (_: Exception) { -1 }
                processes.remove(id)
                val wasRunning = _services.value[id]?.state == ServiceState.RUNNING
                val tail = synchronized(logTails) { logTails.remove(id)?.toList().orEmpty() }
                _services.update { current ->
                    val inst = current[id] ?: return@update current
                    if (wasRunning) {
                        current + (
                            id to inst.copy(
                                state = ServiceState.ERROR,
                                errorMessage = "exited (code $exit): " +
                                    tail.takeLast(6).joinToString("\n"),
                            )
                            )
                    } else {
                        // Expected stop() — drop to STOPPED.
                        current + (id to inst.copy(state = ServiceState.STOPPED))
                    }
                }
                Log.d(TAG, "Guest service ${spec.label} exited: $exit")
            }, "guest-svc-${spec.id}").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start guest service ${spec.label}", e)
            processes.remove(id)
            synchronized(logTails) { logTails.remove(id) }
            _services.update {
                it + (id to GuestServiceInstance(spec, ServiceState.ERROR, e.message))
            }
        }
    }

    /**
     * Stop a running service.
     *
     * Signalling the proot *launcher* (the tracked [Process]) is not enough: proot ptrace-traces
     * the guest tracee and does **not** propagate our SIGTERM/SIGKILL to it, and `--kill-on-exit`
     * only reaps when proot exits because its *tracee* exited — not when proot itself is killed.
     * Device-verified 2026-05-24: a bare `destroy()`/`destroyForcibly()` leaves the guest python
     * orphaned (reparented to init), still holding its listening port, so a restart fails to bind
     * (`[Errno 98] address already in use`). `/proc/net/tcp` is permission-denied inside proot, so
     * kill-by-port is impossible from the guest.
     *
     * So we reap the tree ourselves, app-side: capture the launcher's descendant PIDs from
     * `/proc/<pid>/stat` *while the tree is intact*, signal the launcher, then SIGTERM→SIGKILL the
     * captured tracees directly (same UID, so [android.os.Process.sendSignal]/[killProcess] reach
     * them even after they reparent to init).
     */
    fun stop(id: String) {
        val inst = _services.value[id]
        // Mark STOPPED first so the reader thread treats the exit as expected.
        if (inst != null) {
            _services.update { it + (id to inst.copy(state = ServiceState.STOPPED)) }
        }
        val proc = processes.remove(id) ?: return
        // Snapshot the guest tracee tree before we kill the launcher (after which the children
        // reparent to init and we'd lose the parent link).
        val launcherPid = pidOf(proc) ?: -1
        val tracees = if (launcherPid > 0) descendantPids(launcherPid) else emptyList()

        proc.destroy()
        val exited = try {
            proc.waitFor(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!exited) {
            Log.w(TAG, "guest service $id launcher didn't exit on SIGTERM — forcing")
            proc.destroyForcibly()
        }

        // Reap the orphaned guest tracees directly: proot won't have done it for us.
        if (tracees.isNotEmpty()) {
            tracees.forEach { pid -> runCatching { android.os.Process.sendSignal(pid, 15) } } // SIGTERM
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            tracees.forEach { pid -> runCatching { android.os.Process.killProcess(pid) } }     // SIGKILL
            Log.d(TAG, "reaped ${tracees.size} guest tracee(s) for service $id: $tracees")
        }
    }

    /**
     * PIDs of every descendant of [rootPid] (children, grandchildren, …) as seen in the host's
     * `/proc`. Reads the parent-PID field of each `/proc/<pid>/stat` (field 4, after the
     * parenthesised comm which may itself contain spaces or `)`), builds the child map, and walks
     * it breadth-first. Returns empty on any read failure — best-effort reaping.
     */
    private fun descendantPids(rootPid: Int): List<Int> {
        val childrenOf = mutableMapOf<Int, MutableList<Int>>()
        val procDirs = File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }
            ?: return emptyList()
        for (dir in procDirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val ppid = readPpid(File(dir, "stat")) ?: continue
            childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val out = mutableListOf<Int>()
        val queue = ArrayDeque<Int>().apply { add(rootPid) }
        while (queue.isNotEmpty()) {
            childrenOf[queue.removeFirst()]?.forEach { child ->
                out.add(child)
                queue.add(child)
            }
        }
        return out
    }

    /**
     * The OS pid of a started [Process]. Read via reflection on the platform `Process` impl's
     * `pid` field — Kotlin won't resolve `Process.pid()` against this compile SDK, and reflection
     * is the long-standing Android way to recover a child pid. Null if the field shape changes.
     */
    private fun pidOf(p: Process): Int? = try {
        val v = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.get(p)
        when (v) {
            is Int -> v
            is Long -> v.toInt()
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun readPpid(statFile: File): Int? = try {
        val stat = statFile.readText()
        // Format: "<pid> (<comm>) <state> <ppid> ...". comm can contain spaces/')' so parse after
        // the LAST ')': remaining fields are "state ppid ..." → ppid is index 1.
        val afterComm = stat.substring(stat.lastIndexOf(')') + 1).trim()
        afterComm.split(' ').getOrNull(1)?.toIntOrNull()
    } catch (_: Exception) {
        null
    }

    /** Re-launch every registered service flagged autostart (called on app start). */
    fun startAutostart() {
        registered().filter { it.autostart }.forEach { spec ->
            try {
                start(spec.id)
            } catch (e: Exception) {
                Log.w(TAG, "autostart of ${spec.label} failed", e)
            }
        }
    }

    /** Stop all running services. */
    fun stopAll() {
        processes.keys.toList().forEach { stop(it) }
    }

    /** Ports of currently-RUNNING services — used to multiplex reverse forwards. */
    fun runningPorts(): List<Int> =
        _services.value.values
            .filter { it.state == ServiceState.RUNNING }
            .map { it.spec.port }
            .filter { it > 0 }

    /**
     * Registered MCP services (isMcp) that are currently RUNNING on a valid
     * loopback port — the set Haven aggregates into its own MCP surface.
     */
    fun runningMcpServices(): List<GuestServiceSpec> {
        val running = _services.value
        return registered().filter { spec ->
            spec.isMcp && spec.port > 0 && running[spec.id]?.state == ServiceState.RUNNING
        }
    }

    /** Rolling output tail for a service (diagnostics). */
    fun logTailFor(id: String): List<String> =
        synchronized(logTails) { logTails[id]?.toList().orEmpty() }
}
