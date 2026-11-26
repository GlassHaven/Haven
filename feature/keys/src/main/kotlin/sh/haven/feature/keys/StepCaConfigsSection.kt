package sh.haven.feature.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.stepca.StepCaApiClient

/**
 * Renders the "Certificate authorities" section inside the Keys tab.
 * Designed to slot into the existing [androidx.compose.foundation.lazy.LazyColumn]
 * via a sequence of `item { … }` calls, so it composes alongside the
 * SSH-keys and stored-passwords sections without restructuring the
 * outer scroll.
 *
 * Migrated from the standalone Settings sub-screen (#133 phase 2b
 * follow-up) — CAs live with credentials, not preferences.
 */
@Composable
internal fun StepCaConfigsSectionContent(
    viewModel: StepCaConfigsViewModel = hiltViewModel(),
    /** Collapsible like the other Keys-tab sections (#460). */
    expanded: Boolean = true,
    onToggleExpanded: () -> Unit = {},
) {
    val configs by viewModel.configs.collectAsState()
    val testResults by viewModel.testResults.collectAsState()
    val testInFlight by viewModel.testInFlight.collectAsState()

    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StepCaConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<StepCaConfig?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        val sectionLabel = stringResource(
            R.string.keys_section_certificate_authorities,
            configs.size,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.keys_section_collapse else R.string.keys_section_expand,
                    sectionLabel,
                ),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp),
            )
            Text(
                text = sectionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
            IconButton(onClick = { addOpen = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.stepca_add),
                )
            }
        }
        // Collapsing hides the rows, not the "add" action — a collapsed
        // section must still be a way in (the #133 lesson).
        (if (expanded) configs else emptyList()).forEach { config ->
            StepCaConfigRow(
                config = config,
                testResult = testResults[config.id],
                testing = config.id in testInFlight,
                onTest = { viewModel.test(config) },
                onEdit = { editing = config },
                onDelete = { pendingDelete = config },
            )
        }
    }

    if (addOpen) {
        StepCaConfigDialog(
            initial = null,
            onDismiss = { addOpen = false },
            onConfirm = {
                viewModel.save(it)
                addOpen = false
            },
            onDiscoverHostCa = { caUrl, rootCert ->
                viewModel.discoverSshHostCa(caUrl, rootCert)
            },
            onBootstrap = { caUrl, fingerprint ->
                viewModel.bootstrap(caUrl, fingerprint)
            },
        )
    }
    editing?.let { existing ->
        StepCaConfigDialog(
            initial = existing,
            onDismiss = { editing = null },
            onConfirm = {
                viewModel.save(it)
                editing = null
            },
            onDiscoverHostCa = { caUrl, rootCert ->
                viewModel.discoverSshHostCa(caUrl, rootCert)
            },
            onBootstrap = { caUrl, fingerprint ->
                viewModel.bootstrap(caUrl, fingerprint)
            },
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.stepca_delete_confirm_title, target.name)) },
            text = { Text(stringResource(R.string.stepca_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.stepca_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.stepca_cancel))
                }
            },
        )
    }
}

