package sh.haven.feature.connections

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.SshSessionManager

/** Profile group colors — matches TAB_GROUP_COLORS in TerminalScreen. */
private val PROFILE_COLORS = listOf(
    Color(0xFF42A5F5), // blue
    Color(0xFF66BB6A), // green
    Color(0xFFFF7043), // orange
    Color(0xFFAB47BC), // purple
    Color(0xFFFFCA28), // amber
    Color(0xFF26C6DA), // cyan
    Color(0xFFEF5350), // red
    Color(0xFF8D6E63), // brown
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectionsScreen(
    onNavigateToTerminal: (profileId: String) -> Unit = {},
    onNavigateToNewSession: (profileId: String) -> Unit = {},
    // VNC/RDP desktop navigation is collected at the nav-host level
    // (HavenNavHost), not here — so a tab opens regardless of which screen is
    // composed (fixes Retry / connect_profile reliability, #121).
    onNavigateToSmb: (profileId: String) -> Unit = {},
    onNavigateToRclone: (profileId: String) -> Unit = {},
    onNavigateToEmail: (profileId: String) -> Unit = {},
    onNavigateToWayland: () -> Unit = {},
    onNavigateToConnections: () -> Unit = {},
    onNavigateToAgentActivity: () -> Unit = {},
    /**
     * Slot for the Workspaces section, supplied by the host (HavenNavHost
     * provides it via WorkspaceSection). Rendered as the first item in
     * the list so saved workspaces sit above the per-profile rows.
     * Defaults to no-op so existing test/preview call sites keep working.
     */
    workspaceSection: @Composable () -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()
    val identities by viewModel.identities.collectAsState()
    val totpSecrets by viewModel.totpSecrets.collectAsState()
    val tunnelConfigs by viewModel.tunnelConfigs.collectAsState()
    // Installed distros for a LOCAL profile's "open in" picker. Snapshotted
    // for this screen's lifetime — installs are rare and re-entering the
    // screen refreshes it.
    val availableLocalDistros = remember { viewModel.installedLocalDistros() }
    val availableUsbDevices = remember { viewModel.availableUsbDevices() }
    var showTunnelsScreen by remember { mutableStateOf(false) }
    // When the user picks "+ New WireGuard tunnel" from a profile's
    // Route-through dropdown, navigate to the Tunnels screen with the
    // Add dialog auto-opened on that type. null means "Manage tunnels…"
    // (no auto-open). Cloudflare Tunnel used to live here too but is
    // now an inline SSH-profile transport — see #154.
    var pendingTunnelAddType by remember {
        mutableStateOf<sh.haven.core.data.db.entities.TunnelConfigType?>(null)
    }
    // (showDesktopsScreen removed in 3c — Desktops UI moved to top-level Desktop tab.)
    val profileStatuses by viewModel.profileStatuses.collectAsState()
    val mcpExposure by viewModel.mcpExposure.collectAsState()
    val agentActiveProfiles by viewModel.agentActiveProfiles.collectAsState()
    val sessions by viewModel.sessions.collectAsState()

    // Derive profile colors matching terminal tab colors (by session registration order)
    val profileColors = remember(sessions) {
        sessions.values
            .filter {
                it.status == SshSessionManager.SessionState.Status.CONNECTED ||
                    it.status == SshSessionManager.SessionState.Status.RECONNECTING
            }
            .map { it.profileId }
            .distinct()
            .withIndex()
            .associate { (i, id) -> id to PROFILE_COLORS[i % PROFILE_COLORS.size] }
    }
    val discoveredDestinations by viewModel.discoveredDestinations.collectAsState()
    val discoveredHosts by viewModel.discoveredHosts.collectAsState()
    val localVmStatus by viewModel.localVmStatus.collectAsState()
    val showLinuxVmCard by viewModel.showLinuxVmCard.collectAsState()
    val showDesktopsCard by viewModel.showDesktopsCard.collectAsState()
    val connectingProfileId by viewModel.connectingProfileId.collectAsState()
    val launchingDesktop by viewModel.launchingDesktop.collectAsState()
    val error by viewModel.error.collectAsState()
    val warning by viewModel.warning.collectAsState()
    // haven://connect deep link (#305): confirm sheet for a matched profile,
    // and a pre-fill request for the New-Connection editor on no match.
    val connectConfirm by viewModel.connectConfirm.collectAsState()
    val prefillNewConnection by viewModel.prefillNewConnection.collectAsState()
    val navigateToTerminal by viewModel.navigateToTerminal.collectAsState()
    val navigateToSmb by viewModel.navigateToSmb.collectAsState()
    val navigateToRclone by viewModel.navigateToRclone.collectAsState()
    val navigateToEmail by viewModel.navigateToEmail.collectAsState()
    val navigateToWayland by viewModel.navigateToWayland.collectAsState()
    val navigateBackToConnections by viewModel.navigateToConnections.collectAsState()
    val deploySuccess by viewModel.deploySuccess.collectAsState()
    val sessionSelection by viewModel.sessionSelection.collectAsState()
    val passwordFallback by viewModel.passwordFallback.collectAsState()
    val pendingTunnelDependent by viewModel.pendingTunnelDependent.collectAsState()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsState()
    val fidoTouchPrompt by viewModel.fidoTouchPrompt.collectAsState()
    val keyboardInteractiveAuth by viewModel.keyboardInteractiveAuth.collectAsState()
    val globalSessionManagerLabel by viewModel.globalSessionManagerLabel.collectAsState()
    val newSessionProfileId by viewModel.newSessionProfileId.collectAsState()
    val subnetScanning by viewModel.subnetScanning.collectAsState()
    val jumpScanning by viewModel.jumpScanning.collectAsState()
    val jumpScanError by viewModel.jumpScanError.collectAsState()
    val reticulumScanning by viewModel.reticulumScanning.collectAsState()
    val discoveredSmbHosts by viewModel.discoveredSmbHosts.collectAsState()
    val smbSubnetScanning by viewModel.smbSubnetScanning.collectAsState()
    val showMoshSetupGuide by viewModel.showMoshSetupGuide.collectAsState()
    val showMoshClientMissing by viewModel.showMoshClientMissing.collectAsState()
    val desktopSetupState by viewModel.desktopSetupState.collectAsState()
    val desktopStates by viewModel.desktopStates.collectAsState()
    val desktopVncPasswordPrompt by viewModel.desktopVncPasswordPrompt.collectAsState()
    val groupLaunchState by viewModel.groupLaunchState.collectAsState()
    val certRenewing by viewModel.certRenewing.collectAsState()

    LaunchedEffect(navigateToTerminal) {
        navigateToTerminal?.let { profileId ->
            onNavigateToTerminal(profileId)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(navigateToWayland) {
        if (navigateToWayland) {
            onNavigateToWayland()
            viewModel.consumeNavigateToWayland()
        }
    }

    LaunchedEffect(navigateToSmb) {
        navigateToSmb?.let { profileId ->
            onNavigateToSmb(profileId)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(navigateToRclone) {
        navigateToRclone?.let { profileId ->
            onNavigateToRclone(profileId)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(navigateToEmail) {
        navigateToEmail?.let { profileId ->
            onNavigateToEmail(profileId)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(newSessionProfileId) {
        newSessionProfileId?.let { profileId ->
            onNavigateToNewSession(profileId)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(navigateBackToConnections) {
        if (navigateBackToConnections) {
            onNavigateToConnections()
            viewModel.onNavigated()
        }
    }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    // Draft seeding the add dialog when opened from a haven://connect deep
    // link with no saved match (#305). Cleared when the dialog closes so a
    // later manual "Add" isn't pre-filled.
    var prefillDraft by remember { mutableStateOf<ConnectionProfile?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showImportRclone by rememberSaveable { mutableStateOf(false) }
    var showVmSetup by rememberSaveable { mutableStateOf(false) }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingProfile = editingProfileId?.let { id -> connections.firstOrNull { it.id == id } }
    var connectingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deployingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var portForwardProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    // (setupDesktopDe removed in 3c — Desktop install UI moved to top-level Desktop tab.)
    var quickConnectText by rememberSaveable { mutableStateOf("") }
    var filterText by rememberSaveable { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // haven://connect with no saved match (#305): seed a draft and open the
    // New-Connection editor for the user to add auth and save.
    LaunchedEffect(prefillNewConnection) {
        val p = prefillNewConnection ?: return@LaunchedEffect
        prefillDraft = ConnectionProfile(
            label = "",
            host = p.host,
            username = p.username ?: "",
            port = p.port ?: 22,
            connectionType = "SSH",
            useMosh = p.transport == "mosh",
            useEternalTerminal = p.transport == "et",
            sessionManager = if (p.session != null) "TMUX" else null,
        )
        showAddDialog = true
        viewModel.consumePrefillNewConnection()
    }

    LaunchedEffect(error) {
        error?.let {
            // One message, one surface: a snackbar (dismissible, Material).
            // Previously also fired a Toast, which double-bubbled every error
            // — e.g. the "Shell closed — session manager installed?" hint
            // showed twice on close (#182). Connection errors navigate to /
            // surface on this screen, so the snackbar host is visible.
            snackbarHostState.showSnackbar(
                message = it,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            viewModel.dismissError()
        }
    }

    // Non-fatal warnings (e.g. agent-forwarding enabled but all stored
    // keys are encrypted). Surfaced as a snackbar (single surface, like
    // errors — see #182), but the connection itself still proceeds.
    LaunchedEffect(warning) {
        warning?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            viewModel.dismissWarning()
        }
    }

    // Safety net: if a connection attempt spins for more than 20s without resolving
    // to a terminal, error, or password dialog, it's a silent failure. Show a toast
    // and clear the spinner. This catches all unhappy paths: DNS hangs, blocked
    // coroutines, session manager failures, race conditions, etc.
    LaunchedEffect(connectingProfileId) {
        if (connectingProfileId != null) {
            kotlinx.coroutines.delay(20_000)
            // Still spinning after 20s?
            if (viewModel.connectingProfileId.value != null) {
                viewModel.showError("Connection timed out — check host, port, and credentials")
            }
        }
    }

    val keyDeployedMessage = stringResource(R.string.connections_key_deployed)
    LaunchedEffect(deploySuccess) {
        if (deploySuccess) {
            snackbarHostState.showSnackbar(keyDeployedMessage)
            viewModel.dismissDeploySuccess()
        }
    }

    // Request POST_NOTIFICATIONS permission on Android 13+ so the foreground
    // service notification is visible and "Disconnect All" action works.
    val reticulumIdentityHash by viewModel.reticulumIdentityHash.collectAsState()

    // #585: the identity a server whitelists is a private key, so it can only
    // arrive as a file. Any MIME — Reticulum identity files have no registered
    // type and pickers hide what they cannot name.
    val reticulumIdentityPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importReticulumIdentity(it) } }

    LaunchedEffect(Unit) { viewModel.refreshReticulumIdentity() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or denied — either way, foreground service still works */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            )
            if (status != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Gently offer to disable battery optimization so the foreground service
    // (and SSH connections) survive when the app is backgrounded. The check
    // runs once per screen entry: the ViewModel compares the current state
    // against the last observed one, so a ROM quietly re-enabling
    // optimisation (Realme UI does, on app update) re-opens the offer with
    // an explanation instead of staying silent forever (#494).
    var showBatteryDialog by rememberSaveable { mutableStateOf(false) }
    var batteryDropDetected by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Death certificates first (#494): if the last process was killed by
        // the system, say so — the battery dialog below then lands with its
        // context already on screen.
        viewModel.announceRecentKills()
        val pm = context.getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        val action = viewModel.batteryPromptCheck(exempt)
        if (action != BatteryPromptAction.NONE) {
            // If Shizuku is available, silently whitelist without bothering the user
            if (sh.haven.core.local.WaylandSocketHelper.tryDisableBatteryOptimization(context.packageName)) {
                viewModel.dismissBatteryPrompt()
            } else {
                batteryDropDetected = action == BatteryPromptAction.OFFER_DROPPED
                showBatteryDialog = true
            }
        }
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryDialog = false
                viewModel.dismissBatteryPrompt()
            },
            title = { Text(stringResource(R.string.connections_battery_title)) },
            text = {
                Text(
                    stringResource(
                        if (batteryDropDetected) R.string.connections_battery_redropped_message
                        else R.string.connections_battery_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    viewModel.dismissBatteryPrompt()
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.common_allow))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showBatteryDialog = false
                        viewModel.neverAskBatteryPrompt()
                    }) {
                        Text(stringResource(R.string.connections_battery_dont_ask_again))
                    }
                    TextButton(onClick = {
                        showBatteryDialog = false
                        viewModel.dismissBatteryPrompt()
                    }) {
                        Text(stringResource(R.string.common_not_now))
                    }
                }
            },
        )
    }

    // Probe for Sideband and start collecting announces as soon as the
    // Connections tab is shown. Refreshes every 30s to pick up announces
    // arriving over slow LoRa links. Stops when the screen is disposed.
    DisposableEffect(Unit) {
        viewModel.startPeriodicRefresh()
        viewModel.startNetworkDiscovery()
        onDispose {
            viewModel.stopPeriodicRefresh()
            viewModel.stopNetworkDiscovery()
        }
    }

    if (showAddDialog) {
        ConnectionEditDialog(
            prefill = prefillDraft,
            discoveredDestinations = discoveredDestinations,
            discoveredHosts = discoveredHosts,
            discoveredSmbHosts = discoveredSmbHosts,
            sshProfiles = connections,
            groups = groups,
            identities = identities,
            sshKeys = sshKeys,
            totpSecrets = totpSecrets,
            tunnelConfigs = tunnelConfigs,
            availableDistros = availableLocalDistros,
            usbDevices = availableUsbDevices,
            onManageTunnels = { preselect ->
                pendingTunnelAddType = preselect
                showTunnelsScreen = true
            },
            globalSessionManagerLabel = globalSessionManagerLabel,
            subnetScanning = subnetScanning,
            smbSubnetScanning = smbSubnetScanning,
            reticulumScanning = reticulumScanning,
            reticulumIdentityHash = reticulumIdentityHash,
            onImportReticulumIdentity = { reticulumIdentityPicker.launch(arrayOf("*/*")) },
            onScanSubnet = { viewModel.scanSubnet() },
            onScanSubnetSmb = { viewModel.scanSubnetSmb() },
            jumpScanning = jumpScanning,
            jumpScanError = jumpScanError,
            onScanSubnetViaJump = { jumpId -> viewModel.scanSubnetViaJump(jumpId) },
            onScanReticulum = { host, port, netName, passphrase ->
                viewModel.scanReticulumDestinations(host, port, netName, passphrase)
            },
            onTestKnock = { host, sequence, delayMs ->
                viewModel.testKnock(host, sequence, delayMs)
            },
            onTestSpa = { host, config ->
                viewModel.testSpa(host, config)
            },
            onDismiss = {
                showAddDialog = false
                prefillDraft = null
            },
            onSave = { profile, cfTunnel, mcpTunnel ->
                viewModel.saveProfileWithEmbeddedCloudflareTunnel(profile, cfTunnel)
                viewModel.reconcileMcpReverseTunnel(profile.id, mcpTunnel)
                showAddDialog = false
                prefillDraft = null
            },
        )
    }

    // haven://connect matched a saved profile (#305): confirm before
    // connecting, since a BROWSABLE link can be fired by a web page.
    connectConfirm?.let { confirm ->
        val target = confirm.profile.label.ifBlank { confirm.profile.host }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeepLinkConnect() },
            title = { Text(stringResource(R.string.connections_deeplink_confirm_title)) },
            text = {
                Text(
                    if (confirm.sessionName != null) {
                        stringResource(
                            R.string.connections_deeplink_confirm_session,
                            target,
                            confirm.sessionName,
                        )
                    } else {
                        stringResource(R.string.connections_deeplink_confirm_message, target)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeepLinkConnect() }) {
                    Text(stringResource(R.string.connections_deeplink_confirm_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeepLinkConnect() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showImportRclone) {
        ImportRcloneConfigDialog(onDismiss = { showImportRclone = false })
    }

    if (showVmSetup) {
        val linuxVmLabel = stringResource(R.string.connections_linux_vm)
        LinuxVmSetupDialog(
            vmStatus = localVmStatus,
            onConnectSsh = { port ->
                showVmSetup = false
                val existing = connections.find {
                    it.host in listOf("localhost", "127.0.0.1") && it.port == port && it.username == "droid"
                }
                val profile = existing ?: ConnectionProfile(
                    label = linuxVmLabel,
                    host = "localhost",
                    port = port,
                    username = "droid",
                )
                if (existing == null) viewModel.saveConnection(profile)
                connectingProfile = profile
            },
            onConnectSshDirect = { ip, port ->
                showVmSetup = false
                val existing = connections.find {
                    it.host == ip && it.port == port && it.username == "droid"
                }
                val profile = existing ?: ConnectionProfile(
                    label = linuxVmLabel,
                    host = ip,
                    port = port,
                    username = "droid",
                )
                if (existing == null) viewModel.saveConnection(profile)
                connectingProfile = profile
            },
            onConnectVnc = { port ->
                showVmSetup = false
                val sshPort = localVmStatus.sshPort ?: 8022
                val existing = connections.find {
                    it.host in listOf("localhost", "127.0.0.1") && it.port == sshPort && it.username == "droid"
                }
                val profile = (existing?.copy(vncPort = port, vncSshForward = false))
                    ?: ConnectionProfile(
                        label = linuxVmLabel,
                        host = "localhost",
                        port = sshPort,
                        username = "droid",
                        vncPort = port,
                        vncSshForward = false,
                    )
                viewModel.saveConnection(profile)
                connectingProfile = profile
            },
            onConnectVncDirect = { ip, port ->
                showVmSetup = false
                val existing = connections.find {
                    it.host == ip && it.username == "droid"
                }
                val sshPort = localVmStatus.directSshPort ?: 22
                val profile = (existing?.copy(vncPort = port, vncSshForward = false))
                    ?: ConnectionProfile(
                        label = linuxVmLabel,
                        host = ip,
                        port = sshPort,
                        username = "droid",
                        vncPort = port,
                        vncSshForward = false,
                    )
                viewModel.saveConnection(profile)
                connectingProfile = profile
            },
            onDismiss = { showVmSetup = false },
        )
    }

    editingProfile?.let { profile ->
        // Load the embedded Cloudflare Tunnel (if any) before showing the
        // dialog so the inline transport fields can pre-populate. Loading
        // happens once per profile-edit cycle; null is the "no embedded
        // tunnel" state.
        val embeddedCf = produceState<sh.haven.core.data.db.entities.TunnelConfig?>(
            initialValue = null,
            key1 = profile.id,
        ) {
            value = viewModel.embeddedCloudflareTunnelFor(profile.id)
        }
        val mcpReverseTunnel = produceState(initialValue = false, key1 = profile.id) {
            value = viewModel.hasMcpReverseTunnel(profile.id)
        }
        // #274: stored credential fields are pre-filled with the decrypted
        // secret, so gate the eye-reveal behind a biometric prompt.
        val revealTitle = stringResource(R.string.connections_reveal_password_title)
        val revealSubtitle = stringResource(R.string.connections_reveal_password_subtitle)
        ConnectionEditDialog(
            existing = profile,
            onRevealSavedSecret = { viewModel.authToRevealPassword(revealTitle, revealSubtitle) },
            discoveredDestinations = discoveredDestinations,
            discoveredHosts = discoveredHosts,
            discoveredSmbHosts = discoveredSmbHosts,
            sshProfiles = connections,
            groups = groups,
            identities = identities,
            sshKeys = sshKeys,
            totpSecrets = totpSecrets,
            tunnelConfigs = tunnelConfigs,
            availableDistros = availableLocalDistros,
            usbDevices = availableUsbDevices,
            embeddedCloudflareTunnel = embeddedCf.value,
            mcpReverseTunnelEnabled = mcpReverseTunnel.value,
            onManageTunnels = { preselect ->
                pendingTunnelAddType = preselect
                showTunnelsScreen = true
            },
            globalSessionManagerLabel = globalSessionManagerLabel,
            subnetScanning = subnetScanning,
            smbSubnetScanning = smbSubnetScanning,
            reticulumScanning = reticulumScanning,
            reticulumIdentityHash = reticulumIdentityHash,
            onImportReticulumIdentity = { reticulumIdentityPicker.launch(arrayOf("*/*")) },
            onScanSubnet = { viewModel.scanSubnet() },
            onScanSubnetSmb = { viewModel.scanSubnetSmb() },
            jumpScanning = jumpScanning,
            jumpScanError = jumpScanError,
            onScanSubnetViaJump = { jumpId -> viewModel.scanSubnetViaJump(jumpId) },
            onScanReticulum = { host, port, netName, passphrase ->
                viewModel.scanReticulumDestinations(host, port, netName, passphrase)
            },
            onTestKnock = { host, sequence, delayMs ->
                viewModel.testKnock(host, sequence, delayMs)
            },
            onTestSpa = { host, config ->
                viewModel.testSpa(host, config)
            },
            onDismiss = { editingProfileId = null },
            onSave = { updated, cfTunnel, mcpTunnel ->
                viewModel.saveProfileWithEmbeddedCloudflareTunnel(updated, cfTunnel)
                viewModel.reconcileMcpReverseTunnel(updated.id, mcpTunnel)
                editingProfileId = null
            },
        )
    }

    connectingProfile?.let { profile ->
        val assignedKey = profile.keyId?.let { id -> sshKeys.firstOrNull { it.id == id } }
        val mode = when {
            assignedKey != null && assignedKey.isEncrypted ->
                PasswordDialogMode.ASSIGNED_ENCRYPTED_KEY_PASSPHRASE
            assignedKey != null ->
                PasswordDialogMode.PASSWORD_OR_ASSIGNED_KEY
            sshKeys.isNotEmpty() ->
                PasswordDialogMode.PASSWORD_OR_UNASSIGNED_KEY
            else ->
                PasswordDialogMode.PASSWORD_ONLY
        }
        PasswordDialog(
            profile = profile,
            hasKeys = sshKeys.isNotEmpty(),
            mode = mode,
            assignedKeyLabel = assignedKey?.label,
            onDismiss = { connectingProfile = null },
            onConnect = { username, password, rememberPassword ->
                viewModel.connect(
                    profile,
                    password,
                    rememberPassword = rememberPassword,
                    usernameOverride = username,
                )
                connectingProfile = null
            },
        )
    }

    passwordFallback?.let { profile ->
        val assignedKey = profile.keyId?.let { id -> sshKeys.firstOrNull { it.id == id } }
        // If the profile has an encrypted key assigned and the initial
        // key-only connect attempt just failed, this dialog is specifically
        // asking for the key passphrase — not a host password.
        val mode = when {
            assignedKey != null && assignedKey.isEncrypted ->
                PasswordDialogMode.ASSIGNED_ENCRYPTED_KEY_PASSPHRASE
            assignedKey != null ->
                PasswordDialogMode.PASSWORD_OR_ASSIGNED_KEY
            sshKeys.isNotEmpty() ->
                PasswordDialogMode.PASSWORD_OR_UNASSIGNED_KEY
            else ->
                PasswordDialogMode.PASSWORD_ONLY
        }
        PasswordDialog(
            profile = profile,
            hasKeys = sshKeys.isNotEmpty(),
            mode = mode,
            assignedKeyLabel = assignedKey?.label,
            onDismiss = { viewModel.dismissPasswordFallback() },
            onConnect = { username, password, rememberPassword ->
                // Tunnel-mode (#121a) vs normal SSH login + the dismiss are all
                // handled inside answerPasswordFallback, shared with the MCP
                // answer_auth_prompt verb so both paths stay identical.
                viewModel.answerPasswordFallback(
                    password,
                    username = username,
                    rememberPassword = rememberPassword,
                )
            },
        )
    }

    hostKeyPrompt?.let { prompt ->
        when (prompt) {
            is ConnectionsViewModel.HostKeyPrompt.NewHost -> {
                NewHostKeyDialog(
                    entry = prompt.entry,
                    onTrust = { viewModel.onHostKeyAccepted() },
                    onCancel = { viewModel.onHostKeyRejected() },
                )
            }
            is ConnectionsViewModel.HostKeyPrompt.KeyChanged -> {
                KeyChangedDialog(
                    oldFingerprint = prompt.oldFingerprint,
                    entry = prompt.entry,
                    onAccept = { viewModel.onHostKeyAccepted() },
                    onDisconnect = { viewModel.onHostKeyRejected() },
                )
            }
        }
    }

    fidoTouchPrompt?.let { prompt ->
        FidoTouchPromptDialog(prompt = prompt, onCancel = { viewModel.cancelFido() })
    }

    keyboardInteractiveAuth?.let { pending ->
        KeyboardInteractiveDialog(
            challenge = pending.challenge,
            onSubmit = { viewModel.submitKeyboardInteractiveResponses(it) },
            onCancel = { viewModel.cancelKeyboardInteractive() },
        )
    }

    // Rendered as a full-screen overlay Dialog so it's above the profile
    // edit dialog when the user taps "Manage tunnels…" from the picker.
    if (showTunnelsScreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                showTunnelsScreen = false
                pendingTunnelAddType = null
            },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            sh.haven.feature.tunnel.TunnelsScreen(
                onBack = {
                    showTunnelsScreen = false
                    pendingTunnelAddType = null
                },
                initialAddType = pendingTunnelAddType,
            )
        }
    }

    // (Desktops overlay removed in 3c — see app/desktop/DesktopManagerScreen.kt
    //  and the Manage toggle in app/navigation/DesktopScreen.kt.)

    deployingProfile?.let { profile ->
        DeployKeyDialog(
            profile = profile,
            keys = sshKeys,
            onDismiss = { deployingProfile = null },
            onDeploy = { keyId, password ->
                viewModel.deployKey(profile, keyId, password)
                deployingProfile = null
            },
        )
    }

    portForwardProfile?.let { profile ->
        val pfRulesFlow = remember(profile.id) { viewModel.portForwardRules(profile.id) }
        val pfRules by pfRulesFlow.collectAsState()
        val allSessions by viewModel.sessions.collectAsState()
        val activeForwards = allSessions.values
            .filter { it.profileId == profile.id }
            .flatMap { it.activeForwards }

        PortForwardDialog(
            profileLabel = profile.label,
            profileId = profile.id,
            rules = pfRules,
            activeForwards = activeForwards,
            onSave = { rule -> viewModel.savePortForwardRule(rule) },
            onDelete = { ruleId -> viewModel.deletePortForwardRule(ruleId, profile.id) },
            onDismiss = { portForwardProfile = null },
        )
    }

    sessionSelection?.let { selection ->
        sh.haven.core.ui.SessionPickerDialog(
            title = stringResource(R.string.connections_sessions_title, selection.managerLabel),
            sessionNames = selection.sessionNames,
            suggestedNewName = selection.suggestedNewName,
            createButtonContentDescription = stringResource(R.string.connections_new_session_create),
            onSelect = { name -> viewModel.onSessionSelected(selection.sessionId, name) },
            onNewSession = { name ->
                // Empty string is treated as "use the suggestion the ViewModel
                // would generate" — pass null down so the existing fallback
                // path runs. Any non-empty user input goes through as-is.
                viewModel.onSessionSelected(selection.sessionId, name.takeIf { it.isNotBlank() })
            },
            onDismiss = { viewModel.dismissSessionPicker() },
            previousSessionNames = selection.previousSessionNames,
            restorePreviousLabel = if (selection.previousSessionNames.size > 1) {
                stringResource(R.string.connections_restore_previous_sessions, selection.previousSessionNames.size)
            } else null,
            onRestorePrevious = { names -> viewModel.restorePreviousSessions(selection.sessionId, names) },
            canKill = selection.manager.killCommand != null,
            canRename = selection.manager.renameCommand != null,
            killContentDescription = stringResource(R.string.connections_kill_session),
            renameContentDescription = stringResource(R.string.connections_rename_session),
            onKill = { name -> viewModel.killRemoteSession(name) },
            onRename = { old, new -> viewModel.renameRemoteSession(old, new) },
            plainShellLabel = stringResource(R.string.connections_open_plain_shell),
            onPlainShell = { viewModel.onPlainShellSelected(selection.sessionId) },
            cancelLabel = stringResource(R.string.common_cancel),
            renameDialog = { currentLabel, onDismiss, onRenameTo ->
                RenameDialog(currentLabel = currentLabel, onDismiss = onDismiss, onRename = onRenameTo)
            },
        )
    }

    if (showMoshSetupGuide) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissMoshSetupGuide() },
            title = { Text(stringResource(R.string.connections_mosh_not_found_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.connections_mosh_not_found_message))
                    Text(stringResource(R.string.connections_mosh_install_prompt))
                    Text(
                        stringResource(R.string.connections_mosh_install_commands),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.connections_mosh_firewall_note))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uriHandler.openUri("https://github.com/mobile-shell/mosh")
                }) { Text(stringResource(R.string.connections_mosh_github)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMoshSetupGuide() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    if (showMoshClientMissing) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissMoshClientMissing() },
            title = { Text(stringResource(R.string.connections_mosh_client_missing_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.connections_mosh_client_missing_message))
                    Text(stringResource(R.string.connections_mosh_client_build_instructions))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uriHandler.openUri("https://github.com/mobile-shell/mosh")
                }) { Text(stringResource(R.string.connections_mosh_github)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMoshClientMissing() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    // (DesktopSetupDialog rendering removed in 3c — moved to DesktopManagerScreen.kt.)

    // Desktop VNC password prompt — shown when starting a desktop that requires auth
    // but no stored password is available
    desktopVncPasswordPrompt?.let { prompt ->
        var vncPwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDesktopVncPasswordPrompt() },
            title = { Text(stringResource(R.string.connections_vnc_password_title)) },
            text = {
                sh.haven.core.ui.PasswordField(
                    value = vncPwd,
                    onValueChange = { vncPwd = it },
                    label = stringResource(R.string.common_password),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onDesktopVncPasswordEntered(vncPwd) },
                    enabled = vncPwd.isNotBlank(),
                ) { Text(stringResource(R.string.common_connect)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDesktopVncPasswordPrompt() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    var showNewGroupDialog by rememberSaveable { mutableStateOf(false) }

    if (showNewGroupDialog) {
        NewGroupDialog(
            onDismiss = { showNewGroupDialog = false },
            onCreate = { label ->
                viewModel.createGroup(label)
                showNewGroupDialog = false
            },
        )
    }

    Scaffold(
        // Defer to the app Scaffold's background so the global background-opacity
        // (wallpaper see-through) applies here too instead of being covered.
        containerColor = Color.Transparent,
        // contentColorFor(Transparent) has no scheme match and resolves to
        // Unspecified, so any Text without an explicit colour falls back to
        // black — invisible in dark theme. Pin it to onSurface.
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_title)) },
                actions = {
                    // Lit when the MCP transport has been called in the
                    // last 30s; absent entirely until the audit table
                    // has its first row. Tapping jumps to the audit
                    // log. Driven off the same Room table the
                    // AgentActivityScreen reads from.
                    AgentActiveChip(onClick = onNavigateToAgentActivity)
                    // (Local Shell quick-launch icon removed in 3c — local
                    // shells are now a normal Connection +, no special
                    // topbar entrypoint needed.)
                    // (Desktops icon removed in 3c — the install/manage UI
                    // moved to the top-level Desktop tab's Manage toggle.)
                    // Tunnels are connection definitions (per-app
                    // WireGuard configs that other profiles route
                    // through), so they belong on this screen rather
                    // than in app Settings where they used to live.
                    IconButton(onClick = {
                        pendingTunnelAddType = null
                        showTunnelsScreen = true
                    }) {
                        Icon(Icons.Filled.VpnLock, contentDescription = stringResource(R.string.connections_action_tunnels))
                    }
                    IconButton(onClick = { showNewGroupDialog = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.connections_new_group_action))
                    }
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rclone_import_menu)) },
                            onClick = {
                                showOverflowMenu = false
                                showImportRclone = true
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.connections_add))
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // step-ca cert renewal banner — visible only while CertRenewalGate
            // is mid-flight. Tells the user why the OS browser just popped
            // over the connect spinner. (#133 phase 2b)
            certRenewing?.let { renewing ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(
                            R.string.connections_renewing_cert,
                            renewing.keyLabel,
                            renewing.caName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            // Quick connect bar
            val quickConnectError = stringResource(R.string.connections_quick_connect_error)
            OutlinedTextField(
                value = quickConnectText,
                onValueChange = { quickConnectText = it },
                placeholder = { Text(stringResource(R.string.connections_quick_connect_placeholder)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            quickConnectAction(
                                quickConnectText, viewModel, sshKeys,
                                { connectingProfile = it },
                                { quickConnectText = "" },
                                quickConnectError,
                            )
                        },
                        enabled = quickConnectText.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Cable, contentDescription = stringResource(R.string.connections_quick_connect_button))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        quickConnectAction(
                            quickConnectText, viewModel, sshKeys,
                            { connectingProfile = it },
                            { quickConnectText = "" },
                            quickConnectError,
                        )
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Filter/search bar
            if (connections.isNotEmpty()) {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    placeholder = { Text(stringResource(R.string.connections_filter_placeholder)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filterText.isNotEmpty()) {
                            IconButton(onClick = { filterText = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.connections_filter_clear))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }

            // Linux VM card — shown when Terminal app is installed and not hidden in settings
            if (connections.isEmpty()) {
                EmptyState()
            } else {
                // Build tree: top-level profiles first, then dependents nested beneath.
                val profileMap = connections.associateBy { it.id }
                val dependentsByParent = connections
                    .mapNotNull { profile ->
                        // Nest a profile under the SSH profile that carries its
                        // tunnel: a jump-host chain, or a VNC/RDP/SMB-over-SSH
                        // forward. VNC/SMB are gated by their forward flag
                        // (vncSshForward defaults true) so a direct connection
                        // with a stale sshProfileId isn't wrongly nested.
                        val parentId = profile.jumpProfileId
                            ?: profile.vncSshProfileId?.takeIf { profile.vncSshForward }
                            ?: profile.rdpSshProfileId
                            ?: profile.smbSshProfileId?.takeIf { profile.smbSshForward }
                        if (parentId != null && parentId in profileMap) parentId to profile else null
                    }
                    .groupBy({ it.first }, { it.second })
                val renderedAsChild = dependentsByParent.values.flatten().map { it.id }.toSet()
                // Local profiles show in the list like every other transport.
                // The topbar Terminal icon is a quick-launch convenience for
                // the common case (one tap, find-or-create, connect) but the
                // list is the source of truth — adds, edits, multi-profile
                // setups, and config tweaks (session manager, label, color
                // tag, useAndroidShell) all happen here. Earlier filter
                // (#114) treated topbar + list as duplicates, but they're
                // really shortcut-vs-canonical, like RDP and the Desktops
                // topbar icon.
                val allTopLevel = connections.filter { it.id !in renderedAsChild }

                // Filter by search text (match label, host, username)
                val isFiltering = filterText.isNotBlank()
                val query = filterText.lowercase()
                fun matchesFilter(p: ConnectionProfile): Boolean =
                    isFiltering && (
                        p.label.lowercase().contains(query) ||
                            p.host.lowercase().contains(query) ||
                            p.username.lowercase().contains(query))

                // Build a unified flat list: ungrouped connections + (group header + its connections) ...
                // Group headers use key "group-{id}", connections use their profile id.
                val groupMap = groups.associateBy { it.id }
                val byGroup = allTopLevel.filter { it.groupId != null }.groupBy { it.groupId!! }

                // Canonical ordering: ungrouped profiles by sortOrder, then each group (by group sortOrder)
                // with its profiles (by profile sortOrder) — all as one flat list of keys.
                val canonicalFlatIds = buildList {
                    allTopLevel.filter { it.groupId == null }
                        .sortedBy { it.sortOrder }
                        .forEach { add(it.id) }
                    groups.sortedBy { it.sortOrder }.forEach { group ->
                        add("group-${group.id}")
                        byGroup[group.id].orEmpty()
                            .sortedBy { it.sortOrder }
                            .forEach { add(it.id) }
                    }
                }

                // Drag-to-reorder state — unified flat list
                var draggedId by remember { mutableStateOf<String?>(null) }
                var dragOffset by remember { mutableFloatStateOf(0f) }
                val reorderedIds = remember { mutableStateListOf<String>() }
                // The order we last wrote, until the database echoes it back (#488).
                //
                // commitReorder writes asynchronously, and onDragEnd clears
                // draggedId BEFORE calling it. The resync below therefore used to
                // run on the very next recomposition, while canonicalFlatIds still
                // held the pre-drag order — reverting the move the user had just
                // made. Whether it survived came down to whether the database
                // emitted first, which is why only *some* drags stuck.
                var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
                if (draggedId == null) {
                    val pending = pendingOrder
                    when {
                        // Nothing in flight — follow the database.
                        pending == null ->
                            if (reorderedIds.toList() != canonicalFlatIds) {
                                reorderedIds.clear()
                                reorderedIds.addAll(canonicalFlatIds)
                            }
                        // The database caught up with what we wrote.
                        canonicalFlatIds == pending -> pendingOrder = null
                        // The set of connections changed underneath us (added,
                        // deleted, synced) — our pending order is stale, take theirs.
                        canonicalFlatIds.toSet() != pending.toSet() -> {
                            pendingOrder = null
                            reorderedIds.clear()
                            reorderedIds.addAll(canonicalFlatIds)
                        }
                        // Same members, different order: our write is still in
                        // flight. Hold what the user did rather than fighting it.
                        else -> Unit
                    }
                }

                // Derive group membership from flat order: connections after a group header
                // belong to that group; connections before any group header are ungrouped.
                fun commitReorder() {
                    var currentGroupId: String? = null
                    var sortIdx = 0
                    var groupSortIdx = 0
                    reorderedIds.forEach { key ->
                        if (key.startsWith("group-")) {
                            val gid = key.removePrefix("group-")
                            currentGroupId = gid
                            // updateGroupSortOrder alone. reorderGroups(listOf(gid))
                            // used to run first and assigned index 0 — the only index
                            // a one-element list has — to every group in turn. Both
                            // launch their own coroutine, so whichever landed last
                            // won, and group order could come out zeroed (#488).
                            viewModel.updateGroupSortOrder(gid, groupSortIdx++)
                        } else {
                            val profile = allTopLevel.find { it.id == key }
                            if (profile != null) {
                                val newGroupId = currentGroupId
                                if (profile.groupId != newGroupId) {
                                    viewModel.saveConnection(profile.copy(groupId = newGroupId, sortOrder = sortIdx))
                                } else {
                                    viewModel.updateSortOrder(key, sortIdx)
                                }
                            }
                            sortIdx++
                        }
                    }
                }

                /**
                 * Move a group past its neighbouring group, carrying its
                 * connections with it (#490).
                 *
                 * Groups swap with groups only. Membership here is positional —
                 * a connection belongs to the last header above it — so moving
                 * a lone header would silently donate that group's contents to
                 * whichever group it landed under. Swapping whole blocks keeps
                 * every connection in the group it started in, and leaves the
                 * ungrouped connections sitting above the first header where
                 * they belong.
                 */
                fun moveGroup(gid: String, up: Boolean) {
                    val moved = moveGroupBlock(reorderedIds, gid, up) ?: return
                    reorderedIds.clear()
                    reorderedIds.addAll(moved)
                    // Same in-flight handshake the drag path uses (#488).
                    pendingOrder = moved
                    commitReorder()
                }

                val lazyListState = rememberLazyListState()
                val collapsedGroupIds = groups.filter { it.collapsed }.map { it.id }.toSet()

                // Build display list from reorderedIds (respecting filter + collapsed state)
                val displayIds = if (isFiltering) {
                    reorderedIds.filter { key ->
                        if (key.startsWith("group-")) {
                            val gid = key.removePrefix("group-")
                            val group = groupMap[gid]
                            val groupLabelMatches = group?.label?.lowercase()?.contains(query) == true
                            groupLabelMatches || byGroup[gid].orEmpty().any { p ->
                                matchesFilter(p) || dependentsByParent[p.id]?.any { matchesFilter(it) } == true
                            }
                        } else {
                            val p = allTopLevel.find { it.id == key }
                            p != null && (matchesFilter(p) || dependentsByParent[p.id]?.any { matchesFilter(it) } == true)
                        }
                    }
                } else {
                    displayedRows(reorderedIds, collapsedGroupIds, dragged = draggedId)
                }

                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                    item(key = "workspace-section") { workspaceSection() }
                    displayIds.forEach { key ->
                        if (key.startsWith("group-")) {
                            val gid = key.removePrefix("group-")
                            val group = groupMap[gid] ?: return@forEach
                            val groupProfileCount = byGroup[gid]?.size ?: 0
                            // Only meaningful against the real order, so the
                            // moves are hidden while a filter is narrowing it.
                            val groupKeys = reorderedIds.filter { it.startsWith("group-") }
                            val groupPos = groupKeys.indexOf(key)
                            item(key = key) {
                                ConnectionGroupHeader(
                                    onMoveUp = if (!isFiltering && groupPos > 0) {
                                        { moveGroup(gid, up = true) }
                                    } else {
                                        null
                                    },
                                    onMoveDown = if (!isFiltering && groupPos < groupKeys.lastIndex) {
                                        { moveGroup(gid, up = false) }
                                    } else {
                                        null
                                    },
                                    group = group,
                                    connectionCount = groupProfileCount,
                                    isLaunching = groupLaunchState?.groupId == group.id,
                                    launchProgress = groupLaunchState?.takeIf { it.groupId == group.id }?.let {
                                        "${it.succeeded}/${it.total}"
                                    },
                                    identities = identities,
                                    onToggleCollapsed = { viewModel.toggleGroupCollapsed(group.id) },
                                    onRename = { newLabel -> viewModel.renameGroup(group.id, newLabel) },
                                    onDelete = { viewModel.deleteGroup(group.id) },
                                    onSetIdentity = { id -> viewModel.setGroupIdentity(group.id, id) },
                                    onLaunchGroup = { viewModel.launchGroup(group.id) },
                                )
                            }
                        } else {
                            val profile = allTopLevel.find { it.id == key } ?: return@forEach
                            val isDragged = !isFiltering && draggedId == profile.id
                            item(key = key) {
                                ConnectionTreeItem(
                                    profile = profile,
                                    indent = 0,
                                    isLastChild = false,
                                    profileStatuses = profileStatuses,
                                    mcpExposure = mcpExposure,
                                    agentActiveProfiles = agentActiveProfiles,
                                    onToggleMcp = { enabled -> viewModel.toggleMcpEnabled(profile.id, enabled) },
                                    profileColors = profileColors,
                                    isConnecting = connectingProfileId == profile.id ||
                                        groupLaunchState?.connectingIds?.contains(profile.id) == true,
                                    hasKeys = sshKeys.isNotEmpty(),
                                    hasDependents = profile.id in dependentsByParent,
                                    jumpHostLabel = profile.jumpProfileId?.let { profileMap[it]?.label },
                                    onTap = {
                                        val id = effectiveIdentityFor(profile, groupMap, identities)
                                        onTapProfile(
                                            profile, profileStatuses[profile.id], sshKeys,
                                            id != null && (id.keyId != null || id.password != null),
                                            viewModel, onNavigateToSmb, onNavigateToRclone, onNavigateToEmail,
                                        ) { connectingProfile = profile }
                                    },
                                    onRename = { newLabel -> viewModel.saveConnection(profile.copy(label = newLabel)) },
                                    onEdit = { editingProfileId = profile.id },
                                    onDelete = { viewModel.deleteConnection(profile.id) },
                                    onDuplicate = { viewModel.duplicateConnection(profile.id) },
                                    onDisconnect = { viewModel.disconnect(profile.id) },
                                    onDeployKey = { deployingProfile = profile },
                                    onConnectWithPassword = { connectingProfile = profile },
                                    onForgetPassword = { viewModel.forgetPassword(profile.id) },
                                    onPortForwards = { portForwardProfile = profile },
                                    onReauthRclone = { viewModel.reauthRcloneProfile(profile) },
                                    onCancelOAuth = { viewModel.cancelPendingOAuth(profile) },
                                    onNewSession = { viewModel.openNewSession(profile.id) },
                                    enableDrag = !isFiltering,
                                    dragModifier = if (!isFiltering) Modifier
                                        .zIndex(if (isDragged) 1f else 0f)
                                        .offset(
                                            y = with(LocalDensity.current) {
                                                if (isDragged) dragOffset.roundToInt().toDp() else 0.dp
                                            },
                                        ) else Modifier,
                                    onDragStart = {
                                        if (!isFiltering) {
                                            draggedId = profile.id
                                            dragOffset = 0f
                                        }
                                    },
                                    onDrag = { delta ->
                                        if (!isFiltering) {
                                            dragOffset += delta
                                            if (dragOffset == 0f) return@ConnectionTreeItem
                                            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                                            val draggedInfo = visibleItems.find { it.key == profile.id }
                                                ?: return@ConnectionTreeItem
                                            // Recomputed here rather than reusing the composed
                                            // displayIds, which is a frame behind once a swap has
                                            // already moved the row this gesture.
                                            val rows = displayedRows(reorderedIds, collapsedGroupIds, profile.id)
                                            val here = rows.indexOf(profile.id)
                                            if (here < 0) return@ConnectionTreeItem
                                            val down = dragOffset > 0
                                            val neighbourKey =
                                                rows.getOrNull(if (down) here + 1 else here - 1)
                                                    ?: return@ConnectionTreeItem
                                            val neighbourInfo = visibleItems.find { it.key == neighbourKey }
                                                ?: return@ConnectionTreeItem
                                            val dist = neighbourBlockHeight(
                                                draggedOffset = draggedInfo.offset,
                                                neighbourOffset = neighbourInfo.offset,
                                                neighbourSize = neighbourInfo.size,
                                                afterNeighbourOffset = rows.getOrNull(here + 2)
                                                    ?.let { key -> visibleItems.find { it.key == key }?.offset },
                                                down = down,
                                            )
                                            if (dist <= 0 || abs(dragOffset) <= dist / 2) {
                                                return@ConnectionTreeItem
                                            }
                                            val moved = moveDraggedRow(reorderedIds, rows, profile.id, down)
                                                ?: return@ConnectionTreeItem
                                            reorderedIds.clear()
                                            reorderedIds.addAll(moved)
                                            // Preserve visual continuity: the item's list position
                                            // just jumped by `dist`, so take `dist` back out of
                                            // dragOffset instead of resetting to zero — otherwise
                                            // the visual row leaps a full row past the finger on
                                            // each swap.
                                            dragOffset += if (down) -dist else dist
                                        }
                                    },
                                    onDragEnd = {
                                        if (!isFiltering) {
                                            draggedId = null
                                            dragOffset = 0f
                                            // Recorded BEFORE the write so the resync
                                            // above knows a commit is in flight (#488).
                                            pendingOrder = reorderedIds.toList()
                                            commitReorder()
                                        }
                                    },
                                )
                            }
                            // Dependent children (jump hosts), recursive so chains
                            // deeper than one level (A → B → C) all render. Iterative
                            // DFS via a stack so we can stay inside LazyListScope
                            // without needing to invent a self-recursive lambda.
                            // Cycle guard: visited set prevents an infinite loop if
                            // someone manages to create a jumpProfileId cycle. (#116)
                            val ancestorDragged = draggedId == profile.id
                            val visited = mutableSetOf<String>(profile.id)
                            // Triple: (profile, indent, isLastSibling-at-this-level)
                            val stack = ArrayDeque<Triple<ConnectionProfile, Int, Boolean>>()
                            val topKids = dependentsByParent[profile.id].orEmpty()
                            // Push in reverse so the popped order matches sibling order.
                            for (i in topKids.indices.reversed()) {
                                stack.addFirst(Triple(topKids[i], 1, i == topKids.lastIndex))
                            }
                            while (stack.isNotEmpty()) {
                                val (dep, depIndent, isLastAtLevel) = stack.removeFirst()
                                if (!visited.add(dep.id)) continue
                                item(key = dep.id) {
                                    ConnectionTreeItem(
                                        profile = dep,
                                        indent = depIndent,
                                        isLastChild = isLastAtLevel,
                                        profileStatuses = profileStatuses,
                                        mcpExposure = mcpExposure,
                                        agentActiveProfiles = agentActiveProfiles,
                                        onToggleMcp = { enabled -> viewModel.toggleMcpEnabled(dep.id, enabled) },
                                        profileColors = profileColors,
                                        isConnecting = connectingProfileId == dep.id ||
                                            groupLaunchState?.connectingIds?.contains(dep.id) == true,
                                        hasKeys = sshKeys.isNotEmpty(),
                                        hasDependents = dep.id in dependentsByParent,
                                        jumpHostLabel = null,
                                        onTap = {
                                            val id = effectiveIdentityFor(dep, groupMap, identities)
                                            onTapProfile(
                                                dep, profileStatuses[dep.id], sshKeys,
                                                id != null && (id.keyId != null || id.password != null),
                                                viewModel, onNavigateToSmb, onNavigateToRclone, onNavigateToEmail,
                                            ) { connectingProfile = dep }
                                        },
                                        onRename = { newLabel -> viewModel.saveConnection(dep.copy(label = newLabel)) },
                                        onEdit = { editingProfileId = dep.id },
                                        onDelete = { viewModel.deleteConnection(dep.id) },
                                        onDuplicate = { viewModel.duplicateConnection(dep.id) },
                                        onDisconnect = { viewModel.disconnect(dep.id) },
                                        onDeployKey = { deployingProfile = dep },
                                        onConnectWithPassword = { connectingProfile = dep },
                                        onForgetPassword = { viewModel.forgetPassword(dep.id) },
                                        onPortForwards = { portForwardProfile = dep },
                                        onReauthRclone = { viewModel.reauthRcloneProfile(dep) },
                                        onCancelOAuth = { viewModel.cancelPendingOAuth(dep) },
                                        onNewSession = { viewModel.openNewSession(dep.id) },
                                        dragModifier = if (ancestorDragged) Modifier
                                            .zIndex(1f)
                                            .offset(
                                                y = with(LocalDensity.current) {
                                                    dragOffset.roundToInt().toDp()
                                                },
                                            ) else Modifier,
                                    )
                                }
                                // Push this dep's children at the next indent level.
                                val grandKids = dependentsByParent[dep.id].orEmpty()
                                for (i in grandKids.indices.reversed()) {
                                    stack.addFirst(Triple(grandKids[i], depIndent + 1, i == grandKids.lastIndex))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun quickConnectAction(
    input: String,
    viewModel: ConnectionsViewModel,
    sshKeys: List<sh.haven.core.data.db.entities.SshKey>,
    showPasswordDialog: (ConnectionProfile) -> Unit,
    clearInput: () -> Unit,
    errorMessage: String,
) {
    val profile = viewModel.parseQuickConnect(input)
    if (profile == null) {
        viewModel.showError(errorMessage)
        return
    }
    viewModel.saveConnection(profile)
    clearInput()
    if (sshKeys.isNotEmpty()) {
        viewModel.connectWithKey(profile)
    } else {
        showPasswordDialog(profile)
    }
}

/**
 * The identity that fills this profile's credentials at connect time, or null.
 * Mirrors [sh.haven.core.data.repository.SshIdentityRepository.effectiveIdentity]
 * against the already-collected lists so the synchronous tap handler can decide
 * whether an identity will supply the login before it prompts (#360).
 */
private fun effectiveIdentityFor(
    profile: ConnectionProfile,
    groups: Map<String, ConnectionGroup>,
    identities: List<sh.haven.core.data.db.entities.SshIdentity>,
): sh.haven.core.data.db.entities.SshIdentity? {
    val id = when (val own = profile.identityId) {
        sh.haven.core.data.db.entities.SshIdentity.NONE_ID -> return null
        null -> groups[profile.groupId]?.identityId
            ?.takeUnless { it == sh.haven.core.data.db.entities.SshIdentity.NONE_ID }
        else -> own
    } ?: return null
    return identities.firstOrNull { it.id == id }
}

private fun onTapProfile(
    profile: ConnectionProfile,
    profileStatus: ProfileStatus?,
    sshKeys: List<sh.haven.core.data.db.entities.SshKey>,
    hasUsableIdentity: Boolean,
    viewModel: ConnectionsViewModel,
    onNavigateToSmb: (String) -> Unit,
    onNavigateToRclone: (String) -> Unit,
    onNavigateToEmail: (String) -> Unit,
    showPasswordDialog: () -> Unit,
) {
    if (profile.isLocal) {
        viewModel.connect(profile, "")
    } else if (profileStatus == ProfileStatus.CONNECTED && profile.isEmail) {
        onNavigateToEmail(profile.id)
    } else if (profileStatus == ProfileStatus.CONNECTED && profile.isRclone) {
        onNavigateToRclone(profile.id)
    } else if (profileStatus == ProfileStatus.CONNECTED && profile.isSmb) {
        onNavigateToSmb(profile.id)
    } else if (profileStatus == ProfileStatus.CONNECTED && (profile.isVnc || profile.isRdp || profile.isSpice)) {
        // Desktop already open — re-issuing connect navigates to the Desktop
        // screen and the dedup in addVncSession/addRdpSession switches to the
        // existing tab instead of reconnecting. (A VNC/RDP-over-SSH profile
        // now reports CONNECTED via its tunnel dependent, so without this it
        // would fall into the generic branch below and open a shell instead.)
        viewModel.connect(profile, if (profile.isRdp) profile.rdpPassword.orEmpty() else "")
    } else if (profileStatus == ProfileStatus.CONNECTED) {
        viewModel.ensureShellForProfile(profile.id)
    } else if (profile.isVnc) {
        viewModel.connect(profile, "")
    } else if (profile.isRdp) {
        val savedPassword = profile.rdpPassword
        if (savedPassword != null) {
            viewModel.connect(profile, savedPassword)
        } else {
            showPasswordDialog()
        }
    } else if (profile.isSpice) {
        // SPICE auth is an optional ticket — connect directly (connectSpice
        // reads the saved ticket; no prompt needed if the server is unticketed).
        viewModel.connect(profile, "")
    } else if (profile.isSmb) {
        val savedPassword = profile.smbPassword
        if (savedPassword != null) {
            viewModel.connect(profile, savedPassword)
        } else {
            showPasswordDialog()
        }
    } else if (profile.isReticulum) {
        viewModel.connect(profile, "")
    } else if (profile.isEmail) {
        // EMAIL profiles carry their own credentials (stored password / mailbox
        // password / linked TOTP) — connectEmail handles SRP + unlock, so route
        // straight through connect() like rclone rather than the password dialog.
        viewModel.connect(profile, "")
    } else if (profile.isRclone) {
        // Rclone profiles don't take a Haven-side password — credentials
        // live inside rclone's own config, and OAuth providers run their
        // browser flow via RcloneSessionManager.connectSession. Routing
        // straight through `connect()` lets connectRclone handle that
        // (#108: previously fell through to the password catch-all and
        // confused the user).
        viewModel.connect(profile, "")
    } else if (hasUsableIdentity) {
        // An assigned identity supplies the username plus a key or password at
        // connect time (#360). connect() resolves it via applyTo(), so route
        // straight through instead of prompting for credentials the identity
        // already carries — otherwise an identity-backed host with a blank
        // per-connection username is trapped in the password dialog (whose
        // Connect button stays disabled until a username is typed).
        viewModel.connect(profile, "")
    } else if (profile.username.isBlank()) {
        // Profile saved without a username — always prompt so the user can pick one.
        showPasswordDialog()
    } else if (!profile.sshPassword.isNullOrBlank()) {
        viewModel.connect(profile, profile.sshPassword!!)
    } else if (sshKeys.isNotEmpty()) {
        viewModel.connectWithKey(profile)
    } else {
        showPasswordDialog()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionTreeItem(
    profile: ConnectionProfile,
    indent: Int,
    isLastChild: Boolean,
    profileStatuses: Map<String, ProfileStatus>,
    mcpExposure: Map<String, McpExposureKind>,
    agentActiveProfiles: Map<String, Long>,
    onToggleMcp: (Boolean) -> Unit,
    profileColors: Map<String, Color>,
    isConnecting: Boolean,
    hasKeys: Boolean,
    hasDependents: Boolean,
    jumpHostLabel: String?,
    onTap: () -> Unit,
    onRename: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onDisconnect: () -> Unit,
    onDeployKey: () -> Unit,
    onConnectWithPassword: () -> Unit,
    onForgetPassword: () -> Unit,
    onPortForwards: () -> Unit,
    onReauthRclone: () -> Unit,
    onCancelOAuth: () -> Unit,
    onNewSession: () -> Unit,
    enableDrag: Boolean = true,
    dragModifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val profileStatus = profileStatuses[profile.id]
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showForgetPasswordConfirm by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameDialog(
            currentLabel = profile.label,
            onDismiss = { showRenameDialog = false },
            onRename = { newLabel ->
                onRename(newLabel)
                showRenameDialog = false
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.connections_delete_title)) },
            text = { Text(stringResource(R.string.connections_delete_message, profile.label)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showForgetPasswordConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetPasswordConfirm = false },
            title = { Text(stringResource(R.string.connections_forget_password_title)) },
            text = { Text(stringResource(R.string.connections_forget_password_message, profile.label)) },
            confirmButton = {
                TextButton(onClick = {
                    showForgetPasswordConfirm = false
                    onForgetPassword()
                }) {
                    Text(
                        stringResource(R.string.connections_forget_password),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetPasswordConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Box(modifier = dragModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Drag handle for top-level items (hidden when filtering)
            if (indent == 0 && enableDrag) {
                val currentOnDragStart by rememberUpdatedState(onDragStart)
                val currentOnDrag by rememberUpdatedState(onDrag)
                val currentOnDragEnd by rememberUpdatedState(onDragEnd)
                val haptics = LocalHapticFeedback.current
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.connections_reorder),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(32.dp)
                        .padding(start = 4.dp)
                        .pointerInput(Unit) {
                            // Long-press to arm, rather than dragging on touch
                            // slop. The handle is a 32dp target sitting in the
                            // scroll path, so a thumb that lands on it while
                            // flinging the list used to reorder a connection
                            // by accident (#489). A press-and-hold cannot be
                            // produced by scrolling, and the haptic tick says
                            // the row is now yours to move.
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnDragStart()
                                },
                                onDragEnd = { currentOnDragEnd() },
                                onDragCancel = { currentOnDragEnd() },
                                onDrag = { _, dragAmount -> currentOnDrag(dragAmount.y) },
                            )
                        },
                )
            }
            if (indent > 0) {
                // Tree connector
                val lineColor = MaterialTheme.colorScheme.outlineVariant
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .width(24.dp)
                        .height(56.dp)
                        .padding(start = 12.dp),
                ) {
                    val midX = size.width / 2
                    val midY = size.height / 2
                    // Vertical line (half or full depending on position)
                    drawLine(
                        color = lineColor,
                        start = androidx.compose.ui.geometry.Offset(midX, 0f),
                        end = androidx.compose.ui.geometry.Offset(midX, if (isLastChild) midY else size.height),
                        strokeWidth = 2f,
                    )
                    // Horizontal branch
                    drawLine(
                        color = lineColor,
                        start = androidx.compose.ui.geometry.Offset(midX, midY),
                        end = androidx.compose.ui.geometry.Offset(size.width, midY),
                        strokeWidth = 2f,
                    )
                }
            }

            // Status dot lives in the outer Row (which centres vertically — same reason
            // the drag handle is centred) rather than ListItem.leadingContent, which
            // Material top-aligns on three-line items, floating the dot above the row's
            // midline when the host text wraps to two lines. Fixed-width slot so the
            // headline doesn't shift between the 12dp dot and the 24dp connecting spinner.
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                when {
                    isConnecting -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    profileStatus == ProfileStatus.RECONNECTING ->
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    profileStatus == ProfileStatus.CONNECTED -> {
                        val connectedColor = if (profile.colorTag in 1..PROFILE_COLORS.size)
                            PROFILE_COLORS[profile.colorTag - 1] else Color(0xFF4CAF50)
                        Icon(
                            Icons.Filled.Circle,
                            contentDescription = stringResource(R.string.connections_status_connected),
                            tint = connectedColor,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    profileStatus == ProfileStatus.ERROR -> Icon(
                        Icons.Filled.Circle,
                        contentDescription = stringResource(R.string.connections_status_error),
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(12.dp),
                    )
                    else -> Icon(
                        Icons.Filled.Circle,
                        contentDescription = stringResource(R.string.connections_status_disconnected),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            ListItem(
                headlineContent = { Text(profile.label) },
                supportingContent = {
                    if (profile.isLocal) {
                        Text(
                            stringResource(
                                if (profile.useAndroidShell) R.string.connections_android_shell_label
                                else R.string.connections_proot_label,
                            ),
                        )
                    } else if (profile.isReticulum) {
                        Text("RNS: ${profile.destinationHash?.take(12) ?: ""}... via ${profile.reticulumHost}:${profile.reticulumPort}")
                    } else if (profile.isRclone) {
                        val providerLabel = when (profile.rcloneProvider) {
                            "drive" -> "Google Drive"
                            "dropbox" -> "Dropbox"
                            "onedrive" -> "OneDrive"
                            "s3" -> "Amazon S3"
                            "b2" -> "Backblaze B2"
                            "sftp" -> "SFTP (rclone)"
                            "webdav" -> "WebDAV"
                            "ftp" -> "FTP"
                            "mega" -> "MEGA"
                            "pcloud" -> "pCloud"
                            "box" -> "Box"
                            else -> profile.rcloneProvider ?: stringResource(R.string.connections_rclone_provider_cloud)
                        }
                        Text("$providerLabel \u2022 ${profile.rcloneRemoteName ?: ""}")
                    } else {
                        val via = when {
                            jumpHostLabel != null && indent == 0 -> " " + stringResource(R.string.connections_list_via, jumpHostLabel)
                            profile.proxyType != null && indent == 0 -> " " + stringResource(R.string.connections_list_via, profile.proxyType.toString())
                            else -> ""
                        }
                        Text("${profile.username}@${profile.host}:${profile.port}$via")
                    }
                },
                trailingContent = {
                    // The single per-connection MCP element (the old "carries the MCP
                    // endpoint" badge was dropped in favour of robot-only): a tri-state
                    // robot — hidden when the agent has never used this connection, a
                    // slashed grey robot when MCP is disabled for it, and red-eyed while
                    // the agent is operating on it. Tap toggles MCP access.
                    ConnectionMcpIndicator(
                        lastActiveAt = agentActiveProfiles[profile.id],
                        mcpEnabled = profile.mcpEnabled,
                        onToggle = { onToggleMcp(!profile.mcpEnabled) },
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onTap,
                        onLongClick = { showMenu = true },
                    ),
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_rename)) },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                onClick = { showMenu = false; showRenameDialog = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { showMenu = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.connections_menu_duplicate)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { showMenu = false; onDuplicate() },
            )
            if (profile.isSsh) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_menu_port_forwards)) },
                    leadingIcon = { Icon(Icons.Filled.SyncAlt, null) },
                    onClick = { showMenu = false; onPortForwards() },
                )
            }
            if (profile.isSsh && profileStatus != ProfileStatus.CONNECTED) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_menu_connect_with_password)) },
                    leadingIcon = { Icon(Icons.Filled.Password, null) },
                    onClick = { showMenu = false; onConnectWithPassword() },
                )
            }
            if (profile.isSsh && !profile.sshPassword.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_forget_password)) },
                    leadingIcon = { Icon(Icons.Filled.NoEncryption, null) },
                    onClick = { showMenu = false; showForgetPasswordConfirm = true },
                )
            }
            if (profile.isRclone) {
                // For OAuth rclone providers (gdrive, dropbox, onedrive…)
                // tokens go stale and the rclone backend silently fails
                // to refresh, leaving Haven unable to connect. This
                // forces a fresh OAuth flow without the user needing
                // to delete + re-add the connection.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_action_reauthenticate)) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    onClick = { showMenu = false; onReauthRclone() },
                )
            }
            if (profile.isRclone && profileStatus == ProfileStatus.CONNECTING) {
                // OAuth has a 5-min timeout, but if the user dismissed
                // the browser or the callback never reaches rclone's
                // listener, this lets them clear the spinner manually.
                // Underlying gomobile worker keeps blocking until
                // process restart — that's an rclone-android limitation
                // documented in RcloneSessionManager.cancelPendingOAuth.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_action_cancel_oauth)) },
                    leadingIcon = { Icon(Icons.Filled.Close, null) },
                    onClick = { showMenu = false; onCancelOAuth() },
                )
            }
            if (profile.isSsh && profileStatus == ProfileStatus.CONNECTED) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_menu_sessions)) },
                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                    onClick = { showMenu = false; onNewSession() },
                )
            }
            if (profileStatus == ProfileStatus.CONNECTED) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_menu_disconnect)) },
                    leadingIcon = { Icon(Icons.Filled.LinkOff, null) },
                    onClick = { showMenu = false; onDisconnect() },
                )
            }
            if (profile.isSsh && hasKeys) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_menu_deploy_ssh_key)) },
                    leadingIcon = { Icon(Icons.Filled.VpnKey, null) },
                    onClick = { showMenu = false; onDeployKey() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; showDeleteConfirm = true },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Cable,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.connections_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.connections_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RenameDialog(
    currentLabel: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.common_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(label) },
                enabled = label.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun LinuxVmCard(
    vmStatus: LocalVmStatus,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasLocalServices = vmStatus.sshPort != null || vmStatus.vncPort != null
    val hasDirectServices = vmStatus.directSshPort != null || vmStatus.directVncPort != null
    val hasServices = hasLocalServices || hasDirectServices
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Filled.Laptop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.connections_linux_vm), style = MaterialTheme.typography.titleSmall)
                if (hasServices) {
                    val services = buildList {
                        vmStatus.sshPort?.let { add("SSH :$it") }
                        vmStatus.vncPort?.let { add("VNC :$it") }
                    }
                    if (services.isNotEmpty()) {
                        Text(
                            services.joinToString(" · ") + " on localhost",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hasDirectServices) {
                        val directServices = buildList {
                            vmStatus.directSshPort?.let { add("SSH :$it") }
                            vmStatus.directVncPort?.let { add("VNC :$it") }
                        }
                        Text(
                            directServices.joinToString(" · ") + " on ${vmStatus.directIp}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.connections_vm_tap_to_setup),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasServices) {
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = stringResource(R.string.connections_vm_status_active),
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(10.dp),
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.connections_vm_refresh),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}


@Composable
private fun NewGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_new_group_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.connections_group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(label) },
                enabled = label.isNotBlank(),
            ) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionGroupHeader(
    group: ConnectionGroup,
    connectionCount: Int,
    isLaunching: Boolean = false,
    launchProgress: String? = null,
    identities: List<sh.haven.core.data.db.entities.SshIdentity> = emptyList(),
    onToggleCollapsed: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onSetIdentity: (String?) -> Unit = {},
    onLaunchGroup: () -> Unit = {},
    /** Null at the ends of the list, or while a filter is applied (#490). */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showIdentityDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameDialog(
            currentLabel = group.label,
            onDismiss = { showRenameDialog = false },
            onRename = { newLabel ->
                onRename(newLabel)
                showRenameDialog = false
            },
        )
    }

    if (showIdentityDialog) {
        GroupIdentityDialog(
            groupLabel = group.label,
            identities = identities,
            selectedId = group.identityId,
            onDismiss = { showIdentityDialog = false },
            onSelect = { id ->
                onSetIdentity(id)
                showIdentityDialog = false
            },
        )
    }

    Box {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val groupColor = if (group.colorTag in 1..PROFILE_COLORS.size)
                        PROFILE_COLORS[group.colorTag - 1] else MaterialTheme.colorScheme.primary
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = groupColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        group.label,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "($connectionCount)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLaunching) {
                        Text(
                            launchProgress ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onLaunchGroup) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.connections_launch_group),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(onClick = onToggleCollapsed) {
                        Icon(
                            if (group.collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                            contentDescription = stringResource(if (group.collapsed) R.string.connections_expand else R.string.connections_collapse),
                        )
                    }
                }
            },
            modifier = Modifier.combinedClickable(
                onClick = onToggleCollapsed,
                onLongClick = { showMenu = true },
            ),
        )
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.connections_menu_launch_all)) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                onClick = { showMenu = false; onLaunchGroup() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_rename)) },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                onClick = { showMenu = false; showRenameDialog = true },
            )
            // Deliberately leave the menu open: moving a group more than one
            // place is the common case, and reopening the menu for each step
            // is what makes that tedious.
            if (onMoveUp != null || onMoveDown != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_auth_move_up)) },
                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                    enabled = onMoveUp != null,
                    onClick = { onMoveUp?.invoke() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_auth_move_down)) },
                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                    enabled = onMoveDown != null,
                    onClick = { onMoveDown?.invoke() },
                )
            }
            if (identities.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.connections_menu_group_identity)) },
                    leadingIcon = { Icon(Icons.Filled.Badge, null) },
                    onClick = { showMenu = false; showIdentityDialog = true },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.connections_menu_delete_group)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
    HorizontalDivider()
}

