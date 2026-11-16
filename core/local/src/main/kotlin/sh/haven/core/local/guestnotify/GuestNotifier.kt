package sh.haven.core.local.guestnotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts Android notifications on behalf of Linux guest programs (#851,
 * command-first bridge). The [NotifyGuestServer] reads a `haven-notify`
 * request off the abstract socket and calls [post].
 *
 * Uses a dedicated [CHANNEL_ID] so the user can mute guest notifications
 * without silencing Haven's own connection/renewal/agent notifications —
 * posting on the agent-test channel would repeat the regression documented
 * in `MailRuleNotifier` (muting spam would mute real agent notifications).
 *
 * Deliberately NO content `PendingIntent`: the title/body are guest-supplied,
 * so a blind launch intent would be a phishing surface (fake system dialogs).
 * `setAutoCancel(true)` lets a tap dismiss the notification without routing
 * anywhere. Model: [sh.haven.core.stepca.RenewalNotifier] / `MailRuleNotifier`.
 */
@Singleton
class GuestNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface PostResult {
        data class Posted(val id: Int) : PostResult
        data object Disabled : PostResult
        data class Failed(val error: String) : PostResult
    }

    private val nextId = AtomicInteger(50_000)

    fun post(title: String, body: String): PostResult {
        val mgr = NotificationManagerCompat.from(context)
        if (!mgr.areNotificationsEnabled()) return PostResult.Disabled
        ensureChannel()
        val id = nextId.getAndIncrement()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        return try {
            mgr.notify(id, notification)
            PostResult.Posted(id)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
            PostResult.Failed(e.message ?: "permission denied")
        }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Guest notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications posted by Linux guest programs via haven-notify (#851)."
            },
        )
    }

    companion object {
        const val CHANNEL_ID: String = "guest.notifications"
    }
}
