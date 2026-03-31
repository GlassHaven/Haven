package sh.haven.app.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.feature.connections.ConnectionsScreen
import sh.haven.feature.keys.KeysScreen
import sh.haven.feature.settings.SettingsScreen
import sh.haven.feature.sftp.SftpScreen
import sh.haven.feature.terminal.TerminalScreen
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HavenNavHost(
    preferencesRepository: UserPreferencesRepository,
    connectionRepository: ConnectionRepository,
) {
    // Native Wayland desktop — poll compositor state reactively
    var pendingWaylandDesktop by rememberSaveable { mutableStateOf(false) }
    var waylandRunning by remember { mutableStateOf(false) }
    LaunchedEffect(pendingWaylandDesktop) {
        while (true) {
            waylandRunning = pendingWaylandDesktop ||
                sh.haven.core.wayland.WaylandBridge.nativeIsRunning()
            kotlinx.coroutines.delay(500)
        }
    }

    // Auto-hide tabs for protocols with no configured connections
    val connections by connectionRepository.observeAll()
        .collectAsState(initial = emptyList())
    val hasDesktopConnections = waylandRunning || connections.any { it.isVnc || it.isRdp || it.isLocal }
    val screenOrderPref by preferencesRepository.screenOrder
        .collectAsState(initial = emptyList())
    val screens = remember(screenOrderPref, hasDesktopConnections) {
        val ordered = if (screenOrderPref.isNotEmpty()) {
            val byRoute = screenOrderPref.mapNotNull { route ->
                Screen.entries.find { it.route == route }
            }
            val missing = Screen.entries.filter { it !in byRoute }
            byRoute + missing
        } else {
            Screen.entries.toList()
        }
        ordered.filter { screen ->
            when (screen) {
                Screen.Desktop -> hasDesktopConnections
                else -> true
            }
        }
    }
    // Separate mutable list for nav bar visual order during drag (pager untouched)
    val navScreens = remember { mutableStateListOf<Screen>() }
    LaunchedEffect(screens) {
        if (navScreens.toList() != screens) {
            navScreens.clear()
            navScreens.addAll(screens)
        }
    }
    val pagerState = rememberPagerState { screens.size }
    fun pageOf(screen: Screen): Int = screens.indexOf(screen).coerceAtLeast(0)
    val coroutineScope = rememberCoroutineScope()
    val terminalFontSize by preferencesRepository.terminalFontSize
        .collectAsState(initial = UserPreferencesRepository.DEFAULT_FONT_SIZE)
    val toolbarLayout by preferencesRepository.toolbarLayout
        .collectAsState(initial = sh.haven.core.data.preferences.ToolbarLayout.DEFAULT)
    val navBlockMode by preferencesRepository.navBlockMode
        .collectAsState(initial = sh.haven.core.data.preferences.NavBlockMode.ALIGNED)
    val showSearchButton by preferencesRepository.showSearchButton
        .collectAsState(initial = false)
    val showCopyOutputButton by preferencesRepository.showCopyOutputButton
        .collectAsState(initial = false)
    val mouseInputEnabled by preferencesRepository.mouseInputEnabled
        .collectAsState(initial = true)
    val terminalRightClick by preferencesRepository.terminalRightClick
        .collectAsState(initial = false)

    // Profile ID to focus when navigating to terminal
    var pendingTerminalProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Profile ID for opening a new session (new tab) on an existing connection
    var pendingNewSessionProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // VNC auto-connect params
    var pendingVncHost by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVncPort by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingVncPassword by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVncSshForward by rememberSaveable { mutableStateOf(false) }
    var pendingVncSshSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    // SMB auto-connect params
    var pendingSmbProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Rclone auto-connect params
    var pendingRcloneProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // RDP auto-connect params
    var pendingRdpHost by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRdpPort by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingRdpUsername by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRdpPassword by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRdpDomain by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRdpSshForward by rememberSaveable { mutableStateOf(false) }
    var pendingRdpSshSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRdpSshProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRdpProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Disable pager swipe while terminal text selection is active
    var terminalSelectionActive by remember { mutableStateOf(false) }
    var terminalReorderMode by remember { mutableStateOf(false) }
    var openToolbarConfig by remember { mutableStateOf(false) }

    // Desktop fullscreen hides bottom nav and system bars
    var desktopFullscreen by remember { mutableStateOf(false) }
    // Disable pager swipe when VNC/RDP is connected (pinch-to-zoom conflicts)
    var desktopConnected by remember { mutableStateOf(false) }

    // Nav bar drag-to-reorder state
    var navDragIndex by remember { mutableIntStateOf(-1) }
    var navDragOffset by remember { mutableFloatStateOf(0f) }
    val navItemLefts = remember { mutableStateMapOf<Int, Float>() }
    val navItemWidths = remember { mutableStateMapOf<Int, Float>() }
    val haptic = LocalHapticFeedback.current

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
        bottomBar = {
            if (!desktopFullscreen) {
                NavigationBar {
                    val currentScreen = screens.getOrNull(pagerState.currentPage)
                    navScreens.forEachIndexed { index, screen ->
                        val isDragged = index == navDragIndex
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = screen == currentScreen,
                            onClick = {
                                if (navDragIndex < 0) {
                                    val pageIndex = screens.indexOf(screen)
                                    if (pageIndex >= 0) coroutineScope.launch {
                                        pagerState.animateScrollToPage(pageIndex)
                                    }
                                }
                            },
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    navItemLefts[index] = coords.positionInParent().x
                                    navItemWidths[index] = coords.size.width.toFloat()
                                }
                                .then(
                                    if (isDragged) {
                                        Modifier
                                            .zIndex(1f)
                                            .offset { IntOffset(navDragOffset.roundToInt(), 0) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            navDragIndex = index
                                            navDragOffset = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, offset ->
                                            change.consume()
                                            navDragOffset += offset.x
                                            val di = navDragIndex
                                            if (di < 0) return@detectDragGesturesAfterLongPress
                                            val myLeft = navItemLefts[di] ?: return@detectDragGesturesAfterLongPress
                                            val myW = navItemWidths[di] ?: return@detectDragGesturesAfterLongPress
                                            val myCenter = myLeft + myW / 2 + navDragOffset
                                            // Swap right
                                            if (di < navScreens.size - 1) {
                                                val nextLeft = navItemLefts[di + 1] ?: 0f
                                                val nextW = navItemWidths[di + 1] ?: 0f
                                                if (myCenter > nextLeft + nextW / 2) {
                                                    val item = navScreens.removeAt(di)
                                                    navScreens.add(di + 1, item)
                                                    navDragOffset -= nextW
                                                    navDragIndex = di + 1
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                            // Swap left
                                            if (di > 0) {
                                                val prevLeft = navItemLefts[di - 1] ?: 0f
                                                val prevW = navItemWidths[di - 1] ?: 0f
                                                if (myCenter < prevLeft + prevW / 2) {
                                                    val item = navScreens.removeAt(di)
                                                    navScreens.add(di - 1, item)
                                                    navDragOffset += prevW
                                                    navDragIndex = di - 1
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            navDragIndex = -1
                                            navDragOffset = 0f
                                            coroutineScope.launch {
                                                preferencesRepository.setScreenOrder(
                                                    navScreens.map { it.route },
                                                )
                                            }
                                        },
                                        onDragCancel = {
                                            navDragIndex = -1
                                            navDragOffset = 0f
                                            coroutineScope.launch {
                                                preferencesRepository.setScreenOrder(
                                                    navScreens.map { it.route },
                                                )
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val isWaylandDesktopPage = screens.getOrNull(pagerState.currentPage) == Screen.Desktop &&
            (waylandRunning || pendingWaylandDesktop)
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !desktopFullscreen && !desktopConnected && !terminalSelectionActive && !isWaylandDesktopPage,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .then(if (isWaylandDesktopPage) Modifier else Modifier.imePadding()),
        ) { page ->
            when (screens[page]) {
                Screen.Connections -> ConnectionsScreen(
                    onNavigateToTerminal = { profileId ->
                        pendingTerminalProfileId = profileId
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Terminal))
                        }
                    },
                    onNavigateToNewSession = { profileId ->
                        pendingNewSessionProfileId = profileId
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Terminal))
                        }
                    },
                    onNavigateToVnc = { host, port, password ->
                        pendingVncHost = host
                        pendingVncPort = port
                        pendingVncPassword = password
                        pendingVncSshForward = false
                        pendingVncSshSessionId = null
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Desktop))
                        }
                    },
                    onNavigateToRdp = { host, port, username, password, domain, sshForward, sshProfileId, sshSessionId, profileId ->
                        pendingRdpHost = host
                        pendingRdpPort = port
                        pendingRdpUsername = username
                        pendingRdpPassword = password
                        pendingRdpDomain = domain
                        pendingRdpSshForward = sshForward
                        pendingRdpSshProfileId = sshProfileId
                        pendingRdpSshSessionId = sshSessionId
                        pendingRdpProfileId = profileId
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Desktop))
                        }
                    },
                    onNavigateToSmb = { profileId ->
                        pendingSmbProfileId = profileId
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Sftp))
                        }
                    },
                    onNavigateToRclone = { profileId ->
                        pendingRcloneProfileId = profileId
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Sftp))
                        }
                    },
                    onNavigateToWayland = {
                        pendingWaylandDesktop = true
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Desktop))
                        }
                    },
                )
                Screen.Terminal -> {
                    TerminalScreen(
                        navigateToProfileId = pendingTerminalProfileId,
                        newSessionProfileId = pendingNewSessionProfileId,
                        isActive = pagerState.settledPage == pageOf(Screen.Terminal),
                        fontSize = terminalFontSize,
                        toolbarLayout = toolbarLayout,
                        navBlockMode = navBlockMode,
                        showSearchButton = showSearchButton,
                        showCopyOutputButton = showCopyOutputButton,
                        mouseInputEnabled = mouseInputEnabled,
                        terminalRightClick = terminalRightClick,
                        onNavigateToConnections = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pageOf(Screen.Connections))
                            }
                        },
                        onNavigateToVnc = { host, port, password, sshForward, sshSessionId ->
                            pendingVncHost = host
                            pendingVncPort = port
                            pendingVncPassword = password
                            pendingVncSshForward = sshForward
                            pendingVncSshSessionId = sshSessionId
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pageOf(Screen.Desktop))
                            }
                        },
                        onSelectionActiveChanged = { terminalSelectionActive = it },
                        onReorderModeChanged = { terminalReorderMode = it },
                        onToolbarLayoutChanged = { newLayout ->
                            coroutineScope.launch {
                                preferencesRepository.setToolbarLayout(newLayout)
                            }
                        },
                        onOpenToolbarSettings = {
                            openToolbarConfig = true
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pageOf(Screen.Settings))
                            }
                        },
                        terminalModifier = Modifier.pagerSwipeOverride(
                            pagerState, coroutineScope,
                            isSelectionActive = { terminalSelectionActive || terminalReorderMode },
                        ),
                    )
                    LaunchedEffect(pendingTerminalProfileId) {
                        if (pendingTerminalProfileId != null) {
                            pendingTerminalProfileId = null
                        }
                    }
                    LaunchedEffect(pendingNewSessionProfileId) {
                        if (pendingNewSessionProfileId != null) {
                            pendingNewSessionProfileId = null
                        }
                    }
                }
                Screen.Desktop -> if (waylandRunning || pendingWaylandDesktop) {
                    sh.haven.core.wayland.WaylandDesktopView(
                        modifier = Modifier.fillMaxSize(),
                        toolbarLayout = toolbarLayout,
                        navBlockMode = navBlockMode,
                    )
                } else {
                    val consumePending = {
                        pendingVncHost = null
                        pendingVncPort = null
                        pendingVncPassword = null
                        pendingVncSshForward = false
                        pendingVncSshSessionId = null
                        pendingRdpHost = null
                        pendingRdpPort = null
                        pendingRdpUsername = null
                        pendingRdpPassword = null
                        pendingRdpDomain = null
                        pendingRdpSshForward = false
                        pendingRdpSshSessionId = null
                        pendingRdpSshProfileId = null
                        pendingRdpProfileId = null
                    }
                    DesktopScreen(
                        isActive = pagerState.settledPage == pageOf(Screen.Desktop),
                        pendingVncHost = pendingVncHost,
                        pendingVncPort = pendingVncPort,
                        pendingVncPassword = pendingVncPassword,
                        pendingVncSshForward = pendingVncSshForward,
                        pendingVncSshSessionId = pendingVncSshSessionId,
                        pendingRdpHost = pendingRdpHost,
                        pendingRdpPort = pendingRdpPort,
                        pendingRdpUsername = pendingRdpUsername,
                        pendingRdpPassword = pendingRdpPassword,
                        pendingRdpDomain = pendingRdpDomain,
                        pendingRdpSshForward = pendingRdpSshForward,
                        pendingRdpSshSessionId = pendingRdpSshSessionId,
                        pendingRdpSshProfileId = pendingRdpSshProfileId,
                        pendingRdpProfileId = pendingRdpProfileId,
                        toolbarLayout = toolbarLayout,
                        onPendingConsumed = consumePending,
                        onFullscreenChanged = { desktopFullscreen = it },
                        onConnectedChanged = { desktopConnected = it },
                    )
                }
                Screen.Sftp -> {
                    SftpScreen(
                        pendingSmbProfileId = pendingSmbProfileId,
                        pendingRcloneProfileId = pendingRcloneProfileId,
                    )
                    LaunchedEffect(pendingSmbProfileId) {
                        if (pendingSmbProfileId != null) {
                            pendingSmbProfileId = null
                        }
                    }
                    LaunchedEffect(pendingRcloneProfileId) {
                        if (pendingRcloneProfileId != null) {
                            pendingRcloneProfileId = null
                        }
                    }
                }
                Screen.Keys -> KeysScreen()
                Screen.Settings -> {
                    SettingsScreen(openToolbarConfig = openToolbarConfig)
                    if (openToolbarConfig) openToolbarConfig = false
                }
            }
        }
    }
}

