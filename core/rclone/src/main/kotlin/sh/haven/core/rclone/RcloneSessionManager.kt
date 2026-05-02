package sh.haven.core.rclone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.repository.ConnectionLogRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RcloneSessionMgr"

/** OAuth flow timeout — no browser callback within this window = abort. */
private const val OAUTH_TIMEOUT_MS = 5L * 60 * 1000

@Singleton
class RcloneSessionManager @Inject constructor(
    private val client: RcloneClient,
    @ApplicationContext private val context: Context,
    private val connectionLogRepository: ConnectionLogRepository,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val remoteName: String = "",
        val provider: String = "",
        /**
         * Populated when [status] is [Status.ERROR] — the underlying
         * exception's message, surfaced verbatim so the UI can show
         * "rclone: invalid client_id" instead of the silent-spinner
         * behaviour we used to have.
         */
        val errorMessage: String? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    /**
     * Worker pool for blocking OAuth `config/create` calls. Cached
     * pool because the gomobile binding doesn't honour Java thread
     * interrupts: when an OAuth times out, the worker thread is
     * effectively leaked until process restart. A bounded pool would
     * deadlock once the leak count hit the bound; cached + daemon
     * threads keeps retries working at the cost of letting a few stuck
     * threads sit there until app restart. Documented limitation.
     */
    private val oauthExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "rclone-oauth").apply { isDaemon = true }
    }

    /**
     * Single-thread scheduler for the per-session timeout watchers.
     * One thread is plenty because watchers do almost nothing — they
     * call `Future.get(timeout)` and dispatch the result.
     */
    private val oauthWatchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rclone-oauth-watch").apply { isDaemon = true }
    }

    /** Tracks in-flight OAuth futures so cancelPendingOAuth can reach them. */
    private val oauthFutures = ConcurrentHashMap<String, Future<*>>()

    fun registerSession(profileId: String, label: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
            ))
        }
        return sessionId
    }

    /**
     * Connect a session by initializing rclone and verifying the remote
     * is accessible. For OAuth providers, this starts the auth flow in
     * the background and opens the browser automatically.
     */
    fun connectSession(
        sessionId: String,
        remoteName: String,
        provider: String,
    ) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        Log.d(TAG, "Connecting rclone session: remote=$remoteName provider=$provider")

        // Ensure rclone is initialized
        client.initialize()

        // Check if remote is configured and working
        val remotes = client.listRemotes()
        Log.d(TAG, "Configured remotes: $remotes")

        if (remoteName in remotes) {
            // Remote exists — verify it works
            try {
                client.listDirectory(remoteName, "")
                // It works — mark connected
                markConnected(sessionId, remoteName, provider)
                return
            } catch (e: RcloneException) {
                if ("token" in e.error.lowercase()) {
                    Log.w(TAG, "Remote '$remoteName' has invalid token, deleting for re-auth")
                    client.deleteRemote(remoteName)
                } else {
                    throw e
                }
            }
        }

        // Remote needs to be created (with OAuth). This blocks until the user
        // completes the browser flow, so run it on a background thread and
        // open the browser automatically.
        Log.d(TAG, "Starting OAuth flow for '$remoteName' (type=$provider)")
        startOAuthFlow(sessionId, remoteName, provider)
    }

    private fun startOAuthFlow(sessionId: String, remoteName: String, provider: String) {
        // Monitor logcat for the auth URL in a separate thread.
        // Failures here mark the session ERROR with a useful message
        // — previously this branch only logged and the user saw an
        // endless spinner with no idea what went wrong.
        val urlMonitor = Thread({
            var process: Process? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-v", "raw", "-s", "GoLog:*",
                ))
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val authUrlMatch = Regex("""(http://127\.0\.0\.1:53682/auth\?state=\S+)""")
                        .find(line ?: "")
                    if (authUrlMatch != null) {
                        val url = authUrlMatch.groupValues[1]
                        Log.d(TAG, "Captured OAuth URL: $url")
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.e(TAG, "No browser to open OAuth URL", e)
                            failSession(
                                sessionId,
                                "No browser app installed to handle the OAuth login. Install a browser (e.g. Chrome, Firefox), then retry.",
                            )
                        }
                        break
                    }
                }
            } catch (_: InterruptedException) {
                // Expected on success/teardown — the createRemote thread
                // interrupts us in its finally block.
            } catch (e: Exception) {
                Log.e(TAG, "URL monitor failed", e)
                failSession(
                    sessionId,
                    "Couldn't capture the OAuth URL from rclone — check Settings → Connection log for details.",
                )
            } finally {
                process?.destroy()
            }
        }, "rclone-url-monitor").apply { isDaemon = true }
        urlMonitor.start()

        // Run config/create on a worker thread, watched by a separate
        // future-aware watcher so timeout + cancel can both interrupt
        // the wait without depending on the gomobile binding to honour
        // Java interrupts (which it doesn't). On any terminal state —
        // success, timeout, cancel, exception — surface a useful
        // SessionState.errorMessage and audit-log the event.
        logOAuthEvent(sessionId, "OAuth started for '$remoteName' (provider=$provider)")
        val future: Future<*> = oauthExecutor.submit {
            client.createRemote(remoteName, provider)
        }
        oauthFutures[sessionId] = future

        oauthWatchExecutor.execute {
            try {
                future.get(OAUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                Log.d(TAG, "OAuth completed for '$remoteName'")
                markConnected(sessionId, remoteName, provider)
                logOAuthEvent(sessionId, "OAuth completed for '$remoteName'")
            } catch (_: TimeoutException) {
                Log.w(TAG, "OAuth timed out after ${OAUTH_TIMEOUT_MS / 1000}s for '$remoteName'")
                future.cancel(true)  // best-effort interrupt; gomobile may not honour
                val msg = "OAuth timed out after ${OAUTH_TIMEOUT_MS / 60_000} minutes — no callback received from the browser. Tap Re-authenticate to try again."
                failSession(sessionId, msg)
                logOAuthEvent(sessionId, "OAuth timed out for '$remoteName'", failed = true)
            } catch (_: java.util.concurrent.CancellationException) {
                Log.i(TAG, "OAuth cancelled by user for '$remoteName'")
                failSession(sessionId, "OAuth cancelled.")
                logOAuthEvent(sessionId, "OAuth cancelled by user for '$remoteName'", failed = true)
            } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause ?: e
                Log.e(TAG, "OAuth failed for '$remoteName'", cause)
                val msg = "rclone OAuth failed: ${cause.message ?: cause.javaClass.simpleName}"
                failSession(sessionId, msg)
                logOAuthEvent(sessionId, msg, failed = true)
            } catch (e: Exception) {
                Log.e(TAG, "OAuth watcher unexpected exception for '$remoteName'", e)
                val msg = "rclone OAuth watcher failed: ${e.message ?: e.javaClass.simpleName}"
                failSession(sessionId, msg)
                logOAuthEvent(sessionId, msg, failed = true)
            } finally {
                urlMonitor.interrupt()
                oauthFutures.remove(sessionId)
            }
        }
    }

    /**
     * Cancel the in-flight OAuth flow for [sessionId], if any. Marks
     * the session ERROR and best-effort interrupts the worker thread.
     * The underlying gomobile call may still be running; that thread
     * will be reclaimed on app restart.
     */
    fun cancelPendingOAuth(sessionId: String) {
        val future = oauthFutures[sessionId] ?: return
        Log.i(TAG, "User cancelled OAuth for session $sessionId")
        future.cancel(true)
    }

    /** Append an OAuth lifecycle event to the per-profile audit log. */
    private fun logOAuthEvent(sessionId: String, message: String, failed: Boolean = false) {
        val profileId = _sessions.value[sessionId]?.profileId ?: return
        // Best-effort, fire-and-forget — audit must never break the
        // OAuth path. Using runBlocking on the watcher thread is fine
        // because Room writes are quick and the watcher is dedicated.
        try {
            kotlinx.coroutines.runBlocking {
                connectionLogRepository.logEvent(
                    profileId = profileId,
                    status = if (failed) ConnectionLog.Status.FAILED else ConnectionLog.Status.CONNECTED,
                    details = message,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "audit insert for OAuth event failed: ${e.message}")
        }
    }

    /** Mark a session ERROR with a human-readable [message]. */
    private fun failSession(sessionId: String, message: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.ERROR,
                errorMessage = message,
            ))
        }
    }

    private fun markConnected(sessionId: String, remoteName: String, provider: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                remoteName = remoteName,
                provider = provider,
            ))
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    fun removeSession(sessionId: String) {
        _sessions.update { it - sessionId }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun getRemoteNameForProfile(profileId: String): String? =
        _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.remoteName

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }
}
