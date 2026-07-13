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
    addressFamily = profile.addressFamilyForSsh,
    agentIdentities = agentIdentities,
    reconnectPolicy = reconnectPolicy,
)

/** A complete saved mosh re-attach tuple from the last bootstrap (#371). */
internal data class SavedMoshSession(val serverIp: String, val port: Int, val key: String)

/**
 * The saved mosh re-attach tuple for [profile], or null when the re-attach
 * fast path must not run: the user disabled it, the tuple is incomplete,
 * or [hasLiveSession] — a second transport on the same key would fight the
 * live tab for the server (mosh treats the newest source address as the
 * roamed client, so both would stall).
 */
internal fun savedMoshSession(
    profile: ConnectionProfile,
    hasLiveSession: Boolean,
): SavedMoshSession? {
    if (!profile.moshReconnectToExisting || hasLiveSession) return null
    val key = profile.savedMoshKey?.takeIf { it.isNotEmpty() } ?: return null
    val port = profile.savedMoshPort?.takeIf { it > 0 } ?: return null
    val ip = profile.savedMoshServerIp?.takeIf { it.isNotEmpty() } ?: return null
    return SavedMoshSession(ip, port, key)
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
