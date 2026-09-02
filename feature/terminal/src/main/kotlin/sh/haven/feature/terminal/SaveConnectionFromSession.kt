package sh.haven.feature.terminal

import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.SessionManager
import java.util.UUID

/**
 * Build / upsert a connection profile that pins the live multiplexer session
 * so a cold tap auto-attaches (same idea as a hand-edited `remoteCommand`
 * profile). Name is the user-facing label; [profileId] is the stable key.
 *
 * Distinct from [ConnectionProfile.lastSessionName] reconnect memory: this
 * writes an explicit pin via [ConnectionProfile.remoteCommand].
 */
object SaveConnectionFromSession {

    data class Draft(
        /** Proposed / existing profile id (stable; not editable in UI). */
        val profileId: String,
        /** Default label = multiplexer session name. */
        val defaultName: String,
        /** Multiplexer session name being pinned (sanitized). */
        val sessionName: String,
        val sessionManager: SessionManager,
        val sourceProfileId: String,
        /** True when [profileId] already exists and OK will update it. */
        val isUpdate: Boolean,
    )

    data class Result(
        val profile: ConnectionProfile,
        val created: Boolean,
    )

    /**
     * Remote-command pin for cold connect. Kept short and shell-simple so it
     * works over SSH exec and as `mosh-server -- <cmd>` (see #436).
     */
    fun pinRemoteCommand(manager: SessionManager, sessionName: String): String? {
        val name = SessionManager.sanitizeSessionName(sessionName)
        if (name.isBlank()) return null
        return when (manager) {
            SessionManager.NONE -> null
            SessionManager.TMUX -> "tmux new -A -s $name"
            SessionManager.ZELLIJ -> "zellij attach $name --create"
            SessionManager.SCREEN -> "screen -dRR $name"
            SessionManager.BYOBU -> "byobu new-session -A -s $name"
            SessionManager.HERDR -> "herdr --session $name"
        }
    }

