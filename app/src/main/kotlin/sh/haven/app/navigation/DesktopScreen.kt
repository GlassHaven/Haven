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
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.haven.app.R
import sh.haven.app.desktop.DesktopManagerScreen
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
    rdpChipAnchor: sh.haven.core.data.preferences.RdpChipAnchor =
        sh.haven.core.data.preferences.RdpChipAnchor.DEFAULT,
    onRdpChipAnchorChange: (sh.haven.core.data.preferences.RdpChipAnchor) -> Unit = {},
    navBlockMode: NavBlockMode = NavBlockMode.ALIGNED,
    toolbarUniformGrid: Boolean = false,
    /** Same toolbar_key_scale pref the terminal toolbar reads; wires the wayland desktop toolbar to #641's key scale. */
    toolbarKeyScale: Float = 1f,
    inputMode: String = "DIRECT",
    onSetInputMode: ((String) -> Unit)? = null,
    /** TOUCHPAD-mode speed multiplier; viewer-side gesture math scales by it (#696). */
    touchpadSensitivity: Float = 1f,
    /** Persist trackpad sensitivity changes from the toolbar (#696); null hides the control. */
    onSetTouchpadSensitivity: ((Float) -> Unit)? = null,
    isActive: Boolean = true,
    /** When the active viewer is fullscreen, the manager's own chrome (the
     *  compact Manage row + tab bar) is hidden too, so a small-screen RDP/VNC
     *  session is truly edge-to-edge (#212). The viewer's floating overlay
     *  remains the way back out of fullscreen. */
    fullscreen: Boolean = false,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onConnectedChanged: (Boolean) -> Unit = {},
) {
    val tabs by desktopViewModel.tabs.collectAsState()
    val activeTabIndex by desktopViewModel.activeTabIndex.collectAsState()
    val activeTab by desktopViewModel.activeTab.collectAsState()
    val anyConnected by desktopViewModel.activeTabConnected.collectAsState()

    LaunchedEffect(anyConnected) { onConnectedChanged(anyConnected) }

    // Surface transient errors from background tasks (desktop start
    // failures, timeouts — #169). Toast is consistent with how
    // ConnectionsScreen renders the same class of message and works
    // without requiring a Scaffold restructure.
    val context = LocalContext.current
    LaunchedEffect(desktopViewModel) {
        desktopViewModel.userMessages.collect { message ->
            android.widget.Toast.makeText(
                context, message, android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

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
    // Apply the desktop's chosen orientation only when this page is the
    // settled pager destination. HorizontalPager composes adjacent pages
    // during a swipe gesture; without an `isActive` gate, briefly drifting
    // through Desktop in the pager (e.g. a long swipe across screens, or a
    // user with Desktop adjacent to where they're swiping) would fire this
    // LaunchedEffect on entry, snap the activity to landscape, and the
    // DisposableEffect.onDispose would snap it back to UNSPECIFIED — a
    // visible orientation flash with no Desktop interaction at all.
    LaunchedEffect(desktopOrientation, activity, isActive) {
        activity?.requestedOrientation = if (isActive) {
            desktopOrientation
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    androidx.compose.runtime.DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Manage toggle: a single TopAppBar action that flips between
    // Sessions view (today's tabs + frame) and Manage view (distro
    // picker + DE install/start/stop rows, moved from the old
    // Connections topbar dialog in issue #162 Phase 3c).
    //
    // User override: a non-null userManageOverride takes precedence;
    // when null, default to Manage when no sessions are running so the
    // empty state lands on something actionable, otherwise Sessions.
    var userManageOverride by remember { mutableStateOf<Boolean?>(null) }
    val showManage = userManageOverride ?: tabs.isEmpty()

    // When the Sessions/monitor view is shown, connect a viewer to any
    // running desktop that doesn't have one — e.g. a desktop started via
    // the MCP start_desktop tool (backend-only, no UI viewer). Without
    // this, the monitor view sits empty even though the compositor +
    // wayvnc are up. addVncSession dedupes, so an already-open viewer is
    // untouched.
    LaunchedEffect(showManage) {
        if (!showManage) desktopViewModel.connectRunningDesktopViewers()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact action row at the very top — surfaces the Manage
        // toggle without consuming the full TopAppBar height. Stays
        // visible while a session is running so the user can switch
        // to Manage at any time. Hidden in fullscreen (#212) so the
        // viewer goes edge-to-edge on small screens; the viewer's
        // floating overlay still exits fullscreen.
        if (!fullscreen) {
            Surface(tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { userManageOverride = !showManage },
                    ) {
                        Icon(
                            imageVector = if (showManage) Icons.Filled.DesktopWindows else Icons.Filled.Tune,
                            contentDescription = if (showManage) stringResource(R.string.app_desktop_sessions_cd) else stringResource(R.string.app_desktop_manage_cd),
                        )
                    }
                }
            }
        }

        if (showManage) {
            DesktopManagerScreen(viewModel = desktopViewModel)
            return@Column
        }

        if (tabs.isEmpty()) {
            DesktopEmptyState()
        } else {
            // Tab bar — visible when tabs exist, hidden in fullscreen (#212).
            val isConnected by (activeTab?.connected ?: MutableStateFlow(false)).collectAsState()
            if (!fullscreen && (tabs.size > 1 || !isConnected)) {
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
                        is DesktopTab.Vnc -> {
                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                            val remoteClip by tab.remoteClipboard.collectAsState()
                            LaunchedEffect(remoteClip) {
                                val text = remoteClip
                                if (!text.isNullOrEmpty()) {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                    tab._remoteClipboard.value = null
                                }
                            }
                            VncSessionContent(
                                connected = tab.connected,
                                frame = tab.frame,
                                error = tab.error,
                                toolbarLayout = toolbarLayout,
                                onTap = { x, y -> desktopViewModel.sendClick(x, y) },
                                onMiddleClick = { x, y -> desktopViewModel.sendClick(x, y, button = 2) },
                                onLongPress = { x, y -> desktopViewModel.sendClick(x, y, button = 3) },
                                onDragStart = { x, y ->
                                    desktopViewModel.sendPointer(x, y)
                                    desktopViewModel.pressButton(1)
                                },
                                onDrag = { x, y -> desktopViewModel.sendPointer(x, y) },
                                onDragEnd = { desktopViewModel.releaseButton(1) },
                                onScrollUp = { desktopViewModel.scrollUp() },
                                onScrollDown = { desktopViewModel.scrollDown() },
                                onPressButton = { btn -> desktopViewModel.pressButton(btn) },
                                onReleaseButton = { btn -> desktopViewModel.releaseButton(btn) },
                                onTypeChar = { ch -> desktopViewModel.typeVncKey(charToKeySym(ch)) },
                                onTypeText = { text -> desktopViewModel.typeVncText(text) },
                                onSendClipboard = { text -> desktopViewModel.sendVncClipboardText(text) },
                                onKeyDown = { keySym -> desktopViewModel.sendVncKey(keySym, true) },
                                onKeyUp = { keySym -> desktopViewModel.sendVncKey(keySym, false) },
                                onDisconnect = { desktopViewModel.closeTab(tab.id) },
                                onFullscreenChanged = onFullscreenChanged,
                                cursor = tab.cursor,
                                pointerPos = tab.pointerPos,
                                inputMode = inputMode,
                                onSetInputMode = onSetInputMode,
                                touchpadSensitivity = touchpadSensitivity,
                                onSetTouchpadSensitivity = onSetTouchpadSensitivity,
                                bandwidthSuggestion = tab.bandwidthSuggestion,
                                onAcceptBandwidthSuggestion = { desktopViewModel.acceptBandwidthSuggestion(tab.id) },
                                onDismissBandwidthSuggestion = { desktopViewModel.dismissBandwidthSuggestion(tab.id) },
                                currentOrientation = desktopOrientation,
                                onCycleOrientation = { desktopViewModel.cycleDesktopOrientation() },
                                onRetry = { desktopViewModel.retryTab(tab.id) },
                                onClose = { desktopViewModel.closeTab(tab.id) },
                            )
                        }

                        is DesktopTab.Rdp -> RdpSessionContent(
                            connected = tab.connected,
                            frame = tab.frame,
                            frameSeq = tab.frameSeq,
                            error = tab.error,
                            toolbarLayout = toolbarLayout,
                            chipAnchor = rdpChipAnchor,
                            onChipAnchorChange = onRdpChipAnchorChange,
                            onTap = { x, y -> desktopViewModel.sendClick(x, y) },
                            onMiddleClick = { x, y -> desktopViewModel.sendClick(x, y, button = 2) },
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
                            cursor = tab.cursor,
                            pointerPos = tab.pointerPos,
                            inputMode = inputMode,
                            onSetInputMode = onSetInputMode,
                            touchpadSensitivity = touchpadSensitivity,
                            onSetTouchpadSensitivity = onSetTouchpadSensitivity,
                            currentOrientation = desktopOrientation,
                            onCycleOrientation = { desktopViewModel.cycleDesktopOrientation() },
                            onRetry = { desktopViewModel.retryTab(tab.id) },
                        )

                        // SPICE reuses the RDP content renderer: a bitmap canvas
                        // + PC set-1 scancode keyboard, which SPICE also uses.
                        is DesktopTab.Spice -> RdpSessionContent(
                            connected = tab.connected,
                            frame = tab.frame,
                            error = tab.error,
                            toolbarLayout = toolbarLayout,
                            chipAnchor = rdpChipAnchor,
                            onChipAnchorChange = onRdpChipAnchorChange,
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
                                    sendKey = { sc, pressed -> desktopViewModel.sendSpiceKey(sc, pressed) },
                                    sendUnicode = { /* SPICE has no unicode-key verb */ },
                                )
                            },
                            onKeyDown = { scancode -> desktopViewModel.sendSpiceKey(scancode, true) },
                            onKeyUp = { scancode -> desktopViewModel.sendSpiceKey(scancode, false) },
                            onDisconnect = { desktopViewModel.closeTab(tab.id) },
                            onFullscreenChanged = onFullscreenChanged,
                            cursor = tab.cursor,
                            pointerPos = tab.pointerPos,
                            inputMode = inputMode,
                            onSetInputMode = onSetInputMode,
                            touchpadSensitivity = touchpadSensitivity,
                            onSetTouchpadSensitivity = onSetTouchpadSensitivity,
                            currentOrientation = desktopOrientation,
                            onCycleOrientation = { desktopViewModel.cycleDesktopOrientation() },
                            onRetry = { desktopViewModel.retryTab(tab.id) },
                            // 2-finger pinch = viewport zoom, drag = pan/scroll
                            // (toolbar toggle), tap = middle click (uniform with
                            // RDP/VNC, #286).
                            onMiddleClick = { x, y -> desktopViewModel.sendClick(x, y, button = 2) },
                        )

                        is DesktopTab.Wayland -> WaylandDesktopView(
                            modifier = Modifier.fillMaxSize(),
                            toolbarLayout = toolbarLayout,
                            navBlockMode = navBlockMode,
                            uniformGrid = toolbarUniformGrid,
                            keyScale = toolbarKeyScale,
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
        Text(stringResource(R.string.desktop_screen_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.app_desktop_empty_hint),
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
                                stringResource(R.string.app_desktop_tab_label, tab.protocol, tab.label),
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
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.app_desktop_move_tab_left_cd), modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { onMoveTab(index, 1); showTabMenu = false },
                                enabled = index < tabs.size - 1,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.app_desktop_move_tab_right_cd), modifier = Modifier.size(18.dp))
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
                            Text(stringResource(R.string.common_close))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
