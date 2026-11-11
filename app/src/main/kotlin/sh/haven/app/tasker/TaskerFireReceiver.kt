package sh.haven.app.tasker

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.haven.app.R
import sh.haven.app.agent.HeadlessSshExec
import javax.inject.Inject

/**
 * Fires the Locale/Tasker "Run command on a Haven server" action (#367).
 *
 * The host broadcasts [TaskerPlugin.ACTION_FIRE_SETTING] with the config
 * Bundle. Two modes:
 * - **overlay** — bring Haven to the front and run the command in a visible
 *   terminal so the user can watch it (routed through [MainActivity] via a
 *   `haven://run` deep link).
 * - **headless** — run over the exec channel via [HeadlessSshExec] and post a
 *   result notification with the exit code + a stdout snippet. When **block**
 *   is set, the ordered broadcast is held via [goAsync] until the command
 *   finishes, so a host configured to wait pauses the macro. (A `goAsync`
 *   window is bounded by the OS — see the block caveat below; long commands
 *   are better watched in overlay mode.)
 */
@AndroidEntryPoint
class TaskerFireReceiver : BroadcastReceiver() {

    @Inject lateinit var headlessSshExec: HeadlessSshExec
    @Inject lateinit var preferencesRepository: sh.haven.core.data.preferences.UserPreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskerPlugin.ACTION_FIRE_SETTING) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val secret = preferencesRepository.taskerPluginSecret()
                val bundle = TaskerPlugin.verifiedConfigFrom(intent, secret)
                if (bundle == null) {
                    Log.w(TAG, "ignoring fire with unauthenticated or invalid config")
                    pending.finish()
                    return@launch
                }
                val profileId = bundle.getString(TaskerPlugin.BUNDLE_PROFILE_ID)!!
                val label = bundle.getString(TaskerPlugin.BUNDLE_PROFILE_LABEL) ?: profileId
                val command = bundle.getString(TaskerPlugin.BUNDLE_COMMAND)!!
                val overlay = bundle.getBoolean(TaskerPlugin.BUNDLE_OVERLAY, false)
                val block = bundle.getBoolean(TaskerPlugin.BUNDLE_BLOCK, false)

                if (overlay) {
                    postWatchNotification(appContext, profileId, label, command)
                    pending.finish()
                    return@launch
                }

                if (!block) {
                    // Fire-and-forget: let the macro continue immediately, run
                    // on the app-process scope so we survive the receiver. No
                    // variable passback — the host has already moved on.
                    pending.finish()
                    runAndNotify(appContext, profileId, label, command, passbackTo = null)
                } else {
                    // Hold the ordered broadcast until the command finishes, and
                    // return its output to the host as variables.
                    runAndNotify(appContext, profileId, label, command, passbackTo = pending)
                    pending.finish()
                }
            } catch (e: Exception) {
                Log.w(TAG, "fire failed: ${e.message}")
                runCatching { pending.finish() }
            }
        }
    }

    private suspend fun runAndNotify(
        context: Context,
        profileId: String,
        label: String,
        command: String,
        passbackTo: PendingResult?,
    ) {
        val (title, text) = try {
            val outcome = headlessSshExec.run(profileId, command, TIMEOUT_MS)
            val r = outcome.exec
            passbackTo?.let { setPassback(it, r.stdout, r.stderr, r.exitStatus) }
            val snippet = r.stdout.ifBlank { r.stderr }.trim().take(SNIPPET_CHARS)
            val head = if (r.timedOut) {
                context.getString(R.string.tasker_result_timed_out, label)
            } else {
                context.getString(R.string.tasker_result_exit, label, r.exitStatus)
            }
            head to snippet
        } catch (e: Exception) {
            passbackTo?.let { setPassback(it, "", e.message ?: "", -1) }
            context.getString(R.string.tasker_result_failed, label) to (e.message ?: "")
        }
        notify(context, title, text)
    }

    /**
     * Return the command's output to Tasker/MacroDroid as local variables on
     * the ordered-broadcast result (%hstdout / %hstderr / %hexit). Only has an
     * effect when the host waits for the result (our "wait until finished"
     * mode) — a fire-and-forget host has already continued.
     */
    private fun setPassback(pending: PendingResult, stdout: String, stderr: String, exit: Int) {
        val vars = Bundle().apply {
            putString(TaskerPlugin.VAR_STDOUT, stdout)
            putString(TaskerPlugin.VAR_STDERR, stderr)
            putString(TaskerPlugin.VAR_EXIT, exit.toString())
        }
        val extras = pending.getResultExtras(true) ?: Bundle()
        extras.putBundle(TaskerPlugin.EXTRA_VARIABLES_BUNDLE, vars)
        pending.setResultExtras(extras)
        pending.setResultCode(Activity.RESULT_OK) // TaskerPlugin RESULT_CODE_OK
        Log.i(TAG, "passback set: %hexit=$exit stdout=${stdout.length}b stderr=${stderr.length}b")
    }

    private fun notify(context: Context, title: String, text: String) {
        ensureChannel(context, CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        post(context, NOTIF_ID, n)
    }

    /**
     * Overlay mode's BAL-safe entry point: a heads-up tap-to-watch notification
     * whose [PendingIntent] foregrounds [sh.haven.app.MainActivity] with the
     * profile + command. The user's tap carries the activity-start privilege
     * BAL denies us, so MainActivity (now foreground) can connect and stream
     * the command into a visible terminal.
     */
    private fun postWatchNotification(context: Context, profileId: String, label: String, command: String) {
        ensureChannel(context, WATCH_CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
        val tapIntent = Intent(context, sh.haven.app.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_RUN_PROFILE_ID, profileId)
            putExtra(EXTRA_RUN_COMMAND, command)
        }
        val pi = PendingIntent.getActivity(
            context,
            profileId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(R.string.tasker_watch_text, command)
        val n = NotificationCompat.Builder(context, WATCH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(context.getString(R.string.tasker_watch_title, label))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        post(context, WATCH_NOTIF_ID, n)
    }

    private fun ensureChannel(context: Context, id: String, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(id, context.getString(R.string.tasker_channel_name), importance),
            )
        }
    }

    /** Best-effort post — a no-op on API 33+ without POST_NOTIFICATIONS. */
    private fun post(context: Context, id: Int, n: android.app.Notification) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(context).notify(id, n) }
        }
    }

    companion object {
        private const val TAG = "TaskerFire"
        private const val CHANNEL_ID = "tasker_result"
        private const val WATCH_CHANNEL_ID = "tasker_watch"
        private const val NOTIF_ID = 0x7A5C
        private const val WATCH_NOTIF_ID = 0x7A5D
        private const val TIMEOUT_MS = 120_000L
        private const val SNIPPET_CHARS = 400

        /** MainActivity reads these on the tap-to-watch launch. */
        const val EXTRA_RUN_PROFILE_ID = "sh.haven.tasker.run.profileId"
        const val EXTRA_RUN_COMMAND = "sh.haven.tasker.run.command"

        /** App-process scope so a fire-and-forget command survives the receiver. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
