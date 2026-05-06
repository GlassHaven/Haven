package sh.haven.app.workspace.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.haven.app.R
import sh.haven.app.workspace.ItemProgress
import sh.haven.app.workspace.WorkspaceLaunchState

/**
 * Inline progress / outcome banner for an in-flight or
 * recently-completed workspace launch. Renders nothing in
 * [WorkspaceLaunchState.Idle].
 */
@Composable
fun WorkspaceLaunchBanner(
    state: WorkspaceLaunchState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayed = renderableState(state) ?: return

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayed.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(start = 8.dp))
            TextButton(onClick = if (displayed.isInFlight) onCancel else onDismiss) {
                Text(
                    if (displayed.isInFlight) stringResource(R.string.workspace_launch_cancel)
                    else stringResource(R.string.workspace_launch_dismiss),
                )
            }
        }
        if (displayed.progressFraction != null) {
            LinearProgressIndicator(
                progress = { displayed.progressFraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class BannerView(
    val message: String,
    val isInFlight: Boolean,
    val progressFraction: Float?,
)

@Composable
private fun renderableState(state: WorkspaceLaunchState): BannerView? = when (state) {
    is WorkspaceLaunchState.Idle -> null
    is WorkspaceLaunchState.Launching -> {
        val done = state.items.count { it.status == ItemProgress.Status.Succeeded }
        val total = state.items.size.coerceAtLeast(1)
        BannerView(
            message = stringResource(
                R.string.workspace_launch_progress,
                state.workspaceName,
                done,
                state.items.size,
            ),
            isInFlight = true,
            progressFraction = (done.toFloat() / total).coerceIn(0f, 1f),
        )
    }
    is WorkspaceLaunchState.Completed -> {
        val ok = state.items.count { it.status == ItemProgress.Status.Succeeded }
        BannerView(
            message = if (ok == state.items.size) {
                stringResource(R.string.workspace_launch_completed, state.workspaceName)
            } else {
                stringResource(
                    R.string.workspace_launch_partial,
                    state.workspaceName,
                    ok,
                    state.items.size,
                )
            },
            isInFlight = false,
            progressFraction = null,
        )
    }
    is WorkspaceLaunchState.Cancelled -> BannerView(
        message = stringResource(R.string.workspace_launch_cancelled, state.workspaceName),
        isInFlight = false,
        progressFraction = null,
    )
    is WorkspaceLaunchState.Failed -> BannerView(
        message = stringResource(R.string.workspace_launch_failed, state.reason),
        isInFlight = false,
        progressFraction = null,
    )
}
