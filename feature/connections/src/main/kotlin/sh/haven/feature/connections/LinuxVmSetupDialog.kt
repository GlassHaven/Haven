package sh.haven.feature.connections

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val SCRIPT_URL = "https://github.com/GlassOnTin/Haven/blob/main/scripts/haven-vm-setup.sh"
private const val QUICK_SETUP = """curl -sL https://raw.githubusercontent.com/GlassOnTin/Haven/main/scripts/haven-vm-setup.sh | bash"""

private const val SSH_SETUP = """sudo su -c "passwd droid"
sudo apt update && sudo apt install -y openssh-server
sudo systemctl enable --now ssh"""

private const val VNC_SETUP = """sudo apt install -y tigervnc-standalone-server dbus-x11
vncpasswd
vncserver :1 -localhost no -geometry 1920x1080"""

private const val DESKTOP_SETUP = """sudo apt install -y xfce4 xfce4-terminal
# Then restart VNC:
vncserver -kill :1
vncserver :1 -localhost no -geometry 1920x1080"""

@Composable
fun LinuxVmSetupDialog(
    vmStatus: LocalVmStatus,
    onConnectSsh: (port: Int) -> Unit,
    onConnectSshDirect: (ip: String, port: Int) -> Unit,
    onConnectVnc: (port: Int) -> Unit,
    onConnectVncDirect: (ip: String, port: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val hasLocalServices = vmStatus.sshPort != null || vmStatus.vncPort != null
    val hasDirectServices = vmStatus.directSshPort != null || vmStatus.directVncPort != null
    val hasAnyServices = hasLocalServices || hasDirectServices

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Linux VM") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Status section
                if (hasAnyServices) {
                    Text("Detected services", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    if (vmStatus.sshPort != null) {
                        StatusRow(
                            label = "SSH on localhost:${vmStatus.sshPort}",
                            actionLabel = "Connect",
                            onClick = { onConnectSsh(vmStatus.sshPort) },
                        )
                    }
                    if (vmStatus.vncPort != null) {
                        StatusRow(
                            label = "VNC on localhost:${vmStatus.vncPort}",
                            actionLabel = "Connect",
                            onClick = { onConnectVnc(vmStatus.vncPort) },
                        )
                    }
                    if (vmStatus.directIp != null && vmStatus.directSshPort != null) {
                        StatusRow(
                            label = "SSH on ${vmStatus.directIp}:${vmStatus.directSshPort}",
                            actionLabel = "Connect",
                            onClick = { onConnectSshDirect(vmStatus.directIp, vmStatus.directSshPort) },
                        )
                    }
                    if (vmStatus.directIp != null && vmStatus.directVncPort != null) {
                        StatusRow(
                            label = "VNC on ${vmStatus.directIp}:${vmStatus.directVncPort}",
                            actionLabel = "Connect",
                            onClick = { onConnectVncDirect(vmStatus.directIp, vmStatus.directVncPort) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // Quick setup
                Text("Quick setup", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Installs SSH, VNC, and Xfce4 desktop:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("setup", QUICK_SETUP))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy install command")
                }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SCRIPT_URL)))
                }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("View script")
                }

                // Collapsible step-by-step instructions
                var showSteps by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showSteps = !showSteps }) {
                    Text(if (showSteps) "Hide setup steps" else "Show setup steps")
                }

                if (showSteps) {
                    Spacer(Modifier.height(8.dp))

                    // Launch Terminal app
                    Text("1. Start the Linux VM", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open the Android Terminal app to boot the VM.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { launchTerminalApp(context) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open Terminal app")
                    }

                    Spacer(Modifier.height(12.dp))

                    // Port forwarding
                    Text("2. Open port 8022", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "In the Terminal app settings, add port 8022 to the listening ports list.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Spacer(Modifier.height(12.dp))

                    // SSH setup
                    Text("3. Set password & install SSH", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Run in the Terminal app. You'll be prompted to set a password for the droid user.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    CodeBlock(code = SSH_SETUP, context = context)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Then connect with: droid@localhost:8022",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(12.dp))

                    // VNC setup
                    Text("4. Install VNC server (optional)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "For remote desktop access via Haven's Desktop tab.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    CodeBlock(code = VNC_SETUP, context = context)

                    Spacer(Modifier.height(12.dp))

                    // Desktop environment
                    Text("5. Install a desktop (optional)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Xfce4 is lightweight and works well over VNC.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    CodeBlock(code = DESKTOP_SETUP, context = context)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun StatusRow(
    label: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(actionLabel) }
    }
}

@Composable
private fun CodeBlock(code: String, context: Context) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(8.dp),
        ) {
            Text(
                text = code.trimIndent(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            )
            IconButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("commands", code.trimIndent()))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Filled.ContentCopy, "Copy", Modifier.size(16.dp))
            }
        }
    }
}

private fun launchTerminalApp(context: Context) {
    val intent = Intent("android.virtualization.VM_TERMINAL").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // Terminal app not installed — try by package name
        val fallback = context.packageManager.getLaunchIntentForPackage(
            "com.android.virtualization.terminal"
        )
        if (fallback != null) {
            context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(context, "Terminal app not found", Toast.LENGTH_SHORT).show()
        }
    }
}
