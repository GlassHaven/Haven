package sh.haven.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.haven.app.R
import sh.haven.core.data.update.UpdateChecker
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "haven_updates"
private const val NOTIFICATION_TAG = "haven_update"
private const val NOTIFICATION_ID = 1
private const val TAG = "UpdateNotifier"

/**
 * Launch-time half of the opt-in update check (#578).
 *
 * Every decision about whether to look at all lives in
 * [UpdateChecker.checkOnLaunch] — preference off, wrong signing key, checked
 * within the last day, or a version the user has already been told about all
 * return null there, and this class posts nothing. So the common case is one
 * suspend call that makes no network request and touches no UI.
 *
 * A notification rather than a dialog: the check finishes some seconds after
 * launch, and stealing focus from whatever the user opened Haven to do is not
 * a reasonable trade for news that can wait.
 */
@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateChecker: UpdateChecker,
) {
    private val started = AtomicBoolean(false)

    /** Idempotent; safe to call from `Application.onCreate`. */
    fun start(scope: CoroutineScope, nowMs: Long = System.currentTimeMillis()) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val available = runCatching { updateChecker.checkOnLaunch(nowMs) }
                .onFailure { Log.w(TAG, "launch update check failed", it) }
                .getOrNull() ?: return@launch
            notify(available)
        }
    }

    private fun notify(available: UpdateChecker.Result.Available) {
        val mgr = NotificationManagerCompat.from(context)
        if (!mgr.areNotificationsEnabled()) {
            Log.i(TAG, "update ${available.version} found but notifications are off")
            return
        }
        ensureChannel()
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, available.releaseUrl.toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_title, available.version))
            .setContentText(context.getString(R.string.update_notification_text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            mgr.notify(NOTIFICATION_TAG, NOTIFICATION_ID, n)
            Log.i(TAG, "notified about ${available.version}")
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the notify.
        }
    }

    private fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
