package sh.haven.core.ssh

/**
 * Which SSH protocol implementation a connection uses (#58).
 *
 * Selected per-connection via the `HavenSshEngine` directive in the
 * profile's SSH Options (`HavenSshEngine sshlib`) — a Haven-internal
 * directive that [SshOptionsApplier] filters out before JSch config is
 * applied. Absent/unknown values fall back to [JSCH], so a hand-mangled
 * directive can never change engines silently.
 */
enum class SshEngine {
    /** mwiede JSch fork — the default engine for every profile that has not opted in. */
    JSCH,

    /**
     * org.connectbot.sshlib (ssh-proto) — **experimental, opt-in**.
     *
     * Carries a whole connection: terminal (shell and RemoteCommand exec), one-shot
     * exec, SFTP, and local/remote/dynamic port forwarding over one sshlib transport.
     * It deliberately REFUSES what it cannot do — jump-host or proxied dials, FIDO2
     * hardware keys, OpenSSH certificates, and multi-factor chains — so an opted-in
     * profile fails loudly rather than silently behaving differently; move such
     * profiles back to [JSCH].
     */
    SSHLIB,
}

private const val ENGINE_DIRECTIVE = "havensshengine"

/** Engine choice from a parsed SSH Options map; [SshEngine.JSCH] unless valid. */
fun sshEngineFrom(sshOptions: Map<String, String>): SshEngine {
    val value = sshOptions.entries
        .firstOrNull { it.key.trim().lowercase() == ENGINE_DIRECTIVE }
        ?.value?.trim() ?: return SshEngine.JSCH
    return if (value.equals("sshlib", ignoreCase = true)) SshEngine.SSHLIB else SshEngine.JSCH
}

/**
 * Engine choice from raw SSH Options text (the `ConnectionProfile.sshOptions`
 * column), for callers that pick the engine before a [ConnectionConfig] exists.
 */
fun sshEngineFromOptionsText(sshOptions: String?): SshEngine =
    sshEngineFrom(ConnectionConfig.parseSshOptions(sshOptions))

/** Engine choice carried by this config's SSH Options; [SshEngine.JSCH] unless valid. */
val ConnectionConfig.sshEngine: SshEngine
    get() = sshEngineFrom(sshOptions)

// ── One-tap X11 & PulseAudio forwarding over SSH (#605) ───────────────────

private val FORWARD_X11_DIRECTIVES = setOf("havenforwardx11", "forwardx11")
private val FORWARD_PULSE_DIRECTIVES = setOf("havenforwardpulseaudio", "forwardpulseaudio")
private val TRUTHY_OPTION_VALUES = setOf("yes", "true", "on", "1")

/** Remote loopback TCP port bound for X11 display `:10.0` (`6000 + 10`). */
const val SSH_X11_REMOTE_PORT = 6010

/** Default phone-side X11 loopback TCP port (`:0` -> `6000`). */
const val SSH_X11_DEFAULT_LOCAL_PORT = 6000

/** Remote shell export snippet for SSH-forwarded X11 display `:10.0`. */
const val SSH_X11_DISPLAY_EXPORT = "export DISPLAY=localhost:10.0"

/** Remote loopback TCP port bound for forwarded PulseAudio native protocol. */
const val SSH_PULSE_REMOTE_PORT = 14713

/** Phone-side loopback TCP port exposed by `AudioBridge` (`module-native-protocol-tcp`). */
const val SSH_PULSE_LOCAL_PORT = 4713

/** Remote shell export snippet for SSH-forwarded PulseAudio server. */
const val SSH_PULSE_SERVER_EXPORT = "export PULSE_SERVER=tcp:127.0.0.1:$SSH_PULSE_REMOTE_PORT"

/** Whether one-tap X11 forwarding (#605) is enabled in a parsed SSH Options map. */
fun sshForwardX11From(sshOptions: Map<String, String>): Boolean {
    val value = sshOptions.entries
        .lastOrNull { it.key.trim().lowercase() in FORWARD_X11_DIRECTIVES }
        ?.value?.trim()?.lowercase() ?: return false
    return value in TRUTHY_OPTION_VALUES
}

/** Whether one-tap X11 forwarding (#605) is enabled in raw `ConnectionProfile.sshOptions` text. */
fun sshForwardX11FromOptionsText(sshOptions: String?): Boolean =
    sshForwardX11From(ConnectionConfig.parseSshOptions(sshOptions))

/** Whether one-tap X11 forwarding (#605) is enabled on this [ConnectionConfig]. */
val ConnectionConfig.forwardX11: Boolean
    get() = sshForwardX11From(sshOptions)

/** Rewrite [options] text with `HavenForwardX11 yes` enabled or removed (#605). */
fun setForwardX11InOptions(options: String, enabled: Boolean): String =
    setBooleanDirectiveInOptions(
        options = options,
        canonicalKey = "HavenForwardX11",
        matchKeys = FORWARD_X11_DIRECTIVES,
        enabled = enabled,
    )

/** Whether one-tap PulseAudio forwarding (#605) is enabled in a parsed SSH Options map. */
fun sshForwardPulseAudioFrom(sshOptions: Map<String, String>): Boolean {
    val value = sshOptions.entries
        .lastOrNull { it.key.trim().lowercase() in FORWARD_PULSE_DIRECTIVES }
        ?.value?.trim()?.lowercase() ?: return false
    return value in TRUTHY_OPTION_VALUES
}

/** Whether one-tap PulseAudio forwarding (#605) is enabled in raw `ConnectionProfile.sshOptions` text. */
fun sshForwardPulseAudioFromOptionsText(sshOptions: String?): Boolean =
    sshForwardPulseAudioFrom(ConnectionConfig.parseSshOptions(sshOptions))

