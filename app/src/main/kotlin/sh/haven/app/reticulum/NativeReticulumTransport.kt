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
import sh.haven.core.reticulum.ReticulumIdentityImport
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
private const val CLIENT_IDENTITY_BACKUP_FILE = "haven_client_identity.previous"

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

    /**
     * What the process-wide Reticulum stack currently holds, or null before
     * the first successful init.
     *
     * This replaces a bare `initialised` boolean. That boolean ignored the
     * host and port it was asked for, so the first profile to connect decided
     * what the rest of the process could reach and every later connect
     * returned early having done nothing (#588).
     */
    @Volatile
    private var stackState: StackState? = null

    override val isInitialised: Boolean get() = stackState != null

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
        val request = classifyStackRequest(host, port, ifacNetname)
        val current = stackState

        val pending: StackState = when (val action = planStackAction(current, request)) {
            StackAction.AlreadySatisfied ->
                return@withContext clientIdentity?.hexHash ?: "already-initialised"

            is StackAction.Reject -> throw IllegalStateException(action.reason)

            is StackAction.AddGateway -> {
                // The stack is already up. A second profile adds its own
                // interface rather than being silently dropped, which is what
                // used to happen to every connect after the first (#588).
                Log.d(TAG, "adding gateway interface ${LogRedact.host(host, port)} to the running stack")
                addGatewayInterface(action.spec, ifacNetkey, socketDialer)
                stackState = requireNotNull(current).let { it.copy(gateways = it.gateways + action.spec) }
                return@withContext clientIdentity?.hexHash ?: "already-initialised"
            }

            StackAction.StartShared -> {
                Log.d(TAG, "init: configDir=$configDir, shared instance on port $port")
                File(configDir).mkdirs()
                startSharedInstance(configDir, port)
                StackState(StackMode.SHARED_INSTANCE)
            }

            is StackAction.StartGateway -> {
                Log.d(TAG, "init: configDir=$configDir, host=${LogRedact.of(host)}, port=$port")
                File(configDir).mkdirs()
                Reticulum.start(configDir = configDir)
                addGatewayInterface(action.spec, ifacNetkey, socketDialer)
                StackState(StackMode.GATEWAY, setOf(action.spec))
            }
        }

        clientIdentity = loadOrCreateClientIdentity(configDir)

        // Register rnsh announce handler for destination discovery
        Transport.registerAnnounceHandler(
            handler = rnshAnnounceHandler,
            aspectFilter = "rnsh",
        )
        Log.d(TAG, "Registered rnsh announce handler")

        stackState = pending
        val hexHash = clientIdentity?.hexHash ?: ""
        Log.d(TAG, "init complete, identity=$hexHash")
        hexHash
    }

    /**
     * Connect to a local shared instance (Sideband, Columba, rnsd), or throw.
     */
    private fun startSharedInstance(configDir: String, port: Int) {
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
        // unchecked that latched the transport on something that could not
        // reach anything, so every later connect returned early and the
        // interface count stayed at zero for the life of the process (#588).
        if (!Reticulum.getInstance().isConnectedToSharedInstance) {
            // Reticulum.start() latches on an AtomicBoolean and returns the
            // existing instance for every later call, so a failed attempt
            // would otherwise poison the process: starting the shared
            // instance and trying again gets the same dead instance back
            // and can never succeed until the app is force-stopped.
            // Observed on-device before this line existed (#588).
            runCatching { Reticulum.stop() }
            throw IllegalStateException(
                "No Reticulum shared instance answered on port $port. " +
                    "Start Sideband or another shared instance, or use a gateway host instead.",
            )
        }
        // The gateway path already waits for its TCP interface; this one did
        // not, and the caller sends a link request within milliseconds.
        val iface = lastRegisteredSharedInterface
        if (iface == null) {
            Log.w(TAG, "shared instance connected but no interface was registered")
        } else if (!awaitOnline(
                timeoutMs = 5_000,
                nowMs = System::currentTimeMillis,
                sleep = Thread::sleep,
                isOnline = { iface.online.get() },
            )
        ) {
            Log.w(TAG, "shared-instance interface not online within 5s; the first packet may be dropped")
        }
    }

    /**
     * Build a TCP interface for [spec], register it with Transport and wait
     * briefly for it to come up.
     */
    private fun addGatewayInterface(
        spec: GatewaySpec,
        ifacNetkey: String?,
        socketDialer: ((String, Int, Int) -> java.net.Socket)?,
    ) {
        val tcpClient = TCPClientInterface(
            name = "Gateway ${spec.host}:${spec.port}",
            targetHost = spec.host,
            targetPort = spec.port,
            ifacNetname = spec.ifacNetname,
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
            Log.w(TAG, "TCP connection to ${LogRedact.host(spec.host, spec.port)} not established within 10s")
        }
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
        check(isInitialised) { "Reticulum not initialised" }

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
        check(isInitialised) { "Reticulum not initialised" }

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
            if (!isInitialised) return@withContext false
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

    override suspend fun clientIdentityHash(configDir: String): String? =
        withContext(Dispatchers.IO) { storedClientIdentityHash(File(configDir)) }

    override suspend fun importClientIdentity(
        configDir: String,
        source: File,
    ): ReticulumIdentityImport = withContext(Dispatchers.IO) {
        // Whether the running stack has already read an identity decides what
        // the user is told, so it has to be sampled before the file changes.
        val alreadyRunning = stackState != null
        when (val result = importClientIdentity(File(configDir), source)) {
            is IdentityImport.Installed -> {
                Log.i(
                    TAG,
                    "client identity imported: ${result.hexHash.take(8)}… " +
                        "(replaced ${result.replacedHexHash?.take(8) ?: "nothing"})",
                )
                ReticulumIdentityImport.Installed(
                    hexHash = result.hexHash,
                    replacedHexHash = result.replacedHexHash,
                    takesEffectAfterRestart = alreadyRunning,
                )
            }
            is IdentityImport.NotAnIdentity -> {
                Log.w(TAG, "client identity import refused: ${result.reason}")
                ReticulumIdentityImport.NotAnIdentity(result.reason)
            }
            is IdentityImport.InstallFailed -> {
                Log.e(TAG, "client identity import failed: ${result.reason}")
                ReticulumIdentityImport.InstallFailed(result.reason)
            }
        }
    }

    override suspend fun closeAll() = withContext(Dispatchers.IO) {
        try {
            Reticulum.stop()
        } catch (e: Exception) {
            Log.e(TAG, "closeAll failed", e)
        }
        stackState = null
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
 * Identity of a gateway interface.
 *
 * Two profiles pointing at the same host, port and IFAC network share one
 * interface; anything else needs a second one. IFAC is part of the key
 * because the same address on a different named network is a different peer
 * as far as Reticulum is concerned. The passphrase is not: it is a secret,
 * and two profiles disagreeing about it is a configuration error rather than
 * a reason to open a second socket.
 */
internal data class GatewaySpec(
    val host: String,
    val port: Int,
    val ifacNetname: String?,
)

/** What a connection profile asks the process-wide Reticulum stack for. */
internal sealed interface StackRequest {
    /** A local shared instance — Sideband, Columba, rnsd — owns the interfaces. */
    data object SharedInstance : StackRequest

    /** This profile contributes one TCP interface to the stack. */
    data class Gateway(val spec: GatewaySpec) : StackRequest
}

/** Which of the two mutually exclusive modes the stack is running in. */
internal enum class StackMode {
    /** Proxying through another app's Reticulum instance. */
    SHARED_INSTANCE,

    /** Running our own transport, with our own interfaces. */
    GATEWAY,
}

/** What the process-wide stack currently holds. */
internal data class StackState(
    val mode: StackMode,
    val gateways: Set<GatewaySpec> = emptySet(),
)

/** What [NativeReticulumTransport.init] should do about a request. */
internal sealed interface StackAction {
    /** Nothing is running yet; connect to the shared instance. */
    data object StartShared : StackAction

    /** Nothing is running yet; start our own transport and add [spec]. */
    data class StartGateway(val spec: GatewaySpec) : StackAction

    /** The stack is up; add [spec] as one more interface on it. */
    data class AddGateway(val spec: GatewaySpec) : StackAction

    /** The stack already carries what this profile needs. */
    data object AlreadySatisfied : StackAction

    /** The stack as it stands cannot serve this profile. [reason] is shown to the user. */
    data class Reject(val reason: String) : StackAction
}

private val SHARED_INSTANCE_HOSTS = setOf("127.0.0.1", "localhost", "::1")
private const val SHARED_INSTANCE_PORT = 37428

/** Read a profile's host and port as a request against the shared stack. */
internal fun classifyStackRequest(host: String, port: Int, ifacNetname: String?): StackRequest =
    if (host in SHARED_INSTANCE_HOSTS && port == SHARED_INSTANCE_PORT) {
        StackRequest.SharedInstance
    } else {
        StackRequest.Gateway(GatewaySpec(host, port, ifacNetname))
    }

/**
 * Decide what a profile's connect attempt should do to the stack.
 *
 * Reticulum keeps one process-wide set of interfaces and resolves paths
 * across all of them, but Haven treats host and port as a property of a
 * connection profile. Reconciling those is what this function is for: a
 * profile contributes an interface where it can, and where it genuinely
 * cannot it says so rather than reporting success and reaching nothing.
 *
 * Kept pure and free of Android and Reticulum APIs so the decision is
 * testable on its own — the fault in #588 was in this decision, not in the
 * networking underneath it.
 */
internal fun planStackAction(current: StackState?, request: StackRequest): StackAction {
    if (current == null) {
        return when (request) {
            StackRequest.SharedInstance -> StackAction.StartShared
            is StackRequest.Gateway -> StackAction.StartGateway(request.spec)
        }
    }
    return when (request) {
        StackRequest.SharedInstance ->
            if (current.mode == StackMode.SHARED_INSTANCE) {
                StackAction.AlreadySatisfied
            } else {
                StackAction.Reject(
                    "Reticulum is already connected to a gateway in this session. Joining a " +
                        "shared instance replaces the whole stack rather than adding to it, so " +
                        "the two cannot run side by side. Disconnect the gateway session first.",
                )
            }

        is StackRequest.Gateway -> when {
            current.mode == StackMode.SHARED_INSTANCE -> StackAction.Reject(
                "Reticulum is connected to a shared instance, which owns the network " +
                    "interfaces on behalf of every app using it. Add this gateway in Sideband " +
                    "or whichever app is running the shared instance, or disconnect from it first.",
            )

            request.spec in current.gateways -> StackAction.AlreadySatisfied

            else -> StackAction.AddGateway(request.spec)
        }
    }
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
        // Kept so start-up can wait for it to come online. Registering an
        // interface is not the same as it being usable, and the first packet
        // out is sent immediately after (see startSharedInstance).
        lastRegisteredSharedInterface = iface
    } else {
        Log.e(TAG, "cannot register ${iface.javaClass.name}: not a Reticulum interface")
    }
}

/** Set by [sharedInstanceInterfaceRegistrar]; read only by startSharedInstance. */
@Volatile
internal var lastRegisteredSharedInterface: RnsInterface? = null

/**
 * Block until [isOnline] reports true, or [timeoutMs] elapses.
 *
 * The shared-instance interface is registered from inside the library before
 * its read loop has connected, and Haven opens the rnsh link within
 * milliseconds of `init` returning. Without this wait the first link request
 * hits `processOutgoing called but interface not online` and is dropped —
 * and because a link request is not retried, the session then sits for the
 * full 30s establishment timeout and fails with nothing having been sent.
 * Device-observed on 2026-08-25, twice in a row, once releasing the stack on
 * the last disconnect made every connect re-initialise from cold.
 *
 * @return true if it came online within the timeout
 */
internal fun awaitOnline(
    timeoutMs: Long,
    nowMs: () -> Long,
    sleep: (Long) -> Unit,
    isOnline: () -> Boolean,
): Boolean {
    val deadline = nowMs() + timeoutMs
    while (!isOnline()) {
        if (nowMs() >= deadline) return false
        sleep(50)
    }
    return true
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
/**
 * The stored client identity's hash, or null if there is no usable key in [dir].
 *
 * Separate from [resolveClientIdentity] because reading must not have the side
 * effect of minting one: this answers "what does this device present today" for
 * a screen the user may open before ever connecting, and creating an identity
 * there would be a surprise.
 */
internal fun storedClientIdentityHash(dir: File): String? =
    runCatching { Identity.fromFile(File(dir, CLIENT_IDENTITY_FILE).absolutePath) }
        .getOrNull()
        ?.hexHash

/** Outcome of importing a user-supplied Reticulum identity file (#585). */
internal sealed interface IdentityImport {
    /**
     * The file parsed and is now the client identity. [replacedHexHash] is the
     * hash that was in use before, or null if there was none, so the caller can
     * tell the user what they just stopped being.
     */
    data class Installed(val hexHash: String, val replacedHexHash: String?) : IdentityImport

    /** The source is missing, unreadable, or not a Reticulum identity. */
    data class NotAnIdentity(val reason: String) : IdentityImport

    /** The source parsed but could not be installed. The old identity is intact. */
    data class InstallFailed(val reason: String) : IdentityImport
}

/**
 * Install a user-supplied Reticulum identity from [source] as the client
 * identity in [dir] — the second half of #585, after persistence.
 *
 * The rule that matters is that a bad import must not cost the user the
 * identity they already have. A whitelist entry on the far side is keyed to
 * that hash, so silently replacing it with a fresh one is the same damage #585
 * was reported for. Nothing is touched until [source] has been parsed, the
 * previous key is set aside rather than overwritten, and a failure part-way
 * through puts it back.
 *
 * Note for callers: an identity cannot be reconstructed from its hash. The
 * request in #585 mentions "set the identity hash or load an identity file";
 * only the file can work, because the hash is a fingerprint of a private key
 * rather than the key itself.
 *
 * Android-free so the rules above are directly testable, same as
 * [resolveClientIdentity].
 */
internal fun importClientIdentity(dir: File, source: File): IdentityImport {
    val imported = runCatching { Identity.fromFile(source.absolutePath) }.getOrNull()
        ?: return IdentityImport.NotAnIdentity(
            if (!source.exists()) {
                "no file at ${source.absolutePath}"
            } else {
                "${source.name} is not a Reticulum identity"
            },
        )

    val file = File(dir, CLIENT_IDENTITY_FILE)
    val backup = File(dir, CLIENT_IDENTITY_BACKUP_FILE)
    val replaced = Identity.fromFile(file.absolutePath)?.hexHash
    val tmp = File(dir, "$CLIENT_IDENTITY_FILE.import")

    var setAside = false
    return runCatching {
        dir.mkdirs()
        check(imported.toFile(tmp.absolutePath)) { "writing the imported identity failed" }
        if (file.exists()) {
            backup.delete()
            check(file.renameTo(backup)) { "could not set the current identity aside" }
            setAside = true
        }
        check(tmp.renameTo(file)) { "rename into place failed" }
        restrictToOwner(file)
        IdentityImport.Installed(imported.hexHash, replaced)
    }.getOrElse { failure ->
        tmp.delete()
        // Put the old identity back rather than leaving the user with none.
        if (setAside && !file.exists()) backup.renameTo(file)
        IdentityImport.InstallFailed(failure.message ?: failure.javaClass.simpleName)
    }
}

/** Owner-only permissions: these files are private keys sitting in app storage. */
private fun restrictToOwner(file: File) {
    file.setReadable(false, false)
    file.setReadable(true, true)
    file.setWritable(false, false)
    file.setWritable(true, true)
}

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
        restrictToOwner(file)
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
