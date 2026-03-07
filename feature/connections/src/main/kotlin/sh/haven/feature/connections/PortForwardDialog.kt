package sh.haven.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.ssh.SshSessionManager

@Composable
fun PortForwardDialog(
    profileLabel: String,
    profileId: String,
    rules: List<PortForwardRule>,
    activeForwards: List<SshSessionManager.PortForwardInfo>,
    onSave: (PortForwardRule) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // null = list view, non-null = editing that rule (new rule has a fresh id)
    var editingRule by remember { mutableStateOf<PortForwardRule?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    editingRule != null && rules.any { it.id == editingRule?.id } -> "Edit Port Forward"
                    editingRule != null -> "New Port Forward"
                    else -> "Port Forwards for $profileLabel"
                },
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val currentEdit = editingRule
                if (currentEdit != null) {
                    PortForwardForm(
                        initial = currentEdit,
                        profileId = profileId,
                        existingRules = rules,
                        onSave = { rule ->
                            onSave(rule)
                            editingRule = null
                        },
                        onCancel = { editingRule = null },
                    )
                } else {
                    if (rules.isEmpty()) {
                        Text(
                            "No port forward rules configured.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }

                    rules.forEach { rule ->
                        val activeForward = activeForwards.firstOrNull { it.ruleId == rule.id }
                        PortForwardCard(
                            rule = rule,
                            isActive = activeForward != null,
                            onEdit = { editingRule = rule },
                            onDelete = { onDelete(rule.id) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (editingRule == null) {
                TextButton(onClick = {
                    editingRule = PortForwardRule(profileId = profileId, type = PortForwardRule.Type.LOCAL, bindPort = 0, targetPort = 0)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Rule")
                }
            }
        },
        dismissButton = {
            if (editingRule == null) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}

@Composable
private fun PortForwardCard(
    rule: PortForwardRule,
    isActive: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isLocal = rule.type == PortForwardRule.Type.LOCAL

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Type label + status + edit/delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isLocal) "LOCAL" else "REMOTE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Circle,
                        contentDescription = if (isActive) "Active" else "Inactive",
                        tint = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(8.dp),
                    )
                }
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Visual flow: listen side --> tunnel --> destination side
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // Listen side
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (isLocal) Icons.Filled.PhoneAndroid else Icons.Filled.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (isLocal) "This device" else "Remote server",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "listens on",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "${rule.bindAddress}:${rule.bindPort}",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }

                // Tunnel arrow
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // Destination side
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (isLocal) Icons.Filled.Storage else Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (isLocal) "Remote server" else "This device",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "delivers to",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "${rule.targetHost}:${rule.targetPort}",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun PortForwardForm(
    initial: PortForwardRule,
    profileId: String,
    existingRules: List<PortForwardRule>,
    onSave: (PortForwardRule) -> Unit,
    onCancel: () -> Unit,
) {
    val isNew = initial.bindPort == 0 && initial.targetPort == 0
    var type by remember { mutableStateOf(initial.type) }
    var bindAddress by remember { mutableStateOf(initial.bindAddress) }
    var bindPort by remember { mutableStateOf(if (isNew) "" else initial.bindPort.toString()) }
    var targetHost by remember { mutableStateOf(initial.targetHost) }
    var targetPort by remember { mutableStateOf(if (isNew) "" else initial.targetPort.toString()) }

    val isLocal = type == PortForwardRule.Type.LOCAL

    // Validate ports
    val bPort = bindPort.toIntOrNull()
    val tPort = targetPort.toIntOrNull()

    val bindPortError = when {
        bindPort.isBlank() -> null
        bPort == null -> "Not a number"
        bPort !in 1..65535 -> "Must be 1\u201365535"
        bPort < 1024 && isLocal -> "Privileged port (< 1024) — may fail without root"
        existingRules.any { it.id != initial.id && it.type == type && it.bindPort == bPort && it.bindAddress == bindAddress } ->
            "Already used by another rule"
        else -> null
    }
    val bindPortIsWarning = bindPortError != null && bPort != null && bPort in 1..1023 && isLocal

    val targetPortError = when {
        targetPort.isBlank() -> null
        tPort == null -> "Not a number"
        tPort !in 1..65535 -> "Must be 1\u201365535"
        else -> null
    }

    val canSave = bPort != null && bPort in 1..65535 && tPort != null && tPort in 1..65535 &&
        (bindPortIsWarning || bindPortError == null) && targetPortError == null

    Column {
        // Type selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isLocal,
                onClick = {
                    type = PortForwardRule.Type.LOCAL
                    bindAddress = "127.0.0.1"
                },
                label = { Text("Local (-L)") },
            )
            FilterChip(
                selected = !isLocal,
                onClick = {
                    type = PortForwardRule.Type.REMOTE
                    bindAddress = "0.0.0.0"
                },
                label = { Text("Remote (-R)") },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Explanation of what the selected type does
        Text(
            if (isLocal) "Listen on this device, forward to remote server"
            else "Listen on remote server, forward to this device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Listen side — labels change based on direction
        Text(
            if (isLocal) "Listen on (this device)" else "Listen on (remote server)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = bindAddress,
                onValueChange = { bindAddress = it },
                label = { Text("Address") },
                singleLine = true,
                modifier = Modifier.weight(1.5f),
            )
            OutlinedTextField(
                value = bindPort,
                onValueChange = { bindPort = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                singleLine = true,
                isError = bindPortError != null && !bindPortIsWarning,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        if (bindPortError != null) {
            Text(
                bindPortError,
                style = MaterialTheme.typography.bodySmall,
                color = if (bindPortIsWarning) Color(0xFFFFA000) else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Destination side — labels change based on direction
        Text(
            if (isLocal) "Forward to (remote server)" else "Forward to (this device)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = targetHost,
                onValueChange = { targetHost = it },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.weight(1.5f),
            )
            OutlinedTextField(
                value = targetPort,
                onValueChange = { targetPort = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                singleLine = true,
                isError = targetPortError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        if (targetPortError != null) {
            Text(
                targetPortError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val bp = bPort ?: return@TextButton
                    val tp = tPort ?: return@TextButton
                    onSave(
                        initial.copy(
                            profileId = profileId,
                            type = type,
                            bindAddress = bindAddress,
                            bindPort = bp,
                            targetHost = targetHost,
                            targetPort = tp,
                        ),
                    )
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        }
    }
}
