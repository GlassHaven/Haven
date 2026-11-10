package sh.haven.feature.sftp.root

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.haven.feature.sftp.SftpEntry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy, mutex-serialised client for [RootFsDaemon] (#799).
 *
 * Security & consent invariants:
 * 1. Never spawns `su` on construction or app startup — a child `su` process is
 *    started ONLY when an explicit caller invokes a filesystem operation.
 * 2. Tries `su -mm -c` first (global mount namespace on Magisk/KernelSU/APatch
 *    so `/data/data/<pkg>` is visible on Android 11+), falling back once to
 *    plain `su -c` if the root manager rejects `-mm`.
 * 3. Scans up to [MAX_BANNER_SKIP_BYTES] prior to [RootFsDaemon.MAGIC] so shell
 *    or Magisk wrapper lines printed before `exec app_process` cannot corrupt
 *    RPC framing.
 */
@Singleton
class RootFsClient private constructor(
    private val starter: () -> Conn,
) {

    @Inject
    constructor(@ApplicationContext appContext: Context) : this(
        starter = { startAndroidSuProcess(appContext.applicationInfo.sourceDir) },
    )

    /** Secondary constructor for JVM unit tests spawning [RootFsDaemon.main] directly. */
    internal constructor(processFactory: () -> Process, testMarker: Boolean) : this(
        starter = { connectProcess(processFactory()) },
    )

    internal class Conn(
        val process: Process,
        val input: DataInputStream,
        val output: DataOutputStream,
        val uid: Int,
    ) {
        val isAlive: Boolean get() = process.isAlive

        fun close() {
            try {
                output.writeByte(RootFsDaemon.OP_QUIT)
                output.flush()
            } catch (_: Throwable) {}
            runCatching { input.close() }
            runCatching { output.close() }
            process.destroy()
        }
    }

    private val rpcMutex = Mutex()
    @Volatile
    private var activeConn: Conn? = null

    private suspend fun <T> withRpc(block: (Conn) -> T): T =
        withContext(Dispatchers.IO) {
            rpcMutex.withLock {
                var conn = activeConn
                if (conn == null || !conn.isAlive) {
                    conn?.close()
                    conn = starter()
                    activeConn = conn
                }
                try {
                    block(conn)
                } catch (e: EOFException) {
                    // Daemon died mid-call — drop cached connection so next op reconnects cleanly.
                    conn.close()
                    activeConn = null
                    throw IOException("Root filesystem helper disconnected", e)
                } catch (e: IOException) {
                    if (!conn.isAlive) {
                        conn.close()
                        activeConn = null
                    }
                    throw e
                }
            }
        }

    /** Shut down the persistent `su` helper immediately (e.g. when the Settings toggle is turned off). */
    suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            rpcMutex.withLock {
                activeConn?.close()
                activeConn = null
            }
        }
    }

    /** Non-blocking cleanup for lifecycle hooks (`ViewModel.onCleared`). */
    fun shutdownAsync() {
        if (activeConn == null) return
        Thread({
            kotlinx.coroutines.runBlocking { shutdown() }
        }, "haven-rootfs-shutdown").apply { isDaemon = true }.start()
    }

    suspend fun list(path: String): List<SftpEntry> = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_LIST)
        RootFsDaemon.writeString(conn.output, path)
        conn.output.flush()
        readStatusOrThrow(conn.input)
        val count = conn.input.readInt()
        if (count < 0 || count > 500_000) throw IOException("Unreasonable directory entry count: $count")
        val list = ArrayList<SftpEntry>(minOf(count, 1024))
        repeat(count) {
            list.add(RootFsDaemon.readRawEntry(conn.input).toSftpEntry())
        }
        list
    }

    suspend fun stat(path: String): SftpEntry = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_STAT)
        RootFsDaemon.writeString(conn.output, path)
        conn.output.flush()
        readStatusOrThrow(conn.input)
        RootFsDaemon.readRawEntry(conn.input).toSftpEntry()
    }

    suspend fun readRange(path: String, offset: Long, maxBytes: Int): ByteArray = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_READ_RANGE)
        RootFsDaemon.writeString(conn.output, path)
        conn.output.writeLong(offset)
        conn.output.writeInt(maxBytes)
        conn.output.flush()
        readStatusOrThrow(conn.input)
        val len = conn.input.readInt()
        if (len < 0 || len > 2 * 1024 * 1024) throw IOException("Invalid readRange length: $len")
        if (len == 0) ByteArray(0)
        else ByteArray(len).also { conn.input.readFully(it) }
    }

    suspend fun readBytes(path: String): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val chunk = readRange(path, offset, CHUNK_BYTES)
            if (chunk.isEmpty()) break
            out.write(chunk)
            offset += chunk.size
        }
        return out.toByteArray()
    }

    suspend fun writeBytes(path: String, data: ByteArray) {
        writeOpen(path, append = false)
        var offset = 0L
        while (offset < data.size) {
            val n = minOf(CHUNK_BYTES, data.size - offset.toInt())
            val slice = data.copyOfRange(offset.toInt(), offset.toInt() + n)
            writeChunk(path, offset, slice)
            offset += n
        }
    }

    suspend fun writeStream(
        path: String,
        input: InputStream,
        append: Boolean = false,
        startOffset: Long = 0L,
        onBytes: ((Long) -> Unit)? = null,
    ) {
        writeOpen(path, append = append)
        val buf = ByteArray(CHUNK_BYTES)
        var offset = startOffset
        var transferred = 0L
        while (true) {
            val n = withContext(Dispatchers.IO) { input.read(buf) }
            if (n < 0) break
            if (n == 0) continue
            val slice = if (n == buf.size) buf else buf.copyOf(n)
            writeChunk(path, offset, slice)
            offset += n
            transferred += n
            onBytes?.invoke(transferred)
        }
    }

    private suspend fun writeOpen(path: String, append: Boolean) = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_WRITE_OPEN)
        RootFsDaemon.writeString(conn.output, path)
        conn.output.writeBoolean(append)
        conn.output.flush()
        readStatusOrThrow(conn.input)
    }

    private suspend fun writeChunk(path: String, offset: Long, chunk: ByteArray) = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_WRITE_CHUNK)
        RootFsDaemon.writeString(conn.output, path)
        conn.output.writeLong(offset)
        conn.output.writeInt(chunk.size)
        if (chunk.isNotEmpty()) conn.output.write(chunk)
        conn.output.flush()
        readStatusOrThrow(conn.input)
    }

    suspend fun mkdir(path: String) = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_MKDIR)
        RootFsDaemon.writeString(conn.output, path)
        conn.output.flush()
        readStatusOrThrow(conn.input)
    }

    suspend fun delete(path: String) = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_DELETE)
        RootFsDaemon.writeString(conn.output, path)
        conn.output.flush()
        readStatusOrThrow(conn.input)
    }

    suspend fun rename(from: String, to: String) = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_RENAME)
        RootFsDaemon.writeString(conn.output, from)
        RootFsDaemon.writeString(conn.output, to)
        conn.output.flush()
        readStatusOrThrow(conn.input)
    }

    suspend fun chmod(path: String, mode: Int) = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_CHMOD)
        RootFsDaemon.writeString(conn.output, path)
        conn.output.writeInt(mode)
        conn.output.flush()
        readStatusOrThrow(conn.input)
    }

    suspend fun chown(path: String, ownerSpec: String) = withRpc { conn ->
        conn.output.writeByte(RootFsDaemon.OP_CHOWN)
        RootFsDaemon.writeString(conn.output, path)
        RootFsDaemon.writeString(conn.output, ownerSpec)
        conn.output.flush()
        readStatusOrThrow(conn.input)
    }

    /**
     * Seekable, concurrent-safe [InputStream] backed by stateless [readRange]
     * chunks. Multiple HTTP Range readers (e.g. `SftpStreamServer` media preview)
     * can read different offsets concurrently without interfering.
     */
    fun openRangeInputStream(path: String, initialOffset: Long = 0L): InputStream =
        object : InputStream() {
            private var pos: Long = initialOffset
            private var buf: ByteArray = ByteArray(0)
            private var idx: Int = 0
            private var eof: Boolean = false

            private fun refill(): Boolean {
                if (eof) return false
                val next = kotlinx.coroutines.runBlocking {
                    readRange(path, pos, CHUNK_BYTES)
                }
                if (next.isEmpty()) {
                    eof = true
                    return false
                }
                buf = next
                idx = 0
                pos += next.size
                return true
            }

            override fun read(): Int {
                if (idx >= buf.size && !refill()) return -1
                return buf[idx++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (idx >= buf.size && !refill()) return -1
                val avail = minOf(len, buf.size - idx)
                System.arraycopy(buf, idx, b, off, avail)
                idx += avail
                return avail
            }
        }

    private fun readStatusOrThrow(input: DataInputStream) {
        when (val status = input.readByte()) {
            RootFsDaemon.STATUS_OK -> return
            RootFsDaemon.STATUS_NOT_FOUND -> throw FileNotFoundException(RootFsDaemon.readString(input))
            RootFsDaemon.STATUS_IO_ERROR -> throw IOException(RootFsDaemon.readString(input))
            else -> throw IOException("Unexpected root RPC status: $status")
        }
    }

    private fun RootFsDaemon.RawEntry.toSftpEntry(): SftpEntry = SftpEntry(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        modifiedTime = modifiedTimeSec,
        permissions = permissions,
        owner = owner,
        group = group,
        childCount = childCount,
        visibleChildCount = visibleChildCount,
    )

    companion object {
        private const val CHUNK_BYTES = 64 * 1024
        private const val MAX_BANNER_SKIP_BYTES = 8192

        internal fun buildAppProcessShellCommand(apkPath: String): String {
            val escapedApk = apkPath.replace("'", "'\\''")
            return "export CLASSPATH='$escapedApk'; exec /system/bin/app_process /system/bin sh.haven.feature.sftp.root.RootFsDaemon"
        }

        private fun startAndroidSuProcess(apkPath: String): Conn {
            val cmd = buildAppProcessShellCommand(apkPath)
            var lastErr: Throwable? = null
            for (suArgs in listOf(listOf("su", "-mm", "-c", cmd), listOf("su", "-c", cmd))) {
                try {
                    val proc = ProcessBuilder(suArgs).start()
                    // Drain stderr asynchronously so stderr pipe buffer cannot block the daemon.
                    Thread({
                        try {
                            proc.errorStream.bufferedReader().forEachLine { /* discard or debug */ }
                        } catch (_: Throwable) {}
                    }, "haven-rootfs-stderr").apply { isDaemon = true }.start()
                    return connectProcess(proc)
                } catch (t: Throwable) {
                    lastErr = t
                }
            }
            throw IOException(
                "Root access unavailable or denied (${lastErr?.message ?: "su failed"})",
                lastErr,
            )
        }

        internal fun connectProcess(proc: Process): Conn {
            try {
                val input = DataInputStream(BufferedInputStream(proc.inputStream, CHUNK_BYTES))
                val output = DataOutputStream(BufferedOutputStream(proc.outputStream, CHUNK_BYTES))
                awaitMagicHandshake(input)
                val uid = input.readInt()
                return Conn(proc, input, output, uid)
            } catch (t: Throwable) {
                proc.destroy()
                throw if (t is IOException) t else IOException("Root helper handshake failed: ${t.message}", t)
            }
        }

        /**
         * Scan byte-by-byte until [RootFsDaemon.MAGIC] appears so any shell login
         * or root-manager stdout text emitted before `exec` is discarded safely.
         */
        internal fun awaitMagicHandshake(input: InputStream) {
            val magic = RootFsDaemon.MAGIC
            var matched = 0
            var scanned = 0
            while (scanned < MAX_BANNER_SKIP_BYTES) {
                val b = input.read()
                if (b < 0) throw EOFException("Root helper exited before handshake")
                scanned++
                if (b.toByte() == magic[matched]) {
                    matched++
                    if (matched == magic.size) return
                } else {
                    matched = if (b.toByte() == magic[0]) 1 else 0
                }
            }
            throw IOException("Root helper handshake not found within $MAX_BANNER_SKIP_BYTES bytes")
        }
    }
}
