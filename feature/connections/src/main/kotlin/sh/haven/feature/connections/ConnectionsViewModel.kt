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
import kotlinx.coroutines.withTimeoutOrNull
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
import sh.haven.core.mail.MailConnectParams
import sh.haven.core.mail.MailEngine
import sh.haven.core.mail.MailException
import sh.haven.core.mail.MailSessionManager
import sh.haven.core.security.Totp
import sh.haven.core.reticulum.DiscoveredDestination
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.core.knock.KnockResult
import sh.haven.core.knock.KnockSequence
import sh.haven.core.knock.PortKnocker
import sh.haven.core.spa.SpaConfig
import sh.haven.core.spa.SpaResult
import sh.haven.feature.connections.R
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.stepca.CertRenewalGate
import android.util.Log
import sh.haven.core.ssh.HavenProxy
import java.io.File
import javax.inject.Inject

private const val TAG = "ConnectionsVM"

/**
 * How long a host-key accept/reject prompt waits for an on-screen tap before
 * giving up. Without a bound, a prompt raised while Connections isn't the
 * composed/visible screen (a background reconnect, an MCP-triggered
 * connect_profile) blocks the connect forever with no error and no further
 * log output — reproduced live via a USB-drive-VM profile hanging 5+ minutes.
 */
private const val HOST_KEY_PROMPT_TIMEOUT_MS = 90_000L

/**
 * Port the MCP server binds (first free in 8730..8739). The reverse-tunnel
 * rule maps this 1:1 so an MCP client on the SSH server reaches Haven at
 * `http://127.0.0.1:8730/mcp`.
 */
private const val MCP_REVERSE_TUNNEL_PORT = 8730

/** Unified connection status that maps both SSH and Reticulum states. */
enum class ProfileStatus { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }

/**
 * How a connection exposes Haven's MCP server to its sessions. INTERACTIVE = a
 * per-connection reverse-tunnel rule (rides the user's own sessions); HEADLESS
 * = the designated always-on endpoint (a dedicated self-healing tunnel).
 * Absence from the [ConnectionsViewModel.mcpExposure] map = not exposed.
 */
