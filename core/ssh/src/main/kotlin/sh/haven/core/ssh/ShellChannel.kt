package sh.haven.core.ssh

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

/**
 * Thrown when an authenticated, still-connected SSH server actively rejects
 * opening another session channel (`SSH_MSG_CHANNEL_OPEN_FAILURE`, RFC 4254
 * §5.1) — most commonly OpenSSH's default `MaxSessions 10` ceiling when many
 * terminal tabs share one connection (#888). Distinct from a broken socket so
 * callers can dial an overflow connection instead of failing the new tab.
 */
class SshChannelOpenRejectedException(
    val reasonCode: Int,
    cause: Throwable? = null,
) : RuntimeException(
    "SSH server rejected opening another session channel (reason $reasonCode; server MaxSessions limit may be full)",
    cause,
)

/**
 * An engine-neutral interactive shell channel (#58, phase 5).
 *
 * Callers see the shell as a pair of streams plus resize/teardown/liveness
 * — never the underlying JSch `ChannelShell` or sshlib `SshSession`. Each
 * engine builds one by supplying the streams and the behaviour closures:
 * [openShellOn] for JSch, the sshlib shell path for ssh-proto.
 *
 * The streams must be bound BEFORE the channel is connected: on JSch
 * `getInputStream()` is what installs the server→client pipe, and a write
 * before that pipe exists is silently dropped — which is how the target's
 * SSH banner was lost through a jump host (#381). A shell channel can lose
 * its first output (banner, MOTD, prompt) the same way. Fetch the streams
 * once at construction and pass this object around; never re-fetch.
 */
class ShellChannel(
    val input: InputStream,
    val output: OutputStream,
    private val resizeFn: (cols: Int, rows: Int) -> Unit,
    private val disconnectFn: () -> Unit,
    private val connectedProbe: () -> Boolean,
    private val closedProbe: () -> Boolean,
    private val exitStatusProbe: () -> Int,
) {
    val isConnected: Boolean get() = connectedProbe()
    val isClosed: Boolean get() = closedProbe()

    /** The remote process exit status, or -1 if not yet known / unreported. */
    val exitStatus: Int get() = exitStatusProbe()

    /** Resize the remote PTY (SIGWINCH). */
    fun resize(cols: Int, rows: Int) = resizeFn(cols, rows)

    fun disconnect() = disconnectFn()
}

/**
 * Open an interactive JSch shell channel on [session], binding its streams
 * before connecting it. See [ShellChannel] for why the order matters.
 */
internal fun openShellOn(
    session: Session,
    term: String,
    cols: Int,
    rows: Int,
    agentForwarding: Boolean,
): ShellChannel {
    val channel = session.openChannel("shell") as com.jcraft.jsch.ChannelShell
    channel.setPtyType(term, cols, rows, 0, 0)
    if (agentForwarding) channel.setAgentForwarding(true)
    val input = channel.inputStream
    val output = channel.outputStream
    connectJschSessionChannel(session, channel)
    return ShellChannel(
        input = input,
        output = output,
        resizeFn = { c, r -> channel.setPtySize(c, r, 0, 0) },
        disconnectFn = { channel.disconnect() },
        connectedProbe = { channel.isConnected },
        closedProbe = { channel.isClosed },
        exitStatusProbe = { channel.exitStatus },
    )
}

/**
 * Open a terminal-facing JSch exec channel on [session] for [command] (the
 * SSH RemoteCommand path). The server receives an `exec` request instead of a
 * login shell, so the command runs before shell startup files (`.bashrc`,
 * auto-tmux hooks) — the whole point of the feature. A PTY is optional for
 * non-interactive commands and required by tmux; when omitted, resize is a
 * no-op. Binds streams before connecting for the same reason as [openShellOn].
 */
internal fun openRemoteCommandOn(
    session: Session,
    command: String,
    requestPty: Boolean,
    term: String,
    cols: Int,
    rows: Int,
    agentForwarding: Boolean,
): ShellChannel {
    val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
    channel.setCommand(command)
    if (requestPty) {
        channel.setPty(true)
        channel.setPtyType(term, cols, rows, 0, 0)
    }
    if (agentForwarding) channel.setAgentForwarding(true)
    val input = channel.inputStream
    val output = channel.outputStream
    connectJschSessionChannel(session, channel)
    return ShellChannel(
        input = input,
        output = output,
        resizeFn = { c, r -> if (requestPty) channel.setPtySize(c, r, 0, 0) },
        disconnectFn = { channel.disconnect() },
        connectedProbe = { channel.isConnected },
        closedProbe = { channel.isClosed },
        exitStatusProbe = { channel.exitStatus },
    )
}

private fun connectJschSessionChannel(session: Session, channel: com.jcraft.jsch.Channel) {
    try {
        channel.connect()
    } catch (e: JSchException) {
        val rejectedByLiveServer = session.isConnected && channel.isClosed && channel.exitStatus > 0
        runCatching { channel.disconnect() }
        if (rejectedByLiveServer) {
            throw SshChannelOpenRejectedException(channel.exitStatus, e)
        }
        throw e
    }
}
