package sh.haven.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore

/**
 * Settings → "Security audit" screen. Renders the [Keystore] snapshot
 * grouped by [KeystoreStore] with per-entry metadata (algorithm,
 * fingerprint, capability flags) and a wipe action behind a confirm
 * dialog.
 *
 * No plaintext key material reaches this composable — the audit screen
 * is read-only over redacted [KeystoreEntry] descriptors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoreAuditScreen(
    onBack: () -> Unit,
    viewModel: KeystoreAuditViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var pendingWipe by remember { mutableStateOf<KeystoreEntry?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Security audit") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        val entries = snapshot?.entries
        when {
            entries == null && busy -> {
                EmptyOrLoading(text = "Loading audit snapshot…")
            }
            entries.isNullOrEmpty() -> {
                EmptyOrLoading(
                    text = "No keys or stored credentials yet.\n\n" +
                        "When you import an SSH key, register a FIDO security key, " +
                        "or save a password on a connection profile, it will show up here.",
                )
            }
            else -> {
                val grouped = remember(entries) { entries.groupBy { it.store } }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "summary") {
                        SummaryHeader(snapshot)
                    }
                    grouped.forEach { (store, sectionEntries) ->
                        item(key = "header-$store") {
                            StoreHeader(store, sectionEntries.size)
                        }
                        items(items = sectionEntries, key = { "${it.store}|${it.id}" }) { entry ->
                            EntryRow(
                                entry = entry,
                                onWipeRequested = { pendingWipe = entry },
                                onBiometricToggle = { protected ->
                                    viewModel.setBiometricProtected(entry, protected) { ok ->
                                        if (!ok) {
                                            lastResult = "Biometric protection not supported for ${entry.label}"
                                        }
                                    }
                                },
                            )
                        }
                    }
                    item(key = "footer") {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    pendingWipe?.let { entry ->
        WipeConfirmDialog(
            entry = entry,
            onConfirm = {
                pendingWipe = null
                viewModel.wipe(entry) { ok ->
                    lastResult = if (ok) "Wiped ${entry.label}" else "Could not wipe ${entry.label}"
                }
            },
            onDismiss = { pendingWipe = null },
        )
    }

    lastResult?.let { msg ->
        // Tiny inline banner in lieu of a snackbar host — keeps the
        // screen self-contained without dragging a Scaffold setup in.
        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = msg, modifier = Modifier.padding(end = 8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { lastResult = null }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun EmptyOrLoading(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryHeader(snapshot: sh.haven.core.security.KeystoreAuditSnapshot?) {
    snapshot ?: return
    val byKind = snapshot.countsByKind
    val parts = buildList {
        byKind[KeyKind.SSH_PRIVATE]?.takeIf { it > 0 }?.let { add("$it SSH key${if (it == 1) "" else "s"}") }
        byKind[KeyKind.SSH_FIDO_SK]?.takeIf { it > 0 }?.let { add("$it FIDO credential${if (it == 1) "" else "s"}") }
        byKind[KeyKind.PROFILE_PASSWORD]?.takeIf { it > 0 }?.let { add("$it stored password${if (it == 1) "" else "s"}") }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = if (parts.isEmpty()) "Empty keystore" else parts.joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Metadata-only view. The audit never sees private bytes or password values.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun StoreHeader(store: KeystoreStore, count: Int) {
    val label = when (store) {
        KeystoreStore.SSH_KEYS -> "SSH keys"
        KeystoreStore.PROFILE_CREDENTIALS -> "Profile passwords"
    }
    Text(
        text = "$label  ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(
    entry: KeystoreEntry,
    onWipeRequested: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = iconFor(entry.keyKind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entry.algorithm,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.fingerprint?.let {
                SelectionContainer {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.flags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.flags.sortedBy { it.ordinal }.forEach { flag ->
                        FlagChip(flag)
                    }
                }
            }
            // Biometric toggle is SSH-keys-only today (UnifiedKeystore
            // returns false for credential entries). Hiding the row on
            // unsupported kinds avoids a misleading "this didn't do
            // anything" tap.
            if (entry.keyKind == KeyKind.SSH_PRIVATE || entry.keyKind == KeyKind.SSH_FIDO_SK) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Require biometric to use",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = KeystoreFlag.BIOMETRIC_PROTECTED in entry.flags,
                        onCheckedChange = onBiometricToggle,
                    )
                }
            }
        }
        IconButton(onClick = onWipeRequested) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Wipe ${entry.label}",
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun FlagChip(flag: KeystoreFlag) {
    val (label, icon) = when (flag) {
        KeystoreFlag.HARDWARE_BACKED -> "Hardware-backed" to Icons.Filled.Shield
        KeystoreFlag.REQUIRES_PASSPHRASE -> "Passphrase" to Icons.Filled.Key
        KeystoreFlag.REQUIRES_USER_PRESENCE -> "User presence" to Icons.Filled.TouchApp
        KeystoreFlag.REQUIRES_USER_VERIFICATION -> "User verification" to Icons.Filled.Fingerprint
        KeystoreFlag.BIOMETRIC_PROTECTED -> "Biometric required" to Icons.Filled.Fingerprint
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(2.dp))
        },
        colors = AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun WipeConfirmDialog(
    entry: KeystoreEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val explanation = when (entry.store) {
        KeystoreStore.SSH_KEYS -> "Wiping removes the key entirely. Any profile that uses it will need a new key before connecting again."
        KeystoreStore.PROFILE_CREDENTIALS -> "Wiping clears the saved password for this profile. The profile itself stays; you'll be prompted next connect."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wipe ${entry.label}?") },
        text = {
            Column {
                Text(explanation)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Algorithm: ${entry.algorithm}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.fingerprint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Wipe") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun iconFor(kind: KeyKind): ImageVector = when (kind) {
    KeyKind.SSH_PRIVATE -> Icons.Filled.Key
    KeyKind.SSH_FIDO_SK -> Icons.Filled.Fingerprint
    KeyKind.PROFILE_PASSWORD -> Icons.Filled.Password
}
