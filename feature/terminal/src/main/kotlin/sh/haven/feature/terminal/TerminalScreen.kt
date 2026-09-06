package sh.haven.feature.terminal

import sh.haven.core.toolbar.KeyboardToolbar
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import sh.haven.core.ui.KeyEventInterceptor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.TerminalEmulator
import sh.haven.core.terminal.HavenKeyboardMode
import sh.haven.core.terminal.HavenTerminal
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository

/** Horizontal padding on the tab strip, subtracted when deciding if tabs can be justified. */
private val TAB_STRIP_PADDING = 4.dp

/**
 * Below this a stretched tab is no wider than a content-hugging one, so there is
 * nothing to gain — past that many tabs the strip goes back to scrolling instead
 * of shaving every tab down to a sliver.
 */
private val MIN_JUSTIFIED_TAB_WIDTH = 96.dp

/** Distinct colors for grouping tabs by connection profile. */
/**
 * Above this relative luminance a background counts as bright enough to need
 * dark system-bar icons (#523).
 *
 * Compose's [luminance] is already perceptual (Rec. 709, gamma-corrected), so
 * a mid grey lands near 0.2 rather than 0.5 — the halfway point of the scale
 * is well above mid grey, which is what we want: only genuinely light
 * backgrounds such as Solarized Light flip the icons dark.
 */
private const val BRIGHT_BACKGROUND_LUMINANCE = 0.5f

/**
 * How recently the IME must have gone away for the backgrounding to be the cause (#515).
 *
 * Android takes the keyboard down as part of moving the app out, and the insets
 * report it gone *before* ON_PAUSE arrives — so "was the keyboard up?" cannot be
 * answered by reading them at pause. It has to be answered by how long ago it
 * went. A system-driven hide lands within a couple of hundred milliseconds of
 * the pause; a user tapping it away happened whenever they tapped, which is
 * essentially never that recent.
 *
 * Generous rather than tight: restoring a keyboard the user had just dismissed
 * is a nuisance they can undo with one tap, while failing to restore one they
 * wanted is the bug being fixed.
 */
private const val IME_BACKGROUND_HIDE_WINDOW_MS = 400L

