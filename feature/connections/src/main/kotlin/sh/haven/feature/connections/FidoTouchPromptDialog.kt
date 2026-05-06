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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import sh.haven.core.fido.FidoTouchPrompt

/**
 * Modal prompt shown while a FIDO2 SSH assertion is in flight. The dialog is
 * not user-dismissible for the discovery and touch states — the JSch auth
 * path is awaiting the security key's signature, and there is no clean cancel
 * route from the UI thread back into a blocking USB / NFC transfer. The
 * dialog disappears automatically when [FidoTouchPrompt] flips back to null
 * in [FidoAuthenticator.touchPrompt], whether the assertion succeeds, fails,
 * or the underlying transfer times out.
 *
 * The PIN-entry state ([FidoTouchPrompt.EnterPin]) IS user-dismissible —
 * tapping Cancel calls back with `null`, which makes the authenticator throw
 * out of its PIN flow before any blocking touch wait begins.
 */
@Composable
fun FidoTouchPromptDialog(prompt: FidoTouchPrompt) {
    when (prompt) {
        is FidoTouchPrompt.EnterPin -> PinEntryDialog(prompt)
        is FidoTouchPrompt.WaitingForKey,
        is FidoTouchPrompt.TouchKey -> TouchDialog(prompt)
    }
}

@Composable
private fun TouchDialog(prompt: FidoTouchPrompt) {
    val (title, body) = when (prompt) {
        is FidoTouchPrompt.WaitingForKey -> "Security key required" to
            "Plug in your security key over USB, or tap it on the back of " +
            "the device for NFC. Haven will continue automatically once it is detected."
        is FidoTouchPrompt.TouchKey -> when (prompt.transport) {
            FidoTouchPrompt.TouchKey.Transport.USB -> "Touch your security key" to
                "Press the button on your security key now to authorise the SSH " +
                "signature. The key may blink to indicate it is waiting."
            FidoTouchPrompt.TouchKey.Transport.NFC -> "Hold your security key" to
                "Keep the security key against the back of the device for two " +
                "seconds while it signs. Moving it away early aborts the " +
                "exchange and you'll need to tap it again."
        }
        is FidoTouchPrompt.EnterPin -> error("PinEntryDialog handles this state")
    }

    AlertDialog(
        // Empty lambda — touch states are not user-dismissible. The
        // FidoAuthenticator clears the state when the assertion finishes
        // (success, failure, or timeout) and the dialog goes away with it.
        onDismissRequest = {},
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Cancel by disconnecting from the connections list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun PinEntryDialog(prompt: FidoTouchPrompt.EnterPin) {
    var pin by remember { mutableStateOf("") }

    val retriesNote = prompt.retriesRemaining?.let {
        "Wrong PIN. $it attempts remaining before the key locks."
    }

    AlertDialog(
        onDismissRequest = { prompt.submit(null) },
        title = { Text("Security key PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This SSH key was registered with verify-required. " +
                        "Enter the PIN configured on your security key to continue.",
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
                    label = { Text("PIN") },
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
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = { prompt.submit(null) }) { Text("Cancel") }
        },
    )
}
