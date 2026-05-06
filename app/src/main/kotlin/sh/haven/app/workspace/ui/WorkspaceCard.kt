@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package sh.haven.app.workspace.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.haven.app.R
import sh.haven.app.workspace.WorkspaceViewModel
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile

/**
 * One row in the workspace list. Tap opens the workspace via the
 * launcher; long-press surfaces the delete confirmation. Items are
 * collected from the repo flow so a workspace shows live counts even
 * if its membership changes underneath (e.g. a referenced profile
 * was deleted and the FK got `SET NULL`-ed).
 */
@Composable
fun WorkspaceCard(
    profile: WorkspaceProfile,
    viewModel: WorkspaceViewModel,
    onLaunch: () -> Unit,
    onLongPressDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.observeItems(profile.id).collectAsState(initial = emptyList())

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = onLongPressDelete,
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.padding(top = 4.dp))
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.workspace_card_no_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KindChips(items = items)
            }
        }
    }
}

/**
 * Inline summary of a workspace's items, grouped by kind. Each
 * non-zero kind renders one chip with a count, e.g. "Terminal × 2".
 * Avoids per-item chips so a workspace with 8 SSH tabs doesn't blow
 * out the row height.
 */
@Composable
private fun KindChips(items: List<WorkspaceItem>) {
    val counts = items.groupingBy { it.kind }.eachCount()
    val ordered = listOf(
        WorkspaceItem.Kind.TERMINAL,
        WorkspaceItem.Kind.WAYLAND,
        WorkspaceItem.Kind.FILE_BROWSER,
        WorkspaceItem.Kind.DESKTOP,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (kind in ordered) {
            val n = counts[kind] ?: 0
            if (n == 0) continue
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(
                            R.string.workspace_card_kind_count,
                            stringResource(kind.labelRes()),
                            n,
                        ),
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    }
}

internal fun WorkspaceItem.Kind.labelRes(): Int = when (this) {
    WorkspaceItem.Kind.TERMINAL -> R.string.workspace_card_kind_terminal
    WorkspaceItem.Kind.FILE_BROWSER -> R.string.workspace_card_kind_file_browser
    WorkspaceItem.Kind.DESKTOP -> R.string.workspace_card_kind_desktop
    WorkspaceItem.Kind.WAYLAND -> R.string.workspace_card_kind_wayland
}
