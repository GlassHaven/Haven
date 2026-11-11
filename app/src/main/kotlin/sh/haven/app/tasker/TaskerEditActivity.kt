package sh.haven.app.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import sh.haven.app.R
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ui.theme.HavenTheme
import javax.inject.Inject

/**
 * Locale/Tasker/MacroDroid config screen for the "Run command on a Haven
 * server" action (#367). Launched by the host with
 * [TaskerPlugin.ACTION_EDIT_SETTING]; returns the config Bundle + blurb.
 */
@AndroidEntryPoint
class TaskerEditActivity : ComponentActivity() {

    @Inject lateinit var connectionRepository: ConnectionRepository
    @Inject lateinit var preferencesRepository: sh.haven.core.data.preferences.UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed from an existing config when the host is editing an action.
        val existing = intent.getBundleExtra(TaskerPlugin.EXTRA_BUNDLE)
        val seedProfileId = existing?.getString(TaskerPlugin.BUNDLE_PROFILE_ID)
        val seedCommand = existing?.getString(TaskerPlugin.BUNDLE_COMMAND) ?: ""
        val seedOverlay = existing?.getBoolean(TaskerPlugin.BUNDLE_OVERLAY, false) ?: false
        val seedBlock = existing?.getBoolean(TaskerPlugin.BUNDLE_BLOCK, false) ?: false

        setContent {
            HavenTheme {
                val saveScope = rememberCoroutineScope()
                val profiles by produceState<List<ConnectionProfile>?>(initialValue = null) {
                    value = connectionRepository.getAll().filter { it.isSsh }
                }
                EditScreen(
                    profiles = profiles,
                    seedProfileId = seedProfileId,
                    seedCommand = seedCommand,
                    seedOverlay = seedOverlay,
                    seedBlock = seedBlock,
                    hostVariables = TaskerPlugin.hostVariables(intent),
                    onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                    onSave = { profile, command, overlay, block ->
                        saveScope.launch {
                            val secret = preferencesRepository.getOrCreateTaskerPluginSecret()
                            val bundle = TaskerPlugin.buildBundle(
                                profileId = profile.id,
                                profileLabel = profile.label,
                                command = command,
                                overlay = overlay,
                                block = block,
                                secret = secret,
                            )
                            val blurb = getString(R.string.tasker_blurb, profile.label, command).take(60)
                            setResult(
                                Activity.RESULT_OK,
                                Intent().apply {
                                    putExtra(TaskerPlugin.EXTRA_BUNDLE, bundle)
                                    putExtra(TaskerPlugin.EXTRA_BLURB, blurb)
                                    // Declare the variables this action sets (%hstdout /
                                    // %hstderr / %hexit) so the host lists them (#367).
                                    putExtra(
                                        TaskerPlugin.BUNDLE_KEY_RELEVANT_VARIABLES,
                                        TaskerPlugin.RELEVANT_VARIABLES,
                                    )
                                },
                            )
                            finish()
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScreen(
    profiles: List<ConnectionProfile>?,
    seedProfileId: String?,
    seedCommand: String,
    seedOverlay: Boolean,
    seedBlock: Boolean,
    hostVariables: List<String>,
    onCancel: () -> Unit,
    onSave: (ConnectionProfile, String, Boolean, Boolean) -> Unit,
) {
    var selected by remember(profiles) {
        mutableStateOf(profiles?.firstOrNull { it.id == seedProfileId } ?: profiles?.firstOrNull())
    }
    var command by remember { mutableStateOf(TextFieldValue(seedCommand)) }
    var overlay by remember { mutableStateOf(seedOverlay) }
    var block by remember { mutableStateOf(seedBlock) }
    var expanded by remember { mutableStateOf(false) }

    Scaffold { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.tasker_edit_title))

            when {
                profiles == null -> Text(stringResource(R.string.tasker_loading))
                profiles.isEmpty() -> Text(stringResource(R.string.tasker_no_ssh_profiles))
                else -> {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selected?.label ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.tasker_field_server)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            profiles.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.label) },
                                    onClick = { selected = p; expanded = false },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.tasker_field_command)) },
                        supportingText = { Text(stringResource(R.string.tasker_field_command_help)) },
                        // "…" variable picker — only when the host (Tasker,
                        // and MacroDroid if it advertises them) hands us a
                        // variable list; inserts the chosen token at the cursor.
                        trailingIcon = if (hostVariables.isEmpty()) null else {
                            {
                                VariablePicker(hostVariables) { token ->
                                    command = command.insertAtCursor(token)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ToggleRow(
                        label = stringResource(R.string.tasker_toggle_overlay),
                        checked = overlay,
                        onChange = { overlay = it },
                    )
                    ToggleRow(
                        label = stringResource(R.string.tasker_toggle_block),
                        checked = block,
                        onChange = { block = it },
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = selected != null && command.text.isNotBlank(),
                    onClick = { selected?.let { onSave(it, command.text.trim(), overlay, block) } },
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

/**
 * The "…" magic-text button MacroDroid/Tasker users expect beside a command
 * field (#367). Opens a menu of the host-advertised variables; picking one
 * calls [onPick] with the token to drop in at the cursor.
 */
@Composable
private fun VariablePicker(variables: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.tasker_insert_variable)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Text("…")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            variables.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v) },
                    onClick = { onPick(v); open = false },
                )
            }
        }
    }
}

/** Replace the current selection (or insert at the caret) with [insert]. */
private fun TextFieldValue.insertAtCursor(insert: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val newText = text.replaceRange(start, end, insert)
    return TextFieldValue(text = newText, selection = TextRange(start + insert.length))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
