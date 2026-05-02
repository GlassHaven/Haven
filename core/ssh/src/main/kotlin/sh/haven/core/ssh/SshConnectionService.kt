package sh.haven.core.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_ID = "haven_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT_ALL = "sh.haven.action.DISCONNECT_ALL"

        /** Set when "Disconnect All" is tapped; cleared after the activity finishes. */
        @Volatile
        var disconnectedAll = false
            private set

        fun clearDisconnectedAll() { disconnectedAll = false }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkMonitor.start()
        serviceScope.launch {
            networkMonitor.events
                .debounce(2_000) // network changes fire rapidly during handoff
                .collect { event ->
                    if (event is NetworkMonitor.Event.Available) {
                        Log.d("SshConnectionService", "Network available — requesting reconnect for disconnected sessions")
                        sessionManager.requestReconnectAll()
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
                startActivity(launchIntent)
            }
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkMonitor.stop()
        serviceScope.cancel()
        super.onDestroy()
        participants.forEach { it.disconnectAll() }
    }

    private fun buildNotification(): Notification {
        val activeByParticipant = participants.map { it.activeSessions }
        val count = activeByParticipant.sumOf { it.size }
        val labels = activeByParticipant
            .flatMap { sessions -> sessions.distinctBy { it.profileId } }
            .joinToString(", ") { it.label }

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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_haven_notification)
            .setContentTitle("Haven — $count active session${if (count != 1) "s" else ""}")
            .setContentText(labels.ifEmpty { "Connecting..." })
            .setOngoing(true)
            .setContentIntent(contentPending)
            .addAction(
                R.drawable.ic_haven_notification,
                "Disconnect All",
                disconnectPending,
            )
            .build()
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