private val TAB_GROUP_COLORS = listOf(
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
 * Whether the "an app wants the mouse but the preference is off" hint has been
 * shown since this process started (#586).
 *
 * Deliberately process-scoped rather than remembered per composable: the point
 * is one notice per session, and a `remember` would fire again on every tab
 * switch or configuration change. Not persisted either — a user who turns the
 * app off and comes back a week later has earned the reminder.
 */
private var mouseHintShownThisSession = false

/**
 * Whether to tell the user that a TUI app asked for the mouse while the
 * preference is off (#586).
 *
 * Pure so the three rules are actually assertable: say nothing unless an app
 * genuinely asked, say nothing when the preference is already on, and say it
 * at most once. A silent no-op here is what made #580 look like broken touch
 * input for two days, and an over-eager version that nagged on every tab
 * switch would be worse than the silence it replaces.
 */
internal fun shouldShowMouseHint(
    appRequestedMouse: Boolean,
    preferenceEnabled: Boolean,
    alreadyShown: Boolean,
): Boolean = appRequestedMouse && !preferenceEnabled && !alreadyShown

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    navigateToProfileId: String? = null,
    newSessionProfileId: String? = null,
    openLocalShellProfileId: String? = null,
    openLocalShellDeId: String? = null,
    isActive: Boolean = false,
    terminalModifier: Modifier = Modifier,
    fontSize: Int = UserPreferencesRepository.DEFAULT_FONT_SIZE,
    /**
     * Optional override for the terminal typeface — Settings → Terminal
     * font lets the user pick a Nerd Font (or any TTF/OTF) so prompts
     * with extended glyphs render instead of showing tofu boxes (#123).
     * Null means "fall back to the bundled Hack Regular".
     */
    terminalFontPath: String? = null,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    navBlockMode: sh.haven.core.data.preferences.NavBlockMode = sh.haven.core.data.preferences.NavBlockMode.ALIGNED,
    toolbarUniformGrid: Boolean = false,
    editModeControlsPlacement: sh.haven.core.data.preferences.EditModeControlsPlacement = sh.haven.core.data.preferences.EditModeControlsPlacement.LEFT,
    desktopKeyPlacement: sh.haven.core.data.preferences.DesktopKeyPlacement = sh.haven.core.data.preferences.DesktopKeyPlacement.LEFT,
    fullscreenButtonCorner: sh.haven.core.data.preferences.FullscreenButtonCorner = sh.haven.core.data.preferences.FullscreenButtonCorner.DEFAULT,
    onFullscreenButtonCornerChange: (sh.haven.core.data.preferences.FullscreenButtonCorner) -> Unit = {},
    toolbarMinKeyWidth: Int = sh.haven.core.data.preferences.UserPreferencesRepository.DEFAULT_TOOLBAR_MIN_BUTTON_WIDTH,
    showSearchButton: Boolean = false,
    showCopyOutputButton: Boolean = false,
    keepScreenOnInTerminal: Boolean = false,
    mouseInputEnabled: Boolean = true,
    terminalRightClick: Boolean = false,
    tapToPositionCursorOnPrompt: Boolean = false,
    allowStandardKeyboard: Boolean = false,
    onToggleStandardKeyboard: () -> Unit = {},
    rawKeyboardMode: Boolean = false,
    onToggleRawKeyboard: () -> Unit = {},
    /**
     * Non-null = user has selected the Custom keyboard mode and these
     * are the EditorInfo flag toggles to apply. Null = use one of the
     * preset modes (Secure/Standard/Raw above). #115 follow-up.
     */
    customKeyboardFlags: sh.haven.core.terminal.ImeFlagSet? = null,
    interceptCtrlShiftV: Boolean = true,
    /**
     * User opt-in to resize the PTY (reflow / SIGWINCH) on a soft-keyboard
     * toggle even on the primary buffer, so a full-screen TUI's top status row
     * isn't shifted off-screen (#242). Default off keeps the #206 render-shift
     * for plain shells. OR'd with the mouse-mode heuristic below.
     */
    reflowTerminalOnKeyboard: Boolean = false,
    showTabBar: Boolean = true,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onNavigateToConnections: () -> Unit = {},
    onNavigateToVnc: (host: String, port: Int, username: String?, password: String?, sshForward: Boolean, sshSessionId: String?, colorDepth: String) -> Unit = { _, _, _, _, _, _, _ -> },
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    // Reports the latched swipe-arrows mode (#524b) so the host can suppress
    // the tab-switch pager gesture while the Swipe key owns horizontal swipes.
    onSwipeArrowsActiveChanged: (Boolean) -> Unit = {},
    // Reports when the active terminal wants a translucent (wallpaper
    // see-through) background, so the host can make the Scaffold/window
    // behind the terminal transparent. False otherwise.
    onTransparentChanged: (Boolean) -> Unit = {},
    /**
     * The active terminal's background colour while this pane is showing, or
     * null when it isn't (#523).
     *
     * The terminal's colours come from its own colour scheme, independent of
     * the app theme, so a black scheme under a light app theme left a white
     * strip behind the status bar with the terminal starting abruptly beneath
     * it. The host paints its container with this so the terminal reaches the
     * top of the screen.
     */
    onBackgroundColorChanged: (Color?) -> Unit = {},
    onReorderModeChanged: (Boolean) -> Unit = {},
    onToolbarLayoutChanged: (ToolbarLayout) -> Unit = {},
    snippetLibrary: List<ToolbarItem.Custom> = emptyList(),
    onSnippetLibraryChanged: (List<ToolbarItem.Custom>) -> Unit = {},
    onOpenToolbarSettings: () -> Unit = {},
    /**
     * Switch to the SFTP page — fired by the attach (paperclip) flow so
     * the user lands on the folder picker. The host (HavenNavHost) drives
     * the pager animation; this screen just signals intent.
     */
    onNavigateToSftp: () -> Unit = {},
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    var reorderMode by remember { mutableStateOf(false) }
    // Fullscreen state — survives rotation via rememberSaveable.
    // Setting this true tells the parent to hide the bottom nav / side
    // rail and tells us to hide the tab bar; LaunchedEffect below also
    // hides the system status + nav bars. Mirrors the desktop fullscreen
    // pattern in VncScreen / RdpScreen.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window
    LaunchedEffect(fullscreen) {
        onFullscreenChanged(fullscreen)
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Belt-and-braces: if the screen is torn down while still
            // in fullscreen (e.g. user backgrounds the app), make sure
            // the system bars come back so the next surface isn't stuck
            // edge-to-edge.
            if (fullscreen && window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
                onFullscreenChanged(false)
            }
        }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    val tabs by viewModel.tabs.collectAsState()
    val activeTabIndex by viewModel.activeTabIndex.collectAsState()
    val ctrlActive by viewModel.ctrlActive.collectAsState()
    val altActive by viewModel.altActive.collectAsState()
    val ctrlLocked by viewModel.ctrlLocked.collectAsState()
    val altLocked by viewModel.altLocked.collectAsState()
    val swipeArrowsMode by viewModel.swipeArrowsMode.collectAsState()
    // The latch may own the horizontal pager gesture only while a tab is
    // mounted (#609). The latch persists in DataStore after the last session
    // exits (Ctrl+D), and the empty Terminal page is swipeable solely through
    // the host's pager override — reporting the raw latch there suppresses the
    // override and strands the user on the empty page with no way to swipe to
    // another tab.
    LaunchedEffect(swipeArrowsMode, tabs.isEmpty()) {
        onSwipeArrowsActiveChanged(pagerSwipeOwnedByArrows(swipeArrowsMode, tabs.isNotEmpty()))
    }
    // Push the live system light/dark mode to the ViewModel so its
    // [terminalColorScheme] flow can resolve the auto-switch pref correctly.
    // Must run before collecting the flow so the first emission reflects
    // the current system theme rather than the StateFlow's `true` default.
    val systemIsDark = isSystemInDarkTheme()
    LaunchedEffect(systemIsDark) { viewModel.setSystemIsDark(systemIsDark) }
    val colorScheme by viewModel.terminalColorScheme.collectAsState()
    val applySchemePalette by viewModel.terminalApplySchemePalette.collectAsState()
    val tabTitlesFollowSession by viewModel.tabTitlesFollowSession.collectAsState()
    val navigateToConnections by viewModel.navigateToConnections.collectAsState()
    val newTabSessionPicker by viewModel.newTabSessionPicker.collectAsState()
    val newTabLoading by viewModel.newTabLoading.collectAsState()
    val newTabMessage by viewModel.newTabMessage.collectAsState()
    val fidoTouchPrompt by viewModel.fidoTouchPrompt.collectAsState()
    var vncDialogInfo by remember { mutableStateOf<VncInfo?>(null) }
    var localVncLoading by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val context = LocalContext.current

    LaunchedEffect(newTabMessage) {
        newTabMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.dismissNewTabMessage()
        }
    }

    // Paperclip / attach flow: SAF picker → coordinator (the SFTP screen
    // renders the folder picker) → upload → inject the shell-quoted
    // path/URL at the cursor. The runAttachFlow launches on the VM scope
    // so it survives the screen leaving composition while the user
    // navigates the picker.
    val attachLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val activeProfileId = viewModel.tabs.value
            .getOrNull(viewModel.activeTabIndex.value)?.profileId
        val (fileName, fileSize) = viewModel.attachCoordinator.queryFileInfo(uri)
        onNavigateToSftp()
        viewModel.runAttachFlow(
            sourceUri = uri,
            fileName = fileName,
            fileSize = fileSize,
            initialProfileId = activeProfileId,
        )
    }

    // #604: Dictate key. Launches the system speech recognizer directly
    // (RecognizerIntent) instead of flipping IME flags, so dictation works on
    // devices that force a keyboard with no mic of their own (Supernote +
    // Futo). The recognition service owns the microphone and its own
    // RECORD_AUDIO grant; Haven only receives the transcript and sends it to
    // the active tab. Empty result = user cancelled or silence — stay quiet.
    val dictateLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
        } else null
        if (!spoken.isNullOrEmpty()) {
            val tab = viewModel.tabs.value.getOrNull(viewModel.activeTabIndex.value)
            if (tab != null) {
                tab.sendInput(bracketPasteWrap(spoken, tab.bracketPasteMode.value).toByteArray())
            }
        }
    }
    val dictateUnavailableMessage = stringResource(R.string.terminal_dictate_unavailable)

    // Paperclip → bottom sheet → camera / gallery → recognise → paste.
    //
    // The bottom sheet hosts the SAF "send file" entry alongside four
    // image-recognition flows (QR / OCR × Camera / Gallery). Two launchers
    // back the four entries: TakePicture writes into a FileProvider cache
    // URI we control; PickVisualMedia returns whatever URI the picker hands
    // back. A single `pendingScanMode` decides which recogniser the result
    // is routed to so we don't need four separate launchers.
    var attachSheetVisible by remember { mutableStateOf(false) }
    var pendingScanMode by remember { mutableStateOf<TerminalViewModel.ScanMode?>(null) }
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }

    // Floating text-input dialog (ported from ConnectBot). Both pieces of
    // state are hoisted ABOVE the key(activeTab.sessionId) block below: the
    // dialog itself renders *inside* key() so its send closure can never
    // outlive its tab (a non-tap active-tab change — session death clamping
    // the index, an MCP selectTabBySessionId — tears the dialog down instead
    // of silently re-binding typed text to the wrong session), while the
    // per-tab draft map up here means switching back to a tab restores what
    // was typed there. rememberSaveable (not remember) because drafts are
    // user-authored text that must survive rotation and process death —
    // unlike the attachSheetVisible boolean above, losing them is not
    // harmless. HashMap is Serializable, so the autoSaver handles it.
    var textInputDialogVisible by rememberSaveable { mutableStateOf(false) }
    var textInputDrafts by rememberSaveable { mutableStateOf(HashMap<String, String>()) }

    val cameraScanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraOutputUri
        val mode = pendingScanMode
        cameraOutputUri = null
        pendingScanMode = null
        if (success && uri != null && mode != null) {
            viewModel.runScanFlow(uri, mode)
        }
    }

    val galleryScanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val mode = pendingScanMode
        pendingScanMode = null
        if (uri != null && mode != null) {
            viewModel.runScanFlow(uri, mode)
        }
    }

    // Inject recognised text at the cursor on the active tab, honouring
    // bracket-paste so a runaway newline can't be mis-interpreted as Enter
    // by the remote app. Drains the StateFlow via consumeScanInjection so
    // a second result doesn't fire on recomposition.
    val noActiveTabMessage = stringResource(R.string.terminal_paste_no_active_tab)
    val pendingScanInjection by viewModel.pendingScanInjection.collectAsState()
    LaunchedEffect(pendingScanInjection) {
        val text = pendingScanInjection ?: return@LaunchedEffect
        val tab = viewModel.tabs.value.getOrNull(viewModel.activeTabIndex.value)
        if (tab != null) {
            val payload = if (tab.bracketPasteMode.value) {
                "[200~$text[201~"
            } else {
                text
            }
            tab.sendInput(payload.toByteArray())
        } else {
            viewModel.showNewTabMessage(noActiveTabMessage)
        }
        viewModel.consumeScanInjection()
    }

    // Inject the uploaded file's remote path at the cursor on the active
    // tab once the Attach → "Send a file" flow finishes, honouring
    // bracket-paste the same way pendingScanInjection does above. Drains
    // the StateFlow via consumeAttachInjection so a second attach doesn't
    // fire on recomposition.
    val pendingAttachInjection by viewModel.pendingAttachInjection.collectAsState()
    LaunchedEffect(pendingAttachInjection) {
        val text = pendingAttachInjection ?: return@LaunchedEffect
        val tab = viewModel.tabs.value.getOrNull(viewModel.activeTabIndex.value)
        if (tab != null) {
            val payload = if (tab.bracketPasteMode.value) {
                "[200~$text[201~"
            } else {
                text
            }
            tab.sendInput(payload.toByteArray())
        } else {
            viewModel.showNewTabMessage(noActiveTabMessage)
        }
        viewModel.consumeAttachInjection()
    }

    // Read at composition time — stringResource is @Composable and so
    // can't be invoked from inside the click-time launchCameraScan
    // callback below. Lint also bans context.getString from a Compose
    // tree (LocalContextGetResourceValueCall).
    val noCameraMessage = stringResource(R.string.terminal_scan_no_camera)
    // Hoisted for the same reason: used from click-time callbacks below where
    // @Composable stringResource can't be called and context.getString is
    // lint-banned (LocalContextGetResourceValueCall).
    val linkOpenFailedMessage = stringResource(R.string.terminal_link_open_failed)
    val linkSchemeBlockedMessage = stringResource(R.string.terminal_link_scheme_blocked)
    val localVncFailedMessage = stringResource(R.string.terminal_local_vnc_failed)
    fun launchCameraScan(mode: TerminalViewModel.ScanMode) {
        val cacheRoot = java.io.File(context.cacheDir, "scan").apply { mkdirs() }
        val file = java.io.File(cacheRoot, "scan_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        cameraOutputUri = uri
        pendingScanMode = mode
        try {
            cameraScanLauncher.launch(uri)
        } catch (_: android.content.ActivityNotFoundException) {
            cameraOutputUri = null
            pendingScanMode = null
            android.widget.Toast.makeText(
                context,
                noCameraMessage,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun launchGalleryScan(mode: TerminalViewModel.ScanMode) {
        pendingScanMode = mode
        galleryScanLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
            ),
        )
    }

    if (attachSheetVisible) {
        sh.haven.feature.terminal.attach.AttachOptionsSheet(
            onDismiss = { attachSheetVisible = false },
            onSelect = { option ->
                attachSheetVisible = false
                when (option) {
                    sh.haven.feature.terminal.attach.AttachOption.SEND_FILE ->
                        attachLauncher.launch(arrayOf("*/*"))
                    sh.haven.feature.terminal.attach.AttachOption.SCAN_CAMERA ->
                        launchCameraScan(TerminalViewModel.ScanMode.BARCODE)
                    sh.haven.feature.terminal.attach.AttachOption.SCAN_GALLERY ->
                        launchGalleryScan(TerminalViewModel.ScanMode.BARCODE)
                    sh.haven.feature.terminal.attach.AttachOption.OCR_CAMERA ->
                        launchCameraScan(TerminalViewModel.ScanMode.OCR)
                    sh.haven.feature.terminal.attach.AttachOption.OCR_GALLERY ->
                        launchGalleryScan(TerminalViewModel.ScanMode.OCR)
                }
            },
        )
    }

    // Resolve the terminal typeface: user-supplied font path wins
    // (covers any TTF/OTF the user picked); falls back to the bundled
    // Hack Nerd Font Mono so Powerline / Devicons / Font Awesome
    // glyphs in shell prompts render out of the box (#123), then
    // platform monospace if even that fails to load. Re-read whenever
    // the path changes — callers observe the preference Flow, so a
    // Settings change triggers a recomposition with a new typeface
    // here.
    val hackTypeface = remember(terminalFontPath) {
        terminalFontPath
            ?.let { runCatching { android.graphics.Typeface.createFromFile(it) }.getOrNull() }
            ?: ResourcesCompat.getFont(context, sh.haven.core.ui.R.font.hack_nerd_font_mono_regular)
            ?: android.graphics.Typeface.MONOSPACE
    }

    // Keep the screen awake while a terminal tab is in the foreground when
    // the user has opted in (Settings → "Keep screen on in terminal", #122).
    // DisposableEffect tied to the View ensures the flag is cleared as soon
    // as TerminalScreen leaves composition (navigating to another tab,
    // process death recreating). Tabs without an active session still keep
    // the screen on — the request is "while in the terminal", not "while a
    // shell is connected", so a deliberately quiet session doesn't blank.
    DisposableEffect(view, keepScreenOnInTerminal) {
        view.keepScreenOn = keepScreenOnInTerminal
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(navigateToConnections) {
        if (navigateToConnections) {
            onNavigateToConnections()
            viewModel.onNavigatedToConnections()
        }
    }

    // Show/hide keyboard when this tab becomes active/inactive.
    // When this tab goes inactive (user navigated to Desktop / Connections /
    // SFTP / etc.), also clear focus: the terminal's hidden ImeInputView
    // keeps focus across pager pages otherwise, so when another screen later
    // shows the soft keyboard the IME re-binds to the terminal and the
    // user's keystrokes appear in the terminal instead of the visible
    // screen (e.g. typing on a VNC/Wayland desktop tab landed in the
    // terminal).
    val focusManager = LocalFocusManager.current

    // #511: on a Meta Quest 3 with a physical keyboard, every keypress popped the
    // soft keyboard over the session. Where a real keyboard is attached and
    // usable the soft one is an obstruction, not the input method, so it is never
    // force-shown — the user can still raise it deliberately from the toolbar.
    //
    // hardKeyboardHidden is part of the test on purpose: a docked/folded device
    // reports KEYBOARD_QWERTY with the keys physically inaccessible, and there the
    // soft keyboard is the only way to type.
    val screenConfiguration = LocalConfiguration.current
    val physicalKeyboardAttached =
        screenConfiguration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            screenConfiguration.hardKeyboardHidden ==
            android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

    LaunchedEffect(isActive, physicalKeyboardAttached) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (isActive && tabs.isNotEmpty()) {
            if (!physicalKeyboardAttached) {
                controller.show(WindowInsetsCompat.Type.ime())
            }
        } else if (!isActive) {
            controller.hide(WindowInsetsCompat.Type.ime())
            focusManager.clearFocus(force = true)
        }
    }

    // Track keyboard-hide transitions. (This used to also fire an automatic
    // Ctrl+L "redraw" at Zellij profiles — removed for #554, see the note at
    // TerminalViewModel; zellij reflows fine on SIGWINCH by itself.)
    val imeVisible = WindowInsets.isImeVisible
    var wasImeVisible by remember { mutableStateOf(imeVisible) }
    // When the IME last went away, so the pause handler below can tell "the user
    // dismissed it" from "the system took it as we were backgrounded" (#515).
    var imeHiddenAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(imeVisible) {
        if (wasImeVisible && !imeVisible) {
            imeHiddenAtMs = android.os.SystemClock.elapsedRealtime()
        }
        wasImeVisible = imeVisible
    }

    // #515: Android drops the IME when Haven goes to the background and nothing
    // ever brought it back, so returning to a session always found the keyboard
    // down even though the user had left it up.
    //
    // The first attempt at this sampled the insets at ON_PAUSE, on the assumption
    // that they were still intact there. @paour's logcat says otherwise:
    //
    //     I TerminalIme: ime pause: imeVisible=false (recording)
    //
    // The system has already taken the IME away by the time we are paused, so
    // that version recorded "keyboard down" every time and had nothing to
    // restore. It shipped in v5.87.2 and did nothing for two releases.
    //
    // So the question at pause is not "is the IME up right now" but "was it up
    // until a moment ago". A hide caused by backgrounding lands within a few
    // hundred milliseconds of the pause; a hide the *user* asked for happened
    // whenever they tapped, which is almost never that recent. Hence the
    // lookback rather than an instantaneous read.
    //
    // rememberSaveable so the answer survives the process death that a
    // long backgrounding tends to end in; a plain remember would restore
    // "keyboard down" and look like the original bug.
    val currentImeVisible by rememberUpdatedState(imeVisible)
    val currentImeHiddenAtMs by rememberUpdatedState(imeHiddenAtMs)
    var imeVisibleBeforeBackground by rememberSaveable { mutableStateOf(false) }
    // Handed to the active tab's HavenTerminal: a bump asks termlib to re-run
    // its own ImeInputView.showIme(), the only show that works here.
    var imeRestoreTick by remember { mutableIntStateOf(0) }
    // A consumed tick must not replay: the inactive call sites pass 0, so a
    // later tab switch would step a page's value 0 -> stale-N and pop the
    // keyboard. Discard on any tab change.
    LaunchedEffect(activeTabIndex) { imeRestoreTick = 0 }
    androidx.lifecycle.compose.LifecycleResumeEffect(isActive, physicalKeyboardAttached) {
        val willRestore = imeVisibleBeforeBackground && isActive && !physicalKeyboardAttached
        // Device-verified on v5.87.8 (adb-driven app-switcher round trip,
        // ImeTracker): any show issued from this layer — at ON_RESUME or even
        // deferred to window-focus-gain — dies in the IMM with "Ignoring
        // showSoftInput() as view android:id/content is not served", because
        // ImeInputView deliberately drops focus and focusability while its
        // window is invisible (its Android 16 warm-return crash guard), so at
        // restore time the window holds no servable editor. Only the view's
        // own showIme() can recover: it re-arms its focusability first. So
        // this block decides WHETHER to restore and bumps imeRestoreTick once
        // the window has focus; termlib does the actual show.
        //
        //  recorded=false  the insets had already dropped the IME by the time we
        //                  were paused, so there was never anything to restore.
        //  recorded=true   the restore path ran; await-focus below says how.
        //
        // Booleans only — this line is meant for logs users attach to issues (#518).
        var awaitingFocus: android.view.ViewTreeObserver.OnWindowFocusChangeListener? = null
        val action = when {
            !willRestore -> "skip"
            view.hasWindowFocus() -> "show"
            else -> "await-focus"
        }
        android.util.Log.i(
            "TerminalIme",
            "ime restore: recorded=$imeVisibleBeforeBackground active=$isActive " +
                "physicalKeyboard=$physicalKeyboardAttached action=$action",
        )
        if (willRestore) {
            if (view.hasWindowFocus()) {
                imeRestoreTick++
            } else {
                val listener = object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (!hasFocus) return
                        view.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                        awaitingFocus = null
                        android.util.Log.i("TerminalIme", "ime restore: window focused -> tick")
                        imeRestoreTick++
                    }
                }
                awaitingFocus = listener
                view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            }
        }
        onPauseOrDispose {
            awaitingFocus?.let { view.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
            awaitingFocus = null
            // Up now, or taken away so recently that the backgrounding is the
            // only plausible cause.
            val hiddenAgoMs = android.os.SystemClock.elapsedRealtime() - currentImeHiddenAtMs
            val takenByTheSystem = currentImeHiddenAtMs > 0L && hiddenAgoMs <= IME_BACKGROUND_HIDE_WINDOW_MS
            val wasUp = currentImeVisible || takenByTheSystem
            android.util.Log.i(
                "TerminalIme",
                "ime pause: imeVisible=$currentImeVisible hiddenAgo=${
                    if (currentImeHiddenAtMs > 0L) "${hiddenAgoMs}ms" else "never"
                } -> recording=$wasUp",
            )
            imeVisibleBeforeBackground = wasUp
        }
    }

    // Navigate to specific tab if requested
    LaunchedEffect(navigateToProfileId) {
        if (navigateToProfileId != null) {
            viewModel.selectTabByProfileId(navigateToProfileId)
        }
    }

    // Open new session (new tab) for profile if requested from Connections screen
    LaunchedEffect(newSessionProfileId) {
        if (newSessionProfileId != null) {
            viewModel.addSshTabForProfile(newSessionProfileId)
        }
    }

    // Open a local-shell tab for the Desktop → Manage shell button (#168).
    // Driven by a pending profile id from HavenNavHost rather than the
    // AgentUiCommandBus: the bus is replay=0 and this ViewModel only collects
    // it while this screen is composed, so a request fired from the Desktop
    // tab was dropped before TerminalScreen existed. Consuming a pending id
    // here runs once the screen is composed after the pager switch.
    LaunchedEffect(openLocalShellProfileId) {
        if (openLocalShellProfileId != null) {
            viewModel.addLocalTabForProfile(openLocalShellProfileId, desktopDeId = openLocalShellDeId)
        }
    }

    // VNC settings dialog
    vncDialogInfo?.let { info ->
        VncSettingsDialog(
            host = info.host,
            initialPort = info.port,
            initialUsername = info.username,
            initialPassword = info.password,
            initialSshForward = info.sshForward,
            initialColorDepth = info.colorDepth,
            onConnect = { port, username, password, sshForward, colorDepth, save ->
                if (save) {
                    viewModel.saveVncSettings(info.profileId, port, username, password, sshForward, colorDepth)
                }
                vncDialogInfo = null
                onNavigateToVnc(info.host, port, username, password, sshForward, info.sessionId, colorDepth)
            },
            onDismiss = { vncDialogInfo = null },
        )
    }

    // Session picker dialog for new tab — shared composable from core/ui
    // (consolidated with the connect-time picker in ConnectionsScreen).
    newTabSessionPicker?.let { selection ->
        sh.haven.core.ui.SessionPickerDialog(
            title = stringResource(R.string.terminal_sessions_title, selection.managerLabel),
            sessionNames = selection.sessionNames,
            suggestedNewName = selection.suggestedNewName,
            createButtonContentDescription = stringResource(R.string.terminal_new_session_create),
            onSelect = { name -> viewModel.onNewTabSessionSelected(selection.sessionId, name) },
            onNewSession = { name ->
                viewModel.onNewTabSessionSelected(selection.sessionId, name.takeIf { it.isNotBlank() })
            },
            onDismiss = { viewModel.dismissNewTabSessionPicker() },
            canKill = selection.manager.killCommand != null,
            canRename = selection.manager.renameCommand != null,
            killContentDescription = stringResource(R.string.terminal_kill_session),
            renameContentDescription = stringResource(R.string.terminal_rename_session),
            onKill = { name -> viewModel.killRemoteSession(name) },
            onRename = { old, new -> viewModel.renameRemoteSession(old, new) },
            error = selection.error,
            plainShellLabel = stringResource(R.string.terminal_open_plain_shell),
            onPlainShell = { viewModel.onNewTabPlainShellSelected(selection.sessionId) },
            cancelLabel = stringResource(R.string.common_cancel),
            renameDialog = { currentLabel, onDismiss, onRenameTo ->
                RenameSessionDialog(currentLabel = currentLabel, onDismiss = onDismiss, onRename = onRenameTo)
            },
        )
    }

    // Security-key (FIDO2) touch/PIN prompt for a new-tab fresh dial that needs
    // an assertion — same dialog the Connections screen shows, so the auth no
    // longer blocks silently when triggered from the terminal.
    fidoTouchPrompt?.let { prompt ->
        sh.haven.feature.connections.FidoTouchPromptDialog(
            prompt = prompt,
            onCancel = { viewModel.cancelFido() },
        )
    }

    // Resolve the active terminal foreground/background. For static
    // schemes use the enum's fixed longs; for MATERIAL_YOU pull live
    // values from the system theme so the terminal stays in sync with
    // the wallpaper-driven dynamic palette. The active tab's
    // [colorScheme] (#144 — per-profile override) wins over the global
    // pref so the surrounding chrome matches whatever the emulator is
    // rendering for that tab.
    val activeTabScheme = tabs.getOrNull(activeTabIndex)?.colorScheme ?: colorScheme
    val terminalBg: Color = if (activeTabScheme.isDynamic) MaterialTheme.colorScheme.surface
        else Color(activeTabScheme.background)
    val terminalFg: Color = if (activeTabScheme.isDynamic) MaterialTheme.colorScheme.onSurface
        else Color(activeTabScheme.foreground)

    // Terminal background opacity (see-through to wallpaper). The active
    // tab's per-profile override wins over the global pref, mirroring the
    // colour-scheme resolution above.
    val globalBgOpacity by viewModel.terminalBackgroundOpacity.collectAsState()
    val effectiveBgOpacity =
        (tabs.getOrNull(activeTabIndex)?.backgroundOpacity ?: globalBgOpacity).coerceIn(0f, 1f)

    // When the active terminal opts into < 1.0 opacity, the wallpaper shows
    // behind it. The window is made translucent + wallpaper-backed statically
    // in Theme.Haven (windowShowWallpaper); toggling it at runtime left the
    // surface opaque and ghosted a stale buffer. Here we only need to tell the
    // host to drop the opaque Scaffold background behind the terminal so the
    // wallpaper actually reaches the eye. Gated on isActive so other panes
    // (SFTP/Desktop) keep their opaque background.
    val showWallpaper = isActive && effectiveBgOpacity < 1f
    LaunchedEffect(showWallpaper) { onTransparentChanged(showWallpaper) }
    DisposableEffect(Unit) { onDispose { onTransparentChanged(false) } }

    // Carry the terminal's own background up to the host so it reaches behind
    // the status bar, and match the status-bar icons to it (#523).
    //
    // Only while this pane is actually showing, and only when it is opaque —
    // a translucent terminal already hands the host Color.Transparent above,
    // and painting a solid colour underneath would defeat that.
    val statusBarBg = if (isActive && effectiveBgOpacity >= 1f) terminalBg else null
    LaunchedEffect(statusBarBg) { onBackgroundColorChanged(statusBarBg) }
    DisposableEffect(Unit) { onDispose { onBackgroundColorChanged(null) } }

    // Icon contrast follows the colour scheme's measured luminance rather than
    // the app's light/dark theme. Deciding from the theme would put dark icons
    // on a black terminal whenever the app is in light mode, which is the
    // reported bug in mirror image; deciding from a scheme's *name* would fail
    // for the light schemes (Solarized Light, Modern Light) that need the
    // opposite treatment. `isAppearanceLightStatusBars` means "light
    // background, so draw dark icons".
    // Falling back to the app's own background when this pane isn't showing
    // keeps it symmetric: the icons always contrast whatever is actually
    // painted behind them, so leaving the terminal restores the theme's
    // treatment without having to remember what it was.
    val appBg = MaterialTheme.colorScheme.background
    LaunchedEffect(statusBarBg, appBg, window) {
        if (window == null) return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
            (statusBarBg ?: appBg).luminance() > BRIGHT_BACKGROUND_LUMINANCE
    }

    val saveConnectionUi by viewModel.saveConnectionUi.collectAsState()
    saveConnectionUi?.let { ui ->
        SaveConnectionDialog(
            ui = ui,
            onDismiss = { viewModel.dismissSaveConnection() },
            onConfirm = { name -> viewModel.confirmSaveConnection(name) },
        )
    }
    val whereaboutsUi by viewModel.whereaboutsUi.collectAsState()
    whereaboutsUi?.let { w ->
        WhereaboutsDialog(
            whereabouts = w,
            onDismiss = { viewModel.dismissWhereabouts() },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (tabs.isEmpty()) {
            EmptyTerminalState(
                fontSize = fontSize,
                // Match the terminal's own translucency so the empty (no-session)
                // state shows the wallpaper too, instead of a solid block.
                backgroundColor = terminalBg.copy(alpha = effectiveBgOpacity),
                foregroundColor = terminalFg,
                // Carry the page-swipe override so the empty Terminal page can
                // still be swiped to an adjacent tab. The pager's built-in
                // scroll is disabled on the Terminal page (see HavenNavHost
                // userScrollEnabled), so without this the empty state would be
                // un-swipeable.
                swipeModifier = terminalModifier,
            )
        } else {
            // Tab row — can be hidden via Settings when the user wants more terminal space
            val profileColors = remember(tabs) {
                tabs.associate { tab ->
                    val color = if (tab.colorTag in 1..TAB_GROUP_COLORS.size) {
                        TAB_GROUP_COLORS[tab.colorTag - 1]
                    } else {
                        null // No color assigned — use default theme
                    }
                    tab.profileId to color
                }
            }
            val clampedIndex = activeTabIndex.coerceIn(0, tabs.size - 1)
            val indicatorColor = profileColors[tabs.getOrNull(clampedIndex)?.profileId]

            if (showTabBar && !fullscreen) {
                // Tabs used to hug their label, so a short name like "cctv" gave a
                // tap target barely wider than the four characters — easy to miss
                // on a phone. Share the strip out evenly instead, while the tabs
                // still get a comfortable width; past that many tabs, fall back to
                // the scrolling strip rather than shaving them down to slivers.
                val stripWidth = LocalConfiguration.current.screenWidthDp.dp - TAB_STRIP_PADDING * 2
                val justified = tabs.isNotEmpty() &&
                    stripWidth / tabs.size >= MIN_JUSTIFIED_TAB_WIDTH
                // A menu bound to the loop position would follow the SLOT, not the
                // tab: reordering slides a different tab underneath it and the next
                // press moves the wrong one. Keyed by session id so it tracks the
                // tab across moves, which is what lets it stay open for several.
                var tabMenuFor by remember { mutableStateOf<String?>(null) }
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (justified) Modifier
                                else Modifier.horizontalScroll(rememberScrollState()),
                            )
                            .padding(horizontal = TAB_STRIP_PADDING, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val reconnecting by tab.isReconnecting.collectAsState()
                            // OSC 0/2 title from the running program (#625). Falls
                            // back to the session label when the program has not
                            // set one; a user rename is overridden while a title
                            // is live, matching Termux's session-drawer behaviour.
                            // With "prefer session names" on, tabs attached to a
                            // session manager keep their session name instead
                            // (resolveTabTitle). Collection continues while the
                            // preference is on so a flipped pref repaints instantly.
                            val tabTitle by remember(tab.sessionId) {
                                tab.emulator.terminalTitle.map { it.ifBlank { null } }
                            }.collectAsState(initial = null)
                            val selected = activeTabIndex == index
                            val showTabMenu = tabMenuFor == tab.sessionId
                            var renameDialogFor by remember { mutableStateOf<String?>(null) }
                            val tabColor = profileColors[tab.profileId]

                            renameDialogFor?.let { name ->
                                RenameSessionDialog(
                                    currentLabel = name,
                                    onDismiss = { renameDialogFor = null },
                                    onRename = { newName ->
                                        viewModel.renameAttachedSession(tab.sessionId, newName)
                                        renameDialogFor = null
                                    },
                                )
                            }

                            Box(modifier = if (justified) Modifier.weight(1f) else Modifier) {
                                Surface(
                                    modifier = Modifier
                                        .then(if (justified) Modifier.fillMaxWidth() else Modifier)
                                        .padding(horizontal = 2.dp)
                                        .combinedClickable(
                                            onClick = { viewModel.selectTab(index) },
                                            onLongClick = { tabMenuFor = tab.sessionId },
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
                                        // Blend tab color over surface to get effective luminance
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
                                        // Left-aligned text in a tab that now spans a
                                        // third of the screen reads as misplaced; centre
                                        // it only when the tab is actually stretched.
                                        horizontalArrangement =
                                            if (justified) Arrangement.Center else Arrangement.Start,
                                    ) {
                                        if (reconnecting) {
                                            Icon(
                                                Icons.Filled.Autorenew,
                                                contentDescription = stringResource(R.string.terminal_reconnecting),
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        Text(
                                            resolveTabTitle(
                                                programTitle = tabTitle,
                                                label = tab.label,
                                                multiplexerName = tab.multiplexerName,
                                                followSession = tabTitlesFollowSession,
                                            ),
                                            maxLines = 1,
                                            // A stretched tab has a fixed width, so a long
                                            // label must give way to the close button
                                            // rather than push it off the edge.
                                            overflow = TextOverflow.Ellipsis,
                                            // weight ONLY when justified. In the scrolling
                                            // fallback this Row sits inside horizontalScroll,
                                            // where the width constraint is infinite — and a
                                            // weighted child of an unbounded Row measures to
                                            // ZERO. That blanked the label entirely and
                                            // collapsed the tab onto its close button, which
                                            // is what enough tabs to disable justification
                                            // looked like in practice.
                                            modifier = if (justified) {
                                                Modifier.weight(1f, fill = false)
                                            } else {
                                                Modifier
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        // #306: a one-tap close on the active tab so
                                        // ending a session no longer needs the
                                        // long-press menu. Only on the selected tab to
                                        // keep the strip compact in portrait.
                                        if (selected) {
                                            Spacer(Modifier.width(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clickable { viewModel.closeTab(tab.sessionId) },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = stringResource(R.string.terminal_close),
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                // Refresh remote sessions when popup opens
                                LaunchedEffect(showTabMenu) {
                                    if (showTabMenu) viewModel.refreshRemoteSessions()
                                }
                                // Long-press action bar
                                DropdownMenu(
                                    expanded = showTabMenu,
                                    onDismissRequest = { tabMenuFor = null },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                tabMenuFor = null
                                                viewModel.addTab()
                                            },
                                            enabled = !newTabLoading,
                                        ) {
                                            if (newTabLoading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                if (newTabLoading) stringResource(R.string.terminal_new_tab_connecting)
                                                else stringResource(R.string.terminal_sessions),
                                            )
                                        }
                                        Row {
                                            IconButton(
                                                onClick = { viewModel.moveTab(index, -1) },
                                                enabled = index > 0,
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.terminal_move_left), modifier = Modifier.size(18.dp))
                                            }
                                            IconButton(
                                                onClick = { viewModel.moveTab(index, 1) },
                                                enabled = index < tabs.size - 1,
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.terminal_move_right), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        TextButton(
                                            onClick = {
                                                tabMenuFor = null
                                                viewModel.closeTab(tab.sessionId)
                                            },
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error,
                                            ),
                                        ) {
                                            Text(stringResource(R.string.terminal_close))
                                            Spacer(Modifier.width(4.dp))
                                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    // Details (door/path/room) + Rename + Save connection
                                    val renameableName = viewModel.renameableSessionName(tab.sessionId)
                                    val canSaveConnection = viewModel.canSaveConnection(tab.sessionId)
                                    val canShowDetails = viewModel.canShowDetails(tab.sessionId)
                                    if (renameableName != null || canSaveConnection || canShowDetails ||
                                        viewModel.canOpenPlainShell(tab.sessionId)
                                    ) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    }
                                    if (canShowDetails) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.terminal_details)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.Info,
                                                    null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                tabMenuFor = null
                                                viewModel.beginShowDetails(tab.sessionId)
                                            },
                                        )
                                    }
                                    if (renameableName != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.common_rename)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.DriveFileRenameOutline,
                                                    null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                tabMenuFor = null
                                                renameDialogFor = renameableName
                                            },
                                        )
                                    }
                                    if (viewModel.canOpenPlainShell(tab.sessionId)) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.terminal_open_plain_shell)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.Terminal,
                                                    null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                tabMenuFor = null
                                                viewModel.addPlainShellTab(tab.sessionId)
                                            },
                                        )
                                    }
                                    if (canSaveConnection) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.terminal_save_connection)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.Save,
                                                    null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                tabMenuFor = null
                                                viewModel.beginSaveConnection(tab.sessionId)
                                            },
                                        )
                                    }
                                    // Show connected sessions without tabs + remote sessions (tmux/zellij)
                                    val untabbed by viewModel.untabbedSessions.collectAsState()
                                    val remoteSessions by viewModel.remoteSessionNames.collectAsState()
                                    val tabbedRemoteSessions = tabs.map { it.label }.toSet()
                                    val untabbedRemote = remoteSessions.filter { it !in tabbedRemoteSessions }
                                    if (untabbed.isNotEmpty() || untabbedRemote.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        // Remote sessions (tmux/zellij/screen) on current connection
                                        untabbedRemote.forEach { name ->
                                            DropdownMenuItem(
                                                text = { Text(name, style = MaterialTheme.typography.bodySmall) },
                                                leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)) },
                                                onClick = {
                                                    tabMenuFor = null
                                                    viewModel.openRemoteSession(tab.profileId, name)
                                                },
                                            )
                                        }
                                        // Other SSH connections without tabs
                                        untabbed.forEach { session ->
                                            DropdownMenuItem(
                                                text = { Text(session.label, style = MaterialTheme.typography.bodySmall) },
                                                leadingIcon = { Icon(Icons.Filled.Cable, null, modifier = Modifier.size(16.dp)) },
                                                onClick = {
                                                    tabMenuFor = null
                                                    viewModel.selectTabByProfileId(session.profileId)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Action buttons after tabs
                        if (showCopyOutputButton) {
                            IconButton(
                                onClick = {
                                    val output = viewModel.copyLastCommandOutput()
                                    if (output != null) {
                                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clip.setPrimaryClip(ClipData.newPlainText("command output", output))
                                        @Suppress("LocalContextGetResourceValueCall")
                                        android.widget.Toast.makeText(context, context.getString(R.string.terminal_copied_output, output.length), android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        @Suppress("LocalContextGetResourceValueCall")
                                        android.widget.Toast.makeText(context, context.getString(R.string.terminal_no_command_output), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.terminal_copy_last_output),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (showSearchButton) {
                            IconButton(
                                onClick = { viewModel.sendSearchKeys() },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.terminal_search),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Terminal area
            val activeTab = tabs.getOrNull(activeTabIndex)
            if (activeTab != null) {
                // key() forces Terminal recreation when switching tabs, ensuring
                // the emulator and keyboard input are bound to the correct session.
                key(activeTab.sessionId) {
                    // Wire OSC handler callbacks
                    val clipboard = remember {
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    }
                    activeTab.oscHandler.onClipboardSet = { text ->
                        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
                    }
                    activeTab.oscHandler.onNotification = { title, body ->
                        showTerminalNotification(context, title, body, activeTab.label)
                    }

                    val focusRequester = remember { FocusRequester() }

                    // Keyed on isActive, not Unit: the HorizontalPager keeps
                    // adjacent pages composed, so returning to this screen
                    // doesn't recompose it — without re-requesting here the
                    // IME's InputConnection stays wired to the previous
                    // screen's view (desktop tab) and keys go nowhere until
                    // the user switches away and back a second time.
                    LaunchedEffect(isActive) {
                        if (isActive) focusRequester.requestFocus()
                    }

                    var selectionController by remember {
                        mutableStateOf<org.connectbot.terminal.SelectionController?>(null)
                    }

                    // The active tab's compose controller (termlib local
                    // CJK/accent buffer). Held here so the Compose toolbar key
                    // can toggle it and reflect its state; also registered into
                    // TerminalSessionRegistry for the MCP set_compose_mode verb.
                    // isComposeModeActive is Compose-state backed, so reads
                    // recompose the toolbar tint (incl. auto-exit on Enter).
                    var composeController by remember {
                        mutableStateOf<org.connectbot.terminal.ComposeController?>(null)
                    }

                    // Notify parent when selection state changes.
                    // isSelectionActive is backed by Compose MutableState, so
                    // this block recomposes when selection starts/ends.
                    val selectionActive = selectionController?.isSelectionActive == true

                    // Register Activity-level key interceptor for layout-aware
                    // character mapping. This fires in dispatchKeyEvent() BEFORE
                    // the View hierarchy, bypassing termlib's hardcoded US QWERTY
                    // symbol table.
                    val currentSelectionActive by rememberUpdatedState(selectionActive)
                    val configuration = LocalConfiguration.current
                    val hasHardwareKeyboard by rememberUpdatedState(
                        configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS,
                    )
                    val currentFullscreen by rememberUpdatedState(fullscreen)
                    DisposableEffect(activeTab) {
                        val interceptor = { event: android.view.KeyEvent ->
                            // F11 toggles fullscreen (#138). Caught here, before
                            // handleLayoutAwareKeyEvent forwards it to the shell —
                            // tradeoff: TUI apps that use F11 won't see it. Pinpox
                            // confirmed this scope; revisit if users push back.
                            if (event.keyCode == android.view.KeyEvent.KEYCODE_F11 &&
                                event.action == android.view.KeyEvent.ACTION_DOWN
                            ) {
                                fullscreen = !currentFullscreen
                                true
                            } else if (event.keyCode == android.view.KeyEvent.KEYCODE_F11) {
                                // Swallow the matching ACTION_UP so the shell never
                                // sees a half key event.
                                true
                            } else {
                                handleLayoutAwareKeyEvent(
                                    event, activeTab,
                                    currentSelectionActive, hasHardwareKeyboard, viewModel,
                                )
                            }
                        }
                        KeyEventInterceptor.handler = interceptor
                        onDispose {
                            if (KeyEventInterceptor.handler === interceptor) {
                                KeyEventInterceptor.handler = null
                            }
                        }
                    }
                    val currentHyperlinkUri by activeTab.hyperlinkUri.collectAsState()

                    LaunchedEffect(selectionActive) {
                        onSelectionActiveChanged(selectionActive)
                        if (selectionActive && selectionController != null) {
                            expandSelectionToWord(selectionController!!, activeTab.emulator)
                        }
                    }

                    val isMouseMode by activeTab.mouseMode.collectAsState()
                    val isBracketPaste by activeTab.bracketPasteMode.collectAsState()
                    val isAltScreen by activeTab.altScreen.collectAsState()

                    // #580. The per-gesture line reports the EFFECTIVE flag, which
                    // is `isMouseMode && mouseInputEnabled` — so "mouseMode=false"
                    // means either the app never asked for the mouse (DECSET
                    // ?1000/?1002/?1003 not seen) or the user has "Mouse input in
                    // TUI apps" switched off, and the two are indistinguishable.
                    // A reporter lost a round trip to exactly that ambiguity: they
                    // proved with a minimal program that the taps arrived and the
                    // app had enabled mouse tracking, which rules out one cause but
                    // cannot confirm the other. Same "HavenGesture" tag on purpose,
                    // so `adb logcat -s HavenGesture` picks it up alongside the
                    // gesture lines with no second capture to ask for.
                    // Read in composable scope, not inside the effect: resolving
                    // it from LocalContext at fire time is not configuration-aware
                    // and lint rejects it (LocalContextGetResourceValueCall).
                    val mouseDisabledHint =
                        stringResource(R.string.terminal_mouse_requested_but_disabled)

                    LaunchedEffect(activeTab, isMouseMode, mouseInputEnabled) {
                        android.util.Log.i(
                            "HavenGesture",
                            "mouseModeState tracker=$isMouseMode pref=$mouseInputEnabled " +
                                "effective=${isMouseMode && mouseInputEnabled}",
                        )
                        // #586: the app asked for the mouse and the preference is
                        // off, so every tap will be swallowed and nothing will say
                        // why. That silence is indistinguishable from broken touch
                        // input — it cost the #580 reporter a minimal reproducer,
                        // adb captures and two days before the answer turned out to
                        // be a switch. Say it once and then stay quiet: repeating it
                        // on every tab switch would be worse than saying nothing.
                        if (shouldShowMouseHint(
                                appRequestedMouse = isMouseMode,
                                preferenceEnabled = mouseInputEnabled,
                                alreadyShown = mouseHintShownThisSession,
                            )
                        ) {
                            mouseHintShownThisSession = true
                            android.widget.Toast.makeText(
                                context,
                                mouseDisabledHint,
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }

                    // Build gesture callback when mouse mode is active. Gesture
                    // routing follows the mode (#186):
                    //  • one-finger swipe → onScroll → wheel → scrolls the pane
                    //  • long-press (haptic) then drag → onMouseDrag → the
                    //    multiplexer's own pane-aware copy-mode selection
                    //  • long-press release → right-click iff terminalRightClick;
                    //    otherwise it just arms the copy-mode drag above
                    // Taps/long-press/drag are gated by mouseInputEnabled; the
                    // scroll wheel forwards whenever the app requested mouse mode.
                    // Haven's local scrollback and row-based selection are reached
                    // with a two-finger swipe / tap-and-hold in non-mouse-mode.
                    val gestureCallback = remember(activeTab, isMouseMode, isAltScreen, mouseInputEnabled, terminalRightClick, swipeArrowsMode) {
                        if (isMouseMode) object : org.connectbot.terminal.TerminalGestureCallback {
                            override fun onTap(col: Int, row: Int): Boolean {
                                if (!mouseInputEnabled) return false
                                activeTab.sendInput(sgrMouseButton(0, col + 1, row + 1, true))
                                activeTab.sendInput(sgrMouseButton(0, col + 1, row + 1, false))
                                return true
                            }
                            override fun onLongPress(col: Int, row: Int): Boolean {
                                // Returning false arms the copy-mode drag (the
                                // default); only a right-click is a discrete action
                                // that consumes the long-press.
                                if (!mouseInputEnabled) return false
                                if (!terminalRightClick) return false
                                activeTab.sendInput(sgrMouseButton(2, col + 1, row + 1, true))
                                activeTab.sendInput(sgrMouseButton(2, col + 1, row + 1, false))
                                return true // right-click handled — ignore the trailing drag
                            }
                            override fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean {
                                activeTab.sendInput(sgrMouseWheel(scrollUp, col + 1, row + 1))
                                return true // forward wheel to the mouse-mode app (tmux/vim/less)
                            }
                            override fun onMouseDrag(
                                col: Int,
                                row: Int,
                                phase: org.connectbot.terminal.MouseDragPhase,
                            ): Boolean {
                                if (!mouseInputEnabled) return false
                                when (phase) {
                                    org.connectbot.terminal.MouseDragPhase.Start ->
                                        activeTab.sendInput(sgrMouseButton(0, col + 1, row + 1, true))
                                    org.connectbot.terminal.MouseDragPhase.Move ->
                                        activeTab.sendInput(sgrMouseMotion(0, col + 1, row + 1))
                                    org.connectbot.terminal.MouseDragPhase.End ->
                                        activeTab.sendInput(sgrMouseButton(0, col + 1, row + 1, false))
                                }
                                return true // claim the drag — terminal stops scroll fallback
                            }
                        } else if (isAltScreen || swipeArrowsMode) object : org.connectbot.terminal.TerminalGestureCallback {
                            // Alt screen without mouse tracking (tmux default,
                            // nano, vim, less): Haven's local scrollback is the
                            // frozen *primary* buffer, so scrolling it paints
                            // stale history over the TUI and rubber-bands on
                            // every app redraw. Route the swipe to the app as
                            // arrow keys instead — Termux parity. Taps and
                            // long-press keep the native handling (defaults
                            // return false), so text selection still works. (#255)
                            override fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean {
                                // The tracker scans raw bytes, so it sees
                                // ESC[?1049h even when the emulator was built
                                // with enableAltScreen=false (profile opt-out,
                                // `screen` session manager) and the TUI's output
                                // actually lands in the primary buffer. Trust the
                                // emulator: decline so the swipe falls back to
                                // native local scrollback — which is exactly what
                                // those opt-outs are for.
                                // #524: swipe-arrows mode forces this branch at a
                                // plain shell prompt too — command history without
                                // arrow keys on the toolbar — so the emulator
                                // check only gates the automatic path.
                                if (!swipeArrowsMode && !activeTab.emulator.isAltScreenActive()) return false
                                activeTab.sendInput(
                                    arrowKeyBytes(
                                        // Latched mode maps the finger literally (swipe up
                                        // = ↑); the automatic path keeps natural-scroll
                                        // direction — see swipeArrowIsUp. (#524)
                                        swipeArrowIsUp(scrollUp, swipeArrowsMode),
                                        activeTab.cursorKeyAppMode.value,
                                    ),
                                )
                                return true
                            }

                            // Hold-to-repeat (#524b): in latched swipe-arrows mode
                            // the swipe direction SELECTS the arrow and holding
                            // repeats it at a fixed cadence — lift to stop.
                            // Distance stops mattering past the initial direction
                            // threshold, so a flick is one keystroke and a held
                            // drag is continuous. Reversing the finger switches
                            // arrows mid-hold. Claiming horizontal swipes here
                            // also keeps them from the tab pager (the NavHost
                            // gate suppresses the pager override too).
                            private var holdDirection: org.connectbot.terminal.SwipeHoldDirection? = null
                            private var holdJob: kotlinx.coroutines.Job? = null

                            private fun holdArrowBytes(dir: org.connectbot.terminal.SwipeHoldDirection) =
                                arrowKeyBytesFor(dir, activeTab.cursorKeyAppMode.value)

                            override fun onSwipeHoldStart(
                                col: Int,
                                row: Int,
                                direction: org.connectbot.terminal.SwipeHoldDirection,
                            ): Boolean {
                                // Only the latched mode gets hold-to-repeat; the
                                // automatic alt-screen path keeps quantized
                                // scrolling, which matches touch-scroll feel.
                                if (!swipeArrowsMode) return false
                                holdDirection = direction
                                holdJob?.cancel()
                                holdJob = coroutineScope.launch {
                                    activeTab.sendInput(holdArrowBytes(direction))
                                    kotlinx.coroutines.delay(SWIPE_HOLD_INITIAL_DELAY_MS)
                                    while (true) {
                                        holdDirection?.let { activeTab.sendInput(holdArrowBytes(it)) }
                                        kotlinx.coroutines.delay(SWIPE_HOLD_REPEAT_MS)
                                    }
                                }
                                return true
                            }

                            override fun onSwipeHold(direction: org.connectbot.terminal.SwipeHoldDirection) {
                                holdDirection = direction
                            }

                            override fun onSwipeHoldEnd() {
                                holdJob?.cancel()
                                holdJob = null
                                holdDirection = null
                            }
                        } else null
                    }

                    // Smart clipboard intercepts all terminal copy operations
                    // (toolbar button + library popup) to strip TUI borders and
                    // unwrap soft-wrapped lines.
                    val realClipboard = LocalClipboardManager.current
                    val smartClipboard = remember(activeTab, realClipboard) {
                        SmartTerminalClipboard(
                            delegate = realClipboard,
                            getEmulator = { activeTab.emulator },
                            getController = { selectionController },
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(terminalModifier),
                    ) {
                        // ModifierManager bridges our Ctrl/Alt toolbar state to the
                        // library's KeyboardHandler. clearTransients is a no-op here
                        // because the library calls it during recomposition, not after
                        // key processing. We clear modifiers ourselves via
                        // onInputProcessed below.
                        val modifierManager = remember(viewModel) {
                            object : ModifierManager {
                                override fun isCtrlActive() = viewModel.ctrlActive.value
                                override fun isAltActive() = viewModel.altActive.value
                                override fun isShiftActive() = false
                                // One-shot: clear the tapped modifier once the
                                // keystroke that consumed it has dispatched, so
                                // it can't get stuck (#298).
                                override fun clearTransients() = viewModel.clearStickyModifiers()
                            }
                        }

                        // Update native emulator colors when scheme changes.
                        // MATERIAL_YOU: pulled from MaterialTheme above and
                        // re-applied whenever the system theme shifts (light /
                        // dark / wallpaper change), so the terminal repaints.
                        //
                        // Pushing the scheme's 16-colour ANSI palette makes
                        // SGR-coloured prompts track the theme, but it also
                        // remaps the colours full-screen TUIs like mutt rely on
                        // (e.g. ANSI white → a scheme "cream"), which reads as a
                        // regression (#407). So it's opt-in: default OFF applies
                        // only the default fg/bg and leaves libvterm's stock
                        // palette, matching pre-#165 behaviour.
                        val terminalFgArgb = terminalFg.toArgb()
                        val terminalBgArgb = terminalBg.toArgb()
                        val ansiPalette = remember(activeTabScheme) { activeTabScheme.ansiPaletteArgb() }
                        val applyScheme: (TerminalEmulator) -> Unit = { emu ->
                            if (applySchemePalette) {
                                emu.applyColorScheme(ansiPalette, terminalFgArgb, terminalBgArgb)
                            } else {
                                emu.setDefaultColors(terminalFgArgb, terminalBgArgb)
                            }
                        }
                        LaunchedEffect(applySchemePalette, terminalFgArgb, terminalBgArgb, ansiPalette, activeTab.emulator) {
                            activeTab.emulator?.let(applyScheme)
                        }

                        // Force terminal redraw on resume from background
                        androidx.lifecycle.compose.LifecycleResumeEffect(activeTab.emulator) {
                            activeTab.emulator?.let(applyScheme)
                            onPauseOrDispose {}
                        }

                        // Shared paste action: read the (real, non-smart) system
                        // clipboard and send via sendInput, wrapping in bracketed-
                        // paste when the remote app has mode 2004 active.
                        val doPaste: () -> Unit = {
                            val text = realClipboard.getText()?.text
                            if (!text.isNullOrEmpty()) {
                                activeTab.sendInput(
                                    bracketPasteWrap(text, isBracketPaste).toByteArray(),
                                )
                            }
                        }

                        // Hardware Ctrl+Shift+V shortcut. Null when the user has
                        // turned the shortcut off, so the V key passes through to
                        // the shell unchanged.
                        val pasteShortcut: (() -> Unit)? = if (interceptCtrlShiftV) doPaste else null

                        val keyboardMode = when {
                            rawKeyboardMode -> HavenKeyboardMode.Raw
                            allowStandardKeyboard -> HavenKeyboardMode.Standard
                            customKeyboardFlags != null -> HavenKeyboardMode.Custom(customKeyboardFlags!!)
                            else -> HavenKeyboardMode.Secure
                        }
                        CompositionLocalProvider(LocalClipboardManager provides smartClipboard) {
                            HavenTerminal(
                                terminalEmulator = activeTab.emulator,
                                modifier = Modifier.fillMaxSize(),
                                initialFontSize = fontSize.sp,
                                typeface = hackTypeface,
                                keyboardEnabled = true,
                                // Only the active tab restores; adjacent pager
                                // pages stay composed and must not fight over
                                // the IME (#515).
                                imeRestoreTick = if (isActive) imeRestoreTick else 0,
                                // Reflow (resize) the PTY on a keyboard toggle
                                // when a full-screen TUI is running, so its top
                                // status/header row isn't shifted off the top
                                // (#206 / tmux copy-mode indicator / mutt header
                                // #407). The alt screen is the reliable
                                // full-screen signal — mutt, vim, less, htop and
                                // nested compositors all switch to it, so reflow
                                // it automatically (termlib reflows alt-screen
                                // content correctly on resize). Mouse tracking
                                // additionally catches the primary-buffer
                                // multiplexers (Haven forces `mouse on` for
                                // tmux/byobu). A plain-shell primary-buffer TUI
                                // the heuristics miss (e.g. `top`) keeps the
                                // no-resize render-shift unless the user opts in
                                // (#242).
                                reflowOnKeyboard = isMouseMode || isAltScreen || reflowTerminalOnKeyboard,
                                backgroundColor = terminalBg,
                                foregroundColor = terminalFg,
                                backgroundOpacity = effectiveBgOpacity,
                                focusRequester = focusRequester,
                                modifierManager = modifierManager,
                                onPasteShortcut = pasteShortcut,
                                onPasteRequest = doPaste,
                                onSelectionControllerAvailable = {
                                    selectionController = it
                                    viewModel.terminalSessionRegistry.setSelectionController(activeTab.sessionId, it)
                                },
                                onScrollControllerAvailable = {
                                    viewModel.terminalSessionRegistry.setScrollController(activeTab.sessionId, it)
                                },
                                onGestureInjectorReady = {
                                    viewModel.terminalSessionRegistry.setGestureInjector(activeTab.sessionId, it)
                                },
                                onComposeControllerAvailable = {
                                    composeController = it
                                    viewModel.terminalSessionRegistry.setComposeController(activeTab.sessionId, it)
                                },
                                onTerminalDoubleTap = {
                                    val window = (view.context as? Activity)?.window ?: return@HavenTerminal
                                    val controller = WindowCompat.getInsetsController(window, view)
                                    val rootView = window.decorView
                                    val imeShowing = androidx.core.view.ViewCompat
                                        .getRootWindowInsets(rootView)
                                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                                    if (imeShowing) {
                                        controller.hide(WindowInsetsCompat.Type.ime())
                                    } else {
                                        focusRequester.requestFocus()
                                        controller.show(WindowInsetsCompat.Type.ime())
                                    }
                                },
                                onFontSizeChanged = { newSize ->
                                    viewModel.setFontSize(newSize.value.toInt())
                                },
                                onHyperlinkClick = { url ->
                                    // OSC 8 link targets come straight from untrusted server
                                    // output. Only launch a vetted scheme, and never let a
                                    // missing handler crash the app. (#208 finding 16)
                                    val finalUrl = if (url.contains("://")) url else "https://$url"
                                    val scheme = Uri.parse(finalUrl).scheme?.lowercase()
                                    if (scheme in setOf("http", "https", "mailto", "tel")) {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)))
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            android.widget.Toast.makeText(
                                                context,
                                                linkOpenFailedMessage,
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            linkSchemeBlockedMessage,
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                                gestureCallback = gestureCallback,
                                // Coarser per-arrow travel only while the latched
                                // swipe-arrows callback is the active branch — a
                                // mouse-tracking app keeps the fine wheel quantum
                                // even with the latch on. (#524)
                                scrollQuantumMultiplier =
                                    if (swipeArrowsMode && !isMouseMode) SWIPE_ARROWS_SCROLL_QUANTUM else 1f,
                                // The real DECSET mouse-tracking signal, not
                                // "a gestureCallback exists" — one is installed
                                // for alt-screen-only apps too, and those have
                                // no click to protect. (#435)
                                mouseModeActive = isMouseMode && mouseInputEnabled,
                                keyboardMode = keyboardMode,
                                tapToPositionCursorOnPrompt = tapToPositionCursorOnPrompt,
                            )
                        }

                        StallBanner(
                            stallFlow = activeTab.stallSeconds,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )

                        // Fullscreen toggle (#138). Small low-opacity overlay in
                        // a corner (default top-right; the TopCenter slot is the
                        // disconnect banner's, so they never collide). Tap toggles
                        // fullscreen; hold-drag moves it to another corner, snapping
                        // to whichever it's released nearest and remembering it (#445).
                        var fsDragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                        var fsButtonCoords by remember {
                            mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)
                        }
                        IconButton(
                            onClick = { fullscreen = !fullscreen },
                            modifier = Modifier
                                .align(fullscreenButtonCorner.toComposeAlignment())
                                .offset { androidx.compose.ui.unit.IntOffset(fsDragOffset.x.toInt(), fsDragOffset.y.toInt()) }
                                .onGloballyPositioned { fsButtonCoords = it }
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDrag = { change, delta ->
                                            change.consume()
                                            fsDragOffset += delta
                                        },
                                        onDragEnd = {
                                            val coords = fsButtonCoords
                                            val parent = coords?.parentLayoutCoordinates
                                            if (coords != null && parent != null) {
                                                val c = coords.boundsInParent().center
                                                onFullscreenButtonCornerChange(
                                                    sh.haven.core.data.preferences.FullscreenButtonCorner.nearest(
                                                        c.x, c.y,
                                                        parent.size.width.toFloat(),
                                                        parent.size.height.toFloat(),
                                                    ),
                                                )
                                            }
                                            fsDragOffset = androidx.compose.ui.geometry.Offset.Zero
                                        },
                                    )
                                }
                                .padding(2.dp)
                                .size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (fullscreen) Icons.Filled.FullscreenExit
                                else Icons.Filled.Fullscreen,
                                contentDescription = stringResource(
                                    if (fullscreen) R.string.terminal_exit_fullscreen
                                    else R.string.terminal_enter_fullscreen,
                                ),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // Keep the extra key rows in fullscreen — Ctrl/Esc/arrows are
                    // needed while typing. Fullscreen still hides the system + tab bars.
                    KeyboardToolbar(
                        onSendBytes = { bytes -> activeTab.sendInput(bytes) },
                        // The toolbar's own keys dispatch with mods = 0, so a tapped
                        // Ctrl/Alt never reached them — Ctrl+End sent a bare End. Fold
                        // the active modifiers in here (bit 1 = Alt, bit 2 = Ctrl, per
                        // Terminal.cpp's dispatchKey) and let libvterm build the
                        // sequence: Ctrl+End becomes ESC[1;5F.
                        onDispatchKey = { mods, key ->
                            val tapped = viewModel.toolbarModifierMask()
                            activeTab.emulator?.dispatchKey(mods or tapped, key)
                            if (tapped != 0) viewModel.clearStickyModifiers()
                        },
                        focusRequester = focusRequester,
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        ctrlLocked = ctrlLocked,
                        altLocked = altLocked,
                        bracketPasteMode = isBracketPaste,
                        layout = toolbarLayout,
                        navBlockMode = navBlockMode,
                        uniformGrid = toolbarUniformGrid,
                        editModeControlsPlacement = editModeControlsPlacement,
                        desktopKeyPlacement = desktopKeyPlacement,
                        minKeyWidth = toolbarMinKeyWidth.dp,
                        onToggleCtrl = viewModel::toggleCtrl,
                        onToggleAlt = viewModel::toggleAlt,
                        onVncTap = if (activeTab.transportType == "SSH") {{
                            coroutineScope.launch {
                                val info = viewModel.getActiveVncInfo() ?: return@launch
                                if (info.stored) {
                                    onNavigateToVnc(info.host, info.port, info.username, info.password, info.sshForward, info.sessionId, info.colorDepth)
                                } else {
                                    vncDialogInfo = info
                                }
                            }
                        }} else if (activeTab.transportType == "LOCAL" && viewModel.isLocalDesktopInstalled) {{
                            if (!localVncLoading) {
                                localVncLoading = true
                                coroutineScope.launch {
                                    // try/finally so a failure (e.g. keystore decrypt,
                                    // server start) can't leave the spinner stuck true and
                                    // lock the button out forever. (#208 finding 17)
                                    try {
                                        viewModel.ensureLocalVncProfile()
                                        viewModel.startLocalVncServer()
                                        kotlinx.coroutines.delay(4000)
                                        val pwd = viewModel.getLocalVncPassword()
                                        onNavigateToVnc("localhost", 5901, null, pwd, false, null, "BPP_24_TRUE")
                                    } catch (e: Exception) {
                                        android.util.Log.e("TerminalScreen", "local VNC launch failed", e)
                                        android.widget.Toast.makeText(
                                            context,
                                            localVncFailedMessage,
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    } finally {
                                        localVncLoading = false
                                    }
                                }
                            }
                        }} else null,
                        vncLoading = localVncLoading,
                        selectionController = selectionController,
                        selectionActive = selectionActive,
                        hyperlinkUri = currentHyperlinkUri,
                        onPaste = { text -> activeTab.sendInput(text.toByteArray()) },
                        reorderMode = reorderMode,
                        onReorderModeChanged = {
                            reorderMode = it
                            onReorderModeChanged(it)
                        },
                        onToolbarLayoutChanged = onToolbarLayoutChanged,
                        snippetLibrary = snippetLibrary,
                        onSnippetLibraryChanged = onSnippetLibraryChanged,
                        onOpenSettings = onOpenToolbarSettings,
                        allowStandardKeyboard = allowStandardKeyboard,
                        onToggleStandardKeyboard = onToggleStandardKeyboard,
                        rawKeyboardMode = rawKeyboardMode,
                        onToggleRawKeyboard = onToggleRawKeyboard,
                        swipeArrowsActive = swipeArrowsMode,
                        onToggleSwipeArrows = { viewModel.toggleSwipeArrows() },
                        composeModeActive = composeController?.isComposeModeActive == true,
                        onToggleComposeMode = { composeController?.toggleComposeMode() },
                        onAttachTap = { attachSheetVisible = true },
                        onOpenTextInput = { textInputDialogVisible = true },
                        onDictateTap = {
                            // #604: resolve a speech recognizer explicitly
                            // rather than startActivity(ACTION_RECOGNIZE_SPEECH)
                            // blind — some devices (Supernote) ship none, and
                            // the intent resolver also lets the user pick one
                            // if several are installed.
                            val recognize = android.content.Intent(
                                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
                            ).apply {
                                putExtra(
                                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                            }
                            val chooser = recognize.resolveActivity(context.packageManager)?.let {
                                android.content.Intent.createChooser(recognize, null)
                            }
                            if (chooser != null) {
                                dictateLauncher.launch(chooser)
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    dictateUnavailableMessage,
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        selectionContent = selectionController?.let { ctrl -> {
                            SelectionToolbarContent(
                                controller = ctrl,
                                hyperlinkUri = currentHyperlinkUri,
                                bracketPasteMode = isBracketPaste,
                                onPaste = { text -> activeTab.sendInput(text.toByteArray()) },
                            )
                        } },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Floating text-input dialog — MUST stay inside the
                    // key(activeTab.sessionId) block: an active-tab change of
                    // any kind (tap, session death clamping the index, MCP
                    // selectTabBySessionId) then tears it down and recreates
                    // it, so a send can never fire into a tab other than the
                    // one it was opened for. The draft itself lives in the
                    // per-tab textInputDrafts map hoisted above the key()
                    // boundary, so it survives the teardown.
                    if (textInputDialogVisible) {
                        FloatingTextInputDialog(
                            text = textInputDrafts[activeTab.sessionId].orEmpty(),
                            onTextChange = { draft ->
                                textInputDrafts = HashMap(textInputDrafts).apply {
                                    if (draft.isEmpty()) {
                                        remove(activeTab.sessionId)
                                    } else {
                                        put(activeTab.sessionId, draft)
                                    }
                                }
                            },
                            onSend = {
                                val draft = textInputDrafts[activeTab.sessionId].orEmpty()
                                if (draft.isNotEmpty()) {
                                    // Same bracket-wrap path as paste: an
                                    // embedded newline must arrive as pasted
                                    // text (mode 2004), not execute as a
                                    // literal Enter inside vim/psql.
                                    activeTab.sendInput(
                                        bracketPasteWrap(draft, isBracketPaste).toByteArray(),
                                    )
                                    textInputDrafts = HashMap(textInputDrafts).apply {
                                        remove(activeTab.sessionId)
                                    }
                                }
                            },
                            onDismiss = {
                                textInputDialogVisible = false
                                // Hand focus (and the IME's InputConnection)
                                // back to the terminal, so typing resumes in
                                // the session instead of going nowhere.
                                focusRequester.requestFocus()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTerminalState(
    fontSize: Int,
    backgroundColor: Color,
    foregroundColor: Color,
    swipeModifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(swipeModifier)
            .background(backgroundColor)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.terminal_connect_prompt),
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            color = foregroundColor,
        )
    }
}

/**
 * Intercepts hardware keyboard events to fix character mapping for non-US layouts.
 *
 * Called from [Activity.dispatchKeyEvent] BEFORE the View hierarchy processes events,
 * bypassing termlib's hardcoded US QWERTY symbol mappings in KeyboardHandler.
 *
 * Uses Android's [android.view.KeyEvent.getUnicodeChar] which respects the device's
 * KeyCharacterMap for layout-correct characters (e.g. Shift+2 → '"' on QWERTZ
 * instead of '@').
 *
 * Also fixes AltGr (right Alt) combinations — termlib treats all Alt as ESC prefix,
 * but AltGr produces composed characters (e.g. AltGr+Q → '@' on German QWERTZ).
 *
 * Special keys (Enter, Tab, arrows, F-keys) and Ctrl/Alt combos pass through to
 * termlib which handles them correctly via key codes.
 */
private fun handleLayoutAwareKeyEvent(
    event: android.view.KeyEvent,
    activeTab: TerminalTab,
    selectionActive: Boolean,
    hasHardwareKeyboard: Boolean,
    viewModel: TerminalViewModel,
): Boolean {
    if (event.action != android.view.KeyEvent.ACTION_DOWN) return false

    // Only intercept physical keyboard input. Software keyboard / IME events
    // must flow through InputConnection (commitText), otherwise composing input
    // like Chinese Pinyin gets sent as raw Latin letters.
    if (!hasHardwareKeyboard) return false
    if ((event.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false
    if (event.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) return false
    if (!event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD)) return false

    // Don't intercept during text selection — termlib manages selection keys
    if (selectionActive) return false

    val keyCode = event.keyCode

    // Skip modifier-only key presses
    when (keyCode) {
        android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
        android.view.KeyEvent.KEYCODE_SHIFT_RIGHT,
        android.view.KeyEvent.KEYCODE_CTRL_LEFT,
        android.view.KeyEvent.KEYCODE_CTRL_RIGHT,
        android.view.KeyEvent.KEYCODE_ALT_LEFT,
        android.view.KeyEvent.KEYCODE_ALT_RIGHT,
        android.view.KeyEvent.KEYCODE_META_LEFT,
        android.view.KeyEvent.KEYCODE_META_RIGHT,
        android.view.KeyEvent.KEYCODE_CAPS_LOCK,
        android.view.KeyEvent.KEYCODE_NUM_LOCK,
        android.view.KeyEvent.KEYCODE_SCROLL_LOCK,
        android.view.KeyEvent.KEYCODE_FUNCTION,
        -> return false
    }

    // Let termlib handle special terminal keys (navigation, function keys, numpad)
    if (isSpecialTerminalKey(keyCode)) return false

    // Let termlib handle Ctrl+key and left-Alt+key natively.
    // Control codes (Ctrl+C) and ESC prefix (Alt+x) are key-code based,
    // not layout-dependent. Exception: AltGr (right Alt) produces composed
    // characters via the KeyCharacterMap.
    val meta = event.metaState
    val hasAltGr = (meta and android.view.KeyEvent.META_ALT_RIGHT_ON) != 0
    if ((meta and android.view.KeyEvent.META_CTRL_ON) != 0 && !hasAltGr) return false
    if ((meta and android.view.KeyEvent.META_ALT_ON) != 0 && !hasAltGr) return false

    // Get the layout-correct character from Android's KeyCharacterMap.
    // Returns 0 for non-character keys, negative for combining/dead keys.
    val unicodeChar = event.getUnicodeChar(meta)
    if (unicodeChar <= 0) return false

    val char = unicodeChar.toChar()

    // Build modifier mask from toolbar state (shift/AltGr already baked
    // into the character by getUnicodeChar).
    var modifiers = 0
    if (viewModel.ctrlActive.value) modifiers = modifiers or 4
    if (viewModel.altActive.value) modifiers = modifiers or 2

    activeTab.emulator.dispatchCharacter(modifiers, char)

    // Clear toolbar modifiers after use (one-shot toggle)
    if (viewModel.ctrlActive.value) viewModel.toggleCtrl()
    if (viewModel.altActive.value) viewModel.toggleAlt()

    return true
}

/**
 * Wrap [text] in bracketed-paste markers (xterm mode 2004) when the remote
 * app has requested them, so pasted/injected text — embedded newlines
 * included — arrives as one "paste this block" event instead of being
 * interpreted keystroke-by-keystroke. `sendInput` itself never wraps;
 * callers decide. Shared by the paste path (doPaste / Ctrl+Shift+V) and the
 * floating text-input dialog's send.
 */
internal fun bracketPasteWrap(text: CharSequence, bracketPasteMode: Boolean): String =
    if (bracketPasteMode) "\u001b[200~$text\u001b[201~" else text.toString()

/** Box alignment for the fullscreen button's persisted corner (#445). */
private fun sh.haven.core.data.preferences.FullscreenButtonCorner.toComposeAlignment(): Alignment = when (this) {
    sh.haven.core.data.preferences.FullscreenButtonCorner.TOP_END -> Alignment.TopEnd
    sh.haven.core.data.preferences.FullscreenButtonCorner.TOP_START -> Alignment.TopStart
    sh.haven.core.data.preferences.FullscreenButtonCorner.BOTTOM_END -> Alignment.BottomEnd
    sh.haven.core.data.preferences.FullscreenButtonCorner.BOTTOM_START -> Alignment.BottomStart
}

/** Keys that termlib maps to VTermKey codes — let termlib handle these directly. */
private fun isSpecialTerminalKey(keyCode: Int): Boolean {
    if (keyCode in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12) return true
    if (keyCode in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS) return true
    return when (keyCode) {
        android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
        android.view.KeyEvent.KEYCODE_TAB,
        android.view.KeyEvent.KEYCODE_ESCAPE,
        android.view.KeyEvent.KEYCODE_DEL,         // Backspace
        android.view.KeyEvent.KEYCODE_FORWARD_DEL, // Delete
        android.view.KeyEvent.KEYCODE_DPAD_UP,
        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_MOVE_HOME,
        android.view.KeyEvent.KEYCODE_MOVE_END,
        android.view.KeyEvent.KEYCODE_PAGE_UP,
        android.view.KeyEvent.KEYCODE_PAGE_DOWN,
        android.view.KeyEvent.KEYCODE_INSERT,
        -> true
        else -> false
    }
}

/**
 * Build an SGR-encoded mouse wheel escape sequence.
 * Scroll up: button 64, scroll down: button 65.
 * Format: ESC [ < button ; col ; row M
 * col and row are 1-based terminal coordinates.
 */
private fun sgrMouseWheel(scrollUp: Boolean, col: Int, row: Int): ByteArray {
    val button = if (scrollUp) 64 else 65
    return "\u001b[<$button;$col;${row}M".toByteArray()
}

/**
 * SGR mouse button press/release sequence.
 * Format: ESC [ < button ; col ; row M (press) or m (release)
 * Button 0 = left, 2 = right. col and row are 1-based.
 */
/**
 * SGR mouse motion-while-button-held sequence (xterm button-event mode 1002).
 * Format: ESC [ < (button | 32) ; col ; row M
 * The +32 mask flags the event as motion-while-held rather than press; tmux
 * uses it to extend a running selection in copy-mode. col/row are 1-based.
 *
 * (#94 — without these, the remote sees only press+release and can't track
 * a drag-to-select.)
 */
private fun sgrMouseMotion(button: Int, col: Int, row: Int): ByteArray {
    val code = button or 32
    return "\u001b[<$code;$col;${row}M".toByteArray()
}

private fun sgrMouseButton(button: Int, col: Int, row: Int, pressed: Boolean): ByteArray {
    val suffix = if (pressed) 'M' else 'm'
    return "\u001b[<$button;$col;${row}$suffix".toByteArray()
}

/**
 * Cursor Up/Down key for the alt-screen swipe-to-arrows path (#255).
 * DECCKM set (application cursor keys — what vim/less/nano normally run
 * under) wants the SS3 form ESC O A/B; otherwise the CSI form ESC [ A/B.
 * ncurses apps match terminfo kcuu1 exactly, so the mode matters.
 */
internal fun arrowKeyBytes(up: Boolean, appMode: Boolean): ByteArray {
    val prefix = if (appMode) "\u001bO" else "\u001b["
    return (prefix + if (up) "A" else "B").toByteArray()
}

/**
 * Arrow bytes for a held-swipe direction (#524b) — literal finger mapping in
 * all four directions, SS3 in cursor-application mode like [arrowKeyBytes].
 */
internal fun arrowKeyBytesFor(
    direction: org.connectbot.terminal.SwipeHoldDirection,
    appMode: Boolean,
): ByteArray {
    val prefix = if (appMode) "\u001bO" else "\u001b["
    val letter = when (direction) {
        org.connectbot.terminal.SwipeHoldDirection.Up -> "A"
        org.connectbot.terminal.SwipeHoldDirection.Down -> "B"
        org.connectbot.terminal.SwipeHoldDirection.Right -> "C"
        org.connectbot.terminal.SwipeHoldDirection.Left -> "D"
    }
    return (prefix + letter).toByteArray()
}

/** Hold-to-repeat cadence (#524b): held-key feel — brief arm, then steady. */
internal const val SWIPE_HOLD_INITIAL_DELAY_MS = 350L
internal const val SWIPE_HOLD_REPEAT_MS = 90L

/**
 * Which arrow a swipe sends. The gesture layer reports [scrollUp] in
 * content-scroll terms — a finger dragged DOWN reveals older content, so it
 * arrives as scrollUp=true. The automatic alt-screen path (#255) keeps that
 * mapping: dragging content down in a pager sends ↑, exactly like touch
 * scrolling. The latched swipe-arrows mode (#524) is a different mental
 * model — the swipe IS the key press — so the finger direction maps
 * literally: swipe up = ↑ walks shell history back, swipe down = ↓.
 */
internal fun swipeArrowIsUp(scrollUp: Boolean, latchedSwipeArrows: Boolean): Boolean =
    if (latchedSwipeArrows) !scrollUp else scrollUp

/**
 * #609: the latched Swipe key may suppress the tab-switch pager gesture only
 * while a terminal tab is mounted. The latch persists in DataStore after the
 * last session exits (Ctrl+D), and the empty Terminal page is swipeable solely
 * through the host's pager override — reporting the raw latch there suppresses
 * the override and strands the user on the empty page with no way to swipe to
 * another tab.
 */
internal fun pagerSwipeOwnedByArrows(latchedSwipeArrows: Boolean, hasTab: Boolean): Boolean =
    latchedSwipeArrows && hasTab

/**
 * Swipe-arrows quantum: each arrow takes 4x the content-scroll travel
 * (9dp -> 36dp, ~9mm), so a deliberate swipe steps history one entry at a
 * time instead of flashing through it (#524 reporter feedback).
 */
internal const val SWIPE_ARROWS_SCROLL_QUANTUM = 4f

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VncSettingsDialog(
    host: String,
    initialPort: Int,
    initialUsername: String?,
    initialPassword: String?,
    initialSshForward: Boolean,
    initialColorDepth: String = "BPP_24_TRUE",
    onConnect: (port: Int, username: String?, password: String?, sshForward: Boolean, colorDepth: String, save: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var port by remember { mutableStateOf(initialPort.toString()) }
    var username by remember { mutableStateOf(initialUsername ?: "") }
    var password by remember { mutableStateOf(initialPassword ?: "") }
    var sshForward by remember { mutableStateOf(initialSshForward) }
    var colorDepth by remember { mutableStateOf(initialColorDepth) }
    var save by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_vnc_desktop)) },
        text = {
            // Scrollable so the full form (colour depth + save) stays reachable
            // in landscape, where the dialog's text area is short. (#224)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.terminal_connect_to_host, host), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.terminal_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.terminal_vnc_username_label)) },
                    placeholder = { Text(stringResource(R.string.terminal_vnc_username_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.terminal_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = sshForward,
                        onCheckedChange = { sshForward = it },
                    )
                    Text(stringResource(R.string.terminal_tunnel_through_ssh))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                val depthOptions = listOf(
                    "BPP_24_TRUE" to stringResource(R.string.terminal_vnc_depth_24bit),
                    "BPP_16_TRUE" to stringResource(R.string.terminal_vnc_depth_16bit),
                    "BPP_8_INDEXED" to stringResource(R.string.terminal_vnc_depth_256),
                )
                var depthExpanded by remember { mutableStateOf(false) }
                val selectedDepth = depthOptions.firstOrNull { it.first == colorDepth } ?: depthOptions.first()
                ExposedDropdownMenuBox(
                    expanded = depthExpanded,
                    onExpandedChange = { depthExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedDepth.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.terminal_vnc_color_depth)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(depthExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = depthExpanded,
                        onDismissRequest = { depthExpanded = false },
                    ) {
                        depthOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    colorDepth = value
                                    depthExpanded = false
                                },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = save,
                        onCheckedChange = { save = it },
                    )
                    Text(stringResource(R.string.terminal_save_for_connection))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull() ?: 5900
                    onConnect(p, username.ifEmpty { null }, password.ifEmpty { null }, sshForward, colorDepth, save)
                },
            ) {
                Text(stringResource(R.string.terminal_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Top-aligned banner shown over the terminal when the transport has gone
 * silent: displays how long the server has been unreachable while the
 * transport keeps retrying (it recovers by itself when the network
 * returns). Hidden when the flow is null. Currently driven by Mosh; other
 * transports always emit null.
 */
@Composable
private fun StallBanner(
    stallFlow: kotlinx.coroutines.flow.StateFlow<Int?>,
    modifier: Modifier = Modifier,
) {
    val seconds by stallFlow.collectAsState()
    val visible = seconds != null
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier,
    ) {
        val s = seconds ?: 0
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.padding(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Filled.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.terminal_no_contact, s),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun RenameSessionDialog(
    currentLabel: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_rename_session_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.terminal_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(label) },
                enabled = label.isNotBlank() && label != currentLabel,
            ) {
                Text(stringResource(R.string.terminal_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Confirm dialog for Save connection: door/path/room context, editable name,
 * stable profile id, OK / Cancel.
 */
@Composable
private fun SaveConnectionDialog(
    ui: TerminalViewModel.SaveConnectionUi,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val draft = ui.draft
    var name by remember(draft.profileId, draft.defaultName) {
        mutableStateOf(draft.defaultName)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_save_connection_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.terminal_save_connection_context,
                        ui.doorLabel,
                        ui.pathTransport,
                        ui.roomName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.roomMismatch && ui.roomPinnedOnCard != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.terminal_save_connection_mismatch,
                            ui.roomPinnedOnCard,
                            ui.roomName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.terminal_save_connection_name_label)) },
                    supportingText = {
                        Text(stringResource(R.string.terminal_save_connection_name_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.profileId,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.terminal_save_connection_id)) },
                    supportingText = {
                        Text(stringResource(R.string.terminal_save_connection_id_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    if (draft.isUpdate) stringResource(R.string.terminal_save_connection_update)
                    else stringResource(R.string.common_ok),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Read-only door / path / room sitrep for the active terminal tab. */
@Composable
private fun WhereaboutsDialog(
    whereabouts: TerminalViewModel.ConnectionWhereabouts,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_details_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.terminal_details_door),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(whereabouts.doorLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${whereabouts.doorUser}@${whereabouts.doorHost}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    whereabouts.doorId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.terminal_details_path),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(whereabouts.pathTransport, style = MaterialTheme.typography.bodyMedium)
                Text(
                    whereabouts.pathDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.terminal_details_room),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    whereabouts.roomName
                        ?: stringResource(R.string.terminal_details_room_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.terminal_details_room_pin,
                        whereabouts.roomPinnedOnCard
                            ?: stringResource(R.string.terminal_details_room_no_pin),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (whereabouts.roomMismatch) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.terminal_details_room_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(
                        ClipData.newPlainText("haven-whereabouts", whereabouts.summaryText),
                    )
                    @Suppress("LocalContextGetResourceValueCall")
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.terminal_details_copied),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.terminal_details_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

