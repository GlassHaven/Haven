package sh.haven.core.ssh

import com.jcraft.jsch.JSchException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * JSch socket factory that binds the outgoing socket to a local address
 * before dialing — ssh -b on a connection profile (#636).
 *
 * When a socket factory is set (and no proxy is configured), JSch calls
 * [com.jcraft.jsch.SocketFactory.createSocket] and only sets TcpNoDelay on
 * the result — the session's connect timeout is NOT applied to the socket
 * (that is the job of [com.jcraft.jsch.Util.createSocket], the no-factory
 * path). So the timeout lives here: a factory dial that never completes must
 * fail within [connectTimeoutMs] by throwing, not hang.
 *
 * Bind failures surface as [JSchException] with their cause so a mistyped or
 * non-local address reads as "bind failed" instead of a generic connect
 * error. BindException from an unroutable remote can also surface through
 * connect on some stacks — the message names the bind address either way.
 */
internal class BoundSocketFactory(
    private val bindAddress: String,
    private val connectTimeoutMs: Int,
) : com.jcraft.jsch.SocketFactory {

    /** Resolved once; [InetAddress.getByName] on a literal never touches DNS. */
    private val bind: InetAddress = InetAddress.getByName(bindAddress)

    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            socket.bind(InetSocketAddress(bind, 0))
        } catch (e: BindException) {
            throw JSchException("bind failed: cannot bind to local address $bindAddress", e)
        }
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: java.io.IOException) {
                // Best effort — the primary error below matters.
            }
            throw when (e) {
                is SocketTimeoutException ->
                    JSchException("timeout: socket is not established ($bindAddress → $host:$port)", e)
                is BindException ->
                    JSchException("bind failed: cannot connect from local address $bindAddress to $host:$port", e)
                else -> JSchException(e.message ?: e.javaClass.simpleName, e)
            }
        }
        return socket
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}