/**
 * Swap a group with the group above or below it, carrying its connections
 * along (#490).
 *
 * [ids] is the flat list the screen reorders: `group-<id>` header keys with
 * each group's connections following its header. Membership is positional —
 * a connection belongs to the last header above it — so a group has to move
 * as a block, or it would donate its contents to whichever group it landed
 * under. Ungrouped connections sit above the first header and are never
 * touched, since groups only ever swap with other groups.
 *
 * Returns null when the move is not available: no such group, or it is
 * already at the end it is being asked to move towards.
 */
internal fun moveGroupBlock(ids: List<String>, gid: String, up: Boolean): List<String>? {
    val headers = ids.indices.filter { ids[it].startsWith("group-") }
    val pos = headers.indexOf(ids.indexOf("group-$gid"))
    val neighbour = if (up) pos - 1 else pos + 1
    if (pos < 0 || neighbour !in headers.indices) return null
    // A block runs from its header to just before the next one.
    fun block(p: Int) = headers[p]..(headers.getOrNull(p + 1)?.minus(1) ?: ids.lastIndex)
    val first = block(minOf(pos, neighbour))
    val second = block(maxOf(pos, neighbour))
    // Adjacent by construction, so the swap is just a matter of emitting the
    // second block before the first.
    return ids.subList(0, first.first) +
        ids.slice(second) +
        ids.slice(first) +
        ids.subList(second.last + 1, ids.size)
}

