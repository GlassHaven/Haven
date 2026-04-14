package sh.haven.feature.connections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.ui.PasswordField

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeployKeyDialog(
    profile: ConnectionProfile,
    keys: List<SshKey>,
    onDismiss: () -> Unit,
    onDeploy: (keyId: String, password: String) -> Unit,
) {
    var selectedKey by remember { mutableStateOf(keys.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deploy SSH Key") },
        text = {
            Column {
                Text("${profile.username}@${profile.host}:${profile.port}")

                // Key selector dropdown
                Box(modifier = Modifier.padding(top = 16.dp)) {
                    OutlinedTextField(
                        value = selectedKey?.let { "${it.label} (${it.fingerprintSha256})" } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("SSH key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .combinedClickable(onClick = { expanded = true }),
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        keys.forEach { key ->
                            DropdownMenuItem(
                                text = { Text("${key.label} (${key.fingerprintSha256})") },
                                onClick = {
                                    selectedKey = key
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    imeAction = ImeAction.Go,
                    onImeAction = { selectedKey?.let { onDeploy(it.id, password) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedKey?.let { onDeploy(it.id, password) } },
                enabled = selectedKey != null,
            ) {
                Text("Deploy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
