package sh.haven.app.agent

import android.net.LocalServerSocket
import android.net.LocalSocketAddress
import android.net.LocalSocket
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * The guest-facing GPS device bridge (`attach_gps_to_guest`): an
 * abstract-namespace [LocalServerSocket] named `haven-gps` that streams the
 * phone's NMEA sentences to any reader that connects.
 *
 * Transport matches the haven-usb standard (UsbProxyServer): abstract
 * namespace, NOT TCP on loopback — a loopback TCP port would be reachable
 * by every co-resident app holding INTERNET, while an abstract socket is
 * only reachable by processes sharing Haven's network namespace (the proot
 * guest, which doesn't isolate the netns). The guest side materialises a
 * real device from the stream with the staged `haven-gps` helper (socat
 * ABSTRACT-CONNECT → PTY at /run/haven/gps0), so Linux consumers — gpsd,
 * gpspipe, anything that reads a character device — "just see a GPS".
 *
 * Wire format: the chipset's NMEA sentences exactly as the
 * [android.location.OnNmeaMessageListener] delivers them, each terminated
 * with CRLF (the listener strips the terminator, and every NMEA consumer
 * expects line-delimited sentences).
 *
 * Fan-out is [NmeaFanOut]: one writer thread per client draining a bounded
 * queue, so a slow reader drops sentences (counted) instead of blocking the
 * GNSS callback thread. Mirrors SntpServer's lifecycle: start() binds,
 * stop() tears everything down; running counters for status.
 */
internal class GpsGuestServer(private val socketName: String = SOCKET_NAME) {

    /** One connected reader: its queue (fed by publish), its writer thread. */
    private class Client(val socket: LocalSocket, val sub: NmeaFanOut.Subscriber, val thread: Thread)

    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private val fan = NmeaFanOut()
    private val clients = java.util.concurrent.CopyOnWriteArrayList<Client>()

    @Volatile private var running = false

    val sentencesTotal = AtomicLong(0)
    val clientsConnectedTotal = AtomicLong(0)

    /** Readers currently attached. */
    val clientCount: Int get() = fan.clientCount
    /** Sentences dropped for at least one reader (queue overflow). */
    val droppedTotal: Long get() = fan.droppedTotal

    fun start() {
        check(!running) { "already running" }
        val ss = LocalServerSocket(socketName)
        server = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "haven-gps-bridge").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "GPS guest bridge on abstract \\0$socketName")
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        acceptThread = null
        for (c in clients) closeClient(c)
        clients.clear()
        // A thread blocked in accept() at the moment of the close above holds
        // the last kernel reference to the listening file, so the abstract
        // name is never freed — observed on-device as EADDRINUSE on every
        // later attach, with the zombie listener still accepting (but never
        // serving) connections. Connect dummy clients so accept() returns,
        // the loop sees !running and exits, and the socket is destroyed.
        Thread({
            repeat(20) {
                try {
                    LocalSocket().use {
                        it.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                    }
                } catch (_: Exception) {}
                runCatching { Thread.sleep(100) }
            }
        }, "haven-gps-bridge-wake").apply { isDaemon = true }.start()
        Log.i(TAG, "GPS guest bridge stopped ($sentencesTotal sentences, $clientsConnectedTotal readers)")
    }

    /** Broadcast one NMEA sentence (from the GNSS callback thread; non-blocking). */
    fun publish(sentence: String) {
        if (!running) return
        val line = if (sentence.endsWith("\r\n")) sentence else sentence.trimEnd('\r', '\n') + "\r\n"
        fan.publish(line)
        sentencesTotal.incrementAndGet()
    }

    private fun acceptLoop(ss: LocalServerSocket) {
        while (running) {
            val sock = try {
                ss.accept()
            } catch (_: IOException) {
                break // stop() closed the server socket
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "guest bridge accept failed: ${e.message}")
                continue
            }
            // stop() may have flipped while we were blocked in accept (the
            // dummy-connection wake) — discard the handshake socket.
            if (!running) {
                try { sock.close() } catch (_: Exception) {}
                break
            }
            val sub = fan.attach()
            val client = Client(sock, sub, Thread(null, { writerLoop(sock, sub) }, "haven-gps-writer"))
            clients.add(client)
            clientsConnectedTotal.incrementAndGet()
            client.thread.isDaemon = true
            client.thread.start()
            Log.i(TAG, "guest bridge reader attached (${fan.clientCount} connected)")
        }
    }

    /** Drain this reader's queue to its socket; exits when the peer goes away. */
    private fun writerLoop(sock: LocalSocket, sub: NmeaFanOut.Subscriber) {
        val out: OutputStream = try {
            sock.outputStream
        } catch (e: Exception) {
            removeClient(sock); return
        }
        try {
            while (running) {
                val sentence = sub.queue.take() // blocking; drop-on-full keeps this bounded
                out.write(sentence.toByteArray(Charsets.US_ASCII))
                out.flush()
            }
        } catch (_: InterruptedException) {
            // stop() — fall through to cleanup
        } catch (_: IOException) {
            // peer closed: normal reader exit
        } catch (e: Exception) {
            Log.w(TAG, "guest bridge writer failed: ${e.message}")
        } finally {
            removeClient(sock)
        }
    }

    private fun removeClient(sock: LocalSocket) {
        clients.firstOrNull { it.socket === sock }?.let { c ->
            fan.detach(c.sub)
            clients.remove(c)
            closeClient(c)
            Log.i(TAG, "guest bridge reader left (${fan.clientCount} connected)")
        }
    }

    private fun closeClient(c: Client) {
        c.thread.interrupt()
        try { c.socket.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "HavenGps"
        /** Abstract-namespace socket name — the guest-side helper connects to this. */
        const val SOCKET_NAME = "haven-gps"
        /** Attach rate: 1 Hz keeps the NMEA stream flowing (the sentences ride
         * the same session; the engine rate is min() across consumers). */
        const val GUEST_SESSION_INTERVAL_MS = 1000
    }
}