package sh.haven.core.ssh

/**
 * Universal view of a session across all transports. Each manager's
 * transport-specific SessionState is mapped to this view by SessionManagerRegistry.
 *
 * Cross-cutting concerns (agent inspection, audit logging, future workspace
 * restore) operate against this interface rather than per-transport state types.
 */
interface Session {
    val sessionId: String
    val profileId: String
    val label: String
    val status: SessionStatus
    val transport: Transport

    /**
     * The session-manager (tmux/zellij/screen) session this tab is attached
     * to, or null for a plain shell / a transport without one. Captured into a
     * workspace item so restore can reattach to the same session by name
     * instead of prompting.
     */
    val sessionName: String?

    /**
     * Display label of the session manager wrapping this session
     * ("tmux"/"zellij"/"screen"/"byobu"), or null for a plain shell / a
     * transport without one. Shown in the save-workspace picker so a row
     * reads "<host> tmux <name>" rather than an opaque id.
     */
    val sessionManagerLabel: String?
}

enum class SessionStatus { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }

// RCLONE and GUEST are last so the existing ordinals are unchanged. RCLONE
// names a transport that contributes no sessions — rclone remotes are storage
// handles that are disconnected with everything else but never listed (#363) —
// and exists so its registry entry can identify itself honestly rather than
// borrowing another transport's name. GUEST is the UML guest, which follows the
// LOCAL pattern but runs a real kernel as its process.
enum class Transport { SSH, MOSH, ET, RETICULUM, LOCAL, RDP, SMB, MAIL, BTSERIAL, BLESERIAL, USBSERIAL, RCLONE, GUEST }
