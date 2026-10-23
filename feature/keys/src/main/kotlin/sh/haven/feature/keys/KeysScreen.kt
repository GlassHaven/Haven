package sh.haven.feature.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import sh.haven.core.ssh.openkeychain.OpenKeychainKeyData
import sh.haven.core.ssh.openkeychain.OpenKeychainProvider
import sh.haven.core.ui.PasswordField
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.security.Totp
import sh.haven.core.data.db.entities.TotpSecret
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Section ids for the collapsed-set preference (#460). Stable strings, not
// enum ordinals or resource ids — they are persisted.
internal const val SECTION_CA = "ca"
internal const val SECTION_SSH = "ssh"
internal const val SECTION_PASSWORDS = "passwords"
internal const val SECTION_TOTP = "totp"
internal const val SECTION_AGE = "age"
internal const val SECTION_IDENTITIES = "identities"

/**
 * USB drive VM keys. INVERTED persistence: presence in the collapsed set means
 * EXPANDED. Every other section defaults to expanded once the user has toggled
 * anything (the stored set replaces [DEFAULT_COLLAPSED_SECTIONS] wholesale), but
 * this section holds infrastructure keys and must start collapsed for existing
 * users too — whose stored set can't already contain it. Absent = collapsed
 * gives that without a second preference; toggleSection works unchanged.
 */
internal const val SECTION_DRIVE = "drive"

/**
 * Collapsed on first run — everything except TOTP. Applied only when the user has never
 * toggled a section; see [KeysViewModel.collapsedSections].
 */
internal val DEFAULT_COLLAPSED_SECTIONS = setOf(
    SECTION_CA,
    SECTION_SSH,
    SECTION_PASSWORDS,
    SECTION_AGE,
    SECTION_IDENTITIES,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeysScreen(
    viewModel: KeysViewModel = hiltViewModel(),
) {
    val keys by viewModel.keys.collectAsState()
    val keyEntries by viewModel.keyEntries.collectAsState()
    val skVerifyRequired by viewModel.skVerifyRequired.collectAsState()
    val passwordEntries by viewModel.passwordEntries.collectAsState()
    val generating by viewModel.generating.collectAsState()
    val error by viewModel.error.collectAsState()
    val needsPassphrase by viewModel.needsPassphrase.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val message by viewModel.message.collectAsState()
    val pendingExportKeyId by viewModel.pendingExportKeyId.collectAsState()
    val pendingCertExportKeyId by viewModel.pendingCertExportKeyId.collectAsState()
    val pendingCertKeyId by viewModel.pendingCertKeyId.collectAsState()
    var pendingPasswordWipe by remember { mutableStateOf<KeystoreEntry?>(null) }
    var renameTarget by remember { mutableStateOf<SshKey?>(null) }
    var renameTotpTarget by remember { mutableStateOf<TotpSecret?>(null) }
    // #460. Null = not in multi-select mode; a set (possibly empty) = in it.
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<Set<String>?>(null) }
    val sortMode by viewModel.sortMode.collectAsState()
    val collapsedSections by viewModel.collapsedSections.collectAsState()
    val keysInUse by viewModel.keysInUse.collectAsState()
    val sshExpanded = SECTION_SSH !in collapsedSections
    // Inverted persistence — absent means collapsed; see SECTION_DRIVE.
    val driveExpanded = SECTION_DRIVE in collapsedSections
    val (userKeys, driveKeys) = partitionDriveKeys(keys)
    var pendingStorePassphrase by remember { mutableStateOf<SshKey?>(null) }

    var showAddKeyDialog by remember { mutableStateOf(false) }
    var showGenerateDialog by remember { mutableStateOf(false) }
    var showStepCaDialog by remember { mutableStateOf(false) }
    var showSecurityKeyChooser by remember { mutableStateOf(false) }
    var showRegisterSkDialog by remember { mutableStateOf(false) }
    var showGenerateAgeDialog by remember { mutableStateOf(false) }
    val stepCaConfigs by viewModel.stepCaConfigs.collectAsState()
    val totpSecrets by viewModel.totpSecrets.collectAsState()
    val ageIdentities by viewModel.ageIdentities.collectAsState()
    val sshIdentities by viewModel.sshIdentities.collectAsState()
    // Non-null while the identity editor is open: an existing row to edit, or
    // a fresh blank row to create (#360).
    var editingIdentity by remember { mutableStateOf<sh.haven.core.data.db.entities.SshIdentity?>(null) }
    // CA section ViewModel — separate hilt instance, shared with the
    // section composable inside the LazyColumn. (#133 phase 2b — CA
    // management moved out of Settings into the Keys tab.)
    val stepCaConfigsViewModel: StepCaConfigsViewModel = hiltViewModel()
    val stepCaSectionConfigs by stepCaConfigsViewModel.configs.collectAsState()
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

    val certExportLauncher = rememberLauncherForActivityResult(
        // Neutral type so DocumentsUI keeps the ".pub" name instead of
        // appending ".pem" (as it does for application/x-pem-file). (#185)
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val keyId = pendingCertExportKeyId
        viewModel.clearPendingCertExport()
        if (uri != null && keyId != null) {
            viewModel.exportCertificate(context, keyId, uri)
        }
    }

    LaunchedEffect(pendingCertExportKeyId) {
        pendingCertExportKeyId?.let { keyId ->
            certExportLauncher.launch(viewModel.getCertExportFileName(keyId))
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

    // TOTP QR scan (#178): pick an image containing an otpauth:// QR code.
    val totpQrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { viewModel.addTotpFromImage(context, it) }
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

    // FIDO touch / PIN dialog during "Discover from security key" enumeration
    val fidoPrompt by viewModel.fidoTouchPrompt.collectAsState()
    fidoPrompt?.let { KeysFidoTouchPromptDialog(it, onCancel = { viewModel.cancelFido() }) }

    // Picker dialog after enumeration returns one-or-more credentials
    val discovered by viewModel.discoveredCredentials.collectAsState()
    if (discovered.isNotEmpty()) {
        DiscoveredCredentialsPicker(
            credentials = discovered,
            onImport = { labels -> viewModel.importDiscoveredCredentials(labels) },
            onDismiss = { viewModel.dismissDiscoveryPicker() },
        )
    }

    Scaffold(
        // Defer to the app Scaffold background so the global background-opacity
        // (wallpaper see-through) applies here too.
        containerColor = Color.Transparent,
        // contentColorFor(Transparent) has no scheme match and resolves to
        // Unspecified, so any Text without an explicit colour falls back to
        // black — invisible in dark theme. Pin it to onSurface.
        contentColor = MaterialTheme.colorScheme.onSurface,
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
        // The CA section must always be reachable. Earlier this branched
        // on a "nothing here" condition and rendered a centered empty-
        // state instead — but the empty-state hid the Certificate
        // authorities section too, so a fresh-install user who wanted
        // to start with a step-ca-issued key had no way to register a
        // CA first (#133). Now the LazyColumn always renders, with the
        // empty-state hint as an item below the CA section when there
        // are no keys, passwords, or CA configs yet.
        val nothingButCa = keys.isEmpty() && passwordEntries.isEmpty() &&
            stepCaSectionConfigs.isEmpty() && totpSecrets.isEmpty() && ageIdentities.isEmpty() && !generating
        // One invocation shared by the user-keys and drive-keys sections — the
        // row and its menu behave identically, only reorderability differs.
        val sshKeyRow: @Composable (SshKey, Boolean, Boolean) -> Unit = { sshKey, canMoveUp, canMoveDown ->
            SshKeyAuditRow(
                sshKey = sshKey,
                entry = keyEntries[sshKey.id],
                hasCertificate = sshKey.certificateBytes != null,
                verifyRequired = skVerifyRequired[sshKey.id] ?: false,
                menuOpen = contextMenuKeyId == sshKey.id,
                onMenuOpen = { contextMenuKeyId = sshKey.id },
                onMenuDismiss = { contextMenuKeyId = null },
                onCopyPublic = { copyPublicKey(context, sshKey) },
                onRename = { renameTarget = sshKey },
                onExportPrivate = { viewModel.requestExport(sshKey.id) },
                onDelete = { viewModel.deleteKey(sshKey.id) },
                onBiometricToggle = { protected ->
                    viewModel.setBiometricProtected(sshKey.id, protected)
                },
                onEnabledForAuthToggle = { enabled ->
                    viewModel.setKeyEnabledForAuth(sshKey.id, enabled)
                },
                onStorePassphraseToggle = { store ->
                    if (store) pendingStorePassphrase = sshKey
                    else viewModel.setKeyStoredPassphrase(sshKey.id, null)
                },
                onSetVerifyRequired = { required ->
                    viewModel.setSkVerifyRequired(sshKey.id, required)
                },
                onAttachCertificate = { viewModel.requestAttachCertificate(sshKey.id) },
                onRemoveCertificate = { viewModel.removeCertificate(sshKey.id) },
                onExportCertificate = { viewModel.requestCertExport(sshKey.id) },
                onRegenerateViaStepCa = { viewModel.regenerateViaStepCa(sshKey.id) },
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = { viewModel.moveKey(sshKey.id, up = true) },
                onMoveDown = { viewModel.moveKey(sshKey.id, up = false) },
                selected = selection?.let { sshKey.id in it },
                onStartSelection = { selection = setOf(sshKey.id) },
                onToggleSelected = {
                    selection = selection?.let {
                        if (sshKey.id in it) it - sshKey.id else it + sshKey.id
                    }
                },
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item(key = "stepca-ca-section") {
                StepCaConfigsSectionContent(
                    viewModel = stepCaConfigsViewModel,
                    expanded = SECTION_CA !in collapsedSections,
                    onToggleExpanded = { viewModel.toggleSection(SECTION_CA) },
                )
                HorizontalDivider()
            }
            if (nothingButCa) {
                item(key = "empty-state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
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
                }
            }
            if (keys.isNotEmpty()) {
                    item(key = "ssh-header") {
                        val current = selection
                        if (current != null) {
                            KeySelectionBar(
                                count = current.size,
                                onSelectAll = { selection = keys.map { it.id }.toSet() },
                                onDelete = { pendingBulkDelete = current },
                                onCancel = { selection = null },
                            )
                        } else {
                            SectionHeader(
                                label = stringResource(R.string.keys_section_ssh, userKeys.size),
                                expanded = sshExpanded,
                                onToggle = { viewModel.toggleSection(SECTION_SSH) },
                                trailing = {
                                    KeySortMenu(
                                        current = sortMode,
                                        onSelect = { viewModel.setSortMode(it) },
                                    )
                                },
                            )
                        }
                    }
                    itemsIndexed(
                        if (sshExpanded) userKeys else emptyList(),
                        key = { _, k -> k.id },
                    ) { index, sshKey ->
                        sshKeyRow(
                            sshKey,
                            // Reordering only makes sense under the manual
                            // order — under a sort the move would be computed
                            // away, so the actions are hidden (#460).
                            sortMode == KeySort.MANUAL && index > 0,
                            sortMode == KeySort.MANUAL && index < userKeys.lastIndex,
                        )
                        HorizontalDivider()
                    }
                }
                if (driveKeys.isNotEmpty()) {
                    item(key = "drive-header") {
                        SectionHeader(
                            label = stringResource(R.string.keys_section_drive, driveKeys.size),
                            expanded = driveExpanded,
                            onToggle = { viewModel.toggleSection(SECTION_DRIVE) },
                        )
                    }
                    items(
                        if (driveExpanded) driveKeys else emptyList(),
                        key = { it.id },
                    ) { sshKey ->
                        // Grouped by kind — manual reorder has nothing to express.
                        sshKeyRow(sshKey, false, false)
                        HorizontalDivider()
                    }
                }
                if (passwordEntries.isNotEmpty()) {
                    item(key = "password-header") {
                        SectionHeader(
                            label = stringResource(R.string.keys_section_passwords, passwordEntries.size),
                            expanded = SECTION_PASSWORDS !in collapsedSections,
                            onToggle = { viewModel.toggleSection(SECTION_PASSWORDS) },
                        )
                    }
                    items(
                        if (SECTION_PASSWORDS in collapsedSections) emptyList() else passwordEntries,
                        key = { "pw-${it.id}" },
                    ) { entry ->
                        PasswordAuditRow(
                            entry = entry,
                            onWipeRequested = { pendingPasswordWipe = entry },
                        )
                        HorizontalDivider()
                    }
                }
            if (totpSecrets.isNotEmpty()) {
                item(key = "totp-header") {
                    SectionHeader(
                        label = stringResource(R.string.keys_totp_section_header, totpSecrets.size),
                        expanded = SECTION_TOTP !in collapsedSections,
                        onToggle = { viewModel.toggleSection(SECTION_TOTP) },
                    )
                }
                items(
                    if (SECTION_TOTP in collapsedSections) emptyList() else totpSecrets,
                    key = { "totp-${it.id}" },
                ) { secret ->
                    TotpSecretRow(
                        secret = secret,
                        onRename = { renameTotpTarget = secret },
                        onDelete = { viewModel.deleteTotp(secret.id) },
                    )
                    HorizontalDivider()
                }
            }
            if (ageIdentities.isNotEmpty()) {
                item(key = "age-header") {
                    SectionHeader(
                        label = stringResource(R.string.keys_age_section_header, ageIdentities.size),
                        expanded = SECTION_AGE !in collapsedSections,
                        onToggle = { viewModel.toggleSection(SECTION_AGE) },
                    )
                }
                items(
                    if (SECTION_AGE in collapsedSections) emptyList() else ageIdentities,
                    key = { "age-${it.id}" },
                ) { id ->
                    AgeIdentityRow(identity = id, onDelete = { viewModel.deleteAgeIdentity(id.id) })
                    HorizontalDivider()
                }
            }
            if (sshIdentities.isNotEmpty()) {
                item(key = "identity-header") {
                    SectionHeader(
                        label = stringResource(R.string.keys_identity_section_header, sshIdentities.size),
                        expanded = SECTION_IDENTITIES !in collapsedSections,
                        onToggle = { viewModel.toggleSection(SECTION_IDENTITIES) },
                    )
                }
                items(
                    if (SECTION_IDENTITIES in collapsedSections) emptyList() else sshIdentities,
                    key = { "identity-${it.id}" },
                ) { ident ->
                    SshIdentityRow(
                        identity = ident,
                        keyLabel = keys.firstOrNull { it.id == ident.keyId }?.label,
                        onClick = { editingIdentity = ident },
                        onDelete = { viewModel.deleteSshIdentity(ident.id) },
                    )
                    HorizontalDivider()
                }
            }
            item(key = "footer-spacer") { Spacer(Modifier.height(80.dp)) }
        }
    }

    pendingStorePassphrase?.let { key ->
        PassphraseDialog(
            title = stringResource(R.string.keys_store_passphrase),
            prompt = stringResource(R.string.keys_store_passphrase_warning),
            confirmLabel = stringResource(R.string.keys_store_passphrase_confirm),
            onConfirm = { passphrase ->
                pendingStorePassphrase = null
                viewModel.setKeyStoredPassphrase(key.id, passphrase)
            },
            onDismiss = { pendingStorePassphrase = null },
        )
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

    // Bulk-delete confirmation (#460). Names every key going, and calls out
    // the ones a saved connection authenticates with — deleting several at
    // once is where "which one was that?" costs the most.
    pendingBulkDelete?.let { ids ->
        val doomed = keys.filter { it.id in ids }
        val inUse = doomed.filter { it.id in keysInUse }
        AlertDialog(
            onDismissRequest = { pendingBulkDelete = null },
            title = {
                Text(pluralStringResource(R.plurals.keys_bulk_delete_title, doomed.size, doomed.size))
            },
            text = {
                // Scrolls: the whole point of this dialog is deleting many at
                // once, and an unscrollable list would clip the tail on a phone.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.keys_bulk_delete_body))
                    Spacer(Modifier.height(8.dp))
                    doomed.forEach { key ->
                        Text(
                            text = "• ${key.label}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (inUse.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.keys_bulk_delete_in_use,
                                inUse.joinToString(", ") { it.label },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingBulkDelete = null
                    selection = null
                    viewModel.deleteKeys(ids)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showAddKeyDialog) {
        // Queried once per dialog rather than per recomposition; a
        // provider appearing mid-dialog is not worth a live watch.
        val keyProviders = remember { viewModel.openKeychainProviders() }
        AddKeyChooser(
            stepCaConfigCount = stepCaConfigs.size,
            onGenerate = {
                showAddKeyDialog = false
                showGenerateDialog = true
            },
            onGenerateStepCa = {
                showAddKeyDialog = false
                showStepCaDialog = true
            },
            onImport = {
                showAddKeyDialog = false
                filePickerLauncher.launch(arrayOf("*/*"))
            },
            onSecurityKey = {
                showAddKeyDialog = false
                showSecurityKeyChooser = true
            },
            keyProviders = keyProviders,
            onKeyProvider = { provider ->
                showAddKeyDialog = false
                viewModel.addProviderKey(provider.packageName)
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
            onAddTotpPaste = {
                showAddKeyDialog = false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (text.isNullOrBlank()) {
                    viewModel.showError(clipboardEmptyMsg)
                } else {
                    viewModel.addTotpFromText(text)
                }
            },
            onScanTotpQr = {
                showAddKeyDialog = false
                totpQrLauncher.launch("image/*")
            },
            onGenerateAgeIdentity = {
                showAddKeyDialog = false
                showGenerateAgeDialog = true
            },
            onAddSshIdentity = {
                showAddKeyDialog = false
                editingIdentity = sh.haven.core.data.db.entities.SshIdentity(name = "", username = "")
            },
            onDismiss = { showAddKeyDialog = false },
        )
    }

    editingIdentity?.let { identity ->
        SshIdentityDialog(
            identity = identity,
            sshKeys = keys,
            onSave = { name, username, password, keyId ->
                // identity.id is a fresh UUID for a new row or the existing
                // one for an edit; upsertFromEditor resolves which by lookup.
                viewModel.saveSshIdentity(identity.id, name, username, password, keyId)
                editingIdentity = null
            },
            onDismiss = { editingIdentity = null },
        )
    }

    if (showGenerateAgeDialog) {
        var ageLabel by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGenerateAgeDialog = false },
            title = { Text(stringResource(R.string.keys_generate_age_title)) },
            text = {
                OutlinedTextField(
                    value = ageLabel,
                    onValueChange = { ageLabel = it },
                    label = { Text(stringResource(R.string.keys_age_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showGenerateAgeDialog = false
                    viewModel.generateAgeIdentity(ageLabel)
                }) { Text(stringResource(R.string.keys_generate_age)) }
            },
            dismissButton = {
                TextButton(onClick = { showGenerateAgeDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showGenerateDialog) {
        GenerateKeyDialog(
            onDismiss = { showGenerateDialog = false },
            onGenerate = { label, keyType, hardwareBacked ->
                if (hardwareBacked) viewModel.generateHardwareKey(label)
                else viewModel.generateKey(label, keyType)
                showGenerateDialog = false
            },
        )
    }

    if (showStepCaDialog && stepCaConfigs.isNotEmpty()) {
        GenerateStepCaDialog(
            cas = stepCaConfigs,
            onDismiss = { showStepCaDialog = false },
            onGenerate = { label, caId, principals ->
                viewModel.generateViaStepCa(label, caId, principals)
                showStepCaDialog = false
            },
        )
    }

    if (showSecurityKeyChooser) {
        SecurityKeyChooser(
            onSetUp = {
                showSecurityKeyChooser = false
                showRegisterSkDialog = true
            },
            onImport = {
                showSecurityKeyChooser = false
                viewModel.discoverFromSecurityKey()
            },
            onDismiss = { showSecurityKeyChooser = false },
        )
    }

    if (showRegisterSkDialog) {
        RegisterOnSecurityKeyDialog(
            onDismiss = { showRegisterSkDialog = false },
            onRegister = { label, verifyRequired, pin ->
                viewModel.registerOnSecurityKey(label, verifyRequired, pin)
                showRegisterSkDialog = false
            },
        )
    }

    renameTotpTarget?.let { secret ->
        RenameKeyDialog(
            currentLabel = secret.label,
            onConfirm = { newLabel ->
                viewModel.renameTotp(secret.id, newLabel)
                renameTotpTarget = null
            },
            onDismiss = { renameTotpTarget = null },
        )
    }

    renameTarget?.let { key ->
        RenameKeyDialog(
            currentLabel = key.label,
            onConfirm = { newLabel ->
                viewModel.renameKey(key.id, newLabel)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
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
            // Pre-fill from the key's trailing OpenSSH comment when present
            // (`<type> <base64> [comment]`) — #231 asks for the comment as
            // the default label, still editable before saving.
            defaultLabel = commentOf(result.publicKeyOpenSsh),
            onConfirm = { label -> viewModel.saveImportedKey(label) },
            onDismiss = { viewModel.cancelImport() },
        )
    }
}

/**
 * One stored TOTP secret. Shows the live 6/8-digit code (refreshed each
 * second) so the user can verify it matches their other authenticator,
 * plus a delete affordance. (#178)
 */
@Composable
private fun TotpSecretRow(
    secret: TotpSecret,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val algorithm = remember(secret.algorithm) {
        runCatching { Totp.Algorithm.valueOf(secret.algorithm) }.getOrDefault(Totp.Algorithm.SHA1)
    }
    val code by produceState(initialValue = "•".repeat(secret.digits), secret.id) {
        while (true) {
            value = runCatching {
                Totp.generate(
                    secretBase32 = secret.secret,
                    algorithm = algorithm,
                    digits = secret.digits,
                    periodSeconds = secret.periodSeconds,
                )
            }.getOrDefault("error")
            delay(1000L)
        }
    }
    val subtitle = listOfNotNull(secret.issuer, secret.accountName).joinToString(" · ")
    // Tap the row to copy the code, the same as the age recipient row below.
    // A code you have to retype by hand from a screen you are already holding
    // is the one thing an authenticator should not make you do.
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.keys_totp_code_copied)
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clickable {
            clipboard.setText(AnnotatedString(code))
            android.widget.Toast.makeText(context, copiedMsg, android.widget.Toast.LENGTH_SHORT).show()
        },
        headlineContent = { Text(secret.label) },
        supportingContent = {
            Column {
                if (subtitle.isNotBlank()) Text(subtitle)
                Text(
                    code,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        leadingContent = { Icon(Icons.Filled.Pin, contentDescription = null) },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.keys_rename))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.keys_totp_delete_desc))
                }
            }
        },
    )
}

/** One age identity row: label + the public `age1…` recipient (tap to copy) + delete. */
@Composable
private fun AgeIdentityRow(
    identity: sh.haven.core.data.db.entities.AgeIdentityEntity,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.keys_age_recipient_copied)
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clickable {
            clipboard.setText(AnnotatedString(identity.recipient))
            android.widget.Toast.makeText(context, copiedMsg, android.widget.Toast.LENGTH_SHORT).show()
        },
        headlineContent = { Text(identity.label) },
        supportingContent = {
            Text(
                identity.recipient,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
            }
        },
    )
}

/**
 * A reusable SSH identity (#360): name + username, with a password and/or
 * SSH key. Tap to edit; the trailing button deletes. The password is never
 * shown — only whether one is set.
 */
@Composable
private fun SshIdentityRow(
    identity: sh.haven.core.data.db.entities.SshIdentity,
    keyLabel: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val parts = buildList {
        add(identity.username.ifBlank { stringResource(R.string.keys_identity_no_username) })
        if (identity.password != null) add(stringResource(R.string.keys_identity_has_password))
        keyLabel?.let { add(it) }
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(identity.name) },
        supportingContent = {
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Icon(Icons.Filled.Badge, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
            }
        },
    )
}

/**
 * Create/edit a reusable SSH identity (#360). On edit, the password field
 * starts empty and is only written if the user types one — leaving it blank
 * keeps the stored password (signalled by [onSave]'s null password arg); the
 * "clear password" toggle removes it. Key is chosen from [sshKeys] or none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshIdentityDialog(
    identity: sh.haven.core.data.db.entities.SshIdentity,
    sshKeys: List<SshKey>,
    onSave: (name: String, username: String, password: String?, keyId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEdit = identity.name.isNotEmpty()
    var name by rememberSaveable { mutableStateOf(identity.name) }
    var username by rememberSaveable { mutableStateOf(identity.username) }
    var password by rememberSaveable { mutableStateOf("") }
    // On edit, an existing password is retained unless the user clears it.
    var clearPassword by rememberSaveable { mutableStateOf(false) }
    var keyId by rememberSaveable { mutableStateOf(identity.keyId) }
    var keyMenuOpen by remember { mutableStateOf(false) }
    val hadPassword = identity.password != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isEdit) R.string.keys_identity_edit_title else R.string.keys_identity_add_title,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.keys_identity_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.keys_identity_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it; if (it.isNotEmpty()) clearPassword = false },
                    label = stringResource(
                        if (hadPassword && !clearPassword) R.string.keys_identity_password_keep_label
                        else R.string.keys_identity_password_label,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hadPassword && password.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearPassword, onCheckedChange = { clearPassword = it })
                        Text(
                            stringResource(R.string.keys_identity_clear_password),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Key picker — "None" plus each stored SSH key.
                val selectedKeyLabel = sshKeys.firstOrNull { it.id == keyId }?.label
                    ?: stringResource(R.string.keys_identity_key_none)
                Box {
                    OutlinedButton(onClick = { keyMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.keys_identity_key_label, selectedKeyLabel))
                    }
                    DropdownMenu(expanded = keyMenuOpen, onDismissRequest = { keyMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.keys_identity_key_none)) },
                            onClick = { keyId = null; keyMenuOpen = false },
                        )
                        sshKeys.forEach { k ->
                            DropdownMenuItem(
                                text = { Text(k.label) },
                                onClick = { keyId = k.id; keyMenuOpen = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pw = when {
                        password.isNotEmpty() -> password
                        clearPassword -> ""
                        else -> null // keep stored (null on a new identity = no password)
                    }
                    onSave(name.trim(), username.trim(), pw, keyId)
                },
                enabled = name.isNotBlank() && username.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun AddKeyChooser(
    stepCaConfigCount: Int,
    onGenerate: () -> Unit,
    onGenerateStepCa: () -> Unit,
    onImport: () -> Unit,
    onSecurityKey: () -> Unit,
    /** Apps that can hold a key for Haven; empty hides the option (#487). */
    keyProviders: List<OpenKeychainProvider>,
    onKeyProvider: (OpenKeychainProvider) -> Unit,
    onPaste: () -> Unit,
    onAddTotpPaste: () -> Unit,
    onScanTotpQr: () -> Unit,
    onGenerateAgeIdentity: () -> Unit,
    onAddSshIdentity: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_add_ssh_key)) },
        text = {
            // The chooser has 7+ tall ListItems; on a short portrait screen the
            // AlertDialog caps at the viewport height and a plain Column clips the
            // last option(s) with no way to reach them. Make it scrollable (#238-adjacent).
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ListItem(
                    modifier = Modifier.clickable { onGenerate() },
                    headlineContent = { Text(stringResource(R.string.keys_generate_new_key)) },
                    supportingContent = { Text(stringResource(R.string.keys_generate_key_types)) },
                    leadingContent = {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    },
                )
                ListItem(
                    modifier = if (stepCaConfigCount > 0) {
                        Modifier.clickable { onGenerateStepCa() }
                    } else Modifier,
                    headlineContent = { Text(stringResource(R.string.keys_generate_via_stepca)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (stepCaConfigCount > 0) R.string.keys_generate_via_stepca_hint
                                else R.string.keys_generate_via_stepca_no_ca,
                            ),
                            color = if (stepCaConfigCount == 0)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.VpnKey,
                            contentDescription = null,
                            tint = if (stepCaConfigCount > 0)
                                MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    modifier = Modifier.clickable { onSecurityKey() },
                    headlineContent = { Text(stringResource(R.string.keys_security_key)) },
                    supportingContent = {
                        Text(stringResource(R.string.keys_security_key_hint))
                    },
                    leadingContent = {
                        Icon(Icons.Filled.VpnKey, contentDescription = null)
                    },
                )
                // Only shown when something is installed to serve it —
                // an option that can only fail is worse than no option.
                keyProviders.forEach { provider ->
                    ListItem(
                        modifier = Modifier.clickable { onKeyProvider(provider) },
                        headlineContent = {
                            Text(stringResource(R.string.keys_from_provider, provider.label))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.keys_from_provider_hint))
                        },
                        leadingContent = {
                            Icon(Icons.Filled.Badge, contentDescription = null)
                        },
                    )
                }
                ListItem(
                    modifier = Modifier.clickable { onPaste() },
                    headlineContent = { Text(stringResource(R.string.keys_paste_from_clipboard)) },
                    supportingContent = { Text(stringResource(R.string.keys_paste_clipboard_hint)) },
                    leadingContent = {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { onScanTotpQr() },
                    headlineContent = { Text(stringResource(R.string.keys_totp_scan_qr)) },
                    supportingContent = { Text(stringResource(R.string.keys_totp_scan_qr_hint)) },
                    leadingContent = {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { onAddTotpPaste() },
                    headlineContent = { Text(stringResource(R.string.keys_totp_paste)) },
                    supportingContent = { Text(stringResource(R.string.keys_totp_paste_hint)) },
                    leadingContent = {
                        Icon(Icons.Filled.Pin, contentDescription = null)
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { onGenerateAgeIdentity() },
                    headlineContent = { Text(stringResource(R.string.keys_generate_age)) },
                    supportingContent = { Text(stringResource(R.string.keys_generate_age_hint)) },
                    leadingContent = {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { onAddSshIdentity() },
                    headlineContent = { Text(stringResource(R.string.keys_identity_add)) },
                    supportingContent = { Text(stringResource(R.string.keys_identity_add_hint)) },
                    leadingContent = {
                        Icon(Icons.Filled.Badge, contentDescription = null)
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

/**
 * Two-option chooser shown when the user picks "Security key (YubiKey)" — keeps
 * the add-key menu to one obvious entry instead of two near-identical FIDO
 * items. "Set up" creates a new credential on the key (CTAP2 MakeCredential);
 * "Import" enumerates resident creds already on it (#152).
 */
@Composable
private fun SecurityKeyChooser(
    onSetUp: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_security_key)) },
        text = {
            Column {
                ListItem(
                    modifier = Modifier.clickable { onSetUp() },
                    headlineContent = { Text(stringResource(R.string.keys_security_key_setup)) },
                    supportingContent = { Text(stringResource(R.string.keys_security_key_setup_hint)) },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
                ListItem(
                    modifier = Modifier.clickable { onImport() },
                    headlineContent = { Text(stringResource(R.string.keys_security_key_import)) },
                    supportingContent = { Text(stringResource(R.string.keys_security_key_import_hint)) },
                    leadingContent = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Collect label + verify-required + PIN, then register (create) a new SSH-SK
 * credential on a connected/tapped security key (CTAP2 MakeCredential). The PIN
 * is gathered here (not mid-exchange) so the whole CTAP exchange runs as one
 * continuous tap — USB: plug & touch; NFC: tap & hold. The touch step itself is
 * rendered by the shared [KeysFidoTouchPromptDialog]. To enrol several keys,
 * register, swap the key, and repeat.
 */
@Composable
private fun RegisterOnSecurityKeyDialog(
    onDismiss: () -> Unit,
    onRegister: (label: String, verifyRequired: Boolean, pin: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var verifyRequired by remember { mutableStateOf(true) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && pin != confirm
    val canRegister = pin.length >= 4 && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_register_on_security_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.keys_register_on_security_key_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.keys_register_verify_required))
                    Switch(checked = verifyRequired, onCheckedChange = { verifyRequired = it })
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.keys_register_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.keys_register_pin_confirm)) },
                    singleLine = true,
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        if (mismatch) R.string.keys_register_pin_mismatch
                        else R.string.keys_register_pin_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mismatch) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRegister(label, verifyRequired, pin) },
                enabled = canRegister,
            ) {
                Text(stringResource(R.string.keys_register_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (label: String, keyType: SshKeyGenerator.KeyType, hardwareBacked: Boolean) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(SshKeyGenerator.KeyType.ED25519) }
    var expanded by remember { mutableStateOf(false) }
    // design.md §6: "在设备安全芯片中生成" opt-in. On: the algorithm is
    // locked to P-256 (the only curve KeyMint's hardware backend supports —
    // KeyDecision #1) and the constraints are spelled out before generating.
    var hardwareBacked by remember { mutableStateOf(false) }
    // Resolved up front: stringResource is @Composable, the confirm onClick
    // below is not.
    val hardwareDefaultLabel = stringResource(R.string.keys_hardware_default_label)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_generate_ssh_key)) },
        text = {
            // Scrollable: the hardware branch adds a toggle + locked field +
            // four constraint lines on top of the label field — more than a
            // small phone's dialog can show unscrolled (same reasoning as the
            // bulk-delete dialog).
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_label)) },
                    placeholder = { Text(stringResource(R.string.keys_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Hardware key: chip toggle above the algorithm picker, so
                // locking the picker reads as a consequence of the toggle.
                // Whole row is the hit target (Role.Switch semantics); the
                // Switch itself gets onCheckedChange = null so the toggle
                // isn't announced twice.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .toggleable(
                            value = hardwareBacked,
                            role = Role.Switch,
                            onValueChange = { hardwareBacked = it },
                        ),
                ) {
                    Text(
                        text = stringResource(R.string.keys_hardware_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = hardwareBacked,
                        onCheckedChange = null,
                    )
                }

                if (hardwareBacked) {
                    // Key type is a property of the chip's KeyMint backend,
                    // not a choice — show it locked rather than hiding the
                    // field, so the user still sees what they're getting.
                    OutlinedTextField(
                        value = stringResource(R.string.keys_hardware_algorithm),
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text(stringResource(R.string.keys_key_type)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.keys_hardware_constraints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Blank-label fallback mirrors the software path's
                    // displayName fallback, via a resource so it localizes.
                    onGenerate(
                        label.ifBlank { hardwareDefaultLabel },
                        selectedType,
                        hardwareBacked,
                    )
                },
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
    title: String = stringResource(R.string.keys_encrypted_key),
    prompt: String = stringResource(R.string.keys_passphrase_prompt),
    confirmLabel: String = stringResource(R.string.keys_unlock),
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    prompt,
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
                Text(confirmLabel)
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
    defaultLabel: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(defaultLabel) }

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

@Composable
private fun RenameKeyDialog(
    currentLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.common_label)) },
                placeholder = { Text(stringResource(R.string.keys_label_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label) },
                enabled = label.isNotBlank(),
            ) {
                Text(stringResource(R.string.keys_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Extract the trailing comment from an OpenSSH public-key line
 * (`<type> <base64> [comment]`), or "" if there's none. Used to seed the
 * import-label field from the key's own comment (#231).
 */
private fun commentOf(publicKeyOpenSsh: String): String =
    sh.haven.core.ssh.SshPublicKeyComment.commentOf(publicKeyOpenSsh)

private fun copyPublicKey(context: Context, sshKey: SshKey) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // Imported keys and security-key credentials are stored without a comment,
    // so the copied line had nothing identifying it in an authorized_keys file.
    // Fall back to the key's own label (#449).
    val line = sh.haven.core.ssh.SshPublicKeyComment.withComment(
        sshKey.publicKeyOpenSsh,
        sshKey.label,
    )
    clipboard.setPrimaryClip(ClipData.newPlainText(
        context.getString(R.string.keys_ssh_public_key_clip_label), line,
    ))
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMd"), Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * A section heading that collapses its contents when tapped (#460). The
 * count stays in [label] while collapsed, so a collapsed section still says
 * how much is hidden behind it.
 */
@Composable
private fun SectionHeader(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.keys_section_collapse else R.string.keys_section_expand,
                label,
            ),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
        trailing()
    }
}

/** Sort picker for the SSH-keys section (#460). One entry per field. */
@Composable
private fun KeySortMenu(current: KeySort, onSelect: (KeySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.keys_sort),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(labelRes: Int, field: KeySort) {
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = { onSelect(current.select(field)); open = false },
                    // A check marks the active field; the label itself names
                    // the direction, so the two together say "sorted by name,
                    // Z first" without a second row per field.
                    leadingIcon = {
                        if (current == field || current == field.flipped()) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
            }
            item(R.string.keys_sort_manual, KeySort.MANUAL)
            item(
                if (current == KeySort.LABEL_DESC) R.string.keys_sort_label_desc
                else R.string.keys_sort_label_asc,
                KeySort.LABEL_ASC,
            )
            item(
                if (current == KeySort.OLDEST_FIRST) R.string.keys_sort_oldest
                else R.string.keys_sort_newest,
                KeySort.NEWEST_FIRST,
            )
        }
    }
}

/**
 * Replaces the SSH-keys section header while a multi-select is in progress
 * (#460). Delete is disabled at zero selected rather than hidden, so the
 * bar's shape does not change under the user's thumb.
 */
@Composable
private fun KeySelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.keys_selection_cancel),
            )
        }
        Text(
            text = pluralStringResource(R.plurals.keys_selected_count, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) {
            Text(stringResource(R.string.keys_select_all))
        }
        IconButton(onClick = onDelete, enabled = count > 0) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.keys_selection_delete),
                tint = if (count > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SshKeyAuditRow(
    sshKey: SshKey,
    entry: KeystoreEntry?,
    hasCertificate: Boolean,
    verifyRequired: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onCopyPublic: () -> Unit,
    onRename: () -> Unit,
    onExportPrivate: () -> Unit,
    onDelete: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onEnabledForAuthToggle: (Boolean) -> Unit,
    onStorePassphraseToggle: (Boolean) -> Unit,
    onSetVerifyRequired: (Boolean) -> Unit,
    onAttachCertificate: () -> Unit,
    onRemoveCertificate: () -> Unit,
    onExportCertificate: () -> Unit,
    onRegenerateViaStepCa: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    /** Null when not in multi-select mode, else this row's selected state (#460). */
    selected: Boolean? = null,
    onStartSelection: () -> Unit = {},
    onToggleSelected: () -> Unit = {},
) {
    val flags = entry?.flags ?: emptySet()
    // Hardware-resident key (private half in the device secure chip). A
    // single val up top: both the badge row and the ⋮ menu branch on it.
    val isHardwareKey = sshKey.hardwareAlias != null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected == true) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier,
            )
            // In selection mode a tap toggles the row instead of copying the
            // public key, and long-press does nothing — the ⋮ actions apply
            // to one key, so offering them mid-selection is ambiguous.
            .combinedClickable(
                onClick = if (selected != null) onToggleSelected else onCopyPublic,
                onLongClick = if (selected != null) null else onMenuOpen,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    // onCheckedChange = null: the row's own click owns the
                    // toggle, so the box is not a second, smaller hit target.
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                } else {
                    Icon(
                        // A provider-held key is worth telling apart at a
                        // glance: it is the one that needs another app present
                        // to connect (#487). A hardware-resident key is the
                        // one whose private half lives in the device's secure
                        // chip — the chip icon says "this one never leaves".
                        imageVector = when {
                            sshKey.hardwareAlias != null -> Icons.Filled.Memory
                            sshKey.keyType == OpenKeychainKeyData.KEY_TYPE -> Icons.Filled.Badge
                            sshKey.keyType.startsWith("sk-") -> Icons.Filled.Key
                            else -> Icons.Filled.VpnKey
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    // Explicit onSurface — this Box/Column row has no Surface/ListItem
                    // ancestor, so the default content colour is black and the name
                    // vanishes on a dark theme (the sibling Texts already set a colour).
                    Text(
                        text = sshKey.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = sshKey.keyType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // NOT wrapped in a SelectionContainer (#460): making the
                    // fingerprint text selectable put Android's own
                    // mark/copy/paste handling on a row whose long-press already
                    // means "open this key's menu", so one press produced both
                    // popups at once. In a list, long-press belongs to the item.
                    // Copying is on the menu ("Copy public key"); if copying the
                    // fingerprint specifically is ever wanted, it belongs there
                    // too rather than as text selection.
                    Text(
                        text = sshKey.fingerprintSha256,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatDate(sshKey.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Compact status badges, top-right of the card (#238) — replaces
                    // the old full-width chip row + full-width toggle rows (the
                    // toggles now live in the ⋮ menu). The label rides on each icon's
                    // content description so it's still announced / long-press hinted.
                    if (flags.isNotEmpty() || hasCertificate || isHardwareKey) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            flags.sortedBy { it.ordinal }.forEach { flag -> FlagBadge(flag) }
                            if (isHardwareKey) {
                                // Secure-chip badge: hardware keys carry no Keystore
                                // entry flags (nothing is stored app-side), so this
                                // badge is the only "how is this key kept" marker.
                                Icon(
                                    imageVector = Icons.Filled.Memory,
                                    contentDescription = stringResource(R.string.keys_chip_secure_chip),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            if (hasCertificate) {
                                Icon(
                                    imageVector = Icons.Filled.Badge,
                                    contentDescription = if (sshKey.certIssuedAt != null && sshKey.caConfigId != null) {
                                        stringResource(R.string.keys_chip_certificate_minted, formatDate(sshKey.certIssuedAt!!))
                                    } else {
                                        stringResource(R.string.keys_chip_certificate)
                                    },
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Three kinds of key have no private half stored here: a FIDO2/SK key
        // lives on the token, a provider key lives in another app (#487), and
        // a hardware-resident key lives in the device's secure chip — there
        // is nothing to export for any of them, but only the hardware kind
        // gets the disabled-with-reason item (export is *expected* to be
        // there because the key row looks like a normal generated key; for
        // sk/provider keys the omission itself communicates it).
        // Offering is different: an SK credential handle IS offerable, and
        // hardware keys join auto-auth via their Keystore identity, so both
        // stay in the offer-all pool.
        val isProviderKey = sshKey.keyType == OpenKeychainKeyData.KEY_TYPE
        val havenHoldsPrivateKey =
            !sshKey.keyType.startsWith("sk-") && !isProviderKey && !isHardwareKey

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onMenuDismiss,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.keys_copy_public_key)) },
                onClick = { onCopyPublic(); onMenuDismiss() },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            )
            // Rename applies to every key kind, FIDO2/SK included — the
            // whole point of #231 is disambiguating same-identity hardware
            // keys, which live under sk-* types.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.keys_rename)) },
                onClick = { onMenuDismiss(); onRename() },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            )
            // Multi-select starts here rather than from a long-press (#460
            // suggested long-press): long-press already opens this menu
            // (#238), and an explicit menu item also satisfies "must not be
            // reachable by accident" better than a gesture does.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.keys_select)) },
                onClick = { onMenuDismiss(); onStartSelection() },
                leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
            )
            // Reorder the key in the list (#238). Disabled at the edges; the menu
            // stays open so several moves can be chained.
            if (canMoveUp) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keys_move_up)) },
                    onClick = { onMoveUp() },
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                )
            }
            if (canMoveDown) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keys_move_down)) },
                    onClick = { onMoveDown() },
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                )
            }
            HorizontalDivider()
            // Per-key settings — moved off the card into the menu to keep the key
            // rows compact on tall phones; current state shows as a trailing ✓ and
            // as the top-right card badges (#238).
            // Per-op biometric is enforced by the chip's Keystore itself for
            // hardware keys (setUserAuthenticationValidityDurationSeconds(-1)),
            // and there is no app-side Keystore entry for the toggle to write
            // — showing it would offer a switch that does nothing.
            if (!isProviderKey && !isHardwareKey) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keys_require_biometric)) },
                    onClick = { onBiometricToggle(KeystoreFlag.BIOMETRIC_PROTECTED !in flags); onMenuDismiss() },
                    leadingIcon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
                    trailingIcon = {
                        if (KeystoreFlag.BIOMETRIC_PROTECTED in flags) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
            }
            if (!isProviderKey) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keys_offer_for_connections)) },
                    onClick = { onEnabledForAuthToggle(!sshKey.enabledForAuth); onMenuDismiss() },
                    leadingIcon = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
                    trailingIcon = {
                        if (sshKey.enabledForAuth) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                )
            }
            if (sshKey.isEncrypted) {
                val stored = sshKey.passphraseEncrypted != null
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keys_store_passphrase)) },
                    onClick = { onStorePassphraseToggle(!stored); onMenuDismiss() },
                    leadingIcon = { Icon(Icons.Filled.Password, contentDescription = null) },
                    trailingIcon = {
                        if (stored) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                )
            }
            if (sshKey.keyType.startsWith("sk-")) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keys_sk_require_pin)) },
                    onClick = { onSetVerifyRequired(!verifyRequired); onMenuDismiss() },
                    leadingIcon = { Icon(Icons.Filled.Pin, contentDescription = null) },
                    trailingIcon = {
                        if (verifyRequired) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                )
            }
            HorizontalDivider()
            if (isHardwareKey) {
                // Disabled-with-reason rather than omitted: a hardware key
                // row looks exactly like a normal generated key, so a missing
                // export would read as a bug. The reason is why: the private
                // half does not exist on this device to export.
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.keys_export_private_key))
                            Text(
                                stringResource(R.string.keys_hardware_export_disabled_reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {},
                    enabled = false,
                    leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                )
                // Certificates are public material signed against the public
                // key — they compose with hardware-resident keys (design.md
                // §5: the CA wrapper sits outside the chip's signing path),
                // so attach/export/remove stay available.
                CertificateMenuItems(
                    hasCertificate = hasCertificate,
                    onMenuDismiss = onMenuDismiss,
                    onExportCertificate = onExportCertificate,
                    onRemoveCertificate = onRemoveCertificate,
                    onAttachCertificate = onAttachCertificate,
                )
            } else if (havenHoldsPrivateKey) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keys_export_private_key)) },
                    onClick = { onMenuDismiss(); onExportPrivate() },
                    leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                )
                // Certificate attach / remove (#133 phase 1). FIDO SK keys
                // skip this — their signing path doesn't compose with
                // OpenSSH cert auth.
                CertificateMenuItems(
                    hasCertificate = hasCertificate,
                    onMenuDismiss = onMenuDismiss,
                    onExportCertificate = onExportCertificate,
                    onRemoveCertificate = onRemoveCertificate,
                    onAttachCertificate = onAttachCertificate,
                )
                // Regenerate (step-ca-minted keys only). Same flow as the
                // first Generate; updates the existing row in place. (#133 2b)
                if (sshKey.caConfigId != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.keys_regenerate_via_stepca)) },
                        onClick = { onMenuDismiss(); onRegenerateViaStepCa() },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
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

