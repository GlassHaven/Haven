package sh.haven.feature.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Switch
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
import sh.haven.core.ui.PasswordField
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore
import sh.haven.core.security.SshKeyGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeysScreen(
    viewModel: KeysViewModel = hiltViewModel(),
) {
    val keys by viewModel.keys.collectAsState()
    val keyEntries by viewModel.keyEntries.collectAsState()
    val passwordEntries by viewModel.passwordEntries.collectAsState()
    val generating by viewModel.generating.collectAsState()
    val error by viewModel.error.collectAsState()
    val needsPassphrase by viewModel.needsPassphrase.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val message by viewModel.message.collectAsState()
    val pendingExportKeyId by viewModel.pendingExportKeyId.collectAsState()
    val pendingCertKeyId by viewModel.pendingCertKeyId.collectAsState()
    var pendingPasswordWipe by remember { mutableStateOf<KeystoreEntry?>(null) }

    var showAddKeyDialog by remember { mutableStateOf(false) }
    var showGenerateDialog by remember { mutableStateOf(false) }
    var contextMenuKeyId by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboardEmptyMsg = stringResource(R.string.keys_clipboard_empty)
    val clipboardNotKeyMsg = stringResource(R.string.keys_clipboard_not_text_key)
    val publicKeyClipLabel = stringResource(R.string.keys_ssh_public_key_clip_label)

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            viewModel.importFromUri(context, it)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri ->
        val keyId = pendingExportKeyId
        viewModel.clearPendingExport()
        if (uri != null && keyId != null) {
            viewModel.exportPrivateKey(context, keyId, uri)
        }
    }

    LaunchedEffect(pendingExportKeyId) {
        pendingExportKeyId?.let { keyId ->
            exportLauncher.launch(viewModel.getExportFileName(keyId))
        }
    }

    val certPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val keyId = pendingCertKeyId
        viewModel.clearPendingCertificate()
        if (uri != null && keyId != null) {
            viewModel.importCertificateFromUri(context, keyId, uri)
        }
    }

    LaunchedEffect(pendingCertKeyId) {
        pendingCertKeyId?.let {
            // SAF doesn't filter on `*-cert.pub` shape; accept anything
            // and let the ViewModel reject non-cert content.
            certPickerLauncher.launch(arrayOf("*/*"))
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddKeyDialog = true }) {
                if (generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.keys_add_key))
                }
            }
        },
    ) { innerPadding ->
        if (keys.isEmpty() && passwordEntries.isEmpty() && !generating) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.keys_no_ssh_keys),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.keys_tap_to_add),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (keys.isNotEmpty()) {
                    item(key = "ssh-header") {
                        SectionHeader(stringResource(R.string.keys_section_ssh, keys.size))
                    }
                    items(keys, key = { it.id }) { sshKey ->
                        SshKeyAuditRow(
                            sshKey = sshKey,
                            entry = keyEntries[sshKey.id],
                            hasCertificate = sshKey.certificateBytes != null,
                            menuOpen = contextMenuKeyId == sshKey.id,
                            onMenuOpen = { contextMenuKeyId = sshKey.id },
                            onMenuDismiss = { contextMenuKeyId = null },
                            onCopyPublic = { copyPublicKey(context, sshKey) },
                            onExportPrivate = { viewModel.requestExport(sshKey.id) },
                            onDelete = { viewModel.deleteKey(sshKey.id) },
                            onBiometricToggle = { protected ->
                                viewModel.setBiometricProtected(sshKey.id, protected)
                            },
                            onAttachCertificate = { viewModel.requestAttachCertificate(sshKey.id) },
                            onRemoveCertificate = { viewModel.removeCertificate(sshKey.id) },
                        )
                        HorizontalDivider()
                    }
                }
                if (passwordEntries.isNotEmpty()) {
                    item(key = "password-header") {
                        SectionHeader(stringResource(R.string.keys_section_passwords, passwordEntries.size))
                    }
                    items(passwordEntries, key = { "pw-${it.id}" }) { entry ->
                        PasswordAuditRow(
                            entry = entry,
                            onWipeRequested = { pendingPasswordWipe = entry },
                        )
                        HorizontalDivider()
                    }
                }
                item(key = "footer-spacer") { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    pendingPasswordWipe?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingPasswordWipe = null },
            title = { Text(stringResource(R.string.keys_clear_password_title, entry.label)) },
            text = { Text(stringResource(R.string.keys_clear_password_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val pending = pendingPasswordWipe
                    pendingPasswordWipe = null
                    pending?.let { viewModel.wipePasswordEntry(it) }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPasswordWipe = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showAddKeyDialog) {
        AddKeyChooser(
            onGenerate = {
                showAddKeyDialog = false
                showGenerateDialog = true
            },
            onImport = {
                showAddKeyDialog = false
                filePickerLauncher.launch(arrayOf("*/*"))
            },
            onPaste = {
                showAddKeyDialog = false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (text.isNullOrBlank()) {
                    viewModel.showError(clipboardEmptyMsg)
                } else if (!text.startsWith("-----") && !text.startsWith("ssh-")) {
                    viewModel.showError(clipboardNotKeyMsg)
                } else {
                    viewModel.startImport(text.toByteArray())
                }
            },
            onDismiss = { showAddKeyDialog = false },
        )
    }

    if (showGenerateDialog) {
        GenerateKeyDialog(
            onDismiss = { showGenerateDialog = false },
            onGenerate = { label, keyType ->
                viewModel.generateKey(label, keyType)
                showGenerateDialog = false
            },
        )
    }

    if (needsPassphrase) {
        PassphraseDialog(
            onConfirm = { viewModel.retryImportWithPassphrase(it) },
            onDismiss = { viewModel.cancelImport() },
        )
    }

    importResult?.let { result ->
        ImportLabelDialog(
            keyType = result.keyType,
            fingerprint = result.fingerprintSha256,
            onConfirm = { label -> viewModel.saveImportedKey(label) },
            onDismiss = { viewModel.cancelImport() },
        )
    }
}

@Composable
private fun AddKeyChooser(
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_add_ssh_key)) },
        text = {
            Column {
                ListItem(
                    modifier = Modifier.clickable { onGenerate() },
                    headlineContent = { Text(stringResource(R.string.keys_generate_new_key)) },
                    supportingContent = { Text(stringResource(R.string.keys_generate_key_types)) },
                    leadingContent = {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { onImport() },
                    headlineContent = { Text(stringResource(R.string.keys_import_from_file)) },
                    supportingContent = { Text(stringResource(R.string.keys_import_file_formats)) },
                    leadingContent = {
                        Icon(Icons.Filled.FileUpload, contentDescription = null)
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { onPaste() },
                    headlineContent = { Text(stringResource(R.string.keys_paste_from_clipboard)) },
                    supportingContent = { Text(stringResource(R.string.keys_paste_clipboard_hint)) },
                    leadingContent = {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (label: String, keyType: SshKeyGenerator.KeyType) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(SshKeyGenerator.KeyType.ED25519) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_generate_ssh_key)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_label)) },
                    placeholder = { Text(stringResource(R.string.keys_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(modifier = Modifier.padding(top = 16.dp)) {
                    OutlinedTextField(
                        value = selectedType.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.keys_key_type)) },
                        modifier = Modifier
                            .fillMaxWidth(),
                    )
                    // Invisible clickable overlay to open dropdown
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .combinedClickable(onClick = { expanded = true }),
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        SshKeyGenerator.KeyType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.displayName) },
                                onClick = {
                                    selectedType = type
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGenerate(label.ifBlank { selectedType.displayName }, selectedType) },
            ) {
                Text(stringResource(R.string.keys_generate))
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
private fun PassphraseDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_encrypted_key)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.keys_passphrase_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PasswordField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.keys_passphrase),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotEmpty(),
            ) {
                Text(stringResource(R.string.keys_unlock))
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
private fun ImportLabelDialog(
    keyType: String,
    fingerprint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_import_ssh_key)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_label)) },
                    placeholder = { Text(stringResource(R.string.keys_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    keyType,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label.ifBlank { keyType }) },
            ) {
                Text(stringResource(R.string.keys_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

private fun copyPublicKey(context: Context, sshKey: SshKey) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(
        context.getString(R.string.keys_ssh_public_key_clip_label), sshKey.publicKeyOpenSsh,
    ))
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SshKeyAuditRow(
    sshKey: SshKey,
    entry: KeystoreEntry?,
    hasCertificate: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onCopyPublic: () -> Unit,
    onExportPrivate: () -> Unit,
    onDelete: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onAttachCertificate: () -> Unit,
    onRemoveCertificate: () -> Unit,
) {
    val flags = entry?.flags ?: emptySet()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCopyPublic, onLongClick = onMenuOpen),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (sshKey.keyType.startsWith("sk-")) Icons.Filled.Key
                    else Icons.Filled.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = sshKey.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = sshKey.keyType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            text = sshKey.fingerprintSha256,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = formatDate(sshKey.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (flags.isNotEmpty() || hasCertificate) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    flags.sortedBy { it.ordinal }.forEach { flag -> FlagChip(flag) }
                    if (hasCertificate) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.keys_chip_certificate),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Badge,
                                    contentDescription = null,
                                    modifier = Modifier.padding(2.dp),
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.keys_require_biometric),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = KeystoreFlag.BIOMETRIC_PROTECTED in flags,
                    onCheckedChange = onBiometricToggle,
                )
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onMenuDismiss,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.keys_copy_public_key)) },
                onClick = { onCopyPublic(); onMenuDismiss() },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            )
            if (!sshKey.keyType.startsWith("sk-")) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keys_export_private_key)) },
                    onClick = { onMenuDismiss(); onExportPrivate() },
                    leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                )
                // Certificate attach / remove (#133 phase 1). FIDO SK keys
                // skip this — their signing path doesn't compose with
                // OpenSSH cert auth.
                if (hasCertificate) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.keys_remove_certificate)) },
                        onClick = { onMenuDismiss(); onRemoveCertificate() },
                        leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null) },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.keys_attach_certificate)) },
                        onClick = { onMenuDismiss(); onAttachCertificate() },
                        leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null) },
                    )
                }
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { onDelete(); onMenuDismiss() },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasswordAuditRow(
    entry: KeystoreEntry,
    onWipeRequested: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.Password,
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
            if (entry.flags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.flags.sortedBy { it.ordinal }.forEach { flag -> FlagChip(flag) }
                }
            }
        }
        IconButton(onClick = onWipeRequested) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.keys_wipe_content_description, entry.label),
            )
        }
    }
}