enum class McpExposureKind { INTERACTIVE, HEADLESS }

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
    private val agentActivityHolder: sh.haven.core.data.agent.AgentActivityHolder,
    private val connectionGroupDao: sh.haven.core.data.db.ConnectionGroupDao,
    private val sshIdentityRepository: sh.haven.core.data.repository.SshIdentityRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val sshSessionAttacher: sh.haven.core.ssh.SshSessionAttacher,
    private val reticulumSessionManager: ReticulumSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val reticulumTransport: ReticulumTransport,
    private val smbSessionManager: SmbSessionManager,
    private val rcloneSessionManager: RcloneSessionManager,
    private val rcloneClient: RcloneClient,
    private val mailSessionManager: MailSessionManager,
    private val fidoAuthenticator: FidoAuthenticator,
    private val localSessionManager: LocalSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val sshKeyRepository: SshKeyRepository,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
    private val tunnelResolver: sh.haven.core.tunnel.TunnelResolver,
    private val portKnocker: PortKnocker,
    private val spaSender: sh.haven.core.spa.SpaSender,
    private val tunnelConfigRepository: sh.haven.core.data.repository.TunnelConfigRepository,
    private val certRenewalGate: CertRenewalGate,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val userMessageBus: sh.haven.core.data.message.UserMessageBus,
    private val usbipForwarder: sh.haven.feature.connections.usb.UsbipConnectionForwarder,
    private val biometricGate: sh.haven.core.data.keystore.BiometricGate,
    private val pendingAuthPromptHolder: sh.haven.core.data.agent.PendingAuthPromptHolder,
    private val sessionSelectionHolder: sh.haven.core.data.agent.SessionSelectionHolder,
    private val connectionPreflight: sh.haven.core.data.repository.ConnectionPreflight,
) : ViewModel() {

    /**
     * Gate revealing a stored credential behind a biometric/device-credential
     * prompt (#274). Returns true only on an explicit ALLOW; DENY, timeout, or
     * no-foreground-Activity (UNAVAILABLE) keep the value masked. Reuses the
     * key-unlock [sh.haven.core.data.keystore.BiometricGate], whose 30s
     * session-unlock window means one prompt per edit session.
     */
    suspend fun authToRevealPassword(title: String, subtitle: String): Boolean =
        biometricGate.request(label = title, detail = subtitle) ==
            sh.haven.core.data.keystore.BiometricGate.Decision.ALLOW

    /** Live USB/IP auto-forwards, keyed by sessionId (Slice 1). Torn down on disconnect. */
    private val usbForwardHandles =
        java.util.concurrent.ConcurrentHashMap<String, sh.haven.feature.connections.usb.UsbipConnectionForwarder.Handle>()

    /**
     * Installed distros `(id, label)` for the LOCAL profile editor's
     * "open in" distro picker. Empty when no rootfs is installed.
     */
    fun installedLocalDistros(): List<Pair<String, String>> =
        localSessionManager.installedDistros()

    /** Attached phone USB devices `(vidPid, label)` for the SSH USB-forward picker. */
    fun availableUsbDevices(): List<Pair<String, String>> =
        usbipForwarder.availableDevices()

    /**
     * Re-exposed from [CertRenewalGate] so the Connections screen can
     * render a status banner ("Renewing cert via step-ca…") while a
     * connect-time renewal is in flight. (#133 phase 2b)
     */
    val certRenewing: StateFlow<CertRenewalGate.Renewing?> = certRenewalGate.renewing

    /** Verbose SSH log captured during connect, keyed by sessionId. Consumed by finishConnect. */
    private val pendingVerboseLogs = mutableMapOf<String, String>()

    init {
        // Agent-driven connect: MCP `connect_profile` tool posts here.
        // Look up the profile and dispatch through the unified connect()
        // entry so route-through, stored passwords, and key auth all
        // apply identically to a UI tap.
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                if (command is sh.haven.core.data.agent.AgentUiCommand.ConnectProfile) {
                    val profile = repository.getById(command.profileId)
                    if (profile != null) {
                        connect(profile, password = profile.sshPassword.orEmpty(), sessionName = command.sessionName)
                    } else {
                        Log.w(TAG, "ConnectProfile: profile ${command.profileId} not found")
                    }
                } else if (command is sh.haven.core.data.agent.AgentUiCommand.AnswerAuthPrompt) {
                    // MCP answer_auth_prompt: supply the secret to the live
                    // password/passphrase fallback and re-drive the connect.
                    answerPasswordFallback(
                        command.password,
                        username = command.username,
                        rememberPassword = command.rememberPassword,
                    )
                } else if (command is sh.haven.core.data.agent.AgentUiCommand.AnswerSessionSelection) {
                    // MCP answer_session_picker: choose a session (or null =
                    // create new) on the live picker and re-drive the attach,
                    // exactly as a human tap on the picker would.
                    if (_sessionSelection.value != null) {
                        onSessionSelected(command.sessionId, command.sessionName)
                    }
                } else if (command is sh.haven.core.data.agent.AgentUiCommand.ConnectFromDeepLink) {
                    // haven://connect matched a saved profile (#305). Show a
                    // confirm sheet first — a BROWSABLE link can be fired by a
                    // web page, and confirming blocks a drive-by connect that
                    // would use this profile's stored credentials.
                    val profile = repository.getById(command.profileId)
                    if (profile != null) {
                        _connectConfirm.value = ConnectConfirm(profile, command.sessionName)
                    } else {
                        Log.w(TAG, "ConnectFromDeepLink: profile ${command.profileId} not found")
                    }
                } else if (command is sh.haven.core.data.agent.AgentUiCommand.PrefillNewConnection) {
                    // haven://connect with no saved match (#305): surface the
                    // New-Connection editor pre-filled for the user to review,
                    // add auth, and save.
                    _prefillNewConnection.value = command
                }
            }
        }

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

    /** Reusable SSH identities (#360), for the connection editor's picker. */
    val identities: StateFlow<List<sh.haven.core.data.db.entities.SshIdentity>> =
        sshIdentityRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totpSecrets: StateFlow<List<sh.haven.core.data.db.entities.TotpSecret>> =
        totpSecretRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Shared (non-embedded) tunnel configs the route-through dropdown
     * surfaces in the SSH profile editor. Cloudflare Tunnels embedded on
     * a single SSH profile (GH #154, `ownerProfileId != null`) are
     * excluded from this list — they're surfaced inline on the owning
     * profile instead.
     */
    val tunnelConfigs: StateFlow<List<sh.haven.core.data.db.entities.TunnelConfig>> =
        tunnelConfigRepository.observeStandalone()
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

    /**
     * Which connections expose Haven's MCP to their sessions, and how:
     * INTERACTIVE = a per-connection reverse-tunnel rule; HEADLESS = the
     * designated always-on endpoint ([UserPreferencesRepository.mcpTunnelEndpointProfileId]).
     * Absence = not exposed. Drives the connections-row MCP badge so it's clear
     * which connection serves `:8730` to all of its sessions.
     */
    /** `profileId → last-agent-activity-millis`, for the per-connection MCP indicator glow. */
    val agentActiveProfiles: StateFlow<Map<String, Long>> = agentActivityHolder.activeProfiles

    /** Toggle whether the MCP agent may operate on [profileId]. */
    fun toggleMcpEnabled(profileId: String, enabled: Boolean) {
        viewModelScope.launch { repository.updateMcpEnabled(profileId, enabled) }
    }

    val mcpExposure: StateFlow<Map<String, McpExposureKind>> =
        combine(
            portForwardRepository.observeAll(),
            preferencesRepository.mcpTunnelEndpointProfileId,
        ) { rules, endpointId ->
            val map = mutableMapOf<String, McpExposureKind>()
            rules.filter { it.isMcpReverseTunnel() }
                .forEach { map[it.profileId] = McpExposureKind.INTERACTIVE }
            endpointId?.let { map[it] = McpExposureKind.HEADLESS }
            map.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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
            desktopSessionRegistry.statuses,
        ) { base, extra, deskMap ->
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

            // Remote-desktop (VNC/RDP) tabs have no transport session of their
            // own — they live as Desktop tabs in DesktopViewModel — so their
            // connections-list row would otherwise show no status. Reflect the
            // tab's real live state (DesktopSessionRegistry, set at the actual
            // handshake) onto the profile row: connecting while it dials,
            // connected once the desktop is up, cleared on teardown (#121).
            deskMap.forEach { (profileId, status) ->
                val deskStatus = when (status) {
                    sh.haven.core.data.desktop.DesktopStatus.CONNECTED -> ProfileStatus.CONNECTED
                    sh.haven.core.data.desktop.DesktopStatus.CONNECTING -> ProfileStatus.CONNECTING
                    sh.haven.core.data.desktop.DesktopStatus.ERROR -> ProfileStatus.ERROR
                }
                val existing = result[profileId]
                if (existing == null || deskStatus.ordinal < existing.ordinal) {
                    result[profileId] = deskStatus
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

    /** Cancel an in-flight FIDO key wait/touch (the dialog's Cancel button). */
    fun cancelFido() = fidoAuthenticator.cancelPending()

    private val _showMoshSetupGuide = MutableStateFlow(false)
    val showMoshSetupGuide: StateFlow<Boolean> = _showMoshSetupGuide.asStateFlow()

    private val _showMoshClientMissing = MutableStateFlow(false)
    val showMoshClientMissing: StateFlow<Boolean> = _showMoshClientMissing.asStateFlow()

    fun dismissMoshSetupGuide() { _showMoshSetupGuide.value = false }
    fun dismissMoshClientMissing() { _showMoshClientMissing.value = false }

    /** When non-null, key auth failed and the UI should show a password dialog as fallback. */
    private val _passwordFallback = MutableStateFlow<ConnectionProfile?>(null)
    val passwordFallback: StateFlow<ConnectionProfile?> = _passwordFallback.asStateFlow()

    // Mirror the password/passphrase fallback dialog to a process-wide holder so
    // the MCP agent can observe it (get_pending_auth_prompt) and answer it
    // (answer_auth_prompt) without a human tap. This init block is declared
    // AFTER _passwordFallback so the collector never captures the field before
    // it's initialised (Main.immediate runs init collectors synchronously).
    init {
        viewModelScope.launch {
            _passwordFallback.collect { profile ->
                pendingAuthPromptHolder.set(
                    profile?.let {
                        val encrypted = it.keyId?.let { id ->
                            sshKeyRepository.getById(id)?.isEncrypted
                        } ?: false
                        sh.haven.core.data.agent.PendingAuthPrompt(
                            profileId = it.id,
                            label = it.label,
                            requiresPassphrase = encrypted,
                        )
                    }
                )
            }
        }
    }

    /**
     * VNC/RDP/SMB-over-SSH-tunnel paths used to fail silently when the
     * jump host had no saved password — they auto-connected with empty
     * credentials and the resulting "Auth cancel for methods…" error
     * went unsurfaced (#121a, KoriKraut). We now detect "no saved auth"
     * up front, surface a password prompt, and (after submit) replay
     * the dependent connect against the now-authed jump host. This
     * holds the dependent's profile so the password dialog's onConnect
     * handler knows to dispatch back here instead of running the
     * generic SSH-login path.
     */
    private val _pendingTunnelDependent = MutableStateFlow<ConnectionProfile?>(null)
    val pendingTunnelDependent: StateFlow<ConnectionProfile?> = _pendingTunnelDependent.asStateFlow()

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
     * accept/reject on first contact or key change. Throws if rejected, or if
     * nobody answers within [HOST_KEY_PROMPT_TIMEOUT_MS] — a prompt raised
     * while Connections isn't the visible screen (background reconnect,
     * MCP-triggered connect_profile) would otherwise await a tap that can
     * never come, hanging the connect forever with no error and no further
     * log output (reproduced live).
     *
     * [autoAccept] skips the prompt (and its timeout) entirely, accepting the
     * key immediately — only correct for profiles Haven itself originates
     * *and* owns both ends of (USB-drive-VM loopback profiles): the guest's
     * host key is generated fresh by Haven's own boot script every VM boot,
     * over a loopback-only connection, so there is no third party to
     * trust-on-first-use against, and a fresh key every boot would otherwise
     * re-prompt on every single drive reopen. Every other profile keeps the
     * normal interactive prompt — callers must not set this for anything else
     * without deliberately deciding to trade away that check.
     *
     * Shared by every interactive connect path so that they behave the same
     * way on fresh contact, key change, and (post-fix) auth-failure-after-KEX.
     */
    private suspend fun runTofuVerification(
        entry: KnownHostEntry,
        clientToDisconnectOnReject: SshClient? = null,
        rejectedOnNewHostMessage: String = "Host key rejected by user",
        rejectedOnChangeMessage: String = "Host key change rejected by user",
        autoAccept: Boolean = false,
    ) {
        when (val result = hostKeyVerifier.verify(entry)) {
            is HostKeyResult.Trusted -> return
            is HostKeyResult.NewHost -> {
                if (autoAccept) {
                    Log.d(TAG, "Auto-accepting new host key — Haven-owned USB-drive-VM loopback profile")
                    hostKeyVerifier.accept(result.entry)
                    return
                }
                val deferred = CompletableDeferred<Boolean>()
                _hostKeyPrompt.value = HostKeyPrompt.NewHost(result.entry, deferred)
                if (!awaitHostKeyDecision(deferred)) {
                    clientToDisconnectOnReject?.disconnect()
                    throw Exception(rejectedOnNewHostMessage)
                }
                hostKeyVerifier.accept(result.entry)
            }
            is HostKeyResult.KeyChanged -> {
                if (autoAccept) {
                    Log.d(TAG, "Auto-accepting changed host key — Haven-owned USB-drive-VM loopback profile")
                    hostKeyVerifier.accept(result.new)
                    return
                }
                val deferred = CompletableDeferred<Boolean>()
                _hostKeyPrompt.value = HostKeyPrompt.KeyChanged(
                    oldFingerprint = result.old.fingerprint,
                    entry = result.new,
                    deferred = deferred,
                )
                if (!awaitHostKeyDecision(deferred)) {
                    clientToDisconnectOnReject?.disconnect()
                    throw Exception(rejectedOnChangeMessage)
                }
                hostKeyVerifier.accept(result.new)
            }
        }
    }

    /** Awaits [deferred] with [HOST_KEY_PROMPT_TIMEOUT_MS]; a timeout clears the stale prompt and counts as reject. */
    private suspend fun awaitHostKeyDecision(deferred: CompletableDeferred<Boolean>): Boolean {
        val decided = withTimeoutOrNull(HOST_KEY_PROMPT_TIMEOUT_MS) { deferred.await() }
        if (decided == null) {
            Log.w(TAG, "Host-key prompt timed out after ${HOST_KEY_PROMPT_TIMEOUT_MS}ms with no on-screen response — treating as rejected")
            _hostKeyPrompt.value = null
        }
        return decided ?: false
    }

    /** Emitted once after a successful connect to trigger navigation to terminal (profileId). */
    private val _navigateToTerminal = MutableStateFlow<String?>(null)
    val navigateToTerminal: StateFlow<String?> = _navigateToTerminal.asStateFlow()

    /** Emitted to navigate back to Connections screen after a post-connect failure. */
    private val _navigateToConnections = MutableStateFlow(false)
    val navigateToConnections: StateFlow<Boolean> = _navigateToConnections.asStateFlow()

    /** Emitted to navigate to VNC screen with connection params. */
    data class VncNavigation(
        val host: String,
        val port: Int,
        val password: String?,
        val username: String? = null,
        val sshForward: Boolean = false,
        val sshProfileId: String? = null,
        val sshSessionId: String? = null,
        val profileId: String? = null,
        val colorDepth: String = "BPP_24_TRUE",
        /** Optional knock sequence; honoured only on the direct path
         *  (sshForward=false) since SSH-tunneled connects can't reach
         *  the remote firewall from this device. */
        val portKnockSequence: String? = null,
        val portKnockDelayMs: Int = 100,
    )
    private val _navigateToVnc = MutableStateFlow<VncNavigation?>(null)
    val navigateToVnc: StateFlow<VncNavigation?> = _navigateToVnc.asStateFlow()

    /** Emitted to navigate to the native Wayland desktop view. */
    private val _navigateToWayland = MutableStateFlow(false)
    val navigateToWayland: StateFlow<Boolean> = _navigateToWayland.asStateFlow()

    /** Emitted to navigate to RDP screen with connection params. */
    data class RdpNavigation(val host: String, val port: Int, val username: String, val password: String, val domain: String, val sshForward: Boolean = false, val sshProfileId: String? = null, val sshSessionId: String? = null, val profileId: String? = null, val useNla: Boolean = true, val colorDepth: Int = 32, val portKnockSequence: String? = null, val portKnockDelayMs: Int = 100)
    private val _navigateToRdp = MutableStateFlow<RdpNavigation?>(null)
    val navigateToRdp: StateFlow<RdpNavigation?> = _navigateToRdp.asStateFlow()

    /** Emitted to navigate to the SPICE desktop with connection params (#286). */
    data class SpiceNavigation(
        val host: String,
        val port: Int,
        val password: String?,
        val sshForward: Boolean = false,
        val sshProfileId: String? = null,
        val sshSessionId: String? = null,
        val profileId: String? = null,
    )
    private val _navigateToSpice = MutableStateFlow<SpiceNavigation?>(null)
    val navigateToSpice: StateFlow<SpiceNavigation?> = _navigateToSpice.asStateFlow()

    /** Emitted to navigate to Files tab for an SMB connection. */
    private val _navigateToSmb = MutableStateFlow<String?>(null)
    val navigateToSmb: StateFlow<String?> = _navigateToSmb.asStateFlow()

    private val _navigateToRclone = MutableStateFlow<String?>(null)
    val navigateToRclone: StateFlow<String?> = _navigateToRclone.asStateFlow()

    /** Emitted to navigate to the Mail tab for an EMAIL connection. */
    private val _navigateToEmail = MutableStateFlow<String?>(null)
    val navigateToEmail: StateFlow<String?> = _navigateToEmail.asStateFlow()

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
        /** Pre-filled name for the "Create new session" text field. (#112) */
        val suggestedNewName: String = "",
    )

    private val _sessionSelection = MutableStateFlow<SessionSelection?>(null)
    val sessionSelection: StateFlow<SessionSelection?> = _sessionSelection.asStateFlow()

    // --- haven://connect deep link (#305) ---

    /** A matched saved profile awaiting the user's confirm before connecting. */
    data class ConnectConfirm(val profile: ConnectionProfile, val sessionName: String?)

    private val _connectConfirm = MutableStateFlow<ConnectConfirm?>(null)
    val connectConfirm: StateFlow<ConnectConfirm?> = _connectConfirm.asStateFlow()

    /** User confirmed the deep-link connect: run the same path as ConnectProfile. */
    fun confirmDeepLinkConnect() {
        val c = _connectConfirm.value ?: return
        _connectConfirm.value = null
        connect(c.profile, password = c.profile.sshPassword.orEmpty(), sessionName = c.sessionName)
    }

    fun dismissDeepLinkConnect() {
        _connectConfirm.value = null
    }

    /** Params for the New-Connection editor to open pre-filled (unmatched host). */
    private val _prefillNewConnection =
        MutableStateFlow<sh.haven.core.data.agent.AgentUiCommand.PrefillNewConnection?>(null)
    val prefillNewConnection:
        StateFlow<sh.haven.core.data.agent.AgentUiCommand.PrefillNewConnection?> =
        _prefillNewConnection.asStateFlow()

    /** Clear the prefill request once the editor has consumed it. */
    fun consumePrefillNewConnection() {
        _prefillNewConnection.value = null
    }

    // Mirror the session-manager picker to a process-wide holder so the MCP
    // agent can observe it (get_pending_session_picker) and answer it
    // (answer_session_picker) without a human tap. Declared AFTER
    // _sessionSelection so the Main.immediate init collector never captures the
    // field before it's set (same gotcha as the _passwordFallback mirror).
    init {
        viewModelScope.launch {
            _sessionSelection.collect { sel ->
                sessionSelectionHolder.set(
                    sel?.let {
                        sh.haven.core.data.agent.PendingSessionSelection(
                            profileId = it.profileId,
                            sessionId = it.sessionId,
                            managerLabel = it.managerLabel,
                            sessionNames = it.sessionNames,
                            previousSessionNames = it.previousSessionNames,
                            suggestedNewName = it.suggestedNewName,
                        )
                    }
                )
            }
        }
    }

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
        _navigateToSpice.value = null
        _navigateToSmb.value = null
        _navigateToRclone.value = null
        _navigateToEmail.value = null
        _navigateToConnections.value = false
        _newSessionProfileId.value = null
    }

    /**
     * Consume just the desktop (VNC/RDP) navigation events. Collected at the
     * always-composed nav-host level so a desktop tab is created the instant the
     * connect emits, independent of which screen is on-screen — fixes Retry /
     * MCP connect_profile failing to open a tab when the Connections screen
     * isn't composed (#121). Separate from [onNavigated] so it doesn't clobber a
     * concurrent terminal/SMB navigation owned by the Connections screen.
     */
    fun onDesktopNavigated() {
        _navigateToVnc.value = null
        _navigateToRdp.value = null
        _navigateToSpice.value = null
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

    /**
     * Persist an SSH profile alongside its embedded Cloudflare Tunnel
     * transport (GH #154). When [cfTunnel] is non-null, the tunnel blob is
     * upserted first (so we know its id), then the profile is saved with
     * `tunnelConfigId` pointing at it and port forced to 22 — the CF
     * tunnel dial ignores port anyway, but the resolver path expects a
     * sensible value. When [cfTunnel] is null, any existing embedded
     * tunnel owned by this profile is dropped so the user can switch back
     * to direct-dial or a shared tunnel cleanly.
     */
    fun saveProfileWithEmbeddedCloudflareTunnel(
        profile: ConnectionProfile,
        cfTunnel: EmbeddedCloudflareTunnelInput?,
    ) {
        viewModelScope.launch {
            if (cfTunnel != null) {
                val hostname = cfTunnel.hostname.trim()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore('/')
                val blob = sh.haven.core.tunnel.CloudflareAccessConfigBlob(
                    hostname = hostname,
                    teamDomain = cfTunnel.teamDomain.trim()
                        .removePrefix("https://")
                        .removePrefix("http://"),
                    jwt = cfTunnel.jwt.trim(),
                    jwtExpiresAt = cfTunnel.jwtExpiresAt,
                    jumpDestination = cfTunnel.jumpDestination.trim(),
                )
                val tunnelId = tunnelConfigRepository.upsertEmbedded(
                    profileId = profile.id,
                    type = sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS.name,
                    label = "Cloudflare Tunnel: $hostname",
                    configText = blob.encode(),
                )
                repository.save(
                    profile.copy(
                        tunnelConfigId = tunnelId,
                        port = 22,
                        proxyType = null,
                        proxyHost = null,
                    ),
                )
            } else {
                tunnelConfigRepository.deleteByOwner(profile.id)
                repository.save(profile)
            }
        }
    }

    /**
     * One-shot fetch of an SSH profile's embedded Cloudflare Tunnel, if
     * any. The Connections screen calls this when opening the edit dialog
     * so the inline form can pre-populate. Returns the decrypted blob row;
     * null if the profile has no embedded tunnel.
     */
    suspend fun embeddedCloudflareTunnelFor(profileId: String): sh.haven.core.data.db.entities.TunnelConfig? =
        tunnelConfigRepository.findByOwner(profileId)

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

    /** Set (or clear, id=null) the group's default identity (#360). */
    fun setGroupIdentity(groupId: String, identityId: String?) {
        viewModelScope.launch {
            connectionGroupDao.getById(groupId)?.let {
                connectionGroupDao.upsert(it.copy(identityId = identityId))
            }
        }
    }

    /**
     * Returns true if the profile can connect without interactive dialogs.
     * VNC/RDP/SMB are excluded (they navigate to non-terminal screens).
     * SSH/Mosh/ET require a saved password or SSH keys.
     */
    /**
     * Build the pre-connect "knock" hook for a profile, or `null` if the
     * profile has no knock sequence configured (or the saved sequence
     * fails to parse — we keep that as a no-op rather than blocking the
     * connect, since the parser already gates the save button and an
     * unparseable string here means hand-edited / corrupted state).
     *
     * The returned suspend lambda fires the knock and writes a one-line
     * summary into [verboseLogger] so the result shows up in the
     * Connection Log entry. On knock failure it logs the error and
     * returns normally — the real connect attempt is left to surface the
     * actual symptom (timeout / connection refused / etc.). Aborting on
     * knock failure would mask cases where the firewall already opened
     * the port from a prior successful knock.
     */
    private fun buildKnockHook(
        profile: ConnectionProfile,
        verboseLogger: SshVerboseLogger?,
    ): (suspend () -> Unit)? {
        val spa = parseSpaConfig(profile)
        val sequence = KnockSequence.parse(
            profile.portKnockSequence,
            delayMs = profile.portKnockDelayMs,
        ).getOrNull()
        if (spa == null && sequence == null) return null
        return {
            // SPA runs first — it's the packet that opens the port. A knock
            // sequence, if also configured, stays a legacy fallback.
            if (spa != null) {
                recordSpaResult(verboseLogger, spa, spaSender.send(profile.host, spa))
            }
            if (sequence != null) {
                recordKnockResult(verboseLogger, sequence, portKnocker.knock(profile.host, sequence))
            }
        }
    }

    /** Parse a profile's SPA fields into a [SpaConfig], or null when SPA is disabled/invalid. */
    private fun parseSpaConfig(profile: ConnectionProfile): SpaConfig? =
        SpaConfig.parse(
            key = profile.spaKey,
            keyIsBase64 = profile.spaKeyBase64,
            hmacKey = profile.spaHmacKey,
            hmacKeyIsBase64 = profile.spaHmacKeyBase64,
            accessSpec = profile.spaAccessSpec,
            allowMode = profile.spaAllowMode,
            explicitIp = profile.spaExplicitIp,
            spaPort = profile.spaPort,
        ).getOrNull()

    private fun recordSpaResult(
        verboseLogger: SshVerboseLogger?,
        config: SpaConfig,
        result: SpaResult,
    ) {
        val line = if (result.ok) {
            "[spa] ${config.accessSpec} -> sent ${result.packetLen}B in ${result.totalDurationMs}ms"
        } else {
            "[spa] ${config.accessSpec} -> failed after ${result.totalDurationMs}ms: ${result.error?.message}"
        }
        Log.d(TAG, line)
        verboseLogger?.logInfo(line)
    }

    private fun recordKnockResult(
        verboseLogger: SshVerboseLogger?,
        sequence: KnockSequence,
        result: KnockResult,
    ) {
        val line = if (result.ok) {
            "[knock] ${sequence.format()} -> ok in ${result.totalDurationMs}ms"
        } else {
            "[knock] ${sequence.format()} -> failed after ${result.totalDurationMs}ms " +
                "(sent ${result.sentSteps}/${sequence.steps.size}): ${result.error?.message}"
        }
        Log.d(TAG, line)
        verboseLogger?.logInfo(line)
    }

    /**
     * One-shot port-knock against [host] using a freshly-parsed [sequenceText].
     * Used by the connection-edit dialog's "Test knock" button to verify
     * the user's knockd config without committing the profile or
     * attempting a real service connect.
     *
     * Returns `(ok, message)` for the UI to render. Never throws.
     */
    suspend fun testKnock(
        host: String,
        sequenceText: String,
        delayMs: Int,
    ): Pair<Boolean, String> {
        val parsed = KnockSequence.parse(sequenceText, delayMs)
        val seq = parsed.getOrElse {
            return false to appContext.getString(
                R.string.connections_knock_invalid_sequence, it.message ?: "",
            )
        } ?: return false to appContext.getString(R.string.connections_knock_empty)
        if (host.isBlank()) {
            return false to appContext.getString(R.string.connections_knock_host_blank)
        }
        val result = portKnocker.knock(host, seq)
        return if (result.ok) {
            true to appContext.getString(
                R.string.connections_knock_success,
                result.sentSteps,
                result.totalDurationMs.toInt(),
            )
        } else {
            false to appContext.getString(
                R.string.connections_knock_failed,
                result.totalDurationMs.toInt(),
                result.error?.message ?: "unknown",
            )
        }
    }

    /**
     * One-shot SPA send against [host] using a pre-validated [config]. Used by
     * the connection-edit dialog's "Test SPA" button. Returns `(ok, message)`
     * for the UI; never throws.
     */
    suspend fun testSpa(host: String, config: SpaConfig): Pair<Boolean, String> {
        if (host.isBlank()) {
            return false to appContext.getString(R.string.connections_spa_host_blank)
        }
        val result = spaSender.send(host, config)
        return if (result.ok) {
            true to appContext.getString(
                R.string.connections_spa_success,
                result.packetLen,
                result.totalDurationMs.toInt(),
            )
        } else {
            false to appContext.getString(
                R.string.connections_spa_failed,
                result.totalDurationMs.toInt(),
                result.error?.message ?: "unknown",
            )
        }
    }

    private fun canAutoConnect(profile: ConnectionProfile, keys: List<SshKey>): Boolean = when {
        profile.isLocal -> true
        profile.isReticulum -> true
        profile.isRclone -> true
        profile.isVnc -> false
        profile.isRdp -> false
        profile.isSpice -> false
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

    /**
     * Clear the saved SSH password for [profileId] (#121b). Mirror of the
     * existing `repository.save(profile.copy(sshPassword = password))`
     * remember-password path at line 1810 — same DAO call, just nulled.
     */
    fun forgetPassword(profileId: String) {
        viewModelScope.launch {
            val profile = repository.getById(profileId) ?: return@launch
            repository.save(profile.copy(sshPassword = null))
        }
    }

    fun dismissPasswordFallback() {
        _passwordFallback.value = null
        // If the dialog was opened for a tunnel-mode dependent (#121a),
        // dismissing means "skip this connect" — clear the pending
        // dependent so the next password dialog (e.g. an explicit SSH
        // login) doesn't accidentally trigger a tunnel retry.
        _pendingTunnelDependent.value = null
    }

    /**
     * Answer the active password / key-passphrase fallback prompt with
     * [password] (and optional [username] / [rememberPassword]) and re-drive
     * the stalled connect — the same logic the dialog's onConnect runs. Used by
     * both the UI dialog and the MCP `answer_auth_prompt` verb. No-op when no
     * prompt is pending.
     */
    fun answerPasswordFallback(
        password: String,
        username: String? = null,
        rememberPassword: Boolean = false,
    ) {
        val profile = _passwordFallback.value ?: return
        val pending = _pendingTunnelDependent.value
        if (pending != null) {
            // Tunnel-mode (#121a): the prompt was opened for a VNC/RDP/SMB
            // dependent whose jump host needs a password — route to the
            // tunnel-replay path.
            connectTunnelDependentAfterAuth(
                jumpProfile = profile,
                dependentProfile = pending,
                password = password,
                rememberPassword = rememberPassword,
            )
        } else {
            connect(
                profile,
                password,
                rememberPassword = rememberPassword,
                usernameOverride = username,
            )
        }
        dismissPasswordFallback()
    }

    /**
     * Returns the jump host profile if it needs a password prompt before
     * the tunnel auto-connect can proceed (#121a). "Needs a prompt" means
     * the profile has no saved password, no assigned key, and no
     * unencrypted SSH keys in the keystore that JSch could try as a
     * fallback. Returns null when at least one credential path is
     * available — the existing silent auto-connect handles those.
     */
    // internal for unit test (FIDO-jump-host-no-prompt regression, #286).
    internal suspend fun jumpHostNeedsPasswordPrompt(
        jumpProfileId: String,
    ): ConnectionProfile? {
        // A live jump session already exists → reuse it silently, no prompt.
        // Without this, the post-auth replay of connectVnc/connectRdp/connectSmb
        // re-prompts forever when the entered password wasn't saved ("remember"
        // off): the prompt check fires before connectJumpHost's connected-session
        // reuse, so it never sees that auth already succeeded — an endless
        // password loop, and cancelling left the now-open SSH session orphaned
        // (#121, KoriKraut). Also covers the case where the user already opened
        // the jump host in a Terminal tab.
        val alreadyConnected = sshSessionManager.getSessionsForProfile(jumpProfileId)
            .any { it.status == SshSessionManager.SessionState.Status.CONNECTED }
        if (alreadyConnected) return null

        val jp = repository.getById(jumpProfileId) ?: return null
        if (!jp.sshPassword.isNullOrBlank()) return null
        // "Use password only" — keys (explicit or keystore) are never offered,
        // so the prompt is the only credential path; fire it (#121).
        if (jp.ignoreSavedKeys) return jp
        if (jp.keyId != null) return null
        // connectJumpHost wires the FIDO authenticator + live prompter and
        // resolves the profile's full auth spec (#286), so recognise the
        // credentials it can complete WITHOUT a typed jump-host password and
        // don't pre-empt them with a prompt.
        val specs = jp.authMethodSpecs
        // An explicit spec key, or a live keyboard-interactive / TOTP round.
        if (specs.any {
                it is ConnectionProfile.AuthMethodSpec.KeyboardInteractive ||
                    it is ConnectionProfile.AuthMethodSpec.Totp ||
                    (it is ConnectionProfile.AuthMethodSpec.Key && it.keyId != null)
            }
        ) return null
        val keys = sshKeyRepository.getAllDecrypted().filter { it.enabledForAuth }
        // "Any hardware key": FIDO needs only a touch — usable iff an sk-key is
        // actually enrolled. Without this the sk-exclusion below (which predates
        // jump-host FIDO auth) shadowed FIDO with a password prompt (#286).
        if (specs.any { it is ConnectionProfile.AuthMethodSpec.AnyHardwareKey } &&
            keys.any { it.keyType.startsWith("sk-") }
        ) return null
        // A usable unencrypted software key also suppresses the prompt; FIDO/sk
        // keys are handled above (they aren't loadable as auto-offered keys).
        if (keys.any { !it.isEncrypted && !it.keyType.startsWith("sk-") }) return null
        return jp
    }

    /**
     * After the password dialog resolves for a tunnel-mode prompt, save
     * the password (if requested) and replay the dependent connect.
     * Calling [connectJumpHost] with the entered password establishes
     * the SSH session up front; the subsequent dependent connect then
     * finds and reuses that session via the existing-session lookup at
     * [connectJumpHost]'s `firstOrNull { CONNECTED }` branch.
     */
    fun connectTunnelDependentAfterAuth(
        jumpProfile: ConnectionProfile,
        dependentProfile: ConnectionProfile,
        password: String,
        rememberPassword: Boolean?,
    ) {
        viewModelScope.launch {
            try {
                if (rememberPassword == true && password.isNotBlank()) {
                    repository.save(jumpProfile.copy(sshPassword = password))
                }
                connectJumpHost(
                    jumpProfile.id,
                    password,
                    tunnelOwnerProfileId = dependentProfile.id,
                )
                connect(dependentProfile, password = "")
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel-auth retry failed: ${e.message}", e)
                // A mistyped password lands here — re-open the prompt (user-driven,
                // cancellable) instead of dead-ending on a raw JSch message.
                handleTunnelJumpFailure(e, dependentProfile, jumpProfile.id)
            }
        }
    }

    /**
     * Surface a jump-host (VNC/RDP/SMB-over-SSH) connect failure. On an
     * authentication failure with a recoverable credential path, re-open the
     * password prompt wired to [connectTunnelDependentAfterAuth] so the user can
     * enter or retry a password — mirroring the direct SSH tap's key→password
     * fallback — instead of dead-ending on a raw "Auth cancel for methods
     * 'password'" message (#121). Network and other failures fall through to a
     * clean [_error] string. Classification matches [connectSsh]'s catch block.
     */
    private suspend fun handleTunnelJumpFailure(
        e: Exception,
        dependentProfile: ConnectionProfile,
        sshProfileId: String,
    ) {
        val msg = e.message ?: ""
        val isNetworkError = e is java.net.ConnectException ||
            e is java.net.UnknownHostException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.NoRouteToHostException ||
            msg.contains("refused", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ||
            msg.contains("unreachable", ignoreCase = true)
        val isAuthMessage = !isNetworkError && (
            msg.contains("Auth fail", ignoreCase = true) ||
            msg.contains("Auth cancel", ignoreCase = true) ||
            msg.contains("authentication", ignoreCase = true) ||
            msg.contains("publickey", ignoreCase = true)
        )
        val jumpProfile = if (isAuthMessage) repository.getById(sshProfileId) else null
        if (jumpProfile != null) {
            Log.d(TAG, "Jump-host auth failed; prompting for password (${jumpProfile.label})")
            _pendingTunnelDependent.value = dependentProfile
            _passwordFallback.value = jumpProfile
        } else {
            _error.value = "SSH tunnel: ${msg.ifBlank { "connection failed" }}"
        }
    }

    fun connect(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean = false,
        rememberPassword: Boolean? = null,
        usernameOverride: String? = null,
        sessionName: String? = null,
    ) {
        // Identity resolution (#360): for SSH-family profiles, substitute the
        // effective identity's username/password/key just before dialing, so
        // the whole downstream auth machinery sees ordinary inline
        // credentials. A no-op (returns the profile unchanged) when no
        // identity is assigned or resolvable, so existing per-host
        // credentials behave exactly as before. Needs a DAO read → suspend.
        if (profile.isSsh || profile.isReticulum) {
            viewModelScope.launch {
                val resolved = sshIdentityRepository.applyTo(profile)
                // If the caller didn't already carry a typed password, adopt
                // the identity's (mirrors the jump-host merge idiom).
                val effectivePassword =
                    if (password.isEmpty()) resolved.sshPassword.orEmpty() else password
                connectAfterIdentity(resolved, effectivePassword, keyOnly, rememberPassword, usernameOverride, sessionName)
            }
            return
        }
        connectAfterIdentity(profile, password, keyOnly, rememberPassword, usernameOverride, sessionName)
    }

    /** Post-identity dispatch: USB-drive preflight, then [connectInner]. */
    private fun connectAfterIdentity(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean,
        rememberPassword: Boolean?,
        usernameOverride: String?,
        sessionName: String?,
    ) {
        // USB-drive bookmarks (#287): the "USB: …" connection's VM stops on
        // eject/sleep/app-restart, leaving the profile pointing at a dead
        // loopback port. connectionPreflight re-opens it (and refreshes the
        // profile's port/key) before dialing — a no-op for every other
        // profile, so this only adds a coroutine hop for USB-drive bookmarks.
        if (profile.usbDriveSerial != null) {
            viewModelScope.launch {
                when (val result = connectionPreflight.beforeConnect(profile)) {
                    is sh.haven.core.data.repository.ConnectionPreflight.Result.Block ->
                        _error.value = result.message
                    is sh.haven.core.data.repository.ConnectionPreflight.Result.Proceed ->
                        connectInner(result.profile, password, keyOnly, rememberPassword, usernameOverride, sessionName)
                }
            }
            return
        }
        connectInner(profile, password, keyOnly, rememberPassword, usernameOverride, sessionName)
    }

    private fun connectInner(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean = false,
        rememberPassword: Boolean? = null,
        usernameOverride: String? = null,
        sessionName: String? = null,
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
        if (profile.isSpice) {
            connectSpice(profile)
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
        if (profile.isEmail) {
            connectEmail(profile)
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
        connectSsh(profile, password, keyOnly, rememberPassword, usernameOverride = runtimeUsername, preselectedSessionName = sessionName)
    }

    private fun connectVnc(profile: ConnectionProfile) {
        val host = profile.host
        val port = profile.vncPort ?: profile.port
        val password = profile.vncPassword
        val username = profile.vncUsername
        viewModelScope.launch {
            repository.markConnected(profile.id)
            val sshProfileId = profile.vncSshProfileId
            if (profile.vncSshForward && sshProfileId != null) {
                // If the jump host has no saved credential and no key
                // JSch could try, prompt for a password up front instead
                // of silently failing the auto-connect (#121a).
                val needsPrompt = jumpHostNeedsPasswordPrompt(sshProfileId)
                if (needsPrompt != null) {
                    _pendingTunnelDependent.value = profile
                    _passwordFallback.value = needsPrompt
                    return@launch
                }
                // Auto-connect SSH tunnel host (reuses existing session if
                // available), mirroring the RDP path. The VNC screen itself
                // uses this sshSessionId to open the local forward.
                try {
                    _connectingProfileId.value = profile.id
                    val (sshSessionId, _) = connectJumpHost(
                        sshProfileId, "", tunnelOwnerProfileId = profile.id,
                    )
                    _navigateToVnc.value = VncNavigation(
                        host, port, password, username,
                        sshForward = true,
                        sshProfileId = sshProfileId,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        colorDepth = profile.vncColorDepth,
                        // Knock not honoured on the SSH-forward path, but
                        // pass it through so the field stays a single
                        // source of truth at the call site.
                        portKnockSequence = profile.portKnockSequence,
                        portKnockDelayMs = profile.portKnockDelayMs,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect SSH tunnel host for VNC", e)
                    handleTunnelJumpFailure(e, profile, sshProfileId)
                } finally {
                    _connectingProfileId.value = null
                }
            } else {
                _navigateToVnc.value = VncNavigation(
                    host, port, password, username,
                    sshForward = profile.vncSshForward,
                    sshProfileId = profile.vncSshProfileId,
                    profileId = profile.id,
                    colorDepth = profile.vncColorDepth,
                    portKnockSequence = profile.portKnockSequence,
                    portKnockDelayMs = profile.portKnockDelayMs,
                )
            }
        }
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
                val needsPrompt = jumpHostNeedsPasswordPrompt(sshProfileId)
                if (needsPrompt != null) {
                    _pendingTunnelDependent.value = profile
                    _passwordFallback.value = needsPrompt
                    return@launch
                }
                // Auto-connect SSH tunnel host (reuses existing session if available)
                try {
                    _connectingProfileId.value = profile.id
                    // Pass empty password — SSH profile uses its own auth (key or password)
                    val (sshSessionId, _) = connectJumpHost(
                        sshProfileId, "", tunnelOwnerProfileId = profile.id,
                    )
                    _navigateToRdp.value = RdpNavigation(
                        host, port, username, rdpPassword, domain,
                        sshForward = true,
                        sshProfileId = sshProfileId,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        useNla = profile.rdpUseNla,
                        colorDepth = profile.rdpColorDepth,
                        portKnockSequence = profile.portKnockSequence,
                        portKnockDelayMs = profile.portKnockDelayMs,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect SSH tunnel host for RDP", e)
                    handleTunnelJumpFailure(e, profile, sshProfileId)
                } finally {
                    _connectingProfileId.value = null
                }
            } else {
                _navigateToRdp.value = RdpNavigation(host, port, username, rdpPassword, domain, profile.rdpSshForward, profile.rdpSshProfileId, profileId = profile.id, useNla = profile.rdpUseNla, colorDepth = profile.rdpColorDepth, portKnockSequence = profile.portKnockSequence, portKnockDelayMs = profile.portKnockDelayMs)
            }
        }
    }

    private fun connectSpice(profile: ConnectionProfile) {
        val host = profile.host
        val port = profile.spicePort ?: profile.port
        val password = profile.spicePassword
        viewModelScope.launch {
            repository.markConnected(profile.id)
            val sshProfileId = profile.spiceSshProfileId
            if (profile.spiceSshForward && sshProfileId != null) {
                val needsPrompt = jumpHostNeedsPasswordPrompt(sshProfileId)
                if (needsPrompt != null) {
                    _pendingTunnelDependent.value = profile
                    _passwordFallback.value = needsPrompt
                    return@launch
                }
                try {
                    _connectingProfileId.value = profile.id
                    val (sshSessionId, _) = connectJumpHost(
                        sshProfileId, "", tunnelOwnerProfileId = profile.id,
                    )
                    _navigateToSpice.value = SpiceNavigation(
                        host, port, password,
                        sshForward = true,
                        sshProfileId = sshProfileId,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect SSH tunnel host for SPICE", e)
                    handleTunnelJumpFailure(e, profile, sshProfileId)
                } finally {
                    _connectingProfileId.value = null
                }
            } else {
                _navigateToSpice.value = SpiceNavigation(
                    host, port, password,
                    sshForward = profile.spiceSshForward,
                    sshProfileId = profile.spiceSshProfileId,
                    profileId = profile.id,
                )
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
            // Hoisted out of try so the catch block can see them when
            // cleaning up after a connect failure (#121).
            var sshClientCloseable: java.io.Closeable? = null
            var tunnelPort: Int? = null
            try {
                _connectingProfileId.value = profile.id
                val sshProfileId = profile.smbSshProfileId

                if (profile.smbSshForward && sshProfileId != null) {
                    val needsPrompt = jumpHostNeedsPasswordPrompt(sshProfileId)
                    if (needsPrompt != null) {
                        _pendingTunnelDependent.value = profile
                        _passwordFallback.value = needsPrompt
                        _connectingProfileId.value = null
                        return@launch
                    }
                    // Isolate the jump-host connect: an auth failure here is the
                    // SSH credential, not the SMB share's — route it to the
                    // password prompt rather than the SMB-auth error path (#121).
                    val sshSessionId = try {
                        connectJumpHost(sshProfileId, "", tunnelOwnerProfileId = profile.id).first
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to connect SSH tunnel host for SMB", e)
                        handleTunnelJumpFailure(e, profile, sshProfileId)
                        _connectingProfileId.value = null
                        return@launch
                    }
                    val sshClient = sshSessionManager.sessions.value[sshSessionId]?.client
                        ?: throw IllegalStateException("SSH tunnel session not found")
                    // Set up local port forward: random port -> remoteHost:smbPort
                    tunnelPort = withContext(Dispatchers.IO) {
                        sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    }
                    sshClientCloseable = sshClient
                    Log.d(TAG, "SMB SSH tunnel: 127.0.0.1:$tunnelPort -> $host:$port")
                }

                // WireGuard / Tailscale routing — only used when not going
                // through SSH RemoteForward. SSH-forward already provides
                // tunneled connectivity via 127.0.0.1:<localPort>, and
                // routing the SMB profile's own tunnelConfigId through the
                // SSH session would be a double-hop. (#149)
                val socketFactory = if (sshClientCloseable == null) {
                    tunnelResolver.socketFactory(profile)
                } else {
                    null
                }

                // Knock against the SMB host only when going direct. When
                // tunneled via SSH RemoteForward the SMB server sees
                // packets from the SSH host's IP, so a knock from the
                // Android client wouldn't reach the right firewall —
                // the SSH session is already gating access in that case.
                if (sshClientCloseable == null) {
                    buildKnockHook(profile, verboseLogger = null)?.invoke()
                }

                val sessionId = smbSessionManager.registerSession(profile.id, profile.label)
                withContext(Dispatchers.IO) {
                    smbSessionManager.connectSession(
                        sessionId, host, port, shareName, smbUsername, smbPassword, domain,
                        sshClient = sshClientCloseable,
                        tunnelPort = tunnelPort,
                        socketFactory = socketFactory,
                    )
                }
                _navigateToSmb.value = profile.id
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect SMB", e)
                // Connect-failure cleanup — same pattern as the VNC/RDP
                // path in DesktopViewModel (#121). On failure neither the
                // local port forward nor the SSH tunnel-dependent set
                // unwound on its own, leaving the SSH session green-dotted
                // in the connections list and the next retry racing
                // against stale state.
                if (tunnelPort != null && sshClientCloseable is SshClient) {
                    try {
                        withContext(Dispatchers.IO) {
                            sshClientCloseable.delPortForwardingL("127.0.0.1", tunnelPort!!)
                        }
                    } catch (cleanupErr: Throwable) {
                        Log.w(TAG, "SMB tunnel cleanup failed after connect error", cleanupErr)
                    }
                }
                sshSessionManager.releaseTunnelDependent(profile.id)
                _error.value = "SMB: ${e.message}"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    /**
     * Cancel an in-flight OAuth flow for [profile]. Best-effort: marks
     * the underlying rclone session ERROR so the UI clears the spinner;
     * the gomobile worker thread keeps running in the background until
     * app restart (rclone-android has no cancellation primitive).
     */
    fun cancelPendingOAuth(profile: ConnectionProfile) {
        rcloneSessionManager.getSessionsForProfile(profile.id).forEach {
            rcloneSessionManager.cancelPendingOAuth(it.sessionId)
        }
    }

    /**
     * Force a fresh OAuth flow on an rclone profile by dropping the
     * stored remote in rclone's config, then running the normal connect
     * path (which sees no remote and starts OAuth via
     * [RcloneSessionManager.connectSession]). Use this when stored
     * tokens have gone stale and silent refresh is failing — saves the
     * user from delete-and-re-add the entire Haven profile (#108).
     *
     * Disconnects any live session first so the existing connection's
     * state doesn't survive the re-auth and confuse the next connect.
     */
    fun reauthRcloneProfile(profile: ConnectionProfile) {
        val remoteName = profile.rcloneRemoteName ?: return
        viewModelScope.launch {
            // Tear down any live rclone session for this profile before
            // we drop the underlying config. Otherwise the stale
            // session sits in CONNECTED with credentials that no
            // longer exist.
            rcloneSessionManager.removeAllSessionsForProfile(profile.id)
            withContext(Dispatchers.IO) {
                try {
                    rcloneClient.deleteRemote(remoteName)
                } catch (e: Exception) {
                    Log.w(TAG, "deleteRemote('$remoteName') failed before re-auth: ${e.message}")
                }
            }
            connectRclone(profile)
        }
    }

    private fun connectRclone(profile: ConnectionProfile) {
        val remoteName = profile.rcloneRemoteName ?: return
        val provider = profile.rcloneProvider ?: ""
        viewModelScope.launch {
            repository.markConnected(profile.id)
            try {
                _connectingProfileId.value = profile.id
                // Route rclone's HTTP traffic through the per-profile
                // tunnel's SOCKS5 listener if one is configured (#149).
                // Set BEFORE the first RPC for this remote — rclone
                // caches HTTP clients per-fs, so a later swap wouldn't
                // take effect.
                val socksAddr = tunnelResolver.socksEndpoint(profile)
                rcloneClient.setProxy(socksAddr?.let { "socks5://${it.hostString}:${it.port}" })
                val sessionId = rcloneSessionManager.registerSession(profile.id, profile.label)
                withContext(Dispatchers.IO) {
                    rcloneSessionManager.connectSession(sessionId, remoteName, provider)
                }
                // If connected immediately (no OAuth needed), navigate to Files
                if (rcloneSessionManager.isProfileConnected(profile.id)) {
                    _navigateToRclone.value = profile.id
                }
                // Otherwise OAuth is in progress — the session will become
                // CONNECTED when the user completes the browser flow.
                // Watch for both terminal states: CONNECTED → navigate;
                // ERROR → surface the rclone error message as a toast so
                // the user isn't stuck on a silent spinner if OAuth fails
                // (browser dismissed, no browser app installed, bad
                // client_id, network blip during token exchange, etc).
                else {
                    launch {
                        rcloneSessionManager.sessions.collect { sessions ->
                            val ours = sessions.values.firstOrNull { it.profileId == profile.id }
                                ?: return@collect
                            when (ours.status) {
                                RcloneSessionManager.SessionState.Status.CONNECTED -> {
                                    _navigateToRclone.value = profile.id
                                    return@collect
                                }
                                RcloneSessionManager.SessionState.Status.ERROR -> {
                                    _error.value = ours.errorMessage
                                        ?: "rclone connection failed — see Settings → Connection log"
                                    _connectingProfileId.value = null
                                    return@collect
                                }
                                else -> Unit
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

    /**
     * Connect a Proton EMAIL profile. Mirrors [connectRclone] but adds the
     * pre-connect SPA/knock layer (which [connectRclone] lacks): SRP login +
     * keyring unlock happen in the Go mailbridge, routed through the per-profile
     * tunnel's SOCKS5 endpoint. Stored mailbox password + a linked TOTP secret
     * satisfy two-password / 2FA accounts without a live prompt in v1; if the
     * bridge still demands a missing factor, surface an actionable error.
     */
    private fun connectEmail(profile: ConnectionProfile) {
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            val verbose = SshVerboseLogger()
            val startedAt = System.currentTimeMillis()
            try {
                repository.markConnected(profile.id)

                // Pre-connect SPA/knock (R1) — guards the user's tunnel ingress
                // (profile.host) before any tunnel/transport resolves, for every
                // email provider. The receipt lands in this profile's log.
                buildKnockHook(profile, verbose)?.invoke()

                val username = (profile.emailUsername ?: profile.username).trim()
                val password = profile.emailPassword.orEmpty()
                if (username.isBlank() || password.isBlank()) {
                    throw IllegalStateException("Email username and password are required.")
                }

                // Provider selects the engine + transport: Proton rides the
                // tunnel's SOCKS5 listener (FFI consumer); IMAP rides a JVM
                // SocketFactory. Both fail closed when a tunnel is configured but
                // yields no transport (R7) rather than leaking onto the clearnet.
                val isImap = profile.emailProvider?.equals("imap", ignoreCase = true) == true
                val engine = if (isImap) MailEngine.IMAP else MailEngine.PROTON
                val params = if (isImap) {
                    buildImapParams(profile, username, password)
                } else {
                    buildProtonParams(profile, username, password)
                }

                val sessionId = mailSessionManager.registerSession(profile.id, profile.label, engine)

                try {
                    mailSessionManager.connectSession(sessionId, params)
                } catch (e: MailException.MailboxPasswordRequired) {
                    throw IllegalStateException(
                        "This Proton account uses a separate mailbox password — add it in Edit → Email.",
                    )
                } catch (e: MailException.TwoFaRequired) {
                    throw IllegalStateException(
                        "This Proton account has 2FA enabled — link its TOTP secret in Edit → Email.",
                    )
                }

                _navigateToEmail.value = profile.id
                connectionLogRepository.logEvent(
                    profileId = profile.id,
                    status = ConnectionLog.Status.CONNECTED,
                    durationMs = System.currentTimeMillis() - startedAt,
                    details = if (isImap) "IMAP mail connected" else "Proton mail connected",
                    verboseLog = verbose.drain(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect email profile", e)
                _error.value = "Email: ${e.message}"
                connectionLogRepository.logEvent(
                    profileId = profile.id,
                    status = ConnectionLog.Status.FAILED,
                    durationMs = System.currentTimeMillis() - startedAt,
                    details = e.message,
                    verboseLog = verbose.drain(),
                )
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    /**
     * Proton transport: route HTTPS through the per-profile tunnel's SOCKS5
     * listener. MailBridge wants a bare `host:port` (it prepends `socks5://`
     * itself) — NOT a URL. Fail closed (R7) when a tunnel is configured but
     * yields no SOCKS endpoint (e.g. Cloudflare Access).
     */
    private suspend fun buildProtonParams(
        profile: ConnectionProfile,
        username: String,
        password: String,
    ): MailConnectParams.Proton {
        val socks = tunnelResolver.socksEndpoint(profile)
            ?.let { "${it.hostString}:${it.port}" }
        if (profile.tunnelConfigId != null && socks == null) {
            throw IllegalStateException(
                "Tunnel configured but provides no SOCKS endpoint — refusing to connect Proton directly.",
            )
        }
        return MailConnectParams.Proton(
            username = username,
            password = password,
            mailboxPassword = profile.emailMailboxPassword?.ifBlank { null },
            twoFA = resolveEmailTotp(profile),
            socks = socks,
        )
    }

    /**
     * IMAP transport: route IMAP/SMTP through the per-profile tunnel via a JVM
     * [javax.net.SocketFactory]. Fail closed (R7) when a tunnel is configured
     * but yields no factory; a null factory with no tunnel is a normal direct
     * connection.
     */
    private suspend fun buildImapParams(
        profile: ConnectionProfile,
        username: String,
        password: String,
    ): MailConnectParams.Imap {
        val server = profile.emailServer?.trim().orEmpty()
        if (server.isBlank()) throw IllegalStateException("IMAP server is required.")
        val factory = tunnelResolver.socketFactory(profile)
        if (profile.tunnelConfigId != null && factory == null) {
            throw IllegalStateException(
                "Tunnel configured but provides no socket factory — refusing to connect IMAP directly.",
            )
        }
        return MailConnectParams.Imap(
            username = username,
            password = password,
            server = server,
            port = profile.emailPort,
            smtpPort = profile.emailSmtpPort,
            tls = profile.emailTls,
            // SMTP host (smtp.gmail.com vs imap.gmail.com); null falls back to `server`.
            smtpServer = profile.emailSmtpServer?.trim()?.ifBlank { null },
            socketFactory = factory,
        )
    }

    /**
     * Generate the current TOTP code from a TOTP secret linked via the profile's
     * [ConnectionProfile.emailAuthMethods] (`TOTP[:<secretId>]`), or null when no
     * TOTP factor is configured. Proton uses standard TOTP (SHA1/6/30).
     */
    private suspend fun resolveEmailTotp(profile: ConnectionProfile): String? {
        val spec = ConnectionProfile.AuthMethodSpec.parseList(profile.emailAuthMethods)
            .filterIsInstance<ConnectionProfile.AuthMethodSpec.Totp>()
            .firstOrNull() ?: return null
        val secretId = spec.secretId
            ?: totpSecretRepository.observeAll().first().firstOrNull()?.id
            ?: return null
        val secret = totpSecretRepository.getDecryptedSecret(secretId) ?: return null
        return runCatching { Totp.generate(secret) }.getOrNull()
    }

    val desktopSetupState: StateFlow<sh.haven.core.local.ProotManager.DesktopSetupState> =
        localSessionManager.prootManager.desktopState

    /** Desktop process states from DesktopManager. */
    val desktopStates: StateFlow<Map<sh.haven.core.local.ProotManager.DesktopEnvironment, sh.haven.core.local.DesktopManager.DesktopInstance>> =
        localSessionManager.desktopManager.desktops

    /** All installed desktop environments. */
    val installedDesktops: Set<sh.haven.core.local.ProotManager.DesktopEnvironment>
        get() = localSessionManager.prootManager.installedDesktops

    /** Currently-active distro id (e.g. "alpine-3.21", "debian-bookworm"). */
    val activeDistroId: StateFlow<String> =
        localSessionManager.prootManager.activeDistroIdFlow

    /** Rootfs install progress for the active distro (NotInstalled/Downloading/Extracting/Ready/Error). */
    val rootfsSetupState: StateFlow<sh.haven.core.local.ProotManager.SetupState> =
        localSessionManager.prootManager.state

    /** Distros installed on this device (filesystem-derived). */
    val installedDistros: List<sh.haven.core.local.proot.Distro>
        get() = localSessionManager.prootManager.installedDistros

    /** Catalog distros that could be added (arch-compatible, not installed). */
    val availableDistros: List<sh.haven.core.local.proot.Distro>
        get() = localSessionManager.prootManager.availableDistros

    /** Switch the active distro — affects which rootfs subsequent DE ops use. */
    fun switchActiveDistro(distroId: String) {
        localSessionManager.prootManager.setActiveDistroId(distroId)
    }

    /**
     * Delete a distro's rootfs. Reclaims the disk space and is the
     * recovery path when an install lands in a broken state. If the
     * deleted distro was active, ProotManager switches to the first
     * remaining installed one. Also stops any running DEs on the
     * deleted distro since their rootfs is about to disappear.
     */
    fun deleteDistro(distroId: String) {
        val pm = localSessionManager.prootManager
        // Stop any DEs running on the doomed distro before pulling
        // the rootfs out from under them.
        if (pm.activeDistroId == distroId) {
            localSessionManager.desktopManager.stopAll()
        }
        pm.deleteDistro(distroId)
    }

    /**
     * Make [distro] the active distro and install its rootfs. The
     * common path for "+ Add another distro" — selecting one already
     * installed just switches to it via [switchActiveDistro]; this
     * action both switches AND triggers the download/extract flow.
     *
     * If the rootfs install fails the active distro reverts to the
     * previous selection so the user isn't left with an "active but
     * missing" distro where Haven thinks no rootfs is installed.
     */
    fun addDistro(distro: sh.haven.core.local.proot.Distro) {
        val pm = localSessionManager.prootManager
        val previousActive = pm.activeDistroId
        pm.setActiveDistroId(distro.id)
        viewModelScope.launch {
            try {
                pm.installRootfs()
                val state = pm.state.value
                if (state is sh.haven.core.local.ProotManager.SetupState.Error) {
                    Log.w(TAG, "addDistro: ${distro.id} install failed (${state.message}), reverting to $previousActive")
                    pm.setActiveDistroId(previousActive)
                }
            } catch (e: Exception) {
                Log.e(TAG, "addDistro: ${distro.id} install threw", e)
                pm.setActiveDistroId(previousActive)
            }
        }
    }

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

    // (connectLocalTerminal removed in 3c — local shells are added via
    //  Connection + → LOCAL transport, same as every other backend.
    //  Existing "Local Shell" profiles created by the old topbar button
    //  remain in the list and continue to work.)

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

            val sessionId = localSessionManager.registerSession(profile.id, profile.label, profile.useAndroidShell, profile.prootDistroId)
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

    /**
     * Persist or clear the remembered SSH password/passphrase after a successful
     * connect. Skips multi-user profiles (a runtime-prompted username) — persisting
     * one user's password would bleed it into other sessions. Called from both the
     * direct connect path and the session-picker path (#290), since the latter
     * returns before the inline save block would otherwise run.
     */
    private suspend fun persistRememberedPassword(
        profile: ConnectionProfile,
        password: String,
        rememberPassword: Boolean?,
        multiUserProfile: Boolean,
    ) {
        if (multiUserProfile) return

        // For an encrypted-key profile the entered secret is the KEY
        // PASSPHRASE, not the host password — resolveExplicitKey feeds
        // `password` to JSch as the passphrase. Persist it on the KEY
        // (#290 store) rather than profile.sshPassword. Storing it on the
        // profile mislabels it "<host> — SSH password" in the credential
        // audit (#290 follow-up) and duplicates the passphrase into every
        // host that shares the key; the per-key store is reused across all
        // of them and labelled correctly. resolveExplicitKey already reads
        // getStoredPassphrase when no passphrase is supplied, so this slots
        // straight into the existing auto-unlock path.
        val keyId = profile.keyId?.takeIf { !profile.ignoreSavedKeys }
        val encryptedKeyId = keyId
            ?.let { sshKeyRepository.getById(it) }
            ?.takeIf { it.isEncrypted }
            ?.let { keyId }
        if (encryptedKeyId != null) {
            if (rememberPassword == true && password.isNotBlank()) {
                sshKeyRepository.setStoredPassphrase(encryptedKeyId, password)
            } else if (rememberPassword == false) {
                sshKeyRepository.setStoredPassphrase(encryptedKeyId, null)
            }
            // Migrate away from the conflated slot: a passphrase saved here
            // by an older build (or fed in as a "host password") would keep
            // showing as an SSH password in the audit.
            if (rememberPassword != null && profile.sshPassword != null) {
                repository.save(profile.copy(sshPassword = null))
            }
            return
        }

        if (rememberPassword == true && password.isNotBlank()) {
            repository.save(profile.copy(sshPassword = password))
        } else if (rememberPassword == false && profile.sshPassword != null) {
            repository.save(profile.copy(sshPassword = null))
        }
    }

    private fun connectSsh(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean,
        rememberPassword: Boolean? = null,
        usernameOverride: String? = null,
        preselectedSessionName: String? = null,
    ) {
        val effectiveUsername = usernameOverride?.takeIf { it.isNotBlank() } ?: profile.username
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
            val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null
            // Reuse a live SSH connection to this profile instead of dialing a
            // second flow. A 2nd dial over a WireGuard/Tailscale tunnel can't be
            // serviced by some peers (a FRITZ!Box drops the 2nd concurrent
            // WG→LAN flow), and one SSH connection carrying several tmux/zellij
            // sessions is the canonical model anyway. Scoped to tunnel-routed
            // profiles so direct connections keep independent-per-tab semantics.
            // awaitReusableClient (not getSshClientForProfile) also waits for an
            // in-flight connect to the same profile — closing the app-launch race
            // where this restore runs before the MCP carrier reaches CONNECTED.
            val reuseClient = if (profile.tunnelConfigId != null) {
                sshSessionManager.awaitReusableClient(profile.id)
            } else null
            val client = reuseClient ?: SshClient().apply {
                fidoAuthenticator = this@ConnectionsViewModel.fidoAuthenticator
                this.verboseLogger = verboseLogger
            }
            val sessionId = sshSessionManager.registerSession(profile.id, profile.label, client)

            // Track whether we auto-created the jump session (for cleanup on failure)
            var autoCreatedJumpSessionId: String? = null
            var isFidoAuth = false
            try {
                // If profile has a jump host, establish that connection first.
                // Skipped when reusing — the live client already rode its jump.
                val jumpProfileId = if (reuseClient == null) profile.jumpProfileId else null
                val jumpSessionId = if (jumpProfileId != null) {
                    val (jid, reused) = connectJumpHost(jumpProfileId, password)
                    if (!reused) autoCreatedJumpSessionId = jid
                    jid
                } else null

                if (jumpSessionId != null) {
                    sshSessionManager.setJumpSessionId(sessionId, jumpSessionId)
                }

                val sshSessionMgr = withContext(Dispatchers.IO) {
                    val sshSessionMgr = resolveSessionManager(profile)
                    val cmdOverride = preferencesRepository.sessionCommandOverride.first()
                    if (reuseClient != null) {
                        // Reuse path: the SSH connection is already up, so no
                        // dial, auth, host-key check or reconnect provider — the
                        // primary session owns all of those. Store a bookkeeping
                        // config with reconnect OFF (the primary's reconnect
                        // re-establishes the shared client) and a fresh empty
                        // Password so tearDown's auth-zeroing can never reach
                        // back into the primary's live credential.
                        Log.i(TAG, "Reusing live SSH connection to ${profile.label} for a second session (no new dial)")
                        val config = ConnectionConfig(
                            host = profile.host,
                            port = profile.port,
                            username = effectiveUsername,
                            authMethod = ConnectionConfig.AuthMethod.Password(""),
                            reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = false),
                        )
                        sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr, cmdOverride, profile.postLoginCommand, profile.postLoginBeforeSessionManager)
                    } else {
                        val authMethod = resolveAuthMethods(profile, password)
                        isFidoAuth = authMethod.containsFidoKey()
                        val config = ConnectionConfig(
                            host = profile.host,
                            port = profile.port,
                            username = effectiveUsername,
                            authMethod = authMethod,
                            sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                            forwardAgent = profile.forwardAgent,
                            addressFamily = profile.addressFamilyForSsh,
                            agentIdentities = agentIdentitiesFor(profile),
                            reconnectPolicy = profile.reconnectPolicy,
                        )

                        // Proxy precedence: jump host > Route-through (tunnel /
                        // SOCKS / HTTP). Jump host needs a live SSH session so it
                        // stays inline; everything else delegates to TunnelResolver.
                        val proxy = if (jumpSessionId != null) {
                            sshSessionManager.createProxyJump(jumpSessionId)
                                ?: throw Exception("Jump host session not usable for tunneling")
                        } else {
                            tunnelResolver.havenProxy(profile)
                        }
                        Log.d(TAG, "Connecting to ${config.host}:${config.port} (proxy=${proxy != null})")
                        try {
                            val hostKeyEntry = client.connect(
                                config,
                                proxy = proxy,
                                keyboardInteractivePrompter = keyboardInteractivePrompter,
                                totpCodeProvider = buildTotpCodeProvider(profile),
                                confirmOtp = profile.totpConfirmBeforeSend,
                                preConnect = buildKnockHook(profile, verboseLogger),
                            )
                            runTofuVerification(hostKeyEntry, clientToDisconnectOnReject = client, autoAccept = profile.usbDriveSerial != null)
                        } catch (e: HostKeyAuthFailure) {
                            // KEX succeeded but auth failed — still run the TOFU
                            // prompt on first contact (Haven#75 follow-up) then
                            // rethrow the underlying JSch auth error so the catch
                            // block below can trigger the password-fallback UX.
                            runTofuVerification(e.hostKey, clientToDisconnectOnReject = null, autoAccept = profile.usbDriveSerial != null)
                            throw e.cause ?: e
                        }

                        sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr, cmdOverride, profile.postLoginCommand, profile.postLoginBeforeSessionManager)
                        // Re-resolve the profile's tunnel/proxy on auto-reconnect.
                        // Jump-host sessions reconnect via createProxyJump instead,
                        // so only register for the non-jump (havenProxy) case.
                        if (jumpSessionId == null) {
                            sshSessionManager.setReconnectProxyProvider(sessionId) {
                                tunnelResolver.havenProxy(profile)
                            }
                        }
                    }
                    sshSessionMgr
                }

                // Drain verbose log now (before session picker might return from the coroutine)
                verboseLogger?.drain()?.let { pendingVerboseLogs[sessionId] = it }

                // Agent-supplied session name (connect_profile sessionName): attach
                // or create it directly and skip the interactive picker, which the
                // agent can't tap. tmux/zellij "new-session -A -s <name>" attaches
                // if the session exists and creates it otherwise.
                if (preselectedSessionName != null) {
                    sshSessionManager.setChosenSessionName(sessionId, preselectedSessionName)
                }

                // If session manager supports listing, check for existing sessions
                val listCmd = sshSessionMgr.listCommand
                if (preselectedSessionName == null && listCmd != null) {
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
                        // #290: auth already succeeded (the list command ran above), so
                        // persist the remembered passphrase NOW — the session-picker early
                        // return below exits before the post-finishConnect save block. If the
                        // user later cancels the picker, the saved passphrase is still valid
                        // and the "remember" intent was already given, so keeping it is fine.
                        persistRememberedPassword(profile, password, rememberPassword, usernameOverride != null)
                        _sessionSelection.value = SessionSelection(
                            sessionId = sessionId,
                            profileId = profile.id,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                            previousSessionNames = previousNames,
                            manager = sshSessionMgr,
                            suggestedNewName = generateUniqueSessionName(profile.label, existingSessions),
                        )
                        _connectingProfileId.value = null
                        return@launch // UI will call onSessionSelected() to continue
                    }
                }

                // No existing sessions or no session manager — proceed directly
                finishConnect(sessionId, profile.id, verboseLog = verboseLogger?.drain())

                // Save or clear remembered password after successful connect.
                persistRememberedPassword(profile, password, rememberPassword, usernameOverride != null)

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
                val isAuthMessage = !isNetworkError && (
                    msg.contains("Auth fail", ignoreCase = true) ||
                    msg.contains("Auth cancel", ignoreCase = true) ||
                    msg.contains("authentication", ignoreCase = true) ||
                    msg.contains("publickey", ignoreCase = true)
                )
                // #292: JSch throws "Incorrect passphrase provided." synchronously
                // from addIdentity when an assigned encrypted key's passphrase
                // (a saved host password fed in as the passphrase, blank, or a
                // stale stored #290 value) fails to decrypt it. That string is
                // not an "Auth fail"-style message and the saved-password tap
                // path runs with keyOnly=false, so without this branch it
                // dead-ends to an error toast instead of the passphrase prompt.
                val isPassphraseError = !isNetworkError &&
                    msg.contains("passphrase", ignoreCase = true)
                val isAuthError = keyOnly && isAuthMessage
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
                } else if (isPassphraseError) {
                    Log.d(TAG, "Encrypted-key passphrase failed — prompting (#292)")
                    _passwordFallback.value = profile
                } else if (!keyOnly && isAuthMessage) {
                    _error.value = "Authentication failed — check username and password"
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

                val dialer = tunnelResolver.socketDialer(profile)
                withContext(Dispatchers.IO) {
                    reticulumSessionManager.connectSession(
                        sessionId = sessionId,
                        configDir = configDir,
                        host = profile.reticulumHost,
                        port = profile.reticulumPort,
                        ifacNetname = profile.reticulumNetworkName,
                        ifacNetkey = profile.reticulumPassphrase,
                        socketDialer = dialer,
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
                    val authMethod = resolveAuthMethods(profile, password)
                    isFidoAuth = authMethod is ConnectionConfig.AuthMethod.FidoKey
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = effectiveUsername,
                        authMethod = authMethod,
                        sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                        forwardAgent = profile.forwardAgent,
                        addressFamily = profile.addressFamilyForSsh,
                        agentIdentities = agentIdentitiesFor(profile),
                        reconnectPolicy = profile.reconnectPolicy,
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
                        tunnelResolver.havenProxy(profile)
                    }

                    try {
                        val hostKeyEntry = sshClient.connect(
                            config,
                            proxy = proxy,
                            keyboardInteractivePrompter = keyboardInteractivePrompter,
                            totpCodeProvider = buildTotpCodeProvider(profile),
                            confirmOtp = profile.totpConfirmBeforeSend,
                            preConnect = buildKnockHook(profile, verboseLogger),
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
                            suggestedNewName = generateUniqueSessionName(profile.label, existingSessions),
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
                val isAuthMessage =
                    msg.contains("Auth fail", ignoreCase = true) ||
                        msg.contains("Auth cancel", ignoreCase = true) ||
                        msg.contains("authentication", ignoreCase = true) ||
                        msg.contains("publickey", ignoreCase = true)
                val isAuthError = keyOnly && isAuthMessage
                if (isFidoAuth && (isAuthError || (keyOnly && msg.isBlank()))) {
                    val fidoDetail = fidoAuthenticator.lastAssertionError
                    _error.value = if (fidoDetail != null) "Security key: $fidoDetail"
                    else msg.ifBlank { "Security key authentication failed" }
                } else if (isAuthError || (keyOnly && msg.isBlank())) {
                    _passwordFallback.value = profile
                } else if (!keyOnly && isAuthMessage) {
                    _error.value = "Authentication failed — check username and password"
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
                    val authMethod = resolveAuthMethods(profile, password)
                    isFidoAuth = authMethod is ConnectionConfig.AuthMethod.FidoKey
                    val config = ConnectionConfig(
                        host = profile.host,
                        port = profile.port,
                        username = effectiveUsername,
                        authMethod = authMethod,
                        sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                        forwardAgent = profile.forwardAgent,
                        addressFamily = profile.addressFamilyForSsh,
                        agentIdentities = agentIdentitiesFor(profile),
                        reconnectPolicy = profile.reconnectPolicy,
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
                        tunnelResolver.havenProxy(profile)
                    }

                    try {
                        val hostKeyEntry = sshClient.connect(
                            config,
                            proxy = proxy,
                            keyboardInteractivePrompter = keyboardInteractivePrompter,
                            totpCodeProvider = buildTotpCodeProvider(profile),
                            confirmOtp = profile.totpConfirmBeforeSend,
                            preConnect = buildKnockHook(profile, verboseLogger),
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
                            suggestedNewName = generateUniqueSessionName(profile.label, existingSessions),
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
                val isAuthMessage =
                    msg.contains("Auth fail", ignoreCase = true) ||
                        msg.contains("Auth cancel", ignoreCase = true) ||
                        msg.contains("authentication", ignoreCase = true) ||
                        msg.contains("publickey", ignoreCase = true)
                val isAuthError = keyOnly && isAuthMessage
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
                } else if (!keyOnly && isAuthMessage) {
                    _error.value = "Authentication failed — check username and password"
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
     * ride the same connection as extra shell channels via
     * [sh.haven.core.ssh.SshSessionAttacher] — one SSH connection carrying
     * several multiplexer sessions, the same model as a manual new tab —
     * instead of dialing (and re-authenticating) once per name.
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
                var anyReady = false
                sshSessionManager.setChosenSessionName(sessionId, sessionNames.first())
                anyReady = finishConnect(sessionId, profileId, silent = true) || anyReady

                // A failed name doesn't abort the rest — restore what we can
                // and report the last failure.
                var lastFailure: String? = null
                for (name in sessionNames.drop(1)) {
                    when (val r = sshSessionAttacher.ensureAttached(profileId, name)) {
                        is sh.haven.core.ssh.SshSessionAttacher.Result.Attached,
                        is sh.haven.core.ssh.SshSessionAttacher.Result.AlreadyLive,
                        -> anyReady = true
                        is sh.haven.core.ssh.SshSessionAttacher.Result.NoLiveConnection ->
                            lastFailure = "connection is not up"
                        is sh.haven.core.ssh.SshSessionAttacher.Result.Failed ->
                            lastFailure = r.message
                    }
                }
                lastFailure?.let { _error.value = "Restore failed: $it" }
                // Navigate only if at least one session reached a live terminal
                // (a host with no session manager closes the shell immediately).
                if (anyReady) _navigateToTerminal.value = profileId
            } catch (e: Exception) {
                Log.e(TAG, "Restore sessions failed", e)
                _error.value = "Restore failed: ${e.message}"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    /**
     * "Plain shell" path from the session picker — finish connection
     * without invoking the configured session manager (tmux / zellij /
     * screen). Lets a user open a one-off bare bash on a profile that's
     * normally wrapped in a multiplexer, e.g. for a quick check that
     * shouldn't disturb the long-running session.
     *
     * Only applies to SSH sessions today — Mosh and ET each require a
     * remote-side helper that's tied to their session-manager wrapping,
     * so a "plain" path on those transports would mean a different shape
     * of connection.
     */
    fun onPlainShellSelected(sessionId: String) {
        val sel = _sessionSelection.value
        _sessionSelection.value = null
        if (sel?.transportType != "SSH" && sel?.transportType != null) {
            // Only meaningful for SSH right now; fall through to the
            // normal "create new session" flow on Mosh/ET so users
            // don't end up with a silently-no-op button.
            onSessionSelected(sessionId, null)
            return
        }
        val profileId = sel?.profileId ?: sshSessionManager.getSession(sessionId)?.profileId ?: return
        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                sshSessionManager.setBypassSessionManager(sessionId, true)
                sshSessionManager.setChosenSessionName(sessionId, "shell")
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
            // "Create new session" → null sessionName. Generate a unique name
            // here (matching the SSH path below) so each new-session click
            // really creates a new session instead of attaching multiple
            // participants to a session named after the connection. (#113)
            val effectiveName = sessionName ?: generateUniqueSessionName(
                moshSessionManager.sessions.value[sessionId]?.label ?: sessionId.take(8),
                sel.sessionNames,
            )
            viewModelScope.launch {
                _connectingProfileId.value = profileId
                try {
                    finishMoshConnect(sessionId, profileId, serverHost, client, sel.manager, effectiveName, verboseLogger = pendingLogger)
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
            // Same fix as Mosh above: generate a unique session name when the
            // user picks "Create new session" so each click really creates a
            // new session rather than re-attaching to one named after the
            // connection. (#113)
            val effectiveName = sessionName ?: generateUniqueSessionName(
                etSessionManager.sessions.value[sessionId]?.label ?: sessionId.take(8),
                sel.sessionNames,
            )
            viewModelScope.launch {
                _connectingProfileId.value = profileId
                try {
                    finishEtConnect(sessionId, profile, client, sel.manager, effectiveName, verboseLogger = pendingLogger)
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
                val renameSucceeded = renameResult.exitStatus == 0
                if (!renameSucceeded) {
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
                if (renameSucceeded) {
                    propagateSessionRename(sel.profileId, oldName, newName)
                }
                val newPrev = renameInList(sel.previousSessionNames, oldName, newName)
                _sessionSelection.value = sel.copy(
                    sessionNames = updated,
                    previousSessionNames = newPrev,
                )
            } catch (e: Exception) {
                _error.value = "Failed to rename session: ${e.message}"
            }
        }
    }

    /**
     * After a successful remote rename, update the cached session name everywhere
     * Haven still references the old name: the profile's stored `lastSessionName`
     * (used for restore-on-reconnect), and any live SSH session record on this
     * profile whose `chosenSessionName` matches. Tab labels follow via syncSessions.
     */
    private suspend fun propagateSessionRename(profileId: String, oldName: String, newName: String) {
        val oldSan = sanitizeSessionName(oldName)
        val newSan = sanitizeSessionName(newName)
        if (oldSan == newSan) return
        val profile = repository.getById(profileId) ?: return
        profile.lastSessionName?.takeIf { it.isNotBlank() }?.let { current ->
            val parts = current.split("|")
            if (oldSan in parts) {
                val updated = parts.map { if (it == oldSan) newSan else it }.joinToString("|")
                if (updated != current) repository.save(profile.copy(lastSessionName = updated))
            }
        }
        sshSessionManager.sessions.value.values
            .filter { it.profileId == profileId && sanitizeSessionName(it.chosenSessionName ?: "") == oldSan }
            .forEach { sshSessionManager.setChosenSessionName(it.sessionId, newName) }
    }

    private fun renameInList(names: List<String>, oldName: String, newName: String): List<String> {
        val oldSan = sanitizeSessionName(oldName)
        return names.map { if (sanitizeSessionName(it) == oldSan) newName else it }
    }

    private fun sanitizeSessionName(name: String): String =
        SessionManager.sanitizeSessionName(name)

    /**
     * Generate a session name that doesn't conflict with existing remote sessions.
     * Appends "-2", "-3", etc. if the base name is already taken.
     */
    private fun generateUniqueSessionName(label: String, remoteNames: List<String>): String {
        val base = sanitizeSessionName(label)
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
     * Returns Pair(sessionId, reused) — reused=true if an existing session was used.
     */
    private suspend fun connectJumpHost(
        jumpProfileId: String,
        password: String,
        tunnelOwnerProfileId: String? = null,
    ): Pair<String, Boolean> {
        // Reuse existing connected session for this jump profile
        val existing = sshSessionManager.getSessionsForProfile(jumpProfileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
        if (existing != null) {
            Log.d(TAG, "Reusing existing jump host session ${existing.sessionId}")
            // Reused session might have been opened directly by the user
            // (terminal) — record the new dependent without flipping the
            // tunnelOpened flag, so disconnecting the dependent later won't
            // also tear down a session the user wants kept alive.
            if (tunnelOwnerProfileId != null) {
                sshSessionManager.attachTunnelDependent(existing.sessionId, tunnelOwnerProfileId)
            }
            return existing.sessionId to true
        }

        val jumpProfile = repository.getById(jumpProfileId)
            ?: throw Exception("Jump host profile not found")

        // Auto-connect paths (VNC/RDP/SMB-over-SSH-tunnel) call us with an
        // empty password — they don't have one to pass. Fall back to the
        // jump-host profile's saved sshPassword so password-auth jumps
        // actually succeed instead of bouncing off "Auth cancel for methods
        // public key,password" (#121, KoriKraut). Explicit SSH-side jump
        // chains keep the user-typed password they passed in.
        val effectivePassword = if (password.isEmpty()) jumpProfile.sshPassword ?: "" else password

        Log.d(TAG, "Auto-connecting jump host: ${jumpProfile.label} (${jumpProfile.host}:${jumpProfile.port})")
        // Wire the FIDO authenticator before connect — a jump host with a
        // security-key (ed25519-sk) auth method (e.g. SPICE/VNC/RDP-over-SSH)
        // otherwise fails instantly with "no FidoAuthenticator configured on
        // this SshClient", never reaching the touch prompt (#286).
        val jumpClient = SshClient().apply {
            fidoAuthenticator = this@ConnectionsViewModel.fidoAuthenticator
        }
        val jumpSessionId = sshSessionManager.registerSession(jumpProfileId, "Jump: ${jumpProfile.label}", jumpClient)

        try {
            withContext(Dispatchers.IO) {
                // Plural resolver so a jump host (the SSH leg under SPICE/VNC/RDP)
                // honours its full auth-methods spec — "Any hardware key", AND
                // chains, etc. — not just the legacy single keyId. Falls back to
                // the singular path for simple/legacy profiles. (#286)
                val authMethod = resolveAuthMethods(jumpProfile, effectivePassword)
                val config = ConnectionConfig(
                    host = jumpProfile.host,
                    port = jumpProfile.port,
                    username = jumpProfile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(jumpProfile.sshOptions),
                    addressFamily = jumpProfile.addressFamilyForSsh,
                    reconnectPolicy = jumpProfile.reconnectPolicy,
                )
                Log.d(TAG, "Jump host SSH connecting...")
                try {
                    val hostKeyEntry = jumpClient.connect(
                        config,
                        keyboardInteractivePrompter = keyboardInteractivePrompter,
                        totpCodeProvider = buildTotpCodeProvider(jumpProfile),
                        confirmOtp = jumpProfile.totpConfirmBeforeSend,
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
            // Newly-opened tunnel session: bind it to its owner so the
            // session is torn down when the owner disconnects (#121).
            if (tunnelOwnerProfileId != null) {
                sshSessionManager.markTunnelOpened(jumpSessionId, tunnelOwnerProfileId)
            }
            return jumpSessionId to false
        } catch (e: Exception) {
            // Auth failure (or anything else) used to leave the registered
            // session orphaned in the active-sessions list — undismissable,
            // and one accumulates per retry (#121, KoriKraut: "5 active
            // sessions"). Mark it ERROR and unregister before re-throwing.
            Log.e(TAG, "Jump host connect failed for ${jumpProfile.label}: ${e.message}", e)
            sshSessionManager.updateStatus(jumpSessionId, SshSessionManager.SessionState.Status.ERROR)
            sshSessionManager.removeSession(jumpSessionId)
            throw e
        }
    }

    private suspend fun finishConnect(sessionId: String, profileId: String, verboseLog: String? = pendingVerboseLogs.remove(sessionId), silent: Boolean = false): Boolean {
        // Tunnel-only profiles (#150 Phase B): bring up the transport,
        // register port forwards, but skip shell allocation and the
        // terminal navigation. Session lives in the background just
        // for its forwards — autossh-style when paired with the
        // auto-reconnect policy from Phase A.
        val tunnelOnly = repository.getById(profileId)?.tunnelOnly == true

        // Open the shell and AWAIT a definitive outcome — mark CONNECTED /
        // navigate only on a confirmed live terminal. A shell that closes
        // immediately (no session manager on the host) is reported here, not
        // discovered later by a timer, so the user gets a clear error and is
        // never stranded on the wrong tab. Tunnel-only sessions have no shell.
        if (!tunnelOnly) {
            val outcome = withContext(Dispatchers.IO) {
                sshSessionManager.openShellAndAwaitReady(sessionId)
            }
            when (outcome) {
                is sh.haven.core.ssh.ShellOutcome.Ready -> { /* proceed below */ }
                is sh.haven.core.ssh.ShellOutcome.ShellClosed -> {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = "shell closed (exit ${outcome.exitStatus})", verboseLog = verboseLog)
                    sshSessionManager.removeSession(sessionId)
                    userMessageBus.error("Shell closed — is your session manager (tmux/zellij/screen) installed on this host?")
                    return false
                }
                is sh.haven.core.ssh.ShellOutcome.Failed -> {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = outcome.reason, verboseLog = verboseLog)
                    sshSessionManager.removeSession(sessionId)
                    userMessageBus.error(outcome.reason.ifBlank { "Failed to open the remote shell" })
                    return false
                }
            }
        }

        // Apply enabled port forward rules (best-effort; not gated on shell health).
        // Skipped for connection-reuse sessions — the primary session already
        // holds these forwards on the shared SSH client, so re-binding them here
        // only produces "port already registered" failures.
        if (!sshSessionManager.isClientShared(sessionId)) {
            withContext(Dispatchers.IO) {
                val mcpEndpointId = preferencesRepository.mcpTunnelEndpointProfileId.first()
                val rules = portForwardRepository.getEnabledForProfile(profileId)
                    // One :8730 owner per host: when this profile is the designated
                    // always-on (headless) MCP endpoint, the dedicated McpTunnelManager
                    // tunnel owns the -R 8730 — skip the per-connection rule so the two
                    // don't collide on the remote sshd bind.
                    .filterNot { mcpEndpointId == profileId && it.isMcpReverseTunnel() }
                if (rules.isNotEmpty()) {
                    sshSessionManager.applyPortForwards(
                        sessionId,
                        rules.map { it.toForwardInfo() },
                    )
                }
            }
        }
        sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
        repository.markConnected(profileId)
        // USB/IP auto-forward (Slice 1): if this profile pins a USB device, export
        // it and attach it on the remote. Off the connect path and best-effort —
        // it never blocks or fails the connection.
        viewModelScope.launch(Dispatchers.IO) {
            val profile = repository.getById(profileId) ?: return@launch
            val vidPid = profile.usbForwardVidPid
            if (vidPid.isNullOrBlank() || !profile.isSsh) return@launch
            if (sshSessionManager.getSession(sessionId) == null) return@launch
            usbipForwarder.attach(sessionId, vidPid) { msg ->
                userMessageBus.emit(
                    sh.haven.core.data.message.UserMessage(msg, sh.haven.core.data.message.UserMessage.Severity.INFO),
                )
            }?.let { usbForwardHandles[sessionId] = it }
        }
        // Persist all open session names for this profile (pipe-delimited)
        sshSessionManager.getSession(sessionId)?.let { session ->
            if (session.sessionManager != SessionManager.NONE) {
                // Collect session names from all sessions for this profile
                val allNames = sshSessionManager.sessions.value.values
                    .filter { it.profileId == profileId && it.chosenSessionName != null }
                    .map { sanitizeSessionName(it.chosenSessionName!!) }
                    .toMutableList()
                // Add the current session if not already included
                val currentName =
                    sanitizeSessionName(session.chosenSessionName ?: session.label ?: sessionId.take(8))
                if (currentName !in allNames) allNames.add(currentName)
                repository.getById(profileId)?.let { profile ->
                    repository.save(profile.copy(lastSessionName = allNames.joinToString("|")))
                }
            }
        }
        val authDetail = sshSessionManager.getConnectionConfigForProfile(profileId)?.first?.let { config ->
            fun label(m: ConnectionConfig.AuthMethod): String = when (m) {
                is ConnectionConfig.AuthMethod.Password -> "password"
                is ConnectionConfig.AuthMethod.PrivateKey -> "key"
                is ConnectionConfig.AuthMethod.PrivateKeys -> "key"
                is ConnectionConfig.AuthMethod.FidoKey -> "FIDO2"
                is ConnectionConfig.AuthMethod.Multi -> m.methods.joinToString("+") { label(it) }
            }
            label(config.authMethod)
        }
        connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, details = authDetail, verboseLog = verboseLog)
        startForegroundServiceIfNeeded()
        if (!silent && !tunnelOnly) {
            // Shell confirmed live above, so a terminal tab will materialise —
            // safe to navigate. No optimistic-navigate + recovery poll.
            _navigateToTerminal.value = profileId
        }
        return true
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
            val profile = repository.getById(profileId)
            val customMoshCmd = profile?.moshServerCommand?.takeIf { it.isNotBlank() }
            val moshCmd = customMoshCmd ?: "mosh-server new -s -c 256 -l LANG=en_US.UTF-8"

            // If the user chose "new session on reconnect", kill orphaned
            // mosh-server processes before starting a fresh one. This
            // prevents zombie accumulation when roaming across networks
            // and not reattaching to the old session.
            if (profile?.moshReconnectToExisting == false) {
                try {
                    val killResult = client.execCommand(
                        "pkill -U \$(id -u) mosh-server 2>/dev/null; echo done"
                    )
                    Log.d(TAG, "Killed old mosh-server(s): ${killResult.stdout.trim()}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to kill old mosh-server (non-fatal): ${e.message}")
                }
            }

            Log.d(TAG, "Running mosh-server bootstrap: $moshCmd")
            val result = client.execCommand(moshCmd)

            // Keep SSH client alive for SFTP — don't disconnect

            val connectLine = (result.stdout + "\n" + result.stderr)
                .lines()
                .firstOrNull { it.startsWith("MOSH CONNECT") }
                ?: run {
                    client.disconnect()
                    val stderr = result.stderr.trim()
                    val out = result.stdout.trim()
                    // Distinguish a genuinely-missing binary from one that's
                    // installed but failed to start (most commonly a non-UTF-8
                    // locale in the non-interactive SSH exec environment, which
                    // mosh-server refuses to run under). Misreporting the latter
                    // as "not installed" hid the real cause (#297).
                    if (moshServerLooksMissing(result.exitStatus, stderr)) {
                        throw Exception(
                            "mosh-server not found. Install it on the remote host " +
                                "(e.g. apt install mosh)."
                        )
                    }
                    val detail = stderr.ifBlank { out }.take(300)
                        .ifBlank { "(no output; mosh-server exited ${result.exitStatus})" }
                    val hint = moshLocaleWorkaroundHint(customMoshCmd != null, stderr)
                    throw Exception("mosh-server failed to start:\n$detail$hint")
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
            val sanitized = sanitizeSessionName(rawName)
            effectiveSessionName = sanitized
            moshSessionManager.setInitialCommand(sessionId, smCmd(sanitized))
        }

        val transportLogBuffer = if (verboseLogger != null) java.util.concurrent.ConcurrentLinkedQueue<String>() else null

        // Mosh-over-tunnel (issue #164): when the profile selects a
        // WireGuard or Tailscale tunnel, route the UDP socket through
        // it. udpSocketSupplier returns null for direct profiles or
        // for backends that can't carry UDP (Cloudflare Access,
        // legacy SOCKS) — in which case Mosh falls through to a raw
        // DatagramSocket exactly as it did before this change.
        val socketProvider: sh.haven.mosh.network.UdpSocketProvider? = run {
            val profile = repository.getById(profileId) ?: return@run null
            val supplier = tunnelResolver.udpSocketSupplier(profile) ?: return@run null
            sh.haven.mosh.network.UdpSocketProvider {
                sh.haven.feature.connections.mosh.TunneledUdpAdapter(supplier())
            }
        }

        // Resolve per-profile session-dead timeout
        val profile = repository.getById(profileId)
        val sessionDeadMs: Long? = profile?.moshSessionTimeoutSec?.let { sec ->
            if (sec > 0) sec * 1000L else null
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
                verboseBuffer = transportLogBuffer,
                socketProvider = socketProvider,
                sessionDeadMs = sessionDeadMs,
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
            val sanitized = sanitizeSessionName(rawName)
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
    /**
     * Resolve the profile's ordered [ConnectionProfile.authMethodSpecs] into a
     * connect-time auth method (#166). One method → that method (preserving the
     * pre-#166 single-method behaviour exactly); several → a
     * [ConnectionConfig.AuthMethod.Multi] so a `publickey,password`-style chain
     * completes in one attempt. Keyboard-interactive specs carry no credential
     * (the prompter handles them live), so they don't add to the list.
     */
    private suspend fun resolveAuthMethods(
        profile: ConnectionProfile,
        password: String,
    ): ConnectionConfig.AuthMethod {
        // "Use password only" overrides any key element in the chain — keys are
        // never offered (#121). resolveAuthMethod enforces the same for single
        // specs and the legacy/explicit-key callers.
        if (profile.ignoreSavedKeys) return ConnectionConfig.AuthMethod.Password(password)
        val specs = profile.authMethodSpecs
        // A single explicit-key spec must be honoured via the SPEC's keyId.
        // The legacy resolveAuthMethod reads profile.keyId, which can be out of
        // sync with the spec — e.g. a profile pinned via MCP authMethods, or a
        // UI save that didn't mirror the spec to the legacy field. When unsynced
        // (keyId null) it silently fell back to "any saved key" and ignored the
        // pin, so a connection set to require one specific key authenticated
        // with whatever other key the server accepted.
        (specs.singleOrNull() as? ConnectionProfile.AuthMethodSpec.Key)?.keyId?.let { kid ->
            resolveExplicitKey(kid, password)?.let { return it }
        }
        // A lone "Any hardware key" must resolve to the FIDO pool, not fall to
        // resolveAuthMethod (which only knows password / any-software-key).
        if (specs.singleOrNull() is ConnectionProfile.AuthMethodSpec.AnyHardwareKey) {
            return resolveAnyHardwareKeys() ?: ConnectionConfig.AuthMethod.Password(password)
        }
        if (specs.size <= 1) return resolveAuthMethod(profile, password)

        val methods = specs.mapNotNull { spec ->
            when (spec) {
                is ConnectionProfile.AuthMethodSpec.Password ->
                    ConnectionConfig.AuthMethod.Password(password)
                is ConnectionProfile.AuthMethodSpec.Key ->
                    if (spec.keyId != null) resolveExplicitKey(spec.keyId!!, password)
                    else resolveAnyUnencryptedKeys()
                ConnectionProfile.AuthMethodSpec.AnyHardwareKey -> resolveAnyHardwareKeys()
                ConnectionProfile.AuthMethodSpec.KeyboardInteractive -> null
                // TOTP is auto-filled into the live keyboard-interactive
                // round (#178), not registered as a JSch credential.
                is ConnectionProfile.AuthMethodSpec.Totp -> null
                // ProtonSrp is an EMAIL-only method handled by the Go mailbridge,
                // never part of a JSch/SSH auth chain.
                ConnectionProfile.AuthMethodSpec.ProtonSrp -> null
            }
        }
        return when {
            methods.isEmpty() -> ConnectionConfig.AuthMethod.Password(password)
            methods.size == 1 -> methods.single()
            else -> ConnectionConfig.AuthMethod.Multi(methods)
        }
    }

    /**
     * Build a live TOTP code provider for [profile] if its auth chain
     * carries a `TOTP` element with a resolvable stored secret (#178).
     * The secret is decrypted once here and captured in the closure; the
     * code itself is generated on each invocation so it's current for the
     * window the server validates. Returns null when the profile has no
     * TOTP element or the referenced secret is missing/undecryptable —
     * the keyboard-interactive prompt then falls through to the dialog.
     */
    private suspend fun buildTotpCodeProvider(profile: ConnectionProfile): (() -> String)? {
        val totpSpec = profile.authMethodSpecs
            .filterIsInstance<ConnectionProfile.AuthMethodSpec.Totp>()
            .firstOrNull() ?: return null
        // Explicit id, else the single stored secret if there's exactly one.
        val specId = totpSpec.secretId
        val secret = when {
            !specId.isNullOrEmpty() -> totpSecretRepository.getById(specId)
            else -> totpSecretRepository.getAll().singleOrNull()
        } ?: return null
        val plain = totpSecretRepository.getDecryptedSecret(secret.id) ?: return null
        val algorithm = runCatching { sh.haven.core.security.Totp.Algorithm.valueOf(secret.algorithm) }
            .getOrDefault(sh.haven.core.security.Totp.Algorithm.SHA1)
        return {
            sh.haven.core.security.Totp.generate(
                secretBase32 = plain,
                algorithm = algorithm,
                digits = secret.digits,
                periodSeconds = secret.periodSeconds,
            )
        }
    }

    private suspend fun resolveAuthMethod(
        profile: ConnectionProfile,
        password: String,
    ): ConnectionConfig.AuthMethod {
        // "Use password only" — never offer keys (explicit or keystore), so a
        // password-only server gets the password prompt instead of failed key
        // attempts (#121).
        if (profile.ignoreSavedKeys) {
            return ConnectionConfig.AuthMethod.Password(password)
        }

        // Profile has an explicit key assigned
        val keyId = profile.keyId
        if (keyId != null) {
            resolveExplicitKey(keyId, password)?.let { return it }
        }

        // No explicit key but keys are available — try every key the
        // server might accept.
        if (password.isEmpty()) {
            resolveAnyUnencryptedKeys()?.let { return it }
        }

        return ConnectionConfig.AuthMethod.Password(password)
    }

    /**
     * Resolve a specific saved key into an auth method, running the
     * cert-renewal gate and handling FIDO2 / encrypted keys. Returns null
     * if the key is missing or its bytes can't be decrypted (denied
     * biometric, etc.) — callers fall through. [password] is used as the
     * passphrase for an encrypted key.
     */
    private suspend fun resolveExplicitKey(
        keyId: String,
        password: String,
    ): ConnectionConfig.AuthMethod? {
        // Fetch row metadata first (no biometric prompt yet) so the
        // cert-renewal gate can run before we trigger the biometric prompt
        // and the JSch connect. (#133 phase 2b)
        val originalKey = sshKeyRepository.getById(keyId)
        val key = if (originalKey != null) certRenewalGate.ensureFresh(originalKey) else null
        val keyBytes = if (key != null) sshKeyRepository.getDecryptedKeyBytes(keyId) else null
        if (keyBytes != null && key != null) {
            // Cert is public material so the same fetch works for both
            // software and FIDO2 paths; null = plain pubkey auth.
            val certBytes = sshKeyRepository.getCertificateBytes(keyId)
            if (key.keyType.startsWith("sk-")) {
                Log.d(TAG, "Using FIDO2 SK key: ${key.keyType}" +
                    if (certBytes != null) " (with certificate)" else "")
                return ConnectionConfig.AuthMethod.FidoKey(
                    skKeyData = keyBytes,
                    certBytes = certBytes,
                    keyLabel = key.label,
                )
            }
            // For encrypted keys, pass the original encrypted bytes + passphrase.
            // JSch decrypts at auth time — key never stored in plaintext.
            // When the caller supplied no passphrase, fall back to the opt-in
            // per-key stored passphrase (#290) so one encrypted key works across
            // all profiles without prompting. A wrong/stale stored value just
            // fails auth and surfaces the existing fallback prompt.
            val effectivePassword = if (key.isEncrypted && password.isBlank()) {
                sshKeyRepository.getStoredPassphrase(keyId).orEmpty()
            } else {
                password
            }
            val passphrase = if (key.isEncrypted) effectivePassword.toCharArray() else CharArray(0)
            return ConnectionConfig.AuthMethod.PrivateKey(
                keyBytes = if (key.isEncrypted) keyBytes else rawKeyToPem(keyBytes, key.keyType),
                passphrase = passphrase,
                certificateBytes = certBytes,
            )
        }
        return null
    }

    /**
     * Every stored unencrypted key as a [ConnectionConfig.AuthMethod.PrivateKeys]
     * "try them all" bundle, or null if none. Encrypted keys are skipped (no
     * passphrase here); biometric-protected keys are included (each prompts via
     * its keystore gate).
     *
     * FIDO2/SK keys (`sk-ssh-ed25519@openssh.com` etc.) are excluded: their
     * `privateKeyBytes` hold a credential handle, not loadable private-key
     * material, so PEM-encoding one throws "Invalid Key" — and a hardware key
     * shouldn't be silently tried (with a touch prompt) on every connect. SK
     * keys are used only when explicitly assigned to a profile, where
     * [resolveExplicitKey] resolves them to a [ConnectionConfig.AuthMethod.FidoKey].
     */
    private suspend fun resolveAnyUnencryptedKeys(): ConnectionConfig.AuthMethod? {
        val keys = sshKeyRepository.getAllDecrypted()
            .filter { !it.isEncrypted && !it.keyType.startsWith("sk-") && it.enabledForAuth }
        if (keys.isEmpty()) return null
        return ConnectionConfig.AuthMethod.PrivateKeys(
            keys = keys.map { key ->
                ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                    label = key.label,
                    keyBytes = rawKeyToPem(key.privateKeyBytes, key.keyType),
                    // Carry the attached cert so a CA-only server accepts this
                    // key even without an explicit profile assignment. (#185)
                    certificateBytes = key.certificateBytes,
                )
            },
        )
    }

    /**
     * Every enrolled hardware/FIDO (`sk-*`) key as an either-of pool — each a
     * [ConnectionConfig.AuthMethod.FidoKey] with `anyOf = true`, so [SshClient]
     * asks the user to present whichever one they have and offers only that
     * (#237). Backs the "Any hardware key" auth option. Null if none enrolled.
     * Unlike [resolveExplicitKey] (which yields a single required key) and
     * unlike pinning/listing keys (require-all), this is convenience OR-auth.
     * sk-key bytes are a credential handle, not loadable material, so they're
     * passed verbatim (no PEM wrap, unlike [resolveAnyUnencryptedKeys]).
     */
    private suspend fun resolveAnyHardwareKeys(): ConnectionConfig.AuthMethod? {
        val keys = sshKeyRepository.getAllDecrypted()
            .filter { it.keyType.startsWith("sk-") && it.enabledForAuth }
        if (keys.isEmpty()) return null
        val fidoKeys = keys.map { key ->
            ConnectionConfig.AuthMethod.FidoKey(
                skKeyData = key.privateKeyBytes,
                certBytes = key.certificateBytes,
                keyLabel = key.label,
                anyOf = true,
            )
        }
        return if (fidoKeys.size == 1) fidoKeys.single()
        else ConnectionConfig.AuthMethod.Multi(fidoKeys)
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
        // Exclude SK/FIDO keys: their bytes are a credential handle (rawKeyToPem
        // would throw "Invalid Key"), and a hardware key can't be forwarded as a
        // software agent identity anyway.
        val usable = allKeys.filter { !it.isEncrypted && !it.keyType.startsWith("sk-") && it.enabledForAuth }
        return AgentIdentitiesResult(
            keys = usable.map { key -> key.label to rawKeyToPem(key.privateKeyBytes, key.keyType) },
            skippedEncryptedCount = allKeys.count { it.isEncrypted },
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
        if (sshSessionManager.isLiveTerminal(session.sessionId)) {
            // Shell open AND a terminal is attached — navigate directly. (A
            // dead/absent channel falls through to (re)open below, rather than
            // navigating to a tab that will never appear.)
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

    /**
     * Identifies the MCP reverse-tunnel rule: a `-R` forward mapping
     * [MCP_REVERSE_TUNNEL_PORT] on the server back to the same port on
     * the phone's loopback.
     */
    private fun PortForwardRule.isMcpReverseTunnel(): Boolean =
        type == PortForwardRule.Type.REMOTE &&
            bindPort == MCP_REVERSE_TUNNEL_PORT &&
            targetPort == MCP_REVERSE_TUNNEL_PORT &&
            targetHost == "127.0.0.1"

    /** True if [profileId] carries the MCP reverse-tunnel port-forward rule. */
    suspend fun hasMcpReverseTunnel(profileId: String): Boolean =
        portForwardRepository.observeForProfile(profileId).first()
            .any { it.isMcpReverseTunnel() }

    /**
     * Bring [profileId]'s MCP reverse-tunnel rule in line with [enabled]:
     * create the `-R 8730:127.0.0.1:8730` rule when enabling and it's
     * missing, delete it when disabling. Reuses [savePortForwardRule] /
     * [deletePortForwardRule] so a connected session is updated live.
     */
    fun reconcileMcpReverseTunnel(profileId: String, enabled: Boolean) {
        viewModelScope.launch {
            val existing = portForwardRepository.observeForProfile(profileId).first()
                .firstOrNull { it.isMcpReverseTunnel() }
            when {
                enabled && existing == null -> savePortForwardRule(
                    PortForwardRule(
                        profileId = profileId,
                        type = PortForwardRule.Type.REMOTE,
                        bindAddress = "127.0.0.1",
                        bindPort = MCP_REVERSE_TUNNEL_PORT,
                        targetHost = "127.0.0.1",
                        targetPort = MCP_REVERSE_TUNNEL_PORT,
                        enabled = true,
                    ),
                )
                !enabled && existing != null ->
                    deletePortForwardRule(existing.id, profileId)
            }
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
        // Tear down any USB/IP auto-forward this profile holds before the SSH
        // client goes away (Slice 1). Best-effort; the remote device also detaches
        // when the forward's socket closes.
        sshSessionManager.getSessionsForProfile(profileId).forEach { session ->
            usbForwardHandles.remove(session.sessionId)?.let { handle ->
                viewModelScope.launch(Dispatchers.IO) {
                    usbipForwarder.teardown(session.sessionId, handle) { msg ->
                        userMessageBus.emit(
                            sh.haven.core.data.message.UserMessage(msg, sh.haven.core.data.message.UserMessage.Severity.INFO),
                        )
                    }
                }
            }
        }
        sessionManagerRegistry.disconnectProfile(profileId)
        // Tear down this profile's tunnel-dependent resources: close its VNC/
        // RDP Desktop tab (via the lease's parent-gone callback) AND release
        // its SSH dependent. Closing the tab here is required because when the
        // carrying SSH is a shared / user-opened session (e.g. a terminal the
        // VNC reused) it legitimately survives, so the SSH-teardown cascade
        // alone would leave the tab open over a now-detached tunnel (#121).
        sshSessionManager.teardownTunnelDependent(profileId)
        // Drop the WireGuard / Tailscale refcount this profile may have
        // acquired via TunnelResolver. No-op if the profile didn't go
        // through a tunnel; otherwise the underlying tunnel closes only
        // when its last dependent disconnects (#149).
        viewModelScope.launch { tunnelResolver.release(profileId) }
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
            // The deploy connection has to honour the profile's "Route through"
            // jump host, WireGuard tunnel, or legacy SOCKS/HTTP proxy — same
            // precedence the regular connect path uses (#143). If we
            // auto-create a jump session here we tear it down on failure to
            // avoid leaving an orphan jump connection behind.
            var autoCreatedJumpSessionId: String? = null
            try {
                // Authenticate the deploy connection with the profile's own
                // configured auth (existing SSH key, key+password chain, …) —
                // not a forced password. This is the ssh-copy-id case: use a
                // credential the server already trusts to append the new key,
                // so deploying to a key-only / YubiKey-only server works. The
                // password is only consumed if the profile's chain includes a
                // password element (or for a key passphrase / jump host).
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = resolveAuthMethods(profile, password),
                )
                val proxy = withContext(Dispatchers.IO) {
                    resolveDeployProxy(profile, password) { autoCreated ->
                        autoCreatedJumpSessionId = autoCreated
                    }
                }
                try {
                    val hostKeyEntry = client.connect(
                        config,
                        proxy = proxy,
                        preConnect = buildKnockHook(profile, verboseLogger = null),
                    )
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
                autoCreatedJumpSessionId?.let { sshSessionManager.removeSession(it) }
            } finally {
                client.disconnect()
            }
        }
    }

    /**
     * Build the proxy chain a deploy / probe connect should use to honour
     * the profile's "Route through" setting. Same precedence as the live
     * connect path: jump host > WireGuard tunnel > legacy SOCKS/HTTP proxy.
     * Reuses an existing connected jump session when one is open, otherwise
     * stands up a new one and reports its session id via [onAutoCreatedJump]
     * so the caller can tear it down if the dependent operation fails.
     */
    private suspend fun resolveDeployProxy(
        profile: ConnectionProfile,
        password: String,
        onAutoCreatedJump: (String) -> Unit,
    ): HavenProxy? {
        val jumpProfileId = profile.jumpProfileId
        if (jumpProfileId != null) {
            val (jumpSessionId, reused) = connectJumpHost(jumpProfileId, password)
            if (!reused) onAutoCreatedJump(jumpSessionId)
            return sshSessionManager.createProxyJump(jumpSessionId)
                ?: throw Exception("Jump host session not usable for tunneling")
        }
        return tunnelResolver.havenProxy(profile)
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
            val dialer = tunnelResolver.socketDialer(profile)
            withContext(Dispatchers.IO) {
                reticulumSessionManager.connectSession(
                    sessionId = sessionId,
                    configDir = configDir,
                    host = profile.reticulumHost,
                    port = profile.reticulumPort,
                    ifacNetname = profile.reticulumNetworkName,
                    ifacNetkey = profile.reticulumPassphrase,
                    socketDialer = dialer,
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
                val authMethod = resolveAuthMethods(profile, password)
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    forwardAgent = profile.forwardAgent,
                        addressFamily = profile.addressFamilyForSsh,
                    agentIdentities = agentIdentitiesFor(profile),
                )
                val proxy = if (jumpSessionId != null) {
                    sshSessionManager.createProxyJump(jumpSessionId)
                        ?: throw Exception("Jump host session not usable for tunneling")
                } else {
                    tunnelResolver.havenProxy(profile)
                }
                val hostKeyEntry = client.connect(
                    config,
                    proxy = proxy,
                    preConnect = buildKnockHook(profile, verboseLogger),
                )

                // Silent TOFU is fail-closed: a background / workspace connect
                // never silently trusts an unknown OR changed host key. Trust is
                // established interactively via the host-key prompt. (#5)
                when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> {
                        client.disconnect()
                        throw Exception(
                            "Unknown host key for ${profile.host} — open this connection from " +
                                "the Connections tab first to verify and trust its host key.",
                        )
                    }
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
                val authMethod = resolveAuthMethods(profile, password)
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    forwardAgent = profile.forwardAgent,
                        addressFamily = profile.addressFamilyForSsh,
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
                    tunnelResolver.havenProxy(profile)
                }
                val hostKeyEntry = sshClient.connect(
                    config,
                    proxy = proxy,
                    preConnect = buildKnockHook(profile, verboseLogger),
                )

                when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> {
                        // Fail closed: don't silently trust an unknown host in a
                        // background/workspace connect; require interactive TOFU. (#5)
                        sshClient.disconnect()
                        throw Exception(
                            "Unknown host key for ${profile.host} — open this connection from " +
                                "the Connections tab first to verify and trust its host key.",
                        )
                    }
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
                val authMethod = resolveAuthMethods(profile, password)
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    forwardAgent = profile.forwardAgent,
                        addressFamily = profile.addressFamilyForSsh,
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
                    tunnelResolver.havenProxy(profile)
                }
                val hostKeyEntry = sshClient.connect(
                    config,
                    proxy = proxy,
                    preConnect = buildKnockHook(profile, verboseLogger),
                )

                when (val result = hostKeyVerifier.verify(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> {
                        // Fail closed: don't silently trust an unknown host in a
                        // background/workspace connect; require interactive TOFU. (#5)
                        sshClient.disconnect()
                        throw Exception(
                            "Unknown host key for ${profile.host} — open this connection from " +
                                "the Connections tab first to verify and trust its host key.",
                        )
                    }
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

/**
 * Is this mosh-server exec failure a genuinely-missing binary (→ show the
 * install guide) rather than an installed-but-failed start (→ surface the
 * real stderr)? (#297)
 *
 * The not-found phrases are matched only on stderr LINES that also mention
 * mosh-server. A bare `contains("No such file")` misclassified the most
 * common startup failure — the locale error `"locale: Cannot set LC_CTYPE to
 * default locale: No such file or directory"` — as "not installed", which
 * re-masked exactly the stderr the v5.60.7 fix was meant to surface (the
 * reporter kept seeing the setup guide on v5.61.0 and v5.68.7).
 *
 * Covered shell wordings, all naming the binary on the same line:
 *   bash: `bash: mosh-server: command not found`
 *   zsh:  `zsh: command not found: mosh-server`
 *   dash: `sh: 1: mosh-server: not found`
 *   exec: `sh: /usr/bin/mosh-server: No such file or directory`
 * plus exit 127, the shell's canonical command-not-found status.
 */
internal fun moshServerLooksMissing(exitStatus: Int, stderr: String): Boolean {
    if (exitStatus == 127) return true
    return stderr.lineSequence().any { line ->
        line.contains("mosh-server", ignoreCase = true) &&
            (
                line.contains("command not found", ignoreCase = true) ||
                    line.contains("No such file", ignoreCase = true) ||
                    line.contains("not found", ignoreCase = true)
                )
    }
}

/**
 * When a mosh-server start fails on a missing UTF-8 locale, append a line
 * pointing at Haven's own workaround: force a locale in the connection's
 * custom mosh-server command, fixing it on-device without touching the server
 * (#297 — the reporter noted the surfaced stderr suggests server-side
 * `locale-gen` but not this in-app option).
 *
 * Returns "" (no hint) unless the failure looks locale-related AND they're on
 * the DEFAULT command — telling someone whose own custom command just failed
 * to "set a custom command" would be wrong, and their command may already
 * carry a locale override that failed for a different reason.
 */
internal fun moshLocaleWorkaroundHint(hasCustomCommand: Boolean, stderr: String): String {
    if (hasCustomCommand || !stderr.contains("locale", ignoreCase = true)) return ""
    return "\n\nOr set this connection's mosh-server command to force a UTF-8 " +
        "locale: LC_ALL=C.UTF-8 mosh-server new -s -c 256"
}

/**
 * The two enums share names by construction (#137) — defined separately
 * because `core/data` can't depend on `core/ssh`. Convert via name lookup.
 */
private val ConnectionProfile.addressFamilyForSsh: ConnectionConfig.AddressFamily
    get() = ConnectionConfig.AddressFamily.valueOf(addressFamilyEnum.name)

/**
 * Per-profile reconnect knobs to a value object the SSH session
 * manager understands. Three columns from the data model collapse
 * into one [ConnectionConfig.ReconnectPolicy] — keeps the connect-
 * config builders one line longer instead of three (#150).
 */
private val ConnectionProfile.reconnectPolicy: ConnectionConfig.ReconnectPolicy
    get() = ConnectionConfig.ReconnectPolicy(
        autoReconnect = autoReconnect,
        maxAttempts = reconnectMaxAttempts,
        onNetworkChange = reconnectOnNetworkChange,
    )
