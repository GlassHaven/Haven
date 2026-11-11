package sh.haven.feature.connections

import androidx.compose.runtime.Composable
import sh.haven.core.fido.FidoTouchPrompt
import sh.haven.core.ui.components.FidoPromptUiState
import sh.haven.core.ui.components.FidoTouchPromptDialog as SharedFidoTouchPromptDialog

/**
 * Adapter from `:core:fido`'s [FidoTouchPrompt] onto the shared `:core:ui`
 * [SharedFidoTouchPromptDialog] component.
 */
@Composable
fun FidoTouchPromptDialog(prompt: FidoTouchPrompt, onCancel: () -> Unit) {
    val uiState = when (prompt) {
        is FidoTouchPrompt.WaitingForKey -> FidoPromptUiState.WaitingForKey(keyLabel = prompt.keyLabel)
        is FidoTouchPrompt.WrongKey -> FidoPromptUiState.WrongKey(
            keyLabel = prompt.keyLabel,
            attemptsLeft = prompt.attemptsLeft,
        )
        is FidoTouchPrompt.TouchKey -> FidoPromptUiState.TouchKey(
            transport = when (prompt.transport) {
                FidoTouchPrompt.TouchKey.Transport.USB -> FidoPromptUiState.TouchKey.Transport.USB
                FidoTouchPrompt.TouchKey.Transport.NFC -> FidoPromptUiState.TouchKey.Transport.NFC
            },
            keyLabel = prompt.keyLabel,
        )
        is FidoTouchPrompt.EnterPin -> FidoPromptUiState.EnterPin(
            submit = prompt.submit,
            retriesRemaining = prompt.retriesRemaining,
            keyLabel = prompt.keyLabel,
            settingNew = prompt.settingNew,
            promptKey = prompt,
        )
    }
    SharedFidoTouchPromptDialog(prompt = uiState, onCancel = onCancel)
}
