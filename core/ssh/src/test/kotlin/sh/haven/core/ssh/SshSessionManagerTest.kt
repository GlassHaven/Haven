package sh.haven.core.ssh

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SshSessionManagerTest {

    private lateinit var manager: SshSessionManager

    @Before
    fun setUp() {
        manager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `initially has no sessions`() {
        assertTrue(manager.sessions.value.isEmpty())
        assertFalse(manager.hasActiveSessions)
    }

    @Test
    fun `registerSession adds session with CONNECTING status and returns sessionId`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "My Server", client)

        assertNotNull(sessionId)
        val session = manager.getSession(sessionId)
        assertNotNull(session)
        assertEquals("My Server", session!!.label)
        assertEquals("profile1", session.profileId)
        assertEquals(sessionId, session.sessionId)
        assertEquals(SshSessionManager.SessionState.Status.CONNECTING, session.status)
    }

    @Test
    fun `updateStatus changes session status`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)

        val session = manager.getSession(sessionId)
        assertEquals(SshSessionManager.SessionState.Status.CONNECTED, session!!.status)
    }

    @Test
    fun `updateStatus for non-existent session is no-op`() {
        manager.updateStatus("nonexistent", SshSessionManager.SessionState.Status.CONNECTED)
        assertNull(manager.getSession("nonexistent"))
    }

    @Test
    fun `activeSessions returns only CONNECTING and CONNECTED`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val c3 = mockk<SshClient>(relaxed = true)

        val s1 = manager.registerSession("p1", "S1", c1)
        val s2 = manager.registerSession("p2", "S2", c2)
        val s3 = manager.registerSession("p3", "S3", c3)

        manager.updateStatus(s1, SshSessionManager.SessionState.Status.CONNECTED)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.DISCONNECTED)
        // s3 remains CONNECTING

        val active = manager.activeSessions
        assertEquals(2, active.size)
        assertTrue(active.any { it.sessionId == s1 })
        assertTrue(active.any { it.sessionId == s3 })
    }

    @Test
    fun `removeSession disconnects client and removes from map`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.removeSession(sessionId)

        // State is cleared synchronously
        assertNull(manager.getSession(sessionId))
        assertFalse(manager.hasActiveSessions)

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { client.disconnect() }
    }

    @Test
    fun `removeSession for non-existent session is safe`() {
        manager.removeSession("nonexistent")
        // No exception
    }

    @Test
    fun `disconnectAll clears all sessions`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        manager.registerSession("p1", "S1", c1)
        manager.registerSession("p2", "S2", c2)

        manager.disconnectAll()

        // State is cleared synchronously
        assertTrue(manager.sessions.value.isEmpty())

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { c1.disconnect() }
        verify { c2.disconnect() }
    }

    @Test
    fun `hasActiveSessions returns true when connected sessions exist`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)

        assertTrue(manager.hasActiveSessions)
    }

    @Test
    fun `hasActiveSessions returns false when all disconnected`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.updateStatus(sessionId, SshSessionManager.SessionState.Status.DISCONNECTED)

        assertFalse(manager.hasActiveSessions)
    }

    @Test
    fun `attachShellChannel stores channel in session state`() {
        val client = mockk<SshClient>(relaxed = true)
        val channel = ShellChannel(
            input = java.io.ByteArrayInputStream(ByteArray(0)),
            output = java.io.ByteArrayOutputStream(),
            resizeFn = { _, _ -> },
            disconnectFn = { },
            connectedProbe = { true },
            closedProbe = { false },
            exitStatusProbe = { -1 },
        )
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.attachShellChannel(sessionId, channel)

        val session = manager.getSession(sessionId)
        assertNotNull(session?.shellChannel)
        assertEquals(channel, session?.shellChannel)
    }

    @Test
    fun `attachTerminalSession stores terminal session in state`() {
        val client = mockk<SshClient>(relaxed = true)
        val terminalSession = mockk<TerminalSession>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.attachTerminalSession(sessionId, terminalSession)

        val session = manager.getSession(sessionId)
        assertNotNull(session?.terminalSession)
    }

    @Test
    fun `removeSession closes terminal session`() {
        val client = mockk<SshClient>(relaxed = true)
        val terminalSession = mockk<TerminalSession>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.attachTerminalSession(sessionId, terminalSession)
        manager.removeSession(sessionId)

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { terminalSession.close() }
        verify { client.disconnect() }
    }

    @Test
    fun `disconnectAll closes all terminal sessions`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val t1 = mockk<TerminalSession>(relaxed = true)
        val s1 = manager.registerSession("p1", "S1", c1)
        manager.registerSession("p2", "S2", c2)
        manager.attachTerminalSession(s1, t1)

        manager.disconnectAll()

        // State is cleared synchronously
        assertTrue(manager.sessions.value.isEmpty())

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { t1.close() }
        verify { c1.disconnect() }
        verify { c2.disconnect() }
    }

    @Test
    fun `multiple sessions for same profile`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("profile1", "Server", c1)
        val s2 = manager.registerSession("profile1", "Server", c2)

        // Two distinct sessions for same profile
        assertTrue(s1 != s2)
        assertEquals(2, manager.sessions.value.size)

        val forProfile = manager.getSessionsForProfile("profile1")
        assertEquals(2, forProfile.size)
    }

    @Test
    fun `isProfileConnected returns true when any session is connected`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("profile1", "Server", c1)
        val s2 = manager.registerSession("profile1", "Server", c2)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.DISCONNECTED)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

        assertTrue(manager.isProfileConnected("profile1"))
    }

    @Test
    fun `getProfileStatus returns best status`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("profile1", "Server", c1)
        val s2 = manager.registerSession("profile1", "Server", c2)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.RECONNECTING)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

        assertEquals(SshSessionManager.SessionState.Status.CONNECTED, manager.getProfileStatus("profile1"))
    }

    @Test
    fun `removeAllSessionsForProfile removes all sessions for that profile`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val c3 = mockk<SshClient>(relaxed = true)
        manager.registerSession("profile1", "Server", c1)
        manager.registerSession("profile1", "Server", c2)
        val s3 = manager.registerSession("profile2", "Other", c3)

        manager.removeAllSessionsForProfile("profile1")

        assertEquals(1, manager.sessions.value.size)
        assertNotNull(manager.getSession(s3))

        Thread.sleep(200)
        verify { c1.disconnect() }
        verify { c2.disconnect() }
    }

    // ----- Tunnel-dependent tracking (#121, KoriKraut) -----

    @Test
    fun `markTunnelOpened sets flag and seeds dependent set`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")

        val s = manager.getSession(sshSid)!!
        assertTrue(s.tunnelOpened)
        assertEquals(setOf("vnc-1"), s.tunnelDependents)
    }

    @Test
    fun `releaseTunnelDependent tears down session opened solely as a tunnel`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")

        val torn = manager.releaseTunnelDependent("vnc-1")

        assertEquals(listOf(sshSid), torn)
        assertNull(manager.getSession(sshSid))
        Thread.sleep(200)
        verify { c.disconnect() }
    }

    @Test
    fun `releaseTunnelDependent keeps session alive when other dependents remain`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        manager.attachTunnelDependent(sshSid, "rdp-1")

        // First dependent released — second still depends on it
        val torn = manager.releaseTunnelDependent("vnc-1")
        assertTrue(torn.isEmpty())
        assertNotNull(manager.getSession(sshSid))
        assertEquals(setOf("rdp-1"), manager.getSession(sshSid)!!.tunnelDependents)

        // Last dependent released — now torn down
        val torn2 = manager.releaseTunnelDependent("rdp-1")
        assertEquals(listOf(sshSid), torn2)
        assertNull(manager.getSession(sshSid))
    }

    @Test
    fun `releaseTunnelDependent does not tear down user-opened session that VNC reused`() {
        // Scenario: user opens SSH terminal (tunnelOpened = false), then opens
        // VNC that REUSES the same SSH session (attachTunnelDependent). On
        // VNC disconnect the user's terminal session must not be killed.
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        // No markTunnelOpened — opened directly by user
        manager.attachTunnelDependent(sshSid, "vnc-1")

        val torn = manager.releaseTunnelDependent("vnc-1")
        assertTrue("user-opened SSH must survive VNC disconnect", torn.isEmpty())
        assertNotNull(manager.getSession(sshSid))
        assertEquals(emptySet<String>(), manager.getSession(sshSid)!!.tunnelDependents)
    }

    @Test
    fun `releaseTunnelDependent for unknown profile is no-op`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")

        val torn = manager.releaseTunnelDependent("nobody-knows")
        assertTrue(torn.isEmpty())
        assertNotNull(manager.getSession(sshSid))
    }

    // ----- Tunnel leases: parent->child cascade (#121) -----

    @Test
    fun `removeSession fires onParentGone for a lease on that session`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        manager.removeSession(sshSid)

        assertEquals("parent removal must notify the dependent exactly once", 1, fired)
    }

    @Test
    fun `releaseTunnelDependent of last auto-opened dependent fires onParentGone (the #2 path)`() {
        // Connections-list disconnect: releaseTunnelDependent tears the SSH
        // down (tunnelOpened + no deps), which must cascade to close the tab.
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        manager.releaseTunnelDependent("vnc-1")

        assertEquals(1, fired)
        assertNull(manager.getSession(sshSid))
    }

    @Test
    fun `reconnect-style status changes do NOT fire onParentGone`() {
        // Only true removeSession fires the callback; an in-place status flip
        // (what reconnect does) must leave a working tab alone.
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        manager.updateStatus(sshSid, SshSessionManager.SessionState.Status.RECONNECTING)
        manager.updateStatus(sshSid, SshSessionManager.SessionState.Status.CONNECTED)

        assertEquals("a network blip must not close the tab", 0, fired)
        assertNotNull(manager.getSession(sshSid))
    }

    @Test
    fun `lease close is idempotent and prevents a later parent-gone fire`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        val lease = manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        lease.close()
        lease.close() // idempotent
        // Closing released the sole dependent → SSH already torn down.
        assertNull(manager.getSession(sshSid))
        // A subsequent removeSession (e.g. a racing teardown) must not re-fire.
        manager.removeSession(sshSid)
        assertEquals("consumer-initiated close must not also fire onParentGone", 0, fired)
    }

    @Test
    fun `teardownTunnelDependent fires lease even when the shared SSH survives`() {
        // VNC reuses a user-opened SSH (tunnelOpened = false). Disconnecting
        // the VNC from the connections list must close its tab (fire the
        // lease) WITHOUT killing the shared SSH session (#121 shared-tunnel).
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.attachTunnelDependent(sshSid, "vnc-1") // reused — tunnelOpened stays false
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        val torn = manager.teardownTunnelDependent("vnc-1")

        assertEquals("the tab lease must fire", 1, fired)
        assertTrue("shared user SSH must survive", torn.isEmpty())
        assertNotNull(manager.getSession(sshSid))
    }

    @Test
    fun `teardownTunnelDependent tears down a solely-tunnel SSH and fires the lease`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        val torn = manager.teardownTunnelDependent("vnc-1")

        assertEquals(1, fired)
        assertEquals(listOf(sshSid), torn)
        assertNull(manager.getSession(sshSid))
    }

    @Test
    fun `jump-host cascade fires the dependent session's lease`() {
        // B uses A as its jump host. Removing A cascades to B (removeSession),
        // which must fire B's lease so a tab tunneling over B closes too.
        val ca = mockk<SshClient>(relaxed = true)
        val cb = mockk<SshClient>(relaxed = true)
        val a = manager.registerSession("jump", "A", ca)
        val b = manager.registerSession("leaf", "B", cb)
        manager.setJumpSessionId(b, a)
        manager.markTunnelOpened(b, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(b, "vnc-1", localForwardPort = null) { fired++ }

        manager.removeSession(a)

        assertEquals(1, fired)
        assertNull(manager.getSession(b))
    }

    @Test
    fun `closeShellOrRemoveSession keeps jump host alive and closes only its shell`() {
        // #764: closing the terminal tab for jump host A while dependent B is
        // connected through A must tear down only A's shell/terminal attachment,
        // leaving A's SSH transport CONNECTED and B untouched.
        val ca = mockk<SshClient>(relaxed = true) { every { isConnected } returns true }
        val cb = mockk<SshClient>(relaxed = true) { every { isConnected } returns true }
        val shell = mockk<ShellChannel>(relaxed = true) { every { isConnected } returns true }
        val term = mockk<TerminalSession>(relaxed = true)
        val a = manager.registerSession("jump", "A", ca)
        manager.updateStatus(a, SshSessionManager.SessionState.Status.CONNECTED)
        manager.attachShellChannel(a, shell)
        manager.attachTerminalSession(a, term)

        val b = manager.registerSession("leaf", "B", cb)
        manager.setJumpSessionId(b, a)
        manager.updateStatus(b, SshSessionManager.SessionState.Status.CONNECTED)

        manager.closeShellOrRemoveSession(a)

        assertEquals(SshSessionManager.SessionState.Status.CONNECTED, manager.getSession(b)?.status)
        val stateA = manager.getSession(a)
        assertNotNull("jump carrier must stay registered", stateA)
        assertEquals(SshSessionManager.SessionState.Status.CONNECTED, stateA!!.status)
        assertNull("shellChannel must be cleared", stateA.shellChannel)
        assertNull("terminalSession must be cleared", stateA.terminalSession)
        verify(timeout = 1000, exactly = 1) { term.close() }
        verify(timeout = 1000, exactly = 1) { shell.disconnect() }
        verify(exactly = 0) { ca.disconnect() }
    }

    @Test
    fun `closeShellOrRemoveSession removes standalone session completely`() {
        val ca = mockk<SshClient>(relaxed = true)
        val a = manager.registerSession("solo", "A", ca)
        manager.updateStatus(a, SshSessionManager.SessionState.Status.CONNECTED)

        manager.closeShellOrRemoveSession(a)

        assertNull(manager.getSession(a))
        verify(timeout = 1000, exactly = 1) { ca.disconnect() }
    }

    @Test
    fun `removing last jump dependent tears down jump host whose shell was closed`() {
        // After jump host A's terminal tab is closed via closeShellOrRemoveSession (#764),
        // A stays alive only as a jump carrier for B. Once B is removed, A must also
        // be torn down so it does not stay stuck CONNECTED with no terminal tab.
        val ca = mockk<SshClient>(relaxed = true) { every { isConnected } returns true }
        val cb = mockk<SshClient>(relaxed = true) { every { isConnected } returns true }
        val shell = mockk<ShellChannel>(relaxed = true) { every { isConnected } returns true }
        val term = mockk<TerminalSession>(relaxed = true)
        val a = manager.registerSession("jump", "A", ca)
        manager.updateStatus(a, SshSessionManager.SessionState.Status.CONNECTED)
        manager.attachShellChannel(a, shell)
        manager.attachTerminalSession(a, term)

        val b = manager.registerSession("leaf", "B", cb)
        manager.setJumpSessionId(b, a)
        manager.updateStatus(b, SshSessionManager.SessionState.Status.CONNECTED)

        manager.closeShellOrRemoveSession(a)
        assertNotNull("A stays alive while B rides it", manager.getSession(a))

        manager.closeShellOrRemoveSession(b)

        assertNull("B should be removed", manager.getSession(b))
        assertNull("jump host A should be torn down once its last dependent B closes", manager.getSession(a))
        verify(timeout = 1000, exactly = 1) { ca.disconnect() }
        verify(timeout = 1000, exactly = 1) { cb.disconnect() }
    }

    @Test
    fun `removeAllSessionsForProfile cascades to jump dependents and fires lease parentGone`() {
        val ca = mockk<SshClient>(relaxed = true)
        val cb = mockk<SshClient>(relaxed = true)
        val a = manager.registerSession("jump-profile", "A", ca)
        val b = manager.registerSession("leaf-profile", "B", cb)
        manager.setJumpSessionId(b, a)
        var fired = 0
        manager.acquireTunnelLease(a, "vnc-1", localForwardPort = null) { fired++ }

        manager.removeAllSessionsForProfile("jump-profile")

        assertNull("jump session A must be removed", manager.getSession(a))
        assertNull("dependent session B must cascade-remove", manager.getSession(b))
        assertEquals("tunnel lease on A must fire onParentGone", 1, fired)
        verify(timeout = 1000, exactly = 1) { ca.disconnect() }
        verify(timeout = 1000, exactly = 1) { cb.disconnect() }
    }

    @Test
    fun `tearDown disconnects shared client when only DISCONNECTED sibling remains`() {
        val shared = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("p1", "primary", shared)
        val s2 = manager.registerSession("p1", "reused", shared)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.DISCONNECTED)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

        manager.removeSession(s2)

        verify(timeout = 1000, exactly = 1) { shared.disconnect() }
    }

    // --- #150 reconnect policy gating -----------------------------------

    @Test
    fun `requestReconnectAll skips session whose policy disables autoReconnect`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "S1", client)
        manager.storeConnectionConfig(
            sessionId = sid,
            config = configWithPolicy(autoReconnect = false),
            sessionMgr = SessionManager.NONE,
        )
        manager.updateStatus(sid, SshSessionManager.SessionState.Status.DISCONNECTED)

        manager.requestReconnectAll()

        // Status must stay DISCONNECTED — no reconnect attempt fired.
        assertEquals(
            SshSessionManager.SessionState.Status.DISCONNECTED,
            manager.getSession(sid)!!.status,
        )
    }

    @Test
    fun `requestReconnectAll skips session whose policy disables onNetworkChange`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "S1", client)
        manager.storeConnectionConfig(
            sessionId = sid,
            config = configWithPolicy(autoReconnect = true, onNetworkChange = false),
            sessionMgr = SessionManager.NONE,
        )
        manager.updateStatus(sid, SshSessionManager.SessionState.Status.DISCONNECTED)

        manager.requestReconnectAll()

        // autoReconnect=true alone must NOT trigger network-change reconnect.
        assertEquals(
            SshSessionManager.SessionState.Status.DISCONNECTED,
            manager.getSession(sid)!!.status,
        )
    }

    @Test
    fun `requestReconnectAll honours session whose policy enables both flags`() {
        val client = mockk<SshClient>(relaxed = true)
        every { client.connectBlocking(any(), any(), any()) } throws RuntimeException("offline")
        val sid = manager.registerSession("p1", "S1", client)
        manager.storeConnectionConfig(
            sessionId = sid,
            config = configWithPolicy(autoReconnect = true, onNetworkChange = true),
            sessionMgr = SessionManager.NONE,
        )
        manager.updateStatus(sid, SshSessionManager.SessionState.Status.DISCONNECTED)

        manager.requestReconnectAll()

        // The reconnect attempt fired; with the test config the session is
        // either RECONNECTING (still in the backoff loop) or back to
        // DISCONNECTED after the attempt failed. Either way, the policy
        // didn't gate it out — that's what we're verifying.
        val status = manager.getSession(sid)!!.status
        assertTrue(
            "expected RECONNECTING or DISCONNECTED, got $status",
            status == SshSessionManager.SessionState.Status.RECONNECTING ||
                status == SshSessionManager.SessionState.Status.DISCONNECTED,
        )
    }

    // --- Foreground liveness probe (#186 follow-up: reconnect after background) ---

    private fun connectedSession(
        client: SshClient,
        headless: Boolean = false,
        autoReconnect: Boolean = true,
    ): String {
        val sid = manager.registerSession("p1", "S1", client, headless = headless)
        manager.storeConnectionConfig(
            sessionId = sid,
            config = configWithPolicy(autoReconnect = autoReconnect),
            sessionMgr = SessionManager.NONE,
        )
        manager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)
        return sid
    }

    @Test
    fun `probeAndReconnectStale leaves a live CONNECTED session untouched`() {
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.isAlive(any()) } returns true
        val sid = connectedSession(client)

        runBlocking { manager.probeAndReconnectStale() }

        coVerify { client.isAlive(any()) }
        assertEquals(
            SshSessionManager.SessionState.Status.CONNECTED,
            manager.getSession(sid)!!.status,
        )
    }

    @Test
    fun `probeAndReconnectStale reconnects a CONNECTED session whose probe fails`() {
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.isAlive(any()) } returns false
        // Keep the subsequent reconnect attempt from doing real I/O.
        every { client.connectBlocking(any(), any(), any()) } throws RuntimeException("offline")
        val sid = connectedSession(client)

        runBlocking { manager.probeAndReconnectStale() }

        coVerify { client.isAlive(any()) }
        // A dead probe must move the session off CONNECTED so requestReconnect
        // (which no-ops on CONNECTED) can take it. It is now DISCONNECTED or, if
        // the ioExecutor already picked it up, RECONNECTING.
        val status = manager.getSession(sid)!!.status
        assertTrue(
            "expected DISCONNECTED or RECONNECTING, got $status",
            status == SshSessionManager.SessionState.Status.DISCONNECTED ||
                status == SshSessionManager.SessionState.Status.RECONNECTING,
        )
    }

    @Test
    fun `probeAndReconnectStale skips headless tunnel sessions`() {
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.isAlive(any()) } returns false
        val sid = connectedSession(client, headless = true)

        runBlocking { manager.probeAndReconnectStale() }

        // McpTunnelManager's own watchdog owns headless sessions — never probe.
        coVerify(exactly = 0) { client.isAlive(any()) }
        assertEquals(
            SshSessionManager.SessionState.Status.CONNECTED,
            manager.getSession(sid)!!.status,
        )
    }

    @Test
    fun `probeAndReconnectStale skips sessions whose policy disables autoReconnect`() {
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.isAlive(any()) } returns false
        val sid = connectedSession(client, autoReconnect = false)

        runBlocking { manager.probeAndReconnectStale() }

        coVerify(exactly = 0) { client.isAlive(any()) }
        assertEquals(
            SshSessionManager.SessionState.Status.CONNECTED,
            manager.getSession(sid)!!.status,
        )
    }

    @Test
    fun `probeAndReconnectStale ignores non-CONNECTED sessions`() {
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.isAlive(any()) } returns false
        val sid = manager.registerSession("p1", "S1", client) // stays CONNECTING
        manager.storeConnectionConfig(
            sessionId = sid,
            config = configWithPolicy(autoReconnect = true),
            sessionMgr = SessionManager.NONE,
        )

        runBlocking { manager.probeAndReconnectStale() }

        coVerify(exactly = 0) { client.isAlive(any()) }
        assertEquals(
            SshSessionManager.SessionState.Status.CONNECTING,
            manager.getSession(sid)!!.status,
        )
    }

    // --- ExitOnForwardFailure: critical port forwards (MCP tunnel) -------

    @Test
    fun `applyPortForwards returns false when a critical REMOTE forward fails to bind`() {
        val client = mockk<SshClient>(relaxed = true)
        every { client.setPortForwardingR(any(), any(), any(), any()) } throws
            RuntimeException("remote port forwarding failed for listen port 8730")
        val sid = manager.registerSession("p1", "S1", client)

        val ok = manager.applyPortForwards(sid, listOf(criticalRemoteForward(8730)))

        assertFalse("a failed critical forward must report failure", ok)
        // The failed forward must not be recorded as active.
        assertTrue(manager.getSession(sid)!!.activeForwards.isEmpty())
    }

    @Test
    fun `applyPortForwards returns true when a non-critical forward fails`() {
        val client = mockk<SshClient>(relaxed = true)
        every { client.setPortForwardingR(any(), any(), any(), any()) } throws
            RuntimeException("bind failed")
        val sid = manager.registerSession("p1", "S1", client)

        val ok = manager.applyPortForwards(
            sid,
            listOf(criticalRemoteForward(8730).copy(critical = false)),
        )

        assertTrue("a non-critical failure keeps best-effort semantics", ok)
    }

    @Test
    fun `applyPortForwards returns true when the critical forward binds`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "S1", client)

        val ok = manager.applyPortForwards(sid, listOf(criticalRemoteForward(8730)))

        assertTrue(ok)
        assertEquals(1, manager.getSession(sid)!!.activeForwards.size)
    }

    @Test
    fun `registerSession marks headless flag`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "MCP tunnel", client, headless = true)
        assertTrue(manager.getSession(sid)!!.headless)
    }

    @Test
    fun `findRemoteForwardSession skips the headless MCP tunnel and finds the interactive session`() {
        val tunnelClient = mockk<SshClient>(relaxed = true)
        val replClient = mockk<SshClient>(relaxed = true)
        val tunnelSid = manager.registerSession("p-tunnel", "MCP tunnel", tunnelClient, headless = true)
        val replSid = manager.registerSession("p-repl", "Laptop", replClient)
        manager.applyPortForwards(tunnelSid, listOf(criticalRemoteForward(8730)))
        manager.applyPortForwards(replSid, listOf(criticalRemoteForward(8730)))

        // The interactive (non-headless) session is the typing target.
        assertEquals(replSid, manager.findRemoteForwardSession(8730))
    }

    @Test
    fun `findRemoteForwardSession returns null when only the headless tunnel carries the forward`() {
        val tunnelClient = mockk<SshClient>(relaxed = true)
        val tunnelSid = manager.registerSession("p-tunnel", "MCP tunnel", tunnelClient, headless = true)
        manager.applyPortForwards(tunnelSid, listOf(criticalRemoteForward(8730)))

        assertNull(manager.findRemoteForwardSession(8730))
    }

    private fun criticalRemoteForward(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = "mcp-reverse-tunnel",
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = "127.0.0.1",
        bindPort = port,
        targetHost = "127.0.0.1",
        targetPort = port,
        critical = true,
    )

    // ---- connection reuse (a 2nd session sharing one live SshClient) ----

    @Test
    fun `isClientShared is false for a lone session and true when a client is shared`() {
        val shared = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("p1", "primary", shared)
        assertFalse(manager.isClientShared(s1))

        // Second session reusing the SAME live client (connection reuse).
        val s2 = manager.registerSession("p1", "reused", shared)
        assertTrue(manager.isClientShared(s1))
        assertTrue(manager.isClientShared(s2))

        // A session on its own client is not shared.
        val s3 = manager.registerSession("p2", "other", mockk(relaxed = true))
        assertFalse(manager.isClientShared(s3))
    }

    @Test
    fun `storeReuseConfig disables auto-reconnect and copies host from the primary session`() {
        val shared = mockk<SshClient>(relaxed = true)
        val primary = manager.registerSession("p1", "primary", shared)
        manager.storeConnectionConfig(primary, configWithPolicy(autoReconnect = true), SessionManager.TMUX)

        val reused = manager.registerSession("p1", "reused", shared)
        manager.storeReuseConfig(reused, "p1", SessionManager.TMUX)

        val cfg = manager.getSession(reused)!!.connectionConfig!!
        assertFalse("a reuse session must not auto-reconnect (the primary owns reconnect)", cfg.reconnectPolicy.autoReconnect)
        assertEquals("test.example", cfg.host)
    }

    @Test
    fun `tearDown keeps a shared client alive until its last session is removed`() {
        val shared = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("p1", "primary", shared)
        val s2 = manager.registerSession("p1", "reused", shared)

        // Removing the first of two sharers must NOT disconnect the shared client.
        manager.removeSession(s1)
        Thread.sleep(200)
        verify(exactly = 0) { shared.disconnect() }

        // Removing the last sharer tears the underlying connection down.
        manager.removeSession(s2)
        Thread.sleep(200)
        verify(exactly = 1) { shared.disconnect() }
    }

    // ---- awaitReusableClient (app-launch connect-race coordination) ----

    @Test
    fun `awaitReusableClient returns null when nothing is live or in flight`() = runBlocking {
        assertNull(manager.awaitReusableClient("nobody", timeoutMs = 200))
    }

    @Test
    fun `awaitReusableClient returns a connected client immediately`() = runBlocking {
        val c = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "s", c)
        manager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)
        assertSame(c, manager.awaitReusableClient("p1", timeoutMs = 200))
    }

    @Test
    fun `awaitReusableClient waits for an in-flight connect then returns its client`() = runBlocking {
        val carrier = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "MCP carrier", carrier) // CONNECTING
        // The carrier reaches CONNECTED shortly after the wait begins.
        val flip = launch {
            delay(100)
            manager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)
        }
        val result = manager.awaitReusableClient("p1", timeoutMs = 5000)
        flip.join()
        assertSame("must reuse the in-flight connect's client, not dial", carrier, result)
    }

    @Test
    fun `awaitReusableClient returns null when the in-flight connect fails`() = runBlocking {
        val carrier = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "carrier", carrier) // CONNECTING
        val flip = launch {
            delay(50)
            manager.updateStatus(sid, SshSessionManager.SessionState.Status.ERROR)
        }
        val result = manager.awaitReusableClient("p1", timeoutMs = 5000)
        flip.join()
        assertNull("a failed in-flight connect yields no reusable client", result)
    }

    @Test
    fun `registerSession reaps a dead same-profile same-kind session`() {
        val old = manager.registerSession("p1", "old", mockk(relaxed = true))
        manager.updateStatus(old, SshSessionManager.SessionState.Status.DISCONNECTED)
        val fresh = manager.registerSession("p1", "new", mockk(relaxed = true))

        assertNull("stale session should be reaped", manager.getSession(old))
        assertNotNull(manager.getSession(fresh))
    }

    @Test
    fun `registerSession keeps a live same-profile session`() {
        val live = manager.registerSession("p1", "live", mockk(relaxed = true))
        manager.updateStatus(live, SshSessionManager.SessionState.Status.CONNECTED)
        val fresh = manager.registerSession("p1", "second", mockk(relaxed = true))

        assertNotNull("a connected session must never be reaped", manager.getSession(live))
        assertNotNull(manager.getSession(fresh))
    }

    @Test
    fun `registerSession does not reap a dead session of a different kind`() {
        val term = manager.registerSession("p1", "term", mockk(relaxed = true), headless = false)
        manager.updateStatus(term, SshSessionManager.SessionState.Status.DISCONNECTED)
        // A headless tunnel reconnecting must not yank a dead interactive tab.
        val tunnel = manager.registerSession("p1", "tunnel", mockk(relaxed = true), headless = true)

        assertNotNull(manager.getSession(term))
        assertNotNull(manager.getSession(tunnel))
    }

    @Test
    fun `registerSession does not reap dead sessions of other profiles`() {
        val other = manager.registerSession("p1", "a", mockk(relaxed = true))
        manager.updateStatus(other, SshSessionManager.SessionState.Status.DISCONNECTED)
        val fresh = manager.registerSession("p2", "b", mockk(relaxed = true))

        assertNotNull(manager.getSession(other))
        assertNotNull(manager.getSession(fresh))
    }

    @Test
    fun `headless session does not mark profile connected or get removed by user profile disconnect`() {
        val headlessClient = mockk<SshClient>(relaxed = true)
        val headlessSid = manager.registerSession("p1", "MCP tunnel", headlessClient, headless = true)
        manager.updateStatus(headlessSid, SshSessionManager.SessionState.Status.CONNECTED)

        assertFalse("headless tunnel must not mark user profile connected", manager.isProfileConnected("p1"))
        assertNull("headless tunnel must not report user profile status", manager.getProfileStatus("p1"))
        assertTrue("getSessionsForProfile excludes headless by default", manager.getSessionsForProfile("p1").isEmpty())

        manager.removeAllSessionsForProfile("p1")
        assertNotNull("user profile disconnect must not kill background headless tunnel", manager.getSession(headlessSid))
    }

    @Test
    fun `applyPortForwards with selfHealOnBindFailure strips same remote port from sibling session activeForwards`() {
        val c1 = mockk<SshClient>(relaxed = true) { every { isConnected } returns true }
        val c2 = mockk<SshClient>(relaxed = true) { every { isConnected } returns true }
        val s1 = manager.registerSession("p1", "Tab 1", c1)
        val s2 = manager.registerSession("p1", "Tab 2", c2)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.CONNECTED)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

        val selfHealFwd = criticalRemoteForward(8730).copy(critical = false, selfHealOnBindFailure = true)
        assertTrue(manager.applyPortForwards(s1, listOf(selfHealFwd)))
        assertEquals(1, manager.getSession(s1)!!.activeForwards.size)

        // When MCP carrier moves to s2, s1 must lose the forward from activeForwards
        // so a later reconnect of s1 does not re-bind :8730 and kill s2's sshd.
        assertTrue(manager.applyPortForwards(s2, listOf(selfHealFwd)))
        assertEquals(1, manager.getSession(s2)!!.activeForwards.size)
        assertTrue(
            "s1 must no longer carry mcp-reverse-tunnel in activeForwards",
            manager.getSession(s1)!!.activeForwards.none { it.bindPort == 8730 },
        )
        verify { c1.delPortForwardingR(8730) }
    }

    @Test
    fun `removeSession tears down immediately without blocking behind an in-flight reconnect`() {
        val reconnectingClient = mockk<SshClient>(relaxed = true)
        val victimClient = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("p1", "Reconnecting", reconnectingClient)
        manager.storeConnectionConfig(s1, configWithPolicy(autoReconnect = true), SessionManager.NONE)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.DISCONNECTED)

        val s2 = manager.registerSession("p2", "Victim", victimClient)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

        // Start reconnect on s1 (sleeps 2000ms on reconnect executor).
        manager.requestReconnect(s1)
        Thread.sleep(100)

        // Removing s2 (and s1) must tear down within 300ms, not wait 2000ms for s1's sleep.
        manager.removeSession(s2)
        manager.removeSession(s1)

        verify(timeout = 400, exactly = 1) { victimClient.disconnect() }
        verify(timeout = 400, exactly = 1) { reconnectingClient.disconnect() }
    }

    @Test
    fun `attemptReconnect disconnects newClient and does not resurrect session when removeSession races mid-dial`() {
        val oldClient = mockk<SshClient>(relaxed = true)
        val newClient = mockk<SshClient>(relaxed = true)
        val dialStarted = java.util.concurrent.CountDownLatch(1)
        val finishDial = java.util.concurrent.CountDownLatch(1)

        every {
            newClient.connectBlocking(any(), any(), any(), any(), any(), any(), any())
        } answers {
            dialStarted.countDown()
            try {
                finishDial.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // removeSession interrupts the reconnect thread; simulate connectBlocking
                // either returning just as removeSession raced or throwing on interrupt.
            }
            null
        }
        manager.reconnectInitialDelayMs = 10L
        manager.reconnectClientFactory = { newClient }

        val s1 = manager.registerSession("p1", "Server", oldClient)
        manager.storeConnectionConfig(s1, configWithPolicy(autoReconnect = true), SessionManager.NONE)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.DISCONNECTED)

        manager.requestReconnect(s1)
        assertTrue("reconnect should enter connectBlocking", dialStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // User removes the session while connectBlocking is in flight.
        manager.removeSession(s1)
        finishDial.countDown()

        verify(timeout = 1000, exactly = 1) { oldClient.disconnect() }
        verify(timeout = 1000, atLeast = 1) { newClient.disconnect() }
        assertNull("removed session must not be resurrected", manager.getSession(s1))
    }

    @Test
    fun `applyPortForwards skips LOCAL and non-self-heal REMOTE forwards already bound on a connected sibling session`() {
        val c1 = mockk<SshClient>(relaxed = true) {
            every { isConnected } returns true
            every { setPortForwardingL(any(), any(), any(), any()) } answers { secondArg() }
        }
        val c2 = mockk<SshClient>(relaxed = true) {
            every { isConnected } returns true
        }
        val s1 = manager.registerSession("p1", "Tab 1", c1)
        val s2 = manager.registerSession("p1", "Tab 2", c2)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.CONNECTED)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

        val localFwd = SshSessionManager.PortForwardInfo(
            ruleId = "local-2222",
            type = SshSessionManager.PortForwardType.LOCAL,
            bindAddress = "127.0.0.1",
            bindPort = 2222,
            targetHost = "127.0.0.1",
            targetPort = 22,
        )
        assertTrue(manager.applyPortForwards(s1, listOf(localFwd)))
        assertEquals(1, manager.getSession(s1)!!.activeForwards.size)

        // Applying the same local port forward to s2 while s1 is CONNECTED must
        // skip it immediately instead of colliding in bindLocalWithRetry (3s per rule).
        assertTrue(manager.applyPortForwards(s2, listOf(localFwd)))
        verify(exactly = 0) { c2.setPortForwardingL(any(), eq(2222), any(), any()) }
        assertTrue(manager.getSession(s2)!!.activeForwards.isEmpty())
    }

    @Test
    fun `getConnectionConfigForProfile deep-copies PrivateKey passphrase and Multi auth so sibling tearDown does not zero jump carrier passphrase`() {
        val jumpClient = mockk<SshClient>(relaxed = true) { every { isConnected } returns true }
        val tab2Client = mockk<SshClient>(relaxed = true) { every { isConnected } returns true }
        val jumpId = manager.registerSession("jump-prof", "Server 1", jumpClient)
        val originalConfig = ConnectionConfig(
            host = "jump.example.com",
            port = 22,
            username = "root",
            authMethod = ConnectionConfig.AuthMethod.Multi(
                listOf(
                    ConnectionConfig.AuthMethod.PrivateKey(
                        keyBytes = byteArrayOf(1, 2, 3, 4),
                        passphrase = "secret-passphrase".toCharArray(),
                    ),
                    ConnectionConfig.AuthMethod.PrivateKeys(
                        listOf(
                            ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                                label = "k1",
                                keyBytes = byteArrayOf(5, 6, 7),
                                passphrase = "pool-secret".toByteArray(Charsets.UTF_8),
                            ),
                        ),
                    ),
                ),
            ),
        )
        manager.storeConnectionConfig(jumpId, originalConfig, SessionManager.NONE)
        manager.updateStatus(jumpId, SshSessionManager.SessionState.Status.CONNECTED)

        // Open a 2nd session on jump-prof using getConnectionConfigForProfile, then remove it
        // (which runs tearDown and zeroes the 2nd session's auth arrays).
        val (copiedConfig, _) = manager.getConnectionConfigForProfile("jump-prof")!!
        val tab2Id = manager.registerSession("jump-prof", "Server 1 (2)", tab2Client)
        manager.storeConnectionConfig(tab2Id, copiedConfig, SessionManager.NONE)
        manager.removeSession(tab2Id)

        // Wait for tearDown on ioExecutor to zero tab2's auth material.
        val copyMulti = copiedConfig.authMethod as ConnectionConfig.AuthMethod.Multi
        val copyPk = copyMulti.methods[0] as ConnectionConfig.AuthMethod.PrivateKey
        val deadline = System.currentTimeMillis() + 2_000
        while (copyPk.passphrase.any { it != '\u0000' } && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("removed sibling session's passphrase copy should be zeroed", copyPk.passphrase.all { it == '\u0000' })

        // The primary jump carrier's passphrase must remain intact for subsequent connections (#764).
        val (nextConfig, _) = manager.getConnectionConfigForProfile("jump-prof")!!
        val nextMulti = nextConfig.authMethod as ConnectionConfig.AuthMethod.Multi
        val nextPk = nextMulti.methods[0] as ConnectionConfig.AuthMethod.PrivateKey
        val nextPool = nextMulti.methods[1] as ConnectionConfig.AuthMethod.PrivateKeys
        assertEquals("secret-passphrase", String(nextPk.passphrase))
        org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3, 4), nextPk.keyBytes)
        assertEquals("pool-secret", String(nextPool.keys[0].passphrase!!, Charsets.UTF_8))
        org.junit.Assert.assertArrayEquals(byteArrayOf(5, 6, 7), nextPool.keys[0].keyBytes)
    }

    @Test
    fun `buildPendingCommands exports DISPLAY and PULSE_SERVER before and inside session manager`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p-media", "MediaHost", client)
        val cfg = ConnectionConfig(
            host = "media.example",
            port = 22,
            username = "u",
            sshOptions = ConnectionConfig.parseSshOptions(
                "HavenForwardX11 yes\nHavenForwardPulseAudio yes",
            ),
        )
        manager.storeConnectionConfig(sid, cfg, SessionManager.TMUX, postLoginCommand = "htop")

        val expectedExport = "export DISPLAY=localhost:10.0 ; export PULSE_SERVER=tcp:127.0.0.1:14713"
        val plainCmds = manager.buildPendingCommands(sid, SessionManager.NONE, "htop", beforeSessionManager = false)
        assertEquals(listOf(expectedExport, "htop"), plainCmds)

        val tmuxCmds = manager.buildPendingCommands(sid, SessionManager.TMUX, "htop", beforeSessionManager = false)
        assertEquals(4, tmuxCmds.size)
        assertEquals(expectedExport, tmuxCmds[0])
        assertTrue(tmuxCmds[1].contains("tmux"))
        assertEquals(expectedExport, tmuxCmds[2])
        assertEquals("htop", tmuxCmds[3])
    }

    private fun configWithPolicy(
        autoReconnect: Boolean = true,
        maxAttempts: Int = 5,
        onNetworkChange: Boolean = true,
    ): ConnectionConfig = ConnectionConfig(
        host = "test.example",
        port = 22,
        username = "u",
        reconnectPolicy = ConnectionConfig.ReconnectPolicy(
            autoReconnect = autoReconnect,
            maxAttempts = maxAttempts,
            onNetworkChange = onNetworkChange,
        ),
    )
}
