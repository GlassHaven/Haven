package sh.haven.feature.sftp

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "SftpStreamServer"

/**
 * Loopback HTTP server that exposes SFTP files to ffmpeg so it can read
 * them with Range requests rather than having the caller download the
 * whole file to cache first.
 *
 * Callers register one file at a time via [publish], passing a per-file
 * [Opener] that knows how to re-open an InputStream at a given byte
 * offset on whatever `ChannelSftp` they control. This keeps the server
 * independent of any specific session manager or profile lookup and lets
 * both the SFTP browser ViewModel and the MCP agent use the same server.
 *
 * SFTP channels are not safe to read from concurrently, so all body
 * writes are serialized on an internal lock. That's acceptable for
 * single-stream playback: ffmpeg issues one Range request at a time per
 * input.
 */
@Singleton
class SftpStreamServer @Inject constructor() : Closeable {

    fun interface Opener {
        @Throws(Exception::class)
        fun open(offset: Long): InputStream
    }

    private data class Entry(
        val path: String,
        val size: Long,
        val contentType: String,
        val opener: Opener,
    )

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val entries = ConcurrentHashMap<String, Entry>()
    private val channelLock = Any()
    private val tokenBytes = ByteArray(32)

    @Volatile
    var port: Int = 0
        private set

    /**
     * Per-server-start random token. Required as the first URL path
     * segment on every request — without it, requests get 404. Prevents
     * other apps on the device from reading SFTP files via the loopback
     * port (the port itself is enumerable, the token is not).
     */
    @Volatile
    private var token: String = ""

    @Volatile
    private var running: Boolean = false

    /** Start the loopback server. Returns the bound port. Idempotent. */
    fun start(preferredPort: Int = 0): Int {
        if (running) return port
        SecureRandom().nextBytes(tokenBytes)
        token = encodeTokenUrlSafe(tokenBytes)
        val ss = ServerSocket(preferredPort, 4, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        running = true
        serverThread = thread(name = "sftp-stream-http", isDaemon = true) {
            Log.i(TAG, "listening on 127.0.0.1:$port")
            while (running) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                thread(name = "sftp-stream-client", isDaemon = true) { handle(client) }
            }
        }
        return port
    }

    /**
     * Register a file and return the absolute URL path (beginning with
     * `/`) to use. The path includes the per-server-start auth token —
     * callers concatenate it onto `http://127.0.0.1:$port` to form the
     * full URL. Re-publishing the same file path replaces the previous
     * opener so a caller can point ffmpeg at the same URL with a fresh
     * channel after reconnecting.
     */
    fun publish(
        path: String,
        size: Long,
        contentType: String = "application/octet-stream",
        opener: Opener,
    ): String {
        val key = path.trimStart('/').split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        entries[key] = Entry(path = path, size = size, contentType = contentType, opener = opener)
        return "/$token/$key"
    }

    override fun close() = stop()

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        entries.clear()
        token = ""
        tokenBytes.fill(0)
        port = 0
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val reader = s.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                val out = s.getOutputStream()
                val pathOnly = target.substringBefore('?').removePrefix("/")
                val slash = pathOnly.indexOf('/')
                if (slash <= 0) {
                    writeStatus(out, 404, "Not Found")
                    return
                }
                val receivedToken = pathOnly.substring(0, slash)
                val key = pathOnly.substring(slash + 1)
                val expected = token
                if (expected.isEmpty() || !constantTimeEquals(receivedToken, expected)) {
                    writeStatus(out, 404, "Not Found")
                    return
                }
                val entry = entries[key]
                if (entry == null) {
                    writeStatus(out, 404, "Not Found")
                    return
                }

                val size = entry.size
                var start = 0L
                var end = size - 1
                var isPartial = false
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val spec = rangeHeader.removePrefix("bytes=")
                    val dash = spec.indexOf('-')
                    if (dash >= 0) {
                        val a = spec.substring(0, dash)
                        val b = spec.substring(dash + 1)
                        if (a.isNotEmpty()) {
                            start = a.toLongOrNull() ?: 0L
                            if (b.isNotEmpty()) {
                                end = (b.toLongOrNull() ?: end).coerceAtMost(size - 1)
                            }
                        } else if (b.isNotEmpty()) {
                            val n = b.toLongOrNull() ?: 0L
                            start = (size - n).coerceAtLeast(0L)
                        }
                        isPartial = true
                    }
                }
                if (start < 0 || start >= size || end < start) {
                    val hdr = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */$size\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(hdr.toByteArray())
                    out.flush()
                    return
                }

                val length = end - start + 1
                val hdr = buildString {
                    if (isPartial) {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Range: bytes $start-$end/$size\r\n")
                    } else {
                        append("HTTP/1.1 200 OK\r\n")
                    }
                    append("Content-Type: ${entry.contentType}\r\n")
                    append("Content-Length: $length\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(hdr.toByteArray())
                if (method.equals("HEAD", ignoreCase = true)) {
                    out.flush()
                    return
                }

                synchronized(channelLock) {
                    val stream = try {
                        entry.opener.open(start)
                    } catch (e: Exception) {
                        Log.e(TAG, "open ${entry.path}@$start failed", e)
                        return
                    }
                    stream.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        var remaining = length
                        while (remaining > 0) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            val n = inp.read(buf, 0, want)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                        out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "client ended: ${e.message}")
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        val resp = "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(resp.toByteArray())
        out.flush()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeTokenUrlSafe(bytes: ByteArray): String =
        Base64.UrlSafe.encode(bytes).trimEnd('=')

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
}
