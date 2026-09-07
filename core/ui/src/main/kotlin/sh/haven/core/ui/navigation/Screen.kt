package sh.haven.core.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector
import sh.haven.core.ui.R

enum class Screen(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    /**
     * True for tabs that are always visible and cannot be configured to hide
     * in the per-tab visibility setting (#navbar-visibility). Connections is the
     * master list and Settings hosts the very control that would otherwise be
     * unreachable if it could hide itself.
     */
    val isAlwaysVisible: Boolean,
) {
    Connections("connections", R.string.nav_connections, Icons.Filled.Cable, isAlwaysVisible = true),
    Terminal("terminal", R.string.nav_terminal, Icons.Filled.Terminal, isAlwaysVisible = false),
    Desktop("desktop", R.string.nav_desktop, Icons.Filled.DesktopWindows, isAlwaysVisible = false),
    Keys("keys", R.string.nav_keys, Icons.Filled.VpnKey, isAlwaysVisible = false),
    Sftp("sftp", R.string.nav_sftp, Icons.Filled.Folder, isAlwaysVisible = false),
    Mail("mail", R.string.nav_mail, Icons.Filled.Mail, isAlwaysVisible = false),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings, isAlwaysVisible = true),
}
