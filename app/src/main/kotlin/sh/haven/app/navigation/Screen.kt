package sh.haven.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Connections("connections", "Connect", Icons.Filled.Cable),
    Terminal("terminal", "Terminal", Icons.Filled.Terminal),
    Desktop("desktop", "Desktop", Icons.Filled.DesktopWindows),
    Keys("keys", "Keys", Icons.Filled.VpnKey),
    Sftp("sftp", "Files", Icons.Filled.Folder),
    Settings("settings", "Settings", Icons.Filled.Settings),
}
