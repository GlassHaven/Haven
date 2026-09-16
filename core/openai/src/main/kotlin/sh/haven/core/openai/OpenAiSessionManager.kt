package sh.haven.core.openai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OpenAiSessionMgr"

/** Everything a connect (or a chat call) needs beyond the manager's own state. */
data class OpenAiConnectParams(
    val baseUrl: String,
    val pathPrefix: String? = null,
    val apiKey: String? = null,
    /** Wire protocol of the endpoint (default: OpenAI-compatible). */
    val protocol: AiProtocol = AiProtocol.OPENAI,
    /**
     * Socket factory from `TunnelResolver.socketFactory(profile)`; null means
     * direct. [tunnelConfigured] + null factory is refused in [OpenAiSessionManager.connectSession]
     * so a misconfigured tunnel can never silently leak a direct dial.
     */
    val socketFactory: javax.net.SocketFactory? = null,
    val tunnelConfigured: Boolean = false,
)

/**
 * Tracks live OpenAI-endpoint sessions, mirroring [MailSessionManager] minus
 * the engine indirection: there is one client ([OpenAiClient]) and one session
 * per profile. A session owns its own [OkHttpClient] because tunnel socket
 * factories differ per profile.
 *
 * `connectSession` resolves nothing itself — the caller builds
 * [OpenAiConnectParams] (including the fail-closed tunnel check, as
 * `buildImapParams` does) — but re-asserts that contract so a future caller
 * can't skip it.
 */
@Singleton
class OpenAiSessionManager @Inject constructor(
    private val client: OpenAiClient,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val models: List<ModelInfo> = emptyList(),
        val baseUrl: String = "",
        val protocol: AiProtocol = AiProtocol.OPENAI,
        val errorMessage: String? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Connected sessions — used by the registry's foreground keep-alive check. */
    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter { it.status == SessionState.Status.CONNECTED }

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
     * Probe `GET /v1/models` for [sessionId] and, on success, populate the
     * model list and mark it CONNECTED. Failure marks the session ERROR and
     * rethrows the underlying [OpenAiException].
     */
    suspend fun connectSession(sessionId: String, params: OpenAiConnectParams) {
        val state = _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        if (params.tunnelConfigured && params.socketFactory == null) {
            val message = "Tunnel configured but provides no socket factory — refusing to connect OpenAI directly."
            failSession(sessionId, message)
            throw IllegalStateException(message)
        }
        try {
            val http = client.buildClient(params.socketFactory)
            when (val result = client.verify(http, params.baseUrl, params.pathPrefix, params.apiKey, params.protocol)) {
                is VerifyResult.Ok -> markConnected(
                    sessionId,
                    params.baseUrl,
                    result.models,
                    params.protocol,
                )
                is VerifyResult.Failure -> throw result.error
            }
        } catch (e: OpenAiException) {
            Log.w(TAG, "OpenAI verify failed for session $sessionId", e)
            failSession(sessionId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun markConnected(sessionId: String, baseUrl: String, models: List<ModelInfo>, protocol: AiProtocol) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                baseUrl = baseUrl,
                models = models,
                protocol = protocol,
                errorMessage = null,
            ))
        }
    }

    private fun failSession(sessionId: String, message: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.ERROR,
                errorMessage = message,
            ))
        }
    }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    fun getSessionIdForProfile(profileId: String): String? =
        _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.sessionId

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    /** The connected session's models, for the chat screen's model picker. */
    fun modelsForProfile(profileId: String): List<ModelInfo> =
        _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.models
            .orEmpty()

    fun removeSession(sessionId: String) {
        _sessions.update { it - sessionId }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
    }
}