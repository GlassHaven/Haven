package sh.haven.app.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.app.R
import sh.haven.app.workspace.WorkspaceLaunchState
import sh.haven.app.workspace.WorkspaceViewModel
import sh.haven.core.data.db.entities.WorkspaceProfile

/**
 * Workspace list + save-current entry, designed to slot into the top
 * of the Connections screen as a peer to the existing groups list.
 *
 * The composable is self-contained (uses [hiltViewModel]) so the
 * caller only has to drop it in once. Save / launch / delete dialogs
 * fire from inside this section; the launch progress banner is
 * rendered inline below the header.
 */
@Composable
fun WorkspaceSection(
    modifier: Modifier = Modifier,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val workspaces by viewModel.workspaces.collectAsState()
    val launchState by viewModel.launchState.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WorkspaceProfile?>(null) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.workspace_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSaveDialog = true }) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(stringResource(R.string.workspace_save_current))
            }
        }

        WorkspaceLaunchBanner(
            state = launchState,
            onCancel = viewModel::cancel,
            onDismiss = viewModel::acknowledge,
        )

        if (workspaces.isEmpty() && launchState is WorkspaceLaunchState.Idle) {
            Text(
                text = stringResource(R.string.workspace_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (ws in workspaces) {
                    WorkspaceCard(
                        profile = ws,
                        viewModel = viewModel,
                        onLaunch = { viewModel.launch(ws.id) },
                        onLongPressDelete = { pendingDelete = ws },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showSaveDialog) {
        SaveWorkspaceDialog(
            viewModel = viewModel,
            onDismiss = { showSaveDialog = false },
        )
    }

    pendingDelete?.let { ws ->
        DeleteWorkspaceDialog(
            workspace = ws,
            onConfirm = {
                viewModel.delete(ws.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
