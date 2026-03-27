package sh.haven.app.navigation

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.feature.vnc.VncScreen
import sh.haven.feature.vnc.VncViewModel

/**
 * Desktop screen — FOSS variant (VNC only, RDP excluded).
 */
@Composable
fun DesktopScreen(
    isActive: Boolean = true,
    pendingVncHost: String? = null,
    pendingVncPort: Int? = null,
    pendingVncPassword: String? = null,
    pendingVncSshForward: Boolean = false,
    pendingVncSshSessionId: String? = null,
    pendingRdpHost: String? = null,
    pendingRdpPort: Int? = null,
    pendingRdpUsername: String? = null,
    pendingRdpPassword: String? = null,
    pendingRdpDomain: String? = null,
    pendingRdpSshForward: Boolean = false,
    pendingRdpSshSessionId: String? = null,
    pendingRdpSshProfileId: String? = null,
    pendingRdpProfileId: String? = null,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    onPendingConsumed: () -> Unit = {},
    onFullscreenChanged: (Boolean) -> Unit = {},
    onConnectedChanged: (Boolean) -> Unit = {},
) {
    val vncViewModel: VncViewModel = hiltViewModel()
    val vncConnected by vncViewModel.connected.collectAsState()

    // RDP is not available in the F-Droid (FOSS) build — notify user
    val context = LocalContext.current
    LaunchedEffect(pendingRdpHost) {
        if (pendingRdpHost != null) {
            onPendingConsumed()
            Toast.makeText(
                context,
                "RDP is not available in the F-Droid build. Install Haven from GitHub releases for RDP support.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(vncConnected) { onConnectedChanged(vncConnected) }

    VncScreen(
        isActive = isActive,
        pendingHost = pendingVncHost,
        pendingPort = pendingVncPort,
        pendingPassword = pendingVncPassword,
        pendingSshForward = pendingVncSshForward,
        pendingSshSessionId = pendingVncSshSessionId,
        toolbarLayout = toolbarLayout,
        onPendingConsumed = onPendingConsumed,
        onFullscreenChanged = onFullscreenChanged,
        viewModel = vncViewModel,
    )
}
