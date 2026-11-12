package sh.haven.core.local

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService (#837) running in Shizuku's UID (typically
 * `shell`, uid 2000, or `root`, uid 0).
 *
 * Allocates `/dev/pts` pseudoterminals inside the `shell` SELinux domain via
 * [PtyBridge.nativeForkPty] and relays bytes over an `AF_UNIX` socketpair
 * [ParcelFileDescriptor] so Haven's untrusted-app process never touches
 * `shell_devpts` directly under strict SELinux policies.
 */
@Keep
class ShizukuPtyUserService : IShizukuPtyService.Stub {

    @Volatile
    private var nativeLibraryDir: String? = null

    @Keep
    constructor() : super()

    @Keep
    constructor(context: Context) : super() {
        nativeLibraryDir = context.applicationInfo?.nativeLibraryDir
    }

    private sealed class ManagedSession {
        abstract val exitFuture: CompletableFuture<Int>
        abstract fun close()

        class Pty(
            val childPid: Int,
            val masterFd: Int,
            val masterPfd: ParcelFileDescriptor,
            val serviceSocket: ParcelFileDescriptor,
            override val exitFuture: CompletableFuture<Int> = CompletableFuture(),
        ) : ManagedSession() {
            @Volatile
            var closed: Boolean = false

            override fun close() {
                if (closed) return
                closed = true
                runCatching { serviceSocket.close() }
                runCatching { masterPfd.close() }
                if (!exitFuture.isDone && childPid > 0) {
                    runCatching { Process.killProcess(childPid) }
                }
            }
        }

