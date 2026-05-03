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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import sh.haven.core.data.db.entities.SshKey
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
    val generating by viewModel.generating.collectAsState()
    val error by viewModel.error.collectAsState()
    val needsPassphrase by viewModel.needsPassphrase.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val message by viewModel.message.collectAsState()
    val pendingExportKeyId by viewModel.pendingExportKeyId.collectAsState()
    val pendingCertKeyId by viewModel.pendingCertKeyId.collectAsState()

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

    val certPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val keyId = pendingCertKeyId
        viewModel.clearPendingCert()
        if (uri != null && keyId != null) {
            viewModel.attachCertificateFromUri(context, keyId, uri)
        }
    }

    LaunchedEffect(pendingCertKeyId) {
        pendingCertKeyId?.let {
            certPickerLauncher.launch(arrayOf("*/*"))
        }
    }

    LaunchedEffect(pendingExportKeyId) {
        pendingExportKeyId?.let { keyId ->
            exportLauncher.launch(viewModel.getExportFileName(keyId))
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
        if (keys.isEmpty() && !generating) {
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
                items(keys, key = { it.id }) { sshKey ->
                    Box {
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    copyPublicKey(context, sshKey)
                                },
                                onLongClick = {
                                    contextMenuKeyId = sshKey.id
                                },
                            ),
                            headlineContent = { Text(sshKey.label) },
                            supportingContent = {
                                Column {
                                    Text(
                                        if (sshKey.certificateBytes != null)
                                            "${sshKey.keyType} + cert"
                                        else sshKey.keyType,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        sshKey.fingerprintSha256,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingContent = {
                                Text(
                                    formatDate(sshKey.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (sshKey.keyType.startsWith("sk-"))
                                        Icons.Filled.Key
                                    else
                                        Icons.Filled.VpnKey,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )

                        DropdownMenu(
                            expanded = contextMenuKeyId == sshKey.id,
                            onDismissRequest = { contextMenuKeyId = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.keys_copy_public_key)) },
                                onClick = {
                                    copyPublicKey(context, sshKey)
                                    contextMenuKeyId = null
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                },
                            )
                            if (!sshKey.keyType.startsWith("sk-")) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keys_export_private_key)) },
                                    onClick = {
                                        contextMenuKeyId = null
                                        viewModel.requestExport(sshKey.id)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                                    },
                                )
                            }
                            if (sshKey.certificateBytes != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove certificate") },
                                    onClick = {
                                        viewModel.detachCertificate(sshKey.id)
                                        contextMenuKeyId = null
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null)
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Attach certificate") },
                                    onClick = {
                                        contextMenuKeyId = null
                                        viewModel.requestAttachCertificate(sshKey.id)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.VerifiedUser, contentDescription = null)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    viewModel.deleteKey(sshKey.id)
                                    contextMenuKeyId = null
                                },
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
            }
        }
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
