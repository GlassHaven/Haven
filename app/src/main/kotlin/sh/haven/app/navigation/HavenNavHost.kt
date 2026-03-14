package sh.haven.app.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.feature.connections.ConnectionsScreen
import sh.haven.feature.keys.KeysScreen
import sh.haven.feature.settings.SettingsScreen
import sh.haven.feature.sftp.SftpScreen
import sh.haven.feature.terminal.TerminalScreen
import sh.haven.feature.vnc.VncScreen
import kotlin.math.abs

@Composable
fun HavenNavHost(
    preferencesRepository: UserPreferencesRepository,
) {
    val screens = Screen.entries
    val pagerState = rememberPagerState { screens.size }
    val coroutineScope = rememberCoroutineScope()
    val terminalFontSize by preferencesRepository.terminalFontSize
        .collectAsState(initial = UserPreferencesRepository.DEFAULT_FONT_SIZE)
    val toolbarLayout by preferencesRepository.toolbarLayout
        .collectAsState(initial = sh.haven.core.data.preferences.ToolbarLayout.DEFAULT)

    // Profile ID to focus when navigating to terminal
    var pendingTerminalProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Profile ID for opening a new session (new tab) on an existing connection
    var pendingNewSessionProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // VNC auto-connect params from terminal
    var pendingVncHost by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVncPort by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingVncPassword by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVncSshForward by rememberSaveable { mutableStateOf(false) }
    var pendingVncSshSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    // Disable pager swipe while terminal text selection is active
    var terminalSelectionActive by remember { mutableStateOf(false) }

    // VNC fullscreen hides bottom nav and system bars
    var vncFullscreen by remember { mutableStateOf(false) }

    Scaffold(
        // Exclude IME from Scaffold's contentWindowInsets so that imePadding()
        // on the HorizontalPager can observe and apply keyboard insets directly.
        // Without this, Scaffold consumes IME insets and the terminal doesn't
        // resize when the keyboard opens/closes.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
        bottomBar = {
            if (!vncFullscreen) {
                NavigationBar {
                    screens.forEachIndexed { index, screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            // VNC canvas consumes touch at Initial pass; toolbar/keyboard areas pass through.
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) { page ->
            when (screens[page]) {
                Screen.Connections -> ConnectionsScreen(
                    onNavigateToTerminal = { profileId ->
                        pendingTerminalProfileId = profileId
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(Screen.Terminal.ordinal)
                        }
                    },
                    onNavigateToNewSession = { profileId ->
                        pendingNewSessionProfileId = profileId
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(Screen.Terminal.ordinal)
                        }
                    },
                    moshEnabled = true,
                )
                Screen.Terminal -> {
                    TerminalScreen(
                        navigateToProfileId = pendingTerminalProfileId,
                        newSessionProfileId = pendingNewSessionProfileId,
                        isActive = pagerState.settledPage == Screen.Terminal.ordinal,
                        fontSize = terminalFontSize,
                        toolbarLayout = toolbarLayout,
                        onNavigateToConnections = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(Screen.Connections.ordinal)
                            }
                        },
                        onNavigateToVnc = { host, port, password, sshForward, sshSessionId ->
                            pendingVncHost = host
                            pendingVncPort = port
                            pendingVncPassword = password
                            pendingVncSshForward = sshForward
                            pendingVncSshSessionId = sshSessionId
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(Screen.Vnc.ordinal)
                            }
                        },
                        onSelectionActiveChanged = { terminalSelectionActive = it },
                        // Terminal composable consumes touch events, blocking pager swipe.
                        // Intercept horizontal drags at Initial pass and forward to pager.
                        // Disabled while text selection is active so drag extends the selection.
                        terminalModifier = if (terminalSelectionActive) Modifier
                            else Modifier.pagerSwipeOverride(pagerState, coroutineScope),
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
                Screen.Vnc -> VncScreen(
                    isActive = pagerState.settledPage == Screen.Vnc.ordinal,
                    pendingHost = pendingVncHost,
                    pendingPort = pendingVncPort,
                    pendingPassword = pendingVncPassword,
                    pendingSshForward = pendingVncSshForward,
                    pendingSshSessionId = pendingVncSshSessionId,
                    toolbarLayout = toolbarLayout,
                    onPendingConsumed = {
                        pendingVncHost = null
                        pendingVncPort = null
                        pendingVncPassword = null
                        pendingVncSshForward = false
                        pendingVncSshSessionId = null
                    },
                    onFullscreenChanged = { vncFullscreen = it },
                )
                Screen.Sftp -> SftpScreen()
                Screen.Keys -> KeysScreen()
                Screen.Settings -> SettingsScreen()
            }
        }
    }
}

/**
 * Intercepts horizontal drag gestures at [PointerEventPass.Initial] (before children)
 * and forwards them to the [PagerState]. Vertical drags and taps pass through to children.
 */
private fun Modifier.pagerSwipeOverride(
    pagerState: PagerState,
    scope: CoroutineScope,
): Modifier = pointerInput(pagerState) {
    val touchSlopPx = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var totalX = 0f
        var totalY = 0f
        var decided = false
        var isHorizontal = false

        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: break
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y

            if (!decided && totalX * totalX + totalY * totalY > touchSlopPx * touchSlopPx) {
                decided = true
                isHorizontal = abs(totalX) > abs(totalY)
            }

            if (isHorizontal) {
                change.consume()
                pagerState.dispatchRawDelta(-delta.x)
            }
        } while (change.pressed)

        if (isHorizontal) {
            val threshold = size.width / 4
            val target = when {
                totalX < -threshold -> pagerState.currentPage + 1
                totalX > threshold -> pagerState.currentPage - 1
                else -> pagerState.currentPage
            }.coerceIn(0, pagerState.pageCount - 1)
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }
}