/** Whether one-tap PulseAudio forwarding (#605) is enabled on this [ConnectionConfig]. */
val ConnectionConfig.forwardPulseAudio: Boolean
    get() = sshForwardPulseAudioFrom(sshOptions)

/** Rewrite [options] text with `HavenForwardPulseAudio yes` enabled or removed (#605). */
fun setForwardPulseAudioInOptions(options: String, enabled: Boolean): String =
    setBooleanDirectiveInOptions(
        options = options,
        canonicalKey = "HavenForwardPulseAudio",
        matchKeys = FORWARD_PULSE_DIRECTIVES,
        enabled = enabled,
    )

// ── Per-profile SSH keepalive (`ServerAliveInterval`) helpers (#895) ─────────

/** Haven's default SSH `ServerAliveInterval` in seconds (`SshClient` sets 15,000 ms). */
const val SSH_DEFAULT_KEEPALIVE_SECONDS = 15

private const val SERVER_ALIVE_INTERVAL_KEY = "serveraliveinterval"

/**
 * Explicit `ServerAliveInterval` override (in seconds) parsed from raw
 * `ConnectionProfile.sshOptions` text (#895):
 * - `null`: not specified in `sshOptions` (session inherits [SSH_DEFAULT_KEEPALIVE_SECONDS]).
 * - `0`: SSH keepalive explicitly disabled (`ServerAliveInterval 0`).
 * - `> 0`: explicit keepalive interval in seconds.
 */
fun sshKeepaliveSecondsFromOptionsText(sshOptions: String?): Int? {
    val parsed = ConnectionConfig.parseSshOptions(sshOptions)
    val raw = parsed.entries
        .lastOrNull { it.key.trim().lowercase() == SERVER_ALIVE_INTERVAL_KEY }
        ?.value?.trim() ?: return null
    val sec = raw.toIntOrNull() ?: return null
    return if (sec >= 0) sec.coerceAtMost(86_400) else null
}

/**
 * Rewrite [options] text with `ServerAliveInterval <seconds>` set or cleared (#895).
 * Passing `null` removes `ServerAliveInterval` lines so the profile inherits
 * [SSH_DEFAULT_KEEPALIVE_SECONDS]; passing `0` disables keepalive; passing `> 0`
 * sets the interval in seconds while preserving comments and other directives.
 */
fun setSshKeepaliveInOptions(options: String, seconds: Int?): String {
    val kept = options.lines().filterNot { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return@filterNot false
        val sep = trimmed.indexOfFirst { it == ' ' || it == '=' || it == '\t' }
        sep > 0 && trimmed.substring(0, sep).trim().lowercase() == SERVER_ALIVE_INTERVAL_KEY
    }
    val base = kept.joinToString("\n").trim('\n')
    val clamped = seconds?.takeIf { it >= 0 }?.coerceAtMost(86_400)
    return when {
        clamped == null -> base
        base.isBlank() -> "ServerAliveInterval $clamped"
        else -> "$base\nServerAliveInterval $clamped"
    }
}

private fun setBooleanDirectiveInOptions(
    options: String,
    canonicalKey: String,
    matchKeys: Set<String>,
    enabled: Boolean,
): String {
    val kept = options.lines().filterNot { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return@filterNot false
        val sep = trimmed.indexOfFirst { it == ' ' || it == '=' || it == '\t' }
        sep > 0 && trimmed.substring(0, sep).trim().lowercase() in matchKeys
    }
    val base = kept.joinToString("\n").trim('\n')
    return when {
        !enabled -> base
        base.isBlank() -> "$canonicalKey yes"
        else -> "$base\n$canonicalKey yes"
    }
}

/**
 * Combined remote shell export command for enabled X11 / PulseAudio forwarding (#605),
 * or `null` when neither toggle is enabled.
 */
fun buildForwardingEnvExportCommand(
    forwardX11: Boolean,
    forwardPulseAudio: Boolean,
): String? {
    val exports = buildList {
        if (forwardX11) add(SSH_X11_DISPLAY_EXPORT)
        if (forwardPulseAudio) add(SSH_PULSE_SERVER_EXPORT)
    }
    return if (exports.isEmpty()) null else exports.joinToString(" ; ")
}

/**
 * Build the implicit reverse (`-R`) port-forward rules for one-tap X11 and/or
 * PulseAudio forwarding (#605). Both forwards are non-critical and self-heal
 * stale `sshd` listeners on reconnect.
 */
fun buildMediaPortForwards(
    forwardX11: Boolean,
    localX11Port: Int = SSH_X11_DEFAULT_LOCAL_PORT,
    forwardPulseAudio: Boolean,
    localPulsePort: Int = SSH_PULSE_LOCAL_PORT,
): List<SshSessionManager.PortForwardInfo> = buildList {
    if (forwardX11) {
        add(
            SshSessionManager.PortForwardInfo(
                ruleId = "haven-x11-forward",
                type = SshSessionManager.PortForwardType.REMOTE,
                bindAddress = "127.0.0.1",
                bindPort = SSH_X11_REMOTE_PORT,
                targetHost = "127.0.0.1",
                targetPort = localX11Port,
                critical = false,
                selfHealOnBindFailure = true,
            ),
        )
    }
    if (forwardPulseAudio) {
        add(
            SshSessionManager.PortForwardInfo(
                ruleId = "haven-pulse-forward",
                type = SshSessionManager.PortForwardType.REMOTE,
                bindAddress = "127.0.0.1",
                bindPort = SSH_PULSE_REMOTE_PORT,
                targetHost = "127.0.0.1",
                targetPort = localPulsePort,
                critical = false,
                selfHealOnBindFailure = true,
            ),
        )
    }
}

