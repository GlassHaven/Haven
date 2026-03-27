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
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rdp.RdpSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import javax.inject.Inject

@AndroidEntryPoint
class SshConnectionService : Service() {

    @Inject
    lateinit var sessionManager: SshSessionManager

    @Inject
    lateinit var reticulumSessionManager: ReticulumSessionManager

    @Inject
    lateinit var moshSessionManager: MoshSessionManager

    @Inject
    lateinit var etSessionManager: EtSessionManager

    @Inject
    lateinit var rdpSessionManager: RdpSessionManager

    @Inject
    lateinit var localSessionManager: LocalSessionManager

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
            sessionManager.disconnectAll()
            reticulumSessionManager.disconnectAll()
            moshSessionManager.disconnectAll()
            etSessionManager.disconnectAll()
            rdpSessionManager.disconnectAll()
            localSessionManager.disconnectAll()
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
        sessionManager.disconnectAll()
        reticulumSessionManager.disconnectAll()
        moshSessionManager.disconnectAll()
        etSessionManager.disconnectAll()
        rdpSessionManager.disconnectAll()
        localSessionManager.disconnectAll()
    }

    private fun buildNotification(): Notification {
        val sshActive = sessionManager.activeSessions
        val rnsActive = reticulumSessionManager.activeSessions
        val moshActive = moshSessionManager.activeSessions
        val etActive = etSessionManager.activeSessions
        val rdpActive = rdpSessionManager.activeSessions
        val localActive = localSessionManager.activeSessions
        val count = sshActive.size + rnsActive.size + moshActive.size + etActive.size + rdpActive.size + localActive.size

        val sshLabels = sshActive.distinctBy { it.profileId }.map { it.label }
        val rnsLabels = rnsActive.distinctBy { it.profileId }.map { it.label }
        val moshLabels = moshActive.distinctBy { it.profileId }.map { it.label }
        val etLabels = etActive.distinctBy { it.profileId }.map { it.label }
        val rdpLabels = rdpActive.distinctBy { it.profileId }.map { it.label }
        val localLabels = localActive.distinctBy { it.profileId }.map { it.label }
        val labels = (sshLabels + rnsLabels + moshLabels + etLabels + rdpLabels + localLabels).joinToString(", ")

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
