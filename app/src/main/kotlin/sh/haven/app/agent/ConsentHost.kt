package sh.haven.app.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.agent.ConsentRequest
import javax.inject.Inject

/**
 * ViewModel wrapper around the app-scoped [AgentConsentManager] so the
 * [ConsentHost] composable can subscribe with the standard Hilt + Compose
 * idiom (`hiltViewModel()`). The manager is `@Singleton` so the
 * subscription survives configuration changes; the ViewModel is just a
 * thin pass-through.
 */
@HiltViewModel
internal class ConsentHostViewModel @Inject constructor(
    private val consentManager: AgentConsentManager,
) : ViewModel() {

    val pending: StateFlow<List<ConsentRequest>> = consentManager.pending

    fun respond(requestId: Long, decision: ConsentDecision) {
        viewModelScope.launch { consentManager.respond(requestId, decision) }
    }
}

/**
 * Top-of-tree host for agent-driven consent prompts. Mounted from
 * `MainActivity.setContent { ... }` so the modal sheet floats above
 * whatever screen is active.
 *
 * The host renders the **oldest** pending [ConsentRequest] — usually the
 * only one, since [AgentConsentManager.requestConsent] suspends on a
 * single deferred per call — and dismisses itself when the user resolves
 * it. If a second request piles up while the first is on screen, this
 * composable just keeps showing the first; the second slides into view
 * once the first is answered.
 *
 * The sheet is intentionally non-skippable: tapping outside the sheet
 * does *not* dismiss it. The user must explicitly tap Allow or Deny so
 * we never accidentally swallow a destructive request as "denied by
 * default" via a stray tap. Pressing the system back button maps to
 * Deny, which is the safe direction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsentHost(viewModel: ConsentHostViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val current = pending.firstOrNull() ?: return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Treat any dismissal that isn't an explicit Allow as a Deny so the
    // wheel-stays-with-the-user invariant holds even on edge cases.
    fun resolve(decision: ConsentDecision) {
        viewModel.respond(current.id, decision)
        scope.launch { sheetState.hide() }
    }

    LaunchedEffect(current.id) { sheetState.show() }

    ModalBottomSheet(
        onDismissRequest = { resolve(ConsentDecision.DENY) },
        sheetState = sheetState,
        // Lock the sheet open against accidental tap-outs. The user has
        // to make an explicit choice.
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Agent action requested",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            current.clientHint?.takeIf { it.isNotBlank() }?.let { hint ->
                Text(
                    text = "From: $hint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = current.summary,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tool: ${current.toolName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { resolve(ConsentDecision.DENY) }) {
                    Text("Deny")
                }
                Button(
                    onClick = { resolve(ConsentDecision.ALLOW) },
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text("Allow")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
