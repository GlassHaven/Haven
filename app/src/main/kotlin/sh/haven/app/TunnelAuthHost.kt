package sh.haven.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import sh.haven.core.tunnel.TunnelAuthBroker
import sh.haven.feature.connections.KeyboardInteractiveDialog
import javax.inject.Inject

/** Pass-through to the app-scoped [TunnelAuthBroker]; same idiom as BiometricGateHostViewModel. */
@HiltViewModel
internal class TunnelAuthHostViewModel @Inject constructor(
    broker: TunnelAuthBroker,
) : ViewModel() {
    val pending: StateFlow<TunnelAuthBroker.Request?> = broker.pending
}

/**
 * Top-of-tree host for VPN sign-in questions (an AnyConnect SMS code, or a
 * password the tunnel doesn't store). Mounted from `MainActivity.setContent`
 * next to ConsentHost / BiometricGateHost, so it appears over whatever
 * screen is showing — including when the question comes from a background
 * re-login and the user arrived via the broker's notification. Reuses the
 * SSH keyboard-interactive dialog: same shape (server text + one field).
 */
@Composable
internal fun TunnelAuthHost(viewModel: TunnelAuthHostViewModel = hiltViewModel()) {
    val request by viewModel.pending.collectAsStateWithLifecycle()
    val current = request ?: return
    KeyboardInteractiveDialog(
        challenge = current.challenge,
        onSubmit = { responses -> current.submit(responses.firstOrNull().orEmpty()) },
        onCancel = { current.cancel() },
    )
}
