package sh.haven.feature.connections

import android.Manifest
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ui.PasswordField

/**
 * Intent of the password prompt — drives label, helper text, and the
 * "leave empty to connect with SSH key" hint. Pre-fix, the dialog
 * always showed the "leave empty" hint whenever any key was in the
 * repository, including for profiles whose assigned key was
 * passphrase-protected — where leaving empty produced a silent
 * JSch decrypt failure with no hint that the entered password was
 * being used as the key passphrase. See GlassOnTin/Haven#75
 * (BlackDex follow-up).
 */
enum class PasswordDialogMode {
    /** No key is assigned and no keys are in the repository — plain host/user password. */
    PASSWORD_ONLY,
    /** Keys exist but none are assigned to this profile — leaving empty will try them all. */
    PASSWORD_OR_UNASSIGNED_KEY,
    /** An unencrypted key is assigned — the password field is only a fallback. */
    PASSWORD_OR_ASSIGNED_KEY,
    /** An encrypted key is assigned — the field is the *key passphrase*, not a host password. */
    ASSIGNED_ENCRYPTED_KEY_PASSPHRASE,
}

/** Check whether the currently active keyboard has INTERNET permission. */
@Composable
private fun rememberKeyboardHasInternet(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val currentIme = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            )
            val pkgName = currentIme?.substringBefore('/') ?: return@remember false
            val pkgInfo = context.packageManager.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
            pkgInfo.requestedPermissions?.any { it == Manifest.permission.INTERNET } == true
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
fun PasswordDialog(
    profile: ConnectionProfile,
    hasKeys: Boolean,
    onDismiss: () -> Unit,
    onConnect: (username: String?, password: String, rememberPassword: Boolean) -> Unit,
    mode: PasswordDialogMode = if (hasKeys) PasswordDialogMode.PASSWORD_OR_UNASSIGNED_KEY
        else PasswordDialogMode.PASSWORD_ONLY,
    assignedKeyLabel: String? = null,
) {
    val needsUsername = profile.username.isBlank() && (profile.isSsh || profile.isReticulum)
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(!profile.sshPassword.isNullOrBlank()) }
    val keyboardHasInternet = rememberKeyboardHasInternet()
    val canSubmit = !needsUsername || username.isNotBlank()
    val submit: () -> Unit = {
        if (canSubmit) {
            onConnect(if (needsUsername) username else null, password, rememberPassword)
        }
    }

    val fieldLabel = when (mode) {
        PasswordDialogMode.ASSIGNED_ENCRYPTED_KEY_PASSPHRASE -> "Key passphrase"
        else -> "Password"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to ${profile.label}") },
        text = {
            Column {
                if (keyboardHasInternet) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                "Your keyboard has internet access and may transmit what you type. Consider using a privacy keyboard (e.g. HeliBoard, Simple Keyboard).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
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
                    needsUsername -> Text("${profile.host}:${profile.port}")
                    else -> Text("${profile.username}@${profile.host}:${profile.port}")
                }
                if (needsUsername) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (mode == PasswordDialogMode.ASSIGNED_ENCRYPTED_KEY_PASSPHRASE && assignedKeyLabel != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enter the passphrase for SSH key \"$assignedKeyLabel\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = fieldLabel,
                    imeAction = ImeAction.Go,
                    onImeAction = submit,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (profile.isSsh || profile.isSmb) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { rememberPassword = it },
                        )
                        Text(
                            // When the field holds a key passphrase the
                            // checkbox label should say so too — otherwise
                            // users think they're caching a host password.
                            if (mode == PasswordDialogMode.ASSIGNED_ENCRYPTED_KEY_PASSPHRASE) {
                                "Remember key passphrase"
                            } else {
                                "Remember password"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // Only show the "leave empty" hint when leaving empty will
                // actually work — i.e. no key is assigned (so Haven will try
                // every unencrypted key in the repository). For profiles with
                // an assigned encrypted key, leaving empty produces a silent
                // JSch decrypt failure; for profiles with an assigned
                // unencrypted key the dialog would not normally be shown in
                // the first place. See #75 follow-up.
                if (mode == PasswordDialogMode.PASSWORD_OR_UNASSIGNED_KEY && profile.isSsh) {
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
            TextButton(onClick = submit, enabled = canSubmit) {
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
