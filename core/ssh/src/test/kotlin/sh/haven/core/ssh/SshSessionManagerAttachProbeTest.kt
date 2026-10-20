package sh.haven.core.ssh

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pin the post-attach session-manager verification probe (#731): when the
 * shell comes up but the manager's list command fails or doesn't show the
 * named session, a notice is published so the terminal can say why the user
 * landed in a plain shell. A healthy attach (name listed) publishes nothing,
 * and the retry pass must not false-positive on a slow-but-working attach.
 */
class SshSessionManagerAttachProbeTest {

    private fun newManager() = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))

    private fun config() = ConnectionConfig(host = "h", username = "u")

    /** Register + configure a TMUX-wrapped session named "work". */
    private fun tmuxSession(mgr: SshSessionManager, client: SshClient): String {
        val sessionId = mgr.registerSession("p1", "work", client)
        mgr.storeConnectionConfig(sessionId, config(), SessionManager.TMUX)
        return sessionId
    }

    private fun awaitNotice(mgr: SshSessionManager, sessionId: String, timeoutMs: Long = 3000L): SessionAttachNotice? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mgr.attachNoticeFlow(sessionId).value?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    @Test
    fun `ManagerUnavailable when the list command exits non-zero`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.execCommand(any()) } returns ExecResult(exitStatus = 1, stdout = "", stderr = "tmux: not found")
        val sessionId = tmuxSession(mgr, client)

        mgr.verifySessionManagerAttached(sessionId, graceMs = 0)

        val notice = awaitNotice(mgr, sessionId)
        assertTrue("expected ManagerUnavailable, got $notice", notice is SessionAttachNotice.ManagerUnavailable)
        assertEquals(SessionManager.TMUX, (notice as SessionAttachNotice.ManagerUnavailable).manager)
    }

    @Test
    fun `SessionMissing when the named session is absent from both passes`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.execCommand(any()) } returns ExecResult(exitStatus = 0, stdout = "other\n", stderr = "")
        val sessionId = tmuxSession(mgr, client)

        mgr.verifySessionManagerAttached(sessionId, graceMs = 0)

        val notice = awaitNotice(mgr, sessionId)
        assertTrue("expected SessionMissing, got $notice", notice is SessionAttachNotice.SessionMissing)
        assertEquals("work", (notice as SessionAttachNotice.SessionMissing).sessionName)
    }

    @Test
    fun `no notice when the named session is listed`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.execCommand(any()) } returns ExecResult(exitStatus = 0, stdout = "work\nother\n", stderr = "")
        val sessionId = tmuxSession(mgr, client)

        mgr.verifySessionManagerAttached(sessionId, graceMs = 0)

        // Give the probe time to run both passes, then assert nothing published.
        Thread.sleep(400)
        assertNull(mgr.attachNoticeFlow(sessionId).value)
    }

    @Test
    fun `retry pass rescues a slow attach (empty first list, named second)`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.execCommand(any()) } returnsMany listOf(
            ExecResult(exitStatus = 0, stdout = "", stderr = ""),
            ExecResult(exitStatus = 0, stdout = "work\n", stderr = ""),
        )
        val sessionId = tmuxSession(mgr, client)

        mgr.verifySessionManagerAttached(sessionId, graceMs = 0)

        Thread.sleep(400)
        assertNull("slow-but-working attach must not raise a notice", mgr.attachNoticeFlow(sessionId).value)
        coVerify(exactly = 2) { client.execCommand(any()) }
    }

    @Test
    fun `no probe for plain-shell sessions`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = mgr.registerSession("p1", "work", client)
        mgr.storeConnectionConfig(sessionId, config(), SessionManager.NONE)

        mgr.verifySessionManagerAttached(sessionId, graceMs = 0)

        Thread.sleep(200)
        assertNull(mgr.attachNoticeFlow(sessionId).value)
        coVerify(exactly = 0) { client.execCommand(any()) }
    }

    @Test
    fun `no probe when a custom session command replaces the start template`() = runBlocking {
        // #731 follow-up: the reporter's custom `tmux new-session -A -s {name}`
        // attached fine on a Windows OpenSSH host, but the POSIX list probe
        // fails through cmd/PowerShell and false-positived the banner. With an
        // override the built-in list command can't verify the attach, so the
        // probe must not run at all.
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.execCommand(any()) } returns ExecResult(exitStatus = 1, stdout = "", stderr = "tmux: command not found")
        val sessionId = mgr.registerSession("p1", "work", client)
        mgr.storeConnectionConfig(sessionId, config(), SessionManager.TMUX, sessionCommandOverride = "tmux new-session -A -s {name}")

        mgr.verifySessionManagerAttached(sessionId, graceMs = 0)

        Thread.sleep(200)
        assertNull("custom command must suppress the unverifiable probe", mgr.attachNoticeFlow(sessionId).value)
        coVerify(exactly = 0) { client.execCommand(any()) }
    }

    @Test
    fun `dismissAttachNotice clears a raised notice`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.execCommand(any()) } returns ExecResult(exitStatus = 1, stdout = "", stderr = "")
        val sessionId = tmuxSession(mgr, client)

        mgr.verifySessionManagerAttached(sessionId, graceMs = 0)
        awaitNotice(mgr, sessionId) ?: return@runBlocking fail("no notice published")

        mgr.dismissAttachNotice(sessionId)
        assertNull(mgr.attachNoticeFlow(sessionId).value)
    }

    @Test
    fun `notice is cleared when the session is removed`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        coEvery { client.execCommand(any()) } returns ExecResult(exitStatus = 1, stdout = "", stderr = "")
        val sessionId = tmuxSession(mgr, client)

        mgr.verifySessionManagerAttached(sessionId, graceMs = 0)
        awaitNotice(mgr, sessionId) ?: return@runBlocking fail("no notice published")

        mgr.removeSession(sessionId)
        // Fresh flow after removal starts clean.
        assertNull(mgr.attachNoticeFlow(sessionId).value)
    }
}
