package sh.haven.core.local.guestnotify

import android.net.LocalServerSocket
import android.net.LocalSocketAddress
import android.net.LocalSocket
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * The guest-facing notification bridge (#851, command-first): an
 * abstract-namespace [LocalServerSocket] named `haven-notify` that reads a
 * `haven-notify` request from any guest process that connects, posts an Android
 * notification via [GuestNotifier], and writes back a one-line JSON ack.
 *
 * Direction is the reverse of [sh.haven.app.agent.GpsGuestServer] (guest writes,
 * host reads), so the per-connection shape follows `UsbProxyServer.serve()`
 * rather than the GPS fan-out. Transport matches the haven-usb/haven-gps
 * standard: abstract namespace, NOT TCP loopback — only processes sharing
 * Haven's network namespace (the proot guest) can reach it, not co-resident
 * apps holding INTERNET.
 *
 * One request per connection: read until the peer half-closes (socat does this
 * on stdin EOF), parse, rate-limit, post, ack, close. A socket read timeout
 * bounds a stalled client so it cannot hold a reader thread forever.
 *
 * Lifecycle mirrors GpsGuestServer including the abstract-name-leak wake trick:
 * a thread blocked in accept() holds the last kernel reference to the listening
 * socket, so closing it alone never frees the name (EADDRINUSE on the next
 * start). stop() connects dummy clients so accept() returns and the loop exits.
 */
internal class NotifyGuestServer(
    private val notifier: GuestNotifier,
    private val limiter: NotifyRateLimiter = NotifyRateLimiter(),
    private val socketName: String = GuestNotifyBridge.SOCKET_NAME,
) {
    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile private var running = false

    val postedTotal = AtomicLong(0)
    val rateLimitedTotal = AtomicLong(0)
    val errorsTotal = AtomicLong(0)
    val clientsConnectedTotal = AtomicLong(0)

    fun start() {
        check(!running) { "already running" }
        val ss = LocalServerSocket(socketName)
        server = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "haven-notify-bridge").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "notify guest bridge on abstract \\0$socketName")
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        acceptThread = null
        // Free the abstract name: wake the blocked accept() with dummy clients
        // so the loop sees !running and exits (GpsGuestServer.stop idiom).
        Thread({
            repeat(20) {
                try {
                    LocalSocket().use {
                        it.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                    }
                } catch (_: Exception) {}
                runCatching { Thread.sleep(100) }
            }
        }, "haven-notify-bridge-wake").apply { isDaemon = true }.start()
        Log.i(TAG, "notify guest bridge stopped ($postedTotal posted, $rateLimitedTotal rate-limited, $errorsTotal errors)")
    }

    private fun acceptLoop(ss: LocalServerSocket) {
        while (running) {
            val sock = try {
                ss.accept()
            } catch (_: IOException) {
                break // stop() closed the server socket
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "notify bridge accept failed: ${e.message}")
                continue
            }
            if (!running) {
                try { sock.close() } catch (_: Exception) {}
                break
            }
            clientsConnectedTotal.incrementAndGet()
            Thread(null, { serve(sock) }, "haven-notify-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(sock: LocalSocket) {
        sock.use { s ->
            runCatching { s.soTimeout = READ_TIMEOUT_MS }
            val ack = try {
                val req = NotifyGuestProtocol.parseRequest(readRequest(s.inputStream))
                if (!limiter.tryAcquire()) {
                    rateLimitedTotal.incrementAndGet()
                    NotifyGuestProtocol.encodeError("rate limited")
                } else {
                    when (val r = notifier.post(req.title, req.body)) {
                        is GuestNotifier.PostResult.Posted -> {
                            postedTotal.incrementAndGet()
                            NotifyGuestProtocol.encodeAck(r.id)
                        }
                        GuestNotifier.PostResult.Disabled -> {
                            errorsTotal.incrementAndGet()
                            NotifyGuestProtocol.encodeError("notifications are disabled for Haven — grant POST_NOTIFICATIONS in system settings")
                        }
                        is GuestNotifier.PostResult.Failed -> {
                            errorsTotal.incrementAndGet()
                            NotifyGuestProtocol.encodeError(r.error)
                        }
                    }
                }
            } catch (e: NotifyGuestProtocol.ProtocolException) {
                errorsTotal.incrementAndGet()
                NotifyGuestProtocol.encodeError(e.message ?: "bad request")
            } catch (e: Exception) {
                errorsTotal.incrementAndGet()
                NotifyGuestProtocol.encodeError("could not read request: ${e.message ?: "io error"}")
            }
            try {
                s.outputStream.write(ack.toByteArray(Charsets.UTF_8))
                s.outputStream.flush()
            } catch (_: Exception) {
                // peer went away before reading the ack; nothing to do
            }
        }
    }

    /** Read request bytes until peer half-close, capped at [NotifyGuestProtocol.MAX_REQUEST]. */
    private fun readRequest(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > NotifyGuestProtocol.MAX_REQUEST) {
                throw NotifyGuestProtocol.ProtocolException("request too large (max ${NotifyGuestProtocol.MAX_REQUEST} bytes)")
            }
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    companion object {
        private const val TAG = "HavenNotify"
        private const val READ_TIMEOUT_MS = 5_000
    }
}