/**
 * How far a dragged row's slot moves when it changes places with its
 * neighbour: the height of that neighbour's *block* — its own row plus any
 * dependent rows drawn under it, which travel with their parent.
 *
 * This is what the drag has to give back to its offset so the row keeps
 * tracking the finger, and it is not the same as the gap the drag used to
 * measure going down. That gap is the *dragged* row's height, which only
 * matches when every row is the same height. A group header is shorter than a
 * connection, so dragging a connection down over one took back more than the
 * slot had moved, leaving the row drawn higher than the finger had put it —
 * losing that difference again on each further header.
 *
 * Going up the two happen to coincide: the previous block ends exactly where
 * the dragged row begins, so the gap is already the block height.
 *
 * [afterNeighbourOffset] is the row after the neighbour, which bounds the
 * neighbour's block; without it (the neighbour is last, or scrolled out) fall
 * back to the neighbour's own height and lose only its dependents.
 */
internal fun neighbourBlockHeight(
    draggedOffset: Int,
    neighbourOffset: Int,
    neighbourSize: Int,
    afterNeighbourOffset: Int?,
    down: Boolean,
): Int = if (down) {
    (afterNeighbourOffset ?: (neighbourOffset + neighbourSize)) - neighbourOffset
} else {
    draggedOffset - neighbourOffset
}

