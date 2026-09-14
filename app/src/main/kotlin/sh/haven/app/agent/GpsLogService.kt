package sh.haven.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * The location-type foreground service behind `start_gps_log` /
 * `start_ntp_service` (Android requires an FGS with the `location` type for
 * GPS updates to keep flowing while Haven is backgrounded). A thin shell:
 * [GpsBroker] owns all state and the actual location callbacks; this service
 * paints the notification ("GPS logging — N fixes; NTP :10123; ±X ms GPS
 * time") and dies when the last consumer leaves.
 *
 * Lifecycle: the broker starts it via `startForegroundService` after
 * acquiring a session, and stops it when logging/NTP stops. A system
 * restart (null-intent start after the process died) finds no logging state
 * and stops itself rather than running an empty FGS.
 */
internal class GpsLogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!GpsBroker.isLogging && !GpsBroker.isNtpRunning) {
            // Post-kill restart with no consumers: an empty location FGS
            // would keep GPS hardware awake for nothing.
            stopSelf()
            return START_NOT_STICKY
        }
        GpsBroker.serviceAttached(this)
        scheduleRefresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        GpsBroker.serviceDetached()
        super.onDestroy()
    }

    /** Called by [GpsBroker.serviceAttached] — must paint the FGS notification. */
    fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GPS logging & NTP", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live GPS logging and the GPS-disciplined NTP service"
                },
            )
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForegroundCompatWith(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundCompatWith(notification: Notification, type: Int) {
        androidx.core.app.ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, type,
        )
    }

    internal fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GPS logging & NTP", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPS service")
            .setContentText(GpsBroker.notificationText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Throttled notification refresh while the service lives (10 s). */
    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!GpsBroker.isLogging && !GpsBroker.isNtpRunning) {
                    stopSelf()
                    return
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification())
                handler.postDelayed(this, REFRESH_MS)
            }
        }, REFRESH_MS)
    }

    companion object {
        const val CHANNEL_ID = "gps.logging"
        const val NOTIFICATION_ID = 0x47_50 // "GP"
        private const val REFRESH_MS = 10_000L
    }
}