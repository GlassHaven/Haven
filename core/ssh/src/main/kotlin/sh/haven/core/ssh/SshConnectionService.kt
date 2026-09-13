package sh.haven.core.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.McpStatusHolder
import javax.inject.Inject

@AndroidEntryPoint
class SshConnectionService : Service() {

    /** Kept as a direct dependency for SSH-specific reconnect on network restore. */
    @Inject
    lateinit var sessionManager: SshSessionManager

    @Inject
    lateinit var participants: Set<@JvmSuppressWildcards ForegroundSessionParticipant>

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    /**
     * Transports the standard SSH recovery paths skip (e.g. the headless MCP
     * reverse tunnel). Kicked on the same two triggers as the SSH sessions —
     * return-to-foreground and network-available — so they don't wait out their
     * own (Doze-deferrable) watchdog timers.
     */
    @Inject
    lateinit var reviveHooks: Set<@JvmSuppressWildcards ForegroundReviveHook>

    /**
     * Runs the same hooks on a timer, because both of the triggers above need
     * something an on-device MCP client never produces — a return to the
     * foreground, or a network transition (#494).
     */
    @Inject
    lateinit var reviveTicker: ForegroundReviveTicker

    /** Live MCP activity, folded into the ongoing notification (#239). */
    @Inject
    lateinit var mcpStatusHolder: McpStatusHolder

