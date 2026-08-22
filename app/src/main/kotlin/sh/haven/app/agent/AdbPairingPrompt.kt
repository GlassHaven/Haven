package sh.haven.app.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Asks for the adb pairing code with a direct-reply notification (#575).
 *
 * This replaces an overlay window, which cannot work and never could. Android's
 * Settings windows set `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` — captured directly on
 * a device:
 *
 * ```
 * Window{… com.android.settings.SubSettings}:
 *   pfl=… HIDE_NON_SYSTEM_OVERLAY_WINDOWS …
 * ```
 *
 * so the platform hides every third-party `TYPE_APPLICATION_OVERLAY` while a
 * Settings screen is in front. The overlay window was present in the window
 * list the whole time and simply not drawn. That flag is anti-tapjacking, and
 * overlaying a system permission dialog is exactly what it exists to prevent —
 * so there is no workaround to look for, only a different mechanism.
 *
 * A notification is that mechanism, and it is strictly better than what it
 * replaces:
 *  - the reply field is drawn by system UI, so the hide-overlay flag does not
 *    apply and the pairing dialog stays visible with its code readable;
 *  - it needs no SYSTEM_ALERT_WINDOW, so the restricted-settings block that
 *    gates that permission for sideloaded apps is irrelevant;
 *  - on a high-importance channel it arrives as a heads-up at the top of the
 *    screen, above the dialog, without taking focus from it.
 */
internal class AdbPairingPrompt(private val context: Context) {

    /**
     * Post the prompt. [onCode] fires on the main thread when the user sends a
     * reply. Returns false if the notification could not be posted (no
     * POST_NOTIFICATIONS grant), which is a real outcome the caller reports
     * rather than a crash.
     */
    fun show(endpoint: AdbPairingDiscovery.Endpoint, onCode: (String) -> Unit): Boolean {
        ensureChannel()
        cancel()

        // Dynamically registered so the receiver's lifetime is the prompt's:
        // a manifest receiver would outlive the pairing attempt and have to
        // find the waiting latch again from a cold process.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
                unregister()
                // A bare cancel() does NOT reliably dismiss a notification that
                // is mid-reply: after the user sends, the system holds it in a
                // "sending" state showing a spinner, and it expects the app to
                // REPLACE the notification to signal the reply landed. Cancel
                // alone left the entry box on screen with the code already
                // delivered — reported from a device. Re-posting on the same id
                // without the RemoteInput action is what clears it.
                // Two steps, and both are needed. Replacing the notification
                // takes the reply action away; cancelling removes it entirely.
                // A cancel issued straight from onReceive is swallowed — the
                // system is still animating the reply it just accepted — which
                // is why the box previously survived until the NEXT cancel,
                // seconds later when the code was collected. Reported as "it
                // cleared after pairing, not after entering the code". So:
                // replace now, cancel once the animation has had its moment.
                acknowledge(if (code.isEmpty()) "No code received" else "Code received")
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ cancel() }, DISMISS_DELAY_MS)
                if (code.isEmpty()) {
                    Log.d(TAG, "empty pairing reply")
                    return
                }
                onCode(code)
            }
        }
        registered = receiver
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        context.registerReceiver(receiver, IntentFilter(ACTION_REPLY), flags)

        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("6-digit code")
            .build()
        // MUTABLE is required: the system writes the typed reply into this
        // intent's extras, which an immutable PendingIntent forbids.
        val replyIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_REPLY).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Enter code",
            replyIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val body = "Type the 6-digit code from the pairing dialog.\n${endpoint.host}:${endpoint.port}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pair a computer with adb")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(action)
            // The port dies with the dialog, so this must not linger as a
            // reply box for a pairing that can no longer succeed.
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "no POST_NOTIFICATIONS grant: ${e.message}")
            unregister()
            false
        }
    }

    /**
     * Replace the reply notification with a plain one that expires by itself.
     *
     * Replacement rather than cancellation because the system will not drop a
     * notification it believes is still receiving a reply; and self-expiring
     * rather than permanent because the acknowledgement is worth about two
     * seconds of the user's attention and nothing after that.
     */
    private fun acknowledge(text: String) {
        runCatching {
            val done = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pair a computer with adb")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setTimeoutAfter(ACK_TIMEOUT_MS)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, done)
        }.onFailure { Log.d(TAG, "ack not posted: ${it.message}") }
    }

    fun cancel() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    fun unregister() {
        registered?.let { r -> runCatching { context.unregisterReceiver(r) } }
        registered = null
    }

    private var registered: BroadcastReceiver? = null

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // HIGH so it arrives as a heads-up at the top of the screen, over the
        // pairing dialog — the placement the whole design depends on.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "adb pairing",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Asks for the wireless-debugging pairing code while the system's " +
                "pairing dialog is on screen."
        }
        nm.createNotificationChannel(channel)
    }

    internal companion object {
        const val TAG = "AdbPairingPrompt"
        const val CHANNEL_ID = "agent.adb.pairing"
        const val KEY_CODE = "pairing_code"
        const val ACTION_REPLY = "sh.haven.app.ADB_PAIRING_CODE"
        const val NOTIFICATION_ID = 0x0577

        /** Long enough to read, short enough not to become litter. */
        const val ACK_TIMEOUT_MS = 2_500L

        /**
         * Long enough for the system to finish accepting the reply, short
         * enough that the box visibly clears on the keystroke rather than
         * whenever the agent next happens to act.
         */
        const val DISMISS_DELAY_MS = 600L
    }
}
