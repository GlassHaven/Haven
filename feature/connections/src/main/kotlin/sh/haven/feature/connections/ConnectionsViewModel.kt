package sh.haven.feature.connections

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyAuthFailure
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.KeyboardInteractiveChallenge
import sh.haven.core.ssh.KeyboardInteractivePrompter
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshVerboseLogger
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.et.EtSessionManager
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.fido.FidoTouchPrompt
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.rclone.RcloneSessionManager
import sh.haven.core.reticulum.DiscoveredDestination
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.core.smb.SmbSessionManager
import android.util.Log
import com.jcraft.jsch.Proxy
import java.io.File
import javax.inject.Inject

private const val TAG = "ConnectionsVM"

/** Unified connection status that maps both SSH and Reticulum states. */
enum class ProfileStatus { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }

data class GroupLaunchState(
    val groupId: String,
    val total: Int,
    val completed: Int,
    val succeeded: Int,
    val skipped: Int,
    val connectingIds: Set<String>,
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ConnectionRepository,
    private val connectionGroupDao: sh.haven.core.data.db.ConnectionGroupDao,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val reticulumSessionManager: ReticulumSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val reticulumTransport: ReticulumTransport,
    private val smbSessionManager: SmbSessionManager,
    private val rcloneSessionManager: RcloneSessionManager,
    private val rcloneClient: RcloneClient,
    private val fidoAuthenticator: FidoAuthenticator,
    private val localSessionManager: LocalSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val sshKeyRepository: SshKeyRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
    private val tunnelManager: sh.haven.core.tunnel.TunnelManager,
) : ViewModel() {

    /** Verbose SSH log captured during connect, keyed by sessionId. Consumed by finishConnect. */
    private val pendingVerboseLogs = mutableMapOf<String, String>()

    init {
        // Stop foreground service when last session closes, regardless of how it was removed
        // (close tab, network drop, etc.). Debounce prevents rapid start/stop cycling.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            combine(
                sshSessionManager.sessions,
                reticulumSessionManager.sessions,
                moshSessionManager.sessions,
                etSessionManager.sessions,
                localSessionManager.sessions,
            ) { flows -> flows }
                .debounce(500L)
                .collect { updateServiceNotification() }
        }

    }

    val connections: StateFlow<List<ConnectionProfile>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<sh.haven.core.data.db.entities.ConnectionGroup>> =
        connectionGroupDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sshKeys: StateFlow<List<SshKey>> = sshKeyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val globalSessionManagerLabel: StateFlow<String> = preferencesRepository.sessionManager
        .map { it.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "None")

    val batteryPromptDismissed: StateFlow<Boolean> = preferencesRepository.batteryPromptDismissed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true) // default true to avoid flash

    fun dismissBatteryPrompt() {
        viewModelScope.launch {
            preferencesRepository.setBatteryPromptDismissed()
        }
    }

    val sessions: StateFlow<Map<String, SshSessionManager.SessionState>> = sshSessionManager.sessions

    /** Derive profile-level statuses for the connections list UI (merges SSH + Reticulum + Mosh + ET). */
    val profileStatuses: StateFlow<Map<String, ProfileStatus>> =
        combine(
            combine(
                sshSessionManager.sessions,
                reticulumSessionManager.sessions,
                moshSessionManager.sessions,
                etSessionManager.sessions,
            ) { ssh, rns, mosh, et -> arrayOf(ssh, rns, mosh, et) },
            combine(
                smbSessionManager.sessions,
                localSessionManager.sessions,
                rcloneSessionManager.sessions,
            ) { smb, local, rclone -> arrayOf(smb, local, rclone) },
        ) { base, extra ->
            @Suppress("UNCHECKED_CAST")
            val sshMap = base[0] as Map<String, SshSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val rnsMap = base[1] as Map<String, ReticulumSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val moshMap = base[2] as Map<String, MoshSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val etMap = base[3] as Map<String, EtSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val smbMap = extra[0] as Map<String, SmbSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val localMap = extra[1] as Map<String, LocalSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val rcloneMap = extra[2] as Map<String, RcloneSessionManager.SessionState>
            val result = mutableMapOf<String, ProfileStatus>()

            // Track which profiles have transport-specific sessions (Mosh/ET/RNS/Local).
            // Their status takes precedence over the SSH infrastructure session
            // (which stays CONNECTED for SFTP even after the transport disconnects).
            val transportProfiles = mutableSetOf<String>()

            // SSH statuses (base — may be overridden by transport-specific status)
            sshMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                val statuses = states.map { it.status }
                result[profileId] = when {
                    SshSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    SshSessionManager.SessionState.Status.RECONNECTING in statuses -> ProfileStatus.RECONNECTING
                    SshSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    SshSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
            }

            // Reticulum statuses
            rnsMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                transportProfiles.add(profileId)
                val statuses = states.map { it.status }
                result[profileId] = when {
                    ReticulumSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    ReticulumSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    ReticulumSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
            }

            // Mosh statuses (override SSH for this profile)
            moshMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                transportProfiles.add(profileId)
                val statuses = states.map { it.status }
                result[profileId] = when {
                    MoshSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    MoshSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    MoshSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
            }

            // ET statuses (override SSH for this profile)
            etMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                transportProfiles.add(profileId)
                val statuses = states.map { it.status }
                result[profileId] = when {
                    EtSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    EtSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    EtSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
            }

            // SMB statuses (merge — SMB coexists with SSH, doesn't replace it)
            smbMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                val statuses = states.map { it.status }
                val smbStatus = when {
                    SmbSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    SmbSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    SmbSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
                val existing = result[profileId]
                if (existing == null || smbStatus.ordinal < existing.ordinal) {
                    result[profileId] = smbStatus
                }
            }

            // Local statuses (merge)
            localMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                val statuses = states.map { it.status }
                val localStatus = when {
                    LocalSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    LocalSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    LocalSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
                val existing = result[profileId]
                if (existing == null || localStatus.ordinal < existing.ordinal) {
                    result[profileId] = localStatus
                }
            }

            // Rclone statuses (merge)
            rcloneMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                val statuses = states.map { it.status }
                val rcloneStatus = when {
                    RcloneSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    RcloneSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    RcloneSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
                val existing = result[profileId]
                if (existing == null || rcloneStatus.ordinal < existing.ordinal) {
                    result[profileId] = rcloneStatus
                }
            }

            result.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The profileId currently being connected (for spinner in UI). */
    private val _connectingProfileId = MutableStateFlow<String?>(null)
    val connectingProfileId: StateFlow<String?> = _connectingProfileId.asStateFlow()

    /** Tracks progress of a group launch operation. */
    private val _groupLaunchState = MutableStateFlow<GroupLaunchState?>(null)
    val groupLaunchState: StateFlow<GroupLaunchState?> = _groupLaunchState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Non-fatal warning surfaced alongside a successful or in-progress connect.
     * Distinct from [error] so the UI can style it differently and so callers
     * don't accidentally abort a working connection on a cosmetic warning.
     * Example: agent forwarding enabled but all stored keys are encrypted.
     */
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    /** Profiles that have already shown the agent-forwarding warning this session. */
    private val agentWarningShownFor = mutableSetOf<String>()

    /**
     * Direct re-export of [FidoAuthenticator.touchPrompt] so the connections
     * screen can show a "plug in / tap your security key" prompt during a
     * FIDO2 SSH assertion. Re-exporting (rather than collecting and copying)
     * keeps the StateFlow's hot semantics — the prompt appears and disappears
     * exactly when the authenticator's assertion goroutine sets/clears it.
     */
    val fidoTouchPrompt: StateFlow<FidoTouchPrompt?> = fidoAuthenticator.touchPrompt

    private val _showMoshSetupGuide = MutableStateFlow(false)
    val showMoshSetupGuide: StateFlow<Boolean> = _showMoshSetupGuide.asStateFlow()

    private val _showMoshClientMissing = MutableStateFlow(false)
    val showMoshClientMissing: StateFlow<Boolean> = _showMoshClientMissing.asStateFlow()

    fun dismissMoshSetupGuide() { _showMoshSetupGuide.value = false }
    fun dismissMoshClientMissing() { _showMoshClientMissing.value = false }

    /** When non-null, key auth failed and the UI should show a password dialog as fallback. */
    private val _passwordFallback = MutableStateFlow<ConnectionProfile?>(null)
    val passwordFallback: StateFlow<ConnectionProfile?> = _passwordFallback.asStateFlow()

    sealed class HostKeyPrompt {
        data class NewHost(
            val entry: KnownHostEntry,
            val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>,
        ) : HostKeyPrompt()
        data class KeyChanged(
            val oldFingerprint: String,
            val entry: KnownHostEntry,
            val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>,
        ) : HostKeyPrompt()
    }

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    fun onHostKeyAccepted() {
        (_hostKeyPrompt.value as? HostKeyPrompt.NewHost)?.deferred?.complete(true)
        (_hostKeyPrompt.value as? HostKeyPrompt.KeyChanged)?.deferred?.complete(true)
        _hostKeyPrompt.value = null
    }

    fun onHostKeyRejected() {
        (_hostKeyPrompt.value as? HostKeyPrompt.NewHost)?.deferred?.complete(false)
        (_hostKeyPrompt.value as? HostKeyPrompt.KeyChanged)?.deferred?.complete(false)
        _hostKeyPrompt.value = null
    }

    /**
     * In-flight keyboard-interactive auth round. When non-null the UI should
     * render [KeyboardInteractiveDialog] to collect responses (typically a
     * 2FA / TOTP code after the password) and call either [submit] or
     * [cancel]. Cancelling returns `null` to JSch, which aborts the KI
     * auth attempt. See #100.
     */
    class PendingKeyboardInteractiveAuth(
        val challenge: KeyboardInteractiveChallenge,
        private val deferred: CompletableDeferred<List<String>?>,
    ) {
        fun submit(responses: List<String>) {
            deferred.complete(responses)
        }
        fun cancel() {
            deferred.complete(null)
        }
    }

    private val _keyboardInteractiveAuth = MutableStateFlow<PendingKeyboardInteractiveAuth?>(null)
    val keyboardInteractiveAuth: StateFlow<PendingKeyboardInteractiveAuth?> =
        _keyboardInteractiveAuth.asStateFlow()

    /**
     * Shared prompter for every SSH connect path. Suspends in the JSch IO
     * thread (via [KeyboardInteractiveUserInfo]'s `runBlocking`) until the
     * user submits or cancels the dialog.
     */
    private val keyboardInteractivePrompter = KeyboardInteractivePrompter { challenge ->
        val deferred = CompletableDeferred<List<String>?>()
        android.util.Log.d("HavenKI", "VM prompter: set pending challenge prompts=${challenge.prompts.size} first='${challenge.prompts.firstOrNull()?.text}'")
        _keyboardInteractiveAuth.value = PendingKeyboardInteractiveAuth(challenge, deferred)
        try {
            deferred.await().also {
                android.util.Log.d("HavenKI", "VM prompter: deferred resumed with ${if (it == null) "null" else "${it.size} responses"}")
            }
        } finally {
            _keyboardInteractiveAuth.value = null
        }
    }

    fun submitKeyboardInteractiveResponses(responses: List<String>) {
        val pending = _keyboardInteractiveAuth.value
        android.util.Log.d("HavenKI", "VM submit called: responses=${responses.size} lens=${responses.map{it.length}} pending=${pending != null}")
        pending?.submit(responses)
    }

    fun cancelKeyboardInteractive() {
        android.util.Log.d("HavenKI", "VM cancel called")
        _keyboardInteractiveAuth.value?.cancel()
    }

    /**
     * Run TOFU host-key verification against [entry], prompting the user for
     * accept/reject on first contact or key change. Throws if rejected.
     *
     * Shared by every interactive connect path so that they behave the same
     * way on fresh contact, key change, and (post-fix) auth-failure-after-KEX.
     */
    private suspend fun runTofuVerification(
        entry: KnownHostEntry,
        clientToDisconnectOnReject: SshClient? = null,
        rejectedOnNewHostMessage: String = "Host key rejected by user",
        rejectedOnChangeMessage: String = "Host key change rejected by user",
    ) {
        when (val result = hostKeyVerifier.verify(entry)) {
            is HostKeyResult.Trusted -> return
            is HostKeyResult.NewHost -> {
                val deferred = CompletableDeferred<Boolean>()
                _hostKeyPrompt.value = HostKeyPrompt.NewHost(result.entry, deferred)
                if (!deferred.await()) {
                    clientToDisconnectOnReject?.disconnect()
                    throw Exception(rejectedOnNewHostMessage)
                }
                hostKeyVerifier.accept(result.entry)
            }
            is HostKeyResult.KeyChanged -> {
                val deferred = CompletableDeferred<Boolean>()
                _hostKeyPrompt.value = HostKeyPrompt.KeyChanged(
                    oldFingerprint = result.old.fingerprint,
                    entry = result.new,
                    deferred = deferred,
                )
                if (!deferred.await()) {
                    clientToDisconnectOnReject?.disconnect()
                    throw Exception(rejectedOnChangeMessage)
                }
                hostKeyVerifier.accept(result.new)
            }
        }
    }

    /** Emitted once after a successful connect to trigger navigation to terminal (profileId). */
    private val _navigateToTerminal = MutableStateFlow<String?>(null)
    val navigateToTerminal: StateFlow<String?> = _navigateToTerminal.asStateFlow()

    /** Emitted to navigate back to Connections screen after a post-connect failure. */
    private val _navigateToConnections = MutableStateFlow(false)
    val navigateToConnections: StateFlow<Boolean> = _navigateToConnections.asStateFlow()

    /** Emitted to navigate to VNC screen with connection params. */
    data class VncNavigation(val host: String, val port: Int, val password: String?, val username: String? = null)
    private val _navigateToVnc = MutableStateFlow<VncNavigation?>(null)
    val navigateToVnc: StateFlow<VncNavigation?> = _navigateToVnc.asStateFlow()

    /** Emitted to navigate to the native Wayland desktop view. */
    private val _navigateToWayland = MutableStateFlow(false)
    val navigateToWayland: StateFlow<Boolean> = _navigateToWayland.asStateFlow()

    /** Emitted to navigate to RDP screen with connection params. */
    data class RdpNavigation(val host: String, val port: Int, val username: String, val password: String, val domain: String, val sshForward: Boolean = false, val sshProfileId: String? = null, val sshSessionId: String? = null, val profileId: String? = null)
    private val _navigateToRdp = MutableStateFlow<RdpNavigation?>(null)
    val navigateToRdp: StateFlow<RdpNavigation?> = _navigateToRdp.asStateFlow()

    /** Emitted to navigate to Files tab for an SMB connection. */
    private val _navigateToSmb = MutableStateFlow<String?>(null)
    val navigateToSmb: StateFlow<String?> = _navigateToSmb.asStateFlow()

    private val _navigateToRclone = MutableStateFlow<String?>(null)
    val navigateToRclone: StateFlow<String?> = _navigateToRclone.asStateFlow()

    /** Emitted to open a new session (new tab) on an already-connected profile. */
    private val _newSessionProfileId = MutableStateFlow<String?>(null)
    val newSessionProfileId: StateFlow<String?> = _newSessionProfileId.asStateFlow()

    /** When non-null, the UI should show a session picker dialog. */
    data class SessionSelection(
        val sessionId: String,
        val profileId: String,
        val managerLabel: String,
        val sessionNames: List<String>,
        /** Session names that were open last time (for "Restore" action). */
        val previousSessionNames: List<String> = emptyList(),
        val manager: SessionManager = SessionManager.NONE,
        /** "SSH" or "MOSH" — determines which finish path onSessionSelected uses. */
        val transportType: String = "SSH",
    )

    private val _sessionSelection = MutableStateFlow<SessionSelection?>(null)
    val sessionSelection: StateFlow<SessionSelection?> = _sessionSelection.asStateFlow()

    /** SSH client + host kept alive during mosh session picker (for mosh-server exec). */
    private var moshPendingClient: SshClient? = null
    private var moshPendingHost: String? = null
    private var moshPendingVerboseLogger: SshVerboseLogger? = null

    /** SSH client + host kept alive during ET session picker. */
    private var etPendingClient: SshClient? = null
    private var etPendingProfile: ConnectionProfile? = null
    private var etPendingVerboseLogger: SshVerboseLogger? = null

    fun onNavigated() {
        _navigateToTerminal.value = null
        _navigateToVnc.value = null
        _navigateToRdp.value = null
        _navigateToSmb.value = null
        _navigateToRclone.value = null
        _navigateToConnections.value = false
        _newSessionProfileId.value = null
    }

    /** Open a new terminal session on an already-connected profile. */
    fun openNewSession(profileId: String) {
        _newSessionProfileId.value = profileId
    }

    private val _discoveredDestinations = MutableStateFlow<List<DiscoveredDestination>>(emptyList())
    val discoveredDestinations: StateFlow<List<DiscoveredDestination>> = _discoveredDestinations.asStateFlow()

    private val _reticulumScanning = MutableStateFlow(false)
    val reticulumScanning: StateFlow<Boolean> = _reticulumScanning.asStateFlow()

    /**
     * Scan for rnsh nodes by initialising Reticulum with the given gateway
     * and waiting for announces. Called from the edit dialog's Scan button.
     */
    fun scanReticulumDestinations(host: String, port: Int, networkName: String?, passphrase: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            _reticulumScanning.value = true
            try {
                val configDir = File(appContext.filesDir, "reticulum")
                    .apply { mkdirs() }.absolutePath
                reticulumTransport.init(configDir, host, port, networkName, passphrase)
                Log.d(TAG, "scanReticulum: transport initialised, waiting for gateway stabilisation...")

                // Wait for the gateway's IFAC handshake and tunnel synthesis
                // to complete before starting the announce collection window.
                kotlinx.coroutines.delay(5000)

                // Also request paths for any saved rnsh destinations — this
                // triggers the gateway to forward cached announces/paths.
                requestPathsForSavedConnections()

                // Collect announces for 10 seconds (init + TCP connect + announce
                // propagation can take several seconds)
                val job = launch {
                    reticulumTransport.discoveredDestinations.collect { list ->
                        _discoveredDestinations.value = list
                        if (list.isNotEmpty()) {
                            Log.d(TAG, "scanReticulum: ${list.size} destination(s) discovered so far")
                        }
                    }
                }
                kotlinx.coroutines.delay(10_000)
                job.cancel()

                // Final snapshot
                _discoveredDestinations.value = reticulumTransport.discoveredDestinations.value
                Log.d(TAG, "Scan complete: ${_discoveredDestinations.value.size} destinations found")
            } catch (e: Exception) {
                Log.e(TAG, "scanReticulumDestinations failed", e)
            } finally {
                _reticulumScanning.value = false
            }
        }
    }

    private val networkDiscovery = NetworkDiscovery(appContext)
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = networkDiscovery.hosts
    val discoveredSmbHosts: StateFlow<List<DiscoveredHost>> = networkDiscovery.smbHosts
    val localVmStatus: StateFlow<LocalVmStatus> = networkDiscovery.localVm

    val showLinuxVmCard: StateFlow<Boolean> = preferencesRepository.showLinuxVmCard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showDesktopsCard: StateFlow<Boolean> = preferencesRepository.showDesktopsCard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private var periodicRefreshJob: Job? = null

    fun startPeriodicRefresh() {
        stopPeriodicRefresh()
        periodicRefreshJob = viewModelScope.launch {
            while (true) {
                refreshDiscoveredDestinations()
                delay(30_000)
            }
        }
    }

    fun stopPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    fun startNetworkDiscovery() {
        networkDiscovery.start()
        networkDiscovery.startVmPolling(viewModelScope)
        viewModelScope.launch { networkDiscovery.discoverTailscale() }
    }

    fun refreshLocalVm() {
        viewModelScope.launch {
            networkDiscovery.scanLocalVm()
        }
    }

    private val _subnetScanning = MutableStateFlow(false)
    val subnetScanning: StateFlow<Boolean> = _subnetScanning.asStateFlow()

    fun scanSubnet() {
        viewModelScope.launch {
            _subnetScanning.value = true
            networkDiscovery.scanSubnet()
            networkDiscovery.discoverTailscale()
            _subnetScanning.value = false
        }
    }

    private val _smbSubnetScanning = MutableStateFlow(false)
    val smbSubnetScanning: StateFlow<Boolean> = _smbSubnetScanning.asStateFlow()

    fun scanSubnetSmb() {
        viewModelScope.launch {
            _smbSubnetScanning.value = true
            networkDiscovery.scanSubnetSmb()
            _smbSubnetScanning.value = false
        }
    }

    fun stopNetworkDiscovery() {
        networkDiscovery.stop() // also stops VM polling
    }

    fun refreshDiscoveredDestinations() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!reticulumTransport.isInitialised) {
                    Log.d(TAG, "RNS not initialised, skipping destination refresh")
                    return@launch
                }

                // Proactively request paths for saved Reticulum connections
                requestPathsForSavedConnections()

                // Read discovered destinations from the transport's StateFlow
                val list = reticulumTransport.discoveredDestinations.value
                Log.d(TAG, "Discovered ${list.size} destinations: ${list.map { it.hash.take(8) }}")
                _discoveredDestinations.value = list
            } catch (e: Exception) {
                Log.e(TAG, "refreshDiscoveredDestinations failed", e)
            }
        }
    }

    private suspend fun requestPathsForSavedConnections() {
        try {
            val saved = connections.value.filter { it.isReticulum && !it.destinationHash.isNullOrBlank() }
            for (profile in saved) {
                val hash = profile.destinationHash ?: continue
                val alreadyKnown = reticulumTransport.requestPath(hash)
                Log.d(TAG, "requestPath(${hash.take(8)}...): known=$alreadyKnown")
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestPathsForSavedConnections failed", e)
        }
    }

    fun saveConnection(profile: ConnectionProfile) {
        viewModelScope.launch {
            repository.save(profile)
        }
    }

    /** Persist new sort order for a reordered list of top-level profile IDs. */
    fun createGroup(label: String) {
        viewModelScope.launch {
            connectionGroupDao.upsert(sh.haven.core.data.db.entities.ConnectionGroup(label = label))
        }
    }

    fun renameGroup(id: String, label: String) {
        viewModelScope.launch {
            connectionGroupDao.getById(id)?.let {
                connectionGroupDao.upsert(it.copy(label = label))
            }
        }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch {
            // Move connections in this group to ungrouped
            connections.value.filter { it.groupId == id }.forEach { profile ->
                repository.save(profile.copy(groupId = null))
            }
            connectionGroupDao.deleteById(id)
        }
    }

    fun toggleGroupCollapsed(id: String) {
        viewModelScope.launch {
            connectionGroupDao.getById(id)?.let {
                connectionGroupDao.updateCollapsed(id, !it.collapsed)
            }
        }
    }

    fun reorderGroups(orderedIds: List<String>) {
        viewModelScope.launch {
            orderedIds.forEachIndexed { index, id ->
                connectionGroupDao.updateSortOrder(id, index)
            }
        }
    }

    fun updateGroupSortOrder(id: String, sortOrder: Int) {
        viewModelScope.launch {
            connectionGroupDao.updateSortOrder(id, sortOrder)
        }
    }

    fun updateSortOrder(profileId: String, sortOrder: Int) {
        viewModelScope.launch {
            repository.updateSortOrder(profileId, sortOrder)
        }
    }

    fun updateGroupColor(id: String, colorTag: Int) {
        viewModelScope.launch {
            connectionGroupDao.getById(id)?.let {
                connectionGroupDao.upsert(it.copy(colorTag = colorTag))
            }
        }
    }

    /**
     * Returns true if the profile can connect without interactive dialogs.
     * VNC/RDP/SMB are excluded (they navigate to non-terminal screens).
     * SSH/Mosh/ET require a saved password or SSH keys.
     */
    private fun canAutoConnect(profile: ConnectionProfile, keys: List<SshKey>): Boolean = when {
        profile.isLocal -> true
        profile.isReticulum -> true
        profile.isRclone -> true
        profile.isVnc -> false
        profile.isRdp -> false
        profile.isSmb -> false
        else -> !profile.sshPassword.isNullOrBlank() || keys.isNotEmpty()
    }

    /**
     * Launch all auto-connectable profiles in a group concurrently.
     * Skips already-connected profiles and profiles requiring interactive auth.
     */
    fun launchGroup(groupId: String) {
        if (_groupLaunchState.value != null) return

        viewModelScope.launch {
            val allProfiles = connections.value.filter { it.groupId == groupId }
            val statuses = profileStatuses.value
            val keys = sshKeys.value

            val disconnected = allProfiles.filter { statuses[it.id] != ProfileStatus.CONNECTED }
            val launchable = disconnected.filter { canAutoConnect(it, keys) }
            val skipped = disconnected.size - launchable.size

            if (launchable.isEmpty()) return@launch

            _groupLaunchState.value = GroupLaunchState(
                groupId = groupId,
                total = launchable.size,
                completed = 0,
                succeeded = 0,
                skipped = skipped,
                connectingIds = launchable.map { it.id }.toSet(),
            )

            val deferreds = launchable.map { profile ->
                async {
                    val success = try {
                        connectSilent(profile)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Group launch failed for ${profile.label}: ${e.message}")
                        false
                    }
                    _groupLaunchState.update { state ->
                        state?.copy(
                            connectingIds = state.connectingIds - profile.id,
                            completed = state.completed + 1,
                            succeeded = state.succeeded + if (success) 1 else 0,
                        )
                    }
                    success
                }
            }

            val outcomes = deferreds.map { it.await() }
            val successCount = outcomes.count { it }

            if (successCount > 0) {
                launchable.firstOrNull()?.let { _navigateToTerminal.value = it.id }
            }

            delay(1500)
            _groupLaunchState.value = null
        }
    }

    fun reorderConnections(orderedIds: List<String>) {
        viewModelScope.launch {
            orderedIds.forEachIndexed { index, id ->
                repository.updateSortOrder(id, index)
            }
        }
    }

    fun duplicateConnection(id: String) {
        viewModelScope.launch {
            val profile = connections.value.find { it.id == id } ?: return@launch
            val copy = profile.copy(
                id = java.util.UUID.randomUUID().toString(),
                label = "${profile.label} (copy)",
                lastConnected = null,
            )
            repository.save(copy)
        }
    }

    fun deleteConnection(id: String) {
        viewModelScope.launch {
            sessionManagerRegistry.disconnectProfile(id)
            localSessionManager.desktopManager.stopAll()
            updateServiceNotification()
            repository.delete(id)
        }
    }

    /**
     * Try connecting with key auth (no password dialog). On failure, show password dialog.
     */
    fun connectWithKey(profile: ConnectionProfile) {
        if (profile.username.isBlank() && (profile.isSsh || profile.isReticulum)) {
            // Route through the prompt dialog so the user can supply a username first.
            _passwordFallback.value = profile
            return
        }
        connect(profile, password = "", keyOnly = true)
    }

    fun dismissPasswordFallback() {
        _passwordFallback.value = null
    }

    fun connect(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean = false,
        rememberPassword: Boolean? = null,
        usernameOverride: String? = null,
    ) {
        if (profile.isLocal) {
            connectLocal(profile)
            return
        }
        if (profile.isVnc) {
            connectVnc(profile)
            return
        }
        if (profile.isRdp) {
            connectRdp(profile, password)
            return
        }
        if (profile.isSmb) {
            connectSmb(profile, password)
            return
        }
        if (profile.isRclone) {
            connectRclone(profile)
            return
        }
        if (profile.isReticulum) {
            connectReticulum(profile)
            return
        }
        // SSH-family: if the saved profile has no username, the user must supply one
        // at connect time via the prompt dialog. Resolve here, then thread the runtime
        // value through to the connect routines as a per-call override — the persisted
        // profile must not be mutated, since the whole point of leaving it blank is to
        // reuse one connection entry for many users.
        val runtimeUsername = usernameOverride?.takeIf { it.isNotBlank() }
        if (profile.username.isBlank() && runtimeUsername == null) {
            _passwordFallback.value = profile
            return
        }
        if (profile.isEternalTerminal) {
            connectEternalTerminal(profile, password, keyOnly, usernameOverride = runtimeUsername)
            return
        }
        if (profile.isMosh) {
            connectMosh(profile, password, keyOnly, usernameOverride = runtimeUsername)
            return
        }
        connectSsh(profile, password, keyOnly, rememberPassword, usernameOverride = runtimeUsername)
    }

    private fun connectVnc(profile: ConnectionProfile) {
        val host = profile.host
        val port = profile.vncPort ?: profile.port
        val password = profile.vncPassword
        val username = profile.vncUsername
        viewModelScope.launch {
            repository.markConnected(profile.id)
        }
        _navigateToVnc.value = VncNavigation(host, port, password, username)
    }

    private fun connectRdp(profile: ConnectionProfile, password: String) {
        val host = profile.host
        val port = profile.rdpPort
        val username = profile.rdpUsername ?: profile.username
        val rdpPassword = password.ifBlank { profile.rdpPassword ?: "" }
        val domain = profile.rdpDomain ?: ""
        viewModelScope.launch {
            repository.markConnected(profile.id)
            val sshProfileId = profile.rdpSshProfileId
            if (profile.rdpSshForward && sshProfileId != null) {
                // Auto-connect SSH tunnel host (reuses existing session if available)
                try {
                    _connectingProfileId.value = profile.id
                    // Pass empty password — SSH profile uses its own auth (key or password)
                    val (sshSessionId, _) = connectJumpHost(sshProfileId, "")
                    _navigateToRdp.value = RdpNavigation(
                        host, port, username, rdpPassword, domain,
                        sshForward = true,
                        sshProfileId = sshProfileId,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect SSH tunnel host for RDP", e)
                    _error.value = "SSH tunnel: ${e.message}"
                } finally {
                    _connectingProfileId.value = null
                }
            } else {
                _navigateToRdp.value = RdpNavigation(host, port, username, rdpPassword, domain, profile.rdpSshForward, profile.rdpSshProfileId, profileId = profile.id)
            }
        }
    }

    private fun connectSmb(profile: ConnectionProfile, password: String) {
        val host = profile.host
        val port = profile.smbPort
        val shareName = profile.smbShare ?: return
        val smbUsername = profile.username
        val smbPassword = password.ifBlank { profile.smbPassword ?: "" }
        val domain = profile.smbDomain ?: ""
        viewModelScope.launch {
            repository.markConnected(profile.id)
            try {
                _connectingProfileId.value = profile.id
                val sshProfileId = profile.smbSshProfileId
                var sshClientCloseable: java.io.Closeable? = null
                var tunnelPort: Int? = null

                if (profile.smbSshForward && sshProfileId != null) {
                    val (sshSessionId, _) = connectJumpHost(sshProfileId, "")
                    val sshClient = sshSessionManager.sessions.value[sshSessionId]?.client
                        ?: throw IllegalStateException("SSH tunnel session not found")
                    // Set up local port forward: random port -> remoteHost:smbPort
                    tunnelPort = withContext(Dispatchers.IO) {
                        sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    }
                    sshClientCloseable = sshClient
                    Log.d(TAG, "SMB SSH tunnel: 127.0.0.1:$tunnelPort -> $host:$port")
                }

                val sessionId = smbSessionManager.registerSession(profile.id, profile.label)
                withContext(Dispatchers.IO) {
                    smbSessionManager.connectSession(
                        sessionId, host, port, shareName, smbUsername, smbPassword, domain,
                        sshClient = sshClientCloseable,
                        tunnelPort = tunnelPort,
                    )
                }
                _navigateToSmb.value = profile.id
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect SMB", e)
                _error.value = "SMB: ${e.message}"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    private fun connectRclone(profile: ConnectionProfile) {
        val remoteName = profile.rcloneRemoteName ?: return
        val provider = profile.rcloneProvider ?: ""
        viewModelScope.launch {
            repository.markConnected(profile.id)
            try {
                _connectingProfileId.value = profile.id
                val sessionId = rcloneSessionManager.registerSession(profile.id, profile.label)
                withContext(Dispatchers.IO) {
                    rcloneSessionManager.connectSession(sessionId, remoteName, provider)
                }
                // If connected immediately (no OAuth needed), navigate to Files
                if (rcloneSessionManager.isProfileConnected(profile.id)) {
                    _navigateToRclone.value = profile.id
                }
                // Otherwise OAuth is in progress — the session will become CONNECTED
                // when the user completes the browser flow. Watch for it.
                else {
                    launch {
                        rcloneSessionManager.sessions.collect { sessions ->
                            val connected = sessions.values.any {
                                it.profileId == profile.id &&
                                    it.status == RcloneSessionManager.SessionState.Status.CONNECTED
                            }
                            if (connected) {
                                _navigateToRclone.value = profile.id
                                return@collect
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect rclone remote", e)
                _error.value = "rclone: ${e.message}"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    val desktopSetupState: StateFlow<sh.haven.core.local.ProotManager.DesktopSetupState> =
        localSessionManager.prootManager.desktopState

    /** Desktop process states from DesktopManager. */
    val desktopStates: StateFlow<Map<sh.haven.core.local.ProotManager.DesktopEnvironment, sh.haven.core.local.DesktopManager.DesktopInstance>> =
        localSessionManager.desktopManager.desktops

    /** All installed desktop environments. */
    val installedDesktops: Set<sh.haven.core.local.ProotManager.DesktopEnvironment>
        get() = localSessionManager.prootManager.installedDesktops

    /** Install a desktop environment (packages only, does not auto-start). */
    fun setupDesktop(
        vncPassword: String,
        de: sh.haven.core.local.ProotManager.DesktopEnvironment = sh.haven.core.local.ProotManager.DesktopEnvironment.XFCE4,
        addons: Set<sh.haven.core.local.ProotManager.DesktopAddon> = emptySet(),
    ) {
        viewModelScope.launch {
            val prootManager = localSessionManager.prootManager
            prootManager.setupDesktop(vncPassword, de)

            // Install optional desktop add-ons (panel, file manager, etc.)
            if (addons.isNotEmpty() && prootManager.desktopState.value is sh.haven.core.local.ProotManager.DesktopSetupState.Complete) {
                prootManager.installAddons(addons)
            }

            Log.d(TAG, "Desktop setup result: ${prootManager.desktopState.value}")
            // Don't reset state here — let the dialog observe Complete and auto-dismiss
        }
    }

    /** Uninstall a desktop environment. */
    fun uninstallDesktop(de: sh.haven.core.local.ProotManager.DesktopEnvironment) {
        viewModelScope.launch {
            try {
                // Stop if running
                localSessionManager.desktopManager.stopDesktop(de)
                localSessionManager.prootManager.uninstallDesktop(de)
                localSessionManager.prootManager.resetDesktopState()
            } catch (e: Exception) {
                Log.e(TAG, "uninstallDesktop failed for ${de.label}", e)
                _error.value = "Uninstall failed: ${e.message}"
            }
        }
    }

    /** Prompt for desktop VNC password when no stored password is available. */
    data class DesktopVncPasswordPrompt(
        val de: sh.haven.core.local.ProotManager.DesktopEnvironment,
        val port: Int,
    )
    private val _desktopVncPasswordPrompt = MutableStateFlow<DesktopVncPasswordPrompt?>(null)
    val desktopVncPasswordPrompt: StateFlow<DesktopVncPasswordPrompt?> = _desktopVncPasswordPrompt.asStateFlow()

    fun onDesktopVncPasswordEntered(password: String) {
        val prompt = _desktopVncPasswordPrompt.value ?: return
        _desktopVncPasswordPrompt.value = null
        // Save for next time
        localSessionManager.prootManager.storedVncPassword = password
        _navigateToVnc.value = VncNavigation("localhost", prompt.port, password)
    }

    fun dismissDesktopVncPasswordPrompt() {
        _desktopVncPasswordPrompt.value = null
    }

    /** Start a desktop environment and navigate to viewer. */
    fun startDesktop(de: sh.haven.core.local.ProotManager.DesktopEnvironment) {
        viewModelScope.launch {
            val desktopManager = localSessionManager.desktopManager
            val shellCmd = preferencesRepository.waylandShellCommand.first()
            Log.d(TAG, "startDesktop: ${de.label} shell=$shellCmd")
            withContext(Dispatchers.IO) {
                desktopManager.startDesktop(de, shellCmd)
            }
            val state = desktopManager.desktops.value[de]
            Log.d(TAG, "startDesktop: state after start = ${state?.state}")
            if (de.isNative) {
                delay(2000)
                _navigateToWayland.value = true
            } else {
                val port = desktopManager.getVncPort(de) ?: 5901
                Log.d(TAG, "startDesktop: VNC port=$port")
                // Look up VNC password: stored from setup, then saved profiles
                val pwd = localSessionManager.prootManager.storedVncPassword
                    ?: connections.value
                        .find { it.isVnc && it.host == "localhost" }
                        ?.vncPassword
                Log.d(TAG, "startDesktop: waiting for VNC server on port $port...")
                // Wait up to 8s for VNC port to become available
                val ready = withContext(Dispatchers.IO) {
                    repeat(16) {
                        try {
                            java.net.Socket("127.0.0.1", port).close()
                            return@withContext true
                        } catch (_: Exception) {
                            delay(500)
                        }
                    }
                    false
                }
                if (ready) {
                    if (pwd == null) {
                        // No stored password — prompt the user (VNC may or may not need auth,
                        // but it's better to ask than to fail silently)
                        Log.d(TAG, "startDesktop: VNC needs auth but no password stored, prompting")
                        _desktopVncPasswordPrompt.value = DesktopVncPasswordPrompt(de, port)
                    } else {
                        Log.d(TAG, "startDesktop: VNC server ready, navigating to localhost:$port")
                        _navigateToVnc.value = VncNavigation("localhost", port, pwd)
                    }
                } else {
                    Log.e(TAG, "startDesktop: VNC server not listening on port $port after 8s")
                    _error.value = "Desktop failed to start — VNC server not responding"
                    desktopManager.stopDesktop(de)
                }
            }
        }
    }

    /** Stop a running desktop environment. */
    fun stopDesktop(de: sh.haven.core.local.ProotManager.DesktopEnvironment) {
        viewModelScope.launch(Dispatchers.IO) {
            localSessionManager.desktopManager.stopDesktop(de)
        }
    }

    fun resetDesktopSetupState() {
        localSessionManager.prootManager.resetDesktopState()
    }

    fun setWaylandShellCommand(command: String) {
        viewModelScope.launch {
            preferencesRepository.setWaylandShellCommand(command)
        }
    }

    /** True if any PRoot desktop environment is installed. */
    val isDesktopInstalled: Boolean
        get() = localSessionManager.prootManager.hasAnyDesktopInstalled

    /** True if PRoot rootfs is installed and ready for desktop use. */
    val isRootfsReady: Boolean
        get() = localSessionManager.prootManager.isReady

    private val _launchingDesktop = MutableStateFlow(false)
    val launchingDesktop: StateFlow<Boolean> = _launchingDesktop.asStateFlow()

    fun consumeNavigateToWayland() {
        _navigateToWayland.value = false
    }

    private fun connectLocal(profile: ConnectionProfile) {
        viewModelScope.launch {
            // Skip if already connected
            val existing = localSessionManager.getSessionsForProfile(profile.id)
            if (existing.any { it.status == LocalSessionManager.SessionState.Status.CONNECTED }) {
                _navigateToTerminal.value = profile.id
                return@launch
            }

            _connectingProfileId.value = profile.id
            _error.value = null

            // If proot binary is available but rootfs isn't installed, download it first
            val prootManager = localSessionManager.prootManager
            if (prootManager.prootBinary != null && !prootManager.isRootfsInstalled) {
                Log.d(TAG, "PRoot available but rootfs not installed — downloading...")
                prootManager.installRootfs()
                if (prootManager.state.value is sh.haven.core.local.ProotManager.SetupState.Error) {
                    val err = prootManager.state.value as sh.haven.core.local.ProotManager.SetupState.Error
                    Log.w(TAG, "Rootfs install failed: ${err.message}, falling back to plain shell")
                }
            }

            val sessionId = localSessionManager.registerSession(profile.id, profile.label, profile.useAndroidShell)
            try {
                localSessionManager.connectSession(sessionId)
                repository.markConnected(profile.id)
                connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.CONNECTED)
                startForegroundServiceIfNeeded()
                _navigateToTerminal.value = profile.id
            } catch (e: Exception) {
                Log.e(TAG, "connectLocal failed: ${e.message}", e)
                connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message)
                localSessionManager.updateStatus(sessionId, LocalSessionManager.SessionState.Status.ERROR)
                localSessionManager.removeSession(sessionId)
                _error.value = e.message ?: "Local terminal failed"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    private fun connectSsh(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean,
        rememberPassword: Boolean? = null,
        usernameOverride: String? = null,
    ) {
        val effectiveUsername = usernameOverride?.takeIf { it.isNotBlank() } ?: profile.username
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
            val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null
            val client = SshClient().apply {
                fidoAuthenticator = this@ConnectionsViewModel.fidoAuthenticator
                this.verboseLogger = verboseLogger
            }
            val sessionId = sshSessionManager.registerSession(profile.id, profile.label, client)

            // Track whether we auto-created the jump session (for cleanup on failure)
            var autoCreatedJumpSessionId: String? = null
            var isFidoAuth = false
            try {
                // If profile has a jump host, establish that connection first
                val jumpProfileId = profile.jumpProfileId
                val jumpSessionId = if (jumpProfileId != null) {
                    val (jid, reused) = connectJumpHost(jumpProfileId, password)
                    if (!reused) autoCreatedJumpSessionId = jid
                    jid
                } else null

                if (jumpSessionId != null) {
                    sshSessionManager.setJumpSessionId(sessionId, jumpSessionId)
                }

                val sshSessionMgr = withContext(Dispatchers.IO) {
                    val authMethod = resolveAuthMethod(profile, password)
                    isFidoAuth = authMethod is ConnectionConfig.AuthMethod.FidoKey
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = effectiveUsername,
                        authMethod = authMethod,
                        sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                        forwardAgent = profile.forwardAgent,
                        agentIdentities = agentIdentitiesFor(profile),
                    )

                    // Proxy precedence: jump host > WireGuard tunnel (#102) > SOCKS/HTTP.
                    // The three are mutually exclusive at connect time — if a
                    // profile somehow has both a jump host and a tunnel configured,
                    // the jump host wins (it's the more explicit hop) and the
                    // tunnel is ignored for now.
                    val tunnelId = profile.tunnelConfigId
                    val proxy = when {
                        jumpSessionId != null -> sshSessionManager.createProxyJump(jumpSessionId)
                            ?: throw Exception("Jump host session not usable for tunneling")
                        tunnelId != null -> {
                            val tunnel = tunnelManager.getTunnel(tunnelId)
                                ?: throw Exception("Tunnel config $tunnelId not found")
                            sh.haven.core.tunnel.TunnelProxy(tunnel)
                        }
                        else -> createNetworkProxy(profile)
                    }
                    Log.d(TAG, "Connecting to ${config.host}:${config.port} (proxy=${proxy != null})")
                    try {
                        val hostKeyEntry = client.connect(
                            config,
                            proxy = proxy,
                            keyboardInteractivePrompter = keyboardInteractivePrompter,
                        )
                        runTofuVerification(hostKeyEntry, clientToDisconnectOnReject = client)
                    } catch (e: HostKeyAuthFailure) {
                        // KEX succeeded but auth failed — still run the TOFU
                        // prompt on first contact (Haven#75 follow-up) then
                        // rethrow the underlying JSch auth error so the catch
                        // block below can trigger the password-fallback UX.
                        runTofuVerification(e.hostKey, clientToDisconnectOnReject = null)
                        throw e.cause ?: e
                    }

                    val sshSessionMgr = resolveSessionManager(profile)
                    val cmdOverride = preferencesRepository.sessionCommandOverride.first()
                    sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr, cmdOverride, profile.postLoginCommand, profile.postLoginBeforeSessionManager)
                    sshSessionMgr
                }

                // Drain verbose log now (before session picker might return from the coroutine)
                verboseLogger?.drain()?.let { pendingVerboseLogs[sessionId] = it }

                // If session manager supports listing, check for existing sessions
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
                        // Parse previously open sessions from the pipe-delimited lastSessionName
                        val previousNames = profile.lastSessionName
                            ?.split("|")
                            ?.filter { it.isNotBlank() && it in existingSessions }
                            ?: emptyList()
                        _sessionSelection.value = SessionSelection(
                            sessionId = sessionId,
                            profileId = profile.id,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                            previousSessionNames = previousNames,
                            manager = sshSessionMgr,
                        )
                        _connectingProfileId.value = null
                        return@launch // UI will call onSessionSelected() to continue
                    }
                }

                // No existing sessions or no session manager — proceed directly
                finishConnect(sessionId, profile.id, verboseLog = verboseLogger?.drain())

                // Save or clear remembered password after successful connect.
                // Skip if the username came from a runtime prompt — the profile is
                // explicitly multi-user and persisting one user's password would
                // bleed it into other sessions.
                val multiUserProfile = usernameOverride != null
                if (!multiUserProfile) {
                    if (rememberPassword == true && password.isNotBlank()) {
                        repository.save(profile.copy(sshPassword = password))
                    } else if (rememberPassword == false && profile.sshPassword != null) {
                        repository.save(profile.copy(sshPassword = null))
                    }
                }

                // Auto-deploy SSH key for VM connections after first password connect
                if (password.isNotBlank()) {
                    maybeAutoDeployKey(profile, password)
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectSsh failed for ${profile.label}: ${e.message}", e)
                connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message, verboseLog = verboseLogger?.drain())
                sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
                sshSessionManager.removeSession(sessionId)
                // Clean up auto-created jump session if the final host failed
                autoCreatedJumpSessionId?.let { jid ->
                    Log.d(TAG, "Cleaning up auto-created jump session $jid")
                    sshSessionManager.removeSession(jid)
                }
                val msg = e.message ?: ""
                val isNetworkError = e is java.net.ConnectException ||
                    e is java.net.UnknownHostException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.net.NoRouteToHostException ||
                    msg.contains("refused", ignoreCase = true) ||
                    msg.contains("timed out", ignoreCase = true) ||
                    msg.contains("unreachable", ignoreCase = true)
                val isAuthError = keyOnly && !isNetworkError && (
                    msg.contains("Auth fail", ignoreCase = true) ||
                    msg.contains("Auth cancel", ignoreCase = true) ||
                    msg.contains("authentication", ignoreCase = true) ||
                    msg.contains("publickey", ignoreCase = true)
                )
                if (isFidoAuth && (isAuthError || (keyOnly && !isNetworkError))) {
                    val fidoDetail = fidoAuthenticator.lastAssertionError
                    _error.value = if (fidoDetail != null) {
                        "Security key: $fidoDetail"
                    } else {
                        msg.ifBlank { "Security key authentication failed" }
                    }
                } else if (isAuthError) {
                    Log.d(TAG, "Auth failed, showing password fallback for ${profile.label}")
                    _passwordFallback.value = profile
                } else if (keyOnly && !isNetworkError && msg.isBlank()) {
                    _passwordFallback.value = profile
                } else {
                    _error.value = msg.ifBlank { "Connection failed" }
                }
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    private fun connectReticulum(profile: ConnectionProfile) {
        val destinationHash = profile.destinationHash ?: return
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val sessionId = reticulumSessionManager.registerSession(
                profileId = profile.id,
                label = profile.label,
                destinationHash = destinationHash,
            )

            try {
                val configDir = File(appContext.filesDir, "reticulum").apply { mkdirs() }.absolutePath

                withContext(Dispatchers.IO) {
                    reticulumSessionManager.connectSession(
                        sessionId = sessionId,
                        configDir = configDir,
                        host = profile.reticulumHost,
                        port = profile.reticulumPort,
                        ifacNetname = profile.reticulumNetworkName,
                        ifacNetkey = profile.reticulumPassphrase,
                    )
                }

                repository.markConnected(profile.id)
                connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.CONNECTED)
                startForegroundServiceIfNeeded()
                _navigateToTerminal.value = profile.id
            } catch (e: Exception) {
                reticulumSessionManager.updateStatus(
                    sessionId,
                    ReticulumSessionManager.SessionState.Status.ERROR,
                )
                reticulumSessionManager.removeSession(sessionId)
                _error.value = e.message ?: "Reticulum connection failed"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    private fun connectEternalTerminal(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean,
        usernameOverride: String? = null,
    ) {
        val effectiveUsername = usernameOverride?.takeIf { it.isNotBlank() } ?: profile.username
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val sessionId = etSessionManager.registerSession(
                profileId = profile.id,
                label = profile.label,
            )

            val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
            val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null

            var isFidoAuth = false
            try {
                // Phase 1: SSH bootstrap — connect, verify host key
                val client = withContext(Dispatchers.IO) {
                    val authMethod = resolveAuthMethod(profile, password)
                    isFidoAuth = authMethod is ConnectionConfig.AuthMethod.FidoKey
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = effectiveUsername,
                        authMethod = authMethod,
                        sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                        forwardAgent = profile.forwardAgent,
                        agentIdentities = agentIdentitiesFor(profile),
                    )

                    val sshClient = SshClient().apply {
                        this.verboseLogger = verboseLogger
                    }

                    // Jump host takes priority, then SOCKS/HTTP proxy
                    val jumpProfileId = profile.jumpProfileId
                    val proxy = if (jumpProfileId != null) {
                        val (jid, _) = connectJumpHost(jumpProfileId, password)
                        sshSessionManager.createProxyJump(jid)
                    } else {
                        createNetworkProxy(profile)
                    }

                    try {
                        val hostKeyEntry = sshClient.connect(
                            config,
                            proxy = proxy,
                            keyboardInteractivePrompter = keyboardInteractivePrompter,
                        )
                        runTofuVerification(hostKeyEntry, clientToDisconnectOnReject = sshClient)
                    } catch (e: HostKeyAuthFailure) {
                        runTofuVerification(e.hostKey, clientToDisconnectOnReject = null)
                        throw e.cause ?: e
                    }

                    sshClient
                }

                // Phase 2: Resolve session manager, check for existing sessions
                val smgr = resolveSessionManager(profile)

                val listCmd = smgr.listCommand
                if (listCmd != null) {
                    val existingSessions = withContext(Dispatchers.IO) {
                        try {
                            val result = client.execCommand(listCmd)
                            if (result.exitStatus == 0) {
                                SessionManager.parseSessionList(smgr, result.stdout)
                            } else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    if (existingSessions.isNotEmpty()) {
                        etPendingClient = client
                        etPendingProfile = profile
                        etPendingVerboseLogger = verboseLogger
                        _sessionSelection.value = SessionSelection(
                            sessionId = sessionId,
                            profileId = profile.id,
                            managerLabel = smgr.label,
                            sessionNames = existingSessions,
                            manager = smgr,
                            transportType = "ET",
                        )
                        _connectingProfileId.value = null
                        return@launch // UI will call onSessionSelected() to continue
                    }
                }

                // No existing sessions — proceed directly
                finishEtConnect(sessionId, profile, client, smgr, null, verboseLogger = verboseLogger)
            } catch (e: Exception) {
                Log.e(TAG, "connectEternalTerminal failed for ${profile.label}: ${e.message}", e)
                connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message, verboseLog = verboseLogger?.drain())
                etPendingClient?.disconnect()
                etPendingClient = null
                etPendingProfile = null
                etPendingVerboseLogger = null
                etSessionManager.updateStatus(sessionId, EtSessionManager.SessionState.Status.ERROR)
                etSessionManager.removeSession(sessionId)
                val msg = e.message ?: ""
                val isAuthError = keyOnly && (
                    msg.contains("Auth fail", ignoreCase = true) ||
                        msg.contains("Auth cancel", ignoreCase = true) ||
                        msg.contains("authentication", ignoreCase = true) ||
                        msg.contains("publickey", ignoreCase = true)
                    )
                if (isFidoAuth && (isAuthError || (keyOnly && msg.isBlank()))) {
                    val fidoDetail = fidoAuthenticator.lastAssertionError
                    _error.value = if (fidoDetail != null) "Security key: $fidoDetail"
                    else msg.ifBlank { "Security key authentication failed" }
                } else if (isAuthError || (keyOnly && msg.isBlank())) {
                    _passwordFallback.value = profile
                } else {
                    _error.value = msg.ifBlank { "Eternal Terminal connection failed" }
                }
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    private fun connectMosh(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean,
        usernameOverride: String? = null,
    ) {
        val effectiveUsername = usernameOverride?.takeIf { it.isNotBlank() } ?: profile.username
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val sessionId = moshSessionManager.registerSession(
                profileId = profile.id,
                label = profile.label,
            )

            val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
            val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null

            var isFidoAuth = false
            try {
                // Phase 1: SSH bootstrap — connect, resolve session manager, list sessions
                val client = withContext(Dispatchers.IO) {
                    val authMethod = resolveAuthMethod(profile, password)
                    isFidoAuth = authMethod is ConnectionConfig.AuthMethod.FidoKey
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = effectiveUsername,
                        authMethod = authMethod,
                        sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                        forwardAgent = profile.forwardAgent,
                        agentIdentities = agentIdentitiesFor(profile),
                    )

                    val sshClient = SshClient().apply {
                        this.verboseLogger = verboseLogger
                    }

                    // Jump host takes priority, then SOCKS/HTTP proxy
                    val jumpProfileId = profile.jumpProfileId
                    val proxy = if (jumpProfileId != null) {
                        val (jid, _) = connectJumpHost(jumpProfileId, password)
                        sshSessionManager.createProxyJump(jid)
                    } else {
                        createNetworkProxy(profile)
                    }

                    try {
                        val hostKeyEntry = sshClient.connect(
                            config,
                            proxy = proxy,
                            keyboardInteractivePrompter = keyboardInteractivePrompter,
                        )
                        runTofuVerification(hostKeyEntry, clientToDisconnectOnReject = sshClient)
                    } catch (e: HostKeyAuthFailure) {
                        runTofuVerification(e.hostKey, clientToDisconnectOnReject = null)
                        throw e.cause ?: e
                    }

                    sshClient
                }

                val smgr = resolveSessionManager(profile)

                // If session manager supports listing, check for existing sessions
                val listCmd = smgr.listCommand
                if (listCmd != null) {
                    val existingSessions = withContext(Dispatchers.IO) {
                        try {
                            val result = client.execCommand(listCmd)
                            if (result.exitStatus == 0) {
                                SessionManager.parseSessionList(smgr, result.stdout)
                            } else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    if (existingSessions.isNotEmpty()) {
                        // Keep SSH client alive for mosh-server exec after user picks
                        moshPendingClient = client
                        moshPendingHost = profile.host
                        moshPendingVerboseLogger = verboseLogger
                        _sessionSelection.value = SessionSelection(
                            sessionId = sessionId,
                            profileId = profile.id,
                            managerLabel = smgr.label,
                            sessionNames = existingSessions,
                            manager = smgr,
                            transportType = "MOSH",
                        )
                        _connectingProfileId.value = null
                        return@launch // UI will call onSessionSelected() to continue
                    }
                }

                // No existing sessions — proceed directly
                finishMoshConnect(sessionId, profile.id, profile.host, client, smgr, null, verboseLogger = verboseLogger)
            } catch (e: Exception) {
                Log.e(TAG, "connectMosh failed for ${profile.label}: ${e.message}", e)
                connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message, verboseLog = verboseLogger?.drain())
                moshPendingClient?.disconnect()
                moshPendingClient = null
                moshPendingHost = null
                moshPendingVerboseLogger = null
                moshSessionManager.updateStatus(sessionId, MoshSessionManager.SessionState.Status.ERROR)
                moshSessionManager.removeSession(sessionId)
                val msg = e.message ?: ""
                val isAuthError = keyOnly && (
                    msg.contains("Auth fail", ignoreCase = true) ||
                        msg.contains("Auth cancel", ignoreCase = true) ||
                        msg.contains("authentication", ignoreCase = true) ||
                        msg.contains("publickey", ignoreCase = true)
                    )
                if (isFidoAuth && (isAuthError || (keyOnly && msg.isBlank()))) {
                    val fidoDetail = fidoAuthenticator.lastAssertionError
                    _error.value = if (fidoDetail != null) "Security key: $fidoDetail"
                    else msg.ifBlank { "Security key authentication failed" }
                } else if (isAuthError || (keyOnly && msg.isBlank())) {
                    _passwordFallback.value = profile
                } else if (msg.contains("mosh-server not found", ignoreCase = true) ||
                    msg.contains("command not found", ignoreCase = true) && msg.contains("mosh", ignoreCase = true)
                ) {
                    _showMoshSetupGuide.value = true
                } else if (msg.contains("mosh-client binary not found", ignoreCase = true)) {
                    _showMoshClientMissing.value = true
                } else {
                    _error.value = msg.ifBlank { "Mosh connection failed" }
                }
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    /**
     * Called from the session picker dialog when user selects a session.
     * @param sessionName The name to attach to, or null to create a new session.
     */
    /**
     * Restore multiple previous sessions by opening one tab per session name.
     * The first session uses the existing SSH connection; additional sessions
     * open new SSH connections to the same host.
     */
    fun restorePreviousSessions(sessionId: String, sessionNames: List<String>) {
        val sel = _sessionSelection.value
        _sessionSelection.value = null
        if (sel == null || sessionNames.isEmpty()) return

        val profileId = sel.profileId
        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                // First session uses the already-open SSH connection
                sshSessionManager.setChosenSessionName(sessionId, sessionNames.first())
                finishConnect(sessionId, profileId)

                // Additional sessions need new SSH connections
                for (name in sessionNames.drop(1)) {
                    val profile = repository.getById(profileId) ?: break
                    val password = profile.sshPassword ?: ""
                    // Reuse the stored connection config from the first session
                    val configPair = sshSessionManager.getConnectionConfigForProfile(profileId) ?: break
                    val (config, _) = configPair
                    val newClient = withContext(Dispatchers.IO) {
                        SshClient().apply {
                            connectBlocking(
                                config,
                                keyboardInteractivePrompter = keyboardInteractivePrompter,
                            )
                        }
                    }
                    val newSessionId = sshSessionManager.registerSession(profileId, profile.label, newClient)
                    val manager = sel.manager
                    val cmdOverride = withContext(Dispatchers.IO) {
                        preferencesRepository.sessionCommandOverride.first()
                    }
                    sshSessionManager.storeConnectionConfig(newSessionId, config, manager, cmdOverride, profile.postLoginCommand)
                    sshSessionManager.setChosenSessionName(newSessionId, name)
                    finishConnect(newSessionId, profileId, silent = true)
                }
                // Navigate to terminal after all tabs are open
                _navigateToTerminal.value = profileId
            } catch (e: Exception) {
                Log.e(TAG, "Restore sessions failed", e)
                _error.value = "Restore failed: ${e.message}"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    fun onSessionSelected(sessionId: String, sessionName: String?) {
        val sel = _sessionSelection.value
        _sessionSelection.value = null

        if (sel?.transportType == "MOSH") {
            // Mosh path: finish mosh connection with chosen session name
            val client = moshPendingClient
            val serverHost = moshPendingHost ?: ""
            val pendingLogger = moshPendingVerboseLogger
            moshPendingClient = null
            moshPendingHost = null
            moshPendingVerboseLogger = null
            if (client == null) {
                _error.value = "Mosh SSH connection lost"
                moshSessionManager.removeSession(sessionId)
                return
            }
            val profileId = sel.profileId
            viewModelScope.launch {
                _connectingProfileId.value = profileId
                try {
                    finishMoshConnect(sessionId, profileId, serverHost, client, sel.manager, sessionName, verboseLogger = pendingLogger)
                } catch (e: Exception) {
                    client.disconnect()
                    moshSessionManager.updateStatus(sessionId, MoshSessionManager.SessionState.Status.ERROR)
                    _error.value = e.message ?: "Mosh connection failed"
                    moshSessionManager.removeSession(sessionId)
                } finally {
                    _connectingProfileId.value = null
                }
            }
            return
        }

        if (sel?.transportType == "ET") {
            // ET path: finish ET connection with chosen session name
            val client = etPendingClient
            val profile = etPendingProfile
            val pendingLogger = etPendingVerboseLogger
            etPendingClient = null
            etPendingProfile = null
            etPendingVerboseLogger = null
            if (client == null || profile == null) {
                _error.value = "ET SSH connection lost"
                etSessionManager.removeSession(sessionId)
                return
            }
            val profileId = sel.profileId
            viewModelScope.launch {
                _connectingProfileId.value = profileId
                try {
                    finishEtConnect(sessionId, profile, client, sel.manager, sessionName, verboseLogger = pendingLogger)
                } catch (e: Exception) {
                    client.disconnect()
                    etSessionManager.updateStatus(sessionId, EtSessionManager.SessionState.Status.ERROR)
                    _error.value = e.message ?: "Eternal Terminal connection failed"
                    etSessionManager.removeSession(sessionId)
                } finally {
                    _connectingProfileId.value = null
                }
            }
            return
        }

        // SSH path
        val profileId = sel?.profileId ?: sshSessionManager.getSession(sessionId)?.profileId ?: return
        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                val effectiveName = sessionName ?: generateUniqueSessionName(
                    sshSessionManager.getSession(sessionId)?.label ?: sessionId.take(8),
                    sel?.sessionNames ?: emptyList(),
                )
                sshSessionManager.setChosenSessionName(sessionId, effectiveName)
                finishConnect(sessionId, profileId)
            } catch (e: Exception) {
                sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
                _error.value = e.message ?: "Connection failed"
                sshSessionManager.removeSession(sessionId)
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    /**
     * Kill a remote session (tmux/zellij/screen) and refresh the session list.
     */
    fun killRemoteSession(sessionName: String) {
        val sel = _sessionSelection.value ?: return
        val killCmd = sel.manager.killCommand?.invoke(sessionName) ?: return
        val client = when (sel.transportType) {
            "MOSH" -> moshPendingClient
            "ET" -> etPendingClient
            else -> sshSessionManager.getSession(sel.sessionId)?.client
        }
        if (client == null) return

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client.execCommand(killCmd)
                }
                // Refresh the session list
                val listCmd = sel.manager.listCommand ?: return@launch
                val updated = withContext(Dispatchers.IO) {
                    try {
                        val result = client.execCommand(listCmd)
                        if (result.exitStatus == 0) {
                            SessionManager.parseSessionList(sel.manager, result.stdout)
                        } else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                if (updated.isNotEmpty()) {
                    _sessionSelection.value = sel.copy(sessionNames = updated)
                } else {
                    _sessionSelection.value = null
                }
            } catch (e: Exception) {
                _error.value = "Failed to kill session: ${e.message}"
            }
        }
    }

    /**
     * Rename a remote session (tmux/screen/byobu) and refresh the session list.
     */
    fun renameRemoteSession(oldName: String, newName: String) {
        val sel = _sessionSelection.value ?: return
        val renameCmd = sel.manager.renameCommand?.invoke(oldName, newName) ?: return
        val client = when (sel.transportType) {
            "MOSH" -> moshPendingClient
            "ET" -> etPendingClient
            else -> sshSessionManager.getSession(sel.sessionId)?.client
        }
        if (client == null) return

        viewModelScope.launch {
            try {
                val renameResult = withContext(Dispatchers.IO) {
                    client.execCommand(renameCmd)
                }
                if (renameResult.exitStatus != 0) {
                    Log.w(TAG, "renameRemoteSession failed: exit=${renameResult.exitStatus} stderr='${renameResult.stderr}'")

                    _error.value = renameResult.stderr.ifBlank { "Rename failed (exit ${renameResult.exitStatus})" }
                }
                // Give the session manager a moment to propagate the rename
                kotlinx.coroutines.delay(500)
                // Refresh the session list
                val listCmd = sel.manager.listCommand ?: return@launch
                val updated = withContext(Dispatchers.IO) {
                    try {
                        val result = client.execCommand(listCmd)
                        if (result.exitStatus == 0) {
                            SessionManager.parseSessionList(sel.manager, result.stdout)
                        } else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                _sessionSelection.value = sel.copy(sessionNames = updated)
            } catch (e: Exception) {
                _error.value = "Failed to rename session: ${e.message}"
            }
        }
    }

    /**
     * Generate a session name that doesn't conflict with existing remote sessions.
     * Appends "-2", "-3", etc. if the base name is already taken.
     */
    private fun generateUniqueSessionName(label: String, remoteNames: List<String>): String {
        val base = label.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val existing = remoteNames.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base-$i" in existing) i++
        return "$base-$i"
    }

    fun dismissSessionPicker() {
        val sel = _sessionSelection.value ?: return
        _sessionSelection.value = null
        sshSessionManager.removeSession(sel.sessionId)
    }

    private fun createNetworkProxy(profile: ConnectionProfile): Proxy? {
        val proxyHost = profile.proxyHost ?: return null
        return when (profile.proxyType) {
            "SOCKS5" -> com.jcraft.jsch.ProxySOCKS5(proxyHost, profile.proxyPort)
            "SOCKS4" -> com.jcraft.jsch.ProxySOCKS4(proxyHost, profile.proxyPort)
            "HTTP" -> com.jcraft.jsch.ProxyHTTP(proxyHost, profile.proxyPort)
            else -> null
        }
    }

    /**
     * Connect to a jump host profile, reusing an existing connected session if available.
     * Returns Pair(sessionId, reused) — reused=true if an existing session was used.
     */
    private suspend fun connectJumpHost(jumpProfileId: String, password: String): Pair<String, Boolean> {
        // Reuse existing connected session for this jump profile
        val existing = sshSessionManager.getSessionsForProfile(jumpProfileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
        if (existing != null) {
            Log.d(TAG, "Reusing existing jump host session ${existing.sessionId}")
            return existing.sessionId to true
        }

        val jumpProfile = repository.getById(jumpProfileId)
            ?: throw Exception("Jump host profile not found")

        Log.d(TAG, "Auto-connecting jump host: ${jumpProfile.label} (${jumpProfile.host}:${jumpProfile.port})")
        val jumpClient = SshClient()
        val jumpSessionId = sshSessionManager.registerSession(jumpProfileId, "Jump: ${jumpProfile.label}", jumpClient)

        withContext(Dispatchers.IO) {
            val authMethod = resolveAuthMethod(jumpProfile, password)
            val config = ConnectionConfig(
                host = jumpProfile.host,
                port = jumpProfile.port,
                username = jumpProfile.username,
                authMethod = authMethod,
                sshOptions = ConnectionConfig.parseSshOptions(jumpProfile.sshOptions),
            )
            Log.d(TAG, "Jump host SSH connecting...")
            try {
                val hostKeyEntry = jumpClient.connect(
                    config,
                    keyboardInteractivePrompter = keyboardInteractivePrompter,
                )
                Log.d(TAG, "Jump host SSH connected, verifying host key...")
                runTofuVerification(
                    hostKeyEntry,
                    clientToDisconnectOnReject = jumpClient,
                    rejectedOnNewHostMessage = "Jump host key rejected by user",
                    rejectedOnChangeMessage = "Jump host key change rejected by user",
                )
            } catch (e: HostKeyAuthFailure) {
                runTofuVerification(
                    e.hostKey,
                    clientToDisconnectOnReject = null,
                    rejectedOnNewHostMessage = "Jump host key rejected by user",
                    rejectedOnChangeMessage = "Jump host key change rejected by user",
                )
                throw e.cause ?: e
            }

            sshSessionManager.storeConnectionConfig(jumpSessionId, config, SessionManager.NONE)
        }

        sshSessionManager.updateStatus(jumpSessionId, SshSessionManager.SessionState.Status.CONNECTED)
        Log.d(TAG, "Jump host connected: ${jumpProfile.label} ($jumpSessionId), isConnected=${jumpClient.isConnected}")
        return jumpSessionId to false
    }

    private suspend fun finishConnect(sessionId: String, profileId: String, verboseLog: String? = pendingVerboseLogs.remove(sessionId), silent: Boolean = false) {
        withContext(Dispatchers.IO) {
            sshSessionManager.openShellForSession(sessionId)

            // Apply enabled port forward rules
            val rules = portForwardRepository.getEnabledForProfile(profileId)
            if (rules.isNotEmpty()) {
                sshSessionManager.applyPortForwards(
                    sessionId,
                    rules.map { it.toForwardInfo() },
                )
            }
        }
        sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
        repository.markConnected(profileId)
        // Persist all open session names for this profile (pipe-delimited)
        sshSessionManager.getSession(sessionId)?.let { session ->
            if (session.sessionManager != SessionManager.NONE) {
                // Collect session names from all sessions for this profile
                val allNames = sshSessionManager.sessions.value.values
                    .filter { it.profileId == profileId && it.chosenSessionName != null }
                    .map { it.chosenSessionName!!.replace(Regex("[^A-Za-z0-9._-]"), "-") }
                    .toMutableList()
                // Add the current session if not already included
                val currentName = (session.chosenSessionName ?: session.label ?: sessionId.take(8))
                    .replace(Regex("[^A-Za-z0-9._-]"), "-")
                if (currentName !in allNames) allNames.add(currentName)
                repository.getById(profileId)?.let { profile ->
                    repository.save(profile.copy(lastSessionName = allNames.joinToString("|")))
                }
            }
        }
        val authDetail = sshSessionManager.getConnectionConfigForProfile(profileId)?.first?.let { config ->
            when (config.authMethod) {
                is ConnectionConfig.AuthMethod.Password -> "password"
                is ConnectionConfig.AuthMethod.PrivateKey -> "key"
                is ConnectionConfig.AuthMethod.PrivateKeys -> "key"
                is ConnectionConfig.AuthMethod.FidoKey -> "FIDO2"
            }
        }
        connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, details = authDetail, verboseLog = verboseLog)
        startForegroundServiceIfNeeded()
        if (!silent) {
            _navigateToTerminal.value = profileId
            // Verify a terminal tab appears — if the session manager (tmux/zellij/screen)
            // isn't installed on the remote host, the shell exits silently and no tab is created.
            viewModelScope.launch {
                kotlinx.coroutines.delay(6000)
                val hasTerminal = sshSessionManager.sessions.value.values.any {
                    it.profileId == profileId &&
                        it.status == SshSessionManager.SessionState.Status.CONNECTED &&
                        it.shellChannel?.isConnected == true
                }
                if (!hasTerminal) {
                    _error.value = "Shell closed — is your session manager (tmux/zellij/screen) installed on this host?"
                    _navigateToConnections.value = true
                }
            }
        }
    }

    /**
     * Finish mosh connection: exec mosh-server on SSH, parse MOSH CONNECT,
     * disconnect SSH, spawn mosh-client with session manager initial command.
     */
    private suspend fun finishMoshConnect(
        sessionId: String,
        profileId: String,
        serverHost: String,
        client: SshClient,
        manager: SessionManager,
        chosenSessionName: String?,
        silent: Boolean = false,
        verboseLogger: SshVerboseLogger? = null,
    ) {
        val moshConnect = withContext(Dispatchers.IO) {
            val customMoshCmd = repository.getById(profileId)?.moshServerCommand?.takeIf { it.isNotBlank() }
            val moshCmd = customMoshCmd ?: "mosh-server new -s -c 256 -l LANG=en_US.UTF-8"
            Log.d(TAG, "Running mosh-server bootstrap: $moshCmd")
            val result = client.execCommand(moshCmd)

            // Keep SSH client alive for SFTP — don't disconnect

            val connectLine = (result.stdout + "\n" + result.stderr)
                .lines()
                .firstOrNull { it.startsWith("MOSH CONNECT") }
                ?: run {
                    client.disconnect()
                    throw Exception(
                        "mosh-server not found or failed. " +
                            "Install with: apt install mosh\n" +
                            "stderr: ${result.stderr.take(200)}"
                    )
                }

            val parts = connectLine.split(" ")
            if (parts.size < 4) {
                client.disconnect()
                throw Exception("Unexpected mosh-server output: $connectLine")
            }

            Triple(serverHost, parts[2].toInt(), parts[3])
        }

        val (serverIp, moshPort, moshKey) = moshConnect
        Log.d(TAG, "MOSH CONNECT parsed: $serverIp:$moshPort")

        // Build session manager command with chosen or default session name
        val smCmd = manager.command
        var effectiveSessionName: String? = null
        if (smCmd != null) {
            val rawName = chosenSessionName
                ?: moshSessionManager.sessions.value[sessionId]?.label
                ?: sessionId.take(8)
            val sanitized = rawName.replace(Regex("[^A-Za-z0-9._-]"), "-")
            effectiveSessionName = sanitized
            moshSessionManager.setInitialCommand(sessionId, smCmd(sanitized))
        }

        val transportLogBuffer = if (verboseLogger != null) java.util.concurrent.ConcurrentLinkedQueue<String>() else null
        withContext(Dispatchers.IO) {
            moshSessionManager.connectSession(
                sessionId = sessionId,
                serverIp = serverIp,
                moshPort = moshPort,
                moshKey = moshKey,
                cols = 80,
                rows = 24,
                sshClient = client,
                verboseBuffer = transportLogBuffer,
            )
        }

        repository.markConnected(profileId)
        if (effectiveSessionName != null) {
            repository.getById(profileId)?.let { profile ->
                repository.save(profile.copy(lastSessionName = effectiveSessionName))
            }
        }
        connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = verboseLogger?.drain())
        startForegroundServiceIfNeeded()
        if (!silent) {
            _navigateToTerminal.value = profileId
        }
    }

    /**
     * Finish ET connection: exec etterminal on SSH, parse IDPASSKEY,
     * connect to etserver, set session manager initial command.
     */
    private suspend fun finishEtConnect(
        sessionId: String,
        profile: ConnectionProfile,
        client: SshClient,
        manager: SessionManager,
        chosenSessionName: String?,
        silent: Boolean = false,
        verboseLogger: SshVerboseLogger? = null,
    ) {
        val etPort = profile.etPort
        val (etClientId, etPasskey) = withContext(Dispatchers.IO) {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            fun randomAlphaNum(len: Int) = String(CharArray(len) { chars.random() })
            val proposedId = "XXX" + randomAlphaNum(13)
            val proposedKey = randomAlphaNum(32)
            val term = "xterm-256color"

            val etCmd = "echo '${proposedId}/${proposedKey}_${term}' | etterminal"
            Log.d(TAG, "ET bootstrap: running etterminal via SSH")
            val result = client.execCommand(etCmd)
            val output = result.stdout + "\n" + result.stderr

            val marker = "IDPASSKEY:"
            val markerPos = output.indexOf(marker)
            if (markerPos < 0) {
                client.disconnect()
                throw Exception(
                    "etterminal not found or failed on remote host. " +
                        "Install with: apt install et\n" +
                        "Output: ${output.take(200)}"
                )
            }
            val idPasskey = output.substring(markerPos + marker.length).trim().take(49)
            val parts = idPasskey.split("/", limit = 2)
            if (parts.size != 2 || parts[0].length != 16 || parts[1].length != 32) {
                client.disconnect()
                throw Exception("Unexpected etterminal output: $idPasskey")
            }
            Pair(parts[0], parts[1])
        }

        val serverHost = profile.host
        Log.d(TAG, "ET bootstrap: got clientId=${etClientId.take(6)}... connecting to $serverHost:$etPort")

        // Build session manager command with chosen or default session name
        val smCmd = manager.command
        var effectiveSessionName: String? = null
        if (smCmd != null) {
            val rawName = chosenSessionName
                ?: etSessionManager.sessions.value[sessionId]?.label
                ?: sessionId.take(8)
            val sanitized = rawName.replace(Regex("[^A-Za-z0-9._-]"), "-")
            effectiveSessionName = sanitized
            etSessionManager.setInitialCommand(sessionId, smCmd(sanitized))
        }

        val etTransportLogBuffer = if (verboseLogger != null) java.util.concurrent.ConcurrentLinkedQueue<String>() else null
        withContext(Dispatchers.IO) {
            etSessionManager.connectSession(
                sessionId = sessionId,
                serverHost = serverHost,
                etPort = etPort,
                clientId = etClientId,
                passkey = etPasskey,
                sshClient = client,
                verboseBuffer = etTransportLogBuffer,
            )
        }

        repository.markConnected(profile.id)
        if (effectiveSessionName != null) {
            repository.save(profile.copy(lastSessionName = effectiveSessionName))
        }
        connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.CONNECTED, verboseLog = verboseLogger?.drain())
        startForegroundServiceIfNeeded()
        if (!silent) {
            _navigateToTerminal.value = profile.id
        }
    }

    /**
     * Resolve the auth method for a connection profile.
     * If the profile has an assigned key, use it.
     * Otherwise if keys exist and password is empty, try the first available key.
     * Falls back to password auth.
     */
    private suspend fun resolveAuthMethod(
        profile: ConnectionProfile,
        password: String,
    ): ConnectionConfig.AuthMethod {
        // Profile has an explicit key assigned
        val keyId = profile.keyId
        if (keyId != null) {
            val keyBytes = sshKeyRepository.getDecryptedKeyBytes(keyId)
            val key = sshKeyRepository.getById(keyId)
            if (keyBytes != null && key != null) {
                // FIDO2 SK keys use hardware signing, not PEM key material
                if (key.keyType.startsWith("sk-")) {
                    Log.d(TAG, "Using FIDO2 SK key: ${key.keyType}")
                    return ConnectionConfig.AuthMethod.FidoKey(skKeyData = keyBytes)
                }
                // For encrypted keys, pass the original encrypted bytes + passphrase.
                // JSch decrypts at auth time — key never stored in plaintext.
                val passphrase = if (key.isEncrypted) password.toCharArray() else CharArray(0)
                return ConnectionConfig.AuthMethod.PrivateKey(
                    keyBytes = if (key.isEncrypted) keyBytes else rawKeyToPem(keyBytes, key.keyType),
                    passphrase = passphrase,
                )
            }
        }

        // No explicit key but keys are available — try all keys (skip encrypted keys
        // that need a passphrase — they require explicit assignment to a connection)
        if (password.isEmpty()) {
            val keys = sshKeyRepository.getAllDecrypted()
                .filter { !it.isEncrypted }
            if (keys.isNotEmpty()) {
                return ConnectionConfig.AuthMethod.PrivateKeys(
                    keys = keys.map { key ->
                        key.label to rawKeyToPem(key.privateKeyBytes, key.keyType)
                    }
                )
            }
        }

        return ConnectionConfig.AuthMethod.Password(password)
    }

    /**
     * Resolved agent-forwarding identities for a profile, plus diagnostics
     * needed to warn the user when the forwarded agent would be empty (e.g.
     * all stored SSH keys are passphrase-protected or none are stored at all).
     */
    private data class AgentIdentitiesResult(
        val keys: List<Pair<String, ByteArray>>,
        val skippedEncryptedCount: Int,
        val hadNoStoredKeys: Boolean,
        val forwardAgentEnabled: Boolean,
    ) {
        /**
         * User-visible warning if [forwardAgentEnabled] is true but [keys] ended
         * up empty. Returns null otherwise — no warning needed either when
         * forwarding is disabled, or when at least one usable key was found.
         */
        val warningMessage: String?
            get() {
                if (!forwardAgentEnabled || keys.isNotEmpty()) return null
                return when {
                    skippedEncryptedCount > 0 -> buildString {
                        append("Agent forwarding enabled but all ")
                        append(skippedEncryptedCount)
                        append(" stored SSH key")
                        if (skippedEncryptedCount != 1) append("s are")
                        else append(" is")
                        append(" passphrase-protected; the forwarded agent will be empty.")
                    }
                    hadNoStoredKeys -> "Agent forwarding enabled but no SSH keys are stored in Haven; the forwarded agent will be empty."
                    else -> null
                }
            }
    }

    /**
     * Pure helper — computes the agent-forwarding result for [profile]
     * without any side effects. Kept separate from [agentIdentitiesFor] so
     * the warning logic can be unit-tested.
     */
    private suspend fun computeAgentIdentities(profile: ConnectionProfile): AgentIdentitiesResult {
        if (!profile.forwardAgent) {
            return AgentIdentitiesResult(
                keys = emptyList(),
                skippedEncryptedCount = 0,
                hadNoStoredKeys = false,
                forwardAgentEnabled = false,
            )
        }
        val allKeys = sshKeyRepository.getAllDecrypted()
        val usable = allKeys.filter { !it.isEncrypted }
        return AgentIdentitiesResult(
            keys = usable.map { key -> key.label to rawKeyToPem(key.privateKeyBytes, key.keyType) },
            skippedEncryptedCount = allKeys.size - usable.size,
            hadNoStoredKeys = allKeys.isEmpty(),
            forwardAgentEnabled = true,
        )
    }

    /**
     * Build the list of identities to expose via SSH agent forwarding for
     * [profile]. Side-effectfully publishes a non-fatal warning to [warning]
     * when agent forwarding is enabled but the forwarded agent would end up
     * empty (e.g. all stored SSH keys are passphrase-protected, or none are
     * stored at all) — the connection itself still proceeds, the warning is
     * shown via snackbar so the user isn't left guessing why `ssh-add -l` on
     * the remote returns nothing.
     *
     * Encrypted keys are skipped because JSch's ChannelAgentForwarding has
     * no hook for unlocking them at sign-request time.
     */
    private suspend fun agentIdentitiesFor(profile: ConnectionProfile): List<Pair<String, ByteArray>> {
        val result = computeAgentIdentities(profile)
        result.warningMessage?.let {
            if (agentWarningShownFor.add(profile.id)) {
                _warning.value = it
            }
        }
        return result.keys
    }

    /**
     * Convert raw private key bytes to PEM format that JSch can parse.
     * Delegates to SshKeyExporter which handles all formats correctly:
     * - PEM/OpenSSH: pass through
     * - Ed25519 32-byte seed: OpenSSH format (JSch can't parse PKCS#8 for Ed25519)
     * - PKCS#8 DER (RSA/ECDSA): wrapped in PEM
     */
    private fun rawKeyToPem(rawBytes: ByteArray, keyType: String): ByteArray {
        return SshKeyExporter.toPem(rawBytes, keyType)
    }

    /**
     * Ensure a connected session for a profile has a shell channel open.
     * Jump host sessions are connected but have no shell until the user opens one.
     * Runs the full session manager (tmux/screen) detection flow.
     */
    fun ensureShellForProfile(profileId: String) {
        val session = sshSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
            ?: return
        if (session.shellChannel != null) {
            // Shell already open — navigate directly
            _navigateToTerminal.value = profileId
            return
        }

        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                // Set up session manager preference (per-profile or global default)
                val profile = repository.getById(profileId)
                val sshSessionMgr = resolveSessionManager(profile)
                val cmdOverride = preferencesRepository.sessionCommandOverride.first()
                val config = session.connectionConfig
                if (config != null) {
                    sshSessionManager.storeConnectionConfig(session.sessionId, config, sshSessionMgr, cmdOverride, profile?.postLoginCommand, profile?.postLoginBeforeSessionManager ?: false)
                }

                // Check for existing tmux/screen sessions
                val listCmd = sshSessionMgr.listCommand
                if (listCmd != null) {
                    val existingSessions = withContext(Dispatchers.IO) {
                        try {
                            val result = session.client.execCommand(listCmd)
                            if (result.exitStatus == 0) {
                                SessionManager.parseSessionList(sshSessionMgr, result.stdout)
                            } else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    if (existingSessions.isNotEmpty()) {
                        _sessionSelection.value = SessionSelection(
                            sessionId = session.sessionId,
                            profileId = profileId,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                            manager = sshSessionMgr,
                        )
                        _connectingProfileId.value = null
                        return@launch
                    }
                }

                finishConnect(session.sessionId, profileId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to open shell"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    // --- Port Forward Management ---

    fun portForwardRules(profileId: String): StateFlow<List<PortForwardRule>> =
        portForwardRepository.observeForProfile(profileId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun savePortForwardRule(rule: PortForwardRule) {
        viewModelScope.launch {
            val session = sshSessionManager.getSessionsForProfile(rule.profileId)
                .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }

            // If editing an existing rule on a live session, remove the old forward first
            if (session != null) {
                val oldForward = session.activeForwards.firstOrNull { it.ruleId == rule.id }
                if (oldForward != null) {
                    withContext(Dispatchers.IO) {
                        sshSessionManager.removePortForward(session.sessionId, oldForward)
                    }
                }
            }

            portForwardRepository.save(rule)

            // Activate the new/updated forward on the live session
            if (session != null && rule.enabled) {
                withContext(Dispatchers.IO) {
                    sshSessionManager.applyPortForwards(session.sessionId, listOf(rule.toForwardInfo()))
                }
            }
        }
    }

    fun deletePortForwardRule(ruleId: String, profileId: String) {
        viewModelScope.launch {
            // Deactivate on live session if connected
            val session = sshSessionManager.getSessionsForProfile(profileId)
                .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
            if (session != null) {
                val forward = session.activeForwards.firstOrNull { it.ruleId == ruleId }
                if (forward != null) {
                    withContext(Dispatchers.IO) {
                        sshSessionManager.removePortForward(session.sessionId, forward)
                    }
                }
            }
            portForwardRepository.delete(ruleId)
        }
    }

    private fun PortForwardRule.toForwardInfo() = SshSessionManager.PortForwardInfo(
        ruleId = id,
        type = when (type) {
            PortForwardRule.Type.LOCAL -> SshSessionManager.PortForwardType.LOCAL
            PortForwardRule.Type.REMOTE -> SshSessionManager.PortForwardType.REMOTE
            PortForwardRule.Type.DYNAMIC -> SshSessionManager.PortForwardType.DYNAMIC
        },
        bindAddress = bindAddress,
        bindPort = bindPort,
        targetHost = targetHost,
        targetPort = targetPort,
    )

    fun disconnect(profileId: String) {
        // Drain transport logs before disconnecting (Mosh/ET capture logs in-session)
        val moshLog = moshSessionManager.getSessionsForProfile(profileId)
            .mapNotNull { it.moshSession?.drainTransportLog() }
            .joinToString("\n").ifEmpty { null }
        val etLog = etSessionManager.getSessionsForProfile(profileId)
            .mapNotNull { it.etSession?.drainTransportLog() }
            .joinToString("\n").ifEmpty { null }
        val transportLog = listOfNotNull(moshLog, etLog).joinToString("\n").ifEmpty { null }

        viewModelScope.launch { connectionLogRepository.logEvent(profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = transportLog) }
        sessionManagerRegistry.disconnectProfile(profileId)
        localSessionManager.desktopManager.stopAll()
        updateServiceNotification()
    }

    private fun updateServiceNotification() {
        if (sessionManagerRegistry.hasActiveSessions()) {
            startForegroundServiceIfNeeded()
        } else {
            val intent = Intent(appContext, SshConnectionService::class.java)
            appContext.stopService(intent)
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun showError(message: String) {
        _error.value = message
    }

    fun dismissWarning() {
        _warning.value = null
    }

    private val _deploySuccess = MutableStateFlow(false)
    val deploySuccess: StateFlow<Boolean> = _deploySuccess.asStateFlow()

    fun dismissDeploySuccess() {
        _deploySuccess.value = false
    }

    /**
     * Auto-deploy the first SSH key to a localhost/VM profile after a successful
     * password-based connect. Only fires if keys exist and haven't been deployed yet.
     */
    private fun maybeAutoDeployKey(profile: ConnectionProfile, password: String) {
        val isLocalVm = profile.host in listOf("localhost", "127.0.0.1") ||
            profile.host == localVmStatus.value.directIp
        if (!isLocalVm) return

        viewModelScope.launch {
            val keys = sshKeyRepository.observeAll().first()
            if (keys.isEmpty()) return@launch

            // Check if key is already deployed by trying key auth
            val testClient = SshClient()
            try {
                val keyAuth = resolveAuthMethod(profile, "")
                if (keyAuth is ConnectionConfig.AuthMethod.Password) return@launch // no keys to deploy
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = keyAuth,
                )
                withContext(Dispatchers.IO) {
                    testClient.connect(config)
                }
                // Key auth succeeded — already deployed
                testClient.disconnect()
                return@launch
            } catch (_: Exception) {
                testClient.disconnect()
            }

            // Key auth failed — deploy the first key
            val key = keys.first()
            val deployClient = SshClient()
            try {
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = ConnectionConfig.AuthMethod.Password(password),
                )
                withContext(Dispatchers.IO) {
                    deployClient.connect(config)
                }
                // Skip host key verification — we already verified during the connect

                val pubKey = key.publicKeyOpenSsh.trim()
                val command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "grep -qF '${pubKey.substringAfterLast(' ')}' ~/.ssh/authorized_keys 2>/dev/null || " +
                    "echo '${pubKey}' >> ~/.ssh/authorized_keys && " +
                    "chmod 600 ~/.ssh/authorized_keys"

                val result = withContext(Dispatchers.IO) {
                    deployClient.execCommand(command)
                }
                if (result.exitStatus == 0) {
                    _deploySuccess.value = true
                    Log.d(TAG, "Auto-deployed SSH key to ${profile.host}:${profile.port}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Auto-deploy key failed (non-fatal): ${e.message}")
            } finally {
                deployClient.disconnect()
            }
        }
    }

    fun deployKey(profile: ConnectionProfile, keyId: String, password: String) {
        viewModelScope.launch {
            _error.value = null
            val key = sshKeyRepository.getById(keyId)
            if (key == null) {
                _error.value = "SSH key not found"
                return@launch
            }

            val client = SshClient()
            try {
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = ConnectionConfig.AuthMethod.Password(password),
                )
                try {
                    val hostKeyEntry = client.connect(config)
                    runTofuVerification(hostKeyEntry, clientToDisconnectOnReject = client)
                } catch (e: HostKeyAuthFailure) {
                    runTofuVerification(e.hostKey, clientToDisconnectOnReject = null)
                    throw e.cause ?: e
                }

                val pubKey = key.publicKeyOpenSsh.trim()
                val command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "echo '${pubKey}' >> ~/.ssh/authorized_keys && " +
                    "chmod 600 ~/.ssh/authorized_keys"

                val result = client.execCommand(command)
                if (result.exitStatus != 0) {
                    _error.value = "Deploy failed: ${result.stderr.ifBlank { "exit ${result.exitStatus}" }}"
                } else {
                    _deploySuccess.value = true
                }
            } catch (e: Exception) {
                _error.value = "Deploy failed: ${e.message ?: "unknown error"}"
            } finally {
                client.disconnect()
            }
        }
    }

    private fun startForegroundServiceIfNeeded() {
        val intent = Intent(appContext, SshConnectionService::class.java)
        appContext.startForegroundService(intent)
    }

    fun parseQuickConnect(input: String): ConnectionProfile? {
        val config = ConnectionConfig.parseQuickConnect(input) ?: return null
        return ConnectionProfile(
            label = "${config.username}@${config.host}",
            host = config.host,
            port = config.port,
            username = config.username,
        )
    }

    private fun UserPreferencesRepository.SessionManager.toSshSessionManager(): SessionManager =
        when (this) {
            UserPreferencesRepository.SessionManager.NONE -> SessionManager.NONE
            UserPreferencesRepository.SessionManager.TMUX -> SessionManager.TMUX
            UserPreferencesRepository.SessionManager.ZELLIJ -> SessionManager.ZELLIJ
            UserPreferencesRepository.SessionManager.SCREEN -> SessionManager.SCREEN
            UserPreferencesRepository.SessionManager.BYOBU -> SessionManager.BYOBU
        }

    private suspend fun resolveSessionManager(profile: ConnectionProfile?): SessionManager {
        val override = profile?.sessionManager
        return if (override != null) {
            try {
                SessionManager.valueOf(override)
            } catch (_: IllegalArgumentException) {
                preferencesRepository.sessionManager.first().toSshSessionManager()
            }
        } else {
            preferencesRepository.sessionManager.first().toSshSessionManager()
        }
    }

    // --- Silent (non-interactive) connect methods for group launch ---

    /**
     * Connect a profile without interactive dialogs. Throws on failure.
     * Does not set _connectingProfileId or emit _navigateToTerminal.
     */
    private suspend fun connectSilent(profile: ConnectionProfile) {
        when {
            profile.isLocal -> connectLocalSilent(profile)
            profile.isReticulum -> connectReticulumSilent(profile)
            profile.isEternalTerminal -> connectEtSilent(profile)
            profile.isMosh -> connectMoshSilent(profile)
            else -> connectSshSilent(profile)
        }
    }

    private suspend fun connectLocalSilent(profile: ConnectionProfile) {
        val existing = localSessionManager.getSessionsForProfile(profile.id)
        if (existing.any { it.status == LocalSessionManager.SessionState.Status.CONNECTED }) {
            return
        }

        val prootManager = localSessionManager.prootManager
        if (prootManager.prootBinary != null && !prootManager.isRootfsInstalled) {
            prootManager.installRootfs()
        }

        val sessionId = localSessionManager.registerSession(profile.id, profile.label)
        try {
            localSessionManager.connectSession(sessionId)
            repository.markConnected(profile.id)
            connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.CONNECTED)
            startForegroundServiceIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "connectLocalSilent failed: ${e.message}", e)
            connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message)
            localSessionManager.updateStatus(sessionId, LocalSessionManager.SessionState.Status.ERROR)
            localSessionManager.removeSession(sessionId)
            throw e
        }
    }

    private suspend fun connectReticulumSilent(profile: ConnectionProfile) {
        val destinationHash = profile.destinationHash
            ?: throw Exception("No destination hash for ${profile.label}")

        val sessionId = reticulumSessionManager.registerSession(
            profileId = profile.id,
            label = profile.label,
            destinationHash = destinationHash,
        )

        try {
            val configDir = File(appContext.filesDir, "reticulum").apply { mkdirs() }.absolutePath
            withContext(Dispatchers.IO) {
                reticulumSessionManager.connectSession(
                    sessionId = sessionId,
                    configDir = configDir,
                    host = profile.reticulumHost,
                    port = profile.reticulumPort,
                    ifacNetname = profile.reticulumNetworkName,
                    ifacNetkey = profile.reticulumPassphrase,
                )
            }
            repository.markConnected(profile.id)
            connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.CONNECTED)
            startForegroundServiceIfNeeded()
        } catch (e: Exception) {
            reticulumSessionManager.updateStatus(sessionId, ReticulumSessionManager.SessionState.Status.ERROR)
            reticulumSessionManager.removeSession(sessionId)
            throw e
        }
    }

    private suspend fun connectSshSilent(profile: ConnectionProfile) {
        val password = profile.sshPassword ?: ""
        val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
        val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null
        val client = SshClient().apply {
            fidoAuthenticator = this@ConnectionsViewModel.fidoAuthenticator
            this.verboseLogger = verboseLogger
        }
        val sessionId = sshSessionManager.registerSession(profile.id, profile.label, client)
        var autoCreatedJumpSessionId: String? = null

        try {
            val jumpProfileId = profile.jumpProfileId
            val jumpSessionId = if (jumpProfileId != null) {
                val (jid, reused) = connectJumpHost(jumpProfileId, password)
                if (!reused) autoCreatedJumpSessionId = jid
                jid
            } else null

            if (jumpSessionId != null) {
                sshSessionManager.setJumpSessionId(sessionId, jumpSessionId)
            }

            withContext(Dispatchers.IO) {
                val authMethod = resolveAuthMethod(profile, password)
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    forwardAgent = profile.forwardAgent,
                    agentIdentities = agentIdentitiesFor(profile),
                )
                val proxy = if (jumpSessionId != null) {
                    sshSessionManager.createProxyJump(jumpSessionId)
                        ?: throw Exception("Jump host session not usable for tunneling")
                } else {
                    createNetworkProxy(profile)
                }
                val hostKeyEntry = client.connect(config, proxy = proxy)

                // Silent TOFU: accept new hosts, reject changed keys
                when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> hostKeyVerifier.accept(result.entry)
                    is HostKeyResult.KeyChanged -> {
                        client.disconnect()
                        throw Exception("Host key changed for ${profile.host} — possible MITM")
                    }
                }

                val sshSessionMgr = resolveSessionManager(profile)
                val cmdOverride = preferencesRepository.sessionCommandOverride.first()
                sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr, cmdOverride, profile.postLoginCommand, profile.postLoginBeforeSessionManager)
            }

            verboseLogger?.drain()?.let { pendingVerboseLogs[sessionId] = it }

            // Restore last session name if persisted, so the session manager
            // command (tmux -A, zellij --create, screen -dRR) reattaches to it.
            profile.lastSessionName?.let {
                sshSessionManager.setChosenSessionName(sessionId, it)
            }
            finishConnect(sessionId, profile.id, verboseLog = verboseLogger?.drain(), silent = true)
        } catch (e: Exception) {
            Log.e(TAG, "connectSshSilent failed for ${profile.label}: ${e.message}", e)
            connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message, verboseLog = verboseLogger?.drain())
            sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
            sshSessionManager.removeSession(sessionId)
            autoCreatedJumpSessionId?.let { sshSessionManager.removeSession(it) }
            throw e
        }
    }

    private suspend fun connectMoshSilent(profile: ConnectionProfile) {
        val password = profile.sshPassword ?: ""
        val sessionId = moshSessionManager.registerSession(profileId = profile.id, label = profile.label)
        val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
        val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null

        try {
            val client = withContext(Dispatchers.IO) {
                val authMethod = resolveAuthMethod(profile, password)
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    forwardAgent = profile.forwardAgent,
                    agentIdentities = agentIdentitiesFor(profile),
                )
                val sshClient = SshClient().apply {
                    this.verboseLogger = verboseLogger
                }
                val jumpProfileId = profile.jumpProfileId
                val proxy = if (jumpProfileId != null) {
                    val (jid, _) = connectJumpHost(jumpProfileId, password)
                    sshSessionManager.createProxyJump(jid)
                } else {
                    createNetworkProxy(profile)
                }
                val hostKeyEntry = sshClient.connect(config, proxy = proxy)

                when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> hostKeyVerifier.accept(result.entry)
                    is HostKeyResult.KeyChanged -> {
                        sshClient.disconnect()
                        throw Exception("Host key changed for ${profile.host} — possible MITM")
                    }
                }
                sshClient
            }

            val smgr = resolveSessionManager(profile)
            finishMoshConnect(sessionId, profile.id, profile.host, client, smgr, profile.lastSessionName, silent = true, verboseLogger = verboseLogger)
        } catch (e: Exception) {
            Log.e(TAG, "connectMoshSilent failed for ${profile.label}: ${e.message}", e)
            connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message, verboseLog = verboseLogger?.drain())
            moshSessionManager.updateStatus(sessionId, MoshSessionManager.SessionState.Status.ERROR)
            moshSessionManager.removeSession(sessionId)
            throw e
        }
    }

    private suspend fun connectEtSilent(profile: ConnectionProfile) {
        val password = profile.sshPassword ?: ""
        val sessionId = etSessionManager.registerSession(profileId = profile.id, label = profile.label)
        val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
        val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null

        try {
            val client = withContext(Dispatchers.IO) {
                val authMethod = resolveAuthMethod(profile, password)
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    forwardAgent = profile.forwardAgent,
                    agentIdentities = agentIdentitiesFor(profile),
                )
                val sshClient = SshClient().apply {
                    this.verboseLogger = verboseLogger
                }
                val jumpProfileId = profile.jumpProfileId
                val proxy = if (jumpProfileId != null) {
                    val (jid, _) = connectJumpHost(jumpProfileId, password)
                    sshSessionManager.createProxyJump(jid)
                } else {
                    createNetworkProxy(profile)
                }
                val hostKeyEntry = sshClient.connect(config, proxy = proxy)

                when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> hostKeyVerifier.accept(result.entry)
                    is HostKeyResult.KeyChanged -> {
                        sshClient.disconnect()
                        throw Exception("Host key changed for ${profile.host} — possible MITM")
                    }
                }
                sshClient
            }

            val smgr = resolveSessionManager(profile)
            finishEtConnect(sessionId, profile, client, smgr, profile.lastSessionName, silent = true, verboseLogger = verboseLogger)
        } catch (e: Exception) {
            Log.e(TAG, "connectEtSilent failed for ${profile.label}: ${e.message}", e)
            connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message, verboseLog = verboseLogger?.drain())
            etSessionManager.updateStatus(sessionId, EtSessionManager.SessionState.Status.ERROR)
            etSessionManager.removeSession(sessionId)
            throw e
        }
    }
}
