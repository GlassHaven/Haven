package sh.haven.core.reticulum

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Coroutines-first interface for Reticulum/rnsh transport.
 *
 * Backed by [NativeReticulumTransport] (rnsh-kt + reticulum-kt).
 * Collapses the session lifecycle into a single [openSession] call
 * that resolves the destination, establishes the Link, performs the
 * version handshake, and returns a [RnshShellSession].
 */
interface ReticulumTransport {

    /**
     * Initialise Reticulum. Call before any other method.
     *
     * @param configDir Writable directory for RNS config/identity storage
     * @param host Shared-instance or gateway host
     * @param port Shared-instance or gateway TCP port
     * @param ifacNetname IFAC network name for gateway isolation (optional)
     * @param ifacNetkey IFAC passphrase for gateway isolation (optional)
     * @param socketDialer Optional `(host, port, timeoutMs) -> Socket`
     *   used by the gateway TCP interface in place of a direct kernel
     *   dial. Routes Reticulum's TCP transport through a userspace
     *   tunnel (WireGuard / Tailscale) or SOCKS/HTTP proxy when the
     *   connection profile selects one (#149). Ignored in shared-
     *   instance mode where the local Sideband daemon owns the socket.
     * @return Haven's RNS identity hash (hex)
     */
    suspend fun init(
        configDir: String,
        host: String = "127.0.0.1",
        port: Int = 37428,
        ifacNetname: String? = null,
        ifacNetkey: String? = null,
        socketDialer: ((String, Int, Int) -> java.net.Socket)? = null,
    ): String

    /** Whether Reticulum has been initialised. */
    val isInitialised: Boolean

    /**
     * Open a shell session to an rnsh destination.
     * Resolves the destination, establishes a Link, performs the version
     * handshake, and requests command execution.
     *
     * @param destinationHash Hex-encoded rnsh destination hash
     * @param rows Terminal rows
     * @param cols Terminal columns
     * @return A live shell session
     * @throws Exception if resolution, Link, or handshake fails
     */
    suspend fun openSession(
        destinationHash: String,
        rows: Int = 24,
        cols: Int = 80,
    ): RnshShellSession

    /**
     * Run a single command on an rnsh destination in pipe mode (no PTY),
     * over its own Link, with stdout and stderr demultiplexed and an exit
     * code. The substrate for file transfer and port forwarding over the
     * mesh — see [ReticulumExecSession].
     *
     * Reticulum must already be initialised (typically by an open shell
     * [openSession] to the same destination); the path is reused.
     *
     * @param destinationHash Hex-encoded rnsh destination hash
     * @param command argv to run; use `listOf("sh", "-c", script)` for shell
     *   features (redirection, pipes, globbing)
     * @throws Exception if resolution, Link, or handshake fails
     */
    suspend fun execCommand(
        destinationHash: String,
        command: List<String>,
    ): ReticulumExecSession

    /** Discovered rnsh destinations, sorted by hop count. */
    val discoveredDestinations: StateFlow<List<DiscoveredDestination>>

    /**
     * Request a path to a destination. Non-blocking — returns true if
     * path is already known, false if a request was sent.
     */
    suspend fun requestPath(destinationHashHex: String): Boolean

    /** Probe for Sideband's shared instance. Safe to call repeatedly. */
    suspend fun probeSideband(configDir: String): Boolean

    /**
     * The rnsh client identity hash this device presents, or null if none has
     * been created yet.
     *
     * Reads the stored key; it does not start the stack, so it is safe to call
     * from a settings or connection screen before anything is connected.
     */
    suspend fun clientIdentityHash(configDir: String): String?

    /**
     * Install a Reticulum identity the user supplies as this device's client
     * identity (#585).
     *
     * The identity a server whitelists is a private key, so it can only be
     * moved as a file — there is no way to adopt one from its hash, which is a
     * fingerprint of the key rather than the key itself.
     *
     * Nothing is replaced unless [source] parses, and the key being replaced is
     * kept so a mistaken import can be undone.
     */
    suspend fun importClientIdentity(configDir: String, source: java.io.File): ReticulumIdentityImport

    /** Close all sessions and shut down. */
    suspend fun closeAll()
}

/** Outcome of [ReticulumTransport.importClientIdentity] (#585). */
sealed interface ReticulumIdentityImport {
    /**
     * The file parsed and is now the stored client identity.
     *
     * [replacedHexHash] is the hash that was in use before, or null if there was
     * none. [takesEffectAfterRestart] is true when Reticulum is already running:
     * the stack reads the identity once at start-up, so live sessions and any
     * link opened before a restart still present the old one.
     */
    data class Installed(
        val hexHash: String,
        val replacedHexHash: String?,
        val takesEffectAfterRestart: Boolean,
    ) : ReticulumIdentityImport

    /** The source is missing, unreadable, or not a Reticulum identity. */
    data class NotAnIdentity(val reason: String) : ReticulumIdentityImport

    /** The source parsed but could not be installed. The old identity is intact. */
    data class InstallFailed(val reason: String) : ReticulumIdentityImport
}

/**
 * A live rnsh shell session.
 */
interface RnshShellSession : AutoCloseable {
    val sessionId: String

    /** Stdout and stderr data as received from the remote shell. */
    val output: Flow<ByteArray>

    /** Completes when the remote command exits. Value is the exit code. */
    val exitCode: CompletableDeferred<Int>

    /** Whether the session is still connected. */
    val isConnected: Boolean

    /** Send keyboard input (stdin) to the remote shell. */
    suspend fun sendInput(data: ByteArray)

    /** Send a window resize to the remote PTY. */
    suspend fun resize(rows: Int, cols: Int)

    /** Close the session. */
    override fun close()
}

/**
 * A live rnsh command-exec session: a single remote command run in pipe
 * mode (no PTY) over its own Link, with stdout and stderr demultiplexed and
 * an exit code.
 *
 * stdout/stderr are reliable, single-consumer flows that complete when the
 * command exits; bytes are not dropped (unlike the terminal's lossy output).
 */
interface ReticulumExecSession : AutoCloseable {
    /** Stdout bytes from the remote process. Single consumer; completes on exit. */
    val stdout: Flow<ByteArray>

    /** Stderr bytes from the remote process. Single consumer; completes on exit. */
    val stderr: Flow<ByteArray>

    /**
     * Completes with the exit code the rnsh listener reports.
     *
     * Caveat: some rnsh listeners (incl. current markqvist/rnsh) report 0
     * for every non-signal exit (a `& 0xff` waitpid-masking bug), so this
     * value cannot distinguish success from failure. Callers needing a
     * reliable status should embed `$?` in the command output.
     */
    val exitCode: CompletableDeferred<Int>

    /** Write bytes to the remote process's stdin. */
    suspend fun writeStdin(data: ByteArray)

    /** Signal EOF on the remote process's stdin. */
    suspend fun closeStdin()

    /** Tear down the session and close the underlying Link. */
    override fun close()
}

/**
 * An rnsh destination discovered via announce.
 */
data class DiscoveredDestination(
    val hash: String,
    val hops: Int,
    val lastSeen: Long = System.currentTimeMillis(),
)
