package sh.haven.core.ssh

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression suite for #888:
 *  1. Reconnecting a 10-tab shared SSH connection (1 primary + 9 reuse siblings)
 *     must keep siblings in RECONNECTING (never DISCONNECTED) while the old
 *     client tears down, and must open all 10 shell channels BEFORE transitioning
 *     sessions to CONNECTED — otherwise an observer like McpNearCarrier wakes on
 *     the first sibling's CONNECTED emission, runs `recordTunnelSshPid` (`exec`
 *     channel), and steals OpenSSH's 10th `MaxSessions` slot from the 10th tab.
 *  2. `awaitReusableClient` and `getSshClientForProfile` prefer the connected
 *     client with the fewest active shell channels when multiple pooled clients
 *     exist for the same profile.
 */
class SshSessionManagerMaxSessionsReconnectTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private class PipeShell {
        val remoteOut = PipedOutputStream()
        val localIn = PipedInputStream(remoteOut, 4096)
        val connected = AtomicBoolean(true)
        val closed = AtomicBoolean(false)

        fun toChannel(onClose: () -> Unit = {}): ShellChannel = ShellChannel(
            input = localIn,
            output = ByteArrayOutputStream(),
            resizeFn = { _, _ -> },
            disconnectFn = {
                if (closed.compareAndSet(false, true)) {
                    connected.set(false)
                    runCatching { remoteOut.close() }
                    runCatching { localIn.close() }
                    onClose()
                }
            },
            connectedProbe = { connected.get() },
            closedProbe = { closed.get() },
            exitStatusProbe = { -1 }, // network drop = -1 (not clean exit)
        )

