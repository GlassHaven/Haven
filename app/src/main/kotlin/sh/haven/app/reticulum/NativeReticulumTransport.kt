package sh.haven.app.reticulum

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.interfaces.Interface as RnsInterface
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.RichAnnounceHandler
import network.reticulum.transport.Transport
import sh.haven.core.reticulum.DiscoveredDestination
import sh.haven.core.reticulum.ReticulumExecSession
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.core.reticulum.RnshShellSession
import tech.torlando.rnsh.session.ExecSession
import tech.torlando.rnsh.session.InitiatorSession
import tech.torlando.rnsh.session.ShellSession
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact

private const val TAG = "NativeReticulumTransport"

/** Filename of the persisted rnsh client identity inside the Reticulum config dir (#585). */
private const val CLIENT_IDENTITY_FILE = "haven_client_identity"

/**
 * Native Kotlin implementation of [ReticulumTransport] backed by
 * reticulum-kt + rnsh-kt.
 *
 * Supports two init modes:
 * - Sideband shared-instance client (localhost:37428)
 * - Direct TCP gateway to a remote host
 */
@Singleton
class NativeReticulumTransport @Inject constructor() : ReticulumTransport {

    @Volatile
    private var initialised = false

    override val isInitialised: Boolean get() = initialised

    private val _discovered = MutableStateFlow<List<DiscoveredDestination>>(emptyList())
    override val discoveredDestinations: StateFlow<List<DiscoveredDestination>> =
        _discovered.asStateFlow()

    private var clientIdentity: Identity? = null

