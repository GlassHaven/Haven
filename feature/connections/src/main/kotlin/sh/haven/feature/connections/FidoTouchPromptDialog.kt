package sh.haven.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import sh.haven.core.fido.FidoTouchPrompt

/**
 * Modal prompt shown while a FIDO2 SSH assertion is in flight. All states are
 * cancellable: [onCancel] completes the pending key discovery exceptionally
 * (FidoAuthenticator.cancelPending), so the awaiting CTAP flow unwinds and the
 * prompt clears instead of the user being stuck until the transfer times out.
 * The dialog also disappears automatically when [FidoTouchPrompt] flips back to
 * null in [FidoAuthenticator.touchPrompt] (success / failure / timeout).
 *
 * The PIN-entry state ([FidoTouchPrompt.EnterPin]) cancels via its own
 * `submit(null)` (it has no pending discovery to unwind yet).
 */
@Composable
fun FidoTouchPromptDialog(prompt: FidoTouchPrompt, onCancel: () -> Unit) {
    when (prompt) {
        is FidoTouchPrompt.EnterPin -> PinEntryDialog(prompt)
        is FidoTouchPrompt.WaitingForKey,
        is FidoTouchPrompt.WrongKey,
        is FidoTouchPrompt.TouchKey -> TouchDialog(prompt, onCancel)
    }
}

@Composable
private fun TouchDialog(prompt: FidoTouchPrompt, onCancel: () -> Unit) {
    val (title, body) = when (prompt) {
        is FidoTouchPrompt.WaitingForKey -> stringResource(R.string.connections_fido_waiting_title) to
            stringResource(R.string.connections_fido_waiting_body)
        is FidoTouchPrompt.WrongKey -> stringResource(R.string.connections_fido_wrong_title) to
            stringResource(R.string.connections_fido_wrong_body)
        is FidoTouchPrompt.TouchKey -> when (prompt.transport) {
            FidoTouchPrompt.TouchKey.Transport.USB ->
                stringResource(R.string.connections_fido_touch_usb_title) to
                    stringResource(R.string.connections_fido_touch_usb_body)
            FidoTouchPrompt.TouchKey.Transport.NFC ->
                stringResource(R.string.connections_fido_touch_nfc_title) to
                    stringResource(R.string.connections_fido_touch_nfc_body)
        }
        is FidoTouchPrompt.EnterPin -> error("PinEntryDialog handles this state")
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Name the specific key the server asked for, so when a
                    // profile lists several keys the user presents the right
                    // one rather than guessing (#237).
                    prompt.keyLabel?.let { label ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.connections_fido_key_label, label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.connections_fido_cancel_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun PinEntryDialog(prompt: FidoTouchPrompt.EnterPin) {
    var pin by remember { mutableStateOf("") }
    // A wrong PIN re-opens this dialog as a fresh EnterPin prompt (the retry
    // carries a new retriesRemaining). Without this reset the remembered text
    // stays prefilled, so re-tapping OK resubmits the rejected PIN and burns
    // another attempt (#531). Keyed on the prompt instance, not position.
    LaunchedEffect(prompt) { pin = "" }

    val retriesNote = prompt.retriesRemaining?.let {
        stringResource(R.string.connections_fido_pin_wrong, it)
    }

    AlertDialog(
        onDismissRequest = { prompt.submit(null) },
        title = { Text(stringResource(R.string.connections_fido_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.connections_fido_pin_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (retriesNote != null) {
                    Text(
                        retriesNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.connections_fido_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { prompt.submit(pin) },
                enabled = pin.isNotEmpty(),
            ) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { prompt.submit(null) }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
