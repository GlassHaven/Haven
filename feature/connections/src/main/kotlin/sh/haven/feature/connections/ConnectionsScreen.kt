package sh.haven.feature.connections

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
    onNavigateToVnc: (host: String, port: Int, password: String?) -> Unit = { _, _, _ -> },
    onNavigateToRdp: (host: String, port: Int, username: String, password: String, domain: String, sshForward: Boolean, sshProfileId: String?, sshSessionId: String?, profileId: String?) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onNavigateToSmb: (profileId: String) -> Unit = {},
    onNavigateToRclone: (profileId: String) -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()
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
    val connectingProfileId by viewModel.connectingProfileId.collectAsState()
    val launchingDesktop by viewModel.launchingDesktop.collectAsState()
    val error by viewModel.error.collectAsState()
    val navigateToTerminal by viewModel.navigateToTerminal.collectAsState()
    val navigateToVnc by viewModel.navigateToVnc.collectAsState()
    val navigateToRdp by viewModel.navigateToRdp.collectAsState()
    val navigateToSmb by viewModel.navigateToSmb.collectAsState()
    val navigateToRclone by viewModel.navigateToRclone.collectAsState()
    val deploySuccess by viewModel.deploySuccess.collectAsState()
    val sessionSelection by viewModel.sessionSelection.collectAsState()
    val passwordFallback by viewModel.passwordFallback.collectAsState()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsState()
    val globalSessionManagerLabel by viewModel.globalSessionManagerLabel.collectAsState()
    val newSessionProfileId by viewModel.newSessionProfileId.collectAsState()
    val subnetScanning by viewModel.subnetScanning.collectAsState()
    val discoveredSmbHosts by viewModel.discoveredSmbHosts.collectAsState()
    val smbSubnetScanning by viewModel.smbSubnetScanning.collectAsState()
    val showMoshSetupGuide by viewModel.showMoshSetupGuide.collectAsState()
    val showMoshClientMissing by viewModel.showMoshClientMissing.collectAsState()
    val desktopSetupState by viewModel.desktopSetupState.collectAsState()
    val groupLaunchState by viewModel.groupLaunchState.collectAsState()

    LaunchedEffect(navigateToTerminal) {
        navigateToTerminal?.let { profileId ->
            onNavigateToTerminal(profileId)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(navigateToVnc) {
        navigateToVnc?.let { nav ->
            onNavigateToVnc(nav.host, nav.port, nav.password)
            viewModel.onNavigated()
        }
    }

    LaunchedEffect(navigateToRdp) {
        navigateToRdp?.let { nav ->
            onNavigateToRdp(nav.host, nav.port, nav.username, nav.password, nav.domain, nav.sshForward, nav.sshProfileId, nav.sshSessionId, nav.profileId)
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

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showVmSetup by rememberSaveable { mutableStateOf(false) }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingProfile = editingProfileId?.let { id -> connections.firstOrNull { it.id == id } }
    var connectingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deployingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var portForwardProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var setupDesktopProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var quickConnectText by rememberSaveable { mutableStateOf("") }
    var filterText by rememberSaveable { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(deploySuccess) {
        if (deploySuccess) {
            snackbarHostState.showSnackbar("SSH key deployed successfully")
            viewModel.dismissDeploySuccess()
        }
    }

    // Request POST_NOTIFICATIONS permission on Android 13+ so the foreground
    // service notification is visible and "Disconnect All" action works.
    val context = LocalContext.current
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
            globalSessionManagerLabel = globalSessionManagerLabel,
            subnetScanning = subnetScanning,
            smbSubnetScanning = smbSubnetScanning,
            onScanSubnet = { viewModel.scanSubnet() },
            onScanSubnetSmb = { viewModel.scanSubnetSmb() },
            onDismiss = { showAddDialog = false },
            onSave = { profile ->
                viewModel.saveConnection(profile)
                showAddDialog = false
            },
        )
    }

    if (showVmSetup) {
        LinuxVmSetupDialog(
            vmStatus = localVmStatus,
            onConnectSsh = { port ->
                showVmSetup = false
                val existing = connections.find {
                    it.host in listOf("localhost", "127.0.0.1") && it.port == port && it.username == "droid"
                }
                val profile = existing ?: ConnectionProfile(
                    label = "Linux VM",
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
                    label = "Linux VM",
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
                        label = "Linux VM",
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
                        label = "Linux VM",
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
            globalSessionManagerLabel = globalSessionManagerLabel,
            subnetScanning = subnetScanning,
            smbSubnetScanning = smbSubnetScanning,
            onScanSubnet = { viewModel.scanSubnet() },
            onScanSubnetSmb = { viewModel.scanSubnetSmb() },
            onDismiss = { editingProfileId = null },
            onSave = { updated ->
                viewModel.saveConnection(updated)
                editingProfileId = null
            },
        )
    }

    connectingProfile?.let { profile ->
        PasswordDialog(
            profile = profile,
            hasKeys = sshKeys.isNotEmpty(),
            onDismiss = { connectingProfile = null },
            onConnect = { password, rememberPassword ->
                viewModel.connect(profile, password, rememberPassword = rememberPassword)
                connectingProfile = null
            },
        )
    }

    passwordFallback?.let { profile ->
        PasswordDialog(
            profile = profile,
            hasKeys = sshKeys.isNotEmpty(),
            onDismiss = { viewModel.dismissPasswordFallback() },
            onConnect = { password, rememberPassword ->
                viewModel.connect(profile, password, rememberPassword = rememberPassword)
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
            canKill = selection.manager.killCommand != null,
            canRename = selection.manager.renameCommand != null,
            onSelect = { name -> viewModel.onSessionSelected(selection.sessionId, name) },
            onKill = { name -> viewModel.killRemoteSession(name) },
            onRename = { old, new -> viewModel.renameRemoteSession(old, new) },
            onNewSession = { viewModel.onSessionSelected(selection.sessionId, null) },
            onDismiss = { viewModel.dismissSessionPicker() },
        )
    }

    if (showMoshSetupGuide) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissMoshSetupGuide() },
            title = { Text("Mosh not found on server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The remote host doesn't have mosh-server installed. " +
                            "Mosh (Mobile Shell) keeps your session alive across " +
                            "network changes and high-latency connections."
                    )
                    Text("Install it on the server:")
                    Text(
                        "  Ubuntu/Debian:  sudo apt install mosh\n" +
                            "  Fedora/RHEL:    sudo dnf install mosh\n" +
                            "  Arch:           sudo pacman -S mosh\n" +
                            "  macOS:          brew install mosh",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("UDP port 60001 must be open on the server firewall.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uriHandler.openUri("https://github.com/mobile-shell/mosh")
                }) { Text("Mosh on GitHub") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMoshSetupGuide() }) { Text("OK") }
            },
        )
    }

    if (showMoshClientMissing) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissMoshClientMissing() },
            title = { Text("Mosh client not built") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The mosh-client binary is not included in this build. " +
                            "It needs to be cross-compiled for Android using the NDK."
                    )
                    Text(
                        "Run tools/build-mosh.sh with Android NDK r27+ to build " +
                            "the mosh-client binary, then rebuild the app.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uriHandler.openUri("https://github.com/mobile-shell/mosh")
                }) { Text("Mosh on GitHub") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMoshClientMissing() }) { Text("OK") }
            },
        )
    }

    setupDesktopProfile?.let { profile ->
        DesktopSetupDialog(
            desktopState = desktopSetupState,
            onStart = { password, de ->
                viewModel.setupDesktop(profile, password, de)
            },
            onDismiss = {
                setupDesktopProfile = null
                viewModel.resetDesktopSetupState()
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
                title = { Text("Connections") },
                actions = {
                    IconButton(onClick = { showNewGroupDialog = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "New Group")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add connection")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Quick connect bar
            OutlinedTextField(
                value = quickConnectText,
                onValueChange = { quickConnectText = it },
                placeholder = { Text("user@host or host:port") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            quickConnectAction(
                                quickConnectText, viewModel, sshKeys,
                                { connectingProfile = it },
                                { quickConnectText = "" },
                            )
                        },
                        enabled = quickConnectText.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Cable, contentDescription = "Connect")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        quickConnectAction(
                            quickConnectText, viewModel, sshKeys,
                            { connectingProfile = it },
                            { quickConnectText = "" },
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
                    placeholder = { Text("Filter connections...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filterText.isNotEmpty()) {
                            IconButton(onClick = { filterText = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear filter")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }

            // Linux VM card — shown when Terminal app is installed
            if (localVmStatus.terminalAppInstalled) {
                LinuxVmCard(
                    vmStatus = localVmStatus,
                    onClick = { showVmSetup = true },
                    onRefresh = { viewModel.refreshLocalVm() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

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
                                    onTap = { onTapProfile(profile, profileStatuses[profile.id], sshKeys, viewModel) { connectingProfile = profile } },
                                    onRename = { newLabel -> viewModel.saveConnection(profile.copy(label = newLabel)) },
                                    onEdit = { editingProfileId = profile.id },
                                    onDelete = { viewModel.deleteConnection(profile.id) },
                                    onDisconnect = { viewModel.disconnect(profile.id) },
                                    onDeployKey = { deployingProfile = profile },
                                    onConnectWithPassword = { connectingProfile = profile },
                                    onPortForwards = { portForwardProfile = profile },
                                    onNewSession = { viewModel.openNewSession(profile.id) },
                                    onSetupDesktop = { setupDesktopProfile = profile },
                                    onLaunchDesktop = { viewModel.launchDesktop(profile) },
                                    isDesktopInstalled = viewModel.isDesktopInstalled,
                                    isLaunchingDesktop = launchingDesktop,
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
                                                        dragOffset = 0f
                                                    }
                                                }
                                            } else if (dragOffset < 0 && fromIdx > 0) {
                                                val prevInfo = visibleItems.find { it.key == reorderedIds[fromIdx - 1] }
                                                if (prevInfo != null) {
                                                    val dist = draggedInfo.offset - prevInfo.offset
                                                    if (dragOffset < -dist / 2) {
                                                        reorderedIds.add(fromIdx - 1, reorderedIds.removeAt(fromIdx))
                                                        dragOffset = 0f
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
                            // Dependent children (jump hosts)
                            val deps = dependentsByParent[profile.id].orEmpty()
                            deps.forEachIndexed { index, dep ->
                                item(key = dep.id) {
                                    val parentDragged = draggedId == profile.id
                                    ConnectionTreeItem(
                                        profile = dep,
                                        indent = 1,
                                        isLastChild = index == deps.lastIndex,
                                        profileStatuses = profileStatuses,
                                        profileColors = profileColors,
                                        isConnecting = connectingProfileId == dep.id ||
                                            groupLaunchState?.connectingIds?.contains(dep.id) == true,
                                        hasKeys = sshKeys.isNotEmpty(),
                                        hasDependents = false,
                                        jumpHostLabel = null,
                                        onTap = { onTapProfile(dep, profileStatuses[dep.id], sshKeys, viewModel) { connectingProfile = dep } },
                                        onRename = { newLabel -> viewModel.saveConnection(dep.copy(label = newLabel)) },
                                        onEdit = { editingProfileId = dep.id },
                                        onDelete = { viewModel.deleteConnection(dep.id) },
                                        onDisconnect = { viewModel.disconnect(dep.id) },
                                        onDeployKey = { deployingProfile = dep },
                                        onConnectWithPassword = { connectingProfile = dep },
                                        onPortForwards = { portForwardProfile = dep },
                                        onNewSession = { viewModel.openNewSession(dep.id) },
                                        onSetupDesktop = { setupDesktopProfile = dep },
                                        onLaunchDesktop = { viewModel.launchDesktop(dep) },
                                        isDesktopInstalled = viewModel.isDesktopInstalled,
                                        isLaunchingDesktop = launchingDesktop,
                                        dragModifier = if (parentDragged) Modifier
                                            .zIndex(1f)
                                            .offset(
                                                y = with(LocalDensity.current) {
                                                    dragOffset.roundToInt().toDp()
                                                },
                                            ) else Modifier,
                                    )
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
) {
    val profile = viewModel.parseQuickConnect(input)
    if (profile == null) {
        viewModel.showError("Use format: user@host or user@host:port")
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
    showPasswordDialog: () -> Unit,
) {
    if (profile.isLocal) {
        // Local: no auth needed, handles both fresh connect and re-navigate
        viewModel.connect(profile, "")
    } else if (profileStatus == ProfileStatus.CONNECTED) {
        // ensureShellForProfile navigates via _navigateToTerminal when ready
        // (handles jump host sessions that need shell setup or session picker)
        viewModel.ensureShellForProfile(profile.id)
    } else if (profile.isVnc) {
        // VNC: connect directly (password stored in profile)
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
    onDisconnect: () -> Unit,
    onDeployKey: () -> Unit,
    onConnectWithPassword: () -> Unit,
    onPortForwards: () -> Unit,
    onNewSession: () -> Unit,
    onSetupDesktop: () -> Unit = {},
    onLaunchDesktop: () -> Unit = {},
    isDesktopInstalled: Boolean = false,
    isLaunchingDesktop: Boolean = false,
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
            title = { Text("Delete connection?") },
            text = { Text("Delete \"${profile.label}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
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
                    contentDescription = "Reorder",
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
                        Text("PRoot Alpine Linux")
                    } else if (profile.isReticulum) {
                        Text("RNS: ${profile.destinationHash?.take(12) ?: ""}... via ${profile.reticulumHost}:${profile.reticulumPort}")
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
                                contentDescription = "Connected",
                                tint = connectedColor,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        profileStatus == ProfileStatus.ERROR -> Icon(
                            Icons.Filled.Circle,
                            contentDescription = "Error",
                            tint = Color(0xFFF44336),
                            modifier = Modifier.size(12.dp),
                        )
                        else -> Icon(
                            Icons.Filled.Circle,
                            contentDescription = "Disconnected",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                },
                trailingContent = if (profile.isLocal) {{
                    if (isLaunchingDesktop) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = if (isDesktopInstalled) onLaunchDesktop else onSetupDesktop) {
                            Icon(
                                Icons.Filled.DesktopWindows,
                                contentDescription = if (isDesktopInstalled) "Launch Desktop" else "Setup Desktop",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }} else null,
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
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                onClick = { showMenu = false; showRenameDialog = true },
            )
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { showMenu = false; onEdit() },
            )
            if (profile.isSsh) {
                DropdownMenuItem(
                    text = { Text("Port Forwards") },
                    leadingIcon = { Icon(Icons.Filled.SyncAlt, null) },
                    onClick = { showMenu = false; onPortForwards() },
                )
            }
            if (profile.isSsh && profileStatus != ProfileStatus.CONNECTED) {
                DropdownMenuItem(
                    text = { Text("Connect with password") },
                    leadingIcon = { Icon(Icons.Filled.Password, null) },
                    onClick = { showMenu = false; onConnectWithPassword() },
                )
            }
            if (profile.isSsh && profileStatus == ProfileStatus.CONNECTED) {
                DropdownMenuItem(
                    text = { Text("Sessions") },
                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                    onClick = { showMenu = false; onNewSession() },
                )
            }
            if (profileStatus == ProfileStatus.CONNECTED) {
                DropdownMenuItem(
                    text = { Text("Disconnect") },
                    leadingIcon = { Icon(Icons.Filled.LinkOff, null) },
                    onClick = { showMenu = false; onDisconnect() },
                )
            }
            if (profile.isSsh && hasKeys) {
                DropdownMenuItem(
                    text = { Text("Deploy SSH Key") },
                    leadingIcon = { Icon(Icons.Filled.VpnKey, null) },
                    onClick = { showMenu = false; onDeployKey() },
                )
            }
            if (profile.isLocal) {
                DropdownMenuItem(
                    text = { Text("Setup Desktop") },
                    leadingIcon = { Icon(Icons.Filled.Laptop, null) },
                    onClick = { showMenu = false; onSetupDesktop() },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete") },
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
            "No connections yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Tap + to add a server, or type user@host above",
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
    canKill: Boolean = false,
    canRename: Boolean = false,
    onSelect: (String) -> Unit,
    onKill: (String) -> Unit = {},
    onRename: (old: String, new: String) -> Unit = { _, _ -> },
    onNewSession: () -> Unit,
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
        title = { Text("$managerLabel sessions") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                sessionNames.forEach { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = {
                            Row {
                                if (canRename) {
                                    IconButton(onClick = { renamingSession = name }) {
                                        Icon(
                                            Icons.Filled.DriveFileRenameOutline,
                                            contentDescription = "Rename session",
                                        )
                                    }
                                }
                                if (canKill) {
                                    IconButton(onClick = { onKill(name) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Kill session",
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
                ListItem(
                    headlineContent = {
                        Text(
                            "New session",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable { onNewSession() },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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
        title = { Text("Rename Connection") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(label) },
                enabled = label.isNotBlank(),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
                Text("Linux VM", style = MaterialTheme.typography.titleSmall)
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
                        "Tap to set up",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasServices) {
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = "Active",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(10.dp),
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh VM status",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun DesktopSetupDialog(
    desktopState: sh.haven.core.local.ProotManager.DesktopSetupState,
    onStart: (password: String, de: sh.haven.core.local.ProotManager.DesktopEnvironment) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("haven") }
    val deOptions = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
    var selectedDe by rememberSaveable { mutableIntStateOf(0) }
    val isInstalling = desktopState is sh.haven.core.local.ProotManager.DesktopSetupState.Installing

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text("Setup Desktop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (desktopState) {
                    is sh.haven.core.local.ProotManager.DesktopSetupState.Idle -> {
                        Text(
                            "Install a desktop environment and VNC server in the PRoot environment." +
                                if (deOptions[selectedDe] == sh.haven.core.local.ProotManager.DesktopEnvironment.OPENBOX)
                                    " Openbox is minimal — right-click the desktop for the app menu."
                                else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            deOptions.forEachIndexed { index, de ->
                                SegmentedButton(
                                    selected = selectedDe == index,
                                    onClick = { selectedDe = index },
                                    shape = SegmentedButtonDefaults.itemShape(index, deOptions.size),
                                ) {
                                    Text("${de.label} (${de.sizeEstimate})")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("VNC Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                        Text("Desktop installed. Connecting to VNC...")
                    }
                    is sh.haven.core.local.ProotManager.DesktopSetupState.Error -> {
                        Text(
                            "Setup failed: ${desktopState.message}",
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
                    onClick = { onStart(password, deOptions[selectedDe]) },
                ) { Text("Install") }
            }
        },
        dismissButton = {
            if (!isInstalling) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text("New Group") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(label) },
                enabled = label.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
                                contentDescription = "Launch group",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(onClick = onToggleCollapsed) {
                        Icon(
                            if (group.collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                            contentDescription = if (group.collapsed) "Expand" else "Collapse",
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
                text = { Text("Launch All") },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                onClick = { showMenu = false; onLaunchGroup() },
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                onClick = { showMenu = false; showRenameDialog = true },
            )
            DropdownMenuItem(
                text = { Text("Delete Group") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
    HorizontalDivider()
}
