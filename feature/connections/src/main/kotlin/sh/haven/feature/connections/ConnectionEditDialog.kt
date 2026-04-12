package sh.haven.feature.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import sh.haven.core.data.db.entities.ConnectionProfile

/** Profile group colors — matches PROFILE_COLORS in ConnectionsScreen. */
private val EDIT_DIALOG_COLORS = listOf(
    Color(0xFF42A5F5), // blue
    Color(0xFF66BB6A), // green
    Color(0xFFFF7043), // orange
    Color(0xFFAB47BC), // purple
    Color(0xFFFFCA28), // amber
    Color(0xFF26C6DA), // cyan
    Color(0xFFEF5350), // red
    Color(0xFF8D6E63), // brown
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditDialog(
    existing: ConnectionProfile? = null,
    discoveredDestinations: List<sh.haven.core.reticulum.DiscoveredDestination> = emptyList(),
    discoveredHosts: List<DiscoveredHost> = emptyList(),
    discoveredSmbHosts: List<DiscoveredHost> = emptyList(),
    sshProfiles: List<ConnectionProfile> = emptyList(),
    groups: List<sh.haven.core.data.db.entities.ConnectionGroup> = emptyList(),
    sshKeys: List<sh.haven.core.data.db.entities.SshKey> = emptyList(),
    globalSessionManagerLabel: String = "None",
    subnetScanning: Boolean = false,
    smbSubnetScanning: Boolean = false,
    reticulumScanning: Boolean = false,
    onScanSubnet: () -> Unit = {},
    onScanSubnetSmb: () -> Unit = {},
    onScanReticulum: (host: String, port: Int, networkName: String?, passphrase: String?) -> Unit = { _, _, _, _ -> },
    onDismiss: () -> Unit,
    onSave: (ConnectionProfile) -> Unit,
) {
    // Transport dropdown maps to: connectionType + useMosh + useEternalTerminal
    val initialTransport = when {
        existing?.isLocal == true -> "LOCAL"
        existing?.isVnc == true -> "VNC"
        existing?.isRdp == true -> "RDP"
        existing?.isSmb == true -> "SMB"
        existing?.isRclone == true -> "RCLONE"
        existing?.isEternalTerminal == true -> "ET"
        existing?.isMosh == true -> "MOSH"
        existing?.isReticulum == true -> "RETICULUM"
        else -> "SSH"
    }
    var selectedTransport by rememberSaveable { mutableStateOf(initialTransport) }
    // Derived connectionType for field visibility
    val connectionType = when (selectedTransport) {
        "LOCAL" -> "LOCAL"
        "RETICULUM" -> "RETICULUM"
        "VNC" -> "VNC"
        "RDP" -> "RDP"
        "SMB" -> "SMB"
        "RCLONE" -> "RCLONE"
        else -> "SSH"
    }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "") }
    var colorTag by rememberSaveable { mutableIntStateOf(existing?.colorTag ?: 0) }
    var groupId by rememberSaveable { mutableStateOf(existing?.groupId) }
    var host by rememberSaveable { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable {
        mutableStateOf(
            when {
                existing?.isVnc == true -> (existing.vncPort ?: 5900).toString()
                existing?.isRdp == true -> existing.rdpPort.toString()
                existing?.isSmb == true -> existing.smbPort.toString()
                else -> existing?.port?.toString() ?: "22"
            }
        )
    }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var rdpUsername by rememberSaveable { mutableStateOf(existing?.rdpUsername ?: "") }
    var rdpPassword by rememberSaveable { mutableStateOf(existing?.rdpPassword ?: "") }
    var rdpDomain by rememberSaveable { mutableStateOf(existing?.rdpDomain ?: "") }
    var rdpSshForward by rememberSaveable { mutableStateOf(existing?.rdpSshForward ?: false) }
    var rdpSshProfileId by rememberSaveable { mutableStateOf(existing?.rdpSshProfileId) }
    var smbShare by rememberSaveable { mutableStateOf(existing?.smbShare ?: "") }
    var smbPassword by rememberSaveable { mutableStateOf(existing?.smbPassword ?: "") }
    var smbDomain by rememberSaveable { mutableStateOf(existing?.smbDomain ?: "") }
    var smbSshForward by rememberSaveable { mutableStateOf(existing?.smbSshForward ?: false) }
    var smbSshProfileId by rememberSaveable { mutableStateOf(existing?.smbSshProfileId) }
    var vncUsername by rememberSaveable { mutableStateOf(existing?.vncUsername ?: "") }
    var vncPassword by rememberSaveable { mutableStateOf(existing?.vncPassword ?: "") }
    var destinationHash by rememberSaveable { mutableStateOf(existing?.destinationHash ?: "") }
    var jumpProfileId by rememberSaveable { mutableStateOf(existing?.jumpProfileId) }
    var proxyType by rememberSaveable { mutableStateOf(existing?.proxyType) }
    var proxyHost by rememberSaveable { mutableStateOf(existing?.proxyHost ?: "") }
    var proxyPort by rememberSaveable { mutableStateOf(existing?.proxyPort?.toString() ?: "1080") }
    var keyId by rememberSaveable { mutableStateOf(existing?.keyId) }
    var sshOptions by rememberSaveable { mutableStateOf(existing?.sshOptions ?: "") }
    var moshServerCommand by rememberSaveable { mutableStateOf(existing?.moshServerCommand ?: "") }
    var postLoginCommand by rememberSaveable { mutableStateOf(existing?.postLoginCommand ?: "") }
    var postLoginBeforeSessionManager by rememberSaveable { mutableStateOf(existing?.postLoginBeforeSessionManager ?: true) }
    var disableAltScreen by rememberSaveable { mutableStateOf(existing?.disableAltScreen ?: false) }
    var useAndroidShell by rememberSaveable { mutableStateOf(existing?.useAndroidShell ?: false) }
    var forwardAgent by rememberSaveable { mutableStateOf(existing?.forwardAgent ?: false) }
    var selectedSessionManager by rememberSaveable { mutableStateOf(existing?.sessionManager) }
    var etPort by rememberSaveable { mutableStateOf(existing?.etPort?.toString() ?: "2022") }
    var localSideband by rememberSaveable {
        mutableStateOf(
            existing != null &&
                existing.reticulumHost in listOf("127.0.0.1", "localhost", "::1") &&
                existing.reticulumPort == 37428,
        )
    }
    var rnsHost by rememberSaveable { mutableStateOf(existing?.reticulumHost ?: "") }
    var rcloneRemoteName by rememberSaveable { mutableStateOf(existing?.rcloneRemoteName ?: "") }
    var rcloneProvider by rememberSaveable { mutableStateOf(existing?.rcloneProvider ?: "") }
    var rnsPort by rememberSaveable { mutableStateOf(existing?.reticulumPort?.toString() ?: "4242") }
    var rnsNetworkName by rememberSaveable { mutableStateOf(existing?.reticulumNetworkName ?: "") }
    var rnsPassphrase by rememberSaveable { mutableStateOf(existing?.reticulumPassphrase ?: "") }

    val isEdit = existing != null
    val title = if (isEdit) "Edit Connection" else "New Connection"

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Transport selector
                val transportOptions = listOf(
                    "SSH" to "SSH",
                    "MOSH" to "Mosh",
                    "ET" to "Eternal Terminal",
                    "LOCAL" to "Local Shell (PRoot)",
                    "VNC" to "VNC (Desktop)",
                    "RDP" to "RDP (Desktop)",
                    "SMB" to "SMB (File Share)",
                    "RCLONE" to "Cloud Storage (rclone)",
                    "RETICULUM" to "Reticulum",
                )
                var transportExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = transportExpanded,
                    onExpandedChange = { transportExpanded = it },
                ) {
                    OutlinedTextField(
                        value = transportOptions.first { it.first == selectedTransport }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Transport") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(transportExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = transportExpanded,
                        onDismissRequest = { transportExpanded = false },
                    ) {
                        transportOptions.forEach { (value, displayLabel) ->
                            DropdownMenuItem(
                                text = { Text(displayLabel) },
                                onClick = {
                                    selectedTransport = value
                                    transportExpanded = false
                                    // Update port to transport default when switching
                                    val defaultPort = when (value) {
                                        "VNC" -> "5900"
                                        "RDP" -> "3389"
                                        "SMB" -> "445"
                                        "ET" -> "22"
                                        else -> "22"
                                    }
                                    if (port == "22" || port == "5900" || port == "3389" || port == "445" || port == "2022") {
                                        port = defaultPort
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = {
                        Text(
                            when (connectionType) {
                                "LOCAL" -> "Local Shell"
                                "VNC" -> "My VNC Desktop"
                                "RDP" -> "My RDP Desktop"
                                "SMB" -> "My File Share"
                                "RCLONE" -> "My Google Drive"
                                "RETICULUM" -> "My Node"
                                else -> "My Server"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))

                // Color tag picker
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Color", style = MaterialTheme.typography.bodyMedium)
                    // "None" option
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (colorTag == 0) 2.dp else 1.dp,
                                color = if (colorTag == 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .clickable { colorTag = 0 },
                    ) {
                        if (colorTag == 0) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "None",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    EDIT_DIALOG_COLORS.forEachIndexed { index, color ->
                        val tag = index + 1
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color, CircleShape)
                                .then(
                                    if (colorTag == tag) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier,
                                )
                                .clickable { colorTag = tag },
                        ) {
                            if (colorTag == tag) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                // Group picker
                if (groups.isNotEmpty()) {
                    var groupExpanded by remember { mutableStateOf(false) }
                    val selectedGroup = groups.firstOrNull { it.id == groupId }
                    ExposedDropdownMenuBox(
                        expanded = groupExpanded,
                        onExpandedChange = { groupExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedGroup?.label ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Group") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(groupExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = groupExpanded,
                            onDismissRequest = { groupExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = {
                                    groupId = null
                                    groupExpanded = false
                                },
                            )
                            groups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.label) },
                                    onClick = {
                                        groupId = group.id
                                        groupExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (connectionType == "LOCAL") {
                    Text(
                        if (useAndroidShell) {
                            "Runs the native Android shell (/system/bin/sh). " +
                                "Access Android commands, file system, and root (if available)."
                        } else {
                            "Runs an Alpine Linux shell locally via PRoot. " +
                                "Downloads a minimal rootfs (~4MB) on first use. " +
                                "No root or network connection needed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { useAndroidShell = !useAndroidShell },
                    ) {
                        Checkbox(
                            checked = useAndroidShell,
                            onCheckedChange = { useAndroidShell = it },
                        )
                        Text(
                            stringResource(R.string.connections_use_android_shell),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (connectionType == "RCLONE") {
                    val providerOptions = listOf(
                        "drive" to "Google Drive",
                        "dropbox" to "Dropbox",
                        "onedrive" to "Microsoft OneDrive",
                        "s3" to "Amazon S3 / Compatible",
                        "b2" to "Backblaze B2",
                        "sftp" to "SFTP (remote)",
                        "webdav" to "WebDAV",
                        "ftp" to "FTP",
                        "mega" to "MEGA",
                        "pcloud" to "pCloud",
                        "box" to "Box",
                    )
                    var providerExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = providerExpanded,
                        onExpandedChange = { providerExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = providerOptions.firstOrNull { it.first == rcloneProvider }?.second
                                ?: rcloneProvider.ifEmpty { "Select provider..." },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Provider") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false },
                        ) {
                            providerOptions.forEach { (value, displayLabel) ->
                                DropdownMenuItem(
                                    text = { Text(displayLabel) },
                                    onClick = {
                                        rcloneProvider = value
                                        providerExpanded = false
                                        // Auto-generate remote name if user hasn't set one manually
                                        if (rcloneRemoteName.isEmpty() || rcloneRemoteName == rcloneProvider) {
                                            rcloneRemoteName = value
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sign in via your browser when you first connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (connectionType == "VNC") {
                    // VNC: host, port, password
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        placeholder = { Text("192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        placeholder = { Text("5900") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = vncUsername,
                        onValueChange = { vncUsername = it },
                        label = { Text("Username (optional — VeNCrypt only)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = vncPassword,
                        onValueChange = { vncPassword = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Leave username blank for classic VncAuth (8-char password). Fill username for VeNCrypt (wayvnc, TigerVNC) — supports longer passwords and TLS encryption.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (connectionType == "RDP") {
                    // RDP: host, port, username, domain
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        placeholder = { Text("192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = rdpUsername,
                            onValueChange = { rdpUsername = it },
                            label = { Text("Username") },
                            placeholder = { Text("user") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rdpPassword,
                        onValueChange = { rdpPassword = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rdpDomain,
                        onValueChange = { rdpDomain = it },
                        label = { Text("Domain (optional)") },
                        placeholder = { Text("WORKGROUP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    FilterChip(
                        selected = rdpSshForward,
                        onClick = {
                            rdpSshForward = !rdpSshForward
                            if (rdpSshForward) {
                                if (host.isBlank()) host = "localhost"
                            } else {
                                rdpSshProfileId = null
                                if (host == "localhost") host = ""
                            }
                        },
                        label = { Text("SSH tunnel") },
                    )
                    if (rdpSshForward) {
                        val sshCandidates = sshProfiles.filter { it.isSsh }
                        if (sshCandidates.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            var sshExpanded by remember { mutableStateOf(false) }
                            val selectedSsh = sshCandidates.firstOrNull { it.id == rdpSshProfileId }
                            ExposedDropdownMenuBox(
                                expanded = sshExpanded,
                                onExpandedChange = { sshExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = selectedSsh?.label ?: "Select SSH connection",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("SSH connection") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sshExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                )
                                ExposedDropdownMenu(
                                    expanded = sshExpanded,
                                    onDismissRequest = { sshExpanded = false },
                                ) {
                                    sshCandidates.forEach { candidate ->
                                        DropdownMenuItem(
                                            text = { Text(candidate.label) },
                                            onClick = {
                                                rdpSshProfileId = candidate.id
                                                sshExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                "Add an SSH connection first",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else if (connectionType == "SMB") {
                    // SMB: host (with discovery), share, username, port, password, domain, SSH tunnel
                    val filteredSmbHosts = remember(discoveredSmbHosts, host) {
                        val prefix = host.lowercase()
                        discoveredSmbHosts
                            .filter {
                                prefix.isEmpty() ||
                                    it.address.startsWith(prefix) ||
                                    it.hostname?.lowercase()?.contains(prefix) == true
                            }
                            .take(8)
                    }

                    // Scan network button
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (filteredSmbHosts.isNotEmpty()) {
                            Text(
                                "Discovered (${filteredSmbHosts.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(
                            onClick = onScanSubnetSmb,
                            enabled = !smbSubnetScanning,
                        ) {
                            if (smbSubnetScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                                Text("Scanning", style = MaterialTheme.typography.labelSmall)
                            } else {
                                Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Scan Network", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    var smbHostExpanded by remember { mutableStateOf(false) }
                    if (discoveredSmbHosts.size > 3) {
                        ExposedDropdownMenuBox(
                            expanded = smbHostExpanded,
                            onExpandedChange = { smbHostExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = {
                                    host = it
                                    smbHostExpanded = true
                                },
                                label = { Text("Host") },
                                placeholder = { Text("192.168.1.100") },
                                singleLine = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = smbHostExpanded)
                                },
                                supportingText = if (filteredSmbHosts.isNotEmpty()) {{
                                    Text("${filteredSmbHosts.size} hosts discovered")
                                }} else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                            )
                            if (filteredSmbHosts.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = smbHostExpanded,
                                    onDismissRequest = { smbHostExpanded = false },
                                ) {
                                    filteredSmbHosts.forEach { disc ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(disc.hostname ?: disc.address)
                                                    if (disc.hostname != null) {
                                                        Text(
                                                            disc.address,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                host = disc.address
                                                if (label.isBlank() && disc.hostname != null) {
                                                    label = disc.hostname
                                                }
                                                smbHostExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        if (filteredSmbHosts.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                filteredSmbHosts.forEach { disc ->
                                    val chipLabel = disc.hostname ?: disc.address
                                    SuggestionChip(
                                        onClick = {
                                            host = disc.address
                                            if (label.isBlank() && disc.hostname != null) {
                                                label = disc.hostname
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = chipLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            placeholder = { Text("192.168.1.100") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = smbShare,
                        onValueChange = { smbShare = it },
                        label = { Text("Share Name") },
                        placeholder = { Text("shared") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            placeholder = { Text("user") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = smbPassword,
                        onValueChange = { smbPassword = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = smbDomain,
                        onValueChange = { smbDomain = it },
                        label = { Text("Domain (optional)") },
                        placeholder = { Text("WORKGROUP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    FilterChip(
                        selected = smbSshForward,
                        onClick = {
                            smbSshForward = !smbSshForward
                            if (smbSshForward) {
                                if (host.isBlank()) host = "localhost"
                            } else {
                                smbSshProfileId = null
                                if (host == "localhost") host = ""
                            }
                        },
                        label = { Text("SSH tunnel") },
                    )
                    if (smbSshForward) {
                        val sshCandidates = sshProfiles.filter { it.isSsh }
                        if (sshCandidates.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            var sshExpanded by remember { mutableStateOf(false) }
                            val selectedSsh = sshCandidates.firstOrNull { it.id == smbSshProfileId }
                            ExposedDropdownMenuBox(
                                expanded = sshExpanded,
                                onExpandedChange = { sshExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = selectedSsh?.label ?: "Select SSH connection",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("SSH connection") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sshExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                )
                                ExposedDropdownMenu(
                                    expanded = sshExpanded,
                                    onDismissRequest = { sshExpanded = false },
                                ) {
                                    sshCandidates.forEach { candidate ->
                                        DropdownMenuItem(
                                            text = { Text(candidate.label) },
                                            onClick = {
                                                smbSshProfileId = candidate.id
                                                sshExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                "Add an SSH connection first",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else if (connectionType == "SSH") {
                    // Discovered hosts — filter by typed prefix
                    val filteredHosts = remember(discoveredHosts, host) {
                        val prefix = host.lowercase()
                        discoveredHosts
                            .filter {
                                prefix.isEmpty() ||
                                    it.address.startsWith(prefix) ||
                                    it.hostname?.lowercase()?.contains(prefix) == true
                            }
                            .take(8)
                    }

                    // Scan network button
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (filteredHosts.isNotEmpty()) {
                            Text(
                                "Discovered (${filteredHosts.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(
                            onClick = onScanSubnet,
                            enabled = !subnetScanning,
                        ) {
                            if (subnetScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                                Text("Scanning", style = MaterialTheme.typography.labelSmall)
                            } else {
                                Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Scan Network", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Use dropdown when there are many discovered hosts (decision
                    // based on unfiltered count to avoid switching widgets mid-typing)
                    var hostExpanded by remember { mutableStateOf(false) }
                    if (discoveredHosts.size > 3) {
                        ExposedDropdownMenuBox(
                            expanded = hostExpanded,
                            onExpandedChange = { hostExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = {
                                    host = it
                                    hostExpanded = true
                                },
                                label = { Text("Host") },
                                placeholder = { Text("192.168.1.1") },
                                singleLine = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = hostExpanded)
                                },
                                supportingText = if (filteredHosts.isNotEmpty()) {{
                                    Text("${filteredHosts.size} hosts discovered")
                                }} else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                            )
                            if (filteredHosts.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = hostExpanded,
                                    onDismissRequest = { hostExpanded = false },
                                ) {
                                    filteredHosts.forEach { disc ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(disc.hostname ?: disc.address)
                                                    if (disc.hostname != null) {
                                                        Text(
                                                            disc.address + if (disc.port != 22) ":${disc.port}" else "",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                host = disc.address
                                                if (disc.port != 22) port = disc.port.toString()
                                                if (label.isBlank() && disc.hostname != null) {
                                                    label = disc.hostname
                                                }
                                                hostExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Few or no discovered hosts — show chips + plain text field
                        if (filteredHosts.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                filteredHosts.forEach { disc ->
                                    val chipLabel = disc.hostname ?: disc.address
                                    SuggestionChip(
                                        onClick = {
                                            host = disc.address
                                            if (disc.port != 22) port = disc.port.toString()
                                            if (label.isBlank() && disc.hostname != null) {
                                                label = disc.hostname
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = chipLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            placeholder = { Text("192.168.1.1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            placeholder = { Text("root") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                        )
                    }

                    // Jump host selector — exclude self to prevent circular references
                    val jumpCandidates = sshProfiles.filter { it.id != existing?.id && it.isSsh }
                    if (jumpCandidates.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        var jumpExpanded by remember { mutableStateOf(false) }
                        val selectedJump = jumpCandidates.firstOrNull { it.id == jumpProfileId }

                        ExposedDropdownMenuBox(
                            expanded = jumpExpanded,
                            onExpandedChange = { jumpExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedJump?.label ?: "None (direct)",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Jump Host (-J)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = jumpExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = jumpExpanded,
                                onDismissRequest = { jumpExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None (direct)") },
                                    onClick = {
                                        jumpProfileId = null
                                        jumpExpanded = false
                                    },
                                )
                                jumpCandidates.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(candidate.label) },
                                        onClick = {
                                            jumpProfileId = candidate.id
                                            jumpExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Visual chain indicator
                        if (selectedJump != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(selectedJump.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Filled.Storage, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Proxy configuration
                    Spacer(Modifier.height(4.dp))
                    var proxyExpanded by remember { mutableStateOf(false) }
                    val proxyOptions = listOf(
                        null to "None (direct)",
                        "SOCKS5" to "SOCKS5",
                        "SOCKS4" to "SOCKS4",
                        "HTTP" to "HTTP",
                    )
                    ExposedDropdownMenuBox(
                        expanded = proxyExpanded,
                        onExpandedChange = { proxyExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = proxyOptions.firstOrNull { it.first == proxyType }?.second ?: "None (direct)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Proxy") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = proxyExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = proxyExpanded,
                            onDismissRequest = { proxyExpanded = false },
                        ) {
                            proxyOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        proxyType = value
                                        if (value == null) {
                                            proxyHost = ""
                                        } else if (value == "HTTP" && proxyPort == "1080") {
                                            proxyPort = "8080"
                                        } else if (value != "HTTP" && proxyPort == "8080") {
                                            proxyPort = "1080"
                                        }
                                        proxyExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (proxyType != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                label = { Text("Proxy Host") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { proxyPort = it.filter { c -> c.isDigit() } },
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(80.dp),
                            )
                        }
                        if (host.endsWith(".onion") && proxyType == "SOCKS5") {
                            Text(
                                "Tor .onion address detected — hostname will be resolved through the SOCKS5 proxy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else if (host.endsWith(".onion") && proxyType != "SOCKS5") {
                            Text(
                                ".onion addresses require a SOCKS5 proxy (e.g. Orbot on localhost:9050)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        // Visual chain indicator for proxy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("$proxyType", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Storage, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (host.endsWith(".onion")) {
                        Text(
                            ".onion addresses require a SOCKS5 proxy (e.g. Orbot on localhost:9050)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // Session manager
                    Spacer(Modifier.height(4.dp))
                    val sessionManagerOptions = listOf(
                        null to "Default ($globalSessionManagerLabel)",
                        "NONE" to "None",
                        "TMUX" to "tmux",
                        "ZELLIJ" to "zellij",
                        "SCREEN" to "screen",
                        "BYOBU" to "byobu",
                    )
                    var smExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = smExpanded,
                        onExpandedChange = { smExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = sessionManagerOptions.firstOrNull { it.first == selectedSessionManager }?.second ?: "Default ($globalSessionManagerLabel)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Session Manager") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(smExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = smExpanded,
                            onDismissRequest = { smExpanded = false },
                        ) {
                            sessionManagerOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectedSessionManager = value
                                        smExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    // ET port (shown only for Eternal Terminal)
                    if (selectedTransport == "ET") {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = etPort,
                            onValueChange = { etPort = it.filter { c -> c.isDigit() } },
                            label = { Text("ET Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(120.dp),
                        )
                    }

                    // Transport helper text
                    if (selectedTransport == "MOSH") {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Requires mosh-server on remote host",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = moshServerCommand,
                            onValueChange = { moshServerCommand = it },
                            label = { Text("mosh-server command") },
                            placeholder = { Text("mosh-server new -s -c 256 -l LANG=en_US.UTF-8") },
                            supportingText = { Text("Leave blank for default. Use authbind for ports below 1024.") },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (selectedTransport == "ET") {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Requires etserver on remote host (port ${etPort.ifBlank { "2022" }})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // SSH options
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = sshOptions,
                        onValueChange = { sshOptions = it },
                        label = { Text("SSH Options") },
                        placeholder = { Text("ServerAliveInterval 60\nServerAliveCountMax 3") },
                        supportingText = { Text("ssh_config format: Key Value (one per line)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Post-login command
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = postLoginCommand,
                        onValueChange = { postLoginCommand = it },
                        label = { Text("Post-login command") },
                        placeholder = { Text("cd /app && clear") },
                        supportingText = {
                            Text(
                                if (postLoginCommand.isNotBlank() && postLoginBeforeSessionManager) {
                                    "Runs before session manager starts."
                                } else {
                                    "Sent after login."
                                },
                            )
                        },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (postLoginCommand.isNotBlank()) {
                        FilterChip(
                            selected = postLoginBeforeSessionManager,
                            onClick = { postLoginBeforeSessionManager = !postLoginBeforeSessionManager },
                            label = { Text("Run before session manager") },
                        )
                    }

                    // Alternate screen buffer toggle
                    Spacer(Modifier.height(4.dp))
                    FilterChip(
                        selected = disableAltScreen,
                        onClick = { disableAltScreen = !disableAltScreen },
                        label = { Text("Disable alternate screen") },
                    )
                    if (disableAltScreen) {
                        Text(
                            "Scrollback works in screen/vim. Program output stays in scrollback on exit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Agent forwarding toggle (OpenSSH ForwardAgent)
                    Spacer(Modifier.height(4.dp))
                    FilterChip(
                        selected = forwardAgent,
                        onClick = { forwardAgent = !forwardAgent },
                        label = { Text("Forward SSH agent") },
                    )
                    if (forwardAgent) {
                        Text(
                            "Exposes non-encrypted stored SSH keys to the remote host (for git push etc.). Encrypted keys are skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // SSH key selector
                    if (sshKeys.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        var keyExpanded by remember { mutableStateOf(false) }
                        val selectedKey = sshKeys.firstOrNull { it.id == keyId }
                        ExposedDropdownMenuBox(
                            expanded = keyExpanded,
                            onExpandedChange = { keyExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedKey?.label ?: "Any (try all keys)",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("SSH Key") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(keyExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = keyExpanded,
                                onDismissRequest = { keyExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Any (try all keys)") },
                                    onClick = {
                                        keyId = null
                                        keyExpanded = false
                                    },
                                )
                                sshKeys.forEach { key ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(key.label)
                                                Text(
                                                    "${key.keyType} ${key.fingerprintSha256.take(20)}...",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            keyId = key.id
                                            keyExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // --- Reticulum connection form ---
                    // Order: gateway config → scan → destination hash

                    // 1. Gateway configuration
                    FilterChip(
                        selected = localSideband,
                        onClick = {
                            localSideband = !localSideband
                            if (localSideband) {
                                rnsHost = "127.0.0.1"
                                rnsPort = "37428"
                            }
                        },
                        label = { Text("Local Sideband") },
                    )
                    if (!localSideband) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = rnsHost,
                                onValueChange = { rnsHost = it },
                                label = { Text("Gateway Host") },
                                placeholder = { Text("192.168.0.2") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = rnsPort,
                                onValueChange = { rnsPort = it.filter { c -> c.isDigit() } },
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(80.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = rnsNetworkName,
                            onValueChange = { rnsNetworkName = it },
                            label = { Text("Network Name") },
                            placeholder = { Text("IFAC network name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = rnsPassphrase,
                            onValueChange = { rnsPassphrase = it },
                            label = { Text("Passphrase") },
                            placeholder = { Text("IFAC passphrase (optional)") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // 2. Scan for destinations (uses gateway config above)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val scanHost = if (localSideband) "127.0.0.1" else rnsHost
                            val scanPort = if (localSideband) 37428 else (rnsPort.toIntOrNull() ?: 4242)
                            onScanReticulum(
                                scanHost,
                                scanPort,
                                rnsNetworkName.ifBlank { null },
                                rnsPassphrase.ifBlank { null },
                            )
                        },
                        enabled = !reticulumScanning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (reticulumScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Scanning...")
                        } else {
                            Text("Scan for rnsh nodes")
                        }
                    }

                    // 3. Discovered destinations (tapping a chip fills the hash)
                    val filtered = remember(discoveredDestinations, destinationHash) {
                        val prefix = destinationHash.lowercase()
                        discoveredDestinations
                            .filter { prefix.isEmpty() || it.hash.startsWith(prefix) }
                            .take(8)
                    }
                    if (filtered.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        val hiddenCount = discoveredDestinations.size - filtered.size
                        Text(
                            text = if (hiddenCount > 0) {
                                "Discovered (${filtered.size} of ${discoveredDestinations.size})"
                            } else {
                                "Discovered (${filtered.size})"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            filtered.forEach { dest ->
                                val hopsLabel = if (dest.hops >= 0) " (${dest.hops}h)" else ""
                                SuggestionChip(
                                    onClick = { destinationHash = dest.hash },
                                    label = {
                                        Text(
                                            text = dest.hash.take(12) + ".." + hopsLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    // 4. Destination hash (auto-filled by chip tap, or manual entry)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = destinationHash,
                        onValueChange = {
                            destinationHash = it.filter { c -> c in "0123456789abcdefABCDEF" }
                                .take(32)
                        },
                        label = { Text("Destination Hash") },
                        placeholder = { Text("32-character hex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Post-login command
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = postLoginCommand,
                        onValueChange = { postLoginCommand = it },
                        label = { Text("Post-login command") },
                        placeholder = { Text("cd /app && clear") },
                        supportingText = { Text("Sent after login.") },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            val canSave = when (connectionType) {
                "LOCAL" -> true // No host/auth needed
                "SSH" -> host.isNotBlank() && username.isNotBlank()
                "VNC" -> host.isNotBlank()
                "RDP" -> host.isNotBlank() && rdpUsername.isNotBlank() && (!rdpSshForward || rdpSshProfileId != null)
                "SMB" -> host.isNotBlank() && smbShare.isNotBlank() && (!smbSshForward || smbSshProfileId != null)
                "RCLONE" -> rcloneProvider.isNotBlank()
                else -> destinationHash.length == 32 && (localSideband || rnsHost.isNotBlank())
            }
            TextButton(
                onClick = {
                    val etPortInt = etPort.toIntOrNull() ?: 2022
                    val profile = if (connectionType == "LOCAL") {
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = "localhost",
                            username = "",
                        )).copy(
                            label = label.ifBlank { if (useAndroidShell) "Android Shell" else "Local Shell" },
                            host = "localhost",
                            port = 0,
                            username = "",
                            connectionType = "LOCAL",
                            useAndroidShell = useAndroidShell,
                            colorTag = colorTag,
                            groupId = groupId,
                        )
                    } else if (connectionType == "VNC") {
                        val vncPortInt = port.toIntOrNull() ?: 5900
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "VNC: $host" },
                            host = host,
                            port = vncPortInt,
                            username = "",
                            connectionType = "VNC",
                            vncPort = vncPortInt,
                            vncUsername = vncUsername.ifBlank { null },
                            vncPassword = vncPassword.ifBlank { null },
                            colorTag = colorTag,
                            groupId = groupId,
                        )
                    } else if (connectionType == "RDP") {
                        val rdpPortInt = port.toIntOrNull() ?: 3389
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            username = rdpUsername,
                        )).copy(
                            label = label.ifBlank { "RDP: $rdpUsername@$host" },
                            host = host,
                            port = rdpPortInt,
                            username = rdpUsername,
                            connectionType = "RDP",
                            rdpPort = rdpPortInt,
                            rdpUsername = rdpUsername.ifBlank { null },
                            rdpPassword = rdpPassword.ifBlank { null },
                            rdpDomain = rdpDomain.ifBlank { null },
                            rdpSshForward = rdpSshForward,
                            rdpSshProfileId = if (rdpSshForward) rdpSshProfileId else null,
                            colorTag = colorTag,
                            groupId = groupId,
                        )
                    } else if (connectionType == "RCLONE") {
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = "",
                            username = "",
                        )).copy(
                            label = label.ifBlank {
                                val providerLabel = when (rcloneProvider) {
                                    "drive" -> "Google Drive"
                                    "dropbox" -> "Dropbox"
                                    "onedrive" -> "OneDrive"
                                    "s3" -> "Amazon S3"
                                    "b2" -> "Backblaze B2"
                                    "mega" -> "MEGA"
                                    "pcloud" -> "pCloud"
                                    "box" -> "Box"
                                    else -> rcloneProvider
                                }
                                providerLabel
                            },
                            host = "",
                            port = 0,
                            username = "",
                            connectionType = "RCLONE",
                            rcloneRemoteName = rcloneRemoteName.ifBlank { rcloneProvider },
                            rcloneProvider = rcloneProvider,
                            colorTag = colorTag,
                            groupId = groupId,
                        )
                    } else if (connectionType == "SMB") {
                        val smbPortInt = port.toIntOrNull() ?: 445
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            username = username,
                        )).copy(
                            label = label.ifBlank { "SMB: \\\\$host\\$smbShare" },
                            host = host,
                            port = smbPortInt,
                            username = username,
                            connectionType = "SMB",
                            smbPort = smbPortInt,
                            smbShare = smbShare.ifBlank { null },
                            smbPassword = smbPassword.ifBlank { null },
                            smbDomain = smbDomain.ifBlank { null },
                            smbSshForward = smbSshForward,
                            smbSshProfileId = if (smbSshForward) smbSshProfileId else null,
                            colorTag = colorTag,
                            groupId = groupId,
                        )
                    } else if (connectionType == "SSH") {
                        val portInt = port.toIntOrNull() ?: 22
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            port = portInt,
                            username = username,
                        )).copy(
                            label = label.ifBlank { "$username@$host" },
                            host = host,
                            port = portInt,
                            username = username,
                            connectionType = "SSH",
                            destinationHash = null,
                            jumpProfileId = jumpProfileId,
                            proxyType = proxyType,
                            proxyHost = proxyHost.ifBlank { null },
                            proxyPort = proxyPort.toIntOrNull() ?: 1080,
                            keyId = keyId,
                            sshOptions = sshOptions.ifBlank { null },
                            moshServerCommand = moshServerCommand.ifBlank { null },
                            postLoginCommand = postLoginCommand.ifBlank { null },
                            postLoginBeforeSessionManager = postLoginBeforeSessionManager,
                            disableAltScreen = disableAltScreen,
                            useAndroidShell = useAndroidShell,
                            forwardAgent = forwardAgent,
                            sessionManager = selectedSessionManager,
                            useMosh = selectedTransport == "MOSH",
                            useEternalTerminal = selectedTransport == "ET",
                            etPort = etPortInt,
                            colorTag = colorTag,
                            groupId = groupId,
                        )
                    } else {
                        val savedHost = if (localSideband) "127.0.0.1" else rnsHost
                        val savedPort = if (localSideband) 37428 else (rnsPort.toIntOrNull() ?: 4242)
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = "",
                            port = 0,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "RNS:${destinationHash.take(12)}" },
                            host = "",
                            port = 0,
                            username = "",
                            connectionType = "RETICULUM",
                            destinationHash = destinationHash.lowercase(),
                            reticulumHost = savedHost,
                            reticulumPort = savedPort,
                            reticulumNetworkName = rnsNetworkName.ifBlank { null },
                            reticulumPassphrase = rnsPassphrase.ifBlank { null },
                            postLoginCommand = postLoginCommand.ifBlank { null },
                            postLoginBeforeSessionManager = postLoginBeforeSessionManager,
                            colorTag = colorTag,
                            groupId = groupId,
                        )
                    }
                    onSave(profile)
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
