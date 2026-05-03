package sh.haven.app.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import androidx.activity.compose.LocalActivity
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.app.desktop.DesktopViewModel
import sh.haven.feature.connections.ConnectionsScreen
import sh.haven.feature.keys.KeysScreen
import sh.haven.feature.settings.SettingsScreen
import androidx.compose.ui.unit.dp
import sh.haven.feature.sftp.SftpScreen
import sh.haven.feature.sftp.SftpViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.feature.terminal.TerminalScreen
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun HavenNavHost(
    preferencesRepository: UserPreferencesRepository,
    connectionRepository: ConnectionRepository,
    agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
) {
    // Desktop multi-session ViewModel — hoisted to nav scope so it survives tab switches
    val desktopViewModel: DesktopViewModel = hiltViewModel()
    val desktopTabs by desktopViewModel.tabs.collectAsState()

    // Native Wayland desktop — poll compositor state reactively
    var waylandRunning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            waylandRunning = sh.haven.core.wayland.WaylandBridge.nativeIsRunning()
            kotlinx.coroutines.delay(500)
        }
    }

    // Auto-hide tabs for protocols with no configured connections
    val connections by connectionRepository.observeAll()
        .collectAsState(initial = emptyList())
    val hasDesktopConnections = waylandRunning || desktopTabs.isNotEmpty() ||
        connections.any { it.isVnc || it.isRdp || it.isLocal }
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

    // Debug navigation: scroll pager when DebugReceiver (debug builds only) emits a route
    LaunchedEffect(Unit) {
        DebugNavEvents.requests.collect { route ->
            val target = screens.indexOfFirst { it.route == route }
            if (target >= 0) pagerState.animateScrollToPage(target)
        }
    }

    // Agent UI commands — when an MCP tool posts a navigation verb, switch
    // the pager to the right tab so the user lands where the agent asked.
    // The matching feature ViewModel collects the same bus and adjusts its
    // internal state in parallel.
    LaunchedEffect(agentUiCommandBus, screens) {
        agentUiCommandBus.commands.collect { command ->
            when (command) {
                is sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath -> {
                    val target = screens.indexOfFirst { it.route == sh.haven.core.ui.navigation.Screen.Sftp.route }
                    if (target >= 0) pagerState.animateScrollToPage(target)
                }
            }
        }
    }

    val terminalFontSize by preferencesRepository.terminalFontSize
        .collectAsState(initial = UserPreferencesRepository.DEFAULT_FONT_SIZE)
    // User-picked Nerd Font / custom TTF for the terminal (#123).
    // Null = fall back to bundled Hack Regular.
    val terminalFontPath by preferencesRepository.terminalFontPath
        .collectAsState(initial = null)
    val toolbarLayout by preferencesRepository.toolbarLayout
        .collectAsState(initial = sh.haven.core.data.preferences.ToolbarLayout.DEFAULT)
    val navBlockMode by preferencesRepository.navBlockMode
        .collectAsState(initial = sh.haven.core.data.preferences.NavBlockMode.ALIGNED)
    val showSearchButton by preferencesRepository.showSearchButton
        .collectAsState(initial = false)
    val showCopyOutputButton by preferencesRepository.showCopyOutputButton
        .collectAsState(initial = false)
    val keepScreenOnInTerminal by preferencesRepository.keepScreenOnInTerminal
        .collectAsState(initial = false)
    val mouseInputEnabled by preferencesRepository.mouseInputEnabled
        .collectAsState(initial = true)
    val mouseDragSelects by preferencesRepository.mouseDragSelects
        .collectAsState(initial = true)
    val terminalRightClick by preferencesRepository.terminalRightClick
        .collectAsState(initial = false)
    val allowStandardKeyboard by preferencesRepository.allowStandardKeyboard
        .collectAsState(initial = false)
    val rawKeyboardMode by preferencesRepository.rawKeyboardMode
        .collectAsState(initial = false)
    // Custom keyboard mode + per-flag toggles (#115 follow-up).
    // Build a non-null ImeFlagSet only when Custom mode is on; null
    // when off, so TerminalScreen falls through to the preset logic.
    val keyboardCustomMode by preferencesRepository.keyboardCustomMode
        .collectAsState(initial = false)
    val imeFlagNoSuggestions by preferencesRepository.imeFlagNoSuggestions
        .collectAsState(initial = true)
    val imeFlagVisiblePassword by preferencesRepository.imeFlagVisiblePassword
        .collectAsState(initial = true)
    val imeFlagAutoCorrect by preferencesRepository.imeFlagAutoCorrect
        .collectAsState(initial = false)
    val imeFlagFullEditor by preferencesRepository.imeFlagFullEditor
        .collectAsState(initial = false)
    val imeFlagNoExtractUi by preferencesRepository.imeFlagNoExtractUi
        .collectAsState(initial = true)
    val imeFlagNoPersonalizedLearning by preferencesRepository.imeFlagNoPersonalizedLearning
        .collectAsState(initial = true)
    val customKeyboardFlags = if (keyboardCustomMode) {
        sh.haven.core.terminal.ImeFlagSet(
            noSuggestions = imeFlagNoSuggestions,
            visiblePassword = imeFlagVisiblePassword,
            autoCorrect = imeFlagAutoCorrect,
            fullEditor = imeFlagFullEditor,
            noExtractUi = imeFlagNoExtractUi,
            noPersonalizedLearning = imeFlagNoPersonalizedLearning,
        )
    } else null
    val interceptCtrlShiftV by preferencesRepository.interceptCtrlShiftV
        .collectAsState(initial = true)
    val showTerminalTabBar by preferencesRepository.showTerminalTabBar
        .collectAsState(initial = true)
    val desktopInputMode by preferencesRepository.desktopInputMode
        .collectAsState(initial = "DIRECT")

    // Profile ID to focus when navigating to terminal
    var pendingTerminalProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Profile ID for opening a new session (new tab) on an existing connection
    var pendingNewSessionProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // SMB auto-connect params
    var pendingSmbProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Rclone auto-connect params
    var pendingRcloneProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Disable pager swipe while terminal text selection is active
    var terminalSelectionActive by remember { mutableStateOf(false) }
    var terminalReorderMode by remember { mutableStateOf(false) }
    var openToolbarConfig by remember { mutableStateOf(false) }

    // Desktop fullscreen hides bottom nav and system bars
    var desktopFullscreen by remember { mutableStateOf(false) }
    // Disable pager swipe when VNC/RDP is connected (pinch-to-zoom conflicts)
    var desktopConnected by remember { mutableStateOf(false) }
    // Disable pager swipe when SFTP text editor or image tools are open
    var sftpEditorOpen by remember { mutableStateOf(false) }
    var sftpImageToolOpen by remember { mutableStateOf(false) }

    // Nav bar drag-to-reorder state
    var navDragIndex by remember { mutableIntStateOf(-1) }
    var navDragOffset by remember { mutableFloatStateOf(0f) }
    val navItemLefts = remember { mutableStateMapOf<Int, Float>() }
    val navItemWidths = remember { mutableStateMapOf<Int, Float>() }
    val haptic = LocalHapticFeedback.current

    // Use WindowSizeClass (width >= Medium, i.e. >= 600dp) as the Material3-recommended
    // signal for switching to a side rail.  This avoids triggering the rail on narrow
    // landscape phones where it would eat more width than it saves, while also opting
    // wide-enough portrait tablets into the rail.
    val activity = LocalActivity.current ?: return
    val windowSizeClass = calculateWindowSizeClass(activity)
    val useSideNavigation =
        windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

    val pagerContent: @Composable (Modifier) -> Unit = { modifier ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !desktopFullscreen && !desktopConnected && !terminalSelectionActive && !sftpEditorOpen && !sftpImageToolOpen,
            modifier = modifier,
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
                    onNavigateToVnc = { host, port, password, username, sshForward, sshSessionId, profileId, colorDepth ->
                        desktopViewModel.addVncSession(
                            host, port, password, username,
                            sshForward = sshForward,
                            sshSessionId = sshSessionId,
                            profileId = profileId,
                            colorDepth = colorDepth,
                        )
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Desktop))
                        }
                    },
                    onNavigateToRdp = { host, port, username, password, domain, sshForward, sshProfileId, sshSessionId, profileId, useNla, colorDepth ->
                        desktopViewModel.addRdpSession(
                            host, port, username, password, domain,
                            sshForward, sshSessionId, sshProfileId, profileId,
                            useNla = useNla,
                            colorDepth = colorDepth,
                        )
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
                        desktopViewModel.addWaylandTab()
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Desktop))
                        }
                    },
                    onNavigateToConnections = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Connections))
                        }
                    },
                    onNavigateToAgentActivity = {
                        // No deep-link to AgentActivityScreen exists
                        // (it's a state flag inside SettingsScreen);
                        // jumping to Settings is one tap away from the
                        // audit view, which is good enough for a
                        // one-bit indicator.
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pageOf(Screen.Settings))
                        }
                    },
                )
                Screen.Terminal -> {
                    TerminalScreen(
                        navigateToProfileId = pendingTerminalProfileId,
                        newSessionProfileId = pendingNewSessionProfileId,
                        isActive = pagerState.settledPage == pageOf(Screen.Terminal),
                        fontSize = terminalFontSize,
                        terminalFontPath = terminalFontPath,
                        toolbarLayout = toolbarLayout,
                        navBlockMode = navBlockMode,
                        showSearchButton = showSearchButton,
                        showCopyOutputButton = showCopyOutputButton,
                        keepScreenOnInTerminal = keepScreenOnInTerminal,
                        mouseInputEnabled = mouseInputEnabled,
                        mouseDragSelects = mouseDragSelects,
                        terminalRightClick = terminalRightClick,
                        allowStandardKeyboard = allowStandardKeyboard,
                        onToggleStandardKeyboard = {
                            coroutineScope.launch {
                                preferencesRepository.setAllowStandardKeyboard(!allowStandardKeyboard)
                            }
                        },
                        rawKeyboardMode = rawKeyboardMode,
                        customKeyboardFlags = customKeyboardFlags,
                        onToggleRawKeyboard = {
                            coroutineScope.launch {
                                preferencesRepository.setRawKeyboardMode(!rawKeyboardMode)
                            }
                        },
                        interceptCtrlShiftV = interceptCtrlShiftV,
                        showTabBar = showTerminalTabBar,
                        onNavigateToConnections = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pageOf(Screen.Connections))
                            }
                        },
                        onNavigateToVnc = { host, port, username, password, sshForward, sshSessionId, colorDepth ->
                            desktopViewModel.addVncSession(
                                host, port, password, username,
                                sshForward = sshForward,
                                sshSessionId = sshSessionId,
                                colorDepth = colorDepth,
                            )
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
                    // Clear pending IDs after the terminal has had a chance to consume them.
                    // Use a small delay to avoid cancelling the LaunchedEffect in TerminalScreen
                    // that calls selectTabByProfileId (which may need up to 5s to find the tab).
                    LaunchedEffect(pendingTerminalProfileId) {
                        if (pendingTerminalProfileId != null) {
                            kotlinx.coroutines.delay(6000)
                            pendingTerminalProfileId = null
                        }
                    }
                    LaunchedEffect(pendingNewSessionProfileId) {
                        if (pendingNewSessionProfileId != null) {
                            kotlinx.coroutines.delay(6000)
                            pendingNewSessionProfileId = null
                        }
                    }
                }
                Screen.Desktop -> {
                    // Auto-add Wayland tab when compositor is running
                    LaunchedEffect(waylandRunning) {
                        if (waylandRunning) desktopViewModel.addWaylandTab()
                        else desktopViewModel.removeWaylandTab()
                    }
                    DesktopScreen(
                        desktopViewModel = desktopViewModel,
                        toolbarLayout = toolbarLayout,
                        navBlockMode = navBlockMode,
                        inputMode = desktopInputMode,
                        isActive = pagerState.settledPage == pageOf(Screen.Desktop),
                        onFullscreenChanged = { desktopFullscreen = it },
                        onConnectedChanged = { desktopConnected = it },
                    )
                }
                Screen.Sftp -> {
                    // Share one SftpViewModel instance between the flick
                    // handler (which needs to read currentPath + call
                    // navigateUp) and the SftpScreen itself. hiltViewModel()
                    // returns the same instance within a composition scope.
                    val sftpViewModel: SftpViewModel = hiltViewModel()
                    val sftpCurrentPath by sftpViewModel.currentPath.collectAsState()
                    val sftpSelectedPaths by sftpViewModel.selectedPaths.collectAsState()
                    val sftpModifier = Modifier
                        .fillMaxSize()
                        .pagerSwipeOverride(
                            pagerState = pagerState,
                            scope = coroutineScope,
                            isSelectionActive = { sftpSelectedPaths.isNotEmpty() || sftpEditorOpen || sftpImageToolOpen },
                            onFlick = { rightward ->
                                // Fast rightward flick inside a subdirectory
                                // navigates up one level instead of switching
                                // tabs (#89).
                                val path = sftpCurrentPath
                                if (rightward && path != "/" && path.isNotEmpty()) {
                                    sftpViewModel.navigateUp()
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                    SftpScreen(
                        pendingSmbProfileId = pendingSmbProfileId,
                        pendingRcloneProfileId = pendingRcloneProfileId,
                        onEditorOpenChanged = { sftpEditorOpen = it },
                        onImageToolOpenChanged = { sftpImageToolOpen = it },
                        sftpModifier = sftpModifier,
                        viewModel = sftpViewModel,
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
                    SettingsScreen(
                        openToolbarConfig = openToolbarConfig,
                        onToolbarConfigConsumed = { openToolbarConfig = false },
                    )
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
        bottomBar = {
            if (!desktopFullscreen && !useSideNavigation) {
                NavigationBar {
                    val currentScreen = screens.getOrNull(pagerState.currentPage)
                    navScreens.forEachIndexed { index, screen ->
                        val isDragged = index == navDragIndex
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                            label = {
                                // maxLines = 1 + ellipsis guards against any locale whose
                                // nav label is too long for the tab slot on narrow devices
                                // (e.g. Samsung S23 portrait with 5 tabs). Previously the
                                // label could wrap to two lines and distort the whole bar
                                // height — see #78.
                                Text(
                                    text = stringResource(screen.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    softWrap = false,
                                )
                            },
                            selected = screen == currentScreen,
                            onClick = {
                                if (navDragIndex < 0) {
                                    val pageIndex = screens.indexOf(screen)
                                    if (pageIndex >= 0) coroutineScope.launch {
                                        // Use instant scroll to avoid double-jump through
                                        // intermediate pages during animated scroll
                                        pagerState.scrollToPage(pageIndex)
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
                                                preferencesRepository.setScreenOrder(navScreens.map { it.route })
                                            }
                                        },
                                        onDragCancel = {
                                            navDragIndex = -1
                                            navDragOffset = 0f
                                            coroutineScope.launch {
                                                preferencesRepository.setScreenOrder(navScreens.map { it.route })
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (useSideNavigation) {
            Row(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                if (!desktopFullscreen) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        val currentScreen = screens.getOrNull(pagerState.currentPage)
                        navScreens.forEach { screen ->
                            val pageIndex = screens.indexOf(screen)
                            if (pageIndex >= 0) {
                                NavigationRailItem(
                                    icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                                    label = {
                                        Text(
                                            text = stringResource(screen.labelRes),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            softWrap = false,
                                        )
                                    },
                                    alwaysShowLabel = false,
                                    selected = screen == currentScreen,
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.scrollToPage(pageIndex)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                pagerContent(Modifier.weight(1f))
            }
        } else {
            pagerContent(
                Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            )
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
    /**
     * Optional per-page flick handler. Called at gesture release if the
     * horizontal drag qualifies as a fast flick (velocity past the
     * internal threshold). Return true if the flick was handled
     * (e.g. SFTP navigated up one directory): the pager will snap back
     * to its current page instead of page-switching.
     *
     * `rightward = true` when totalX > 0 (drag went left→right).
     */
    onFlick: (rightward: Boolean) -> Boolean = { false },
): Modifier = pointerInput(pagerState) {
    val touchSlopPx = viewConfiguration.touchSlop
    val flickVelocityPx = with(density) { 200.dp.toPx() }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var totalX = 0f
        var totalY = 0f
        var decided = false
        var isHorizontal = false

        // Anchor the gesture to the page we started on. During the drag we
        // call dispatchRawDelta, which moves the pager progressively and
        // may tick pagerState.currentPage over to the next page before the
        // finger lifts. Computing the release target from currentPage would
        // then cause a two-page jump. Use startPage as the stable anchor.
        val startPage = pagerState.currentPage
        var selectionInterrupted = false
        val velocityTracker = VelocityTracker().also {
            it.addPosition(down.uptimeMillis, down.position)
        }

        var change: PointerInputChange? = down
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            change = event.changes.firstOrNull() ?: break
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y
            velocityTracker.addPosition(change.uptimeMillis, change.position)

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
        } while (change?.pressed == true)

        if (isHorizontal && !selectionInterrupted && !isSelectionActive()) {
            // Release decision — fast flick first (per-page hook gets a
            // chance to claim it), then fall back to standard page-threshold
            // behaviour.
            val releaseVelocity = velocityTracker.calculateVelocity().x
            val isFlick = abs(releaseVelocity) >= flickVelocityPx
            val flickHandled = if (isFlick) onFlick(totalX > 0) else false

            if (flickHandled) {
                // Caller took over — snap pager back to the page we
                // started on (we forwarded some delta to it during the
                // drag, so currentPage may already have ticked over).
                scope.launch { pagerState.animateScrollToPage(startPage) }
            } else {
                val threshold = size.width / 4
                val target = when {
                    totalX < -threshold -> startPage + 1
                    totalX > threshold -> startPage - 1
                    else -> startPage
                }.coerceIn(0, pagerState.pageCount - 1)
                scope.launch { pagerState.animateScrollToPage(target) }
            }
        } else if (selectionInterrupted) {
            // Ensure pager settles on the page we started on
            scope.launch { pagerState.animateScrollToPage(startPage) }
        }
    }
}
