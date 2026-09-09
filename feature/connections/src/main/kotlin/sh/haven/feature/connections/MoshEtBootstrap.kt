package sh.haven.feature.connections

import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.SessionManager

/**
 * Pure pieces of the Mosh / Eternal Terminal phase-1 SSH bootstrap, shared
 * by the interactive and silent (group-launch) connect paths in
 * [ConnectionsViewModel]. The connect/host-trust step itself stays on the
 * view model ([ConnectionsViewModel] `bootstrapMoshEtSsh`) because it
 * needs the session managers, TOFU flow, and prompt hooks.
 */

/**
 * The two enums share names by construction (#137) — defined separately
 * because `core/data` can't depend on `core/ssh`. Convert via name lookup.
 */
internal val ConnectionProfile.addressFamilyForSsh: ConnectionConfig.AddressFamily
    get() = ConnectionConfig.AddressFamily.valueOf(addressFamilyEnum.name)

/**
 * Per-profile reconnect knobs to a value object the SSH session
 * manager understands. Three columns from the data model collapse
 * into one [ConnectionConfig.ReconnectPolicy] — keeps the connect-
 * config builders one line longer instead of three (#150).
 */
internal val ConnectionProfile.reconnectPolicy: ConnectionConfig.ReconnectPolicy
    get() = ConnectionConfig.ReconnectPolicy(
        autoReconnect = autoReconnect,
        maxAttempts = reconnectMaxAttempts,
        onNetworkChange = reconnectOnNetworkChange,
    )

/**
 * SSH [ConnectionConfig] for a Mosh/ET bootstrap connection. Interactive
 * connects pass the override-aware effective username and the profile's
 * reconnect policy; silent connects keep their historical defaults — the
 * profile username and a default [ConnectionConfig.ReconnectPolicy].
 */
internal fun moshEtBootstrapConfig(
    profile: ConnectionProfile,
    authMethod: ConnectionConfig.AuthMethod,
    agentIdentities: List<ConnectionConfig.AgentIdentity>,
    username: String = profile.username,
    reconnectPolicy: ConnectionConfig.ReconnectPolicy = ConnectionConfig.ReconnectPolicy(),
): ConnectionConfig = ConnectionConfig(
    host = profile.host,
    port = profile.port,
    username = username,
    authMethod = authMethod,
    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
    forwardAgent = profile.forwardAgent,
    remoteCommand = profile.remoteCommand,
    requestPty = profile.requestPty,
    addressFamily = profile.addressFamilyForSsh,
    bindAddress = profile.bindAddress,
    agentIdentities = agentIdentities,
    reconnectPolicy = reconnectPolicy,
)

/**
 * The multiplexer session to re-attach to without stopping at the picker:
 * the one this profile was last attached to, when it is still running on
 * the remote.
 *
 * This is what actually survives an app restart. The Mosh/ET transport
 * itself cannot be resumed — a mosh-server ignores a second client process
 * even with the right key, port and IP (verified against stock mosh; #371)
 * — but the tmux/zellij/screen session behind it lives on, and attaching
 * back to it restores the shell exactly where the user left it. The silent
 * group-launch path has always attached by [ConnectionProfile.lastSessionName];
 * this lets the interactive connect do the same instead of asking.
 *
 * Null — keep the picker — when nothing is remembered, when the remembered
 * session is gone (killed, or the host rebooted), or when several are
 * remembered, since then the user genuinely has a choice to make.
 */
internal fun autoAttachSessionName(profile: ConnectionProfile, existing: List<String>): String? {
    val remembered = profile.lastSessionName
        ?.split("|")
        ?.filter { it.isNotBlank() }
        ?.singleOrNull()
        ?: return null
    return remembered.takeIf { it in existing }
}

/**
 * Best-effort listing of existing multiplexer sessions on the remote: a
 * manager without a list command, a non-zero exit, or an exec failure all
 * yield an empty list, so the connect proceeds to a fresh session instead
 * of failing the bootstrap.
 */
internal suspend fun listExistingMultiplexerSessions(
    smgr: SessionManager,
    exec: suspend (String) -> ExecResult,
): List<String> {
    val listCmd = smgr.listCommand ?: return emptyList()
    return try {
        val result = exec(listCmd)
        if (result.exitStatus == 0) SessionManager.parseSessionList(smgr, result.stdout) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}
