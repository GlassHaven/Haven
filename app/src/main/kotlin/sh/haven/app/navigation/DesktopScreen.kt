package sh.haven.app.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import sh.haven.app.desktop.DesktopTab
import sh.haven.app.desktop.DesktopViewModel
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.wayland.WaylandDesktopView
import sh.haven.feature.rdp.RdpSessionContent
import sh.haven.feature.vnc.VncSessionContent
import sh.haven.feature.vnc.charToKeySym

private val TAB_COLORS = listOf(
    Color(0xFF42A5F5), // blue
    Color(0xFF66BB6A), // green
    Color(0xFFFF7043), // orange
    Color(0xFFAB47BC), // purple
    Color(0xFFFFCA28), // amber
    Color(0xFF26C6DA), // cyan
    Color(0xFFEF5350), // red
    Color(0xFF8D6E63), // brown
)

/**
 * Multi-session desktop screen with VNC/RDP/Wayland tabs.
 * Mirrors the terminal's multi-tab pattern.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopScreen(
    desktopViewModel: DesktopViewModel,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    navBlockMode: NavBlockMode = NavBlockMode.ALIGNED,
    inputMode: String = "DIRECT",
    onFullscreenChanged: (Boolean) -> Unit = {},
    onConnectedChanged: (Boolean) -> Unit = {},
) {
    val tabs by desktopViewModel.tabs.collectAsState()
    val activeTabIndex by desktopViewModel.activeTabIndex.collectAsState()
    val activeTab by desktopViewModel.activeTab.collectAsState()
    val anyConnected by desktopViewModel.activeTabConnected.collectAsState()

    LaunchedEffect(anyConnected) { onConnectedChanged(anyConnected) }

    // Orientation is owned by DesktopViewModel — keeping the state
    // outside the inner composables sidesteps the slot-position shift
    // that the conditional tab bar (line 97 below) caused: when
    // `isConnected` momentarily flips during a rotation
    // recomposition, the bar appeared/disappeared and Compose tore
    // down + recreated RdpViewer / VncViewer, resetting their
    // `remember` state to the Landscape default. The LaunchedEffect
    // here applies the chosen orientation to the Activity from a
    // stable parent (DesktopScreen itself, not the conditional
    // child).
    val activity = androidx.activity.compose.LocalActivity.current
    val desktopOrientation by desktopViewModel.desktopOrientation.collectAsState()
    LaunchedEffect(desktopOrientation, activity) {
        activity?.requestedOrientation = desktopOrientation
    }
    androidx.compose.runtime.DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (tabs.isEmpty()) {
            DesktopEmptyState()
        } else {
            // Tab bar — always visible when tabs exist (hidden in fullscreen by parent)
            val isConnected by (activeTab?.connected ?: MutableStateFlow(false)).collectAsState()
            if (tabs.size > 1 || !isConnected) {
                DesktopTabBar(
                    tabs = tabs,
                    activeTabIndex = activeTabIndex,
                    onSelectTab = { desktopViewModel.selectTab(it) },
                    onMoveTab = { idx, dir -> desktopViewModel.moveTab(idx, dir) },
                    onCloseTab = { desktopViewModel.closeTab(it) },
                )
            }

            // Active tab content
            val tab = activeTab
            if (tab != null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        is DesktopTab.Vnc -> VncSessionContent(
                            connected = tab.connected,
                            frame = tab.frame,
                            error = tab.error,
                            toolbarLayout = toolbarLayout,
                            onTap = { x, y -> desktopViewModel.sendClick(x, y) },
                            onLongPress = { x, y -> desktopViewModel.sendClick(x, y, button = 3) },
                            onDragStart = { x, y ->
                                desktopViewModel.sendPointer(x, y)
                                desktopViewModel.pressButton(1)
                            },
                            onDrag = { x, y -> desktopViewModel.sendPointer(x, y) },
                            onDragEnd = { desktopViewModel.releaseButton(1) },
                            onScrollUp = { desktopViewModel.scrollUp() },
                            onScrollDown = { desktopViewModel.scrollDown() },
                            onTypeChar = { ch -> desktopViewModel.typeVncKey(charToKeySym(ch)) },
                            onTypeText = { text -> desktopViewModel.typeVncText(text) },
                            onKeyDown = { keySym -> desktopViewModel.sendVncKey(keySym, true) },
                            onKeyUp = { keySym -> desktopViewModel.sendVncKey(keySym, false) },
                            onDisconnect = { desktopViewModel.closeTab(tab.id) },
                            onFullscreenChanged = onFullscreenChanged,
                            cursor = tab.cursor,
                            pointerPos = tab.pointerPos,
                            inputMode = inputMode,
                            bandwidthSuggestion = tab.bandwidthSuggestion,
                            onAcceptBandwidthSuggestion = { desktopViewModel.acceptBandwidthSuggestion(tab.id) },
                            onDismissBandwidthSuggestion = { desktopViewModel.dismissBandwidthSuggestion(tab.id) },
                            currentOrientation = desktopOrientation,
                            onCycleOrientation = { desktopViewModel.cycleDesktopOrientation() },
                        )

                        is DesktopTab.Rdp -> RdpSessionContent(
                            connected = tab.connected,
                            frame = tab.frame,
                            error = tab.error,
                            toolbarLayout = toolbarLayout,
                            onTap = { x, y -> desktopViewModel.sendClick(x, y) },
                            onDragStart = { x, y ->
                                desktopViewModel.sendPointer(x, y)
                                desktopViewModel.pressButton(1)
                            },
                            onDrag = { x, y -> desktopViewModel.sendPointer(x, y) },
                            onDragEnd = { desktopViewModel.releaseButton(1) },
                            onScrollUp = { desktopViewModel.scrollUp() },
                            onScrollDown = { desktopViewModel.scrollDown() },
                            onTypeChar = { ch ->
                                sh.haven.feature.rdp.typeRdpChar(
                                    ch = ch,
                                    sendKey = { sc, pressed -> desktopViewModel.sendRdpKey(sc, pressed) },
                                    sendUnicode = { codepoint -> desktopViewModel.typeRdpUnicode(codepoint) },
                                )
                            },
                            onKeyDown = { scancode -> desktopViewModel.sendRdpKey(scancode, true) },
                            onKeyUp = { scancode -> desktopViewModel.sendRdpKey(scancode, false) },
                            onDisconnect = { desktopViewModel.closeTab(tab.id) },
                            onFullscreenChanged = onFullscreenChanged,
                            pointerPos = tab.pointerPos,
                            inputMode = inputMode,
                            currentOrientation = desktopOrientation,
                            onCycleOrientation = { desktopViewModel.cycleDesktopOrientation() },
                        )

                        is DesktopTab.Wayland -> WaylandDesktopView(
                            modifier = Modifier.fillMaxSize(),
                            toolbarLayout = toolbarLayout,
                            navBlockMode = navBlockMode,
                            onFullscreenChanged = onFullscreenChanged,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Desktop", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Connect via VNC or RDP from the Connections tab",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopTabBar(
    tabs: List<DesktopTab>,
    activeTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    onMoveTab: (Int, Int) -> Unit,
    onCloseTab: (String) -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = activeTabIndex == index
                var showTabMenu by remember { mutableStateOf(false) }
                val tabColor = if (tab.colorTag in 1..TAB_COLORS.size) {
                    TAB_COLORS[tab.colorTag - 1]
                } else {
                    null
                }

                Box {
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .combinedClickable(
                                onClick = { onSelectTab(index) },
                                onLongClick = { showTabMenu = true },
                            ),
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) {
                            tabColor?.copy(alpha = 0.55f)
                                ?: MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            tabColor?.copy(alpha = 0.25f)
                                ?: MaterialTheme.colorScheme.surface
                        },
                        contentColor = run {
                            val bg = tabColor ?: return@run if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val alpha = if (selected) 0.55f else 0.25f
                            val surfaceLum = MaterialTheme.colorScheme.surface.luminance()
                            val effectiveLum = surfaceLum * (1 - alpha) + bg.luminance() * alpha
                            if (effectiveLum > 0.5f) Color.Black else Color.White
                        },
                        tonalElevation = if (selected) 4.dp else 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 12.dp,
                                vertical = 8.dp,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${tab.protocol} ${tab.label}",
                                maxLines = 1,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    // Long-press menu
                    DropdownMenu(
                        expanded = showTabMenu,
                        onDismissRequest = { showTabMenu = false },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { onMoveTab(index, -1); showTabMenu = false },
                                enabled = index > 0,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Move left", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { onMoveTab(index, 1); showTabMenu = false },
                                enabled = index < tabs.size - 1,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move right", modifier = Modifier.size(18.dp))
                            }
                        }
                        TextButton(
                            onClick = {
                                showTabMenu = false
                                onCloseTab(tab.id)
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Close")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
