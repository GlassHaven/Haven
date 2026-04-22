package sh.haven.feature.tunnel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.typeEnum
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists tunnel configs the user has saved, with add + delete.
 * Referenced from [ConnectionsViewModel]'s profile edit flow via the
 * tunnel dropdown's "Manage tunnels..." link, and from Settings.
 *
 * WireGuard is the only backend wired at launch. Tailscale shares the
 * screen but its "Add" path is disabled until the tsnet bridge lands —
 * surfacing as a greyed option communicates the roadmap.
 */
@Composable
fun TunnelsScreen(
    viewModel: TunnelViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null,
) {
    val tunnels by viewModel.tunnels.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add tunnel")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tunnels.isEmpty()) {
                EmptyState(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = 8.dp,
                    ),
                ) {
                    items(tunnels, key = { it.id }) { tunnel ->
                        TunnelRow(
                            tunnel = tunnel,
                            onDelete = { pendingDeleteId = tunnel.id },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTunnelDialog(
            onDismiss = { showAddDialog = false },
            onSubmitWireguard = { label, configText ->
                viewModel.addWireguardConfig(label, configText)
                showAddDialog = false
            },
            onSubmitTailscale = { label, authKey ->
                viewModel.addTailscaleConfig(label, authKey)
                showAddDialog = false
            },
        )
    }

    pendingDeleteId?.let { id ->
        val tunnel = tunnels.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete tunnel?") },
            text = {
                Text("Remove \"${tunnel?.label ?: id}\". Profiles referencing this tunnel will fail to connect until you pick another one.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    pendingDeleteId = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.VpnLock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No tunnels configured yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Add a WireGuard config to route individual connection profiles through it — no system-wide VPN required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TunnelRow(
    tunnel: TunnelConfig,
    onDelete: () -> Unit,
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    ListItem(
        headlineContent = {
            Text(
                tunnel.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val kind = runCatching { tunnel.typeEnum.name }.getOrDefault("unknown")
            Text(
                "$kind · added ${formatter.format(Date(tunnel.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(Icons.Filled.VpnLock, contentDescription = null)
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete tunnel",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun AddTunnelDialog(
    onDismiss: () -> Unit,
    onSubmitWireguard: (label: String, configText: String) -> Unit,
    onSubmitTailscale: (label: String, authKey: String) -> Unit,
) {
    var type by remember { mutableStateOf(sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD) }
    var label by remember { mutableStateOf("") }
    var configText by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Use OpenDocument (SAF) rather than GetContent so the user can pick
    // the file from any provider — Drive, NextCloud, Files app, etc.
    // Filtering to text/* and */* because .conf files often show up as
    // application/octet-stream depending on the provider.
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@rememberLauncherForActivityResult
            configText = text
            if (label.isBlank()) {
                // Best-effort label from filename. DocumentsContract gives us
                // a _display_name via query; for simplicity, extract from the
                // URI's last path segment and strip .conf suffix.
                val last = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                val guessed = last.substringAfterLast(':').removeSuffix(".conf")
                if (guessed.isNotBlank()) label = guessed
            }
        } catch (_: Throwable) {
            // Surface via snackbar? For MVP, keep the dialog open and let
            // the user notice nothing populated.
        }
    }

    // Use a full-size Dialog rather than AlertDialog so the config editor
    // gets real screen width instead of the AlertDialog's narrow column.
    // Wrap in Surface so the dialog has an opaque background — without it
    // the full-size Dialog draws against whatever's behind it, making the
    // whole sheet look semi-transparent (#105).
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Text(
                "Add tunnel",
                style = MaterialTheme.typography.headlineSmall,
            )

            // Type picker — FilterChip row over the two backends. Each
            // toggles the fields below; label persists across flips.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sh.haven.core.data.db.entities.TunnelConfigType.entries.forEach { t ->
                    androidx.compose.material3.FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        label = {
                            Text(t.name.lowercase().replaceFirstChar { it.titlecase() })
                        },
                    )
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when (type) {
                sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD -> {
                    Text(
                        "Paste a wg-quick style config or load a .conf file. Only [Interface] and [Peer] fields are read; unknown keys (PostUp, Table, MTU) are ignored. Hostname endpoints and DNS names are resolved at tunnel start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { fileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Text("  Load from file…")
                    }
                    OutlinedTextField(
                        value = configText,
                        onValueChange = { configText = it },
                        label = { Text("WireGuard config") },
                        placeholder = {
                            Text(
                                "[Interface]\nPrivateKey = …\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = …\nEndpoint = vpn.example.com:51820\nAllowedIPs = 0.0.0.0/0",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        },
                        singleLine = false,
                        minLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                    )
                }
                sh.haven.core.data.db.entities.TunnelConfigType.TAILSCALE -> {
                    Text(
                        "Generate an authkey in the Tailscale admin console (Settings → Keys). Haven joins your tailnet on first use and reuses the node state after that, so a one-time key is fine. Reusable keys let you reconnect after reinstall.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = authKey,
                        onValueChange = { authKey = it },
                        label = { Text("Auth key (tskey-auth-…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                val canSubmit = label.isNotBlank() && when (type) {
                    sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD -> configText.isNotBlank()
                    sh.haven.core.data.db.entities.TunnelConfigType.TAILSCALE -> authKey.isNotBlank()
                }
                Button(
                    onClick = {
                        when (type) {
                            sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD ->
                                onSubmitWireguard(label, configText)
                            sh.haven.core.data.db.entities.TunnelConfigType.TAILSCALE ->
                                onSubmitTailscale(label, authKey)
                        }
                    },
                    enabled = canSubmit,
                ) { Text("Save") }
            }
            }
        }
    }
}
