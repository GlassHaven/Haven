package sh.haven.core.tunnel

import java.io.InputStream
import java.io.OutputStream

/**
 * A userspace network tunnel. Implementations can be backed by WireGuard,
 * Tailscale (follow-up), or any other backend that can produce a
 * [TunneledConnection] for a `(host, port)` pair.
 *
 * Tunnels are expected to be reference-counted by a [TunnelManager] — the
 * same tunnel handle may be used by multiple in-flight connections.
 */
interface Tunnel {
    /**
     * Dial a host through this tunnel. Must not block indefinitely — obey
     * [timeoutMs] and throw on timeout. The returned [TunneledConnection] is
     * owned by the caller and must be closed when done.
     */
    fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection

    /**
     * Tear down the tunnel. All outstanding connections are invalidated.
     * Idempotent.
     */
    fun close()
}

/**
 * A byte-stream through a [Tunnel]. Mirrors [java.net.Socket] but without
 * exposing a real [java.net.Socket] — our WireGuard implementation routes
 * data through an in-process userspace netstack, not the kernel socket
 * layer, so `getSocket()` on the JSch side returns null.
 */
interface TunneledConnection {
    val inputStream: InputStream
    val outputStream: OutputStream

    /** Close the connection. Idempotent. */
    fun close()
}
