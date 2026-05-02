package sh.haven.feature.connections

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import sh.haven.core.data.db.AgentAuditEventDao
import javax.inject.Inject

/** Window after the most recent agent action during which the chip stays lit. */
private const val ACTIVE_WINDOW_MS = 30_000L

/**
 * Tiny ViewModel feeding [AgentActiveChip] from the agent audit table.
 * Observing the latest timestamp directly off Room means the chip lights
 * up on every audit insert without any cross-cutting plumbing — `record`
 * inside [sh.haven.app.agent.AgentAuditRecorder] writes to the same dao.
 */
@HiltViewModel
internal class AgentActiveChipViewModel @Inject constructor(
    dao: AgentAuditEventDao,
) : ViewModel() {

    val lastEventAt: StateFlow<Long?> = dao.observeLatestTimestamp()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
}

/**
 * Header indicator that lights up while the MCP transport has had recent
 * activity. Absent when there is no audit row at all (the user has never
 * enabled the endpoint or never been called); rendered in a muted tone
 * outside the active window so the user can still discover the screen
 * with a tap; rendered in the primary accent during the active window
 * so it's unmissable.
 *
 * [onClick] should navigate the user to the audit-log surface. The chip
 * deliberately does no other UI work — it's a one-bit indicator and a
 * jump target, nothing more. Following the build-vs-delegate rule: the
 * detail view exists already (AgentActivityScreen), we just need to
 * surface a way in.
 */
@Composable
internal fun AgentActiveChip(
    onClick: () -> Unit,
    viewModel: AgentActiveChipViewModel = hiltViewModel(),
) {
    val lastEventAt by viewModel.lastEventAt.collectAsStateWithLifecycle()

    // 1 Hz tick so the chip auto-fades after [ACTIVE_WINDOW_MS] without
    // the audit recorder having to push a "fade" event. Using a
    // mutableLongState keeps the Compose graph happy without churn.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val ts = lastEventAt ?: return  // never been used → no chip at all
    val active by remember(ts, nowMs) {
        derivedStateOf { (nowMs - ts) in 0L..ACTIVE_WINDOW_MS }
    }

    val tint = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = if (active) "Agent active" else "Agent activity",
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
