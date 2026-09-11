package sh.haven.feature.tunnel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
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
    /**
     * If non-null, the Add Tunnel dialog auto-opens on first composition
     * with this type pre-selected on the chip row. Used by the connection
     * edit dialog's quick-add affordances ("+ New Cloudflare Tunnel" /
     * "+ New WireGuard tunnel") so the user doesn't have to know which
     * chip to pick after navigating in. Default null preserves the
     * existing entry-point behaviour (manual + button, type starts on
     * WireGuard).
     */
    initialAddType: TunnelConfigType? = null,
) {
    val tunnels by viewModel.tunnels.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // Auto-open the Add dialog if the caller pre-selected a type. The
    // key parameter scopes this to the first composition for a given
    // type — re-entering with the same type doesn't re-trigger.
    LaunchedEffect(initialAddType) {
        if (initialAddType != null) {
            showAddDialog = true
        }
    }

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
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tunnel_add))
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
        // Cloudflare Tunnel is no longer a standalone type (GH #154 — it's
        // now an SSH-profile transport). Fall back to WireGuard if a caller
        // pre-selects the retired chip.
        val effectiveInitialType = initialAddType
            ?.takeIf { it != TunnelConfigType.CLOUDFLARE_ACCESS }
            ?: TunnelConfigType.WIREGUARD
        AddTunnelDialog(
            initialType = effectiveInitialType,
            onDismiss = { showAddDialog = false },
            onSubmitWireguard = { label, configText ->
                viewModel.addWireguardConfig(label, configText)
                showAddDialog = false
            },
            onSubmitTailscale = { label, authKey, controlUrl ->
                viewModel.addTailscaleConfig(label, authKey, controlUrl)
                showAddDialog = false
            },
            onSubmitNetbird = { label, setupKey, managementUrl ->
                viewModel.addNetbirdConfig(label, setupKey, managementUrl)
                showAddDialog = false
            },
        )
    }

    pendingDeleteId?.let { id ->
        val tunnel = tunnels.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.tunnel_delete_title)) },
            text = {
                Text(stringResource(R.string.tunnel_delete_message, tunnel?.label ?: id))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    pendingDeleteId = null
                }) { Text(stringResource(R.string.tunnel_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.tunnel_cancel)) }
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
            stringResource(R.string.tunnel_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.tunnel_empty_subtitle),
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
    val formatter = remember { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMd"), Locale.getDefault()) }
    // For Cloudflare Access rows, decode the blob to surface a stale-JWT
    // hint inline; for other types this is skipped entirely.
    val cfExpired = remember(tunnel.id, tunnel.configText) {
        runCatching {
            if (tunnel.typeEnum == sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS) {
                sh.haven.core.tunnel.CloudflareAccessConfigBlob.parse(tunnel.configText)
                    .isJwtExpired()
            } else false
        }.getOrDefault(false)
    }
    ListItem(
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tunnel.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (cfExpired) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            stringResource(R.string.tunnel_cf_sign_in_again),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        },
        supportingContent = {
            val unknown = stringResource(R.string.tunnel_type_unknown)
            val kind = runCatching { tunnelTypeLabel(tunnel.typeEnum) }.getOrDefault(unknown)
            Text(
                stringResource(R.string.tunnel_row_subtitle, kind, formatter.format(Date(tunnel.createdAt))),
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
                    contentDescription = stringResource(R.string.tunnel_delete_content_desc),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun AddTunnelDialog(
    initialType: TunnelConfigType,
    onDismiss: () -> Unit,
    onSubmitWireguard: (label: String, configText: String) -> Unit,
    onSubmitTailscale: (label: String, authKey: String, controlUrl: String) -> Unit,
    onSubmitNetbird: (label: String, setupKey: String, managementUrl: String) -> Unit,
) {
    var type by remember { mutableStateOf(initialType) }
    var label by remember { mutableStateOf("") }
    var configText by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    var controlUrl by remember { mutableStateOf("") }
    var setupKey by remember { mutableStateOf("") }
    var managementUrl by remember { mutableStateOf("") }
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
                stringResource(R.string.tunnel_add),
                style = MaterialTheme.typography.headlineSmall,
            )

            // Type picker — FilterChip row over the backends. Each
            // toggles the fields below; label persists across flips.
            // Cloudflare Tunnel is omitted here on purpose: it lives as
            // an SSH-profile transport (GH #154), not a standalone tunnel.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TunnelConfigType.entries
                    .filter { it != TunnelConfigType.CLOUDFLARE_ACCESS }
                    .forEach { t ->
                        androidx.compose.material3.FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(tunnelTypeLabel(t)) },
                        )
                    }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.tunnel_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when (type) {
                TunnelConfigType.WIREGUARD -> {
                    Text(
                        stringResource(R.string.tunnel_wireguard_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { fileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tunnel_load_from_file))
                    }
                    OutlinedTextField(
                        value = configText,
                        onValueChange = { configText = it },
                        label = { Text(stringResource(R.string.tunnel_wireguard_config)) },
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
                TunnelConfigType.TAILSCALE -> {
                    Text(
                        stringResource(R.string.tunnel_tailscale_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = authKey,
                        onValueChange = { authKey = it },
                        label = { Text(stringResource(R.string.tunnel_tailscale_authkey_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    OutlinedTextField(
                        value = controlUrl,
                        onValueChange = { controlUrl = it },
                        label = { Text(stringResource(R.string.tunnel_tailscale_control_url_label)) },
                        placeholder = {
                            Text(
                                "https://headscale.example.com",
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        supportingText = {
                            Text(
                                stringResource(R.string.tunnel_tailscale_control_url_help),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                TunnelConfigType.NETBIRD -> {
                    Text(
                        stringResource(R.string.tunnel_netbird_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = setupKey,
                        onValueChange = { setupKey = it },
                        label = { Text(stringResource(R.string.tunnel_netbird_setup_key_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    OutlinedTextField(
                        value = managementUrl,
                        onValueChange = { managementUrl = it },
                        label = { Text(stringResource(R.string.tunnel_netbird_management_url_label)) },
                        placeholder = {
                            Text(
                                "https://netbird.example.com",
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        supportingText = {
                            Text(
                                stringResource(R.string.tunnel_netbird_management_url_help),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                TunnelConfigType.CLOUDFLARE_ACCESS -> {
                    // Retired from this dialog (GH #154). Filtered out of
                    // the chip row above, so this branch is unreachable in
                    // practice; it exists only because the enum still
                    // includes the value for legacy standalone rows in
                    // the list.
                    Unit
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tunnel_cancel)) }
                val canSubmit = label.isNotBlank() && when (type) {
                    TunnelConfigType.WIREGUARD -> configText.isNotBlank()
                    TunnelConfigType.TAILSCALE -> authKey.isNotBlank()
                    TunnelConfigType.NETBIRD -> setupKey.isNotBlank()
                    TunnelConfigType.CLOUDFLARE_ACCESS -> false
                }
                Button(
                    onClick = {
                        when (type) {
                            TunnelConfigType.WIREGUARD ->
                                onSubmitWireguard(label, configText)
                            TunnelConfigType.TAILSCALE ->
                                onSubmitTailscale(label, authKey, controlUrl)
                            TunnelConfigType.NETBIRD ->
                                onSubmitNetbird(label, setupKey, managementUrl)
                            TunnelConfigType.CLOUDFLARE_ACCESS -> Unit
                        }
                    },
                    enabled = canSubmit,
                ) { Text(stringResource(R.string.tunnel_save)) }
            }
            }
        }
    }
}

private fun tunnelTypeLabel(t: TunnelConfigType): String =
    when (t) {
        TunnelConfigType.WIREGUARD -> "WireGuard"
        TunnelConfigType.TAILSCALE -> "Tailscale"
        TunnelConfigType.NETBIRD -> "NetBird"
        TunnelConfigType.CLOUDFLARE_ACCESS -> "Cloudflare Tunnel"
    }

