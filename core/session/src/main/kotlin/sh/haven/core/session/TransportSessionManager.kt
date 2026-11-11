package sh.haven.core.session

/**
 * One transport's contribution to the cross-transport operations in
 * [SessionManagerRegistry].
 *
 * Transports contribute themselves with `@IntoSet` against this lightweight
 * `:core:session` contract so neither the registry nor individual transport
 * modules need a compile dependency on `:core:ssh`.
 */
interface TransportSessionManager {

    /** Identifies this transport in the unified [Session] view. */
    val transport: Transport

    /** Disconnect every session this transport holds for [profileId]. */
    fun removeAllSessionsForProfile(profileId: String)

    /**
     * Every session this transport knows about as a unified [Session],
     * including inactive ones (DISCONNECTED, ERROR), so consumers can present
     * a full registered-session list rather than only live ones.
     */
    val sessions: List<Session> get() = emptyList()

    /** How many sessions are CONNECTED / CONNECTING / RECONNECTING. */
    val activeSessionCount: Int get() = 0

    /**
     * Display name for this transport in terminal-input errors, or null when
     * it has no PTY-like input to write to. Doubles as the opt-in for
     * [sendInput] — [SessionManagerRegistry] only offers a session id to
     * transports that name themselves here.
     */
    val inputName: String? get() = null

    /**
     * Write raw input to [sessionId] if this transport owns it.
     *
     * @throws IllegalStateException when it does not. The message must start
     *   with "No " for the not-mine case — the registry uses that prefix to
     *   tell "never heard of this id" apart from the owner's real diagnosis.
     */
    fun sendInput(sessionId: String, text: String): Unit =
        throw IllegalStateException("No $inputName session: $sessionId")
}