/**
 * The rows the connection list actually renders: every `group-` header, plus
 * the connections of the groups that are not collapsed.
 *
 * [dragged] is exempt from hiding. A row dragged into a collapsed group would
 * otherwise disappear from under the finger mid-gesture, stranding it there —
 * it stays on screen until the drag ends, and is then hidden with the rest of
 * that group's members. (#488)
 */
internal fun displayedRows(
    ids: List<String>,
    collapsedGroupIds: Set<String>,
    dragged: String?,
): List<String> {
    var hidden = false
    return ids.filter { key ->
        if (key.startsWith("group-")) {
            hidden = key.removePrefix("group-") in collapsedGroupIds
            true // always show group headers
        } else {
            !hidden || key == dragged
        }
    }
}

/**
 * Move a dragged connection one row past its neighbour *on screen* (#488).
 *
 * [ids] is the full flat order; [displayed] is the subset the list actually
 * rendered, which omits the members of collapsed groups. The neighbour has to
 * come from [displayed], because the caller can only measure rows that exist:
 * picking it from [ids] meant that as soon as the row above was a hidden member
 * of a collapsed group there was nothing to measure against, and the drag stopped
 * dead there. A collapsed group was an impassable wall, so a connection below one
 * could never be dragged above it or into it — the "refuses to move" in #488.
 *
 * Landing position is expressed against that same neighbour: just after it going
 * down, just before it going up. Since membership is positional, dragging onto a
 * header — collapsed or not — makes the connection that group's first member,
 * and dragging up past a header takes it out of the group again.
 *
 * Returns null when there is no neighbour that way, i.e. the row is already at
 * that end of the list.
 */
