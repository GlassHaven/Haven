package sh.haven.core.ssh

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.exception.SshChannelOpenException
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.channel.ChannelSessionFactory
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Regression contract for #888: when a multiplexed [SshClient] fills the
 * server's per-connection channel cap (`MaxSessions`, 10 in stock OpenSSH),
 * [SshClient.isAlive] must treat the server's authenticated
 * `SSH_MSG_CHANNEL_OPEN_FAILURE` reply as proof that the transport is alive,
 * while still returning `false` when the underlying TCP socket stalls silently.
 */
class JschMaxSessionsIsAliveContractTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private lateinit var server: SshServer
    private var serverPort: Int = 0
    private val clients = CopyOnWriteArrayList<SshClient>()
    private val openShells = CopyOnWriteArrayList<ShellChannel>()
    private var relay: BlackholeRelay? = null

    @Before
    fun setUp() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("maxsess-hostkey", ".ser"))
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            // Reproduce OpenSSH sshd's exact MaxSessions wire behaviour: reject
            // session channel N+1 with SSH_MSG_CHANNEL_OPEN_FAILURE
            // (SSH_OPEN_ADMINISTRATIVELY_PROHIBITED, "no more sessions") while
            // keeping the authenticated transport alive.
            channelFactories = listOf(OpenSshMaxSessionsChannelFactory(MAX_CHANNELS))
            commandFactory = CommandFactory { _, _ -> ImmediateZeroCommand() }
            shellFactory = ShellFactory { HoldOpenShell() }
        }
        server.start()
        serverPort = server.port
    }

    @After
    fun tearDown() {
        openShells.forEach { runCatching { it.disconnect() } }
        openShells.clear()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        relay?.close()
        relay = null
        if (::server.isInitialized) server.stop(true)
    }

    @Test
    fun `isAlive returns true when server rejects probe channel at MaxSessions capacity`(): Unit = runBlocking {
        val client = SshClient().also { clients.add(it) }
        client.connect(
            ConnectionConfig(
                host = "127.0.0.1",
                port = serverPort,
                username = "tester",
                authMethod = ConnectionConfig.AuthMethod.Password("secret"),
            ),
        )

        repeat(MAX_CHANNELS) {
            openShells.add(client.openShellChannel())
        }

        // Gate check: both interactive shell open and one-shot exec surface
        // SshChannelOpenRejectedException (reason 1) so callers can dial an
        // overflow connection rather than treating the transport as broken.
        val shellFailure = runCatching { client.openShellChannel() }.exceptionOrNull()
        assertTrue(
            "expected SshChannelOpenRejectedException once server channel table is full, got $shellFailure",
            shellFailure is SshChannelOpenRejectedException,
        )
        assertEquals(
            SshConstants.SSH_OPEN_ADMINISTRATIVELY_PROHIBITED,
            (shellFailure as SshChannelOpenRejectedException).reasonCode,
        )

        val execFailure = runCatching { client.execCommand("true") }.exceptionOrNull()
        assertTrue(
            "expected execCommand to surface SshChannelOpenRejectedException at capacity, got $execFailure",
            execFailure is SshChannelOpenRejectedException,
        )

        // Foreground liveness probe must still recognise the session as alive.
        assertTrue(
            "isAlive must return true when live sshd rejects probe with SSH_MSG_CHANNEL_OPEN_FAILURE",
            client.isAlive(2_000),
        )

        // Releasing a slot restores normal channel opens on the same connection.
        openShells.removeAt(0).disconnect()
        delay(100)
        assertTrue(client.isAlive(2_000))
        openShells.add(client.openShellChannel())
    }

    @Test
    fun `isAlive returns false when underlying TCP socket stalls silently`(): Unit = runBlocking {
        val proxyRelay = BlackholeRelay(serverPort).also { relay = it }
        val client = SshClient().also { clients.add(it) }
        client.connect(
            ConnectionConfig(
                host = "127.0.0.1",
                port = proxyRelay.listenPort,
                username = "tester",
                authMethod = ConnectionConfig.AuthMethod.Password("secret"),
            ),
        )

        assertTrue("sanity: live relay must pass isAlive", client.isAlive(2_000))

        // Blackhole traffic in both directions without FIN/RST (Doze / dead NAT).
        proxyRelay.forwarding.set(false)

        assertFalse(
            "isAlive must return false when no server reply arrives within timeout",
            client.isAlive(350),
        )
    }

    private companion object {
        const val MAX_CHANNELS = 2

        private class OpenSshMaxSessionsChannelFactory(
            private val maxSessions: Int,
        ) : ChannelSessionFactory() {
            private val openCount = AtomicInteger(0)

            override fun createChannel(session: org.apache.sshd.common.session.Session): Channel {
                return object : ChannelSession() {
                    private val counted = AtomicBoolean(false)

                    override fun doInit(
                        buffer: org.apache.sshd.common.util.buffer.Buffer,
                    ): org.apache.sshd.client.future.OpenFuture {
                        while (true) {
                            val cur = openCount.get()
                            if (cur >= maxSessions) {
                                val err = SshChannelOpenException(
                                    channelId,
                                    SshConstants.SSH_OPEN_ADMINISTRATIVELY_PROHIBITED,
                                    "no more sessions",
                                )
                                signalChannelOpenFailure(err)
                                return org.apache.sshd.client.future.DefaultOpenFuture(this, this).apply {
                                    exception = err
                                }
                            }
                            if (openCount.compareAndSet(cur, cur + 1)) {
                                counted.set(true)
                                break
                            }
                        }
                        return super.doInit(buffer)
                    }

                    override fun handleChannelUnregistration(
                        service: org.apache.sshd.common.session.ConnectionService,
                    ) {
                        if (counted.compareAndSet(true, false)) {
                            openCount.decrementAndGet()
                        }
                        super.handleChannelUnregistration(service)
                    }
                }
            }
        }

        private class ImmediateZeroCommand : Command {
            private var exit: ExitCallback? = null
            override fun setInputStream(inStream: InputStream?) {}
            override fun setOutputStream(out: OutputStream?) {}
            override fun setErrorStream(err: OutputStream?) {}
            override fun setExitCallback(callback: ExitCallback?) { exit = callback }
            override fun start(channel: ChannelSession?, env: Environment?) {
                exit?.onExit(0)
            }
            override fun destroy(channel: ChannelSession?) {}
        }

        private class HoldOpenShell : Command {
            private var input: InputStream? = null
            private var exit: ExitCallback? = null
            @Volatile private var worker: Thread? = null
            override fun setInputStream(inStream: InputStream?) { input = inStream }
            override fun setOutputStream(out: OutputStream?) {}
            override fun setErrorStream(err: OutputStream?) {}
            override fun setExitCallback(callback: ExitCallback?) { exit = callback }
            override fun start(channel: ChannelSession?, env: Environment?) {
                worker = thread(isDaemon = true, name = "hold-open-shell") {
                    try {
                        val buf = ByteArray(64)
                        while ((input?.read(buf) ?: -1) >= 0) { /* hold open */ }
                        exit?.onExit(0)
                    } catch (_: Exception) {
                    }
                }
            }
            override fun destroy(channel: ChannelSession?) { worker?.interrupt() }
        }

        private class BlackholeRelay(private val targetPort: Int) : AutoCloseable {
            private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val listenPort: Int = serverSocket.localPort
            val forwarding = AtomicBoolean(true)
            private val sockets = CopyOnWriteArrayList<Socket>()

            init {
                thread(isDaemon = true, name = "blackhole-relay-accept") {
                    while (!serverSocket.isClosed) {
                        val accepted = try { serverSocket.accept() } catch (_: Exception) { break }
                        val upstream = try { Socket("127.0.0.1", targetPort) } catch (_: Exception) {
                            runCatching { accepted.close() }
                            continue
                        }
                        sockets.add(accepted)
                        sockets.add(upstream)
                        pump(accepted, upstream)
                        pump(upstream, accepted)
                    }
                }
            }

            private fun pump(src: Socket, dst: Socket) {
                thread(isDaemon = true, name = "blackhole-relay-pump") {
                    val buf = ByteArray(4096)
                    try {
                        val input = src.getInputStream()
                        val output = dst.getOutputStream()
                        while (!src.isClosed && !dst.isClosed) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (forwarding.get()) {
                                output.write(buf, 0, n)
                                output.flush()
                            } else {
                                // Silently drop bytes while keeping TCP open.
                                Thread.sleep(20)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            override fun close() {
                runCatching { serverSocket.close() }
                sockets.forEach { runCatching { it.close() } }
                sockets.clear()
            }
        }
    }
}
