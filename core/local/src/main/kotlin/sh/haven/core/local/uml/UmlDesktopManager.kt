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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.haven.core.local.AppScanResult
import sh.haven.core.local.DesktopEntryParser
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.InstalledApp
import sh.haven.core.local.computeAutoResolution
import sh.haven.core.local.formatCageScale
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UmlDesktop"

/**
 * Desktops and single-app cage windows inside the UML guest: the mirror of
 * [DesktopManager] for the guest's own kernel and ext4 rootfs.
 *
 * A single shared UML guest session hosts both full desktops ([Kind.X11],
 * [Kind.WAYLAND]) and up to [MAX_CAGE_SLOTS] concurrent single-app cage
 * windows:
 * - On cold boot (~3–4s), `PASST_TFWD` forwards both the desktop ports and
 *   the cage port pool (`5961..5968`), and the console parks at `uml:~#`.
 * - Subsequent cage launches reuse the warm guest without rebooting the
 *   kernel (<1s startup, no `F_SETLK` rootfs lock conflict).
 */
@Singleton
class UmlDesktopManager @Inject constructor(
    private val guestManager: UmlGuestManager,
    private val desktopManager: DesktopManager,
) {
    internal constructor(
        guestManager: UmlGuestManager,
        desktopManager: DesktopManager,
        cagePortAllocator: () -> List<Int>,
    ) : this(guestManager, desktopManager) {
        this.cagePortAllocator = cagePortAllocator
    }

    private var cagePortAllocator: () -> List<Int> = ::defaultAllocateCagePorts

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
        desktopManager.setPortPreference(portGroup, kind.id, port)
        writeRecipeScript()
        driveJobs[kind] = ioScope.launch { runStart(kind, port) }
    }

    fun stopDesktop(kind: Kind) {
        when (state.value[kind]?.status) {
            DesktopState.Status.RUNNING -> {
                ioScope.launch {
                    val sid = sessionIds.remove(kind) ?: sharedSessionId
                    if (activeCageSlots.isEmpty()) {
                        closeSharedGuest(sid)
                    } else if (sid != null && !sessionDead(sid)) {
                        val port = state.value[kind]?.port ?: 0
                        guestMutex.withLock {
                            guestManager.sendInput(sid, recipeLine("stop ${kind.id} $port"))
                        }
                    }
                    markStopped(kind)
                }
            }
            DesktopState.Status.STARTING, DesktopState.Status.INSTALLING -> {
                val job = driveJobs.remove(kind)
                ioScope.launch {
                    // Cancel and let the driver unwind first: its catch
                    // releases the guest it booted. Reading sharedSessionId
                    // before the job settles races ensureGuestLocked's write
                    // and can miss the lock holder entirely (#686 on the
                    // cancel path).
                    job?.cancel()
                    job?.join()
                    val sid = sessionIds.remove(kind) ?: sharedSessionId
                    if (sid != null && activeCageSlots.isEmpty()) {
                        closeSharedGuest(sid)
                    }
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

    // --------------------------------------------------- shared guest & cages

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val driveJobs = mutableMapOf<Kind, Job>()
    private val sessionIds = mutableMapOf<Kind, String>()
    private val guestMutex = Mutex()

    @Volatile
    private var sharedSessionId: String? = null

    @Volatile
    private var sharedConsole: ConsoleLog? = null

    @Volatile
    private var forwardedPorts: Set<Int> = emptySet()

    @Volatile
    private var cagePortPool: List<Int> = emptyList()

    private data class CageSlotEntry(
        val sessionId: String,
        val slot: Int,
        val port: Int,
        val command: String,
        val onExit: ((String) -> Unit)?,
        var watchJob: Job? = null,
    )

    private val activeCageSlots = ConcurrentHashMap<String, CageSlotEntry>()
    private val sessionToSlot = ConcurrentHashMap<String, Int>()

    /** True when the UML cage runtime (`sway` + `wayvnc`) is already installed in the guest. */
    fun isCageRuntimeReady(): Boolean = markerFor(Kind.WAYLAND).exists()

    /**
     * Ensure the UML guest has `sway`, `wayvnc`, `foot`, and `xwayland`
     * installed, keeping the booted guest warm so the immediately-following
     * [startCageWindow] reuses it without a second kernel boot.
     */
    suspend fun ensureCageRuntime(): Boolean {
        if (!isAvailable) return false
        if (isCageRuntimeReady()) return true
        writeRecipeScript()
        return try {
            guestMutex.withLock {
                val (sid, console) = ensureGuestLocked(emptySet(), label = "UML cage runtime")
                if (!isCageRuntimeReady()) {
                    val pos = console.markPosition()
                    guestManager.sendInput(sid, recipeLine("install wayland"))
                    val marker = awaitAnyMarker(console, sid, "HDESKTOP:", timeoutMs = 600_000, fromIndex = pos)
                    if (marker != "HDESKTOP:done" || !markerFor(Kind.WAYLAND).exists()) {
                        return@withLock false
                    }
                    update(Kind.WAYLAND) { it.copy(installed = true) }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureCageRuntime failed", e)
            false
        }
    }

    /**
     * Launch [command] as a single-app `sway` kiosk inside the UML guest on a
     * dedicated headless Wayland + `wayvnc` slot. Reuses the running UML guest
     * when warm (<1s startup) or boots it with the cage port pool forwarded.
     */
    fun startCageWindow(
        command: String,
        outW: Int,
        outH: Int,
        scale: Float = 1f,
        multiWindow: Boolean = false,
        swayRules: List<String> = emptyList(),
        timeoutMs: Long = 20_000,
        onSessionExit: ((String) -> Unit)? = null,
    ): DesktopManager.AppWindowSession = runBlocking(Dispatchers.IO) {
        val sessionId = "uml-appwin-${System.currentTimeMillis()}-${(100..999).random()}"
        writeRecipeScript()
        try {
            guestMutex.withLock {
                val (guestSid, console) = ensureGuestLocked(emptySet(), label = "UML cage host")
                val usedSlots = activeCageSlots.values.map { it.slot }.toSet()
                val slot = (1..MAX_CAGE_SLOTS).firstOrNull { it !in usedSlots && it <= cagePortPool.size }
                    ?: return@withLock DesktopManager.AppWindowSession(
                        sessionId = sessionId,
                        command = command,
                        displayNumber = -1,
                        vncPort = 0,
                        state = DesktopManager.DesktopState.ERROR,
                        errorMessage = "All $MAX_CAGE_SLOTS UML cage slots are in use",
                        scale = scale,
                    )
                val port = cagePortPool[slot - 1]
                val display = if (port > 5900) port - 5900 else slot
                sessionToSlot[sessionId] = slot

                if (!isCageRuntimeReady()) {
                    val installPos = console.markPosition()
                    guestManager.sendInput(guestSid, recipeLine("install wayland"))
                    val installMarker = awaitAnyMarker(
                        console, guestSid, "HDESKTOP:", timeoutMs = 600_000, fromIndex = installPos,
                    )
                    if (installMarker != "HDESKTOP:done" || !markerFor(Kind.WAYLAND).exists()) {
                        return@withLock DesktopManager.AppWindowSession(
                            sessionId = sessionId,
                            command = command,
                            displayNumber = display,
                            vncPort = port,
                            state = DesktopManager.DesktopState.ERROR,
                            errorMessage = "UML cage runtime install failed",
                            scale = scale,
                        )
                    }
                    update(Kind.WAYLAND) { it.copy(installed = true) }
                }

                val share = guestManager.guestShareDir.apply { mkdirs() }
                val exitFile = File(share, "haven-kiosk-$slot.exit").apply { delete() }
                File(share, "haven-kiosk-$slot.log").delete()
                File(share, "haven-kiosk-$slot.run").writeText(buildCageRunScript(slot, command))
                File(share, "haven-kiosk-$slot.config").writeText(
                    buildCageSwayConfig(
                        slot = slot,
                        outW = outW,
                        outH = outH,
                        scaleStr = formatCageScale(scale),
                        multiWindow = multiWindow,
                        swayRules = swayRules,
                    ),
                )

                val primaryBin = extractPrimaryBinary(command).orEmpty()
                val pos = console.markPosition()
                val cageArgs = buildString {
                    append("cage-start ").append(slot).append(' ').append(port)
                    if (primaryBin.isNotEmpty()) append(' ').append(primaryBin)
                }
                guestManager.sendInput(guestSid, recipeLine(cageArgs))
                val marker = awaitAnyMarker(
                    console = console,
                    sessionId = guestSid,
                    prefix = "HCAGE:$slot:",
                    timeoutMs = timeoutMs.coerceAtLeast(15_000),
                    fromIndex = pos,
                )
                if (marker != "HCAGE:$slot:started") {
                    val detail = marker?.removePrefix("HCAGE:$slot:fail")?.trim()
                        ?.ifEmpty { "cage failed to start" }
                        ?: "timed out waiting for UML cage slot $slot"
                    return@withLock DesktopManager.AppWindowSession(
                        sessionId = sessionId,
                        command = command,
                        displayNumber = display,
                        vncPort = port,
                        state = DesktopManager.DesktopState.ERROR,
                        errorMessage = detail,
                        scale = scale,
                    )
                }

                // Wait for wayvnc's RFB banner on the forwarded port, aborting
                // immediately if sway already wrote its .exit marker.
                val deadline = System.currentTimeMillis() + timeoutMs
                var bannerUp = false
                while (System.currentTimeMillis() < deadline) {
                    if (sessionDead(guestSid) || exitFile.exists()) break
                    if (rfbBannerServed(port)) {
                        bannerUp = true
                        break
                    }
                    delay(120)
                }
                if (!bannerUp) {
                    guestManager.sendInput(guestSid, recipeLine("cage-stop $slot"))
                    val logTail = cageCompositorLog(sessionId)?.takeLast(400)?.trim()
                    val reason = if (exitFile.exists()) {
                        "cage app exited during startup" +
                            if (!logTail.isNullOrBlank()) ": $logTail" else ""
                    } else {
                        "no VNC banner on 127.0.0.1:$port within ${timeoutMs}ms"
                    }
                    return@withLock DesktopManager.AppWindowSession(
                        sessionId = sessionId,
                        command = command,
                        displayNumber = display,
                        vncPort = port,
                        state = DesktopManager.DesktopState.ERROR,
                        errorMessage = reason,
                        scale = scale,
                    )
                }

                val entry = CageSlotEntry(
                    sessionId = sessionId,
                    slot = slot,
                    port = port,
                    command = command,
                    onExit = onSessionExit,
                )
                activeCageSlots[sessionId] = entry
                entry.watchJob = ioScope.launch {
                    while (activeCageSlots.containsKey(sessionId)) {
                        delay(250)
                        if (sessionDead(guestSid) || exitFile.exists()) {
                            exitFile.delete()
                            activeCageSlots.remove(sessionId)
                            onSessionExit?.invoke(sessionId)
                            break
                        }
                    }
                }
                DesktopManager.AppWindowSession(
                    sessionId = sessionId,
                    command = command,
                    displayNumber = display,
                    vncPort = port,
                    state = DesktopManager.DesktopState.RUNNING,
                    scale = scale,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "startCageWindow($command) failed", e)
            DesktopManager.AppWindowSession(
                sessionId = sessionId,
                command = command,
                displayNumber = -1,
                vncPort = 0,
                state = DesktopManager.DesktopState.ERROR,
                errorMessage = e.message ?: "UML cage launch failed",
                scale = scale,
            )
        }
    }

    /** Stop one UML cage window slot while keeping the UML guest warm for future launches. */
    fun stopCageWindow(sessionId: String) {
        val entry = activeCageSlots.remove(sessionId)
        val slot = entry?.slot ?: sessionToSlot[sessionId] ?: return
        entry?.watchJob?.cancel()
        val sid = sharedSessionId
        if (sid != null && !sessionDead(sid)) {
            ioScope.launch {
                guestMutex.withLock {
                    if (!sessionDead(sid)) {
                        guestManager.sendInput(sid, recipeLine("cage-stop $slot"))
                    }
                }
            }
        }
    }

    /** Live-change a running UML cage window's sway output scale. */
    suspend fun setCageScale(sessionId: String, scale: Float) {
        val entry = activeCageSlots[sessionId] ?: return
        val sid = sharedSessionId ?: return
        if (sessionDead(sid)) return
        val s = formatCageScale(scale)
        guestMutex.withLock {
            if (!sessionDead(sid)) {
                guestManager.sendInput(sid, recipeLine("cage-scale ${entry.slot} $s"))
            }
        }
    }

    /** Live-refit a running UML cage window's sway output mode to [screenW]x[screenH]. */
    suspend fun setCageResolution(sessionId: String, screenW: Int, screenH: Int) {
        val entry = activeCageSlots[sessionId] ?: return
        val sid = sharedSessionId ?: return
        if (sessionDead(sid)) return
        val (w, h) = computeAutoResolution(screenW, screenH)
        guestMutex.withLock {
            if (!sessionDead(sid)) {
                guestManager.sendInput(sid, recipeLine("cage-mode ${entry.slot} $w $h"))
            }
        }
    }

    /** Read the compositor + app merged log for [sessionId] from `/host/haven-kiosk-<slot>.log`. */
    fun cageCompositorLog(sessionId: String): String? {
        val slot = activeCageSlots[sessionId]?.slot ?: sessionToSlot[sessionId] ?: return null
        val file = File(guestManager.guestShareDir, "haven-kiosk-$slot.log")
        return if (file.isFile) file.readText() else null
    }

    /** Newest `/host/haven-kiosk-<slot>.log` across all UML cage slots. */
    fun latestCageLog(): Pair<Int, File>? =
        guestManager.guestShareDir
            .listFiles { f -> f.isFile && f.name.startsWith("haven-kiosk-") && f.name.endsWith(".log") }
            ?.mapNotNull { file ->
                val slot = file.name.removePrefix("haven-kiosk-").removeSuffix(".log").toIntOrNull()
                if (slot != null) slot to file else null
            }
            ?.maxByOrNull { it.second.lastModified() }

    /**
     * Scan `.desktop` entries synced from the UML guest into `/host/applications`.
     * Syncs live when the UML guest is running or reads the cached entries on disk.
     */
    suspend fun scanInstalledApps(): AppScanResult {
        val appsDir = File(guestManager.guestShareDir, "applications")
        val sid = sharedSessionId
        if (sid != null && !sessionDead(sid)) {
            runCatching {
                guestMutex.withLock {
                    val console = sharedConsole
                    if (console != null && !sessionDead(sid)) {
                        val pos = console.markPosition()
                        guestManager.sendInput(sid, recipeLine("cage-apps"))
                        awaitAnyMarker(console, sid, "HCAGE:apps:", timeoutMs = 5_000, fromIndex = pos)
                    }
                }
            }
        }
        val files = appsDir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") }.orEmpty()
        val apps = files.mapNotNull { f ->
            val entry = runCatching { DesktopEntryParser.parse(f.readText()) }.getOrNull()
                ?: return@mapNotNull null
            InstalledApp(entry.name, entry.exec, iconPath = null, categories = entry.categories)
        }.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
        return AppScanResult(apps, iconsResolved = 0, total = apps.size)
    }

    // ---------------------------------------------------------------- drivers

    private suspend fun ensureGuestLocked(
        requiredPorts: Set<Int>,
        profileId: String = "uml-desktop-shared",
        label: String = "UML desktop",
    ): Pair<String, ConsoleLog> {
        val currentSid = sharedSessionId
        val currentConsole = sharedConsole
        if (currentSid != null && !sessionDead(currentSid) && currentConsole != null &&
            requiredPorts.all { it in forwardedPorts }
        ) {
            return currentSid to currentConsole
        }
        if (currentSid != null) {
            closeSharedGuest(currentSid)
        }
        val allocatedCages = cagePortAllocator()
        cagePortPool = allocatedCages
        val allPorts = (requiredPorts + allocatedCages).filter { it in 1..65535 }.toSortedSet()
        val extraEnv = if (allPorts.isNotEmpty()) {
            listOf("PASST_TFWD=${allPorts.joinToString(",")}")
        } else {
            emptyList()
        }
        val sid = bootGuest(profileId = profileId, label = label, extraEnv = extraEnv)
        sharedSessionId = sid
        forwardedPorts = allPorts
        try {
            val console = startConsole(sid)
                ?: throw IllegalStateException("guest console didn't come up")
            awaitConsolePrompt(console, sid, timeoutMs = 45_000)
            sharedConsole = console
            return sid to console
        } catch (e: Exception) {
            closeSharedGuest(sid)
            throw e
        }
    }

    private fun closeSharedGuest(sessionId: String?) {
        val target = sessionId ?: sharedSessionId
        if (target != null) {
            if (sharedSessionId == target) {
                sharedSessionId = null
                sharedConsole = null
                forwardedPorts = emptySet()
            }
            try {
                guestManager.closeGuest(target)
            } catch (ce: Exception) {
                Log.w(TAG, "teardown of guest $target did not complete", ce)
            }
        }
    }

    private suspend fun runStart(kind: Kind, port: Int) {
        var bootedSid: String? = null
        try {
            guestMutex.withLock {
                val (sessionId, console) = ensureGuestLocked(
                    requiredPorts = setOf(port),
                    profileId = "uml-desktop-${kind.id}",
                    label = "UML ${kind.title} desktop",
                )
                bootedSid = sessionId
                sessionIds[kind] = sessionId

                // Auto-install on first start in the same guest boot so the
                // primary Start button works out-of-the-box without requiring
                // a separate manual Install pass first.
                if (!markerFor(kind).exists()) {
                    val installPos = console.markPosition()
                    guestManager.sendInput(sessionId, recipeLine("install ${kind.id}"))
                    val installMarker = awaitAnyMarker(
                        console, sessionId, "HDESKTOP:", timeoutMs = 600_000, fromIndex = installPos,
                    )
                    if (installMarker != "HDESKTOP:done" || !markerFor(kind).exists()) {
                        throw IllegalStateException(
                            "Package install failed" +
                                if (installMarker == null) " (timed out)" else ": $installMarker",
                        )
                    }
                    update(kind) { it.copy(installed = true) }
                }

                val startPos = console.markPosition()
                guestManager.sendInput(sessionId, recipeLine("start ${kind.id} $port"))
                val marker = awaitAnyMarker(
                    console, sessionId, "HDESKTOP:", timeoutMs = 120_000, fromIndex = startPos,
                )
                if (marker != "HDESKTOP:started") {
                    throw IllegalStateException(
                        "start script did not come up. Last console output:\n${console.tail(2_000)}",
                    )
                }
                awaitRfb(sessionId, port, timeoutMs = 60_000)
                update(kind) { it.copy(status = DesktopState.Status.RUNNING, port = port) }
            }
        } catch (ce: CancellationException) {
            // stopDesktop during STARTING cancels this job; its own close
            // coroutine races us for sharedSessionId and may read it before
            // ensureGuestLocked set it. Close here so the rootfs-lock holder
            // is always released (#686 on the cancel path).
            val sid = bootedSid ?: sessionIds[kind] ?: sharedSessionId
            if (sid != null) closeSharedGuest(sid)
            throw ce
        } catch (e: Exception) {
            val sid = bootedSid ?: sessionIds[kind] ?: sharedSessionId
            if (sid != null) {
                fail(kind, "Desktop start failed", e, sid)
            } else {
                fail(kind, "Guest boot failed", e, null)
            }
        }
    }

    private suspend fun runInstall(kind: Kind) {
        var bootedSid: String? = null
        try {
            val sessionId = bootGuest(
                profileId = "uml-install-${kind.id}",
                label = "UML ${kind.title} install",
                extraEnv = emptyList(),
            )
            bootedSid = sessionId
            try {
                val console = startConsole(sessionId)
                    ?: throw IllegalStateException("guest console didn't come up")
                awaitConsolePrompt(console, sessionId, timeoutMs = 45_000)
                val pos = console.markPosition()
                guestManager.sendInput(sessionId, recipeLine("install ${kind.id}"))
                val marker = awaitAnyMarker(console, sessionId, "HDESKTOP:", timeoutMs = 600_000, fromIndex = pos)
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
            fail(kind, "Guest boot failed", e, bootedSid)
        }
    }

    /** ensureRootfs + registerSession + connectSession; the kernel boots when the pty starts. */
    private suspend fun bootGuest(profileId: String, label: String, extraEnv: List<String>): String {
        guestManager.ensureRootfs()
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
                delay(250)
                if (console.tail(120) == tail) return
            } else {
                delay(100)
            }
        }
        throw IllegalStateException("no console prompt within ${timeoutMs / 1000}s")
    }

    private suspend fun awaitAnyMarker(
        console: ConsoleLog,
        sessionId: String,
        prefix: String,
        timeoutMs: Long,
        fromIndex: Int = 0,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionDead(sessionId)) return null
            console.firstMarker(prefix, fromIndex)?.let { return it }
            delay(100)
        }
        return null
    }

    private suspend fun awaitRfb(sessionId: String, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionDead(sessionId)) {
                throw IllegalStateException("guest exited before the desktop came up")
            }
            if (rfbBannerServed(port)) return
            delay(150)
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

    private fun fail(kind: Kind, base: String, e: Exception?, sessionId: String?) {
        Log.e(TAG, base, e)
        val tail = sessionId?.let { guestManager.readAgentScrollback(it, 2_000) }
        if (sessionId != null) {
            sessionIds.remove(kind)
            if (activeCageSlots.isEmpty()) {
                closeSharedGuest(sessionId)
            }
        }
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

    private fun pickPort(kind: Kind): Int {
        var port = desktopManager.getPortPreference(portGroup, kind.id)
            .takeIf { it in 5901..5999 }
            ?: desktopManager.suggestNextVncPort(portGroup)
                .takeIf { it in 5901..5999 }
            ?: 5951
        var hops = 0
        // Ports already forwarded by our own live passt instance are ours, not collisions.
        while (port !in forwardedPorts && listenerHoldsLoopback(port) && hops < 50) {
            port++
            hops++
        }
        return port
    }

    private fun defaultAllocateCagePorts(): List<Int> {
        val ports = mutableListOf<Int>()
        var candidate = CAGE_BASE_PORT
        while (ports.size < MAX_CAGE_SLOTS && candidate <= 5999) {
            if (candidate in forwardedPorts || !listenerHoldsLoopback(candidate)) {
                ports.add(candidate)
            }
            candidate++
        }
        return ports
    }

    private fun listenerHoldsLoopback(port: Int): Boolean = try {
        ServerSocket(port, 0, InetAddress.getLoopbackAddress()).close()
        false
    } catch (_: Exception) {
        true
    }

    // ------------------------------------------------------------ the script

    private fun writeRecipeScript() {
        guestManager.guestShareDir.apply { mkdirs() }
            .resolve(RECIPE_NAME)
            .writeText(RECIPE_SCRIPT)
    }

    companion object {
        private const val DS = "$"
        const val MAX_CAGE_SLOTS = 8
        private const val CAGE_BASE_PORT = 5961

        private val ANSI = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)")
        private val SIMPLE_BINARY = Regex("^[A-Za-z0-9_./-]+$")
        private val SHELL_META = Regex("[;|`$&<>()'\"]")

        /** True when the console tail ends at a root-shell prompt. */
        fun atPrompt(tail: String): Boolean =
            ANSI.replace(tail, "").trimEnd().endsWith("#")

        /**
         * Extract the primary executable token from [command] when it is a
         * straightforward command (e.g. `"galculator"`, `"imv /root/a.png"`,
         * `"env QT_QPA_PLATFORM=xcb qmmp"`), or null when the command uses
         * shell pipelines/compound syntax.
         */
        fun extractPrimaryBinary(command: String): String? {
            val trimmed = command.trim()
            if (trimmed.isEmpty() || SHELL_META.containsMatchIn(trimmed)) return null
            val tokens = trimmed.split(Regex("\\s+"))
            for (t in tokens) {
                if (t == "env" || t == "fakeroot-tcp" || t == "exec") continue
                if (t.contains('=') && !t.startsWith('/')) continue
                return if (SIMPLE_BINARY.matches(t)) t else null
            }
            return null
        }

        /** Launcher script executed by sway for cage slot [slot]. */
        fun buildCageRunScript(slot: Int, command: String): String =
            """#!/bin/sh
$command
for k in /tmp/wr-cage-$slot/sway-ipc.*.sock; do
    [ -S "${DS}k" ] && swaymsg -s "${DS}k" exit >/dev/null 2>&1
done
"""

        /** Sway kiosk configuration for cage slot [slot]. */
        fun buildCageSwayConfig(
            slot: Int,
            outW: Int,
            outH: Int,
            scaleStr: String,
            multiWindow: Boolean,
            swayRules: List<String>,
        ): String {
            val windowMode = if (multiWindow) {
                "for_window [app_id=\".*\"] floating enable\nfor_window [class=\".*\"] floating enable"
            } else {
                "for_window [app_id=\".*\"] fullscreen enable\nfor_window [class=\".*\"] fullscreen enable"
            }
            val rulesBlock = swayRules.joinToString("\n") { it.replace('\n', ' ') }
            return buildString {
                appendLine("output HEADLESS-1 mode ${outW}x${outH}@60Hz")
                appendLine("output HEADLESS-1 scale $scaleStr")
                appendLine("output HEADLESS-1 enable")
                appendLine("default_border none")
                appendLine("default_floating_border none")
                appendLine("hide_edge_borders both")
                appendLine("gaps inner 0")
                appendLine("gaps outer 0")
                appendLine(windowMode)
                if (rulesBlock.isNotBlank()) appendLine(rulesBlock)
                appendLine("exec sh /host/haven-kiosk-$slot.run")
            }
        }

        /** Kernel arg gating the agent launcher off (uml-guest-9 inittab hook). */
        const val DESKTOP_KERNEL_ARG = "haven.desktop=1"

        /** Name under [UmlGuestManager.guestShareDir]; the guest runs /host/filename. */
        private const val RECIPE_NAME = "haven-desktop.sh"

        fun recipeLine(args: String): String =
            "mount -t hostfs none /host 2>/dev/null; " +
                "[ -f /host/$RECIPE_NAME ] || echo 'HDESKTOP:fail /host share not mounted'; " +
                "sh /host/$RECIPE_NAME $args\r"

        internal val RECIPE_SCRIPT = """#!/bin/sh
# Haven's UML desktop + cage recipe, written by the app into the hostfs share.
#   sh /host/haven-desktop.sh install x11|wayland
#   sh /host/haven-desktop.sh start x11|wayland <port>
#   sh /host/haven-desktop.sh stop x11|wayland <port>
#   sh /host/haven-desktop.sh cage-start <slot> <port> [<app_bin>]
#   sh /host/haven-desktop.sh cage-stop <slot>
#   sh /host/haven-desktop.sh cage-scale <slot> <scale>
#   sh /host/haven-desktop.sh cage-mode <slot> <w> <h>
#   sh /host/haven-desktop.sh cage-apps
set -u
APPLOG=/host/haven-desktop.log

x11_pkgs() {
    # font-misc-misc must be installed BEFORE Xvnc starts: Xvnc snapshots
    # its font path at startup, and xterm maps a bitmap font from it
    # (stage-1: xterm rendered unmapped glyphs without it).
    apk add --no-cache font-misc-misc tigervnc openbox xterm xsetroot xwininfo xprop font-noto
}
wayland_pkgs() {
    apk add --no-cache sway wayvnc foot xwayland jq font-noto
}
sync_desktop_entries() {
    mkdir -p /host/applications
    cp -f /usr/share/applications/*.desktop /host/applications/ 2>/dev/null || true
}

case "${DS}1" in
install)
    case "${DS}2" in
    x11)     x11_pkgs && sync_desktop_entries && touch /host/haven-desktop-x11.ok ;;
    wayland) wayland_pkgs && sync_desktop_entries && touch /host/haven-desktop-wayland.ok ;;
    *) echo "HDESKTOP:fail unknown kind: ${DS}2"; exit 1 ;;
    esac && echo HDESKTOP:done || echo "HDESKTOP:fail apk add exited nonzero"
    ;;
start)
    kind="${DS}2"; port="${DS}3"
    display=${DS}((port - 5900))
    case "${DS}kind" in
    x11)
        command -v Xvnc >/dev/null 2>&1 || { echo "HDESKTOP:fail Xvnc not installed"; exit 1; }
        command -v xprop >/dev/null 2>&1 || apk add --no-cache xprop >>"${DS}APPLOG" 2>&1
        rm -f "/tmp/.X${DS}display-lock" "/tmp/.X11-unix/X${DS}display"
        Xvnc ":${DS}display" -geometry 1280x720 -depth 24 -SecurityTypes None -BlacklistTimeout 0 >>"${DS}APPLOG" 2>&1 &
        xvnc_pid=${DS}!
        echo "${DS}xvnc_pid" > "/tmp/xvnc-${DS}display.pid"
        sleep 2
        kill -0 ${DS}xvnc_pid 2>/dev/null || {
            echo "HDESKTOP:fail Xvnc died at startup"
            tail -20 "${DS}APPLOG"
            exit 1
        }
        (DISPLAY=:${DS}display openbox >>"${DS}APPLOG" 2>&1) &
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
        printf 'xwayland disable\noutput * bg #101418 solid_color\n' > /tmp/wr/sway.config
        rm -f /tmp/wr/wayland-* /tmp/wr/.X*-lock
        (WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman \
            XDG_RUNTIME_DIR=/tmp/wr WAYLAND_DISPLAY=wayland-1 \
            sway --config /tmp/wr/sway.config >>"${DS}APPLOG" 2>&1) &
        echo "${DS}!" > /tmp/wr/sway.pid
        n=0
        while [ ${DS}n -lt 15 ] && ! ls /tmp/wr/wayland-1 >/dev/null 2>&1; do
            sleep 1; n=${DS}((n + 1))
        done
        (XDG_RUNTIME_DIR=/tmp/wr WAYLAND_DISPLAY=wayland-1 \
            wayvnc 0.0.0.0 ${DS}port >>"${DS}APPLOG" 2>&1) &
        echo "${DS}!" > /tmp/wr/wayvnc.pid
        (XDG_RUNTIME_DIR=/tmp/wr WAYLAND_DISPLAY=wayland-1 foot >>"${DS}APPLOG" 2>&1) &
        ;;
    *)
        echo "HDESKTOP:fail unknown kind: ${DS}kind"; exit 1 ;;
    esac
    echo HDESKTOP:started
    ;;
stop)
    kind="${DS}2"; port="${DS}{3:-0}"
    display=${DS}((port - 5900))
    case "${DS}kind" in
    x11)
        [ -f "/tmp/xvnc-${DS}display.pid" ] && kill ${DS}(cat "/tmp/xvnc-${DS}display.pid") 2>/dev/null || true
        rm -f "/tmp/xvnc-${DS}display.pid" "/tmp/.X${DS}display-lock" "/tmp/.X11-unix/X${DS}display"
        ;;
    wayland)
        [ -f /tmp/wr/wayvnc.pid ] && kill ${DS}(cat /tmp/wr/wayvnc.pid) 2>/dev/null || true
        [ -f /tmp/wr/sway.pid ] && kill ${DS}(cat /tmp/wr/sway.pid) 2>/dev/null || true
        rm -rf /tmp/wr
        ;;
    esac
    echo HDESKTOP:stopped
    ;;
cage-start)
    slot="${DS}2"; port="${DS}3"; app_bin="${DS}{4:-}"
    rt="/tmp/wr-cage-${DS}slot"
    SLOTLOG="/host/haven-kiosk-${DS}slot.log"
    : > "${DS}SLOTLOG"
    if ! command -v sway >/dev/null 2>&1 || ! command -v wayvnc >/dev/null 2>&1; then
        wayland_pkgs >>"${DS}SLOTLOG" 2>&1 && touch /host/haven-desktop-wayland.ok || {
            echo "HCAGE:${DS}slot:fail sway/wayvnc install failed"
            exit 1
        }
    fi
    if [ -n "${DS}app_bin" ] && ! command -v "${DS}app_bin" >/dev/null 2>&1; then
        case "${DS}app_bin" in
        */*) ;;
        *) apk add --no-cache "${DS}app_bin" >>"${DS}SLOTLOG" 2>&1 || true ;;
        esac
        command -v "${DS}app_bin" >/dev/null 2>&1 || {
            echo "HCAGE:${DS}slot:fail command '${DS}app_bin' not found in UML guest"
            exit 1
        }
        sync_desktop_entries
    fi
    [ -f "${DS}rt/wayvnc.pid" ] && kill ${DS}(cat "${DS}rt/wayvnc.pid") 2>/dev/null || true
    [ -f "${DS}rt/sway.pid" ] && kill ${DS}(cat "${DS}rt/sway.pid") 2>/dev/null || true
    rm -rf "${DS}rt" "/host/haven-kiosk-${DS}slot.exit"
    mkdir -p "${DS}rt" && chmod 700 "${DS}rt"
    if [ -x /usr/bin/Xwayland ]; then
        cp "/host/haven-kiosk-${DS}slot.config" "${DS}rt/sway.config"
    else
        { echo "xwayland disable"; cat "/host/haven-kiosk-${DS}slot.config"; } > "${DS}rt/sway.config"
    fi
    (
        WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman \
            XDG_RUNTIME_DIR="${DS}rt" WAYLAND_DISPLAY=wayland-1 \
            sway --config "${DS}rt/sway.config" >>"${DS}SLOTLOG" 2>&1
        rc=${DS}?
        echo "${DS}rc" > "/host/haven-kiosk-${DS}slot.exit"
        [ -f "${DS}rt/wayvnc.pid" ] && kill ${DS}(cat "${DS}rt/wayvnc.pid") 2>/dev/null || true
    ) &
    sway_pid=${DS}!
    echo "${DS}sway_pid" > "${DS}rt/sway.pid"
    n=0
    while [ ${DS}n -lt 100 ] && ! [ -S "${DS}rt/wayland-1" ]; do
        if ! kill -0 "${DS}sway_pid" 2>/dev/null; then
            echo "HCAGE:${DS}slot:fail sway exited during startup"
            exit 1
        fi
        sleep 0.1 2>/dev/null || sleep 1
        n=${DS}((n + 1))
    done
    [ -S "${DS}rt/wayland-1" ] || { echo "HCAGE:${DS}slot:fail wayland-1 socket did not appear"; exit 1; }
    (XDG_RUNTIME_DIR="${DS}rt" WAYLAND_DISPLAY=wayland-1 \
        wayvnc 0.0.0.0 "${DS}port" >>"${DS}SLOTLOG" 2>&1) &
    wayvnc_pid=${DS}!
    echo "${DS}wayvnc_pid" > "${DS}rt/wayvnc.pid"
    sleep 0.1 2>/dev/null || sleep 1
    kill -0 "${DS}sway_pid" 2>/dev/null || { echo "HCAGE:${DS}slot:fail cage app exited immediately"; exit 1; }
    kill -0 "${DS}wayvnc_pid" 2>/dev/null || { echo "HCAGE:${DS}slot:fail wayvnc died at startup"; exit 1; }
    echo "HCAGE:${DS}slot:started"
    ;;
cage-stop)
    slot="${DS}2"
    rt="/tmp/wr-cage-${DS}slot"
    for k in "${DS}rt"/sway-ipc.*.sock; do
        [ -S "${DS}k" ] && swaymsg -s "${DS}k" exit >/dev/null 2>&1
    done
    [ -f "${DS}rt/wayvnc.pid" ] && kill ${DS}(cat "${DS}rt/wayvnc.pid") 2>/dev/null || true
    [ -f "${DS}rt/sway.pid" ] && kill ${DS}(cat "${DS}rt/sway.pid") 2>/dev/null || true
    rm -rf "${DS}rt"
    echo "HCAGE:${DS}slot:stopped"
    ;;
cage-scale)
    slot="${DS}2"; scale="${DS}3"
    rt="/tmp/wr-cage-${DS}slot"
    for k in "${DS}rt"/sway-ipc.*.sock; do
        [ -S "${DS}k" ] && swaymsg -s "${DS}k" -t get_version >/dev/null 2>&1 && {
            swaymsg -s "${DS}k" output HEADLESS-1 scale "${DS}scale" >/dev/null 2>&1
            break
        }
    done
    echo "HCAGE:${DS}slot:scaled"
    ;;
cage-mode)
    slot="${DS}2"; w="${DS}3"; h="${DS}4"
    rt="/tmp/wr-cage-${DS}slot"
    for k in "${DS}rt"/sway-ipc.*.sock; do
        [ -S "${DS}k" ] && swaymsg -s "${DS}k" -t get_version >/dev/null 2>&1 && {
            swaymsg -s "${DS}k" output HEADLESS-1 mode "${DS}{w}x${DS}{h}@60Hz" >/dev/null 2>&1
            break
        }
    done
    echo "HCAGE:${DS}slot:moded"
    ;;
cage-apps)
    sync_desktop_entries
    echo "HCAGE:apps:done"
    ;;
*)
    echo "HDESKTOP:fail usage: ${DS}0 install|start|stop|cage-start|cage-stop|cage-scale|cage-mode|cage-apps"; exit 1
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
        fun markPosition(): Int = sb.length

        @Synchronized
        fun tail(n: Int): String =
            if (sb.length <= n) sb.toString() else sb.substring(sb.length - n)

        @Synchronized
        fun contains(m: String): Boolean = sb.contains(m)

        /**
         * The first line in the ring beginning with [prefix] at or after
         * [fromIndex]; markers only count at line start so echoed commands
         * cannot fake them.
         */
        @Synchronized
        fun firstMarker(prefix: String, fromIndex: Int = 0): String? {
            var at = fromIndex.coerceIn(0, sb.length)
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