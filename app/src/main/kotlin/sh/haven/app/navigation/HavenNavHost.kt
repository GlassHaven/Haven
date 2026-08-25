package sh.haven.app.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import androidx.activity.compose.LocalActivity
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.app.desktop.DesktopViewModel
import sh.haven.feature.connections.ConnectionsScreen
import sh.haven.feature.connections.ConnectionsViewModel
import sh.haven.feature.keys.KeysScreen
import sh.haven.feature.mail.MailScreen
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
    sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    userMessageBus: sh.haven.core.data.message.UserMessageBus,
    mailSessionManager: sh.haven.core.mail.MailSessionManager,
    mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder,
) {
    // Desktop multi-session ViewModel — hoisted to nav scope so it survives tab switches
    val desktopViewModel: DesktopViewModel = hiltViewModel()

    // Agent activity audit overlay — opened by the AgentActiveChip on the
    // Connections top bar, dismissed via the screen's back arrow. Lifted
    // here so the chip can deep-link directly instead of jumping to
    // Settings as a fallback. Settings still has its own entry; both
    // surfaces render the same screen.
    var showAgentActivityOverlay by remember { mutableStateOf(false) }

    // Notification "Agent log" action → open the audit overlay. Latched state
    // on the holder (not a bus event) so a tap that cold-starts the app, or
    // lands while the biometric lock delays composition, isn't dropped;
    // consumed once shown so back-dismiss sticks (#239).
    val openAgentLogRequested by mcpStatusHolder.openActivityLog.collectAsState()
    LaunchedEffect(openAgentLogRequested) {
        if (openAgentLogRequested) {
            showAgentActivityOverlay = true
            mcpStatusHolder.consumeOpenActivityLog()
        }
    }

    // Native Wayland desktop — poll compositor state reactively
    var waylandRunning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            waylandRunning = sh.haven.core.wayland.WaylandBridge.isCompositorRunning()
            kotlinx.coroutines.delay(500)
        }
    }

    // Auto-hide tabs for protocols with no configured connections.
    // Predicates layer on the always-shown Connections + Settings tabs:
    // each per-screen flag short-circuits to true when alwaysShowAllTabs
    // is on, which is the safety net for power users with single-purpose
    // installs that empty repositories.
    val connections by connectionRepository.observeAll()
        .collectAsState(initial = emptyList())
    val sshKeys by sshKeyRepository.observeAll()
        .collectAsState(initial = emptyList())
    val alwaysShowAllTabs by preferencesRepository.alwaysShowAllTabs
        .collectAsState(initial = false)

    // App-wide background opacity (reuses the terminal background-opacity
    // setting). Below 1.0 the device wallpaper shows through every screen's
    // background. Terminal panes blend via their own translucent fill, so
    // they get Color.Transparent here instead (see terminalTransparent).
    val appBackgroundOpacity by preferencesRepository.terminalBackgroundOpacity
        .collectAsState(initial = 1f)

    val hasTerminalProfiles = connections.any { it.isTerminal }
    // The Mail tab tracks live sessions, not saved profiles: it appears only
    // while an email connection is actually open (a CONNECTED session) and
    // disappears on disconnect — mail is a transient view with no offline
    // store yet, unlike the Terminal/Keys tabs which gate on profile existence.
    val mailSessions by mailSessionManager.sessions.collectAsState()
    val hasOpenEmailSession = mailSessions.values.any {
        it.status == sh.haven.core.mail.MailSessionManager.SessionState.Status.CONNECTED
    }

    val screenOrderPref by preferencesRepository.screenOrder
        .collectAsState(initial = emptyList())
    val screens = remember(
        screenOrderPref,
        hasTerminalProfiles,
        hasOpenEmailSession,
        alwaysShowAllTabs,
    ) {
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
            if (alwaysShowAllTabs) return@filter true
            when (screen) {
                // Always shown — Connections is the master list, Settings
                // hosts the Always-show-all-tabs toggle (discoverability).
                Screen.Connections, Screen.Settings -> true
                // Always shown: the Desktop tab is the sole entry point to
                // the local-desktop install hub (DesktopManagerScreen), which
                // is useful with no connection at all — same rationale as Sftp
                // below. Hiding it until a desktop connection existed made the
                // local-desktop feature undiscoverable on a fresh install: the
                // installer was reachable only via "Always show all tabs" or
                // after blindly creating a Local Shell first (#215).
                Screen.Desktop -> true
                // SFTP file browser is useful even with no remote storage
                // (local file paths work), so it stays visible.
                Screen.Sftp -> true
                // Mail shows only while an email connection is open (a live
                // CONNECTED session) and hides again on disconnect.
                Screen.Mail -> hasOpenEmailSession
                // Terminal hides until there's any SSH/Mosh/ET/Reticulum
                // profile (ConnectionProfile.isTerminal covers them all).
                Screen.Terminal -> hasTerminalProfiles
                // Keys/identity management is always shown — like Desktop and
                // Sftp above, it's useful before any connection exists (adding
                // keys or identities first is a normal first step). Hiding it
                // until a key/CA/SSH host existed left it only in Settings on a
                // fresh install, which was undiscoverable for a keys-first user
                // (#360).
                Screen.Keys -> true
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

    // SINGLE SOURCE OF TRUTH for which screen is shown. Render (the pager
    // page), selection (nav-bar / rail highlight) and navigation
    // (requestScreen) all derive from this one Screen value, so they can
    // never drift apart. The previous design tracked position as a raw Int
    // page index against a `screens` list that is both *filtered*
    // (Desktop/Terminal/Keys appear only when relevant) and *user-reorderable*
    // (drag-to-reorder persists an order) — when the set or order changed
    // under it, the same Int aliased to a different screen, so tapping one
    // tab could land on another and the highlight could disagree with the
    // page. Anchoring to the Screen *identity* keeps the page locked to the
    // selection across any set/order change.
    //
    // Hoisted out of this composition into an Activity-scoped ViewModel so the
    // selection survives HavenNavHost leaving + re-entering the composition when
    // a PiP app-window swaps the whole nav host out (MainActivity) — otherwise a
    // fresh `remember` re-initialised it to page 0 (Connections) on PiP-exit and
    // the user lost their place. SavedStateHandle also carries it across process
    // death. See NavStateViewModel.
    val navStateViewModel: NavStateViewModel = hiltViewModel()
    val selectedScreen by navStateViewModel.selectedScreen.collectAsState()
    fun requestScreen(screen: Screen) { navStateViewModel.select(screen) }
    // The fresh pager starts at page zero when the host re-enters composition.
    // Do not let that initial settled page overwrite the retained selection
    // before the render effect below has restored it.
    var restoreDone by remember { mutableStateOf(false) }
    // True while a manual page-swipe (pagerSwipeOverride, on the Terminal/SFTP
    // pages) owns the pager. While set, the settledPage→selection sync below is
    // suppressed: the override adopts the settled page explicitly on release, so
    // a settledPage value observed mid-gesture (or in the one-frame gap between
    // the drag scroll session and the release animation) can't flip the
    // selection underneath the gesture. Never set on the built-in-pager pages.
    var isUserSwiping by remember { mutableStateOf(false) }
    // During an override-driven swipe (Terminal/SFTP), stop child scrollables
    // (e.g. the SFTP file list) from forwarding their unconsumed HORIZONTAL
    // drag up to the pager's built-in DefaultPagerNestedScrollConnection. That
    // connection scrolls the pager even with userScrollEnabled=false (it only
    // needs currentPageOffsetFraction != 0), so with the IME up it amplified a
    // swipe by a whole page (the overshoot/bounce). Applied OUTER to the pager
    // so its onPreScroll runs before the pager's; vertical passes through so the
    // list still scrolls. Gated on isUserSwiping so built-in-pager pages are
    // unaffected.
    val consumeHorizontalNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (isUserSwiping) Offset(available.x, 0f) else Offset.Zero
        }
    }
    // RENDER follows SELECTION: re-scroll whenever the selection changes OR
    // the available screens change (filter/reorder), so the visible page is
    // always the selected Screen. Instant scroll (not animated) so a tap
    // never double-jumps through intermediate pages. No-op when the screen
    // isn't present yet (e.g. a tab still gated off, or a deep-link to
    // Desktop before its first session) — adding it mutates `screens`, which
    // re-fires this effect and lands the user there.
    LaunchedEffect(selectedScreen, screens) {
        val idx = screens.indexOf(selectedScreen)
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
        restoreDone = true
    }
    // Manual swipes feed the source of truth: when the pager *settles* on a
    // page, adopt that screen as the selection. Keyed on settledPage only
    // (not `screens`) so a pending requestScreen to a not-yet-present tab is
    // never clobbered by a screens-list change before the scroll lands.
    LaunchedEffect(pagerState.settledPage) {
        // While a manual swipe owns the pager the override is the authority on
        // the settled page (it calls requestScreen on release); skip here so a
        // transient settledPage can't flip the selection mid-gesture. The
        // built-in-pager pages never set this flag, so they still sync here.
        if (!restoreDone || isUserSwiping) return@LaunchedEffect
        screens.getOrNull(pagerState.settledPage)?.let { settled ->
            if (settled != selectedScreen) {
                requestScreen(settled)
            }
        }
    }

    // Desktop (VNC/RDP) navigation is collected HERE, at the always-composed
    // nav-host level, rather than inside ConnectionsScreen — so a desktop tab is
    // created the instant the connect emits, no matter which screen is on-screen.
    // This is what makes the lost-connection Retry button and the MCP
    // connect_profile tool reliably open the desktop even when the Connections
    // screen isn't composed (it isn't, from the Desktop tab). (#121)
    val connectionsViewModel: ConnectionsViewModel = hiltViewModel()
    val navigateToVncEvent by connectionsViewModel.navigateToVnc.collectAsState()
    val navigateToRdpEvent by connectionsViewModel.navigateToRdp.collectAsState()
    val navigateToSpiceEvent by connectionsViewModel.navigateToSpice.collectAsState()
    LaunchedEffect(navigateToVncEvent) {
        navigateToVncEvent?.let { nav ->
            desktopViewModel.addVncSession(
                nav.host, nav.port, nav.password, nav.username,
                sshForward = nav.sshForward,
                sshSessionId = nav.sshSessionId,
                profileId = nav.profileId,
                colorDepth = nav.colorDepth,
            )
            connectionsViewModel.onDesktopNavigated()
            requestScreen(Screen.Desktop)
        }
    }
    LaunchedEffect(navigateToRdpEvent) {
        navigateToRdpEvent?.let { nav ->
            desktopViewModel.addRdpSession(
                nav.host, nav.port, nav.username, nav.password, nav.domain,
                nav.sshForward, nav.sshSessionId, nav.profileId,
                useNla = nav.useNla,
                colorDepth = nav.colorDepth,
            )
            connectionsViewModel.onDesktopNavigated()
            requestScreen(Screen.Desktop)
        }
    }
    LaunchedEffect(navigateToSpiceEvent) {
        navigateToSpiceEvent?.let { nav ->
            desktopViewModel.addSpiceSession(
                nav.host, nav.port, nav.password,
                sshForward = nav.sshForward,
                sshSessionId = nav.sshSessionId,
                profileId = nav.profileId,
            )
            connectionsViewModel.onDesktopNavigated()
            requestScreen(Screen.Desktop)
        }
    }

    // Debug navigation: scroll pager when DebugReceiver (debug builds only) emits a route
    LaunchedEffect(Unit) {
        DebugNavEvents.requests.collect { route ->
            val target = screens.indexOfFirst { it.route == route }
            if (target >= 0) pagerState.animateScrollToPage(target)
        }
    }

    // Set when the "mail actions awaiting approval" notification tap routes
    // through (MainActivity → AgentUiCommand.OpenMailRules): MailScreen opens
    // its rules pane once composed, then consumes the flag. Declared here
    // (before the command collector below) so the collector can set it.
    var pendingOpenMailRules by rememberSaveable { mutableStateOf(false) }

    // Agent UI commands — when an MCP tool posts a navigation verb, switch
    // the pager to the right tab so the user lands where the agent asked.
    // The matching feature ViewModel collects the same bus and adjusts its
    // internal state in parallel.
    LaunchedEffect(agentUiCommandBus, screens) {
        agentUiCommandBus.commands
            // Cold-start: an SFTP-targeted command emitted before any collector
            // subscribed (Compose hadn't built yet) is held in the bus latch.
            // Peek it (don't consume — SftpViewModel consumes it via
            // takePendingSftpCommand) and switch to Files, which mounts the
            // SftpViewModel so it can drain the latch and run the action.
            .onSubscription {
                if (agentUiCommandBus.peekPendingSftpCommand() != null) requestScreen(Screen.Sftp)
            }
            .collect { command ->
            val screen = when (command) {
                is sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath -> Screen.Sftp
                is sh.haven.core.data.agent.AgentUiCommand.OpenConvertDialog -> Screen.Sftp
                is sh.haven.core.data.agent.AgentUiCommand.FocusTerminalSession -> Screen.Terminal
                is sh.haven.core.data.agent.AgentUiCommand.OpenTerminalSession -> Screen.Terminal
                is sh.haven.core.data.agent.AgentUiCommand.OpenRemoteDesktop -> Screen.Desktop
                is sh.haven.core.data.agent.AgentUiCommand.OpenWaylandDesktop -> Screen.Desktop
                is sh.haven.core.data.agent.AgentUiCommand.OpenUsbDrive -> Screen.Desktop
                is sh.haven.core.data.agent.AgentUiCommand.RegenerateStepCaCert -> Screen.Keys
                is sh.haven.core.data.agent.AgentUiCommand.OpenInEditor -> Screen.Sftp
                is sh.haven.core.data.agent.AgentUiCommand.EncryptFile -> Screen.Sftp
                is sh.haven.core.data.agent.AgentUiCommand.DecryptFile -> Screen.Sftp
                is sh.haven.core.data.agent.AgentUiCommand.ConnectProfile ->
                    // Connect lands the user on the Connections tab so they
                    // can see the connecting → connected status flip.
                    Screen.Connections
                is sh.haven.core.data.agent.AgentUiCommand.ConnectFromDeepLink ->
                    // haven://connect matched a saved profile: show the
                    // Connections tab for the confirm sheet + status flip.
                    Screen.Connections
                is sh.haven.core.data.agent.AgentUiCommand.PrefillNewConnection ->
                    // haven://connect with no saved match: open the New
                    // Connection editor (on the Connections tab) pre-filled.
                    Screen.Connections
                is sh.haven.core.data.agent.AgentUiCommand.AnswerAuthPrompt ->
                    // Answering the password/passphrase fallback re-drives the
                    // connect — same as ConnectProfile, show the Connections tab.
                    Screen.Connections
                is sh.haven.core.data.agent.AgentUiCommand.AnswerSessionSelection ->
                    // Answering the session picker re-drives the attach — same
                    // as ConnectProfile, show the Connections tab.
                    Screen.Connections
                is sh.haven.core.data.agent.AgentUiCommand.OpenBackupPasswordDialog ->
                    Screen.Settings
                is sh.haven.core.data.agent.AgentUiCommand.OpenMailRules -> {
                    // Approval-queue notification tap: land on Mail with the
                    // rules pane open. With no live email session the Mail tab
                    // is hidden; the selection is remembered and both fire
                    // once the user reconnects the account (approval needs the
                    // session anyway).
                    pendingOpenMailRules = true
                    Screen.Mail
                }
            }
            // Reactive: e.g. OpenRemoteDesktop waits for Desktop to appear
            // rather than silently no-op'ing when it's still hidden.
            requestScreen(screen)
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
    val snippetLibrary by preferencesRepository.snippetLibrary
        .collectAsState(initial = emptyList())
    val navBlockMode by preferencesRepository.navBlockMode
        .collectAsState(initial = sh.haven.core.data.preferences.NavBlockMode.ALIGNED)
    val toolbarUniformGrid by preferencesRepository.toolbarUniformGrid
        .collectAsState(initial = false)
    val editModeControlsPlacement by preferencesRepository.editModeControlsPlacement
        .collectAsState(initial = sh.haven.core.data.preferences.EditModeControlsPlacement.LEFT)
    val desktopKeyPlacement by preferencesRepository.desktopKeyPlacement
        .collectAsState(initial = sh.haven.core.data.preferences.DesktopKeyPlacement.LEFT)
    val fullscreenButtonCorner by preferencesRepository.fullscreenButtonCorner
        .collectAsState(initial = sh.haven.core.data.preferences.FullscreenButtonCorner.DEFAULT)
    val rdpChipAnchor by preferencesRepository.rdpChipAnchor
        .collectAsState(initial = sh.haven.core.data.preferences.RdpChipAnchor.DEFAULT)
    val toolbarMinKeyWidth by preferencesRepository.toolbarMinButtonWidth
        .collectAsState(initial = sh.haven.core.data.preferences.UserPreferencesRepository.DEFAULT_TOOLBAR_MIN_BUTTON_WIDTH)
    val showSearchButton by preferencesRepository.showSearchButton
        .collectAsState(initial = false)
    val showCopyOutputButton by preferencesRepository.showCopyOutputButton
        .collectAsState(initial = false)
    val keepScreenOnInTerminal by preferencesRepository.keepScreenOnInTerminal
        .collectAsState(initial = false)
    val hideNavBarInTerminal by preferencesRepository.hideNavBarInTerminal
        .collectAsState(initial = false)
    val mouseInputEnabled by preferencesRepository.mouseInputEnabled
        .collectAsState(initial = true)
    val terminalRightClick by preferencesRepository.terminalRightClick
        .collectAsState(initial = false)
    val tapToPositionCursorOnPrompt by preferencesRepository.terminalTapToPositionCursor
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
    val reflowTerminalOnKeyboard by preferencesRepository.reflowTerminalOnKeyboard
        .collectAsState(initial = false)
    val showTerminalTabBar by preferencesRepository.showTerminalTabBar
        .collectAsState(initial = true)
    val desktopInputMode by preferencesRepository.desktopInputMode
        .collectAsState(initial = "DIRECT")

    // Profile ID to focus when navigating to terminal
    var pendingTerminalProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Profile ID for opening a new session (new tab) on an existing connection
    var pendingNewSessionProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Profile ID for opening a local-shell tab (Desktop → Manage shell button,
    // #168). Routed through here rather than the AgentUiCommandBus because
    // TerminalViewModel only collects the bus while TerminalScreen is composed,
    // and the Desktop tab is outside the pager's composition window — the bus
    // event was dropped and no tab opened. HavenNavHost is always composed.
    var pendingLocalShellProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Desktop id to join (DISPLAY/WAYLAND_DISPLAY) when the pending shell opens (#285).
    var pendingLocalShellDeId by rememberSaveable { mutableStateOf<String?>(null) }

    // SMB auto-connect params
    var pendingSmbProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Rclone auto-connect params
    var pendingRcloneProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Email (Mail) auto-open params
    var pendingEmailProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Local-shell open requests from the Desktop → Manage shell button (#168).
    // Always-composed HavenNavHost collects them, sets the pending profile,
    // and animates to the Terminal page; TerminalScreen creates the tab once
    // composed (see pendingLocalShellProfileId).
    LaunchedEffect(desktopViewModel) {
        desktopViewModel.openLocalShellRequests.collect { req ->
            pendingLocalShellProfileId = req.profileId
            pendingLocalShellDeId = req.desktopDeId
            requestScreen(Screen.Terminal)
        }
    }

    // Disable pager swipe while terminal text selection is active
    var terminalSelectionActive by remember { mutableStateOf(false) }
    // #524b: while the Swipe key is latched, horizontal swipes inside the
    // terminal are arrow keys — the pager override must not fight them.
    var terminalSwipeArrowsActive by remember { mutableStateOf(false) }
    // True while the active terminal wants a wallpaper see-through background;
    // makes the Scaffold container transparent so the (window-level)
    // FLAG_SHOW_WALLPAPER actually reaches the eye behind the terminal.
    var terminalTransparent by remember { mutableStateOf(false) }
    // The active terminal's background while the Terminal pane is showing
    // (#523). The terminal's colours come from its own scheme rather than the
    // app theme, so without this a black terminal under a light app theme left
    // the Scaffold's light container showing behind the status bar.
    var terminalBackground by remember { mutableStateOf<Color?>(null) }
    var terminalReorderMode by remember { mutableStateOf(false) }
    var openToolbarConfig by remember { mutableStateOf(false) }

    // Desktop fullscreen hides bottom nav and system bars
    var desktopFullscreen by remember { mutableStateOf(false) }
    // Terminal fullscreen — same chrome behaviour, owned by TerminalScreen (#138)
    var terminalFullscreen by remember { mutableStateOf(false) }
    // Exit a tab's fullscreen mode when the pager settles on a different
    // tab. Without this, switching from a fullscreen Terminal/Desktop to
    // (e.g.) Keys leaves `terminalFullscreen = true`, which disables
    // pager swipe (line 277) and strands the user — they'd swiped here
    // but can no longer swipe back. Reported by maintainer 2026-05-07.
    LaunchedEffect(pagerState.settledPage) {
        val settled = screens.getOrNull(pagerState.settledPage)
        if (settled != Screen.Terminal && terminalFullscreen) {
            terminalFullscreen = false
        }
        if (settled != Screen.Desktop && desktopFullscreen) {
            desktopFullscreen = false
        }
    }
    // Disable pager swipe when VNC/RDP is connected (pinch-to-zoom conflicts)
    var desktopConnected by remember { mutableStateOf(false) }
    // A desktop fullscreen flag that outlives its session strands the user completely:
    // it hides the app bar and bottom nav, and the same flag disables pager swipe
    // (below), so the way out is switched off by the thing you need to get out of.
    // Nothing is connected, so nothing can be fullscreen. (#386)
    LaunchedEffect(desktopConnected) {
        if (!desktopConnected && desktopFullscreen) desktopFullscreen = false
    }
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
            // The Terminal and SFTP pages drive the pager themselves through
            // pagerSwipeOverride (so they can co-exist with the terminal's own
            // gestures and SFTP's flick-to-navigate-up). Disable the pager's
            // BUILT-IN scrollable on those pages so it can't also move the
            // pager — otherwise two controllers fight: the override scrolls a
            // little while the built-in flings the pager a couple of pages on,
            // leaving the page and the nav selection inconsistent. The
            // built-in stays enabled on the other pages (Connections/Desktop/
            // Keys/Settings), which have no override and rely on it.
            userScrollEnabled = !desktopFullscreen && !terminalFullscreen && !desktopConnected && !terminalSelectionActive && !sftpEditorOpen && !sftpImageToolOpen &&
                selectedScreen != Screen.Terminal && selectedScreen != Screen.Sftp,
            modifier = modifier.nestedScroll(consumeHorizontalNestedScroll),
        ) { page ->
            // While the Terminal pane is showing, the root Scaffold paints the
            // TERMINAL scheme's background (#523), so during a swipe away from
            // the terminal the incoming page sat on the terminal's colour — a
            // black flash under a light app theme (#574). Give every
            // non-terminal page its own app-theme background so it never
            // depends on the root layer mid-transition. Skip it when the
            // wallpaper see-through preference wants translucent pages.
            val pageBackground =
                if (screens[page] != Screen.Terminal && appBackgroundOpacity >= 1f) {
                    Modifier.background(MaterialTheme.colorScheme.background)
                } else {
                    Modifier
                }
            Box(modifier = Modifier.fillMaxSize().then(pageBackground)) {
            when (screens[page]) {
                Screen.Connections -> ConnectionsScreen(
                    onNavigateToTerminal = { profileId ->
                        pendingTerminalProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Terminal)
                        }
                    },
                    onNavigateToNewSession = { profileId ->
                        pendingNewSessionProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Terminal)
                        }
                    },
                    // VNC/RDP navigation is handled by the always-composed
                    // collector above (HavenNavHost), not via these callbacks —
                    // see the navigateToVnc/navigateToRdp LaunchedEffects (#121).
                    onNavigateToSmb = { profileId ->
                        pendingSmbProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Sftp)
                        }
                    },
                    onNavigateToRclone = { profileId ->
                        pendingRcloneProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Sftp)
                        }
                    },
                    onNavigateToEmail = { profileId ->
                        pendingEmailProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Mail)
                        }
                    },
                    onNavigateToWayland = {
                        desktopViewModel.addWaylandTab()
                        coroutineScope.launch {
                            requestScreen(Screen.Desktop)
                        }
                    },
                    onNavigateToConnections = {
                        coroutineScope.launch {
                            requestScreen(Screen.Connections)
                        }
                    },
                    onNavigateToAgentActivity = { showAgentActivityOverlay = true },
                    workspaceSection = { sh.haven.app.workspace.ui.WorkspaceSection() },
                )
                Screen.Terminal -> {
                    TerminalScreen(
                        navigateToProfileId = pendingTerminalProfileId,
                        newSessionProfileId = pendingNewSessionProfileId,
                        openLocalShellProfileId = pendingLocalShellProfileId,
                        openLocalShellDeId = pendingLocalShellDeId,
                        isActive = pagerState.settledPage == pageOf(Screen.Terminal),
                        fontSize = terminalFontSize,
                        terminalFontPath = terminalFontPath,
                        toolbarLayout = toolbarLayout,
                        navBlockMode = navBlockMode,
                        toolbarUniformGrid = toolbarUniformGrid,
                        editModeControlsPlacement = editModeControlsPlacement,
                        desktopKeyPlacement = desktopKeyPlacement,
                        fullscreenButtonCorner = fullscreenButtonCorner,
                        onFullscreenButtonCornerChange = {
                            coroutineScope.launch { preferencesRepository.setFullscreenButtonCorner(it) }
                        },
                        toolbarMinKeyWidth = toolbarMinKeyWidth,
                        showSearchButton = showSearchButton,
                        showCopyOutputButton = showCopyOutputButton,
                        keepScreenOnInTerminal = keepScreenOnInTerminal,
                        mouseInputEnabled = mouseInputEnabled,
                        terminalRightClick = terminalRightClick,
                        tapToPositionCursorOnPrompt = tapToPositionCursorOnPrompt,
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
                        reflowTerminalOnKeyboard = reflowTerminalOnKeyboard,
                        showTabBar = showTerminalTabBar,
                        onFullscreenChanged = { terminalFullscreen = it },
                        onNavigateToConnections = {
                            coroutineScope.launch {
                                requestScreen(Screen.Connections)
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
                                requestScreen(Screen.Desktop)
                            }
                        },
                        onSelectionActiveChanged = { terminalSelectionActive = it },
                        onSwipeArrowsActiveChanged = { terminalSwipeArrowsActive = it },
                        onTransparentChanged = { terminalTransparent = it },
                        onBackgroundColorChanged = { terminalBackground = it },
                        onReorderModeChanged = { terminalReorderMode = it },
                        onToolbarLayoutChanged = { newLayout ->
                            coroutineScope.launch {
                                preferencesRepository.setToolbarLayout(newLayout)
                            }
                        },
                        snippetLibrary = snippetLibrary,
                        onSnippetLibraryChanged = { newLibrary ->
                            coroutineScope.launch {
                                preferencesRepository.setSnippetLibrary(newLibrary)
                            }
                        },
                        onOpenToolbarSettings = {
                            openToolbarConfig = true
                            coroutineScope.launch {
                                requestScreen(Screen.Settings)
                            }
                        },
                        onNavigateToSftp = {
                            coroutineScope.launch {
                                requestScreen(Screen.Sftp)
                            }
                        },
                        terminalModifier = Modifier.pagerSwipeOverride(
                            pagerState, coroutineScope,
                            isSelectionActive = { terminalSelectionActive || terminalReorderMode || terminalSwipeArrowsActive },
                            onSwipeActiveChange = { isUserSwiping = it },
                            onAdoptPage = { page -> screens.getOrNull(page)?.let { requestScreen(it) } },
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
                    LaunchedEffect(pendingLocalShellProfileId) {
                        if (pendingLocalShellProfileId != null) {
                            kotlinx.coroutines.delay(6000)
                            pendingLocalShellProfileId = null
                            pendingLocalShellDeId = null
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
                        toolbarUniformGrid = toolbarUniformGrid,
                        rdpChipAnchor = rdpChipAnchor,
                        onRdpChipAnchorChange = {
                            coroutineScope.launch { preferencesRepository.setRdpChipAnchor(it) }
                        },
                        inputMode = desktopInputMode,
                        onSetInputMode = { mode ->
                            coroutineScope.launch { preferencesRepository.setDesktopInputMode(mode) }
                        },
                        isActive = pagerState.settledPage == pageOf(Screen.Desktop),
                        fullscreen = desktopFullscreen,
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
                            onSwipeActiveChange = { isUserSwiping = it },
                            onAdoptPage = { page -> screens.getOrNull(page)?.let { requestScreen(it) } },
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
                        onAttachFinished = {
                            // Once the picker resolves (confirm or cancel)
                            // bring the user back to the Terminal page so the
                            // shell-quoted path lands at their cursor where
                            // they can see it.
                            coroutineScope.launch {
                                requestScreen(Screen.Terminal)
                            }
                        },
                        onOpenUsbDrive = { desktopViewModel.openUsbDrive() },
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
                Screen.Mail -> {
                    MailScreen(
                        pendingProfileId = pendingEmailProfileId,
                        pendingOpenRules = pendingOpenMailRules,
                        onPendingRulesConsumed = { pendingOpenMailRules = false },
                        mailModifier = Modifier.fillMaxSize(),
                    )
                    LaunchedEffect(pendingEmailProfileId) {
                        if (pendingEmailProfileId != null) {
                            pendingEmailProfileId = null
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
    }

    // App-global user-message host: any ViewModel can post to userMessageBus
    // and it shows here, over EVERY screen (the Scaffold is the root, the pager
    // is its content). Fixes errors being lost to a screen-scoped snackbar once
    // the connect path navigates away (#215 follow-up).
    val globalSnackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(userMessageBus) {
        userMessageBus.messages.collect { msg ->
            // Simple dedupe: skip if identical to the currently-shown message.
            if (globalSnackbarHostState.currentSnackbarData?.visuals?.message != msg.text) {
                globalSnackbarHostState.showSnackbar(
                    message = msg.text,
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    Scaffold(
        // This app Scaffold owns the single translucent background layer.
        // - A translucent terminal pane handles its own per-tab blend, so we
        //   drop to fully transparent for it (terminalTransparent).
        // - Otherwise, when the global background opacity is < 1, blend the
        //   app background with alpha so the device wallpaper shows through
        //   every screen. Inner screen Scaffolds are Color.Transparent so they
        //   defer to this single layer instead of compounding it.
        // - An opaque terminal pane paints its own scheme's background here so
        //   it reaches behind the status bar instead of stopping under a strip
        //   of app-theme colour (#523). Below the translucent case, which must
        //   still win: that one wants nothing painted at all.
        containerColor = when {
            terminalTransparent -> Color.Transparent
            terminalBackground != null -> terminalBackground!!
            appBackgroundOpacity < 1f ->
                MaterialTheme.colorScheme.background.copy(alpha = appBackgroundOpacity)
            else -> MaterialTheme.colorScheme.background
        },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
        snackbarHost = { SnackbarHost(globalSnackbarHostState) },
        bottomBar = {
            // hideNavBarInTerminal (#521) hides only Haven's own tab bar while
            // the Terminal screen is selected; system bars are untouched. The
            // pager override gestures still switch screens, and the bar returns
            // as soon as the pager settles anywhere else.
            val hideForTerminalPref = hideNavBarInTerminal && selectedScreen == Screen.Terminal
            if (!desktopFullscreen && !terminalFullscreen && !useSideNavigation && !hideForTerminalPref) {
                NavigationBar {
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
                            selected = screen == selectedScreen,
                            onClick = {
                                // Don't navigate while a drag-reorder is in
                                // flight. Otherwise just set the selection —
                                // the render effect scrolls the pager to it.
                                if (navDragIndex < 0 && screens.contains(screen)) {
                                    requestScreen(screen)
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
                if (!desktopFullscreen && !terminalFullscreen) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        // Scrollable so the items never clip off the bottom on
                        // short landscape heights — a plain NavigationRail
                        // centres its items and silently cuts off the last ones
                        // when they don't all fit, which reads as "a tab is
                        // gone" (cf. issue #179's auto-hide confusion).
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
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
                                    selected = screen == selectedScreen,
                                    onClick = { requestScreen(screen) },
                                )
                            }
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

    // Agent-activity audit overlay — rendered above the pager when the
    // chip on the Connections top bar (or any future deep-link) toggles
    // it on. Dismissed via the screen's back arrow.
    if (showAgentActivityOverlay) {
        sh.haven.feature.settings.AgentActivityScreen(
            onBack = { showAgentActivityOverlay = false },
        )
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
     * Called with true when a horizontal swipe starts driving the pager and
     * false once the release-settle animation completes. The host raises a
     * flag from this that suppresses the settledPage→selection sync for the
     * duration of the gesture (see isUserSwiping in HavenNavHost).
     */
    onSwipeActiveChange: (Boolean) -> Unit = {},
    /**
     * Called once on a normal (non-flick) release with the page index the
     * swipe settled on, so the host can adopt it as the selection. This is the
     * single authoritative selection update for an override-driven swipe.
     */
    onAdoptPage: (Int) -> Unit = {},
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

        // Anchor the gesture to the page we started on. During the drag the
        // pager scrolls progressively and pagerState.currentPage may tick over
        // to the next page before the finger lifts; computing the release
        // target from currentPage would then cause a two-page jump. Use
        // startPage as the stable anchor.
        val startPage = pagerState.currentPage
        var selectionInterrupted = false
        val velocityTracker = VelocityTracker().also {
            it.addPosition(down.uptimeMillis, down.position)
        }

        // Drive the drag with dispatchRawDelta — a direct 1:1 scroll that
        // tracks the finger. The settledPage→selection feedback this would
        // otherwise trigger mid-drag (dispatchRawDelta never raises
        // isScrollInProgress, so settledPage tracks currentPage) is instead
        // suppressed by the isUserSwiping flag, which gates BOTH settledPage
        // effects for the whole gesture. NOTE: an earlier attempt drove a real
        // pagerState.scroll{} session here to keep isScrollInProgress true —
        // but the pager snap-FLINGS on scroll-session-end using the built-up
        // velocity, hurling a fast flick a full page in one frame (an "instant
        // jump", no slide). dispatchRawDelta has no fling-on-end, so the page
        // tracks the finger and the release animation is the only motion.
        var swiping = false
        fun beginSwipe() {
            if (!swiping) { swiping = true; onSwipeActiveChange(true) }
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

            // Forward horizontal drags straight to the pager (unless selection
            // is active).
            if (isHorizontal && !selectionInterrupted) {
                if (isSelectionActive()) {
                    // Selection activated mid-swipe — snap back after the loop.
                    selectionInterrupted = true
                } else {
                    beginSwipe()
                    pagerState.dispatchRawDelta(-delta.x)
                }
            }
        } while (change?.pressed == true)

        if (isHorizontal && !selectionInterrupted && !isSelectionActive()) {
            // Release decision — fast flick first (per-page hook gets a
            // chance to claim it), then fall back to the standard
            // page-threshold behaviour.
            val releaseVelocity = velocityTracker.calculateVelocity().x
            val isFlick = abs(releaseVelocity) >= flickVelocityPx
            val flickHandled = if (isFlick) onFlick(totalX > 0) else false

            scope.launch {
                try {
                    // Decide the settle target from where the pager ACTUALLY
                    // scrolled to (currentPage + offset), NOT from net finger
                    // displacement (totalX). totalX can diverge from the real
                    // scroll position on a fast/curved flick or an IME-dismiss
                    // relayout mid-drag — which made the pager visibly reach the
                    // next page yet snap back to the start page (the "bounce").
                    // Deciding from the real position keeps the settle
                    // consistent with what the user sees.
                    val target = if (flickHandled) {
                        // Caller took over (e.g. SFTP navigated up) — settle
                        // back on the page we started on.
                        startPage
                    } else {
                        val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        val moved = pos - startPage
                        // Commit to the neighbour once the pager has travelled a
                        // quarter page from the start (a flick commits sooner).
                        val commit = if (isFlick) 0.1f else 0.25f
                        when {
                            moved <= -commit -> startPage - 1
                            moved >= commit -> startPage + 1
                            else -> startPage
                        }
                    }.coerceIn(0, pagerState.pageCount - 1)
                    // The selection sync stays suppressed (isUserSwiping still
                    // set) across the gap between sessions; we adopt the final
                    // page explicitly, then release the flag in finally.
                    pagerState.animateScrollToPage(target)
                    if (!flickHandled) {
                        onAdoptPage(target)
                    }
                } finally {
                    // ALWAYS release the swipe flag, even if the settle
                    // animation was cancelled — otherwise the selection sync
                    // stays suppressed and the nav highlight sticks on the page
                    // we started from.
                    onSwipeActiveChange(false)
                }
            }
        } else if (swiping) {
            // The drag was interrupted by selection (or released while
            // selection active) — snap back to where we began and release the
            // flag. Selection never changed during the swipe, so no adopt.
            scope.launch {
                try {
                    pagerState.animateScrollToPage(startPage)
                } finally {
                    onSwipeActiveChange(false)
                }
            }
        }
    }
}
