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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.et.EtSessionManager
import sh.haven.core.reticulum.ReticulumBridge
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.smb.SmbSessionManager
import android.util.Log
import java.io.File
import java.util.Base64
import javax.inject.Inject

private const val TAG = "ConnectionsVM"

/** Unified connection status that maps both SSH and Reticulum states. */
enum class ProfileStatus { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val reticulumSessionManager: ReticulumSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val reticulumBridge: ReticulumBridge,
    private val smbSessionManager: SmbSessionManager,
    private val sshKeyRepository: SshKeyRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val hostKeyVerifier: HostKeyVerifier,
) : ViewModel() {

    val connections: StateFlow<List<ConnectionProfile>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sshKeys: StateFlow<List<SshKey>> = sshKeyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val globalSessionManagerLabel: StateFlow<String> = preferencesRepository.sessionManager
        .map { it.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "None")

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
            smbSessionManager.sessions,
        ) { base, smbMap ->
            @Suppress("UNCHECKED_CAST")
            val sshMap = base[0] as Map<String, SshSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val rnsMap = base[1] as Map<String, ReticulumSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val moshMap = base[2] as Map<String, MoshSessionManager.SessionState>
            @Suppress("UNCHECKED_CAST")
            val etMap = base[3] as Map<String, EtSessionManager.SessionState>
            val result = mutableMapOf<String, ProfileStatus>()

            // SSH statuses
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
                val statuses = states.map { it.status }
                val rnsStatus = when {
                    ReticulumSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    ReticulumSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    ReticulumSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
                val existing = result[profileId]
                if (existing == null || rnsStatus.ordinal < existing.ordinal) {
                    result[profileId] = rnsStatus
                }
            }

            // Mosh statuses
            moshMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                val statuses = states.map { it.status }
                val moshStatus = when {
                    MoshSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    MoshSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    MoshSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
                val existing = result[profileId]
                if (existing == null || moshStatus.ordinal < existing.ordinal) {
                    result[profileId] = moshStatus
                }
            }

            // ET statuses
            etMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                val statuses = states.map { it.status }
                val etStatus = when {
                    EtSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    EtSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    EtSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
                val existing = result[profileId]
                if (existing == null || etStatus.ordinal < existing.ordinal) {
                    result[profileId] = etStatus
                }
            }

            // SMB statuses
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

            result.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The profileId currently being connected (for spinner in UI). */
    private val _connectingProfileId = MutableStateFlow<String?>(null)
    val connectingProfileId: StateFlow<String?> = _connectingProfileId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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

    /** Emitted once after a successful connect to trigger navigation to terminal (profileId). */
    private val _navigateToTerminal = MutableStateFlow<String?>(null)
    val navigateToTerminal: StateFlow<String?> = _navigateToTerminal.asStateFlow()

    /** Emitted to navigate to VNC screen with connection params. */
    data class VncNavigation(val host: String, val port: Int, val password: String?)
    private val _navigateToVnc = MutableStateFlow<VncNavigation?>(null)
    val navigateToVnc: StateFlow<VncNavigation?> = _navigateToVnc.asStateFlow()

    /** Emitted to navigate to RDP screen with connection params. */
    data class RdpNavigation(val host: String, val port: Int, val username: String, val password: String, val domain: String, val sshForward: Boolean = false, val sshProfileId: String? = null, val sshSessionId: String? = null)
    private val _navigateToRdp = MutableStateFlow<RdpNavigation?>(null)
    val navigateToRdp: StateFlow<RdpNavigation?> = _navigateToRdp.asStateFlow()

    /** Emitted to navigate to Files tab for an SMB connection. */
    private val _navigateToSmb = MutableStateFlow<String?>(null)
    val navigateToSmb: StateFlow<String?> = _navigateToSmb.asStateFlow()

    /** Emitted to open a new session (new tab) on an already-connected profile. */
    private val _newSessionProfileId = MutableStateFlow<String?>(null)
    val newSessionProfileId: StateFlow<String?> = _newSessionProfileId.asStateFlow()

    /** When non-null, the UI should show a session picker dialog. */
    data class SessionSelection(
        val sessionId: String,
        val profileId: String,
        val managerLabel: String,
        val sessionNames: List<String>,
        val manager: SessionManager = SessionManager.NONE,
        /** "SSH" or "MOSH" — determines which finish path onSessionSelected uses. */
        val transportType: String = "SSH",
    )

    private val _sessionSelection = MutableStateFlow<SessionSelection?>(null)
    val sessionSelection: StateFlow<SessionSelection?> = _sessionSelection.asStateFlow()

    /** SSH client + host kept alive during mosh session picker (for mosh-server exec). */
    private var moshPendingClient: SshClient? = null
    private var moshPendingHost: String? = null

    /** SSH client + host kept alive during ET session picker. */
    private var etPendingClient: SshClient? = null
    private var etPendingProfile: ConnectionProfile? = null

    fun onNavigated() {
        _navigateToTerminal.value = null
        _navigateToVnc.value = null
        _navigateToRdp.value = null
        _navigateToSmb.value = null
        _newSessionProfileId.value = null
    }

    /** Open a new terminal session on an already-connected profile. */
    fun openNewSession(profileId: String) {
        _newSessionProfileId.value = profileId
    }

    data class DiscoveredDestination(val hash: String, val hops: Int)

    private val _discoveredDestinations = MutableStateFlow<List<DiscoveredDestination>>(emptyList())
    val discoveredDestinations: StateFlow<List<DiscoveredDestination>> = _discoveredDestinations.asStateFlow()

    private val networkDiscovery = NetworkDiscovery(appContext)
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = networkDiscovery.hosts
    val discoveredSmbHosts: StateFlow<List<DiscoveredHost>> = networkDiscovery.smbHosts
    val localVmStatus: StateFlow<LocalVmStatus> = networkDiscovery.localVm

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
        networkDiscovery.stop()
    }

    fun refreshDiscoveredDestinations() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Speculatively connect to Sideband if RNS isn't initialised yet
                if (!reticulumBridge.isInitialised()) {
                    Log.d(TAG, "RNS not initialised, probing for Sideband...")
                    val configDir = File(appContext.filesDir, "reticulum")
                        .apply { mkdirs() }.absolutePath
                    val probeResult = reticulumBridge.probeSideband(configDir)
                    Log.d(TAG, "probeSideband result: $probeResult, isInitialised: ${reticulumBridge.isInitialised()}")
                } else {
                    Log.d(TAG, "RNS already initialised (mode=${reticulumBridge.getInitMode()})")
                }
                if (!reticulumBridge.isInitialised()) {
                    Log.d(TAG, "RNS still not initialised after probe, skipping destination refresh")
                    return@launch
                }

                // Proactively request paths for saved Reticulum connections
                requestPathsForSavedConnections()

                val json = reticulumBridge.getDiscoveredDestinations()
                val list = parseDiscoveredDestinations(json)
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
                val alreadyKnown = reticulumBridge.requestPath(hash)
                Log.d(TAG, "requestPath(${hash.take(8)}...): known=$alreadyKnown")
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestPathsForSavedConnections failed", e)
        }
    }

    private fun parseDiscoveredDestinations(json: String): List<DiscoveredDestination> {
        // Simple JSON array parsing without adding a dependency
        val results = mutableListOf<DiscoveredDestination>()
        val pattern = Regex(""""hash":\s*"([0-9a-f]+)".*?"hops":\s*(-?\d+)""")
        for (match in pattern.findAll(json)) {
            results.add(DiscoveredDestination(
                hash = match.groupValues[1],
                hops = match.groupValues[2].toIntOrNull() ?: -1,
            ))
        }
        return results
    }

    fun saveConnection(profile: ConnectionProfile) {
        viewModelScope.launch {
            repository.save(profile)
        }
    }

    fun deleteConnection(id: String) {
        viewModelScope.launch {
            sshSessionManager.removeAllSessionsForProfile(id)
            reticulumSessionManager.removeAllSessionsForProfile(id)
            moshSessionManager.removeAllSessionsForProfile(id)
            smbSessionManager.removeAllSessionsForProfile(id)
            repository.delete(id)
        }
    }

    /**
     * Try connecting with key auth (no password dialog). On failure, show password dialog.
     */
    fun connectWithKey(profile: ConnectionProfile) {
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
    ) {
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
        if (profile.isReticulum) {
            connectReticulum(profile)
            return
        }
        if (profile.isEternalTerminal) {
            connectEternalTerminal(profile, password, keyOnly)
            return
        }
        if (profile.isMosh) {
            connectMosh(profile, password, keyOnly)
            return
        }
        connectSsh(profile, password, keyOnly, rememberPassword)
    }

    private fun connectVnc(profile: ConnectionProfile) {
        val host = profile.host
        val port = profile.vncPort ?: profile.port
        val password = profile.vncPassword
        viewModelScope.launch {
            repository.markConnected(profile.id)
        }
        _navigateToVnc.value = VncNavigation(host, port, password)
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
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect SSH tunnel host for RDP", e)
                    _error.value = "SSH tunnel: ${e.message}"
                } finally {
                    _connectingProfileId.value = null
                }
            } else {
                _navigateToRdp.value = RdpNavigation(host, port, username, rdpPassword, domain, profile.rdpSshForward, profile.rdpSshProfileId)
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

    private fun connectSsh(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean,
        rememberPassword: Boolean?,
    ) {
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val client = SshClient()
            val sessionId = sshSessionManager.registerSession(profile.id, profile.label, client)

            // Track whether we auto-created the jump session (for cleanup on failure)
            var autoCreatedJumpSessionId: String? = null
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
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = profile.username,
                        authMethod = authMethod,
                        sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    )

                    // Create proxy through jump host if applicable
                    val proxy = jumpSessionId?.let { jid ->
                        sshSessionManager.createProxyJump(jid)
                            ?: throw Exception("Jump host session not usable for tunneling")
                    }
                    Log.d(TAG, "Connecting to ${config.host}:${config.port} (proxy=${proxy != null})")
                    val hostKeyEntry = client.connect(config, proxy = proxy)

                    // TOFU host key verification
                    when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                        is HostKeyResult.Trusted -> { /* key matches — continue */ }
                        is HostKeyResult.NewHost -> {
                            val deferred = CompletableDeferred<Boolean>()
                            _hostKeyPrompt.value = HostKeyPrompt.NewHost(result.entry, deferred)
                            if (!deferred.await()) {
                                client.disconnect()
                                throw Exception("Host key rejected by user")
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
                                client.disconnect()
                                throw Exception("Host key change rejected by user")
                            }
                            hostKeyVerifier.accept(result.new)
                        }
                    }

                    val sshSessionMgr = resolveSessionManager(profile)
                    val cmdOverride = preferencesRepository.sessionCommandOverride.first()
                    sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr, cmdOverride)
                    sshSessionMgr
                }

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
                        _sessionSelection.value = SessionSelection(
                            sessionId = sessionId,
                            profileId = profile.id,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                            manager = sshSessionMgr,
                        )
                        _connectingProfileId.value = null
                        return@launch // UI will call onSessionSelected() to continue
                    }
                }

                // No existing sessions or no session manager — proceed directly
                finishConnect(sessionId, profile.id)
                if (rememberPassword != null) {
                    when {
                        rememberPassword && password.isNotBlank() ->
                            repository.save(profile.copy(sshPassword = password))
                        !rememberPassword && profile.sshPassword != null ->
                            repository.save(profile.copy(sshPassword = null))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectSsh failed for ${profile.label}: ${e.message}", e)
                sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
                sshSessionManager.removeSession(sessionId)
                // Clean up auto-created jump session if the final host failed
                autoCreatedJumpSessionId?.let { jid ->
                    Log.d(TAG, "Cleaning up auto-created jump session $jid")
                    sshSessionManager.removeSession(jid)
                }
                val msg = e.message ?: ""
                val isAuthError = keyOnly && (
                    msg.contains("Auth fail", ignoreCase = true) ||
                    msg.contains("Auth cancel", ignoreCase = true) ||
                    msg.contains("authentication", ignoreCase = true) ||
                    msg.contains("publickey", ignoreCase = true)
                )
                if (isAuthError) {
                    Log.d(TAG, "Auth failed, showing password fallback for ${profile.label}")
                    _passwordFallback.value = profile
                } else if (keyOnly && msg.isBlank()) {
                    // Unknown error with key auth — try password
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
                    )
                }

                repository.markConnected(profile.id)
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

    private fun connectEternalTerminal(profile: ConnectionProfile, password: String, keyOnly: Boolean) {
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val sessionId = etSessionManager.registerSession(
                profileId = profile.id,
                label = profile.label,
            )

            try {
                // Phase 1: SSH bootstrap — connect, verify host key
                val client = withContext(Dispatchers.IO) {
                    val authMethod = resolveAuthMethod(profile, password)
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = profile.username,
                        authMethod = authMethod,
                        sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    )

                    val sshClient = SshClient()

                    // Jump host support
                    val jumpProfileId = profile.jumpProfileId
                    val proxy = if (jumpProfileId != null) {
                        val (jid, _) = connectJumpHost(jumpProfileId, password)
                        sshSessionManager.createProxyJump(jid)
                    } else null

                    val hostKeyEntry = sshClient.connect(config, proxy = proxy)

                    // TOFU host key verification
                    when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                        is HostKeyResult.Trusted -> {}
                        is HostKeyResult.NewHost -> {
                            val deferred = CompletableDeferred<Boolean>()
                            _hostKeyPrompt.value = HostKeyPrompt.NewHost(result.entry, deferred)
                            if (!deferred.await()) {
                                sshClient.disconnect()
                                throw Exception("Host key rejected by user")
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
                                sshClient.disconnect()
                                throw Exception("Host key change rejected by user")
                            }
                            hostKeyVerifier.accept(result.new)
                        }
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
                finishEtConnect(sessionId, profile, client, smgr, null)
            } catch (e: Exception) {
                Log.e(TAG, "connectEternalTerminal failed for ${profile.label}: ${e.message}", e)
                etPendingClient?.disconnect()
                etPendingClient = null
                etPendingProfile = null
                etSessionManager.updateStatus(sessionId, EtSessionManager.SessionState.Status.ERROR)
                etSessionManager.removeSession(sessionId)
                val msg = e.message ?: ""
                val isAuthError = keyOnly && (
                    msg.contains("Auth fail", ignoreCase = true) ||
                        msg.contains("Auth cancel", ignoreCase = true) ||
                        msg.contains("authentication", ignoreCase = true) ||
                        msg.contains("publickey", ignoreCase = true)
                    )
                if (isAuthError || (keyOnly && msg.isBlank())) {
                    _passwordFallback.value = profile
                } else {
                    _error.value = msg.ifBlank { "Eternal Terminal connection failed" }
                }
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    private fun connectMosh(profile: ConnectionProfile, password: String, keyOnly: Boolean) {
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val sessionId = moshSessionManager.registerSession(
                profileId = profile.id,
                label = profile.label,
            )

            try {
                // Phase 1: SSH bootstrap — connect, resolve session manager, list sessions
                val client = withContext(Dispatchers.IO) {
                    val authMethod = resolveAuthMethod(profile, password)
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = profile.username,
                        authMethod = authMethod,
                        sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    )

                    val sshClient = SshClient()

                    // Jump host support
                    val jumpProfileId = profile.jumpProfileId
                    val proxy = if (jumpProfileId != null) {
                        val (jid, _) = connectJumpHost(jumpProfileId, password)
                        sshSessionManager.createProxyJump(jid)
                    } else null

                    val hostKeyEntry = sshClient.connect(config, proxy = proxy)

                    // TOFU host key verification
                    when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                        is HostKeyResult.Trusted -> {}
                        is HostKeyResult.NewHost -> {
                            val deferred = CompletableDeferred<Boolean>()
                            _hostKeyPrompt.value = HostKeyPrompt.NewHost(result.entry, deferred)
                            if (!deferred.await()) {
                                sshClient.disconnect()
                                throw Exception("Host key rejected by user")
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
                                sshClient.disconnect()
                                throw Exception("Host key change rejected by user")
                            }
                            hostKeyVerifier.accept(result.new)
                        }
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
                finishMoshConnect(sessionId, profile.id, profile.host, client, smgr, null)
            } catch (e: Exception) {
                Log.e(TAG, "connectMosh failed for ${profile.label}: ${e.message}", e)
                moshPendingClient?.disconnect()
                moshPendingClient = null
                moshPendingHost = null
                moshSessionManager.updateStatus(sessionId, MoshSessionManager.SessionState.Status.ERROR)
                moshSessionManager.removeSession(sessionId)
                val msg = e.message ?: ""
                val isAuthError = keyOnly && (
                    msg.contains("Auth fail", ignoreCase = true) ||
                        msg.contains("Auth cancel", ignoreCase = true) ||
                        msg.contains("authentication", ignoreCase = true) ||
                        msg.contains("publickey", ignoreCase = true)
                    )
                if (isAuthError || (keyOnly && msg.isBlank())) {
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
    fun onSessionSelected(sessionId: String, sessionName: String?) {
        val sel = _sessionSelection.value
        _sessionSelection.value = null

        if (sel?.transportType == "MOSH") {
            // Mosh path: finish mosh connection with chosen session name
            val client = moshPendingClient
            val serverHost = moshPendingHost ?: ""
            moshPendingClient = null
            moshPendingHost = null
            if (client == null) {
                _error.value = "Mosh SSH connection lost"
                moshSessionManager.removeSession(sessionId)
                return
            }
            val profileId = sel.profileId
            viewModelScope.launch {
                _connectingProfileId.value = profileId
                try {
                    finishMoshConnect(sessionId, profileId, serverHost, client, sel.manager, sessionName)
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
            etPendingClient = null
            etPendingProfile = null
            if (client == null || profile == null) {
                _error.value = "ET SSH connection lost"
                etSessionManager.removeSession(sessionId)
                return
            }
            val profileId = sel.profileId
            viewModelScope.launch {
                _connectingProfileId.value = profileId
                try {
                    finishEtConnect(sessionId, profile, client, sel.manager, sessionName)
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

    /**
     * Connect to a jump host profile, reusing an existing connected session if available.
     * Returns the sessionId of the connected jump host session.
     */
    /**
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
            val hostKeyEntry = jumpClient.connect(config)
            Log.d(TAG, "Jump host SSH connected, verifying host key...")

            // TOFU for jump host
            when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                is HostKeyResult.Trusted -> {}
                is HostKeyResult.NewHost -> {
                    val deferred = CompletableDeferred<Boolean>()
                    _hostKeyPrompt.value = HostKeyPrompt.NewHost(result.entry, deferred)
                    if (!deferred.await()) {
                        jumpClient.disconnect()
                        throw Exception("Jump host key rejected by user")
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
                        jumpClient.disconnect()
                        throw Exception("Jump host key change rejected by user")
                    }
                    hostKeyVerifier.accept(result.new)
                }
            }

            sshSessionManager.storeConnectionConfig(jumpSessionId, config, SessionManager.NONE)
        }

        sshSessionManager.updateStatus(jumpSessionId, SshSessionManager.SessionState.Status.CONNECTED)
        Log.d(TAG, "Jump host connected: ${jumpProfile.label} ($jumpSessionId), isConnected=${jumpClient.isConnected}")
        return jumpSessionId to false
    }

    private suspend fun finishConnect(sessionId: String, profileId: String) {
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
        startForegroundServiceIfNeeded()
        _navigateToTerminal.value = profileId
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
    ) {
        val moshConnect = withContext(Dispatchers.IO) {
            val moshCmd = "mosh-server new -s -c 256 -l LANG=en_US.UTF-8"
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
        if (smCmd != null) {
            val rawName = chosenSessionName
                ?: moshSessionManager.sessions.value[sessionId]?.label
                ?: sessionId.take(8)
            val sanitized = rawName.replace(Regex("[^A-Za-z0-9._-]"), "-")
            moshSessionManager.setInitialCommand(sessionId, smCmd(sanitized))
        }

        withContext(Dispatchers.IO) {
            moshSessionManager.connectSession(
                sessionId = sessionId,
                serverIp = serverIp,
                moshPort = moshPort,
                moshKey = moshKey,
                cols = 80,
                rows = 24,
                sshClient = client,
            )
        }

        repository.markConnected(profileId)
        startForegroundServiceIfNeeded()
        _navigateToTerminal.value = profileId
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
        if (smCmd != null) {
            val rawName = chosenSessionName
                ?: etSessionManager.sessions.value[sessionId]?.label
                ?: sessionId.take(8)
            val sanitized = rawName.replace(Regex("[^A-Za-z0-9._-]"), "-")
            etSessionManager.setInitialCommand(sessionId, smCmd(sanitized))
        }

        withContext(Dispatchers.IO) {
            etSessionManager.connectSession(
                sessionId = sessionId,
                serverHost = serverHost,
                etPort = etPort,
                clientId = etClientId,
                passkey = etPasskey,
                sshClient = client,
            )
        }

        repository.markConnected(profile.id)
        startForegroundServiceIfNeeded()
        _navigateToTerminal.value = profile.id
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
                return ConnectionConfig.AuthMethod.PrivateKey(
                    keyBytes = rawKeyToPem(keyBytes, key.keyType),
                    passphrase = password,
                )
            }
        }

        // No explicit key but keys are available — try all keys
        if (password.isEmpty()) {
            val keys = sshKeyRepository.getAllDecrypted()
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
     * Convert raw private key bytes to PEM format that JSch can parse.
     * Ed25519 keys are raw 32-byte seeds that need a PKCS#8 DER envelope.
     * RSA/ECDSA keys from JCA are already PKCS#8 DER encoded.
     */
    private fun rawKeyToPem(rawBytes: ByteArray, keyType: String): ByteArray {
        // Imported keys are stored as PEM or OpenSSH format — pass through to JSch
        if (rawBytes.size > 5 && rawBytes[0] == '-'.code.toByte()) {
            return rawBytes
        }

        // Generated keys: raw bytes → PKCS#8 PEM
        val pkcs8Der = if (keyType.contains("Ed25519", ignoreCase = true)) {
            // PKCS#8 DER prefix for Ed25519: SEQUENCE { INTEGER 0, SEQUENCE { OID 1.3.101.112 }, OCTET STRING { OCTET STRING { <32 bytes> } } }
            val prefix = byteArrayOf(
                0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
                0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
            )
            prefix + rawBytes
        } else {
            // RSA/ECDSA: keyPair.private.encoded already returns PKCS#8 DER
            rawBytes
        }
        val b64 = Base64.getEncoder().encodeToString(pkcs8Der)
        val pem = buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            for (i in b64.indices step 64) {
                append(b64, i, minOf(i + 64, b64.length))
                append('\n')
            }
            append("-----END PRIVATE KEY-----\n")
        }
        return pem.toByteArray()
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
                    sshSessionManager.storeConnectionConfig(session.sessionId, config, sshSessionMgr, cmdOverride)
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
        },
        bindAddress = bindAddress,
        bindPort = bindPort,
        targetHost = targetHost,
        targetPort = targetPort,
    )

    fun disconnect(profileId: String) {
        sshSessionManager.removeAllSessionsForProfile(profileId)
        reticulumSessionManager.removeAllSessionsForProfile(profileId)
        moshSessionManager.removeAllSessionsForProfile(profileId)
        etSessionManager.removeAllSessionsForProfile(profileId)
        updateServiceNotification()
    }

    private fun updateServiceNotification() {
        if (sshSessionManager.hasActiveSessions || reticulumSessionManager.activeSessions.isNotEmpty() ||
            moshSessionManager.activeSessions.isNotEmpty() || etSessionManager.activeSessions.isNotEmpty()) {
            // Re-start the service to refresh the notification count
            startForegroundServiceIfNeeded()
        } else {
            // No more sessions — stop the foreground service
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

    private val _deploySuccess = MutableStateFlow(false)
    val deploySuccess: StateFlow<Boolean> = _deploySuccess.asStateFlow()

    fun dismissDeploySuccess() {
        _deploySuccess.value = false
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
                val hostKeyEntry = client.connect(config)

                // TOFU host key verification for deploy
                when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> { /* key matches — continue */ }
                    is HostKeyResult.NewHost -> {
                        val deferred = CompletableDeferred<Boolean>()
                        _hostKeyPrompt.value = HostKeyPrompt.NewHost(result.entry, deferred)
                        if (!deferred.await()) {
                            client.disconnect()
                            _error.value = "Host key rejected"
                            return@launch
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
                            client.disconnect()
                            _error.value = "Host key change rejected"
                            return@launch
                        }
                        hostKeyVerifier.accept(result.new)
                    }
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
}
