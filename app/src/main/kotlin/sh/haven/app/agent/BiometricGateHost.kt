package sh.haven.app.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sh.haven.core.data.keystore.BiometricGate
import sh.haven.core.security.BiometricAuthenticator
import javax.inject.Inject

/**
 * ViewModel pass-through for [BiometricGate] so [BiometricGateHost] can
 * subscribe via the standard `hiltViewModel()` idiom — same pattern as
 * `ConsentHostViewModel`. Both pieces (the manager and the
 * [BiometricAuthenticator]) are app-scoped, the ViewModel just observes
 * and forwards.
 */
@HiltViewModel
internal class BiometricGateHostViewModel @Inject constructor(
    private val gate: BiometricGate,
    val authenticator: BiometricAuthenticator,
) : ViewModel() {

    val pending: StateFlow<List<BiometricGate.Request>> = gate.pending

    fun respond(requestId: Long, decision: BiometricGate.Decision) {
        viewModelScope.launch { gate.respond(requestId, decision) }
    }
}

/**
 * Top-of-tree host for [BiometricGate] requests. Mounted from
 * `MainActivity.setContent { ... }` alongside [ConsentHost].
 *
 * Each pending [BiometricGate.Request] triggers a single
 * `BiometricPrompt` via [BiometricAuthenticator]. The prompt result
 * maps back to [BiometricGate.Decision] and gets posted to the gate so
 * the suspending [sh.haven.core.data.keystore.SshKeySection.fetch]
 * caller resumes.
 */
@Composable
internal fun BiometricGateHost(viewModel: BiometricGateHostViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val current = pending.firstOrNull() ?: return
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return

    LaunchedEffect(current.id) {
        val result = viewModel.authenticator.authenticate(
            activity = activity,
            title = current.label,
            subtitle = current.detail ?: "Authenticate to unlock the key",
        )
        val decision = when (result) {
            is BiometricAuthenticator.AuthResult.Success -> BiometricGate.Decision.ALLOW
            is BiometricAuthenticator.AuthResult.Failure -> BiometricGate.Decision.DENY
            is BiometricAuthenticator.AuthResult.Cancelled -> BiometricGate.Decision.DENY
        }
        viewModel.respond(current.id, decision)
    }
}