@Composable
private fun FlagChip(flag: KeystoreFlag) {
    val labelRes = when (flag) {
        KeystoreFlag.HARDWARE_BACKED -> R.string.keys_chip_hardware_backed
        KeystoreFlag.REQUIRES_PASSPHRASE -> R.string.keys_chip_passphrase
        KeystoreFlag.REQUIRES_USER_PRESENCE -> R.string.keys_chip_user_presence
        KeystoreFlag.REQUIRES_USER_VERIFICATION -> R.string.keys_chip_user_verification
        KeystoreFlag.BIOMETRIC_PROTECTED -> R.string.keys_chip_biometric
    }
    val label = stringResource(labelRes)
    val icon = when (flag) {
        KeystoreFlag.HARDWARE_BACKED -> Icons.Filled.Shield
        KeystoreFlag.REQUIRES_PASSPHRASE -> Icons.Filled.Key
        KeystoreFlag.REQUIRES_USER_PRESENCE -> Icons.Filled.TouchApp
        KeystoreFlag.REQUIRES_USER_VERIFICATION -> Icons.Filled.Fingerprint
        KeystoreFlag.BIOMETRIC_PROTECTED -> Icons.Filled.Fingerprint
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.padding(2.dp)) },
        colors = AssistChipDefaults.assistChipColors(),
    )
}
