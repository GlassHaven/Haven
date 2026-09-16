package sh.haven.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.data.agent.PresentedMediaKind
import sh.haven.app.agent.AppWindowConnectionStore
import sh.haven.app.agent.AppWindowVncController
import sh.haven.app.agent.PipController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import sh.haven.app.agent.ConsentHost
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.app.navigation.HavenNavHost
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ui.KeyEventInterceptor
import sh.haven.core.ui.theme.HavenTheme
import javax.inject.Inject
import sh.haven.core.redact.LogRedact

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var biometricAuthenticator: BiometricAuthenticator
    // Eagerly injected to trigger one-time password encryption migration
    @Inject lateinit var connectionRepository: ConnectionRepository
    @Inject lateinit var sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository
    // Drives the Mail tab's visibility (shown only while a mail session is open).
    @Inject lateinit var mailSessionManager: sh.haven.core.mail.MailSessionManager
    @Inject lateinit var openAiSessionManager: sh.haven.core.openai.OpenAiSessionManager
    // Published to FidoAuthenticator in onResume so NFC reader mode can
    // be enabled during FIDO2 SSH assertions. Without this, Nitrokey /
    // SoloKey / YubiKey-over-NFC flows never saw a Tag (#15).
    @Inject lateinit var fidoAuthenticator: FidoAuthenticator
    // Tracks foreground state so AgentConsentManager can fail-closed
    // when there's no activity to render the prompt. The §85 rule
    // forbids letting destructive agent calls slip through silently.
    @Inject lateinit var agentConsentManager: AgentConsentManager

    @Inject lateinit var prootIdleReaper: ProotIdleReaper
    // Cross-tab navigation verbs: HavenNavHost collects from this so an
    // MCP `navigate_sftp_browser` switches the pager to the right tab.
    @Inject lateinit var agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus
    // App-global user-facing messages (e.g. shell-closed connect errors);
    // HavenNavHost shows these in a root snackbar over every screen (#215 follow-up).
    @Inject lateinit var userMessageBus: sh.haven.core.data.message.UserMessageBus
    // The foreground notification's "Agent log" action lands here as a launch
    // intent extra; routed via this holder's latched flag so a cold-start tap
    // (or one behind the biometric lock) isn't dropped (#239).
    @Inject lateinit var mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder
    // Cert renewal request bus: notifications fire haven://renew-cert/<id>
    // intents which land here and we re-publish via the existing
    // AgentUiCommandBus so HavenNavHost flips to Keys and KeysViewModel
    // picks up the regenerate request. (#133 phase 2b)
    // Keystore biometric gate: a fetch on a BIOMETRIC_PROTECTED entry
    // queues a request here, this Activity collects, runs
    // BiometricPrompt, and posts the decision back. Foreground tracking
    // mirrors AgentConsentManager (fail-closed when backgrounded).
    @Inject lateinit var biometricGate: sh.haven.core.data.keystore.BiometricGate
    // Workspace launcher (singleton) — invoked when a launcher
    // long-press shortcut routes the workspace id through onCreate /
    // onNewIntent.
    @Inject lateinit var workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher
    // App-window launcher (singleton) — invoked when a home-screen pinned
    // shortcut routes an app-window def id through onCreate / onNewIntent.
    @Inject lateinit var appWindowLauncher: sh.haven.app.desktop.AppWindowLauncher
    // Picture-in-Picture for app windows: PipController bridges PiP state to
    // the composition; the store owns the live VNC connection so it survives
    // the overlay→PiP→overlay round-trip.
    @Inject lateinit var pipController: PipController
    @Inject lateinit var appWindowConnectionStore: AppWindowConnectionStore
    // Lets the MCP agent endpoint capture and drive Haven's own UI
    // (self-hosting loop, §1a). Holds a weak ref to the foreground
    // activity; attach/detach mirror fidoAuthenticator's lifecycle.
    @Inject lateinit var havenUiBridge: sh.haven.app.agent.HavenUiBridge

    @Inject lateinit var taskerOverlayCoordinator: sh.haven.app.tasker.TaskerOverlayCoordinator

    /**
     * A pending Tasker overlay run (profileId → command). Set by
     * [handleTaskerRunIntent] and consumed by a LaunchedEffect inside
     * [setContent] — deferring past composition so the always-composed
     * ConnectionsViewModel is subscribed to the (replay=0) command bus before
     * the coordinator emits ConnectProfile, otherwise the connect is dropped.
     */
    private val pendingTaskerRun = androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)

    private fun exitIfDisconnected() {
        // Only the service's own auto-launch (it carries
        // EXTRA_EXIT_AFTER_DISCONNECT_ALL) may finish the activity. When that
        // launch is blocked by background-activity-launch restrictions the
        // flag would otherwise survive to the next manual open from the
        // launcher, which then bounced straight back out (#640) — clear the
        // flag on a plain open instead of finishing.
        val exitExtra = intent?.getBooleanExtra(
            SshConnectionService.EXTRA_EXIT_AFTER_DISCONNECT_ALL, false) ?: false
        if (SshConnectionService.shouldFinishOnResume(
                SshConnectionService.disconnectedAll, exitExtra)) {
            Log.d("MainActivity", "Disconnect All detected — exiting")
            SshConnectionService.clearDisconnectedAll()
            finishAndRemoveTask()
        } else if (SshConnectionService.disconnectedAll) {
            SshConnectionService.clearDisconnectedAll()
        }
    }

    override fun onResume() {
        super.onResume()
        exitIfDisconnected()
        fidoAuthenticator.setActiveActivity(this)
        agentConsentManager.setForegroundActive(true)
        biometricGate.setForegroundActive(true)
        prootIdleReaper.onForeground()
        havenUiBridge.attach(this)
        // A pairing attempt blocked while backgrounded is re-prompted now that
        // a foreground activity can render the sheet — this is what the
        // pairing notification's tap-to-open promises. No-op when nothing is
        // remembered.
        lifecycleScope.launch {
            agentConsentManager.repromptBlockedPairing()?.let { client ->
                androidx.core.app.NotificationManagerCompat.from(this@MainActivity)
                    .cancel(HavenApp.consentNotifId(client, sh.haven.core.data.agent.AgentConsentManager.PAIRING_TOOL_NAME))
            }
        }
    }

    override fun onPause() {
        fidoAuthenticator.clearActiveActivity(this)
        agentConsentManager.setForegroundActive(false)
        biometricGate.setForegroundActive(false)
        prootIdleReaper.onBackground()
        havenUiBridge.detach(this)
        super.onPause()
    }

    /** Last deep-link URI handled, with its elapsed-realtime stamp. See [isFreshDeepLink]. */
    private var lastDeepLink: Pair<String, Long>? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        exitIfDisconnected()
        handleWorkspaceShortcut(intent)
        handleAppWindowShortcut(intent)
        handleRenewCertDeepLink(intent)
        handleConnectDeepLink(intent)
        handleOpenUsbDriveIntent(intent)
        handleOpenMailRulesIntent(intent)
        handleOpenAgentLogExtra(intent)
        handleTaskerRunIntent(intent)
        handleBackupShortcutIntent(intent)
    }

    /**
     * Tap-to-watch from the Tasker plugin (#367 Phase 2): the fire receiver
     * can't foreground us itself (BAL), so it posts a notification whose tap
     * lands here. Now that we're foreground the coordinator can connect the
     * profile into a visible terminal and stream the command in. Extras are
     * cleared once consumed so a config change doesn't re-fire it.
     */
    private fun handleTaskerRunIntent(intent: Intent?) {
        val profileId = intent?.getStringExtra(
            sh.haven.app.tasker.TaskerFireReceiver.EXTRA_RUN_PROFILE_ID,
        ) ?: return
        val command = intent.getStringExtra(
            sh.haven.app.tasker.TaskerFireReceiver.EXTRA_RUN_COMMAND,
        ) ?: return
        intent.removeExtra(sh.haven.app.tasker.TaskerFireReceiver.EXTRA_RUN_PROFILE_ID)
        intent.removeExtra(sh.haven.app.tasker.TaskerFireReceiver.EXTRA_RUN_COMMAND)
        pendingTaskerRun.value = profileId to command
    }

    /**
     * The foreground notification's "Agent log" action (posted while the MCP
     * endpoint runs) fires the launch intent with this extra. Latch it on
     * [mcpStatusHolder] — HavenNavHost collects the flag and opens the Agent
     * Activity overlay. Extra cleared once consumed so a configuration change
     * doesn't re-open it (#239).
     */
    private fun handleOpenAgentLogExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(SshConnectionService.EXTRA_OPEN_AGENT_LOG, false) != true) return
        intent.removeExtra(SshConnectionService.EXTRA_OPEN_AGENT_LOG)
        mcpStatusHolder.requestOpenActivityLog()
    }

    /**
     * If [intent] carries an app-window launch action (home-screen pinned
     * shortcut tap), start the cage and present it. Cold-start safe — the
     * launcher queues straight onto the retained presentation queue, so a
     * tap that cold-starts Haven still opens the app. The extra is cleared
     * once consumed so a configuration change doesn't relaunch.
     */
    private fun handleAppWindowShortcut(intent: Intent?) {
        if (intent?.action != sh.haven.app.desktop.AppWindowShortcutManager.ACTION_LAUNCH_APP_WINDOW) return
        val defId = intent.getStringExtra(
            sh.haven.app.desktop.AppWindowShortcutManager.EXTRA_APP_WINDOW_ID,
        ) ?: return
        intent.removeExtra(sh.haven.app.desktop.AppWindowShortcutManager.EXTRA_APP_WINDOW_ID)
        Log.d("MainActivity", "launching app window $defId from shortcut")
        MainScope().launch {
            appWindowLauncher.launchById(defId)?.let { userMessageBus.error(it) }
        }
    }

    /**
     * Handle the "USB drive detected" notification tap (#287): [HavenApp]'s
     * mass-storage attach receiver posts a notification whose content intent
     * carries [HavenApp.ACTION_OPEN_USB_DRIVE]. Re-publish onto the UI command
     * bus so HavenNavHost switches to Desktop and DesktopViewModel opens the
     * drive — the same flow as the in-app "Open USB drive…" tap. The receiver
     * only fires while the app process is alive, so this lands on a live UI.
     */
    private fun handleOpenUsbDriveIntent(intent: Intent?) {
        if (intent?.action != HavenApp.ACTION_OPEN_USB_DRIVE) return
        val device = intent.getStringExtra(HavenApp.EXTRA_USB_DEVICE_NAME)
        intent.removeExtra(HavenApp.EXTRA_USB_DEVICE_NAME)
        intent.action = null
        Log.d("MainActivity", "open-usb-drive intent for ${device ?: "(sole drive)"}")
        agentUiCommandBus.emit(sh.haven.core.data.agent.AgentUiCommand.OpenUsbDrive(device))
    }

    /**
     * Handle the "mail actions awaiting approval" notification tap: the
     * content intent carries [sh.haven.app.agent.mailrules.MailRuleNotifier]'s
     * action; re-publish onto the UI command bus so HavenNavHost switches to
     * Mail and opens the rules pane's approval queue.
     */
    private fun handleOpenMailRulesIntent(intent: Intent?) {
        if (intent?.action != sh.haven.app.agent.mailrules.MailRuleNotifier.ACTION_OPEN_MAIL_RULES) return
        intent.action = null
        agentUiCommandBus.emit(sh.haven.core.data.agent.AgentUiCommand.OpenMailRules)
    }

    // --- Picture-in-Picture (app windows) ---

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipController.setInPip(isInPictureInPictureMode)
    }

    /**
     * API 26–30 auto-enter fallback: those releases lack
     * `setAutoEnterEnabled`, so enter PiP explicitly when the user leaves
     * while an app window is open. On API 31+ the armed params auto-enter.
     * Only an APP_WINDOW auto-enters — an image/web sheet floats only via the
     * explicit PiP button (auto-PiP'ing a static image on Home is surprising).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            pipController.activePipMedia.value?.kind == PresentedMediaKind.APP_WINDOW
        ) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    /** Explicit PiP from a present_media / present_web / app-window overlay button. */
    fun enterPipForMedia() {
        if (pipController.activePipMedia.value == null) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(currentPipAspect())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Only a live app window auto-enters PiP on Home; image/web are
            // explicit-tap only.
            builder.setAutoEnterEnabled(
                pipController.activePipMedia.value?.kind == PresentedMediaKind.APP_WINDOW,
            )
        }
        return builder.build()
    }

    /** Aspect ratio for the current PiP item — VNC frame for an app window,
     *  intrinsic size for an image / PDF, 16:9 for a WebView — clamped to
     *  Android's allowed PiP range, with a 16:9 fallback. (#225) */
    private fun currentPipAspect(): Rational {
        val media = pipController.activePipMedia.value ?: return Rational(16, 9)
        val dims: Pair<Int, Int>? = when (media.kind) {
            PresentedMediaKind.APP_WINDOW -> {
                val sid = media.sessionId
                val h = media.host
                val p = media.port
                if (sid != null && h != null && p != null) {
                    appWindowConnectionStore.controllerFor(sid, h, p).frame.value
                        ?.let { it.width to it.height }
                } else null
            }
            PresentedMediaKind.IMAGE -> media.filePath?.let { imageDims(it) }
            PresentedMediaKind.WEB ->
                if (media.mimeType == "application/pdf") media.filePath?.let { pdfFirstPageDims(it) }
                else null // HTML/SVG WebView has no intrinsic size → 16:9
            else -> null
        }
        val (w, h) = dims ?: return Rational(16, 9)
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val ratio = w.toFloat() / h.toFloat()
        // Android rejects aspect ratios outside roughly [1:2.39, 2.39:1].
        return if (ratio in 0.42f..2.39f) Rational(w, h) else Rational(16, 9)
    }

    /** Width/height of an image file via a bounds-only decode (no pixels). */
    private fun imageDims(path: String): Pair<Int, Int>? = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }.getOrNull()

    /** Width/height of a PDF's first page. */
    private fun pdfFirstPageDims(path: String): Pair<Int, Int>? = runCatching {
        android.os.ParcelFileDescriptor.open(
            java.io.File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        ).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                if (r.pageCount <= 0) null
                else r.openPage(0).use { pg -> pg.width to pg.height }
            }
        }
    }.getOrNull()

    /**
     * Parse `haven://renew-cert/<keyId>` deep links posted by
     * [sh.haven.core.stepca.RenewalNotifier] and re-publish onto the
     * existing UI command bus. The notifier never imports the bus
     * directly — that would couple core/stepca to core/data — so the
     * Activity bridges the two.
     */
    /**
     * True the first time a given deep-link URI is seen, false for a repeat
     * within [DEEP_LINK_DEDUPE_MS].
     *
     * `intent.data = null` below already stops the *same* Intent being replayed
     * across a configuration change. It cannot help when the same URI arrives as
     * two *different* Intents, which is exactly what [DeepLinkActivity] produces:
     * it hands the URI straight to a live MainActivity (the ColorOS path, where
     * onNewIntent is not reliably delivered) and *also* forwards an Intent that
     * reaches onNewIntent wherever the platform behaves. On a device where both
     * arrive, one tap would otherwise emit two connect commands.
     *
     * Deduping here rather than dropping one of the two delivery paths keeps the
     * ColorOS workaround intact: whichever arrives first wins, the other is
     * ignored, and neither has to be trusted to be the one that works.
     */
    private fun isFreshDeepLink(data: android.net.Uri): Boolean {
        val key = data.toString()
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastDeepLink
        if (previous != null && previous.first == key &&
            now - previous.second < DEEP_LINK_DEDUPE_MS
        ) {
            Log.d("MainActivity", "ignoring duplicate deep link within dedupe window")
            return false
        }
        lastDeepLink = key to now
        return true
    }

    private fun handleRenewCertDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "haven" || data.host != "renew-cert") return
        if (!isFreshDeepLink(data)) return
        val keyId = data.lastPathSegment ?: return
        Log.d("MainActivity", "renew-cert deep link for key $keyId")
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.RegenerateStepCaCert(keyId),
        )
        intent.data = null
    }

    /**
     * Parse a `haven://connect?host=…&user=…&port=…&transport=…&session=…`
     * deep link (#305) and re-publish a connect/prefill command onto the UI
     * bus. A host matching exactly one saved profile connects (routed through
     * a confirm in ConnectionsViewModel, since a BROWSABLE link can be fired
     * by a web page); no/ambiguous match opens the New-Connection editor
     * pre-filled. The saved-profile lookup is async (Room), which also defers
     * the emit past `setContent` so the always-composed ConnectionsViewModel
     * is collecting by the time it lands.
     */
    private fun handleConnectDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "haven" || data.host != "connect") return
        val params = ConnectDeepLink.parse { data.getQueryParameter(it) } ?: return
        if (!isFreshDeepLink(data)) return
        intent.data = null
        Log.d("MainActivity", "connect deep link host=${LogRedact.of(params.host)} transport=${params.transport}")
        MainScope().launch {
            agentUiCommandBus.emit(ConnectDeepLink.resolve(connectionRepository.getAll(), params))
        }
    }

    /**
     * If [intent] carries a workspace launch action (long-press shortcut
     * tap, or a `compose_workspace` MCP call routed through here in a
     * future patch), kick off the launcher. Idempotent — extras are
     * cleared once consumed so a configuration change doesn't replay.
     */
    private fun handleWorkspaceShortcut(intent: Intent?) {
        if (intent?.action != sh.haven.app.workspace.WorkspaceShortcutManager.ACTION_LAUNCH_WORKSPACE) return
        val workspaceId = intent.getStringExtra(
            sh.haven.app.workspace.WorkspaceShortcutManager.EXTRA_WORKSPACE_ID,
        ) ?: return
        intent.removeExtra(sh.haven.app.workspace.WorkspaceShortcutManager.EXTRA_WORKSPACE_ID)
        Log.d("MainActivity", "launching workspace $workspaceId from shortcut")
        MainScope().launch {
            workspaceLauncher.launch(workspaceId)
        }
    }

    private fun handleBackupShortcutIntent(intent: Intent?) {
        if (intent?.action != sh.haven.app.backup.PullBackupShortcutActivity.ACTION_SHOW_BACKUP_PASSWORD_DIALOG) return
        intent.action = null
        agentUiCommandBus.emit(sh.haven.core.data.agent.AgentUiCommand.OpenBackupPasswordDialog)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyEventInterceptor.handler?.let { interceptor ->
            if (interceptor(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        activeInstanceRef = java.lang.ref.WeakReference(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWorkspaceShortcut(intent)
        handleAppWindowShortcut(intent)
        handleRenewCertDeepLink(intent)
        handleConnectDeepLink(intent)
        handleOpenUsbDriveIntent(intent)
        handleOpenMailRulesIntent(intent)
        handleOpenAgentLogExtra(intent)
        handleTaskerRunIntent(intent)
        handleBackupShortcutIntent(intent)
        setContent {
            // Tasker overlay run (#367): deferred here so the ConnectionsViewModel
            // is subscribed to the command bus before the coordinator connects.
            val pendingRun by pendingTaskerRun
            LaunchedEffect(pendingRun) {
                pendingRun?.let { (profileId, command) ->
                    taskerOverlayCoordinator.runInTerminal(profileId, command)
                    pendingTaskerRun.value = null
                }
            }

            // Prevent screenshots/screen recording when enabled
            val screenSecurity by preferencesRepository.screenSecurity
                .collectAsState(initial = false)
            LaunchedEffect(screenSecurity) {
                if (screenSecurity) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Hide Haven's task card from the recents screen (#239). Dynamic
            // (AppTask API) rather than the manifest flag so it's toggleable;
            // sessions and services are untouched — recents UI only.
            val excludeFromRecents by preferencesRepository.excludeFromRecents
                .collectAsState(initial = false)
            LaunchedEffect(excludeFromRecents) {
                getSystemService(android.app.ActivityManager::class.java)
                    ?.appTasks?.forEach { it.setExcludeFromRecents(excludeFromRecents) }
            }

            val themeMode by preferencesRepository.theme
                .collectAsState(initial = UserPreferencesRepository.ThemeMode.SYSTEM)
            // Mirror the user's choice to AppCompatDelegate so the next
            // cold-launch splash window matches without waiting for the
            // Application to re-read DataStore (#153 follow-up).
            LaunchedEffect(themeMode) {
                AppCompatDelegate.setDefaultNightMode(themeMode.toNightMode())
            }
            val darkTheme = when (themeMode) {
                UserPreferencesRepository.ThemeMode.LIGHT -> false
                UserPreferencesRepository.ThemeMode.DARK -> true
                UserPreferencesRepository.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            HavenTheme(darkTheme = darkTheme) {
                val biometricEnabled by preferencesRepository.biometricEnabled
                    .collectAsState(initial = false)
                val lockTimeout by preferencesRepository.lockTimeout
                    .collectAsState(initial = sh.haven.core.data.preferences.UserPreferencesRepository.LockTimeout.IMMEDIATE)
                var unlocked by remember { mutableStateOf(false) }
                var backgroundedAt by remember { mutableStateOf(0L) }

                // Re-lock when app goes to background, respecting timeout.
                // Minimum 5s grace period so file pickers, permission dialogs,
                // and other brief system activities don't trigger re-lock.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        // Don't arm the re-lock when we stop because we entered
                        // PiP — the app window is still floating, and re-locking
                        // would slam the lock screen over it on expand.
                        if (event == Lifecycle.Event.ON_STOP && !pipController.isInPip.value) {
                            backgroundedAt = System.currentTimeMillis()
                        }
                        if (event == Lifecycle.Event.ON_START && unlocked && backgroundedAt > 0) {
                            val elapsed = (System.currentTimeMillis() - backgroundedAt) / 1000
                            val effectiveTimeout = maxOf(lockTimeout.seconds, 5L)
                            if (elapsed >= effectiveTimeout) {
                                unlocked = false
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val inPip by pipController.isInPip.collectAsState()
                val activePipMedia by pipController.activePipMedia.collectAsState()
                // Keep PiP params current so API 31+ auto-enters PiP when an
                // app window is open, and disarms when it closes.
                LaunchedEffect(activePipMedia?.id) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { setPictureInPictureParams(buildPipParams()) }
                    }
                }

                val pipWin = activePipMedia
                if (inPip && pipWin != null) {
                    // In PiP: render just the item, full-bleed on black. PiP is
                    // view-only on Android — interaction resumes on expand. (#225)
                    when (pipWin.kind) {
                        PresentedMediaKind.APP_WINDOW -> {
                            if (pipWin.sessionId != null && pipWin.host != null && pipWin.port != null) {
                                PipAppWindow(
                                    appWindowConnectionStore.controllerFor(
                                        pipWin.sessionId!!, pipWin.host!!, pipWin.port!!,
                                    ),
                                )
                            }
                        }
                        PresentedMediaKind.IMAGE -> pipWin.filePath?.let { PipImage(it) }
                        PresentedMediaKind.WEB ->
                            if (pipWin.mimeType == "application/pdf") {
                                pipWin.filePath?.let { PipPdfFirstPage(it) }
                            } else {
                                pipWin.url?.let { PipWebView(it) }
                            }
                        else -> {}
                    }
                } else if (biometricEnabled && !unlocked) {
                    BiometricLockScreen(
                        authenticator = biometricAuthenticator,
                        onUnlocked = { unlocked = true },
                    )
                } else {
                    HavenNavHost(
                        preferencesRepository = preferencesRepository,
                        connectionRepository = connectionRepository,
                        sshKeyRepository = sshKeyRepository,
                        agentUiCommandBus = agentUiCommandBus,
                        userMessageBus = userMessageBus,
                        mailSessionManager = mailSessionManager,
                        openAiSessionManager = openAiSessionManager,
                        mcpStatusHolder = mcpStatusHolder,
                    )
                    // Floats above whatever screen is active so an
                    // agent's consent prompt is unmissable. No-op when
                    // there are no pending requests.
                    ConsentHost()
                    // Same top-of-tree pattern: an agent-pushed image or
                    // sound (present_media) floats over the active screen.
                    sh.haven.app.agent.PresentationHost()
                    // Backgrounded app windows dock here as draggable edge
                    // icons; tap restores one to the PresentationHost overlay.
                    sh.haven.app.agent.EdgeIconDock()
                    // Same pattern for BIOMETRIC_PROTECTED keystore
                    // fetches — the gate publishes; this host renders
                    // BiometricPrompt; the result resumes the
                    // suspending fetch caller.
                    sh.haven.app.agent.BiometricGateHost()
                }
            }
        }
    }

    override fun onDestroy() {
        if (activeInstanceRef?.get() === this) {
            activeInstanceRef = null
        }
        super.onDestroy()
    }

    /**
     * Handle a deep link handed straight to a live instance by
     * [DeepLinkActivity], bypassing the Intent system. Only needed because
     * onNewIntent is not reliably delivered on ColorOS; deduped against the
     * forwarded Intent by [isFreshDeepLink] so both paths can fire safely.
     */
    fun handleDeepLinkUri(uri: android.net.Uri) {
        val intent = Intent().apply { data = uri }
        handleRenewCertDeepLink(intent)
        handleConnectDeepLink(intent)
    }

    companion object {
        /**
         * Long enough to cover the gap between DeepLinkActivity's direct call
         * and the forwarded Intent landing in onNewIntent, short enough that a
         * user deliberately re-firing the same link still works.
         */
        private const val DEEP_LINK_DEDUPE_MS = 3_000L

        @Volatile
        private var activeInstanceRef: java.lang.ref.WeakReference<MainActivity>? = null

        fun getActiveInstance(): MainActivity? = activeInstanceRef?.get()
    }
}

/**
 * The minimal Picture-in-Picture view: the app window's live frame,
 * full-bleed on black. PiP content is view-only on Android (taps expand the
 * window), so no input wiring — interaction resumes in the expanded overlay.
 */
@Composable
private fun PipAppWindow(controller: AppWindowVncController) {
    val frame by controller.frame.collectAsState()
    PipBitmap(frame?.asImageBitmap())
}

/** Full-bleed bitmap on black — the shared body for the image and PDF PiP views. */
@Composable
private fun PipBitmap(bitmap: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** PiP for a present_media image: the decoded file, full-bleed. (#225) */
@Composable
private fun PipImage(path: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }
                .getOrNull()
        }
    }
    PipBitmap(bitmap)
}

/** PiP for a present_web PDF: the first page rasterised, full-bleed. (#225) */
@Composable
private fun PipPdfFirstPage(path: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                android.os.ParcelFileDescriptor.open(
                    java.io.File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { r ->
                        if (r.pageCount <= 0) return@runCatching null
                        r.openPage(0).use { page ->
                            val w = 1080
                            val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            val bmp = android.graphics.Bitmap.createBitmap(
                                w, h, android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(
                                bmp, null, null,
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            bmp.asImageBitmap()
                        }
                    }
                }
            }.getOrNull()
        }
    }
    PipBitmap(bitmap)
}

/** PiP for a present_web HTML/SVG page: a live WebView loading the loopback
 *  URL. Renders live; view-only in PiP (no touch until the window expands). (#225) */
@android.annotation.SuppressLint("SetJavaScriptEnabled") // deliberate; no JS bridge exposed
@Composable
private fun PipWebView(url: String) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    webViewClient = android.webkit.WebViewClient()
                    // Match the sheet's WebContent: JS/DOM storage default off
                    // and blank any scripted page.
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