    /** Announce handler that collects rnsh destinations into the StateFlow. */
    private val rnshAnnounceHandler = object : RichAnnounceHandler {
        override fun handleAnnounceWithContext(
            destinationHash: ByteArray,
            announcedIdentity: Identity,
            appData: ByteArray?,
            hops: Int,
            receivingInterfaceName: String?,
            matchedAspect: String?,
        ): Boolean {
            val hash = destinationHash.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "rnsh announce: $hash (${hops} hops, via $receivingInterfaceName)")

            val dest = DiscoveredDestination(
                hash = hash,
                hops = hops,
            )

            _discovered.value = (_discovered.value
                .filter { it.hash != hash } + dest)
                .sortedBy { it.hops }

            return true
        }
    }

    override suspend fun init(
        configDir: String,
        host: String,
        port: Int,
        ifacNetname: String?,
        ifacNetkey: String?,
        socketDialer: ((String, Int, Int) -> java.net.Socket)?,
    ): String = withContext(Dispatchers.IO) {
        if (initialised) {
            return@withContext clientIdentity?.hexHash ?: "already-initialised"
        }

        Log.d(TAG, "init: configDir=$configDir, host=${LogRedact.of(host)}, port=$port")
        File(configDir).mkdirs()

        val isSideband = host in listOf("127.0.0.1", "localhost", "::1") && port == 37428

        if (isSideband) {
            // Sideband shared-instance client mode
            Reticulum.setLocalClientFactory { p, h ->
                LocalClientInterface("Sideband", tcpPort = p, tcpHost = h)
            }
            Reticulum.setInterfaceRegistrar(sharedInstanceInterfaceRegistrar)
            Reticulum.start(
                configDir = configDir,
                connectToSharedInstance = true,
                sharedInstancePort = port,
            )
            // The library falls back to a standalone stack with no interfaces
            // when nothing answers on the port, and reports nothing. Left
            // unchecked that latched `initialised` on a transport that could
            // not reach anything, so every later connect returned early and
            // the interface count stayed at zero for the life of the process
            // (#588).
            check(Reticulum.getInstance().isConnectedToSharedInstance) {
                "No Reticulum shared instance answered on port $port. " +
                    "Start Sideband or another shared instance, or use a gateway host instead."
            }
        } else {
            // Direct TCP gateway mode
            Reticulum.start(configDir = configDir)

            val tcpClient = TCPClientInterface(
                name = "Gateway $host:$port",
                targetHost = host,
                targetPort = port,
                ifacNetname = ifacNetname,
                ifacNetkey = ifacNetkey,
                socketDialer = socketDialer,
            )
            Reticulum.getInstance().addInterface(tcpClient)
            Transport.registerInterface(tcpClient.toRef())
            tcpClient.start()

            // Wait for TCP connection
            val deadline = System.currentTimeMillis() + 10_000
            while (!tcpClient.online.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            if (!tcpClient.online.get()) {
                Log.w(TAG, "TCP connection to ${LogRedact.host(host, port)} not established within 10s")
            }
        }

        clientIdentity = loadOrCreateClientIdentity(configDir)

        // Register rnsh announce handler for destination discovery
        Transport.registerAnnounceHandler(
            handler = rnshAnnounceHandler,
            aspectFilter = "rnsh",
        )
        Log.d(TAG, "Registered rnsh announce handler")

        initialised = true
        val hexHash = clientIdentity?.hexHash ?: ""
        Log.d(TAG, "init complete, identity=$hexHash")
        hexHash
    }

    /**
     * The identity an rnsh server sees, loaded from disk if we have one.
     *
     * This used to be a bare `Identity.create()` on every init, so the hash
     * changed on every process start and a server-side whitelist entry stopped
     * matching as soon as Haven was force-stopped (#585). The transport
     * identity was already persisted; this one — the one that actually
     * identifies the client — was not.
     */
    private fun loadOrCreateClientIdentity(configDir: String): Identity {
        val resolved = resolveClientIdentity(File(configDir))
        when (resolved.origin) {
            ClientIdentityOrigin.LOADED ->
                Log.d(TAG, "loaded client identity ${resolved.identity.hexHash}")
            ClientIdentityOrigin.CREATED ->
                Log.d(TAG, "created client identity ${resolved.identity.hexHash}")
            ClientIdentityOrigin.REPLACED_UNREADABLE ->
                // Say so rather than silently minting a new one — from the
                // user's side that is the whitelist breaking for no reason.
                Log.w(TAG, "client identity file was unreadable; created ${resolved.identity.hexHash}")
            ClientIdentityOrigin.CREATED_UNSAVED ->
                Log.e(TAG, "could not persist client identity — it will change again on the next launch")
        }
        return resolved.identity
    }

    override suspend fun openSession(
        destinationHash: String,
        rows: Int,
        cols: Int,
    ): RnshShellSession = withContext(Dispatchers.IO) {
        check(initialised) { "Reticulum not initialised" }

        val destHash = awaitPath(destinationHash)

        // Create and execute rnsh session
        val session = InitiatorSession(
            destinationHash = destHash,
            clientIdentity = clientIdentity,
        )

        val shell = session.execute(rows = rows, cols = cols)
        Log.d(TAG, "Session opened to $destinationHash")

        NativeShellSession(
            sessionId = UUID.randomUUID().toString(),
            shell = shell,
        )
    }

    override suspend fun execCommand(
        destinationHash: String,
        command: List<String>,
    ): ReticulumExecSession = withContext(Dispatchers.IO) {
        check(initialised) { "Reticulum not initialised" }

        val destHash = awaitPath(destinationHash)

        // Each exec runs over its own Link to the same destination.
        val session = InitiatorSession(
            destinationHash = destHash,
            clientIdentity = clientIdentity,
        )
        val exec = session.executeCommand(command = command)
        Log.d(TAG, "Exec opened to $destinationHash: ${command.firstOrNull()}")
        NativeExecSession(exec)
    }

    /**
     * Resolve a path to [destinationHash], blocking up to 20s. Returns the
     * destination hash bytes. Reuses any already-known path (e.g. from an
     * open shell session to the same destination).
     */
    private fun awaitPath(destinationHash: String): ByteArray {
        val destHash = hexToBytes(destinationHash)
        if (Transport.hasPath(destHash)) return destHash

        Log.d(TAG, "Requesting path to $destinationHash...")
        Transport.requestPath(destHash)
        val deadline = System.currentTimeMillis() + 20_000
        while (!Transport.hasPath(destHash)) {
            if (System.currentTimeMillis() > deadline) {
                throw RuntimeException(
                    "Could not resolve destination $destinationHash within 20s"
                )
            }
            Thread.sleep(250)
        }
        Log.d(TAG, "Path found for $destinationHash")
        return destHash
    }

    override suspend fun requestPath(destinationHashHex: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!initialised) return@withContext false
            val destHash = hexToBytes(destinationHashHex)
            if (Transport.hasPath(destHash)) {
                true
            } else {
                Transport.requestPath(destHash)
                false
            }
        }

    override suspend fun probeSideband(configDir: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Reticulum.isSharedInstanceRunning()
            } catch (e: Exception) {
                Log.e(TAG, "probeSideband failed", e)
                false
            }
        }

    override suspend fun closeAll() = withContext(Dispatchers.IO) {
        try {
            Reticulum.stop()
        } catch (e: Exception) {
            Log.e(TAG, "closeAll failed", e)
        }
        initialised = false
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Shell session backed by rnsh-kt's native [ShellSession].
 */
private class NativeShellSession(
    override val sessionId: String,
    private val shell: ShellSession,
) : RnshShellSession {

    override val output: Flow<ByteArray> = shell.output

    override val exitCode: CompletableDeferred<Int> = shell.exitCode

    override val isConnected: Boolean
        get() = !exitCode.isCompleted

    override suspend fun sendInput(data: ByteArray) {
        shell.sendInput(data)
    }

    override suspend fun resize(rows: Int, cols: Int) {
        shell.resize(rows, cols)
    }

    override fun close() {
        shell.close()
    }
}

/**
 * Exec session backed by rnsh-kt's native [ExecSession].
 */
private class NativeExecSession(
    private val exec: ExecSession,
) : ReticulumExecSession {

    override val stdout: Flow<ByteArray> = exec.stdout
    override val stderr: Flow<ByteArray> = exec.stderr
    override val exitCode: CompletableDeferred<Int> = exec.exitCode

    override suspend fun writeStdin(data: ByteArray) = exec.writeStdin(data)
    override suspend fun closeStdin() = exec.closeStdin()
    override fun close() = exec.close()
}

/**
 * Adds an interface that reticulum-kt built for us to [Transport].
 *
 * The gateway path constructs its own [TCPClientInterface] and registers it
 * directly, but the shared-instance path builds its client interface inside
 * the library and hands it back through this callback. Haven never set one,
 * so on that path the interface was created and started and then never
 * registered: Transport had nothing to send or receive on, the connection
 * still reported as up, and the interface count stayed at zero (#588). The
 * library says so in its own log and carries on regardless, which is why it
 * looked like nothing was wrong.
 */
internal val sharedInstanceInterfaceRegistrar: (Any) -> Unit = { iface ->
    if (iface is RnsInterface) {
        Transport.registerInterface(iface.toRef())
    } else {
        Log.e(TAG, "cannot register ${iface.javaClass.name}: not a Reticulum interface")
    }
}

/** How [resolveClientIdentity] arrived at the identity it returned. */
internal enum class ClientIdentityOrigin {
    /** Read back from disk — the hash is unchanged from last launch. */
    LOADED,

    /** No file existed; a new identity was created and written. */
    CREATED,

    /** A file existed but could not be parsed; a new identity replaced it. */
    REPLACED_UNREADABLE,

    /** Created, but writing it failed — the hash WILL change again. */
    CREATED_UNSAVED,
}

internal data class ResolvedClientIdentity(
    val identity: Identity,
    val origin: ClientIdentityOrigin,
)

/**
 * Load the persisted rnsh client identity from [dir], or create and save one.
 *
 * Kept free of Android APIs so it can be tested directly: the bug in #585 was
 * that this step did not exist, and the regression test that matters is simply
 * "call it twice, get the same hash". Logging is the caller's job precisely so
 * this stays testable.
 *
 * The write goes to a temp file and is renamed into place. A half-written key
 * is the failure mode that would hurt most: [Identity.fromFile] returns null
 * for a truncated read, a fresh identity would be minted, and #585 would come
 * back intermittently looking like an unrelated fault.
 */
internal fun resolveClientIdentity(dir: File): ResolvedClientIdentity {
    val file = File(dir, CLIENT_IDENTITY_FILE)

    Identity.fromFile(file.absolutePath)?.let { existing ->
        return ResolvedClientIdentity(existing, ClientIdentityOrigin.LOADED)
    }
    val hadUnreadableFile = file.exists()

    val created = Identity.create()
    val tmp = File(dir, "$CLIENT_IDENTITY_FILE.tmp")
    val saved = runCatching {
        dir.mkdirs()
        check(created.toFile(tmp.absolutePath)) { "toFile reported failure" }
        check(tmp.renameTo(file)) { "rename into place failed" }
        // Owner-only: this is a private key sitting in app storage.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        true
    }.getOrElse {
        tmp.delete()
        false
    }

    val origin = when {
        !saved -> ClientIdentityOrigin.CREATED_UNSAVED
        hadUnreadableFile -> ClientIdentityOrigin.REPLACED_UNREADABLE
        else -> ClientIdentityOrigin.CREATED
    }
    return ResolvedClientIdentity(created, origin)
}