    /**
     * Prepare dialog state. Upsert target = existing SSH profile with the
     * same host + username + label (after trim); otherwise a new UUID.
     */
    fun draft(
        source: ConnectionProfile,
        sessionName: String,
        manager: SessionManager,
        existing: List<ConnectionProfile>,
        defaultLabel: String = sessionName,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Draft? {
        if (!source.isSsh) return null
        val sanitized = SessionManager.sanitizeSessionName(sessionName)
        if (sanitized.isBlank()) return null
        if (manager == SessionManager.NONE) return null
        if (pinRemoteCommand(manager, sanitized) == null) return null

        val label = defaultLabel.trim().ifBlank { sanitized }
        val match = findUpsertTarget(existing, source, label)
        return Draft(
            profileId = match?.id ?: newId(),
            defaultName = label,
            sessionName = sanitized,
            sessionManager = manager,
            sourceProfileId = source.id,
            isUpdate = match != null,
        )
    }

    /**
     * Resolve the profile to write. [displayName] is the editable dialog name.
     * Re-runs upsert match so a rename in the dialog can still hit an existing card.
     */
    fun build(
        source: ConnectionProfile,
        displayName: String,
        sessionName: String,
        manager: SessionManager,
        existing: List<ConnectionProfile>,
        preferredId: String,
    ): Result? {
        if (!source.isSsh) return null
        val sanitized = SessionManager.sanitizeSessionName(sessionName)
        val standardRemote = pinRemoteCommand(manager, sanitized) ?: return null
        val label = displayName.trim().ifBlank { sanitized }
        if (label.isBlank()) return null

        val match = findUpsertTarget(existing, source, label)
        val id = match?.id ?: preferredId
        val created = match == null
        val sortOrder = match?.sortOrder
            ?: ((existing.maxOfOrNull { it.sortOrder } ?: -1) + 1)

        // Fleet/custom pins (e.g. bash …/haven-role-attach dogfood) must not be
        // clobbered by a re-Save that only re-derives the simple tmux pin — that
        // was wiping conversation-recovery wrappers and leaving empty shells.
        val remote = chooseRemoteCommand(
            standard = standardRemote,
            sourceRemote = source.remoteCommand,
            existingRemote = match?.remoteCommand,
        )

        val base = match ?: source
        val profile = base.copy(
            id = id,
            label = label,
            // Keep endpoint/auth from the live source (fresh host/key/mosh).
            host = source.host,
            port = source.port,
            username = source.username,
            sshPassword = source.sshPassword,
            authType = source.authType,
            keyId = source.keyId,
            authMethods = source.authMethods,
            totpConfirmBeforeSend = source.totpConfirmBeforeSend,
            ignoreSavedKeys = source.ignoreSavedKeys,
            connectionType = "SSH",
            jumpProfileId = source.jumpProfileId,
            sshOptions = source.sshOptions,
            useMosh = source.useMosh,
            useEternalTerminal = source.useEternalTerminal,
            etPort = source.etPort,
            proxyType = source.proxyType,
            proxyHost = source.proxyHost,
            proxyPort = source.proxyPort,
            proxyUser = source.proxyUser,
            proxyPassword = source.proxyPassword,
            identityId = source.identityId,
            groupId = match?.groupId ?: source.groupId,
            colorTag = match?.colorTag ?: source.colorTag,
            forwardAgent = source.forwardAgent,
            addressFamily = source.addressFamily,
            moshServerCommand = source.moshServerCommand,
            tunnelConfigId = source.tunnelConfigId,
            autoReconnect = source.autoReconnect,
            reconnectMaxAttempts = source.reconnectMaxAttempts,
            reconnectOnNetworkChange = source.reconnectOnNetworkChange,
            // Pin: explicit remote command + remembered name.
            remoteCommand = remote,
            requestPty = true,
            lastSessionName = sanitized,
            sessionManager = manager.name,
            // Clear interactive post-login so it cannot race the pin.
            postLoginCommand = null,
            sortOrder = sortOrder,
            lastConnected = match?.lastConnected,
        )
        return Result(profile = profile, created = created)
    }

    private fun findUpsertTarget(
        existing: List<ConnectionProfile>,
        source: ConnectionProfile,
        label: String,
    ): ConnectionProfile? =
        existing.firstOrNull {
            it.isSsh &&
                it.label == label &&
                it.host == source.host &&
                it.username == source.username &&
                it.port == source.port
        }

    /**
     * Prefer a non-standard (fleet/wrapper) remoteCommand already on the card
     * or live source over the simple multiplexer pin, so Save does not destroy
     * conversation-recovery hooks.
     */
    fun chooseRemoteCommand(
        standard: String,
        sourceRemote: String?,
        existingRemote: String?,
    ): String {
        val candidates = listOfNotNull(existingRemote, sourceRemote)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val custom = candidates.firstOrNull { !isStandardMultiplexerPin(it) }
        return custom ?: standard
    }

    /** True when [cmd] is exactly a pin written by [pinRemoteCommand] (simple form). */
    fun isStandardMultiplexerPin(cmd: String): Boolean {
        val t = cmd.trim()
        if (t.isEmpty()) return false
        // Must parse as a known pin *and* not look like a shell wrapper.
        if (sessionNameFromRemoteCommand(t) == null) return false
        if (t.contains("&&") || t.contains(';') || t.contains('|')) return false
        if (t.startsWith("bash ") || t.startsWith("sh ") || t.contains("haven-role-attach")) return false
        if (t.contains('/')) return false
        return true
    }

    /**
     * Parse a session name out of a profile [remoteCommand] pin (the simple
     * forms written by [pinRemoteCommand]). Returns null when the command is
     * not a known pin shape.
     */
    fun sessionNameFromRemoteCommand(remoteCommand: String?): String? {
        val cmd = remoteCommand?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // tmux new -A -s NAME  |  byobu new-session -A -s NAME
        Regex("""(?:tmux|byobu)\s+(?:new(?:-session)?)\s+-A\s+-s\s+(\S+)""")
            .find(cmd)?.groupValues?.getOrNull(1)?.let { return SessionManager.sanitizeSessionName(it) }
        // zellij attach NAME --create
        Regex("""zellij\s+attach\s+(\S+)""")
            .find(cmd)?.groupValues?.getOrNull(1)?.let { return SessionManager.sanitizeSessionName(it) }
        // screen -dRR NAME
        Regex("""screen\s+-dRR\s+(\S+)""")
            .find(cmd)?.groupValues?.getOrNull(1)?.let { return SessionManager.sanitizeSessionName(it) }
        return null
    }

    fun parseSessionManager(name: String?): SessionManager? {
        if (name.isNullOrBlank()) return null
        return try {
            SessionManager.valueOf(name.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