    /** Stamps process background/foreground so a drop can be attributed (#495). */
    @Inject
    lateinit var backgroundDisconnectDetector: BackgroundDisconnectDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * On every return-to-foreground, probe live SSH sessions and reconnect any
     * whose socket died silently in the background. Without this, a session
     * dropped by NAT/Doze (no transport change, so [NetworkMonitor] is quiet)
     * stays frozen until JSch's keepalive eventually times out (~45 s, and that
     * timer is itself suspended during Doze). [addObserver] also delivers an
     * immediate onStart when the app is already foreground — harmless, since the
     * probe is cheap and idempotent.
     */
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            backgroundDisconnectDetector.onForegrounded()
            serviceScope.launch { sessionManager.probeAndReconnectStale() }
            // probeAndReconnectStale skips headless transports (the MCP tunnel);
            // give them their own immediate kick rather than the deferrable watchdog.
            reviveHooks.forEach { it.reviveNow() }
        }

        // Stamped here rather than in MainActivity.onPause because this is the
        // *process* going background, which is what a ROM's background policy
        // acts on — an Activity pause also fires for a permission dialog (#495).
        override fun onStop(owner: LifecycleOwner) {
            backgroundDisconnectDetector.onBackgrounded()
        }
    }

    companion object {
        const val CHANNEL_ID = "haven_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT_ALL = "sh.haven.action.DISCONNECT_ALL"

        /**
         * Boolean extra on the launch intent fired by the notification's
         * "Agent log" action; MainActivity routes it to
         * [McpStatusHolder.requestOpenActivityLog] (#239).
         */
        const val EXTRA_OPEN_AGENT_LOG = "sh.haven.extra.OPEN_AGENT_LOG"

        /**
         * Boolean extra the service puts on the launch intent it fires after
         * Disconnect All, so MainActivity can tell its own auto-launch from a
         * launcher open carrying the same category/action (#640).
         */
        const val EXTRA_EXIT_AFTER_DISCONNECT_ALL = "sh.haven.extra.EXIT_AFTER_DISCONNECT_ALL"

        /** Set when "Disconnect All" is tapped; cleared after the activity finishes. */
        @Volatile
        var disconnectedAll = false
            private set

        fun clearDisconnectedAll() { disconnectedAll = false }

        /**
         * True when an activity resume should finish the app: the Disconnect
         * All flag is set and the resume's intent is the service's own
         * auto-launch, which carries [EXTRA_EXIT_AFTER_DISCONNECT_ALL]. A
         * plain launcher open must clear the flag without finishing (#640):
         * when the auto-launch is blocked by background-activity-launch
         * restrictions the flag would otherwise survive to the next manual
         * open, which then bounced straight back to the launcher.
         */
        fun shouldFinishOnResume(disconnectedAll: Boolean, hasExitExtra: Boolean): Boolean =
            disconnectedAll && hasExitExtra
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkMonitor.start()
        // Service.onCreate runs on the main thread, where ProcessLifecycleOwner
        // observers must be added.
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        reviveTicker.start()
        serviceScope.launch {
            networkMonitor.events
                .debounce(2_000) // network changes fire rapidly during handoff
                .collect { event ->
                    if (event is NetworkMonitor.Event.Available) {
                        Log.d("SshConnectionService", "Network available — requesting reconnect for disconnected sessions")
                        sessionManager.requestReconnectAll()
                        // requestReconnectAll only acts on already-DISCONNECTED/ERROR
                        // sessions; a roamed headless tunnel may still read stale
                        // CONNECTED, so kick it explicitly to revive/probe now.
                        reviveHooks.forEach { it.reviveNow() }
                    }
                }
        }
        // Re-post the ongoing notification when MCP activity changes so the
        // endpoint line (running tool / last error) stays current (#239).
        // drop(1): skip the StateFlow's initial replay — the startForeground
        // in onStartCommand posts the first notification. Debounced because
        // tool calls can fire in quick bursts; the channel is IMPORTANCE_LOW
        // so updates are silent.
        serviceScope.launch {
            mcpStatusHolder.activity
                .drop(1)
                .debounce(400)
                .collect {
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, buildNotification())
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            disconnectedAll = true
            participants.forEach { it.disconnectAll() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Bring the activity to the foreground so it can finish itself
            packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                launchIntent.putExtra(EXTRA_EXIT_AFTER_DISCONNECT_ALL, true)
                startActivity(launchIntent)
            }
            return START_NOT_STICKY
        }

        // specialUse, not dataSync: on Android 16 dataSync gets killed
        // by Stop FGS timeout every 10–30 s regardless of the 3-arg
        // startForeground call, which tears down any SSH session and
        // the MCP reverse tunnel forwarded over it. specialUse fits the
        // long-lived-connection use case and isn't subject to the same
        // timeout schedule; subtype is declared via PROPERTY_SPECIAL_USE_FGS_SUBTYPE
        // on the <service> element in the manifest.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        reviveTicker.stop()
        networkMonitor.stop()
        serviceScope.cancel()
        super.onDestroy()
        participants.forEach { it.disconnectAll() }
    }

    private fun buildNotification(): Notification {
        val activeByParticipant = participants.map { it.activeSessions }
        val count = activeByParticipant.sumOf { it.size }
        val labelList = activeByParticipant
            .flatMap { sessions -> sessions.distinctBy { it.profileId } }
            .map { it.label }
        val labels = labelList.joinToString(", ")

        val disconnectIntent = Intent(this, SshConnectionService::class.java).apply {
            action = ACTION_DISCONNECT_ALL
        }
        val disconnectPending = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPending = launchIntent?.let {
            PendingIntent.getActivity(
                this, 1, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_haven_notification)
            .setContentTitle("Haven — $count active session${if (count != 1) "s" else ""}")
            .setContentText(labels.ifEmpty { "Connecting..." })
            // Expanded view: one session per line, so the MCP status line
            // (running tool / last error) isn't truncated behind the other
            // session labels in the single collapsed row (#239).
            .setStyle(NotificationCompat.BigTextStyle().bigText(labelList.joinToString("\n").ifEmpty { "Connecting..." }))
            .setOngoing(true)
            .setContentIntent(contentPending)
            .addAction(
                R.drawable.ic_haven_notification,
                "Disconnect All",
                disconnectPending,
            )
        if (mcpStatusHolder.activity.value.running) {
            val agentLogIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_AGENT_LOG, true)
            }
            if (agentLogIntent != null) {
                builder.addAction(
                    R.drawable.ic_haven_notification,
                    "Agent log",
                    PendingIntent.getActivity(
                        this, 2, agentLogIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active Connections",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Active connection status"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
