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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
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
    onNavigateToVnc: (host: String, port: Int, password: String?, username: String?, sshForward: Boolean, sshSessionId: String?, profileId: String?, colorDepth: String) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onNavigateToRdp: (host: String, port: Int, username: String, password: String, domain: String, sshForward: Boolean, sshProfileId: String?, sshSessionId: String?, profileId: String?, useNla: Boolean, colorDepth: Int) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> },
    onNavigateToSmb: (profileId: String) -> Unit = {},
    onNavigateToRclone: (profileId: String) -> Unit = {},
    onNavigateToWayland: () -> Unit = {},
    onNavigateToConnections: () -> Unit = {},
    onNavigateToAgentActivity: () -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()
    val tunnelConfigs by viewModel.tunnelConfigs.collectAsState()
    var showTunnelsScreen by remember { mutableStateOf(false) }
    var showDesktopsScreen by remember { mutableStateOf(false) }
    val profileStatuses by viewModel.profileStatuses.collectAsState()
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
    val navigateToTerminal by viewModel.navigateToTerminal.collectAsState()
    val navigateToVnc by viewModel.navigateToVnc.collectAsState()
    val navigateToRdp by viewModel.navigateToRdp.collectAsState()
    val navigateToSmb by viewModel.navigateToSmb.collectAsState()
    val navigateToRclone by viewModel.navigateToRclone.collectAsState()
    val navigateToWayland by viewModel.navigateToWayland.collectAsState()
    val navigateBackToConnections by viewModel.navigateToConnections.collectAsState()
    val deploySuccess by viewModel.deploySuccess.collectAsState()
    val sessionSelection by viewModel.sessionSelection.collectAsState()
    val passwordFallback by viewModel.passwordFallback.collectAsState()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsState()
    val fidoTouchPrompt by viewModel.fidoTouchPrompt.collectAsState()
    val keyboardInteractiveAuth by viewModel.keyboardInteractiveAuth.collectAsState()
    val globalSessionManagerLabel by viewModel.globalSessionManagerLabel.collectAsState()
    val newSessionProfileId by viewModel.newSessionProfileId.collectAsState()
    val subnetScanning by viewModel.subnetScanning.collectAsState()
    val reticulumScanning by viewModel.reticulumScanning.collectAsState()
    val discoveredSmbHosts by viewModel.discoveredSmbHosts.collectAsState()
    val smbSubnetScanning by viewModel.smbSubnetScanning.collectAsState()
    val showMoshSetupGuide by viewModel.showMoshSetupGuide.collectAsState()
    val showMoshClientMissing by viewModel.showMoshClientMissing.collectAsState()
    val desktopSetupState by viewModel.desktopSetupState.collectAsState()
    val desktopStates by viewModel.desktopStates.collectAsState()
    val desktopVncPasswordPrompt by viewModel.desktopVncPasswordPrompt.collectAsState()
    val groupLaunchState by viewModel.groupLaunchState.collectAsState()

    LaunchedEffect(navigateToTerminal) {
        navigateToTerminal?.let { profileId ->
            onNavigateToTerminal(profileId)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(navigateToVnc) {
        navigateToVnc?.let { nav ->
            onNavigateToVnc(nav.host, nav.port, nav.password, nav.username, nav.sshForward, nav.sshSessionId, nav.profileId, nav.colorDepth)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(navigateToWayland) {
        if (navigateToWayland) {
            onNavigateToWayland()
            viewModel.consumeNavigateToWayland()
        }
    }

    LaunchedEffect(navigateToRdp) {
        navigateToRdp?.let { nav ->
            onNavigateToRdp(nav.host, nav.port, nav.username, nav.password, nav.domain, nav.sshForward, nav.sshProfileId, nav.sshSessionId, nav.profileId, nav.useNla, nav.colorDepth)
            viewModel.onNavigated()
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
    var showVmSetup by rememberSaveable { mutableStateOf(false) }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingProfile = editingProfileId?.let { id -> connections.firstOrNull { it.id == id } }
    var connectingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deployingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var portForwardProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var setupDesktopDe by remember {
        mutableStateOf<sh.haven.core.local.ProotManager.DesktopEnvironment?>(null)
    }
    var quickConnectText by rememberSaveable { mutableStateOf("") }
    var filterText by rememberSaveable { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(error) {
        error?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            snackbarHostState.showSnackbar(
                message = it,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            viewModel.dismissError()
        }
    }

    // Non-fatal warnings (e.g. agent-forwarding enabled but all stored
    // keys are encrypted). Rendered as a snackbar + toast like errors so
    // they're discoverable, but the connection itself still proceeds.
    LaunchedEffect(warning) {
        warning?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
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
    // (and SSH connections) survive when the app is backgrounded.
    val batteryPromptDismissed by viewModel.batteryPromptDismissed.collectAsState()
    var showBatteryDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(batteryPromptDismissed) {
        if (!batteryPromptDismissed) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                // If Shizuku is available, silently whitelist without bothering the user
                if (sh.haven.core.local.WaylandSocketHelper.tryDisableBatteryOptimization(context.packageName)) {
                    viewModel.dismissBatteryPrompt()
                } else {
                    showBatteryDialog = true
                }
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
                Text(stringResource(R.string.connections_battery_message))
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
                TextButton(onClick = {
                    showBatteryDialog = false
                    viewModel.dismissBatteryPrompt()
                }) {
                    Text(stringResource(R.string.common_not_now))
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
            discoveredDestinations = discoveredDestinations,
            discoveredHosts = discoveredHosts,
            discoveredSmbHosts = discoveredSmbHosts,
            sshProfiles = connections,
            groups = groups,
            sshKeys = sshKeys,
            tunnelConfigs = tunnelConfigs,
            onManageTunnels = { showTunnelsScreen = true },
            globalSessionManagerLabel = globalSessionManagerLabel,
            subnetScanning = subnetScanning,
            smbSubnetScanning = smbSubnetScanning,
            reticulumScanning = reticulumScanning,
            onScanSubnet = { viewModel.scanSubnet() },
            onScanSubnetSmb = { viewModel.scanSubnetSmb() },
            onScanReticulum = { host, port, netName, passphrase ->
                viewModel.scanReticulumDestinations(host, port, netName, passphrase)
            },
            onDismiss = { showAddDialog = false },
            onSave = { profile ->
                viewModel.saveConnection(profile)
                showAddDialog = false
            },
        )
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
        ConnectionEditDialog(
            existing = profile,
            discoveredDestinations = discoveredDestinations,
            discoveredHosts = discoveredHosts,
            discoveredSmbHosts = discoveredSmbHosts,
            sshProfiles = connections,
            groups = groups,
            sshKeys = sshKeys,
            tunnelConfigs = tunnelConfigs,
            onManageTunnels = { showTunnelsScreen = true },
            globalSessionManagerLabel = globalSessionManagerLabel,
            subnetScanning = subnetScanning,
            smbSubnetScanning = smbSubnetScanning,
            reticulumScanning = reticulumScanning,
            onScanSubnet = { viewModel.scanSubnet() },
            onScanSubnetSmb = { viewModel.scanSubnetSmb() },
            onScanReticulum = { host, port, netName, passphrase ->
                viewModel.scanReticulumDestinations(host, port, netName, passphrase)
            },
            onDismiss = { editingProfileId = null },
            onSave = { updated ->
                viewModel.saveConnection(updated)
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
                viewModel.connect(
                    profile,
                    password,
                    rememberPassword = rememberPassword,
                    usernameOverride = username,
                )
                viewModel.dismissPasswordFallback()
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
        FidoTouchPromptDialog(prompt = prompt)
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
            onDismissRequest = { showTunnelsScreen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            sh.haven.feature.tunnel.TunnelsScreen(
                onBack = { showTunnelsScreen = false },
            )
        }
    }

    // Desktops overlay — DesktopManagerSection used to live inline at
    // the top of the connection list. Behind a topbar icon now so the
    // list isn't permanently topped by the desktop manager.
    if (showDesktopsScreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showDesktopsScreen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Desktops") },
                        navigationIcon = {
                            IconButton(onClick = { showDesktopsScreen = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    DesktopManagerSection(
                        installedDesktops = viewModel.installedDesktops,
                        desktopStates = desktopStates,
                        desktopSetupState = desktopSetupState,
                        onInstall = { de -> setupDesktopDe = de },
                        onStart = { de -> viewModel.startDesktop(de) },
                        onStop = { de -> viewModel.stopDesktop(de) },
                        onUninstall = { de -> viewModel.uninstallDesktop(de) },
                    )
                }
            }
        }
    }

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
        SessionPickerDialog(
            managerLabel = selection.managerLabel,
            sessionNames = selection.sessionNames,
            previousSessionNames = selection.previousSessionNames,
            suggestedNewName = selection.suggestedNewName,
            canKill = selection.manager.killCommand != null,
            canRename = selection.manager.renameCommand != null,
            onSelect = { name -> viewModel.onSessionSelected(selection.sessionId, name) },
            onRestore = { names -> viewModel.restorePreviousSessions(selection.sessionId, names) },
            onKill = { name -> viewModel.killRemoteSession(name) },
            onRename = { old, new -> viewModel.renameRemoteSession(old, new) },
            onNewSession = { name ->
                // Empty string is treated as "use the suggestion the ViewModel
                // would generate" — pass null down so the existing fallback
                // path runs. Any non-empty user input goes through as-is.
                viewModel.onSessionSelected(selection.sessionId, name.takeIf { it.isNotBlank() })
            },
            onDismiss = { viewModel.dismissSessionPicker() },
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

    setupDesktopDe?.let { de ->
        // Auto-dismiss dialog when setup completes
        LaunchedEffect(desktopSetupState) {
            if (desktopSetupState is sh.haven.core.local.ProotManager.DesktopSetupState.Complete) {
                setupDesktopDe = null
                viewModel.resetDesktopSetupState()
            }
        }
        DesktopSetupDialog(
            desktopState = desktopSetupState,
            selectedDe = de,
            onStart = { password, _, addons ->
                viewModel.setupDesktop(password, de, addons)
            },
            onShellSelected = { shell ->
                viewModel.setWaylandShellCommand(shell)
            },
            onDismiss = {
                setupDesktopDe = null
                viewModel.resetDesktopSetupState()
            },
        )
    }

    // Desktop VNC password prompt — shown when starting a desktop that requires auth
    // but no stored password is available
    desktopVncPasswordPrompt?.let { prompt ->
        var vncPwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDesktopVncPasswordPrompt() },
            title = { Text("VNC Password") },
            text = {
                sh.haven.core.ui.PasswordField(
                    value = vncPwd,
                    onValueChange = { vncPwd = it },
                    label = "Password",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onDesktopVncPasswordEntered(vncPwd) },
                    enabled = vncPwd.isNotBlank(),
                ) { Text("Connect") }
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
                    // Local terminal (PRoot) — one-tap entry to the
                    // local Linux environment Haven ships. Always
                    // visible because PRoot ships with the app; if no
                    // LOCAL profile exists yet it's created on first
                    // tap. Replaces the previous Android Linux VM
                    // (com.android.virtualization.terminal) icon —
                    // that's a much rarer code path; users with that
                    // app installed can still create a regular SSH
                    // profile pointed at it.
                    IconButton(onClick = { viewModel.connectLocalTerminal() }) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Local terminal")
                    }
                    // Desktops likewise — the DesktopManagerSection now
                    // lives behind this icon as a full-screen overlay
                    // rather than always sitting at the top of the list.
                    if (showDesktopsCard && viewModel.isRootfsReady) {
                        IconButton(onClick = { showDesktopsScreen = true }) {
                            Icon(Icons.Filled.DesktopWindows, contentDescription = "Desktops")
                        }
                    }
                    // Tunnels are connection definitions (per-app
                    // WireGuard configs that other profiles route
                    // through), so they belong on this screen rather
                    // than in app Settings where they used to live.
                    IconButton(onClick = { showTunnelsScreen = true }) {
                        Icon(Icons.Filled.VpnLock, contentDescription = "Tunnels")
                    }
                    IconButton(onClick = { showNewGroupDialog = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.connections_new_group_action))
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
                        val parentId = profile.jumpProfileId ?: profile.rdpSshProfileId
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
                if (reorderedIds.toList() != canonicalFlatIds && draggedId == null) {
                    reorderedIds.clear()
                    reorderedIds.addAll(canonicalFlatIds)
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
                            viewModel.reorderGroups(listOf(gid)) // will be batched below
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

                val lazyListState = rememberLazyListState()

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
                    // Respect collapsed groups: skip profile IDs that belong to a collapsed group
                    val collapsedGroupIds = groups.filter { it.collapsed }.map { it.id }.toSet()
                    var inCollapsedGroup = false
                    reorderedIds.filter { key ->
                        if (key.startsWith("group-")) {
                            val gid = key.removePrefix("group-")
                            inCollapsedGroup = gid in collapsedGroupIds
                            true // always show group header
                        } else {
                            !inCollapsedGroup
                        }
                    }
                }

                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                    displayIds.forEach { key ->
                        if (key.startsWith("group-")) {
                            val gid = key.removePrefix("group-")
                            val group = groupMap[gid] ?: return@forEach
                            val groupProfileCount = byGroup[gid]?.size ?: 0
                            item(key = key) {
                                ConnectionGroupHeader(
                                    group = group,
                                    connectionCount = groupProfileCount,
                                    isLaunching = groupLaunchState?.groupId == group.id,
                                    launchProgress = groupLaunchState?.takeIf { it.groupId == group.id }?.let {
                                        "${it.succeeded}/${it.total}"
                                    },
                                    onToggleCollapsed = { viewModel.toggleGroupCollapsed(group.id) },
                                    onRename = { newLabel -> viewModel.renameGroup(group.id, newLabel) },
                                    onDelete = { viewModel.deleteGroup(group.id) },
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
                                    profileColors = profileColors,
                                    isConnecting = connectingProfileId == profile.id ||
                                        groupLaunchState?.connectingIds?.contains(profile.id) == true,
                                    hasKeys = sshKeys.isNotEmpty(),
                                    hasDependents = profile.id in dependentsByParent,
                                    jumpHostLabel = profile.jumpProfileId?.let { profileMap[it]?.label },
                                    onTap = { onTapProfile(profile, profileStatuses[profile.id], sshKeys, viewModel, onNavigateToSmb, onNavigateToRclone) { connectingProfile = profile } },
                                    onRename = { newLabel -> viewModel.saveConnection(profile.copy(label = newLabel)) },
                                    onEdit = { editingProfileId = profile.id },
                                    onDelete = { viewModel.deleteConnection(profile.id) },
                                    onDuplicate = { viewModel.duplicateConnection(profile.id) },
                                    onDisconnect = { viewModel.disconnect(profile.id) },
                                    onDeployKey = { deployingProfile = profile },
                                    onConnectWithPassword = { connectingProfile = profile },
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
                                            val fromIdx = reorderedIds.indexOf(profile.id)
                                            if (fromIdx < 0) return@ConnectionTreeItem
                                            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                                            val draggedInfo = visibleItems.find { it.key == profile.id }
                                                ?: return@ConnectionTreeItem
                                            if (dragOffset > 0 && fromIdx < reorderedIds.lastIndex) {
                                                val nextInfo = visibleItems.find { it.key == reorderedIds[fromIdx + 1] }
                                                if (nextInfo != null) {
                                                    val dist = nextInfo.offset - draggedInfo.offset
                                                    if (dragOffset > dist / 2) {
                                                        reorderedIds.add(fromIdx + 1, reorderedIds.removeAt(fromIdx))
                                                        // Preserve visual continuity: the item's list
                                                        // position just jumped by `dist`, so subtract
                                                        // `dist` from dragOffset instead of resetting
                                                        // to zero — otherwise the visual row leaps a
                                                        // full row ahead of the finger on each swap.
                                                        dragOffset -= dist
                                                    }
                                                }
                                            } else if (dragOffset < 0 && fromIdx > 0) {
                                                val prevInfo = visibleItems.find { it.key == reorderedIds[fromIdx - 1] }
                                                if (prevInfo != null) {
                                                    val dist = draggedInfo.offset - prevInfo.offset
                                                    if (dragOffset < -dist / 2) {
                                                        reorderedIds.add(fromIdx - 1, reorderedIds.removeAt(fromIdx))
                                                        // Mirror of the downward case — the item
                                                        // jumped one row up, so add `dist` back to
                                                        // dragOffset so visual tracking stays 1:1.
                                                        dragOffset += dist
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        if (!isFiltering) {
                                            draggedId = null
                                            dragOffset = 0f
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
                                        profileColors = profileColors,
                                        isConnecting = connectingProfileId == dep.id ||
                                            groupLaunchState?.connectingIds?.contains(dep.id) == true,
                                        hasKeys = sshKeys.isNotEmpty(),
                                        hasDependents = dep.id in dependentsByParent,
                                        jumpHostLabel = null,
                                        onTap = { onTapProfile(dep, profileStatuses[dep.id], sshKeys, viewModel, onNavigateToSmb, onNavigateToRclone) { connectingProfile = dep } },
                                        onRename = { newLabel -> viewModel.saveConnection(dep.copy(label = newLabel)) },
                                        onEdit = { editingProfileId = dep.id },
                                        onDelete = { viewModel.deleteConnection(dep.id) },
                                        onDuplicate = { viewModel.duplicateConnection(dep.id) },
                                        onDisconnect = { viewModel.disconnect(dep.id) },
                                        onDeployKey = { deployingProfile = dep },
                                        onConnectWithPassword = { connectingProfile = dep },
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

private fun onTapProfile(
    profile: ConnectionProfile,
    profileStatus: ProfileStatus?,
    sshKeys: List<sh.haven.core.data.db.entities.SshKey>,
    viewModel: ConnectionsViewModel,
    onNavigateToSmb: (String) -> Unit,
    onNavigateToRclone: (String) -> Unit,
    showPasswordDialog: () -> Unit,
) {
    if (profile.isLocal) {
        viewModel.connect(profile, "")
    } else if (profileStatus == ProfileStatus.CONNECTED && profile.isRclone) {
        onNavigateToRclone(profile.id)
    } else if (profileStatus == ProfileStatus.CONNECTED && profile.isSmb) {
        onNavigateToSmb(profile.id)
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
    } else if (profile.isSmb) {
        val savedPassword = profile.smbPassword
        if (savedPassword != null) {
            viewModel.connect(profile, savedPassword)
        } else {
            showPasswordDialog()
        }
    } else if (profile.isReticulum) {
        viewModel.connect(profile, "")
    } else if (profile.isRclone) {
        // Rclone profiles don't take a Haven-side password — credentials
        // live inside rclone's own config, and OAuth providers run their
        // browser flow via RcloneSessionManager.connectSession. Routing
        // straight through `connect()` lets connectRclone handle that
        // (#108: previously fell through to the password catch-all and
        // confused the user).
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

    Box(modifier = dragModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Drag handle for top-level items (hidden when filtering)
            if (indent == 0 && enableDrag) {
                val currentOnDragStart by rememberUpdatedState(onDragStart)
                val currentOnDrag by rememberUpdatedState(onDrag)
                val currentOnDragEnd by rememberUpdatedState(onDragEnd)
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.connections_reorder),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(32.dp)
                        .padding(start = 4.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { currentOnDragStart() },
                                onDragEnd = { currentOnDragEnd() },
                                onDragCancel = { currentOnDragEnd() },
                                onVerticalDrag = { _, dragAmount -> currentOnDrag(dragAmount) },
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

            ListItem(
                headlineContent = { Text(profile.label) },
                supportingContent = {
                    if (profile.isLocal) {
                        Text(stringResource(R.string.connections_proot_label))
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
                            else -> profile.rcloneProvider ?: "Cloud"
                        }
                        Text("$providerLabel \u2022 ${profile.rcloneRemoteName ?: ""}")
                    } else {
                        val via = when {
                            jumpHostLabel != null && indent == 0 -> " via $jumpHostLabel"
                            profile.proxyType != null && indent == 0 -> " via ${profile.proxyType}"
                            else -> ""
                        }
                        Text("${profile.username}@${profile.host}:${profile.port}$via")
                    }
                },
                leadingContent = {
                    when {
                        isConnecting -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        profileStatus == ProfileStatus.RECONNECTING -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
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
                },
                trailingContent = null,
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
            if (profile.isRclone) {
                // For OAuth rclone providers (gdrive, dropbox, onedrive…)
                // tokens go stale and the rclone backend silently fails
                // to refresh, leaving Haven unable to connect. This
                // forces a fresh OAuth flow without the user needing
                // to delete + re-add the connection.
                DropdownMenuItem(
                    text = { Text("Re-authenticate") },
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
                    text = { Text("Cancel OAuth") },
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
private fun SessionPickerDialog(
    managerLabel: String,
    sessionNames: List<String>,
    previousSessionNames: List<String> = emptyList(),
    suggestedNewName: String = "",
    canKill: Boolean = false,
    canRename: Boolean = false,
    onSelect: (String) -> Unit,
    onRestore: (List<String>) -> Unit = {},
    onKill: (String) -> Unit = {},
    onRename: (old: String, new: String) -> Unit = { _, _ -> },
    onNewSession: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renamingSession by remember { mutableStateOf<String?>(null) }

    renamingSession?.let { name ->
        RenameDialog(
            currentLabel = name,
            onDismiss = { renamingSession = null },
            onRename = { newName ->
                onRename(name, newName)
                renamingSession = null
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_sessions_title, managerLabel)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Restore previous sessions button (if any match)
                if (previousSessionNames.size > 1) {
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.connections_restore_previous_sessions, previousSessionNames.size),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(
                                previousSessionNames.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Restore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.clickable { onRestore(previousSessionNames) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                sessionNames.forEach { name ->
                    val wasPrevious = name in previousSessionNames
                    ListItem(
                        headlineContent = {
                            Text(
                                name,
                                fontWeight = if (wasPrevious) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            )
                        },
                        trailingContent = {
                            Row {
                                if (canRename) {
                                    IconButton(onClick = { renamingSession = name }) {
                                        Icon(
                                            Icons.Filled.DriveFileRenameOutline,
                                            contentDescription = stringResource(R.string.connections_rename_session),
                                        )
                                    }
                                }
                                if (canKill) {
                                    IconButton(onClick = { onKill(name) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.connections_kill_session),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable { onSelect(name) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NewSessionInlineRow(
                    suggestedName = suggestedNewName,
                    onCreate = onNewSession,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * The "Create new session" row at the bottom of [SessionPickerDialog] and
 * [NewTabSessionPickerDialog] in TerminalScreen. A pre-filled text field
 * with a Create button. The field's text is selected on first focus so a
 * tap-then-type immediately replaces the suggestion. Keyboard "Done" /
 * Enter triggers Create. (#112)
 */
@Composable
internal fun NewSessionInlineRow(
    suggestedName: String,
    onCreate: (String) -> Unit,
) {
    var fieldValue by remember(suggestedName) {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = suggestedName,
                selection = androidx.compose.ui.text.TextRange(0, suggestedName.length),
            )
        )
    }
    var hasBeenFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { fieldValue = it },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    // Re-select all on each refocus so a tap-then-type wipes
                    // the field — matches SeriousM's request in #112. The
                    // `hasBeenFocused` gate prevents fighting with the user's
                    // own selection moves while they're already typing.
                    if (focusState.isFocused && !hasBeenFocused) {
                        hasBeenFocused = true
                        fieldValue = fieldValue.copy(
                            selection = androidx.compose.ui.text.TextRange(0, fieldValue.text.length),
                        )
                    } else if (!focusState.isFocused) {
                        hasBeenFocused = false
                    }
                },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    if (fieldValue.text.isNotBlank()) onCreate(fieldValue.text)
                },
            ),
        )
        IconButton(
            onClick = { onCreate(fieldValue.text) },
            enabled = fieldValue.text.isNotBlank(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.connections_new_session_create),
            )
        }
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
private fun DesktopManagerSection(
    installedDesktops: Set<sh.haven.core.local.ProotManager.DesktopEnvironment>,
    desktopStates: Map<sh.haven.core.local.ProotManager.DesktopEnvironment, sh.haven.core.local.DesktopManager.DesktopInstance>,
    desktopSetupState: sh.haven.core.local.ProotManager.DesktopSetupState,
    onInstall: (sh.haven.core.local.ProotManager.DesktopEnvironment) -> Unit,
    onStart: (sh.haven.core.local.ProotManager.DesktopEnvironment) -> Unit,
    onStop: (sh.haven.core.local.ProotManager.DesktopEnvironment) -> Unit,
    onUninstall: (sh.haven.core.local.ProotManager.DesktopEnvironment) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Filled.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.connections_desktops_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                sh.haven.core.local.ProotManager.DesktopEnvironment.entries.filter { !it.hidden }.forEach { de ->
                    val isInstalled = de in installedDesktops
                    val instance = desktopStates[de]
                    DesktopRow(
                        de = de,
                        isInstalled = isInstalled,
                        instance = instance,
                        isSetupBusy = desktopSetupState is sh.haven.core.local.ProotManager.DesktopSetupState.Installing,
                        onInstall = { onInstall(de) },
                        onStart = { onStart(de) },
                        onStop = { onStop(de) },
                        onUninstall = { onUninstall(de) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopRow(
    de: sh.haven.core.local.ProotManager.DesktopEnvironment,
    isInstalled: Boolean,
    instance: sh.haven.core.local.DesktopManager.DesktopInstance?,
    isSetupBusy: Boolean = false,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUninstall: () -> Unit,
) {
    var showUninstallConfirm by remember { mutableStateOf(false) }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.connections_desktop_uninstall_title)) },
            text = { Text(stringResource(R.string.connections_desktop_uninstall_message, de.label)) },
            confirmButton = {
                TextButton(onClick = { showUninstallConfirm = false; onUninstall() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        // Status indicator
        Icon(
            Icons.Filled.Circle,
            contentDescription = null,
            tint = when (instance?.state) {
                sh.haven.core.local.DesktopManager.DesktopState.RUNNING -> Color(0xFF4CAF50)
                sh.haven.core.local.DesktopManager.DesktopState.STARTING -> Color(0xFFFFC107)
                sh.haven.core.local.DesktopManager.DesktopState.ERROR -> Color(0xFFF44336)
                else -> MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(de.label, style = MaterialTheme.typography.bodyMedium)
            when {
                instance?.state == sh.haven.core.local.DesktopManager.DesktopState.RUNNING && !de.isNative ->
                    Text(
                        "VNC :${instance.displayNumber} (port ${instance.vncPort})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                instance?.state == sh.haven.core.local.DesktopManager.DesktopState.RUNNING && de.isNative ->
                    Text(
                        stringResource(R.string.connections_desktop_native_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                instance?.state == sh.haven.core.local.DesktopManager.DesktopState.ERROR ->
                    Text(
                        instance.errorMessage ?: "Error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                !isInstalled ->
                    Text(
                        de.sizeEstimate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }

        if (!isInstalled) {
            TextButton(onClick = onInstall, enabled = !isSetupBusy) {
                Text(stringResource(R.string.common_install))
            }
        } else if (isSetupBusy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            when (instance?.state) {
                sh.haven.core.local.DesktopManager.DesktopState.RUNNING ->
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.connections_desktop_stop))
                    }
                sh.haven.core.local.DesktopManager.DesktopState.STARTING ->
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> {
                    IconButton(onClick = onStart) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.connections_desktop_start))
                    }
                    IconButton(onClick = { showUninstallConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSetupDialog(
    desktopState: sh.haven.core.local.ProotManager.DesktopSetupState,
    selectedDe: sh.haven.core.local.ProotManager.DesktopEnvironment,
    onStart: (password: String, de: sh.haven.core.local.ProotManager.DesktopEnvironment, addons: Set<sh.haven.core.local.ProotManager.DesktopAddon>) -> Unit,
    onShellSelected: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("haven") }
    var shellCmd by rememberSaveable { mutableStateOf("/bin/sh") }
    var selectedAddons by remember {
        mutableStateOf(emptySet<sh.haven.core.local.ProotManager.DesktopAddon>())
    }
    val isInstalling = desktopState is sh.haven.core.local.ProotManager.DesktopSetupState.Installing

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text(stringResource(R.string.connections_desktop_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (desktopState) {
                    is sh.haven.core.local.ProotManager.DesktopSetupState.Idle -> {
                        Text(
                            "${selectedDe.label} (${selectedDe.sizeEstimate})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            when {
                                selectedDe.isWayland -> stringResource(R.string.connections_desktop_wayland_description)
                                selectedDe == sh.haven.core.local.ProotManager.DesktopEnvironment.OPENBOX -> stringResource(R.string.connections_desktop_openbox_description)
                                else -> stringResource(R.string.connections_desktop_vnc_description)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!selectedDe.isWayland) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.connections_desktop_vnc_password)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                        if (selectedDe.isNative) {
                            var shellExpanded by remember { mutableStateOf(false) }
                            val shellOptions = listOf("/bin/sh", "/bin/ash", "/bin/bash", "/bin/zsh", "/bin/fish")
                            ExposedDropdownMenuBox(
                                expanded = shellExpanded,
                                onExpandedChange = { shellExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = shellCmd,
                                    onValueChange = { shellCmd = it },
                                    label = { Text(stringResource(R.string.connections_desktop_shell)) },
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = shellExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                )
                                ExposedDropdownMenu(
                                    expanded = shellExpanded,
                                    onDismissRequest = { shellExpanded = false },
                                ) {
                                    shellOptions.forEach { shell ->
                                        DropdownMenuItem(
                                            text = { Text(shell) },
                                            onClick = {
                                                shellCmd = shell
                                                shellExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.connections_desktop_addons_header),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            sh.haven.core.local.ProotManager.DesktopAddon.entries.forEach { addon ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Checkbox(
                                        checked = addon in selectedAddons,
                                        onCheckedChange = { checked ->
                                            selectedAddons = if (checked) selectedAddons + addon
                                                else selectedAddons - addon
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(addon.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${addon.description} (${addon.sizeEstimate})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is sh.haven.core.local.ProotManager.DesktopSetupState.Installing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                desktopState.step,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is sh.haven.core.local.ProotManager.DesktopSetupState.Complete -> {
                        Text(stringResource(R.string.connections_desktop_installed))
                    }
                    is sh.haven.core.local.ProotManager.DesktopSetupState.Error -> {
                        Text(
                            stringResource(R.string.connections_desktop_setup_failed, desktopState.message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (desktopState is sh.haven.core.local.ProotManager.DesktopSetupState.Idle) {
                TextButton(
                    onClick = {
                        if (selectedDe.isNative) onShellSelected(shellCmd)
                        onStart(password, selectedDe, selectedAddons)
                    },
                ) { Text(stringResource(R.string.common_install)) }
            }
        },
        dismissButton = {
            if (!isInstalling) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
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
    onToggleCollapsed: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onLaunchGroup: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

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
            DropdownMenuItem(
                text = { Text(stringResource(R.string.connections_menu_delete_group)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
    HorizontalDivider()
}