/**
 * The certificate menu rows shared by the hardware-resident and software key
 * rows: certificates are public material signed against the public key, so
 * they compose with both (the CA wrapper sits outside the signing path —
 * chip or bytes alike). Attach when absent; export + remove when present.
 */
@Composable
private fun CertificateMenuItems(
    hasCertificate: Boolean,
    onMenuDismiss: () -> Unit,
    onExportCertificate: () -> Unit,
    onRemoveCertificate: () -> Unit,
    onAttachCertificate: () -> Unit,
) {
    if (hasCertificate) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.keys_export_certificate)) },
            onClick = { onMenuDismiss(); onExportCertificate() },
            leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
        )
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

/**
 * Compact icon-only status badge for the top-right of a key card (#238). Same
 * icon/label mapping as [FlagChip], but no chip background or text — the label
 * rides on the content description (a11y + long-press hint) so a stack of keys
 * stays short on tall phones. [FlagChip] is kept for the password rows.
 */
@Composable
private fun FlagBadge(flag: KeystoreFlag) {
    val labelRes = when (flag) {
        KeystoreFlag.HARDWARE_BACKED -> R.string.keys_chip_hardware_backed
        KeystoreFlag.REQUIRES_PASSPHRASE -> R.string.keys_chip_passphrase
        KeystoreFlag.REQUIRES_USER_PRESENCE -> R.string.keys_chip_user_presence
        KeystoreFlag.REQUIRES_USER_VERIFICATION -> R.string.keys_chip_user_verification
        KeystoreFlag.BIOMETRIC_PROTECTED -> R.string.keys_chip_biometric
    }
    val icon = when (flag) {
        KeystoreFlag.HARDWARE_BACKED -> Icons.Filled.Shield
        KeystoreFlag.REQUIRES_PASSPHRASE -> Icons.Filled.Key
        KeystoreFlag.REQUIRES_USER_PRESENCE -> Icons.Filled.TouchApp
        KeystoreFlag.REQUIRES_USER_VERIFICATION -> Icons.Filled.Fingerprint
        KeystoreFlag.BIOMETRIC_PROTECTED -> Icons.Filled.Fingerprint
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(labelRes),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
    )
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
