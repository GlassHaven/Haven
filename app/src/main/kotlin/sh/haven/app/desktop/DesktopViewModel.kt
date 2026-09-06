package sh.haven.app.desktop

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.desktop.CursorSnapshot
import sh.haven.core.data.desktop.DesktopFrameHandle
import sh.haven.core.data.desktop.DesktopInputHandle
import sh.haven.core.data.desktop.DesktopStatus
import sh.haven.core.data.preferences.AppWindowDef
import sh.haven.core.data.preferences.AppWindowOrigin
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.knock.KnockSequence
import sh.haven.core.knock.PortKnocker
import sh.haven.core.local.AppScanResult
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.GuestAppScanner
import sh.haven.core.local.InstalledApp
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.proot.Distro
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rdp.RdpSession
import sh.haven.core.spice.SpiceSession
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnection
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ui.CursorOverlay
import sh.haven.core.tunnel.TunnelResolver
import sh.haven.core.tunnel.TunneledConnection
import sh.haven.core.tunnel.TunneledSocket
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncClient
import sh.haven.core.vnc.VncConfig
import sh.haven.feature.rdp.RdpViewModel
import sh.haven.feature.vnc.VncViewModel
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import sh.haven.core.redact.LogRedact

private const val TAG = "DesktopViewModel"

private sealed class DesktopStartOutcome {
    object Ready : DesktopStartOutcome()
    object Timeout : DesktopStartOutcome()
    data class Error(val message: String) : DesktopStartOutcome()
}

