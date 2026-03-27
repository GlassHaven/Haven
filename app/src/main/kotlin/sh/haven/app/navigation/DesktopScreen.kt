package sh.haven.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.feature.rdp.RdpScreen
import sh.haven.feature.rdp.RdpViewModel
import sh.haven.feature.vnc.VncScreen
import sh.haven.feature.vnc.VncViewModel

/**
 * Combined desktop screen with VNC/RDP protocol tabs.
 * When connected, the tab bar is hidden and the active viewer fills the screen.
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
    // 0 = VNC, 1 = RDP — persisted across recompositions
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val vncViewModel: VncViewModel = hiltViewModel()
    val rdpViewModel: RdpViewModel = hiltViewModel()

    // Auto-select tab when pending params arrive
    LaunchedEffect(pendingVncHost) {
        if (pendingVncHost != null) selectedTab = 0
    }
    LaunchedEffect(pendingRdpHost) {
        if (pendingRdpHost != null) {
            selectedTab = 1
            pendingRdpProfileId?.let { rdpViewModel.setProfileId(it) }
        }
    }

    // Check if either protocol is connected (to hide tab bar during active session)
    val vncConnected by vncViewModel.connected.collectAsState()
    val rdpConnected by rdpViewModel.connected.collectAsState()
    val anyConnected = vncConnected || rdpConnected

    LaunchedEffect(anyConnected) { onConnectedChanged(anyConnected) }

    // If one is connected, force that tab
    if (vncConnected) selectedTab = 0
    if (rdpConnected) selectedTab = 1

    Column(modifier = Modifier.fillMaxSize()) {
        // Protocol tabs — hidden when connected (viewer needs full screen)
        if (!anyConnected) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("VNC") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("RDP") },
                )
            }
        }

        when (selectedTab) {
            0 -> VncScreen(
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
            1 -> RdpScreen(
                isActive = isActive,
                pendingHost = pendingRdpHost,
                pendingPort = pendingRdpPort,
                pendingUsername = pendingRdpUsername,
                pendingPassword = pendingRdpPassword,
                pendingDomain = pendingRdpDomain,
                pendingSshForward = pendingRdpSshForward,
                pendingSshSessionId = pendingRdpSshSessionId,
                pendingSshProfileId = pendingRdpSshProfileId,
                toolbarLayout = toolbarLayout,
                onPendingConsumed = onPendingConsumed,
                onFullscreenChanged = onFullscreenChanged,
                viewModel = rdpViewModel,
            )
        }
    }
}
