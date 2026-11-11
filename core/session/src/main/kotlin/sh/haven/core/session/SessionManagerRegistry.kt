package sh.haven.core.session

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all transport session managers.
 *
 * Provides operations that apply across all transports, preventing bugs where
 * a new transport is added but forgotten in disconnect/cleanup paths. Adding a
 * transport means contributing one [TransportSessionManager] `@IntoSet`
 * binding — every call site here is then covered.
 */
@Singleton
class SessionManagerRegistry @Inject constructor(
    private val transports: Set<@JvmSuppressWildcards TransportSessionManager>,
    private val keepAlives: Set<@JvmSuppressWildcards ForegroundKeepAlive>,
) {
    /**
     * Set iteration order is undefined, and two of the operations below are
     * user-visible in their ordering (the session list, and the transport
     * names in a no-owner error). Sorting by the [Transport] declaration order
     * makes both stable across builds.
     */
    private val ordered: List<TransportSessionManager>
        get() = transports.sortedBy { it.transport.ordinal }

    /** Disconnect all sessions for a profile across all transports. */
    fun disconnectProfile(profileId: String) {
        transports.forEach { it.removeAllSessionsForProfile(profileId) }
    }

    /**
     * Write raw input to whichever transport owns [sessionId].
     *
     * Returns the [TransportSessionManager.inputName] of the transport that
     * accepted the write.
     *
     * @throws IllegalStateException when no transport delivers.
     */
    fun sendTerminalInput(sessionId: String, text: String): String {
        val writable = ordered.filter { it.inputName != null }
        val errors = mutableListOf<String>()
        for (transport in writable) {
            try {
                transport.sendInput(sessionId, text)
                return transport.inputName!!
            } catch (e: IllegalStateException) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException(
            errors.firstOrNull { !it.startsWith("No ") }
                ?: "No terminal session $sessionId on any transport " +
                "(${writable.joinToString(", ") { it.inputName!! }})",
        )
    }

    /**
     * True if the FGS should stay running. Any active transport session
     * keeps it alive, as does any registered [ForegroundKeepAlive].
     */
    fun hasActiveSessions(): Boolean =
        transports.any { it.activeSessionCount > 0 } || keepAlives.any { it.isActive }

    /**
     * All sessions across all transports as a unified [Session] view.
     * Includes inactive sessions (DISCONNECTED, ERROR) so consumers can present
     * a full registered-session list, not just live ones.
     */
    val allSessions: List<Session>
        get() = ordered.flatMap { it.sessions }

    /** All sessions belonging to a single profile, across all transports. */
    fun sessionsForProfile(profileId: String): List<Session> =
        allSessions.filter { it.profileId == profileId }
}
