package sh.haven.core.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.ssh.KeyboardInteractiveChallenge
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a tunnel's sign-in questions (an SMS code, a password that isn't
 * stored) to the user, from wherever the question arises. A tunnel can need
 * one while no screen is involved — re-signing in after the gateway expired
 * the session during a background reconnect — so this is app-scoped rather
 * than owned by a ViewModel: it publishes [pending] for the top-of-tree
 * `TunnelAuthHost` to render with the existing [KeyboardInteractiveChallenge]
 * dialog, and posts a heads-up notification that brings Haven forward. The
 * notification also helps when the code arrives by SMS: read it in the
 * messages app, tap the notification, type it.
 *
 * One question at a time; a second tunnel asking waits its turn.
 */
@Singleton
class TunnelAuthBroker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** One question awaiting the user. Answer with [submit] or [cancel]. */
    class Request internal constructor(
        val id: Long,
        val challenge: KeyboardInteractiveChallenge,
        private val answer: CompletableDeferred<String?>,
    ) {
        fun submit(value: String) {
            answer.complete(value)
        }

        fun cancel() {
            answer.complete(null)
        }
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val turn = Mutex()
    private val ids = AtomicLong()

    /**
     * Ask the user and block the calling (native) thread until they answer,
     * cancel, or [PROMPT_TIMEOUT_MS] passes. Returns null on cancel/timeout,
     * which the native side turns into "sign-in cancelled". Never call on
     * the main thread.
     */
    fun promptBlocking(
        tunnelLabel: String,
        title: String,
        message: String,
        fieldLabel: String,
        secret: Boolean,
    ): String? = runBlocking {
        turn.withLock {
            val answer = CompletableDeferred<String?>()
            val request = Request(
                id = ids.incrementAndGet(),
                challenge = KeyboardInteractiveChallenge(
                    destination = tunnelLabel.ifBlank { "VPN" },
                    name = title,
                    instruction = message,
                    prompts = listOf(
                        KeyboardInteractiveChallenge.Prompt(
                            text = fieldLabel.ifBlank { if (secret) "Password" else "Code" },
                            echo = !secret,
                        ),
                    ),
                ),
                answer = answer,
            )
            _pending.value = request
            postNotification(tunnelLabel, message, secret)
            try {
                withTimeoutOrNull(PROMPT_TIMEOUT_MS) { answer.await() }
            } finally {
                _pending.value = null
                cancelNotification()
            }
        }
    }

    private fun postNotification(tunnelLabel: String, message: String, secret: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!nm.areNotificationsEnabled()) return // the in-app dialog still covers the foreground case
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VPN sign-in",
                    NotificationManager.IMPORTANCE_HIGH, // heads-up: a connection is waiting
                ).apply { description = "Shown when a VPN tunnel needs a code or password from you" },
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK) }
        val pending = launch?.let {
            PendingIntent.getActivity(
                context, NOTIFICATION_ID, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val what = if (secret) "your password" else "your code"
        val name = tunnelLabel.ifBlank { "VPN" }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("$name needs $what")
            .setContentText(message.ifBlank { "Tap to sign in" })
            .setStyle(Notification.BigTextStyle().bigText(message.ifBlank { "Tap to sign in" }))
            .setCategory(Notification.CATEGORY_CALL) // time-sensitive: the gateway times the sign-in out
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val CHANNEL_ID = "haven_tunnel_auth"
        private const val NOTIFICATION_ID = 0x7a11

        /**
         * Long enough to fetch an SMS, short enough that a forgotten prompt
         * doesn't hold the sign-in forever. Gateways time out sign-ins on
         * their own too (ocserv 4 min by default).
         */
        const val PROMPT_TIMEOUT_MS = 5 * 60_000L
    }
}
