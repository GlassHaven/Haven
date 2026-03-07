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
import sh.haven.core.reticulum.ReticulumBridge
import sh.haven.core.reticulum.ReticulumSessionManager
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
    private val reticulumBridge: ReticulumBridge,
    private val sshKeyRepository: SshKeyRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val hostKeyVerifier: HostKeyVerifier,
) : ViewModel() {

    val connections: StateFlow<List<ConnectionProfile>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sshKeys: StateFlow<List<SshKey>> = sshKeyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessions: StateFlow<Map<String, SshSessionManager.SessionState>> = sshSessionManager.sessions

    /** Derive profile-level statuses for the connections list UI (merges SSH + Reticulum). */
    val profileStatuses: StateFlow<Map<String, ProfileStatus>> =
        combine(
            sshSessionManager.sessions,
            reticulumSessionManager.sessions,
        ) { sshMap, rnsMap ->
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
                // If both exist, prefer the higher-priority one
                val existing = result[profileId]
                if (existing == null || rnsStatus.ordinal < existing.ordinal) {
                    result[profileId] = rnsStatus
                }
            }

            result.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The profileId currently being connected (for spinner in UI). */
    private val _connectingProfileId = MutableStateFlow<String?>(null)
    val connectingProfileId: StateFlow<String?> = _connectingProfileId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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

    /** When non-null, the UI should show a session picker dialog. */
    data class SessionSelection(
        val sessionId: String,
        val profileId: String,
        val managerLabel: String,
        val sessionNames: List<String>,
        val manager: SessionManager = SessionManager.NONE,
    )

    private val _sessionSelection = MutableStateFlow<SessionSelection?>(null)
    val sessionSelection: StateFlow<SessionSelection?> = _sessionSelection.asStateFlow()

    fun onNavigated() {
        _navigateToTerminal.value = null
    }

    data class DiscoveredDestination(val hash: String, val hops: Int)

    private val _discoveredDestinations = MutableStateFlow<List<DiscoveredDestination>>(emptyList())
    val discoveredDestinations: StateFlow<List<DiscoveredDestination>> = _discoveredDestinations.asStateFlow()

    private val networkDiscovery = NetworkDiscovery(appContext)
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = networkDiscovery.hosts

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
            networkDiscovery.scanSubnet()
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

    fun connect(profile: ConnectionProfile, password: String, keyOnly: Boolean = false) {
        if (profile.isReticulum) {
            connectReticulum(profile)
            return
        }
        connectSsh(profile, password, keyOnly)
    }

    private fun connectSsh(profile: ConnectionProfile, password: String, keyOnly: Boolean) {
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val client = SshClient()
            val sessionId = sshSessionManager.registerSession(profile.id, profile.label, client)

            try {
                val sshSessionMgr = withContext(Dispatchers.IO) {
                    val authMethod = resolveAuthMethod(profile, password)
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = profile.username,
                        authMethod = authMethod,
                    )
                    val hostKeyEntry = client.connect(config)

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

                    val prefSessionMgr = preferencesRepository.sessionManager.first()
                    val sshSessionMgr = prefSessionMgr.toSshSessionManager()
                    sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr)
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
            } catch (e: Exception) {
                sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
                sshSessionManager.removeSession(sessionId)
                if (keyOnly) {
                    // Key auth failed — fall back to password dialog
                    _passwordFallback.value = profile
                } else {
                    _error.value = e.message ?: "Connection failed"
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

    /**
     * Called from the session picker dialog when user selects a session.
     * @param sessionName The name to attach to, or null to create a new session.
     */
    fun onSessionSelected(sessionId: String, sessionName: String?) {
        val sel = _sessionSelection.value
        _sessionSelection.value = null
        val profileId = sel?.profileId ?: sshSessionManager.getSession(sessionId)?.profileId ?: return
        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                if (sessionName != null) {
                    sshSessionManager.setChosenSessionName(sessionId, sessionName)
                }
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
        val session = sshSessionManager.getSession(sel.sessionId) ?: return

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    session.client.execCommand(killCmd)
                }
                // Refresh the session list
                val listCmd = sel.manager.listCommand ?: return@launch
                val updated = withContext(Dispatchers.IO) {
                    try {
                        val result = session.client.execCommand(listCmd)
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
        val session = sshSessionManager.getSession(sel.sessionId) ?: return

        viewModelScope.launch {
            try {
                val renameResult = withContext(Dispatchers.IO) {
                    session.client.execCommand(renameCmd)
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
                        val result = session.client.execCommand(listCmd)
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

    fun dismissSessionPicker() {
        val sel = _sessionSelection.value ?: return
        _sessionSelection.value = null
        sshSessionManager.removeSession(sel.sessionId)
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
            val key = sshKeyRepository.getById(keyId)
            if (key != null) {
                return ConnectionConfig.AuthMethod.PrivateKey(
                    keyBytes = rawKeyToPem(key.privateKeyBytes, key.keyType),
                    passphrase = password,
                )
            }
        }

        // No explicit key but keys are available — try first key when password is empty
        if (password.isEmpty()) {
            val keys = sshKeyRepository.getAll()
            if (keys.isNotEmpty()) {
                val key = keys.first()
                return ConnectionConfig.AuthMethod.PrivateKey(
                    keyBytes = rawKeyToPem(key.privateKeyBytes, key.keyType),
                    passphrase = "",
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
    }

    fun dismissError() {
        _error.value = null
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
}
