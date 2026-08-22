package sh.haven.core.ssh

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all transport session managers.
 *
 * Provides operations that apply across all transports, preventing bugs where
 * a new transport is added but forgotten in disconnect/cleanup paths. Adding a
 * transport means contributing one [TransportSessionManager] `@IntoSet`
 * binding — every call site here is then covered.
 *
 * Transports are contributed rather than named (#510): naming them put a
 * compile dependency on every transport module in `:core:ssh`, which is why a
 * terminal-only build could not drop the RDP client.
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
     * Write raw input to whichever transport owns [sessionId]. Session ids
     * are UUIDs, so at most one manager claims the id — its error (e.g.
     * "has no active terminal") is the informative one; a manager that has
     * never heard of the id says "No <transport> session". Covers every
     * transport with a PTY-like input; #366 was the forgotten-transport bug
     * again — agent input tried SSH+local only, so mosh/ET/Reticulum
     * sessions answered "No local session" while snapshot reads worked.
     *
     * Returns the [TransportSessionManager.inputName] of the transport that
     * accepted the write. Callers surface it so a delivery ack says WHERE the
     * bytes went: #555 reported `delivered: true` from a local shell that
     * never saw them, and the ack carried nothing to tell "the local transport
     * took it and lost it" apart from "a different transport claimed the id".
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
        // A "No <transport> session: id" error just means that manager never
        // owned the id; anything else is a real diagnosis from the owner.
        throw IllegalStateException(
            errors.firstOrNull { !it.startsWith("No ") }
                ?: "No terminal session $sessionId on any transport " +
                "(${writable.joinToString(", ") { it.inputName!! }})",
        )
    }

    /**
     * True if the FGS should stay running. Any active transport session
     * keeps it alive (the original semantics), as does any registered
     * [ForegroundKeepAlive] — currently just the MCP endpoint, which
     * runs in the Application process and dies with it without the
     * FGS keep-alive.
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
