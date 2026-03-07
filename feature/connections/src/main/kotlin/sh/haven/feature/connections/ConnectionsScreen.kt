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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.ConnectionProfile

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectionsScreen(
    onNavigateToTerminal: (profileId: String) -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()
    val profileStatuses by viewModel.profileStatuses.collectAsState()
    val discoveredDestinations by viewModel.discoveredDestinations.collectAsState()
    val discoveredHosts by viewModel.discoveredHosts.collectAsState()
    val connectingProfileId by viewModel.connectingProfileId.collectAsState()
    val error by viewModel.error.collectAsState()
    val navigateToTerminal by viewModel.navigateToTerminal.collectAsState()
    val deploySuccess by viewModel.deploySuccess.collectAsState()
    val sessionSelection by viewModel.sessionSelection.collectAsState()
    val passwordFallback by viewModel.passwordFallback.collectAsState()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsState()

    LaunchedEffect(navigateToTerminal) {
        navigateToTerminal?.let { profileId ->
            onNavigateToTerminal(profileId)
            viewModel.onNavigated()
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var connectingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deployingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var portForwardProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var quickConnectText by remember { mutableStateOf("") }

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
            onDismiss = { showAddDialog = false },
            onSave = { profile ->
                viewModel.saveConnection(profile)
                showAddDialog = false
            },
        )
    }

    editingProfile?.let { profile ->
        ConnectionEditDialog(
            existing = profile,
            discoveredDestinations = discoveredDestinations,
            discoveredHosts = discoveredHosts,
            onDismiss = { editingProfile = null },
            onSave = { updated ->
                viewModel.saveConnection(updated)
                editingProfile = null
            },
        )
    }

    connectingProfile?.let { profile ->
        PasswordDialog(
            profile = profile,
            hasKeys = sshKeys.isNotEmpty(),
            onDismiss = { connectingProfile = null },
            onConnect = { password ->
                viewModel.connect(profile, password)
                connectingProfile = null
            },
        )
    }

    passwordFallback?.let { profile ->
        PasswordDialog(
            profile = profile,
            hasKeys = sshKeys.isNotEmpty(),
            onDismiss = { viewModel.dismissPasswordFallback() },
            onConnect = { password ->
                viewModel.connect(profile, password)
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connections") })
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
                placeholder = { Text("Quick connect: user@host:port") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val profile = viewModel.parseQuickConnect(quickConnectText)
                            if (profile != null) {
                                viewModel.saveConnection(profile)
                                connectingProfile = profile
                                quickConnectText = ""
                            }
                        },
                        enabled = quickConnectText.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Cable, contentDescription = "Connect")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val profile = viewModel.parseQuickConnect(quickConnectText)
                        if (profile != null) {
                            viewModel.saveConnection(profile)
                            connectingProfile = profile
                            quickConnectText = ""
                        }
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (connections.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(connections, key = { it.id }) { profile ->
                        val profileStatus = profileStatuses[profile.id]
                        ConnectionListItem(
                            profile = profile,
                            profileStatus = profileStatus,
                            isConnecting = connectingProfileId == profile.id,
                            hasKeys = sshKeys.isNotEmpty(),
                            onTap = {
                                if (profileStatus == ProfileStatus.CONNECTED) {
                                    onNavigateToTerminal(profile.id)
                                } else if (profile.isReticulum) {
                                    viewModel.connect(profile, "")
                                } else if (sshKeys.isNotEmpty()) {
                                    viewModel.connectWithKey(profile)
                                } else {
                                    connectingProfile = profile
                                }
                            },
                            onRename = { newLabel ->
                                viewModel.saveConnection(profile.copy(label = newLabel))
                            },
                            onEdit = { editingProfile = profile },
                            onDelete = { viewModel.deleteConnection(profile.id) },
                            onDisconnect = { viewModel.disconnect(profile.id) },
                            onDeployKey = { deployingProfile = profile },
                            onConnectWithPassword = { connectingProfile = profile },
                            onPortForwards = { portForwardProfile = profile },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionListItem(
    profile: ConnectionProfile,
    profileStatus: ProfileStatus?,
    isConnecting: Boolean,
    hasKeys: Boolean,
    onTap: () -> Unit,
    onRename: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDisconnect: () -> Unit,
    onDeployKey: () -> Unit,
    onConnectWithPassword: () -> Unit,
    onPortForwards: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

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

    Box {
        ListItem(
            headlineContent = { Text(profile.label) },
            supportingContent = {
                if (profile.isReticulum) {
                    Text("RNS: ${profile.destinationHash?.take(12) ?: ""}... via ${profile.reticulumHost}:${profile.reticulumPort}")
                } else {
                    Text("${profile.username}@${profile.host}:${profile.port}")
                }
            },
            leadingContent = {
                when {
                    isConnecting -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    profileStatus == ProfileStatus.RECONNECTING -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    profileStatus == ProfileStatus.CONNECTED -> Icon(
                        Icons.Filled.Circle,
                        contentDescription = "Connected",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(12.dp),
                    )
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
            modifier = Modifier.combinedClickable(
                onClick = onTap,
                onLongClick = { showMenu = true },
            ),
        )

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
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; onDelete() },
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
