package sh.haven.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import sh.haven.core.data.db.entities.ConnectionProfile

@Composable
fun PasswordDialog(
    profile: ConnectionProfile,
    hasKeys: Boolean,
    onDismiss: () -> Unit,
    onConnect: (String, Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(!profile.sshPassword.isNullOrBlank()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to ${profile.label}") },
        text = {
            Column {
                when {
                    profile.isRdp -> {
                        Text("${profile.rdpUsername ?: profile.username}@${profile.host}:${profile.rdpPort}")
                        if (!profile.rdpDomain.isNullOrBlank()) {
                            Text(
                                "Domain: ${profile.rdpDomain}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> Text("${profile.username}@${profile.host}:${profile.port}")
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { onConnect(password, rememberPassword) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (profile.isSsh) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { checked -> rememberPassword = checked },
                        )
                        Text(
                            "Remember password",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                        )
                    }
                }
                if (hasKeys && profile.isSsh) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Leave empty to connect with SSH key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(password, rememberPassword) }) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
