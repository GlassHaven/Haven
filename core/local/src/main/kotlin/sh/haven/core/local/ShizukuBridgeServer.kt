package sh.haven.core.local

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShizukuBridgeServer"

/**
 * App-private filesystem `AF_UNIX` stream server (`cacheDir/.haven-shizuku.sock`,
 * visible inside PRoot at `/tmp/.haven-shizuku.sock`) that backs
 * `/usr/local/bin/shizuku` and `/usr/local/bin/rish` (#837).
 *
 * Every incoming connection is authenticated with `SO_PEERCRED`
 * (`LocalSocket.peerCredentials.uid == Process.myUid()`) before reading any bytes,
 * so processes outside Haven's UID cannot reach the bridge even if they somehow
 * obtained path traversal to `cacheDir`.
 */
@Singleton
class ShizukuBridgeServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuPtyClient: ShizukuPtyClient,
) {
    private val started = AtomicBoolean(false)
    private val clientPool = Executors.newCachedThreadPool { r ->
        Thread(r, "shizuku-bridge-client").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    @Volatile
    private var serverFd: FileDescriptor? = null

    val hostSocketFile: File
        get() = File(context.cacheDir, ShizukuBridgeProtocol.SOCKET_FILENAME)

    /**
     * Idempotently ensure the filesystem `AF_UNIX` listener is active at
     * `cacheDir/.haven-shizuku.sock` (`0600`).
     */
    fun ensureStarted() {
        if (started.get() && hostSocketFile.exists()) return
        synchronized(this) {
            if (started.get() && hostSocketFile.exists()) return
            stopLocked()
            val sockFile = hostSocketFile
            sockFile.parentFile?.mkdirs()
            runCatching { Os.remove(sockFile.absolutePath) }

            try {
                // Bind via android.net.LocalSocket in FILESYSTEM namespace so it
                // creates a real inode inside cacheDir (bound into PRoot at /tmp).
                val bindSock = LocalSocket(LocalSocket.SOCKET_STREAM)
                bindSock.bind(
                    LocalSocketAddress(
                        sockFile.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM,
                    )
                )
                runCatching { Os.chmod(sockFile.absolutePath, 384 /* 0600 */) }
                val rawFd = Os.dup(bindSock.fileDescriptor)
                bindSock.close()
                val srv = LocalServerSocket(rawFd)
                serverFd = rawFd
                serverSocket = srv
                started.set(true)

                Thread({ acceptLoop(srv) }, "shizuku-bridge-accept").apply {
                    isDaemon = true
                    start()
                }
                Log.d(TAG, "Shizuku Unix bridge listening at ${sockFile.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Shizuku Unix bridge: ${t.message}", t)
                started.set(false)
            }
        }
    }

    private fun stopLocked() {
        started.set(false)
        serverSocket?.let { srv ->
            runCatching { srv.close() }
        }
        serverSocket = null
        serverFd?.let { fd ->
            runCatching { Os.close(fd) }
        }
        serverFd = null
        runCatching { Os.remove(hostSocketFile.absolutePath) }
    }

    private fun acceptLoop(srv: LocalServerSocket) {
        while (started.get()) {
            val clientSock = try {
                srv.accept()
            } catch (t: Throwable) {
                if (started.get()) {
                    Log.w(TAG, "Shizuku bridge accept ended: ${t.message}")
                }
                break
            }

            clientPool.execute {
                handleClient(clientSock)
            }
        }
    }

    private fun handleClient(clientSock: LocalSocket) {
        clientSock.use { sock ->
            // Verify peer UID via SO_PEERCRED before reading a single byte.
            val peerAllowed = try {
                val cred = sock.peerCredentials
                val expectedUid = Process.myUid()
                if (cred.uid != expectedUid) {
                    Log.w(TAG, "Rejected Shizuku bridge connection from foreign uid=${cred.uid} (expected $expectedUid)")
                    false
                } else {
                    true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "SO_PEERCRED check failed: ${t.message}")
                false
            }

            if (!peerAllowed) {
                return
            }

            val sockIn: InputStream = sock.inputStream
            val sockOut: OutputStream = sock.outputStream

            try {
                val handshake = ShizukuBridgeProtocol.readHandshake(sockIn)
                val svc = try {
                    runBlocking(Dispatchers.IO) { shizukuPtyClient.ensureReady() }
                } catch (t: Throwable) {
                    val msg = t.message ?: "Shizuku is unavailable."
                    ShizukuBridgeProtocol.writeErrorFrame(sockOut, msg)
                    ShizukuBridgeProtocol.writeExitFrame(sockOut, 1)
                    return
                }

                if (handshake.usePty) {
                    servePtySession(svc, handshake, sockIn, sockOut)
                } else {
                    servePipeSession(svc, handshake, sockIn, sockOut)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Shizuku bridge session ended: ${t.message}")
            }
        }
    }

    private fun servePtySession(
        svc: IShizukuPtyService,
        handshake: ShizukuBridgeProtocol.Handshake,
        sockIn: InputStream,
        sockOut: OutputStream,
    ) {
        val remoteId = UUID.randomUUID().toString()
        val (cmd, args) = shizukuPtyClient.buildShellCommand(handshake.args)
        val env = shizukuPtyClient.buildShellEnv(term = handshake.term)

        val ptyPfd = try {
            svc.openPtySession(remoteId, cmd, args, env, handshake.rows, handshake.cols)
        } catch (t: Throwable) {
            ShizukuBridgeProtocol.writeErrorFrame(sockOut, t.message ?: "Failed to open Shizuku PTY")
            ShizukuBridgeProtocol.writeExitFrame(sockOut, 1)
            return
        }

        val ptyIn = FileInputStream(ptyPfd.fileDescriptor)
        val ptyOut = FileOutputStream(ptyPfd.fileDescriptor)

        // Pump client frames (STDIN / RESIZE) -> remote PTY on a helper thread.
        val stdinPump = Thread({
            try {
                while (true) {
                    val frame = ShizukuBridgeProtocol.readFrame(sockIn) ?: break
                    when (frame.type) {
                        ShizukuBridgeProtocol.MSG_STDIN -> {
                            if (frame.payload.isNotEmpty()) {
                                ptyOut.write(frame.payload)
                                ptyOut.flush()
                            }
                        }
                        ShizukuBridgeProtocol.MSG_STDIN_EOF -> {
                            // On a PTY, Ctrl-D (0x04) signals EOT if canonical; don't close master fd
                            // until PTY stdout itself closes, or interactive programs lose output.
                        }
                        ShizukuBridgeProtocol.MSG_RESIZE -> {
                            ShizukuBridgeProtocol.parseResizePayload(frame.payload)?.let { (rows, cols) ->
                                runCatching { svc.resizeSession(remoteId, rows, cols) }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                // Client disconnected -> terminate remote PTY if still running.
                runCatching { ptyPfd.close() }
            }
        }, "shizuku-bridge-in-$remoteId").apply {
            isDaemon = true
            start()
        }

        try {
            val buf = ByteArray(16_384)
            while (true) {
                val n = try {
                    ptyIn.read(buf)
                } catch (_: Throwable) {
                    -1
                }
                if (n <= 0) break
                ShizukuBridgeProtocol.writeFrame(sockOut, ShizukuBridgeProtocol.MSG_STDOUT, buf, 0, n)
            }
            val exitCode = runCatching { svc.waitExit(remoteId) }.getOrDefault(0)
            runCatching { ShizukuBridgeProtocol.writeExitFrame(sockOut, exitCode) }
        } finally {
            runCatching { svc.closeSession(remoteId) }
            runCatching { ptyPfd.close() }
            stdinPump.interrupt()
        }
    }

    private fun servePipeSession(
        svc: IShizukuPtyService,
        handshake: ShizukuBridgeProtocol.Handshake,
        sockIn: InputStream,
        sockOut: OutputStream,
    ) {
        val remoteId = UUID.randomUUID().toString()
        val (cmd, args) = shizukuPtyClient.buildShellCommand(handshake.args)
        val env = shizukuPtyClient.buildShellEnv(term = handshake.term)

        val stdinPfd: ParcelFileDescriptor
        val stdoutPfd: ParcelFileDescriptor
        val stderrPfd: ParcelFileDescriptor
        try {
            svc.startPipeSession(remoteId, cmd, args, env)
            stdinPfd = svc.takeSessionFd(remoteId, ShizukuPtyUserService.STREAM_STDIN)
            stdoutPfd = svc.takeSessionFd(remoteId, ShizukuPtyUserService.STREAM_STDOUT)
            stderrPfd = svc.takeSessionFd(remoteId, ShizukuPtyUserService.STREAM_STDERR)
        } catch (t: Throwable) {
            runCatching { svc.closeSession(remoteId) }
            ShizukuBridgeProtocol.writeErrorFrame(sockOut, t.message ?: "Failed to start Shizuku command")
            ShizukuBridgeProtocol.writeExitFrame(sockOut, 1)
            return
        }

        val stdinPump = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(stdinPfd).use { remoteIn ->
                    while (true) {
                        val frame = ShizukuBridgeProtocol.readFrame(sockIn) ?: break
                        when (frame.type) {
                            ShizukuBridgeProtocol.MSG_STDIN -> {
                                if (frame.payload.isNotEmpty()) {
                                    remoteIn.write(frame.payload)
                                    remoteIn.flush()
                                }
                            }
                            ShizukuBridgeProtocol.MSG_STDIN_EOF -> break
                        }
                    }
                }
            } catch (_: Throwable) {
                runCatching { stdinPfd.close() }
            }
        }, "shizuku-pipe-bridge-in-$remoteId").apply {
            isDaemon = true
            start()
        }

        val outPump = Thread({
            val buf = ByteArray(16_384)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(stdoutPfd).use { sOut ->
                    while (true) {
                        val n = sOut.read(buf)
                        if (n <= 0) break
                        ShizukuBridgeProtocol.writeFrame(sockOut, ShizukuBridgeProtocol.MSG_STDOUT, buf, 0, n)
                    }
                }
            } catch (_: Throwable) {
            }
        }, "shizuku-pipe-bridge-out-$remoteId").apply {
            isDaemon = true
            start()
        }

        val errPump = Thread({
            val buf = ByteArray(16_384)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(stderrPfd).use { sErr ->
                    while (true) {
                        val n = sErr.read(buf)
                        if (n <= 0) break
                        ShizukuBridgeProtocol.writeFrame(sockOut, ShizukuBridgeProtocol.MSG_STDERR, buf, 0, n)
                    }
                }
            } catch (_: Throwable) {
            }
        }, "shizuku-pipe-bridge-err-$remoteId").apply {
            isDaemon = true
            start()
        }

        try {
            outPump.join()
            errPump.join()
            val exitCode = runCatching { svc.waitExit(remoteId) }.getOrDefault(0)
            runCatching { ShizukuBridgeProtocol.writeExitFrame(sockOut, exitCode) }
        } finally {
            runCatching { svc.closeSession(remoteId) }
            runCatching { stdinPfd.close() }
            runCatching { stdoutPfd.close() }
            runCatching { stderrPfd.close() }
            stdinPump.interrupt()
        }
    }
}
