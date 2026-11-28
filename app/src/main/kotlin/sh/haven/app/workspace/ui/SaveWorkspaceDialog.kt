package sh.haven.app.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.haven.app.R
import sh.haven.app.workspace.CapturedDraft
import sh.haven.app.workspace.WorkspaceViewModel
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile

/**
 * Capture the user's currently-open singletons (sessions + Wayland)
 * into a draft list, let them tick which to keep, name the workspace,
 * and persist it.
 *
 * Items not present in app singletons (VNC tabs, SFTP path, rclone
 * tabs) aren't auto-captured here — the dialog covers terminal
 * sessions, SMB shares, RDP, Wayland. Manual additions land in a
 * follow-up: see VISION.md §1 "Workspace profiles".
 */
@Composable
fun SaveWorkspaceDialog(
    viewModel: WorkspaceViewModel,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val drafts = remember { mutableStateOf<List<CapturedDraft>>(emptyList()) }
    val included = remember { mutableStateMapOf<String, Boolean>() }

    // Capture once on first composition. The capture runs on
    // Dispatchers.IO inside the VM and returns once the SessionManagerRegistry
    // snapshot is taken — no live subscription, since the dialog wants
    // a static snapshot of "right now".
    LaunchedEffect(Unit) {
        val pendingId = WorkspaceProfile(name = "").id
        val captured = viewModel.captureFromSingletons(pendingId)
        drafts.value = captured
        captured.forEach { included[it.item.id] = true }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_save_dialog_title)) },
        text = {
            Column {
                // What a workspace is for, said where one is made rather
                // than as a standing paragraph on the Connections screen.
                Text(
                    text = stringResource(R.string.workspace_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workspace_save_dialog_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (drafts.value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.workspace_save_dialog_no_items),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.workspace_save_dialog_items_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (draft in drafts.value) {
                            DraftItemRow(
                                draft = draft,
                                checked = included[draft.item.id] ?: true,
                                onCheckedChange = { included[draft.item.id] = it },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() &&
                    drafts.value.any { included[it.item.id] == true },
                onClick = {
                    val selected = drafts.value
                        .filter { included[it.item.id] == true }
                        .map { it.item }
                    viewModel.save(name = name, items = selected)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.workspace_save_action))
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
private fun DraftItemRow(
    draft: CapturedDraft,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        val item = draft.item
        // "<host> tmux haven" for a terminal on a multiplexer; "<host> haven"
        // or just "<host>" for a plain shell; "<kind> <host>" for the other
        // kinds; the kind label alone for Wayland. Manager labels (tmux/…) and
        // hostnames are data / proper nouns, so no new translatable strings.
        val detail = when (item.kind) {
            WorkspaceItem.Kind.TERMINAL ->
                listOfNotNull(draft.host, draft.sessionType, item.sessionName)
                    .joinToString(" ")
                    .ifBlank { stringResource(item.kind.labelRes()) }
            WorkspaceItem.Kind.WAYLAND -> stringResource(item.kind.labelRes())
            else -> listOfNotNull(stringResource(item.kind.labelRes()), draft.host)
                .joinToString(" ")
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun DeleteWorkspaceDialog(
    workspace: WorkspaceProfile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_delete_confirm_title)) },
        text = {
            Text(
                stringResource(R.string.workspace_delete_confirm_message, workspace.name),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.workspace_long_press_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