        class Pipe(
            val process: java.lang.Process,
            @Volatile var clientStdin: ParcelFileDescriptor?,
            @Volatile var clientStdout: ParcelFileDescriptor?,
            @Volatile var clientStderr: ParcelFileDescriptor?,
            override val exitFuture: CompletableFuture<Int> = CompletableFuture(),
        ) : ManagedSession() {
            override fun close() {
                runCatching { clientStdin?.close() }
                runCatching { clientStdout?.close() }
                runCatching { clientStderr?.close() }
                clientStdin = null
                clientStdout = null
                clientStderr = null
                runCatching { process.destroy() }
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, ManagedSession>()

    override fun destroy() {
        val snapshot = sessions.values.toList()
        sessions.clear()
        snapshot.forEach { runCatching { it.close() } }
        exitProcess(0)
    }

    override fun openPtySession(
        sessionId: String,
        cmd: String,
        args: Array<String>,
        env: Array<String>,
        rows: Int,
        cols: Int,
    ): ParcelFileDescriptor {
        try {
            PtyBridge.ensureLoaded(nativeLibraryDir)
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to load libpty_bridge.so in Shizuku service: ${t.message}")
        }

        val safeRows = rows.coerceIn(1, 500)
        val safeCols = cols.coerceIn(1, 500)
        val result = PtyBridge.nativeForkPty(cmd, args, env, safeRows, safeCols, false)
        val fd = result[0]
        if (fd < 0) {
            throw IllegalStateException("Shizuku forkpty failed (errno=${result[1]})")
        }
        val pid = result[1]
        val masterPfd = ParcelFileDescriptor.adoptFd(fd)
        val pair = try {
            ParcelFileDescriptor.createSocketPair()
        } catch (t: Throwable) {
            runCatching { masterPfd.close() }
            runCatching { Process.killProcess(pid) }
            throw IllegalStateException("Shizuku socketpair failed: ${t.message}")
        }

        val serviceEnd = pair[0]
        val clientEnd = pair[1]
        val entry = ManagedSession.Pty(
            childPid = pid,
            masterFd = fd,
            masterPfd = masterPfd,
            serviceSocket = serviceEnd,
        )
        sessions[sessionId]?.close()
        sessions[sessionId] = entry

        // Pump PTY master -> client socket; reap exit status on PTY EOF.
        Thread({
            val buf = ByteArray(8192)
            val ptyIn = FileInputStream(masterPfd.fileDescriptor)
            val sockOut = FileOutputStream(serviceEnd.fileDescriptor)
            try {
                while (!entry.closed) {
                    val n = ptyIn.read(buf)
                    if (n <= 0) break
                    sockOut.write(buf, 0, n)
                    sockOut.flush()
                }
            } catch (_: Throwable) {
            } finally {
                val code = try {
                    PtyBridge.nativeWaitPid(pid)
                } catch (_: Throwable) {
                    -1
                }
                entry.exitFuture.complete(code)
                runCatching { serviceEnd.close() }
                runCatching { masterPfd.close() }
            }
        }, "shizuku-pty-out-$sessionId").apply {
            isDaemon = true
            start()
        }

        // Pump client socket -> PTY master; kill child if client disconnects early.
        Thread({
            val buf = ByteArray(8192)
            val sockIn = FileInputStream(serviceEnd.fileDescriptor)
            val ptyOut = FileOutputStream(masterPfd.fileDescriptor)
            try {
                while (!entry.closed) {
                    val n = sockIn.read(buf)
                    if (n <= 0) break
                    ptyOut.write(buf, 0, n)
                    ptyOut.flush()
                }
            } catch (_: Throwable) {
            } finally {
                if (!entry.exitFuture.isDone) {
                    runCatching { Process.killProcess(pid) }
                }
                runCatching { masterPfd.close() }
            }
        }, "shizuku-pty-in-$sessionId").apply {
            isDaemon = true
            start()
        }

        return clientEnd
    }

    override fun startPipeSession(
        sessionId: String,
        cmd: String,
        args: Array<String>,
        env: Array<String>,
    ) {
        val argv = if (args.isNotEmpty()) args.toList() else listOf(cmd)
        val pb = ProcessBuilder(argv)
        val defaultCwd = File("/data/local/tmp")
        if (defaultCwd.isDirectory) {
            pb.directory(defaultCwd)
        }
        val pbEnv = pb.environment()
        pbEnv.clear()
        for (entry in env) {
            val eq = entry.indexOf('=')
            if (eq > 0) {
                pbEnv[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
        }

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            throw IllegalStateException("Shizuku process start failed: ${t.message}")
        }

        val stdinPipe = ParcelFileDescriptor.createPipe()
        val stdoutPipe = ParcelFileDescriptor.createPipe()
        val stderrPipe = ParcelFileDescriptor.createPipe()

        val entry = ManagedSession.Pipe(
            process = proc,
            clientStdin = stdinPipe[1],
            clientStdout = stdoutPipe[0],
            clientStderr = stderrPipe[0],
        )
        sessions[sessionId]?.close()
        sessions[sessionId] = entry

        val stdoutDone = CompletableFuture<Unit>()
        val stderrDone = CompletableFuture<Unit>()

        pumpClose(
            name = "shizuku-pipe-in-$sessionId",
            src = ParcelFileDescriptor.AutoCloseInputStream(stdinPipe[0]),
            dst = proc.outputStream,
            onDone = {},
        )
        pumpClose(
            name = "shizuku-pipe-out-$sessionId",
            src = proc.inputStream,
            dst = ParcelFileDescriptor.AutoCloseOutputStream(stdoutPipe[1]),
            onDone = { stdoutDone.complete(Unit) },
        )
        pumpClose(
            name = "shizuku-pipe-err-$sessionId",
            src = proc.errorStream,
            dst = ParcelFileDescriptor.AutoCloseOutputStream(stderrPipe[1]),
            onDone = { stderrDone.complete(Unit) },
        )

        Thread({
            val code = try {
                proc.waitFor()
            } catch (_: Throwable) {
                -1
            }
            runCatching { stdoutDone.get(2, TimeUnit.SECONDS) }
            runCatching { stderrDone.get(2, TimeUnit.SECONDS) }
            entry.exitFuture.complete(code)
        }, "shizuku-pipe-wait-$sessionId").apply {
            isDaemon = true
            start()
        }
    }

    override fun takeSessionFd(sessionId: String, stream: Int): ParcelFileDescriptor {
        val entry = sessions[sessionId] as? ManagedSession.Pipe
            ?: throw IllegalStateException("Unknown pipe session: $sessionId")
        val pfd = when (stream) {
            STREAM_STDIN -> entry.clientStdin.also { entry.clientStdin = null }
            STREAM_STDOUT -> entry.clientStdout.also { entry.clientStdout = null }
            STREAM_STDERR -> entry.clientStderr.also { entry.clientStderr = null }
            else -> throw IllegalArgumentException("Invalid stream index: $stream")
        }
        return pfd ?: throw IllegalStateException("Stream $stream already taken for $sessionId")
    }

    override fun resizeSession(sessionId: String, rows: Int, cols: Int): Int {
        val entry = sessions[sessionId] as? ManagedSession.Pty ?: return -1
        if (entry.closed || entry.masterFd < 0) return -1
        return try {
            PtyBridge.nativeSetSize(entry.masterFd, rows.coerceIn(1, 500), cols.coerceIn(1, 500))
        } catch (_: Throwable) {
            -1
        }
    }

    override fun waitExit(sessionId: String): Int {
        val entry = sessions[sessionId] ?: return -1
        return try {
            entry.exitFuture.get(10, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            -1
        } finally {
            sessions.remove(sessionId, entry)
            entry.close()
        }
    }

    override fun closeSession(sessionId: String) {
        sessions.remove(sessionId)?.close()
    }

    private fun pumpClose(
        name: String,
        src: InputStream,
        dst: OutputStream,
        onDone: () -> Unit,
    ) {
        Thread({
            val buf = ByteArray(8192)
            try {
                src.use { input ->
                    dst.use { output ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                output.write(buf, 0, n)
                                output.flush()
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                onDone()
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        const val STREAM_STDIN = 0
        const val STREAM_STDOUT = 1
        const val STREAM_STDERR = 2
    }
}