        fun simulateTransportDrop() {
            connected.set(false)
            closed.set(true)
            runCatching { remoteOut.close() }
        }
    }

    @Test
    fun `attemptReconnect reattaches all 10 shared sessions without losing slot 10 to CONNECTED observer exec`() = runBlocking {
        val manager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))
        manager.reconnectInitialDelayMs = 10L

        val oldShells = CopyOnWriteArrayList<PipeShell>()
        val oldClient = mockk<SshClient>(relaxed = true)
        every { oldClient.isConnected } returns true
        every { oldClient.openTerminalChannel(any(), any(), any(), any(), any()) } answers {
            PipeShell().also { oldShells.add(it) }.toChannel()
        }
        every { oldClient.disconnect() } answers {
            // Mirror real JSch: disconnecting previousClient drops all 10 old shell streams -> EOF.
            oldShells.forEach { it.simulateTransportDrop() }
        }

        val activeChannelsOnNewClient = AtomicInteger(0)
        val maxConcurrentChannels = 10
        val newShells = CopyOnWriteArrayList<PipeShell>()
        val newClient = mockk<SshClient>(relaxed = true)
        every { newClient.isConnected } returns true
        every { newClient.connectBlocking(any(), any(), any(), any(), any(), any(), any()) } returns null
        every { newClient.openTerminalChannel(any(), any(), any(), any(), any()) } answers {
            val now = activeChannelsOnNewClient.incrementAndGet()
            if (now > maxConcurrentChannels) {
                activeChannelsOnNewClient.decrementAndGet()
                throw RuntimeException("channel is not opened.")
            }
            // Simulate realistic ~5ms SSH channel-open RTT so an early CONNECTED observer races mid-loop.
            Thread.sleep(8)
            val ps = PipeShell().also { newShells.add(it) }
            ps.toChannel(onClose = { activeChannelsOnNewClient.decrementAndGet() })
        }
        coEvery { newClient.execCommand(any(), any()) } coAnswers {
            val now = activeChannelsOnNewClient.incrementAndGet()
            try {
                if (now > maxConcurrentChannels) {
                    throw RuntimeException("channel is not opened.")
                }
                delay(80)
                ExecResult(exitStatus = 0, stdout = "", stderr = "")
            } finally {
                activeChannelsOnNewClient.decrementAndGet()
            }
        }
        manager.reconnectClientFactory = { newClient }

        // Register 1 primary + 9 connection-reuse siblings (= 10 tmux tabs, last is "haven-2").
        val primaryId = manager.registerSession("p1", "msi-z790", oldClient)
        manager.storeConnectionConfig(
            primaryId,
            ConnectionConfig(
                host = "192.168.0.180",
                port = 22,
                username = "ian",
                reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = true),
            ),
            SessionManager.TMUX,
        )
        manager.setChosenSessionName(primaryId, "haven")
        manager.openShellForSession(primaryId)
        manager.createTerminalSession(primaryId, { _, _, _ -> })!!.start()
        manager.updateStatus(primaryId, SshSessionManager.SessionState.Status.CONNECTED)

        val siblingNames = listOf("fieldsmith", "gl-inet", "cnc", "mutiny", "sdr", "meteor", "meta", "watch", "haven-2")
        val siblingIds = siblingNames.map { name ->
            val sid = manager.registerSession("p1", "haven", oldClient)
            manager.storeReuseConfig(sid, "p1", SessionManager.TMUX)
            manager.setChosenSessionName(sid, name)
            manager.openShellForSession(sid)
            manager.createTerminalSession(sid, { _, _, _ -> })!!.start()
            manager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)
            sid
        }

        // Observe session emissions just like TerminalViewModel + McpNearCarrier:
        //  - record if any sibling ever emits DISCONNECTED during reconnect,
        //  - apply the self-healing MCP reverse forward as soon as any interactive
        //    session for "p1" transitions to CONNECTED on newClient.
        val siblingDisconnectedObserved = Collections.synchronizedSet(mutableSetOf<String>())
        val forwardAppliedOnNewClient = AtomicBoolean(false)
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val observerReady = java.util.concurrent.CountDownLatch(1)
        observerScope.launch {
            manager.sessions.collect { map ->
                observerReady.countDown()
                for (sid in siblingIds) {
                    if (map[sid]?.status == SshSessionManager.SessionState.Status.DISCONNECTED) {
                        siblingDisconnectedObserved.add(sid)
                    }
                }
                val connectedOnNew = map.values.firstOrNull {
                    it.profileId == "p1" &&
                        !it.headless &&
                        it.client === newClient &&
                        it.status == SshSessionManager.SessionState.Status.CONNECTED
                }
                if (connectedOnNew != null && forwardAppliedOnNewClient.compareAndSet(false, true)) {
                    manager.applyPortForwards(
                        connectedOnNew.sessionId,
                        listOf(
                            SshSessionManager.PortForwardInfo(
                                ruleId = "mcp-reverse-tunnel",
                                type = SshSessionManager.PortForwardType.REMOTE,
                                bindAddress = "127.0.0.1",
                                bindPort = 8730,
                                targetHost = "127.0.0.1",
                                targetPort = 8740,
                                critical = false,
                                selfHealOnBindFailure = true,
                            ),
                        ),
                    )
                }
            }
        }
        assertTrue(observerReady.await(2, java.util.concurrent.TimeUnit.SECONDS))

        try {
            // Trigger reconnect on the primary session (e.g. genuine network roam).
            manager.updateStatus(primaryId, SshSessionManager.SessionState.Status.DISCONNECTED)
            manager.requestReconnect(primaryId)

            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                val pStatus = manager.getSession(primaryId)?.status
                val pClient = manager.getSession(primaryId)?.client
                if (pStatus == SshSessionManager.SessionState.Status.CONNECTED && pClient === newClient) {
                    break
                }
                delay(20)
            }
            // Allow any straggler reader-EOF callbacks to settle.
            delay(150)

            val allIds = listOf(primaryId) + siblingIds
            for (id in allIds) {
                val state = manager.getSession(id)!!
                assertEquals(
                    "session ${state.chosenSessionName} ($id) must be CONNECTED after reconnect",
                    SshSessionManager.SessionState.Status.CONNECTED,
                    state.status,
                )
                assertSame("session ${state.chosenSessionName} must ride newClient", newClient, state.client)
            }
            assertTrue(
                "siblings must stay RECONNECTING (never DISCONNECTED) during primary reconnect, got DISCONNECTED for: $siblingDisconnectedObserved",
                siblingDisconnectedObserved.isEmpty(),
            )
        } finally {
            observerScope.cancel()
            manager.disconnectAll()
        }
    }

    @Test
    fun `awaitReusableClient and getSshClientForProfile prefer connected client with fewest active shells`() = runBlocking {
        val manager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))
        val saturatedClient = mockk<SshClient>(relaxed = true)
        val lightlyLoadedClient = mockk<SshClient>(relaxed = true)

        repeat(10) { i ->
            val sid = manager.registerSession("p1", "tab-$i", saturatedClient)
            manager.attachShellChannel(sid, PipeShell().toChannel())
            manager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)
        }
        val overflowSid = manager.registerSession("p1", "tab-10", lightlyLoadedClient)
        manager.attachShellChannel(overflowSid, PipeShell().toChannel())
        manager.updateStatus(overflowSid, SshSessionManager.SessionState.Status.CONNECTED)

        assertSame(
            "awaitReusableClient must pick the less-loaded client rather than saturated firstOrNull",
            lightlyLoadedClient,
            manager.awaitReusableClient("p1", timeoutMs = 200),
        )
        assertSame(
            "getSshClientForProfile must pick the less-loaded client so exec/SFTP have channel headroom",
            lightlyLoadedClient,
            manager.getSshClientForProfile("p1"),
        )
        assertFalse(saturatedClient === lightlyLoadedClient)
    }

    @Test
    fun `attemptReconnect emits UserMessageBus error naming dropped tab when sibling hits MaxSessions on FIDO profile`(): Unit = runBlocking {
        val messageBus = sh.haven.core.data.message.UserMessageBus()
        val manager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true), messageBus)
        manager.reconnectInitialDelayMs = 10L

        val oldShells = CopyOnWriteArrayList<PipeShell>()
        val oldClient = mockk<SshClient>(relaxed = true)
        every { oldClient.isConnected } returns true
        every { oldClient.openTerminalChannel(any(), any(), any(), any(), any()) } answers {
            PipeShell().also { oldShells.add(it) }.toChannel()
        }
        every { oldClient.disconnect() } answers {
            oldShells.forEach { it.simulateTransportDrop() }
        }

        val activeOnNew = AtomicInteger(0)
        val newClient = mockk<SshClient>(relaxed = true)
        every { newClient.isConnected } returns true
        every { newClient.connectBlocking(any(), any(), any(), any(), any(), any(), any()) } returns null
        every { newClient.openTerminalChannel(any(), any(), any(), any(), any()) } answers {
            val n = activeOnNew.incrementAndGet()
            if (n > 2) {
                activeOnNew.decrementAndGet()
                throw SshChannelOpenRejectedException(1)
            }
            PipeShell().toChannel(onClose = { activeOnNew.decrementAndGet() })
        }
        var factoryCalls = 0
        manager.reconnectClientFactory = {
            factoryCalls++
            newClient
        }

        val emittedMessages = CopyOnWriteArrayList<sh.haven.core.data.message.UserMessage>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collectorScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            messageBus.messages.collect { emittedMessages.add(it) }
        }

        val primaryId = manager.registerSession("p1", "msi-z790", oldClient)
        manager.storeConnectionConfig(
            primaryId,
            ConnectionConfig(
                host = "192.168.0.180",
                port = 22,
                username = "ian",
                authMethod = ConnectionConfig.AuthMethod.FidoKey(skKeyData = byteArrayOf(1, 2, 3)),
                reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = true),
            ),
            SessionManager.TMUX,
        )
        manager.setChosenSessionName(primaryId, "haven")
        manager.openShellForSession(primaryId)
        manager.createTerminalSession(primaryId, { _, _, _ -> })!!.start()
        manager.updateStatus(primaryId, SshSessionManager.SessionState.Status.CONNECTED)

        val siblingIds = listOf("cnc", "haven-2").map { name ->
            val sid = manager.registerSession("p1", "msi-z790", oldClient)
            manager.storeReuseConfig(sid, "p1", SessionManager.TMUX)
            manager.setChosenSessionName(sid, name)
            manager.openShellForSession(sid)
            manager.createTerminalSession(sid, { _, _, _ -> })!!.start()
            manager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)
            sid
        }

        try {
            manager.updateStatus(primaryId, SshSessionManager.SessionState.Status.DISCONNECTED)
            manager.requestReconnect(primaryId)

            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                if (manager.getSession(primaryId)?.status == SshSessionManager.SessionState.Status.CONNECTED &&
                    manager.getSession(primaryId)?.client === newClient
                ) {
                    break
                }
                delay(20)
            }

            assertEquals(1, factoryCalls)
            assertEquals(SshSessionManager.SessionState.Status.CONNECTED, manager.getSession(primaryId)?.status)
            assertEquals(SshSessionManager.SessionState.Status.CONNECTED, manager.getSession(siblingIds[0])?.status)
            assertEquals(SshSessionManager.SessionState.Status.DISCONNECTED, manager.getSession(siblingIds[1])?.status)
            assertEquals(1, emittedMessages.size)
            val text = emittedMessages.single().text
            assertTrue("expected tab name in user error, got: $text", text.contains("'haven-2'"))
            assertTrue("expected MaxSessions explanation in user error, got: $text", text.contains("MaxSessions"))
        } finally {
            collectorScope.cancel()
            manager.disconnectAll()
        }
    }

    @Test
    fun `attemptReconnect dials overflow client when server MaxSessions shrank on non-FIDO profile`(): Unit = runBlocking {
        val messageBus = sh.haven.core.data.message.UserMessageBus()
        val manager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true), messageBus)
        manager.reconnectInitialDelayMs = 10L

        val oldShells = CopyOnWriteArrayList<PipeShell>()
        val oldClient = mockk<SshClient>(relaxed = true)
        every { oldClient.isConnected } returns true
        every { oldClient.openTerminalChannel(any(), any(), any(), any(), any()) } answers {
            PipeShell().also { oldShells.add(it) }.toChannel()
        }
        every { oldClient.disconnect() } answers {
            oldShells.forEach { it.simulateTransportDrop() }
        }

        fun makeCappedClient(cap: Int): SshClient {
            val count = AtomicInteger(0)
            return mockk<SshClient>(relaxed = true) {
                every { isConnected } returns true
                every { connectBlocking(any(), any(), any(), any(), any(), any(), any()) } returns null
                every { openTerminalChannel(any(), any(), any(), any(), any()) } answers {
                    val n = count.incrementAndGet()
                    if (n > cap) {
                        count.decrementAndGet()
                        throw SshChannelOpenRejectedException(1)
                    }
                    PipeShell().toChannel(onClose = { count.decrementAndGet() })
                }
            }
        }

        val newClient1 = makeCappedClient(cap = 2)
        val newClient2 = makeCappedClient(cap = 2)
        val clients = ArrayDeque(listOf(newClient1, newClient2))
        manager.reconnectClientFactory = { clients.removeFirst() }

        val emittedMessages = CopyOnWriteArrayList<sh.haven.core.data.message.UserMessage>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collectorScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            messageBus.messages.collect { emittedMessages.add(it) }
        }

        val primaryId = manager.registerSession("p1", "msi-z790", oldClient)
        manager.storeConnectionConfig(
            primaryId,
            ConnectionConfig(
                host = "192.168.0.180",
                port = 22,
                username = "ian",
                authMethod = ConnectionConfig.AuthMethod.Password("secret"),
                reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = true),
            ),
            SessionManager.TMUX,
        )
        manager.setChosenSessionName(primaryId, "haven")
        manager.openShellForSession(primaryId)
        manager.createTerminalSession(primaryId, { _, _, _ -> })!!.start()
        manager.updateStatus(primaryId, SshSessionManager.SessionState.Status.CONNECTED)

        val siblingIds = listOf("cnc", "haven-2").map { name ->
            val sid = manager.registerSession("p1", "msi-z790", oldClient)
            manager.storeReuseConfig(sid, "p1", SessionManager.TMUX)
            manager.setChosenSessionName(sid, name)
            manager.openShellForSession(sid)
            manager.createTerminalSession(sid, { _, _, _ -> })!!.start()
            manager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)
            sid
        }

        try {
            manager.updateStatus(primaryId, SshSessionManager.SessionState.Status.DISCONNECTED)
            manager.requestReconnect(primaryId)

            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                if (manager.getSession(primaryId)?.status == SshSessionManager.SessionState.Status.CONNECTED &&
                    manager.getSession(primaryId)?.client === newClient1
                ) {
                    break
                }
                delay(20)
            }

            assertEquals(SshSessionManager.SessionState.Status.CONNECTED, manager.getSession(primaryId)?.status)
            assertEquals(SshSessionManager.SessionState.Status.CONNECTED, manager.getSession(siblingIds[0])?.status)
            assertEquals(SshSessionManager.SessionState.Status.CONNECTED, manager.getSession(siblingIds[1])?.status)
            assertSame(newClient1, manager.getSession(primaryId)?.client)
            assertSame(newClient1, manager.getSession(siblingIds[0])?.client)
            assertSame(newClient2, manager.getSession(siblingIds[1])?.client)
            assertTrue(
                "first sibling moved onto overflow client must own auto-reconnect for that client",
                manager.getSession(siblingIds[1])?.connectionConfig?.reconnectPolicy?.autoReconnect == true,
            )
            assertTrue("expected zero user error messages when overflow succeeded, got: $emittedMessages", emittedMessages.isEmpty())
        } finally {
            collectorScope.cancel()
            manager.disconnectAll()
        }
    }
}
