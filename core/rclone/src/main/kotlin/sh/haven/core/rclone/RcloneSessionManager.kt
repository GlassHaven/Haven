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
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RcloneSessionMgr"

@Singleton
class RcloneSessionManager @Inject constructor(
    private val client: RcloneClient,
    @ApplicationContext private val context: Context,
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

    /** Background thread for blocking OAuth config/create calls. */
    private val oauthExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rclone-oauth").apply { isDaemon = true }
    }

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

        // Run config/create on the OAuth thread (blocks until OAuth completes).
        // On any throw — user dismissed the browser, callback never reached
        // rclone's localhost listener, network blip during token exchange,
        // bad client_id in the rclone build, etc. — propagate the message
        // to the SessionState so the ConnectionsViewModel can surface a
        // toast instead of leaving the user on a silent spinner.
        oauthExecutor.execute {
            try {
                client.createRemote(remoteName, provider)
                Log.d(TAG, "OAuth completed for '$remoteName'")
                markConnected(sessionId, remoteName, provider)
            } catch (e: Exception) {
                Log.e(TAG, "OAuth failed for '$remoteName'", e)
                failSession(
                    sessionId,
                    "rclone OAuth failed: ${e.message ?: e.javaClass.simpleName}",
                )
            } finally {
                urlMonitor.interrupt()
            }
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