@Composable
private fun StepCaConfigRow(
    config: StepCaConfig,
    testResult: StepCaApiClient.TestResult?,
    testing: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val supporting: String? = when {
        testing -> null
        testResult is StepCaApiClient.TestResult.Ok ->
            stringResource(R.string.stepca_test_ok)
        testResult is StepCaApiClient.TestResult.BadRootCert ->
            stringResource(R.string.stepca_test_bad_root_cert, testResult.reason)
        testResult is StepCaApiClient.TestResult.HttpError ->
            stringResource(R.string.stepca_test_http_error, testResult.code, testResult.message)
        testResult is StepCaApiClient.TestResult.NetworkError ->
            stringResource(R.string.stepca_test_network_error, testResult.message)
        else -> config.caUrl
    }
    val statusIcon: @Composable () -> Unit = {
        when {
            testing -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            testResult is StepCaApiClient.TestResult.Ok -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            testResult is StepCaApiClient.TestResult.BadRootCert ||
                testResult is StepCaApiClient.TestResult.HttpError ||
                testResult is StepCaApiClient.TestResult.NetworkError -> Icon(
                Icons.Filled.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            else -> Icon(Icons.Filled.VpnKey, contentDescription = null)
        }
    }

    ListItem(
        leadingContent = statusIcon,
        headlineContent = { Text(config.name) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.stepca_test_connection)) },
                        leadingIcon = { Icon(Icons.Filled.NetworkCheck, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onTest()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.stepca_edit_title)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.stepca_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepCaConfigDialog(
    initial: StepCaConfig?,
    onDismiss: () -> Unit,
    onConfirm: (StepCaConfig) -> Unit,
    onDiscoverHostCa: suspend (caUrl: String, rootCertPem: String) -> StepCaApiClient.SshConfigResult,
    onBootstrap: suspend (caUrl: String, fingerprintHex: String) -> StepCaApiClient.BootstrapResult,
) {
    // Edit always opens straight in manual mode (rows are already populated).
    // Add starts in bootstrap mode; the user can switch to manual if their CA
    // doesn't expose `/provisioners` or runs only non-OIDC provisioners.
    var manualMode by remember { mutableStateOf(initial != null) }

    // Bootstrap pre-stage state.
    var bootstrapCaUrl by remember { mutableStateOf("") }
    var bootstrapFingerprint by remember { mutableStateOf("") }
    var bootstrapping by remember { mutableStateOf(false) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    var bootstrapChoices by remember {
        mutableStateOf<List<StepCaApiClient.BootstrapResult.ResolvedProvisioner>>(emptyList())
    }
    var bootstrapRoots by remember { mutableStateOf("") }

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var caUrl by remember { mutableStateOf(initial?.caUrl.orEmpty()) }
    var oidcIssuer by remember { mutableStateOf(initial?.oidcIssuer.orEmpty()) }
    var oidcAuthUrl by remember { mutableStateOf(initial?.oidcAuthUrl.orEmpty()) }
    var oidcTokenUrl by remember { mutableStateOf(initial?.oidcTokenUrl.orEmpty()) }
    var oidcClientId by remember { mutableStateOf(initial?.oidcClientId.orEmpty()) }
    var oidcClientSecret by remember { mutableStateOf(initial?.oidcClientSecret.orEmpty()) }
    var provisioner by remember { mutableStateOf(initial?.provisioner.orEmpty()) }
    var principals by remember { mutableStateOf(initial?.defaultPrincipals.orEmpty()) }
    var rootCert by remember { mutableStateOf(initial?.rootCertPem.orEmpty()) }
    var sshHostCa by remember { mutableStateOf(initial?.sshHostCaPublicKey.orEmpty()) }
    var discovering by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    fun adoptProvisioner(chosen: StepCaApiClient.BootstrapResult.ResolvedProvisioner) {
        provisioner = chosen.provisioner.name
        oidcClientId = chosen.provisioner.clientId
        oidcClientSecret = chosen.provisioner.clientSecret.orEmpty()
        oidcIssuer = chosen.endpoints.issuer
        oidcAuthUrl = chosen.endpoints.authorizationEndpoint
        oidcTokenUrl = chosen.endpoints.tokenEndpoint
        rootCert = bootstrapRoots
        caUrl = bootstrapCaUrl.trim()
        if (name.isBlank()) {
            val host = runCatching { java.net.URI(bootstrapCaUrl.trim()).host }.getOrNull().orEmpty()
            name = if (host.isNotBlank()) "${chosen.provisioner.name}@$host" else chosen.provisioner.name
        }
        bootstrapChoices = emptyList()
        manualMode = true
    }

    val context = LocalContext.current
    val importFailedTemplate = stringResource(R.string.stepca_import_failed)

    val rootCertPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importError = null
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Couldn't open file")
            rootCert = StepCaFileImport.readRootCertPem(bytes)
        } catch (t: Throwable) {
            importError = importFailedTemplate.format(
                t.message ?: t::class.simpleName ?: "unknown error",
            )
        }
    }
    val sshHostCaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importError = null
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Couldn't open file")
            sshHostCa = StepCaFileImport.readSshHostCaPubkey(bytes)
        } catch (t: Throwable) {
            importError = importFailedTemplate.format(
                t.message ?: t::class.simpleName ?: "unknown error",
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.stepca_add_title else R.string.stepca_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!manualMode) {
                    Text(
                        stringResource(R.string.stepca_bootstrap_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = bootstrapCaUrl,
                        onValueChange = { bootstrapCaUrl = it },
                        label = { Text(stringResource(R.string.stepca_field_ca_url)) },
                        placeholder = { Text(stringResource(R.string.stepca_field_ca_url_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            capitalization = KeyboardCapitalization.None,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = bootstrapFingerprint,
                        onValueChange = { bootstrapFingerprint = it },
                        label = { Text(stringResource(R.string.stepca_field_fingerprint)) },
                        placeholder = { Text(stringResource(R.string.stepca_field_fingerprint_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (bootstrapping) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                stringResource(R.string.stepca_bootstrapping),
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (bootstrapChoices.isNotEmpty()) {
                        Text(
                            stringResource(R.string.stepca_bootstrap_pick_provisioner),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        bootstrapChoices.forEach { choice ->
                            TextButton(
                                onClick = { adoptProvisioner(choice) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${choice.provisioner.name} — ${choice.endpoints.issuer}",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    bootstrapError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        enabled = !bootstrapping &&
                            bootstrapCaUrl.trim().startsWith("https://") &&
                            bootstrapFingerprint.trim().isNotEmpty(),
                        onClick = {
                            bootstrapError = null
                            bootstrapChoices = emptyList()
                            bootstrapping = true
                            coroutineScope.launch {
                                try {
                                    val result = onBootstrap(
                                        bootstrapCaUrl.trim(),
                                        bootstrapFingerprint.trim(),
                                    )
                                    when (result) {
                                        is StepCaApiClient.BootstrapResult.Failure ->
                                            bootstrapError = result.message
                                        is StepCaApiClient.BootstrapResult.Success -> {
                                            bootstrapRoots = result.rootsPem
                                            if (result.provisioners.size == 1) {
                                                adoptProvisioner(result.provisioners.first())
                                            } else {
                                                bootstrapChoices = result.provisioners
                                            }
                                        }
                                    }
                                } finally {
                                    bootstrapping = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.stepca_bootstrap_action))
                    }
                    TextButton(
                        onClick = { manualMode = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.stepca_manual_entry))
                    }
                    return@Column
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.stepca_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = caUrl,
                    onValueChange = { caUrl = it },
                    label = { Text(stringResource(R.string.stepca_field_ca_url)) },
                    placeholder = { Text(stringResource(R.string.stepca_field_ca_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = oidcIssuer,
                    onValueChange = { oidcIssuer = it },
                    label = { Text(stringResource(R.string.stepca_field_oidc_issuer)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = oidcAuthUrl,
                    onValueChange = { oidcAuthUrl = it },
                    label = { Text(stringResource(R.string.stepca_field_oidc_auth_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = oidcTokenUrl,
                    onValueChange = { oidcTokenUrl = it },
                    label = { Text(stringResource(R.string.stepca_field_oidc_token_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = oidcClientId,
                    onValueChange = { oidcClientId = it },
                    label = { Text(stringResource(R.string.stepca_field_oidc_client_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                sh.haven.core.ui.PasswordField(
                    value = oidcClientSecret,
                    onValueChange = { oidcClientSecret = it },
                    label = stringResource(R.string.stepca_field_oidc_client_secret),
                    placeholder = stringResource(R.string.stepca_field_oidc_client_secret_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = provisioner,
                    onValueChange = { provisioner = it },
                    label = { Text(stringResource(R.string.stepca_field_provisioner)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = principals,
                    onValueChange = { principals = it },
                    label = { Text(stringResource(R.string.stepca_field_principals)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rootCert,
                    onValueChange = { rootCert = it },
                    label = { Text(stringResource(R.string.stepca_field_root_cert)) },
                    placeholder = { Text(stringResource(R.string.stepca_field_root_cert_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                )
                TextButton(onClick = { rootCertPicker.launch("*/*") }) {
                    Text(stringResource(R.string.stepca_import_from_file))
                }
                OutlinedTextField(
                    value = sshHostCa,
                    onValueChange = { sshHostCa = it },
                    label = { Text(stringResource(R.string.stepca_field_ssh_host_ca)) },
                    placeholder = { Text(stringResource(R.string.stepca_field_ssh_host_ca_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { sshHostCaPicker.launch("*/*") }) {
                        Text(stringResource(R.string.stepca_import_from_file))
                    }
                }
                TextButton(
                    enabled = !discovering &&
                        caUrl.trim().startsWith("https://") &&
                        rootCert.contains("BEGIN CERTIFICATE"),
                    onClick = {
                        discoverError = null
                        discovering = true
                        coroutineScope.launch {
                            try {
                                when (val r = onDiscoverHostCa(caUrl.trim(), rootCert.trim())) {
                                    is StepCaApiClient.SshConfigResult.Success -> {
                                        r.hostKey?.let { sshHostCa = it.trim() }
                                            ?: run { discoverError = "step-ca returned no hostKey" }
                                    }
                                    is StepCaApiClient.SshConfigResult.Failure ->
                                        discoverError = r.message
                                }
                            } finally {
                                discovering = false
                            }
                        }
                    },
                ) {
                    Text(
                        if (discovering) stringResource(R.string.stepca_discovering)
                        else stringResource(R.string.stepca_discover_host_ca),
                    )
                }
                discoverError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                importError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (manualMode) TextButton(onClick = {
                val problem = validate(
                    context = context,
                    name = name,
                    caUrl = caUrl,
                    oidcIssuer = oidcIssuer,
                    oidcAuthUrl = oidcAuthUrl,
                    oidcTokenUrl = oidcTokenUrl,
                    oidcClientId = oidcClientId,
                    provisioner = provisioner,
                    rootCert = rootCert,
                    sshHostCa = sshHostCa,
                )
                if (problem != null) {
                    error = problem
                    return@TextButton
                }
                val out = (initial ?: StepCaConfig(
                    name = name.trim(),
                    caUrl = caUrl.trim(),
                    oidcIssuer = oidcIssuer.trim(),
                    oidcAuthUrl = oidcAuthUrl.trim(),
                    oidcTokenUrl = oidcTokenUrl.trim(),
                    oidcClientId = oidcClientId.trim(),
                    provisioner = provisioner.trim(),
                    defaultPrincipals = principals.trim(),
                    rootCertPem = rootCert.trim(),
                )).copy(
                    name = name.trim(),
                    caUrl = caUrl.trim(),
                    oidcIssuer = oidcIssuer.trim(),
                    oidcAuthUrl = oidcAuthUrl.trim(),
                    oidcTokenUrl = oidcTokenUrl.trim(),
                    oidcClientId = oidcClientId.trim(),
                    oidcClientSecret = oidcClientSecret.trim().ifBlank { null },
                    provisioner = provisioner.trim(),
                    defaultPrincipals = principals.trim(),
                    rootCertPem = rootCert.trim(),
                    sshHostCaPublicKey = sshHostCa.trim().ifBlank { null },
                )
                onConfirm(out)
            }) {
                Text(stringResource(R.string.stepca_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.stepca_cancel))
            }
        },
    )
}

private fun validate(
    context: android.content.Context,
    name: String,
    caUrl: String,
    oidcIssuer: String,
    oidcAuthUrl: String,
    oidcTokenUrl: String,
    oidcClientId: String,
    provisioner: String,
    rootCert: String,
    sshHostCa: String,
): String? {
    fun req(field: String, value: String) =
        if (value.isBlank()) context.getString(R.string.stepca_validation_required, field) else null

    req(context.getString(R.string.stepca_field_name), name)?.let { return it }

    // Host-CA-only trust config (#380): if none of the user-cert *signing*
    // fields are filled, this config exists solely to trust host certificates
    // signed by the given CA — validate just that key and skip the OIDC/
    // provisioner requirements. Trusting a host CA needs no OIDC flow.
    val signingFields = listOf(caUrl, oidcIssuer, oidcAuthUrl, oidcTokenUrl, oidcClientId, provisioner, rootCert)
    if (signingFields.all { it.isBlank() }) {
        if (sshHostCa.isBlank()) {
            return context.getString(R.string.stepca_validation_host_ca_or_signing)
        }
        if (!StepCaFileImport.isValidHostCaPublicKey(sshHostCa)) {
            return context.getString(R.string.stepca_validation_host_ca)
        }
        return null
    }

    // Full signing config — every OIDC/provisioner field is required.
    req(context.getString(R.string.stepca_field_ca_url), caUrl)?.let { return it }
    req(context.getString(R.string.stepca_field_oidc_issuer), oidcIssuer)?.let { return it }
    req(context.getString(R.string.stepca_field_oidc_auth_url), oidcAuthUrl)?.let { return it }
    req(context.getString(R.string.stepca_field_oidc_token_url), oidcTokenUrl)?.let { return it }
    req(context.getString(R.string.stepca_field_oidc_client_id), oidcClientId)?.let { return it }
    req(context.getString(R.string.stepca_field_provisioner), provisioner)?.let { return it }
    req(context.getString(R.string.stepca_field_root_cert), rootCert)?.let { return it }

    fun https(field: String, value: String) =
        if (!value.trim().startsWith("https://")) {
            context.getString(R.string.stepca_validation_url, field)
        } else null

    https(context.getString(R.string.stepca_field_ca_url), caUrl)?.let { return it }
    https(context.getString(R.string.stepca_field_oidc_auth_url), oidcAuthUrl)?.let { return it }
    https(context.getString(R.string.stepca_field_oidc_token_url), oidcTokenUrl)?.let { return it }

    if (!rootCert.trim().startsWith("-----BEGIN CERTIFICATE-----")) {
        return context.getString(R.string.stepca_validation_pem)
    }
    // Host CA is optional on a signing config, but if present it must be valid.
    if (sshHostCa.isNotBlank() && !StepCaFileImport.isValidHostCaPublicKey(sshHostCa)) {
        return context.getString(R.string.stepca_validation_host_ca)
    }
    return null
}