@HiltViewModel
class DesktopViewModel @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val connectionLogRepository: ConnectionLogRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: ConnectionRepository,
    private val tunnelResolver: TunnelResolver,
    private val portKnocker: PortKnocker,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val localSessionManager: LocalSessionManager,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val presentationManager: sh.haven.core.data.agent.AgentPresentationManager,
    private val appWindowLauncher: AppWindowLauncher,
    private val appWindowShortcutManager: AppWindowShortcutManager,
    private val usbDriveVmManager: sh.haven.app.usb.UsbDriveVmManager,
    private val systemVmManager: sh.haven.core.local.SystemVmManager,
) : ViewModel() {

    // --- Distro / DE management (issue #162 Phase 3c) ---
    //
    // The Connections topbar used to host a dialog with the distro picker,
    // rootfs setup state, and DE install/start/stop rows. That UI moved
    // to a "Manage" view inside the Desktop tab in 3c (`DesktopManagerScreen`).
    // These StateFlows + methods are the data layer the new screen reads;
    // every accessor is a thin pass-through to ProotManager / DesktopManager
    // so the source of truth stays in one place.

    private val prootManager: ProotManager get() = localSessionManager.prootManager
    private val desktopManager: DesktopManager get() = localSessionManager.desktopManager

    val activeDistroId: StateFlow<String> get() = prootManager.activeDistroIdFlow

    val rootfsSetupState: StateFlow<ProotManager.SetupState> get() = prootManager.state

    val desktopSetupState: StateFlow<ProotManager.DesktopSetupState>
        get() = prootManager.desktopState

    val desktopStates: StateFlow<Map<ProotManager.DesktopEnvironment, DesktopManager.DesktopInstance>>
        get() = desktopManager.desktops

    val installedDistros: List<Distro> get() = prootManager.installedDistros
    val availableDistros: List<Distro> get() = prootManager.availableDistros
    val availableForeignDistros: List<Pair<Distro, sh.haven.core.local.proot.Arch>>
        get() = prootManager.availableForeignDistros
    val installedDesktops: Set<ProotManager.DesktopEnvironment>
        get() = prootManager.installedDesktops

    val isRootfsReady: Boolean get() = prootManager.isReady

    fun switchActiveDistro(distroId: String) {
        prootManager.setActiveDistroId(distroId)
    }

    /** Selected package-mirror region (#263). Pass-through to ProotManager. */
    val mirrorRegion: StateFlow<sh.haven.core.local.proot.MirrorRegion>
        get() = prootManager.mirrorRegionFlow

    fun setMirrorRegion(region: sh.haven.core.local.proot.MirrorRegion) {
        prootManager.setMirrorRegion(region)
    }

    /** #300: remap privileged (<1024) guest binds up by +2000. Pass-through. */
    val remapLowPorts: StateFlow<Boolean> get() = prootManager.remapLowPortsFlow

    fun setRemapLowPorts(enabled: Boolean) {
        prootManager.setRemapLowPorts(enabled)
    }

    /** #301: share the device's /storage with the local guest. Pass-through. */
    val shareStorageWithGuest: StateFlow<Boolean> get() = prootManager.shareStorageWithGuestFlow

    fun setShareStorageWithGuest(enabled: Boolean) {
        prootManager.setShareStorageWithGuest(enabled)
    }

    /** #304: bind Android's read-only system partitions into the guest. Pass-through. */
    val bindAndroidSystem: StateFlow<Boolean> get() = prootManager.bindAndroidSystemFlow

    fun setBindAndroidSystem(enabled: Boolean) {
        prootManager.setBindAndroidSystem(enabled)
    }

    /** #446: which resolvers the local guest's /etc/resolv.conf gets. Pass-through. */
    val dnsMode: StateFlow<sh.haven.core.local.ProotDnsMode> get() = prootManager.dnsModeFlow

    fun setDnsMode(mode: sh.haven.core.local.ProotDnsMode) {
        prootManager.setDnsMode(mode)
    }

    /** #446: custom nameservers, used when [dnsMode] is CUSTOM. Pass-through. */
    val dnsServers: StateFlow<String> get() = prootManager.dnsServersFlow

    fun setDnsServers(servers: String) {
        prootManager.setDnsServers(servers)
    }

    /**
     * Local-shell open requests keyed by the resolved profile id. Collected
     * by HavenNavHost (which is always composed, unlike TerminalScreen) so
     * the request is never dropped while the user is on the Desktop tab.
     * HavenNavHost sets a pending-profile state and animates to the Terminal
     * page; TerminalScreen consumes it once composed and calls
     * addLocalTabForProfile. SharedFlow with replay=0 — a screen rotation
     * shouldn't re-open a shell. See GlassHaven/Haven#168.
     */
    /** A request to open a local shell tab, optionally joining a running desktop (#285). */
    data class LocalShellRequest(val profileId: String, val desktopDeId: String? = null)

    private val _openLocalShellRequests = MutableSharedFlow<LocalShellRequest>(extraBufferCapacity = 4)
    val openLocalShellRequests: SharedFlow<LocalShellRequest> = _openLocalShellRequests.asSharedFlow()

    /**
     * Open a terminal tab into the given distro. Restores the entry-point
     * removed in v5.38.0 when the Connections topbar lost its "Alpine
     * console" icon — see GlassHaven/Haven#168. Reuses the single canonical
     * "Local Shell" profile (mirrors McpTools.openLocalShell so the agent
     * and the user reach the same place) and switches the active distro
     * to [distroId] first, so the proot session boots into the right rootfs.
     *
     * Emits the resolved profile id on [openLocalShellRequests] rather than
     * the AgentUiCommandBus: the bus is replay=0 and TerminalViewModel only
     * collects it while TerminalScreen is composed, so a request fired from
     * the Desktop tab (Terminal not in the pager's composition window) was
     * silently dropped — the tab never opened (#168 regression). HavenNavHost
     * is always composed, so routing through it is reliable.
     */
    fun openShellForDistro(distroId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (prootManager.activeDistroId != distroId) {
                prootManager.setActiveDistroId(distroId)
            }
            val all = connectionRepository.getAll()
            val profile = all.firstOrNull {
                it.connectionType == "LOCAL" && !it.useAndroidShell
            } ?: run {
                val seeded = ConnectionProfile(
                    label = "Local Shell",
                    host = "localhost",
                    username = "",
                    port = 0,
                    connectionType = "LOCAL",
                    useAndroidShell = false,
                )
                connectionRepository.save(seeded)
                connectionRepository.getAll().firstOrNull {
                    it.connectionType == "LOCAL" && it.label == "Local Shell" && !it.useAndroidShell
                } ?: seeded
            }
            _openLocalShellRequests.emit(LocalShellRequest(profile.id))
        }
    }

    /**
     * Open a terminal tab whose environment joins the RUNNING desktop [de]
     * (#285) — DISPLAY / WAYLAND_DISPLAY / XDG_RUNTIME_DIR — so the user can
     * drive the desktop's apps from a shell. Reuses the canonical "Local Shell"
     * profile; unlike [openShellForDistro] it does NOT switch the active distro
     * (desktops run on the active distro, and their sockets live in the shared
     * cacheDir). The deId rides the request so the terminal resolves and merges
     * the desktop env at session creation.
     */
    fun openTerminalInDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = connectionRepository.getAll()
            val profile = all.firstOrNull {
                it.connectionType == "LOCAL" && !it.useAndroidShell
            } ?: run {
                val seeded = ConnectionProfile(
                    label = "Local Shell",
                    host = "localhost",
                    username = "",
                    port = 0,
                    connectionType = "LOCAL",
                    useAndroidShell = false,
                )
                connectionRepository.save(seeded)
                connectionRepository.getAll().firstOrNull {
                    it.connectionType == "LOCAL" && it.label == "Local Shell" && !it.useAndroidShell
                } ?: seeded
            }
            _openLocalShellRequests.emit(LocalShellRequest(profile.id, de.spec.id))
        }
    }

    /**
     * Make [distro] the active distro and install its rootfs.
     * Mirrors the revert-on-failure semantics from ConnectionsViewModel.addDistro:1472.
     */
    fun addDistro(distro: Distro) {
        val previousActive = prootManager.activeDistroId
        prootManager.setActiveDistroId(distro.id)
        viewModelScope.launch {
            try {
                prootManager.installRootfs()
                if (prootManager.state.value is ProotManager.SetupState.Error) {
                    Log.w(TAG, "addDistro: ${distro.id} install failed, reverting to $previousActive")
                    prootManager.setActiveDistroId(previousActive)
                }
            } catch (e: Exception) {
                Log.e(TAG, "addDistro: ${distro.id} install threw", e)
                prootManager.setActiveDistroId(previousActive)
            }
        }
    }

    /**
     * Install a catalog distro for a NON-host arch — runs under qemu-user
     * emulation (#325). Routed through the import path, which downloads the
     * foreign tarball, auto-detects its arch, and writes the marker that
     * arms qemu at launch; registers under the derived "<id>-<arch>" id.
     */
    fun addForeignDistro(distro: Distro, arch: sh.haven.core.local.proot.Arch) {
        val source = distro.rootfsSources[arch] ?: return
        viewModelScope.launch {
            try {
                prootManager.importRootfs(
                    id = prootManager.foreignDistroId(distro, arch),
                    label = "${distro.label} (${arch.slug})",
                    family = distro.family,
                    source = source.url,
                    format = formatFor(source.url),
                    expectedSha256 = source.sha256,
                )
            } catch (e: Exception) {
                Log.e(TAG, "addForeignDistro: ${distro.id}/${arch.slug} threw", e)
            }
        }
    }

    /**
     * Import a custom rootfs tarball as a new distro (#284). [source] is an
     * http(s) URL or an on-device file path; progress shows via the same
     * rootfsSetupState the install path uses.
     */
    fun importRootfs(
        id: String,
        label: String,
        family: sh.haven.core.local.proot.PackageFamily,
        source: String,
    ) {
        viewModelScope.launch {
            try {
                prootManager.importRootfs(id, label, family, source, formatFor(source))
            } catch (e: Exception) {
                Log.e(TAG, "importRootfs: $id threw", e)
            }
        }
    }

    private fun formatFor(source: String): sh.haven.core.local.proot.RootfsFormat = when {
        source.endsWith(".tar.xz") || source.endsWith(".txz") -> sh.haven.core.local.proot.RootfsFormat.TAR_XZ
        source.endsWith(".tar.zst") || source.endsWith(".tar.zstd") -> sh.haven.core.local.proot.RootfsFormat.TAR_ZSTD
        else -> sh.haven.core.local.proot.RootfsFormat.TAR_GZ
    }

    // --- Per-distro custom bind mounts (#301) ---

    /** Bumped when binds change, so the Manage screen recomposes the count. */
    val customBindsRev: StateFlow<Int> get() = prootManager.customBindsRev

    fun customBinds(distroId: String): List<sh.haven.core.local.proot.CustomBind> =
        prootManager.customBinds(distroId)

    fun setCustomBinds(distroId: String, binds: List<sh.haven.core.local.proot.CustomBind>) {
        prootManager.setCustomBinds(distroId, binds)
    }

    /**
     * Delete a distro's rootfs. Stops any DEs running on it first so
     * the session-viewer tabs don't outlive their backing rootfs.
     */
    fun deleteDistro(distroId: String) {
        if (prootManager.activeDistroId == distroId) {
            desktopManager.stopAll()
        }
        prootManager.deleteDistro(distroId)
    }

    /**
     * Install a desktop environment on the active distro. Optional addons
     * land in a follow-up `installAddons` call when the primary install
     * succeeds — same shape ConnectionsViewModel.setupDesktop had before
     * the 3c move.
     */
    fun setupDesktop(
        vncPassword: String,
        de: ProotManager.DesktopEnvironment = ProotManager.DesktopEnvironment.XFCE4,
        addons: Set<ProotManager.DesktopAddon> = emptySet(),
        vncPort: Int? = null,
    ) {
        viewModelScope.launch {
            // Capture the port preference BEFORE the install kicks off
            // so the eventual startDesktop reads it on its first launch.
            // Port 0 from the dialog (e.g. user cleared the field) means
            // "keep auto"; we only persist non-zero overrides.
            if (vncPort != null && vncPort in 5901..5999) {
                desktopManager.setPortPreference(
                    distroId = prootManager.activeDistroId,
                    deId = de.spec.id,
                    port = vncPort,
                )
            }
            prootManager.setupDesktop(vncPassword, de)
            if (addons.isNotEmpty() &&
                prootManager.desktopState.value is ProotManager.DesktopSetupState.Complete
            ) {
                prootManager.installAddons(addons)
            }
        }
    }

    /**
     * Suggested default VNC port for a fresh install on the active
     * distro. Picks the lowest 5900+N not already pinned by another
     * installed DE or in use by a running session. UI reads this once
     * when the install dialog opens and stuffs it into the editable
     * field; subsequent typed edits replace it.
     */
    fun suggestVncPortFor(de: ProotManager.DesktopEnvironment): Int {
        val existing = desktopManager.getPortPreference(prootManager.activeDistroId, de.spec.id)
        if (existing in 5901..5999) return existing
        return desktopManager.suggestNextVncPort(prootManager.activeDistroId)
    }

    /** The stored port preference for [de], or null if auto-assign applies. */
    fun storedVncPortFor(de: ProotManager.DesktopEnvironment): Int? {
        val p = desktopManager.getPortPreference(prootManager.activeDistroId, de.spec.id)
        return if (p in 5901..5999) p else null
    }

    fun uninstallDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch {
            desktopManager.stopDesktop(de)
            prootManager.uninstallDesktop(de)
            prootManager.resetDesktopState()
        }
    }

    /** Session command for the Custom (X11) desktop (#361); blank = unset. */
    val customDesktopCommand: StateFlow<String> = preferencesRepository.customDesktopCommand
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Persist the custom command; [thenStart] launches the Custom desktop
     * after the write commits (sequenced here — starting from the UI right
     * after an async save would race the launch path's preference read).
     */
    fun setCustomDesktopCommand(command: String, thenStart: Boolean = false) {
        viewModelScope.launch {
            preferencesRepository.setCustomDesktopCommand(command)
            if (thenStart && command.isNotBlank()) {
                startDesktop(ProotManager.DesktopEnvironment.CUSTOM_X11)
            }
        }
    }

    /**
     * Start a DE and add it as a tab in the Desktop session viewer.
     * Replaces the old _navigateToVnc → ConnectionsScreen → DesktopViewModel
     * round-trip with a direct `addVncSession` (or `addWaylandTab`) call.
     */
    fun startDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch {
            val shellCmd = preferencesRepository.waylandShellCommand.first()
            Log.d(TAG, "startDesktop: ${LogRedact.of(de.label)} shell=$shellCmd")
            withContext(Dispatchers.IO) {
                desktopManager.startDesktop(de, shellCmd)
            }
            if (de.isNative) {
                kotlinx.coroutines.delay(2000)
                addWaylandTab()
                return@launch
            }
            val port = desktopManager.getVncPort(de) ?: 5901
            // Wait up to 8 s for the Xvnc server to start listening,
            // racing the poll against the DesktopManager's own error
            // state — if the launch throws inside DesktopManager (e.g.
            // missing compositor binary on the Arch nested-Wayland path,
            // GlassHaven/Haven#162 bug B), the manager records ERROR +
            // an errorMessage and we exit the wait immediately with the
            // diagnostic instead of running out the full timeout and
            // leaving the user staring at a spinner. See #169.
            val outcome = withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    val managerInstance = desktopManager.desktops.value[de]
                    if (managerInstance?.state == DesktopManager.DesktopState.ERROR) {
                        return@withContext DesktopStartOutcome.Error(
                            managerInstance.errorMessage ?: "Couldn't start ${de.label}",
                        )
                    }
                    try {
                        java.net.Socket("127.0.0.1", port).close()
                        return@withContext DesktopStartOutcome.Ready
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(500)
                    }
                }
                DesktopStartOutcome.Timeout
            }
            when (outcome) {
                is DesktopStartOutcome.Ready -> {
                    // Confirm to the manager that the desktop is up so
                    // the row's status dot flips from amber STARTING to
                    // green RUNNING. The manager keeps STARTING until
                    // we signal — see DesktopManager.markRunning.
                    desktopManager.markRunning(de)
                }
                is DesktopStartOutcome.Error -> {
                    Log.e(TAG, "startDesktop: ${LogRedact.of(de.label)} failed — ${outcome.message}")
                    val activeDistro = prootManager.activeDistroId
                    // Surface to the user via a toast + the Manage row's
                    // ERROR chip (DesktopManager already holds the state).
                    // NOT persisted to ConnectionLog: that table has a
                    // foreign key to connection_profiles, and a desktop has
                    // no profile row — inserting a synthetic id crashed the
                    // app with SQLITE_CONSTRAINT_FOREIGNKEY on every failing
                    // nested-Wayland start (Sway/Hyprland/Niri), the
                    // regression the #169/#162-B error-surfacing introduced.
                    _userMessages.emit("Couldn't start ${de.label} on $activeDistro: ${outcome.message}")
                    desktopManager.stopDesktop(de)
                    return@launch
                }
                is DesktopStartOutcome.Timeout -> {
                    Log.e(TAG, "startDesktop: VNC port $port not listening after 8s")
                    _userMessages.emit("${de.label} didn't come up within 8s")
                    desktopManager.stopDesktop(de)
                    return@launch
                }
            }
            val pwd = prootManager.storedVncPassword
                ?: connectionRepository.getAll()
                    .firstOrNull { it.isVnc && it.host == "localhost" }
                    ?.vncPassword
            addVncSession(
                host = "localhost",
                port = port,
                password = pwd,
                username = null,
                sshForward = false,
                sshSessionId = null,
                profileId = null,
                colorDepth = "BPP_24_TRUE",
            )
        }
    }

    fun stopDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch(Dispatchers.IO) {
            desktopManager.stopDesktop(de)
        }
    }

    // --- Saved app windows (single-app cage kiosks; see AppWindowDefList) ---
    // The user-facing half of the agent's present_app: define + launch app
    // windows, and restart ones the agent launched. Both land in the same
    // present_media overlay, so this mirrors McpTools.presentApp.

    /** Saved app windows, most-recently-used first, for the Desktop settings list. */
    val appWindowDefs: StateFlow<List<AppWindowDef>> =
        preferencesRepository.appWindowDefs
            .map { list -> list.items.sortedByDescending { it.lastUsed } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Def ids currently launching, so the row's Launch button shows a spinner
     *  during the (~10–15s) cage-kiosk bring-up. */
    private val _launchingIds = MutableStateFlow<Set<String>>(emptySet())
    val launchingIds: StateFlow<Set<String>> = _launchingIds.asStateFlow()

    /** Global default cage resolution/scale, applied when a def doesn't set its own. */
    val appWindowDefaultResolution: StateFlow<String> =
        preferencesRepository.appWindowDefaultResolution
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "auto")
    val appWindowDefaultScale: StateFlow<Float> =
        preferencesRepository.appWindowDefaultScale
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    fun setAppWindowDefaultResolution(resolution: String) {
        viewModelScope.launch { preferencesRepository.setAppWindowDefaultResolution(resolution) }
    }

    fun setAppWindowDefaultScale(scale: Float) {
        viewModelScope.launch { preferencesRepository.setAppWindowDefaultScale(scale) }
    }

    /**
     * Launch a saved app window into the present_media overlay — the same
     * surface the agent's `present_app` uses. Delegates the cage start +
     * present to [AppWindowLauncher] (shared with the home-screen shortcut
     * path); this wrapper only adds the per-def launching spinner and
     * surfaces any returned message on the screen's snackbar.
     */
    fun launchAppWindow(def: AppWindowDef) {
        viewModelScope.launch(Dispatchers.IO) {
            _launchingIds.update { it + def.id }
            try {
                appWindowLauncher.launch(def)?.let { _userMessages.emit(it) }
            } finally {
                _launchingIds.update { it - def.id }
            }
        }
    }

    /** Pin [def] to the home screen as a launcher icon (the app's Linux desktop icon). */
    fun pinAppWindow(def: AppWindowDef) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!appWindowShortcutManager.pinToHomeScreen(def)) {
                _userMessages.emit("This launcher doesn't support home-screen shortcuts")
            }
        }
    }

    fun addAppWindow(
        label: String,
        command: String,
        fullscreen: Boolean,
        resolution: String?,
        scale: Float?,
        runAsRoot: Boolean = false,
    ) {
        viewModelScope.launch {
            preferencesRepository.upsertAppWindowDef(
                label, command, AppWindowOrigin.USER, fullscreen, resolution, scale, runAsRoot,
            )
        }
    }

    fun deleteAppWindow(id: String) {
        viewModelScope.launch { preferencesRepository.deleteAppWindowDef(id) }
    }

    fun updateAppWindow(
        id: String,
        label: String,
        command: String,
        fullscreen: Boolean,
        resolution: String?,
        scale: Float?,
        runAsRoot: Boolean = false,
    ) {
        viewModelScope.launch {
            preferencesRepository.updateAppWindowDef(id, label, command, fullscreen, resolution, scale, runAsRoot)
        }
    }

    // --- Installed-app launcher ("Browse installed apps", xfce4-style menu) ---

    private val _installedApps = MutableStateFlow<AppScanResult?>(null)
    /** Discovered guest GUI apps; null until [refreshInstalledApps] completes. */
    val installedApps: StateFlow<AppScanResult?> = _installedApps.asStateFlow()

    private val _scanningApps = MutableStateFlow(false)
    val scanningApps: StateFlow<Boolean> = _scanningApps.asStateFlow()

    /** Scan the active guest's `.desktop` catalog. Idempotent; safe to re-call. */
    fun refreshInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!prootManager.isRootfsInstalled) {
                _installedApps.value = AppScanResult(emptyList(), 0, 0)
                return@launch
            }
            _scanningApps.value = true
            try {
                _installedApps.value = GuestAppScanner(prootManager).scan()
            } catch (e: Exception) {
                Log.w(TAG, "installed-app scan failed", e)
                _userMessages.emit("Couldn't scan installed apps: ${e.message}")
                _installedApps.value = AppScanResult(emptyList(), 0, 0)
            } finally {
                _scanningApps.value = false
            }
        }
    }

    /** Launch a discovered app in a cage window, recording it as a saved def. */
    fun launchInstalledApp(app: InstalledApp, fullscreen: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            // A saved def with the same label is the CONFIGURED way to run
            // this app — a pack (or the user) may have set the command
            // (xcb platform), multiWindow, and placement rules there. The
            // guest .desktop scan knows none of that (its exec is the raw
            // desktop-file command, e.g. `qmmp-1`), so launching it
            // generically gives a broken variant of an app the user
            // already configured — the two "Qmmp" rows behaving
            // differently was reported as a bug. Delegate on label match.
            val configured = preferencesRepository.appWindowDefs.first()
                .items.find { it.label.equals(app.name, ignoreCase = true) }
            if (configured != null) {
                _launchingIds.update { it + configured.id }
                try {
                    appWindowLauncher.launch(configured)?.let { _userMessages.emit(it) }
                } finally {
                    _launchingIds.update { it - configured.id }
                }
                return@launch
            }
            if (!desktopManager.isCageRuntimeReady()) {
                _userMessages.emit("Installing the cage runtime (sway/wayvnc) — this can take a minute…")
                if (!desktopManager.ensureCageRuntime()) {
                    _userMessages.emit("Couldn't install the cage runtime for ${app.name}")
                    return@launch
                }
            }
            val session = desktopManager.startAppWindow(
                app.exec, appWindowDefaultResolution.value, appWindowDefaultScale.value,
            )
            if (session.state == DesktopManager.DesktopState.RUNNING) {
                presentationManager.presentAppWindow(
                    host = "127.0.0.1",
                    port = session.vncPort,
                    sessionId = session.sessionId,
                    caption = app.name,
                    fullscreen = fullscreen,
                    scale = appWindowDefaultScale.value,
                    resolution = appWindowDefaultResolution.value,
                )
                preferencesRepository.upsertAppWindowDef(app.name, app.exec, AppWindowOrigin.USER, fullscreen)
            } else {
                _userMessages.emit("Couldn't launch ${app.name}: ${session.errorMessage ?: "failed to start"}")
            }
        }
    }

    /**
     * Open an in-app VNC viewer for any running desktop that doesn't have
     * one yet. A desktop started outside the UI start path — notably via
     * the MCP `start_desktop` tool, which brings up the compositor +
     * wayvnc but can't open a viewer — leaves the Sessions view empty.
     * Called when the Sessions/monitor view is shown so the
     * recently-started session actually connects. addVncSession dedupes
     * by host:port, so this is safe to call repeatedly and won't disturb
     * an already-open viewer. Native (labwc) desktops use the Wayland tab
     * path instead and are skipped here.
     */
    fun connectRunningDesktopViewers() {
        viewModelScope.launch {
            val pwd = prootManager.storedVncPassword
                ?: connectionRepository.getAll()
                    .firstOrNull { it.isVnc && it.host == "localhost" }
                    ?.vncPassword
            desktopManager.desktops.value.forEach { (de, inst) ->
                if (inst.state == DesktopManager.DesktopState.RUNNING && !de.isNative) {
                    addVncSession(
                        host = "localhost",
                        port = inst.vncPort,
                        password = pwd,
                        username = null,
                        sshForward = false,
                        sshSessionId = null,
                        profileId = null,
                        colorDepth = "BPP_24_TRUE",
                    )
                }
            }
        }
    }

    fun resetDesktopSetupState() {
        prootManager.resetDesktopState()
    }

    /**
     * Retry the failed install phase (issue #162 Phase 3d). Dispatches
     * via `ProotManager.retry()` which inspects the current Error
     * state and re-runs just the failing layer (or wipes-and-retries
     * for the destructive Download/Extract phases).
     */
    fun retryRootfsInstall() {
        viewModelScope.launch { prootManager.retry() }
    }

    init {
        // Workspace launcher posts here when a DESKTOP / WAYLAND item
        // fires. The matching pager switch happens in HavenNavHost.
        // DesktopViewModel is hoisted to nav scope so emissions always
        // land regardless of which tab the user is currently viewing.
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                when (command) {
                    is sh.haven.core.data.agent.AgentUiCommand.OpenRemoteDesktop ->
                        openRemoteDesktopForProfile(command.profileId)
                    is sh.haven.core.data.agent.AgentUiCommand.OpenWaylandDesktop ->
                        addWaylandTab()
                    is sh.haven.core.data.agent.AgentUiCommand.OpenUsbDrive ->
                        openUsbDrive(command.deviceName)
                    else -> { /* handled by other collectors */ }
                }
            }
        }
    }

    /**
     * Resolve [profileId] to a VNC or RDP profile and dispatch to the
     * matching `add*Session`. Used by the workspace launcher; for
     * tunneled profiles, picks the first connected SSH session for the
     * tunnel profile and lets `addVncSession` / `addRdpSession` throw
     * the existing "SSH session not found" error if none is up.
     */
    private fun openRemoteDesktopForProfile(profileId: String) {
        viewModelScope.launch {
            val profile = connectionRepository.getById(profileId)
            if (profile == null) {
                Log.w(TAG, "OpenRemoteDesktop: profile $profileId not found")
                return@launch
            }
            when {
                profile.isVnc -> {
                    val sshSessionId =
                        if (profile.vncSshForward && profile.vncSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.vncSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addVncSession(
                        host = profile.host,
                        port = profile.vncPort ?: 5900,
                        password = profile.vncPassword,
                        username = profile.vncUsername,
                        sshForward = profile.vncSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        colorDepth = profile.vncColorDepth,
                    )
                }
                profile.isRdp -> {
                    val sshSessionId =
                        if (profile.rdpSshForward && profile.rdpSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.rdpSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addRdpSession(
                        host = profile.host,
                        port = profile.rdpPort,
                        username = profile.rdpUsername.orEmpty(),
                        password = profile.rdpPassword.orEmpty(),
                        domain = profile.rdpDomain.orEmpty(),
                        sshForward = profile.rdpSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        useNla = profile.rdpUseNla,
                        colorDepth = profile.rdpColorDepth,
                    )
                }
                profile.isSpice -> {
                    val sshSessionId =
                        if (profile.spiceSshForward && profile.spiceSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.spiceSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addSpiceSession(
                        host = profile.host,
                        port = profile.spicePort ?: 5900,
                        password = profile.spicePassword,
                        sshForward = profile.spiceSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                    )
                }
                else -> Log.w(
                    TAG,
                    "OpenRemoteDesktop: ${profile.label} is ${profile.connectionType}, not VNC/RDP/SPICE",
                )
            }
        }
    }

    /**
     * Transient user-facing messages from background tasks (desktop start
     * failures, etc.) that need to surface as a Toast/Snackbar. Collected
     * by DesktopScreen. SharedFlow with replay=0 so a screen rotation
     * doesn't re-fire the same message.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    // --- "Open USB drive" in a VM (#287) — keyed by busid, up to
    // QemuManager.MAX_CONCURRENT_DRIVES concurrently OPENING/READY.
    val usbDriveSessions: StateFlow<Map<String, sh.haven.app.usb.UsbDriveVmManager.Status>> = usbDriveVmManager.sessions

    // Whether the persistent USB-helper appliance is provisioned (drives the
    // "Delete USB helper Linux" menu item; the appliance is kept across opens).
    private val _applianceProvisioned = MutableStateFlow(usbDriveVmManager.applianceProvisioned)
    val applianceProvisioned: StateFlow<Boolean> = _applianceProvisioned.asStateFlow()

    init {
        // Announce each drive's slow VM boot outcome on the snackbar (it then
        // auto-opens in Files at its mount) — tracked per busid so opening a
        // second drive doesn't re-fire the first one's message.
        viewModelScope.launch {
            var last = usbDriveVmManager.sessions.value.mapValues { it.value.phase }
            usbDriveVmManager.sessions.collect { byBusid ->
                _applianceProvisioned.value = usbDriveVmManager.applianceProvisioned
                byBusid.forEach { (busid, s) ->
                    if (s.phase != last[busid]) {
                        when (s.phase) {
                            sh.haven.app.usb.UsbDriveVmManager.Phase.READY ->
                                _userMessages.emit("USB drive mounted — opening it in Files.")
                            sh.haven.app.usb.UsbDriveVmManager.Phase.ERROR ->
                                _userMessages.emit("Couldn't open USB drive: ${s.error ?: "VM failed to start"}")
                            else -> {}
                        }
                    }
                }
                last = byBusid.mapValues { it.value.phase }
            }
        }
    }

    /** Delete the persistent USB-helper appliance; the next open re-provisions it. */
    fun deleteUsbAppliance() {
        viewModelScope.launch {
            usbDriveVmManager.deleteAppliance()
            _applianceProvisioned.value = usbDriveVmManager.applianceProvisioned
            _userMessages.emit("USB helper Linux deleted — the next USB-drive open will rebuild it once.")
        }
    }

    /**
     * Non-null while the user must choose between several attached USB
     * drives — the manual menu item has no deviceName, and resolveDrive
     * refuses to guess. The Manage screen renders this as a picker dialog.
     */
    data class UsbDrivePicker(
        val drives: List<sh.haven.core.usb.UsbDeviceInfo>,
        val writable: Boolean,
    )
    private val _usbDrivePicker = MutableStateFlow<UsbDrivePicker?>(null)
    val usbDrivePicker: StateFlow<UsbDrivePicker?> = _usbDrivePicker.asStateFlow()

    fun dismissUsbDrivePicker() {
        _usbDrivePicker.value = null
    }

    /**
     * Non-null while the user must choose a mount route for a drive Android
     * has mounted itself (#603): browse it directly through the Files tab
     * (no VM), or commit to the Linux-VM boot. Shown every time — correlation
     * is a heuristic, so the choice isn't remembered.
     */
    data class UsbRouteChoice(
        val deviceName: String,
        val productName: String?,
        val match: sh.haven.app.usb.UsbMountMatch,
        val writable: Boolean,
    )
    private val _usbRouteChoice = MutableStateFlow<UsbRouteChoice?>(null)
    val usbRouteChoice: StateFlow<UsbRouteChoice?> = _usbRouteChoice.asStateFlow()

    fun dismissUsbRouteChoice() {
        _usbRouteChoice.value = null
    }

    /** Commit to the Linux-VM boot (the pre-#603 open path, unchanged). */
    fun openUsbDriveVm(deviceName: String?, writable: Boolean = false) {
        viewModelScope.launch {
            try {
                usbDriveVmManager.open(deviceName, writable)
                _userMessages.emit("Opening the USB drive in a Linux VM — this can take a few minutes; progress is shown below.")
            } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
                _userMessages.emit(e.message ?: "Couldn't open USB drive")
            }
        }
    }

    /** Open the Android-mounted volume directly in the Files tab — no VM boot. */
    fun openUsbDriveDirectly(choice: UsbRouteChoice) {
        _usbRouteChoice.value = null
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath("local", choice.match.volume.path),
        )
        val where = choice.match.volume.description ?: choice.match.volume.path
        _userMessages.tryEmit("Browsing $where directly — no VM needed.")
    }

    /**
     * Boot a VM that mounts the attached USB drive; its files appear as a
     * connection. When Android has already mounted the drive (#603), a route
     * choice is offered first; without a mount the VM path runs as before,
     * with a one-line why (ext4/GPT/LUKS are only readable through the VM).
     */
    fun openUsbDrive(deviceName: String? = null, writable: Boolean = false) {
        if (deviceName == null) {
            val drives = usbDriveVmManager.massStorageDevices()
            if (drives.size > 1) {
                _usbDrivePicker.value = UsbDrivePicker(drives, writable)
                return
            }
        }
        _usbDrivePicker.value = null
        viewModelScope.launch {
            val match = try {
                usbDriveVmManager.androidMount(deviceName)
            } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
                _userMessages.emit(e.message ?: "Couldn't open USB drive")
                return@launch
            }
            if (match != null) {
                val info = usbDriveVmManager.massStorageDevices().firstOrNull { it.deviceName == match.deviceName }
                _usbRouteChoice.value = UsbRouteChoice(match.deviceName, info?.productName, match, writable)
            } else {
                _userMessages.emit(
                    "Android hasn't mounted this drive — opening it in the Linux VM (the only way to read ext4/GPT/LUKS filesystems).",
                )
                openUsbDriveVm(deviceName, writable)
            }
        }
    }

    fun closeUsbDrive(busid: String) {
        viewModelScope.launch {
            usbDriveVmManager.close(busid)
            _userMessages.emit("USB drive VM closed.")
        }
    }

    /** Unlock a LUKS-encrypted partition on an open USB-drive VM. */
    fun unlockUsbDrivePartition(busid: String, devicePath: String, passphrase: String) {
        viewModelScope.launch {
            try {
                usbDriveVmManager.unlockPartition(busid, devicePath, passphrase)
                _userMessages.emit("Partition unlocked.")
            } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
                _userMessages.emit(e.message ?: "Couldn't unlock the partition")
            }
        }
    }

    // --- System VM (#326) — a full QEMU x86_64 VM in the active distro,
    // viewed over VNC on loopback. One at a time (TCG + phone RAM). The
    // manager owns the lifecycle; this exposes its state + image store to
    // the Manage screen and auto-opens a VNC tab once the VM is up.

    val systemVmState: StateFlow<sh.haven.core.local.SystemVmManager.VmState?> get() = systemVmManager.state

    private val _systemVmImages = MutableStateFlow<List<sh.haven.core.local.SystemVmManager.VmImage>>(emptyList())
    val systemVmImages: StateFlow<List<sh.haven.core.local.SystemVmManager.VmImage>> = _systemVmImages.asStateFlow()

    /** True during an import or a boot (both slow, and both hold the manager mutex). */
    private val _systemVmBusy = MutableStateFlow(false)
    val systemVmBusy: StateFlow<Boolean> = _systemVmBusy.asStateFlow()

    /**
     * The import dialog's whole draft — open/closed AND the three fields —
     * lives here rather than in the composable, because a rotation recreates
     * the activity and took the dialog with it. `remember` loses it outright,
     * and `rememberSaveable` doesn't save it either: the composable holding it
     * is no longer in the composition when state is saved. A ViewModel survives
     * config changes by construction, which is the property this needs.
     *
     * The fields have to move together with the flag. Hoisting only the flag
     * was verified on-device to be WORSE than not hoisting at all: the dialog
     * came back looking intact with the arch silently reset to x86_64, which
     * for this field means an arm64 image gets recorded as x86_64 and then
     * never boots — the exact trap the dialog's own hint warns about. A dialog
     * that vanishes is at least an obvious loss.
     */
    private val _showSystemVmImport = MutableStateFlow(false)
    val showSystemVmImport: StateFlow<Boolean> = _showSystemVmImport.asStateFlow()

    private val _systemVmImportLabel = MutableStateFlow("")
    val systemVmImportLabel: StateFlow<String> = _systemVmImportLabel.asStateFlow()

    private val _systemVmImportSource = MutableStateFlow("")
    val systemVmImportSource: StateFlow<String> = _systemVmImportSource.asStateFlow()

    private val _systemVmImportArch = MutableStateFlow(sh.haven.core.local.VmArch.X86_64)
    val systemVmImportArch: StateFlow<sh.haven.core.local.VmArch> = _systemVmImportArch.asStateFlow()

    // --- Desktop-manager dialog drafts -------------------------------------
    //
    // Same reason as the system-VM import draft below, and the same rule: flag
    // AND fields together, never the flag alone. These screens sit in a
    // HorizontalPager under a nav host that can leave the composition, so a
    // rotation takes any open dialog with it.
    //
    // AppWindowDialog and DesktopSetupDialog are deliberately NOT here yet:
    // their state is derived (from `initial`) or key-reset (the setup dialog's
    // port field re-initialises when the selected DE changes), and moving that
    // faithfully needs watching on a device rather than reasoning about it.

    /**
     * The app-window add/edit dialog's draft; null when closed. [editingId] is
     * the AppWindowDef being edited, or null for a new one — the add/edit
     * distinction has to travel with the draft, since it decides both the title
     * and whether Save updates or inserts.
     *
     * The caller builds the draft (the resolution presets that decide
     * [customMode] live with the UI), so this only stores and clears it.
     */
    data class AppWindowDraft(
        val editingId: String? = null,
        val label: String = "",
        val command: String = "",
        val fullscreen: Boolean = false,
        val runAsRoot: Boolean = false,
        val resToken: String? = null,
        val customMode: Boolean = false,
        val customRes: String = "",
        val scale: Float? = null,
    )

    private val _appWindowDraft = MutableStateFlow<AppWindowDraft?>(null)
    val appWindowDraft: StateFlow<AppWindowDraft?> = _appWindowDraft.asStateFlow()

    fun openAppWindowDialog(draft: AppWindowDraft) { _appWindowDraft.value = draft }

    fun setAppWindowDraft(draft: AppWindowDraft) { _appWindowDraft.value = draft }

    fun dismissAppWindowDialog() { _appWindowDraft.value = null }

    /**
     * The desktop-setup dialog: which DE it was opened for (null = closed) plus
     * its draft. The port seeds from the suggestion at open time, which is what
     * the old `rememberSaveable(selectedDe, suggestedVncPort)` amounted to — the
     * dialog is opened *for* a DE and neither key can change while it is up.
     */
    data class DesktopSetupDraft(
        val password: String = "haven",
        val shellCmd: String = "/bin/sh",
        val portText: String = "",
        val addons: Set<ProotManager.DesktopAddon> = emptySet(),
    )

    private val _setupDesktopDe = MutableStateFlow<ProotManager.DesktopEnvironment?>(null)
    val setupDesktopDe: StateFlow<ProotManager.DesktopEnvironment?> = _setupDesktopDe.asStateFlow()

    private val _desktopSetupDraft = MutableStateFlow(DesktopSetupDraft())
    val desktopSetupDraft: StateFlow<DesktopSetupDraft> = _desktopSetupDraft.asStateFlow()

    fun openDesktopSetup(de: ProotManager.DesktopEnvironment, suggestedVncPort: Int) {
        _desktopSetupDraft.value = DesktopSetupDraft(portText = suggestedVncPort.toString())
        _setupDesktopDe.value = de
    }

    fun setDesktopSetupDraft(draft: DesktopSetupDraft) { _desktopSetupDraft.value = draft }

    fun dismissDesktopSetup() {
        _setupDesktopDe.value = null
        _desktopSetupDraft.value = DesktopSetupDraft()
    }

    private val _showInstalledApps = MutableStateFlow(false)
    val showInstalledApps: StateFlow<Boolean> = _showInstalledApps.asStateFlow()

    fun setShowInstalledApps(open: Boolean) { _showInstalledApps.value = open }

    /** Non-null while the Custom (X11) command dialog is open; the value is "also start the desktop after saving" (#361). */
    private val _customCmdStartAfter = MutableStateFlow<Boolean?>(null)
    val customCmdStartAfter: StateFlow<Boolean?> = _customCmdStartAfter.asStateFlow()

    private val _customCmdDraft = MutableStateFlow("")
    val customCmdDraft: StateFlow<String> = _customCmdDraft.asStateFlow()

    fun openCustomCmdDialog(initial: String, startAfterSave: Boolean) {
        _customCmdDraft.value = initial
        _customCmdStartAfter.value = startAfterSave
    }

    fun setCustomCmdDraft(value: String) { _customCmdDraft.value = value }

    fun dismissCustomCmdDialog() {
        _customCmdStartAfter.value = null
        _customCmdDraft.value = ""
    }

    /** The bring-your-own-rootfs import dialog's draft (#284). */
    data class ImportRootfsDraft(
        val id: String = "",
        val label: String = "",
        val source: String = "",
        val family: sh.haven.core.local.proot.PackageFamily = sh.haven.core.local.proot.PackageFamily.APT,
    )

    private val _showImportRootfs = MutableStateFlow(false)
    val showImportRootfs: StateFlow<Boolean> = _showImportRootfs.asStateFlow()

    private val _importRootfsDraft = MutableStateFlow(ImportRootfsDraft())
    val importRootfsDraft: StateFlow<ImportRootfsDraft> = _importRootfsDraft.asStateFlow()

    fun openImportRootfs() {
        _importRootfsDraft.value = ImportRootfsDraft()
        _showImportRootfs.value = true
    }

    fun setImportRootfsDraft(draft: ImportRootfsDraft) { _importRootfsDraft.value = draft }

    fun dismissImportRootfs() {
        _showImportRootfs.value = false
        _importRootfsDraft.value = ImportRootfsDraft()
    }

    /** The custom-binds editor's rows as (host, guest) pairs — a plain list, replaced wholesale on each edit. */
    private val _showCustomBinds = MutableStateFlow(false)
    val showCustomBinds: StateFlow<Boolean> = _showCustomBinds.asStateFlow()

    private val _customBindsDraft = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val customBindsDraft: StateFlow<List<Pair<String, String>>> = _customBindsDraft.asStateFlow()

    fun openCustomBinds(initial: List<sh.haven.core.local.proot.CustomBind>) {
        _customBindsDraft.value = initial.map { it.host to it.guest }
        _showCustomBinds.value = true
    }

    fun setCustomBindsDraft(rows: List<Pair<String, String>>) { _customBindsDraft.value = rows }

    fun dismissCustomBinds() {
        _showCustomBinds.value = false
        _customBindsDraft.value = emptyList()
    }

    private val _showUsbWritableConfirm = MutableStateFlow(false)
    val showUsbWritableConfirm: StateFlow<Boolean> = _showUsbWritableConfirm.asStateFlow()

    fun setShowUsbWritableConfirm(open: Boolean) { _showUsbWritableConfirm.value = open }

    /** The distro a delete confirmation is currently asking about — the target has to survive with the flag, or the dialog returns pointing at nothing. */
    private val _distroPendingDelete = MutableStateFlow<sh.haven.core.local.proot.Distro?>(null)
    val distroPendingDelete: StateFlow<sh.haven.core.local.proot.Distro?> = _distroPendingDelete.asStateFlow()

    fun setDistroPendingDelete(distro: sh.haven.core.local.proot.Distro?) { _distroPendingDelete.value = distro }

    /**
     * A distro add the confirm dialog is currently asking about (#620). Tapping
     * "+ <name> (~MB)" in the distro dropdown downloads a few hundred MB, so it
     * goes through a confirm first, mirroring [distroPendingDelete]. Holds the
     * target (and the arch for emulated foreign adds) so the dialog can act on it.
     */
    sealed interface PendingAdd {
        data class Native(val distro: Distro) : PendingAdd
        data class Foreign(val distro: Distro, val arch: sh.haven.core.local.proot.Arch) : PendingAdd
    }

    private val _distroPendingAdd = MutableStateFlow<PendingAdd?>(null)
    val distroPendingAdd: StateFlow<PendingAdd?> = _distroPendingAdd.asStateFlow()

    fun requestAddDistro(distro: Distro) { _distroPendingAdd.value = PendingAdd.Native(distro) }

    fun requestAddForeignDistro(distro: Distro, arch: sh.haven.core.local.proot.Arch) {
        _distroPendingAdd.value = PendingAdd.Foreign(distro, arch)
    }

    fun dismissDistroPendingAdd() { _distroPendingAdd.value = null }

    fun confirmDistroPendingAdd() {
        when (val pending = _distroPendingAdd.value) {
            is PendingAdd.Native -> addDistro(pending.distro)
            is PendingAdd.Foreign -> addForeignDistro(pending.distro, pending.arch)
            null -> {}
        }
        _distroPendingAdd.value = null
    }

    fun openSystemVmImport() { _showSystemVmImport.value = true }

    fun setSystemVmImportLabel(value: String) { _systemVmImportLabel.value = value }

    fun setSystemVmImportSource(value: String) { _systemVmImportSource.value = value }

    fun setSystemVmImportArch(value: sh.haven.core.local.VmArch) { _systemVmImportArch.value = value }

    /** Closes the dialog and clears the draft, so the next open starts clean rather than resurrecting a cancelled one. */
    fun dismissSystemVmImport() {
        _showSystemVmImport.value = false
        _systemVmImportLabel.value = ""
        _systemVmImportSource.value = ""
        _systemVmImportArch.value = sh.haven.core.local.VmArch.X86_64
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { refreshSystemVmImages() }
    }

    private fun refreshSystemVmImages() {
        _systemVmImages.value = runCatching { systemVmManager.listImages() }.getOrDefault(emptyList())
    }

    /**
     * Import a bootable disk image ([source] = http(s) URL or on-device path),
     * normalised to qcow2. [arch] is the guest CPU the image holds — recorded
     * with it, since a qcow2 doesn't say and the boot silently hangs on the
     * wrong target.
     */
    fun importSystemVmImage(label: String, source: String, arch: sh.haven.core.local.VmArch) {
        val id = label.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
        if (id.isEmpty()) {
            viewModelScope.launch { _userMessages.emit("Give the image a name (letters/digits).") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _systemVmBusy.value = true
            try {
                systemVmManager.importImage(id, label.ifBlank { id }, source.trim(), arch = arch)
                refreshSystemVmImages()
                _userMessages.emit("Imported \"$label\".")
            } catch (e: Exception) {
                _userMessages.emit(e.message ?: "Couldn't import the image")
            } finally {
                _systemVmBusy.value = false
            }
        }
    }

    /** Boot a stored image and, once its VNC server is up, open a viewer tab on it. */
    fun startSystemVm(imageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _systemVmBusy.value = true
            try {
                val st = systemVmManager.startImage(imageId)
                val port = st.vncPort
                if (st.status == sh.haven.core.local.SystemVmManager.Status.RUNNING && port != null) {
                    addVncSession(host = "127.0.0.1", port = port, password = null, colorDepth = "BPP_24_TRUE")
                }
            } catch (e: Exception) {
                _userMessages.emit(e.message ?: "Couldn't start the VM")
            } finally {
                _systemVmBusy.value = false
            }
        }
    }

    fun stopSystemVm() {
        viewModelScope.launch(Dispatchers.IO) { systemVmManager.stop() }
    }

    fun deleteSystemVmImage(imageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            systemVmManager.deleteImage(imageId)
            refreshSystemVmImages()
        }
    }

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

    /**
     * Reconnect a tab that hit "connection lost" (e.g. no server listening),
     * from the inline Retry button — so a dead desktop isn't a long-press dead
     * end (#121, KoriKraut). Profile-backed tabs re-run the full connect via the
     * AgentUiCommand bus (same path a tap uses), which re-establishes the SSH
     * tunnel with a fresh session instead of reusing the torn-down one. Ad-hoc
     * VNC tabs (no profile) fall back to re-dialling the saved original params,
     * mirroring [acceptBandwidthSuggestion].
     */
    fun retryTab(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        val profileId = when (tab) {
            is DesktopTab.Vnc -> tab.profileId
            is DesktopTab.Rdp -> tab.profileId
            is DesktopTab.Spice -> tab.profileId
            else -> null
        }
        if (profileId != null) {
            closeTab(tabId)
            agentUiCommandBus.emit(
                sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profileId),
            )
            return
        }
        // Ad-hoc VNC tab with no backing profile — re-dial the original params.
        if (tab is DesktopTab.Vnc) {
            val host = tab.originalHost.ifEmpty { return }
            val port = tab.originalPort
            val username = tab.originalUsername
            val password = tab.originalPassword
            val sshForward = tab.sshForward
            val sshSessionId = tab.sshSessionId
            val colorDepth = tab.colorDepth
            closeTab(tabId)
            addVncSession(
                host = host,
                port = port,
                password = password,
                username = username,
                sshForward = sshForward,
                sshSessionId = sshSessionId,
                profileId = null,
                colorDepth = colorDepth,
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
                    is DesktopTab.Spice -> tab.profileId == profileId
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
                protocol == "SPICE" && tab is DesktopTab.Spice && tab.profileId == null ->
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

            // Per-tab live state, declared up front so the tab can be shown in
            // a connecting state (connected=false, no frame) before the
            // synchronous handshake runs — mirroring RDP, which previously was
            // the only protocol to show "connecting".
            val connected = MutableStateFlow(false)
            val frame = MutableStateFlow<Bitmap?>(null)
            val error = MutableStateFlow<String?>(null)
            val cursor = MutableStateFlow<CursorOverlay?>(null)
            val pointerPos = MutableStateFlow(0 to 0)
            val bandwidthSuggestion = MutableStateFlow<String?>(null)

            // Hoisted out of try so the catch / onError can clean it up
            // when the dial fails (#121). tunnelLease owns the forward +
            // dependent release + the parent-gone teardown callback.
            var tunnelLease: SshSessionManager.TunnelLease? = null
            var tabAdded = false
            try {
                val actualHost: String
                val actualPort: Int
                // WireGuard / Tailscale TunneledConnection — non-null when
                // the profile has tunnelConfigId set and we're not going
                // through SSH RemoteForward instead.
                var tunneledConn: TunneledConnection? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    // Forward target as seen FROM the SSH server. When the VNC
                    // server is the jump host itself (#104: wayvnc binds
                    // loopback only, and dialing the host's own external
                    // address back through sshd hits ECONNREFUSED), the target
                    // must be 127.0.0.1 — detected by the VNC host matching
                    // the jump profile's host. Any OTHER value is a distinct
                    // machine behind the jump host and must be honoured
                    // verbatim; hardcoding loopback here sent every jump-host
                    // topology to the wrong machine (#538). RDP/SPICE below
                    // already pass the host through.
                    val jumpHost = profileId?.let { pid ->
                        connectionRepository.getById(pid)?.vncSshProfileId
                            ?.let { connectionRepository.getById(it)?.host }
                    }
                    val target = vncForwardTarget(host, jumpHost)
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, target, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "VNC SSH tunnel: localhost:$lp -> ${LogRedact.of(target)}:$port (via ${LogRedact.of(host)})")
                    // Tie this tab to the SSH session: if the SSH is torn down
                    // for any reason (Connections-list disconnect, network
                    // death, jump cascade), the lease fires and closes the tab
                    // instead of leaving it over a dead pipe (#121).
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                    // Try the WireGuard / Tailscale path. Returns null when
                    // the profile has no tunnelConfigId — caller falls
                    // through to a direct kernel-socket dial in client.start.
                    if (profileId != null) {
                        val profile = connectionRepository.getById(profileId)
                        if (profile != null) {
                            tunneledConn = tunnelResolver.dial(profile, actualHost, actualPort, 30_000)
                            if (tunneledConn != null) {
                                Log.d(TAG, "VNC dialed via tunnel ${profile.tunnelConfigId} -> $actualHost:$actualPort")
                            }
                        }
                    }
                }

                val config = VncConfig().apply {
                    this.colorDepth = runCatching { ColorDepth.valueOf(colorDepth) }
                        .getOrDefault(ColorDepth.BPP_24_TRUE)
                    shared = true
                    if (!password.isNullOrEmpty()) passwordSupplier = { password }
                    if (!username.isNullOrEmpty()) usernameSupplier = { username }
                    onScreenUpdate = { bitmap -> frame.value = bitmap }
                    onCursorUpdate = { bmp, hx, hy ->
                        cursor.value = if (bmp == null) null else CursorOverlay(bmp, hx, hy)
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
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                        // VncClient.start() catches setup failures (e.g. the
                        // handshake EOF when no VNC server is listening behind
                        // the SSH forward) and routes them here instead of
                        // throwing, so the catch block below never runs for
                        // them — and a mid-session drop lands here too. Release
                        // the SSH tunnel (lease) + any WG/Tailscale dependent so
                        // nothing is left orphaned with a green dot (#121).
                        // Idempotent, so the later disconnectTab stays safe.
                        tunnelLease?.close()
                        releaseSshTunnelDependent(profileId)
                    }
                    onRemoteClipboard = { text ->
                        // Length only. A clipboard is where a password manager puts
                        // the password, and this line put 50 characters of it in
                        // logcat for anyone who reads a bug report (#518).
                        Log.d(TAG, "VNC clipboard ($tabId): ${text.length} chars")
                    }
                }

                val client = VncClient(config)

                // Add the tab now (connected=false) so the Desktop screen
                // shows a connecting state during the (synchronous) handshake,
                // like RDP does via onConnected. The handshake below flips
                // _connected in place. A connected tab keeps its lease so it's
                // torn down with the SSH session; a failed handshake leaves the
                // error visible (onError already released the lease, and that
                // release removed the lease before any SSH teardown could fire
                // its parent-gone callback, so the error tab isn't auto-closed).
                val newTab = DesktopTab.Vnc(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    client = client,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    _bandwidthSuggestion = bandwidthSuggestion,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                    originalHost = host,
                    originalPort = port,
                    originalUsername = username,
                    originalPassword = password,
                    sshForward = sshForward,
                    sshSessionId = sshSessionId,
                    colorDepth = colorDepth,
                )
                _tabs.value = _tabs.value.toMutableList().apply { add(newTab) }
                tabAdded = true
                pauseAllExcept(_tabs.value.size - 1)
                _activeTabIndex.value = _tabs.value.size - 1
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
                // Expose the rendered frame + cursor to MCP capture_desktop_tab.
                desktopSessionRegistry.registerFrameHandle(
                    profileId,
                    DesktopFrameHandle(
                        protocol = "VNC",
                        frame = { frame.value },
                        cursor = { cursor.value?.let { CursorSnapshot(it.bitmap, it.hotspotX, it.hotspotY) } },
                        pointer = { pointerPos.value },
                    ),
                )
                // Expose mouse/clipboard input to the MCP remote-desktop tools.
                desktopSessionRegistry.registerInputHandle(
                    profileId,
                    DesktopInputHandle(
                        protocol = "VNC",
                        mouseMove = { x, y -> newTab.remoteDesktop.sendMouseMove(x, y) },
                        mouseClick = { x, y, button -> newTab.remoteDesktop.sendMouseClick(x, y, button) },
                        mouseWheel = { deltaY -> newTab.remoteDesktop.sendMouseWheel(deltaY) },
                        clipboard = { text -> newTab.remoteDesktop.sendClipboardText(text) },
                    ),
                )
                // Let MCP disconnect_profile close this tab even when there's
                // no tunnel lease to cascade through (direct connections, #437).
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }

                val tc = tunneledConn
                if (tc != null) {
                    client.start(TunneledSocket(tc, actualHost, actualPort), actualHost)
                } else {
                    // Knock only on the direct path. SSH-forward goes via
                    // a localhost tunnel (handled at the SSH connect site)
                    // and a userspace WG/Tailscale tunnel doesn't expose
                    // raw kernel sockets for knockd to see.
                    if (!sshForward && profileId != null) {
                        runVncKnockIfConfigured(profileId, actualHost)
                    }
                    client.start(actualHost, actualPort)
                }
                // start() runs the handshake synchronously and swallows setup
                // failures into onError (above), which has flagged the error +
                // set the registry to ERROR. Only flip to connected on success.
                if (error.value == null) {
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "VNC connect failed", e)
                // Connect-failure cleanup (#121): release the SSH tunnel lease
                // (removes the forward + releases the dependent, tearing down
                // the SSH iff it was opened solely for this tunnel) and any
                // WG/Tailscale dependent. Idempotent / no-op if the failure
                // happened before the lease was acquired.
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                error.value = VncViewModel.describeError(e, host, port)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                // If the failure happened before the tab was added (e.g. tunnel
                // setup threw), surface an error tab so the user sees why.
                if (!tabAdded) {
                    val errorTab = DesktopTab.Vnc(
                        id = tabId,
                        label = label,
                        colorTag = colorTag,
                        client = VncClient(VncConfig()),
                        _connected = connected,
                        _frame = frame,
                        _error = error,
                        _cursor = cursor,
                        _pointerPos = pointerPos,
                        _bandwidthSuggestion = bandwidthSuggestion,
                        profileId = profileId,
                    )
                    val tabs = _tabs.value.toMutableList()
                    tabs.add(errorTab)
                    _tabs.value = tabs
                    _activeTabIndex.value = tabs.size - 1
                }
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

            // Hoisted out of try so the catch / onError can clean it up
            // when the dial fails (#121). tunnelLease owns the forward +
            // dependent release + the parent-gone teardown callback.
            var tunnelLease: SshSessionManager.TunnelLease? = null
            try {
                val actualHost: String
                val actualPort: Int

                // SOCKS5 endpoint of any WireGuard / Tailscale tunnel the
                // profile selected (#149 step 4 + 9). Only consulted when
                // not going through SSH RemoteForward — that already
                // tunnels the connection via 127.0.0.1:<localPort>.
                var rdpSocksProxy: sh.haven.rdp.SocksProxyConfig? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "RDP SSH tunnel: localhost:$lp -> ${LogRedact.host(host, port)}")
                    // Tie this tab to the SSH session so it closes if the SSH
                    // is torn down for any reason (#121).
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                    if (profileId != null) {
                        val profile = connectionRepository.getById(profileId)
                        if (profile != null) {
                            tunnelResolver.socksEndpoint(profile)?.let { addr ->
                                rdpSocksProxy = sh.haven.rdp.SocksProxyConfig(
                                    host = addr.hostString,
                                    port = addr.port.toUShort(),
                                )
                                Log.d(TAG, "RDP routed via SOCKS5 ${LogRedact.of(addr.hostString)}:${addr.port} -> ${LogRedact.host(host, port)}")
                            }
                        }
                    }
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)
                val cursor = MutableStateFlow<CursorOverlay?>(null)
                val pointerPos = MutableStateFlow(0 to 0)

                val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
                val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null

                // Was hardcoded to the RdpSession defaults (1920x1080) with no
                // way to change it, so a server drawing anything else had its
                // updates discarded (#422 — VirtualBox defaults to 2560x1600
                // and never announces a resize).
                val rdpWidth = preferencesRepository.rdpDesktopWidth.first()
                val rdpHeight = preferencesRepository.rdpDesktopHeight.first()

                val session = RdpSession(
                    sessionId = "rdp-$tabId",
                    host = actualHost,
                    port = actualPort,
                    width = rdpWidth,
                    height = rdpHeight,
                    username = username,
                    password = password,
                    domain = domain,
                    useNla = useNla,
                    colorDepth = colorDepth,
                    verboseBuffer = verboseBuffer,
                    socksProxy = rdpSocksProxy,
                )
                session.onFrameUpdate = { bitmap -> frame.value = bitmap }
                session.onCursorUpdate = { bmp, hx, hy ->
                    cursor.value = if (bmp == null) null else CursorOverlay(bmp, hx, hy)
                }
                session.onCursorPosition = { x, y -> pointerPos.value = x to y }
                session.onError = { e ->
                    Log.e(TAG, "RDP error on tab $tabId", e)
                    error.value = RdpViewModel.describeError(e, host, port)
                    connected.value = false
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                        }
                    }
                    // RdpSession.start() reports connect failures via this
                    // callback rather than throwing, so the catch block never
                    // runs for them — release the SSH tunnel lease + any WG
                    // dependent so nothing lingers with a green dot (#121,
                    // same shape as the VNC path). Idempotent with disconnectTab.
                    tunnelLease?.close()
                    releaseSshTunnelDependent(profileId)
                }
                session.onConnected = { _, _ ->
                    // Real handshake complete — only now flip the tab to
                    // "connected". Before this the UI stays on a Connecting
                    // state rather than a misleading empty framebuffer.
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val startLog = session.drainVerboseLog()
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
                        }
                    }
                }
                session.onDisconnected = {
                    // Session ended without a surfaced error (server logoff /
                    // transport death). Skip if the tab was already closed by
                    // the user — disconnectTab has cleared the registry and a
                    // late status write would resurrect a ghost entry (#437).
                    if (_tabs.value.any { it.id == tabId }) {
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.DISCONNECTED)
                        if (profileId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                connectionLogRepository.logEvent(
                                    profileId,
                                    ConnectionLog.Status.DISCONNECTED,
                                    details = "Session ended by server",
                                    verboseLog = session.drainVerboseLog(),
                                )
                            }
                        }
                    }
                }

                // Knock only on the direct path. SSH-forward goes via a
                // localhost tunnel (knock happened at the SSH connect)
                // and the SOCKS path runs through a userspace tunnel
                // that knockd can't observe.
                if (!sshForward && rdpSocksProxy == null && profileId != null) {
                    runVncKnockIfConfigured(profileId, actualHost)
                }

                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
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
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                )

                val tabs = _tabs.value.toMutableList()
                tabs.add(tab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
                // Expose the rendered frame + cursor to MCP capture_desktop_tab.
                desktopSessionRegistry.registerFrameHandle(
                    profileId,
                    DesktopFrameHandle(
                        protocol = "RDP",
                        frame = { frame.value },
                        cursor = { cursor.value?.let { CursorSnapshot(it.bitmap, it.hotspotX, it.hotspotY) } },
                        pointer = { pointerPos.value },
                    ),
                )
                // Expose mouse/clipboard input to the MCP remote-desktop tools.
                desktopSessionRegistry.registerInputHandle(
                    profileId,
                    DesktopInputHandle(
                        protocol = "RDP",
                        mouseMove = { x, y -> tab.remoteDesktop.sendMouseMove(x, y) },
                        mouseClick = { x, y, button -> tab.remoteDesktop.sendMouseClick(x, y, button) },
                        mouseWheel = { deltaY -> tab.remoteDesktop.sendMouseWheel(deltaY) },
                        clipboard = { text -> tab.remoteDesktop.sendClipboardText(text) },
                    ),
                )
                // Let MCP disconnect_profile close this tab even when there's
                // no tunnel lease to cascade through (direct connections, #437).
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RDP connect failed", e)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                // Connect-failure cleanup — same reasoning as the VNC
                // path above (#121): release the lease + WG dependent.
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
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

    // --- SPICE sessions ---

    fun addSpiceSession(
        host: String,
        port: Int,
        password: String?,
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingIdx = findExistingTab(profileId, host, port, "SPICE")
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

            var tunnelLease: SshSessionManager.TunnelLease? = null
            try {
                val actualHost: String
                val actualPort: Int
                // SPICE's Rust client does its own TCP dial — no socket/SOCKS
                // injection like VNC/RDP — so SSH-forward goes via a local
                // -L forward and we hand it 127.0.0.1:<localPort>.
                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "SPICE SSH tunnel: localhost:$lp -> ${LogRedact.host(host, port)}")
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)
                val cursor = MutableStateFlow<CursorOverlay?>(null)
                val pointerPos = MutableStateFlow(0 to 0)

                val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
                val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null

                val session = SpiceSession(
                    sessionId = "spice-$tabId",
                    host = actualHost,
                    port = actualPort,
                    password = password,
                    verboseBuffer = verboseBuffer,
                )
                session.onFrameUpdate = { bitmap -> frame.value = bitmap }
                session.onCursorUpdate = { bmp, hx, hy ->
                    cursor.value = if (bmp == null) null else CursorOverlay(bmp, hx, hy)
                }
                session.onCursorPosition = { x, y -> pointerPos.value = x to y }
                session.onError = { e ->
                    Log.e(TAG, "SPICE error on tab $tabId", e)
                    error.value = e.message ?: "SPICE connection to $host:$port failed"
                    connected.value = false
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                        }
                    }
                    tunnelLease?.close()
                    releaseSshTunnelDependent(profileId)
                }
                session.onConnected = { _, _ ->
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val startLog = session.drainVerboseLog()
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
                        }
                    }
                }
                session.onDisconnected = {
                    // Session ended without a surfaced error — mirror the RDP
                    // path so the tab never claims "connected" past death (#437).
                    if (_tabs.value.any { it.id == tabId }) {
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.DISCONNECTED)
                        if (profileId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                connectionLogRepository.logEvent(
                                    profileId,
                                    ConnectionLog.Status.DISCONNECTED,
                                    details = "Session ended by server",
                                    verboseLog = session.drainVerboseLog(),
                                )
                            }
                        }
                    }
                }

                // Create + show the tab before start() — SPICE's connect()
                // blocks until established, so the UI sits on "Connecting"
                // until onConnected flips it (or onError sets the error).
                val tab = DesktopTab.Spice(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = session,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                )
                val tabs = _tabs.value.toMutableList()
                tabs.add(tab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
                desktopSessionRegistry.registerFrameHandle(
                    profileId,
                    DesktopFrameHandle(
                        protocol = "SPICE",
                        frame = { frame.value },
                        cursor = { cursor.value?.let { CursorSnapshot(it.bitmap, it.hotspotX, it.hotspotY) } },
                        pointer = { pointerPos.value },
                    ),
                )
                desktopSessionRegistry.registerInputHandle(
                    profileId,
                    DesktopInputHandle(
                        protocol = "SPICE",
                        mouseMove = { x, y -> tab.remoteDesktop.sendMouseMove(x, y) },
                        mouseClick = { x, y, button -> tab.remoteDesktop.sendMouseClick(x, y, button) },
                        mouseWheel = { deltaY -> tab.remoteDesktop.sendMouseWheel(deltaY) },
                        clipboard = { text -> tab.remoteDesktop.sendClipboardText(text) },
                    ),
                )
                // Let MCP disconnect_profile close this tab even when there's
                // no tunnel lease to cascade through (direct connections, #437).
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }

                // Knock only on the direct path (SSH-forward knocked at SSH connect).
                if (!sshForward && profileId != null) {
                    runVncKnockIfConfigured(profileId, actualHost)
                }

                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
                session.start() // blocks until established; fires onConnected/onError
            } catch (e: Exception) {
                // SpiceSession.start() invokes onError (which sets the tab's
                // error state) before rethrowing, so just clean up here.
                Log.e(TAG, "SPICE connect failed", e)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
            }
        }
    }

    fun sendSpiceKey(scancode: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Spice)?.session?.sendKey(scancode, pressed)
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
            is DesktopTab.Spice -> tab._pointerPos.value = x to y
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
            is DesktopTab.Spice -> tab._pointerPos.value = x to y
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
     * local `remember` state to its initial value and the
     * LaunchedEffect immediately wrote that back, so the lock never
     * took effect.
     *
     * Default is UNSPECIFIED (auto / follow system) so the Desktop
     * tab behaves like its neighbours when no desktop session is
     * active and the user hasn't explicitly chosen landscape —
     * the previous LANDSCAPE default rotated the activity as soon
     * as the user landed on the empty Desktop tab, breaking the
     * left/right swipe-to-change-tab gesture for anyone using
     * Haven without a desktop connection. Users who want landscape
     * cycle to it via the orientation toolbar button.
     *
     * Values are the raw `ActivityInfo.SCREEN_ORIENTATION_*`
     * constants since the enum is private to feature modules. Cycle
     * order from the toolbar button: Auto -> Landscape -> Portrait
     * -> Auto.
     */
    private val _desktopOrientation = MutableStateFlow(
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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

    /** Knock against the VNC/RDP host using the profile's saved sequence,
     *  if any. Failures are logged but not thrown; the real socket open
     *  surfaces the actual symptom. */
    private suspend fun runVncKnockIfConfigured(profileId: String, host: String) {
        val profile = connectionRepository.getById(profileId) ?: return
        val seq = KnockSequence.parse(
            profile.portKnockSequence,
            delayMs = profile.portKnockDelayMs,
        ).getOrNull() ?: return
        val result = portKnocker.knock(host, seq)
        Log.d(
            TAG,
            if (result.ok) "[knock] ${seq.format()} -> ok in ${result.totalDurationMs}ms"
            else "[knock] ${seq.format()} -> failed after ${result.totalDurationMs}ms: ${result.error?.message}"
        )
    }

    private fun findSshClient(sessionId: String): SshConnection? {
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
                    // SSH side via the lease (removes the forward + releases
                    // the dependent, tearing the SSH down if it was opened
                    // solely for this tunnel). No-op if already closed by a
                    // parent-gone cascade. releaseSshTunnelDependent also
                    // covers the WG/Tailscale dependent (and is idempotent).
                    tab.tunnelLease?.close()
                    releaseSshTunnelDependent(tab.profileId)
                    desktopSessionRegistry.clear(tab.profileId)
                    desktopSessionRegistry.clearFrameHandle(tab.profileId)
                    desktopSessionRegistry.clearInputHandle(tab.profileId)
                    desktopSessionRegistry.clearCloseHandle(tab.profileId)
                }
                is DesktopTab.Rdp -> {
                    if (tab.profileId != null) {
                        val verboseLog = tab.session.drainVerboseLog()
                        connectionLogRepository.logEvent(tab.profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                    }
                    tab.session.close()
                    tab.tunnelLease?.close()
                    releaseSshTunnelDependent(tab.profileId)
                    desktopSessionRegistry.clear(tab.profileId)
                    desktopSessionRegistry.clearFrameHandle(tab.profileId)
                    desktopSessionRegistry.clearInputHandle(tab.profileId)
                    desktopSessionRegistry.clearCloseHandle(tab.profileId)
                }
                is DesktopTab.Spice -> {
                    if (tab.profileId != null) {
                        val verboseLog = tab.session.drainVerboseLog()
                        connectionLogRepository.logEvent(tab.profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                    }
                    tab.session.close()
                    tab.tunnelLease?.close()
                    releaseSshTunnelDependent(tab.profileId)
                    desktopSessionRegistry.clear(tab.profileId)
                    desktopSessionRegistry.clearFrameHandle(tab.profileId)
                    desktopSessionRegistry.clearInputHandle(tab.profileId)
                    desktopSessionRegistry.clearCloseHandle(tab.profileId)
                }
                is DesktopTab.Wayland -> {} // compositor lifecycle managed externally
            }
        }
    }

    /**
     * Decrement refcounts on any auto-opened tunnels this profile holds.
     *
     * SSH-side: the v5.24.85 wiring in [ConnectionsViewModel.disconnect]
     * called this for connections-tab disconnects, but the bottom-of-
     * Desktop-tab "Disconnect" button routes through [closeTab] /
     * [disconnectTab] instead — without this call the auto-opened SSH
     * idled on with a green dot in the connections list (#121,
     * KoriKraut on v5.24.89).
     *
     * WireGuard / Tailscale side: a profile that dialled through
     * [TunnelResolver.dial] holds a slot in [TunnelManager]'s dependent
     * set. Release here so the underlying tunnel tears down when the
     * last dependent disconnects (#149).
     *
     * No-op when [profileId] is null (older tabs / Wayland) or when the
     * profile was never registered as a tunnel dependent on either side.
     */
    private fun releaseSshTunnelDependent(profileId: String?) {
        if (profileId == null) return
        sshSessionManager.releaseTunnelDependent(profileId)
        viewModelScope.launch { tunnelResolver.release(profileId) }
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

/**
 * The remote target for a VNC-over-SSH local forward, as dialled FROM the
 * SSH server. The configured VNC host is honoured verbatim (#538: a distinct
 * machine behind the jump host) except when it names the jump host itself —
 * then loopback, because the server there typically binds 127.0.0.1 only and
 * sshd dialling its own external address refuses (#104). Blank falls back to
 * loopback too.
 */
internal fun vncForwardTarget(vncHost: String?, jumpHost: String?): String = when {
    vncHost.isNullOrBlank() -> "127.0.0.1"
    !jumpHost.isNullOrBlank() && vncHost.trim() == jumpHost.trim() -> "127.0.0.1"
    else -> vncHost.trim()
}