/**
 * Intercepts all gestures on [PointerEventPass.Initial] to prevent the
 * HorizontalPager's built-in scroll from stealing the touch. Horizontal
 * drags are forwarded to the [PagerState] programmatically; all other
 * gestures (vertical scroll, selection, hold) are consumed on Initial
 * so the pager never intercepts them — Terminal.kt handles them on Main.
 *
 * When [isSelectionActive] returns true, horizontal forwarding is suppressed
 * so selection drag isn't misinterpreted as a tab swipe.
 */
private fun Modifier.pagerSwipeOverride(
    pagerState: PagerState,
    scope: CoroutineScope,
    isSelectionActive: () -> Boolean = { false },
): Modifier = pointerInput(pagerState) {
    val touchSlopPx = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var totalX = 0f
        var totalY = 0f
        var decided = false
        var isHorizontal = false

        var selectionInterrupted = false

        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: break
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y

            if (!decided && totalX * totalX + totalY * totalY > touchSlopPx * touchSlopPx) {
                decided = true
                // Require 2:1 horizontal:vertical ratio to trigger page swipe,
                // preventing accidental tab switches during vertical scroll (#40.8)
                isHorizontal = abs(totalX) > abs(totalY) * 2
            }

            // Consume horizontal drags on Initial to prevent the pager's built-in
            // scrollable from intercepting — but let vertical events through so
            // the terminal can handle scrollback and mouse input.
            if (decided && isHorizontal && !isSelectionActive()) {
                change.consume()
            }

            // Forward horizontal drags to pager (unless selection active)
            if (isHorizontal && !selectionInterrupted) {
                if (isSelectionActive()) {
                    // Selection activated mid-swipe — snap pager back
                    selectionInterrupted = true
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                } else {
                    pagerState.dispatchRawDelta(-delta.x)
                }
            }
        } while (change.pressed)

        if (isHorizontal && !selectionInterrupted && !isSelectionActive()) {
            val threshold = size.width / 4
            val target = when {
                totalX < -threshold -> pagerState.currentPage + 1
                totalX > threshold -> pagerState.currentPage - 1
                else -> pagerState.currentPage
            }.coerceIn(0, pagerState.pageCount - 1)
            scope.launch { pagerState.animateScrollToPage(target) }
        } else if (selectionInterrupted) {
            // Ensure pager settles on current page
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
        }
    }
}