internal fun moveDraggedRow(
    ids: List<String>,
    displayed: List<String>,
    id: String,
    down: Boolean,
): List<String>? {
    val here = displayed.indexOf(id)
    if (here < 0) return null
    val neighbour = displayed.getOrNull(if (down) here + 1 else here - 1) ?: return null
    if (!ids.contains(id) || !ids.contains(neighbour)) return null
    val moved = ids.toMutableList()
    moved.remove(id)
    val at = moved.indexOf(neighbour)
    moved.add(if (down) at + 1 else at, id)
    return moved
}

/**
 * Pick the default [SshIdentity] for a group (#360) — a member connection
 * inherits it unless it sets its own. "None" clears the group default.
 */
@Composable
private fun GroupIdentityDialog(
    groupLabel: String,
    identities: List<sh.haven.core.data.db.entities.SshIdentity>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_group_identity_title, groupLabel)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.material3.ListItem(
                    modifier = Modifier.clickable { onSelect(null) },
                    headlineContent = { Text(stringResource(R.string.connections_dropdown_none)) },
                    trailingContent = if (selectedId == null) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                )
                identities.forEach { ident ->
                    androidx.compose.material3.ListItem(
                        modifier = Modifier.clickable { onSelect(ident.id) },
                        headlineContent = { Text(ident.name) },
                        supportingContent = { Text(ident.username, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = if (selectedId == ident.id) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
